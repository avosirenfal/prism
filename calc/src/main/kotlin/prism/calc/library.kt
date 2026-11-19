package prism.calc

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/*
 * The functions the calculator knows, each declared once: its name, what it is for, and its overloads, each with the
 * types of what it takes and gives. A call picks the overload that asks least change of its arguments ([cost]), and
 * converts them to it as an operand is converted ([implicitly]): sin(1) takes the Decimal 1 as the Double sin is of,
 * and sin(1e400) is refused, a Double holding no such number. Called after a dot (`x.f(a)`), a function is called
 * on what is before it, as f(x, a): every function is a method too.
 *
 * To add one, add it to [Library.FUNCTIONS], by one of the helpers below or with overloads written out.
 */

/** What a parameter takes. */
sealed interface Takes {
	val written: String

	/** One type. */
	data class One(
		val type: NumberType,
	) : Takes {
		override val written: String get() = type.written
	}

	/** Any type [kind] allows, the same for every parameter of the overload that takes it: what is written T. */
	data class Same(
		val kind: Kind,
	) : Takes {
		override val written: String get() = "T"
	}
}

/** The types a [Takes.Same] parameter may be. */
enum class Kind(
	val written: String,
	val allows: (NumberType) -> Boolean,
) {
	Number("a number", { true }),
	Whole("a whole number", { it == NumberType.BigInteger || it is Fixed }),
	FixedWidth("a fixed-width integer", { it is Fixed }),
}

/** What an overload gives. */
sealed interface Gives {
	val written: String

	data class One(
		val type: NumberType,
	) : Gives {
		override val written: String get() = type.written
	}

	/** The type its [Takes.Same] parameters were. */
	data object Same : Gives {
		override val written: String get() = "T"
	}

	/** Text: a number in a form of its own ([Formatted]). */
	data object Text : Gives {
		override val written: String get() = "String"
	}
}

data class Parameter(
	val name: String,
	val takes: Takes,
)

/** One way of calling a function: what it takes, what it gives, and how it works it out of its arguments' values. */
class Overload(
	val parameters: List<Parameter>,
	val gives: Gives,
	val compute: (List<NumberValue>) -> Computed,
) {
	/** The kind of its T, where it has one. */
	val kind: Kind? get() =
		parameters.firstNotNullOfOrNull {
			(it.takes as? Takes.Same)?.kind
		}

	/** As it is shown: `(y: Double, x: Double): Double`, with what T may be after it. */
	val written: String
		get() =
			"(${parameters.joinToString(
				", ",
			) { "${it.name}: ${it.takes.written}" }}): ${gives.written}" +
				(kind?.let { "  (T is ${it.written})" } ?: "")
}

/** A function: its [name], what it is for ([does]), and the ways it can be called. */
class Function(
	val name: String,
	val does: String,
	val overloads: List<Overload>,
) {
	init {
		require(overloads.isNotEmpty()) { "$name has a way to be called" }
	}

	/** Each overload as it is shown: `atan2(y: Double, x: Double): Double`. */
	val signatures: List<String> get() = overloads.map { name + it.written }
}

/** A constant: its value, and what it is. */
data class Constant(
	val name: String,
	val value: DecimalValue,
	val does: String,
)

/**
 * A property that makes what it is of a number of [type], as a literal of the type is made (`5.uint` is `5u`): only
 * where it is that number still, so that `300.byte` is refused, where `300.toByte()` wraps round.
 */
data class TypedLiteral(
	val name: String,
	val type: NumberType,
) {
	val does: String get() = "this number as a${an(
		type,
	)} ${type.written}, as a literal of it: refused where it would change"
}

/** What calling a function on some values came to. */
sealed interface Called {
	data class Done(
		val computed: Computed,
	) : Called

	/** No overload takes them, and [why]. */
	data class Refused(
		val why: String,
	) : Called
}

/**
 * How much converting [from] to [to] changes what is worked in: nothing for the same type; least for a Decimal (what
 * was typed, which takes whatever type it is put to work in); more for a whole number to a wider one or a Float to a
 * Double; more again for one kind of number to another; most for any number to a Decimal, which leaves the type it was
 * asked to be.
 */
