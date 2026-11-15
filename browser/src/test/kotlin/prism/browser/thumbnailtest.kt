package prism.browser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.v2.runComposeUiTest
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.ThumbnailCache
import prism.desktop.freedesktop.ThumbnailSize
import prism.desktop.freedesktop.XdgPlaces
import prism.preferences.Layout

/**
 * The gallery's pictures: made from real image files, scaled to fit, kept, and not made from what is not an image;
 * and taken from the thumbnails the desktop's programs share, for a file that has one there that is still its
 * picture.
 */
@OptIn(ExperimentalTestApi::class)
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

		/**
		 * [picture] kept in [cache] as the thumbnail of [file] at [size], saying it is that file's as it was last
		 * changed [at]: what a file manager that had shown the file would have left there.
		 */
		fun thumbnail(
			cache: ThumbnailCache,
			file: Path,
			size: ThumbnailSize,
			picture: BufferedImage,
			at: Instant = Files.getLastModifiedTime(file).toInstant(),
		): Path {
			val writer = ImageIO.getImageWritersByFormatName("png").next()

			val says =
				writer.getDefaultImageMetadata(
					ImageTypeSpecifier.createFromRenderedImage(picture),
					null,
				)

			val said =
				listOf(
					"Thumb::URI" to ThumbnailCache.uriOf(file),
					"Thumb::MTime" to "${at.epochSecond}",
				)

			says.mergeTree(
				"javax_imageio_png_1.0",
				IIOMetadataNode("javax_imageio_png_1.0").apply {
					appendChild(
						IIOMetadataNode("tEXt").apply {
							for ((keyword, value) in said)
								appendChild(
									IIOMetadataNode("tEXtEntry").apply {
										setAttribute("keyword", keyword)
										setAttribute("value", value)
									},
								)
						},
					)
				},
			)

			return cache.entry(file, size).also { entry ->
				entry.parent.createDirectories()
				ImageIO.createImageOutputStream(entry.toFile()).use {
					writer.output = it
					writer.write(IIOImage(picture, null, says))
				}
				writer.dispose()
			}
		}

		/** A [width] by [height] picture of one [colour]. */
		fun plain(width: Int, height: Int, colour: Color): BufferedImage =
			BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
				createGraphics().apply {
					color = colour
					fillRect(0, 0, width, height)
					dispose()
				}
			}
	}

	private fun cache() = ThumbnailCache(work.resolve("cache/thumbnails"))

	private fun modifiedOf(file: Path) = Files.getLastModifiedTime(file).toInstant()

	private fun colourAt(picture: ImageBitmap, x: Int, y: Int): Color {
		val pixel = IntArray(1)

		picture.readPixels(pixel, x, y, 1, 1)

		return Color(pixel[0])
	}

	private fun greenish(colour: Color) =
		colour.green > 180 && colour.red < 80 && colour.blue < 80

	@Test
	fun aFileThatIsNoImageHasTheThumbnailTheDesktopSharesWhileItIsStillItsPicture() =
		runBlocking {
			val cache = cache()
			val film = work.resolve("holiday (1).mkv").also { it.writeText("not really a film") }
			val modified = modifiedOf(film)

			thumbnail(cache, film, ThumbnailSize.Large, plain(256, 144, Color.GREEN))

			val thumbnails = Thumbnails(shared = cache)
			val picture = thumbnails.of(film, modified, 200, Files.size(film))!!

			// The thumbnail as it is kept, and kept here too.
			assertEquals(256 to 144, picture.width to picture.height)
			assertTrue(greenish(colourAt(picture, 100, 70)))
			assertSame(picture, thumbnails.of(film, modified, 256))
			// Drawn larger than any there is of it: the largest there is, no larger than it is.
			assertEquals(256, thumbnails.of(film, modified, 900)!!.width)
			// Drawn smaller: made to fit, as an image's own picture is.
			assertEquals(
				128 to 72,
				thumbnails.of(film, modified, 100)!!.let {
					it.width to
						it.height
				},
			)

			// The file changed since the thumbnail was made: it is not its picture, and the film has its icon.
			assertNull(thumbnails.of(film, modified.plusSeconds(5), 200))
			// A file whose time is not known cannot be told from one that changed.
			assertNull(thumbnails.of(film, null, 200))
			// With no shared cache, as before: no picture of what is no image.
			assertNull(Thumbnails(shared = null).of(film, modified, 200))
		}

	@Test
	fun anImageIsTakenFromTheSharedCacheOnlyWhereItsThumbnailIsAsLargeAsIsWanted() =
		runBlocking {
			val cache = cache()
			val photo = picture(work, "photo.png", 2000, 1500)
			val modified = modifiedOf(photo)

			thumbnail(cache, photo, ThumbnailSize.XLarge, plain(512, 384, Color.GREEN))

			val thumbnails = Thumbnails(shared = cache)

			// Wanted no larger than the thumbnail kept: that is the picture, and the photograph is not decoded.
			val small = thumbnails.of(photo, modified, 400)!!

			assertEquals(512 to 384, small.width to small.height)
			assertTrue(greenish(colourAt(small, 250, 190)))

			// Wanted larger: made from the photograph itself, as sharp as it is drawn.
			val large = thumbnails.of(photo, modified, 1000)!!

			assertEquals(1024 to 768, large.width to large.height)
			assertTrue(!greenish(colourAt(large, 500, 380)))
			// A stale thumbnail is not the photograph's: made from the photograph.
			assertTrue(
				!greenish(colourAt(thumbnails.of(photo, modified.plusSeconds(9), 400)!!, 250, 190)),
			)
		}

	@Test
	fun thatAFileHasNoPictureIsRememberedForAWhileAndThenLookedAtAgain() =
		runBlocking {
			val cache = cache()
			val film = work.resolve("clip.mp4").also { it.writeText("not really a film") }
			val modified = modifiedOf(film)
			var now = 0L
			val thumbnails = Thumbnails(shared = cache, now = { now })

			assertNull(thumbnails.of(film, modified, 200))

			// Another program makes its thumbnail: not seen at once, since the file was only just found to have none.
			thumbnail(cache, film, ThumbnailSize.Normal, plain(128, 72, Color.GREEN))
			now += 9_000_000_000L
			assertNull(thumbnails.of(film, modified, 200))

			// A while later it is looked for again, and found.
			now += 2_000_000_000L
			assertEquals(128, thumbnails.of(film, modified, 200)?.width)

			// A file that changes is another thing to look for, at once: its thumbnail of before is not its picture,
			// and one made of it as it now is, is.
			val changed = modified.plusSeconds(60)

			assertNull(thumbnails.of(film, changed, 200))
			thumbnail(cache, film, ThumbnailSize.Normal, plain(128, 72, Color.GREEN), changed)
			now += 11_000_000_000L
			assertEquals(128, thumbnails.of(film, changed, 200)?.width)
		}

	/** A picture that could be a frame of a film: a sky, a sun and ground, in [sky] and [ground]. */
	private fun frame(width: Int, height: Int, sky: Color, ground: Color): BufferedImage =
		BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
			createGraphics().apply {
				paint = GradientPaint(0f, 0f, sky, 0f, height * 0.7f, Color(255, 214, 170))
				fillRect(0, 0, width, height)
				color = Color(255, 244, 214)
				fillOval(width * 6 / 10, height * 3 / 10, height / 4, height / 4)
				color = ground
				fillPolygon(
					intArrayOf(0, width / 3, width * 2 / 3, width, width, 0),
					intArrayOf(
						height * 7 / 10,
						height / 2,
						height * 3 / 4,
						height * 6 / 10,
						height,
						height,
					),
					6,
				)
				dispose()
			}
		}

	/** A picture that could be a page of a document: white, with lines of grey for its words. */
	private fun page(width: Int, height: Int): BufferedImage =
		BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
			createGraphics().apply {
				color = Color.WHITE
				fillRect(0, 0, width, height)
				color = Color(60, 70, 90)
				fillRect(width / 8, height / 10, width / 2, height / 28)
				color = Color(170, 176, 188)
				for (line in 0 until 14)
					fillRect(
						width / 8,
						height * (20 + line * 5) / 100,
						width * (if (line % 4 == 3) 45 else 74) / 100,
						height / 70,
					)
				dispose()
			}
		}

	/** A folder of films and a document, some with thumbnails in [cache], shown as a gallery. */
	private fun shown(cache: ThumbnailCache): BrowserState {
		val folder = work.resolve("home/Videos").createDirectories()

		fun file(name: String) = folder.resolve(name).also { it.writeBytes(ByteArray(2048)) }

		thumbnail(
			cache,
			file("Lisbon, day one.mkv"),
			ThumbnailSize.XLarge,
			frame(512, 288, Color(70, 130, 220), Color(40, 90, 70)),
		)
		thumbnail(
			cache,
			file("Sunset timelapse.mp4"),
			ThumbnailSize.XLarge,
			frame(512, 288, Color(190, 80, 110), Color(50, 40, 70)),
		)
		thumbnail(
			cache,
			file("Phone clip (vertical).mov"),
			ThumbnailSize.XLarge,
			frame(288, 512, Color(90, 170, 200), Color(70, 110, 60)),
		)
		thumbnail(cache, file("Itinerary.pdf"), ThumbnailSize.XLarge, page(362, 512))
		// One whose thumbnail was made of the file as it was before, and one that has none.
		thumbnail(
			cache,
			file("Edited since.mkv"),
			ThumbnailSize.XLarge,
			plain(512, 288, Color.RED),
			Instant.EPOCH,
		)
		file("Never shown.webm")
		picture(folder, "Cover.png", 640, 360)

		return step(
			pickerOf(
				folder,
				Choosing.OneFile(),
				home = work.resolve("home"),
				places = XdgPlaces(),
			),
			BrowserEvent.Viewed(Layout.Gallery),
		).state
	}

	@Test
	fun theGalleryShowsAFilmsSharedThumbnailAndTheIconOfOneWhoseThumbnailIsStale() =
		runComposeUiTest {
			val cache = cache()
			val state = shown(cache)

			setContent {
				CompositionLocalProvider(LocalThumbnails provides Thumbnails(shared = cache)) {
					BrowserTheme { FileBrowser(state) }
				}
			}

			fun pictured(name: String) =
				onAllNodes(hasTestTag("picture:$name")).fetchSemanticsNodes().isNotEmpty()

			waitUntil(timeoutMillis = 10_000) {
				pictured("Lisbon, day one.mkv") && pictured("Itinerary.pdf") && pictured("Cover.png")
			}
			waitForIdle()
			assertTrue(pictured("Sunset timelapse.mp4"))
			assertTrue(pictured("Phone clip (vertical).mov"))
			assertTrue(!pictured("Edited since.mkv"), "a stale thumbnail is shown")
			assertTrue(!pictured("Never shown.webm"))
		}

	@Test
	fun theGalleryIsDrawnWithSharedThumbnails() =
		runBlocking {
			val cache = cache()
			val state = shown(cache)
			val thumbnails = Thumbnails(shared = cache)

			// Made before it is drawn, at every size a tile could ask for: a headless picture is of two frames, and
			// does not wait for an IO thread.
			for (row in state.rows.list)
				for (side in listOf(128, 256, 512, 1024, 2048))
					row.path?.let { thumbnails.of(it, row.modified, side, row.size?.value) }

			for ((name, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png =
					renderedPng(1100, 680, palette, scale = 2f) {
						CompositionLocalProvider(LocalThumbnails provides thumbnails) {
							FileBrowser(state)
						}
					}

				assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
				System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
					Path(dir)
						.createDirectories()
						.resolve("gallery-shared-thumbnails-$name.png")
						.writeBytes(png)
				}
			}
		}

	@Test
	fun anImageIsScaledToFitAndKept() =
		runBlocking {
			val thumbnails = Thumbnails(shared = null)
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
			val small = Thumbnails(shared = null).of(icon, Instant.EPOCH, 256)!!

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

			fun reddish(colour: Color) = colour.red > 180 && colour.blue < 80

			fun bluish(colour: Color) = colour.blue > 180 && colour.red < 80

			val upright = Thumbnails(shared = null).of(plain, Instant.EPOCH, 256)!!
			val onItsSide = Thumbnails(shared = null).of(turned, Instant.EPOCH, 256)!!

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
					Thumbnails(shared = null).of(
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
			val thumbnails = Thumbnails(shared = null)

			work.resolve("notes.txt").writeText("not a picture")
			work.resolve("fake.png").writeText("not a picture either")

			assertNull(thumbnails.of(work.resolve("notes.txt"), null))
			assertNull(thumbnails.of(work.resolve("fake.png"), null))
			assertNull(thumbnails.of(work.resolve("gone.png"), null))
		}
}
