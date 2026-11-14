package prism.app

import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess
import prism.cli.Category
import prism.cli.Group
import prism.cli.execute
import prism.client.ShellCommand

/**
 * The command line: every command, in its category, in the order `prism --help` lists them. `prism` with no command
 * is `prism explore`, as a file manager's name alone opens it.
 */
val PRISM =
	Group(
		"prism",
		"a file picker for Wayland's portals, and a file explorer, over an index of every file on the machine that " +
			"the kernel keeps current",
		listOf(
			Category(
				"browsing",
				listOf(ExploreCommand, LaunchCommand, PickCommand, SnapshotCommand, PortalGroup),
			),
			Category("searching", listOf(PathSearch, ShellCommand)),
			Category("running", listOf(DaemonCommand, StatusCommand, MatchesCommand)),
			Category("indexing", listOf(IndexCommand, ScanCommand)),
			Category("watching", listOf(ProbeGroup)),
		),
		default = ExploreCommand,
	)

/** What prism's windows are named to the desktop: Wayland's app id and X11's class, which its entry and icon match. */
internal const val APP_ID = "prism"

/** How this prism was started, for starting another like it: a daemon apart from its terminal, an installed service. */
internal object Self {
	/** The words this process was given, after `prism`. */
	@Volatile
	var words: List<String> = emptyList()

	/**
	 * What runs prism as this process was run, to be followed by a command line's words: the launcher a package says
	 * it has (`-Dprism.launcher`, a Nix package's wrapper), or an app image's own; or, run by `java` itself (as Gradle
	 * runs it), that `java` with everything it was given before this process's own words. Null where none can be told.
	 */
	fun launcher(): List<String>? {
		(System.getProperty("prism.launcher") ?: System.getProperty("jpackage.app-path"))?.let {
			return listOf(it)
		}

		val process = ProcessHandle.current().info()
		val program = process.command().orElse(null) ?: return null
		val given = process.arguments().orElse(null)?.toList() ?: return null

		return (listOf(program) + given.dropLast(words.size)).takeIf {
			given.takeLast(words.size) ==
				words
		}
	}
}

/**
 * The command line [args] run: what a command prints goes to stdout, its help too, and a mistake in the words or a
 * problem with what the command was given to stderr. The output is UTF-8 whatever the platform's charset, and
 * buffered, flushed however the process ends. What a command throws that is no problem with what it was given is a
 * mistake of prism's own: said in full on stderr, as everything that goes wrong is (report.kt), and the process ends
 * with a status of its own, having written what it held.
 */
fun main(args: Array<String>) {
	val out =
		PrintStream(
			BufferedOutputStream(FileOutputStream(FileDescriptor.out), 1 shl 16),
			false,
			Charsets.UTF_8,
		)

	System.setOut(out)
	System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))

	Report.install()
	Self.words = args.toList()

	val ran =
		try {
			execute(PRISM, args.toList())
		} catch (failed: Throwable) {
			out.flush()
			Report.problem("prism ${args.joinToString(" ")} failed", failed)
			exitProcess(FAILED_STATUS)
		}

	if (ran.out.isNotEmpty())
		println(ran.out)
	if (ran.err.isNotEmpty())
		System.err.println(ran.err)
	out.flush()
	exitProcess(ran.status)
}
