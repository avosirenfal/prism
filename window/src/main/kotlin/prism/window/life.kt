package prism.window

/**
 * Where a window is in its life: one of these. It is made not on the screen, or on it with nothing in it yet (a
 * Wayland window, which takes a frame only once it is shown); a frame put in it, it is on the screen with that; and
 * from wherever it is it can fail, and be closed, in either order. What cannot be said is what never happens: a
 * window closed and then shown, or one that failed still counted as drawing.
 */
internal sealed interface Life {
	/** Open, with nothing gone wrong in it: frames are drawn in it and its events taken. */
	sealed interface Sound : Life {
		/** Whether a frame has ever been put in it: a window none can be put in is said to be so, not left blank. */
		val drawn: Boolean
	}

	/** Not on the screen yet, and nothing drawn: shown once there is something in it, rather than empty first. */
	data object Hidden : Sound {
		override val drawn get() = false
	}

	/** On the screen. */
	data class Shown(
		override val drawn: Boolean,
	) : Sound

	/** Something went wrong in it: its scene is not to be trusted with a frame or an event again. Open still. */
	data object Failed : Life

	/** Closed; whether something had gone wrong in it, or did as it closed, which is told only once ([failed]). */
	data class Closed(
		override val failed: Boolean,
	) : Life

	/** Whether something went wrong in it, open or closed. */
	val failed: Boolean
		get() = this == Failed

	/** The window as something goes wrong in it; null where something already had, which was told then. */
	fun failing(): Life? =
		when (this) {
			is Sound -> Failed
			Failed -> null
			is Closed -> copy(failed = true).takeIf { !failed }
		}

	/** The window as it is closed; null where it already is. */
	fun closing(): Closed? =
		when (this) {
			is Sound -> Closed(failed = false)
			Failed -> Closed(failed = true)
			is Closed -> null
		}

	/** The window with a frame put in it, where it is one frames are put in: on the screen, whatever it was. */
	fun drawnIn(): Life =
		when (this) {
			is Sound -> Shown(drawn = true)
			Failed, is Closed -> this
		}
}
