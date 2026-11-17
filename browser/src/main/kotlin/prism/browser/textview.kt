package prism.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlinx.coroutines.delay
import prism.index.query.QueryText

/*
 * What a text search found, drawn (docs/text.md), in the listing's place: what is looked for, typed, with the
 * search's options beside it; under that, on one side, the lines found, each under the file it is in; and on the
 * other the file the line under the cursor is in, at that line, with what matched in it marked.
 *
 * Like the rest of the view it is a function of the state, and reports what the user does as events. What it keeps of
 * its own is what any view keeps: how far each side is scrolled, and how the room is shared between them.
 *
 * | keys | where | what |
 * |---|---|---|
 * | Up, Down, Page Up, Page Down | the field, the list | the cursor, among the files and their lines |
 * | Home, End | the list | the first row, the last |
 * | Left, Right | the list | a file's lines put away under it, or shown again |
 * | Escape | anywhere | back to what the window showed |
 */

/** The least width either side is given, however the line between them is dragged, where there is room for both. */
private val LEAST_SIDE = 200.dp

/** How much of the room the list of lines takes until the line between it and the file is dragged. */
private const val LIST_SHARE = 0.3f

/** How far down what is in sight of the file the line wanted is put, as a share of it. */
private const val LINE_SITS = 0.3f

/** The most pixels wide the file beside the list is laid out: what is wider cannot be measured, and is not scrolled to. */
private const val WIDEST = 100_000

/** What [text] found, drawn in the listing's place. */
@Composable
internal fun TextPane(
	state: BrowserState,
	text: Location.Text,
	stops: Stops,
	modifier: Modifier,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val menus = rememberContextMenus<BrowserEvent>()
	val list = rememberLazyListState()
	val rows = text.rows
	var share by remember { mutableStateOf(LIST_SHARE) }

	/** How many rows a page is: those in sight but one, so that the last seen before is the first seen after. */
	fun page(): Int = (list.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)

	/** What [key] does to the cursor, pressed in the field or in the list: whether it did anything. */
	fun moved(key: KeyEvent): Boolean {
		val move =
			when (key.key) {
				Key.DirectionDown -> Move.By(1)
				Key.DirectionUp -> Move.By(-1)
				Key.PageDown -> Move.By(page())
				Key.PageUp -> Move.By(-page())
				else -> null
			}

		move?.let { onEvent(BrowserEvent.TextMoved(it)) }

		return move != null
	}

	// The keyboard starts in the field: a search is opened to type what to look for.
	LaunchedEffect(text.search, text.scope) { stops.search.requestFocus() }

	// The row under the cursor is kept in sight, as it moves and as more is found above it.
	val cursorAt = text.cursor?.let(rows::indexOf)

	LaunchedEffect(cursorAt) {
		val shown = list.layoutInfo.visibleItemsInfo
		val whole =
			shown.filter {
				it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset
			}

		if (cursorAt != null && shown.isNotEmpty() && whole.none { it.index == cursorAt })
			list.scrollToItem(
				if (cursorAt < (whole.firstOrNull()?.index ?: 0))
					cursorAt
				else
					(cursorAt - whole.size + 1).coerceAtLeast(0),
			)
	}

	Column(modifier.fillMaxHeight().background(palette.surface).testTag("text")) {
		// What is typed is the window's own text, as a toolbar's is, whatever size the listing is drawn at.
		CompositionLocalProvider(LocalType provides LocalTypes.current.general) {
			TextHeader(state, text, stops, onEvent) { key ->
				key.type == KeyEventType.KeyDown && moved(key)
			}
		}
		Divider()
		// With nothing found by its one step there is no file to show beside it: what is said of that has all the room.
		if (text.shown.hits.groups
				.isEmpty() && text.stages.size == 1
		)
			// It is where the list would be, and takes the keyboard as the list does.
			TextNothing(
				text,
				Modifier
					.weight(1f)
					.fillMaxWidth()
					.focusRequester(stops.listing)
					.focusable(),
			)
		else
			BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
				val room = maxWidth
				// As much of the room as was asked for, but never so little of it that either side cannot be read;
				// half each, where there is not room for that.
				val least = minOf(LEAST_SIDE, room / 2)
				val left = (room * share).coerceIn(least, room - least)

				Row(Modifier.fillMaxSize()) {
					Column(Modifier.width(left).fillMaxHeight().testTag("text-list")) {
						CompositionLocalProvider(LocalType provides LocalTypes.current.general) {
							ListHeader(text, rows, stops, onEvent)
						}
						Box(
							Modifier
								.weight(1f)
								.fillMaxWidth()
								.focusRequester(stops.listing)
								.focusable()
								.onKeyEvent { key ->
									when {
										key.type != KeyEventType.KeyDown -> false
										key.key == Key.MoveHome ->
											true.also { onEvent(BrowserEvent.TextMoved(Move.First)) }
										key.key == Key.MoveEnd ->
											true.also { onEvent(BrowserEvent.TextMoved(Move.Last)) }
										key.key == Key.DirectionLeft || key.key == Key.DirectionRight ->
											text.cursor
												?.let {
													onEvent(
														BrowserEvent.TextFolded(
															it.path,
															key.key == Key.DirectionLeft,
														),
													)
												} != null
										else -> moved(key)
									}
								},
						) {
							if (rows.isEmpty())
								TextNothing(text, Modifier.fillMaxSize())
							else
								TextList(state, text, rows, list, menus, stops, onEvent)
						}
					}
					Box(Modifier.width(1.dp).fillMaxHeight().background(palette.border))
					TextPreview(text, Modifier.weight(1f).fillMaxHeight(), menus, stops, onEvent)
				}
				// Over the line between them, and wider than it, so that it can be taken hold of.
				Splitter(Modifier.offset(x = left - Space.xs)) { dragged ->
					share = ((left + dragged) / room).coerceIn(0f, 1f)
				}
			}
	}
	ContextMenuHost(menus, onEvent)
}

