package prism.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import prism.browser.ActionMenuLayer
import prism.browser.ActionMenuPanel
import prism.browser.Bindings
import prism.browser.Chord
import prism.browser.Divider
import prism.browser.EmptyState
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.KeyHint
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.MenuLine
import prism.browser.QueryHelpPanel
import prism.browser.Radius
import prism.browser.ResultList
import prism.browser.ResultText
import prism.browser.Scaled
import prism.browser.Space
import prism.browser.Spinner
import prism.browser.capsOf
import prism.browser.glyph
import prism.browser.times
import prism.preferences.LauncherPreferences
import prism.window.Backdrop
import prism.window.LocalNativeWindow

/*
 * The launcher, drawn as Raycast's and Vicinae's are: a field across the top, large, with nothing around it but the
 * window; under it what was found, in groups, each result a row with its picture, its name, what is said of it and
 * what kind of thing it is; and along the bottom what the keys do to the result the arrows are on. The field keeps the
 * keyboard throughout: the arrows move through the results from it, the list scrolling to keep the one they are on in
 * sight, and every key is one the user can bind again (bindings.kt). How a query is written, asked for (a bare `?`),
 * is shown in place of all of that, in the same window, until it is left.
 *
 * The right arrow on a result (once the caret is at the end of what is typed), or a right click on one, shows what
 * can be done to it as a menu by its row, in the same window: the arrows and Enter are the menu's while it is open,
 * and the left arrow, Escape or a click elsewhere puts it away.
 */

/** How long the launcher's window is without the keyboard before it is taken to have lost it, and put away. */
private const val UNFOCUSED_MILLIS = 200L

/** The sizes of the launcher's parts, which no other window shares. */
object LauncherDims {
	val field = 64.dp
	val row = 46.dp
	val icon = 28.dp

	/** The dot that marks an application as running, at the corner of its icon. */
	val running = 7.dp
	val footer = 42.dp

	/** The room around the list of results, between the field's rule and the footer's. */
	val around = Space.s

	/** How round its corners are, where they are round; and how wide the line around it is. */
	val corner = 12.dp
	val edge = 1.dp

	/**
	 * How tall the launcher's window is with [rows] results in sight: the field, the results with a heading's room
	 * over them, and the footer.
	 */
	fun heightOf(rows: Int): Dp = field + rows * row + heading + 2 * around + footer + 2.dp

	/** How wide and tall its window is, as [preferences] have it, in the units a window is sized in. */
	fun sizeOf(preferences: LauncherPreferences): Pair<Dp, Dp> =
		preferences.let {
			(it.width.number * it.scale.times).dp to heightOf(it.rows.number) * it.scale.times
		}

	private val heading = 36.dp
}

/** How the launcher's corners are drawn. */
enum class Corners {
	Square,
	Round,
}

/**
 * The corners of a launcher whose user wants them as [preferences] say, in a window with [backdrop] behind what it
 * draws: round only where they are asked for and the window is clear, since the corners of an opaque window are
 * its own to paint, and what is left unpainted there is black.
 */
fun cornersOf(preferences: LauncherPreferences, backdrop: Backdrop): Corners =
	if (preferences.rounded && backdrop == Backdrop.Clear)
		Corners.Round
	else
		Corners.Square

/**
 * The launcher [session] runs, drawn in a window of its own: its state as it changes, what the user does sent to it,
 * each key what [bindings] say. Its window given up (another given the keyboard), it is put away, where the user has
 * it so.
 */
