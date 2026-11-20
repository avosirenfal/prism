package prism.thumbnails

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * ffmpeg as a maker of thumbnails (ffmpeg.kt): what it says it reads, read as two versions of it print it; what it
 * says of a file; and, with the ffmpeg installed here, pictures made of films, sound files and pictures that it made
 * itself, and of files that are not what their names say.
 */
class FfmpegTest {
	@TempDir
	lateinit var work: Path

	/** What ffmpeg 6 prints for `-formats`: two flags. */
	private val six =
		"""
		File formats:
		 D. = Demuxing supported
		 .E = Muxing supported
		 --
		 D  3dostr          3DO STR
		  E 3g2             3GP2 (3GPP2 file format)
		 DE avi             AVI (Audio Video Interleaved)
		 D  jpeg_pipe       piped jpeg sequence
		  E matroska        Matroska
		 D  matroska,webm   Matroska / WebM
		 D  mov,mp4,m4a,3gp,3g2,mj2 QuickTime / MOV
		 DE mp3             MP3 (MPEG audio layer 3)
		  E webm            WebM
		""".trimIndent()

	/** What ffmpeg 7 prints: a third flag, for devices. */
	private val seven =
		"""
		Formats:
		 D.. = Demuxing supported
		 .E. = Muxing supported
		 ..d = Is a device
		 ---
		 D   3dostr          3DO STR
		  E  3g2             3GP2 (3GPP2 file format)
		 DE  avi             AVI (Audio Video Interleaved)
		 D d alsa            ALSA audio input
		 D   jpeg_pipe       piped jpeg sequence
		  E  matroska        Matroska
		 D   matroska,webm   Matroska / WebM
		 D   mov,mp4,m4a,3gp,3g2,mj2 QuickTime / MOV
		 DE  mp3             MP3 (MPEG audio layer 3)
		""".trimIndent()

	@Test
	fun theFormatsFfmpegReadsAreReadFromWhatItListsInEitherVersion() {
		val read =
			setOf(
				"3dostr",
				"avi",
				"jpeg_pipe",
				"matroska",
				"webm",
				"mov",
				"mp4",
				"m4a",
				"3gp",
				"3g2",
				"mj2",
				"mp3",
			)

		assertEquals(read, Ffmpeg.demuxersOf(six))
		assertEquals(read + "alsa", Ffmpeg.demuxersOf(seven))
		// What it only writes (the muxer named 3g2 is not what reads a .3g2) does not make it a reader of it.
		assertEquals(emptySet<String>(), Ffmpeg.demuxersOf(" --\n  E 3g2             3GP2\n"))
		assertEquals(emptySet<String>(), Ffmpeg.demuxersOf("ffmpeg: command not found"))
	}

	@Test
	fun whatItIsForIsWhatThatFfmpegReads() {
		val ffmpeg = Ffmpeg(Path.of("/usr/bin/ffmpeg"), Ffmpeg.demuxersOf(six))

		assertEquals(Medium.Film, ffmpeg.mediumOf("mkv"))
		assertEquals(Medium.Film, ffmpeg.mediumOf("mp4"))
		assertEquals(Medium.Sound, ffmpeg.mediumOf("m4a"))
		assertEquals(Medium.Sound, ffmpeg.mediumOf("mp3"))
		assertEquals(Medium.Picture, ffmpeg.mediumOf("heic"))
		assertEquals(Medium.Picture, ffmpeg.mediumOf("jpg"))
		// This one was built without PNG, SVG or Ogg: their files are not its.
		assertNull(ffmpeg.mediumOf("png"))
		assertNull(ffmpeg.mediumOf("svg"))
		assertNull(ffmpeg.mediumOf("ogv"))
		assertNull(ffmpeg.mediumOf("txt"))
		assertTrue(ffmpeg.handles.byName("webm"))
		assertTrue(ffmpeg.handles.byType("video/x-matroska"))
		assertFalse(ffmpeg.handles.byType("image/png"))
		assertFalse(ffmpeg.handles.byName("pdf"))

		// One that reads nothing prism knows is for nothing.
		assertEquals(Handles(), Ffmpeg(Path.of("/usr/bin/ffmpeg"), setOf("alsa")).handles)

		// No extension is two kinds of thing at once.
		val twice =
			Ffmpeg.FORMATS
				.flatMap { format -> format.extensions.map { it to format.medium } }
				.groupBy({ it.first }, { it.second })
				.filterValues { it.toSet().size > 1 }

		assertEquals(emptyMap<String, List<Medium>>(), twice)
	}

