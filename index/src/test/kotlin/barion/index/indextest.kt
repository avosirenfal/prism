package barion.index

import barion.index.query.QueryBuilder
import barion.index.query.query
import barion.linux.Fd
import barion.linux.orThrow
import barion.linux.statx
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteExisting
import kotlin.io.path.moveTo
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The index as changes are made to it: each change says what is so now, so making one twice is making it once; a
 * change is held by hiding and grafting until the index is compacted, and a search finds the same before and after;
 * and what the rules leave out is never held.
 */
class IndexTest {
	@TempDir
	lateinit var root: Path

	/** A folder beside the index's, which it does not hold. */
	@TempDir
	lateinit var elsewhere: Path

	private val indexer = Indexer(mounts = emptyList())

	private fun made(vararg files: String) =
		files.forEach {
			root
				.resolve(it)
				.also { path ->
					path.parent.createDirectories()
				}.writeText(it)
		}

	private fun where(path: String) = Where.of(root.resolve(path))

	/** Each entry held, as a path under [root] and its kind. */
	private fun Index.shape(): List<String> =
		entries().map {
			"${it.where.toString().removePrefix("$root").ifEmpty { "/" }} ${it.kind}"
		}

	private fun Index.found(build: QueryBuilder.() -> Unit): Set<String> =
		(search(query(build)) as Searched.Found)
			.results
			.map {
				it.where.toString().removePrefix("$root/")
			}.toSet()

	@Test
	fun anIndexHoldsWhatIsOnDiskInPathOrder() {
		made("b/two.txt", "a/one.txt", "c.md")
		root.resolve("link").createSymbolicLinkPointingTo(Path.of("c.md"))

		val index = Index.EMPTY.with(indexer.index(root))

		assertEquals(setOf(Where.of(root)), index.roots)
		assertEquals(
			listOf(
				"/ Directory",
				"/a Directory",
				"/a/one.txt File",
				"/b Directory",
				"/b/two.txt File",
				"/c.md File",
				"/link LinkToFile",
			),
			index.shape(),
		)
	}

	@Test
	fun aChangeMadeTwiceIsAChangeMadeOnce() {
		made("a/one.txt", "b/two.txt")

		val first = Index.EMPTY.with(indexer.index(root))

		made("a/three.txt")
		root.resolve("b/two.txt").deleteExisting()

		val changes =
			listOfNotNull(
				indexer.reread(where("a")),
				indexer.reread(where("b/two.txt")),
				Change.Picked(where("a/one.txt"), Instant.parse("2026-01-01T00:00:00Z")),
				Change.Picked(where("a/one.txt"), Instant.parse("2025-01-01T00:00:00Z")),
			)

		val once = first.with(changes)
		val twice = once.with(changes)

		assertEquals(once.shape(), twice.shape())
		assertEquals(once.found { +word("t") }, twice.found { +word("t") })
		assertEquals(Instant.parse("2026-01-01T00:00:00Z"), twice.pickedAt(where("a/one.txt")))
		// And indexing the root again changes nothing.
		assertEquals(once.shape(), once.with(indexer.index(root)).shape())
	}

	@Test
	fun changesAreHeldAsTheDiskHasThemAndCompactingKeepsThem() {
		made("docs/a.txt", "docs/old/b.txt", "music/c.mp3")

		val before = Index.EMPTY.with(indexer.index(root))

		// A file made, a folder moved, a file gone: each reread where the watcher would say.
		made("docs/new.txt")
		root.resolve("docs/old").moveTo(root.resolve("music/old"))
		root.resolve("music/c.mp3").deleteExisting()

		val after =
			before.with(
				listOfNotNull(
					indexer.reread(where("docs/new.txt")),
					indexer.reread(where("docs/old")),
					indexer.reread(where("music/old")),
					indexer.reread(where("music/c.mp3")),
				),
			)

		val fresh = Index.EMPTY.with(indexer.index(root))

		assertTrue(after.loose > 0)
		assertEquals(fresh.shape(), after.shape())
		assertEquals(0, after.compacted().loose)
		assertEquals(fresh.shape(), after.compacted().shape())
		assertEquals(
			setOf("docs/new.txt", "music/old/b.txt", "docs/a.txt"),
			after.found {
				+word("*.txt")
			},
		)
		assertEquals(after.found { +word("*") }, after.compacted().found { +word("*") })
		assertEquals(fresh.found { +word("*") }, after.found { +word("*") })
	}

	@Test
	fun aChangeWhereNothingIsHeldIsDropped() {
		made("kept/a.txt", "node_modules/pkg/index.js")

		val index = Index.EMPTY.with(indexer.index(root))

		// Under a folder the rules leave out: the index does not hold it, so it does not hold what is in it.
		made("node_modules/pkg/more.js")
		assertEquals(
			index.shape(),
			index.with(indexer.reread(where("node_modules/pkg/more.js"))!!).shape(),
		)
		// The rules say so of the folder itself, too: there is nothing to change.
		assertNull(indexer.reread(where("node_modules")))
		// Outside every root.
		elsewhere.resolve("x.txt").writeText("x")
		assertEquals(
			index.shape(),
			index.with(indexer.reread(Where.of(elsewhere.resolve("x.txt")))!!).shape(),
		)
	}

