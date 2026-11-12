package prism.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.nio.file.Path
import kotlin.io.path.Path
import kotlinx.coroutines.flow.first
import prism.browser.Finder
import prism.browser.JvmDisk
import prism.browser.JvmFolderWatcher
import prism.browser.Search
import prism.browser.SystemFileClipboard
import prism.cli.Args
import prism.cli.Command
import prism.cli.Outcome
import prism.cli.ParamType
import prism.desktop.currentDesktop
import prism.explorer.Explorer
import prism.explorer.ExplorerWindows
import prism.preferences.PreferenceFile
import prism.window.application

/**
 * `prism explore`: the file explorer, in a window on a folder, and in as many more as it is asked for, until the last
 * is closed. It is the explorer as the daemon will run it, in a process of its own: its preferences are the same file,
 * watched, so a place pinned here is pinned in every picker and explorer, running or not.
 */
object ExploreCommand : Command(
	"explore",
	"open the file explorer on a folder",
) {
	private val folder =
		argument(
			"FOLDER",
			ParamType.FOLDER,
			default = { Path(System.getProperty("user.home")) },
			shown = "your home",
		)
	private val indexing = Indexing(this)
	private val theme = themeOption()
	private val decorated = decoratedFlag("the window's menu or Ctrl+W closes it")

	override fun run(args: Args): Outcome {
		val home = Path(System.getProperty("user.home"))
		val (search, finder) = indexing.searchOf(args, home)

		explored(this, args[folder], null, home, search, finder, args[theme], args[decorated])

		return Outcome("")
	}
}

/**
 * The explorer, run in this process on [folder] ([chosen] chosen in it, where given), until its last window is closed:
 * what `prism explore` is, and what `prism portal show` falls back to where no file manager answers on the bus.
 */
internal fun explored(
	command: Command,
	folder: Path,
	chosen: String?,
	home: Path,
	search: Search,
	finder: Finder?,
	theme: Theme,
	decorated: Boolean,
) {
	val desktop = currentDesktop()

	PreferenceFile.open().use { preferences ->
		preferences.problem?.let {
			warn(command, "the preferences can't be read ($it); every default is used meanwhile")
		}

		Appearance.follow(theme).use { appearance ->
			application(APP_ID) {
				val scheme by appearance.changes.collectAsState(appearance.first)
				val scope = rememberCoroutineScope()

				val explorer =
					remember {
						Explorer(
							JvmDisk(),
							finder,
							desktop,
							SystemFileClipboard,
							preferences,
							scope,
							home,
							search,
							JvmFolderWatcher,
						).also { it.browse(folder, chosen) }
					}

				// Run on its own, the explorer is done when its last window is.
				LaunchedEffect(explorer) {
					explorer.windows.first { it.isEmpty() }
					exitApplication()
				}

				ExplorerWindows(explorer, paletteOf(theme, scheme), decorated)
			}
		}
	}
}
