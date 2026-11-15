package prism.browser

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import prism.preferences.Held
import prism.window.WindowLocals

/*
 * Menus, drawn (menu.kt has them as data). One component, [MenuPopup], draws any menu wherever it opens; the rest
 * open it:
 *
 * - [ContextMenus], [Modifier.contextMenu] and [ContextMenuHost] together: the right button pressed over anything
 *   with `contextMenu` opens the menu it gives at the pointer, and the host draws it. A part of the view has one
 *   host, so one menu is open at a time, and anything in that part can give a menu of its own, worked out as it opens.
 * - [MenuButton]: a toolbar button whose menu opens under it.
 *
 * A menu is worked by the pointer (hovering an entry lights it and opens its submenu; a click chooses) and by the
 * keyboard, which it takes while it is open: Up and Down light an entry, Right or Enter open a submenu, Left or
 * Escape close one, Enter or Space choose, Escape closes the menu. Choosing an item closes the whole menu, except a
 * box ([Mark.Check]), which is ticked or unticked and leaves it open, showing what it was set to. An item chosen with
 * Ctrl held does its alternate, where it has one.
 *
 * What is drawn before an item's label says which of three things it is, the same in every menu: nothing, before
 * something done; a square box, ticked or empty, before something on or off; a ring, with a dot in it or empty,
 * before one of several of which one is chosen. The box and the ring are the ones a settings page draws ([TickBox],
 * [Radio]). The one thing to do in a menu of things to set is drawn as a button across it ([Mark.Button]).
 */

/** Where a menu opens. */
@Immutable
sealed interface MenuAnchor {
	/** At a point of the window, as a context menu opens at the pointer. */
	data class At(
		val point: IntOffset,
	) : MenuAnchor

	/** Below what it is drawn in (a button), or above it where there is no room below. */
	data object Below : MenuAnchor

	/** Beside what it is drawn in (a submenu's entry): to the right, or to the left where there is no room. */
	data object Beside : MenuAnchor
}

/**
 * A menu of [menu] open where [anchor] says, giving what is chosen to [onAction]; [onClose] when it closes.
 *
 * Every level of it (the menu, and each submenu open beside its entry) is drawn in one layer over the whole window,
 * so the pointer is followed from one level to another: an entry of the menu lit while a submenu is open closes that
 * submenu, which a submenu in a layer of its own would keep from ever being told. A press anywhere outside every level
 * closes the whole menu.
 */
@Composable
fun <A> MenuPopup(
	menu: Menu<A>,
	anchor: MenuAnchor,
	onAction: (A) -> Unit,
	onClose: () -> Unit,
) {
	// What the menu is drawn in (a button, for a menu under it), found from a mark of no size left in it.
	var owner by remember { mutableStateOf<LayoutCoordinates?>(null) }
	var layer by remember { mutableStateOf<LayoutCoordinates?>(null) }

	Box(Modifier.onGloballyPositioned { owner = it.parentLayoutCoordinates })
	Popup(
		popupPositionProvider = WholeWindow,
		onDismissRequest = onClose,
		properties = PopupProperties(focusable = true, dismissOnBackPress = false),
	) {
		WindowLocals {
			Box(
				Modifier
					.fillMaxSize()
					.onGloballyPositioned { layer = it }
					.pointerInput(Unit) {
						awaitPointerEventScope {
							while (true) {
								val event = awaitPointerEvent()

								// A press no level took is a press outside the menu.
								if (event.type == PointerEventType.Press && event.changes.none { it.isConsumed })
									onClose()
							}
						}
					},
			) {
				val from =
					layer?.let { over ->
						when (anchor) {
							is MenuAnchor.At ->
								over
									.windowToLocal(
										Offset(anchor.point.x.toFloat(), anchor.point.y.toFloat()),
									).let { IntRect(it.round(), IntSize.Zero) }
							MenuAnchor.Below, MenuAnchor.Beside ->
								owner?.takeIf { it.isAttached }?.let {
									val corner = over.windowToLocal(it.positionInWindow()).round()

									IntRect(corner, it.size)
								}
						}
					}

				from?.let {
					// A menu's text is as large as the user wants menus' to be, whatever the window's is.
					CompositionLocalProvider(LocalType provides LocalTypes.current.menus) {
						MenuLevel(menu, anchor, it, onAction, onClose, onBack = onClose, startLit = false)
					}
				}
			}
		}
	}
}

