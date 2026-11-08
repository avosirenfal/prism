package barion.picker

import androidx.compose.runtime.Immutable
import java.nio.file.Path

/*
 * What the picker shows, as immutable data. The view (view.kt) is a pure function of a [PickerState]: it draws what
 * the state says and reports what the user did as a [PickerEvent], and holds nothing of its own. Everything a row
 * shows is worked out when the state is made (picker.source), so drawing a row is reading strings.
 *
 * What the picker is choosing decides what can be chosen, and that is in the types: a picker for one file holds at
 * most one, a picker that saves always has a name, and the button that accepts is worked out from those, never set
 * beside them. What the types cannot hold (that a chosen row is a row of the listing) is checked once, when a state is
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

/** One entry of the folder shown, with everything the view shows of it already worked out. */
@Immutable
data class Row(
	val id: RowId,
	val name: String,
	val category: Category,
	/** What the type column says: "Folder", "PNG image", "Kotlin source". */
	val type: String,
	/** The size as the size column says it ("4.2 KB"), or null for a folder. */
	val size: String?,
	/** When it was last modified, as the date column says it, or null where that could not be read. */
	val modified: String?,
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

	companion object {
		val BY_NAME = Sort(Column.Name, ascending = true)
	}
}

/** What the folder shown holds, or why it shows nothing. */
@Immutable
sealed interface Listing {
	/** Its rows, in [sort]'s order. */
	data class Shown(
		val rows: List<Row>,
		val sort: Sort,
	) : Listing

	/** The folder could not be read: what the system said. */
	data class Unreadable(
		val why: String,
	) : Listing
}

/**
 * What the picker is asked to choose, and what has been chosen so far. Each kind holds exactly what it can: a
 * picker for one file cannot hold two, and one that saves always has a name to save under.
 */
@Immutable
sealed interface Choosing {
	/** One file to open. */
	data class OneFile(
		val chosen: RowId?,
	) : Choosing

	/** Files to open, as many as are chosen. */
	data class Files(
		val chosen: Set<RowId>,
	) : Choosing

	/** A name to save under, in the folder shown. Choosing a file of the listing gives its name. */
	data class SaveAs(
		val name: String,
	) : Choosing

	/** A folder: the one shown, or one chosen among its rows. */
	data class Folder(
		val chosen: RowId?,
	) : Choosing

	/** The rows this has chosen. */
	val rows: Set<RowId>
		get() =
			when (this) {
				is OneFile -> setOfNotNull(chosen)
				is Files -> chosen
				is SaveAs -> emptySet()
				is Folder -> setOfNotNull(chosen)
			}
}

/** The button that accepts: what it says, and whether what has been chosen is enough to press it. */
@Immutable
data class Accept(
	val label: String,
	val enabled: Boolean,
)

/** The button [Choosing] gives: worked out from what has been chosen, so it cannot disagree with it. */
val Choosing.accept: Accept
	get() =
		when (this) {
			is Choosing.OneFile -> Accept("Open", chosen != null)
			is Choosing.Files -> Accept("Open", chosen.isNotEmpty())
			is Choosing.SaveAs -> Accept("Save", name.isNotBlank())
			// A folder picker always has a folder: the one shown, when none of its rows is chosen.
			is Choosing.Folder -> Accept("Select folder", true)
		}

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
}

/** A choice of which files the listing shows, as the asking application names them. */
@Immutable
data class TypeFilter(
	val label: String,
	/** Name patterns (`*.png`); empty for every file. */
	val patterns: List<String>,
) {
	companion object {
		val ALL_FILES = TypeFilter("All files", emptyList())
	}
}

/** Everything the picker shows. */
@Immutable
data class PickerState(
	val title: String,
	val choosing: Choosing,
	val folder: Path,
	val crumbs: List<Crumb>,
	val sidebar: Sidebar,
	val listing: Listing,
	val filters: List<TypeFilter>,
	val filter: TypeFilter,
	val query: String,
	val canGoBack: Boolean,
	val canGoForward: Boolean,
) {
	init {
		val shown =
			(listing as? Listing.Shown)
				?.rows
				?.map { it.id }
				?.toSet()
				.orEmpty()

		require(
			choosing.rows.all {
				it in shown
			},
		) { "a chosen row is not a row of the listing: ${choosing.rows - shown}" }
		require(filter in filters) { "the filter shown is not one of the filters offered" }
		require(crumbs.isNotEmpty()) { "the folder shown has a path" }
	}

	val accept: Accept get() = choosing.accept

	/** The rows the listing shows, the query applied; none for a folder that could not be read. */
	val visibleRows: List<Row>
		get() =
			when (listing) {
				is Listing.Unreadable -> emptyList()
				is Listing.Shown ->
					if (query.isBlank())
						listing.rows
					else
						listing.rows.filter { it.name.contains(query, ignoreCase = true) }
			}

	/**
	 * What the name field holds: a saved file's name, or the name of what has been chosen, each in quotes where there
	 * are several, as a desktop's own dialogs write them.
	 */
	val nameField: String
		get() =
			when (choosing) {
				is Choosing.SaveAs -> choosing.name
				else -> {
					val names = choosing.rows.map { it.name }

					if (names.size == 1)
						names.single()
					else
						names.joinToString(" ") { "\"$it\"" }
				}
			}
}

/** What the user did, as the view reports it. The view changes nothing itself: whoever holds the state does. */
sealed interface PickerEvent {
	data class Opened(
		val path: Path,
	) : PickerEvent

	data class Chose(
		val row: RowId,
		val adding: Boolean,
	) : PickerEvent

	data class Activated(
		val row: RowId,
	) : PickerEvent

	data class Sorted(
		val by: Sort.Column,
	) : PickerEvent

	data class Searched(
		val query: String,
	) : PickerEvent

	data class Named(
		val name: String,
	) : PickerEvent

	data class Filtered(
		val filter: TypeFilter,
	) : PickerEvent

	data object Back : PickerEvent

	data object Forward : PickerEvent

	data object Up : PickerEvent

	data object Accepted : PickerEvent

	data object Cancelled : PickerEvent
}
