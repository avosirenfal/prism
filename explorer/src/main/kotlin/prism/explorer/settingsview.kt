package prism.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import prism.browser.Act
import prism.browser.Action
import prism.browser.Actions
import prism.browser.Bindable
import prism.browser.Bindings
import prism.browser.ButtonRow
import prism.browser.Card
import prism.browser.CardItem
import prism.browser.CheckBox
import prism.browser.Chip
import prism.browser.Chord
import prism.browser.ComboBox
import prism.browser.Commands
import prism.browser.Dims
import prism.browser.Divider
import prism.browser.EmptyState
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.Labelled
import prism.browser.LazyScrollArea
import prism.browser.ListHeading
import prism.browser.ListRow
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Moving
import prism.browser.Notice
import prism.browser.OptionGroup
import prism.browser.ProcessCommands
import prism.browser.Radio
import prism.browser.Radius
import prism.browser.ReorderableColumn
import prism.browser.Scope
import prism.browser.ScrollArea
import prism.browser.Section
import prism.browser.Segmented
import prism.browser.Select
import prism.browser.SettingRow
import prism.browser.Space
import prism.browser.Stepper
import prism.browser.Switch
import prism.browser.Tabs
import prism.browser.Tone
import prism.browser.ToolButton
import prism.browser.World
import prism.browser.mouseChords
import prism.browser.systemFontFamilies
import prism.browser.tabular
import prism.desktop.TerminalChoice
import prism.desktop.TerminalCommand
import prism.desktop.TerminalOption
import prism.desktop.TerminalOptions
import prism.preferences.Align
import prism.preferences.ColumnId
import prism.preferences.DateFormat
import prism.preferences.Filters
import prism.preferences.Kind
import prism.preferences.Layout
import prism.preferences.ListingKind
import prism.preferences.Look
import prism.preferences.Order
import prism.preferences.Role
import prism.preferences.Rows
import prism.preferences.Sort
import prism.preferences.TextPart
import prism.preferences.Typography
import prism.window.GraphicsReport
import prism.window.WindowLocals
import prism.browser.Page as PageLayout

/*
 * The preferences window. Its edits are one value ([Settings]) that the window replaces with each change and applies
 * when the user does; the window only draws it and says what was done to it. Its pages are made of the layouts every
 * window of prism is (layout.kt): a page of sections, each a card of rows, each row a setting and its control.
 *
 * On the bindings page, a binding clicked waits for a chord: whatever is pressed next, a key with what modifiers are
 * held, or a mouse button (middle, back or forward), is taken; Escape stops waiting instead.
 */

/**
 * The preferences window over [settings], each change reported to [onChange]: applied by [onApply], and left by
 * [onClose]. [mouseChords] are the mouse's buttons pressed over the window, which a binding waiting for a chord takes.
 * The Renderer page shows [graphics] (while it is looked at, null), and copies its report by [onCopy]. The Context
 * menus page decides the user's actions in [world], as the menus it shows would. The keys for undo and redo, wherever
 * nothing that has the keyboard takes them (a field's own typing), are [onUndo] and [onRedo]. An action or a text
 * search tried out from the window that edits it is run by [commands].
 */
