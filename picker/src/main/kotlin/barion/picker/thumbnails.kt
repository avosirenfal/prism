package barion.picker

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
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * Small pictures of image files, for the gallery. Each is decoded by Skia (the library Compose draws with), scaled to
 * fit a square of one of a few sizes (the first at least as large as the tile draws it, so a gallery made larger gets
 * sharper pictures), and kept, by the file's path, the time it was last changed and the size, so that a file is
 * decoded once however often its tile is drawn, and again only once it has changed.
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

	/** [path] decoded and scaled down to fit a square of [side]; null where it cannot be read or decoded. */
	private fun pictureOf(path: Path, side: Int): ImageBitmap? {
		val bytes =
			try {
				if (Files.size(path) > LARGEST)
					return null

				Files.readAllBytes(path)
			} catch (failed: IOException) {
				return null
			}

		val image =
			try {
				Image.makeFromEncoded(bytes)
			} catch (undecodable: IllegalArgumentException) {
				return null
			}

		return image.use {
			val scale = minOf(side.toFloat() / max(it.width, it.height), 1f)
			val width = (it.width * scale).roundToInt().coerceAtLeast(1)
			val height = (it.height * scale).roundToInt().coerceAtLeast(1)

			Surface.makeRasterN32Premul(width, height).use { surface ->
				surface.canvas.drawImageRect(
					it,
					Rect.makeWH(it.width.toFloat(), it.height.toFloat()),
					Rect.makeWH(width.toFloat(), height.toFloat()),
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
		private val SIDES = listOf(128, 256, 512)

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
