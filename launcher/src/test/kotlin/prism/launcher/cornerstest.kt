package prism.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.Palette
import prism.browser.renderedPng
import prism.preferences.LauncherPreferences
import prism.window.Backdrop

/**
 * The launcher's corners: round only where they are asked for and its window is clear behind what it draws; and, drawn
 * round, clear at the very corner, with the line around it the same line all the way round the arc, and nothing of
 * what is inside it showing past it.
 */
class CornersTest {
	private val width = 480
	private val height = 320

	/** The launcher drawn headless at [scale], its corners as [corners] say: its pixels, with their alpha. */
	private fun drawn(
		corners: Corners,
		palette: Palette,
		scale: Float,
		name: String,
	): BufferedImage {
		val png =
			renderedPng(width, height, palette, scale) {
				LauncherPane(LauncherState(applications = Programs.all), corners = corners)
			}

		System.getProperty("launcher.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
			Path
				.of(dir)
				.also { it.createDirectories() }
				.resolve("$name.png")
				.toFile()
				.writeBytes(png)
		}

		return ImageIO.read(ByteArrayInputStream(png))
	}

	private fun BufferedImage.alphaAt(x: Int, y: Int): Int = getRGB(x, y) ushr 24

	/** How far the colour at ([x], [y]) is from [colour], at the channel where it is farthest. */
	private fun BufferedImage.farFrom(x: Int, y: Int, colour: Color): Int {
		val here = getRGB(x, y)
		val there = colour.toArgb()

		return listOf(16, 8, 0).maxOf {
			abs(((here shr it) and 0xFF) - ((there shr it) and 0xFF))
		}
	}

	@Test
	fun cornersAreRoundOnlyWhereTheyAreAskedForAndTheWindowIsClear() {
		val round = LauncherPreferences()
		val square = LauncherPreferences(rounded = false)

		assertTrue(round.rounded)
		assertEquals(Corners.Round, cornersOf(round, Backdrop.Clear))
		// An opaque window's corners are its own to paint: left unpainted, they are black, so they are square.
		assertEquals(Corners.Square, cornersOf(round, Backdrop.Opaque))
		assertEquals(Corners.Square, cornersOf(square, Backdrop.Clear))
		assertEquals(Corners.Square, cornersOf(square, Backdrop.Opaque))
	}

	@Test
	fun roundCornersAreClearAndTheLineFollowsTheArcWithNothingShowingPastIt() {
		for (palette in listOf(Palette.Light, Palette.Dark))
			for (scale in listOf(1f, 2f)) {
				val tone = if (palette.isDark) "dark" else "light"
				val image =
					drawn(Corners.Round, palette, scale, "corners-round-$tone-${scale.toInt()}x")
				val wide = image.width
				val high = image.height
				val radius = LauncherDims.corner.value * scale
				val edge = LauncherDims.edge.value * scale
				val said = "$tone at ${scale.toInt()}x"

				// The four corners themselves are clear, and the middle of each side is the line, whole.
				for ((x, y) in listOf(0 to 0, wide - 1 to 0, 0 to high - 1, wide - 1 to high - 1))
					assertEquals(0, image.alphaAt(x, y), "$said: the corner at $x,$y")
				for ((x, y) in listOf(
					wide / 2 to 0,
					0 to high / 2,
					wide - 1 to high / 2,
					wide / 2 to high - 1,
				)) {
					assertEquals(255, image.alphaAt(x, y), "$said: the side at $x,$y")
					assertTrue(image.farFrom(x, y, palette.border) <= 2, "$said: the line at $x,$y")
				}

				// In each corner's square, by how far a pixel's middle is from the middle of the corner's arc:
				// outside the arc, clear; well inside the line, the launcher's own; and anything not whole (the
				// arc's edge, as it fades) is the line's colour and no other, so nothing inside shows past the line.
				for (right in listOf(false, true))
					for (bottom in listOf(false, true))
						for (dx in 0 until radius.roundToInt())
							for (dy in 0 until radius.roundToInt()) {
								val x =
									if (right)
										wide - 1 - dx
									else
										dx

								val y =
									if (bottom)
										high - 1 - dy
									else
										dy
								val from = hypot(radius - dx - 0.5, radius - dy - 0.5)
								val alpha = image.alphaAt(x, y)
								val where = "$said: $x,$y, ${"%.2f".format(from)} from the arc's middle"

								when {
									from > radius + 1 -> assertEquals(0, alpha, where)
									from < radius - edge - 1 -> assertEquals(255, alpha, where)
								}
								// (A pixel all but clear has its colour only to within what dividing by so little
								// alpha leaves of it, and is not looked at.)
								if (alpha in 48..254)
									assertTrue(
										image.farFrom(x, y, palette.border) <= 24,
										"$where: half clear, and not the line's colour " +
											"(${Integer.toHexString(image.getRGB(x, y))})",
									)
								// On the line itself, where it is whole: the line's colour.
								if (edge >= 2 && abs(from - (radius - edge / 2)) < 0.3)
									assertTrue(
										alpha == 255 && image.farFrom(x, y, palette.border) <= 40,
										"$where: on the line (${Integer.toHexString(image.getRGB(x, y))})",
									)
							}
			}
	}

	@Test
	fun squareCornersAreTheLineToTheVeryCorner() {
		for (palette in listOf(Palette.Light, Palette.Dark))
			for (scale in listOf(1f, 2f)) {
				val tone = if (palette.isDark) "dark" else "light"
				val image =
					drawn(Corners.Square, palette, scale, "corners-square-$tone-${scale.toInt()}x")
				val edge = (LauncherDims.edge.value * scale).roundToInt()

				for (x in listOf(0, image.width - 1))
					for (y in listOf(0, image.height - 1)) {
						assertEquals(255, image.alphaAt(x, y), "$tone: $x,$y")
						assertTrue(image.farFrom(x, y, palette.border) <= 2, "$tone: $x,$y")
					}
				// And a line's width in, the launcher's own.
				assertTrue(
					image.farFrom(edge + 1, image.height / 2, palette.border) > 2 ||
						palette.border == palette.overlay,
					"$tone: inside the line",
				)
			}
	}
}
