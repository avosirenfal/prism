package prism.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import prism.preferences.Moved
import prism.preferences.moved

@OptIn(ExperimentalTestApi::class)
class ReorderTest {
	/** Five letters in a list [moving] as it says, each with a button that counts its clicks, kept in order as moved. */
	private fun ComposeUiTest.letters(moving: Moving): Letters {
		val letters = Letters()

		setContent {
			BrowserTheme {
				Box(Modifier.width(300.dp).focusable().testTag("list")) {
					ReorderableColumn(letters.order, {
						it
					}, moving, onMove = letters::move) { letter, handle, lit ->
						Row(Modifier.fillMaxWidth().height(40.dp).testTag("row:$letter")) {
							Box(handle.width(40.dp).height(40.dp).testTag("grip:$letter"))
							Text(
								if (lit)
									"$letter lit"
								else
									letter,
							)
							Box(
								Modifier.width(40.dp).height(40.dp).testTag("button:$letter").clickable {
									letters.pressed +=
										letter
								},
							)
						}
					}
				}
			}
		}

		return letters
	}

	private class Letters {
		var order by mutableStateOf(listOf("a", "b", "c", "d", "e"))
		var moves = emptyList<Moved>()
		var pressed = emptyList<String>()

		fun move(move: Moved) {
			moves = moves + move
			order = order.moved(move)
		}
	}

	private fun ComposeUiTest.shiftClick(tag: String) {
		onNodeWithTag("list").requestFocus()
		onNodeWithTag("list").performKeyInput { keyDown(Key.ShiftLeft) }
		onNodeWithTag(tag).performMouseInput { click() }
		onNodeWithTag("list").performKeyInput { keyUp(Key.ShiftLeft) }
		waitForIdle()
	}

	/**
	 * [letter]'s grip dragged [rows] rows down (up, where negative), a quarter of a row at a time, and half a row more,
	 * for the way the pointer goes before a drag is one.
	 */
	private fun ComposeUiTest.drag(letter: String, rows: Int) {
		val row =
			onNodeWithTag("row:$letter")
				.fetchSemanticsNode()
				.size.height
				.toFloat()
		val step = Offset(0f, row * 0.25f * sign(rows.toFloat()))

		onNodeWithTag("grip:$letter").performTouchInput {
			down(center)
			repeat(abs(rows) * 4 + 2) { moveBy(step) }
			up()
		}
		waitForIdle()
	}

	@Test
	fun severalChosenByShiftAndAClickAreDraggedTogether_GatheredInTheirOrder() =
		runComposeUiTest {
			val letters = letters(Moving.Several)

			shiftClick("row:b")
			shiftClick("row:d")
			onNodeWithText("b lit").assertExists()
			onNodeWithText("d lit").assertExists()
			onNodeWithText("c lit").assertDoesNotExist()

			// d dragged up three rows: b, chosen too, is gathered above it, and the two are let go first, in their order.
			drag("d", -3)
			assertEquals(listOf(Moved(listOf(1, 3), 0)), letters.moves)
			assertEquals(listOf("b", "d", "a", "c", "e"), letters.order)
			// They stay chosen, to be moved again.
			onNodeWithText("b lit").assertExists()
		}

	@Test
	fun shiftClickChoosesOrNot_KeptFromWhatIsUnderIt_AClickWithoutLetsGo() =
		runComposeUiTest {
			val letters = letters(Moving.Several)

			// A Shift-click on a button chooses its entry, and does not press it.
			shiftClick("button:b")
			onNodeWithText("b lit").assertExists()
			assertEquals(emptyList<String>(), letters.pressed)
			// Again, no longer chosen.
			shiftClick("row:b")
			onNodeWithText("b lit").assertDoesNotExist()

			shiftClick("row:b")
			shiftClick("row:c")
			// A click without Shift presses what it is on, and lets the choice go.
			onNodeWithTag("button:a").performClick()
			waitForIdle()
			assertEquals(listOf("a"), letters.pressed)
			onNodeWithText("b lit").assertDoesNotExist()
			onNodeWithText("c lit").assertDoesNotExist()
		}

	@Test
	fun anEntryNotChosenIsDraggedAlone_AndTheChoiceLetGo() =
		runComposeUiTest {
			val letters = letters(Moving.Several)

			shiftClick("row:b")
			shiftClick("row:c")
			drag("e", -4)
			assertEquals(listOf(Moved.of(4, 0)), letters.moves)
			onNodeWithText("b lit").assertDoesNotExist()
		}

	@Test
	fun whereEntriesAreMovedOneAtATime_ShiftChoosesNothing() =
		runComposeUiTest {
			val letters = letters(Moving.One)

			shiftClick("row:b")
			onNodeWithText("b lit").assertDoesNotExist()
			drag("b", 2)
			assertEquals(listOf(Moved.of(1, 3)), letters.moves)
			assertEquals(listOf("a", "c", "d", "b", "e"), letters.order)
		}
}
