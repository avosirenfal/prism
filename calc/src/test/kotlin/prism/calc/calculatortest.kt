package prism.calc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The calculator: what it makes of what is typed, as a person and as a programmer would have it. */
class CalculatorTest {
	/** What [text] comes to, as it is shown, with its type where it is said. */
	private fun answer(text: String): String {
		val outcome = Calculation.of(text).outcome

		check(outcome is Outcome.Answer) { "$text: $outcome" }

		return outcome.type?.let { "${outcome.shown} : $it" } ?: outcome.shown
	}

	private fun notes(text: String): List<String> =
		(Calculation.of(text).outcome as Outcome.Answer).notes

	private fun wrong(text: String): String =
		when (val outcome = Calculation.of(text).outcome) {
			is Outcome.Wrong -> outcome.why
			is Outcome.Unread -> "unread at ${outcome.at}: ${outcome.why}"
			is Outcome.Answer -> error("$text came to ${outcome.shown}")
		}

	@Test
	fun whatIsTypedIsWorkedOutExactlyAsADecimal() {
		assertEquals("0.3", answer("0.1 + 0.2"))
		assertEquals("14", answer("2+3*4"))
		assertEquals("20", answer("(2+3)*4"))
		assertEquals("0.3333333333333333333333333333333333", answer("1/3"))
		assertEquals("1", answer("10 % 3"))
		assertEquals("-4", answer("-2**2"))
		assertEquals("512", answer("2**3**2"))
		assertEquals("0.5", answer("2**-1"))
		assertEquals("1267650600228229401496703205376", answer("2**100"))
		assertEquals("1000000", answer("1_000_000"))
		assertEquals("255", answer("0xff"))
		assertEquals("5", answer("0b101"))
		assertEquals("1500", answer("1.5e3"))
	}

	@Test
	fun functionsAreCalledByTheOverloadThatChangesLeast() {
		assertEquals("1.414213562373095048801688724209698", answer("sqrt(2)"))
		assertEquals("1.4142135623730951 : Double", answer("sqrt(2.toDouble())"))
		assertEquals("1.4142135623730951 : Double", answer("2.toDouble().sqrt()"))
		assertEquals("1.0 : Double", answer("sin(pi/2)"))
		assertEquals("0.7853981633974483 : Double", answer("atan2(1, 1)"))
		assertEquals("1024", answer("pow(2, 10)"))
		assertEquals("1.4142135623730951 : Double", answer("pow(2, 0.5)"))
		assertEquals("3", answer("abs(-3)"))
		assertEquals("2", answer("min(2, 3)"))
		assertEquals("3.14", answer("round(pi, 2)"))
		assertEquals("120 : BigInteger", answer("factorial(5)"))
		assertEquals("6.283185307179586476925286766559006", answer("tau"))
	}

	@Test
	fun kotlinsTypesAreAskedForAndKeptAndTheirOverflowShown() {
		assertEquals("5 : UInt", answer("5.toUInt()"))
		assertEquals("6 : UInt", answer("5.toUInt() + 1"))
		assertEquals("5 : Long", answer("5L"))
		assertEquals("-2147483648 : Int", answer("2147483647.toInt() + 1"))
		assertEquals(
			listOf(
				"Overflow: 2147483648 > 2147483647 (Int.MAX_VALUE)",
			),
			notes("2147483647.toInt() + 1"),
		)
		assertEquals("4294967295 : UInt", answer("0u - 1u"))
		// A Byte's arithmetic is a Byte's, and goes past its largest as soon as it can.
		assertEquals("-56 : Byte", answer("100.toByte() + 100.toByte()"))
		assertEquals(
			listOf("Overflow: 200 > 127 (Byte.MAX_VALUE)"),
			notes("100.toByte() + 100.toByte()"),
		)
		assertEquals(
			listOf("Underflow: -1 < 0 (UInt.MIN_VALUE)"),
			notes("0u - 1u"),
		)
		assertEquals("-56 : Byte", answer("200.toByte()"))
		assertEquals(
			listOf("Overflow: 200 > 127 (Byte.MAX_VALUE)"),
			notes("200.toByte()"),
		)
		assertEquals("3 : Int", answer("3.7.toInt()"))
		assertEquals("2147483647 : Int", answer("1e20.toDouble().toInt()"))
		assertEquals("0.30000000000000004 : Double", answer("0.1.toDouble() + 0.2"))
		assertEquals("1.5 : Float", answer("1.5f"))
		assertEquals("3 : Int", answer("7.toInt() / 2"))
		assertEquals("Infinity : Double", answer("1.toDouble() / 0"))
	}

