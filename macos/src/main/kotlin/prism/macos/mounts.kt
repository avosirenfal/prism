package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.UUID
import prism.platform.Dev
import prism.platform.KernelDev
import prism.platform.Mount

/*
 * The volumes mounted, as `getfsstat(2)` lists them, each a `struct statfs`. A Mac mounts a volume whole, so each
 * mount's root is `/`; its flags are said as `mount(8)` says them (`nobrowse`, `local`, `read-only`), and a volume
 * Finder does not show (`nobrowse`: the system's own under /System/Volumes, and devfs) is one prism leaves out too.
 *
 * Since Catalina the system is two volumes: the sealed one at `/`, and the user's data at /System/Volumes/Data,
 * which `/Users`, `/Applications` and the rest of what is the user's are on, joined to `/` by firmlinks. The data
 * volume is `nobrowse`, so what is on it is reached once, from `/`, and not again under /System/Volumes/Data.
 */

internal fun interface Getfsstat {
	operator fun invoke(buffer: MemorySegment, size: Int, flags: Int): Int
}

internal fun interface Getattrlist {
	operator fun invoke(
		path: MemorySegment,
		list: MemorySegment,
		buffer: MemorySegment,
		size: Long,
		options: Int,
	): Int
}

/** The volumes mounted now. */
object MacMounts {
	/** `struct statfs` (the 64-bit inode one, which is arm64's only), as `<sys/mount.h>` lays it out: 2168 bytes. */
	internal object Statfs {
		const val SIZE = 2168L
		const val FSID = 48L
		const val FLAGS = 64L
		const val TYPE_NAME = 72L
		const val TYPE_NAME_LENGTH = 16L
		const val ON_NAME = 88L
		const val FROM_NAME = 1112L
		const val PATH_LENGTH = 1024L
	}

	/** `MNT_NOWAIT`: what the kernel has, without asking each file system again (a share that does not answer). */
	private const val NO_WAIT = 2

	private val getfsstat by Frameworks.system.binding<Getfsstat>("getfsstat")

	/** What is mounted now, in the order the kernel lists it; empty where it will not say. */
	fun current(): List<Mount> =
		Arena.ofConfined().use { arena ->
			val count = getfsstat(MemorySegment.NULL, 0, NO_WAIT)

			if (count <= 0)
				return emptyList()

			// Room for a few more than were counted, in case one is mounted between the two calls.
			val room = count + 8
			val buffer = arena.allocate(Statfs.SIZE * room, 8)

			val listed = getfsstat(buffer, (Statfs.SIZE * room).toInt(), NO_WAIT)

			(0 until listed.coerceAtLeast(0)).map { at ->
				mountOf(buffer.asSlice(at * Statfs.SIZE, Statfs.SIZE), at + 1L)
			}
		}

	/** The mount [statfs] describes, [id] its place in the list. */
	internal fun mountOf(statfs: MemorySegment, id: Long): Mount {
		val flags = statfs.get(JAVA_INT_LE, Statfs.FLAGS)

		return Mount(
			id = id,
			dev = KernelDev(devOf(statfs.get(JAVA_INT_LE, Statfs.FSID))),
			root = "/",
			at = Path.of(text(statfs, Statfs.ON_NAME, Statfs.PATH_LENGTH)),
			type = text(statfs, Statfs.TYPE_NAME, Statfs.TYPE_NAME_LENGTH),
			source = text(statfs, Statfs.FROM_NAME, Statfs.PATH_LENGTH),
			options = MountFlag.entries.filter { flags and it.bit != 0 }.map { it.said },
		)
	}

	/** A fixed field of C text: up to its first NUL, within [length] bytes. */
	private fun text(statfs: MemorySegment, offset: Long, length: Long): String {
		val field = statfs.asSlice(offset, length)
		val end =
			(0 until length).firstOrNull { field.get(JAVA_BYTE, it) == 0.toByte() } ?: length

		return String(field.asSlice(0, end).toArray(JAVA_BYTE), Charsets.UTF_8)
	}

	private val JAVA_INT_LE = JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN)
}

/** Darwin's `dev_t`: the major number in the top 8 bits, the minor in the low 24. */
fun devOf(dev: Int): Dev = Dev((dev ushr 24) and 0xFF, dev and 0xFFFFFF)

/** The flags of a mount that prism tells apart, as `mount(8)` names them. */
enum class MountFlag(
	val bit: Int,
	val said: String,
) {
	ReadOnly(0x1, "read-only"),
	Local(0x1000, "local"),
	Root(0x4000, "root"),
	NoBrowse(0x100000, "nobrowse"),
	Automounted(0x400000, "automounted"),
	Journaled(0x800000, "journaled"),
	Snapshot(0x40000000, "snapshot"),
}

/** Whether [mount] is mounted with [flag]. */
operator fun Mount.contains(flag: MountFlag): Boolean = flag.said in options

/*
 * A volume's own id: its UUID, written in it, the same wherever and whenever it is mounted. `getattrlist(2)` asks the
 * volume at its mount point for `ATTR_VOL_UUID`, which answers with the attributes' length and then the 16 bytes.
 */

private val getattrlist by Frameworks.system.binding<Getattrlist>("getattrlist")

/** `struct attrlist`: the count of its bitmaps (5), then the common, volume, directory, file and fork attributes. */
private const val ATTRLIST_SIZE = 24L
private const val ATTR_BIT_MAP_COUNT: Short = 5
private const val VOLUME_ATTRIBUTES = 8L

/** `ATTR_VOL_INFO`, which every volume attribute is asked with, and `ATTR_VOL_UUID`. */
private const val ATTR_VOL_INFO = 0x80000000.toInt()
private const val ATTR_VOL_UUID = 0x00040000

/** The UUID of the volume mounted at [at]; null where it has none (a share, devfs) or cannot be asked. */
fun volumeUuidOf(at: Path): UUID? =
	Arena.ofConfined().use { arena ->
		val list = arena.allocate(ATTRLIST_SIZE, 4)
		val answer = arena.allocate(4L + 16L, 4)

		list.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 0L, ATTR_BIT_MAP_COUNT)
		list.set(JAVA_INT, VOLUME_ATTRIBUTES, ATTR_VOL_INFO or ATTR_VOL_UUID)

		val result =
			getattrlist(arena.allocateFrom(at.toString()), list, answer, answer.byteSize(), 0)

		if (result != 0 || answer.get(JAVA_INT, 0L) < 20)
			null
		else
			uuidOf(answer.asSlice(4L, 16L).toArray(JAVA_BYTE))
	}

/** A `uuid_t`'s 16 bytes, in the order they are written; null for the nil UUID, which says none was given. */
internal fun uuidOf(bytes: ByteArray): UUID? {
	val high =
		bytes.take(8).fold(0L) { value, byte ->
			(value shl 8) or
				(byte.toLong() and 0xFF)
		}

	val low =
		bytes.drop(8).fold(0L) { value, byte ->
			(value shl 8) or
				(byte.toLong() and 0xFF)
		}

	return UUID(high, low).takeUnless { high == 0L && low == 0L }
}
