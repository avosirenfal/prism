package prism.index

/*
 * What a search found, and the order it is given in. A search walks each placed block of an index and keeps, for each,
 * the entries that passed with their scores (a [Results.Part]); the results are all the parts' entries, put in order.
 *
 * Three different numbers say where a result is, and each is a type of its own so that none can be given for another:
 * its place among the results as they are given, best first (the index a caller asks by); its [Position] among every
 * part's entries laid end to end, which is what the order is an order of; and where it is within its own part. Which
 * part a position is in is worked out in one place ([Laid]), and an entry and its score are one number ([Hit]), so
 * the two can never be of different lengths or out of step.
 */

/** An entry a search found in a block, and its score: one number, the score above the entry. */
@JvmInline
internal value class Hit(
	val packed: Long,
) {
	/** Which entry of its block it is. */
	val entry: Int get() = packed.toInt()

	/** What it scored: what puts it where it is among the results ([Ranking]). */
	val score: Int get() = (packed ushr 32).toInt()

	companion object {
		fun of(entry: Int, score: Int): Hit =
			Hit((score.toLong() shl 32) or (entry.toLong() and 0xFFFFFFFFL))
	}
}

/** The entries a walk found in a block, each with its score, in the block's order. */
internal class Hits(
	private val packed: LongArray,
) {
	val size: Int get() = packed.size

	operator fun get(at: Int): Hit = Hit(packed[at])

	companion object {
		/** The first [count] of [found], as they are now: [found] is whoever found them's to write over. */
		fun of(found: LongArray, count: Int): Hits = Hits(found.copyOf(count))

		/** [slices], which are of one block and in its order, as one. */
		fun joined(slices: List<Hits>): Hits =
			slices.singleOrNull() ?: Hits(
				LongArray(slices.sumOf { it.size }).also { all ->
					slices.fold(0) { at, slice ->
						slice.packed.copyInto(all, at)
						at + slice.size
					}
				},
			)
	}
}

/**
 * A name's extension, in lower case, as one number: what follows the name's last dot, where that is not its first
 * byte and something follows it (`photo.JPG`'s is `jpg`, `archive.tar.gz`'s is `gz`, and `.gitignore` and `Makefile`
 * have [NONE]). Its bytes are the number's, the first the lowest, so two are the same extension exactly where they
 * are the same number, and a million names' extensions can be looked up without a text being made for any. One
 * longer than [LONGEST] bytes is [NONE] too: nothing known by its extension has one so long.
 */
@JvmInline
value class Extension private constructor(
	val packed: Long,
) {
	companion object {
		/** No extension, or one too long to be one anything is known by. */
		val NONE = Extension(0)

		/** The most bytes an extension has. */
		const val LONGEST = 8

		private const val DOT = '.'.code.toByte()

		/** The extension of the [name] of a file. */
		fun of(name: String): Extension =
			name.toByteArray().let { of(Fold.folded(it), 0, it.size) }

		/** The extension of the name that is [folded]'s bytes from [from] up to [to], which are in lower case. */
		internal fun of(folded: ByteArray, from: Int, to: Int): Extension {
			var dot = to - 1

			while (dot > from && folded[dot] != DOT)
				dot--

			if (dot <= from || dot == to - 1 || to - dot - 1 > LONGEST)
				return NONE

			var packed = 0L

			for (at in to - 1 downTo dot + 1)
				packed = (packed shl 8) or (folded[at].toLong() and 0xFF)

			return Extension(packed)
		}
	}
}

/** A result's place among every part's entries, laid end to end in the order the parts are in. */
@JvmInline
internal value class Position(
	val value: Int,
)

/**
 * Where each part's entries are among them all laid end to end: [sizes] are how many each part has, in order. A part
 * with none takes no room, so the part a position is in is never one of those, however many there are and wherever
 * they come.
 */
