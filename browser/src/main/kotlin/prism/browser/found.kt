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
import prism.preferences.ColumnId
import prism.preferences.Order
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

/** The search of the index that Ctrl+Shift+F opens over the picker, its field empty each time it is opened. */
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
		/** Whether how a query is written is shown under the field (its `?`): not, each time the window opens. */
		val help: Boolean = false,
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

		/** How many matches the window lists, a few of them in sight at once: every one. */
		val listed: Int get() = (found as? Finding.Matched)?.matches?.size ?: 0
	}

	companion object {
		/** How many matches the search's window has in sight under its field; the rest are scrolled to. */
		const val SHOWN = 8
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

	/** The query cannot be run where it was asked: an alias that is not defined, no index to search. */
	data class Refused(
		override val text: String,
		val why: String,
	) : Finding

	/**
	 * The search itself went wrong, which is a mistake of prism's and nothing the query did: what it was ([fault]),
	 * as it was said in full where prism says what goes wrong (standard error, the daemon's log).
	 */
	data class Failed(
		override val text: String,
		val fault: Fault,
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
 * Which of a search's matches a result is, counting from the best, which is the first: its rank. A number of its own
 * kind, so that it cannot be given where a row's place in the listing is wanted, or the other way about: the listing
 * shows the matches filtered and in another order, and the two numbers are the same only by chance. Given out by the
 * [Matches] it is a rank in ([Matches.rank], [Matches.ranks]), so one in hand is one of that search's results.
 */
@JvmInline
value class Rank internal constructor(
	val at: Int,
)

/**
 * Some of a search's results, by their ranks, in an order: numbers kept as numbers, so that the ranks of a million
 * results are four million bytes and none of them is boxed to be put in a list.
 */
class Ranks internal constructor(
	private val ranks: IntArray,
) {
	val size: Int get() = ranks.size

	fun isEmpty(): Boolean = ranks.isEmpty()

	operator fun get(index: Int): Rank = Rank(ranks[index])

	inline fun forEach(each: (Rank) -> Unit) {
		for (index in 0 until size)
			each(this[index])
	}

	fun <T> map(of: (Rank) -> T): List<T> = List(size) { of(this[it]) }

	/** Those [keeps] keeps, in the order they are in. */
	inline fun filter(keeps: (Rank) -> Boolean): Ranks {
		val kept = Building(size)

		forEach {
			if (keeps(it))
				kept += it
		}

		return kept.built()
	}

	/** These from [from] up to [to], as far as there are any. */
	fun between(from: Int, to: Int): Ranks =
		(from.coerceAtLeast(0) to to.coerceAtMost(size)).let { (first, end) ->
			if (first >= end)
				NONE
			else
				Ranks(ranks.copyOfRange(first, end))
		}

	/** These, and then those of [others] that are not among these: each once, the first place it is in kept. */
	fun then(others: Ranks): Ranks {
		val seen = java.util.BitSet()
		val all = Building(size + others.size)

		for (each in listOf(this, others))
			each.forEach {
				if (!seen[it.at]) {
					seen.set(it.at)
					all += it
				}
			}

		return all.built()
	}

	/** These, at most [most] at a time, in order. */
	fun chunked(most: Int): List<Ranks> =
		(0 until size step most).map { Ranks(ranks.copyOfRange(it, minOf(it + most, size))) }

	override fun equals(other: Any?): Boolean =
		other is Ranks && ranks.contentEquals(other.ranks)

	override fun hashCode(): Int = ranks.contentHashCode()

	override fun toString(): String = "Ranks(${ranks.joinToString()})"

	/** Ranks put together one at a time, at most as many as it was made for. */
	class Building(
		most: Int,
	) {
		private val ranks = IntArray(most)
		private var count = 0

		operator fun plusAssign(rank: Rank) {
			ranks[count++] = rank.at
		}

		/** What was put in, in the order it was. */
		fun built(): Ranks = Ranks(ranks.copyOf(count))

		/** What was put in, and then what was put in [after]. */
		fun then(after: Building): Ranks =
			Ranks(
				ranks.copyOf(count + after.count).also {
					after.ranks.copyInto(it, count, 0, after.count)
				},
			)
	}

	companion object {
		val NONE = Ranks(IntArray(0))
	}
}

/**
 * Every one of a search's results, each once, in an order: by a column, from the least. Made only by putting every
 * rank in order ([sorted]), so it can leave no result out and hold none twice, and a listing drawn through one shows
 * what was found and nothing else.
 */
class Permutation private constructor(
	private val ranks: IntArray,
) {
	val size: Int get() = ranks.size

	/** The rank that is [index]th in this order. */
	operator fun get(index: Int): Rank = Rank(ranks[index])

	override fun toString(): String = "Permutation($size)"

	companion object {
		/** Every rank below [size], in the order [by] puts them in. */
		fun sorted(size: Int, by: Comparator<Rank>): Permutation =
			Permutation(
				(0 until size)
					.sortedWith { a, b -> by.compare(Rank(a), Rank(b)) }
					.toIntArray(),
			)
	}
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
			// Read from each name where the index keeps it: a text made of each would be a million of them for a
			// broad search, thrown away as soon as it had been looked at.
			IntStream.range(0, results.size).parallel().forEach { at ->
				val category =
					when (results.kindOf(at)) {
						EntryKind.Directory, EntryKind.LinkToDirectory -> Category.Folder
						EntryKind.File, EntryKind.LinkToFile -> categoryOf(results.extensionOf(at))
						EntryKind.LinkToOther, EntryKind.BrokenLink -> Category.Link
					}
				val hidden = results.startsWithDot(at)

				facts[at] = (category.ordinal or (if (hidden) HIDDEN else 0)).toByte()
			}
		}

	/** The [index]th result's rank, counting from the best; none where there are not so many. */
	fun rank(index: Int): Rank? = index.takeIf { it in 0 until size }?.let(::Rank)

	/** Every result's rank, best first. */
	val ranks: Ranks get() = Ranks(IntArray(size) { it })

	fun nameOf(rank: Rank): String = results.nameOf(rank.at)

	fun categoryOf(rank: Rank): Category =
		CATEGORIES[facts[rank.at].toInt() and HIDDEN.inv()]

	fun isFolder(rank: Rank): Boolean = categoryOf(rank) == Category.Folder

	fun isHidden(rank: Rank): Boolean = facts[rank.at].toInt() and HIDDEN != 0

	/** What the type column says of the result at [rank]. */
	fun typeOf(rank: Rank): String =
		categoryOf(rank).let {
			if (it == Category.Folder)
				"Folder"
			else
				typeOf(nameOf(rank), it)
		}

	/** Where the result at [rank] is. A name that is not UTF-8 is written as near as Java can, and found missing on disk. */
	fun pathOf(rank: Rank): Path =
		results[rank.at].where.let {
			it.path
				?: Path(it.toString())
		}

	/** The row of the result at [rank], with what [stat] says of it on disk, where it has been read. */
	fun rowOf(rank: Rank, stat: Stat?): Row {
		val id = RowId.Found(this, rank)

		// A result of a search in a place is that place's entry, which may be on disk or not, as the place has it.
		entryOf(rank)?.let { return it.copy(id = id) }

		val category = categoryOf(rank)
		val read = stat as? Stat.Read

		return Row(
			id = id,
			item = Item.OnDisk(pathOf(rank)),
			name = nameOf(rank),
			category = category,
			type = typeOf(rank),
			size = read?.size,
			modified = read?.modified,
			hidden = isHidden(rank),
		)
	}

	/** The entry of the place searched that the result at [rank] is, as its row there. */
	fun entryOf(rank: Rank): Row? = tree?.row(pathOf(rank).map { it.toString() })

	/** What is known of the result at [rank] of a search in a place, which the place's listing said: nothing is read. */
	fun listedStat(rank: Rank): Stat =
		entryOf(rank)?.let { Stat.Read(it.size, it.modified) } ?: Stat.Gone

	/**
	 * Every result in order by [column], from the least (A, the oldest, the smallest), by name among equals. Size and
	 * date are as [stats] read them; a result not read is after every one that was.
	 */
	fun orderedBy(column: ColumnId, stats: Stats): Permutation {
		val names = Array(size) { results.nameOf(it) }
		val byName = Comparator<Rank> { a, b -> naturalOrder(names[a.at], names[b.at]) }

		fun read(rank: Rank) = stats[rank] as? Stat.Read

		fun byText(text: (Rank) -> String): Comparator<Rank> {
			val texts = Array(size) { text(Rank(it)) }

			return Comparator<Rank> { a, b -> naturalOrder(texts[a.at], texts[b.at]) }.then(byName)
		}

		return Permutation.sorted(
			size,
			when (column) {
				ColumnId.Name -> byName
				ColumnId.Type -> byText { typeOf(it).lowercase() }
				ColumnId.Extension -> byText { extensionOf(names[it.at]).orEmpty() }
				ColumnId.Location -> byText { pathOf(it).parent?.toString().orEmpty() }
				ColumnId.Modified ->
					compareBy<Rank, Instant?>(nullsLast()) { read(it)?.modified }.then(byName)
				ColumnId.Size ->
					compareBy<Rank, Long?>(nullsLast()) { read(it)?.size?.value }.then(byName)
				// Results are never ordered by when they were made or trashed (Found.orders): by name, if asked.
				ColumnId.Created, ColumnId.Deleted -> byName
			},
		)
	}

	override fun toString(): String = "Matches($size)"

	private companion object {
		val CATEGORIES = Category.entries

		/** The bit of a fact that says the name starts with a dot: above every category's ordinal. */
		const val HIDDEN = 0x40
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

	/**
	 * Asked of the disk, and prism went wrong reading it ([Answer.Broke]): nothing is known of it, and it is not
	 * asked again. Shown with no size and no date, and after every result read where they are ordered by those.
	 */
	data object Unknown : Stat
}

/**
 * What has been read on disk of each of a search's [size] results, by its rank: nothing at first. Kept in
 * chunks, so that taking in what a read said copies only the chunks it touched, however many results there are.
 */
@Immutable
class Stats private constructor(
	val size: Int,
	private val chunks: Array<Array<Stat?>?>,
	/** How many are read, found gone, or not to be known: every one, where [complete]. */
	val known: Int,
) {
	operator fun get(rank: Rank): Stat? = chunks[rank.at shr SHIFT]?.get(rank.at and MASK)

	/** Whether every result has been read, which ordering by size or date needs. */
	val complete: Boolean get() = known == size

	/** These, with what [stat] says of each of [ranks], by where it is among them. */
	private fun with(ranks: Ranks, stat: (Int) -> Stat): Stats {
		val copied = chunks.copyOf()
		val fresh = BooleanArray(chunks.size)
		var known = known

		fun counts(stat: Stat?) = stat != null && stat != Stat.Asked

		for (which in 0 until ranks.size) {
			val at = ranks[which].at
			val index = at shr SHIFT
			val chunk =
				(
					copied[index].takeIf { fresh[index] } ?: chunks[index]?.copyOf()
						?: arrayOfNulls(CHUNK)
				).also {
					copied[index] = it
					fresh[index] = true
				}
			val now = stat(which)

			if (counts(chunk[at and MASK]))
				known--
			if (counts(now))
				known++
			chunk[at and MASK] = now
		}

		return Stats(size, copied, known)
	}

	/**
	 * These, with what was [read] of [ranks], one for one. A result nothing was read of (fewer were read than were
	 * asked for) is not known, which is what is so.
	 */
	fun read(ranks: Ranks, read: List<Stat>): Stats =
		with(ranks) { read.getOrElse(it) { Stat.Unknown } }

	/** Of [ranks], those not yet asked about, in the order given. */
	fun unasked(ranks: Ranks): Ranks =
		ranks.filter {
			it.at in 0 until size &&
				get(it) == null
		}

	/** These, with each of [ranks] asked about. */
	fun asking(ranks: Ranks): Stats = with(ranks) { Stat.Asked }

	/** These, with each of [ranks] not to be known: asked about, and prism went wrong reading it. */
	fun unknown(ranks: Ranks): Stats = with(ranks) { Stat.Unknown }

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
