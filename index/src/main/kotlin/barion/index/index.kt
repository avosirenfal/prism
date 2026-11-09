package barion.index

import java.time.Instant

/**
 * A change to what the index holds: what reading the disk again says ([Indexer]), or what the picker says was picked.
 * Each says what is so now rather than what happened, so making one twice is making it once: an index with a
 * change made, and then made again, is the index with it made once.
 */
sealed interface Change {
	/**
	 * The folder (or file) at [tree]'s root is to be indexed, with everything under it as [tree] says: a root of the
	 * index, unless it is already held under one, in which case it is read again as [Present] reads it.
	 */
	data class Indexed(
		val tree: Block,
	) : Change

	/**
	 * The entry at [tree]'s root is now as [tree] says, with everything under it: made, changed, or read again. Not
	 * held, and dropped, where the folder it is in is not held: one the rules leave out, or one not indexed.
	 */
	data class Present(
		val tree: Block,
	) : Change

	/** Nothing is at [where] any more, nor under it. */
	data class Absent(
		val where: Where,
	) : Change

	/** The picker gave [where] at [at]: what `recent:` asks about. The latest time is what is kept. */
	data class Picked(
		val where: Where,
		val at: Instant,
	) : Change
}

/** One entry of the index, as a search finds it. */
data class Entry(
	val where: Where,
	val kind: Kind,
	val device: Device,
)

/**
 * Everything indexed: the folders asked for (its [roots]) and everything under them that the rules let in, held as
 * [Block]s of columns; and when the picker last gave each entry it gave.
 *
 * An index is a value. A change makes a new one, which shares with the old whatever did not change: a block is never
 * changed, so taking entries out hides them (each block's [Ranges]), and putting a tree in places a block of its own
 * beside the others (a graft). [compacted] makes one block of each root again, as the file is rewritten whole now and
 * then; until then, a search reads the blocks as they are, which finds the same entries.
 */
