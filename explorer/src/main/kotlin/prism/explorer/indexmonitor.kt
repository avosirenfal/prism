package prism.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import prism.browser.Act
import prism.browser.ButtonRow
import prism.browser.Chip
import prism.browser.Divider
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.LazyScrollArea
import prism.browser.LocalDateFormat
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Modal
import prism.browser.ModalWidth
import prism.browser.Palette
import prism.browser.PushButton
import prism.browser.Radius
import prism.browser.Space
import prism.browser.ToolButton
import prism.browser.felt
import prism.browser.isPrimaryPressed
import prism.browser.tabular
import prism.index.ActKind
import prism.index.Activity
import prism.index.ActivityLog
import prism.index.Burst
import prism.index.Doer
import prism.index.FileId
import prism.index.Heard
import prism.preferences.DateFormat
import prism.preferences.TimePrecision

/*
 * The index monitor: the file system events the index heard in the last minute ([ActivityLog]), grouped by the
 * process that caused them. It opens on the groups, latest first, as a table; a group's row opens its events in the
 * groups' place, and Back (or Escape) goes back to them.
 *
 * The groups are read again twice a second, unless the log is paused: then they are a copy of the log as it was, which
 * the monitor keeps until it is resumed or closed, while the log goes on recording. Both tables are narrowed by the
 * kinds of event (created, deleted, moved) and by what is typed: the groups by their process's name first and then by
 * their events' paths, a group's events by their paths. Ctrl+F (Cmd+F) goes to the field. What either shows can be
 * copied: a PID, a path, or the whole table as text.
 */

/** The kinds of event a table shows: those in [only], or every kind where it is empty. */
internal data class KindFilter(
	val only: Set<ActKind> = emptySet(),
) {
	/** Whether events of [kind] are shown. */
	fun admits(kind: ActKind): Boolean = only.isEmpty() || kind in only

	/** The kinds shown, every one where none is picked: what the log is asked for. */
	val kinds: Set<ActKind> get() = only.ifEmpty { ActKind.entries.toSet() }

	/** This, with [kind] picked or no longer. */
	fun toggled(kind: ActKind): KindFilter =
		KindFilter(
			if (kind in only)
				only - kind
			else
				only + kind,
		)
}

/** A copy of the log, held while it is paused, and when it was taken. */
internal data class Held(
	val activity: Activity,
	val at: Instant,
)

/** What of the index monitor is open: nothing, the groups, or one group's events in their place. */
internal sealed interface Monitoring {
	data object Closed : Monitoring

	/**
	 * The groups, narrowed by what is typed to [find] them and by the kinds of event in them: as the log holds them
	 * each time they are read, or, where the log is paused, as it was [held].
	 */
	data class Bursts(
		val held: Held? = null,
		val find: String = "",
		val filter: KindFilter = KindFilter(),
	) : Monitoring {
		val paused: Boolean get() = held != null
	}

	/**
	 * The events of [burst], narrowed by what is typed to [find] them by their paths and by their kinds; and the
	 * groups to go back [to], whose filter of kinds they start with.
	 */
	data class Changes(
		val burst: Burst,
		val to: Bursts,
		val find: String = "",
		val filter: KindFilter = to.filter,
	) : Monitoring
}

/** The groups shown: those of the log as it is, or as it was held, that what is typed and the filter find. */
internal fun Monitoring.Bursts.shown(
	log: ActivityLog,
	folders: (FileId) -> String?,
): List<Burst> = (held?.activity ?: log.live).found(find, filter.kinds, folders)

/** An event as the monitor shows it: what was heard, and its path. */
internal data class ShownChange(
	val heard: Heard,
	val path: String,
)

/** The events shown: the group's, as its groups have them, of the kinds asked for, whose paths have what is typed. */
internal fun Monitoring.Changes.shown(
	log: ActivityLog,
	folders: (FileId) -> String?,
): List<ShownChange> =
	(to.held?.activity ?: log.live)
		.heardIn(burst.id)
		.filter { filter.admits(it.act.kind) }
		.map { ShownChange(it, it.spot.said(folders)) }
		.filter { find.isBlank() || it.path.contains(find, ignoreCase = true) }

/** How many events a group has, as its row says it: "7", or "1,523 (200 kept)" where it kept fewer. */
internal fun countOf(burst: Burst): String =
	if (burst.overflowed)
		"%,d (%,d kept)".format(burst.seen, burst.kept)
	else
		"%,d".format(burst.seen)