	@Test
	fun bitsAreAWholeNumbersAsKotlinHasThem() {
		assertEquals("16", answer("1 shl 4"))
		assertEquals("2 : Int", answer("1.toInt() shl 33"))
		assertEquals("2147483647 : Int", answer("-1.toInt() ushr 1"))
		assertEquals("8", answer("12 and 10"))
		assertEquals("6", answer("12 xor 10"))
		assertEquals("-1 : Int", answer("0.toInt().inv()"))
		// Kotlin's infix words bind more loosely than + and -: this is 1 shl 5.
		assertEquals("32", answer("1 shl 4 + 1 - 1 + 1"))
		// And a minus before a call is of what the call gives: -(1.toInt()).
		assertEquals("-1 : Int", answer("-1.toInt()"))
	}

	@Test
	fun formsAreShownWithTheTypeTheyAreOf() {
		assertEquals("0xff : hex", answer("255.toHex()"))
		assertEquals("0xffffffff : Int · hex", answer("(-1).toInt().toHex()"))
		assertEquals("0b101 : binary", answer("5.toBinary()"))
		assertEquals("1,234,567.89 : grouped", answer("1234567.89.grouped()"))
		assertEquals("3.14 : fixed", answer("pi.toFixed(2)"))
		assertEquals("1.23E+6 : scientific", answer("1230000.toScientific()"))
		assertEquals("ff : base", answer("255.toString(16)"))
		assertEquals("25% : percent", answer("0.25.toPercent()"))
		// A form is its number still.
		assertEquals("256", answer("255.toHex() + 1"))
	}

	@Test
	fun whatCannotBeDoneIsSaidAndWhere() {
		assertEquals(
			"1.5 is not a whole number, so it can't be a UInt",
			wrong("5.toUInt() + 1.5"),
		)
		assertEquals(
			"sin: 1E+400 is too large for a Double",
			wrong("sin(1e400)"),
		)
		assertTrue(
			wrong("5.toUInt() + 1.toInt()").startsWith("a UInt and an Int can't be mixed"),
		)
		assertEquals("division by zero", wrong("1/0"))
		assertEquals("there is no function nope", wrong("nope(1)"))
		assertEquals("there is no x", wrong("x + 1"))
		assertEquals("sin is a function: call it, as sin(…)", wrong("sin + 1"))
		assertTrue(
			wrong(
				"atan2(1)",
			).startsWith("atan2 takes 2 arguments: atan2(y: Double, x: Double): Double"),
		)
		assertEquals(
			"unread at 4: expected a name, a number, “(”, “+”, “-” or “~”",
			wrong("1 + "),
		)
		assertTrue(wrong("-5u").startsWith("a UInt can't be negated"))
		assertEquals("300 doesn't fit in a Byte (-128..127)", wrong("100.toByte() + 300"))
	}

