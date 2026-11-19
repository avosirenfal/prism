package prism.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import prism.browser.BrowserTheme
import prism.browser.Field

/**
 * Tab, in the windows' text inputs: the keyboard moved on to the next, and Shift+Tab back, from a field of one line and
 * from one of several alike, and nothing typed by it. These are every text input the explorer, the picker and the
 * settings have.
 */
@OptIn(ExperimentalTestApi::class)
class TabTest {
	private val inputs = listOf("field", "area", "after")

	/** Which of [inputs] has the keyboard: the one the focused node is, or is inside. */
	private fun ComposeUiTest.focused(): String? =
		onAllNodes(isFocused()).fetchSemanticsNodes().firstNotNullOfOrNull { focused ->
			inputs.firstOrNull { tag ->
				onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().any { tagged ->
					generateSequence(focused) { it.parent }.any { it.id == tagged.id }
				}
			}
		}

	private fun ComposeUiTest.tab(back: Boolean = false) {
		onAllNodes(isFocused())[0].performKeyInput {
			if (back)
				withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }
			else
				pressKey(Key.Tab)
		}
		waitForIdle()
	}

	@Test
	fun tabMovesTheKeyboardOnFromEveryTextInput_AndShiftTabBack_TypingNothing() =
		runComposeUiTest {
			val texts: List<MutableState<String>> = inputs.map { mutableStateOf("") }

			setContent {
				BrowserTheme {
					Column {
						Field(texts[0].value, { texts[0].value = it }, Modifier.testTag("field"))
						TextArea(texts[1].value, { texts[1].value = it }, Modifier.testTag("area"))
						Field(texts[2].value, { texts[2].value = it }, Modifier.testTag("after"))
					}
				}
			}

			onNodeWithTag("field").performClick()
			waitForIdle()

			val forward = listOf(focused()) + (1..2).map { tab().let { focused() } }
			val backward = (1..2).map { tab(back = true).let { focused() } }

			assertEquals(listOf("field", "area", "after"), forward)
			assertEquals(listOf("area", "field"), backward)
			assertEquals(listOf("", "", ""), texts.map { it.value })
		}
}
