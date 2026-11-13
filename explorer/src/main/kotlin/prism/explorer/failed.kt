package prism.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import prism.browser.Act
import prism.browser.ButtonRow
import prism.browser.Card
import prism.browser.CardInsets
import prism.browser.LocalType
import prism.browser.Notice
import prism.browser.Page
import prism.browser.ScrollArea
import prism.browser.Tone

/*
 * What is shown in place of a window that failed. A window of prism's is on its own (explorer.kt): when something goes
 * wrong in one, it is closed, and this says so, in a window that depends on as little as a window can: what it was,
 * what went wrong in a line, and where, in full, to read or to copy into a report. Everything else was left as it was,
 * and the window says that too, since a person who has just lost a window wants to know what else they lost.
 */

/** What the window says under its title: that one window was closed, and nothing else. */
internal const val FAILED_DESCRIPTION =
	"That window was closed. Nothing else was: prism's other windows are as they were, " +
		"and so is its index. Files are never changed by a window closing."

/**
 * [window]: what failed, the failure in a line and in full, and its buttons: Copy, Close, and, where the window that
 * failed was browsing, Open again ([onAgain]), which opens a browser where it was.
 */
@Composable
internal fun FailedView(
	window: ExplorerWindow.Failed,
	onAgain: (() -> Unit)?,
	onCopy: (String) -> Unit,
	onClose: () -> Unit,
) {
	val type = LocalType.current
	val close = Act("Close", "failed-close", onClick = onClose)

	Column(Modifier.fillMaxSize()) {
		Page(
			"${window.what} ran into a problem",
			FAILED_DESCRIPTION,
			Modifier.weight(1f),
			scrolls = false,
		) {
			Card {
				Notice(
					window.failure.summary,
					Tone.Bad,
					Modifier.testTag("failed-summary"),
					detail =
						"It is in prism's log as well: where prism runs as a service, " +
							"journalctl --user -u prism.",
				)
			}
			Card(Modifier.weight(1f)) {
				ScrollArea(Modifier.fillMaxSize(), padding = CardInsets, tag = "failed-trace") {
					SelectionContainer { Text(window.failure.trace, style = type.mono) }
				}
			}
		}
		DialogFoot {
			ButtonRow(
				primary = onAgain?.let { Act("Open again", "failed-again", onClick = it) } ?: close,
				secondary = listOfNotNull(close.takeIf { onAgain != null }),
				aside =
					listOf(
						Act("Copy", "failed-copy") {
							onCopy("${window.what}: ${window.failure.summary}\n\n${window.failure.trace}")
						},
					),
			)
		}
	}
}
