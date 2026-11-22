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
 * the lost changes may have been in. Where reading the ring fails, the thread stops, and [stopped] says why; and so it
 * says where the watcher it reads was pinned by a loader, and that loader has since taken it away or pinned another
 * ([pin]), which nothing reading the ring would ever find out.
 */
class Watcher private constructor(
	private val watch: Watch,
	/** The pin of the ring read, where it is a watcher a loader left pinned; none for one of this process's own. */
	private val pin: Pin?,
	private val seen: (List<Observed>) -> Unit,
) : AutoCloseable {
	// A flag or a number of one word, set by one thread and read by others: volatile is all it needs, unboxed.
	@Volatile
	private var running = true

	/** Why the thread stopped reading, if it did before it was closed. */
	private var readFailure: String? by Published(null)

	/** Why the watcher it reads is no longer the one pinned, as last looked at ([recheck]). */
	private var replaced: String? by Published(null)

	/**
	 * Why what it reads tells of no changes any more, if it does not: the thread stopped reading, or the watcher it
	 * reads is no longer the one pinned, as last looked at ([recheck]). Asking looks at nothing.
	 */
	val stopped: String? get() = readFailure ?: replaced

	/**
	 * Whether the watcher it reads is still the one pinned, looked at again: what is told that the pins may have
	 * changed ([PinWatch]) asks.
	 */
	fun recheck() {
		replaced = pin?.gone()
	}

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
							readFailure = "reading the ring failed: $read"

							return
						}
					is Sys.Ok ->
						if (batch.isNotEmpty())
							seen(batch)
				}
			}
		} catch (failed: Throwable) {
			// Never ended without saying so: whoever keeps an index from this reads its places again instead.
			readFailure = "reading the ring failed: $failed"
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
			// The pin as it was before the ring was opened, and as it is after: where a loader pinned another between
			// the two, which this has opened is not known, and it is opened again.
			fun opened(): Pair<Sys<Watch>, Pin?> {
				val before = Pin.at(Pin.ringIn(pins.folder))
				val watch = openPinnedWatcher(pins)

				return if (watch is Sys.Ok && before?.gone() != null) {
					watch.value.close()
					opened()
				} else {
					watch to before
				}
			}

			val (pinned, pin) = opened()

			if (pinned is Sys.Ok)
				return Start.Running(
					Watcher(pinned.value, pin, seen),
					"probe pinned at ${pins.folder}",
				)

			val unpinned = "none this user may read is pinned at ${pins.mount} ($pinned)"

			// A process that may not load one is not asked to: the kernel would refuse it, and libbpf say so on
			// standard error, each time a daemon tries again.
			if (!Capabilities.mayLoadBpf())
				return Start.NotRunning(
					"$unpinned, and this process may not load one (it has no CAP_BPF and CAP_PERFMON)",
				)

			return when (val started = startWatching()) {
				is Watching.Started ->
					Start.Running(
						Watcher(started.watch, null, seen),
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
