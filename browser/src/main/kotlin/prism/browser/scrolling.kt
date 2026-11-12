package prism.browser

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 * What scrolls, and how. Everything in prism that scrolls is one of these: a column of anything ([ScrollArea]) or a lazy
 * list ([LazyScrollArea]), each with its bar beside it whenever there is more than fits, so that it is plain to see
 * that it scrolls, and each scrolled by the wheel a number of its own lines a notch ([wheelScrolled]), so that a notch
 * moves as far through what is shown however large it is drawn. A desktop's lists scroll three lines a notch.
 */

/** How many lines a notch of the wheel moves a list. */
const val LINES_A_NOTCH = 3f

/**
 * The wheel turned over [state]'s content scrolling it by [lines] of [line] a notch, gliding there, rather than by a
 * distance that knows nothing of what is shown: at any size, a notch moves as many rows. A touchpad's small scrolls
 * move it as much less as they are smaller. Ctrl with the wheel is left alone, for whatever zooms.
 *
 * It is put on what holds the list and its bar, and the list's own scrolling by the wheel is turned off: the wheel is
 * heard as it comes back up from what it is over, so a list inside another scrolls first, and the one around it only
 * where the inner one was not under the pointer.
 */
fun Modifier.wheelScrolled(
	state: ScrollableState,
	line: Dp,
	lines: Float = LINES_A_NOTCH,
): Modifier =
	pointerInput(state, line, lines) {
		val gliding = Glide(state)

		coroutineScope {
			awaitPointerEventScope {
				while (true) {
					val event = awaitPointerEvent(PointerEventPass.Main)
					val turned = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }

					if (event.type == PointerEventType.Scroll &&
						!event.keyboardModifiers.isCtrlPressed &&
						turned != 0f &&
						event.changes.none { it.isConsumed }
					) {
						event.changes.forEach { it.consume() }
						gliding.by(this@coroutineScope, turned * lines * line.toPx())
					}
				}
			}
		}
	}

/**
 * A scroll that glides to where the notches turned so far take it: a notch turned while it glides adds to where it is
 * going, rather than starting again from where it is. Reaching an end, it goes no further, and forgets what is left.
 */
private class Glide(
	private val state: ScrollableState,
) {
	private var left = 0f
	private var going: Job? = null

	fun by(scope: CoroutineScope, pixels: Float) {
		left += pixels
		going?.cancel()
		going =
			scope.launch {
				val whole = left
				var done = 0f

				state.scroll {
					animate(
						0f,
						whole,
						animationSpec = tween(140, easing = LinearOutSlowInEasing),
					) { at, _ ->
						val step = at - done
						val moved = scrollBy(step)

						done += step
						left -= step
						// At an end: what is left would only pile up against it.
						if (kotlin.math.abs(moved - step) > 0.5f)
							left = 0f
					}
				}
			}
	}
}

/** The bar beside what scrolls: seen whenever there is more than fits, and darker under the pointer. */
@Composable
fun scrollbarStyle(): ScrollbarStyle {
	val palette = LocalPalette.current

	return ScrollbarStyle(
		minimalHeight = 32.dp,
		thickness = Dims.scrollbar,
		shape = RoundedCornerShape(Dims.scrollbar / 2),
		hoverDurationMillis = 150,
		unhoverColor = palette.textFaint.copy(alpha = 0.45f),
		hoverColor = palette.textMuted.copy(alpha = 0.8f),
	)
}

/** [adapter]'s bar, at the end of the box it is drawn in, the whole of its height. */
@Composable
fun ScrollbarBeside(
	adapter: ScrollbarAdapter,
	modifier: Modifier = Modifier,
	tag: String = "",
) {
	Box(modifier, contentAlignment = Alignment.CenterEnd) {
		VerticalScrollbar(
			adapter,
			Modifier
				.fillMaxHeight()
				.padding(vertical = Space.xxs, horizontal = Space.xxs)
				.then(
					if (tag.isEmpty())
						Modifier
					else
						Modifier.testTag(tag),
				),
			style = scrollbarStyle(),
		)
	}
}

/**
 * [content], one thing under another, scrolled when it is taller than its room: by the wheel, [LINES_A_NOTCH] of its
 * lines a notch, and by its bar, which is drawn beside it with room kept for it. [padding] is room inside, around the
 * content, which scrolls with it.
 */
@Composable
fun ScrollArea(
	modifier: Modifier = Modifier,
	state: ScrollState = rememberScrollState(),
	padding: PaddingValues = PaddingValues(0.dp),
	tag: String = "",
	content: @Composable ColumnScope.() -> Unit,
) {
	val type = LocalType.current

	Box(modifier.wheelScrolled(state, type.line)) {
		Column(
			Modifier
				.fillMaxWidth()
				.verticalScroll(state, enabled = false)
				.padding(padding)
				.padding(end = Dims.scrollbarRoom),
			content = content,
		)
		ScrollbarBeside(rememberScrollbarAdapter(state), Modifier.matchParentSize(), tag)
	}
}

/**
 * A lazy list of [content], scrolled as a [ScrollArea] is: its rows [line] tall (the body's line, unless they are
 * taller), [LINES_A_NOTCH] of them a notch, its bar beside it.
 */
@Composable
fun LazyScrollArea(
	modifier: Modifier = Modifier,
	state: LazyListState = rememberLazyListState(),
	line: Dp = LocalType.current.row,
	padding: PaddingValues = PaddingValues(0.dp),
	tag: String = "",
	scrollbarTag: String = "",
	content: LazyListScope.() -> Unit,
) {
	Box(modifier.wheelScrolled(state, line)) {
		LazyColumn(
			Modifier
				.fillMaxWidth()
				.padding(end = Dims.scrollbarRoom)
				.then(
					if (tag.isEmpty())
						Modifier
					else
						Modifier.testTag(tag),
				),
			state = state,
			contentPadding = padding,
			userScrollEnabled = false,
			content = content,
		)
		ScrollbarBeside(
			rememberScrollbarAdapter(state),
			Modifier.matchParentSize(),
			scrollbarTag,
		)
	}
}
