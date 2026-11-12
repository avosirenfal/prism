package prism.window

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import java.nio.file.Path

/*
 * Drag and drop, as a window takes it: things dragged over it from another program or from one of this process's
 * own windows, each place that takes drops told as the pointer comes over it and goes, and given what is dropped
 * when it is let go there. The innermost place under the pointer is the one told: a row before the list it is in.
 */

/** The keys held during a drag: what asks a drop to copy, move or link, as each platform has it. */
data class DragKeys(
	val ctrl: Boolean,
	val shift: Boolean,
	val alt: Boolean,
	val meta: Boolean,
) {
	val any: Boolean get() = ctrl || shift || alt || meta

	companion object {
		val None = DragKeys(ctrl = false, shift = false, alt = false, meta = false)
	}
}

/** Something dragged over a place: from this process ([local], what its drag carries, and the keys held), or not. */
sealed interface DragOver {
	/** A drag of this process's own: what it carries, and the keys held now. */
	data class Own(
		val local: Any,
		val keys: DragKeys,
	) : DragOver

	/** A drag from another program: what it carries is known only once it is dropped. */
	data object Foreign : DragOver
}

/** What is let go on a place. */
sealed interface Dropped {
	/** Files, from another program. */
	data class Files(
		val paths: List<Path>,
	) : Dropped

	/** Text, from another program. */
	data class Text(
		val text: String,
	) : Dropped

	/** What a drag of this process's own carries, and the keys held as it was let go. */
	data class Own(
		val local: Any,
		val keys: DragKeys,
	) : Dropped
}

/** A place things can be dropped on. */
interface DropTarget {
	/** What is dragged over it now; null as it leaves, or the drag ends anywhere. */
	fun hover(over: DragOver?)

	/** [dropped] let go on it; whether it took it. */
	fun drop(dropped: Dropped): Boolean
}

/** This a place things can be dropped on, as [target] says. */
fun Modifier.dropTarget(target: DropTarget): Modifier =
	this then DropTargetElement(target)

private data class DropTargetElement(
	val target: DropTarget,
) : ModifierNodeElement<DropTargetNode>() {
	override fun create(): DropTargetNode = DropTargetNode(target)

	override fun update(node: DropTargetNode) {
		node.target = target
	}

	override fun InspectorInfo.inspectableProperties() {
		name = "dropTarget"
	}
}

internal class DropTargetNode(
	var target: DropTarget,
) : Modifier.Node(),
	GlobalPositionAwareModifierNode,
	CompositionLocalConsumerModifierNode {
	var coordinates: LayoutCoordinates? = null
		private set

	private var drops: Drops? = null

	override fun onAttach() {
		drops = currentValueOf(LocalNativeWindow)?.drops?.also { it.add(this) }
	}

	override fun onDetach() {
		drops?.remove(this)
		drops = null
		coordinates = null
	}

	override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
		this.coordinates = coordinates
	}
}

/** A window's places things can be dropped on, and which of them a drag is over now. */
internal class Drops {
	private val targets = LinkedHashSet<DropTargetNode>()
	private var over: DropTargetNode? = null

	fun add(node: DropTargetNode) {
		targets += node
	}

	fun remove(node: DropTargetNode) {
		targets -= node
		if (over === node)
			over = null
	}

	/** The innermost place at [position] (in the window's pixels): the smallest of those whose bounds hold it. */
	fun at(position: Offset): DropTargetNode? =
		targets
			.mapNotNull { node ->
				node.coordinates
					?.takeIf { it.isAttached }
					?.boundsInWindow()
					?.takeIf { position in it }
					?.let { node to it.width * it.height }
			}.minByOrNull { it.second }
			?.first

	/** A drag over the window at [position], carrying [drag]: the place under it told, and the one it left. */
	fun hover(position: Offset, drag: DragOver) {
		val now = at(position)

		if (now !== over)
			over?.target?.hover(null)
		over = now
		now?.target?.hover(drag)
	}

	/** The drag gone from the window, or ended, without a drop. */
	fun leave() {
		over?.target?.hover(null)
		over = null
	}

	/** [dropped] let go at [position]: given to the place there; whether it took it. */
	fun drop(position: Offset, dropped: Dropped): Boolean {
		val target = at(position)

		leave()

		return target?.target?.drop(dropped) ?: false
	}
}
