package prism.browser

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/*
 * The controls prism is made of: buttons, icon buttons, a text field, a check box, a switch, a radio, a segmented
 * choice, a stepper, a chip, tabs, and the ring that shows where the keyboard is. Each is built from the theme's tokens
 * alone (theme.kt): its height from the type of its text, its room from the spacing scale, its colours from the
 * palette, so no caller sizes or pads one. Each draws its own looks (hovered, pressed, focused, disabled), takes the
 * keys a desktop's controls take, and reports only what it is for: pressed, typed, chosen.
 *
 * What a control keeps of its own is what a control keeps on any desktop: whether the pointer is over it, whether
 * its menu is open, where the caret is in its text. What it shows and what it does are its caller's.
 */

/**
 * Whether the keyboard, rather than the pointer, was used last in the window. The ring that shows where the keyboard
 * is, is drawn only then, as a browser draws its focus ring only for the keyboard (`:focus-visible`): a control
 * clicked keeps the keyboard, but shows no ring for it once the pointer has gone on to something else. The window
 * says which was used last ([FileBrowser]); a control only reads it.
 */
@Stable
class KeyboardUse {
	var lately by mutableStateOf(true)
}

val LocalKeyboardUse = staticCompositionLocalOf { KeyboardUse() }

/**
 * What the pointer and the keyboard are doing to a control at the moment, read from its interactions. [focused] is
 * whether it is the window's focused control, which it stays while the window is in the background; [active] is
 * whether it has the keyboard now, which is that and the window having it too; [ringed] is whether it is active and
 * the keyboard was used last, which is when its ring is drawn.
 */
@Immutable
data class Felt(
	val hovered: Boolean,
	val pressed: Boolean,
	val focused: Boolean,
	val active: Boolean,
	val ringed: Boolean,
)

@Composable
fun MutableInteractionSource.felt(): Felt {
	val hovered by collectIsHoveredAsState()
	val pressed by collectIsPressedAsState()
	val focused by collectIsFocusedAsState()
	val keyboard = LocalKeyboardUse.current
	val active = focused && LocalWindowInfo.current.isWindowFocused

	return Felt(hovered, pressed, focused, active, active && keyboard.lately)
}

/**
 * The ring around what the keyboard is on, when [shown]: [outset] outside the control's edge, following its corners,
 * so that it is seen whatever the control's own fill. A row inside a list, which clips what is outside it, takes a
 * negative outset to draw it inside.
 */
