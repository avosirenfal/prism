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
	fun theDefaultLookAndTheTypographyAreWrittenAndReadBack() {
		Files.createDirectories(file.parent)
		file.writeText(
			"""{ "listings": {
					"looks": {
						"explorer/folder": { "order": { "type": "by", "sort": { "by": "Modified", "ascending": false } },
							"columns": [ { "id": "Size", "align": "Center" }, { "id": "Name" }, { "id": "Type", "shown": false } ] },
						"picker/place:archive": { "order": { "type": "relevance" } }
					},
					"filters": { "explorer/results/gallery": { "hidden": { "files": false }, "kinds": [ "Images" ] } }
				},
				"typography": { "menus": { "points": 13.5 }, "listing": { "family": "Inter" } },
				"text": { "general": 13 }, "rows": "Compact",
				"dates": "Iso", "scales": { "sidebar": 1.25 } }""",
		)

		val read = PreferenceFile.read(file).preferences
		val folders = read.listings.look(Role.Explorer, ListingKind.Folder)
		val columns = folders.columns(ListingKind.Folder)

		assertEquals(Sort(ColumnId.Modified, ascending = false), folders.order.sort)
		// The name first and shown whatever the file says; the rest in its order; those it left out after, hidden.
		assertEquals(
			listOf(
				ColumnId.Name,
				ColumnId.Size,
				ColumnId.Type,
				ColumnId.Modified,
				ColumnId.Created,
				ColumnId.Extension,
			),
			columns.settings.map { it.id },
		)
		assertEquals(listOf(ColumnId.Name, ColumnId.Size), columns.shown.map { it.id })
		assertEquals(Align.Center, columns.settings[1].aligned)
		// A picker's folders look as the explorer's, where nothing is said of them; an archive as it is told to.
		assertEquals(folders, read.listings.look(Role.Picker, ListingKind.Folder))
		assertEquals(
			Order.Relevance,
			read.listings.look(Role.Picker, ListingKind.Place("archive")).order,
		)
		assertEquals(
			Kind.Images,
			read.listings
				.filters(Role.Picker, ListingKind.Results, Layout.Gallery)
				.kinds
				.single(),
		)
		assertEquals(
			Filters(),
			read.listings.filters(Role.Explorer, ListingKind.Results, Layout.List),
		)
		assertEquals("Inter", read.typography.familyOf(TextPart.Listing))
		assertEquals(Rows.Compact, read.rows)
		assertEquals(DateFormat.Iso, read.dates)
		assertEquals(1.25f, read.scales.sidebar)
		assertEquals(13.5f, read.typography.pointsOf(TextPart.Menus))
		// What is not chosen is the default, whatever an older version's sizes said: it wrote its defaults as though
		// they were chosen, and they would hold back a larger default for ever.
		assertEquals(TextPart.General.points, read.typography.pointsOf(TextPart.General))
		assertEquals(null, read.typography.general.points)
		// A size is within what can be read, and steps half a point at a time.
		assertEquals(
			Typography.LARGEST,
			Typography(general = FontChoice(points = 90f)).pointsOf(TextPart.General),
		)
		assertEquals(
			11.5f,
			Typography().stepped(TextPart.General, 1).pointsOf(TextPart.General),
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
	fun aListingsColumnsAreEachThatItsKindOffersOnce_TheNamesFirstAndAlwaysShown() {
		val columns = ListingKind.Results.columnsOf(emptyList())

		// As the kind has them by default: a search's results say where each is, and not when it was made.
		assertEquals(ListingKind.Results.offered, columns.settings.map { it.id })
		assertFalse(ColumnId.Created in columns.settings.map { it.id })
		assertFalse(columns.isShown(ColumnId.Extension))
		// The name stays first, however a column is moved, and cannot be hidden.
		assertEquals(
			ColumnId.Name,
			columns
				.moved(3, 0)
				.settings
				.first()
				.id,
		)
		assertEquals(ColumnId.Type, columns.moved(3, 0).settings[1].id)
		assertEquals(columns, columns.moved(0, 3))
		assertTrue(columns.showing(ColumnId.Name, false).isShown(ColumnId.Name))
		// Aligned as the column is by its own, the setting says nothing.
		assertEquals(
			null,
			columns
				.aligning(ColumnId.Size, Align.End)
				.settings
				.single {
					it.id ==
						ColumnId.Size
				}.align,
		)
		// The trash's location is where an entry was.
		assertEquals("Original location", ListingKind.Trash.labelOf(ColumnId.Location))
		assertEquals(ListingKind.Place("archive"), ListingKind.of("place:archive"))
	}

	@Test
	fun aDateIsWrittenPartByPart_EachFieldANumberOrAWord() {
		val at = java.time.Instant.parse("2026-11-02T14:03:00Z")
		val utc = java.time.ZoneOffset.UTC

		assertEquals("2 Nov 2026, 14:03", DateFormat.Written.write(at, utc))
		assertEquals("Nov 2, 2026, 2:03 PM", DateFormat.WrittenMonthFirst.write(at, utc))
		assertEquals("2026-11-02 14:03", DateFormat.Iso.write(at, utc))

		// The parts a listing lines up: each field, and the words between them.
		val parts = DateFormat.WrittenMonthFirst.dateParts

		assertEquals(
			listOf(
				DatePart.Field("MMM"),
				DatePart.Words(" "),
				DatePart.Field("d"),
				DatePart.Words(", "),
				DatePart.Field("yyyy"),
			),
			parts,
		)
		// A month's name is a word, lined up at its start; a day and a year are numbers, lined up at their ends.
		assertEquals(
			listOf(false, true, true),
			parts.filterIsInstance<DatePart.Field>().map {
				it.numeric
			},
		)
		assertEquals("Nov", DateFormat.WrittenMonthFirst.write(parts[0], at, utc))
		// Words in quotes are words, and two quotes are a quote.
		assertEquals(
			listOf(DatePart.Field("HH"), DatePart.Words(" o'clock")),
			DatePart.of("HH 'o''clock'"),
		)
	}
}
