package prism.window

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.SemanticsOwner

/*
 * What a window's frame was laying out when it failed. Compose's own errors in layout name the node they failed on by
 * its id ("LayoutNode 1309 not found in RectList") and nothing else; its stack says only which kinds of layout it went
 * through. This finds the node the error names in the window's layout tree, and says what it and each node above it is:
 * its id, whether it is placed, its size, and its modifiers (padding, alpha, a test tag), which tell what composable it
 * is. It only reads, and only after a frame has failed.
 *
 * Compose gives no public way down its layout tree, only up it (`LayoutInfo.parentInfo`). A node's children are read by
 * the name it answers to as Compose's semantics tree (`getChildrenInfo`), which its public interface does not have;
 * where that is not there, the report says so and gives what it can.
 */

/** The roots of the layout trees under [owners]: a window's, one for each of its layers (a menu open, a tooltip). */
internal fun rootsOf(owners: Collection<SemanticsOwner>): List<LayoutInfo> =
	owners.mapNotNull { runCatching { it.rootSemanticsNode.layoutInfo }.getOrNull() }

/** A report of [error], which a frame of the window [title] failed with, its layout trees those under [roots]. */
internal fun frameReport(
	error: Throwable,
	title: String,
	roots: List<LayoutInfo>,
): String {
	val named =
		Regex(
			"""LayoutNode (\d+)""",
		).find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

	val found = named?.let { id -> roots.firstNotNullOfOrNull { findIn(it, id) } }

	return buildString {
		appendLine("prism: a frame of the window \"$title\" failed: $error")
		when {
			named == null -> appendLine("It names no layout node.")
			found == null ->
				appendLine(
					"It names layout node $named, which is not in the window's layout " +
						"(${roots.size} tree${if (roots.size == 1) "" else "s"}), or cannot be read.",
				)
			else -> {
				appendLine("Layout node $named, and each node above it:")
				generateSequence(found) { it.parentInfo }.forEach { appendLine("  ${describe(it)}") }

				val children = childrenOf(found)

				if (children.isNotEmpty()) {
					appendLine("Its children:")
					children.forEach { appendLine("  ${describe(it)}") }
				}
			}
		}
	}
}

/** [info] in a line: its id, whether it is placed, its size, and what modifies it, outermost first. */
private fun describe(info: LayoutInfo): String {
	val modifiers =
		runCatching { info.getModifierInfo().map { nameOf(it.modifier) } }
			.getOrElse { listOf("(its modifiers cannot be read: $it)") }

	val placed = if (info.isPlaced) "placed" else "not placed"

	return "#${info.semanticsId} $placed ${info.width}x${info.height}" +
		modifiers.joinToString(", ", prefix = " [", postfix = "]")
}

/**
 * What [modifier] is, in a word: the kind of modifier (`Padding`, `GraphicsLayer`, `Size`), and for a test tag the tag,
 * which says what composable it is.
 */
private fun nameOf(modifier: Modifier): String {
	val kind =
		modifier.javaClass.simpleName.removeSuffix("Element").ifEmpty {
			modifier.javaClass.name
		}

	val tag =
		runCatching {
			modifier.javaClass
				.getDeclaredField("tag")
				.apply {
					isAccessible = true
				}.get(modifier)
		}.getOrNull()

	return if (tag is String)
		"testTag($tag)"
	else
		kind
}

/** The node of [id] at or under [info]; null where it is not there, or the tree cannot be read down. */
private fun findIn(info: LayoutInfo, id: Int): LayoutInfo? =
	if (info.semanticsId ==
		id
	)
		info
	else
		childrenOf(info).firstNotNullOfOrNull { findIn(it, id) }

/** [info]'s children, as its semantics tree knows them; none where Compose does not answer to that. */
private fun childrenOf(info: LayoutInfo): List<LayoutInfo> =
	runCatching {
		(
			info.javaClass
				.getMethod(
					"getChildrenInfo",
				).invoke(info) as List<*>
		).filterIsInstance<LayoutInfo>()
	}.getOrDefault(emptyList())
