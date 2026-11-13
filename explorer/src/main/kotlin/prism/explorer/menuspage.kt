package prism.explorer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import prism.browser.ComboBox
import prism.browser.ContextMenuHost
import prism.browser.ContextMenus
import prism.browser.Emphasis
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.Grip
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.LocalTypes
import prism.browser.Menu
import prism.browser.Moving
import prism.browser.OptionGroup
import prism.browser.PushButton
import prism.browser.Radius
import prism.browser.ReorderableColumn
import prism.browser.ScrollArea
import prism.browser.Section
import prism.browser.Segmented
import prism.browser.Space
import prism.browser.Switch
import prism.browser.Tip
import prism.browser.Toggles
import prism.browser.ToolButton
import prism.browser.World
import prism.browser.contextMenu
import prism.browser.menu
import prism.browser.rememberContextMenus
import prism.preferences.Held
import prism.preferences.ListingKind
import prism.preferences.MenuKey
import prism.preferences.MenuOf
import prism.preferences.MenuSpec
import prism.preferences.Moved
import prism.preferences.Placed
import prism.preferences.Show
import prism.browser.Page as PageLayout

/*
 * The Context menus page: every context menu prism opens, arranged. Down the side, where a menu is (each kind of
 * listing, as the Layout page lists them, and the sidebar); across the top, what it is of (a file, a folder, several,
 * the empty space around them). The menu itself is drawn as a menu is, every entry it can hold in it: dragged by their
 * grips into order, each shown always, only with the keys lit held as the menu opens, or, switched off, never; lines
 * set groups apart, and are added and taken away. It is seen as it would be for a file of a type the user's actions are
 * for, and with keys held, what it would not show then faint.
 */

/** The Context menus page of [settings], the user's actions decided in [world]. */
@Composable
internal fun MenusPage(settings: Settings, world: World, onChange: (Settings) -> Unit) {
	val type = LocalType.current
	val key = settings.menuing
	val place = MenuPlace.of(key)
	val of = (key as? MenuKey.Listing)?.of ?: MenuOf.File
	val menus = settings.menus
	val kinds =
		listOf(ListingKind.Folder, ListingKind.Results, ListingKind.Trash) +
			settings.actions.places.keys
				.sorted()
				.map(ListingKind::Place)
	val (own, places) = kinds.partition { it !is ListingKind.Place }

	fun arrangesItself(place: MenuPlace): Boolean =
		when (place) {
			is MenuPlace.Of ->
				MenuOf.entries.any {
					menus.arrangesItself(MenuKey.Listing(place.kind, it))
				}
			MenuPlace.Sidebar -> menus.arrangesItself(MenuKey.Sidebar)
		}

	PageLayout(
		"Context menus",
		"What each context menu offers, in what order, and when: always, " +
			"only with a key held as it opens, or never. Applied, a change " +
			"reaches every window.",
		scrolls = false,
	) {
		Row(
			Modifier.weight(1f).fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(Space.xl),
		) {
			ChoiceList(
				listOf(
					"Prism's own" to own.map(MenuPlace::Of),
					"Places" to places.map(MenuPlace::Of),
					"Elsewhere" to listOf(MenuPlace.Sidebar),
				),
				place,
				::labelOf,
				::glyphOf,
				marked = ::arrangesItself,
				tag = "menus",
				hint = "Find a menu",
				Modifier.width(240.dp).fillMaxHeight(),
			) { onChange(settings.copy(menuing = keyOf(it, of), seenFor = null)) }
			// Another menu chosen is seen from its top.
			ScrollArea(Modifier.weight(1f).fillMaxHeight(), remember(key) { ScrollState(0) }) {
				Column(verticalArrangement = Arrangement.spacedBy(Space.xl)) {
					Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
						// The menu's name, and what puts it back; under it, where the page is too narrow for both.
						FlowRow(
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalArrangement = Arrangement.spacedBy(Space.s),
							itemVerticalAlignment = Alignment.CenterVertically,
						) {
							// Kept apart from the buttons where they are all on one line and fill it.
							Text(
								labelOf(place),
								style = type.title,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.padding(end = Space.l),
							)
							Row(
								horizontalArrangement = Arrangement.spacedBy(Space.s),
							) { Resets(settings, onChange) }
						}
						// Where the page is too narrow for both, what the menu is opened on goes under its label.
						if (place is MenuPlace.Of)
							FlowRow(
								horizontalArrangement = Arrangement.spacedBy(Space.m),
								verticalArrangement = Arrangement.spacedBy(Space.s),
								itemVerticalAlignment = Alignment.CenterVertically,
							) {
								Text("Opened on", style = type.muted)
								Segmented(MenuOf.entries, of, { it.label }, tag = "menu-of") {
									onChange(settings.copy(menuing = keyOf(place, it), seenFor = null))
								}
							}
					}
					MenuSection(settings, world, onChange)
				}
			}
		}
	}
}

