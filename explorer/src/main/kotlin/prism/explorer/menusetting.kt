package prism.explorer

import androidx.compose.runtime.Immutable
import prism.browser.Actions
import prism.browser.BUILTINS
import prism.browser.Item
import prism.browser.On
import prism.browser.Subject
import prism.browser.Thing
import prism.browser.ThingKind
import prism.browser.UserAction
import prism.browser.Where
import prism.browser.World
import prism.browser.arranged
import prism.browser.builtinsOf
import prism.browser.defaultOf
import prism.browser.submenuId
import prism.preferences.ActionSpec
import prism.preferences.ActionsFile
import prism.preferences.Held
import prism.preferences.ListingKind
import prism.preferences.MenuKey
import prism.preferences.MenuOf
import prism.preferences.MenuSpec
import prism.preferences.Moved
import prism.preferences.Placed
import prism.preferences.Show
import prism.preferences.Spec
import prism.preferences.WhenSpec
import prism.preferences.movedAmong
import prism.browser.Placed as PlacedIn

/*
 * The Context menus page's menus, as values: each menu as the page shows it to be arranged, its entries in order with
 * the lines between them ([menuLinesOf]), every entry it can hold whether or not it would now; and what each change
 * the page makes to one comes to, as the arrangement the preferences keep ([placedOf]).
 *
 * An entry of the user's actions is there where it could be offered in that menu at all. The page shows those of them
 * offered for the file it sees the menu for ([SeenMenu]): for all files, the actions for every file, and not those only
 * for some (a .zip, an image); for a file of a name and a type ([Sample]), the actions offered for it. What it does not
 * show stays where it is, among the rest, whatever is moved around it.
 *
 * A line is kept where the user puts it, even where the menu would not draw it (first, last, or with no entry shown
 * between it and the one before), so that one added can be moved to where it is wanted; [drawnLines] says which the
 * menu draws. Two lines together are one.
 */

/** Where a menu the page arranges is: a kind of listing's, or the sidebar's. */
@Immutable
sealed interface MenuPlace {
	data class Of(
		val kind: ListingKind,
	) : MenuPlace

	data object Sidebar : MenuPlace

	companion object {
		fun of(key: MenuKey): MenuPlace =
			when (key) {
				is MenuKey.Listing -> Of(key.kind)
				MenuKey.Sidebar -> Sidebar
			}
	}
}

/** The menu of [place] that is of [of] (a listing's), or the sidebar's. */
fun keyOf(place: MenuPlace, of: MenuOf): MenuKey =
	when (place) {
		is MenuPlace.Of -> MenuKey.Listing(place.kind, of)
		MenuPlace.Sidebar -> MenuKey.Sidebar
	}

/** What a menu is seen for: a file named [name], of [types] where they are said (as its name says otherwise). */
@Immutable
data class Sample(
	val label: String,
	val name: String,
	val types: List<String> = emptyList(),
)

/** What an entry of a menu is: one of prism's own, one of the user's actions, or a submenu of them. */
enum class EntryKind {
	Builtin,
	Action,
	Submenu,
}

/** A row of a menu as the page shows it: an entry, or a line between groups. */
@Immutable
sealed interface MenuLine {
	/** What tells it apart from the menu's others, as its entry is dragged. */
	val key: Any

	/** A line, the [at]th of the menu's: a line has no name of its own to be told apart by. */
	data class Line(
		val at: Int,
	) : MenuLine {
		override val key: Any get() = this
	}

	data class Entry(
		val slot: Placed.Slot,
		val label: String,
		val kind: EntryKind,
		/** When it is in the menu, where it is not always ("In an explorer"), or what it is for ("For .zip"). */
		val note: String? = null,
		/** Whether it is offered for the file the menu is seen for, as far as that decides it; or, for all files, to each. */
		val fits: Boolean = true,
		/** A submenu's items. */
		val within: List<Entry> = emptyList(),
	) : MenuLine {
		val id: String get() = slot.id
		val show: Show get() = slot.show
		override val key: Any get() = id

		/** Whether the menu as seen shows it, opened with [held] held: a submenu, where it shows any of its items. */
		fun shownWith(held: Set<Held>): Boolean =
			fits && show.admits(held) && (within.isEmpty() || within.any { it.shownWith(held) })
	}
}

