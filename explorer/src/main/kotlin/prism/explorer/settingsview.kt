package prism.explorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import prism.browser.Actions
import prism.browser.Bindable
import prism.browser.Bindings
import prism.browser.CheckBox
import prism.browser.Chord
import prism.browser.Dims
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.PushButton
import prism.browser.ReorderableColumn
import prism.browser.Scope
import prism.browser.Select
import prism.browser.ToolButton
import prism.browser.mouseChords
import prism.browser.systemFontFamilies
import prism.desktop.TerminalChoice
import prism.desktop.TerminalCommand
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
import prism.preferences.TextSizes
import prism.window.WindowLocals

/*
 * The preferences window. Its edits are one value ([Settings]) that the window replaces with each change and saves
 * when the user does; the window only draws it and says what was done to it. On the bindings page, a binding clicked
 * waits for a chord: whatever is pressed next, a key with what modifiers are held, or a mouse button (middle, back or
 * forward), is taken; Escape stops waiting instead.
 */

/**
 * The preferences window over [settings], each change reported to [onChange]: saved by [onSave], or not, by [onCancel].
 * [mouseChords] are the mouse's buttons pressed over the window, which a binding waiting for a chord takes.
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
) {
	val palette = LocalPalette.current
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
				.width(180.dp)
				.fillMaxHeight()
				.background(palette.pane)
				.padding(Dims.gap),
		) {
			Page.entries.forEach { page ->
				PageButton(page.label, page == settings.page) { onChange(settings.copy(page = page)) }
			}
		}
		Column(Modifier.weight(1f).fillMaxHeight().padding(Dims.gap * 2)) {
			Box(Modifier.weight(1f).fillMaxWidth()) {
				when (settings.page) {
					Page.Display -> DisplayPage(settings, onChange)
					Page.Columns -> ColumnsPage(settings, onChange)
					Page.Filters -> FiltersPage(settings, onChange)
					Page.Terminal -> TerminalPage(settings, onChange, terminalPreview)
					Page.Bindings ->
						BindingsPage(rebinding, filter) {
							onChange(settings.copy(rebinding = it))
						}
					Page.Actions -> ActionsPage(settings.actions)
				}
			}
			Spacer(Modifier.height(Dims.gap))
			Row(
				Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(Dims.gap, Alignment.End),
			) {
				// Applied, the window stays: what was changed shows in every window, and more can be changed.
				PushButton(
					"Close",
					Emphasis.Secondary,
					enabled = true,
					Modifier.testTag("close"),
					onClick = onClose,
				)
				PushButton(
					"Apply",
					Emphasis.Primary,
					enabled = settings.changed,
					Modifier.testTag("apply"),
					onClick = onApply,
				)
			}
		}
	}
}

@Composable
private fun PageButton(label: String, chosen: Boolean, onClick: () -> Unit) {
	val palette = LocalPalette.current

	Text(
		label,
		style = LocalType.current.body,
		modifier =
			Modifier
				.fillMaxWidth()
				.background(
					if (chosen) palette.chosen else palette.pane,
					RoundedCornerShape(Dims.radius),
				).clickable(onClick = onClick)
				.padding(horizontal = Dims.gap, vertical = 7.dp)
				.testTag("page:$label"),
	)
}

@Composable
private fun Heading(text: String, help: String) {
	val type = LocalType.current

	Text(text, style = type.title)
	Spacer(Modifier.height(Dims.gapSmall))
	Text(help, style = type.muted)
	Spacer(Modifier.height(Dims.gap * 2))
}

/**
 * How things are drawn: each part's text, its size and its font; the list's rows; the listing of a new explorer's
 * window and of a picker's, and the sidebar's quick access; and how dates are written.
 */
