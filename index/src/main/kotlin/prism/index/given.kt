package prism.index

import java.util.Arrays
import java.util.TreeMap
import prism.linux.Dev

/*
 * An index of a tree that was given, and not read from a disk: what a command listed of an archive, say, or of the
 * files a tool found. It is an index like any other (one block of columns, in preorder, each folder's entries in the
 * order of their names' bytes), so the same search runs over it, with the same language and the same ranking; only
 * where its entries came from differs. Its root is `/`, and an entry's path in it is where it sits in the tree given.
 */

/** An entry of a tree given to be indexed: where it sits (each folder's name, then its own), and whether it is a folder. */
data class Given(
	val at: List<String>,
	val folder: Boolean,
)

/** The device every entry of a given tree is said to be on: none, since they are on no disk. */
private val NOWHERE = Device(Dev(0, 0), Dev(0, 0), null)

/**
 * An index of [entries], under `/`. A folder is held wherever something is under one, whether or not it was given
 * itself; an entry given twice is held once.
 */
fun Index.Companion.given(entries: List<Given>): Index {
	class Node(
		var folder: Boolean,
	) {
		val children = TreeMap<ByteArray, Node>(Arrays::compareUnsigned)
	}

	val root = Node(folder = true)

	for (entry in entries) {
		var node = root

		entry.at.forEachIndexed { level, name ->
			val last = level == entry.at.lastIndex

			node =
				node.children
					.getOrPut(name.encodeToByteArray()) { Node(folder = !last || entry.folder) }
					.also {
						// Something under it, or its being listed as one, makes it a folder.
						if (!last || entry.folder)
							it.folder = true
					}
		}
	}

	val builder = BlockBuilder(Where.ROOT)

	fun add(parent: Int, name: ByteArray, node: Node) {
		val id =
			builder.add(
				parent,
				name,
				if (node.folder) Kind.Directory else Kind.File,
				NOWHERE,
				inode = 0,
			)

		for ((child, under) in node.children)
			add(id, child, under)
	}

	add(-1, ByteArray(0), root)

	return of(setOf(Where.ROOT), listOf(builder.built()))
}
