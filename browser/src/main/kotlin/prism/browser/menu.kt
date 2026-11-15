package prism.browser

import androidx.compose.runtime.Immutable

/*
 * Menus, as data. What a menu offers is worked out from what it is a menu of by a pure function (menus.kt makes the
 * picker's), and drawn by one component wherever it opens (menuview.kt): at the pointer for a context menu, under a
 * button for a menu button, beside its entry for a submenu.
 *
 * An entry chosen gives back its action, of a type the menu is of: a menu of the picker's gives `BrowserEvent`s, so
 * choosing from it is reporting what the user did, as any other control does, and the reducer decides what comes of
 * it. A menu knows nothing of what its actions mean.
 *
 * An item is one of three things, told apart at a glance by what is drawn before its label ([Mark]): something done
 * (nothing before it), a box ticked or not, or one of a ring of choices. Which it is says how it is drawn and
 * whether choosing it closes the menu, so no menu says either for itself.
 *
 * For example, the menu of a file in the listing:
 *
 * ```kotlin
 * menu {
 *     item("Open", BrowserEvent.Activated(row.id), keys = "Enter")
 *     separator()
 *     item("Rename", BrowserEvent.StartedRenaming(row.id), keys = "F6")
 *     item("Move to trash", BrowserEvent.Trashed(listOf(row.id)), keys = "Delete")
 * }
 * ```
 */

/** A menu of entries, each of which gives an [A] when chosen. */
@Immutable
data class Menu<out A>(
	val entries: List<MenuEntry<A>>,
) {
	/** Whether any item has something before its label, so that every item keeps room for it and the labels line up. */
	val marked: Boolean get() = entries.any { it is MenuEntry.Item && it.mark != Mark.None }

	fun isEmpty(): Boolean = entries.isEmpty()

	/** This without the entries known by any of [hidden], at any level; a submenu left empty goes with them. */
	fun without(hidden: Set<String>): Menu<A> =
		if (hidden.isEmpty())
			this
		else
			menu {
				for (entry in entries)
					when (entry) {
						is MenuEntry.Item ->
							if (entry.id !in hidden)
								add(entry)
						is MenuEntry.Submenu ->
							entry.menu.without(hidden).takeUnless { it.isEmpty() }?.let {
								add(MenuEntry.Submenu(entry.label, it))
							}
						MenuEntry.Separator -> separator()
					}
			}
}

/** One entry of a menu. */
@Immutable
sealed interface MenuEntry<out A> {
	/** Something to do. */
	data class Item<out A>(
		val label: String,
		val action: A,
		/**
		 * What is done instead when the item is chosen with Ctrl held: a box's, which leaves it the only one of its
		 * boxes ticked, where choosing it plainly ticks or unticks it alone. Null where Ctrl changes nothing.
		 */
		val alternate: A? = null,
		val enabled: Boolean = true,
		val mark: Mark = Mark.None,
		/** The keys that do the same without the menu, shown beside the label: "F6", "Ctrl+Shift+N". */
		val keys: String? = null,
		/** What the entry is known by, where it can be hidden: `builtin.copy-path`, or an action's id. */
		val id: String? = null,
	) : MenuEntry<A>

	/** Entries a level down, opened beside this one. */
	data class Submenu<out A>(
		val label: String,
		val menu: Menu<A>,
	) : MenuEntry<A>

	/** A line between groups of entries. */
	data object Separator : MenuEntry<Nothing>
}

/**
 * What an item is, which is what is drawn before its label and what choosing it does to the menu: something done, a
 * box ticked or not, or one of a ring of choices.
 */
@Immutable
sealed interface Mark {
	/**
	 * Whether the menu stays open once the item is chosen. A box does not end a visit to its menu: several are set
	 * in one, each showing at once what it was set to. Anything else is what the menu was opened for, and closes it.
	 */
	val stays: Boolean get() = false

	/** Whether it is on, where it is something that can be: what a reader of the screen is told of it. */
	val on: Boolean? get() = null

	/** Something done: nothing is drawn before it. */
	data object None : Mark

	/** Something done, drawn as a button within the menu: the one thing to do, over a menu of things to set. */
	data object Button : Mark

	/** One of several that can each be on: a box, ticked when on. */
	data class Check(
		override val on: Boolean,
	) : Mark {
		override val stays: Boolean get() = true
	}

	/** One of several of which one is on: a ring, with a dot in it when on. */
	data class Radio(
		override val on: Boolean,
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

	/** Something to do. */
	fun item(
		label: String,
		action: A,
		keys: String? = null,
		enabled: Boolean = true,
		id: String? = null,
	) {
		entries += MenuEntry.Item(label, action, enabled = enabled, keys = keys, id = id)
	}

	/** Something to do, drawn as a button: see [Mark.Button]. */
	fun button(label: String, action: A, enabled: Boolean = true) {
		entries += MenuEntry.Item(label, action, enabled = enabled, mark = Mark.Button)
	}

	/** [entry] as it is: one made elsewhere. */
	fun add(entry: MenuEntry<A>) {
		entries += entry
	}

	/**
	 * A box that is on or off, ticked when on: [action] is what turns it the other way, and [alternate] what Ctrl
	 * held does instead, where that is something else. The menu stays open either way.
	 */
	fun check(
		label: String,
		on: Boolean,
		action: A,
		alternate: A? = null,
		keys: String? = null,
	) {
		entries += MenuEntry.Item(label, action, alternate, mark = Mark.Check(on), keys = keys)
	}

	/** One of a ring of choices of which one is on, dotted when it is. */
	fun radio(
		label: String,
		on: Boolean,
		action: A,
		keys: String? = null,
		enabled: Boolean = true,
	) {
		entries +=
			MenuEntry.Item(label, action, enabled = enabled, mark = Mark.Radio(on), keys = keys)
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
