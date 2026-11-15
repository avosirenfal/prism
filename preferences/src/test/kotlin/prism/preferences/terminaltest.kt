package prism.preferences

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.TerminalChoice
import prism.shared.Loaded
import prism.shared.orElse

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

		val line =
			read(
				"""{"open": ["ghostty", "--working-directory={folder}"], "run": ["ghostty", "--working-directory={folder}", "-e", "{command}"]}""",
			)

		assertTrue(
			line is TerminalChoice.CommandLines && line.opensInFolder && line.runsCommands,
		)

		val openOnly = read("""{"open": ["foot", "-D", "{folder}"]}""")

		assertTrue(openOnly is TerminalChoice.CommandLines && !openOnly.runsCommands)
		roundTrip(openOnly)
		listOf(TerminalChoice.Found, TerminalChoice.Named("ghostty"), line).forEach(::roundTrip)
	}

	@Test
	fun whatCannotBeReadIsSaidWhyAndKeptAsItWasWritten() {
		val wrong =
			listOf(
				"3",
				"\"\"",
				"""{"command": ["t"]}""",
				"""["foot", "-D", "{folder}"]""",
				"""{"open": ["foot", "{folder}", "{folder}"]}""",
				"""{"run": ["foot", "{folder}"]}""",
				"""{}""",
			).map(::read)

		wrong.forEach { assertTrue(it is TerminalChoice.Unreadable, "$it") }
		assertTrue((wrong[4] as TerminalChoice.Unreadable).why.contains("{folder}"))
		assertTrue((wrong[5] as TerminalChoice.Unreadable).why.contains("{command}"))
		assertTrue((wrong[2] as TerminalChoice.Unreadable).why.contains("“command”"))
		// Written back as it was, for the user to mend.
		wrong.forEach(::roundTrip)

		// The rest of the preferences read whatever the terminal says.
		val written =
			root.resolve("preferences.json").also {
				it.writeText("""{"terminal": {"command": []}, "bindings": {"Refresh": ["F6"]}}""")
			}
		val file = PreferenceFile.read(written).orElse(Preferences.DEFAULT)

		assertEquals(mapOf("Refresh" to listOf("F6")), file.bindings)
		assertTrue(file.terminal is TerminalChoice.Unreadable)
	}
}
