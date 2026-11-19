package prism.calc

import java.math.BigInteger

/*
 * What the operators do. Two numbers are first made one type ([common]): a Decimal becomes the type of what it is with
 * (it is what was typed, and takes the type it is put to work in, as a literal does in Kotlin); a floating-point number
 * wins over a whole one, and a Double over a Float; two fixed-width integers become the wider of them, and at least an
 * Int (as Kotlin's Byte + Byte is an Int); and a signed one with an unsigned one is refused, as Kotlin refuses it,
 * since neither holds all of the other. Then the operator does what that type's does in Kotlin.
 */

/** What working something out came to: a value, and what is to be said of how it came about; or why there is none. */
sealed interface Computed {
	data class Done(
		val value: Value,
		val notes: List<String> = emptyList(),
	) : Computed

	data class Failed(
		val why: String,
	) : Computed
}

/** An operator written between two operands. */
enum class Operator(
	val written: String,
) {
	Plus("+"),
	Minus("-"),
	Times("*"),
	Divide("/"),
	Remainder("%"),
	Power("**"),
	Shl("shl"),
	Shr("shr"),
	Ushr("ushr"),
	And("and"),
	Or("or"),
	Xor("xor"),
}

/** An operator written before its operand. */
enum class Prefix(
	val written: String,
) {
	Negate("-"),
	Same("+"),

	/** Every bit turned over: C's `~`, Kotlin's inv(). */
	Invert("~"),
}

/** The type [a] and [b] are worked out in, or why there is none. */
fun common(a: NumberType, b: NumberType): Either<String, NumberType> =
	when {
		a == b -> Either.Right(a)
		a == NumberType.Decimal -> Either.Right(b)
		b == NumberType.Decimal -> Either.Right(a)
		a == Floating.Double || b == Floating.Double -> Either.Right(Floating.Double)
		a == Floating.Float || b == Floating.Float -> Either.Right(Floating.Float)
		a == NumberType.BigInteger || b == NumberType.BigInteger ->
			Either.Right(
				NumberType.BigInteger,
			)
		a is Fixed && b is Fixed ->
			if (a.signed == b.signed)
				Either.Right(if (a.bits >= b.bits) a else b)
			else
				Either.Left(
					"a${an(
						a,
					)} ${a.written} and a${an(
						b,
					)} ${b.written} can't be mixed, as in Kotlin: convert one " +
						"of them (to${a.written}(), to${b.written}())",
				)
		else -> Either.Left("${a.written} and ${b.written} can't be worked out together")
	}

/** One of two things: what went wrong ([Left]), or what came of it ([Right]). */
sealed interface Either<out L, out R> {
	data class Left<out L>(
		val value: L,
	) : Either<L, Nothing>

	data class Right<out R>(
		val value: R,
	) : Either<Nothing, R>
}

/** [a] and [b] made one type, as [common] says: each converted to it, or why they could not be. */
private fun alike(
	a: NumberValue,
	b: NumberValue,
): Either<String, Pair<NumberValue, NumberValue>> =
	when (val type = common(a.type, b.type)) {
		is Either.Left -> type
		is Either.Right ->
			when (val left = implicitly(a, type.value)) {
				is Converted.Refused -> Either.Left(left.why)
				is Converted.Done ->
					when (val right = implicitly(b, type.value)) {
						is Converted.Refused -> Either.Left(right.why)
						is Converted.Done -> Either.Right(left.value to right.value)
					}
			}
	}

/** [whole] as a value of [type], wrapped round where it does not fit, and said so. */
internal fun fixed(type: Fixed, whole: BigInteger): Computed.Done {
	val (made, wrapped) = type.wrapped(whole)

	return Computed.Done(
		made,
		listOfNotNull(
			type.beyond(whole).takeIf { wrapped },
		),
	)
}

/** [a] [operator] [b], for the operators whose operands are one type: arithmetic, and the bits of whole numbers. */
fun binary(operator: Operator, a: NumberValue, b: NumberValue): Computed {
	if (operator == Operator.Shl || operator == Operator.Shr || operator == Operator.Ushr)
		return shifted(operator, a, b)

	val (left, right) =
		when (val made = alike(a, b)) {
			is Either.Left -> return Computed.Failed(made.value)
			is Either.Right -> made.value
		}

	return when (operator) {
		Operator.And, Operator.Or, Operator.Xor -> bitwise(operator, left, right)
		Operator.Power -> Computed.Failed("** is worked out by pow")
		else -> arithmetic(operator, left, right)
	}
}

