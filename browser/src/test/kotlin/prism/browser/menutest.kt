package prism.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.Fixtures.home
import prism.browser.Fixtures.picker
import prism.preferences.ActionsFile
import prism.preferences.ColumnId
import prism.preferences.Held
import prism.preferences.Hidden
import prism.preferences.Kind
import prism.preferences.Layout
import prism.preferences.ListingKind
import prism.preferences.MenuKey
import prism.preferences.MenuOf
import prism.preferences.MenuSpec
import prism.preferences.Placed
import prism.preferences.Show
import prism.preferences.Sort

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

	private fun rowsOf(state: BrowserState, vararg names: String) =
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
		assertEquals(BrowserEvent.Picked(RowId("notes.md")), menu.item("Open").action)
		assertEquals(
			BrowserEvent.StartedRenaming(RowId("notes.md")),
			menu.item("Rename").action,
		)
		assertEquals(
			BrowserEvent.CopiedPaths(listOf(home.resolve("notes.md"))),
			menu.item("Copy path").action,
		)
		assertEquals("F6", menu.item("Rename").keys)
		// From a menu, the trash at once: only the Delete key alone asks first.
		assertEquals(
			BrowserEvent.Trashed(listOf(RowId("notes.md"))),
			menu.item("Move to trash").action,
		)
	}

	@Test
	fun aFoldersMenuGoesIntoItAndAFolderPickerCanGiveIt() {
		val state = picker()

		assertEquals(
			BrowserEvent.Activated(RowId("Music")),
			contextMenuOf(state, rowsOf(state, "Music")).item("Open").action,
		)

		val folders = picker(Choosing.Folder())
		val menu = contextMenuOf(folders, rowsOf(folders, "Music"))

		assertEquals(listOf("Open", "Select folder"), menu.labels().take(2))
		assertEquals(BrowserEvent.Picked(RowId("Music")), menu.item("Select folder").action)
	}

	@Test
	fun savingAFilesMenuSavesOverIt() {
		val state = picker(Choosing.SaveAs("draft.txt"))

		assertEquals(
			"Replace",
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
			BrowserEvent.Trashed(listOf(RowId("main.kt"), RowId("todo.txt"))),
			menu.item("Move to trash").action,
		)
	}

	@Test
	fun theFoldersOwnMenuSortsViewsShowsHiddenAndMakesAFolder() {
		val state = picker()

		// What is rarely wanted is out of the way until Shift is held as the menu opens.
		assertEquals(
			listOf("Sort by ▸", "View ▸", "—", "New folder", "Copy path"),
			contextMenuOf(state, Target.Folder).labels(),
		)

		val menu = contextMenuOf(state, Target.Folder, held = setOf(Held.Shift))

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
				// The home folder is one of prism's own in quick access: unpinned, it is hidden, never removed.
				"Unpin from sidebar",
				"Refresh",
			),
			menu.labels(),
		)

		val sort = menu.submenu("Sort by")

		assertEquals(
			listOf(
				"Name",
				"Date modified",
				"Date created",
				"Type",
				"Size",
				"Extension",
				"—",
				"Ascending",
				"Descending",
			),
			sort.labels(),
		)
		assertEquals(Mark.Radio(true), sort.item("Name").mark)
		assertEquals(Mark.Radio(false), sort.item("Size").mark)
		assertEquals(
			BrowserEvent.SortedBy(Sort(ColumnId.Size, ascending = true)),
			sort.item("Size").action,
		)

		// At its usual size, the view can be made neither "usual" again nor smaller than smallest.
		val view =
			contextMenuOf(
				after(state, BrowserEvent.Zoomed(-10)),
				Target.Folder,
			).submenu("View")

		assertFalse(view.item("Zoom out").enabled)
		assertEquals(true, view.item("Reset zoom").enabled)
	}

	@Test
	fun theFoldersMenuUndoesAndRedoesWhatThePickerChanged() {
		val rename = Change.Rename(home.resolve("notes.md"), home.resolve("journal.md"))
		val back = Change.Rename(home.resolve("journal.md"), home.resolve("notes.md"))
		val trash = Change.Trash(listOf(home.resolve("main.kt")))

		val menu =
			contextMenuOf(
				picker().copy(
					changes =
						Changes(
							undo = listOf(Undoable(rename, back)),
							redo = listOf(Undoable(trash, trash)),
						),
				),
				Target.Folder,
			)

		assertEquals(
			listOf("Undo rename", "Redo move to trash", "—"),
			menu.labels().take(3),
		)
		assertEquals(BrowserEvent.Undone, menu.item("Undo rename").action)
		assertEquals("Ctrl+Z", menu.item("Undo rename").keys)
		// With nothing done, neither is there.
		assertEquals("Sort by ▸", contextMenuOf(picker(), Target.Folder).labels().first())
	}

	@Test
	fun theGallerysLargestIsOneTileAtATime() {
		val whole =
			after(
				picker(),
				BrowserEvent.Viewed(Layout.Gallery),
				BrowserEvent.Measured(4.2f),
				BrowserEvent.Zoomed(100),
			)

		val view = contextMenuOf(whole, Target.Folder).submenu("View")

		assertFalse(view.item("Zoom in").enabled)
		assertEquals(true, view.item("Zoom out").enabled)
	}

	@Test
	fun theFilterMenuChoosesOneKindAndWithCtrlMore() {
		val state = picker(hidden = Hidden())
		val menu = filterMenuOf(state)

		assertEquals(
			listOf("All types") + Kind.entries.map { it.label } +
				listOf("—", "Show hidden files", "Show hidden folders"),
			menu.labels(),
		)
		assertEquals(Mark.Check(true), menu.item("All types").mark)
		assertEquals(Mark.Check(false), menu.item("Images").mark)
		assertEquals(BrowserEvent.ShowedOnlyKind(Kind.Images), menu.item("Images").action)
		assertEquals(BrowserEvent.ToggledKind(Kind.Images), menu.item("Images").alternate)
		// Hidden files are shown at first, hidden folders not.
		assertEquals(Mark.Check(true), menu.item("Show hidden files").mark)
		assertEquals(Mark.Check(false), menu.item("Show hidden folders").mark)

		val images = filterMenuOf(after(state, BrowserEvent.ShowedOnlyKind(Kind.Images)))

		assertEquals(Mark.Check(true), images.item("Images").mark)
		assertEquals(Mark.Check(false), images.item("All types").mark)
	}

	@Test
	fun aFolderPickersFilterMenuHasOnlyHiddenFolders() {
		assertEquals(
			listOf("Show hidden folders"),
			filterMenuOf(picker(Choosing.Folder())).labels(),
		)
	}

	/**
	 * An explorer whose user wrote an action of their own for the folder's menu and two in a submenu, its menus
	 * arranged as [menus] says.
	 */
	private fun arrangedExplorer(menus: MenuSpec): BrowserState =
		picker(Choosing.Exploring()).copy(
			actions =
				Actions.read(
					listOf(
						ActionsFile.parse(
							"""{"actions": [
								{"id": "mine.mail", "label": "Mail it", "when": {"target": "folder"}, "run": ["mail", "{folder}"]},
								{"id": "share.web", "label": "To the web", "submenu": "Share", "when": {"target": "folder"}, "run": ["up", "{folder}"]},
								{"id": "share.phone", "label": "To the phone", "submenu": "Share", "when": {"target": "folder"}, "run": ["kde", "{folder}"]}
							]}""",
						),
					),
				),
			menus = menus,
		)

	@Test
	fun aMenuIsAsItsArrangementSays_AndWhatItDoesNotNameGoesWherePrismWouldPutIt() {
		val key = MenuKey.Listing(ListingKind.Folder, MenuOf.Space)

		// Copy path first, then a line, then New folder; Sort by never.
		val state =
			arrangedExplorer(
				MenuSpec().arranging(
					key,
					listOf(
						Placed.Slot("builtin.copy-path"),
						Placed.Separator,
						Placed.Slot("builtin.new-folder"),
						Placed.Slot("builtin.sort", Show.Never),
					),
				),
			)

		// What it does not name goes after the nearest before it in prism's: Paste and the actions before Copy path;
		// pinning, the terminal and the properties after it; New file after New folder, and View after Sort by.
		assertEquals(
			listOf(
				"Paste",
				"Mail it",
				"Share ▸",
				"Copy path",
				"Open terminal here",
				"Properties",
				"—",
				"New folder",
				"New file",
				"View ▸",
			),
			contextMenuOf(state, Target.Folder).labels(),
		)
		// With Shift held, what prism keeps for it too: in their places, as prism's defaults show them.
		assertEquals(
			listOf("Unpin from sidebar", "Refresh"),
			contextMenuOf(state, Target.Folder, held = setOf(Held.Shift)).labels() -
				contextMenuOf(state, Target.Folder).labels().toSet() -
				setOf("Show hidden files", "Show hidden folders"),
		)
	}

	@Test
	fun anEntryShownWithKeysHeldNeedsEveryOne_AndASubmenusItemIsArrangedByItsOwnId() {
		val key = MenuKey.Listing(ListingKind.Folder, MenuOf.Space)

		val state =
			arrangedExplorer(
				MenuSpec().arranging(
					key,
					listOf(
						Placed.Slot("mine.mail", Show.With(setOf(Held.Ctrl, Held.Alt))),
						Placed.Slot(submenuId("Share")),
						Placed.Slot("share.phone", Show.Never),
					),
				),
			)

		fun has(vararg held: Held) =
			"Mail it" in contextMenuOf(state, Target.Folder, held = held.toSet()).labels()

		assertFalse(has())
		assertFalse(has(Held.Ctrl))
		assertTrue(has(Held.Ctrl, Held.Alt))
		assertTrue(has(Held.Ctrl, Held.Alt, Held.Shift))
		// The submenu is where it was put; its item never shown is not in it.
		assertEquals(
			listOf("To the web"),
			contextMenuOf(state, Target.Folder).submenu("Share").labels(),
		)
	}

	@Test
	fun aSubmenusItemsAreInTheArrangementsOrder() {
		val key = MenuKey.Listing(ListingKind.Folder, MenuOf.Space)

		val state =
			arrangedExplorer(
				MenuSpec().arranging(
					key,
					listOf(
						Placed.Slot(submenuId("Share")),
						Placed.Slot("share.phone"),
						Placed.Slot("share.web"),
					),
				),
			)

		// The actions are written web first; arranged, the phone is.
		assertEquals(
			listOf("To the phone", "To the web"),
			contextMenuOf(state, Target.Folder).submenu("Share").labels(),
		)
		// Not arranged, they are as written.
		assertEquals(
			listOf("To the web", "To the phone"),
			contextMenuOf(arrangedExplorer(MenuSpec()), Target.Folder).submenu("Share").labels(),
		)
	}

	@Test
	fun eachMenuIsArrangedItself_AFilesNotAFolders() {
		val files = MenuKey.Listing(ListingKind.Folder, MenuOf.File)

		val state =
			arrangedExplorer(
				MenuSpec().arranging(
					files,
					listOf(
						Placed.Slot("builtin.properties"),
						Placed.Separator,
						Placed.Slot("builtin.open"),
					),
				),
			)

		assertEquals(files, state.menuKeyOf(rowsOf(state, "notes.md")))
		assertEquals(
			MenuKey.Listing(ListingKind.Folder, MenuOf.Folder),
			state.menuKeyOf(rowsOf(state, "Documents")),
		)
		assertEquals(
			MenuKey.Listing(ListingKind.Folder, MenuOf.Several),
			state.menuKeyOf(rowsOf(state, "notes.md", "todo.txt")),
		)
		assertEquals(
			MenuKey.Listing(ListingKind.Folder, MenuOf.Space),
			state.menuKeyOf(Target.Folder),
		)
		assertEquals(
			listOf("Properties", "—", "Open"),
			contextMenuOf(state, rowsOf(state, "notes.md")).labels().take(3),
		)
		assertEquals("Open", contextMenuOf(state, rowsOf(state, "Documents")).labels().first())
	}
}
