package prism.browser

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
 * The mouse's buttons beyond the two every click is: the middle one, and the side buttons (back and forward, the
 * fourth and fifth buttons of most mice), which a chord can be made of, as a key can (bindings.kt). By default the
 * side buttons go back and forward, as they do in a web browser.
 *
 * Compose cannot be asked for the side buttons on Linux. X numbers them 8 and 9, after its four buttons of scrolling (4
 * and 5 up and down, 6 and 7 left and right). The JDK passes X's 6 and 7 on as AWT's buttons 4 and 5, and X's 8 and 9
 * as AWT's 6 and 7. Compose takes AWT's 4 and 5 for back and forward, which is right on Windows and macOS, so on Linux
 * it reports a sideways scroll (a tilted wheel, a touchpad) as back and forward, and the side buttons as nothing at
 * all. So the buttons are read from AWT itself, numbered as the platform numbers them.
 */

/** Which of the buttons a chord can be made of AWT's button numbered [button] is, as [linux] numbers them or not. */
fun mouseButtonOf(
	button: Int,
	linux: Boolean = System.getProperty("os.name").startsWith("Linux"),
): MouseButton? =
	when {
		button == MouseEvent.BUTTON2 -> MouseButton.Middle
		button == (if (linux) 6 else 4) -> MouseButton.Back
		button == (if (linux) 7 else 5) -> MouseButton.Forward
		else -> null
	}

/**
 * The buttons a chord can be made of, pressed anywhere over [window], reported to [onChord] with the modifiers held, for
 * as long as this is composed. Presses over any other window are not this one's.
 */
@Composable
fun MouseChords(window: Window, onChord: (Chord) -> Unit) {
	val latest by rememberUpdatedState(onChord)

	DisposableEffect(window) {
		val listener =
			AWTEventListener { event ->
				val press = event as? MouseEvent

				if (press != null && press.id == MouseEvent.MOUSE_PRESSED && press.over(window))
					mouseButtonOf(press.button)?.let {
						latest(
							Chord(
								Trigger.Mouse(it),
								press.isControlDown,
								press.isShiftDown,
								press.isAltDown,
								press.isMetaDown,
							),
						)
					}
			}

		Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)

		onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
	}
}

private fun MouseEvent.over(window: Window): Boolean =
	component == window || SwingUtilities.getWindowAncestor(component) == window