	@Test
	fun itsCommandsAreShownAsTheyAreRun() {
		val ffmpeg = Ffmpeg(Path.of("/opt/ffmpeg/bin/ffmpeg"), setOf("mov"))
		val film = ffmpeg.commands.getValue(Medium.Film).written

		assertEquals("/opt/ffmpeg/bin/ffmpeg", film.first())
		assertEquals(listOf("-ss", "{seek}", "-i", "{input}"), film.subList(4, 8))
		assertTrue(film.any { "{size}" in it })
		assertEquals(listOf("-f", "image2pipe", "-c:v", "png", "{output}"), film.takeLast(5))
		assertEquals(Medium.entries.toSet(), ffmpeg.commands.keys)
	}

	@Test
	fun whatFfmpegSaysOfAFileIsRead() {
		val film =
			Ffmpeg.Probed.of(
				"""
				Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'a.mp4':
				  Duration: 01:02:06.50, start: 0.000000, bitrate: 759 kb/s
				  Stream #0:0[0x1](und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(progressive), 640x360, 25 fps (default)
				  Stream #0:1[0x2](und): Audio: aac (LC), 48000 Hz, stereo
				At least one output file must be specified
				""".trimIndent(),
			)

		assertEquals(
			Ffmpeg.Probed(1.minutes * 62 + 6.5.seconds, film = true, cover = false),
			film,
		)

		val song =
			Ffmpeg.Probed.of(
				"""
				Input #0, mp3, from 'c.mp3':
				  Duration: 00:03:25.04, start: 0.025056, bitrate: 219 kb/s
				  Stream #0:0: Audio: mp3, 44100 Hz, mono, fltp, 64 kb/s
				  Stream #0:1: Video: mjpeg (Baseline), yuvj420p(pc), 300x300, 90k tbr, 90k tbn (attached pic)
				""".trimIndent(),
			)

		assertEquals(false to true, song.film to song.cover)

		// A picture has no length to speak of; a stream of no known length says so.
		assertEquals(Duration.ZERO, Ffmpeg.seekOf(null))
		assertNull(
			Ffmpeg.Probed
				.of(
					"  Duration: N/A, start: 0.000000, bitrate: N/A\n  Stream #0:0: Video: h264",
				).duration,
		)

		val broken =
			Ffmpeg.Probed.of(
				"[matroska,webm @ 0x55] EBML header parsing failed\nbroken.mkv: Invalid data found when processing input\n",
			)

		assertEquals(false to false, broken.film to broken.cover)
		assertEquals("broken.mkv: Invalid data found when processing input", broken.problem)
	}

	@Test
	fun aFramIsTakenATenthOfTheWayInAndNoFurtherThanFiveMinutes() {
		assertEquals(12.seconds, Ffmpeg.seekOf(2.minutes))
		assertEquals(Ffmpeg.FURTHEST, Ffmpeg.seekOf(180.minutes))
		// Too short to be worth seeking in: from its beginning.
		assertEquals(Duration.ZERO, Ffmpeg.seekOf(6.seconds))
		assertEquals(Duration.ZERO, Ffmpeg.seekOf(40.milliseconds))
	}

	// ---- with the ffmpeg installed here ----------------------------------------------------------------------------

	private val found: Ffmpeg? by lazy { runBlocking { Ffmpeg.find() } }

	private fun ffmpeg(): Ffmpeg = found.also { assumeTrue(it != null, "no ffmpeg here") }!!

	/** [name] made in the tests' folder by ffmpeg itself, from [words]. */
	private fun generated(name: String, vararg words: String): Subject {
		val file = work.resolve(name)

		val process =
			ProcessBuilder(listOf("${ffmpeg().program}", "-v", "error", "-y") + words + "$file")
				.redirectErrorStream(true)
				.start()
		val said = process.inputStream.readAllBytes().toString(Charsets.UTF_8)

		assertEquals(0, process.waitFor(), said)

		return Subject.of(file)!!
	}