/**
 * What takes changes back: every change to the context menus not yet applied ("Cancel"); and what puts prism's
 * arrangement back, of the menu chosen where it has its own, and of every menu, with every entry hidden. Each says, as
 * the pointer rests on it, what it undoes; none is kept until applied, and each is undone as any change is.
 */
@Composable
private fun Resets(settings: Settings, onChange: (Settings) -> Unit) {
	val key = settings.menuing
	val menus = settings.menus
	val itself = menus.arrangesItself(key)
	val follows = key is MenuKey.Listing && key.kind != ListingKind.Folder

	Tip(
		"Undo every change to the context menus not yet applied: each menu as it was when last applied.",
	) {
		PushButton(
			"Cancel",
			Emphasis.Secondary,
			enabled = menus != settings.base.menu,
			Modifier.testTag("cancel-menus"),
		) {
			onChange(settings.copy(menus = settings.base.menu))
		}
	}

	Tip(
		when {
			itself ->
				"Forget this menu's own arrangement, and arrange it as prism does: its " +
					"order, its lines, and when each entry shows."
			follows ->
				"This menu is arranged as a folder's: it has nothing of its own to forget."
			else -> "This menu is arranged as prism arranges it already."
		},
	) {
		PushButton(
			"Reset menu",
			Emphasis.Secondary,
			enabled = itself,
			Modifier.testTag("reset-menu"),
		) {
			onChange(settings.copy(menus = menus.arranging(key, null)))
		}
	}
	Tip(
		"Forget how every context menu is arranged, and every entry hidden: each as " +
			"prism arranges it.",
	) {
		PushButton(
			"Reset all menus",
			Emphasis.Secondary,
			enabled = menus != MenuSpec(),
			Modifier.testTag("reset-menus"),
		) {
			onChange(settings.copy(menus = MenuSpec()))
		}
	}
}

