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

/** How a terminal is told the folder to start in: words, [FOLDER] standing for the folder in one of them. */
@JvmInline
value class FolderArgs private constructor(
	val template: List<String>,
) {
	/** The words that say [folder]. */
	fun words(folder: Path): List<String> =
		template.map { it.replace(FOLDER, folder.toString()) }

	companion object {
		const val FOLDER = "{folder}"

		/** No words at all: it starts in the folder it is started in. */
		val None = FolderArgs(emptyList())

		/** The folder alone, a word of its own: what macOS's `open -a Terminal` takes. */
		val Positional = FolderArgs(listOf(FOLDER))

		/** An option the folder is joined to: `--working-directory=` and the folder, one word. */
		fun joined(option: String) = FolderArgs(listOf(option + FOLDER))

		/** An option the folder follows as a word of its own: `--workdir`, then the folder. */
		fun separate(option: String) = FolderArgs(listOf(option, FOLDER))

		/** As a desktop entry's `X-TerminalArgDir=` says it: an option ending in `=` takes the folder joined. */
		fun ofOption(option: String): FolderArgs =
			if (option.endsWith("="))
				joined(option)
			else
				separate(option)

		/**
		 * [template] as written by a person: none, or words of which exactly one says where the folder goes; null for
		 * words that do not say it, or say it twice, which would tell a terminal no folder, or two.
		 */
		fun of(template: List<String>): FolderArgs? =
			FolderArgs(template).takeIf {
				template.isEmpty() ||
					template.count { FOLDER in it } == 1
			}
	}
}

/** How a terminal is given a command to run. */
sealed interface ExecArg {
	/** Words before the command (`-e`, `-x`, `--`, or none): the command's own words follow, each a word of its own. */
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
 * A terminal: [program], its words before any option (a program, or a launcher and what it launches, and the user's
 * own options); how it is told a folder ([folder]) and given a command ([exec]); and [alone], what has it open a window
 * in a process of its own rather than in one already running, where a running one would start where it likes.
 */
data class Terminal(
	val name: String,
	val program: List<String>,
	val folder: FolderArgs,
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

		return program + alone + this.folder.words(folder) + running
	}

	/** This, told a folder and given a command as [described] says where it says; what it does not say is as it was. */
	fun with(described: TerminalChoice.Described): Terminal =
		copy(
			folder = described.folder ?: folder,
			exec = described.exec ?: exec,
			alone = described.alone ?: alone,
		)
}

/**
 * The terminal the user has chosen, as the preferences say it: none (the desktop's own, as found), words to run as
 * they are written, a terminal described, or a choice that could not be read, which is said rather than guessed at.
 */
sealed interface TerminalChoice {
	/** None chosen: the desktop's own, as the platform finds it. */
	data object Found : TerminalChoice

	/**
	 * [words] run as they are written: [FolderArgs.FOLDER] where the folder goes, and [COMMAND], a word of its own,
	 * where a command's words go. Without [COMMAND] it opens in a folder only, and a command runs in the desktop's own.
	 */
	data class Written(
		val words: List<String>,
	) : TerminalChoice {
		/** These words for [folder], running [command] where one is given; null where they have no place for one. */
		fun opening(folder: Path, command: List<String>?): List<String>? {
			val filled = words.map { it.replace(FolderArgs.FOLDER, folder.toString()) }

			return when {
				command == null -> filled.filter { it != COMMAND }
				COMMAND in filled -> filled.flatMap { if (it == COMMAND) command else listOf(it) }
				else -> null
			}
		}

		companion object {
			const val COMMAND = "{command}"
		}
	}

	/**
	 * A terminal described: [command], its program and the user's own options; and, where they are said (not null),
	 * how it is told a folder, how it is given a command, and what opens a window of its own. What is not said is as
	 * the terminal's desktop entry says, or as prism knows the program to take it.
	 */
	data class Described(
		val command: List<String>,
		val folder: FolderArgs? = null,
		val exec: ExecArg? = null,
		val alone: List<String>? = null,
	) : TerminalChoice

	/** A choice written so that it could not be read: [why], and what was written, [written], to be kept as it is. */
	data class Unreadable(
		val written: String,
		val why: String,
	) : TerminalChoice
}

/** The terminals prism knows how to tell a folder and a command, for those whose desktop entry does not say. */
object KnownTerminals {
	private fun joined(option: String) = FolderArgs.joined(option)

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
			Terminal("Konsole", listOf("konsole"), FolderArgs.separate("--workdir"), before("-e")),
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
			Terminal(
				"QTerminal",
				listOf("qterminal"),
				FolderArgs.separate("--workdir"),
				before("-e"),
			),
			Terminal("st", listOf("st"), FolderArgs.separate("-d"), before("-e")),
			Terminal("urxvt", listOf("urxvt"), FolderArgs.separate("-cd"), before("-e")),
			Terminal("XTerm", listOf("xterm"), FolderArgs.None, before("-e")),
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
				FolderArgs.Positional,
				ExecArg.Unsupported,
			),
			Terminal(
				"Terminal",
				listOf("open", "-a", "Terminal"),
				FolderArgs.Positional,
				ExecArg.Unsupported,
			),
		)

	/** On Windows: Windows Terminal (`wt.exe -d`), and PowerShell in a console of its own where there is none. */
	val windows: List<Terminal> =
		listOf(
			Terminal("Windows Terminal", listOf("wt.exe"), FolderArgs.separate("-d"), before()),
			Terminal(
				"PowerShell",
				listOf("cmd.exe", "/c", "start", "", "powershell.exe", "-NoExit"),
				FolderArgs.None,
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

/** The command a terminal is opened with, or why there is none. */
sealed interface TerminalCommand {
	data class Ready(
		val words: List<String>,
	) : TerminalCommand

	data class Failed(
		val what: String,
		val why: String,
	) : TerminalCommand
}
