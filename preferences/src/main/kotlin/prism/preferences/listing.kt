package prism.preferences

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * How a listing looks: its order, which time its date column shows, and which of its columns it shows. A folder's
 * listing and a search's results each have their own, since a search's results are best first until the user says
 * otherwise, and have a column a folder's listing has no need of (the folder each result is in). The preferences say
 * how each looks as a window opens; a window keeps the look the user gives it as its own from then on.
 */

/** A column a listing can show or leave out. The name's is always shown. */
@Serializable
enum class Column {
	/** The folder a search's result is in: a search's results alone have it. */
	Folder,
	Date,
	Type,
	Size,
	;

	companion object {
		/** The columns a folder's listing has. */
		val OF_FOLDERS: List<Column> = listOf(Date, Type, Size)

		/** The columns a search's results have. */
		val OF_RESULTS: List<Column> = entries
	}
}

/** Which time the date column shows: when a file was last modified, or when it was made. */
@Serializable
enum class DateShown {
	Modified,
	Created,
	;

	/** The column a listing showing this is ordered by, by its date. */
	val column: Sort.Column
		get() =
			when (this) {
				Modified -> Sort.Column.Modified
				Created -> Sort.Column.Created
			}

	companion object {
		/** The time [sort] orders by, where it orders by one. */
		fun of(sort: Sort): DateShown? = entries.firstOrNull { it.column == sort.by }
	}
}

/** How a search's results are ordered. Folders come first whatever it is. */
@Serializable
sealed interface Order {
	/** Best first, as the search ranked them. */
	@Serializable
	@SerialName("relevance")
	data object Relevance : Order

	/** By a column, as a folder is. */
	@Serializable
	@SerialName("by")
	data class By(
		override val sort: Sort,
	) : Order

	/** The order after [column]'s header is clicked: the other way round if it is already the order, otherwise by it. */
	fun toggled(column: Sort.Column): Order =
		when (this) {
			Relevance -> By(Sort.BY_NAME.toggled(column))
			is By -> By(sort.toggled(column))
		}

	/** The sort this is, where it is by a column. */
	val sort: Sort? get() = null
}

/**
 * How a folder's listing looks: [sort], the time its date column shows, and the columns [hidden]. A listing ordered by
 * a time shows that time: [date] is the time shown otherwise.
 */
@Serializable
data class FolderView(
	val sort: Sort = Sort.BY_NAME,
	private val date: DateShown = DateShown.Modified,
	val hidden: Set<Column> = emptySet(),
) {
	/** The time the date column shows. */
	val dateShown: DateShown get() = DateShown.of(sort) ?: date

	/** These, ordered by [sort]. */
	fun sorted(sort: Sort): FolderView = copy(sort = sort, date = dateShown)

	/** These, showing [time]; a listing ordered by the other time ordered by this one, the same way round. */
	fun showing(time: DateShown): FolderView =
		copy(
			date = time,
			sort =
				if (DateShown.of(sort) != null)
					sort.copy(by = time.column)
				else
					sort,
		)

	/** These, with [column] shown or not. */
	fun showingColumn(column: Column, shown: Boolean): FolderView =
		copy(hidden = hiddenWith(hidden, column, shown))
}

/** How a search's results look: [order], the time their date column shows, and the columns [hidden]. */
@Serializable
data class ResultsView(
	val order: Order = Order.Relevance,
	private val date: DateShown = DateShown.Modified,
	val hidden: Set<Column> = emptySet(),
) {
	/** The time the date column shows: the time they are ordered by, where they are ordered by one. */
	val dateShown: DateShown get() = order.sort?.let(DateShown::of) ?: date

	/** These, in [order]. */
	fun ordered(order: Order): ResultsView = copy(order = order, date = dateShown)

	/** These, showing [time]; results ordered by the other time ordered by this one, the same way round. */
	fun showing(time: DateShown): ResultsView =
		copy(
			date = time,
			order =
				order.sort
					?.takeIf { DateShown.of(it) != null }
					?.let { Order.By(it.copy(by = time.column)) }
					?: order,
		)

	/** These, with [column] shown or not. */
	fun showingColumn(column: Column, shown: Boolean): ResultsView =
		copy(hidden = hiddenWith(hidden, column, shown))
}

private fun hiddenWith(hidden: Set<Column>, column: Column, shown: Boolean): Set<Column> =
	if (shown)
		hidden - column
	else
		hidden + column

/**
 * How large things are drawn, as many times their usual size: the listing of an explorer's window and of a picker's as
 * each opens ([explorer], [picker]; Ctrl and the wheel zoom a window's own from there), the sidebar's quick access and
 * the window that arranges it ([sidebar]), and each of the other windows, as Ctrl and the wheel last left it
 * ([windows]).
 */
@Serializable
data class Scales(
	val explorer: Float = 1f,
	val picker: Float = 1f,
	val sidebar: Float = 1f,
	val windows: Map<WindowKind, Float> = emptyMap(),
) {
	/** How large [kind] is drawn. */
	fun of(kind: WindowKind): Float = (windows[kind] ?: 1f).coerceIn(SMALLEST, LARGEST)

	/** These, with [kind] drawn [scale] times its usual size, within what can be read and laid out. */
	fun with(kind: WindowKind, scale: Float): Scales =
		copy(
			windows =
				windows + (kind to scale.coerceIn(SMALLEST, LARGEST)),
		)

	companion object {
		const val SMALLEST = 0.5f
		const val LARGEST = 3f
	}
}

/** A window that is not a browser's, each drawn as large as the user last zoomed one of its kind. */
@Serializable
enum class WindowKind {
	Preferences,
	Properties,
	OpenWith,
}
