package prism.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import prism.browser.Act
import prism.browser.ButtonRow
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.LazyScrollArea
import prism.browser.LocalType
import prism.browser.Modal
import prism.browser.ModalWidth
import prism.browser.PushButton
import prism.browser.Space
import prism.browser.isPrimaryPressed
import prism.browser.tabular
import prism.index.Activity
import prism.index.ActivityLog
import prism.index.Burst
import prism.index.FileId
import prism.index.Heard

/*
 * The index monitor: what the disk was heard doing in the last minute ([ActivityLog]), by who did it, over the
 * preferences' window. It opens on the bursts, each process's changes over a few seconds, latest first; a burst's
 * button shows its changes in the bursts' place, and Back (or Escape) goes back to them.
 *
 * The bursts are read again twice a second, so what the disk does now is shown as it does it, unless the log is paused:
 * then they are a copy of the log as it was, which the monitor keeps until it is resumed or closed, while the log goes
 * on noting what is heard. Ctrl+F finds, in either: the bursts by their process's name first and then by the paths of
 * their changes, and a burst's changes by their paths.
 */

/** What of the index monitor is open: nothing, the bursts, or one burst's changes in their place. */
internal sealed interface Monitoring {
	data object Closed : Monitoring

	/**
	 * The bursts, narrowed by what is typed to [find] them: as the log holds them each time they are read, or, where
	 * the log is paused, as it [held] them then.
	 */
	data class Bursts(
		val held: Activity? = null,
		val find: String = "",
	) : Monitoring {
		val paused: Boolean get() = held != null
	}

	/** The changes of [burst], narrowed by what is typed to [find] them by their paths; and the bursts to go back [to]. */
	data class Changes(
		val burst: Burst,
		val to: Bursts,
		val find: String = "",
	) : Monitoring
}

/** The bursts shown: those of the log as it is, or as it was held, that what is typed finds. */
internal fun Monitoring.Bursts.shown(
	log: ActivityLog,
	folders: (FileId) -> String?,
): List<Burst> = (held ?: log.live).found(find, folders)

/** A change as the monitor shows it: what was heard, and its path, said as [Spot.said] says it. */
internal data class ShownChange(
	val heard: Heard,
	val path: String,
)

/** The changes shown: the burst's, as its bursts have them, whose paths have what is typed. */
internal fun Monitoring.Changes.shown(
	log: ActivityLog,
	folders: (FileId) -> String?,
): List<ShownChange> =
	(to.held ?: log.live)
		.heardIn(burst.id)
		.map { ShownChange(it, it.spot.said(folders)) }
		.filter { find.isBlank() || it.path.contains(find, ignoreCase = true) }

/** How often what the monitor shows is read again from the log, while it is not paused. */
private val READ_EVERY = 500.milliseconds

private val TIME =
	DateTimeFormatter
		.ofPattern(
			"HH:mm:ss",
		).withZone(ZoneId.systemDefault())
private val TIME_EXACTLY =
	DateTimeFormatter
		.ofPattern(
			"HH:mm:ss.SSS",
		).withZone(ZoneId.systemDefault())

/** How tall the list of bursts, or of changes, is: room for a dozen and more. */
private val LIST_HEIGHT = 380.dp

/**
 * The index monitor, as [monitoring] has it open, over [log], each folder its changes name placed by [folders];
 * whatever is done in it reported to [onMonitoring].
 */
@Composable
internal fun IndexMonitor(
	monitoring: Monitoring,
	log: ActivityLog,
	folders: (FileId) -> String?,
	onMonitoring: (Monitoring) -> Unit,
) {
	when (monitoring) {
		Monitoring.Closed -> Unit
		is Monitoring.Bursts -> BurstsModal(monitoring, log, folders, onMonitoring)
		is Monitoring.Changes -> ChangesModal(monitoring, log, folders, onMonitoring)
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

@Composable
private fun BurstsModal(
	bursts: Monitoring.Bursts,
	log: ActivityLog,
	folders: (FileId) -> String?,
	onMonitoring: (Monitoring) -> Unit,
) {
	val type = LocalType.current
	val find = remember { FocusRequester() }
	val shown = readOften(bursts, !bursts.paused, emptyList()) { bursts.shown(log, folders) }

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
				note =
					if (log.recording)
						""
					else
						"Index monitoring is off: nothing new is noted",
			)
		},
	) {
		Text(
			"What the index heard happen on the disk in the last minute, by the process that " +
				"did it. Each " +
				"process's changes over five seconds are one entry.",
			style = type.muted,
		)
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
		) {
			Field(
				bursts.find,
				{ onMonitoring(bursts.copy(find = it)) },
				Modifier.weight(1f).testTag("monitor-find"),
				hint = "Find a process, or a path",
				leading = Glyph.Search,
				focus = find,
			)
			PushButton(
				if (bursts.paused)
					"Resume log"
				else
					"Pause log",
				Emphasis.Secondary,
				enabled = true,
				Modifier.testTag("monitor-pause"),
			) {
				onMonitoring(
					bursts.copy(
						held =
							if (bursts.paused)
								null
							else
								log.copy(),
					),
				)
			}
		}
		Listed(
			shown,
			if (bursts.find.isBlank())
				"Nothing was heard in the last minute."
			else
				"Nothing heard in the last minute has that.",
			"monitor-bursts",
		) { burst ->
			BurstRow(burst) { onMonitoring(Monitoring.Changes(burst, bursts)) }
		}
	}
}

