package prism.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.time.Instant
import prism.preferences.ColumnId
import prism.preferences.ColumnSetting
import prism.preferences.RecentSection
import prism.preferences.Relevance
import prism.window.WindowLocals

/*
 * The Recents page as the listing draws it: its rows are the listing's own, drawn by what draws a folder's (chosen,
 * dragged, opened and named as those are), as a list or as a gallery, with the name, the folder each is in, and,
 * last, when it was made or used. What this adds is what is between them: in groups, each section's heading over its
 * first row (pressed, it collapses the section to the heading alone, or opens it again); in tabs, a tab a section
 * over them all; and a thin line before the first row that is long before its section's newest, the rows from there
 * on drawn quieter. And the tuning of how its most relevant folders are found, opened from the toolbar.
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
	dimmed: Boolean,
	indented: Boolean,
	onToggle: (RecentSection) -> Unit,
	content: @Composable () -> Unit,
) {
	if (mark == null) {
		content()

		return
	}

	// In from the headings, where there are headings; in tabs, at the listing's edge, as a folder's rows are.
	val indent =
		if (indented)
			recentIndent(zoom)
		else
			0.dp

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
		// One hidden from the page, shown because that was asked for, is faint: there, and not as the rest are.
		Box(
			Modifier
				.padding(start = indent)
				.then(
					if (dimmed)
						Modifier.alpha(HIDDEN_ALPHA).testTag("recent-dimmed")
					else
						Modifier,
				),
		) { content() }
	}
}

/** How faint a row hidden from the page is drawn, where the page shows it all the same. */
private const val HIDDEN_ALPHA = 0.45f

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

/** Where a heading's ground starts, in from the list's room: so that its arrow stands where a row's icon would. */
internal val LIST_HEADING_GROUND = Dims.edge + Dims.cell - FOLD_ROOM

/**
 * Sections' [headings], one under the other: over a row, or, for the sections collapsed after the last row, in a
 * place of their own past the rows. The [first] of the page has less room over it than one that follows rows, which
 * has [apart] over it. Each heading's ground starts [ground] in from where they are drawn.
 */
@Composable
internal fun RecentHeadings(
	headings: List<RecentPage.Heading>,
	first: Boolean,
	zoom: Float,
	onToggle: (RecentSection) -> Unit,
	ground: Dp = LIST_HEADING_GROUND,
	apart: Dp = Space.m,
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
						else -> apart
					},
				ground,
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
	ground: Dp,
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
			.padding(start = ground),
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

// ---- tabs ----------------------------------------------------------------------------------------------------

/**
 * The Recents page's tabs, over its rows, where its sections are shown as tabs: a tab a section with anything to show,
 * its name and how many it shows; the one [chosen] has its name strong and a line in the accent under it. A tab
 * pressed is shown. Too many for the listing's width, they are scrolled across, by the wheel too, and fade out at an
 * edge there are more past. The first tab's name starts [start] in from the listing's edge. They are no stop of the
 * keyboard's, which in the listing goes from tab to tab by Ctrl+Page Up and Ctrl+Page Down.
 */
@Composable
internal fun RecentTabs(
	tabs: List<RecentPage.Tab>,
	chosen: RecentPage.Kind,
	start: Dp,
	onChoose: (RecentSection) -> Unit,
) {
	val palette = LocalPalette.current
	val scroll = rememberScrollState()

	Column(Modifier.fillMaxWidth().testTag("recent-tabs")) {
		Row(
			Modifier
				.fillMaxWidth()
				.wheelScrolled(scroll, TAB_WHEEL)
				.drawWithContent {
					drawContent()

					val fade = TAB_FADE.toPx().coerceAtMost(size.width / 2)

					if (scroll.canScrollBackward)
						drawRect(
							Brush.horizontalGradient(listOf(palette.surface, Color.Transparent), 0f, fade),
							size = Size(fade, size.height),
						)
					if (scroll.canScrollForward)
						drawRect(
							Brush.horizontalGradient(
								listOf(Color.Transparent, palette.surface),
								size.width - fade,
								size.width,
							),
							topLeft = Offset(size.width - fade, 0f),
							size = Size(fade, size.height),
						)
				}.horizontalScroll(scroll)
				.padding(
					start = (start - TAB_ROOM).coerceAtLeast(0.dp),
					end = Space.s,
					top = Space.xs,
				),
		) {
			for (tab in tabs)
				RecentTab(tab, shown = tab.kind == chosen) { onChoose(tab.kind.section) }
		}
		Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
	}
}

/** The room each side of a tab's name and count, inside the ground it is lit on. */
private val TAB_ROOM = Space.s

