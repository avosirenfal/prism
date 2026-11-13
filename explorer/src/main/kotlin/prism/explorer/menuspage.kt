package prism.explorer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import prism.browser.Act
import prism.browser.ButtonRow
import prism.browser.ComboBox
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.LocalTypes
import prism.browser.OptionGroup
import prism.browser.Radius
import prism.browser.ReorderableColumn
import prism.browser.ScrollArea
import prism.browser.Section
import prism.browser.Segmented
import prism.browser.Space
import prism.browser.Switch
import prism.browser.Toggles
import prism.browser.ToolButton
import prism.browser.World
import prism.preferences.Held
import prism.preferences.ListingKind
import prism.preferences.MenuKey
import prism.preferences.MenuOf
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
						Text(
							labelOf(place),
							style = type.title,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						if (place is MenuPlace.Of)
							Row(
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(Space.m),
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

/** The menu chosen, as it is arranged, and the ways of seeing it: for a file of a type, with keys held. */
@Composable
private fun MenuSection(settings: Settings, world: World, onChange: (Settings) -> Unit) {
	val type = LocalType.current
	val key = settings.menuing
	val menus = settings.menus
	val lines =
		remember(key, menus, settings.actions, settings.seenFor, world) {
			menuLinesOf(key, menus, settings.actions, settings.specs, settings.seenFor, world)
		}

	val samples =
		remember(key, settings.actions) { samplesOf(key, settings.actions, settings.specs) }
	// A listing's menu other than a folder's is arranged as a folder's is, until it is arranged itself.
	val follows = (key as? MenuKey.Listing)?.kind?.takeIf { it != ListingKind.Folder }
	val itself = menus.arrangesItself(key)
	val following =
		if (follows != null && !itself)
			" Until it has its own arrangement, this menu is arranged as a folder's."
		else
			""

	fun arranged(placed: List<Placed>) =
		onChange(settings.copy(menus = menus.arranging(key, placed)))

	Section(
		"Entries",
		description =
			"Drag an entry by its grip to move it. A lit key keeps it for when " +
				"that key is held as the menu opens; switched off, it never shows. " +
				"Faint entries are not in the menu as it is seen here." +
				following,
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
								settings.copy(menus = menus.arranging(key, if (own) placedOf(lines) else null)),
							)
						}
					}
				}
			},
	) {
		Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
			Seeing(settings, samples, onChange)
			MenuEditor(
				lines,
				settings,
				onMove = { from, to -> arranged(moved(lines, from, to)) },
				onShow = { id, show -> arranged(showing(lines, id, show)) },
				onUnline = { arranged(unlined(lines, it)) },
			)
			ButtonRow(
				null,
				Modifier.widthIn(max = MENU_WIDTH),
				aside =
					listOf(
						Act("Add a line", "add-line") { arranged(lined(lines)) },
						Act("Put prism's back", "reset-menu", enabled = itself) {
							onChange(settings.copy(menus = menus.arranging(key, null)))
						},
					),
			)
		}
	}
}

/** How the menu is seen: for a file of a type the user's actions are for, and with keys held as it opens. */
@Composable
private fun Seeing(
	settings: Settings,
	samples: List<Sample>,
	onChange: (Settings) -> Unit,
) {
	val type = LocalType.current
	val key = settings.menuing
	val any =
		when ((key as? MenuKey.Listing)?.of) {
			MenuOf.Several -> "Any files"
			else -> "Any file"
		}

	Row(
		Modifier.widthIn(max = MENU_WIDTH).fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		if (samples.isNotEmpty()) {
			Text("Seen for", style = type.muted)
			ComboBox(
				listOf(OptionGroup(listOf<Sample?>(null)), OptionGroup(samples)),
				settings.seenFor,
				{ it?.label ?: any },
				Modifier.width(200.dp),
				tag = "seen-for",
				hint = "An extension or a type",
			) { onChange(settings.copy(seenFor = it)) }
		}
		Spacer(Modifier.weight(1f))
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

/** The widest the menu is drawn: a menu's width and the room its controls take, not the page's whole width. */
private val MENU_WIDTH = 680.dp

/**
 * [lines] drawn as a menu is, on a menu's surface, what each says in a menu's type: each entry in its place, faint where
 * the menu as [settings] see it would not show it, its keys and its switch at its end; a submenu with its items under it.
 */
@Composable
private fun MenuEditor(
	lines: List<MenuLine>,
	settings: Settings,
	onMove: (from: Int, to: Int) -> Unit,
	onShow: (id: String, show: Show) -> Unit,
	onUnline: (at: Int) -> Unit,
) {
	val palette = LocalPalette.current
	val shape = RoundedCornerShape(Radius.large)
	val held = settings.holding
	val drawn = drawnLines(lines, held)

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
			lines,
			key = { line ->
				when (line) {
					is MenuLine.Line -> "line:${line.at}"
					is MenuLine.Entry -> line.id
				}
			},
			onMove = onMove,
		) { line, handle, dragged ->
			Column(
				Modifier
					.fillMaxWidth()
					.background(
						if (dragged)
							palette.chosen
						else
							Color.Transparent,
						RoundedCornerShape(Radius.medium),
					),
			) {
				when (line) {
					is MenuLine.Line -> LineRow(line, line.at in drawn, handle) { onUnline(line.at) }
					is MenuLine.Entry -> {
						val shown = line.shownWith(held)

						EntryRow(line, handle, shown, nested = false) { onShow(line.id, it) }
						for (item in line.within)
							EntryRow(item, Modifier, shown && item.shownWith(held), nested = true) {
								onShow(item.id, it)
							}
					}
				}
			}
		}
	}
}

/** A line between groups: its grip, the line, faint where the menu would not draw it, and what takes it away. */
@Composable
private fun LineRow(
	line: MenuLine.Line,
	drawn: Boolean,
	handle: Modifier,
	onRemove: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Row(
		Modifier
			.fillMaxWidth()
			.testTag("line:${line.at}")
			.padding(horizontal = Space.s),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		GlyphIcon(
			Glyph.Grip,
			palette.textFaint,
			type.icon,
			handle.testTag("grip:line:${line.at}"),
		)
		Box(
			Modifier
				.weight(1f)
				.height(1.dp)
				.background(
					if (drawn)
						palette.borderStrong
					else
						palette.divider,
				),
		)
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
 * An entry: its grip (none for a submenu's item, which moves with its submenu), what it says, faint where the menu as
 * seen would not show it ([shown]), and what it is for where it is not for everything; then its keys and its switch.
 */
@Composable
private fun EntryRow(
	entry: MenuLine.Entry,
	handle: Modifier,
	shown: Boolean,
	nested: Boolean,
	onShow: (Show) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	// What it says is set as the menu sets it; the controls beside it, as the page's are.
	val menu = LocalTypes.current.menus

	Row(
		Modifier
			.fillMaxWidth()
			.heightIn(min = menu.row)
			.testTag("entry:${entry.id}")
			.padding(
				start =
					if (nested)
						type.icon + Space.l
					else
						Space.s,
				end = Space.xs,
			),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		if (!nested)
			GlyphIcon(Glyph.Grip, palette.textFaint, type.icon, handle.testTag("grip:${entry.id}"))
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
				if (entry.kind == EntryKind.Submenu)
					GlyphIcon(
						Glyph.Chevron,
						if (shown)
							palette.textMuted
						else
							palette.textFaint,
						menu.icon * 0.8f,
					)
			}
			entry.note?.let {
				Text(
					it,
					style = type.muted.copy(color = palette.textFaint),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
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
