package prism.desktop.freedesktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import prism.desktop.Bus
import prism.desktop.Drive
import prism.desktop.Part
import prism.desktop.Reached
import prism.desktop.Space
import prism.desktop.Storage
import prism.desktop.StorageReader
import prism.desktop.Store
import prism.desktop.StoreKind
import prism.linux.Dev
import prism.linux.Mount
import prism.linux.Mounts

/*
 * The machine's storage on Linux (prism.desktop's storage.kt says what is shown, and why it is grouped as it is), read
 * from what the kernel and udev keep, as any user can:
 *
 * - Drives and their partitions: /sys/block. A drive's size (in 512-byte sectors), whether it is removable, whether
 *   it spins (`queue/rotational`), its model; the bus it is on, from where it sits in /sys/devices (under a USB
 *   controller, an NVMe one, an SD card's host). What sits under /sys/devices/virtual (a loop device, LVM's and LUKS's
 *   device-mapper devices, a RAID) is made of drives, not one.
 * - What is mounted: /proc/self/mountinfo, its file systems grouped by superblock (its `MAJOR:MINOR`), so a btrfs's
 *   subvolumes, mounted at several folders, are one file system. A folder of one mounted elsewhere (a bind mount) is
 *   not another place it is reached: it is left out. What holds no files of a drive's (proc, tmpfs, an overlay, a
 *   squashfs on a loop device) is left out too.
 * - Which drives a file system is on: its device's folder in /sys (by /sys/dev/block), followed down through what it
 *   is made of (`slaves`: LVM, LUKS, RAID) to the partitions and drives under it. A btrfs over several devices lists
 *   them in /sys/fs/btrfs; a ZFS pool's members are known by udev's record of each partition (`ID_FS_TYPE=zfs_member`,
 *   labelled with the pool's name), or failing that, by `zpool list -vHPL`.
 * - How full each is: statvfs, given a moment, as a network share that does not answer would hang whoever asked. A
 *   ZFS pool by `zfs list`, which says what each dataset holds of its own (used, less its children's), so the pool's
 *   use is shared out among them; without it, by statvfs of each dataset mounted.
 * - What is not mounted: udev's record of each partition (a file system's type and label), and /proc/swaps for swap.
 */

/**
 * The storage of the Linux machine whose files are under [root] (the machine's own, unless a test gives another):
 * [space] says how full a mounted file system is, [run] gives a command's output, and [rdev] the device a device file
 * is.
 */
