package prism.platform

import java.nio.file.Path
import java.time.Instant

/*
 * Files as every Unix kernel describes them: what an entry is, the device it is on, an inode's facts, and the file
 * systems mounted. The calls that answer with them are each platform's (the linux module's statx and mountinfo, the
 * macos module's getfsstat and the JVM's stat); these are what they answer with.
 */

/** What a directory entry or an inode is. */
enum class FileKind {
	Directory,
	Regular,
	Symlink,
	Fifo,
	Socket,
	CharDevice,
	BlockDevice,
	;

	companion object {
		/** The kind a `d_type` names, or null for `DT_UNKNOWN`: a file system that does not say, which needs a stat. */
		fun ofDirentType(type: Int): FileKind? =
			when (type) {
				4 -> Directory
				8 -> Regular
				10 -> Symlink
				1 -> Fifo
				12 -> Socket
				2 -> CharDevice
				6 -> BlockDevice
				else -> null
			}

		/** The kind an `st_mode`'s `S_IFMT` bits name. */
		fun ofMode(mode: Int): FileKind? =
			when (mode and 0xF000) {
				0x4000 -> Directory
				0x8000 -> Regular
				0xA000 -> Symlink
				0x1000 -> Fifo
				0xC000 -> Socket
				0x2000 -> CharDevice
				0x6000 -> BlockDevice
				else -> null
			}
	}
}

/** A device number, as a stat gives it: major and minor. */
data class Dev(
	val major: Int,
	val minor: Int,
) {
	override fun toString(): String = "$major:$minor"
}

/**
 * A file system's device as the kernel names it: on Linux, its superblock's (`super_block.s_dev`), which is what
 * mountinfo lists and what a BPF program reads; on macOS, a volume's `f_fsid`, which is the device of its files. Not the device a stat of a file on it gives ([Dev]): the two are one number everywhere
 * but on btrfs, where each subvolume is a device of its own to a stat and the whole file system is one device to the
 * kernel. A type of its own, so that neither can be given where the other is meant: a folder looked for by the one a
 * stat gave would be found everywhere but on btrfs.
 */
@JvmInline
value class KernelDev(
	val dev: Dev,
) {
	/** The kernel's own encoding (`MKDEV`: the minor in the low 20 bits), as a BPF program reads it. */
	val encoded: Int get() = (dev.major shl 20) or dev.minor

	override fun toString(): String = dev.toString()

	companion object {
		fun ofEncoded(encoded: Int): KernelDev =
			KernelDev(
				Dev(
					encoded ushr 20,
					encoded and 0xFFFFF,
				),
			)
	}
}

/**
 * What identifies a file on a machine: its file system's device, as the kernel names it, and its inode. A watcher
 * names the folder a change is in by it.
 */
data class FileId(
	val dev: KernelDev,
	val ino: Long,
)

/** An inode as a stat described it: what a listing shows of an entry, and what identifies it. */
data class Stat(
	val kind: FileKind?,
	val permissions: Int,
	val dev: Dev,
	val ino: Long,
	val size: Long,
	val modified: Instant,
	val links: Int,
	/** The mount it was reached through (Linux's `STATX_MNT_ID`), 0 where the kernel did not say. */
	val mountId: Long,
	/** When it was made (`STATX_BTIME`); null where the file system does not keep it. */
	val born: Instant? = null,
)

/**
 * One mounted file system, as the system lists it: [id], the mount's own number (on Linux, what statx's
 * `STATX_MNT_ID` says a file was reached through; on macOS, its place in getfsstat's list); [dev], the kernel's
 * device for it; [root], the folder of its file system that is mounted (`/` on macOS, which mounts volumes whole);
 * [at], where it is mounted; [type], its file system's type; [source], what was mounted (`/dev/sda1`, `tmpfs`,
 * `server:/export`, `/dev/disk3s5`); and [options], its file system's own (`subvol=/@home`, of a btrfs), or on macOS
 * the flags it is mounted with (`nobrowse`, `local`).
 */
data class Mount(
	val id: Long,
	val dev: KernelDev,
	val root: String,
	val at: Path,
	val type: String,
	val source: String,
	val options: List<String> = emptyList(),
) {
	/** The value of the file system's option [name] (`subvol`, of `subvol=/@home`); null where it has none. */
	fun option(name: String): String? =
		options.firstNotNullOfOrNull {
			it.takeIf { it.startsWith("$name=") }?.substringAfter('=')
		}
}
