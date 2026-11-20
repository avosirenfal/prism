package prism.explorer

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import prism.browser.Card
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.Finder
import prism.browser.Glyph
import prism.browser.LocalIndexReading
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Palette
import prism.browser.PushButton
import prism.browser.QueryColouring
import prism.browser.Segmented
import prism.browser.SettingRow
import prism.browser.Space
import prism.browser.Switch
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
	/** The index kept here, to rebuild and to watch; null where none is kept. */
	index: Finder? = null,
	onChange: (ExcludingEdits) -> Unit,
) {
	val type = LocalType.current

	PageLayout(
		"Indexing",
		"Ignored items aren't indexed. Hidden items are indexed, but found only when a search " +
			"includes their path. A rule also applies to everything inside what it matches.",
	) {
		if (index != null)
			Rebuilding(index)
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
 * The index itself: whether it is being read and how far that has got, or, where it is not, how it is kept as the
 * disk changes; a button that reads it anew, for when something seems missing from it all the same; and one that
 * opens the index monitor, where what it hears is kept to be looked at.
 */
@Composable
private fun Rebuilding(index: Finder) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val reading = LocalIndexReading.current
	val keeping by produceState(index.keeping(), index) {
		while (true) {
			value = index.keeping()
			delay(KEEPING_LOOKED_AT)
		}
	}
	var monitoring by remember { mutableStateOf<Monitoring>(Monitoring.Closed) }
	val log = index.activity

	Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
		Text("Index", style = type.heading)
		Card {
			Row(
				Modifier.fillMaxWidth().padding(Space.m),
				horizontalArrangement = Arrangement.spacedBy(Space.m),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Column(
					Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(Space.s),
				) {
					val said = keeping?.let(::saidOf)

					Text(
						reading?.said() ?: said?.first ?: "The index is up to date.",
						style = type.body,
						modifier = Modifier.testTag("index-state"),
					)
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
					else
						Text(
							said?.second ?: "Rebuilding reads every folder again.",
							style = type.muted,
							modifier = Modifier.testTag("index-state-detail"),
						)
				}
				if (log != null)
					PushButton(
						"Monitor",
						Emphasis.Secondary,
						enabled = true,
						Modifier.testTag("index-monitor-open"),
					) { monitoring = Monitoring.Bursts() }
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
	if (log != null)
		IndexMonitor(monitoring, log, index::folderAt) { monitoring = it }
}

/** What follows changes as they happen on this platform, as the page names it. */
private val followerHere: String
	get() =
		when (Os.current) {
			Os.Linux -> "the eBPF probe"
			Os.MacOS -> "FSEvents"
			Os.Windows -> "continuous monitoring"
		}

/** What follows the changes, as the page names it. */
private fun nameOf(kind: FeedKind): String =
	when (kind) {
		FeedKind.EbpfProbe -> "the eBPF probe"
		FeedKind.FsEvents -> "FSEvents"
		FeedKind.Other -> "a watcher"
	}

/** Why nothing follows the changes, as the page says it under how the index is kept. */
private fun detailOf(why: Unfollowed): String =
	when (why) {
		Unfollowed.TurnedOff -> "Continuous monitoring is turned off below."
		is Unfollowed.Failed ->
			why.why.replaceFirstChar(Char::uppercase).let {
				if (it.endsWith('.'))
					it
				else
					"$it."
			}
	}

/** How the index is kept, as the page says it: a line, and a line under it of why, or of what Rebuild does. */
internal fun saidOf(keeping: Keeping): Pair<String, String> =
	when (keeping) {
		is Keeping.Followed ->
			"Changes are followed as they happen by ${nameOf(keeping.by)}." to
				"Through ${keeping.through}. Rebuilding reads every folder again."
		is Keeping.Rescanned -> {
			val minutes = keeping.every.inWholeMinutes
			val instead = "so every folder is read again every $minutes minutes"

			when (keeping.why) {
				Unfollowed.TurnedOff -> "Changes aren't followed as they happen, $instead."
				is Unfollowed.Failed ->
					"${followerHere.replaceFirstChar(Char::uppercase)} isn't running, $instead."
			} to detailOf(keeping.why)
		}
		is Keeping.Unkept ->
			"The index isn't kept up to date on its own: rebuild it to bring it up to date." to
				detailOf(keeping.why).let {
					if (keeping.why == Unfollowed.TurnedOff)
						"Continuous monitoring and fallback scans are turned off below."
					else
						"$it Fallback scans are turned off below."
				}
	}

/**
 * What the index does on its own, each turned on or off: read every folder as prism starts, follow changes as they
 * happen, read every folder now and then while nothing follows them, and keep what it hears for the monitor.
 */
@Composable
private fun Upkeeping(upkeep: IndexUpkeep, onUpkeep: (IndexUpkeep) -> Unit) {
	val type = LocalType.current

	Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
		Text("Keeping the index up to date", style = type.heading)
		Text(
			"With the first three off, the index changes only when you rebuild it. Searches use it either way.",
			style = type.muted,
		)
		Card {
			SettingRow(
				"Scan on start",
				description =
					"Read every folder when prism starts, to find what changed while it " +
						"wasn't running.",
			) {
				Switch(upkeep.scanOnStart, Modifier.testTag("upkeep-scan-on-start")) {
					onUpkeep(upkeep.copy(scanOnStart = it))
				}
			}
			SettingRow(
				"Continuous monitoring",
				description =
					if (Os.current == Os.Windows)
						"Follow changes as they happen. Prism can't do this on Windows yet."
					else
						"Follow changes as they happen, with $followerHere.",
			) {
				Switch(upkeep.watch, Modifier.testTag("upkeep-watch")) {
					onUpkeep(upkeep.copy(watch = it))
				}
			}
			SettingRow(
				"Fallback scans",
				description =
					"While changes aren't followed as they happen, read every folder " +
						"again now and then.",
			) {
				Switch(upkeep.fallbackScans, Modifier.testTag("upkeep-fallback")) {
					onUpkeep(upkeep.copy(fallbackScans = it))
				}
			}
			SettingRow(
				"Index monitoring",
				description =
					"Keep the last minute of changes the index hears, to look at with Monitor.",
			) {
				Switch(upkeep.monitor, Modifier.testTag("upkeep-monitor")) {
					onUpkeep(upkeep.copy(monitor = it))
				}
			}
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
