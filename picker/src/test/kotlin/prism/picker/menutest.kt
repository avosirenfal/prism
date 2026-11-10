package prism.picker

import prism.picker.Fixtures.home
import prism.picker.Fixtures.picker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** The menus, as values: what each target's menu offers, what each item does, and what it shows as on. */
class MenuTest {
	/** A menu as a line of words: a submenu with an arrow, a separator as a dash. */
	private fun Menu<*>.labels(): List<String> =
		entries.map {
			when (it) {
				is MenuEntry.Item -> it.label
				is MenuEntry.Submenu -> "${it.label} ▸"
				MenuEntry.Separator -> "—"
			}
		}

	private fun <A> Menu<A>.item(label: String): MenuEntry.Item<A> =
		entries.filterIsInstance<MenuEntry.Item<A>>().single { it.label == label }

	private fun <A> Menu<A>.submenu(label: String): Menu<A> =
		entries.filterIsInstance<MenuEntry.Submenu<A>>().single { it.label == label }.menu

	private fun rowsOf(state: PickerState, vararg names: String) =
		Target.Rows(
			names.map { name ->
				state.rows.list.single {
					it.name ==
						name
				}
			},
		)

	@Test
	fun separatorsAreKeptOnlyBetweenGroups() {
		val built =
			menu<Int> {
				separator()
				item("a", 1)
				separator()
				separator()
				submenu("nothing") {}
				separator()
				item("b", 2)
				separator()
			}

		assertEquals(listOf("a", "—", "b"), built.labels())
	}

	@Test
	fun aFilesMenuOpensRenamesCopiesAndDeletes() {
		val state = picker()
		val menu = contextMenuOf(state, rowsOf(state, "notes.md"))

		assertEquals(
			listOf(
				"Open",
				"—",
				"Rename",
				"Copy path",
				"—",
				"Move to trash",
			),
			menu.labels(),
		)
		assertEquals(PickerEvent.Picked(RowId("notes.md")), menu.item("Open").action)
		assertEquals(PickerEvent.StartedRenaming(RowId("notes.md")), menu.item("Rename").action)
		assertEquals(
			PickerEvent.CopiedPaths(listOf(home.resolve("notes.md"))),
			menu.item("Copy path").action,
		)
		assertEquals("F2", menu.item("Rename").keys)
		// From a menu, the trash at once: only the Delete key alone asks first.
		assertEquals(
			PickerEvent.Trashed(listOf(RowId("notes.md"))),
			menu.item("Move to trash").action,
		)
	}

	@Test
	fun aFoldersMenuGoesIntoItAndAFolderPickerCanGiveIt() {
		val state = picker()

		assertEquals(
			PickerEvent.Activated(RowId("Music")),
			contextMenuOf(state, rowsOf(state, "Music")).item("Open").action,
		)

		val folders = picker(Choosing.Folder())
		val menu = contextMenuOf(folders, rowsOf(folders, "Music"))

		assertEquals(listOf("Open", "Select folder"), menu.labels().take(2))
		assertEquals(PickerEvent.Picked(RowId("Music")), menu.item("Select folder").action)
	}

	@Test
	fun savingAFilesMenuSavesOverIt() {
		val state = picker(Choosing.SaveAs("draft.txt"))

		assertEquals(
			"Save over it",
			contextMenuOf(state, rowsOf(state, "notes.md")).labels().first(),
		)
	}

	@Test
	fun severalRowsHaveNothingThatTakesOne() {
		val state = picker()
		val menu = contextMenuOf(state, rowsOf(state, "main.kt", "todo.txt"))

		assertEquals(
			listOf("Copy paths", "—", "Move to trash"),
			menu.labels(),
		)
		assertEquals(
			PickerEvent.Trashed(listOf(RowId("main.kt"), RowId("todo.txt"))),
			menu.item("Move to trash").action,
		)
	}

