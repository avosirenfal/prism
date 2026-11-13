package prism.preferences

/*
 * A list reordered: entries taken from where they are and put down together somewhere else, as a list dragged into
 * order is (the columns of a listing, quick access, a context menu). One entry moved is the same as several, of one.
 */

/**
 * Entries of a list moved: those at [from] (each once, in the list's order), put down together, in that order, so that
 * the first of them is at [to] once they are.
 */
data class Moved(
	val from: List<Int>,
	val to: Int,
) {
	init {
		require(from.isNotEmpty()) { "a move moves something" }
		require(
			from.zipWithNext().all { (a, b) ->
				a < b
			},
		) { "what is moved is in order, each once: $from" }
		require(from.first() >= 0 && to >= 0) { "a move is within the list: $from to $to" }
	}

	companion object {
		/** The entry at [from] moved so that it is at [to]. */
		fun of(from: Int, to: Int): Moved = Moved(listOf(from), to)
	}
}

/** These, with [move] made: what it moves taken out, and put back together at its place among the rest. */
fun <T> List<T>.moved(move: Moved): List<T> {
	val picked = move.from.filter { it in indices }
	val rest = filterIndexed { at, _ -> at !in picked }
	val at = move.to.coerceIn(0, rest.size)

	return rest.take(at) + picked.map(::get) + rest.drop(at)
}

/**
 * These, with [move] made among those of them [shown] (some of these, in their order, told apart by [key]): what it
 * moves goes, in its order, just after the one shown before where it lands, or just before the first shown where it
 * lands first; what is not shown stays where it is among the rest.
 */
fun <T, K> List<T>.movedAmong(shown: List<T>, key: (T) -> K, move: Moved): List<T> {
	val moving = move.from.mapNotNull { shown.getOrNull(it)?.let(key) }.toSet()
	val rest = filterNot { key(it) in moving }
	val restShown = shown.filterNot { key(it) in moving }

	fun placeOf(one: T): Int = rest.indexOfFirst { key(it) == key(one) }

	// Where every one shown is moved, none is passed: nothing is changed.
	if (restShown.isEmpty())
		return this

	val at =
		if (move.to <= 0)
			placeOf(restShown.first())
		else
			placeOf(restShown[(move.to - 1).coerceAtMost(restShown.lastIndex)]) + 1

	return rest.take(at) + filter { key(it) in moving } + rest.drop(at)
}
