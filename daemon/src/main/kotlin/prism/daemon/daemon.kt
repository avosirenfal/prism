package prism.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import prism.index.FileId
import prism.index.IgnoreRules
import prism.index.Index
import prism.index.IndexStore
import prism.index.Indexer
import prism.index.Name
import prism.index.Where
import prism.macos.EventStream
import prism.macos.FsEvent
import prism.macos.FsFlag
import prism.macos.devOf
import prism.platform.KernelDev
import prism.platform.Os
import prism.shared.Once
import prism.shared.Published
import prism.watcher.Done
import prism.watcher.Observed
import prism.watcher.Watcher

/** Where a daemon's changes come from: the kernel, through the watcher; or, for a test, whatever it gives. */
fun interface Feed {
	/** Changes from now on, each batch given to [seen] as it is seen; or why there will be none. */
	fun start(seen: (List<Changed>) -> Unit): Feeding

	companion object {
		/** The eBPF watcher's changes, each made into what a live index takes ([changedOf]). */
		val KERNEL =
			Feed { seen ->
				when (val started = Watcher.start { batch -> seen(batch.flatMap(::changedOf)) }) {
					is Watcher.Start.NotRunning -> Feeding.Off(started.why)
					is Watcher.Start.Running ->
						Feeding.On(
							stop = started.watcher,
							lost = started.watcher::lost,
							stopped = { started.watcher.stopped },
							through = started.through,
						)
				}
			}

		/** No feed at all, and [why]: a daemon that reads its roots again now and then, and is told nothing between. */
		fun none(why: String) = Feed { Feeding.Off(why) }

		/**
		 * FSEvents' changes under [roots], on a Mac: each entry made, removed or renamed, said of its folder and its
		 * name ([changedOf]). Where the stream could not keep up (it says to read a folder again, or that events were
		 * dropped), what was lost is counted, and the daemon reads its roots again, as for a watcher whose ring was full.
		 *
		 * The changes are those [since] an event's id, where one is given ([History]): what FSEvents recorded while
		 * no daemon ran is told first. Where its record no longer goes back that far, it says to read everything
		 * again, which is counted as lost.
		 */
		fun fsEvents(
			roots: List<Path>,
			since: Long = EventStream.SINCE_NOW,
			heard: (List<FsEvent>) -> Unit = {},
		) = Feed { seen ->
			val lost = AtomicLong(0)
			val last = AtomicLong(since)
			val stopped = Published<String?>(null)

			val stream =
				EventStream.start(
					roots,
					latency = FSEVENTS_LATENCY,
					since = since,
					failed = { stopped.set("FSEvents' changes could not be read: $it") },
				) { batch ->
					if (batch.any { event -> event.flags.any { it in FSEVENTS_LOST } })
						lost.incrementAndGet()
					seen(batch.mapNotNull(::changedOf))
					heard(batch)
					// Once given: a place kept is one whose changes were at least handed on.
					batch.maxOfOrNull { it.id }?.let { id -> last.accumulateAndGet(id, ::maxOf) }
				}

			stream?.let {
				Feeding.On(
					it,
					lost::get,
					stopped::now,
					if (since == EventStream.SINCE_NOW)
						"FSEvents"
					else
						"FSEvents, from where the last daemon left off",
					at = { last.get().takeIf { id -> id > 0 } },
				)
			}
				?: Feeding.Off("macOS would not start a stream of FSEvents")
		}

		/** The feed of the platform this runs on, of changes under [roots]: the kernel's watcher on Linux, FSEvents on a Mac. */
		fun of(roots: List<Path>, heard: (List<FsEvent>) -> Unit = {}): Feed =
			when (Os.current) {
				Os.Linux -> KERNEL
				Os.MacOS -> fsEvents(roots, heard = heard)
				Os.Windows -> none("prism has no watcher for Windows yet")
			}

		/** How long FSEvents gathers a burst of changes into one batch, in seconds. */
		private const val FSEVENTS_LATENCY = 0.2

		/** What FSEvents says when it has lost changes: a folder to read again, events dropped, or its ids begun again. */
		private val FSEVENTS_LOST =
			setOf(
				FsFlag.MustScanSubDirs,
				FsFlag.UserDropped,
				FsFlag.KernelDropped,
				FsFlag.EventIdsWrapped,
			)
	}
}

