package prism.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import prism.browser.Card
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Palette
import prism.browser.PushButton
import prism.browser.QueryColouring
import prism.browser.Segmented
import prism.browser.Space
import prism.browser.Switch
import prism.browser.ToolButton
import prism.browser.colouredQuery
import prism.index.Exclusions
import prism.index.Naming
import prism.index.query.Aliases
import prism.index.query.QueryText
import prism.preferences.Excluded
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

/** The page of the rules over [edits], each change reported to [onChange]. */
@Composable
internal fun ExcludingPage(edits: ExcludingEdits, onChange: (ExcludingEdits) -> Unit) {
	val type = LocalType.current

	PageLayout(
		"Indexing",
		"Rules for what the index leaves out. A rule covers what it names and " +
			"everything inside it. Ignored items are not indexed. Hidden items are " +
			"indexed, and found only by a search that includes their path.",
	) {
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
