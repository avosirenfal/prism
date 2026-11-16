package prism.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import prism.macos.HotKeyModifier

/** The launcher's key on a Mac, as Carbon is given it: its key's code and its modifiers, read in a Mac's names. */
class HotKeyTest {
	@Test
	fun aChordIsCarbonsKeyAndModifiers() {
		assertEquals(
			HotKeyChord.Of(49, setOf(HotKeyModifier.Option)),
			HotKeyChord.of("Option+Space"),
		)
		assertEquals(
			HotKeyChord.Of(49, setOf(HotKeyModifier.Command)),
			HotKeyChord.of("Cmd+Space"),
		)
		assertEquals(
			HotKeyChord.Of(0x28, setOf(HotKeyModifier.Control, HotKeyModifier.Option)),
			HotKeyChord.of("Ctrl+Option+K"),
		)
		assertEquals(
			HotKeyChord.Of(0x7A, setOf(HotKeyModifier.Shift)),
			HotKeyChord.of("Shift+F1"),
		)
		assertInstanceOf(HotKeyChord.Not::class.java, HotKeyChord.of("Space"))
		assertInstanceOf(HotKeyChord.Not::class.java, HotKeyChord.of("Option+NoSuchKey"))
		assertInstanceOf(HotKeyChord.Not::class.java, HotKeyChord.of("Option+Mouse Back"))
	}
}