/** How far the tabs fade out at an edge there are more past. */
private val TAB_FADE = Space.xxl

/** How far across a notch of the wheel moves the tabs, as a line it moves a list down. */
private val TAB_WHEEL = Space.xl

/**
 * One tab: its name, and its count in a small round ground of its own; lit under the pointer, darker pressed; and
 * where it is [shown], its name strong, its count in the colour of what is chosen, and a line in the accent under it.
 */
@Composable
private fun RecentTab(tab: RecentPage.Tab, shown: Boolean, onChoose: () -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val hovered by source.collectIsHoveredAsState()
	val pressed by source.collectIsPressedAsState()
	val ink =
		if (shown || hovered)
			palette.text
		else
			palette.textMuted

	Column(
		Modifier
			.width(IntrinsicSize.Max)
			.clip(RoundedCornerShape(topStart = Radius.medium, topEnd = Radius.medium))
			.background(
				when {
					pressed -> palette.pressed
					hovered && !shown -> palette.hover
					else -> Color.Transparent
				},
			).hoverable(source)
			.clickable(source, indication = null, role = Role.Tab, onClick = onChoose)
			.semantics { selected = shown }
			.testTag("recent-tab:${tab.kind.title}"),
	) {
		Row(
			Modifier.padding(
				start = TAB_ROOM,
				end = TAB_ROOM,
				top = Space.s,
				bottom =
					Space.s - Space.xxs,
			),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.xs + Space.xxs),
		) {
			Text(
				tab.kind.tab,
				style =
					(if (shown) type.strong else type.body).copy(color = ink),
				maxLines = 1,
			)
			Text(
				tab.count.toString(),
				style =
					type.muted.tabular().copy(
						color =
							if (shown)
								palette.accentText
							else
								palette.textMuted,
					),
				maxLines = 1,
				modifier =
					Modifier
						.background(
							if (shown)
								palette.chosen
							else
								palette.pressed,
							CircleShape,
						).padding(horizontal = Space.xs + Space.xxs)
						.testTag("recent-tab-count:${tab.kind.title}"),
			)
		}
		Box(
			Modifier
				.fillMaxWidth()
				.padding(horizontal = Space.xs)
				.height(2.dp)
				.background(
					if (shown)
						palette.accent
					else
						Color.Transparent,
					RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp),
				),
		)
	}
}

/** The tab after [chosen] among [tabs] ([onward]), or the one before it: round from the last to the first. */
internal fun tabAfter(
	tabs: List<RecentPage.Tab>,
	chosen: RecentPage.Kind,
	onward: Boolean,
): RecentPage.Tab? {
	val at =
		tabs.indexOfFirst { it.kind == chosen }.takeIf { it >= 0 } ?: return tabs.firstOrNull()

	val step =
		if (onward)
			1
		else
			-1

	return tabs[(at + step).mod(tabs.size)]
}

// ---- the gallery's places ------------------------------------------------------------------------------------

/**
 * Where a gallery lays out its rows: a place a row, in order ([Plain]); or, on the Recents page in groups, with a
 * place across the whole of a line before each row headings are over, and one past the last row for the headings of
 * the sections collapsed after it, the headings in it ([Headed]). What goes by the gallery's places (the tiles in
 * sight, the one the cursor is on, the line under another) goes through these to the rows.
 */
@Immutable
internal sealed interface GalleryPlaces {
	/** How many places there are. */
	val size: Int

	/** The place of the row [row], by its place among the rows. */
	fun placeOf(row: Int): Int

	/** The row at [place], by its place among the rows; none, where headings are. */
	fun rowAt(place: Int): Int?

	/** The headings at [place], where headings are; none, where a row is. */
	fun headingsAt(place: Int): List<RecentPage.Heading>?

	/**
	 * The row [lines] lines of [span] tiles down from [row] (up, where negative): in the same column, or the last of a
	 * shorter line, the rows under each heading laid out in lines of their own. Past the last line, the last row; and
	 * before the first, the first.
	 */
	fun down(row: Int, lines: Int, span: Int): Int

	/** A place a row: what every gallery is, but the Recents page's in groups. */
	class Plain(
		private val rows: Int,
	) : GalleryPlaces {
		override val size: Int get() = rows

		override fun placeOf(row: Int): Int = row

		override fun rowAt(place: Int): Int? = place.takeIf { it in 0 until rows }

		override fun headingsAt(place: Int): List<RecentPage.Heading>? = null

		override fun down(row: Int, lines: Int, span: Int): Int =
			(row + lines * span).coerceIn(0, (rows - 1).coerceAtLeast(0))
	}

