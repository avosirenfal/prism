package prism.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.DetachedLauncher
import prism.desktop.freedesktop.FreedesktopTerminals
import prism.desktop.freedesktop.Xdg

/**
 * Terminals opened in a folder, and given a command: the command each kind is opened with, and which terminal is the
 * user's on a freedesktop.org desktop, from each place one can be said.
 */
class TerminalsTest {
	@TempDir
	lateinit var root: Path

	private val folder: Path get() = root.resolve("my files")

	private val bin: Path get() = root.resolve("bin").createDirectories()

	/** Programs on a `PATH` of the test's own. */
	private fun installed(vararg names: String) =
		names.forEach {
			bin
				.resolve(it)
				.also { file ->
					file.writeText("#!/bin/sh\n")
				}.toFile()
				.setExecutable(true)
		}

	private fun xdg(vararg more: Pair<String, String>) =
		Xdg(
			mapOf(
				"XDG_DATA_HOME" to root.resolve("home/.local/share").toString(),
				"XDG_DATA_DIRS" to root.resolve("usr/share").toString(),
				"XDG_CONFIG_HOME" to root.resolve("home/.config").toString(),
				"XDG_CONFIG_DIRS" to root.resolve("etc/xdg").toString(),
				"PATH" to bin.toString(),
			) + more,
			root.resolve("home"),
		)

	private fun terminals(xdg: Xdg) =
		FreedesktopTerminals(
			xdg,
		) { name -> bin.resolve(name).takeIf { Files.isExecutable(it) } }

	private fun entry(id: String, text: String) =
		root
			.resolve("usr/share/applications")
			.createDirectories()
			.resolve(id)
			.writeText("[Desktop Entry]\nType=Application\n$text\n")

	private fun opened(terminal: Terminal?, command: List<String>? = null): List<String>? =
		terminal?.opening(folder, command)?.let {
			listOf(Path.of(it.first()).fileName.toString()) +
				it.drop(1)
		}

	@Test
	fun eachTerminalIsToldTheFolderAndTheCommandAsItTakesThem() {
		fun known(name: String) = KnownTerminals.unixOf(name)

		// Ghostty runs one process for its windows, and is told to open one of its own, where it was asked to.
		assertEquals(
			listOf(
				"ghostty",
				"--working-directory=$folder",
				"-e",
				"htop",
			),
			opened(known("ghostty"), listOf("htop")),
		)
		assertEquals(listOf("kitty", "--directory=$folder"), opened(known("kitty")))
		assertEquals(
			listOf("wezterm", "start", "--cwd=$folder", "--", "htop"),
			opened(known("wezterm"), listOf("htop")),
		)
		assertEquals(
			listOf("konsole", "--workdir", folder.toString(), "-e", "htop"),
			opened(known("konsole"), listOf("htop")),
		)
		assertEquals(
			listOf("foot", "--working-directory=$folder", "htop"),
			opened(known("foot"), listOf("htop")),
		)
		// XTerm has no option for it: it starts where it is started.
		assertEquals(listOf("xterm"), opened(known("xterm")))

		// macOS's Terminal opens the folder, and cannot be given a command; Windows Terminal takes -d.
		val mac = KnownTerminals.mac.single { it.name == "Terminal" }

		assertEquals(listOf("open", "-a", "Terminal", folder.toString()), mac.opening(folder))
		assertNull(mac.opening(folder, listOf("htop")))
		assertEquals(
			listOf("wt.exe", "-d", folder.toString(), "htop"),
			KnownTerminals.windows.first().opening(folder, listOf("htop")),
		)
	}

