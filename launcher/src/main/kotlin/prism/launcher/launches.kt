package prism.launcher

import java.nio.file.Path
import java.time.Instant
import kotlin.math.ln
import prism.shared.Format
import prism.shared.Loaded
import prism.shared.Parsed
import prism.shared.Stored
import prism.shared.Used
import prism.shared.Uses

/*
 * What has been started from the launcher, and how lately: what puts the applications a person uses before the ones
 * they do not. Each entry's uses are one weight that halves every two weeks, as the index's usage keeps a file's
 * picks, so an application started every day outweighs one started often last year, and no list of every start is
 * kept. Kept in a file of its own (`$XDG_STATE_HOME/prism/launches`), a line an entry: it is state worth keeping, not
 * a setting, and not the index's.
 */

/** What has been started from the launcher, by each entry's key ([Uses]: a weight each, halving as it ages). */
data class Launches(
	val kept: Uses<String>,
) {
	val uses: Map<String, Used> get() = kept.uses

	/** How much the entry of [key] has been used, as of [now]; nothing, for one never used. */
	fun weightOf(key: String, now: Instant): Double = kept.weightOf(key, now)

	/** What its uses add to how well an entry fits what is typed: for each doubling, up to [MOST]. */
	fun pointsOf(key: String, now: Instant): Int =
		minOf(MOST, (PER_DOUBLING * ln(1 + weightOf(key, now)) / ln(2.0)).toInt())

	/** These, with the entry of [key] used once more, [at]. */
	fun used(key: String, at: Instant): Launches = Launches(kept.used(key, at))

	companion object {
		val NONE = Launches(Uses())

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
	Uses.format<String>({ it }, { it }).let { uses ->
		object : Format<Launches> {
			override val none = Launches.NONE

			override fun read(bytes: ByteArray): Parsed<Launches> =
				when (val read = uses.read(bytes)) {
					is Loaded.Read -> Loaded.Read(Launches(read.value))
					is Loaded.Unreadable -> read
				}

			override fun written(value: Launches): ByteArray = uses.written(value.kept)
		}
	}