/**
 * The search's steps, one after another; and, under them, what is typed for the step chosen among them, that step's
 * options, and, while a command runs, what stops it.
 */
@Suppress("DEPRECATION") // What colours a value-based field, as Field is.
@Composable
private fun TextHeader(
	state: BrowserState,
	text: Location.Text,
	stops: Stops,
	onEvent: (BrowserEvent) -> Unit,
	onKey: (KeyEvent) -> Boolean,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val step = text.step
	val search = (step as? TextStep.Search)?.search

	val colouring =
		remember(step, state.aliases, palette) {
			(step as? TextStep.Names)?.let {
				QueryColouring(QueryText.read(it.text, state.aliases), palette)
			}
		}

	StepStrip(state, text, stops, onEvent)
	Row(
		Modifier.fillMaxWidth().padding(start = Space.m, end = Space.m, bottom = Space.s),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
			Field(
				text.query,
				{ onEvent(BrowserEvent.TextTyped(it)) },
				Modifier.fillMaxWidth().testTag("text-query"),
				hint =
					when {
						search == null -> "*.kt, dir:src, ext:md…"
						search.query == QueryUse.Typed -> "Search text"
						else -> "Filter results"
					},
				leading =
					if (search == null)
						Glyph.Filter
					else
						Glyph.Search,
				focus = stops.search,
				onKey = onKey,
				colouring = colouring ?: androidx.compose.ui.text.input.VisualTransformation.None,
				onClear = {
					onEvent(BrowserEvent.TextTyped(""))
					stops.search.requestFocus()
				},
			)
			// In the field, at its end, before the cross that empties it: nothing beside the field moves as a
			// search starts and ends, which it does at every letter.
			if (text.running != null)
				Stopper(Modifier.padding(end = type.control)) { onEvent(BrowserEvent.TextStopped) }
		}
		if (search != null && search.options.isNotEmpty())
			Toggles(
				search.options,
				search.options.filterTo(LinkedHashSet()) { it.name in text.on },
				{ it.label },
				tag = "text-option",
			) { onEvent(BrowserEvent.TextOptioned(it.name)) }
		// A step after the first can be turned about, to give what it finds nothing in.
		if (step is TextStep.Search && text.at > 0)
			Toggles(
				listOf(WITHOUT),
				if (step.without) setOf(WITHOUT) else emptySet(),
				{ it },
				tag = "text-without",
			) { onEvent(BrowserEvent.TextWithout) }
	}
}

/** What turns a step about is called. */
private const val WITHOUT = "Without"

/** What a step's chip calls it: the search it is, without the dots a menu's entry ends in; or that it is of names. */
private fun TextStep.label(): String =
	when (this) {
		is TextStep.Search -> search.label.removeSuffix("…")
		is TextStep.Names -> "File names"
	}

/**
 * The steps of the search, in the order they are run: where it looks; then each step, as a chip that says what it is,
 * what is typed for it, and how many files it gave. The one what is typed is for is lit, and any is made that one by
 * a click. Each but the first has a cross that takes it out. After them, what adds a step.
 */
@Composable
private fun StepStrip(
	state: BrowserState,
	text: Location.Text,
	stops: Stops,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val scroll = rememberScrollState()

	// A step added is at the end, which is brought into sight.
	LaunchedEffect(text.stages.size) { scroll.scrollTo(scroll.maxValue) }
	Row(
		Modifier
			.fillMaxWidth()
			.padding(horizontal = Space.m, vertical = Space.s)
			.testTag("text-steps"),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Row(
			Modifier.weight(1f, fill = false).horizontalScroll(scroll),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.xs),
		) {
			Text(
				"In ${text.scope.said}",
				style = type.muted,
				maxLines = 1,
				softWrap = false,
				modifier = Modifier.padding(end = Space.xxs),
			)
			text.stages.forEachIndexed { index, stage ->
				GlyphIcon(Glyph.Chevron, palette.textFaint, type.icon * 0.6f)
				StepChip(text, index, stage) { event ->
					onEvent(event)
					stops.search.requestFocus()
				}
			}
		}
		MenuButton(
			Glyph.Plus,
			"Add a step",
			{ stepMenuOf(state, text) },
			{ event ->
				onEvent(event)
				stops.search.requestFocus()
			},
			Modifier.testTag("text-step-add"),
		)
	}
}

