package prism.desktop

import java.nio.file.Path

/*
 * Terminals, as a file manager opens one: in a folder ("Open terminal here"), or running a command there. Every
 * terminal is told those two things differently on its command line, and no platform standardizes it: freedesktop.org
 * has a proposal (terminals declaring their options in their desktop entries, `X-TerminalArgDir=` and the like, and
 * the user's choice in `xdg-terminals.list`), which some terminals follow; macOS and Windows open a terminal through
 * their own launchers. So a terminal is described here by what its command line takes, as values, and the command
 * that opens it is a function of that description. Which terminal is the user's is each platform's to find
 * (freedesktop/terminals.kt for Linux).
 *
 * Merely starting a terminal in the folder is not enough for some: a terminal that runs as one process for all its
 * windows (Ghostty, Ptyxis) hands a new launch to the process already running, and that one opens its window where it
 * likes, typically the home folder. Those are told the folder, and to open a window of their own.
 */

/** How a terminal is told the folder to start in. */
sealed interface DirArg {
	/** An option the folder is joined to: `--working-directory=` and the folder, one word. */
	data class Joined(
		val option: String,
	) : DirArg

	/** An option the folder follows as the next word: `--workdir`, then the folder. */
	data class Separate(
		val option: String,
	) : DirArg

	/** The folder alone, as a word of its own: what macOS's `open -a Terminal` takes. */
	data object Positional : DirArg

	/** No option at all: it starts where its process is started, which is the folder. */
	data object ProcessFolder : DirArg

	/** The words that say [folder]. */
	fun words(folder: Path): List<String> =
		when (this) {
			is Joined -> listOf(option + folder)
			is Separate -> listOf(option, folder.toString())
			Positional -> listOf(folder.toString())
			ProcessFolder -> emptyList()
		}

	companion object {
		/** As a desktop entry's `X-TerminalArgDir=` says it: an option ending in `=` takes the folder joined. */
		fun of(option: String): DirArg =
			if (option.endsWith("="))
				Joined(option)
			else
				Separate(option)
	}
}

/** How a terminal is given a command to run. */
sealed interface ExecArg {
	/** Words before the command (`-e`, `-x`, `--`): the command's own words follow, each as a word of its own. */
	data class Before(
		val words: List<String>,
	) : ExecArg

	/** It cannot be given a command on its command line (macOS's Terminal through `open`). */
	data object Unsupported : ExecArg

	companion object {
		/** As a desktop entry's `X-TerminalArgExec=` says it: none (the command follows as it is), or the option. */
		fun of(option: String): ExecArg =
			Before(listOfNotNull(option.takeIf { it.isNotEmpty() }))
	}
}

/**
 * A terminal: [program], its words before any option (a program, or a launcher and what it launches); how it is told
 * a folder ([dir]) and given a command ([exec]); and [alone], what has it open a window in a process of its own rather
 * than in one already running, where a running one would start where it likes.
 */
data class Terminal(
	val name: String,
	val program: List<String>,
	val dir: DirArg,
	val exec: ExecArg,
	val alone: List<String> = emptyList(),
) {
	/** The command that opens this terminal in [folder], running [command] there where one is given; null where it cannot run one. */
	fun opening(folder: Path, command: List<String>? = null): List<String>? {
		val running =
			when {
				command == null -> emptyList()
				exec is ExecArg.Before -> exec.words + command
				else -> return null
			}

		return program + alone + dir.words(folder) + running
	}
}

/** The terminals prism knows how to tell a folder and a command, for those whose desktop entry does not say. */
object KnownTerminals {
	private fun joined(option: String) = DirArg.Joined(option)

	private fun before(vararg words: String) = ExecArg.Before(words.toList())

