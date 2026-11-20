package prism.thumbnails

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * What decodes pictures in this module's tests: the JDK's own readers, in place of Skia (which the browser gives a
 * running prism). It reads PNGs and JPEGs, scales down and never up, and turns a picture a quarter round where it is
 * told the picture is held on its side, which is all the tests ask of one.
 */
object ImagePictures : Pictures {
	override val extensions = setOf("png", "jpg", "jpeg", "bmp", "gif")

	override fun extentOf(file: Path): Extent? =
		try {
			ImageIO.createImageInputStream(file.toFile())?.use { stream ->
				ImageIO.getImageReaders(stream).asSequence().firstOrNull()?.let { reader ->
					try {
						reader.input = stream
						Extent(reader.getWidth(0), reader.getHeight(0))
					} finally {
						reader.dispose()
					}
				}
			}
		} catch (failed: IOException) {
			null
		}

	override fun png(encoded: Encoded, side: Int, orientation: Int?): Picture? {
		val read =
			try {
				when (encoded) {
					is Encoded.InFile -> ImageIO.read(encoded.file.toFile())
					is Encoded.Bytes -> ImageIO.read(encoded.bytes.inputStream())
				}
			} catch (failed: IOException) {
				null
			} ?: return null

		val turned = orientation != null && orientation >= 5
		val across = if (turned) read.height else read.width
		val down = if (turned) read.width else read.height
		val scale = minOf(side.toDouble() / maxOf(across, down), 1.0)
		val width = (across * scale).toInt().coerceAtLeast(1)
		val height = (down * scale).toInt().coerceAtLeast(1)
		val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

		scaled.createGraphics().apply {
			setRenderingHint(
				RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR,
			)
			if (turned) {
				translate(width.toDouble(), 0.0)
				rotate(Math.PI / 2)
				drawImage(read, 0, 0, height, width, null)
			} else {
				drawImage(read, 0, 0, width, height, null)
			}
			dispose()
		}

		return Picture(encodedAs(scaled, "png"), Extent(width, height))
	}

	/** A [width] by [height] picture, a gradient so that it is not blank, in the format [format] names. */
	fun sample(width: Int, height: Int, format: String = "png"): ByteArray {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

		for (x in 0 until width)
			for (y in 0 until height)
				image.setRGB(x, y, Color(x * 255 / width, y * 255 / height, 160).rgb)

		return encodedAs(image, format)
	}

	/** [sample] written as [name] in [folder], in the format its extension names. */
	fun sampleFile(folder: Path, name: String, width: Int, height: Int): Path =
		folder.resolve(name).also {
			Files.createDirectories(folder)
			Files.write(it, sample(width, height, name.substringAfterLast('.')))
		}

	private fun encodedAs(image: BufferedImage, format: String): ByteArray =
		ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
}
