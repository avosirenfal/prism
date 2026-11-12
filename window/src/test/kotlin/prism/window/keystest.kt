package prism.window

import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import java.awt.event.InputEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.sdl.SDLKeycode.SDLK_F5
import org.lwjgl.sdl.SDLKeycode.SDLK_KP_7
import org.lwjgl.sdl.SDLKeycode.SDLK_LGUI
import org.lwjgl.sdl.SDLKeycode.SDLK_LSHIFT
import org.lwjgl.sdl.SDLKeycode.SDLK_RETURN
import org.lwjgl.sdl.SDLKeycode.SDLK_RSHIFT
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_LCTRL
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_RSHIFT
import org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_Q
import org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_Z
import java.awt.event.KeyEvent as Awt

class KeysTest {
	@Test
	fun aKeyIsWhatItTypesOnTheLayoutWhereThatIsLatin() {
		// On AZERTY the key where QWERTY has Q types "a": it is A, so Ctrl+A selects all on the key marked A.
		assertEquals(
			Awt.VK_A to Awt.KEY_LOCATION_STANDARD,
			awtKeyOf('a'.code, SDL_SCANCODE_Q, mac = false),
		)
		assertEquals(
			Awt.VK_SLASH to Awt.KEY_LOCATION_STANDARD,
			awtKeyOf('/'.code, 0, mac = false),
		)
		assertEquals(
			Awt.VK_QUOTE to Awt.KEY_LOCATION_STANDARD,
			awtKeyOf('\''.code, 0, mac = false),
		)
	}

	@Test
	fun onALayoutOfOtherLettersAKeyIsWhereItIs() {
		// Russian: the key that types "я" is where Z is, and is Z, so Ctrl+Z still undoes.
		assertEquals(
			Awt.VK_Z to Awt.KEY_LOCATION_STANDARD,
			awtKeyOf('я'.code, SDL_SCANCODE_Z, mac = false),
		)
	}

	@Test
	fun keysThatAreTwiceAreToldApartByWhereTheyAre() {
		assertEquals(
			Awt.VK_SHIFT to Awt.KEY_LOCATION_LEFT,
			awtKeyOf(SDLK_LSHIFT, 0, mac = false),
		)
		assertEquals(
			Awt.VK_SHIFT to Awt.KEY_LOCATION_RIGHT,
			awtKeyOf(SDLK_RSHIFT, 0, mac = false),
		)
		assertEquals(
			Awt.VK_ENTER to Awt.KEY_LOCATION_STANDARD,
			awtKeyOf(SDLK_RETURN, 0, mac = false),
		)
		assertEquals(
			Awt.VK_NUMPAD7 to Awt.KEY_LOCATION_NUMPAD,
			awtKeyOf(SDLK_KP_7, 0, mac = false),
		)
		assertEquals(Awt.VK_F5 to Awt.KEY_LOCATION_STANDARD, awtKeyOf(SDLK_F5, 0, mac = false))
	}

	@Test
	fun theSystemKeyIsCommandOnMacOSAndTheWindowsKeyElsewhere() {
		assertEquals(Awt.VK_META, awtKeyOf(SDLK_LGUI, 0, mac = true)?.first)
		assertEquals(Awt.VK_WINDOWS, awtKeyOf(SDLK_LGUI, 0, mac = false)?.first)
	}

	@Test
	fun aKeyNothingNamesIsNone() {
		assertNull(awtKeyOf(0x40000123, 0x123, mac = false))
	}

	@Test
	fun modifiersAreAwtsExtendedOnes() {
		assertEquals(
			InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
			awtModifiersOf(SDL_KMOD_LCTRL or SDL_KMOD_RSHIFT),
		)
	}

	@Test
	fun textTypedIsAnEventACharacterThatNoShortcutClaims() {
		val typed = typedEventsOf("hé", SDL_KMOD_LCTRL)

		assertEquals(listOf('h'.code, 'é'.code), typed.map { it.utf16CodePoint })
		assertTrue(typed.all { it.type == KeyEventType.Unknown })
		assertFalse(typed.any { it.isCtrlPressed })
	}

	@Test
	fun openGlOnTheCpuIsToldFromAGpu() {
		assertTrue(isSoftware("llvmpipe (LLVM 20.1.2, 256 bits)"))
		assertTrue(isSoftware("GDI Generic"))
		assertFalse(isSoftware("Mesa Intel(R) UHD Graphics 620 (KBL GT2)"))
		assertFalse(isSoftware("NVIDIA GeForce RTX 4070/PCIe/SSE2"))
	}
}
