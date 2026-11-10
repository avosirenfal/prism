package prism.explorer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import prism.browser.Action
import prism.browser.BrowserTheme
import prism.browser.Chord
import prism.browser.MouseButton
import prism.browser.Trigger
import prism.desktop.Place
import prism.preferences.Preferences

/**
 * The bindings window as a player rebinds a game's keys: a binding clicked waits, and the next chord pressed, a key with
 * its modifiers or a mouse button, is it; Escape stops waiting; and Save is there only once something is changed.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsViewTest {
	private fun chord(text: String) = Chord.parse(text) ?: error(text)

	@Test
	fun aBindingClickedTakesTheNextChordPressed() =
		runComposeUiTest {
			var settings by mutableStateOf(
				Settings(
					Preferences.DEFAULT,
					listOf(Place("Home", Place.Kind.Home, Path.of("/home/avery"))),
					page = Page.Bindings,
				),
			)

			val mouse = MutableSharedFlow<Chord>(extraBufferCapacity = 1)

			setContent {
				BrowserTheme {
					SettingsView(
						settings,
						{ settings = it },
						onSave = {},
						onCancel = {},
						mouseChords = mouse,
					)
				}
			}
			onNodeWithText("Save").assertIsNotEnabled()

			// Search everything: clicked, waiting, then Ctrl+K.
			onNodeWithTag("chord:Ctrl+Shift+F").performClick()
			onNodeWithTag("chord:awaited").assertExists()
			onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
			waitForIdle()

			assertEquals(
				listOf(chord("Ctrl+K")),
				settings.rebinding.bindings.of(Action.SearchIndex),
			)
			onNodeWithTag("chord:Ctrl+K").assertExists()
			onNodeWithText("Save").assertIsEnabled()

			// Back: its first chord clicked, then Escape, which changes nothing.
			onNodeWithTag("chord:Alt+Left").performClick()
			onNode(isFocused()).performKeyInput { pressKey(Key.Escape) }
			waitForIdle()

			assertEquals(Action.Back.defaults, settings.rebinding.bindings.of(Action.Back))

			// Back given another chord, from the mouse's middle button.
			onAllNodesWithTag("chord:+")[0].performClick()
			waitForIdle()
			mouse.tryEmit(Chord(Trigger.Mouse(MouseButton.Middle)))
			waitForIdle()

			assertEquals(
				Action.Back.defaults + chord("Mouse Middle"),
				settings.rebinding.bindings.of(Action.Back),
			)

			// And a chord removed by its ×.
			onNodeWithTag("remove:Alt+Left").performClick()
			waitForIdle()

			assertEquals(
				listOf(chord("Mouse Back"), chord("Mouse Middle")),
				settings.rebinding.bindings.of(Action.Back),
			)
			assertEquals(
				mapOf(
					"Back" to listOf("Mouse Back", "Mouse Middle"),
					"SearchIndex" to listOf("Ctrl+K"),
				),
				settings.over(Preferences.DEFAULT).bindings,
			)
		}
}
