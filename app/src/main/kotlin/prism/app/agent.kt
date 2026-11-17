package prism.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.seconds
import prism.cli.Args
import prism.cli.Category
import prism.cli.Command
import prism.cli.Group
import prism.cli.InputError
import prism.cli.Outcome
import prism.cli.ParamType
import prism.client.Asking
import prism.client.Caller
import prism.client.InstancePlace
import prism.client.Request
import prism.client.ask
import prism.platform.Os

/*
 * `prism agent`: the daemon as a launchd agent, on a Mac, as a systemd user service is on Linux: started as the user
 * logs in, in their graphical session (where windows can be opened), and started again should it end by failing.
 * `install` writes its property list in ~/Library/LaunchAgents and has launchd start it; `uninstall` stops it and
 * takes the list away. What the daemon says goes to its log, beside the rest of prism's state.
 */

val AgentGroup =
	Group(
		"agent",
		"run the daemon as a launchd agent, started as you log in (macOS)",
		listOf(Category("agent", listOf(AgentInstall, AgentUninstall))),
	)

/** What launchd knows the daemon's agent by. */
internal const val AGENT_LABEL = "dev.prism.daemon"

/** Where the agent's property list is kept: the user's own launch agents. */
internal fun agentFile(home: Path = Path(System.getProperty("user.home"))): Path =
	home.resolve("Library/LaunchAgents/$AGENT_LABEL.plist")

/**
 * The agent's property list: [command] (what runs prism) with `daemon --foreground`, started at login in the user's
 * graphical session (`Aqua`), started again when it ends by failing and not when it is stopped, as a program the user
 * works with (`Interactive`, which launchd does not hold back); what it says written to [log].
 */
internal fun agentPlist(command: List<String>, log: Path): String {
	fun string(text: String) = "<string>${escaped(text)}</string>"

	val arguments =
		(command + listOf("daemon", "--foreground")).joinToString("\n") {
			"\t\t${string(it)}"
		}

	return """
		|<?xml version="1.0" encoding="UTF-8"?>
		|<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
		|<!-- $WRITTEN_BY_AGENT -->
		|<plist version="1.0">
		|<dict>
		|	<key>Label</key>
		|	${string(AGENT_LABEL)}
		|	<key>ProgramArguments</key>
		|	<array>
		|$arguments
		|	</array>
		|	<key>RunAtLoad</key>
		|	<true/>
		|	<key>KeepAlive</key>
		|	<dict>
		|		<key>SuccessfulExit</key>
		|		<false/>
		|	</dict>
		|	<key>LimitLoadToSessionType</key>
		|	${string("Aqua")}
		|	<key>ProcessType</key>
		|	${string("Interactive")}
		|	<key>StandardOutPath</key>
		|	${string(log.toString())}
		|	<key>StandardErrorPath</key>
		|	${string(log.toString())}
		|</dict>
		|</plist>
		|
		""".trimMargin()
}

/** What marks the agent's property list as prism's, for uninstalling it and nothing else. */
internal const val WRITTEN_BY_AGENT =
	"Written by prism agent install; prism agent uninstall takes it away."

private fun escaped(text: String): String =
	text
		.replace(
			"&",
			"&amp;",
		).replace("<", "&lt;")
		.replace(">", "&gt;")
		.replace("\"", "&quot;")

/** The user's launchd domain, as `launchctl` names it: `gui/501`. */
private fun domain(home: Path): String = "gui/${Files.getAttribute(home, "unix:uid")}"

/** `launchctl` with [words]: whether it did it, and what it said. */
private fun launchctl(vararg words: String): Pair<Boolean, String> {
	val process =
		ProcessBuilder(listOf("/bin/launchctl") + words)
			.redirectErrorStream(true)
			.start()

	val said =
		process.inputStream
			.bufferedReader()
			.readText()
			.trim()

	return (process.waitFor() == 0) to said
}

private fun onlyOnMac(command: Command) {
	if (Os.current != Os.MacOS)
		throw InputError(
			"prism ${command.name} is for macOS's launchd; on Linux the daemon runs as a " +
				"systemd user service (docs/running.md)",
		)
}

