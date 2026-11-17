package prism.index

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.index.query.query

/**
 * The index's file: written and read back to the same index; changes appended and read back as they were made; a
 * frame cut short or damaged dropped, and written over by the next; and the store that keeps an index and its file in
 * step, building one where there is none and compacting it once changes pile up.
 */
class FileTest {
	@TempDir
	lateinit var root: Path

	@TempDir
	lateinit var state: Path

	private val indexer = Indexer(mounts = emptyList())

	private val file: Path get() = state.resolve("index.prism")

	private fun made(vararg files: String) =
		files.forEach {
			root
				.resolve(it)
				.also { path ->
					path.parent.createDirectories()
				}.writeText(it)
		}

	private fun where(path: String) = Where.of(root.resolve(path))

	private fun Index.shape(): List<String> =
		entries().map {
			"${it.where} ${it.kind} ${it.device} ${it.inode}"
		}

	private fun read(): IndexRead.Read = IndexFile.read(file) as IndexRead.Read

	@Test
	fun anIndexWrittenIsTheIndexRead() {
		made("docs/a.txt", "docs/b.md", "music/c.flac")

		val later =
			Indexer(
				mounts = emptyList(),
				clock =
					java.time.Clock.fixed(
						Instant.parse("2026-11-01T12:30:00Z"),
						java.time.ZoneOffset.UTC,
					),
			)

		val first = Index.EMPTY.with(indexer.index(root))

		made("docs/new.txt")

		// Through a graft and compacted as it is written.
		val index = first.with(later.reread(where("docs/new.txt"))!!)

		IndexFile.write(file, index)

		val read = read()

		assertFalse(read.torn)
		assertEquals(file.fileSize(), read.length)
		assertEquals(0, read.changes)
		assertEquals(index.roots, read.index.roots)
		assertEquals(index.shape(), read.index.shape())
		assertEquals(
			Instant.parse("2026-11-01T12:30:00Z"),
			read.index.seenAt(where("docs/new.txt")),
		)
		assertEquals(null, read.index.seenAt(where("docs/a.txt")))
	}

	@Test
	fun theSnapshotIsCompressed() {
		made(*(1..500).map { "photos/2026/img_%04d.jpg".format(it) }.toTypedArray())
		IndexFile.write(file, Index.EMPTY.with(indexer.index(root)))

		val frame = ByteBuffer.wrap(file.readBytes(), 16, 16).order(ByteOrder.LITTLE_ENDIAN)
		val payload = frame.getInt(20)
		val stored = frame.getInt(24)

		assertTrue(stored * 3 < payload, "$stored bytes stored of $payload")
	}

	@Test
	fun changesAppendedAreReadBackAsTheyWereMade() {
		made("a/one.txt", "b/two.txt")

		val first = Index.EMPTY.with(indexer.index(root))
		var length = IndexFile.write(file, first)

		made("a/three.txt")
		root.resolve("b/two.txt").deleteExisting()

		val changes =
			listOfNotNull(indexer.reread(where("a")), indexer.reread(where("b/two.txt")))

		length = IndexFile.append(file, length, changes.take(1))
		length = IndexFile.append(file, length, changes.drop(1))

		val read = read()
		val made = first.with(changes)

		assertEquals(length, read.length)
		assertTrue(read.changes > 0)
		assertEquals(made.shape(), read.index.shape())
		assertEquals(made.seenAt(where("a/three.txt")), read.index.seenAt(where("a/three.txt")))
		assertEquals(
			setOf(where("a/three.txt")),
			(
				read.index.search(
					query {
						+word("three")
					},
				) as Searched.Found
			).results.map { it.where }.toSet(),
		)

		// Appending the same changes again changes nothing: they say what is so.
		IndexFile.append(file, length, changes)
		assertEquals(made.shape(), read().index.shape())
	}

	@Test
	fun aFrameCutShortIsDroppedAndWrittenOver() {
		made("a/one.txt")

		val index = Index.EMPTY.with(indexer.index(root))
		val length = IndexFile.write(file, index)

		made("a/two.txt")

		val change = listOfNotNull(indexer.reread(where("a/two.txt")))
		val whole = IndexFile.append(file, length, change)

		// A crash partway through writing the frame: half of it on disk.
		file.writeBytes(file.readBytes().copyOf(((length + whole) / 2).toInt()))

		val torn = read()

		assertTrue(torn.torn)
		assertEquals(length, torn.length)
		assertEquals(index.shape(), torn.index.shape())

		// The next frame goes where the torn one began.
		IndexFile.append(file, torn.length, change)
		assertFalse(read().torn)
		assertEquals(index.with(change).shape(), read().index.shape())
	}

	@Test
	fun aFrameDamagedIsDropped() {
		made("a/one.txt")

		val index = Index.EMPTY.with(indexer.index(root))
		val length = IndexFile.write(file, index)

		made("a/two.txt")
		IndexFile.append(file, length, listOfNotNull(indexer.reread(where("a/two.txt"))))

		val bytes = file.readBytes()

		bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0x5A).toByte()
		file.writeBytes(bytes)