@Composable
private fun DisplayPage(settings: Settings, onChange: (Settings) -> Unit) {
	val type = LocalType.current
	val text = settings.text
	val fonts = settings.fonts
	val scales = settings.scales
	val families = remember { listOf<String?>(null) + systemFontFamilies() }

	fun sized(size: Float, by: Float) =
		(size + by).coerceIn(TextSizes.SMALLEST, TextSizes.LARGEST)

	@Composable
	fun Part(
		label: String,
		size: Float,
		family: String?,
		onSize: (Float) -> Unit,
		onFamily: (String?) -> Unit,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.padding(vertical = Dims.gapSmall),
		) {
			StepRow(
				label,
				"${size.toInt()} pt",
				size > TextSizes.SMALLEST,
				size < TextSizes.LARGEST,
			) { onSize(sized(size, it)) }
			Spacer(Modifier.width(Dims.gap * 2))
			Select(
				families,
				family,
				{ it ?: "The default font" },
				Modifier.width(260.dp).testTag("font:$label"),
				onFamily,
			)
		}
	}

	Column(Modifier.verticalScroll(rememberScrollState())) {
		Heading(
			"Text",
			"How large the text of each part of a window is, in points, and in what font. The listing's rows and the " +
				"menus' entries grow with their text; Ctrl and the wheel still zoom the listing from its size.",
		)
		Part("Menus", text.menus, fonts.menus, {
			onChange(settings.copy(text = text.copy(menus = it)))
		}) {
			onChange(settings.copy(fonts = fonts.copy(menus = it)))
		}
		Part("Listing", text.listing, fonts.listing, {
			onChange(settings.copy(text = text.copy(listing = it)))
		}) {
			onChange(settings.copy(fonts = fonts.copy(listing = it)))
		}
		Part("Everything else", text.general, fonts.general, {
			onChange(settings.copy(text = text.copy(general = it)))
		}) {
			onChange(settings.copy(fonts = fonts.copy(general = it)))
		}
		Spacer(Modifier.height(Dims.gap * 3))
		Heading("Rows", "How tall the list's rows are, and how large their icons.")
		ChoiceRow("Icons", Rows.entries, settings.rows, {
			if (it ==
				Rows.Larger
			) "Larger" else "Compact"
		}) {
			onChange(settings.copy(rows = it))
		}
		Spacer(Modifier.height(Dims.gap * 3))
		Heading(
			"Size",
			"How large a new window's listing is drawn, as a percentage of its usual size: Ctrl and the wheel zoom it " +
				"from there, as they zoom every other window of prism's (the preferences, a file's properties) on its own. " +
				"Quick access, and the window that arranges it, are drawn as large as the sidebar's size says.",
		)
		ZoomRow("Explorer's listing", scales.explorer, LISTING_STEPS) {
			onChange(settings.copy(scales = scales.copy(explorer = it)))
		}
		ZoomRow("Picker's listing", scales.picker, LISTING_STEPS) {
			onChange(settings.copy(scales = scales.copy(picker = it)))
		}
		ZoomRow("Sidebar", scales.sidebar, WINDOW_STEPS) {
			onChange(settings.copy(scales = scales.copy(sidebar = it)))
		}
		Spacer(Modifier.height(Dims.gap * 3))
		Heading(
			"Dates",
			"How a date and its time are written in the listing. A file's properties write them in full, to the second.",
		)

		val now = remember { java.time.Instant.now() }

		DateFormat.entries.forEach { format ->
			Row(
				Modifier
					.fillMaxWidth()
					.clickable { onChange(settings.copy(dates = format)) }
					.padding(vertical = Dims.gapSmall)
					.testTag("dates:${format.name}"),
				verticalAlignment = Alignment.CenterVertically,
			) {
				RadioDot(settings.dates == format)
				Spacer(Modifier.width(Dims.gap))
				Text(format.label, style = type.body, modifier = Modifier.width(220.dp))
				Text(format.write(now), style = type.muted)
			}
		}
		Spacer(Modifier.height(Dims.gap))
		Text("Changes show once applied.", style = type.muted)
	}
}

