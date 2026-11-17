package prism.browser

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
 * | Enter | the field, the list | the line under the cursor opened in the editor |
 * | Escape | anywhere | back to what the window showed |
 */

/** The least of the window's width either side is given, as a share of it, when the line between them is dragged. */
private const val LEAST_SHARE = 0.2f

/** How much of the room the list of lines takes until the line between it and the file is dragged. */
private const val LIST_SHARE = 0.42f

/** How far down what is in sight of the file the line wanted is put, as a share of it. */
private const val LINE_SITS = 0.3f

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
			TextHeader(text, stops, onEvent) { key ->
				when {
					key.type != KeyEventType.KeyDown -> false
					key.key == Key.Enter || key.key == Key.NumPadEnter ->
						true.also { onEvent(BrowserEvent.TextOpened()) }
					else -> moved(key)
				}
			}
		}
		Divider()
		BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
			val room = maxWidth

			Row(Modifier.fillMaxSize()) {
				Box(
					Modifier
						.width(room * share)
						.fillMaxHeight()
						.focusRequester(stops.listing)
						.focusable()
						.onKeyEvent { key ->
							when {
								key.type != KeyEventType.KeyDown -> false
								key.key == Key.Enter || key.key == Key.NumPadEnter ->
									true.also { onEvent(BrowserEvent.TextOpened()) }
								key.key == Key.MoveHome ->
									true.also { onEvent(BrowserEvent.TextMoved(Move.First)) }
								key.key == Key.MoveEnd ->
									true.also { onEvent(BrowserEvent.TextMoved(Move.Last)) }
								key.key == Key.DirectionLeft || key.key == Key.DirectionRight ->
									text.cursor
										?.let {
											onEvent(
												BrowserEvent.TextFolded(it.path, key.key == Key.DirectionLeft),
											)
										} != null
								else -> moved(key)
							}
						},
				) {
					TextList(state, text, rows, list, menus, stops, onEvent)
				}
				Splitter { dragged ->
					share = (share + dragged / room).coerceIn(LEAST_SHARE, 1f - LEAST_SHARE)
				}
				TextPreview(text, Modifier.weight(1f).fillMaxHeight(), menus)
			}
		}
	}
	ContextMenuHost(menus, onEvent)
}

/** What is looked for, typed; the search's options; and, while its command runs, what stops it. */
@Composable
private fun TextHeader(
	text: Location.Text,
	stops: Stops,
	onEvent: (BrowserEvent) -> Unit,
	onKey: (KeyEvent) -> Boolean,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Row(
		Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		Field(
			text.query,
			{ onEvent(BrowserEvent.TextTyped(it)) },
			Modifier.weight(1f).testTag("text-query"),
			hint =
				when (text.search.query) {
					QueryUse.Typed -> "Search in ${text.scope.said}"
					QueryUse.Narrows -> "Filter what was found in ${text.scope.said}"
				},
			leading = Glyph.Search,
			focus = stops.search,
			onKey = onKey,
			onClear = {
				onEvent(BrowserEvent.TextTyped(""))
				stops.search.requestFocus()
			},
		)
		if (text.search.options.isNotEmpty())
			Toggles(
				text.search.options,
				text.search.options.filterTo(LinkedHashSet()) { it.name in text.on },
				{ it.label },
				tag = "text-option",
			) { onEvent(BrowserEvent.TextOptioned(it.name)) }
		// Room kept for it whether or not it is there, so that the field does not change its width as a search starts.
		Box(Modifier.width(type.control), contentAlignment = Alignment.Center) {
			if (text.running != null)
				Tip("Stop searching") {
					Box(contentAlignment = Alignment.Center) {
						Spinner(palette.accent, type.control - Space.s, 2.dp)
						ToolButton(
							Glyph.Close,
							"Stop searching",
							enabled = true,
							Modifier.testTag("text-stop"),
							icon = type.icon * 0.6f,
						) { onEvent(BrowserEvent.TextStopped) }
					}
				}
		}
	}
}

/** The line between the list and the file, dragged to share the room between them otherwise. */
@Composable
private fun Splitter(onDrag: (Dp) -> Unit) {
	val palette = LocalPalette.current
	val density = LocalDensity.current
	val latest by rememberUpdatedState(onDrag)

	// Wider than the line it draws, so that it can be taken hold of.
	Box(
		Modifier
			.width(Space.s)
			.fillMaxHeight()
			.pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR)))
			.pointerInput(Unit) {
				awaitPointerEventScope {
					while (true) {
						val event = awaitPointerEvent()
						val change = event.changes.firstOrNull() ?: continue

						if (event.type == PointerEventType.Move && change.pressed) {
							latest(with(density) { change.positionChange().x.toDp() })
							change.consume()
						}
					}
				}
			}.testTag("text-splitter"),
		contentAlignment = Alignment.Center,
	) {
		Box(Modifier.width(1.dp).fillMaxHeight().background(palette.border))
	}
}

// ---- the lines found ---------------------------------------------------------------------------------------------

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

	if (rows.isEmpty()) {
		TextNothing(text)

		return
	}

	LazyScrollArea(
		Modifier.fillMaxSize(),
		list,
		line = type.textRow,
		padding =
			androidx.compose.foundation.layout
				.PaddingValues(vertical = Space.xs),
		tag = "text-rows",
	) {
		items(rows.size, key = { rows[it].id }) { index ->
			val row = rows[index]

			Box(
				Modifier
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
					is TextRow.File -> FileRow(row, text, row.id == text.cursor, onEvent)
					is TextRow.Line -> LineRow(row, row.id == text.cursor)
				}
			}
		}
	}
}

