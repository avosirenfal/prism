package prism.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import prism.preferences.ColumnId
import prism.preferences.ColumnSetting

/*
 * The Recents page as the listing draws it: its rows are the listing's own, drawn by what draws a folder's (chosen,
 * dragged, opened and named as those are), with the name, the folder each is in, and, last, when it was made or
 * used. What this adds is what is between them: each section's heading over its first row, and a thin line before
 * the first row that is long before its section's newest, the rows from there on drawn quieter.
 */

/** The columns the Recents page's rows show before when each was made or used: the name, and the folder it is in. */
internal val RECENT_COLUMNS =
	listOf(ColumnSetting(ColumnId.Name), ColumnSetting(ColumnId.Location))

/** How many characters wide what says when is: "45 minutes ago", "28 Sept 2025". */
internal const val RECENT_AGO_CHARACTERS = 15

/**
 * A row of the listing, [content], with what the Recents page puts over it where it is one of that page's ([mark]):
 * its section's heading, where the row [before] is of another section or there is none ([first]); a thin line,
 * where it is the first of its section's that is long before the section's newest. Any other listing's row is drawn
 * as it is, with nothing around it.
 */
@Composable
internal fun RecentSlot(
	mark: RecentPage.Mark?,
	before: RecentPage.Mark?,
	first: Boolean,
	zoom: Float,
	content: @Composable () -> Unit,
) {
	if (mark == null) {
		content()

		return
	}

	val palette = LocalPalette.current
	val type = LocalType.current
	val opens = first || before?.section != mark.section

	Column {
		if (opens)
			Text(
				mark.section.title,
				style = type.heading.times(zoom),
				maxLines = 1,
				modifier =
					Modifier
						.fillMaxWidth()
						.padding(
							start = Dims.edge + Dims.cell,
							top = if (first) Space.s else Space.l,
							bottom = Space.xs,
						).testTag("recent-section:${mark.section.title}"),
			)
		else if (mark.stale && before?.stale == false)
			Box(
				Modifier
					.fillMaxWidth()
					.padding(horizontal = Dims.edge + Dims.cell, vertical = Space.xs)
					.height(1.dp)
					.background(palette.divider)
					.testTag("recent-older"),
			)
		content()
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