/** Which listing a page arranges: in which window, and of which kind (every place there is, by its type). */
@Composable
private fun ListingChoice(settings: Settings, onChange: (Settings) -> Unit) {
	val key = settings.looking
	val kinds =
		listOf(ListingKind.Folder, ListingKind.Results, ListingKind.Trash) +
			settings.actions.places.keys
				.sorted()
				.map(ListingKind::Place)

	ChoiceRow("Window", Role.entries, key.role, {
		if (it ==
			Role.Explorer
		)
			"Explorer"
		else
			"File picker"
	}) {
		onChange(settings.copy(looking = key.copy(role = it)))
	}
	ChoiceRow("Listing", kinds, key.kind, ::labelOf) {
		onChange(settings.copy(looking = key.copy(kind = it)))
	}
}

/**
 * How each kind of listing looks as a window opens, in an explorer and in a picker: its order, and its columns, which
 * are dragged by their grips into the order they are shown in, shown or not, and aligned. A picker's listing looks as
 * the explorer's does until it is given a look of its own.
 */
@Composable
private fun ColumnsPage(settings: Settings, onChange: (Settings) -> Unit) {
	val type = LocalType.current
	val palette = LocalPalette.current
	val (role, kind) = settings.looking
	val listings = settings.listings
	val look = listings.look(role, kind)
	val columns = look.columns(kind)
	val sort = look.order.sort

	fun looked(look: Look) =
		onChange(settings.copy(listings = listings.looking(role, kind, look)))

	Column(Modifier.verticalScroll(rememberScrollState())) {
		Heading(
			"Columns and order",
			"How a new window lists each kind of listing: a folder, a search's results, the trash, and each place (an " +
				"archive, say), in an explorer and in a file picker. A window given another order or other columns keeps " +
				"them wherever it goes, and no other window is changed by it.",
		)
		ListingChoice(settings, onChange)
		OwnRow(role, listings.ownLook(role, kind)) { own ->
			onChange(
				settings.copy(listings = listings.looking(role, kind, if (own) look else null)),
			)
		}
		Spacer(Modifier.height(Dims.gap))
		ChoiceRow(
			"Order",
			(
				if (kind == ListingKind.Results)
					listOf<ColumnId?>(null)
				else
					emptyList()
			) +
				kind.offered,
			sort?.by,
			{ it?.let(kind::labelOf) ?: "Best match" },
		) { column ->
			looked(
				look.copy(
					order =
						column?.let { Order.By(Sort.of(it)) } ?: Order.Relevance,
				),
			)
		}
		if (sort != null)
			ChoiceRow("Direction", listOf(true, false), sort.ascending, ::directionOf) {
				looked(look.copy(order = Order.By(sort.copy(ascending = it))))
			}
		Spacer(Modifier.height(Dims.gap))
		Text("Columns", style = type.body)
		Text(
			"Drag a column by its grip to move it. The name's is always first, and always shown.",
			style = type.muted,
		)
		Spacer(Modifier.height(Dims.gapSmall))
		ReorderableColumn(
			columns.settings,
			key = { it.id },
			onMove = { from, to -> looked(look.with(columns.moved(from, to))) },
			modifier = Modifier.widthIn(max = 760.dp),
			movable = { it.id != ColumnId.Name },
		) { column, handle, dragged ->
			Row(
				Modifier
					.fillMaxWidth()
					.background(
						if (dragged) palette.chosen else palette.window,
						RoundedCornerShape(Dims.radius),
					).padding(vertical = 2.dp)
					.testTag("columns:${column.id.name}"),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Dims.gap),
			) {
				GlyphIcon(
					Glyph.Grip,
					if (column.id == ColumnId.Name)
						palette.window
					else
						palette.textFaint,
					18.dp,
					handle.testTag("grip:${column.id.name}"),
				)
				CheckBox(
					kind.labelOf(column.id),
					column.shown,
					Modifier.width(240.dp).testTag("shown:${column.id.name}"),
					enabled = column.id != ColumnId.Name,
				) { looked(look.with(columns.showing(column.id, it))) }
				listOf(
					Align.Start to "Left",
					Align.Center to "Centre",
					Align.End to "Right",
				).forEach { (align, label) ->
					PushButton(
						label,
						if (column.aligned == align)
							Emphasis.Primary
						else
							Emphasis.Secondary,
						enabled = true,
						Modifier.testTag("align:${column.id.name}:$label"),
					) { looked(look.with(columns.aligning(column.id, align))) }
				}
			}
		}
		Spacer(Modifier.height(Dims.gap))
		Text("Changes show in windows opened once applied.", style = type.muted)
	}
}

