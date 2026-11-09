package barion.index

/**
 * A tree of entries, held as columns: one array per thing known of an entry, entry 0 the tree's root, at [root].
 *
 * Entries are in preorder: each comes before everything under it, which follows it at once. So everything under an
 * entry is the range of entries from just after it to its [end], and a folder and all it holds are a slice of every
 * column. Siblings are in the order of their names' bytes, so a tree makes the same block however it was read.
 *
 * Kept, and written to disk: each entry's [parent] (-1 for the root), its name (the names back to back in [names],
 * each from its [nameAt] to the next), its [kinds] (a [Kind]'s ordinal), and the [Device] it is on (an index into
 * [devices]). Worked out from those as the block is made, and never written: each entry's [end], each name [folded] to
 * lower case, and each name's [stem] length.
 *
 * The root's name is the last level of [root]: empty for `/`, which is the one entry without a name, and never a
 * result of a search.
 */
class Block internal constructor(
	val root: Where,
	internal val parent: IntArray,
	internal val nameAt: IntArray,
	internal val names: ByteArray,
	internal val kinds: ByteArray,
	internal val inodes: LongArray,
	internal val device: ShortArray,
	val devices: List<Device>,
) {
	val size: Int get() = parent.size

	/** Where what is under each entry ends: the first entry after it that is not under it. */
	internal val end: IntArray = IntArray(size) { it + 1 }

	/** The names, folded to lower case ([Fold]), at the same offsets as [names]. */
	internal val folded: ByteArray = ByteArray(names.size)

	/** How many bytes of each name are its stem: the name up to its extension ([stemLength]). */
	internal val stem: ShortArray = ShortArray(size)

	/** The names of the root's ancestors, from the top: the levels of [root] above the root itself. */
	internal val above: List<ByteArray> = root.levels.dropLast(1)

	init {
		require(size > 0) { "a block holds at least its root" }
		require(
			kinds.size == size && inodes.size == size && device.size == size &&
				nameAt.size == size + 1,
		) {
			"a block's columns are as long as each other"
		}
		require(nameAt[0] == 0 && nameAt[size] == names.size) {
			"a block's names are back to back"
		}
		require(parent[0] == -1) { "a block's first entry is its root" }
		require(
			nameOf(0).contentEquals(root.name),
		) { "a block's root is named as its place is" }
		require(devices.size <= 0xFFFF) { "a block is on at most ${0xFFFF} devices" }

		val open = IntBuilder()

		for (entry in 0 until size) {
			val name = nameAt[entry + 1] - nameAt[entry]

			require(
				name in (
					if (entry ==
						0
					) 0 else 1
				)..Short.MAX_VALUE,
			) { "entry $entry's name is ${name}B long" }
			require(kinds[entry] in 0 until KINDS.size) { "entry $entry is of no kind" }
			require((device[entry].toInt() and 0xFFFF) < devices.size) {
				"entry $entry is on no device"
			}

			if (entry > 0) {
				// Preorder: an entry's parent is a folder it is still under.
				var top = open.size - 1

				while (top >= 0 && open[top] != parent[entry])
					top--
				require(top >= 0) { "entry $entry is not under its parent ${parent[entry]}" }
				require(KINDS[kinds[parent[entry]].toInt()] == Kind.Directory) {
					"entry $entry is in something not a folder"
				}
				open.truncate(top + 1)
			}
			open.add(entry)

			Fold.fold(names, nameAt[entry], nameAt[entry + 1], folded, nameAt[entry])
			stem[entry] = stemLength(names, nameAt[entry], nameAt[entry + 1]).toShort()
		}

		for (entry in size - 1 downTo 1)
			end[parent[entry]] = maxOf(end[parent[entry]], end[entry])
	}

	internal fun kindOf(entry: Int): Kind = KINDS[kinds[entry].toInt()]

	internal fun deviceOf(entry: Int): Device = devices[device[entry].toInt() and 0xFFFF]

	/**
	 * The block's folders by device and inode: how a change the watcher saw, which names the folder it happened in by
	 * those two numbers, finds the folder. Made the first time it is asked for. Usually one folder each; a folder bind
	 * mounted elsewhere too is two.
	 */
	internal val folders: Map<FileId, IntArray> by lazy {
		(0 until size)
			.filter { kindOf(it) == Kind.Directory }
			.groupBy { FileId(deviceOf(it).dev, inodes[it]) }
			.mapValues { (_, entries) -> entries.toIntArray() }
	}

	internal fun entryOf(entry: Int): Entry =
		Entry(whereOf(entry), kindOf(entry), deviceOf(entry), inodes[entry])

	internal fun nameOf(entry: Int): ByteArray =
		names.copyOfRange(
			nameAt[entry],
			nameAt[
				entry +
					1,
			],
		)

	internal fun nameLength(entry: Int): Int = nameAt[entry + 1] - nameAt[entry]

	private fun named(entry: Int, name: ByteArray): Boolean =
		nameLength(entry) == name.size &&
			java.util.Arrays.equals(names, nameAt[entry], nameAt[entry + 1], name, 0, name.size)

	/** The child of [folder] named [name] that is not [hidden], if there is one. */
	internal fun childNamed(folder: Int, name: ByteArray, hidden: Ranges): Int? {
		var child = folder + 1

		while (child < end[folder]) {
			if (child !in hidden && named(child, name))
				return child
			child = end[child]
		}

		return null
	}

	/** The children of [folder] that are not [hidden], in order. */
	internal fun childrenOf(folder: Int, hidden: Ranges): List<Int> =
		generateSequence(folder + 1) { end[it] }
			.takeWhile { it < end[folder] }
			.filter { it !in hidden }
			.toList()

	/** The entry at [where], which is [root] or under it, unless it is not held or [hidden]. */
	internal fun locate(where: Where, hidden: Ranges): Int? {
		if (0 in hidden)
			return null

		return where.levels.drop(root.levels.size).fold<ByteArray, Int?>(0) { at, name ->
			at?.let { childNamed(it, name, hidden) }
		}
	}

	/** Where [entry] is. */
	internal fun whereOf(entry: Int): Where =
		generateSequence(entry) { parent[it].takeIf { it > 0 } }
			.takeWhile { it > 0 }
			.toList()
			.asReversed()
			.fold(root) { where, it -> where.child(nameOf(it)) }

	override fun equals(other: Any?): Boolean =
		other is Block &&
			root == other.root &&
			parent.contentEquals(other.parent) &&
			nameAt.contentEquals(other.nameAt) &&
			names.contentEquals(other.names) &&
			kinds.contentEquals(other.kinds) &&
			inodes.contentEquals(other.inodes) &&
			device.contentEquals(other.device) &&
			devices == other.devices

	override fun hashCode(): Int = root.hashCode() * 31 + names.contentHashCode()

	override fun toString(): String = "Block($root, $size entries)"

	internal companion object {
		val KINDS: List<Kind> = Kind.entries

		private const val DOT = '.'.code.toByte()

		/**
		 * How many bytes of the name from [from] to [to] are its stem: up to its last `.`, or up to `.tar.` where the
		 * last extension follows one (`archive.tar.gz`'s stem is `archive`). A leading `.` is the stem's, so
		 * `.gitignore` is all stem.
		 */
		fun stemLength(name: ByteArray, from: Int, to: Int): Int {
			val dot = (to - 1 downTo from + 1).firstOrNull { name[it] == DOT } ?: return to - from
			val tar = dot - 4

			val compound =
				tar > from &&
					name[tar] == DOT &&
					String(name, tar + 1, 3, Charsets.ISO_8859_1).equals("tar", ignoreCase = true)

			return (if (compound) tar else dot) - from
		}
	}
}