/** One step of the search, as its chip. */
@Composable
private fun StepChip(
	text: Location.Text,
	index: Int,
	stage: Stage,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val chosen = index == text.at
	val step = stage.step
	val failed = stage.done?.failure != null
	val shape = RoundedCornerShape(Radius.medium)
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()

	Row(
		Modifier
			.height(type.row - Space.xs)
			.background(
				when {
					chosen -> palette.chosen
					felt.hovered -> palette.hover
					else -> palette.sunken
				},
				shape,
			).border(1.dp, if (chosen) palette.accent else palette.border, shape)
			.clickable(source, indication = null, onClickLabel = "Edit this step") {
				onEvent(BrowserEvent.TextStepChosen(index))
			}.focusProperties { canFocus = false }
			.padding(start = Space.s, end = if (index > 0) Space.xxs else Space.s)
			.semantics { selected = chosen }
			.testTag("text-step:$index"),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		if ((step as? TextStep.Search)?.without == true)
			Text("without", style = type.muted.copy(color = palette.dangerText), maxLines = 1)
		Text(step.label(), style = type.muted, maxLines = 1, softWrap = false)
		if (step.typed.isNotBlank())
			Text(
				"“${step.typed.trim()}”",
				style = type.body,
				maxLines = 1,
				softWrap = false,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = type.line * 12),
			)
		// How many files it gave; or that it is still looking, or failed.
		when {
			failed -> GlyphIcon(Glyph.Warning, palette.dangerText, type.icon * 0.8f)
			text.under?.index == index -> Spinner(palette.textMuted, type.icon * 0.7f, 2.dp)
			else ->
				stage.done?.let { done ->
					Text(
						"%,d".format(done.out.hits.groups.size) +
							"+".takeIf { done.ending == TextEnding.Capped }.orEmpty(),
						style = type.muted.copy(color = palette.textFaint).tabular(),
						maxLines = 1,
						softWrap = false,
						modifier = Modifier.testTag("text-step-count:$index"),
					)
				}
		}
		if (index > 0)
			ToolButton(
				Glyph.Close,
				"Remove this step",
				enabled = true,
				Modifier.testTag("text-step-remove:$index"),
				size = type.row - Space.s,
				icon = type.icon * 0.55f,
			) { onEvent(BrowserEvent.TextStepRemoved(index)) }
	}
}

/**
 * That the search's command is running, once it has run for a moment (most runs are over sooner, and say nothing):
 * an arc going round, about the square that stops it, which the pointer presses.
 */
@Composable
private fun Stopper(modifier: Modifier, onStop: () -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()

	val waited by produceState(false) {
		delay(Wait.GRACE_MILLIS)
		value = true
	}

	if (waited)
		Tip("Stop searching", modifier) {
			Box(
				Modifier
					.size(type.icon + Space.xs)
					.clickable(
						source,
						indication = null,
						onClickLabel = "Stop searching",
						onClick = onStop,
					).focusProperties { canFocus = false }
					.testTag("text-stop"),
				contentAlignment = Alignment.Center,
			) {
				Spinner(palette.accent, type.icon, 2.dp)
				Box(
					Modifier
						.size(type.icon * 0.3f)
						.background(
							if (felt.hovered)
								palette.text
							else
								palette.textMuted,
							RoundedCornerShape(1.dp),
						),
				)
			}
		}
}

/** What is taken hold of to drag the line between the list and the file, and share the room between them otherwise. */
@Composable
private fun Splitter(modifier: Modifier, onDrag: (Dp) -> Unit) {
	val density = LocalDensity.current
	val latest by rememberUpdatedState(onDrag)

	Box(
		modifier
			.width(Space.s)
			.fillMaxHeight()
			.pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR)))
			.pointerInput(Unit) {
				awaitPointerEventScope {
					// Where in it the pointer took hold of it. It follows the pointer as it is dragged, so how far
					// the pointer is from there is how far it has still to go, which is what is reported.
					var held: Float? = null

					while (true) {
						val event = awaitPointerEvent()
						val change = event.changes.firstOrNull() ?: continue

						when (event.type) {
							PointerEventType.Press -> {
								held = change.position.x
								change.consume()
							}
							PointerEventType.Release -> held = null
							PointerEventType.Move ->
								held?.takeIf { change.pressed }?.let {
									latest(with(density) { (change.position.x - it).toDp() })
									change.consume()
								}
						}
					}
				}
			}.testTag("text-splitter"),
	)
}

// ---- the lines found ---------------------------------------------------------------------------------------------

/**
 * What is over the list: how much it lists; and what folds every file or unfolds them, what adds a step that narrows
 * the files by a query of their names, and what shows the files as a listing of files.
 */
