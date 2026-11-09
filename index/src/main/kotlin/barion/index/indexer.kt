package barion.index

import barion.linux.AtFlags
import barion.linux.Dev
import barion.linux.Fd
import barion.linux.FileKind
import barion.linux.Stat
import barion.linux.Sys
import barion.linux.statx
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.nio.file.Path

/**
 * Reads the disk into [Change]s: a folder indexed, with everything under it the rules let in, or one entry read
 * again. A folder is read by a [Scanner], whose directories are packed into arrays as they are read (never a tree of
 * objects, which for a whole disk would take several times the index's memory), and then laid out as a [Block].
 *
 * What the index keeps of an entry is what a directory listing says (its name, and whether it is a folder, a file or
 * a link), what a link leads to (one stat that follows it), and the device each folder is on (one stat of each
 * folder, which reading it takes anyway). Devices, pipes and sockets are left out. [mounts], read once, say each
 * device's file system type, and which mount points are of file systems the rules leave out.
 */
class Indexer(
	val rules: IgnoreRules = IgnoreRules.DEFAULT,
	val mounts: List<Mount> = Mounts.current(),
	val backend: Backend = Backend.Getdents,
	val threads: Int = Runtime.getRuntime().availableProcessors(),
) {
	private val ignoredMounts: Set<Path> = Mounts.ignored(rules, mounts)
	private val ignoredKeys: Set<String> = ignoredMounts.map(Where::keyOf).toSet()
	private val scanner = Scanner(backend, rules, threads, ignoredMounts)

	/** Each device's file system type; where two are mounted from one device, the later, as mountinfo lists them. */
	private val types: Map<Dev, String> =
		mounts.associate {
			Dev(it.major, it.minor) to
				it.type
		}

	/** [path] indexed: a root of the index, held as it is now. [Change.Absent] where nothing is there. */
	fun index(path: Path): Change {
		val where = Where.of(path)

		return treeAt(where)?.let(Change::Indexed) ?: Change.Absent(where)
	}

	/**
	 * What is at [where] now, with everything under it: what the index makes of a change the watcher saw, or of a
	 * folder the picker shows. [Change.Absent] where nothing is there. Null where the rules leave it out: the index
	 * holds nothing there to change, and saying it is absent would take away a root indexed under it on purpose.
	 */
	fun reread(where: Where): Change? {
		val leftOut = !where.isRoot && (rules.ignores(where) || where.key in ignoredKeys)

		return if (leftOut)
			null
		else
			treeAt(where)?.let(Change::Present)
				?: Change.Absent(where)
	}

	private fun treeAt(where: Where): Block? {
		val own = statOf(where, follow = false) ?: return null
		val link = own.kind == FileKind.Symlink
		val target =
			if (link)
				statOf(where, follow = true)?.kind
			else
				null
		val kind = Kind.of(own.kind, target) ?: return null
		val builder = BlockBuilder(where)

		if (kind != Kind.Directory) {
			builder.add(-1, where.name, kind, deviceOf(own.dev, null), own.ino)

			return builder.built()
		}

		val packed = scanner.gather(where, Packing)
		val device = deviceOf(packed.dev ?: own.dev, null)

		lay(
			builder,
			builder.add(-1, where.name, kind, device, packed.ino ?: own.ino),
			packed,
			device,
		)

		return builder.built()
	}

	/** [packed]'s entries added under [folder], on [device] (or their own, for folders on one of their own), in preorder. */
	private fun lay(builder: BlockBuilder, folder: Int, packed: Packed, device: Device) {
		for (entry in 0 until packed.size) {
			val inside = packed.inside[entry]
			val on = inside?.dev?.let { deviceOf(it, device) } ?: device

			val id =
				builder.add(
					folder,
					packed.names,
					Block.KINDS[packed.kinds[entry].toInt()],
					on,
					packed.inodes[entry],
					packed.nameAt[entry],
					packed.nameAt[entry + 1],
				)

			if (inside != null)
				lay(builder, id, inside, on)
		}
	}

	/**
	 * [dev] as a [Device]: its type as mountinfo says, or where it does not (a btrfs subvolume not mounted on its own
	 * has a device number of its own), the type of the folder above it.
	 */
	private fun deviceOf(dev: Dev, above: Device?): Device =
		Device(
			dev,
			types[dev] ?: above?.type,
		)

	/** [where]'s statx, following a link or not, and never mounting an automount point; null where it is not there. */
	private fun statOf(where: Where, follow: Boolean): Stat? =
		Arena.ofConfined().use { arena ->
			val path = arena.allocate(where.bytes.size + 1L)

			MemorySegment.copy(where.bytes, 0, path, JAVA_BYTE, 0, where.bytes.size)
			path.set(JAVA_BYTE, where.bytes.size.toLong(), 0)

			val flags =
				if (follow)
					AtFlags.NO_AUTOMOUNT
				else
					AtFlags.NO_AUTOMOUNT or
						AtFlags.SYMLINK_NO_FOLLOW

			when (val stat = statx(Fd.CWD, path, flags, arena.allocate(256, 8))) {
				is Sys.Ok -> stat.value
				is Sys.Failed -> null
			}
		}
}

/**
 * A directory as the indexer gathers it: its own device and inode (null where it could not be read), and its entries
 * packed into arrays, sorted by name: names back to back, each entry's [Kind] and inode, and for a folder read, what
 * it holds.
 */
internal class Packed(
	val dev: Dev?,
	val ino: Long?,
	val names: ByteArray,
	val nameAt: IntArray,
	val kinds: ByteArray,
	val inodes: LongArray,
	val inside: Array<Packed?>,
) {
	val size: Int get() = kinds.size
}

/** How the indexer gathers a directory: what the index holds of it, packed; what it does not hold, left out. */
internal object Packing : Gathering<Packed> {
	private val NOTHING =
		Packed(
			null,
			null,
			ByteArray(0),
			intArrayOf(0),
			ByteArray(0),
			LongArray(0),
			emptyArray(),
		)

	/** An entry the index holds. A folder's inode is its own, as reading it said: a mount point's is the mounted root's. */
	private class Kept(
		val name: Name,
		val kind: Kind,
		val ino: Long,
		val inside: Packed?,
	)

	override fun read(
		where: Where,
		listing: Listing.Read,
		found: List<Found<Packed>>,
	): Packed {
		val kept =
			found
				.mapNotNull { entry ->
					val listed = entry.listed

					when (entry) {
						is Found.Skipped -> null
						is Found.Folder ->
							Kept(
								listed.name,
								Kind.Directory,
								entry.gathered.ino ?: listed.ino,
								entry.gathered,
							)
						is Found.Leaf ->
							Kind.of(listed.kind, listed.target)?.let {
								Kept(listed.name, it, listed.ino, null)
							}
					}
				}.sortedBy { it.name }

		val names = ByteBuilder(kept.sumOf { it.name.length })
		val nameAt = IntBuilder(kept.size + 1).also { it.add(0) }

		for (entry in kept) {
			names.add(entry.name.raw)
			nameAt.add(names.size)
		}

		return Packed(
			listing.dev,
			listing.ino,
			names.toArray(),
			nameAt.toArray(),
			ByteArray(kept.size) { kept[it].kind.ordinal.toByte() },
			LongArray(kept.size) { kept[it].ino },
			Array(kept.size) { kept[it].inside },
		)
	}

	override fun unreadable(where: Where, why: String): Packed = NOTHING
}
