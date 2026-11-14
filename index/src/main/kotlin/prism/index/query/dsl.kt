package prism.index.query

/*
 * The Kotlin DSL: queries written in Kotlin, term by term, as the search language writes them in text. Each function
 * makes the test its term would parse to, so a query built here is the query its text will parse to:
 *
 *     query {
 *         +word("report")                 // report
 *         +word("report", start = true)   // ^report
 *         +exactly("thing")               // =thing
 *         +raw("Img_*", Case.Sensitive)   // cr"Img_*"
 *         +path("funny/rep")              // funny/rep
 *         +dir("funny", Depth.exactly(1)) // dir:funny:1
 *         -ext("jpg")                     // -ext:jpg
 *     }
 *
 * `+` adds a term; `-` adds it negated. What each word, glob or path means is decided here, by the same functions the
 * parser will use ([TextPattern.of], [NamePart.of], [PathPatterns.parse]), so it is decided in one place.
 */

/** A query of the terms [build] adds. */
fun query(build: QueryBuilder.() -> Unit): Query = QueryBuilder().apply(build).built()

/** What [query] builds a query with: the terms, in order, and the functions that make them. */
class QueryBuilder internal constructor() {
	private val terms = mutableListOf<Term>()

	internal fun built() = Query(terms.toList())

	/** [this] as a term. */
	operator fun Test.unaryPlus() {
		terms += Term(this)
	}

	/** [this], negated, as a term: `-ext:jpg`. */
	operator fun Test.unaryMinus() {
		terms += Term(this, negated = true)
	}

	/**
	 * A bare word, or a quoted one (`"tax return"`): found in the stem as a substring, or with a glob character, a glob
	 * over the whole stem; over the whole name instead where a `.` follows its first character. `\` escapes.
	 * [start] is `^` before it and [end] is `$` after it.
	 */
	fun word(
		text: String,
		start: Boolean = false,
		end: Boolean = false,
		case: Case = Case.Default,
	) =
		Test.Name(NameMatch(TextPattern.of(text), NamePart.of(text), Anchors(start, end)), case)

	/** `=text`: the stem, or with a `.` after its first character the whole name, is [text] and nothing else. */
	fun exactly(text: String, case: Case = Case.Default) =
		word(text, start = true, end = true, case = case)

	/** `r"text"`: [text] as it is, nothing in it special, found as a substring. */
	fun raw(
		text: String,
		case: Case = Case.Default,
		start: Boolean = false,
		end: Boolean = false,
	) = Test.Name(
		NameMatch(TextPattern.Literal(text), NamePart.of(text), Anchors(start, end)),
		case,
	)

	/**
	 * A path term: `funny/rep`, `/mnt/photos/`, `~/Downloads/inv`, `~reports/`. [start] and [end] anchor its last
	 * segment, which is matched as a name term is.
	 */
	fun path(
		text: String,
		case: Case = Case.Default,
		start: Boolean = false,
		end: Boolean = false,
	) = Test.InPath(PathPatterns.parse(text, Anchors(start, end)), case)

	/** `r/source/`: a regex found in the whole name, or with `s` the stem, or with `p` the whole path. */
	fun regex(source: String, on: RegexOn = RegexOn.Name, case: Case = Case.Default) =
		Test.Regex(source, on, case)

	/**
	 * `dir:pattern[:depth]`: under a folder whose name is [pattern] (a word or a glob), or where [pattern] has a `/` or
	 * starts with `~`, a folder at that path.
	 */
	fun dir(
		pattern: String,
		depth: Depth = Depth.ANY,
		case: Case = Case.Default,
	): Test.Dir {
		val target =
			if (pattern.startsWith("~") || PathPatterns.slashes(pattern).isNotEmpty())
				PathPatterns.parse(pattern.trimEnd('/') + "/", Anchors.NONE).let {
					DirTarget.Located(it.base, it.segments)
				}
			else
				DirTarget.Named(TextPattern.of(pattern))

		return Test.Dir(target, depth, case)
	}

