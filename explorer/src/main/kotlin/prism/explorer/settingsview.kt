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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import prism.browser.Act
import prism.browser.Actions
import prism.browser.Bindable
import prism.browser.ButtonRow
import prism.browser.Card
import prism.browser.CardItem
import prism.browser.CheckBox
import prism.browser.Chip
import prism.browser.Chord
import prism.browser.ComboBox
import prism.browser.Dims
import prism.browser.Divider
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.Labelled
import prism.browser.LazyScrollArea
import prism.browser.ListHeading
import prism.browser.ListRow
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.OptionGroup
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
import prism.browser.ToolButton
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
 */
@Composable
fun SettingsView(
	settings: Settings,
	onChange: (Settings) -> Unit,
	onApply: () -> Unit,
	onClose: () -> Unit,
	mouseChords: Flow<Chord> = emptyFlow(),
	terminalPreview: (TerminalChoice, List<String>?) -> TerminalCommand = { _, _ ->
		TerminalCommand.Failed("a terminal", "there is no desktop")
	},
	terminals: TerminalOptions = TerminalOptions.NONE,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val root = remember { FocusRequester() }
	val latest by rememberUpdatedState(settings)
	val change by rememberUpdatedState(onChange)
	val rebinding = settings.rebinding

	val filter = remember { FocusRequester() }

	LaunchedEffect(mouseChords) {
		mouseChords.collect { chord ->
			if (latest.rebinding.listening)
				change(latest.copy(rebinding = latest.rebinding.pressed(chord)))
		}
	}
	// The keyboard starts in the window, where its keys (Ctrl+F) are heard; and goes back there to wait for a chord.
	LaunchedEffect(rebinding.listening) {
		root.requestFocus()
	}

	Row(
		Modifier
			.fillMaxSize()
			.background(palette.canvas)
			.focusRequester(root)
			// Left on nothing (a button pressed that then goes, or can no longer be pressed), the keyboard comes back
			// to the window, where Escape and Ctrl+F are heard.
			.onFocusChanged {
				if (!it.hasFocus)
					root.requestFocus()
			}.focusable()
			.onPreviewKeyEvent { key ->
				// While a chord is awaited, every key is it: none reaches a field, nor moves the keyboard on. Ctrl+F on
				// the bindings page goes to its filter, as it does in a browser's page.
				when {
					!rebinding.listening ->
						(
							settings.page == Page.Bindings &&
								key.type == KeyEventType.KeyDown &&
								key.isCtrlPressed &&
								key.key == Key.F
						).also {
							if (it)
								filter.requestFocus()
						}
					key.type != KeyEventType.KeyDown -> true
					key.key == Key.Escape ->
						true.also {
							onChange(settings.copy(rebinding = rebinding.cancelled()))
						}
					else ->
						true.also {
							Chord.of(key)?.let { chord ->
								onChange(settings.copy(rebinding = rebinding.pressed(chord)))
							}
						}
				}
			},
	) {
		Column(
			Modifier
				.width(Dims.navigation)
				.fillMaxHeight()
				.background(palette.pane)
				.padding(Space.s),
			verticalArrangement = Arrangement.spacedBy(Space.xxs),
		) {
			Page.entries.forEach { page ->
				ListRow(
					page.label,
					Modifier.testTag("page:${page.label}"),
					chosen = page == settings.page,
					glyph = glyphOf(page),
				) { onChange(settings.copy(page = page)) }
			}
		}
		Divider(vertical = true)
		Column(Modifier.weight(1f).fillMaxHeight()) {
			Box(Modifier.weight(1f).fillMaxWidth()) {
				when (settings.page) {
					Page.Display -> DisplayPage(settings, onChange)
					Page.Listings -> ListingsPage(settings, onChange)
					Page.Terminal -> TerminalPage(settings, onChange, terminalPreview, terminals)
					Page.Bindings ->
						BindingsPage(rebinding, filter) {
							onChange(settings.copy(rebinding = it))
						}
					Page.Actions -> ActionsPage(settings.actions)
				}
			}
			Divider()
			Row(
				Modifier
					.fillMaxWidth()
					.background(palette.window)
					.padding(horizontal = Space.xl, vertical = Space.m),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					if (settings.changed)
						"Changed, and not yet applied"
					else
						"",
					style = type.muted,
					modifier = Modifier.weight(1f),
				)
				// Applied, the window stays: what was changed shows in every window, and more can be changed.
				ButtonRow(
					Act("Apply", "apply", settings.changed, onClick = onApply),
					Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max),
					secondary = listOf(Act("Close", "close", onClick = onClose)),
				)
			}
		}
	}
}

