package prism.browser

import androidx.compose.runtime.Immutable
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.IntStream
import kotlin.io.path.Path
import prism.index.Index
import prism.index.Results
import prism.index.SearchContext
import prism.index.Searched
import prism.index.Usage
import prism.index.Where
import prism.index.query.Flag
import prism.index.query.Query
import prism.index.query.QueryText
import prism.index.query.ReadQuery
import prism.index.query.Term
import prism.index.query.Test
import prism.index.search
import prism.preferences.Order
import prism.preferences.Sort
import prism.index.Kind as EntryKind

/*
 * Searching the index from the picker. Ctrl+Shift+F opens a search over the picker ([Search]): a field, and the first
 * few matches under it as they are found. A match chosen goes to it; the search given (Enter, or its button) shows
 * every match in the listing, as if a folder held them all ([Location.Results], [Listing.Found]).
 *
 * The picker never searches itself: it asks for a search as an effect, and whoever runs it asks a [Finder], which is
 * the daemon's live index when the daemon opens the picker, or an index read once from a file when the picker is run on
 * its own ([StaticFinder]).
 */

/** What the picker searches: an index the daemon keeps current, or one read from a file and kept as it was. */
interface Finder {
	/** What [query] finds, best first, or why it cannot be run. */
	fun find(query: Query): Searched

	/** [paths] were given to the application at [at]: what ranks them, and the folders they came from, higher. */
	fun picked(paths: List<Path>, at: Instant)
}

/**
 * An index searched as it was read, never kept current: what the picker searches when it is run on its own. What is
 * picked ranks higher for as long as it runs, as [usage] says it was used, and is not written anywhere.
 */
class StaticFinder(
	private val index: Index,
	usage: Usage = Usage.NONE,
	private val home: Path = Path(System.getProperty("user.home")),
) : Finder {
	private val usage = AtomicReference(usage)

	override fun find(query: Query): Searched =
		index.search(query, SearchContext(home = home, usage = usage.get()))

	override fun picked(paths: List<Path>, at: Instant) {
		usage.updateAndGet { it.picked(paths.map(Where::of), at) }
	}
}

/** The query [text] asks for, as [choosing] searches it: a folder picker finds only folders, as if `is:dir` were typed. */
fun queryOf(read: ReadQuery, choosing: Choosing): Query =
	if (choosing is Choosing.Folder)
		Query(read.query.terms + Term(Test.Is(setOf(Flag.Dir))))
	else
		read.query

/** The search of the index that Ctrl+Shift+F opens over the picker. What was typed is kept when it is closed. */
@Immutable
sealed interface Search {
	/** Whether its window is open over the picker. */
	val open: Boolean

	/** No index to search: why, which its window says when it is opened. */
	data class Unavailable(
		val why: String,
		override val open: Boolean = false,
	) : Search

	/**
	 * An index to search: what is typed into the field, what the last search that came back found ([found], which can
	 * be for an older [text] while the next one is run), and which of the first few matches the arrows are on (none
	 * while the keyboard is in the field). [showing] says the matches are to be shown in the listing once the search
	 * for [text] comes back, which was asked for before it had.
	 */
	data class Ready(
		val text: String = "",
		override val open: Boolean = false,
		val found: Finding? = null,
		val highlighted: Int? = null,
		val showing: Boolean = false,
	) : Search {
		/** [text] read in the search language: its query, what is wrong with it, and the marks that colour it. */
		val read: ReadQuery by lazy { QueryText.read(text) }

		/** Whether [text] asks for anything yet: a query of no terms (nothing typed, or `ext:` alone) would find all. */
		val searchable: Boolean get() = read.query.terms.isNotEmpty()

		/** What [found] found, where it found matches for [text] as it is now. */
		val matched: Finding.Matched? get() =
			(found as? Finding.Matched)?.takeIf {
				it.text ==
					text
			}

		/** How many matches the window lists: the first few. */
		val listed: Int get() = minOf(LISTED, (found as? Finding.Matched)?.matches?.size ?: 0)
	}

	companion object {
		/** How many matches the search's window lists under its field. */
		const val LISTED = 8
	}
}

