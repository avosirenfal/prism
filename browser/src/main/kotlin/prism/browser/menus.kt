package prism.browser

import androidx.compose.runtime.Immutable
import java.nio.file.Path
import prism.desktop.TerminalChoice
import prism.preferences.ColumnId
import prism.preferences.Held
import prism.preferences.Kind
import prism.preferences.Layout
import prism.preferences.MenuKey
import prism.preferences.MenuOf
import prism.preferences.Order
import prism.preferences.Sort

/*
 * The picker's menus, worked out from its state: pure functions, so what each offers is a test of values. Each
 * item's action is the event choosing it reports, as if the user had pressed its keys.
 *
 * What a context menu offers depends on what it is a menu of ([Target]): one row or several, a file or a folder, the
 * folder itself where no row is, or a place in the sidebar; and on what the browser is for: a picker offers what
 * choosing needs, an explorer what a file manager does. Each item shows the keys its action is bound to.
 *
 * An explorer's menus offer the user's own actions too (actions.kt), among prism's: each where its conditions hold for
 * what the menu is of, in the submenu it names. What prism does itself is done to files on disk, so an entry of a
 * place, which is nowhere on disk, is offered none of it but opening and copying, and whatever its place's actions do.
 * Every entry of prism's own has an id (`builtin.rename`), by which the preferences can hide it, as they can an action.
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

	/** Something the sidebar shows. */
	data class Sidebar(
		val item: SidebarItem,
	) : Target
}

/**
 * The context menu of [target] in [state], each item showing the keys [bindings] bind its action to; the user's
 * actions are those whose conditions hold in [world]. Its entries are arranged as the preferences arrange that menu
 * (arranged.kt), each shown as they say with [held] held as the menu opened.
 */
fun contextMenuOf(
	state: BrowserState,
	target: Target,
	bindings: Bindings = Bindings.DEFAULT,
	world: World = World.Bare(state.home),
	held: Set<Held> = emptySet(),
): Menu<BrowserEvent> {
	val subject = state.subjectOf(target)
	// A picker chooses for another application: what the user's actions do to files is an explorer's to offer. The
	// trash's rows only go back or go for good, and the trash in the sidebar is opened or emptied.
	val acting =
		state.choosing is Choosing.Exploring &&
			when (target) {
				is Target.Rows -> target.rows.any { it.item !is Item.InTrash }
				Target.Folder -> true
				is Target.Sidebar -> target.item.path != null
			}

	val offered =
		if (acting)
			state.actions.offered(subject, world)
		else
			emptyList()

	// The action that opens what was pressed, in place of its application: "Open" does it, and it is not offered twice.
	val opener =
		offered.firstOrNull { it.default }?.takeIf {
			(target as? Target.Rows)?.rows?.size ==
				1
		}
	val own = ownActions(offered - setOfNotNull(opener), subject, bindings, state.terminal)
	val key = state.menuKeyOf(target)
	val slots =
		when (target) {
			is Target.Rows ->
				rowsSlots(state, target.rows, bindings, opener?.let { BrowserEvent.Did(it, subject) })
			Target.Folder -> spaceSlots(state, bindings)
			is Target.Sidebar -> sidebarSlots(state, target.item)
		} + own.associate { slotIdOf(it) to listOf(it) }

	return assembled(
		slots,
		arranged(state.menus.arrangementOf(key), defaultOf(key), own.map(::slotIdOf)),
		held,
	).without(state.actions.hidden)
}

/** Which menu the menu of [target] is, as the preferences arrange it: a listing's of a file, a folder, several, or none. */
fun BrowserState.menuKeyOf(target: Target): MenuKey =
	when (target) {
		is Target.Rows ->
			MenuKey.Listing(
				kind,
				target.rows.singleOrNull()?.let { if (it.isFolder) MenuOf.Folder else MenuOf.File }
					?: MenuOf.Several,
			)
		Target.Folder -> MenuKey.Listing(kind, MenuOf.Space)
		is Target.Sidebar -> MenuKey.Sidebar
	}

/** The slot an entry of the user's actions takes: an action's own, by its id; a submenu of them, by its name. */
private fun slotIdOf(entry: MenuEntry<*>): String =
	when (entry) {
		is MenuEntry.Item -> entry.id ?: entry.label
		is MenuEntry.Submenu -> submenuId(entry.label)
		MenuEntry.Separator -> "-"
	}

/** A menu's entries before they are arranged, each in its slot, by the slot's id. */
private class Slots {
	val by = LinkedHashMap<String, List<MenuEntry<BrowserEvent>>>()

	/** The slot [id], holding what [build] builds; none, where it builds nothing. */
	fun slot(id: String, build: MenuBuilder<BrowserEvent>.() -> Unit) {
		menu(build).entries.takeIf { it.isNotEmpty() }?.let { by[id] = it }
	}
}

