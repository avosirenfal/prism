package prism.browser

import androidx.compose.runtime.Immutable
import java.nio.file.Path
import java.time.Instant
import prism.desktop.Place
import prism.desktop.Storage
import prism.desktop.StoreKind
import prism.desktop.TerminalChoice
import prism.index.query.AliasProblem
import prism.index.query.Aliases
import prism.preferences.Builtin
import prism.preferences.ColumnId
import prism.preferences.Columns
import prism.preferences.Filters
import prism.preferences.Kind
import prism.preferences.Layout
import prism.preferences.ListingKind
import prism.preferences.Listings
import prism.preferences.Look
import prism.preferences.MenuSpec
import prism.preferences.Order
import prism.preferences.QuickAccess
import prism.preferences.QuickItem
import prism.preferences.RecentSection
import prism.preferences.Role
import prism.preferences.Sort
import prism.shared.Published

/*
 * What the picker shows, as immutable data. The view (view.kt) is a pure function of a [BrowserState]: it draws what
 * the state says and reports what the user did as a [BrowserEvent]. The next state is worked out from the last and
 * the event by one pure function (step.kt), which is also what asks for a folder to be read or a typed name to be
 * looked up, as an [Effect] for whoever runs the picker (session.kt) to carry out.
 *
 * What the picker is choosing decides what can be chosen, and that is in the types: a picker for one file holds at
 * most one, a picker that saves always has a name, and the button that accepts is worked out from those, never set
 * beside them. That a row chosen, or under the cursor, or being renamed, is a row the listing shows is how a state is
 * read: it is given what was asked to be marked, and what it says is marked is what of that its listing shows
 * ([Marks]). So no state can name a row it does not show, whatever it was made from, and making one never fails.
 */

/** A row of the listing, by an identity that survives sorting and filtering. */
@Immutable
sealed interface RowId {
	/** An entry of the folder shown, by its name in it. */
	data class Named(
		val name: String,
	) : RowId

	/**
	 * A result of a search, by its rank among that search's matches: of [of], and of no other search's. Matches are
	 * equal only to themselves, so the same search made again gives other ids, and one kept from before names no row
	 * of the new results: it is simply not among them, without anybody having to let it go.
	 */
	data class Found(
		val of: Matches,
		val rank: Rank,
	) : RowId

	/** An entry of the place shown, by what the place's command called it. */
	data class Entry(
		val key: String,
	) : RowId

	/** An entry of a trash: the trash's folder, and the name it keeps the entry under there. */
	data class Trashed(
		val bin: Path,
		val name: String,
	) : RowId

	/**
	 * A row of the Recents page, by where it is on disk and which [section] it is in: its rows are of many folders,
	 * and may share a name, and a folder can be in two sections (lately picked, and often gone to).
	 */
	data class Recent(
		val path: Path,
		val section: RecentSection,
	) : RowId
}

/** The entry of the folder shown named [name]: how the folder's rows are known. */
fun RowId(name: String): RowId = RowId.Named(name)

/** What a row is, for its icon and its type column. */
enum class Category {
	Folder,
	Image,
	Audio,
	Video,
	Archive,
	Code,
	Text,
	Document,
	Pdf,
	Link,
	Other,
}

/**
 * A value a column shows, with the text it shows it as, made together so they cannot disagree: the listing is
 * ordered by the value and drawn as the text, so the date column orders by time and not by the words for it.
 */
@Immutable
data class Shown<T : Comparable<T>>(
	val value: T,
	val text: String,
) : Comparable<Shown<T>> {
	override fun compareTo(other: Shown<T>): Int = value.compareTo(other.value)
}

/**
 * When a file was made: kept by its file system ([At]), or not kept, which many file systems (FAT, NFS, and others)
 * do not. Unix's "ctime" is another thing, when the file's inode last changed; this is its birth time. It is read
 * with the rest of what a folder's row shows, in the one call that reads its size and its date, so a row of a folder
 * has always one or the other: there is no row whose time is still to come.
 */
@Immutable
sealed interface Created {
	data object Unkept : Created

	/** Not a thing its listing says: a search's result, an entry of the trash or of a place, which have no such column. */
	data object Unsaid : Created

	data class At(
		val time: Instant,
	) : Created

	/** The time, where there is one. */
	val at: Instant? get() = (this as? At)?.time
}

/** One entry of the folder shown, or one result of a search, with everything the view shows of it worked out. */
@Immutable
data class Row(
	val id: RowId,
	/** What it is: a file or a folder on disk, or an entry of the place shown, which only that place knows. */
	val item: Item,
	val name: String,
	val category: Category,
	/** What the type column says: "Folder", "PNG image", "Kotlin source". */
	val type: String,
	/** The size in bytes, said as the size column says it ("4.2 KB"); null for a folder, or a result not read yet. */
	val size: Shown<Long>?,
	/** When it was last modified; null where that is not read, or could not be. */
	val modified: Instant?,
	val hidden: Boolean,
	/** When it was made, where its listing says (a folder's does, of every row, as it is read). */
	val created: Created = Created.Unsaid,
) {
	val isFolder: Boolean get() = category == Category.Folder

	/** Where it is on disk, absolute; null for an entry of a place that is nowhere on disk. */
	val path: Path? get() = (item as? Item.OnDisk)?.path

	/** When it was trashed, for an entry of the trash. */
	val deleted: Instant? get() = (item as? Item.InTrash)?.deleted

	/**
	 * The folder it is in, as a path: on disk, where it is; in the trash, where it was; in a place, the folders it sits
	 * under there.
	 */
	val location: String?
		get() =
			when (val item = item) {
				is Item.OnDisk -> item.path.parent?.toString()
				is Item.InTrash ->
					item.trashed.original.parent
						?.toString()
				is Item.InPlace -> Part.Parent.of(item.key)
			}
}

/** A choice of which files the listing shows, as the asking application names them. Folders are always shown. */
@Immutable
data class TypeFilter(
	val label: String,
	/** Name patterns (`*.png`, `report-??.txt`), matched without case; none for every file. */
	val patterns: List<String>,
) {
	private val compiled: List<Regex> by lazy { patterns.map(::globOf) }

	fun admits(name: String): Boolean =
		patterns.isEmpty() || compiled.any { it.matches(name) }

	companion object {
		val ALL_FILES = TypeFilter("All files", emptyList())
	}
}

/**
 * A shell pattern as a regular expression: `*` any run of characters, `?` any one, `[...]` one of those (`[!...]`
 * one not of those), and anything else itself.
 */
internal fun globOf(pattern: String): Regex {
	val parts =
		Regex("""\[!?[^\]]+]|[*?]|[^*?\[]+|\[""").findAll(pattern).map {
			val part = it.value

			when {
				part == "*" -> ".*"
				part == "?" -> "."
				part.length > 2 && part.startsWith("[") && part != "[!]" -> {
					val members = part.substring(1, part.length - 1)
					val negated = members.startsWith("!")
					val listed = members.removePrefix("!").replace("\\", "\\\\").replace("[", "\\[")

					if (negated)
						"[^$listed]"
					else
						"[$listed]"
				}
				else -> Regex.escape(part)
			}
		}

	return Regex(parts.joinToString(""), RegexOption.IGNORE_CASE)
}

/** The kind of entry [category] is of, as the filters above the listing choose among them; none for a link to nothing, or a file of no known type, which is shown only while no kind is chosen. */
fun kindOf(category: Category): Kind? =
	when (category) {
		Category.Folder -> Kind.Folders
		Category.Image -> Kind.Images
		Category.Video -> Kind.Videos
		Category.Audio -> Kind.Audio
		Category.Document, Category.Pdf, Category.Text -> Kind.Documents
		Category.Archive -> Kind.Archives
		Category.Code -> Kind.Code
		Category.Link, Category.Other -> null
	}