fun Modifier.ring(
	shown: Boolean,
	color: Color,
	corner: Dp = Radius.medium,
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

/**
 * How a button stands out among others: the one that does what a dialog is for ([Primary]), one that does that and
 * cannot be undone ([Danger]), the rest ([Secondary]), and one that is only a word to click ([Quiet]).
 */
enum class Emphasis {
	Primary,
	Danger,
	Secondary,
	Quiet,
}

/** A control's fill, edge and text, as it is felt. */
@Immutable
private data class ControlLooks(
	val fill: Color,
	val edge: Color,
	val text: Color,
)

private fun looksOf(emphasis: Emphasis, felt: Felt, palette: Palette): ControlLooks {
	fun quiet() =
		when {
			felt.pressed -> palette.pressed
			felt.hovered -> palette.hover
			else -> Color.Transparent
		}

	return when (emphasis) {
		Emphasis.Primary ->
			ControlLooks(
				when {
					felt.pressed -> palette.accentPressed
					felt.hovered -> palette.accentHover
					else -> palette.accent
				},
				Color.Transparent,
				palette.onAccent,
			)
		Emphasis.Danger ->
			ControlLooks(
				if (felt.hovered || felt.pressed)
					palette.dangerHover
				else
					palette.danger,
				Color.Transparent,
				palette.onDanger,
			)
		Emphasis.Secondary -> ControlLooks(quiet(), palette.borderStrong, palette.text)
		Emphasis.Quiet -> ControlLooks(quiet(), Color.Transparent, palette.text)
	}
}

/** A button with words on it, pressed by the pointer, or by Enter or Space when the keyboard is on it. */
@Composable
fun PushButton(
	label: String,
	emphasis: Emphasis,
	enabled: Boolean,
	modifier: Modifier = Modifier,
	focus: FocusRequester = remember { FocusRequester() },
	glyph: Glyph? = null,
	onClick: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val shape = RoundedCornerShape(Radius.medium)

	val looks =
		looksOf(
			emphasis,
			felt.takeIf { enabled } ?: felt.copy(hovered = false, pressed = false),
			palette,
		)

	// The ring first, so it is not dimmed; dimmed next, so a button that cannot be pressed is dimmed whole.
	Row(
		modifier
			.ring(felt.ringed, palette.focus)
			.alpha(if (enabled) 1f else 0.45f)
			// As wide as a button is, or as its words need, whichever is wider: words are never broken over lines.
			.widthIn(min = 84.dp)
			.height(type.control)
			.background(looks.fill, shape)
			.border(1.dp, looks.edge, shape)
			.focusRequester(focus)
			.clickable(
				source,
				indication = null,
				enabled = enabled,
				role = Role.Button,
				onClick = onClick,
			).padding(horizontal = Space.l),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
	) {
		glyph?.let { GlyphIcon(it, looks.text, type.icon * 0.85f) }
		Text(
			label,
			style = type.button.copy(color = looks.text),
			maxLines = 1,
			softWrap = false,
		)
	}
}

/**
 * [content] that says [tip] when the pointer rests on it: what a button with no words on it does, and its key, where
 * it has one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tip(tip: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.medium)

	TooltipArea(
		tooltip = {
			Text(
				tip,
				style = type.muted.copy(color = palette.text),
				modifier =
					Modifier
						.shadow(6.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
						.background(palette.overlay, shape)
						.border(1.dp, palette.border, shape)
						.padding(horizontal = Space.s, vertical = Space.xs),
			)
		},
		modifier = modifier,
		delayMillis = 600,
		tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 20.dp)),
		content = content,
	)
}

/**
 * A button with a glyph on it, for a toolbar: pressed by the pointer only, since each has a key of its own (Alt and an
 * arrow, Ctrl and a number), so the keyboard's Tab passes over it. It says what it does ([label]) when the pointer
 * rests on it. One of a set that shows which is in use is [selected].
 */
@Composable
fun ToolButton(
	glyph: Glyph,
	label: String,
	enabled: Boolean,
	modifier: Modifier = Modifier,
	selected: Boolean = false,
	size: Dp = LocalType.current.control,
	icon: Dp = LocalType.current.icon,
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

	Tip(label) {
		Box(
			modifier
				.alpha(if (enabled) 1f else 0.35f)
				.size(size)
				.background(fill, RoundedCornerShape(Radius.medium))
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
					palette.accentText
				else
					palette.textMuted,
				icon,
			)
		}
	}
}

/**
 * A line of text to type into. [text] is what it shows, and every change typed is reported to [onText], so the text
 * is its caller's; the caret and the selection are the field's own. When the keyboard comes to it, [selecting]
 * says what of its text is selected (all of it, by default, as a desktop's fields do), and [onKey] sees each key
 * before the field does, for Enter and Escape and the like. [onLeave] is told when the keyboard leaves it, having
 * been there. Each time [reselect] changes while the keyboard is in it, what [selecting] says is selected again, as
 * though the keyboard had just arrived: a combo box's text clicked, to be typed over. [onSelection] is told what is
 * selected (the caret, where nothing is) as the field shows it, for whoever gives a key a meaning that depends on
 * where the caret is. [badge], where there is one, is drawn after its glyph: a small mark of what the field is for. A field that [restsAtStart] shows its text from its beginning whenever the
 * keyboard is not in it, where one that does not shows its end: for text too long for its field that is read more
 * than it is typed.
 */
