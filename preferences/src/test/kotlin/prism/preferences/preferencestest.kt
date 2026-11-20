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
import prism.shared.Loaded
import prism.shared.ScrollDirection
import prism.shared.orElse

/**
 * The preferences: every default where nothing is set; written whole and read back as they were; a file written wrong
 * by hand left as it is until a change, then kept beside the new one. How any value kept in a file is read, changed
 * and written is prism.shared's to test (StoredTest); here is what is the preferences' own.
 */
class PreferencesTest {
	@TempDir
	lateinit var folder: Path

	private val file get() = folder.resolve("prism/preferences.json")

	@Test
	fun nothingSetIsEveryDefault() {
		PreferenceFile.open(file).let {
			assertEquals(Preferences.DEFAULT, it.preferences.value)
			assertNull(it.problem)
		}
		assertEquals(Loaded.Missing, PreferenceFile.read(file))
	}

	@Test
	fun theWheelScrollsAsTheSystemIsSetUnlessReversed_AndIsWrittenByName() {
		assertEquals(ScrollDirection.AsTheSystem, Preferences.DEFAULT.scrolling)

		val reversed = Preferences.DEFAULT.copy(scrolling = ScrollDirection.Reversed)
		val written = PreferenceFormat.written(reversed).toString(Charsets.UTF_8)

		assertTrue("\"scrolling\": \"Reversed\"" in written, written)
		assertEquals(Loaded.Read(reversed), PreferenceFormat.read(written.toByteArray()))
	}

	@Test
	fun aWheelSpedUpIsWrittenAsTimes_AndReadAsTheNearestSpeed() {
		assertEquals(1f, Preferences.DEFAULT.scrollSpeed.times)

		val faster = Preferences.DEFAULT.copy(scrollSpeed = ScrollSpeed.nearest(1.5f))
		val written = PreferenceFormat.written(faster).toString(Charsets.UTF_8)

		assertTrue("\"scrollSpeed\": 1.5" in written, written)
		assertEquals(Loaded.Read(faster), PreferenceFormat.read(written.toByteArray()))

		// Written by hand past the fastest, or between two speeds: read as the nearest there is.
		fun read(times: String) =
			(
				PreferenceFormat.read(
					"{ \"scrollSpeed\": $times }".toByteArray(),
				) as Loaded.Read
			).value.scrollSpeed

		assertEquals(ScrollSpeed.ALL.last(), read("40"))
		assertEquals(ScrollSpeed.ALL.first(), read("0"))
		assertEquals(1.25f, read("1.3").times)
	}

	@Test
	fun theJumpPauseIsASecondAndAHalf_WrittenAsSecondsAndReadAsTheNearest() {
		assertEquals(1500L, Preferences.DEFAULT.jumpPause.millis)

		val longer = Preferences.DEFAULT.copy(jumpPause = JumpPause.nearest(2.5f))
		val written = PreferenceFormat.written(longer).toString(Charsets.UTF_8)

		assertTrue("\"jumpPause\": 2.5" in written, written)
		assertEquals(Loaded.Read(longer), PreferenceFormat.read(written.toByteArray()))

		// Written by hand past the longest, or between two pauses: read as the nearest there is.
		fun read(seconds: String) =
			(PreferenceFormat.read("{ \"jumpPause\": $seconds }".toByteArray()) as Loaded.Read)
				.value
				.jumpPause

		assertEquals(JumpPause.ALL.last(), read("40"))
		assertEquals(JumpPause.ALL.first(), read("0"))
		assertEquals(1250L, read("1.3").millis)
	}

	@Test
	fun preferencesWrittenAreReadBackAndTheFileShowsEverySetting() {
		val set =
			Preferences.DEFAULT
				.arranging(
					QuickAccess.DEFAULT
						.pinning(Path.of("/home/avery/Projects"), Role.Explorer)
						.pinning(Path.of("/mnt/data"), Role.Picker)
						.showing(Builtin.Music, Role.Explorer, false)
						.showingFilesystems(Role.Picker, false)
						.moved(7, -7),
				).binding("SearchIndex", listOf("Ctrl+Space"))
				.binding("Back", listOf("Mouse Back", "Alt+Left"))
				.copy(
					terminal = TerminalChoice.Named("foot"),
				)

		PreferenceFile.open(file).update { set }

		assertEquals(Loaded.Read(set), PreferenceFile.read(file))
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

		PreferenceFile.open(file).let { store ->
			assertEquals(Preferences.DEFAULT, store.preferences.value)
			assertNotNull(store.problem)

			store.update { arranging(QuickAccess.DEFAULT.pinning(Path.of("/srv"), Role.Explorer)) }

			assertNull(store.problem)
			assertEquals(
				"{ \"pinned\": [ unclosed",
				file.resolveSibling("preferences.json.unreadable").readText(),
			)
		}
		assertInstanceOf(Loaded.Read::class.java, PreferenceFile.read(file))
	}

