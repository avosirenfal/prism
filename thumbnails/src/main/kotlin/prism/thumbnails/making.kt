package prism.thumbnails

import java.nio.file.Path

/*
 * How a file's thumbnail is made. There are several ways, and which are tried for a file, and in what order, is
 * worked out from what the file is ([Making.makersFor]); each is tried in turn until one gives a picture:
 *
 * 1. The file decoded as the picture it is ([Maker.Decoded]), where it is of a format the decoder reads (Skia's:
 *    PNG, JPEG, WebP, GIF, BMP, ICO) and not too large to hold decoded.
 * 2. A picture the file carries of itself ([Maker.Carried]): a raw photograph's preview, a JPEG's Exif thumbnail,
 *    where it is as large as what is wanted.
 * 3. Each program that makes thumbnails of such a file ([Maker.Programmed]): the user's own first, then ffmpeg.
 * 4. The system's own maker ([Maker.Systems]), where there is one (Quick Look, on a Mac).
 * 5. The picture the file carries again, however small: a small picture is better than none.
 *
 * So an image the decoder cannot read after all goes on to ffmpeg, a raw file with no preview does too, and a file
 * nothing can make a picture of has none. That last is remembered only where a program was run on the file and failed
 * ([Outcome.Failed]): the file is then not tried again until it changes. Where nothing was run (there is no program
 * for it, or ffmpeg is not installed), nothing is remembered, since what would make its picture may yet be installed.
 */

/** A picture as a PNG's bytes, and how large it is. */
class Picture(
	val png: ByteArray,
	val extent: Extent,
) {
	companion object {
		/** [png] as a picture, where it is a PNG. */
		fun of(png: ByteArray): Picture? = Png.extent(png)?.let { Picture(png, it) }
	}
}

/** An encoded picture to decode: a file's own, or bytes already read. */
sealed interface Encoded {
	data class InFile(
		val file: Path,
	) : Encoded

	class Bytes(
		val bytes: ByteArray,
	) : Encoded
}

/**
 * What decodes pictures: Skia, where there are windows (the browser's `SkiaPictures`). This module only says what it
 * needs of one, so that it does not itself need the library windows are drawn with.
 */
interface Pictures {
	/** The extensions of the files it decodes. */
	val extensions: Set<String>

	/** How large the picture in [file] is, by its header alone: nothing is decoded. Null where it is none it reads. */
	fun extentOf(file: Path): Extent?

	/**
	 * [encoded] decoded, turned upright, and scaled down to fit a square [side] across (never up), as a PNG; null
	 * where it cannot be decoded. It is turned as it says itself it is held, unless [orientation] says (as Exif numbers
	 * it, 1 to 8): a raw file's preview is turned as the raw file says.
	 */
	fun png(encoded: Encoded, side: Int, orientation: Int? = null): Picture?

	companion object {
		/** Nothing decoded: every picture comes from a program that writes a PNG no larger than it was asked for. */
		val NONE: Pictures =
			object : Pictures {
				override val extensions = emptySet<String>()

				override fun extentOf(file: Path): Extent? = null

				override fun png(encoded: Encoded, side: Int, orientation: Int?): Picture? = null
			}
	}
}

/** The system's own maker of pictures of files, where it has one: Quick Look, on a Mac. */
fun interface SystemPictures {
	/** A picture of [file] no more than [side] across, as a PNG; null where the system makes none. */
	fun png(file: Path, side: Int): ByteArray?
}

/** What one way of making a picture came to. */
sealed interface Made {
	data class Pictured(
		val picture: Picture,
	) : Made

	/** Nothing, and [why]: it is not a way to make this file's picture, or there was nothing to make one from. */
	data class Not(
		val why: String,
	) : Made

	/** A program was run on the file to make one, and made none, and [why]: what is worth remembering of a file. */
	data class Failed(
		val why: String,
	) : Made
}

/** What making a file's thumbnail came to. */
sealed interface Outcome {
	/** A picture, and what made it. */
	data class Pictured(
		val picture: Picture,
		val by: String,
	) : Outcome

	/** No picture, and no program run that failed: nothing to remember. [tried] is each way tried, and why not. */
	data class Unmade(
		val tried: List<Pair<String, String>> = emptyList(),
	) : Outcome

	/** No picture, though a program was run to make one: remembered, until the file changes. */
	data class Failed(
		val tried: List<Pair<String, String>>,
	) : Outcome
}

/** One way of making a picture of a file. */
sealed interface Maker {
	/** What it is called, where what was tried is said. */
	val name: String

