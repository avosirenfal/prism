package prism.explorer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.BrowserTheme
import prism.browser.Palette
import prism.browser.Start
import prism.browser.renderedPng

/**
 * The window shown in place of one that failed: what it says, what its buttons do, and that it draws, since it is
 * what is shown when something else could not be.
 */
@OptIn(ExperimentalTestApi::class)
class FailedTest {
	private val failure =
		Failure.of(
			IllegalStateException(
				"Vertically scrollable component was measured with an infinity maximum height constraints",
			),
		)

	private val browsing =
		ExplorerWindow.Failed(
			WindowId(7),
			"Documents",
			failure,
			Start.Folder(Path.of("/home/a/Documents")),
		)

	@Test
	fun itSaysWhatFailedAndItsButtonsCopyOpenAgainAndClose() =
		runComposeUiTest {
			var copied: String? = null
			var again = 0
			var closed = 0

			setContent {
				BrowserTheme {
					FailedView(browsing, { again++ }, { copied = it }, { closed++ })
				}
			}

			onNodeWithText("Documents ran into a problem").assertExists()
			onNodeWithText(FAILED_DESCRIPTION).assertExists()
			onNodeWithText(failure.summary).assertExists()
			onNodeWithTag("failed-copy").performClick()
			onNodeWithTag("failed-again").performClick()
			onNodeWithTag("failed-close").performClick()
			waitForIdle()

			// What is copied is what a report of it wants: what failed, in a line, and where, in full.
			assertTrue(
				copied.orEmpty().startsWith("Documents: IllegalStateException: Vertically"),
				copied,
			)
			assertTrue("at prism.explorer.FailedTest" in copied.orEmpty(), copied)
			assertEquals(1 to 1, again to closed)
		}

	@Test
	fun aWindowThatWasNotBrowsingHasNothingToOpenAgain() =
		runComposeUiTest {
			setContent {
				BrowserTheme {
					FailedView(browsing.copy(what = "Preferences", again = null), null, {}, {})
				}
			}

			onNodeWithText("Preferences ran into a problem").assertExists()
			onNodeWithTag("failed-again").assertDoesNotExist()
			onNodeWithTag("failed-close").assertExists()
		}

	/** Drawn headless, in both palettes: with `-Pexplorer.screenshots=DIR`, kept there. */
	@Test
	fun itIsDrawn() {
		for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
			val png =
				renderedPng(720, 520, palette, scale = 2f) {
					FailedView(browsing, {}, {}, {})
				}

			assertTrue(png.size > 20_000, "$tone: ${png.size} bytes")
			System.getProperty("explorer.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
				Path
					.of(dir)
					.createDirectories()
					.resolve("failed-$tone.png")
					.writeBytes(png)
			}
		}
	}
}