/**
 * How the listing arranges what the window shows: its [order], and which rows it leaves out ([filters], the asking
 * application's [filter] of names, and the words of the [query]). Worked out from the window's looks for what it shows
 * (`BrowserState.arrangement`), never kept beside them.
 */
@Immutable
data class Arrangement(
	val order: Order = Order.By(Sort.BY_NAME),
	val filters: Filters = Filters(),
	/** The asking application's filter of files, by name. */
	val filter: TypeFilter = TypeFilter.ALL_FILES,
	/** Words a row's name must contain to be shown, without case; blank for every row. */
	val query: String = "",
	/** What the Recents page puts away and leaves out: nothing, of any other listing. */
	val recent: RecentArranged = RecentArranged.NONE,
) {
	fun admits(row: Row): Boolean = admits(row.category, row.hidden) { row.name }

	/**
	 * Whether an entry of [category], hidden or not, is shown: its [name] asked for only where a filter of names needs
	 * it, since for a search's results it is made from the index's bytes for the asking.
	 */
	fun admits(category: Category, hidden: Boolean, name: () -> String): Boolean {
		val folder = category == Category.Folder

		if (!filters.hidden.shows(hidden, folder) ||
			(filters.kinds.isNotEmpty() && kindOf(category) !in filters.kinds)
		)
			return false

		val named = (!folder && filter.patterns.isNotEmpty()) || query.isNotBlank()

		if (!named)
			return true

		val text = name()

		return (folder || filter.admits(text)) &&
			(query.isBlank() || text.contains(query.trim(), ignoreCase = true))
	}
}

/**
 * How a window's listings look: as the preferences say ([defaults]), and as the user has made them in it since, each
 * kind of listing's look ([own]) and each kind's filters in each layout ([ownFilters]). What it shows looks as these say
 * for its kind, wherever it goes: a folder's order, given once, is every folder's in the window. The preferences
 * changed, the window takes up what they now say of each kind they changed ([relisted]).
 */
@Immutable
data class Looks(
	val defaults: Listings = Listings(),
	val own: Map<ListingKind, Look> = emptyMap(),
	val ownFilters: Map<Pair<ListingKind, Layout>, Filters> = emptyMap(),
) {
	fun look(role: Role, kind: ListingKind): Look = own[kind] ?: defaults.look(role, kind)

	fun filters(role: Role, kind: ListingKind, layout: Layout): Filters =
		ownFilters[kind to layout] ?: defaults.filters(role, kind, layout)

	fun looking(kind: ListingKind, look: Look): Looks = copy(own = own + (kind to look))

	fun filtering(kind: ListingKind, layout: Layout, filters: Filters): Looks =
		copy(ownFilters = ownFilters + ((kind to layout) to filters))

	/**
	 * These, the preferences now saying [listings] for a window of [role]: each kind whose look they changed looks as
	 * they now say, what the window had made of it its own given up, and each kind and layout whose filters they
	 * changed likewise. What they did not change, the window keeps as it made it.
	 */
	fun relisted(role: Role, listings: Listings): Looks =
		Looks(
			listings,
			own.filterKeys { kind -> defaults.look(role, kind) == listings.look(role, kind) },
			ownFilters.filterKeys { (kind, layout) ->
				defaults.filters(role, kind, layout) == listings.filters(role, kind, layout)
			},
		)
}

/**
 * How large the listing is drawn, as Ctrl and the mouse's wheel make it: in the list, its rows' type and icons; in the
 * gallery, its tiles and their pictures. A notch of the wheel is a step of [SCALE]; the gallery goes on past its steps
 * to [Whole], one tile as large as the listing, whatever the window.
 */
@Immutable
sealed interface Zoom {
	/** The [step]th size of [SCALE]: [factor] times the usual. */
	data class By(
		val step: Int,
	) : Zoom {
		init {
			require(step in SCALE.indices) { "no step $step of the scale" }
		}

		val factor: Float get() = SCALE[step]
	}

	/** One tile at a time, as large as the listing has room for: the gallery's largest. */
	data object Whole : Zoom

	companion object {
		/** The sizes, each a notch of the wheel from the next, as many times the usual size. */
		val SCALE = listOf(0.75f, 0.875f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f)

		// Made each time they are read, not kept: a size kept here would be made while this object is being set up,
		// and a size checks its step against [SCALE], which is not there until this object is.
		val NORMAL: By get() = By(2)

		/** The list's largest: twice the usual, beyond which a row is larger than anyone reads. */
		val LIST_LARGEST: By get() = By(5)

		/** The step of [SCALE] nearest [factor] times the usual size. */
		fun nearest(factor: Float): By =
			By(
				SCALE.indices.minBy {
					kotlin.math.abs(SCALE[it] - factor)
				},
			)
	}
}

/** How large each layout is drawn, each kept apart, so a gallery made large leaves the list as it was. */
@Immutable
data class Zooms(
	val list: Zoom.By = Zoom.NORMAL,
	val gallery: Zoom = Zoom.NORMAL,
) {
	operator fun get(layout: Layout): Zoom =
		when (layout) {
			Layout.List -> list
			Layout.Gallery -> gallery
		}
}

/** The picker's own changes to the disk that can be taken back, the latest last, and those taken back, to make again. */
@Immutable
data class Changes(
	val undo: List<Undoable> = emptyList(),
	val redo: List<Undoable> = emptyList(),
)

/**
 * What the user did, [done], which names it in the menus, and the change [by] which it is taken back (kept in
 * [Changes.undo]) or made again (kept in [Changes.redo]). Both are kept because the second cannot say what the first
 * was: a copy and a putting back from the trash are each undone by moving files to the trash.
 */
@Immutable
data class Undoable(
	val done: Change,
	val by: Change,
)

/**
 * The rows the listing shows, in order, with where each is: what the view draws and what the keyboard moves
 * through, so a row is found by its identity without searching for it. A folder's are a list; a search's are made as
 * they are asked for, so that a million results are not a million rows.
 */
@Immutable
sealed class Rows {
	abstract val size: Int

	/** The row at [index], counting from the top. */
	abstract operator fun get(index: Int): Row

	/** The identity of the row at [index], without making the row. */
	abstract fun idAt(index: Int): RowId

	/** The name of the row at [index], without making the row. */
	abstract fun nameAt(index: Int): String

	abstract fun indexOf(id: RowId): Int?

	/**
	 * These rows, with what they are looked up by worked out now, by whoever asks: off the windows' thread, by what
	 * read a large folder there, where it would otherwise be worked out the first time a row is looked for.
	 */
	abstract fun ready(): Rows

	/** The rows, in order, as a list made as it is read. */
	val list: List<Row> =
		object : AbstractList<Row>() {
			override val size: Int get() = this@Rows.size

			override fun get(index: Int): Row = this@Rows[index]
		}

	fun isEmpty(): Boolean = size == 0

	operator fun contains(id: RowId): Boolean = indexOf(id) != null

	operator fun get(id: RowId): Row? = indexOf(id)?.let(::get)

	/** [ids] in the order they are shown. */
	fun inOrder(ids: Set<RowId>): List<Row> = ids.mapNotNull(::indexOf).sorted().map(::get)

	/** The rows from [from] to [to], whichever way round, both included. */
	fun between(from: RowId, to: RowId): Set<RowId> {
		val ends = listOfNotNull(indexOf(from), indexOf(to)).sorted()

		return if (ends.size < 2)
			setOf(to)
		else
			(ends[0]..ends[1]).mapTo(LinkedHashSet(), ::idAt)
	}

