package prism.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import prism.browser.Card
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.Finder
import prism.browser.Glyph
import prism.browser.IndexSource
import prism.browser.LocalDateFormat
import prism.browser.LocalIndexReading
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Notice
import prism.browser.Palette
import prism.browser.PushButton
import prism.browser.QueryColouring
import prism.browser.Segmented
import prism.browser.SettingRow
import prism.browser.Space
import prism.browser.Switch
import prism.browser.Tone
import prism.browser.ToolButton
import prism.browser.colouredQuery
import prism.index.Exclusions
import prism.index.FeedKind
import prism.index.Keeping
import prism.index.Naming
import prism.index.Unfollowed
import prism.index.query.Aliases
import prism.index.query.QueryText
import prism.platform.Os
import prism.preferences.DateFormat
import prism.preferences.Excluded
import prism.preferences.IndexUpkeep
import prism.browser.Page as PageLayout

/*
 * The page of what the index leaves out, in the preferences' window and in the launcher's settings alike. The user's
 * own rules come first, with the field that adds one, since those are what the page is opened for; prism's own are
 * under them, each with a switch. Every rule says what it does (not indexed, or hidden from searches) in the same
 * place, and that is changed where it is said.
 *
 * A rule is added only once it reads as one, so nothing on the page is ever wrong but what is still being typed, and
 * the window can always apply.
 */

/** How wide what a rule does is: its two choices, side by side. */
private val DOING_WIDTH = 176.dp

/** What each kind is called on the page. */
private fun labelOf(excluded: Excluded): String =
	when (excluded) {
		Excluded.Ignored -> "Ignored"
		Excluded.Hidden -> "Hidden"
	}

/** A rule as the page writes it: a folder's path from `~` where it is under [home], a search in a search's colours. */
private fun shown(
	rule: Exclusions.Rule,
	home: java.nio.file.Path,
	palette: Palette,
): AnnotatedString =
	when (val names = rule.names) {
		is Naming.Folder ->
			AnnotatedString(
				if (names.path.startsWith(home))
					"~" + names.path.toString().removePrefix(home.toString())
				else
					names.path.toString(),
			)
		is Naming.Sought -> colouredQuery(names.text, Aliases.NONE, palette)
	}

/**
 * The page of the rules over [edits], each change reported to [onChange]; above them, where the page is the
 * preferences' own, what the index does on its own ([upkeep], changed through [onUpkeep]) and the index kept here
 * ([index]), to rebuild and to watch what it hears.
 */
@Composable
internal fun ExcludingPage(
	edits: ExcludingEdits,
	/** What the index does on its own; null where this page does not set it (the launcher's settings). */
	upkeep: IndexUpkeep? = null,
	onUpkeep: (IndexUpkeep) -> Unit = {},
	/** The index searched here, to rebuild and to watch where it can be; null where there is none. */
	index: Finder? = null,
	/** Where the index comes from; null where this page shows no index (the launcher's settings). */
	source: IndexSource? = null,
	/** What copies text to the clipboard, from the index monitor. */
	onCopy: (String) -> Unit = {},
	/** What shows the index's file in a window of its folder. */
	onShowFile: (Path) -> Unit = {},
	onChange: (ExcludingEdits) -> Unit,
) {
	val type = LocalType.current

	PageLayout(
		"Indexing",
		"Ignored items aren't indexed. Hidden items are indexed, but found only when a search " +
			"includes their path. A rule also applies to everything inside what it matches.",
	) {
		if (source != null)
			Rebuilding(index, source, edits.home, onCopy, onShowFile)
		if (upkeep != null)
			Upkeeping(upkeep, onUpkeep)
		Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
			Text("Your rules", style = type.heading)
			Card {
				Adding(edits, onChange)
				if (edits.own.isNotEmpty())
					Column(Modifier.padding(vertical = Space.s)) {
						for (rule in edits.own)
							RuleRow(rule, edits, onChange)
					}
			}
		}
		Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text("Built-in rules", style = type.heading, modifier = Modifier.weight(1f))
				PushButton(
					"Reset",
					Emphasis.Secondary,
					enabled = edits.changed,
					Modifier.testTag("rules-reset"),
				) { onChange(edits.reset()) }
			}
			Card {
				Column(Modifier.padding(vertical = Space.s)) {
					for (rule in edits.builtIn)
						RuleRow(rule, edits, onChange)
				}
			}
		}
	}
}