/** The glyph a page is listed by. */
private fun glyphOf(page: Page): Glyph =
	when (page) {
		Page.Display -> Glyph.Desktop
		Page.Listings -> Glyph.ViewList
		Page.Terminal -> Glyph.Terminal
		Page.Bindings -> Glyph.Keyboard
		Page.Actions -> Glyph.Bolt
	}

/** What each part's text is, as the Display page says it. */
private fun descriptionOf(part: TextPart): String =
	when (part) {
		TextPart.General -> "The toolbar, the sidebar, and every window's own text"
		TextPart.Listing -> "A folder's rows and tiles: Ctrl and the wheel zoom them from here"
		TextPart.Menus -> "Context menus, and the window's menu"
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
		"How prism's windows look. Changes reach every window once they are applied.",
	) {
		Section(
			"Text",
			description =
				"The font and size of each part of a window. Rows, menus and buttons grow with " +
					"their text.",
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
		Section("Listing") {
			Card {
				SettingRow(
					"Row size",
					description =
						"Larger rows draw each file's icon large enough to tell it at a glance.",
				) {
					Segmented(Rows.entries, settings.rows, { it.name }, tag = "rows") {
						onChange(settings.copy(rows = it))
					}
				}
			}
		}
		Section(
			"Size",
			description =
				"How large a new window's listing is drawn. Ctrl and the wheel zoom any window of " +
					"prism from there, and it keeps that size.",
		) {
			Card {
				ZoomRow(
					"Explorer windows",
					"The listing of a window opened to explore",
					scales.explorer,
					LISTING_STEPS,
				) {
					onChange(settings.copy(scales = scales.copy(explorer = it)))
				}
				ZoomRow(
					"File pickers",
					"The listing of a picker another program opens",
					scales.picker,
					LISTING_STEPS,
				) {
					onChange(settings.copy(scales = scales.copy(picker = it)))
				}
				ZoomRow(
					"Sidebar",
					"Quick access and the drives, and the window that arranges them",
					scales.sidebar,
					SIDEBAR_STEPS,
				) {
					onChange(settings.copy(scales = scales.copy(sidebar = it)))
				}
			}
		}
		Section(
			"Dates",
			description =
				"How a date and its time are written in the listing. A file's properties write " +
					"them in full.",
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
private fun glyphOf(kind: ListingKind): Glyph =
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
private fun ListingsPage(settings: Settings, onChange: (Settings) -> Unit) {
	val type = LocalType.current
	val (role, kind) = settings.looking
	val kinds =
		listOf(ListingKind.Folder, ListingKind.Results, ListingKind.Trash) +
			settings.actions.places.keys
				.sorted()
				.map(ListingKind::Place)

	PageLayout(
		"Listings",
		"How each kind of listing is ordered, which columns it shows, and what it leaves " +
			"out, as a window opens. A window changed from its own menus keeps that, wherever " +
			"it goes.",
		scrolls = false,
	) {
		Row(
			Modifier.weight(1f).fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(Space.xl),
		) {
			KindList(kinds, kind, settings, Modifier.width(240.dp).fillMaxHeight()) {
				onChange(settings.copy(looking = settings.looking.copy(kind = it)))
			}
			ScrollArea(Modifier.weight(1f).fillMaxHeight()) {
				Column(verticalArrangement = Arrangement.spacedBy(Space.xl)) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.m),
					) {
						Text(labelOf(kind), style = type.title, modifier = Modifier.weight(1f))
						Text("In", style = type.muted)
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
 * The kinds of listing there are, down the side: prism's own, then each type of place, a field over them narrowing
 * them as it is typed into. A kind given anything of its own for the window chosen is marked.
 */
@Composable
private fun KindList(
	kinds: List<ListingKind>,
	chosen: ListingKind,
	settings: Settings,
	modifier: Modifier,
	onChoose: (ListingKind) -> Unit,
) {
	val palette = LocalPalette.current
	var typed by remember { mutableStateOf("") }
	val shown =
		kinds.filter {
			typed.isBlank() ||
				labelOf(it).contains(typed.trim(), ignoreCase = true)
		}
	val (own, places) = shown.partition { it !is ListingKind.Place }
	val role = settings.looking.role
	val listings = settings.listings

	fun changed(kind: ListingKind): Boolean =
		listings.ownLook(role, kind) ||
			Layout.entries.any { listings.ownFilters(role, kind, it) }

	Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
		Field(
			typed,
			{
				typed = it
			},
			Modifier.fillMaxWidth().testTag(
				"listings-filter",
			),
			hint = "Find a listing",
			leading = Glyph.Search,
		)
		Card(Modifier.weight(1f)) {
			LazyScrollArea(Modifier.fillMaxSize(), padding = PaddingValues(Space.xs)) {
				for ((heading, group) in listOf("Prism's own" to own, "Places" to places)) {
					if (group.isNotEmpty())
						item(key = heading) { ListHeading(heading) }
					items(group, key = { it.toString() }) { kind ->
						ListRow(
							labelOf(kind),
							Modifier.testTag("listing:${labelOf(kind)}"),
							chosen = kind == chosen,
							glyph = glyphOf(kind),
							trailing =
								if (changed(kind)) {
									{ Box(Modifier.size(6.dp).background(palette.accentText, CircleShape)) }
								} else {
									null
								},
						) { onChoose(kind) }
					}
				}
				if (shown.isEmpty())
					item(key = "none") { ListHeading("No listing is called that") }
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
			Text(if (own) "Its own" else "The explorer's", style = LocalType.current.muted)
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
		"Order and columns",
		description =
			"Drag a column by its grip to move it. The name is always first, and always shown." +
				(
					" Changing anything gives the file picker its own.".takeIf {
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
			SettingRow("Ordered by") {
				Select(
					(
						if (kind == ListingKind.Results)
							listOf<ColumnId?>(null)
						else
							emptyList()
					) +
						kind.offered,
					sort?.by,
					{ it?.let(kind::labelOf) ?: "Best match" },
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
				onMove = { from, to -> looked(look.with(columns.moved(from, to))) },
				movable = { it.id != ColumnId.Name },
				divided = true,
			) { column, handle, dragged ->
				val name = column.id == ColumnId.Name

				Row(
					Modifier
						.fillMaxWidth()
						.background(if (dragged) palette.chosen else palette.raised)
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
						handle.testTag("grip:${column.id.name}"),
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
		Align.Center -> "Centre"
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
			"What is left out, drawn as a list and as a gallery: a gallery can leave out hidden " +
				"files that a list shows." +
				(
					" Changing anything gives the file picker its own.".takeIf {
						role == Role.Picker &&
							!listings.ownFilters(role, kind, layout)
					}
						?: ""
				),
		trailing = {
			Segmented(Layout.entries, layout, {
				if (it ==
					Layout.List
				) "Details" else "Gallery"
			}, tag = "View") {
				onChange(settings.copy(filteringIn = it))
			}
		},
	) {
		Card {
			if (role == Role.Picker)
				SettingRow("The file picker's own", description = "Or as the explorer's, where not") {
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
			SettingRow("Hidden files", description = "Files whose names start with a dot") {
				Switch(filters.hidden.files, Modifier.testTag("hidden-files")) {
					filtered(filters.copy(hidden = filters.hidden.copy(files = it)))
				}
			}
			SettingRow("Hidden folders", description = "Where programs keep their own things") {
				Switch(filters.hidden.folders, Modifier.testTag("hidden-folders")) {
					filtered(filters.copy(hidden = filters.hidden.copy(folders = it)))
				}
			}
			CardItem {
				Text("Kinds of file shown", style = type.body)
				Text("Every kind, where none is ticked", style = type.muted)
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
 * The actions the menus offer beyond prism's own, and the places they show: which there are, and, of those the
 * preferences write that did not read, why not, so that one written wrong is not simply missing from a menu. They are
 * written in the preferences file, as docs/menus.md says how.
 */
@Composable
private fun ActionsPage(actions: Actions) {
	val palette = LocalPalette.current
	val type = LocalType.current

	PageLayout(
		"Actions",
		"What a context menu offers beyond prism's own, and the places a window shows that " +
			"are not folders. Both are written in the preferences file, under “actions” and " +
			"“places”.",
	) {
		if (actions.broken.isNotEmpty())
			Section(
				"Not read",
				description = "Written in the preferences, but left out of the menus, and why",
			) {
				Card {
					actions.broken.forEach { broken ->
						Row(
							Modifier
								.fillMaxWidth()
								.padding(
									prism.browser.CardInsets,
								).testTag("broken:${broken.id}"),
							horizontalArrangement = Arrangement.spacedBy(Space.m),
						) {
							GlyphIcon(Glyph.Warning, palette.dangerText, type.icon)
							Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
								Text(
									"The ${broken.what} ${broken.id?.let { "“$it”" } ?: "with no id"}",
									style = type.body.copy(color = palette.dangerText),
								)
								Text(broken.why, style = type.muted)
							}
						}
					}
				}
			}
		Section("Actions") {
			Card(Modifier.testTag("actions")) {
				actions.actions.forEach { action ->
					SettingRow(
						(action.submenu + action.label).joinToString(" ▸ "),
						description =
							action.id + " (hidden)".takeIf { action.id in actions.hidden }.orEmpty(),
					) {}
				}
			}
		}
		Section("Places") {
			Card {
				actions.places.values.forEach { place ->
					SettingRow(place.id) {}
				}
			}
		}
	}
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
				heading = "Not found on your PATH",
				faint = true,
			),
		).filter { it.options.isNotEmpty() }

	PageLayout(
		"Terminal",
		"The terminal “Open terminal here” opens, and the one that actions and applications " +
			"which run in a terminal open in.",
	) {
		Section(
			"Which terminal",
			description =
				"Automatic is the one your desktop names, in \$TERMINAL or its own settings. prism " +
					"knows how to tell each terminal listed which folder to open in, and what to run " +
					"there; for one it doesn't, choose Custom.",
		) {
			Card {
				CardItem {
					ComboBox(
						groups,
						pick,
						::labelOf,
						Modifier.fillMaxWidth(),
						tag = "terminal",
						hint = "Type a terminal's name",
					) { edit(form.picked(it)) }
					// Chosen, though it is not installed: said under the box, in red.
					(pick as? TerminalPick.Named)
						?.takeIf { chosen -> missing.any { it.name == chosen.name } }
						?.let {
							Text(
								"${labelOf(it)} wasn't found on your PATH. Install it, or choose another.",
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
					"The terminal's command lines, each in full. {folder} stands for the folder, and " +
						"{command} for the words of the command to run, as a word of its own. A line " +
						"left empty turns off what it is for.",
			) {
				Card {
					CardItem {
						Labelled("Opening in a folder") {
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
						Labelled("Running a command in a folder") {
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
			"What runs",
			description = "The commands prism would run, opening in your home folder.",
		) {
			Card {
				Preview("Open terminal here", opens, palette.dangerText) {
					"“Open terminal here” is off: $it"
				}
				Preview("Running ${EXAMPLE.joinToString(" ")}", runs, palette.dangerText) {
					"Actions that run in a terminal are off: $it"
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

/** Where each scope's actions act, as the bindings page heads them. */
private fun headingOf(scope: Scope): String =
	when (scope) {
		Scope.Window -> "Anywhere in the window"
		Scope.Listing -> "On the files, from the list"
		Scope.Movement ->
			"Moving through the list (Shift chooses as it moves, Ctrl moves without choosing)"
		Scope.Leftover -> "Wherever the keyboard is, once it is done with the key"
	}

/**
 * Every action, with its chords, each one clicked to bind it anew, removed, or added to: those [Finding] shows, found
 * by words typed into the filter (Ctrl+F) or by a chord pressed, which shows what it does. The list scrolls, its
 * scrollbar beside it.
 */
@Composable
private fun BindingsPage(
	rebinding: Rebinding,
	filter: FocusRequester,
	onChange: (Rebinding) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val list = rememberLazyListState()
	val finding = rebinding.finding
	val shown =
		rebinding.bindings.actions.filter {
			finding.shows(it, rebinding.bindings.of(it))
		}

	PageLayout(
		"Key bindings",
		"Click a binding, then press a key (with Ctrl, Alt or Shift, if you like) or a mouse " +
			"button: the middle, back or forward. Escape stops waiting. A chord does one thing, " +
			"so one given here is taken from whatever had it.",
		scrolls = false,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
		) {
			Field(
				(finding as? Finding.Words)?.text.orEmpty(),
				{ onChange(rebinding.finding(Finding.Words(it))) },
				Modifier.weight(1f).testTag("bindings-filter"),
				hint = "Find an action, or a key (Ctrl+F)",
				leading = Glyph.Search,
				focus = filter,
			)
			// What the window that finds a shortcut found, while it is what is shown: let go of by its ×.
			(finding as? Finding.Shortcut)?.chord?.let { chord ->
				Chip(
					chord.toString(),
					removeTag = "found:remove",
					onRemove = { onChange(rebinding.finding(Finding.ALL)) },
				) { onChange(rebinding.finding(Finding.OPENED)) }
			}
			Box {
				ToolButton(
					Glyph.FindKey,
					"Find a shortcut by pressing it",
					enabled = true,
					Modifier.testTag("find-by-key"),
					selected = finding is Finding.Shortcut,
				) {
					// Opened afresh, every action is shown until a shortcut is pressed; pressed again, it closes.
					onChange(
						rebinding.finding(
							if ((finding as? Finding.Shortcut)?.open == true)
								Finding.ALL
							else
								Finding.OPENED,
						),
					)
				}
				if ((finding as? Finding.Shortcut)?.open == true)
					FindShortcut(finding.chord, onPressed = { onChange(rebinding.pressed(it)) }) {
						onChange(rebinding.cancelled())
					}
			}
		}
		rebinding.took?.let { took ->
			Text(
				"${took.chord} was taken from ${namesOf(took.from)}.",
				style = type.body.copy(color = palette.accentText),
			)
		}
		Card(Modifier.weight(1f)) {
			LazyScrollArea(
				Modifier.fillMaxSize(),
				state = list,
				padding = PaddingValues(horizontal = Space.xs, vertical = Space.xs),
				tag = "bindings",
				scrollbarTag = "bindings-scrollbar",
			) {
				Scope.entries.forEach { scope ->
					val here = shown.filter { it.scope == scope }

					if (here.isNotEmpty())
						item(key = scope) { ListHeading(headingOf(scope)) }
					items(here, key = { it.key }) { action ->
						BindingRow(action, rebinding, onChange)
					}
				}
				if (shown.isEmpty())
					item(key = "none") {
						Text(
							(finding as? Finding.Shortcut)?.chord?.let { "$it does nothing." }
								?: "No action matches.",
							style = type.muted,
							modifier = Modifier.padding(Space.m),
						)
					}
			}
		}
		ButtonRow(
			null,
			aside =
				listOf(
					Act("Reset all", "reset-all", rebinding.bindings != rebinding.bindings.defaults()) {
						onChange(rebinding.resetAll())
					},
				),
		)
	}
}

/**
 * The window that finds a shortcut, as JetBrains' "Find Shortcut": below its button, over the page, taking the keys
 * while it is open. The shortcut pressed ([chord], none yet where null) is shown, and the bindings with it are what the
 * page shows, as it is pressed; another pressed takes its place. A mouse's middle, back or forward button pressed over
 * its lower part is a shortcut too. Escape, or a click elsewhere, closes it ([onClose]), what it found still shown.
 */
@Composable
private fun FindShortcut(chord: Chord?, onPressed: (Chord) -> Unit, onClose: () -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val keys = remember { FocusRequester() }
	val shape = RoundedCornerShape(Radius.large)

	Popup(
		alignment = Alignment.TopEnd,
		offset = IntOffset(0, 44),
		onDismissRequest = onClose,
		properties = PopupProperties(focusable = true),
	) {
		WindowLocals {
			Column(
				Modifier
					.width(360.dp)
					.shadow(16.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
					.background(palette.overlay, shape)
					.border(1.dp, palette.border, shape)
					.testTag("find-shortcut"),
			) {
				Column(
					Modifier.padding(Space.l),
					verticalArrangement = Arrangement.spacedBy(Space.m),
				) {
					Text(
						"Find Shortcut",
						style = type.title,
						textAlign = TextAlign.Center,
						modifier = Modifier.fillMaxWidth(),
					)
					Row(
						Modifier
							.fillMaxWidth()
							.height(type.control)
							.background(palette.field, RoundedCornerShape(Radius.medium))
							.border(2.dp, palette.focus, RoundedCornerShape(Radius.medium))
							.focusRequester(keys)
							.focusable()
							.onPreviewKeyEvent { key ->
								when {
									key.type != KeyEventType.KeyDown -> true
									key.key == Key.Escape -> true.also { onClose() }
									else -> true.also { Chord.of(key)?.let(onPressed) }
								}
							}.testTag("shortcut-field")
							.padding(horizontal = Space.m),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.s),
					) {
						GlyphIcon(Glyph.Keyboard, palette.textMuted, type.icon * 0.9f)
						Text(
							chord?.toString() ?: "Press a shortcut",
							style =
								if (chord ==
									null
								)
									type.body.copy(color = palette.textFaint)
								else
									type.strong,
							maxLines = 1,
						)
					}
				}
				Divider()
				Row(
					Modifier
						.fillMaxWidth()
						.background(
							palette.sunken,
							RoundedCornerShape(bottomStart = Radius.large, bottomEnd = Radius.large),
						).mouseChords(onPressed)
						.padding(horizontal = Space.l, vertical = Space.m)
						.testTag("mouse-shortcut"),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						"Mouse shortcut: press the middle, back or forward button here",
						style = type.muted,
					)
				}
			}
			LaunchedEffect(Unit) { keys.requestFocus() }
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BindingRow(
	action: Bindable,
	rebinding: Rebinding,
	onChange: (Rebinding) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val chords = rebinding.bindings.of(action)

	Row(
		Modifier.fillMaxWidth().padding(horizontal = Space.s, vertical = Space.xs),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.m),
	) {
		Text(action.label, style = type.body, modifier = Modifier.weight(0.45f))
		FlowRow(
			Modifier.weight(0.55f),
			horizontalArrangement = Arrangement.spacedBy(Space.xs),
			verticalArrangement = Arrangement.spacedBy(Space.xs),
		) {
			chords.forEachIndexed { index, chord ->
				val slot = Slot(action, index)
				val awaited = rebinding.awaited == slot

				Chip(
					if (awaited) "Press a key…" else chord.toString(),
					Modifier.testTag("chord:${if (awaited) "awaited" else chord.toString()}"),
					strong = awaited,
					removeTag = "remove:$chord",
					onRemove = { onChange(rebinding.removed(slot)) }.takeIf { !awaited },
				) { onChange(rebinding.awaiting(slot)) }
			}

			val added = Slot(action, chords.size)
			val adding = rebinding.awaited == added

			Chip(
				if (adding) "Press a key…" else "+",
				Modifier.testTag(if (adding) "chord:awaited" else "chord:+"),
				strong = adding,
			) { onChange(rebinding.awaiting(added)) }
		}
		if (chords != action.defaults)
			Text(
				"Reset",
				style = type.body.copy(color = palette.accentText),
				modifier =
					Modifier
						.clickable { onChange(rebinding.reset(action)) }
						.padding(horizontal = Space.xs),
			)
		else
			Spacer(Modifier.width(Space.xs))
	}
}

/** “Back” and “Forward”: actions named as the bindings page names them. */
private fun namesOf(actions: List<Bindable>): String =
	actions.joinToString(" and ") { "“${it.label}”" }