	/** A folder's rows, as a list. */
	class Of(
		private val rows: List<Row>,
	) : Rows() {
		private val positions: Map<RowId, Int> by lazy {
			rows.indices.associateBy { rows[it].id }
		}

		override val size: Int get() = rows.size

		override fun get(index: Int): Row = rows[index]

		override fun idAt(index: Int): RowId = rows[index].id

		override fun nameAt(index: Int): String = rows[index].name

		override fun indexOf(id: RowId): Int? = positions[id]

		override fun ready(): Rows = also { positions.size }

		override fun toString(): String = "Rows(${rows.map { it.name }})"
	}

	/** A search's rows: the results at [order]'s ranks, each made from [matches] and [stats] as it is asked for. */
	class Found private constructor(
		private val matches: Matches,
		private val stats: Stats,
		private val order: Ranks,
		/** Where each result is shown, by its rank; -1 for one not shown. */
		private val shown: Lazy<IntArray>,
	) : Rows() {
		constructor(matches: Matches, stats: Stats, order: Ranks) : this(
			matches,
			stats,
			order,
			lazy {
				IntArray(matches.size) { -1 }.also { at ->
					for (index in 0 until order.size)
						at[order[index].at] = index
				}
			},
		)

		private val shownAt: IntArray get() = shown.value

		/**
		 * The same rows, in the same places, with [stats] known of them: for what was read of some of them since,
		 * where none was found gone. Nothing is arranged again, and where each is shown is what it was.
		 */
		fun knowing(stats: Stats): Found = Found(matches, stats, order, shown)

		override fun ready(): Rows = also { shown.value }

		override val size: Int get() = order.size

		override fun get(index: Int): Row = order[index].let { matches.rowOf(it, stats[it]) }

		override fun idAt(index: Int): RowId = RowId.Found(matches, order[index])

		override fun nameAt(index: Int): String = matches.nameOf(order[index])

		/** Where a result of these matches is shown; nowhere, for a result of any other search's. */
		override fun indexOf(id: RowId): Int? =
			(id as? RowId.Found)
				?.takeIf { it.of === matches }
				?.let { shownAt[it.rank.at] }
				?.takeIf { it >= 0 }

		/** The ranks of the rows from [from] up to [to], as far as there are rows. */
		fun ranksBetween(from: Int, to: Int): Ranks = order.between(from, to)

		override fun toString(): String = "Rows($size of ${matches.size} results)"
	}

	companion object {
		val NONE: Rows = Of(emptyList())

		operator fun invoke(rows: List<Row>): Rows = Of(rows)
	}
}

/**
 * A function's last answer, kept for a value that is worked out from an immutable one: asked again for the same
 * key, it is the same answer, without working it out again. The keeping is the only thing about it that changes,
 * and nothing outside can see it change.
 */
internal class Memo<K, V>(
	/** An answer had already, for the key it is of: what another memo of the same function last kept. */
	had: Pair<K, V>? = null,
	private val compute: (K) -> V,
) {
	private val last = Published(had)

	/** The answer kept, and the key it is for; none, where it has not been asked. */
	val kept: Pair<K, V>? get() = last.now

	operator fun invoke(key: K): V =
		last.now?.takeIf { it.first == key }?.second
			?: compute(key).also { last.set(key to it) }
}

/**
 * Reads of the whole of the folder shown, under way: how many ([reads], at least one), and what the folder was told
 * had changed since the first of them began ([touched]). A read says what the folder held at some moment while it
 * was read, which may be before a change it was told of meanwhile: so what changed meanwhile is read once the last
 * of them is back, after it, and never before, where what the read then said would undo it.
 */
@Immutable
data class Rereading(
	val reads: Int,
	val touched: Touch = Touch.NOTHING,
)

/** What a listing waits for before it shows what was asked of it ([BrowserState.wait]). */
@Immutable
sealed interface Wait {
	/** The folder to be read. */
	data object Reading : Wait

	/** A search's results to be put in order by [column]: read on disk, every one, or worked out behind. */
	data class Ordering(
		val column: ColumnId,
	) : Wait

	companion object {
		/**
		 * How long a wait lasts before anything says there is one, in milliseconds: most are over sooner (a folder
		 * is read in a few), and one that is shows nothing of itself, where a word or a spinner would only flash.
		 */
		const val GRACE_MILLIS = 100L
	}
}

/** What the folder shown holds: being read, read, or why it could not be. */
@Immutable
sealed interface Listing {
	/** Being read. */
	data object Reading : Listing

	/** What a read of a folder gives. */
	sealed interface Read : Listing

	/**
	 * The folder's entries, every one this picker could show (a folder picker's holds no files), hidden ones too,
	 * in no particular order. What the listing shows of them is [rows]: sorted once for each order, and narrowed
	 * again only when what narrows them changes, so that moving the cursor through a large folder does neither.
	 *
	 * A listing is equal only to itself: two reads of a folder are two listings, however alike. But a folder read
	 * again, or told that some of its entries changed, is the listing it was where nothing of it differs ([reread],
	 * [with]), and shows the very rows it showed where nothing it shows does: so that what changes on disk and is
	 * not seen (a hidden file written to, while hidden files are not shown) changes nothing that is drawn.
	 */
	class Shown private constructor(
		val entries: List<Row>,
		/** These in the order a listing they came of was last in: its own, with what changed put in place ([with]). */
		sortedBefore: Pair<Order, List<Row>>?,
		/** The rows a listing these came of last showed, where these show the same ([with]). */
		arrangedBefore: Pair<Arrangement, Rows>?,
	) : Read {
		constructor(entries: List<Row>) : this(entries, null, null)

		private val sortedBy = Memo(sortedBefore) { sorted(entries, it) }
		private val arranged =
			Memo(arrangedBefore) { Rows(sortedBy(it.order).filter(it::admits)) }

		/** The entries by what each is known by: worked out once, when a change is first made to them. */
		private val known: Map<RowId, Row> by lazy { entries.associateBy { it.id } }

		fun rows(arrangement: Arrangement): Rows = arranged(arrangement)

		/**
		 * This listing with [changed] of its entries as each now is: its row, or none for one that is gone. Where none
		 * of them differs from what is held, this very listing. Where some do, another, which shows the very rows
		 * this last showed unless one of those that differ is, or was, among them: the rows are arranged again only
		 * for a change that is seen. And then they are not put in order again, every one: those that came are put
		 * in their places among the rest, in the order this was last in.
		 */
		fun with(changed: Map<RowId, Row?>): Shown {
			val differing = changed.filter { (id, row) -> known[id] != row }

			if (differing.isEmpty())
				return this

			val seen =
				arranged.kept?.takeIf { (arrangement, _) ->
					differing.none { (id, row) ->
						known[id]?.let(arrangement::admits) == true ||
							row?.let(arrangement::admits) == true
					}
				}

			val come = differing.values.filterNotNull()

			return Shown(
				entries.filterNot { it.id in differing } + come,
				sortedBy.kept?.let { (order, sorted) ->
					order to merged(sorted, differing.keys, come, order)
				},
				seen,
			)
		}

		/** This listing, read again and found to hold [read]: [with] every entry that came, went or changed. */
		fun reread(read: Shown): Shown =
			with((known.keys + read.known.keys).associateWith { read.known[it] })

		override fun toString(): String = "Shown(${entries.size} entries)"
	}

	/** The folder could not be read: what the system said, or that prism went wrong reading it ([Fault.said]). */
	data class Unreadable(
		val why: String,
	) : Read