@Suppress("DEPRECATION") // The window feeds value-based fields.
@Composable
fun Field(
	text: String,
	onText: (String) -> Unit,
	modifier: Modifier = Modifier,
	hint: String = "",
	leading: Glyph? = null,
	trailing: Glyph? = null,
	readOnly: Boolean = false,
	height: Dp = LocalType.current.control,
	focus: FocusRequester = remember { FocusRequester() },
	selecting: (String) -> TextRange = { TextRange(0, it.length) },
	reselect: Int = 0,
	onLeave: () -> Unit = {},
	onKey: (KeyEvent) -> Boolean = { false },
	colouring: VisualTransformation = VisualTransformation.None,
	style: TextStyle = LocalType.current.body,
	onSelection: (TextRange) -> Unit = {},
	badge: (@Composable () -> Unit)? = null,
	restsAtStart: Boolean = false,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val shape = RoundedCornerShape(Radius.medium)

	/** Where the caret is in [text] while the keyboard is elsewhere. */
	fun resting(text: String) = TextRange(if (restsAtStart) 0 else text.length)

	var typed by remember { mutableStateOf(TextFieldValue(text, resting(text))) }
	var visited by remember { mutableStateOf(false) }
	// What this field has said was typed that the caller's text has not come round to yet. The caller's text is the
	// text, but it comes back a moment after each key, and a key pressed meanwhile types onto what was typed here, not
	// onto the text a key behind; so while the caller's text is one of these, the field is catching up, not changed.
	val echoes = remember { ArrayList<String>() }

	val shown =
		when (text) {
			typed.text, in echoes -> typed
			// Changed from outside (emptied, say): the caret goes to its end.
			else ->
				TextFieldValue(
					text,
					if (felt.focused)
						TextRange(text.length)
					else
						resting(text),
				)
		}

	SideEffect {
		if (text == typed.text || text !in echoes)
			echoes.clear()
		onSelection(shown.selection)
	}

	// What is selected as the keyboard arrives, once the field has settled its own caret from the focus.
	LaunchedEffect(felt.focused, reselect) {
		if (felt.focused)
			typed = shown.copy(selection = selecting(shown.text))
		else if (restsAtStart)
			typed = shown.copy(selection = resting(shown.text))
	}

	Row(
		modifier
			.ring(felt.active, palette.focus, outset = 0.dp, width = 2.dp)
			.height(height)
			.background(palette.field, shape)
			.border(
				1.dp,
				if (felt.hovered)
					palette.fieldEdgeHover
				else
					palette.fieldEdge,
				shape,
			).padding(horizontal = Space.m),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		leading?.let { GlyphIcon(it, palette.textMuted, type.icon * 0.85f) }
		badge?.invoke()
		Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
			if (text.isEmpty() && hint.isNotEmpty())
				Text(
					hint,
					style = style.copy(color = palette.textFaint),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			BasicTextField(
				shown,
				{
					typed = it

					if (it.text != text) {
						echoes += it.text
						onText(it.text)
					}
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
				textStyle = style,
				cursorBrush = SolidColor(palette.accent),
				interactionSource = source,
				visualTransformation = colouring,
			)
		}
		trailing?.let { GlyphIcon(it, palette.textMuted, type.icon * 0.85f) }
	}
}

/** A box ticked or not, with [label] beside it: toggled by the pointer, or by Space when the keyboard is on it. */
@Composable
fun CheckBox(
	label: String,
	checked: Boolean,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	onChange: (Boolean) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()

	Row(
		modifier
			.alpha(if (enabled) 1f else 0.45f)
			.toggleable(
				checked,
				source,
				indication = null,
				enabled = enabled,
				role = Role.Checkbox,
				onValueChange = onChange,
			).padding(vertical = Space.xs),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		TickBox(
			checked,
			Modifier.ring(felt.ringed, palette.focus, corner = Radius.small),
			hovered = felt.hovered && enabled,
		)
		if (label.isNotEmpty())
			Text(label, style = type.body)
	}
}

/**
 * The box of a check box alone, ticked or not, [size] across: what says "this is on or off" wherever it is drawn, a
 * setting's [CheckBox] and a menu's item alike. It takes no press of its own: what it is drawn in does.
 */
@Composable
fun TickBox(
	checked: Boolean,
	modifier: Modifier = Modifier,
	size: Dp = 18.dp,
	hovered: Boolean = false,
	/** Whether it stands for several things of which some are on and some off: a bar across it, not a tick. */
	mixed: Boolean = false,
) {
	val palette = LocalPalette.current
	val shape = RoundedCornerShape(Radius.small)
	val filled = checked || mixed

	Box(
		modifier
			.size(size)
			.background(
				when {
					filled && hovered -> palette.accentHover
					filled -> palette.accent
					hovered -> palette.hover
					else -> palette.field
				},
				shape,
			).border(
				1.dp,
				if (filled)
					Color.Transparent
				else
					palette.fieldEdge,
				shape,
			),
		contentAlignment = Alignment.Center,
	) {
		when {
			mixed ->
				Box(
					Modifier
						.size(size * 0.5f, 2.dp)
						.background(palette.onAccent, RoundedCornerShape(1.dp)),
				)
			checked -> GlyphIcon(Glyph.Check, palette.onAccent, size * 0.78f)
		}
	}
}

/**
 * A box that stands for several ([state]: all of them on, none, or some), with [label] beside it: pressed, or Space
 * with the keyboard on it, it is [onClick]'s to say what becomes of them.
 */
@Composable
fun TriBox(
	label: String,
	state: ToggleableState,
	modifier: Modifier = Modifier,
	onClick: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()

	Row(
		modifier
			.triStateToggleable(
				state,
				source,
				indication = null,
				role = Role.Checkbox,
				onClick = onClick,
			).padding(vertical = Space.xs),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		TickBox(
			state == ToggleableState.On,
			Modifier.ring(felt.ringed, palette.focus, corner = Radius.small),
			hovered = felt.hovered,
			mixed = state == ToggleableState.Indeterminate,
		)
		if (label.isNotEmpty())
			Text(
				label,
				style = type.strong.copy(color = palette.textMuted),
				maxLines = 1,
				softWrap = false,
			)
	}
}

/**
 * A switch, on or off, for a setting that takes effect as it is switched: slid by the pointer, or by Space when the
 * keyboard is on it.
 */
@Composable
fun Switch(
	on: Boolean,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	onChange: (Boolean) -> Unit,
) {
	val palette = LocalPalette.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val knob by animateDpAsState(if (on) 18.dp else 2.dp)

	Box(
		modifier
			.ring(felt.ringed, palette.focus, corner = 11.dp)
			.alpha(if (enabled) 1f else 0.45f)
			.size(38.dp, 22.dp)
			.background(
				when {
					on && felt.hovered && enabled -> palette.accentHover
					on -> palette.accent
					else -> palette.borderStrong
				},
				CircleShape,
			).toggleable(
				on,
				source,
				indication = null,
				enabled = enabled,
				role = Role.Switch,
				onValueChange = onChange,
			),
		contentAlignment = Alignment.CenterStart,
	) {
		Box(
			Modifier
				.offset(x = knob)
				.size(18.dp)
				.shadow(1.dp, CircleShape)
				.background(
					if (on || !palette.isDark)
						Color.White
					else
						palette.text,
					CircleShape,
				),
		)
	}
}

/**
 * A ring, [size] across, filled with a dot in it where it is the one chosen of its kind: what a choice of several,
 * each in a row, is marked by, in a settings page and in a menu alike.
 */
@Composable
fun Radio(chosen: Boolean, size: Dp = 18.dp) {
	val palette = LocalPalette.current

	Box(
		Modifier
			.size(size)
			.background(if (chosen) palette.accent else palette.field, CircleShape)
			.border(
				1.dp,
				if (chosen)
					Color.Transparent
				else
					palette.fieldEdge,
				CircleShape,
			),
		contentAlignment = Alignment.Center,
	) {
		if (chosen)
			Box(Modifier.size(size * 0.39f).background(palette.onAccent, CircleShape))
	}
}

/**
 * One of a few [options], side by side in a track, the one [chosen] raised in it: chosen by the pointer, or by Left and
 * Right when the keyboard is on the track. Each option is named by [label], and drawn as its glyph instead where
 * [glyph] gives one (and its name said when the pointer rests on it). Each is tagged [tag]`:`its name. One in a
 * toolbar, whose choices have keys of their own, is not [focusable]: Tab passes over it, and a click leaves the keyboard
 * where it was.
 */
@Composable
fun <T> Segmented(
	options: List<T>,
	chosen: T,
	label: (T) -> String,
	modifier: Modifier = Modifier,
	tag: String = "",
	enabled: Boolean = true,
	focusable: Boolean = true,
	glyph: (T) -> Glyph? = { null },
	onChoose: (T) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val track = RoundedCornerShape(Radius.medium + Space.xxs)
	val at = options.indexOf(chosen)

	fun step(by: Int): Boolean {
		options.getOrNull(at + by)?.let(onChoose)

		return true
	}

	Layout(
		content = {
			options.forEach { option ->
				val name = label(option)
				val icon = glyph(option)

				if (icon != null)
					Tip(name, Modifier.fillMaxHeight()) {
						Segment(name, icon, option == chosen, enabled, "$tag:$name") { onChoose(option) }
					}
				else
					Segment(name, null, option == chosen, enabled, "$tag:$name") { onChoose(option) }
			}
		},
		modifier =
			modifier
				.ring(felt.ringed, palette.focus, corner = Radius.medium + Space.xxs)
				.alpha(if (enabled) 1f else 0.45f)
				.height(type.control)
				.background(palette.sunken, track)
				.border(1.dp, palette.border, track)
				.onKeyEvent {
					when {
						!enabled || it.type != KeyEventType.KeyDown -> false
						it.key == Key.DirectionLeft -> step(-1)
						it.key == Key.DirectionRight -> step(1)
						else -> false
					}
				}.focusable(enabled && focusable, source)
				.padding(Space.xxs),
		measurePolicy = SegmentsSideBySide,
	)
}

/** One choice of a [Segmented]: its [name], or its [icon] where it has one; raised where [selected]. */
@Composable
private fun Segment(
	name: String,
	icon: Glyph?,
	selected: Boolean,
	enabled: Boolean,
	tag: String,
	onChoose: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()
	val shape = RoundedCornerShape(Radius.medium)

	Box(
		Modifier
			.fillMaxHeight()
			.then(
				when {
					selected ->
						Modifier
							.shadow(1.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
							.background(palette.thumb, shape)
					hovered && enabled -> Modifier.background(palette.hover, shape)
					else -> Modifier
				},
			).focusProperties { canFocus = false }
			.selectable(
				selected,
				hover,
				indication = null,
				enabled = enabled,
				role = Role.Tab,
				onClick = onChoose,
			).testTag(tag)
			.padding(horizontal = if (icon == null) Space.m else Space.s),
		contentAlignment = Alignment.Center,
	) {
		val tint = if (selected) palette.text else palette.textMuted

		if (icon != null)
			GlyphIcon(icon, tint, type.icon * 0.9f)
		else
			Text(
				name,
				style = (if (selected) type.button else type.body).copy(color = tint),
				maxLines = 1,
				softWrap = false,
				overflow = TextOverflow.Ellipsis,
			)
	}
}

/**
 * A [Segmented]'s choices side by side: each as wide as the widest, where that fits, so they look alike; each as wide as
 * it needs, where only that fits, so each is read whole; narrowed in proportion, where not even that does. Room more
 * than they take (the track made to fill a width) is shared out among them.
 */
private val SegmentsSideBySide =
	MeasurePolicy { measurables, constraints ->
		val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
		val widest = natural.maxOrNull() ?: 0
		val room = constraints.maxWidth
		val sized =
			when {
				widest * natural.size <= room -> natural.map { widest }
				natural.sum() <= room -> natural
				else -> natural.map { it * room / natural.sum() }
			}
		val extra = (constraints.minWidth - sized.sum()).coerceAtLeast(0)
		val widths =
			sized.mapIndexed { at, width ->
				width + extra / sized.size +
					if (at < extra % sized.size) 1 else 0
			}
		val height = constraints.maxHeight

		val placeables =
			measurables.zip(
				widths,
			) { segment, width -> segment.measure(Constraints.fixed(width, height)) }

		layout(widths.sum(), height) {
			placeables.fold(
				0,
			) { x, placeable -> x + placeable.width.also { placeable.place(x, 0) } }
		}
	}

/**
 * Options any number of which are [on], side by side in one track, each turned on or off by itself ([onToggle]): one on
 * is drawn in the accent and one off quietly, so that among many rows of them only what is on stands out. Each is named
 * by [label] and tagged [tag]`:`its name; the track is [height] tall, a control's unless a row's is wanted.
 */
@Composable
fun <T> Toggles(
	options: List<T>,
	on: Set<T>,
	label: (T) -> String,
	modifier: Modifier = Modifier,
	tag: String = "",
	height: Dp = LocalType.current.control,
	onToggle: (T) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val track = RoundedCornerShape(Radius.medium + Space.xxs)

	Row(
		modifier
			.height(height)
			.width(IntrinsicSize.Max)
			.border(1.dp, palette.border, track)
			.padding(Space.xxs),
		horizontalArrangement = Arrangement.spacedBy(Space.xxs),
	) {
		options.forEach { option ->
			val name = label(option)
			val lit = option in on
			val source = remember { MutableInteractionSource() }
			val felt = source.felt()
			val shape = RoundedCornerShape(Radius.medium)

			Box(
				Modifier
					.fillMaxHeight()
					.weight(1f)
					.ring(felt.ringed, palette.focus, corner = Radius.medium)
					.background(
						when {
							lit -> palette.accent
							felt.hovered -> palette.hover
							else -> Color.Transparent
						},
						shape,
					).toggleable(
						lit,
						source,
						indication = null,
						role = Role.Checkbox,
					) { onToggle(option) }
					.testTag("$tag:$name")
					.padding(horizontal = Space.s),
				contentAlignment = Alignment.Center,
			) {
				Text(
					name,
					style = type.muted.copy(color = if (lit) palette.onAccent else palette.textMuted),
					maxLines = 1,
					softWrap = false,
				)
			}
		}
	}
}

/**
 * A value, said as [shown], made a step smaller or larger by the buttons either side of it, as far as [smaller] and
 * [larger] allow. Its buttons are tagged [tag]`:smaller` and [tag]`:larger`.
 */
@Composable
fun Stepper(
	shown: String,
	smaller: Boolean,
	larger: Boolean,
	modifier: Modifier = Modifier,
	tag: String = "",
	onStep: (Int) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.medium)

	@Composable
	fun StepButton(glyph: Glyph, label: String, enabled: Boolean, by: Int) {
		val source = remember { MutableInteractionSource() }
		val felt = source.felt()

		Box(
			Modifier
				.fillMaxHeight()
				.width(type.control)
				.alpha(if (enabled) 1f else 0.35f)
				.background(
					when {
						!enabled -> Color.Transparent
						felt.pressed -> palette.pressed
						felt.hovered -> palette.hover
						else -> Color.Transparent
					},
				).clickable(
					source,
					indication = null,
					enabled = enabled,
					onClickLabel = label,
					role = Role.Button,
				) {
					onStep(by)
				}.testTag("$tag:$label"),
			contentAlignment = Alignment.Center,
		) {
			GlyphIcon(glyph, palette.text, type.icon * 0.8f)
		}
	}

	Row(
		modifier
			.height(type.control)
			.width(IntrinsicSize.Max)
			.background(palette.field, shape)
			.border(1.dp, palette.fieldEdge, shape)
			.clip(shape),
		verticalAlignment = Alignment.CenterVertically,
	) {
		StepButton(Glyph.Minus, "smaller", smaller, -1)
		Divider(vertical = true)
		Text(
			shown,
			style = type.body.tabular(),
			maxLines = 1,
			softWrap = false,
			modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = Space.s),
			textAlign = androidx.compose.ui.text.style.TextAlign.Center,
		)
		Divider(vertical = true)
		StepButton(Glyph.Plus, "larger", larger, 1)
	}
}

