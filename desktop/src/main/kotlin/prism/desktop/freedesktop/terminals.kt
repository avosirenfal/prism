package prism.desktop.freedesktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import prism.desktop.DirArg
import prism.desktop.ExecArg
import prism.desktop.KnownTerminals
import prism.desktop.Terminal

/*
 * The user's terminal on a freedesktop.org desktop. There is no one standard for it, so each place one is said is
 * asked, the most deliberate first:
 *
 * 1. `$TERMINAL`, which many users and window managers' configurations set;
 * 2. `xdg-terminals.list`, the proposed standard's (xdg-terminal-exec's) list of desktop entries, in the user's
 *    settings, then the system's, then the data folders, each folder's file for the session's desktop before its own;
 * 3. KDE's own setting (`TerminalApplication` in `kdeglobals`);
 * 4. Debian's `x-terminal-emulator`, the program its alternatives point at;
 * 5. the installed desktop entries of the category `TerminalEmulator`, those prism knows first;
 * 6. the terminals prism knows, on the `PATH`.
 *
 * A terminal named by a desktop entry is told a folder and a command as the entry says (the proposal's
 * `X-TerminalArgDir=` and `X-TerminalArgExec=`), where it says; otherwise as prism knows it to take them
 * ([KnownTerminals]); otherwise it is started in the folder and given `-e`, as the proposal's compatible mode does.
 */

/** The terminals on [xdg]'s desktop, [find] saying where a program is on the `PATH`. */
class FreedesktopTerminals(
	private val xdg: Xdg,
	private val find: (String) -> Path?,
) {
	/** The user's terminal, as the first place that names one installed says; null where none is found at all. */
	fun preferred(): Terminal? =
		sequenceOf(
			{ xdg["TERMINAL"]?.let(::ofCommandLine) },
			{ listed() },
			{ kde() },
			{ find("x-terminal-emulator")?.let(::ofAlternative) },
			{ categorized() },
			{ KnownTerminals.unix.firstOrNull { find(it.program.first()) != null } },
		).firstNotNullOfOrNull { it() }

	/**
	 * The terminal [words] say, as a person writes one (`ghostty`, or `kitty --single-instance`): prism's description
	 * of the program, with the words given kept; or, for a program prism does not know, started in the folder with
	 * `-e`. Null where the program is not installed.
	 */
	fun ofCommandLine(words: List<String>): Terminal? {
		val program = words.firstOrNull() ?: return null
		val found = find(program) ?: return null
		val known = KnownTerminals.unixOf(program)

		return known?.copy(
			program =
				listOf(found.toString()) + known.program.drop(1) + words.drop(1),
		)
			?: Terminal(
				program,
				listOf(found.toString()) + words.drop(1),
				DirArg.ProcessFolder,
				ExecArg.Before(listOf("-e")),
			)
	}

	private fun ofCommandLine(line: String): Terminal? =
		Exec.split(line)?.let(::ofCommandLine)

	/** The first installed terminal the `xdg-terminals.list` files name. */
	private fun listed(): Terminal? {
		val folders =
			xdg.config + xdg.data.map { it.resolve("xdg-terminal-exec") }

		val files =
			folders.flatMap { folder ->
				xdg.desktops.map { folder.resolve("$it-xdg-terminals.list") } +
					folder.resolve("xdg-terminals.list")
			}

		return files
			.asSequence()
			.flatMap { listedIn(it) }
			.firstNotNullOfOrNull(::ofEntry)
	}

	/** The desktop entries [file] names, in order: what it excludes (`-`) and its directives (`/`) left out. */
	private fun listedIn(file: Path): Sequence<String> =
		try {
			Files
				.readAllLines(file)
				.asSequence()
				.map { it.trim() }
				.filter {
					it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("-") &&
						!it.startsWith("/")
				}.map { it.removePrefix("+").substringBefore(':') }
		} catch (missing: IOException) {
			emptySequence()
		}

	/** KDE's terminal, where the session is KDE's: its desktop entry, or its program. */
	private fun kde(): Terminal? {
		if ("kde" !in xdg.desktops)
			return null

		val general =
			xdg.config
				.firstNotNullOfOrNull {
					KeyFile.read(it.resolve("kdeglobals"))
				}?.get("General")
				.orEmpty()

		return general["TerminalService"]?.let(::ofEntry)
			?: general["TerminalApplication"]?.let(::ofCommandLine)
	}

	/** What Debian's `x-terminal-emulator` is: the program its alternatives point at, as prism knows it if it does. */
	private fun ofAlternative(link: Path): Terminal? {
		val real =
			try {
				link.toRealPath()
			} catch (failed: IOException) {
				link
			}

		return KnownTerminals.unixOf(real.toString())
			?: Terminal(
				"x-terminal-emulator",
				listOf(link.toString()),
				DirArg.ProcessFolder,
				ExecArg.Before(listOf("-e")),
			)
	}

	/** The installed terminals' desktop entries, those prism knows the program of first. */
	private fun categorized(): Terminal? {
		val ids =
			xdg.data
				.map { it.resolve("applications") }
				.flatMap { folder ->
					try {
						Files.list(folder).use { files ->
							files
								.filter { it.fileName.toString().endsWith(".desktop") }
								.map { it.fileName.toString() }
								.toList()
						}
					} catch (missing: IOException) {
						emptyList()
					}
				}.distinct()

		val terminals =
			ids.mapNotNull { id ->
				entryOf(id)
					?.takeIf { (_, keys) ->
						"TerminalEmulator" in itemsOf(keys["Categories"])
					}?.let { ofEntry(id) }
			}

		return terminals.firstOrNull { KnownTerminals.unixOf(it.program.first()) != null }
			?: terminals.firstOrNull()
	}

	/** The desktop entry [id], where it is installed: its file, and its keys. */
	private fun entryOf(id: String): Pair<Path, Map<String, String>>? =
		xdg.data
			.map { it.resolve("applications").resolve(id) }
			.firstNotNullOfOrNull { file ->
				KeyFile.read(file)?.let { file to it["Desktop Entry"] }
			}?.takeIf { (_, keys) -> keys["Type"] == "Application" && keys["Hidden"] != "true" }

	/**
	 * The terminal the desktop entry [id] is, where it is installed and its program is: told a folder and a command as
	 * the entry says, or as prism knows its program to be, or started in the folder with `-e`.
	 */
	fun ofEntry(id: String): Terminal? {
		val (_, keys) = entryOf(id) ?: return null
		val words =
			Exec
				.split(keys["Exec"] ?: return null)
				// Field codes (`%U`) are for files to open, which a terminal opened in a folder is not given.
				?.filterNot { it.length == 2 && it[0] == '%' }
				?.takeIf { it.isNotEmpty() }
				?: return null
		val found = find(words.first()) ?: return null
		val known = KnownTerminals.unixOf(words.first())

		return Terminal(
			name = keys["Name"] ?: id.removeSuffix(".desktop"),
			program = listOf(found.toString()) + words.drop(1),
			dir = keys["X-TerminalArgDir"]?.let(DirArg::of) ?: known?.dir ?: DirArg.ProcessFolder,
			exec =
				keys["X-TerminalArgExec"]?.let(ExecArg::of) ?: known?.exec
					?: ExecArg.Before(listOf("-e")),
			alone = known?.alone.orEmpty(),
		)
	}
}