	/**
	 * A search's results, shown as a folder holding them all would be: [matches], best first; what has been read of
	 * each on disk so far ([stats]); and their orders by the columns that have been worked out in the background
	 * ([orders], for results too many to order at once). What it shows is [rows], worked out again only when what
	 * arranges them changes. A result read and found gone is not shown.
	 *
	 * A listing is equal only to itself.
	 */
	class Found private constructor(
		val finding: Finding.Matched,
		val stats: Stats,
		val orders: Map<ColumnId, Permutation>,
		/** The rows as a listing these came of last arranged them, where they are these rows still ([knowing]). */
		arrangedBefore: Pair<Arrangement, Rows.Found>?,
	) : Listing {
		val matches: Matches get() = finding.matches

		/** Whether they are few enough to be read every one, and ordered by any column at once ([FEW_RESULTS]). */
		val few: Boolean get() = matches.size <= FEW_RESULTS

		private val sortedBy = Memo<ColumnId, Permutation> { matches.orderedBy(it, stats) }
		private val arranged = Memo(arrangedBefore) { arrange(it) }

		constructor(
			finding: Finding.Matched,
			stats: Stats,
			orders: Map<ColumnId, Permutation> = emptyMap(),
		) : this(finding, stats, orders, null)

		fun rows(arrangement: Arrangement): Rows = arranged(arrangement)

		/**
		 * These results with [stats] known of them, which is what was known but for [changed]. A million results
		 * are read a screenful at a time, and each batch read is a new listing: so where no row can have moved (none
		 * of [changed] was found gone, which takes its row away, and they are too many for their order to be by
		 * what was read), the rows last arranged are kept as they are, with what is now known of them, and not
		 * arranged again.
		 */
		fun knowing(stats: Stats, changed: Ranks): Found {
			val moved = few || changed.any { stats[it] == Stat.Gone }

			// Rows that would move are kept where they are all the same while the order asked for cannot be had
			// yet: they are shown as they were until it can, and put in it once.
			return Found(
				finding,
				stats,
				orders,
				arranged.kept
					?.takeIf { (arrangement, _) -> !moved || awaited(arrangement.order, stats) != null }
					?.let { (arrangement, rows) -> arrangement to rows.knowing(stats) },
			)
		}

		/**
		 * Whether the results can be ordered by [column]: by any, where they are few (all read at once, and ordered as
		 * they are read); by those the index knows (name and type) where they are not; never by what only the disk
		 * knows of too many to read.
		 */
		fun orders(column: ColumnId): Boolean =
			when (column) {
				ColumnId.Name, ColumnId.Type, ColumnId.Extension, ColumnId.Location -> true
				ColumnId.Modified, ColumnId.Size -> few
				// When a result was made is read for a folder's rows alone; and a result was never trashed.
				ColumnId.Created, ColumnId.Deleted -> false
			}

		/** The results in order by [column], where that is worked out; for too many, once it is worked out behind. */
		private fun orderedBy(column: ColumnId): Permutation? =
			when {
				!orders(column) -> null
				few -> sortedBy(column)
				else -> orders[column]
			}

		/** The column [order] asks for that is still being worked out behind, if it is. */
		fun pending(order: Order): ColumnId? =
			(order as? Order.By)
				?.sort
				?.by
				?.takeIf { orders(it) && !few && it !in orders }

		/**
		 * The column [order] is by that these cannot be shown in order by yet, if there is one: one being worked out
		 * behind ([pending]), or, of few results, a size or a date while those are still being read. Until it can be,
		 * they are shown as they last were ([rows]): never in an order made of half of what it is by.
		 */
		fun awaited(order: Order): ColumnId? = awaited(order, stats)

		private fun awaited(order: Order, stats: Stats): ColumnId? =
			pending(order)
				?: (order as? Order.By)
					?.sort
					?.by
					?.takeIf { few && it in READ_FROM_DISK && !stats.complete }

		/**
		 * The rows shown: filtered as a folder's are, then in the arrangement's order (best first, where that is not
		 * an order these can be had in), folders first.
		 */
		private fun arrange(arrangement: Arrangement): Rows.Found {
			// Not to be had in that order yet: as they were last shown, in the order they were in, where they were
			// shown (and with what is now left out, where that changed); none of them, where they were not.
			if (awaited(arrangement.order) != null)
				return arranged.kept?.let { (last, rows) ->
					when {
						last.copy(order = arrangement.order) == arrangement -> rows
						awaited(last.order) == null -> arrange(arrangement.copy(order = last.order))
						else -> rows
					}
				} ?: Rows.Found(matches, stats, Ranks.NONE)

			val sort = (arrangement.order as? Order.By)?.sort
			val sorted = sort?.let { orderedBy(it.by) }
			val descending = sort?.ascending == false

			// There can be millions, arranged again as each batch of them is read: so into arrays, nothing boxed.
			val folders = Ranks.Building(matches.size)
			val files = Ranks.Building(matches.size)

			for (index in 0 until matches.size) {
				val rank =
					when {
						sorted == null -> matches.rank(index) ?: continue
						descending -> sorted[matches.size - 1 - index]
						else -> sorted[index]
					}

				val kept =
					stats[rank] != Stat.Gone &&
						arrangement.admits(matches.categoryOf(rank), matches.isHidden(rank)) {
							matches.nameOf(rank)
						}

				when {
					!kept -> Unit
					matches.isFolder(rank) -> folders += rank
					else -> files += rank
				}
			}

			return Rows.Found(matches, stats, folders.then(files))
		}

		override fun toString(): String = "Found(${matches.size} results, $stats)"

		private companion object {
			/** What a result's row is ordered by that only reading it on disk says. */
			val READ_FROM_DISK = setOf(ColumnId.Modified, ColumnId.Size)
		}
	}

	/**
	 * The Recents page's rows ([RecentPage]): shown in the page's own order, section after section, whatever order
	 * the window's folders are in, and narrowed as a folder's are.
	 *
	 * A listing is equal only to itself.
	 */
	class Recent(
		val page: RecentPage,
	) : Listing {
		private val arranged = Memo<Arrangement, RecentPage.Shown> { page.shown(it) }

		/** What the page shows as [arrangement] has it: its rows, and the headings they are under. */
		fun shown(arrangement: Arrangement): RecentPage.Shown = arranged(arrangement)

		fun rows(arrangement: Arrangement): Rows = shown(arrangement).rows

		override fun toString(): String = "Recent($page)"
	}
}

/** Where the picker is: in a folder, or among a search's results, which it shows as if a folder held them all. */
@Immutable
sealed interface Location {
	data class Folder(
		val path: Path,
	) : Location

	/**
	 * A search's results; of a search made in a place, [within] is that place, which is all that was searched. Where
	 * the search was made within pinned searches, [refines] is those, and what was found is found by them all. The
	 * results are [pinned] once the user pins them, or where they were found from results that were: a search begun
	 * from them then is made within them. That is said of the view, so it is kept wherever the view is, in the history
	 * as here.
	 */
	data class Results(
		val finding: Finding.Matched,
		val within: Place? = null,
		val refines: Refines = Refines.NONE,
		val pinned: Boolean = false,
	) : Location {
		/** What a search begun from these is made within: these and what they refine, where they are pinned. */
		val refining: Refines get() =
			if (pinned)
				refines + finding.text
			else
				Refines.NONE

		/** Every search these are the results of, the outermost first: what they refine, then their own. */
		val searches: List<String> get() = refines.texts + finding.text

		/** These, pinned or not: the same results, which the view shows as it did. */
		val unpinned: Results get() = copy(pinned = false)
	}

	/**
	 * A place, as a command listed it: [placed] says which, [within] the folder of it shown (the names of the folders
	 * gone into, outermost first), and [tree] what the listing gave, kept so that going about the place, and coming
	 * back to it, does not list it again; null until the first listing arrives.
	 */
	data class Place(
		val placed: Placed,
		val within: List<String> = emptyList(),
		val tree: PlaceTree? = null,
	) : Location

	/** The trash: every trash's entries, the home trash's and each drive's, together. */
	data object Trash : Location

	/** The Recents page: the files lately made, and the folders pickers were lately answered from. */
	data object Recents : Location

