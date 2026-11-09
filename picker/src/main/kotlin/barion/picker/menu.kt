package barion.picker

import androidx.compose.runtime.Immutable

/*
 * Menus, as data. What a menu offers is worked out from what it is a menu of by a pure function (menus.kt makes the
 * picker's), and drawn by one component wherever it opens (menuview.kt): at the pointer for a context menu, under a
 * button for a menu button, beside its entry for a submenu.
 *
 * An entry chosen gives back its action, of a type the menu is of: a menu of the picker's gives `PickerEvent`s, so
 * choosing from it is reporting what the user did, as any other control does, and the reducer decides what comes of
 * it. A menu knows nothing of what its actions mean.
 *
 * For example, the menu of a file in the listing:
 *
 * ```kotlin
 * menu {
 *     item("Open", PickerEvent.Activated(row.id), keys = "Enter")
 *     separator()
 *     item("Rename", PickerEvent.StartedRenaming(row.id), keys = "F2")
 *     item("Move to trash", PickerEvent.Trashed(listOf(row.id)), keys = "Delete")
 * }
 * ```
 */

/** A menu of entries, each of which gives an [A] when chosen. */
@Immutable
data class Menu<out A>(
	val entries: List<MenuEntry<A>>,
) {
	/** Whether any item shows a mark, so that every item keeps room for one and the labels line up. */
	val marked: Boolean get() = entries.any { it is MenuEntry.Item && it.mark != Mark.None }

	fun isEmpty(): Boolean = entries.isEmpty()
}

/** One entry of a menu. */
@Immutable
sealed interface MenuEntry<out A> {
	/** Something to do. */
	data class Item<out A>(
		val label: String,
		val action: A,
		/**
		 * What is done instead when the item is chosen with Ctrl held, the menu then staying open, so that several can
		 * be chosen in turn: adding to a choice, where choosing plainly replaces it. Null where Ctrl changes nothing.
		 */
		val alternate: A? = null,
		val enabled: Boolean = true,
		val mark: Mark = Mark.None,
		/** The keys that do the same without the menu, shown beside the label: "F2", "Ctrl+Shift+N". */
		val keys: String? = null,
	) : MenuEntry<A>

	/** Entries a level down, opened beside this one. */
	data class Submenu<out A>(
		val label: String,
		val menu: Menu<A>,
	) : MenuEntry<A>

	/** A line between groups of entries. */
	data object Separator : MenuEntry<Nothing>
}

/** What an item shows beside its label of the state it changes. */
@Immutable
sealed interface Mark {
	data object None : Mark

	/** One of several that can each be on: a tick when on. */
	data class Check(
		val on: Boolean,
	) : Mark

	/** One of several of which one is on: a dot when on. */
	data class Radio(
		val on: Boolean,
	) : Mark
}

/**
 * A menu built by [build], entry by entry. Separators are kept only between groups, never first, last or two
 * together, so that a group left out for one target leaves no stray line; a submenu with nothing in it is left out.
 */
fun <A> menu(build: MenuBuilder<A>.() -> Unit): Menu<A> =
	MenuBuilder<A>().apply(build).built()

/** What [menu] builds a menu with. Its list is its own, and the menu it builds is immutable. */
class MenuBuilder<A> internal constructor() {
	private val entries = mutableListOf<MenuEntry<A>>()

	fun item(
		label: String,
		action: A,
		keys: String? = null,
		enabled: Boolean = true,
		alternate: A? = null,
	) {
		entries +=
			MenuEntry.Item(label, action, alternate, enabled, Mark.None, keys)
	}

	/** An item that is on or off, ticked when on. */
	fun check(
		label: String,
		on: Boolean,
		action: A,
		alternate: A? = null,
		keys: String? = null,
	) {
		entries += MenuEntry.Item(label, action, alternate, mark = Mark.Check(on), keys = keys)
	}

	/** One item of a group of which one is on, dotted when it is. */
	fun radio(label: String, on: Boolean, action: A, keys: String? = null) {
		entries += MenuEntry.Item(label, action, mark = Mark.Radio(on), keys = keys)
	}

	fun submenu(label: String, build: MenuBuilder<A>.() -> Unit) {
		menu(build).takeUnless { it.isEmpty() }?.let { entries += MenuEntry.Submenu(label, it) }
	}

	fun separator() {
		entries += MenuEntry.Separator
	}

	internal fun built(): Menu<A> =
		Menu(
			entries
				.fold(emptyList<MenuEntry<A>>()) { kept, entry ->
					val stray =
						entry == MenuEntry.Separator &&
							(kept.isEmpty() || kept.last() == MenuEntry.Separator)

					if (stray)
						kept
					else
						kept + entry
				}.dropLastWhile { it == MenuEntry.Separator },
		)
}
