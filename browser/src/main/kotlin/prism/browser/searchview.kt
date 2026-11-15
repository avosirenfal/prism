package prism.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.nio.file.Path
import prism.index.query.Problem
import prism.index.query.QueryHelp
import prism.index.query.QueryText
import prism.index.query.ReadQuery
import prism.index.query.Role
import prism.window.WindowLocals
import prism.preferences.Role as WindowRole

/*
 * The search of the index, drawn over the picker as Everything's and Listary's are: a field across the top of a
 * window, the query coloured as it is read (each part by what it is, and what is wrong underlined); under it what it
 * found, a few in sight at once ([ResultList]): prism's own things the query asks for, and then the matches; and
 * along the bottom how many there are and what the keys do. The arrows, and Page Up and Down, go through what is
 * listed from the field, which keeps the keyboard throughout, the list scrolling to keep the one they are on in
 * sight; Enter goes to the one they are on, or, from the field, shows every match in the listing; Escape, or a click
 * outside, puts the window away. Its field is empty each time it opens.
 *
 * A `?` beside the field shows how a query is written ([QueryHelpPanel]) in place of all of that, and not over it:
 * left by its × or by Escape, or by clicking one of its queries, which is typed into the field.
 *
 * The right arrow on what the arrows are on (once the caret is at the end of what is typed, where it has nowhere to
 * go), or a right click on anything listed, shows what can be done to it as a menu by its row ([ActionMenuPanel]):
 * the arrows and Enter are the menu's while it is open, and the left arrow, Escape or a click elsewhere puts it away.
 * Which chord shows it is the user's to say ([Action.ShowActions]).
 */

/** The search's window over the picker, for [search] as it stands; where a match is said from `~`, [home]. */
@Composable
internal fun SearchLayer(
	search: Search,
	home: Path,
	role: WindowRole,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val field = remember { FocusRequester() }
	val shape = RoundedCornerShape(Radius.large)
	val bindings = LocalBindings.current
	val helping = (search as? Search.Ready)?.help == true
	val menu = (search as? Search.Ready)?.menu
	// Where the row the arrows are on is, for its menu to be drawn by: not known again until the row they have moved to
	// says where it is, so a menu is never drawn by the row they were on.
	var anchor by remember((search as? Search.Ready)?.highlighted) {
		mutableStateOf<Rect?>(null)
	}

	Popup(
		alignment = Alignment.TopCenter,
		onDismissRequest = { onEvent(BrowserEvent.ClosedSearch) },
		properties = PopupProperties(focusable = true),
	) {
		WindowLocals {
			Box(
				Modifier
					.fillMaxSize()
					.background(palette.scrim)
					// Taps, not clicks: a click's semantics would merge everything inside into one node.
					.pointerInput(Unit) { detectTapGestures { onEvent(BrowserEvent.ClosedSearch) } }
					.testTag("search-layer"),
				contentAlignment = Alignment.TopCenter,
			) {
				Column(
					Modifier
						.padding(top = 72.dp, bottom = Space.xl, start = Space.xl, end = Space.xl)
						.width(680.dp)
						.shadow(24.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
						.clip(shape)
						.background(palette.overlay)
						.border(1.dp, palette.border, shape)
						// A tap inside is not a tap outside.
						.pointerInput(Unit) { detectTapGestures { } }
						// Escape leaves a result's menu for the results, how a query is written for the search, and
						// the search for the browser, whatever in the window has the keyboard. The chord that opens it, pressed while it is open
						// (the window holds the keyboard, so the browser behind it is not told), starts the search
						// again: the field emptied, and the keyboard in it.
						.onPreviewKeyEvent {
							val down = it.type == KeyEventType.KeyDown
							val escape = down && it.key == Key.Escape

							// Either of the chords that open it: the search begun again, or a place to go to asked for.
							val again =
								Chord
									.of(it)
									?.takeIf { _ -> down }
									?.let(bindings::window)
									?.let { action ->
										when (action) {
											Action.SearchIndex -> BrowserEvent.OpenedSearch
											Action.GoTo -> BrowserEvent.OpenedGoTo
											else -> null
										}
									}

							when {
								escape && menu != null -> onEvent(BrowserEvent.MatchMenuClosed)
								escape && helping -> onEvent(BrowserEvent.ToggledSearchHelp)
								escape -> onEvent(BrowserEvent.ClosedSearch)
								again != null -> onEvent(again)
							}

							escape || again != null
						},
				) {
					when (search) {
						is Search.Unavailable -> Unavailable(search, field, onEvent)
						is Search.Ready ->
							if (search.help)
								QueryHelpPanel(
									onTry = { onEvent(BrowserEvent.SearchTyped(it)) },
									onClose = { onEvent(BrowserEvent.ToggledSearchHelp) },
									aliases = search.aliases,
								)
							else
								Searching(search, home, role, field, { anchor = it }, onEvent)
					}
				}
				// Over the window and not in it: a menu is as tall as it is, however few results there are.
				if (menu != null)
					ActionMenuLayer(anchor, { onEvent(BrowserEvent.MatchMenuClosed) }) {
						ActionMenuPanel(
							menu.actions.actions.mapIndexed { at, action ->
								MenuLine(action.label, listOf("↵").takeIf { at == 0 }.orEmpty())
							},
							menu.actions.on,
							{ onEvent(BrowserEvent.MatchActionChosen(it)) },
						)
					}
			}
			// The keyboard in the field whenever the field is what is shown: as the window opens, and back from the help.
			LaunchedEffect(helping) {
				if (!helping)
					field.requestFocus()
			}
		}
	}
}

/** What the window says with no index to search. */
@Composable
private fun Unavailable(
	search: Search.Unavailable,
	focus: FocusRequester,
	onEvent: (BrowserEvent) -> Unit,
) {
	val type = LocalType.current

	Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
		Text("Search is unavailable", style = type.title)
		Text(search.why, style = type.body)
		ButtonRow(Act("OK", "search-ok", focus = focus) { onEvent(BrowserEvent.ClosedSearch) })
	}
}

