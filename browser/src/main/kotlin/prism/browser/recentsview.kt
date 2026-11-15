package prism.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import prism.preferences.ColumnId
import prism.preferences.ColumnSetting
import prism.preferences.RecentSection

/*
 * The Recents page as the listing draws it: its rows are the listing's own, drawn by what draws a folder's (chosen,
 * dragged, opened and named as those are), with the name, the folder each is in, and, last, when it was made or
 * used. What this adds is what is between them: each section's heading over its first row (pressed, it collapses the
 * section to the heading alone, or opens it again), and a thin line before the first row that is long before its
 * section's newest, the rows from there on drawn quieter.
 */

/** The columns the Recents page's rows show before when each was made or used: the name, and the folder it is in. */
internal val RECENT_COLUMNS =
	listOf(ColumnSetting(ColumnId.Name), ColumnSetting(ColumnId.Location))

/** How many characters wide what says when is: "45 minutes ago", "28 Sept 2025". */
internal const val RECENT_AGO_CHARACTERS = 15

/**
 * A row of the listing, [content], with what the Recents page puts over it where it is one of that page's ([mark]):
 * the headings drawn [over] it (its own section's, where it is the section's first row, after those of any sections
 * collapsed before it); or a thin line, where it is the first of its section's that is long before the section's
 * newest, as the row [before] it is not. Any other listing's row is drawn as it is, with nothing around it.
 *
 * The headings are part of the row's own place in the list, so that the list's places are the listing's rows, one
 * each and in order, as everything that goes by them takes them to be.
 */
@Composable
internal fun RecentSlot(
	mark: RecentPage.Mark?,
	over: List<RecentPage.Heading>,
	before: RecentPage.Mark?,
	first: Boolean,
	zoom: Float,
	onToggle: (RecentSection) -> Unit,
	content: @Composable () -> Unit,
) {
	if (mark == null) {
		content()

		return
	}

	val indent = recentIndent(zoom)

	Column {
		if (over.isNotEmpty())
			RecentHeadings(over, first, zoom, onToggle)
		else if (mark.stale && before?.stale == false)
			Box(
				Modifier
					.fillMaxWidth()
					.padding(
						start = indent + Dims.edge + Dims.cell,
						end = Dims.edge + Dims.cell,
						top = Space.xs,
						bottom = Space.xs,
					).height(1.dp)
					.background(LocalPalette.current.divider)
					.testTag("recent-older"),
			)
		// Under its heading, and in from it: the row, with what lights it and marks it chosen, starts where the
		// heading's title does.
		Box(Modifier.padding(start = indent)) { content() }
	}
}

/** How large a heading's arrow is drawn: about as tall as the heading's capitals. */
private val FOLD_MARK = 14.dp

/** The room between a heading's arrow and its title: little, so that they read as one thing. */
private val FOLD_GAP = 6.dp

/** The room around a heading's arrow and title, inside the ground they are lit on: what is pressed, with them. */
private val FOLD_ROOM = 6.dp

/**
 * How far in from its heading a section's rows are: as far as the heading's arrow and the room after it, so that
 * the rows' icons stand under the heading's title.
 */
@Composable
internal fun recentIndent(zoom: Float): Dp = (FOLD_MARK + FOLD_GAP) * zoom

/**
 * Sections' [headings], one under the other: over a row, or, for the sections collapsed after the last row, in a
 * place of their own past the rows. The [first] of the page has less room over it than one that follows rows.
 */
@Composable
internal fun RecentHeadings(
	headings: List<RecentPage.Heading>,
	first: Boolean,
	zoom: Float,
	onToggle: (RecentSection) -> Unit,
) {
	Column {
		headings.forEachIndexed { index, heading ->
			RecentHeading(
				heading,
				// A heading under another (the one over it is collapsed) sits close to it; under rows, apart.
				over =
					when {
						index > 0 -> 0.dp
						first -> Space.xs
						else -> Space.m
					},
				zoom,
				onToggle,
			)
		}
	}
}