	/** The places of [rows] rows with the headings [over] them, by the row each is over ([RecentPage.Shown.over]). */
	class Headed(
		over: Map<Int, List<RecentPage.Heading>>,
		private val rows: Int,
	) : GalleryPlaces {
		/** What is at each place: a row, by its place among the rows; or headings. */
		private val held: List<Held> =
			(0..rows).flatMap { row ->
				listOfNotNull(
					over[row]?.let { Held.Headings(it) },
					Held.Row(row).takeIf { row < rows },
				)
			}

		private val places: IntArray =
			IntArray(rows).also { places ->
				held.forEachIndexed { place, it ->
					if (it is Held.Row)
						places[it.at] = place
				}
			}

		/** Where each run of rows laid out in lines together starts: the first row, and each a heading is over. */
		private val starts: List<Int> =
			(
				over.keys.filter {
					it in 1 until rows
				} + 0
			).distinct().sorted()

		override val size: Int get() = held.size

		override fun placeOf(row: Int): Int = places.getOrElse(row) { held.size }

		override fun rowAt(place: Int): Int? = (held.getOrNull(place) as? Held.Row)?.at

		override fun headingsAt(place: Int): List<RecentPage.Heading>? =
			(held.getOrNull(place) as? Held.Headings)?.headings

		override fun down(row: Int, lines: Int, span: Int): Int =
			(
				0 until
					kotlin.math.abs(
						lines,
					)
			).fold(row) { at, _ -> stepped(at, lines > 0, span.coerceAtLeast(1)) }

		/** The row a line down from [row] ([down]), or up. */
		private fun stepped(row: Int, down: Boolean, span: Int): Int {
			val run = starts.indexOfLast { it <= row }.coerceAtLeast(0)
			val start = starts[run]
			val end = starts.getOrNull(run + 1) ?: rows
			val column = (row - start) % span

			return if (down)
				when {
					row + span < end -> row + span
					// A shorter line under this one: its last.
					(row - start) / span < (end - 1 - start) / span -> end - 1
					// The next run's first line.
					end < rows -> end + minOf(column, (starts.getOrNull(run + 2) ?: rows) - end - 1)
					else -> rows - 1
				}
			else
				when {
					row - span >= start -> row - span
					// The last line of the run before.
					run > 0 -> {
						val before = starts[run - 1]

						minOf(before + (start - 1 - before) / span * span + column, start - 1)
					}
					else -> 0
				}
		}

		private sealed interface Held {
			data class Row(
				val at: Int,
			) : Held

			data class Headings(
				val headings: List<RecentPage.Heading>,
			) : Held
		}
	}
}

// ---- the tuning ----------------------------------------------------------------------------------------------

/**
 * What opens the tuning of how the Recents page finds its most relevant folders: a button, and, open, the tuning
 * under it ([RelevanceTuning]), which closes by Escape or a press outside it.
 */
@Composable
internal fun RelevanceButton(relevance: Relevance, onTune: (Relevance) -> Unit) {
	var open by remember { mutableStateOf(false) }

	Box {
		ToolButton(
			Glyph.Tune,
			"Tune most relevant folders",
			enabled = true,
			Modifier.testTag("relevance"),
			selected = open,
		) { open = !open }
		if (open)
			Popup(
				popupPositionProvider = BelowEnd,
				onDismissRequest = { open = false },
				properties = PopupProperties(focusable = true),
			) {
				WindowLocals { RelevanceTuning(relevance, onTune) { open = false } }
			}
	}
}

/**
 * How the most relevant folders are found, to be tuned: a slider for how much each of what says so counts, for how
 * fast how lately a folder was used stops counting, and for how many are shown, each beside what it is set to; and
 * what sets them all back as prism has them. Each change is the page's at once, and kept ([onTune]).
 */
