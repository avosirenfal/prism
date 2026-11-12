package prism.browser

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex

/** A drag under way in a [ReorderableColumn]: the entry [from], and how far the pointer has moved it, in pixels. */
@Immutable
private data class Dragging(
	val from: Int,
	val by: Float,
)

/**
 * [items] one under another, each drawn by [content], that the pointer reorders: an entry held by its handle (the
 * modifier [content] is given, for whatever part of it is held) is dragged up or down, the others making room for it
 * as it goes, and let go where it is to be; [onMove] is then told where from and where to. An entry [movable] says
 * is not has no handle. What is shown is [items] as they are: the order changes only as the caller's does.
 */
@Composable
fun <T> ReorderableColumn(
	items: List<T>,
	key: (T) -> Any,
	onMove: (from: Int, to: Int) -> Unit,
	modifier: Modifier = Modifier,
	movable: (T) -> Boolean = { true },
	content: @Composable (item: T, handle: Modifier, dragged: Boolean) -> Unit,
) {
	var dragging by remember { mutableStateOf<Dragging?>(null) }
	var heights by remember { mutableStateOf(emptyMap<Any, Int>()) }
	val moved by rememberUpdatedState(onMove)
	val latest by rememberUpdatedState(items)

	/** Where an entry dragged [by] pixels from [from] would go: past each neighbour it has covered half of. */
	fun targetOf(from: Int, by: Float): Int {
		fun heightAt(at: Int) = heights[key(latest[at])]?.toFloat() ?: Float.MAX_VALUE

		tailrec fun down(at: Int, left: Float): Int =
			if (at < latest.lastIndex && left >= heightAt(at + 1) / 2)
				down(at + 1, left - heightAt(at + 1))
			else
				at

		tailrec fun up(at: Int, left: Float): Int =
			if (at > 0 && left >= heightAt(at - 1) / 2)
				up(at - 1, left - heightAt(at - 1))
			else
				at

		return if (by >= 0)
			down(from, by)
		else
			up(from, -by)
	}

	Column(modifier) {
		items.forEachIndexed { index, item ->
			key(key(item)) {
				val drag = dragging
				val target = drag?.let { targetOf(it.from, it.by) }
				val height = drag?.let { heights[key(latest[it.from])] } ?: 0
				// The dragged entry follows the pointer; those it has passed make room for it.
				val shift =
					when {
						drag == null || target == null -> 0f
						index == drag.from -> drag.by
						index in (drag.from + 1)..target -> -height.toFloat()
						index in target until drag.from -> height.toFloat()
						else -> 0f
					}

				val handle =
					if (!movable(item))
						Modifier
					else
						Modifier.pointerInput(index) {
							detectDragGestures(
								onDragStart = { dragging = Dragging(index, 0f) },
								onDragEnd = {
									dragging?.let { done ->
										val to = targetOf(done.from, done.by)

										if (to != done.from)
											moved(done.from, to)
									}
									dragging = null
								},
								onDragCancel = { dragging = null },
							) { change, amount ->
								change.consume()
								dragging = dragging?.let { it.copy(by = it.by + amount.y) }
							}
						}

				Box(
					Modifier
						.onSizeChanged { heights = heights + (key(item) to it.height) }
						.zIndex(if (drag?.from == index) 1f else 0f)
						.graphicsLayer { translationY = shift },
				) {
					content(item, handle, drag?.from == index)
				}
			}
		}
	}
}