	@Test
	fun aSettingTheFileDoesNotKnowIsLeftAlone() {
		Files.createDirectories(file.parent)
		file.writeText("{ \"terminal\": \"kitty\", \"from-the-future\": true }")

		assertEquals(
			TerminalChoice.Named("kitty"),
			PreferenceFile.read(file).orElse(Preferences.DEFAULT).terminal,
		)
	}

	@Test
	fun theLaunchersNumbersAreWithinWhatItCanBeDrawnWithWhateverAFileOrAStepSays() {
		Files.createDirectories(file.parent)
		// As a file from before the numbers had bounds of their own may have them: plain numbers, any at all.
		file.writeText(
			"{ \"launcher\": { \"width\": 99999, \"rows\": -3, \"scale\": 40.0, \"fileResults\": 0 } }",
		)

		val read = PreferenceFile.read(file)

		assertInstanceOf(Loaded.Read::class.java, read)
		assertEquals(
			LauncherPreferences(
				width = LauncherWidth.of(LauncherWidth.range.last),
				rows = LauncherRows.of(LauncherRows.range.first),
				scale = LauncherScale.of(Scales.LARGEST),
				fileResults = FileResults.of(1),
			),
			read.orElse(Preferences.DEFAULT).launcher,
		)
		// One within them is as it was written, and is written as the plain number it is.
		file.writeText("{ \"launcher\": { \"width\": 800, \"rows\": 12 } }")
		assertEquals(
			800,
			PreferenceFile
				.read(file)
				.orElse(Preferences.DEFAULT)
				.launcher.width.number,
		)
		PreferenceFile.open(file).update { binding("Back", listOf("Alt+Left")) }
		assertTrue("\"width\": 800" in file.readText())

		// A step past the last stops at it, and says it is there.
		val rows = LauncherRows.of(19) + 5

		assertEquals(20, rows.number)
		assertTrue(rows.most)
		assertFalse(rows.least)
		assertTrue((rows + -100).least)
		assertTrue(LauncherScale.of(Scales.SMALLEST).stepped(-1).least)
		assertEquals(1.1f, LauncherScale.of(1f).stepped(1).times)
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

		val read = PreferenceFile.read(file).orElse(Preferences.DEFAULT)
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
	fun preferencesInMemoryChangeAsAFile() {
		val store = MemoryPreferences()

		store.update { copy(terminal = TerminalChoice.Named("alacritty")) }
		assertEquals(
			TerminalChoice.Named("alacritty"),
			store.preferences.value.terminal,
		)
	}

	@Test
	fun eachEntryOfQuickAccessIsShownInTheExplorerOrThePickersOrBoth_AsAreTheFileSystems() {
		val projects = Path.of("/home/avery/Projects")
		val quick = QuickAccess.DEFAULT.pinning(projects, Role.Picker)

		// A folder new to quick access is shown everywhere, whichever window pinned it.
		assertEquals(ShownIn.EVERYWHERE, quick.items.last().shown)

		// Taken off in one kind of window, it is still the other's; taken off in both, it is gone.
		val pickers = quick.unpinning(projects, Role.Explorer)

		assertEquals(ShownIn(explorer = false, picker = true), pickers.items.last().shown)
		assertEquals(listOf(projects), pickers.pins)
		assertEquals(emptyList<Path>(), pickers.unpinning(projects, Role.Picker).pins)
		// Pinned again where it is not shown, it is the same entry, shown there too: in its place, not at the end.
		assertEquals(quick, pickers.pinning(projects, Role.Explorer))

		// One of prism's own is hidden from one kind of window and shown in the other.
		val hidden = quick.showing(Builtin.Music, Role.Picker, false)

		assertTrue(hidden.shows(Builtin.Music, Role.Explorer))
		assertFalse(hidden.shows(Builtin.Music, Role.Picker))

		// Every entry at once, for one kind of window; the other's are as they were.
		val none = hidden.showingAll(Role.Explorer, false)

		assertTrue(none.items.none { it.shown.explorer })
		assertEquals(hidden.items.map { it.shown.picker }, none.items.map { it.shown.picker })
		assertEquals(
			hidden.items.map { it.showing(it.shown.with(Role.Explorer, true)) },
			none.showingAll(Role.Explorer, true).items,
		)

		// The file systems under it, each kind of window its own.
		assertEquals(ShownIn.EVERYWHERE, quick.filesystems)
		assertEquals(
			ShownIn(explorer = true, picker = false),
			quick.showingFilesystems(Role.Picker, false).filesystems,
		)
		// And they stay as they are whatever is done to the entries.
		assertEquals(
			ShownIn(explorer = true, picker = false),
			quick
				.showingFilesystems(Role.Picker, false)
				.moved(0, 3)
				.pinning(Path.of("/srv"), Role.Explorer)
				.removing(projects)
				.showingAll(Role.Picker, false)
				.filesystems,
		)
	}

	@Test
	fun quickAccessAsAnOlderVersionWroteItIsShownInEveryWindowAsItWasShownAtAll() {
		val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

		// A list of entries, one answer each for every window, and nothing of the file systems.
		val older =
			json.decodeFromString(
				QuickAccess.serializer(),
				"""
				[
					{"type": "builtin", "place": "Home"},
					{"type": "builtin", "place": "Music", "shown": false},
					{"type": "folder", "path": "/home/avery/Projects"}
				]
				""".trimIndent(),
			)

		assertEquals(
			listOf(
				QuickItem.Own(Builtin.Home, ShownIn.EVERYWHERE),
				QuickItem.Own(Builtin.Music, ShownIn.NOWHERE),
				QuickItem.Pinned(Path.of("/home/avery/Projects"), ShownIn.EVERYWHERE),
			),
			older.items.filter {
				it is QuickItem.Pinned ||
					(it as QuickItem.Own).place in setOf(Builtin.Home, Builtin.Music)
			},
		)
		assertEquals(ShownIn.EVERYWHERE, older.filesystems)

		// As it is written now, it is read back as it was: each entry's windows, and the file systems'.
		val now =
			older
				.showing(Builtin.Home, Role.Picker, false)
				.showingFilesystems(Role.Explorer, false)

		assertEquals(
			now,
			json.decodeFromString(
				QuickAccess.serializer(),
				json.encodeToString(QuickAccess.serializer(), now),
			),
		)
	}

	@Test
	fun theSidebarIsShownOrNotInEachKindOfWindowApart() {
		val closed = Preferences.DEFAULT.showingSidebar(Role.Picker, false)

		assertEquals(ShownIn(explorer = true, picker = false), closed.sidebarShown)
		assertEquals(Preferences.DEFAULT, closed.showingSidebar(Role.Picker, true))
	}

	@Test
	fun quickAccessAlwaysHoldsEachOfPrismsOwnOnce_AndEachFolderOnce() {
		val projects = Path.of("/home/avery/Projects")

		val read =
			QuickAccess.of(
				listOf(
					QuickItem.Pinned(projects),
					QuickItem.Own(Builtin.Trash, ShownIn.NOWHERE),
					QuickItem.Own(Builtin.Trash),
					QuickItem.Pinned(projects),
				),
			)

		// The first of what is named twice; prism's own that were left out added, shown, where their own order puts
		// them: before the trash, which comes after every one of them.
		assertEquals(
			listOf(QuickItem.Pinned(projects)) +
				Builtin.entries.filter { it != Builtin.Trash }.map { QuickItem.Own(it) } +
				QuickItem.Own(Builtin.Trash, ShownIn.NOWHERE),
			read.items,
		)

		// One a later version adds goes where its order puts it among those there: the System page, first of them,
		// before the home folder, wherever that is.
		val older =
			listOf(
				QuickItem.Pinned(projects),
				QuickItem.Own(Builtin.Home),
				QuickItem.Own(Builtin.Trash),
			) +
				Builtin.entries
					.filter {
						it != Builtin.System && it != Builtin.Home &&
							it != Builtin.Trash
					}.map { QuickItem.Own(it) }

		assertEquals(
			listOf(
				QuickItem.Pinned(projects),
				QuickItem.Own(Builtin.System),
				QuickItem.Own(Builtin.Home),
			),
			QuickAccess.of(older).items.take(3),
		)
		// Pinned again, a folder is still there once; removed, it is gone; prism's own are only ever hidden.
		assertEquals(read, read.pinning(projects, Role.Explorer))
		assertEquals(emptyList<Path>(), read.removing(projects).pins)
		assertFalse(read.shows(Builtin.Trash, Role.Explorer))
		assertTrue(
			read.showing(Builtin.Trash, Role.Explorer, true).shows(Builtin.Trash, Role.Explorer),
		)
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
				.moved(Moved.of(3, 0))
				.settings
				.first()
				.id,
		)
		assertEquals(ColumnId.Type, columns.moved(Moved.of(3, 0)).settings[1].id)
		assertEquals(columns, columns.moved(Moved.of(0, 3)))
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
