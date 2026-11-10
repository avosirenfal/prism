package prism.browser

import androidx.compose.runtime.Immutable

/*
 * The picker's menus, worked out from its state: pure functions, so what each offers is a test of values. Each
 * item's action is the event choosing it reports, as if the user had pressed its keys.
 *
 * What a context menu offers depends on what it is a menu of ([Target]): one row or several, a file or a folder, the
 * folder itself where no row is, or a place in the sidebar; and on what the browser is for: a picker offers what
 * choosing needs, an explorer what a file manager does. Each item shows the keys its action is bound to.
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

	/** A place in the sidebar. */
	data class Place(
		val place: prism.desktop.Place,
	) : Target
}

/** The context menu of [target] in [state], each item showing the keys [bindings] bind its action to. */
fun contextMenuOf(
	state: BrowserState,
	target: Target,
	bindings: Bindings = Bindings.DEFAULT,
): Menu<BrowserEvent> =
	when (target) {
		is Target.Rows -> rowsMenuOf(state, target.rows, bindings)
		Target.Folder -> folderMenuOf(state, bindings)
		is Target.Place -> placeMenuOf(state, target.place)
	}

/** The first chord [action] is bound to, as a menu shows it; nothing where it is bound to none. */
private fun Bindings.keys(action: Action): String? = of(action).firstOrNull()?.toString()

private fun rowsMenuOf(
	state: BrowserState,
	rows: List<Row>,
	bindings: Bindings,
): Menu<BrowserEvent> {
	val one = rows.singleOrNull()
	val ids = rows.map { it.id }
	val paths = rows.map { it.path }
	val exploring = state.choosing is Choosing.Exploring
	val folder = one?.takeIf { it.isFolder }

	return menu {
		one?.let { row ->
			when {
				row.isFolder -> {
					item("Open", BrowserEvent.Activated(row.id), keys = bindings.keys(Action.Open))
					if (exploring)
						item("Open in new window", BrowserEvent.Requested(Request.NewWindow(row.path)))
					// A folder picker gives a folder: this one, without going into it.
					if (state.choosing is Choosing.Folder)
						item("Select folder", BrowserEvent.Picked(row.id))
				}
				state.choosing is Choosing.SaveAs -> item("Save over it", BrowserEvent.Picked(row.id))
				else -> item("Open", BrowserEvent.Picked(row.id), keys = bindings.keys(Action.Open))
			}
		}
		// Several files open together, each with its application.
		if (exploring && one == null && rows.any { !it.isFolder })
			item("Open", BrowserEvent.Accepted, keys = bindings.keys(Action.Open))
		if (exploring && rows.none { it.isFolder })
			item("Open with…", BrowserEvent.Requested(Request.OpenWith(paths)))
		separator()
		if (exploring) {
			item("Cut", BrowserEvent.CutFiles(ids), keys = bindings.keys(Action.CutFiles))
			item("Copy", BrowserEvent.CopiedFiles(ids), keys = bindings.keys(Action.CopyFiles))
			folder?.let { item("Paste into folder", BrowserEvent.Pasted(it.path)) }
			separator()
		}
		one?.let {
			item(
				"Rename",
				BrowserEvent.StartedRenaming(it.id),
				keys = bindings.keys(Action.Rename),
			)
		}
		item(
			if (one != null) "Copy path" else "Copy paths",
			BrowserEvent.CopiedPaths(paths),
			keys = bindings.keys(Action.CopyPaths),
		)
		folder?.let { pinning(state, it.path) }
		if (exploring)
			folder?.let { item("Open terminal here", BrowserEvent.OpenedTerminal(it.path)) }
		// The trash apart from the rest, last, where it is not pressed in passing. Chosen from a menu, it is meant: the
		// trash at once, as Shift+Delete does; Delete alone asks first.
		separator()
		item("Move to trash", BrowserEvent.Trashed(ids), keys = bindings.keys(Action.Trash))
		if (exploring) {
			separator()
			item(
				"Properties",
				BrowserEvent.Requested(Request.Properties(paths)),
				keys = bindings.keys(Action.Properties),
			)
		}
	}
}

/** Pinning [path] to the sidebar, or taking it off where it is pinned. */
private fun MenuBuilder<BrowserEvent>.pinning(
	state: BrowserState,
	path: java.nio.file.Path,
) {
	if (state.sidebar.pins(path))
		item("Unpin from sidebar", BrowserEvent.Unpinned(path))
	else
		item("Pin to sidebar", BrowserEvent.Pinned(path))
}

