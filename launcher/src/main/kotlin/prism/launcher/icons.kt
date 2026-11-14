package prism.launcher

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLength
import org.jetbrains.skia.svg.SVGLengthUnit
import prism.desktop.Icons

/*
 * The pictures applications are known by, as the launcher draws them. The desktop says which file an icon's name is
 * ([Icons]); here it is read and drawn at the size asked for: a PNG decoded and scaled, an SVG drawn at that size, both
 * by Skia, the library Compose draws with. Each is made once and kept, by its name and size, for as long as the process
 * runs (a few hundred small pictures), so the launcher opened again draws them at once; that an icon has no picture is
 * kept too, so it is not looked for again.
 */
class IconImages(
	private val icons: Icons,
	parallel: Int = 4,
) {
	private data class Key(
		val name: String,
		val pixels: Int,
	)

	private val kept = ConcurrentHashMap<Key, Optional<ImageBitmap>>()
	private val drawing: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(parallel)

	/** The picture of the icon [name], [pixels] across, where it is made already: null where it is not, or there is none. */
	fun kept(name: String, pixels: Int): ImageBitmap? = kept[Key(name, pixels)]?.orElse(null)

	/** The picture of the icon [name], [pixels] across; null where it has none that can be drawn. */
	suspend fun of(name: String, pixels: Int): ImageBitmap? {
		val key = Key(name, pixels)

		return (
			kept[key] ?: withContext(drawing) {
				kept.computeIfAbsent(key) {
					Optional.ofNullable(
						icons.find(name, pixels, 1)?.let { file -> drawn(file, pixels) },
					)
				}
			}
		).orElse(null)
	}

	/** Every picture forgotten: the icon theme changed, or what is installed did. */
	fun forget() = kept.clear()

	companion object {
		/** The largest icon file read, in bytes: anything bigger is not an icon. */
		private const val LARGEST = 16L * 1024 * 1024

		/** [file] drawn to fit a square [pixels] across; null where it cannot be read, or is not a picture Skia draws. */
		fun drawn(file: Path, pixels: Int): ImageBitmap? {
			val bytes =
				try {
					if (Files.size(file) > LARGEST)
						return null

					Files.readAllBytes(file)
				} catch (failed: IOException) {
					return null
				}

			return try {
				if (file.extension.equals("svg", ignoreCase = true))
					vector(bytes, pixels)
				else
					raster(bytes, pixels)
			} catch (undrawable: RuntimeException) {
				null
			}
		}

		private fun raster(bytes: ByteArray, pixels: Int): ImageBitmap =
			Image.makeFromEncoded(bytes).use { image ->
				val scale = pixels.toFloat() / max(image.width, image.height)
				val width = (image.width * scale).roundToInt().coerceAtLeast(1)
				val height = (image.height * scale).roundToInt().coerceAtLeast(1)

				Surface.makeRasterN32Premul(width, height).use { surface ->
					surface.canvas.drawImageRect(
						image,
						Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
						Rect.makeWH(width.toFloat(), height.toFloat()),
						FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
						null,
						true,
					)
					surface.makeImageSnapshot().toComposeImageBitmap()
				}
			}

		/** An SVG drawn [pixels] across: its own size made its view box, where it has none, and the whole scaled to fit. */
		private fun vector(bytes: ByteArray, pixels: Int): ImageBitmap? =
			Data.makeFromBytes(bytes).use { data ->
				SVGDOM(data).use { dom ->
					val root = dom.root ?: return null
					val side = pixels.toFloat()

					if (root.viewBox == null) {
						val width = root.width.takeIf { it.unit != SVGLengthUnit.PERCENTAGE }?.value
						val height = root.height.takeIf { it.unit != SVGLengthUnit.PERCENTAGE }?.value

						if (width != null && height != null && width > 0 && height > 0)
							root.viewBox = Rect.makeWH(width, height)
					}
					root.width = SVGLength(side, SVGLengthUnit.PX)
					root.height = SVGLength(side, SVGLengthUnit.PX)
					dom.setContainerSize(side, side)

					Surface.makeRasterN32Premul(pixels, pixels).use { surface ->
						dom.render(surface.canvas)
						surface.makeImageSnapshot().toComposeImageBitmap()
					}
				}
			}
	}
}

/** The pictures the launcher draws its applications with: none, unless whoever shows it provides the desktop's. */
val LocalIconImages = staticCompositionLocalOf { IconImages(Icons.NONE) }