/** How often how the index is kept is looked at again, while the page is open. */
private val KEEPING_LOOKED_AT = 500.milliseconds

/**
 * The index itself: whether it is being read and how far that has got; or, where it is not, how it is kept as the
 * disk changes (the daemon's), or that it was read once from its file (a prism of its own, or one with no daemon), as
 * the Renderer page says how windows are drawn; and under that, the index's file, which a click shows in a window of
 * its folder ([onShowFile]). Beside them, where the index is the daemon's, a button that reads it anew, and one that
 * opens the index monitor, which shows what it hears.
 */
@Composable
private fun Rebuilding(
	index: Finder?,
	source: IndexSource,
	home: Path,
	onCopy: (String) -> Unit,
	onShowFile: (Path) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val format = LocalDateFormat.current
	val reading = LocalIndexReading.current
	val keeping by produceState(index?.keeping(), index) {
		while (index != null) {
			value = index.keeping()
			delay(KEEPING_LOOKED_AT)
		}
	}
	var monitoring by remember { mutableStateOf<Monitoring>(Monitoring.Closed) }
	val log = index?.activity
	val said =
		when {
			reading != null -> KeptSaid(Tone.Plain, reading.said(), null)
			else -> saidOf(source, keeping, format)
		}

	Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
		Text("Index", style = type.heading)
		Card(Modifier.testTag("index-card")) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Notice(
					said.title,
					said.tone,
					Modifier.weight(1f).semantics(mergeDescendants = true) {}.testTag("index-state"),
				) {
					said.detail?.let {
						Text(it, style = type.muted, modifier = Modifier.testTag("index-state-detail"))
					}
					if (reading != null)
						Box(
							Modifier
								.fillMaxWidth()
								.height(PROGRESS_HEIGHT)
								.clip(RoundedCornerShape(PROGRESS_HEIGHT))
								.background(palette.border)
								.testTag("index-progress"),
						) {
							Box(
								Modifier
									.fillMaxWidth(reading.fraction ?: 0f)
									.fillMaxHeight()
									.background(palette.accent),
							)
						}
					IndexFileLine(source.file, home, onShowFile)
				}
				Row(
					Modifier.padding(end = Space.l),
					horizontalArrangement = Arrangement.spacedBy(Space.s),
				) {
					if (log != null)
						PushButton(
							"Monitor",
							Emphasis.Secondary,
							enabled = true,
							Modifier.testTag("index-monitor-open"),
						) { monitoring = Monitoring.Bursts() }
					// Only the daemon's index can be read anew: one read from its file stays as it was read.
					if (index?.rebuilds == true)
						PushButton(
							"Rebuild index",
							Emphasis.Secondary,
							enabled = reading == null,
							Modifier.testTag("index-rebuild"),
							onClick = index::rebuild,
						)
				}
			}
		}
	}
	if (log != null)
		IndexMonitor(monitoring, log, index::folderAt, onCopy) { monitoring = it }
}

/**
 * The index's [file], from `~` where it is under [home], which a click shows ([onShow]); or, for a daemon's index kept
 * in memory alone, that it is.
 */
@Composable
private fun IndexFileLine(file: Path?, home: Path, onShow: (Path) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current

	if (file == null) {
		Text(
			"Kept in memory only, never written to disk",
			style = type.muted,
			modifier = Modifier.testTag("index-file"),
		)

		return
	}

	Row(
		horizontalArrangement = Arrangement.spacedBy(Space.s),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text("Index file", style = type.muted)
		run {
			val source = remember { MutableInteractionSource() }
			val hovered by source.collectIsHoveredAsState()

			Text(
				if (file.startsWith(home))
					"~/" + home.relativize(file)
				else
					file.toString(),
				style =
					type.mono.copy(
						color = palette.accentText,
						textDecoration =
							if (hovered)
								TextDecoration.Underline
							else
								TextDecoration.None,
					),
				maxLines = 1,
				overflow = TextOverflow.MiddleEllipsis,
				modifier =
					Modifier
						.weight(1f, fill = false)
						.pointerHoverIcon(PointerIcon.Hand)
						.clickable(source, indication = null, role = Role.Button) { onShow(file) }
						.testTag("index-file"),
			)
		}
	}
}

