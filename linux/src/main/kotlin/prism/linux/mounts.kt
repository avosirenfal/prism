package prism.linux

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import prism.ffi.KeepsErrno
import prism.platform.Dev
import prism.platform.KernelDev
import prism.platform.Mount

/** The file systems mounted where this process sees them. */
object Mounts {
	private val MOUNTINFO = Path("/proc/self/mountinfo")

	/** What is mounted now, in the order it was mounted; empty where the kernel does not say (a system without /proc). */
	fun current(): List<Mount> =
		if (MOUNTINFO.exists())
			parse(MOUNTINFO.readLines())
		else
			emptyList()

	/**
	 * `mountinfo`'s lines: `ID PARENT MAJOR:MINOR ROOT MOUNT_POINT OPTIONS OPTIONAL... - TYPE SOURCE SUPER_OPTIONS`. A
	 * path's spaces, tabs, newlines and backslashes are written as octal escapes (`\040`).
	 */
	fun parse(lines: List<String>): List<Mount> =
		lines.mapNotNull { line ->
			val fields = line.split(' ')
			val separator = fields.indexOf("-")
			val device = fields.getOrNull(2)?.split(':')
			val id = fields.getOrNull(0)?.toLongOrNull()

			if (id == null || separator < 5 || fields.size <= separator + 2 || device?.size != 2)
				null
			else
				Mount(
					id,
					KernelDev(Dev(device[0].toInt(), device[1].toInt())),
					unescaped(fields[3]),
					Path(unescaped(fields[4])),
					fields[separator + 1],
					unescaped(fields[separator + 2]),
					fields
						.getOrNull(separator + 3)
						?.split(',')
						?.filter { it.isNotEmpty() }
						.orEmpty(),
				)
		}

	private val ESCAPE = Regex("\\\\([0-7]{3})")

	private fun unescaped(field: String): String =
		ESCAPE.replace(field) {
			it.groupValues[1]
				.toInt(8)
				.toChar()
				.toString()
		}
}

internal fun interface MountCall {
	@KeepsErrno
	operator fun invoke(
		source: MemorySegment,
		target: MemorySegment,
		type: MemorySegment,
		flags: Long,
		data: MemorySegment,
	): Int
}

internal fun interface Umount2 {
	@KeepsErrno
	operator fun invoke(target: MemorySegment, flags: Int): Int
}

internal fun interface Statfs {
	@KeepsErrno
	operator fun invoke(path: MemorySegment, into: MemorySegment): Int
}

private val mountCall: MountCall = Libc.bind("mount")
private val umountCall: Umount2 = Libc.bind("umount2")
private val statfsCall: Statfs = Libc.bind("statfs")

/**
 * `mount(2)`: a file system of [type] mounted at [target], with [options] as that file system reads them
 * (`mode=0750`). For the kernel's own file systems, which have nothing behind them to name, [source] is only what
 * the mount is called. It needs `CAP_SYS_ADMIN`.
 */
fun mount(source: String, target: Path, type: String, options: String = ""): Sys<Unit> =
	Arena.ofConfined().use { arena ->
		val result =
			mountCall(
				arena.allocateFrom(source),
				arena.allocateFrom(target.toString()),
				arena.allocateFrom(type),
				0L,
				arena.allocateFrom(options),
			)

		CallState.outcome("mount", result.toLong()) { }
	}

/** `umount2(2)`: what is mounted at [target] taken away, once nothing is using it (`MNT_DETACH`). */
fun unmount(target: Path): Sys<Unit> =
	Arena.ofConfined().use { arena ->
		val result =
			umountCall(arena.allocateFrom(target.toString()), MNT_DETACH)

		CallState.outcome("umount2", result.toLong()) { }
	}

private const val MNT_DETACH = 2

/** As many bytes as `struct statfs` is, and more. */
private const val STATFS_SIZE = 128L

/** Where `f_fsid` is in `struct statfs`, on a 64-bit Linux: after seven words. */
private const val STATFS_FSID = 56L

/**
 * `statfs(2)`'s `f_fsid` of the file system [path] is on: a number the file system gives itself. Some keep it from
 * one mount to the next (ZFS and btrfs make it from what is written on the disk; ext4 from its UUID), and some make
 * it from the device's number, which is another every time: what it is worth is the file system's to say, and whoever
 * asks knows which they asked.
 */
fun fsidOf(path: Path): Sys<Long> =
	Arena.ofConfined().use { arena ->
		val scratch = arena.allocate(STATFS_SIZE, 8)

		val result =
			statfsCall(arena.allocateFrom(path.toString()), scratch)

		CallState.outcome("statfs", result.toLong()) { scratch.get(JAVA_LONG, STATFS_FSID) }
	}
