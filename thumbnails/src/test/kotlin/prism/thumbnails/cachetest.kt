package prism.thumbnails

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.platform.Os
import prism.platform.OwnFolders
import prism.platform.XdgHomes

/**
 * The thumbnails the desktop's programs share (thumbnailcache.kt): where one is, by the standard's own example and by
 * the addresses GLib itself writes; what a PNG says of the file it is of, however its text is written and wherever in
 * the file; and which thumbnail is found for a file, and when one that is there is no longer its picture.
 */
class ThumbnailCacheTest {
	@TempDir
	lateinit var work: Path

	/** One chunk of a PNG: its length, its type, its data, and the checksum of the last two. */
	private fun chunk(type: String, data: ByteArray): ByteArray {
		val named = type.toByteArray(Charsets.ISO_8859_1) + data
		val checksum = CRC32().apply { update(named) }.value.toInt()

		return ByteBuffer
			.allocate(4 + named.size + 4)
			.putInt(data.size)
			.put(named)
			.putInt(checksum)
			.array()
	}

	private fun deflated(text: ByteArray): ByteArray {
		val deflater =
			Deflater().apply {
				setInput(text)
				finish()
			}
		val out = ByteArrayOutputStream()
		val buffer = ByteArray(256)

		while (!deflater.finished())
			out.write(buffer, 0, deflater.deflate(buffer))

		return out.toByteArray()
	}

	/** What ends a keyword, and says a text is deflated; what says it is not. */
	private val zero = byteArrayOf(0)
	private val one = byteArrayOf(1)

	private fun text(keyword: String, value: String) =
		chunk(
			"tEXt",
			keyword.toByteArray(Charsets.ISO_8859_1) + zero +
				value.toByteArray(Charsets.ISO_8859_1),
		)

	private fun compressed(keyword: String, value: String) =
		chunk(
			"zTXt",
			keyword.toByteArray(Charsets.ISO_8859_1) + zero + zero +
				deflated(value.toByteArray(Charsets.ISO_8859_1)),
		)

	private fun international(keyword: String, value: String, deflate: Boolean) =
		chunk(
			"iTXt",
			keyword.toByteArray(Charsets.ISO_8859_1) + zero +
				(if (deflate) one else zero) + zero +
				"en".toByteArray() + zero +
				"translated".toByteArray() + zero +
				value.toByteArray(Charsets.UTF_8).let { if (deflate) deflated(it) else it },
		)

	/**
	 * A PNG of a small picture with [chunks] in it: before the picture's own data, as most programs write their text,
	 * or [after] it, as ImageMagick does.
	 */
	private fun png(vararg chunks: ByteArray, after: Boolean = false): ByteArray {
		val plain =
			ByteArrayOutputStream()
				.also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", it) }
				.toByteArray()

		// The signature and the header chunk, which is always first and 13 bytes; the end chunk, always last and empty.
		val start = 8 + 4 + 4 + 13 + 4
		val end = plain.size - 12
		val added = chunks.fold(ByteArray(0)) { all, chunk -> all + chunk }

		return if (after)
			plain.copyOfRange(0, end) + added + plain.copyOfRange(end, plain.size)
		else
			plain.copyOfRange(0, start) + added + plain.copyOfRange(start, plain.size)
	}

	private fun cache() = ThumbnailCache(work.resolve("cache/thumbnails"))

	/** A thumbnail of [file] in the cache at [size], saying [says] of it. */
	private fun ThumbnailCache.thumbnail(
		file: Path,
		size: ThumbnailSize,
		vararg says: Pair<String, String>,
	): Path =
		entry(file, size).also {
			it.parent.createDirectories()
			it.writeBytes(
				png(
					*says
						.map { (keyword, value) ->
							text(keyword, value)
						}.toTypedArray(),
				),
			)
		}

	@Test
	fun aThumbnailIsNamedByTheMd5OfItsFilesAddressAsTheStandardsExampleIs() {
		val file = Path.of("/home/jens/photos/me.png")

		assertEquals("file:///home/jens/photos/me.png", ThumbnailCache.uriOf(file))
		assertEquals(
			"c6ee772d9e49320e97ec29a7eb5b1697.png",
			ThumbnailCache.nameOf(ThumbnailCache.uriOf(file)),
		)
		assertEquals(
			Path.of("/home/jens/.cache/thumbnails/normal/c6ee772d9e49320e97ec29a7eb5b1697.png"),
			ThumbnailCache(
				Path.of("/home/jens/.cache/thumbnails"),
			).entry(file, ThumbnailSize.Normal),
		)
		assertEquals(
			listOf("normal" to 128, "large" to 256, "x-large" to 512, "xx-large" to 1024),
			ThumbnailSize.entries.map { it.folder to it.pixels },
		)
	}