	@Test
	fun aRootIsReplacedByOneAboveItAndGoneWithWhatIsGone() {
		made("outer/inner/a.txt", "outer/b.txt")

		val inner = Index.EMPTY.with(indexer.index(root.resolve("outer/inner")))

		assertEquals(setOf(where("outer/inner")), inner.roots)

		// Indexing a folder above takes the root in.
		val outer = inner.with(indexer.index(root.resolve("outer")))

		assertEquals(setOf(where("outer")), outer.roots)
		assertEquals(setOf("outer/inner/a.txt", "outer/b.txt"), outer.found { +word("*.txt") })

		// Indexing a folder already held reads it again, and makes no root of it.
		assertEquals(
			setOf(where("outer")),
			outer.with(indexer.index(root.resolve("outer/inner"))).roots,
		)

		// A root gone is no longer a root.
		assertEquals(emptySet<Where>(), outer.with(Change.Absent(where("outer"))).roots)
		assertEquals(0, outer.with(Change.Absent(where("outer"))).size)
	}

	@Test
	fun devicesPipesAndSocketsAreNotHeld() {
		made("a.txt")
		ServerSocketChannel.open(StandardProtocolFamily.UNIX).use {
			it.bind(UnixDomainSocketAddress.of(root.resolve("socket")))

			val shape = Index.EMPTY.with(indexer.index(root)).shape()

			assertTrue(Files.exists(root.resolve("socket")))
			assertEquals(listOf("/ Directory", "/a.txt File"), shape)
		}
	}

	@Test
	fun theRulesLeaveOutNamesPathEndingsPathsAndFileSystems() {
		made(
			"keep/a.txt",
			"keep/.local/share/containers/layer/x",
			"share/containers/y.txt",
			"skip/me.txt",
			"fake-proc/1/status",
		)

		val rules =
			IgnoreRules.DEFAULT.alsoIgnoring(
				listOf(Ignored.of(root.resolve("skip").toString())),
			)

		val mounts = listOf(Mount(root.resolve("fake-proc"), "proc", 0, 99))
		val index = Index.EMPTY.with(Indexer(rules, mounts).index(root))

		assertEquals(
			setOf("keep/a.txt", "share/containers/y.txt"),
			index.found {
				+isA(barion.index.query.Flag.File)
			},
		)
		assertNull(index.find(where("keep/.local/share/containers")))
		assertNull(index.find(where("skip")))
		assertNull(index.find(where("fake-proc")))
		assertFalse(index.find(where("keep/.local/share")) == null)
	}

	@Test
	fun aFolderIsFoundByItsDeviceAndInodeAsTheWatcherNamesIt() {
		made("docs/a.txt", "music/b.mp3")

		fun idOf(path: String): FileId =
			statx(Fd.CWD, root.resolve(path).toString()).orThrow().let { FileId(it.dev, it.ino) }

		val index = Index.EMPTY.with(indexer.index(root))

		// Every entry's inode is the one on disk.
		assertEquals(idOf("docs/a.txt").ino, index.find(where("docs/a.txt"))!!.inode)
		assertEquals(listOf(where("docs")), index.foldersAt(idOf("docs")))
		assertEquals(listOf(Where.of(root)), index.foldersAt(idOf(".")))
		// A file is not a folder a change could happen in.
		assertEquals(emptyList<Where>(), index.foldersAt(idOf("docs/a.txt")))

		// Moved, the folder keeps its inode, and is found where it went.
		root.resolve("docs").moveTo(root.resolve("music/docs"))

		val moved =
			index.with(
				listOfNotNull(indexer.reread(where("docs")), indexer.reread(where("music/docs"))),
			)

		assertEquals(listOf(where("music/docs")), moved.foldersAt(idOf("music/docs")))
		assertEquals(
			listOf(where("music/docs")),
			moved.compacted().foldersAt(idOf("music/docs")),
		)
	}

	@Test
	fun aNameThatIsNotUtf8IsHeldAndFound() {
		val bytes =
			root.toString().encodeToByteArray() + "/caf".encodeToByteArray() +
				byteArrayOf(0xE9.toByte()) +
				".txt".encodeToByteArray()

		java.lang.foreign.Arena.ofConfined().use { arena ->
			val path = arena.allocate(bytes.size + 1L)

			java.lang.foreign.MemorySegment.copy(
				bytes,
				0,
				path,
				java.lang.foreign.ValueLayout.JAVA_BYTE,
				0,
				bytes.size,
			)
			assertTrue(create(path) >= 0)
		}

		val found =
			(
				Index.EMPTY.with(indexer.index(root)).search(
					query {
						+word("caf")
					},
				) as Searched.Found
			).results

		assertEquals(1, found.size)
		assertTrue(
			found
				.single()
				.where.bytes
				.contentEquals(bytes),
		)
		assertNull(found.single().where.path)
	}
}

/** `creat(2)` of a path given as raw bytes, for a name the JVM cannot spell. */
private fun create(path: java.lang.foreign.MemorySegment): Int {
	val linker =
		java.lang.foreign.Linker
			.nativeLinker()

	val handle =
		linker.downcallHandle(
			linker.defaultLookup().find("creat").orElseThrow(),
			java.lang.foreign.FunctionDescriptor.of(
				java.lang.foreign.ValueLayout.JAVA_INT,
				java.lang.foreign.ValueLayout.ADDRESS,
				java.lang.foreign.ValueLayout.JAVA_INT,
			),
		)

	return handle.invokeExact(path, 0x1A4) as Int
}
