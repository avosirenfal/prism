package prism.index.query

import java.time.DayOfWeek
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * What a query's text means: each term of its syntax made into a [Term], with what is wrong with it. A query is read
 * as it is typed, so most of what is wrong with one is that it is not finished: a quote not closed yet, a `[` not
 * closed yet, `ext:` with no extension after it yet. So a problem is one of two kinds, by one rule:
 *
 * - **unfinished**, where the problem runs to the end of the text (where the cursor most likely is): the term is
 *   finished as well as it can be (a quote or a `[` closed at the end) and searched for, so results show as it is
 *   typed;
 * - **invalid**, anywhere else, or where no more typing would mend it (`ic"..."`, `foo:bar`): the term is left out of
 *   the search, and the rest of the query is searched without it.
 *
 * Either way, the problem has a span, for the text to be marked where it is.
 */

/** Something wrong with a query's text: where, what, and of which [kind]. */
data class Problem(
	val span: Span,
	val message: String,
	val kind: Kind,
) {
	enum class Kind {
		/** The text ends before this is finished: it is finished as well as it can be, and searched for. */
		Unfinished,

		/** This is wrong as written: its term is left out of the search. */
		Invalid,
	}
}

/** One term of a query's text: as it was written, what it means (null: nothing yet), and what is wrong with it. */
data class ReadTerm(
	val syntax: TermSyntax,
	val meaning: Term?,
	val problems: List<Problem>,
) {
	/** The term searched for: what it means, unless something about it is invalid. */
	val searched: Term? get() =
		meaning.takeIf {
			problems.none {
				it.kind ==
					Problem.Kind.Invalid
			}
		}
}

/**
 * A query's text, read: its [syntax], each term's meaning and problems, the [query] that searches for what can be
 * searched for, and the [marks] that colour the text.
 */
data class ReadQuery(
	val syntax: QuerySyntax,
	val terms: List<ReadTerm>,
) {
	val text: String get() = syntax.text

	val query: Query get() = Query(terms.mapNotNull { it.searched })

	/** Every term's problems, in the order of where they are in the text. */
	val problems: List<Problem> get() =
		terms
			.flatMap {
				it.problems
			}.sortedBy { it.span.start }

	val marks: List<Mark> by lazy { Marks.of(syntax) }
}

/** Reading a query's text. */
object QueryText {
	/**
	 * [text] read: parsed into its syntax, and each term made into what it means. Any text reads, however it is
	 * malformed or unfinished. Where [aliases] are given, a `~name` that is not one of them is a problem.
	 */
	fun read(text: String, aliases: Set<String>? = null): ReadQuery {
		val syntax = QueryGrammar.read(text)

		return ReadQuery(syntax, syntax.terms.map { Reading(text, aliases).of(it) })
	}
}

/**
 * The reading of one term: the one place meaning is made, and where its problems are gathered as they are found.
 * Made for one term and dropped after it.
 */