	/** The file decoded as the picture it is. */
	data class Decoded(
		val pictures: Pictures,
		/** The most pixels a picture may have to be decoded here: more would take too much memory while it is. */
		val limit: Long,
	) : Maker {
		override val name get() = "decoding"
	}

	/** A picture the file carries of itself: one [atLeast] as large as is wanted, or whichever there is. */
	data class Carried(
		val pictures: Pictures,
		val atLeast: Boolean,
	) : Maker {
		override val name get() = "embedded preview"
	}

	/** A program run. */
	data class Programmed(
		val thumbnailer: Thumbnailer,
		val pictures: Pictures,
		val processes: Processes,
	) : Maker {
		override val name get() = thumbnailer.name
	}

	/** The system's own. */
	data class Systems(
		val system: SystemPictures,
	) : Maker {
		override val name get() = "the system"
	}
}

/** [subject]'s picture, [side] across at most, made this way. */
suspend fun Maker.make(subject: Subject, side: Int): Made =
	when (this) {
		is Maker.Decoded -> {
			val extent = pictures.extentOf(subject.file)

			when {
				extent == null -> Made.Not("not a picture it reads")
				extent.pixels > limit ->
					Made.Not(
						"${extent.width}×${extent.height} is too large to decode",
					)
				else ->
					pictures.png(Encoded.InFile(subject.file), side)?.let(Made::Pictured)
						?: Made.Not("could not be decoded")
			}
		}
		is Maker.Carried ->
			Embedded.read(subject.file, side, atLeast)?.let { (jpeg, orientation) ->
				pictures.png(Encoded.Bytes(jpeg), side, orientation)?.let(Made::Pictured)
					?: Made.Not("its preview could not be decoded")
			} ?: Made.Not("no preview in the file")
		is Maker.Programmed -> thumbnailer.run(subject, side, processes, pictures)
		is Maker.Systems ->
			system.png(subject.file, side)?.let(Picture::of)?.let(Made::Pictured)
				?: Made.Not("none made")
	}

/**
 * What makes thumbnails, as it is set up: what decodes ([pictures]), the programs there are ([thumbnailers]), the
 * system's own maker, and what runs programs.
 */
class Making(
	val pictures: Pictures = Pictures.NONE,
	val thumbnailers: Thumbnailers = Thumbnailers.NONE,
	val system: SystemPictures? = null,
	val processes: Processes = Processes.SYSTEM,
	/** A file's MIME type, where something says: asked only where a program is chosen by type. */
	val typeOf: (Path) -> String? = { null },
	/** The most pixels decoded in this process ([Maker.Decoded.limit]). */
	val decodedLimit: Long = DECODED_LIMIT,
) {
	/** The ways of making [subject]'s picture, in the order they are tried. */
	fun makersFor(subject: Subject): List<Maker> {
		val extension = subject.extension
		val carries = extension in Embedded.EXTENSIONS && pictures !== Pictures.NONE

		return buildList {
			if (extension in pictures.extensions)
				add(Maker.Decoded(pictures, decodedLimit))
			if (carries)
				add(Maker.Carried(pictures, atLeast = true))
			thumbnailers
				.forFile(extension) { typeOf(subject.file) }
				.forEach { add(Maker.Programmed(it, pictures, processes)) }
			system?.let { add(Maker.Systems(it)) }
			if (carries)
				add(Maker.Carried(pictures, atLeast = false))
		}
	}

	/** Whether there is any way to make a picture of a file named with [extension], before its type is asked. */
	fun mayPicture(extension: String): Boolean =
		extension in pictures.extensions || extension in Embedded.EXTENSIONS ||
			system != null || thumbnailers.mayHandle(extension)

	/** [subject]'s picture, no more than [side] across: each way tried in turn, until one makes it. */
	suspend fun make(subject: Subject, side: Int): Outcome {
		val tried = ArrayList<Pair<String, String>>()
		var ran = false

		for (maker in makersFor(subject))
			when (val made = maker.make(subject, side)) {
				is Made.Pictured -> return Outcome.Pictured(made.picture, maker.name)
				is Made.Not -> tried += maker.name to made.why
				is Made.Failed -> {
					tried += maker.name to made.why
					ran = true
				}
			}

		return if (ran)
			Outcome.Failed(tried)
		else
			Outcome.Unmade(tried)
	}

	companion object {
		/**
		 * Sixty-four million pixels: a quarter of a gigabyte while it is decoded, which a few at once can be. A larger
		 * picture is ffmpeg's to make, in a process of its own.
		 */
		const val DECODED_LIMIT = 64_000_000L
	}
}
