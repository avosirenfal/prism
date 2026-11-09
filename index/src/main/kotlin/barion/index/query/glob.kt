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
			val spans: List<Chars>,
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
	data class Chars(
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

	/**
	 * A glob as [read] made it; where in its text a class or a choice was opened that it closed at the end; and where
	 * a range ran backwards (`z-a`), which it read the right way round.
	 */
	data class Reading(
		val glob: Glob,
		val unclosed: List<Int>,
		val backwards: List<Range>,
	)

	/** Where something is in a glob's text: from [start] up to [end], [end] not included. */
	data class Range(
		val start: Int,
		val end: Int,
	)

	companion object {
		/** The characters that make a word a glob, unless `\` comes before them. */
		private const val SPECIAL = "*?[{"

		/** [text] as a glob. Refused, with why, where it is not one: a class or a choice never closed, a `\` at the end. */
		fun parse(text: String): Glob = Parser(text, lenient = false).whole()

		/**
		 * [text] as a glob, however it ends: a class or a choice never closed is closed at the end (a class with
		 * nothing in it yet is left out), and a `\` at the end is itself. What the reading had to close is said, by
		 * where each was opened: how a glob still being typed is searched for as far as it goes.
		 */
		fun read(text: String): Reading =
			Parser(text, lenient = true).let {
				Reading(it.whole(), it.unclosed.toList(), it.backwards.toList())
			}

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
		private val lenient: Boolean,
	) {
		private var at = 0

		/** Where the classes and choices closed at the end, leniently, were opened. */
		val unclosed = ArrayList<Int>()

		/** The ranges that ran backwards (`[z-a]`), read leniently the right way round. */
		val backwards = ArrayList<Range>()

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
					c == '\\' && at + 1 == text.length -> {
						require(lenient) { "“$text” ends with a “\\” that escapes nothing" }
						literal.append('\\')
						at++
					}
					c == '\\' -> {
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
					c == '[' -> spanClass()?.let(::add)
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
				if (at == text.length) {
					require(lenient) { "“$text” has a “{” at ${opened + 1} that is never closed" }
					unclosed += opened

					return Part.Choice(alternatives)
				}
				if (text[at++] == '}')
					return Part.Choice(alternatives)
			}
		}

		/** A class; null for one never closed that has nothing in it yet, which a lenient reading leaves out. */
		private fun spanClass(): Part.Class? {
			val opened = at

			at++

			val negated = at < text.length && (text[at] == '!' || text[at] == '^')

			if (negated)
				at++

			val spans = ArrayList<Chars>()

			// A `]` first is one of the class, not its end: `[]a]`.
			while (at < text.length && (text[at] != ']' || spans.isEmpty())) {
				val from = at
				val first = codePoint()

				if (at + 1 < text.length && text[at] == '-' && text[at + 1] != ']') {
					at++

					val last = codePoint()

					if (last < first) {
						require(lenient) { "“$text” has a range at ${from + 1} that runs backwards" }
						backwards += Range(from, at)
					}
					spans += Chars(minOf(first, last), maxOf(first, last))
				} else {
					spans += Chars(first, first)
				}
			}
			if (at == text.length) {
				require(lenient) { "“$text” has a “[” at ${opened + 1} that is never closed" }
				unclosed += opened

				return if (spans.isEmpty())
					null
				else
					Part.Class(spans, negated)
			}
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