@Composable
fun SettingsView(
	settings: Settings,
	onChange: (Settings) -> Unit,
	onApply: () -> Unit,
	onClose: () -> Unit,
	mouseChords: Flow<Chord> = emptyFlow(),
	terminalPreview: (TerminalChoice, List<String>?) -> TerminalCommand = { _, _ ->
		TerminalCommand.Failed("a terminal", "No desktop session is available.")
	},
	terminals: TerminalOptions = TerminalOptions.NONE,
	graphics: GraphicsReport? = null,
	onCopy: (String) -> Unit = {},
	world: World = World.Bare(Path.of(System.getProperty("user.home"))),
	onUndo: () -> Unit = {},
	onRedo: () -> Unit = {},
	commands: Commands = ProcessCommands,
) {
	// The keys as they are applied, not as the bindings page is changing them.
	val bindings = remember(settings.base) { Bindings.of(settings.base) }
	val filter = remember { FocusRequester() }

	SettingsFrame(
		Page.entries,
		settings.page,
		Page::label,
		::glyphOf,
		onPage = { onChange(settings.copy(page = it)) },
		rebinding = settings.rebound.takeIf { settings.page == Page.Bindings },
		onRebinding = { onChange(settings.rebound(it)) },
		changed = settings.changed,
		onApply = onApply,
		onClose = onClose,
		mouseChords = mouseChords,
		keys = bindings,
		onUndo = onUndo,
		onRedo = onRedo,
		filter = filter,
		blocked = settings.blocked,
		finds = settings.page in setOf(Page.Bindings, Page.Actions, Page.Searches),
	) { page ->
		when (page) {
			Page.Display -> DisplayPage(settings, onChange)
			Page.Renderer -> RendererPage(graphics, onCopy)
			Page.Layout -> LayoutPage(settings, onChange)
			Page.Menus -> MenusPage(settings, world, onChange)
			Page.Terminal -> TerminalPage(settings, onChange, terminalPreview, terminals)
			Page.Bindings -> ExplorerBindingsPage(settings, filter, onChange)
			Page.Aliases ->
				AliasesPage(
					settings.aliasing,
				) { onChange(settings.copy(aliasing = it)) }
			Page.Indexing ->
				ExcludingPage(
					settings.excluding,
				) { onChange(settings.copy(excluding = it)) }
			Page.Actions -> ScriptsPage(ScriptKind.Action, settings, world, filter, onChange)
			Page.Searches -> SearchesPage(settings, world, filter, onChange)
		}
	}
	// Whatever is open over the pages of actions and text searches: one being made, changed or moved.
	ActingModals(settings, world, commands, onChange)
}

/**
 * The explorer's bindings page: an explorer's keys, or, on its other tab, a picker's, which are an explorer's until
 * pickers are given their own.
 */
@Composable
private fun ExplorerBindingsPage(
	settings: Settings,
	filter: FocusRequester,
	onChange: (Settings) -> Unit,
) {
	BindingsPage(
		settings.rebound,
		filter,
		onChange = { onChange(settings.rebound(it)) },
		empty = {
			EmptyState(
				Glyph.Keyboard,
				"Pickers use the explorer's shortcuts",
				"Turn on separate shortcuts to change them.",
				Modifier.fillMaxSize(),
			)
		},
	) {
		Tabs(
			KeyTab.entries,
			settings.keys,
			KeyTab::label,
			Modifier.testTag("key-tabs"),
		) { onChange(settings.copy(keys = it)) }
		if (settings.keys == KeyTab.Picker)
			Card {
				SettingRow(
					"Separate shortcuts for pickers",
					description = "When off, file pickers use the explorer's shortcuts.",
				) {
					Switch(settings.picking != null, Modifier.testTag("picker-keys-own")) { own ->
						onChange(settings.pickersOwn(own))
					}
				}
			}
	}
}

/** The glyph a page is listed by. */
private fun glyphOf(page: Page): Glyph =
	when (page) {
		Page.Display -> Glyph.Desktop
		Page.Renderer -> Glyph.Chip
		Page.Layout -> Glyph.ViewList
		Page.Menus -> Glyph.Menu
		Page.Terminal -> Glyph.Terminal
		Page.Bindings -> Glyph.Keyboard
		Page.Aliases -> Glyph.At
		Page.Indexing -> Glyph.Filter
		Page.Actions -> Glyph.Bolt
		Page.Searches -> Glyph.Search
	}

/** What each part's text is, as the Display page says it. */
private fun descriptionOf(part: TextPart): String =
	when (part) {
		TextPart.General -> "Toolbar, sidebar and dialogs"
		TextPart.Listing -> "Rows and gallery tiles"
		TextPart.Menus -> "Context menus and the window menu"
	}

