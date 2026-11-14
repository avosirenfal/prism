package prism.browser

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

/**
 * Small pictures of image files, for the gallery. Each is decoded by Skia (the library Compose draws with), scaled to
 * fit a square of one of a few sizes, from 128 to 2048 pixels, the first at least as large as the tile draws it (so a
 * gallery made larger gets sharper pictures, up to one picture as large as the window). Each is kept by the file's
 * path, the time it was last changed and the size, so that a file is decoded once however often its tile is drawn,
 * and again only once it has changed.
 *
 * Decoding a photograph takes tens of milliseconds and the whole image's memory while it lasts, so at most
 * [parallel] are decoded at once, on IO threads; a tile scrolled away before its turn comes is never decoded at all,
 * since asking is cancelled with it. What is kept is the pictures used most recently, up to [budget] pixels in all
 * (four bytes each).
 *
 * What is kept is the one thing here that changes, and it is a cache: asking twice gives the same picture whether or
 * not it was kept in between.
 */
class Thumbnails(
	private val budget: Long = 16L * 1024 * 1024,
	parallel: Int = 4,
) {
	private data class Key(
		val path: Path,
		val modified: Instant?,
		val side: Int,
	)

	/** The pictures kept, the least recently used first, and how many pixels they hold in all. */
	private val kept = LinkedHashMap<Key, ImageBitmap>(64, 0.75f, true)
	private var pixels = 0L

	private val decoding: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(parallel)

	/**
	 * [path]'s picture, last changed at [modified], to be drawn [side] pixels across at most; null for a file that is
	 * not an image Skia can decode.
	 */
	suspend fun of(path: Path, modified: Instant?, side: Int = SIDES[1]): ImageBitmap? {
		if (!drawable(path.name))
			return null

		val key = Key(path, modified, sideFor(side))

		return synchronized(kept) { kept[key] }
			?: withContext(decoding) { pictureOf(path, key.side) }?.also { keep(key, it) }
	}

	private fun keep(key: Key, picture: ImageBitmap) =
		synchronized(kept) {
			kept.put(key, picture)?.let { pixels -= it.area }
			pixels += picture.area

			val oldest = kept.entries.iterator()

			while (pixels > budget && oldest.hasNext()) {
				pixels -= oldest.next().value.area
				oldest.remove()
			}
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

		/** The image formats Skia decodes, by their extensions. */
		private val DECODED = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "wbmp")

		/** Whether a file of [name] has a picture to draw: an image Skia can decode. */
		fun drawable(name: String): Boolean =
			name.substringAfterLast('.', "").lowercase(Locale.ROOT) in DECODED
	}
}

/** The pictures the gallery draws its tiles with: one store for the whole process, unless one is provided. */
val LocalThumbnails = staticCompositionLocalOf { Thumbnails() }