/** The groups as text, a line each, tab-separated: what Copy list copies. */
internal fun burstsText(bursts: List<Burst>, format: DateFormat): String =
	bursts.joinToString("\n") {
		listOf(
			format.writeTime(it.from, precision = TimePrecision.Seconds),
			it.by.label,
			(it.by as? Doer.Process)?.pid?.toString().orEmpty(),
			it.seen.toString(),
		).joinToString("\t")
	}

/** The events as text, a line each, tab-separated: what Copy list copies. */
internal fun changesText(changes: List<ShownChange>, format: DateFormat): String =
	changes.joinToString("\n") {
		listOf(
			format.writeTime(it.heard.at, precision = TimePrecision.Milliseconds),
			it.heard.act.label,
			it.path,
		).joinToString("\t")
	}

/** How often what the monitor shows is read again from the log, while it is not paused. */
private val READ_EVERY = 500.milliseconds

/** How tall the table is: room for a dozen rows and more. */
private val TABLE_HEIGHT = 400.dp

/** How wide the PID column is, and the count's. */
private val PID_WIDTH = 72.dp
private val COUNT_WIDTH = 150.dp

/** How wide the event column is: room for "Special file created". */
private val ACT_WIDTH = 168.dp

/**
 * The index monitor, as [monitoring] has it open, over [log], each folder its events name placed by [folders], and
 * what is copied given to [onCopy]; whatever is done in it reported to [onMonitoring].
 */
@Composable
internal fun IndexMonitor(
	monitoring: Monitoring,
	log: ActivityLog,
	folders: (FileId) -> String?,
	onCopy: (String) -> Unit,
	onMonitoring: (Monitoring) -> Unit,
) {
	when (monitoring) {
		Monitoring.Closed -> Unit
		is Monitoring.Bursts -> BurstsModal(monitoring, log, folders, onCopy, onMonitoring)
		is Monitoring.Changes -> ChangesModal(monitoring, log, folders, onCopy, onMonitoring)
	}
}

/**
 * What [read] reads, read again every [READ_EVERY] away from the window's thread while [live], and once where not:
 * what a paused log's copy holds does not change.
 */
@Composable
private fun <T> readOften(key: Any, live: Boolean, first: T, read: () -> T): T {
	val value by produceState(first, key, live) {
		do {
			value = withContext(Dispatchers.Default) { read() }
			if (live)
				delay(READ_EVERY)
		} while (live)
	}

	return value
}

/** Ctrl+F (Cmd+F on a Mac): the keyboard to [find]. */
private fun findKey(find: FocusRequester): (KeyEvent) -> Boolean =
	{ key ->
		(key.type == KeyEventType.KeyDown && key.isPrimaryPressed && key.key == Key.F).also {
			if (it)
				find.requestFocus()
		}
	}

/** How wide a time written as [format] writes it to [precision] is at most, in [style]: a column of them lines up. */
@Composable
private fun timeWidth(
	format: DateFormat,
	precision: TimePrecision,
	style: TextStyle,
): Dp {
	val measurer = rememberTextMeasurer()
	val density = LocalDensity.current

	return remember(format, precision, style, density) {
		val zone = ZoneId.systemDefault()

		// The widest hours there are, morning and evening, at the widest minutes and seconds.
		val widest =
			listOf(
				LocalTime.of(10, 58, 58, 888_000_000),
				LocalTime.of(22, 58, 58, 888_000_000),
			).maxOf {
				val at =
					LocalDate
						.of(2026, 1, 1)
						.atTime(it)
						.atZone(zone)
						.toInstant()

				measurer.measure(format.writeTime(at, zone, precision), style).size.width
			}

		with(density) { widest.toDp() } + Space.xs
	}
}

/** A path as a row shows it: its folder faint, and the entry's name in full. */
private fun pathShown(path: String, palette: Palette): AnnotatedString {
	val name = path.lastIndexOf('/') + 1

	return buildAnnotatedString {
		withStyle(SpanStyle(color = palette.textMuted)) { append(path.take(name)) }
		append(path.drop(name))
	}
}

/** What an event of [kind] is drawn with: a glyph, and its colour. */
private fun lookOf(kind: ActKind, palette: Palette): Pair<Glyph, Color> =
	when (kind) {
		ActKind.Created -> Glyph.Plus to palette.accentText
		ActKind.Deleted -> Glyph.Minus to palette.dangerText
		ActKind.Moved -> Glyph.Forward to palette.textMuted
	}