/**
 * How things are drawn: each part's font and size; the list's rows; how large a new window's listing is drawn, and the
 * sidebar; and how dates are written.
 */
@Composable
private fun DisplayPage(settings: Settings, onChange: (Settings) -> Unit) {
	val type = LocalType.current
	val typography = settings.typography
	val scales = settings.scales
	val families = remember { listOf<String?>(null) + systemFontFamilies() }

	fun typed(typography: Typography) = onChange(settings.copy(typography = typography))

	PageLayout(
		"Display",
	) {
		Section(
			"Text",
			description = "Font and size for each part of a window.",
		) {
			Card {
				TextPart.entries.forEach { part ->
					val points = typography.pointsOf(part)

					SettingRow(part.label, description = descriptionOf(part)) {
						Select(
							families,
							typography[part].family,
							{ it ?: "Default font" },
							Modifier.width(220.dp).testTag("font:${part.label}"),
						) { typed(typography.with(part, typography[part].copy(family = it))) }
						Stepper(
							"${points.toString().removeSuffix(".0")} pt",
							points > Typography.SMALLEST,
							points < Typography.LARGEST,
							tag = "size:${part.name}",
						) { typed(typography.stepped(part, it)) }
					}
				}
			}
		}
		Section("File list") {
			Card {
				SettingRow("Row size", description = "Larger rows have larger icons.") {
					Segmented(Rows.entries, settings.rows, {
						if (it ==
							Rows.Larger
						) "Large" else "Compact"
					}, tag = "rows") {
						onChange(settings.copy(rows = it))
					}
				}
			}
		}
		Section(
			"Zoom",
			description = "Default zoom for new windows. Ctrl+scroll changes it in any window.",
		) {
			Card {
				ZoomRow(
					"Explorer windows",
					"File list in explorer windows",
					scales.explorer,
					LISTING_STEPS,
				) {
					onChange(settings.copy(scales = scales.copy(explorer = it)))
				}
				ZoomRow(
					"File pickers",
					"File list in file pickers",
					scales.picker,
					LISTING_STEPS,
				) {
					onChange(settings.copy(scales = scales.copy(picker = it)))
				}
				ZoomRow(
					"Sidebar",
					"Quick access and drives",
					scales.sidebar,
					SIDEBAR_STEPS,
				) {
					onChange(settings.copy(scales = scales.copy(sidebar = it)))
				}
			}
		}
		Section(
			"Dates",
			description = "Date and time format in the file list.",
		) {
			val now = remember { java.time.Instant.now() }

			Card {
				DateFormat.entries.forEach { format ->
					val source = remember { MutableInteractionSource() }
					val hovered by source.collectIsHoveredAsState()

					Row(
						Modifier
							.fillMaxWidth()
							.background(
								if (hovered)
									LocalPalette.current.hover
								else
									Color.Transparent,
							).clickable(source, indication = null) { onChange(settings.copy(dates = format)) }
							.padding(prism.browser.CardInsets)
							.testTag("dates:${format.name}"),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.m),
					) {
						Radio(settings.dates == format)
						Text(format.label, style = type.body, modifier = Modifier.weight(1f))
						Text(format.write(now), style = type.muted.tabular())
					}
				}
			}
		}
	}
}

/** A size, as a percentage, stepped through [steps]: from the nearest of them, to the next smaller or larger. */
@Composable
private fun ZoomRow(
	title: String,
	description: String,
	scale: Float,
	steps: List<Float>,
	onScale: (Float) -> Unit,
) {
	val at = steps.indices.minBy { kotlin.math.abs(steps[it] - scale) }

	SettingRow(title, description = description) {
		Stepper(
			"${(steps[at] * 100).roundToInt()} %",
			at > 0,
			at < steps.lastIndex,
			tag = "zoom:$title",
		) {
			onScale(steps[(at + it).coerceIn(0, steps.lastIndex)])
		}
	}
}

