package prism.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import prism.index.Change
import prism.index.FileId
import prism.index.Index
import prism.index.IndexStore
import prism.index.Indexer
import prism.index.Name
import prism.index.Results
import prism.index.SearchContext
import prism.index.Searched
import prism.index.Where
import prism.index.places
import prism.index.query.Query
import prism.index.search

/**
 * A change something saw on the disk, as a live index takes it: the [folder] it happened in, by the kernel's device and
 * the folder's inode, and the entry's [name], where the change says which entry it was (null: something in the folder
 * changed, and the folder is read again, one level deep).
 */
data class Changed(
	val folder: FileId,
	val name: Name?,
)

/** How a live index goes about its work. */
data class LiveSettings(
	/**
	 * Whether the roots are read again as the index opens, where its file holds them already: what was changed while
	 * nothing was watching is found so. Roots the file does not hold are read whatever this says.
	 */
	val readRootsOnOpen: Boolean = true,
	/** How long a place read for a search alone is kept, unasked for, before it is let go. */
	val keepAdHoc: Duration = 10.minutes,
)

/** What a search of a live index gave. */
sealed interface LiveSearch {
	/**
	 * The [results] found, in the index and in the places read for searches alone, best first among them all; the
	 * places this search looks under that are still being read ([reading]), whose entries are not among these yet; and
	 * [ready], which completes once they are read, for the search to be run again.
	 */
	data class Found(
		val results: Results,
		val reading: Set<Where>,
		val ready: CompletableFuture<Unit>,
	) : LiveSearch

	/** A query that cannot be run in its context: an alias that is not defined. */
	data class Refused(
		val why: String,
	) : LiveSearch
}

/**
 * An index open and kept current: an [IndexStore] (on disk, or in memory alone) and the [Indexer] that reads the disk
 * for it. It can be told to read places again ([rescan]), to index more ([index]), and what changed where ([changed]);
 * it needs nothing to watch the disk, and a daemon is what tells it what the watcher saw.
 *
 * **Scans and what happens during them.** Reading a large place takes a while, so a scan reads without holding the
 * index, and its result is made once it has read. Whatever is changed meanwhile and said to the index ([changed]) is
 * made at once, as far as the index holds the folder it happened in, and is also kept beside each scan in flight, to be
 * made again once that scan's result is made: the scan may have read the place before or after the change, and the
 * change, made again on the disk as it is then, is right either way. Every change says what is so, so making one twice
 * is harmless. Changes are made one at a time, the disk read for each as it is made, so a change read later is never
 * made before one read earlier.
 *
 * **Places the rules leave out.** A search that looks under a place the rules leave out (an absolute path into
 * `/nix/store`, or `~/.cache`) has that place read for it, with the rules that left it out set aside, into an index
 * of its own, in memory alone: never written, never mixed with the index proper, kept current as the index is, and
 * let go once no search has asked for it for [LiveSettings.keepAdHoc].
 */
