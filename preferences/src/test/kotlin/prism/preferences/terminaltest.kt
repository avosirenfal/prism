package prism.preferences

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.TerminalChoice

/** The terminal chosen, as the preferences file writes it: each form read, written back the same, and what is wrong kept. */
class TerminalChoiceTest {
	@TempDir
	lateinit var root: Path

	private fun read(json: String): TerminalChoice =
		TerminalChoiceSerializer.choiceOf(Json.parseToJsonElement(json))

	private fun roundTrip(choice: TerminalChoice) =
		assertEquals(
			choice,
			TerminalChoiceSerializer.choiceOf(TerminalChoiceSerializer.jsonOf(choice)),
		)

	@Test
	fun eachFormIsReadAsWhatItSaysAndWrittenBackSo() {
		assertEquals(TerminalChoice.Found, read("null"))
		assertEquals(TerminalChoice.Named("ghostty"), read("\"ghostty\""))
		// A name alone, written as a list of one, as it was before.
		assertEquals(TerminalChoice.Named("kitty"), read("""["kitty"]"""))

		val line =
			read(
				"""["ghostty", "--font-size=14", "--working-directory={folder}", "-e", "{command}"]""",
			)

		assertTrue(line is TerminalChoice.Written && line.opensInFolder && line.runsCommands)
		listOf(TerminalChoice.Found, TerminalChoice.Named("ghostty"), line).forEach(::roundTrip)
	}

	@Test
	fun whatCannotBeReadIsSaidWhyAndKeptAsItWasWritten() {
		val wrong =
			listOf(
				"3",
				"\"\"",
				"""{"command": ["t"]}""",
				"""["foot", "--hold"]""",
				"""["foot", "{folder}", "{folder}"]""",
				"""["t", 3]""",
			).map(::read)

		wrong.forEach { assertTrue(it is TerminalChoice.Unreadable, "$it") }
		assertTrue((wrong[3] as TerminalChoice.Unreadable).why.contains("{folder}"))
		// Written back as it was, for the user to mend.
		wrong.forEach(::roundTrip)

		// The rest of the preferences read whatever the terminal says.
		val written =
			root.resolve("preferences.json").also {
				it.writeText("""{"terminal": {"command": []}, "bindings": {"Refresh": ["F6"]}}""")
			}
		val file = PreferenceFile.read(written).preferences

		assertEquals(mapOf("Refresh" to listOf("F6")), file.bindings)
		assertTrue(file.terminal is TerminalChoice.Unreadable)
	}
}
