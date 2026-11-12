@file:OptIn(ExperimentalComposeUiApi::class)

package prism.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput

/*
 * The mouse's buttons beyond the two every click is: the middle one, and the side buttons (back and forward, the
 * fourth and fifth buttons of most mice), which a chord can be made of, as a key can (bindings.kt). By default the
 * side buttons go back and forward, as they do in a web browser. The window gives Compose each button as the
 * platform names it (prism.window), so they are read from Compose's own events.
 */

/** Which of the buttons a chord can be made of [button] is: none, for the two every click is. */
fun mouseButtonOf(button: PointerButton?): MouseButton? =
	when (button) {
		PointerButton.Tertiary -> MouseButton.Middle
		PointerButton.Back -> MouseButton.Back
		PointerButton.Forward -> MouseButton.Forward
		else -> null
	}

/**
 * The buttons a chord can be made of, pressed anywhere over this, reported to [onChord] with the modifiers held, before
 * anything within sees the press.
 */
@Composable
fun Modifier.mouseChords(onChord: (Chord) -> Unit): Modifier {
	val latest by rememberUpdatedState(onChord)

	return pointerInput(Unit) {
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Initial)
				val keys = event.keyboardModifiers

				if (event.type == PointerEventType.Press)
					mouseButtonOf(event.button)?.let {
						latest(
							Chord(
								Trigger.Mouse(it),
								keys.isCtrlPressed,
								keys.isShiftPressed,
								keys.isAltPressed,
								keys.isMetaPressed,
							),
						)
					}
			}
		}
	}
}
