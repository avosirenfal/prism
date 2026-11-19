package prism.calc

import java.math.BigDecimal
import java.math.BigInteger
import prism.parsing.Parsed
import prism.parsing.Parser
import prism.parsing.Span
import prism.parsing.after
import prism.parsing.and
import prism.parsing.before
import prism.parsing.char
import prism.parsing.charThat
import prism.parsing.deferred
import prism.parsing.firstOf
import prism.parsing.joined
import prism.parsing.leftAssociative
import prism.parsing.literal
import prism.parsing.many
import prism.parsing.map
import prism.parsing.named
import prism.parsing.not
import prism.parsing.optional
import prism.parsing.parseAll
import prism.parsing.pure
import prism.parsing.separatedBy
import prism.parsing.some
import prism.parsing.spanned
import prism.parsing.then

/*
 * The calculator's language, as it is written: Kotlin's arithmetic, with `**` for powers (and `^`, as calculators
 * have it). From the loosest to the tightest:
 *
 *   1 shl 4      Kotlin's infix words, grouped from the left: shl shr ushr and or xor
 *   1 + 2 - 3    grouped from the left
 *   2 * 3 / 4 % 5
 *   -x  +x
 *   2 ** 3 ** 2  grouped from the right; what is raised to may be negated (2 ** -1)
 *   x.f(a)       a function called on what is before its dot: f(x, a)
 *   1.5  0xff  0b101  1_000  1e9  5u  5L  5uL  1.5f   (x)   pi   f(a, b)
 *
 * A literal with no suffix is a Decimal; a suffix makes it one of Kotlin's types, as in Kotlin (`L` a Long, `u` a
 * UInt, `uL` a ULong, `f` a Float). Spaces are allowed between any two things.
 */

/** Something written in the language, where it is in the text. */
sealed interface Expression {
	val span: Span

	/** A number as it is written: [exact], of [type]. */
	data class Literal(
		val exact: BigDecimal,
		val type: NumberType,
		override val span: Span,
	) : Expression

	/** A name on its own: a constant, such as `pi`. */
	data class Name(
		val name: String,
		override val span: Span,
	) : Expression

	/**
	 * A function [name] (where it is: [named]) called with [arguments]; called on the first of them ([method]),
	 * where it was written after it and a dot (`5.toUInt()`).
	 */
	data class Call(
		val name: String,
		val named: Span,
		val arguments: List<Expression>,
		val method: Boolean,
		override val span: Span,
	) : Expression

	data class Prefixed(
		val operator: Prefix,
		val operand: Expression,
		override val span: Span,
	) : Expression

	/** [left] [operator] [right], the operator itself [at] its place. */
	data class Binary(
		val operator: Operator,
		val left: Expression,
		val right: Expression,
		val at: Span,
		override val span: Span,
	) : Expression

	/** What is in parentheses. */
	data class Grouped(
		val inner: Expression,
		override val span: Span,
	) : Expression
}

/** The language's grammar. */
object Grammar {
	private val space: Parser<Unit> = charThat(Char::isWhitespace).many().map { }

	/** [parser], and the spaces after it. */
	private fun <T> token(parser: Parser<T>): Parser<T> = parser before space

	private fun isNameStart(c: Char): Boolean = c.isLetter() || c == '_'

	private fun isNamePart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

	/** A name: a letter or `_`, then letters, digits and `_`. */
	val name: Parser<String> =
		(charThat(::isNameStart) and charThat(::isNamePart).many()).map { (first, rest) ->
			first + rest.joinToString("")
		} named "a name"

	/** [word] as a word of its own: not the start of a longer name. */
	private fun word(word: String): Parser<String> =
		literal(word) before not(charThat(::isNamePart))

	/** Digits for which [digit] is true, any of them `_` but the first, as Kotlin allows (`1_000_000`). */
	private fun digits(digit: (Char) -> Boolean): Parser<String> =
		(charThat(digit) and charThat { digit(it) || it == '_' }.many()).map { (first, rest) ->
			(listOf(first) + rest).filter { it != '_' }.joinToString("")
		}

	private val decimalDigits = digits(Char::isDigit)

	/** A whole number in a base of its own: `0xff`, `0b101`, `0o17`. */
	private fun based(prefix: String, radix: Int): Parser<BigDecimal> =
		(
			firstOf(literal(prefix), literal(prefix.uppercase())) after
				digits { Character.digit(it, radix) >= 0 }
		).map { BigInteger(it, radix).toBigDecimal() }

