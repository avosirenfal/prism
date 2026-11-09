package barion.app

import barion.cli.Args
import barion.cli.Command
import barion.cli.Outcome
import barion.cli.ParamType
import barion.index.IndexFile
import barion.index.IndexRead
import barion.index.IndexStore
import barion.index.Indexer
import barion.index.Searched
import barion.index.query.query
import barion.index.search
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.fileSize
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * `barion index`: folders indexed into the index's file (or the file read, where there is one), with what it holds,
 * how big the file is and how long each step took; and words searched for, as bare words of the search language, with
 * how long each search took. What the daemon will do when it starts, done once, to be seen.
 */
object IndexCommand : Command(
	"index",
	"index folders into the index's file, or read the one there is, and say what it holds and how long it took",
) {
	private val roots =
		arguments(
			"PATH",
			ParamType.FOLDER,
			help = "the folders to index where there is no file yet (/ unless named)",
		)

	private val file =
		option(
			"--file",
			type = ParamType.PATH,
			default = defaultFile(),
			help = "the index's file",
			shown = "\$XDG_CACHE_HOME/barion/index.barion",
		)

	private val fresh =
		flag(
			"--fresh",
			help = "index anew, even where there is a file already",
		)

	private val threads =
		option(
			"--threads",
			type = ParamType.COUNT,
			default = Runtime.getRuntime().availableProcessors(),
			help = "how many threads read directories at once",
			shown = "every core",
		)

	private val words =
		repeated(
			"--find",
			type = ParamType.TEXT.named("WORD"),
			help = "a bare word to search for: report, *.jpg, img_[0-9]*",
		)

	override fun run(args: Args): Outcome {
		val file = args[file]

		if (args[fresh])
			Files.deleteIfExists(file)

		val lines = ArrayList<String>()
		val roots = args[roots].ifEmpty { listOf(Path("/")) }
		val (store, opening) =
			measureTimedValue {
				IndexStore.open(file, roots, Indexer(threads = args[threads]))
			}
		val index = store.index

		lines +=
			"${how(
				store.origin,
			)} in ${millis(
				opening,
			)}: ${"%,d".format(index.size)} entries under ${index.roots.joinToString()}"
		lines += "$file: ${"%,d".format(file.fileSize())} bytes"

		val (read, reading) = measureTimedValue { IndexFile.read(file) }

		if (read is IndexRead.Read)
			lines += "read back in ${millis(reading)}"

		for (word in args[words]) {
			val query = query { +word(word) }
			val runs = (1..5).map { measureTimedValue { index.search(query) } }

			when (val searched = runs.first().value) {
				is Searched.Refused -> lines += "$word: ${searched.why}"
				is Searched.Found -> {
					val results = searched.results

					lines += ""
					lines +=
						"$word: ${"%,d".format(
							results.size,
						)} found, the first search in ${millis(runs.first().duration)}, " +
						"the best of ${runs.size} in ${millis(runs.minOf { it.duration })}"
					results.take(5).forEach { lines += "  ${it.where}" }
					if (results.size > 5)
						lines += "  ..."
				}
			}
		}

		return Outcome(lines.joinToString("\n"))
	}

	private fun how(origin: IndexStore.Origin): String =
		when (origin) {
			IndexStore.Origin.Built -> "indexed"
			is IndexStore.Origin.Rebuilt -> "indexed anew (${origin.why})"
			is IndexStore.Origin.Read ->
				if (origin.torn)
					"read, its last frame dropped,"
				else
					"read"
		}

	/** `$XDG_CACHE_HOME/barion/index.barion`: an index can always be made again, which is what a cache is. */
	private fun defaultFile(): Path {
		val cache =
			System.getenv("XDG_CACHE_HOME")?.takeIf { it.startsWith("/") }?.let(::Path)
				?: Path(System.getProperty("user.home"), ".cache")

		return cache.resolve("barion/index.barion")
	}

	private fun millis(elapsed: Duration): String =
		"%.1f ms".format(elapsed.inWholeMicroseconds / 1000.0)
}