@Composable
private fun RelevanceTuning(
	relevance: Relevance,
	onTune: (Relevance) -> Unit,
	onClose: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.large)
	val first = remember { FocusRequester() }

	LaunchedEffect(Unit) { first.requestFocus() }

	Column(
		Modifier
			.width(TUNING_WIDTH)
			.shadow(12.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
			.background(palette.overlay, shape)
			.border(1.dp, palette.border, shape)
			.onPreviewKeyEvent {
				(it.type == KeyEventType.KeyDown && it.key == Key.Escape).also { escaped ->
					if (escaped)
						onClose()
				}
			}.padding(Space.l)
			.testTag("relevance-tuning"),
		verticalArrangement = Arrangement.spacedBy(Space.m),
	) {
		Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
			Text("Most relevant folders", style = type.strong)
			Text(
				"Each folder is scored by recency, frequency, and how often this " +
					"application picked files there, each multiplied by its weight. " +
					"The highest scores are listed newest first.",
				style = type.muted,
			)
		}
		Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
			Tuned(
				"Recency",
				WEIGHTS,
				relevance.recency,
				::weightSaid,
				"relevance-recency",
				first,
			) {
				onTune(relevance.copy(recency = it))
			}
			Tuned(
				"Recency half-life",
				HALF_LIVES,
				relevance.recencyHalfLifeHours,
				::hoursSaid,
				"relevance-half-life",
			) { onTune(relevance.copy(recencyHalfLifeHours = it)) }
			Tuned("Frequency", WEIGHTS, relevance.frequency, ::weightSaid, "relevance-frequency") {
				onTune(relevance.copy(frequency = it))
			}
			Tuned(
				"This application",
				WEIGHTS,
				relevance.application,
				::weightSaid,
				"relevance-application",
			) {
				onTune(relevance.copy(application = it))
			}
			Tuned(
				"Folders shown",
				SHOWN_COUNTS,
				relevance.shown.toDouble(),
				{ it.toInt().toString() },
				"relevance-shown",
			) { onTune(relevance.copy(shown = it.toInt())) }
		}
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
			PushButton(
				"Reset",
				Emphasis.Secondary,
				enabled = relevance != Relevance(),
				Modifier.testTag("relevance-reset"),
			) { onTune(Relevance()) }
		}
	}
}

/**
 * One thing tuned: its [label], a slider of [options] at the one nearest [value], and what that is, as [said] says
 * it. The slider is tagged [tag], and what it is set to `[tag]-value`.
 */
@Composable
private fun Tuned(
	label: String,
	options: List<Double>,
	value: Double,
	said: (Double) -> String,
	tag: String,
	focus: FocusRequester? = null,
	onChoose: (Double) -> Unit,
) {
	val type = LocalType.current
	val chosen = options.minBy { kotlin.math.abs(it - value) }

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.m),
	) {
		Text(label, style = type.body, maxLines = 1, modifier = Modifier.width(TUNING_LABEL))
		Slider(
			options,
			chosen,
			focus?.let { Modifier.focusRequester(it) } ?: Modifier,
			tag = tag,
			onChoose = onChoose,
		)
		Text(
			said(chosen),
			style = type.muted.tabular(),
			maxLines = 1,
			textAlign = TextAlign.End,
			modifier = Modifier.width(TUNING_VALUE).testTag("$tag-value"),
		)
	}
}

/** What a weight is set to: "1.0", and "Off" for nothing. */
internal fun weightSaid(weight: Double): String =
	if (weight == 0.0)
		"Off"
	else
		String.format(java.util.Locale.ROOT, "%.1f", weight)

/** A half-life of [hours], in a few words: "6 hours", "2 days", "1 week". */
internal fun hoursSaid(hours: Double): String {
	val whole = hours.toLong()

	fun counted(count: Long, unit: String): String =
		if (count == 1L)
			"1 $unit"
		else
			"$count ${unit}s"

	return when {
		whole < 24 -> counted(whole, "hour")
		whole % (24 * 7) == 0L -> counted(whole / (24 * 7), "week")
		else -> counted(whole / 24, "day")
	}
}

/** The weights the tuning offers: none, to twice as much as prism has the most of, by tenths. */
private val WEIGHTS = (0..20).map { it / 10.0 }

/** The half-lives the tuning offers for how lately a folder was used, in hours: an hour to a month. */
private val HALF_LIVES =
	listOf(1.0, 2.0, 3.0, 6.0, 12.0, 24.0, 48.0, 72.0, 120.0, 168.0, 336.0, 720.0)

/** How many most relevant folders the tuning offers to show. */
private val SHOWN_COUNTS = (4..16).map { it.toDouble() }

private val TUNING_WIDTH = 456.dp
private val TUNING_LABEL = 128.dp
private val TUNING_VALUE = 64.dp

/** Under what opens it, its end at that one's end; above it, where there is no room below; inside the window either way. */
internal object BelowEnd : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val gap = 4
		val below = anchorBounds.bottom + gap
		val y =
			if (below + popupContentSize.height <= windowSize.height)
				below
			else
				(anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)

		return IntOffset(
			(anchorBounds.right - popupContentSize.width).coerceIn(
				0,
				(windowSize.width - popupContentSize.width).coerceAtLeast(0),
			),
			y,
		)
	}
}
