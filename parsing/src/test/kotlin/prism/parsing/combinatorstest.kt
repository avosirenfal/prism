package prism.parsing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The combinators: what they read, and where and why they say what they could not. */
class CombinatorsTest {
	private val digit = charThat(Char::isDigit) named "a digit"
	private val number = digit.some().joined().map(String::toInt) named "a number"

	/** Numbers joined by `+`, `-` or `^`: the first two from the left, the last from the right and first. */
	private val sum: Parser<Int> =
		deferred {
			val power =
				number.rightAssociative(
					char('^').map {
						{
							base: Int,
							exponent: Int,
							->
							Math.pow(base.toDouble(), exponent.toDouble()).toInt()
						}
					},
				)

			power.leftAssociative(
				firstOf(
					char('+').map { { a: Int, b: Int -> a + b } },
					char('-').map { { a: Int, b: Int -> a - b } },
				),
			)
		}

	@Test
	fun operatorsAreGroupedAsTheyAreMeant() {
		assertEquals(Parsed.Read(4), sum.parseAll("10-3-3"))
		assertEquals(Parsed.Read(512), sum.parseAll("2^3^2"))
		assertEquals(Parsed.Read(9), sum.parseAll("1+2^3"))
	}

	@Test
	fun whatIsWrongIsSaidWhereItIsAndAsWhatWouldHaveBeenTaken() {
		// What the last `+` wants, and not the end the rest of the text was asked for.
		assertEquals(Parsed.Wrong(4, setOf("a number")), sum.parseAll("1+2+"))
		assertEquals("expected a number", (sum.parseAll("1+2+") as Parsed.Wrong).said)
		// Something after a whole sum: the end was wanted, or another operator.
		assertEquals(
			Parsed.Wrong(3, setOf("a digit", "“+”", "“-”", "“^”", "the end")),
			sum.parseAll("1+2x"),
		)
		assertEquals(Parsed.Wrong(0, setOf("a number")), sum.parseAll(""))
	}

	@Test
	fun ofAlternativesTheOneThatGotFurthestIsSaid() {
		val pair = (char('(') and number and char(',') and number and char(')')) named "a pair"
		val either = firstOf(pair.map { 0 }, number)

		assertEquals(Parsed.Wrong(3, setOf("a digit", "“,”")), either.parseAll("(12"))
		assertEquals(Parsed.Read(0), either.parseAll("(1,2)"))
	}

	@Test
	fun separatedValuesAreAllTaken() {
		assertEquals(
			Parsed.Read(listOf(1, 22, 333)),
			number.separatedBy(char(',')).parseAll("1,22,333"),
		)
		assertEquals(
			Parsed.Wrong(4, setOf("a number")),
			number.separatedBy(char(',')).parseAll("1,2,"),
		)
	}
}
