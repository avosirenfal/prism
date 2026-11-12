package prism.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import prism.preferences.PreferenceStore
import prism.window.NativeWindow

/**
 * [content] in its [window], as every window of prism hosts what it shows: the title bar, where the platform's window
 * manager draws one from what it is told (X11), asked for in [palette]'s tone; and [palette] itself.
 */
@Composable
fun HostedWindow(
	window: NativeWindow,
	palette: Palette,
	content: @Composable () -> Unit,
) {
	TitleBarTone(window, palette.isDark)
	BrowserTheme(palette, content)
}

/**
 * A browser [session] (a picker's, or an explorer's) in its [window]: hosted as every window is ([HostedWindow]), with
 * the mouse's buttons bound as keys are, and every key bound as the user's [preferences] say, changing as they change.
 */
@Composable
fun HostedBrowser(
	window: NativeWindow,
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

	HostedWindow(window, palette) {
		CompositionLocalProvider(LocalBindings provides bindings) {
			Box(Modifier.fillMaxSize().mouseChords { chords.tryEmit(it) }) {
				SessionBrowser(session, mouseChords = chords)
			}
		}
	}
}
