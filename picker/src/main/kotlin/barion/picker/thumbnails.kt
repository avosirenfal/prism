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
 * fit a square of [side] pixels, and kept, by the file's path and the time it was last changed, so that a file is
 * decoded once however often its tile is drawn, and again only once it has changed.
 *
 * Decoding a photograph takes tens of milliseconds and the whole image's memory while it lasts, so at most
 * [parallel] are decoded at once, on IO threads; a tile scrolled away before its turn comes is never decoded at all,
 * since asking is cancelled with it. The [kept] pictures are the [capacity] used most recently.
 *
 * What is kept is the one thing here that changes, and it is a cache: asking twice gives the same picture whether or
 * not it was kept in between.
 */
class Thumbnails(
	private val side: Int = 256,
	private val capacity: Int = 1024,
	parallel: Int = 4,
) {
	private data class Key(
		val path: Path,
		val modified: Instant?,
	)

	private val kept =
		object : LinkedHashMap<Key, ImageBitmap>(capacity, 0.75f, true) {
			override fun removeEldestEntry(
				eldest: MutableMap.MutableEntry<Key, ImageBitmap>?,
			): Boolean = size > capacity
		}

	private val decoding: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(parallel)

	/** [path]'s picture, last changed at [modified]; null for a file that is not an image Skia can decode. */
	suspend fun of(path: Path, modified: Instant?): ImageBitmap? {
		if (!drawable(path.name))
			return null

		val key = Key(path, modified)

		return synchronized(kept) { kept[key] }
			?: withContext(decoding) { pictureOf(path) }?.also { picture ->
				synchronized(kept) {
					kept[key] =
						picture
				}
			}
	}

	/** [path] decoded and scaled down to fit the square; null where it cannot be read or decoded. */
	private fun pictureOf(path: Path): ImageBitmap? {
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
