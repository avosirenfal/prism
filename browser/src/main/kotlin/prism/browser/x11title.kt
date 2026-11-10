package prism.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * The title bar a window manager draws over the picker, in its palette. On X11 an application draws only inside its
 * window: the bar with its title and its close button is the window manager's (under niri, xwayland-satellite's), and
 * an application can only ask for a dark one. GTK's way of asking, which window managers read for the frame they draw,
 * is a property on the window, `_GTK_THEME_VARIANT`, `dark` or `light`; so the picker sets it, and sets it again as
 * its palette follows the desktop's. A window manager that does not read it draws what it draws.
 */

/** For as long as this is composed, [window]'s title bar asked to be dark where [dark], and light otherwise. */
@Composable
fun TitleBarTone(window: ComposeWindow, dark: Boolean) {
	LaunchedEffect(window, dark) {
		if (!onX11())
			return@LaunchedEffect

		val x = X11.instance ?: return@LaunchedEffect

		// The window has no native handle until it is shown, which can be a moment after it is composed.
		var handle = window.windowHandle

		repeat(100) {
			if (handle != 0L)
				return@repeat
			delay(50)
			handle = window.windowHandle
		}
		if (handle == 0L)
			return@LaunchedEffect

		withContext(Dispatchers.IO) {
			val display = x.openDisplay() ?: return@withContext

			x.setText(display, handle, "_GTK_THEME_VARIANT", if (dark) "dark" else "light")
			x.flush(display)
			x.closeDisplay(display)
		}
	}
}

/** Whether the picker's windows are X11's (directly, or through XWayland), the only ones these are for. */
internal fun onX11(): Boolean =
	!GraphicsEnvironment.isHeadless() &&
		Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit"
