package prism.app

import java.nio.file.Path
import prism.browser.Request
import prism.explorer.Explorer
import prism.macos.DockEntry

/*
 * prism's menu in a Mac's Dock (a press on its icon, or a long click): a window, the folders gone to lately, the
 * launcher, and the settings, as Finder's and Terminal's have theirs. What it holds is plain data, made again each
 * time it is shown, and what each item asks for is done by the explorer as a window's asking would be.
 */

/** What an item of prism's Dock menu asks for. */
internal sealed interface DockAsk {
	/** A window on the home folder. */
	data object NewWindow : DockAsk

	/** A window on [folder]. */
	data class Folder(
		val folder: Path,
	) : DockAsk

	/** The launcher shown. */
	data object Launcher : DockAsk

	/** prism's settings. */
	data object Settings : DockAsk

	/** The launcher's settings. */
	data object LauncherSettings : DockAsk
}

/** How many of the folders gone to lately the Dock's menu lists. */
private const val RECENT = 5

/**
 * prism's Dock menu, with the folders gone to lately ([recent], the latest first), each by its path, written from the
 * home folder ([home]) where it is under it: `~/Projects/prism`, which tells two of one name apart.
 */
internal fun dockMenuOf(recent: List<Path>, home: Path): List<DockEntry<DockAsk>> {
	val folders =
		recent.distinct().take(RECENT).map {
			DockEntry.Item(
				if (it.startsWith(home) && it != home)
					"~/${home.relativize(it)}"
				else
					it.toString(),
				DockAsk.Folder(it),
			)
		}

	return listOf(DockEntry.Item("New Window", DockAsk.NewWindow)) +
		(
			if (folders.isEmpty())
				emptyList()
			else
				listOf(DockEntry.Line) + folders
		) +
		listOf(
			DockEntry.Line,
			DockEntry.Item("Show Launcher", DockAsk.Launcher),
			DockEntry.Line,
			DockEntry.Item("Settings…", DockAsk.Settings),
			DockEntry.Item("Launcher Settings…", DockAsk.LauncherSettings),
		)
}

/** What [ask] asks for, done by [explorer], whose home folder is [home]. */
internal fun Explorer.done(ask: DockAsk, home: Path) =
	when (ask) {
		DockAsk.NewWindow -> browse(home)
		is DockAsk.Folder -> browse(ask.folder)
		DockAsk.Launcher -> launch()
		DockAsk.Settings -> ask(Request.Preferences)
		DockAsk.LauncherSettings -> launcherSettings()
	}