@Composable
private fun ListHeader(
	text: Location.Text,
	rows: TextRows,
	stops: Stops,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val everyFolded = rows.files > 0 && rows.paths.all { it in text.folded }

	@Composable
	fun Tool(
		glyph: Glyph,
		says: String,
		tag: String,
		enabled: Boolean = true,
		onClick: () -> Unit,
	) = ToolButton(
		glyph,
		says,
		enabled,
		Modifier.testTag(tag),
		size = type.row - Space.xs,
		icon = type.icon * 0.8f,
		onClick = onClick,
	)

	Row(
		Modifier
			.fillMaxWidth()
			.height(type.row)
			.background(palette.window)
			.padding(start = Space.m, end = Space.xs),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.xxs),
	) {
		Text(
			"${counted(rows.files, "file")}, ${counted(rows.lines, "line")}",
			style = type.muted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f).testTag("text-count"),
		)
		Tool(
			if (everyFolded)
				Glyph.ChevronDown
			else
				Glyph.Chevron,
			if (everyFolded)
				"Expand all files"
			else
				"Collapse all files",
			"text-fold-all",
			enabled = rows.files > 0,
		) { onEvent(BrowserEvent.TextFoldedAll(!everyFolded)) }
		Tool(Glyph.Filter, "Filter files by name", "text-filter") {
			onEvent(BrowserEvent.TextStepAdded(null))
			stops.search.requestFocus()
		}
		Tool(
			Glyph.ViewList,
			"Show as file list",
			"text-as-files",
			enabled =
				rows.files > 0,
		) {
			onEvent(BrowserEvent.TextListed)
		}
	}
	Divider()
}

/** How tall a row of the list is: a line of its text, and a little room. */
private val Type.textRow: Dp get() = line + Space.s

/** The files and the lines found in them; or, where there are none, why not. */
@Composable
private fun TextList(
	state: BrowserState,
	text: Location.Text,
	rows: TextRows,
	list: LazyListState,
	menus: ContextMenus<BrowserEvent>,
	stops: Stops,
	onEvent: (BrowserEvent) -> Unit,
) {
	val type = LocalType.current
	val bindings = LocalBindings.current
	val world = LocalWorld.current
	val current by rememberUpdatedState(state)

	val measurer = rememberTextMeasurer()
	val density = LocalDensity.current
	val mono = type.mono

	val figure =
		remember(mono, density) {
			with(density) {
				measurer
					.measure("0", mono)
					.size.width
					.toDp()
			}
		}

	val across = rememberScrollState()

	BoxWithConstraints(Modifier.fillMaxSize()) {
		val sight = maxWidth

		// As wide as its longest line, where that is wider than its room: it is then scrolled across, as it is down.
		val width =
			maxOf(
				sight,
				type.lineInset + type.line * 2 + Space.m + figure * (rows.longest + 2) +
					Dims.scrollbarRoom,
			)

		Box(Modifier.fillMaxSize().wheelScrolled(list, type.textRow, across = across)) {
			Box(Modifier.fillMaxSize().horizontalScroll(across, enabled = false)) {
				LazyColumn(
					Modifier.width(width).fillMaxHeight().testTag("text-rows"),
					state = list,
					contentPadding =
						androidx.compose.foundation.layout
							.PaddingValues(vertical = Space.xs),
					userScrollEnabled = false,
				) {
					items(rows.size, key = { rows[it].id }) { index ->
						val row = rows[index]

						Box(
							Modifier
								.fillMaxWidth()
								.pressed(
									row.id,
									{
										stops.listing.requestFocus()
										onEvent(BrowserEvent.TextPointed(row.id))
									},
								) {
									onEvent(
										when (row) {
											is TextRow.File -> BrowserEvent.TextFolded(row.id.path)
											is TextRow.Line -> BrowserEvent.TextOpened(row.id)
										},
									)
								}.contextMenu(
									menus,
									opening = {
										stops.listing.requestFocus()
										onEvent(BrowserEvent.TextPointed(row.id))
									},
								) { textMenuOf(current, row, bindings, world) },
						) {
							when (row) {
								// A file's row stays where it is as its lines are scrolled across: it says whose they are.
								is TextRow.File ->
									FileRow(
										row,
										text,
										row.id == text.cursor,
										Modifier
											.width(sight)
											.offset {
												androidx.compose.ui.unit
													.IntOffset(across.value, 0)
											},
										onEvent,
									)
								is TextRow.Line -> LineRow(row, row.id == text.cursor)
							}
						}
					}
				}
			}
			ScrollbarBeside(rememberScrollbarAdapter(list), Modifier.fillMaxSize())
			ScrollbarBelow(rememberScrollbarAdapter(across), Modifier.fillMaxSize())
		}
	}
}

/** How far in a line found starts: under its file's name, past what folds the file and its icon. */
private val Type.lineInset: Dp get() = Space.s + icon * 1.8f + Space.s

/** What the list says where it has nothing: what to do, that nothing was found, or which step failed and why. */
@Composable
private fun TextNothing(text: Location.Text, modifier: Modifier) {
	val scope = text.scope.said
	val failed = text.failed
	val first = text.stages.first().step as TextStep.Search

	Box(modifier.testTag("text-nothing"), contentAlignment = Alignment.Center) {
		when {
			failed != null -> TextFailed(text.stages[failed.first].step.label(), failed.second)
			// Still looking: nothing is said of what has not been found yet.
			text.busy -> Unit
			text.stages.size == 1 && first.idle ->
				EmptyState(Glyph.Search, first.label(), "Type to search in $scope.")
			text.stages.size == 1 && first.search.query == QueryUse.Narrows &&
				first.query.isNotBlank() ->
				EmptyState(Glyph.Search, "No lines contain “${first.query.trim()}”", null)
			text.stages.size == 1 ->
				EmptyState(
					Glyph.Search,
					"Nothing found",
					"Nothing in $scope matches.",
				)
			else -> EmptyState(Glyph.Search, "Nothing found", "No file matches every step.")
		}
	}
}

