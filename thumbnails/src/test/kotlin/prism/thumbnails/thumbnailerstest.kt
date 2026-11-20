package prism.thumbnails

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The programs that make thumbnails (thumbnailers.kt): commands read and filled, which program is for which file,
 * and the commands other desktops' thumbnailers are run by, written as prism declares them and run: for real where
 * the program is installed here (pdftoppm, ImageMagick, unzip), and as a script that takes the same words where it
 * is not.
 */
class ThumbnailersTest {
	@TempDir
	lateinit var work: Path

	private fun declared(text: String): JsonElement = Json.parseToJsonElement(text)

	private fun program(text: String): Thumbnailer.Program =
		Thumbnailer.read(declared(text)).getOrThrow()

	private fun installed(name: String): Boolean =
		Ffmpeg.pathOf(System.getenv()).any { Files.isExecutable(it.resolve(name)) }

	/** A script in the tests' folder that is run as [name] would be: what stands in for a program not installed here. */
	private fun script(name: String, body: String): Path =
		work.resolve(name).also {
			it.writeText("#!/bin/sh\n$body\n")
			Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
		}

	private fun subject(name: String, bytes: ByteArray = "x".toByteArray()): Subject =
		Subject.of(work.resolve(name).also { it.writeBytes(bytes) })!!

	private fun made(thumbnailer: Thumbnailer, subject: Subject, side: Int = 256): Made =
		runBlocking { thumbnailer.run(subject, side, Processes.SYSTEM, ImagePictures) }

	/** What a program is run with, and what the test has it answer. */
	private class Recorded(
		private val answer: (List<String>) -> Ran,
	) : Processes {
		val ran = ArrayList<List<String>>()

		override suspend fun run(words: List<String>, limit: Duration, most: Int): Ran {
			ran += words

			return answer(words)
		}
	}

	@Test
	fun aCommandIsReadIntoItsWordsAndSlotsAndWrittenBackAsItWas() {
		val words = listOf("pdftoppm", "-png", "-singlefile", "-scale-to", "{size}", "{input}")
		val template = (Template.read(words) as Template.Read.Fit).template

		assertEquals(words, template.written)
		assertFalse(template.writesFile)
		assertEquals(
			listOf("pdftoppm", "-png", "-singlefile", "-scale-to", "256", "/home/u/a b.pdf"),
			template.filled {
				when (it) {
					Slot.Size -> "256"
					else -> "/home/u/a b.pdf"
				}
			},
		)

		// A slot may be part of a word, and a word may have several.
		val joined =
			(
				Template.read(
					listOf("convert", "{input}[0]", "-thumbnail", "{size}x{size}", "png:{output}"),
				) as Template.Read.Fit
			).template

		assertTrue(joined.writesFile)
		assertEquals(
			listOf("convert", "in.psd[0]", "-thumbnail", "128x128", "png:out.png"),
			joined.filled {
				when (it) {
					Slot.Input -> "in.psd"
					Slot.Size -> "128"
					Slot.Output -> "out.png"
					else -> "?"
				}
			},
		)
		// Braces that are no slot's are text: a shell's, or JSON's.
		assertEquals(
			listOf("sh", "-c", "f() { cat \"$1\"; }; f {input}"),
			(
				Template.read(
					listOf("sh", "-c", "f() { cat \"$1\"; }; f {input}"),
				) as Template.Read.Fit
			).template.written,
		)
	}