/** What starting a feed gave. */
sealed interface Feeding {
	/** Feeding: [stop] stops it; [lost] says how many changes it could not give; [stopped], why it stopped, if it did. */
	class On(
		val stop: AutoCloseable,
		val lost: () -> Long?,
		val stopped: () -> String?,
		/** What the changes come through, as it is said of a daemon: "the watcher pinned at ...". */
		val through: String = "a feed",
		/**
		 * The feed's place in the system's record of changes: the last one it gave. Null for a feed of no such
		 * record, which can only say what happens while it watches.
		 */
		val at: () -> Long? = { null },
	) : Feeding

	/** Not feeding, and [why]. */
	data class Off(
		val why: String,
	) : Feeding
}

/**
 * What a live index makes of a change the watcher saw: the folder it happened in, and the entry, by name. A move is
 * the folder it left (read again, since the move gives only the entry's new name) and the entry where it went; a swap is
 * both folders read again.
 */
fun changedOf(observed: Observed): List<Changed> {
	val name = runCatching { Name.of(observed.name.bytes()) }.getOrNull()

	return when (val what = observed.what) {
		is Done.Made, is Done.Removed -> listOf(Changed(observed.folder, name))
		// Moved to where the record does not say: the folder it left is read again, which is all there is to do.
		is Done.Left -> listOf(Changed(observed.folder, null))
		is Done.Moved ->
			listOf(
				Changed(observed.folder, null),
				Changed(what.to, name.takeUnless { what.swapped }),
			)
	}
}

/**
 * What a live index makes of an event of FSEvents': the folder it happened in, by the device and inode a stat of the
 * folder gives (as the index knows its folders), and the entry, by name. Only an entry made, removed or renamed
 * changes what the index holds; a change of what is in a file does not. None where the folder is gone too: its own
 * removal is said of the folder above it.
 */
fun changedOf(event: FsEvent): Changed? {
	if (event.flags.none { it in FSEVENTS_ENTRIES })
		return null

	val folder = event.path.parent ?: return null
	val name =
		runCatching { Name.of(event.path.fileName.toString()) }.getOrNull() ?: return null

	return try {
		val stat = Files.readAttributes(folder, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS)

		Changed(
			FileId(KernelDev(devOf((stat["dev"] as Long).toInt())), stat["ino"] as Long),
			name,
		)
	} catch (gone: IOException) {
		null
	}
}

/** What FSEvents says of an entry whose being there, or its name, changed. */
private val FSEVENTS_ENTRIES = setOf(FsFlag.Created, FsFlag.Removed, FsFlag.Renamed)

/**
 * What a daemon tells of what it sees, besides keeping the index, to what keeps something of its own of the disk (a
 * Mac's applications, which are what the index holds that is named as a bundle is). The index tells nobody of what
 * changes in it; the daemon tells of the index as a whole, and of what its feed says changed.
 */
class Telling(
	/** The index, whenever it is whole: as it opens from its file, and each time its roots have been read. */
	val whole: (Index) -> Unit = {},
	/** Each batch of FSEvents' changes, as it is seen, on a Mac: every one, whatever the index makes of it. */
	val heard: (List<FsEvent>) -> Unit = {},
) {
	companion object {
		val NOBODY = Telling()
	}
}

/** How a daemon goes about its work. */
data class DaemonSettings(
	/** The index's file; null to keep it in memory alone. */
	val file: Path?,
	/** Who is told of the index as a whole, and of each batch of changes. */
	val telling: Telling = Telling.NOBODY,
	val roots: List<Path> = listOf(Path("/")),
	val indexer: Indexer = Indexer(),
	val live: LiveSettings = LiveSettings(),
	/** How often places read for searches alone are let go, the watcher looked at, and whether it is time to write asked. */
	val tick: Duration = 5.seconds,
	/**
	 * How often the index is written to its file, whole. Not often: what is lost with what was not written is when
	 * the files made since were first seen, and nothing else, since the roots are read again as a daemon starts. It
	 * is written as a daemon closes, whenever that is.
	 */
	val write: Duration = 1.hours,
	/** How often the roots are read again where there is no watcher to say what changed. */
	val rescanWithoutWatcher: Duration = 30.minutes,
	/** How often what is mounted is looked at, for file systems that have come or gone. */
	val mounts: Duration = 2.seconds,
	/** How often the trees kept of file systems long unmounted are looked through, for those to let go. */
	val expiry: Duration = 1.days,
	/**
	 * Where what goes wrong is said, with what was being done: standard error, unless told otherwise. A daemon goes on
	 * from whatever it reports here.
	 */
	val report: (String, Throwable) -> Unit = { doing, failure ->
		System.err.println("prism: $doing failed: ${failure.stackTraceToString().trimEnd()}")
	},
)

