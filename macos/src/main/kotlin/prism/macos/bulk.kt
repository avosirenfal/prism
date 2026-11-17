package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_SHORT
import prism.platform.FileKind

/*
 * A folder read a buffer at a time, by `getattrlistbulk(2)`: what `getdents64` is on Linux. One call fills a buffer
 * with hundreds of entries, each with its name, what it is and its file's number, so that reading a folder costs a
 * call for every few hundred entries, where listing it and asking of each entry what it is costs one for every entry.
 *
 * Each entry in the buffer is a record: its length; which of the attributes asked for it has (`ATTR_CMN_RETURNED_ATTRS`,
 * five words, of which the first is the common ones); then those attributes, in the order of their bits. A name is
 * given as where its bytes are, counted from the reference itself, and how many (its NUL among them).
 */

internal fun interface OpenFolder {
	operator fun invoke(path: MemorySegment, flags: Int): Int
}

internal fun interface CloseFile {
	operator fun invoke(fd: Int): Int
}

internal fun interface Getattrlistbulk {
	operator fun invoke(
		fd: Int,
		list: MemorySegment,
		buffer: MemorySegment,
		size: Long,
		options: Long,
	): Int
}

/** An entry as a folder's bulk reading gives it: its name's bytes, what it is (null where it does not say), its number. */
class BulkEntry(
	val name: ByteArray,
	val kind: FileKind?,
	val id: Long,
)

/** A folder's entries, read in bulk. */
object BulkFolder {
	private const val NAME = 0x00000001
	private const val OBJECT_TYPE = 0x00000008
	private const val FILE_ID = 0x02000000
	private const val RETURNED = 0x80000000.toInt()

	/** `O_RDONLY | O_DIRECTORY | O_CLOEXEC`, as Darwin numbers them. */
	private const val FOLDER_FOR_READING = 0x00100000 or 0x01000000

	/** `struct attrlist`'s `ATTR_BIT_MAP_COUNT`: how many groups of attributes it has. */
	private const val GROUPS: Short = 5

	/** Where a record's attributes begin: after its length and the five words of what it has. */
	private const val ATTRIBUTES = 24L

	private const val BUFFER = 256L * 1024

	private val open by Frameworks.system.binding<OpenFolder>("open")
	private val close by Frameworks.system.binding<CloseFile>("close")
	private val bulk by Frameworks.system.binding<Getattrlistbulk>("getattrlistbulk")

	/** `struct attrlist`: the count of its groups, then each group's bits; only the common ones are asked for. */
	private val asked: MemorySegment =
		Arena.global().allocate(24, 4).also {
			it.set(JAVA_SHORT, 0, GROUPS)
			it.set(JAVA_INT_UNALIGNED, 4, RETURNED or NAME or OBJECT_TYPE or FILE_ID)
		}

	/** Each thread's buffer, used again from folder to folder. */
	private val buffers = ThreadLocal.withInitial { Arena.ofAuto().allocate(BUFFER, 8) }

	/** Every entry of the folder at [path] (a C string's bytes, without its NUL); null where it cannot be read so. */
	fun entries(path: ByteArray): List<BulkEntry>? =
		Arena.ofConfined().use { arena ->
			val named =
				arena.allocate(path.size + 1L).also {
					MemorySegment.copy(path, 0, it, JAVA_BYTE, 0, path.size)
				}
			val fd = open(named, FOLDER_FOR_READING)

			if (fd < 0)
				return@use null

			try {
				all(fd)
			} finally {
				close(fd)
			}
		}

	/** Every entry of the folder open as [fd], a buffer at a time until there are no more; null where one fails. */
	private fun all(fd: Int): List<BulkEntry>? {
		val buffer = buffers.get()
		val read = ArrayList<BulkEntry>()

		while (true) {
			val count = bulk(fd, asked, buffer, BUFFER, 0)

			when {
				count < 0 -> return null
				count == 0 -> return read
				else -> records(buffer, count, read::add)
			}
		}
	}

	/** The [count] records at the start of [buffer], each given to [each]; one with no name is passed over. */
	internal fun records(buffer: MemorySegment, count: Int, each: (BulkEntry) -> Unit) {
		var at = 0L

		repeat(count) {
			val length = buffer.get(JAVA_INT_UNALIGNED, at)
			val has = buffer.get(JAVA_INT_UNALIGNED, at + 4)
			var field = at + ATTRIBUTES
			var name: ByteArray? = null
			var kind: FileKind? = null
			var id = 0L

			if (has and NAME != 0) {
				val from = field + buffer.get(JAVA_INT_UNALIGNED, field)
				// The length counts the NUL that ends it.
				val bytes = buffer.get(JAVA_INT_UNALIGNED, field + 4) - 1

				name = buffer.asSlice(from, bytes.toLong()).toArray(JAVA_BYTE)
				field += 8
			}
			if (has and OBJECT_TYPE != 0) {
				kind = kindOf(buffer.get(JAVA_INT_UNALIGNED, field))
				field += 4
			}
			if (has and FILE_ID != 0)
				id = buffer.get(JAVA_LONG_UNALIGNED, field)

			name?.takeIf { it.isNotEmpty() }?.let { each(BulkEntry(it, kind, id)) }
			at += length
		}
	}

	/** What a vnode's type (`enum vtype`) is. */
	private fun kindOf(type: Int): FileKind? =
		when (type) {
			1 -> FileKind.Regular
			2 -> FileKind.Directory
			3 -> FileKind.BlockDevice
			4 -> FileKind.CharDevice
			5 -> FileKind.Symlink
			6 -> FileKind.Socket
			7 -> FileKind.Fifo
			else -> null
		}
}