/** Builds a [Block], entry by entry, in preorder. */
internal class BlockBuilder(
	private val root: Where,
) {
	private val parent = IntBuilder()
	private val nameAt = IntBuilder().also { it.add(0) }
	private val names = ByteBuilder()
	private val kinds = ByteBuilder()
	private val inodes = LongBuilder()
	private val device = ShortBuilder()
	private val devices = LinkedHashMap<Device, Int>()

	/** An entry, under [parent] (-1 for the root): its id. */
	fun add(
		parent: Int,
		name: ByteArray,
		kind: Kind,
		on: Device,
		inode: Long,
		from: Int = 0,
		to: Int = name.size,
	): Int {
		this.parent.add(parent)
		names.add(name, from, to)
		nameAt.add(names.size)
		kinds.add(kind.ordinal.toByte())
		inodes.add(inode)
		device.add(devices.getOrPut(on) { devices.size }.toShort())

		return this.parent.size - 1
	}

	fun built(): Block =
		Block(
			root,
			parent.toArray(),
			nameAt.toArray(),
			names.toArray(),
			kinds.toArray(),
			inodes.toArray(),
			device.toArray(),
			devices.keys.toList(),
		)
}

/**
 * Entries no longer there, as ranges: whole subtrees, sorted and apart. Hiding is how an index takes entries out of a
 * block without making the block again; compacting makes it again without them.
 */
internal class Ranges private constructor(
	/** Each range's first entry and the entry after its last, in turn. */
	private val bounds: IntArray,
) {
	val count: Int get() = bounds.size / 2

	/** How many entries the ranges hold. */
	val entries: Int get() = (0 until count).sumOf { end(it) - start(it) }

	fun start(range: Int): Int = bounds[range * 2]

	fun end(range: Int): Int = bounds[range * 2 + 1]

	operator fun contains(entry: Int): Boolean {
		val range = after(entry)

		return range < count && start(range) <= entry
	}

	/** The first range that ends after [entry]. */
	fun after(entry: Int): Int {
		var low = 0
		var high = count

		while (low < high) {
			val middle = (low + high) ushr 1

			if (end(middle) <= entry)
				low = middle + 1
			else
				high = middle
		}

		return low
	}

	/** These and the entries from [from] up to [to], merged where they meet. */
	fun plus(from: Int, to: Int): Ranges {
		val merged =
			((0 until count).map { start(it) to end(it) } + (from to to))
				.sortedBy { it.first }
				.fold(listOf<Pair<Int, Int>>()) { ranges, range ->
					val last = ranges.lastOrNull()

					if (last != null && range.first <= last.second)
						ranges.dropLast(1) + (last.first to maxOf(last.second, range.second))
					else
						ranges + range
				}

		return Ranges(merged.flatMap { listOf(it.first, it.second) }.toIntArray())
	}

	companion object {
		val NONE = Ranges(IntArray(0))
	}
}
