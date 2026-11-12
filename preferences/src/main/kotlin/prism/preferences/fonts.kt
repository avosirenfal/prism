package prism.preferences

import kotlinx.serialization.Serializable

/**
 * The font of each part of a window, by its family's name as the system knows it (`Inter`, `Noto Sans`): [menus], the
 * context menus and the toolbar's; [listing], the rows of a folder; and [general], everything else. A part named none
 * of, or a family the system does not have, is drawn in the default.
 */
@Serializable
data class Fonts(
	val general: String? = null,
	val listing: String? = null,
	val menus: String? = null,
)
