package prism.thumbnails

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The pictures files carry of themselves (embedded.kt), read from files laid out as each camera's are: a JPEG's Exif
 * thumbnail, the previews of raw files that are TIFFs in each of the ways they hold one, Fuji's, and files that say
 * things that are not so.
 */
class EmbeddedTest {
	@TempDir
	lateinit var work: Path

	/** One entry of a directory: its tag, its type, how many values, and the values, or where they are. */
	private data class Tagged(
		val tag: Int,
		val type: Int,
		val count: Long,
		val value: Long,
	)

	private fun short(tag: Int, value: Int) = Tagged(tag, 3, 1, value.toLong())

	private fun long(tag: Int, value: Long) = Tagged(tag, 4, 1, value)

	/** A TIFF being laid out: what is put in it, and where each thing came to lie. */
	private class Laying(
		val order: ByteOrder,
		magic: Int,
	) {
		val out = ByteBuffer.allocate(1 shl 20).order(order)

		init {
			out.put(
				(
					if (order == ByteOrder.LITTLE_ENDIAN)
						"II"
					else
						"MM"
				).toByteArray(),
			)
			out.putShort(magic.toShort())
			// Where the first directory is: said once it is laid.
			out.putInt(0)
		}

		/** [bytes] put in, as they are: where they lie. */
		fun blob(bytes: ByteArray): Long = out.position().toLong().also { out.put(bytes) }

		/** [values] put in as longs: where they lie. */
		fun longs(vararg values: Long): Long =
			out.position().toLong().also { values.forEach { out.putInt(it.toInt()) } }

		/** A directory of [entries], followed by the one at [next] (0: none): where it lies. */
		fun directory(entries: List<Tagged>, next: Long = 0): Long {
			val at = out.position().toLong()

			out.putShort(entries.size.toShort())
			for (entry in entries) {
				out.putShort(entry.tag.toShort())
				out.putShort(entry.type.toShort())
				out.putInt(entry.count.toInt())
				// A short lies in the first two of the four bytes, whichever way round the numbers are.
				if (entry.type == 3 && entry.count == 1L) {
					out.putShort(entry.value.toShort())
					out.putShort(0)
				} else {
					out.putInt(entry.value.toInt())
				}
			}
			out.putInt(next.toInt())

			return at
		}

		fun bytes(first: Long): ByteArray {
			out.putInt(4, first.toInt())

			return out.array().copyOf(out.position())
		}
	}

	/** A TIFF, laid out by [lay], which gives where its first directory is. */
	private fun tiff(
		order: ByteOrder = ByteOrder.LITTLE_ENDIAN,
		magic: Int = 42,
		lay: Laying.() -> Long,
	): ByteArray = Laying(order, magic).let { it.bytes(it.lay()) }

	private fun file(name: String, bytes: ByteArray): Path =
		work.resolve(name).also {
			it.writeBytes(bytes)
		}

	private fun jpeg(width: Int, height: Int) = ImagePictures.sample(width, height, "jpg")

	/** What looks like a JPEG and is raw data held as one: a frame of the lossless kind, which no picture is. */
	private val lossless =
		byteArrayOf(
			0xFF.toByte(),
			0xD8.toByte(),
			0xFF.toByte(),
			0xC3.toByte(),
			0,
			11,
			14,
			0x10,
			0,
			0x18,
			0,
			1,
			1,
			0x11,
			0,
		) +
			ByteArray(64)

	private val jpegOffset = 0x0201
	private val jpegLength = 0x0202

	@Test
	fun aJpegsSizeIsReadFromItsFrameHeader() {
		assertEquals(Extent(160, 120), Jpeg.extent(jpeg(160, 120)))
		assertEquals(Extent(31, 47), Jpeg.extent(jpeg(31, 47)))
		// Raw data held as a lossless JPEG is no picture; nor is what is no JPEG, or one cut off before its header.
		assertNull(Jpeg.extent(lossless))
		assertNull(Jpeg.extent(ImagePictures.sample(8, 8, "png")))
		assertNull(Jpeg.extent(jpeg(160, 120).copyOf(12)))
		assertNull(Jpeg.extent(ByteArray(0)))
	}