/**
 * What the search's field shows where it searches within pinned results: a pin, small and still, which is no button;
 * it says what it is when the pointer rests on it.
 */
@Composable
private fun PinnedBadge() {
	val palette = LocalPalette.current
	val type = LocalType.current

	Tip(PINNED_SEARCH, Modifier.testTag("search-pinned")) {
		Box(
			Modifier
				.background(palette.chosen, RoundedCornerShape(Radius.small))
				.padding(horizontal = Space.xs, vertical = Space.xxs),
		) {
			GlyphIcon(Glyph.Pin, palette.accentText, type.icon * 0.8f)
		}
	}
}

@Composable
private fun Searching(
	search: Search.Ready,
	home: Path,
	role: WindowRole,
	field: FocusRequester,
	onAnchor: (Rect) -> Unit,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val bindings = LocalBindings.current
	val read = search.read
	val colouring = remember(read, palette) { QueryColouring(read, palette) }
	val tall = type.control + Space.s
	// What is selected in the field, as it shows it: the right arrow is the caret's until the caret is at the end.
	var selection by remember { mutableStateOf(TextRange(search.text.length)) }

	fun key(key: KeyEvent): Boolean {
		if (key.type != KeyEventType.KeyDown)
			return false

		val menu = search.menu != null
		val plain =
			!key.isCtrlPressed && !key.isAltPressed && !key.isMetaPressed && !key.isShiftPressed
		val atEnd = selection.collapsed && selection.end == search.text.length
		// The field's own key for moving the caret forward, while it has somewhere to move it.
		val carets = plain && key.key == Key.DirectionRight && !atEnd
		val shows = Chord.of(key)?.let(bindings::search) == Action.ShowActions

		val event =
			when {
				// A menu open: the left arrow puts it away, and the right one has nothing further to show.
				menu && plain && key.key == Key.DirectionLeft -> BrowserEvent.MatchMenuClosed
				menu && shows -> return true
				shows && !carets && search.highlighted != null -> BrowserEvent.MatchMenuOpened()
				// Tab finishes the alias being named, the first of those it could be.
				plain && key.key == Key.Tab && search.completing != null ->
					search.completing?.let { BrowserEvent.SearchTyped(it.completed(it.aliases.first())) }
				key.key == Key.DirectionDown -> BrowserEvent.MatchMoved(1)
				key.key == Key.DirectionUp -> BrowserEvent.MatchMoved(-1)
				key.key == Key.PageDown -> BrowserEvent.MatchMoved(Search.SHOWN)
				key.key == Key.PageUp -> BrowserEvent.MatchMoved(-Search.SHOWN)
				key.key == Key.Enter || key.key == Key.NumPadEnter -> BrowserEvent.SearchEntered
				else -> null
			}

		event?.let(onEvent)

		return event != null
	}

	Row(
		Modifier.padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.m),
		horizontalArrangement = Arrangement.spacedBy(Space.s),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Field(
			search.text,
			{ onEvent(BrowserEvent.SearchTyped(it)) },
			Modifier.weight(1f).testTag("search-query"),
			hint =
				when {
					search.aim is Aim.Go -> "Go to location"
					search.refines.any -> "Search in results"
					else -> "Search everywhere"
				},
			leading = Glyph.Search,
			height = tall,
			focus = field,
			badge = (@Composable { PinnedBadge() }).takeIf { search.refines.any },
			// The keyboard arrives at the end of what was typed, to go on typing it.
			selecting = {
				androidx.compose.ui.text
					.TextRange(it.length)
			},
			onKey = ::key,
			colouring = colouring,
			style = type.body.times(1.15f),
			onSelection = { selection = it },
		)
		HelpButton(tall) { onEvent(BrowserEvent.ToggledSearchHelp) }
	}

	read.problems.firstOrNull { it.kind == Problem.Kind.Invalid }?.let {
		Text(
			it.message,
			style = type.muted.copy(color = palette.dangerText),
			modifier =
				Modifier
					.padding(horizontal = Space.l)
					.padding(bottom = Space.m)
					.testTag("search-problem"),
		)
	}

	val found = search.found

	when {
		found is Finding.Refused ->
			Said(found.why, type.muted, "search-refused")
		found is Finding.Failed -> {
			Said(
				"The search failed. This is a bug in prism, not a problem with your query.",
				type.body.copy(color = palette.dangerText),
				"search-failed",
			)
			Said(
				"${found.fault.line}. See prism's log for details.",
				type.muted,
				"search-failure",
			)
		}
	}

	if (search.listed > 0)
		ResultList(
			search.listed,
			search.highlighted,
			rowHeight = type.row + Space.xs,
			height = (type.row + Space.xs) * Search.SHOWN,
			modifier = Modifier.padding(horizontal = Space.s).padding(bottom = Space.s),
			tag = "matches",
			rowTag = { at ->
				when (val listed = search.listedAt(at)) {
					null -> "match:"
					is Search.Listed.Prisms -> "own:${listed.own.title}"
					is Search.Listed.Named -> "alias:${listed.alias.name}"
					is Search.Listed.Place -> "place:${listed.path}"
					is Search.Listed.Match -> "match:${listed.matches.nameOf(listed.rank)}"
				}
			},
			onMenu = { onEvent(BrowserEvent.MatchMenuOpened(it)) },
			onPlaced = onAnchor,
			onChoose = { onEvent(BrowserEvent.MatchChosen(it)) },
		) { at ->
			when (val listed = search.listedAt(at)) {
				null -> Unit
				is Search.Listed.Prisms -> OwnResult(listed.own)
				is Search.Listed.Named -> AliasResult(listed.alias)
				is Search.Listed.Place -> Place(listed, home)
				is Search.Listed.Match -> Match(listed.matches.rowOf(listed.rank, null), home)
			}
		}

	Footer(search, role, onEvent)
}

