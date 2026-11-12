package prism.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import prism.browser.JvmDisk
import prism.browser.JvmFolderWatcher
import prism.browser.Request
import prism.browser.SystemFileClipboard
import prism.cli.Args
import prism.cli.Category
import prism.cli.Command
import prism.cli.Group
import prism.cli.InputError
import prism.cli.Outcome
import prism.cli.ParamType
import prism.dbus.Connection
import prism.dbus.Opening
import prism.desktop.MimeType
import prism.desktop.currentDesktop
import prism.desktop.freedesktop.Freedesktop
import prism.desktop.freedesktop.Xdg
import prism.explorer.Explorer
import prism.explorer.ExplorerWindows
import prism.portal.FileChooser
import prism.portal.FileManager
import prism.portal.Owning
import prism.portal.Showing
import prism.portal.own
import prism.portal.pathOf
import prism.portal.serveFileChooser
import prism.portal.serveFileManager
import prism.portal.showInFileManager
import prism.preferences.PreferenceFile
import prism.window.application

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
		"be the desktop's file chooser and file manager",
		listOf(
			Category("portal", listOf(PortalServe, PortalShow, PortalInstall, PortalUninstall)),
		),
	)

/** `prism portal serve`: the file chooser and the file manager, served until the process is stopped. */
object PortalServe : Command(
	"serve",
	"serve the file chooser and the file manager on the session bus, a window for each request, until stopped",
) {
	private val indexing = Indexing(this)
	private val theme = themeOption()
	private val decorated = decoratedFlag("Escape or Cancel closes a picker")
	private val replace =
		flag(
			"--replace",
			help =
				"take the names from whatever has them (another prism, or another file manager), " +
					"where it lets them go",
		)
	private val noManager =
		flag(
			"--no-file-manager",
			help = "serve the file chooser alone, and leave org.freedesktop.FileManager1 to another",
		)

	override fun run(args: Args): Outcome {
		val bus =
			when (val opening = runBlocking { Connection.session() }) {
				is Opening.Opened -> opening.connection
				is Opening.Failed -> throw InputError(
					"there is no session bus to serve on: ${opening.message}",
				)
			}

		val home = Path(System.getProperty("user.home"))
		val (search, finder) = indexing.searchOf(args, home)
		val desktop = currentDesktop()

		bus.use {
			PreferenceFile.open().use { preferences ->
				Appearance.follow(args[theme]).use { appearance ->
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
								)
							}

						LaunchedEffect(bus) {
							// Served before the names are asked for: D-Bus hands a call that started this process to it the
							// moment it has the name, and a call before its method is served would be answered that there
							// is no such method.
							val chooser =
								bus
									.serveFileChooser(scope) { request ->
										val asking = askingOf(request, desktop.types, home)

										pickedOf(request, asking, explorer.choose(asking), desktop.types)
									}.takeIf { serving ->
										owned(bus.own(FileChooser.BACKEND, args[replace]), FileChooser.BACKEND)
											.also {
												if (!it)
													serving.close()
											}
									}

							val manager =
								bus
									.serveFileManager { showing -> scope.launch { shown(explorer, showing) } }
									.takeIf { serving ->
										(
											!args[noManager] &&
												owned(bus.own(FileManager.NAME, args[replace]), FileManager.NAME)
										).also {
											if (!it)
												serving.close()
										}
									}

							if (chooser == null && manager == null) {
								warn(this@PortalServe, "nothing to serve")
								exitApplication()
							}

							try {
								awaitCancellation()
							} finally {
								chooser?.close()
								manager?.close()
							}
						}

						ExplorerWindows(explorer, paletteOf(args[theme], scheme), args[decorated])
					}
				}
			}
		}

		return Outcome("")
	}

	/** Whether [owning] says this has [name]; where it does not, why, on standard error. */
	private fun owned(owning: Owning, name: String): Boolean =
		when (owning) {
			Owning.Owned -> true
			Owning.Taken ->
				false.also {
					warn(this, "another has $name; --replace takes it, where it lets it go")
				}
			Owning.Failed -> false.also { warn(this, "the bus would not give $name") }
		}

	/** What the file manager is asked to show, shown: folders in windows, items chosen in theirs, or properties. */
	private fun shown(explorer: Explorer, showing: Showing) {
		when (showing) {
			is Showing.Folders -> showing.paths.forEach { explorer.browse(it) }
			// Each folder once, with the first of its items asked for chosen in it.
			is Showing.Items ->
				showing.paths
					.groupBy { it.parent ?: it }
					.forEach { (folder, items) ->
						explorer.browse(folder, items.first().fileName?.toString())
					}
			is Showing.Properties -> explorer.ask(Request.Properties(showing.paths))
		}
	}
}

