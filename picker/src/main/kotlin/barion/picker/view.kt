package barion.picker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/*
 * The picker, drawn: a pure function of a [PickerState], made of the controls in elements.kt. It shows what the state
 * says and reports what the user does as a [PickerEvent]; it changes nothing itself.
 *
 * Laid out as a desktop's own file dialogs are: the way back and up and the path across the top, with a search
 * field; the places down the left; the folder's entries in columns; and the name, the type filter and the buttons
 * across the bottom. The keyboard goes through it in that order with Tab, stopping once at the places and once at
 * the listing, inside which the arrows move; the toolbar's buttons have keys of their own instead.
 *
 * | keys | where | what |
 * |---|---|---|
 * | arrows, Page Up and Down, Home, End | listing | the cursor; with Shift, choosing a run; with Ctrl, not choosing |
 * | Space | listing | the row at the cursor chosen, or no longer |
 * | Enter | listing, name | accept (a chosen folder is opened) |
 * | letters | listing | the next row whose name starts with them |
 * | Backspace, Alt+Up | listing; anywhere | the folder above |
 * | Alt+Left, Alt+Right | anywhere | back, forward |
 * | Ctrl+L, Alt+D | anywhere | type a path |
 * | Ctrl+F, F3 | anywhere | search the folder shown |
 * | Ctrl+Shift+F | anywhere | search the index: the first matches as it is typed, every one in the listing on Enter |
 * | Ctrl+H | anywhere | hidden files shown, or not |
 * | F5, Ctrl+R | anywhere | the folder read again |
 * | Ctrl+A | listing | every row chosen |
 * | Escape | anywhere | cancel (in a field: leave what it is doing first) |
 */

/** Where the keyboard can be sent: the controls that a key, or the picker's own changes, move it to. */
@Stable
private class Stops {
	val listing = FocusRequester()
	val places = FocusRequester()
	val name = FocusRequester()
	val search = FocusRequester()
	val address = FocusRequester()
}

/** The picker for [state]: what it shows, and [onEvent] for what the user does. */
@Composable
fun FilePicker(
	state: PickerState,
	modifier: Modifier = Modifier,
	onEvent: (PickerEvent) -> Unit = {},
) {
	val palette = LocalPalette.current
	val stops = remember { Stops() }
	val saving = state.choosing is Choosing.SaveAs
	val keyboard = remember { KeyboardUse() }
	val latest by rememberUpdatedState(onEvent)

	// The keyboard starts where a desktop's dialogs start it: at the name to save under, or at the listing.
	LaunchedEffect(Unit) {
		if (saving)
			stops.name.requestFocus()
		else
			stops.listing.requestFocus()
	}

	CompositionLocalProvider(LocalKeyboardUse provides keyboard) {
		Box(
			modifier
				.fillMaxSize()
				.background(palette.window)
				.usedBy(keyboard)
				.zoomedByWheel { latest(PickerEvent.Zoomed(it)) }
				.onPreviewKeyEvent { shortcut(it, stops, onEvent) }
				.onKeyEvent { afterwards(it, onEvent) },
		) {
			Column(Modifier.fillMaxSize()) {
				Toolbar(state, stops, onEvent)
				Line(palette.border)
				Row(Modifier.weight(1f).fillMaxWidth()) {
					SidebarPane(state.sidebar, state.folder, stops, onEvent)
					Box(Modifier.width(1.dp).fillMaxHeight().background(palette.border))
					ListingPane(state, stops, Modifier.weight(1f), onEvent)
				}
				Line(palette.border)
				Footer(state, stops, onEvent)
			}
			state.search.takeIf { it.open }?.let { SearchLayer(it, state.home, onEvent) }
			state.notice?.let { NoticeLayer(it, onEvent) }
		}
	}
}

/**
 * [keyboard] told which was used last: the keyboard, as a key goes down anywhere in the picker, or the pointer, as a
 * button goes down. Seen before anything inside sees either, and left for it.
 */
private fun Modifier.usedBy(keyboard: KeyboardUse): Modifier =
	onPreviewKeyEvent {
		if (it.type == KeyEventType.KeyDown)
			keyboard.lately = true

		false
	}.pointerInput(keyboard) {
		awaitPointerEventScope {
			while (true) {
				if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press)
					keyboard.lately = false
			}
		}
	}

/** The keys that work wherever the keyboard is, seen before the control it is on sees them. */
private fun shortcut(
	key: KeyEvent,
	stops: Stops,
	onEvent: (PickerEvent) -> Unit,
): Boolean {
	// A chord with Ctrl types its character as well, after it (Ctrl+2 types a "2"), which a text field would take as
	// typing: it is the chord's, and goes nowhere. Ctrl with Alt is AltGr on many keyboards, which types for real.
	if (key.type == KeyEventType.Unknown)
		return key.isCtrlPressed && !key.isAltPressed && key.utf16CodePoint >= ' '.code

	if (key.type != KeyEventType.KeyDown)
		return false

	val alt = key.isAltPressed
	val ctrl = key.isCtrlPressed

	val event =
		when {
			alt && key.key == Key.DirectionLeft -> PickerEvent.Back
			alt && key.key == Key.DirectionRight -> PickerEvent.Forward
			alt && key.key == Key.DirectionUp -> PickerEvent.Up
			(ctrl && key.key == Key.L) || (alt && key.key == Key.D) -> PickerEvent.EditedAddress
			ctrl && key.key == Key.H -> PickerEvent.ToggledEveryHidden
			ctrl && key.isShiftPressed && key.key == Key.N -> PickerEvent.MadeFolder
			ctrl && key.isShiftPressed && key.key == Key.F -> PickerEvent.OpenedSearch
			ctrl && key.key in LARGER -> PickerEvent.Zoomed(1)
			ctrl && key.key in SMALLER -> PickerEvent.Zoomed(-1)
			ctrl && key.key in USUAL -> PickerEvent.Unzoomed
			key.key == Key.F5 || (ctrl && key.key == Key.R) -> PickerEvent.Refreshed
			ctrl && key.key == Key.One -> PickerEvent.Viewed(Layout.List)
			ctrl && key.key == Key.Two -> PickerEvent.Viewed(Layout.Gallery)
			else -> null
		}

	val searching = (ctrl && !key.isShiftPressed && key.key == Key.F) || key.key == Key.F3

	event?.let(onEvent)

	if (searching)
		stops.search.requestFocus()

	return event != null || searching
}

/** The keys that, with Ctrl, draw the listing larger, smaller, and at its usual size: on either side of the keyboard. */
private val LARGER = setOf(Key.Equals, Key.Plus, Key.NumPadAdd)
private val SMALLER = setOf(Key.Minus, Key.NumPadSubtract)
private val USUAL = setOf(Key.Zero, Key.NumPad0)

