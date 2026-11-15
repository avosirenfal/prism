package prism.desktop.freedesktop

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import prism.desktop.Activation
import prism.desktop.ActivationToken
import prism.desktop.Application
import prism.desktop.Launched
import prism.desktop.Launcher
import prism.desktop.TerminalChoice
import prism.desktop.TerminalCommand
import prism.desktop.TerminalOptions
import prism.desktop.asChildOfPrism

/*
 * Starting what prism opens as processes of their own. A process the JVM starts is its child, in its session and its
 * group of processes: a terminal's hangup reaches it, and a service manager stopping prism stops it too. So each
 * command is started through `setsid --fork`, which starts it in a session of its own and returns at once, leaving it
 * a child of init (or of the user's service manager) rather than of prism. Where the user's systemd is running, it is
 * started in a scope of its own as well (`systemd-run --user --scope`, in `app.slice`, as desktops start
 * applications), so it is not in prism's group of processes either, and a `systemctl --user stop` of prism's service
 * leaves it running. The scope is named for the application ([AppScope]), which is what tells the desktop's portal
 * which application a file chooser is for.
 *
 * Once started that way, a command that fails to start fails where prism cannot see it; so the program is looked for
 * on the `PATH` first, and one that is not there is said not to be, rather than started into nothing.
 *
 * Each command is given a token of the desktop's for bringing its window forward ([Activation]), in its environment,
 * as `XDG_ACTIVATION_TOKEN` (which Wayland's programs read) and `DESKTOP_STARTUP_ID` (which older ones read, and which
 * GLib's launchers set to the same token on Wayland). A token prism itself was started with was its own, and is used:
 * neither is passed on where there is no new one.
 */

/** Starts commands detached from prism, as [xdg]'s environment says the desktop is, each brought forward by [activation]. */
class DetachedLauncher(
	private val xdg: Xdg,
	private val activation: Activation = Activation.None,
	/**
	 * What starts a command, its words in full, in a folder, with a token for bringing it forward where there is one: a
	 * process, unless a test says otherwise.
	 */
	private val spawn: (List<String>, Path, ActivationToken?) -> Launched = ::spawned,
) : Launcher {
	override fun launch(
		application: Application,
		paths: List<Path>,
		terminal: TerminalChoice,
	): Launched =
		Exec
			.expand(application.command, paths, application)
			.map {
				if (application.inTerminal)
					when (val opening = terminalCommand(xdg.home, terminal, it)) {
						is TerminalCommand.Ready -> run(opening.words, xdg.home, label = application.id)
						is TerminalCommand.Failed -> Launched.Failed(application.name, opening.why)
					}
				else
					run(it, xdg.home, label = application.id)
			}.firstOrNull { it is Launched.Failed }
			?: Launched.Started(application.name)

	override fun run(command: List<String>, folder: Path): Launched =
		run(command, folder, label = null)

	private fun run(command: List<String>, folder: Path, label: String?): Launched {
		val program =
			command.firstOrNull()
				?: return Launched.Failed("the command", "There is nothing to run.")

		if (find(program) == null)
			return Launched.Failed(program, "“$program” isn't installed or isn't on the PATH.")

		// Asked for only now, for what is surely started: a desktop entry's name is the application's, as a token names it.
		return spawn(
			detached(command, label),
			folder,
			activation.token(label?.removeSuffix(".desktop")),
		)
	}

	/** The user's terminal, wherever on the desktop it is said (freedesktop/terminals.kt). */
	private val terminals = FreedesktopTerminals(xdg, ::find)

	override fun terminals(): TerminalOptions =
		TerminalOptions(terminals.preferred()?.name, terminals.options())

	override fun terminalCommand(
		folder: Path,
		choice: TerminalChoice,
		command: List<String>?,
	): TerminalCommand {
		val terminal =
			when (choice) {
				TerminalChoice.Found ->
					terminals.preferred()
						?: return TerminalCommand.Failed(
							"a terminal",
							"No terminal was found. Choose one in Preferences.",
						)
				// The command lines as written, each only filled in; one not written is not used.
				is TerminalChoice.CommandLines ->
					return when {
						command == null ->
							choice.inFolder?.let { TerminalCommand.Ready(it.filled(folder)) }
								?: TerminalCommand.Failed(
									"the terminal",
									"No command line is set for opening a folder.",
								)
						else ->
							choice.running?.let { TerminalCommand.Ready(it.filled(folder, command)) }
								?: TerminalCommand.Failed(
									"the terminal",
									"No command line is set for running a command.",
								)
					}
				is TerminalChoice.Named ->
					terminals.named(choice.name)
						?: return TerminalCommand.Failed(
							choice.name,
							if (!terminals.installed(choice.name))
								"“${choice.name}” isn't installed or isn't on the PATH."
							else
								"prism doesn't know how to start “${choice.name}”. Set its command lines in " +
									"Preferences.",
						)
				is TerminalChoice.Unreadable ->
					return TerminalCommand.Failed("the terminal", choice.why)
			}

		return terminal.opening(folder, command)?.let(TerminalCommand::Ready)
			?: TerminalCommand.Failed(terminal.name, "It can't run a command.")
	}

	/** [command] started in a session of its own, and in a scope of its own where the user's systemd is running. */
	private fun detached(command: List<String>, label: String?): List<String> {
		val setsid = find("setsid")?.let { listOf(it.toString(), "--fork") }.orEmpty()

		val scope =
			if (find("systemd-run") != null &&
				xdg.runtime?.resolve("systemd/private")?.let(Files::exists) == true
			)
				listOf(
					"systemd-run",
					"--user",
					"--scope",
					"--quiet",
					"--collect",
					"--slice=app.slice",
					// Named for the application, which is how the desktop's portal knows who asks it for a file (scope.kt).
					"--unit=${AppScope.of(label ?: command.first())}",
					"--",
				)
			else
				emptyList()

		return setsid + scope + command
	}

	/** [program] where it is, or null where it is not: by its path, or on the `PATH` ([Xdg.program]). */
	private fun find(program: String): Path? = xdg.program(program)

	private companion object {
		const val ACTIVATION_TOKEN = "XDG_ACTIVATION_TOKEN"
		const val STARTUP_ID = "DESKTOP_STARTUP_ID"

		/**
		 * [command] started in [folder], with nothing of prism's: no input, and what it writes thrown away; given
		 * [token] to bring its window forward, where there is one. It is waited for only as long as `setsid --fork`
		 * takes to hand it on, which is no time.
		 */
		fun spawned(command: List<String>, folder: Path, token: ActivationToken?): Launched {
			val what = command.last { !it.startsWith("-") }.let { File(it).name }

			return try {
				val builder =
					ProcessBuilder(command)
						.asChildOfPrism()
						.directory(folder.toFile())
						.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
						.redirectOutput(ProcessBuilder.Redirect.DISCARD)
						.redirectError(ProcessBuilder.Redirect.DISCARD)

				builder.environment().apply {
					remove(ACTIVATION_TOKEN)
					remove(STARTUP_ID)
					token?.let {
						put(ACTIVATION_TOKEN, it.text)
						put(STARTUP_ID, it.text)
					}
				}

				val process = builder.start()

				// setsid --fork returns once it has forked; without it, the command is left to run as prism's child.
				if (command.firstOrNull()?.endsWith("setsid") == true &&
					process.waitFor(5, TimeUnit.SECONDS) &&
					process.exitValue() != 0
				)
					Launched.Failed(what, "It exited with status ${process.exitValue()}.")
				else
					Launched.Started(what)
			} catch (failed: IOException) {
				Launched.Failed(what, failed.message ?: failed.toString())
			}
		}
	}
}
