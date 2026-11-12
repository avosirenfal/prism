package prism.preferences

import kotlinx.serialization.Serializable

/** Which column the listing is ordered [by], and which way. Folders come first whatever it is. */
@Serializable
data class Sort(
	val by: ColumnId,
	val ascending: Boolean,
) {
	/** The order after [column]'s header is clicked: the other way round if it is already the order, otherwise by it. */
	fun toggled(column: ColumnId): Sort =
		if (column == by)
			copy(ascending = !ascending)
		else
			of(column)

	companion object {
		val BY_NAME = Sort(ColumnId.Name, ascending = true)

		/**
		 * By [column], the way it is first wanted: words from A (names, places, types, extensions), and the newest and
		 * the largest first.
		 */
		fun of(column: ColumnId): Sort =
			Sort(
				column,
				ascending =
					when (column) {
						ColumnId.Name, ColumnId.Location, ColumnId.Type, ColumnId.Extension -> true
						ColumnId.Modified, ColumnId.Created, ColumnId.Deleted, ColumnId.Size -> false
					},
			)
	}
}