/**
 * That the search's command failed, and what it wrote of why, set as it wrote it: a program that points at where a
 * pattern goes wrong points with spaces, which only letters of one width keep in place.
 */
@Composable
private fun TextFailed(label: String, why: String) {
	val palette = LocalPalette.current
	val type = LocalTypes.current.general

	Column(
		Modifier.padding(Space.xxl),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(Space.m),
	) {
		GlyphIcon(Glyph.Warning, palette.textFaint, type.line * 2.4f)
		Text("“$label” failed", style = type.title)
		// What is said can be selected and copied.
		androidx.compose.foundation.text.selection.SelectionContainer {
			Text(
				why,
				style = type.mono.copy(color = palette.textMuted),
				modifier =
					Modifier
						.background(palette.sunken, RoundedCornerShape(Radius.medium))
						.padding(horizontal = Space.l, vertical = Space.m)
						.testTag("text-failure"),
			)
		}
	}
}

/** [path] as a list of what was found in [scope] says where it is: from the folder searched, or from the root. */
internal fun shownFrom(path: Path, scope: TextScope): String =
	(
		path.parent
			?.takeIf { it.startsWith(scope.folder) }
			?.let(scope.folder::relativize)
			?: path.parent
	)?.toString().orEmpty()

/** A file lines were found in: what folds it, its icon and its name, where it is, and how many lines. */
@Composable
private fun FileRow(
	row: TextRow.File,
	text: Location.Text,
	chosen: Boolean,
	modifier: Modifier,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val path = row.id.path
	val name = path.fileName?.toString() ?: path.toString()
	val source = remember { MutableInteractionSource() }

	Row(
		modifier
			.height(type.textRow)
			.background(
				if (chosen)
					palette.chosen
				else
					androidx.compose.ui.graphics.Color.Transparent,
			).padding(start = Space.s, end = Space.m)
			.semantics { selected = chosen }
			.testTag("text-file:$name"),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Box(
			Modifier.pressed(row.id to row.folded, { onEvent(BrowserEvent.TextFolded(path)) }) {},
			contentAlignment = Alignment.Center,
		) {
			GlyphIcon(
				if (row.folded)
					Glyph.Chevron
				else
					Glyph.ChevronDown,
				palette.textMuted,
				type.icon * 0.8f,
				Modifier.testTag("text-fold:$name"),
			)
		}
		GlyphIcon(Glyph.File, palette.textMuted, type.icon, category = categoryOf(name))
		Spacer(Modifier.width(Space.xxs))
		Text(name, style = type.strong, maxLines = 1, softWrap = false)
		Text(
			shownFrom(path, text.scope),
			style = type.muted.copy(color = palette.textFaint),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			"%,d".format(row.lines),
			style = type.muted.tabular(),
			maxLines = 1,
			softWrap = false,
		)
	}
}

/** A line found: its place in its file, and its text, with what matched marked. */
@Composable
private fun LineRow(row: TextRow.Line, chosen: Boolean) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shown = remember(row.hit) { row.hit.excerpt() }
	val line =
		remember(shown, palette) {
			marked(AnnotatedString(shown.text), shown.marks, palette.match)
		}

	Row(
		Modifier
			.fillMaxWidth()
			.height(type.textRow)
			.background(
				if (chosen)
					palette.chosen
				else
					androidx.compose.ui.graphics.Color.Transparent,
			).padding(start = type.lineInset, end = Space.m)
			.semantics { selected = chosen }
			.testTag("text-line:${row.id.path.fileName}:${row.hit.position}"),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			row.hit.position
				?.toString()
				.orEmpty(),
			style = type.mono.copy(color = palette.textFaint).tabular(),
			textAlign = TextAlign.End,
			maxLines = 1,
			softWrap = false,
			modifier = Modifier.widthIn(min = type.line * 2),
		)
		Spacer(Modifier.width(Space.m))
		BasicText(
			line,
			style = type.mono,
			maxLines = 1,
			softWrap = false,
			overflow = TextOverflow.Clip,
		)
	}
}

/** [text] with [marks] of it drawn on [fill]. */
private fun marked(
	text: AnnotatedString,
	marks: List<IntRange>,
	fill: androidx.compose.ui.graphics.Color,
): AnnotatedString =
	if (marks.isEmpty())
		text
	else
		buildAnnotatedString {
			append(text)
			marks.forEach {
				if (it.first < text.length && !it.isEmpty())
					addStyle(
						SpanStyle(background = fill),
						it.first,
						minOf(it.last + 1, text.length),
					)
			}
		}

/**
 * The primary button pressed here doing [onPress], and pressed again at once [onTwice]: a double click, whose first
 * press has done what one press does, as a row chosen and then opened is.
 */
private fun Modifier.pressed(
	key: Any,
	onPress: () -> Unit,
	onTwice: () -> Unit,
): Modifier =
	pointerInput(key) {
		awaitPointerEventScope {
			var last: Long? = null

			while (true) {
				val event = awaitPointerEvent()
				val down = event.changes.firstOrNull { it.changedToDown() && !it.isConsumed }

				if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed &&
					down != null
				) {
					val again =
						last?.let {
							down.uptimeMillis - it <=
								viewConfiguration.doubleTapTimeoutMillis
						}

					down.consume()
					if (again == true) {
						last = null
						onTwice()
					} else {
						last = down.uptimeMillis
						onPress()
					}
				}
			}
		}
	}

