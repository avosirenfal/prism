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
		launcher.terminal(folder, null)
		// A preference naming a program alone is told the folder as that program takes it.
		launcher.terminal(folder, listOf("foot"))
		// One that says where the folder goes is run as it is written.
		launcher.terminal(folder, listOf("foot", "-D", "{folder}"))

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
}
