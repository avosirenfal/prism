package prism.index.query

/*
 * Parser combinators: a parser is a function from a text and a position to a value and the position after it (or to
 * nothing, where it does not match there), and a grammar is parsers made out of smaller ones. Choice is ordered, as in
 * a PEG: [firstOf] takes the first alternative that matches, and backtracks to try the next, which a query's few
 * dozen characters make free.
 *
 * Nothing here knows the search language; its grammar is in syntax.kt.
 */

/** Where in a query's text something is: from [start] up to [end], [end] not included. */
data class Span(
	val start: Int,
	val end: Int,
) {
	init {
		require(start in 0..end) { "a span runs forward from where it starts" }
	}

	val isEmpty: Boolean get() = start == end

	override fun toString(): String = "$start..$end"
}

/** A [value], and the [span] of the text it was made from. */
data class Spanned<out T>(
	val value: T,
	val span: Span,
)

/** What a parser made of the text from a position. */
sealed interface Step<out T> {
	/** It matched: [value], made from the text up to [next]. */
	data class Took<out T>(
		val value: T,
		val next: Int,
	) : Step<T>

	/** It did not match there. */
	data object Missed : Step<Nothing>
}

/** Something to read from [text] at a position: a value and where it stopped, or [Step.Missed]. */
fun interface Parser<out T> {
	fun parse(text: String, at: Int): Step<T>
}

/** A parser that takes something wherever it is asked, if only nothing: what it gives is never [Step.Missed]. */
fun interface Always<out T> : Parser<T> {
	override fun parse(text: String, at: Int): Step.Took<T>
}

/** A character [test] passes. */
fun charThat(test: (Char) -> Boolean): Parser<Char> =
	Parser { text, at ->
		if (at < text.length &&
			test(text[at])
		)
			Step.Took(text[at], at + 1)
		else
			Step.Missed
	}

fun char(c: Char): Parser<Char> = charThat { it == c }

/** [word], as it is. */
fun literal(word: String): Parser<String> =
	Parser {
		text,
		at,
		->
		if (text.startsWith(word, at))
			Step.Took(word, at + word.length)
		else
			Step.Missed
	}

/** The end of the text. */
val end: Parser<Unit> =
	Parser { text, at ->
		if (at ==
			text.length
		)
			Step.Took(Unit, at)
		else
			Step.Missed
	}

/** [value], reading nothing. */
fun <T> pure(value: T): Parser<T> = Parser { _, at -> Step.Took(value, at) }

/** What this parser makes, made into something else by [make]. */
fun <T, R> Parser<T>.map(make: (T) -> R): Parser<R> =
	Parser { text, at ->
		when (val step = parse(text, at)) {
			is Step.Took -> Step.Took(make(step.value), step.next)
			Step.Missed -> Step.Missed
		}
	}

/** This parser, then the parser [next] makes of what it made: a grammar that depends on what came before. */
fun <T, R> Parser<T>.then(next: (T) -> Parser<R>): Parser<R> =
	Parser { text, at ->
		when (val step = parse(text, at)) {
			is Step.Took -> next(step.value).parse(text, step.next)
			Step.Missed -> Step.Missed
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

/** The first of [alternatives] that matches. */
fun <T> firstOf(vararg alternatives: Parser<T>): Parser<T> =
	Parser { text, at ->
		alternatives.firstNotNullOfOrNull { it.parse(text, at) as? Step.Took } ?: Step.Missed
	}

/** This parser's value where it matches, and null, reading nothing, where it does not. */
fun <T> Parser<T>.optional(): Parser<T?> = firstOf(this, pure(null))

/** This parser as many times as it matches in a row, none included; it stops where one matches without reading. */
fun <T> Parser<T>.many(): Always<List<T>> =
	Always { text, start ->
		val values = ArrayList<T>()
		var at = start

		while (true) {
			val step = parse(text, at) as? Step.Took ?: break

			if (step.next == at)
				break
			values += step.value
			at = step.next
		}

		Step.Took(values, at)
	}

/** This parser as many times as it matches in a row, at least once. */
fun <T> Parser<T>.some(): Parser<List<T>> =
	many().then {
		if (it.isEmpty()) missed() else pure(it)
	}

/** Nothing matches. */
fun <T> missed(): Parser<T> = Parser { _, _ -> Step.Missed }

/** This parser's value, with the span of what it read. */
fun <T> Parser<T>.spanned(): Parser<Spanned<T>> =
	Parser { text, at ->
		when (val step = parse(text, at)) {
			is Step.Took -> Step.Took(Spanned(step.value, Span(at, step.next)), step.next)
			Step.Missed -> Step.Missed
		}
	}

/** Matches, reading nothing, where [parser] does not match. */
fun not(parser: Parser<*>): Parser<Unit> =
	Parser {
		text,
		at,
		->
		if (parser.parse(text, at) is Step.Took)
			Step.Missed
		else
			Step.Took(Unit, at)
	}

/** Matches, reading nothing, where [parser] matches. */
fun ahead(parser: Parser<*>): Parser<Unit> =
	Parser {
		text,
		at,
		->
		if (parser.parse(text, at) is Step.Took)
			Step.Took(Unit, at)
		else
			Step.Missed
	}

/** Characters, as the text they make. */
fun Parser<List<Char>>.joined(): Parser<String> = map { it.joinToString("") }

/** Pieces of text, as the text they make. */
fun Parser<List<String>>.concatenated(): Parser<String> = map { it.joinToString("") }
