package prism.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import prism.window.WindowLocals

/*
 * How prism's windows are laid out, as parts that space themselves: a page and its sections; a card, which sets a line
 * between each two things in it; a row of a card that sets something, with its title, what it does, and its control; a
 * field with its label and what it is for; a row of a list that is chosen; a row of buttons, always in the same order;
 * and a modal over a window. None takes padding from its caller: each keeps the room the spacing scale gives its part,
 * so that two pages built from them are spaced alike, and nothing is too close to anything.
 */

/** The room inside a card, around each of its rows. */
val CardInsets = PaddingValues(horizontal = Space.l, vertical = Space.m)

/** How wide a page's content grows, at most: rows far wider than this are harder to read across, not easier. */
private val PAGE_WIDTH = 820.dp

/**
 * A page of a window: its [title], what it is for ([description]), then its sections, a section's room apart. Its
 * content scrolls, with its bar beside it, unless [scrolls] is false, for a page that has a list of its own to scroll.
 */
@Composable
fun Page(
	title: String,
	description: String? = null,
	modifier: Modifier = Modifier,
	scrolls: Boolean = true,
	content: @Composable ColumnScope.() -> Unit,
) {
	val type = LocalType.current

	val room =
		PaddingValues(
			start = Space.xl,
			end = Space.xl - Dims.scrollbarRoom + Space.xs,
			top = Space.xl,
			bottom = Space.xl,
		)

	@Composable
	fun Body(modifier: Modifier) =
		Column(
			modifier.widthIn(max = PAGE_WIDTH),
			verticalArrangement = Arrangement.spacedBy(Space.xl),
		) {
			Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
				Text(title, style = type.headline)
				description?.let { Text(it, style = type.muted) }
			}
			content()
		}

	if (scrolls)
		ScrollArea(modifier.fillMaxSize(), padding = room, tag = "page") { Body(Modifier) }
	else
		Box(modifier.fillMaxSize().padding(room).padding(end = Dims.scrollbarRoom)) {
			Body(Modifier.fillMaxSize())
		}
}

/**
 * A section of a page: its [title], what it is for, anything that governs it all at the end of its title's line
 * ([trailing]: a switch, a choice of view), and what it holds, under them.
 */
@Composable
fun Section(
	title: String,
	modifier: Modifier = Modifier,
	description: String? = null,
	trailing: (@Composable RowScope.() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	val type = LocalType.current

	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.l),
		) {
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
				Text(title, style = type.title)
				description?.let { Text(it, style = type.muted) }
			}
			trailing?.invoke(this)
		}
		content()
	}
}

/**
 * A card: a raised surface with rounded corners, what is in it one thing under another, and a line between each two
 * of them. What is in it keeps its own room ([CardInsets], as [SettingRow] and [CardItem] do), so that a row of a list
 * in a card can be lit across its whole width.
 */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	val palette = LocalPalette.current
	val shape = RoundedCornerShape(Radius.large)
	val lines = remember { DividerLines() }

	Layout(
		content = content,
		modifier =
			modifier
				.fillMaxWidth()
				.background(palette.raised, shape)
				.border(1.dp, palette.border, shape)
				.clip(shape)
				.drawBehind {
					lines.at.forEach { y ->
						drawLine(palette.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
					}
				},
	) { measurables, constraints ->
		val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
		val tops = placeables.runningFold(0) { top, placed -> top + placed.height }

		lines.at = tops.drop(1).dropLast(1).map { it.toFloat() }
		layout(
			constraints.maxWidth,
			tops.last().coerceIn(constraints.minHeight, constraints.maxHeight),
		) {
			placeables.forEachIndexed { index, placed -> placed.place(0, tops[index]) }
		}
	}
}

/** Where the lines between a card's rows are, as the card was last laid out, for it to draw them there. */
private class DividerLines {
	var at: List<Float> = emptyList()
}

/** Anything in a card, kept the card's room inside its edges. */
@Composable
fun CardItem(
	modifier: Modifier = Modifier,
	content: @Composable ColumnScope.() -> Unit,
) {
	Column(
		modifier.fillMaxWidth().padding(CardInsets),
		verticalArrangement = Arrangement.spacedBy(Space.s),
		content = content,
	)
}

/**
 * A row of a card that sets something: its [title], what it does ([description]), and the [control] that sets it, at
 * the row's end. A row that is not [enabled] is dimmed, its control with it.
 */