/** What putting the agent in place came to. */
internal sealed interface AgentSet {
	/** It was as it should be already: nothing was written or started. */
	data object AsItWas : AgentSet

	/** [file] was written, and launchd started the daemon. */
	data class Started(
		val file: Path,
	) : AgentSet

	/** It was not, and [why]. */
	data class Not(
		val why: String,
	) : AgentSet
}

/**
 * The agent that runs [words] put in place for the user whose home is [home], and started: written where there is
 * none, or where the one there (of prism's own writing) runs another prism, as after the application was moved.
 * Nothing of another's is written over.
 */
internal fun setAgent(words: List<String>, home: Path): AgentSet {
	val file = agentFile(home)
	val text = agentPlist(words, stateFolder().resolve("daemon.log"))

	try {
		if (Files.exists(file)) {
			val there = Files.readString(file)

			if (WRITTEN_BY_AGENT !in there)
				return AgentSet.Not(
					"$file exists and was not written by prism, so nothing was changed",
				)
			if (there == text)
				return AgentSet.AsItWas
		}
		Files.createDirectories(file.parent)
		Files.createDirectories(stateFolder())
		Files.writeString(file, text)
	} catch (failed: IOException) {
		return AgentSet.Not("can't write $file: ${failed.message ?: failed}")
	}

	// One loaded before is stopped first, so the new list is the one launchd starts.
	launchctl("bootout", "${domain(home)}/$AGENT_LABEL")

	val (started, said) = launchctl("bootstrap", domain(home), file.toString())

	return if (started)
		AgentSet.Started(file)
	else
		AgentSet.Not("wrote $file, but launchd did not start it: $said")
}

/**
 * Prism.app opened by its icon, with nothing asked of it: what a Mac's applications do the first time they are opened
 * is set themselves up, and prism's setting up is its daemon, as an agent of this very application, started now and
 * whenever the user logs in. Done where it is not done, or the application has moved; then the daemon is waited for,
 * so that the window this opening asks for is the daemon's. Not from a disk image or from where the system runs an
 * application it has not been told to trust, which are no place to be started from tomorrow.
 */
internal fun settleAsApplication() {
	val program = System.getProperty("jpackage.app-path") ?: return

	if (Os.current != Os.MacOS || Self.words.isNotEmpty() || TRANSIENT.any { it in program })
		return

	when (val set = setAgent(listOf(program), Path(System.getProperty("user.home")))) {
		AgentSet.AsItWas -> Unit
		is AgentSet.Not ->
			System.err.println(
				"prism: the daemon was not set up to start as you log in: ${set.why}",
			)
		is AgentSet.Started -> {
			val place = InstancePlace.of() ?: return
			val until = System.nanoTime() + DAEMON_AWAITED.inWholeNanoseconds

			// Until the daemon answers: a window asked for before it does would be this process's own.
			while (System.nanoTime() < until &&
				ask(place, Request(listOf(StatusCommand.name), Caller.here())) == Asking.Nobody
				)
				Thread.sleep(200)
		}
	}
}

/** Where an application is only for now: a disk image it was opened from, and where the system runs one it distrusts. */
private val TRANSIENT = listOf("/Volumes/", "/AppTranslocation/")

/** How long a daemon just started is waited for before a window is opened without it. */
private val DAEMON_AWAITED = 20.seconds

/** `prism agent install`: the agent written, and started. */
object AgentInstall : Command(
	"install",
	"start the daemon now and whenever you log in, through launchd",
) {
	private val command =
		option(
			"--command",
			type = ParamType.PATH,
			help = "command that runs prism (default: the one that ran this)",
		)
	private val dryRun =
		flag("--dry-run", help = "print the agent's property list without writing it")

	override fun run(args: Args): Outcome {
		val home = Path(System.getProperty("user.home"))
		val words =
			args[command]?.let { listOf(it.toAbsolutePath().normalize().toString()) }
				?: Self.launcher()
				?: throw InputError("can't tell which command runs prism; pass it with --command")

		if (args[dryRun])
			return Outcome(agentPlist(words, stateFolder().resolve("daemon.log")).trimEnd())

		onlyOnMac(this)

		return when (val set = setAgent(words, home)) {
			AgentSet.AsItWas ->
				Outcome(
					"the daemon's agent is in place already (${agentFile(home)})",
				)
			is AgentSet.Started ->
				Outcome(
					"wrote ${set.file}; launchd starts the daemon now and whenever you log in " +
						"(prism status says how it is)",
				)
			is AgentSet.Not -> Outcome(set.why, 1)
		}
	}
}

