package prism.launcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.map
import prism.browser.Bindings
import prism.browser.Chord
import prism.browser.Divider
import prism.browser.EmptyState
import prism.browser.Glyph
import prism.browser.ResultList
import prism.browser.ResultText
import prism.browser.Scaled

/*
 * A dmenu drawn: the launcher's window with the lines it was given in place of what the launcher finds. The field,
 * the list, the line along the bottom and the edge around them are the launcher's own (view.kt), and so are its keys,
 * as the user has bound them; a line is its text and nothing else.
 */

/**
 * The dmenu [session] runs, drawn in the launcher's window: its state as it changes, what the user does sent to it,
 * each key what [bindings] say. Its window given up (another given the keyboard), it is put away, where the user has
 * the launcher so.
 */
@Composable
fun DmenuView(session: DmenuSession, bindings: Bindings) {
	val state by session.state.collectAsState()

	PutAwayUnfocused(state.preferences.closeUnfocused) { session.send(DmenuEvent.Closed) }

	val queries = remember(session) { session.state.map { it.query } }
	var typed by typedOf(queries, state.query)

	Scaled(state.preferences.scale.times) {
		DmenuPane(
			state,
			bindings,
			typed,
			corners = cornersHere(state.preferences),
			onTyped = {
				val changed = it.text != typed.text

				typed = it
				if (changed)
					session.send(DmenuEvent.Typed(it.text))
			},
			onEvent = session::send,
		)
	}
}

/**
 * The dmenu as [state] has it, what is in its field being [typed], its [corners] square or round: what [DmenuView]
 * draws, and the tests.
 */
@Composable
fun DmenuPane(
	state: DmenuState,
	bindings: Bindings = launcherBindings(),
	typed: TextFieldValue = TextFieldValue(state.query, TextRange(state.query.length)),
	corners: Corners = Corners.Square,
	onTyped: (TextFieldValue) -> Unit = {},
	onEvent: (DmenuEvent) -> Unit = {},
) {
	val field = remember { FocusRequester() }
	val asked = state.asked

	Frame(
		corners,
		onKey = { key ->
			// A key that is the launcher's alone (its settings, a result's menu) is nobody's here, and is left to
			// the field: the right arrow moves its caret.
			val event =
				(Chord.of(key)?.let(bindings::action) as? LauncherAction)
					?.takeIf { key.type == KeyEventType.KeyDown }
					?.let { dmenuEventOf(it, state.preferences.rows.number - 1) }

			event?.let(onEvent)

			event != null
		},
	) {
		QueryField(
			asked.placeholder,
			typed,
			field,
			onTyped,
			searching = !state.settled,
			redacted = asked.redacted,
		)
		Divider()
		Box(Modifier.weight(1f).fillMaxWidth().padding(LauncherDims.around)) {
			if (state.lines.isNotEmpty())
				ResultList(
					state.lines.size,
					state.highlighted,
					LauncherDims.row,
					height = Dp.Infinity,
					tag = "launcher-results",
					heading = { at -> state.heading.takeIf { at == 0 } },
					rowTag = { "line:${state.lines[it].at}" },
					onChoose = { onEvent(DmenuEvent.Chosen(it)) },
				) { at ->
					ResultText(state.lines[at].text, "")
				}
			// Given lines, and none of them fits: said, with what Enter then answers. Given none, it is a question
			// with no choices to it, and what is typed is the answer from the start.
			else if (asked.lines.isNotEmpty() && state.settled)
				EmptyState(
					Glyph.Search,
					"No matches",
					"Press Enter to use “${state.query}”.".takeUnless { asked.redacted },
					Modifier.fillMaxSize(),
					detailTag = "launcher-nothing",
				)
		}
		if (asked.footer) {
			Divider()
			FooterBar(
				listOfNotNull(asked.title, countOf(state)).joinToString(" · "),
				wrong = false,
				listOfNotNull(
					when {
						state.current != null -> LauncherAction.Open to "Select"
						state.query.isNotEmpty() -> LauncherAction.Open to "Accept"
						else -> null
					},
					LauncherAction.Dismiss to
						if (state.query.isEmpty())
							"Cancel"
						else
							"Clear",
				),
				bindings,
			)
		}
	}

	LaunchedEffect(Unit) { field.requestFocus() }
}

/** How many lines there are, and how many of them are shown where that is fewer; nothing, where it was given none. */
private fun countOf(state: DmenuState): String? {
	val all = state.asked.lines.size
	val shown = state.lines.size

	return when {
		all == 0 -> null
		shown < all -> "${"%,d".format(shown)} of ${"%,d".format(all)}"
		all == 1 -> "1 item"
		else -> "${"%,d".format(all)} items"
	}
}
