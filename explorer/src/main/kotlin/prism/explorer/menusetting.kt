package prism.explorer

import androidx.compose.runtime.Immutable
import prism.browser.Actions
import prism.browser.BUILTINS
import prism.browser.Item
import prism.browser.Offering
import prism.browser.On
import prism.browser.Subject
import prism.browser.Takes
import prism.browser.TextSearch
import prism.browser.Thing
import prism.browser.ThingKind
import prism.browser.UserAction
import prism.browser.Where
import prism.browser.World
import prism.browser.arranged
import prism.browser.builtinsOf
import prism.browser.defaultOf
import prism.browser.inOrderOf
import prism.browser.submenuId
import prism.preferences.ActionSpec
import prism.preferences.ActionsFile
import prism.preferences.Declared
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
 * An entry of the user's actions, or of the text searches (prism's and the user's), is there where it could be offered
 * in that menu at all. The page shows those of them offered for the file it sees the menu for ([SeenMenu]): for all
 * files, those for every file, and not those only for some (a .zip, an image); for a file of a name and a type
 * ([Sample]), those offered for it. What it does not show stays where it is, among the rest, whatever is moved around
 * it.
 *
 * A line is kept where the user puts it, before or after an entry ([lined]). Two lines together are one.
 *
 * What differs from prism's own arrangement of a menu is said of each line and entry ([changesOf]), for the page to show
 * what has been changed.
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

/** What an entry of a menu is: one of prism's own, one of the user's actions, a text search, or a submenu of them. */
enum class EntryKind {
	Builtin,
	Action,
	Search,
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
 * The menu [key] as [menus] arrange it, every entry it can hold among prism's and [actions] (its actions and its text
 * searches), seen for [sample] (all files, where null) in [world]: in order, with the lines between groups, never two
 * together; and, where it is arranged as prism arranges it, none first or last. The actions come before the searches,
 * as a menu offers them.
 */
