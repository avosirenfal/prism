package prism.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import prism.browser.Card
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.PushButton
import prism.browser.QueryColouring
import prism.browser.Space
import prism.browser.ToolButton
import prism.browser.colouredQuery
import prism.index.query.AliasName
import prism.index.query.AliasProblem
import prism.index.query.QueryText
import prism.browser.Page as PageLayout

/*
 * The page of search aliases, in the preferences' window and in the launcher's settings alike: each alias a row, its
 * name and the query it stands for, both typed where they are shown, the query coloured as a search's field colours
 * one. What is wrong with a row is said under it as it is typed, and while anything is, the window does not apply
 * (aliases.kt). With none yet, the page shows what one is by two that can be added as they are.
 *
 * The keyboard goes through a row as it reads: Enter in a name goes to its query, and in a query to the next row,
 * a new one after the last.
 */

/** How wide a name's field is: room for a dozen letters and the `@` after them. */
private val NAME_WIDTH = 190.dp

/** The aliases' page over [edits], each change reported to [onChange]. */
@Composable
internal fun AliasesPage(edits: AliasEdits, onChange: (AliasEdits) -> Unit) {
	// The row whose name the keyboard goes to once it is there: one just added.
	var entering by remember { mutableStateOf<Int?>(null) }

	fun add() {
		entering = edits.drafts.size
		onChange(edits.added())
	}

	PageLayout(
		"Aliases",
		"Short names for searches. Type a name followed by @ in any search to use it.",
	) {
		if (edits.drafts.isEmpty())
			NoAliases { onChange(edits.added(it)) }
		else
			Card {
				Headings()
				// One part of the card, so that no line is drawn between rows: each is two fields with edges of
				// their own, and a line between every two of them is more lines than there is anything to part.
				Column(Modifier.padding(vertical = Space.s)) {
					edits.drafts.forEachIndexed { at, draft ->
						AliasRow(
							at,
							draft,
							edits,
							enter = entering == at,
							onEntered = { entering = null },
							onChange = onChange,
							onNext = {
								if (at == edits.drafts.lastIndex) {
									if (!draft.blank)
										add()
								} else {
									entering = at + 1
								}
							},
						)
					}
				}
			}
		Row {
			PushButton(
				"Add alias",
				Emphasis.Secondary,
				enabled = true,
				Modifier.testTag("alias-add"),
				glyph = Glyph.Plus,
			) { add() }
		}
	}
}

/** What each column of the rows is. */
@Composable
private fun Headings() {
	val type = LocalType.current

	Row(
		Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		Text("Name", style = type.heading, modifier = Modifier.width(NAME_WIDTH))
		Text("Search", style = type.heading, modifier = Modifier.weight(1f))
	}
}

/**
 * The [at]th alias: its name, its query, and what removes it; under them what is wrong with it, or, of a query that
 * names a folder without the `/` that looks inside it, that it does. It takes the keyboard to its name where it is
 * to [enter], and says so ([onEntered]).
 */
@Composable
private fun AliasRow(
	at: Int,
	draft: AliasDraft,
	edits: AliasEdits,
	enter: Boolean,
	onEntered: () -> Unit,
	onChange: (AliasEdits) -> Unit,
	onNext: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val name = remember { FocusRequester() }
	val query = remember { FocusRequester() }
	val problem = edits.problemAt(at)
	val hint = remember(edits, at) { edits.hintAt(at) }

	val colouring =
		remember(draft.query, edits.aliases, palette) {
			QueryColouring(QueryText.read(draft.query, edits.aliases), palette)
		}

	LaunchedEffect(enter) {
		if (enter) {
			name.requestFocus()
			onEntered()
		}
	}

	/** Enter, pressed: [then] done, and the key taken. */
	fun onEnter(key: KeyEvent, then: () -> Unit): Boolean =
		(key.key == Key.Enter || key.key == Key.NumPadEnter).also {
			if (it && key.type == KeyEventType.KeyDown)
				then()
		}

	Column(
		Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xs),
		verticalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
		) {
			Field(
				draft.name,
				// The `@` is how a name is used, and is shown after the field: typed here, it is not part of the name.
				{ typed ->
					onChange(edits.set(at) { copy(name = typed.removeSuffix("${AliasName.SIGN}")) })
				},
				Modifier.width(NAME_WIDTH).testTag("alias-name:$at"),
				hint = "name",
				trailing = Glyph.At,
				focus = name,
				onKey = { key -> onEnter(key) { query.requestFocus() } },
				style = type.mono,
			)
			Field(
				draft.query,
				{ typed -> onChange(edits.set(at) { copy(query = typed) }) },
				Modifier.weight(1f).testTag("alias-query:$at"),
				hint = "Search",
				focus = query,
				onKey = { key -> onEnter(key, onNext) },
				colouring = colouring,
				style = type.mono,
				restsAtStart = true,
			)
			ToolButton(
				Glyph.Close,
				"Remove",
				enabled = true,
				Modifier.testTag("alias-remove:$at"),
			) { onChange(edits.removed(at)) }
		}
		when {
			problem != null ->
				Text(
					problem.message,
					style = type.muted.copy(color = palette.dangerText),
					modifier =
						Modifier
							.padding(
								start =
									if (problem.ofName)
										0.dp
									else
										NAME_WIDTH + Space.s,
							).testTag("alias-problem:$at"),
				)
			hint != null ->
				Row(
					Modifier.padding(start = NAME_WIDTH + Space.s),
					horizontalArrangement = Arrangement.spacedBy(Space.s),
				) {
					Text(
						"“${hint.name}” is a folder. End it with / to search inside it.",
						style = type.muted,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f, fill = false).testTag("alias-hint:$at"),
					)
					Text(
						"Add /",
						style = type.muted.copy(color = palette.accentText),
						maxLines = 1,
						modifier =
							Modifier.testTag("alias-mend:$at").clickable {
								onChange(edits.set(at) { copy(query = hint.mended) })
							},
					)
				}
		}
	}
}

/** Whether it is the name that is wrong, and not the query. */
private val AliasProblem.ofName: Boolean
	get() =
		when (this) {
			AliasProblem.Unnamed, AliasProblem.Misnamed, is AliasProblem.Taken -> true
			AliasProblem.Empty, is AliasProblem.Wrong -> false
		}

/** In place of the rows, where there are none: what an alias is, by two that are added as they are when chosen. */
@Composable
private fun NoAliases(onAdd: (AliasDraft) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Card {
		Column(
			Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.l),
			verticalArrangement = Arrangement.spacedBy(Space.s),
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.s),
			) {
				GlyphIcon(Glyph.At, palette.textMuted, type.icon)
				Text("No aliases", style = type.body)
			}
			Text("Examples", style = type.heading, modifier = Modifier.padding(top = Space.s))
			for (example in AliasEdits.EXAMPLES)
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Space.s),
				) {
					val aliases = remember { AliasEdits(AliasEdits.EXAMPLES).aliases }

					Text(
						remember(palette) { colouredQuery("${example.name}@", aliases, palette) },
						style = type.mono,
						maxLines = 1,
						modifier = Modifier.width(NAME_WIDTH),
					)
					Text(
						remember(palette) { colouredQuery(example.query, aliases, palette) },
						style = type.mono,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f),
					)
					Spacer(Modifier.width(Space.s))
					PushButton(
						"Add",
						Emphasis.Secondary,
						enabled = true,
						Modifier.testTag("alias-example:${example.name}"),
					) { onAdd(example) }
				}
		}
	}
}
