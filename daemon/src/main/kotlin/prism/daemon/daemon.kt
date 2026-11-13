package prism.daemon

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import prism.index.FileId
import prism.index.Indexer
import prism.index.Name
import prism.index.Where
import prism.probe.Change
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
						)
				}
			}

		/** No feed at all, and [why]: a daemon that reads its roots again now and then, and is told nothing between. */
		fun none(why: String) = Feed { Feeding.Off(why) }
	}
}

/** What starting a feed gave. */
sealed interface Feeding {
	/** Feeding: [stop] stops it; [lost] says how many changes it could not give; [stopped], why it stopped, if it did. */
	class On(
		val stop: AutoCloseable,
		val lost: () -> Long?,
		val stopped: () -> String?,
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
	val from = FileId(observed.dev, observed.parent)
	val to = observed.movedTo?.let { FileId(observed.dev, it) }
	val name = runCatching { Name.of(observed.name()) }.getOrNull()

	return when (observed.change) {
		Change.Moved -> listOfNotNull(Changed(from, null), to?.let { Changed(it, name) })
		Change.Exchanged -> listOfNotNull(Changed(from, null), to?.let { Changed(it, null) })
		else -> listOf(Changed(from, name))
	}
}

/** How a daemon goes about its work. */
data class DaemonSettings(
	/** The index's file; null to keep it in memory alone. */
	val file: Path?,
	val roots: List<Path> = listOf(Path("/")),
	val indexer: Indexer = Indexer(),
	val live: LiveSettings = LiveSettings(),
	/** How often what was made is written, places read for searches alone let go, and the watcher looked at. */
	val tick: Duration = 5.seconds,
	/** How often the roots are read again where there is no watcher to say what changed. */
	val rescanWithoutWatcher: Duration = 30.minutes,
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
) : AutoCloseable {
	/** Whether the watcher is running, and where it is not, why. */
	sealed interface Watching {
		data object On : Watching

		data class Off(
			val why: String,
		) : Watching
	}

	@Volatile
	private var feeding: Feeding = feeding

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

	init {
		val every = settings.tick.inWholeMilliseconds

		reported("reading ${settings.roots.joinToString()}", live.opened)
		// A tick that threw would be the last: the executor runs a task that failed no more.
		ticks.scheduleWithFixedDelay({
			carrying("keeping the index", ::tick)
		}, every, every, TimeUnit.MILLISECONDS)
	}

	private fun tick() {
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
	fun rescanRoots() = reported("reading the roots again", live.rescanRoots())

	/**
	 * What was made written, and a failure to write reported when it is not the one reported last: a disk that is
	 * full is said once, not every few seconds. What could not be written is kept, and written the next time.
	 */
	private fun written() {
		try {
			live.flush()
			unwritten = null
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
		carrying("writing the index") { live.close() }
	}

	/**
	 * What carries a feed's changes to a live index: a queue, emptied on a thread of its own once there is an index to
	 * make them in, every batch waiting then made as one.
	 */
	private class Relay(
		private val report: (String, Throwable) -> Unit,
	) : AutoCloseable {
		private val queue = LinkedBlockingQueue<List<Changed>>()

		@Volatile
		private var running = true

		private var thread: Thread? = null

		fun seen(batch: List<Changed>) = queue.put(batch)

		/** Begins making what is queued, and what will be, in [live]. */
		fun into(live: LiveIndex) {
			thread =
				Thread.ofPlatform().name("prism-changes").daemon().start {
					while (running) {
						val first = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
						val batch =
							ArrayList(first).also { all ->
								generateSequence { queue.poll() }.forEach(all::addAll)
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
						}
					}
				}
		}

		override fun close() {
			running = false
			thread?.join()
		}
	}

	companion object {
		/**
		 * A daemon started: its [feed] first, then its index opened (read from its file, or built, in the background),
		 * and the feed's changes made in it.
		 */
		fun start(settings: DaemonSettings, feed: Feed = Feed.KERNEL): Daemon =
			start(settings, feed) {}

		internal fun start(
			settings: DaemonSettings,
			feed: Feed,
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
					Feeding.Off("it could not be started: $failed")
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

			return Daemon(live, feeding, relay, settings)
		}
	}
}