/** The menu chosen, as it is arranged, and the ways of seeing it: for a file of a type, with keys held. */
@Composable
private fun MenuSection(settings: Settings, world: World, onChange: (Settings) -> Unit) {
	val type = LocalType.current
	val key = settings.menuing
	val menus = settings.menus
	val menu =
		remember(key, menus, settings.actions, settings.seenFor, world) {
			seenMenuOf(key, menus, settings.actions, settings.specs, settings.seenFor, world)
		}

	// The same menu as prism arranges it, for what differs from it to be shown.
	val changes =
		remember(menu, settings.actions, settings.seenFor, world, settings.dragged) {
			changesOf(
				menu,
				seenMenuOf(
					key,
					MenuSpec(menus.hidden),
					settings.actions,
					settings.specs,
					settings.seenFor,
					world,
				),
				settings.dragged,
			)
		}

	val samples =
		remember(key, settings.actions) { samplesOf(key, settings.actions, settings.specs) }
	// A listing's menu other than a folder's is arranged as a folder's is, until it is arranged itself.
	val follows = (key as? MenuKey.Listing)?.kind?.takeIf { it != ListingKind.Folder }
	val itself = menus.arrangesItself(key)
	val following =
		if (follows != null &&
			!itself
		)
			" Until it has its own arrangement, it is arranged as a folder's."
		else
			""
	val editing = rememberContextMenus<Edit>()

	fun arranged(placed: List<Placed>, dragged: Set<String> = emptySet()) =
		onChange(
			settings.copy(
				menus = menus.arranging(key, placed),
				dragged =
					settings.dragged + dragged,
			),
		)

	/** The ids of the entries of [lines] that [move] moves: what the user dragged. */
	fun draggedBy(lines: List<MenuLine>, move: Moved): Set<String> =
		move.from.mapNotNull { (lines.getOrNull(it) as? MenuLine.Entry)?.id }.toSet()

	fun edited(edit: Edit) =
		arranged(
			when (edit) {
				is Edit.Line -> lined(menu, edit.at, edit.side)
				is Edit.Unline -> unlined(menu, edit.at)
			},
		)

	Section(
		"Entries",
		description =
			"Drag an entry to move it, anywhere on it but its keys and switch; a click " +
				"chooses one, and Shift and a click more, to move together. Right-click one " +
				"to put a line before or after it. A lit " +
				"key keeps an entry for when that key is held as the menu opens; switched " +
				"off, it never shows. Faint entries wait for keys not held here; tinted " +
				"ones differ from prism's own arrangement." + following,
		trailing =
			follows?.let {
				{
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.s),
					) {
						Text("Own arrangement", style = type.muted, maxLines = 1, softWrap = false)
						Switch(itself, Modifier.testTag("own-menu")) { own ->
							onChange(
								settings.copy(menus = menus.arranging(key, if (own) placedOf(menu) else null)),
							)
						}
					}
				}
			},
	) {
		Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
			Seeing(settings, samples, onChange)
			MenuEditor(
				seen = menu,
				changes,
				settings,
				editing,
				onMove = { arranged(moved(menu, it), draggedBy(menu.shown, it)) },
				onMoveWithin = { submenu, move ->
					val items =
						menu.shown
							.filterIsInstance<MenuLine.Entry>()
							.firstOrNull {
								it.id ==
									submenu
							}?.within
							.orEmpty()

					arranged(movedWithin(menu, submenu, move), draggedBy(items, move))
				},
				onShow = { id, show -> arranged(showing(menu, id, show)) },
				onEdit = ::edited,
			)
			if (menu.unshown > 0)
				Text(
					if (settings.seenFor == null)
						"${menu.unshown} more are only for particular kinds of file: " +
							"choose one in Seen for to arrange them."
					else
						"${menu.unshown} more are for other kinds of file.",
					style = type.muted,
					modifier = Modifier.widthIn(max = MENU_WIDTH).testTag("unshown"),
				)
		}
	}
	ContextMenuHost(editing, ::edited)
}

/** What a right-click on the menu being arranged offers: a line put beside what was clicked, or one taken away. */
private sealed interface Edit {
	data class Line(
		val at: Any,
		val side: Side,
	) : Edit

	data class Unline(
		val at: Int,
	) : Edit
}

/** How the menu is seen: for a file of a type the user's actions are for, and with keys held as it opens. */
@Composable
private fun Seeing(
	settings: Settings,
	samples: List<Sample>,
	onChange: (Settings) -> Unit,
) {
	val type = LocalType.current

	// The keys go under the file seen for where the page is too narrow for both.
	FlowRow(
		Modifier.widthIn(max = MENU_WIDTH).fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalArrangement = Arrangement.spacedBy(Space.s),
		itemVerticalAlignment = Alignment.CenterVertically,
	) {
		if (samples.isNotEmpty())
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.s),
			) {
				Text("Seen for", style = type.muted)
				ComboBox(
					listOf(OptionGroup(listOf<Sample?>(null)), OptionGroup(samples)),
					settings.seenFor,
					{ it?.label ?: "All files" },
					Modifier.width(200.dp),
					tag = "seen-for",
					hint = "An extension or a type",
				) { onChange(settings.copy(seenFor = it)) }
			}
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
		) {
			Text("Keys held", style = type.muted)
			Toggles(Held.entries, settings.holding, { it.label }, tag = "holding") { held ->
				onChange(
					settings.copy(
						holding =
							if (held in
								settings.holding
							)
								settings.holding - held
							else
								settings.holding + held,
					),
				)
			}
		}
	}
}

/** The widest the menu is drawn: a menu's width and the room its controls take, not the page's whole width. */
private val MENU_WIDTH = 680.dp

/** How far a submenu's items are drawn in from its own row. */
private val NESTED = 28.dp

/**
 * [seen] drawn as a menu is, on a menu's surface, what each says in a menu's type: each entry in its place, faint where
 * the menu as [settings] see it would not show it for the keys held, tinted where it differs from prism's arrangement
 * ([changes]), its keys and its switch at its end; a submenu with its items under it, folded away by its arrow. Several
 * are chosen by Shift and a click, and dragged together; a submenu's items among themselves.
 */
