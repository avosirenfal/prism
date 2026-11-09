package barion.app

import barion.cli.Args
import barion.cli.Command
import barion.cli.Outcome
import barion.cli.ParamType
import barion.index.Index
import barion.index.IndexFile
import barion.index.IndexRead
import barion.index.IndexStore
import barion.index.Indexer
import barion.index.Searched
import barion.index.query.QueryText
import barion.index.search
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.fileSize
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * `barion index`: folders indexed into the index's file (or the file read, where there is one), with what it holds,
 * how big the file is and how long each step took; and queries searched for, each read in the search language with
 * what is wrong with it shown, and how long each search took. What the daemon will do when it starts, done once, to be
 * seen.
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

	private val queries =
		repeated(
			"--find",
			type = ParamType.TEXT.named("QUERY"),
			help = "a query in the search language: report, \"tax *.pdf\", dir:funny",
		)

	override fun run(args: Args): Outcome {
		val file = args[file]

		if (args[fresh])
			Files.deleteIfExists(file)

		val lines = ArrayList<String>()
		val roots = args[roots].ifEmpty { listOf(Path("/")) }
		val indexer = Indexer(threads = args[threads])

		val (store, opening) =
			measureTimedValue {
				IndexStore.open(file).also { store ->
					// No file it can read: the roots indexed, and the file written.
					if (store.origin !is IndexStore.Origin.Read) {
						store.make(roots.map(indexer::index))
						store.flush()
					}
				}
			}

		val index = store.index
		val entries = "%,d".format(index.size)

		val opened = how(store.origin)

		lines +=
			"$opened in ${millis(opening)}: $entries entries under ${index.roots.joinToString()}"
		lines += "$file: ${"%,d".format(file.fileSize())} bytes"

		val (read, reading) = measureTimedValue { IndexFile.read(file) }

		if (read is IndexRead.Read)
			lines += "read back in ${millis(reading)}"

		for (text in args[queries])
			lines += listOf("") + found(index, text)

		return Outcome(lines.joinToString("\n"))
	}

	/** [text] read and searched for in [index]: what is wrong with it, each underlined, then what it found and how fast. */
	private fun found(index: Index, text: String): List<String> {
		val read = QueryText.read(text)
		val problems =
			read.problems.flatMap {
				val under =
					" ".repeat(it.span.start) + "^".repeat(maxOf(1, it.span.end - it.span.start))

				listOf(under, "${it.kind.name.lowercase()}: ${it.message}")
			}
		val runs = (1..5).map { measureTimedValue { index.search(read.query) } }
		val first = millis(runs.first().duration)
		val best = millis(runs.minOf { it.duration })

		return listOf(text) + problems +
			when (val searched = runs.first().value) {
				is Searched.Refused -> listOf(searched.why)
				is Searched.Found -> {
					val results = searched.results
					val count = "%,d".format(results.size)

					listOf(
						"$count found, the first search in $first, the best of ${runs.size} in $best",
					) +
						results.take(5).map { "  ${it.where}" } +
						listOfNotNull("  ...".takeIf { results.size > 5 })
				}
			}
	}

	private fun how(origin: IndexStore.Origin): String =
		when (origin) {
			IndexStore.Origin.Missing, IndexStore.Origin.Memory -> "indexed"
			is IndexStore.Origin.Unreadable -> "indexed anew (${origin.why})"
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
