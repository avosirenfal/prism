package barion.picker

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

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
 * Escape close one, Enter or Space choose, Escape closes the menu. Choosing an item closes the whole menu, except an
 * item chosen with Ctrl held that has an alternate, which does its alternate and leaves the menu open.
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

/** A menu of [menu] open where [anchor] says, giving what is chosen to [onAction]; [onClose] when it closes. */
@Composable
fun <A> MenuPopup(
	menu: Menu<A>,
	anchor: MenuAnchor,
	onAction: (A) -> Unit,
	onClose: () -> Unit,
) = MenuLevel(menu, anchor, onAction, onClose, onBack = onClose, startLit = false)

/**
 * One level of a menu: the menu itself, or a submenu of it. [onClose] closes every level; [onBack] closes this one
 * (the menu itself, at the first level). [startLit] lights its first entry, for a submenu opened from the keyboard.
 */
@Composable
private fun <A> MenuLevel(
	menu: Menu<A>,
	anchor: MenuAnchor,
	onAction: (A) -> Unit,
	onClose: () -> Unit,
	onBack: () -> Unit,
	startLit: Boolean,
) {
	val gap = with(LocalDensity.current) { Dims.gapSmall.roundToPx() }

	// A press outside any level closes the whole menu, as on any desktop: a submenu is a popup of its own, and left to
	// itself would close only itself. Escape is the panel's own, and goes back a level.
	Popup(
		popupPositionProvider = remember(anchor, gap) { MenuPlacement(anchor, gap) },
		onDismissRequest = onClose,
		properties = PopupProperties(focusable = true, dismissOnBackPress = false),
	) {
		MenuPanel(menu, onAction, onClose, onBack, sub = anchor == MenuAnchor.Beside, startLit)
	}
}

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
) {
	val palette = LocalPalette.current
	val shape = RoundedCornerShape(8.dp)
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

	fun choose(index: Int, ctrl: Boolean, byKey: Boolean) {
		when (val entry = menu.entries[index]) {
			is MenuEntry.Item ->
				if (entry.enabled)
					when (val alternate = entry.alternate.takeIf { ctrl }) {
						null -> {
							onClose()
							onAction(entry.action)
						}
						else -> onAction(alternate)
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
			.shadow(10.dp, shape)
			.background(palette.surface, shape)
			.border(1.dp, palette.border, shape)
			.width(IntrinsicSize.Max)
			.widthIn(min = 200.dp)
			.focusRequester(focus)
			.onKeyEvent(::key)
			.focusable()
			.testTag("menu")
			.padding(4.dp),
	) {
		menu.entries.forEachIndexed { index, entry ->
			when (entry) {
				MenuEntry.Separator ->
					Box(
						Modifier
							.fillMaxWidth()
							.padding(
								vertical = 4.dp,
							).height(1.dp)
							.background(palette.divider),
					)
				is MenuEntry.Item ->
					MenuRow(
						entry.label,
						entry.keys,
						entry.mark,
						menu.marked,
						entry.enabled,
						lit = lit == index,
						submenu = false,
						onHover = {
							lit = index
							opening = null
						},
						onChoose = { ctrl -> choose(index, ctrl, byKey = false) },
					)
				is MenuEntry.Submenu ->
					Box {
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
						opening?.takeIf { it.index == index }?.let {
							MenuLevel(
								entry.menu,
								MenuAnchor.Beside,
								onAction,
								onClose,
								onBack = {
									opening = null
									focus.requestFocus()
								},
								startLit = it.byKey,
							)
						}
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
	val source = remember { MutableInteractionSource() }
	val hovered by source.collectIsHoveredAsState()
	val hovering by rememberUpdatedState(onHover)
	val choosing by rememberUpdatedState(onChoose)

	LaunchedEffect(hovered) {
		if (hovered)
			hovering()
	}

	Row(
		Modifier
			.fillMaxWidth()
			.height(28.dp)
			.background(
				if (lit && enabled)
					palette.hover
				else
					Color.Transparent,
				RoundedCornerShape(4.dp),
			).alpha(if (enabled) 1f else 0.4f)
			.hoverable(source)
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
				selected =
					(mark as? Mark.Check)?.on ?: (mark as? Mark.Radio)?.on ?: false
			}.testTag("menu:$label")
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// Room for a mark in every row of a menu that has any, so that the labels line up and turning one on or off
		// moves nothing.
		if (marked)
			Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) {
				when {
					mark is Mark.Check && mark.on -> GlyphIcon(Glyph.Check, palette.accent, 15.dp)
					mark is Mark.Radio && mark.on ->
						Box(
							Modifier.size(6.dp).background(palette.text, CircleShape),
						)
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
			Spacer(Modifier.width(28.dp))
			Text(it, style = type.muted, maxLines = 1)
		}
		if (submenu) {
			Spacer(Modifier.width(16.dp))
			GlyphIcon(Glyph.Chevron, palette.textMuted, 12.dp)
		}
	}
}

/** A menu's place in the window, from where it opens and how big it is: where there is room, and never outside. */
private class MenuPlacement(
	private val anchor: MenuAnchor,
	private val gap: Int,
) : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val width = popupContentSize.width
		val height = popupContentSize.height

		return when (anchor) {
			is MenuAnchor.At ->
				IntOffset(
					fitted(anchor.point.x, width, windowSize.width, anchor.point.x - width),
					fitted(anchor.point.y, height, windowSize.height, anchor.point.y - height),
				)
			MenuAnchor.Below ->
				IntOffset(
					fitted(anchorBounds.left, width, windowSize.width, anchorBounds.right - width),
					fitted(
						anchorBounds.bottom + gap,
						height,
						windowSize.height,
						anchorBounds.top - gap - height,
					),
				)
			MenuAnchor.Beside ->
				IntOffset(
					fitted(anchorBounds.right, width, windowSize.width, anchorBounds.left - width),
					fitted(
						anchorBounds.top - gap,
						height,
						windowSize.height,
						anchorBounds.bottom + gap - height,
					),
				)
		}
	}
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

/** A context menu open: what it offers, and the point of the window it opened at. */
@Immutable
data class OpenMenu<A>(
	val menu: Menu<A>,
	val at: IntOffset,
)

/**
 * The context menus of a part of the view: what is open, if anything. [Modifier.contextMenu] opens one, and
 * [ContextMenuHost] draws it. What is open is the view's own, as any menu's being open is.
 */
@Stable
class ContextMenus<A> {
	var open: OpenMenu<A>? by mutableStateOf(null)
		private set

	/** [menu] opened at [at], a point of the window; a menu with nothing in it is not opened. */
	fun open(menu: Menu<A>, at: IntOffset) {
		if (!menu.isEmpty())
			open = OpenMenu(menu, at)
	}

	fun close() {
		open = null
	}
}

@Composable
fun <A> rememberContextMenus(): ContextMenus<A> = remember { ContextMenus() }

/** The context menu [menus] has open, drawn, giving what is chosen to [onAction]. */
@Composable
fun <A> ContextMenuHost(menus: ContextMenus<A>, onAction: (A) -> Unit) {
	menus.open?.let { MenuPopup(it.menu, MenuAnchor.At(it.at), onAction, menus::close) }
}

/** Where a layout is in the window, kept as it is placed, for a point in it to be a point of the window. */
internal class Placed {
	var coordinates: LayoutCoordinates? = null
}

/**
 * The right button pressed here opening, at the pointer, the menu [menu] gives as it is pressed (none where it gives
 * null). A press something inside has taken for its own menu is left to it, so a row's menu opens over a row and the
 * listing's over the rest of the listing.
 */
fun <A> Modifier.contextMenu(menus: ContextMenus<A>, menu: () -> Menu<A>?): Modifier =
	composed {
		val placed = remember { Placed() }
		val latest by rememberUpdatedState(menu)

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
								latest()?.let {
									menus.open(
										it,
										IntOffset(point.x.roundToInt(), point.y.roundToInt()),
									)
								}
							}
						}
					}
				}
			}
	}

/**
 * A toolbar button whose menu opens under it: [menu] is worked out each time the view is drawn while it is open, so an
 * item chosen with Ctrl, which leaves it open, shows at once what it changed. It shows as [selected] while what the
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
) {
	var open by remember { mutableStateOf(false) }

	Box {
		ToolButton(glyph, label, enabled = true, modifier, selected = selected || open) {
			open =
				!open
		}
		if (open)
			MenuPopup(menu(), MenuAnchor.Below, onAction) { open = false }
	}
}