// ---- the file beside them ----------------------------------------------------------------------------------------

/** The file the row under the cursor is in: which file, and its lines, at the line found. */
@Composable
private fun TextPreview(
	text: Location.Text,
	modifier: Modifier,
	menus: ContextMenus<BrowserEvent>,
	stops: Stops,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val previewed = text.previewed

	Column(modifier.background(palette.surface).testTag("text-preview")) {
		if (previewed == null)
			return@Column

		val path = previewed.ask.path
		// The line found under the cursor, where it is in the file shown, and where a place in a file is a line of it.
		val line =
			text.line
				?.takeIf { it.id.path == path && text.search.positions == Positions.Lines }
				?.hit
				?.position

		CompositionLocalProvider(LocalType provides LocalTypes.current.general) {
			val general = LocalType.current

			Row(
				Modifier
					.fillMaxWidth()
					.height(general.row)
					.background(palette.window)
					.padding(horizontal = Space.m),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.s),
			) {
				GlyphIcon(
					Glyph.File,
					palette.textMuted,
					general.icon,
					category = categoryOf(path.fileName?.toString().orEmpty()),
				)
				Text(
					path.fileName?.toString() ?: path.toString(),
					style = general.strong,
					maxLines = 1,
					softWrap = false,
					modifier = Modifier.testTag("text-preview-name"),
				)
				Text(
					shownFrom(path, text.scope),
					style = general.muted.copy(color = palette.textFaint),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				text.line?.takeIf { it.id.path == path }?.hit?.position?.let {
					Text(
						when (text.search.positions) {
							Positions.Lines -> "Line $it"
							Positions.Offsets -> "Byte %,d".format(it)
						},
						style = general.muted.tabular(),
						maxLines = 1,
						softWrap = false,
					)
				}
			}
		}
		Divider()
		when (val preview = previewed.preview) {
			is Preview.Unshown ->
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					EmptyState(Glyph.File, "Can't show this file", preview.why)
				}
			is Preview.Shown -> {
				val find = text.find

				// Every place what is looked for in this file is at, and the one gone to.
				val found =
					remember(
						preview,
						find?.text,
					) { find?.let { preview.occurrences(it.text) }.orEmpty() }

				if (find != null) {
					CompositionLocalProvider(LocalType provides LocalTypes.current.general) {
						FindBar(find, found, stops, onEvent)
					}
					Divider()
				}
				PreviewLines(text, path, preview, line, found, find?.current(found), menus)
			}
		}
	}
}

/**
 * What looks in the file shown, as Ctrl+F looks in any text: what is looked for, typed; which of the places it is at
 * is gone to, of how many; and what goes to the one before, to the next, and puts this away. Enter goes to the next
 * and Shift with it to the one before; Escape puts it away.
 */
@Composable
private fun FindBar(
	find: PreviewFind,
	found: List<Occurrence>,
	stops: Stops,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	fun close() {
		onEvent(BrowserEvent.PreviewFinding(false))
		stops.listing.requestFocus()
	}

	// The keyboard goes to it as it opens.
	LaunchedEffect(Unit) { stops.find.requestFocus() }
	Row(
		Modifier
			.fillMaxWidth()
			.background(palette.window)
			.padding(horizontal = Space.s, vertical = Space.xs)
			.testTag("text-find"),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Field(
			find.text,
			{ onEvent(BrowserEvent.PreviewFindTyped(it)) },
			Modifier.weight(1f).testTag("text-find-field"),
			hint = "Find in this file",
			leading = Glyph.Search,
			focus = stops.find,
			onKey = { key ->
				when {
					key.type != KeyEventType.KeyDown -> false
					key.key == Key.Escape -> true.also { close() }
					key.key == Key.Enter || key.key == Key.NumPadEnter ->
						true.also {
							onEvent(BrowserEvent.PreviewFindStepped(if (key.isShiftPressed) -1 else 1))
						}
					else -> false
				}
			},
		)
		Text(
			when {
				find.text.isEmpty() -> ""
				found.isEmpty() -> "No results"
				else -> "${"%,d".format(find.index(found) + 1)} of ${"%,d".format(found.size)}"
			},
			style =
				type.muted
					.copy(
						color =
							if (find.text.isNotEmpty() && found.isEmpty())
								palette.dangerText
							else
								palette.textMuted,
					).tabular(),
			maxLines = 1,
			softWrap = false,
			modifier = Modifier.padding(horizontal = Space.s).testTag("text-find-count"),
		)
		ToolButton(
			Glyph.Up,
			"Previous (Shift+Enter)",
			enabled = found.isNotEmpty(),
			Modifier.testTag("text-find-previous"),
		) { onEvent(BrowserEvent.PreviewFindStepped(-1)) }
		ToolButton(
			Glyph.Down,
			"Next (Enter)",
			enabled = found.isNotEmpty(),
			Modifier.testTag("text-find-next"),
		) { onEvent(BrowserEvent.PreviewFindStepped(1)) }
		ToolButton(
			Glyph.Close,
			"Close (Escape)",
			enabled = true,
			Modifier.testTag("text-find-close"),
			icon = type.icon * 0.7f,
		) { close() }
	}
}

