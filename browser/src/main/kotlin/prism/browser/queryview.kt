package prism.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import prism.index.query.Own
import prism.index.query.Problem
import prism.index.query.QueryHelp
import prism.index.query.QueryText
import prism.index.query.ReadQuery
import prism.index.query.Role

/*
 * What every window that searches draws alike, an explorer's search and the launcher: a query coloured as it is read;
 * how a query is written, as a page of its own in place of the results; what a key does, with the key on a cap; and
 * the row of something of prism's own that a search found ([Own]).
 */

/**
 * How a query is written ([QueryHelp]), in place of a search's field and results: what it is, a sentence or two, and
 * a query a line with what it finds, each coloured as the field would colour it and given to [onTry] when clicked,
 * to be typed there and tried. It is left by its × or by Escape ([onClose]), back to the search it was asked from. It
 * takes the keyboard while it is shown, since the field it took the place of is not there to have it. Where it
 * [fills] the height it is given (a window of a size of its own, the launcher's), what is said along its bottom is at
 * the bottom of that; otherwise it is as tall as what it shows.
 */
@Composable
fun QueryHelpPanel(
	onTry: (String) -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
	fills: Boolean = false,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val focus = remember { FocusRequester() }

	LaunchedEffect(Unit) { focus.requestFocus() }

	Column(
		modifier
			.fillMaxWidth()
			.focusRequester(focus)
			.focusable()
			.onKeyEvent {
				val left = it.type == KeyEventType.KeyDown && it.key == Key.Escape

				if (left)
					onClose()
				left
			}.testTag("search-help-shown"),
	) {
		Row(
			Modifier.fillMaxWidth().padding(start = Space.l, end = Space.m, top = Space.m),
			verticalAlignment = Alignment.CenterVertically,
		) {
			GlyphIcon(Glyph.Help, palette.textMuted, type.icon)
			Spacer(Modifier.width(Space.s + Space.xxs))
			Text(Own.Help.title, style = type.title, modifier = Modifier.weight(1f))
			ToolButton(
				Glyph.Close,
				"Back to the search (Esc)",
				enabled = true,
				Modifier.testTag("search-help-close"),
				onClick = onClose,
			)
		}
		Text(
			QueryHelp.SUMMARY,
			style = type.muted,
			modifier =
				Modifier
					.padding(
						horizontal = Space.l,
					).padding(top = Space.xs, bottom = Space.m),
		)
		Divider()
		ScrollArea(
			Modifier.weight(1f, fill = fills),
			padding = PaddingValues(start = Space.s, top = Space.s, bottom = Space.s),
		) {
			for (example in QueryHelp.EXAMPLES)
				Example(example) { onTry(example.text) }
		}
		Divider()
		Row(
			Modifier
				.fillMaxWidth()
				.background(palette.pane)
				.padding(horizontal = Space.l, vertical = Space.s),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				"Click a query to try it",
				style = type.muted,
				modifier = Modifier.weight(1f),
			)
			KeyHint("Back", listOf("Esc"))
		}
	}
}

/** One query and what it finds, in a row that is tinted under the pointer and tried when clicked. */
@Composable
private fun Example(example: QueryHelp.Example, onTry: () -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()

	val coloured =
		remember(example, palette) {
			QueryColouring(QueryText.read(example.text), palette)
				.filter(AnnotatedString(example.text))
				.text
		}

	Row(
		Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(Radius.medium))
			.background(if (hovered) palette.hover else Color.Transparent)
			.hoverable(hover)
			.clickable(onClick = onTry)
			.padding(horizontal = Space.m, vertical = Space.xxs + 1.dp)
			.testTag("help:${example.text}"),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(coloured, style = type.mono, maxLines = 1, modifier = Modifier.width(180.dp))
		Text(
			example.finds,
			style = type.muted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
	}
}

/** What a key does, and the key: each part of the chord on a cap of its own. */
@Composable
fun KeyHint(label: String, chord: Chord) = KeyHint(label, capsOf(chord))

/** What a key does, and the key, as the [caps] it is pressed on. */
@Composable
fun KeyHint(label: String, caps: List<String>) {
	val type = LocalType.current

	Row(
		horizontalArrangement = Arrangement.spacedBy(Space.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(label, style = type.muted, maxLines = 1)
		Spacer(Modifier.width(Space.xxs))
		for (cap in caps)
			KeyCap(cap)
	}
}

/** A key, as the cap it is pressed on. */
@Composable
fun KeyCap(cap: String) {
	val palette = LocalPalette.current
	val shape = RoundedCornerShape(Radius.small)

	Box(
		Modifier
			.clip(shape)
			.background(palette.sunken)
			.border(1.dp, palette.border, shape)
			.padding(horizontal = Space.xs + Space.xxs, vertical = 1.dp),
	) {
		Text(cap, style = LocalType.current.heading.copy(color = palette.text), maxLines = 1)
	}
}

/** [chord] as the caps it is pressed on: `Ctrl`, `↵`. */
fun capsOf(chord: Chord): List<String> =
	chord.toString().split('+').map {
		when (it) {
			"Enter" -> "↵"
			"Escape" -> "Esc"
			"Comma" -> ","
			"Up" -> "↑"
			"Down" -> "↓"
			else -> it
		}
	}

/** The icon of something of prism's own. */
val Own.glyph: Glyph
	get() =
		when (this) {
			Own.Settings -> Glyph.Settings
			Own.Bindings -> Glyph.Keyboard
			Own.System -> Glyph.System
			Own.Help -> Glyph.Help
		}

/** The row of something of prism's own that a search found: its icon, what it is called and does, and whose it is. */
@Composable
fun RowScope.OwnResult(own: Own) {
	val palette = LocalPalette.current
	val type = LocalType.current

	GlyphIcon(own.glyph, palette.accentText, type.icon)
	Spacer(Modifier.width(Space.s + Space.xxs))
	ResultText(own.title, own.does, kind = "prism")
}

/**
 * A query's text coloured as it is read: each part by its [Role], and each problem underlined, in red where it leaves
 * its term out of the search, faintly where the term is only unfinished. The text itself is not changed, so where
 * the caret is needs no mapping.
 */
class QueryColouring(
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