private class Reading(
	private val text: String,
	private val aliases: Set<String>?,
) {
	private val problems = ArrayList<Problem>()

	fun of(term: TermSyntax): ReadTerm {
		val test =
			when (val body = term.body) {
				null -> {
					unfinishedOr(
						term.negation ?: term.span,
						"“-” excludes the term after it. To search for a name starting with “-”, quote it: \"-notes\"",
					)
					null
				}
				is BodySyntax.Words -> words(body)
				is BodySyntax.Regex ->
					regex(body.regex, RegexOn.entries)?.let {
						Test.Regex(it.source, it.on, it.case)
					}
				is BodySyntax.Modifier -> modifier(body)
			}

		term.junk?.let { invalid(it, "Add a space between terms") }

		return ReadTerm(
			term,
			test?.let {
				Term(it, negated = term.negation != null)
			},
			problems.toList(),
		)
	}

	// ---- problems -----------------------------------------------------------------------------------------------

	/** A problem that more typing could mend: unfinished where it runs to the end of the text, and invalid elsewhere. */
	private fun unfinishedOr(span: Span, message: String) {
		problems +=
			Problem(
				span,
				message,
				if (span.end ==
					text.length
				)
					Problem.Kind.Unfinished
				else
					Problem.Kind.Invalid,
			)
	}

	private fun invalid(span: Span, message: String) {
		problems += Problem(span, message, Problem.Kind.Invalid)
	}

	// ---- words ------------------------------------------------------------------------------------------------------

	/** Words as a term or a value means them: their [text] (escapes and all), where it [starts], whether it is raw. */
	private class Content(
		val text: String,
		val starts: Int,
		val raw: Boolean,
		val case: Case,
	) {
		val span: Span get() = Span(starts, starts + text.length)

		val isPath: Boolean get() = !raw && (PathPatterns.slashes(text).isNotEmpty())
	}

	private fun contentOf(text: TextSyntax): Content? =
		when (text) {
			is TextSyntax.Bare -> Content(text.text, text.span.start, raw = false, Case.Default)
			is TextSyntax.Quoted -> quoted(text)
		}

	private fun quoted(text: TextSyntax.Quoted): Content? {
		val sensitive = text.flags.filter { it.value == 'c' }
		val insensitive = text.flags.filter { it.value == 'i' }

		if (sensitive.isNotEmpty() && insensitive.isNotEmpty())
			(sensitive + insensitive).forEach {
				invalid(it.span, "Use either “c” or “i”, not both")
			}
		if (text.hashes != null && text.flags.none { it.value == 'r' })
			invalid(text.hashes, "“#” is only allowed in a raw term: r#\"...\"#")
		if (text.close == null)
			unfinishedOr(text.span, "Missing closing quote")

		if (text.content.value.isEmpty()) {
			if (text.close != null)
				invalid(text.span, "Empty quotes match nothing")

			return null
		}

		val case =
			when {
				sensitive.isNotEmpty() && insensitive.isEmpty() -> Case.Sensitive
				insensitive.isNotEmpty() && sensitive.isEmpty() -> Case.Insensitive
				else -> Case.Default
			}

		return Content(text.content.value, text.content.span.start, text.isRaw, case)
	}

	/**
	 * What [content] matches: itself where it is raw or has no glob character, and otherwise a glob, read leniently: a
	 * `[` or `{` not closed is closed at its end, which is unfinished where its end is the text's.
	 */
	private fun patternOf(content: Content): TextPattern? {
		if (content.raw)
			return TextPattern.Literal(content.text)
		if (!Glob.isGlob(content.text))
			return TextPattern.Literal(Glob.unescaped(content.text))

		val reading = Glob.read(content.text)

		for (range in reading.backwards)
			invalid(
				Span(content.starts + range.start, content.starts + range.end),
				"The range “${content.text.substring(range.start, range.end)}” is backwards",
			)
		for (opened in reading.unclosed)
			unfinishedOr(
				Span(content.starts + opened, content.span.end),
				"“${content.text[opened]}” is missing its closing “${if (content.text[opened] == '[') ']' else '}'}”",
			)

		return if (reading.glob.parts.isEmpty())
			null
		else
			TextPattern.Wildcard(reading.glob)
	}

	private fun words(body: BodySyntax.Words): Test? {
		val text = body.text

		if (text == null) {
			unfinishedOr(
				body.span,
				if (body.start !=
					null
				)
					"Add a term after the anchor"
				else
					"Add a term before “$”",
			)

			return null
		}

		val content = contentOf(text) ?: return null

		val anchors =
			Anchors(
				start = body.start != null,
				end =
					body.start?.value == Anchor.Exact || body.end != null,
			)

		if (content.isPath)
			return pathOf(content, anchors)?.let { Test.InPath(it, content.case) }

		return patternOf(content)?.let {
			Test.Name(NameMatch(it, NamePart.of(content.text), anchors), content.case)
		}
	}

	private fun pathOf(content: Content, anchors: Anchors): PathPattern? {
		val pattern =
			try {
				PathPatterns.parse(content.text, anchors)
			} catch (wrong: IllegalArgumentException) {
				unfinishedOr(content.span, wrong.message ?: "Not a valid path")

				return null
			}

		return pattern.takeIf { known(it.base, content) }
	}

	/** Whether [base] is a place the user has: anything but an alias not among [aliases], where they are known. */
	private fun known(base: PathBase, content: Content): Boolean {
		if (base !is PathBase.Alias || aliases == null || base.name in aliases)
			return true

		invalid(
			Span(content.starts, content.starts + 1 + base.name.length),
			"No folder alias named “~${base.name}”",
		)

		return false
	}

	// ---- regexes ----------------------------------------------------------------------------------------------------

	private class Regexed(
		val source: String,
		val on: RegexOn,
		val case: Case,
	)

	/** A regex, with the flags [allowed]: `s` and `p` say what it is over (where [on] has more than the name). */
	private fun regex(regex: RegexSyntax, on: List<RegexOn>): Regexed? {
		val flags =
			regex.flags
				?.let { flags ->
					flags.value.mapIndexed { at, it ->
						Spanned(
							it,
							Span(flags.span.start + at, flags.span.start + at + 1),
						)
					}
				}.orEmpty()

		val letters = if (on.size > 1) "s, p, c and i" else "c and i"

		for (flag in flags)
			if (flag.value !in (if (on.size > 1) "spci" else "ci"))
				invalid(flag.span, "Allowed regex flags here: $letters")

		fun pair(one: Char, other: Char): Char? {
			val ones = flags.filter { it.value == one }
			val others = flags.filter { it.value == other }

			if (ones.isNotEmpty() && others.isNotEmpty()) {
				(ones + others).forEach {
					invalid(it.span, "Use either “$one” or “$other”, not both")
				}

				return null
			}

			return (ones + others).firstOrNull()?.value
		}

		val over =
			when (
				if (on.size > 1)
					pair('s', 'p')
				else
					null
			) {
				's' -> RegexOn.Stem
				'p' -> RegexOn.Path
				else -> RegexOn.Name
			}

		val case =
			when (pair('c', 'i')) {
				'c' -> Case.Sensitive
				'i' -> Case.Insensitive
				else -> Case.Default
			}

		if (regex.close == null)
			unfinishedOr(regex.span, "Missing closing “/”")

		val source = regex.source.value

		if (source.isEmpty()) {
			if (regex.close != null)
				invalid(regex.span, "The regex is empty")

			return null
		}

		try {
			Pattern.compile(source)
		} catch (wrong: PatternSyntaxException) {
			if (regex.close != null)
				invalid(regex.source.span, "Invalid regex: ${wrong.description}")

			return null
		}

		return Regexed(source, over, case)
	}

	// ---- modifiers --------------------------------------------------------------------------------------------------

	private fun modifier(body: BodySyntax.Modifier): Test? {
		val name = body.name.value.lowercase()

		/** What [mean] makes of [value], which the modifier needs: said to be missing, where nothing follows the colon. */
		fun <V : ValueSyntax> given(value: V?, mean: (V) -> Test?): Test? {
			if (value == null) {
				unfinishedOr(body.span, "“$name:” needs a value")

				return null
			}

			return mean(value)
		}

		return when (val said = body.said) {
			is ModifierSyntax.Unknown -> {
				val known = ModifierSyntax.NAMES.joinToString(", ") { "$it:" }

				invalid(body.name.span, "Unknown modifier “${body.name.value}:”. Available: $known")

				null
			}
			is ModifierSyntax.Dir -> given(said.value, ::dir)
			is ModifierSyntax.Ext -> given(said.value, ::ext)
			is ModifierSyntax.Worded ->
				given(said.value) { value ->
					when (said.of) {
						ModifierSyntax.Worded.Kind.Type -> type(value)
						ModifierSyntax.Worded.Kind.Is -> flags(value)
						ModifierSyntax.Worded.Kind.Recent -> since(value, name)?.let(Test::Recent)
						ModifierSyntax.Worded.Kind.New -> since(value, name)?.let(Test::New)
						ModifierSyntax.Worded.Kind.Mount -> mount(value)
					}
				}
		}
	}

	private fun dir(value: ValueSyntax.Placed): Test? {
		val content = contentOf(value.text) ?: return null
		val depth = value.depth?.let { depthOf(it) ?: return null } ?: Depth.ANY
		val located = !content.raw && (content.text.startsWith("~") || content.isPath)

		val target =
			if (located) {
				val path =
					pathOf(
						Content(content.text.trimEnd('/') + "/", content.starts, false, content.case),
						Anchors.NONE,
					)

				DirTarget.Located(path?.base ?: return null, path.segments)
			} else {
				DirTarget.Named(patternOf(content) ?: return null)
			}

		return Test.Dir(target, depth, content.case)
	}

	private fun depthOf(depth: DepthSyntax): Depth? {
		val text = depth.text.value
		val shape = Regex("(\\d+)(-(\\d*))?").matchEntire(text)

		if (text.isEmpty()) {
			unfinishedOr(
				depth.span,
				"Add a depth after “:”: 2 (exactly), 2-4 (from 2 to 4) or 2- (2 or more)",
			)

			return null
		}
		if (shape == null) {
			invalid(depth.span, "A depth is 2 (exactly), 2-4 (from 2 to 4) or 2- (2 or more)")

			return null
		}

		val min = shape.groupValues[1].toIntOrNull()
		val max = shape.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()

		return when {
			min == null || min < 1 ->
				null.also {
					invalid(depth.span, "Depth starts at 1 (the parent folder)")
				}
			shape.groupValues[2].isEmpty() -> Depth.exactly(min)
			shape.groupValues[3].isEmpty() -> Depth.atLeast(min)
			max == null || max < min ->
				null.also {
					invalid(depth.span, "The depth range is backwards. Use $max-$min")
				}
			else -> Depth.between(min, max)
		}
	}

	private fun ext(value: ValueSyntax.OfExt): Test? =
		when (value) {
			is ValueSyntax.Regex ->
				regex(value.regex, listOf(RegexOn.Name))?.let {
					Test.Ext(ExtPattern.Regex(it.source, it.case))
				}
			is ValueSyntax.Words -> {
				val content = contentOf(value.text)

				// `ext:.pdf` is `ext:pdf`: the dot is the boundary, not part of the extension.
				val extension =
					content?.let {
						if (it.text.startsWith(".") &&
							it.text.length > 1
						)
							Content(it.text.substring(1), it.starts + 1, it.raw, it.case)
						else
							it
					}

				extension?.let(::patternOf)?.let { Test.Ext(ExtPattern.Text(it, extension.case)) }
			}
		}

	/** A value that is one of [choices]' names, unfinished where it is the start of one at the end of the text. */
	private fun <T> oneOf(content: Content, choices: Map<String, T>, what: String): T? {
		val said = Glob.unescaped(content.text).lowercase()

		choices[said]?.let { return it }

		val message = "$what ${choices.keys.joinToString(", ")}"

		if (choices.keys.any { it.startsWith(said) })
			unfinishedOr(content.span, message)
		else
			invalid(content.span, message)

		return null
	}

	private fun type(value: ValueSyntax.Words): Test? {
		val content = contentOf(value.text) ?: return null

		return oneOf(
			content,
			Group.entries.associateBy {
				it.name.lowercase()
			},
			"type: accepts",
		)?.let(Test::Type)
	}

	private fun flags(value: ValueSyntax.Words): Test? {
		val content = contentOf(value.text) ?: return null
		val names = Flag.entries.associateBy { it.name.lowercase() }

		if (!content.text.startsWith("{"))
			return oneOf(content, names, "is: accepts")?.let { Test.Is(setOf(it)) }

		// `is:{file,symlink}`: each name between the braces, each with where it is.
		val closed = content.text.endsWith("}") && content.text.length > 1

		val inside =
			content.text.substring(
				1,
				if (closed)
					content.text.length - 1
				else
					content.text.length,
			)

		if (!closed)
			unfinishedOr(content.span, "Missing closing “}”")

		val starts = inside.indices.filter { inside[it] == ',' }.map { it + 1 }
		val entries =
			(listOf(0) + starts).zip(starts.map { it - 1 } + inside.length).map { (from, to) ->
				Content(inside.substring(from, to), content.starts + 1 + from, false, Case.Default)
			}

		val flags =
			entries.mapNotNull { entry ->
				if (entry.text.isEmpty()) {
					unfinishedOr(Span(entry.starts, entry.starts), "Add a flag between the commas")
					null
				} else {
					oneOf(entry, names, "is: accepts")
				}
			}

		return if (flags.isEmpty())
			null
		else
			Test.Is(flags.toSet())
	}

	/** When [value], what `recent:` or `new:` ([name]) was given, counts from. */
	private fun since(value: ValueSyntax.Words, name: String): Since? {
		val content = contentOf(value.text) ?: return null
		val said = content.text.lowercase()
		val duration = Regex("(\\d+)([smhdw])").matchEntire(said)
		val days =
			mapOf("today" to Since.Today, "yesterday" to Since.Yesterday) +
				DayOfWeek.entries.associate { it.name.lowercase() to Since.Weekday(it) }

		val what =
			"$name: accepts a duration (5m, 24h, 2d, 1w), today, yesterday, or a weekday:"

		return when {
			duration != null -> {
				val count = duration.groupValues[1].toLongOrNull()
				val unit = UNITS.getValue(duration.groupValues[2].single())

				if (count == null || count == 0L) {
					invalid(content.span, "The duration must be greater than zero")
					null
				} else {
					Since.Within(unit * count.toDouble())
				}
			}
			said.isNotEmpty() && said.all { it.isDigit() } -> {
				unfinishedOr(content.span, "Add a unit to “$said”: s, m, h, d or w")
				null
			}
			else -> oneOf(content, days, what)
		}
	}

	private fun mount(value: ValueSyntax.Words): Test? {
		val content = contentOf(value.text) ?: return null

		return patternOf(content)?.let(Test::Mount)
	}

	private companion object {
		val UNITS: Map<Char, Duration> =
			mapOf(
				's' to 1.seconds,
				'm' to 1.minutes,
				'h' to 1.hours,
				'd' to 1.days,
				'w' to 7.days,
			)
	}
}
