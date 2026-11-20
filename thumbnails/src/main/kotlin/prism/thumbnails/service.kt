package prism.thumbnails

import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import prism.shared.Guarded
import prism.shared.Published

/*
 * The thumbnails of one prism: found in the cache, made where they are not, and kept within their budget. Every window
 * of the process asks the one service, so that a picture two windows show is made once, and what is made is in an
 * order that suits them all.
 *
 * **What is made first.** A window asks for the picture of each file it shows ([ThumbnailService.find]), and says
 * which files are near what it shows ([ThumbnailService.near]: so many before and after, the nearest first). What
 * is shown is made before anything that is only near; and what is near is made a few at a time, while nothing shown
 * waits. Both stop being wanted as easily as they began: a tile scrolled away stops waiting, and a window that shows
 * another folder, or is closed, says so ([ThumbnailService.forget]). What nobody waits for any more is not begun,
 * and where it has begun, is stopped, ffmpeg and all. What two ask for is stopped once neither wants it.
 *
 * **What it costs to ask.** Looking a thumbnail up is a file's name worked out and its text read, and waits for
 * nothing. Only making one takes a place among the few made at once ([parallel]).
 *
 * **What is kept.** Every thumbnail in the cache's folder is this service's to keep or remove, whoever made it: those
 * there as it starts, those it makes, and those another program makes while it runs, which it is told of by whatever
 * watches the folder ([Watching]). When the cache is over its budget, what is used least is removed ([Keeping]).
 */

/** Where a file's picture is to be had. */
sealed interface Found {
	/** In the cache: the thumbnail's file, to decode. */
	data class Kept(
		val thumbnail: Path,
	) : Found

	/** The file itself is the picture to decode: an image small enough that a thumbnail of it would save nothing. */
	data object Itself : Found

	/** It has none. */
	data object None : Found
}

/** A picture wanted: of [subject], to be drawn [side] pixels across. */
data class Wanted(
	val subject: Subject,
	val side: Int,
)

/** What says a folder has changed: which of its entries, by name; or null, for the folder as a whole. */
fun interface Watching {
	/** [changed] told whenever what [folder] holds changes, until what this gives is closed. */
	fun watch(folder: Path, changed: (Set<String>?) -> Unit): AutoCloseable

	companion object {
		/** Nothing watched: what other programs add is found the next time prism starts. */
		val NONE = Watching { _, _ -> AutoCloseable {} }
	}
}