class LinuxStorage(
	private val root: Path = Path.of("/"),
	private val space: (Path) -> Space? = ::spaceOf,
	private val run: (List<String>) -> String? = ::outputOf,
	private val rdev: (Path) -> Dev? = ::rdevOf,
) : StorageReader {
	/** What each file system was last asked, and has perhaps not answered yet. */
	private val asking = ConcurrentHashMap<Path, Future<Space?>>()

	override fun read(): Storage {
		val drives = drives()
		val mounts = Mounts.parse(lines("proc/self/mountinfo"))
		val swaps = swaps()
		val local = localVolumes(mounts)
		val pools = pools(mounts)
		val network = networkVolumes(mounts)
		val spaces = spacesOf((local.map { it.at } + network.map { it.at }).distinct())

		// Each file system, as a part, with the partitions and drives under it.
		val placed =
			local.map { volume -> Placed(volume.part(spaces[volume.at]), volume.leaves) } +
				pools.map { Placed(it.part, it.leaves) }

		val stores = storesOf(drives, placed, swaps)

		val shares =
			network
				.map { it.part(spaces[it.at]) }
				.takeIf { it.isNotEmpty() }
				?.let {
					listOf(
						Store(StoreKind.Network, emptyList(), it.sortedByDescending(Part::size)),
					)
				}.orEmpty()

		return Storage(ordered(stores) + shares)
	}

	// ---- files of the kernel's and udev's ----------------------------------------------------------------------------

	private fun file(relative: String): Path = root.resolve(relative.removePrefix("/"))

	/** A file's text, trimmed; null where it is not there or cannot be read. */
	private fun text(relative: String): String? =
		try {
			file(relative).takeIf { it.exists() }?.readText()?.trim()
		} catch (unreadable: IOException) {
			null
		}

	private fun lines(relative: String): List<String> = text(relative)?.lines().orEmpty()

	private fun names(relative: String): List<String> =
		try {
			file(relative)
				.takeIf { it.isDirectory() }
				?.listDirectoryEntries()
				?.map { it.name }
				?.sorted()
				.orEmpty()
		} catch (unreadable: IOException) {
			emptyList()
		}

	/** Where [relative], a link of sysfs's, leads, as the folder's own path under /sys (`devices/.../block/sda/sda1`). */
	private fun resolved(relative: String): Path? =
		try {
			file(relative).takeIf { it.exists() }?.toRealPath()
		} catch (unreadable: IOException) {
			null
		}

	/** udev's record of the block device [dev]: its `E:` lines, as names and values. */
	private fun udev(dev: Dev): Map<String, String> =
		lines("run/udev/data/b$dev")
			.filter { it.startsWith("E:") }
			.associate { it.removePrefix("E:").substringBefore('=') to it.substringAfter('=') }

	// ---- drives ----------------------------------------------------------------------------------------------------

	/** A drive, as /sys/block has it, with its [partitions]. */
	private data class Found(
		val drive: Drive,
		val dev: Dev?,
		val partitions: List<Partition>,
	)

	private data class Partition(
		val name: String,
		val dev: Dev?,
		val size: Long,
	)

	/** Every physical drive with room on it: what /sys/block has that is not made of others, nor empty (a card reader). */
	private fun drives(): List<Found> =
		names("sys/block").mapNotNull { name ->
			val at = resolved("sys/block/$name") ?: return@mapNotNull null
			val path = at.toString()
			val size = sectors("sys/block/$name/size")

			if ("/devices/virtual/" in path || size <= 0)
				return@mapNotNull null

			val bus =
				when {
					"/usb" in path -> Bus.Usb
					name.startsWith("nvme") -> Bus.Nvme
					name.startsWith("mmcblk") -> Bus.Sd
					name.startsWith("vd") || "/virtio" in path -> Bus.Virtual
					name.startsWith("sd") || "/ata" in path -> Bus.Sata
					else -> Bus.Other
				}

			val partitions =
				names("sys/block/$name")
					.filter { file("sys/block/$name/$it/partition").exists() }
					.map {
						Partition(
							it,
							devOf(text("sys/block/$name/$it/dev")),
							sectors("sys/block/$name/$it/size"),
						)
					}

			Found(
				Drive(
					name,
					modelOf(name),
					size,
					bus,
					spins = text("sys/block/$name/queue/rotational") == "1",
					removable = text("sys/block/$name/removable") == "1" || bus == Bus.Usb,
				),
				devOf(text("sys/block/$name/dev")),
				partitions,
			)
		}

	/** What [file] says in 512-byte sectors, in bytes; 0 where it says nothing. */
	private fun sectors(file: String): Long = (text(file)?.toLongOrNull() ?: 0) * 512

	/** A drive's model as it says it: its vendor's name before it, where that is not the bus's (`ATA`). */
	private fun modelOf(name: String): String? {
		val model =
			text("sys/block/$name/device/model")?.takeIf { it.isNotBlank() } ?: return null

		val vendor =
			text("sys/block/$name/device/vendor")?.takeIf {
				it.isNotBlank() && it != "ATA" && !model.startsWith(it, ignoreCase = true)
			}

		return listOfNotNull(vendor, model).joinToString(" ")
	}

	/** The block devices under [name] (a partition, a drive, or what is made of them), as far down as they go. */
	private fun leavesOf(name: String): Set<String> {
		val made = names("sys/class/block/$name/slaves")

		return if (made.isEmpty())
			setOf(name)
		else
			made.flatMap(::leavesOf).toSet()
	}

	/** The block device the kernel calls [dev]: its name (`nvme0n1p2`, `dm-0`); null where it is no block device. */
	private fun blockOf(dev: Dev): String? = resolved("sys/dev/block/$dev")?.name

	// ---- what is mounted ---------------------------------------------------------------------------------------------

	/** A file system on drives of the machine's: where it is first reached, and the partitions and drives under it. */
	private inner class Local(
		val mounts: List<Mount>,
		val leaves: Set<String>,
		val label: String?,
	) {
		/** Where it is reached: its root, and each btrfs subvolume, each once, at the shortest folder it is mounted at. */
		val reached: List<Reached> =
			mounts
				.filter { it.root == "/" || it.root == it.option("subvol") }
				.groupBy { it.root }
				.map { (root, at) ->
					Reached(
						if (root == "/")
							at.minBy { it.at.nameCount }.at.toString()
						else
							root.removePrefix("/"),
						at.minBy { it.at.nameCount }.at,
						holds = null,
					)
				}.ifEmpty {
					// Only a folder of it is mounted: that, then, is where it is reached.
					listOf(
						mounts.minBy { it.at.nameCount }.let { Reached(it.at.toString(), it.at, null) },
					)
				}.sortedBy { it.at?.nameCount ?: 0 }

		val at: Path get() = reached.first().at!!

		fun part(space: Space?) =
			mounts.first().let { first ->
				Part.Volume(
					label,
					first.type,
					first.source,
					space,
					reached,
					readOnly = readOnly(first),
				)
			}
	}

	/** The file systems on the machine's drives, one for each superblock, and what they are on. */
	private fun localVolumes(mounts: List<Mount>): List<Local> =
		mounts
			.filter { it.type != "zfs" && it.type !in NETWORK }
			.groupBy { it.dev }
			.mapNotNull { (dev, all) ->
				val first = all.first()

				// A block device's own number, or, for a file system that numbers its superblock apart (btrfs), its
				// source's.
				val device =
					blockOf(dev)?.let { dev to it }
						?: first.source
							.takeIf { it.startsWith("/dev/") }
							?.let { source ->
								rdev(file(source))?.let { rdev -> blockOf(rdev)?.let { rdev to it } }
							}
						?: return@mapNotNull null

				val (number, name) = device
				val leaves =
					if (first.type == "btrfs")
						btrfsDevices(name).flatMap(::leavesOf).toSet()
					else
						leavesOf(name)

				// What a loop device holds is a file of another file system's, counted there.
				if (leaves.any {
						it.startsWith("loop") || it.startsWith("ram") ||
							it.startsWith("zram")
					}
				)
					return@mapNotNull null

				Local(all, leaves, udev(number)["ID_FS_LABEL"]?.takeIf { it.isNotBlank() })
			}

	/** The devices a btrfs on [name] spans, as /sys/fs/btrfs lists them; [name] alone where it does not say. */
	private fun btrfsDevices(name: String): List<String> =
		names("sys/fs/btrfs")
			.map { names("sys/fs/btrfs/$it/devices") }
			.firstOrNull { name in it }
			?: listOf(name)

	/** A network share: what was mounted, and where. */
	private class Share(
		val mount: Mount,
	) {
		val at: Path get() = mount.at

		fun part(space: Space?) =
			Part.Volume(
				null,
				mount.type,
				mount.source,
				space,
				listOf(Reached(mount.at.toString(), mount.at, null)),
				readOnly = readOnly(mount),
			)
	}

	private fun networkVolumes(mounts: List<Mount>): List<Share> =
		mounts
			.filter { it.type in NETWORK }
			.distinctBy { it.source to it.root }
			.map(::Share)

	// ---- ZFS --------------------------------------------------------------------------------------------------------

	/** A ZFS pool, as a part, and the partitions and drives it is on. */
	private class Pool(
		val part: Part.Volume,
		val leaves: Set<String>,
	)

	/** A dataset as `zfs list` says it: its name, what it and its children hold, what is free, where it is mounted. */
	private data class Dataset(
		val name: String,
		val used: Long,
		val free: Long,
		val children: Long,
		val at: Path?,
	)

	/** Each ZFS pool something is mounted from: its datasets and what each holds, and its members. */
	private fun pools(mounts: List<Mount>): List<Pool> {
		val mounted = mounts.filter { it.type == "zfs" }
		val names = mounted.map { it.source.substringBefore('/') }.distinct()

		if (names.isEmpty())
			return emptyList()

		val listed = datasets()
		val members = poolMembers(names)

		return names.map { pool ->
			val datasets = listed.filter { it.name == pool || it.name.startsWith("$pool/") }
			val top = datasets.firstOrNull { it.name == pool }

			val (space, reached) =
				if (top != null)
					Space(top.used + top.free, top.used, top.free) to
						datasets
							.map { Reached(it.name, it.at, it.used - it.children) }
							.filter { it.at != null || (it.holds ?: 0) > 0 }
				else
					measured(mounted.filter { it.source.substringBefore('/') == pool })

			Pool(
				Part.Volume(pool, "zfs", pool, space, reached.sortedByDescending { it.holds ?: 0 }),
				members[pool].orEmpty(),
			)
		}
	}

	/**
	 * A pool's datasets measured by statvfs, where `zfs list` is not there to say: what each holds is what it refers
	 * to itself, and the pool's free room is any of theirs (they share it). Snapshots are not counted.
	 */
	private fun measured(datasets: List<Mount>): Pair<Space?, List<Reached>> {
		val spaces = spacesOf(datasets.map { it.at })
		val reached =
			datasets.map { Reached(it.source, it.at, spaces[it.at]?.used) }
		val free = spaces.values.maxOfOrNull { it.free } ?: return null to reached
		val used = reached.sumOf { it.holds ?: 0 }

		return Space(used + free, used, free) to reached
	}

	/** Every dataset `zfs list` gives; none where it is not there. */
	private fun datasets(): List<Dataset> =
		run(
			listOf(
				"zfs",
				"list",
				"-Hp",
				"-t",
				"filesystem,volume",
				"-o",
				"name,used,avail,usedbychildren,mountpoint,mounted",
			),
		)?.lines()
			.orEmpty()
			.mapNotNull { line ->
				val fields = line.split('\t')

				if (fields.size < 6)
					null
				else
					Dataset(
						fields[0],
						fields[1].toLongOrNull() ?: return@mapNotNull null,
						fields[2].toLongOrNull() ?: 0,
						fields[3].toLongOrNull() ?: 0,
						fields[4].takeIf { fields[5] == "yes" && it.startsWith("/") }?.let(Path::of),
					)
			}

	/**
	 * The partitions and drives each of [pools] is on: those udev records as its members (by the pool's name, as a
	 * member's label); for a pool it records none of, those `zpool list` names.
	 */
	private fun poolMembers(pools: List<String>): Map<String, Set<String>> {
		val recorded =
			names("sys/class/block")
				.mapNotNull { name ->
					val record = devOf(text("sys/class/block/$name/dev"))?.let(::udev).orEmpty()

					record["ID_FS_LABEL"]
						?.takeIf { record["ID_FS_TYPE"] == "zfs_member" }
						?.let { it to name }
				}.groupBy({ it.first }, { it.second })
				.mapValues { it.value.toSet() }

		val unrecorded = pools.filter { it !in recorded }

		return recorded +
			if (unrecorded.isEmpty())
				emptyMap()
			else
				zpoolMembers()
					.filterKeys { it in unrecorded }
	}

	/** Each pool's members as `zpool list -vHPL` names them: a pool's line, then its devices' by their paths. */
	private fun zpoolMembers(): Map<String, Set<String>> {
		val lines = run(listOf("zpool", "list", "-vHPL"))?.lines().orEmpty()
		val members = HashMap<String, MutableSet<String>>()
		var pool: String? = null

		for (line in lines) {
			val first = line.trim().substringBefore('\t')

			when {
				line.isBlank() -> Unit
				!line.startsWith('\t') && !line.startsWith(' ') -> pool = first
				first.startsWith("/dev/") ->
					pool?.let { members.getOrPut(it) { HashSet() } += Path.of(first).name }
			}
		}

		return members
	}

	// ---- swap, and what is not mounted ---------------------------------------------------------------------------------

	/** Swap in use on a device, by the device's name: its size and use, as /proc/swaps says them (in KiB). */
	private fun swaps(): Map<String, Part.Swap> =
		lines("proc/swaps")
			.drop(1)
			.mapNotNull { line ->
				val fields = line.trim().split(Regex("\\s+"))

				if (fields.size < 4 || fields[1] != "partition")
					null
				else
					Path.of(fields[0]).name.let { name ->
						name to
							Part.Swap(
								name,
								(fields[2].toLongOrNull() ?: 0) * 1024,
								(fields[3].toLongOrNull() ?: 0) * 1024,
							)
					}
			}.toMap()

	// ---- stores -------------------------------------------------------------------------------------------------------

	/** A file system and the partitions and drives it is on. */
	private class Placed(
		val part: Part.Volume,
		val leaves: Set<String>,
	)

	/**
	 * The stores the drives make: drives joined where a file system spans them, each with its file systems, its swap,
	 * the partitions of it with nothing mounted, and its room no partition takes. A file system on no drive this knows
	 * is a store of its own.
	 */
	private fun storesOf(
		drives: List<Found>,
		placed: List<Placed>,
		swaps: Map<String, Part.Swap>,
	): List<Store> {
		val driveOf =
			drives
				.flatMap { found ->
					(found.partitions.map { it.name } + found.drive.name).map {
						it to
							found
					}
				}.toMap()

		/** The drives [leaves] are on. */
		fun drivesOf(leaves: Set<String>): Set<Found> = leaves.mapNotNull(driveOf::get).toSet()

		// Drives joined, one group at a time, by each file system on several.
		val groups =
			placed
				.map { drivesOf(it.leaves) }
				.fold(drives.map { setOf(it) }) { joined, spanned ->
					if (spanned.size < 2)
						joined
					else
						joined.filter { it.none(spanned::contains) } +
							listOf(joined.filter { it.any(spanned::contains) }.flatten().toSet())
				}

		val used = placed.flatMap { it.leaves }.toSet()

		val onDrives =
			groups.map { group ->
				val volumes =
					placed
						.filter {
							drivesOf(
								it.leaves,
							).any(group::contains)
						}.map { it.part }

				val rest = group.flatMap { unused(it, used, swaps) }

				Store(
					if (group.any { it.drive.removable })
						StoreKind.Removable
					else
						StoreKind.Internal,
					group.map { it.drive }.sortedBy { it.name },
					volumes.sortedByDescending { it.size } + rest,
				)
			}

		val elsewhere =
			placed
				.filter { drivesOf(it.leaves).isEmpty() }
				.map { Store(StoreKind.Internal, emptyList(), listOf(it.part)) }

		return onDrives.filter { it.parts.isNotEmpty() } + elsewhere
	}

	/**
	 * What of [found] no file system is on: swap in use, each partition with nothing mounted (as udev says what is on
	 * it), the drive itself where it has no partitions and nothing is on it, and its room no partition takes. What is
	 * too small to see in a drive's bar (a BIOS boot partition, Windows' reserved one) is left out.
	 */
	private fun unused(
		found: Found,
		used: Set<String>,
		swaps: Map<String, Part.Swap>,
	): List<Part> {
		val pieces =
			found.partitions.map { Triple(it.name, it.dev, it.size) }.ifEmpty {
				listOf(Triple(found.drive.name, found.dev, found.drive.size))
			}

		val unmounted =
			pieces
				.filter { (name) -> name !in used }
				.mapNotNull { (name, dev, size) ->
					swaps[name]
						?: dev
							?.let(::udev)
							.orEmpty()
							.let { record ->
								Part.Unmounted(
									name,
									size,
									record["ID_FS_TYPE"]?.takeIf { it.isNotBlank() },
									(record["ID_FS_LABEL"] ?: record["ID_PART_ENTRY_NAME"])?.takeIf {
										it.isNotBlank()
									},
								)
							}.takeIf { size >= SMALLEST }
				}

		val unallocated =
			(found.drive.size - found.partitions.sumOf { it.size })
				.takeIf { found.partitions.isNotEmpty() && it >= SMALLEST }
				?.let { Part.Unallocated(found.drive.name, it) }

		return unmounted.sortedWith(
			compareBy<Part> {
				it !is Part.Swap
			}.thenByDescending { it.size },
		) +
			listOfNotNull(unallocated)
	}

	/** The system's own store first, then the rest in the machine, then removable ones; each kind by its first drive. */
	private fun ordered(stores: List<Store>): List<Store> =
		stores.sortedWith(
			compareBy<Store>(
				{ !it.holdsSystem },
				{ it.kind },
				{ it.drives.firstOrNull()?.name ?: "~" },
			),
		)

	// ---- how full ----------------------------------------------------------------------------------------------------

	/**
	 * How full the file system at each of [points] is, each given a moment to say: a network share that does not
	 * answer would hang whoever asked, so it is asked on a thread of its own, and is said not to have answered. One
	 * that has not answered yet is not asked again until it has, so a share that is down holds one thread, not one
	 * for every look.
	 */
	private fun spacesOf(points: List<Path>): Map<Path, Space> {
		val asked =
			points.associateWith { at ->
				asking.compute(at) { _, before ->
					before?.takeUnless { it.isDone } ?: ASKERS.submit<Space?> { space(at) }
				}!!
			}
		val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS)

		return asked
			.mapNotNull { (at, answer) ->
				try {
					answer
						.get(
							(deadline - System.nanoTime()).coerceAtLeast(0),
							TimeUnit.NANOSECONDS,
						)?.let {
							at to
								it
						}
				} catch (late: java.util.concurrent.TimeoutException) {
					null
				} catch (failed: java.util.concurrent.ExecutionException) {
					null
				}
			}.toMap()
	}

	private companion object {
		/** File systems that are another machine's, reached over the network. */
		val NETWORK =
			setOf(
				"nfs",
				"nfs4",
				"cifs",
				"smb3",
				"smbfs",
				"9p",
				"ceph",
				"glusterfs",
				"fuse.sshfs",
				"fuse.rclone",
				"davfs",
				"fuse.davfs2",
			)

		/** File systems that are read only as they are made: images, and what holds programs to be run as they are. */
		val READ_ONLY = setOf("squashfs", "iso9660", "udf", "erofs", "cramfs")

		/** Whether [mount] is of a file system that can only be read: one of [READ_ONLY], or mounted so (`ro`). */
		fun readOnly(mount: Mount): Boolean = mount.type in READ_ONLY || "ro" in mount.options

		/** What is smaller than this takes too little of a drive to show: 64 MiB. */
		const val SMALLEST = 64L shl 20

		/** How long the file systems are given, together, to say how full they are. */
		const val WAIT_MILLIS = 1500L

		/** The threads file systems are asked on: as many as are asked at once, each let go of after a minute idle. */
		val ASKERS: ExecutorService =
			Executors.newCachedThreadPool { Thread(it, "prism storage").apply { isDaemon = true } }

		fun devOf(text: String?): Dev? =
			text?.split(':')?.takeIf { it.size == 2 }?.let { (major, minor) ->
				Dev(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null)
			}
	}
}

