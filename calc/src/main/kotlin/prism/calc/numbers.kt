package prism.calc

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/*
 * The calculator's numbers. What is typed is a Decimal: exact, of any size, as a person counts (0.1 + 0.2 is 0.3). A
 * programmer asks for one of Kotlin's own types by converting to it (`5.toUInt()`, `0.1.toDouble()`), or by a literal's
 * suffix (`5u`, `5L`, `1.5f`), and from then on the arithmetic is that type's, as Kotlin does it: an Int that goes past
 * its largest wraps round, and a Double's 0.1 is not quite a tenth. What is hidden by Kotlin is said here: a value that
 * wrapped is shown wrapped, with a note that it did.
 *
 * Every value is valid for its type by construction. A fixed-width integer is made only through [Fixed.wrapped], which
 * brings any whole number into its range, and a Float only through [FloatValue.of], which rounds it to a Float's
 * precision; so no Int holds what no Int can, and no Float holds a Double's digits.
 */

/** A type of number the calculator works in. */
sealed interface NumberType {
	/** As it is written in a conversion and said beside a result: "UInt". */
	val written: String

	/** Exact, and of any size: what is typed. */
	data object Decimal : NumberType {
		override val written: String get() = "Decimal"
	}

	/** Whole, and of any size: Kotlin's (Java's) BigInteger. */
	data object BigInteger : NumberType {
		override val written: String get() = "BigInteger"
	}

	companion object {
		/** Every type there is, in the order they are listed. */
		val ALL: List<NumberType> =
			listOf(Decimal, BigInteger) + Fixed.entries + Floating.entries
	}
}

/** One of Kotlin's whole numbers of a fixed width: [bits] wide, and [signed] or not. */
enum class Fixed(
	val bits: Int,
	val signed: Boolean,
) : NumberType {
	Byte(8, true),
	Short(16, true),
	Int(32, true),
	Long(64, true),
	UByte(8, false),
	UShort(16, false),
	UInt(32, false),
	ULong(64, false),
	;

	override val written: String get() = name

	private val span: BigInteger get() = BigInteger.ONE.shiftLeft(bits)

	val min: BigInteger get() =
		if (signed)
			BigInteger.ONE.shiftLeft(bits - 1).negate()
		else
			BigInteger.ZERO

	val max: BigInteger get() =
		(
			if (signed)
				BigInteger.ONE.shiftLeft(bits - 1)
			else
				span
		) -
			BigInteger.ONE

	/**
	 * What is said of [whole], which this type does not hold: past its largest, `Overflow: 2147483648 > 2147483647
	 * (Int.MAX_VALUE)`; below its smallest, `Underflow: …`.
	 */
	fun beyond(whole: BigInteger): String =
		if (whole > max)
			"Overflow: $whole > $max ($written.MAX_VALUE)"
		else
			"Underflow: $whole < $min ($written.MIN_VALUE)"

	/** Whether [whole] is one of this type's values as it is. */
	fun holds(whole: BigInteger): Boolean = whole in min..max

	/** [whole] as this type keeps it: brought into its range as its bits wrap round, and whether that changed it. */
	fun wrapped(whole: BigInteger): Wrapped {
		val within =
			whole.mod(span).let {
				if (signed && it > max)
					it - span
				else
					it
			}

		return Wrapped(FixedValue(this, within), within != whole)
	}

	/** A value of this type, and whether making it wrapped what it was made of. */
	data class Wrapped(
		val value: FixedValue,
		val wrapped: Boolean,
	)
}

/** One of Kotlin's floating-point numbers. */
enum class Floating : NumberType {
	Float,
	Double,
	;

	override val written: String get() = name
}

/** What the calculator works with: a number, or a number shown in a form of its own. */
sealed interface Value {
	/** The number this is, or is a form of. */
	val number: NumberValue
}

/** A number, of its [type]. */
sealed interface NumberValue : Value {
	val type: NumberType
	override val number: NumberValue get() = this
}

data class DecimalValue(
	val value: BigDecimal,
) : NumberValue {
	override val type: NumberType get() = NumberType.Decimal
}

data class BigIntegerValue(
	val value: BigInteger,
) : NumberValue {
	override val type: NumberType get() = NumberType.BigInteger
}

/** A whole number of a fixed width, always within its type's range ([Fixed.wrapped] is the only way to make one). */
@ConsistentCopyVisibility
data class FixedValue internal constructor(
	override val type: Fixed,
	val value: BigInteger,
) : NumberValue

