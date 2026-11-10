package prism.picker

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
import prism.index.query.Problem
import prism.index.query.ReadQuery
import prism.index.query.Role
import java.nio.file.Path

/*
 * The search of the index, drawn over the picker as Everything's and Listary's are: a field across the top of a
 * window, the query coloured as it is read (each part by what it is, and what is wrong underlined), the first few
 * matches under it, and a button that shows every match in the listing. The arrows go through the matches from the
 * field, which keeps the keyboard throughout; Enter goes to the match they are on, or, from the field, shows every
 * match; Escape, or a click outside, puts the window away with what was typed kept.
 */

/** The search's window over the picker, for [search] as it stands; where a match is said from `~`, [home]. */
@Composable
internal fun SearchLayer(search: Search, home: Path, onEvent: (PickerEvent) -> Unit) {
	val palette = LocalPalette.current
	val field = remember { FocusRequester() }
	val shape = RoundedCornerShape(10.dp)

	Popup(
		alignment = Alignment.TopCenter,
		onDismissRequest = { onEvent(PickerEvent.ClosedSearch) },
		properties = PopupProperties(focusable = true),
	) {
		Box(
			Modifier
				.fillMaxSize()
				.background(palette.scrim)
				// Taps, not clicks: a click's semantics would merge everything inside into one node.
				.pointerInput(Unit) { detectTapGestures { onEvent(PickerEvent.ClosedSearch) } }
				.testTag("search-layer"),
			contentAlignment = Alignment.TopCenter,
		) {
			Column(
				Modifier
					.padding(top = 64.dp, start = 24.dp, end = 24.dp)
					.width(680.dp)
					.background(palette.surface, shape)
					.border(1.dp, palette.border, shape)
					// A tap inside is not a tap outside.
					.pointerInput(Unit) { detectTapGestures { } }
					// Escape puts the window away, whatever in it has the keyboard.
					.onPreviewKeyEvent {
						val escape = it.type == KeyEventType.KeyDown && it.key == Key.Escape

						if (escape)
							onEvent(PickerEvent.ClosedSearch)

						escape
					}.padding(12.dp),
				verticalArrangement = Arrangement.spacedBy(Dims.gap),
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

/** What the window says with no index to search. */
@Composable
private fun Unavailable(
	search: Search.Unavailable,
	focus: FocusRequester,
	onEvent: (PickerEvent) -> Unit,
) {
	val type = LocalType.current

	Text("There is no index to search", style = type.title)
	Text(search.why, style = type.body)
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
		PickerButton(
			"OK",
			Emphasis.Primary,
			enabled = true,
			Modifier.testTag("search-ok"),
			focus,
		) {
			onEvent(PickerEvent.ClosedSearch)
		}
	}
}

@Composable
private fun Searching(
	search: Search.Ready,
	home: Path,
	field: FocusRequester,
	onEvent: (PickerEvent) -> Unit,
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
				Key.DirectionDown -> PickerEvent.MatchMoved(1)
				Key.DirectionUp -> PickerEvent.MatchMoved(-1)
				Key.Enter, Key.NumPadEnter -> PickerEvent.SearchEntered
				else -> null
			}

		event?.let(onEvent)

		return event != null
	}

	Row(verticalAlignment = Alignment.CenterVertically) {
		Field(
			search.text,
			{ onEvent(PickerEvent.SearchTyped(it)) },
			Modifier.weight(1f).testTag("search-query"),
			hint = "Search everything: report, *.pdf, dir:photos, ~/Documents/",
			trailing = Glyph.Search,
			height = 40.dp,
			focus = field,
			// The keyboard arrives at the end of what was typed, to go on typing it.
			selecting = {
				androidx.compose.ui.text
					.TextRange(it.length)
			},
			onKey = ::key,
			colouring = colouring,
			style = type.body.copy(fontSize = 16.sp),
		)
		Spacer(Modifier.width(Dims.gap))
		PickerButton(
			"Search",
			Emphasis.Primary,
			enabled = search.searchable,
			Modifier.testTag("search-go"),
		) { onEvent(PickerEvent.ShowedResults) }
	}

	read.problems.firstOrNull { it.kind == Problem.Kind.Invalid }?.let {
		Text(
			it.message,
			style = type.muted.copy(color = palette.pdf),
			modifier = Modifier.testTag("search-problem"),
		)
	}

	when (val found = search.found) {
		null -> Unit
		is Finding.Refused ->
			Text(found.why, style = type.muted, modifier = Modifier.testTag("search-refused"))
		is Finding.Matched -> {
			val matches = found.matches

			Column(Modifier.testTag("matches")) {
				for (at in 0 until search.listed)
					Match(
						matches.rowOf(at, null),
						home,
						highlighted = at == search.highlighted,
					) { onEvent(PickerEvent.MatchChosen(at)) }
			}
			Text(
				when (matches.size) {
					0 -> "Nothing matches"
					1 -> "1 match"
					else -> "${"%,d".format(matches.size)} matches · Enter shows them all"
				},
				style = type.muted,
				modifier = Modifier.padding(horizontal = Dims.gapSmall).testTag("search-count"),
			)
		}
	}
}

/** One match: its icon, its name, and where it is, highlighted where the arrows are on it. */
@Composable
private fun Match(row: Row, home: Path, highlighted: Boolean, onClick: () -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()
	val shape = RoundedCornerShape(Dims.radius)

	Row(
		Modifier
			.fillMaxWidth()
			.height(36.dp)
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
			.testTag("match:${row.name}")
			.padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		GlyphIcon(
			if (row.isFolder) Glyph.Folder else Glyph.File,
			palette.text,
			Dims.icon,
			category = row.category,
		)
		Spacer(Modifier.width(Dims.gap + 2.dp))
		Text(row.name, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Spacer(Modifier.width(Dims.gap * 2))
		Text(
			folderOf(row, home),
			style = type.muted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
	}
}

/**
 * A query's text coloured as it is read: each part by its [Role], and each problem underlined, in red where it leaves
 * its term out of the search, faintly where the term is only unfinished. The text itself is not changed, so where
 * the caret is needs no mapping.
 */
private class QueryColouring(
	private val read: ReadQuery,
	private val palette: Palette,
) : VisualTransformation {
	override fun filter(text: AnnotatedString): TransformedText =
		TransformedText(coloured(text.text), OffsetMapping.Identity)

	private fun coloured(text: String): AnnotatedString =
		buildAnnotatedString {
			append(text)
			for (mark in read.marks)
				spanOf(mark.span.start, mark.span.end, text)?.let { (from, to) ->
					addStyle(SpanStyle(color = colourOf(mark.role)), from, to)
				}
			for (problem in read.problems)
				spanOf(problem.span.start, problem.span.end, text)?.let { (from, to) ->
					addStyle(
						SpanStyle(
							textDecoration = TextDecoration.Underline,
							color =
								when (problem.kind) {
									Problem.Kind.Invalid -> palette.pdf
									Problem.Kind.Unfinished -> Color.Unspecified
								},
						),
						from,
						to,
					)
				}
		}

	/** A span within [text], or null for one beyond it (marks of an older reading, for a moment) or empty. */
	private fun spanOf(start: Int, end: Int, text: String): Pair<Int, Int>? =
		(start.coerceIn(0, text.length) to end.coerceIn(0, text.length)).takeIf {
			it.first <
				it.second
		}

	private fun colourOf(role: Role): Color =
		when (role) {
			Role.Word -> palette.text
			Role.Negation, Role.Junk -> palette.pdf
			Role.Anchor, Role.Flag, Role.Modifier, Role.Colon, Role.Depth -> palette.accent
			Role.Wildcard, Role.Escape -> palette.archive
			Role.Quote -> palette.textMuted
			Role.Slash, Role.Place -> palette.folder
			Role.Regex, Role.RegexDelimiter -> palette.video
			Role.Value -> palette.image
		}

	override fun equals(other: Any?): Boolean =
		other is QueryColouring && other.read == read && other.palette == palette

	override fun hashCode(): Int = read.hashCode() * 31 + palette.hashCode()
}
