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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import prism.browser.Bindings
import prism.browser.BrowserEvent
import prism.browser.Chord
import prism.browser.Edits
import prism.browser.HostedBrowser
import prism.browser.HostedWindow
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Palette
import prism.browser.Scaled
import prism.browser.Space
import prism.browser.TransientFor
import prism.browser.Undone
import prism.browser.mouseChords
import prism.browser.toClipboard
import prism.browser.zoomedByWheel
import prism.desktop.TerminalOptions
import prism.launcher.DmenuView
import prism.launcher.LauncherDims
import prism.launcher.LauncherView
import prism.launcher.LocalIconImages
import prism.launcher.launcherBindings
import prism.preferences.DateFormat
import prism.preferences.Preferences
import prism.preferences.Typography
import prism.preferences.WindowKind
import prism.window.Backdrop
import prism.window.Graphics
import prism.window.GraphicsReport
import prism.window.Keyboard
import prism.window.LocalNativeWindow
import prism.window.NativeWindow
import prism.window.Window
import prism.window.WindowRole

/*
 * Every window the explorer has open, drawn from its list: a browser's in a window of its own, and each of the rest
 * (properties, "open with", the preferences) in a smaller one, closed by its own buttons or Escape as well as by the
 * window manager. A window has the title bar the window manager draws only where [decorated] asks for one.
 */

/**
 * What the launcher's window is titled: what a window manager's rule knows it by, to place it or to round its
 * corners, since every window of prism's has prism's own name as its application's.
 */
const val LAUNCHER_TITLE = "Prism Launcher"

/**
 * What the launcher is named where it is no window but a surface over them all: what a compositor's rule for such
 * surfaces knows it by (a layer surface's namespace, on Wayland).
 */
const val LAUNCHER_NAME = "prism-launcher"

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
					// Read so that the title is made again as the browser goes from one folder to another.
					val state by window.session.state.collectAsState()

					Window(
						onCloseRequest = { explorer.close(window) },
						title = state.let { Explorer.titleOf(window) },
						size = DpSize(1100.dp, 680.dp),
						decorated = decorated,
						onFailure = { explorer.failed(window, it) },
						activation = window.activation,
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
						onFailure = { explorer.failed(window, it) },
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
						onFailure = { explorer.failed(window, it) },
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
						onFailure = { explorer.failed(window, it) },
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
						onFailure = { explorer.failed(window, it) },
						raised = window.raised,
						typography = preferences.typography,
						dates = preferences.dates,
						zoom = zoomOf(WindowKind.Preferences),
					) {
						SettingsWindow(explorer, window) { explorer.close(window) }
					}
				is ExplorerWindow.Launching ->
					LauncherWindow(
						explorer,
						window,
						window.activation,
						LauncherDims.sizeOf(preferences.launcher),
						palette,
						preferences,
					) {
						LauncherView(window.session, launcherBindings(preferences.launcher))
					}
				// Lines to choose one of, in the launcher's window: of the size its lines ask for, where they do.
				is ExplorerWindow.Dmenu ->
					LauncherWindow(
						explorer,
						window,
						window.activation,
						window.session.state.value.asked.let { asked ->
							LauncherDims.sizeOf(preferences.launcher).let { (width, height) ->
								(asked.width?.dp ?: width) to (asked.height?.dp ?: height)
							}
						},
						palette,
						preferences,
					) {
						DmenuView(window.session, launcherBindings(preferences.launcher))
					}
				is ExplorerWindow.LauncherSettings ->
					Dialog(
						Explorer.titleOf(window),
						DpSize(900.dp, 680.dp),
						palette,
						decorated,
						{ explorer.close(window) },
						onFailure = { explorer.failed(window, it) },
						raised = window.raised,
						typography = preferences.typography,
						dates = preferences.dates,
						zoom = zoomOf(WindowKind.Preferences),
					) {
						CompositionLocalProvider(LocalIconImages provides explorer.icons) {
							LauncherSettingsWindow(explorer, window.asked, window.raised) {
								explorer.close(window)
							}
						}
					}
				is ExplorerWindow.Failed ->
					Dialog(
						Explorer.titleOf(window),
						DpSize(720.dp, 520.dp),
						palette,
						decorated,
						{ explorer.close(window) },
						onFailure = { explorer.failed(window, it) },
						typography = preferences.typography,
						dates = preferences.dates,
					) {
						FailedView(
							window,
							onAgain =
								window.again?.let { again ->
									{
										explorer.close(window)
										explorer.browse(again)
									}
								},
							onCopy = ::toClipboard,
							onClose = { explorer.close(window) },
						)
					}
			}
		}
}

/**
 * The launcher's window, [size] across and down, around [content]: over every window, in the middle of the screen,
 * with the keyboard for as long as it is shown, where the platform has such a surface and the user wants it one; and
 * otherwise a window with no title bar, of a size that cannot be changed, which a window manager that tiles leaves
 * floating over its tiles. What it is, it is from when it opens.
 */