/** A line the window says in place of what it would list. */
@Composable
private fun Said(text: String, style: androidx.compose.ui.text.TextStyle, tag: String) {
	Text(
		text,
		style = style,
		modifier =
			Modifier
				.padding(horizontal = Space.l)
				.padding(bottom = Space.m)
				.testTag(tag),
	)
}

/**
 * The button beside the field that shows how a query is written: as tall as the field and as wide as it is tall,
 * with an edge of its own, so that it is seen to be something to press.
 */
@Composable
private fun HelpButton(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
	val palette = LocalPalette.current
	val source = remember { MutableInteractionSource() }
	val hovered by source.collectIsHoveredAsState()
	val shape = RoundedCornerShape(Radius.medium)

	Tip("Search help") {
		Box(
			Modifier
				.size(size)
				.clip(shape)
				.background(if (hovered) palette.hover else palette.sunken)
				.border(
					1.dp,
					if (hovered)
						palette.borderStrong
					else
						palette.border,
					shape,
				)
				// The field keeps the keyboard, whatever is clicked beside it.
				.focusProperties { canFocus = false }
				.hoverable(source)
				.clickable(
					source,
					indication = null,
					onClickLabel = "Search help",
					onClick = onClick,
				).testTag("search-help"),
			contentAlignment = Alignment.Center,
		) {
			GlyphIcon(
				Glyph.Help,
				if (hovered) palette.text else palette.textMuted,
				LocalType.current.icon + Space.xxs,
			)
		}
	}
}