	@Test
	fun aCommandThatCouldNotBeRunIsRefusedAndSaysWhy() {
		fun unfit(vararg words: String) =
			(Template.read(words.toList()) as Template.Read.Unfit).why

		assertEquals("there is no program to run", unfit())
		assertEquals("there is no program to run", unfit(" ", "{input}"))
		assertEquals(
			"the program to run cannot be a placeholder",
			unfit("{input}", "-o", "{output}"),
		)
		assertEquals(
			"the command does not say which file: it needs {input} or {uri}",
			unfit("thumbnailer", "-s", "{size}", "{output}"),
		)
		assertTrue(
			unfit(
				"thumbnailer",
				"{file}",
			).startsWith("{file} is not something a command can be given"),
		)
		// Where in a film is ffmpeg's to say, and no declared command's.
		assertTrue(unfit("thumbnailer", "{input}", "{seek}").startsWith("{seek} is not"))
		assertTrue(
			Template.read(
				listOf("ffmpeg", "-ss", "{seek}", "{input}"),
				own = true,
			) is Template.Read.Fit,
		)
	}

	@Test
	fun aProgramIsReadFromThePreferencesOrSaidToBeWrong() {
		val pdf =
			program(
				"""{"id": "pdf", "name": "PDF pages", "command": ["pdftoppm", "-png", "-singlefile", "-scale-to", "{size}", "{input}"],
				"extensions": ["PDF", ".ai"], "types": ["application/pdf"], "seconds": 5}""",
			)

		assertEquals("PDF pages", pdf.name)
		// Extensions are as names have them, however they were written.
		assertEquals(Handles(setOf("pdf", "ai"), setOf("application/pdf")), pdf.handles)
		assertEquals(5.seconds, pdf.limit)
		// Written, it reads back as itself.
		assertEquals(pdf, Thumbnailer.read(Thumbnailer.written(pdf)).getOrThrow())
		// A name and a time are not needed.
		assertEquals(
			"epub",
			program(
				"""{"id": "epub", "command": ["gnome-epub-thumbnailer", "{input}", "{output}"], "extensions": ["epub"]}""",
			).name,
		)

		fun wrong(text: String) = Thumbnailer.read(declared(text)).exceptionOrNull()!!.message!!

		assertEquals(
			"x: the command does not say which file: it needs {input} or {uri}",
			wrong("""{"id": "x", "command": ["thing"], "extensions": ["x"]}"""),
		)
		assertEquals(
			"x: it names no extensions and no types to be for",
			wrong("""{"id": "x", "command": ["thing", "{input}"]}"""),
		)
		assertEquals(
			"ffmpeg: that id is ffmpeg's own",
			wrong(
				"""{"id": "ffmpeg", "command": ["ffmpeg", "-i", "{input}"], "extensions": ["mkv"]}""",
			),
		)
		assertTrue(
			wrong(
				"""{"name": "no id", "command": ["thing", "{input}"]}""",
			).startsWith("a thumbnailer: "),
		)
		assertTrue(wrong(""""just text"""").startsWith("a thumbnailer: "))
	}

	@Test
	fun whichProgramsAreTriedForAFileAndInWhatOrder() {
		val ffmpeg =
			Ffmpeg(Path.of("/usr/bin/ffmpeg"), setOf("matroska", "mov", "png_pipe", "mp3"))

		val thumbnailers =
			Thumbnailers.of(
				listOf(
					declared(
						"""{"id": "pdf", "command": ["pdftoppm", "{input}"], "extensions": ["pdf"], "types": ["application/pdf"]}""",
					),
					declared(
						"""{"id": "totem", "command": ["totem-video-thumbnailer", "{input}", "{output}"], "types": ["video/*"]}""",
					),
					declared("""{"id": "broken", "command": ["nothing"], "extensions": ["x"]}"""),
					declared(
						"""{"id": "pdf", "command": ["evince-thumbnailer", "{input}", "{output}"], "extensions": ["pdf"]}""",
					),
				),
				off = emptySet(),
				ffmpeg,
			)

		var asked = 0

		fun ids(extension: String, type: String?) =
			thumbnailers
				.forFile(extension) {
					asked++
					type
				}.map { it.id }

		// By its name, with no need to ask what type it is where its name says for all that are for it.
		assertEquals(listOf("pdf"), ids("pdf", "application/pdf"))
		// The user's own first, then ffmpeg: by a type's kind, and by the name.
		assertEquals(listOf("totem", "ffmpeg"), ids("mkv", "video/x-matroska"))
		// A file whose name says nothing is known by its type alone, asked once however many programs go by type.
		asked = 0
		assertEquals(listOf("pdf"), ids("", "application/pdf"))
		assertEquals(1, asked)
		assertEquals(listOf("totem", "ffmpeg"), ids("", "video/mp4"))
		assertEquals(emptyList<String>(), ids("txt", "text/plain"))
		assertEquals(emptyList<String>(), ids("txt", null))

		// What was declared wrong, or twice, is said, and the rest are there.
		assertEquals(
			listOf(
				"broken: the command does not say which file: it needs {input} or {uri}",
				"pdf: declared more than once; the first is used",
			),
			thumbnailers.broken,
		)
		assertEquals(listOf("pdf", "totem", "ffmpeg"), thumbnailers.all.map { it.id })

		// Turned off, a program is not tried, ffmpeg included; and is still there to turn on.
		val without = thumbnailers.copy(off = setOf("ffmpeg", "totem"))

		assertEquals(emptyList<Thumbnailer>(), without.forFile("mkv") { "video/x-matroska" })
		assertEquals(listOf("pdf", "totem", "ffmpeg"), without.all.map { it.id })
		assertFalse(Thumbnailers.of(emptyList(), setOf("ffmpeg"), ffmpeg).mayHandle("mkv"))
		assertTrue(Thumbnailers.of(emptyList(), emptySet(), ffmpeg).mayHandle("mkv"))
		// With no ffmpeg installed there is none to try.
		assertEquals(Thumbnailers.NONE.all, Thumbnailers.of(emptyList(), emptySet(), null).all)
	}

	/**
	 * The commands the thumbnailers a desktop ships are run by (each as its `.thumbnailer` file has it, with prism's
	 * slots for the desktop's `%i`, `%u`, `%o` and `%s`): each reads, and is run with the file, the size and somewhere
	 * to write filled in.
	 */
	@Test
	fun theCommandsOfTheThumbnailersDesktopsShipAreDeclaredAndFilled() {
		val shipped =
			mapOf(
				"evince-thumbnailer -s {size} {uri} {output}" to listOf("pdf", "djvu", "ps"),
				"gnome-epub-thumbnailer -s {size} {uri} {output}" to listOf("epub", "mobi"),
				"totem-video-thumbnailer -s {size} {uri} {output}" to listOf("mkv"),
				"ffmpegthumbnailer -i {input} -o {output} -s {size} -f" to listOf("mp4"),
				"gdk-pixbuf-thumbnailer -s {size} {uri} {output}" to listOf("xpm"),
				"gnome-thumbnail-font --size {size} {uri} {output}" to listOf("ttf", "otf"),
				"gsf-office-thumbnailer -i {input} -o {output} -s {size}" to listOf("odt", "docx"),
				"blender-thumbnailer {input} {output}" to listOf("blend"),
				"rsvg-convert -w {size} -h {size} -a -f png {input}" to listOf("svg"),
				"dcraw -e -c {input}" to listOf("cr3"),
			)

		for ((command, extensions) in shipped) {
			val words = command.split(' ')

			val thumbnailer =
				program(
					"""{"id": "${words.first()}", "command": [${words.joinToString { "\"$it\"" }}],
					"extensions": [${extensions.joinToString { "\"$it\"" }}]}""",
				)

			val subject = subject("a file (1).${extensions.first()}")
			val processes = Recorded { Ran.Ended(0, ImagePictures.sample(64, 48), "") }

			val made = runBlocking { thumbnailer.run(subject, 256, processes, ImagePictures) }
			val ran = processes.ran.single()

			assertEquals(words.first(), ran.first(), command)
			assertTrue(subject.file.toString() in ran || subject.uri in ran, command)
			assertFalse(ran.any { '{' in it }, "$command: $ran")
			if ("{size}" in words)
				assertTrue("256" in ran, command)
			// One that prints its picture has it taken from what it printed; one that writes a file wrote none here.
			if (thumbnailer.command.writesFile)
				assertEquals(Made.Failed("it made no picture"), made, command)
			else
				assertEquals(Extent(64, 48), (made as Made.Pictured).picture.extent, command)
		}
	}

	@Test
	fun aProgramThatWritesAFileHasItsPictureReadAndTheFileRemoved() {
		// As gnome-epub-thumbnailer and its kin are run: `-s SIZE INPUT OUTPUT`, a PNG written to the last.
		val picture =
			work.resolve("cover.png").also {
				it.writeBytes(ImagePictures.sample(200, 300))
			}

		val writer =
			script(
				"epub-thumbnailer",
				"""echo "$3" > "${work.resolve("wrote")}"; cp "$picture" "$4"""",
			)

		val thumbnailer =
			program(
				"""{"id": "epub", "command": ["$writer", "-s", "{size}", "{input}", "{output}"], "extensions": ["epub"]}""",
			)

		val book = subject("a book.epub")

		val made = made(thumbnailer, book, 512) as Made.Pictured

		assertEquals(Extent(200, 300), made.picture.extent)
		assertEquals(book.file.toString(), Files.readString(work.resolve("wrote")).trim())
		// Nothing of it is left in the system's temporary folder.
		assertEquals(
			emptyList<Path>(),
			Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { all ->
				all.filter { it.fileName.toString().startsWith("prism-thumbnail") }.toList()
			},
		)
		// Asked for one smaller than it writes, its picture is made to fit.
		assertEquals(
			Extent(85, 128),
			(made(thumbnailer, book, 128) as Made.Pictured).picture.extent,
		)
	}

	@Test
	fun whatAProgramDoesWrongIsAFailureAndAProgramThatIsNotThereIsNotOne() {
		val book = subject("a book.epub")

		fun running(body: String, seconds: Int = 20) =
			program(
				"""{"id": "t", "command": ["${script("t-${body.hashCode()}", body)}", "{input}"],
				"extensions": ["epub"], "seconds": $seconds}""",
			)

		// It ends in error: what it last said is why.
		assertEquals(
			Made.Failed("cannot read book: not a zip"),
			made(
				running("echo starting >&2; echo 'cannot read book: not a zip' >&2; exit 3"),
				book,
			),
		)
		assertEquals(Made.Failed("it ended with 1"), made(running("exit 1"), book))
		// It ends well having made nothing, or something that is no picture.
		assertEquals(Made.Failed("it made no picture"), made(running("exit 0"), book))
		assertEquals(
			Made.Failed("what it made is not a picture that could be read"),
			made(running("echo '<html>not found</html>'"), book),
		)

		// It never ends: stopped, and whatever it started with it.
		val began = System.nanoTime()
		val stuck = made(running("sleep 30 & sleep 30", seconds = 1), book)

		assertEquals(Made.Failed("it took longer than 1s"), stuck)
		assertTrue((System.nanoTime() - began).milliseconds / 1_000_000 < 10.seconds)

		// Not installed: nothing was tried, and nothing is to be remembered of the file.
		assertEquals(
			Made.Not("gnome-epub-thumbnailer-that-is-not-here is not installed"),
			made(
				program(
					"""{"id": "t", "command": ["gnome-epub-thumbnailer-that-is-not-here", "{input}"], "extensions": ["epub"]}""",
				),
				book,
			),
		)
	}

	/** A PDF of one page, [width] by [height] points, with a filled rectangle on it. */
	private fun pdf(width: Int, height: Int): ByteArray {
		val drawn = "0.2 0.4 0.8 rg 20 20 ${width - 40} ${height - 40} re f"

		val objects =
			listOf(
				"<< /Type /Catalog /Pages 2 0 R >>",
				"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
				"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Contents 4 0 R >>",
				"<< /Length ${drawn.length} >>\nstream\n$drawn\nendstream",
			)

		val out = StringBuilder("%PDF-1.4\n")
		val offsets = ArrayList<Int>()

		objects.forEachIndexed { index, body ->
			offsets += out.length
			out.append("${index + 1} 0 obj\n$body\nendobj\n")
		}

		val table = out.length

		out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
		offsets.forEach { out.append("%010d 00000 n \n".format(it)) }
		out.append(
			"trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$table\n%%EOF\n",
		)

		return out.toString().toByteArray(Charsets.ISO_8859_1)
	}

	@Test
	fun aPdfsFirstPageIsMadeByPdftoppmWhichPrintsIt() {
		assumeTrue(installed("pdftoppm"), "no pdftoppm here")

		val thumbnailer =
			program(
				"""{"id": "pdf", "name": "PDF pages", "command": ["pdftoppm", "-png", "-singlefile", "-scale-to", "{size}", "{input}"],
				"extensions": ["pdf"], "types": ["application/pdf"]}""",
			)

		// A page taller than wide, in a file with a space in its name: scaled to fit the size asked.
		val made =
			made(
				thumbnailer,
				subject("a paper (final).pdf", pdf(300, 600)),
				256,
			) as Made.Pictured

		assertEquals(Extent(128, 256), made.picture.extent)
		assertEquals(
			Extent(64, 128),
			(
				made(
					thumbnailer,
					subject("b.pdf", pdf(300, 600)),
					128,
				) as Made.Pictured
			).picture.extent,
		)
		// What is no PDF fails, saying what pdftoppm said.
		assertTrue(
			made(thumbnailer, subject("broken.pdf", "not a pdf".toByteArray())) is Made.Failed,
		)
	}

	@Test
	fun anEbooksCoverIsPrintedAsAJpegByUnzipAndMadeAPng() {
		assumeTrue(installed("unzip"), "no unzip here")

		// An EPUB is a zip, and its cover a picture in it: printed as the JPEG it is, larger than was asked for.
		val book = work.resolve("a book.epub")

		ZipOutputStream(Files.newOutputStream(book)).use { zip ->
			zip.putNextEntry(ZipEntry("mimetype"))
			zip.write("application/epub+zip".toByteArray())
			zip.putNextEntry(ZipEntry("OEBPS/cover.jpg"))
			zip.write(ImagePictures.sample(600, 900, "jpg"))
		}

		val thumbnailer =
			program(
				"""{"id": "epub", "command": ["unzip", "-p", "{input}", "OEBPS/cover.jpg"], "extensions": ["epub"],
				"types": ["application/epub+zip"]}""",
			)

		val made = made(thumbnailer, Subject.of(book)!!, 256) as Made.Pictured

		assertEquals(Extent(170, 256), made.picture.extent)
		assertTrue(Png.signed(made.picture.png))
		// A book with no such cover: unzip says so, and that is a failure.
		assertTrue(made(thumbnailer, subject("empty.epub", "PK".toByteArray())) is Made.Failed)
	}

	@Test
	fun imageMagickWritesAThumbnailOfWhatItReadsToTheFileItIsGiven() {
		assumeTrue(installed("convert"), "no ImageMagick here")

		val thumbnailer =
			program(
				"""{"id": "magick", "command": ["convert", "{input}[0]", "-thumbnail", "{size}x{size}", "png:{output}"],
				"extensions": ["xcf", "tga", "pcx"]}""",
			)

		// A format neither the decoder nor the tests' own reader has: made by ImageMagick itself, from a PNG.
		val source =
			work.resolve("source.png").also {
				it.writeBytes(ImagePictures.sample(400, 200))
			}
		val picture = work.resolve("drawing.pcx")

		assertEquals(0, ProcessBuilder("convert", "$source", "$picture").start().waitFor())

		val made = made(thumbnailer, Subject.of(picture)!!, 128) as Made.Pictured

		assertEquals(Extent(128, 64), made.picture.extent)
	}
}