private fun cost(from: NumberType, to: NumberType): Int =
	when {
		from == to -> 0
		from == NumberType.Decimal -> 1
		from is Fixed && (to is Fixed || to == NumberType.BigInteger) -> 2
		from == Floating.Float && to == Floating.Double -> 2
		to is Floating -> 3
		to == NumberType.Decimal -> 5
		else -> 4
	}

/** [function] called on [arguments]: by the overload that asks least change of them, or why none takes them. */
fun call(function: Function, arguments: List<NumberValue>): Called {
	data class Fit(
		val overload: Overload,
		val values: List<NumberValue>,
		val cost: Int,
	)

	val tried =
		function.overloads.map { overload ->
			fit(overload, arguments)?.let { made ->
				when (made) {
					is Either.Left -> made
					is Either.Right -> Either.Right(Fit(overload, made.value.first, made.value.second))
				}
			}
		}

	val best =
		tried
			.filterIsInstance<Either.Right<Fit>>()
			.map {
				it.value
			}.minByOrNull { it.cost }

	return when {
		best != null -> Called.Done(best.overload.compute(best.values))
		tried.all { it == null } ->
			Called.Refused(
				"${function.name} takes ${function.overloads.map {
					it.parameters.size
				}.distinct().sorted()
					.joinToString(
						" or ",
					)} argument${if (function.overloads.all { it.parameters.size == 1 })
					""
				else
					"s"}: " +
					function.signatures.joinToString("; "),
			)
		else -> {
			val why = tried.filterIsInstance<Either.Left<String>>().first().value

			Called.Refused(
				if (function.overloads.size == 1)
					"${function.signatures.single()}: $why"
				else
					"${function.name}: $why",
			)
		}
	}
}

/** [arguments] made what [overload] takes, and what that cost; none where it takes another number of them. */
private fun fit(
	overload: Overload,
	arguments: List<NumberValue>,
): Either<String, Pair<List<NumberValue>, Int>>? {
	if (overload.parameters.size != arguments.size)
		return null

	val same =
		overload.parameters
			.zip(arguments)
			.filter {
				it.first.takes is Takes.Same
			}.map { it.second.type }

	val t =
		same.reduceOrNull { a, b ->
			when (val made = common(a, b)) {
				is Either.Left -> return Either.Left(made.value)
				is Either.Right -> made.value
			}
		}

	overload.kind?.let { kind ->
		if (t != null && !kind.allows(t))
			return Either.Left("it takes ${kind.written}, and this is a${an(t)} ${t.written}")
	}

	val made =
		overload.parameters.zip(arguments).map { (parameter, argument) ->
			val to =
				when (val takes = parameter.takes) {
					is Takes.One -> takes.type
					is Takes.Same -> t ?: argument.type
				}

			when (val converted = implicitly(argument, to)) {
				is Converted.Refused -> return Either.Left(converted.why)
				is Converted.Done -> converted.value to cost(argument.type, to)
			}
		}

	return Either.Right(made.map { it.first } to made.sumOf { it.second })
}

private fun one(name: String, type: NumberType) = Parameter(name, Takes.One(type))

private fun same(name: String, kind: Kind = Kind.Number) =
	Parameter(name, Takes.Same(kind))

private val DOUBLE = Floating.Double
private val FLOAT = Floating.Float
private val DECIMAL = NumberType.Decimal
private val INT = Fixed.Int

/** The value of a Double argument. */
private val NumberValue.double: Double get() = (this as FloatValue).value

private val NumberValue.decimal: BigDecimal get() = (this as DecimalValue).value

private val NumberValue.whole: BigInteger get() = (this as BigIntegerValue).value

private val NumberValue.int: Int get() = (this as FixedValue).value.toInt()

private fun done(value: Value, notes: List<String> = emptyList()): Computed =
	Computed.Done(value, notes)

/**
 * A function of floating-point numbers, as Kotlin's kotlin.math has: of [parameters] Doubles giving a Double, and of
 * as many Floats giving a Float, worked out by [of].
 */
