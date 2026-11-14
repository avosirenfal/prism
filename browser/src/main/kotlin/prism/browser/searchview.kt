package prism.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.nio.file.Path
import prism.index.query.Problem
import prism.index.query.QueryHelp
import prism.index.query.QueryText
import prism.index.query.ReadQuery
import prism.index.query.Role
import prism.window.WindowLocals

/*
 * The search of the index, drawn over the picker as Everything's and Listary's are: a field across the top of a
 * window, the query coloured as it is read (each part by what it is, and what is wrong underlined), the matches
 * under it, a few in sight at once ([ResultList]), and a button that shows every match in the listing. The arrows, and
 * Page Up and Down, go through the matches from the field, which keeps the keyboard throughout, the list scrolling
 * to keep the one they are on in sight; Enter goes to the match they are on, or, from the field, shows every
 * match; Escape, or a click outside, puts the window away. Its field is empty each time it opens. A `?` beside the
 * field shows how a query is written, in a sentence and a handful of queries ([QueryHelp]), each of which is typed
 * into the field when clicked.
 */

/** The search's window over the picker, for [search] as it stands; where a match is said from `~`, [home]. */
@Composable
internal fun SearchLayer(search: Search, home: Path, onEvent: (BrowserEvent) -> Unit) {
	val palette = LocalPalette.current
	val field = remember { FocusRequester() }
	val shape = RoundedCornerShape(Radius.large)
	val bindings = LocalBindings.current

	Popup(
		alignment = Alignment.TopCenter,
		onDismissRequest = { onEvent(BrowserEvent.ClosedSearch) },
		properties = PopupProperties(focusable = true),
	) {
		WindowLocals {
			Box(
				Modifier
					.fillMaxSize()
					.background(palette.scrim)
					// Taps, not clicks: a click's semantics would merge everything inside into one node.
					.pointerInput(Unit) { detectTapGestures { onEvent(BrowserEvent.ClosedSearch) } }
					.testTag("search-layer"),
				contentAlignment = Alignment.TopCenter,
			) {
				Column(
					Modifier
						.padding(top = 72.dp, start = Space.xl, end = Space.xl)
						.width(680.dp)
						.shadow(24.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
						.background(palette.overlay, shape)
						.border(1.dp, palette.border, shape)
						// A tap inside is not a tap outside.
						.pointerInput(Unit) { detectTapGestures { } }
						// Escape puts the window away, whatever in it has the keyboard. The chord that opens it, pressed
						// while it is open (the window holds the keyboard, so the browser behind it is not told), starts
						// the search again: the field emptied, and the keyboard in it.
						.onPreviewKeyEvent {
							val down = it.type == KeyEventType.KeyDown
							val escape = down && it.key == Key.Escape
							val again = down && Chord.of(it)?.let(bindings::window) == Action.SearchIndex

							when {
								escape -> onEvent(BrowserEvent.ClosedSearch)
								again -> {
									onEvent(BrowserEvent.OpenedSearch)
									field.requestFocus()
								}
							}

							escape || again
						}.padding(Space.l),
					verticalArrangement = Arrangement.spacedBy(Space.m),
				) {
					when (search) {
						is Search.Unavailable -> Unavailable(search, field, onEvent)
						is Search.Ready -> Searching(search, home, field, onEvent)
					}
				}
			}
			LaunchedEffect(Unit) { field.requestFocus() }
		}
	}
}

/** What the window says with no index to search. */
@Composable
private fun Unavailable(
	search: Search.Unavailable,
	focus: FocusRequester,
	onEvent: (BrowserEvent) -> Unit,
) {
	val type = LocalType.current

	Text("There is no index to search", style = type.title)
	Text(search.why, style = type.body)
	ButtonRow(Act("OK", "search-ok", focus = focus) { onEvent(BrowserEvent.ClosedSearch) })
}

@Composable
private fun Searching(
	search: Search.Ready,
	home: Path,
	field: FocusRequester,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val read = search.read
	val colouring = remember(read, palette) { QueryColouring(read, palette) }

	fun key(key: KeyEvent): Boolean {
		if (key.type != KeyEventType.KeyDown)
			return false

		val event =
			when (key.key) {
				Key.DirectionDown -> BrowserEvent.MatchMoved(1)
				Key.DirectionUp -> BrowserEvent.MatchMoved(-1)
				Key.PageDown -> BrowserEvent.MatchMoved(Search.SHOWN)
				Key.PageUp -> BrowserEvent.MatchMoved(-Search.SHOWN)
				Key.Enter, Key.NumPadEnter -> BrowserEvent.SearchEntered
				else -> null
			}

		event?.let(onEvent)

		return event != null
	}

	Row(verticalAlignment = Alignment.CenterVertically) {
		Field(
			search.text,
			{ onEvent(BrowserEvent.SearchTyped(it)) },
			Modifier.weight(1f).testTag("search-query"),
			hint = "Search everything: report, .pdf, dir:photos, ~/Documents/",
			leading = Glyph.Search,
			height = type.control + Space.s,
			focus = field,
			// The keyboard arrives at the end of what was typed, to go on typing it.
			selecting = {
				androidx.compose.ui.text
					.TextRange(it.length)
			},
			onKey = ::key,
			colouring = colouring,
			style = type.body.times(1.15f),
		)
		Spacer(Modifier.width(Space.s))
		// The field keeps the keyboard, whatever is clicked beside it.
		PushButton("?", Emphasis.Quiet, enabled = true, Modifier.testTag("search-help")) {
			onEvent(BrowserEvent.ToggledSearchHelp)
			field.requestFocus()
		}
		Spacer(Modifier.width(Space.xs))
		PushButton(
			"Search",
			Emphasis.Primary,
			enabled = search.searchable,
			Modifier.testTag("search-go"),
		) { onEvent(BrowserEvent.ShowedResults) }
	}

	read.problems.firstOrNull { it.kind == Problem.Kind.Invalid }?.let {
		Text(
			it.message,
			style = type.muted.copy(color = palette.dangerText),
			modifier = Modifier.testTag("search-problem"),
		)
	}

	when (val found = search.found) {
		null -> Unit
		is Finding.Refused ->
			Text(found.why, style = type.muted, modifier = Modifier.testTag("search-refused"))
		is Finding.Failed -> {
			Text(
				"The search failed. That is a mistake of prism's, not of what you typed.",
				style = type.body.copy(color = palette.dangerText),
				modifier = Modifier.testTag("search-failed"),
			)
			Text(
				"${found.fault.line}. It is said in full in prism's log.",
				style = type.muted,
				modifier = Modifier.testTag("search-failure"),
			)
		}
		is Finding.Matched -> {
			val matches = found.matches

			ResultList(
				search.listed,
				search.highlighted,
				rowHeight = type.row + Space.xs,
				height = (type.row + Space.xs) * Search.SHOWN,
				tag = "matches",
				rowTag = { "match:${matches.rank(it)?.let(matches::nameOf).orEmpty()}" },
				onChoose = { onEvent(BrowserEvent.MatchChosen(it)) },
			) { at -> matches.rank(at)?.let { Match(matches.rowOf(it, null), home) } }
			Text(
				when (matches.size) {
					0 -> "Nothing matches"
					1 -> "1 match"
					else -> "${"%,d".format(matches.size)} matches · Enter shows them all"
				},
				style = type.muted,
				modifier =
					Modifier
						.padding(
							horizontal = Space.s,
							vertical = Space.xxs,
						).testTag("search-count"),
			)
		}
	}
}

/** One match, in its row: its icon, its name, and where it is. */
@Composable
private fun RowScope.Match(row: Row, home: Path) {
	val palette = LocalPalette.current
	val type = LocalType.current

	GlyphIcon(
		if (row.isFolder) Glyph.Folder else Glyph.File,
		palette.text,
		type.icon,
		category = row.category,
	)
	Spacer(Modifier.width(Space.s + Space.xxs))
	ResultText(row.name, row.path?.let { folderOf(it, home) }.orEmpty())
}
