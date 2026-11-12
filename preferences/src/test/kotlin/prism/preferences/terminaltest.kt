package prism.preferences

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.ExecArg
import prism.desktop.FolderArgs
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
	fun eachFormIsReadAsWhatItSays() {
		assertEquals(TerminalChoice.Found, read("null"))
		assertEquals(
			TerminalChoice.Described(listOf("ghostty", "--font-size=14")),
			read("""["ghostty", "--font-size=14"]"""),
		)
		assertEquals(
			TerminalChoice.Written(listOf("foot", "-D", "{folder}", "{command}")),
			read("""["foot", "-D", "{folder}", "{command}"]"""),
		)
		assertEquals(
			TerminalChoice.Described(
				listOf("my-term", "--login"),
				FolderArgs.separate("--start-in"),
				ExecArg.Before(listOf("-x")),
				listOf("--new-process"),
			),
			read(
				"""{"command": ["my-term", "--login"], "folder": ["--start-in", "{folder}"], "run": ["-x"], "alone": ["--new-process"]}""",
			),
		)
		// Said: no folder words at all, nothing before a command, and a terminal that cannot be given one.
		assertEquals(
			TerminalChoice.Described(listOf("t"), FolderArgs.None, ExecArg.Before(emptyList())),
			read("""{"command": ["t"], "folder": [], "run": []}"""),
		)
		assertEquals(
			TerminalChoice.Described(listOf("t"), exec = ExecArg.Unsupported),
			read("""{"command": ["t"], "run": null}"""),
		)
	}

	@Test
	fun eachIsWrittenBackAsItReads() {
		listOf(
			TerminalChoice.Found,
			TerminalChoice.Described(listOf("kitty")),
			TerminalChoice.Written(listOf("xterm", "-T", "{folder}")),
			TerminalChoice.Described(
				listOf("t", "-a"),
				FolderArgs.joined("--dir="),
				ExecArg.Unsupported,
				emptyList(),
			),
		).forEach(::roundTrip)
	}

	@Test
	fun whatCannotBeReadIsSaidWhyAndKeptAsItWasWritten() {
		val wrong =
			listOf(
				""""ghostty"""",
				"""{"folder": ["{folder}"]}""",
				"""{"command": []}""",
				"""{"command": ["t"], "folder": ["--in"]}""",
				"""{"command": ["t"], "dir": ["{folder}"]}""",
				"""["t", 3]""",
			).map(::read)

		wrong.forEach { assertTrue(it is TerminalChoice.Unreadable, "$it") }
		assertTrue((wrong[3] as TerminalChoice.Unreadable).why.contains("{folder}"))
		assertTrue((wrong[4] as TerminalChoice.Unreadable).why.contains("“dir”"))
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