/** What a search of the index came back with: for the [text] it was asked for, matches, or why it was not run. */
@Immutable
sealed interface Finding {
	val text: String

	/** A finding is equal only to itself: two searches for one text are two findings, however alike. */
	class Matched(
		override val text: String,
		val matches: Matches,
	) : Finding {
		override fun toString(): String = "Matched($text, ${matches.size})"
	}

	data class Refused(
		override val text: String,
		val why: String,
	) : Finding
}

/** What a search is asked for: to list its first matches in the search's window, to show them all, or to show again. */
enum class Purpose {
	/** The window's matches, as the text is typed: only the latest text's matter. */
	Preview,

	/** Every match in the listing, once found. */
	Show,

	/** The results shown, searched again after the picker changed something on disk, in their place. */
	Refresh,
}

/**
 * What a search of the index found, best first, as the picker shows it. A result's row is made when it is asked for
 * ([rowOf]); what the listing's filters need of every result (what kind of row it is, whether it is hidden) is worked
 * out here, once, on whatever thread the search was run on, never the picker's. So a million results are a few
 * million bytes until they are seen.
 */
class Matches(
	private val results: Results,
	/** The place that was searched, where one was, and not the index of the disk: its entries are what was found. */
	val tree: PlaceTree? = null,
) {
	val size: Int get() = results.size

	/** Each result's [Category]'s ordinal, its sign set where its name starts with a dot. */
	private val facts: ByteArray =
		ByteArray(results.size).also { facts ->
			IntStream.range(0, results.size).parallel().forEach { at ->
				val name = results.nameOf(at)
				val category = categoryOf(results.kindOf(at), name)
				val hidden = name.startsWith(".")

				facts[at] = (category.ordinal or (if (hidden) HIDDEN else 0)).toByte()
			}
		}

	fun nameOf(at: Int): String = results.nameOf(at)

	fun categoryOf(at: Int): Category = CATEGORIES[facts[at].toInt() and HIDDEN.inv()]

	fun isFolder(at: Int): Boolean = categoryOf(at) == Category.Folder

	fun isHidden(at: Int): Boolean = facts[at].toInt() and HIDDEN != 0

	/** What the type column says of the [at]th result. */
	fun typeOf(at: Int): String =
		categoryOf(at).let {
			if (it == Category.Folder)
				"Folder"
			else
				typeOf(nameOf(at), it)
		}

	/** Where the [at]th result is. A name that is not UTF-8 is written as near as Java can, and found missing on disk. */
	fun pathOf(at: Int): Path = results[at].where.let { it.path ?: Path(it.toString()) }

	/** The [at]th result's row, with what [stat] says of it on disk, where it has been read. */
	fun rowOf(at: Int, stat: Stat?): Row {
		// A result of a search in a place is that place's entry, which may be on disk or not, as the place has it.
		entryOf(at)?.let { return it.copy(id = RowId.Found(at)) }

		val category = categoryOf(at)
		val read = stat as? Stat.Read

		return Row(
			id = RowId.Found(at),
			item = Item.OnDisk(pathOf(at)),
			name = nameOf(at),
			category = category,
			type = typeOf(at),
			size = read?.size,
			modified = read?.modified,
			hidden = isHidden(at),
		)
	}

	/** The entry of the place searched that the [at]th result is, as its row there. */
	fun entryOf(at: Int): Row? = tree?.row(pathOf(at).map { it.toString() })

	/** What is known of the [at]th result of a search in a place, which the place's listing said: nothing is read. */
	fun listedStat(at: Int): Stat =
		entryOf(at)?.let { Stat.Read(it.size, it.modified) } ?: Stat.Gone

	override fun toString(): String = "Matches($size)"

	private companion object {
		val CATEGORIES = Category.entries

		/** The bit of a fact that says the name starts with a dot: above every category's ordinal. */
		const val HIDDEN = 0x40

		/** What a row of an entry of this kind and name is, as a folder read from disk would say. */
		fun categoryOf(kind: EntryKind, name: String): Category =
			when (kind) {
				EntryKind.Directory, EntryKind.LinkToDirectory -> Category.Folder
				EntryKind.File, EntryKind.LinkToFile -> prism.browser.categoryOf(name)
				EntryKind.LinkToOther, EntryKind.BrokenLink -> Category.Link
			}
	}
}