/** The sizes a listing starts at: the steps Ctrl and the wheel zoom a list through. */
private val LISTING_STEPS = listOf(0.75f, 0.875f, 1f, 1.25f, 1.5f, 2f)

/** The sizes the sidebar is drawn at: the steps a window is zoomed through. */
private val SIDEBAR_STEPS = WINDOW_STEPS

/** What a listing of [kind] is called. */
internal fun labelOf(kind: ListingKind): String =
	when (kind) {
		ListingKind.Folder -> "Folders"
		ListingKind.Results -> "Search results"
		ListingKind.Trash -> "Trash"
		// A place's type as its declaration names it, "archive.7z": the kind, and which of it.
		is ListingKind.Place ->
			kind.type.split('.', limit = 2).let { parts ->
				parts[0].replaceFirstChar { it.uppercase() } +
					parts.getOrNull(1)?.let { " ($it)" }.orEmpty()
			}
	}

/** The glyph a listing of [kind] is listed by. */
internal fun glyphOf(kind: ListingKind): Glyph =
	when (kind) {
		ListingKind.Folder -> Glyph.Folder
		ListingKind.Results -> Glyph.Search
		ListingKind.Trash -> Glyph.Trash
		is ListingKind.Place -> Glyph.Drive
	}

/**
 * How each kind of listing looks as a window opens, and what it leaves out: a folder, a search's results, the trash,
 * and each type of place there is (an archive, say), in an explorer and in a file picker. The kinds are listed down the
 * page's side, narrowed by what is typed over them, so that however many places there are, each is found; the one
 * chosen is arranged beside them: its order, its columns (dragged by their grips into order, shown or not, and
 * aligned), and its filters, for a list and for a gallery. A picker's are the explorer's until it is given its own.
 */
@Composable
private fun LayoutPage(settings: Settings, onChange: (Settings) -> Unit) {
	val type = LocalType.current
	val (role, kind) = settings.looking
	val listings = settings.listings
	val kinds =
		listOf(ListingKind.Folder, ListingKind.Results, ListingKind.Trash) +
			settings.actions.places.keys
				.sorted()
				.map(ListingKind::Place)

	PageLayout(
		"Layout",
		scrolls = false,
	) {
		Row(
			Modifier.weight(1f).fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(Space.xl),
		) {
			val (own, places) = kinds.partition { it !is ListingKind.Place }

			ChoiceList(
				listOf("Built-in" to own, "Places" to places),
				kind,
				::labelOf,
				::glyphOf,
				marked = {
					listings.ownLook(role, it) ||
						Layout.entries.any { layout -> listings.ownFilters(role, it, layout) }
				},
				tag = "listing",
				hint = "Filter views",
				Modifier.width(240.dp).fillMaxHeight(),
			) {
				onChange(settings.copy(looking = settings.looking.copy(kind = it)))
			}
			ScrollArea(Modifier.weight(1f).fillMaxHeight()) {
				Column(verticalArrangement = Arrangement.spacedBy(Space.xl)) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.m),
					) {
						Text(labelOf(kind), style = type.title, modifier = Modifier.weight(1f))
						Text("For", style = type.muted)
						Segmented(
							Role.entries,
							role,
							{
								if (it == Role.Explorer)
									"Explorer"
								else
									"File picker"
							},
							tag = "Window",
						) { onChange(settings.copy(looking = settings.looking.copy(role = it))) }
					}
					LookSection(settings, onChange)
					FiltersSection(settings, onChange)
				}
			}
		}
	}
}

/**
 * What a page arranges, down its side, in [groups] under their headings: a field over them narrows them as it is
 * typed into, so that whatever there is is found. The one [chosen] is lit; one [marked] (given anything of its own)
 * has a dot.
 */