	@Test
	fun theCacheIsInTheUsersCacheHome() {
		val home = Path.of("/home/u")

		assertEquals(
			Path.of("/home/u/.cache/thumbnails"),
			ThumbnailCache.of(XdgHomes(emptyMap(), home)).root,
		)
		assertEquals(
			Path.of("/var/cache/u/thumbnails"),
			ThumbnailCache.of(XdgHomes(mapOf("XDG_CACHE_HOME" to "/var/cache/u"), home)).root,
		)
		// Set and blank, or no path from the root, is not set.
		assertEquals(
			Path.of("/home/u/.cache/thumbnails"),
			ThumbnailCache.of(XdgHomes(mapOf("XDG_CACHE_HOME" to ""), home)).root,
		)
	}

	/** The names a thumbnail must still be found for: what GLib's `g_filename_to_uri` made of each, and its MD5. */
	private val awkward =
		listOf(
			Triple(
				"/tmp/a b#c%d?e+f&g'h(i)é.mp4",
				"file:///tmp/a%20b%23c%25d%3Fe+f&g'h(i)%C3%A9.mp4",
				"6cd75f3f8eb203150bbca7705398b08d",
			),
			Triple(
				"/x/!\"$,-.:;<=>@[\\]^_`{|}~*",
				"file:///x/!%22$,-.:%3B%3C=%3E@%5B%5C%5D%5E_%60%7B%7C%7D~*",
				"becdb70be3481823eb68be3c617179fc",
			),
			Triple(
				"/x/日本語 файл.mkv",
				"file:///x/%E6%97%A5%E6%9C%AC%E8%AA%9E%20%D1%84%D0%B0%D0%B9%D0%BB.mkv",
				"568f4ecd1a0513d32e28742317fd567d",
			),
		)

	@Test
	fun anAddressIsWrittenAsGlibWritesIt() {
		for ((path, uri, md5) in awkward) {
			assertEquals(uri, ThumbnailCache.uriOf(Path.of(path)), path)
			assertEquals("$md5.png", ThumbnailCache.nameOf(uri), path)
		}

		// A path that is not plain is made so first: the thumbnail is of the file, however it was come to.
		assertEquals("file:///x/a/c", ThumbnailCache.uriOf(Path.of("/x/a//b/../c/.")))
	}

	@Test
	fun everyCharacterIsWrittenAsTheGlibInstalledHereWritesIt() {
		val arena = Arena.ofConfined()

		val glib =
			listOf("libglib-2.0.so.0", "libglib-2.0.so").firstNotNullOfOrNull {
				runCatching { SymbolLookup.libraryLookup(it, arena) }.getOrNull()
			}

		assumeTrue(glib != null, "no GLib here to ask")

		val toUri =
			Linker.nativeLinker().downcallHandle(
				glib!!.find("g_filename_to_uri").orElseThrow(),
				FunctionDescriptor.of(
					ValueLayout.ADDRESS,
					ValueLayout.ADDRESS,
					ValueLayout.ADDRESS,
					ValueLayout.ADDRESS,
				),
			)

		fun asked(path: String): String =
			(
				toUri.invokeWithArguments(
					arena.allocateFrom(path),
					MemorySegment.NULL,
					MemorySegment.NULL,
				) as MemorySegment
			).reinterpret(Long.MAX_VALUE).getString(0)

		// Every character of ASCII that a name can hold, each in a name of its own, and a few of the rest.
		val names =
			(0x20..0x7E).filter { it != '/'.code }.map { "/t/a${it.toChar()}b.mkv" } +
				awkward.map { it.first } +
				listOf(
					"/t/über größe.pdf",
					"/t/100% (final) [v2] {x}.mov",
					"/t/tab\there",
					"/t/emoji 🎬.mp4",
				)

		for (name in names)
			assertEquals(asked(name), ThumbnailCache.uriOf(Path.of(name)), name)

		arena.close()
	}