private fun arithmetic(
	operator: Operator,
	left: NumberValue,
	right: NumberValue,
): Computed =
	when (left) {
		is DecimalValue -> {
			val a = left.value
			val b = (right as DecimalValue).value

			when (operator) {
				Operator.Plus -> Computed.Done(DecimalValue(a + b))
				Operator.Minus -> Computed.Done(DecimalValue(a - b))
				Operator.Times -> Computed.Done(DecimalValue(a * b))
				Operator.Divide ->
					if (b.signum() == 0)
						Computed.Failed("division by zero")
					else
						Computed.Done(DecimalValue(a.divide(b, PRECISION)))
				Operator.Remainder ->
					if (b.signum() == 0)
						Computed.Failed("division by zero")
					else
						Computed.Done(DecimalValue(a.remainder(b)))
				else -> Computed.Failed("$operator is not arithmetic")
			}
		}
		is BigIntegerValue ->
			wholes(operator, left.value, (right as BigIntegerValue).value) {
				Computed.Done(BigIntegerValue(it))
			}
		is FixedValue ->
			wholes(operator, left.value, (right as FixedValue).value) {
				fixed(left.type, it)
			}
		is FloatValue -> {
			val a = left.value
			val b = (right as FloatValue).value

			Computed.Done(
				FloatValue.of(
					left.type,
					when (operator) {
						Operator.Plus -> a + b
						Operator.Minus -> a - b
						Operator.Times -> a * b
						Operator.Divide -> a / b
						else -> a % b
					},
				),
			)
		}
	}

/** [a] [operator] [b] for whole numbers, as Kotlin's integers have it: division toward zero, and a remainder of the sign of [a]. */
private fun wholes(
	operator: Operator,
	a: BigInteger,
	b: BigInteger,
	made: (BigInteger) -> Computed,
): Computed =
	when (operator) {
		Operator.Plus -> made(a + b)
		Operator.Minus -> made(a - b)
		Operator.Times -> made(a * b)
		Operator.Divide ->
			if (b.signum() ==
				0
			)
				Computed.Failed("division by zero")
			else
				made(a.divide(b))
		Operator.Remainder ->
			if (b.signum() ==
				0
			)
				Computed.Failed("division by zero")
			else
				made(a.rem(b))
		else -> Computed.Failed("$operator is not arithmetic")
	}

/** A Decimal's whole number, where it is one: what the bits of a Decimal are worked out on. */
private fun wholeOrWhy(value: DecimalValue): Either<String, BigInteger> =
	when (val whole = implicitly(value, NumberType.BigInteger)) {
		is Converted.Done -> Either.Right((whole.value as BigIntegerValue).value)
		is Converted.Refused ->
			Either.Left(
				"bits are only a whole number's: ${said(value)} is not whole",
			)
	}

private fun bitwise(
	operator: Operator,
	left: NumberValue,
	right: NumberValue,
): Computed {
	fun of(a: BigInteger, b: BigInteger): BigInteger =
		when (operator) {
			Operator.And -> a.and(b)
			Operator.Or -> a.or(b)
			else -> a.xor(b)
		}

	return when (left) {
		is FloatValue ->
			Computed.Failed(
				"bits are only a whole number's: ${described(left)} is not",
			)
		is DecimalValue ->
			when (val a = wholeOrWhy(left)) {
				is Either.Left -> Computed.Failed(a.value)
				is Either.Right ->
					when (val b = wholeOrWhy(right as DecimalValue)) {
						is Either.Left -> Computed.Failed(b.value)
						is Either.Right -> Computed.Done(DecimalValue(of(a.value, b.value).toBigDecimal()))
					}
			}
		is BigIntegerValue ->
			Computed.Done(
				BigIntegerValue(of(left.value, (right as BigIntegerValue).value)),
			)
		// Two's complement, as a fixed-width integer's bits are: BigInteger's own and, or and xor are of the same.
		is FixedValue ->
			fixed(
				left.type,
				of(left.value, (right as FixedValue).value),
			)
	}
}

