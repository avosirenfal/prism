package prism.preferences

import kotlinx.serialization.Serializable

/** Which column the listing is ordered by, and which way. Folders come first whatever it is. */
@Serializable
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
