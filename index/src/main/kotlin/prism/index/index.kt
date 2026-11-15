package prism.index

/**
 * A change to what the index holds: what reading the disk again says ([Indexer]). Each says what is so now rather
 * than what happened, so making one twice is making it once: an index with a change made, and then made again, is the
 * index with it made once.
 */
sealed interface Change {
	/** The place it changes: what is there, and under it, is as the change says once it is made. */
	val at: Where

	/**
	 * The folder (or file) at [tree]'s root is to be indexed, with everything under it as [tree] says: a root of the
	 * index, unless it is already held under one, in which case it is read again as [Present] reads it. A new root's
	 * entries are seen as the index began ([Seen.UNKNOWN]): being indexed says nothing of how new they are.
	 */
	data class Indexed(
		val tree: Block,
	) : Change {
		override val at: Where get() = tree.root
	}

	/**
	 * The entry at [tree]'s root is now as [tree] says, with everything under it: made, changed, or read again. Not
	 * held, and dropped, where the folder it is in is not held: one the rules leave out, or one not indexed. An entry
	 * the index held already keeps when it was first seen; one it did not is as new as [tree] says.
	 */
	data class Present(
		val tree: Block,
	) : Change {
		override val at: Where get() = tree.root
	}

	/** Nothing is at [where] any more, nor under it. */
	data class Absent(
		val where: Where,
	) : Change {
		override val at: Where get() = where
	}
}

/** An [entry] the index saw turn up, and when it did ([Index.newest]). */
data class Sighting(
	val entry: Entry,
	val at: java.time.Instant,
)

/** One entry of the index, as a search finds it. */
data class Entry(
	val where: Where,
	val kind: Kind,
	val device: Device,
	val inode: Long,
)

/**
 * Everything indexed: the folders asked for (its [roots]) and everything under them that the rules let in, held as
 * [Block]s of columns, with when each entry was first seen.
 *
 * An index is a value. A change makes a new one, which shares with the old whatever did not change: a block is never
 * changed, so taking entries out hides them (each block's [Ranges]), and putting a tree in places a block of its own
 * beside the others (a graft). [compacted] makes one block of each root again, as the file is rewritten whole now and
 * then; until then, a search reads the blocks as they are, which finds the same entries.
 */
