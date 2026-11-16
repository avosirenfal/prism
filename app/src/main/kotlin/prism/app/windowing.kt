package prism.app

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import prism.browser.BrowserState
import prism.browser.Finder
import prism.browser.Search
import prism.browser.StaticFinder
import prism.cli.Args
import prism.cli.Command
import prism.cli.Given
import prism.cli.Param
import prism.cli.ParamType
import prism.cli.Parsed
import prism.desktop.Activation
import prism.desktop.ActivationToken
import prism.desktop.Desktop
import prism.desktop.currentDesktop
import prism.index.IndexFile
import prism.index.IndexRead
import prism.index.Usage
import prism.index.UsageFile
import prism.shared.Loaded
import prism.shared.orElse
import prism.window.activationToken

/*
 * What the commands that open windows (`prism pick`, `prism explore`) take alike: the index Ctrl+Shift+F searches, the
 * palette, and whether the window manager draws a title bar.
 */

/**
 * The desktop prism is on, for a command that opens windows: what it starts is brought forward from them, by the
 * token the windows' platform makes of the user's last press in one (prism.window's `activationToken`).
 */
internal fun windowsDesktop(): Desktop =
	currentDesktop(Activation { appId -> activationToken(appId)?.let(::ActivationToken) })

/** `--theme`: the palette, the desktop's colour scheme by default. */
internal fun Command.themeOption(): Param<Theme> =
	option(
		"--theme",
		type = Theme.TYPE,
		default = Theme.Auto,
		help = "colour theme; auto follows the desktop's setting",
		shown = "auto",
	)

/** `--decorated`: a title bar from the window manager; without it, the window is closed as [closing] says. */
internal fun Command.decoratedFlag(closing: String): Param<Boolean> =
	flag(
		"--decorated",
		help = "use the window manager's title bar; without it, $closing",
	)

/** A file that is not a folder: what `--index` reads, told apart so from the folder that may follow it. */
private val INDEX_FILE =
	ParamType("FILE") { word ->
		if (Path(word).isDirectory())
			Parsed.Invalid("$word is a folder, not an index file")
		else
			Parsed.Value(Path(word))
	}

/**
 * `--index [FILE]` of [command]: Ctrl+Shift+F searches an index read once from its file (and the usage beside it), as
 * it was read: what the daemon will hand a window it opens, kept current; for now, a copy to develop against. Where
 * there is none, or it cannot be read, the command says so on standard error and opens without.
 */
internal class Indexing(
	private val command: Command,
) {
	private val index =
		command.optionalValue(
			"--index",
			type = INDEX_FILE,
			help =
				"enable search (Ctrl+Shift+F) from an index file, read once and not kept up to date; " +
					"FILE defaults to ${defaultIndex.said}",
		)

	/** Whether [args] ask for an index at all. */
	fun given(args: Args): Boolean = args[index] != null

	/**
	 * The search [args] ask for, and what it searches: none without `--index`; with it, the index in its file, with the
	 * usage (`$XDG_STATE_HOME/prism/usage.prism`), or, where there is no index there or it cannot be read, a warning
	 * and no search.
	 */
	fun searchOf(args: Args, home: Path): Pair<Search, Finder?> {
		val file =
			when (val given = args[index]) {
				null -> return Search.Unavailable(
					"${BrowserState.NO_INDEX} Start prism ${command.name} with --index to search one.",
				) to
					null
				Given.Alone -> defaultIndexFile()
				is Given.With -> given.value
			}

		return when (val read = IndexFile.read(file)) {
			is IndexRead.Read -> {
				val usage = UsageFile.read(UsageFile.default())

				if (usage is Loaded.Unreadable)
					warn(
						command,
						"can't read the usage file (${usage.why}); results are ranked without it",
					)

				Search.Ready() to
					StaticFinder(read.index, usage.orElse(Usage.NONE), home) {
						exclusionsNow().hiding
					}
			}
			IndexRead.Missing -> {
				warn(
					command,
					"no index at $file, so search is unavailable; run prism index to create one",
				)
				Search.Unavailable("No index at $file. Run prism index to create one.") to null
			}
			is IndexRead.Unreadable -> {
				warn(command, "can't read the index at $file (${read.why}), so search is unavailable")
				Search.Unavailable("Can't read the index at $file: ${read.why}") to null
			}
		}
	}
}

/** [line] on standard error, as [command]'s warning. */
internal fun warn(command: Command, line: String) =
	System.err.println("prism ${command.name}: warning: $line")