/** `prism agent uninstall`: the agent stopped, and its property list taken away. */
object AgentUninstall : Command(
	"uninstall",
	"stop the daemon's launchd agent and take it away",
) {
	override fun run(args: Args): Outcome {
		onlyOnMac(this)

		val home = Path(System.getProperty("user.home"))
		val file = agentFile(home)

		if (!Files.exists(file))
			return Outcome("there is no agent of prism's ($file is not there)")
		if (WRITTEN_BY_AGENT !in Files.readString(file))
			throw InputError("$file was not written by prism, so it was left as it is")

		launchctl("bootout", "${domain(home)}/$AGENT_LABEL")
		Files.delete(file)

		return Outcome("stopped the daemon's agent and removed $file")
	}
}

/*
 * `prism cli`: the `prism` a shell starts, on a Mac, where an application is a folder and its programs are on no PATH.
 * `install` links one in a folder of the PATH to this application's: the client compiled ahead of time, where the
 * application has it beside its own program, and that program where not. `uninstall` takes the link away.
 */

val CliGroup =
	Group(
		"cli",
		"put the prism command on your PATH, as a link to this application's (macOS)",
		listOf(Category("cli", listOf(CliInstall, CliUninstall))),
	)

private const val DEFAULT_BIN = "/usr/local/bin"

/** What `prism` is linked to: the client beside this application's program, or the program itself. */
private fun linked(): Path {
	val program =
		Self.launcher()?.singleOrNull()?.let(::Path)
			?: throw InputError(
				"can't tell where this prism is; run it as Prism.app/Contents/MacOS/Prism",
			)

	val client = program.resolveSibling("prism-client")

	return if (Files.isExecutable(client))
		client
	else
		program
}

/** `prism cli install`. */
object CliInstall : Command(
	"install",
	"link the prism command into a folder on your PATH",
) {
	private val bin =
		option(
			"--bin",
			type = ParamType.PATH,
			default = Path(DEFAULT_BIN),
			help = "folder to link it in",
		)

	override fun run(args: Args): Outcome {
		onlyOnMac(this)

		val target = linked()
		val link = args[bin].resolve("prism")

		if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
			!Files.isSymbolicLink(link)
		)
			throw InputError("$link is there and is no link, so it was left as it is")

		return try {
			Files.createDirectories(link.parent)
			Files.deleteIfExists(link)
			Files.createSymbolicLink(link, target)
			Outcome("linked $link to $target")
		} catch (refused: IOException) {
			Outcome(
				"${link.parent} is not yours to write. Run this, which asks for your password:\n" +
					"sudo mkdir -p '${link.parent}' && sudo ln -sf '$target' '$link'",
				1,
			)
		}
	}
}

/** `prism cli uninstall`. */
object CliUninstall : Command(
	"uninstall",
	"take the prism command's link away",
) {
	private val bin =
		option(
			"--bin",
			type = ParamType.PATH,
			default = Path(DEFAULT_BIN),
			help = "folder it is linked in",
		)

	override fun run(args: Args): Outcome {
		onlyOnMac(this)

		val link = args[bin].resolve("prism")

		if (!Files.isSymbolicLink(link))
			return Outcome("$link is no link of prism's")

		return try {
			Files.delete(link)
			Outcome("removed $link")
		} catch (refused: IOException) {
			Outcome("${link.parent} is not yours to write. Run: sudo rm '$link'", 1)
		}
	}
}
