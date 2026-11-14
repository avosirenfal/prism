package prism.index.query

/*
 * A query's text as it was written: a tree of what each part of it is, every node with its span, so that what is
 * shown can be coloured part by part and a problem pointed at exactly. The grammar below reads any text at all into
 * such a tree: a quote never closed runs to the end, a character no rule expects is a node of its own, so there is
 * always a tree to show and to make what can be made of. What the tree means, and what is wrong with it, is
 * meaning.kt's to say.
 *
 *     query     = space* (term space*)*
 *     term      = "-"? (regex | modifier | words)? junk?
 *     regex     = "r/" source ("/" flags)?
 *     modifier  = letters ":" value?
 *     words     = ("=" | "^")? (quoted | bare)? ("$" before the end of the term)?
 *     quoted    = [rci]* "#"* '"' content ('"' "#"*)?
 *     bare      = (escape | a character but space, and but a "$" that ends the term)+
 *     junk      = what follows a term without a space before it
 *
 * A modifier's value depends on its name: `ext:` takes a regex too (`ext:/jpe?g/i`), and `dir:` a depth after its
 * pattern (`dir:funny:3-5`).
 */

/** A query's text, read into its terms. */
data class QuerySyntax(
	val text: String,
	val terms: List<TermSyntax>,
)

/**
 * One term as written: a [negation] (`-`), what it is ([body]; null for a `-` with nothing after it), and anything
 * written against it without a space ([junk]).
 */
data class TermSyntax(
	val span: Span,
	val negation: Span?,
	val body: BodySyntax?,
	val junk: Span?,
)

/** What a term is. */
sealed interface BodySyntax {
	val span: Span

	/**
	 * A name or a path: words, bare or quoted, anchored by `^` or `=` before them and `$` after; any of the three may be
	 * missing while a term is being typed (`^` alone).
	 */
	data class Words(
		override val span: Span,
		val start: Spanned<Anchor>?,
		val text: TextSyntax?,
		val end: Span?,
	) : BodySyntax

	/** `r/.../flags`. */
	data class Regex(
		override val span: Span,
		val regex: RegexSyntax,
	) : BodySyntax

	/** `name:value`: the [name] as written, and which modifier that is, with the value read as that one reads its own. */
	data class Modifier(
		override val span: Span,
		val name: Spanned<String>,
		val colon: Span,
		val said: ModifierSyntax,
	) : BodySyntax
}

/**
 * Which modifier a name before a colon is, and its value, read the way that modifier's is: each kind holds the only
 * kind of value it can have, so what gives a modifier its meaning never has to ask whether it was given the right
 * one. The value is null where nothing follows the colon yet.
 */
sealed interface ModifierSyntax {
	val value: ValueSyntax?

	/** `dir:`, whose words may have a depth after them. */
	data class Dir(
		override val value: ValueSyntax.Placed?,
	) : ModifierSyntax

	/** `ext:`, which takes words or a regex. */
	data class Ext(
		override val value: ValueSyntax.OfExt?,
	) : ModifierSyntax

	/** A modifier that takes words, and which. */
	data class Worded(
		val of: Kind,
		override val value: ValueSyntax.Words?,
	) : ModifierSyntax {
		enum class Kind {
			Type,
			Is,
			Recent,
			New,
			Mount,
			;

			/** Its name, as it is written before the colon. */
			val word: String get() = name.lowercase()
		}
	}

	/** A name that is no modifier's, with the words after it. */
	data class Unknown(
		override val value: ValueSyntax.Words?,
	) : ModifierSyntax

	companion object {
		const val DIR = "dir"
		const val EXT = "ext"

		/** Every modifier's name. */
		val NAMES: List<String> = listOf(DIR, EXT) + Worded.Kind.entries.map { it.word }
	}
}

/** The anchor before a term's words. */
enum class Anchor {
	/** `^`: at the start. */
	Start,

	/** `=`: the whole of it. */
	Exact,
}

/** Words, as written. */
sealed interface TextSyntax {
	val span: Span

	/** Words without quotes, up to a space, escapes and all (`\[draft\]*`). */
	data class Bare(
		override val span: Span,
		val text: String,
	) : TextSyntax

	/**
	 * Words in quotes, with the [flags] before them (`r`, `c`, `i`) and the [hashes] that let a raw literal hold a
	 * quote (`r#"..."#`). [close] is null for a quote not closed yet, whose [content] runs to the end of the text.
	 */
	data class Quoted(
		override val span: Span,
		val flags: List<Spanned<Char>>,
		val hashes: Span?,
		val open: Span,
		val content: Spanned<String>,
		val close: Span?,
	) : TextSyntax {
		/** Whether nothing in it is special: an `r` flag, or `#`s (which only a raw literal may have, but are read as one). */
		val isRaw: Boolean get() = hashes != null || flags.any { it.value == 'r' }
	}
}