/**
 * What a new window's listing of each kind, drawn as a list or as a gallery, leaves out, in an explorer and in a
 * picker: which hidden entries it shows, and which kinds of file. A picker's are the explorer's until it has its own.
 */
@Composable
private fun FiltersPage(settings: Settings, onChange: (Settings) -> Unit) {
	val type = LocalType.current
	val (role, kind) = settings.looking
	val layout = settings.filteringIn
	val listings = settings.listings
	val filters = listings.filters(role, kind, layout)

	fun filtered(filters: Filters) =
		onChange(settings.copy(listings = listings.filtering(role, kind, layout, filters)))

	Column(Modifier.verticalScroll(rememberScrollState())) {
		Heading(
			"Filters",
			"What a new window's listing shows, for each kind of listing, drawn as a list or as a gallery, in an explorer " +
				"and in a file picker: a gallery can leave out hidden files that a list shows, say. A window's filter menu " +
				"changes its own, wherever it goes.",
		)
		ListingChoice(settings, onChange)
		ChoiceRow("View", Layout.entries, layout, {
			if (it ==
				Layout.List
			) "Details" else "Gallery"
		}) {
			onChange(settings.copy(filteringIn = it))
		}
		OwnRow(role, listings.ownFilters(role, kind, layout)) { own ->
			onChange(
				settings.copy(
					listings = listings.filtering(role, kind, layout, if (own) filters else null),
				),
			)
		}
		Spacer(Modifier.height(Dims.gap))
		CheckBox("Show hidden files", filters.hidden.files, Modifier.testTag("hidden-files")) {
			filtered(filters.copy(hidden = filters.hidden.copy(files = it)))
		}
		CheckBox(
			"Show hidden folders",
			filters.hidden.folders,
			Modifier.testTag("hidden-folders"),
		) {
			filtered(filters.copy(hidden = filters.hidden.copy(folders = it)))
		}
		Spacer(Modifier.height(Dims.gap))
		Text("Kinds shown (every kind, where none is ticked)", style = type.body)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(Dims.gap * 2)) {
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
		Spacer(Modifier.height(Dims.gap))
		Text("Changes show in windows opened once applied.", style = type.muted)
	}
}

/** Whether a picker's listing has its own look or filters, or the explorer's; nothing to choose of the explorer's. */
@Composable
private fun OwnRow(role: Role, own: Boolean, onOwn: (Boolean) -> Unit) {
	if (role == Role.Picker)
		CheckBox(
			"Its own, apart from the explorer's (as the explorer's, where not)",
			own,
			Modifier.testTag("own"),
			onChange = onOwn,
		)
}

/** What a listing of [kind] is called. */
private fun labelOf(kind: ListingKind): String =
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

/** One of [choices], each a button, the one [chosen] marked. */
@Composable
private fun <T> ChoiceRow(
	label: String,
	choices: List<T>,
	chosen: T,
	name: (T) -> String,
	onChoose: (T) -> Unit,
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.padding(vertical = Dims.gapSmall),
	) {
		Text(label, style = LocalType.current.body, modifier = Modifier.width(160.dp))
		FlowRow(
			horizontalArrangement = Arrangement.spacedBy(Dims.gap),
			verticalArrangement = Arrangement.spacedBy(Dims.gap),
		) {
			choices.forEach { choice ->
				PushButton(
					name(choice),
					if (choice == chosen)
						Emphasis.Primary
					else
						Emphasis.Secondary,
					enabled = true,
					Modifier.testTag("$label:${name(choice)}"),
				) { onChoose(choice) }
			}
		}
	}
}

