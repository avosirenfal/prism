package prism.index

import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.BitSet
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.regex.Pattern
import java.util.stream.IntStream
import kotlin.io.path.Path
import prism.index.query.Anchors
import prism.index.query.Case
import prism.index.query.DirTarget
import prism.index.query.ExtPattern
import prism.index.query.Flag
import prism.index.query.Glob
import prism.index.query.NamePart
import prism.index.query.PathBase
import prism.index.query.PathPattern
import prism.index.query.Query
import prism.index.query.RegexOn
import prism.index.query.Segment
import prism.index.query.Since
import prism.index.query.Term
import prism.index.query.Test
import prism.index.query.TextPattern

/*
 * Running a query over the index's columns.
 *
 * Each term is compiled once into a check of one entry: its needle folded or not, its glob read into tokens over
 * bytes, its path into a small automaton. A search then walks each block in order (preorder), keeping the folders it
 * is under on a stack, and keeps the entries every check passes. A term that asks about an entry's ancestors (`dir:`, a
 * path) keeps a state at each level of the stack, worked out once as the walk goes into a folder and then read by
 * every entry under it, so it costs a step per folder and a look per entry, never a climb up the tree per entry.
 *
 * Names are compared as bytes, in the [Block.names] column or the [Block.folded] one, so matching a name allocates
 * nothing; only a regex, which Java runs over strings, decodes one. Large blocks are walked in slices on every core,
 * each slice starting its stack from the ancestors of its first entry.
 *
 * Each entry found is scored as it is found ([Ranker], rank.kt), and the results are given best first.
 */

/**
 * What a search is run in: what the search as a whole says, what `~`, `~name` and `recent:` need to know, and what
 * its results are ranked by.
 */
data class SearchContext(
	/** The toggle for the whole search: whether terms that do not say otherwise tell upper from lower case. */
	val caseSensitive: Boolean = false,
	val home: Path = Path(System.getProperty("user.home")),
	/** The user's named folders: `~reports` is every folder named here as `reports`. */
	val aliases: Map<String, List<Path>> = emptyMap(),
	val now: Instant = Instant.now(),
	val zone: ZoneId = ZoneId.systemDefault(),
	/** What the user has picked, and from where: what `recent:` asks about, and what ranks a result higher. */
	val usage: Usage = Usage.NONE,
	val ranking: Ranking = Ranking(),
)

/** What a search gave: the entries found, or why the query could not be run. */
sealed interface Searched {
	data class Found(
		val results: Results,
	) : Searched

	/** A query that could not be run where it was asked: an alias that is not defined, a path too long to match. */
	data class Refused(
		val why: String,
	) : Searched
}

/** The entries [query] finds, in [context]. */
fun Index.search(query: Query, context: SearchContext = SearchContext()): Searched =
	when (val plan = Plan.of(query, context, this)) {
		is Planning.Refused -> Searched.Refused(plan.why)
		is Planning.Ready ->
			Searched.Found(
				Searching.run {
					Results(
						placed.indices.map {
							plan.plan.run(this, it)
						},
					)
				},
			)
	}

/**
 * The threads searches are run on: one for every core but one. A search is asked for by whatever is showing a window,
 * key by key, and a broad one keeps every thread it is given busy for as long as it takes; left every core, it would
 * take the one the window is drawn on as well, and what is typed would be seen late. Everything a search runs side by
 * side (a block's slices, the first results' order) is run here, since what is run from one of a pool's threads stays
 * in that pool.
 */
internal object Searching {
	private val pool =
		ForkJoinPool(
			maxOf(1, Runtime.getRuntime().availableProcessors() - 1),
			{ pool ->
				ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool).apply {
					name = "prism-search-$poolIndex"
				}
			},
			null,
			false,
		)

	/** What [search] gives, run on the searches' threads; as it is, where it is asked from one of them. */
	fun <T> run(search: () -> T): T =
		if (ForkJoinTask.inForkJoinPool())
			search()
		else
			pool.submit(search).join()
}

/**
 * The folders this query looks only under: where each of its path terms (and each `dir:` at a path) that starts at
 * the root, the home folder or an alias leads, up to its first glob or `**`: `/nix/store/`, and then a glob, looks
 * under `/nix/store`, and `~/.cache/thumbnails/` under the home folder's `.cache/thumbnails`. A negated term says where not to
 * look, so it is not counted. What a live index reads for a query alone where these are places the rules leave out.
 */
fun Query.places(context: SearchContext = SearchContext()): List<Where> =
	terms
		.filterNot { it.negated }
		.flatMap { term ->
			when (val test = term.test) {
				is Test.InPath -> placesOf(test.pattern.base, test.pattern.segments, context)
				is Test.Dir ->
					(test.target as? DirTarget.Located)
						?.let {
							placesOf(it.base, it.segments, context)
						}.orEmpty()
				else -> emptyList()
			}
		}.distinct()