class ThumbnailService(
	private val scope: CoroutineScope,
	settings: ThumbnailSettings = ThumbnailSettings(),
	private val pictures: Pictures = Pictures.NONE,
	private val system: SystemPictures? = null,
	private val processes: Processes = Processes.SYSTEM,
	private val typeOf: (Path) -> String? = { null },
	/** The ffmpeg there is, found once: awaited by whatever is made first. */
	val ffmpeg: Deferred<Ffmpeg?> =
		scope.async(Dispatchers.IO) { Ffmpeg.find(processes = processes) },
	/** Where what is known of the thumbnails' use is kept between runs; nowhere, where null. */
	private val usage: Path? = null,
	private val watching: Watching = Watching.NONE,
	/** Where thumbnails are kept where the settings name no folder. */
	private val defaultRoot: () -> Path = { ThumbnailCache.defaultRoot() },
	private val now: () -> Instant = Instant::now,
	/** How many are made at once. */
	private val parallel: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
	private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
	/** How often what is known of use is written. */
	private val written: Duration = 5.minutes,
	/** Where what goes wrong that should not have is said. */
	private val report: (String, Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {
	/** A cache as the service keeps it: what is known of its entries, and what watches its folders. */
	private class Store(
		val cache: ThumbnailCache,
		val catalogue: Catalogue,
	) {
		val watches = Guarded(ArrayList<AutoCloseable>())
	}

	/** The service as it is set up: for one cache, with one set of settings. */
	private inner class Setup(
		val settings: ThumbnailSettings,
		val store: Store,
	) {
		val cache: ThumbnailCache get() = store.cache
		val catalogue: Catalogue get() = store.catalogue

		val making: Deferred<Making> =
			scope.async(start = CoroutineStart.LAZY) {
				Making(
					pictures,
					Thumbnailers.of(settings.thumbnailers, settings.off, ffmpeg.await()),
					system,
					processes,
					typeOf,
				)
			}
	}

	private val setup: Published<Setup> = Published(setupOf(settings, null))

	/** The settings in use. */
	val settings: ThumbnailSettings get() = setup.now.settings

	/** The cache in use. */
	val cache: ThumbnailCache get() = setup.now.cache

	/** How many thumbnails are kept, and how many bytes they take, as far as has been counted. */
	val totals: Totals get() = setup.now.catalogue.totals

	/** The programs there are to make thumbnails with, as the settings in use have them: ffmpeg, once it is found. */
	suspend fun thumbnailers(): Thumbnailers =
		setup.now.making
			.await()
			.thumbnailers

	/** The programs [settings] would give. */
	suspend fun thumbnailers(settings: ThumbnailSettings): Thumbnailers =
		Thumbnailers.of(settings.thumbnailers, settings.off, ffmpeg.await())

	private val permits = Semaphore(parallel)

	/** How many pictures that are shown are waited for: while any is, nothing that is only near is begun. */
	private val shown = MutableStateFlow(0)

	private val trimming = AtomicBoolean(false)

	private fun setupOf(settings: ThumbnailSettings, before: Setup?): Setup {
		val cache = ThumbnailCache(settings.root(defaultRoot))

		// The same folder is the same thumbnails, and what is known of them stands.
		return Setup(
			settings,
			before?.store?.takeIf { it.cache == cache } ?: Store(cache, Catalogue(usage)),
		)
	}

	/**
	 * The service set up as [settings] say, from now on. Where they name another folder, that folder's thumbnails are
	 * the ones kept from here: what is known of the old folder's is remembered, so that a thumbnail moved from there
	 * to here is known as it was.
	 */
	fun configure(settings: ThumbnailSettings) {
		val before = setup.now

		if (settings == before.settings)
			return

		val next = setupOf(settings, before)

		setup.set(next)
		if (next.store !== before.store) {
			closed(before.store)
			before.catalogue.write()
			previous.set(before.catalogue.all())
			opened(next.store)
		} else {
			trim(next)
		}
	}

	/** What was known of the thumbnails of the folder last kept in: a thumbnail found here that was there is the same. */
	private val previous = Published<Map<Entry, Kept>>(emptyMap())

	/**
	 * [store]'s cache counted, in the background, and watched from now on. Its folders are made first, those there
	 * are not, so that each is watched from before anything is put in it: what is moved into a cache just set
	 * (relocation.kt) is then heard of as it arrives.
	 */
	private fun opened(store: Store) {
		store.cache.prepare()
		scope.launch(dispatcher) {
			carrying("reading the thumbnails kept") {
				val since = now()

				if (previous.now.isEmpty())
					store.catalogue.read()
				store.catalogue.reconcile(store.cache.entries().map(::carried), since)
				trim()
			}
		}
		watch(store)
	}

	/** [lying], as it is to be first known: with the use it had in the folder thumbnails were last kept in, if it was there. */
	private fun carried(lying: Lying): Pair<Lying, Kept?> =
		lying to previous.now[lying.entry]?.copy(bytes = lying.bytes)

	private fun closed(store: Store) {
		store.watches.guarded {
			forEach { runCatching { it.close() } }
			clear()
		}
	}

	/** The folders of [store]'s cache watched: each there is, and the cache's own, for folders that come to be. */
	private fun watch(store: Store) {
		val cache = store.cache

		fun folder(folder: Path) =
			watching.watch(folder) { names ->
				scope.launch(dispatcher) {
					carrying("noting a thumbnail") {
						if (names == null)
							cache.entriesIn(folder).forEach { store.catalogue.found(it, carried(it).second) }
						else
							for (name in names) {
								val path = folder.resolve(name)
								val lying = cache.lying(path)

								if (lying != null)
									store.catalogue.found(lying, carried(lying).second)
								else
									cache.entryAt(path)?.let(store.catalogue::gone)
							}
						trim()
					}
				}
			}

		val watches =
			cache.folders().map(::folder) +
				// A folder of the cache made later (the first thumbnail of a size) is watched from then.
				listOf(cache.root, cache.root.resolve(ThumbnailCache.FAIL)).map {
					watching.watch(it) { _ ->
						if (setup.now.store === store) {
							closed(store)
							watch(store)
							// What came to lie in a folder before it was watched is found by looking.
							scope.launch(dispatcher) {
								carrying("reading the thumbnails kept") {
									store.cache.entries().forEach {
										store.catalogue.found(it, carried(it).second)
									}
								}
							}
						}
					}
				}

		store.watches.guarded { addAll(watches) }
	}

	// ---- finding and making ------------------------------------------------------------------------------------------

	/**
	 * Where the picture of [subject], to be drawn [wanted] pixels across, is to be had: looked for, and made where
	 * there is none and there is a way to make one. Whoever asks is shown the file, and waits ahead of whatever is
	 * only [near]; where they stop waiting, and nobody else wants it, it is not made.
	 */
	suspend fun find(subject: Subject, wanted: Int): Found =
		withContext(dispatcher) {
			val setup = setup.now

			located(setup, subject, wanted, showing = true)
				?: run {
					shown.update { it + 1 }
					try {
						made(setup, subject, wanted)
					} finally {
						shown.update { it - 1 }
					}
				}
		}

	/**
	 * Where [subject]'s picture is, as far as can be said without making anything: null where it is to be made. One
	 * found in the cache is counted as shown where it is [showing].
	 */
	private suspend fun located(
		setup: Setup,
		subject: Subject,
		wanted: Int,
		showing: Boolean,
	): Found? {
		val extension = subject.extension
		val making = setup.making.await()

		if (extension in pictures.extensions)
			pictures.extentOf(subject.file)?.let { extent ->
				// Small enough to decode each time it is shown; or wanted larger than any thumbnail is kept, which only
				// the picture itself is.
				if (extent.pixels <= DIRECT ||
					(wanted > ThumbnailSize.LARGEST.pixels && extent.pixels <= making.decodedLimit)
				)
					return Found.Itself
			}

		val size = setup.settings.resolution.sizeFor(wanted)

		kept(
			setup,
			subject,
			ThumbnailSize.entries.filter {
				it >= size
			},
			showing,
		)?.let { return it }

		return when {
			// Nothing here makes its picture, or making it failed: a smaller one another program made is still its
			// picture, and better than none.
			!making.mayPicture(extension) || setup.cache.failed(subject) ->
				smaller(setup, subject, size, showing)
			else -> null
		}
	}

	private fun kept(
		setup: Setup,
		subject: Subject,
		sizes: List<ThumbnailSize>,
		showing: Boolean,
	): Found.Kept? =
		setup.cache.find(subject, sizes)?.let { thumbnail ->
			if (showing)
				setup.cache.entryAt(thumbnail)?.let { setup.catalogue.shown(it, now()) }
			Found.Kept(thumbnail)
		}

	/** The best there is for [subject] smaller than [size], where its own size cannot be made: or nothing. */
	private fun smaller(
		setup: Setup,
		subject: Subject,
		size: ThumbnailSize,
		showing: Boolean,
	): Found =
		kept(setup, subject, ThumbnailSize.entries.filter { it < size }.reversed(), showing)
			?: Found.None

	/** What is being made: whose picture, and at what size. */
	private data class Order(
		val subject: Subject,
		val size: ThumbnailSize,
	)

	private inner class Flight(
		key: Order,
		make: suspend () -> Found,
	) {
		var waiters = 0

		val work: Deferred<Found> =
			scope.async(dispatcher, start = CoroutineStart.LAZY) {
				try {
					make()
				} finally {
					flights.guarded {
						if (this[key] === this@Flight)
							remove(key)
					}
				}
			}
	}

	private val flights = Guarded(HashMap<Order, Flight>())

	/** [subject]'s thumbnail made, or waited for where it is being made already. */
	private suspend fun made(setup: Setup, subject: Subject, wanted: Int): Found {
		val size = setup.settings.resolution.sizeFor(wanted)
		val key = Order(subject, size)

		val flight =
			flights.guarded {
				getOrPut(key) { Flight(key) { make(setup, subject, size) } }.also { it.waiters++ }
			}

		flight.work.start()
		try {
			return flight.work.await()
		} finally {
			flights.guarded {
				flight.waiters--
				// Nobody waits for it any more: it is not made, and the next to want it begins it anew.
				if (flight.waiters == 0 && !flight.work.isCompleted) {
					flight.work.cancel()
					if (this[key] === flight)
						remove(key)
				}
			}
		}
	}

	private suspend fun make(setup: Setup, subject: Subject, size: ThumbnailSize): Found =
		permits.withPermit {
			try {
				// Made meanwhile, by another program or the one before this in the queue.
				kept(setup, subject, ThumbnailSize.entries.filter { it >= size }, showing = false)
					?: when (val outcome = setup.making.await().make(subject, size.pixels)) {
						is Outcome.Pictured ->
							setup.cache
								.write(subject, size, outcome.picture.png)
								?.also { noted(setup, it) }
								?.let(Found::Kept)
								?: Found.None
						is Outcome.Failed -> {
							setup.cache.fail(subject)?.let { noted(setup, it) }
							smaller(setup, subject, size, showing = false)
						}
						is Outcome.Unmade -> smaller(setup, subject, size, showing = false)
					}
			} catch (stopped: CancellationException) {
				throw stopped
			} catch (failed: IOException) {
				Found.None
			} catch (failed: RuntimeException) {
				report("making the thumbnail of ${subject.file}", failed)
				Found.None
			}
		}

	/** The entry just written at [path] known of, and the cache brought within its budget where that put it over. */
	private fun noted(setup: Setup, path: Path) {
		// Made now, whatever the file's own time says (a file system's clock is not always this machine's).
		setup.cache.lying(path)?.copy(written = now())?.let {
			setup.catalogue.found(it, carried(it).second)
		}
		trim(setup)
	}

	/** What is used least removed, in the background, where the cache is over its budget. */
	private fun trim(setup: Setup = this.setup.now) {
		val budget = setup.settings.kept

		if (!setup.catalogue.over(budget) || !trimming.compareAndSet(false, true))
			return

		scope.launch(dispatcher) {
			try {
				carrying("removing thumbnails") {
					setup.catalogue.removing(budget, now()).forEach(setup.cache::remove)
				}
			} finally {
				trimming.set(false)
			}
		}
	}

	// ---- what is near ------------------------------------------------------------------------------------------------

	/** What one window wants made that it does not show: in order, the nearest to what it shows first. */
	private inner class Near {
		private inner class State {
			var wanted: List<Wanted> = emptyList()
			var wantedSet: Set<Wanted> = emptySet()
			val running = HashMap<Wanted, Job>()
			val done = HashSet<Wanted>()
		}

		private val state = Guarded(State())

		/** [wanted] from now on, in place of what was: what is being made that is no longer among them is stopped. */
		fun want(wanted: List<Wanted>) {
			state.guarded {
				this.wanted = wanted
				wantedSet = wanted.toSet()
				running.filterKeys { it !in wantedSet }.values.forEach { it.cancel() }
				done.retainAll(wantedSet)
			}
			pump()
		}

		/** As many more begun as may be at once, while nothing shown waits: all but one of the places there are. */
		fun pump() {
			state.guarded {
				while (running.size < (parallel - 1).coerceAtLeast(1) && shown.value == 0) {
					val next = wanted.firstOrNull { it !in done && it !in running } ?: break

					val job =
						scope.launch(dispatcher, start = CoroutineStart.LAZY) {
							val setup = this@ThumbnailService.setup.now

							located(setup, next.subject, next.side, showing = false)
								?: made(setup, next.subject, next.side)
						}

					running[next] = job
					// However it ends, made or stopped, and though it was stopped before it began: its place is free
					// for the next.
					job.invokeOnCompletion {
						state.guarded {
							running.remove(next, job)
							if (next in wantedSet)
								done += next
						}
						pump()
					}
					job.start()
				}
			}
		}
	}

	private val owners = Guarded(HashMap<Any, Near>())

	/**
	 * What [owner] (a window's listing) wants made that it does not show, from now on: [wanted], the nearest to what
	 * it shows first. Said again as the window scrolls; what is no longer among them is no longer made.
	 */
	fun near(owner: Any, wanted: List<Wanted>) {
		owners.guarded { getOrPut(owner, ::Near) }.want(wanted)
	}

	/** [owner] wants nothing more: it shows another folder, or is closed. */
	fun forget(owner: Any) {
		owners.guarded { remove(owner) }?.want(emptyList())
	}

	private inline fun carrying(doing: String, work: () -> Unit) {
		try {
			work()
		} catch (stopped: CancellationException) {
			throw stopped
		} catch (failed: IOException) {
			report(doing, failed)
		} catch (failed: RuntimeException) {
			report(doing, failed)
		}
	}

	// Begun last, once everything above is there for it: the cache counted and watched, what is near begun again
	// whenever nothing shown waits, and what is known of use written now and then.
	init {
		opened(setup.now.store)
		scope.launch {
			shown.collect { waiting ->
				if (waiting == 0)
					owners.guarded { values.toList() }.forEach { it.pump() }
			}
		}
		scope.launch(dispatcher) {
			while (true) {
				delay(written)
				setup.now.catalogue.write()
			}
		}
	}

	/** What is known of use written, and nothing watched any more. What is being made is its scope's to stop. */
	override fun close() {
		closed(setup.now.store)
		setup.now.catalogue.write()
	}

	companion object {
		/**
		 * Two million pixels: a picture no larger is decoded each time it is shown, in a few thousandths of a second,
		 * and has no thumbnail kept. A screenshot is about that; a photograph is ten times it.
		 */
		const val DIRECT = 2_000_000L
	}
}