/**
 * The index kept current: the watcher and a [LiveIndex], run together.
 *
 * The watcher starts first, before the index is read or built, so that from the moment the index's roots begin to be
 * read, every change to them is seen. What it sees is queued, and made on a thread of the daemon's own (so reading the
 * ring never waits on the disk); a change seen while a scan was reading is made again once the scan's result is, so
 * whichever the scan read, before or after the change, the index ends as the disk is. Every change says what is so, so
 * a change made twice is made once.
 *
 * Every [DaemonSettings.tick], what was made is written to the index's file, places read for searches alone that are
 * no longer asked for are let go, and the watcher is looked at: where it lost changes (its ring was full), the roots
 * are read again, since what was lost cannot be known. Without a watcher (where the kernel will not load one), the
 * daemon runs all the same, and reads its roots again every [DaemonSettings.rescanWithoutWatcher].
 *
 * **What goes wrong.** Nothing that fails here stops the daemon: each failure is reported
 * ([DaemonSettings.report]) and gone on from, by the same means as everything else is recovered from, which is reading
 * the disk again. A scan that fails is read again at the next reading of the roots; changes that could not be made
 * have the roots read again, since which were made cannot be known; a write that fails leaves what it had to write to
 * be written at the next tick, and is said once, not at every tick, until it succeeds or fails differently. Closing
 * writes what was made, whatever else closing came to.
 */
