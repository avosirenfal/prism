package prism.explorer

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import prism.desktop.Application
import prism.desktop.Applications
import prism.desktop.FileTypes
import prism.desktop.MimeType

/** The applications offered to open files with: those for every type first, then the rest, narrowed as typed. */
class OpenWithTest {
	private val png = MimeType("image/png")
	private val jpeg = MimeType("image/jpeg")

	private fun application(name: String, types: Set<MimeType>) =
		Application("$name.desktop", name, types, listOf(name.lowercase(), "%F"), false)

	private val viewer = application("Viewer", setOf(png, jpeg))
	private val painter = application("Painter", setOf(png))
	private val editor = application("Editor", setOf(MimeType.TEXT))

	private val applications =
		object : Applications {
			override fun all() = listOf(editor, painter, viewer)

			override fun forType(type: MimeType) =
				all().filter { type in it.types }.sortedBy {
					it !=
						viewer
				}

			override fun defaultFor(type: MimeType) = forType(type).firstOrNull()

			override fun setDefault(type: MimeType, application: Application) = Unit
		}

	private val types =
		object : FileTypes {
			override fun of(path: Path) =
				if (path.toString().endsWith(".png"))
					png
				else
					jpeg

			override fun describe(type: MimeType) = null

			override fun lineage(type: MimeType) = listOf(type)
		}

	@Test
	fun whatOpensEveryTypeIsRecommendedAndTheRestFollow() {
		val one = chooserOf(Choice.Open(listOf(Path.of("a.png")), png), applications, types)

		assertEquals(listOf(viewer, painter), one.recommended)
		assertEquals(listOf(editor), one.others)
		assertEquals(viewer, one.chosen)
		assertEquals(png, one.defaultable)

		// Of a PNG and a JPEG, only what opens both.
		val two =
			chooserOf(
				Choice.Open(listOf(Path.of("a.png"), Path.of("b.jpg")), null),
				applications,
				types,
			)

		assertEquals(listOf(viewer), two.recommended)
		assertEquals(listOf(editor, painter), two.others)
		assertNull(two.defaultable)
		// Choosing a default is for one type, and makes it the default anyway.
		assertNull(chooserOf(Choice.Default(png), applications, types).defaultable)
	}

	@Test
	fun typingNarrowsWhatIsShownAndTheArrowsMoveThroughIt() {
		val chooser = chooserOf(Choice.Default(png), applications, types)

		assertEquals(
			listOf("Recommended", "Other applications"),
			chooser.sections.map {
				it.heading
			},
		)
		assertEquals(painter, chooser.moved(1).chosen)
		assertEquals(editor, chooser.moved(5).chosen)
		assertEquals(viewer, chooser.moved(-1).chosen)

		val narrowed = chooser.filtered("edit")

		assertEquals(listOf("Other applications"), narrowed.sections.map { it.heading })
		assertEquals(editor, narrowed.chosen)
		assertNull(chooser.filtered("nothing like it").chosen)
		// What is chosen stays chosen while it is shown.
		assertEquals(painter, chooser.moved(1).filtered("p").chosen)
	}
}
