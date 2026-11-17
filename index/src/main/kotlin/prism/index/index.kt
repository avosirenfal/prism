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
	fun with(change: Change): Index = with(listOf(change))

	/** The index with [changes] made, in order. */
	fun with(changes: Iterable<Change>): Index =
		changing().also { changes.forEach(it::make) }.index()

	/** This index, to make many changes to at once: what reading its file does, a frame of them after another. */
	internal fun changing(): Changing = Changing(this)

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
	private fun byName(one: Pair<Int, Int>, other: Pair<Int, Int>): Int =
		named(placed[one.first].block, one.second, placed[other.first].block, other.second)

	/** Where the entry at [where] is held: which placed block, and which entry of it; null where it is not held. */
	internal fun locate(where: Where): Pair<Int, Int>? =
		placed.indices.firstNotNullOfOrNull { at ->
			val (block, hidden) = placed[at].let { it.block to it.hidden }

			if (where.within(block.root))
				block.locate(where, hidden)?.let { at to it }
			else
				null
		}

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

	/**
	 * An index as changes are made to it, one after another: the blocks it holds, each by where its root is, so that
	 * a change finds the block it is made in without looking at every block there is. An index made anew for each
	 * change looks at them all each time, which is nothing for one change and everything for the thousands a file
	 * holds after a day: reading it then took as long as their number squared.
	 *
	 * It is changed, and is its maker's alone, which keeps it to one thread and lets go of it once it has the index it
	 * came to ([index]): an [Index] is a value still, and this is how one is made.
	 *
	 * What it relies on is what an index always keeps: a tree is held live in one block alone. Placing a tree drops
	 * every block at its root or under it, and hides the tree where a block above held it, so that of the blocks whose
	 * roots are above a place, at most one still has it.
	 */
	internal class Changing(
		index: Index,
	) {
		private var roots: Set<Where> = index.roots

		/** The blocks held, in the order they were placed; null where one has been dropped since. */
		private val blocks = ArrayList<Block?>()

		/** What of each block is no longer there. */
		private val hidden = ArrayList<Hides>()

		/** Which block is rooted where, by the place's bytes: in their order, so that what is under a place is together. */
		private val rooted = java.util.TreeMap<String, Int>()

		init {
			index.placed.forEach { place(it.block, it.hidden) }
		}

		/** The index these changes have come to. */
		fun index(): Index =
			Index(
				roots,
				blocks.indices.mapNotNull { at ->
					blocks[at]?.let { Placed(it, hidden[at].ranges()) }
				},
			)

		fun make(change: Change) {
			when (change) {
				is Change.Indexed -> indexed(change.tree)
				is Change.Present -> present(change.tree)
				is Change.Absent -> {
					without(change.where)
					roots = roots.filterNot { it.within(change.where) }.toSet()
				}
			}
		}

		private fun indexed(tree: Block) {
			val at = tree.root
			val heldAbove = at.parent?.let(::isFolder) == true

			if (heldAbove || at in roots) {
				present(tree)
			} else {
				without(at)
				roots = roots.filterNot { it.within(at) }.toSet() + at
				place(tree.seenAs(IntArray(tree.size)), Ranges.NONE)
			}
		}

		private fun present(tree: Block) {
			val at = tree.root

			if (at in roots || at.parent?.let(::isFolder) == true) {
				// Each entry seen when it was, read from what is held before that is taken away.
				val carried = carried(tree)

				without(at)
				place(carried, Ranges.NONE)
			}
		}

		private fun place(block: Block, gone: Ranges) {
			rooted[block.root.key] = blocks.size
			blocks += block
			hidden += Hides(gone)
		}

		/** The blocks rooted at [where] or under it. */
		private fun under(where: Where): List<Int> =
			if (where.isRoot)
				rooted.values.toList()
			else
				// What is under it starts with its bytes and a slash; the byte after a slash is the first that does not.
				listOfNotNull(rooted[where.key]) +
					rooted.subMap(where.key + SLASH, where.key + AFTER_SLASH).values

		/** Without the entry at [where] and everything under it: blocks under it dropped, and it hidden where it is held. */
		private fun without(where: Where) {
			under(where).forEach { at ->
				blocks[at]?.let { rooted.remove(it.root.key) }
				blocks[at] = null
			}
			locate(where)?.let { (at, entry) ->
				blocks[at]?.let { hidden[at].hide(entry, it.end[entry]) }
			}
		}

		/**
		 * Where the entry at [where] is held: which block, and which entry of it. Of the blocks rooted at it or above
		 * it, the one that still has it, the nearest looked in first.
		 */
		private fun locate(where: Where): Pair<Int, Int>? {
			// Its own bytes, then those of each folder above it, and the root's.
			val above =
				generateSequence(where.key) {
					it.substring(0, it.lastIndexOf(SLASH)).takeIf(String::isNotEmpty)
				} + ROOT_KEY

			return above.distinct().firstNotNullOfOrNull { place ->
				rooted[place]?.let { at ->
					blocks[at]?.locate(where, hidden[at])?.let { at to it }
				}
			}
		}

		private fun isFolder(where: Where): Boolean =
			locate(where)?.let { (at, entry) -> blocks[at]?.kindOf(entry) == Kind.Directory } ==
				true

		/** The grafts at [where] or under it, each by the entry it is placed under. */
		private fun graftsUnder(where: Where): Map<Pair<Int, Int>?, List<Int>> =
			under(where)
				.filter { blocks[it]?.root?.let { root -> root !in roots } == true }
				.sorted()
				.groupBy { at -> blocks[at]?.root?.parent?.let(::locate) }

		/**
		 * [tree] with each entry held already seen when the index first saw it, and the rest as [tree] says: the old
		 * entries and the new walked side by side, folder by folder, each folder's children matched by name (in the
		 * order of their names' bytes, which both keep). An entry is the same entry where it has the same path and is
		 * the same kind of thing; a folder made where a file was is new.
		 */
		private fun carried(tree: Block): Block {
			val start = locate(tree.root) ?: return tree
			val seen = tree.seen.copyOf()
			val grafts = graftsUnder(tree.root)

			/** The block placed [at], which is held: what was located in is. */
			fun held(at: Int): Block =
				checkNotNull(blocks[at]) { "an entry was found in a block that is gone" }

			fun children(of: Pair<Int, Int>): List<Pair<Int, Int>> {
				val (at, entry) = of
				val within = held(at).childrenOf(entry, hidden[at]).map { at to it }
				val grafted = grafts[of].orEmpty().map { it to 0 }

				return if (grafted.isEmpty())
					within
				else
					(within + grafted).sortedWith { one, other ->
						named(held(one.first), one.second, held(other.first), other.second)
					}
			}

			fun carry(new: Int, old: Pair<Int, Int>) {
				val block = held(old.first)

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
					val order = named(tree, news[i], held(at), entry)

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

		private companion object {
			const val SLASH = '/'

			/** The character after a slash: what the bytes of everything under a place come before. */
			const val AFTER_SLASH = '0'
			const val ROOT_KEY = "/"
		}
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

/** The entry [one] of [a] and the entry [other] of [b], in the order of their names' bytes. */
private fun named(a: Block, one: Int, b: Block, other: Int): Int =
	java.util.Arrays.compareUnsigned(
		a.names,
		a.nameAt[one],
		a.nameAt[one + 1],
		b.names,
		b.nameAt[other],
		b.nameAt[other + 1],
	)
