package prism.launcher

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import prism.desktop.Application
import prism.desktop.ApplicationAction
import prism.desktop.Applications
import prism.desktop.Desktop
import prism.desktop.FileTypes
import prism.desktop.JvmFacts
import prism.desktop.Launched
import prism.desktop.Launcher
import prism.desktop.MimeType
import prism.desktop.Place
import prism.desktop.Places
import prism.desktop.TerminalChoice

/** The applications of the tests' desktop. */
object Programs {
	fun application(
		id: String,
		name: String,
		generic: String? = null,
		comment: String? = null,
		keywords: List<String> = emptyList(),
		actions: List<ApplicationAction> = emptyList(),
		command: String = id,
		icon: String? = null,
	) = Application(
		"$id.desktop",
		name,
		emptySet(),
		listOf(command),
		inTerminal = false,
		icon = icon,
		source = Path.of("/usr/share/applications/$id.desktop"),
		generic = generic,
		comment = comment,
		keywords = keywords,
		actions = actions,
	)

	val firefox =
		application(
			"firefox",
			"Firefox",
			"Web Browser",
			"Browse the World Wide Web",
			listOf("internet", "www", "browser"),
			listOf(
				ApplicationAction("new-window", "New Window", listOf("firefox", "--new-window")),
				ApplicationAction(
					"new-private-window",
					"New Private Window",
					listOf("firefox", "--private-window"),
				),
			),
		)
	val files =
		application("thunar", "Thunar File Manager", "File Manager", "Browse the filesystem")
	val code =
		application(
			"code",
			"Visual Studio Code",
			"Text Editor",
			"Code editing. Redefined.",
			listOf("vscode"),
		)
	val editor =
		application(
			"org.gnome.TextEditor",
			"Text Editor",
			"Text Editor",
			"Edit text files",
			command = "gnome-text-editor",
		)
	val terminal =
		application("foot", "Foot", "Terminal", "A wayland native terminal emulator")
	val fire =
		application("firewall", "Firewall Configuration", comment = "Configure the firewall")

	val all = listOf(firefox, files, code, editor, terminal, fire)
}

/** A desktop of the tests': [installed] applications, every file text that the editor opens, and what was started kept. */
class FakeDesktop(
	private val home: Path,
	var installed: List<Application> = Programs.all,
) : Desktop {
	/** What was started, and with what, in order. */
	val started = CopyOnWriteArrayList<Pair<Application, List<Path>>>()

	/** Why the next start fails, where one should. */
	@Volatile
	var failing: String? = null

	override val types =
		object : FileTypes {
			override fun of(path: Path) = MimeType.TEXT

			override fun describe(type: MimeType) = "Text"

			override fun lineage(type: MimeType) = listOf(type)
		}
	override val applications =
		object : Applications {
			override fun all() = installed.sortedBy { it.name.lowercase() }

			override fun forType(type: MimeType) = listOf(Programs.editor)

			override fun defaultFor(type: MimeType) = Programs.editor

			override fun setDefault(type: MimeType, application: Application) = Unit
		}
	override val launcher =
		object : Launcher {
			override fun launch(
				application: Application,
				paths: List<Path>,
				terminal: TerminalChoice,
			): Launched =
				failing?.let { Launched.Failed(application.name, it) }
					?: Launched.Started(application.name).also { started += application to paths }

			override fun run(command: List<String>, folder: Path) =
				Launched.Started(command.first())
		}
	override val places =
		object : Places {
			override fun known(home: Path) = listOf(Place("Home", Place.Kind.Home, home))

			override fun drives(home: Path) = emptyList<Place>()
		}
	override val facts = JvmFacts(types)
}

/** A host that keeps what it was asked. */
class FakeHost : LauncherHost {
	val asked = CopyOnWriteArrayList<String>()

	override fun close() {
		asked += "close"
	}

	override fun settings() {
		asked += "settings"
	}

	override fun browse(folder: Path) {
		asked += "browse $folder"
	}

	override fun reveal(path: Path) {
		asked += "reveal $path"
	}

	override fun copy(text: String) {
		asked += "copy $text"
	}
}
