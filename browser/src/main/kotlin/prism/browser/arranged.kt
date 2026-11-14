package prism.browser

import androidx.compose.runtime.Immutable
import prism.preferences.Held
import prism.preferences.ListingKind
import prism.preferences.MenuKey
import prism.preferences.MenuOf
import prism.preferences.Placed
import prism.preferences.Show

/*
 * The context menus, arranged: what a menu holds is worked out (menus.kt) as its entries each in a slot of its own, by
 * the slot's id, and an arrangement puts the slots in order, with lines between groups, each shown always, never, or
 * only with keys held as the menu opens. prism has an arrangement of its own for each menu ([defaultOf]); the user's
 * (preferences' `menu.arranged`) is laid over it ([arranged]), so that what the user's leaves out (an action added
 * since, an entry a later prism adds) goes where prism's would put it.
 *
 * The user's actions have no place in prism's arrangement but the one they share ([ACTIONS]): each takes a slot of its
 * own there, in the order they are written, and a submenu of them one slot for all of it. An item in a submenu is
 * arranged by its own id after the submenu: where it goes is the submenu's, and whether it is shown its own.
 *
 * What each of prism's own entries is, and which menus it can be in, is said here too ([BUILTINS], [builtinsOf]), for
 * the preferences to arrange each menu with everything it can hold, whatever it holds as they are looked at.
 */

/** Where the user's actions go in prism's own arrangements: each in a slot of its own, in their written order. */
const val ACTIONS = "@actions"

/** The slot of a submenu of the user's actions named [label]. */
fun submenuId(label: String): String = "submenu:$label"

/** The arrangement prism gives the menu [key]. */
fun defaultOf(key: MenuKey): List<Placed> =
	when (key) {
		MenuKey.Sidebar -> SIDEBAR
		is MenuKey.Listing ->
			when (key.of) {
				MenuOf.Space -> SPACE
				MenuOf.File, MenuOf.Folder, MenuOf.Several -> ROWS
			}
	}

/**
 * [user]'s arrangement of a menu prism arranges as [defaults] (prism's, where [user] is null), the user's actions (by
 * the ids of their slots, [actions]) in place of [ACTIONS]: each slot where [user] puts it, shown as it says; each it
 * does not name after the nearest slot before it in prism's that it does, shown as prism's says.
 */
fun arranged(
	user: List<Placed>?,
	defaults: List<Placed>,
	actions: List<String>,
): List<Placed> {
	val prisms =
		defaults.flatMap { placed ->
			if (placed is Placed.Slot && placed.id == ACTIONS)
				actions.map { Placed.Slot(it) }
			else
				listOf(placed)
		}

	if (user == null)
		return prisms

	val named = user.filter { !(it is Placed.Slot && it.id == ACTIONS) }

	return prisms.foldIndexed(named) { index, sofar, placed ->
		if (placed !is Placed.Slot || sofar.any { it is Placed.Slot && it.id == placed.id }) {
			sofar
		} else {
			// After the nearest slot before it in prism's arrangement that this one has; first where it has none.
			val before =
				prisms
					.take(index)
					.asReversed()
					.filterIsInstance<Placed.Slot>()
					.firstNotNullOfOrNull { earlier ->
						sofar.indexOfFirst { it is Placed.Slot && it.id == earlier.id }.takeIf { it >= 0 }
					}
			val at = before?.plus(1) ?: 0

			sofar.take(at) + placed + sofar.drop(at)
		}
	}
}

/**
 * A menu of the entries of [slots] (each slot's, by its id) in [arrangement]'s order, with lines where it puts them:
 * each shown as the arrangement says, with [held] held as the menu opens; an item of a submenu likewise, by its own id,
 * and in the arrangement's order among the submenu's others (what it does not name after them, as they come).
 */
fun <A> assembled(
	slots: Map<String, List<MenuEntry<A>>>,
	arrangement: List<Placed>,
	held: Set<Held>,
): Menu<A> {
	val shows = arrangement.filterIsInstance<Placed.Slot>().associate { it.id to it.show }

	fun admitted(entry: MenuEntry<A>): MenuEntry<A>? =
		when (entry) {
			is MenuEntry.Item ->
				entry.takeIf { item ->
					item.id?.let(shows::get)?.admits(held) !=
						false
				}
			is MenuEntry.Submenu ->
				Menu(
					entry.menu.entries
						.inOrderOf(arrangement) {
							(it as? MenuEntry.Item)?.id
						}.mapNotNull(::admitted),
				).let { menu { it.entries.forEach(::add) } }
					.takeUnless { it.isEmpty() }
					?.let { MenuEntry.Submenu(entry.label, it) }
			MenuEntry.Separator -> entry
		}

	return menu {
		for (placed in arrangement)
			when (placed) {
				Placed.Separator -> separator()
				is Placed.Slot ->
					if (placed.show.admits(held))
						slots[placed.id].orEmpty().mapNotNull(::admitted).forEach(::add)
			}
	}
}

