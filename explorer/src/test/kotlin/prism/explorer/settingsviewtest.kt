package prism.explorer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.Action
import prism.browser.BrowserTheme
import prism.browser.Chord
import prism.browser.MouseButton
import prism.browser.Trigger
import prism.desktop.TerminalOption
import prism.desktop.TerminalOptions
import prism.preferences.Align
import prism.preferences.ColumnId
import prism.preferences.Layout
import prism.preferences.ListingKind
import prism.preferences.Preferences
import prism.preferences.Role

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
				Settings(Preferences.DEFAULT, page = Page.Bindings),
			)

			val mouse = MutableSharedFlow<Chord>(extraBufferCapacity = 1)

			setContent {
				BrowserTheme {
					SettingsView(
						settings,
						{ settings = it },
						onApply = {},
						onClose = {},
						mouseChords = mouse,
					)
				}
			}
			onNodeWithText("Apply").assertIsNotEnabled()

			// Showing hidden files: its chord clicked, waiting, then Ctrl+K.
			onNodeWithTag("chord:Ctrl+H").performClick()
			onNodeWithTag("chord:awaited").assertExists()
			onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
			waitForIdle()

			assertEquals(
				listOf(chord("Ctrl+K")),
				settings.rebinding.bindings.of(Action.ToggleHidden),
			)
			onNodeWithTag("chord:Ctrl+K").assertExists()
			onNodeWithText("Apply").assertIsEnabled()

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
					"ToggleHidden" to listOf("Ctrl+K"),
				),
				settings.over(Preferences.DEFAULT).bindings,
			)
		}

	@Test
	fun theBindingsAreFoundByTheirWordsOrByAKeyPressed_AndTheListsBarShowsItScrolls() =
		runComposeUiTest {
			var settings by mutableStateOf(Settings(Preferences.DEFAULT, page = Page.Bindings))

			setContent {
				BrowserTheme {
					SettingsView(settings, { settings = it }, onApply = {}, onClose = {})
				}
			}
			onNodeWithTag("bindings-scrollbar").assertExists()

			// Ctrl+F, from wherever the keyboard is, goes to the filter; words typed there leave the rest out.
			onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
			waitForIdle()
			onNode(
				isFocused() and
					(hasTestTag("bindings-filter") or hasAnyAncestor(hasTestTag("bindings-filter"))),
			).assertExists()
			onNode(isFocused()).performTextReplacement("terminal")
			waitForIdle()
			onNodeWithText(Action.OpenTerminal.label).assertExists()
			onNodeWithText(Action.Back.label).assertDoesNotExist()

			// The window that finds a shortcut takes a key pressed while it is open, shows what it does, and binds nothing.
			onNodeWithTag("find-by-key").performClick()
			waitForIdle()
			onNodeWithTag("find-shortcut").assertExists()
			onNodeWithTag("shortcut-field").performKeyInput {
				withKeyDown(Key.CtrlLeft) { pressKey(Key.H) }
			}
			waitForIdle()

			assertEquals(Hearing.Seeking(chord("Ctrl+H")), settings.rebinding.hearing)
			assertEquals(Finding.Shortcut(chord("Ctrl+H")), settings.rebinding.finding)
			onNodeWithText(Action.ToggleHidden.label).assertExists()
			onNodeWithText(Action.OpenTerminal.label).assertDoesNotExist()
			assertEquals(Preferences.DEFAULT.bindings, settings.over(Preferences.DEFAULT).bindings)

			// Closed, it takes no more keys; what it found is still what is shown, until the button opens it afresh.
			onNodeWithTag("shortcut-field").performKeyInput { pressKey(Key.Escape) }
			waitForIdle()
			onNodeWithTag("find-shortcut").assertDoesNotExist()
			assertEquals(
				Hearing.Idle(Finding.Shortcut(chord("Ctrl+H"))),
				settings.rebinding.hearing,
			)
			assertEquals(false, settings.rebinding.listening)
			onNodeWithTag("find-by-key").performClick()
			waitForIdle()
			onNodeWithText(Action.Back.label).assertExists()
		}

	@Test
	fun aListingIsFoundByName_AGallerysFiltersApartFromAListsAndAPickersFromTheExplorers() =
		runComposeUiTest {
			val zip = ListingKind.Place("archive.zip")
			var settings by mutableStateOf(Settings(Preferences.DEFAULT, page = Page.Layout))

			setContent {
				BrowserTheme {
					SettingsView(settings, { settings = it }, onApply = {}, onClose = {})
				}
			}

			// Every kind of listing is listed down the side; what is typed over them narrows them to those it names.
			onNodeWithTag("listing:Folders").assertExists()
			onNode(
				hasSetTextAction() and hasAnyAncestor(hasTestTag("listing-filter")),
			).performTextReplacement("zip")
			waitForIdle()
			onNodeWithTag("listing:Folders").assertDoesNotExist()
			onNodeWithTag("listing:Archive (zip)").performClick()
			waitForIdle()
			assertEquals(zip, settings.looking.kind)

			// A gallery's hidden files left out, and a list's still shown: the filters are wheeled to, under the columns.
			onNodeWithTag("Window:Explorer").performMouseInput {
				moveTo(center)
				scroll(40f)
			}
			waitForIdle()
			onNodeWithTag("View:Gallery").performClick()
			waitForIdle()
			onNodeWithTag("hidden-files").performClick()
			waitForIdle()
			assertFalse(
				settings.listings
					.filters(Role.Explorer, zip, Layout.Gallery)
					.hidden.files,
			)
			assertTrue(
				settings.listings
					.filters(Role.Explorer, zip, Layout.List)
					.hidden.files,
			)

			// A column aligned, from its row, wheeled back to.
			onNodeWithTag("hidden-files").performMouseInput {
				moveTo(center)
				scroll(-40f)
			}
			waitForIdle()
			onNodeWithTag("align:Size:Centre").performClick()
			waitForIdle()
			assertEquals(
				Align.Center,
				settings.listings
					.look(Role.Explorer, zip)
					.columns(zip)
					.settings
					.single { it.id == ColumnId.Size }
					.aligned,
			)

			// A picker's are the explorer's, until it is given its own.
			onNodeWithTag("Window:File picker").performClick()
			waitForIdle()
			assertFalse(settings.listings.ownLook(Role.Picker, zip))
			onNodeWithTag("own").performClick()
			waitForIdle()
			assertTrue(settings.listings.ownLook(Role.Picker, zip))
			onNodeWithText("Apply").assertIsEnabled()
		}

	@Test
	fun aTerminalIsFoundByTypingItsName_OneNotInstalledSaidSo_AndCustomShowsItsLines() =
		runComposeUiTest {
			var settings by mutableStateOf(Settings(Preferences.DEFAULT, page = Page.Terminal))

			val terminals =
				TerminalOptions(
					"Ghostty",
					listOf(
						TerminalOption("Ghostty", "ghostty", installed = true),
						TerminalOption("foot", "foot", installed = true),
						TerminalOption("kitty", "kitty", installed = false),
					),
				)

			setContent {
				BrowserTheme {
					SettingsView(settings, {
						settings = it
					}, onApply = {}, onClose = {}, terminals = terminals)
				}
			}

			// The box shows what is chosen; clicked, every terminal, those not installed among them.
			onNodeWithText("Automatic: Ghostty").assertExists()
			onNodeWithTag("terminal").performClick()
			waitForIdle()
			onNodeWithTag("option:foot").assertExists()
			onNodeWithTag("option:kitty").assertExists()
			onNodeWithTag("option:Custom").assertExists()

			// Typed into, only those whose names have what is typed; Enter chooses the first.
			onNode(
				hasSetTextAction() and hasAnyAncestor(hasTestTag("terminal")),
			).performTextReplacement("kit")
			waitForIdle()
			onNodeWithTag("option:foot").assertDoesNotExist()
			onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("terminal"))).performKeyInput {
				pressKey(Key.Enter)
			}
			waitForIdle()
			assertEquals(TerminalPick.Named("kitty"), settings.terminal.pick)
			// Not installed: said under the box.
			onNodeWithTag("terminal-missing").assertExists()
			onNodeWithTag("terminal-open").assertDoesNotExist()

			// Custom: its lines are shown, and what they would run.
			onNodeWithTag("terminal").performClick()
			waitForIdle()
			onNodeWithTag("option:Custom").performClick()
			waitForIdle()
			assertEquals(TerminalPick.Custom, settings.terminal.pick)
			onNodeWithTag("terminal-missing").assertDoesNotExist()
			onNodeWithTag("terminal-open").assertExists()
			onNodeWithText("What runs").assertExists()
		}
}
