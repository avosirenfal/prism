package prism.preferences

import kotlinx.serialization.Serializable

/**
 * A part of a window whose text has its own font and size: the context menus and the window's menu; the listing's rows;
 * and everything else. Each part's text is [points] large unless the user says otherwise. The default is a desktop's
 * usual body text (GNOME's and KDE's are 10 to 11 points), with menus a little larger, for being easier to read and to
 * hit.
 */
@Serializable
enum class TextPart(
	val label: String,
	val points: Float,
) {
	General("Everything else", 11f),
	Listing("The listing", 11f),
	Menus("Menus", 12f),
}

/**
 * The font chosen for a part of a window: a [family] by its name as the system knows it (`Inter`, `Noto Sans`), and a
 * size in [points]. Either left null is the default's, so that a later default reaches whoever never chose one.
 */
@Serializable
data class FontChoice(
	val family: String? = null,
	val points: Float? = null,
)

/**
 * The font of each part of a window ([TextPart]): what the user chose for it, and its default where they chose
 * nothing. A family the system does not have is drawn in the default font.
 */
@Serializable
data class Typography(
	val general: FontChoice = FontChoice(),
	val listing: FontChoice = FontChoice(),
	val menus: FontChoice = FontChoice(),
) {
	operator fun get(part: TextPart): FontChoice =
		when (part) {
			TextPart.General -> general
			TextPart.Listing -> listing
			TextPart.Menus -> menus
		}

	/** These, with [part]'s font chosen as [choice]. */
	fun with(part: TextPart, choice: FontChoice): Typography =
		when (part) {
			TextPart.General -> copy(general = choice)
			TextPart.Listing -> copy(listing = choice)
			TextPart.Menus -> copy(menus = choice)
		}

	/** How large [part]'s text is, in points: as chosen, within [SMALLEST] to [LARGEST], or its default. */
	fun pointsOf(part: TextPart): Float =
		this[part].points?.coerceIn(SMALLEST, LARGEST) ?: part.points

	/** The family [part]'s text is drawn in: as chosen, or the default's, where null. */
	fun familyOf(part: TextPart): String? = this[part].family?.takeIf { it.isNotBlank() }

	/** These, [part]'s text [by] steps larger (or smaller, for a negative [by]), within what can be read. */
	fun stepped(part: TextPart, by: Int): Typography =
		with(
			part,
			this[part].copy(points = (pointsOf(part) + by * STEP).coerceIn(SMALLEST, LARGEST)),
		)

	companion object {
		const val SMALLEST = 6f
		const val LARGEST = 24f

		/** How much a size changes by, a step at a time. */
		const val STEP = 0.5f
	}
}