/** Digits each as wide as the next, so that numbers in a column line up under each other. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** A line between two things: across, or, where [vertical], up and down. */
@Composable
fun Divider(modifier: Modifier = Modifier, vertical: Boolean = false) {
	val palette = LocalPalette.current

	Box(
		if (vertical)
			modifier.width(1.dp).fillMaxHeight().background(palette.divider)
		else
			modifier.fillMaxWidth().height(1.dp).background(palette.divider),
	)
}

/**
 * A chip: [text] in a small rounded box, clicked by the pointer, with a × that removes what it stands for where
 * [onRemove] is given. One that is [strong] (a chord awaited, say) is drawn in the accent.
 */
@Composable
fun Chip(
	text: String,
	modifier: Modifier = Modifier,
	strong: Boolean = false,
	removeTag: String = "",
	onRemove: (() -> Unit)? = null,
	onClick: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.medium)
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()

	Row(
		modifier
			.height(type.row - Space.xs)
			.background(
				when {
					strong -> palette.accent
					felt.hovered -> palette.hover
					else -> palette.sunken
				},
				shape,
			).border(1.dp, if (strong) Color.Transparent else palette.border, shape)
			.clickable(source, indication = null, onClick = onClick)
			.padding(start = Space.s, end = if (onRemove != null) Space.xs else Space.s),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Text(
			text,
			style =
				type.muted
					.copy(
						color = if (strong) palette.onAccent else palette.text,
					).tabular(),
			maxLines = 1,
		)
		onRemove?.let {
			val remove = remember { MutableInteractionSource() }
			val hovered by remove.collectIsHoveredAsState()

			Box(
				Modifier
					.size(type.line - Space.xxs)
					.background(
						if (hovered)
							palette.pressed
						else
							Color.Transparent,
						CircleShape,
					).clickable(remove, indication = null, onClick = it)
					.testTag(removeTag),
				contentAlignment = Alignment.Center,
			) {
				GlyphIcon(Glyph.Close, palette.textMuted, type.icon * 0.6f)
			}
		}
	}
}