/** A regex: its opening (`r/`, or `/` in a modifier's value), its [source], its closing `/`, and its [flags]. */
data class RegexSyntax(
	val span: Span,
	val open: Span,
	val source: Spanned<String>,
	val close: Span?,
	val flags: Spanned<String>?,
)

/** A modifier's value. */
sealed interface ValueSyntax {
	val span: Span

	/** What `ext:` takes: words, or a regex. */
	sealed interface OfExt : ValueSyntax

	/** Words, bare or quoted. */
	data class Words(
		override val span: Span,
		val text: TextSyntax,
	) : OfExt

	/** `dir:`'s words, with the [depth] after them where there is one. */
	data class Placed(
		override val span: Span,
		val text: TextSyntax,
		val depth: DepthSyntax?,
	) : ValueSyntax

	/** A regex: `ext:/jpe?g/i`. */
	data class Regex(
		override val span: Span,
		val regex: RegexSyntax,
	) : OfExt
}

/** `:3`, `:3-5`, `:2-`: the [colon], and what follows it, which may be nothing yet. */
data class DepthSyntax(
	val span: Span,
	val colon: Span,
	val text: Spanned<String>,
)

/** The search language's grammar, from combinators. */
object QueryGrammar {
	/** [text] read into its terms. Any text reads: what no rule expects is a term's junk, or a term of its own. */
	fun read(text: String): QuerySyntax =
		QuerySyntax(text, terms.parse(text, space.many().parse(text, 0).next).value)

	private val space = charThat { it.isWhitespace() }

	/** Where a term ends: before a space, or at the end of the text. */
	private val termEnd: Parser<Unit> = firstOf(ahead(space), end)

	private val anyChar = charThat { true }

	/** `\` and the character after it, both kept: what a glob reads as that character, itself. */
	private val escape: Parser<String> =
		(char('\\') and anyChar).map { (slash, it) ->
			"$slash$it"
		}

	/** A `\` with nothing after it: kept as it is. */
	private val lastBackslash: Parser<String> = (char('\\') before end).map { "\\" }

	/** A piece of words: an escape, or a character that [ends] does not stop at. */
	private fun chunk(ends: (Char) -> Boolean): Parser<String> =
		firstOf(escape, lastBackslash, charThat { it != '\\' && !ends(it) }.map(Char::toString))

	/** A `$` that ends its term: the end anchor. */
	private val endDollar: Parser<Unit> = (char('$') and termEnd).map { }

	/** A piece of bare words: anything up to a space, but a `$` that ends the term. */
	private val bareChunk: Parser<String> =
		(
			not(endDollar) and
				chunk {
					it.isWhitespace()
				}
		).map { it.second }

	private fun bareOf(pieces: Parser<String>): Parser<TextSyntax> =
		pieces
			.some()
			.concatenated()
			.spanned()
			.map { TextSyntax.Bare(it.span, it.value) }

	private val bare: Parser<TextSyntax> = bareOf(bareChunk)

	/** `dir:`'s depth: `:` and digits and a `-`, at the end of the term (`:3-5`, `:2-`, or `:` alone, unfinished). */
	private val depth: Parser<DepthSyntax> =
		(
			char(':').spanned() and
				charThat { it.isDigit() || it == '-' }.many().joined().spanned() before
				termEnd
		).spanned()
			.map { (parts, span) -> DepthSyntax(span, parts.first.span, parts.second) }

	/** Bare words that stop before a depth. */
	private val dirBare: Parser<TextSyntax> =
		bareOf((not(depth) and bareChunk).map { it.second })

	/** What a quote holds: anything up to [closing], which in a raw literal nothing escapes. */
	private fun contentOf(raw: Boolean, closing: String): Parser<String> =
		if (raw)
			(not(literal(closing)) and anyChar).map { it.second.toString() }
		else
			chunk { it == '"' }

	private val quoted: Parser<TextSyntax> =
		(
			charThat { it in "rci" }.spanned().many() and
				char('#').some().spanned().optional() and
				char('"').spanned()
		).then { (flagsAndHashes, open) ->
			val (flags, hashes) = flagsAndHashes
			val count = hashes?.value?.size ?: 0
			val closing = "\"" + "#".repeat(count)
			val raw = count > 0 || flags.any { it.value == 'r' }
			val content = contentOf(raw, closing).many().concatenated().spanned()
			val close = literal(closing).spanned().optional()

			(content and close).map { (text, closed) ->
				val start = flags.firstOrNull()?.span ?: hashes?.span ?: open.span

				TextSyntax.Quoted(
					Span(start.start, closed?.span?.end ?: text.span.end),
					flags,
					hashes?.span,
					open.span,
					text,
					closed?.span,
				)
			}
		}

