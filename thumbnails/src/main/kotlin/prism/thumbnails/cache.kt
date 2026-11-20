package prism.thumbnails

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import prism.platform.Os
import prism.platform.OwnFolders
import prism.platform.XdgHomes

/*
 * The thumbnails the desktop's programs share, as freedesktop.org's Thumbnail Managing Standard keeps them: small
 * PNG pictures of files, in a folder of the user's cache (`$XDG_CACHE_HOME/thumbnails`), which a file manager makes
 * once and every other program can show. Nautilus, Thunar and Dolphin all write there, and so does prism: a picture
 * one of them made is shown here, and one made here is shown by them.
 *
 * A thumbnail is found by its file's address alone: it is named by the MD5 of the file's `file://` URI, in a folder
 * for each size it is kept at. It says, in the PNG's text, which file it is of and when that file was last changed
 * (and may say how large it was), and it is that file's picture only while those are still so: a file changed since,
 * or another file moved to the same name, has no picture here until one is made again. So what says whether a
 * thumbnail is current lives in the thumbnail itself, and there is no list of them to fall out of step with it.
 *
 * That a file has no thumbnail, because making one was tried and failed, is kept the same way: a PNG of no picture,
 * named as the file's thumbnail would be, in a folder of whoever tried (`fail/prism-1`), saying which file and as of
 * when. Until the file changes, it is not tried again.
 *
 * On a Mac and on Windows, where no programs share such a folder, prism keeps its own in the same form, in its own
 * cache. The standard has one thing more, which is not read: folders named `.sh_thumbnails` kept beside the files
 * themselves, on media handed about, which nothing common writes.
 */

/** The sizes the cache keeps a thumbnail at: the folder each is in, and how many pixels across it is at most. */
@Serializable
enum class ThumbnailSize(
	val folder: String,
	val pixels: Int,
) {
	@SerialName("normal")
	Normal("normal", 128),

	@SerialName("large")
	Large("large", 256),

	@SerialName("x-large")
	XLarge("x-large", 512),

	@SerialName("xx-large")
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

		/** The size a picture drawn [wanted] pixels across is made at: the smallest at least that large, or the largest. */
		fun toMake(wanted: Int): ThumbnailSize =
			entries.firstOrNull { it.pixels >= wanted } ?: entries.last()

		/** The largest there is. */
		val LARGEST: ThumbnailSize = entries.last()
	}
}

/**
 * A file a thumbnail is of, as it is now: where it is, when it was last [modified], and how many [bytes] it holds,
 * where that is known. A thumbnail is this file's while it says the same.
 */
