package prism.browser

import androidx.compose.runtime.Immutable

/*
 * The picker's menus, worked out from its state: pure functions, so what each offers is a test of values. Each
 * item's action is the event choosing it reports, as if the user had pressed its keys.
 *
 * What a context menu offers depends on what it is a menu of ([Target]): one row or several, a file or a folder, or
 * the folder itself where no row is. New targets (a place in the sidebar, a step of the path) are new cases here.
 */

/** What a context menu is a menu of. */
@Immutable
sealed interface Target {
	/** Rows of the listing: the one pressed, or every one chosen where it was one of them. */
	data class Rows(
		val rows: List<Row>,
	) : Target

	/** The folder shown: the listing pressed where there is no row. */
	data object Folder : Target
}

/** The context menu of [target] in [state]. */
fun contextMenuOf(state: BrowserState, target: Target): Menu<BrowserEvent> =
	when (target) {
		is Target.Rows -> rowsMenuOf(state, target.rows)
		Target.Folder -> folderMenuOf(state)
	}

private fun rowsMenuOf(state: BrowserState, rows: List<Row>): Menu<BrowserEvent> {
	val one = rows.singleOrNull()
	val ids = rows.map { it.id }
	val paths = rows.map { it.path }

	return menu {
		one?.let { row ->
			when {
				row.isFolder -> {
					item("Open", BrowserEvent.Activated(row.id), keys = "Enter")
					// A folder picker gives a folder: this one, without going into it.
					if (state.choosing is Choosing.Folder)
						item("Select folder", BrowserEvent.Picked(row.id))
				}
				state.choosing is Choosing.SaveAs -> item("Save over it", BrowserEvent.Picked(row.id))
				else -> item("Open", BrowserEvent.Picked(row.id), keys = "Enter")
			}
		}
		separator()
		one?.let { item("Rename", BrowserEvent.StartedRenaming(it.id), keys = "F2") }
		item(
			if (one != null) "Copy path" else "Copy paths",
			BrowserEvent.CopiedPaths(paths),
			keys = "Ctrl+Shift+C",
		)
		separator()
		// Chosen from a menu, it is meant: the trash at once, as Shift+Delete does; Delete alone asks first.
		item("Move to trash", BrowserEvent.Trashed(ids), keys = "Delete")
	}
}

private fun folderMenuOf(state: BrowserState): Menu<BrowserEvent> {
	val folders = state.choosing is Choosing.Folder

	return menu {
		state.changes.undo.lastOrNull()?.let {
			item("Undo ${undoneBy(it)}", BrowserEvent.Undone, keys = "Ctrl+Z")
		}
		state.changes.redo.lastOrNull()?.let {
			item("Redo ${doneBy(it)}", BrowserEvent.Redone, keys = "Ctrl+Shift+Z")
		}
		separator()
		submenu("Sort by") { sorts(state) }
		submenu("View") {
			radio(
				"List",
				state.layout == Layout.List,
				BrowserEvent.Viewed(Layout.List),
				keys = "Ctrl+1",
			)
			radio(
				"Gallery",
				state.layout == Layout.Gallery,
				BrowserEvent.Viewed(Layout.Gallery),
				keys = "Ctrl+2",
			)
			separator()
			// Each can be chosen where it would change something: what it would do is asked of the reducer itself.
			item(
				"Larger",
				BrowserEvent.Zoomed(1),
				keys = "Ctrl+=",
				enabled = state.changedBy(BrowserEvent.Zoomed(1)),
			)
			item(
				"Smaller",
				BrowserEvent.Zoomed(-1),
				keys = "Ctrl+-",
				enabled = state.changedBy(BrowserEvent.Zoomed(-1)),
			)
			item(
				"Usual size",
				BrowserEvent.Unzoomed,
				keys = "Ctrl+0",
				enabled = state.changedBy(BrowserEvent.Unzoomed),
			)
		}
		separator()
		hiddenChecks(state, folders)
		separator()
		// A search's results are in no one folder: there is nowhere to make one, and no path to copy.
		state.folder?.let {
			item("New folder", BrowserEvent.MadeFolder, keys = "Ctrl+Shift+N")
			item("Copy path", BrowserEvent.CopiedPaths(listOf(it)))
		}
		item("Refresh", BrowserEvent.Refreshed, keys = "F5")
	}
}