class LiveIndex private constructor(
	private val store: IndexStore,
	private val indexer: Indexer,
	private val settings: LiveSettings,
	/** Called with what a scan read before what it read is made: for a test to hold a scan, and change the disk meanwhile. */
	private val beforeMaking: (Set<Where>) -> Unit,
	read: Set<Where>,
) : AutoCloseable {
	/** A scan in flight: the [places] it reads, and what was [changed] while it read. */
	private data class Scan(
		val places: Set<Where>,
		val changed: List<Changed>,
	)

	/** A place read for searches alone: its own [indexer] (its rules set aside), its [index] once read, and when it was last [used]. */
	private data class AdHoc(
		val place: Where,
		val indexer: Indexer,
		val index: Index?,
		val changed: List<Changed>,
		val ready: CompletableFuture<Unit>,
		val used: Instant,
	)

	/** What every change is made under, one at a time. */
	private val lock = Any()
	private val scanIds = AtomicInteger()
	private var scans: Map<Int, Scan> = emptyMap()
	private var adHoc: Map<Where, AdHoc> = emptyMap()

	private val scanning: ExecutorService =
		Executors.newSingleThreadExecutor(
			named("prism-scan"),
		)
	private val adHocScanning: ExecutorService =
		Executors.newSingleThreadExecutor(
			named("prism-ad-hoc"),
		)

	/** The index as it is now. */
	val index: Index get() = store.index

	/** How the index's store was opened: from its file, or empty, or in memory alone. */
	val origin: IndexStore.Origin get() = store.origin

	/** Completes once the roots read as the index opened are made. */
	val opened: CompletableFuture<Unit> = scan(read) { read.map(indexer::index) }

	/** What is being read now: places scanned again, roots, and places read for searches alone. */
	val reading: Set<Where>
		get() =
			synchronized(lock) {
				scans.values.flatMap { it.places }.toSet() +
					adHoc.values.filter { it.index == null }.map { it.place }
			}

	/** [paths] indexed, as roots of the index, each read as it is now (a root already, read again). */
	fun index(paths: Collection<Path>): CompletableFuture<Unit> {
		val places = paths.map(Where::of).toSet()

		return scan(places) { places.map(indexer::index) }
	}

	/** Every root read again, as it is now: what is done where changes may have been missed. */
	fun rescanRoots(): CompletableFuture<Unit> =
		scan(index.roots) {
			index.roots.map(indexer::index)
		}

	/**
	 * Each of [paths], a file or a folder, read again as it is now, with everything under it. Completes once what was
	 * read is made. A path the index does not hold (outside its roots, or left out) changes nothing; [index] adds one.
	 */
	fun rescan(paths: Collection<Path>): CompletableFuture<Unit> {
		val places = paths.map(Where::of).toSet()

		return scan(places) { places.mapNotNull(indexer::reread) }
	}

	/** [changes] made, in the index and in every place read for searches alone, and kept for every scan in flight. */
	fun changed(changes: Collection<Changed>) {
		if (changes.isEmpty())
			return

		synchronized(lock) {
			scans = scans.mapValues { (_, scan) -> scan.copy(changed = scan.changed + changes) }
			store.make(made(changes, store.index, indexer))
			adHoc =
				adHoc.mapValues { (_, it) ->
					when (val held = it.index) {
						null -> it.copy(changed = it.changed + changes)
						else -> it.copy(index = held.with(made(changes, held, it.indexer)))
					}
				}
		}
	}

	/**
	 * [query] searched for, in [context]: in the index, and in the places it looks under that the rules leave out,
	 * each read for searches alone (now, where it has not been, while this search goes on without it).
	 */
	fun search(query: Query, context: SearchContext = SearchContext()): LiveSearch {
		val now = Instant.now()

		tidy(now)

		val found =
			when (val searched = index.search(query, context)) {
				is Searched.Refused -> return LiveSearch.Refused(searched.why)
				is Searched.Found -> searched.results
			}

		val leftOut =
			query.places(context).filter { place ->
				index.find(place) == null && indexer.leavesOut(place) &&
					place.path?.let { Files.isDirectory(it) } == true
			}

		val asked =
			synchronized(lock) { leftOut.map { adHocFor(it, now) }.distinctBy { it.place } }

		val alone =
			asked
				.mapNotNull {
					it.index?.search(query, context) as? Searched.Found
				}.map { it.results }

		return LiveSearch.Found(
			Results.of(listOf(found) + alone),
			asked.filter { it.index == null }.map { it.place }.toSet(),
			CompletableFuture.allOf(*asked.map { it.ready }.toTypedArray()).thenApply { },
		)
	}

	/** What was made since the last flush, written to the index's file (where it has one). */
	fun flush() = store.flush()

	/** The places read for searches alone that no search has asked for since [now] less [LiveSettings.keepAdHoc], let go. */
	fun tidy(now: Instant = Instant.now()) {
		val since = now.minus(settings.keepAdHoc.toJavaDuration())

		synchronized(lock) {
			adHoc = adHoc.filterValues { it.index == null || !it.used.isBefore(since) }
		}
	}

	/** The scans stopped, and what was made written. */
	override fun close() {
		for (executor in listOf(scanning, adHocScanning)) {
			executor.shutdownNow()
			executor.awaitTermination(5, TimeUnit.SECONDS)
		}
		store.flush()
	}

	/**
	 * A scan of [places] that [read]s them without holding the index, then makes what it read, and then what was
	 * changed while it read; written to the file once made.
	 */
	private fun scan(places: Set<Where>, read: () -> List<Change>): CompletableFuture<Unit> {
		if (places.isEmpty())
			return CompletableFuture.completedFuture(Unit)

		val id = scanIds.incrementAndGet()

		synchronized(lock) { scans = scans + (id to Scan(places, emptyList())) }

		return CompletableFuture
			.supplyAsync({
				val changes =
					try {
						read().also { beforeMaking(places) }
					} catch (failed: Throwable) {
						synchronized(lock) { scans = scans - id }

						throw failed
					}

				synchronized(lock) {
					val meanwhile = scans.getValue(id).changed

					store.make(changes)
					store.make(made(meanwhile, store.index, indexer))
					scans = scans - id
				}
				// What was read is made, whether or not it can be written now: a write that fails is said by whoever
				// writes next ([flush], a daemon's every tick), with what was made kept until then.
				try {
					store.flush()
				} catch (unwritten: Exception) {
					Unit
				}
			}, scanning)
	}

	/** The place read for searches alone that holds [place]: one already, or one begun now. Under [lock]. */
	private fun adHocFor(place: Where, now: Instant): AdHoc {
		adHoc.values.firstOrNull { place.within(it.place) }?.let { held ->
			return held.copy(used = now).also { adHoc = adHoc + (held.place to it) }
		}

		val rules = indexer.rules.notIgnoring(indexer.rules.matching(place))
		val own = Indexer(rules, indexer.mounts, indexer.backend, indexer.threads)
		val begun = AdHoc(place, own, null, emptyList(), CompletableFuture(), now)

		adHoc = adHoc + (place to begun)
		adHocScanning.execute {
			try {
				val read = Index.EMPTY.with(own.index(place))

				synchronized(lock) {
					adHoc[place]?.let { asked ->
						val held = read.with(made(asked.changed, read, own))

						// A place read now holds any read before under it: those are let go.
						adHoc =
							adHoc.filterKeys { !it.within(place) } +
							(place to asked.copy(index = held, changed = emptyList()))
					}
				}
				begun.ready.complete(Unit)
			} catch (failed: Throwable) {
				synchronized(lock) { adHoc = adHoc - place }
				begun.ready.completeExceptionally(failed)
			}
		}

		return begun
	}

	companion object {
		/**
		 * The live index whose file is [file] (null: in memory alone), of [roots], read by [indexer]. Opens at once,
		 * with what the file holds (or nothing, where there is none), and reads its roots in the background: those the
		 * file does not hold, and, unless [LiveSettings.readRootsOnOpen] says otherwise, those it does. Roots the file
		 * holds that are no longer asked for are let go. [LiveIndex.opened] completes once the roots are read.
		 */
		fun open(
			file: Path?,
			roots: Collection<Path>,
			indexer: Indexer = Indexer(),
			settings: LiveSettings = LiveSettings(),
		): LiveIndex = open(file, roots, indexer, settings) {}

		internal fun open(
			file: Path?,
			roots: Collection<Path>,
			indexer: Indexer,
			settings: LiveSettings,
			beforeMaking: (Set<Where>) -> Unit,
		): LiveIndex {
			val store = file?.let(IndexStore::open) ?: IndexStore.inMemory()
			val wanted = roots.map(Where::of).toSet()
			val held = store.index.roots
			val forgotten = held.filter { root -> wanted.none { root.within(it) } }

			if (forgotten.isNotEmpty())
				store.make(forgotten.map(Change::Absent))

			val read =
				if (settings.readRootsOnOpen)
					wanted
				else
					wanted - held

			return LiveIndex(store, indexer, settings, beforeMaking, read)
		}

		/**
		 * What [changes] make of [index], read by [indexer]: in each folder they name that the index holds, each entry
		 * named read again, or where any change in the folder names none, the folder reconciled one level deep.
		 */
		internal fun made(
			changes: Collection<Changed>,
			index: Index,
			indexer: Indexer,
		): List<Change> =
			changes.groupBy { it.folder }.flatMap { (folder, seen) ->
				index.foldersAt(folder).flatMap { where ->
					if (seen.any { it.name == null })
						indexer.reconcile(where, index)
					else
						seen.mapNotNull { it.name }.distinct().mapNotNull {
							indexer.reread(
								where.child(it),
							)
						}
				}
			}

		private fun named(name: String) =
			ThreadFactory { work ->
				Thread
					.ofPlatform()
					.name(name)
					.daemon()
					.unstarted(work)
			}
	}
}
