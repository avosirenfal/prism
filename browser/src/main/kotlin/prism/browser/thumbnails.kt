package prism.browser

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import kotlin.io.path.name
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.makeFromFileName
import prism.desktop.Desktop
import prism.desktop.SessionEnvironment
import prism.desktop.systemPictures
import prism.platform.OwnFolders
import prism.preferences.PreferenceStore
import prism.preferences.preferences
import prism.shared.Guarded
import prism.thumbnails.Encoded
import prism.thumbnails.Extent
import prism.thumbnails.Ffmpeg
import prism.thumbnails.Found
import prism.thumbnails.Making
import prism.thumbnails.Picture
import prism.thumbnails.Pictures
import prism.thumbnails.Processes
import prism.thumbnails.Subject
import prism.thumbnails.ThumbnailService
import prism.thumbnails.Wanted
import prism.thumbnails.Watching

/**
 * Pictures decoded by Skia, the library Compose draws with: what the thumbnails' makers decode with wherever prism has
 * windows ([Pictures]). Its decoders are its own, built into it, and the same on every platform: PNG, JPEG, WebP, GIF,
 * BMP, ICO and WBMP. It asks nothing of the system's codecs, so what it reads on one machine it reads on every one,
 * and what it does not read (HEIC, AVIF, TIFF, a raw photograph) is ffmpeg's to make a picture of.
 */
object SkiaPictures : Pictures {
	override val extensions: Set<String> =
		setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "wbmp")

	/** How large a file's picture is, as its header says and as the file holds it. Nothing is decoded. */
	override fun extentOf(file: Path): Extent? =
		reading {
			Data.makeFromFileName(file.toString()).use { data ->
				Codec.makeFromData(data).use { Extent(it.width, it.height) }
			}
		}

	override fun png(encoded: Encoded, side: Int, orientation: Int?): Picture? =
		scaled(encoded, side, orientation, Making.DECODED_LIMIT)?.use { image ->
			image.encodeToData(EncodedImageFormat.PNG)?.use {
				Picture(it.bytes, Extent(image.width, image.height))
			}
		}

	/**
	 * [encoded] decoded, turned upright and scaled down to fit a square of [side]; null where it cannot be read or
	 * decoded, or has more than [limit] pixels, which would take too much memory while it is decoded. A file is
	 * mapped and decoded by Skia, in memory of its own: read into Java's, a photograph would be megabytes of garbage
	 * for a picture of a few thousand pixels, for every tile of a folder of them.
	 */
	fun scaled(
		encoded: Encoded,
		side: Int,
		orientation: Int? = null,
		limit: Long = Making.DECODED_LIMIT,
	): Image? {
		val (pixels, origin) =
			reading {
				data(encoded).use { data ->
					Codec.makeFromData(data).use { codec ->
						if (codec.width.toLong() * codec.height > limit)
							null
						else
							codec.readPixels() to codec.encodedOrigin
					}
				}
			} ?: return null

		return pixels.use { scaled(it, orientation?.let(::originOf) ?: origin, side) }
	}

	private fun data(encoded: Encoded): Data =
		when (encoded) {
			is Encoded.InFile -> Data.makeFromFileName(encoded.file.toString())
			is Encoded.Bytes -> Data.makeFromBytes(encoded.bytes)
		}

	/** How a picture is turned, as Exif numbers it, in Skia's words: they are numbered alike, from one. */
	private fun originOf(orientation: Int): EncodedOrigin? =
		EncodedOrigin.entries.getOrNull(orientation - 1)

	/**
	 * What [read] reads, or null where Skia cannot: it throws for a file it cannot map (gone since it was asked of)
	 * and for what is no picture it knows, and nothing else here is expected to fail.
	 */
	private inline fun <T> reading(read: () -> T?): T? =
		try {
			read()
		} catch (undecodable: RuntimeException) {
			null
		}

	/**
	 * [pixels], which are a picture as its file holds it, the way up [origin] says it is (a photograph taken with the
	 * camera on its side says so, and is held on its side), scaled down to fit a square of [side].
	 */
	private fun scaled(pixels: Bitmap, origin: EncodedOrigin, side: Int): Image {
		val turned = origin.swapsWidthHeight()
		val across = if (turned) pixels.height else pixels.width
		val down = if (turned) pixels.width else pixels.height
		val scale = minOf(side.toFloat() / max(across, down), 1f)
		val width = (across * scale).roundToInt().coerceAtLeast(1)
		val height = (down * scale).roundToInt().coerceAtLeast(1)
		val held = Rect.makeWH(pixels.width.toFloat(), pixels.height.toFloat())

		// Its pixels as they are, not a copy of them: nothing writes to them after this.
		pixels.setImmutable()

		return Image.makeFromBitmap(pixels).use { image ->
			Surface.makeRasterN32Premul(width, height).use { surface ->
				surface.canvas
					.scale(width.toFloat() / across, height.toFloat() / down)
					.concat(origin.toMatrix(across, down))
					.drawImageRect(
						image,
						held,
						held,
						FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
						null,
						true,
					)
				surface.makeImageSnapshot()
			}
		}
	}
}