	@Test
	fun whatLooksLikeArithmeticIsToldFromWhatDoesNot() {
		fun shape(text: String) = Calculation.of(text).shape

		assertEquals(Shape.Calculation, shape("2+3"))
		assertEquals(Shape.Calculation, shape("sqrt(2)"))
		assertEquals(Shape.Calculation, shape("1 - 2"))
		assertEquals(Shape.Calculation, shape("5.toUInt()"))
		assertEquals(Shape.Ambiguous, shape("2024-11-18"))
		assertEquals(Shape.Ambiguous, shape("1/2"))
		assertEquals(Shape.Plain, shape("42"))
		assertEquals(Shape.Plain, shape("pi"))
		assertEquals(Shape.Plain, shape("-5"))
		assertEquals(Shape.Plain, shape("firefox"))
		assertEquals(null, shape("hello world"))
		assertEquals(null, shape("1.2.3"))
	}

	@Test
	fun theNameBeingTypedIsCompletedAndItsSignaturesShown() {
		fun names(text: String) = Calculation.of(text).completions.map { it.name }

		assertEquals(listOf("sin", "sign", "sinh"), names("si"))
		assertTrue("atan2" in names("1 + at"))
		assertEquals(
			listOf("atan2(y: Double, x: Double): Double", "atan2(y: Float, x: Float): Float"),
			Calculation
				.of("atan")
				.completions
				.single { it.name == "atan2" }
				.signatures,
		)
		// After a dot, what can be called on something, and no constants.
		assertTrue(names("5.to").containsAll(listOf("toInt", "toUInt", "toHex", "toDouble")))
		assertTrue("tau" !in names("5.t"))
		// A name typed whole, and the start of nothing else.
		assertEquals(emptyList<String>(), names("2*pi"))
		assertEquals(emptyList<String>(), names("2+3"))
		assertTrue(names("e").containsAll(listOf("e", "exp")))
	}

	@Test
	fun csOperatorsOfBitsAreThereBesideKotlinsWordsAndBindAsInC() {
		assertEquals("16", answer("1 << 4"))
		assertEquals("4", answer("16 >> 2"))
		assertEquals("2147483647 : Int", answer("(-1).int >>> 1"))
		assertEquals("8", answer("12 & 10"))
		assertEquals("14", answer("12 | 10"))
		assertEquals("6", answer("12 ^ 10"))
		assertEquals("-1 : Int", answer("~0.int"))
		assertEquals("-6", answer("~5"))
		// As in C: shifts looser than +, & than shifts, ^ than &, | than ^.
		assertEquals("32", answer("1 << 4 + 1"))
		assertEquals("8", answer("1 << 3 & 15"))
		assertEquals("7", answer("1 | 2 ^ 4"))
		assertEquals("5", answer("1 | 4 & 5"))
		// And Kotlin's words are looser than all of them.
		assertEquals("3", answer("1 or 2 & 3"))
	}

	@Test
	fun aTypesNameAfterADotMakesANumberThatTypeAsALiteralOfItWould() {
		assertEquals("5 : Int", answer("5.int"))
		assertEquals("5 : UInt", answer("5.uint"))
		assertEquals("5 : Short", answer("5.short"))
		assertEquals("5 : UShort", answer("5.ushort"))
		assertEquals("5 : Long", answer("5.long"))
		assertEquals("5 : ULong", answer("5.ulong"))
		assertEquals("5 : Byte", answer("5.byte"))
		assertEquals("5 : UByte", answer("5.ubyte"))
		assertEquals("5.0 : Float", answer("5.float"))
		assertEquals("0.1 : Double", answer("0.1.double"))
		// Where it would change the number, it is refused, as a literal of the type would be; toByte() wraps it.
		assertEquals("300 doesn't fit in a Byte (-128..127)", wrong("300.byte"))
		assertEquals("44 : Byte", answer("300.toByte()"))
		assertEquals("there is no property nope", wrong("5.nope"))
		assertEquals("toInt is a function: call it, as .toInt()", wrong("5.toInt"))
		assertTrue(
			Calculation
				.of("5.u")
				.completions
				.map {
					it.name
				}.containsAll(listOf("ubyte", "uint", "ulong", "ushort")),
		)
		assertEquals(Shape.Plain, Calculation.of("5.uint").shape)
	}
}