private fun slots(build: Slots.() -> Unit): Map<String, List<MenuEntry<BrowserEvent>>> =
	Slots().apply(build).by

/** The first chord [action] is bound to, as a menu shows it; nothing where it is bound to none. */
private fun Bindings.keys(action: Bindable): String? =
	of(action).firstOrNull()?.toString()

/** [offered], the user's actions for [subject], as a menu's entries: those of no submenu, then each submenu, nested. */
private fun ownActions(
	offered: List<UserAction>,
	subject: Subject,
	bindings: Bindings,
	terminal: TerminalChoice,
): List<MenuEntry<BrowserEvent>> {
	// An action that runs in a terminal is off where the terminal chosen has no place for a command.
	fun runs(action: UserAction): Boolean =
		terminal.runsCommands || (action.deed as? Deed.Run)?.how != How.Terminal

	fun entries(actions: List<UserAction>, depth: Int): List<MenuEntry<BrowserEvent>> {
		val here = actions.filter { it.submenu.size == depth }
		val below = actions.filter { it.submenu.size > depth }.groupBy { it.submenu[depth] }

		return here.map {
			MenuEntry.Item(
				it.label,
				BrowserEvent.Did(it, subject) as BrowserEvent,
				keys = bindings.keys(Custom(it)),
				enabled = runs(it),
				id = it.id,
			)
		} +
			below.map { (label, under) ->
				MenuEntry.Submenu(label, Menu(entries(under, depth + 1)))
			}
	}

	return entries(offered, 0)
}

/** What prism offers for [rows], each in its slot. */
private fun rowsSlots(
	state: BrowserState,
	rows: List<Row>,
	bindings: Bindings,
	/** What opening the one row does, where an action of the user's opens it in place of its application. */
	opening: BrowserEvent?,
): Map<String, List<MenuEntry<BrowserEvent>>> {
	val one = rows.singleOrNull()
	val ids = rows.map { it.id }

	// What is in the trash is put back, or deleted for good, and nothing else.
	if (rows.all { it.item is Item.InTrash })
		return slots {
			slot("builtin.put-back") {
				item("Put back", BrowserEvent.PutBack(ids), id = "builtin.put-back")
			}
			slot("builtin.erase") {
				item(
					"Delete permanently",
					BrowserEvent.AskedToErase(ids),
					keys = bindings.keys(Action.Erase),
					id = "builtin.erase",
				)
			}
		}

	// What prism does itself, it does to files on disk: where any row is not one, there are no paths to do it to.
	val paths = rows.mapNotNull { it.path }.takeIf { it.size == rows.size }
	val exploring = state.choosing is Choosing.Exploring
	val folder = one?.takeIf { it.isFolder }?.path

	return slots {
		one?.let { row ->
			when {
				row.isFolder -> {
					slot("builtin.open") {
						item(
							"Open",
							opening ?: BrowserEvent.Activated(row.id),
							keys = bindings.keys(Action.Open),
							id = "builtin.open",
						)
					}
					if (exploring)
						folder?.let {
							slot("builtin.open-in-new-window") {
								item(
									"Open in new window",
									BrowserEvent.Requested(Request.NewWindow(it)),
									id = "builtin.open-in-new-window",
								)
							}
						}
					// A folder picker gives a folder: this one, without going into it.
					if (state.choosing is Choosing.Folder)
						slot("builtin.select-folder") {
							item("Select folder", BrowserEvent.Picked(row.id), id = "builtin.select-folder")
						}
				}
				state.choosing is Choosing.SaveAs ->
					slot("builtin.save-over") {
						item("Save over it", BrowserEvent.Picked(row.id), id = "builtin.save-over")
					}
				else ->
					slot("builtin.open") {
						item(
							"Open",
							opening ?: BrowserEvent.Picked(row.id),
							keys = bindings.keys(Action.Open),
							id = "builtin.open",
						)
					}
			}
		}
		// Several files open together, each with its application.
		if (exploring && one == null && paths != null && rows.any { !it.isFolder })
			slot("builtin.open") {
				item(
					"Open",
					BrowserEvent.Accepted,
					keys = bindings.keys(Action.Open),
					id = "builtin.open",
				)
			}
		if (exploring && rows.none { it.isFolder })
			paths?.let {
				slot("builtin.open-with") {
					item(
						"Open with…",
						// Another application can still be chosen for what an action opens, this once: it is the action
						// that is its default, which choosing an application does not change.
						BrowserEvent.Requested(Request.OpenWith(it, settable = opening == null)),
						id = "builtin.open-with",
					)
				}
			}
		if (exploring) {
			if (paths != null)
				slot("builtin.cut") {
					item(
						"Cut",
						BrowserEvent.CutFiles(ids),
						keys = bindings.keys(Action.CutFiles),
						id = "builtin.cut",
					)
				}
			// Entries of a place are copied where their place says how they are brought out of it.
			if (paths != null || state.carrying(rows) != null)
				slot("builtin.copy") {
					item(
						"Copy",
						BrowserEvent.CopiedFiles(ids),
						keys = bindings.keys(Action.CopyFiles),
						id = "builtin.copy",
					)
				}
			folder?.let {
				slot("builtin.paste-into") {
					item("Paste into folder", BrowserEvent.Pasted(it), id = "builtin.paste-into")
				}
			}
		}
		paths?.let {
			one?.let { row ->
				slot("builtin.rename") {
					item(
						"Rename",
						BrowserEvent.StartedRenaming(row.id),
						keys = bindings.keys(Action.Rename),
						id = "builtin.rename",
					)
				}
			}
			slot("builtin.copy-path") {
				item(
					if (one != null) "Copy path" else "Copy paths",
					BrowserEvent.CopiedPaths(it),
					keys = bindings.keys(Action.CopyPaths),
					id = "builtin.copy-path",
				)
			}
		}
		folder?.let { pinning(state, it) }
		if (exploring)
			folder?.let {
				slot("builtin.terminal") {
					item(
						"Open terminal here",
						BrowserEvent.OpenedTerminal(it),
						enabled = state.terminal.opensInFolder,
						id = "builtin.terminal",
					)
				}
			}
		// The trash apart from the rest, where it is not pressed in passing; deleting for good is asked first.
		if (paths != null) {
			slot("builtin.trash") {
				item(
					"Move to trash",
					BrowserEvent.Trashed(ids),
					keys = bindings.keys(Action.Trash),
					id = "builtin.trash",
				)
			}
			if (exploring)
				slot("builtin.erase") {
					item(
						"Delete permanently",
						BrowserEvent.AskedToErase(ids),
						keys = bindings.keys(Action.Erase),
						id = "builtin.erase",
					)
				}
		}
		if (exploring)
			paths?.let {
				slot("builtin.properties") {
					item(
						"Properties",
						BrowserEvent.Requested(Request.Properties(it)),
						keys = bindings.keys(Action.Properties),
						id = "builtin.properties",
					)
				}
			}
	}
}

