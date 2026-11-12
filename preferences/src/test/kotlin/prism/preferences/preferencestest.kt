package prism.preferences

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.TerminalChoice

/**
 * The preferences: every default where nothing is set; written whole and read back as they were; a file written wrong
 * by hand left as it is until a change, then kept beside the new one; and a change made through one store, as one
 * window makes it, reaching another store of the same file, as another window, or another process, has it.
 */
class PreferencesTest {
	@TempDir
	lateinit var folder: Path

	private val file get() = folder.resolve("prism/preferences.json")

	@Test
	fun nothingSetIsEveryDefault() {
		PreferenceFile.open(file).use {
			assertEquals(Preferences.DEFAULT, it.preferences.value)
			assertNull(it.problem)
		}
		assertEquals(PreferencesRead.Missing, PreferenceFile.read(file))
	}

	@Test
	fun preferencesWrittenAreReadBackAndTheFileShowsEverySetting() {
		val set =
			Preferences.DEFAULT
				.pinning(listOf(Path.of("/home/avery/Projects"), Path.of("/mnt/data")))
				.binding("SearchIndex", listOf("Ctrl+Space"))
				.binding("Back", listOf("Mouse Back", "Alt+Left"))
				.copy(
					terminal = TerminalChoice.Named("foot"),
				)

		PreferenceFile.open(file).use { it.update { set } }

		assertEquals(PreferencesRead.Read(set), PreferenceFile.read(file))
		// Defaults are written too, for the file to say what can be set.
		assertTrue("\"pinned\"" in file.readText())
		// And a binding put back to its default is no longer named.
		assertEquals(
			mapOf("Back" to listOf("Mouse Back", "Alt+Left")),
			set.binding("SearchIndex", null).bindings,
		)
	}

	@Test
	fun aFileWrittenWrongIsKeptAndNotWrittenOverUnseen() {
		Files.createDirectories(file.parent)
		file.writeText("{ \"pinned\": [ unclosed")

		PreferenceFile.open(file).use { store ->
			assertEquals(Preferences.DEFAULT, store.preferences.value)
			assertNotNull(store.problem)

			store.update { it.pinning(listOf(Path.of("/srv"))) }

			assertNull(store.problem)
			assertEquals(
				"{ \"pinned\": [ unclosed",
				file.resolveSibling("preferences.json.unreadable").readText(),
			)
		}
		assertInstanceOf(PreferencesRead.Read::class.java, PreferenceFile.read(file))
	}

	@Test
	fun aSettingTheFileDoesNotKnowIsLeftAlone() {
		Files.createDirectories(file.parent)
		file.writeText("{ \"terminal\": \"kitty\", \"from-the-future\": true }")

		assertEquals(
			TerminalChoice.Named("kitty"),
			PreferenceFile.read(file).preferences.terminal,
		)
	}

	@Test
	fun aChangeMadeThroughOneStoreReachesAnotherOfTheSameFile() {
		PreferenceFile.open(file).use { one ->
			PreferenceFile.open(file).use { other ->
				one.update { it.pinning(listOf(Path.of("/srv/media"))) }

				val deadline = System.currentTimeMillis() + 10_000

				while (other.preferences.value.pinned == null &&
					System.currentTimeMillis() < deadline
					)
					Thread.sleep(20)

				assertEquals(listOf(Path.of("/srv/media")), other.preferences.value.pinned)
			}
		}
		assertTrue(file.exists())
	}

	@Test
	fun preferencesInMemoryChangeAsAFile() {
		val store = MemoryPreferences()

		store.update { it.copy(terminal = TerminalChoice.Named("alacritty")) }
		assertEquals(
			TerminalChoice.Named("alacritty"),
			store.preferences.value.terminal,
		)
	}
}
