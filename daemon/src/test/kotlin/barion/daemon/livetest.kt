package barion.daemon

import barion.index.FileId
import barion.index.Index
import barion.index.IndexStore
import barion.index.Indexer
import barion.index.Name
import barion.index.Where
import barion.index.query.query
import barion.linux.Fd
import barion.linux.orThrow
import barion.linux.statx
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A live index: opened at once and read behind; read again where it is told; changes made as they are said, and made
 * again once a scan they happened during lands; places the rules leave out read for searches alone, in memory, never
 * written; and nothing written by one kept in memory alone.
 */
class LiveIndexTest {
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

	/** [path]'s folder as the watcher names it: its device and inode. */
	private fun idOf(path: String): FileId =
		statx(Fd.CWD, root.resolve(path).toString()).orThrow().let {
			FileId(it.dev, it.ino)
		}

	private fun Index.names(): Set<String> =
		entries()
			.map {
				it.where.toString().removePrefix("$root/")
			}.toSet()

	private fun fresh(): Set<String> = Index.EMPTY.with(indexer.index(root)).names()

	private fun <T> java.util.concurrent.Future<T>.done(): T = get(30, TimeUnit.SECONDS)

	@Test
	fun anIndexOpensAtOnceAndReadsAgainWhereItIsTold() {
		made("docs/a.txt")

		LiveIndex.open(file, listOf(root), indexer).use { live ->
			assertEquals(IndexStore.Origin.Missing, live.origin)
			live.opened.done()
			assertEquals(fresh(), live.index.names())
			// Written once read.
			assertTrue(file.exists())
		}

		// Opened again, its roots not read again: what the file holds, until it is told to read a place again.
		made("docs/b.txt")

		val asIs = LiveSettings(readRootsOnOpen = false)

		LiveIndex.open(file, listOf(root), indexer, asIs).use { live ->
			live.opened.done()
			assertEquals(IndexStore.Origin.Read(torn = false), live.origin)
			assertFalse("docs/b.txt" in live.index.names())

			live.rescan(listOf(root.resolve("docs"))).done()
			assertEquals(fresh(), live.index.names())

			// A file, as well as a folder.
			made("docs/c.txt")
			live.rescan(listOf(root.resolve("docs/c.txt"))).done()
			assertTrue("docs/c.txt" in live.index.names())
			root.resolve("docs/c.txt").deleteExisting()
			live.rescan(listOf(root.resolve("docs/c.txt"))).done()
			assertFalse("docs/c.txt" in live.index.names())
		}
	}

	@Test
	fun changesAreMadeAsTheyAreSaid() {
		made("docs/a.txt", "other/b.txt")

		LiveIndex.open(null, listOf(root), indexer).use { live ->
			live.opened.done()

			// Made: the entry, by name.
			made("docs/new.txt")
			live.changed(listOf(Changed(idOf("docs"), Name.of("new.txt"))))
			assertTrue("docs/new.txt" in live.index.names())

			// Moved: the folder it left, read again, and the entry where it went.
			root.resolve("docs/new.txt").moveTo(root.resolve("other/new.txt"))
			live.changed(
				listOf(Changed(idOf("docs"), null), Changed(idOf("other"), Name.of("new.txt"))),
			)
			assertEquals(fresh(), live.index.names())
		}
	}

	@Test
	fun aChangeMadeWhileAScanReadsIsMadeAgainOnceTheScanLands() {
		made("docs/a.txt", "docs/b.txt")

		val read = CountDownLatch(1)
		val go = CountDownLatch(1)

		LiveIndex
			.open(file, listOf(root), indexer, LiveSettings()) {
				read.countDown()
				go.await()
			}.use { live ->
				// The scan has read the disk, and waits to make what it read.
				read.await()

				// Meanwhile a file is made and another removed, and said to be. The index does not hold the folder yet,
				// so nothing can be made of it now.
				made("docs/late.txt")
				root.resolve("docs/a.txt").deleteExisting()
				live.changed(
					listOf(
						Changed(idOf("docs"), Name.of("late.txt")),
						Changed(idOf("docs"), Name.of("a.txt")),
					),
				)

				go.countDown()
				live.opened.done()

				// What the scan read did not have the one, and had the other; made again once it landed, they are right.
				assertEquals(fresh(), live.index.names())
				assertTrue("docs/late.txt" in live.index.names())
				assertFalse("docs/a.txt" in live.index.names())
			}
	}

	@Test
	fun aPlaceTheRulesLeaveOutIsReadForSearchesAloneInMemoryNeverWritten() {
		made(
			"proj/src/a.js",
			"proj/node_modules/pkg/index.js",
			"proj/node_modules/pkg/node_modules/dep/x.js",
		)

		LiveIndex.open(file, listOf(root), indexer).use { live ->
			live.opened.done()
			assertNull(live.index.find(where("proj/node_modules")))

			val asked =
				query {
					+path("$root/proj/node_modules/")
					+word("*.js")
				}
			val first = live.search(asked) as LiveSearch.Found

			// The place is read for the search, which goes on without it meanwhile.
			assertEquals(setOf(where("proj/node_modules")), first.reading)
			first.ready.done()

			val found = (live.search(asked) as LiveSearch.Found).results.map { it.where }.toSet()

			// Read with the rule that left it out set aside, so a node_modules within it is read too.
			assertEquals(
				setOf(
					where("proj/node_modules/pkg/index.js"),
					where("proj/node_modules/pkg/node_modules/dep/x.js"),
				),
				found,
			)
			// And kept current, as the index is.
			made("proj/node_modules/pkg/new.js")
			live.changed(listOf(Changed(idOf("proj/node_modules/pkg"), Name.of("new.js"))))
			assertTrue(
				where("proj/node_modules/pkg/new.js") in
					(live.search(asked) as LiveSearch.Found).results.map { it.where },
			)

			// Never in the index proper, nor written with it.
			live.flush()
			assertNull(live.index.find(where("proj/node_modules")))
			assertNull(IndexStore.open(file).index.find(where("proj/node_modules")))

			// Unasked for long enough, it is let go, and read again when it is asked for again.
			live.tidy(Instant.now().plusSeconds(3600))
			assertEquals(
				setOf(where("proj/node_modules")),
				(live.search(asked) as LiveSearch.Found).reading,
			)
		}
	}

	@Test
	fun anIndexInMemoryWritesNothing() {
		made("a.txt")

		LiveIndex.open(null, listOf(root), indexer).use { live ->
			live.opened.done()
			live.flush()
			assertEquals(IndexStore.Origin.Memory, live.origin)
			assertTrue("a.txt" in live.index.names())
		}
		assertEquals(emptyList<Path>(), Files.list(state).use { it.toList() })
	}

	@Test
	fun rootsNoLongerAskedForAreLetGo() {
		made("a/x.txt", "b/y.txt")

		LiveIndex.open(file, listOf(root.resolve("a"), root.resolve("b")), indexer).use {
			it.opened.done()
		}

		LiveIndex
			.open(
				file,
				listOf(root.resolve("a")),
				indexer,
				LiveSettings(readRootsOnOpen = false),
			).use { live ->
				assertEquals(setOf(where("a")), live.index.roots)
				assertNull(live.index.find(where("b/y.txt")))
			}
	}

	@Test
	fun aSearchThatCannotBeRunIsRefused() {
		LiveIndex.open(null, listOf(root), indexer).use { live ->
			assertInstanceOf(
				LiveSearch.Refused::class.java,
				live.search(
					query {
						+path("~nowhere/")
					},
				),
			)
		}
	}
}
