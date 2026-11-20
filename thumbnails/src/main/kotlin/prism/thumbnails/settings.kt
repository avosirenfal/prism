package prism.thumbnails

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * What the user has set of thumbnails, as the preferences keep it: where they are kept, how much may be, how large
 * they are made, how far ahead of what is shown they are made, and the programs that make them.
 */

/** How large a thumbnail is made. */
sealed interface Resolution {
	/** The size the cache keeps that is at least as large as [wanted], the pixels a picture is drawn across. */
	fun sizeFor(wanted: Int): ThumbnailSize

	/**
	 * As large as it is drawn: the smallest of the cache's sizes that is at least that, so a gallery of small tiles
	 * makes small thumbnails, quickly, and one zoomed in makes sharp ones.
	 */
	data object Automatic : Resolution {
		override fun sizeFor(wanted: Int): ThumbnailSize = ThumbnailSize.toMake(wanted)
	}

	/** Always [size], however large it is drawn: one size made, and scaled as it is shown. */
	data class Fixed(
		val size: ThumbnailSize,
	) : Resolution {
		override fun sizeFor(wanted: Int): ThumbnailSize = size
	}
}

/** What the user has set of thumbnails. */
@Serializable
data class ThumbnailSettings(
	/** The folder they are kept in, as an absolute path; null for the platform's own ([ThumbnailCache.defaultRoot]). */
	val folder: String? = null,
	/** How much is kept. */
	val budget: Budget = Budget(),
	/** The one size thumbnails are made at; null for whatever size each is drawn at ([Resolution.Automatic]). */
	val size: ThumbnailSize? = null,
	/**
	 * How many files before what a window shows, and as many after, have their thumbnails made once what it shows
	 * has its own: so that scrolling comes upon pictures already made, and a folder of a hundred thousand is not all
	 * made because it was opened.
	 */
	val nearby: Int = NEARBY,
	/** The ids of the programs turned off, ffmpeg's among them ([Thumbnailer.FFMPEG]). */
	val off: Set<String> = emptySet(),
	/** The user's own programs, each as it is written ([ThumbnailerSpec]), in the order they are tried. */
	val thumbnailers: List<JsonElement> = emptyList(),
) {
	val resolution: Resolution get() = size?.let(Resolution::Fixed) ?: Resolution.Automatic

	/** The folder thumbnails are kept in: the one set, where it is a path from the root; the platform's own otherwise. */
	fun root(otherwise: () -> Path = { ThumbnailCache.defaultRoot() }): Path =
		folderOf(folder) ?: otherwise()

	/** The budget, never less than a cache needs to be one. */
	val kept: Budget
		get() =
			Budget(
				budget.bytes.coerceAtLeast(Budget.LEAST_BYTES),
				budget.files.coerceAtLeast(Budget.LEAST_FILES),
			)

	/** How many files either side of what is shown are made ahead: none, for a number that is not one. */
	val ahead: Int get() = nearby.coerceIn(0, MOST_NEARBY)

	companion object {
		const val NEARBY = 150
		const val MOST_NEARBY = 2000

		/** [written] as a folder thumbnails can be kept in: a path from the root; null for anything else. */
		fun folderOf(written: String?): Path? =
			try {
				written
					?.trim()
					?.takeIf { it.isNotEmpty() }
					?.let(Path::of)
					?.takeIf { it.isAbsolute }
					?.normalize()
			} catch (wrong: InvalidPathException) {
				null
			}
	}
}