@Composable
fun LauncherView(session: LauncherSession, bindings: Bindings) {
	val state by session.state.collectAsState()

	PutAwayUnfocused(state.preferences.closeUnfocused) { session.send(LauncherEvent.Closed) }

	val queries = remember(session) { session.state.map { it.query } }
	var typed by typedOf(queries, state.query)

	Scaled(state.preferences.scale.times) {
		LauncherPane(
			state,
			bindings,
			typed,
			corners = cornersHere(state.preferences),
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

/**
 * [onGone] told once the window has had the keyboard and has been without it a moment, where it [closes] then: a
 * compositor takes the keyboard from a surface and gives it straight back when a keyboard is plugged in or its
 * layout changes, which is no reason to go. The wait ends with the keyboard's return, since this is begun again as
 * it changes.
 */
@Composable
internal fun PutAwayUnfocused(closes: Boolean, onGone: () -> Unit) {
	val focused = LocalWindowInfo.current.isWindowFocused
	var wasFocused by remember { mutableStateOf(false) }

	LaunchedEffect(focused) {
		if (focused) {
			wasFocused = true
		} else if (wasFocused && closes) {
			delay(UNFOCUSED_MILLIS)
			onGone()
		}
	}
}

/**
 * What is typed into the field, which is the field's own until what its session has typed ([queries]) differs:
 * cleared by Escape, say. It begins as [first].
 */
@Composable
internal fun typedOf(queries: Flow<String>, first: String): MutableState<TextFieldValue> {
	val typed = remember { mutableStateOf(TextFieldValue(first, TextRange(first.length))) }

	LaunchedEffect(queries) {
		queries.distinctUntilChanged().collect { query ->
			if (query != typed.value.text)
				typed.value = TextFieldValue(query, TextRange(query.length))
		}
	}

	return typed
}

/** The corners of the window this is drawn in, as [preferences] ask and what is behind the window allows. */
@Composable
internal fun cornersHere(preferences: LauncherPreferences): Corners =
	cornersOf(preferences, LocalNativeWindow.current?.backdrop ?: Backdrop.Opaque)

/**
 * The launcher as [state] has it, what is in its field being [typed], its [corners] square or round: what
 * [LauncherView] draws, and the tests.
 */
@Composable
fun LauncherPane(
	state: LauncherState,
	bindings: Bindings = launcherBindings(),
	typed: TextFieldValue = TextFieldValue(state.query, TextRange(state.query.length)),
	corners: Corners = Corners.Square,
	onTyped: (TextFieldValue) -> Unit = {},
	onEvent: (LauncherEvent) -> Unit = {},
) {
	val field = remember { FocusRequester() }
	val menu = state.menu
	// Where the row the arrows are on is, for its menu to be drawn by: not known again until the row they have moved to
	// says where it is, so a menu is never drawn by the row they were on.
	var anchor by remember(state.highlighted) { mutableStateOf<Rect?>(null) }

	Box(Modifier.fillMaxSize()) {
		Frame(
			corners,
			onKey = { key ->
				val down = key.type == KeyEventType.KeyDown
				val plain =
					!key.isCtrlPressed && !key.isAltPressed && !key.isMetaPressed && !key.isShiftPressed
				val atEnd = typed.selection.collapsed && typed.selection.end == typed.text.length

				// A menu open: the left arrow puts it away, and is not the caret's.
				if (down && menu != null && plain && key.key == Key.DirectionLeft) {
					onEvent(LauncherEvent.MenuClosed)

					return@Frame true
				}

				// Tab finishes the alias being named, the first of those it could be.
				if (down && plain && key.key == Key.Tab && !state.help && menu == null)
					state.completing?.let {
						onEvent(LauncherEvent.Typed(it.completed(it.aliases.first())))

						return@Frame true
					}

				// While how a query is written is shown, the one key that is the launcher's is the one that
				// leaves it: the rest are for what is not there, and are the page's own to have (its Escape,
				// its scrolling). The right arrow is the caret's while the caret has somewhere to go.
				val action =
					(Chord.of(key)?.let(bindings::action) as? LauncherAction)
						?.takeIf { down }
						?.takeIf { !state.help || it == LauncherAction.Dismiss }
						?.takeUnless {
							it == LauncherAction.ShowActions &&
								plain &&
								key.key == Key.DirectionRight &&
								!atEnd
						}

				action?.let { onEvent(eventOf(it, state.preferences.rows.number - 1)) }

				action != null
			},
		) {
			if (state.help)
				QueryHelpPanel(
					onTry = { onEvent(LauncherEvent.Tried(it)) },
					onClose = { onEvent(LauncherEvent.HelpClosed) },
					Modifier.weight(1f),
					fills = true,
					aliases = state.aliases,
				)
			else
				Found(state, bindings, typed, field, onTyped, { anchor = it }, onEvent)
		}
		if (menu != null)
			ActionMenuLayer(anchor, { onEvent(LauncherEvent.MenuClosed) }) {
				ActionMenuPanel(
					menu.actions.actions.map { action ->
						MenuLine(
							action.label,
							action.key
								?.let(bindings::of)
								?.firstOrNull()
								?.let(::capsOf)
								.orEmpty(),
						)
					},
					menu.actions.on,
					{ onEvent(LauncherEvent.MenuChosen(it)) },
				)
			}
	}

	// The field has the keyboard as the launcher opens, and again when what was shown in its place is left.
	LaunchedEffect(state.help) {
		if (!state.help)
			field.requestFocus()
	}
}

/**
 * What the launcher's window is filled with, around [content]: its ground, the line around it, its [corners] square
 * or round; every key pressed in it is [onKey]'s first, which says whether it was its own.
 *
 * The line around it is the colour of its edge filling the whole of its shape, with everything else drawn over that
 * a line's width in, clipped to the same shape made that much smaller. So the line is exactly as wide all the way
 * round a corner, nothing of what is inside shows past it, and its outer edge fades into what is behind the window
 * and into nothing else: a line drawn over the content, both clipped at one edge, would let the content show through
 * the line's own fading there, as a pale rim.
 */
@Composable
internal fun Frame(
	corners: Corners,
	onKey: (KeyEvent) -> Boolean,
	content: @Composable ColumnScope.() -> Unit,
) {
	val palette = LocalPalette.current

	val (outer, inner) =
		when (corners) {
			Corners.Square -> RectangleShape to RectangleShape
			Corners.Round ->
				RoundedCornerShape(LauncherDims.corner) to
					RoundedCornerShape(LauncherDims.corner - LauncherDims.edge)
		}

	Column(
		Modifier
			.fillMaxSize()
			.background(palette.border, outer)
			.padding(LauncherDims.edge)
			.clip(inner)
			.background(palette.overlay)
			.onPreviewKeyEvent(onKey)
			.testTag("launcher"),
		content = content,
	)
}

/** The field, what was found for what is typed into it, and what the keys do: the launcher, but for its edge. */
@Composable
private fun ColumnScope.Found(
	state: LauncherState,
	bindings: Bindings,
	typed: TextFieldValue,
	field: FocusRequester,
	onTyped: (TextFieldValue) -> Unit,
	onAnchor: (Rect) -> Unit,
	onEvent: (LauncherEvent) -> Unit,
) {
	QueryField("Search applications and files…", typed, field, onTyped, state.searching)
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
				onMenu = { onEvent(LauncherEvent.MenuOpened(it)) },
				onPlaced = onAnchor,
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

/**
 * The field: a magnifier, what is typed, large ([placeholder] while nothing is), and a spinner while what is typed
 * is [searching] for. What is typed is shown as dots where it is [redacted].
 */
@Suppress("DEPRECATION") // The window feeds value-based fields.
@Composable
internal fun QueryField(
	placeholder: String,
	typed: TextFieldValue,
	focus: FocusRequester,
	onTyped: (TextFieldValue) -> Unit,
	searching: Boolean,
	redacted: Boolean = false,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val style = type.body.times(1.4f)

	Row(
		Modifier.fillMaxWidth().height(LauncherDims.field).padding(
			horizontal =
				Space.xl - Space.xs,
		),
		verticalAlignment = Alignment.CenterVertically,
	) {
		GlyphIcon(Glyph.Search, palette.textMuted, 22.dp)
		Spacer(Modifier.width(Space.m + Space.xxs))
		Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
			if (typed.text.isEmpty())
				Text(
					placeholder,
					style = style.copy(color = palette.textFaint),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.testTag("launcher-placeholder"),
				)
			BasicTextField(
				typed,
				onTyped,
				Modifier.fillMaxWidth().focusRequester(focus).testTag("launcher-query"),
				singleLine = true,
				textStyle = style,
				cursorBrush = SolidColor(palette.accent),
				visualTransformation =
					if (redacted)
						PasswordVisualTransformation()
					else
						VisualTransformation.None,
			)
		}
		if (searching) {
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
			"No results",
		(
			if (state.preferences.files && state.searchable)
				"No applications or files match “${state.query}”."
			else
				"No applications match “${state.query}”."
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
			is Entry.App -> {
				AppIcon(
					listOfNotNull(entry.application.icon),
					entry.application.name,
					size,
				)
				// Running: a dot at the corner of its icon, as a dock marks it.
				if (entry.windows.isNotEmpty())
					Box(
						Modifier
							.align(Alignment.BottomEnd)
							.offset(LauncherDims.running / 2, LauncherDims.running / 2)
							.size(LauncherDims.running)
							.background(palette.accent, CircleShape)
							.testTag("running:${entry.key}"),
					)
			}
			// An action's own picture, and its application's where the icon theme has none for the action.
			is Entry.Action ->
				AppIcon(
					listOfNotNull(entry.action.icon, entry.application.icon),
					entry.application.name,
					size,
				)
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
						LauncherCommand.Files -> Glyph.Folder
					},
					palette.accent,
					size * 0.75f,
				)
			is Entry.Prism -> GlyphIcon(entry.own.glyph, palette.accent, size * 0.75f)
			is Entry.Named -> GlyphIcon(Glyph.At, palette.accent, size * 0.75f)
		}
	}
}

/**
 * The first of the icons [names] that the desktop's icon theme has, [size] across; while it is read, and where it has
 * none of them, the first letter of [label] on a tile, so that every row has something to be known by.
 */
@Composable
private fun AppIcon(names: List<String>, label: String, size: Dp) {
	val images = LocalIconImages.current
	val pixels = with(LocalDensity.current) { size.toPx().roundToInt() }

	val image by produceState(
		names.firstNotNullOfOrNull {
			images.kept(it, pixels)
		},
		names,
		pixels,
	) {
		value = names.firstNotNullOfOrNull { images.of(it, pixels) }
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
private fun Footer(state: LauncherState, bindings: Bindings) {
	val files = state.files as? FilesFound.Found

	// A search that went wrong is said as what goes wrong with something chosen is.
	val wrong =
		(state.notice as? LauncherNotice.Failed)?.text
			?: "File search failed. This is a bug in prism; details are in its log."
				.takeIf { state.files is FilesFound.Failed }

	FooterBar(
		wrong
			?: (state.notice as? LauncherNotice.Done)?.text
			?: when {
				state.entries.isEmpty() -> ""
				files != null && files.total > files.files.size ->
					"${"%,d".format(state.entries.size)} shown · ${"%,d".format(files.total)} files"
				state.entries.size == 1 -> "1 result"
				else -> "${"%,d".format(state.entries.size)} results"
			},
		wrong = wrong != null,
		hintsFor(state.current),
		bindings,
	)
}

/**
 * The launcher's bottom line: what is [said] at its start (as something that went [wrong], where it did), and at
 * its end what the keys do ([hints]: each action's first chord in [bindings], with what it does in a word or two).
 */
@Composable
internal fun FooterBar(
	said: String,
	wrong: Boolean,
	hints: List<Pair<LauncherAction, String>>,
	bindings: Bindings,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Row(
		Modifier
			.fillMaxWidth()
			.height(LauncherDims.footer)
			.background(palette.pane)
			.padding(horizontal = Space.l),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			said,
			style =
				if (wrong)
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
			for ((action, label) in hints)
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
			is Entry.Command, is Entry.Prism -> LauncherAction.Open to "Open"
			is Entry.Named -> LauncherAction.Open to "Use alias"
		},
		(LauncherAction.Reveal to "Show in folder").takeIf {
			entry is Entry.App || entry is Entry.Action || entry is Entry.File
		},
		(LauncherAction.ShowActions to "Actions").takeIf { entry != null },
		LauncherAction.Settings to "Settings",
	)