/**
 * Along the bottom: how much was found, and what the keys do now: Enter to what the arrows are on, or from the field
 * to every match in the listing.
 */
@Composable
private fun Footer(
	search: Search.Ready,
	role: WindowRole,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val bindings = LocalBindings.current
	val matches = (search.found as? Finding.Matched)?.matches

	Divider()
	Row(
		Modifier
			.fillMaxWidth()
			.background(palette.pane)
			.padding(horizontal = Space.l, vertical = Space.s),
		horizontalArrangement = Arrangement.spacedBy(Space.l),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			when {
				!search.searchable || matches == null -> ""
				// Something of prism's own is listed: it is only among files that nothing was found.
				matches.size == 0 && search.own.isNotEmpty() -> "No files match"
				matches.size == 0 -> "No matches"
				matches.size == 1 -> "1 match"
				else -> "${"%,d".format(matches.size)} matches"
			},
			style = type.muted,
			maxLines = 1,
			modifier = Modifier.weight(1f).testTag("search-count"),
		)
		// Said until the arrows are on something: by then they are known, and the room is for what they are on.
		// The key that finishes the alias a word could name, while there is one to finish.
		if (search.completing != null && search.highlighted == null)
			KeyHint("Use alias", listOf("Tab"))
		if (search.listed > 0 && search.highlighted == null)
			KeyHint("Move", listOf("↑", "↓"))
		// What Enter does to what the arrows are on, said as its menu says it: the first of its actions.
		search.highlighted?.let(search::listedAt)?.let { listed ->
			// Where the window is to go somewhere, that is what Enter does to anything of the disk's.
			actionsOf(listed, role, terminal = false)
				.firstOrNull()
				?.let {
					KeyHint(
						if (search.aim is Aim.Go && listed !is Search.Listed.Named)
							MatchAction.GoTo.label
						else
							it.label,
						listOf("↵"),
					)
				}
			bindings.of(Action.ShowActions).firstOrNull()?.let { KeyHint("Actions", it) }
		}
		// Every match in the listing: what Enter does from the field, and a click does wherever the arrows are. Not
		// where the window is to go to one place: there is no showing them all there.
		if (matches != null && matches.size > 0 && search.searchable && search.aim is Aim.Find)
			Box(
				Modifier
					.clip(RoundedCornerShape(Radius.small))
					.clickable { onEvent(BrowserEvent.ShowedResults) }
					.testTag("search-go"),
			) {
				KeyHint(
					"Explore results",
					listOf("↵").takeIf { search.highlighted == null }.orEmpty(),
				)
			}
		KeyHint("Close", listOf("Esc"))
	}
}

/** What is on the disk at the path typed, in its row: its icon, its name, and where it is, as a match has them. */
@Composable
private fun RowScope.Place(place: Search.Listed.Place, home: Path) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val name = place.path.fileName?.toString() ?: place.path.toString()

	GlyphIcon(
		if (place.folder) Glyph.Folder else Glyph.File,
		palette.text,
		type.icon,
		category =
			if (place.folder)
				Category.Folder
			else
				categoryOf(name),
	)
	Spacer(Modifier.width(Space.s + Space.xxs))
	ResultText(name, folderOf(place.path, home))
}

/** One match, in its row: its icon, its name, and where it is. */
@Composable
private fun RowScope.Match(row: Row, home: Path) {
	val palette = LocalPalette.current
	val type = LocalType.current

	GlyphIcon(
		if (row.isFolder) Glyph.Folder else Glyph.File,
		palette.text,
		type.icon,
		category = row.category,
	)
	Spacer(Modifier.width(Space.s + Space.xxs))
	ResultText(row.name, row.path?.let { folderOf(it, home) }.orEmpty())
}