private fun folderMenuOf(state: BrowserState, bindings: Bindings): Menu<BrowserEvent> {
	val folders = state.choosing is Choosing.Folder
	val exploring = state.choosing is Choosing.Exploring

	return menu {
		state.changes.undo.lastOrNull()?.let {
			item("Undo ${doneBy(it.done)}", BrowserEvent.Undone, keys = bindings.keys(Action.Undo))
		}
		state.changes.redo.lastOrNull()?.let {
			item("Redo ${doneBy(it.done)}", BrowserEvent.Redone, keys = bindings.keys(Action.Redo))
		}
		separator()
		if (exploring && state.folder != null) {
			item("Paste", BrowserEvent.Pasted(), keys = bindings.keys(Action.Paste))
			separator()
		}
		submenu("Sort by") { sorts(state) }
		submenu("View") {
			radio(
				"List",
				state.layout == Layout.List,
				BrowserEvent.Viewed(Layout.List),
				keys = bindings.keys(Action.ViewList),
			)
			radio(
				"Gallery",
				state.layout == Layout.Gallery,
				BrowserEvent.Viewed(Layout.Gallery),
				keys = bindings.keys(Action.ViewGallery),
			)
			separator()
			// Each can be chosen where it would change something: what it would do is asked of the reducer itself.
			item(
				"Larger",
				BrowserEvent.Zoomed(1),
				keys = bindings.keys(Action.ZoomIn),
				enabled = state.changedBy(BrowserEvent.Zoomed(1)),
			)
			item(
				"Smaller",
				BrowserEvent.Zoomed(-1),
				keys = bindings.keys(Action.ZoomOut),
				enabled = state.changedBy(BrowserEvent.Zoomed(-1)),
			)
			item(
				"Usual size",
				BrowserEvent.Unzoomed,
				keys = bindings.keys(Action.ZoomReset),
				enabled = state.changedBy(BrowserEvent.Unzoomed),
			)
		}
		separator()
		hiddenChecks(state, folders)
		separator()
		// A search's results are in no one folder: there is nowhere to make one, and no path to copy.
		state.folder?.let {
			item("New folder", BrowserEvent.MadeFolder, keys = bindings.keys(Action.NewFolder))
			if (exploring)
				item("New file", BrowserEvent.MadeFile, keys = bindings.keys(Action.NewFile))
			item("Copy path", BrowserEvent.CopiedPaths(listOf(it)))
			pinning(state, it)
			if (exploring)
				item(
					"Open terminal here",
					BrowserEvent.OpenedTerminal(it),
					keys = bindings.keys(Action.OpenTerminal),
				)
		}
		item("Refresh", BrowserEvent.Refreshed, keys = bindings.keys(Action.Refresh))
		if (exploring)
			state.folder?.let {
				separator()
				item("Properties", BrowserEvent.Requested(Request.Properties(listOf(it))))
			}
	}
}

/**
 * An explorer's own menu, behind its toolbar's button: another window, a terminal here, the preferences, and closing
 * the window, which a window with no title bar has no other button for.
 */
fun appMenuOf(
	state: BrowserState,
	bindings: Bindings = Bindings.DEFAULT,
): Menu<BrowserEvent> =
	menu {
		item(
			"New window",
			BrowserEvent.Requested(Request.NewWindow(state.folder ?: state.home)),
			keys = bindings.keys(Action.NewWindow),
		)
		state.folder?.let {
			item(
				"Open terminal here",
				BrowserEvent.OpenedTerminal(it),
				keys = bindings.keys(Action.OpenTerminal),
			)
		}
		separator()
		item(
			"Preferences…",
			BrowserEvent.Requested(Request.Preferences),
			keys = bindings.keys(Action.Preferences),
		)
		item("Close window", BrowserEvent.Cancelled, keys = bindings.keys(Action.CloseWindow))
	}

/** A sidebar place's menu: open it, here or in a window of its own, and unpin it where it is pinned. */
private fun placeMenuOf(
	state: BrowserState,
	place: prism.desktop.Place,
): Menu<BrowserEvent> =
	menu {
		item("Open", BrowserEvent.Opened(place.path))
		if (state.choosing is Choosing.Exploring) {
			item("Open in new window", BrowserEvent.Requested(Request.NewWindow(place.path)))
			separator()
			item("Properties", BrowserEvent.Requested(Request.Properties(listOf(place.path))))
		}
		if (state.sidebar.pinned.contains(place)) {
			separator()
			item("Unpin from sidebar", BrowserEvent.Unpinned(place.path))
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