/** The chips that narrow a table to kinds of event: each picked, or not; none picked shows every kind. */
@Composable
private fun KindChips(filter: KindFilter, onFilter: (KindFilter) -> Unit) {
	Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
		for (kind in ActKind.entries)
			Chip(
				kind.label,
				Modifier.testTag("monitor-kind:${kind.name}"),
				strong = kind in filter.only,
			) { onFilter(filter.toggled(kind)) }
	}
}

/** What narrows a table: the field [find] is typed into, the chips of kinds, and whatever [trailing] adds after them. */
@Composable
private fun Toolbar(
	find: String,
	hint: String,
	focus: FocusRequester,
	filter: KindFilter,
	onFind: (String) -> Unit,
	onFilter: (KindFilter) -> Unit,
	trailing: @Composable RowScope.() -> Unit = {},
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.m),
	) {
		Field(
			find,
			onFind,
			Modifier.weight(1f).testTag("monitor-find"),
			hint = hint,
			leading = Glyph.Search,
			focus = focus,
			onClear = { onFind("") }.takeIf { find.isNotEmpty() },
		)
		KindChips(filter, onFilter)
		trailing()
	}
}

/**
 * A table: its [header], and under it a row of each of [items], scrolled, or [empty] said where there are none; in a
 * card of its own, inset from the edges of the modal.
 */
@Composable
private fun <T> Table(
	items: List<T>,
	empty: String,
	tag: String,
	header: @Composable RowScope.() -> Unit,
	row: @Composable (T) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.large)

	Column(
		Modifier
			.fillMaxWidth()
			.height(TABLE_HEIGHT)
			.clip(shape)
			.background(palette.raised)
			.border(1.dp, palette.border, shape),
	) {
		Row(
			Modifier
				.fillMaxWidth()
				.background(palette.sunken)
				.padding(
					start = Space.l,
					end = Space.l + SCROLLBAR_ROOM,
					top = Space.s,
					bottom = Space.s,
				),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.m),
			content = header,
		)
		Divider()
		Box(Modifier.fillMaxSize()) {
			if (items.isEmpty())
				Text(
					empty,
					style = type.muted,
					modifier = Modifier.align(Alignment.Center).testTag("$tag-empty"),
				)
			else
				LazyScrollArea(Modifier.fillMaxSize(), tag = tag) {
					items(items) { row(it) }
				}
		}
	}
}

/** The room a table's rows leave at their end for its scrollbar ([LazyScrollArea]'s): its heading leaves the same. */
private val SCROLLBAR_ROOM = 12.dp

/** A heading of a table's column, of [width] where it has one, or of what room is left. */
@Composable
private fun RowScope.Heading(text: String, width: Dp? = null, end: Boolean = false) {
	val type = LocalType.current

	Text(
		text,
		style = type.heading,
		textAlign =
			if (end)
				TextAlign.End
			else
				TextAlign.Start,
		modifier =
			width?.let { Modifier.width(it) } ?: Modifier.weight(1f),
	)
}

/**
 * A row of a table: pressed, it does [onClick] where there is one; under the pointer it is lit, and shows what only
 * [hovered] shows (its copy buttons).
 */
@Composable
private fun TableRow(
	tag: String,
	onClick: (() -> Unit)? = null,
	content: @Composable RowScope.(hovered: Boolean) -> Unit,
) {
	val palette = LocalPalette.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()

	Row(
		Modifier
			.fillMaxWidth()
			.background(
				if (felt.hovered)
					palette.hover
				else
					Color.Transparent,
			).then(
				if (onClick == null)
					Modifier.hoverable(source)
				else
					Modifier.clickable(source, indication = null, onClick = onClick),
			).padding(horizontal = Space.l, vertical = Space.s)
			.testTag(tag),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.m),
	) { content(felt.hovered) }
}

/** A button that copies, shown only where [shown] (the row under the pointer), its room kept where it is not. */
@Composable
private fun CopyButton(shown: Boolean, label: String, tag: String, onCopy: () -> Unit) {
	val type = LocalType.current

	Box(Modifier.size(type.line + Space.xs), contentAlignment = Alignment.Center) {
		if (shown)
			ToolButton(
				Glyph.Copy,
				label,
				enabled = true,
				Modifier.testTag(tag),
				size = type.line + Space.xs,
				icon = type.icon * 0.8f,
				onClick = onCopy,
			)
	}
}

