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
import prism.browser.IndexReading
import prism.daemon.LiveIndex
import prism.daemon.LiveSearch
import prism.index.ActivityLog
import prism.index.FileId
import prism.index.Hiding
import prism.index.Keeping
import prism.index.SearchContext
import prism.index.Searched
import prism.index.Sighting
import prism.index.Usage
import prism.index.UsageFile
import prism.index.Where
import prism.index.query.Query
import prism.shared.Stored

/**
 * What a window of the daemon's searches: the daemon's own index, as it is at the moment of each search, so a file
 * made a moment ago is found. A search that names a place the rules leave out (`/nix/store/`, `~/.cache/`) has it read
 * for it, and waits for that a little ([patience]) before it answers, so that the first search of such a place finds
 * what is in it rather than nothing.
 *
 * What is picked ranks higher from then on, in every window, and is written to the usage's file as it is picked, so
 * that it does after the daemon is started again too.
 */
internal class LiveFinder(
	private val live: LiveIndex,
	private val home: Path,
	/** What was picked, and where it is kept ([UsageFile.open]), or kept in memory alone. */
	private val usage: Stored<Usage>,
	private val patience: Duration = 5.seconds,
	/** What searches leave out of what they find, as it is at each search. */
	private val hiding: () -> Hiding = { Hiding.NONE },
	/** How the index is kept as the disk changes, as the daemon says it now. */
	private val kept: () -> Keeping? = { null },
	override val activity: ActivityLog? = null,
) : Finder {
	override fun find(query: Query): Searched {
		fun searched() =
			live.search(
				query,
				SearchContext(home = home, usage = usage.value.value, hiding = hiding()),
			)

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

	override fun reading(): IndexReading? =
		live.indexing?.let { IndexReading(it.listed, it.held) }

	override val rebuilds: Boolean get() = true

	override fun rebuild() {
		live.rescanRoots().whenComplete { _, failed ->
			if (failed != null)
				Report.problem("reading the index anew failed", failed)
		}
	}

	override fun keeping(): Keeping? = kept()

	override fun folderAt(folder: FileId): String? =
		live.index
			.foldersAt(folder)
			.firstOrNull()
			?.toString()

	override fun newest(count: Int, named: (String) -> Boolean): List<Sighting> =
		live.index.newestFiles(count, hiding(), named)

	override fun picked(paths: List<Path>, at: Instant) {
		usage.update { picked(paths.map(Where::of), at).pruned(at) }
	}
}
