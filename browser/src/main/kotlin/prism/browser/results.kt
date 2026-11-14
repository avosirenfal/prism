package prism.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * A list of results under a field, as a search's window and the launcher both have one: rows of one height, some with
 * a heading over them, one of them highlighted. The keyboard stays in the field, so the list is never focused: the
 * arrows move the highlight from there, and the list scrolls to keep the row it is on in sight, as far as there are
 * results, however few rows are in sight at once. The wheel scrolls it too, and a click chooses a row.
 */

/**
 * [count] results, each drawn by [row] in a row [rowHeight] tall, at most [height] of them in sight, the rest reached
 * by scrolling: with the wheel, or by moving [highlighted] (the row the arrows are on; none while they are on none),
 * which is kept in sight. [heading] is what is written over a result that begins a group; [onChoose] is a row clicked.
 */
@Composable
fun ResultList(
	count: Int,
	highlighted: Int?,
	rowHeight: Dp,
	height: Dp,
	modifier: Modifier = Modifier,
	tag: String = "results-list",
	heading: (Int) -> String? = { null },
	rowTag: (Int) -> String = { "result:$it" },
	onChoose: (Int) -> Unit,
	row: @Composable RowScope.(Int) -> Unit,
) {
	val state = rememberLazyListState()
	val rowPixels = with(LocalDensity.current) { rowHeight.toPx() }

	LaunchedEffect(highlighted, count) { state.show(highlighted, rowPixels) }

	Box(modifier.fillMaxWidth().heightIn(max = height)) {
		LazyColumn(
			Modifier.fillMaxWidth().testTag(tag),
			state,
			contentPadding =
				PaddingValues(
					end =
						if (state.canScroll)
							Dims.scrollbar + Space.xs
						else
							0.dp,
				),
		) {
			items(count, key = { it }) { at ->
				Column {
					heading(at)?.let { ResultHeading(it) }
					ResultRow(
						at == highlighted,
						rowHeight,
						Modifier.testTag(rowTag(at)),
						{ onChoose(at) },
					) { row(at) }
				}
			}
		}
		if (state.canScroll)
			ScrollbarBeside(
				rememberScrollbarAdapter(state),
				Modifier.align(Alignment.CenterEnd),
				tag = "$tag-scrollbar",
			)
	}
}

/** Whether there is more than is in sight, either way. */
private val LazyListState.canScroll: Boolean get() = canScrollForward || canScrollBackward

/**
 * The list scrolled as little as shows the whole of the row [at]: nothing, where it is in sight already. With no row
 * (the arrows back in the field), the list is at its start.
 */
private suspend fun LazyListState.show(at: Int?, rowPixels: Float) {
	if (at == null)
		return scrollToItem(0)
	if (at >= layoutInfo.totalItemsCount)
		return

	fun shown() = layoutInfo.visibleItemsInfo.firstOrNull { it.index == at }

	// Not in sight at all: brought to the top where it is above, and to about the bottom where it is below.
	if (shown() == null)
		scrollToItem(
			at,
			if (at < firstVisibleItemIndex)
				0
			else
				-(layoutInfo.viewportEndOffset - rowPixels.toInt()).coerceAtLeast(0),
		)

	// In sight, but cut off at an edge: moved by what is cut off.
	shown()?.let { item ->
		val below = item.offset + item.size - layoutInfo.viewportEndOffset

		when {
			item.offset < layoutInfo.viewportStartOffset ->
				scrollBy((item.offset - layoutInfo.viewportStartOffset).toFloat())
			below > 0 -> scrollBy(below.toFloat())
		}
	}
}

/** What is written over the first result of a group. */
@Composable
private fun ResultHeading(text: String) {
	Text(
		text,
		style = LocalType.current.heading,
		maxLines = 1,
		modifier =
			Modifier
				.fillMaxWidth()
				.padding(start = Space.m, end = Space.m, top = Space.m, bottom = Space.xs)
				.testTag("results-heading:$text"),
	)
}

/** One result's row: highlighted where the arrows are on it, tinted under the pointer, and chosen by a click. */
@Composable
private fun ResultRow(
	highlighted: Boolean,
	height: Dp,
	modifier: Modifier,
	onClick: () -> Unit,
	content: @Composable RowScope.() -> Unit,
) {
	val palette = LocalPalette.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()
	val shape = RoundedCornerShape(Radius.medium)

	Row(
		Modifier
			.fillMaxWidth()
			.height(height)
			.background(
				when {
					highlighted -> palette.chosen
					hovered -> palette.hover
					else -> Color.Transparent
				},
				shape,
			).hoverable(hover)
			.clickable(onClick = onClick)
			.semantics { selected = highlighted }
			.then(modifier)
			.padding(horizontal = Space.m),
		verticalAlignment = Alignment.CenterVertically,
		content = content,
	)
}

/**
 * What a result's row says, after its icon: its [title], what is said of it beside that ([detail]: where it is, what
 * it is for), and, at the row's end, what kind of thing it is ([kind]), where that is said.
 */
@Composable
fun RowScope.ResultText(title: String, detail: String, kind: String = "") {
	val type = LocalType.current

	Text(title, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
	Spacer(Modifier.width(Space.m))
	Text(
		detail,
		style = type.muted,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = Modifier.weight(1f),
	)
	if (kind.isNotEmpty()) {
		Spacer(Modifier.width(Space.m))
		// Fainter than what is said of the result: it is the same word down most of a list.
		Text(
			kind,
			style = type.muted.copy(color = LocalPalette.current.textFaint),
			maxLines = 1,
		)
	}
}