class Index private constructor(
	val roots: Set<Where>,
	internal val placed: List<Placed>,
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
		}

	/** The index with [changes] made, in order. */
	fun with(changes: Iterable<Change>): Index = changes.fold(this, Index::with)

	private fun indexed(tree: Block): Index {
		val at = tree.root
		val heldAbove = at.parent?.let(::isFolder) == true

		return if (heldAbove || at in roots)
			present(tree)
		else
			without(at)
				.rooted(roots.filterNot { it.within(at) }.toSet() + at)
				.placing(tree.seenAs(IntArray(tree.size)))
	}

	private fun present(tree: Block): Index {
		val at = tree.root
		val held = at in roots || at.parent?.let(::isFolder) == true

		return if (held)
			without(at).placing(carried(tree))
		else
			this
	}

	private fun rooted(roots: Set<Where>) = Index(roots, placed)

	private fun placing(tree: Block) = Index(roots, placed + Placed(tree, Ranges.NONE))

	/**
	 * [tree] with each entry this index holds already seen when the index first saw it, and the rest as [tree] says:
	 * the old entries and the new walked side by side, folder by folder, each folder's children matched by name (in
	 * the order of their names' bytes, which both keep). An entry is the same entry where it has the same path and is
	 * the same kind of thing; a folder made where a file was is new.
	 */
	private fun carried(tree: Block): Block {
		val start = locate(tree.root) ?: return tree
		val seen = tree.seen.copyOf()
		val grafts = graftsUnder(tree.root)

		fun children(of: Pair<Int, Int>): List<Pair<Int, Int>> {
			val (at, entry) = of
			val within = placed[at].block.childrenOf(entry, placed[at].hidden).map { at to it }
			val grafted = grafts[of].orEmpty().map { it to 0 }

			return if (grafted.isEmpty())
				within
			else
				(within + grafted).sortedWith(::byName)
		}

		fun carry(new: Int, old: Pair<Int, Int>) {
			val block = placed[old.first].block

			if (block.kindOf(old.second) != tree.kindOf(new))
				return

			seen[new] = block.seen[old.second]

			if (tree.kindOf(new) != Kind.Directory)
				return

			val olds = children(old)
			val news = tree.childrenOf(new, Ranges.NONE)
			var i = 0
			var j = 0

			while (i < news.size && j < olds.size) {
				val (at, entry) = olds[j]
				val other = placed[at].block

				val order =
					java.util.Arrays.compareUnsigned(
						tree.names,
						tree.nameAt[news[i]],
						tree.nameAt[news[i] + 1],
						other.names,
						other.nameAt[entry],
						other.nameAt[entry + 1],
					)

				when {
					order < 0 -> i++
					order > 0 -> j++
					else -> {
						carry(news[i], olds[j])
						i++
						j++
					}
				}
			}
		}

		carry(0, start)

		return tree.seenAs(seen)
	}

	/** The grafts under [where] (or all, for null), each by the entry it is placed under. */
	private fun graftsUnder(where: Where?): Map<Pair<Int, Int>?, List<Int>> =
		placed.indices
			.filter {
				val root = placed[it].block.root

				root !in roots && 0 !in placed[it].hidden && (where == null || root.within(where))
			}.groupBy { at ->
				placed[at]
					.block.root.parent
					?.let(::locate)
			}

	/** Two entries, each a placed block's, in the order of their names' bytes. */
	private fun byName(one: Pair<Int, Int>, other: Pair<Int, Int>): Int {
		val a = placed[one.first].block
		val b = placed[other.first].block

		return java.util.Arrays.compareUnsigned(
			a.names,
			a.nameAt[one.second],
			a.nameAt[one.second + 1],
			b.names,
			b.nameAt[other.second],
			b.nameAt[other.second + 1],
		)
	}

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

	internal fun entryAt(at: Int, entry: Int): Entry = placed[at].block.entryOf(entry)

	/** Every entry held, but `/` itself, in the order of their paths' bytes. */
	fun entries(): List<Entry> =
		compacted().placed.flatMap { placed ->
			val block = placed.block

			(0 until block.size)
				.filter { block.nameLength(it) > 0 }
				.map(block::entryOf)
		}

	/**
	 * Where the folders held as [id] are: the folder a change the watcher saw happened in, which it names by the
	 * kernel's device ([Device.kernel]) and inode. Usually one; none where the index does not hold it (one the rules
	 * leave out, or one not read yet); two for a folder bind mounted in two places.
	 */
	fun foldersAt(id: FileId): List<Where> =
		placed.flatMap { placed ->
			(placed.block.folders[id] ?: IntArray(0))
				.filter { it !in placed.hidden }
				.map(placed.block::whereOf)
		}

	/**
	 * What the folder at [where] holds, as the index has it: its children, in a block or grafted beside it; null where
	 * the index does not hold a folder there.
	 */
	fun childrenOf(where: Where): List<Entry>? {
		val (at, entry) = locate(where) ?: return null
		val (block, hidden) = placed[at].let { it.block to it.hidden }

		if (block.kindOf(entry) != Kind.Directory)
			return null

		val grafted =
			placed
				.filter {
					it.block.root.parent == where && 0 !in it.hidden
				}.map { it.block.entryOf(0) }

		return block.childrenOf(entry, hidden).map(block::entryOf) + grafted
	}

	/** When the index first held the entry at [where]: null for one held since its root was indexed, or not held. */
	fun seenAt(where: Where): java.time.Instant? =
		locate(where)?.let { (at, entry) -> Seen.instantOf(placed[at].block.seen[entry]) }

	/**
	 * The [count] newest entries that [wanted] takes, the newest first: those the index first saw last, which is the
	 * order they were made in (or moved in), as the watcher said it as it happened. What a picker offers as what was
	 * lately made. What was there when a root was indexed is not among them: when it was made, the index never saw.
	 * One pass over the stamps, so cheap enough to ask for as a window opens.
	 */
	fun newest(
		count: Int,
		hiding: Hiding = Hiding.NONE,
		wanted: (Entry) -> Boolean = { true },
	): List<Sighting> {
		// The oldest of those kept is at the head, to be let go as a newer one is found.
		val kept =
			java.util.PriorityQueue<Triple<Long, Int, Int>>(
				compareBy({ it.first }, { -it.second }, { -it.third }),
			)

		placed.forEachIndexed { at, it ->
			val block = it.block
			// What a search leaves out is not what was lately made either: nothing asks for it here by its path.
			val hidden = it.hidden.plus(hiding.skipped(block, emptyList()))

			for (entry in 0 until block.size) {
				val seen = block.seen[entry]

				if (seen == Seen.UNKNOWN || block.nameLength(entry) == 0)
					continue

				val stamp = seen.toLong() and 0xFFFFFFFFL

				if (kept.size >= count && stamp <= kept.peek().first)
					continue
				if (entry in hidden || !wanted(block.entryOf(entry)))
					continue

				kept.add(Triple(stamp, at, entry))
				if (kept.size > count)
					kept.poll()
			}
		}

		return generateSequence { kept.poll() }
			.map { (stamp, at, entry) ->
				Sighting(entryAt(at, entry), java.time.Instant.ofEpochSecond(stamp))
			}.toList()
			.asReversed()
	}

	/**
	 * The [count] newest files a person would know as their own, the newest first ([newest]): files, neither hidden
	 * nor inside a hidden folder (what programs keep for themselves), nor what [hiding] leaves out of searches, whose
	 * names [named] takes. A name is asked of
	 * only for a file new enough to be among them, so a filter of names costs little over a large index.
	 */
	fun newestFiles(
		count: Int,
		hiding: Hiding = Hiding.NONE,
		named: (String) -> Boolean = { true },
	): List<Sighting> =
		newest(count, hiding) { entry ->
			entry.kind == Kind.File &&
				!entry.where.dotted &&
				entry.where.path
					?.fileName
					?.toString()
					?.let(named) == true
		}

	/**
	 * The index as one block a root, made again from what is held now, without what is hidden. It holds the same
	 * entries as this index, each seen when it was, and a search of it finds the same.
	 */
	fun compacted(): Index {
		if (placed.size == roots.size &&
			placed.all { it.hidden.count == 0 && it.block.root in roots }
		)
			return this

		// Each graft, by the entry it was placed under.
		val grafts = graftsUnder(null)

		val compact =
			roots.sortedBy { it.key }.mapNotNull { root ->
				locate(root)?.let { (at, entry) ->
					Placed(
						BlockBuilder(root).also { copy(it, -1, at, entry, grafts, null) }.built(),
						Ranges.NONE,
					)
				}
			}

		return Index(roots, compact)
	}

	/**
	 * What is held at [where], with everything under it, as one block of its own, each entry seen when it was: the
	 * same block [compacted] would make of it, were it a root. Rooted at [at], where that is somewhere else: the same
	 * tree as it would be there, which is how a file system mounted in another place than it was is put where it is
	 * now. Null where nothing is held at [where].
	 */
	fun tree(where: Where, at: Where = where): Block? =
		locate(where)?.let { (placedAt, entry) ->
			BlockBuilder(at)
				.also { copy(it, -1, placedAt, entry, graftsUnder(where), at.name) }
				.built()
		}

	/**
	 * The [entry] of the [at]th placed block, and everything under it that is not hidden, the [grafts] placed under
	 * its folders among them, added to [builder] under [under]; named [named], where that is given: the top of a tree
	 * that is to be somewhere else.
	 */
	private fun copy(
		builder: BlockBuilder,
		under: Int,
		at: Int,
		entry: Int,
		grafts: Map<Pair<Int, Int>?, List<Int>>,
		named: ByteArray?,
	) {
		val (block, hidden) = placed[at].let { it.block to it.hidden }

		val id =
			if (named != null)
				builder.add(
					under,
					named,
					block.kindOf(entry),
					block.deviceOf(entry),
					block.inodes[entry],
					seen = block.seen[entry],
				)
			else
				builder.add(
					under,
					block.names,
					block.kindOf(entry),
					block.deviceOf(entry),
					block.inodes[entry],
					block.nameAt[entry],
					block.nameAt[entry + 1],
					block.seen[entry],
				)

		if (block.kindOf(entry) != Kind.Directory)
			return

		val children =
			block.childrenOf(entry, hidden).map { at to it } +
				grafts[at to entry].orEmpty().map { it to 0 }

		children
			.sortedWith(::byName)
			.forEach { (childAt, child) -> copy(builder, id, childAt, child, grafts, null) }
	}

	companion object {
		/** An index of nothing. */
		val EMPTY = Index(emptySet(), emptyList())

		/** An index of [block] alone, as a root, without the entries [hidden] of it. */
		internal fun of(block: Block, hidden: Ranges = Ranges.NONE): Index =
			Index(setOf(block.root), listOf(Placed(block, hidden)))

		/** An index as a file holds it: [roots], and one block of each. */
		internal fun of(roots: Set<Where>, blocks: List<Block>): Index {
			require(
				blocks
					.map {
						it.root
					}.toSet() == roots,
			) { "a compact index holds one block a root" }

			return Index(roots, blocks.map { Placed(it, Ranges.NONE) })
		}
	}
}