	@Test
	fun whatAPngSaysIsReadHoweverItIsWrittenAndWhereverItIs() {
		val says = mapOf("Thumb::URI" to "file:///a", "Thumb::MTime" to "17")
		val file = work.resolve("t.png")

		fun read(bytes: ByteArray) = file.also { it.writeBytes(bytes) }.let(PngText::read)

		// Plain, compressed, and international (plain and compressed); before the picture and after it.
		assertEquals(
			says,
			read(png(text("Thumb::URI", "file:///a"), text("Thumb::MTime", "17"))),
		)
		assertEquals(
			says,
			read(png(text("Thumb::URI", "file:///a"), text("Thumb::MTime", "17"), after = true)),
		)
		assertEquals(
			says,
			read(png(compressed("Thumb::URI", "file:///a"), compressed("Thumb::MTime", "17"))),
		)
		assertEquals(
			says,
			read(
				png(
					international("Thumb::URI", "file:///a", deflate = false),
					international("Thumb::MTime", "17", deflate = true),
				),
			),
		)
		assertEquals(
			mapOf("Title" to "naïve — é"),
			read(png(international("Title", "naïve — é", deflate = true), after = true)),
		)
		// Said twice, the first is what is said.
		assertEquals(
			mapOf("Thumb::MTime" to "1"),
			read(png(text("Thumb::MTime", "1"), text("Thumb::MTime", "2"))),
		)
		// A picture with no text says nothing; what is no PNG, or not there, is not read.
		assertEquals(emptyMap<String, String>(), read(png()))
		assertNull(read("not a png at all".toByteArray()))
		assertNull(PngText.read(work.resolve("gone.png")))

		// Cut short in the middle of its text: what was whole before it.
		val whole = png(text("Thumb::URI", "file:///a"), text("Thumb::MTime", "17"))

		val second = String(whole, Charsets.ISO_8859_1).indexOf("Thumb::MTime")

		assertEquals(mapOf("Thumb::URI" to "file:///a"), read(whole.copyOfRange(0, second + 3)))
		// A chunk that is not as text is (no keyword, or deflated data that is not) is passed over.
		assertEquals(
			mapOf("Thumb::MTime" to "17"),
			read(
				png(
					chunk("tEXt", "no end to the keyword".toByteArray()),
					chunk("zTXt", "Key".toByteArray() + zero + zero + "not deflated".toByteArray()),
					text("Thumb::MTime", "17"),
				),
			),
		)
	}

	@Test
	fun whatAThumbnailSaysOfItsFile() {
		assertEquals(
			ThumbnailOf("file:///a", 17, 2048),
			ThumbnailOf.of(
				mapOf("Thumb::URI" to "file:///a", "Thumb::MTime" to "17", "Thumb::Size" to "2048"),
			),
		)
		// A time with a fraction is its seconds; what is no number is not said (ImageMagick writes a size of `5039BB`).
		assertEquals(
			ThumbnailOf(null, 17, null),
			ThumbnailOf.of(mapOf("Thumb::MTime" to "17.25", "Thumb::Size" to "5039BB")),
		)
		assertEquals(
			ThumbnailOf(null, null, null),
			ThumbnailOf.of(
				mapOf("Thumb::MTime" to "soon"),
			),
		)

		val at = Instant.ofEpochSecond(17, 999_000_000)
		val said = ThumbnailOf("file:///a", 17, 2048)

		assertEquals(true, said.isOf("file:///a", at, 2048))
		// The size is checked where both know it.
		assertEquals(true, said.isOf("file:///a", at, null))
		assertEquals(true, said.copy(bytes = null).isOf("file:///a", at, 5))
		assertEquals(false, said.isOf("file:///a", at, 2049))
		// Another file's; a file changed since, or older than it was (another moved over it).
		assertEquals(false, said.isOf("file:///b", at, 2048))
		assertEquals(false, said.isOf("file:///a", at.plusSeconds(1), 2048))
		assertEquals(false, said.isOf("file:///a", at.minusSeconds(1), 2048))
		// One that does not say whose it is, or when, is nobody's.
		assertEquals(false, said.copy(uri = null).isOf("file:///a", at, 2048))
		assertEquals(false, said.copy(modified = null).isOf("file:///a", at, 2048))
	}

