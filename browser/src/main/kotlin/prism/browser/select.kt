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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import prism.window.WindowLocals

/*
 * A choice of one of many, as a desktop's drop-down list: a control that shows what is chosen, and a list that opens
 * under it to choose from. A long list (a font of every one the system has) has a field at its top that narrows it as
 * it is typed into. Each option is tagged `option:` and its name.
 */

/**
 * One of [options], each named by [label], chosen from a list that opens below the control: by the pointer, or from
 * the keyboard by Enter, Space or Alt+Down to open it, and Up and Down to choose without opening it. The list, open,
 * is gone through by Up, Down, Home and End and chosen from by Enter; where it is [searchable], what is typed narrows
 * it. Escape, or a click outside, closes it as it was.
 */
@Composable
fun <T> Select(
	options: List<T>,
	chosen: T,
	label: (T) -> String,
	modifier: Modifier = Modifier,
	searchable: Boolean = options.size > 12,
	onChoose: (T) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val shape = RoundedCornerShape(Radius.medium)
	var open by remember { mutableStateOf(false) }
	var width by remember { mutableIntStateOf(0) }
	val at = options.indexOf(chosen)

	fun choose(index: Int): Boolean {
		options.getOrNull(index)?.takeIf { index != at }?.let(onChoose)

		return true
	}

	Box(modifier) {
		Row(
			Modifier
				.fillMaxWidth()
				.ring(felt.ringed, palette.focus)
				.height(type.control)
				.onSizeChanged { width = it.width }
				.background(
					if (felt.hovered || felt.pressed)
						palette.hover
					else
						palette.field,
					shape,
				).border(
					1.dp,
					if (felt.hovered)
						palette.fieldEdgeHover
					else
						palette.fieldEdge,
					shape,
				).onPreviewKeyEvent {
					when {
						it.type != KeyEventType.KeyDown -> false
						it.key == Key.DirectionDown && it.isAltPressed -> true.also { open = true }
						it.key == Key.DirectionUp -> choose(at - 1)
						it.key == Key.DirectionDown -> choose(at + 1)
						else -> false
					}
				}.clickable(source, indication = null, role = Role.DropdownList) { open = true }
				.padding(start = Space.m, end = Space.s),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
		) {
			Text(
				label(chosen),
				style = type.body,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			GlyphIcon(Glyph.ChevronDown, palette.textMuted, type.icon * 0.75f)
		}
		if (open)
			Popup(
				popupPositionProvider = Below,
				onDismissRequest = { open = false },
				properties = PopupProperties(focusable = true),
			) {
				WindowLocals {
					OptionList(
						options,
						at,
						label,
						searchable,
						with(LocalDensity.current) { width.toDp() },
						onClose = { open = false },
					) {
						open = false
						choose(it)
					}
				}
			}
	}
}

/** The list a [Select] opens: every option, or those the field narrows it to, the one [at] marked. */
@Composable
private fun <T> OptionList(
	options: List<T>,
	at: Int,
	label: (T) -> String,
	searchable: Boolean,
	width: androidx.compose.ui.unit.Dp,
	onClose: () -> Unit,
	onChoose: (Int) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.medium)
	val focus = remember { FocusRequester() }
	var typed by remember { mutableStateOf("") }
	val shown =
		options.indices.filter {
			typed.isBlank() || label(options[it]).contains(typed.trim(), ignoreCase = true)
		}
	var lit by remember(shown) { mutableIntStateOf(shown.indexOf(at).coerceAtLeast(0)) }
	val list = rememberLazyListState()

	LaunchedEffect(lit, shown) {
		val visible = list.layoutInfo.visibleItemsInfo.map { it.index }

		if (lit !in visible.drop(1).dropLast(1))
			list.scrollToItem((lit - 2).coerceAtLeast(0))
	}
	LaunchedEffect(Unit) { focus.requestFocus() }

	fun key(key: KeyEvent): Boolean {
		if (key.type != KeyEventType.KeyDown)
			return false

		when (key.key) {
			Key.DirectionDown -> lit = (lit + 1).coerceAtMost(shown.lastIndex)
			Key.DirectionUp -> lit = (lit - 1).coerceAtLeast(0)
			Key.PageDown -> lit = (lit + 8).coerceAtMost(shown.lastIndex)
			Key.PageUp -> lit = (lit - 8).coerceAtLeast(0)
			Key.MoveHome ->
				if (!searchable)
					lit = 0
				else
					return false
			Key.MoveEnd ->
				if (!searchable)
					lit = shown.lastIndex
				else
					return false
			Key.Enter, Key.NumPadEnter, Key.Spacebar ->
				if (key.key == Key.Spacebar && searchable)
					return false
				else
					shown.getOrNull(lit)?.let(onChoose)
			Key.Escape -> onClose()
			else -> return false
		}

		return true
	}

	Column(
		Modifier
			.width(maxOf(width, 200.dp))
			.shadow(12.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
			.background(palette.overlay, shape)
			.border(1.dp, palette.border, shape)
			.onPreviewKeyEvent(::key)
			.padding(Space.xs)
			.testTag("options"),
		verticalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		if (searchable)
			Field(
				typed,
				{ typed = it },
				Modifier.fillMaxWidth().testTag("options-filter"),
				hint = "Filter",
				leading = Glyph.Search,
				focus = focus,
			)
		else
			Box(Modifier.focusRequester(focus).focusable())
		if (shown.isEmpty())
			Text(
				"No matches",
				style = type.muted,
				modifier = Modifier.padding(horizontal = Space.s, vertical = Space.s),
			)
		LazyScrollArea(Modifier.heightIn(max = type.row * 10), state = list) {
			itemsIndexed(shown, key = { _, it -> it }) { index, option ->
				Option(
					label(options[option]),
					chosen = option == at,
					lit = index == lit,
					onHover = { lit = index },
				) { onChoose(option) }
			}
		}
	}
}

/** One option of an open list: its name, ticked where it is the one chosen, lit where the pointer or the arrows are. */
@Composable
private fun Option(
	name: String,
	chosen: Boolean,
	lit: Boolean,
	onHover: () -> Unit,
	onClick: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val hovered by source.collectIsHoveredAsState()

	LaunchedEffect(hovered) {
		if (hovered)
			onHover()
	}

	Row(
		Modifier
			.fillMaxWidth()
			.height(type.row)
			.background(
				if (lit)
					palette.chosen
				else
					androidx.compose.ui.graphics.Color.Transparent,
				RoundedCornerShape(Radius.small),
			).hoverable(source)
			.clickable(source, indication = null, onClick = onClick)
			.semantics { selected = chosen }
			.testTag("option:$name")
			.padding(horizontal = Space.s),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		Box(Modifier.width(type.icon), contentAlignment = Alignment.Center) {
			if (chosen)
				GlyphIcon(Glyph.Check, palette.accentText, type.icon * 0.8f)
		}
		Text(name, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** Under what opens it, its start at its start; above it where there is no room below; inside the window either way. */
internal object Below : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val gap = 4
		val below = anchorBounds.bottom + gap
		val y =
			if (below + popupContentSize.height <= windowSize.height)
				below
			else
				(anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)

		return IntOffset(
			anchorBounds.left.coerceIn(
				0,
				(windowSize.width - popupContentSize.width).coerceAtLeast(0),
			),
			y,
		)
	}
}
