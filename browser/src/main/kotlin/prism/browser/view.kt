package prism.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.name
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import prism.desktop.Place
import prism.preferences.Align
import prism.preferences.Builtin
import prism.preferences.ColumnId
import prism.preferences.ColumnSetting
import prism.preferences.Columns
import prism.preferences.DateFormat
import prism.preferences.DatePart
import prism.preferences.Kind
import prism.preferences.Layout
import prism.preferences.Sort
import prism.preferences.Width
import prism.window.WindowLocals

/*
 * The picker, drawn: a pure function of a [BrowserState], made of the controls in elements.kt. It shows what the state
 * says and reports what the user does as a [BrowserEvent]; it changes nothing itself.
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
fun FileBrowser(
	state: BrowserState,
	modifier: Modifier = Modifier,
	mouseChords: Flow<Chord> = emptyFlow(),
	onEvent: (BrowserEvent) -> Unit = {},
) {
	val palette = LocalPalette.current
	val stops = remember { Stops() }
	val saving = state.choosing is Choosing.SaveAs
	val keyboard = remember { KeyboardUse() }
	val hint = remember { DropHint() }
	val latest by rememberUpdatedState(onEvent)
	val bindings = LocalBindings.current
	val current by rememberUpdatedState(state)

	/** What a window's action (or one left to the window) does. */
	fun act(action: Action): Boolean = windowAction(action, current, stops, latest)

	// The mouse's buttons, read from AWT by whoever runs the browser, bound as the keys are.
	LaunchedEffect(mouseChords, bindings) {
		mouseChords.collect { chord -> bindings.window(chord)?.let(::act) }
	}

	// The keyboard starts where a desktop's dialogs start it: at the name to save under, or at the listing.
	LaunchedEffect(Unit) {
		if (saving)
			stops.name.requestFocus()
		else
			stops.listing.requestFocus()
	}

	CompositionLocalProvider(
		LocalKeyboardUse provides keyboard,
		LocalDropHint provides hint,
	) {
		Box(
			modifier
				.fillMaxSize()
				.background(palette.window)
				.usedBy(keyboard)
				.zoomedByWheel { latest(BrowserEvent.Zoomed(it)) }
				.onPreviewKeyEvent { shortcut(it, bindings, ::act) }
				.onKeyEvent { afterwards(it, bindings, ::act) },
		) {
			Column(Modifier.fillMaxSize()) {
				Toolbar(state, stops, onEvent)
				Line(palette.border)
				Row(Modifier.weight(1f).fillMaxWidth()) {
					// A place that is a tool of its own (an archive's window) has no places on disk beside it.
					if (state.inPlace
							?.placed
							?.type
							?.sidebar != false
					) {
						SidebarPane(state, stops, onEvent)
						Box(Modifier.width(1.dp).fillMaxHeight().background(palette.border))
					}
					// The listing in its own type: its own size and font, where the user chose them.
					CompositionLocalProvider(LocalType provides LocalTypes.current.listing) {
						ListingPane(state, stops, Modifier.weight(1f), ::act, onEvent)
					}
				}
				Line(palette.border)
				// A picker's name and buttons; an explorer, which chooses nothing for anyone, has only a status line.
				if (state.choosing is Choosing.Exploring)
					StatusBar(state)
				else
					Footer(state, stops, onEvent)
			}
			state.search.takeIf { it.open }?.let { SearchLayer(it, state.home, onEvent) }
			if (state.arrangingQuick)
				QuickAccessLayer(state.sidebar, onEvent)
			state.notice?.let { NoticeLayer(it, onEvent) }
			// What is waited on, over everything, where nothing is asking: a notice is answered first.
			if (state.notice == null)
				state.running.firstOrNull()?.let { WorkLayer(it, onEvent) }
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

/**
 * The window's actions bound to chords no text field types, seen before the control the keyboard is on sees them
 * (docs/picker.md, "Bindings"), so they work wherever the keyboard is.
 */
private fun shortcut(
	key: KeyEvent,
	bindings: Bindings,
	act: (Action) -> Boolean,
): Boolean {
	// A chord with Ctrl types its character as well, after it (Ctrl+2 types a "2"), which a text field would take as
	// typing: it is the chord's, and goes nowhere. Ctrl with Alt is AltGr on many keyboards, which types for real.
	if (key.type == KeyEventType.Unknown)
		return key.isCtrlPressed && !key.isAltPressed && key.utf16CodePoint >= ' '.code

	if (key.type != KeyEventType.KeyDown)
		return false

	return Chord.of(key)?.let(bindings::window)?.let(act) ?: false
}

/**
 * The actions left to the window by whatever has the keyboard, which sees them first: Escape (a field leaves what it is
 * doing first), and undo and redo of the browser's changes to the disk, except in a text field, whose own typing they
 * undo.
 */
private fun afterwards(
	key: KeyEvent,
	bindings: Bindings,
	act: (Action) -> Boolean,
): Boolean {
	if (key.type != KeyEventType.KeyDown)
		return false

	return Chord.of(key)?.let(bindings::leftover)?.let(act) ?: false
}

/**
 * What a window's action does in [state]: the event it is, or the keyboard taken where it goes. False for an action
 * that is not a window's (a row's, or a move of the cursor, which the listing does), or that this browser does not do.
 */
private fun windowAction(
	action: Action,
	state: BrowserState,
	stops: Stops,
	onEvent: (BrowserEvent) -> Unit,
): Boolean {
	val exploring = state.choosing is Choosing.Exploring

	val event =
		when (action) {
			Action.Back -> BrowserEvent.Back
			Action.Forward -> BrowserEvent.Forward
			Action.Up -> BrowserEvent.Up
			Action.EditAddress -> BrowserEvent.EditedAddress
			Action.SearchIndex -> BrowserEvent.OpenedSearch
			Action.Refresh -> BrowserEvent.Refreshed
			Action.ToggleHidden -> BrowserEvent.ToggledEveryHidden
			Action.NewFolder -> BrowserEvent.MadeFolder
			Action.NewFile -> BrowserEvent.MadeFile.takeIf { exploring }
			Action.ViewList -> BrowserEvent.Viewed(Layout.List)
			Action.ViewGallery -> BrowserEvent.Viewed(Layout.Gallery)
			Action.ZoomIn -> BrowserEvent.Zoomed(1)
			Action.ZoomOut -> BrowserEvent.Zoomed(-1)
			Action.ZoomReset -> BrowserEvent.Unzoomed
			Action.OpenTerminal -> BrowserEvent.OpenedTerminal().takeIf { exploring }
			Action.NewWindow ->
				BrowserEvent
					.Requested(
						Request.NewWindow(state.folder ?: state.home),
					).takeIf { exploring }
			Action.Preferences -> BrowserEvent.Requested(Request.Preferences).takeIf { exploring }
			// Exploring, there is nothing to cancel: Escape lets go of what is chosen. A picker is cancelled.
			Action.Cancel ->
				if (exploring)
					BrowserEvent.ClickedNowhere
				else
					BrowserEvent.Cancelled
			Action.Undo -> BrowserEvent.Undone
			Action.Redo -> BrowserEvent.Redone
			// An explorer's window ends as a picker does, and whoever opened it closes it.
			Action.CloseWindow -> BrowserEvent.Cancelled
			Action.SearchFolder -> {
				stops.search.requestFocus()

				return true
			}
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

/**
 * The toolbar, in groups a little apart: the window's menu; the way back, forward and up; the path, and the search of
 * the folder; and how the listing is filtered and drawn.
 */
@Composable
private fun Toolbar(state: BrowserState, stops: Stops, onEvent: (BrowserEvent) -> Unit) {
	val bindings = LocalBindings.current

	/** What [label] does, and the key that does it too, where there is one. */
	fun tip(label: String, action: Action): String =
		bindings.of(action).firstOrNull()?.let { "$label ($it)" } ?: label

	Row(
		Modifier
			.fillMaxWidth()
			.background(LocalPalette.current.window)
			.padding(horizontal = Space.s, vertical = Space.s),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		// The window's own menu first, where a window's menu is on every desktop.
		// The window's own menu first, where a window's menu is on every desktop.
		if (state.choosing is Choosing.Exploring) {
			MenuButton(Glyph.Menu, "Menu", {
				appMenuOf(state, bindings)
			}, onEvent, Modifier.testTag("menu-button"))
			Spacer(Modifier.width(Space.xs))
		}
		ToolButton(
			Glyph.Back,
			tip("Back", Action.Back),
			state.history.back.isNotEmpty(),
			Modifier.testTag("back"),
		) {
			onEvent(BrowserEvent.Back)
		}
		ToolButton(
			Glyph.Forward,
			tip("Forward", Action.Forward),
			state.history.forward.isNotEmpty(),
			Modifier.testTag("forward"),
		) {
			onEvent(BrowserEvent.Forward)
		}
		ToolButton(
			Glyph.Up,
			tip("The folder above", Action.Up),
			state.folder?.parent != null || state.place?.within?.isNotEmpty() == true,
			Modifier.testTag("up"),
		) {
			onEvent(BrowserEvent.Up)
		}
		Spacer(Modifier.width(Space.s))
		AddressBar(state, stops, Modifier.weight(1f), onEvent)
		Spacer(Modifier.width(Space.s))
		Field(
			state.arrangement.query,
			{ onEvent(BrowserEvent.Searched(it)) },
			Modifier.width(260.dp).testTag("search"),
			hint = "Search ${state.placeName}",
			leading = Glyph.Search,
			focus = stops.search,
			onKey = { searchKey(it, state.arrangement.query, stops, onEvent) },
		)
		Spacer(Modifier.width(Space.s))
		MenuButton(
			Glyph.Filter,
			"Filters",
			{ filterMenuOf(state) },
			onEvent,
			Modifier.testTag("filters"),
			selected = state.entryFilters.kinds.isNotEmpty(),
		)
		Segmented(
			Layout.entries,
			state.layout,
			{ it.name },
			tag = "layout",
			focusable = false,
			glyph = {
				when (it) {
					Layout.List -> Glyph.ViewList
					Layout.Gallery -> Glyph.ViewGrid
				}
			},
		) { onEvent(BrowserEvent.Viewed(it)) }
	}
}

/** Escape empties the search before it cancels anything; Enter and Down take the keyboard to what it found. */
private fun searchKey(
	key: KeyEvent,
	query: String,
	stops: Stops,
	onEvent: (BrowserEvent) -> Unit,
): Boolean =
	when {
		key.type != KeyEventType.KeyDown -> false
		key.key == Key.Escape && query.isNotEmpty() ->
			true.also {
				onEvent(BrowserEvent.Searched(""))
			}
		key.isEnter || key.key == Key.DirectionDown ->
			true.also { stops.listing.requestFocus() }
		else -> false
	}

/** The path to the folder shown: steps to click, or, after Ctrl+L or a click beside them, a path to type. */
@Composable
private fun AddressBar(
	state: BrowserState,
	stops: Stops,
	modifier: Modifier,
	onEvent: (BrowserEvent) -> Unit,
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
			when (val at = state.at) {
				is Location.Folder -> Crumbs(state.crumbs, modifier, onEvent)
				is Location.Results -> ResultsBar(at.finding, modifier, onEvent)
				is Location.Place -> PlaceBar(at, modifier, onEvent)
				Location.Trash -> TrashBar(modifier)
			}
		is Address.Editing -> {
			Field(
				address.text,
				{ onEvent(BrowserEvent.AddressTyped(it)) },
				modifier.testTag("address"),
				focus = stops.address,
				onLeave = { onEvent(BrowserEvent.LeftAddress) },
				onKey = {
					when {
						it.type != KeyEventType.KeyDown -> false
						it.isEnter ->
							true.also {
								going = true
								onEvent(BrowserEvent.Went)
							}
						// Escape leaves the path as it was, and the keyboard goes back to the listing.
						it.key == Key.Escape ->
							true.also {
								stops.listing.requestFocus()
								onEvent(BrowserEvent.LeftAddress)
							}
						else -> false
					}
				},
			)
			LaunchedEffect(Unit) { stops.address.requestFocus() }
		}
	}
}

/**
 * What the path's bar shows, in a field's look: its [glyph], then [content]. Clicked where nothing in it takes the click,
 * it does [onClick] (a path to type, a search to open again), where there is one.
 */
@Composable
private fun Bar(
	glyph: Glyph,
	modifier: Modifier,
	clickLabel: String? = null,
	onClick: (() -> Unit)? = null,
	content: @Composable RowScope.() -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val shape = RoundedCornerShape(Radius.medium)

	Row(
		modifier
			.ring(felt.ringed, palette.focus)
			.height(type.control)
			.background(
				if (felt.hovered &&
					onClick != null
				)
					palette.hover
				else
					palette.field,
				shape,
			).border(
				1.dp,
				if (felt.hovered &&
					onClick != null
				)
					palette.fieldEdgeHover
				else
					palette.fieldEdge,
				shape,
			).then(
				onClick?.let {
					Modifier.clickable(
						source,
						indication = null,
						onClickLabel = clickLabel,
						onClick = it,
					)
				} ?: Modifier,
			).padding(start = Space.m, end = Space.s),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		GlyphIcon(glyph, palette.textMuted, type.icon * 0.85f)
		content()
	}
}

/** Steps to click, one after another with a chevron between, the last shown in full where the bar is too short. */
@Composable
private fun RowScope.Steps(
	count: Int,
	step: @Composable (index: Int, last: Boolean) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val scroll = rememberScrollState()

	// A path too long for the bar shows its end, where the folder shown is.
	LaunchedEffect(count) {
		snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
	}

	Row(
		Modifier.weight(1f, fill = false).horizontalScroll(scroll),
		verticalAlignment = Alignment.CenterVertically,
	) {
		for (index in 0 until count) {
			val last = index == count - 1

			step(index, last)
			if (!last)
				GlyphIcon(Glyph.Chevron, palette.textFaint, type.icon * 0.6f)
		}
	}
}

@Composable
private fun Crumbs(
	crumbs: List<Crumb>,
	modifier: Modifier,
	onEvent: (BrowserEvent) -> Unit,
) {
	Bar(Glyph.Folder, modifier.testTag("crumbs"), "Type a path", {
		onEvent(BrowserEvent.EditedAddress)
	}) {
		Steps(crumbs.size) { index, last ->
			val crumb = crumbs[index]

			CrumbStep(
				crumb,
				last,
				Modifier.folderDrop(
					crumb.path,
					{ onEvent(BrowserEvent.Opened(crumb.path)) }.takeIf { !last },
					onEvent,
				),
			) { onEvent(BrowserEvent.Opened(crumb.path)) }
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
	onEvent: (BrowserEvent) -> Unit,
) {
	val type = LocalType.current

	Bar(Glyph.Search, modifier.testTag("results"), "Search again", {
		onEvent(BrowserEvent.OpenedSearch)
	}) {
		Text("Search results", style = type.strong, maxLines = 1)
		Text(
			"“${results.text}”",
			style = type.muted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f, fill = false),
		)
	}
}

/** In place of the path, that the window shows the trash: every trash's entries, which are on no one path. */
@Composable
private fun TrashBar(modifier: Modifier) {
	Bar(Glyph.Trash, modifier.testTag("trash-bar")) {
		Text("Trash", style = LocalType.current.strong, maxLines = 1)
	}
}

/**
 * In place of the path, where in a place the window is: what the place is called, then each folder of it gone into, as
 * steps to click, as a path's are.
 */
@Composable
private fun PlaceBar(
	place: Location.Place,
	modifier: Modifier,
	onEvent: (BrowserEvent) -> Unit,
) {
	val steps = listOf(place.placed.title) + place.within

	Bar(Glyph.Folder, modifier.testTag("place")) {
		Steps(steps.size) { index, last ->
			// The steps are only names here: the path each stands for is the place's own, and not one on disk.
			CrumbStep(Crumb(steps[index], Path.of("/")), last, Modifier) {
				onEvent(BrowserEvent.WentWithin(place.within.take(index)))
			}
		}
	}
}

@Composable
private fun CrumbStep(
	crumb: Crumb,
	last: Boolean,
	modifier: Modifier,
	onClick: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()

	Text(
		crumb.label,
		style =
			if (last)
				type.strong
			else
				type.body.copy(color = palette.textMuted),
		maxLines = 1,
		modifier =
			modifier
				.background(
					if (felt.hovered)
						palette.pressed
					else
						Color.Transparent,
					RoundedCornerShape(Radius.small),
				).focusProperties { canFocus = false }
				.clickable(source, indication = null, onClick = onClick)
				.testTag("crumb:${crumb.label}")
				.padding(horizontal = Space.xs + Space.xxs, vertical = Space.xxs),
	)
}

// ---- the sidebar: the places --------------------------------------------------------------------------------

/**
 * The places, as one stop of the keyboard: the arrows move through them, Enter opens one. Which place the arrows are
 * on is the sidebar's own, as a list's highlighted entry is on any desktop: it starts at the place shown. Quick access
 * is drawn as large as the user set it, and a button beside its heading opens the window that arranges it.
 */
@Composable
private fun SidebarPane(
	state: BrowserState,
	stops: Stops,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val sidebar = state.sidebar
	// A picker chooses files for another application: the trash, whose entries it cannot give, is an explorer's.
	val groups = sidebar.groups(trash = state.choosing is Choosing.Exploring)
	val items = groups.flatMap { it.items }
	val menus = rememberContextMenus<BrowserEvent>()
	val bindings = LocalBindings.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()

	/** Whether [item] is what the window shows. */
	fun isShown(item: SidebarItem): Boolean =
		when (item) {
			SidebarItem.Trash -> state.inTrash
			else -> item.path == state.folder
		}

	/** What going to [item] is. */
	fun openingOf(item: SidebarItem): BrowserEvent =
		item.path?.let(BrowserEvent::Opened) ?: BrowserEvent.OpenedTrash

	var at by remember(sidebar) {
		mutableIntStateOf(items.indexOfFirst(::isShown).coerceAtLeast(0))
	}

	fun key(key: KeyEvent): Boolean {
		if (key.type != KeyEventType.KeyDown || items.isEmpty())
			return false

		val chord = Chord.of(key) ?: return false

		// The listing's moves and its Open, as the user has bound them, move through the places and open one.
		when (bindings.movement(chord) ?: bindings.listing(chord)) {
			Action.MoveUp, Action.PageUp -> at = (at - 1).coerceAtLeast(0)
			Action.MoveDown, Action.PageDown -> at = (at + 1).coerceAtMost(items.lastIndex)
			Action.First -> at = 0
			Action.Last -> at = items.lastIndex
			Action.Open, Action.Toggle -> onEvent(openingOf(items[at]))
			else -> return false
		}

		return true
	}

	Scaled(LocalSidebarScale.current) {
		ScrollArea(
			Modifier
				.width(Dims.sidebar)
				.fillMaxHeight()
				.background(palette.pane)
				.focusRequester(stops.places)
				.onKeyEvent(::key)
				.focusable(interactionSource = source)
				.testTag("places"),
			padding = PaddingValues(start = Space.s, top = Space.xs, bottom = Space.s),
		) {
			for (group in groups) {
				Row(
					Modifier.fillMaxWidth().padding(start = Space.s, top = Space.m, bottom = Space.xs),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(group.heading, style = type.heading, modifier = Modifier.weight(1f))
					if (group.arranged)
						ToolButton(
							Glyph.Settings,
							"Arrange ${group.heading.lowercase()}",
							enabled = true,
							Modifier.testTag("arrange-quick-access"),
							size = type.line + Space.xs,
							icon = type.icon * 0.8f,
						) { onEvent(BrowserEvent.ArrangingQuickAccess(true)) }
				}
				for (item in group.items) {
					val index = items.indexOf(item)
					val path = item.path

					PlaceRow(
						item,
						current = isShown(item),
						keyed = felt.ringed && index == at,
						Modifier
							.then(
								path?.let {
									Modifier.folderDrop(
										it,
										{ onEvent(BrowserEvent.Opened(it)) }.takeIf { path != state.folder },
										onEvent,
									)
								} ?: Modifier,
							).contextMenu(menus) { contextMenuOf(state, Target.Sidebar(item), bindings) },
					) {
						// A place clicked takes the keyboard to the places, as a list clicked does on any desktop.
						at = index
						stops.places.requestFocus()
						onEvent(openingOf(item))
					}
				}
			}
			ContextMenuHost(menus, onEvent)
		}
	}
}

@Composable
private fun PlaceRow(
	item: SidebarItem,
	current: Boolean,
	keyed: Boolean,
	modifier: Modifier,
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
		modifier
			.fillMaxWidth()
			.height(type.row)
			.ring(keyed, palette.focus, outset = 0.dp, width = 1.5.dp)
			.background(background, RoundedCornerShape(Radius.medium))
			.focusProperties { canFocus = false }
			.clickable(source, indication = null, onClick = onOpen)
			.testTag("place:${item.label}")
			.padding(horizontal = Space.s),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s + Space.xxs),
	) {
		GlyphIcon(
			glyphOf(item),
			if (current)
				palette.accentText
			else
				palette.textMuted,
			type.icon,
		)
		Text(
			item.label,
			style = if (current) type.strong else type.body,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/** The icon of what the sidebar shows. */
internal fun glyphOf(item: SidebarItem): Glyph =
	when (item) {
		SidebarItem.Trash -> Glyph.Trash
		is SidebarItem.Own -> glyphOf(item.place.kind)
		is SidebarItem.Pinned -> glyphOf(item.place.kind)
		is SidebarItem.Drive -> glyphOf(item.place.kind)
	}

/** The icon of one of prism's own places. */
internal fun glyphOf(builtin: Builtin): Glyph =
	when (builtin) {
		Builtin.Home -> Glyph.Home
		Builtin.Desktop -> Glyph.Desktop
		Builtin.Documents -> Glyph.Documents
		Builtin.Downloads -> Glyph.Downloads
		Builtin.Pictures -> Glyph.Pictures
		Builtin.Music -> Glyph.Music
		Builtin.Videos -> Glyph.Videos
		Builtin.Trash -> Glyph.Trash
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
		Place.Kind.Folder -> Glyph.Folder
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
 * The listing, as one stop of the keyboard: what it shows is [BrowserState.rows], as rows in columns or as tiles in a
 * gallery, with the cursor's row ringed while the keyboard is here, and kept in sight as it moves. Each folder starts
 * at its top. In a gallery, Left and Right move along a line of tiles, and Up and Down from one line to the next.
 *
 * Ctrl and the mouse's wheel draw it larger or smaller. The right button opens a menu: of a row, over a row (of every
 * row chosen, if it is one of them; otherwise it is chosen first, alone), and of the folder over the rest; Shift+F10
 * or the menu key opens the same from the keyboard, at the cursor's row.
 */
@Composable
private fun ListingPane(
	state: BrowserState,
	stops: Stops,
	modifier: Modifier,
	act: (Action) -> Boolean,
	onEvent: (BrowserEvent) -> Unit,
) {
	val bindings = LocalBindings.current
	val palette = LocalPalette.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val list = remember(state.at) { LazyListState() }
	val grid = remember(state.at) { LazyGridState() }
	val menus = rememberContextMenus<BrowserEvent>()
	val area = remember { InWindow() }
	val rows = state.rows
	val cursor = state.cursor?.at
	val cursorAt = cursor?.let(rows::indexOf)
	val query = state.arrangement.query
	val gallery = state.layout == Layout.Gallery
	// The rows as large as the listing's type makes them, and Ctrl and the wheel zooming from there.
	val zoom = state.scale
	val rowHeight = rowHeightOf(LocalRows.current, LocalType.current) * zoom
	val chosen = state.choosing.rows
	val renaming = state.renaming
	var typing by remember { mutableStateOf(Typing("", 0)) }
	var pressed by remember { mutableStateOf<Pressed?>(null) }
	// A row of several chosen, pressed with no key held: they all stay chosen until the press ends without a drag, as
	// it would drag them all, and it alone is chosen then.
	var deferred by remember { mutableStateOf<RowId?>(null) }
	val hint = LocalDropHint.current
	val exploring = state.choosing is Choosing.Exploring
	val world = LocalWorld.current
	val columns = state.columns.shown
	// Each part of a date as wide as its widest, and a character of the rows' text, at the size the rows are drawn.
	val dates = rememberDateWidths(LocalDateFormat.current, LocalType.current.muted) * zoom
	val character = rememberCharacterWidth(LocalType.current.body) * zoom
	// The space before a row's name: its icon, and the room after it.
	val beforeName =
		(rowIconOf(LocalRows.current, LocalType.current) + Space.s + Space.xxs) * zoom
	// How far across the rows are scrolled, where they are wider than the listing.
	val across = remember(state.at) { ScrollState(0) }

	/** What the user's action bound to a chord, or done by a double click, is done to: the rows, or the folder shown. */
	fun subjectOf(rows: List<Row>): Subject =
		state.subjectOf(
			if (rows.isEmpty())
				Target.Folder
			else
				Target.Rows(rows),
		)

	/** [row] opened by a double click: by the user's action for that, where one is offered for it; or as ever. */
	fun activate(row: RowId) {
		val subject = rows[row]?.let { subjectOf(listOf(it)) }
		val own =
			subject
				?.takeIf { exploring }
				?.let { state.actions.offered(it, world).firstOrNull { action -> action.default } }

		onEvent(
			if (own != null)
				BrowserEvent.Did(own, subject)
			else
				BrowserEvent.Activated(row),
		)
	}

	// Gone elsewhere, nothing dragged here is over anything of it any more.
	LaunchedEffect(state.at) { hint.clear() }

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
				.collect { (first, last) -> latest(BrowserEvent.Scrolled(first, last)) }
	}

	// A second plain press of the row pressed last, soon enough, is a double click; a third starts again.
	fun press(row: RowId, press: Press) {
		stops.listing.requestFocus()

		if (pressed?.doubledBy(row, press) == true) {
			pressed = null
			deferred = null
			activate(row)
		} else {
			pressed = Pressed(row, press)
			deferred = row.takeIf { press.click == Click.Only && it in chosen && chosen.size > 1 }
			if (deferred == null)
				onEvent(BrowserEvent.Clicked(row, press.click))
		}
	}

	/** A press of [row] ended without a drag: the row it chose alone, if its choosing waited for that. */
	fun release(row: RowId) {
		if (deferred == row)
			onEvent(BrowserEvent.Clicked(row, Click.Only))
		deferred = null
	}

	/** The rows dragging [row] drags: every row chosen, where it is one; or it alone. */
	fun draggedWith(row: Row): List<Row> =
		if (row.id in chosen)
			state.chosenRows
		else
			listOf(row)

	/** What dragging [row] drags, as files: none where any of it is not one on disk. */
	fun dragged(row: Row): List<java.nio.file.Path> =
		draggedWith(row).let { all ->
			all.mapNotNull { it.path }.takeIf { it.size == all.size }.orEmpty()
		}

	/**
	 * [row] as something dragged, and, for a folder, as somewhere to drop: a drag of it is a drag of what it drags, and
	 * a move another application made of them is the folder changed, read again.
	 */
	@Composable
	fun dragging(row: Row): Modifier =
		Modifier
			.filesDragged(
				paths = { dragged(row) },
				onStart = { deferred = null },
				onEnd = {
					if (it == Transfer.Move)
						onEvent(BrowserEvent.Refreshed)
				},
				// Entries of a place are dragged as what brings them out of it, where its place says what does.
				carried = { state.carrying(draggedWith(row)) },
			).then(
				// Only a folder on disk takes what is dropped: a place's own folders are not there to put files in.
				row.path?.takeIf { row.isFolder }?.let {
					Modifier.folderDrop(it, { onEvent(BrowserEvent.Activated(row.id)) }, onEvent)
				} ?: Modifier,
			)

	/** The rows a key acts on: those chosen, or the cursor's where none is. */
	fun targets(): List<Row> =
		state.chosenRows.ifEmpty {
			listOfNotNull(cursor?.let(rows::get))
		}

	/** The menu of [row], pressed with the right button: of the rows chosen if it is one, of it alone otherwise. */
	fun menuOf(row: Row): Menu<BrowserEvent> {
		stops.listing.requestFocus()

		val among = row.id in chosen

		if (!among)
			onEvent(BrowserEvent.Clicked(row.id, Click.Only))

		return contextMenuOf(
			state,
			Target.Rows(
				if (among)
					state.chosenRows
				else
					listOf(row),
			),
			bindings,
			world,
		)
	}

	fun folderMenu(): Menu<BrowserEvent> {
		stops.listing.requestFocus()

		return contextMenuOf(state, Target.Folder, bindings, world)
	}

	/** The menu of what is chosen (or of the folder, where nothing is), opened from the keyboard at the cursor's row. */
	fun openMenuByKey() {
		val shown = state.chosenRows
		val menu =
			if (shown.isEmpty())
				contextMenuOf(state, Target.Folder, bindings, world)
			else
				contextMenuOf(state, Target.Rows(shown), bindings, world)

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

	/** What a row's action does, on the rows chosen (or the cursor's); false where there are none to act on. */
	fun rowAction(action: Action): Boolean {
		val exploring = state.choosing is Choosing.Exploring
		// What the browser does itself to rows, it does to files on disk: entries of a place are not its to rename or
		// trash, so their keys are left for an action of the place's.
		val real = targets().all { it.path != null }
		val ids = targets().map { it.id }.takeIf { real }.orEmpty()
		val copied = targets().map { it.id }
		// What is in the trash is deleted for good from it, by either key, once asked.
		val trashed = targets().filter { it.item is Item.InTrash }.map { it.id }

		val event =
			when (action) {
				Action.Open -> BrowserEvent.Accepted
				Action.Toggle -> BrowserEvent.Toggled
				Action.SelectAll -> BrowserEvent.ChoseAll
				Action.Rename -> ids.singleOrNull()?.let { BrowserEvent.StartedRenaming(it) }
				// Delete moves to the trash, at once, where there is one; Shift+Delete deletes for good, once asked.
				Action.Trash ->
					(
						ids.takeIf {
							it.isNotEmpty()
						} ?: trashed.takeIf { it.isNotEmpty() }
					)?.let { BrowserEvent.Trashed(it) }
				Action.Erase ->
					(
						ids.takeIf {
							it.isNotEmpty()
						} ?: trashed.takeIf { it.isNotEmpty() }
					)?.let { BrowserEvent.AskedToErase(it) }
				Action.CopyPaths ->
					BrowserEvent.CopiedPaths(
						targets()
							.mapNotNull {
								it.path
							}.ifEmpty { listOfNotNull(state.folder) },
					)
				Action.CopyFiles ->
					copied
						.takeIf {
							it.isNotEmpty() && exploring && (real || state.carrying(targets()) != null)
						}?.let { BrowserEvent.CopiedFiles(it) }
				Action.CutFiles ->
					ids
						.takeIf {
							it.isNotEmpty() && exploring
						}?.let { BrowserEvent.CutFiles(it) }
				Action.Paste -> BrowserEvent.Pasted().takeIf { exploring }
				Action.Properties ->
					targets()
						.mapNotNull { it.path }
						.ifEmpty { listOfNotNull(state.folder) }
						.takeIf { it.isNotEmpty() && exploring }
						?.let { BrowserEvent.Requested(Request.Properties(it)) }
				Action.ContextMenu -> {
					openMenuByKey()

					return true
				}
				else -> null
			}

		event?.let(onEvent)

		return event != null
	}

	fun key(key: KeyEvent): Boolean {
		// A name being typed over has the keys; what it leaves is not the listing's.
		if (key.type != KeyEventType.KeyDown || renaming != null)
			return false

		val chord = Chord.of(key) ?: return false

		for (action in bindings.listings(chord))
			when (action) {
				is Action ->
					// Enter on one row is a double click of it, which an action of the user's may be the doing of.
					if (action == Action.Open && exploring && targets().size == 1) {
						activate(targets().single().id)

						return true
					} else if (rowAction(action) || act(action))
						return true
				// One of the user's own, done to the rows chosen (or the folder shown), where it is offered for them.
				is Custom -> {
					val subject = subjectOf(targets())
					// The action as this window has it: read for the programs this machine has.
					val own = state.actions[action.id]

					if (exploring && own != null && own.offered(subject, world)) {
						onEvent(BrowserEvent.Did(own, subject))

						return true
					}
				}
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

		// Shift extends what is chosen as the cursor moves, and Ctrl moves it without choosing, whatever key moves it.
		val carry =
			when {
				chord.shift -> Carry.Extend
				chord.ctrl || chord.meta -> Carry.Stay
				else -> Carry.Choose
			}

		val move =
			when (bindings.movement(chord)) {
				Action.MoveUp -> Move.By(-line)
				Action.MoveDown -> Move.By(line)
				Action.MoveLeft -> Move.By(-1).takeIf { gallery }
				Action.MoveRight -> Move.By(1).takeIf { gallery }
				Action.PageUp -> Move.By(-page)
				Action.PageDown -> Move.By(page)
				Action.First -> Move.First
				Action.Last -> Move.Last
				else -> null
			}

		if (move != null) {
			onEvent(BrowserEvent.Moved(move, carry))

			return true
		}

		// Letters typed jump to the next row whose name starts with them.
		val letter =
			key.utf16CodePoint
				.takeIf {
					it > ' '.code && !chord.ctrl && !chord.alt && !chord.meta
				}?.toChar() ?: return false

		typing = typing.then(letter, System.currentTimeMillis())
		onEvent(BrowserEvent.Jumped(typing.prefix))

		return true
	}

	// A rectangle dragged out from where no row is, in the listing's own space, while it is being dragged; and what
	// was chosen as it began, which a rectangle dragged with Ctrl held adds to.
	var band by remember { mutableStateOf<Rect?>(null) }
	var banded by remember { mutableStateOf(emptySet<RowId>()) }
	// Where the rows are laid out from: the list's, and the gallery's, room around them.
	val inset = with(LocalDensity.current) { LIST_INSET.toPx() }
	val tileInset = with(LocalDensity.current) { Dims.tilePad.toPx() }

	/** The rows [rect] takes in: those in sight that it touches, each where the listing has laid it out. */
	fun within(rect: Rect): Set<RowId> {
		val laid =
			if (gallery)
				grid.layoutInfo.visibleItemsInfo.map {
					it.index to
						Rect(
							Offset(it.offset.x + tileInset, it.offset.y + tileInset),
							Size(it.size.width.toFloat(), it.size.height.toFloat()),
						)
				}
			else
				list.layoutInfo.visibleItemsInfo.map {
					it.index to
						Rect(
							Offset(inset, it.offset + inset),
							Size(
								list.layoutInfo.viewportSize.width
									.toFloat(),
								it.size.toFloat(),
							),
						)
				}

		return laid
			.filter { (index, bounds) -> index < rows.size && bounds.overlaps(rect) }
			.mapTo(LinkedHashSet()) { (index, _) -> rows.idAt(index) }
	}

	// What a press where no row is, and a rectangle dragged from it, do, as the listing is by then: the pointer is
	// followed by one thing for as long as the listing is drawn, which asks these each time.
	val pressedNowhere by rememberUpdatedState { adding: Boolean ->
		stops.listing.requestFocus()
		// With Ctrl held, what is chosen stays, for what the rectangle takes in to be added to.
		banded =
			if (adding)
				chosen
			else
				emptySet()
		if (!adding)
			onEvent(BrowserEvent.ClickedNowhere)
	}

	val bandedNowhere by rememberUpdatedState { rect: Rect? ->
		band = rect
		if (rect != null && state.choosing.many)
			onEvent(BrowserEvent.Banded(banded + within(rect)))
	}

	val nowhere =
		Modifier
			.pressedNowhere(
				onPress = { pressedNowhere(it) },
				onBand = { bandedNowhere(it) },
			)
			// The rectangle being dragged out, over the rows, where they are laid out.
			.drawWithContent {
				drawContent()
				band?.takeIf { state.choosing.many }?.let {
					drawRect(palette.accent.copy(alpha = 0.14f), it.topLeft, it.size)
					drawRect(palette.accent, it.topLeft, it.size, style = Stroke(1.dp.toPx()))
				}
			}

	BoxWithConstraints(
		modifier
			.fillMaxHeight()
			.background(palette.surface)
			.focusRequester(stops.listing)
			.onKeyEvent(::key)
			.focusable(interactionSource = source)
			.testTag("listing"),
	) {
		// The columns as wide as the rows' room shares out, or as their least where that is more: the rows are then
		// wider than the listing, and they and their headings scroll across together.
		val layout =
			layoutOf(
				columns,
				maxWidth - LIST_INSET * 2 - Dims.scrollbarRoom,
				dates,
				character,
				beforeName,
			)

		val wide = layout.row + LIST_INSET * 2 + Dims.scrollbarRoom
		val sideways = wide > maxWidth

		Column(Modifier.fillMaxSize()) {
			if (gallery)
				SortHeadings(state, menus, onEvent)
			else
				ColumnHeadings(state, layout, wide, across, menus, onEvent)
			Line(palette.divider)

			Box(
				Modifier
					.weight(1f)
					.fillMaxWidth()
					.onGloballyPositioned { area.coordinates = it }
					.contextMenu(menus) { folderMenu() }
					// Files dropped between the rows: given to the folder shown, or, in a picker, gone to.
					.filesDropped(
						DropSpot.Here,
						state.folder,
						onHover = { hint.over(DropSpot.Here, it) },
						onEntries =
							{ entries: EntriesOut ->
								onEvent(BrowserEvent.DroppedEntries(entries, DropSpot.Here))
							}.takeIf { state.folder != null },
						onDrop = {
							paths,
							transfer,
							->
							onEvent(BrowserEvent.Dropped(paths, DropSpot.Here, transfer))
						},
					),
			) {
				when (val listing = state.listing) {
					// A folder that reads quickly would only flash a message, so a folder being read shows nothing yet.
					Listing.Reading -> Box(Modifier.fillMaxSize())
					is Listing.Unreadable ->
						Message(
							Glyph.Warning,
							state.place?.let { "“${it.placed.title}” can't be listed" }
								?: "This folder can't be opened",
							listing.why,
						)
					is Listing.Shown, is Listing.Found ->
						when {
							rows.isEmpty() -> {
								val none = state.entryCount == 0

								Message(
									when {
										query.isNotBlank() || listing is Listing.Found -> Glyph.Search
										none -> Glyph.Folder
										else -> Glyph.Filter
									},
									when {
										query.isNotBlank() -> "Nothing here matches “$query”"
										none && listing is Listing.Found -> "Nothing matches “${listing.finding.text}”"
										none && state.place != null -> "Nothing is here"
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
										onRelease = { release(row.id) },
										onEvent = onEvent,
										modifier = dragging(row).contextMenu(menus) { menuOf(row) },
									)
								}
							else ->
								// A notch of the wheel moves the list three rows, however large they are drawn; with Shift, or a
								// touchpad's sideways, it moves the rows across, where they are wider than the listing.
								Box(Modifier.fillMaxSize().wheelScrolled(list, rowHeight, across = across)) {
									Box(Modifier.fillMaxSize().horizontalScroll(across, enabled = false)) {
										LazyColumn(
											nowhere
												.width(wide)
												.fillMaxHeight()
												.padding(
													start = LIST_INSET,
													top = LIST_INSET,
													bottom = LIST_INSET + if (sideways) Dims.scrollbarRoom else 0.dp,
													end = LIST_INSET + Dims.scrollbarRoom,
												),
											state = list,
											userScrollEnabled = false,
										) {
											items(rows.size, key = rows::idAt, contentType = { "row" }) { index ->
												val row = rows[index]

												EntryRow(
													row,
													columns = columns,
													home = state.home,
													layout = layout,
													dates = dates,
													chosen = row.id in chosen,
													cursor = felt.ringed && row.id == cursor,
													zoom = zoom,
													renamed = renaming?.takeIf { it.row == row.id }?.text,
													stops = stops,
													onPress = { press(row.id, it) },
													onRelease = { release(row.id) },
													onEvent = onEvent,
													modifier = dragging(row),
													// The row's menu over its name, and the space after it in the name's column; the other
													// columns are the listing's, whose menu is the folder's.
													nameMenu = Modifier.contextMenu(menus) { menuOf(row) },
												)
											}
										}
									}
									ScrollbarBeside(rememberScrollbarAdapter(list), Modifier.matchParentSize())
									if (sideways)
										ScrollbarBelow(rememberScrollbarAdapter(across), Modifier.matchParentSize())
								}
						}
				}
			}
			// What a drop where files are dragged would make of them.
			hint.shown?.let { DropLine(wordsOf(it, exploring)) }
			ContextMenuHost(menus, onEvent)
		}
	}
}

/** A line under the listing saying what files dragged over the browser would be made, dropped where they are. */
@Composable
private fun DropLine(words: String) {
	val palette = LocalPalette.current

	Text(
		words,
		style = LocalType.current.body.copy(color = palette.onAccent),
		modifier =
			Modifier
				.fillMaxWidth()
				.background(palette.accent)
				.padding(horizontal = Space.l, vertical = Space.s)
				.testTag("drop-hint"),
	)
}

/**
 * The gallery: its room measured as it is laid out, and the size at which a tile would fill it reported, for the zoom to
 * go no further ([BrowserEvent.Measured]); its tiles drawn at the zoom chosen, but never larger than fits, so a window
 * made smaller makes them smaller at once. At its largest, one tile at a time, as wide as the gallery.
 */
@Composable
private fun Gallery(
	state: BrowserState,
	grid: LazyGridState,
	nowhere: Modifier,
	onEvent: (BrowserEvent) -> Unit,
	tile: @Composable (Row, Float) -> Unit,
) {
	BoxWithConstraints(Modifier.fillMaxSize()) {
		val wide = (maxWidth - Dims.tilePad * 2) / Dims.tile
		val high = (maxHeight - Dims.tilePad * 2) / Dims.tileHeight
		val whole = minOf(wide, high).coerceAtLeast(0.1f)
		val scale = scaleOf(state.zoom, whole)

		LaunchedEffect(whole) { onEvent(BrowserEvent.Measured(whole)) }

		// A notch of the wheel moves the gallery a line of tiles, however large they are drawn.
		Box(
			Modifier.fillMaxSize().wheelScrolled(
				grid,
				Dims.tileHeight * scale + Dims.tileGap,
				lines = 1f,
			),
		) {
			LazyVerticalGrid(
				if (state.zoom == Zoom.Whole || scale >= whole)
					GridCells.Fixed(1)
				else
					GridCells.Adaptive(Dims.tile * scale),
				nowhere.fillMaxSize().padding(Dims.tilePad),
				state = grid,
				horizontalArrangement = Arrangement.spacedBy(Dims.tileGap),
				verticalArrangement = Arrangement.spacedBy(Dims.tileGap),
				userScrollEnabled = false,
			) {
				val rows = state.rows

				items(
					rows.size,
					key = rows::idAt,
					contentType = { "tile" },
				) { tile(rows[it], scale) }
			}
			ScrollbarBeside(rememberScrollbarAdapter(grid), Modifier.matchParentSize())
		}
	}
}

/**
 * Ctrl and the mouse's wheel reported as steps of zoom: up larger, down smaller, a step a notch. A touchpad's small
 * scrolls are added up until they make a notch. Seen before the list inside sees it, which then does not scroll.
 */
fun Modifier.zoomedByWheel(onZoom: (Int) -> Unit): Modifier =
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
internal fun TextStyle.times(factor: Float): TextStyle =
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
	onEvent: (BrowserEvent) -> Unit,
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
		{ onEvent(BrowserEvent.RenameTyped(it)) },
		modifier.testTag("rename"),
		height = with(LocalDensity.current) { (style.lineHeight * 1.5f).toDp() },
		focus = focus,
		selecting = {
			val dot = it.lastIndexOf('.').takeIf { dot -> dot > 0 && !row.isFolder }

			TextRange(0, dot ?: it.length)
		},
		onLeave = { onEvent(BrowserEvent.Renamed) },
		onKey = {
			when {
				it.type != KeyEventType.KeyDown -> false
				// What the field says first, then the keyboard back: leaving the field then gives nothing new.
				it.isEnter ->
					true.also {
						onEvent(BrowserEvent.Renamed)
						stops.listing.requestFocus()
					}
				it.key == Key.Escape ->
					true.also {
						onEvent(BrowserEvent.RenameCancelled)
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
	snapshotFlow { layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
	// What is in sight can be learnt while the window is being laid out (its scrollbar reads it then), which is no time
	// to scroll: the scroll waits for the next frame, and goes by what is in sight by then.
	withFrameNanos {}

	val info = layoutInfo
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
	snapshotFlow { layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
	// What is in sight can be learnt while the window is being laid out (its scrollbar reads it then), which is no time
	// to scroll: the scroll waits for the next frame, and goes by what is in sight by then.
	withFrameNanos {}

	val info = layoutInfo
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
private fun Modifier.pressedNowhere(
	onPress: (adding: Boolean) -> Unit,
	onBand: (Rect?) -> Unit,
): Modifier =
	pointerInput(Unit) {
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Main)
				val pressed =
					event.changes.firstOrNull {
						it.pressed && !it.previousPressed && !it.isConsumed
					}

				if (pressed == null || !event.buttons.isPrimaryPressed)
					continue

				onPress(
					event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed,
				)

				// Held and dragged from there, it draws out a rectangle, which chooses the rows it takes in, until
				// the button is let go. Not before it has moved a little way: a click is not a rectangle.
				val from = pressed.position
				var drawn = false

				while (true) {
					val moved =
						awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == pressed.id }
							?: break

					if (!moved.pressed)
						break

					if (drawn ||
						(moved.position - from).getDistance() >= viewConfiguration.touchSlop / 2
					) {
						drawn = true
						moved.consume()
						onBand(Rect(from, moved.position).normalized())
					}
				}

				if (drawn)
					onBand(null)
			}
		}
	}

/** [this] with its corners the right way round, whichever way it was dragged out. */
private fun Rect.normalized(): Rect =
	Rect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

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
private fun Modifier.pressing(onPress: (Press) -> Unit, onRelease: () -> Unit): Modifier =
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
				waitForUpOrCancellation()?.let {
					it.consume()
					onRelease()
				}
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
 * The headings over a list's columns, as the window's look for what it shows has them (which, in what order, aligned
 * how), each as wide as its column ([layout]) and scrolled across with the rows ([across]). Clicked, a heading orders
 * the list by its column, the other way round when clicked again. A search's results are best first until a heading is
 * clicked, and a column they cannot be ordered by (too many to read every one's size or date) is drawn faint and does
 * nothing. The headings' menu shows and hides columns, and aligns the one pressed.
 */
@Composable
private fun ColumnHeadings(
	state: BrowserState,
	layout: ColumnLayout,
	wide: Dp,
	across: ScrollState,
	menus: ContextMenus<BrowserEvent>,
	onEvent: (BrowserEvent) -> Unit,
) {
	val type = LocalType.current

	Box(
		Modifier
			.fillMaxWidth()
			.height(type.row)
			.clipToBounds()
			.contextMenu(menus) { headingsMenuOf(state, null) },
	) {
		Row(
			Modifier
				.wrapContentWidth(Alignment.Start, unbounded = true)
				.width(wide)
				.fillMaxHeight()
				// Read as the headings are placed, so scrolling moves them without composing them again.
				.offset { IntOffset(-across.value, 0) }
				// Each heading over its column: as far in as the rows' values are.
				.padding(
					start = LIST_INSET + Dims.edge,
					end =
						LIST_INSET + Dims.scrollbarRoom + Dims.edge,
				),
			verticalAlignment = Alignment.CenterVertically,
		) {
			for (heading in state.columns.shown)
				HeaderCell(
					heading,
					state.kind.labelOf(heading.id),
					state.look.order.sort,
					Modifier.width(layout.of(heading.id)),
					align = heading.aligned,
					enabled = (state.listing as? Listing.Found)?.orders(heading.id) != false,
					menu = Modifier.contextMenu(menus) { headingsMenuOf(state, heading.id) },
					onEvent,
				)
		}
	}
}

/**
 * Over a gallery's tiles, which have no columns, the same choices of order in a row after "Sort by": every column the
 * listing offers but where an entry is.
 */
@Composable
private fun SortHeadings(
	state: BrowserState,
	menus: ContextMenus<BrowserEvent>,
	onEvent: (BrowserEvent) -> Unit,
) {
	val type = LocalType.current

	Row(
		Modifier
			.fillMaxWidth()
			.height(type.row)
			.contextMenu(menus) { headingsMenuOf(state, null) }
			.padding(horizontal = Dims.tilePad),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		Text("Sort by", style = type.muted, maxLines = 1)
		for (column in state.kind.offered.filter { it != ColumnId.Location })
			HeaderCell(
				ColumnSetting(column),
				state.kind.labelOf(column),
				state.look.order.sort,
				Modifier,
				align = Align.Start,
				enabled = (state.listing as? Listing.Found)?.orders(column) != false,
				menu = Modifier.contextMenu(menus) { headingsMenuOf(state, column) },
				onEvent,
			)
	}
}

/**
 * How wide each column shown is, in its heading and in every row ([of]), and how wide the rows are ([row]). Made only by
 * [layoutOf], from the rows' room and what each column needs.
 */
@Immutable
private class ColumnLayout(
	private val widths: Map<ColumnId, Dp>,
	val row: Dp,
) {
	fun of(column: ColumnId): Dp = widths.getValue(column)
}

/**
 * [columns] laid out in [room]: a date's as wide as its date and time ([DateWidths.whole]); the rest at least their
 * least in [character]s (the name's with what is before it, [beforeName]), and the room left shared out among them by
 * their shares. Where their least is more than the room, the rows are as wide as that, and scroll across.
 */
private fun layoutOf(
	columns: List<ColumnSetting>,
	room: Dp,
	dates: DateWidths,
	character: Dp,
	beforeName: Dp,
): ColumnLayout {
	fun leastOf(column: ColumnId, width: Width.Share): Dp =
		character * width.least + (if (column == ColumnId.Name) beforeName else 0.dp) +
			Dims.cell * 2

	val dated = dates.whole + Dims.cell * 2
	val fixed = columns.map { it.id }.filter { it.width == Width.Date }
	val shared =
		columns.map { it.id }.mapNotNull { id ->
			(id.width as? Width.Share)?.let {
				id to
					it
			}
		}

	val least =
		shared.fold(dated * fixed.size) { sum, (id, width) ->
			sum +
				leastOf(id, width)
		}
	val left = (room - Dims.edge * 2 - least).coerceAtLeast(0.dp)
	val shares =
		shared.sumOf { (_, width) -> width.of.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f

	return ColumnLayout(
		fixed.associateWith { dated } +
			shared.associate { (id, width) ->
				id to leastOf(id, width) + left * (width.of / shares)
			},
		maxOf(room, least + Dims.edge * 2),
	)
}

@Composable
private fun HeaderCell(
	heading: ColumnSetting,
	label: String,
	sort: Sort?,
	modifier: Modifier,
	align: Align,
	enabled: Boolean,
	menu: Modifier,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val sorted = sort != null && sort.by == heading.id

	Row(
		modifier
			.fillMaxHeight()
			.then(menu)
			.background(
				if (felt.hovered && enabled)
					palette.hover
				else
					Color.Transparent,
			).focusProperties { canFocus = false }
			.clickable(source, indication = null, enabled = enabled) {
				onEvent(BrowserEvent.Sorted(heading.id))
			}.testTag("column:$label")
			.padding(horizontal = Dims.cell),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = arrangementOf(align),
	) {
		Text(
			label,
			style =
				type.muted.copy(
					fontWeight = FontWeight.Medium,
					color =
						when {
							sorted -> palette.text
							enabled -> palette.textMuted
							else -> palette.textFaint
						},
				),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		if (sorted)
			GlyphIcon(
				if (sort!!.ascending)
					Glyph.SortUp
				else
					Glyph.SortDown,
				palette.textMuted,
				type.icon * 0.75f,
			)
	}
}

/**
 * The menu of the listing's headings: each column it offers but the name's, shown or not; and, of the heading pressed
 * ([column]), how its values sit.
 */
private fun headingsMenuOf(state: BrowserState, column: ColumnId?): Menu<BrowserEvent> {
	val columns = state.columns
	val kind = state.kind

	return menu {
		columns.settings
			.filter { it.id != ColumnId.Name }
			.forEach { setting ->
				val shown = setting.shown

				check(
					kind.labelOf(setting.id),
					shown,
					BrowserEvent.ShowedColumn(setting.id, !shown),
					alternate = BrowserEvent.ShowedColumn(setting.id, !shown),
				)
			}
		columns.settings.firstOrNull { it.id == column }?.let { setting ->
			separator()
			submenu("Align “${kind.labelOf(setting.id)}”") {
				listOf(
					Align.Start to "Left",
					Align.Center to "Centre",
					Align.End to "Right",
				).forEach { (align, label) ->
					radio(label, setting.aligned == align, BrowserEvent.AlignedColumn(setting.id, align))
				}
			}
		}
	}
}

/** Where a row of [align] puts what is in it. */
private fun arrangementOf(align: Align): Arrangement.Horizontal =
	when (align) {
		Align.Start -> Arrangement.Start
		Align.Center -> Arrangement.Center
		Align.End -> Arrangement.End
	}

private fun textAlignOf(align: Align): TextAlign =
	when (align) {
		Align.Start -> TextAlign.Start
		Align.Center -> TextAlign.Center
		Align.End -> TextAlign.End
	}

/**
 * Where [row] is, as the listing's location column says it: a search's result's folder, a trashed entry's folder before
 * it was trashed, from `~` where it is under [home]; an entry of a place, the folders it sits under there.
 */
private fun whereOf(row: Row, home: Path): String =
	when (val item = row.item) {
		is Item.InTrash -> folderOf(item.trashed.original, home)
		is Item.OnDisk -> folderOf(item.path, home)
		is Item.InPlace ->
			Part.Parent
				.of(item.key)
				.trimStart('/')
				.ifEmpty { "/" }
	}

/** The folder [path] is in, from `~` where it is under [home]. */
internal fun folderOf(path: Path, home: Path): String {
	val folder = path.parent ?: return "/"

	return if (folder.startsWith(home))
		"~" + folder.toString().removePrefix(home.toString())
	else
		folder.toString()
}

/**
 * How wide each part of the listing's dates is drawn, as [DateFormat] writes them: each field (the day, the month's
 * name, the hour) as wide as the widest it writes, of a day of each month at hours that write widest, so that the same
 * field of every row's date lines up; the words between them as wide as they are; and the room between a date and its
 * time ([gap]).
 */
@Immutable
data class DateWidths(
	val date: List<Dp>,
	val gap: Dp,
	val time: List<Dp>,
) {
	val whole: Dp get() = (date + gap + time).fold(0.dp) { sum, width -> sum + width }

	/** These, drawn [factor] times as large. */
	operator fun times(factor: Float): DateWidths =
		DateWidths(
			date.map { it * factor },
			gap * factor,
			time.map { it * factor },
		)
}

@Composable
private fun rememberDateWidths(format: DateFormat, style: TextStyle): DateWidths {
	val measurer = rememberTextMeasurer()
	val density = LocalDensity.current

	return remember(format, style, density) {
		val zone = java.time.ZoneId.systemDefault()

		// A day of each month, with one digit and with two, at hours with one and with two, morning and afternoon.
		val samples =
			(1..12).flatMap { month ->
				listOf(1 to 9, 28 to 12, 28 to 22, 1 to 0).map { (day, hour) ->
					java.time.LocalDateTime
						.of(2026, month, day, hour, 58)
						.atZone(zone)
						.toInstant()
				}
			}

		val tabular = style.tabular()

		fun widthOf(text: String): Dp =
			with(density) {
				measurer
					.measure(text, tabular)
					.size.width
					.toDp()
			}

		// Words are measured between two marks, so that a space at either end is counted as the room it is.
		fun widthsOf(parts: List<DatePart>): List<Dp> =
			parts.map { part ->
				when (part) {
					is DatePart.Field -> samples.maxOf { widthOf(format.write(part, it, zone)) } + 1.dp
					is DatePart.Words -> widthOf("|${part.text}|") - widthOf("||")
				}
			}

		DateWidths(widthsOf(format.dateParts), Space.m, widthsOf(format.timeParts))
	}
}

/** How wide a character of [style]'s text is, on average: what a column's least width is counted in. */
@Composable
private fun rememberCharacterWidth(style: TextStyle): Dp {
	val measurer = rememberTextMeasurer()
	val density = LocalDensity.current

	return remember(style, density) {
		val sample = "abcdefghijklmnopqrstuvwxyz0123456789"

		with(density) {
			measurer
				.measure(sample, style)
				.size.width
				.toDp()
		} / sample.length
	}
}

@Composable
private fun EntryRow(
	row: Row,
	columns: List<ColumnSetting>,
	home: Path,
	layout: ColumnLayout,
	dates: DateWidths,
	chosen: Boolean,
	cursor: Boolean,
	zoom: Float,
	renamed: String?,
	stops: Stops,
	onPress: (Press) -> Unit,
	onRelease: () -> Unit,
	onEvent: (BrowserEvent) -> Unit,
	modifier: Modifier = Modifier,
	nameMenu: Modifier = Modifier,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val rows = LocalRows.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()
	val pressed by rememberUpdatedState(onPress)
	val shape = RoundedCornerShape(Radius.medium)
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
			.height(rowHeightOf(rows, type) * zoom)
			.background(background, shape)
			.ring(cursor, palette.focus, outset = 0.dp, width = 1.5.dp)
			.hoverable(hover)
			.pressing({ pressed(it) }, onRelease)
			.semantics { selected = chosen }
			.testTag("row:${row.name}")
			.padding(horizontal = Dims.edge),
		verticalAlignment = Alignment.CenterVertically,
	) {
		for (column in columns) {
			val cell = Modifier.width(layout.of(column.id)).padding(horizontal = Dims.cell)

			when (column.id) {
				// The row's menu over its name, and the space after it in the name's column.
				ColumnId.Name ->
					Row(
						Modifier
							.width(layout.of(column.id))
							.fillMaxHeight()
							.then(nameMenu)
							.padding(horizontal = Dims.cell),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = arrangementOf(column.aligned),
					) {
						GlyphIcon(
							if (row.isFolder) Glyph.Folder else Glyph.File,
							palette.text,
							rowIconOf(rows, type) * zoom,
							category = row.category,
						)
						Spacer(Modifier.width((Space.s + Space.xxs) * zoom))
						NameOf(
							row,
							renamed,
							body,
							stops,
							onEvent,
							Modifier.weight(
								1f,
								fill =
									renamed != null,
							),
						)
					}
				ColumnId.Modified ->
					DateCell(
						row.modified,
						null,
						column.aligned,
						dates,
						muted,
						cell,
					)
				ColumnId.Deleted ->
					DateCell(
						row.deleted,
						null,
						column.aligned,
						dates,
						muted,
						cell,
					)
				ColumnId.Created ->
					when (val created = row.created) {
						is Created.At ->
							DateCell(
								created.time,
								null,
								column.aligned,
								dates,
								muted,
								cell,
							)
						// Read behind, those in sight first: blank until it is; a dash where the file system keeps none.
						Created.Unread -> DateCell(null, null, column.aligned, dates, muted, cell)
						Created.Unkept -> DateCell(null, "—", column.aligned, dates, muted, cell)
					}
				ColumnId.Location -> TextCell(whereOf(row, home), column.aligned, muted, cell)
				ColumnId.Type -> TextCell(row.type, column.aligned, muted, cell)
				ColumnId.Size ->
					TextCell(
						row.size?.text.orEmpty(),
						column.aligned,
						muted.tabular(),
						cell,
					)
				ColumnId.Extension ->
					TextCell(
						extensionOf(row.name).orEmpty(),
						column.aligned,
						muted,
						cell,
					)
			}
		}
	}
}

@Composable
private fun RowScope.TextCell(
	text: String,
	align: Align,
	style: TextStyle,
	modifier: Modifier,
) {
	Text(
		text,
		style = style,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		textAlign = textAlignOf(align),
		modifier = modifier,
	)
}

/**
 * A date as a row of a table: each of its parts in the width every row's same part fits ([widths]), a number at the end
 * of its width and a word at its start, its digits each as wide as the next, so that every row's day, month, year, hour
 * and minute line up under each other whatever the font; the date, then room, then its time. The whole sits as [align]
 * says. Nothing where there is no date, or [instead] where something stands for one.
 */
@Composable
private fun RowScope.DateCell(
	instant: Instant?,
	instead: String?,
	align: Align,
	widths: DateWidths,
	style: TextStyle,
	modifier: Modifier,
) {
	val format = LocalDateFormat.current
	val tabular = style.tabular()

	@Composable
	fun Parts(parts: List<DatePart>, widths: List<Dp>, at: Instant) =
		parts.forEachIndexed { index, part ->
			Text(
				format.write(part, at),
				style = tabular,
				maxLines = 1,
				softWrap = false,
				textAlign =
					if ((part as? DatePart.Field)?.numeric ==
						true
					)
						TextAlign.End
					else
						TextAlign.Start,
				modifier = Modifier.width(widths[index]),
			)
		}

	Row(
		modifier,
		horizontalArrangement = arrangementOf(align),
		verticalAlignment = Alignment.CenterVertically,
	) {
		when {
			instant != null -> {
				Parts(format.dateParts, widths.date, instant)
				Spacer(Modifier.width(widths.gap))
				Parts(format.timeParts, widths.time, instant)
			}
			instead != null -> Text(instead, style = tabular, maxLines = 1)
		}
	}
}

/** One entry as a tile of the gallery: its picture, or its icon drawn large, and its name under it. */
@Composable
private fun Tile(
	row: Row,
	path: Path?,
	chosen: Boolean,
	cursor: Boolean,
	zoom: Float,
	renamed: String?,
	stops: Stops,
	onPress: (Press) -> Unit,
	onRelease: () -> Unit,
	onEvent: (BrowserEvent) -> Unit,
	modifier: Modifier = Modifier,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()
	val pressed by rememberUpdatedState(onPress)
	val shape = RoundedCornerShape(Radius.large)

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
			).ring(cursor, palette.focus, corner = Radius.large, outset = 0.dp, width = 1.5.dp)
			.hoverable(hover)
			.pressing({ pressed(it) }, onRelease)
			.semantics { selected = chosen }
			.testTag("row:${row.name}")
			.padding(Space.s),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		// The name is drawn larger with the tile only so far, a size people read; the picture has the rest.
		val name = type.body.times(minOf(zoom, 1.5f))

		Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
			Picture(row, path, zoom)
		}
		Spacer(Modifier.height(Space.xs))
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
private fun Picture(row: Row, path: Path?, zoom: Float) {
	val palette = LocalPalette.current
	val thumbnails = LocalThumbnails.current
	// An entry of a place is nowhere on disk to be drawn from: its icon alone.
	val drawable = path != null && !row.isFolder && Thumbnails.drawable(row.name)

	BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		// Made for the longer side of its room, so that it is as sharp as it is drawn.
		val side = maxOf(constraints.maxWidth, constraints.maxHeight)

		// The picture is its file's: a tile shown for another file (a row of another listing known by the same identity,
		// as a search's first result and the next search's are) starts without one, rather than keeping the last file's.
		// For the same file, the last picture stays while one for a new size or a new version of it is made.
		val picture by key(path) {
			produceState<ImageBitmap?>(null, row.modified, Thumbnails.sideFor(side)) {
				value =
					if (drawable)
						thumbnails.of(path, row.modified, side)
					else
						null
			}
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

/** What the listing says where it has no rows to show, and why: a [glyph] for what is so, its [title], and [detail]. */
@Composable
private fun Message(glyph: Glyph, title: String, detail: String?) =
	EmptyState(glyph, title, detail, Modifier.fillMaxSize())

// ---- the footer: the name, the filter, the buttons -----------------------------------------------------------

@Composable
private fun Footer(state: BrowserState, stops: Stops, onEvent: (BrowserEvent) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val saving = state.choosing is Choosing.SaveAs

	Column(
		Modifier
			.fillMaxWidth()
			.background(palette.window)
			.padding(horizontal = Space.l, vertical = Space.m),
		verticalArrangement = Arrangement.spacedBy(Space.m),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.m),
		) {
			Text(
				if (state.choosing is Choosing.Folder)
					"Folder"
				else
					"Name",
				style = type.body,
			)
			Field(
				state.nameField,
				{ onEvent(BrowserEvent.Named(it)) },
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
						onEvent(BrowserEvent.Accepted)

					accepting
				},
			)
			// The asking application's filters, where it offers a choice of them; a folder picker shows no files.
			if (state.choosing !is Choosing.Folder && state.filters.size > 1)
				Select(
					state.filters,
					state.arrangement.filter,
					{ it.label },
					Modifier.width(240.dp).testTag("filter"),
				) { onEvent(BrowserEvent.Filtered(it)) }
		}
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				statusOf(state),
				style = type.muted,
				modifier = Modifier.weight(1f).testTag("status"),
			)
			ButtonRow(
				Act(state.accept.label, "accept", state.accept.enabled) {
					onEvent(BrowserEvent.Accepted)
				},
				Modifier.width(IntrinsicSize.Max),
				secondary = listOf(Act("Cancel", "cancel") { onEvent(BrowserEvent.Cancelled) }),
			)
		}
	}
}

/** An explorer's foot: the status line alone, as a file manager's is. */
@Composable
private fun StatusBar(state: BrowserState) {
	val type = LocalType.current

	Row(
		Modifier
			.fillMaxWidth()
			.background(LocalPalette.current.window)
			.height(type.row)
			.padding(horizontal = Space.l),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			statusOf(state),
			style = type.muted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f).testTag("status"),
		)
	}
}

/**
 * How many rows are shown (of how many, where some are left out) and how many chosen, as the status line says it; of
 * a search's results, what they are ordered by, where it is being worked out still or cannot be had of so many.
 */
private fun statusOf(state: BrowserState): String {
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
			when (val column = it.pending(state.arrangement.order)) {
				null -> null
				else -> "ordering by ${state.kind.labelOf(column).lowercase()}…"
			}
		}

	val counted =
		if (shown == entries)
			items
		else
			"$shown of $items"

	// The watched commands under way, by what each is called: "Extract here…".
	val running =
		state.running
			.takeIf { it.isNotEmpty() }
			?.joinToString(", ", postfix = "…") { it.task.label.removeSuffix("…") }

	return listOfNotNull(
		if (state.listing is Listing.Reading)
			"Reading…"
		else
			counted,
		"$chosen selected".takeIf {
			chosen > 0 && state.listing !is Listing.Reading
		},
		ordering.takeIf { state.listing !is Listing.Reading },
		running,
	).joinToString("  ·  ")
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
		is Notice.ConfirmErase ->
			Words(
				"Delete ${erasedOf(notice.erasing)} permanently?",
				listOfNotNull(
					notice.noTrash?.let { "There is no trash to move it to: $it." },
					"It can't be put back once it is deleted.",
				).joinToString(" "),
				"Delete permanently",
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
		is Notice.Asking -> Words(notice.prompt, "", "OK", "Cancel")
		is Notice.ConfirmAction -> Words(notice.label, notice.question, notice.label, "Cancel")
	}

/** How long a command runs before it is shown as running: one that is done at once shows nothing. */
private const val WORK_SHOWN_AFTER = 350L

/**
 * A command under way, over the browser, once it has run for a moment: what it is, that it is running, the last lines
 * it wrote, and a button that stops it (as Escape does). It goes when the command ends. The browser behind it is not
 * waiting on the command, which runs elsewhere; it is only not to be changed under it.
 */
@Composable
private fun WorkLayer(work: Work, onEvent: (BrowserEvent) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val button = remember { FocusRequester() }
	var shown by remember(work.task) { mutableStateOf(false) }

	LaunchedEffect(work.task) {
		delay(WORK_SHOWN_AFTER)
		shown = true
	}

	if (!shown)
		return

	Modal(
		work.task.label.removeSuffix("…") + "…",
		onDismiss = { onEvent(BrowserEvent.Stopped(work.task)) },
		outside = false,
		tag = "work",
		focus = button,
		leading = { Spinner(palette.accent, type.icon) },
		buttons = {
			ButtonRow(
				null,
				secondary =
					listOf(
						Act(if (work.stopping) "Stopping…" else "Cancel", "stop", !work.stopping, button) {
							onEvent(BrowserEvent.Stopped(work.task))
						},
					),
			)
		},
	) {
		// The last lines it wrote, the latest last, in the room they are given.
		if (work.said.isNotEmpty())
			Text(
				work.said.takeLast(WORK_LINES).joinToString("\n"),
				style = type.mono,
				maxLines = WORK_LINES,
				overflow = TextOverflow.Ellipsis,
				modifier =
					Modifier
						.fillMaxWidth()
						.background(palette.sunken, RoundedCornerShape(Radius.medium))
						.padding(Space.m)
						.testTag("work-said"),
			)
	}
}

/** How many of a command's last lines are shown while it runs. */
private const val WORK_LINES = 8

/**
 * A notice, over the picker and in front of the keyboard: it takes the keyboard to its first button, Escape puts it
 * away, and nothing behind it can be reached until it is answered.
 */
@Composable
private fun NoticeLayer(notice: Notice, onEvent: (BrowserEvent) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val words = wordsOf(notice)
	val first = remember { FocusRequester() }
	val asks = words.no != null
	// What cannot be undone starts with the keyboard on the way out of it, so Enter alone does nothing.
	val wary = notice is Notice.ConfirmErase
	val trouble = notice is Notice.Failed || wary

	Modal(
		words.title,
		onDismiss = { onEvent(BrowserEvent.Dismissed) },
		width =
			if (notice is Notice.Failed)
				ModalWidth.Large
			else
				ModalWidth.Small,
		tag = "notice",
		focus = first,
		leading =
			trouble.takeIf { it }?.let {
				{
					GlyphIcon(
						Glyph.Warning,
						palette.dangerText,
						type.icon * 1.2f,
					)
				}
			},
		buttons = {
			ButtonRow(
				Act(
					words.yes,
					"yes",
					focus = first.takeIf { notice !is Notice.Asking && !wary },
				) {
					onEvent(
						if (asks)
							BrowserEvent.Confirmed
						else
							BrowserEvent.Dismissed,
					)
				},
				secondary =
					listOfNotNull(
						words.no?.let {
							Act(it, "no", focus = first.takeIf { wary }) { onEvent(BrowserEvent.Dismissed) }
						},
					),
				// What failed, and why, copied whole, to be pasted where it is wanted.
				aside =
					listOfNotNull(
						(notice as? Notice.Failed)?.let {
							Act(
								"Copy",
								"copy",
							) { onEvent(BrowserEvent.CopiedText("${it.doing}\n\n${it.why}")) }
						},
					),
				danger = wary,
			)
		},
	) {
		// Why something failed can be all a command wrote: in a box of its own, which scrolls, the end in sight.
		if (notice is Notice.Failed && words.detail.lines().size > 2) {
			val scroll = rememberScrollState()

			LaunchedEffect(words.detail) { scroll.scrollTo(scroll.maxValue) }
			ScrollArea(
				Modifier
					.fillMaxWidth()
					.heightIn(max = 280.dp)
					.background(palette.sunken, RoundedCornerShape(Radius.medium)),
				state = scroll,
				padding = PaddingValues(Space.m),
			) {
				SelectionContainer {
					Text(
						words.detail,
						style = type.mono,
						modifier = Modifier.fillMaxWidth().testTag("failure"),
					)
				}
			}
		} else if (words.detail.isNotEmpty())
			SelectionContainer {
				Text(words.detail, style = type.body, modifier = Modifier.testTag("notice-detail"))
			}
		// A line an action asks for: typed here, Enter giving it as the button does.
		if (notice is Notice.Asking)
			Field(
				notice.text,
				{ onEvent(BrowserEvent.AnswerTyped(it)) },
				Modifier.fillMaxWidth().testTag("answer"),
				focus = first,
				onKey = {
					(it.type == KeyEventType.KeyDown && it.isEnter).also { entered ->
						if (entered)
							onEvent(BrowserEvent.Confirmed)
					}
				},
			)
	}
}

/** The rows the listing is laid out inside of, from its edges. */
private val LIST_INSET = Space.xs