/** A row of tabs, [chosen] among [tabs], each named by [label] and underlined in the accent where it is the one shown. */
@Composable
fun <T> Tabs(
	tabs: List<T>,
	chosen: T,
	label: (T) -> String,
	modifier: Modifier = Modifier,
	onChoose: (T) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Column(modifier) {
		Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
			tabs.forEach { tab ->
				val source = remember { MutableInteractionSource() }
				val hovered by source.collectIsHoveredAsState()
				val shown = tab == chosen

				// As wide as its name, which its underline is too.
				Column(
					Modifier
						.width(IntrinsicSize.Max)
						.clickable(source, indication = null, role = Role.Tab) { onChoose(tab) }
						.testTag("tab:${label(tab)}"),
				) {
					Text(
						label(tab),
						style =
							(if (shown) type.strong else type.body).copy(
								color =
									if (shown || hovered)
										palette.text
									else
										palette.textMuted,
							),
						modifier = Modifier.padding(vertical = Space.s),
					)
					Box(
						Modifier
							.height(2.dp)
							.fillMaxWidth()
							.background(
								if (shown) palette.accent else Color.Transparent,
								RoundedCornerShape(1.dp),
							),
					)
				}
			}
		}
		Divider()
	}
}

/** Whether what moves for its own sake (a spinner) moves: not where the view is drawn to be looked at once, or tested. */
val LocalMotion = staticCompositionLocalOf { true }

