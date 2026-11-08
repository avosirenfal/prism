package barion.picker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.nio.file.Path

/*
 * The picker, drawn: a pure function of a [PickerState]. It shows what the state says and reports what the user does
 * as a [PickerEvent]; it changes nothing itself. The only state it keeps is what a widget keeps for itself while
 * the pointer is over it or a menu is open, which is not the picker's.
 *
 * Laid out as a desktop's own file dialogs are: the way back and up and the path across the top, with a search
 * field; the places down the left; the folder's entries in columns; and the name, the type filter and the buttons
 * across the bottom.
 */

/** The picker for [state]: what it shows, and [onEvent] for what the user does. */
@Composable
fun FilePicker(
	state: PickerState,
	modifier: Modifier = Modifier,
	onEvent: (PickerEvent) -> Unit = {},
) {
	val palette = LocalPalette.current

	Column(modifier.fillMaxSize().background(palette.window)) {
		Toolbar(state, onEvent)
		Line(palette.border)
		Row(Modifier.weight(1f).fillMaxWidth()) {
			SidebarPane(state.sidebar, state.folder, onEvent)
			Box(Modifier.width(1.dp).fillMaxHeight().background(palette.border))
			ListingPane(state, Modifier.weight(1f), onEvent)
		}
		Line(palette.border)
		Footer(state, onEvent)
	}
}

@Composable
private fun Line(color: Color) =
	Box(Modifier.fillMaxWidth().height(1.dp).background(color))

// ---- the toolbar: the way back and up, the path, the search ----------------------------------------------------

@Composable
private fun Toolbar(state: PickerState, onEvent: (PickerEvent) -> Unit) {
	val palette = LocalPalette.current

	Row(
		Modifier
			.fillMaxWidth()
			.height(
				Dims.toolbar,
			).background(palette.window)
			.padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Dims.gapSmall),
	) {
		ToolButton(Glyph.Back, "Back", state.canGoBack) { onEvent(PickerEvent.Back) }
		ToolButton(
			Glyph.Forward,
			"Forward",
			state.canGoForward,
		) { onEvent(PickerEvent.Forward) }
		ToolButton(Glyph.Up, "Up", state.crumbs.size > 1) { onEvent(PickerEvent.Up) }
		Spacer(Modifier.width(Dims.gapSmall))
		Breadcrumbs(state.crumbs, Modifier.weight(1f), onEvent)
		Spacer(Modifier.width(Dims.gapSmall))
		SearchField(
			state.query,
			"Search ${state.crumbs.last().label}",
			Modifier.width(260.dp),
		) {
			onEvent(PickerEvent.Searched(it))
		}
	}
}

@Composable
private fun ToolButton(
	glyph: Glyph,
	label: String,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val palette = LocalPalette.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()

	// Dimmed first, so what is drawn after (the hover, the glyph) is dimmed with it.
	Box(
		Modifier
			.alpha(if (enabled) 1f else 0.35f)
			.width(Dims.control)
			.height(Dims.control)
			.background(
				if (hovered &&
					enabled
				)
					palette.hover
				else
					Color.Transparent,
				RoundedCornerShape(Dims.radius),
			).hoverable(hover)
			.clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
		contentAlignment = Alignment.Center,
	) {
		GlyphIcon(glyph, palette.text, Dims.icon)
	}
}

