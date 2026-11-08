package barion.picker

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The picker's model and its view. The model's rules are checked as values; the view is composed and drawn headless,
 * in both palettes, which is the test that it composes at all, and with `-Ppicker.screenshots=DIR` writes what it
 * drew there to look at.
 */
class PickerTest {
	@TempDir
	lateinit var work: Path

	private val home = Path("/home/avery")

	/** A row as the source would make it, from a size in bytes and a time, said in UTC. */
	private fun row(
		name: String,
		bytes: Long? = null,
		at: String = "2026-11-08T09:41:00Z",
	): Row {
		val folder = bytes == null
		val category = if (folder) Category.Folder else categoryOf(name)
		val instant = Instant.parse(at)

		return Row(
			RowId(name),
			name,
			category,
			typeOf(name, category),
			bytes?.let { Shown(it, sizeOf(it)) },
			Shown(instant, dateOf(instant, ZoneOffset.UTC)),
			name.startsWith("."),
		)
	}

	/** A home folder as a person's might be, every kind of row in it. */
	private fun fixture(
		choosing: Choosing = Choosing.OneFile(RowId("Quarterly report.pdf")),
	): PickerState {
		val rows =
			listOf(
				row("Desktop"),
				row("Documents"),
				row("Downloads"),
				row("Music"),
				row("Pictures"),
				row("Projects"),
				row("Videos"),
				row("Budget 2026.xlsx", 48_000, "2026-11-02T17:05:00Z"),
				row("Holiday in Lisbon.jpg", 4_400_000, "2026-10-21T12:30:00Z"),
				row("Quarterly report.pdf", 1_150_000, "2026-11-07T16:12:00Z"),
				row("backup.tar.gz", 327_000_000, "2026-10-30T03:00:00Z"),
				row("main.kt", 2_460, "2026-11-08T11:20:00Z"),
				row("notes.md", 6_250, "2026-11-08T10:02:00Z"),
				row("screenshot 2.png", 901_000, "2026-11-05T14:44:00Z"),
				row("screenshot 10.png", 934_000, "2026-11-06T09:13:00Z"),
				row("song.flac", 39_800_000, "2026-09-12T21:40:00Z"),
				row("trailer.mp4", 100_600_000, "2026-11-01T19:27:00Z"),
				row("todo.txt", 214, "2026-11-08T08:55:00Z"),
			)

		val places =
			listOf(
				Place("Home", Place.Kind.Home, home),
				Place("Desktop", Place.Kind.Desktop, home.resolve("Desktop")),
				Place("Documents", Place.Kind.Documents, home.resolve("Documents")),
				Place("Downloads", Place.Kind.Downloads, home.resolve("Downloads")),
				Place("Pictures", Place.Kind.Pictures, home.resolve("Pictures")),
				Place("Music", Place.Kind.Music, home.resolve("Music")),
				Place("Videos", Place.Kind.Videos, home.resolve("Videos")),
			)

		val drives =
			listOf(
				Place("File system", Place.Kind.Drive, Path("/")),
				Place("Data", Place.Kind.Drive, Path("/mnt/data")),
			)

		return PickerState(
			title = titleOf(choosing),
			choosing = choosing,
			folder = home,
			crumbs = crumbsOf(home, home),
			sidebar =
				Sidebar(
					listOf(
						Sidebar.Group("Quick access", places),
						Sidebar.Group("This computer", drives),
					),
				),
			listing = Listing.Shown(sorted(rows, Sort.BY_NAME), Sort.BY_NAME),
			filters = listOf(TypeFilter.ALL_FILES, TypeFilter("Images", listOf("*.png", "*.jpg"))),
			filter = TypeFilter.ALL_FILES,
			query = "",
			canGoBack = true,
			canGoForward = false,
		)
	}

	private fun screenshot(name: String, png: ByteArray) {
		System.getProperty("picker.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
			Path(dir).createDirectories().resolve("$name.png").writeBytes(png)
		}
	}

