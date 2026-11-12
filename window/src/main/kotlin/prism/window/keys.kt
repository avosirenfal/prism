@file:OptIn(InternalComposeUiApi::class)

package prism.window

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.Component
import java.awt.event.InputEvent
import org.lwjgl.sdl.SDLKeycode.SDLK_APPLICATION
import org.lwjgl.sdl.SDLKeycode.SDLK_CAPSLOCK
import org.lwjgl.sdl.SDLKeycode.SDLK_DOWN
import org.lwjgl.sdl.SDLKeycode.SDLK_END
import org.lwjgl.sdl.SDLKeycode.SDLK_F1
import org.lwjgl.sdl.SDLKeycode.SDLK_F12
import org.lwjgl.sdl.SDLKeycode.SDLK_F13
import org.lwjgl.sdl.SDLKeycode.SDLK_F24
import org.lwjgl.sdl.SDLKeycode.SDLK_GRAVE
import org.lwjgl.sdl.SDLKeycode.SDLK_HOME
import org.lwjgl.sdl.SDLKeycode.SDLK_INSERT
import org.lwjgl.sdl.SDLKeycode.SDLK_KP_0
import org.lwjgl.sdl.SDLKeycode.SDLK_KP_1
import org.lwjgl.sdl.SDLKeycode.SDLK_KP_9
import org.lwjgl.sdl.SDLKeycode.SDLK_KP_DIVIDE
import org.lwjgl.sdl.SDLKeycode.SDLK_KP_ENTER
import org.lwjgl.sdl.SDLKeycode.SDLK_KP_MINUS
import org.lwjgl.sdl.SDLKeycode.SDLK_KP_MULTIPLY
import org.lwjgl.sdl.SDLKeycode.SDLK_KP_PERIOD
import org.lwjgl.sdl.SDLKeycode.SDLK_KP_PLUS
import org.lwjgl.sdl.SDLKeycode.SDLK_LALT
import org.lwjgl.sdl.SDLKeycode.SDLK_LCTRL
import org.lwjgl.sdl.SDLKeycode.SDLK_LEFT
import org.lwjgl.sdl.SDLKeycode.SDLK_LGUI
import org.lwjgl.sdl.SDLKeycode.SDLK_LSHIFT
import org.lwjgl.sdl.SDLKeycode.SDLK_MENU
import org.lwjgl.sdl.SDLKeycode.SDLK_MODE
import org.lwjgl.sdl.SDLKeycode.SDLK_NUMLOCKCLEAR
import org.lwjgl.sdl.SDLKeycode.SDLK_PAGEDOWN
import org.lwjgl.sdl.SDLKeycode.SDLK_PAGEUP
import org.lwjgl.sdl.SDLKeycode.SDLK_PAUSE
import org.lwjgl.sdl.SDLKeycode.SDLK_PRINTSCREEN
import org.lwjgl.sdl.SDLKeycode.SDLK_RALT
import org.lwjgl.sdl.SDLKeycode.SDLK_RCTRL
import org.lwjgl.sdl.SDLKeycode.SDLK_RETURN
import org.lwjgl.sdl.SDLKeycode.SDLK_RGUI
import org.lwjgl.sdl.SDLKeycode.SDLK_RIGHT
import org.lwjgl.sdl.SDLKeycode.SDLK_RSHIFT
import org.lwjgl.sdl.SDLKeycode.SDLK_SCROLLLOCK
import org.lwjgl.sdl.SDLKeycode.SDLK_UP
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_ALT
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_GUI
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_MODE
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_SHIFT
import org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_0
import org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_1
import org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_9
import org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_A
import org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_Z
import java.awt.event.KeyEvent as Awt