internal class Laid(
	sizes: List<Int>,
) {
	/** Where each part starts, and, after the last, where they all end. */
	private val starts: IntArray = sizes.runningFold(0, Int::plus).toIntArray()

	/** How many entries there are in all. */
	val total: Int get() = starts.last()

	/** The position of the [within]th entry of the [part]th part. */
	fun positionOf(part: Int, within: Int): Position = Position(starts[part] + within)

	/**
	 * What [read] makes of where [position] is: which part, and how far into it. The part is the last that starts at
	 * or before the position: the one after it starts past the position, so it holds it, and is not empty.
	 */
	inline fun <T> at(position: Position, read: (part: Int, within: Int) -> T): T {
		require(position.value in 0 until total) { "position ${position.value} of $total" }

		var low = 0
		var high = starts.size - 2

		while (low < high) {
			val middle = (low + high + 1) ushr 1

			if (starts[middle] <= position.value)
				low = middle
			else
				high = middle - 1
		}

		return read(low, position.value - starts[low])
	}
}

/**
 * The entries a search found, best first: by their scores ([Ranking]), and among equals by name, from A to Z but with
 * a name's numbers from the greatest down, so that names that differ by a date or a number (`backup_2026_11_13`,
 * `IMG_1002`) come the latest first; among equal names, by path. Each is made into an [Entry] when it is asked for, so
 * a search that finds a million entries keeps a few million numbers, not a million paths; [nameOf] and [kindOf] say
 * what a result is without making its path.
 */
