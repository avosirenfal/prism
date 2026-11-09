package barion.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The controls the picker is made of: a button, a tool button, a text field, a choice from a list, and the ring that
 * shows where the keyboard is. Each draws its own looks (hovered, pressed, focused, disabled) from the palette, takes
 * the keys a desktop's controls take, and reports only what it is for: pressed, typed, chosen. None knows the picker.
 *
 * What a control keeps of its own is what a control keeps on any desktop: whether the pointer is over it, whether
 * its menu is open, where the caret is in its text. What it shows and what it does are its caller's.
 */

/**
 * Whether the keyboard, rather than the pointer, was used last in the picker. The ring that shows where the keyboard
 * is, is drawn only then, as a browser draws its focus ring only for the keyboard (`:focus-visible`): a control
 * clicked keeps the keyboard, but shows no ring for it once the pointer has gone on to something else. The picker
 * says which was used last ([FilePicker]); a control only reads it.
 */
@Stable
class KeyboardUse {
	var lately by mutableStateOf(true)
}

val LocalKeyboardUse = staticCompositionLocalOf { KeyboardUse() }

/**
 * What the pointer and the keyboard are doing to a control at the moment, read from its interactions: [ringed] is
 * whether it has the keyboard and the keyboard was used last, which is when its ring is drawn.
 */
@Immutable
data class Felt(
	val hovered: Boolean,
	val pressed: Boolean,
	val focused: Boolean,
	val ringed: Boolean,
)

@Composable
fun MutableInteractionSource.felt(): Felt {
	val hovered by collectIsHoveredAsState()
	val pressed by collectIsPressedAsState()
	val focused by collectIsFocusedAsState()
	val keyboard = LocalKeyboardUse.current

	return Felt(hovered, pressed, focused, focused && keyboard.lately)
}

/**
 * The ring around what the keyboard is on, when [shown]: [outset] outside the control's edge, following its corners,
 * so that it is seen whatever the control's own fill. A row inside a list, which clips what is outside it, takes a
 * negative outset to draw it inside.
 */
fun Modifier.ring(
	shown: Boolean,
	color: Color,
	corner: Dp = Dims.radius,
	outset: Dp = Dims.ring,
	width: Dp = 2.dp,
): Modifier =
	drawWithContent {
		drawContent()

		if (shown) {
			val out = outset.toPx()
			val stroke = width.toPx()
			val edge = out - stroke / 2

			drawRoundRect(
				color,
				topLeft = Offset(-edge, -edge),
				size = Size(size.width + 2 * edge, size.height + 2 * edge),
				cornerRadius = CornerRadius((corner.toPx() + edge).coerceAtLeast(0f)),
				style = Stroke(stroke),
			)
		}
	}

/** How a button stands out among others: the one that does what the picker is for, or the rest. */
enum class Emphasis {
	Primary,
	Secondary,
}

/** A button with words on it, pressed by the pointer, or by Enter or Space when the keyboard is on it. */
@Composable
fun PickerButton(
	label: String,
	emphasis: Emphasis,
	enabled: Boolean,
	modifier: Modifier = Modifier,
	focus: FocusRequester = remember { FocusRequester() },
	onClick: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val shape = RoundedCornerShape(Dims.radius)
	val strong =
		when (emphasis) {
			Emphasis.Primary -> palette.accent
			Emphasis.Secondary -> null
		}
	val primary = strong != null

	val fill =
		when {
			strong != null && felt.pressed -> strong.copy(alpha = 0.8f)
			strong != null && felt.hovered -> strong.copy(alpha = 0.9f)
			strong != null -> strong
			felt.pressed -> palette.pressed
			felt.hovered -> palette.hover
			else -> palette.field
		}

	// The ring first, so it is not dimmed; dimmed next, so a button that cannot be pressed is dimmed whole.
	Box(
		modifier
			.ring(felt.ringed, palette.focus)
			.alpha(if (enabled) 1f else 0.45f)
			.width(Dims.button)
			.height(Dims.control)
			.background(fill, shape)
			.border(
				1.dp,
				strong ?: palette.fieldEdge,
				shape,
			).focusRequester(focus)
			.clickable(
				source,
				indication = null,
				enabled = enabled,
				role = Role.Button,
				onClick = onClick,
			),
		contentAlignment = Alignment.Center,
	) {
		Text(
			label,
			style = type.button.copy(color = if (primary) palette.onAccent else palette.text),
		)
	}
}

/**
 * A button with a glyph on it, for the toolbar: pressed by the pointer only, since each has a key of its own (Alt
 * and an arrow, Ctrl and a number), so the keyboard's Tab passes over it. One of a set that shows which is in use
 * (the list or the gallery) is [selected].
 */