/**
 * A section's heading, at the listing's edge: a small arrow (down where the section is open, across where it is
 * collapsed), its title close after it, and, collapsed, how many entries it holds. Arrow and title are one thing to
 * press, to collapse the section or open it: under the pointer they darken together, on a soft ground that holds
 * the two of them and no more of the row. It is no stop of the keyboard's, which in the listing is the rows'.
 */
@Composable
private fun RecentHeading(
	heading: RecentPage.Heading,
	over: Dp,
	zoom: Float,
	onToggle: (RecentSection) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()
	var pressed by remember { mutableStateOf(false) }
	val toggle by rememberUpdatedState { onToggle(heading.kind.section) }
	val does =
		if (heading.collapsed)
			"Show ${heading.kind.holds}"
		else
			"Hide ${heading.kind.holds}"

	val ink =
		if (hovered || pressed)
			palette.text
		else
			palette.textMuted

	Row(
		Modifier
			.padding(top = over, bottom = Space.xxs)
			.fillMaxWidth()
			// The arrow where a row's ground begins: what is around it reaches a little further out.
			.padding(start = Dims.edge + Dims.cell - FOLD_ROOM),
	) {
		Tip(does) {
			Row(
				Modifier
					.background(
						when {
							pressed -> palette.pressed
							hovered -> palette.hover
							else -> Color.Transparent
						},
						RoundedCornerShape(Radius.medium),
					).hoverable(hover)
					// Pressed as a row is: taken as it goes down, so that it is no press of the space between the rows.
					.pointerInput(Unit) {
						awaitEachGesture {
							val down = awaitFirstDown()

							if (currentEvent.buttons.isPrimaryPressed) {
								down.consume()
								pressed = true

								val up = waitForUpOrCancellation()

								pressed = false
								up?.let {
									it.consume()
									toggle()
								}
							}
						}
					}.semantics {
						role = Role.Button
						onClick(does) {
							toggle()

							true
						}
					}.testTag("recent-section:${heading.kind.title}")
					.padding(horizontal = FOLD_ROOM, vertical = Space.xs),
				verticalAlignment = Alignment.CenterVertically,
			) {
				FoldMark(
					heading.collapsed,
					ink,
					FOLD_MARK * zoom,
					Modifier.testTag("recent-fold:${heading.kind.title}"),
				)
				Spacer(Modifier.width(FOLD_GAP * zoom))
				Text(
					heading.kind.title,
					style = type.heading.times(zoom).copy(color = ink),
					maxLines = 1,
				)
				if (heading.collapsed) {
					Spacer(Modifier.width(Space.s))
					Text(
						heading.count.toString(),
						style = type.muted.times(zoom),
						maxLines = 1,
						modifier = Modifier.testTag("recent-count:${heading.kind.title}"),
					)
				}
			}
		}
	}
}

/**
 * The arrow before a heading's title, [size] square: across where the section is [collapsed], down where it is
 * open, in [ink], drawn as prism's glyphs are, with a line.
 */
@Composable
private fun FoldMark(
	collapsed: Boolean,
	ink: Color,
	size: Dp,
	modifier: Modifier = Modifier,
) {
	Canvas(modifier.size(size)) {
		val side = this.size.minDimension

		val points =
			if (collapsed)
				listOf(0.32f to 0.14f, 0.68f to 0.5f, 0.32f to 0.86f)
			else
				listOf(0.14f to 0.32f, 0.5f to 0.68f, 0.86f to 0.32f)

		drawPath(
			Path().apply {
				points.forEachIndexed { index, (x, y) ->
					if (index == 0)
						moveTo(x * side, y * side)
					else
						lineTo(x * side, y * side)
				}
			},
			ink,
			style = Stroke(side * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round),
		)
	}
}

/** When a row of the Recents page was made or used ([at]), as of when the page was [read]: the row's last column. */
@Composable
internal fun RowScope.RecentAgo(at: Instant, read: Instant, width: Dp, zoom: Float) {
	CellText(
		agoOf(at, read),
		LocalType.current.muted.times(zoom),
		Modifier.width(width).padding(horizontal = Dims.cell),
		TextAlign.End,
	)
}
