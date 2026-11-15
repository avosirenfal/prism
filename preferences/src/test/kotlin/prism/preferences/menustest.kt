package prism.preferences

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.shared.Loaded
import prism.shared.orElse

/** How the context menus are arranged: as the file writes it, read back; and which arrangement a menu follows. */
class MenusTest {
	@TempDir
	lateinit var folder: Path

	private val file get() = folder.resolve("prism/preferences.json")

	@Test
	fun eachPlaceInAMenuIsALineTheFileReadsBack() {
		val placed =
			listOf(
				Placed.Slot("builtin.undo"),
				Placed.Separator,
				Placed.Slot("builtin.refresh", Show.With(setOf(Held.Shift))),
				Placed.Slot("builtin.pin", Show.With(setOf(Held.Alt, Held.Ctrl))),
				Placed.Slot("archive.extract", Show.Never),
				Placed.Slot("submenu:Compress"),
			)

		assertEquals(
			listOf(
				"builtin.undo",
				"-",
				"builtin.refresh with Shift",
				"builtin.pin with Ctrl+Alt",
				"archive.extract hidden",
				"submenu:Compress",
			),
			placed.map(PlacedSerializer::textOf),
		)
		// Written by hand, keys in any case and any order, and a long dash for a line.
		assertEquals(
			Placed.Slot("builtin.pin", Show.With(setOf(Held.Ctrl, Held.Alt))),
			PlacedSerializer.placedOf("builtin.pin with alt+CTRL"),
		)
		assertEquals(Placed.Separator, PlacedSerializer.placedOf("—"))
		assertNull(PlacedSerializer.placedOf("builtin.pin with Hyper"))

		val set =
			Preferences.DEFAULT.copy(
				menu =
					MenuSpec()
						.arranging(MenuKey.Listing(ListingKind.Folder, MenuOf.Space), placed)
						.arranging(MenuKey.Sidebar, listOf(Placed.Slot("builtin.open"))),
			)

		PreferenceFile.open(file).update { set }

		assertEquals(Loaded.Read(set), PreferenceFile.read(file))
		assertTrue(file.readText().contains("\"folder/space\""), file.readText())
		assertTrue(file.readText().contains("\"builtin.refresh with Shift\""), file.readText())
	}

	@Test
	fun aMenuArrangedForFoldersIsEveryListingsUntilOneIsArrangedItself() {
		val folders = listOf(Placed.Slot("builtin.open"), Placed.Slot("builtin.copy"))
		val trash = listOf(Placed.Slot("builtin.put-back"))
		val menus =
			MenuSpec()
				.arranging(MenuKey.Listing(ListingKind.Folder, MenuOf.File), folders)
				.arranging(MenuKey.Listing(ListingKind.Trash, MenuOf.File), trash)

		assertEquals(
			folders,
			menus.arrangementOf(MenuKey.Listing(ListingKind.Results, MenuOf.File)),
		)
		assertEquals(
			folders,
			menus.arrangementOf(MenuKey.Listing(ListingKind.Place("archive"), MenuOf.File)),
		)
		assertEquals(
			trash,
			menus.arrangementOf(MenuKey.Listing(ListingKind.Trash, MenuOf.File)),
		)
		assertNull(menus.arrangementOf(MenuKey.Listing(ListingKind.Results, MenuOf.Space)))
		assertNull(menus.arrangementOf(MenuKey.Sidebar))
		assertFalse(menus.arrangesItself(MenuKey.Listing(ListingKind.Results, MenuOf.File)))
		assertEquals(
			folders,
			menus
				.arranging(MenuKey.Listing(ListingKind.Trash, MenuOf.File), null)
				.arrangementOf(MenuKey.Listing(ListingKind.Trash, MenuOf.File)),
		)
		assertEquals(
			MenuKey.Listing(ListingKind.Place("archive.7z"), MenuOf.Several),
			MenuKey.of("place:archive.7z/several"),
		)
	}

	@Test
	fun aMenuThatCannotBeReadIsSaidNotToBe() {
		file.parent.createDirectories()
		file.writeText(
			"""{"menu": {"arranged": {"folder/space": ["builtin.pin with Hyper"]}}}""",
		)

		assertTrue(
			PreferenceFile.read(file) is Loaded.Unreadable,
			"${PreferenceFile.read(file)}",
		)
	}
}
