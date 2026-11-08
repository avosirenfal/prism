package barion.linux

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.time.Instant

/** An open file descriptor. Closing it is the owner's ([close]); nothing here closes one behind its back. */
@JvmInline
value class Fd(
	val number: Int,
) {
	companion object {
		/** `AT_FDCWD`: a path relative to the working directory, where a call takes a directory and a path. */
		val CWD = Fd(-100)
	}
}

/** `open(2)` flags, by name. */
@JvmInline
value class OpenFlags(
	val bits: Int,
) {
	infix fun or(other: OpenFlags) = OpenFlags(bits or other.bits)

	operator fun contains(other: OpenFlags) = bits and other.bits == other.bits

	companion object {
		val READ_ONLY = OpenFlags(0)
		val NO_FOLLOW = OpenFlags(0x20000)
		val DIRECTORY = OpenFlags(0x10000)
		val CLOSE_ON_EXEC = OpenFlags(0x80000)

		/** `O_NOATIME`: reading does not touch the access time; refused (EPERM) on files the caller does not own. */
		val NO_ACCESS_TIME = OpenFlags(0x40000)

		/** What the scanner opens a directory with: for reading its entries, never through a link, never inherited. */
		val DIRECTORY_FOR_READING = READ_ONLY or DIRECTORY or NO_FOLLOW or CLOSE_ON_EXEC
	}
}

/** `AT_*` flags of the calls that take a directory and a path. */
@JvmInline
value class AtFlags(
	val bits: Int,
) {
	infix fun or(other: AtFlags) = AtFlags(bits or other.bits)

	companion object {
		val NONE = AtFlags(0)
		val SYMLINK_NO_FOLLOW = AtFlags(0x100)

		/** The path is empty and the call is about the directory descriptor itself. */
		val EMPTY_PATH = AtFlags(0x1000)

		/** `AT_STATX_DONT_SYNC`: what the client has cached, for a network file system, without asking the server. */
		val STATX_DONT_SYNC = AtFlags(0x4000)
	}
}

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

/** A device number, as statx gives it (major and minor) and as the kernel keeps it (`MKDEV`: the minor in 20 bits). */
data class Dev(
	val major: Int,
	val minor: Int,
) {
	/** The kernel's own encoding, which a BPF program reads from `super_block.s_dev`. */
	val kernel: Int get() = (major shl 20) or minor

	override fun toString(): String = "$major:$minor"

	companion object {
		fun ofKernel(encoded: Int): Dev = Dev(encoded ushr 20, encoded and 0xFFFFF)
	}
}

/** An inode as statx described it: what a listing shows of an entry, and what identifies it. */
data class Stat(
	val kind: FileKind?,
	val permissions: Int,
	val dev: Dev,
	val ino: Long,
	val size: Long,
	val modified: Instant,
	val links: Int,
	/** The mount it was reached through (`STATX_MNT_ID`), 0 where the kernel did not say. */
	val mountId: Long,
)

private val openatHandle =
	Libc.capturing(
		"openat",
		FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
		variadicFrom = 3,
	)
private val closeHandle = Libc.capturing("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
private val getdentsHandle =
	Libc.capturing("getdents64", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG))
private val statxHandle =
	Libc.capturing("statx", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS))

/** `openat(2)`: [path], a NUL-terminated C string, relative to [dir]. */
fun openat(dir: Fd, path: MemorySegment, flags: OpenFlags): Sys<Fd> {
	val result = openatHandle.invokeExact(Libc.callState(), dir.number, path, flags.bits, 0) as Int

	return Libc.outcome("openat", result.toLong()) { Fd(it.toInt()) }
}

/** `openat(2)` of a path given as text (UTF-8, as the JVM names files). */
fun openat(dir: Fd, path: String, flags: OpenFlags): Sys<Fd> =
	Arena.ofConfined().use { arena -> openat(dir, arena.allocateFrom(path), flags) }

fun close(fd: Fd): Sys<Unit> {
	val result = closeHandle.invokeExact(Libc.callState(), fd.number) as Int

	return Libc.outcome("close", result.toLong()) { }
}

/** Opens [path] for the duration of [use], closing it however [use] ends. */
inline fun <T> withOpened(dir: Fd, path: String, flags: OpenFlags, use: (Fd) -> Sys<T>): Sys<T> =
	openat(dir, path, flags).then { fd ->
		try {
			use(fd)
		} finally {
			close(fd)
		}
	}

/** The `struct statx` statx(2) fills: 256 bytes, whatever the kernel. */
private const val STATX_SIZE = 256L

