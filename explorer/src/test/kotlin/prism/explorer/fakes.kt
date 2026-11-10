package prism.explorer

import java.nio.file.Path
import prism.desktop.Application
import prism.desktop.Applications
import prism.desktop.Desktop
import prism.desktop.FileTypes
import prism.desktop.JvmFacts
import prism.desktop.Launched
import prism.desktop.Launcher
import prism.desktop.MimeType
import prism.desktop.Place
import prism.desktop.Places

/** A desktop of the tests': everything is text, an editor opens it, and only [home] is pinned until the user says. */
class FakeDesktop(
	private val home: Path,
) : Desktop {
	val editor =
		Application(
			"editor.desktop",
			"Editor",
			setOf(MimeType.TEXT),
			listOf("editor", "%F"),
			false,
		)

	/** The defaults set, in order. */
	val defaultsSet = ArrayList<Pair<MimeType, Application>>()

	override val types =
		object : FileTypes {
			override fun of(path: Path) = MimeType.TEXT

			override fun describe(type: MimeType) = "Text"

			override fun lineage(type: MimeType) = listOf(type)
		}
	override val applications =
		object : Applications {
			override fun all() = listOf(editor)

			override fun forType(type: MimeType) = listOf(editor)

			override fun defaultFor(type: MimeType) = editor

			override fun setDefault(type: MimeType, application: Application) {
				defaultsSet += type to application
			}
		}
	override val launcher =
		object : Launcher {
			override fun launch(application: Application, paths: List<Path>) =
				Launched.Started(application.name)

			override fun run(command: List<String>, folder: Path) =
				Launched.Started(command.first())

			override fun terminal(folder: Path, preferred: List<String>?) =
				Launched.Started("terminal")
		}
	override val places =
		object : Places {
			override fun known(home: Path) = listOf(Place("Home", Place.Kind.Home, home))

			override fun drives(home: Path) = emptyList<Place>()
		}
	override val facts = JvmFacts(types)
}
