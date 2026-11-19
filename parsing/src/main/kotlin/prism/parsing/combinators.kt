package prism.parsing

/*
 * Parser combinators: a parser is a function from a text and a position to a value and the position after it, or to
 * where it could go no further and what it would have taken there; and a grammar is parsers made out of smaller ones.
 * Choice is ordered, as in a PEG: [firstOf] takes the first alternative that matches, and backtracks to try the next,
 * which the few dozen characters of what is typed into a field make free.
 *
 * What goes wrong is said where it went wrong. Every miss says where it was ([Step.Missed.at]) and what would have been
 * taken there ([Step.Missed.expected], as [named] names it). And a parser that matched keeps the deepest miss it met on
 * the way ([Step.Took.furthest]): `1+` read as an expression takes `1`, having tried a number after the `+` and found
 * none, so what follows (the end of the text, which is not there) is not the only thing to be said wrong. Of two misses
 * the one further into the text is said, since it got further; of two as far, both are.
 */

/** Where in a text something is: from [start] up to [end], [end] not included. */
data class Span(
	val start: Int,
	val end: Int,
) {
	init {
		require(start in 0..end) { "a span runs forward from where it starts" }
	}

	val isEmpty: Boolean get() = start == end

	/** From where this starts to where [other] ends. */
	operator fun rangeTo(other: Span): Span = Span(start, maxOf(end, other.end))

	override fun toString(): String = "$start..$end"
}

/** A [value], and the [span] of the text it was made from. */
data class Spanned<out T>(
	val value: T,
	val span: Span,
)

/** What a parser made of the text from a position. */
sealed interface Step<out T> {
	/** It matched: [value], made from the text up to [next]; the deepest miss on the way, where there was one past it. */
	data class Took<out T>(
		val value: T,
		val next: Int,
		val furthest: Missed? = null,
	) : Step<T>

	/** It did not match: it could go no further than [at], where it would have taken what [expected] names. */
	data class Missed(
		val at: Int,
		val expected: Set<String> = emptySet(),
	) : Step<Nothing> {
		/** Of this and [other], the one that got further; both said, where they got as far. */
		infix fun or(other: Missed?): Missed =
			when {
				other == null || other.at < at -> this
				other.at > at -> other
				else -> Missed(at, expected + other.expected)
			}
	}
}

/** The deeper of two misses, either of which may be none. */
private fun deeper(one: Step.Missed?, other: Step.Missed?): Step.Missed? =
	one?.or(other) ?: other

/** [missed] said with what was met on the way to it. */
private fun Step.Missed.after(met: Step.Missed?): Step.Missed = this or met

/** [took] with [met] kept among what it met, where it got further than where it stopped. */
private fun <T> Step.Took<T>.meeting(met: Step.Missed?): Step.Took<T> =
	deeper(furthest, met)?.takeIf { it.at >= next }.let { copy(furthest = it) }

/** Something to read from a text at a position: a value and where it stopped, or where it could go no further. */
fun interface Parser<out T> {
	fun parse(text: String, at: Int): Step<T>
}

/** A parser that takes something wherever it is asked, if only nothing: what it gives is never [Step.Missed]. */
fun interface Always<out T> : Parser<T> {
	override fun parse(text: String, at: Int): Step.Took<T>
}

/** What reading a whole text came to. */
sealed interface Parsed<out T> {
	/** All of it read, as [value]. */
	data class Read<out T>(
		val value: T,
	) : Parsed<T>

	/** It could not be: it went wrong [at], where what [expected] names would have been taken. */
	data class Wrong(
		val at: Int,
		val expected: Set<String>,
	) : Parsed<Nothing> {
		/** As it is said: "expected a number or “(”". */
		val said: String
			get() =
				if (expected.isEmpty())
					"this can't be read here"
				else
					"expected ${expected.sorted().let { names ->
						if (names.size == 1)
							names.single()
						else
							names.dropLast(1).joinToString(", ") + " or " + names.last()
					}}"
	}
}

