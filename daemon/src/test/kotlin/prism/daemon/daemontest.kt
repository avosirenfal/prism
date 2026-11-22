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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.index.FeedKind
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
import prism.macos.BundleDisk
import prism.macos.BundlePath
import prism.macos.Catalogue
import prism.platform.Dev
import prism.platform.FileKind
import prism.platform.KernelDev
import prism.platform.Mount
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
	fun theBundlesTheIndexHoldsAreToldOfEachTimeItIsWhole() {
		made("Applications/Safari.app/Contents/Info.plist", "docs/a.txt")

		val feed = Given()
		val catalogue = KeptCatalogue(Disk)

		fun kept(): Set<String> =
			when (val now = catalogue.now) {
				Catalogue.Unread -> emptySet()
				is Catalogue.Read ->
					now.kept.bundles
						.map { root.relativize(it.path).toString() }
						.toSet()
			}

		Daemon.start(settings().copy(telling = catalogue.telling), feed).use { daemon ->
			daemon.live.opened.get(30, TimeUnit.SECONDS)
			eventually { kept() == setOf("Applications/Safari.app") }

			// Made without the watcher saying so: the roots are read again, and what they hold told of again.
			made("Applications/Zed.app/Contents/Info.plist")
			feed.lost.incrementAndGet()

			eventually { kept() == setOf("Applications/Safari.app", "Applications/Zed.app") }
		}
	}

	/** The disk as it is, for bundles: they are folders, and none is moved in while a test runs. */
	private object Disk : BundleDisk {
		override fun holds(bundle: BundlePath): Boolean = Files.isDirectory(bundle.path)

		override fun bundlesIn(folder: Path): List<BundlePath> = emptyList()
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
	fun notScanningOnStartReadsNothingUntilTheRootsAreAskedToBeRead() {
		made("docs/a.txt")

		val upkeep = Upkeep(scanOnStart = false, watch = false, fallbackScans = false)

		Daemon.start(settings().copy(upkeep = upkeep), Given()).use { daemon ->
			daemon.live.opened.get(30, TimeUnit.SECONDS)
			assertEquals(emptySet<String>(), daemon.live.index.names())

			// Rebuilt by hand, the roots are read though the index never held them.
			daemon.rescanRoots().get(30, TimeUnit.SECONDS)
			assertTrue("docs/a.txt" in daemon.live.index.names())
		}
	}

	@Test
	fun watchingTurnedOffStartsNoFeed_AndTurnedOnAndOffAsTheDaemonRunsStartsAndStopsIt() {
		made("docs/a.txt")

		val stopped = AtomicLong()

		val feed =
			object : Feed {
				val started = AtomicLong()
				var seen: (List<Changed>) -> Unit = {}

				override fun start(seen: (List<Changed>) -> Unit): Feeding {
					started.incrementAndGet()
					this.seen = seen

					return Feeding.On(
						stop = AutoCloseable { stopped.incrementAndGet() },
						lost = { 0 },
						stopped = { null },
					)
				}
			}

		Daemon.start(settings().copy(upkeep = Upkeep(watch = false)), feed).use { daemon ->
			daemon.live.opened.get(30, TimeUnit.SECONDS)
			assertEquals(Daemon.Watching.TurnedOff, daemon.watching)
			assertEquals(0, feed.started.get())

			daemon.upkeep(Upkeep(watch = true))
			eventually { daemon.watching == Daemon.Watching.On }
			assertEquals(1, feed.started.get())

			// Started again, what it hears is made.
			made("docs/late.txt")
			feed.seen(listOf(Changed(idOf("docs"), Name.of("late.txt"))))
			eventually { "docs/late.txt" in daemon.live.index.names() }

			daemon.upkeep(Upkeep(watch = false))
			eventually { daemon.watching == Daemon.Watching.TurnedOff }
			assertEquals(1, stopped.get())
			assertEquals(Upkeep(watch = false), daemon.upkeep)
		}
	}

	@Test
	fun withoutAWatcherTheRootsAreReadAgainNowAndThen_UnlessFallbackScansAreOff() {
		made("docs/a.txt")

		val often = settings().copy(rescanWithoutWatcher = 200.milliseconds)

		Daemon.start(often, Feed { Feeding.Off("no watcher here") }).use { daemon ->
			daemon.live.opened.get(30, TimeUnit.SECONDS)
			made("docs/unseen.txt")
			eventually { "docs/unseen.txt" in daemon.live.index.names() }

			daemon.upkeep(Upkeep(fallbackScans = false))
			// Whatever scan was under way when it was turned off has landed by now.
			Thread.sleep(500)
			made("docs/never.txt")
			Thread.sleep(1_000)
			assertFalse("docs/never.txt" in daemon.live.index.names())
		}
	}

	@Test
	fun aFeedThatStopsIsReattached_AndWhatChangedMeanwhileIsRead() {
		made("docs/a.txt")

		/** A feed whose first watcher goes quiet (its loader pinned another), and whose next is read. */
		val feed =
			object : Feed {
				val started = AtomicLong()
				val replaced =
					java.util.concurrent.atomic
						.AtomicBoolean()
				var seen: (List<Changed>) -> Unit = {}

				override fun start(seen: (List<Changed>) -> Unit): Feeding {
					val which = started.incrementAndGet()

					this.seen = seen

					return Feeding.On(
						stop = AutoCloseable { },
						lost = { 0 },
						stopped = {
							"the watcher pinned at /run/prism-watcher/x was replaced by a new one"
								.takeIf { which == 1L && replaced.get() }
						},
						through = "probe pinned at /run/prism-watcher/x",
						kind = FeedKind.EbpfProbe,
					)
				}
			}

		val said = CopyOnWriteArrayList<String>()

		Daemon.start(settings().copy(say = { said += it }), feed).use { daemon ->
			daemon.live.opened.get(30, TimeUnit.SECONDS)

			// The loader run again: the watcher read tells of nothing more, and a file is made meanwhile, unheard.
			feed.replaced.set(true)
			made("docs/unheard.txt")

			eventually { feed.started.get() == 2L }
			eventually { "docs/unheard.txt" in daemon.live.index.names() }
			assertEquals(Daemon.Watching.On, daemon.watching)
			assertEquals(
				listOf(
					"changes: live monitoring stopped (the watcher pinned at " +
						"/run/prism-watcher/x was replaced by a new one); reattaching",
					"changes: live monitoring via eBPF again (probe pinned at " +
						"/run/prism-watcher/x); rescanning for changes missed meanwhile",
				),
				said.toList(),
			)

			// And what the new one hears is made, as ever.
			made("docs/heard.txt")
			feed.seen(listOf(Changed(idOf("docs"), Name.of("heard.txt"))))
			eventually { "docs/heard.txt" in daemon.live.index.names() }
		}
	}

	@Test
	fun aFeedThatWillNotStartIsTriedAgain_AndOnceItStartsTheRootsAreRead() {
		made("docs/a.txt")

		val ready =
			java.util.concurrent.atomic
				.AtomicBoolean()
		val said = CopyOnWriteArrayList<String>()
		val feed =
			Feed {
				if (ready.get())
					Feeding.On(stop = AutoCloseable { }, lost = { 0 }, stopped = { null })
				else
					Feeding.Off("nothing is pinned yet")
			}

		Daemon
			.start(settings().copy(reattach = 200.milliseconds, say = { said += it }), feed)
			.use { daemon ->
				daemon.live.opened.get(30, TimeUnit.SECONDS)
				eventually { said.isNotEmpty() }
				// Said once, however many times it is tried.
				Thread.sleep(600)
				assertEquals(
					listOf(
						"changes: live monitoring isn't running (nothing is pinned yet); trying again every 200ms",
					),
					said.toList(),
				)

				// The loader runs after the daemon started: from then on, watched, and what changed before read.
				made("docs/before.txt")
				ready.set(true)
				eventually { daemon.watching == Daemon.Watching.On }
				eventually { "docs/before.txt" in daemon.live.index.names() }
			}
	}

	@Test
	fun aFeedThatSaysWhenToLookAgainIsLookedAtThenAndNotEverySoOften() {
		made("docs/a.txt")

		/** A feed whose loader the test runs, and which says so, as the pins being watched would. */
		val feed =
			object : Feed {
				val pinned =
					java.util.concurrent.atomic
						.AtomicInteger()
				val starts = AtomicLong()
				var notice: () -> Unit = {}

				override fun start(seen: (List<Changed>) -> Unit): Feeding {
					starts.incrementAndGet()

					val which = pinned.get()

					if (which == 0)
						return Feeding.Off("nothing is pinned yet")

					val replaced =
						java.util.concurrent.atomic
							.AtomicReference<String?>()

					return Feeding.On(
						stop = AutoCloseable { },
						lost = { 0 },
						stopped = { replaced.get() },
						recheck = {
							replaced.set("replaced".takeIf { pinned.get() != which })
						},
					)
				}

				override fun notices(changed: () -> Unit): AutoCloseable {
					notice = changed

					return AutoCloseable { }
				}
			}

		// Tried again every hour, were nothing to say when: what it says is what is waited on.
		Daemon.start(settings().copy(reattach = 1.hours, say = {}), feed).use { daemon ->
			daemon.live.opened.get(30, TimeUnit.SECONDS)
			assertTrue(daemon.watching is Daemon.Watching.Off)

			// The loader pins a watcher, and the feed says so: started, and what changed before it read.
			made("docs/before.txt")
			feed.pinned.set(1)
			feed.notice()
			eventually { daemon.watching == Daemon.Watching.On }
			eventually { "docs/before.txt" in daemon.live.index.names() }

			// Pinned anew under it: looked at again when told, and started again.
			val starts = feed.starts.get()

			feed.pinned.set(2)
			feed.notice()
			eventually { feed.starts.get() == starts + 1 }
			assertEquals(Daemon.Watching.On, daemon.watching)
		}
	}

	@Test
	fun theKernelsOwnWatcherRunsOrSaysWhyNot() {
		made("docs/a.txt")

		Daemon.start(settings()).use { daemon ->
			when (val watching = daemon.watching) {
				Daemon.Watching.On -> Unit
				is Daemon.Watching.Off -> assertTrue(watching.why.isNotBlank())
				Daemon.Watching.TurnedOff -> error("watching is on unless it is turned off")
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
	fun aFeedsPlaceIsKeptEachTimeTheIndexIsWritten() {
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

		// Written at every tick, here, where a daemon left to itself writes once an hour.
		Daemon
			.start(settings().copy(write = 100.milliseconds), feed, history) {}
			.use { daemon ->
				daemon.live.opened.get()
				eventually { history.read()?.id == 7L }

				// A place is of the volumes and the rules it was kept with.
				assertEquals(volumesOf(settings().live.mounts()), history.read()?.of)
				assertEquals(rulesOf(daemon.live.rules), history.read()?.rules)

				at.set(9)
			}

		// Closing writes the index, and the place with it.
		assertEquals(9L, history.read()?.id)
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

	@Test
	fun aFolderTheIndexLeavesOutIsListenedToThroughTheFeed() {
		made("docs/a.txt")

		// A folder that is under no root of the index, as the thumbnails' is: the feed sees what changes in it all
		// the same, and whoever listens for it is told, by name.
		val cache = state.resolve("cache/thumbnails/large").createDirectories()
		val other = state.resolve("cache/other").createDirectories()

		// The one file system there is to the test: what the folder's device is the kernel's device of.
		val mounted =
			listOf(
				Mount(
					1,
					KernelDev(statx(Fd.CWD, "$cache").orThrow().dev),
					"/",
					Path.of("/"),
					"ext4",
					"/dev/x",
				),
			)

		val listening = Listening { mounted }
		val told = CopyOnWriteArrayList<Set<String>?>()
		val feed = Given()

		fun id(folder: Path) =
			statx(Fd.CWD, "$folder").orThrow().let { FileId(KernelDev(it.dev), it.ino) }

		assertEquals(id(cache), Listening.idOf(cache, mounted))

		Daemon
			.start(
				settings().copy(telling = Telling(changed = listening::heard)),
				feed,
			).use { daemon ->
				daemon.live.opened.get(30, TimeUnit.SECONDS)

				val watch = listening.watch(cache) { told += it }

				feed.seen(
					listOf(
						Changed(id(cache), Name.of("0123.png")),
						Changed(idOf("docs"), Name.of("b.txt")),
						Changed(id(other), Name.of("elsewhere.png")),
						Changed(id(cache), Name.of("4567.png")),
					),
				)
				assertEquals(listOf<Set<String>?>(setOf("0123.png", "4567.png")), told.toList())
				// A change that names no entry is of the folder as a whole.
				feed.seen(listOf(Changed(id(cache), Name.of("89ab.png")), Changed(id(cache), null)))
				assertEquals(null, told.last())
				// The index was given every change too, and holds nothing of the folder it leaves out.
				assertTrue(
					daemon.live.index
						.names()
						.none { "thumbnails" in it },
				)

				// No longer listened to, nothing more is told; a folder that is not there is not listened to at all.
				watch.close()
				feed.seen(listOf(Changed(id(cache), Name.of("cdef.png"))))
				assertEquals(2, told.size)
				listening.watch(state.resolve("gone")) { told += it }.close()
			}
		assertEquals(null, Listening.idOf(cache, emptyList()))

		// One who is told and fails does not stop the feed, nor the index: it is said, and the change is made.
		val reported = CopyOnWriteArrayList<String>()
		val given = Given()

		Daemon
			.start(
				settings().copy(
					telling = Telling(changed = { error("a listener that fails") }),
					report = { doing, _ -> reported += doing },
				),
				given,
			).use { daemon ->
				daemon.live.opened.get(30, TimeUnit.SECONDS)
				made("docs/late.txt")
				given.seen(listOf(Changed(idOf("docs"), Name.of("late.txt"))))
				eventually { "docs/late.txt" in daemon.live.index.names() }
				assertEquals(listOf("telling of 1 changes"), reported.toList())
			}
	}
}
