package prism.thumbnails

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * A PNG file as a thumbnail needs it known: how large its picture is, the text it holds beside the picture (which is
 * where a thumbnail says what file it is of), and that text put into one. The picture itself is never decoded here,
 * nor encoded: a PNG is a signature and then chunks, each with its length, its type and its checksum, and text is
 * chunks of its own, so text is read by going from chunk to chunk, and written by putting chunks among the others.
 */

/** How large a picture is, in pixels. */
data class Extent(
	val width: Int,
	val height: Int,
) {
	/** How many pixels it has in all. */
	val pixels: Long get() = width.toLong() * height

	/** Its longer side. */
	val longest: Int get() = maxOf(width, height)

	/** Whether it fits a square [side] across. */
	fun within(side: Int): Boolean = longest <= side
}

/** A PNG's bytes, taken apart and put together chunk by chunk. */
object Png {
	internal val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

	/** A chunk's length and type, and after its data its checksum. */
	internal const val HEADER = 8
	internal const val CHECKSUM = 4

	/** The header chunk's data: the width, the height, and five bytes of how the pixels are held. */
	private const val IHDR = 13

	/** Where the chunks after the header chunk begin. */
	private val AFTER_HEADER = SIGNATURE.size + HEADER + IHDR + CHECKSUM

	/** Whether [bytes] begin as a PNG does. */
	fun signed(bytes: ByteArray): Boolean =
		bytes.size >= SIGNATURE.size &&
			SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

	/** How large the picture of the PNG [bytes] are is, as its header says; null where they are no PNG. */
	fun extent(bytes: ByteArray): Extent? {
		if (!signed(bytes) || bytes.size < AFTER_HEADER)
			return null

		val header = ByteBuffer.wrap(bytes, SIGNATURE.size, HEADER + IHDR)

		if (header.int != IHDR || header.int != TYPE_IHDR)
			return null

		val width = header.int
		val height = header.int

		return Extent(width, height).takeIf { width > 0 && height > 0 }
	}

	/**
	 * The PNG [png] saying [text] beside its picture: a text chunk for each, put straight after the header, where any
	 * reader comes to them first, and whatever the PNG said under the same keywords taken out, so that it says each
	 * thing once. The picture's own chunks are as they were, byte for byte. Null where [png] is no PNG, or is cut
	 * short.
	 */
	fun saying(png: ByteArray, text: Map<String, String>): ByteArray? {
		if (extent(png) == null)
			return null

		val out = ByteArrayOutputStream(png.size + text.size * 64)

		out.write(png, 0, AFTER_HEADER)
		for ((keyword, value) in text)
			out.write(chunk("tEXt", latin(keyword) + 0.toByte() + latin(value)))

		var at = AFTER_HEADER

		while (at + HEADER + CHECKSUM <= png.size) {
			val length = ByteBuffer.wrap(png, at, 4).int
			val whole = HEADER.toLong() + length + CHECKSUM

			if (length < 0 || at + whole > png.size)
				return null

			val type = String(png, at + 4, 4, Charsets.ISO_8859_1)
			val keyword = keywordOf(type, png, at + HEADER, length)

			if (keyword == null || keyword !in text)
				out.write(png, at, whole.toInt())
			at += whole.toInt()
			if (type == "IEND")
				return out.toByteArray()
		}

		// It never ended: not a whole PNG.
		return null
	}

	/** The keyword of a text chunk of [type] whose data is [length] bytes of [png] from [from]; null for any other. */
	private fun keywordOf(type: String, png: ByteArray, from: Int, length: Int): String? {
		if (type !in PngText.TEXTS)
			return null

		val end =
			(from until from + length).firstOrNull { png[it] == 0.toByte() } ?: return null

		return String(png, from, end - from, Charsets.ISO_8859_1)
	}

	/** One chunk: its length, its [type], its [data], and the checksum of the last two. */
	internal fun chunk(type: String, data: ByteArray): ByteArray {
		val named = latin(type) + data
		val checksum = CRC32().apply { update(named) }.value.toInt()

		return ByteBuffer
			.allocate(4 + named.size + CHECKSUM)
			.putInt(data.size)
			.put(named)
			.putInt(checksum)
			.array()
	}