@Composable
fun SettingRow(
	title: String,
	modifier: Modifier = Modifier,
	description: String? = null,
	enabled: Boolean = true,
	control: @Composable RowScope.() -> Unit,
) {
	val type = LocalType.current

	Row(
		modifier
			.fillMaxWidth()
			.heightIn(min = type.control + Space.m * 2)
			.alpha(if (enabled) 1f else 0.5f)
			.padding(CardInsets),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.l),
	) {
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
			Text(title, style = type.body)
			description?.let { Text(it, style = type.muted) }
		}
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
			content = control,
		)
	}
}

/** A labelled control: its [label], the control under it, and what it is for ([help]) under that, kept together. */
@Composable
fun Labelled(
	label: String,
	modifier: Modifier = Modifier,
	help: String? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	val type = LocalType.current

	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
		Text(label, style = type.strong)
		content()
		help?.let { Text(it, style = type.muted) }
	}
}

/**
 * A row of a list that is chosen by a click: its [glyph], its [title] and what is said of it ([subtitle]), and anything
 * at its end ([trailing]). The one [chosen] is lit; the pointer lights the rest as it passes. A double click is
 * [onDoubleClick], where there is one: what choosing it and going on would be.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ListRow(
	title: String,
	modifier: Modifier = Modifier,
	chosen: Boolean = false,
	glyph: Glyph? = null,
	subtitle: String? = null,
	trailing: (@Composable RowScope.() -> Unit)? = null,
	onDoubleClick: (() -> Unit)? = null,
	onClick: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val hovered by source.collectIsHoveredAsState()

	Row(
		modifier
			.fillMaxWidth()
			.heightIn(min = type.row)
			.background(
				when {
					chosen -> palette.chosen
					hovered -> palette.hover
					else -> Color.Transparent
				},
				RoundedCornerShape(Radius.medium),
			).hoverable(source)
			.combinedClickable(
				source,
				indication = null,
				onDoubleClick = onDoubleClick,
				onClick = onClick,
			).semantics { selected = chosen }
			.padding(horizontal = Space.s, vertical = Space.xs),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		glyph?.let {
			GlyphIcon(
				it,
				if (chosen)
					palette.accentText
				else
					palette.textMuted,
				type.icon,
			)
		}
		Column(Modifier.weight(1f)) {
			Text(title, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
			subtitle?.let {
				Text(it, style = type.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
		trailing?.invoke(this)
	}
}

/** How a [Notice] reads: as it should be ([Good]), as it should not be ([Bad]), or neither ([Plain]). */
enum class Tone {
	Good,
	Bad,
	Plain,
}

/**
 * A line of a card that says how something is, rather than setting it: a glyph for its [tone] (a tick, a warning, an
 * i), its [title], in red where it is [Tone.Bad], what more there is to say ([detail]), and under them whatever
 * [content] adds.
 */
@Composable
fun Notice(
	title: String,
	tone: Tone,
	modifier: Modifier = Modifier,
	detail: String? = null,
	content: @Composable ColumnScope.() -> Unit = {},
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val (glyph, tint) =
		when (tone) {
			Tone.Good -> Glyph.Check to palette.accentText
			Tone.Bad -> Glyph.Warning to palette.dangerText
			Tone.Plain -> Glyph.Info to palette.textMuted
		}

	Row(
		modifier.fillMaxWidth().padding(CardInsets),
		horizontalArrangement = Arrangement.spacedBy(Space.m),
	) {
		GlyphIcon(glyph, tint, type.icon)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
			Text(
				title,
				style =
					type.strong.copy(
						color =
							if (tone ==
								Tone.Bad
							)
								palette.dangerText
							else
								palette.text,
					),
			)
			detail?.let { Text(it, style = type.muted) }
			content()
		}
	}
}

/** A group's heading in a list: small and faint, with room above it to set it apart from the group before. */
@Composable
fun ListHeading(text: String, modifier: Modifier = Modifier) {
	Text(
		text,
		style = LocalType.current.heading,
		maxLines = 1,
		modifier = modifier.padding(start = Space.s, top = Space.m, bottom = Space.xs),
	)
}

/** A button of a [ButtonRow]: its words, its tag, whether it can be pressed, the keyboard's way to it, what it does. */
@Immutable
data class Act(
	val label: String,
	val tag: String,
	val enabled: Boolean = true,
	val focus: FocusRequester? = null,
	val onClick: () -> Unit,
)

