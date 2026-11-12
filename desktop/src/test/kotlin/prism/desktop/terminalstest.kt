package prism.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
				"--gtk-single-instance=false",
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
			listOf("ghostty", "--gtk-single-instance=false", "--working-directory=$folder"),
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
			listOf("ghostty", "--gtk-single-instance=false", "--working-directory=$folder"),
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
			DetachedLauncher(xdg()) { command, at ->
				started += command to at
				Launched.Started("")
			}

		// The desktop's own: started in the folder, and told it.
		launcher.terminal(folder, TerminalChoice.Found)
		// A preference naming a program alone is told the folder as that program takes it.
		launcher.terminal(folder, TerminalChoice.Described(listOf("foot")))
		// One that says where the folder goes is run as it is written.
		launcher.terminal(folder, TerminalChoice.Written(listOf("foot", "-D", "{folder}")))

		val words =
			started.map { (command, _) ->
				command.drop(2).let {
					listOf(Path.of(it.first()).fileName.toString()) +
						it.drop(1)
				}
			}

		assertEquals(
			listOf("ghostty", "--gtk-single-instance=false", "--working-directory=$folder"),
			words[0],
		)
		assertEquals(listOf("foot", "--working-directory=$folder"), words[1])
		assertEquals(listOf("foot", "-D", folder.toString()), words[2])
		assertEquals(listOf(folder, folder, folder), started.map { it.second })
	}

	@Test
	fun aTerminalChosenIsOpenedAsTheUserSaysWhereTheySayAndAsKnownWhereNot() {
		installed("ghostty", "foot", "my-term")

		val launcher = DetachedLauncher(xdg()) { _, _ -> Launched.Started("") }

		fun opened(choice: TerminalChoice, command: List<String>? = null): Any =
			when (val opening = launcher.terminalCommand(folder, choice, command)) {
				is TerminalCommand.Ready ->
					listOf(Path.of(opening.words.first()).fileName.toString()) +
						opening.words.drop(1)
				is TerminalCommand.Failed -> opening.why
			}

		// The user's own options kept, and the folder and a window of its own as Ghostty is known to take them.
		assertEquals(
			listOf(
				"ghostty",
				"--font-size=14",
				"--gtk-single-instance=false",
				"--working-directory=$folder",
				"-e",
				"htop",
			),
			opened(TerminalChoice.Described(listOf("ghostty", "--font-size=14")), listOf("htop")),
		)
		// What the user says in place of what is known: a terminal that has changed how it takes a folder, say.
		assertEquals(
			listOf("ghostty", "--cwd", folder.toString(), "-e", "htop"),
			opened(
				TerminalChoice.Described(
					listOf("ghostty"),
					FolderArgs.separate("--cwd"),
					alone = emptyList(),
				),
				listOf("htop"),
			),
		)
		// A terminal prism does not know, described in full.
		assertEquals(
			listOf("my-term", "--in=$folder", "--run", "htop"),
			opened(
				TerminalChoice.Described(
					listOf("my-term"),
					FolderArgs.joined("--in="),
					ExecArg.Before(listOf("--run")),
				),
				listOf("htop"),
			),
		)
		// Words as written: a command where {command} is; where there is none, a command runs in the desktop's own.
		assertEquals(
			listOf("foot", "-D", folder.toString(), "htop", "-d", "1"),
			opened(
				TerminalChoice.Written(listOf("foot", "-D", "{folder}", "{command}")),
				listOf("htop", "-d", "1"),
			),
		)
		assertEquals(
			listOf("foot", "-D", folder.toString()),
			opened(TerminalChoice.Written(listOf("foot", "-D", "{folder}"))),
		)
		assertEquals(
			listOf(
				"ghostty",
				"--gtk-single-instance=false",
				"--working-directory=$folder",
				"-e",
				"htop",
			),
			opened(TerminalChoice.Written(listOf("foot", "-D", "{folder}")), listOf("htop")),
		)
		// What cannot be: said, not guessed at.
		assertEquals(
			"“not-here” is not installed, or not on the PATH",
			opened(TerminalChoice.Described(listOf("not-here"))),
		)
		assertEquals("it's wrong", opened(TerminalChoice.Unreadable("{}", "it's wrong")))
		assertEquals(
			"it cannot be given a command to run",
			opened(
				TerminalChoice.Described(listOf("my-term"), exec = ExecArg.Unsupported),
				listOf("htop"),
			),
		)
	}
}