private fun placesOf(
	base: PathBase,
	segments: List<Segment>,
	context: SearchContext,
): List<Where> {
	val starts =
		when (base) {
			PathBase.Relative -> return emptyList()
			PathBase.Root -> listOf(Where.ROOT)
			PathBase.Home -> listOf(Where.of(context.home))
			is PathBase.Alias -> context.aliases[base.name].orEmpty().map(Where::of)
		}

	val named =
		segments
			.asSequence()
			.map { ((it as? Segment.Named)?.pattern as? TextPattern.Literal)?.text }
			.takeWhile { it != null && it != "." && it != ".." }
			.filterNotNull()
			.map(String::encodeToByteArray)
			.toList()

	return starts.map { start -> named.fold(start, Where::child) }
}

/** What planning a query gave. */
private sealed interface Planning {
	data class Ready(
		val plan: Plan,
	) : Planning

	data class Refused(
		val why: String,
	) : Planning
}

/** A check made for one block, given which placed block it is. */
private typealias Making = (block: Block, placed: Int) -> Check

/** A term compiled: how to make its check for a block, or why it cannot be run. */
private sealed interface Compiled {
	class Made(
		val making: Making,
	) : Compiled

	class Refused(
		val why: String,
	) : Compiled
}

/**
 * A query compiled: a check for each term, made afresh for each block it walks; and the words whose match of a name
 * ranks a result, with the context that ranks the rest.
 */
