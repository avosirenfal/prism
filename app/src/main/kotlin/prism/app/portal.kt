package prism.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import prism.cli.Args
import prism.cli.Category
import prism.cli.Command
import prism.cli.Group
import prism.cli.InputError
import prism.cli.Outcome
import prism.cli.Param
import prism.cli.ParamType
import prism.client.Caller
import prism.client.Request
import prism.dbus.Connection
import prism.dbus.Opening
import prism.desktop.MimeType
import prism.desktop.freedesktop.Freedesktop
import prism.desktop.freedesktop.Xdg
import prism.portal.pathOf
import prism.portal.showInFileManager
import prism.shared.Format
import prism.shared.OnDisk
import prism.shared.Writers

/*
 * `prism portal`: prism as the desktop's file chooser and file manager (docs/portal.md). `serve` is the process that
 * does it: it owns the file chooser backend's name and `org.freedesktop.FileManager1` on the session bus, and opens a
 * window for each request, a picker for an application or the explorer on a folder, for as long as it runs. `install`
 * tells the desktop to use it (and D-Bus to start it), `uninstall` undoes that, and `show` asks the file manager to show
 * something, as the folder handler `install` makes the default does.
 */

val PortalGroup =
	Group(
		"portal",
		"act as the desktop's file chooser and file manager",
		listOf(
			Category("portal", listOf(PortalServe, PortalShow, PortalInstall, PortalUninstall)),
		),
	)

/**
 * `prism portal serve`: the file chooser and the file manager, served until the process is stopped; and, as every
 * prism that has windows is, the session's prism, which other prisms open their windows in. It is the daemon without
 * its index: what D-Bus starts where no daemon is running.
 */
object PortalServe : Command(
	"serve",
	"serve file chooser and file manager requests on the session bus until stopped",
) {
	private val indexing = Indexing(this)
	private val theme = themeOption()
	private val decorated = decoratedFlag("close a picker with Escape or Cancel")
	private val replace = replaceFlag()
	private val noManager = noManagerFlag()

	override fun run(args: Args): Outcome {
		val bus =
			when (val opening = runBlocking { Connection.session() }) {
				is Opening.Opened -> opening.connection
				is Opening.Failed -> throw InputError(
					"no session bus to serve on: ${opening.message}",
				)
			}

		val home = Path(System.getProperty("user.home"))
		val (search, finder, index) = indexing.searchOf(args, home)

		val instance =
			Instance(
				Windows(
					Hosting(args[theme], args[decorated], search, finder, index),
					Lasting.UntilStopped,
				),
				"prism portal serve (process ${ProcessHandle.current().pid()}): opens this " +
					"session's windows while it runs; it has no index (prism daemon keeps one)",
			)

		bus.use {
			stayed(
				this,
				instance,
				BusNames(chooser = true, manager = !args[noManager], replace = args[replace]),
				home,
				bus,
				needsBus = true,
			)
		}

		return Outcome("")
	}
}

/** `--replace`: a bus name another has, taken. */
internal fun Command.replaceFlag(): Param<Boolean> =
	flag(
		"--replace",
		help =
			"take over the bus names if another program (another prism, or a file manager) " +
				"holds them and allows it",
	)

/** `--no-file-manager`: the file manager's name left to another. */
internal fun Command.noManagerFlag(): Param<Boolean> =
	flag(
		"--no-file-manager",
		help = "don't serve org.freedesktop.FileManager1",
	)

/**
 * `prism portal show`: what is named shown by the file manager on the bus (a folder in a window, a file in its
 * folder), whichever program that is, starting one where D-Bus has been told how; or, where no file manager answers,
 * by prism, as `prism explore` shows it.
 */
object PortalShow : Command(
	"show",
	"show files and folders in the file manager, starting one if none is running",
) {
	private val items =
		arguments(
			"ITEM",
			ParamType("PATH|URI") { prism.cli.Parsed.Value(it) },
			help = "a path or a file:// URI (default your home folder)",
		)
	private val theme = themeOption()

	override fun run(args: Args): Outcome {
		val home = Path(System.getProperty("user.home"))

		val paths =
			args[items]
				.mapNotNull {
					if (it.startsWith("file:"))
						pathOf(it)
					else
						Path(it).toAbsolutePath().normalize()
				}.filter { Files.exists(it) }
				.ifEmpty { listOf(home) }

		val shown =
			runBlocking {
				when (val opening = Connection.session()) {
					is Opening.Opened -> opening.connection.use { it.showInFileManager(paths) }
					is Opening.Failed -> false
				}
			}

		return if (shown)
			Outcome("")
		else
			opened(
				this,
				Request(
					listOf(ExploreCommand.name, "--") + paths.map { it.toString() },
					Caller.here(),
				),
				apart = args[theme] != Theme.Auto,
			) { Hosting(args[theme]) }
	}
}

/** What runs prism, as D-Bus and desktop entries are to run it: [given], or the launcher this process was run by. */
private fun commandOf(given: Path?): List<String> =
	listOf(
		(
			given
				?: (
					System.getProperty(
						"prism.launcher",
					) ?: System.getProperty("jpackage.app-path")
				)?.let {
					Path(it)
				}
				?: throw InputError(
					"can't tell which command runs prism; pass it with --command (for an app image, " +
						"build/compose/binaries/main/app/prism/bin/prism, from ./gradlew :app:createDistributable)",
				)
		).toAbsolutePath().normalize().toString(),
	)

