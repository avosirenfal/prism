package prism.browser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import prism.browser.Fixtures.picker
import prism.window.ClipOffer
import prism.window.SystemClipboard
import prism.window.Window
import prism.window.application

/** What a notice says, selected with the pointer and copied with Ctrl+C, as text is anywhere. */
class SelectionTest {
	/**
	 * In a real window, with the platform's own clipboard: a popup is given that clipboard by the window it opens in,
	 * whatever is provided around it, so only a window can say what is copied from a notice. Run where there is a
	 * display: `xvfb-run ./gradlew :browser:test --tests prism.browser.SelectionTest -Pbrowser.display`.
	 */
	@Test
	fun whatFailedIsSelectedWithThePointerAndCopiedWithCtrlCInAWindow() {
		assumeTrue(
			System.getProperty("browser.display") != null && System.getenv("DISPLAY") != null,
		)

		val why = (1..5).joinToString("\n") { "line $it of what the command wrote" }

		val failed =
			picker(
				Choosing.Exploring(),
			).copy(notice = Notice.Failed("“Extract to…” failed", why))

		fun xdotool(vararg words: String) =
			assertEquals(
				0,
				ProcessBuilder(listOf("xdotool") + words).inheritIO().start().waitFor(),
			)

		var copied: String? = null

		application("prism-test") {
			Window({}, "failed", DpSize(900.dp, 600.dp), decorated = false, at = IntOffset(0, 0)) {
				CompositionLocalProvider(LocalMotion provides false) {
					BrowserTheme { FileBrowser(failed) }
				}
				LaunchedEffect(Unit) {
					SystemClipboard.put(ClipOffer.text("nothing yet"))
					delay(1500)
					withContext(Dispatchers.IO) {
						// From the start of what the command wrote (its first line, left in the notice's box) to the
						// end of its last; then Ctrl+C.
						xdotool("mousemove", "199", "254", "mousedown", "1")
						for (step in 1..10)
							xdotool("mousemove", "${199 + step * 21}", "${254 + step * 6}")
						xdotool("mousemove", "420", "318", "mouseup", "1", "sleep", "0.3", "key", "ctrl+c")
					}
					delay(500)
					copied = SystemClipboard.text()
					exitApplication()
				}
			}
		}

		assertTrue("of what the command wrote" in copied.orEmpty(), "copied: “$copied”")
	}
}