private fun floating(
	name: String,
	does: String,
	vararg parameters: String,
	of: (DoubleArray) -> Double,
): Function =
	Function(
		name,
		does,
		listOf(DOUBLE, FLOAT).map { type ->
			Overload(parameters.map { one(it, type) }, Gives.One(type)) { values ->
				done(FloatValue.of(type, of(values.map { it.double }.toDoubleArray())))
			}
		},
	)

/** A function of one number of any type, giving the same type, worked out for each kind of number by its own way. */
private fun each(
	name: String,
	does: String,
	decimal: (BigDecimal) -> Computed,
	whole: (Fixed?, BigInteger) -> Computed,
	floating: (Floating, Double) -> Double,
): Function =
	Function(
		name,
		does,
		listOf(
			Overload(listOf(same("x")), Gives.Same) { (x) ->
				when (x) {
					is DecimalValue -> decimal(x.value)
					is BigIntegerValue -> whole(null, x.value)
					is FixedValue -> whole(x.type, x.value)
					is FloatValue -> done(FloatValue.of(x.type, floating(x.type, x.value)))
				}
			},
		),
	)

/** A whole number made a value of what it was: a BigInteger, or a fixed-width integer, wrapped where it must be. */
private fun wholeOf(type: Fixed?, whole: BigInteger): Computed =
	if (type == null)
		done(BigIntegerValue(whole))
	else
		fixed(type, whole)

/** A conversion to [type], as Kotlin's own (`toInt()`): what it changes said. */
private fun conversion(type: NumberType, name: String = "to${type.written}"): Function =
	Function(
		name,
		"this number as a${an(type)} ${type.written}, as Kotlin's $name() makes it",
		listOf(
			Overload(listOf(same("x")), Gives.One(type)) { (x) ->
				when (val made = explicitly(x, type)) {
					is Converted.Done -> done(made.value, made.notes)
					is Converted.Refused -> Computed.Failed(made.why)
				}
			},
		),
	)

/** A function that shows a number of [kind] in a form of its own, named [format], as [show] writes it. */
private fun format(
	name: String,
	does: String,
	format: String,
	kind: Kind = Kind.Number,
	vararg extra: Parameter,
	show: (NumberValue, List<NumberValue>) -> Either<String, String>,
): Function =
	Function(
		name,
		does,
		listOf(
			Overload(listOf(same("x", kind)) + extra, Gives.Text) { values ->
				when (val shown = show(values.first(), values.drop(1))) {
					is Either.Left -> Computed.Failed(shown.value)
					is Either.Right -> done(Formatted(values.first(), format, shown.value))
				}
			},
		),
	)

/**
 * [x]'s bits in [radix], after [prefix]: a fixed-width integer's as its bits are (its two's complement: -1 as an Int is
 * 0xffffffff), and any other whole number's with a sign.
 */
private fun inRadix(x: NumberValue, radix: Int, prefix: String): Either<String, String> =
	when (x) {
		is FixedValue ->
			Either.Right(
				prefix + x.value.mod(BigInteger.ONE.shiftLeft(x.type.bits)).toString(radix),
			)
		else ->
			when (val made = implicitly(x, NumberType.BigInteger)) {
				is Converted.Refused -> Either.Left(made.why)
				is Converted.Done ->
					(made.value as BigIntegerValue).value.let {
						Either.Right((if (it.signum() < 0) "-" else "") + prefix + it.abs().toString(radix))
					}
			}
	}

/** [x] exactly, as a Decimal, or why it is not a number that can be written so. */
private fun exactOrWhy(x: NumberValue): Either<String, BigDecimal> =
	exactOf(x)?.let { Either.Right(it) }
		?: Either.Left("${said(x)} is not a number that can be written so")

/** The largest power a Decimal or a whole number is raised to exactly. */
private const val MOST_EXACT_POWER = 10_000

/** The largest number whose factorial is worked out. */
private const val MOST_FACTORIAL = 5_000

private fun decimalOrFailed(make: () -> BigDecimal): Computed =
	try {
		done(DecimalValue(make()))
	} catch (wrong: ArithmeticException) {
		Computed.Failed(wrong.message ?: "it can't be worked out")
	}