/**
 * `prism portal show`: what is named shown by the file manager on the bus (a folder in a window, a file in its
 * folder), starting `prism portal serve` where D-Bus has been told to; or, where no file manager answers, by an explorer
 * in this process.
 */
object PortalShow : Command(
	"show",
	"show folders and files in the file manager: the running one, or one started for them",
) {
	private val items =
		arguments(
			"ITEM",
			ParamType("PATH|URI") { prism.cli.Parsed.Value(it) },
			help = "a path or a file:// URI; your home where none is named",
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

		if (!shown) {
			val first = paths.first()
			val folder =
				if (Files.isDirectory(first))
					first
				else
					first.parent ?: home

			explored(
				this,
				folder,
				first.takeIf { it != folder }?.fileName?.toString(),
				home,
				prism.browser.Search.Unavailable(prism.browser.BrowserState.NO_INDEX),
				null,
				args[theme],
				decorated = false,
			)
		}

		return Outcome("")
	}
}

/** What runs prism, as D-Bus and desktop entries are to run it: [given], or the launcher this process was run by. */
private fun commandOf(given: Path?): List<String> =
	listOf(
		(
			given
				?: System.getProperty("jpackage.app-path")?.let { Path(it) }
				?: throw InputError(
					"say which command runs prism, with --command: the launcher of an app image " +
						"(./gradlew :app:createDistributable makes one, as build/compose/binaries/main/app/prism/bin/prism)",
				)
		).toAbsolutePath().normalize().toString(),
	)

/** `prism portal install`: prism made the user's file chooser and file manager. */
object PortalInstall : Command(
	"install",
	"make prism your file chooser (through the desktop portal) and your file manager, for you alone",
) {
	private val command =
		option(
			"--command",
			type = ParamType.PATH,
			help = "the command that runs prism (the launcher this was run by, unless said)",
		)
	private val noChooser =
		flag("--no-file-chooser", help = "leave the file chooser as it is")
	private val noManager =
		flag(
			"--no-file-manager",
			help = "leave the file manager, and what opens folders, as they are",
		)
	private val dryRun =
		flag("--dry-run", help = "say what would be written, and write nothing")

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
				"these are there, and are not prism's, so nothing was written: ${others.joinToString()}",
			)

		for ((path, text) in files) {
			said += "wrote $path"
			if (!args[dryRun])
				Files.createDirectories(path.parent).also { Files.writeString(path, text) }
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
				(line.before?.let { " (it was $it)" } ?: "")
			if (!args[dryRun])
				Files.createDirectories(config.parent).also { Files.writeString(config, line.text) }
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
				"made $FOLDER_ENTRY what opens folders" + (before?.let { " (it was $it)" } ?: "")
			if (!args[dryRun]) {
				applications.all().firstOrNull { it.id == FOLDER_ENTRY }?.let {
					applications.setDefault(MimeType.FOLDER, it)
				}
				installedFile(xdg).let { file ->
					val kept = readInstalled(file)

					Files.createDirectories(file.parent)
					Files.writeString(
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
				"Nothing was written: that is what prism portal install would write."
			else
				"The portal reads its backends as it starts: systemctl --user restart xdg-desktop-portal.service " +
					"(or log in again). D-Bus starts prism portal serve when it is first called."

		return Outcome(said.joinToString("\n"))
	}
}

/** `prism portal uninstall`: what install wrote taken away, and what it took the place of put back. */
object PortalUninstall : Command(
	"uninstall",
	"stop prism being your file chooser and file manager: what install wrote is taken away",
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
							"removed $config, which held nothing but prism's line"
					}
				else ->
					Files.writeString(config, left).also {
						said +=
							"took prism's line out of $config"
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
					said += "made ${it.id} what opens folders again"
				}
		Files.deleteIfExists(file)

		said +=
			if (said.isEmpty())
				"There was nothing of prism's to take away."
			else
				"systemctl --user restart xdg-desktop-portal.service, for the portal to forget the backend."

		return Outcome(said.joinToString("\n"))
	}
}
