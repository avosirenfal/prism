package prism.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import prism.browser.Chord
import prism.browser.Divider
import prism.browser.EmptyState
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Radius
import prism.browser.ResultList
import prism.browser.ResultText
import prism.browser.Scaled
import prism.browser.Space
import prism.browser.Spinner
import prism.browser.times
import prism.preferences.LauncherPreferences

/*
 * The launcher, drawn as Raycast's and Vicinae's are: a field across the top, large, with nothing around it but the
 * window; under it what was found, in groups, each result a row with its picture, its name, what is said of it and
 * what kind of thing it is; and along the bottom what the keys do to the result the arrows are on. The field keeps the
 * keyboard throughout: the arrows move through the results from it, the list scrolling to keep the one they are on in
 * sight, and every key is one the user can bind again (bindings.kt).
 */

/** The sizes of the launcher's parts, which no other window shares. */
object LauncherDims {
	val field = 64.dp
	val row = 46.dp
	val icon = 28.dp
	val footer = 42.dp

	/** The room around the list of results, between the field's rule and the footer's. */
	val around = Space.s

	/**
	 * How tall the launcher's window is with [rows] results in sight: the field, the results with a heading's room
	 * over them, and the footer.
	 */
	fun heightOf(rows: Int): Dp = field + rows * row + heading + 2 * around + footer + 2.dp

	/** How wide and tall its window is, as [preferences] have it, in the units a window is sized in. */
	fun sizeOf(preferences: LauncherPreferences): Pair<Dp, Dp> =
		preferences.bounded.let {
			(it.width * it.scale).dp to heightOf(it.rows) * it.scale
		}

	private val heading = 36.dp
}

/**
 * The launcher [session] runs, drawn in a window of its own: its state as it changes, what the user does sent to it,
 * each key what [bindings] say. Its window given up (another given the keyboard), it is put away, where the user has
 * it so.
 */
@Composable
fun LauncherView(session: LauncherSession, bindings: LauncherBindings) {
	val state by session.state.collectAsState()
	val focused = LocalWindowInfo.current.isWindowFocused
	var wasFocused by remember { mutableStateOf(false) }

	LaunchedEffect(focused) {
		if (focused)
			wasFocused = true
		else if (wasFocused && state.preferences.closeUnfocused)
			session.send(LauncherEvent.Closed)
	}

	// What is typed is the field's own until the session's differs: cleared by Escape, say.
	var typed by remember { mutableStateOf(TextFieldValue(state.query)) }

	LaunchedEffect(session) {
		session.state.map { it.query }.distinctUntilChanged().collect { query ->
			if (query != typed.text)
				typed = TextFieldValue(query, TextRange(query.length))
		}
	}

	Scaled(state.preferences.scale) {
		LauncherPane(
			state,
			bindings,
			typed,
			onTyped = {
				val changed = it.text != typed.text

				typed = it
				if (changed)
					session.send(LauncherEvent.Typed(it.text))
			},
			onEvent = session::send,
		)
	}
}

/** The launcher as [state] has it, what is in its field being [typed]: what [LauncherView] draws, and the tests. */
@Composable
fun LauncherPane(
	state: LauncherState,
	bindings: LauncherBindings = LauncherBindings.DEFAULT,
	typed: TextFieldValue = TextFieldValue(state.query, TextRange(state.query.length)),
	onTyped: (TextFieldValue) -> Unit = {},
	onEvent: (LauncherEvent) -> Unit = {},
) {
	val palette = LocalPalette.current
	val field = remember { FocusRequester() }

	Column(
		Modifier
			.fillMaxSize()
			.background(palette.overlay)
			.border(1.dp, palette.border)
			.onPreviewKeyEvent { key ->
				val action =
					Chord.of(key)?.let(bindings::action)?.takeIf { key.type == KeyEventType.KeyDown }

				action?.let { onEvent(eventOf(it, state.preferences.rows - 1)) }

				action != null
			}.testTag("launcher"),
	) {
		QueryField(state, typed, field, onTyped)
		Divider()
		Box(Modifier.weight(1f).fillMaxWidth().padding(LauncherDims.around)) {
			if (state.entries.isEmpty())
				Nothing(state)
			else
				ResultList(
					state.entries.size,
					state.highlighted,
					LauncherDims.row,
					height = Dp.Infinity,
					tag = "launcher-results",
					heading = state::headingAt,
					rowTag = { "entry:${state.entries[it].key}" },
					onChoose = { onEvent(LauncherEvent.Chosen(it)) },
				) { at ->
					val entry = state.entries[at]

					EntryIcon(entry, LauncherDims.icon)
					Spacer(Modifier.width(Space.m))
					ResultText(entry.title, entry.detail, entry.kind)
				}
		}
		Divider()
		Footer(state, bindings)
	}

	LaunchedEffect(Unit) { field.requestFocus() }
}

/** The field: a magnifier, what is typed, large, and a spinner while the index is searched. */
@Composable
private fun QueryField(
	state: LauncherState,
	typed: TextFieldValue,
	focus: FocusRequester,
	onTyped: (TextFieldValue) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val style = type.body.times(1.3f)

	Row(
		Modifier.fillMaxWidth().height(LauncherDims.field).padding(
			horizontal =
				Space.xl - Space.xs,
		),
		verticalAlignment = Alignment.CenterVertically,
	) {
		GlyphIcon(Glyph.Search, palette.textMuted, 20.dp)
		Spacer(Modifier.width(Space.m + Space.xxs))
		Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
			if (typed.text.isEmpty())
				Text(
					"Search applications and files…",
					style = style.copy(color = palette.textFaint),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			BasicTextField(
				typed,
				onTyped,
				Modifier.fillMaxWidth().focusRequester(focus).testTag("launcher-query"),
				singleLine = true,
				textStyle = style,
				cursorBrush = SolidColor(palette.accent),
			)
		}
		if (state.searching) {
			Spacer(Modifier.width(Space.m))
			Spinner(palette.textMuted, 16.dp)
		}
	}
}