class Daemon private constructor(
	val live: LiveIndex,
	feeding: Feeding,
	private val relay: Relay,
	private val settings: DaemonSettings,
	/** Where the feed's place is kept, for the daemon started next; null where the feed has none to keep. */
	private val history: History?,
) : AutoCloseable {
	/** Whether the watcher is running, and where it is not, why. */
	sealed interface Watching {
		data object On : Watching

		data class Off(
			val why: String,
		) : Watching
	}

	private var feeding: Feeding by Published(feeding)

	/** What the changes come through, while they are watched. */
	val through: String? get() = (feeding as? Feeding.On)?.through

	/** Whether changes are being watched, and where they are not, why. */
	val watching: Watching
		get() =
			when (val now = feeding) {
				is Feeding.On -> now.stopped()?.let(Watching::Off) ?: Watching.On
				is Feeding.Off -> Watching.Off(now.why)
			}

	private val ticks: ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor {
			Thread
				.ofPlatform()
				.name("prism-tick")
				.daemon()
				.unstarted(it)
		}

	/** The watcher's count of what it lost, as last looked at. */
	private var lost: Long = (feeding as? Feeding.On)?.lost?.invoke() ?: 0

	/** When the roots were last read again for want of a watcher, in nanoseconds of [System.nanoTime]. */
	private var rescanned: Long = System.nanoTime()

	/** What the last write of the index failed with, as text; null while it is written. */
	private var unwritten: String? = null

	/** When the index was last written, or the daemon started, in nanoseconds of [System.nanoTime]. */
	private var wrote: Long = System.nanoTime()

	/**
	 * The feed's place as it last was with the index settled: every change up to it made. Kept beside the index's
	 * file each time that is written, so that the place kept is never ahead of what the file holds.
	 */
	private var settledAt: Long? = null

	init {
		val every = settings.tick.inWholeMilliseconds

		// The index as its file held it is whole, before its roots are read again; so is it once they are.
		if (live.origin is IndexStore.Origin.Read)
			carrying("telling of the index") { settings.telling.whole(live.index) }
		told(reported("reading ${settings.roots.joinToString()}", live.opened))
		// A tick that threw would be the last: the executor runs a task that failed no more.
		ticks.scheduleWithFixedDelay({
			carrying("keeping the index", ::tick)
		}, every, every, TimeUnit.MILLISECONDS)
		// What is mounted, looked at from the first moment and often: a file system that goes has its tree kept, and
		// one that comes is in the index, within a look of it happening.
		ticks.scheduleWithFixedDelay({
			carrying("looking at what is mounted") {
				reported("reading what was mounted", live.remounted())
			}
		}, 0, settings.mounts.inWholeMilliseconds, TimeUnit.MILLISECONDS)
		ticks.scheduleWithFixedDelay({
			carrying("letting go of file systems long unmounted") { live.expire() }
		}, 0, settings.expiry.inWholeMilliseconds, TimeUnit.MILLISECONDS)
	}

	/**
	 * The feed's place noted, where the index holds every change up to it: nothing is being read, and nothing waits
	 * to be made (a reading in flight holds changes that are in no index yet). Where it does not, the place noted
	 * before stands, which the index still holds everything up to.
	 */
	private fun noted() {
		if (relay.idle && live.reading.isEmpty())
			(feeding as? Feeding.On)?.at?.invoke()?.let { settledAt = it }
	}

	/** The place noted kept beside the index ([History]), the index's file having just been written. */
	private fun placed() {
		val history = history ?: return

		settledAt?.let {
			history.write(Place(volumesOf(settings.live.mounts()), rulesOf(live.rules), it))
		}
	}

	private fun tick() {
		// Written once an hour; at once, where the roots have been read and no file holds an index yet, since reading
		// them is what takes long; and again at the next tick, where a write failed, until one does not.
		val first = live.unwritten && live.opened.isDone

		carrying("noting the place in the record of changes", ::noted)
		if (first || unwritten != null ||
			System.nanoTime() - wrote >= settings.write.inWholeNanoseconds
		)
			written()
		carrying("letting go of places read for searches") { live.tidy() }

		when (val now = feeding) {
			is Feeding.On -> {
				val count = now.lost() ?: lost

				if (count > lost)
					rescanRoots()
				lost = count
				now.stopped()?.let { why -> feeding = Feeding.Off(why) }
			}
			is Feeding.Off ->
				if (System.nanoTime() - rescanned >
					settings.rescanWithoutWatcher.inWholeNanoseconds
				) {
					rescanned = System.nanoTime()
					rescanRoots()
				}
		}
	}

	/** The roots read again, and what that fails with, if it does, reported: they are read again the next time. */
	fun rescanRoots() = told(reported("reading the roots again", live.rescanRoots()))

	/** [read], once it is done, told of as the index whole, where it was read without going wrong. */
	private fun told(read: CompletableFuture<Unit>): CompletableFuture<Unit> =
		read.thenApply {
			carrying(
				"telling of the index",
			) { settings.telling.whole(live.index) }
		}

	/** What the index leaves out made [rules], and the index made to agree ([LiveIndex.ruled]). */
	fun ruled(rules: IgnoreRules) =
		reported("applying the rules of what is left out", live.ruled(rules))

	/**
	 * The index written, and a failure to write reported when it is not the one reported last: a disk that is full is
	 * said once, not every few seconds. What could not be written is held as it is, and written the next time.
	 */
	private fun written() {
		try {
			live.write()
			wrote = System.nanoTime()
			unwritten = null
			carrying("keeping the place in the record of changes", ::placed)
		} catch (failed: Throwable) {
			val why = failed.toString()

			if (why != unwritten)
				settings.report("writing the index", failed)
			unwritten = why
		}
	}

	/** [work] done, and what it throws reported as a failure of [doing], and gone on from. */
	private fun carrying(doing: String, work: () -> Unit) {
		try {
			work()
		} catch (failed: Throwable) {
			settings.report(doing, failed)
		}
	}

	/** [scan], with what it fails with, if it does, reported as a failure of [doing]. */
	private fun reported(
		doing: String,
		scan: CompletableFuture<Unit>,
	): CompletableFuture<Unit> =
		scan.whenComplete { _, failed ->
			if (failed != null)
				settings.report(doing, (failed as? CompletionException)?.cause ?: failed)
		}

	/**
	 * The watcher stopped, what it saw made, the index written, and its scans stopped: each whatever the one before
	 * came to, so that the index is written even where stopping the watcher failed.
	 */
	override fun close() {
		ticks.shutdownNow()
		carrying("stopping the watcher") { (feeding as? Feeding.On)?.stop?.close() }
		carrying("making the last changes") { relay.close() }
		carrying("writing the index") {
			noted()
			live.close()
			placed()
		}
	}

	/**
	 * What carries a feed's changes to a live index: a queue, emptied on a thread of its own once there is an index to
	 * make them in, every batch waiting then made as one.
	 */
	private class Relay(
		private val report: (String, Throwable) -> Unit,
	) : AutoCloseable {
		private val queue = LinkedBlockingQueue<List<Changed>>()

		/** How many batches have been given and not yet made. */
		private val waiting = AtomicLong(0)

		/** Whether everything given has been made. */
		val idle: Boolean get() = waiting.get() == 0L

		// A flag or a number of one word, set by one thread and read by others: volatile is all it needs, unboxed.
		@Volatile
		private var running = true

		/** The thread that makes what is queued: begun once ([into]), and waited for by whoever closes this. */
		private val thread = Once<Thread>()

		fun seen(batch: List<Changed>) {
			waiting.incrementAndGet()
			queue.put(batch)
		}

		/** Begins making what is queued, and what will be, in [live]. */
		fun into(live: LiveIndex) {
			thread.set(
				Thread.ofPlatform().name("prism-changes").daemon().start {
					while (running) {
						val first = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
						var taken = 1L
						val batch =
							ArrayList(first).also { all ->
								generateSequence { queue.poll() }.forEach {
									taken++
									all.addAll(it)
								}
							}

						// Changes that could not be made are made up for as lost ones are: which were made cannot be
						// known, so the roots are read again. The thread goes on to the next, whatever these came to.
						try {
							live.changed(batch.distinct())
						} catch (failed: Throwable) {
							report("making ${batch.size} changes", failed)
							try {
								live.rescanRoots()
							} catch (again: Throwable) {
								report("reading the roots again", again)
							}
						} finally {
							waiting.addAndGet(-taken)
						}
					}
				},
			)
		}

		override fun close() {
			running = false
			thread.value?.join()
		}
	}

	companion object {
		/**
		 * A daemon started: its [feed] first, then its index opened (read from its file, or built, in the background),
		 * and the feed's changes made in it.
		 */
		fun start(settings: DaemonSettings, feed: Feed): Daemon = start(settings, feed, null) {}

		/**
		 * A daemon started with the platform's own feed. On a Mac, where the index's file has a place kept beside it
		 * that is of the volumes there are now, and of rules this daemon still has ([History]), the feed takes up
		 * from that place, and the roots the file holds are not read again: what changed while no daemon ran is told
		 * by FSEvents.
		 */
		fun start(settings: DaemonSettings): Daemon {
			val history =
				settings.file?.takeIf { Os.current == Os.MacOS }?.let(History::beside)
					?: return start(settings, Feed.of(settings.roots, settings.telling.heard), null) {}
			val rules = rulesOf(settings.indexer.rules)
			val kept =
				history.read()?.takeIf {
					it.of == volumesOf(settings.live.mounts()) && rules.containsAll(it.rules) &&
						Files.exists(settings.file)
				}

			// Whatever is kept is of the daemon before: this one keeps its own, once it is settled.
			history.forget()

			return if (kept == null)
				start(settings, Feed.of(settings.roots, settings.telling.heard), history) {}
			else
				start(
					settings.copy(live = settings.live.copy(readRootsOnOpen = false)),
					Feed.fsEvents(settings.roots, kept.id, settings.telling.heard),
					history,
				) {}.also {
					// Rules added while no daemon ran: what they leave out is taken out, which reads nothing.
					if (kept.rules != rules)
						it.live.leaveOut()
				}
		}

		internal fun start(
			settings: DaemonSettings,
			feed: Feed,
			history: History?,
			beforeMaking: (Set<Where>) -> Unit,
		): Daemon {
			val relay = Relay(settings.report)

			// The watcher first: from here on, nothing is missed, and what is seen waits in the queue for the index.
			// A feed that cannot even say why it will not start is no feed, and the daemon runs without.
			val feeding =
				try {
					feed.start(relay::seen)
				} catch (failed: Throwable) {
					settings.report("starting the watcher", failed)
					Feeding.Off("the watcher couldn't start: $failed")
				}

			val live =
				LiveIndex.open(
					settings.file,
					settings.roots,
					settings.indexer,
					settings.live,
					beforeMaking,
				)

			relay.into(live)

			// A feed that did not start told nothing of what changed before: the roots are read after all.
			if (feeding is Feeding.Off && !settings.live.readRootsOnOpen)
				live.rescanRoots()

			return Daemon(live, feeding, relay, settings, history)
		}
	}
}