class Index private constructor(
	val roots: Set<Where>,
	internal val placed: List<Placed>,
	internal val picked: Map<Where, Instant>,
) {
	/** A block, and the entries of it no longer there. */
	internal class Placed(
		val block: Block,
		val hidden: Ranges,
	)

	/** How many entries are held. */
	val size: Int get() = placed.sumOf { it.block.size - it.hidden.entries }

	/**
	 * How far from compact the index is: the entries hidden, and the entries in blocks beyond one a root. What
	 * [compacted] does away with, and so how much it would gain.
	 */
	val loose: Int
		get() =
			placed.sumOf { it.hidden.entries } +
				placed.filter { it.block.root !in roots }.sumOf { it.block.size }

	/** The index with [change] made. */
	fun with(change: Change): Index =
		when (change) {
			is Change.Indexed -> indexed(change.tree)
			is Change.Present -> present(change.tree)
			is Change.Absent ->
				without(change.where).rooted(
					roots
						.filterNot {
							it.within(change.where)
						}.toSet(),
				)
			is Change.Picked ->
				Index(
					roots,
					placed,
					picked + (change.where to maxOf(change.at, picked[change.where] ?: change.at)),
				)
		}

	/** The index with [changes] made, in order. */
	fun with(changes: Iterable<Change>): Index = changes.fold(this, Index::with)

	private fun indexed(tree: Block): Index {
		val at = tree.root
		val heldAbove = at.parent?.let(::isFolder) == true

		return if (heldAbove || at in roots)
			present(tree)
		else
			without(at).rooted(roots.filterNot { it.within(at) }.toSet() + at).placing(tree)
	}

	private fun present(tree: Block): Index {
		val at = tree.root
		val held = at in roots || at.parent?.let(::isFolder) == true

		return if (held)
			without(at).placing(tree)
		else
			this
	}

	private fun rooted(roots: Set<Where>) = Index(roots, placed, picked)

	private fun placing(tree: Block) =
		Index(roots, placed + Placed(tree, Ranges.NONE), picked)

	/** The index without the entry at [where] and everything under it: blocks under it dropped, the rest hidden. */
	private fun without(where: Where): Index =
		Index(
			roots,
			placed.mapNotNull { it ->
				val block = it.block

				when {
					block.root.within(where) -> null
					where.within(block.root) ->
						block.locate(where, it.hidden)?.let { entry ->
							Placed(block, it.hidden.plus(entry, block.end[entry]))
						} ?: it
					else -> it
				}
			},
			picked,
		)

	/** Where the entry at [where] is held: which placed block, and which entry of it; null where it is not held. */
	internal fun locate(where: Where): Pair<Int, Int>? =
		placed.indices.firstNotNullOfOrNull { at ->
			val (block, hidden) = placed[at].let { it.block to it.hidden }

			if (where.within(block.root))
				block.locate(where, hidden)?.let { at to it }
			else
				null
		}

	private fun isFolder(where: Where): Boolean =
		locate(where)?.let { (at, entry) ->
			placed[at].block.kindOf(entry) == Kind.Directory
		} ==
			true

	/** The entry at [where], if it is held. */
	fun find(where: Where): Entry? = locate(where)?.let { (at, entry) -> entryAt(at, entry) }

	internal fun entryAt(at: Int, entry: Int): Entry {
		val block = placed[at].block

		return Entry(block.whereOf(entry), block.kindOf(entry), block.deviceOf(entry))
	}

	/** Every entry held, but `/` itself, in the order of their paths' bytes. */
	fun entries(): List<Entry> =
		compacted().placed.flatMap { placed ->
			val block = placed.block

			(0 until block.size)
				.filter { block.nameLength(it) > 0 }
				.map { Entry(block.whereOf(it), block.kindOf(it), block.deviceOf(it)) }
		}

	/** When the picker last gave [where], if it did. */
	fun pickedAt(where: Where): Instant? = picked[where]

	/**
	 * The index as one block a root, made again from what is held now, without what is hidden; what was picked kept
	 * only for entries still held. It holds the same entries as this index, and a search of it finds the same.
	 */
	fun compacted(): Index {
		if (placed.size == roots.size &&
			placed.all { it.hidden.count == 0 && it.block.root in roots }
		)
			return this

		// Each graft, by the entry it was placed under.
		val grafts =
			placed.indices
				.filter { placed[it].block.root !in roots && 0 !in placed[it].hidden }
				.groupBy { at ->
					placed[at]
						.block.root.parent
						?.let(::locate)
				}

		fun copy(builder: BlockBuilder, under: Int, at: Int, entry: Int) {
			val (block, hidden) = placed[at].let { it.block to it.hidden }

			val id =
				builder.add(
					under,
					block.names,
					block.kindOf(entry),
					block.deviceOf(entry),
					block.nameAt[entry],
					block.nameAt[entry + 1],
				)

			if (block.kindOf(entry) != Kind.Directory)
				return

			val children =
				block.childrenOf(entry, hidden).map { at to it } +
					grafts[at to entry].orEmpty().map { it to 0 }

			children
				.sortedWith { one, other ->
					val a = placed[one.first].block
					val b = placed[other.first].block

					java.util.Arrays.compareUnsigned(
						a.names,
						a.nameAt[one.second],
						a.nameAt[one.second + 1],
						b.names,
						b.nameAt[other.second],
						b.nameAt[other.second + 1],
					)
				}.forEach { (childAt, child) -> copy(builder, id, childAt, child) }
		}

		val compact =
			roots.sortedBy { it.key }.mapNotNull { root ->
				locate(root)?.let { (at, entry) ->
					Placed(BlockBuilder(root).also { copy(it, -1, at, entry) }.built(), Ranges.NONE)
				}
			}
		val held = Index(roots, compact, emptyMap())

		return Index(roots, compact, picked.filterKeys { held.locate(it) != null })
	}

	companion object {
		/** An index of nothing. */
		val EMPTY = Index(emptySet(), emptyList(), emptyMap())

		/** An index as a file holds it: [roots], one block of each, and what was picked when. */
		internal fun of(
			roots: Set<Where>,
			blocks: List<Block>,
			picked: Map<Where, Instant>,
		): Index {
			require(
				blocks
					.map {
						it.root
					}.toSet() == roots,
			) { "a compact index holds one block a root" }

			return Index(roots, blocks.map { Placed(it, Ranges.NONE) }, picked)
		}
	}
}