/** Pinning [path] to the sidebar, or taking it off where quick access shows it. */
private fun Slots.pinning(state: BrowserState, path: Path) {
	when (val shown = state.sidebar.shown.firstOrNull { it.path == path }) {
		null ->
			slot("builtin.pin") {
				item("Pin to sidebar", BrowserEvent.Pinned(path), id = "builtin.pin")
			}
		else -> unpinning(shown)
	}
}

/**
 * Taking [shown] off the sidebar, as one entry whatever it is: a folder pinned is unpinned; one of prism's own is hidden,
 * since it is never removed, but to the user it is unpinned all the same.
 */
private fun Slots.unpinning(shown: SidebarItem) {
	when (shown) {
		is SidebarItem.Pinned, is SidebarItem.Own, SidebarItem.Trash, SidebarItem.System ->
			slot("builtin.unpin") {
				item("Unpin from sidebar", BrowserEvent.Unpinned(shown), id = "builtin.unpin")
			}
		is SidebarItem.Drive -> Unit
	}
}

/** What prism offers for the space around the rows: the folder shown, or what else is. */
private fun spaceSlots(
	state: BrowserState,
	bindings: Bindings,
): Map<String, List<MenuEntry<BrowserEvent>>> {
	val folders = state.choosing is Choosing.Folder
	val exploring = state.choosing is Choosing.Exploring
	// A place takes what is pasted where it says what taking files in does.
	val takes = state.place?.let { state.actions.into(it.placed) } != null
	val hidden = state.entryFilters.hidden

	return slots {
		state.changes.undo.lastOrNull()?.let {
			slot("builtin.undo") {
				item(
					"Undo ${doneBy(it.done)}",
					BrowserEvent.Undone,
					keys = bindings.keys(Action.Undo),
					id = "builtin.undo",
				)
			}
		}
		state.changes.redo.lastOrNull()?.let {
			slot("builtin.redo") {
				item(
					"Redo ${doneBy(it.done)}",
					BrowserEvent.Redone,
					keys = bindings.keys(Action.Redo),
					id = "builtin.redo",
				)
			}
		}
		if (state.inTrash)
			slot("builtin.empty-trash") {
				item(
					"Empty trash",
					BrowserEvent.AskedToEmpty,
					enabled = state.entryCount > 0,
					id = "builtin.empty-trash",
				)
			}
		if (exploring && (state.folder != null || takes))
			slot("builtin.paste") {
				item(
					"Paste",
					BrowserEvent.Pasted(),
					keys = bindings.keys(Action.Paste),
					id = "builtin.paste",
				)
			}
		slot("builtin.sort") { submenu("Sort by") { sorts(state) } }
		slot("builtin.view") {
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
		}
		// A folder picker has no files to show.
		if (!folders)
			slot("builtin.hidden-files") {
				check(
					"Show hidden files",
					hidden.files,
					BrowserEvent.ToggledHiddenFiles,
					alternate = BrowserEvent.ToggledHiddenFiles,
				)
			}
		slot("builtin.hidden-folders") {
			check(
				"Show hidden folders",
				hidden.folders,
				BrowserEvent.ToggledHiddenFolders,
				alternate = BrowserEvent.ToggledHiddenFolders,
			)
		}
		// A search's results, and a place, are in no one folder: there is nowhere to make one, and no path to copy.
		state.folder?.let {
			slot("builtin.new-folder") {
				item(
					"New folder",
					BrowserEvent.MadeFolder,
					keys = bindings.keys(Action.NewFolder),
					id = "builtin.new-folder",
				)
			}
			if (exploring)
				slot("builtin.new-file") {
					item(
						"New file",
						BrowserEvent.MadeFile,
						keys = bindings.keys(Action.NewFile),
						id = "builtin.new-file",
					)
				}
			slot("builtin.copy-path") {
				item("Copy path", BrowserEvent.CopiedPaths(listOf(it)), id = "builtin.copy-path")
			}
			pinning(state, it)
			if (exploring)
				slot("builtin.terminal") {
					item(
						"Open terminal here",
						BrowserEvent.OpenedTerminal(it),
						keys = bindings.keys(Action.OpenTerminal),
						enabled = state.terminal.opensInFolder,
						id = "builtin.terminal",
					)
				}
		}
		slot("builtin.refresh") {
			item(
				"Refresh",
				BrowserEvent.Refreshed,
				keys = bindings.keys(Action.Refresh),
				id = "builtin.refresh",
			)
		}
		if (exploring)
			state.folder?.let {
				slot("builtin.properties") {
					item(
						"Properties",
						BrowserEvent.Requested(Request.Properties(listOf(it))),
						id = "builtin.properties",
					)
				}
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
				enabled = state.terminal.opensInFolder,
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

/**
 * What prism offers for something the sidebar shows: open it, here or in a window of its own; the trash emptied; and,
 * for what quick access shows, taken off it, or quick access arranged.
 */
private fun sidebarSlots(
	state: BrowserState,
	item: SidebarItem,
): Map<String, List<MenuEntry<BrowserEvent>>> {
	val exploring = state.choosing is Choosing.Exploring

	return slots {
		slot("builtin.open") { item("Open", openingOf(item), id = "builtin.open") }
		when (val path = item.path) {
			null ->
				if (exploring && item == SidebarItem.Trash)
					slot("builtin.empty-trash") {
						item("Empty trash", BrowserEvent.AskedToEmpty, id = "builtin.empty-trash")
					}
			else -> {
				if (exploring) {
					slot("builtin.open-in-new-window") {
						item(
							"Open in new window",
							BrowserEvent.Requested(Request.NewWindow(path)),
							id = "builtin.open-in-new-window",
						)
					}
					slot("builtin.properties") {
						item(
							"Properties",
							BrowserEvent.Requested(Request.Properties(listOf(path))),
							id = "builtin.properties",
						)
					}
				}
			}
		}
		if (item !is SidebarItem.Drive) {
			unpinning(item)
			slot("builtin.arrange") {
				item(
					"Arrange quick access…",
					BrowserEvent.ArrangingQuickAccess(true),
					id = "builtin.arrange",
				)
			}
		}
	}
}

/**
 * The orders the listing can be put in: a folder's by each column, either way; a search's best first as well, and by
 * each column it can be ordered by ([Listing.Found.orders]), the rest shown but not to be chosen.
 */
private fun MenuBuilder<BrowserEvent>.sorts(state: BrowserState) {
	val found = state.listing as? Listing.Found
	val order = state.look.order
	val sort = order.sort

	if (found != null) {
		radio("Best match", order == Order.Relevance, BrowserEvent.SortedByRelevance)
		separator()
	}
	// Each column the listing offers, shown or not: an order is not a column's to be seen in.
	for (column in state.kind.offered)
		radio(
			state.kind.labelOf(column),
			sort?.by == column,
			BrowserEvent.SortedBy(sort?.copy(by = column) ?: Sort.of(column)),
			enabled = found?.orders(column) != false,
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
	val kinds = state.entryFilters.kinds
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
	val hidden = state.entryFilters.hidden

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

/** Whether [event] would change how the listing is drawn: whether a menu item that does it has anything to do. */
private fun BrowserState.changedBy(event: BrowserEvent): Boolean =
	step(this, event).state.zooms != zooms
