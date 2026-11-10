package prism.daemon

import java.nio.file.Path
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

	init {
		val every = settings.tick.inWholeMilliseconds

		ticks.scheduleWithFixedDelay(::tick, every, every, TimeUnit.MILLISECONDS)
	}

	private fun tick() {
		live.flush()
		live.tidy()

		when (val now = feeding) {
			is Feeding.On -> {
				val count = now.lost() ?: lost

				if (count > lost)
					live.rescanRoots()
				lost = count
				now.stopped()?.let { why -> feeding = Feeding.Off(why) }
			}
			is Feeding.Off ->
				if (System.nanoTime() - rescanned >
					settings.rescanWithoutWatcher.inWholeNanoseconds
				) {
					rescanned = System.nanoTime()
					live.rescanRoots()
				}
		}
	}

	/** The watcher stopped, what it saw made, the index written, and its scans stopped. */
	override fun close() {
		ticks.shutdownNow()
		(feeding as? Feeding.On)?.stop?.close()
		relay.close()
		live.close()
	}

	/**
	 * What carries a feed's changes to a live index: a queue, emptied on a thread of its own once there is an index to
	 * make them in, every batch waiting then made as one.
	 */
	private class Relay : AutoCloseable {
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

						live.changed(batch.distinct())
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
			val relay = Relay()
			// The watcher first: from here on, nothing is missed, and what is seen waits in the queue for the index.
			val feeding = feed.start(relay::seen)

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