	/** On Linux and the BSDs, by the name of their program: the first found on the `PATH` is the one used, in this order. */
	val unix: List<Terminal> =
		listOf(
			// One process for all its windows, unless it is told otherwise: then the window opens where it likes.
			Terminal(
				"Ghostty",
				listOf("ghostty"),
				joined("--working-directory="),
				before("-e"),
				listOf("--gtk-single-instance=false"),
			),
			Terminal("foot", listOf("foot"), joined("--working-directory="), before()),
			Terminal("kitty", listOf("kitty"), joined("--directory="), before()),
			Terminal(
				"Alacritty",
				listOf("alacritty"),
				joined("--working-directory="),
				before("-e"),
			),
			Terminal("WezTerm", listOf("wezterm", "start"), joined("--cwd="), before("--")),
			Terminal("Rio", listOf("rio"), joined("--working-dir="), before("-e")),
			// GNOME's: Ptyxis keeps one process too, and opens a window of the new one's own with --new-window.
			Terminal(
				"Ptyxis",
				listOf("ptyxis"),
				joined("--working-directory="),
				before("--"),
				listOf("--new-window"),
			),
			Terminal("Console", listOf("kgx"), joined("--working-directory="), before("--")),
			Terminal(
				"GNOME Terminal",
				listOf("gnome-terminal"),
				joined("--working-directory="),
				before("--"),
			),
			Terminal("Konsole", listOf("konsole"), DirArg.Separate("--workdir"), before("-e")),
			Terminal(
				"Xfce Terminal",
				listOf("xfce4-terminal"),
				joined("--working-directory="),
				before("-x"),
			),
			Terminal(
				"MATE Terminal",
				listOf("mate-terminal"),
				joined("--working-directory="),
				before("-x"),
			),
			Terminal("Tilix", listOf("tilix"), joined("--working-directory="), before("-x")),
			Terminal(
				"Terminator",
				listOf("terminator"),
				joined("--working-directory="),
				before("-x"),
			),
			Terminal(
				"LXTerminal",
				listOf("lxterminal"),
				joined("--working-directory="),
				before("-e"),
			),
			Terminal("QTerminal", listOf("qterminal"), DirArg.Separate("--workdir"), before("-e")),
			Terminal("st", listOf("st"), DirArg.Separate("-d"), before("-e")),
			Terminal("urxvt", listOf("urxvt"), DirArg.Separate("-cd"), before("-e")),
			Terminal("XTerm", listOf("xterm"), DirArg.ProcessFolder, before("-e")),
		)

	/**
	 * On macOS, through `open`: Terminal and iTerm take the folder as what they open, and no command; Ghostty, as an
	 * application of its own (`-n`), takes its options after `--args`.
	 */
	val mac: List<Terminal> =
		listOf(
			Terminal(
				"Ghostty",
				listOf("open", "-na", "Ghostty", "--args"),
				joined("--working-directory="),
				before("-e"),
			),
			Terminal(
				"iTerm",
				listOf("open", "-a", "iTerm"),
				DirArg.Positional,
				ExecArg.Unsupported,
			),
			Terminal(
				"Terminal",
				listOf("open", "-a", "Terminal"),
				DirArg.Positional,
				ExecArg.Unsupported,
			),
		)

	/** On Windows: Windows Terminal (`wt.exe -d`), and PowerShell in a console of its own where there is none. */
	val windows: List<Terminal> =
		listOf(
			Terminal("Windows Terminal", listOf("wt.exe"), DirArg.Separate("-d"), before()),
			Terminal(
				"PowerShell",
				listOf("cmd.exe", "/c", "start", "", "powershell.exe", "-NoExit"),
				DirArg.ProcessFolder,
				before("-Command"),
			),
		)

	/** The terminal of [program]'s name on Linux and the BSDs (a path, or a name), with that program; null for one not known. */
	fun unixOf(program: String): Terminal? {
		val name = program.substringAfterLast('/')

		return unix.firstOrNull { it.program.first() == name }?.let {
			it.copy(
				program =
					listOf(program) + it.program.drop(1),
			)
		}
	}
}