private class Plan(
	private val making: List<Making>,
	private val words: List<Word>,
	private val context: SearchContext,
) {
	/** The entries of the [placed]th block of [index] that pass every check, each with its score. */
	fun run(index: Index, placed: Int): Results.Part {
		val (block, hidden) = index.placed[placed].let { it.block to it.hidden }
		// An array, gone through by its indices: this is asked of every entry of the index, and a list's iterator made
		// for each would be millions of them for one search.
		val checks = making.map { it(block, placed) }.sortedBy { it.cost }.toTypedArray()

		val ranker =
			Ranker.of(
				block,
				hidden,
				words,
				context.usage,
				context.ranking,
				Where.of(context.home),
				context.now,
			)

		val slices =
			if (block.size <= SLICE)
				listOf(Walk(block, hidden, checks, ranker).run(0, block.size))
			else
				IntStream
					.range(0, (block.size + SLICE - 1) / SLICE)
					.parallel()
					.mapToObj {
						Walk(block, hidden, checks, ranker).run(
							it * SLICE,
							minOf(
								block.size,
								(it + 1) * SLICE,
							),
						)
					}.toList()

		return Results.Part(index, placed, Hits.joined(slices))
	}

	companion object {
		/** How many entries a walk takes at a time, a slice of a block walked on one core. */
		const val SLICE = 1 shl 16

		fun of(query: Query, context: SearchContext, index: Index): Planning {
			// Each check that keeps a state at each level of a walk has a slot of its own for it.
			val slots = generateSequence(0) { it + 1 }.iterator()
			val compiled = query.terms.map { compile(it, context, index) { slots.next() } }

			return compiled.filterIsInstance<Compiled.Refused>().firstOrNull()?.let {
				Planning.Refused(it.why)
			}
				?: Planning.Ready(
					Plan(
						compiled.filterIsInstance<Compiled.Made>().map { it.making },
						wordsOf(query, context),
						context,
					),
				)
		}

		/** The words whose match of a name ranks a result: those of the terms that ask for a name to hold some text. */
		private fun wordsOf(query: Query, context: SearchContext): List<Word> =
			query.terms
				.filterNot { it.negated }
				.mapNotNull { term ->
					val test = term.test as? Test.Name
					val literal = test?.match?.pattern as? TextPattern.Literal

					literal?.takeIf { it.text.isNotEmpty() }?.let {
						val sensitive = test.case.sensitive(context.caseSensitive)
						val bytes = it.text.encodeToByteArray()

						Word(
							if (sensitive)
								bytes
							else
								Fold.folded(bytes),
							folded = !sensitive,
						)
					}
				}

		/** [term] compiled, or why it cannot be run. */
		private fun compile(
			term: Term,
			context: SearchContext,
			index: Index,
			slot: () -> Int,
		): Compiled {
			val no = term.negated

			fun sensitive(case: Case) = case.sensitive(context.caseSensitive)

			fun made(making: Making) = Compiled.Made(making)

			return when (val test = term.test) {
				is Test.Name -> {
					val matching =
						Matching.of(
							test.match.pattern,
							test.match.anchors,
							sensitive(test.case),
						)

					made { _, _ -> NameCheck(matching, test.match.part, no) }
				}
				is Test.InPath -> {
					val nfa =
						Nfa.of(
							levelsOf(test.pattern.base, test.pattern.segments, context),
							sensitive(test.case),
						)
							?: return refusal(test.pattern.base)

					val at = slot()

					when (val last = test.pattern.last) {
						PathPattern.Last.Under -> made { _, _ -> UnderCheck(nfa, at, no) }
						is PathPattern.Last.Entry -> {
							val matching =
								Matching.of(
									last.match.pattern,
									last.match.anchors,
									sensitive(test.case),
								)

							made { _, _ -> PathEntryCheck(nfa, matching, last.match.part, at, no) }
						}
					}
				}
				is Test.Regex -> {
					val pattern = Pattern.compile(test.source, flagsOf(test.case, context))

					made { _, _ -> RegexCheck(pattern, test.on, no) }
				}
				is Test.Dir -> {
					val at = slot()

					when (val target = test.target) {
						is DirTarget.Named -> {
							val matching = Matching.of(target.pattern, Anchors.BOTH, sensitive(test.case))

							made { _, _ ->
								DirCheck(
									DirCheck.Named(matching),
									test.depth.min,
									test.depth.max,
									at,
									no,
								)
							}
						}
						is DirTarget.Located -> {
							val nfa =
								Nfa.of(levelsOf(target.base, target.segments, context), sensitive(test.case))
									?: return refusal(target.base)

							made { _, _ ->
								DirCheck(
									DirCheck.Located(nfa),
									test.depth.min,
									test.depth.max,
									at,
									no,
								)
							}
						}
					}
				}
				is Test.Ext -> {
					val matching =
						when (val pattern = test.pattern) {
							is ExtPattern.Text ->
								Matching.of(
									pattern.pattern,
									Anchors.BOTH,
									sensitive(pattern.case),
								)
							is ExtPattern.Regex ->
								Matching(
									RegexMatch(Pattern.compile(pattern.source, flagsOf(pattern.case, context))),
									folded = false,
								)
						}

					made { _, _ -> ExtCheck(matching, no) }
				}
				is Test.Type -> {
					val extensions =
						test.group.extensions
							.mapNotNull(::packed)
							.sorted()
							.toLongArray()

					made { _, _ -> TypeCheck(extensions, no) }
				}
				is Test.Is -> made { _, _ -> IsCheck(test.flags, no) }
				is Test.Recent -> {
					val since = cutoffOf(test.since, context)
					val picked =
						context.usage
							.pickedSince(since)
							.mapNotNull(index::locate)
							.groupBy({ it.first }, { it.second })
							.mapValues { (_, entries) -> BitSet().also { set -> entries.forEach(set::set) } }

					made { _, placed -> RecentCheck(picked[placed], no) }
				}
				is Test.New -> {
					val since = cutoffOf(test.since, context).epochSecond

					made { _, _ -> NewCheck(since, no) }
				}
				is Test.Mount -> {
					val matching = Matching.of(test.type, Anchors.BOTH, sensitive(Case.Default))

					made { block, _ ->
						MountCheck(
							BooleanArray(block.devices.size) { device ->
								block.devices[device]
									.type
									?.encodeToByteArray()
									?.let(matching::matches) == true
							},
							no,
						)
					}
				}
			}
		}

		private fun refusal(base: PathBase): Compiled =
			Compiled.Refused(
				when (base) {
					is PathBase.Alias -> "No folder alias named “~${base.name}”"
					else -> "That path is too long to search for"
				},
			)

		/**
		 * Where a path pattern's levels start, each way it can: a relative pattern from anywhere (a `**` before it),
		 * `~` from the home folder, `~name` from each folder of the alias; null for an alias not defined.
		 */
		private fun levelsOf(
			base: PathBase,
			segments: List<Segment>,
			context: SearchContext,
		): List<List<Segment>>? {
			fun from(path: Path) =
				Where.of(path).levels.map { Segment.Named(TextPattern.Literal(String(it))) } +
					segments

			return when (base) {
				PathBase.Relative -> listOf(listOf(Segment.AnyLevels) + segments)
				PathBase.Root -> listOf(segments)
				PathBase.Home -> listOf(from(context.home))
				is PathBase.Alias -> context.aliases[base.name]?.map(::from)
			}
		}

		private fun flagsOf(case: Case, context: SearchContext): Int =
			if (case.sensitive(context.caseSensitive))
				0
			else
				Pattern.CASE_INSENSITIVE or
					Pattern.UNICODE_CASE

		private fun cutoffOf(since: Since, context: SearchContext): Instant {
			val today = LocalDate.ofInstant(context.now, context.zone)

			fun startOf(day: LocalDate) = day.atStartOfDay(context.zone).toInstant()

			return when (since) {
				Since.Today -> startOf(today)
				Since.Yesterday -> startOf(today.minusDays(1))
				is Since.Weekday -> startOf(today.with(TemporalAdjusters.previousOrSame(since.day)))
				is Since.Within -> context.now.minusMillis(since.duration.inWholeMilliseconds)
			}
		}

		/** An extension of up to eight ASCII bytes as one number, for a group's to be found by a binary search. */
		fun packed(extension: String): Long? =
			packed(extension.encodeToByteArray(), 0, extension.length)

		fun packed(bytes: ByteArray, from: Int, to: Int): Long? {
			if (to - from !in 1..8)
				return null

			return (from until to).fold(0L) { packed, at ->
				if (bytes[at] < 0)
					return null
				(packed shl 8) or bytes[at].toLong()
			}
		}
	}
}

// ---- matching names --------------------------------------------------------------------------------------------

