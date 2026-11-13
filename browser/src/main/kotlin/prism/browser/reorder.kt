package prism.browser

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
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
 * A list put in order by dragging. An entry is taken anywhere on it but on what is its own to press (a switch, a button,
 * a box: those take their press before the entry sees it), dragged up or down, the others making room for it, and let
 * go where it is to be. Where several can be moved together, a click chooses an entry and no other, Shift and a click
 * choose it too (or no longer), and a chosen one dragged brings every one chosen with it, gathered under it in their
 * order; one not chosen, dragged, is chosen alone and goes alone.
 */

/** How a [ReorderableColumn]'s entries are moved: one at a time, or as several chosen together by Shift and a click. */
enum class Moving {
	One,
	Several,
}

/**
 * What an entry of a [ReorderableColumn] is drawn with: [entry], for the part of it that is pressed to choose it and drag
 * it, the whole of it but anything nested in it that is chosen apart (a submenu's items, in a list of their own), and
 * that lifts it, with a shadow under it, while it is dragged; and whether it is [lit], chosen or being dragged.
 */
@Immutable
class Grip internal constructor(
	val entry: Modifier,
	val lit: Boolean,
)

/** The entries being dragged (their places, in order), and the one of them held. */
@Immutable
private data class Lifted(
	val moved: List<Int>,
	val held: Int,
)

/**
 * [items] one under another, each drawn by [content], that the pointer reorders: an entry taken on the part of it its
 * [Grip.entry] is on is dragged, and [onMove] told what was moved where once it is let go; as [moving] says, alone or
 * with others chosen. An entry [movable] says is not is neither dragged nor chosen. What is shown is [items] as they
 * are: the order changes only as the caller's does. In a card, [divided] sets a line between each two entries, as a
 * card's rows have, which moves with the entry under it.
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
	content: @Composable (item: T, grip: Grip) -> Unit,
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

	/** The entry at [index] taken to be dragged: with every one chosen, where it is chosen; else alone, and chosen so. */
	fun lift(index: Int) {
		val group = chosenPlaces()

		lifted =
			if (index in group) {
				Lifted(group, index)
			} else {
				if (choosable(index))
					chosen = setOf(keyOf(latest[index]))
				Lifted(listOf(index), index)
			}
		travel = 0f
	}

	/** A click on the entry at [index]: it chosen and no other; with Shift, chosen as well, or no longer. */
	fun clicked(index: Int, shift: Boolean) {
		if (!choosable(index))
			return

		val itsKey = keyOf(latest[index])

		chosen =
			when {
				!shift -> setOf(itsKey)
				itsKey in chosen -> chosen - itsKey
				else -> chosen + itsKey
			}
	}

	val palette = LocalPalette.current
	val divider = palette.divider

	Column(modifier.onPlaced { column.at = it }) {
		items.forEachIndexed { index, item ->
			key(key(item)) {
				val itsKey = key(item)
				val up = lifted?.moved?.contains(index) == true
				val isChosen = itsKey in chosen && choosable(index)
				val placed = remember { Placement() }
				val shape = RoundedCornerShape(Radius.medium)

				/** How far down the column [position], a point of the entry's, is. */
				fun downOf(position: Offset): Float? =
					column.at?.let { at -> placed.at?.let { at.localPositionOf(it, position).y } }

				val entry =
					if (!movable(item))
						Modifier
					else
						Modifier
							.onPlaced { placed.at = it }
							.then(
								if (up)
									Modifier.shadow(
										6.dp,
										shape,
										ambientColor = palette.shadow,
										spotColor = palette.shadow,
									)
								else
									Modifier,
							).then(
								if (moving ==
									Moving.Several
								)
									Modifier.semantics { selected = isChosen }
								else
									Modifier,
							).pointerInput(index, moving) {
								// Where the drag began, down the column: the entry moves with the pointer, the column does not.
								var from = 0f

								taking(
									onClick = { shift -> clicked(index, shift) },
									onStart = { at ->
										lift(index)
										from = downOf(at) ?: 0f
									},
									onDrag = { at -> downOf(at)?.let { travel = it - from } },
									onEnd = ::letGo,
									onCancel = {
										lifted = null
										travel = 0f
									},
								)
							}

				Box(
					Modifier
						.onSizeChanged { heights = heights + (itsKey to it.height) }
						.zIndex(if (up) 1f else 0f)
						.graphicsLayer {
							// Drawn where the drag puts it: read here, a drag only redraws.
							translationY = dragged?.let { it.drawn.getValue(index) - it.tops[index] } ?: 0f
						}.drawWithContent {
							drawContent()
							if (divided && index > 0 && !up)
								drawLine(divider, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
						},
				) {
					content(item, Grip(entry, up || isChosen))
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
 * Each press of an entry that nothing in it took for its own: a switch, a button or a box takes its press as it is
 * made, before the entry sees it. Let go where it was pressed, it is a click, told whether Shift was held; moved
 * further than a touch's slop, it is a drag, told where it began and where the pointer is after each move, in the
 * entry's space, until it is let go, or cancelled. The mouse's other buttons are not this.
 */
private suspend fun PointerInputScope.taking(
	onClick: (shift: Boolean) -> Unit,
	onStart: (Offset) -> Unit,
	onDrag: (Offset) -> Unit,
	onEnd: () -> Unit,
	onCancel: () -> Unit,
) {
	awaitEachGesture {
		val down = awaitFirstDown()
		val shift = currentEvent.keyboardModifiers.isShiftPressed
		val slop = viewConfiguration.touchSlop

		if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed)
			return@awaitEachGesture

		while (true) {
			val change =
				awaitPointerEvent().changes.firstOrNull { it.id == down.id }
					?: return@awaitEachGesture

			when {
				change.isConsumed -> return@awaitEachGesture
				!change.pressed -> {
					change.consume()
					onClick(shift)

					return@awaitEachGesture
				}
				(change.position - down.position).getDistance() > slop -> {
					change.consume()
					onStart(down.position)
					onDrag(change.position)

					val ended =
						drag(change.id) { moved ->
							moved.consume()
							onDrag(moved.position)
						}

					if (ended)
						onEnd()
					else
						onCancel()

					return@awaitEachGesture
				}
			}
		}
	}
}
