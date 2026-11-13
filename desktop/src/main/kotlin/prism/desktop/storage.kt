package prism.desktop

import java.nio.file.Path

/*
 * The machine's storage, as the System page shows it: what holds files, and how full it is.
 *
 * How full is a fact of the space files share, which is not a fact of where they are mounted. A ZFS pool's datasets,
 * each mounted at a folder of its own, all take from the pool; a btrfs's subvolumes all take from the one file
 * system; a drive's partitions each take a part of the drive. So what is shown is grouped by what shares space:
 *
 * - A [Store] is a set of physical drives and everything on them, taken together where something spans them (a ZFS
 *   pool mirrored over two drives, an LVM volume group, a RAID, a btrfs over several devices). One drive alone is the
 *   usual store.
 * - A store's room is taken by [Part]s: each file system on it ([Volume]), each partition with none mounted
 *   ([Unmounted]), swap in use ([Swap]), and room no partition takes ([Unallocated]).
 * - A volume is reached where it is mounted ([Reached]): a file system at one folder; a btrfs's subvolumes each at
 *   their own; a ZFS pool's datasets each at theirs, with what each holds of the pool, where that is known.
 *
 * What a platform reads this from is its own (freedesktop/storage.kt: Linux's sysfs, mountinfo and udev).
 */

/**
 * How much a file system holds: [total] bytes; [free], what the user can still write; and [used], the rest (what files
 * take, and any room the file system keeps back for the system's own use), so that the two make the whole.
 */
data class Space(
	val total: Long,
	val used: Long,
	val free: Long,
)

/** What a drive is connected by. */
enum class Bus {
	Nvme,
	Sata,
	Usb,
	Sd,
	Virtual,
	Other,
}

/**
 * A physical drive: the kernel's [name] for it (`nvme0n1`, `sda`), its [model] where it says, its [size] in bytes, the
 * [bus] it is on, whether it [spins] (a hard disk, rather than flash), whether it is [removable], and its [link]: the
 * path that names it whatever order the kernel finds the drives in (`/dev/disk/by-id/nvme-Samsung_SSD_980_PRO_2TB_…`,
 * what a pool or a mount table is best given), where the system makes one.
 */
data class Drive(
	val name: String,
	val model: String?,
	val size: Long,
	val bus: Bus,
	val spins: Boolean,
	val removable: Boolean,
	val link: String? = null,
)

/**
 * Where a volume's files are reached: [mounts], each folder it is mounted at, the shortest first (none for a dataset
 * that is not mounted); [name], what it is (a dataset's name, a subvolume's); and how much it [holds] of the volume's
 * space, where that is known of it alone (a ZFS dataset's own use; null for a btrfs subvolume, which shares its file
 * system's). Whatever mounted it (its own tools, the system's mount table, systemd): where the kernel says it is.
 */
data class Reached(
	val name: String,
	val mounts: List<Path>,
	val holds: Long?,
) {
	/** The folder it is first reached at; null where it is not mounted. */
	val at: Path? get() = mounts.firstOrNull()
}

/** Something that takes room in a [Store]: [size] bytes of it. */
sealed interface Part {
	val size: Long

	/**
	 * A file system: its [label] where it has one; its [type] (`ext4`, `zfs`, `nfs4`); its [source], what was mounted
	 * (`/dev/nvme0n1p2`, a pool's name, `server:/export`); its [space], null where it did not answer in time (a
	 * network share that is down); where it is [reached]; and whether it is [readOnly] (an image, a squashfs, a file
	 * system mounted so), which is full as it was made, and is not said to be filling up.
	 */
	data class Volume(
		val label: String?,
		val type: String,
		val source: String,
		val space: Space?,
		val reached: List<Reached>,
		val readOnly: Boolean = false,
	) : Part {
		override val size: Long get() = space?.total ?: 0

		/** The folder it is first reached at: the shortest of its mount points. */
		val at: Path? get() = reached.mapNotNull { it.at }.minByOrNull { it.nameCount }

		/** Whether it is a pool of datasets, each of which holds its own part of it. */
		val pooled: Boolean get() = reached.any { it.holds != null }
	}

	/** Swap in use on the partition [device]: [size] bytes, [used] of them. */
	data class Swap(
		val device: String,
		override val size: Long,
		val used: Long,
	) : Part

	/** A partition (or a drive with none) with nothing of it mounted: what udev says is on it, where it says. */
	data class Unmounted(
		val device: String,
		override val size: Long,
		val type: String?,
		val label: String?,
	) : Part

	/** Room on the drive [drive] that no partition takes. */
	data class Unallocated(
		val drive: String,
		override val size: Long,
	) : Part
}

/** What kind of store it is: in the machine, removable (a USB drive, a card), or over the network. */
enum class StoreKind {
	Internal,
	Removable,
	Network,
}

/** How a store is known to be the one the computer was started from. */
enum class Boot {
	/** The firmware says so: it started the computer from a partition of the store's (its EFI variables name it). */
	Firmware,

	/**
	 * Nothing says where the computer was started from (an older machine's BIOS, a firmware that keeps no record): the
	 * system's root is on the store, which is as near as can be said.
	 */
	Root,
}

/**
 * Physical [drives] and the [parts] that take their room, as one: see the top of this file. [boot] says whether, and
 * how it is known that, the computer was started from it.
 */
data class Store(
	val kind: StoreKind,
	val drives: List<Drive>,
	val parts: List<Part>,
	val boot: Boot? = null,
) {
	/** What it is known by from one read to the next: its drives' names, or what is on it where it has no drives. */
	val key: String
		get() =
			drives
				.joinToString("+") { it.name }
				.ifEmpty {
					"$kind:" +
						parts.filterIsInstance<Part.Volume>().joinToString("+") { it.source }
				}

	/** How large it is: what its parts take, together. */
	val size: Long get() = parts.sumOf { it.size }

	/** What can still be written to it: what its file systems that can be written have free, together. */
	val free: Long get() =
		parts.filterIsInstance<Part.Volume>().filterNot { it.readOnly }.sumOf {
			it.space?.free
				?: 0
		}

	/** Whether anything of it can be written: it has a file system that is not read-only. */
	val writable: Boolean get() = parts.any { it is Part.Volume && !it.readOnly }

	/** Whether the system itself is on it: a file system of it is mounted at the root. */
	val holdsSystem: Boolean
		get() =
			parts.any { part ->
				part is Part.Volume &&
					part.reached.any { it.at == Path.of("/") }
			}
}

/** The machine's stores: the one the system is on first, then the others in the machine, removable ones, the network's. */
data class Storage(
	val stores: List<Store>,
)

/** What reads the machine's storage, as its platform keeps it. */
fun interface StorageReader {
	/** The storage as it is now; slow where a network share is slow to answer, so not on the windows' thread. */
	fun read(): Storage
}