/** A test of a name, or part of one: the bytes of [bytes] from [from] up to [to]. */
private sealed interface TextMatch {
	fun matches(bytes: ByteArray, from: Int, to: Int): Boolean
}

/** A [match], and whether it is made of the names [Block.folded] (case not told apart) or as they are. */
private class Matching(
	val match: TextMatch,
	val folded: Boolean,
) {
	fun matches(bytes: ByteArray): Boolean =
		match.matches(if (folded) Fold.folded(bytes) else bytes, 0, bytes.size)

	companion object {
		fun of(pattern: TextPattern, anchors: Anchors, sensitive: Boolean): Matching {
			fun bytes(text: String) =
				text.encodeToByteArray().let { if (sensitive) it else Fold.folded(it) }

			val match =
				when (pattern) {
					is TextPattern.Literal -> LiteralMatch(bytes(pattern.text), anchors)
					is TextPattern.Wildcard ->
						GlobMatch(
							pattern.glob.readings.map { parts ->
								val tokens =
									parts.map { part ->
										when (part) {
											is Glob.Part.Text -> Token.Bytes(bytes(part.text))
											Glob.Part.One -> Token.One
											Glob.Part.Run -> Token.Run
											is Glob.Part.Class -> Token.OneOf(part, anyCase = !sensitive)
										}
									}

								// Found anywhere, as a word is, but where an anchor holds it: an end that is not
								// held may have anything past it, which is a run there.
								val before = listOf(Token.Run).takeUnless { anchors.start }.orEmpty()
								val after = listOf(Token.Run).takeUnless { anchors.end }.orEmpty()

								(before + tokens + after).toTypedArray()
							},
						)
				}

			return Matching(match, folded = !sensitive)
		}
	}
}

/** [needle] found anywhere, or where [anchors] say. */
private class LiteralMatch(
	private val needle: ByteArray,
	private val anchors: Anchors,
) : TextMatch {
	override fun matches(bytes: ByteArray, from: Int, to: Int): Boolean {
		val length = needle.size

		if (length > to - from)
			return false

		return when {
			anchors.start && anchors.end -> length == to - from && at(bytes, from)
			anchors.start -> at(bytes, from)
			anchors.end -> at(bytes, to - length)
			else -> anywhere(bytes, from, to)
		}
	}

	private fun at(bytes: ByteArray, from: Int): Boolean =
		java.util.Arrays.equals(bytes, from, from + needle.size, needle, 0, needle.size)

	private fun anywhere(bytes: ByteArray, from: Int, to: Int): Boolean {
		val first = needle[0]

		for (start in from..to - needle.size)
			if (bytes[start] == first && at(bytes, start))
				return true

		return false
	}
}

/** A glob's tokens over bytes: a run of any characters, or what takes something of its own ([Single]). */
private sealed interface Token {
	data object Run : Token

	/** What matches at one place or does not, taking what it matches: all a glob has but its runs. */
	sealed interface Single : Token

	class Bytes(
		val bytes: ByteArray,
	) : Single

	data object One : Single

	class OneOf(
		private val spans: Glob.Part.Class,
		private val anyCase: Boolean,
	) : Single {
		fun has(codePoint: Int): Boolean =
			spans.has(codePoint) ||
				(
					anyCase &&
						(
							spans.has(Character.toUpperCase(codePoint)) ||
								spans.has(Character.toLowerCase(codePoint))
						)
				)
	}
}

/** A glob over the whole of what it is given: any of its readings, each matched as `*` and `?` match. */
private class GlobMatch(
	private val readings: List<Array<Token>>,
) : TextMatch {
	override fun matches(bytes: ByteArray, from: Int, to: Int): Boolean =
		readings.any { matches(it, bytes, from, to) }

	/** The usual matching of a glob: on a mismatch, the last `*` takes one more character, and the rest is tried again. */
	private fun matches(
		tokens: Array<Token>,
		bytes: ByteArray,
		from: Int,
		to: Int,
	): Boolean {
		var token = 0
		var at = from
		var run = -1
		var runFrom = from

		while (true) {
			if (token < tokens.size) {
				when (val next = tokens[token]) {
					Token.Run -> {
						run = token
						runFrom = at
						token++
						continue
					}
					is Token.Single -> {
						val after = step(next, bytes, at, to)

						if (after >= 0) {
							at = after
							token++
							continue
						}
					}
				}
			} else if (at == to) {
				return true
			}

			if (run < 0 || runFrom >= to)
				return false

			runFrom = Utf8.next(bytes, runFrom, to)
			at = runFrom
			token = run + 1
		}
	}

	/** Where [token] leaves off, matched at [at]; -1 where it does not match there. */
	private fun step(token: Token.Single, bytes: ByteArray, at: Int, to: Int): Int =
		when (token) {
			is Token.Bytes ->
				if (to - at >= token.bytes.size &&
					java.util.Arrays.equals(
						bytes,
						at,
						at + token.bytes.size,
						token.bytes,
						0,
						token.bytes.size,
					)
				)
					at + token.bytes.size
				else
					-1
			Token.One ->
				if (at < to)
					Utf8.next(bytes, at, to)
				else
					-1
			is Token.OneOf ->
				if (at < to &&
					token.has(Utf8.codePoint(bytes, at, to))
				)
					Utf8.next(bytes, at, to)
				else
					-1
		}
}

