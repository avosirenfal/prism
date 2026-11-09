package barion.picker

import androidx.compose.runtime.Immutable
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/*
 * What the picker shows, as immutable data. The view (view.kt) is a pure function of a [PickerState]: it draws what
 * the state says and reports what the user did as a [PickerEvent]. The next state is worked out from the last and
 * the event by one pure function (step.kt), which is also what asks for a folder to be read or a typed name to be
 * looked up, as an [Effect] for whoever runs the picker (session.kt) to carry out.
 *
 * What the picker is choosing decides what can be chosen, and that is in the types: a picker for one file holds at
 * most one, a picker that saves always has a name, and the button that accepts is worked out from those, never set
 * beside them. What the types cannot hold (that a chosen row is a row the listing shows) is checked when a state is
 * made, so no state the view is given can name a row it does not show.
 */

/** A row of the listing, by an identity that survives sorting and filtering: its name in its folder. */
@JvmInline
value class RowId(
	val name: String,
)

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

/** One entry of the folder shown, with everything the view shows of it already worked out. */
@Immutable
data class Row(
	val id: RowId,
	val name: String,
	val category: Category,
	/** What the type column says: "Folder", "PNG image", "Kotlin source". */
	val type: String,
	/** The size in bytes, said as the size column says it ("4.2 KB"); null for a folder. */
	val size: Shown<Long>?,
	/** When it was last modified, said as the date column says it; null where that could not be read. */
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
	/** Whether [row] is shown, as far as being hidden goes. */
	fun shows(row: Row): Boolean =
		when {
			!row.hidden -> true
			row.isFolder -> folders
			else -> files
		}

	/** Every hidden entry shown, or, where every one is already, none: what Ctrl+H does. */
	fun toggled(): Hidden =
		if (files && folders)
			Hidden(files = false, folders = false)
		else
			Hidden(files = true, folders = true)
}

