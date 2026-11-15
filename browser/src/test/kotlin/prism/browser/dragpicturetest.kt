package prism.browser

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.math.roundToInt
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The picture a drag shows under the pointer, drawn as the window draws it: at a density of its own (the window's), on
 * a canvas clear to begin with. The icon of what is dragged, and for several, a badge at the top right counting them.
 * With `-Pbrowser.screenshots=DIR` each picture drawn is written there to look at.
 */
@OptIn(InternalComposeUiApi::class)
class DragPictureTest {
	init {
		// Text is laid out by whatever implementation is registered for the process, as a window registers Skia's.
		registerSkikoComposeImplementation()
	}

	private val density = Density(2f)
	private val measurer =
		TextMeasurer(createFontFamilyResolver(), LocaleList("en"), density, LayoutDirection.Ltr)

	/** [carrying]'s picture in [palette], drawn into pixels of its own at [density]. */
	private fun drawn(
		carrying: Carrying,
		palette: Palette = Palette.Light,
		name: String? = null,
	): ImageBitmap {
		val picture = dragPictureOf(carrying, palette, measurer)
		val width = (picture.size.width.value * density.density).roundToInt()
		val height = (picture.size.height.value * density.density).roundToInt()
		val image = ImageBitmap(width, height)

		CanvasDrawScope().draw(
			density,
			LayoutDirection.Ltr,
			Canvas(image),
			Size(width.toFloat(), height.toFloat()),
		) { picture.draw(this) }

		name?.let { shot ->
			System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
				Path(dir)
					.createDirectories()
					.resolve("drag-$shot.png")
					.writeBytes(
						Image
							.makeFromBitmap(image.asSkiaBitmap())
							.encodeToData(EncodedImageFormat.PNG)!!
							.bytes,
					)
			}
		}

		return image
	}

	/** Where the badge's middle is, at the picture's top right corner: half of it in from each edge. */
	private fun ImageBitmap.badgeMiddle(): Pair<Int, Int> {
		val badge = (18 * density.density).roundToInt()

		return width - badge / 2 to badge / 2
	}

	private fun ImageBitmap.at(spot: Pair<Int, Int>): Color =
		toPixelMap()[spot.first, spot.second]

	@Test
	fun oneFileShowsItsIconAloneOnNothing() {
		val image = drawn(Carrying(Glyph.File, Category.Text, 1), name = "one")

		// The page's middle is drawn; the corner the badge would be in, and the picture's own corners, are clear.
		assertEquals(1f, image.at(image.width * 2 / 5 to image.height * 3 / 5).alpha, 0.01f)
		assertEquals(0f, image.at(image.badgeMiddle()).alpha)
		assertEquals(0f, image.at(0 to 0).alpha)
		assertEquals(0f, image.at(image.width - 1 to image.height - 1).alpha)
	}

	@Test
	fun severalShowTheIconOfTheOneTakenAndHowManyOnABadge() {
		for ((name, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
			val image = drawn(Carrying(Glyph.Folder, Category.Folder, 3), palette, "three-$name")
			val (x, y) = image.badgeMiddle()
			val badge = (18 * density.density).roundToInt()
			// Beside the figure, in the badge: its colour.
			val edge = image.at(x - badge / 2 + 3 to y)

			assertEquals(palette.accent, edge.copy(alpha = 1f), name)
			// The folder, below and to the left of it.
			assertEquals(
				palette.folderFront,
				image.at(image.width * 2 / 5 to image.height * 3 / 4),
				name,
			)
		}
	}

	@Test
	fun aCountTooLongForACircleIsAPillAsWideAsItIs() {
		val three = drawn(Carrying(Glyph.File, Category.Other, 3))
		val many = drawn(Carrying(Glyph.File, Category.Other, 12_000), name = "many")
		val badge = (18 * density.density).roundToInt()
		// Left of where a circle's badge ends, at its height: a pill for "999+" reaches there, a circle does not.
		val spot = three.width - badge - 6 to badge / 2

		assertEquals(0f, three.at(spot).alpha)
		assertTrue(many.at(spot).alpha > 0.99f, "${many.at(spot)}")
	}
}