/** A floating-point number, always of its type's precision ([of] is the only way to make one). */
@ConsistentCopyVisibility
data class FloatValue private constructor(
	override val type: Floating,
	val value: Double,
) : NumberValue {
	companion object {
		/** [value] as a number of [type]: a Float's, rounded to a Float's precision. */
		fun of(type: Floating, value: Double): FloatValue =
			FloatValue(
				type,
				when (type) {
					Floating.Float -> value.toFloat().toDouble()
					Floating.Double -> value
				},
			)
	}
}

/** A [number] shown in a form of its own ([format]: "hex"), as [text]: what a formatting function makes. */
data class Formatted(
	override val number: NumberValue,
	val format: String,
	val text: String,
) : Value

/** The precision a Decimal's division and roots are worked to: 34 digits, as IEEE's decimal128. */
val PRECISION: MathContext = MathContext.DECIMAL128

/** [value] exactly, as a Decimal; none for what is no number (NaN) or no finite one. */
fun exactOf(value: NumberValue): BigDecimal? =
	when (value) {
		is DecimalValue -> value.value
		is BigIntegerValue -> value.value.toBigDecimal()
		is FixedValue -> value.value.toBigDecimal()
		is FloatValue ->
			value.value.takeIf { it.isFinite() }?.let {
				// As the number is written (0.1, not 0.1000000000000000055…): what Kotlin's toBigDecimal makes.
				when (value.type) {
					Floating.Float -> it.toFloat().toString().toBigDecimal()
					Floating.Double -> it.toString().toBigDecimal()
				}
			}
	}

/** [exact] as a whole number, where it is one. */
private fun wholeOf(exact: BigDecimal): BigInteger? =
	exact
		.takeIf {
			it.signum() == 0 || it.stripTrailingZeros().scale() <= 0
		}?.toBigIntegerExact()

/** What converting a value came to. */
sealed interface Converted {
	data class Done(
		val value: NumberValue,
		val notes: List<String> = emptyList(),
	) : Converted

	/** It could not be, and [why]. */
	data class Refused(
		val why: String,
	) : Converted
}

/** How [value] is said in an explanation: "1.5", "300 (an Int)". */
internal fun described(value: NumberValue): String =
	if (value.type == NumberType.Decimal)
		said(value)
	else
		"${said(value)} (${value.type.written})"

/**
 * [value] as a number of [to], where it can be without being changed: what is done to an operand or an argument to
 * make it the type it is wanted as. A whole number becomes any type that holds it; a fraction, a Decimal or a
 * floating-point number; and anything becomes a floating-point number, rounded to its precision, unless it is too
 * large for it. What would have to be changed is refused, with why.
 */
fun implicitly(value: NumberValue, to: NumberType): Converted {
	if (value.type == to)
		return Converted.Done(value)

	if (value is FloatValue && to is Floating)
		return FloatValue.of(to, value.value).let { made ->
			if (made.value.isInfinite() && value.value.isFinite())
				Converted.Refused("${described(value)} is too large for a ${to.written}")
			else
				Converted.Done(made)
		}

	val exact =
		exactOf(value)
			?: return Converted.Refused("${said(value)} is not a number a ${to.written} can be")

	return when (to) {
		NumberType.Decimal -> Converted.Done(DecimalValue(exact))
		NumberType.BigInteger ->
			wholeOf(exact)?.let { Converted.Done(BigIntegerValue(it)) }
				?: Converted.Refused(
					"${described(value)} is not a whole number, so it can't be a BigInteger",
				)
		is Fixed -> {
			val whole =
				wholeOf(exact)
					?: return Converted.Refused(
						"${described(
							value,
						)} is not a whole number, so it can't be a${an(to)} ${to.written}",
					)

			if (to.holds(whole))
				Converted.Done(to.wrapped(whole).value)
			else
				Converted.Refused(
					"${described(value)} doesn't fit in a${an(to)} ${to.written} (${to.min}..${to.max})",
				)
		}
		is Floating -> {
			val made = FloatValue.of(to, exact.toDouble())

			if (made.value.isInfinite())
				Converted.Refused("${described(value)} is too large for a ${to.written}")
			else
				Converted.Done(made)
		}
	}
}

/** "n" before a type's name that begins with a vowel's sound: "an Int", "a UInt". */
internal fun an(type: NumberType): String =
	if (type.written.first() in
		"AEIO"
	) "n" else ""

