package prism.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class FrameReportTest {
	@Test
	fun theNodeAnErrorNamesIsFound_AndWhatIsAboveItSaid() =
		runComposeUiTest {
			setContent {
				Row(Modifier.testTag("row")) {
					Box(
						Modifier
							.testTag("checkbox")
							.alpha(0.5f)
							.padding(4.dp)
							.size(10.dp),
					)
				}
			}

			val root = onRoot().fetchSemanticsNode().layoutInfo
			val id = onNodeWithTag("checkbox").fetchSemanticsNode().id

			val report =
				frameReport(
					IllegalArgumentException("LayoutNode $id not found in RectList"),
					"Preferences",
					listOf(root),
				)

			// The node, with its modifiers outermost first, then the row above it, up to the root.
			assertTrue("Layout node $id, and each node above it:" in report, report)
			assertTrue(
				"#$id placed 18x18 [testTag(checkbox), GraphicsLayer, Padding, Size]" in report,
				report,
			)
			assertTrue(Regex("""#\d+ placed 18x18 \[testTag\(row\)]""") in report, report)
			// An error naming no node, or one not there, says so.
			assertTrue(
				"names no layout node" in
					frameReport(IllegalStateException("odd"), "Preferences", listOf(root)),
			)
			assertTrue(
				"which is not in the window's layout" in
					frameReport(IllegalArgumentException("LayoutNode 999999"), "x", listOf(root)),
			)
		}
}