	/**
	 * The System page: the machine's drives, what is on each and how full it is, as [storage] says, as it was last
	 * read; null until the first read arrives. It lists no files. It [shows] one kind of store at a time (those in the
	 * machine, removable ones, the network's), and its [folded] stores (by their keys) show their bars alone.
	 */
	data class System(
		val storage: Storage? = null,
		val shows: StoreKind = StoreKind.Internal,
		val folded: Set<String> = emptySet(),
	) : Location
}

/**
 * What says which listing a location shows: the location itself, but for whether a search's results are pinned,
 * which changes nothing of what they list. What the view keeps of a listing (how far it is scrolled) it keeps by this.
 */
val Location.listed: Location get() = (this as? Location.Results)?.unpinned ?: this

/** Where the keyboard's cursor goes when the next read of the folder shown arrives. */
@Immutable
sealed interface Landing {
	val row: RowId

	/** On a row, chosen or not as [choose] says: the folder just left, when going up; the file the path named. */
	data class On(
		override val row: RowId,
		val choose: Boolean,
	) : Landing

	/** On a row, chosen, with its name being typed over: a folder just made, as a file manager names one. */
	data class Renaming(
		override val row: RowId,
	) : Landing
}

/** A row whose name is being typed over in the listing: the row, and its new name as typed so far. */
@Immutable
data class Renaming(
	val row: RowId,
	val text: String,
)

/**
 * What the picker is asked to choose, and what has been chosen so far. Each kind holds exactly what it can: a
 * picker for one file cannot hold two, and one that saves always has a name to save under.
 *
 * Opening, the name field shows what has been chosen until something is typed into it; from then on, what was typed
 * is what the picker accepts ([typed]), until a row is chosen again. Saving, the field is the name to save under and
 * nothing else: choosing a row never writes into it.
 */
@Immutable
sealed interface Choosing {
	/** One file to open. A folder can be chosen too, to be opened when the picker is accepted. */
	data class OneFile(
		val chosen: RowId? = null,
		val typed: String? = null,
	) : Choosing

	/** Files to open, as many as are chosen; folders among them are left out of what is opened. */
	data class Files(
		val chosen: Set<RowId> = emptySet(),
		val typed: String? = null,
	) : Choosing

	/**
	 * A name to save under, in the folder shown. A row chosen is only chosen: the [name] is what is typed, and what
	 * is saved under, whatever is chosen. A file is saved over by opening it (a double click, Enter on it), once the
	 * user has said so.
	 */
	data class SaveAs(
		val name: String,
		val chosen: RowId? = null,
	) : Choosing

	/** A folder: the one shown, or one chosen among its rows, or one typed. */
	data class Folder(
		val chosen: RowId? = null,
		val typed: String? = null,
	) : Choosing

	/**
	 * Nothing to give back: the explorer's, where what is chosen is what the user acts on (opens, copies, trashes),
	 * and a file accepted is opened with its application rather than handed to one.
	 */
	data class Exploring(
		val chosen: Set<RowId> = emptySet(),
	) : Choosing

	/** The rows this has chosen. */
	val rows: Set<RowId>
		get() =
			when (this) {
				is OneFile -> setOfNotNull(chosen)
				is Files -> chosen
				is SaveAs -> setOfNotNull(chosen)
				is Folder -> setOfNotNull(chosen)
				is Exploring -> chosen
			}

	/** Whether this can hold more than one row. */
	val many: Boolean get() = this is Files || this is Exploring
}

/**
 * What is being typed into the listing to go to a row by its name: the letters [typed] so far, each within [PAUSE] of
 * the one before; when the last of them was typed ([at], in milliseconds), which is what a pause is measured from;
 * and whether a row was [found] for them. It is there only while it is being typed: a pause ends it, and what is
 * typed after that names a row anew.
 */
@Immutable
data class Jump(
	val typed: String,
	val at: Long,
	val found: Boolean,
) {
	companion object {
		/** How long a pause in typing ends the letters that name a row, in milliseconds. */
		const val PAUSE = 1000L
	}
}

/** Where the keyboard is in the listing: on [at], with a range chosen by Shift reaching from [anchor]. */
@Immutable
data class Cursor(
	val at: RowId,
	val anchor: RowId,
)

/** The places behind and ahead of the one shown: [back]'s last is the one Back goes to, [forward]'s first Forward's. */
@Immutable
data class History(
	val back: List<Location> = emptyList(),
	val forward: List<Location> = emptyList(),
)

/** The path across the top: shown as steps to click, or being typed into. */
@Immutable
sealed interface Address {
	data object Crumbs : Address

	data class Editing(
		val text: String,
	) : Address
}

/** Something the user must answer before anything else, drawn over the picker. */
@Immutable
sealed interface Notice {
	/** Nothing is at what was typed. */
	data class NotFound(
		val text: String,
	) : Notice

	/** What was typed is a file, where a folder was wanted. */
	data class NotAFolder(
		val text: String,
	) : Notice

	/** The name to save under is a file already: saving replaces it, if the user says so. */
	data class Replace(
		val path: Path,
	) : Notice

	/**
	 * Something to delete for good, if the user says so: asked for (Shift+Delete, the trash's own menus), or, where
	 * [noTrash] says why, what the Delete key would have moved to a trash, on a drive that has none.
	 */
	data class ConfirmErase(
		val erasing: Erasing,
		val noTrash: String? = null,
	) : Notice {
		/**
		 * Whether the keyboard starts on the way out of it, so that Enter pressed in passing deletes nothing: where
		 * deleting for good is not what was asked for (Delete, on a drive with no trash), or is the whole trash at
		 * once. Asked to delete what is chosen for good (Shift+Delete, or its menu), the user means it, and Enter
		 * says yes.
		 */
		val wary: Boolean get() = noTrash != null || erasing == Erasing.Everything
	}

	/** Something could not be done: what ("Couldn't rename “a.txt”"), and what is said of why ([detail]). */
	sealed interface Trouble : Notice {
		val doing: String
		val detail: String
	}

	/** Something the user asked for could not be done: what, and why, as the system or the command said it. */
	data class Failed(
		override val doing: String,
		val why: String,
	) : Trouble {
		override val detail: String get() = why
	}

	/**
	 * Something could not be done because prism itself went wrong doing it ([Answer.Broke]): what, and what went
	 * wrong, which is nothing the system said and nothing the user did, and is said to be neither.
	 */
	data class Broke(
		override val doing: String,
		val fault: Fault,
	) : Trouble {
		override val detail: String get() = fault.said
	}

	/** A name was typed among a search's results, which are in no one folder, with no result chosen to go beside. */
	data class NoFolder(
		val text: String,
	) : Notice

	/** A line of text an action asks for before it is done: [prompt], and what is typed so far. */
	data class Asking(
		val prompt: String,
		val text: String,
	) : Notice

	/** An action to be done once the user says yes: what it is called, and what it asks. */
	data class ConfirmAction(
		val label: String,
		val question: String,
	) : Notice

	/**
	 * The search shown, to be kept as an alias: the [query] it would stand for, its [name] as typed so far, and the
	 * aliases there are ([among]), which say whether it can be one more of them ([problem]).
	 */
	data class NamingAlias(
		val query: String,
		val name: String,
		val among: Aliases,
	) : Notice {
		/** What stops it from being kept, as the aliases' own page would say it: nothing, where it can be. */
		val problem: AliasProblem? by lazy { Aliases.unfitAdded(among.written, name, query) }
	}
}

/**
 * What went wrong inside prism, in a line: the kind of failure it was, and what it said. Made only from the failure
 * ([of]), so nothing can be shown as prism's mistake that was not one, and it is never the system's words or the
 * user's: what the disk or a command refused is said as that ([Notice.Failed], [Listing.Unreadable]).
 */