@Composable
private fun MenuEditor(
	seen: SeenMenu,
	changes: Map<Any, Set<Change>>,
	settings: Settings,
	editing: ContextMenus<Edit>,
	onMove: (Moved) -> Unit,
	onMoveWithin: (submenu: String, Moved) -> Unit,
	onShow: (id: String, show: Show) -> Unit,
	onEdit: (Edit) -> Unit,
) {
	val palette = LocalPalette.current
	val shape = RoundedCornerShape(Radius.large)
	val held = settings.holding
	var folded by remember(settings.menuing) { mutableStateOf(emptySet<String>()) }

	/** What a right-click on [line] offers: a line on either side, where it would set something apart; or, on a line, its going. */
	fun offered(line: MenuLine): Menu<Edit> =
		menu {
			when (line) {
				is MenuLine.Line -> item("Remove line", Edit.Unline(line.at))
				is MenuLine.Entry -> {
					item(
						"Insert line before",
						Edit.Line(line.key, Side.Before),
						enabled = linable(seen, line.key, Side.Before),
					)
					item(
						"Insert line after",
						Edit.Line(line.key, Side.After),
						enabled = linable(seen, line.key, Side.After),
					)
				}
			}
		}

	Box(
		Modifier
			.widthIn(max = MENU_WIDTH)
			.fillMaxWidth()
			.shadow(8.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
			.background(palette.overlay, shape)
			.border(1.dp, palette.border, shape)
			.padding(Space.xs)
			.testTag("menu-editor"),
	) {
		ReorderableColumn(
			seen.shown,
			key = MenuLine::key,
			moving = Moving.Several,
			onMove = onMove,
		) { line, grip ->
			val right = Modifier.contextMenu(editing) { offered(line) }

			when (line) {
				is MenuLine.Line ->
					LineRow(line, grip, right, changes[line.key].orEmpty()) {
						onEdit(Edit.Unline(line.at))
					}
				is MenuLine.Entry -> {
					val shown = line.shownWith(held)
					val open = line.id !in folded

					Column(Modifier.fillMaxWidth()) {
						EntryRow(
							line,
							grip,
							right,
							shown,
							changes[line.key].orEmpty(),
							nested = false,
							fold =
								Fold.of(line, open) {
									folded =
										if (open)
											folded + line.id
										else
											folded - line.id
								},
						) { onShow(line.id, it) }
						if (open && line.within.isNotEmpty())
							ReorderableColumn(
								line.within,
								key = MenuLine.Entry::id,
								moving = Moving.Several,
								onMove = { onMoveWithin(line.id, it) },
							) { item, itemGrip ->
								EntryRow(
									item,
									itemGrip,
									Modifier,
									shown && item.shownWith(held),
									changes[item.key].orEmpty(),
									nested = true,
									fold = null,
								) { onShow(item.id, it) }
							}
					}
				}
			}
		}
	}
}

/** A submenu's arrow: whether its items are [open], and what folds or unfolds them. */
private class Fold(
	val open: Boolean,
	val toggle: () -> Unit,
) {
	companion object {
		/** [entry]'s arrow, where it is a submenu: none for anything else. */
		fun of(entry: MenuLine.Entry, open: Boolean, toggle: () -> Unit): Fold? =
			Fold(open, toggle).takeIf { entry.kind == EntryKind.Submenu }
	}
}

/**
 * The background of a row: lit where it is chosen or dragged, tinted where it differs from prism's arrangement, with a
 * mark at its start; nothing otherwise.
 */
@Composable
private fun Modifier.rowed(lit: Boolean, changed: Boolean): Modifier {
	val palette = LocalPalette.current
	val shape = RoundedCornerShape(Radius.medium)

	return when {
		lit -> background(palette.chosen, shape)
		changed ->
			background(palette.accent.copy(alpha = 0.10f), shape)
				.drawBehind { drawRect(palette.accent, size = Size(3.dp.toPx(), size.height)) }
		else -> this
	}
}

/** A line between groups: its grip, the line, and what takes it away; tinted where prism's has none there. */
@Composable
private fun LineRow(
	line: MenuLine.Line,
	grip: Grip,
	right: Modifier,
	changes: Set<Change>,
	onRemove: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Row(
		Modifier
			.fillMaxWidth()
			.then(grip.entry)
			.then(right)
			.rowed(grip.lit, changes.isNotEmpty())
			.testTag("line:${line.at}")
			.padding(horizontal = Space.s),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		GlyphIcon(
			Glyph.Grip,
			palette.textFaint,
			type.icon,
			Modifier.testTag("grip:line:${line.at}"),
		)
		// Every line drawn alike: whether the menu draws it is the entries' to say, faint or not.
		Box(Modifier.weight(1f).height(1.dp).background(palette.borderStrong))
		ToolButton(
			Glyph.Close,
			"Take this line away",
			enabled = true,
			Modifier.testTag("unline:${line.at}"),
			size = type.control * 0.8f,
			icon = type.icon * 0.8f,
			onClick = onRemove,
		)
	}
}

/**
 * An entry: its grip, what it says in a menu's type (faint where the menu as seen would not show it, [shown]), a
 * submenu's arrow that folds its items away ([fold]), what it is for where that is not everything, and how it differs
 * from prism's arrangement; then its keys and its switch. A submenu's item ([nested]) is drawn in from the submenu.
 */
@Composable
private fun EntryRow(
	entry: MenuLine.Entry,
	grip: Grip,
	right: Modifier,
	shown: Boolean,
	changes: Set<Change>,
	nested: Boolean,
	fold: Fold?,
	onShow: (Show) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	// What it says is set as the menu sets it; the controls beside it, as the page's are.
	val menu = LocalTypes.current.menus

	Row(
		Modifier
			.fillMaxWidth()
			.padding(start = if (nested) NESTED else 0.dp)
			.then(grip.entry)
			.then(right)
			.rowed(grip.lit, changes.isNotEmpty())
			.heightIn(min = menu.row)
			.testTag("entry:${entry.id}")
			.padding(start = Space.s, end = Space.xs),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		GlyphIcon(
			Glyph.Grip,
			palette.textFaint,
			type.icon,
			Modifier.testTag("grip:${entry.id}"),
		)
		Column(Modifier.weight(1f).padding(vertical = Space.xs)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.xs),
			) {
				Text(
					entry.label,
					style = menu.body.copy(color = if (shown) palette.text else palette.textFaint),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				fold?.let { Arrow(entry, it, shown) }
			}
			if (entry.note != null || changes.isNotEmpty())
				Text(
					buildAnnotatedString {
						entry.note?.let { append(it) }
						changes.sortedBy { it.ordinal }.forEachIndexed { at, change ->
							if (at > 0 || entry.note != null)
								append(" · ")
							withStyle(SpanStyle(color = palette.accentText)) { append(change.label) }
						}
					},
					style = type.muted.copy(color = palette.textFaint),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
		}
		Toggles(
			Held.entries,
			(entry.show as? Show.With)?.keys.orEmpty(),
			{ it.label },
			tag = "key:${entry.id}",
			height = type.row - Space.xs,
		) { onShow(toggled(entry.show, it)) }
		Switch(entry.show != Show.Never, Modifier.testTag("shown:${entry.id}")) { on ->
			onShow(if (on) Show.Always else Show.Never)
		}
	}
}

/** A submenu's arrow, beside its name: pointing down while its items are shown, aside while they are folded away. */
@Composable
private fun Arrow(entry: MenuLine.Entry, fold: Fold, shown: Boolean) {
	val palette = LocalPalette.current
	val menu = LocalTypes.current.menus
	val label =
		if (fold.open)
			"Fold its items away"
		else
			"Show its items"

	Tip(label) {
		Box(
			Modifier
				.size(menu.icon)
				.clip(RoundedCornerShape(Radius.small))
				.clickable(onClickLabel = label, onClick = fold.toggle)
				.testTag("fold:${entry.id}"),
			contentAlignment = Alignment.Center,
		) {
			GlyphIcon(
				if (fold.open)
					Glyph.ChevronDown
				else
					Glyph.Chevron,
				if (shown)
					palette.textMuted
				else
					palette.textFaint,
				menu.icon * 0.8f,
			)
		}
	}
}

/** What a place a menu is in is called. */
private fun labelOf(place: MenuPlace): String =
	when (place) {
		is MenuPlace.Of -> labelOf(place.kind)
		MenuPlace.Sidebar -> "Sidebar"
	}

/** The glyph a place a menu is in is listed by. */
private fun glyphOf(place: MenuPlace): Glyph =
	when (place) {
		is MenuPlace.Of -> glyphOf(place.kind)
		MenuPlace.Sidebar -> Glyph.Home
	}