/** A list of [items] as tall as the monitor's lists are, or [empty] said where there are none. */
@Composable
private fun <T> Listed(
	items: List<T>,
	empty: String,
	tag: String,
	row: @Composable (T) -> Unit,
) {
	val type = LocalType.current

	Box(Modifier.fillMaxWidth().height(LIST_HEIGHT)) {
		if (items.isEmpty())
			Text(
				empty,
				style = type.muted,
				modifier = Modifier.align(Alignment.Center).testTag("$tag-empty"),
			)
		else
			LazyScrollArea(Modifier.fillMaxWidth(), tag = tag) {
				items(items) { row(it) }
			}
	}
}

/** A burst: when it began, who made it, how many changes it has, and the button that shows them. */
@Composable
private fun BurstRow(burst: Burst, onChanges: () -> Unit) {
	val type = LocalType.current

	Row(
		Modifier.fillMaxWidth().padding(vertical = Space.xs).testTag("burst:${burst.id}"),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.m),
	) {
		Text(TIME.format(burst.from), style = type.muted.tabular())
		Text(
			burst.by.said,
			style = type.body,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			countOf(burst),
			style = type.muted.tabular(),
			modifier = Modifier.testTag("burst-count"),
		)
		PushButton(
			"Changes",
			Emphasis.Secondary,
			enabled = true,
			Modifier.testTag("burst-open:${burst.id}"),
			onClick = onChanges,
		)
	}
}

/** How many changes a burst has, as its row says it: "7 changes", or "200 of 1,523 changes" where it kept fewer. */
internal fun countOf(burst: Burst): String =
	if (burst.overflowed)
		"%,d of %,d changes".format(burst.kept, burst.seen)
	else if (burst.seen == 1)
		"1 change"
	else
		"%,d changes".format(burst.seen)

@Composable
private fun ChangesModal(
	changes: Monitoring.Changes,
	log: ActivityLog,
	folders: (FileId) -> String?,
	onMonitoring: (Monitoring) -> Unit,
) {
	val type = LocalType.current
	val find = remember { FocusRequester() }
	val burst = changes.burst
	val shown =
		readOften(changes, !changes.to.paused, emptyList()) { changes.shown(log, folders) }

	Modal(
		burst.by.said,
		// Escape goes back to the bursts, as Back does.
		onDismiss = { onMonitoring(changes.to) },
		width = ModalWidth.Wide,
		tag = "index-monitor-changes",
		onKey = findKey(find),
		buttons = {
			ButtonRow(
				null,
				secondary = listOf(Act("Close", "monitor-close") { onMonitoring(Monitoring.Closed) }),
				aside = listOf(Act("Back", "monitor-back") { onMonitoring(changes.to) }),
			)
		},
	) {
		Text(
			"From ${TIME.format(burst.from)}: ${countOf(burst)}" +
				if (burst.overflowed)
					". Only the first ${burst.kept} are kept."
				else
					".",
			style = type.muted,
		)
		Field(
			changes.find,
			{ onMonitoring(changes.copy(find = it)) },
			Modifier.fillMaxWidth().testTag("monitor-find"),
			hint = "Narrow by path",
			leading = Glyph.Search,
			focus = find,
		)
		Listed(
			shown,
			if (changes.find.isBlank())
				"These changes are no longer kept: they were heard over a minute ago."
			else
				"None of these changes' paths has that.",
			"monitor-changes",
		) { ChangeRow(it) }
	}
}

/** A change: exactly when it was heard, what was done, and the path it was done to. */
@Composable
private fun ChangeRow(change: ShownChange) {
	val type = LocalType.current

	Row(
		Modifier.fillMaxWidth().padding(vertical = Space.xs).testTag("change"),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.m),
	) {
		Text(TIME_EXACTLY.format(change.heard.at), style = type.muted.tabular())
		Text(change.heard.act.said, style = type.body, modifier = Modifier.width(ACT_WIDTH))
		Text(
			change.path,
			style = type.mono,
			maxLines = 1,
			overflow = TextOverflow.MiddleEllipsis,
			modifier = Modifier.weight(1f).testTag("change-path"),
		)
	}
}

/** How wide what was done is: room for "Folder removed". */
private val ACT_WIDTH = 120.dp