@Immutable
@ConsistentCopyVisibility
data class Fault private constructor(
	val line: String,
) {
	/** What a window says of it: whose mistake it is, the line, and where all of it is. */
	val said: String
		get() = "$MISTAKE\n$line. See prism's log for details."

	companion object {
		const val MISTAKE =
			"Something went wrong in prism. This is a bug in prism, not something you did."

		fun of(failure: Throwable): Fault =
			Fault(
				listOfNotNull(
					failure.javaClass.simpleName.ifEmpty { failure.javaClass.name },
					failure.message,
				).joinToString(": "),
			)
	}
}

/** The button that accepts: what it says, and whether what has been chosen is enough to press it. */
@Immutable
data class Accept(
	val label: String,
	val enabled: Boolean,
)

/** One step of the path to the folder shown. */
@Immutable
data class Crumb(
	val label: String,
	val path: Path,
)

/** Something the sidebar shows, to go to. */
@Immutable
sealed interface SidebarItem {
	val label: String

	/** Where it goes on disk; none for the trash, which is many folders. */
	val path: Path?

	/** One of prism's own folders: the home folder, or one of its well-known folders, at [place]. */
	data class Own(
		val builtin: Builtin,
		val place: Place,
	) : SidebarItem {
		override val label: String get() = place.label
		override val path: Path get() = place.path
	}

	/** A folder the user pinned. */
	data class Pinned(
		val place: Place,
	) : SidebarItem {
		override val label: String get() = place.label
		override val path: Path get() = place.path
	}

	/** The trash. */
	data object Trash : SidebarItem {
		override val label: String get() = "Trash"
		override val path: Path? get() = null
	}

	/** The System page: the machine's drives, and how full each is. */
	data object System : SidebarItem {
		override val label: String get() = "System"
		override val path: Path? get() = null
	}

	/** The Recents page: what was lately made, and where pickers were lately answered from. */
	data object Recents : SidebarItem {
		override val label: String get() = "Recents"
		override val path: Path? get() = null
	}

	/** A drive. */
	data class Drive(
		val place: Place,
	) : SidebarItem {
		override val label: String get() = place.label
		override val path: Path get() = place.path
	}
}

/**
 * The sidebar: its quick access ([quick], as the user arranged it, showing those of prism's own folders that are there,
 * [known]), then the [drives], each under its heading.
 */
@Immutable
data class Sidebar(
	val quick: QuickAccess,
	val known: Map<Builtin, Place>,
	val drives: List<Place>,
) {
	@Immutable
	data class Group(
		val heading: String,
		val items: List<SidebarItem>,
		/** Whether the user arranges what it shows, in a window of its own. */
		val arranged: Boolean,
	)

	/** Quick access as the user arranged it, every entry of it, shown or hidden: what arranging it shows. */
	val arranging: List<Arranged>
		get() =
			quick.items.mapNotNull { item ->
				when (item) {
					is QuickItem.Own -> Arranged(itemOf(item.place), item.place, item.shown)
					is QuickItem.Pinned ->
						Arranged(
							SidebarItem.Pinned(pinnedPlaceOf(item.path)),
							null,
							shown = true,
						)
				}
			}

	/** What quick access shows, in order: each entry shown, of prism's own folders only those that are there. */
	val shown: List<SidebarItem>
		get() = arranging.filter { it.shown && it.item != null }.mapNotNull { it.item }

	/** What the sidebar shows, under each heading: with the trash where it is [trash], where quick access shows it. */
	fun groups(trash: Boolean = true): List<Group> =
		listOf(
			Group(
				"Quick access",
				shown.filter { trash || it != SidebarItem.Trash },
				arranged = true,
			),
			Group("This computer", drives.map(SidebarItem::Drive), arranged = false),
		)

	/** Whether quick access shows [path]. */
	fun shows(path: Path): Boolean = shown.any { it.path == path }

	/** The item [builtin] is: one of prism's pages, or one of its own folders where it is there; null where not. */
	private fun itemOf(builtin: Builtin): SidebarItem? =
		when (builtin) {
			Builtin.Trash -> SidebarItem.Trash
			Builtin.System -> SidebarItem.System
			Builtin.Recents -> SidebarItem.Recents
			else -> known[builtin]?.let { SidebarItem.Own(builtin, it) }
		}

	/** A folder pinned, named as the desktop names it where it is one of its own, and by its own name otherwise. */
	private fun pinnedPlaceOf(path: Path): Place =
		known.values.firstOrNull { it.path == path }
			?: Place(path.fileName?.toString() ?: "/", Place.Kind.Folder, path)

	/**
	 * An entry of quick access as its window shows it: [item], what it shows where it is there (null for one of prism's
	 * own folders that is not); [builtin], where it is one of prism's own, which is hidden rather than removed.
	 */
	@Immutable
	data class Arranged(
		val item: SidebarItem?,
		val builtin: Builtin?,
		val shown: Boolean,
	)
}

/**
 * What is marked among a listing's rows: what is chosen, where the cursor is, and which row's name is being typed
 * over. Made only by keeping, of what was asked to be marked, what [rows] show ([of]): so each of them is a row
 * shown, and none can be a row of another folder, or one a filter has since taken away.
 */
internal class Marks private constructor(
	val choosing: Choosing,
	val cursor: Cursor?,
	val renaming: Renaming?,
) {
	companion object {
		fun of(rows: Rows, choosing: Choosing, cursor: Cursor?, renaming: Renaming?): Marks =
			Marks(
				choosing.within(rows),
				cursor.within(rows),
				renaming?.takeIf {
					it.row in
						rows
				},
			)
	}
}

/**
 * The marks a state last read of what it was given, kept for the states copied from it: most steps change neither
 * what is marked nor what is shown, and such a copy is given the same marks without a look at any row. The keeping
 * is the only thing about it that changes, and it says nothing of the state: every one is equal to every other.
 */
class Marked {
	private class Read(
		val rows: Rows,
		val choosing: Choosing,
		val cursor: Cursor?,
		val renaming: Renaming?,
		val marks: Marks,
	)

	private val last = Published<Read?>(null)

	internal fun of(
		rows: Rows,
		choosing: Choosing,
		cursor: Cursor?,
		renaming: Renaming?,
	): Marks =
		last.now
			?.takeIf {
				it.rows === rows && it.choosing === choosing && it.cursor === cursor &&
					it.renaming === renaming
			}?.marks
			?: Marks.of(rows, choosing, cursor, renaming).also {
				last.set(Read(rows, choosing, cursor, renaming, it))
			}

	override fun equals(other: Any?): Boolean = other is Marked

	override fun hashCode(): Int = 0

	override fun toString(): String = "Marked"
}

/** What is chosen, kept where it is in [rows]: this itself, where all of it is. */
internal fun Choosing.within(rows: Rows): Choosing {
	fun one(chosen: RowId?) = chosen == null || chosen in rows

	fun all(chosen: Set<RowId>) = chosen.all { it in rows }

	return when (this) {
		is Choosing.OneFile -> takeIf { one(chosen) } ?: copy(chosen = null)
		is Choosing.Files ->
			takeIf { all(chosen) }
				?: copy(chosen = chosen.filterTo(LinkedHashSet()) { it in rows })
		is Choosing.SaveAs -> takeIf { one(chosen) } ?: copy(chosen = null)
		is Choosing.Folder -> takeIf { one(chosen) } ?: copy(chosen = null)
		is Choosing.Exploring ->
			takeIf { all(chosen) }
				?: copy(chosen = chosen.filterTo(LinkedHashSet()) { it in rows })
	}
}

/** The cursor, where the row it is on is in [rows]; reaching from that row alone, where its anchor is not. */
internal fun Cursor?.within(rows: Rows): Cursor? =
	this?.takeIf { it.at in rows }?.let {
		if (it.anchor in rows)
			it
		else
			Cursor(it.at, it.at)
	}

