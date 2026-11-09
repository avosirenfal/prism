package barion.index

import barion.index.query.query
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

	private val file: Path get() = state.resolve("index.barion")

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
			"${it.where} ${it.kind} ${it.device}"
		}

	private fun read(): IndexRead.Read = IndexFile.read(file) as IndexRead.Read

	@Test
	fun anIndexWrittenIsTheIndexRead() {
		made("docs/a.txt", "docs/b.md", "music/c.flac")

		val index =
			Index.EMPTY
				.with(indexer.index(root))
				.with(
					Change.Picked(where("docs/a.txt"), Instant.parse("2026-11-01T12:30:00.123456789Z")),
				)

		IndexFile.write(file, index)

		val read = read()

		assertFalse(read.torn)
		assertEquals(file.fileSize(), read.length)
		assertEquals(0, read.changes)
		assertEquals(index.roots, read.index.roots)
		assertEquals(index.shape(), read.index.shape())
		assertEquals(
			Instant.parse("2026-11-01T12:30:00.123456789Z"),
			read.index.pickedAt(where("docs/a.txt")),
		)
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
		val second = Change.Picked(where("a/three.txt"), Instant.parse("2026-11-12T10:00:00Z"))

		length = IndexFile.append(file, length, changes)
		length = IndexFile.append(file, length, listOf(second))

		val read = read()
		val made = first.with(changes).with(second)

		assertEquals(length, read.length)
		assertTrue(read.changes > 0)
		assertEquals(made.shape(), read.index.shape())
		assertEquals(
			made.pickedAt(where("a/three.txt")),
			read.index.pickedAt(where("a/three.txt")),
		)
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
	fun aStoreBuildsWhereThereIsNoFileAndReadsWhereThereIsOne() {
		made("docs/a.txt", "docs/b.txt")

		val built = IndexStore.open(file, listOf(root), indexer)

		assertEquals(IndexStore.Origin.Built, built.origin)
		assertTrue(file.exists())

		made("docs/c.txt")
		built.make(listOfNotNull(indexer.reread(where("docs/c.txt"))))
		built.flush()

		val reopened = IndexStore.open(file, listOf(root), indexer)

		assertEquals(IndexStore.Origin.Read(torn = false), reopened.origin)
		assertEquals(built.index.shape(), reopened.index.shape())

		// A file of something else is replaced by an index made anew.
		file.writeText("something else")
		assertInstanceOf(
			IndexStore.Origin.Rebuilt::class.java,
			IndexStore.open(file, listOf(root), indexer).origin,
		)
	}

	@Test
	fun aStoreCompactsOnceChangesPileUp() {
		made(*(1..50).map { "docs/file$it.txt" }.toTypedArray())

		val store = IndexStore.open(file, listOf(root), indexer)
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
		assertFalse(Files.exists(state.resolve("index.barion.new")))
		// Nothing was left half written beside it, and the store reads as it holds.
		assertEquals(
			store.index.shape(),
			IndexStore.open(file, listOf(root), indexer).index.shape(),
		)
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
}