/** `prism portal install`: prism made the user's file chooser and file manager. */
object PortalInstall : Command(
	"install",
	"make prism your file chooser (via the desktop portal) and file manager, for your user only",
) {
	private val command =
		option(
			"--command",
			type = ParamType.PATH,
			help = "command that runs prism (default: the one that ran this)",
		)
	private val noChooser =
		flag("--no-file-chooser", help = "leave the file chooser unchanged")
	private val noManager =
		flag(
			"--no-file-manager",
			help = "leave the file manager and the default app for folders unchanged",
		)
	private val dryRun =
		flag("--dry-run", help = "print what would be written without writing anything")

	override fun run(args: Args): Outcome {
		val xdg = Xdg()
		val words = commandOf(args[command])
		val chooser = !args[noChooser]
		val manager = !args[noManager]
		val files = installedFiles(xdg, words, chooser, manager)
		val config = chooserConfigOf(xdg)
		val said = ArrayList<String>()

		// Never over a file of another's: what is there and is not prism's is said, and left.
		val others = files.keys.filter { Files.exists(it) && !isPrisms(it) }

		if (others.isNotEmpty())
			throw InputError(
				"these files already exist and were not written by prism, so nothing was changed: " +
					others.joinToString(),
			)

		for ((path, text) in files) {
			said += "wrote $path"
			if (!args[dryRun])
				put(path, text)
		}

		if (chooser) {
			// The user's own file where they have one; or a copy of the one the portal reads now, so that nothing it says
			// is lost to a portal that reads one file alone; or a new one.
			val line =
				withPrism(
					when {
						Files.exists(config) -> Files.readString(config)
						else -> portalConfigNow(xdg)?.let { "$MADE_FROM $it\n" + Files.readString(it) }
					},
				)

			said +=
				"set $CHOOSER_KEY=$BACKEND_NAME in $config" +
				(line.before?.let { " (was $it)" } ?: "")
			if (!args[dryRun])
				put(config, line.text)
		}

		if (manager) {
			// Read again: the folder handler was only just written.
			val applications = Freedesktop(xdg).applications

			val before =
				applications
					.defaultFor(
						MimeType.FOLDER,
					)?.id
					?.takeIf { it != FOLDER_ENTRY }

			said +=
				"set $FOLDER_ENTRY as the default app for folders" +
				(before?.let { " (was $it)" } ?: "")
			if (!args[dryRun]) {
				applications.all().firstOrNull { it.id == FOLDER_ENTRY }?.let {
					applications.setDefault(MimeType.FOLDER, it)
				}
				installedFile(xdg).let { file ->
					val kept = readInstalled(file)

					put(
						file,
						Json.encodeToString(
							Installed.serializer(),
							kept.copy(
								folderHandler =
									kept.folderHandler ?: before,
							),
						),
					)
				}
			}
		}

		said += ""
		said +=
			if (args[dryRun])
				"Dry run: nothing was written."
			else
				"To apply, restart the portal: systemctl --user restart xdg-desktop-portal.service " +
					"(or log in again). D-Bus starts prism portal serve on first use."

		return Outcome(said.joinToString("\n"))
	}
}

/** `prism portal uninstall`: what install wrote taken away, and what it took the place of put back. */
object PortalUninstall : Command(
	"uninstall",
	"stop using prism as your file chooser and file manager, removing what install wrote",
) {
	override fun run(args: Args): Outcome {
		val xdg = Xdg()
		val said = ArrayList<String>()

		// Wherever they are, whatever command they name: prism's by their marker.
		for (path in installedFiles(xdg, listOf("prism"), chooser = true, manager = true).keys)
			if (isPrisms(path)) {
				Files.delete(path)
				said += "removed $path"
			}

		val config = chooserConfigOf(xdg)

		if (Files.exists(config)) {
			val text = Files.readString(config)

			when (val left = withoutPrism(text)) {
				text -> Unit
				null ->
					Files.delete(config).also {
						said +=
							"removed $config (it only held prism's entry)"
					}
				else ->
					put(config, left).also {
						said +=
							"removed prism's entry from $config"
					}
			}
		}

		val file = installedFile(xdg)
		val installed = readInstalled(file)
		val applications = Freedesktop(xdg).applications

		if (applications.defaultFor(MimeType.FOLDER)?.id in setOf(null, FOLDER_ENTRY))
			installed.folderHandler
				?.let { id -> applications.all().firstOrNull { it.id == id } }
				?.let {
					applications.setDefault(MimeType.FOLDER, it)
					said += "restored ${it.id} as the default app for folders"
				}
		Files.deleteIfExists(file)

		said +=
			if (said.isEmpty())
				"Nothing to remove."
			else
				"To apply, restart the portal: systemctl --user restart xdg-desktop-portal.service"

		return Outcome(said.joinToString("\n"))
	}
}

/**
 * [file] made to say [text], whole: one of the files the portal and the desktop read, which are in their folders and
 * not prism's, so no lock of prism's is left beside them.
 */
private fun put(file: Path, text: String) {
	OnDisk(file, Format.TEXT, Writers.Anyone).update { text }
}
