package prism.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * A result's actions, as a menu over it: what Listary shows for the right arrow on a result, and Raycast in its
 * action panel. A window that lists results (an explorer's search, the launcher) keeps which menu is open, of which
 * result, in its state ([ActionMenu], with the result beside it), so that its reducer says what the arrows and Enter
 * do while one is; and draws it in its own window, by the result's row ([ActionMenuLayer], [ActionMenuPanel]). The
 * keyboard stays where it was, in the window's field.
 */

/**
 * The actions of a menu that is open, in the order they are listed in, and the one the arrows are [on]. There is no
 * menu of no actions ([of]), and the arrows are never past either end of one, so there is always a [current] action.
 */
@Immutable
class ActionMenu<A> private constructor(
	val actions: List<A>,
	val on: Int,
) {
	/** The action the arrows are on: what Enter does. */
	val current: A get() = actions[on]

	/** This, with the arrows [by] actions down, or up where negative, stopping at either end. */
	fun moved(by: Int): ActionMenu<A> =
		ActionMenu(actions, (on + by).coerceIn(0, actions.lastIndex))

	/** This, with the arrows on its first action, or its [last]. */
	fun movedTo(last: Boolean): ActionMenu<A> =
		ActionMenu(
			actions,
			if (last)
				actions.lastIndex
			else
				0,
		)

	override fun equals(other: Any?): Boolean =
		other is ActionMenu<*> && other.actions == actions && other.on == on

	override fun hashCode(): Int = actions.hashCode() * 31 + on

	override fun toString(): String = "ActionMenu($actions, on $on)"

	companion object {
		/** A menu of [actions], the arrows on the first; none, of no actions. */
		fun <A> of(actions: List<A>): ActionMenu<A>? =
			actions.takeIf { it.isNotEmpty() }?.let { ActionMenu(it, 0) }
	}
}

/** An action as its menu says it: what it does, and the keys that do the same without the menu, each on a cap. */
@Immutable
data class MenuLine(
	val label: String,
	val keys: List<String> = emptyList(),
)

/** The sizes of an actions menu. */
object ActionMenuDims {
	/** A menu is as wide as its longest line, between these. */
	val narrowest = 220.dp
	val widest = 440.dp
	val row = 32.dp

	/** How far from its window's edges a menu stays, and from the row it is of. */
	val margin = 8.dp
	val gap = 2.dp
}

/**
 * A menu of [lines], the arrows on the one at [highlighted]: each a row, tinted under the pointer, done by a click
 * ([onChoose]).
 */
@Composable
fun ActionMenuPanel(
	lines: List<MenuLine>,
	highlighted: Int,
	onChoose: (Int) -> Unit,
	modifier: Modifier = Modifier,
	tag: String = "actions",
) {
	val palette = LocalPalette.current
	val shape = RoundedCornerShape(Radius.medium)

	Column(
		modifier
			.widthIn(ActionMenuDims.narrowest, ActionMenuDims.widest)
			.width(IntrinsicSize.Max)
			.shadow(16.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
			.clip(shape)
			.background(palette.overlay)
			.border(1.dp, palette.borderStrong, shape)
			// A tap in the menu is not a tap outside it.
			.pointerInput(Unit) { detectTapGestures { } }
			.padding(Space.xs)
			.testTag(tag),
	) {
		lines.forEachIndexed { at, line ->
			MenuRow(line, at == highlighted, Modifier.testTag("$tag:${line.label}")) {
				onChoose(at)
			}
		}
	}
}

@Composable
private fun MenuRow(
	line: MenuLine,
	highlighted: Boolean,
	modifier: Modifier,
	onClick: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()

	Row(
		Modifier
			.fillMaxWidth()
			.height(ActionMenuDims.row)
			.clip(RoundedCornerShape(Radius.small))
			.background(
				when {
					highlighted -> palette.chosen
					hovered -> palette.hover
					else -> Color.Transparent
				},
			).hoverable(hover)
			.clickable(onClick = onClick)
			.semantics { selected = highlighted }
			.then(modifier)
			.padding(horizontal = Space.m),
		horizontalArrangement = Arrangement.spacedBy(Space.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			line.label,
			style = type.body,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f).padding(end = Space.m),
		)
		for (key in line.keys)
			KeyCap(key)
	}
}

/**
 * [menu] drawn over everything this fills, by the row at [anchor] (where it is in the window, as [ResultList] says):
 * under the row, at its right; over it, where there is no room under; and never past an edge of what this fills. Until
 * the row has said where it is, there is nowhere to draw the menu, and it is not drawn. A tap anywhere else puts the
 * menu away ([onDismiss]) and does nothing more.
 */
@Composable
fun ActionMenuLayer(anchor: Rect?, onDismiss: () -> Unit, menu: @Composable () -> Unit) {
	var origin by remember { mutableStateOf(Offset.Zero) }

	Box(
		Modifier
			.fillMaxSize()
			.onGloballyPositioned { origin = it.positionInRoot() }
			.pointerInput(Unit) { detectTapGestures { onDismiss() } }
			.testTag("actions-layer"),
	) {
		if (anchor == null)
			return@Box

		Box(
			Modifier.layout { measurable, constraints ->
				val placed = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
				val margin = ActionMenuDims.margin.roundToPx()
				val gap = ActionMenuDims.gap.roundToPx()
				val row = anchor.translate(-origin)
				val under = row.bottom.roundToInt() + gap
				val over = row.top.roundToInt() - gap - placed.height

				val y =
					if (under + placed.height <= constraints.maxHeight - margin || over < margin)
						under
					else
						over

				layout(constraints.maxWidth, constraints.maxHeight) {
					placed.place(
						(row.right.roundToInt() - margin - placed.width)
							.coerceAtMost(constraints.maxWidth - margin - placed.width)
							.coerceAtLeast(margin),
						y
							.coerceAtMost(constraints.maxHeight - margin - placed.height)
							.coerceAtLeast(margin),
					)
				}
			},
		) { menu() }
	}
}
