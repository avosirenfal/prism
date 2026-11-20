package prism.preferences

import kotlin.math.roundToInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
		private const val QUARTERS = 4f
		private const val SLOWEST = 1
		private const val FASTEST = 12

		/** As far as the wheel scrolls where nothing is set: three rows a notch. */
		val USUAL = ScrollSpeed(QUARTERS.toInt())

		/** Every speed there is, slowest first. */
		val ALL: List<ScrollSpeed> = (SLOWEST..FASTEST).map(::ScrollSpeed)

		/** The speed nearest [times] as far as usual; the usual one, where [times] is no number. */
		fun nearest(times: Float): ScrollSpeed =
			if (times.isNaN())
				USUAL
			else
				ScrollSpeed((times * QUARTERS).coerceIn(SLOWEST.toFloat(), FASTEST.toFloat()).roundToInt())
	}
}

/** A speed as the file writes it: how many times as far as usual, `1.5`; read back as the nearest there is. */
object ScrollSpeedSerializer : KSerializer<ScrollSpeed> {
	override val descriptor = PrimitiveSerialDescriptor("prism.ScrollSpeed", PrimitiveKind.FLOAT)

	override fun serialize(encoder: Encoder, value: ScrollSpeed) = encoder.encodeFloat(value.times)

	override fun deserialize(decoder: Decoder): ScrollSpeed = ScrollSpeed.nearest(decoder.decodeFloat())
}
