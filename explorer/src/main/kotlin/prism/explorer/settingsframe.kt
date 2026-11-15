package prism.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import prism.browser.Act
import prism.browser.Action
import prism.browser.Bindings
import prism.browser.ButtonRow
import prism.browser.Chord
import prism.browser.Dims
import prism.browser.Divider
import prism.browser.Glyph
import prism.browser.ListRow
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Space

/*
 * What every window of settings is: its pages listed down the left, the one chosen beside them, and along the bottom
 * whether anything is changed, Apply and Close. The preferences' window is one, and the launcher's settings another;
 * each says what its pages are and draws them, and this is the rest.
 *
 * A page that rebinds keys gives its bindings: while a chord is awaited there, every key pressed in the window is
 * that chord and nothing else sees it, a mouse's button too; Escape stops waiting; and Ctrl+F goes to the page's
 * filter.
 */

/**
 * A window of settings: [pages], each listed by its [label] and [glyph], the one shown ([page]) drawn by [content],
 * another chosen through [onPage]. [changed] says whether anything waits to be applied, which [onApply] does;
 * [onClose] leaves the window. While something stops what is changed from being applied (an alias written wrong),
 * [blocked] says what, and nothing is.
 *
 * [rebinding] is the bindings the page shown rebinds, where it rebinds any, each change to them made through
 * [onRebinding]; [mouseChords] are the mouse's buttons pressed over the window, and [filter] the page's field that
 * Ctrl+F goes to. Undo and redo, as [keys] bind them, are [onUndo] and [onRedo], wherever nothing that has the
 * keyboard takes them first (a field's own typing).
 */
@Composable
internal fun <P> SettingsFrame(
	pages: List<P>,
	page: P,
	label: (P) -> String,
	glyph: (P) -> Glyph,
	onPage: (P) -> Unit,
	rebinding: Rebinding?,
	onRebinding: (Rebinding) -> Unit,
	changed: Boolean,
	onApply: () -> Unit,
	onClose: () -> Unit,
	mouseChords: Flow<Chord> = emptyFlow(),
	keys: Bindings = Bindings.DEFAULT,
	onUndo: () -> Unit = {},
	onRedo: () -> Unit = {},
	filter: FocusRequester = remember { FocusRequester() },
	blocked: String? = null,
	content: @Composable (P) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val root = remember { FocusRequester() }
	val latest by rememberUpdatedState(rebinding)
	val change by rememberUpdatedState(onRebinding)
	val listening = rebinding?.listening == true

	LaunchedEffect(mouseChords) {
		mouseChords.collect { chord ->
			latest?.takeIf { it.listening }?.let { change(it.pressed(chord)) }
		}
	}
	// The keyboard starts in the window, where its keys (Ctrl+F) are heard; and goes back there to wait for a chord.
	LaunchedEffect(listening) {
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
				// a page that rebinds goes to its filter, as it does in a browser's page.
				when {
					rebinding == null -> false
					!listening ->
						(key.type == KeyEventType.KeyDown && key.isCtrlPressed && key.key == Key.F).also {
							if (it)
								filter.requestFocus()
						}
					key.type != KeyEventType.KeyDown -> true
					key.key == Key.Escape -> true.also { onRebinding(rebinding.cancelled()) }
					else -> true.also { Chord.of(key)?.let { onRebinding(rebinding.pressed(it)) } }
				}
			}.onKeyEvent { key ->
				// Left by whatever has the keyboard: a field undoes its own typing first.
				key.type == KeyEventType.KeyDown &&
					when (Chord.of(key)?.let(keys::leftover)) {
						Action.Undo -> true.also { onUndo() }
						Action.Redo -> true.also { onRedo() }
						else -> false
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
			pages.forEach { each ->
				ListRow(
					label(each),
					Modifier.testTag("page:${label(each)}"),
					chosen = each == page,
					glyph = glyph(each),
				) { onPage(each) }
			}
		}
		Divider(vertical = true)
		Column(Modifier.weight(1f).fillMaxHeight()) {
			Box(Modifier.weight(1f).fillMaxWidth()) { content(page) }
			Divider()
			Row(
				Modifier
					.fillMaxWidth()
					.background(palette.window)
					.padding(horizontal = Space.xl, vertical = Space.m),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					when {
						blocked != null -> blocked
						changed -> "Unsaved changes"
						else -> ""
					},
					style =
						if (blocked != null)
							type.muted.copy(color = palette.dangerText)
						else
							type.muted,
					modifier = Modifier.weight(1f).testTag("settings-state"),
				)
				// Applied, the window stays: what was changed shows in every window, and more can be changed.
				ButtonRow(
					Act("Apply", "apply", changed && blocked == null, onClick = onApply),
					Modifier.width(IntrinsicSize.Max),
					secondary = listOf(Act("Close", "close", onClick = onClose)),
				)
			}
		}
	}
}
