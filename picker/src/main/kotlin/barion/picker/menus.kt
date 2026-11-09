package barion.picker

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
fun contextMenuOf(state: PickerState, target: Target): Menu<PickerEvent> =
	when (target) {
		is Target.Rows -> rowsMenuOf(state, target.rows)
		Target.Folder -> folderMenuOf(state)
	}

private fun rowsMenuOf(state: PickerState, rows: List<Row>): Menu<PickerEvent> {
	val one = rows.singleOrNull()
	val ids = rows.map { it.id }
	val paths = rows.map { state.folder.resolve(it.name) }

	return menu {
		one?.let { row ->
			when {
				row.isFolder -> {
					item("Open", PickerEvent.Activated(row.id), keys = "Enter")
					// A folder picker gives a folder: this one, without going into it.
					if (state.choosing is Choosing.Folder)
						item("Select folder", PickerEvent.Picked(row.id))
				}
				state.choosing is Choosing.SaveAs -> item("Save over it", PickerEvent.Picked(row.id))
				else -> item("Open", PickerEvent.Picked(row.id), keys = "Enter")
			}
		}
		separator()
		one?.let { item("Rename", PickerEvent.StartedRenaming(it.id), keys = "F2") }
		item(
			if (one != null) "Copy path" else "Copy paths",
			PickerEvent.CopiedPaths(paths),
			keys = "Ctrl+Shift+C",
		)
		separator()
		// Chosen from a menu, it is meant: the trash at once, as Shift+Delete does; Delete alone asks first.
		item("Move to trash", PickerEvent.Trashed(ids), keys = "Delete")
	}
}

private fun folderMenuOf(state: PickerState): Menu<PickerEvent> {
	val sort = state.arrangement.sort
	val folders = state.choosing is Choosing.Folder

	return menu {
		state.changes.undo.lastOrNull()?.let {
			item("Undo ${undoneBy(it)}", PickerEvent.Undone, keys = "Ctrl+Z")
		}
		state.changes.redo.lastOrNull()?.let {
			item("Redo ${doneBy(it)}", PickerEvent.Redone, keys = "Ctrl+Shift+Z")
		}
		separator()
		submenu("Sort by") {
			for (heading in Sort.Column.entries)
				radio(
					headingOf(heading),
					sort.by == heading,
					PickerEvent.SortedBy(sort.copy(by = heading)),
				)
			separator()
			radio("Ascending", sort.ascending, PickerEvent.SortedBy(sort.copy(ascending = true)))
			radio(
				"Descending",
				!sort.ascending,
				PickerEvent.SortedBy(sort.copy(ascending = false)),
			)
		}
		submenu("View") {
			radio(
				"List",
				state.layout == Layout.List,
				PickerEvent.Viewed(Layout.List),
				keys = "Ctrl+1",
			)
			radio(
				"Gallery",
				state.layout == Layout.Gallery,
				PickerEvent.Viewed(Layout.Gallery),
				keys = "Ctrl+2",
			)
			separator()
			// Each can be chosen where it would change something: what it would do is asked of the reducer itself.
			item(
				"Larger",
				PickerEvent.Zoomed(1),
				keys = "Ctrl+=",
				enabled = state.changedBy(PickerEvent.Zoomed(1)),
			)
			item(
				"Smaller",
				PickerEvent.Zoomed(-1),
				keys = "Ctrl+-",
				enabled = state.changedBy(PickerEvent.Zoomed(-1)),
			)
			item(
				"Usual size",
				PickerEvent.Unzoomed,
				keys = "Ctrl+0",
				enabled = state.changedBy(PickerEvent.Unzoomed),
			)
		}
		separator()
		hiddenChecks(state, folders)
		separator()
		item("New folder", PickerEvent.MadeFolder, keys = "Ctrl+Shift+N")
		item("Copy path", PickerEvent.CopiedPaths(listOf(state.folder)))
		item("Refresh", PickerEvent.Refreshed, keys = "F5")
	}
}

/**
 * The filter menu: the kinds of entry, of which a plain click shows that one alone and a click with Ctrl adds it to
 * those shown or takes it away, then whether hidden files and hidden folders are shown. Every item keeps its place
 * whichever are on: they are a list of marks, not a list that grows.
 */
fun filterMenuOf(state: PickerState): Menu<PickerEvent> {
	val kinds = state.arrangement.kinds
	val folders = state.choosing is Choosing.Folder

	return menu {
		// A folder picker shows only folders: there are no kinds to choose among.
		if (!folders) {
			check(
				"All kinds",
				kinds.isEmpty(),
				PickerEvent.ShowedEveryKind,
				alternate = PickerEvent.ShowedEveryKind,
			)
			for (kind in Kind.entries)
				check(
					kind.label,
					kind in kinds,
					PickerEvent.ShowedOnlyKind(kind),
					alternate = PickerEvent.ToggledKind(kind),
				)
			separator()
		}
		hiddenChecks(state, folders)
	}
}

/** Whether hidden files and hidden folders are shown, each its own; a folder picker has no files to show. */
private fun MenuBuilder<PickerEvent>.hiddenChecks(state: PickerState, folders: Boolean) {
	val hidden = state.arrangement.hidden

	if (!folders)
		check(
			"Show hidden files",
			hidden.files,
			PickerEvent.ToggledHiddenFiles,
			alternate = PickerEvent.ToggledHiddenFiles,
		)
	check(
		"Show hidden folders",
		hidden.folders,
		PickerEvent.ToggledHiddenFolders,
		alternate = PickerEvent.ToggledHiddenFolders,
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
private fun PickerState.changedBy(event: PickerEvent): Boolean =
	step(this, event).state.zooms != zooms
