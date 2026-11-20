package prism.thumbnails

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/*
 * The pictures a file carries of itself. A camera writes a small JPEG of each photograph into the photograph's own
 * file: a thumbnail in a JPEG's Exif, and in a raw file a preview, often as large as the photograph, since developing
 * the raw data is slow even for the camera. Reading one out is a few hundred kilobytes read from a file of tens of
 * megabytes, and nothing decoded but an ordinary JPEG, so it is what a raw file's thumbnail is made from wherever
 * there is one.
 *
 * Nearly every raw format is a TIFF: a header that says which way round its numbers are and where the first directory
 * is, and directories of tagged entries, each of which may lead to further directories. A preview is a directory that
 * says where a JPEG lies and how long it is. So one reader of TIFF directories finds the previews of Canon's CR2,
 * Nikon's NEF, Sony's ARW, Adobe's DNG, Pentax's PEF, Olympus' ORF and Panasonic's RW2 (the last two with a magic
 * number of their own), and the Exif thumbnail of a JPEG, which is a TIFF inside one of the JPEG's segments. Fuji's
 * RAF is not a TIFF, and says where its JPEG is at a fixed place. Canon's CR3 is not read.
 *
 * Everything read is checked before it is believed: an offset past the end of the file, a directory that leads to
 * itself, a count of millions, are a file with no preview, never an error. What is found is checked to be a JPEG a
 * decoder reads (raw data is itself often held as a lossless JPEG, in a directory that looks like a preview's).
 */

/** A picture a file carries of itself: where its JPEG lies, how large the picture is, and which way up it is held. */
data class Preview(
	val offset: Long,
	val length: Int,
	val extent: Extent,
	/** How the picture is turned, as Exif numbers it (1 upright, to 8); null where nothing says. */
	val orientation: Int?,
)

/** The pictures files carry of themselves. */
object Embedded {
	/** The extensions of the files looked in: JPEGs, TIFFs, and the raw formats that are TIFFs or are known. */
	val EXTENSIONS: Set<String> =
		setOf(
			"jpg",
			"jpeg",
			"jpe",
			"tif",
			"tiff",
			"dng",
			"cr2",
			"nef",
			"nrw",
			"arw",
			"sr2",
			"srf",
			"pef",
			"orf",
			"rw2",
			"raf",
			"srw",
			"3fr",
			"erf",
			"kdc",
			"dcr",
			"mos",
			"mef",
			"iiq",
			"rwl",
		)

	/** No preview is longer, in bytes: one that says it is, is not one. */
	private const val LONGEST = 64 * 1024 * 1024

	/** No file has more directories worth reading, nor a directory more entries. */
	private const val MOST_DIRECTORIES = 32
	private const val MOST_ENTRIES = 512

	/** How far into a JPEG its size is looked for. */
	private const val JPEG_HEAD = 256 * 1024

	/** The previews [file] carries, each a JPEG that can be decoded; none where it has none, or cannot be read. */
	fun previews(file: Path): List<Preview> =
		try {
			FileChannel.open(file, StandardOpenOption.READ).use(::previews)
		} catch (failed: IOException) {
			emptyList()
		}

	/**
	 * The preview of [file] to make a picture [side] across from, as its JPEG's bytes and how it is turned: the
	 * smallest that is at least that large, so that no more is decoded than is needed; and where none is that large,
	 * the largest there is, unless only one [atLeast] that large will do. Null where there is none.
	 */
	fun read(file: Path, side: Int, atLeast: Boolean): Pair<ByteArray, Int?>? =
		try {
			FileChannel.open(file, StandardOpenOption.READ).use { channel ->
				chosen(previews(channel), side, atLeast)?.let { preview ->
					bytes(channel, preview.offset, preview.length)?.let {
						it.array() to preview.orientation
					}
				}
			}
		} catch (failed: IOException) {
			null
		}

