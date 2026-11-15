package prism.app

import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import prism.browser.Finder
import prism.daemon.LiveIndex
import prism.daemon.LiveSearch
import prism.index.SearchContext
import prism.index.Searched
import prism.index.Sighting
import prism.index.Usage
import prism.index.UsageFile
import prism.index.Where
import prism.index.query.Query

/**
 * What a window of the daemon's searches: the daemon's own index, as it is at the moment of each search, so a file
 * made a moment ago is found. A search that names a place the rules leave out (`/nix/store/`, `~/.cache/`) has it read
 * for it, and waits for that a little ([patience]) before it answers, so that the first search of such a place finds
 * what is in it rather than nothing.
 *
 * What is picked ranks higher from then on, in every window, and is written to the usage's [file] as it is picked, so
 * that it does after the daemon is started again too.
 */
internal class LiveFinder(
	private val live: LiveIndex,
	private val home: Path,
	usage: Usage,
	/** Where the usage is kept; null to keep it in memory alone. */
	private val file: Path?,
	private val patience: Duration = 5.seconds,
) : Finder {
	private val usage = AtomicReference(usage)

	override fun find(query: Query): Searched {
		fun searched() = live.search(query, SearchContext(home = home, usage = usage.get()))

		val first = searched()

		val found =
			if (first is LiveSearch.Found && first.reading.isNotEmpty()) {
				try {
					first.ready.get(patience.inWholeMilliseconds, TimeUnit.MILLISECONDS)
				} catch (slow: TimeoutException) {
					// What has been read by now is what is found: the place is still being read, for the next search.
				} catch (failed: ExecutionException) {
					Report.problem(
						"reading ${first.reading.joinToString()} for a search failed",
						failed.cause ?: failed,
					)
				}
				searched()
			} else {
				first
			}

		return when (found) {
			is LiveSearch.Refused -> Searched.Refused(found.why)
			is LiveSearch.Found -> Searched.Found(found.results)
		}
	}

	override fun newest(count: Int, named: (String) -> Boolean): List<Sighting> =
		live.index.newestFiles(count, named)

	override fun picked(paths: List<Path>, at: Instant) {
		val now = usage.updateAndGet { it.picked(paths.map(Where::of), at) }

		try {
			file?.let { UsageFile.write(it, now.pruned(at)) }
		} catch (failed: IOException) {
			Report.problem("writing what was picked to $file failed", failed)
		}
	}
}
