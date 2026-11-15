package prism.shared

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A value kept in a file, whatever the value and the file: read as it is opened and as a change is written, and at
 * no other time; changed in the file as it is, so that two that keep one file keep each other's changes; left as it
 * was, file and value, by a change or a write that fails; kept aside where it cannot be read; and handed to its
 * readers once for each change. What is particular to a file is its format's, and tested with it.
 */
class StoredTest {
	@TempDir
	lateinit var dir: Path

	private val file get() = dir.resolve("kept/words")

	/** A set of words, a line a word; a file that says `!` is not one. */
	private class Words(
		val reads: AtomicInteger = AtomicInteger(),
		val unwritable: (Set<String>) -> Boolean = { false },
	) : Format<Set<String>> {
		override val none: Set<String> = emptySet()

		override fun read(bytes: ByteArray): Parsed<Set<String>> {
			reads.incrementAndGet()

			val lines = bytes.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }

			return if ("!" in lines)
				Loaded.Unreadable("it says !")
			else
				Loaded.Read(lines.toSet())
		}

		override fun written(value: Set<String>): ByteArray {
			if (unwritable(value))
				throw IOException("the disk is full")

			return value.sorted().joinToString("") { "$it\n" }.toByteArray()
		}
	}

	private fun beside(): List<String> =
		file.parent
			.listDirectoryEntries()
			.map { it.name }
			.filter { it != file.name }
			.sorted()

	@Test
	fun noFileIsNothing_AndWhatIsChangedIsWrittenAndReadBack() {
		val stored = Stored.of(file, Words())

		assertEquals(emptySet<String>(), stored.value.value)
		assertNull(stored.problem)
		assertEquals(Loaded.Missing, OnDisk(file, Words()).read())

		assertEquals(setOf("a"), stored.update { this + "a" })
		assertEquals(setOf("a", "b"), stored.update { this + "b" })
		assertEquals(setOf("a", "b"), stored.value.value)
		assertEquals("a\nb\n", file.readText())
		assertEquals(Loaded.Read(setOf("a", "b")), OnDisk(file, Words()).read())
		// One opened now holds it; and nothing is left of the writing but the lock its writers take.
		assertEquals(setOf("a", "b"), Stored.of(file, Words()).value.value)
		assertEquals(listOf("words.lock"), beside())
	}

	@Test
	fun theFileIsReadAsItIsOpenedAndAsAChangeIsWritten_AndAtNoOtherTime() {
		Files.createDirectories(file.parent)
		file.writeText("a\n")

		val format = Words()
		val stored = Stored.of(file, format)

		assertEquals(1, format.reads.get())

		// Changed by hand: nothing reads it, and the value is what it was.
		file.writeText("a\nby hand\n")
		repeat(3) { assertEquals(setOf("a"), stored.value.value) }
		assertEquals(1, format.reads.get())

		// The next change is made to the file as it is, which is the one read there is.
		assertEquals(setOf("a", "b", "by hand"), stored.update { this + "b" })
		assertEquals(2, format.reads.get())
	}

	@Test
	fun twoOfOneFileKeepEachOthersChanges() {
		// As two processes have: the prism that is running, and a window run on its own.
		val one = Stored.of(file, Words())
		val other = Stored.of(file, Words())

		one.update { this + "one's" }
		// The other is not told, and has what it began with; its own change is made to what the first left.
		assertEquals(emptySet<String>(), other.value.value)
		assertEquals(setOf("one's", "other's"), other.update { this + "other's" })
		assertEquals(setOf("one's", "other's"), other.value.value)
		// And so back.
		assertEquals(setOf("one's", "other's", "more"), one.update { this + "more" })
		assertEquals("more\none's\nother's\n", file.readText())

		// A value that is not held is read each time, and changed the same way.
		val fresh = OnDisk(file, Words())

		assertEquals(setOf("one's", "other's", "more"), fresh.read().orElse(emptySet()))
		fresh.update { this - "more" }
		assertEquals(setOf("one's", "other's", "last"), one.update { this + "last" })
	}

	@Test
	fun aChangeThatFailsLeavesTheFileAndTheValueAsTheyWere() {
		val stored = Stored.of(file, Words())

		stored.update { this + "a" }

		assertThrows(IllegalStateException::class.java) {
			stored.update { error("no such word") }
		}
		assertEquals("a\n", file.readText())
		assertEquals(setOf("a"), stored.value.value)

		// And one made after it is made as ever: the lock was let go.
		assertEquals(setOf("a", "b"), stored.update { this + "b" })
		assertEquals(listOf("words.lock"), beside())
	}

	@Test
	fun aWriteThatFailsIsToldAndLeavesTheFile_AndTheValueIsWhatWhoIsToldSays() {
		// Told by being thrown at, which is what is done unless something else is asked for: nothing changes.
		val strict = Stored.of(file, Words(unwritable = { "full" in it }))

		strict.update { this + "a" }
		assertThrows(IOException::class.java) { strict.update { this + "full" } }
		assertEquals("a\n", file.readText())
		assertEquals(setOf("a"), strict.value.value)
		assertEquals(listOf("words.lock"), beside())

		// Told, and saying nothing: what cannot be kept is held all the same, for as long as this runs.
		val told = ArrayList<IOException>()
		val lenient = Stored.of(file, Words(unwritable = { "full" in it }), told::add)

		assertEquals(setOf("a", "full"), lenient.update { this + "full" })
		assertEquals(1, told.size)
		assertEquals(setOf("a", "full"), lenient.value.value)
		assertEquals("a\n", file.readText())
	}

	@Test
	fun aFileThatCannotBeReadIsNothing_SaysWhy_AndIsKeptAsideBeforeItIsWrittenOver() {
		Files.createDirectories(file.parent)
		file.writeText("a\n!\n")

		val stored = Stored.of(file, Words())

		assertEquals(emptySet<String>(), stored.value.value)
		assertEquals("it says !", stored.problem)
		assertEquals(Loaded.Unreadable("it says !"), OnDisk(file, Words()).read())
		// Untouched until something is changed.
		assertEquals(emptyList<String>(), beside())

		stored.update { this + "b" }

		assertNull(stored.problem)
		assertEquals("b\n", file.readText())
		assertEquals("a\n!\n", file.resolveSibling("words.unreadable").readText())
	}

	@Test
	fun everyReaderIsHandedEachChangeOnce_WithAFileAndWithout() {
		for (stored in listOf(Stored.of(file, Words()), Stored.inMemory(emptySet()))) {
			val scope = CoroutineScope(Dispatchers.Unconfined)
			val handed = CopyOnWriteArrayList<Set<String>>()

			try {
				scope.launch { stored.value.collect(handed::add) }

				stored.update { this + "a" }
				stored.update { this + "b" }
				stored.update { this - "a" }

				assertEquals(
					listOf(emptySet(), setOf("a"), setOf("a", "b"), setOf("b")),
					handed.toList(),
				)
			} finally {
				scope.cancel()
			}
		}
	}

	@Test
	fun changesMadeAtOnceAreEachMadeToWhatTheLastLeft() {
		val stored = Stored.of(file, Words())

		val threads =
			(0 until 8).map { writer ->
				Thread { repeat(25) { stored.update { this + "$writer-$it" } } }.apply { start() }
			}

		threads.forEach { it.join() }

		assertEquals(200, stored.value.value.size)
		assertEquals(200, file.readText().lines().count { it.isNotEmpty() })
		assertTrue(Files.notExists(file.resolveSibling("words.unreadable")))
	}

	@Test
	fun linesAndTextAreReadAsTheyAreWritten() {
		val lines =
			Format.lines<List<Int>>(
				emptyList(),
				read = { it.mapNotNull(String::toIntOrNull) },
				written = { it.map(Int::toString) },
			)

		assertEquals(Loaded.Read(listOf(1, 2, 3)), lines.read(lines.written(listOf(1, 2, 3))))
		// A line that says nothing is left out by what reads the lines.
		assertEquals(Loaded.Read(listOf(1, 3)), lines.read("1\ntwo\n\n3\n".toByteArray()))
		assertEquals(
			Loaded.Read("as\nit is"),
			Format.TEXT.read(Format.TEXT.written("as\nit is")),
		)
	}
}
