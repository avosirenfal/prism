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
import java.util.concurrent.atomic.LongAdder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import prism.index.Away
import prism.index.Change
import prism.index.FileId
import prism.index.FileSystems
import prism.index.IgnoreRules
import prism.index.Ignored
import prism.index.Index
import prism.index.IndexStore
import prism.index.Indexer
import prism.index.Kept
import prism.index.Kind
import prism.index.Name
import prism.index.Results
import prism.index.SearchContext
import prism.index.Searched
import prism.index.Seeking
import prism.index.Volume
import prism.index.VolumeId
import prism.index.Where
import prism.index.places
import prism.index.query.Query
import prism.index.search
import prism.platform.Mount
import prism.shared.Guarded
import prism.shared.Published

/**
 * How far a reading of the index's roots has got: how many entries it has [listed], and how many the index [held]
 * as it was asked, which is about how many there are to list where the index was read before.
 */
data class Indexing(
	val listed: Long,
	val held: Long,
)

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
	/** What is mounted, each time it is asked. */
	val mounts: () -> List<Mount> = FileSystems.SYSTEM::mounted,
	/** Which file system a mount is of; null for one nothing says is the same one the next time ([FileSystems]). */
	val volumes: (Mount) -> Volume? = FileSystems.SYSTEM::volumeOf,
	/** How long the tree of a file system that is not mounted is kept before it is let go. */
	val keepAway: Duration = 30.days,
	val clock: () -> Instant = Instant::now,
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
 *
 * **File systems that come and go.** A file system that is unmounted takes its entries out of the index, as anything
 * no longer on the disk is; but its tree is kept beside the index ([Away]) instead of thrown away, and put back the
 * moment the same file system is mounted again, in the same place or another, then read again for what changed while
 * it was away. Which file system is which is what [LiveSettings.volumes] says of a mount; which is mounted at each
 * place the index holds one is noted as it is seen, since a file system that has gone cannot be asked. The tree is
 * kept wherever its entries would leave the index, whatever takes them out (the folder above read again, a root
 * scanned, the mount point itself removed), and not only when the mounts are looked at ([remounted]), so nothing that
 * happens between two looks loses it. A file system newly mounted under what the index holds is read at once, unless
 * it is one that comes and goes with something outside the machine ([Volume.portable]) and was not asked for. A tree
 * kept and not seen for [LiveSettings.keepAway] is let go ([expire]).
 */
