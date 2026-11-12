package prism.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import prism.browser.BrowserEvent
import prism.browser.Chord
import prism.browser.Dims
import prism.browser.HostedBrowser
import prism.browser.HostedWindow
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Location
import prism.browser.Palette
import prism.browser.TransientFor
import prism.browser.mouseChords
import prism.window.NativeWindow
import prism.window.Window

/*
 * Every window the explorer has open, drawn from its list: a browser's in a window of its own, and each of the rest
 * (properties, "open with", the preferences) in a smaller one, closed by its own buttons or Escape as well as by the
 * window manager. A window has the title bar the window manager draws only where [decorated] asks for one.
 */

/** Every window [explorer] has open, in [palette]. */
@Composable
fun ExplorerWindows(explorer: Explorer, palette: Palette, decorated: Boolean) {
	val windows by explorer.windows.collectAsState()

	for (window in windows)
		key(window.id) {
			when (window) {
				is ExplorerWindow.Browsing -> {
					val state by window.session.state.collectAsState()

					Window(
						onCloseRequest = { explorer.close(window) },
						title =
							when (val at = state.at) {
								is Location.Folder -> (at.path.fileName ?: at.path).toString()
								is Location.Results -> "Search"
								is Location.Place -> at.placed.title
							},
						size = DpSize(1100.dp, 680.dp),
						decorated = decorated,
					) {
						HostedBrowser(this.window, window.session, explorer.preferences, palette)
					}
				}
				// A folder chosen for an action: a picker, as any application's, closed as one is cancelled.
				is ExplorerWindow.Picking ->
					Window(
						onCloseRequest = { window.session.send(BrowserEvent.Cancelled) },
						title = window.title,
						size = DpSize(900.dp, 600.dp),
						decorated = decorated,
					) {
						// A dialog of the application that asked, where it is an X11 window prism can name; and in
						// front, where the window manager lets it be, since it was asked for from elsewhere.
						TransientFor(this.window, window.parent)
						LaunchedEffect(Unit) { this@Window.window.raise() }
						HostedBrowser(this.window, window.session, explorer.preferences, palette)
					}
				is ExplorerWindow.Properties ->
					Dialog(propertiesTitleOf(window.paths), DpSize(560.dp, 520.dp), palette, decorated, {
						explorer.close(window)
					}) {
						PropertiesView(window.paths, explorer) { explorer.close(window) }
					}
				is ExplorerWindow.OpenWith ->
					Dialog(openWithTitleOf(window.choice), DpSize(620.dp, 560.dp), palette, decorated, {
						explorer.close(window)
					}) {
						OpenWithView(window.choice, explorer) { explorer.close(window) }
					}
				is ExplorerWindow.Settings ->
					Dialog(
						"Preferences",
						DpSize(980.dp, 680.dp),
						palette,
						decorated,
						{ explorer.close(window) },
						raised = window.raised,
					) {
						SettingsWindow(explorer) { explorer.close(window) }
					}
			}
		}
}

/** The preferences window's edits, kept while it is open, and saved over the preferences as they are by then. */
@Composable
private fun SettingsWindow(explorer: Explorer, onClose: () -> Unit) {
	val chords =
		remember {
			MutableSharedFlow<Chord>(
				extraBufferCapacity = 8,
				onBufferOverflow = BufferOverflow.DROP_OLDEST,
			)
		}

	var settings by remember {
		mutableStateOf(
			Settings(
				explorer.preferences.preferences.value,
				explorer.desktop.places.known(explorer.home),
			),
		)
	}

	Box(Modifier.fillMaxSize().mouseChords { chords.tryEmit(it) }) {
		SettingsView(
			settings,
			onChange = { settings = it },
			onSave = {
				explorer.preferences.update(settings::over)
				onClose()
			},
			onCancel = onClose,
			mouseChords = chords,
			terminalPreview = { choice ->
				explorer.desktop.launcher.terminalCommand(explorer.home, choice)
			},
		)
	}
}

/**
 * A window of [size] for something the explorer shows beside its browsers, hosted as every prism window is, and closed
 * by [onClose] (its own buttons call it too) or by Escape where nothing in it uses the key. Each time [raised] changes,
 * it is brought to the front.
 */
@Composable
private fun Dialog(
	title: String,
	size: DpSize,
	palette: Palette,
	decorated: Boolean,
	onClose: () -> Unit,
	raised: Int = 0,
	content: @Composable (NativeWindow) -> Unit,
) {
	Window(
		onCloseRequest = onClose,
		title = title,
		size = size,
		decorated = decorated,
	) {
		val native = window

		LaunchedEffect(raised) {
			if (raised > 0)
				native.raise()
		}
		HostedWindow(native, palette) {
			Column(
				Modifier
					.fillMaxSize()
					.background(LocalPalette.current.window)
					.onKeyEvent {
						(it.type == KeyEventType.KeyDown && it.key == Key.Escape).also { escaped ->
							if (escaped)
								onClose()
						}
					},
			) {
				if (!decorated)
					Text(
						title,
						style = LocalType.current.heading,
						modifier = Modifier.padding(start = Dims.gap * 2, top = Dims.gap * 2),
					)
				Box(Modifier.weight(1f).fillMaxWidth()) { content(native) }
			}
		}
	}
}

/** A row of tabs, [chosen] among [tabs], each named by [label]. */
@Composable
internal fun <T> Tabs(
	tabs: List<T>,
	chosen: T,
	label: (T) -> String,
	onChoose: (T) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Row {
		tabs.forEach { tab ->
			// As wide as its name, which its underline is too.
			Column(
				Modifier.padding(end = Dims.gap * 2).width(IntrinsicSize.Max).clickable {
					onChoose(tab)
				},
			) {
				Text(
					label(tab),
					style =
						if (tab == chosen)
							type.body.copy(color = palette.accent)
						else
							type.body,
					modifier = Modifier.padding(vertical = Dims.gapSmall),
				)
				Spacer(
					Modifier.height(2.dp).fillMaxWidth().background(
						if (tab ==
							chosen
						)
							palette.accent
						else
							palette.window,
					),
				)
			}
		}
	}
}