/** A regex: found in a name, or with [whole], matching the whole of it. Java's regexes run over strings, so a name is decoded. */
private class RegexMatch(
	private val pattern: Pattern,
	private val whole: Boolean = true,
) : TextMatch {
	override fun matches(bytes: ByteArray, from: Int, to: Int): Boolean {
		val matcher = pattern.matcher(String(bytes, from, to - from, Charsets.UTF_8))

		return if (whole) matcher.matches() else matcher.find()
	}
}

/** Stepping through UTF-8 a character at a time; a byte that is not UTF-8 is a character of its own. */
private object Utf8 {
	fun next(bytes: ByteArray, at: Int, to: Int): Int = at + length(bytes, at, to)

	/** The character at [at]; for a byte that is not UTF-8, a number no character has. */
	fun codePoint(bytes: ByteArray, at: Int, to: Int): Int {
		val lead = bytes[at].toInt() and 0xFF

		return when (val length = length(bytes, at, to)) {
			1 ->
				if (lead < 0x80)
					lead
				else
					NOT_A_CHARACTER + lead
			else ->
				(1 until length).fold(lead and (0xFF ushr (length + 1))) { codePoint, it ->
					(codePoint shl 6) or (bytes[at + it].toInt() and 0x3F)
				}
		}
	}

	private fun length(bytes: ByteArray, at: Int, to: Int): Int {
		val lead = bytes[at].toInt() and 0xFF
		val length =
			when {
				lead in 0xC2..0xDF -> 2
				lead in 0xE0..0xEF -> 3
				lead in 0xF0..0xF4 -> 4
				else -> return 1
			}

		return if (at + length <= to &&
			(1 until length).all { (bytes[at + it].toInt() and 0xC0) == 0x80 }
		)
			length
		else
			1
	}

	private const val NOT_A_CHARACTER = 0x110000
}

// ---- paths ------------------------------------------------------------------------------------------------------

/**
 * A path pattern's levels as an automaton over the names of an entry's ancestors, its states the bits of a number:
 * state `j` of a way of reading it is "its first `j` levels matched". A `**` keeps its state as a level goes by, and
 * lets the next state be had without one; a named level moves to the next state where the name matches. Several ways
 * (an alias of several folders) are read side by side.
 */
private class Nfa private constructor(
	private val leaving: Array<Matching?>,
	/** The states a `**` leaves. */
	private val anyLevels: Long,
	private val finals: Long,
	starts: Long,
) {
	val initial: Long = closure(starts)

	fun accepts(state: Long): Boolean = state and finals != 0L

	/** The state after a level named as [original] (or [folded]) from [from] to [to]. */
	fun step(
		state: Long,
		original: ByteArray,
		folded: ByteArray,
		from: Int,
		to: Int,
	): Long {
		var next = state and anyLevels
		var named = state and anyLevels.inv() and finals.inv()

		while (named != 0L) {
			val at = named.countTrailingZeroBits()

			named = named and (named - 1)

			val matching = leaving[at] ?: continue

			if (matching.match.matches(if (matching.folded) folded else original, from, to))
				next = next or (1L shl (at + 1))
		}

		return closure(next)
	}

	/** [state] with every state a `**` can be passed to without a level. */
	private fun closure(state: Long): Long =
		leaving.indices.fold(state) { closed, at ->
			if ((closed shr at) and 1L != 0L && (anyLevels shr at) and 1L != 0L)
				closed or (1L shl (at + 1))
			else
				closed
		}

	companion object {
		/** The most states a pattern may have: the bits of a number, but those the checks keep for themselves. */
		private const val MOST = 60

		/** An automaton reading any of [ways], each a run of levels; null where [ways] is null or too long. */
		fun of(ways: List<List<Segment>>?, sensitive: Boolean): Nfa? {
			if (ways == null || ways.sumOf { it.size + 1 } > MOST)
				return null

			val leaving = ArrayList<Matching?>()
			var anyLevels = 0L
			var finals = 0L
			var starts = 0L

			for (way in ways) {
				starts = starts or (1L shl leaving.size)
				for (segment in way) {
					when (segment) {
						Segment.AnyLevels -> {
							anyLevels = anyLevels or (1L shl leaving.size)
							leaving += null
						}
						is Segment.Named -> leaving += Matching.of(segment.pattern, Anchors.BOTH, sensitive)
					}
				}
				finals = finals or (1L shl leaving.size)
				leaving += null
			}

			return Nfa(leaving.toTypedArray(), anyLevels, finals, starts)
		}
	}
}

// ---- the walk ---------------------------------------------------------------------------------------------------

/**
 * One walk of a slice of a block: the folders it is in, as a stack of levels from the top (level 0 is `/`, before any
 * ancestor), and each [AncestralCheck]'s state at each. The one place a search keeps anything that changes, and it
 * is one thread's.
 */