	/** A number in tens: a fraction only with a digit after its point, so that `5.toInt()` is 5's own call. */
	private val tens: Parser<BigDecimal> =
		(
			decimalDigits and
				(char('.') after decimalDigits).optional() and
				(
					charThat { it == 'e' || it == 'E' } after
						(charThat { it == '+' || it == '-' }.optional() and decimalDigits)
				).optional()
		).map { (whole, exponent) ->
			val (integer, fraction) = whole
			val written =
				integer + (fraction?.let { ".$it" } ?: "") +
					(exponent?.let { (sign, power) -> "E${sign ?: ""}$power" } ?: "")

			BigDecimal(written)
		}

	/** A literal's suffix, as Kotlin's: the type it makes the literal. */
	private val suffix: Parser<NumberType> =
		firstOf(
			firstOf(
				literal("uL"),
				literal("UL"),
				literal("ul"),
				literal("Ul"),
			).map { Fixed.ULong },
			charThat { it == 'u' || it == 'U' }.map { Fixed.UInt },
			char('L').map { Fixed.Long },
			charThat { it == 'f' || it == 'F' }.map { Floating.Float },
		) before not(charThat(::isNamePart))

	val number: Parser<Expression> =
		(
			firstOf(based("0x", 16), based("0b", 2), based("0o", 8), tens) and
				suffix.optional()
		).spanned()
			.map { (made, span) ->
				val (exact, type) = made

				Expression.Literal(exact, type ?: NumberType.Decimal, span) as Expression
			} named "a number"

	private val expression: Parser<Expression> = deferred { infix }

	/** What a function is called with: in parentheses, separated by commas; none at all included. */
	private val arguments: Parser<List<Expression>> =
		token(char('(')) after
			firstOf(expression.separatedBy(token(char(','))), pure(emptyList())) before
			token(char(')'))

	/** A name, called with arguments where they follow it: a function's call, or a constant. */
	private val named: Parser<Expression> =
		(name.spanned() before space and arguments.spanned().optional()).map { found ->
			val (called, given) = found

			if (given == null)
				Expression.Name(called.value, called.span)
			else
				Expression.Call(
					called.value,
					called.span,
					given.value,
					method = false,
					called.span..given.span,
				)
		}

	private val grouped: Parser<Expression> =
		(token(char('(')) after expression before char(')')).spanned().map { (inner, span) ->
			Expression.Grouped(inner, span) as Expression
		}

	private val primary: Parser<Expression> = token(firstOf(number, grouped, named))

	/** A primary, and the functions called on it after a dot, one after another. */
	private val postfix: Parser<Expression> =
		primary.then { first ->
			(
				token(
					char('.'),
				) after name.spanned() before space and arguments.spanned()
			).many().map { calls ->
				calls.fold(first) { on, (called, given) ->
					Expression.Call(
						called.value,
						called.span,
						listOf(on) + given.value,
						method = true,
						on.span..given.span,
					)
				}
			}
		}

	/** The operator [operator], written as [written] and followed by nothing that would make it another. */
	private fun binary(
		operator: Operator,
		written: Parser<*>,
	): Parser<(Expression, Expression) -> Expression> =
		token(written.spanned()).map { (_, at) ->
			{ left: Expression, right: Expression ->
				Expression.Binary(operator, left, right, at, left.span..right.span)
			}
		}

	private val unary: Parser<Expression> =
		deferred {
			firstOf(
				(
					token(
						firstOf(
							char('-').map {
								Prefix.Negate
							},
							char('+').map { Prefix.Same },
						).spanned(),
					) and
						unary
				).map { (operator, operand) ->
					Expression.Prefixed(
						operator.value,
						operand,
						operator.span..operand.span,
					) as Expression
				},
				power,
			)
		}

	/** A power: grouped from the right, and what is raised to may be negated, as `2 ** -1`. */
	private val power: Parser<Expression> =
		(
			postfix and
				(binary(Operator.Power, firstOf(literal("**"), char('^'))) and unary).optional()
		).map { (base, raised) ->
			raised?.let { (join, exponent) -> join(base, exponent) } ?: base
		}

	private val multiplicative: Parser<Expression> =
		unary.leftAssociative(
			firstOf(
				binary(Operator.Times, char('*') before not(char('*'))),
				binary(Operator.Divide, char('/')),
				binary(Operator.Remainder, char('%')),
			),
		)

	private val additive: Parser<Expression> =
		multiplicative.leftAssociative(
			firstOf(binary(Operator.Plus, char('+')), binary(Operator.Minus, char('-'))),
		)

	private val infix: Parser<Expression> =
		additive.leftAssociative(
			firstOf(
				*listOf(
					Operator.Shl,
					Operator.Shr,
					Operator.Ushr,
					Operator.And,
					Operator.Or,
					Operator.Xor,
				).map { binary(it, word(it.written)) }
					.toTypedArray(),
			),
		)

	private val whole: Parser<Expression> = space after expression

	/** [text] read as an expression, whole: what it says, or where and why it can't be read. */
	fun read(text: String): Parsed<Expression> = whole.parseAll(text)
}
