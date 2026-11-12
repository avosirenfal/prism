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
 * windows (Ghostty) hands a launch given no options to the process already running, and that one opens its window
 * where it likes, typically the home folder. So a terminal is always told the folder, never left to find it.
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
 * A terminal: [program], its words before any option (a program, or a launcher and what it launches); and how it is
 * told a folder ([folder]) and given a command ([exec]).
 */
data class Terminal(
	val name: String,
	val program: List<String>,
	val folder: FolderArgs,
	val exec: ExecArg,
) {
	/** The command that opens this terminal in [folder], running [command] there where one is given; null where it cannot run one. */
	fun opening(folder: Path, command: List<String>? = null): List<String>? {
		val running =
			when {
				command == null -> emptyList()
				exec is ExecArg.Before -> exec.words + command
				else -> return null
			}

		return program + this.folder.words(folder) + running
	}
}

/**
 * The terminal the user has chosen, as the preferences say it: none (the desktop's own, as found), a terminal by its
 * name (which prism knows how to tell a folder and give a command, or whose desktop entry says), the terminal's
 * command lines written out, or a choice that could not be read, which is said rather than guessed at.
 */
sealed interface TerminalChoice {
	/** Whether a terminal can be opened in a folder: "Open terminal here". */
	val opensInFolder: Boolean get() = true

	/** Whether a command can be run in a terminal: the actions and applications that run in one. */
	val runsCommands: Boolean get() = true

	/** None chosen: the desktop's own, as the platform finds it. */
	data object Found : TerminalChoice

	/** A terminal by its program's name (`ghostty`) or its desktop entry's (`com.mitchellh.ghostty.desktop`). */
	data class Named(
		val name: String,
	) : TerminalChoice

	/**
	 * The terminal's command lines, each written out in full for what it is used for: [inFolder], a terminal opened in
	 * a folder ("Open terminal here"); and [running], a terminal opened in a folder running a command there (the
	 * actions and applications that run in a terminal). Each is only filled in, never made from the other; one not
	 * written is not used, and what it is for is off. Made by [of], which refuses neither being written.
	 */
	@ConsistentCopyVisibility
	data class CommandLines private constructor(
		val inFolder: FolderLine?,
		val running: CommandLine?,
	) : TerminalChoice {
		override val opensInFolder: Boolean get() = inFolder != null

		override val runsCommands: Boolean get() = running != null

		companion object {
			fun of(inFolder: FolderLine?, running: CommandLine?): CommandLines? =
				CommandLines(inFolder, running).takeIf { inFolder != null || running != null }
		}
	}

	/** A choice written so that it could not be read: [why], and what was written, [written], to be kept as it is. */
	data class Unreadable(
		val written: String,
		val why: String,
	) : TerminalChoice
}

/** Where a command line's words are to be filled in: [FOLDER] the folder, in a word or joined to an option; [COMMAND], a word of its own, a command's words. */
object Placeholders {
	const val FOLDER = FolderArgs.FOLDER
	const val COMMAND = "{command}"

	/** Why [words] are not a command line saying [FOLDER] once, and [COMMAND] once or ([command] false) never; null where they are. */
	internal fun wrongIn(words: List<String>, command: Boolean): String? =
		when {
			words.isEmpty() -> "it is empty"
			words.count {
				FOLDER in it
			} != 1 -> "it says $FOLDER ${if (words.none { FOLDER in it })
				"nowhere"
			else
				"more than once"}: once, where the folder goes"
			words.any {
				COMMAND in it && it != COMMAND
			} -> "$COMMAND is a word of its own: a command's words take its place"
			!command && COMMAND in words -> "it says $COMMAND, which a terminal opened in a folder is not given"
			command && words.count {
				it == COMMAND
			} != 1 -> "it says $COMMAND ${if (COMMAND !in words)
				"nowhere"
			else
				"more than once"}: once, where a command's words go"
			else -> null
		}
}

/** A command line that opens a terminal in a folder: [words], [Placeholders.FOLDER] once in them, and no command. */
@JvmInline
value class FolderLine private constructor(
	val words: List<String>,
) {
	/** The command line for [folder]. */
	fun filled(folder: Path): List<String> =
		words.map { it.replace(Placeholders.FOLDER, folder.toString()) }

	companion object {
		/** [words] as such a line, or why they are not one. */
		fun of(words: List<String>): Read<FolderLine> =
			Placeholders.wrongIn(words, command = false)?.let { Read.Wrong(it) }
				?: Read.Right(FolderLine(words))
	}
}

/**
 * A command line that opens a terminal in a folder running a command there: [words], [Placeholders.FOLDER] once in them,
 * and [Placeholders.COMMAND] once, a word of its own.
 */
@JvmInline
value class CommandLine private constructor(
	val words: List<String>,
) {
	/** The command line for [command] in [folder]: the command's words, each a word of its own, where it says. */
	fun filled(folder: Path, command: List<String>): List<String> =
		words.flatMap {
			if (it ==
				Placeholders.COMMAND
			)
				command
			else
				listOf(it.replace(Placeholders.FOLDER, folder.toString()))
		}

	companion object {
		fun of(words: List<String>): Read<CommandLine> =
			Placeholders.wrongIn(words, command = true)?.let { Read.Wrong(it) }
				?: Read.Right(CommandLine(words))
	}
}

/** Something read from what a person wrote, or why it could not be. */
sealed interface Read<out T> {
	data class Right<out T>(
		val value: T,
	) : Read<T>

	data class Wrong(
		val why: String,
	) : Read<Nothing>
}

/** The terminals prism knows how to tell a folder and a command, for those whose desktop entry does not say. */
object KnownTerminals {
	private fun joined(option: String) = FolderArgs.joined(option)

	private fun before(vararg words: String) = ExecArg.Before(words.toList())

	/** On Linux and the BSDs, by the name of their program: the first found on the `PATH` is the one used, in this order. */
	val unix: List<Terminal> =
		listOf(
			// One process for all its windows, unless it is told otherwise: then the window opens where it likes.
			// Told a folder, Ghostty opens it in a process of its own: its one process for every window (GTK's single
			// instance) is for launches given no options, and would open where it likes.
			Terminal("Ghostty", listOf("ghostty"), joined("--working-directory="), before("-e")),
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
			Terminal("Ptyxis", listOf("ptyxis"), joined("--working-directory="), before("--")),
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
