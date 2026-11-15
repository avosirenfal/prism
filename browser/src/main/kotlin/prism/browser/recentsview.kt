package prism.browser

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

	Column {
		if (over.isNotEmpty())
			RecentHeadings(over, first, zoom, onToggle)
		else if (mark.stale && before?.stale == false)
			Box(
				Modifier
					.fillMaxWidth()
					.padding(horizontal = Dims.edge + Dims.cell, vertical = Space.xs)
					.height(1.dp)
					.background(LocalPalette.current.divider)
					.testTag("recent-older"),
			)
		content()
	}
}

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
 * A section's heading: an arrow (down where the section is open, across where it is collapsed), its title, and,
 * collapsed, how many entries it holds. All of it is pressed to collapse the section or open it, and says so under
 * the pointer. The arrow sits where the rows' icons do, and the title where their names start.
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
	val toggle by rememberUpdatedState { onToggle(heading.kind.section) }
	val does =
		if (heading.collapsed)
			"Expand"
		else
			"Collapse"

	Row(
		Modifier
			.padding(top = over)
			.fillMaxWidth()
			.background(
				if (hovered)
					palette.hover
				else
					Color.Transparent,
				RoundedCornerShape(Radius.medium),
			).hoverable(hover)
			// Pressed as a row is: taken as it goes down, so that it is no press of the space between the rows.
			.pointerInput(Unit) {
				awaitEachGesture {
					val down = awaitFirstDown()

					if (currentEvent.buttons.isPrimaryPressed) {
						down.consume()
						waitForUpOrCancellation()?.let {
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
			.padding(horizontal = Dims.edge + Dims.cell, vertical = Space.s),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			Modifier.width(rowIconOf(LocalRows.current, type) * zoom),
			contentAlignment = Alignment.Center,
		) {
			GlyphIcon(
				if (heading.collapsed)
					Glyph.Chevron
				else
					Glyph.ChevronDown,
				palette.textMuted,
				type.icon * 0.8f * zoom,
			)
		}
		Spacer(Modifier.width((Space.s + Space.xxs) * zoom))
		Text(heading.kind.title, style = type.heading.times(zoom), maxLines = 1)
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