	private fun film(name: String, size: String, seconds: Int, vararg more: String) =
		generated(
			name,
			"-f",
			"lavfi",
			"-i",
			"testsrc2=size=$size:rate=25:duration=$seconds",
			*more,
		)

	private fun picture(subject: Subject, side: Int = 256): Made =
		runBlocking { ffmpeg().picture(subject, side, Processes.SYSTEM, 30.seconds) }

	private fun extent(subject: Subject, side: Int = 256): Extent =
		(picture(subject, side) as Made.Pictured).picture.extent

	@Test
	fun theFfmpegOnThePathIsFoundAndSaysWhatItReads() {
		val ffmpeg = ffmpeg()

		assertTrue(Files.isExecutable(ffmpeg.program))
		assertTrue(
			"mov" in ffmpeg.demuxers && "matroska" in ffmpeg.demuxers,
			"${ffmpeg.demuxers}",
		)
		assertEquals(Medium.Film, ffmpeg.mediumOf("mp4"))
		// Where there is none on the path, there is none.
		assertNull(runBlocking { Ffmpeg.find(path = listOf(work)) })
		assertNull(runBlocking { Ffmpeg.find(path = emptyList()) })

		// One that is there and does not answer as ffmpeg does is not one.
		val impostor =
			work.resolve("bin/ffmpeg").also {
				Files.createDirectories(it.parent)
				it.writeBytes("#!/bin/sh\necho 'I am not ffmpeg'\n".toByteArray())
				it.toFile().setExecutable(true)
			}

		assertNull(runBlocking { Ffmpeg.find(path = listOf(impostor.parent)) })
	}

	@Test
	fun aFilmsPictureIsAFrameOfItScaledToFitInItsOwnShape() {
		val wide = film("wide.mp4", "640x360", 6, "-c:v", "libx264", "-pix_fmt", "yuv420p")

		assertEquals(Extent(256, 144), extent(wide))
		assertEquals(Extent(128, 72), extent(wide, 128))
		// Never scaled up: a film smaller than was asked for is as large as it is.
		assertEquals(Extent(640, 360), extent(wide, 1024))
		// Taller than wide, in a file whose name has spaces and brackets.
		assertEquals(
			Extent(144, 256),
			extent(
				film("tall [2024] (1).mkv", "360x640", 3, "-c:v", "libx264", "-pix_fmt", "yuv420p"),
			),
		)
		// A film of a few frames, too short to go a tenth of the way into.
		assertEquals(
			Extent(256, 192),
			extent(film("short.webm", "320x240", 1, "-frames:v", "3", "-c:v", "libvpx")),
		)
	}

	@Test
	fun aFilmWhosePixelsAreNotSquareIsPicturedInTheShapeItIsShown() {
		// 720 by 576 pixels shown 16:9, as a DVD's are: its picture is 16:9, not 5:4.
		val dvd =
			film(
				"dvd.mkv",
				"720x576",
				2,
				"-vf",
				"setsar=64/45",
				"-c:v",
				"libx264",
				"-pix_fmt",
				"yuv420p",
			)

		assertEquals(Extent(256, 144), extent(dvd))
	}

	@Test
	fun aFrameFromFurtherInIsNotTheFirst() {
		// Black for its first two seconds, then a picture: what is taken a tenth of the way into twenty seconds is not black.
		val opening =
			generated(
				"opening.mp4",
				"-f",
				"lavfi",
				"-i",
				"color=black:size=320x240:rate=25:duration=2",
				"-f",
				"lavfi",
				"-i",
				"testsrc2=size=320x240:rate=25:duration=18",
				"-filter_complex",
				"[0:v][1:v]concat=n=2:v=1[v]",
				"-map",
				"[v]",
				"-c:v",
				"libx264",
				"-pix_fmt",
				"yuv420p",
				"-g",
				"25",
			)

		val png = (picture(opening) as Made.Pictured).picture.png
		val image = javax.imageio.ImageIO.read(png.inputStream())

		val lit =
			(0 until image.width step 8).sumOf { x ->
				(0 until image.height step 8).count { y -> (image.getRGB(x, y) and 0xFFFFFF) != 0 }
			}

		assertTrue(
			lit > 100,
			"the frame is black: $lit lit of ${image.width * image.height / 64}",
		)
	}