	/** A regex after [opening]: its source up to an unescaped `/` (or the end of the text), then its flags. */
	private fun regex(opening: Parser<*>): Parser<RegexSyntax> {
		val source = chunk { it == '/' }.many().concatenated().spanned()
		val flags = charThat { !it.isWhitespace() }.many().joined().spanned()

		val closing = char('/').spanned().optional()

		return (opening.spanned() and source and closing).then { (openAndSource, close) ->
			val (open, read) = openAndSource
			// Flags only after the closing `/`: a regex not closed runs to the end, and has none.
			val after = if (close != null) flags else pure(null)

			after.map {
				val end = it?.span?.end ?: close?.span?.end ?: read.span.end

				RegexSyntax(Span(open.span.start, end), open.span, read, close?.span, it)
			}
		}
	}

	private val regexTerm: Parser<BodySyntax> =
		regex(literal("r/")).map {
			BodySyntax.Regex(it.span, it)
		}

	private val anchor: Parser<Spanned<Anchor>> =
		firstOf(char('=').map { Anchor.Exact }, char('^').map { Anchor.Start }).spanned()

	private val endAnchor: Parser<Span> =
		(
			char(
				'$',
			).spanned() before termEnd
		).map { it.span }

	private val texts: Parser<TextSyntax> = firstOf(quoted, bare)

	/** A name or path term's parts: an anchor before, words, and an anchor after, any of which may be missing yet. */
	private val wordParts = anchor.optional() and texts.optional() and endAnchor.optional()

	private val words: Parser<BodySyntax> =
		wordParts.then { (startAndText, last) ->
			val (start, text) = startAndText
			val parts = listOfNotNull(start?.span, text?.span, last)

			if (parts.isEmpty())
				missed()
			else
				pure(BodySyntax.Words(Span(parts.first().start, parts.last().end), start, text, last))
		}

	/** Which modifier [name] is, with its value, read as that modifier's is; no value where none follows. */
	private fun saidOf(name: String): Parser<ModifierSyntax> {
		val words = firstOf(quoted, bare).map { ValueSyntax.Words(it.span, it) }
		val lowered = name.lowercase()

		return when (lowered) {
			ModifierSyntax.EXT ->
				firstOf<ValueSyntax.OfExt>(
					regex(char('/')).map { ValueSyntax.Regex(it.span, it) },
					words,
				).optional()
					.map(ModifierSyntax::Ext)
			ModifierSyntax.DIR ->
				(firstOf(quoted, dirBare) and depth.optional())
					.map { (words, depth) ->
						ValueSyntax.Placed(
							Span(words.span.start, depth?.span?.end ?: words.span.end),
							words,
							depth,
						)
					}.optional()
					.map(ModifierSyntax::Dir)
			else ->
				words.optional().map { value ->
					ModifierSyntax.Worded.Kind.entries
						.firstOrNull { it.word == lowered }
						?.let { ModifierSyntax.Worded(it, value) }
						?: ModifierSyntax.Unknown(value)
				}
		}
	}

	private val modifierName: Parser<Spanned<String>> =
		charThat {
			it.isLetter()
		}.some().joined().spanned()

	private val modifier: Parser<BodySyntax> =
		(modifierName and char(':').spanned()).then { (name, colon) ->
			saidOf(name.value).map { said ->
				val end = said.value?.span?.end ?: colon.span.end

				BodySyntax.Modifier(Span(name.span.start, end), name, colon.span, said)
			}
		}

	private val junk: Parser<Span> =
		charThat {
			!it.isWhitespace()
		}.some().spanned().map { it.span }

	private val body: Parser<BodySyntax> = firstOf(regexTerm, modifier, words)

	private val term: Parser<TermSyntax> =
		(
			char(
				'-',
			).spanned().optional() and body.optional() and junk.optional()
		).spanned().then { (parts, span) ->
			val (negationAndBody, junk) = parts
			val (negation, body) = negationAndBody

			if (span.isEmpty)
				missed()
			else
				pure(TermSyntax(span, negation?.span, body, junk))
		}

	/** A query's terms, from its first: as many as there are, none included, so any text is read. */
	private val terms: Always<List<TermSyntax>> = (term before space.many()).many()
}