/** A value said as [shown], made smaller or larger a step at a time, as far as [smaller] and [larger] allow. */
@Composable
private fun StepRow(
	label: String,
	shown: String,
	smaller: Boolean,
	larger: Boolean,
	onStep: (Float) -> Unit,
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.padding(vertical = Dims.gapSmall),
	) {
		Text(label, style = LocalType.current.body, modifier = Modifier.width(160.dp))
		PushButton(
			"−",
			Emphasis.Secondary,
			enabled = smaller,
			Modifier.testTag("$label:smaller"),
		) {
			onStep(-1f)
		}
		Text(
			shown,
			style = LocalType.current.body,
			modifier = Modifier.width(72.dp).padding(horizontal = Dims.gap),
		)
		PushButton(
			"+",
			Emphasis.Secondary,
			enabled = larger,
			Modifier.testTag("$label:larger"),
		) {
			onStep(1f)
		}
	}
}

/** A size, as a percentage, stepped through [steps]: from the nearest of them, to the next smaller or larger. */
@Composable
private fun ZoomRow(
	label: String,
	scale: Float,
	steps: List<Float>,
	onScale: (Float) -> Unit,
) {
	val at = steps.indices.minBy { kotlin.math.abs(steps[it] - scale) }

	StepRow(label, "${(steps[at] * 100).roundToInt()} %", at > 0, at < steps.lastIndex) {
		onScale(steps[(at + it.toInt()).coerceIn(0, steps.lastIndex)])
	}
}

/** A dot, filled where it is the one chosen of its kind. */
@Composable
private fun RadioDot(chosen: Boolean) {
	val palette = LocalPalette.current

	Box(
		Modifier
			.size(16.dp)
			.border(1.dp, if (chosen) palette.accent else palette.fieldEdge, CircleShape)
			.padding(4.dp)
			.background(if (chosen) palette.accent else Color.Transparent, CircleShape),
	)
}

/** The sizes a listing starts at: the steps Ctrl and the wheel zoom a list through. */
private val LISTING_STEPS = listOf(0.75f, 0.875f, 1f, 1.25f, 1.5f, 2f)

private fun directionOf(ascending: Boolean): String =
	if (ascending) "Ascending" else "Descending"

/**
 * The actions the menus offer beyond prism's own, and the places they show: which there are, and, of those the
 * preferences write that did not read, why not, so that one written wrong is not simply missing from a menu. They are
 * written in the preferences file, as docs/menus.md says how.
 */
