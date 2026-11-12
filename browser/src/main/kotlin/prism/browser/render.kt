package prism.browser

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * [state] drawn headless at [width] by [height] pixels in [palette], as a PNG: what the screenshot test and
 * `prism snapshot` look at, so the picker can be seen without a display. [scale] is the display's density (2 for
 * a picture a person will look at on a high-density screen).
 *
 * It is drawn on a thread of its own ([Drawing]), and the scene's effects run there too: an effect that scrolls the
 * listing then runs between frames, as in a window, and never while another thread measures the scene.
 */
fun renderedPng(
	state: BrowserState,
	width: Int,
	height: Int,
	palette: Palette = Palette.Light,
	scale: Float = 1f,
): ByteArray = renderedPng(width, height, palette, scale) { FileBrowser(state) }

/** [content] drawn headless, as [renderedPng] draws a browser: what the explorer's windows are looked at by. */
fun renderedPng(
	width: Int,
	height: Int,
	palette: Palette = Palette.Light,
	scale: Float = 1f,
	content: @Composable () -> Unit,
): ByteArray =
	if (Thread.currentThread().name == Drawing.NAME)
		drawn(width, height, palette, scale, content)
	else
		runBlocking(Drawing.dispatcher) { drawn(width, height, palette, scale, content) }

/** The thread headless drawing is done on, one at a time, as a window's is. */
private object Drawing {
	const val NAME = "prism headless drawing"

	val dispatcher =
		Executors
			.newSingleThreadExecutor { task -> Thread(task, NAME).apply { isDaemon = true } }
			.asCoroutineDispatcher()
}

private fun drawn(
	width: Int,
	height: Int,
	palette: Palette,
	scale: Float,
	content: @Composable () -> Unit,
): ByteArray {
	val scene =
		ImageComposeScene(
			(width * scale).toInt(),
			(height * scale).toInt(),
			Density(scale),
			coroutineContext = Drawing.dispatcher,
		) {
			BrowserTheme(palette, content)
		}

	try {
		// Two frames: the first lays out and the second draws what the first measured (the lazy list's rows).
		scene.render(0L)

		val image = scene.render(16_000_000L)

		return requireNotNull(image.encodeToData(EncodedImageFormat.PNG)) {
			"skia encoded no PNG"
		}.bytes
	} finally {
		scene.close()
	}
}