	@Test
	fun theFoldersOwnMenuSortsViewsShowsHiddenAndMakesAFolder() {
		val state = picker()
		val menu = contextMenuOf(state, Target.Folder)

		assertEquals(
			listOf(
				"Sort by ▸",
				"View ▸",
				"—",
				"Show hidden files",
				"Show hidden folders",
				"—",
				"New folder",
				"Copy path",
				"Refresh",
			),
			menu.labels(),
		)

		val sort = menu.submenu("Sort by")

		assertEquals(
			listOf("Name", "Date modified", "Type", "Size", "—", "Ascending", "Descending"),
			sort.labels(),
		)
		assertEquals(Mark.Radio(true), sort.item("Name").mark)
		assertEquals(Mark.Radio(false), sort.item("Size").mark)
		assertEquals(
			PickerEvent.SortedBy(Sort(Sort.Column.Size, ascending = true)),
			sort.item("Size").action,
		)

		// At its usual size, the view can be made neither "usual" again nor smaller than smallest.
		val view =
			contextMenuOf(
				after(state, PickerEvent.Zoomed(-10)),
				Target.Folder,
			).submenu("View")

		assertFalse(view.item("Smaller").enabled)
		assertEquals(true, view.item("Usual size").enabled)
	}

	@Test
	fun theFoldersMenuUndoesAndRedoesWhatThePickerChanged() {
		val back = Change.Rename(home.resolve("journal.md"), home.resolve("notes.md"))
		val again = Change.Trash(listOf(home.resolve("main.kt")))

		val menu =
			contextMenuOf(
				picker().copy(changes = Changes(undo = listOf(back), redo = listOf(again))),
				Target.Folder,
			)

		assertEquals(
			listOf("Undo the rename", "Redo the move to the trash", "—"),
			menu.labels().take(3),
		)
		assertEquals(PickerEvent.Undone, menu.item("Undo the rename").action)
		assertEquals("Ctrl+Z", menu.item("Undo the rename").keys)
		// With nothing done, neither is there.
		assertEquals("Sort by ▸", contextMenuOf(picker(), Target.Folder).labels().first())
	}

	@Test
	fun theGallerysLargestIsOneTileAtATime() {
		val whole =
			after(
				picker(),
				PickerEvent.Viewed(Layout.Gallery),
				PickerEvent.Measured(4.2f),
				PickerEvent.Zoomed(100),
			)

		val view = contextMenuOf(whole, Target.Folder).submenu("View")

		assertFalse(view.item("Larger").enabled)
		assertEquals(true, view.item("Smaller").enabled)
	}

	@Test
	fun theFilterMenuChoosesOneKindAndWithCtrlMore() {
		val state = picker(hidden = Hidden())
		val menu = filterMenuOf(state)

		assertEquals(
			listOf("All kinds") + Kind.entries.map { it.label } +
				listOf("—", "Show hidden files", "Show hidden folders"),
			menu.labels(),
		)
		assertEquals(Mark.Check(true), menu.item("All kinds").mark)
		assertEquals(Mark.Check(false), menu.item("Images").mark)
		assertEquals(PickerEvent.ShowedOnlyKind(Kind.Images), menu.item("Images").action)
		assertEquals(PickerEvent.ToggledKind(Kind.Images), menu.item("Images").alternate)
		// Hidden files are shown at first, hidden folders not.
		assertEquals(Mark.Check(true), menu.item("Show hidden files").mark)
		assertEquals(Mark.Check(false), menu.item("Show hidden folders").mark)

		val images = filterMenuOf(after(state, PickerEvent.ShowedOnlyKind(Kind.Images)))

		assertEquals(Mark.Check(true), images.item("Images").mark)
		assertEquals(Mark.Check(false), images.item("All kinds").mark)
	}

	@Test
	fun aFolderPickersFilterMenuHasOnlyHiddenFolders() {
		assertEquals(
			listOf("Show hidden folders"),
			filterMenuOf(picker(Choosing.Folder())).labels(),
		)
	}
}