/**
 * The keys left to the picker by whatever has the keyboard, which sees them first: Escape (a field leaves what it is
 * doing first), and Ctrl+Z and Ctrl+Shift+Z (or Ctrl+Y), which undo and redo the picker's changes to the disk, except
 * in a text field, whose own typing they undo.
 */
private fun afterwards(key: KeyEvent, onEvent: (PickerEvent) -> Unit): Boolean {
	if (key.type != KeyEventType.KeyDown)
		return false

	val ctrl = key.isCtrlPressed || key.isMetaPressed
	val event =
		when {
			key.key == Key.Escape -> PickerEvent.Cancelled
			ctrl && key.isShiftPressed && key.key == Key.Z -> PickerEvent.Redone
			ctrl && key.key == Key.Y -> PickerEvent.Redone
			ctrl && key.key == Key.Z -> PickerEvent.Undone
			else -> null
		}

	event?.let(onEvent)

	return event != null
}

/** Enter, on either side of the keyboard. */
private val KeyEvent.isEnter: Boolean get() = key == Key.Enter || key == Key.NumPadEnter

@Composable
private fun Line(color: Color) =
	Box(Modifier.fillMaxWidth().height(1.dp).background(color))

// ---- the toolbar: the way back and up, the path, the search ----------------------------------------------------

@Composable
private fun Toolbar(state: PickerState, stops: Stops, onEvent: (PickerEvent) -> Unit) {
	Row(
		Modifier
			.fillMaxWidth()
			.height(Dims.toolbar)
			.padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Dims.gapSmall),
	) {
		ToolButton(
			Glyph.Back,
			"Back",
			state.history.back.isNotEmpty(),
			Modifier.testTag("back"),
		) {
			onEvent(PickerEvent.Back)
		}
		ToolButton(
			Glyph.Forward,
			"Forward",
			state.history.forward.isNotEmpty(),
			Modifier.testTag("forward"),
		) {
			onEvent(PickerEvent.Forward)
		}
		ToolButton(Glyph.Up, "Up", state.folder?.parent != null, Modifier.testTag("up")) {
			onEvent(PickerEvent.Up)
		}
		MenuButton(
			Glyph.Filter,
			"Filters",
			{ filterMenuOf(state) },
			onEvent,
			Modifier.testTag("filters"),
			selected = state.arrangement.kinds.isNotEmpty(),
		)
		Spacer(Modifier.width(Dims.gapSmall))
		AddressBar(state, stops, Modifier.weight(1f), onEvent)
		Spacer(Modifier.width(Dims.gapSmall))
		Field(
			state.arrangement.query,
			{ onEvent(PickerEvent.Searched(it)) },
			Modifier.width(260.dp).testTag("search"),
			hint = "Search ${state.placeName}",
			trailing = Glyph.Search,
			focus = stops.search,
			onKey = { searchKey(it, state.arrangement.query, stops, onEvent) },
		)
		Spacer(Modifier.width(Dims.gapSmall))
		for ((layout, glyph) in listOf(
			Layout.List to Glyph.ViewList,
			Layout.Gallery to Glyph.ViewGrid,
		))
			ToolButton(
				glyph,
				layout.name,
				enabled = true,
				Modifier.testTag("layout:${layout.name}"),
				selected = state.layout == layout,
			) { onEvent(PickerEvent.Viewed(layout)) }
	}
}

/** Escape empties the search before it cancels anything; Enter and Down take the keyboard to what it found. */
private fun searchKey(
	key: KeyEvent,
	query: String,
	stops: Stops,
	onEvent: (PickerEvent) -> Unit,
): Boolean =
	when {
		key.type != KeyEventType.KeyDown -> false
		key.key == Key.Escape && query.isNotEmpty() ->
			true.also {
				onEvent(PickerEvent.Searched(""))
			}
		key.isEnter || key.key == Key.DirectionDown ->
			true.also { stops.listing.requestFocus() }
		else -> false
	}

/** The path to the folder shown: steps to click, or, after Ctrl+L or a click beside them, a path to type. */
@Composable
private fun AddressBar(
	state: PickerState,
	stops: Stops,
	modifier: Modifier,
	onEvent: (PickerEvent) -> Unit,
) {
	// Set when Enter goes to the path typed, so that the keyboard follows to the listing when the folder arrives.
	var going by remember { mutableStateOf(false) }

	LaunchedEffect(state.at) {
		if (going) {
			going = false
			stops.listing.requestFocus()
		}
	}

	when (val address = state.address) {
		Address.Crumbs ->
			when (val results = state.results) {
				null -> Crumbs(state.crumbs, modifier, onEvent)
				else -> ResultsBar(results, modifier, onEvent)
			}
		is Address.Editing -> {
			Field(
				address.text,
				{ onEvent(PickerEvent.AddressTyped(it)) },
				modifier.testTag("address"),
				focus = stops.address,
				onLeave = { onEvent(PickerEvent.LeftAddress) },
				onKey = {
					when {
						it.type != KeyEventType.KeyDown -> false
						it.isEnter ->
							true.also {
								going = true
								onEvent(PickerEvent.Went)
							}
						// Escape leaves the path as it was, and the keyboard goes back to the listing.
						it.key == Key.Escape ->
							true.also {
								stops.listing.requestFocus()
								onEvent(PickerEvent.LeftAddress)
							}
						else -> false
					}
				},
			)
			LaunchedEffect(Unit) { stops.address.requestFocus() }
		}
	}
}

