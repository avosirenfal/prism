package prism.shared

import java.time.Duration
import java.time.Instant
import kotlin.math.pow

/*
 * How much things are used, and how lately, as one number each: every use adds one to a thing's weight, and the
 * weight halves every two weeks, so what is used every day outweighs what was used often last year, and no list of
 * every use is kept. What the launcher puts its applications in order by, and what the folders gone to most are
 * found by.
 */

/** A thing's uses: their [weight] as it was [at], when it was last used. */
data class Used(
	val weight: Double,
	val at: Instant,
) {
	/** What the uses weigh at [now], halved for every [HALF_LIFE] since. */
	fun weightAt(now: Instant): Double {
		val age = Duration.between(at, now).seconds.coerceAtLeast(0)

		return weight * 0.5.pow(age.toDouble() / HALF_LIFE.seconds)
	}

	companion object {
		val HALF_LIFE: Duration = Duration.ofDays(14)
	}
}

/** How much each of some things has been used, by its key. A value: a use makes another. */
data class Uses<K>(
	val uses: Map<K, Used> = emptyMap(),
) {
	/** How much [key] has been used, as of [now]; nothing, for one never used. */
	fun weightOf(key: K, now: Instant): Double = uses[key]?.weightAt(now) ?: 0.0

	/** These, with [key] used once more, [at]. */
	fun used(key: K, at: Instant): Uses<K> =
		Uses(uses + (key to Used(weightOf(key, at) + 1, at)))

	/** The [count] used most as of [now], the most used first, of those [admitted]. */
	fun most(
		count: Int,
		now: Instant,
		admitted: (K) -> Boolean = { true },
	): List<Pair<K, Used>> =
		uses.entries
			.asSequence()
			.filter { admitted(it.key) }
			.sortedByDescending { it.value.weightAt(now) }
			.take(count)
			.map { it.key to it.value }
			.toList()

	/** These, without all but the [most] used as of [now]: what keeps what is kept of them from growing for ever. */
	fun capped(most: Int, now: Instant): Uses<K> =
		if (uses.size <= most)
			this
		else
			Uses(most(most, now).toMap())

	companion object {
		/**
		 * Uses as a file: a line a thing, its weight, the second it was last used, and its key as [written] writes
		 * it, between tabs. A line that does not say one ([read] gives no key for it) is left out.
		 */
		fun <K> format(read: (String) -> K?, written: (K) -> String): Format<Uses<K>> =
			Format.lines(
				Uses(),
				read = { lines ->
					Uses(
						lines
							.mapNotNull { line ->
								val parts = line.split('\t', limit = 3)
								val weight = parts.getOrNull(0)?.toDoubleOrNull()
								val at = parts.getOrNull(1)?.toLongOrNull()
								val key = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(read)

								if (weight == null || at == null || key == null)
									null
								else
									key to Used(weight, Instant.ofEpochSecond(at))
							}.toMap(),
					)
				},
				written = { kept ->
					kept.uses.map { (key, used) ->
						"${used.weight}\t${used.at.epochSecond}\t${written(key)}"
					}
				},
			)
	}
}
