package prism.browser

import androidx.compose.runtime.Immutable
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import prism.desktop.Place

/*
 * What the picker shows, as immutable data. The view (view.kt) is a pure function of a [BrowserState]: it draws what
 * the state says and reports what the user did as a [BrowserEvent]. The next state is worked out from the last and
 * the event by one pure function (step.kt), which is also what asks for a folder to be read or a typed name to be
 * looked up, as an [Effect] for whoever runs the picker (session.kt) to carry out.
 *
 * What the picker is choosing decides what can be chosen, and that is in the types: a picker for one file holds at
 * most one, a picker that saves always has a name, and the button that accepts is worked out from those, never set
 * beside them. What the types cannot hold (that a chosen row is a row the listing shows) is checked when a state is
 * made, so no state the view is given can name a row it does not show.
 */

/** A row of the listing, by an identity that survives sorting and filtering. */
@Immutable
sealed interface RowId {
	/** An entry of the folder shown, by its name in it. */
	data class Named(
		val name: String,
	) : RowId

	/** A result of the search shown, by its place among the results, best first from 0. */
	data class Found(
		val at: Int,
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

/** One entry of the folder shown, or one result of a search, with everything the view shows of it worked out. */
@Immutable
data class Row(
	val id: RowId,
	/** Where it is: absolute, its last name [name]. */
	val path: Path,
	val name: String,
	val category: Category,
	/** What the type column says: "Folder", "PNG image", "Kotlin source". */
	val type: String,
	/** The size in bytes, said as the size column says it ("4.2 KB"); null for a folder, or a result not read yet. */
	val size: Shown<Long>?,
	/** When it was last modified, said as the date column says it; null where that is not read, or could not be. */
	val modified: Shown<Instant>?,
	val hidden: Boolean,
) {
	val isFolder: Boolean get() = category == Category.Folder
}

/** Which column the listing is ordered by, and which way. Folders come first whatever it is. */
@Immutable
data class Sort(
	val by: Column,
	val ascending: Boolean,
) {
	enum class Column {
		Name,
		Modified,
		Type,
		Size,
	}

	/**
	 * The order after [column]'s header is clicked: the other way round if it is already the order; otherwise by that
	 * column, the newest and the largest first, and names and types from A.
	 */
	fun toggled(column: Column): Sort =
		if (column == by)
			copy(ascending = !ascending)
		else
			Sort(column, ascending = column == Column.Name || column == Column.Type)

	companion object {
		val BY_NAME = Sort(Column.Name, ascending = true)
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
private fun globOf(pattern: String): Regex {
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

/**
 * What the filters above the listing choose among: kinds of entry, each a few [Category]s together. A row whose
 * category is of no kind (a link to nothing, a file of no known type) is shown only while no kind is chosen.
 */
enum class Kind(
	val label: String,
) {
	Folders("Folders"),
	Images("Images"),
	Videos("Videos"),
	Audio("Audio"),
	Documents("Documents"),
	Archives("Archives"),
	Code("Code"),
	;

	companion object {
		/** The kind [category] is of, if any. */
		fun of(category: Category): Kind? =
			when (category) {
				Category.Folder -> Folders
				Category.Image -> Images
				Category.Video -> Videos
				Category.Audio -> Audio
				Category.Document, Category.Pdf, Category.Text -> Documents
				Category.Archive -> Archives
				Category.Code -> Code
				Category.Link, Category.Other -> null
			}
	}
}

/**
 * Which hidden entries (a name starting with a dot) are shown: hidden files are, and hidden folders are not, until the
 * user says otherwise. The folders are kept back by default because they are where programs keep their own things
 * (`.config`, `.cache`, `.local`), which are rarely what a person picks from; a hidden file is more often a person's.
 */
@Immutable
data class Hidden(
	val files: Boolean = true,
	val folders: Boolean = false,
) {
	/** Whether an entry is shown, as far as being hidden goes. */
	fun shows(hidden: Boolean, folder: Boolean): Boolean =
		when {
			!hidden -> true
			folder -> folders
			else -> files
		}

	/** Every hidden entry shown, or, where every one is already, none: what Ctrl+H does. */
	fun toggled(): Hidden =
		if (files && folders)
			Hidden(files = false, folders = false)
		else
			Hidden(files = true, folders = true)
}

/**
 * How the listing arranges what a folder holds, or what a search found: its order, and which rows it leaves out. A
 * folder's order and a search's are kept apart, since a search's is best first until the user says otherwise; what
 * leaves rows out is the same for both, so the results of a search are filtered as a folder holding them would be.
 */
@Immutable
data class Arrangement(
	val sort: Sort = Sort.BY_NAME,
	val results: Order = Order.Relevance,
	val hidden: Hidden = Hidden(),
	/** The kinds shown: only these where any are chosen, every row where none is. */
	val kinds: Set<Kind> = emptySet(),
	/** The asking application's filter of files, by name. */
	val filter: TypeFilter = TypeFilter.ALL_FILES,
	/** Words a row's name must contain to be shown, without case; blank for every row. */
	val query: String = "",
) {
	fun admits(row: Row): Boolean = admits(row.category, row.hidden) { row.name }

	/**
	 * Whether an entry of [category], hidden or not, is shown: its [name] asked for only where a filter of names needs
	 * it, since for a search's results it is made from the index's bytes for the asking.
	 */
	fun admits(category: Category, hidden: Boolean, name: () -> String): Boolean {
		val folder = category == Category.Folder

		if (!this.hidden.shows(hidden, folder) ||
			(kinds.isNotEmpty() && Kind.of(category) !in kinds)
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

/** How the listing is drawn: rows in columns, or tiles with a picture of each. */
enum class Layout {
	List,
	Gallery,
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

		override fun toString(): String = "Rows(${rows.map { it.name }})"
	}

	/** A search's rows: the results at [order]'s places, each made from [matches] and [stats] as it is asked for. */
	class Found(
		private val matches: Matches,
		private val stats: Stats,
		private val order: IntArray,
	) : Rows() {
		/** Where each result is shown, by its place among the results; -1 for one not shown. */
		private val shownAt: IntArray by lazy {
			IntArray(matches.size) { -1 }.also { at ->
				order.forEachIndexed { index, place ->
					at[place] =
						index
				}
			}
		}

		override val size: Int get() = order.size

		override fun get(index: Int): Row = matches.rowOf(order[index], stats[order[index]])

		override fun idAt(index: Int): RowId = RowId.Found(order[index])

		override fun nameAt(index: Int): String = matches.nameOf(order[index])

		override fun indexOf(id: RowId): Int? =
			(id as? RowId.Found)
				?.at
				?.takeIf {
					it in 0 until matches.size
				}?.let { shownAt[it] }
				?.takeIf {
					it >=
						0
				}

		/** The places among the results of the rows from [from] up to [to]. */
		fun placesBetween(from: Int, to: Int): List<Int> =
			(from.coerceAtLeast(0) until to.coerceAtMost(size)).map { order[it] }

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
	private val compute: (K) -> V,
) {
	private val last = AtomicReference<Pair<K, V>?>(null)

	operator fun invoke(key: K): V =
		last.get()?.takeIf { it.first == key }?.second
			?: compute(key).also { last.set(key to it) }
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
	 * A listing is equal only to itself: two reads of a folder are two listings, however alike.
	 */
	class Shown(
		val entries: List<Row>,
	) : Read {
		private val sortedBy = Memo<Sort, List<Row>> { sorted(entries, it) }
		private val arranged =
			Memo<Arrangement, Rows> { Rows(sortedBy(it.sort).filter(it::admits)) }

		fun rows(arrangement: Arrangement): Rows = arranged(arrangement)

		override fun toString(): String = "Shown(${entries.size} entries)"
	}

	/** The folder could not be read: what the system said. */
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
	class Found(
		val finding: Finding.Matched,
		val stats: Stats,
		val orders: Map<Sort.Column, IntArray> = emptyMap(),
	) : Listing {
		val matches: Matches get() = finding.matches

		/** Whether they are few enough to be read every one, and ordered by any column at once ([FEW_RESULTS]). */
		val few: Boolean get() = matches.size <= FEW_RESULTS

		private val sortedBy = Memo<Sort.Column, IntArray> { orderOf(matches, stats, it) }
		private val arranged = Memo<Arrangement, Rows> { arrange(it) }

		fun rows(arrangement: Arrangement): Rows = arranged(arrangement)

		/**
		 * Whether the results can be ordered by [column]: by any, where they are few (all read at once, and ordered as
		 * they are read); by those the index knows (name and type) where they are not; never by what only the disk
		 * knows of too many to read.
		 */
		fun orders(column: Sort.Column): Boolean =
			when (column) {
				Sort.Column.Name, Sort.Column.Type -> true
				Sort.Column.Modified, Sort.Column.Size -> few
			}

		/** The order [order] asks for, where it is worked out; for too many results, once it is worked out behind. */
		fun placesBy(order: Order): IntArray? =
			when (order) {
				Order.Relevance -> null
				is Order.By ->
					when {
						!orders(order.sort.by) -> null
						few -> sortedBy(order.sort.by)
						else -> orders[order.sort.by]
					}
			}

		/** The column [order] asks for that is still being worked out behind, if it is. */
		fun pending(order: Order): Sort.Column? =
			(order as? Order.By)
				?.sort
				?.by
				?.takeIf { orders(it) && !few && it !in orders }

		/** The rows shown: filtered as a folder's are, then in [Arrangement.results]' order, folders first. */
		private fun arrange(arrangement: Arrangement): Rows {
			val order = arrangement.results
			val sorted = placesBy(order)
			val places =
				when {
					sorted == null -> IntArray(matches.size) { it }
					(order as Order.By).sort.ascending -> sorted
					else -> sorted.reversedArray()
				}

			// There can be millions, arranged again as each batch of them is read: so into arrays, nothing boxed.
			val folders = IntArray(places.size)
			val files = IntArray(places.size)
			var folderCount = 0
			var fileCount = 0

			for (at in places) {
				val kept =
					stats[at] != Stat.Gone &&
						arrangement.admits(matches.categoryOf(at), matches.isHidden(at)) {
							matches.nameOf(at)
						}

				when {
					!kept -> Unit
					matches.isFolder(at) -> folders[folderCount++] = at
					else -> files[fileCount++] = at
				}
			}

			return Rows.Found(
				matches,
				stats,
				folders.copyOf(folderCount) + files.copyOf(fileCount),
			)
		}

		override fun toString(): String = "Found(${matches.size} results, $stats)"
	}
}

/** Where the picker is: in a folder, or among a search's results, which it shows as if a folder held them all. */
@Immutable
sealed interface Location {
	data class Folder(
		val path: Path,
	) : Location

	data class Results(
		val finding: Finding.Matched,
	) : Location
}

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
 * The name field shows what has been chosen until something is typed into it; from then on, what was typed is what
 * the picker accepts ([typed]), until a row is chosen again.
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

	/** A name to save under, in the folder shown. Choosing a file gives its name; choosing a folder, a way into it. */
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

	/** Entries to move to the trash, if the user says so: asked when the Delete key alone asks for it. */
	data class ConfirmTrash(
		val paths: List<Path>,
	) : Notice

	/** Something the user asked for could not be done: what ("Couldn't rename “a.txt”"), and why. */
	data class Failed(
		val doing: String,
		val why: String,
	) : Notice

	/** A name was typed among a search's results, which are in no one folder, with no result chosen to go beside. */
	data class NoFolder(
		val text: String,
	) : Notice
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

/**
 * The sidebar: the places pinned to it (its quick access, which the user pins to and unpins from), then the drives,
 * each under its heading.
 */
@Immutable
data class Sidebar(
	val pinned: List<Place>,
	val drives: List<Place>,
) {
	@Immutable
	data class Group(
		val heading: String,
		val places: List<Place>,
	)

	val groups: List<Group> get() =
		listOf(
			Group("Quick access", pinned),
			Group("This computer", drives),
		)

	val places: List<Place> get() = pinned + drives

	/** Whether [path] is pinned. */
	fun pins(path: Path): Boolean = pinned.any { it.path == path }
}

/** Everything the picker shows. */
@Immutable
data class BrowserState(
	val title: String,
	val choosing: Choosing,
	/** Where the picker is: a folder (absolute and normalized), or a search's results. */
	val at: Location,
	/** The user's home, which the path across the top starts from where it can, and `~` names. */
	val home: Path,
	val history: History,
	val sidebar: Sidebar,
	val listing: Listing,
	val arrangement: Arrangement,
	/** The type filters the asking application offers; [Arrangement.filter] is one of them. */
	val filters: List<TypeFilter>,
	val cursor: Cursor?,
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
	val renaming: Renaming? = null,
	/** The search of the index, as Ctrl+Shift+F opens it; unavailable where there is no index. */
	val search: Search = Search.Unavailable(NO_INDEX),
) {
	/** The folder shown; none among a search's results. */
	val folder: Path? get() = (at as? Location.Folder)?.path

	/** The search whose results are shown, where they are. */
	val results: Finding.Matched? get() = (at as? Location.Results)?.finding

	/**
	 * Where a name typed is looked for: in the folder shown, or, among a search's results, beside the one chosen;
	 * nowhere where none is.
	 */
	val base: Path? get() = folder ?: chosenRows.singleOrNull()?.path?.parent

	/** The rows the listing shows, its arrangement applied; none while it is read, or when it could not be. */
	val rows: Rows get() = rowsOf(listing, arrangement)

	init {
		val rows = rows

		require(arrangement.filter in filters) {
			"the filter shown is not one of the filters offered"
		}

		val strays = choosing.rows.filterNot { it in rows }

		require(strays.isEmpty()) { "chosen, but not a row the listing shows: $strays" }
		require(cursor == null || (cursor.at in rows && cursor.anchor in rows)) {
			"the cursor is on a row the listing does not show: $cursor"
		}
		require(renaming == null || renaming.row in rows) {
			"the row being renamed is not a row the listing shows: $renaming"
		}
	}

	/** The path to [folder], as steps from the home folder where it is under it and from the root otherwise. */
	val crumbs: List<Crumb> by lazy { folder?.let { crumbsOf(it, home) }.orEmpty() }

	/** What the place shown is called: the folder's name, or what the search was for. */
	val placeName: String
		get() =
			when (val at = at) {
				is Location.Folder -> crumbs.last().label
				is Location.Results -> "results for “${at.finding.text}”"
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
				Listing.Reading, is Listing.Unreadable -> 0
			}

	/** The button that accepts, worked out from what has been chosen and typed. */
	val accept: Accept
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
				is Choosing.SaveAs ->
					if (chosenRows.singleOrNull()?.isFolder == true)
						Accept("Open", true)
					else
						Accept("Save", choosing.name.isNotBlank())
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
		const val NO_INDEX = "This window was opened without an index to search."
	}
}

/** How many times its usual size [zoom] draws, where a tile fills the listing at [whole] (as [BrowserState.scale]). */
fun scaleOf(zoom: Zoom, whole: Float): Float =
	when (zoom) {
		is Zoom.By -> minOf(zoom.factor, whole)
		Zoom.Whole -> whole.takeIf { it.isFinite() } ?: Zoom.SCALE.last()
	}

/** The rows [listing] shows when arranged by [arrangement]. */
fun rowsOf(listing: Listing, arrangement: Arrangement): Rows =
	when (listing) {
		is Listing.Shown -> listing.rows(arrangement)
		is Listing.Found -> listing.rows(arrangement)
		is Listing.Reading, is Listing.Unreadable -> Rows.NONE
	}

private fun List<Row>.namesWritten(): String =
	singleOrNull()?.name ?: joinToString(" ") { "\"${it.name}\"" }
