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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import prism.browser.BrowserEvent
import prism.browser.Chord
import prism.browser.HostedBrowser
import prism.browser.HostedWindow
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Location
import prism.browser.Palette
import prism.browser.Scaled
import prism.browser.Space
import prism.browser.TransientFor
import prism.browser.mouseChords
import prism.browser.zoomedByWheel
import prism.preferences.DateFormat
import prism.preferences.Typography
import prism.preferences.WindowKind
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
	val preferences by explorer.preferences.preferences.collectAsState()

	/** How large windows of [kind] are drawn, and what zooming one does: every one of its kind drawn so, from then on. */
	fun zoomOf(kind: WindowKind): Zooming =
		Zooming(preferences.scales.of(kind)) { scale ->
			explorer.preferences.update { it.copy(scales = it.scales.with(kind, scale)) }
		}

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
								Location.Trash -> "Trash"
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
					Dialog(
						propertiesTitleOf(window.paths),
						DpSize(560.dp, 520.dp),
						palette,
						decorated,
						{
							explorer.close(window)
						},
						typography = preferences.typography,
						dates = preferences.dates,
						zoom =
							zoomOf(
								WindowKind.Properties,
							),
					) {
						PropertiesView(window.paths, explorer) { explorer.close(window) }
					}
				is ExplorerWindow.OpenWith ->
					Dialog(
						openWithTitleOf(window.choice),
						DpSize(620.dp, 560.dp),
						palette,
						decorated,
						{
							explorer.close(window)
						},
						typography = preferences.typography,
						dates = preferences.dates,
						zoom =
							zoomOf(
								WindowKind.OpenWith,
							),
					) {
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
						typography = preferences.typography,
						dates = preferences.dates,
						zoom = zoomOf(WindowKind.Preferences),
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
		mutableStateOf(Settings(explorer.preferences.preferences.value))
	}

	Box(Modifier.fillMaxSize().mouseChords { chords.tryEmit(it) }) {
		SettingsView(
			settings,
			onChange = { settings = it },
			// Applied, the window stays open, editing from what is applied.
			onApply = {
				explorer.preferences.update(settings::over)
				settings = settings.applied(explorer.preferences.preferences.value)
			},
			onClose = onClose,
			mouseChords = chords,
			terminalPreview = { choice, command ->
				explorer.desktop.launcher.terminalCommand(explorer.home, choice, command)
			},
		)
	}
}

/** How large a window is drawn ([scale] times its usual size), and what is done with a size it is zoomed to. */
class Zooming(
	val scale: Float,
	val onScale: (Float) -> Unit,
) {
	/** The size [steps] of the zoom's steps from this one: from the nearest step to it, as far as there are steps. */
	fun stepped(steps: Int): Float {
		val at = WINDOW_STEPS.indices.minBy { kotlin.math.abs(WINDOW_STEPS[it] - scale) }

		return WINDOW_STEPS[(at + steps).coerceIn(0, WINDOW_STEPS.lastIndex)]
	}
}

/** The sizes a window is zoomed through, as a web browser's are: from half its usual size to five times it. */
internal val WINDOW_STEPS =
	listOf(
		0.5f,
		0.67f,
		0.75f,
		0.8f,
		0.9f,
		1f,
		1.1f,
		1.25f,
		1.5f,
		1.75f,
		2f,
		2.5f,
		3f,
		4f,
		5f,
	)

/**
 * A window of [size] for something the explorer shows beside its browsers, hosted as every prism window is, and closed
 * by [onClose] (its own buttons call it too) or by Escape where nothing in it uses the key. Each time [raised] changes,
 * it is brought to the front. It is drawn as large as [zoom] says, which Ctrl and the wheel change, as they zoom a
 * page in a web browser (and Ctrl with + and -, and Ctrl+0 back to its usual size).
 */
@Composable
private fun Dialog(
	title: String,
	size: DpSize,
	palette: Palette,
	decorated: Boolean,
	onClose: () -> Unit,
	raised: Int = 0,
	typography: Typography = Typography(),
	dates: DateFormat = DateFormat.Written,
	zoom: Zooming = Zooming(1f) {},
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
		HostedWindow(native, palette, typography, dates) {
			Column(
				Modifier
					.fillMaxSize()
					.background(LocalPalette.current.canvas)
					.zoomedByWheel { zoom.onScale(zoom.stepped(it)) }
					.onPreviewKeyEvent { zoomKey(it, zoom) }
					.onKeyEvent {
						(it.type == KeyEventType.KeyDown && it.key == Key.Escape).also { escaped ->
							if (escaped)
								onClose()
						}
					},
			) {
				Scaled(zoom.scale) {
					Column(Modifier.fillMaxSize()) {
						if (!decorated)
							Text(
								title,
								style = LocalType.current.heading,
								modifier = Modifier.padding(start = Space.xl, top = Space.l),
							)
						Box(Modifier.weight(1f).fillMaxWidth()) { content(native) }
					}
				}
			}
		}
	}
}

/** Ctrl with + or - zooming a window a step, and Ctrl+0 back to its usual size: whether [key] was one of them. */
private fun zoomKey(key: KeyEvent, zoom: Zooming): Boolean {
	if (key.type != KeyEventType.KeyDown || !key.isCtrlPressed || key.isAltPressed)
		return false

	val scale =
		when (key.key) {
			Key.Equals, Key.Plus, Key.NumPadAdd -> zoom.stepped(1)
			Key.Minus, Key.NumPadSubtract -> zoom.stepped(-1)
			Key.Zero, Key.NumPad0 -> 1f
			else -> return false
		}

	zoom.onScale(scale)

	return true
}