/**
 * These, in [arrangement]'s order by their ids ([idOf]); those it does not name after them, as they come. How a
 * submenu's items are put in order, in a menu and as the preferences window shows one.
 */
fun <T> List<T>.inOrderOf(arrangement: List<Placed>, idOf: (T) -> String?): List<T> {
	val places =
		arrangement.filterIsInstance<Placed.Slot>().withIndex().associate { (at, slot) ->
			slot.id to
				at
		}

	return sortedBy { idOf(it)?.let(places::get) ?: Int.MAX_VALUE }
}

/** One of prism's own entries, as the preferences name it: what it says ([label]), and when it is there ([note]). */
@Immutable
data class Builtin(
	val id: String,
	val label: String,
	/** When it is in a menu that can hold it, where that is not always: "Explorer only", "A folder pinned". */
	val note: String? = null,
)

/** Every entry of prism's own a context menu can hold. */
val BUILTINS: Map<String, Builtin> =
	listOf(
		Builtin("builtin.open", "Open"),
		Builtin("builtin.put-back", "Restore"),
		Builtin("builtin.open-in-new-window", "Open in new window", "Explorer only"),
		Builtin("builtin.select-folder", "Select folder", "When selecting a folder"),
		Builtin("builtin.save-over", "Replace", "When saving a file"),
		Builtin("builtin.open-with", "Open with…", "Explorer only"),
		Builtin("builtin.cut", "Cut", "Explorer only"),
		Builtin("builtin.copy", "Copy", "Explorer only"),
		Builtin("builtin.paste-into", "Paste into folder", "Explorer only"),
		Builtin("builtin.rename", "Rename"),
		Builtin("builtin.copy-path", "Copy path"),
		Builtin("builtin.pin", "Pin to sidebar", "Folders not in the sidebar"),
		Builtin("builtin.unpin", "Unpin from sidebar", "Items in the sidebar"),
		Builtin("builtin.terminal", "Open terminal here", "Explorer only"),
		Builtin("builtin.trash", "Move to trash"),
		Builtin("builtin.erase", "Delete permanently", "Explorer only"),
		Builtin("builtin.properties", "Properties", "Explorer only"),
		Builtin("builtin.undo", "Undo", "When there is something to undo"),
		Builtin("builtin.redo", "Redo", "When there is something to redo"),
		Builtin("builtin.empty-trash", "Empty trash", "Explorer only"),
		Builtin("builtin.paste", "Paste", "Explorer only"),
		Builtin("builtin.sort", "Sort by"),
		Builtin("builtin.view", "View"),
		Builtin("builtin.hidden-files", "Show hidden files"),
		Builtin("builtin.hidden-folders", "Show hidden folders"),
		Builtin("builtin.new-folder", "New folder"),
		Builtin("builtin.new-file", "New file", "Explorer only"),
		Builtin("builtin.refresh", "Refresh"),
		Builtin("builtin.arrange", "Edit quick access…"),
	).associateBy { it.id }

/**
 * The ids of prism's own entries the menu [key] can hold, in prism's order: everything that menu, of that listing, can
 * show in an explorer or a picker, whether or not it would now.
 */
fun builtinsOf(key: MenuKey): List<String> {
	val ids =
		when (key) {
			MenuKey.Sidebar -> SIDEBAR_HOLDS
			is MenuKey.Listing -> listingHolds(key.kind, key.of)
		}

	return defaultOf(key).filterIsInstance<Placed.Slot>().map { it.id }.filter { it in ids }
}