/** [text] read whole by this parser: its value, or where and why it could not be. */
fun <T> Parser<T>.parseAll(text: String): Parsed<T> =
	when (val step = (this before end).parse(text, 0)) {
		is Step.Took -> Parsed.Read(step.value)
		is Step.Missed -> Parsed.Wrong(step.at, step.expected)
	}

/** A character [test] passes. */
fun charThat(test: (Char) -> Boolean): Parser<Char> =
	Parser { text, at ->
		if (at < text.length &&
			test(text[at])
		)
			Step.Took(text[at], at + 1)
		else
			Step.Missed(at)
	}

fun char(c: Char): Parser<Char> = charThat { it == c } named "“$c”"

/** [word], as it is. */
fun literal(word: String): Parser<String> =
	Parser { text, at ->
		if (text.startsWith(word, at))
			Step.Took(word, at + word.length)
		else
			Step.Missed(at, setOf("“$word”"))
	}

/** The end of the text. */
val end: Parser<Unit> =
	Parser { text, at ->
		if (at == text.length)
			Step.Took(Unit, at)
		else
			Step.Missed(at, setOf("the end"))
	}

/** [value], reading nothing. */
fun <T> pure(value: T): Always<T> = Always { _, at -> Step.Took(value, at) }

/** Nothing matches. */
fun <T> missed(): Parser<T> = Parser { _, at -> Step.Missed(at) }

/**
 * This parser, said as [name] where it does not match where it began: "a number", in place of every character a
 * number could begin with. Where it got further before missing, what it missed there is said, which says more.
 */
infix fun <T> Parser<T>.named(name: String): Parser<T> =
	Parser { text, at ->
		when (val step = parse(text, at)) {
			is Step.Took -> step
			is Step.Missed ->
				if (step.at == at)
					Step.Missed(at, setOf(name))
				else
					step
		}
	}

/** What this parser makes, made into something else by [make]. */
fun <T, R> Parser<T>.map(make: (T) -> R): Parser<R> =
	Parser { text, at ->
		when (val step = parse(text, at)) {
			is Step.Took -> Step.Took(make(step.value), step.next, step.furthest)
			is Step.Missed -> step
		}
	}

/** This parser, then the parser [next] makes of what it made: a grammar that depends on what came before. */
fun <T, R> Parser<T>.then(next: (T) -> Parser<R>): Parser<R> =
	Parser { text, at ->
		when (val step = parse(text, at)) {
			is Step.Took ->
				when (val after = next(step.value).parse(text, step.next)) {
					is Step.Took -> after.meeting(step.furthest)
					is Step.Missed -> after.after(step.furthest)
				}
			is Step.Missed -> step
		}
	}

/** This parser, then [other]: both values. */
infix fun <A, B> Parser<A>.and(other: Parser<B>): Parser<Pair<A, B>> =
	then { a ->
		other.map { b ->
			a to
				b
		}
	}

/** This parser, then [other], whose value is dropped. */
infix fun <A> Parser<A>.before(other: Parser<*>): Parser<A> =
	then { a -> other.map { a } }

/** This parser, whose value is dropped, then [other]. */
infix fun <B> Parser<*>.after(other: Parser<B>): Parser<B> = then { other }

/** The first of [alternatives] that matches; where none does, what each of them missed. */
fun <T> firstOf(vararg alternatives: Parser<T>): Parser<T> =
	Parser { text, at ->
		alternatives.fold<Parser<T>, Step<T>>(Step.Missed(at)) { before, alternative ->
			when (before) {
				is Step.Took -> before
				is Step.Missed ->
					when (val step = alternative.parse(text, at)) {
						is Step.Took -> step.meeting(before)
						is Step.Missed -> step or before
					}
			}
		}
	}