	@Test
	fun theSizesLookedInAreTheSmallestLargeEnoughAndThenTheLargestThereIs() {
		val (normal, large, xLarge, xxLarge) = ThumbnailSize.entries

		assertEquals(
			listOf(normal, large, xLarge, xxLarge),
			ThumbnailSize.toShow(100, smaller = true),
		)
		assertEquals(
			listOf(large, xLarge, xxLarge, normal),
			ThumbnailSize.toShow(200, smaller = true),
		)
		assertEquals(
			listOf(xxLarge, xLarge, large, normal),
			ThumbnailSize.toShow(600, smaller = true),
		)
		assertEquals(
			listOf(xxLarge, xLarge, large, normal),
			ThumbnailSize.toShow(2048, smaller = true),
		)
		// Where one smaller will not do: those large enough alone.
		assertEquals(listOf(xLarge, xxLarge), ThumbnailSize.toShow(512, smaller = false))
		assertEquals(emptyList<ThumbnailSize>(), ThumbnailSize.toShow(2048, smaller = false))
	}

	@Test
	fun aFilesThumbnailIsFoundWhileItIsStillItsPicture() {
		val cache = cache()
		val film =
			work.resolve("films/a b#c (1).mkv").also {
				it.parent.createDirectories()
				it.writeText("not really a film")
			}
		val modified = Files.getLastModifiedTime(film).toInstant()
		val uri = ThumbnailCache.uriOf(film)
		val says = arrayOf("Thumb::URI" to uri, "Thumb::MTime" to "${modified.epochSecond}")

		// None made: none found, and the cache's folders need not even be there.
		assertNull(cache.find(film, modified, null, 256, smaller = true))

		val normal = cache.thumbnail(film, ThumbnailSize.Normal, *says)

		// Only a smaller one than is wanted: found where a smaller will do.
		assertEquals(normal, cache.find(film, modified, null, 256, smaller = true))
		assertNull(cache.find(film, modified, null, 256, smaller = false))

		val xLarge = cache.thumbnail(film, ThumbnailSize.XLarge, *says)

		// The smallest at least as large as wanted; the largest there is, where none is.
		assertEquals(xLarge, cache.find(film, modified, null, 256, smaller = true))
		assertEquals(normal, cache.find(film, modified, null, 128, smaller = true))
		assertEquals(xLarge, cache.find(film, modified, null, 1024, smaller = true))

		// The file changed since: neither is its picture any more.
		assertNull(cache.find(film, modified.plusSeconds(5), null, 256, smaller = true))

		// One that is stale is passed over for one that is not.
		cache.thumbnail(film, ThumbnailSize.Large, "Thumb::URI" to uri, "Thumb::MTime" to "1")
		assertEquals(xLarge, cache.find(film, modified, null, 256, smaller = true))

		// A thumbnail that says it is another file's (two addresses cannot share an MD5, but a file can be wrong),
		// that does not say when, or that says another size, is not this file's.
		val other = work.resolve("films/other.mkv").also { it.writeText("x") }

		cache.thumbnail(other, ThumbnailSize.Large, *says)
		assertNull(cache.find(other, modified, null, 256, smaller = true))
		cache.thumbnail(other, ThumbnailSize.Large, "Thumb::URI" to ThumbnailCache.uriOf(other))
		assertNull(cache.find(other, modified, null, 256, smaller = true))
		cache.thumbnail(
			other,
			ThumbnailSize.Large,
			"Thumb::URI" to ThumbnailCache.uriOf(other),
			"Thumb::MTime" to "${modified.epochSecond}",
			"Thumb::Size" to "999",
		)
		assertNull(cache.find(other, modified, 1, 256, smaller = true))
		assertEquals(
			cache.entry(other, ThumbnailSize.Large),
			cache.find(other, modified, 999, 256, smaller = true),
		)
	}

