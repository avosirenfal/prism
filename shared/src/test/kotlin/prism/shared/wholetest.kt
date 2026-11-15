package prism.shared

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A file written whole: it holds what was written, in a folder made for it; a write that fails part way leaves the
 * file as it was and nothing beside it; many writers at once each leave a whole file; and a change made under the
 * file's lock is made to what the last writer left.
 */
class WholeFileTest {
	@TempDir
	lateinit var dir: Path

	private val file get() = dir.resolve("kept/settings")

	/** What is in the file's folder, but the file. */
	private fun beside(): List<String> =
		file.parent
			.listDirectoryEntries()
			.map { it.name }
			.filter { it != file.name }

	private fun write(text: String) = WholeFile.replace(file, text.toByteArray())

	@Test
	fun whatIsWrittenIsWhatTheFileHolds_InAFolderMadeForIt() {
		write("one\n")
		assertEquals("one\n", file.readText())

		Snapshot.write(
			file,
			listOf(ByteBuffer.wrap("fo".toByteArray()), ByteBuffer.wrap("ur".toByteArray())),
		)
		assertEquals("four", file.readText())

		// Nothing is left of the writing.
		assertEquals(emptyList<String>(), beside())
	}

	@Test
	fun aWriteThatFailsPartWayLeavesTheFileAsItWasAndNothingBesideIt() {
		write("as it was\n")

		// The new contents are written, and then the write fails before the file is put in the old one's place.
		assertThrows(IOException::class.java) {
			WholeFile.replace(file) { channel ->
				channel.write(ByteBuffer.wrap("half of what it was to".toByteArray()))
				// What was written is there, under another name, as this fails.
				assertEquals(1, beside().size)
				throw IOException("the disk is full")
			}
		}

		assertEquals("as it was\n", file.readText())
		assertEquals(emptyList<String>(), beside())

		// And with no file yet, there is still none.
		val other = dir.resolve("kept/other")

		assertThrows(IllegalStateException::class.java) {
			WholeFile.replace(other) { error("failed") }
		}
		assertTrue(Files.notExists(other))
		assertEquals(emptyList<String>(), beside())
	}

	@Test
	fun manyWritersAtOnceEachLeaveAWholeFile() {
		val texts = (0 until 16).map { writer -> "$writer ".repeat(4000) }
		val begin = CountDownLatch(1)

		val writers =
			texts.map { text ->
				thread {
					begin.await()
					repeat(20) { write(text) }
				}
			}

		begin.countDown()
		// Read while they write: the file is always one writer's text, all of it.
		repeat(200) {
			if (Files.exists(file))
				assertTrue(file.readText() in texts)
		}
		writers.forEach { it.join() }

		assertTrue(file.readText() in texts)
		assertEquals(emptyList<String>(), beside())
	}

	@Test
	fun aChangeMadeUnderTheLockIsMadeToWhatTheLastWriterLeft() {
		file.parent.let(Files::createDirectories)
		file.writeText("0")

		fun counted(writers: Writers) {
			val begin = CountDownLatch(1)

			val threads =
				(0 until 8).map {
					thread {
						begin.await()
						repeat(50) {
							WholeFile.locked(file, writers) {
								write((file.readText().toInt() + 1).toString())
							}
						}
					}
				}

			begin.countDown()
			threads.forEach { it.join() }
		}

		// Not one of the four hundred was made to a file another had since written over; and a file that others
		// write too has no lock left beside it.
		counted(Writers.Anyone)
		assertEquals("400", file.readText())
		assertEquals(emptyList<String>(), beside())

		counted(Writers.Prisms)
		assertEquals("800", file.readText())
		assertEquals(listOf("settings.lock"), beside())
	}
}
