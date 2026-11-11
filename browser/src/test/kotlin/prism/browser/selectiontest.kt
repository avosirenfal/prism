package prism.browser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.awt.ComposeWindow
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import prism.browser.Fixtures.picker

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
			System.getProperty("browser.display") != null && !GraphicsEnvironment.isHeadless(),
		)

		val why = (1..5).joinToString("\n") { "line $it of what the command wrote" }

		val failed =
			picker(
				Choosing.Exploring(),
			).copy(notice = Notice.Failed("“Extract to…” failed", why))

		val clipboard = Toolkit.getDefaultToolkit().systemClipboard
		var window: ComposeWindow? = null

		clipboard.setContents(StringSelection("nothing yet"), null)
		try {
			EventQueue.invokeAndWait {
				window =
					ComposeWindow().apply {
						isUndecorated = true
						setBounds(0, 0, 900, 600)
						setContent {
							CompositionLocalProvider(LocalMotion provides false) {
								BrowserTheme { FileBrowser(failed) }
							}
						}
						isVisible = true
					}
			}

			val robot = Robot().apply { autoDelay = 30 }

			robot.waitForIdle()
			robot.delay(1500)
			System.getProperty("browser.screenshots")?.let { dir ->
				javax.imageio.ImageIO.write(
					robot.createScreenCapture(java.awt.Rectangle(0, 0, 900, 600)),
					"png",
					java.io.File(dir, "notice-window.png").also { it.parentFile.mkdirs() },
				)
			}

			// From the start of what the command wrote (its first line, left in the notice's box) to the end of its last.
			robot.mouseMove(199, 254)
			robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
			for (step in 1..10)
				robot.mouseMove(199 + step * 21, 254 + step * 6)
			robot.mouseMove(420, 318)
			robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
			robot.delay(300)
			robot.keyPress(KeyEvent.VK_CONTROL)
			robot.keyPress(KeyEvent.VK_C)
			robot.keyRelease(KeyEvent.VK_C)
			robot.keyRelease(KeyEvent.VK_CONTROL)
			robot.delay(500)

			val copied = clipboard.getData(DataFlavor.stringFlavor) as String

			assertTrue("of what the command wrote" in copied, "copied: “$copied”")
		} finally {
			EventQueue.invokeAndWait { window?.dispose() }
		}
	}
}
