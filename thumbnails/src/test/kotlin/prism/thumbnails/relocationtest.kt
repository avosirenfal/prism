package prism.thumbnails

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A cache moved to another folder, stopped part of the way, and removed (relocation.kt): each entry whole in one
 * folder or the other, and nothing touched that is not the cache's.
 */
class RelocationTest {
	@TempDir
	lateinit var work: Path

	/** A cache of [count] thumbnails of files that need not be there, a few records of failures, and a stray file. */
	private fun filled(root: Path, count: Int): ThumbnailCache {
		val cache = ThumbnailCache(root)

		for (number in 1..count) {
			val subject =
				Subject(work.resolve("pictures/$number.jpg"), java.time.Instant.ofEpochSecond(1000))

			cache.write(
				subject,
				ThumbnailSize.entries[number % 4],
				ImagePictures.sample(8 + number % 5, 8),
			)
			if (number % 10 == 0)
				cache.fail(subject)
		}

		return cache
	}

	private fun Path.files(): Map<String, Long> =
		if (Files.isDirectory(this))
			Files.walk(this).use { all ->
				all.filter { Files.isRegularFile(it) }.toList().associate {
					relativize(it).toString() to
						Files.size(it)
				}
			}
		else
			emptyMap()

	@Test
	fun aCacheMovedIsTheSameEntriesInTheOtherFolder() =
		runBlocking {
			val from = filled(work.resolve("old/thumbnails"), 40)
			val to = ThumbnailCache(work.resolve("new place/cache"))
			val before = from.root.files()
			val told = ArrayList<Relocated>()

			assertTrue(Relocation.holds(from))
			assertFalse(Relocation.holds(to))

			val moved = Relocation.move(from, to) { told += it }

			assertEquals(Relocated(44, 0, 44), moved)
			assertEquals(before, to.root.files())
			// Told at each, from none to all; and the folders they left are gone with them.
			assertEquals((0..44).toList(), told.map { it.done })
			assertTrue(told.last().finished)
			assertEquals(1f, told.last().fraction)
			assertFalse(Relocation.holds(from))
			assertFalse(Files.exists(from.root.resolve("large")))
			assertEquals(emptyMap<String, Long>(), from.root.files())
			assertFalse(
				Files.exists(from.root.resolve("fail")),
				"${Files.walk(from.root).use { it.toList() }}",
			)

			// Each still says what it is of: found in the new folder as it was in the old.
			val subject =
				Subject(work.resolve("pictures/8.jpg"), java.time.Instant.ofEpochSecond(1000))

			assertEquals(
				to.entry(subject.file, ThumbnailSize.Normal),
				to.find(subject, ThumbnailSize.entries),
			)
		}

	@Test
	fun whatIsNotTheCachesIsLeftWhereItIsWhicheverIsDone() =
		runBlocking {
			val from = filled(work.resolve("shared"), 8)

			// The folder the user named held other things too.
			from.root.resolve("large/notes.txt").writeText("mine")
			from.root.resolve("holiday.png").writeBytes(ImagePictures.sample(8, 8))
			from.root.resolve("documents").createDirectories()
			from.root.resolve("documents/letter.txt").writeText("dear")

			Relocation.move(from, ThumbnailCache(work.resolve("elsewhere")))
			assertEquals(
				setOf("large/notes.txt", "holiday.png", "documents/letter.txt"),
				from.root.files().keys,
			)

			// Removed, the same: and a folder that still holds something is not removed.
			val other = filled(work.resolve("shared"), 8)

			assertEquals(Relocated(8, 0, 8), Relocation.delete(other))
			assertEquals(
				setOf("large/notes.txt", "holiday.png", "documents/letter.txt"),
				other.root.files().keys,
			)
			assertFalse(Files.exists(other.root.resolve("normal")))
		}

	@Test
	fun aMoveStoppedPartOfTheWayLeavesTwoGoodCaches() {
		val from = filled(work.resolve("old"), 60)
		val to = ThumbnailCache(work.resolve("new"))
		val before = from.root.files()

		// Stopped by whoever watches it, after the twentieth.
		val stopped =
			runCatching {
				runBlocking {
					Relocation.move(from, to) {
						if (it.done == 20)
							cancel()
					}
				}
			}

		assertTrue(stopped.exceptionOrNull() is CancellationException)

		val moved = to.root.files()
		val left = from.root.files()

		// Twenty are in the new folder, the rest where they were: none in both, none in neither, none cut short.
		assertEquals(20, moved.size)
		assertEquals(before, moved + left)
		assertEquals(emptySet<String>(), moved.keys intersect left.keys)
		assertEquals(20, to.entries().count())
		assertEquals(46, from.entries().count())
		// Nothing half written lies beside them.
		assertTrue((moved.keys + left.keys).all { it.endsWith(".png") })

		// Begun again, it takes what was left.
		assertEquals(Relocated(46, 0, 46), runBlocking { Relocation.move(from, to) })
		assertEquals(before, to.root.files())
	}

	@Test
	fun movedOntoACacheThatHasTheSameEntryTheOneMovedIsTheOneKept() =
		runBlocking {
			val subject =
				Subject(work.resolve("pictures/1.jpg"), java.time.Instant.ofEpochSecond(1000))
			val from = ThumbnailCache(work.resolve("a"))
			val to = ThumbnailCache(work.resolve("b"))

			to.write(
				subject.copy(modified = java.time.Instant.ofEpochSecond(1)),
				ThumbnailSize.Large,
				ImagePictures.sample(8, 8),
			)
			from.write(subject, ThumbnailSize.Large, ImagePictures.sample(16, 16))

			Relocation.move(from, to)
			assertEquals(
				to.entry(subject.file, ThumbnailSize.Large),
				to.find(subject, ThumbnailSize.entries),
			)
			// Moved to the folder it is in, nothing happens to it.
			assertEquals(Relocated(1, 0, 1), Relocation.move(to, ThumbnailCache(to.root)))
			assertEquals(1, to.entries().count())
		}
}