@Composable
private fun Crumbs(
	crumbs: List<Crumb>,
	modifier: Modifier,
	onEvent: (PickerEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val shape = RoundedCornerShape(Dims.radius)
	val scroll = rememberScrollState()

	// A path too long for the bar shows its end, where the folder shown is.
	LaunchedEffect(crumbs) {
		snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
	}

	Row(
		modifier
			.ring(felt.ringed, palette.focus)
			.height(Dims.control)
			.background(
				if (felt.hovered)
					palette.hover
				else
					palette.field,
				shape,
			).border(1.dp, palette.fieldEdge, shape)
			.clickable(source, indication = null, onClickLabel = "Type a path") {
				onEvent(PickerEvent.EditedAddress)
			}.testTag("crumbs")
			.padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		GlyphIcon(Glyph.Folder, palette.text, 16.dp)
		Spacer(Modifier.width(Dims.gap))
		Row(
			Modifier.weight(1f, fill = false).horizontalScroll(scroll),
			verticalAlignment = Alignment.CenterVertically,
		) {
			crumbs.forEachIndexed { index, crumb ->
				val last = index == crumbs.lastIndex

				CrumbStep(crumb, last) { onEvent(PickerEvent.Opened(crumb.path)) }
				if (!last)
					GlyphIcon(Glyph.Chevron, palette.textFaint, 12.dp)
			}
		}
	}
}

/**
 * In place of the path, what a search's results are: the words searched for, which open the search's window again
 * (with them in it) when clicked, as the path's bar opens a path to type.
 */
@Composable
private fun ResultsBar(
	results: Finding.Matched,
	modifier: Modifier,
	onEvent: (PickerEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val shape = RoundedCornerShape(Dims.radius)

	Row(
		modifier
			.ring(felt.ringed, palette.focus)
			.height(Dims.control)
			.background(
				if (felt.hovered)
					palette.hover
				else
					palette.field,
				shape,
			).border(1.dp, palette.fieldEdge, shape)
			.clickable(source, indication = null, onClickLabel = "Search again") {
				onEvent(PickerEvent.OpenedSearch)
			}.testTag("results")
			.padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		GlyphIcon(Glyph.Search, palette.text, 16.dp)
		Spacer(Modifier.width(Dims.gap))
		Text("Search results", style = type.body, maxLines = 1)
		Spacer(Modifier.width(Dims.gap))
		Text(
			"“${results.text}”",
			style = type.muted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f, fill = false),
		)
	}
}

@Composable
private fun CrumbStep(crumb: Crumb, last: Boolean, onClick: () -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()

	Text(
		crumb.label,
		style =
			if (last)
				type.body.copy(fontWeight = FontWeight.SemiBold)
			else
				type.body,
		maxLines = 1,
		modifier =
			Modifier
				.background(
					if (felt.hovered)
						palette.pressed
					else
						Color.Transparent,
					RoundedCornerShape(4.dp),
				).focusProperties { canFocus = false }
				.clickable(source, indication = null, onClick = onClick)
				.testTag("crumb:${crumb.label}")
				.padding(horizontal = 4.dp, vertical = 2.dp),
	)
}

// ---- the sidebar: the places --------------------------------------------------------------------------------

/**
 * The places, as one stop of the keyboard: the arrows move through them, Enter opens one. Which place the arrows are
 * on is the sidebar's own, as a list's highlighted entry is on any desktop: it starts at the place shown.
 */
@Composable
private fun SidebarPane(
	sidebar: Sidebar,
	current: Path?,
	stops: Stops,
	onEvent: (PickerEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val places = sidebar.places
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	var at by remember(sidebar) {
		mutableIntStateOf(places.indexOfFirst { it.path == current }.coerceAtLeast(0))
	}

	fun key(key: KeyEvent): Boolean {
		if (key.type != KeyEventType.KeyDown || places.isEmpty())
			return false

		when (key.key) {
			Key.DirectionUp -> at = (at - 1).coerceAtLeast(0)
			Key.DirectionDown -> at = (at + 1).coerceAtMost(places.lastIndex)
			Key.MoveHome -> at = 0
			Key.MoveEnd -> at = places.lastIndex
			Key.Enter, Key.NumPadEnter, Key.Spacebar ->
				onEvent(
					PickerEvent.Opened(places[at].path),
				)
			else -> return false
		}

		return true
	}

	Column(
		Modifier
			.width(Dims.sidebar)
			.fillMaxHeight()
			.background(palette.pane)
			.focusRequester(stops.places)
			.onKeyEvent(::key)
			.focusable(interactionSource = source)
			.testTag("places")
			.padding(Dims.gap),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		for (group in sidebar.groups) {
			Text(
				group.heading,
				style = type.heading,
				modifier = Modifier.padding(start = Dims.gap, top = Dims.gap, bottom = Dims.gapSmall),
			)
			for (place in group.places) {
				val index = places.indexOf(place)

				PlaceRow(place, current = place.path == current, keyed = felt.ringed && index == at) {
					// A place clicked takes the keyboard to the places, as a list clicked does on any desktop.
					at = index
					stops.places.requestFocus()
					onEvent(PickerEvent.Opened(place.path))
				}
			}
		}
	}
}

@Composable
private fun PlaceRow(
	place: Place,
	current: Boolean,
	keyed: Boolean,
	onOpen: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val background =
		when {
			current -> palette.chosen
			felt.pressed -> palette.pressed
			felt.hovered -> palette.hover
			else -> Color.Transparent
		}

	Row(
		Modifier
			.fillMaxWidth()
			.height(Dims.row)
			.ring(keyed, palette.focus, outset = 0.dp, width = 1.5.dp)
			.background(background, RoundedCornerShape(Dims.radius))
			.focusProperties { canFocus = false }
			.clickable(source, indication = null, onClick = onOpen)
			.testTag("place:${place.label}")
			.padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// The place being shown is marked as the desktop marks it: a short bar of the accent at its edge.
		Box(
			Modifier.width(3.dp).height(14.dp).background(
				if (current)
					palette.accent
				else
					Color.Transparent,
				RoundedCornerShape(2.dp),
			),
		)
		Spacer(Modifier.width(Dims.gap))
		GlyphIcon(
			glyphOf(place.kind),
			if (current)
				palette.accent
			else
				palette.textMuted,
			Dims.icon,
		)
		Spacer(Modifier.width(Dims.gap + 2.dp))
		Text(place.label, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

private fun glyphOf(kind: Place.Kind): Glyph =
	when (kind) {
		Place.Kind.Home -> Glyph.Home
		Place.Kind.Desktop -> Glyph.Desktop
		Place.Kind.Documents -> Glyph.Documents
		Place.Kind.Downloads -> Glyph.Downloads
		Place.Kind.Pictures -> Glyph.Pictures
		Place.Kind.Music -> Glyph.Music
		Place.Kind.Videos -> Glyph.Videos
		Place.Kind.Drive -> Glyph.Drive
	}

// ---- the listing: the folder's entries in columns ------------------------------------------------------------

/** Letters typed into the listing in quick succession, which together name the row to go to. */
@Immutable
private data class Typing(
	val prefix: String,
	val at: Long,
) {
	fun then(letter: Char, now: Long): Typing =
		if (now - at < TYPING_PAUSE)
			Typing(prefix + letter, now)
		else
			Typing(letter.toString(), now)
}

/** How long a pause in typing ends the letters that name a row, in milliseconds. */
private const val TYPING_PAUSE = 1000L

/**
 * The listing, as one stop of the keyboard: what it shows is [PickerState.rows], as rows in columns or as tiles in a
 * gallery, with the cursor's row ringed while the keyboard is here, and kept in sight as it moves. Each folder starts
 * at its top. In a gallery, Left and Right move along a line of tiles, and Up and Down from one line to the next.
 *
 * Ctrl and the mouse's wheel draw it larger or smaller. The right button opens a menu: of a row, over a row (of every
 * row chosen, if it is one of them; otherwise it is chosen first, alone), and of the folder over the rest; Shift+F10
 * or the menu key opens the same from the keyboard, at the cursor's row.
 */
@Composable
private fun ListingPane(
	state: PickerState,
	stops: Stops,
	modifier: Modifier,
	onEvent: (PickerEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val list = remember(state.at) { LazyListState() }
	val grid = remember(state.at) { LazyGridState() }
	val menus = rememberContextMenus<PickerEvent>()
	val area = remember { Placed() }
	val rows = state.rows
	val cursor = state.cursor?.at
	val cursorAt = cursor?.let(rows::indexOf)
	val query = state.arrangement.query
	val gallery = state.layout == Layout.Gallery
	val zoom = state.scale
	val chosen = state.choosing.rows
	val renaming = state.renaming
	var typing by remember { mutableStateOf(Typing("", 0)) }
	var pressed by remember { mutableStateOf<Pressed?>(null) }

	LaunchedEffect(cursorAt, state.layout) {
		cursorAt?.let {
			if (gallery)
				grid.reveal(it)
			else
				list.reveal(it)
		}
	}

	// A search's results are read on disk as they come into sight: which rows are in sight is said as it changes.
	val found = state.listing is Listing.Found
	val latest by rememberUpdatedState(onEvent)

	LaunchedEffect(found, list, grid, gallery) {
		if (found)
			snapshotFlow {
				val shown =
					if (gallery)
						grid.layoutInfo.visibleItemsInfo.map { it.index }
					else
						list.layoutInfo.visibleItemsInfo.map { it.index }

				shown.minOrNull()?.let { it to shown.max() }
			}.filterNotNull()
				.distinctUntilChanged()
				.collect { (first, last) -> latest(PickerEvent.Scrolled(first, last)) }
	}

	// A second plain press of the row pressed last, soon enough, is a double click; a third starts again.
	fun press(row: RowId, press: Press) {
		stops.listing.requestFocus()

		if (pressed?.doubledBy(row, press) == true) {
			pressed = null
			onEvent(PickerEvent.Activated(row))
		} else {
			pressed = Pressed(row, press)
			onEvent(PickerEvent.Clicked(row, press.click))
		}
	}

	/** The rows a key acts on: those chosen, or the cursor's where none is. */
	fun targets(): List<Row> =
		state.chosenRows.ifEmpty {
			listOfNotNull(cursor?.let(rows::get))
		}

	/** The menu of [row], pressed with the right button: of the rows chosen if it is one, of it alone otherwise. */
	fun menuOf(row: Row): Menu<PickerEvent> {
		stops.listing.requestFocus()

		val among = row.id in chosen

		if (!among)
			onEvent(PickerEvent.Clicked(row.id, Click.Only))

		return contextMenuOf(
			state,
			Target.Rows(
				if (among)
					state.chosenRows
				else
					listOf(row),
			),
		)
	}

	fun folderMenu(): Menu<PickerEvent> {
		stops.listing.requestFocus()

		return contextMenuOf(state, Target.Folder)
	}

	/** The menu of what is chosen (or of the folder, where nothing is), opened from the keyboard at the cursor's row. */
	fun openMenuByKey() {
		val shown = state.chosenRows
		val menu =
			if (shown.isEmpty())
				contextMenuOf(state, Target.Folder)
			else
				contextMenuOf(state, Target.Rows(shown))

		val inside =
			cursorAt
				?.let { at ->
					if (gallery)
						grid.layoutInfo.visibleItemsInfo
							.firstOrNull { it.index == at }
							?.let {
								Offset(
									it.offset.x + it.size.width / 2f,
									it.offset.y + it.size.height / 2f,
								)
							}
					else
						list.layoutInfo.visibleItemsInfo
							.firstOrNull { it.index == at }
							?.let { Offset(48f, it.offset + it.size / 2f) }
				} ?: Offset(48f, 24f)

		area.coordinates?.localToWindow(inside)?.let {
			menus.open(menu, IntOffset(it.x.roundToInt(), it.y.roundToInt()))
		}
	}

	fun key(key: KeyEvent): Boolean {
		// A name being typed over has the keys; what it leaves is not the listing's.
		if (key.type != KeyEventType.KeyDown || renaming != null)
			return false

		val ctrl = key.isCtrlPressed || key.isMetaPressed
		val shift = key.isShiftPressed

		if ((shift && key.key == Key.F10) || key.key == Key.Menu) {
			openMenuByKey()

			return true
		}

		val carry =
			when {
				shift -> Carry.Extend
				ctrl -> Carry.Stay
				else -> Carry.Choose
			}

		// A line is a row in the list, and as many tiles as fit across in the gallery.
		val line =
			if (gallery)
				grid.layoutInfo.maxSpan.coerceAtLeast(1)
			else
				1

		val lines =
			if (gallery)
				grid.layoutInfo.visibleItemsInfo
					.map { it.row }
					.distinct()
					.size
			else
				list.layoutInfo.visibleItemsInfo.size
		val page = line * (lines - 1).coerceAtLeast(1)
		val letter =
			key.utf16CodePoint
				.takeIf {
					it > ' '.code && !ctrl && !key.isAltPressed
				}?.toChar()
		val ids = targets().map { it.id }

		val event =
			when {
				key.key == Key.DirectionUp -> PickerEvent.Moved(Move.By(-line), carry)
				key.key == Key.DirectionDown -> PickerEvent.Moved(Move.By(line), carry)
				gallery && key.key == Key.DirectionLeft -> PickerEvent.Moved(Move.By(-1), carry)
				gallery && key.key == Key.DirectionRight -> PickerEvent.Moved(Move.By(1), carry)
				key.key == Key.PageUp -> PickerEvent.Moved(Move.By(-page), carry)
				key.key == Key.PageDown -> PickerEvent.Moved(Move.By(page), carry)
				key.key == Key.MoveHome -> PickerEvent.Moved(Move.First, carry)
				key.key == Key.MoveEnd -> PickerEvent.Moved(Move.Last, carry)
				key.key == Key.Spacebar -> PickerEvent.Toggled
				key.isEnter -> PickerEvent.Accepted
				key.key == Key.Backspace -> PickerEvent.Up
				key.key == Key.F2 -> ids.singleOrNull()?.let { PickerEvent.StartedRenaming(it) }
				// The trash: Delete asks first, since it is easily pressed by mistake; Shift+Delete does not.
				key.key == Key.Delete && ids.isNotEmpty() ->
					if (shift)
						PickerEvent.Trashed(ids)
					else
						PickerEvent.AskedToTrash(ids)
				ctrl && shift && key.key == Key.C ->
					PickerEvent.CopiedPaths(
						targets().map { it.path }.ifEmpty { listOfNotNull(state.folder) },
					)
				ctrl && key.key == Key.A -> PickerEvent.ChoseAll
				letter != null -> {
					typing = typing.then(letter, System.currentTimeMillis())
					PickerEvent.Jumped(typing.prefix)
				}
				else -> null
			}

		event?.let(onEvent)

		return event != null
	}

	val nowhere =
		Modifier.pressedNowhere {
			stops.listing.requestFocus()
			onEvent(PickerEvent.ClickedNowhere)
		}

	Column(
		modifier
			.fillMaxHeight()
			.background(palette.surface)
			.focusRequester(stops.listing)
			.onKeyEvent(::key)
			.focusable(interactionSource = source)
			.testTag("listing"),
	) {
		Header(state, onEvent)
		Line(palette.divider)

		Box(
			Modifier
				.weight(1f)
				.fillMaxWidth()
				.onGloballyPositioned { area.coordinates = it }
				.contextMenu(menus) { folderMenu() },
		) {
			when (val listing = state.listing) {
				// A folder that reads quickly would only flash a message, so a folder being read shows nothing yet.
				Listing.Reading -> Box(Modifier.fillMaxSize())
				is Listing.Unreadable -> Message("This folder can't be opened", listing.why)
				is Listing.Shown, is Listing.Found ->
					when {
						rows.isEmpty() -> {
							val none = state.entryCount == 0

							Message(
								when {
									query.isNotBlank() -> "Nothing here matches “$query”"
									none && listing is Listing.Found -> "Nothing matches “${listing.finding.text}”"
									none -> "This folder is empty"
									else -> "Nothing here is shown"
								},
								if (none || query.isNotBlank())
									null
								else
									"The filters leave out everything here.",
							)
						}
						gallery ->
							Gallery(state, grid, nowhere, onEvent) { row, scale ->
								Tile(
									row,
									row.path,
									chosen = row.id in chosen,
									cursor = felt.ringed && row.id == cursor,
									zoom = scale,
									renamed = renaming?.takeIf { it.row == row.id }?.text,
									stops = stops,
									onPress = { press(row.id, it) },
									onEvent = onEvent,
									modifier = Modifier.contextMenu(menus) { menuOf(row) },
								)
							}
						else ->
							LazyColumn(
								nowhere
									.fillMaxSize()
									.padding(horizontal = Dims.gapSmall, vertical = Dims.gapSmall),
								state = list,
							) {
								items(rows.size, key = rows::idAt, contentType = { "row" }) { index ->
									val row = rows[index]

									EntryRow(
										row,
										where = whereOf(row, state),
										chosen = row.id in chosen,
										cursor = felt.ringed && row.id == cursor,
										zoom = zoom,
										renamed = renaming?.takeIf { it.row == row.id }?.text,
										stops = stops,
										onPress = { press(row.id, it) },
										onEvent = onEvent,
										modifier = Modifier.contextMenu(menus) { menuOf(row) },
									)
								}
							}
					}
			}
		}
		ContextMenuHost(menus, onEvent)
	}
}

/**
 * The gallery: its room measured as it is laid out, and the size at which a tile would fill it reported, for the zoom to
 * go no further ([PickerEvent.Measured]); its tiles drawn at the zoom chosen, but never larger than fits, so a window
 * made smaller makes them smaller at once. At its largest, one tile at a time, as wide as the gallery.
 */
@Composable
private fun Gallery(
	state: PickerState,
	grid: LazyGridState,
	nowhere: Modifier,
	onEvent: (PickerEvent) -> Unit,
	tile: @Composable (Row, Float) -> Unit,
) {
	BoxWithConstraints(Modifier.fillMaxSize()) {
		val wide = (maxWidth - Dims.gapSmall * 2) / Dims.tile
		val high = (maxHeight - Dims.gapSmall * 2) / Dims.tileHeight
		val whole = minOf(wide, high).coerceAtLeast(0.1f)
		val scale = scaleOf(state.zoom, whole)

		LaunchedEffect(whole) { onEvent(PickerEvent.Measured(whole)) }

		LazyVerticalGrid(
			if (state.zoom == Zoom.Whole || scale >= whole)
				GridCells.Fixed(1)
			else
				GridCells.Adaptive(Dims.tile * scale),
			nowhere.fillMaxSize().padding(Dims.gapSmall),
			state = grid,
			horizontalArrangement = Arrangement.spacedBy(Dims.gapSmall),
			verticalArrangement = Arrangement.spacedBy(Dims.gapSmall),
		) {
			val rows = state.rows

			items(
				rows.size,
				key = rows::idAt,
				contentType = { "tile" },
			) { tile(rows[it], scale) }
		}
	}
}

/**
 * Ctrl and the mouse's wheel reported as steps of zoom: up larger, down smaller, a step a notch. A touchpad's small
 * scrolls are added up until they make a notch. Seen before the list inside sees it, which then does not scroll.
 */
private fun Modifier.zoomedByWheel(onZoom: (Int) -> Unit): Modifier =
	pointerInput(Unit) {
		awaitPointerEventScope {
			var turned = 0f

			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Initial)

				if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
					turned += event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
					event.changes.forEach { it.consume() }

					val notches = turned.toInt()

					if (notches != 0) {
						turned -= notches
						onZoom(-notches)
					}
				}
			}
		}
	}

/** [this] style drawn [factor] times as large. */
private fun TextStyle.times(factor: Float): TextStyle =
	copy(
		fontSize = fontSize * factor,
		lineHeight =
			lineHeight * factor,
	)

/**
 * A row's name, or, while it is being renamed, a field with the new name as typed: its name's stem selected, Enter
 * giving the name and Escape leaving it as it was, the keyboard then going back to the listing. Leaving the field
 * gives the name too, as clicking away does in a file manager.
 */
@Composable
private fun NameOf(
	row: Row,
	renamed: String?,
	style: TextStyle,
	stops: Stops,
	onEvent: (PickerEvent) -> Unit,
	modifier: Modifier = Modifier,
	maxLines: Int = 1,
	textAlign: TextAlign? = null,
) {
	if (renamed == null) {
		Text(
			row.name,
			modifier,
			style = style,
			maxLines = maxLines,
			overflow = TextOverflow.Ellipsis,
			textAlign = textAlign,
		)

		return
	}

	val focus = remember { FocusRequester() }

	Field(
		renamed,
		{ onEvent(PickerEvent.RenameTyped(it)) },
		modifier.testTag("rename"),
		height = with(LocalDensity.current) { (style.lineHeight * 1.5f).toDp() },
		focus = focus,
		selecting = {
			val dot = it.lastIndexOf('.').takeIf { dot -> dot > 0 && !row.isFolder }

			TextRange(0, dot ?: it.length)
		},
		onLeave = { onEvent(PickerEvent.Renamed) },
		onKey = {
			when {
				it.type != KeyEventType.KeyDown -> false
				// What the field says first, then the keyboard back: leaving the field then gives nothing new.
				it.isEnter ->
					true.also {
						onEvent(PickerEvent.Renamed)
						stops.listing.requestFocus()
					}
				it.key == Key.Escape ->
					true.also {
						onEvent(PickerEvent.RenameCancelled)
						stops.listing.requestFocus()
					}
				else -> false
			}
		},
	)
	LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * The tile at [index] scrolled into sight, as [LazyListState.reveal] does for a row: its line moved in by what is cut
 * off, or scrolled to directly, at the top from above and at the bottom from below. Every tile is as tall as every
 * other.
 */
private suspend fun LazyGridState.reveal(index: Int) {
	val info = snapshotFlow { layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
	val shown = info.visibleItemsInfo
	val first = shown.first()
	val last = shown.last()
	val across = info.maxSpan.coerceAtLeast(1)
	val line = first.size.height + info.mainAxisItemSpacing
	val height = info.viewportEndOffset - info.viewportStartOffset

	when {
		index < first.index -> scrollToItem(index)
		index > last.index -> {
			val target = index / across
			// The lines above it, once it is at the bottom: as many as fill the rest of the height, the top one cut.
			val above = (height - line + line - 1) / line
			val top = (target - above).coerceAtLeast(0)

			scrollToItem(top * across, ((target - top + 1) * line - height).coerceAtLeast(0))
		}
		else -> {
			val tile = shown.first { it.index == index }
			val cutAbove = info.viewportStartOffset - tile.offset.y
			val cutBelow = tile.offset.y + tile.size.height - info.viewportEndOffset

			when {
				cutAbove > 0 -> scrollBy(-cutAbove.toFloat())
				cutBelow > 0 -> scrollBy(cutBelow.toFloat())
			}
		}
	}
}

/**
 * The row at [index] scrolled into sight, by as little as it takes: a row partly cut off at an edge is moved in by
 * what is cut off; one out of sight is scrolled to directly (never by scrolling through every row between, which
 * in a folder of a hundred thousand would lay each of them out), arriving at the top when it was above and at the
 * bottom when it was below, as a list moved by its arrows does. Every row is as tall as every other.
 */
private suspend fun LazyListState.reveal(index: Int) {
	// A folder's rows and the cursor landing in them arrive together, before the list has laid anything out; what is
	// in sight is known only once it has.
	val info = snapshotFlow { layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
	val shown = info.visibleItemsInfo
	val first = shown.first()
	val last = shown.last()
	val size = first.size
	val height = info.viewportEndOffset - info.viewportStartOffset

	when {
		index < first.index -> scrollToItem(index)
		index > last.index -> {
			// The rows above it, once it is at the bottom: as many as fill the rest of the height, the top one cut.
			val above = (height - size + size - 1) / size
			val top = (index - above).coerceAtLeast(0)

			scrollToItem(top, ((index - top + 1) * size - height).coerceAtLeast(0))
		}
		else -> {
			val row = shown.first { it.index == index }
			val cutAbove = info.viewportStartOffset - row.offset
			val cutBelow = row.offset + row.size - info.viewportEndOffset

			when {
				cutAbove > 0 -> scrollBy(-cutAbove.toFloat())
				cutBelow > 0 -> scrollBy(cutBelow.toFloat())
			}
		}
	}
}

/** A press that no row inside took. */
private fun Modifier.pressedNowhere(onPress: () -> Unit): Modifier =
	pointerInput(Unit) {
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Main)
				val pressed =
					event.changes.any {
						it.pressed && !it.previousPressed && !it.isConsumed
					}

				if (pressed && event.buttons.isPrimaryPressed)
					onPress()
			}
		}
	}

/** A press of a row: which keys were held, and when, by the pointer's own clock. */
@Immutable
private data class Press(
	val click: Click,
	val at: Long,
	/** How soon a second press must follow for the two to be a double click, as the platform sets it. */
	val within: Long,
)

/**
 * Presses of a row, each reported as it happens, so a row is chosen as it is pressed and not a double click's wait
 * later. Whether two presses are a double click is the listing's to say, since only it sees presses of other rows.
 */
private fun Modifier.pressing(onPress: (Press) -> Unit): Modifier =
	pointerInput(Unit) {
		awaitEachGesture {
			val down = awaitFirstDown()

			if (currentEvent.buttons.isPrimaryPressed) {
				down.consume()
				onPress(
					Press(
						clickOf(currentEvent.keyboardModifiers),
						down.uptimeMillis,
						viewConfiguration.doubleTapTimeoutMillis,
					),
				)
				waitForUpOrCancellation()?.consume()
			}
		}
	}

/** The last press of a row, for the next press to be seen as a double click of it or not. */
@Immutable
private data class Pressed(
	val row: RowId,
	val press: Press,
) {
	/** Whether [press] of [row] makes a double click with this one: the same row, no keys held, soon enough. */
	fun doubledBy(row: RowId, press: Press): Boolean =
		row == this.row && press.click == Click.Only && this.press.click == Click.Only &&
			press.at - this.press.at <= press.within
}

private fun clickOf(keys: PointerKeyboardModifiers): Click =
	when {
		keys.isShiftPressed -> Click.Range
		keys.isCtrlPressed || keys.isMetaPressed -> Click.Toggle
		else -> Click.Only
	}

/**
 * A column of the list: its heading, the order it sorts by (none, for one that sorts nothing), its share of the width,
 * and which side it keeps to.
 */
private data class Heading(
	val label: String,
	val column: Sort.Column?,
	val share: Float,
	val end: Boolean = false,
)

/** The listing's headings: a folder's, and a search's, which says where each result is. */
private val HEADINGS =
	listOf(
		Heading("Name", Sort.Column.Name, Dims.nameColumn),
		Heading("Date modified", Sort.Column.Modified, Dims.dateColumn),
		Heading("Type", Sort.Column.Type, Dims.typeColumn),
		Heading("Size", Sort.Column.Size, Dims.sizeColumn, end = true),
	)

private val RESULT_HEADINGS =
	HEADINGS.take(1) + Heading("Folder", null, Dims.folderColumn) + HEADINGS.drop(1)

/**
 * The headings that sort the listing, each by its column, the other way round when clicked again: over the list's
 * columns, or, over a gallery's tiles, which have no columns, as a row of the same choices after "Sort by". A search's
 * results are best first until a heading is clicked, and a column they cannot be ordered by (too many to read every
 * one's size or date) is drawn faint and does nothing.
 */
@Composable
private fun Header(state: PickerState, onEvent: (PickerEvent) -> Unit) {
	val type = LocalType.current
	val layout = state.layout
	val found = state.listing as? Listing.Found
	val sort =
		if (found == null)
			state.arrangement.sort
		else
			(state.arrangement.results as? Order.By)?.sort

	val headings =
		when {
			found == null -> HEADINGS
			layout == Layout.Gallery -> HEADINGS
			else -> RESULT_HEADINGS
		}

	Row(
		Modifier.fillMaxWidth().height(Dims.header).padding(
			horizontal =
				Dims.gap + Dims.gapSmall,
		),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement =
			when (layout) {
				Layout.List -> Arrangement.Start
				Layout.Gallery -> Arrangement.spacedBy(Dims.gap)
			},
	) {
		if (layout == Layout.Gallery)
			Text("Sort by", style = type.muted, maxLines = 1)
		for (heading in headings)
			HeaderCell(
				heading,
				sort,
				when (layout) {
					Layout.List -> Modifier.weight(heading.share)
					Layout.Gallery -> Modifier
				},
				end = heading.end && layout == Layout.List,
				enabled = heading.column != null && found?.orders(heading.column) != false,
				onEvent,
			)
	}
}

@Composable
private fun HeaderCell(
	heading: Heading,
	sort: Sort?,
	modifier: Modifier,
	end: Boolean,
	enabled: Boolean,
	onEvent: (PickerEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()

	val sorted = sort != null && sort.by == heading.column

	Row(
		modifier
			.fillMaxHeight()
			.background(
				if (felt.hovered && enabled)
					palette.hover
				else
					Color.Transparent,
			).focusProperties { canFocus = false }
			.clickable(source, indication = null, enabled = enabled) {
				heading.column?.let { onEvent(PickerEvent.Sorted(it)) }
			}.testTag("column:${heading.label}")
			.padding(horizontal = Dims.gapSmall),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = if (end) Arrangement.End else Arrangement.Start,
	) {
		Text(
			heading.label,
			style =
				when {
					sorted -> type.muted.copy(color = palette.text)
					enabled || heading.column == null -> type.muted
					else -> type.muted.copy(color = palette.textFaint)
				},
			maxLines = 1,
		)
		if (sorted)
			GlyphIcon(
				if (sort!!.ascending)
					Glyph.SortUp
				else
					Glyph.SortDown,
				palette.textMuted,
				14.dp,
			)
	}
}

/** Where [row] is, as a search's results say it; nothing in a folder, where every row is in the folder shown. */
private fun whereOf(row: Row, state: PickerState): String? =
	state.results?.let { folderOf(row, state.home) }

/** The folder [row] is in, from `~` where it is under [home]. */
internal fun folderOf(row: Row, home: Path): String {
	val folder = row.path.parent ?: return "/"

	return if (folder.startsWith(home))
		"~" + folder.toString().removePrefix(home.toString())
	else
		folder.toString()
}

@Composable
private fun EntryRow(
	row: Row,
	where: String?,
	chosen: Boolean,
	cursor: Boolean,
	zoom: Float,
	renamed: String?,
	stops: Stops,
	onPress: (Press) -> Unit,
	onEvent: (PickerEvent) -> Unit,
	modifier: Modifier = Modifier,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()
	val pressed by rememberUpdatedState(onPress)
	val shape = RoundedCornerShape(Dims.radius)
	val body = type.body.times(zoom)
	val muted = type.muted.times(zoom)
	val background =
		when {
			chosen -> palette.chosen
			hovered -> palette.hover
			else -> Color.Transparent
		}

	Row(
		modifier
			.alpha(if (row.hidden) 0.6f else 1f)
			.fillMaxWidth()
			.height(Dims.row * zoom)
			.background(background, shape)
			.border(
				1.dp,
				if (chosen)
					palette.chosenEdge
				else
					Color.Transparent,
				shape,
			).ring(cursor, palette.focus, outset = 0.dp, width = 1.5.dp)
			.hoverable(hover)
			.pressing { pressed(it) }
			.semantics { selected = chosen }
			.testTag("row:${row.name}")
			.padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(Modifier.weight(Dims.nameColumn), verticalAlignment = Alignment.CenterVertically) {
			GlyphIcon(
				if (row.isFolder) Glyph.Folder else Glyph.File,
				palette.text,
				Dims.icon * zoom,
				category = row.category,
			)
			Spacer(Modifier.width(Dims.gap + 2.dp))
			NameOf(row, renamed, body, stops, onEvent, Modifier.weight(1f, fill = renamed != null))
		}
		where?.let {
			Text(
				it,
				style = muted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(Dims.folderColumn).padding(end = Dims.gap),
			)
		}
		Text(
			row.modified?.text.orEmpty(),
			style = muted,
			maxLines = 1,
			modifier = Modifier.weight(Dims.dateColumn),
		)
		Text(
			row.type,
			style = muted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(Dims.typeColumn),
		)
		Text(
			row.size?.text.orEmpty(),
			style = muted,
			maxLines = 1,
			textAlign = TextAlign.End,
			modifier = Modifier.weight(Dims.sizeColumn),
		)
	}
}

/** One entry as a tile of the gallery: its picture, or its icon drawn large, and its name under it. */
@Composable
private fun Tile(
	row: Row,
	path: Path,
	chosen: Boolean,
	cursor: Boolean,
	zoom: Float,
	renamed: String?,
	stops: Stops,
	onPress: (Press) -> Unit,
	onEvent: (PickerEvent) -> Unit,
	modifier: Modifier = Modifier,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()
	val pressed by rememberUpdatedState(onPress)
	val shape = RoundedCornerShape(Dims.radius)

	Column(
		modifier
			.alpha(if (row.hidden) 0.6f else 1f)
			.height(Dims.tileHeight * zoom)
			.background(
				when {
					chosen -> palette.chosen
					hovered -> palette.hover
					else -> Color.Transparent
				},
				shape,
			).border(
				1.dp,
				if (chosen)
					palette.chosenEdge
				else
					Color.Transparent,
				shape,
			).ring(cursor, palette.focus, outset = 0.dp, width = 1.5.dp)
			.hoverable(hover)
			.pressing { pressed(it) }
			.semantics { selected = chosen }
			.testTag("row:${row.name}")
			.padding(Dims.gap),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		// The name is drawn larger with the tile only so far, a size people read; the picture has the rest.
		val name = type.body.times(minOf(zoom, 1.5f))

		Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
			Picture(row, path, zoom)
		}
		Spacer(Modifier.height(Dims.gapSmall))
		NameOf(
			row,
			renamed,
			name,
			stops,
			onEvent,
			Modifier.fillMaxWidth().height(
				with(LocalDensity.current) { name.lineHeight.toDp() * 2 },
			),
			maxLines = 2,
			textAlign = TextAlign.Center,
		)
	}
}

/**
 * The picture of [row]: a small picture of an image (LocalThumbnails), made on an IO thread while its icon is shown,
 * at a size for how large it is drawn, or the icon alone for anything else.
 */
@Composable
private fun Picture(row: Row, path: Path, zoom: Float) {
	val palette = LocalPalette.current
	val thumbnails = LocalThumbnails.current
	val drawable = !row.isFolder && Thumbnails.drawable(row.name)

	BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		// Made for the longer side of its room, so that it is as sharp as it is drawn.
		val side = maxOf(constraints.maxWidth, constraints.maxHeight)

		val picture by produceState<ImageBitmap?>(
			null,
			path,
			row.modified,
			Thumbnails.sideFor(side),
		) {
			if (drawable)
				value = thumbnails.of(path, row.modified?.value, side)
		}

		when (val shown = picture) {
			null ->
				GlyphIcon(
					if (row.isFolder) Glyph.Folder else Glyph.File,
					palette.text,
					minOf(64.dp * zoom, minOf(maxWidth, maxHeight) * 0.8f),
					category = row.category,
				)
			else ->
				Image(
					shown,
					contentDescription = row.name,
					modifier = Modifier.fillMaxSize().testTag("picture:${row.name}"),
					contentScale = ContentScale.Inside,
				)
		}
	}
}

@Composable
private fun Message(title: String, detail: String?) {
	val type = LocalType.current

	Column(
		Modifier.fillMaxSize().padding(40.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		Text(title, style = type.title)
		detail?.let {
			Text(
				it,
				style = type.muted,
				textAlign = TextAlign.Center,
				modifier = Modifier.padding(top = Dims.gapSmall),
			)
		}
	}
}

// ---- the footer: the name, the filter, the buttons -----------------------------------------------------------

@Composable
private fun Footer(state: PickerState, stops: Stops, onEvent: (PickerEvent) -> Unit) {
	val type = LocalType.current
	val saving = state.choosing is Choosing.SaveAs

	Column(
		Modifier
			.fillMaxWidth()
			.height(Dims.footer)
			.padding(horizontal = Dims.gap * 2, vertical = Dims.gap + 2.dp),
		verticalArrangement = Arrangement.spacedBy(Dims.gap + 2.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				if (state.choosing is Choosing.Folder)
					"Folder:"
				else
					"File name:",
				style = type.body,
				modifier = Modifier.width(80.dp),
			)
			Field(
				state.nameField,
				{ onEvent(PickerEvent.Named(it)) },
				Modifier.weight(1f).testTag("name"),
				hint = state.nameHint,
				focus = stops.name,
				// Saving, the name is selected without its extension, so that typing replaces only the name.
				selecting = {
					if (saving)
						TextRange(0, it.lastIndexOf('.').takeIf { dot -> dot > 0 } ?: it.length)
					else
						TextRange(0, it.length)
				},
				onKey = {
					val accepting = it.type == KeyEventType.KeyDown && it.isEnter

					if (accepting)
						onEvent(PickerEvent.Accepted)

					accepting
				},
			)
			// The asking application's filters, where it offers a choice of them; a folder picker shows no files.
			if (state.choosing !is Choosing.Folder && state.filters.size > 1) {
				Spacer(Modifier.width(Dims.gap))
				Select(
					state.filters,
					state.arrangement.filter,
					{ it.label },
					Modifier.width(220.dp).testTag("filter"),
				) { onEvent(PickerEvent.Filtered(it)) }
			}
		}
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				statusOf(state),
				style = type.muted,
				modifier = Modifier.weight(1f).testTag("status"),
			)
			PickerButton(
				state.accept.label,
				Emphasis.Primary,
				state.accept.enabled,
				Modifier.testTag("accept"),
			) { onEvent(PickerEvent.Accepted) }
			Spacer(Modifier.width(Dims.gap))
			PickerButton(
				"Cancel",
				Emphasis.Secondary,
				enabled = true,
				Modifier.testTag("cancel"),
			) {
				onEvent(PickerEvent.Cancelled)
			}
		}
	}
}