@Composable
private fun ActionsPage(actions: Actions) {
	val type = LocalType.current
	val palette = LocalPalette.current

	Column(Modifier.fillMaxSize()) {
		Text(
			"Actions are what a context menu offers beyond prism's own. Places are what a " +
				"window shows that is not a folder. Both are written in the preferences " +
				"file, under “actions” and “places”.",
			style = type.muted,
		)
		Spacer(Modifier.height(Dims.gap))
		LazyColumn(Modifier.weight(1f).testTag("actions")) {
			if (actions.broken.isNotEmpty()) {
				item(key = "broken") {
					Text(
						"Not read",
						style = type.heading,
						modifier = Modifier.padding(top = Dims.gap, bottom = Dims.gap),
					)
				}
				items(actions.broken) { broken ->
					Column(
						Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("broken:${broken.id}"),
					) {
						Text(
							"The ${broken.what} ${broken.id?.let { "“$it”" } ?: "with no id"}",
							style = type.body.copy(color = palette.accent),
						)
						Text(broken.why, style = type.muted)
					}
				}
			}
			item(key = "actions") {
				Text(
					"Actions",
					style = type.heading,
					modifier = Modifier.padding(top = Dims.gap * 2, bottom = Dims.gap),
				)
			}
			items(actions.actions, key = { "action:${it.id}" }) { action ->
				Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
					Text(
						(action.submenu + action.label).joinToString(" ▸ "),
						style = type.body,
						maxLines = 1,
						modifier = Modifier.width(320.dp),
					)
					Text(
						action.id + " (hidden)".takeIf { action.id in actions.hidden }.orEmpty(),
						style = type.muted,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			item(key = "places") {
				Text(
					"Places",
					style = type.heading,
					modifier = Modifier.padding(top = Dims.gap * 2, bottom = Dims.gap),
				)
			}
			items(actions.places.values.toList(), key = { "place:${it.id}" }) { place ->
				Text(place.id, style = type.body, modifier = Modifier.padding(vertical = 3.dp))
			}
		}
	}
}

/**
 * The terminal "Open terminal here" opens, and what runs in a terminal runs in: found, or chosen by its name or its
 * command line; with what it would run in the home folder ([preview]), and what it is used for, said as it is typed.
 */
@Composable
private fun TerminalPage(
	settings: Settings,
	onChange: (Settings) -> Unit,
	preview: (TerminalChoice, List<String>?) -> TerminalCommand,
) {
	val type = LocalType.current
	val form = settings.terminal
	val choice = remember(form) { form.choice() }
	val opens = remember(choice) { preview(choice, null) }
	val runs = remember(choice) { preview(choice, EXAMPLE) }

	fun edit(change: (TerminalForm) -> TerminalForm) =
		onChange(settings.copy(terminal = change(form).copy(kept = null)))

	@Composable
	fun Labelled(label: String, text: String, hint: String, onText: (String) -> Unit) {
		Text(label, style = type.body)
		Field(text, onText, Modifier.fillMaxWidth(), hint = hint)
		Spacer(Modifier.height(Dims.gap))
	}

	Column(Modifier.verticalScroll(rememberScrollState())) {
		Heading(
			"Terminal",
			"What “Open terminal here” opens, and what runs in a terminal runs in. Left empty, the desktop's own is " +
				"found. Or name a terminal, which is told the folder, and given a command, as prism or its own desktop " +
				"entry knows it takes them. Or write its command lines, each in full: {folder} where the folder goes, " +
				"{command} where a command's words go. A line left empty is not used, and what it is for is off.",
		)
		Labelled(
			"Terminal",
			form.name,
			"ghostty, say",
		) { text -> edit { it.copy(name = text) } }
		Labelled(
			"Or its command line, opened in a folder",
			form.open,
			"ghostty --working-directory={folder}",
		) { text ->
			edit { it.copy(open = text) }
		}
		Labelled(
			"Its command line, running a command in a folder",
			form.run,
			"ghostty --working-directory={folder} -e {command}",
		) { text ->
			edit { it.copy(run = text) }
		}
		Text(
			when (opens) {
				is TerminalCommand.Ready ->
					"Opened in your home folder: " +
						opens.words.joinToString(" ") { "“$it”" }
				is TerminalCommand.Failed -> "“Open terminal here” is off: ${opens.why}"
			},
			style = type.muted,
		)
		Spacer(Modifier.height(Dims.gap))
		Text(
			when (runs) {
				is TerminalCommand.Ready ->
					"Running ${EXAMPLE.joinToString(" ")} there: " +
						runs.words.joinToString(" ") { "“$it”" }
				is TerminalCommand.Failed -> "Actions that run in a terminal are off: ${runs.why}"
			},
			style = type.muted,
		)
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
			"Moving through the list (Shift chooses as it moves, " +
				"Ctrl moves without choosing)"
		Scope.Leftover -> "Wherever the keyboard is, once it is done with the key"
	}

/**
 * Every action, with its chords, each one clicked to bind it anew, removed, or added to: those [Finding] shows, found
 * by words typed into the filter (Ctrl+F) or by a chord pressed, which shows what it does. The list scrolls, its
 * scrollbar beside it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BindingsPage(
	rebinding: Rebinding,
	filter: FocusRequester,
	onChange: (Rebinding) -> Unit,
) {
	val type = LocalType.current
	val palette = LocalPalette.current
	val list = rememberLazyListState()
	val finding = rebinding.finding
	val shown =
		rebinding.bindings.actions.filter {
			finding.shows(it, rebinding.bindings.of(it))
		}

	Column {
		Heading(
			"Key bindings",
			"Click a binding, then press a key (with Ctrl, Alt or Shift, if " +
				"you like) or a mouse button: the middle, back or forward. " +
				"Escape stops waiting. A chord does one thing, so one given " +
				"here is taken from whatever had it.",
		)
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Dims.gap),
		) {
			Field(
				(finding as? Finding.Words)?.text.orEmpty(),
				{ onChange(rebinding.finding(Finding.Words(it))) },
				Modifier.weight(1f).testTag("bindings-filter"),
				hint = "Find an action, or a key (Ctrl+F)",
				trailing = Glyph.Search,
				focus = filter,
			)
			// What the window that finds a shortcut found, while it is what is shown: let go of by its ×.
			(finding as? Finding.Shortcut)?.chord?.let { chord ->
				ChordChip(
					chord.toString(),
					onClick = { onChange(rebinding.finding(Finding.OPENED)) },
				) {
					onChange(rebinding.finding(Finding.ALL))
				}
			}
			Box {
				ToolButton(
					Glyph.FindKey,
					"Find a shortcut",
					enabled = true,
					Modifier.testTag("find-by-key"),
					selected = finding is Finding.Shortcut,
				) {
					// Opened afresh, every action is shown until a shortcut is pressed; pressed again, it closes.
					onChange(
						rebinding.finding(
							if ((finding as? Finding.Shortcut)?.open ==
								true
							)
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
		Spacer(Modifier.height(Dims.gap))
		rebinding.took?.let { took ->
			Text(
				"${took.chord} was taken from ${namesOf(took.from)}.",
				style = type.body.copy(color = palette.accent),
			)
			Spacer(Modifier.height(Dims.gap))
		}
		Row(Modifier.weight(1f)) {
			LazyColumn(Modifier.weight(1f).testTag("bindings"), state = list) {
				Scope.entries.forEach { scope ->
					val here = shown.filter { it.scope == scope }

					if (here.isNotEmpty())
						item(key = scope) {
							Text(
								headingOf(scope),
								style = type.heading,
								modifier = Modifier.padding(top = Dims.gap * 2, bottom = Dims.gap),
							)
						}
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
							modifier = Modifier.padding(top = Dims.gap * 2),
						)
					}
			}
			// The list's own bar, always drawn while there is more than fits: that it scrolls is plain to see.
			VerticalScrollbar(
				rememberScrollbarAdapter(list),
				Modifier.fillMaxHeight().padding(start = Dims.gapSmall).testTag("bindings-scrollbar"),
				style = scrollbarStyleOf(palette),
			)
		}
		Spacer(Modifier.height(Dims.gap))
		PushButton(
			"Reset all",
			Emphasis.Secondary,
			enabled =
				rebinding.bindings != rebinding.bindings.defaults(),
		) {
			onChange(rebinding.resetAll())
		}
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
	val shape = RoundedCornerShape(8.dp)

	Popup(
		alignment = Alignment.TopEnd,
		offset = IntOffset(0, 40),
		onDismissRequest = onClose,
		properties = PopupProperties(focusable = true),
	) {
		WindowLocals {
			Column(
				Modifier
					.width(340.dp)
					.background(palette.surface, shape)
					.border(1.dp, palette.border, shape)
					.testTag("find-shortcut"),
			) {
				Text(
					"Find Shortcut",
					style = type.title,
					textAlign = TextAlign.Center,
					modifier = Modifier.fillMaxWidth().padding(vertical = Dims.gap * 1.5f),
				)
				Row(
					Modifier
						.padding(horizontal = Dims.gap * 1.5f)
						.fillMaxWidth()
						.height(Dims.control)
						.background(palette.field, RoundedCornerShape(Dims.radius))
						.border(2.dp, palette.accent, RoundedCornerShape(Dims.radius))
						.focusRequester(keys)
						.focusable()
						.onPreviewKeyEvent { key ->
							when {
								key.type != KeyEventType.KeyDown -> true
								key.key == Key.Escape -> true.also { onClose() }
								else -> true.also { Chord.of(key)?.let(onPressed) }
							}
						}.testTag("shortcut-field")
						.padding(horizontal = Dims.gap),
					verticalAlignment = Alignment.CenterVertically,
				) {
					GlyphIcon(Glyph.Search, palette.textMuted, 16.dp)
					Spacer(Modifier.width(Dims.gap))
					Text(
						chord?.toString() ?: "Press a shortcut",
						style = if (chord == null) type.muted else type.body,
						maxLines = 1,
					)
				}
				Spacer(Modifier.height(Dims.gap * 1.5f))
				Row(
					Modifier
						.fillMaxWidth()
						.background(palette.pane, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
						.mouseChords(onPressed)
						.padding(horizontal = Dims.gap * 1.5f, vertical = Dims.gap * 1.5f)
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

/** A scrollbar in [palette]'s colours, seen without being hovered over. */
private fun scrollbarStyleOf(palette: prism.browser.Palette): ScrollbarStyle =
	ScrollbarStyle(
		minimalHeight = 24.dp,
		thickness = 8.dp,
		shape = RoundedCornerShape(4.dp),
		hoverDurationMillis = 200,
		unhoverColor = palette.textFaint.copy(alpha = 0.5f),
		hoverColor = palette.textMuted,
	)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BindingRow(
	action: Bindable,
	rebinding: Rebinding,
	onChange: (Rebinding) -> Unit,
) {
	val type = LocalType.current
	val chords = rebinding.bindings.of(action)

	Row(
		Modifier.fillMaxWidth().padding(vertical = 3.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(action.label, style = type.body, modifier = Modifier.width(260.dp))
		FlowRow(
			Modifier.weight(1f),
			horizontalArrangement = Arrangement.spacedBy(Dims.gapSmall),
			verticalArrangement = Arrangement.spacedBy(Dims.gapSmall),
		) {
			chords.forEachIndexed { index, chord ->
				val slot = Slot(action, index)

				ChordChip(
					if (rebinding.awaited == slot)
						null
					else
						chord.toString(),
					onClick = { onChange(rebinding.awaiting(slot)) },
					onRemove = { onChange(rebinding.removed(slot)) },
				)
			}

			val added = Slot(action, chords.size)

			ChordChip(
				if (rebinding.awaited ==
					added
				) null else "+",
				onClick = { onChange(rebinding.awaiting(added)) },
			)
		}
		if (chords != action.defaults)
			Text(
				"Reset",
				style = type.body.copy(color = LocalPalette.current.accent),
				modifier =
					Modifier
						.clickable {
							onChange(rebinding.reset(action))
						}.padding(horizontal = Dims.gap),
			)
	}
}

/** A chord's chip: its [text], or, where it is null, the chord awaited; clicked to bind it, and removed by its ×. */
@Composable
private fun ChordChip(
	text: String?,
	onClick: () -> Unit,
	onRemove: (() -> Unit)? = null,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Dims.radius)
	val awaiting = text == null

	Row(
		Modifier
			.height(Dims.chip)
			.background(if (awaiting) palette.accent else palette.field, shape)
			.border(
				1.dp,
				if (awaiting)
					palette.accent
				else
					palette.fieldEdge,
				shape,
			).clickable(
				remember { MutableInteractionSource() },
				indication = null,
				onClick = onClick,
			).padding(horizontal = Dims.gap)
			.testTag("chord:${text ?: "awaited"}"),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text ?: "Press a key…",
			style = type.body.copy(color = if (awaiting) palette.onAccent else palette.text),
		)
		if (onRemove != null && !awaiting)
			Text(
				"  ×",
				style = type.muted,
				modifier = Modifier.clickable(onClick = onRemove).testTag("remove:$text"),
			)
	}
}

/** “Back” and “Forward”: actions named as the bindings page names them. */
private fun namesOf(actions: List<Bindable>): String =
	actions.joinToString(" and ") { "“${it.label}”" }
