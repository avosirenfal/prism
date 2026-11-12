package prism.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class CellsTest {
	@Test
	fun aNumberIsDrawnAFigureAtATime_BetweenItsWords() {
		assertEquals(
			listOf("4", ",", "0", "9", "6", " bytes").map(::Run),
			figuresOf("4,096 bytes"),
		)
		assertEquals(listOf(Run("—")), figuresOf("—"))
	}

	@Test
	fun runsTooWideForTheirRoomAreOneLine_WithinIt_AndReadAsTheyAre() =
		runComposeUiTest {
			setContent {
				BrowserTheme {
					Box(Modifier.width(40.dp)) {
						CellText(
							figuresOf("1,234,567 bytes"),
							LocalType.current.body.tabular(),
							"1,234,567 bytes",
							Modifier.testTag("size"),
							TextAlign.End,
						)
					}
				}
			}

			onNodeWithText("1,234,567 bytes").assertExists()
			assertTrue(
				onNodeWithTag("size").fetchSemanticsNode().size.width <=
					with(density) { 40.dp.roundToPx() },
			)
		}
}
