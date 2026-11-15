package prism.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import prism.preferences.Moved
import prism.preferences.moved

@OptIn(ExperimentalTestApi::class)
class ReorderTest {
	/**
	 * Five letters in a list [moving] as it says, kept in order as moved: each row its grip, its letter ("lit" after it
	 * where it is), room, and at its end a button that counts its presses.
	 */
	private fun ComposeUiTest.letters(moving: Moving): Letters {
		val letters = Letters()

		setContent {
			BrowserTheme {
				Box(Modifier.width(300.dp).focusable().testTag("list")) {
					ReorderableColumn(
						letters.order,
						{ it },
						moving,
						onMove = letters::move,
					) { letter, grip ->
						Row(
							Modifier
								.fillMaxWidth()
								.then(grip.entry)
								.height(40.dp)
								.testTag("row:$letter"),
						) {
							Box(Modifier.width(40.dp).height(40.dp).testTag("grip:$letter"))
							Text(
								if (grip.lit)
									"$letter lit"
								else
									letter,
							)
							Spacer(Modifier.weight(1f))
							Box(
								Modifier
									.width(40.dp)
									.height(40.dp)
									.testTag("button:$letter")
									.clickable { letters.pressed += letter },
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

	/** Where a row is nothing but room: its middle, between its letter and its button. */
	private val room = Offset(150f, 20f)

	private fun ComposeUiTest.click(tag: String, at: Offset = room) {
		onNodeWithTag(tag).performMouseInput { click(at) }
		waitForIdle()
	}

	private fun ComposeUiTest.shiftClick(tag: String, at: Offset = room) {
		onNodeWithTag("list").requestFocus()
		onNodeWithTag("list").performKeyInput { keyDown(Key.ShiftLeft) }
		onNodeWithTag(tag).performMouseInput { click(at) }
		onNodeWithTag("list").performKeyInput { keyUp(Key.ShiftLeft) }
		waitForIdle()
	}

	/** [letter]'s row taken in its room and dragged [rows] rows down (up, where negative), a quarter of a row at a time. */
	private fun ComposeUiTest.drag(
		letter: String,
		rows: Int,
		tag: String = "row:$letter",
		at: Offset = room,
	) {
		val row =
			onNodeWithTag("row:$letter")
				.fetchSemanticsNode()
				.size.height
				.toFloat()
		val step = Offset(0f, row * 0.25f * sign(rows.toFloat()))

		onNodeWithTag(tag).performTouchInput {
			down(at)
			repeat(abs(rows) * 4) { moveBy(step) }
			up()
		}
		waitForIdle()
	}

	@Test
	fun aClickChoosesARowAlone_ShiftAndAClickAddOrTakeOne() =
		runComposeUiTest {
			letters(Moving.Several)

			click("row:b")
			onNodeWithText("b lit").assertExists()
			click("row:c")
			onNodeWithText("c lit").assertExists()
			onNodeWithText("b lit").assertDoesNotExist()
			shiftClick("row:d")
			onNodeWithText("c lit").assertExists()
			onNodeWithText("d lit").assertExists()
			shiftClick("row:c")
			onNodeWithText("c lit").assertDoesNotExist()
			onNodeWithText("d lit").assertExists()
		}

	@Test
	fun whatIsARowsOwnToPressIsPressed_TheRowNeitherChosenNorDragged() =
		runComposeUiTest {
			val letters = letters(Moving.Several)
			val button = Offset(280f, 20f)

			click("row:b", button)
			shiftClick("row:c", button)
			assertEquals(listOf("b", "c"), letters.pressed)
			onNodeWithText("b lit").assertDoesNotExist()
			onNodeWithText("c lit").assertDoesNotExist()
			// Dragged from its button, a row stays where it is.
			drag("b", 2, at = button)
			assertEquals(emptyList<Moved>(), letters.moves)
		}

	@Test
	fun severalChosenAreDraggedTogether_GatheredInTheirOrder_FromAnywhereOnOne() =
		runComposeUiTest {
			val letters = letters(Moving.Several)

			click("row:b")
			shiftClick("row:d")

			// d dragged up three rows by its room: b, chosen too, is gathered above it, and the two go first, in order.
			drag("d", -3)
			assertEquals(listOf(Moved(listOf(1, 3), 0)), letters.moves)
			assertEquals(listOf("b", "d", "a", "c", "e"), letters.order)
			// They stay chosen, to be moved again; dragged by its grip, the same.
			onNodeWithText("b lit").assertExists()
			drag("b", 2, tag = "grip:b", at = Offset(20f, 20f))
			assertEquals(listOf("a", "c", "b", "d", "e"), letters.order)
		}

	@Test
	fun aRowNotChosen_DraggedGoesAlone_AndIsChosenAlone() =
		runComposeUiTest {
			val letters = letters(Moving.Several)

			click("row:b")
			shiftClick("row:c")
			drag("e", -4)
			assertEquals(listOf(Moved.of(4, 0)), letters.moves)
			onNodeWithText("e lit").assertExists()
			onNodeWithText("b lit").assertDoesNotExist()
		}

	@Test
	fun whereRowsAreMovedOneAtATime_NoneIsChosen() =
		runComposeUiTest {
			val letters = letters(Moving.One)

			click("row:b")
			shiftClick("row:c")
			onNodeWithText("b lit").assertDoesNotExist()
			onNodeWithText("c lit").assertDoesNotExist()
			drag("b", 2)
			assertEquals(listOf(Moved.of(1, 3)), letters.moves)
			assertEquals(listOf("a", "c", "d", "b", "e"), letters.order)
		}
}
