package prism.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.index.FileId
import prism.index.IgnoreRules
import prism.index.Index
import prism.index.IndexFile
import prism.index.IndexRead
import prism.index.Indexer
import prism.index.Name
import prism.linux.Fd
import prism.linux.orThrow
import prism.linux.statx
import prism.platform.Dev
import prism.platform.FileKind
import prism.platform.KernelDev
import prism.watcher.Done
import prism.watcher.EntryName
import prism.watcher.Observed
import prism.watcher.Present

/**
 * The daemon: its feed started before its index is read, so a change made while the index is read is not missed;
 * lost changes have the roots read again; and without a watcher, it runs all the same. Its feed here is the test's,
 * since this machine's kernel may not load the watcher; one test runs the kernel's own, which either runs or says why.
 */
class DaemonTest {
	@TempDir
	lateinit var root: Path

	@TempDir
	lateinit var state: Path

	private val indexer = Indexer(mounts = emptyList())

	private fun made(vararg files: String) =
		files.forEach {
			root
				.resolve(it)
				.also { path ->
					path.parent.createDirectories()
				}.writeText(it)
		}

	private fun idOf(path: String): FileId =
		statx(Fd.CWD, root.resolve(path).toString()).orThrow().let {
			FileId(KernelDev(it.dev), it.ino)
		}

	private fun Index.names(): Set<String> =
		entries()
			.map {
				it.where.toString().removePrefix("$root/")
			}.toSet()

	private fun settings() =
		DaemonSettings(
			file = state.resolve("index.prism"),
			roots = listOf(root),
			indexer = indexer,
			tick = 100.milliseconds,
		)

	/** Waits, up to ten seconds, for [what] to be so. */
	private fun eventually(what: () -> Boolean) {
		val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)