	@Test
	fun aThumbnailImageMagickMadeIsReadAsItSaysIt() {
		val convert =
			listOf(
				"/usr/bin/convert",
				"/usr/local/bin/convert",
			).map(Path::of).firstOrNull(Files::isExecutable)

		assumeTrue(convert != null, "no ImageMagick here to make one")

		val picture = work.resolve("picture.png")
		val thumbnail = work.resolve("thumbnail.png")

		fun run(vararg words: String) =
			assertEquals(
				0,
				ProcessBuilder(convert.toString(), *words)
					.directory(work.toFile())
					.inheritIO()
					.start()
					.waitFor(),
			)

		run("-size", "640x480", "gradient:red-blue", picture.toString())
		run(picture.toString(), "-thumbnail", "128x128", thumbnail.toString())

		// It writes its text after the picture, and says when the file was changed as the standard has it.
		val says = ThumbnailOf.of(PngText.read(thumbnail)!!)

		assertEquals(Files.getLastModifiedTime(picture).toInstant().epochSecond, says.modified)
		assertEquals("640", PngText.read(thumbnail)!!["Thumb::Image::Width"])
	}

	/** A real picture's PNG: [side] across, so that there is something to keep. */
	private fun pictured(side: Int = 8): ByteArray =
		ByteArrayOutputStream()
			.also {
				ImageIO.write(
					BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB),
					"png",
					it,
				)
			}.toByteArray()

	@Test
	fun aThumbnailWrittenIsFoundAndSaysWhatItIsOfAsTheStandardHasIt() {
		val cache = cache()
		val film = work.resolve("a film.mkv").also { it.writeText("not really a film") }
		val subject = Subject.of(film)!!
		val png = pictured()

		val written = cache.write(subject, ThumbnailSize.Large, png)!!

		assertEquals(cache.entry(film, ThumbnailSize.Large), written)
		assertEquals(written, cache.find(subject, listOf(ThumbnailSize.Large)))
		assertEquals(
			mapOf(
				"Thumb::URI" to ThumbnailCache.uriOf(film),
				"Thumb::MTime" to "${subject.modified.epochSecond}",
				"Thumb::Size" to "${Files.size(film)}",
				"Software" to "prism",
			),
			PngText.read(written),
		)
		// The picture is as it was given: what was added is text, and any decoder still reads it.
		assertEquals(Extent(8, 8), Png.extent(Files.readAllBytes(written)))
		assertEquals(8, ImageIO.read(written.toFile()).width)
		// The user's alone, the folders made for it too; and nothing left beside it.
		assertEquals(
			"rw-------",
			PosixFilePermissions.toString(Files.getPosixFilePermissions(written)),
		)
		assertEquals(
			"rwx------",
			PosixFilePermissions.toString(Files.getPosixFilePermissions(written.parent)),
		)
		assertEquals(listOf(written), Files.list(written.parent).use { it.toList() })

		// The file changed: no longer its picture. Written again, it is.
		val later = subject.copy(modified = subject.modified.plusSeconds(60))

		assertNull(cache.find(later, ThumbnailSize.entries))
		assertNotNull(cache.write(later, ThumbnailSize.Large, png))
		assertEquals(written, cache.find(later, ThumbnailSize.entries))

		// What is no PNG is not kept.
		assertNull(cache.write(subject, ThumbnailSize.Normal, "<html>".toByteArray()))
		assertNull(cache.find(subject, listOf(ThumbnailSize.Normal)))
	}

	@Test
	fun whatAPngAlreadySaidUnderTheSameWordsIsSaidOnce() {
		val before =
			png(
				text("Thumb::URI", "file:///stale"),
				text("Title", "kept"),
				text("Thumb::MTime", "1"),
			)

		val file = work.resolve("t.png")

		file.writeBytes(
			Png.saying(
				before,
				mapOf(
					"Thumb::URI" to "file:///a",
					"Thumb::MTime" to "17",
				),
			)!!,
		)
		assertEquals(
			mapOf("Thumb::URI" to "file:///a", "Thumb::MTime" to "17", "Title" to "kept"),
			PngText.read(file),
		)
		// Said once: the stale text is gone, not merely read second.
		assertEquals(
			1,
			Regex(
				"Thumb::URI",
			).findAll(String(Files.readAllBytes(file), Charsets.ISO_8859_1)).count(),
		)
		// What is cut short, or is no PNG, says nothing.
		assertNull(Png.saying(before.copyOf(before.size - 6), mapOf("a" to "b")))
		assertNull(Png.saying("GIF89a".toByteArray(), mapOf("a" to "b")))
		// The picture of no picture is a PNG any reader takes.
		assertEquals(Extent(1, 1), Png.extent(Png.BLANK))
		assertEquals(1, ImageIO.read(Png.BLANK.inputStream()).width)
	}

	@Test
	fun thatAThumbnailCouldNotBeMadeIsKeptUntilTheFileChanges() {
		val cache = cache()
		val film = work.resolve("broken.mkv").also { it.writeText("not a film") }
		val subject = Subject.of(film)!!

		assertFalse(cache.failed(subject))

		val record = cache.fail(subject)!!

		assertEquals(
			cache.root.resolve("fail/prism-1").resolve(ThumbnailCache.nameOf(subject.uri)),
			record,
		)
		assertTrue(cache.failed(subject))
		// Changed since, it may be tried again; another file is not this one.
		assertFalse(cache.failed(subject.copy(modified = subject.modified.plusSeconds(1))))
		assertFalse(cache.failed(subject.copy(file = work.resolve("other.mkv"))))
		// It is no thumbnail: nothing is found for the file.
		assertNull(cache.find(subject, ThumbnailSize.entries))
	}

	@Test
	fun theCachesEntriesAreItsThumbnailsWhoeverMadeThemAndNothingElse() {
		val cache = cache()
		val film = work.resolve("film.mkv").also { it.writeText("film") }
		val subject = Subject.of(film)!!

		val large = cache.write(subject, ThumbnailSize.Large, pictured())!!
		val failed = cache.fail(subject)!!
		// Another program's: a thumbnail, and its record of one it could not make.
		val theirs =
			cache.root.resolve("normal/00000000000000000000000000000001.png").also {
				it.parent.createDirectories()
				it.writeBytes(pictured())
			}

		val theirFailure =
			cache.root
				.resolve(
					"fail/gnome-thumbnail-factory/00000000000000000000000000000002.png",
				).also {
					it.parent.createDirectories()
					it.writeBytes(Png.BLANK)
				}

		// What is not a thumbnail by its name, or is not in a folder thumbnails are in, is not the cache's.
		cache.root.resolve("large/notes.txt").writeText("mine")
		cache.root.resolve("large/.prism123.tmp").writeText("half written")
		cache.root.resolve("other").createDirectories()
		cache.root.resolve("other/00000000000000000000000000000003.png").writeBytes(pictured())

		assertEquals(
			setOf(
				Entry("large/${large.fileName}"),
				Entry("fail/prism-1/${failed.fileName}"),
				Entry("normal/${theirs.fileName}"),
				Entry("fail/gnome-thumbnail-factory/${theirFailure.fileName}"),
			),
			cache.entries().map { it.entry }.toSet(),
		)
		assertEquals(Files.size(large), cache.lying(large)!!.bytes)
		assertEquals(ThumbnailSize.Large, Entry("large/x.png").size)
		assertNull(Entry("fail/prism-1/x.png").size)
		assertEquals(large, cache.path(cache.entryAt(large)!!))
		assertNull(cache.entryAt(work.resolve("elsewhere.png")))

		// Removed, an entry is gone; what is not one is never removed through the cache.
		assertTrue(cache.remove(Entry("normal/${theirs.fileName}")))
		assertFalse(Files.exists(theirs))
		assertFalse(cache.remove(Entry("large/notes.txt")))
		assertTrue(Files.exists(cache.root.resolve("large/notes.txt")))
	}

	@Test
	fun whereThumbnailsAreKeptByDefaultOnEachPlatform() {
		val home = Path.of("/home/u")
		val own = { os: Os -> OwnFolders.of(os, emptyMap(), home, null) }

		// On Linux, the folder the desktop's programs share; elsewhere, prism's own cache.
		assertEquals(
			Path.of("/home/u/.cache/thumbnails"),
			ThumbnailCache.defaultRoot(Os.Linux, XdgHomes(emptyMap(), home)) { own(Os.Linux) },
		)
		assertEquals(
			Path.of("/home/u/Library/Caches/prism/thumbnails"),
			ThumbnailCache.defaultRoot(Os.MacOS, XdgHomes(emptyMap(), home)) { own(Os.MacOS) },
		)
		assertTrue(
			ThumbnailCache
				.defaultRoot(Os.Windows, XdgHomes(emptyMap(), home)) { own(Os.Windows) }
				.endsWith(Path.of("prism/cache/thumbnails")),
		)
	}
}
