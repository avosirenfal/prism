package barion.picker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat

/**
 * [state] drawn headless at [width] by [height] pixels in [palette], as a PNG: what the screenshot test and
 * `barion snapshot` look at, so the picker can be seen without a display. [scale] is the display's density (2 for
 * a picture a person will look at on a high-density screen).
 */
fun renderedPng(
	state: PickerState,
	width: Int,
	height: Int,
	palette: Palette = Palette.Light,
	scale: Float = 1f,
): ByteArray {
	val scene =
		ImageComposeScene((width * scale).toInt(), (height * scale).toInt(), Density(scale)) {
			PickerTheme(palette) { FilePicker(state) }
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
