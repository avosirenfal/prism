package barion.picker

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
			val thumbnails = Thumbnails(side = 256)
			val photo = picture(work, "photo.png", 400, 300)
			val modified = Files.getLastModifiedTime(photo).toInstant()
			val small = thumbnails.of(photo, modified)!!

			assertEquals(256, small.width)
			assertEquals(192, small.height)
			// Asked again: the same picture, not decoded again.
			assertSame(small, thumbnails.of(photo, modified))
			// Changed since: decoded anew.
			assertNotSame(small, thumbnails.of(photo, modified.plusSeconds(1)))
		}

	@Test
	fun aSmallImageIsNotScaledUp() =
		runBlocking {
			val icon = picture(work, "icon.jpg", 32, 24)
			val small = Thumbnails(side = 256).of(icon, Instant.EPOCH)!!

			assertEquals(32, small.width)
			assertEquals(24, small.height)
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
