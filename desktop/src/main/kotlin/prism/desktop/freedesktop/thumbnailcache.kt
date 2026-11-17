package prism.desktop.freedesktop

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import prism.desktop.SharedThumbnails

/*
 * The thumbnails the desktop's programs share, as freedesktop.org's Thumbnail Managing Standard keeps them: small
 * PNG pictures of files, in the user's cache (`$XDG_CACHE_HOME/thumbnails`), which a file manager makes once and every
 * other program can show. Nautilus, Thunar and Dolphin all write there, so a video or a document one of them has
 * shown has a picture here.
 *
 * A thumbnail is found by its file's address alone: it is named by the MD5 of the file's `file://` URI, in a folder
 * for each size it is kept at. It says, in the PNG's text, which file it is of and when that file was last changed
 * (and may say how large it was), and it is that file's picture only while those are still so: a file changed since,
 * or another file moved to the same name, has no picture here until one is made again.
 *
 * This reads the cache and writes nothing to it. The standard has two things more, neither read: a `fail` folder,
 * where whoever could not make a file's thumbnail says so, so as not to try again (a reader has no use for it: a file
 * with no thumbnail has none, whyever); and folders named `.sh_thumbnails` kept beside the files themselves, on media
 * handed about, which nothing common writes.
 */

/** The sizes the cache keeps a thumbnail at: the folder each is in, and how many pixels across it is at most. */
enum class ThumbnailSize(
	val folder: String,
	val pixels: Int,
) {
	Normal("normal", 128),
	Large("large", 256),
	XLarge("x-large", 512),
	XxLarge("xx-large", 1024),
	;

	companion object {
		/**
		 * The sizes to look for a picture drawn [wanted] pixels across in, in order: the smallest that is at least that
		 * large first, then the larger; and, where one [smaller] than wanted will do, those after them, the largest
		 * first.
		 */
		fun toShow(wanted: Int, smaller: Boolean): List<ThumbnailSize> {
			val (enough, less) = entries.partition { it.pixels >= wanted }

			return enough + less.reversed().takeIf { smaller }.orEmpty()
		}
	}
}

/**
 * What a thumbnail says of the file it is of, in its PNG's text: the file's [uri], when it was last [modified] (in
 * seconds, as the standard has it), and how many [bytes] it held; each null where the thumbnail does not say.
 */
data class ThumbnailOf(
	val uri: String?,
	val modified: Long?,
	val bytes: Long?,
) {
	/**
	 * Whether this is still the picture of the file at [uri], last [modified] then, of [bytes] bytes where that is
	 * known. The standard has it that the address and the time are always said and must be the file's own (a time that
	 * differs either way: a file moved over another may be the older), and that the size is checked where it is said.
	 */
	fun isOf(uri: String, modified: Instant, bytes: Long?): Boolean =
		this.uri == uri &&
			this.modified == modified.epochSecond &&
			(this.bytes == null || bytes == null || this.bytes == bytes)

	companion object {
		const val URI = "Thumb::URI"
		const val MODIFIED = "Thumb::MTime"
		const val BYTES = "Thumb::Size"

		/** What a PNG whose text is [text] says. A time or a size that is no number is not said. */
		fun of(text: Map<String, String>): ThumbnailOf =
			ThumbnailOf(
				text[URI],
				text[MODIFIED]?.let(::whole),
				text[BYTES]?.let(::whole),
			)

		/** [text] as a whole number; of a time written with a fraction, the seconds before it. */
		private fun whole(text: String): Long? = text.trim().substringBefore('.').toLongOrNull()
	}
}

/** The thumbnails kept under [root], which is the `thumbnails` folder of a user's cache. */
class ThumbnailCache(
	val root: Path,
) : SharedThumbnails {
	/** Where the thumbnail of [file] at [size] is, if there is one: the one place such a path is made. */
	fun entry(file: Path, size: ThumbnailSize): Path =
		root.resolve(size.folder).resolve(nameOf(uriOf(file)))

	/**
	 * The thumbnail of [file] to draw [wanted] pixels across: one that is still [file]'s picture ([ThumbnailOf.isOf],
	 * by when it was last [modified] and its [bytes]), of the first size there is one at ([ThumbnailSize.toShow]);
	 * null where there is none. Each size is one file looked for by name: the cache is never listed.
	 */
	override fun find(
		file: Path,
		modified: Instant,
		bytes: Long?,
		wanted: Int,
		smaller: Boolean,
	): Path? {
		val uri = uriOf(file)
		val name = nameOf(uri)

		return ThumbnailSize
			.toShow(wanted, smaller)
			.asSequence()
			.map { root.resolve(it.folder).resolve(name) }
			// Looked for before it is opened: for most files there is none, and that is one call that fails quietly.
			.filter(Files::isRegularFile)
			.firstOrNull {
				PngText.read(it)?.let(ThumbnailOf::of)?.isOf(uri, modified, bytes) ==
					true
			}
	}

	companion object {
		/** The cache of the user whose folders [xdg] says: `$XDG_CACHE_HOME/thumbnails`, or `~/.cache/thumbnails`. */
		fun of(xdg: Xdg = Xdg()): ThumbnailCache =
			ThumbnailCache(xdg.cacheHome.resolve("thumbnails"))

		/**
		 * The address a thumbnail knows [file] by: `file://` and its path in full, written as GLib writes one
		 * (`g_filename_to_uri`), which is what the programs that fill the cache name their thumbnails by. Letters,
		 * digits and ``-._~!$&'()*+,:=@/`` are themselves; every other byte of the path's UTF-8 is `%` and its two
		 * hexadecimal digits, in capitals. A path with `.`, `..` or doubled slashes in it is first made plain; a link
		 * is not followed, as it is not when its thumbnail is made.
		 */
		fun uriOf(file: Path): String {
			val path = file.toAbsolutePath().normalize().toString()
			val written = StringBuilder("file://")

			for (byte in path.toByteArray(Charsets.UTF_8)) {
				val code = byte.toInt() and 0xFF

				if (code < PLAIN.size && PLAIN[code])
					written.append(code.toChar())
				else
					written.append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
			}

			return written.toString()
		}

		/** The name of the thumbnail of the file at [uri], in whichever size's folder: its MD5, and `.png`. */
		fun nameOf(uri: String): String =
			HexFormat.of().formatHex(
				MessageDigest.getInstance("MD5").digest(uri.toByteArray(Charsets.UTF_8)),
			) + ".png"

		private const val HEX = "0123456789ABCDEF"

		/** Which ASCII characters a path's address holds as they are. */
		private val PLAIN: BooleanArray =
			BooleanArray(128) {
				val char = it.toChar()

				char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
					char in "-._~!$&'()*+,:=@/"
			}
	}
}

/**
 * The text a PNG file holds beside its picture: each keyword and what is said under it. Read by going from one of
 * the file's chunks to the next and reading only those that are text (`tEXt`, `zTXt`, `iTXt`), wherever in the file
 * they are: the picture itself is neither read nor decoded.
 */
object PngText {
	private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

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

	private val TEXTS = setOf("tEXt", "zTXt", "iTXt")

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
