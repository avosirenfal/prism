package prism.preferences

import kotlin.math.roundToInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * A setting that is one of a few quarters in a row (a quarter to three times as fast; half a second to four), slid
 * along by the preferences' slider. The file writes it as the number it stands for, `1.5`, and reads whatever number
 * it says as the nearest quarter there is, so that a value written by hand can never be one the slider has no place
 * for.
 */

/** How many quarters are a whole one. */
internal const val QUARTERS = 4f

/** The quarter of [quarters] nearest [value] wholes; none where [value] is no number. */
internal fun nearestQuarter(value: Float, quarters: IntRange): Int? =
	if (value.isNaN())
		null
	else
		(value * QUARTERS)
			.coerceIn(
				quarters.first.toFloat(),
				quarters.last.toFloat(),
			).roundToInt()

/** A setting of quarters as the file writes it: [written] as the number it stands for, and [read] back from one. */
abstract class QuartersSerializer<T>(
	name: String,
	private val written: (T) -> Float,
	private val read: (Float) -> T,
) : KSerializer<T> {
	override val descriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.FLOAT)

	override fun serialize(encoder: Encoder, value: T) = encoder.encodeFloat(written(value))

	override fun deserialize(decoder: Decoder): T = read(decoder.decodeFloat())
}
