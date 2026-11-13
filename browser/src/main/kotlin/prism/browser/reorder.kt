package prism.browser

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.zIndex
import prism.preferences.Moved
import prism.preferences.moved

/*
 * A list put in order by dragging. An entry is held by its handle and dragged up or down, the others making room for it,
 * and let go where it is to be. Where several can be moved together, Shift and a click choose an entry (or no longer);
 * a chosen one dragged brings every one chosen with it, gathered under it in their order, and they are let go together.
 * A click without Shift, or another entry dragged, lets the choice go.
 */

/** How a [ReorderableColumn]'s entries are moved: one at a time, or as several chosen together by Shift and a click. */
enum class Moving {
	One,
	Several,
}

/** The entries being dragged (their places, in order), and the one of them held. */
@Immutable
private data class Lifted(
	val moved: List<Int>,
	val held: Int,
)

/**
 * [items] one under another, each drawn by [content], that the pointer reorders: an entry held by its handle (the
 * modifier [content] is given, for whatever part of it is held) is dragged, and [onMove] told what was moved where once
 * it is let go; as [moving] says, with any others chosen by Shift and a click. An entry [movable] says is not has no
 * handle, and is not chosen. What is shown is [items] as they are: the order changes only as the caller's does. In a
 * card, [divided] sets a line between each two entries, as a card's rows have, which moves with the entry under it.
 * [content] is told whether its entry is lit: chosen, or being dragged.
 */
