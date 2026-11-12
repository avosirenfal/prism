package prism.preferences

import kotlinx.serialization.Serializable

/**
 * How large the text of each part of a window is, in points (sp): [menus], the context menus and the toolbar's;
 * [listing], the rows of a folder (which Ctrl and the wheel zoom from); and [general], everything else. The parts grow
 * with their text: a menu's entries are taller where its text is larger, and so are the listing's rows.
 */
@Serializable
data class TextSizes(
	val general: Float = DEFAULT,
	val listing: Float = DEFAULT,
	val menus: Float = MENUS,
) {
	/** These, each within what can be read and laid out: [SMALLEST] to [LARGEST]. */
	fun bounded(): TextSizes =
		TextSizes(
			general.coerceIn(SMALLEST, LARGEST),
			listing.coerceIn(SMALLEST, LARGEST),
			menus.coerceIn(SMALLEST, LARGEST),
		)

	companion object {
		const val DEFAULT = 13f

		/** A context menu's text, a little larger than the rest by default: easier to read, and to hit. */
		const val MENUS = 15f
		const val SMALLEST = 8f
		const val LARGEST = 32f
	}
}