private class Walk(
	val block: Block,
	private val hidden: Ranges,
	private val checks: Array<Check>,
	private val ranker: Ranker,
) {
	/** An array, as [checks] is: gone through for every folder the walk enters. */
	private val ancestral: Array<AncestralCheck> =
		checks
			.filterIsInstance<AncestralCheck>()
			.toTypedArray()
	private val slots = (ancestral.maxOfOrNull { it.slot } ?: -1) + 1

	/** How many levels are on the stack. */
	var depth = 0
		private set

	private var ends = IntArray(INITIAL)
	private var entries = IntArray(INITIAL)
	private var aboveNames = arrayOfNulls<ByteArray>(INITIAL)
	private var states = Array(slots) { LongArray(INITIAL) }
	private var paths = arrayOfNulls<String>(INITIAL)

	/** Whether a name at each level, or one above it, starts with a dot. */
	private var hiddenAt = BooleanArray(INITIAL)

	/** An [AncestralCheck]'s state at [level]. */
	fun state(slot: Int, level: Int): Long = states[slot][level]

	/** The entries from [from] up to [to] that pass every check, each with its score, in the block's order. */
	fun run(from: Int, to: Int): Hits {
		// What is found is written where this thread keeps what it finds, and copied out once at the length it came
		// to: a list that grows would be copied each time it doubled, for every slice of every search.
		val found = FOUND.get()
		var count = 0

		depth = 0
		ends[0] = Int.MAX_VALUE
		hiddenAt[0] = false
		for (check in ancestral)
			states[check.slot][0] = check.initial
		for (name in block.above)
			push(-1, Int.MAX_VALUE, name, Fold.folded(name), 0, name.size, name)
		generateSequence(block.parent.getOrNull(from)?.takeIf { it >= 0 }) {
			block.parent[it].takeIf { p ->
				p >=
					0
			}
		}.toList()
			.asReversed()
			.filter { block.nameLength(it) > 0 }
			.forEach(::enter)

		var entry = from
		var range = hidden.after(entry)

		while (entry < to) {
			if (range < hidden.count && hidden.start(range) <= entry) {
				entry = hidden.end(range)
				range++
				continue
			}

			while (ends[depth] <= entry)
				depth--

			if (block.nameLength(entry) > 0 && passes(entry)) {
				found[count++] = Hit.of(entry, ranker.score(entry, depth + 1, hiddenAt[depth])).packed
			}

			if (block.end[entry] > entry + 1 && block.nameLength(entry) > 0)
				enter(entry)
			entry++
		}

		return Hits.of(found, count)
	}

	/** Whether [entry] passes every check: run for every entry of the block, so it makes nothing. */
	private fun passes(entry: Int): Boolean {
		for (check in checks)
			if (check.test(this, entry) == check.negated)
				return false

		return true
	}

	/** Into the folder [entry]. */
	private fun enter(entry: Int) =
		push(
			entry,
			block.end[entry],
			block.names,
			block.folded,
			block.nameAt[entry],
			block.nameAt[
				entry +
					1,
			],
			null,
		)

	private fun push(
		entry: Int,
		end: Int,
		original: ByteArray,
		folded: ByteArray,
		from: Int,
		to: Int,
		above: ByteArray?,
	) {
		if (depth + 1 == ends.size)
			grow()
		depth++
		ends[depth] = end
		entries[depth] = entry
		aboveNames[depth] = above
		paths[depth] = null
		hiddenAt[depth] = hiddenAt[depth - 1] || (to > from && original[from] == DOT)
		for (check in ancestral)
			states[check.slot][depth] =
				check.step(states[check.slot][depth - 1], original, folded, from, to)
	}

	private fun grow() {
		val size = ends.size * 2

		ends = ends.copyOf(size)
		entries = entries.copyOf(size)
		aboveNames = aboveNames.copyOf(size)
		paths = paths.copyOf(size)
		hiddenAt = hiddenAt.copyOf(size)
		states = Array(slots) { states[it].copyOf(size) }
	}

	/** The path of [entry], as text: for a regex over paths. */
	fun pathOf(entry: Int): String =
		pathAt(depth) + "/" +
			String(block.names, block.nameAt[entry], block.nameLength(entry), Charsets.UTF_8)

	private fun pathAt(level: Int): String =
		if (level == 0)
			""
		else
			paths[level] ?: (
				pathAt(level - 1) + "/" +
					(
						aboveNames[level]?.let { String(it, Charsets.UTF_8) }
							?: String(
								block.names,
								block.nameAt[entries[level]],
								block.nameLength(entries[level]),
								Charsets.UTF_8,
							)
					)
			).also { paths[level] = it }

	private companion object {
		const val INITIAL = 32
		const val DOT = '.'.code.toByte()

		/**
		 * Where each thread that walks writes what a slice finds, before it is copied out at its own length: as long
		 * as the longest slice, and kept from one search to the next, so that walking makes nothing but what it
		 * found.
		 */
		val FOUND: ThreadLocal<LongArray> = ThreadLocal.withInitial { LongArray(Plan.SLICE) }
	}
}

