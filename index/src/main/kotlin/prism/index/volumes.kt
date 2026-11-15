package prism.index

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import prism.linux.Mount
import prism.linux.Sys
import prism.linux.fsidOf

/*
 * Which file system a mount is of, whenever and wherever it is mounted. A file system that is unmounted and mounted
 * again (a drive unplugged, a dataset exported, a share that went away) is another device to the kernel each time,
 * and may be mounted somewhere else: what says it is the same one is something it carries itself.
 *
 * - One on a block device (ext4, xfs, btrfs, FAT, NTFS, and anything else whose source is under /dev) has a UUID
 *   written in it, which udev read when the device appeared (`ID_FS_UUID`, in /run/udev/data), and /dev/disk/by-uuid
 *   names it by where there is no udev; where neither says, ext4, btrfs and f2fs still give an `f_fsid` made from
 *   it. With the folder of it that is mounted (a btrfs subvolume, a bind of a part) that is one mount's worth of
 *   one file system.
 * - ZFS has no device to ask: a dataset's `f_fsid` is made from an id written in the pool, the same wherever it is
 *   imported, and with its name that says which dataset it is.
 * - A share on another machine is what was mounted: the type, the source (`server:/export`) and the folder of it.
 * - Anything else (memory, the kernel's own, overlays) has nothing that says it is the same one the next time, and
 *   is never taken for one seen before.
 */

/** What says which file system a mount is of, as text: the same for the same one, wherever it is mounted. */
@JvmInline
value class VolumeId(
	val text: String,
) {
	override fun toString(): String = text
}

/**
 * A file system mounted: which it is ([id]), and whether it is one that comes and goes with something outside the
 * machine ([portable]: on a drive that is plugged in, or on another machine), which is not read into the index only
 * because it turned up.
 */
data class Volume(
	val id: VolumeId,
	val portable: Boolean,
)

/**
 * What tells which file system a mount is of ([of]), read from the system's files under [root] (`/`, but for a test),
 * each time it is asked: the device a name stands for is another once a drive is taken out and another put in.
 */
class Volumes(
	private val root: Path = Path("/"),
	/** A file system's own id (`statfs`), for those that keep one: null where it cannot be asked. */
	private val fsid: (Path) -> Long? = { path -> (fsidOf(path) as? Sys.Ok)?.value },
) {
	/** What udev and sysfs say of a block device: its file system's UUID, and whether it is one that is plugged in. */
	private data class Device(
		val uuid: String?,
		val removable: Boolean,
	)

	/** Which file system [mount] is of; null where nothing says it would be the same one another time. */
	fun of(mount: Mount): Volume? =
		when {
			networked(mount.type) ->
				Volume(VolumeId("net:${mount.type}:${mount.source}:${mount.root}"), portable = true)
			// A snapshot looked into is mounted for as long as it is looked at: nothing to keep, or to read.
			mount.type == ZFS && '@' in mount.source -> null
			mount.type == ZFS ->
				fsid(mount.at)?.let {
					Volume(
						VolumeId("zfs:${mount.source}:${it.toULong().toString(16)}"),
						portable = false,
					)
				}
			mount.source.startsWith("/dev/") ->
				deviceOf(mount.source).let { device ->
					// Its UUID; or, where nothing on the system says it (no udev, no links), the id the file system
					// gives itself, for those that make it from what is written in them.
					val id =
						device.uuid?.let { "uuid:$it" }
							?: fsid(mount.at)?.takeIf { mount.type in KEEPS_FSID }?.let {
								"fsid:${mount.type}:${it.toULong().toString(16)}"
							}

					id?.let { Volume(VolumeId("$it:${mount.root}"), device.removable) }
				}
			else -> null
		}

	/** What is known of the block device a mount's source names (`/dev/sdb1`, `/dev/mapper/home`, a link to either). */
	private fun deviceOf(source: String): Device {
		val node = resolved(source)
		val name = node?.name
		val number = name?.let { text("sys/class/block/$it/dev") }
		val udev = number?.let { lines("run/udev/data/b$it") }.orEmpty()

		fun property(key: String) =
			udev.firstNotNullOfOrNull {
				it.removePrefix("E:$key=").takeIf { value ->
					value != it
				}
			}

		// The disk a partition is of: the folder its own is in, where that is a block device too.
		val disk =
			name
				?.let { resolved("sys/class/block/$it") }
				?.let { own -> own.parent?.takeIf { it.resolve("dev").exists() } ?: own }

		return Device(
			uuid = property("ID_FS_UUID") ?: node?.let(::uuidByLink),
			removable =
				disk
					?.resolve("removable")
					?.takeIf { it.exists() }
					?.readText()
					?.trim() == "1" ||
					property("ID_BUS") == "usb" ||
					property("ID_DRIVE_FLASH_SD") == "1" ||
					name?.startsWith("mmcblk") == true,
		)
	}

	/** The UUID /dev/disk/by-uuid names [node] by: what says it where udev kept nothing. */
	private fun uuidByLink(node: Path): String? =
		file("dev/disk/by-uuid")
			.takeIf { it.isDirectory() }
			?.listDirectoryEntries()
			?.firstOrNull { link -> runCatching { link.toRealPath() }.getOrNull() == node }
			?.name

	private fun file(path: String): Path = root.resolve(path.removePrefix("/"))

	/** [path] under [root], with its links followed; null where it is not there. */
	private fun resolved(path: String): Path? =
		file(path)
			.takeIf {
				Files.exists(it)
			}?.let { runCatching { it.toRealPath() }.getOrNull() }

	private fun text(path: String): String? =
		file(path)
			.takeIf {
				it.exists()
			}?.let { runCatching { it.readText().trim() }.getOrNull() }

	private fun lines(path: String): List<String>? =
		file(path).takeIf { it.exists() }?.let { runCatching { it.readLines() }.getOrNull() }

	/** Whether a file system of [type] is another machine's. */
	private fun networked(type: String): Boolean =
		type in NETWORK || (type.startsWith(FUSE) && type.removePrefix(FUSE) in NETWORK_FUSE)

	companion object {
		private const val FUSE = "fuse."

		/** What tells which file system a mount is of, on this machine. */
		val SYSTEM = Volumes()

		private const val ZFS = "zfs"

		/** File systems whose `f_fsid` is made from their own UUID, and so is the same at every mount. */
		val KEEPS_FSID: Set<String> = setOf("ext2", "ext3", "ext4", "btrfs", "f2fs")

		/** File systems that are another machine's, by the type mountinfo gives. */
		val NETWORK: Set<String> =
			setOf(
				"nfs",
				"nfs4",
				"cifs",
				"smb3",
				"smbfs",
				"9p",
				"ceph",
				"glusterfs",
				"afs",
				"ncpfs",
				"davfs",
				"lustre",
				"ocfs2",
				"gfs2",
				"beegfs",
				"virtiofs",
			)

		/** FUSE file systems that are another machine's, by what follows `fuse.` in their type. */
		val NETWORK_FUSE: Set<String> =
			setOf(
				"sshfs",
				"rclone",
				"davfs",
				"curlftpfs",
				"s3fs",
				"gcsfuse",
				"glusterfs",
				"ceph",
				"smbnetfs",
			)
	}
}