/** What the Index card says of an index read from [source] (kept by the daemon as [keeping] says, or not), times as [format] writes them. */
internal fun saidOf(
	source: IndexSource,
	keeping: Keeping?,
	format: DateFormat,
): KeptSaid {
	fun alone(isolated: Boolean) =
		if (isolated)
			"Isolated instance"
		else
			"Daemon not running"

	return when (source) {
		is IndexSource.Daemon ->
			keeping?.let(::saidOf)
				?: KeptSaid(Tone.Good, "Up to date", null)
		is IndexSource.File ->
			KeptSaid(
				Tone.Plain,
				alone(source.isolated),
				"Loaded at ${format.write(source.at)}, %,d entries. ".format(source.entries) +
					if (source.isolated)
						"Not updated while isolated."
					else
						"Not updated until the daemon runs.",
			)
		is IndexSource.None -> KeptSaid(Tone.Plain, alone(source.isolated), source.why)
	}
}

/** How the index is kept, as the Index card says it: a [tone]'s glyph, a [title], and a [detail] under it. */
internal data class KeptSaid(
	val tone: Tone,
	val title: String,
	val detail: String?,
)

/** What follows changes on this platform, as the page names it: eBPF, FSEvents, or live monitoring on Windows. */
private val monitorHere: String get() = FeedKind.here?.label ?: "live monitoring"

/** Why live monitoring is not running, as the card says it. */
private fun whyOf(why: Unfollowed): String =
	when (why) {
		Unfollowed.TurnedOff -> "Live monitoring is off."
		is Unfollowed.Failed ->
			"${monitorHere.replaceFirstChar(
				Char::uppercase,
			)} unavailable: ${why.why.removeSuffix(".")}."
	}

/** When the next periodic rescan is, as the card says it: "next in 12 minutes". */
internal fun nextIn(next: Duration): String {
	val minutes = (next.inWholeSeconds + 59) / 60

	return when {
		next < 1.minutes -> "next in under a minute"
		minutes == 1L -> "next in 1 minute"
		else -> "next in $minutes minutes"
	}
}

/** How the index is kept, as the card says it: monitored live, rescanned now and then, or not updated at all. */
internal fun saidOf(keeping: Keeping): KeptSaid =
	when (keeping) {
		is Keeping.Followed ->
			KeptSaid(
				Tone.Good,
				"Live monitoring via ${keeping.by.label}",
				keeping.through
					.removePrefix(keeping.by.label)
					.trimStart(',', ' ')
					.takeIf { it.isNotEmpty() }
					?.replaceFirstChar(Char::uppercase),
			)
		is Keeping.Rescanned ->
			KeptSaid(
				// Turned off, that is as it was asked to be; unavailable, it is not.
				if (keeping.why == Unfollowed.TurnedOff)
					Tone.Good
				else
					Tone.Bad,
				"Periodic rescan, ${nextIn(keeping.next)}",
				whyOf(keeping.why),
			)
		is Keeping.Unkept ->
			KeptSaid(
				Tone.Off,
				"Indexing disabled",
				when (keeping.why) {
					Unfollowed.TurnedOff -> "Live monitoring and periodic rescan are off."
					is Unfollowed.Failed -> "${whyOf(keeping.why)} Periodic rescan is off."
				} + " The index only changes when rebuilt.",
			)
	}

/** The switches for what the index does on its own: rescan on startup, live monitoring, periodic rescan, the log. */
@Composable
private fun Upkeeping(upkeep: IndexUpkeep, onUpkeep: (IndexUpkeep) -> Unit) {
	val type = LocalType.current

	@Composable
	fun Toggle(
		title: String,
		description: String,
		on: Boolean,
		tag: String,
		set: (Boolean) -> IndexUpkeep,
	) = SettingRow(title, description = description) {
		Switch(on, Modifier.testTag(tag)) { onUpkeep(set(it)) }
	}

	Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
		Text("Index updates", style = type.heading)
		Text(
			"With the first three off, the index only changes when you rebuild it. Searches " +
				"always use the index.",
			style = type.muted,
		)
		Card {
			Toggle(
				"Rescan on startup",
				"Rescan all indexed folders when prism starts.",
				upkeep.scanOnStart,
				"upkeep-scan-on-start",
			) { upkeep.copy(scanOnStart = it) }
			Toggle(
				"Live monitoring",
				if (FeedKind.here == null)
					"Update the index as files change. Not yet supported on Windows."
				else
					"Update the index as files change, using $monitorHere.",
				upkeep.watch,
				"upkeep-watch",
			) { upkeep.copy(watch = it) }
			Toggle(
				"Periodic rescan",
				"Rescan all indexed folders every 30 minutes while live monitoring is off or " +
					"unavailable.",
				upkeep.fallbackScans,
				"upkeep-fallback",
			) { upkeep.copy(fallbackScans = it) }
			Toggle(
				"Event log",
				"Record the last 60 seconds of file system events, for the index monitor.",
				upkeep.monitor,
				"upkeep-monitor",
			) { upkeep.copy(monitor = it) }
		}
	}
}