/**
 * How many rows are shown (of how many, where some are left out) and how many chosen, as the status line says it; of
 * a search's results, what they are ordered by, where it is being worked out still or cannot be had of so many.
 */
private fun statusOf(state: PickerState): String {
	val shown = state.rows.size
	val chosen = state.choosing.rows.size
	val entries = state.entryCount
	val found = state.listing as? Listing.Found
	val noun =
		if (found == null)
			"item"
		else
			"result"

	val items =
		if (entries == 1)
			"1 $noun"
		else
			"${"%,d".format(entries)} ${noun}s"

	val ordering =
		found?.let {
			when (val column = it.pending(state.arrangement.results)) {
				null -> null
				else -> "ordering by ${headingOf(column).lowercase()}…"
			}
		}

	val counted =
		if (shown == entries)
			items
		else
			"$shown of $items"

	return when {
		state.listing is Listing.Reading -> "Reading…"
		else ->
			listOfNotNull(
				counted,
				"$chosen selected".takeIf {
					chosen > 0
				},
				ordering,
			).joinToString("  ·  ")
	}
}

// ---- notices -------------------------------------------------------------------------------------------------

/** What a notice says, and the words on its buttons: [no] for a notice that asks, null for one that only tells. */
@Immutable
private data class Words(
	val title: String,
	val detail: String,
	val yes: String,
	val no: String?,
)