/** How full the file system [at] is mounted at is, as statvfs says; null where it cannot say. */
private fun spaceOf(at: Path): Space? =
	try {
		val store = Files.getFileStore(at)
		val total = store.totalSpace
		val free = store.usableSpace

		// What the user cannot write is used, whether files take it or the file system keeps it back (ext4's for root).
		Space(total, total - free, free)
	} catch (failed: IOException) {
		null
	}

/** The device the device file [at] is; null where it is none. */
private fun rdevOf(at: Path): Dev? =
	try {
		val encoded = Files.getAttribute(at, "unix:rdev") as Long

		// glibc's gnu_dev_major and gnu_dev_minor: each number's low bits, then its high ones, in 32 bits.
		Dev(
			(((encoded ushr 8) and 0xfffL) or ((encoded ushr 32) and 0xfffff000L)).toInt(),
			((encoded and 0xffL) or ((encoded ushr 12) and 0xffffff00L)).toInt(),
		)
	} catch (failed: IOException) {
		null
	} catch (unsupported: UnsupportedOperationException) {
		null
	}

/**
 * What [command] writes, where it is there and works within a few seconds; null otherwise. What it writes goes to a
 * file, so that a command with much to say is not held up by a pipe nobody reads until it ends.
 */
private fun outputOf(command: List<String>): String? {
	val out = Files.createTempFile("prism-storage", ".txt")

	return try {
		val process =
			ProcessBuilder(command)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
				.redirectOutput(out.toFile())
				.start()

		if (!process.waitFor(3, TimeUnit.SECONDS)) {
			process.destroyForcibly()
			null
		} else if (process.exitValue() == 0) {
			out.readText()
		} else {
			null
		}
	} catch (missing: IOException) {
		null
	} finally {
		Files.deleteIfExists(out)
	}
}