	/** Which of [previews] a picture [side] across is made from. */
	internal fun chosen(previews: List<Preview>, side: Int, atLeast: Boolean): Preview? =
		previews.filter { it.extent.longest >= side }.minByOrNull { it.extent.pixels }
			?: previews.maxByOrNull { it.extent.pixels }.takeUnless { atLeast }

	private fun previews(channel: FileChannel): List<Preview> {
		val head = bytes(channel, 0, 16) ?: return emptyList()

		val found =
			when {
				head.get(0) == 0xFF.toByte() && head.get(1) == 0xD8.toByte() -> ofJpeg(channel)
				String(
					head.array(),
					0,
					15,
					Charsets.ISO_8859_1,
				) == "FUJIFILMCCD-RAW" -> ofRaf(channel)
				else -> ofTiff(channel, 0)
			}

		return found.mapNotNull { claimed -> checked(channel, claimed) }.distinct()
	}

	/** What a directory says of a preview, before any of it is believed. */
	private data class Claimed(
		val offset: Long,
		val length: Long,
		val orientation: Int?,
	)

	/** [claimed] as a preview, where a JPEG that can be decoded does lie there, whole. */
	private fun checked(channel: FileChannel, claimed: Claimed): Preview? {
		if (claimed.offset <= 0 || claimed.length !in 4..LONGEST ||
			claimed.offset + claimed.length > channel.size()
		)
			return null

		val head =
			bytes(channel, claimed.offset, minOf(claimed.length, JPEG_HEAD.toLong()).toInt())
				?: return null

		return Jpeg.extent(head.array())?.let {
			Preview(claimed.offset, claimed.length.toInt(), it, claimed.orientation)
		}
	}