	@Test
	fun theUsersTerminalIsFoundWhereverItIsSaid() {
		installed("xterm", "kitty", "ghostty", "my-term")

		// With nothing said, the first prism knows that is installed.
		assertEquals(
			listOf("ghostty", "--working-directory=$folder"),
			opened(terminals(xdg()).preferred()),
		)

		// A desktop entry of the category, which says how it is told a folder, before one prism merely knows of.
		entry(
			"org.example.MyTerm.desktop",
			"Name=My Term\nExec=my-term --login %U\nCategories=System;TerminalEmulator;\nX-TerminalArgDir=--in\nX-TerminalArgExec=--run",
		)
		assertEquals(
			listOf("my-term", "--login", "--in", folder.toString()),
			opened(terminals(xdg()).preferred()),
		)
		Files.delete(bin.resolve("ghostty"))
		Files.delete(bin.resolve("kitty"))
		Files.delete(bin.resolve("xterm"))
		assertEquals(
			listOf("my-term", "--login", "--in", folder.toString(), "--run", "htop"),
			opened(terminals(xdg()).preferred(), listOf("htop")),
		)
		installed("xterm", "kitty", "ghostty")

		// The user's list (xdg-terminal-exec's), for their desktop before their own, before anything installed.
		entry("kitty.desktop", "Name=kitty\nExec=kitty\nCategories=TerminalEmulator;")
		root
			.resolve(
				"home/.config",
			).createDirectories()
			.resolve(
				"xdg-terminals.list",
			).writeText("# mine\n-ghostty.desktop\nkitty.desktop\n")
		assertEquals(
			listOf("kitty", "--directory=$folder"),
			opened(terminals(xdg()).preferred()),
		)
		root
			.resolve(
				"home/.config/niri-xdg-terminals.list",
			).writeText("org.example.MyTerm.desktop:new-window\n")
		assertEquals(
			listOf("my-term", "--login", "--in", folder.toString()),
			opened(terminals(xdg("XDG_CURRENT_DESKTOP" to "niri")).preferred()),
		)

		// $TERMINAL before all of them, told the folder as prism knows its program to be.
		assertEquals(
			listOf("ghostty", "--working-directory=$folder"),
			opened(
				terminals(xdg("TERMINAL" to "ghostty", "XDG_CURRENT_DESKTOP" to "niri")).preferred(),
			),
		)
		// A program prism does not know: started in the folder, and given -e, as the proposal's compatible mode does.
		installed("odd-term")
		assertEquals(
			listOf("odd-term", "-x"),
			opened(terminals(xdg("TERMINAL" to "odd-term -x")).preferred()),
		)
		assertEquals(
			listOf("odd-term", "-x", "-e", "htop"),
			opened(
				terminals(
					xdg("TERMINAL" to "odd-term -x"),
				).preferred(),
				listOf("htop"),
			),
		)
	}

	@Test
	fun openTerminalHereTellsTheTerminalTheFolderAndAPreferenceIsReadTheSameWay() {
		installed("setsid", "ghostty", "foot")

		val started = ArrayList<Pair<List<String>, Path>>()
		val launcher =
			DetachedLauncher(xdg()) { command, at, _ ->
				started += command to at
				Launched.Started("")
			}

		// The desktop's own: started in the folder, and told it.
		launcher.terminal(folder, TerminalChoice.Found)
		// A terminal chosen by name is told the folder as that terminal takes it.
		launcher.terminal(folder, TerminalChoice.Named("foot"))
		// One that says where the folder goes is run as it is written.
		launcher.terminal(folder, lines(listOf("foot", "-D", "{folder}"), null))

		val words =
			started.map { (command, _) ->
				command.drop(2).let {
					listOf(Path.of(it.first()).fileName.toString()) +
						it.drop(1)
				}
			}

		assertEquals(
			listOf("ghostty", "--working-directory=$folder"),
			words[0],
		)
		assertEquals(listOf("foot", "--working-directory=$folder"), words[1])
		assertEquals(listOf("foot", "-D", folder.toString()), words[2])
		assertEquals(listOf(folder, folder, folder), started.map { it.second })
	}

	private fun <T> right(read: Read<T>): T = (read as Read.Right<T>).value

	private fun lines(open: List<String>?, run: List<String>?): TerminalChoice.CommandLines =
		TerminalChoice.CommandLines.of(
			open?.let {
				right(FolderLine.of(it))
			},
			run?.let { right(CommandLine.of(it)) },
		)!!