/**
 * The filters of names the asking application offers, and the one in use, which is one of them: made only from the
 * filters ([of]), and changed only to another of them ([using]), so the one in use is never one that was not offered,
 * and there is always one.
 */
@Immutable
@ConsistentCopyVisibility
data class Offered private constructor(
	val filters: List<TypeFilter>,
	val using: TypeFilter,
) {
	/** These, with [filter] in use, where it is one of them; as they are, where it is not. */
	fun using(filter: TypeFilter): Offered =
		if (filter in filters)
			Offered(filters, filter)
		else
			this

	companion object {
		/** Every file, which is what is offered where nothing is. */
		val ALL = Offered(listOf(TypeFilter.ALL_FILES), TypeFilter.ALL_FILES)

		/** [filters] offered, the first in use, or [using] where it is one of them; [ALL], where none is offered. */
		fun of(filters: List<TypeFilter>, using: TypeFilter? = null): Offered =
			filters.firstOrNull()?.let { Offered(filters, it).using(using ?: it) } ?: ALL
	}
}

/** Everything the picker shows. */
@Immutable
data class BrowserState(
	val title: String,
	/** What is to be chosen, and what was asked to be chosen so far: [choosing] is what of that the listing shows. */
	private val chose: Choosing,
	/** Where the picker is: a folder (absolute and normalized), or a search's results. */
	val at: Location,
	/** The user's home, which the path across the top starts from where it can, and `~` names. */
	val home: Path,
	val history: History,
	val sidebar: Sidebar,
	val listing: Listing,
	/** How each kind of listing looks in this window, and what each leaves out: what [arrangement] is worked out from. */
	val looks: Looks,
	/** The filters of names the asking application offers, and the one in use ([filters], [typeFilter]). */
	val offered: Offered,
	/** Words a row's name must contain to be shown, without case; blank for every row. */
	val query: String,
	/** Where the cursor was asked to be: [cursor] is there, where the listing shows that row. */
	private val pointed: Cursor?,
	val address: Address,
	val notice: Notice?,
	/** A name being looked up on disk, whose answer is waited for; an answer for anything else is too late. */
	val waiting: Lookup?,
	val layout: Layout = Layout.List,
	val zooms: Zooms = Zooms(),
	/**
	 * The size at which one of the gallery's tiles fills the listing, as the view last measured it: the most the
	 * gallery can be zoomed before a tile no longer fits. Nothing limits it until it is measured.
	 */
	val whole: Float = Float.POSITIVE_INFINITY,
	/** What the picker changed on disk, to undo and to redo. */
	val changes: Changes = Changes(),
	/** Where the cursor goes when the next read of the folder arrives; nowhere in particular when null. */
	val landing: Landing? = null,
	/** The row whose name was asked to be typed over: [renaming] is that, where the listing shows the row. */
	private val naming: Renaming? = null,
	/** The search of the index, as Ctrl+Shift+F opens it; unavailable where there is no index. */
	val search: Search = Search.Unavailable(NO_INDEX),
	/** The user's search aliases, as the preferences define them: what a `name@` in a search stands for. */
	val aliases: Aliases = Aliases.NONE,
	/** The rows in sight, by their places in [rows], as the view last said; null before it has. */
	val sight: IntRange? = null,
	/** What is being typed into the listing to go to a row by its name, while it is. */
	val jump: Jump? = null,
	/** The reads of the whole of the folder shown that are under way, where any is ([Rereading]). */
	val rereading: Rereading? = null,
	/** The user's actions and places, with prism's own: what the menus offer beyond what the browser does itself. */
	val actions: Actions = Actions.NONE,
	/** How the context menus are arranged, as the preferences say: their entries in order, and when each is shown. */
	val menus: MenuSpec = MenuSpec(),
	/** The terminal chosen: what says whether a terminal opens in a folder, and whether commands run in one. */
	val terminal: TerminalChoice = TerminalChoice.Found,
	/** An action begun and waiting for an answer (a folder being chosen in another window, a line being typed). */
	val pending: Pending? = null,
	/** What is under way and waited on (an action's commands, a place being listed): shown, and to be stopped. */
	val running: List<Work> = emptyList(),
	/** What the asking application calls the button that accepts ("Upload", "Attach"); its own words where null. */
	val acceptLabel: String? = null,
	/** Whether the window that arranges quick access is open over the browser. */
	val arrangingQuick: Boolean = false,
	/** The application the picker is for, where the desktop could tell which: whose recent folders are its own. */
	val asker: AppKey? = null,
	/** The sections of the Recents page shown as their headings alone: the user's, every window's. */
	val recentsCollapsed: Set<RecentSection> = emptySet(),
	/** Whether files are shown by small pictures of themselves: the user's choice, every window's. */
	val thumbnails: Boolean = true,
	/** What the user has hidden from the Recents page: theirs, every window's. */
	val recentsHidden: Set<Path> = emptySet(),
	/** Whether this window's Recents page shows what is hidden from it all the same: an explorer's alone, asked to. */
	val recentsShowHidden: Boolean = false,
	/** What was last read of the marks given, for a copy that changes none of them to be given again. */
	private val marked: Marked = Marked(),
) {
	/**
	 * What is marked among the rows shown: of what was asked to be, what they show. Read as the state is made, from
	 * what it was made of alone (what kind of thing is chosen is as it was asked, whatever rows are shown), and for a
	 * copy that changed none of it, not read again.
	 */
	private val marks: Marks =
		marked.of(
			rowsOf(listing, arrangement),
			chose,
			pointed,
			naming,
		)

	/** What is to be chosen, and what has been chosen so far: rows the listing shows, every one. */
	val choosing: Choosing = marks.choosing

	/** Where the keyboard is in the listing: on a row it shows, or nowhere. */
	val cursor: Cursor? = marks.cursor

	/** The row whose name is being typed over: one the listing shows, or none. */
	val renaming: Renaming? = marks.renaming

	/** The type filters the asking application offers; [typeFilter] is one of them. */
	val filters: List<TypeFilter> get() = offered.filters

	/** The asking application's filter of names in use. */
	val typeFilter: TypeFilter get() = offered.using

	/** The folder shown; none among a search's results. */
	val folder: Path? get() = (at as? Location.Folder)?.path

	/** The place shown, where one is. */
	val place: Location.Place? get() = at as? Location.Place

	/** The place the window is in: the one shown, or the one whose search's results are. */
	val inPlace: Location.Place? get() = place ?: (at as? Location.Results)?.within

	/** Where the window is, as an action's conditions name it. */
	val where: Where
		get() =
			when (val at = at) {
				is Location.Folder -> Where.Folder(at.path)
				// Results of a search in a place are in that place, at its top: its actions are for them too.
				is Location.Results -> at.within?.let { Where.Place(it.placed) } ?: Where.Results
				is Location.Place -> Where.Place(at.placed, at.within)
				Location.Trash -> Where.Trash
				is Location.System -> Where.System
				// Files and folders of anywhere, as a search's results are: what is done to those is done to these.
				Location.Recents -> Where.Results
			}

	/** Whether the trash is shown. */
	val inTrash: Boolean get() = at == Location.Trash

	/** The System page shown, where it is. */
	val system: Location.System? get() = at as? Location.System

	/** What the window is for, as how its listings look goes. */
	val role: Role get() =
		if (chose is Choosing.Exploring)
			Role.Explorer
		else
			Role.Picker

	/** What kind of listing it shows. */
	val kind: ListingKind
		get() =
			when (val at = at) {
				is Location.Folder -> ListingKind.Folder
				is Location.Results -> ListingKind.Results
				is Location.Place -> ListingKind.Place(at.placed.type.id)
				Location.Trash -> ListingKind.Trash
				// It lists no files: as far as how a listing would look goes, it is a folder's, which it never draws.
				is Location.System -> ListingKind.Folder
				// Its rows are in its own order and drawn its own way: narrowed as a folder's are, and no more.
				Location.Recents -> ListingKind.Folder
			}

	/** How what it shows looks: its order and its columns. */
	val look: Look get() = looks.look(role, kind)

	/** The listing's columns. */
	val columns: Columns get() = look.columns(kind)

	/** Which hidden entries, and which kinds of file, what it shows shows, drawn as it is. */
	val entryFilters: Filters get() = looks.filters(role, kind, layout)

	/** How the listing is arranged: worked out from its look and its filters, the application's filter and the query. */
	val arrangement: Arrangement get() =
		Arrangement(
			look.order,
			entryFilters,
			typeFilter,
			query,
			recentAt(at, recentsCollapsed, recentsHidden, recentsShowHidden),
		)

	/** The search whose results are shown, where they are. */
	val results: Finding.Matched? get() = (at as? Location.Results)?.finding

	/**
	 * Whether the Recents page shown has something hidden from it that this window may show: an explorer's alone,
	 * since a picker is another application's window.
	 */
	val recentsHides: Boolean
		get() =
			choosing is Choosing.Exploring &&
				(listing as? Listing.Recent)?.page?.hides(recentsHidden) == true

	/**
	 * Where a name typed is looked for: in the folder shown, or, among a search's results, beside the one chosen;
	 * nowhere where none is.
	 */
	val base: Path? get() = folder ?: chosenRows.singleOrNull()?.path?.parent

	/** The rows the listing shows, its arrangement applied; none while it is read, or when it could not be. */
	val rows: Rows get() = rowsOf(listing, arrangement)

	/**
	 * What is waited for before the listing can show what was asked of it, if anything is: the folder to be read,
	 * or a search's results to be put in the order asked for. Until then it shows what it showed, or nothing: never
	 * what it has so far, in whatever order that happens to be in.
	 */
	val wait: Wait? get() =
		when (val listing = listing) {
			Listing.Reading -> Wait.Reading
			is Listing.Found -> listing.awaited(arrangement.order)?.let(Wait::Ordering)
			is Listing.Shown, is Listing.Recent, is Listing.Unreadable -> null
		}

	/** The path to [folder], as steps from the home folder where it is under it and from the root otherwise. */
	val crumbs: List<Crumb> by lazy { folder?.let { crumbsOf(it, home) }.orEmpty() }

	/** What the place shown is called: the folder's name, or what the search was for. */
	val placeName: String
		get() =
			when (val at = at) {
				is Location.Folder -> crumbs.last().label
				is Location.Results ->
					"results for ${at.searches.joinToString(" › ") { "“$it”" }}"
				is Location.Place -> at.within.lastOrNull() ?: at.placed.title
				Location.Trash -> "Trash"
				is Location.System -> "System"
				Location.Recents -> "Recents"
			}

	/** The rows chosen, in the order they are shown. */
	val chosenRows: List<Row> get() = rows.inOrder(choosing.rows)

	/** How large the listing is drawn, in the layout it is drawn in. */
	val zoom: Zoom get() = zooms[layout]

	/**
	 * How many times its usual size the listing is drawn: the zoom chosen, but in the gallery never larger than a
	 * tile that fits, so that a window made smaller makes the tiles smaller with it.
	 */
	val scale: Float get() = scaleOf(zoom, whole)

	/** Every entry the folder holds, or every result, shown or not: the "of 750" of the status line. */
	val entryCount: Int
		get() =
			when (val listing = listing) {
				is Listing.Shown -> listing.entries.size
				is Listing.Found -> listing.matches.size
				// What a section shown as its heading alone holds is not listed, nor what is hidden from the page.
				is Listing.Recent -> listing.shown(arrangement).listed
				Listing.Reading, is Listing.Unreadable -> 0
			}

	/**
	 * The button that accepts, worked out from what has been chosen and typed, in the asking application's words
	 * where it gave any.
	 */
	val accept: Accept
		get() = ownAccept.let { own -> acceptLabel?.let { own.copy(label = it) } ?: own }

	private val ownAccept: Accept
		get() =
			when (choosing) {
				is Choosing.OneFile ->
					Accept(
						"Open",
						choosing.typed?.isNotBlank() ?: (choosing.chosen != null),
					)
				is Choosing.Files ->
					Accept(
						"Open",
						choosing.typed?.isNotBlank()
							?: chosenRows.let { chosen -> chosen.any { !it.isFolder } || chosen.size == 1 },
					)
				// The name alone says whether there is something to save under, whatever row is chosen.
				is Choosing.SaveAs -> Accept("Save", choosing.name.isNotBlank())
				// A folder picker always has a folder in a folder: the one shown, when none is chosen or typed.
				is Choosing.Folder ->
					Accept(
						"Select folder",
						folder != null || chosenRows.isNotEmpty() || !choosing.typed.isNullOrBlank(),
					)
				is Choosing.Exploring -> Accept("Open", chosenRows.isNotEmpty())
			}

	/**
	 * What the name field holds: what was typed, or the name of what has been chosen, each in quotes where there are
	 * several, as a desktop's own dialogs write them. A folder chosen in a file picker is not a name to open, so it
	 * is not written there.
	 */
	val nameField: String
		get() =
			when (choosing) {
				is Choosing.OneFile ->
					choosing.typed
						?: chosenRows.filterNot { it.isFolder }.namesWritten()
				is Choosing.Files ->
					choosing.typed
						?: chosenRows.filterNot { it.isFolder }.namesWritten()
				is Choosing.SaveAs -> choosing.name
				is Choosing.Folder -> choosing.typed ?: chosenRows.namesWritten()
				is Choosing.Exploring -> chosenRows.namesWritten()
			}

	/** What the name field says faintly when it is empty: in a folder picker, the folder that is chosen then. */
	val nameHint: String
		get() =
			when (choosing) {
				is Choosing.Folder -> crumbs.lastOrNull()?.label.orEmpty()
				else -> ""
			}

	companion object {
		/** Why a picker opened without an index has no search. */
		const val NO_INDEX = "This window was opened without a search index."
	}
}

