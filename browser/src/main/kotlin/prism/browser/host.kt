package prism.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import prism.preferences.PreferenceStore

/**
 * [content] in its [window], as every window of prism hosts what it shows: the keyboard taken back from a compositor
 * that gives it to the window as AWT does not expect (x11focus.kt); the title bar, where there is one, asked for in
 * [palette]'s tone; and [palette] itself.
 */
@Composable
fun HostedWindow(
	window: ComposeWindow,
	palette: Palette,
	content: @Composable () -> Unit,
) {
	KeepsKeyboard(window)
	TitleBarTone(window, palette.isDark)
	BrowserTheme(palette, content)
}

/**
 * A browser [session] (a picker's, or an explorer's) in its [window]: hosted as every window is ([HostedWindow]), with
 * the mouse's buttons read from AWT and bound as keys are, and every key bound as the user's [preferences] say,
 * changing as they change.
 */
@Composable
fun HostedBrowser(
	window: ComposeWindow,
	session: Session,
	preferences: PreferenceStore,
	palette: Palette,
) {
	val chords =
		remember {
			MutableSharedFlow<Chord>(
				extraBufferCapacity = 8,
				onBufferOverflow = BufferOverflow.DROP_OLDEST,
			)
		}

	val set by preferences.preferences.collectAsState()
	val bindings = remember(set) { Bindings.of(set) }

	MouseChords(window) { chords.tryEmit(it) }
	HostedWindow(window, palette) {
		CompositionLocalProvider(LocalBindings provides bindings) {
			SessionBrowser(session, mouseChords = chords)
		}
	}
}
