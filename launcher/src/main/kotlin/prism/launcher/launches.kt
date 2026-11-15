package prism.launcher

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.math.ln
import kotlin.math.pow
import prism.shared.Format
import prism.shared.Stored

/*
 * What has been started from the launcher, and how lately: what puts the applications a person uses before the ones
 * they do not. Each entry's uses are one weight that halves every two weeks, as the index's usage keeps a file's
 * picks, so an application started every day outweighs one started often last year, and no list of every start is
 * kept. Kept in a file of its own (`$XDG_STATE_HOME/prism/launches`), a line an entry: it is state worth keeping, not
 * a setting, and not the index's.
 */

/** One entry's uses: their [weight] as it was [at]. */
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

/** What has been started from the launcher, by each entry's key. */
data class Launches(
	val uses: Map<String, Used>,
) {
	/** How much the entry of [key] has been used, as of [now]; nothing, for one never used. */
	fun weightOf(key: String, now: Instant): Double = uses[key]?.weightAt(now) ?: 0.0

	/** What its uses add to how well an entry fits what is typed: for each doubling, up to [MOST]. */
	fun pointsOf(key: String, now: Instant): Int =
		minOf(MOST, (PER_DOUBLING * ln(1 + weightOf(key, now)) / ln(2.0)).toInt())

	/** These, with the entry of [key] used once more, [at]. */
	fun used(key: String, at: Instant): Launches =
		Launches(uses + (key to Used(weightOf(key, at) + 1, at)))

	companion object {
		val NONE = Launches(emptyMap())

		private const val PER_DOUBLING = 20
		private const val MOST = 60
	}
}

/**
 * Where the launches are kept: a value kept in a file ([Stored]), or in memory alone, read as the launcher opens and
 * added to as something is started.
 */
typealias LaunchStore = Stored<Launches>

/** What has been started, as it is now. */
val LaunchStore.launches: Launches get() = value.value

/** The entry of [key] started, [at]. */
fun LaunchStore.used(key: String, at: Instant) {
	update { used(key, at) }
}

/** Launches kept in memory alone: a launcher run without a file, and the tests. */
@Suppress("FunctionName")
fun MemoryLaunches(first: Launches = Launches.NONE): LaunchStore = Stored.inMemory(first)

/**
 * Launches kept in [file] ([LaunchFormat]): each use is added to the file as it then is. A file that cannot be read
 * is no launches; one that cannot be written leaves them in memory, and says so to [report].
 */
@Suppress("FunctionName")
fun LaunchFile(
	file: Path,
	report: (String, Throwable) -> Unit = { _, _ -> },
): LaunchStore =
	Stored.of(
		file,
		LaunchFormat,
	) { report("keeping what the launcher started in $file", it) }

/**
 * Launches as a file: a line an entry, its weight, the second it was last used, and its key, between tabs. A line
 * that does not say an entry is left out.
 */
val LaunchFormat: Format<Launches> =
	Format.lines(
		Launches.NONE,
		read = { lines ->
			Launches(
				lines
					.mapNotNull { line ->
						val parts = line.split('\t', limit = 3)
						val weight = parts.getOrNull(0)?.toDoubleOrNull()
						val at = parts.getOrNull(1)?.toLongOrNull()
						val key = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }

						if (weight == null || at == null || key == null)
							null
						else
							key to Used(weight, Instant.ofEpochSecond(at))
					}.toMap(),
			)
		},
		written = { launches ->
			launches.uses.map { (key, used) -> "${used.weight}\t${used.at.epochSecond}\t$key" }
		},
	)
