package prism.macos

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/** Carbon's hot keys and Spotlight's key, on a Mac: a key no one has is had and let go, and Spotlight's is asked. */
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
		Overlay::class.java
	}
}