@Composable
internal fun <T> ChoiceList(
	groups: List<Pair<String, List<T>>>,
	chosen: T,
	label: (T) -> String,
	glyph: (T) -> Glyph,
	marked: (T) -> Boolean,
	tag: String,
	hint: String,
	modifier: Modifier,
	onChoose: (T) -> Unit,
) {
	val palette = LocalPalette.current
	var typed by remember { mutableStateOf("") }

	val shown =
		groups.map { (heading, group) ->
			heading to
				group.filter {
					typed.isBlank() ||
						label(
							it,
						).contains(typed.trim(), ignoreCase = true)
				}
		}

	Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
		Field(
			typed,
			{
				typed = it
			},
			Modifier.fillMaxWidth().testTag("$tag-filter"),
			hint = hint,
			leading = Glyph.Search,
		)
		Card(Modifier.weight(1f)) {
			LazyScrollArea(Modifier.fillMaxSize(), padding = PaddingValues(Space.xs)) {
				for ((heading, group) in shown) {
					if (group.isNotEmpty())
						item(key = heading) { ListHeading(heading) }
					items(group, key = { it.toString() }) { choice ->
						ListRow(
							label(choice),
							Modifier.testTag("$tag:${label(choice)}"),
							chosen = choice == chosen,
							glyph = glyph(choice),
							trailing =
								if (marked(choice)) {
									{ Box(Modifier.size(6.dp).background(palette.accentText, CircleShape)) }
								} else {
									null
								},
						) { onChoose(choice) }
					}
				}
				if (shown.all { it.second.isEmpty() })
					item(key = "none") { ListHeading("No matches") }
			}
		}
	}
}

/**
 * Whether a picker's listing has its own [what], or follows the explorer's: said at a section's head, for a picker
 * only, since the explorer's are its own always.
 */
@Composable
private fun OwnSwitch(role: Role, own: Boolean, onOwn: (Boolean) -> Unit) {
	if (role == Role.Picker)
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
		) {
			Text(if (own) "Custom" else "Same as explorer", style = LocalType.current.muted)
			Switch(own, Modifier.testTag("own"), onChange = onOwn)
		}
}

/** The chosen listing's order and its columns: dragged into order by their grips, shown or not, and aligned. */
@Composable
private fun LookSection(settings: Settings, onChange: (Settings) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val (role, kind) = settings.looking
	val listings = settings.listings
	val look = listings.look(role, kind)
	val columns = look.columns(kind)
	val sort = look.order.sort

	fun looked(look: Look) =
		onChange(settings.copy(listings = listings.looking(role, kind, look)))

	Section(
		"Sorting and columns",
		description =
			"Drag columns to reorder them. Shift+click selects several. Name is always first." +
				(
					" Any change here applies to file pickers only.".takeIf {
						role == Role.Picker &&
							!listings.ownLook(role, kind)
					}
						?: ""
				),
		trailing = {
			OwnSwitch(role, listings.ownLook(role, kind)) { own ->
				onChange(
					settings.copy(listings = listings.looking(role, kind, if (own) look else null)),
				)
			}
		},
	) {
		Card {
			SettingRow("Sort by") {
				Select(
					(
						if (kind == ListingKind.Results)
							listOf<ColumnId?>(null)
						else
							emptyList()
					) +
						kind.offered,
					sort?.by,
					{ it?.let(kind::labelOf) ?: "Relevance" },
					Modifier.width(200.dp).testTag("order"),
				) { column ->
					looked(
						look.copy(
							order =
								column?.let { Order.By(Sort.of(it)) } ?: Order.Relevance,
						),
					)
				}
				if (sort != null)
					Segmented(
						listOf(true, false),
						sort.ascending,
						{ if (it) "Ascending" else "Descending" },
						tag = "Direction",
						glyph = { if (it) Glyph.SortUp else Glyph.SortDown },
					) { looked(look.copy(order = Order.By(sort.copy(ascending = it)))) }
			}
			ReorderableColumn(
				columns.settings,
				key = { it.id },
				moving = Moving.Several,
				onMove = { looked(look.with(columns.moved(it))) },
				movable = { it.id != ColumnId.Name },
				divided = true,
			) { column, grip ->
				val name = column.id == ColumnId.Name

				Row(
					Modifier
						.fillMaxWidth()
						.then(grip.entry)
						.background(if (grip.lit) palette.chosen else palette.raised)
						.testTag("columns:${column.id.name}")
						.padding(horizontal = Space.m, vertical = Space.s),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Space.m),
				) {
					GlyphIcon(
						Glyph.Grip,
						if (name)
							Color.Transparent
						else
							palette.textFaint,
						type.icon,
						Modifier.testTag("grip:${column.id.name}"),
					)
					CheckBox(
						kind.labelOf(column.id),
						column.shown,
						Modifier.weight(1f).testTag("shown:${column.id.name}"),
						enabled = !name,
					) { looked(look.with(columns.showing(column.id, it))) }
					Segmented(
						Align.entries,
						column.aligned,
						::alignedOf,
						tag = "align:${column.id.name}",
						glyph = ::glyphOf,
					) { looked(look.with(columns.aligning(column.id, it))) }
				}
			}
		}
	}
}