		assertTrue(read().torn)
		assertEquals(index.shape(), read().index.shape())
	}

	@Test
	fun whatIsNotAnIndexIsSaidSo() {
		assertEquals(IndexRead.Missing, IndexFile.read(file))

		file.writeText("not an index at all")
		assertInstanceOf(IndexRead.Unreadable::class.java, IndexFile.read(file))
	}

	@Test
	fun aStoreStartsEmptyWhereThereIsNoFileAndReadsWhereThereIsOne() {
		made("docs/a.txt", "docs/b.txt")

		val store = IndexStore.open(file)

		assertEquals(IndexStore.Origin.Missing, store.origin)
		assertEquals(0, store.index.size)
		assertFalse(file.exists())

		// What is made is written by the first flush, the whole index at once.
		store.make(listOf(indexer.index(root)))
		store.flush()
		assertTrue(file.exists())

		made("docs/c.txt")
		store.make(listOfNotNull(indexer.reread(where("docs/c.txt"))))
		store.flush()

		val reopened = IndexStore.open(file)

		assertEquals(IndexStore.Origin.Read(torn = false), reopened.origin)
		assertEquals(store.index.shape(), reopened.index.shape())

		// A file of something else is said to be, and written over by the next flush.
		file.writeText("something else")

		val over = IndexStore.open(file)

		assertInstanceOf(IndexStore.Origin.Unreadable::class.java, over.origin)
		over.make(listOf(indexer.index(root)))
		over.flush()
		assertEquals(store.index.shape(), IndexStore.open(file).index.shape())
	}

	@Test
	fun aStoreInMemoryWritesNothing() {
		made("a.txt")

		val store = IndexStore.inMemory()

		store.make(listOf(indexer.index(root)))
		store.flush()
		store.compact()
		assertEquals(IndexStore.Origin.Memory, store.origin)
		assertEquals(
			listOf(Where.of(root.resolve("a.txt"))),
			store.index
				.entries()
				.map {
					it.where
				}.drop(1),
		)
		assertEquals(emptyList<Path>(), Files.list(state).use { it.toList() })
	}

	@Test
	fun aStoreCompactsOnceChangesPileUp() {
		made(*(1..50).map { "docs/file$it.txt" }.toTypedArray())

		val store =
			IndexStore.open(file).also {
				it.make(listOf(indexer.index(root)))
				it.flush()
			}
		val compact = file.fileSize()

		// Each change is small next to the snapshot; enough of them, and the file is written anew, as one snapshot.
		for (round in 1..200) {
			made("docs/new$round.txt")
			store.make(listOfNotNull(indexer.reread(where("docs/new$round.txt"))))
			store.flush()
			if (read().changes == 0L)
				break
		}

		val read = read()

		assertEquals(0, read.changes)
		assertEquals(0, store.index.loose)
		assertTrue(file.fileSize() < compact * 3)
		assertEquals(Index.EMPTY.with(indexer.index(root)).shape(), read.index.shape())
		// Nothing was left half written beside it, and the store reads as it holds.
		assertEquals(
			emptyList<String>(),
			Files.list(state).use { all ->
				all.map { it.fileName.toString() }.filter { it.endsWith(".new") }.toList()
			},
		)
		assertEquals(store.index.shape(), IndexStore.open(file).index.shape())
	}

	@Test
	fun appendingAfterTheGoodPartCutsOffWhatFollowsIt() {
		made("a.txt")

		val length = IndexFile.write(file, Index.EMPTY.with(indexer.index(root)))

		Files.write(file, ByteArray(40) { 7 }, StandardOpenOption.APPEND)
		assertTrue(read().torn)

		val after = IndexFile.append(file, read().length, emptyList())

		assertEquals(after, file.fileSize())
		assertFalse(read().torn)
		assertTrue(after > length)
	}

	/**
	 * A file as a daemon leaves it after a day of work: one snapshot, and after it a great many changes, each of
	 * another folder. Reading it makes every one of them, and must take time in proportion to how many there are: it is
	 * read before a window opens. It once took time as their number squared, which was a minute for a day's changes.
	 *
	 * So the same is read with four times the changes, and may take no more than eight times as long: four, were it
	 * exactly in proportion, and sixteen, were it as the square. Each is the best of three readings, so that a pause
	 * of the machine's is not taken for the reading's.
	 */
	@Test
	fun aFileOfManyChangesIsReadInTimeInProportionToThem() {
		// Folders in folders, as they are on a disk: a hundred to each, so that no one folder holds them all.
		fun folder(at: Int) = "d${at / 100}/f${at % 100}"

		/** How long the file takes to read, in nanoseconds, once [folders] of the tree have each been changed in it. */
		fun readingAfter(from: Int, folders: Int): Long {
			var length = read().length

			// Twenty changes to a frame, as a flush writes what gathered since the last.
			(from until folders).chunked(20).forEach { batch ->
				batch.forEach { made("${folder(it)}/b.txt") }
				length =
					IndexFile.append(file, length, batch.map { indexer.reread(where(folder(it)))!! })
			}

			return (1..3).minOf {
				val began = System.nanoTime()

				read()
				System.nanoTime() - began
			}
		}

		(0 until MANY).forEach { made("${folder(it)}/a.txt") }
		IndexFile.write(file, Index.EMPTY.with(indexer.index(root)))

		val few = readingAfter(0, MANY / 4)
		val many = readingAfter(MANY / 4, MANY)
		val read = read()

		// Every change was made: each folder holds both its files, and the index is the one a fresh look gives.
		assertEquals(1 + MANY / 100 + MANY * 3, read.index.size)
		assertEquals(
			Index.EMPTY.with(indexer.index(root)).shape(),
			read.index.compacted().shape(),
		)
		assertTrue(
			many < few * 8,
			"${MANY / 4} changes read in ${few / 1_000_000} ms, and $MANY in ${many / 1_000_000} ms",
		)
	}

	private companion object {
		/** How many folders the file of many changes changes. */
		const val MANY = 6000
	}
}
