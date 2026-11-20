package prism.watcher

import prism.ebpf.Hook
import prism.ebpfloader.Attachment
import prism.linux.Sys
import prism.linux.Wait
import prism.shared.Published

/**
 * The watcher, running: its program loaded and attached, and its ring read on a thread of its own, whose every batch
 * of changes is handed to [seen] as it is read. Reading never waits on what [seen] does with a batch for longer than
 * [seen] takes, so [seen] should hand it on (to a queue) rather than act on it, or the ring fills while it acts.
 *
 * A watcher counts what it could not send ([lost]); where that grows, whoever keeps an index from it reads again what
 * the lost changes may have been in. Where reading the ring fails, the thread stops, and [stopped] says why.
 */
class Watcher private constructor(
	private val watch: Watch,
	private val seen: (List<Observed>) -> Unit,
) : AutoCloseable {
	// A flag or a number of one word, set by one thread and read by others: volatile is all it needs, unboxed.
	@Volatile
	private var running = true

	/** Why the thread stopped reading, if it did before it was closed. */
	var stopped: String? by Published(null)
		private set

	private val thread: Thread =
		Thread
			.ofPlatform()
			.name(
				"prism-watcher",
			).daemon()
			.start(::read)

	/** Each hook, attached or not and why. */
	val hooks: Map<Hook, Attachment> get() = watch.hooks

	/** How many changes the watcher could not send since it started, the ring being full; null where it cannot say. */
	fun lost(): Long? = (watch.lost() as? Sys.Ok)?.value

	private fun read() {
		try {
			while (running) {
				val batch = ArrayList<Observed>()

				when (val read = watch.await(Wait.AtMost(READ_EVERY_MILLIS), batch::add)) {
					is Sys.Failed ->
						if (read.errno.name != "EINTR") {
							stopped = "reading the ring failed: $read"

							return
						}
					is Sys.Ok ->
						if (batch.isNotEmpty())
							seen(batch)
				}
			}
		} catch (failed: Throwable) {
			// Never ended without saying so: whoever keeps an index from this reads its places again instead.
			stopped = "reading the ring failed: $failed"
		}
	}

	/** The thread stopped, and the program taken out of the kernel. */
	override fun close() {
		running = false
		thread.join()
		watch.close()
	}

	/** What starting the watcher gave. */
	sealed interface Start {
		data class Running(
			val watcher: Watcher,
			/** Which watcher it is: one a loader left pinned, or this process's own. */
			val through: String,
		) : Start

		/** No watcher: the build has none, or the kernel would not take it, and why in words. */
		data class NotRunning(
			val why: String,
		) : Start
	}

	companion object {
		/** How long the thread waits for the ring at a time, between looks at whether it has been closed. */
		private const val READ_EVERY_MILLIS = 100

		/**
		 * The watcher read on a thread of its own, every batch of changes it sees given to [seen]: the one a loader
		 * left pinned at [pins], where there is one this user may read; otherwise one this process loads itself, where
		 * it may (it is root's to).
		 */
		fun start(
			pins: WatcherPins = WatcherPins.default(),
			seen: (List<Observed>) -> Unit,
		): Start {
			val pinned = openPinnedWatcher(pins)

			if (pinned is Sys.Ok)
				return Start.Running(
					Watcher(pinned.value, seen),
					"probe pinned at ${pins.folder}",
				)

			val unpinned = "none this user may read is pinned at ${pins.mount} ($pinned)"

			return when (val started = startWatching()) {
				is Watching.Started ->
					Start.Running(
						Watcher(started.watch, seen),
						"probe loaded by prism",
					)
				Watching.NotBuilt ->
					Start.NotRunning(
						"this build has no compiled watcher (it was built without clang's BPF target)",
					)
				is Watching.Refused ->
					Start.NotRunning(
						"$unpinned, and the kernel would not load one for this process: ${started.why.message}",
					)
				is Watching.RingUnreadable ->
					Start.NotRunning(
						"the watcher's ring could not be mapped: ${started.failure}",
					)
			}
		}
	}
}