	/** A JPEG's Exif: a TIFF in one of the segments before its picture, whose second directory is the thumbnail's. */
	private fun ofJpeg(channel: FileChannel): List<Claimed> {
		var at = 2L

		repeat(MOST_DIRECTORIES) {
			val segment = bytes(channel, at, 10) ?: return emptyList()

			if (segment.get(0) != 0xFF.toByte())
				return emptyList()

			val marker = segment.get(1).toInt() and 0xFF
			val length = segment.getShort(2).toInt() and 0xFFFF

			// The picture itself begins: there was no Exif before it.
			if (marker == 0xDA || length < 2)
				return emptyList()
			if (marker == 0xE1 &&
				String(segment.array(), 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000"
			)
				return ofTiff(channel, at + 10)
			at += 2 + length
		}

		return emptyList()
	}

	/** Fuji's: where its JPEG lies and how long it is, at a fixed place in the header, the high byte first. */
	private fun ofRaf(channel: FileChannel): List<Claimed> {
		val said = bytes(channel, 84, 8) ?: return emptyList()

		return listOf(
			Claimed(
				said.int.toLong() and 0xFFFFFFFFL,
				said.int.toLong() and 0xFFFFFFFFL,
				// A Fuji's JPEG says how it is turned itself, in its own Exif.
				null,
			),
		)
	}

	/** One entry of a directory: its tag, the type and count of its values, and where they are. */
	private class Tagged(
		val tag: Int,
		val type: Int,
		val count: Long,
		/** Where the four bytes are that hold the values, or say where they are. */
		val at: Long,
	)

	/** A TIFF beginning at [base] of the file: every directory it has, each asked for its preview. */
	private fun ofTiff(channel: FileChannel, base: Long): List<Claimed> {
		val header = bytes(channel, base, 8) ?: return emptyList()

		val order =
			when (String(header.array(), 0, 2, Charsets.ISO_8859_1)) {
				"II" -> ByteOrder.LITTLE_ENDIAN
				"MM" -> ByteOrder.BIG_ENDIAN
				else -> return emptyList()
			}

		header.order(order)

		// 42 is TIFF's own; Olympus and Panasonic write numbers of theirs where it would be.
		if ((header.getShort(2).toInt() and 0xFFFF) !in MAGICS)
			return emptyList()

		val reader = Reader(channel, base, order)
		val waiting = ArrayDeque(listOf(header.getInt(4).toLong() and 0xFFFFFFFFL))
		val seen = HashSet<Long>()
		val claimed = ArrayList<Claimed>()
		var orientation: Int? = null

		while (waiting.isNotEmpty() && seen.size < MOST_DIRECTORIES) {
			val at = waiting.removeFirst()

			if (at == 0L || !seen.add(at))
				continue

			val entries = reader.directory(at) ?: continue
			val first = seen.size == 1

			fun one(tag: Int): Long? =
				entries.firstOrNull { it.tag == tag }?.let { reader.values(it).firstOrNull() }

			// How the photograph is turned is said once, of the photograph: its previews are turned the same.
			if (first)
				orientation = one(ORIENTATION)?.toInt()?.takeIf { it in 1..8 }

			one(JPEG_OFFSET)?.let { offset ->
				one(JPEG_LENGTH)?.let { claimed += Claimed(base + offset, it, null) }
			}
			// A picture held as one strip, compressed as a JPEG: a preview, or raw data held so, told apart later.
			if (one(COMPRESSION) in JPEGS)
				entries.firstOrNull { it.tag == STRIP_OFFSETS }?.takeIf { it.count == 1L }?.let {
					one(STRIP_OFFSETS)?.let { offset ->
						one(STRIP_LENGTHS)?.let { claimed += Claimed(base + offset, it, null) }
					}
				}
			// Panasonic's: the JPEG is the entry's own value.
			entries.firstOrNull { it.tag == JPEG_FROM_RAW }?.let { entry ->
				reader.placed(entry)?.let { claimed += Claimed(base + it, entry.count, null) }
			}
			entries.filter { it.tag == SUB_DIRECTORIES }.forEach { waiting += reader.values(it) }
			reader.next(at, entries.size)?.let { waiting += it }
		}

		return claimed.map { it.copy(orientation = orientation) }
	}

	private val MAGICS = setOf(42, 0x4F52, 0x5352, 0x55)

	private const val COMPRESSION = 0x0103
	private const val STRIP_OFFSETS = 0x0111
	private const val ORIENTATION = 0x0112
	private const val STRIP_LENGTHS = 0x0117
	private const val SUB_DIRECTORIES = 0x014A
	private const val JPEG_OFFSET = 0x0201
	private const val JPEG_LENGTH = 0x0202
	private const val JPEG_FROM_RAW = 0x002E

	/** The compressions that are JPEG: the old way of saying so, and the new. */
	private val JPEGS = setOf<Long?>(6, 7)

	/** A TIFF's directories read: its numbers the way round [order] says, its offsets from [base]. */
	private class Reader(
		private val channel: FileChannel,
		private val base: Long,
		private val order: ByteOrder,
	) {
		/** The entries of the directory at [at]; null where there is none there. */
		fun directory(at: Long): List<Tagged>? {
			val count =
				bytes(channel, base + at, 2)?.order(order)?.short?.let { it.toInt() and 0xFFFF }
					?: return null

			if (count !in 1..MOST_ENTRIES)
				return null

			val entries = bytes(channel, base + at + 2, count * ENTRY)?.order(order) ?: return null

			return (0 until count).map { index ->
				Tagged(
					entries.getShort(index * ENTRY).toInt() and 0xFFFF,
					entries.getShort(index * ENTRY + 2).toInt() and 0xFFFF,
					entries.getInt(index * ENTRY + 4).toLong() and 0xFFFFFFFFL,
					at + 2 + index * ENTRY + 8,
				)
			}
		}

		/** Where the directory after the one at [at], of [count] entries, is; null where it is the last. */
		fun next(at: Long, count: Int): Long? =
			bytes(channel, base + at + 2 + count.toLong() * ENTRY, 4)
				?.order(order)
				?.int
				?.let { it.toLong() and 0xFFFFFFFFL }
				?.takeIf { it != 0L }

		/** Where [entry]'s values are, from the TIFF's start: in the entry itself where they fit in its four bytes. */
		fun placed(entry: Tagged): Long? {
			val size = SIZES[entry.type] ?: return null

			return if (entry.count * size <= 4)
				entry.at
			else
				bytes(channel, base + entry.at, 4)?.order(order)?.int?.let {
					it.toLong() and
						0xFFFFFFFFL
				}
		}

		/** [entry]'s values, where they are whole numbers: the first few of them, which is all anything here asks. */
		fun values(entry: Tagged): List<Long> {
			val size = SIZES[entry.type]?.takeIf { entry.type in NUMBERS } ?: return emptyList()
			val count = minOf(entry.count, MOST_VALUES).toInt()
			val at = placed(entry) ?: return emptyList()
			val held = bytes(channel, base + at, count * size)?.order(order) ?: return emptyList()

			return (0 until count).map { index ->
				when (size) {
					1 -> held.get(index).toLong() and 0xFF
					2 -> held.getShort(index * 2).toLong() and 0xFFFF
					else -> held.getInt(index * 4).toLong() and 0xFFFFFFFFL
				}
			}
		}

		private companion object {
			const val ENTRY = 12
			const val MOST_VALUES = 16L

			/** How many bytes a value of each type is: bytes and text, shorts, longs, and offsets of directories. */
			val SIZES =
				mapOf(
					1 to 1,
					2 to 1,
					3 to 2,
					4 to 4,
					6 to 1,
					7 to 1,
					8 to 2,
					9 to 4,
					13 to 4,
				)

			/** The types that are whole numbers without a sign. */
			val NUMBERS = setOf(1, 3, 4, 13)
		}
	}

	/** [count] bytes of [channel] from [at]; null where it ends before them, or they are not there to be had. */
	private fun bytes(channel: FileChannel, at: Long, count: Int): ByteBuffer? {
		if (at < 0 || count < 0 || at + count > channel.size())
			return null

		val buffer = ByteBuffer.allocate(count)
		var position = at

		while (buffer.hasRemaining()) {
			val read = channel.read(buffer, position)

			if (read <= 0)
				return null
			position += read
		}

		return buffer.flip()
	}
}

/** A JPEG's bytes, as far as saying how large its picture is. */
object Jpeg {
	/**
	 * How large the picture of the JPEG that [bytes] begin is, as the frame header among its first segments says; null
	 * where they do not begin a JPEG, the header is not among them, or it is of a kind no ordinary decoder reads
	 * (lossless, which is how raw data is held, never a picture to look at).
	 */
	fun extent(bytes: ByteArray): Extent? {
		if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte())
			return null

