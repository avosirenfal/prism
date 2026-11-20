package prism.thumbnails

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import prism.platform.Os

/*
 * ffmpeg, as a maker of thumbnails. It is the `ffmpeg` program run on a file, a process each time, and never its
 * libraries called from here: what a damaged file does to ffmpeg then happens in that process (processes.kt), and one
 * command line is the same on every platform and across every version of ffmpeg, as its libraries' layouts are not.
 *
 * What it makes thumbnails of is found out, not assumed: as prism starts, `ffmpeg -formats` is asked what this ffmpeg
 * reads (one built without WebM, or with SVG, says so), and the formats it names are matched against what prism knows
 * of each ([FORMATS]: the extensions and types of its files, and what kind of thing it is). The kind decides the
 * command:
 *
 * - a picture is decoded and scaled;
 * - a film is first asked how long it is, and a frame taken a tenth of the way in (past the black it opens on and
 *   the titles), the most telling of the frames about there (ffmpeg's `thumbnail` filter); a film too short to seek
 *   in has its first frames looked at instead;
 * - a sound file has the cover it carries, where it carries one, and no picture otherwise.
 *
 * The picture is scaled down to fit and never up, with the film's own shape (pixels that are not square made so),
 * and printed as a PNG.
 */

/** What a file ffmpeg reads is, as far as making a picture of it goes. */
enum class Medium {
	/** A still picture: decoded and scaled. */
	Picture,

	/** A film: a frame from a little way in. */
	Film,

	/** Sound: the cover it carries, if any. */
	Sound,
}

/** A kind of file ffmpeg may read: the format (demuxer) it reads it as, and the files that are of it. */
data class FfmpegFormat(
	val demuxer: String,
	val medium: Medium,
	val extensions: Set<String>,
	val types: Set<String> = emptySet(),
)