/**
 * A menu as the page shows it: of [all] its lines (every entry it can hold, and the lines between), those [shown], the
 * entries offered for the file it is seen for (a submenu's items likewise) and the lines between them; not a line with
 * only entries not shown between it and the one before, which sets apart nothing here, and stays where it is.
 */
@Immutable
data class SeenMenu(
	val all: List<MenuLine>,
) {
	val shown: List<MenuLine> =
		all
			.mapNotNull { line ->
				when (line) {
					is MenuLine.Line -> line
					is MenuLine.Entry ->
						line
							.takeIf {
								it.fits
							}?.copy(within = line.within.filter { it.fits })
				}
			}.fold(emptyList()) { kept, line ->
				if (line is MenuLine.Line && kept.lastOrNull() is MenuLine.Line)
					kept
				else
					kept + line
			}

	/** How many entries it holds, a submenu's items among them, that are not shown: those for other files. */
	val unshown: Int =
		all.filterIsInstance<MenuLine.Entry>().sumOf { entry ->
			(if (entry.fits) 0 else 1) + entry.within.count { !it.fits }
		}
}

/**
 * The menu [key] as [menus] arrange it, every entry it can hold among prism's and [actions], seen for [sample] (all
 * files, where null) in [world]: in order, with the lines between groups, never two together; and, where it is arranged
 * as prism arranges it, none first or last.
 */
fun seenMenuOf(
	key: MenuKey,
	menus: MenuSpec,
	actions: Actions,
	specs: Map<String, WhenSpec>,
	sample: Sample?,
	world: World,
): SeenMenu {
	val possible = actions.actions.filter { it.id !in actions.hidden && possibleIn(it, key) }
	val (top, nested) = possible.partition { it.submenu.isEmpty() }
	val submenus = nested.groupBy { it.submenu.first() }
	val actionSlots = top.map { it.id } + submenus.keys.map(::submenuId)
	val builtins = builtinsOf(key).toSet()
	val order = arranged(menus.arrangementOf(key), defaultOf(key), actionSlots)
	val shows = order.filterIsInstance<Placed.Slot>().associate { it.id to it.show }
	val subject = sample?.let { subjectOf(key, it, actions) }
	val seen = sample?.let { SampleWorld(world, it) } ?: world

	// For all files, an action is offered to each where it is not only for some: of a name, or of a type.
	fun fits(action: UserAction): Boolean =
		subject?.let { action.offered(it, seen) }
			?: (action.conditions.names == null && action.conditions.types == null)

	fun actionEntry(action: UserAction): MenuLine.Entry =
		MenuLine.Entry(
			Placed.Slot(action.id, shows[action.id] ?: Show.Always),
			action.label,
			EntryKind.Action,
			specs[action.id]?.let(::noteOf),
			fits(action),
		)

	val entries =
		builtins.associateWith { id ->
			BUILTINS.getValue(id).let {
				MenuLine.Entry(
					Placed.Slot(id, shows[id] ?: Show.Always),
					it.label,
					EntryKind.Builtin,
					it.note,
				)
			}
		} +
			top.associate { it.id to actionEntry(it) } +
			submenus.map { (label, under) ->
				val within = under.map(::actionEntry)

				submenuId(label) to
					MenuLine.Entry(
						Placed.Slot(submenuId(label), shows[submenuId(label)] ?: Show.Always),
						label,
						EntryKind.Submenu,
						fits = within.any { it.fits },
						within = within,
					)
			}

	val known = order.filter { it !is Placed.Slot || it.id in entries }

	val lines =
		if (menus.arrangementOf(key) ==
			null
		)
			trimmed(merged(known))
		else
			merged(known)

	return SeenMenu(
		lines.mapIndexed { at, placed ->
			when (placed) {
				Placed.Separator -> MenuLine.Line(at)
				is Placed.Slot -> entries.getValue(placed.id)
			}
		},
	)
}

/** The arrangement the preferences keep of [menu]: each entry, a submenu's items after it, and the lines between. */
fun placedOf(menu: SeenMenu): List<Placed> = placedOf(menu.all)