/** This parser's value where it matches, and null, reading nothing, where it does not. */
fun <T> Parser<T>.optional(): Always<T?> =
	Always { text, at ->
		when (val step = parse(text, at)) {
			is Step.Took -> step
			is Step.Missed -> Step.Took(null, at, step)
		}
	}

/**
 * This parser as many times as it matches in a row, none included; it stops where one matches without reading. The
 * one that did not match is kept as what was met, since it may be what was meant: `1+2+` stopped before the last `+`.
 */
fun <T> Parser<T>.many(): Always<List<T>> =
	Always { text, start ->
		val values = ArrayList<T>()
		var at = start
		var met: Step.Missed? = null

		while (true) {
			when (val step = parse(text, at)) {
				is Step.Missed -> {
					met = deeper(met, step)
					break
				}
				is Step.Took -> {
					met = deeper(met, step.furthest)
					if (step.next == at)
						break
					values += step.value
					at = step.next
				}
			}
		}

		Step.Took(values, at).meeting(met)
	}

/** This parser as many times as it matches in a row, at least once. */
fun <T> Parser<T>.some(): Parser<List<T>> =
	(this and many()).map { (first, rest) ->
		listOf(first) +
			rest
	}

/** This parser at least once, with [separator] between each and the next. */
fun <T> Parser<T>.separatedBy(separator: Parser<*>): Parser<List<T>> =
	(this and (separator after this).many()).map { (first, rest) -> listOf(first) + rest }

/** This parser's value, with the span of what it read. */
fun <T> Parser<T>.spanned(): Parser<Spanned<T>> =
	Parser { text, at ->
		when (val step = parse(text, at)) {
			is Step.Took ->
				Step.Took(
					Spanned(step.value, Span(at, step.next)),
					step.next,
					step.furthest,
				)
			is Step.Missed -> step
		}
	}

/** Matches, reading nothing, where [parser] does not match. */
fun not(parser: Parser<*>): Parser<Unit> =
	Parser { text, at ->
		if (parser.parse(text, at) is Step.Took)
			Step.Missed(at)
		else
			Step.Took(Unit, at)
	}

/** Matches, reading nothing, where [parser] matches. */
fun ahead(parser: Parser<*>): Parser<Unit> =
	Parser { text, at ->
		when (val step = parser.parse(text, at)) {
			is Step.Took -> Step.Took(Unit, at)
			is Step.Missed -> step
		}
	}

/** The parser [make] makes, made the first time it is asked to read: what a grammar that holds itself is written with. */
fun <T> deferred(make: () -> Parser<T>): Parser<T> {
	val made by lazy(make)

	return Parser { text, at -> made.parse(text, at) }
}

/**
 * Operands of this parser joined by [operator], grouped from the left (`1-2-3` is `(1-2)-3`): each operator what
 * makes one value of the two either side of it.
 */
fun <T> Parser<T>.leftAssociative(operator: Parser<(T, T) -> T>): Parser<T> =
	(this and (operator and this).many()).map { (first, rest) ->
		rest.fold(first) { left, (join, right) -> join(left, right) }
	}

/**
 * Operands of this parser joined by [operator], grouped from the right (`2**3**2` is `2**(3**2)`): each operator what
 * makes one value of the two either side of it.
 */
fun <T> Parser<T>.rightAssociative(operator: Parser<(T, T) -> T>): Parser<T> =
	(this and (operator and this).many()).map { (first, rest) ->
		if (rest.isEmpty()) {
			first
		} else {
			val operands = listOf(first) + rest.map { it.second }
			val joins = rest.map { it.first }

			operands
				.dropLast(
					1,
				).zip(joins)
				.foldRight(operands.last()) { (left, join), right -> join(left, right) }
		}
	}

/** Characters, as the text they make. */
fun Parser<List<Char>>.joined(): Parser<String> = map { it.joinToString("") }

/** Pieces of text, as the text they make. */
fun Parser<List<String>>.concatenated(): Parser<String> = map { it.joinToString("") }
