package prism.preferences

import kotlinx.serialization.Serializable

/**
 * How far the wheel and a touchpad scroll a list, as so many times what they usually do: at [USUAL], a notch of the
 * wheel moves a list three of its rows; at twice that, six; at half, one and a half. It is always one of [ALL], the
 * quarters from a quarter to three times, so a file that says anything else is read as the nearest of them.
 */
@Serializable(with = ScrollSpeedSerializer::class)
@JvmInline
value class ScrollSpeed private constructor(
	private val quarters: Int,
) {
	/** How many times as far as usual. */
	val times: Float get() = quarters / QUARTERS

	companion object {
		private val RANGE = 1..12

		/** As far as the wheel scrolls where nothing is set: three rows a notch. */
		val USUAL = ScrollSpeed(QUARTERS.toInt())

		/** Every speed there is, slowest first. */
		val ALL: List<ScrollSpeed> = RANGE.map(::ScrollSpeed)

		/** The speed nearest [times] as far as usual; the usual one, where [times] is no number. */
		fun nearest(times: Float): ScrollSpeed =
			nearestQuarter(times, RANGE)?.let(::ScrollSpeed) ?: USUAL
	}
}

/** A speed as the file writes it: how many times as far as usual, `1.5`; read back as the nearest there is. */
object ScrollSpeedSerializer :
	QuartersSerializer<ScrollSpeed>(
		"prism.ScrollSpeed",
		ScrollSpeed::times,
		ScrollSpeed::nearest,
	)
