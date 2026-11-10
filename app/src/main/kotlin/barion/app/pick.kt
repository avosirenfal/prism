package barion.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import barion.cli.Args
import barion.cli.Command
import barion.cli.Given
import barion.cli.InputError
import barion.cli.Outcome
import barion.cli.ParamType
import barion.cli.Parsed
import barion.cli.Size
import barion.index.IndexFile
import barion.index.IndexRead
import barion.index.UsageFile
import barion.index.UsageRead
import barion.picker.Choosing
import barion.picker.Finder
import barion.picker.JvmDisk
import barion.picker.KeepsKeyboard
import barion.picker.PickerEvent
import barion.picker.PickerState
import barion.picker.PickerTheme
import barion.picker.Search
import barion.picker.Session
import barion.picker.SessionPicker
import barion.picker.SideButtons
import barion.picker.StaticFinder
import barion.picker.Step
import barion.picker.TitleBarTone
import barion.picker.opened
import barion.picker.pickerOf
import barion.picker.renderedPng
import barion.picker.sidebarOf
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.writeBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import barion.picker.Outcome as Ended

/*
 * The picker from the command line: in a window (`barion pick`), or drawn into a picture (`barion snapshot`), each
 * over a real folder read through the JVM. `barion pick` is the picker as an application will get it, with the
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
			shown = "your home",
		)
	private val save =
		command.option(
			"--save",
			type = ParamType.TEXT.named("NAME"),
			help = "a picker that saves, under NAME",
		)
	private val many = command.flag("--multiple", help = "a picker that opens several files")
	private val folders = command.flag("--folder", help = "a picker that chooses a folder")
	private val theme =
		command.option(
			"--theme",
			type = Theme.TYPE,
			default = Theme.Auto,
			help = "the palette: the desktop's colour scheme (light where there is none), or light or dark",
			shown = "auto",
		)

	fun choosing(args: Args): Choosing {
		val asked =
			listOfNotNull(
				"--save".takeIf { args[save] != null },
				"--multiple".takeIf { args[many] },
				"--folder".takeIf { args[folders] },
			)

		if (asked.size > 1)
			throw InputError(
				"${asked.joinToString(" and ")} ask for different pickers; ask for one",
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
	fun state(args: Args): PickerState = pickerOf(folder(args), choosing(args))

	fun theme(args: Args): Theme = args[theme]
}

/** A file that is not a folder: what `--index` reads, told apart so from the folder that may follow it. */
private val INDEX_FILE =
	ParamType("FILE") { word ->
		if (Path(word).isDirectory())
			Parsed.Invalid("$word is a folder, not an index")
		else
			Parsed.Value(Path(word))
	}

/**
 * `barion pick`: the picker in a window. With `--index`, Ctrl+Shift+F searches an index read once from its file (and
 * the usage beside it), as it was read: what the daemon will hand a picker it opens, kept current, for now a copy to
 * develop against. Where there is none, or it cannot be read, the picker says so on standard error and opens without.
 */
