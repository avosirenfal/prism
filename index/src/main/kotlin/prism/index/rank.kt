package prism.index

import java.time.Duration
import java.time.Instant
import kotlin.math.ln
import kotlin.math.pow

/*
 * How a search orders what it finds, best first: each result is given a score as the walk finds it, from what is known
 * of it without reading the disk, and the results are put in order of their scores at the end (a counting sort, since
 * a score is a small number, so ordering a million results is a pass over them).
 *
 * What counts, roughly from most to least:
 *
 * - How each word of the query matches the name: the whole name (or its stem) is the word, the name starts with it,
 *   a word of the name starts with it (after `-`, `_`, `.`, a space, a digit, or a capital in camelCase), or it is
 *   only somewhere inside.
 * - How much the user has used it, by picking it, and how much they have used the folder it is in ([Usage]).
 * - How new it is: when the index first saw it, if that was after its root was first indexed.
 * - Where it is: under the home folder; shallow rather than deep; not hidden (no name on its path starts with a dot).
 *   Among results named exactly what was asked for, the shallower comes first, before how new or how used.
 *
 * The weights are [Ranking]'s, in one place, to be tuned.
 */

/**
 * What each thing that counts towards a result's place is worth, in points. The defaults make a better match of the
 * name worth more than anything but heavy use, and heavy use of a file worth more than one step of its name's match.
 */
data class Ranking(
	/** For each step a word's match of the name is better: inside, at a word's start, at the name's start, all of it. */
	val perTier: Int = 400,
	/** For each doubling of how much a result has been used. */
	val perUse: Int = 200,
	val mostForUse: Int = 600,
	/** For each doubling of how much the folder a result is in has been used. */
	val perFolderUse: Int = 120,
	val mostForFolderUse: Int = 400,
	/** For an entry seen just now, halving for every [newHalfLife] since. */
	val new: Int = 300,
	val newHalfLife: Duration = Duration.ofDays(3),
	val underHome: Int = 200,
	/** Taken away for each level of a result's path, up to [mostForDepth]. */
	val perLevel: Int = 12,
	val mostForDepth: Int = 150,
	/**
	 * Taken away for each level of the path of a result named exactly what was asked for, for up to [levelsCounted]
	 * of them: among such results the one nearer the top comes first, whatever else there is to say of where they
	 * are ([Ranker.score]). Less in all than [perTier], so that none of them falls below a lesser match of the name.
	 */
	val perLevelExact: Int = 32,
	val levelsCounted: Int = 12,
	/** Taken away where any name on a result's path starts with a dot. */
	val hidden: Int = 250,
) {
	companion object {
		/** The most words of a query whose match counts. */
		internal const val WORDS = 3

		/** What every score starts from, so that what is taken away leaves it above zero. */
		internal const val BASE = 1000

		/** The highest score: beyond what the defaults reach, so they never meet it. */
		internal const val MOST = 8191
	}
}

/** How a word matches a name, from worst to best: each step is worth [Ranking.perTier]. */
internal enum class Tier {
	Inside,
	WordStart,
	Prefix,
	Whole,
	;

	/** How many steps it is worth, the worst one. */
	val steps: Int get() = ordinal + 1
}

/**
 * Points by entry, for the few entries that have any: looked up for every result of a search, so by halving a sorted
 * array of the entries, with no number boxed; nothing, for an entry that has none.
 */
internal class Points private constructor(
	private val entries: IntArray,
	private val points: IntArray,
) {
	operator fun get(entry: Int): Int {
		if (entries.isEmpty())
			return 0

		val at = java.util.Arrays.binarySearch(entries, entry)

		return if (at >= 0)
			points[at]
		else
			0
	}

	companion object {
		/** The points [of] says, each entry's once. */
		fun of(of: Map<Int, Int>): Points {
			val sorted = of.entries.sortedBy { it.key }

			return Points(
				IntArray(sorted.size) { sorted[it].key },
				IntArray(sorted.size) { sorted[it].value },
			)
		}
	}
}

/** A word of the query whose match of a name counts: its bytes, folded where the search does not tell case. */
internal class Word(
	val needle: ByteArray,
	val folded: Boolean,
)

/**
 * The scores of one block's entries: what is known of the block worked out once ([of]), and each entry found scored
 * from it with what the walk knows of where the entry is ([score]).
 */