/** Something under way with no end in sight: an arc going round, or, where nothing moves, standing still. */
@Composable
fun Spinner(color: Color, size: Dp = 22.dp, stroke: Dp = 3.dp) {
	var turn by remember { mutableStateOf(0f) }

	if (LocalMotion.current)
		LaunchedEffect(Unit) {
			val began = withFrameNanos { it }

			while (true)
				withFrameNanos { turn = ((it - began) / 1_000_000f * 0.36f) % 360f }
		}

	Canvas(Modifier.size(size)) {
		drawArc(
			color,
			startAngle = turn,
			sweepAngle = 270f,
			useCenter = false,
			style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
		)
	}
}

/** What a list or a page shows where there is nothing to show: a glyph, what is so, and what can be done about it. */
@Composable
fun EmptyState(
	glyph: Glyph,
	title: String,
	detail: String?,
	modifier: Modifier = Modifier,
	detailTag: String = "message-detail",
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Column(
		modifier.padding(Space.xxl),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically),
	) {
		GlyphIcon(glyph, palette.textFaint, type.line * 2.4f)
		Spacer(Modifier.height(Space.xs))
		// What is said can be selected and copied: why a folder cannot be shown, above all.
		androidx.compose.foundation.text.selection.SelectionContainer {
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(Space.xs),
			) {
				Text(
					title,
					style = type.title,
					textAlign = androidx.compose.ui.text.style.TextAlign.Center,
				)
				detail?.let {
					Text(
						it,
						style = type.muted,
						textAlign = androidx.compose.ui.text.style.TextAlign.Center,
						modifier = Modifier.widthIn(max = 480.dp).testTag(detailTag),
					)
				}
			}
		}
	}
}