/**
 * [a] shifted by [b] bits: [a] keeps its type (an Int shifted is an Int), and [b] is an Int's count. A fixed-width
 * integer shifts by the low bits of its count alone (5 of them for an Int, 6 for a Long), as Kotlin's does.
 */
private fun shifted(operator: Operator, a: NumberValue, b: NumberValue): Computed {
	val count =
		when (val made = implicitly(b.number, Fixed.Int)) {
			is Converted.Refused -> return Computed.Failed(
				"a shift is by an Int's count of bits: ${made.why}",
			)
			is Converted.Done -> (made.value as FixedValue).value.toInt()
		}

	return when (a) {
		is FloatValue ->
			Computed.Failed(
				"bits are only a whole number's: ${described(a)} is not",
			)
		is DecimalValue, is BigIntegerValue -> {
			val whole =
				when (a) {
					is BigIntegerValue -> a.value
					else ->
						when (val made = wholeOrWhy(a as DecimalValue)) {
							is Either.Left -> return Computed.Failed(made.value)
							is Either.Right -> made.value
						}
				}

			val moved =
				when (operator) {
					Operator.Shl -> whole.shiftLeft(count)
					Operator.Shr -> whole.shiftRight(count)
					else -> return Computed.Failed(
						"ushr is a fixed-width integer's: a ${a.type.written} has no width to shift into",
					)
				}

			Computed.Done(
				if (a is BigIntegerValue)
					BigIntegerValue(moved)
				else
					DecimalValue(moved.toBigDecimal()),
			)
		}
		is FixedValue -> {
			val type = a.type
			val by = count and (type.bits - 1)

			val masked =
				listOfNotNull(
					"${operator.written} by $count is ${operator.written} by $by: a${an(
						type,
					)} ${type.written} " +
						"shifts by the low ${Integer.numberOfTrailingZeros(
							type.bits,
						)} bits of its count, as Kotlin's does"
							.takeIf { by != count },
				)

			val bits = a.value.mod(BigInteger.ONE.shiftLeft(type.bits))

			val moved =
				when (operator) {
					Operator.Shl -> a.value.shiftLeft(by)
					// An unsigned number's shr is its ushr: it has no sign to carry.
					Operator.Shr ->
						if (type.signed)
							a.value.shiftRight(by)
						else
							bits.shiftRight(by)
					else -> bits.shiftRight(by)
				}

			val (made, _) = type.wrapped(moved)

			Computed.Done(made, masked)
		}
	}
}

/** [operator] before [value]. */
fun prefixed(operator: Prefix, value: NumberValue): Computed =
	when (operator) {
		Prefix.Same -> Computed.Done(value)
		Prefix.Invert ->
			when (value) {
				is FixedValue -> fixed(value.type, value.value.not())
				is BigIntegerValue -> Computed.Done(BigIntegerValue(value.value.not()))
				is FloatValue ->
					Computed.Failed(
						"bits are only a whole number's: ${described(value)} is not",
					)
				is DecimalValue ->
					when (val whole = implicitly(value, NumberType.BigInteger)) {
						is Converted.Refused ->
							Computed.Failed(
								"bits are only a whole number's: ${said(value)} is not whole",
							)
						is Converted.Done ->
							Computed.Done(
								DecimalValue((whole.value as BigIntegerValue).value.not().toBigDecimal()),
							)
					}
			}
		Prefix.Negate ->
			when (value) {
				is DecimalValue -> Computed.Done(DecimalValue(value.value.negate()))
				is BigIntegerValue -> Computed.Done(BigIntegerValue(value.value.negate()))
				is FloatValue -> Computed.Done(FloatValue.of(value.type, -value.value))
				is FixedValue ->
					if (value.type.signed)
						fixed(value.type, value.value.negate())
					else
						Computed.Failed(
							"a${an(
								value.type,
							)} ${value.type.written} can't be negated, as in Kotlin: convert it first " +
								"(${said(value)}.toLong())",
						)
			}
	}
