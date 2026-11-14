package prism.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import prism.window.WindowLocals

/*
 * A combo box: a field that shows what is chosen, and a list that opens under it, as a desktop's editable drop-down is.
 * Clicked, it shows every option; typed into, it shows those whose names have what is typed in them, the first lit, so
 * that Enter chooses it. Its options come in groups, each set apart from the one before it, under a heading where it has
 * one, and drawn faint where they are there to be chosen but are not the likely choice. The field keeps the keyboard
 * throughout, and what is typed is the box's own: what is chosen is its caller's.
 */

/**
 * A group of a [ComboBox]'s [options], under [heading] where there is one, drawn [faint] where they can be chosen but
 * are not the likely choice (a terminal that is not installed, say).
 */
@Immutable
data class OptionGroup<T>(
	val options: List<T>,
	val heading: String? = null,
	val faint: Boolean = false,
)

/** An option as an open combo box lists it: its group's look, and whether a group begins with it. */
@Immutable
private data class Listed<T>(
	val option: T,
	val name: String,
	val faint: Boolean,
	val opens: OptionGroup<T>?,
	val first: Boolean,
)

/**
 * One of the options in [groups], each named by [label], chosen by typing into the box or from the list it opens:
 * clicked, the list shows every option; typed into, those whose names have what is typed in them. Up and Down go
 * through the list, Enter chooses the one lit, and Escape, or the keyboard leaving, puts the box back as it was. The
 * field is tagged [tag], and each option `option:` and its name.
 */
@Composable
fun <T> ComboBox(
	groups: List<OptionGroup<T>>,
	chosen: T,
	label: (T) -> String,
	modifier: Modifier = Modifier,
	tag: String = "combo",
	hint: String = "Search",
	onChoose: (T) -> Unit,
) {
	val type = LocalType.current
	// What is typed, while something is: the box shows what is chosen otherwise.
	var typed by remember { mutableStateOf<String?>(null) }
	var open by remember { mutableStateOf(false) }
	var width by remember { mutableIntStateOf(0) }
	val query = typed?.trim().orEmpty()

	val shown =
		groups.flatMap { group ->
			group.options
				.filter { query.isEmpty() || label(it).contains(query, ignoreCase = true) }
				.mapIndexed { index, option ->
					Listed(
						option,
						label(option),
						group.faint,
						group,
						index == 0,
					)
				}
		}

	var lit by remember { mutableIntStateOf(0) }
	// Clicked, or chosen from, the box's text is selected, so that what is typed next starts afresh.
	var reselect by remember { mutableIntStateOf(0) }

	// Opened, the list lights what is chosen; narrowed, the first that is left.
	LaunchedEffect(open, query) {
		lit =
			if (query.isEmpty())
				shown.indexOfFirst { it.option == chosen }.coerceAtLeast(0)
			else
				0
	}

	fun close() {
		typed = null
		open = false
	}

	fun choose(option: T) {
		close()
		reselect++
		if (option != chosen)
			onChoose(option)
	}

	fun key(key: KeyEvent): Boolean {
		if (key.type != KeyEventType.KeyDown)
			return false

		when (key.key) {
			Key.DirectionDown ->
				if (!open)
					open = true
				else
					lit = (lit + 1).coerceAtMost(shown.lastIndex)
			Key.DirectionUp -> lit = (lit - 1).coerceAtLeast(0)
			Key.Enter, Key.NumPadEnter ->
				if (open)
					shown.getOrNull(lit)?.let { choose(it.option) } ?: close()
				else
					return false
			Key.Escape ->
				if (open || typed != null)
					close()
				else
					return false
			else -> return false
		}

		return true
	}

	Box(modifier.onSizeChanged { width = it.width }) {
		Field(
			typed ?: label(chosen),
			{
				typed = it
				open = true
			},
			Modifier
				.fillMaxWidth()
				// A press anywhere on the box opens its list, and the field takes the keyboard as it would.
				.pointerInput(Unit) {
					awaitEachGesture {
						awaitFirstDown(pass = PointerEventPass.Initial)
						open = true
						reselect++
					}
				}.testTag(tag),
			hint = hint,
			trailing = Glyph.ChevronDown,
			reselect = reselect,
			onLeave = ::close,
			onKey = ::key,
		)
		if (open)
			Popup(
				popupPositionProvider = Below,
				onDismissRequest = ::close,
				// The field keeps the keyboard while the list is open: what is typed narrows it.
				properties = PopupProperties(focusable = false),
			) {
				WindowLocals {
					ComboList(
						shown,
						chosen,
						lit,
						with(
							LocalDensity.current,
						) { width.toDp() },
						onHover = {
							lit =
								it
						},
					) {
						choose(it)
					}
				}
			}
	}
}

/** The list an open [ComboBox] shows: [shown], in their groups, the one [lit] lit and the one [chosen] ticked. */
@Composable
private fun <T> ComboList(
	shown: List<Listed<T>>,
	chosen: T,
	lit: Int,
	width: androidx.compose.ui.unit.Dp,
	onHover: (Int) -> Unit,
	onChoose: (T) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.medium)
	val list = rememberLazyListState()

	LaunchedEffect(lit) {
		val visible = list.layoutInfo.visibleItemsInfo.map { it.index }

		if (lit !in visible.drop(1).dropLast(1))
			list.scrollToItem((lit - 2).coerceAtLeast(0))
	}

	Column(
		Modifier
			.width(maxOf(width, 220.dp))
			.shadow(12.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
			.background(palette.overlay, shape)
			.border(1.dp, palette.border, shape)
			.padding(Space.xs)
			.testTag("options"),
	) {
		if (shown.isEmpty())
			Text(
				"No matches",
				style = type.muted,
				modifier = Modifier.padding(Space.s),
			)
		LazyScrollArea(Modifier.heightIn(max = type.row * 10), state = list) {
			items(shown.size) { index ->
				val listed = shown[index]

				Column {
					// A group after the first is set apart by a line, and its heading, where it has one.
					if (listed.first && index > 0)
						Divider(Modifier.padding(vertical = Space.xs))
					if (listed.first)
						listed.opens?.heading?.let { ListHeading(it) }
					ComboOption(
						listed.name,
						chosen = listed.option == chosen,
						lit = index == lit,
						faint = listed.faint,
						onHover = { onHover(index) },
					) { onChoose(listed.option) }
				}
			}
		}
	}
}

/** One option of an open combo box: its name, ticked where chosen, lit where the pointer or the arrows are. */
@Composable
private fun ComboOption(
	name: String,
	chosen: Boolean,
	lit: Boolean,
	faint: Boolean,
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
				if (lit) palette.chosen else Color.Transparent,
				RoundedCornerShape(Radius.small),
			).hoverable(source)
			// Chosen without taking the keyboard from the field, which would close the list first.
			.focusProperties { canFocus = false }
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
		Text(
			name,
			style = type.body.copy(color = if (faint) palette.textFaint else palette.text),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}