	@Test
	fun aTerminalChosenIsOpenedByItsNameOrItsCommandLinesAndEachLineOnlyFilledIn() {
		installed("ghostty", "foot", "my-term")
		entry(
			"org.example.MyTerm.desktop",
			"Name=My Term\nExec=my-term\nCategories=TerminalEmulator;\nX-TerminalArgDir=--in=\nX-TerminalArgExec=--run",
		)

		val launcher = DetachedLauncher(xdg()) { _, _, _ -> Launched.Started("") }

		fun opened(choice: TerminalChoice, command: List<String>? = null): Any =
			when (val opening = launcher.terminalCommand(folder, choice, command)) {
				is TerminalCommand.Ready ->
					listOf(Path.of(opening.words.first()).fileName.toString()) + opening.words.drop(1)
				is TerminalCommand.Failed -> opening.why
			}

		// By name: as prism knows it, or as the terminal's own desktop entry says.
		assertEquals(
			listOf("ghostty", "--working-directory=$folder", "-e", "htop"),
			opened(TerminalChoice.Named("ghostty"), listOf("htop")),
		)
		assertEquals(
			listOf("my-term", "--in=$folder", "--run", "htop"),
			opened(TerminalChoice.Named("my-term"), listOf("htop")),
		)
		assertEquals(
			listOf("my-term", "--in=$folder"),
			opened(TerminalChoice.Named("org.example.MyTerm.desktop")),
		)

		// By its command lines: each used for what it is for, and only filled in, word for word.
		val both =
			lines(
				listOf("ghostty", "--font-size=14", "--working-directory={folder}"),
				listOf(
					"ghostty",
					"--font-size=14",
					"--working-directory={folder}",
					"-e",
					"{command}",
					"--hold",
				),
			)

		assertEquals(
			listOf("ghostty", "--font-size=14", "--working-directory=$folder"),
			opened(both),
		)
		assertEquals(
			listOf(
				"ghostty",
				"--font-size=14",
				"--working-directory=$folder",
				"-e",
				"htop",
				"-d",
				"1",
				"--hold",
			),
			opened(both, listOf("htop", "-d", "1")),
		)

		// A line not written is not used, and nothing else is run in its place.
		val openOnly = lines(listOf("foot", "-D", "{folder}"), null)
		val runOnly = lines(null, listOf("foot", "-D", "{folder}", "{command}"))

		assertTrue(openOnly.opensInFolder && !openOnly.runsCommands)
		assertTrue(!runOnly.opensInFolder && runOnly.runsCommands)
		assertEquals(
			"No command line is set for running a command.",
			opened(openOnly, listOf("htop")),
		)
		assertEquals("No command line is set for opening a folder.", opened(runOnly))
		assertEquals(
			listOf("foot", "-D", folder.toString(), "htop"),
			opened(runOnly, listOf("htop")),
		)

		// A line that cannot be filled in rightly cannot be made: each says {folder} once; a running line, {command}
		// once and as a word of its own; an opening line, none.
		listOf(
			listOf("foot"),
			listOf("foot", "{folder}", "{folder}"),
			listOf("foot", "{folder}", "{command}"),
			emptyList(),
		).forEach { assertTrue(FolderLine.of(it) is Read.Wrong, "$it") }
		listOf(
			listOf("foot", "{folder}"),
			listOf("foot", "-e={command}", "{folder}"),
			listOf("f", "{folder}", "{command}", "{command}"),
		).forEach { assertTrue(CommandLine.of(it) is Read.Wrong, "$it") }
		assertNull(TerminalChoice.CommandLines.of(null, null))

		// A name not installed, or one nothing says how to tell a folder: said, not guessed at.
		installed("odd-term")
		assertEquals(
			"“not-here” isn't installed or isn't on the PATH.",
			opened(TerminalChoice.Named("not-here")),
		)
		assertTrue(
			(
				opened(
					TerminalChoice.Named("odd-term"),
				) as String
			).contains("Set its command lines"),
		)
		assertEquals("it's wrong", opened(TerminalChoice.Unreadable("{}", "it's wrong")))
	}
}
