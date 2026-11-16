package prism.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import prism.cli.Args
import prism.cli.Category
import prism.cli.Command
import prism.cli.Group
import prism.cli.InputError
import prism.cli.Outcome
import prism.cli.ParamType
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
		val file = agentFile(home)
		val text = agentPlist(words, stateFolder().resolve("daemon.log"))

		if (args[dryRun])
			return Outcome(text.trimEnd())

		onlyOnMac(this)
		if (Files.exists(file) && WRITTEN_BY_AGENT !in Files.readString(file))
			throw InputError("$file exists and was not written by prism, so nothing was changed")

		try {
			Files.createDirectories(file.parent)
			Files.createDirectories(stateFolder())
			Files.writeString(file, text)
		} catch (failed: IOException) {
			throw InputError("can't write $file: ${failed.message ?: failed}")
		}

		// One loaded before is stopped first, so the new list is the one launchd starts.
		launchctl("bootout", "${domain(home)}/$AGENT_LABEL")

		val (started, said) = launchctl("bootstrap", domain(home), file.toString())

		return if (started)
			Outcome(
				"wrote $file; launchd starts the daemon now and whenever you log in (prism status says how it is)",
			)
		else
			Outcome("wrote $file, but launchd did not start it: $said", 1)
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