/** The whole window, for the layer a menu's levels are drawn in. */
private object WholeWindow : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset = IntOffset.Zero
}

/**
 * One level of a menu: the menu itself, or a submenu of it, placed by [anchor] about [from] (a rectangle of the
 * menu's layer: the point it opens at, the button it opens under, the entry it opens beside). [onClose] closes every
 * level; [onBack] closes this one (the menu itself, at the first level). [startLit] lights its first entry, for a
 * submenu opened from the keyboard.
 */
@Composable
private fun <A> MenuLevel(
	menu: Menu<A>,
	anchor: MenuAnchor,
	from: IntRect,
	onAction: (A) -> Unit,
	onClose: () -> Unit,
	onBack: () -> Unit,
	startLit: Boolean,
) {
	val gap = with(LocalDensity.current) { Space.xs.roundToPx() }
	// The submenu open beside one of this level's entries, drawn after it: over it, where they overlap.
	var beside by remember { mutableStateOf<Beside<A>?>(null) }

	Layout(
		content = {
			MenuPanel(
				menu,
				onAction,
				onClose,
				onBack,
				sub = anchor == MenuAnchor.Beside,
				startLit,
				onBeside = { beside = it },
			)
		},
	) { measurables, constraints ->
		val panel = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
		val window = IntSize(constraints.maxWidth, constraints.maxHeight)

		layout(window.width, window.height) {
			panel.place(placeOf(anchor, from, gap, IntSize(panel.width, panel.height), window))
		}
	}

	beside?.let {
		MenuLevel(
			it.menu,
			MenuAnchor.Beside,
			it.from,
			onAction,
			onClose,
			onBack = it.onBack,
			startLit = it.byKey,
		)
	}
}

/** A submenu open beside its entry, which is at [from] in the menu's layer. */
@Immutable
private data class Beside<A>(
	val menu: Menu<A>,
	val from: IntRect,
	val byKey: Boolean,
	val onBack: () -> Unit,
)

/** A submenu open, and whether it was opened from the keyboard, which lights its first entry. */
@Immutable
private data class Opening(
	val index: Int,
	val byKey: Boolean,
)