/** What the calculator knows: its functions and its constants. */
object Library {
	private val PI = BigDecimal("3.141592653589793238462643383279503")
	private val E = BigDecimal("2.718281828459045235360287471352662")
	private val PHI = BigDecimal("1.618033988749894848204586834365638")

	val CONSTANTS: List<Constant> =
		listOf(
			Constant(
				"pi",
				DecimalValue(PI),
				"the ratio of a circle's circumference to its diameter",
			),
			Constant("tau", DecimalValue(PI * BigDecimal.TWO), "a full turn, in radians: 2π"),
			Constant("e", DecimalValue(E), "the base of the natural logarithm"),
			Constant("phi", DecimalValue(PHI), "the golden ratio"),
		)

	val FUNCTIONS: List<Function> =
		listOf(
			// Trigonometry, in radians.
			floating("sin", "the sine of an angle, in radians", "x") { kotlin.math.sin(it[0]) },
			floating("cos", "the cosine of an angle, in radians", "x") { kotlin.math.cos(it[0]) },
			floating("tan", "the tangent of an angle, in radians", "x") { kotlin.math.tan(it[0]) },
			floating("asin", "the angle whose sine this is, in radians", "x") {
				kotlin.math.asin(it[0])
			},
			floating("acos", "the angle whose cosine this is, in radians", "x") {
				kotlin.math.acos(it[0])
			},
			floating("atan", "the angle whose tangent this is, in radians", "x") {
				kotlin.math.atan(it[0])
			},
			floating(
				"atan2",
				"the angle of the point (x, y) from the x axis, in radians",
				"y",
				"x",
			) {
				kotlin.math.atan2(it[0], it[1])
			},
			floating("sinh", "the hyperbolic sine", "x") { kotlin.math.sinh(it[0]) },
			floating("cosh", "the hyperbolic cosine", "x") { kotlin.math.cosh(it[0]) },
			floating("tanh", "the hyperbolic tangent", "x") { kotlin.math.tanh(it[0]) },
			floating("asinh", "the inverse hyperbolic sine", "x") { kotlin.math.asinh(it[0]) },
			floating("acosh", "the inverse hyperbolic cosine", "x") { kotlin.math.acosh(it[0]) },
			floating("atanh", "the inverse hyperbolic tangent", "x") { kotlin.math.atanh(it[0]) },
			floating(
				"hypot",
				"the length of the hypotenuse of a right triangle: √(x² + y²)",
				"x",
				"y",
			) {
				kotlin.math.hypot(it[0], it[1])
			},
			floating("toDegrees", "an angle in radians, in degrees", "radians") {
				Math.toDegrees(it[0])
			},
			floating("toRadians", "an angle in degrees, in radians", "degrees") {
				Math.toRadians(it[0])
			},
			floating(
				"deg",
				"an angle in radians, in degrees",
				"radians",
			) { Math.toDegrees(it[0]) },
			floating(
				"rad",
				"an angle in degrees, in radians",
				"degrees",
			) { Math.toRadians(it[0]) },
			// Powers and logarithms.
			floating("exp", "e raised to this power", "x") { kotlin.math.exp(it[0]) },
			floating("expm1", "e raised to this power, less 1: exact for a small x", "x") {
				kotlin.math.expm1(it[0])
			},
			floating("ln", "the natural logarithm", "x") { kotlin.math.ln(it[0]) },
			floating("ln1p", "the natural logarithm of 1 + x: exact for a small x", "x") {
				kotlin.math.ln1p(it[0])
			},
			floating("log10", "the logarithm in base 10", "x") { kotlin.math.log10(it[0]) },
			floating("log2", "the logarithm in base 2", "x") { kotlin.math.log2(it[0]) },
			Function(
				"log",
				"the logarithm in base 10, or in the base given",
				listOf(
					Overload(listOf(one("x", DOUBLE)), Gives.One(DOUBLE)) { (x) ->
						done(FloatValue.of(DOUBLE, kotlin.math.log10(x.double)))
					},
					Overload(
						listOf(one("x", DOUBLE), one("base", DOUBLE)),
						Gives.One(DOUBLE),
					) { (x, base) ->
						done(FloatValue.of(DOUBLE, kotlin.math.log(x.double, base.double)))
					},
				),
			),
			Function(
				"sqrt",
				"the square root: of a Decimal, to 34 digits",
				listOf(
					Overload(listOf(one("x", DECIMAL)), Gives.One(DECIMAL)) { (x) ->
						if (x.decimal.signum() < 0)
							Computed.Failed(
								"a negative number has no square root that is a Decimal (as a Double, it is NaN)",
							)
						else
							done(DecimalValue(x.decimal.sqrt(PRECISION)))
					},
					Overload(
						listOf(one("x", DOUBLE)),
						Gives.One(DOUBLE),
					) { (x) -> done(FloatValue.of(DOUBLE, kotlin.math.sqrt(x.double))) },
					Overload(
						listOf(one("x", FLOAT)),
						Gives.One(FLOAT),
					) { (x) -> done(FloatValue.of(FLOAT, kotlin.math.sqrt(x.double))) },
				),
			),
			floating("cbrt", "the cube root", "x") { kotlin.math.cbrt(it[0]) },
			Function(
				"pow",
				"a number raised to a power: exactly, where the power is a whole number and the number is not a Double",
				listOf(
					Overload(
						listOf(one("base", DECIMAL), one("exponent", INT)),
						Gives.One(DECIMAL),
					) { (base, exponent) ->
						val n = exponent.int

						when {
							kotlin.math.abs(n) > MOST_EXACT_POWER ->
								Computed.Failed(
									"a power above $MOST_EXACT_POWER is not worked out exactly: make it a Double (toDouble())",
								)
							n >= 0 -> done(DecimalValue(base.decimal.pow(n)))
							base.decimal.signum() == 0 -> Computed.Failed("division by zero")
							else -> decimalOrFailed { BigDecimal.ONE.divide(base.decimal.pow(-n), PRECISION) }
						}
					},
					Overload(
						listOf(same("base", Kind.Whole), one("exponent", INT)),
						Gives.Same,
					) { (base, exponent) ->
						val n = exponent.int
						val whole = (base as? BigIntegerValue)?.value ?: (base as FixedValue).value

						when {
							n < 0 ->
								Computed.Failed(
									"a whole number raised to a negative power is not whole: make it a Decimal or a Double",
								)
							n > MOST_EXACT_POWER ->
								Computed.Failed(
									"a power above $MOST_EXACT_POWER is not worked out exactly",
								)
							else -> wholeOf((base as? FixedValue)?.type, whole.pow(n))
						}
					},
					Overload(
						listOf(one("base", DOUBLE), one("exponent", DOUBLE)),
						Gives.One(DOUBLE),
					) { (base, exponent) ->
						done(FloatValue.of(DOUBLE, Math.pow(base.double, exponent.double)))
					},
					Overload(
						listOf(one("base", FLOAT), one("exponent", FLOAT)),
						Gives.One(FLOAT),
					) { (base, exponent) ->
						done(FloatValue.of(FLOAT, Math.pow(base.double, exponent.double)))
					},
				),
			),
			// Of any number, giving its own type.
			each(
				"abs",
				"the number without its sign",
				{ done(DecimalValue(it.abs())) },
				{ type, it -> wholeOf(type, it.abs()) },
				{ _, it -> kotlin.math.abs(it) },
			),
			each(
				"sign",
				"-1, 0 or 1, as the number is negative, zero or positive",
				{ done(DecimalValue(it.signum().toBigDecimal())) },
				{ type, it -> wholeOf(type, it.signum().toBigInteger()) },
				{ _, it -> kotlin.math.sign(it) },
			),
			each(
				"floor",
				"the largest whole number no greater than this",
				{ done(DecimalValue(it.setScale(0, RoundingMode.FLOOR))) },
				{ type, it -> wholeOf(type, it) },
				{ _, it -> kotlin.math.floor(it) },
			),
			each(
				"ceil",
				"the smallest whole number no less than this",
				{ done(DecimalValue(it.setScale(0, RoundingMode.CEILING))) },
				{ type, it -> wholeOf(type, it) },
				{ _, it -> kotlin.math.ceil(it) },
			),
			each(
				"truncate",
				"the whole number this is, the fraction dropped",
				{ done(DecimalValue(it.setScale(0, RoundingMode.DOWN))) },
				{ type, it -> wholeOf(type, it) },
				{ _, it -> kotlin.math.truncate(it) },
			),
			Function(
				"round",
				"the nearest whole number, halves away from zero; or to the number of places given",
				listOf(
					Overload(listOf(same("x")), Gives.Same) { (x) ->
						when (x) {
							is DecimalValue -> done(DecimalValue(x.value.setScale(0, RoundingMode.HALF_UP)))
							is FloatValue -> done(FloatValue.of(x.type, kotlin.math.round(x.value)))
							else -> done(x)
						}
					},
					Overload(
						listOf(one("x", DECIMAL), one("places", INT)),
						Gives.One(DECIMAL),
					) { (x, places) ->
						done(DecimalValue(x.decimal.setScale(places.int, RoundingMode.HALF_UP)))
					},
				),
			),
			Function(
				"min",
				"the smaller of two numbers",
				listOf(
					Overload(listOf(same("a"), same("b")), Gives.Same) { (a, b) ->
						done(if (compare(a, b) <= 0) a else b)
					},
				),
			),
			Function(
				"max",
				"the larger of two numbers",
				listOf(
					Overload(listOf(same("a"), same("b")), Gives.Same) { (a, b) ->
						done(if (compare(a, b) >= 0) a else b)
					},
				),
			),
			Function(
				"coerceIn",
				"this number, held between the two given",
				listOf(
					Overload(
						listOf(same("x"), same("least"), same("most")),
						Gives.Same,
					) { (x, least, most) ->
						when {
							compare(
								least,
								most,
							) > 0 -> Computed.Failed("${said(least)} is more than ${said(most)}")
							compare(x, least) < 0 -> done(least)
							compare(x, most) > 0 -> done(most)
							else -> done(x)
						}
					},
				),
			),
			Function(
				"mod",
				"the remainder of a division whose quotient is rounded down: of the sign of the divisor, as Kotlin's mod",
				listOf(
					Overload(
						listOf(one("a", DECIMAL), one("b", DECIMAL)),
						Gives.One(DECIMAL),
					) { (a, b) ->
						if (b.decimal.signum() == 0)
							Computed.Failed("division by zero")
						else
							a.decimal.remainder(b.decimal).let { r ->
								done(
									DecimalValue(
										if (r.signum() != 0 &&
											r.signum() != b.decimal.signum()
										)
											r + b.decimal
										else
											r,
									),
								)
							}
					},
					Overload(
						listOf(same("a", Kind.Whole), same("b", Kind.Whole)),
						Gives.Same,
					) { (a, b) ->
						val x = (a as? BigIntegerValue)?.value ?: (a as FixedValue).value
						val y = (b as? BigIntegerValue)?.value ?: (b as FixedValue).value

						if (y.signum() == 0)
							Computed.Failed("division by zero")
						else
							x.rem(y).let { r ->
								wholeOf(
									(a as? FixedValue)?.type,
									if (r.signum() != 0 && r.signum() != y.signum())
										r + y
									else
										r,
								)
							}
					},
					Overload(listOf(one("a", DOUBLE), one("b", DOUBLE)), Gives.One(DOUBLE)) { (a, b) ->
						done(FloatValue.of(DOUBLE, a.double.mod(b.double)))
					},
				),
			),
			Function(
				"factorial",
				"the product of every whole number from 1 up to this",
				listOf(
					Overload(
						listOf(one("n", NumberType.BigInteger)),
						Gives.One(NumberType.BigInteger),
					) { (n) ->
						when {
							n.whole.signum() < 0 -> Computed.Failed("a negative number has no factorial")
							n.whole > MOST_FACTORIAL.toBigInteger() ->
								Computed.Failed(
									"factorials past $MOST_FACTORIAL are not worked out",
								)
							else ->
								done(
									BigIntegerValue(
										(1..n.whole.toInt()).fold(BigInteger.ONE) { product, k ->
											product *
												k.toBigInteger()
										},
									),
								)
						}
					},
				),
			),
			Function(
				"gcd",
				"the greatest common divisor",
				listOf(
					Overload(
						listOf(one("a", NumberType.BigInteger), one("b", NumberType.BigInteger)),
						Gives.One(NumberType.BigInteger),
					) { (a, b) ->
						done(BigIntegerValue(a.whole.gcd(b.whole)))
					},
				),
			),
			Function(
				"lcm",
				"the least common multiple",
				listOf(
					Overload(
						listOf(one("a", NumberType.BigInteger), one("b", NumberType.BigInteger)),
						Gives.One(NumberType.BigInteger),
					) { (a, b) ->
						if (a.whole.signum() == 0 || b.whole.signum() == 0)
							done(BigIntegerValue(BigInteger.ZERO))
						else
							done(BigIntegerValue((a.whole * b.whole).abs() / a.whole.gcd(b.whole)))
					},
				),
			),
			// Bits.
			Function(
				"inv",
				"every bit turned over, as Kotlin's inv()",
				listOf(
					Overload(listOf(same("x", Kind.Whole)), Gives.Same) { (x) ->
						when (x) {
							is FixedValue -> wholeOf(x.type, x.value.not())
							else -> done(BigIntegerValue(x.whole.not()))
						}
					},
				),
			),
			Function(
				"countOneBits",
				"how many of its bits are 1",
				listOf(
					Overload(listOf(same("x", Kind.FixedWidth)), Gives.One(INT)) { (x) ->
						val bits = (x as FixedValue).value.mod(BigInteger.ONE.shiftLeft(x.type.bits))

						done(Fixed.Int.wrapped(bits.bitCount().toBigInteger()).value)
					},
				),
			),
			floating(
				"ulp",
				"how far it is to the next number of its type: the size of its last digit",
				"x",
			) {
				Math.ulp(it[0])
			},
			floating(
				"nextUp",
				"the next number of its type above this",
				"x",
			) { Math.nextUp(it[0]) },
			floating("nextDown", "the next number of its type below this", "x") {
				Math.nextDown(it[0])
			},
		) +
			// Conversions to each type, as Kotlin's.
			NumberType.ALL.filter { it != NumberType.Decimal }.map { conversion(it) } +
			listOf(
				conversion(NumberType.Decimal, "toBigDecimal"),
				conversion(NumberType.Decimal, "toDecimal"),
			) +
			// Formatting: a number shown in a form of its own.
			listOf(
				format(
					"toHex",
					"its bits in hexadecimal: a fixed-width integer's as its bits are",
					"hex",
				) { x, _ -> inRadix(x, 16, "0x") },
				format(
					"toBinary",
					"its bits in binary: a fixed-width integer's as its bits are",
					"binary",
				) { x, _ -> inRadix(x, 2, "0b") },
				format(
					"toOctal",
					"its bits in octal: a fixed-width integer's as its bits are",
					"octal",
				) { x, _ -> inRadix(x, 8, "0o") },
				format(
					"toString",
					"written in the base given, as Kotlin's toString(radix)",
					"base",
					Kind.Number,
					one("radix", INT),
				) { x, (radix) ->
					val base = radix.int

					when {
						base !in Character.MIN_RADIX..Character.MAX_RADIX ->
							Either.Left("a base is from ${Character.MIN_RADIX} to ${Character.MAX_RADIX}")
						else ->
							when (val made = implicitly(x, NumberType.BigInteger)) {
								is Converted.Refused -> Either.Left(made.why)
								is Converted.Done ->
									Either.Right(
										(made.value as BigIntegerValue).value.toString(base),
									)
							}
					}
				},
				format(
					"grouped",
					"written with its thousands apart: 1,234,567.89",
					"grouped",
				) { x, _ ->
					when (val exact = exactOrWhy(x)) {
						is Either.Left -> exact
						is Either.Right ->
							Either.Right(
								DecimalFormat("#,##0.##########", DecimalFormatSymbols(Locale.ROOT))
									.apply { maximumFractionDigits = 340 }
									.format(exact.value),
							)
					}
				},
				format(
					"toFixed",
					"written to the number of places given, halves away from zero",
					"fixed",
					Kind.Number,
					one("places", INT),
				) { x, (places) ->
					when (val exact = exactOrWhy(x)) {
						is Either.Left -> exact
						is Either.Right ->
							if (places.int < 0)
								Either.Left("a number of places is not negative")
							else
								Either.Right(
									exact.value.setScale(places.int, RoundingMode.HALF_UP).toPlainString(),
								)
					}
				},
				format(
					"toScientific",
					"written as a number from 1 to 10 times a power of ten: 1.23E+6",
					"scientific",
				) { x, _ ->
					when (val exact = exactOrWhy(x)) {
						is Either.Left -> exact
						is Either.Right ->
							Either.Right(
								exact.value.stripTrailingZeros().let {
									if (it.signum() == 0)
										"0E+0"
									else
										"${it.movePointLeft(it.precision() - it.scale() - 1).toPlainString()}E" +
											(it.precision() - it.scale() - 1).let { power ->
												if (power >=
													0
												)
													"+$power"
												else
													"$power"
											}
								},
							)
					}
				},
				format("toPercent", "as a percentage: 0.25 is 25%", "percent") { x, _ ->
					when (val exact = exactOrWhy(x)) {
						is Either.Left -> exact
						is Either.Right ->
							Either.Right(
								"${said(DecimalValue(exact.value.movePointRight(2)))}%",
							)
					}
				},
			)