	@Test
	fun aJpegsExifThumbnailIsFoundTurnedAsThePhotographIs() {
		val thumbnail = jpeg(160, 120)
		val photograph = jpeg(1600, 1200)

		val exif =
			tiff {
				val thumb = blob(thumbnail)
				val second =
					directory(listOf(long(jpegOffset, thumb), long(jpegLength, thumbnail.size.toLong())))

				// The camera was held on its side.
				directory(listOf(short(0x0112, 6)), next = second)
			}

		val segment = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + exif

		val file =
			file(
				"photo.jpg",
				ByteArrayOutputStream()
					.apply {
						write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()))
						write(ByteBuffer.allocate(2).putShort((segment.size + 2).toShort()).array())
						write(segment)
						write(photograph, 2, photograph.size - 2)
					}.toByteArray(),
			)

		val previews = Embedded.previews(file)

		assertEquals(listOf(Extent(160, 120)), previews.map { it.extent })
		assertEquals(6, previews.single().orientation)

		// Large enough for the smallest thumbnail, and not for one larger: unless whatever there is will do.
		val (bytes, orientation) = Embedded.read(file, 128, atLeast = true)!!

		assertArrayEquals(thumbnail, bytes)
		assertEquals(6, orientation)
		assertNull(Embedded.read(file, 256, atLeast = true))
		assertArrayEquals(thumbnail, Embedded.read(file, 256, atLeast = false)!!.first)

		// A JPEG with no Exif has none.
		assertEquals(emptyList<Preview>(), Embedded.previews(file("plain.jpg", photograph)))
	}

	@Test
	fun aNikonsPreviewIsInADirectoryUnderTheFirst() {
		val preview = jpeg(1620, 1080)
		val small = jpeg(160, 120)

		val file =
			file(
				"photo.nef",
				tiff {
					val full = blob(preview)
					val thumb = blob(small)

					val previewDirectory =
						directory(
							listOf(
								long(0x00FE, 1),
								short(0x0103, 6),
								long(jpegOffset, full),
								long(jpegLength, preview.size.toLong()),
							),
						)

					// The raw data itself: compressed Nikon's own way, and no preview.
					val raw =
						directory(
							listOf(short(0x0103, 34713), long(0x0111, 8), long(0x0117, 1000)),
						)

					val under = longs(previewDirectory, raw)
					val second =
						directory(listOf(long(jpegOffset, thumb), long(jpegLength, small.size.toLong())))

					directory(
						listOf(short(0x0112, 8), Tagged(0x014A, 4, 2, under)),
						next = second,
					)
				},
			)

		assertEquals(
			setOf(Extent(1620, 1080), Extent(160, 120)),
			Embedded.previews(file).map { it.extent }.toSet(),
		)
		// The smallest that is large enough is what is decoded: the thumbnail for a small tile, the preview for more.
		assertArrayEquals(small, Embedded.read(file, 128, atLeast = true)!!.first)
		assertArrayEquals(preview, Embedded.read(file, 512, atLeast = true)!!.first)
		assertEquals(8, Embedded.read(file, 512, atLeast = true)!!.second)
		// Wanted larger than any: the largest, where that will do.
		assertNull(Embedded.read(file, 2048, atLeast = true))
		assertArrayEquals(preview, Embedded.read(file, 2048, atLeast = false)!!.first)
	}

	@Test
	fun aCanonsPreviewIsAStripAndItsRawDataIsNotTakenForOne() {
		val preview = jpeg(1200, 800)

		val file =
			file(
				"photo.cr2",
				tiff {
					val full = blob(preview)
					val data = blob(lossless)

					// The raw data, held as a lossless JPEG in one strip: what a preview's directory also looks like.
					val raw =
						directory(
							listOf(short(0x0103, 6), long(0x0111, data), long(0x0117, lossless.size.toLong())),
						)

					directory(
						listOf(
							short(0x0103, 6),
							long(0x0111, full),
							long(0x0117, preview.size.toLong()),
							short(0x0112, 1),
						),
						next = raw,
					)
				},
			)

		assertEquals(listOf(Extent(1200, 800)), Embedded.previews(file).map { it.extent })
	}

	@Test
	fun aDngWithItsNumbersTheOtherWayRoundIsReadTheSame() {
		val small = jpeg(256, 171)
		val large = jpeg(1024, 683)

		val file =
			file(
				"photo.dng",
				tiff(ByteOrder.BIG_ENDIAN) {
					val first = blob(small)
					val second = blob(large)

					val under =
						longs(
							directory(
								listOf(
									long(0x00FE, 1),
									short(0x0103, 7),
									long(0x0111, first),
									long(0x0117, small.size.toLong()),
								),
							),
							directory(
								listOf(
									long(0x00FE, 1),
									short(0x0103, 7),
									long(0x0111, second),
									long(0x0117, large.size.toLong()),
								),
							),
						)

					directory(listOf(Tagged(0x014A, 4, 2, under), short(0x0112, 3)))
				},
			)

		assertEquals(
			listOf(Extent(256, 171), Extent(1024, 683)),
			Embedded.previews(file).map { it.extent }.sortedBy { it.pixels },
		)
		assertArrayEquals(small, Embedded.read(file, 256, atLeast = true)!!.first)
		assertArrayEquals(large, Embedded.read(file, 512, atLeast = true)!!.first)
		assertEquals(3, Embedded.read(file, 512, atLeast = true)!!.second)
	}

	@Test
	fun olympusAndPanasonicAreTiffsWithNumbersOfTheirOwn() {
		val preview = jpeg(640, 480)

		val olympus =
			file(
				"photo.orf",
				tiff(magic = 0x4F52) {
					val at = blob(preview)

					directory(listOf(long(jpegOffset, at), long(jpegLength, preview.size.toLong())))
				},
			)

		// Panasonic's JPEG is the value of an entry of its own.
		val panasonic =
			file(
				"photo.rw2",
				tiff(magic = 0x55) {
					val at = blob(preview)

					directory(listOf(Tagged(0x002E, 7, preview.size.toLong(), at)))
				},
			)

		assertEquals(listOf(Extent(640, 480)), Embedded.previews(olympus).map { it.extent })
		assertEquals(listOf(Extent(640, 480)), Embedded.previews(panasonic).map { it.extent })
		assertArrayEquals(preview, Embedded.read(panasonic, 256, atLeast = true)!!.first)
	}

	@Test
	fun aFujiSaysWhereItsJpegIsAtAFixedPlace() {
		val preview = jpeg(1920, 1280)
		val header = ByteArray(160)

		"FUJIFILMCCD-RAW 0201FF129502X-T3".toByteArray().copyInto(header)
		ByteBuffer
			.wrap(header)
			.putInt(84, header.size)
			.putInt(88, preview.size)

		val file = file("photo.raf", header + preview + ByteArray(4096))

		assertEquals(listOf(Extent(1920, 1280)), Embedded.previews(file).map { it.extent })
		assertArrayEquals(preview, Embedded.read(file, 1024, atLeast = true)!!.first)
	}

	@Test
	fun aFileThatSaysWhatIsNotSoHasNoPreviewAndBreaksNothing() {
		val preview = jpeg(320, 240)

		// A directory that is its own next; one whose preview lies past the end of the file; one with a count of
		// millions; a preview that says it is longer than the file; a header and nothing more; and no file at all.
		val looped = tiff { directory(listOf(short(0x0112, 1)), next = 8) }
		val beyond =
			tiff { directory(listOf(long(jpegOffset, 900_000), long(jpegLength, 5000))) }

		val countless =
			tiff {
				val at = out.position().toLong()

				out.putShort(0xFFFF.toShort())
				at
			}

		val overlong =
			tiff {
				val at = blob(preview)

				directory(listOf(long(jpegOffset, at), long(jpegLength, 50_000_000)))
			}
		// The entry that says where the directories under it are points nowhere.
		val nowhere = tiff { directory(listOf(Tagged(0x014A, 4, 9, 800_000))) }

		for ((name, bytes) in listOf(
			"looped" to looped,
			"beyond" to beyond,
			"countless" to countless,
			"overlong" to overlong,
			"nowhere" to nowhere,
			"short" to looped.copyOf(6),
			"empty" to ByteArray(0),
			"text" to "This is no photograph at all, only words.".toByteArray(),
		)) {
			assertEquals(emptyList<Preview>(), Embedded.previews(file("$name.dng", bytes)), name)
			assertNull(Embedded.read(work.resolve("$name.dng"), 128, atLeast = false), name)
		}
		assertEquals(emptyList<Preview>(), Embedded.previews(work.resolve("gone.nef")))

		// Every prefix of a good file is read without complaint, whatever it finds in it.
		val good =
			tiff {
				val at = blob(preview)

				directory(listOf(long(jpegOffset, at), long(jpegLength, preview.size.toLong())))
			}

		for (length in 0..good.size step 97)
			Embedded.previews(file("cut.dng", good.copyOf(length)))
		assertEquals(
			listOf(Extent(320, 240)),
			Embedded.previews(file("whole.dng", good)).map {
				it.extent
			},
		)
	}

	@Test
	fun whichPreviewIsChosen() {
		fun preview(side: Int) = Preview(1, 1, Extent(side, side * 2 / 3), null)

		val previews = listOf(preview(160), preview(1024), preview(6000))

		assertEquals(preview(160), Embedded.chosen(previews, 128, atLeast = true))
		assertEquals(preview(1024), Embedded.chosen(previews, 256, atLeast = true))
		assertEquals(preview(6000), Embedded.chosen(previews, 2048, atLeast = true))
		assertNull(Embedded.chosen(previews.take(1), 256, atLeast = true))
		assertEquals(preview(160), Embedded.chosen(previews.take(1), 256, atLeast = false))
		assertNull(Embedded.chosen(emptyList(), 256, atLeast = false))
	}
}