		while (!what()) {
			check(System.nanoTime() < until) { "never so" }
			Thread.sleep(20)
		}
	}

	/** A feed of what the test gives it, counting what it was told it lost. */
	private class Given : Feed {
		var seen: (List<Changed>) -> Unit = {}
		val lost = AtomicLong()
		val order: MutableList<String> = Collections.synchronizedList(ArrayList())

		override fun start(seen: (List<Changed>) -> Unit): Feeding {
			order += "watching"
			this.seen = seen

			return Feeding.On(stop = AutoCloseable { }, lost = lost::get, stopped = { null })
		}
	}

	@Test
	fun theWatcherStartsFirstSoAChangeMadeWhileTheIndexIsReadIsNotMissed() {
		made("docs/a.txt")

		val feed = Given()
		val read = CountDownLatch(1)
		val go = CountDownLatch(1)

		Daemon
			.start(settings(), feed, null) {
				feed.order += "read"
				read.countDown()
				go.await()
			}.use { daemon ->
				read.await()
				assertEquals(listOf("watching", "read"), feed.order.toList())

				// While what was read waits to be made, a file is made, and the watcher sees it.
				made("docs/late.txt")
				feed.seen(listOf(Changed(idOf("docs"), Name.of("late.txt"))))
				go.countDown()
				daemon.live.opened.get(30, TimeUnit.SECONDS)

				eventually { "docs/late.txt" in daemon.live.index.names() }
				assertEquals(Daemon.Watching.On, daemon.watching)
			}
	}

	@Test
	fun changesTheWatcherLostHaveTheRootsReadAgain() {
		made("docs/a.txt")

		val feed = Given()

		Daemon.start(settings(), feed).use { daemon ->
			daemon.live.opened.get(30, TimeUnit.SECONDS)

			// Made without the watcher saying so: it lost the change.
			made("docs/unseen.txt")
			feed.lost.incrementAndGet()

			eventually { "docs/unseen.txt" in daemon.live.index.names() }
		}
	}

	@Test
	fun withoutAWatcherTheDaemonRunsAllTheSame() {
		made("docs/a.txt")

		Daemon.start(settings(), Feed { Feeding.Off("no watcher here") }).use { daemon ->
			assertEquals(Daemon.Watching.Off("no watcher here"), daemon.watching)
			daemon.live.opened.get(30, TimeUnit.SECONDS)
			assertTrue("docs/a.txt" in daemon.live.index.names())
		}
	}

	@Test
	fun aWatcherThatFailsToStartIsReportedAndTheDaemonRunsWithout() {
		made("docs/a.txt")

		val reported = CopyOnWriteArrayList<Pair<String, Throwable>>()

		val settings =
			settings().copy(report = {
				doing,
				failure,
				->
				reported += doing to failure
			})

		Daemon.start(settings, Feed { error("no libbpf") }).use { daemon ->
			assertEquals(
				Daemon.Watching.Off(
					"the watcher couldn't start: java.lang.IllegalStateException: no libbpf",
				),
				daemon.watching,
			)
			daemon.live.opened.get(30, TimeUnit.SECONDS)
			assertTrue("docs/a.txt" in daemon.live.index.names())
		}

		assertEquals(listOf("starting the watcher"), reported.map { it.first })
	}

	@Test
	fun theIndexIsWrittenOnceItIsFirstRead_ThenOnlyAsOftenAsIsSaid_AndAsTheDaemonCloses() {
		made("docs/a.txt")

		val file = state.resolve("index.prism")
		val feed = Given()

		fun onDisk(): Set<String> = (IndexFile.read(file) as IndexRead.Read).index.names()

		// Written once an hour, which no test waits for: so what is written meanwhile is what the first read wrote.
		Daemon.start(settings(), feed).use { daemon ->
			daemon.live.opened.get(30, TimeUnit.SECONDS)
			eventually { Files.exists(file) }
			assertTrue("docs/a.txt" in onDisk())

			made("docs/b.txt")
			feed.seen(listOf(Changed(idOf("docs"), Name.of("b.txt"))))
			eventually { "docs/b.txt" in daemon.live.index.names() }
			// Many ticks on, the change is in the index and not in its file.
			Thread.sleep(500)
			assertTrue("docs/b.txt" !in onDisk())
		}
		// Closed, it is: one snapshot, with nothing after it.
		assertTrue("docs/b.txt" in onDisk())
		assertEquals(0L, (IndexFile.read(file) as IndexRead.Read).changes)

		// Told to write more often, it does, while it runs.
		Daemon.start(settings().copy(write = 200.milliseconds), feed).use { daemon ->
			daemon.live.opened.get(30, TimeUnit.SECONDS)
			made("docs/c.txt")
			feed.seen(listOf(Changed(idOf("docs"), Name.of("c.txt"))))
			eventually { "docs/c.txt" in onDisk() }
		}
	}

	@Test
	fun anIndexThatCannotBeWrittenIsSaidOnceAndKeptCurrentAllTheSame() {
		made("docs/a.txt")

		// The index's file is to be in a folder that is a file: it can never be written.
		val reported = CopyOnWriteArrayList<Pair<String, Throwable>>()
		val blocked = state.resolve("blocked").also { it.writeText("") }

		val settings =
			settings().copy(
				file = blocked.resolve("index.prism"),
				report = { doing, failure -> reported += doing to failure },
			)

		val feed = Given()

		Daemon.start(settings, feed).use { daemon ->
			daemon.live.opened.get(30, TimeUnit.SECONDS)
			assertTrue("docs/a.txt" in daemon.live.index.names())

			// Changes are made in it as they are seen, written or not.
			made("docs/b.txt")
			feed.seen(listOf(Changed(idOf("docs"), Name.of("b.txt"))))
			eventually { "docs/b.txt" in daemon.live.index.names() }

			// Many ticks on, each of which failed to write, it has been said once.
			eventually { reported.isNotEmpty() }
			Thread.sleep(600)
			assertEquals(listOf("writing the index"), reported.map { it.first })
		}

		// Closing tried once more, and said so: nothing was lost that the next start does not read again.
		assertEquals(
			listOf("writing the index", "writing the index"),
			reported.map { it.first },
		)
	}

	@Test
	fun theKernelsOwnWatcherRunsOrSaysWhyNot() {
		made("docs/a.txt")

		Daemon.start(settings()).use { daemon ->
			when (val watching = daemon.watching) {
				Daemon.Watching.On -> Unit
				is Daemon.Watching.Off -> assertTrue(watching.why.isNotBlank())
			}
			daemon.live.opened.get(30, TimeUnit.SECONDS)
			assertTrue("docs/a.txt" in daemon.live.index.names())
		}
	}

	@Test
	fun whatTheWatcherSeesIsTheFolderItWasInAndTheEntry() {
		val dev = KernelDev(Dev(8, 1))
		val entry = Present(7, FileKind.Regular)

		fun seen(what: Done) =
			Observed(FileId(dev, 10), EntryName("a.txt".encodeToByteArray()), what, 1, 0)

		val name = Name.of("a.txt")

		assertEquals(
			listOf(Changed(FileId(dev, 10), name)),
			changedOf(seen(Done.Made(Done.Making.File, entry))),
		)
		assertEquals(
			listOf(Changed(FileId(dev, 10), name)),
			changedOf(seen(Done.Removed(Done.Removing.Directory))),
		)
		// A move gives only the entry's new name: the folder it left is read again.
		assertEquals(
			listOf(Changed(FileId(dev, 10), null), Changed(FileId(dev, 20), name)),
			changedOf(seen(Done.Moved(FileId(dev, 20), swapped = false, entry))),
		)
		// A move to where the record does not say: the folder it left, read again.
		assertEquals(
			listOf(Changed(FileId(dev, 10), null)),
			changedOf(seen(Done.Left(swapped = false))),
		)
		// A swap: both folders.
		assertEquals(
			listOf(Changed(FileId(dev, 10), null), Changed(FileId(dev, 20), null)),
			changedOf(seen(Done.Moved(FileId(dev, 20), swapped = true, entry))),
		)
	}

	@Test
	fun aFeedsPlaceIsKeptOnceTheIndexIsSettledAndReadAsItWasKept() {
		made("docs/a.txt")

		val history = History.beside(state.resolve("index.prism"))
		val at = AtomicLong(7)

		val feed =
			Feed {
				Feeding.On(
					stop = AutoCloseable { },
					lost = { 0 },
					stopped = { null },
					at = at::get,
				)
			}

		Daemon.start(settings(), feed, history) {}.use { daemon ->
			daemon.live.opened.get()
			eventually { history.read()?.id == 7L }

			// A place is of the volumes and the rules it was kept with.
			assertEquals(volumesOf(settings().live.mounts()), history.read()?.of)
			assertEquals(rulesOf(daemon.live.rules), history.read()?.rules)

			at.set(9)
			eventually { history.read()?.id == 9L }
		}
	}

	@Test
	fun aPlaceIsKeptWithItsRulesARuleToALine() {
		val one = IgnoreRules.DEFAULT
		val other = one.copy(directories = one.directories.reversed().toSet())

		assertEquals(rulesOf(one), rulesOf(other))
		// A rule added is rules the same and more: what a daemon may take up from.
		assertTrue(rulesOf(one).containsAll(rulesOf(one.copy(directories = emptySet()))))
		assertNotEquals(rulesOf(one), rulesOf(one.copy(directories = emptySet())))

		val history = History.beside(state.resolve("index.prism"))

		assertEquals(null, history.read())
		history.write(Place("abc", rulesOf(one), 42))
		assertEquals(Place("abc", rulesOf(one), 42), history.read())
		history.forget()
		assertEquals(null, history.read())
	}
}