	private fun latin(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

	private const val TYPE_IHDR = 0x49484452

	/**
	 * A PNG of one transparent pixel: what a record that a file has no thumbnail is, since the standard has such a
	 * record be a PNG, read for its text alone.
	 */
	val BLANK: ByteArray by lazy {
		val header =
			ByteBuffer
				.allocate(IHDR)
				.putInt(1)
				.putInt(1)
				// Eight bits a channel, red, green, blue and alpha; deflated, filtered the one way, not interlaced.
				.put(8)
				.put(6)
				.put(0)
				.put(0)
				.put(0)
				.array()

		// One row: its filter (none), and one pixel of four zero bytes.
		val row = ByteArray(5)

		val deflater =
			Deflater().apply {
				setInput(row)
				finish()
			}
		val data = ByteArray(64)
		val count = deflater.deflate(data)

		deflater.end()

		SIGNATURE + chunk("IHDR", header) + chunk("IDAT", data.copyOf(count)) +
			chunk("IEND", ByteArray(0))
	}
}

/**
 * The text a PNG file holds beside its picture: each keyword and what is said under it. Read by going from one of
 * the file's chunks to the next and reading only those that are text (`tEXt`, `zTXt`, `iTXt`), wherever in the file
 * they are: the picture itself is neither read nor decoded.
 */
object PngText {
	private val SIGNATURE = Png.SIGNATURE

	/** A chunk's length and type, and after its data its checksum. */
	private const val HEADER = 8
	private const val CHECKSUM = 4

	/** No text of a thumbnail's is longer; one that says it is, is not read. */
	private const val LONGEST = 1 shl 16

	/** No PNG worth reading has more chunks: a file that goes on is given up. */
	private const val MOST = 4096

	/** The text of the PNG at [file]; null where it is not there, cannot be read, or is no PNG. */
	fun read(file: Path): Map<String, String>? =
		try {
			FileChannel.open(file, StandardOpenOption.READ).use(::read)
		} catch (failed: IOException) {
			null
		}

	private fun read(channel: FileChannel): Map<String, String>? {
		if (!filled(channel, 0, SIGNATURE.size)?.array().contentEquals(SIGNATURE))
			return null

		val text = HashMap<String, String>()
		var at = SIGNATURE.size.toLong()

		repeat(MOST) {
			val header = filled(channel, at, HEADER) ?: return text
			val length = header.int.toLong() and 0xFFFFFFFFL
			val type = String(header.array(), 4, 4, Charsets.ISO_8859_1)

			if (type == "IEND")
				return text

			if (type in TEXTS && length <= LONGEST)
				filled(channel, at + HEADER, length.toInt())
					?.array()
					?.let { said(type, it) }
					?.let { (keyword, value) -> text.putIfAbsent(keyword, value) }

			at += HEADER + length + CHECKSUM
		}

		return text
	}

	internal val TEXTS = setOf("tEXt", "zTXt", "iTXt")

	/** [count] bytes of [channel] from [at]; null where it ends before them. */
	private fun filled(channel: FileChannel, at: Long, count: Int): ByteBuffer? {
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

	/** The keyword and the text of a text chunk of [type] whose data is [data]; null where it is not as one is. */
	private fun said(type: String, data: ByteArray): Pair<String, String>? {
		val end = data.indexOf(0.toByte())

		if (end <= 0)
			return null

		val keyword = String(data, 0, end, Charsets.ISO_8859_1)
		val rest = end + 1

		return when (type) {
			"tEXt" -> String(data, rest, data.size - rest, Charsets.ISO_8859_1)
			// After the keyword, how it is compressed (always deflate), then the text.
			"zTXt" ->
				inflated(data, rest + 1)?.let { String(it, Charsets.ISO_8859_1) }
			// After the keyword: whether it is compressed, how, a language and a translated keyword, each ended by a
			// zero, and then the text, in UTF-8.
			else -> {
				val compressed = data.getOrNull(rest)?.toInt() == 1
				val language = data.indexOfFrom(0.toByte(), rest + 2)
				val translated =
					language.takeIf { it >= 0 }?.let { data.indexOfFrom(0.toByte(), it + 1) } ?: -1

				if (translated < 0)
					null
				else if (compressed)
					inflated(data, translated + 1)?.let { String(it, Charsets.UTF_8) }
				else
					String(data, translated + 1, data.size - translated - 1, Charsets.UTF_8)
			}
		}?.let { keyword to it }
	}

	private fun ByteArray.indexOfFrom(byte: Byte, from: Int): Int =
		(from until size).firstOrNull { this[it] == byte } ?: -1

	/** [data] from [from] on, inflated; null where it is not deflated data, or is more than any text here is. */
	private fun inflated(data: ByteArray, from: Int): ByteArray? {
		if (from > data.size)
			return null

		val inflater = Inflater()

		return try {
			inflater.setInput(data, from, data.size - from)

			val out = ByteArrayOutputStream()
			val buffer = ByteArray(4096)

			while (!inflater.finished() && out.size() <= LONGEST) {
				val count = inflater.inflate(buffer)

				if (count == 0 && (inflater.needsInput() || inflater.needsDictionary()))
					break

				out.write(buffer, 0, count)
			}

			out.toByteArray().takeIf { inflater.finished() }
		} catch (malformed: DataFormatException) {
			null
		} finally {
			inflater.end()
		}
	}
}