	/** `ext:pattern`: an extension that is [pattern], a word or a glob. */
	fun ext(pattern: String) = Test.Ext(ExtPattern.Text(TextPattern.of(pattern)))

	/** `ext:/source/`: an extension a regex matches the whole of. */
	fun extRegex(source: String, case: Case = Case.Default) =
		Test.Ext(ExtPattern.Regex(source, case))

	/** `type:group`. */
	fun type(group: Group) = Test.Type(group)

	/** `is:flag`, or `is:{a,b}` for several. */
	fun isA(vararg flags: Flag) = Test.Is(flags.toSet())

	/** `recent:when`. */
	fun recent(since: Since) = Test.Recent(since)

	/** `new:when`. */
	fun new(since: Since) = Test.New(since)

	/** `mount:type`. */
	fun mount(type: String) = Test.Mount(TextPattern.of(type))
}

/** How a path term's text is read: into where it starts, its levels, and its last segment. */
object PathPatterns {
	/**
	 * [text] as a path pattern. A leading `/` is the root, `~` the home folder and `~name` an alias; a trailing `/`
	 * (or a last segment of `**`) is everything under; `**` is any number of levels, as a whole segment or at the
	 * start of the last one (`a/` then `**.jpg` is `a/` then `**`, and `*.jpg` below that). [anchors] hold the last
	 * segment's match.
	 */
	fun parse(text: String, anchors: Anchors): PathPattern {
		val (base, rest) = baseOf(text)
		val cuts = slashes(rest)
		val pieces =
			(listOf(-1) + cuts).zip(cuts + rest.length).map { (from, to) ->
				rest.substring(from + 1, to)
			}
		val under = rest.isEmpty() || cuts.lastOrNull() == rest.length - 1
		val last = pieces.last()

		return when {
			under -> PathPattern(base, levelsOf(pieces.dropLast(1)), PathPattern.Last.Under)
			last == "**" -> PathPattern(base, levelsOf(pieces.dropLast(1)), PathPattern.Last.Under)
			last.startsWith("**") ->
				PathPattern(
					base,
					levelsOf(pieces.dropLast(1)) + Segment.AnyLevels,
					entryOf(last.substring(1), anchors),
				)
			else -> PathPattern(base, levelsOf(pieces.dropLast(1)), entryOf(last, anchors))
		}
	}

	/** Where in [text] its unescaped slashes are. */
	internal fun slashes(text: String): List<Int> {
		val found = ArrayList<Int>()
		var at = 0

		while (at < text.length) {
			when (text[at]) {
				'\\' -> at++
				'/' -> found += at
			}
			at++
		}

		return found
	}

	private fun baseOf(text: String): Pair<PathBase, String> =
		when {
			text.startsWith("/") -> PathBase.Root to text.substring(1)
			text == "~" || text.startsWith("~/") ->
				PathBase.Home to
					text.removePrefix("~").removePrefix("/")
			text.startsWith("~") -> {
				val slash = text.indexOf('/')
				val name =
					if (slash < 0)
						text.substring(1)
					else
						text.substring(1, slash)

				val rest =
					if (slash < 0)
						""
					else
						text.substring(slash + 1)

				PathBase.Alias(name) to rest
			}
			else -> PathBase.Relative to text
		}

	private fun levelsOf(pieces: List<String>): List<Segment> =
		pieces.map {
			require(it.isNotEmpty()) { "a path's levels are named: “//” has none between" }
			require(
				it == "**" || "**" !in it,
			) { "“**” is a whole level, or the start of the last" }

			if (it == "**")
				Segment.AnyLevels
			else
				Segment.Named(TextPattern.of(it))
		}

	private fun entryOf(last: String, anchors: Anchors): PathPattern.Last.Entry {
		require(last.isNotEmpty()) { "a path ends with a name, or with “/”" }

		return PathPattern.Last.Entry(
			NameMatch(TextPattern.of(last), NamePart.of(last), anchors),
		)
	}
}