	init {
		val names =
			FUNCTIONS.map { it.name } + CONSTANTS.map { it.name } + PROPERTIES.map { it.name }

		require(names.size == names.toSet().size) {
			"every function and constant has a name of its own: ${names.groupBy {
				it
			}.filter { it.value.size > 1 }.keys}"
		}
	}

	private val functionsByName: Map<String, Function> = FUNCTIONS.associateBy { it.name }
	private val constantsByName: Map<String, Constant> = CONSTANTS.associateBy { it.name }

	/** The properties after a dot that make a number one of Kotlin's types: `5.uint`, `0.1.double`. */
	val PROPERTIES: List<TypedLiteral>
		get() =
			listOf(
				TypedLiteral("byte", Fixed.Byte),
				TypedLiteral("short", Fixed.Short),
				TypedLiteral("int", Fixed.Int),
				TypedLiteral("long", Fixed.Long),
				TypedLiteral("ubyte", Fixed.UByte),
				TypedLiteral("ushort", Fixed.UShort),
				TypedLiteral("uint", Fixed.UInt),
				TypedLiteral("ulong", Fixed.ULong),
				TypedLiteral("float", Floating.Float),
				TypedLiteral("double", Floating.Double),
			)

	/** The property called [name], in whatever case. */
	fun property(name: String): TypedLiteral? =
		PROPERTIES.firstOrNull { it.name.equals(name, ignoreCase = true) }

	/** The function called [name]: by its own case, or, where none is, by any (`Sin`, `SQRT`). */
	fun function(name: String): Function? =
		functionsByName[name]
			?: FUNCTIONS.firstOrNull { it.name.equals(name, ignoreCase = true) }

	/** The constant called [name], in whatever case (`pi`, `PI`, `Pi`). */
	fun constant(name: String): Constant? =
		constantsByName[name]
			?: CONSTANTS.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** [a] against [b], of one type, as numbers: NaN the largest, as Kotlin's compareTo has it. */
private fun compare(a: NumberValue, b: NumberValue): Int =
	when (a) {
		is DecimalValue -> a.value.compareTo((b as DecimalValue).value)
		is BigIntegerValue -> a.value.compareTo((b as BigIntegerValue).value)
		is FixedValue -> a.value.compareTo((b as FixedValue).value)
		is FloatValue -> a.value.compareTo((b as FloatValue).value)
	}