	@Test
	fun aSoundFilesPictureIsTheCoverItCarriesAndOneWithNoneHasNone() {
		val withCover =
			generated(
				"song.mp3",
				"-f",
				"lavfi",
				"-i",
				"sine=duration=2",
				"-f",
				"lavfi",
				"-i",
				"color=red:size=600x600:duration=1",
				"-map",
				"0:a",
				"-map",
				"1:v",
				"-frames:v",
				"1",
				"-c:a",
				"libmp3lame",
				"-c:v",
				"mjpeg",
				"-disposition:v",
				"attached_pic",
			)

		assertEquals(Extent(256, 256), extent(withCover))

		val bare = generated("bare.mp3", "-f", "lavfi", "-i", "sine=duration=2")

		assertEquals(Made.Failed("nothing in it to make a picture of"), picture(bare))
	}

	@Test
	fun picturesAreScaledDownAndNeverUp() {
		val large =
			generated(
				"large.jpg",
				"-f",
				"lavfi",
				"-i",
				"testsrc2=size=3000x2000:duration=1",
				"-frames:v",
				"1",
			)

		val small =
			generated(
				"small.png",
				"-f",
				"lavfi",
				"-i",
				"testsrc2=size=100x60:duration=1",
				"-frames:v",
				"1",
			)

		assertEquals(Extent(256, 171), extent(large))
		assertEquals(Extent(1024, 683), extent(large, 1024))
		assertEquals(Extent(100, 60), extent(small))
	}

	@Test
	fun aFileThatIsNotWhatItsNameSaysFailsAndSaysWhy() {
		val noise =
			work.resolve("noise.mkv").also {
				it.writeBytes(
					ByteArray(64 * 1024) {
						(
							it *
								31
						).toByte()
					},
				)
			}

		val words =
			work.resolve("words.jpg").also {
				it.writeBytes("these are only words".toByteArray())
			}
		val cut = film("whole.mp4", "320x240", 2, "-c:v", "libx264", "-pix_fmt", "yuv420p").file

		// An MP4 says where everything in it is at its end: cut short, it cannot be read.
		val truncated =
			work.resolve("cut.mp4").also {
				it.writeBytes(Files.readAllBytes(cut).copyOf(4096))
			}

		for (file in listOf(noise, words, truncated)) {
			val made = picture(Subject.of(file)!!)

			assertTrue(made is Made.Failed, "$file: $made")
			assertTrue((made as Made.Failed).why.isNotBlank())
		}
		// A file that is gone by the time it is run on.
		assertTrue(
			picture(Subject(work.resolve("gone.mp4"), java.time.Instant.EPOCH)) is Made.Failed,
		)
	}

	@Test
	fun aProgramNoLongerWaitedForIsKilled() =
		runBlocking {
			val marker = work.resolve("still-running")
			val began = System.nanoTime()

			val running =
				async(Dispatchers.Default) {
					Processes.SYSTEM.run(
						listOf("sh", "-c", "sleep 3; touch '$marker'"),
						30.seconds,
						1024,
					)
				}

			delay(300)
			running.cancelAndJoin()
			assertTrue((System.nanoTime() - began) / 1_000_000 < 2500, "it was waited for")
			// Killed, it never gets as far as saying it went on.
			delay(3200)
			assertFalse(Files.exists(marker))

			// What is printed past what it may print ends it too; and what it prints is what is read.
			assertTrue(
				Processes.SYSTEM.run(
					listOf("sh", "-c", "head -c 100000 /dev/zero"),
					10.seconds,
					1000,
				) is Ran.Killed,
			)

			val printed =
				Processes.SYSTEM.run(
					listOf("sh", "-c", "printf abc; echo oops >&2; exit 4"),
					10.seconds,
					1000,
				) as Ran.Ended

			assertEquals(
				Triple(4, "abc", "oops\n"),
				Triple(printed.exit, printed.out.toString(Charsets.UTF_8), printed.err),
			)
			assertTrue(
				Processes.SYSTEM.run(
					listOf("no-such-program-anywhere"),
					1.seconds,
					10,
				) is Ran.Missing,
			)
		}
}
