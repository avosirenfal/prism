package prism.preferences

import kotlinx.serialization.Serializable

/**
 * How the launcher looks and what it finds. Its keys are among the rest ([Preferences.bindings]), each named
 * `launcher.` and its action.
 */
@Serializable
data class LauncherPreferences(
	/** How wide its window is, in the units text is sized in. */
	val width: Int = 760,
	/** How many results are in sight at once; the rest are scrolled to. */
	val rows: Int = 9,
	/** How large it is drawn: 1 is the size every window starts at. */
	val scale: Float = 1f,
	/** Whether files and folders are found too, after the applications, by a search of the index. */
	val files: Boolean = true,
	/** The most files a search lists. */
	val fileResults: Int = 30,
	/** Whether the other things an application can be started to do (a new private window) are found. */
	val actions: Boolean = true,
	/** Whether it closes when another window is given the keyboard. */
	val closeUnfocused: Boolean = true,
	/** The applications left out of it, by the desktop's names for them. */
	val hidden: Set<String> = emptySet(),
) {
	/** These, each number within what the launcher can be drawn with, whatever a file written by hand says. */
	val bounded: LauncherPreferences
		get() =
			copy(
				width = width.coerceIn(WIDTHS),
				rows = rows.coerceIn(ROWS),
				scale = scale.coerceIn(Scales.SMALLEST, Scales.LARGEST),
				fileResults = fileResults.coerceIn(FILE_RESULTS),
			)

	companion object {
		val WIDTHS = 480..1400
		val ROWS = 4..20
		val FILE_RESULTS = 1..200

		/** What an action of the launcher's is named among the bindings. */
		fun bindingOf(action: String): String = "launcher.$action"
	}
}