/**
 * Small pictures of files, for the gallery, as a window draws them. Where each is to be had is the thumbnail
 * service's to say ([ThumbnailService], docs/thumbnails.md): a small image is decoded as it is, each time; anything
 * else has a thumbnail in the cache, found there or made (by Skia, from a preview the file carries, or by ffmpeg),
 * and that is what is decoded. A picture drawn larger than any thumbnail is kept is decoded from the file itself.
 * With no [service], nothing is looked for or kept on disk: an image Skia reads is decoded each time, and nothing else
 * has a picture, which is what a window run with nothing behind it does, and the tests that draw one.
 *
 * Each picture decoded is kept here in memory by the file's path, the time it was last changed and the size, so that
 * a file is decoded once however often its tile is drawn, and again only once it has changed. That a file has no
 * picture is kept so too, for a while ([FORGOTTEN]): a folder of files with none is not looked through again each
 * time it is scrolled, and a picture another program makes meanwhile is found soon enough.
 *
 * Decoding a photograph takes tens of milliseconds and the whole image's memory while it lasts, so at most
 * [parallel] are decoded at once, on IO threads; a tile scrolled away before its turn comes is never made at all,
 * since asking is cancelled with it. What is kept is the pictures used most recently, up to [budget] pixels in all
 * (four bytes each).
 *
 * What is kept is the one thing here that changes, and it is a cache: asking twice gives the same picture whether or
 * not it was kept in between.
 */
class Thumbnails(
	/** What finds and makes thumbnails; none, for pictures of images alone, decoded and kept nowhere. */
	val service: ThumbnailService? = null,
	private val budget: Long = 16L * 1024 * 1024,
	parallel: Int = 4,
	/** What time it is, in nanoseconds from whenever: what a file known to have no picture is forgotten by. */
	private val now: () -> Long = System::nanoTime,
) {
	private data class Key(
		val path: Path,
		val modified: Instant?,
		val side: Int,
	)

	/** What is known of a file's picture: the picture, or that it has none. */
	private sealed interface Known {
		data class Pictured(
			val picture: ImageBitmap,
		) : Known

		data object None : Known
	}

	/**
	 * What is kept: the pictures, the least recently used first, and how many pixels they hold in all; and the files
	 * known to have none, with when each was found so. Reached only through [kept], one thread at a time.
	 */
	private class Kept(
		private val budget: Long,
	) {
		private val pictures = LinkedHashMap<Key, ImageBitmap>(64, 0.75f, true)
		private var pixels = 0L

		private val none =
			object : LinkedHashMap<Key, Long>(64, 0.75f, true) {
				override fun removeEldestEntry(eldest: Map.Entry<Key, Long>): Boolean =
					size > NONE_KEPT
			}

		/** What is known of [key] at [now]; null where nothing is, or it was found to have none too long ago. */
		fun known(key: Key, now: Long): Known? =
			pictures[key]?.let(Known::Pictured)
				?: none[key]?.let { found ->
					if (now - found < FORGOTTEN)
						Known.None
					else
						null.also { none.remove(key) }
				}

		/** [key]'s picture kept as [picture], or that it has none, found at [now]. */
		fun keep(key: Key, picture: ImageBitmap?, now: Long) {
			if (picture == null) {
				none[key] = now

				return
			}

			none.remove(key)
			pictures.put(key, picture)?.let { pixels -= it.area }
			pixels += picture.area

			val oldest = pictures.entries.iterator()

			while (pixels > budget && oldest.hasNext()) {
				pixels -= oldest.next().value.area
				oldest.remove()
			}
		}
	}

	private val kept = Guarded(Kept(budget))

	private val decoding: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(parallel)

	/**
	 * [path]'s picture, last changed at [modified] and of [bytes] bytes where that is known, to be drawn [side] pixels
	 * across at most; null for a file that has none. Whoever asks is shown the file: its thumbnail is made ahead of
	 * any that is only near what a window shows ([near]), and is not made if they stop waiting.
	 */
	suspend fun of(
		path: Path,
		modified: Instant?,
		side: Int = SIDES[1],
		bytes: Long? = null,
	): ImageBitmap? {
		val key = Key(path, modified, sideFor(side))

		return when (val known = kept.guarded { known(key, now()) }) {
			is Known.Pictured -> known.picture
			Known.None -> null
			null ->
				made(path, modified, bytes, key.side).also {
					kept.guarded { keep(key, it, now()) }
				}
		}
	}

	/**
	 * The picture of [path] made: decoded from wherever the service says it is to be had, the file itself or its
	 * thumbnail. A file whose time is not known cannot be told from one changed since its thumbnail was made, so it
	 * has none looked for: it is decoded itself, where it is an image.
	 */
	private suspend fun made(
		path: Path,
		modified: Instant?,
		bytes: Long?,
		side: Int,
	): ImageBitmap? {
		val from =
			if (service == null || modified == null)
				path.takeIf { drawable(it.name) }
			else
				when (val found = service.find(Subject(path, modified, bytes), side)) {
					is Found.Kept -> found.thumbnail
					Found.Itself -> path
					Found.None -> null
				}

		return from?.let {
			withContext(decoding) {
				SkiaPictures.scaled(Encoded.InFile(it), side)?.use(Image::toComposeImageBitmap)
			}
		}
	}

	/**
	 * What [owner], a window's listing, shows nothing of yet and is near what it does: the files of [wanted], the
	 * nearest first, each to be drawn [side] across. Their thumbnails are made once what is shown has its own, so that
	 * scrolling comes upon them made. Said again as the listing scrolls; what is no longer near is no longer made.
	 */
	fun near(owner: Any, wanted: List<Subject>, side: Int) {
		service?.near(owner, wanted.map { Wanted(it, sideFor(side)) })
	}

	/** [owner] shows another folder, or is closed: nothing more is made for it. */
	fun forget(owner: Any) {
		service?.forget(owner)
	}

	/** How many files either side of what a listing shows are made ahead. */
	val ahead: Int get() = service?.settings?.ahead ?: 0

	companion object {
		/** The sizes a picture is made at, across: the first at least as large as it is drawn is the one made. */
		private val SIDES = listOf(128, 256, 512, 1024, 2048)

		/** The size a picture drawn [wanted] pixels across is made at. */
		fun sideFor(wanted: Int): Int = SIDES.firstOrNull { it >= wanted } ?: SIDES.last()

		private val ImageBitmap.area: Long get() = width.toLong() * height

		/** How long a file found to have no picture is taken to have none, in nanoseconds: ten seconds. */
		private const val FORGOTTEN = 10_000_000_000L

		/** How many files with no picture are remembered at once. */
		private const val NONE_KEPT = 16384

		/** Whether a file of [name] is one Skia decodes, by its extension. */
		fun drawable(name: String): Boolean =
			name.substringAfterLast('.', "").lowercase(Locale.ROOT) in SkiaPictures.extensions

		/**
		 * The files of [rows] near those from [first] to [last], which are in sight: up to [ahead] after them and as
		 * many before, the nearest first, turn about, since which way the listing will be scrolled is not known. Only
		 * files that may have a picture are among them: on disk, and with a time to know a thumbnail by.
		 */
		fun around(rows: Rows, first: Int, last: Int, ahead: Int): List<Subject> =
			(1..ahead)
				.asSequence()
				.flatMap { sequenceOf(last + it, first - it) }
				.filter { it in 0 until rows.size }
				.mapNotNull { subjectOf(rows[it]) }
				.toList()

		/** [row] as what a thumbnail is of; null for what has none (a folder, an entry of a place, a row not yet read). */
		fun subjectOf(row: Row): Subject? {
			val path = row.path?.takeUnless { row.isFolder } ?: return null

			return row.modified?.let { Subject(path, it, row.size?.value) }
		}
	}
}

