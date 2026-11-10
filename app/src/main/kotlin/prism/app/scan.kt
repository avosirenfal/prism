package prism.app

import prism.cli.Args
import prism.cli.Command
import prism.cli.Outcome
import prism.cli.ParamType
import prism.index.Backend
import prism.index.IgnoreRules
import prism.index.Ignored
import prism.index.Scanned
import prism.index.Scanner
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * `prism scan`: a directory tree read by each backend, several times, with what each found and how fast. The
 * first run of a tree reads the disk; the runs after it read the kernel's caches, which is what the daemon does
 * once it has run before, so the best run is the one to compare.
 */
object ScanCommand : Command(
	"scan",
	"read a directory tree into the index's shape, with each backend, and say how long each took",
) {
	private val root =
		argument(
			"PATH",
			ParamType.FOLDER,
			default = { Path(System.getProperty("user.home")) },
			shown = "your home",
		)

	private val backends =
		repeated(
			"--backend",
			type = ParamType.choice(Backend.ALL) { it.name },
			help = "the backends to compare, each read the same way (all of them unless named)",
		)

	private val threads =
		option(
			"--threads",
			type = ParamType.COUNT,
			default = Runtime.getRuntime().availableProcessors(),
			help = "how many threads read directories at once",
			shown = "every core",
		)

	private val runs =
		option(
			"--runs",
			type = ParamType.COUNT,
			default = 3,
			help = "how many times each backend reads the tree",
		)

	private val everything =
		flag(
			"--no-ignore",
			help = "read what the ignore rules would leave out (.git, node_modules, ...)",
		)

	private val ignoring =
		repeated(
			"--ignore",
			type = ParamType.TEXT.named("RULE"),
			help = "leave these out too: a name anywhere (.git), or one path (/nix/store)",
		)

	override fun run(args: Args): Outcome {
		val rules =
			(
				if (args[everything])
					IgnoreRules.DEFAULT.copy(directories = emptySet())
				else
					IgnoreRules.DEFAULT
			).alsoIgnoring(args[ignoring].map(Ignored::of))
		val chosen = args[backends].ifEmpty { Backend.ALL }
		val scans =
			chosen.associateWith { backend ->
				(1..args[runs]).map { Scanner(backend, rules, args[threads]).scan(args[root]) }
			}

		return Outcome(report(args[root], args[threads], scans))
	}

	private fun report(
		root: Path,
		threads: Int,
		scans: Map<Backend, List<Scanned>>,
	): String {
		val lines = ArrayList<String>()
		val best = scans.mapValues { (_, runs) -> runs.minBy { it.elapsed } }

		lines += "$root, on $threads threads"
		lines += ""
		lines +=
			"%-9s %10s %10s %10s %10s %14s   %s".format(
				"backend",
				"entries",
				"dirs",
				"skipped",
				"best",
				"entries/s",
				"runs",
			)
		for ((backend, runs) in scans) {
			val fastest = best.getValue(backend)
			val counts = fastest.counts

			lines +=
				"%-9s %10d %10d %10d %10s %14s   %s".format(
					backend.name,
					counts.entries,
					counts.directories,
					counts.skipped,
					millis(fastest.elapsed),
					"%,.0f".format(counts.entries / fastest.elapsed.toDouble(DurationUnit.SECONDS)),
					runs.joinToString(" ") { millis(it.elapsed) },
				)
		}

		val getdents = best[Backend.Getdents]
		val jvm = best[Backend.Jvm]

		if (getdents != null && jvm != null) {
			lines += ""
			lines +=
				"getdents64 took %.2fx the JVM's time (%s against %s)".format(
					getdents.elapsed / jvm.elapsed,
					millis(getdents.elapsed),
					millis(jvm.elapsed),
				)
			if (getdents.counts != jvm.counts)
				lines += "the two read different trees: ${getdents.counts} against ${jvm.counts}"
		}

		return lines.joinToString("\n")
	}

	private fun millis(duration: Duration): String =
		"%.1fms".format(duration.toDouble(DurationUnit.MILLISECONDS))
}