object PickCommand : Command(
	"pick",
	"open the picker in a window over a folder, and print what is chosen",
) {
	private val picking = Picking(this)

	private val index =
		optionalValue(
			"--index",
			type = INDEX_FILE,
			help =
				"search an index with Ctrl+Shift+F: the one in FILE, or without FILE the usual one " +
					"(\$XDG_CACHE_HOME/barion/index.barion); read once, and never updated",
		)

	private val decorated =
		flag(
			"--decorated",
			help =
				"give the window the title bar the window manager draws (in the picker's palette " +
					"where it heeds _GTK_THEME_VARIANT); without it, the window has none, " +
					"and Escape or Cancel closes it",
		)

	override fun run(args: Args): Outcome {
		val home = Path(System.getProperty("user.home"))
		val (search, finder) = searchOf(args[index], home)

		val first =
			opened(
				picking.choosing(args),
				picking.folder(args),
				home,
				sidebarOf(home),
				search = search,
			)

		return when (
			val outcome =
				keepingStdout {
					picked(
						first,
						finder,
						picking.theme(args),
						args[decorated],
					)
				}
		) {
			is Ended.Chosen -> Outcome(outcome.paths.joinToString("\n"))
			Ended.Cancelled -> Outcome("", status = 1)
		}
	}

	/**
	 * The search [given] asks for, and what it searches: none without `--index`; with it, the index in its file, with
	 * the usage (`$XDG_STATE_HOME/barion/usage.barion`), or, where there is no index there or it cannot be read, a warning and no search.
	 */
	private fun searchOf(given: Given<Path>?, home: Path): Pair<Search, Finder?> {
		val file =
			when (given) {
				null -> return Search.Unavailable(
					"${PickerState.NO_INDEX} barion pick --index searches one.",
				) to
					null
				Given.Alone -> defaultIndexFile()
				is Given.With -> given.value
			}

		return when (val read = IndexFile.read(file)) {
			is IndexRead.Read -> {
				val usage = UsageFile.read(UsageFile.default())

				if (usage is UsageRead.Unreadable)
					warn(
						"the usage can't be read (${usage.why}); results are ranked without it",
					)

				Search.Ready() to StaticFinder(read.index, usage.usage, home)
			}
			IndexRead.Missing -> {
				warn("there is no index at $file, so there is no search; barion index makes one")
				Search.Unavailable("There is no index at $file. barion index makes one.") to null
			}
			is IndexRead.Unreadable -> {
				warn("the index at $file can't be read (${read.why}), so there is no search")
				Search.Unavailable("The index at $file can't be read: ${read.why}") to null
			}
		}
	}

	private fun warn(line: String) = System.err.println("barion pick: warning: $line")

	/**
	 * The picker [first] opens in a window, run until it ends. The desktop's colour scheme is asked for before the
	 * window opens, so it opens in the right palette, and followed while it is open, so it changes palette when the
	 * user changes their preference. The application returns when the picker ends, rather than ending the process,
	 * so that what was chosen can be printed after it; a window closed is a picker cancelled.
	 */
	@OptIn(ExperimentalCoroutinesApi::class)
	private fun picked(
		first: Step,
		finder: Finder?,
		theme: Theme,
		decorated: Boolean,
	): Ended {
		val ended = CompletableDeferred<Ended>()

		Appearance.follow(theme).use { appearance ->
			application(exitProcessOnExit = false) {
				val scheme by appearance.changes.collectAsState(appearance.first)
				val scope = rememberCoroutineScope()
				val session = remember { Session(first, JvmDisk(), scope, finder = finder) }
				val state by session.state.collectAsState()

				LaunchedEffect(session) {
					ended.complete(session.outcome.await())
					exitApplication()
				}

				val palette = paletteOf(theme, scheme)

				Window(
					onCloseRequest = { session.send(PickerEvent.Cancelled) },
					title = state.title,
					state = rememberWindowState(width = 1100.dp, height = 680.dp),
					undecorated = !decorated,
				) {
					SideButtons(window, session::send)
					KeepsKeyboard(window)
					TitleBarTone(window, palette.isDark)
					PickerTheme(palette) { SessionPicker(session) }
				}
			}
		}

		return if (ended.isCompleted)
			ended.getCompleted()
		else
			Ended.Cancelled
	}

	/**
	 * [run], with standard output kept for the paths chosen: what the toolkit prints meanwhile (skiko says so on
	 * standard output when it falls back from OpenGL) goes to standard error instead.
	 */
	private fun <T> keepingStdout(run: () -> T): T {
		val stdout = System.out

		System.setOut(System.err)

		return try {
			run()
		} finally {
			System.setOut(stdout)
		}
	}
}

/** `barion snapshot`: the picker drawn headless into a PNG. */
object SnapshotCommand : Command(
	"snapshot",
	"draw the picker over a folder into a PNG, with no display",
) {
	private val picking = Picking(this)
	private val out =
		required("--out", type = ParamType.PATH.named("FILE"), help = "where the PNG goes")
	private val size =
		option(
			"--size",
			type = Size.TYPE,
			default = Size(1100, 680),
			help = "the window's size",
			shown = "1100x680",
		)
	private val scale =
		option("--scale", type = ParamType.COUNT, default = 2, help = "the display's density")

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