/**
 * The orders the listing can be put in: a folder's by each column, either way; a search's best first as well, and by
 * each column it can be ordered by ([Listing.Found.orders]), the rest shown but not to be chosen.
 */
private fun MenuBuilder<BrowserEvent>.sorts(state: BrowserState) {
	val found = state.listing as? Listing.Found
	val order =
		if (found == null)
			Order.By(state.arrangement.sort)
		else
			state.arrangement.results
	val sort = (order as? Order.By)?.sort

	if (found != null) {
		radio("Best match", order == Order.Relevance, BrowserEvent.SortedByRelevance)
		separator()
	}
	for (heading in Sort.Column.entries)
		radio(
			headingOf(heading),
			sort?.by == heading,
			BrowserEvent.SortedBy(
				sort?.copy(by = heading)
					?: Sort(
						heading,
						ascending =
							heading == Sort.Column.Name || heading == Sort.Column.Type,
					),
			),
			enabled = found?.orders(heading) != false,
		)
	if (sort != null) {
		separator()
		radio("Ascending", sort.ascending, BrowserEvent.SortedBy(sort.copy(ascending = true)))
		radio(
			"Descending",
			!sort.ascending,
			BrowserEvent.SortedBy(sort.copy(ascending = false)),
		)
	}
}

/**
 * The filter menu: the kinds of entry, of which a plain click shows that one alone and a click with Ctrl adds it to
 * those shown or takes it away, then whether hidden files and hidden folders are shown. Every item keeps its place
 * whichever are on: they are a list of marks, not a list that grows.
 */
fun filterMenuOf(state: BrowserState): Menu<BrowserEvent> {
	val kinds = state.arrangement.kinds
	val folders = state.choosing is Choosing.Folder

	return menu {
		// A folder picker shows only folders: there are no kinds to choose among.
		if (!folders) {
			check(
				"All kinds",
				kinds.isEmpty(),
				BrowserEvent.ShowedEveryKind,
				alternate = BrowserEvent.ShowedEveryKind,
			)
			for (kind in Kind.entries)
				check(
					kind.label,
					kind in kinds,
					BrowserEvent.ShowedOnlyKind(kind),
					alternate = BrowserEvent.ToggledKind(kind),
				)
			separator()
		}
		hiddenChecks(state, folders)
	}
}

/** Whether hidden files and hidden folders are shown, each its own; a folder picker has no files to show. */
private fun MenuBuilder<BrowserEvent>.hiddenChecks(
	state: BrowserState,
	folders: Boolean,
) {
	val hidden = state.arrangement.hidden

	if (!folders)
		check(
			"Show hidden files",
			hidden.files,
			BrowserEvent.ToggledHiddenFiles,
			alternate = BrowserEvent.ToggledHiddenFiles,
		)
	check(
		"Show hidden folders",
		hidden.folders,
		BrowserEvent.ToggledHiddenFolders,
		alternate = BrowserEvent.ToggledHiddenFolders,
	)
}

/** What the listing's heading for [column] says. */
fun headingOf(column: Sort.Column): String =
	when (column) {
		Sort.Column.Name -> "Name"
		Sort.Column.Modified -> "Date modified"
		Sort.Column.Type -> "Type"
		Sort.Column.Size -> "Size"
	}

/** Whether [event] would change how the listing is drawn: whether a menu item that does it has anything to do. */
private fun BrowserState.changedBy(event: BrowserEvent): Boolean =
	step(this, event).state.zooms != zooms