/** What a listing's menu holds of prism's own: the trash's rows only go back or go for good; a place's are no files. */
private fun listingHolds(kind: ListingKind, of: MenuOf): Set<String> {
	val onDisk = kind == ListingKind.Folder || kind == ListingKind.Results
	val place = kind is ListingKind.Place

	return when (of) {
		MenuOf.File, MenuOf.Folder, MenuOf.Several ->
			when {
				kind == ListingKind.Trash -> setOf("builtin.put-back", "builtin.erase")
				else ->
					buildSet {
						val one = of != MenuOf.Several

						if (of != MenuOf.Several || onDisk)
							add("builtin.open")
						if (of == MenuOf.Folder)
							add("builtin.select-folder")
						if (of == MenuOf.File)
							add("builtin.save-over")
						if (onDisk || place)
							add("builtin.copy")
						if (onDisk) {
							if (of == MenuOf.Folder)
								addAll(
									listOf(
										"builtin.open-in-new-window",
										"builtin.paste-into",
										"builtin.pin",
										"builtin.unpin",
										"builtin.terminal",
									),
								)
							if (of != MenuOf.Folder)
								add("builtin.open-with")
							if (one)
								add("builtin.rename")
							addAll(
								listOf(
									"builtin.cut",
									"builtin.copy-path",
									"builtin.trash",
									"builtin.erase",
									"builtin.properties",
								),
							)
						}
					}
			}
		MenuOf.Space ->
			buildSet {
				addAll(
					listOf(
						"builtin.undo",
						"builtin.redo",
						"builtin.sort",
						"builtin.view",
						"builtin.hidden-files",
						"builtin.hidden-folders",
						"builtin.refresh",
					),
				)
				if (kind == ListingKind.Trash)
					add("builtin.empty-trash")
				if (kind == ListingKind.Folder || place)
					add("builtin.paste")
				if (kind == ListingKind.Folder)
					addAll(
						listOf(
							"builtin.new-folder",
							"builtin.new-file",
							"builtin.copy-path",
							"builtin.pin",
							"builtin.unpin",
							"builtin.terminal",
							"builtin.properties",
						),
					)
			}
	}
}

private val SIDEBAR_HOLDS =
	setOf(
		"builtin.open",
		"builtin.empty-trash",
		"builtin.open-in-new-window",
		"builtin.properties",
		"builtin.unpin",
		"builtin.arrange",
	)

/** Shown only with Shift held: what is rarely wanted, out of the way until it is. */
private val SHIFT = Show.With(setOf(Held.Shift))

private fun slot(id: String, show: Show = Show.Always) = Placed.Slot(id, show)

private val LINE = Placed.Separator

/** prism's arrangement of a row's menu: a file's, a folder's, several's; and the trash's rows, which go back or for good. */
private val ROWS: List<Placed> =
	listOf(
		slot("builtin.open"),
		slot("builtin.put-back"),
		slot("builtin.open-in-new-window"),
		slot("builtin.select-folder"),
		slot("builtin.save-over"),
		slot("builtin.open-with"),
		LINE,
		slot(ACTIONS),
		LINE,
		slot("builtin.cut"),
		slot("builtin.copy"),
		slot("builtin.paste-into"),
		LINE,
		slot("builtin.rename"),
		slot("builtin.copy-path"),
		slot("builtin.pin", SHIFT),
		slot("builtin.unpin", SHIFT),
		slot("builtin.terminal"),
		LINE,
		slot("builtin.trash"),
		slot("builtin.erase"),
		LINE,
		slot("builtin.properties"),
	)

/** prism's arrangement of the menu of the space around the rows: the folder shown, or what else is. */
private val SPACE: List<Placed> =
	listOf(
		slot("builtin.undo"),
		slot("builtin.redo"),
		LINE,
		slot("builtin.empty-trash"),
		LINE,
		slot("builtin.paste"),
		LINE,
		slot(ACTIONS),
		LINE,
		slot("builtin.sort"),
		slot("builtin.view"),
		LINE,
		slot("builtin.hidden-files", SHIFT),
		slot("builtin.hidden-folders", SHIFT),
		LINE,
		slot("builtin.new-folder"),
		slot("builtin.new-file"),
		slot("builtin.copy-path"),
		slot("builtin.pin", SHIFT),
		slot("builtin.unpin", SHIFT),
		slot("builtin.terminal"),
		slot("builtin.refresh", SHIFT),
		LINE,
		slot("builtin.properties"),
	)

/** prism's arrangement of the menu of something the sidebar shows. */
private val SIDEBAR: List<Placed> =
	listOf(
		slot("builtin.open"),
		slot("builtin.empty-trash"),
		slot("builtin.open-in-new-window"),
		LINE,
		slot(ACTIONS),
		LINE,
		slot("builtin.properties"),
		LINE,
		slot("builtin.unpin", SHIFT),
		slot("builtin.arrange"),
	)
