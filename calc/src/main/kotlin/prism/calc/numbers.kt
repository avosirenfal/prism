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

	/** The largest finite number of this type. */
	val max: BigDecimal
		get() =
			when (this) {
				Float ->
					kotlin.Float.MAX_VALUE
						.toString()
						.toBigDecimal()
				Double ->
					kotlin.Double.MAX_VALUE
						.toString()
						.toBigDecimal()
			}

	/**
	 * What is said of [what], which this type has no finite number for, as a fixed-width integer's is said
	 * ([Fixed.beyond]): `Overflow: 1E+400 > 1.7976931348623157E+308 (Double.MAX_VALUE)`. [negative] where it is below
	 * the most negative number, which is the largest negated (Kotlin's MIN_VALUE is the smallest above zero).
	 */
	fun beyond(what: String, negative: Boolean): String =
		if (negative)
			"Underflow: $what < -${said(DecimalValue(max))} (-$written.MAX_VALUE)"
		else
			"Overflow: $what > ${said(DecimalValue(max))} ($written.MAX_VALUE)"

	/** [exact], which this type has no finite number for, said as [beyond] says it. */
	fun beyond(exact: BigDecimal): String =
		beyond(
			said(DecimalValue(exact)),
			exact.signum() < 0,
		)
}

/**
 * [value] worked out as a number of [type] from what are all finite numbers, said as an overflow where it is not one:
 * [working] is what it is the result of (`a * b`, `exp(1000.0)`), or its exact value where that is known.
 */
fun floated(type: Floating, value: Double, working: () -> String): Computed.Done {
	val made = FloatValue.of(type, value)

	return Computed.Done(
		made,
		listOfNotNull(
			type.beyond(working(), made.value < 0).takeIf { made.value.isInfinite() },
		),
	)
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
				Converted.Refused(to.beyond(exactOf(value)!!))
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
				Converted.Refused(to.beyond(whole))
		}
		is Floating -> {
			val made = FloatValue.of(to, exact.toDouble())

			if (made.value.isInfinite())
				Converted.Refused(to.beyond(exact))
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
				to.beyond(exactOf(value) ?: BigDecimal.ZERO).takeIf {
					made.value.isInfinite() &&
						value.value.isFinite()
				},
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
					to.beyond(exact).takeIf { made.value.isInfinite() },
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
						to.beyond(whole),
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

/** How many digits a number has before its point, past which it is written with an exponent. */
private const val MOST_WHOLE_DIGITS = 30

/** How many zeros a number has after its point before its first digit, past which it is written with an exponent. */
private const val MOST_LEADING_ZEROS = 10

/** [exact] written out in full, every digit of it, without its trailing zeros: what is copied. */
private fun full(exact: BigDecimal): String =
	exact
		.stripTrailingZeros()
		.let {
			if (it.scale() <
				0
			) it.setScale(0) else it
		}.toPlainString()

/**
 * [exact] as it is read: in full, but where it is so large or so small that its zeros would be most of it (more than
 * [MOST_WHOLE_DIGITS] digits before its point, or [MOST_LEADING_ZEROS] zeros after it before the first digit), with an
 * exponent, as `1.2676506002282294E+30`.
 */
private fun readable(exact: BigDecimal): String {
	val stripped = exact.stripTrailingZeros()
	// Where its first digit is: 3 for 123.4, -2 for 0.00123.
	val magnitude = stripped.precision() - stripped.scale()

	return if (stripped.signum() != 0 &&
		(magnitude > MOST_WHOLE_DIGITS || magnitude < -MOST_LEADING_ZEROS)
	)
		scientific(stripped)
	else
		full(stripped)
}

/** [exact] as a number from 1 to 10 times a power of ten: `1.2676506002282294E+30`, `1.23E-12`, `0E+0`. */
fun scientific(exact: BigDecimal): String {
	val stripped = exact.stripTrailingZeros()

	if (stripped.signum() == 0)
		return "0E+0"

	val power = stripped.precision() - stripped.scale() - 1

	return "${stripped.movePointLeft(
		power,
	).toPlainString()}E${if (power >= 0) "+" else ""}$power"
}

/** A floating-point number's value as it is written, exactly: what its shortest repetition says (0.1, not 0.1000…). */
private fun exactOfFloat(value: FloatValue): BigDecimal? = exactOf(value)

/**
 * [value] as it is said in what is said of it (an overflow, what is wrong): in full, but for a number so large or so
 * small that it is written with an exponent ([readable]). A Double or a Float keeps a point, as Kotlin writes one
 * (`5.0`), so that it is told from a whole number; and is Kotlin's own where it is no number (NaN, Infinity).
 */
fun said(value: Value): String =
	when (value) {
		is Formatted -> value.text
		is DecimalValue -> readable(value.value)
		is BigIntegerValue -> readable(value.value.toBigDecimal())
		is FixedValue -> value.value.toString()
		is FloatValue ->
			exactOfFloat(value)?.let { exact ->
				readable(exact).let {
					if ('.' in it || 'E' in it)
						it
					else
						"$it.0"
				}
			} ?: value.value.toString()
	}

/**
 * [value] as an answer is shown: as [said] says it, with its thousands apart before its point (`1,234,567.89`), where
 * it is written in full.
 */
fun shown(value: Value): String {
	val text = said(value)

	if (value is Formatted || 'E' in text || text.none(Char::isDigit) ||
		text.any { it.isLetter() }
	)
		return text

	val negative = text.startsWith('-')
	val digits = text.removePrefix("-")
	val whole = digits.substringBefore('.')
	val fraction = digits.substring(whole.length)

	return (if (negative) "-" else "") +
		whole
			.reversed()
			.chunked(3)
			.joinToString(",")
			.reversed() +
		fraction
}

/**
 * [value] as it is copied: every digit of it, with no exponent and nothing between its thousands, as any program
 * reads a number; a form ([Formatted]) as it is shown; and a floating-point number that is no number as Kotlin writes it.
 */
fun copied(value: Value): String =
	when (value) {
		is Formatted -> value.text
		is FixedValue -> value.value.toString()
		is FloatValue -> exactOfFloat(value)?.let(::full) ?: value.value.toString()
		is DecimalValue -> full(value.value)
		is BigIntegerValue -> value.value.toString()
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
