package prism.macos

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Carbon's hot keys, Spotlight's key and the launcher's panel, on a Mac: a key no one has is had and let go,
 * Spotlight's is asked, and a panel is made.
 */
@EnabledOnOs(OS.MAC)
class HotKeyTest {
	@Test
	fun aKeyNoOneHasIsHadAndLetGo() {
		val f19 = MacKeys.of(java.awt.event.KeyEvent.VK_F19) ?: 0x50

		val registered =
			HotKey.register(
				f19,
				setOf(HotKeyModifier.Control, HotKeyModifier.Option, HotKeyModifier.Shift),
			) {
			}

		assertInstanceOf(HotKeyRegistered.Held::class.java, registered, "$registered")
		(registered as HotKeyRegistered.Held).key.close()
	}

	@Test
	fun spotlightsKeyIsAsked() {
		// Either answer is right, as the user has Spotlight's key; that it answers is what is tested.
		spotlightHolds(0x31, setOf(HotKeyModifier.Command))
	}

	@Test
	fun aPanelIsMadeAndClosed() {
		// AppKit's classes made at run time, a panel of them made as AppKit makes one, and let go of.
		// Made on AppKit's thread, which a test is not on: handed over, and waited for, but not for ever.
		assertTimeoutPreemptively(Duration.ofSeconds(20)) {
			Panel.make(640.0, 80.0).use { panel -> assertTrue(panel.window != 0L) }
		}
	}
}