/** An ffmpeg found: the [program], and the formats it says it reads ([demuxers]). */
data class Ffmpeg(
	val program: Path,
	val demuxers: Set<String>,
) {
	/** The formats prism knows that this ffmpeg reads. */
	val formats: List<FfmpegFormat> get() = FORMATS.filter { it.demuxer in demuxers }

	/** The files it is for. */
	val handles: Handles by lazy {
		Handles(
			formats.flatMap { it.extensions }.toSet(),
			formats.flatMap { it.types }.toSet(),
		)
	}

	private val byExtension: Map<String, Medium> by lazy {
		formats.flatMap { format -> format.extensions.map { it to format.medium } }.toMap()
	}

	/** What a file named with [extension] is; one known only by its type is what that type's kind is. */
	fun mediumOf(extension: String): Medium? = byExtension[extension]

	/** The command for each kind of file, as it is run: what the preferences show, and cannot change. */
	val commands: Map<Medium, Template>
		get() =
			mapOf(
				Medium.Picture to command(PICTURE),
				Medium.Film to command(FRAME),
				Medium.Sound to command(COVER),
			)

	private fun command(arguments: List<String>): Template =
		Template.own(program.toString(), *arguments.toTypedArray())

	/** [subject]'s picture, [side] across at most. */
	suspend fun picture(
		subject: Subject,
		side: Int,
		processes: Processes,
		limit: Duration,
	): Made {
		suspend fun run(arguments: List<String>, seek: Duration = Duration.ZERO): Made {
			val words =
				command(arguments).filled {
					when (it) {
						Slot.Input, Slot.Uri -> subject.file.toString()
						Slot.Size -> "$side"
						Slot.Seek -> "%.3f".format(java.util.Locale.ROOT, seek.inWholeMilliseconds / 1000.0)
						Slot.Output -> "pipe:1"
					}
				}

			return when (val ran = processes.run(words, limit, Processes.MOST)) {
				is Ran.Missing -> Made.Not("ffmpeg is no longer installed")
				is Ran.Killed -> Made.Failed(ran.why)
				is Ran.Ended ->
					when {
						ran.exit != 0 -> Made.Failed(saidOf(ran))
						// It prints nothing, and says nothing is wrong, where there was no frame to print.
						ran.out.isEmpty() -> Made.Failed("no picture came of it")
						else ->
							Picture.of(ran.out)?.let(Made::Pictured)
								?: Made.Failed("what it printed is no PNG")
					}
			}
		}

		if (mediumOf(subject.extension) == Medium.Picture)
			return run(PICTURE)

		val probed =
			when (
				val ran =
					processes.run(
						command(PROBE).filled {
							subject.file.toString()
						},
						limit,
						SAID,
					)
			) {
				is Ran.Missing -> return Made.Not("ffmpeg is no longer installed")
				is Ran.Killed -> return Made.Failed(ran.why)
				// With nothing to write, it says what the file is and ends in error: what it says is what was asked.
				is Ran.Ended -> Probed.of(ran.err)
			}

		return when {
			probed.film -> {
				val seek = seekOf(probed.duration)
				val sought = run(FRAME, seek)

				// Some files cannot be sought in, and say so by giving nothing: their beginning is still theirs.
				if (sought is Made.Failed && seek > Duration.ZERO)
					run(FRAME)
				else
					sought
			}
			probed.cover -> run(COVER)
			else -> Made.Failed(probed.problem ?: "nothing in it to make a picture of")
		}
	}

	/** What ffmpeg says a file is, with no output to write: how long it lasts, and what pictures are in it. */
	data class Probed(
		val duration: Duration?,
		/** Whether it has moving pictures. */
		val film: Boolean,
		/** Whether it carries a cover: one picture, said to be attached. */
		val cover: Boolean,
		/** What ffmpeg said was wrong with the file, where it could not read it. */
		val problem: String? = null,
	) {
		companion object {
			private val DURATION = Regex("Duration: (\\d+):(\\d\\d):(\\d\\d(?:\\.\\d+)?)")
			private val VIDEO = Regex("Stream #.*: Video: ")

			/** What [said], ffmpeg's account of a file, says. */
			fun of(said: String): Probed {
				val streams = said.lineSequence().filter { VIDEO.containsMatchIn(it) }.toList()
				val (covers, films) = streams.partition { "(attached pic)" in it }

				return Probed(
					DURATION.find(said)?.destructured?.let { (hours, minutes, seconds) ->
						(hours.toLong() * 3600 + minutes.toLong() * 60).seconds +
							(seconds.toDouble() * 1000).toLong().milliseconds
					},
					films.isNotEmpty(),
					covers.isNotEmpty(),
					// Where it could not read the file at all, what it last said is why: "Invalid data found when
					// processing input".
					said
						.lineSequence()
						.map { it.trim() }
						.lastOrNull { it.isNotEmpty() }
						.takeIf { "Input #" !in said },
				)
			}
		}
	}

	companion object {
		/** How far in a film's frame is taken from: a tenth of the way, and no further than this. */
		val FURTHEST: Duration = 300.seconds

		/** Where in a film lasting [duration] its frame is taken from; its beginning, where it does not say. */
		fun seekOf(duration: Duration?): Duration =
			duration?.let { minOf(it / 10, FURTHEST) }?.takeIf { it >= 1.seconds } ?: Duration.ZERO

		/**
		 * Scaled down to fit a square of the size asked, never up, the picture's shape kept; pixels that are not
		 * square (a DVD's, an anamorphic film's) made square first, so that the shape kept is the one shown.
		 */
		private const val FIT =
			"scale=w='trunc(iw*sar)':h=ih,setsar=1," +
				"scale=w='min({size},iw)':h='min({size},ih)':force_original_aspect_ratio=decrease"

		private val QUIET = listOf("-v", "error", "-nostdin")
		private val PRINTED = listOf("-f", "image2pipe", "-c:v", "png", "{output}")

		/** A picture: its first frame. */
		private val PICTURE =
			QUIET + listOf("-i", "{input}", "-frames:v", "1", "-an", "-sn", "-dn", "-vf", FIT) +
				PRINTED

		/**
		 * A film: sought before it is opened, which goes to the key frame before and decodes from there; its moving
		 * pictures alone (`V`: not a cover); and of the couple of dozen frames from there, the one most like the rest.
		 */
		private val FRAME =
			QUIET +
				listOf("-ss", "{seek}", "-i", "{input}", "-map", "0:V:0", "-frames:v", "1") +
				listOf("-an", "-sn", "-dn", "-vf", "thumbnail=n=24,$FIT") + PRINTED

		/** A cover: the first picture there is, which in a sound file is it. */
		private val COVER =
			QUIET +
				listOf("-i", "{input}", "-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-dn") +
				listOf("-vf", FIT) + PRINTED

		/** What the file is, said and nothing made. */
		private val PROBE = listOf("-hide_banner", "-nostdin", "-i", "{input}")

		/** The most ffmpeg is let print where it is only asked something. */
		private const val SAID = 1 shl 20

		/** What `ffmpeg -formats` is asked with. */
		val ASKED = listOf("-hide_banner", "-formats")

		/**
		 * The formats [listed], which is what `ffmpeg -formats` prints, says it reads. After its heading, each line is
		 * flags, the format's names with commas between, and what it is: a `D` among the flags is a format read.
		 * ffmpeg 7 has a third flag, for devices; both are read.
		 */
		fun demuxersOf(listed: String): Set<String> =
			listed
				.lineSequence()
				.dropWhile { !it.trim().startsWith("--") }
				.drop(1)
				.mapNotNull(LISTED::find)
				.filter { it.groupValues[1] == "D" }
				.flatMap { it.groupValues[2].split(',') }
				.toSet()

		private val LISTED = Regex("^ ([D ])[E ][d ]? *([A-Za-z0-9_,]+)")

		/** The ffmpeg on [path], asked what it reads; null where there is none, or it does not answer. */
		suspend fun find(
			path: List<Path> = pathOf(System.getenv()),
			processes: Processes = Processes.SYSTEM,
			os: Os = Os.current,
		): Ffmpeg? {
			val program = programOn(path, os) ?: return null

			return when (val ran = processes.run(listOf("$program") + ASKED, 10.seconds, SAID)) {
				is Ran.Ended ->
					demuxersOf(ran.out.toString(Charsets.UTF_8))
						.takeIf { ran.exit == 0 && it.isNotEmpty() }
						?.let { Ffmpeg(program, it) }
				is Ran.Missing, is Ran.Killed -> null
			}
		}

		/** The folders programs are looked for in, as [environment] says. */
		fun pathOf(environment: Map<String, String>): List<Path> =
			environment["PATH"]
				.orEmpty()
				.split(File.pathSeparatorChar)
				.filter { it.isNotEmpty() }
				.map(Path::of)

		/** The first `ffmpeg` there is in [path] that can be run. */
		fun programOn(path: List<Path>, os: Os = Os.current): Path? {
			val name =
				if (os == Os.Windows)
					"ffmpeg.exe"
				else
					"ffmpeg"

			return path
				.asSequence()
				.map { it.resolve(name) }
				.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
		}

		private fun films(demuxer: String, types: Set<String>, vararg extensions: String) =
			FfmpegFormat(demuxer, Medium.Film, extensions.toSet(), types)

		private fun pictures(demuxer: String, types: Set<String>, vararg extensions: String) =
			FfmpegFormat(demuxer, Medium.Picture, extensions.toSet(), types)

		private fun sounds(demuxer: String, types: Set<String>, vararg extensions: String) =
			FfmpegFormat(demuxer, Medium.Sound, extensions.toSet(), types)

		/**
		 * What prism knows of ffmpeg's formats: each by the name `ffmpeg -formats` lists it under, with the files that
		 * are of it. A format is here more than once where its files are of more than one kind (the MP4 family holds
		 * films, songs, and still pictures). An ffmpeg that does not list a format's name is not asked for its files.
		 */
		val FORMATS: List<FfmpegFormat> =
			listOf(
				films("matroska", setOf("video/x-matroska", "video/webm"), "mkv", "mk3d", "webm"),
				films(
					"mov",
					setOf("video/mp4", "video/quicktime", "video/3gpp", "video/3gpp2", "video/x-m4v"),
					"mp4",
					"m4v",
					"mov",
					"qt",
					"3gp",
					"3g2",
					"f4v",
				),
				films("avi", setOf("video/x-msvideo", "video/avi", "video/vnd.avi"), "avi", "divx"),
				films("flv", setOf("video/x-flv"), "flv"),
				films("asf", setOf("video/x-ms-wmv", "video/x-ms-asf"), "wmv", "asf"),
				films("mpegts", setOf("video/mp2t"), "ts", "m2ts", "mts"),
				films("mpeg", setOf("video/mpeg"), "mpg", "mpeg", "vob", "m2p"),
				films("mpegvideo", emptySet(), "m2v", "m1v"),
				films("ogg", setOf("video/ogg"), "ogv", "ogm"),
				films("rm", setOf("application/vnd.rn-realmedia"), "rm", "rmvb"),
				films("mxf", setOf("application/mxf"), "mxf"),
				films("dv", setOf("video/dv"), "dv"),
				films("nut", emptySet(), "nut"),
				films("ivf", emptySet(), "ivf"),
				films("wtv", emptySet(), "wtv"),
				films("gxf", emptySet(), "gxf"),
				films("swf", setOf("application/vnd.adobe.flash.movie"), "swf"),
				films("yuv4mpegpipe", emptySet(), "y4m"),
				films("h264", emptySet(), "h264", "264"),
				films("hevc", emptySet(), "hevc", "h265", "265"),
				films("gif", setOf("image/gif"), "gif"),
				films("apng", setOf("image/apng"), "apng"),
				pictures(
					"mov",
					setOf("image/avif", "image/heic", "image/heif"),
					"avif",
					"heic",
					"heif",
				),
				pictures("png_pipe", setOf("image/png"), "png"),
				pictures("jpeg_pipe", setOf("image/jpeg"), "jpg", "jpeg", "jpe"),
				pictures("webp_pipe", setOf("image/webp"), "webp"),
				pictures("bmp_pipe", setOf("image/bmp"), "bmp"),
				pictures("tiff_pipe", setOf("image/tiff"), "tif", "tiff"),
				pictures("jpegxl_pipe", setOf("image/jxl"), "jxl"),
				pictures("j2k_pipe", setOf("image/jp2", "image/jpx"), "jp2", "j2k", "jpf", "jpx"),
				pictures("psd_pipe", setOf("image/vnd.adobe.photoshop"), "psd"),
				pictures("exr_pipe", setOf("image/x-exr"), "exr"),
				pictures("dds_pipe", setOf("image/x-dds"), "dds"),
				pictures("qoi_pipe", setOf("image/qoi"), "qoi"),
				pictures("hdr_pipe", setOf("image/vnd.radiance"), "hdr"),
				pictures("svg_pipe", setOf("image/svg+xml"), "svg"),
				pictures("ppm_pipe", setOf("image/x-portable-pixmap"), "ppm"),
				pictures("pgm_pipe", setOf("image/x-portable-graymap"), "pgm"),
				pictures("pbm_pipe", setOf("image/x-portable-bitmap"), "pbm"),
				pictures("pam_pipe", emptySet(), "pam"),
				pictures("sgi_pipe", setOf("image/x-sgi"), "sgi"),
				pictures("pcx_pipe", setOf("image/vnd.zbrush.pcx"), "pcx"),
				pictures("xpm_pipe", setOf("image/x-xpixmap"), "xpm"),
				pictures("xbm_pipe", setOf("image/x-xbitmap"), "xbm"),
				pictures("dpx_pipe", emptySet(), "dpx"),
				pictures("sunrast_pipe", emptySet(), "ras", "sun"),
				pictures("ico", setOf("image/vnd.microsoft.icon"), "ico", "cur"),
				// Read by their names alone, as a numbered picture of a film is: there is nothing in them to know them by.
				pictures("image2", setOf("image/x-tga"), "tga"),
				sounds("mp3", setOf("audio/mpeg"), "mp3"),
				sounds("flac", setOf("audio/flac"), "flac"),
				sounds("ogg", setOf("audio/ogg", "audio/opus"), "ogg", "oga", "opus"),
				sounds("mov", setOf("audio/mp4", "audio/x-m4b"), "m4a", "m4b"),
				sounds("asf", setOf("audio/x-ms-wma"), "wma"),
				sounds("ape", emptySet(), "ape"),
				sounds("wv", emptySet(), "wv"),
				sounds("aiff", setOf("audio/aiff"), "aif", "aiff"),
			)
	}
}