/** What is said where nothing is shown. */
@Composable
private fun Nothing(state: LauncherState) {
	EmptyState(
		Glyph.Search,
		if (state.searching)
			"Searching…"
		else
			"Nothing found",
		(
			if (state.preferences.files && state.searchable)
				"Nothing installed, and no file the index holds, fits “${state.query}”."
			else
				"Nothing installed fits “${state.query}”."
		).takeUnless { state.searching || state.query.isBlank() },
		Modifier.fillMaxSize(),
		detailTag = "launcher-nothing",
	)
}

/** An entry's picture: an application's icon, a file's kind, or what prism does. */
@Composable
private fun EntryIcon(entry: Entry, size: Dp) {
	val palette = LocalPalette.current

	Box(Modifier.size(size), contentAlignment = Alignment.Center) {
		when (entry) {
			is Entry.App -> AppIcon(entry.application.icon, entry.application.name, size)
			is Entry.Action ->
				AppIcon(entry.action.icon ?: entry.application.icon, entry.application.name, size)
			is Entry.File ->
				GlyphIcon(
					if (entry.isFolder) Glyph.Folder else Glyph.File,
					palette.text,
					size * 0.8f,
					category = entry.category,
				)
			is Entry.Command ->
				GlyphIcon(
					when (entry.command) {
						LauncherCommand.Settings -> Glyph.Settings
						LauncherCommand.Files -> Glyph.Folder
					},
					palette.accent,
					size * 0.75f,
				)
		}
	}
}

/**
 * The icon [name], as the desktop's icon theme has it, [size] across; while it is read, and where an application has
 * none, the first letter of its [label] on a tile, so that every row has something to be known by.
 */
@Composable
private fun AppIcon(name: String?, label: String, size: Dp) {
	val images = LocalIconImages.current
	val pixels = with(LocalDensity.current) { size.toPx().roundToInt() }

	val image by produceState(name?.let { images.kept(it, pixels) }, name, pixels) {
		value = name?.let { images.of(it, pixels) }
	}

	image?.let {
		Image(it, null, Modifier.size(size))
	} ?: Initial(label, size)
}

/** The first letter of [label], on a tile [size] across. */
@Composable
private fun Initial(label: String, size: Dp) {
	val palette = LocalPalette.current

	Box(
		Modifier.size(size).clip(RoundedCornerShape(Radius.medium)).background(palette.sunken),
		contentAlignment = Alignment.Center,
	) {
		Text(
			label.trim().take(1).uppercase(),
			style = LocalType.current.strong.copy(color = palette.textMuted),
			maxLines = 1,
		)
	}
}

/**
 * Along the bottom: what went wrong with the last thing chosen, or how much was found; and what the keys do to the
 * result the arrows are on.
 */
@Composable
private fun Footer(state: LauncherState, bindings: LauncherBindings) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val files = state.files as? FilesFound.Found

	Row(
		Modifier
			.fillMaxWidth()
			.height(LauncherDims.footer)
			.background(palette.pane)
			.padding(horizontal = Space.l),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			state.notice
				?: when {
					state.entries.isEmpty() -> ""
					files != null && files.total > files.files.size ->
						"${"%,d".format(state.entries.size)} shown · ${"%,d".format(files.total)} files"
					state.entries.size == 1 -> "1 result"
					else -> "${"%,d".format(state.entries.size)} results"
				},
			style =
				if (state.notice !=
					null
				)
					type.muted.copy(color = palette.dangerText)
				else
					type.muted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f).testTag("launcher-status"),
		)
		Row(
			horizontalArrangement = Arrangement.spacedBy(Space.l),
			verticalAlignment = Alignment.CenterVertically,
		) {
			for ((action, label) in hintsFor(state.current))
				bindings.of(action).firstOrNull()?.let { KeyHint(label, it) }
		}
	}
}

/** The keys said along the bottom for [entry], each with what it does to it, in a word or two. */
private fun hintsFor(entry: Entry?): List<Pair<LauncherAction, String>> =
	listOfNotNull(
		when (entry) {
			null -> null
			is Entry.App, is Entry.Action -> LauncherAction.Open to "Launch"
			is Entry.File -> LauncherAction.Open to "Open"
			is Entry.Command -> LauncherAction.Open to "Go"
		},
		(LauncherAction.Reveal to "Show in folder").takeIf {
			entry != null &&
				entry !is Entry.Command
		},
		LauncherAction.Settings to "Settings",
	)

/** What a key does, and the key: each part of the chord on a cap of its own. */
@Composable
private fun KeyHint(label: String, chord: Chord) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Row(
		horizontalArrangement = Arrangement.spacedBy(Space.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(label, style = type.muted, maxLines = 1)
		Spacer(Modifier.width(Space.xxs))
		for (part in capsOf(chord))
			Box(
				Modifier
					.clip(RoundedCornerShape(Radius.small))
					.background(palette.sunken)
					.border(1.dp, palette.border, RoundedCornerShape(Radius.small))
					.padding(horizontal = Space.xs + Space.xxs, vertical = 1.dp),
			) {
				Text(part, style = type.heading.copy(color = palette.text), maxLines = 1)
			}
	}
}

/** [chord] as the caps it is pressed on: `Ctrl`, `↵`. */
internal fun capsOf(chord: Chord): List<String> =
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
