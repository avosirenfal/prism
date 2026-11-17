package prism.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.system.exitProcess
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
 * takes the list away. `stop`, `start` and `restart` are launchd's own, said in prism's words. What the daemon says
 * goes to its log, beside the rest of prism's state.
 */

val AgentGroup =
	Group(
		"agent",
		"run the daemon as a launchd agent, started as you log in (macOS)",
		listOf(
			Category(
				"agent",
				listOf(AgentInstall, AgentUninstall, AgentStart, AgentStop, AgentRestart),
			),
		),
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

	if (Os.current != Os.MacOS || Self.words.isNotEmpty() ||
		SET_UP_ELSEWHERE.any { it in program }
	)
		return

	val home = Path(System.getProperty("user.home"))

	when (val set = setAgent(listOf(program), home)) {
		// In place, and yet perhaps not running: stopped (`prism agent stop`), or ended when its application went.
		AgentSet.AsItWas ->
			if (!answers()) {
				startAgent(home)
				if (!awaitDaemon())
					System.err.println("prism: ${unanswered()}")
			}
		is AgentSet.Not ->
			System.err.println(
				"prism: the daemon was not set up to start as you log in: ${set.why}",
			)
		is AgentSet.Started ->
			if (!awaitDaemon())
				System.err.println("prism: ${unanswered()}")
	}
	offerCli(Path(program))
}

/** Whether a prism answers at the session's socket. */
private fun answers(): Boolean {
	val place = InstancePlace.of() ?: return false

	return ask(place, Request(listOf(StatusCommand.name), Caller.here())) != Asking.Nobody
}

/**
 * Until the daemon answers, or long enough: a window asked for before it does would be this process's own. Whether
 * it answered.
 */
private fun awaitDaemon(): Boolean {
	val until = System.nanoTime() + DAEMON_AWAITED.inWholeNanoseconds

	while (System.nanoTime() < until) {
		if (answers())
			return true
		Thread.sleep(200)
	}

	return false
}

/** What is said of a daemon launchd was had to start and that does not answer: the last of what it said itself. */
private fun unanswered(): String {
	val log = stateFolder().resolve("daemon.log")
	val last =
		try {
			Files.readAllLines(log).takeLast(LOG_SHOWN)
		} catch (unread: IOException) {
			emptyList()
		}

	return "launchd started the daemon, and it does not answer. " +
		if (last.isEmpty())
			"It has said nothing in $log."
		else
			"The last it said, in $log:\n${last.joinToString("\n") { "  $it" }}"
}

private const val LOG_SHOWN = 20

/**
 * Where an application sets nothing up by being opened: a disk image it was opened from and where the system runs one
 * it distrusts, which are no place to be started from tomorrow; and the Nix store, whose prism nix-darwin sets up
 * (nix/darwin-module.nix).
 */
private val SET_UP_ELSEWHERE = listOf("/Volumes/", "/AppTranslocation/", "/nix/store/")

/*
 * The application replaced, or taken away, while its daemon runs. A Mac's applications are replaced by dragging the
 * new one over the old, and removed by dragging them to the Trash, and neither tells the daemon, which would go on
 * running what it was started from. So the daemon looks at its own program's file now and then. Another file there,
 * and the same one twice running (so that it is no longer being copied), is a new build: the daemon ends by failing,
 * which is what launchd starts it again for, from the new application. No file there for a minute is the application
 * gone: the daemon ends well, and launchd leaves it ended.
 */

/** A program's file as it is: what tells one build of an application from the next. */
internal data class Build(
	val modified: FileTime,
	val size: Long,
)

/** What a daemon does about its own application, having looked at it. */
internal enum class Fate {
	/** It is the one this daemon was started from, or is still being put there. */
	Stay,

	/** Another is there: end by failing, to be started again from it. */
	Renew,

	/** None is there: end, and stay ended. */
	End,
}

/**
 * What the daemon started from [started] does, having seen [looks] since (the newest last; null where no file was
 * there).
 */