@Composable
private fun LauncherWindow(
	explorer: Explorer,
	window: ExplorerWindow,
	activation: String?,
	size: Pair<Dp, Dp>,
	palette: Palette,
	preferences: Preferences,
	content: @Composable () -> Unit,
) {
	val sized = remember { size }

	val role =
		remember {
			if (preferences.launcher.overlay)
				WindowRole.Overlay(
					LAUNCHER_NAME,
					// One that closes when the keyboard goes elsewhere must let it go: a click on
					// another window takes it. One that stays keeps the keyboard until it is put away.
					keyboard =
						if (preferences.launcher.closeUnfocused)
							Keyboard.Shared
						else
							Keyboard.Held,
				)
			else
				WindowRole.Normal
		}

	Window(
		onCloseRequest = { explorer.close(window) },
		title = LAUNCHER_TITLE,
		size = DpSize(sized.first, sized.second),
		decorated = false,
		onFailure = { explorer.failed(window, it) },
		activation = activation,
		resizable = false,
		role = role,
		// Clear behind what it draws, for round corners to show what is behind them, where the user
		// wants them round; the window says whether it could be, and the launcher is square if not.
		backdrop =
			remember {
				if (preferences.launcher.rounded)
					Backdrop.Clear
				else
					Backdrop.Opaque
			},
	) {
		val native = this.window

		LaunchedEffect(Unit) { native.raise() }
		HostedWindow(native, palette, preferences.typography, preferences.dates) {
			CompositionLocalProvider(LocalIconImages provides explorer.icons, content = content)
		}
	}
}

/**
 * The launcher's settings' edits, kept while their window is open, and saved as the preferences' are. The window
 * is at the page it was [asked] for at, each time it is asked for ([raised] counts them): as it opens, and turned to
 * it where it was open at another.
 */
@Composable
private fun LauncherSettingsWindow(
	explorer: Explorer,
	asked: LauncherPage?,
	raised: Int,
	onClose: () -> Unit,
) {
	val chords =
		remember {
			MutableSharedFlow<Chord>(
				extraBufferCapacity = 8,
				onBufferOverflow = BufferOverflow.DROP_OLDEST,
			)
		}

	var settings by remember {
		mutableStateOf(LauncherSettings(explorer.preferences.preferences.value))
	}
	var edits by remember { mutableStateOf(Edits<LauncherSettings>()) }

	LaunchedEffect(raised, asked) {
		asked?.let { settings = settings.copy(page = it) }
	}

	val applications = remember { explorer.desktop.applications.all() }
	val applied by explorer.preferences.preferences.collectAsState()

	fun stepped(undone: Undone<LauncherSettings>?) {
		undone?.let {
			edits = it.edits
			settings = it.to
		}
	}

	Box(Modifier.fillMaxSize().mouseChords { chords.tryEmit(it) }) {
		LauncherSettingsView(
			settings,
			applications,
			onChange = {
				edits = edits.changed(settings, it)
				settings = it
			},
			onApply = {
				explorer.preferences.update(settings::over)
				settings = settings.applied(explorer.preferences.preferences.value)
				edits = Edits()
			},
			onClose = onClose,
			mouseChords = chords,
			keys = remember(applied) { Bindings.of(applied) },
			onUndo = { stepped(edits.undo(settings)) },
			onRedo = { stepped(edits.redo(settings)) },
		)
	}
}

/** The preferences window's edits, kept while it is open, and saved over the preferences as they are by then. */
@Composable
private fun SettingsWindow(
	explorer: Explorer,
	window: ExplorerWindow.Settings,
	onClose: () -> Unit,
) {
	val chords =
		remember {
			MutableSharedFlow<Chord>(
				extraBufferCapacity = 8,
				onBufferOverflow = BufferOverflow.DROP_OLDEST,
			)
		}

	var settings by remember {
		mutableStateOf(
			Settings(explorer.preferences.preferences.value).let { opened ->
				window.page?.let { opened.copy(page = it) } ?: opened
			},
		)
	}

	// Asked for again at a page (its key bindings, from a search), it goes to that page, what was being edited kept.
	LaunchedEffect(
		window.raised,
	) { window.page?.let { settings = settings.copy(page = it) } }

	// The window's edits not yet applied, to be undone: its own, apart from any other window's, and forgotten once
	// applied or closed.
	var edits by remember { mutableStateOf(Edits<Settings>()) }

	fun stepped(undone: Undone<Settings>?) {
		undone?.let {
			edits = it.edits
			settings = it.to
		}
	}

	// The terminals there are to choose from, found on the disk as the window opens.
	val terminals by produceState(TerminalOptions.NONE) {
		value = withContext(Dispatchers.IO) { explorer.desktop.launcher.terminals() }
	}
	// How the windows are drawn, and the graphics devices, looked at afresh each time the Renderer page is opened.
	val native = LocalNativeWindow.current
	val examining = settings.page == Page.Renderer
	val graphics by produceState<GraphicsReport?>(null, native, examining) {
		if (examining && native != null)
			value =
				withContext(Dispatchers.IO) { Graphics.examine(native.rendering, native.drawnOn) }
	}

	Box(Modifier.fillMaxSize().mouseChords { chords.tryEmit(it) }) {
		SettingsView(
			settings,
			onChange = {
				edits = edits.changed(settings, it)
				settings = it
			},
			// Applied, the window stays open, editing from what is applied.
			onApply = {
				explorer.preferences.update(settings::over)
				settings = settings.applied(explorer.preferences.preferences.value)
				edits = Edits()
			},
			onClose = onClose,
			mouseChords = chords,
			terminalPreview = { choice, command ->
				explorer.desktop.launcher.terminalCommand(explorer.home, choice, command)
			},
			terminals = terminals,
			graphics = graphics,
			onCopy = ::toClipboard,
			world = explorer.world,
			onUndo = { stepped(edits.undo(settings)) },
			onRedo = { stepped(edits.redo(settings)) },
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
 * by [onClose] (its own buttons call it too) or by Escape where nothing in it uses the key; what goes wrong in it is
 * told to [onFailure]. Each time [raised] changes,
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
	onFailure: (Throwable) -> Unit,
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
		onFailure = onFailure,
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