@Composable
fun <T> ReorderableColumn(
	items: List<T>,
	key: (T) -> Any,
	moving: Moving,
	onMove: (Moved) -> Unit,
	modifier: Modifier = Modifier,
	movable: (T) -> Boolean = { true },
	divided: Boolean = false,
	content: @Composable (item: T, handle: Modifier, lit: Boolean) -> Unit,
) {
	var lifted by remember { mutableStateOf<Lifted?>(null) }
	// How far the pointer has dragged what is lifted: read only where it is drawn, so a drag only redraws.
	var travel by remember { mutableFloatStateOf(0f) }
	var heights by remember { mutableStateOf(emptyMap<Any, Int>()) }
	var chosen by remember { mutableStateOf(emptySet<Any>()) }
	val moved by rememberUpdatedState(onMove)
	val latest by rememberUpdatedState(items)
	val keyOf by rememberUpdatedState(key)
	// Where the column is: what a drag is measured in, as the entry dragged moves under the pointer.
	val column = remember { Placement() }

	fun heightAt(at: Int): Float = heights[keyOf(latest[at])]?.toFloat() ?: 0f

	fun choosable(at: Int): Boolean = moving == Moving.Several && movable(latest[at])

	/** The places of the entries chosen, in order, of those there still are. */
	fun chosenPlaces(): List<Int> =
		latest.indices.filter {
			keyOf(latest[it]) in chosen &&
				choosable(it)
		}

	/** The tops of [places] laid one under another from 0, and where the last ends. */
	fun topsOf(places: List<Int>): List<Float> =
		places.runningFold(0f) { top, at ->
			top +
				heightAt(at)
		}

	/** Where [what] is dragged to: in a new order, the drawn top of each entry ([Layout.tops]) and where they would go. */
	fun layoutOf(what: Lifted): Layout {
		val tops = topsOf(latest.indices.toList())
		val rest = latest.indices.filter { it !in what.moved }
		val before = what.moved.takeWhile { it != what.held }
		// What is lifted is gathered, in order, around the entry held, which follows the pointer.
		val blockTop = tops[what.held] + travel - topsOf(before).last()
		val restTops = topsOf(rest)
		// It goes past each of the rest whose middle it has reached; those it has passed make room for it.
		val to = rest.indices.count { restTops[it] + heightAt(rest[it]) / 2 <= blockTop }
		val block = topsOf(what.moved).last()
		val restDrawn =
			rest.mapIndexed { place, at ->
				at to
					restTops[place] + if (place >= to) block else 0f
			}
		val liftedDrawn = what.moved.zip(topsOf(what.moved)) { at, top -> at to blockTop + top }

		return Layout(tops, (restDrawn + liftedDrawn).toMap(), Moved(what.moved, to))
	}

	// Worked out once a frame of a drag, for every entry to be drawn by.
	val dragged by remember { derivedStateOf { lifted?.let(::layoutOf) } }

	fun letGo() {
		dragged?.move?.let { move ->
			if (latest.moved(move).map(keyOf) != latest.map(keyOf))
				moved(move)
		}
		lifted = null
		travel = 0f
	}

	Column(modifier.onPlaced { column.at = it }) {
		items.forEachIndexed { index, item ->
			key(key(item)) {
				val itsKey = key(item)
				val lift = lifted
				val up = lift?.moved?.contains(index) == true
				val isChosen = itsKey in chosen && choosable(index)
				val grip = remember { Placement() }

				/** How far down the column [position], where the grip is, is. */
				fun downOf(position: Offset): Float? =
					column.at?.let { at -> grip.at?.let { at.localPositionOf(it, position).y } }

				val handle =
					if (!movable(item))
						Modifier
					else
						Modifier.onPlaced { grip.at = it }.pointerInput(index, moving) {
							// Where the drag began, down the column: the grip moves with the entry, the column does not.
							var from = 0f

							detectDragGestures(
								onDragStart = { start ->
									val group = chosenPlaces()

									from = downOf(start) ?: 0f

									lifted =
										if (index in group)
											Lifted(group, index)
										else
											Lifted(listOf(index), index).also { chosen = emptySet() }
									travel = 0f
								},
								onDragEnd = ::letGo,
								onDragCancel = {
									lifted = null
									travel = 0f
								},
							) { change, _ ->
								change.consume()
								downOf(change.position)?.let { travel = it - from }
							}
						}

				val divider = LocalPalette.current.divider

				Box(
					Modifier
						.onSizeChanged { heights = heights + (itsKey to it.height) }
						.zIndex(if (up) 1f else 0f)
						.graphicsLayer {
							// Drawn where the drag puts it: read here, a drag only redraws.
							translationY = dragged?.let { it.drawn.getValue(index) - it.tops[index] } ?: 0f
							shadowElevation = if (up) 8.dp.toPx() else 0f
						}.drawWithContent {
							drawContent()
							if (divided && index > 0 && !up)
								drawLine(divider, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
						}.then(
							if (moving == Moving.Several)
								Modifier
									.semantics { selected = isChosen }
									.pointerInput(itsKey, movable(item)) {
										choosing(movable(item)) { shift ->
											chosen =
												when {
													!shift -> emptySet()
													itsKey in chosen -> chosen - itsKey
													else -> chosen + itsKey
												}
										}
									}
							else
								Modifier,
						),
				) {
					content(item, handle, up || isChosen)
				}
			}
		}
	}
}

/** Where something is laid out, kept where pointer input can read it as it comes. */
private class Placement {
	var at: LayoutCoordinates? = null
}

/** Where a drag would put what it lifts: the tops the entries have, those it draws them at, and the move it makes. */
private class Layout(
	val tops: List<Float>,
	val drawn: Map<Int, Float>,
	val move: Moved,
)

/**
 * Each click on an entry, told whether Shift was held: one with Shift is the entry's alone, kept from what is under the
 * pointer (a switch, a button), so that it only chooses; one without passes on as it would have. A press that becomes
 * a drag is no click. An entry not [movable] is not chosen: a click with Shift on it is no click here.
 */
private suspend fun PointerInputScope.choosing(
	movable: Boolean,
	onClick: (shift: Boolean) -> Unit,
) {
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
		val shift = currentEvent.keyboardModifiers.isShiftPressed && movable

		if (shift)
			down.consume()
		awaitClick(down, shift)?.let { onClick(shift) }
	}
}

/** The release of [down] as a click, kept from what is under it where [keep]; null where it was dragged or cancelled. */
private suspend fun AwaitPointerEventScope.awaitClick(
	down: PointerInputChange,
	keep: Boolean,
): PointerInputChange? {
	val slop = viewConfiguration.touchSlop

	while (true) {
		val event = awaitPointerEvent(PointerEventPass.Initial)
		val change = event.changes.firstOrNull { it.id == down.id } ?: return null

		if ((change.position - down.position).getDistance() > slop)
			return null
		if (event.changes.fastAll { !it.pressed })
			return change.also {
				if (keep)
					it.consume()
			}
	}
}