internal fun fateOf(started: Build, looks: List<Build?>): Fate {
	val settled = looks.takeLast(LOOKS_SETTLED)
	val gone = looks.takeLast(LOOKS_GONE)

	return when {
		gone.size == LOOKS_GONE && gone.all { it == null } -> Fate.End
		settled.size == LOOKS_SETTLED &&
			settled.distinct().singleOrNull().let { it != null && it != started } -> Fate.Renew
		else -> Fate.Stay
	}
}

private const val LOOKS_SETTLED = 2
private const val LOOKS_GONE = 12
private val LOOKED_EVERY = 5.seconds

/** What launchd is told by a daemon that ends to be started again: a failure that is for now (`EX_TEMPFAIL`). */
private const val STARTED_AGAIN = 75

private fun buildAt(program: Path): Build? =
	try {
		Files.readAttributes(program, BasicFileAttributes::class.java).let {
			Build(it.lastModifiedTime(), it.size())
		}
	} catch (gone: IOException) {
		null
	}

/**
 * This daemon made to follow its own application, where it is one's (Prism.app, on a Mac): ended when the application
 * is replaced or taken away, as [fateOf] says. [say] is told why.
 */
internal fun followOwnApplication(say: (String) -> Unit) {
	val program = System.getProperty("jpackage.app-path")?.let(::Path) ?: return

	if (Os.current != Os.MacOS)
		return

	val started = buildAt(program) ?: return

	thread(isDaemon = true, name = "own-application") {
		generateSequence {
			Thread.sleep(LOOKED_EVERY.inWholeMilliseconds)
			buildAt(program)
		}.runningFold(
			emptyList<Build?>(),
		) { looks, look -> (looks + look).takeLast(LOOKS_GONE) }
			.map { fateOf(started, it) }
			.first { it != Fate.Stay }
			.let { fate ->
				if (fate == Fate.Renew) {
					say("$program was replaced: ending, for launchd to start the new one")
					exitProcess(STARTED_AGAIN)
				}
				say("$program is gone: ending")
				exitProcess(0)
			}
	}
}

/** The agent started where it is not running: loaded where launchd does not have it, and set going. */
private fun startAgent(home: Path): Pair<Boolean, String> {
	launchctl("bootstrap", domain(home), agentFile(home).toString())

	return launchctl("kickstart", "${domain(home)}/$AGENT_LABEL")
}

/** The agent's file, where it is prism's own to start and stop; a mistake in what was asked where it is not. */
private fun ownAgent(home: Path): Path {
	val file = agentFile(home)

	if (!Files.exists(file))
		throw InputError(
			"there is no agent of prism's ($file is not there); prism agent install",
		)

	return file
}

/** `prism agent start`. */
object AgentStart : Command(
	"start",
	"start the daemon's agent, where it was stopped",
) {
	override fun run(args: Args): Outcome {
		onlyOnMac(this)

		val home = Path(System.getProperty("user.home"))

		ownAgent(home)

		val (started, said) = startAgent(home)

		return when {
			!started -> Outcome("launchd did not start it: $said", 1)
			awaitDaemon() -> Outcome("started the daemon (prism status says how it is)")
			else -> Outcome(unanswered(), 1)
		}
	}
}

/** `prism agent stop`. */
object AgentStop : Command(
	"stop",
	"stop the daemon until you log in again, open Prism, or start it",
) {
	override fun run(args: Args): Outcome {
		onlyOnMac(this)

		val home = Path(System.getProperty("user.home"))

		ownAgent(home)

		val (stopped, said) = launchctl("bootout", "${domain(home)}/$AGENT_LABEL")

		return if (stopped)
			Outcome("stopped the daemon; prism agent start starts it again")
		else
			Outcome("launchd did not stop it (it may not be running): $said", 1)
	}
}

