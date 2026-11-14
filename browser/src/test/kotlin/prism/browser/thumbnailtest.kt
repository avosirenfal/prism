package prism.browser

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The gallery's pictures: made from real image files, scaled to fit, kept, and not made from what is not an image. */
class ThumbnailTest {
	@TempDir
	lateinit var work: Path

	companion object {
		/** A [width] by [height] picture written as [name], in the format its extension names: a gradient, so it is not blank. */
		fun picture(folder: Path, name: String, width: Int, height: Int): Path {
			val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

			for (x in 0 until width)
				for (y in 0 until height)
					image.setRGB(x, y, Color(x * 255 / width, y * 255 / height, 160).rgb)

			return folder.resolve(name).also {
				ImageIO.write(image, name.substringAfterLast('.'), it.toFile())
			}
		}
	}

	@Test
	fun anImageIsScaledToFitAndKept() =
		runBlocking {
			val thumbnails = Thumbnails()
			val photo = picture(work, "photo.png", 400, 300)
			val modified = Files.getLastModifiedTime(photo).toInstant()
			val small = thumbnails.of(photo, modified, 200)!!

			// Drawn 200 pixels across, made at the first size at least that large.
			assertEquals(256, small.width)
			assertEquals(192, small.height)
			// Asked again: the same picture, not decoded again.
			assertSame(small, thumbnails.of(photo, modified, 256))
			// Changed since: decoded anew. Drawn larger: made larger.
			assertNotSame(small, thumbnails.of(photo, modified.plusSeconds(1), 256))
			assertEquals(128, thumbnails.of(photo, modified, 100)!!.width)
		}

	@Test
	fun aSmallImageIsNotScaledUp() =
		runBlocking {
			val icon = picture(work, "icon.jpg", 32, 24)
			val small = Thumbnails().of(icon, Instant.EPOCH, 256)!!

			assertEquals(32, small.width)
			assertEquals(24, small.height)
		}

	@Test
	fun aPhotographTakenOnItsSideIsMadeTheWayUpItSaysItIs() =
		runBlocking {
			// A JPEG, red on its left and blue on its right, that says (in its EXIF) that its left is its top: a camera
			// held on its side writes one so.
			val image = BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB)

			for (x in 0 until 80)
				for (y in 0 until 40)
					image.setRGB(x, y, (if (x < 40) Color.RED else Color.BLUE).rgb)

			val plain = work.resolve("plain.jpg").also { ImageIO.write(image, "jpg", it.toFile()) }
			val bytes = Files.readAllBytes(plain)

			// EXIF: a TIFF of one entry, the orientation (tag 0x0112), which is 6 for "the left is the top".
			val tiff =
				byteArrayOf(
					'I'.code.toByte(),
					'I'.code.toByte(),
					42,
					0,
					8,
					0,
					0,
					0,
					1,
					0,
					0x12,
					0x01,
					3,
					0,
					1,
					0,
					0,
					0,
					6,
					0,
					0,
					0,
					0,
					0,
					0,
					0,
				)

			val exif = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
			val length = exif.size + 2
			val app1 =
				byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) +
					exif

			val turned =
				work.resolve("turned.jpg").also {
					Files.write(it, bytes.copyOfRange(0, 2) + app1 + bytes.copyOfRange(2, bytes.size))
				}

			fun colourAt(
				picture: androidx.compose.ui.graphics.ImageBitmap,
				x: Int,
				y: Int,
			): Color {
				val pixel = IntArray(1)

				picture.readPixels(pixel, x, y, 1, 1)

				return Color(pixel[0])
			}

			fun reddish(colour: Color) = colour.red > 180 && colour.blue < 80

			fun bluish(colour: Color) = colour.blue > 180 && colour.red < 80

			val upright = Thumbnails().of(plain, Instant.EPOCH, 256)!!
			val onItsSide = Thumbnails().of(turned, Instant.EPOCH, 256)!!

			assertEquals(80 to 40, upright.width to upright.height)
			assertTrue(reddish(colourAt(upright, 10, 20)) && bluish(colourAt(upright, 70, 20)))
			// Turned: as tall as it was wide, and what was its left is its top.
			assertEquals(40 to 80, onItsSide.width to onItsSide.height)
			assertTrue(reddish(colourAt(onItsSide, 20, 10)), "${colourAt(onItsSide, 20, 10)}")
			assertTrue(bluish(colourAt(onItsSide, 20, 70)), "${colourAt(onItsSide, 20, 70)}")
		}

	@Test
	fun everyFormatSkiaReadsIsMade() =
		runBlocking {
			for (format in listOf("png", "jpg", "gif", "bmp")) {
				val made =
					Thumbnails().of(
						picture(work, "picture.$format", 300, 200),
						Instant.EPOCH,
						128,
					)

				assertEquals(128 to 85, made?.let { it.width to it.height }, format)
			}
		}

	@Test
	fun whatIsNotAnImageHasNoPicture() =
		runBlocking {
			val thumbnails = Thumbnails()

			work.resolve("notes.txt").writeText("not a picture")
			work.resolve("fake.png").writeText("not a picture either")

			assertNull(thumbnails.of(work.resolve("notes.txt"), null))
			assertNull(thumbnails.of(work.resolve("fake.png"), null))
			assertNull(thumbnails.of(work.resolve("gone.png"), null))
		}
}