private fun placedOf(lines: List<MenuLine>): List<Placed> =
	merged(
		lines.flatMap { line ->
			when (line) {
				is MenuLine.Line -> listOf(Placed.Separator)
				is MenuLine.Entry -> listOf(line.slot) + line.within.map { it.slot }
			}
		},
	)

/** [menu], with [move] made among the lines it shows, those it does not show staying where they are. */
fun moved(menu: SeenMenu, move: Moved): List<Placed> =
	placedOf(menu.all.movedAmong(menu.shown, MenuLine::key, move))

/** [menu], the entry [id] (or an item of a submenu) shown as [show]. */
fun showing(menu: SeenMenu, id: String, show: Show): List<Placed> {
	fun shown(entry: MenuLine.Entry): MenuLine.Entry =
		entry.copy(
			slot =
				if (entry.id == id)
					entry.slot.copy(show = show)
				else
					entry.slot,
			within = entry.within.map(::shown),
		)

	return placedOf(menu.all.map { if (it is MenuLine.Entry) shown(it) else it })
}

/** [menu] with a line added at the end, to be moved where it is wanted. */
fun lined(menu: SeenMenu): List<Placed> = placedOf(menu) + Placed.Separator

/** [menu] without the line at [at]. */
fun unlined(menu: SeenMenu, at: Int): List<Placed> =
	placedOf(
		menu.all.filterNot {
			it ==
				MenuLine.Line(at)
		},
	)

/**
 * Which of [lines] (by [MenuLine.Line.at]) the menu draws, opened with [held] held, as a menu is built: a line after an
 * entry shown, where an entry is shown after it; of lines with none shown between them, the first.
 */
fun drawnLines(lines: List<MenuLine>, held: Set<Held>): Set<Int> =
	lines
		.fold(Drawing()) { drawing, line ->
			when (line) {
				is MenuLine.Line ->
					if (drawing.anyShown && drawing.waiting == null)
						drawing.copy(waiting = line.at)
					else
						drawing
				is MenuLine.Entry ->
					if (line.shownWith(held))
						Drawing(
							drawing.drawn + listOfNotNull(drawing.waiting),
							anyShown = true,
							waiting = null,
						)
					else
						drawing
			}
		}.drawn

/** How far [drawnLines] has come: the lines drawn, whether an entry has been shown, and a line waiting for one to be. */
private data class Drawing(
	val drawn: Set<Int> = emptySet(),
	val anyShown: Boolean = false,
	val waiting: Int? = null,
)

/** [show], with [key] held for it or not: shown with the keys held left, always where none is. */
fun toggled(show: Show, key: Held): Show {
	val keys = (show as? Show.With)?.keys.orEmpty()

	return Show.with(
		if (key in keys)
			keys - key
		else
			keys + key,
	)
}

/**
 * The files a menu of [key] can be seen for: a file of each extension, name and type [specs] say actions are for, as
 * the actions [actions] of it can be offered; for a listing's files only, since a folder's and the space's are no file.
 */
fun samplesOf(
	key: MenuKey,
	actions: Actions,
	specs: Map<String, WhenSpec>,
): List<Sample> {
	if (key !is MenuKey.Listing || (key.of != MenuOf.File && key.of != MenuOf.Several))
		return emptyList()

	val written = actions.actions.filter { possibleIn(it, key) }.mapNotNull { specs[it.id] }
	val extensions = written.flatMap { it.ext.orEmpty() }.map { Sample(".$it", "file.$it") }
	val names =
		written.flatMap { it.names.orEmpty() }.map { glob ->
			Sample(glob, glob.replace("*", "file").replace("?", "x"))
		}
	val types = written.flatMap { it.types.orEmpty() }.map { Sample(it, "file", listOf(it)) }

	return (extensions + names + types)
		.distinctBy {
			it.label
		}.sortedBy { it.label.lowercase() }
}

/** What each action of prism's and the [base] preferences' is written to be offered for, by its id; a later one's over an earlier. */
fun specsOf(vararg files: ActionsFile): Map<String, WhenSpec> =
	files
		.flatMap { it.actionSpecs() }
		.mapNotNull { (it as? Spec.Read<ActionSpec>)?.value }
		.associate { it.id to it.conditions }

/**
 * Whether [action] could be offered in the menu [key] at all: for what that menu is of, in that listing, for as many
 * as it is of, of the kind they are; what it is for beyond that (a name, a type) is the file's to decide.
 */