/** Whether the log is being read as it goes, or is held: a dot and a word, at the top of the groups. */
@Composable
private fun LiveState(held: Held?, format: DateFormat) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.xs),
	) {
		Box(
			Modifier
				.size(8.dp)
				.background(
					if (held == null)
						palette.accent
					else
						palette.textFaint,
					CircleShape,
				),
		)
		Text(
			held?.let { "Paused at ${format.writeTime(it.at, precision = TimePrecision.Seconds)}" }
				?: "Live",
			style = type.muted.tabular(),
			modifier = Modifier.testTag("monitor-state"),
		)
	}
}

@Composable
private fun BurstsModal(
	bursts: Monitoring.Bursts,
	log: ActivityLog,
	folders: (FileId) -> String?,
	onCopy: (String) -> Unit,
	onMonitoring: (Monitoring) -> Unit,
) {
	val type = LocalType.current
	val palette = LocalPalette.current
	val format = LocalDateFormat.current
	val find = remember { FocusRequester() }
	val shown = readOften(bursts, !bursts.paused, emptyList()) { bursts.shown(log, folders) }
	val time = timeWidth(format, TimePrecision.Seconds, type.body.tabular())
	val narrowed = bursts.find.isNotBlank() || bursts.filter.only.isNotEmpty()

	Modal(
		"Index monitor",
		onDismiss = { onMonitoring(Monitoring.Closed) },
		width = ModalWidth.Wide,
		tag = "index-monitor",
		onKey = findKey(find),
		buttons = {
			ButtonRow(
				null,
				secondary = listOf(Act("Close", "monitor-close") { onMonitoring(Monitoring.Closed) }),
				aside =
					listOf(
						Act("Copy list", "monitor-copy-list", enabled = shown.isNotEmpty()) {
							onCopy(burstsText(shown, format))
						},
					),
				note =
					if (log.recording)
						""
					else
						"Event log is off in Index updates",
			)
		},
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				"File system events from the last ${log.keep.inWholeSeconds} seconds, grouped by " +
					"process in ${log.window.inWholeSeconds}-second windows.",
				style = type.muted,
				modifier = Modifier.weight(1f),
			)
			LiveState(bursts.held, format)
		}
		Toolbar(
			bursts.find,
			"Search processes and paths",
			find,
			bursts.filter,
			onFind = { onMonitoring(bursts.copy(find = it)) },
			onFilter = { onMonitoring(bursts.copy(filter = it)) },
		) {
			PushButton(
				if (bursts.paused)
					"Resume"
				else
					"Pause",
				Emphasis.Secondary,
				enabled = true,
				Modifier.testTag("monitor-pause"),
				glyph =
					if (bursts.paused)
						Glyph.Play
					else
						Glyph.Pause,
			) {
				onMonitoring(
					bursts.copy(
						held =
							if (bursts.paused)
								null
							else
								Held(log.copy(), Instant.now()),
					),
				)
			}
		}
		Table(
			shown,
			if (narrowed)
				"No matching events"
			else
				"No events in the last ${log.keep.inWholeSeconds} seconds",
			"monitor-bursts",
			header = {
				Heading("Time", time)
				Heading("Process")
				Heading("PID", PID_WIDTH)
				Heading("Events", COUNT_WIDTH, end = true)
				Box(Modifier.size(type.line + Space.xs))
				Box(Modifier.size(type.icon))
			},
		) { burst ->
			val pid = (burst.by as? Doer.Process)?.pid

			TableRow("burst:${burst.id}", onClick = {
				onMonitoring(Monitoring.Changes(burst, bursts))
			}) { hovered ->
				Text(
					format.writeTime(burst.from, precision = TimePrecision.Seconds),
					style = type.body.tabular().copy(color = palette.textMuted),
					modifier = Modifier.width(time).testTag("burst-time"),
				)
				Text(
					burst.by.label,
					style =
						if ((burst.by as? Doer.Process)?.name != null)
							type.strong
						else
							type.body.copy(color = palette.textMuted),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				Text(
					pid?.toString() ?: "—",
					style = type.mono.copy(color = palette.textMuted),
					modifier = Modifier.width(PID_WIDTH),
				)
				Text(
					countOf(burst),
					style =
						type.body.tabular().let {
							if (burst.overflowed)
								it.copy(color = palette.dangerText)
							else
								it
						},
					textAlign = TextAlign.End,
					maxLines = 1,
					modifier = Modifier.width(COUNT_WIDTH).testTag("burst-count"),
				)
				CopyButton(hovered && pid != null, "Copy PID", "burst-copy-pid:${burst.id}") {
					onCopy(pid.toString())
				}
				GlyphIcon(Glyph.Chevron, palette.textMuted, type.icon)
			}
		}
	}
}

