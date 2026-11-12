package prism.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import prism.window.NativeHandle
import prism.window.NativeWindow

/*
 * The title bar a window manager draws over the picker, in its palette. On X11 an application draws only inside its
 * window: the bar with its title and its close button is the window manager's (under niri, xwayland-satellite's), and
 * an application can only ask for a dark one. GTK's way of asking, which window managers read for the frame they draw,
 * is a property on the window, `_GTK_THEME_VARIANT`, `dark` or `light`; so the picker sets it, and sets it again as
 * its palette follows the desktop's. A window manager that does not read it draws what it draws.
 */

/** For as long as this is composed, [window]'s title bar asked to be dark where [dark], and light otherwise. */
@Composable
fun TitleBarTone(window: NativeWindow, dark: Boolean) {
	LaunchedEffect(window, dark) {
		val handle = window.handle as? NativeHandle.X11 ?: return@LaunchedEffect
		val x = X11.instance ?: return@LaunchedEffect

		withContext(Dispatchers.IO) {
			val display = x.openDisplay() ?: return@withContext

			x.setText(display, handle.window, "_GTK_THEME_VARIANT", if (dark) "dark" else "light")
			x.flush(display)
			x.closeDisplay(display)
		}
	}
}

/**
 * For as long as this is composed, [window] a dialog of the X11 window [parent]: the window manager keeps it over
 * that window, and puts it with it, as a dialog of an application's own would be. What the portal asks a file chooser
 * to be, of the application that asked for it, where both are X11 windows; a Wayland window's parent is named another
 * way (xdg-foreign), not yet.
 */
@Composable
fun TransientFor(window: NativeWindow, parent: Long?) {
	LaunchedEffect(window, parent) {
		val handle = window.handle as? NativeHandle.X11 ?: return@LaunchedEffect
		val x = X11.instance ?: return@LaunchedEffect

		if (parent == null)
			return@LaunchedEffect

		withContext(Dispatchers.IO) {
			val display = x.openDisplay() ?: return@withContext

			x.setWindow(display, handle.window, "WM_TRANSIENT_FOR", parent)
			x.flush(display)
			x.closeDisplay(display)
		}
	}
}