class LiveIndex private constructor(
	private val store: IndexStore,
	indexer: Indexer,
	/** How many entries [indexer] has listed, in every reading since it was made ([Indexer.counting]). */
	private val listed: LongAdder,
	private val settings: LiveSettings,
	private val away: Away,
	/** Called with what a scan read before what it read is made: for a test to hold a scan, and change the disk meanwhile. */
	private val beforeMaking: (Set<Where>) -> Unit,
	read: Set<Where>,
) : AutoCloseable {
	/**
	 * What was changed while something was read, batch by batch as it was seen. A batch is added in one step, however
	 * many came before it: a list copied for each would be copied whole for every batch of a long first scan, on a
	 * machine whose files change all the while.
	 */
	private class Meanwhile private constructor(
		private val last: Collection<Changed>,
		private val before: Meanwhile?,
	) {
		operator fun plus(batch: Collection<Changed>): Meanwhile =
			Meanwhile(batch.toList(), this)

		/** Every change, in the order they were seen. */
		fun all(): List<Changed> =
			generateSequence(this) { it.before }
				.map { it.last }
				.toList()
				.asReversed()
				.flatten()

		companion object {
			val NONE = Meanwhile(emptyList(), null)
		}
	}

	/**
	 * A scan in flight: the [places] it reads, and what was [changed] while it read. One of [whole] roots is the
	 * index being read through, which is said to whoever searches it meanwhile; [from] is how many entries had been
	 * listed when it began.
	 */
	private data class Scan(
		val places: Set<Where>,
		val changed: Meanwhile,
		val whole: Boolean,
		val from: Long,
	)

	/** A place read for searches alone: its own [indexer] (its rules set aside), its [index] once read, and when it was last [used]. */
	private data class AdHoc(
		val place: Where,
		val indexer: Indexer,
		val index: Index?,
		val changed: Meanwhile,
		val ready: CompletableFuture<Unit>,
		val used: Instant,
	)

	/**
	 * The indexer, as what is mounted now makes it: another each time the mounts change. Set as a change is made
	 * ([changing]); read without waiting for one, by a scan and by a search.
	 */
	private var indexer: Indexer by Published(indexer)

	/**
	 * The places read for searches alone. Set as a change is made ([changing]); read without waiting for one by a
	 * search, which so need not wait for a change being made.
	 */
	private var adHoc: Map<Where, AdHoc> by Published(emptyMap())

	private val scanIds = AtomicInteger()

	/**
	 * What a change to the index is made with, and what else changes with it: reached only as a change is made, one
	 * at a time ([changing]). What is done to the index under it is done here, so that it cannot be done outside it.
	 */
	private inner class Changing {
		/** Which file system is mounted at each place the index holds one, as last seen. */
		var mounted: Map<Where, VolumeId> = away.mounted()

		/** The mounts as they were when last looked at; null before the first look. */
		var looked: List<Mount>? = null

		/**
		 * Which file system each mount there is now is of, as it was found when the mount was first seen: asked once
		 * a mount, so that a mount whose file system cannot be asked for a moment is not taken for another's.
		 */
		var known: Map<Mount, Volume> = emptyMap()

		/** The scans in flight, and what has changed while each read. */
		var scans: Map<Int, Scan> = emptyMap()

		/**
		 * Which file system is mounted at each place, of those that say which they are; the later, of two at one place.
		 * A mount seen before is of what it was then; one not yet known is asked again, each time.
		 */
		fun volumesOf(mounts: List<Mount>): Map<Where, Volume> {
			val volumes =
				mounts.mapNotNull { mount ->
					(known[mount] ?: settings.volumes(mount))?.let { mount to it }
				}

			known = volumes.toMap()

			return volumes.associate { (mount, volume) -> Where.of(mount.at) to volume }
		}

		/**
		 * [changes] made in the index, the tree of every file system they would take out of it kept first: one the index
		 * holds at or under a place they change, and that is no longer mounted there.
		 */
		fun make(changes: List<Change>) {
			val under = mounted.filterKeys { place -> changes.any { place.within(it.at) } }

			if (under.isNotEmpty()) {
				val now = volumesOf(settings.mounts())
				val gone = under.filter { (place, volume) -> now[place]?.id != volume }

				gone.forEach { (place, volume) -> keep(place, volume) }
				if (gone.isNotEmpty()) {
					mounted = mounted - gone.keys
					away.note(mounted)
				}
			}
			store.make(changes)
		}

		/** The place read for searches alone that holds [place]: one already, or one begun now. */
		fun adHocFor(place: Where, now: Instant): AdHoc {
			adHoc.values.firstOrNull { place.within(it.place) }?.let { held ->
				return held.copy(used = now).also { adHoc = adHoc + (held.place to it) }
			}

			val rules = indexer.rules.settingAside(place)
			val own = indexer.ruled(rules)
			val begun = AdHoc(place, own, null, Meanwhile.NONE, CompletableFuture(), now)

			adHoc = adHoc + (place to begun)
			adHocScanning.execute {
				try {
					val read = Index.EMPTY.with(own.index(place))

					changing.guarded {
						adHoc[place]?.let { asked ->
							val held = read.with(made(asked.changed.all(), read, own))

							// A place read now holds any read before under it: those are let go.
							adHoc =
								adHoc.filterKeys { !it.within(place) } +
								(place to asked.copy(index = held, changed = Meanwhile.NONE))
						}
					}
					begun.ready.complete(Unit)
				} catch (failed: Throwable) {
					changing.guarded { adHoc = adHoc - place }
					begun.ready.completeExceptionally(failed)
				}
			}

			return begun
		}
	}

	/** Every change to the index is made under this, one at a time. */
	private val changing = Guarded(Changing())

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

	/** The rules of what the index leaves out, as they are now. */
	val rules: IgnoreRules get() = indexer.rules

	/**
	 * How far the reading of the index's roots has got, while they are read through (as the index opens, read again,
	 * or after its rules changed): null while none is, which is nearly always.
	 */
	val indexing: Indexing?
		get() =
			changing.guarded {
				scans.values.filter { it.whole }.minOfOrNull { it.from }?.let {
					Indexing(listed.sum() - it, store.index.size.toLong())
				}
			}

	/** Completes once the roots read as the index opened are made. */
	val opened: CompletableFuture<Unit> =
		scan(read, whole = true) { read.map(indexer::index) }

	/** What is being read now: places scanned again, roots, and places read for searches alone. */
	val reading: Set<Where>
		get() =
			changing.guarded {
				scans.values.flatMap { it.places }.toSet() +
					adHoc.values.filter { it.index == null }.map { it.place }
			}

	/** The file systems that are not mounted whose trees are kept, to be put back when they are. */
	val kept: List<Kept> get() = away.kept

	/** [paths] indexed, as roots of the index, each read as it is now (a root already, read again). */
	fun index(paths: Collection<Path>): CompletableFuture<Unit> {
		val places = paths.map(Where::of).toSet()

		// Asked for outright, they are read whatever is mounted there.
		changing.guarded { indexer = indexer.mounted(indexer.mounts, index.roots + places) }

		return scan(places) { places.map(indexer::index) }
	}

	/**
	 * What is mounted looked at, and the index made to agree, where it has changed since it was last looked at:
	 *
	 * - a file system the index held at a place, and that is no longer mounted there, has its tree kept ([Away]),
	 *   and the place read again, for what is there now;
	 * - a file system mounted now whose tree is kept is put back where it is mounted, as it was, and read again
	 *   for what changed while it was away;
	 * - any other mounted since the last look, under what the index holds, is read, unless the indexer leaves it out
	 *   (the rules do, or it is [Volume.portable] and not asked for).
	 *
	 * Completes once what it read is made.
	 */
	fun remounted(): CompletableFuture<Unit> {
		val mounts = settings.mounts()
		val read = LinkedHashSet<Where>()

		changing.guarded {
			val before = looked

			if (mounts != before) {
				looked = mounts
				indexer = indexer.mounted(mounts)
			}

			val now = volumesOf(mounts)

			for ((place, volume) in mounted)
				if (now[place]?.id != volume) {
					keep(place, volume)
					read += place
				}
			mounted = mounted.filter { (place, volume) -> now[place]?.id == volume }

			val were = before?.map { Where.of(it.at) }?.toSet()

			// What is mounted where the index holds its place and has not noted it: put back, where its tree is kept;
			// noted, unless it is one the indexer leaves out. A place the index does not hold yet (its root still
			// being read) is noted at a later look, once it does.
			for ((place, volume) in now) {
				if (place in mounted || !holds(place))
					continue

				val back = away.take(volume.id, place)

				if (back != null)
					store.make(listOf(Change.Present(back)))
				if (back == null && indexer.leavesOut(place))
					continue
				mounted = mounted + (place to volume.id)
				// Put back, or mounted since the last look: read. At the first look, the roots are being read anyway.
				if (back != null || (were != null && place !in were))
					read += place
			}
			if (mounted != away.mounted())
				away.note(mounted)
		}

		return scan(read) { read.mapNotNull(indexer::reread) }
	}

	/** The trees of file systems not seen mounted for [LiveSettings.keepAway], let go: which they were. */
	fun expire(): List<Kept> =
		away.expire(settings.clock().minus(settings.keepAway.toJavaDuration()))

	/** Whether the index holds [place], or would hold what is put there: a root, or in a folder it holds. */
	private fun holds(place: Where): Boolean =
		place in index.roots ||
			place.parent?.let { index.find(it)?.kind == Kind.Directory } == true

	/** The tree the index holds at [place], which is [volume]'s, kept, where there is anything of it but the place. */
	private fun keep(place: Where, volume: VolumeId) {
		index
			.tree(
				place,
			)?.takeIf { it.size > 1 }
			?.let { away.keep(volume, it, settings.clock()) }
	}

	/**
	 * What the index leaves out made [rules], and the index made to agree with them, as it runs, reading as little as
	 * agreeing takes.
	 *
	 * A rule added reads nothing: what it leaves out is in the index, and is taken out of it, whether the rule names
	 * a folder by its path, folders of a name wherever they are, or what a query finds. A rule taken away has to read
	 * what it left out, which the index does not hold. Where it named a folder by its path, that folder is read, and
	 * nothing else. Where it named folders by name, or by a query, nothing says where on the disk they are but
	 * reading it, so every root is read again; and so it is where the file systems left out changed.
	 *
	 * The places read for searches alone were read by the old rules, and are let go. Completes once what was read is
	 * made.
	 */
	fun ruled(rules: IgnoreRules): CompletableFuture<Unit> {
		val before = indexer.rules

		if (rules == before)
			return CompletableFuture.completedFuture(Unit)

		changing.guarded {
			indexer = indexer.ruled(rules)
			adHoc = emptyMap()
		}

		val added = rules.directories - before.directories
		val removed = before.directories - rules.directories
		val seekingMore = rules.sought.queries - before.sought.queries.toSet()
		val seekingLess = before.sought.queries - rules.sought.queries.toSet()

		val foundOnlyByReading =
			rules.fileSystems != before.fileSystems || rules.sought.home != before.sought.home ||
				removed.any { it !is Ignored.At } || seekingLess.isNotEmpty()

		if (foundOnlyByReading)
			return rescanRoots()

		fun places(of: Set<Ignored>) =
			of.filterIsInstance<Ignored.At>().map { Where.of(it.path) }

		// What the rules added leave out that are not paths: folders of a name, and what a query finds.
		val elsewhere =
			IgnoreRules(
				added.filterNot { it is Ignored.At }.toSet(),
				emptySet(),
				Seeking(seekingMore, rules.sought.home),
			)

		val back = places(removed).filter(::holds).toSet()

		takeOut(places(added).filter { index.find(it) != null } + heldLeftOutBy(elsewhere))

		// What was taken out is written with whatever is made next; nothing waits for it.
		return scan(back) { back.mapNotNull(indexer::reread) }
	}

	/**
	 * What the rules as they are leave out taken out of the index, reading nothing: what an index read by fewer rules
	 * than there are now (one kept by a daemon before a rule was added) is made to agree by.
	 */
	fun leaveOut() = takeOut(heldLeftOutBy(indexer.rules))

	/** The places the index holds that [rules] leave out, by name or by a query: looked for in what it holds. */
	private fun heldLeftOutBy(rules: IgnoreRules): List<Where> =
		if (rules.directories.isEmpty() && rules.sought.none)
			emptyList()
		else
			index.entries().map { it.where }.filter {
				rules.ignores(it) || rules.sought.finds(it)
			}

	/** [places] taken out of the index, each with all that is in it; a root stays, whatever names it. */
	private fun takeOut(places: List<Where>) {
		changing.guarded {
			make(outermost(places).filter { it !in index.roots }.map(Change::Absent))
		}
	}

	/** [places] without those inside another of them: taking a folder out takes out all that is in it. */
	private fun outermost(places: List<Where>): List<Where> {
		val all = places.toSet()

		return all.filter { place ->
			generateSequence(place.parent) { it.parent }.none { it in all }
		}
	}

	/** Every root read again, as it is now: what is done where changes may have been missed. */
	fun rescanRoots(): CompletableFuture<Unit> =
		scan(index.roots, whole = true) {
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

		changing.guarded {
			scans = scans.mapValues { (_, scan) -> scan.copy(changed = scan.changed + changes) }
			make(made(changes, store.index, indexer))
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

		// A change is made under the lock, the disk read for it as it is: a search that took the lock where it has
		// nothing to do under it would wait for that, and so would whoever is typing the query.
		if (adHoc.isNotEmpty())
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
			if (leftOut.isEmpty())
				emptyList()
			else
				changing.guarded { leftOut.map { adHocFor(it, now) }.distinctBy { it.place } }

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

	/** The index written to its file, whole, where it has one and it is behind the index. */
	fun write() = store.write()

	/** Whether the index has a file to be in and is in none yet: none was read, and none has been written. */
	val unwritten: Boolean get() = store.unwritten

	/** The places read for searches alone that no search has asked for since [now] less [LiveSettings.keepAdHoc], let go. */
	fun tidy(now: Instant = Instant.now()) {
		val since = now.minus(settings.keepAdHoc.toJavaDuration())

		changing.guarded {
			adHoc = adHoc.filterValues { it.index == null || !it.used.isBefore(since) }
		}
	}

	/** The scans stopped, and the index written. */
	override fun close() {
		for (executor in listOf(scanning, adHocScanning)) {
			executor.shutdownNow()
			executor.awaitTermination(5, TimeUnit.SECONDS)
		}
		store.write()
	}

	/**
	 * A scan of [places] that [read]s them without holding the index, then makes what it read, and then what was
	 * changed while it read; written to the file once made, where no file holds an index yet.
	 */
	private fun scan(
		places: Set<Where>,
		whole: Boolean = false,
		read: () -> List<Change>,
	): CompletableFuture<Unit> {
		if (places.isEmpty())
			return CompletableFuture.completedFuture(Unit)

		val id = scanIds.incrementAndGet()

		changing.guarded {
			scans = scans + (id to Scan(places, Meanwhile.NONE, whole, listed.sum()))
		}

		return CompletableFuture
			.supplyAsync({
				val changes =
					try {
						read().also { beforeMaking(places) }
					} catch (failed: Throwable) {
						changing.guarded { scans = scans - id }

						throw failed
					}

				changing.guarded {
					val meanwhile = scans.getValue(id).changed.all()

					make(changes)
					make(made(meanwhile, store.index, indexer))
					scans = scans - id
				}
				// The first index there is, is written as soon as it is read: reading it is what takes long, and
				// until it is written a prism that starts has none. After that the file is written when whoever keeps
				// this says ([write]: a daemon, once an hour and as it closes). What was read is made whether or not it
				// can be written now: a write that fails is said by whoever writes next.
				try {
					if (store.unwritten)
						store.write()
				} catch (unwritten: Exception) {
					// Said by whoever writes next.
				}
			}, scanning)
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

			val listed = LongAdder()

			return LiveIndex(
				store,
				indexer.counting(listed).mounted(indexer.mounts, wanted),
				listed,
				settings,
				Away.beside(file),
				beforeMaking,
				read,
			)
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
