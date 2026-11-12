package prism.browser

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
import org.junit.jupiter.api.io.TempDir
import prism.browser.Fixtures.home
import prism.browser.Fixtures.picker
import prism.desktop.freedesktop.XdgPlaces
import prism.preferences.ColumnId
import prism.preferences.DateFormat
import prism.preferences.Hidden
import prism.preferences.Kind
import prism.preferences.Layout
import prism.preferences.Order
import prism.preferences.Sort

/**
 * The picker's model, its source and its drawing. The model's rules are checked as values; the view is composed and
 * drawn headless, in both palettes, which is the test that it composes at all, and with `-Pbrowser.screenshots=DIR`
 * writes what it drew there to look at.
 */
class PickerTest {
	@TempDir
	lateinit var work: Path

	private fun id(name: String) = RowId(name)

	/** The picker over the fixture's home, a file chosen. */
	private fun fixture(
		choosing: Choosing = Choosing.OneFile(),
		vararg inputs: Input,
	): BrowserState = after(picker(choosing), *inputs)

	private fun screenshot(name: String, png: ByteArray) {
		System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
			Path(dir).createDirectories().resolve("$name.png").writeBytes(png)
		}
	}

	private val reportChosen =
		BrowserEvent.Clicked(
			RowId("Quarterly report.pdf"),
			Click.Only,
		)

	@Test
	fun thePickerDrawsInBothPalettes() {
		// As a picker opens: hidden files shown (dimmed), hidden folders not.
		val state =
			after(
				picker(hidden = Hidden()),
				BrowserEvent.Opened(Path("/")),
				BrowserEvent.Opened(home),
				BrowserEvent.Clicked(RowId("Holiday in Lisbon.jpg"), Click.Only),
			)

		for ((name, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
			val png = renderedPng(state, 1100, 680, palette, scale = 2f)

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
				"files" to
					fixture(
						Choosing.Files(),
						BrowserEvent.Clicked(id("main.kt"), Click.Only),
						BrowserEvent.Clicked(id("notes.md"), Click.Toggle),
					),
				"save" to fixture(Choosing.SaveAs("Quarterly report (final).pdf")),
				"folder" to
					fixture(Choosing.Folder(), BrowserEvent.Clicked(id("Projects"), Click.Only)),
				"replace" to fixture(Choosing.SaveAs("notes.md"), BrowserEvent.Accepted),
				"address" to fixture(Choosing.OneFile(), BrowserEvent.EditedAddress),
				"search" to fixture(Choosing.OneFile(), BrowserEvent.Searched("shot")),
				"unreadable" to fixture(Choosing.OneFile(), BrowserEvent.Opened(Path("/root"))),
				"gallery" to
					fixture(Choosing.OneFile(), BrowserEvent.Viewed(Layout.Gallery), reportChosen),
				"kinds" to
					fixture(
						Choosing.OneFile(),
						BrowserEvent.ToggledKind(Kind.Images),
						BrowserEvent.ToggledKind(Kind.Videos),
					),
			)

		for ((name, state) in kinds)
			screenshot("picker-$name", renderedPng(state, 1100, 680, Palette.Light, scale = 2f))

		assertInstanceOf(Notice.Replace::class.java, kinds.getValue("replace").notice)
		assertInstanceOf(Listing.Unreadable::class.java, kinds.getValue("unreadable").listing)
	}

	@Test
	fun oneNameIsWrittenAsItIsAndSeveralInQuotes() {
		assertEquals(
			"Quarterly report.pdf",
			fixture(Choosing.OneFile(), reportChosen).nameField,
		)
		assertEquals(
			"\"main.kt\" \"notes.md\"",
			fixture(
				Choosing.Files(),
				BrowserEvent.Clicked(id("main.kt"), Click.Only),
				BrowserEvent.Clicked(id("notes.md"), Click.Toggle),
			).nameField,
		)
		// What is typed is what is shown, until a row is chosen again.
		assertEquals(
			"other",
			fixture(Choosing.OneFile(), reportChosen, BrowserEvent.Named("other")).nameField,
		)
		assertEquals(
			"main.kt",
			fixture(
				Choosing.OneFile(),
				BrowserEvent.Named("other"),
				BrowserEvent.Clicked(id("main.kt"), Click.Only),
			).nameField,
		)
	}

	@Test
	fun theButtonIsWhatHasBeenChosen() {
		assertEquals(Accept("Open", false), fixture().accept)
		assertEquals(Accept("Open", true), fixture(Choosing.OneFile(), reportChosen).accept)
		assertEquals(Accept("Open", false), fixture(Choosing.Files()).accept)
		assertEquals(
			Accept("Open", true),
			fixture(Choosing.OneFile(), BrowserEvent.Named("x")).accept,
		)
		assertEquals(Accept("Save", false), fixture(Choosing.SaveAs("  ")).accept)
		assertEquals(Accept("Save", true), fixture(Choosing.SaveAs("a.txt")).accept)
		// A folder picker always has a folder: the one shown.
		assertEquals(Accept("Select folder", true), fixture(Choosing.Folder()).accept)
	}

	@Test
	fun theQueryNarrowsTheRows() {
		val narrowed =
			fixture(
				Choosing.OneFile(),
				BrowserEvent.Searched("SCREEN"),
			).rows.list.map {
				it.name
			}

		assertEquals(listOf("screenshot 2.png", "screenshot 10.png"), narrowed)
	}

	@Test
	fun foldersComeFirstAndNumbersByTheirValue() {
		val names = fixture().rows.list.map { it.name }

		assertTrue(names.indexOf("Videos") < names.indexOf("Budget 2026.xlsx"), "$names")
		assertTrue(
			names.indexOf("screenshot 2.png") < names.indexOf("screenshot 10.png"),
			"$names",
		)
		assertTrue(naturalOrder("file9", "file10") < 0)
		assertTrue(naturalOrder("file010", "file9") > 0)
		assertTrue(naturalOrder("a", "ab") < 0)
		assertTrue(naturalOrder("File", "file") != 0)
		assertEquals(0, naturalOrder("same", "same"))
	}

	@Test
	fun aColumnOrdersByItsValueNotItsWords() {
		val rows = Fixtures.homeRows
		val byDate =
			sorted(rows, Order.By(Sort(ColumnId.Modified, ascending = true)))
				.filterNot {
					it.isFolder
				}.map { it.name }

		val bySize =
			sorted(rows, Order.By(Sort(ColumnId.Size, ascending = false)))
				.filterNot {
					it.isFolder
				}.map { it.name }

		// "12 Sep" is before "8 Nov" in time, though not in the words for it.
		assertEquals(".bashrc", byDate.first())
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
			listOf("/", "etc"),
			crumbsOf(Path("/etc"), home).map { it.label },
		)
		assertEquals(listOf("/"), crumbsOf(Path("/"), home).map { it.label })
	}

	@Test
	fun aRealFolderIsReadIntoRows() {
		work.resolve("Folder").createDirectory()
		work.resolve("photo.png").writeText("not really")
		work.resolve(".hidden").writeText("")

		val state = pickerOf(work, Choosing.OneFile(), JvmDisk(), home = work)
		val rows = state.rows.list

		// Hidden entries are shown until the user hides them.
		assertEquals(listOf("Folder", ".hidden", "photo.png"), rows.map { it.name })
		assertEquals("PNG image", rows[2].type)
		assertEquals(Shown(10L, "10 bytes"), rows[2].size)
		assertTrue(rows[1].hidden)
		assertEquals(listOf("Home"), state.crumbs.map { it.label })
	}

	@Test
	fun aRealDiskSaysWhatIsAtAPath() {
		work.resolve("Folder").createDirectory()
		work.resolve("file.txt").writeText("")

		val found =
			JvmDisk().look(
				listOf(
					work.resolve("Folder"),
					work.resolve("file.txt"),
					work.resolve("new.txt"),
					work.resolve("no/new.txt"),
				),
			)

		assertEquals(Found.Folder, found[work.resolve("Folder")])
		assertEquals(Found.File, found[work.resolve("file.txt")])
		assertEquals(Found.Nothing(inFolder = true), found[work.resolve("new.txt")])
		assertEquals(Found.Nothing(inFolder = false), found[work.resolve("no/new.txt")])
	}

	@Test
	fun aFolderThatCannotBeReadIsSaidSo() {
		assertInstanceOf(
			Listing.Unreadable::class.java,
			listingOf(work.resolve("nowhere")),
		)
	}

	@Test
	fun theUsersOwnFolderNamesAreRead() {
		work.resolve(".config").createDirectory()
		work
			.resolve(
				".config/user-dirs.dirs",
			).writeText("XDG_DOWNLOAD_DIR=\"\$HOME/Téléchargements\"\n")

		assertEquals(
			work.resolve("Téléchargements"),
			XdgPlaces.userDirectories(work)["XDG_DOWNLOAD_DIR"],
		)
		assertEquals(
			work.resolve("Desktop"),
			XdgPlaces.userDirectories(work)["XDG_DESKTOP_DIR"],
		)
	}

	@Test
	fun datesAreWrittenEachCommonWayInTheZoneGiven() {
		val at = Instant.parse("2026-11-08T09:41:00Z")

		assertEquals(
			listOf(
				"8 Nov 2026, 09:41",
				"Nov 8, 2026, 9:41 AM",
				"2026-11-08 09:41",
				"08/11/2026, 09:41",
				"11/08/2026, 9:41 AM",
				"08.11.2026, 09:41",
			),
			DateFormat.entries
				.filter {
					it != DateFormat.System
				}.map { it.write(at, ZoneOffset.UTC) },
		)
	}
}