@Composable
private fun ChangesModal(
	changes: Monitoring.Changes,
	log: ActivityLog,
	folders: (FileId) -> String?,
	onCopy: (String) -> Unit,
	onMonitoring: (Monitoring) -> Unit,
) {
	val type = LocalType.current
	val palette = LocalPalette.current
	val format = LocalDateFormat.current
	val find = remember { FocusRequester() }
	val burst = changes.burst
	val pid = (burst.by as? Doer.Process)?.pid
	val shown =
		readOften(changes, !changes.to.paused, emptyList()) { changes.shown(log, folders) }
	val time = timeWidth(format, TimePrecision.Milliseconds, type.body.tabular())
	val narrowed = changes.find.isNotBlank() || changes.filter.only.isNotEmpty()

	Modal(
		burst.by.label,
		// Escape goes back to the groups, as Back does.
		onDismiss = { onMonitoring(changes.to) },
		width = ModalWidth.Wide,
		tag = "index-monitor-changes",
		onKey = findKey(find),
		buttons = {
			ButtonRow(
				null,
				secondary = listOf(Act("Close", "monitor-close") { onMonitoring(Monitoring.Closed) }),
				aside =
					listOf(
						Act("Back", "monitor-back") { onMonitoring(changes.to) },
						Act("Copy list", "monitor-copy-list", enabled = shown.isNotEmpty()) {
							onCopy(changesText(shown, format))
						},
					),
			)
		},
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.m),
		) {
			if (pid != null) {
				SelectionContainer {
					Text("PID $pid", style = type.mono, modifier = Modifier.testTag("changes-pid"))
				}
				ToolButton(
					Glyph.Copy,
					"Copy PID",
					enabled = true,
					Modifier.testTag("changes-copy-pid"),
					size = type.line + Space.xs,
					icon = type.icon * 0.8f,
				) { onCopy(pid.toString()) }
				Text("·", style = type.muted)
			}
			Text(
				"Started ${format.write(burst.from, precision = TimePrecision.Seconds)}",
				style = type.muted.tabular(),
			)
			Text("·", style = type.muted)
			Text(
				if (burst.overflowed)
					"%,d events, first %,d recorded".format(burst.seen, burst.kept)
				else if (burst.seen == 1)
					"1 event"
				else
					"%,d events".format(burst.seen),
				style =
					type.muted.let {
						if (burst.overflowed)
							it.copy(color = palette.dangerText)
						else
							it
					},
			)
		}
		Toolbar(
			changes.find,
			"Filter by path",
			find,
			changes.filter,
			onFind = { onMonitoring(changes.copy(find = it)) },
			onFilter = { onMonitoring(changes.copy(filter = it)) },
		)
		Table(
			shown,
			if (narrowed)
				"No matching events"
			else
				"These events have expired",
			"monitor-changes",
			header = {
				Heading("Time", time)
				Heading("Event", ACT_WIDTH)
				Heading("Path")
				Box(Modifier.size(type.line + Space.xs))
			},
		) { change ->
			val (glyph, tint) = lookOf(change.heard.act.kind, palette)

			TableRow("change") { hovered ->
				Text(
					format.writeTime(change.heard.at, precision = TimePrecision.Milliseconds),
					style = type.body.tabular().copy(color = palette.textMuted),
					modifier = Modifier.width(time),
				)
				Row(
					Modifier.width(ACT_WIDTH),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Space.s),
				) {
					GlyphIcon(glyph, tint, type.icon * 0.8f)
					Text(change.heard.act.label, style = type.body, maxLines = 1)
				}
				SelectionContainer(Modifier.weight(1f)) {
					Text(
						pathShown(change.path, palette),
						style = type.mono,
						maxLines = 1,
						overflow = TextOverflow.MiddleEllipsis,
						modifier = Modifier.testTag("change-path"),
					)
				}
				CopyButton(hovered, "Copy path", "change-copy-path") { onCopy(change.path) }
			}
		}
	}
}