/** How a column's values sitting so is said. */
private fun alignedOf(align: Align): String =
	when (align) {
		Align.Start -> "Left"
		Align.Center -> "Center"
		Align.End -> "Right"
	}

private fun glyphOf(align: Align): Glyph =
	when (align) {
		Align.Start -> Glyph.AlignStart
		Align.Center -> Glyph.AlignCenter
		Align.End -> Glyph.AlignEnd
	}

/**
 * What the chosen listing leaves out, drawn as a list or as a gallery (chosen at the section's head): which hidden
 * entries it shows, and which kinds of file.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersSection(settings: Settings, onChange: (Settings) -> Unit) {
	val type = LocalType.current
	val (role, kind) = settings.looking
	val layout = settings.filteringIn
	val listings = settings.listings
	val filters = listings.filters(role, kind, layout)

	fun filtered(filters: Filters) =
		onChange(settings.copy(listings = listings.filtering(role, kind, layout, filters)))

	Section(
		"Filters",
		description =
			"What each view hides. List and gallery have separate filters." +
				(
					" Any change here applies to file pickers only.".takeIf {
						role == Role.Picker &&
							!listings.ownFilters(role, kind, layout)
					}
						?: ""
				),
		trailing = {
			Segmented(Layout.entries, layout, {
				if (it ==
					Layout.List
				) "List" else "Gallery"
			}, tag = "View") {
				onChange(settings.copy(filteringIn = it))
			}
		},
	) {
		Card {
			if (role == Role.Picker)
				SettingRow(
					"Custom filters for pickers",
					description = "When off, uses the explorer's.",
				) {
					Switch(
						listings.ownFilters(role, kind, layout),
						Modifier.testTag("own-filters"),
					) { own ->
						onChange(
							settings.copy(
								listings = listings.filtering(role, kind, layout, if (own) filters else null),
							),
						)
					}
				}
			SettingRow("Show hidden files", description = "Files whose names start with a dot.") {
				Switch(filters.hidden.files, Modifier.testTag("hidden-files")) {
					filtered(filters.copy(hidden = filters.hidden.copy(files = it)))
				}
			}
			SettingRow(
				"Show hidden folders",
				description = "Folders whose names start with a dot.",
			) {
				Switch(filters.hidden.folders, Modifier.testTag("hidden-folders")) {
					filtered(filters.copy(hidden = filters.hidden.copy(folders = it)))
				}
			}
			CardItem {
				Text("File types shown", style = type.body)
				Text("All types are shown when none is checked.", style = type.muted)
				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(Space.l),
					verticalArrangement = Arrangement.spacedBy(Space.xs),
				) {
					Kind.entries.forEach { shown ->
						CheckBox(
							shown.label,
							shown in filters.kinds,
							Modifier.testTag("kind:${shown.label}"),
						) {
							filtered(
								filters.copy(
									kinds =
										if (it)
											filters.kinds + shown
										else
											filters.kinds -
												shown,
								),
							)
						}
					}
				}
			}
		}
	}
}

/**
 * The text searches' page: the searches, and under them the editor a line found is opened in, as a command line that
 * names the file and the line; with none typed, the editor the environment names is said, which is the one used.
 */
