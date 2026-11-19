package prism.app

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import prism.macos.DockEntry

/** prism's Dock menu: a window, the folders gone to lately, the launcher and the settings, each saying what it asks. */
class DockMenuTest {
	private val home = Path.of("/Users/a")

	/** [entries] as they read: a title and what it asks, or a line. */
	private fun read(entries: List<DockEntry<DockAsk>>): List<Pair<String, DockAsk?>> =
		entries.map {
			when (it) {
				is DockEntry.Item -> it.title to it.asks
				DockEntry.Line -> "—" to null
			}
		}

	@Test
	fun theFoldersGoneToLatelyAreListedByTheirPaths_FromHomeWhereTheyAreUnderIt() {
		val projects = home.resolve("Projects/prism")
		val volume = Path.of("/Volumes/Backup")

		assertEquals(
			listOf(
				"New Window" to DockAsk.NewWindow,
				"—" to null,
				"~/Projects/prism" to DockAsk.Folder(projects),
				"/Volumes/Backup" to DockAsk.Folder(volume),
				"/Users/a" to DockAsk.Folder(home),
				"—" to null,
				"Show Launcher" to DockAsk.Launcher,
				"—" to null,
				"Settings…" to DockAsk.Settings,
				"Launcher Settings…" to DockAsk.LauncherSettings,
			),
			read(dockMenuOf(listOf(projects, volume, projects, home), home)),
		)
	}

	@Test
	fun withNoFolderGoneToThereIsNoLineForThem_AndAtMostFiveAreListed() {
		assertEquals(
			listOf("New Window", "—", "Show Launcher", "—", "Settings…", "Launcher Settings…"),
			read(dockMenuOf(emptyList(), home)).map { it.first },
		)
		assertEquals(
			5,
			dockMenuOf((1..9).map { home.resolve("f$it") }, home).count {
				(it as? DockEntry.Item)?.asks is DockAsk.Folder
			},
		)
	}
}