/** `prism agent restart`. */
object AgentRestart : Command(
	"restart",
	"stop the daemon and start it again",
) {
	override fun run(args: Args): Outcome {
		onlyOnMac(this)

		val home = Path(System.getProperty("user.home"))

		ownAgent(home)
		launchctl("bootstrap", domain(home), agentFile(home).toString())

		val (started, said) = launchctl("kickstart", "-k", "${domain(home)}/$AGENT_LABEL")

		return when {
			!started -> Outcome("launchd did not start it again: $said", 1)
			awaitDaemon() -> Outcome("started the daemon again")
			else -> Outcome(unanswered(), 1)
		}
	}
}

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

/** What `prism` is linked to, of the application whose program is [program]: the client beside it, or the program. */
private fun cliOf(program: Path): Path =
	program.resolveSibling("prism-client").takeIf(Files::isExecutable) ?: program

/** What linking the command came to. */
internal sealed interface Linked {
	/** [link] was to its target already. */
	data object AsItWas : Linked

	/** [link] was made. */
	data object Made : Linked

	/** What is at [link] is no link, and so not prism's to replace. */
	data object NotALink : Linked

	/** The folder is not the user's to write, [why]. */
	data class Refused(
		val why: String,
	) : Linked
}

/** [link] made a link to [target], where it is not one already, and nothing but a link is in its place. */
internal fun linkCli(target: Path, link: Path): Linked =
	try {
		when {
			Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target -> Linked.AsItWas
			Files.exists(link, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(link) ->
				Linked.NotALink
			else -> {
				Files.createDirectories(link.parent)
				Files.deleteIfExists(link)
				Files.createSymbolicLink(link, target)
				Linked.Made
			}
		}
	} catch (refused: IOException) {
		Linked.Refused(refused.message ?: refused.toString())
	}

/** What a shell runs, as an administrator, to link [link] to [target]. */
private fun linking(target: Path, link: Path): String =
	"/bin/mkdir -p ${quoted(link.parent)} && /bin/ln -sfn ${quoted(target)} ${quoted(link)}"

private fun quoted(path: Path): String = "'${path.toString().replace("'", "'\\''")}'"

/**
 * The `prism` command put on the PATH as the application is opened, so that nobody has to be told to: linked in
 * /usr/local/bin, which every Mac's shells look in. That folder is the administrator's on a Mac as it comes, so where
 * the link is refused the system is had to ask for the password, once for each place the application has been:
 * what was asked is remembered beside prism's state, and somebody who said no is not asked again.
 */
private fun offerCli(program: Path) {
	val target = cliOf(program)
	val link = Path(DEFAULT_BIN).resolve("prism")

	if (linkCli(target, link) !is Linked.Refused)
		return

	val asked = stateFolder().resolve("cli-offered")

	try {
		if (Files.exists(asked) && Files.readString(asked) == target.toString())
			return
		Files.createDirectories(asked.parent)
		Files.writeString(asked, target.toString())
	} catch (failed: IOException) {
		return
	}

	val script =
		"do shell script ${appleScript(linking(target, link))} " +
			"with prompt ${appleScript(CLI_PROMPT)} with administrator privileges"

	ProcessBuilder("/usr/bin/osascript", "-e", script)
		.redirectErrorStream(true)
		.redirectOutput(ProcessBuilder.Redirect.DISCARD)
		.start()
		.waitFor()
}

private const val CLI_PROMPT =
	"Prism would like to put its prism command in /usr/local/bin, for the Terminal."

/** [text] as AppleScript writes a string. */
private fun appleScript(text: String): String =
	"\"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\""

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

		val program =
			Self.launcher()?.singleOrNull()?.let(::Path)
				?: throw InputError(
					"can't tell where this prism is; run it as Prism.app/Contents/MacOS/Prism",
				)

		val target = cliOf(program)
		val link = args[bin].resolve("prism")

		return when (linkCli(target, link)) {
			Linked.AsItWas -> Outcome("$link is linked to $target already")
			Linked.Made -> Outcome("linked $link to $target")
			Linked.NotALink ->
				throw InputError("$link is there and is no link, so it was left as it is")
			is Linked.Refused ->
				Outcome(
					"${link.parent} is not yours to write. Run this, which asks for your " +
						"password:\nsudo sh -c \"${linking(target, link)}\"",
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