class Results internal constructor(
	private val parts: List<Part>,
) : AbstractList<Entry>() {
	/** The entries found in one placed block of an index, each with its score, in the block's order. */
	internal class Part(
		val index: Index,
		val placed: Int,
		val hits: Hits,
	) {
		val block: Block get() = index.placed[placed].block
	}

	private val laid = Laid(parts.map { it.hits.size })

	/** Each result's position, best first. */
	private val order: IntArray = ranked()

	override val size: Int get() = laid.total

	override fun get(index: Int): Entry =
		at(index) { part, hit -> part.index.entryAt(part.placed, hit.entry) }

	/** The name of the [index]th result, as text. */
	fun nameOf(index: Int): String =
		at(index) { part, hit ->
			val block = part.block

			String(
				block.names,
				block.nameAt[hit.entry],
				block.nameLength(hit.entry),
				Charsets.UTF_8,
			)
		}

	fun kindOf(index: Int): Kind = at(index) { part, hit -> part.block.kindOf(hit.entry) }

	/** The [index]th result's extension, read from its name where it is kept: nothing is made. */
	fun extensionOf(index: Int): Extension =
		at(index) { part, hit ->
			val block = part.block

			Extension.of(block.folded, block.nameAt[hit.entry], block.nameAt[hit.entry + 1])
		}

	/** Whether the [index]th result's name starts with a dot. */
	fun startsWithDot(index: Int): Boolean =
		at(index) { part, hit ->
			val block = part.block

			block.nameLength(hit.entry) > 0 && block.names[block.nameAt[hit.entry]] == DOT
		}

	/** The [index]th result's score: what put it where it is. */
	fun scoreOf(index: Int): Int = at(index) { _, hit -> hit.score }

	/** What [read] makes of the [index]th result, best first: the part that found it, and what it found. */
	private inline fun <T> at(index: Int, read: (Part, Hit) -> T): T {
		if (index !in 0 until size)
			throw IndexOutOfBoundsException("$index of $size")

		return placed(Position(order[index]), read)
	}

	/** What [read] makes of the result at [position]: the part that found it, and what it found. */
	private inline fun <T> placed(position: Position, read: (Part, Hit) -> T): T =
		laid.at(position) { part, within -> read(parts[part], parts[part].hits[within]) }

	/**
	 * Every result's position, in order of score from the highest: a counting sort, which leaves equals in the order
	 * they are taken in, which is that of their paths, since a block holds its entries so. The first [EXACT] results
	 * are then put in the order of their names among equals ([byName]), which is a sort, and so is not done for every
	 * one of a million: past them, equals stay in the order of their paths.
	 */
	private fun ranked(): IntArray {
		val counts = IntArray(Ranking.MOST + 1)

		for (part in parts)
			for (found in 0 until part.hits.size)
				counts[part.hits[found].score]++

		// Where each score's results begin: the best score's first.
		val begins = IntArray(Ranking.MOST + 1)
		var at = 0

		for (score in Ranking.MOST downTo 0) {
			begins[score] = at
			at += counts[score]
		}

		val order = IntArray(at)
		val next = begins.copyOf()

		parts.forEachIndexed { which, part ->
			for (found in 0 until part.hits.size)
				order[next[part.hits[found].score]++] = laid.positionOf(which, found).value
		}

		// Equals, from the best down, until the first [EXACT] results are in the order of their names.
		var left = EXACT

		for (score in Ranking.MOST downTo 0) {
			val count = counts[score]

			if (left <= 0)
				break
			if (count > 1)
				byName(order, begins[score], begins[score] + count, score, left)
			left -= count
		}

		return order
	}

	/**
	 * The positions of [order] from [from] up to [to], which are those of [score], with the first [most] of them by
	 * name ([byNameOrder]; by path, among equal names) put first, in that order, and the rest after them as they were.
	 * Where there are more than [most], those are picked out in one pass over what was found ([firstByName]), each
	 * result looked at once against the last of those kept, which is why a million equals cost a million looks and
	 * not a sort.
	 */
	private fun byName(
		order: IntArray,
		from: Int,
		to: Int,
		score: Int,
		most: Int,
	) {
		/** Two positions in the order of their names, and of the positions themselves among equal names. */
		fun named(one: Int, other: Int): Int =
			placed(Position(one)) { a, aHit ->
				placed(Position(other)) { b, bHit ->
					val order = named(a, aHit.entry, b, bHit.entry)

					if (order != 0)
						order
					else
						one.compareTo(other)
				}
			}

		if (to - from <= most) {
			order
				.asList()
				.subList(from, to)
				.sortedWith(::named)
				.forEachIndexed { at, position -> order[from + at] = position }

			return
		}

		// What was found, in slices, each gone through on whatever core is free: the first of each by name, and then
		// the first of those.
		val slice = maxOf(SLICE, size / Runtime.getRuntime().availableProcessors() + 1)

		val slices =
			parts.indices.flatMap { at ->
				val found = parts[at].hits.size

				(0 until found step slice).map { Triple(at, it, minOf(it + slice, found)) }
			}

		val first =
			slices
				.parallelStream()
				.map { (at, start, end) -> firstByName(at, start, end, score, most) }
				.toList()
				.flatten()
				.sortedWith(::named)
				.take(most)

		val picked = java.util.BitSet(size).also { first.forEach(it::set) }
		val rest = IntArray(to - from - first.size)
		var filled = 0

		for (at in from until to)
			if (!picked[order[at]])
				rest[filled++] = order[at]

		first.forEachIndexed { at, position -> order[from + at] = position }
		rest.copyInto(order, from + first.size)
	}

	/**
	 * The positions of the first [most] by name of the results of [score] among those the [at]th part found from
	 * [start] up to [end], in no order. Run for every one of a million equals, so it keeps what it finds in an array
	 * twice as long as it needs, and cuts that back to the first [most] each time it fills ([cut]): a result that
	 * comes after the last of those is passed over with one look, and one that does not costs a few more, however the
	 * names happen to be ordered.
	 */
	private fun firstByName(
		at: Int,
		start: Int,
		end: Int,
		score: Int,
		most: Int,
	): List<Int> {
		val part = parts[at]
		val hits = part.hits

		// Two of this part's results, by where among them each is: by name, and by path among equal names.
		val order = { one: Int, other: Int ->
			val named = named(part, hits[one].entry, part, hits[other].entry)

			if (named != 0)
				named
			else
				one.compareTo(other)
		}

		val kept = IntArray(most * 2)
		var count = 0
		// The last of the first [most] found so far, once there have been more than that.
		var last = -1

		for (found in start until end) {
			if (hits[found].score != score || (last >= 0 && order(found, last) > 0))
				continue

			kept[count++] = found
			if (count == kept.size) {
				last = cut(kept, count, most, order)
				count = most
			}
		}
		if (count > most) {
			cut(kept, count, most, order)
			count = most
		}

		return List(count) { laid.positionOf(at, kept[it]).value }
	}

	/** The names of [aEntry] of [a]'s block and [bEntry] of [b]'s, in the order equals are given in. */
	private fun named(a: Part, aEntry: Int, b: Part, bEntry: Int): Int {
		val aBlock = a.block
		val bBlock = b.block

		return byNameOrder(
			aBlock.folded,
			aBlock.nameAt[aEntry],
			aBlock.nameAt[aEntry + 1],
			bBlock.folded,
			bBlock.nameAt[bEntry],
			bBlock.nameAt[bEntry + 1],
		)
	}

	companion object {
		/** Nothing found. */
		val NONE = Results(emptyList())

		/** The results of several searches as one, best first among them all: an index's and those of places beside it. */
		fun of(results: List<Results>): Results =
			// One search's results are in their order already: putting them in it again would be a second sort of
			// every one of them, which a search of an index with nothing beside it would pay for nothing.
			results.singleOrNull() ?: Results(results.flatMap { it.parts })

		/**
		 * How many of the first results are in the order of their names among equals, whatever was found: as many as
		 * anybody reads down a listing for the order's sake, and few enough that picking them out of a million equals
		 * is one look at each.
		 */
		internal const val EXACT = 1_000

		/** The fewest of a part's results that are worth a core of their own, for the first of them by name. */
		private const val SLICE = 1 shl 16

		/**
		 * The first [size] of [values] rearranged so that the first [first] of them are those that come first by
		 * [order], in no order among themselves; the last of those is given. A quickselect: it halves what it looks
		 * at each time round, so it looks at each value a few times, where sorting them would look at each a dozen.
		 */
		private fun cut(
			values: IntArray,
			size: Int,
			first: Int,
			order: (Int, Int) -> Int,
		): Int {
			val wanted = first - 1
			var low = 0
			var high = size - 1

			while (low < high) {
				val pivot = values[(low + high) ushr 1]
				var up = low
				var down = high

				while (up <= down) {
					while (order(values[up], pivot) < 0)
						up++
					while (order(values[down], pivot) > 0)
						down--
					if (up <= down) {
						val swapped = values[up]

						values[up++] = values[down]
						values[down--] = swapped
					}
				}

				when {
					wanted <= down -> high = down
					wanted >= up -> low = up
					else -> break
				}
			}

			return values[wanted]
		}

		/**
		 * The order of two names among results otherwise equal: byte by byte from the least, which is from A to Z,
		 * but a run of digits as the number it is, and from the greatest: `a_2026` before `a_2025` before `b_2027`,
		 * and `img_10` before `img_9`. So what differs by a date or a count comes the latest first, and everything
		 * else as a listing has it. Bytes are compared unsigned, which is the order of UTF-8's characters.
		 */
		internal fun byNameOrder(
			a: ByteArray,
			aFrom: Int,
			aTo: Int,
			b: ByteArray,
			bFrom: Int,
			bTo: Int,
		): Int {
			fun digit(byte: Byte) = byte in ZERO..NINE

			tailrec fun from(i: Int, j: Int): Int {
				if (i == aTo || j == bTo)
					return (aTo - i).compareTo(bTo - j)

				if (!digit(a[i]) || !digit(b[j]))
					return if (a[i] == b[j])
						from(i + 1, j + 1)
					else
						(a[i].toInt() and 0xFF).compareTo(b[j].toInt() and 0xFF)

				// Two numbers: without the zeros before them, the longer is the greater, and of two as long, the
				// one greater at the first digit they differ in.
				val aDigits = pastWhile(a, i, aTo, ::digit)
				val bDigits = pastWhile(b, j, bTo, ::digit)
				val aFirst = pastWhile(a, i, aDigits) { it == ZERO }
				val bFirst = pastWhile(b, j, bDigits) { it == ZERO }

				val greater =
					(aDigits - aFirst).compareTo(bDigits - bFirst).takeIf { it != 0 }
						?: java.util.Arrays.compare(a, aFirst, aDigits, b, bFirst, bDigits)

				// The greater number is the first.
				return if (greater != 0)
					-greater
				else
					from(aDigits, bDigits)
			}

			return from(aFrom, bFrom)
		}

		/** The first place from [from] up to [to] whose byte [holds] does not take; [to] where it takes them all. */
		private inline fun pastWhile(
			bytes: ByteArray,
			from: Int,
			to: Int,
			holds: (Byte) -> Boolean,
		): Int {
			var at = from

			while (at < to && holds(bytes[at]))
				at++

			return at
		}

		private const val DOT = '.'.code.toByte()
		private const val ZERO = '0'.code.toByte()
		private const val NINE = '9'.code.toByte()
	}
}