@Composable
private fun SearchesPage(
	settings: Settings,
	world: World,
	filter: FocusRequester,
	onChange: (Settings) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val editor = settings.acting.editor

	fun edited(to: EditorForm) =
		onChange(settings.copy(acting = settings.acting.copy(editor = to)))

	ScriptsPage(ScriptKind.Search, settings, world, filter, onChange) {
		Card {
			SettingRow(
				"Editor",
				description =
					"Command that opens a search result. Use {path} and {line}." +
						when {
							editor.spec() != null -> ""
							settings.actions.editor == prism.browser.Editor.Application ->
								" Empty: uses the file's default application."
							else -> " Empty: uses ${editorSaid(
								settings.actions.editor,
							)}, from \$VISUAL or \$EDITOR."
						},
			) {
				Field(
					editor.command,
					{ edited(editor.copy(command = it)) },
					Modifier.width(260.dp).testTag("editor"),
					hint = "code --goto {path}:{line}",
					style = type.mono,
					onClear = { edited(EditorForm()) },
				)
				CheckBox("Run in terminal", editor.terminal, Modifier.testTag("editor-terminal")) {
					edited(editor.copy(terminal = it))
				}
			}
			settings.actions.broken.firstOrNull { it.what == "editor" }?.let {
				Text(
					it.why,
					style = type.muted.copy(color = palette.dangerText),
					modifier =
						Modifier
							.padding(
								horizontal = Space.l,
								vertical = Space.s,
							).testTag("editor-wrong"),
				)
			}
		}
	}
}

/** What opens a line a text search found, as the Text searches page says it. */
internal fun editorSaid(editor: prism.browser.Editor): String =
	when (editor) {
		prism.browser.Editor.Application -> "The file's default application"
		is prism.browser.Editor.Program ->
			editor.opener.command.words
				.filterIsInstance<prism.browser.Word.One>()
				.firstOrNull()
				?.phrase
				?.pieces
				?.filterIsInstance<prism.browser.Piece.Text>()
				?.joinToString("") { it.text }
				.orEmpty() +
				if (editor.opener.terminal)
					", in the terminal"
				else
					""
	}

/**
 * The terminal "Open terminal here" opens, and the one actions and applications that run in a terminal open in: chosen
 * from a box that lists the desktop's own, every terminal installed, Custom, then those prism knows that are not
 * installed (faint, still choosable, and said not to be once chosen); Custom's command lines, where it is chosen; and
 * what would run in the home folder ([preview]), always, as it is chosen and typed.
 */
