package barion.index.query

/**
 * A glob: a pattern over the whole of a name, a stem, an extension or one segment of a path.
 *
 * - `*` is any run of characters, `?` is one character;
 * - `[abc]`, `[a-z]` and `[0-5]` are one character of a class, and `[!a-z]` (or `[^a-z]`) one character not of it;
 * - `{a,b}` is one of several globs, which may hold globs of their own (`*.{jp{,e}g,png}`);
 * - `\` makes the character after it itself, special or not.
 *
 * A glob is made by [parse], so a glob that is held is one that was valid as written.
 */
data class Glob(
	val parts: List<Part>,
) {
	sealed interface Part {
		/** Characters as themselves. */
		data class Text(
			val text: String,
		) : Part {
			init {
				require(text.isNotEmpty()) { "a glob's text is at least one character" }
			}
		}

		/** `?`: one character. */
		data object One : Part

		/** `*`: any run of characters, none included. */
		data object Run : Part

		/** `[...]`: one character of [spans], or with [negated], one character of none of them. */
		data class Class(
			val spans: List<Span>,
			val negated: Boolean,
		) : Part {
			init {
				require(spans.isNotEmpty()) { "a class holds at least one character" }
			}

			fun has(codePoint: Int): Boolean =
				spans.any { codePoint in it.first..it.last } != negated
		}

		/** `{a,b}`: one of [alternatives]. */
		data class Choice(
			val alternatives: List<Glob>,
		) : Part {
			init {
				require(alternatives.isNotEmpty()) { "a choice has at least one alternative" }
			}
		}
	}

	/** The code points from [first] to [last], both included: `a-z`, or one character alone. */
	data class Span(
		val first: Int,
		val last: Int,
	) {
		init {
			require(first <= last) { "a range runs from its lower end to its higher" }
		}
	}

	/**
	 * Every way of reading this glob without a choice in it, each a run of parts: `*.{jpg,png}` is `*.jpg` and
	 * `*.png`. A choice in a choice is read out too.
	 */
	val readings: List<List<Part>> by lazy {
		parts.fold(listOf(emptyList())) { readings, part ->
			when (part) {
				is Part.Choice ->
					readings.flatMap { before ->
						part.alternatives.flatMap { alternative ->
							alternative.readings.map { before + it }
						}
					}
				else -> readings.map { it + part }
			}
		}
	}

	override fun toString(): String = parts.joinToString("", transform = ::written)

	companion object {
		/** The characters that make a word a glob, unless `\` comes before them. */
		private const val SPECIAL = "*?[{"

		/** [text] as a glob. Refused, with why, where it is not one: a class or a choice never closed, a `\` at the end. */
		fun parse(text: String): Glob = Parser(text).whole()

		/** Whether [text] has a character that makes it a glob (`*`, `?`, `[`, `{`) that no `\` comes before. */
		fun isGlob(text: String): Boolean {
			var at = 0

			while (at < text.length) {
				when (text[at]) {
					'\\' -> at++
					in SPECIAL -> return true
				}
				at++
			}

			return false
		}

		/** [text] with its escapes taken out: what it says, a `\x` being `x`. */
		fun unescaped(text: String): String =
			buildString {
				var at = 0

				while (at < text.length) {
					if (text[at] == '\\' && at + 1 < text.length)
						at++
					append(text[at])
					at++
				}
			}

		private fun written(part: Part): String =
			when (part) {
				is Part.Text -> part.text.replace(Regex("[\\\\*?\\[\\]{},]")) { "\\" + it.value }
				Part.One -> "?"
				Part.Run -> "*"
				is Part.Class ->
					part.spans.joinToString(
						"",
						if (part.negated) "[!" else "[",
						"]",
					) {
						if (it.first == it.last)
							Character.toString(it.first)
						else
							Character.toString(it.first) + "-" + Character.toString(it.last)
					}
				is Part.Choice -> part.alternatives.joinToString(",", "{", "}")
			}
	}

	/** Reads a glob's text, a code point at a time. */
	private class Parser(
		private val text: String,
	) {
		private var at = 0

		fun whole(): Glob {
			val glob = glob(inChoice = false)

			require(at == text.length) { "“$text” has a “${text[at]}” that nothing opened" }

			return glob
		}

		/** A glob up to the end, or, in a choice, up to the `,` or `}` that ends it. */
		private fun glob(inChoice: Boolean): Glob {
			val parts = ArrayList<Part>()
			val literal = StringBuilder()

			fun add(part: Part) {
				if (literal.isNotEmpty())
					parts += Part.Text(literal.toString())
				literal.clear()

				// `**` in a name is `*`: a run of runs is one run.
				if (!(part == Part.Run && parts.lastOrNull() == Part.Run))
					parts += part
			}

			while (at < text.length) {
				val c = text[at]

				when {
					inChoice && (c == ',' || c == '}') -> break
					c == '\\' -> {
						require(at + 1 < text.length) { "“$text” ends with a “\\” that escapes nothing" }
						literal.appendCodePoint(text.codePointAt(at + 1))
						at += 1 + Character.charCount(text.codePointAt(at + 1))
					}
					c == '*' -> {
						add(Part.Run)
						at++
					}
					c == '?' -> {
						add(Part.One)
						at++
					}
					c == '[' -> add(spanClass())
					c == '{' -> add(choice())
					else -> {
						literal.appendCodePoint(text.codePointAt(at))
						at += Character.charCount(text.codePointAt(at))
					}
				}
			}

			if (literal.isNotEmpty())
				parts += Part.Text(literal.toString())

			return Glob(parts)
		}

		private fun choice(): Part.Choice {
			val opened = at

			at++

			val alternatives = ArrayList<Glob>()

			while (true) {
				alternatives += glob(inChoice = true)
				require(
					at < text.length,
				) { "“$text” has a “{” at ${opened + 1} that is never closed" }
				if (text[at++] == '}')
					return Part.Choice(alternatives)
			}
		}

		private fun spanClass(): Part.Class {
			val opened = at

			at++

			val negated = at < text.length && (text[at] == '!' || text[at] == '^')

			if (negated)
				at++

			val spans = ArrayList<Span>()

			// A `]` first is one of the class, not its end: `[]a]`.
			while (at < text.length && (text[at] != ']' || spans.isEmpty())) {
				val first = codePoint()

				if (at + 1 < text.length && text[at] == '-' && text[at + 1] != ']') {
					at++
					spans += Span(first, codePoint())
				} else {
					spans += Span(first, first)
				}
			}
			require(at < text.length) { "“$text” has a “[” at ${opened + 1} that is never closed" }
			at++

			return Part.Class(spans, negated)
		}

		/** One character of a class, which `\` may escape. */
		private fun codePoint(): Int {
			if (text[at] == '\\' && at + 1 < text.length)
				at++

			val codePoint = text.codePointAt(at)

			at += Character.charCount(codePoint)

			return codePoint
		}
	}
}