@Composable
fun ToolButton(
	glyph: Glyph,
	label: String,
	enabled: Boolean,
	modifier: Modifier = Modifier,
	selected: Boolean = false,
	onClick: () -> Unit,
) {
	val palette = LocalPalette.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val fill =
		when {
			!enabled -> Color.Transparent
			felt.pressed -> palette.pressed
			selected -> palette.chosen
			felt.hovered -> palette.hover
			else -> Color.Transparent
		}

	Box(
		modifier
			.alpha(if (enabled) 1f else 0.35f)
			.width(Dims.control)
			.height(Dims.control)
			.background(fill, RoundedCornerShape(Dims.radius))
			.focusProperties { canFocus = false }
			.clickable(
				source,
				indication = null,
				enabled = enabled,
				onClickLabel = label,
				role = Role.Button,
				onClick = onClick,
			).semantics { this.selected = selected },
		contentAlignment = Alignment.Center,
	) {
		GlyphIcon(
			glyph,
			if (selected)
				palette.accent
			else
				palette.text,
			Dims.icon,
		)
	}
}

/**
 * A line of text to type into. [text] is what it shows, and every change typed is reported to [onText], so the text
 * is its caller's; the caret and the selection are the field's own. When the keyboard comes to it, [selecting]
 * says what of its text is selected (all of it, by default, as a desktop's fields do), and [onKey] sees each key
 * before the field does, for Enter and Escape and the like. [onLeave] is told when the keyboard leaves it, having
 * been there.
 */
@Composable
fun Field(
	text: String,
	onText: (String) -> Unit,
	modifier: Modifier = Modifier,
	hint: String = "",
	trailing: Glyph? = null,
	readOnly: Boolean = false,
	height: Dp = Dims.control,
	focus: FocusRequester = remember { FocusRequester() },
	selecting: (String) -> TextRange = { TextRange(0, it.length) },
	onLeave: () -> Unit = {},
	onKey: (KeyEvent) -> Boolean = { false },
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val shape = RoundedCornerShape(Dims.radius)
	var typed by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
	var visited by remember { mutableStateOf(false) }
	// The caller's text is the text; where it has changed from what was typed here, the caret goes to its end.
	val shown =
		if (typed.text == text)
			typed
		else
			TextFieldValue(text, TextRange(text.length))

	// What is selected as the keyboard arrives, once the field has settled its own caret from the focus.
	LaunchedEffect(felt.focused) {
		if (felt.focused)
			typed = shown.copy(selection = selecting(shown.text))
	}

	Row(
		modifier
			.ring(felt.focused, palette.accent, outset = 0.dp, width = 1.5.dp)
			.height(height)
			.background(palette.field, shape)
			.border(1.dp, palette.fieldEdge, shape)
			.padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
			if (text.isEmpty() && hint.isNotEmpty())
				Text(hint, style = type.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
			BasicTextField(
				shown,
				{
					typed = it

					if (it.text != text)
						onText(it.text)
				},
				Modifier
					.fillMaxWidth()
					.focusRequester(focus)
					.onFocusChanged {
						if (it.isFocused)
							visited = true
						else if (visited)
							onLeave()
					}.onPreviewKeyEvent(onKey),
				singleLine = true,
				readOnly = readOnly,
				textStyle = type.body,
				cursorBrush = SolidColor(palette.accent),
				interactionSource = source,
			)
		}
		trailing?.let {
			Spacer(Modifier.width(Dims.gapSmall))
			GlyphIcon(it, palette.textMuted, 16.dp)
		}
	}
}

/**
 * One of [options], chosen from a menu that opens below it: by the pointer, or from the keyboard by Enter, Space or
 * Alt+Down to open it and the arrows to choose without opening it, as a desktop's drop-down lists are.
 */
@Composable
fun <T> Select(
	options: List<T>,
	chosen: T,
	label: (T) -> String,
	modifier: Modifier = Modifier,
	onChoose: (T) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val shape = RoundedCornerShape(Dims.radius)
	var open by remember { mutableStateOf(false) }
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
				.height(Dims.control)
				.background(
					when {
						felt.pressed -> palette.pressed
						felt.hovered -> palette.hover
						else -> palette.field
					},
					shape,
				).border(1.dp, palette.fieldEdge, shape)
				.onPreviewKeyEvent {
					when {
						it.type != KeyEventType.KeyDown -> false
						it.key == Key.DirectionDown && it.isAltPressed -> true.also { open = true }
						it.key == Key.DirectionUp -> choose(at - 1)
						it.key == Key.DirectionDown -> choose(at + 1)
						else -> false
					}
				}.clickable(source, indication = null, role = Role.DropdownList) { open = true }
				.padding(horizontal = Dims.gap),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				label(chosen),
				style = type.body,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			GlyphIcon(Glyph.ChevronDown, palette.textMuted, 14.dp)
		}
		DropdownMenu(open, onDismissRequest = { open = false }) {
			options.forEachIndexed { index, option ->
				DropdownMenuItem(
					text = { Text(label(option), style = type.body) },
					onClick = {
						open = false
						choose(index)
					},
					modifier = Modifier.testTag("option:${label(option)}"),
				)
			}
		}
	}
}
