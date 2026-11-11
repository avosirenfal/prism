package prism.desktop.freedesktop

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import prism.desktop.Application
import prism.desktop.Launched
import prism.desktop.Launcher

/*
 * Starting what prism opens as processes of their own. A process the JVM starts is its child, in its session and its
 * group of processes: a terminal's hangup reaches it, and a service manager stopping prism stops it too. So each
 * command is started through `setsid --fork`, which starts it in a session of its own and returns at once, leaving it
 * a child of init (or of the user's service manager) rather than of prism. Where the user's systemd is running, it is
 * started in a scope of its own as well (`systemd-run --user --scope`, in `app.slice`, as desktops start
 * applications), so it is not in prism's group of processes either, and a `systemctl --user stop` of prism's service
 * leaves it running.
 *
 * Once started that way, a command that fails to start fails where prism cannot see it; so the program is looked for
 * on the `PATH` first, and one that is not there is said not to be, rather than started into nothing.
 */

/** Starts commands detached from prism, as [xdg]'s environment says the desktop is. */
class DetachedLauncher(
	private val xdg: Xdg,
	/** What starts a command, its words in full, in a folder: a process, unless a test says otherwise. */
	private val spawn: (List<String>, Path) -> Launched = ::spawned,
) : Launcher {
	override fun launch(application: Application, paths: List<Path>): Launched {
		val commands =
			Exec.expand(application.command, paths, application).map {
				if (application.inTerminal)
					inTerminal(it)
						?: return Launched.Failed(application.name, "no terminal to run it in was found")
				else
					it
			}

		return commands
			.map { run(it, xdg.home, label = application.id) }
			.firstOrNull { it is Launched.Failed }
			?: Launched.Started(application.name)
	}

	override fun run(command: List<String>, folder: Path): Launched =
		run(command, folder, label = null)

	private fun run(command: List<String>, folder: Path, label: String?): Launched {
		val program =
			command.firstOrNull() ?: return Launched.Failed("nothing", "there is nothing to run")

		if (find(program) == null)
			return Launched.Failed(program, "“$program” is not installed, or not on the PATH")

		return spawn(detached(command, label), folder)
	}

	override fun terminal(folder: Path, preferred: List<String>?): Launched {
		val command =
			preferred?.map { it.replace("{folder}", folder.toString()) }
				?: terminals().firstOrNull()
				?: return Launched.Failed(
					"a terminal",
					"no terminal was found: set one in the preferences",
				)

		return run(command, folder)
	}

	override fun inTerminal(command: List<String>, folder: Path): Launched =
		inTerminal(command)?.let { run(it, folder) }
			?: Launched.Failed(command.firstOrNull() ?: "nothing", "no terminal to run it in was found")

	/** [command] run in a terminal: `xdg-terminal-exec` where there is one, or the terminal found, with `-e`. */
	private fun inTerminal(command: List<String>): List<String>? =
		when {
			find("xdg-terminal-exec") != null -> listOf("xdg-terminal-exec") + command
			else -> terminals().firstOrNull()?.let { it + "-e" + command }
		}

	/** The terminals there are, the one the user's environment names first. */
	private fun terminals(): List<List<String>> =
		(
			listOfNotNull(xdg["TERMINAL"]) +
				listOf(
					"xdg-terminal-exec",
					"x-terminal-emulator",
					"foot",
					"kitty",
					"alacritty",
					"wezterm",
					"ghostty",
					"gnome-terminal",
					"konsole",
					"xfce4-terminal",
					"xterm",
				)
		).filter { find(it.substringBefore(' ')) != null }
			.map { listOf(it) }

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
					"--unit=app-prism-${unitPart(
						label ?: command.first(),
					)}-${UUID.randomUUID().toString().take(8)}",
					"--",
				)
			else
				emptyList()

		return setsid + scope + command
	}

	/** [text] as a unit name may have it: letters, digits, `:`, `_`, `.` and `-`; anything else made `_`. */
	private fun unitPart(text: String): String =
		File(text)
			.name
			.removeSuffix(".desktop")
			.map {
				if (it.isLetterOrDigit() ||
					it in ":_.-"
				)
					it
				else
					'_'
			}.joinToString("")

	/** [program] where it is: as it is where it has a `/`, or in a folder of the `PATH`; null where it is not. */
	private fun find(program: String): Path? {
		if ('/' in program)
			return Path.of(program).takeIf { Files.isExecutable(it) }

		return xdg["PATH"]
			.orEmpty()
			.split(':')
			.filter { it.isNotEmpty() }
			.map { Path.of(it, program) }
			.firstOrNull { Files.isExecutable(it) && !Files.isDirectory(it) }
	}

	private companion object {
		/**
		 * [command] started in [folder], with nothing of prism's: no input, and what it writes thrown away. It is
		 * waited for only as long as `setsid --fork` takes to hand it on, which is no time.
		 */
		fun spawned(command: List<String>, folder: Path): Launched {
			val what = command.last { !it.startsWith("-") }.let { File(it).name }

			return try {
				val process =
					ProcessBuilder(command)
						.directory(folder.toFile())
						.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
						.redirectOutput(ProcessBuilder.Redirect.DISCARD)
						.redirectError(ProcessBuilder.Redirect.DISCARD)
						.start()

				// setsid --fork returns once it has forked; without it, the command is left to run as prism's child.
				if (command.firstOrNull()?.endsWith("setsid") == true &&
					process.waitFor(5, TimeUnit.SECONDS) &&
					process.exitValue() != 0
				)
					Launched.Failed(what, "it could not be started (status ${process.exitValue()})")
				else
					Launched.Started(what)
			} catch (failed: IOException) {
				Launched.Failed(what, failed.message ?: failed.toString())
			}
		}
	}
}