fun seenMenuOf(
	key: MenuKey,
	menus: MenuSpec,
	actions: Actions,
	specs: Map<String, WhenSpec>,
	sample: Sample?,
	world: World,
): SeenMenu {
	// Only a search that looks in files is offered in a menu: one of lines follows something that found some.
	val offerings: List<Offering> =
		actions.actions + actions.searches.filter { it.takes == Takes.Files }
	val possible = offerings.filter { it.id !in actions.hidden && possibleIn(it, key) }
	val (top, nested) = possible.partition { it.submenu.isEmpty() }
	val submenus = nested.groupBy { it.submenu.first() }
	val actionSlots = top.map { it.id } + submenus.keys.map(::submenuId)
	val builtins = builtinsOf(key).toSet()
	val order = arranged(menus.arrangementOf(key), defaultOf(key), actionSlots)
	val shows = order.filterIsInstance<Placed.Slot>().associate { it.id to it.show }
	val subject = sample?.let { subjectOf(key, it, actions) }
	val seen = sample?.let { SampleWorld(world, it) } ?: world

	// For all files, an action or a search is offered to each where it is not only for some: of a name, or of a type.
	fun fits(offering: Offering): Boolean =
		subject?.let { offering.offered(it, seen) }
			?: (offering.conditions.names == null && offering.conditions.types == null)

	fun actionEntry(offering: Offering): MenuLine.Entry {
		val note = specs[offering.id]?.let(::noteOf)

		return MenuLine.Entry(
			Placed.Slot(offering.id, shows[offering.id] ?: Show.Always),
			offering.label,
			when (offering) {
				is UserAction -> EntryKind.Action
				is TextSearch -> EntryKind.Search
			},
			when (offering) {
				is UserAction -> note
				is TextSearch -> listOfNotNull("Text search", note).joinToString(" · ")
			},
			fits(offering),
		)
	}

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
				// In the order the menu puts them in.
				val within = under.map(::actionEntry).inOrderOf(order) { it.id }

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
	merged(lines.flatMap(::placesOf))

/** The places [line] takes in an arrangement: a line's, or an entry's and its items' after it. */
private fun placesOf(line: MenuLine): List<Placed> =
	when (line) {
		is MenuLine.Line -> listOf(Placed.Separator)
		is MenuLine.Entry -> listOf(line.slot) + line.within.map { it.slot }
	}

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

/** [menu], with [move] made among the items of the submenu [submenu] it shows, those it does not show staying put. */
fun movedWithin(menu: SeenMenu, submenu: String, move: Moved): List<Placed> {
	val shown =
		menu.shown
			.filterIsInstance<MenuLine.Entry>()
			.firstOrNull {
				it.id == submenu
			}?.within
			.orEmpty()

	return placedOf(
		menu.all.map { line ->
			if (line is MenuLine.Entry && line.id == submenu)
				line.copy(within = line.within.movedAmong(shown, MenuLine.Entry::id, move))
			else
				line
		},
	)
}

/** Which side of an entry, or of a line, another line is put on. */
enum class Side {
	Before,
	After,
}

/**
 * Whether a line put on [side] of the line or entry [at] (by its key) sets anything apart in [menu] as it is shown: an
 * entry is there, on that side, for it to set apart; not a line already, nor the menu's end.
 */
fun linable(menu: SeenMenu, at: Any, side: Side): Boolean {
	val place = menu.shown.indexOfFirst { it.key == at }

	val beside =
		menu.shown.getOrNull(
			if (side == Side.Before)
				place - 1
			else
				place + 1,
		)

	return place >= 0 && beside is MenuLine.Entry
}

/** [menu] with a line put on [side] of the line or entry [at] (by its key): an entry's items stay with it. */
fun lined(menu: SeenMenu, at: Any, side: Side): List<Placed> {
	val parts = menu.all.map(::placesOf)
	val place = menu.all.indexOfFirst { it.key == at } + if (side == Side.After) 1 else 0

	return merged(
		(parts.take(place) + listOf(listOf(Placed.Separator)) + parts.drop(place)).flatten(),
	)
}

/** [menu] without the line at [at]. */
fun unlined(menu: SeenMenu, at: Int): List<Placed> =
	placedOf(
		menu.all.filterNot {
			it ==
				MenuLine.Line(at)
		},
	)

/** How a line or an entry of a menu differs from prism's own arrangement of it. */
enum class Change(
	val label: String,
) {
	/** A line prism's has not, there. */
	Added("Added"),

	/** Out of the order prism's has it in. */
	Moved("Moved"),

	/** Shown with other keys, or never, or now always. */
	Shown("Visibility changed"),
}

/**
 * How each line and entry of [menu] (by its key), a submenu's items among them, differs from [prisms], the same menu as
 * prism arranges it; those that do not differ are not in it. What keeps prism's order is the most of them that can: of
 * one entry dragged elsewhere, that one is moved, not each it passed; and of two that changed places, the one the user
 * [dragged] (by id) is.
 */
fun changesOf(
	menu: SeenMenu,
	prisms: SeenMenu,
	dragged: Set<String> = emptySet(),
): Map<Any, Set<Change>> {
	fun entries(lines: List<MenuLine>) =
		lines.filterIsInstance<MenuLine.Entry>().flatMap {
			listOf(it) +
				it.within
		}

	fun tokensOf(lines: List<MenuLine>): List<Any> =
		lines.map {
			if (it is MenuLine.Line) LINE else it.key
		}

	val shows = entries(prisms.all).associate { it.id to it.show }
	val prismsWithin =
		prisms.all.filterIsInstance<MenuLine.Entry>().associate {
			it.id to
				tokensOf(it.within)
		}

	/** The changes of the [lines] of one list (the menu's, or a submenu's), as [theirs] has them in prism's. */
	fun orderOf(lines: List<MenuLine>, theirs: List<Any>): List<Pair<Any, Change>> {
		val kept =
			kept(tokensOf(lines), theirs) { at ->
				(lines[at] as? MenuLine.Entry)?.id !in
					dragged
			}

		return lines.withIndex().filter { it.index !in kept }.map { (_, line) ->
			line.key to
				if (line is MenuLine.Entry && line.id in shows)
					Change.Moved
				else
					Change.Added
		}
	}

	val order =
		orderOf(menu.all, tokensOf(prisms.all)) +
			menu.all.filterIsInstance<MenuLine.Entry>().flatMap {
				orderOf(it.within, prismsWithin[it.id].orEmpty())
			}

	val shown =
		entries(menu.all).filter { it.id in shows && shows[it.id] != it.show }.map {
			it.key to
				Change.Shown
		}

	return (order + shown)
		.groupBy(
			{ it.first },
			{ it.second },
		).mapValues { it.value.toSet() }
}

/** What a line is to [changesOf]: any line is any other, where it is. */
private const val LINE = "-"

/**
 * The places in [these] of a longest run of them, in their order, that [those] has in the same order; of runs as long,
 * one that keeps the most of those [keep] would rather keep (what the user did not drag).
 */
private fun <T> kept(these: List<T>, those: List<T>, keep: (Int) -> Boolean): Set<Int> {
	// Each kept is worth more than all that could be preferred together, so a longer run is always the better one.
	fun worth(at: Int): Int = these.size + 1 + if (keep(at)) 1 else 0

	// The worth of the best run the rest of each has in common from each pair of places, from the ends back.
	val best = Array(these.size + 1) { IntArray(those.size + 1) }

	for (i in these.indices.reversed())
		for (j in those.indices.reversed())
			best[i][j] =
				maxOf(
					best[i + 1][j],
					best[i][j + 1],
					if (these[i] == those[j])
						worth(i) + best[i + 1][j + 1]
					else
						0,
				)

	tailrec fun walk(i: Int, j: Int, run: Set<Int>): Set<Int> =
		when {
			i == these.size || j == those.size -> run
			these[i] == those[j] && best[i][j] == worth(i) + best[i + 1][j + 1] ->
				walk(
					i + 1,
					j + 1,
					run + i,
				)
			best[i + 1][j] >= best[i][j + 1] -> walk(i + 1, j, run)
			else -> walk(i, j + 1, run)
		}

	return walk(0, 0, emptySet())
}

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
 * The files a menu of [key] can be seen for: one for each group of the extensions, names and types [specs] say its
 * actions are for, each group those the same actions are for (a .zip, a .jar and the rest the zip actions open are one,
 * an .epub that an action of the user's is also for another); for a listing's files only, since a folder's and the
 * space's are no file.
 */
fun samplesOf(
	key: MenuKey,
	actions: Actions,
	specs: Map<String, WhenSpec>,
): List<Sample> {
	if (key !is MenuKey.Listing || (key.of != MenuOf.File && key.of != MenuOf.Several))
		return emptyList()

	val written =
		actions.actions.filter { possibleIn(it, key) }.mapNotNull { action ->
			specs[action.id]?.let {
				action.id to
					it
			}
		}

	/** What the actions are written for, of one kind ([of]), in groups of those the same actions are for, each a [sample]. */
	fun grouped(
		of: (WhenSpec) -> List<String>?,
		sample: (List<String>) -> Sample,
	): List<Sample> =
		written
			.flatMap { (id, spec) -> of(spec).orEmpty().map { it to id } }
			.groupBy({ it.first }, { it.second })
			.entries
			.groupBy({ it.value.toSet() }, { it.key })
			.values
			.map(sample)

	val extensions =
		grouped(WhenSpec::ext) {
			Sample(labelOf(it.map { ext -> ".$ext" }), "file.${it.first()}")
		}

	val names =
		grouped(WhenSpec::names) {
			Sample(labelOf(it), it.first().replace("*", "file").replace("?", "x"))
		}
	val types = grouped(WhenSpec::types) { Sample(labelOf(it), "file", listOf(it.first())) }

	return (extensions + names + types).sortedBy { it.label.lowercase() }
}

/** A group's words, for it to be chosen by: the first few, and how many more. */
private fun labelOf(words: List<String>): String =
	if (words.size <= 3)
		words.joinToString(", ")
	else
		words.take(3).joinToString(", ") + " and ${words.size - 3} more"

/** What each action of prism's and the [base] preferences' is written to be offered for, by its id; a later one's over an earlier. */
fun specsOf(vararg files: ActionsFile): Map<String, WhenSpec> =
	files
		.flatMap { it.actionSpecs() + it.textSearchSpecs() }
		.mapNotNull { (it as? Spec.Read<Declared>)?.value }
		.associate { it.id to it.conditions }

/**
 * Whether [offering] could be offered in the menu [key] at all: for what that menu is of (whatever it is of, where its
 * conditions do not say, as a text search's need not), in that listing, for as many as it is of, of the kind they are;
 * what it is for beyond that (a name, a type) is the file's to decide. A text search looks in files on disk, so it is
 * not offered in a place's listing or the trash's.
 */
private fun possibleIn(offering: Offering, key: MenuKey): Boolean {
	val conditions = offering.conditions

	fun offeredOn(menu: On) = conditions.on == null || conditions.on == menu

	// What a menu of a listing is of is on disk, to search, but in a place's or the trash's.
	val onDisk =
		(key as? MenuKey.Listing)?.kind.let {
			it !is ListingKind.Place &&
				it != ListingKind.Trash
		}

	return when (key) {
		MenuKey.Sidebar -> offeredOn(On.Place)
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
			offeredOn(on) &&
				(offering !is TextSearch || onDisk) &&
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
