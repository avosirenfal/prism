package barion.index.query

/*
 * How a query's text is coloured: each part of it marked with the role it plays, from its syntax. A view draws the
 * text with a colour a role, and then the problems over it (meaning.kt), so a part can be both what it is (a flag) and
 * wrong (a flag that says the opposite of another).
 */

/** What a part of a query's text is, for it to be drawn so. */
enum class Role {
	/** `-` before a term. */
	Negation,

	/** `^`, `=` and `$`. */
	Anchor,

	/** A name term's words. */
	Word,

	/** A glob's `*`, `?`, `[...]`, `{`, `,` and `}`. */
	Wildcard,

	/** `\` and the character it escapes. */
	Escape,

	/** Quotes, and a raw literal's `#`s. */
	Quote,

	/** Flags: `r`, `c` and `i` before a quote, and a regex's after it. */
	Flag,

	/** A path's `/`. */
	Slash,

	/** `~` and `~name`: a place the user has. */
	Place,

	/** A regex's source. */
	Regex,

	/** A regex's `r/`, `/` and closing `/`. */
	RegexDelimiter,

	/** A modifier's name. */
	Modifier,

	/** The `:` after a modifier's name. */
	Colon,

	/** A modifier's value. */
	Value,

	/** `dir:`'s depth, and the `:` before it. */
	Depth,

	/** What is written against a term without a space. */
	Junk,
}

/** A part of a query's text, and its [role]. */
data class Mark(
	val span: Span,
	val role: Role,
)

/** The marks of a query's text: in order, apart, adjacent parts of one role joined, spaces unmarked. */
object Marks {
	fun of(syntax: QuerySyntax): List<Mark> =
		joined(
			syntax.terms
				.flatMap(::term)
				.filterNot {
					it.span.isEmpty
				}.sortedBy { it.span.start },
		)

	private fun joined(marks: List<Mark>): List<Mark> =
		marks.fold(listOf()) { joined, mark ->
			val last = joined.lastOrNull()

			if (last != null && last.role == mark.role && last.span.end == mark.span.start)
				joined.dropLast(1) + Mark(Span(last.span.start, mark.span.end), mark.role)
			else
				joined + mark
		}

	private fun term(term: TermSyntax): List<Mark> =
		listOfNotNull(
			term.negation?.let {
				Mark(it, Role.Negation)
			},
			term.junk?.let { Mark(it, Role.Junk) },
		) +
			when (val body = term.body) {
				null -> emptyList()
				is BodySyntax.Words ->
					listOfNotNull(
						body.start?.let {
							Mark(it.span, Role.Anchor)
						},
						body.end?.let { Mark(it, Role.Anchor) },
					) +
						(body.text?.let { text(it, Role.Word) } ?: emptyList())
				is BodySyntax.Regex -> regex(body.regex)
				is BodySyntax.Modifier ->
					listOf(Mark(body.name.span, Role.Modifier), Mark(body.colon, Role.Colon)) +
						when (val value = body.value) {
							null -> emptyList()
							is ValueSyntax.Regex -> regex(value.regex)
							is ValueSyntax.Words ->
								text(value.text, Role.Value) +
									listOfNotNull(value.depth?.let { Mark(it.span, Role.Depth) })
						}
			}

	private fun regex(regex: RegexSyntax): List<Mark> =
		listOfNotNull(
			Mark(regex.open, Role.RegexDelimiter),
			Mark(regex.source.span, Role.Regex),
			regex.close?.let { Mark(it, Role.RegexDelimiter) },
			regex.flags?.let { Mark(it.span, Role.Flag) },
		)

	private fun text(text: TextSyntax, role: Role): List<Mark> =
		when (text) {
			is TextSyntax.Bare -> words(text.text, text.span.start, raw = false, role)
			is TextSyntax.Quoted ->
				text.flags.map { Mark(it.span, Role.Flag) } +
					listOfNotNull(
						text.hashes?.let { Mark(it, Role.Quote) },
						Mark(text.open, Role.Quote),
					) +
					words(text.content.value, text.content.span.start, text.isRaw, role) +
					listOfNotNull(text.close?.let { Mark(it, Role.Quote) })
		}

	/** The characters of [words], from [at]: each glob character, escape, slash and place marked as such, the rest [role]. */
	private fun words(words: String, at: Int, raw: Boolean, role: Role): List<Mark> {
		if (raw)
			return listOf(Mark(Span(at, at + words.length), role))

		val path = words.startsWith("~") || PathPatterns.slashes(words).isNotEmpty()

		val place =
			if (path &&
				words.startsWith("~")
			)
				words.indexOf('/').let {
					if (it <
						0
					) words.length else it
				}
			else
				0

		val marks = ArrayList<Mark>()
		var index = 0
		var braces = 0

		fun mark(length: Int, marked: Role) {
			marks += Mark(Span(at + index, at + index + length), marked)
			index += length
		}

		if (place > 0)
			mark(place, Role.Place)
		while (index < words.length) {
			val c = words[index]

			when {
				c == '\\' -> mark(minOf(2, words.length - index), Role.Escape)
				c == '*' || c == '?' -> mark(1, Role.Wildcard)
				c == '[' -> mark(classLength(words, index), Role.Wildcard)
				c == '{' -> {
					braces++
					mark(1, Role.Wildcard)
				}
				c == '}' && braces > 0 -> {
					braces--
					mark(1, Role.Wildcard)
				}
				c == ',' && braces > 0 -> mark(1, Role.Wildcard)
				c == '/' && path -> mark(1, Role.Slash)
				else -> mark(1, role)
			}
		}

		return marks
	}

	/** How long the class that starts at [from] is, up to its `]` (a `]` first being one of it), or to the end. */
	private fun classLength(words: String, from: Int): Int {
		var at = from + 1

		if (at < words.length && (words[at] == '!' || words[at] == '^'))
			at++
		if (at < words.length && words[at] == ']')
			at++
		while (at < words.length && words[at] != ']')
			at +=
				if (words[at] == '\\' && at + 1 < words.length)
					2
				else
					1

		return minOf(words.length, at + 1) - from
	}
}