/**
 * The buttons at the foot of a dialog or a page, always in the same order, as a desktop's are: those that are aside
 * from what it is for ([aside]: Reset, Copy) at its start; then, at its end, the ways out of it ([secondary]: Cancel,
 * Close), and last, at the far end, what it is for ([primary]), which is [Emphasis.Danger] where it cannot be undone.
 */
@Composable
fun ButtonRow(
	primary: Act?,
	modifier: Modifier = Modifier,
	secondary: List<Act> = emptyList(),
	aside: List<Act> = emptyList(),
	danger: Boolean = false,
) {
	@Composable
	fun Button(act: Act, emphasis: Emphasis) =
		PushButton(
			act.label,
			emphasis,
			act.enabled,
			Modifier.testTag(act.tag),
			act.focus ?: remember { FocusRequester() },
			onClick = act.onClick,
		)

	Row(
		modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		aside.forEach { Button(it, Emphasis.Quiet) }
		Spacer(Modifier.weight(1f))
		secondary.forEach { Button(it, Emphasis.Secondary) }
		primary?.let { Button(it, if (danger) Emphasis.Danger else Emphasis.Primary) }
	}
}

/** How wide a modal is: a few words, a paragraph and a field, or a list. */
enum class ModalWidth(
	val width: Dp,
) {
	Small(420.dp),
	Medium(540.dp),
	Large(640.dp),
}

/**
 * A modal over the window: a scrim over what is behind it, which nothing can be reached through, and a card with its
 * [title] (after [leading], a glyph or a spinner), what it holds ([content]), and its [buttons]. The keyboard starts at
 * [focus], or in the card. Escape, and a click outside it where [outside] allows, are [onDismiss].
 */
@Composable
fun Modal(
	title: String,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
	width: ModalWidth = ModalWidth.Medium,
	outside: Boolean = true,
	tag: String = "",
	focus: FocusRequester? = null,
	leading: (@Composable () -> Unit)? = null,
	buttons: @Composable () -> Unit,
	content: @Composable ColumnScope.() -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.large)

	// The modal covers the whole window, so there is no outside of it to click: a press on the scrim is heard below, and
	// so is Escape, wherever in the modal the keyboard is.
	Popup(
		alignment = Alignment.Center,
		onDismissRequest = onDismiss,
		properties =
			PopupProperties(
				focusable = true,
				dismissOnBackPress = false,
				dismissOnClickOutside = false,
			),
	) {
		val card = remember { FocusRequester() }

		WindowLocals {
			Box(
				Modifier
					.fillMaxSize()
					.background(palette.scrim)
					.onKeyEvent {
						(it.type == KeyEventType.KeyDown && it.key == Key.Escape).also { escaped ->
							if (escaped)
								onDismiss()
						}
					}.then(
						if (outside)
							Modifier.pointerInput(Unit) {
								awaitPointerEventScope {
									while (true) {
										val event = awaitPointerEvent()

										// A press that nothing in the card took is a press outside it.
										if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press &&
											event.changes.none { it.isConsumed }
										)
											onDismiss()
									}
								}
							}
						else
							Modifier,
					).then(
						if (tag.isEmpty())
							Modifier
						else
							Modifier.testTag(tag),
					),
				contentAlignment = Alignment.Center,
			) {
				Column(
					modifier
						.padding(Space.xl)
						.width(width.width)
						.shadow(24.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
						.background(palette.overlay, shape)
						.border(1.dp, palette.border, shape)
						// A press on the card is the card's, and not one outside it.
						.pointerInput(Unit) {
							awaitPointerEventScope {
								while (true)
									awaitPointerEvent().changes.forEach {
										if (it.pressed && !it.previousPressed)
											it.consume()
									}
							}
						}
						// Where the keyboard is when nothing in it was given it: Escape is heard there too.
						.focusRequester(card)
						.focusable()
						.padding(Space.xl),
					verticalArrangement = Arrangement.spacedBy(Space.m),
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.m),
					) {
						leading?.invoke()
						androidx.compose.foundation.text.selection.SelectionContainer {
							Text(title, style = type.title)
						}
					}
					content()
					Spacer(Modifier.heightIn(min = Space.xs))
					buttons()
				}
			}
			LaunchedEffect(Unit) { (focus ?: card).requestFocus() }
		}
	}
}
