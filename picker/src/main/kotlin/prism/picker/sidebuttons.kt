package prism.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/*
 * The mouse's side buttons (back and forward, the fourth and fifth buttons of most mice), which go back and forward
 * in the picker as they do in a browser.
 *
 * Compose cannot be asked for them on Linux. X numbers them 8 and 9, after its four buttons of scrolling (4 and 5 up
 * and down, 6 and 7 left and right). The JDK passes X's 6 and 7 on as AWT's buttons 4 and 5, and X's 8 and 9 as AWT's
 * 6 and 7. Compose takes AWT's 4 and 5 for back and forward, which is right on Windows, so on Linux it reports a
 * sideways scroll (a tilted wheel, a touchpad) as back and forward, and the side buttons as nothing at all. So they
 * are read from AWT itself, by Linux's numbering, and Compose's back and forward are left alone.
 */

/** What the AWT mouse button numbered [button] does in the picker on Linux, if anything. */
fun sideButtonOf(button: Int): PickerEvent? =
	when (button) {
		6 -> PickerEvent.Back
		7 -> PickerEvent.Forward
		else -> null
	}

/**
 * The side buttons pressed anywhere over [window] reported to [onEvent] as [PickerEvent.Back] and
 * [PickerEvent.Forward], for as long as this is composed. Presses over any other window are not this picker's.
 */
@Composable
fun SideButtons(window: Window, onEvent: (PickerEvent) -> Unit) {
	val latest by rememberUpdatedState(onEvent)

	DisposableEffect(window) {
		val listener =
			AWTEventListener { event ->
				val press = event as? MouseEvent

				if (press != null && press.id == MouseEvent.MOUSE_PRESSED && press.over(window))
					sideButtonOf(press.button)?.let(latest)
			}

		Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)

		onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
	}
}

private fun MouseEvent.over(window: Window): Boolean =
	component == window || SwingUtilities.getWindowAncestor(component) == window