/*
 * Keys, as Compose's desktop reads them. Compose for the desktop knows a key by AWT's key code and where on the
 * keyboard it is (`Key.ShiftLeft` is AWT's Shift on the left), reads text typed from AWT's "typed" events, and asks
 * an event for its modifiers as AWT keeps them. So SDL's keys are made into AWT's events, which are only values:
 * nothing of AWT's toolkit is touched, and no AWT window is ever made. Everything Compose does with keys (a text
 * field's shortcuts, focus moving on Tab, `onKeyEvent`) then works as it does in Compose's own windows.
 *
 * A key is known by what it types on the user's layout where that is a Latin letter, digit or mark, so that Ctrl+Z
 * is the key marked Z; on a layout of other letters (Cyrillic, Greek), by where it is, as browsers do, so that
 * shortcuts still work.
 */

/** What every event says it came from: AWT wants a component, and this is one only by name. */
private object Source : Component() {
	private fun readResolve(): Any = Source
}

/** AWT's key code and keyboard location for SDL's [keycode] (pressed at [scancode]); null for a key AWT has no code for. */
internal fun awtKeyOf(keycode: Int, scancode: Int, mac: Boolean): Pair<Int, Int>? {
	val standard = Awt.KEY_LOCATION_STANDARD
	val left = Awt.KEY_LOCATION_LEFT
	val right = Awt.KEY_LOCATION_RIGHT
	val pad = Awt.KEY_LOCATION_NUMPAD

	val named: Pair<Int, Int>? =
		when (keycode) {
			SDLK_RETURN -> Awt.VK_ENTER to standard
			SDLK_GRAVE -> Awt.VK_BACK_QUOTE to standard
			'\''.code -> Awt.VK_QUOTE to standard
			SDLK_LSHIFT -> Awt.VK_SHIFT to left
			SDLK_RSHIFT -> Awt.VK_SHIFT to right
			SDLK_LCTRL -> Awt.VK_CONTROL to left
			SDLK_RCTRL -> Awt.VK_CONTROL to right
			SDLK_LALT -> Awt.VK_ALT to left
			SDLK_RALT -> Awt.VK_ALT to right
			SDLK_MODE -> Awt.VK_ALT_GRAPH to standard
			SDLK_LGUI -> (if (mac) Awt.VK_META else Awt.VK_WINDOWS) to left
			SDLK_RGUI -> (if (mac) Awt.VK_META else Awt.VK_WINDOWS) to right
			SDLK_APPLICATION, SDLK_MENU -> Awt.VK_CONTEXT_MENU to standard
			SDLK_LEFT -> Awt.VK_LEFT to standard
			SDLK_RIGHT -> Awt.VK_RIGHT to standard
			SDLK_UP -> Awt.VK_UP to standard
			SDLK_DOWN -> Awt.VK_DOWN to standard
			SDLK_HOME -> Awt.VK_HOME to standard
			SDLK_END -> Awt.VK_END to standard
			SDLK_PAGEUP -> Awt.VK_PAGE_UP to standard
			SDLK_PAGEDOWN -> Awt.VK_PAGE_DOWN to standard
			SDLK_INSERT -> Awt.VK_INSERT to standard
			SDLK_CAPSLOCK -> Awt.VK_CAPS_LOCK to standard
			SDLK_NUMLOCKCLEAR -> Awt.VK_NUM_LOCK to pad
			SDLK_SCROLLLOCK -> Awt.VK_SCROLL_LOCK to standard
			SDLK_PRINTSCREEN -> Awt.VK_PRINTSCREEN to standard
			SDLK_PAUSE -> Awt.VK_PAUSE to standard
			SDLK_KP_ENTER -> Awt.VK_ENTER to pad
			SDLK_KP_PLUS -> Awt.VK_ADD to pad
			SDLK_KP_MINUS -> Awt.VK_SUBTRACT to pad
			SDLK_KP_MULTIPLY -> Awt.VK_MULTIPLY to pad
			SDLK_KP_DIVIDE -> Awt.VK_DIVIDE to pad
			SDLK_KP_PERIOD -> Awt.VK_DECIMAL to pad
			SDLK_KP_0 -> Awt.VK_NUMPAD0 to pad
			in SDLK_KP_1..SDLK_KP_9 -> Awt.VK_NUMPAD1 + (keycode - SDLK_KP_1) to pad
			in SDLK_F1..SDLK_F12 -> Awt.VK_F1 + (keycode - SDLK_F1) to standard
			in SDLK_F13..SDLK_F24 -> Awt.VK_F13 + (keycode - SDLK_F13) to standard
			else -> null
		}

	return named
		?: when (keycode) {
			// Letters: SDL's are the lower case the key types, AWT's the upper.
			in 'a'.code..'z'.code -> keycode - 'a'.code + 'A'.code to standard
			// Backspace, Tab, Escape, Space, Delete, digits, and the marks AWT codes by their own character.
			8, 9, 0x1B, 0x20, 0x7F, in '0'.code..'9'.code, ','.code, '-'.code, '.'.code, '/'.code, ';'
				.code,
			'='.code, '['.code, '\\'.code, ']'.code,
			-> keycode to standard
			else ->
				// A key of a layout of other letters: by where it is.
				when (scancode) {
					in SDL_SCANCODE_A..SDL_SCANCODE_Z ->
						Awt.VK_A + (scancode - SDL_SCANCODE_A) to
							standard
					in SDL_SCANCODE_1..SDL_SCANCODE_9 ->
						Awt.VK_1 + (scancode - SDL_SCANCODE_1) to
							standard
					SDL_SCANCODE_0 -> Awt.VK_0 to standard
					else -> null
				}
		}
}