/**
 * A file's lines, numbered, scrolled so that [wanted] is in sight, with what the search found in each marked; and
 * every place what is looked for in the file is at ([found]) marked another way, the one gone to ([current]) most of
 * all, and brought into sight.
 */
@Composable
private fun PreviewLines(
	text: Location.Text,
	path: Path,
	preview: Preview.Shown,
	wanted: Long?,
	found: List<Occurrence>,
	current: Occurrence?,
	menus: ContextMenus<BrowserEvent>,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val measurer = rememberTextMeasurer()
	val density = LocalDensity.current
	val height = type.line

	// What was found in each line of this file, where places in it are lines: marked wherever it is in sight.
	val matched =
		remember(text.shown.hits, path, text.search.positions) {
			if (text.search.positions == Positions.Lines)
				text.shown.hits[path]
					?.hits
					.orEmpty()
					.mapNotNull { hit -> hit.position?.let { it to hit.marks } }
					.groupBy({ it.first }, { it.second })
					.mapValues { (_, marks) -> marks.flatten() }
			else
				emptyMap()
		}

	val mono = type.mono

	val figure =
		remember(mono, density) {
			with(density) {
				measurer
					.measure("0", mono)
					.size.width
					.toDp()
			}
		}

	// Its lines are numbered where a place in the file is a line of it: what a command printed of a place that is
	// not (a dump of the bytes about one) has lines of its own, which are no lines of the file.
	val numbered = text.search.positions == Positions.Lines
	val digits = (preview.first + preview.lines.size).toString().length.coerceAtLeast(3)
	val gutter =
		if (numbered)
			figure * digits
		else
			0.dp

	// A line too long to draw whole is drawn in part ([sight]), so none is longer than that part and its two dots.
	val longest =
		remember(preview) {
			preview.lines.maxOfOrNull { untabbed(it.text.take(LINE_SHOWN + 2)).first.length }
				?: 0
		}
	val across = rememberScrollState()
	// The places what is looked for in the file is at, by the line each is in.
	val sought = remember(found) { found.groupBy({ it.line }, { it.range }) }

	/**
	 * The line at [index] as it is shown: whole, or, of one too long to draw whole, the part about the place gone to
	 * in it, or what the search found in it, or what is looked for in it, whichever there is first.
	 */
	fun shownAt(index: Int): LineSight =
		preview.lines[index].sight(
			(
				current?.takeIf { it.line == index }?.range
					?: matched[preview.first + index]?.firstOrNull()
					?: sought[index]?.firstOrNull()
			)?.first,
		)

	BoxWithConstraints(Modifier.fillMaxSize()) {
		val sight = (maxHeight / height).toInt().coerceAtLeast(1)
		val room = maxWidth

		/** The row to scroll to for line [line] to sit a way down what is in sight. */
		fun topFor(line: Long): Int =
			((line - preview.first).toInt() - (sight * LINE_SITS).toInt())
				.coerceIn(0, (preview.lines.size - 1).coerceAtLeast(0))

		// Made for the file, already at the line wanted: it is never drawn at its top first and then moved.
		val list =
			remember(path, preview.first, preview.lines.size) {
				LazyListState(wanted?.takeIf(preview::covers)?.let(::topFor) ?: 0)
			}

		LaunchedEffect(list, wanted, sight) {
			if (wanted != null && preview.covers(wanted))
				list.scrollToItem(topFor(wanted))
		}

		// The place gone to of what is looked for in the file is brought into sight, down and across: where it is
		// already, nothing moves.
		LaunchedEffect(list, current, sight) {
			if (current != null) {
				val shown = list.layoutInfo.visibleItemsInfo
				val inSight =
					shown.any {
						it.index == current.line && it.offset >= 0 &&
							it.offset + it.size <= list.layoutInfo.viewportEndOffset
					}

				if (!inSight)
					list.scrollToItem(topFor(preview.first + current.line))

				// Where it is in the line as that is shown, which for a line too long to draw whole is a part of it.
				val part = shownAt(current.line)
				val (_, spaced) = untabbed(part.line.text)
				val within = part.at(current.range) ?: 0..0

				fun at(place: Int): Int = spaced(place - current.range.first + within.first)

				with(density) {
					val starts =
						(
							Space.m + gutter + Space.m + figure *
								at(
									current.range.first,
								)
						).roundToPx()

					val ends =
						(
							Space.m + gutter + Space.m + figure *
								at(
									current.range.last + 1,
								)
						).roundToPx()

					val sees = across.value until across.value + (room - Dims.scrollbarRoom).roundToPx()

					if (starts !in sees || ends !in sees)
						across.scrollTo((starts - room.roundToPx() / 3).coerceAtLeast(0))
				}
			}
		}

		// As wide as its longest line as that is shown, and never wider than can be laid out at all, whatever its
		// tabs come to.
		val width =
			maxOf(
				maxWidth,
				minOf(
					Space.m + gutter + Space.m + figure * (longest + 2) + Dims.scrollbarRoom,
					with(density) { WIDEST.toDp() },
				),
			)

		Box(
			Modifier
				.fillMaxSize()
				.wheelScrolled(list, height, across = across),
		) {
			Box(Modifier.fillMaxSize().horizontalScroll(across, enabled = false)) {
				LazyColumn(
					Modifier.width(width).fillMaxHeight().testTag("text-preview-lines"),
					state = list,
					userScrollEnabled = false,
				) {
					items(preview.lines.size) { index ->
						val number = preview.first + index
						val inked = preview.lines[index]
						val marks = matched[number].orEmpty()
						val places = sought[index].orEmpty()
						val gone = current?.takeIf { it.line == index }?.range

						val drawn =
							remember(inked, marks, places, gone, palette) {
								val part = shownAt(index)

								drawnLine(
									part.line,
									marks.mapNotNull(part::at),
									places.mapNotNull(part::at),
									gone?.let(part::at),
									palette,
								)
							}

						Row(
							Modifier
								.fillMaxWidth()
								.height(height)
								.background(
									if (number == wanted)
										palette.matchLine
									else
										androidx.compose.ui.graphics.Color.Transparent,
								).contextMenu(menus) {
									previewMenuOf(path, number.takeIf { numbered }, inked.text)
								}.padding(start = Space.m)
								.testTag("text-preview-line:$number"),
							verticalAlignment = Alignment.CenterVertically,
						) {
							if (numbered) {
								Text(
									number.toString(),
									style =
										mono.copy(
											color =
												if (number == wanted)
													palette.textMuted
												else
													palette.textFaint,
										),
									textAlign = TextAlign.End,
									maxLines = 1,
									softWrap = false,
									modifier = Modifier.width(gutter),
								)
								Spacer(Modifier.width(Space.m))
							}
							BasicText(drawn, style = mono, maxLines = 1, softWrap = false)
						}
					}
				}
			}
			ScrollbarBeside(rememberScrollbarAdapter(list), Modifier.fillMaxSize())
			ScrollbarBelow(rememberScrollbarAdapter(across), Modifier.fillMaxSize())
		}
	}
}