@Composable
private fun TerminalPage(
	settings: Settings,
	onChange: (Settings) -> Unit,
	preview: (TerminalChoice, List<String>?) -> TerminalCommand,
	terminals: TerminalOptions,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val form = settings.terminal
	val choice = remember(form) { form.choice() }
	val opens = remember(choice) { preview(choice, null) }
	val runs = remember(choice) { preview(choice, EXAMPLE) }
	val pick = form.pick
	// A terminal the preferences name that prism neither knows nor has found is listed too, installed or not as it is.
	val named = (pick as? TerminalPick.Named)?.name

	val listed =
		terminals.options +
			listOfNotNull(
				named
					?.takeIf { name -> terminals.options.none { it.name == name } }
					?.let { TerminalOption(it, it, opens is TerminalCommand.Ready) },
			)

	val (installed, missing) = listed.partition { it.installed }
	val labels = listed.associate { it.name to it.label }

	fun labelOf(pick: TerminalPick): String =
		when (pick) {
			TerminalPick.Automatic -> terminals.preferred?.let { "Automatic: $it" } ?: "Automatic"
			is TerminalPick.Named -> labels[pick.name] ?: pick.name
			TerminalPick.Custom -> "Custom"
		}

	fun edit(form: TerminalForm) = onChange(settings.copy(terminal = form))

	// The desktop's own, where it names one; the terminals installed; and Custom, which is always there. Then, apart,
	// those prism knows that are not installed.
	val groups =
		listOf<OptionGroup<TerminalPick>>(
			OptionGroup(
				listOfNotNull<TerminalPick>(
					TerminalPick.Automatic.takeIf {
						terminals.preferred !=
							null ||
							pick == it
					},
				) +
					installed.map { TerminalPick.Named(it.name) } +
					TerminalPick.Custom,
			),
			OptionGroup(
				missing.map<TerminalOption, TerminalPick> { TerminalPick.Named(it.name) },
				heading = "Not installed",
				faint = true,
			),
		).filter { it.options.isNotEmpty() }

	PageLayout(
		"Terminal",
		"Used by “Open terminal here” and by actions and applications that run in a terminal.",
	) {
		Section(
			"Terminal application",
			description =
				"Automatic uses \$TERMINAL or your desktop's default. Choose Custom for a " +
					"terminal that isn't listed.",
		) {
			Card {
				CardItem {
					ComboBox(
						groups,
						pick,
						::labelOf,
						Modifier.fillMaxWidth(),
						tag = "terminal",
						hint = "Terminal name",
					) { edit(form.picked(it)) }
					// Chosen, though it is not installed: said under the box, in red.
					(pick as? TerminalPick.Named)
						?.takeIf { chosen -> missing.any { it.name == chosen.name } }
						?.let {
							Text(
								"${labelOf(it)} isn't installed. Install it or choose another terminal.",
								style = type.muted.copy(color = palette.dangerText),
								modifier = Modifier.testTag("terminal-missing"),
							)
						}
				}
			}
		}
		if (pick == TerminalPick.Custom)
			Section(
				"Custom",
				description =
					"{folder} is replaced by the folder and {command} by the command to run. " +
						"Leave a line empty to turn that feature off.",
			) {
				Card {
					CardItem {
						Labelled("Open in a folder") {
							Field(
								form.open,
								{ edit(form.typed(open = it)) },
								Modifier.fillMaxWidth().testTag("terminal-open"),
								hint = "ghostty --working-directory={folder}",
								style = type.mono,
							)
						}
					}
					CardItem {
						Labelled("Run a command in a folder") {
							Field(
								form.run,
								{ edit(form.typed(run = it)) },
								Modifier.fillMaxWidth().testTag("terminal-run"),
								hint = "ghostty --working-directory={folder} -e {command}",
								style = type.mono,
							)
						}
					}
				}
			}
		Section(
			"Preview",
			description = "The commands prism would run in your home folder.",
		) {
			Card {
				Preview("Open terminal here", opens, palette.dangerText) {
					"“Open terminal here” is unavailable: $it"
				}
				Preview("Run ${EXAMPLE.joinToString(" ")}", runs, palette.dangerText) {
					"Actions that run in a terminal are unavailable: $it"
				}
			}
		}
	}
}

/** What [command] runs, under [title]; or why it cannot, said as [off] says it. */
@Composable
private fun Preview(
	title: String,
	command: TerminalCommand,
	wrong: Color,
	off: (String) -> String,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	CardItem {
		Text(title, style = type.body)
		when (command) {
			is TerminalCommand.Ready ->
				Text(
					textOf(command.words),
					style = type.mono,
					modifier =
						Modifier
							.fillMaxWidth()
							.background(palette.sunken, RoundedCornerShape(Radius.medium))
							.padding(horizontal = Space.m, vertical = Space.s),
				)
			is TerminalCommand.Failed ->
				Text(
					off(command.why),
					style = type.muted.copy(color = wrong),
				)
		}
	}
}

/** The command the Terminal page shows a terminal running, as an action would. */
private val EXAMPLE = listOf("htop", "-d", "10")