/**
 * [value] as a number of [to], as Kotlin's own conversion makes it (`toInt()`, `toUByte()`), what it changes said:
 * a fraction rounded toward zero; a floating-point number too large for a whole type held at that type's largest (as
 * Kotlin's `Double.toInt()` does); any other whole number too large wrapped round (as `Long.toInt()` does).
 */
fun explicitly(value: NumberValue, to: NumberType): Converted {
	if (value.type == to)
		return Converted.Done(value)

	if (value is FloatValue && to is Floating) {
		val made = FloatValue.of(to, value.value)

		return Converted.Done(
			made,
			listOfNotNull(
				"${described(value)} is too large for a ${to.written}: it became ${said(made)}"
					.takeIf { made.value.isInfinite() && value.value.isFinite() },
			),
		)
	}

	if (value is FloatValue && !value.value.isFinite())
		return when (to) {
			is Fixed ->
				// As Kotlin has it: NaN is 0, and either infinity the type's own furthest value.
				Converted.Done(
					to
						.wrapped(
							when {
								value.value.isNaN() -> BigInteger.ZERO
								value.value > 0 -> to.max
								else -> to.min
							},
						).value,
					listOf("${said(value)} has no ${to.written}: it became ${clampedSaid(value, to)}"),
				)
			else -> Converted.Refused("${said(value)} is not a number a ${to.written} can be")
		}

	val exact = exactOf(value) ?: return Converted.Refused("${said(value)} is not a number")

	return when (to) {
		NumberType.Decimal -> Converted.Done(DecimalValue(exact))
		is Floating -> {
			val made = FloatValue.of(to, exact.toDouble())

			Converted.Done(
				made,
				listOfNotNull(
					"${described(value)} is too large for a ${to.written}: it became ${said(made)}"
						.takeIf { made.value.isInfinite() },
				),
			)
		}
		NumberType.BigInteger -> {
			val whole = exact.setScale(0, RoundingMode.DOWN).toBigIntegerExact()

			Converted.Done(BigIntegerValue(whole), roundedNote(value, exact, whole))
		}
		is Fixed -> {
			val whole = exact.setScale(0, RoundingMode.DOWN).toBigIntegerExact()

			if (value is FloatValue && !to.holds(whole)) {
				val held = if (whole > to.max) to.max else to.min

				Converted.Done(
					to.wrapped(held).value,
					listOf(
						"${to.beyond(whole)}: held at $held, as Kotlin's to${to.written}() does",
					),
				)
			} else {
				val (made, wrapped) = to.wrapped(whole)

				Converted.Done(
					made,
					roundedNote(value, exact, whole) +
						listOfNotNull(
							to
								.beyond(whole)
								.takeIf { wrapped },
						),
				)
			}
		}
	}
}

private fun clampedSaid(value: FloatValue, to: Fixed): String =
	when {
		value.value.isNaN() -> "0"
		value.value > 0 -> to.max.toString()
		else -> to.min.toString()
	}

private fun roundedNote(
	value: NumberValue,
	exact: BigDecimal,
	whole: BigInteger,
): List<String> =
	listOfNotNull(
		"${said(value)} was rounded toward zero, to $whole".takeIf {
			exact.compareTo(whole.toBigDecimal()) !=
				0
		},
	)

/** How long a Decimal is written out in full before it is written with an exponent. */
private const val PLAIN_DIGITS = 40

/** [value] as it is shown: as Kotlin writes its own types, and a Decimal in full where that is not too long. */
fun said(value: Value): String =
	when (value) {
		is Formatted -> value.text
		is DecimalValue ->
			value.value.stripTrailingZeros().let { stripped ->
				val plain = stripped.toPlainString()

				if (plain.length <= PLAIN_DIGITS)
					plain
				else
					stripped.toString()
			}
		is BigIntegerValue -> value.value.toString()
		is FixedValue -> value.value.toString()
		is FloatValue ->
			when (value.type) {
				Floating.Float -> value.value.toFloat().toString()
				Floating.Double -> value.value.toString()
			}
	}

/** What is said beside a result of [value]'s: its type, and the form it is shown in; nothing for a plain Decimal. */
fun typeSaid(value: Value): String? =
	when (value) {
		is Formatted ->
			if (value.number.type == NumberType.Decimal)
				value.format
			else
				"${value.number.type.written} · ${value.format}"
		is NumberValue -> value.type.written.takeUnless { value.type == NumberType.Decimal }
	}