/**
 * [line] as it is drawn: its tabs made spaces, each run of it in its ink (as [palette] draws a terminal's colours);
 * [marks] of it, said of the line as it is in the file, on the fill of what the search matched; and [places], where
 * what is looked for in the file is, on a fill of their own, the one gone to ([gone]) in the accent.
 */
private fun drawnLine(
	line: InkedLine,
	marks: List<IntRange>,
	places: List<IntRange>,
	gone: IntRange?,
	palette: Palette,
): AnnotatedString {
	val (text, at) = untabbed(line.text)

	fun filled(range: IntRange, style: SpanStyle, into: AnnotatedString.Builder) {
		if (range.first < line.text.length && !range.isEmpty())
			into.addStyle(style, at(range.first), at(minOf(range.last + 1, line.text.length)))
	}

	return buildAnnotatedString {
		append(text)
		line.runs.forEach { run ->
			val ink = run.ink
			val colour = (if (ink.inverse) ink.background else ink.color)?.let(palette::ansi)

			addStyle(
				SpanStyle(
					color =
						(colour ?: palette.text).let {
							if (ink.faint)
								it.copy(alpha = 0.6f)
							else
								it
						},
					fontWeight = FontWeight.Bold.takeIf { ink.bold },
					fontStyle = FontStyle.Italic.takeIf { ink.italic },
					textDecoration = TextDecoration.Underline.takeIf { ink.underlined },
				),
				at(run.range.first),
				at(run.range.last + 1),
			)
		}
		marks.forEach { filled(it, SpanStyle(background = palette.match), this) }
		places.forEach { filled(it, SpanStyle(background = palette.sought), this) }
		gone?.let {
			filled(it, SpanStyle(background = palette.accent, color = palette.onAccent), this)
		}
	}
}

/** What the status line says of [text]: how many lines in how many files, and how its last step ended, or that it runs. */
internal fun textStatus(text: Location.Text): String {
	val rows = text.rows
	val failed = text.failed
	val first = text.stages.first().step as TextStep.Search

	val counted =
		when {
			failed != null -> "“${text.stages[failed.first].step.label()}” failed"
			rows.size == 0 && text.busy -> "Searching…"
			text.stages.size == 1 && first.idle -> "Enter a search"
			rows.size == 0 -> "No results"
			else -> "${counted(rows.lines, "line")} in ${counted(rows.files, "file")}"
		}

	val ended =
		when (val ending = text.ending.takeIf { !text.busy && failed == null }) {
			TextEnding.Capped -> "stopped at the first ${"%,d".format(Hits.LIMIT)}"
			TextEnding.Stopped -> "stopped"
			// What it said beside what it found: a file it could not read, say. Its last line, which is the latest.
			is TextEnding.Done ->
				ending.note
					?.lineSequence()
					?.lastOrNull { it.isNotBlank() }
					?.trim()
			is TextEnding.Failed, null -> null
		}

	val searching = "searching…".takeIf { text.busy && rows.size > 0 }

	return listOfNotNull(counted, ended, searching).joinToString(" · ")
}

private fun counted(count: Int, noun: String): String =
	if (count == 1)
		"1 $noun"
	else
		"${"%,d".format(count)} ${noun}s"
