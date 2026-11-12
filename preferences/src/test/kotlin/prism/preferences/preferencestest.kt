package prism.preferences

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
				.arranging(
					QuickAccess.DEFAULT
						.pinning(Path.of("/home/avery/Projects"))
						.pinning(Path.of("/mnt/data"))
						.showing(Builtin.Music, false)
						.moved(7, -7),
				).binding("SearchIndex", listOf("Ctrl+Space"))
				.binding("Back", listOf("Mouse Back", "Alt+Left"))
				.copy(
					terminal = TerminalChoice.Named("foot"),
				)

		PreferenceFile.open(file).use { it.update { set } }

		assertEquals(PreferencesRead.Read(set), PreferenceFile.read(file))
		// Defaults are written too, for the file to say what can be set.
		assertTrue("\"quickAccess\"" in file.readText())
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

			store.update { it.arranging(QuickAccess.DEFAULT.pinning(Path.of("/srv"))) }

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
	fun theDefaultLookAndTheTextSizesAreWrittenAndReadBack() {
		Files.createDirectories(file.parent)
		file.writeText(
			"""{ "folders": { "sort": { "by": "Modified", "ascending": false }, "hidden": [ "Type" ] },
				"results": { "order": { "type": "by", "sort": { "by": "Name", "ascending": true } } },
				"text": { "menus": 18 }, "dates": "Iso", "scales": { "sidebar": 1.25 } }""",
		)

		val read = PreferenceFile.read(file).preferences

		assertEquals(Sort(Sort.Column.Modified, ascending = false), read.folders.sort)
		assertEquals(setOf(Column.Type), read.folders.hidden)
		assertEquals(Order.By(Sort.BY_NAME), read.results.order)
		assertEquals(DateFormat.Iso, read.dates)
		assertEquals(1.25f, read.scales.sidebar)
		assertEquals(TextSizes(menus = 18f), read.text)
		// What is not said is the default: menus a little larger than the rest.
		assertEquals(TextSizes.MENUS, Preferences().text.menus)
		assertEquals(
			TextSizes(general = TextSizes.SMALLEST, menus = TextSizes.LARGEST),
			TextSizes(general = 2f, menus = 90f).bounded(),
		)
	}

	@Test
	fun aChangeMadeThroughOneStoreReachesAnotherOfTheSameFile() {
		PreferenceFile.open(file).use { one ->
			PreferenceFile.open(file).use { other ->
				one.update { it.arranging(QuickAccess.DEFAULT.pinning(Path.of("/srv/media"))) }

				val deadline = System.currentTimeMillis() + 10_000

				while (other.preferences.value.quickAccess == null &&
					System.currentTimeMillis() < deadline
					)
					Thread.sleep(20)

				assertEquals(
					listOf(Path.of("/srv/media")),
					other.preferences.value.quickAccess
						?.pins,
				)
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

	@Test
	fun quickAccessAlwaysHoldsEachOfPrismsOwnOnce_AndEachFolderOnce() {
		val projects = Path.of("/home/avery/Projects")

		val read =
			QuickAccess.of(
				listOf(
					QuickItem.Pinned(projects),
					QuickItem.Own(Builtin.Trash, shown = false),
					QuickItem.Own(Builtin.Trash),
					QuickItem.Pinned(projects),
				),
			)

		// The first of what is named twice; prism's own that were left out added, shown, in their own order.
		assertEquals(
			listOf(QuickItem.Pinned(projects), QuickItem.Own(Builtin.Trash, shown = false)) +
				Builtin.entries.filter { it != Builtin.Trash }.map { QuickItem.Own(it) },
			read.items,
		)
		// Pinned again, a folder is still there once; unpinned, it is gone; prism's own are only ever hidden.
		assertEquals(read, read.pinning(projects))
		assertEquals(emptyList<Path>(), read.unpinning(projects).pins)
		assertFalse(read.shows(Builtin.Trash))
		assertTrue(read.showing(Builtin.Trash, true).shows(Builtin.Trash))
		// Moved as far as it can go, and no further.
		assertEquals(QuickItem.Pinned(projects), read.moved(0, 100).items.last())
		assertEquals(
			QuickItem.Pinned(projects),
			read
				.moved(0, 100)
				.moved(read.items.lastIndex, -100)
				.items
				.first(),
		)
	}

	@Test
	fun aListingOrderedByATimeShowsThatTime() {
		val created = FolderView(Sort(Sort.Column.Created, ascending = false))

		assertEquals(DateShown.Created, created.dateShown)
		// Shown the other time, it is ordered by that one, the same way round.
		assertEquals(
			Sort(Sort.Column.Modified, ascending = false),
			created.showing(DateShown.Modified).sort,
		)
		// Ordered by name, it shows whichever time it is told, and stays ordered by name.
		assertEquals(DateShown.Created, FolderView().showing(DateShown.Created).dateShown)
		assertEquals(Sort.BY_NAME, FolderView().showing(DateShown.Created).sort)
		// Results ordered best first show whichever time they are told.
		assertEquals(DateShown.Created, ResultsView().showing(DateShown.Created).dateShown)
		assertEquals(Order.Relevance, ResultsView().showing(DateShown.Created).order)
	}
}