private fun possibleIn(action: UserAction, key: MenuKey): Boolean {
	val conditions = action.conditions

	return when (key) {
		MenuKey.Sidebar -> conditions.on == On.Place
		is MenuKey.Listing -> {
			val kind = key.kind
			val word =
				when (kind) {
					ListingKind.Folder -> "folder"
					ListingKind.Results -> "results"
					ListingKind.Trash -> "trash"
					is ListingKind.Place -> kind.type
				}

			val on =
				if (key.of == MenuOf.Space)
					On.Folder
				else
					On.Files

			val count =
				when (key.of) {
					MenuOf.File, MenuOf.Folder -> 1
					MenuOf.Several -> 2
					MenuOf.Space -> null
				}

			val thing =
				when (key.of) {
					MenuOf.File -> setOf(ThingKind.File, ThingKind.Link)
					MenuOf.Folder -> setOf(ThingKind.Folder)
					MenuOf.Several, MenuOf.Space -> ThingKind.entries.toSet()
				}

			// The trash's rows only go back or go for good: no action of the user's is offered for them.
			conditions.on == on &&
				!(kind == ListingKind.Trash && on == On.Files) &&
				conditions.within?.let { word in it } != false &&
				(count == null || conditions.count?.holds?.invoke(count) != false) &&
				(conditions.kind == null || conditions.kind in thing) &&
				(conditions.real == null || conditions.real == (kind !is ListingKind.Place)) &&
				(
					conditions.elsewhere == null || key.of == MenuOf.Space ||
						conditions.elsewhere == (kind == ListingKind.Results)
				)
		}
	}
}

/** What a menu of [key] seen for [sample] is of: the file, once or twice, where its listing's window is. */
private fun subjectOf(key: MenuKey, sample: Sample, actions: Actions): Subject? {
	if (key !is MenuKey.Listing)
		return null

	val home =
		java.nio.file.Path
			.of(System.getProperty("user.home"))

	val where =
		when (val kind = key.kind) {
			ListingKind.Folder -> Where.Folder(home)
			ListingKind.Results -> Where.Results
			ListingKind.Trash -> Where.Trash
			is ListingKind.Place ->
				actions.places[kind.type]?.let { Where.Place(PlacedIn(it, emptyMap())) }
					?: return null
		}

	val item =
		if (key.kind is ListingKind.Place)
			Item.InPlace(sample.name)
		else
			Item.OnDisk(home.resolve(sample.name))

	val kind =
		if (key.of == MenuOf.Folder)
			ThingKind.Folder
		else
			ThingKind.File
	val thing = Thing(item, sample.name, kind)

	return Subject(
		On.Files,
		if (key.of ==
			MenuOf.Several
		)
			listOf(thing, thing)
		else
			listOf(thing),
		where,
	)
}

/** [world], but for [sample]'s file, whose types are those it is seen as. */
private class SampleWorld(
	private val world: World,
	private val sample: Sample,
) : World by world {
	override fun lineage(thing: Thing): List<String> =
		if (sample.types.isNotEmpty() && thing.name == sample.name)
			sample.types
		else
			world.lineage(thing)
}

/** What an action is written to be offered for, in a few words: the extensions, names and types of what it is for. */
private fun noteOf(spec: WhenSpec): String? {
	fun few(words: List<String>): String =
		words.take(3).joinToString(", ") + if (words.size > 3) ", …" else ""

	val parts =
		listOfNotNull(
			spec.ext?.takeIf { it.isNotEmpty() }?.let { few(it.map { ext -> ".$ext" }) },
			spec.names?.takeIf { it.isNotEmpty() }?.let(::few),
			spec.types?.takeIf { it.isNotEmpty() }?.let(::few),
		)

	return parts.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "For ")
}

/** [placed] without a line right after another. */
private fun merged(placed: List<Placed>): List<Placed> =
	placed.filterIndexed { at, it ->
		!(
			it == Placed.Separator &&
				placed.getOrNull(at - 1) == Placed.Separator
		)
	}

/** [placed] without lines first or last. */
private fun trimmed(placed: List<Placed>): List<Placed> =
	placed.dropWhile { it == Placed.Separator }.dropLastWhile { it == Placed.Separator }