/** `STATX_BASIC_STATS | STATX_MNT_ID`: what [Stat] holds. */
private const val STATX_MASK = 0x7FF or 0x1000

/** `statx(2)` of [path] (a C string) relative to [dir], into [scratch] (at least 256 bytes), as a [Stat]. */
fun statx(dir: Fd, path: MemorySegment, flags: AtFlags, scratch: MemorySegment): Sys<Stat> {
	require(scratch.byteSize() >= STATX_SIZE) { "statx writes 256 bytes" }

	val result =
		statxHandle.invokeExact(Libc.callState(), dir.number, path, flags.bits, STATX_MASK, scratch) as Int

	return Libc.outcome("statx", result.toLong()) {
		val mode = scratch.get(JAVA_SHORT, 28).toInt() and 0xFFFF

		Stat(
			kind = FileKind.ofMode(mode),
			permissions = mode and 0xFFF,
			dev = Dev(scratch.get(JAVA_INT, 136), scratch.get(JAVA_INT, 140)),
			ino = scratch.get(JAVA_LONG, 32),
			size = scratch.get(JAVA_LONG, 40),
			modified = Instant.ofEpochSecond(scratch.get(JAVA_LONG, 112), scratch.get(JAVA_INT, 120).toLong()),
			links = scratch.get(JAVA_INT, 16),
			mountId = scratch.get(JAVA_LONG, 144),
		)
	}
}

/** `statx(2)` of a path given as text. */
fun statx(dir: Fd, path: String, flags: AtFlags = AtFlags.NONE): Sys<Stat> =
	Arena.ofConfined().use { arena -> statx(dir, arena.allocateFrom(path), flags, arena.allocate(STATX_SIZE)) }

/**
 * A buffer `getdents64(2)` fills with a directory's entries, read in place: each entry's inode, kind and name are
 * where the kernel wrote them, so reading a directory allocates nothing per entry. What [forEach] hands out is valid
 * until the buffer is filled again. One thread's: it is reused from directory to directory.
 */
class DirentBuffer(
	arena: Arena,
	size: Int = 64 * 1024,
) {
	val segment: MemorySegment = arena.allocate(size.toLong(), 8)

	/** Fills the buffer with [dir]'s next entries: the bytes written, 0 at the end of the directory. */
	fun fill(dir: Fd): Sys<Int> {
		val result = getdentsHandle.invokeExact(Libc.callState(), dir.number, segment, segment.byteSize()) as Long

		return Libc.outcome("getdents64", result) { it.toInt() }
	}

	/**
	 * Every entry of the first [length] bytes but `.` and `..`, in the order the kernel wrote them: its inode, its
	 * kind (null where the file system does not say), and where its name starts in [segment], NUL-terminated, with
	 * its length in bytes.
	 */
	inline fun forEach(length: Int, visit: (ino: Long, kind: FileKind?, name: Long, nameLength: Int) -> Unit) {
		var at = 0L

		while (at < length) {
			val recordLength = segment.get(JAVA_SHORT, at + 16).toInt() and 0xFFFF
			val name = at + NAME_OFFSET
			val nameLength = nameLength(name, at + recordLength)

			if (!isDots(name, nameLength))
				visit(
					segment.get(JAVA_LONG, at),
					FileKind.ofDirentType(segment.get(JAVA_BYTE, at + 18).toInt()),
					name,
					nameLength,
				)
			at += recordLength
		}
	}

	/** The bytes of the name at [name], [length] long. */
	fun nameBytes(name: Long, length: Int): ByteArray = segment.asSlice(name, length.toLong()).toArray(JAVA_BYTE)

	/** The name at [name] as text, decoded as the JVM names files (UTF-8). */
	fun nameString(name: Long): String = segment.getString(name)

	/** The name at [name] as a C string, for a call relative to the directory it is in. */
	fun nameSegment(name: Long, length: Int): MemorySegment = segment.asSlice(name, length + 1L)

	@PublishedApi
	internal fun nameLength(name: Long, end: Long): Int {
		var at = name

		while (at < end && segment.get(JAVA_BYTE, at) != 0.toByte())
			at++

		return (at - name).toInt()
	}

	@PublishedApi
	internal fun isDots(name: Long, length: Int): Boolean =
		segment.get(JAVA_BYTE, name) == DOT &&
			(length == 1 || (length == 2 && segment.get(JAVA_BYTE, name + 1) == DOT))

	companion object {
		/** `struct linux_dirent64`: `d_ino` at 0, `d_off` at 8, `d_reclen` at 16, `d_type` at 18, `d_name` at 19. */
		const val NAME_OFFSET = 19L

		@PublishedApi
		internal const val DOT = '.'.code.toByte()
	}
}
