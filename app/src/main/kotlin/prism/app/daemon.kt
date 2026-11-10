package prism.app

import java.util.concurrent.CountDownLatch
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.measureTime
import prism.cli.Args
import prism.cli.Command
import prism.cli.Outcome
import prism.cli.ParamType
import prism.daemon.Daemon
import prism.daemon.DaemonSettings
import prism.daemon.LiveIndex
import prism.daemon.LiveSearch
import prism.index.Indexer
import prism.index.query.QueryText

/**
 * `prism daemon`: the index kept current. The watcher is started first, then the index read from its file (and its
 * roots read again, for what changed while nothing watched) or built, and every change the watcher sees made in it as
 * it is seen. Queries given are searched once the roots are read, places they ask after that the rules leave out read
 * for them alone. Runs until killed, or for as long as it is told.
 */
object DaemonCommand : Command(
	"daemon",
	"keep the index current: the watcher started first, then the index read or built, every change made as it is seen",
) {
	private val roots =
		arguments(
			"PATH",
			ParamType.FOLDER,
			help = "the folders to index (/ unless named)",
		)

	private val file =
		option(
			"--file",
			type = ParamType.PATH,
			default = defaultIndexFile(),
			help = "the index's file",
			shown = "\$XDG_CACHE_HOME/prism/index.prism",
		)

	private val memory =
		flag(
			"--in-memory",
			help = "keep the index in memory alone, never written",
		)

	private val seconds =
		option(
			"--for",
			type = ParamType.COUNT,
			help = "stop after this many seconds (run until killed without it)",
		)

	private val queries =
		repeated(
			"--find",
			type = ParamType.TEXT.named("QUERY"),
			help = "a query in the search language, searched once the roots are read",
		)

	override fun run(args: Args): Outcome {
		val settings =
			DaemonSettings(
				file =
					if (args[memory])
						null
					else
						args[file],
				roots = args[roots].ifEmpty { listOf(Path("/")) },
				indexer = Indexer(),
			)

		val stopped = CountDownLatch(1)
		val daemon = Daemon.start(settings)

		Runtime.getRuntime().addShutdownHook(
			Thread {
				daemon.close()
				stopped.countDown()
			},
		)

		val watching =
			when (val now = daemon.watching) {
				Daemon.Watching.On -> "watching every change the kernel makes"
				is Daemon.Watching.Off -> "not watching: ${now.why}"
			}

		say(watching)
		if (daemon.watching is Daemon.Watching.Off)
			say("in its place, the roots are read again every half hour")
		say("reading ${settings.roots.joinToString()} (${daemon.live.origin})")

		val reading = measureTime { daemon.live.opened.get() }

		say("read in ${millis(reading)}: ${"%,d".format(daemon.live.index.size)} entries")
		args[queries].forEach { found(daemon.live, it) }

		args[seconds]?.let { stopped.await(it.toLong(), java.util.concurrent.TimeUnit.SECONDS) }
			?: stopped.await()
		daemon.close()

		return Outcome(
			"${"%,d".format(
				daemon.live.index.size,
			)} entries, written to ${settings.file ?: "nowhere (in memory)"}",
		)
	}

	/** [text] searched for, waiting for places it looks under that the rules leave out to be read for it. */
	private fun found(live: LiveIndex, text: String) {
		val read = QueryText.read(text)

		read.problems.forEach {
			say("$text: ${it.kind.name.lowercase()} at ${it.span}: ${it.message}")
		}

		val first = live.search(read.query)
		val searched =
			if (first is LiveSearch.Found && first.reading.isNotEmpty()) {
				say("$text: reading ${first.reading.joinToString()} for searches alone, in memory")
				first.ready.get()
				live.search(read.query)
			} else {
				first
			}

		when (searched) {
			is LiveSearch.Refused -> say("$text: ${searched.why}")
			is LiveSearch.Found -> {
				say("$text: ${"%,d".format(searched.results.size)} found")
				searched.results.take(5).forEach { say("  ${it.where}") }
			}
		}
	}

	private fun say(line: String) {
		println(line)
		System.out.flush()
	}

	private fun millis(elapsed: Duration): String =
		"%.1f ms".format(elapsed.inWholeMicroseconds / 1000.0)
}