/** How many times its usual size [zoom] draws, where a tile fills the listing at [whole] (as [BrowserState.scale]). */
fun scaleOf(zoom: Zoom, whole: Float): Float =
	when (zoom) {
		is Zoom.By -> minOf(zoom.factor, whole)
		Zoom.Whole -> whole.takeIf { it.isFinite() } ?: Zoom.SCALE.last()
	}

/**
 * What an arrangement puts away and leaves out [at] a place: on the Recents page, the sections the user has
 * [collapsed] and what they have [hidden] from it (shown all the same, where [showsHidden]); nothing anywhere else,
 * so that no other listing is arranged again for what does not arrange it.
 */
internal fun recentAt(
	at: Location,
	collapsed: Set<RecentSection>,
	hidden: Set<Path>,
	showsHidden: Boolean,
): RecentArranged =
	if (at == Location.Recents)
		RecentArranged(collapsed, hidden, showsHidden && hidden.isNotEmpty())
	else
		RecentArranged.NONE

/** The rows [listing] shows when arranged by [arrangement]. */
fun rowsOf(listing: Listing, arrangement: Arrangement): Rows =
	when (listing) {
		is Listing.Shown -> listing.rows(arrangement)
		is Listing.Found -> listing.rows(arrangement)
		is Listing.Recent -> listing.rows(arrangement)
		is Listing.Reading, is Listing.Unreadable -> Rows.NONE
	}

private fun List<Row>.namesWritten(): String =
	singleOrNull()?.name ?: joinToString(" ") { "\"${it.name}\"" }
