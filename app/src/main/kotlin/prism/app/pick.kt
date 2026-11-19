package prism.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.writeBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import prism.browser.BrowserEvent
import prism.browser.BrowserState
import prism.browser.BrowserTheme
import prism.browser.Choosing
import prism.browser.Finder
import prism.browser.FolderWatcher
import prism.browser.HostedBrowser
import prism.browser.JvmDisk
import prism.browser.Search
import prism.browser.Services
import prism.browser.Session
import prism.browser.StaticFinder
import prism.browser.Step
import prism.browser.SystemFileClipboard
import prism.browser.opened
import prism.browser.pickerOf
import prism.browser.renderedPng
import prism.browser.sidebarOf
import prism.browser.zoomOf
import prism.cli.Args
import prism.cli.Command
import prism.cli.Given
import prism.cli.InputError
import prism.cli.Outcome
import prism.cli.ParamType
import prism.cli.Parsed
import prism.cli.Size
import prism.index.IndexFile
import prism.index.IndexRead
import prism.index.UsageFile
import prism.preferences.Role
import prism.preferences.preferences
import prism.window.LocalScrollDirection
import prism.window.Window
import prism.window.application
import prism.browser.Outcome as Ended

/*
 * The picker from the command line: in a window (`prism pick`), or drawn into a picture (`prism snapshot`), each
 * over a real folder read through the JVM. `prism pick` is the picker as an application will get it, with the
 * answer on standard output as zenity gives it: each path chosen on a line of its own, or nothing and exit status 1
 * for a picker cancelled.
 */

/** The parameters both commands take: the folder, what the picker is choosing, and the theme. */
private class Picking(
	command: Command,
) {
	private val folder =
		command.argument(
			"FOLDER",
			ParamType.FOLDER,
			default = { Path(System.getProperty("user.home")) },
			shown = "your home folder",
		)
	private val save =
		command.option(
			"--save",
			type = ParamType.TEXT.named("NAME"),
			help = "pick where to save a file, suggesting NAME",
		)
	private val many = command.flag("--multiple", help = "allow selecting several files")
	private val folders = command.flag("--folder", help = "pick a folder")
	private val theme = command.themeOption()

	fun choosing(args: Args): Choosing {
		val asked =
			listOfNotNull(
				"--save".takeIf { args[save] != null },
				"--multiple".takeIf { args[many] },
				"--folder".takeIf { args[folders] },
			)

		if (asked.size > 1)
			throw InputError(
				"${asked.joinToString(" and ")} can't be used together",
			)

		return when {
			args[save] != null -> Choosing.SaveAs(args[save]!!)
			args[many] -> Choosing.Files()
			args[folders] -> Choosing.Folder()
			else -> Choosing.OneFile()
		}
	}

	fun folder(args: Args): Path = args[folder]

	/** The picker over its folder, read: what a snapshot draws. */
	fun state(args: Args): BrowserState = pickerOf(folder(args), choosing(args))

	fun theme(args: Args): Theme = args[theme]
}

/**
 * `prism pick`: the picker in a window. With `--index`, Ctrl+Shift+F searches an index read once from its file (and
 * the usage beside it), as it was read: what the daemon will hand a picker it opens, kept current, for now a copy to
 * develop against. Where there is none, or it cannot be read, the picker says so on standard error and opens without.
 */
object PickCommand : Command(
	"pick",
	"open the file picker on a folder and print the selected paths",
) {
	private val picking = Picking(this)

	private val indexing = Indexing(this)
	private val decorated = decoratedFlag("close it with Escape or Cancel")

	override fun run(args: Args): Outcome {
		val home = Path(System.getProperty("user.home"))
		val (search, finder) = indexing.searchOf(args, home)
		val desktop = windowsDesktop()
		val preferences = preferencesHere

		preferences.problem?.let {
			warn(this, "can't read the preferences ($it); using the defaults")
		}

		val first =
			opened(
				picking.choosing(args),
				picking.folder(args),
				home,
				sidebarOf(desktop.places, home, preferences.preferences.value),
				search = search,
				listings = preferences.preferences.value.listings,
				zoom = zoomOf(preferences.preferences.value, Role.Picker),
			)

		val services =
			Services(
				JvmDisk(),
				finder,
				desktop,
				SystemFileClipboard,
				preferences,
				watcher = FolderWatcher.SYSTEM,
				recents = recentFolders(),
			)

		return when (
			val outcome =
				keepingStdout {
					picked(
						first,
						services,
						picking.theme(args),
						args[decorated] || preferences.preferences.value.titlebar,
					)
				}
		) {
			is Ended.Chosen -> Outcome(outcome.paths.joinToString("\n"))
			Ended.Cancelled -> Outcome("", status = 1)
		}
	}

	/**
	 * The picker [first] opens in a window, run until it ends. The desktop's colour scheme is asked for before the
	 * window opens, so it opens in the right palette, and followed while it is open, so it changes palette when the
	 * user changes their preference. The application returns when the picker ends, rather than ending the process,
	 * so that what was chosen can be printed after it; a window closed is a picker cancelled.
	 */
	@OptIn(ExperimentalCoroutinesApi::class)
	private fun picked(
		first: Step,
		services: Services,
		theme: Theme,
		decorated: Boolean,
	): Ended {
		val ended = CompletableDeferred<Ended>()

		Appearance.follow(theme).use { appearance ->
			application(APP_ID) {
				val scheme by appearance.changes.collectAsState(appearance.first)
				val scope = rememberCoroutineScope()
				val session = remember { Session(first, services, scope) }
				val state by session.state.collectAsState()

				LaunchedEffect(session) {
					ended.complete(session.outcome.await())
					exitApplication()
				}

				val palette = paletteOf(theme, scheme)
				val preferences by services.preferences.preferences.collectAsState()

				CompositionLocalProvider(LocalScrollDirection provides preferences.scrolling) {
					Window(
						onCloseRequest = { session.send(BrowserEvent.Cancelled) },
						title = state.title,
						size = DpSize(1100.dp, 680.dp),
						decorated = decorated,
					) {
						HostedBrowser(window, session, services.preferences, palette)
					}
				}
			}
		}

		return if (ended.isCompleted)
			ended.getCompleted()
		else
			Ended.Cancelled
	}
}

/**
 * [run], with standard output kept for what a command prints of what was chosen: what the toolkit prints meanwhile
 * (skiko says so on standard output when it falls back from OpenGL) goes to standard error instead.
 */
internal fun <T> keepingStdout(run: () -> T): T {
	val stdout = System.out

	System.setOut(System.err)

	return try {
		run()
	} finally {
		System.setOut(stdout)
	}
}

/** `prism snapshot`: the picker drawn headless into a PNG. */
object SnapshotCommand : Command(
	"snapshot",
	"render the file picker to a PNG, without a display",
) {
	private val picking = Picking(this)
	private val out =
		required("--out", type = ParamType.PATH.named("FILE"), help = "PNG file to write")
	private val size =
		option(
			"--size",
			type = Size.TYPE,
			default = Size(1100, 680),
			help = "window size",
			shown = "1100x680",
		)
	private val scale =
		option("--scale", type = ParamType.COUNT, default = 2, help = "display scale factor")

	override fun run(args: Args): Outcome {
		val theme = picking.theme(args)
		val scheme = Appearance.follow(theme).use { it.first }

		val png =
			renderedPng(
				picking.state(args),
				args[size].width,
				args[size].height,
				paletteOf(theme, scheme),
				args[scale].toFloat(),
			)

		val into: Path = args[out]

		into.writeBytes(png)

		return Outcome("${into.toAbsolutePath()}: ${png.size} bytes")
	}
}