data class Subject(
	val file: Path,
	val modified: Instant,
	val bytes: Long? = null,
) {
	/** The address a thumbnail knows the file by. */
	val uri: String get() = ThumbnailCache.uriOf(file)

	/** The file's extension, in lower case; empty where its name has none. */
	val extension: String
		get() =
			file.fileName
				?.toString()
				.orEmpty()
				.substringAfterLast('.', "")
				.lowercase()

	companion object {
		/** [file] as it is on the disk now; null where it is not there, or is no file. */
		fun of(file: Path): Subject? =
			try {
				Files
					.readAttributes(file, BasicFileAttributes::class.java)
					.takeIf { it.isRegularFile }
					?.let { Subject(file, it.lastModifiedTime().toInstant(), it.size()) }
			} catch (gone: IOException) {
				null
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

	/** Whether this is still [subject]'s picture. */
	fun isOf(subject: Subject): Boolean = isOf(subject.uri, subject.modified, subject.bytes)

	/** This, as a PNG's text says it. */
	val text: Map<String, String>
		get() =
			listOfNotNull(
				uri?.let { URI to it },
				modified?.let { MODIFIED to "$it" },
				bytes?.let { BYTES to "$it" },
			).toMap()

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

		/** What a thumbnail of [subject] says. */
		fun of(subject: Subject): ThumbnailOf =
			ThumbnailOf(subject.uri, subject.modified.epochSecond, subject.bytes)

		/** [text] as a whole number; of a time written with a fraction, the seconds before it. */
		private fun whole(text: String): Long? = text.trim().substringBefore('.').toLongOrNull()
	}
}

/**
 * One file of a cache, by where it is under the cache's folder: `large/c6ee….png`, `fail/prism-1/c6ee….png`. What the
 * cache's files are known by wherever the folder is, so that a cache moved to another folder is the same entries.
 */
@JvmInline
value class Entry(
	val relative: String,
) {
	/** The size it is a thumbnail at; null for a record that a file has none. */
	val size: ThumbnailSize?
		get() = ThumbnailSize.entries.firstOrNull { relative.startsWith("${it.folder}/") }

	override fun toString(): String = relative
}

/** An entry as it lies on the disk: how many [bytes] it takes, and when it was written. */
data class Lying(
	val entry: Entry,
	val bytes: Long,
	val written: Instant,
)

/** The thumbnails kept under [root], which is the `thumbnails` folder of a user's cache, or a folder of that form. */
class ThumbnailCache(
	val root: Path,
) {
	/** Where the thumbnail of [file] at [size] is, if there is one: the one place such a path is made. */
	fun entry(file: Path, size: ThumbnailSize): Path =
		root.resolve(size.folder).resolve(nameOf(uriOf(file)))

	/** Where [entry] is. */
	fun path(entry: Entry): Path = root.resolve(entry.relative)

	/** The entry the file at [path] is, where it is under this cache's folder. */
	fun entryAt(path: Path): Entry? =
		path
			.takeIf { it.startsWith(root) }
			?.let { Entry(root.relativize(it).joinToString("/")) }

	/**
	 * The thumbnail of [file] to draw [wanted] pixels across: one that is still [file]'s picture ([ThumbnailOf.isOf],
	 * by when it was last [modified] and its [bytes]), of the first size there is one at ([ThumbnailSize.toShow]);
	 * null where there is none. Each size is one file looked for by name: the cache is never listed.
	 */
	fun find(
		file: Path,
		modified: Instant,
		bytes: Long?,
		wanted: Int,
		smaller: Boolean,
	): Path? = find(Subject(file, modified, bytes), ThumbnailSize.toShow(wanted, smaller))

	/** The thumbnail that is still [subject]'s picture, of the first of [sizes] there is one at. */
	fun find(subject: Subject, sizes: List<ThumbnailSize>): Path? {
		val uri = subject.uri
		val name = nameOf(uri)

		return sizes
			.asSequence()
			.map { root.resolve(it.folder).resolve(name) }
			// Looked for before it is opened: for most files there is none, and that is one call that fails quietly.
			.filter(Files::isRegularFile)
			.firstOrNull { says(it)?.isOf(uri, subject.modified, subject.bytes) == true }
	}

	/** What the thumbnail at [path] says it is of; null where it is not there, or is no PNG. */
	fun says(path: Path): ThumbnailOf? = PngText.read(path)?.let(ThumbnailOf::of)

	/**
	 * [png], which is a picture of [subject] no more than [size] across, kept as its thumbnail at that size, saying
	 * what it is of: where it now is. Written beside and moved into place whole, so that no reader sees half of one,
	 * and readable by the user alone, as the standard has it (a thumbnail of a private picture is as private). Null
	 * where [png] is no PNG, or it could not be written.
	 */
	fun write(subject: Subject, size: ThumbnailSize, png: ByteArray): Path? =
		Png.saying(png, ThumbnailOf.of(subject).text + (SOFTWARE to MAKER))?.let {
			written(entry(subject.file, size), it)
		}

	/** Where the record that [file] has no thumbnail is, if there is one. */
	fun failure(file: Path): Path = root.resolve(FAILED).resolve(nameOf(uriOf(file)))

	/** Whether making [subject]'s thumbnail was tried, as the file is now, and failed. */
	fun failed(subject: Subject): Boolean {
		val record = failure(subject.file)

		return Files.isRegularFile(record) && says(record)?.isOf(subject) == true
	}

	/** That making [subject]'s thumbnail failed, kept, so that it is not tried again until the file changes. */
	fun fail(subject: Subject): Path? =
		Png.saying(Png.BLANK, ThumbnailOf.of(subject).text + (SOFTWARE to MAKER))?.let {
			written(failure(subject.file), it)
		}

	/** [bytes] as the file [at], made whole or not at all; null where it could not be. */
	private fun written(at: Path, bytes: ByteArray): Path? =
		try {
			folder(at.parent)

			val beside = Files.createTempFile(at.parent, ".prism", ".tmp")

			try {
				Files.write(beside, bytes)
				own(beside, FILE)
				Files.move(beside, at, StandardCopyOption.ATOMIC_MOVE)
			} finally {
				// Moved, it is no longer there to remove.
				Files.deleteIfExists(beside)
			}
			at
		} catch (failed: IOException) {
			null
		}

	/**
	 * The cache's folders made, those that are not there: one for each size, and the one records of failures are kept
	 * under. Whether they could be is not said: one that cannot be made is found out when something is written.
	 */
	fun prepare() {
		try {
			(ThumbnailSize.entries.map { it.folder } + FAIL).forEach { folder(root.resolve(it)) }
		} catch (failed: IOException) {
			// A folder that cannot be made here: nothing is kept until it can.
		}
	}

	/** [folder] made, with every folder above it that is not there: the user's alone, where it is made here. */
	private fun folder(folder: Path) {
		if (Files.isDirectory(folder))
			return

		folder.parent?.let(::folder)
		try {
			Files.createDirectory(folder)
			own(folder, FOLDER)
		} catch (there: java.nio.file.FileAlreadyExistsException) {
			// Made meanwhile, by another writer: theirs to have made as they would.
		}
	}

	private fun own(path: Path, permissions: String) {
		try {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
		} catch (unsupported: UnsupportedOperationException) {
			// A file system without such permissions: the folder is the user's as that system has it.
		}
	}

	/** Every folder there may be entries in: one for each size, and one for each program that keeps what failed. */
	fun folders(): List<Path> =
		ThumbnailSize.entries.map { root.resolve(it.folder) } +
			listed(root.resolve(FAIL)) { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

	/**
	 * Every entry there is, as it lies: the thumbnails of each size, and the records of what failed, whoever wrote
	 * them. Only what a thumbnail is named as is an entry (a PNG, in one of the cache's own folders): whatever else
	 * lies about is not the cache's, and is left alone. Read a folder at a time, as it is asked for.
	 */
	fun entries(): Sequence<Lying> = folders().asSequence().flatMap(::entriesIn)

	/** The entries in [folder], which is one of [folders]. */
	fun entriesIn(folder: Path): Sequence<Lying> =
		listed(folder) { true }.asSequence().mapNotNull(::lying)

	/** The entry at [path] as it lies; null where there is none there (gone, or not named as a thumbnail is). */
	fun lying(path: Path): Lying? {
		val entry = entryAt(path)?.takeIf { named(path) } ?: return null

		return try {
			Files
				.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
				.takeIf { it.isRegularFile }
				?.let { Lying(entry, it.size(), it.lastModifiedTime().toInstant()) }
		} catch (gone: IOException) {
			null
		}
	}

	/** [entry] removed: whether it was there. */
	fun remove(entry: Entry): Boolean =
		try {
			named(path(entry)) && Files.deleteIfExists(path(entry))
		} catch (failed: IOException) {
			false
		}

	override fun equals(other: Any?): Boolean = other is ThumbnailCache && other.root == root

	override fun hashCode(): Int = root.hashCode()

	override fun toString(): String = "ThumbnailCache($root)"

	companion object {
		/** What prism says made a thumbnail, and the keyword it says it under. */
		const val MAKER = "prism"
		const val SOFTWARE = "Software"

		/** Where what could not be made is kept, and whose folder of it prism's is: its name, and its makers' version. */
		const val FAIL = "fail"
		const val FAILED = "$FAIL/$MAKER-1"

		private const val FILE = "rw-------"
		private const val FOLDER = "rwx------"

		/** Whether [path] is named as an entry is: the 32 hexadecimal digits of an MD5, and `.png`. */
		fun named(path: Path): Boolean = NAME.matches(path.fileName?.toString().orEmpty())

		private val NAME = Regex("[0-9a-f]{32}\\.png")

		/** What [folder] holds that [wanted], where it is a folder that can be read; nothing, where not. */
		private fun listed(folder: Path, wanted: (Path) -> Boolean): List<Path> =
			try {
				Files.newDirectoryStream(folder, DirectoryStream.Filter { wanted(it) }).use {
					it.toList()
				}
			} catch (missing: NoSuchFileException) {
				emptyList()
			} catch (failed: IOException) {
				emptyList()
			}

		/** The cache of the user whose folders [xdg] says: `$XDG_CACHE_HOME/thumbnails`, or `~/.cache/thumbnails`. */
		fun of(xdg: XdgHomes = XdgHomes()): ThumbnailCache =
			ThumbnailCache(xdg.cache.resolve("thumbnails"))

		/**
		 * Where thumbnails are kept unless the user says otherwise: the folder the desktop's programs share, on Linux;
		 * a folder of prism's own cache, elsewhere, where nothing is shared.
		 */
		fun defaultRoot(
			os: Os = Os.current,
			xdg: XdgHomes = XdgHomes(),
			own: () -> OwnFolders = { OwnFolders.of(os) },
		): Path =
			when (os) {
				Os.Linux -> of(xdg).root
				Os.MacOS, Os.Windows -> own().cache.path.resolve("thumbnails")
			}

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