// ---- the checks -------------------------------------------------------------------------------------------------

/** One term, made for one block: whether an entry passes it (before [negated] is taken into account). */
private abstract class Check(
	val negated: Boolean,
) {
	/** How costly it is, for the cheap ones to be run first: every check must pass, so the order changes nothing else. */
	abstract val cost: Int

	abstract fun test(walk: Walk, entry: Int): Boolean
}

/** A check that looks at an entry's ancestors, keeping a state at each level of a walk's stack, in its [slot]. */
private abstract class AncestralCheck(
	val slot: Int,
	negated: Boolean,
) : Check(negated) {
	/** The state at the top, before any ancestor. */
	abstract val initial: Long

	/** The state at a folder named as [original] (or [folded]) from [from] to [to], from its state at the folder above. */
	abstract fun step(
		state: Long,
		original: ByteArray,
		folded: ByteArray,
		from: Int,
		to: Int,
	): Long
}

private class NameCheck(
	private val matching: Matching,
	private val part: NamePart,
	negated: Boolean,
) : Check(negated) {
	override val cost: Int = if (matching.match is LiteralMatch) 3 else 4

	override fun test(walk: Walk, entry: Int): Boolean =
		matches(matching, part, walk.block, entry)

	companion object {
		fun matches(matching: Matching, part: NamePart, block: Block, entry: Int): Boolean {
			val start = block.nameAt[entry]
			val end = block.nameAt[entry + 1]
			val stem = start + block.stem[entry]

			val from =
				when (part) {
					NamePart.Stem, NamePart.Whole -> start
					// The extension, from its dot; all of a name that begins with a dot, which is all stem.
					NamePart.Extension ->
						if (start < end && block.names[start] == DOT)
							start
						else
							stem
				}

			val to =
				when (part) {
					NamePart.Stem -> stem
					NamePart.Whole, NamePart.Extension -> end
				}

			return matching.match.matches(
				if (matching.folded)
					block.folded
				else
					block.names,
				from,
				to,
			)
		}
	}
}

/** A path with a trailing `/`: some ancestor's path is what the pattern leads to. */
private class UnderCheck(
	private val nfa: Nfa,
	slot: Int,
	negated: Boolean,
) : AncestralCheck(slot, negated) {
	override val cost: Int = 2

	override val initial: Long = nfa.initial or (if (nfa.accepts(nfa.initial)) UNDER else 0)

	override fun step(
		state: Long,
		original: ByteArray,
		folded: ByteArray,
		from: Int,
		to: Int,
	): Long {
		val next = nfa.step(state and UNDER.inv(), original, folded, from, to)

		return next or (state and UNDER) or (if (nfa.accepts(next)) UNDER else 0)
	}

	override fun test(walk: Walk, entry: Int): Boolean =
		walk.state(slot, walk.depth) and UNDER != 0L

	private companion object {
		/** An ancestor's path matched: everything below is under it. */
		const val UNDER = 1L shl 62
	}
}

/** A path ending at an entry: the parent's path is what the pattern's levels lead to, and the name matches its last. */
private class PathEntryCheck(
	private val nfa: Nfa,
	private val matching: Matching,
	private val part: NamePart,
	slot: Int,
	negated: Boolean,
) : AncestralCheck(slot, negated) {
	override val cost: Int = 4

	override val initial: Long = nfa.initial

	override fun step(
		state: Long,
		original: ByteArray,
		folded: ByteArray,
		from: Int,
		to: Int,
	): Long = nfa.step(state, original, folded, from, to)

	override fun test(walk: Walk, entry: Int): Boolean =
		nfa.accepts(walk.state(slot, walk.depth)) &&
			NameCheck.matches(matching, part, walk.block, entry)
}

/**
 * `dir:`: an ancestor named so (or at such a path), from [min] to [max] levels up. Each level's state says whether
 * that ancestor is one ([HERE]), and whether it or any above it is ([SO_FAR]), so a depth without a limit is one look.
 */