internal class Ranker private constructor(
	private val block: Block,
	private val words: Array<Word>,
	private val ranking: Ranking,
	/** The points for each entry used, by entry. */
	private val used: Points,
	/** The points for each entry in a folder used, by the folder's entry. */
	private val inUsed: Points,
	/** The points for the root's own folder, which is outside the block: a graft's. */
	private val rootInUsed: Int,
	/** The entries under the home folder: from the first up to the one after the last. */
	private val home: IntRange,
	private val now: Long,
) {
	/**
	 * [entry]'s score, [levels] deep (how many names its path has), under a hidden folder or not. Run for every
	 * result of a search, a million of them for a broad one, so it makes nothing: no list is gone through by an
	 * iterator, and no number is boxed to be looked up.
	 */
	fun score(entry: Int, levels: Int, hiddenAbove: Boolean): Int {
		var name = 0
		var exact = words.isNotEmpty()

		for (word in words) {
			val tier = tierOf(word, entry)

			name += tier * ranking.perTier
			exact = exact && tier == Tier.Whole.steps
		}

		val folder =
			if (entry == 0)
				rootInUsed
			else
				inUsed[block.parent[entry]]
		val hidden = hiddenAbove || block.names[block.nameAt[entry]] == DOT
		val seen = block.seen[entry]

		val new =
			if (seen == Seen.UNKNOWN) {
				0
			} else {
				val age = (now - (seen.toLong() and 0xFFFFFFFFL)).coerceAtLeast(0)

				(ranking.new * 0.5.pow(age.toDouble() / ranking.newHalfLife.seconds)).toInt()
			}

		val place =
			(if (entry in home) ranking.underHome else 0) -
				(if (hidden) ranking.hidden else 0)

		// Named exactly what was asked for (`prism/`, of every folder named prism): many are, and the one meant is
		// the one nearest the top. So each level counts for more than all there is to say of how new an entry is
		// and how used its folder: those are pressed into less than one level is worth, and only tell apart
		// entries as deep as each other.
		val score =
			if (exact) {
				val within =
					(folder + new) * (ranking.perLevelExact - 1) /
						(ranking.mostForFolderUse + ranking.new)

				Ranking.BASE + name + used[entry] + place + minOf(within, ranking.perLevelExact - 1) -
					ranking.perLevelExact * minOf(levels, ranking.levelsCounted)
			} else {
				Ranking.BASE + name + used[entry] + folder + new + place -
					minOf(ranking.mostForDepth, ranking.perLevel * levels)
			}

		return score.coerceIn(0, Ranking.MOST)
	}

	/** How many steps [word]'s match of [entry]'s name is worth ([Tier], counted from one); none where it is not in it. */
	private fun tierOf(word: Word, entry: Int): Int {
		val bytes =
			if (word.folded)
				block.folded
			else
				block.names
		val needle = word.needle
		val from = block.nameAt[entry]
		val to = block.nameAt[entry + 1]
		val stem = from + block.stem[entry]

		if (needle.size > to - from)
			return 0

		val starts =
			java.util.Arrays.equals(
				bytes,
				from,
				from + needle.size,
				needle,
				0,
				needle.size,
			)

		return when {
			starts && (needle.size == to - from || needle.size == stem - from) -> Tier.Whole.steps
			starts -> Tier.Prefix.steps
			else -> inside(bytes, from, to, needle)
		}
	}

	/**
	 * How many steps the needle's match inside the name from [from] to [to] is worth: at a word's start anywhere, or
	 * only inside a word; none where it is not there.
	 */
	private fun inside(bytes: ByteArray, from: Int, to: Int, needle: ByteArray): Int {
		var found = 0

		for (start in from + 1..to - needle.size) {
			if (!java.util.Arrays.equals(
					bytes,
					start,
					start + needle.size,
					needle,
					0,
					needle.size,
				)
			)
				continue
			if (startsWord(block.names, from, start))
				return Tier.WordStart.steps
			found = Tier.Inside.steps
		}

		return found
	}

	companion object {
		private const val DOT = '.'.code.toByte()

		fun of(
			block: Block,
			hidden: Ranges,
			words: List<Word>,
			usage: Usage,
			ranking: Ranking,
			home: Where,
			now: Instant,
		): Ranker {
			fun points(weight: Double, per: Int, most: Int): Int =
				minOf(most, (per * ln(1 + weight) / ln(2.0)).toInt())

			val held =
				usage.places
					.filterKeys { it.within(block.root) }
					.mapNotNull { (where, used) ->
						block.locate(where, hidden)?.let { it to used.weightAt(now) }
					}

			val homeAt =
				when {
					block.root.within(home) -> 0 until block.size
					home.within(block.root) ->
						block.locate(home, hidden)?.let { it + 1 until block.end[it] } ?: IntRange.EMPTY
					else -> IntRange.EMPTY
				}

			return Ranker(
				block,
				words.take(Ranking.WORDS).toTypedArray(),
				ranking,
				Points.of(
					held.associate { (entry, weight) ->
						entry to points(weight, ranking.perUse, ranking.mostForUse)
					},
				),
				Points.of(
					held
						.filter { (entry, _) -> block.kindOf(entry) == Kind.Directory }
						.associate { (entry, weight) ->
							entry to points(weight, ranking.perFolderUse, ranking.mostForFolderUse)
						},
				),
				block.root.parent
					?.let { usage.weightOf(it, now) }
					?.let { points(it, ranking.perFolderUse, ranking.mostForFolderUse) } ?: 0,
				homeAt,
				now.epochSecond,
			)
		}

		/**
		 * Whether a word of the name from [from] starts at [at]: after a byte that is not a letter or a digit, at one
		 * that is neither itself (the dot of `.jpg`, where an extension starts), between a digit and a letter, or at a
		 * capital after a small letter (camelCase). Bytes beyond ASCII are letters.
		 */
		fun startsWord(name: ByteArray, from: Int, at: Int): Boolean {
			if (at <= from)
				return true

			val before = name[at - 1].toInt() and 0xFF
			val here = name[at].toInt() and 0xFF

			fun letter(byte: Int) = byte >= 0x80 || byte.toChar().isLetter()

			fun digit(byte: Int) = byte in '0'.code..'9'.code

			fun small(byte: Int) = byte in 'a'.code..'z'.code

			fun capital(byte: Int) = byte in 'A'.code..'Z'.code

			return when {
				!letter(before) && !digit(before) -> true
				!letter(here) && !digit(here) -> true
				digit(before) != digit(here) -> true
				small(before) && capital(here) -> true
				else -> false
			}
		}
	}
}