private val PROGRESS_HEIGHT = 4.dp

/** The field a rule is typed into, what it is to do, and what adds it; under them, how it reads, or why it does not. */
@Composable
private fun Adding(edits: ExcludingEdits, onChange: (ExcludingEdits) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val adding = edits.adding
	val problem = edits.addingProblem
	val kind = edits.addingKind
	val missing = remember(edits.adding.text, edits.kept) { edits.missing() }

	val colouring =
		remember(adding.text, palette, kind) {
			// A folder's path is no search, and is not coloured as one.
			QueryColouring(
				QueryText.read(
					if (kind == RuleKind.Folder)
						""
					else
						adding.text,
				),
				palette,
			)
		}

	Column(
		Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
		verticalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
		) {
			Field(
				adding.text,
				{ typed -> onChange(edits.typing { copy(text = typed) }) },
				Modifier.weight(1f).testTag("rule-add-text"),
				hint = "build/ or ~/Games",
				onKey = { key ->
					(key.key == Key.Enter || key.key == Key.NumPadEnter).also {
						if (it && key.type == KeyEventType.KeyDown)
							onChange(edits.added())
					}
				},
				colouring = colouring,
				style = type.mono,
				restsAtStart = true,
			)
			Segmented(
				Excluded.entries,
				adding.excluded,
				::labelOf,
				Modifier.width(DOING_WIDTH),
				tag = "rule-add-doing",
			) { onChange(edits.typing { copy(excluded = it) }) }
			PushButton(
				"Add",
				Emphasis.Secondary,
				enabled = edits.addable,
				Modifier.testTag("rule-add"),
				glyph = Glyph.Plus,
			) { onChange(edits.added()) }
		}
		when {
			problem != null ->
				Text(
					problem,
					style = type.muted.copy(color = palette.dangerText),
					modifier = Modifier.testTag("rule-add-problem"),
				)
			missing != null ->
				Text(
					"Folder. “${missing.fileName}” doesn't exist yet.",
					style = type.muted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.testTag("rule-add-kind"),
				)
			kind != null ->
				Text(
					when (kind) {
						RuleKind.Folder -> "Folder. Covers this folder and everything in it."
						RuleKind.Search -> "Search. Covers what it finds and everything inside each match."
					},
					style = type.muted,
					modifier = Modifier.testTag("rule-add-kind"),
				)
			edits.own.isEmpty() ->
				Text(
					"Enter a folder's path, or a search such as build/ for every folder named build.",
					style = type.muted,
				)
		}
	}
}

/**
 * One rule: for one of prism's own, the switch that turns it on or off; what it names; what it does; and for one of
 * the user's, what removes it. One switched off is drawn quieter, and does nothing to choose between.
 */
@Composable
private fun RuleRow(
	rule: Exclusions.Rule,
	edits: ExcludingEdits,
	onChange: (ExcludingEdits) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val text = remember(rule, palette) { shown(rule, edits.home, palette) }

	Row(
		Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xs),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.m),
	) {
		if (rule.builtIn)
			Switch(rule.on, Modifier.testTag("rule-on:${rule.written}")) {
				onChange(edits.switched(rule.written, it))
			}
		Text(
			text,
			style = type.mono,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier =
				Modifier
					.weight(1f)
					.alpha(
						if (rule.on)
							1f
						else
							0.5f,
					).testTag("rule:${rule.written}"),
		)
		Segmented(
			Excluded.entries,
			edits.excluded(rule),
			::labelOf,
			Modifier.width(DOING_WIDTH),
			tag = "rule-doing:${rule.written}",
			enabled = rule.on,
		) { onChange(edits.doing(rule.written, it)) }
		if (!rule.builtIn)
			ToolButton(
				Glyph.Close,
				"Remove",
				enabled = true,
				Modifier.testTag("rule-remove:${rule.written}"),
			) { onChange(edits.removed(rule.written)) }
	}
}