private class DirCheck(
	private val target: Target,
	private val min: Int,
	private val max: Int?,
	slot: Int,
	negated: Boolean,
) : AncestralCheck(slot, negated) {
	sealed interface Target {
		val initial: Long

		/** The state at a folder, from the state above, with [HERE] and [SO_FAR] left out. */
		fun step(
			state: Long,
			original: ByteArray,
			folded: ByteArray,
			from: Int,
			to: Int,
		): Long

		fun isOne(state: Long): Boolean
	}

	/** A folder whose whole name is a pattern. */
	class Named(
		private val matching: Matching,
	) : Target {
		override val initial: Long = 0

		override fun step(
			state: Long,
			original: ByteArray,
			folded: ByteArray,
			from: Int,
			to: Int,
		): Long =
			if (matching.match.matches(if (matching.folded) folded else original, from, to))
				ONE
			else
				0

		override fun isOne(state: Long): Boolean = state and ONE != 0L

		private companion object {
			const val ONE = 1L
		}
	}

	/** A folder at a path. */
	class Located(
		private val nfa: Nfa,
	) : Target {
		override val initial: Long = nfa.initial

		override fun step(
			state: Long,
			original: ByteArray,
			folded: ByteArray,
			from: Int,
			to: Int,
		): Long = nfa.step(state, original, folded, from, to)

		override fun isOne(state: Long): Boolean = nfa.accepts(state)
	}

	override val cost: Int = 2

	override val initial: Long = target.initial

	override fun step(
		state: Long,
		original: ByteArray,
		folded: ByteArray,
		from: Int,
		to: Int,
	): Long {
		val next = target.step(state and FLAGS.inv(), original, folded, from, to)
		val here = target.isOne(next)

		return next or (if (here) HERE else 0) or
			(if (here || state and SO_FAR != 0L) SO_FAR else 0)
	}

	override fun test(walk: Walk, entry: Int): Boolean {
		// The ancestor `d` levels up is at level depth - d + 1.
		val nearest = walk.depth - min + 1

		if (nearest < 1)
			return false

		if (max == null)
			return walk.state(slot, nearest) and SO_FAR != 0L

		return (maxOf(1, walk.depth - max + 1)..nearest).any {
			walk.state(slot, it) and HERE !=
				0L
		}
	}

	private companion object {
		const val HERE = 1L shl 61
		const val SO_FAR = 1L shl 62
		const val FLAGS = HERE or SO_FAR
	}
}

/** `ext:`: some extension, after any `.` but a leading one, matches. */
private class ExtCheck(
	private val matching: Matching,
	negated: Boolean,
) : Check(negated) {
	override val cost: Int = if (matching.match is RegexMatch) 8 else 2

	override fun test(walk: Walk, entry: Int): Boolean {
		val block = walk.block
		val names =
			if (matching.folded)
				block.folded
			else
				block.names
		val end = block.nameAt[entry + 1]

		for (dot in block.nameAt[entry] + 1 until end)
			if (block.names[dot] == DOT && matching.match.matches(names, dot + 1, end))
				return true

		return false
	}
}

/** `type:`: some extension is one of a group's, each packed into a number ([Plan.packed]). */
private class TypeCheck(
	private val extensions: LongArray,
	negated: Boolean,
) : Check(negated) {
	override val cost: Int = 2

	override fun test(walk: Walk, entry: Int): Boolean {
		val block = walk.block
		val end = block.nameAt[entry + 1]

		for (dot in block.nameAt[entry] + 1 until end)
			if (block.names[dot] == DOT) {
				val packed = Plan.packed(block.folded, dot + 1, end)

				if (packed != null && java.util.Arrays.binarySearch(extensions, packed) >= 0)
					return true
			}

		return false
	}
}

private class IsCheck(
	private val flags: Set<Flag>,
	negated: Boolean,
) : Check(negated) {
	override val cost: Int = 1

	override fun test(walk: Walk, entry: Int): Boolean {
		val block = walk.block
		val kind = block.kindOf(entry)

		return flags.any {
			when (it) {
				Flag.Hidden -> block.names[block.nameAt[entry]] == DOT
				Flag.File -> kind.isFile
				Flag.Dir -> kind.isDirectory
				Flag.Symlink -> kind.isLink
			}
		}
	}
}

/** `recent:`: one of the entries picked since then, as this block holds them. */
private class RecentCheck(
	private val picked: BitSet?,
	negated: Boolean,
) : Check(negated) {
	override val cost: Int = 1

	override fun test(walk: Walk, entry: Int): Boolean = picked?.get(entry) == true
}

/** `new:`: first seen since then; what was there when its root was indexed was never seen to turn up. */
private class NewCheck(
	private val since: Long,
	negated: Boolean,
) : Check(negated) {
	override val cost: Int = 1

	override fun test(walk: Walk, entry: Int): Boolean =
		walk.block.seen[entry].let {
			it != Seen.UNKNOWN && (it.toLong() and 0xFFFFFFFFL) >= since
		}
}

/** `mount:`: on one of the block's devices whose type matches. */
private class MountCheck(
	private val devices: BooleanArray,
	negated: Boolean,
) : Check(negated) {
	override val cost: Int = 1

	override fun test(walk: Walk, entry: Int): Boolean =
		devices[
			walk.block.device[entry].toInt() and
				0xFFFF,
		]
}

/** `r/.../`: found in the name, the stem, or the path. */
private class RegexCheck(
	private val pattern: Pattern,
	private val on: RegexOn,
	negated: Boolean,
) : Check(negated) {
	override val cost: Int = if (on == RegexOn.Path) 10 else 8

	override fun test(walk: Walk, entry: Int): Boolean {
		val block = walk.block
		val from = block.nameAt[entry]
		val text =
			when (on) {
				RegexOn.Name -> String(block.names, from, block.nameLength(entry), Charsets.UTF_8)
				RegexOn.Stem -> String(block.names, from, block.stem[entry].toInt(), Charsets.UTF_8)
				RegexOn.Path -> walk.pathOf(entry)
			}

		return pattern.matcher(text).find()
	}
}

private const val DOT = '.'.code.toByte()