	@Test
	fun thePickerDrawsInBothPalettes() {
		for ((name, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
			val png = renderedPng(fixture(), 1100, 680, palette, scale = 2f)

			// A PNG's signature, and more than a blank image would take.
			assertEquals(0x89.toByte(), png[0])
			assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
			screenshot("picker-$name", png)
		}
	}

	@Test
	fun everyKindOfPickerDraws() {
		val kinds =
			mapOf(
				"files" to Choosing.Files(setOf(RowId("main.kt"), RowId("notes.md"))),
				"save" to Choosing.SaveAs("Quarterly report (final).pdf"),
				"folder" to Choosing.Folder(null),
			)

		for ((name, choosing) in kinds)
			screenshot(
				"picker-$name",
				renderedPng(fixture(choosing), 1100, 680, Palette.Light, scale = 2f),
			)
	}

	@Test
	fun oneNameIsWrittenAsItIsAndSeveralInQuotes() {
		assertEquals("Quarterly report.pdf", fixture().nameField)
		assertEquals(
			"\"main.kt\" \"notes.md\"",
			fixture(Choosing.Files(setOf(RowId("main.kt"), RowId("notes.md")))).nameField,
		)
	}

	@Test
	fun theButtonIsWhatHasBeenChosen() {
		assertEquals(Accept("Open", false), Choosing.OneFile(null).accept)
		assertEquals(Accept("Open", true), Choosing.OneFile(RowId("a")).accept)
		assertEquals(Accept("Open", false), Choosing.Files(emptySet()).accept)
		assertEquals(Accept("Save", false), Choosing.SaveAs("  ").accept)
		assertEquals(Accept("Save", true), Choosing.SaveAs("a.txt").accept)
		// A folder picker always has a folder: the one shown.
		assertEquals(Accept("Select folder", true), Choosing.Folder(null).accept)
	}

	@Test
	fun aStateCannotChooseARowItDoesNotShow() {
		assertThrows<IllegalArgumentException> {
			fixture(Choosing.OneFile(RowId("not there.txt")))
		}
	}

	@Test
	fun theQueryNarrowsTheRows() {
		val narrowed = fixture().copy(query = "SCREEN").visibleRows.map { it.name }

		assertEquals(listOf("screenshot 2.png", "screenshot 10.png"), narrowed)
	}

	@Test
	fun foldersComeFirstAndNumbersByTheirValue() {
		val names = (fixture().listing as Listing.Shown).rows.map { it.name }

		assertTrue(names.indexOf("Videos") < names.indexOf("Budget 2026.xlsx"), "$names")
		assertTrue(
			names.indexOf("screenshot 2.png") < names.indexOf("screenshot 10.png"),
			"$names",
		)
		assertTrue(naturalOrder("file9", "file10") < 0)
		assertTrue(naturalOrder("File", "file") != 0)
	}

	@Test
	fun aColumnOrdersByItsValueNotItsWords() {
		val rows = (fixture().listing as Listing.Shown).rows
		val byDate =
			sorted(rows, Sort(Sort.Column.Modified, ascending = true))
				.filterNot {
					it.isFolder
				}.map { it.name }

		val bySize =
			sorted(rows, Sort(Sort.Column.Size, ascending = false))
				.filterNot {
					it.isFolder
				}.map { it.name }

		// "12 Sep" is before "8 Nov" in time, though not in the words for it.
		assertEquals("song.flac", byDate.first())
		assertEquals("main.kt", byDate.last())
		// 312 MB before 38 MB, though "3" comes before "3" and "1" before "8".
		assertEquals(listOf("backup.tar.gz", "trailer.mp4", "song.flac"), bySize.take(3))
		assertEquals("todo.txt", bySize.last())
	}

	@Test
	fun sizesAreSaidAsPeopleReadThem() {
		assertEquals("1 byte", sizeOf(1))
		assertEquals("214 bytes", sizeOf(214))
		assertEquals("4.2 KB", sizeOf(4300))
		assertEquals("312 MB", sizeOf(312L * 1024 * 1024))
	}

	@Test
	fun theTypeIsTheExtensionsOwn() {
		assertEquals("Kotlin source", typeOf("main.kt", categoryOf("main.kt")))
		assertEquals(Category.Image, categoryOf("A.JPEG"))
		assertEquals("XYZ file", typeOf("a.xyz", categoryOf("a.xyz")))
		// A dotfile's dot is not an extension.
		assertEquals("File", typeOf(".bashrc", categoryOf(".bashrc")))
	}

	@Test
	fun thePathIsCrumbsFromHomeOrFromTheRoot() {
		assertEquals(
			listOf(
				"Home",
				"Documents",
				"Taxes",
			),
			crumbsOf(home.resolve("Documents/Taxes"), home).map {
				it.label
			},
		)
		assertEquals(
			listOf("File system", "etc"),
			crumbsOf(Path("/etc"), home).map { it.label },
		)
		assertEquals(listOf("File system"), crumbsOf(Path("/"), home).map { it.label })
	}

	@Test
	fun aRealFolderIsReadIntoRows() {
		work.resolve("Folder").createDirectory()
		work.resolve("photo.png").writeText("not really")
		work.resolve(".hidden").writeText("")

		val state =
			pickerOf(work, Choosing.OneFile(null), Reading(zone = ZoneOffset.UTC), home = work)
		val rows = (state.listing as Listing.Shown).rows

		assertEquals(listOf("Folder", "photo.png"), rows.map { it.name })
		assertEquals("PNG image", rows[1].type)
		assertEquals(Shown(10L, "10 bytes"), rows[1].size)
		assertFalse(rows.any { it.hidden })
		assertEquals(listOf("Home"), state.crumbs.map { it.label })
	}

	@Test
	fun aFolderThatCannotBeReadIsSaidSo() {
		assertInstanceOf(
			Listing.Unreadable::class.java,
			listingOf(work.resolve("nowhere"), Reading()),
		)
	}

	@Test
	fun theUsersOwnFolderNamesAreRead() {
		work.resolve(".config").createDirectory()
		work
			.resolve(
				".config/user-dirs.dirs",
			).writeText("XDG_DOWNLOAD_DIR=\"\$HOME/Téléchargements\"\n")

		assertEquals(work.resolve("Téléchargements"), userDirectories(work)["XDG_DOWNLOAD_DIR"])
		assertEquals(work.resolve("Desktop"), userDirectories(work)["XDG_DESKTOP_DIR"])
	}

	@Test
	fun datesAreSaidInTheZoneGiven() {
		assertEquals(
			"8 Nov 2026, 09:41",
			dateOf(Instant.parse("2026-11-08T09:41:00Z"), ZoneOffset.UTC),
		)
	}
}