		var at = 2

		while (at + 9 < bytes.size) {
			if (bytes[at] != 0xFF.toByte())
				return null

			val marker = bytes[at + 1].toInt() and 0xFF

			// Padding before a marker, and the markers that stand alone.
			if (marker == 0xFF) {
				at++
				continue
			}
			if (marker == 0x01 || marker in 0xD0..0xD8) {
				at += 2
				continue
			}

			val length =
				((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

			if (marker in FRAMES) {
				val height =
					((bytes[at + 5].toInt() and 0xFF) shl 8) or (bytes[at + 6].toInt() and 0xFF)

				val width =
					((bytes[at + 7].toInt() and 0xFF) shl 8) or (bytes[at + 8].toInt() and 0xFF)

				return Extent(width, height).takeIf { width > 0 && height > 0 && marker in DECODED }
			}
			if (marker == 0xDA || length < 2)
				return null
			at += 2 + length
		}

		return null
	}

	/** The markers that begin a frame: every `SOF`, which are `C0` to `CF` but the three that are tables. */
	private val FRAMES = (0xC0..0xCF).toSet() - setOf(0xC4, 0xC8, 0xCC)

	/** The frames an ordinary decoder reads: baseline, extended and progressive. */
	private val DECODED = setOf(0xC0, 0xC1, 0xC2)
}
