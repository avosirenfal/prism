package prism.browser

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import kotlin.io.path.name
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.makeFromFileName
import prism.desktop.SharedThumbnails
import prism.shared.Guarded

/**
 * Small pictures of files, for the gallery. An image file's is decoded by Skia (the library Compose draws with), scaled
 * to fit a square of one of a few sizes, from 128 to 2048 pixels, the first at least as large as the tile draws it (so
 * a gallery made larger gets sharper pictures, up to one picture as large as the window). A file Skia cannot decode (a
 * video, a document) has the picture the desktop's programs have made of it, where one of them has: the thumbnail
 * cache they [share][ThumbnailCache], which is read and never written. An image's is taken from there too where there
 * is one as large as is wanted, which saves decoding the image itself.
 *
 * Each is kept by the file's path, the time it was last changed and the size, so that a file is decoded once however
 * often its tile is drawn, and again only once it has changed. That a file has no picture is kept so too, for a
 * while ([FORGOTTEN]): a folder of files with none is not looked through again each time it is scrolled, and a
 * picture another program makes meanwhile is found soon enough.
 *
 * Decoding a photograph takes tens of milliseconds and the whole image's memory while it lasts, so at most
 * [parallel] are made at once, on IO threads; a tile scrolled away before its turn comes is never made at all,
 * since asking is cancelled with it. What is kept is the pictures used most recently, up to [budget] pixels in all
 * (four bytes each).
 *
 * What is kept is the one thing here that changes, and it is a cache: asking twice gives the same picture whether or
 * not it was kept in between.
 */
class Thumbnails(
	private val budget: Long = 16L * 1024 * 1024,
	parallel: Int = 4,
	/** The thumbnails the desktop's programs share; none, for pictures of images alone. */
	private val shared: SharedThumbnails? = SharedThumbnails.system(),
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
		data class Picture(
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
			pictures[key]?.let(Known::Picture)
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
	 * across at most; null for a file that is not an image Skia can decode and has no thumbnail in the shared cache.
	 */
	suspend fun of(
		path: Path,
		modified: Instant?,
		side: Int = SIDES[1],
		bytes: Long? = null,
	): ImageBitmap? {
		val key = Key(path, modified, sideFor(side))

		return when (val known = kept.guarded { known(key, now()) }) {
			is Known.Picture -> known.picture
			Known.None -> null
			null ->
				withContext(decoding) { made(path, modified, bytes, key.side) }
					.also { kept.guarded { keep(key, it, now()) } }
		}
	}

	/**
	 * The picture of [path] made: from the shared cache's thumbnail of it, where that is still its picture, or, for
	 * an image, from the image itself. An image is taken from the cache only at a size at least [side], so that it
	 * is as sharp as one made here; anything else, at the best size there is, since there is nothing else to draw.
	 */
	private fun made(path: Path, modified: Instant?, bytes: Long?, side: Int): ImageBitmap? {
		val image = drawable(path.name)

		// A file whose time is not known cannot be told from one changed since its thumbnail was made.
		val thumbnail = modified?.let { shared?.find(path, it, bytes, side, smaller = !image) }

		return thumbnail?.let { pictureOf(it, side) }
			?: if (image)
				pictureOf(path, side)
			else
				null
	}

	/**
	 * [path] decoded and scaled down to fit a square of [side]; null where it cannot be read or decoded. The file is
	 * mapped and decoded by Skia, in memory of its own: read into Java's, a photograph would be megabytes of garbage
	 * for a picture of a few thousand pixels, for every tile of a folder of them.
	 */
	private fun pictureOf(path: Path, side: Int): ImageBitmap? {
		try {
			if (Files.size(path) > LARGEST)
				return null
		} catch (failed: IOException) {
			return null
		}

		// Only Skia's own reading is tried: it throws for a file it cannot map (gone since it was asked of) and for one
		// that is no image it knows, and nothing else here is expected to fail.
		val (pixels, origin) =
			try {
				Data.makeFromFileName(path.toString()).use { data ->
					Codec.makeFromData(data).use { it.readPixels() to it.encodedOrigin }
				}
			} catch (undecodable: RuntimeException) {
				return null
			}

		return pixels.use { scaled(it, origin, side) }
	}

	/**
	 * [pixels], which are a picture as its file holds it, the way up [origin] says it is (a photograph taken with the
	 * camera on its side says so, and is held on its side), scaled down to fit a square of [side].
	 */
	private fun scaled(pixels: Bitmap, origin: EncodedOrigin, side: Int): ImageBitmap {
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
				surface.makeImageSnapshot().toComposeImageBitmap()
			}
		}
	}

	companion object {
		/** The sizes a picture is made at, across: the first at least as large as it is drawn is the one made. */
		private val SIDES = listOf(128, 256, 512, 1024, 2048)

		/** The size a picture drawn [wanted] pixels across is made at. */
		fun sideFor(wanted: Int): Int = SIDES.firstOrNull { it >= wanted } ?: SIDES.last()

		private val ImageBitmap.area: Long get() = width.toLong() * height

		/** The largest file decoded, in bytes: anything bigger is drawn as its icon. */
		private const val LARGEST = 64L * 1024 * 1024

		/** How long a file found to have no picture is taken to have none, in nanoseconds: ten seconds. */
		private const val FORGOTTEN = 10_000_000_000L

		/** How many files with no picture are remembered at once. */
		private const val NONE_KEPT = 16384

		/** The image formats Skia decodes, by their extensions. */
		private val DECODED = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "wbmp")

		/** Whether a file of [name] is one whose picture is made here: an image Skia can decode. */
		fun drawable(name: String): Boolean =
			name.substringAfterLast('.', "").lowercase(Locale.ROOT) in DECODED
	}
}

/** The pictures the gallery draws its tiles with: one store for the whole process, unless one is provided. */
val LocalThumbnails = staticCompositionLocalOf { Thumbnails() }

/**
 * Whether files are drawn by their pictures at all: where not (the user's choice, kept in the preferences), each is
 * its type's icon, and no picture is made or looked for.
 */
val LocalPictured = compositionLocalOf { true }
