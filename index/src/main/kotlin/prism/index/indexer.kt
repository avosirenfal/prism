package prism.index

import prism.linux.AtFlags
import prism.linux.Dev
import prism.linux.Fd
import prism.linux.FileKind
import prism.linux.Stat
import prism.linux.Sys
import prism.linux.statx
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
 * device's file system type, and which mount points are of file systems the rules leave out. Each entry read is
 * seen when [clock] says it was read, which is how new it is to an index that did not hold it ([Change.Present]).
 */
class Indexer(
	val rules: IgnoreRules = IgnoreRules.DEFAULT,
	val mounts: List<Mount> = Mounts.current(),
	val backend: Backend = Backend.Getdents,
	val threads: Int = Runtime.getRuntime().availableProcessors(),
	private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) {
	private val ignoredMounts: Set<Path> = Mounts.ignored(rules, mounts)
	private val ignoredKeys: Set<String> = ignoredMounts.map(Where::keyOf).toSet()
	private val scanner = Scanner(backend, rules, threads, ignoredMounts)

	/** The mounts, by the bytes of where they are; where two are mounted at one place, the later, which is the one seen. */
	private val mountsAt: Map<String, Mount> = mounts.associateBy { Where.keyOf(it.at) }

	/** [path] indexed: a root of the index, held as it is now. [Change.Absent] where nothing is there. */
	fun index(path: Path): Change = index(Where.of(path))

	/** The place at [where] indexed: a root of the index, held as it is now. [Change.Absent] where nothing is there. */
	fun index(where: Where): Change =
		treeAt(where)?.let(Change::Indexed) ?: Change.Absent(where)

	/** Whether the rules leave out the place at [where], or a folder above it. */
	fun leavesOut(where: Where): Boolean =
		generateSequence(where) { it.parent }.any(::leftOut)

	/**
	 * What is at [where] now, with everything under it: what the index makes of a change the watcher saw, or of a
	 * folder the picker shows. [Change.Absent] where nothing is there. Null where the rules leave it out: the index
	 * holds nothing there to change, and saying it is absent would take away a root indexed under it on purpose.
	 */
	fun reread(where: Where): Change? =
		if (leftOut(where))
			null
		else
			treeAt(where)?.let(Change::Present) ?: Change.Absent(where)

	/** Whether the rules leave out the directory at [where]: by its name or path, or as the mount point of one. */
	private fun leftOut(where: Where): Boolean =
		!where.isRoot && (rules.ignores(where) || where.key in ignoredKeys)

	/**
	 * What makes the folder at [where] agree with the disk, one level deep: its entries listed once and compared with
	 * what [index] holds of it, by name, kind and inode. An entry gone is [Change.Absent]; one new, or changed (another
	 * kind, or another inode, as a rename over it or a swap leaves it), is read again with everything under it; an
	 * entry as it was is left as it is.
	 *
	 * What a change the watcher saw becomes where it does not say which entry changed (a move says only where the
	 * entry went, not what it was called before). A folder the index does not hold is read whole; one gone is absent;
	 * one the rules leave out is not read.
	 *
	 * One level is all it reads, so what it cannot see is an entry taken away and made again under the same name, of
	 * the same kind, which a file system gave the same inode (ext4 reuses one freed a moment before): that looks
	 * unchanged. The watcher sends such changes by name (the removal, and the making), and a change by name reads the
	 * entry whole; what a lost change might have hidden is read again whole too, never reconciled.
	 */
	fun reconcile(where: Where, index: Index): List<Change> {
		if (leftOut(where))
			return emptyList()

		val held = index.childrenOf(where) ?: return listOfNotNull(reread(where))
		val folder = index.find(where) ?: return listOfNotNull(reread(where))

		val listing =
			when (val listed = backend.list(where)) {
				is Listing.Read -> listed
				is Listing.Unreadable -> return if (statOf(where, follow = false) ==
					null
				)
					listOf(Change.Absent(where))
				else
					emptyList()
			}

		val onDisk =
			listing.entries
				.filter { Kind.of(it.kind, it.target) != null }
				.filterNot { it.kind == FileKind.Directory && leftOut(where.child(it.name)) }
				.associateBy { String(it.name.raw, Charsets.ISO_8859_1) }
		val heldByName = held.associateBy { String(it.where.name, Charsets.ISO_8859_1) }

		val gone =
			heldByName.filterKeys { it !in onDisk }.values.map {
				Change.Absent(
					it.where,
				)
			}

		val changed =
			onDisk.mapNotNull { (name, listed) ->
				val before = heldByName[name]
				val kind = Kind.of(listed.kind, listed.target)
				// A mount point's inode is the mounted root's, which the listing of the folder above it does not give.
				val mounted = kind == Kind.Directory && before?.device?.dev != folder.device.dev
				val same =
					before != null && before.kind == kind && (before.inode == listed.ino || mounted)

				if (same)
					null
				else
					reread(where.child(listed.name))
			}

		return gone + changed
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
		val builder = BlockBuilder(where, Seen.of(clock.instant()))

		if (kind != Kind.Directory) {
			builder.add(-1, where.name, kind, deviceOf(own.dev, where, null), own.ino)

			return builder.built()
		}

		val packed = scanner.gather(where, Packing)
		val device = deviceOf(packed.dev ?: own.dev, where, null)
		val root = builder.add(-1, where.name, kind, device, packed.ino ?: own.ino)

		lay(builder, root, where, packed, device)

		return builder.built()
	}

	/**
	 * [packed]'s entries added under [folder], which is at [where], on [device] (or their own, for folders on one of
	 * their own), in preorder.
	 */
	private fun lay(
		builder: BlockBuilder,
		folder: Int,
		where: Where,
		packed: Packed,
		device: Device,
	) {
		for (entry in 0 until packed.size) {
			val inside = packed.inside[entry]

			val place =
				inside?.let {
					where.child(
						packed.names.copyOfRange(
							packed.nameAt[entry],
							packed.nameAt[
								entry +
									1,
							],
						),
					)
				}

			val on = inside?.dev?.let { deviceOf(it, place!!, device) } ?: device

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
				lay(builder, id, place!!, inside, on)
		}
	}

	/**
	 * The device of a folder at [where] whose stat says [dev], under a folder on [above] (null for the top of a tree).
	 * Where it is the device above, it is that one; where it is not, the folder is a mount point, and mountinfo says
	 * its file system's type and the kernel's device for it, or it is a btrfs subvolume within the mount above, whose
	 * type and kernel device are that mount's. The top of a tree is under the deepest mount point above it.
	 */
	internal fun deviceOf(dev: Dev, where: Where, above: Device?): Device {
		if (above != null && above.dev == dev)
			return above

		val mountAbove = {
			generateSequence(where.parent) { it.parent }.firstNotNullOfOrNull { mountsAt[it.key] }
		}
		val mount = mountsAt[where.key] ?: if (above == null) mountAbove() else null

		return when {
			mount != null -> Device(dev, Dev(mount.major, mount.minor), mount.type)
			above != null -> Device(dev, above.kernel, above.type)
			else -> Device(dev, dev, null)
		}
	}

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