@Composable
private fun <A> MenuPanel(
	menu: Menu<A>,
	onAction: (A) -> Unit,
	onClose: () -> Unit,
	onBack: () -> Unit,
	sub: Boolean,
	startLit: Boolean,
	onBeside: (Beside<A>?) -> Unit,
) {
	val palette = LocalPalette.current
	val shape = RoundedCornerShape(Radius.large)
	val focus = remember { FocusRequester() }
	val choosable = menu.entries.indices.filter { menu.entries[it].choosable }

	var lit by remember {
		mutableIntStateOf(
			if (startLit)
				choosable.firstOrNull() ?: -1
			else
				-1,
		)
	}

	var opening by remember { mutableStateOf<Opening?>(null) }
	// Where each submenu's entry is, in the menu's layer, for its submenu to open beside.
	val places = remember { mutableMapOf<Int, IntRect>() }
	val beside by rememberUpdatedState(onBeside)

	// The submenu open is this level's to say, and the level's to draw, beside the entry it belongs to.
	LaunchedEffect(opening, menu) {
		val open = opening
		val entry = open?.let { menu.entries.getOrNull(it.index) as? MenuEntry.Submenu }
		val from = open?.let { places[it.index] }

		beside(
			if (open == null || entry == null || from == null)
				null
			else
				Beside(entry.menu, from, open.byKey) {
					opening = null
					focus.requestFocus()
				},
		)
	}
	DisposableEffect(Unit) { onDispose { beside(null) } }

	fun choose(index: Int, ctrl: Boolean, byKey: Boolean) {
		when (val entry = menu.entries[index]) {
			is MenuEntry.Item ->
				if (entry.enabled) {
					if (!entry.mark.stays)
						onClose()
					onAction(entry.alternate.takeIf { ctrl } ?: entry.action)
				}
			is MenuEntry.Submenu -> opening = Opening(index, byKey)
			MenuEntry.Separator -> Unit
		}
	}

	fun key(key: KeyEvent): Boolean {
		if (key.type != KeyEventType.KeyDown)
			return false

		when (key.key) {
			Key.DirectionDown -> lit = stepped(choosable, lit, 1)
			Key.DirectionUp -> lit = stepped(choosable, lit, -1)
			Key.MoveHome -> lit = choosable.firstOrNull() ?: -1
			Key.MoveEnd -> lit = choosable.lastOrNull() ?: -1
			Key.DirectionRight ->
				if (menu.entries.getOrNull(lit) is MenuEntry.Submenu)
					opening = Opening(lit, byKey = true)
			Key.DirectionLeft ->
				if (sub)
					onBack()
			Key.Escape -> onBack()
			Key.Enter, Key.NumPadEnter, Key.Spacebar ->
				if (lit >= 0)
					choose(lit, key.isCtrlPressed, byKey = true)
			else -> return false
		}

		return true
	}

	LaunchedEffect(Unit) { focus.requestFocus() }

	Column(
		Modifier
			.shadow(16.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
			.background(palette.overlay, shape)
			.border(1.dp, palette.border, shape)
			.width(IntrinsicSize.Max)
			.widthIn(min = 220.dp)
			.focusRequester(focus)
			.onKeyEvent(::key)
			.focusable()
			.testTag("menu")
			// A press on the menu is the menu's, and not a press outside it, which would close it.
			.pointerInput(Unit) {
				awaitPointerEventScope {
					while (true)
						awaitPointerEvent().changes.forEach {
							if (it.changedToDown())
								it.consume()
						}
				}
			}.padding(Space.xs),
	) {
		menu.entries.forEachIndexed { index, entry ->
			when (entry) {
				MenuEntry.Separator ->
					Divider(
						Modifier.padding(vertical = Space.xs, horizontal = Space.s),
					)
				is MenuEntry.Item -> {
					val hover = {
						lit = index
						opening = null
					}
					val chosen = { ctrl: Boolean -> choose(index, ctrl, byKey = false) }

					if (entry.mark == Mark.Button)
						MenuButtonRow(entry.label, entry.enabled, lit == index, hover, chosen)
					else
						MenuRow(
							entry.label,
							entry.keys,
							entry.mark,
							menu.marked,
							entry.enabled,
							lit = lit == index,
							submenu = false,
							onHover = hover,
							onChoose = chosen,
						)
				}
				is MenuEntry.Submenu ->
					Box(
						Modifier.onGloballyPositioned { row ->
							row.findRootCoordinates().let { root ->
								places[index] =
									IntRect(root.localPositionOf(row, Offset.Zero).round(), row.size)
							}
						},
					) {
						MenuRow(
							entry.label,
							keys = null,
							Mark.None,
							menu.marked,
							enabled = true,
							lit = lit == index,
							submenu = true,
							onHover = {
								lit = index
								opening = Opening(index, byKey = false)
							},
							onChoose = { choose(index, ctrl = false, byKey = false) },
						)
					}
			}
		}
	}
}

/** Whether an entry can be lit and chosen: an item that can be done, or a submenu. */
private val MenuEntry<*>.choosable: Boolean
	get() =
		when (this) {
			is MenuEntry.Item -> enabled
			is MenuEntry.Submenu -> true
			MenuEntry.Separator -> false
		}

/** The choosable entry [by] places from [from], round the ends; the first or last where none is lit yet. */
private fun stepped(choosable: List<Int>, from: Int, by: Int): Int {
	if (choosable.isEmpty())
		return -1

	val at = choosable.indexOf(from)

	return when {
		at < 0 && by > 0 -> choosable.first()
		at < 0 -> choosable.last()
		else -> choosable[(at + by).mod(choosable.size)]
	}
}

/** One entry's row: its mark, its label, its keys, and an arrow for a submenu. */
@Composable
private fun MenuRow(
	label: String,
	keys: String?,
	mark: Mark,
	marked: Boolean,
	enabled: Boolean,
	lit: Boolean,
	submenu: Boolean,
	onHover: () -> Unit,
	onChoose: (ctrl: Boolean) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Row(
		Modifier
			.fillMaxWidth()
			.height(type.row)
			.background(
				if (lit && enabled)
					palette.chosen
				else
					Color.Transparent,
				RoundedCornerShape(Radius.medium),
			).alpha(if (enabled) 1f else 0.4f)
			.worked(enabled, onHover, onChoose)
			.semantics { selected = mark.on ?: false }
			.testTag("menu:$label")
			.padding(horizontal = Space.s + Space.xxs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// Room before the label in every row of a menu that has anything there, so that the labels line up and
		// turning one on or off moves nothing.
		if (marked)
			Box(Modifier.width(type.icon + Space.s), contentAlignment = Alignment.CenterStart) {
				when (mark) {
					Mark.None, Mark.Button -> Unit
					is Mark.Check -> TickBox(mark.on, size = type.icon)
					is Mark.Radio -> Radio(mark.on, size = type.icon)
				}
			}
		Text(
			label,
			style = type.body,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		keys?.let {
			Spacer(Modifier.width(Space.xxl))
			Text(it, style = type.muted.tabular(), maxLines = 1)
		}
		if (submenu) {
			Spacer(Modifier.width(Space.l))
			GlyphIcon(Glyph.Chevron, palette.textMuted, type.icon * 0.7f)
		}
	}
}

/**
 * An item that is a button ([Mark.Button]), across the menu: an edge round its label, as a button has anywhere, so
 * that it reads as the thing to press among things to set. Lit, it is filled as a button the pointer is on is.
 */
@Composable
private fun MenuButtonRow(
	label: String,
	enabled: Boolean,
	lit: Boolean,
	onHover: () -> Unit,
	onChoose: (ctrl: Boolean) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.medium)

	Box(
		Modifier
			.fillMaxWidth()
			.padding(horizontal = Space.xs, vertical = Space.xs)
			.alpha(if (enabled) 1f else 0.4f)
			.height(type.control)
			.background(
				if (lit && enabled)
					palette.chosen
				else
					Color.Transparent,
				shape,
			).border(1.dp, palette.borderStrong, shape)
			.worked(enabled, onHover, onChoose)
			.testTag("menu:$label"),
		contentAlignment = Alignment.Center,
	) {
		Text(label, style = type.button.copy(color = palette.text), maxLines = 1)
	}
}

/**
 * An entry's being worked by the pointer: [onHover] as the pointer comes onto it, and [onChoose], where it is
 * [enabled], as a press on it is let go, told whether Ctrl was held. One that is not is said not to be.
 */
private fun Modifier.worked(
	enabled: Boolean,
	onHover: () -> Unit,
	onChoose: (ctrl: Boolean) -> Unit,
): Modifier =
	composed {
		val source = remember { MutableInteractionSource() }
		val hovered by source.collectIsHoveredAsState()
		val hovering by rememberUpdatedState(onHover)
		val choosing by rememberUpdatedState(onChoose)

		LaunchedEffect(hovered) {
			if (hovered)
				hovering()
		}

		hoverable(source)
			.pointerInput(enabled) {
				awaitEachGesture {
					awaitFirstDown()

					val up = waitForUpOrCancellation()

					if (up != null && enabled) {
						up.consume()
						choosing(currentEvent.keyboardModifiers.isCtrlPressed)
					}
				}
			}.semantics {
				if (!enabled)
					disabled()
			}
	}

/**
 * Where a menu of [size] goes in a window of [window], opened by [anchor] about [from]: where there is room, and
 * never outside.
 */
private fun placeOf(
	anchor: MenuAnchor,
	from: IntRect,
	gap: Int,
	size: IntSize,
	window: IntSize,
): IntOffset =
	when (anchor) {
		is MenuAnchor.At ->
			IntOffset(
				fitted(from.left, size.width, window.width, from.left - size.width),
				fitted(from.top, size.height, window.height, from.top - size.height),
			)
		MenuAnchor.Below ->
			IntOffset(
				fitted(from.left, size.width, window.width, from.right - size.width),
				fitted(from.bottom + gap, size.height, window.height, from.top - gap - size.height),
			)
		MenuAnchor.Beside ->
			IntOffset(
				fitted(from.right, size.width, window.width, from.left - size.width),
				fitted(from.top - gap, size.height, window.height, from.bottom + gap - size.height),
			)
	}

/**
 * Where something [size] long starts along one side of the window: at [start] if it fits there, otherwise at
 * [otherwise] (the far side of what it opens from), and inside the window whichever it is.
 */
private fun fitted(start: Int, size: Int, limit: Int, otherwise: Int): Int {
	val chosen =
		if (start + size <= limit)
			start
		else
			otherwise

	return chosen.coerceIn(0, (limit - size).coerceAtLeast(0))
}

/**
 * A context menu open: what it offers, and the point of the window it opened at. What it offers is asked for each
 * time it is drawn ([menu]), as a button's menu is, so that a box ticked in it, which leaves it open, shows what it
 * was set to. [owner] is what it was opened over, where that can go while it is open (a row deleted, a column
 * hidden by its own heading's menu): it is closed then, since there is nothing left to ask what it offers.
 */
@Stable
class OpenMenu<A>(
	val menu: () -> Menu<A>?,
	val at: IntOffset,
	val owner: Any? = null,
)

/**
 * The context menus of a part of the view: what is open, if anything. [Modifier.contextMenu] opens one, and
 * [ContextMenuHost] draws it. What is open is the view's own, as any menu's being open is.
 */
@Stable
class ContextMenus<A> {
	var open: OpenMenu<A>? by mutableStateOf(null)
		private set

	/**
	 * The menu [menu] gives opened at [at], a point of the window, over [owner]: none, where it gives none, or one
	 * with nothing in it. [menu] only works a menu out, since it is asked again while the menu is open.
	 */
	fun open(at: IntOffset, owner: Any? = null, menu: () -> Menu<A>?) {
		if (menu()?.isEmpty() == false)
			open = OpenMenu(menu, at, owner)
	}

	fun close() {
		open = null
	}

	/** The menu open over [owner] closed, where that is the one open: [owner] is gone. */
	fun closeOf(owner: Any) {
		if (open?.owner === owner)
			open = null
	}
}

@Composable
fun <A> rememberContextMenus(): ContextMenus<A> = remember { ContextMenus() }

/**
 * The context menu [menus] has open, drawn, giving what is chosen to [onAction]; closed, once what it is a menu of
 * offers nothing any more.
 */
@Composable
fun <A> ContextMenuHost(menus: ContextMenus<A>, onAction: (A) -> Unit) {
	val open = menus.open ?: return
	val menu = open.menu()?.takeUnless { it.isEmpty() }

	if (menu == null)
		LaunchedEffect(open) { menus.close() }
	else
		MenuPopup(menu, MenuAnchor.At(open.at), onAction, menus::close)
}

/** Where a layout is in the window, kept as it is placed, for a point in it to be a point of the window. */
internal class InWindow {
	var coordinates: LayoutCoordinates? = null
}

/**
 * The right button pressed here opening, at the pointer, the menu [menu] gives with the keys held as it was pressed
 * (none where it gives null). [menu] only works a menu out: it is asked again while the menu is open, so that the
 * menu shows what is so now. What else the press does (the keyboard taken, a row chosen) is [opening]'s, done once,
 * before the menu is first asked for. A press something inside has taken for its own menu is left to it, so a row's
 * menu opens over a row and the listing's over the rest of the listing.
 */
fun <A> Modifier.contextMenu(
	menus: ContextMenus<A>,
	opening: () -> Unit = {},
	menu: (held: Set<Held>) -> Menu<A>?,
): Modifier =
	composed {
		val placed = remember { InWindow() }
		val latest by rememberUpdatedState(menu)
		val began by rememberUpdatedState(opening)
		// What its menu is open over: this, wherever it is drawn, for as long as it is.
		val owner = remember { Any() }

		DisposableEffect(menus) { onDispose { menus.closeOf(owner) } }

		onGloballyPositioned { placed.coordinates = it }
			.pointerInput(menus) {
				// Every press is looked at, since Compose's own wait for a press waits only for the primary button.
				awaitPointerEventScope {
					while (true) {
						val event = awaitPointerEvent()
						val down = event.changes.firstOrNull { it.changedToDown() }

						if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed &&
							down != null
						) {
							down.consume()

							placed.coordinates?.localToWindow(down.position)?.let { point ->
								val held = heldOf(event.keyboardModifiers)

								began()
								menus.open(
									IntOffset(point.x.roundToInt(), point.y.roundToInt()),
									owner,
								) { latest(held) }
							}
						}
					}
				}
			}
	}

/** The keys of [modifiers] a menu can be told were held as it opened. */
fun heldOf(modifiers: PointerKeyboardModifiers): Set<Held> =
	buildSet {
		if (modifiers.isShiftPressed)
			add(Held.Shift)
		if (modifiers.isCtrlPressed)
			add(Held.Ctrl)
		if (modifiers.isAltPressed)
			add(Held.Alt)
	}

/**
 * A toolbar button whose menu opens under it: [menu] is worked out each time the view is drawn while it is open, so a
 * box ticked in it, which leaves it open, shows at once what it was set to. It shows as [selected] while what the
 * menu chooses is not as it was at first (a filter on), and while the menu is open.
 */
@Composable
fun <A> MenuButton(
	glyph: Glyph,
	label: String,
	menu: () -> Menu<A>,
	onAction: (A) -> Unit,
	modifier: Modifier = Modifier,
	selected: Boolean = false,
	enabled: Boolean = true,
) {
	var open by remember { mutableStateOf(false) }

	Box {
		ToolButton(glyph, label, enabled = enabled, modifier, selected = selected || open) {
			open =
				!open
		}
		if (open)
			MenuPopup(menu(), MenuAnchor.Below, onAction) { open = false }
	}
}

/**
 * The menus Compose opens of its own (a text field's: Cut, Copy, Paste, Select all), drawn as prism's are, at the
 * pointer, rather than in Compose's own look.
 */
object PrismContextMenus : ContextMenuRepresentation {
	@Composable
	override fun Representation(
		state: ContextMenuState,
		items: () -> List<ContextMenuItem>,
	) {
		// Where the menu's area is in the window, found from a mark of no size left in it: the menu opens at a point of
		// the area, and is drawn over the whole window.
		var area by remember { mutableStateOf<LayoutCoordinates?>(null) }

		Box(Modifier.onGloballyPositioned { area = it.parentLayoutCoordinates })

		val open = state.status as? ContextMenuState.Status.Open ?: return
		val at = area?.takeIf { it.isAttached }?.localToWindow(open.rect.center) ?: return
		val offered = items()

		if (offered.isNotEmpty())
			MenuPopup(
				menu { offered.forEach { item(it.label, it) } },
				MenuAnchor.At(IntOffset(at.x.roundToInt(), at.y.roundToInt())),
				onAction = { it.onClick() },
				onClose = { state.status = ContextMenuState.Status.Closed },
			)
	}
}
