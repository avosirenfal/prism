package prism.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.fileSize
import kotlin.time.Duration
import kotlin.time.measureTimedValue
import prism.cli.Args
import prism.cli.Command
import prism.cli.InputError
import prism.cli.Outcome
import prism.cli.ParamType
import prism.index.Index
import prism.index.IndexFile
import prism.index.IndexRead
import prism.index.IndexStore
import prism.index.Indexer
import prism.index.Searched
import prism.index.query.QueryText
import prism.index.search

/**
 * `prism index`: folders indexed into the index's file (or the file read, where there is one), with what it holds,
 * how big the file is and how long each step took; and queries searched for, each read in the search language with
 * what is wrong with it shown, and how long each search took. What the daemon will do when it starts, done once, to be
 * seen.
 */
object IndexCommand : Command(
	"index",
	"build the index file, or load the existing one, and print what it holds and how long it took",
) {
	private val roots =
		arguments(
			"PATH",
			ParamType.FOLDER,
			help = "folders to index if there is no index file yet (default /)",
		)

	private val file =
		option(
			"--file",
			type = ParamType.PATH,
			default = defaultIndexFile(),
			help = "index file",
			shown = "\$XDG_CACHE_HOME/prism/index.prism",
		)

	private val fresh =
		flag(
			"--fresh",
			help = "rebuild the index even if the file exists",
		)

	private val threads =
		option(
			"--threads",
			type = ParamType.COUNT,
			default = Runtime.getRuntime().availableProcessors(),
			help = "number of threads reading directories",
			shown = "all cores",
		)

	private val queries =
		repeated(
			"--find",
			type = ParamType.TEXT.named("QUERY"),
			help = "run a search query, e.g. report, \"tax *.pdf\", dir:funny",
		)

	override fun run(args: Args): Outcome {
		val file = args[file].toAbsolutePath().normalize()

		// Read, the file is whoever's; written, it is this command's alone, and never while a daemon keeps it.
		val writing = args[fresh] || !Files.exists(file)

		val lock =
			if (writing)
				IndexLock.take(file)
					?: throw InputError(
						"a prism daemon is using $file and would overwrite it; " +
							"stop the daemon first, or use another file with --file",
					)
			else
				null

		return lock.use { indexed(args, file) }
	}

	private fun indexed(args: Args, file: Path): Outcome {
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
		val read = QueryText.read(text, aliasesNow())
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
						"$count found; first search $first, best of ${runs.size} $best",
					) +
						results.take(5).map { "  ${it.where}" } +
						listOfNotNull("  ...".takeIf { results.size > 5 })
				}
			}
	}

	private fun how(origin: IndexStore.Origin): String =
		when (origin) {
			IndexStore.Origin.Missing, IndexStore.Origin.Memory -> "indexed"
			is IndexStore.Origin.Unreadable -> "re-indexed (${origin.why})"
			is IndexStore.Origin.Read ->
				if (origin.torn)
					"loaded, dropping a truncated last frame,"
				else
					"loaded"
		}

	private fun millis(elapsed: Duration): String =
		"%.1f ms".format(elapsed.inWholeMicroseconds / 1000.0)
}

/**
 * `$XDG_CACHE_HOME/prism/index.prism`, where the index is kept unless told otherwise: an index can always be made
 * again, which is what a cache is. What every command that reads or writes the index starts from.
 */
internal fun defaultIndexFile(): Path {
	val cache =
		System.getenv("XDG_CACHE_HOME")?.takeIf { it.startsWith("/") }?.let(::Path)
			?: Path(System.getProperty("user.home"), ".cache")

	return cache.resolve("prism/index.prism")
}