/** How the listing arranges what a folder holds: its order, and which rows it leaves out. */
@Immutable
data class Arrangement(
	val sort: Sort = Sort.BY_NAME,
	val hidden: Hidden = Hidden(),
	/** The kinds shown: only these where any are chosen, every row where none is. */
	val kinds: Set<Kind> = emptySet(),
	/** The asking application's filter of files, by name. */
	val filter: TypeFilter = TypeFilter.ALL_FILES,
	/** Words a row's name must contain to be shown, without case; blank for every row. */
	val query: String = "",
) {
	fun admits(row: Row): Boolean =
		hidden.shows(row) &&
			(kinds.isEmpty() || Kind.of(row.category) in kinds) &&
			(row.isFolder || filter.admits(row.name)) &&
			(query.isBlank() || row.name.contains(query.trim(), ignoreCase = true))
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

/** The picker's own changes to the disk, each kept as the change that undoes it, the latest last, and those undone. */
@Immutable
data class Changes(
	val undo: List<Change> = emptyList(),
	val redo: List<Change> = emptyList(),
)

/**
 * The rows the listing shows, in order, with where each is: what the view draws and what the keyboard moves
 * through, so a row is found by its identity without searching for it.
 */
@Immutable
class Rows(
	val list: List<Row>,
) {
	private val positions: Map<RowId, Int> by lazy {
		list.indices.associateBy { list[it].id }
	}

	val size: Int get() = list.size

	fun isEmpty(): Boolean = list.isEmpty()

	operator fun contains(id: RowId): Boolean = id in positions

	operator fun get(id: RowId): Row? = positions[id]?.let(list::get)

	fun indexOf(id: RowId): Int? = positions[id]

	/** [ids] in the order they are shown. */
	fun inOrder(ids: Set<RowId>): List<Row> = list.filter { it.id in ids }

	/** The rows from [from] to [to], whichever way round, both included. */
	fun between(from: RowId, to: RowId): Set<RowId> {
		val ends = listOfNotNull(indexOf(from), indexOf(to)).sorted()

		return if (ends.size < 2)
			setOf(to)
		else
			list.subList(ends[0], ends[1] + 1).mapTo(LinkedHashSet()) { it.id }
	}

	override fun toString(): String = "Rows(${list.map { it.name }})"

	companion object {
		val NONE = Rows(emptyList())
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

	/** The rows this has chosen. */
	val rows: Set<RowId>
		get() =
			when (this) {
				is OneFile -> setOfNotNull(chosen)
				is Files -> chosen
				is SaveAs -> setOfNotNull(chosen)
				is Folder -> setOfNotNull(chosen)
			}

	/** Whether this can hold more than one row. */
	val many: Boolean get() = this is Files
}

/** Where the keyboard is in the listing: on [at], with a range chosen by Shift reaching from [anchor]. */
@Immutable
data class Cursor(
	val at: RowId,
	val anchor: RowId,
)

/** The folders behind and ahead of the one shown: [back]'s last is the one Back goes to, [forward]'s first Forward's. */
@Immutable
data class History(
	val back: List<Path> = emptyList(),
	val forward: List<Path> = emptyList(),
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

/** A place the sidebar offers. */
@Immutable
data class Place(
	val label: String,
	val kind: Kind,
	val path: Path,
) {
	enum class Kind {
		Home,
		Desktop,
		Documents,
		Downloads,
		Pictures,
		Music,
		Videos,
		Drive,
	}
}

/** The sidebar: places, under the heading each belongs to. */
@Immutable
data class Sidebar(
	val groups: List<Group>,
) {
	@Immutable
	data class Group(
		val heading: String,
		val places: List<Place>,
	)

	val places: List<Place> get() = groups.flatMap { it.places }
}

/** Everything the picker shows. */
@Immutable
data class PickerState(
	val title: String,
	val choosing: Choosing,
	/** The folder shown, absolute and normalized. */
	val folder: Path,
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
) {
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
	val crumbs: List<Crumb> by lazy { crumbsOf(folder, home) }

	/** The rows chosen, in the order they are shown. */
	val chosenRows: List<Row> get() = rows.inOrder(choosing.rows)

	/** How large the listing is drawn, in the layout it is drawn in. */
	val zoom: Zoom get() = zooms[layout]

	/**
	 * How many times its usual size the listing is drawn: the zoom chosen, but in the gallery never larger than a
	 * tile that fits, so that a window made smaller makes the tiles smaller with it.
	 */
	val scale: Float get() = scaleOf(zoom, whole)

	/** Every entry the folder holds, shown or not: the "of 750" of the status line. */
	val entryCount: Int get() = (listing as? Listing.Shown)?.entries?.size ?: 0

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
				// A folder picker always has a folder: the one shown, when none is chosen or typed.
				is Choosing.Folder -> Accept("Select folder", true)
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
			}

	/** What the name field says faintly when it is empty: in a folder picker, the folder that is chosen then. */
	val nameHint: String
		get() =
			when (choosing) {
				is Choosing.Folder -> crumbs.last().label
				else -> ""
			}
}

/** How many times its usual size [zoom] draws, where a tile fills the listing at [whole] (as [PickerState.scale]). */
fun scaleOf(zoom: Zoom, whole: Float): Float =
	when (zoom) {
		is Zoom.By -> minOf(zoom.factor, whole)
		Zoom.Whole -> whole.takeIf { it.isFinite() } ?: Zoom.SCALE.last()
	}

/** The rows [listing] shows when arranged by [arrangement]. */
fun rowsOf(listing: Listing, arrangement: Arrangement): Rows =
	when (listing) {
		is Listing.Shown -> listing.rows(arrangement)
		is Listing.Reading, is Listing.Unreadable -> Rows.NONE
	}

private fun List<Row>.namesWritten(): String =
	singleOrNull()?.name ?: joinToString(" ") { "\"${it.name}\"" }