/** What says a folder shown has changed, as what says the folder thumbnails are kept in has: the same watcher. */
fun FolderWatcher.watching(): Watching =
	Watching { folder, changed ->
		watch(folder) { touch -> changed((touch as? Touch.Entries)?.names) }
	}

/**
 * The pictures of a prism with windows: one service for the process, set up as the user's [preferences] say and set
 * up again as they change, making with Skia, the system's own maker where it has one, and the programs there are;
 * its work done in [scope], which lasts as long as the process shows windows. [desktop] says what type a file is,
 * for a program chosen by type; [watching] says when another program adds to the folder thumbnails are kept in; and
 * what is known of how they are used is kept in [usage] between runs.
 */
fun thumbnailsFor(
	preferences: PreferenceStore,
	scope: CoroutineScope,
	desktop: Desktop?,
	watching: Watching,
	usage: Path? =
		OwnFolders
			.of()
			.state.path
			.resolve("thumbnails.used"),
	report: (String, Throwable) -> Unit = { _, _ -> },
): Thumbnails {
	// Programs are found and run as the user's session would find and run them.
	val processes = Processes.system { SessionEnvironment.variables }

	val service =
		ThumbnailService(
			scope,
			preferences.preferences.value.thumbnailing,
			pictures = SkiaPictures,
			system = systemPictures(),
			processes = processes,
			typeOf = { file -> desktop?.types?.of(file)?.name },
			ffmpeg =
				scope.async(Dispatchers.IO) {
					Ffmpeg.find(Ffmpeg.pathOf(SessionEnvironment.variables), processes)
				},
			usage = usage,
			watching = watching,
			report = report,
		)

	scope.launch {
		preferences.preferences
			.map { it.thumbnailing }
			.distinctUntilChanged()
			.collect(service::configure)
	}

	return Thumbnails(service)
}

/** The pictures the gallery draws its tiles with: of images alone and kept nowhere, unless a window is given more. */
val LocalThumbnails = staticCompositionLocalOf { Thumbnails() }

/**
 * Whether files are drawn by their pictures at all: where not (the user's choice, kept in the preferences), each is
 * its type's icon, and no picture is made or looked for.
 */
val LocalPictured = compositionLocalOf { true }