private fun wordsOf(notice: Notice): Words =
	when (notice) {
		is Notice.NotFound ->
			Words(
				"Can't find “${notice.text}”",
				"Check the name and try again.",
				"OK",
				null,
			)
		is Notice.NotAFolder ->
			Words(
				"“${notice.text}” is a file",
				"Choose a folder, or type the path of one.",
				"OK",
				null,
			)
		is Notice.Replace ->
			Words(
				"“${notice.path.name}” already exists",
				"Saving will replace what is in it. Replace it?",
				"Replace",
				"Cancel",
			)
		is Notice.ConfirmTrash ->
			Words(
				"Move ${countOf(notice.paths)} to the trash?",
				"It can be put back from the trash.",
				"Move to trash",
				"Cancel",
			)
		is Notice.Failed -> Words(notice.doing, notice.why, "OK", null)
		is Notice.NoFolder ->
			Words(
				"Search results aren't a folder",
				"Choose a result to save “${notice.text}” beside, or type a whole path.",
				"OK",
				null,
			)
	}

/**
 * A notice, over the picker and in front of the keyboard: it takes the keyboard to its first button, Escape puts it
 * away, and nothing behind it can be reached until it is answered.
 */
@Composable
private fun NoticeLayer(notice: Notice, onEvent: (PickerEvent) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val words = wordsOf(notice)
	val first = remember { FocusRequester() }
	val shape = RoundedCornerShape(8.dp)

	Popup(
		alignment = Alignment.Center,
		onDismissRequest = { onEvent(PickerEvent.Dismissed) },
		properties = PopupProperties(focusable = true),
	) {
		Box(
			Modifier.fillMaxSize().background(palette.scrim).testTag("notice"),
			contentAlignment = Alignment.Center,
		) {
			Column(
				Modifier
					.width(420.dp)
					.background(palette.surface, shape)
					.border(1.dp, palette.border, shape)
					.padding(20.dp),
			) {
				Text(words.title, style = type.title)
				Text(words.detail, style = type.body, modifier = Modifier.padding(top = Dims.gap))
				Row(
					Modifier.fillMaxWidth().padding(top = 20.dp),
					horizontalArrangement = Arrangement.spacedBy(Dims.gap, Alignment.End),
				) {
					val asks = notice is Notice.Replace || notice is Notice.ConfirmTrash

					PickerButton(
						words.yes,
						Emphasis.Primary,
						enabled = true,
						Modifier.testTag("yes"),
						first,
					) {
						onEvent(
							if (asks)
								PickerEvent.Confirmed
							else
								PickerEvent.Dismissed,
						)
					}
					words.no?.let {
						PickerButton(it, Emphasis.Secondary, enabled = true, Modifier.testTag("no")) {
							onEvent(PickerEvent.Dismissed)
						}
					}
				}
			}
		}
		LaunchedEffect(Unit) { first.requestFocus() }
	}
}