/** What the list says where it has no lines: what to do, that nothing was found, or why its command failed. */
@Composable
private fun TextNothing(text: Location.Text) {
	val ending = text.results.ending
	val scope = text.scope.said

	Box(
		Modifier.fillMaxSize().testTag("text-nothing"),
		contentAlignment = Alignment.Center,
	) {
		when {
			// Still looking: nothing is said of what has not been found yet.
			text.running != null -> Unit
			ending is TextEnding.Failed ->
				EmptyState(
					Glyph.Warning,
					"“${text.search.label.removeSuffix("…")}” failed",
					ending.why,
				)
			text.search.query == QueryUse.Typed && text.query.isBlank() ->
				EmptyState(Glyph.Search, "Search in $scope", "Type what to look for.")
			text.results.hits.count > 0 ->
				EmptyState(Glyph.Search, "No lines match “${text.query.trim()}”", null)
			else -> EmptyState(Glyph.Search, "Nothing found in $scope", null)
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
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val path = row.id.path
	val name = path.fileName?.toString() ?: path.toString()
	val source = remember { MutableInteractionSource() }

	Row(
		Modifier
			.fillMaxWidth()
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
			)
			// Under its file's name, past what folds the file and its icon.
			.padding(start = Space.s + type.icon * 1.8f + Space.s, end = Space.m)
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
			modifier = Modifier.weight(1f),
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
			is Preview.Shown -> PreviewLines(text, path, preview, line, menus)
		}
	}
}

/** A file's lines, numbered, scrolled so that [wanted] is in sight, with what was found in each marked. */
@Composable
private fun PreviewLines(
	text: Location.Text,
	path: Path,
	preview: Preview.Shown,
	wanted: Long?,
	menus: ContextMenus<BrowserEvent>,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val measurer = rememberTextMeasurer()
	val density = LocalDensity.current
	val height = type.line

	// What was found in each line of this file, where places in it are lines: marked wherever it is in sight.
	val found =
		remember(text.results.hits, path, text.search.positions) {
			if (text.search.positions == Positions.Lines)
				text.results.hits[path]
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

	val digits = (preview.first + preview.lines.size).toString().length.coerceAtLeast(3)
	val gutter = figure * digits
	val longest =
		remember(preview) {
			preview.lines.maxOfOrNull { untabbed(it.text).first.length }
				?: 0
		}
	val across = rememberScrollState()

	BoxWithConstraints(Modifier.fillMaxSize()) {
		val sight = (maxHeight / height).toInt().coerceAtLeast(1)

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

		val width =
			maxOf(
				maxWidth,
				Space.m + gutter + Space.m + figure * (longest + 2) + Dims.scrollbarRoom,
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
						val marks = found[number].orEmpty()
						val drawn =
							remember(inked, marks, palette) { drawnLine(inked, marks, palette) }

						Row(
							Modifier
								.fillMaxWidth()
								.height(height)
								.background(
									if (number == wanted)
										palette.matchLine
									else
										androidx.compose.ui.graphics.Color.Transparent,
								).contextMenu(menus) { previewMenuOf(path, number, inked.text) }
								.padding(start = Space.m)
								.testTag("text-preview-line:$number"),
							verticalAlignment = Alignment.CenterVertically,
						) {
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
 * [line] as it is drawn: its tabs made spaces, each run of it in its ink (as [palette] draws a terminal's colours),
 * and [marks] of it, said of the line as it is in the file, on the fill of what matched.
 */
private fun drawnLine(
	line: InkedLine,
	marks: List<IntRange>,
	palette: Palette,
): AnnotatedString {
	val (text, at) = untabbed(line.text)

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
		marks.forEach {
			if (it.first < line.text.length)
				addStyle(
					SpanStyle(background = palette.match),
					at(it.first),
					at(minOf(it.last + 1, line.text.length)),
				)
		}
	}
}

/** What the status line says of [text]: how many lines in how many files, and how its command ended, or that it runs. */
internal fun textStatus(text: Location.Text): String {
	val rows = text.rows
	val found = text.results.hits.count

	val counted =
		when {
			rows.lines == 0 && text.running != null -> "Searching…"
			rows.lines == 0 -> "No results"
			else ->
				"${counted(rows.lines, "line")} in ${counted(rows.files, "file")}" +
					// Narrowed, of how many found.
					" of ${"%,d".format(found)}".takeIf { rows.lines != found }.orEmpty()
		}

	val ended =
		when (text.results.ending.takeIf { text.running == null }) {
			TextEnding.Capped -> "stopped at the first ${"%,d".format(Hits.LIMIT)}"
			TextEnding.Stopped -> "stopped"
			is TextEnding.Done, is TextEnding.Failed, null -> null
		}

	val searching = "searching…".takeIf { text.running != null && rows.lines > 0 }

	return listOfNotNull(counted, ended, searching).joinToString(" · ")
}

private fun counted(count: Int, noun: String): String =
	if (count == 1)
		"1 $noun"
	else
		"${"%,d".format(count)} ${noun}s"