/** AWT's modifiers for SDL's [mods]: the keys held, as AWT's extended modifiers say them. */
internal fun awtModifiersOf(mods: Int): Int =
	(
		if (mods and SDL_KMOD_SHIFT != 0)
			InputEvent.SHIFT_DOWN_MASK
		else
			0
	) or
		(
			if (mods and SDL_KMOD_CTRL != 0)
				InputEvent.CTRL_DOWN_MASK
			else
				0
		) or
		(
			if (mods and SDL_KMOD_ALT != 0)
				InputEvent.ALT_DOWN_MASK
			else
				0
		) or
		(
			if (mods and SDL_KMOD_GUI != 0)
				InputEvent.META_DOWN_MASK
			else
				0
		) or
		(
			if (mods and SDL_KMOD_MODE != 0)
				InputEvent.ALT_GRAPH_DOWN_MASK
			else
				0
		)

/** A key pressed or let go, as Compose's event; null for a key Compose could not name. */
internal fun keyEventOf(
	down: Boolean,
	keycode: Int,
	scancode: Int,
	mods: Int,
	mac: Boolean,
): KeyEvent? {
	val (code, location) = awtKeyOf(keycode, scancode, mac) ?: return null

	return composeEventOf(
		Awt(
			Source,
			if (down) Awt.KEY_PRESSED else Awt.KEY_RELEASED,
			System.currentTimeMillis(),
			awtModifiersOf(mods),
			code,
			Awt.CHAR_UNDEFINED,
			location,
		),
	)
}

/** [awt] as Compose's event, as Compose's own windows make theirs: the key by its code and place, AWT's event within. */
private fun composeEventOf(awt: Awt): KeyEvent =
	KeyEvent(
		key = Key(awt.keyCode, awt.keyLocation),
		type =
			when (awt.id) {
				Awt.KEY_PRESSED -> KeyEventType.KeyDown
				Awt.KEY_RELEASED -> KeyEventType.KeyUp
				else -> KeyEventType.Unknown
			},
		codePoint = awt.keyChar.code,
		isCtrlPressed = awt.isControlDown,
		isMetaPressed = awt.isMetaDown,
		isAltPressed = awt.isAltDown,
		isShiftPressed = awt.isShiftDown,
		nativeEvent = awt,
	)

/** Each character of [text], typed: what a text field takes in. */
internal fun typedEventsOf(text: String, mods: Int): List<KeyEvent> =
	text.map { char ->
		composeEventOf(
			Awt(
				Source,
				Awt.KEY_TYPED,
				System.currentTimeMillis(),
				// What typed it was a key with Shift or AltGr, perhaps; never a shortcut, which types nothing.
				awtModifiersOf(
					mods,
				) and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK).inv(),
				Awt.VK_UNDEFINED,
				char,
				Awt.KEY_LOCATION_UNKNOWN,
			),
		)
	}
