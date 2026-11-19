package prism.desktop.macos

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import prism.desktop.Application
import prism.desktop.BundleIdentifier
import prism.desktop.KnownTerminals
import prism.desktop.Launched
import prism.desktop.Launcher
import prism.desktop.MacTerminal
import prism.desktop.Terminal
import prism.desktop.TerminalChoice
import prism.desktop.TerminalCommand
import prism.desktop.TerminalOption
import prism.desktop.TerminalOptions
import prism.desktop.asChildOfPrism
import prism.desktop.commandIn

/*
 * Starting what prism opens, on a Mac. An application is started by `open -a`, which asks Launch Services to start it
 * (as a process of Launch Services', not prism's) or to bring it forward with the files, where it is running: so
 * nothing prism opens goes when prism does, and the application's window comes to the front, as anything opened from
 * Finder does. A command of the user's is a process of its own, given nothing of prism's.
 */

/** Starts what prism opens through Launch Services, with the terminals a Mac has. */
class OpenLauncher(
	private val home: Path = Path.of(System.getProperty("user.home")),
	/**
	 * Whether the application of a bundle identifier is installed: known to Launch Services, wherever it is, which is
	 * what `open -b` asks.
	 */
	private val installed: (BundleIdentifier) -> Boolean = {
		prism.macos.Applications.withIdentifier(it.name) !=
			null
	},
) : Launcher {
	override fun launch(
		application: Application,
		paths: List<Path>,
		terminal: TerminalChoice,
	): Launched =
		run(
			application.command +
				paths.map {
					it.toString()
				},
			home,
			application.name,
		)

	override fun run(command: List<String>, folder: Path): Launched =
		run(command, folder, command.firstOrNull()?.let { File(it).name } ?: "the command")

	/**
	 * [command] started in [folder], given nothing of prism's. `open` is waited for, as it says at once whether Launch
	 * Services could start what it was asked to (an application that is not there is said so); anything else is left
	 * to run.
	 */
	private fun run(command: List<String>, folder: Path, what: String): Launched {
		if (command.isEmpty())
			return Launched.Failed("the command", "There is nothing to run.")

		return try {
			val process =
				ProcessBuilder(command)
					.asChildOfPrism()
					.directory(folder.toFile())
					.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.PIPE)
					.start()

			if (command.first() == LaunchServicesApplications.OPEN &&
				process.waitFor(OPEN_WAITED, TimeUnit.SECONDS) &&
				process.exitValue() != 0
			)
				Launched.Failed(
					what,
					process.errorStream.bufferedReader().readText().trim().ifEmpty {
						"It could not be opened."
					},
				)
			else
				Launched.Started(what)
		} catch (failed: IOException) {
			Launched.Failed(what, failed.message ?: failed.toString())
		}
	}

	/** The terminals a Mac has that prism knows, the first installed being the user's: Ghostty, then iTerm, then Terminal. */
	override fun terminals(): TerminalOptions =
		TerminalOptions(
			preferred()?.name,
			KnownTerminals.mac.map { TerminalOption(it.name, it.name, installed(it.bundle)) },
		)

	override fun terminalCommand(
		folder: Path,
		choice: TerminalChoice,
		command: List<String>?,
	): TerminalCommand =
		choice.commandIn(
			folder,
			command,
			::preferred,
			{ named(it)?.terminal },
			{ name -> named(name)?.bundle?.let(installed) == true },
			"known to macOS",
		)

	private fun preferred(): Terminal? =
		KnownTerminals.mac
			.firstOrNull {
				installed(it.bundle)
			}?.terminal

	private fun named(name: String): MacTerminal? =
		KnownTerminals.mac.firstOrNull {
			it.name.equals(name, ignoreCase = true)
		}

	private companion object {
		/** How long `open` is waited for: it hands its work to Launch Services and is done, in no time. */
		const val OPEN_WAITED = 10L
	}
}