@Composable
private fun Breadcrumbs(
	crumbs: List<Crumb>,
	modifier: Modifier,
	onEvent: (PickerEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Row(
		modifier
			.height(Dims.control)
			.background(palette.field, RoundedCornerShape(Dims.radius))
			.border(1.dp, palette.fieldEdge, RoundedCornerShape(Dims.radius))
			.padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		GlyphIcon(Glyph.Folder, palette.text, 16.dp)
		Spacer(Modifier.width(Dims.gap))
		crumbs.forEachIndexed { index, crumb ->
			val last = index == crumbs.lastIndex

			Text(
				crumb.label,
				style =
					if (last)
						type.body.copy(fontWeight = FontWeight.SemiBold)
					else
						type.body,
				maxLines = 1,
				modifier =
					Modifier
						.clickable {
							onEvent(PickerEvent.Opened(crumb.path))
						}.padding(horizontal = 4.dp),
			)
			if (!last)
				GlyphIcon(Glyph.Chevron, palette.textFaint, 12.dp)
		}
	}
}

@Composable
private fun SearchField(
	query: String,
	hint: String,
	modifier: Modifier,
	onChange: (String) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Row(
		modifier
			.height(Dims.control)
			.background(palette.field, RoundedCornerShape(Dims.radius))
			.border(1.dp, palette.fieldEdge, RoundedCornerShape(Dims.radius))
			.padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.weight(1f)) {
			if (query.isEmpty())
				Text(hint, style = type.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
			BasicTextField(
				query,
				onChange,
				singleLine = true,
				textStyle = type.body,
				cursorBrush = SolidColor(palette.accent),
			)
		}
		GlyphIcon(Glyph.Search, palette.textMuted, 16.dp)
	}
}

// ---- the sidebar: the places --------------------------------------------------------------------------------

@Composable
private fun SidebarPane(sidebar: Sidebar, current: Path, onEvent: (PickerEvent) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Column(
		Modifier
			.width(Dims.sidebar)
			.fillMaxHeight()
			.background(palette.pane)
			.padding(Dims.gap),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		for (group in sidebar.groups) {
			Text(
				group.heading,
				style = type.heading,
				modifier = Modifier.padding(start = Dims.gap, top = Dims.gap, bottom = Dims.gapSmall),
			)
			for (place in group.places)
				PlaceRow(place, place.path == current, onEvent)
		}
	}
}

@Composable
private fun PlaceRow(place: Place, current: Boolean, onEvent: (PickerEvent) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()
	val background =
		when {
			current -> palette.chosen
			hovered -> palette.hover
			else -> Color.Transparent
		}

	Row(
		Modifier
			.fillMaxWidth()
			.height(Dims.row)
			.background(background, RoundedCornerShape(Dims.radius))
			.hoverable(hover)
			.clickable { onEvent(PickerEvent.Opened(place.path)) }
			.padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// The place being shown is marked as the desktop marks it: a short bar of the accent at its edge.
		Box(
			Modifier.width(3.dp).height(14.dp).background(
				if (current)
					palette.accent
				else
					Color.Transparent,
				RoundedCornerShape(2.dp),
			),
		)
		Spacer(Modifier.width(Dims.gap))
		GlyphIcon(
			glyphOf(place.kind),
			if (current)
				palette.accent
			else
				palette.textMuted,
			Dims.icon,
		)
		Spacer(Modifier.width(Dims.gap + 2.dp))
		Text(place.label, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

private fun glyphOf(kind: Place.Kind): Glyph =
	when (kind) {
		Place.Kind.Home -> Glyph.Home
		Place.Kind.Desktop -> Glyph.Desktop
		Place.Kind.Documents -> Glyph.Documents
		Place.Kind.Downloads -> Glyph.Downloads
		Place.Kind.Pictures -> Glyph.Pictures
		Place.Kind.Music -> Glyph.Music
		Place.Kind.Videos -> Glyph.Videos
		Place.Kind.Drive -> Glyph.Drive
	}

// ---- the listing: the folder's entries in columns ------------------------------------------------------------

@Composable
private fun ListingPane(
	state: PickerState,
	modifier: Modifier,
	onEvent: (PickerEvent) -> Unit,
) {
	val palette = LocalPalette.current

	Column(modifier.fillMaxHeight().background(palette.surface)) {
		when (val listing = state.listing) {
			is Listing.Unreadable -> Message("This folder can't be opened", listing.why)
			is Listing.Shown -> {
				Header(listing.sort, onEvent)
				Line(palette.divider)

				val rows = state.visibleRows
				val chosen = state.choosing.rows

				if (rows.isEmpty())
					Message(
						if (state.query.isBlank())
							"This folder is empty"
						else
							"Nothing here matches “${state.query}”",
						null,
					)
				else
					LazyColumn(
						Modifier.fillMaxSize().padding(
							horizontal = Dims.gapSmall,
							vertical = Dims.gapSmall,
						),
					) {
						items(rows, key = { it.id.name }, contentType = { "row" }) { row ->
							EntryRow(row, row.id in chosen, onEvent)
						}
					}
			}
		}
	}
}

@Composable
private fun Header(sort: Sort, onEvent: (PickerEvent) -> Unit) {
	Row(
		Modifier.fillMaxWidth().height(Dims.header).padding(
			horizontal =
				Dims.gap + Dims.gapSmall,
		),
		verticalAlignment = Alignment.CenterVertically,
	) {
		HeaderCell("Name", Sort.Column.Name, sort, Dims.nameColumn, onEvent)
		HeaderCell("Date modified", Sort.Column.Modified, sort, Dims.dateColumn, onEvent)
		HeaderCell("Type", Sort.Column.Type, sort, Dims.typeColumn, onEvent)
		HeaderCell("Size", Sort.Column.Size, sort, Dims.sizeColumn, onEvent, end = true)
	}
}

@Composable
private fun RowScope.HeaderCell(
	label: String,
	column: Sort.Column,
	sort: Sort,
	share: Float,
	onEvent: (PickerEvent) -> Unit,
	end: Boolean = false,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Row(
		Modifier
			.weight(
				share,
			).fillMaxHeight()
			.clickable { onEvent(PickerEvent.Sorted(column)) },
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = if (end) Arrangement.End else Arrangement.Start,
	) {
		Text(label, style = type.muted, maxLines = 1)
		if (sort.by == column)
			GlyphIcon(
				if (sort.ascending)
					Glyph.SortUp
				else
					Glyph.SortDown,
				palette.textMuted,
				14.dp,
			)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(row: Row, chosen: Boolean, onEvent: (PickerEvent) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()
	val shape = RoundedCornerShape(Dims.radius)
	val background =
		when {
			chosen -> palette.chosen
			hovered -> palette.hover
			else -> Color.Transparent
		}

	Row(
		Modifier
			.alpha(if (row.hidden) 0.6f else 1f)
			.fillMaxWidth()
			.height(Dims.row)
			.background(background, shape)
			.border(
				1.dp,
				if (chosen)
					palette.chosenEdge
				else
					Color.Transparent,
				shape,
			).hoverable(hover)
			.combinedClickable(
				onClick = { onEvent(PickerEvent.Chose(row.id, adding = false)) },
				onDoubleClick = { onEvent(PickerEvent.Activated(row.id)) },
			).padding(horizontal = Dims.gap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(Modifier.weight(Dims.nameColumn), verticalAlignment = Alignment.CenterVertically) {
			GlyphIcon(
				if (row.isFolder) Glyph.Folder else Glyph.File,
				palette.text,
				Dims.icon,
				category = row.category,
			)
			Spacer(Modifier.width(Dims.gap + 2.dp))
			Text(row.name, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
		Text(
			row.modified.orEmpty(),
			style = type.muted,
			maxLines = 1,
			modifier = Modifier.weight(Dims.dateColumn),
		)
		Text(
			row.type,
			style = type.muted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(Dims.typeColumn),
		)
		Text(
			row.size.orEmpty(),
			style = type.muted,
			maxLines = 1,
			textAlign = TextAlign.End,
			modifier = Modifier.weight(Dims.sizeColumn),
		)
	}
}

@Composable
private fun Message(title: String, detail: String?) {
	val type = LocalType.current

	Column(
		Modifier.fillMaxSize().padding(40.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		Text(title, style = type.title)
		detail?.let {
			Text(
				it,
				style = type.muted,
				textAlign = TextAlign.Center,
				modifier = Modifier.padding(top = Dims.gapSmall),
			)
		}
	}
}

// ---- the footer: the name, the filter, the buttons -----------------------------------------------------------

@Composable
private fun Footer(state: PickerState, onEvent: (PickerEvent) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val count = state.visibleRows.size
	val chosen = state.choosing.rows.size

	Column(
		Modifier.fillMaxWidth().height(Dims.footer).background(palette.window).padding(
			horizontal =
				Dims.gap * 2,
			vertical = Dims.gap + 2.dp,
		),
		verticalArrangement = Arrangement.spacedBy(Dims.gap + 2.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				if (state.choosing is Choosing.Folder)
					"Folder:"
				else
					"File name:",
				style = type.body,
				modifier = Modifier.width(80.dp),
			)
			NameField(state, Modifier.weight(1f), onEvent)
			Spacer(Modifier.width(Dims.gap))
			FilterField(state, Modifier.width(220.dp), onEvent)
		}
		Row(verticalAlignment = Alignment.CenterVertically) {
			val counted =
				if (count == 1)
					"1 item"
				else
					"$count items"

			Text(
				if (chosen >
					0
				)
					"$counted  ·  $chosen selected"
				else
					counted,
				style = type.muted,
				modifier = Modifier.weight(1f),
			)
			ActionButton(state.accept.label, primary = true, enabled = state.accept.enabled) {
				onEvent(PickerEvent.Accepted)
			}
			Spacer(Modifier.width(Dims.gap))
			ActionButton(
				"Cancel",
				primary = false,
				enabled = true,
			) { onEvent(PickerEvent.Cancelled) }
		}
	}
}

@Composable
private fun NameField(
	state: PickerState,
	modifier: Modifier,
	onEvent: (PickerEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val editable = state.choosing is Choosing.SaveAs
	val shown =
		if (state.choosing is Choosing.Folder &&
			state.nameField.isEmpty()
		)
			state.crumbs.last().label
		else
			state.nameField

	Box(
		modifier
			.height(Dims.control)
			.background(palette.field, RoundedCornerShape(Dims.radius))
			.border(
				1.dp,
				if (editable)
					palette.accent
				else
					palette.fieldEdge,
				RoundedCornerShape(Dims.radius),
			).padding(horizontal = Dims.gap),
		contentAlignment = Alignment.CenterStart,
	) {
		BasicTextField(
			shown,
			{ onEvent(PickerEvent.Named(it)) },
			singleLine = true,
			readOnly = !editable,
			textStyle = type.body,
			cursorBrush = SolidColor(palette.accent),
		)
	}
}

@Composable
private fun FilterField(
	state: PickerState,
	modifier: Modifier,
	onEvent: (PickerEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	var open by remember { mutableStateOf(false) }

	Box(modifier) {
		Row(
			Modifier
				.fillMaxWidth()
				.height(Dims.control)
				.background(palette.field, RoundedCornerShape(Dims.radius))
				.border(1.dp, palette.fieldEdge, RoundedCornerShape(Dims.radius))
				.clickable { open = true }
				.padding(horizontal = Dims.gap),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				state.filter.label,
				style = type.body,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			GlyphIcon(Glyph.ChevronDown, palette.textMuted, 14.dp)
		}
		DropdownMenu(open, onDismissRequest = { open = false }) {
			for (filter in state.filters)
				DropdownMenuItem(
					text = { Text(filter.label, style = type.body) },
					onClick = {
						open = false
						onEvent(PickerEvent.Filtered(filter))
					},
				)
		}
	}
}

@Composable
private fun ActionButton(
	label: String,
	primary: Boolean,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Dims.radius)

	// Dimmed first, so a button that cannot be pressed is dimmed whole: its fill and edge as well as its label.
	Box(
		Modifier
			.alpha(if (enabled) 1f else 0.45f)
			.width(112.dp)
			.height(Dims.control)
			.background(if (primary) palette.accent else palette.field, shape)
			.border(
				1.dp,
				if (primary)
					palette.accent
				else
					palette.fieldEdge,
				shape,
			).clickable(enabled = enabled, onClick = onClick),
		contentAlignment = Alignment.Center,
	) {
		Text(
			label,
			style = type.button.copy(color = if (primary) palette.onAccent else palette.text),
		)
	}
}