/** What reading a result on disk said, or that it is being read. */
@Immutable
sealed interface Stat {
	/** Asked of the disk, and not answered yet. */
	data object Asked : Stat

	/** Its size (none for a folder, with the text its column shows) and when it was last modified. */
	data class Read(
		val size: Shown<Long>?,
		val modified: Instant?,
	) : Stat

	/** Not there any more: left out of the results shown. */
	data object Gone : Stat
}

/**
 * What has been read on disk of each of a search's [size] results, by its place among them: nothing at first. Kept in
 * chunks, so that taking in what a read said copies only the chunks it touched, however many results there are.
 */
@Immutable
class Stats private constructor(
	val size: Int,
	private val chunks: Array<Array<Stat?>?>,
	/** How many are read, or found gone: every one, where [complete]. */
	val known: Int,
) {
	operator fun get(at: Int): Stat? = chunks[at shr SHIFT]?.get(at and MASK)

	/** Whether every result has been read, which ordering by size or date needs. */
	val complete: Boolean get() = known == size

	/** These, with what [read] says of each result it names. */
	fun with(read: Map<Int, Stat>): Stats {
		val copied = chunks.copyOf()
		val fresh = BooleanArray(chunks.size)
		var known = known

		fun counts(stat: Stat?) = stat is Stat.Read || stat is Stat.Gone

		for ((at, stat) in read) {
			val index = at shr SHIFT
			val chunk =
				if (fresh[index])
					copied[index]!!
				else
					(chunks[index]?.copyOf() ?: arrayOfNulls(CHUNK)).also {
						copied[index] = it
						fresh[index] = true
					}
			val was = chunk[at and MASK]

			if (counts(was))
				known--
			if (counts(stat))
				known++
			chunk[at and MASK] = stat
		}

		return Stats(size, copied, known)
	}

	/** Of [places], those not yet asked about, in the order given. */
	fun unasked(places: Iterable<Int>): List<Int> =
		places.filter {
			it in 0 until size &&
				get(it) == null
		}

	/** These, with each of [places] asked about. */
	fun asking(places: List<Int>): Stats = with(places.associateWith { Stat.Asked })

	override fun toString(): String = "Stats($known of $size)"

	companion object {
		private const val SHIFT = 10
		private const val CHUNK = 1 shl SHIFT
		private const val MASK = CHUNK - 1

		/** Nothing read yet of [size] results. */
		fun none(size: Int): Stats = Stats(size, arrayOfNulls((size + CHUNK - 1) shr SHIFT), 0)
	}
}

/**
 * How many results are few enough to be read on disk every one, as soon as they are shown, and ordered by any column
 * at once, as a folder is. More are read as they come into sight, and ordered by name or type (which the index knows)
 * in the background; by size or date, which only the disk knows, not at all.
 */
const val FEW_RESULTS = 10_000

/**
 * [matches]' places ordered by [column], from the least (A, the oldest, the smallest), by name among equals. Size and
 * date are as [stats] read them; a result not read yet is after every one that was.
 */
fun orderOf(matches: Matches, stats: Stats, column: Sort.Column): IntArray {
	val names = Array(matches.size, matches::nameOf)
	val byName = Comparator<Int> { a, b -> naturalOrder(names[a], names[b]) }

	fun read(at: Int) = stats[at] as? Stat.Read

	val by: Comparator<Int> =
		when (column) {
			Sort.Column.Name -> byName
			Sort.Column.Type -> {
				val types = Array(matches.size) { matches.typeOf(it).lowercase() }

				compareBy<Int> { types[it] }.then(byName)
			}
			Sort.Column.Modified ->
				compareBy<Int, Instant?>(nullsLast()) {
					read(it)?.modified
				}.then(byName)
			Sort.Column.Size ->
				compareBy<Int, Long?>(nullsLast()) {
					read(it)?.size?.value
				}.then(byName)
			// A search's results are never ordered by when they were made (Found.orders): by name, should it be asked.
			Sort.Column.Created -> byName
		}

	return (0 until matches.size).sortedWith(by).toIntArray()
}
