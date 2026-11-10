package prism.index

import prism.linux.Dev
import prism.linux.FileKind

/**
 * What an entry of the index is. The index holds folders, files and links, and nothing else (no devices, pipes or
 * sockets). A link is never followed, but what it leads to is kept, since `is:file` and `is:dir` count a link to a
 * file or a folder as one.
 */
enum class Kind(
	/** A folder, or a link to one: what `is:dir` matches. */
	val isDirectory: Boolean,
	/** A file, or a link to one: what `is:file` matches. */
	val isFile: Boolean,
	/** Any link: what `is:symlink` matches. */
	val isLink: Boolean,
) {
	Directory(isDirectory = true, isFile = false, isLink = false),
	File(isDirectory = false, isFile = true, isLink = false),
	LinkToDirectory(isDirectory = true, isFile = false, isLink = true),
	LinkToFile(isDirectory = false, isFile = true, isLink = true),

	/** A link to something that is neither a file nor a folder: a device, a pipe, a socket. */
	LinkToOther(isDirectory = false, isFile = false, isLink = true),

	/** A link that leads nowhere, or round in a loop. */
	BrokenLink(isDirectory = false, isFile = false, isLink = true),
	;

	companion object {
		/**
		 * The kind of an entry listed as [kind] that, as a link, leads to [target]; null for what the index does not
		 * hold: a device, a pipe, a socket, or an entry that was gone before it could be told what it was.
		 */
		fun of(kind: FileKind?, target: FileKind?): Kind? =
			when (kind) {
				FileKind.Directory -> Directory
				FileKind.Regular -> File
				FileKind.Symlink ->
					when (target) {
						FileKind.Directory -> LinkToDirectory
						FileKind.Regular -> LinkToFile
						null -> BrokenLink
						else -> LinkToOther
					}
				else -> null
			}
	}
}

/** What identifies a file on a machine: its device and its inode. A watcher names the folder a change is in by it. */
data class FileId(
	val dev: Dev,
	val ino: Long,
)

/**
 * A file system entries are on: the device a stat of them gives ([dev]), the device of the file system's superblock
 * ([kernel]: what mountinfo lists, and what the watcher names a change by), and the file system's type as mountinfo
 * says (null where it did not say). The two devices are one, but on btrfs, where each subvolume is a device of its own
 * to a stat and the whole file system's one device to the kernel.
 */
data class Device(
	val dev: Dev,
	val kernel: Dev,
	val type: String?,
)

/**
 * Names folded to one case, for matching that does not tell upper from lower: each character of a UTF-8 name made
 * lower case where its lower case is as many bytes long, so a folded name is as long as the name and every offset
 * into one is an offset into the other. What is not UTF-8 is left as it is.
 */
internal object Fold {
	/** [bytes], folded. */
	fun folded(bytes: ByteArray): ByteArray =
		bytes.copyOf().also { fold(bytes, 0, bytes.size, it, 0) }

	/** [source]'s bytes from [from] to [to], folded into [target] at [at]. */
	fun fold(
		source: ByteArray,
		from: Int,
		to: Int,
		target: ByteArray,
		at: Int,
	) {
		var read = from
		var write = at

		while (read < to) {
			val lead = source[read].toInt() and 0xFF

			if (lead < 0x80) {
				target[write++] =
					(
						if (lead in 'A'.code..'Z'.code)
							lead + 32
						else
							lead
					).toByte()
				read++
				continue
			}

			val length = sequenceLength(source, read, to)

			if (length == 0) {
				target[write++] = source[read++]
				continue
			}

			val codePoint = decode(source, read, length)
			val lower = Character.toLowerCase(codePoint)

			if (lower != codePoint && encodedLength(lower) == length)
				encode(lower, target, write)
			else
				System.arraycopy(source, read, target, write, length)

			read += length
			write += length
		}
	}

	/** How many bytes the UTF-8 sequence at [at] takes, or 0 where it is not one. */
	private fun sequenceLength(bytes: ByteArray, at: Int, end: Int): Int {
		val lead = bytes[at].toInt() and 0xFF
		val length =
			when {
				lead in 0xC2..0xDF -> 2
				lead in 0xE0..0xEF -> 3
				lead in 0xF0..0xF4 -> 4
				else -> return 0
			}

		if (at + length > end)
			return 0

		return if ((1 until length).all { (bytes[at + it].toInt() and 0xC0) == 0x80 })
			length
		else
			0
	}

	private fun decode(bytes: ByteArray, at: Int, length: Int): Int {
		val lead = bytes[at].toInt() and 0xFF
		val first =
			when (length) {
				2 -> lead and 0x1F
				3 -> lead and 0x0F
				else -> lead and 0x07
			}

		return (1 until length).fold(first) { codePoint, it ->
			(codePoint shl 6) or
				(bytes[at + it].toInt() and 0x3F)
		}
	}

	private fun encodedLength(codePoint: Int): Int =
		when {
			codePoint < 0x80 -> 1
			codePoint < 0x800 -> 2
			codePoint < 0x10000 -> 3
			else -> 4
		}

	private fun encode(codePoint: Int, target: ByteArray, at: Int) {
		val bytes = String(Character.toChars(codePoint)).encodeToByteArray()

		System.arraycopy(bytes, 0, target, at, bytes.size)
	}
}
