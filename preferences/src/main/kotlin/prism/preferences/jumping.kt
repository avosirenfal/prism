package prism.preferences

import kotlinx.serialization.Serializable

/**
 * How long a pause in typing ends the letters typed into a listing to go to a row by its name, after which what is
 * typed names a row anew: at [USUAL], a second and a half. It is always one of [ALL], the quarter seconds from half a
 * second to four, so a file that says anything else is read as the nearest of them.
 */
@Serializable(with = JumpPauseSerializer::class)
@JvmInline
value class JumpPause private constructor(
	private val quarters: Int,
) {
	/** How long, in seconds. */
	val seconds: Float get() = quarters / QUARTERS

	/** How long, in milliseconds. */
	val millis: Long get() = quarters * MILLIS_A_QUARTER

	companion object {
		private const val MILLIS_A_QUARTER = 250L
		private val RANGE = 2..16

		/** How long the pause is where nothing is set: a second and a half. */
		val USUAL = JumpPause(6)

		/** Every pause there is, shortest first. */
		val ALL: List<JumpPause> = RANGE.map(::JumpPause)

		/** The pause nearest [seconds] long; the usual one, where [seconds] is no number. */
		fun nearest(seconds: Float): JumpPause =
			nearestQuarter(seconds, RANGE)?.let(::JumpPause) ?: USUAL
	}
}

/** A pause as the file writes it: how many seconds, `1.5`; read back as the nearest there is. */
object JumpPauseSerializer :
	QuartersSerializer<JumpPause>("prism.JumpPause", JumpPause::seconds, JumpPause::nearest)
