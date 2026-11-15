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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Path
import kotlin.io.path.createDirectories
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.Action
import prism.browser.Bindable
import prism.browser.Bindings
import prism.browser.BrowserTheme
import prism.browser.Chord
import prism.browser.KeyGroups
import prism.browser.Palette
import prism.browser.renderedPng
import prism.desktop.Application
import prism.launcher.LauncherAction
import prism.launcher.launcherBindings
import prism.preferences.LauncherPreferences
import prism.preferences.LauncherRows
import prism.preferences.LauncherWidth
import prism.preferences.Preferences
import prism.preferences.Role
import prism.preferences.plus

/**
 * Keys, in the groups they belong to; a picker's own, apart from an explorer's once pickers are given theirs; and the
 * launcher's, rebound on the same page in the launcher's own window of settings, which is made as the preferences' is.
 */
@OptIn(ExperimentalTestApi::class)
class KeysTest {
	/** The place of the first of [action]'s chords as they come. */
	private fun first(action: Bindable) = Slot.Bound(action, action.defaults.first())

	private fun chord(text: String) = Chord.parse(text) ?: error(text)

	private fun application(id: String, name: String) =
		Application("$id.desktop", name, emptySet(), listOf(id), inTerminal = false)

	private val applications =
		listOf(
			application("firefox", "Firefox"),
			application("foot", "Foot"),
			application("gimp", "GIMP"),
		)

	@Test
	fun actionsAreListedInTheirGroupsEachGroupTogether() {
		val groups = Action.entries.map { it.group }

		// A group's actions follow one another: no group is listed twice.
		assertEquals(
			groups.distinct(),
			groups.zipWithNext().filter { (a, b) -> a != b }.map { it.second }.let {
				listOf(groups.first()) +
					it
			},
		)
		assertEquals(
			listOf(
				KeyGroups.Places,
				KeyGroups.Finding,
				KeyGroups.View,
				KeyGroups.Cursor,
				KeyGroups.Choosing,
				KeyGroups.Files,
				KeyGroups.History,
				KeyGroups.Windows,
			),
			groups.distinct(),
		)
		// Where an action acts is said beside it, where that is not everywhere.
		assertNull(Action.Back.where)
		assertEquals("in the file list", Action.Rename.where)
		// What is found by a group's name is that group's.
		assertTrue(Finding.Words("history").shows(Action.Redo, Action.Redo.defaults))
		assertFalse(Finding.Words("history").shows(Action.Back, Action.Back.defaults))
	}

	@Test
	fun aPickersKeysAreAnExplorersUntilPickersAreGivenTheirOwn() {
		val shared = Preferences(bindings = mapOf("Back" to listOf("Ctrl+B")))

		assertEquals(Bindings.of(shared), Bindings.of(shared, Role.Picker))

		val own = shared.copy(pickerBindings = mapOf("Up" to listOf("Ctrl+U")))
		val picker = Bindings.of(own, Role.Picker)

		// Its own: what an explorer's say of Back is nothing to it, and what it does not do, it has no key for.
		assertEquals(Action.Back.defaults, picker.of(Action.Back))
		assertEquals(listOf(chord("Ctrl+U")), picker.of(Action.Up))
		assertEquals(Action.PICKING, picker.actions)
		assertFalse(Action.NewWindow in picker.actions)
		assertEquals(listOf(chord("Ctrl+B")), Bindings.of(own).of(Action.Back))

		// In the window: switched on, they start as the explorer's are there; changed, they are written apart.
		val settings = Settings(shared, keys = KeyTab.Picker)

		assertNull(settings.rebound)
		assertFalse(settings.changed)

		val given = settings.pickersOwn(true)

		assertEquals(listOf(chord("Ctrl+B")), given.rebound!!.bindings.of(Action.Back))
		assertTrue(given.changed)
		assertEquals(mapOf("Back" to listOf("Ctrl+B")), given.written.pickerBindings)

		val rebound =
			given.rebound(
				given.rebound!!
					.awaiting(Slot.Bound(Action.Back, chord("Ctrl+B")))
					.pressed(chord("Ctrl+J")),
			)

		assertEquals(mapOf("Back" to listOf("Ctrl+J")), rebound.written.pickerBindings)
		assertEquals(mapOf("Back" to listOf("Ctrl+B")), rebound.written.bindings)
		// The explorer's tab rebinds the explorer's, and leaves a picker's own as they are.
		assertEquals(rebound.rebinding, rebound.copy(keys = KeyTab.Explorer).rebound)

		// Applied, they are what the window edits from; switched off again, pickers have an explorer's once more.
		val applied = rebound.applied(rebound.written)

		assertFalse(applied.changed)
		assertEquals(KeyTab.Picker, applied.keys)
		assertNull(applied.pickersOwn(false).written.pickerBindings)
		assertTrue(applied.pickersOwn(false).changed)
	}

	@Test
	fun bindingsThatTakeNothingTypedRefuseAKeyThatTypesAndGoOnWaiting() {
		val rebinding = Rebinding(launcherBindings(), takes = Taking.NothingTyped)
		val waiting = rebinding.awaiting(first(LauncherAction.Settings))

		for (typed in listOf("K", "Shift+K", "5", "Space", "Comma")) {
			val refused = waiting.pressed(chord(typed))

			assertEquals(chord(typed), refused.refused, typed)
			assertEquals(waiting.awaited, refused.awaited, typed)
			assertEquals(rebinding.bindings, refused.bindings, typed)
		}
		for (taken in listOf("Ctrl+K", "Alt+5", "F4", "Enter", "Down", "Tab", "Mouse Middle")) {
			val bound = waiting.pressed(chord(taken))

			assertNull(bound.refused, taken)
			assertNull(bound.awaited, taken)
			assertEquals(listOf(chord(taken)), bound.bindings.of(LauncherAction.Settings), taken)
		}

		// The key that shows a result's actions is bound again as any other is, and the right arrow, which types
		// nothing, is taken back for it.
		val shows = first(LauncherAction.ShowActions)
		val other = rebinding.awaiting(shows).pressed(chord("Ctrl+O"))

		assertEquals(listOf(chord("Ctrl+O")), other.bindings.of(LauncherAction.ShowActions))

		val back =
			other
				.awaiting(Slot.Bound(LauncherAction.ShowActions, chord("Ctrl+O")))
				.pressed(chord("Right"))

		assertNull(back.refused)
		assertEquals(listOf(chord("Right")), back.bindings.of(LauncherAction.ShowActions))
		// An explorer's take any: a letter acts from the listing, where nothing is typed.
		assertEquals(
			listOf(chord("K")),
			Rebinding(Bindings.DEFAULT)
				.awaiting(first(Action.Refresh))
				.pressed(chord("K"))
				.bindings
				.of(Action.Refresh)
				.take(1),
		)

		// How many are changed, and those alone found.
		val changed = waiting.pressed(chord("Ctrl+G"))

		assertEquals(1, changed.changed)
		assertTrue(
			Finding.Changed.shows(
				LauncherAction.Settings,
				changed.bindings.of(LauncherAction.Settings),
			),
		)
		assertFalse(
			Finding.Changed.shows(LauncherAction.Open, changed.bindings.of(LauncherAction.Open)),
		)
	}

	@Test
	fun theLaunchersSettingsAreWrittenOnlyOnceAppliedAndOnlyWhatWasChanged() {
		val base = Preferences(launcher = LauncherPreferences(rows = LauncherRows.of(12)))
		val opened = LauncherSettings(base)

		assertFalse(opened.changed)

		val edited =
			opened
				.set { copy(width = width + 40, files = false) }
				.let {
					it.copy(
						rebinding =
							it.rebinding
								.awaiting(first(LauncherAction.Reveal))
								.pressed(chord("Alt+Enter")),
					)
				}

		assertTrue(edited.changed)

		// Over the preferences as they are by then: what another window changed meanwhile is kept.
		val meanwhile = base.copy(bindings = mapOf("Back" to listOf("Ctrl+B")))
		val written = edited.over(meanwhile)

		assertEquals(mapOf("Back" to listOf("Ctrl+B")), written.bindings)
		assertEquals(
			LauncherPreferences(
				width = LauncherWidth.of(800),
				rows = LauncherRows.of(12),
				files = false,
				bindings = mapOf("Reveal" to listOf("Alt+Enter")),
			),
			written.launcher,
		)
		assertEquals(
			listOf(chord("Alt+Enter")),
			launcherBindings(written.launcher).of(LauncherAction.Reveal),
		)
		// Nothing changed writes nothing.
		assertEquals(meanwhile, opened.over(meanwhile))

		val applied = edited.copy(page = LauncherPage.Bindings).applied(written)

		assertFalse(applied.changed)
		assertEquals(LauncherPage.Bindings, applied.page)
		// A number stops at what the launcher can be drawn with.
		assertEquals(
			LauncherRows.range.last,
			opened
				.set { copy(rows = rows + 999) }
				.launcher.rows.number,
		)
	}

	@Test
	fun theBindingsPageListsGroupsNarrowsThemByWhatIsTypedAndShowsThoseChanged() =
		runComposeUiTest {
			var settings by mutableStateOf(Settings(Preferences.DEFAULT, page = Page.Bindings))

			setContent {
				BrowserTheme { SettingsView(settings, { settings = it }, onApply = {}, onClose = {}) }
			}
			waitForIdle()
			onNodeWithTag("key-group:Navigation").assertExists()
			onNodeWithTag("key-group:Search").assertExists()
			assertEquals(0, onAllNodesWithTag("bindings-changed").fetchSemanticsNodes().size)

			// Typed into the filter: the actions that fit, under their groups, and no group with none.
			onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("bindings-filter")))
				.performTextReplacement("search")
			waitForIdle()
			onNodeWithTag("key-group:Search").assertExists()
			onNodeWithTag("binding:SearchIndex").assertExists()
			assertEquals(
				0,
				onAllNodesWithTag("key-group:Navigation").fetchSemanticsNodes().size,
			)
			assertEquals(0, onAllNodesWithTag("binding:Back").fetchSemanticsNodes().size)

			// A group's name finds the group's.
			onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("bindings-filter")))
				.performTextReplacement("navig")
			waitForIdle()
			onNodeWithTag("binding:Back").assertExists()
			onNodeWithTag("binding:EditAddress").assertExists()
			assertEquals(0, onAllNodesWithTag("binding:SearchIndex").fetchSemanticsNodes().size)

			// One rebound is marked, counted, and found by the count's chip; reset, it is as it came.
			onNodeWithTag("chord:Alt+Left").performClick()
			onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
			waitForIdle()
			onNodeWithTag("binding-changed:Back").assertExists()
			onNodeWithTag("bindings-changed").performClick()
			waitForIdle()
			assertEquals(Finding.Changed, settings.rebinding.finding)
			onNodeWithTag("binding:Back").assertExists()
			assertEquals(0, onAllNodesWithTag("binding:EditAddress").fetchSemanticsNodes().size)
			onNodeWithTag("reset:Back").performClick()
			waitForIdle()
			assertEquals(Action.Back.defaults, settings.rebinding.bindings.of(Action.Back))
			onNodeWithText("No shortcuts have been changed.").assertExists()
		}

	@Test
	fun pickersAreGivenKeysOfTheirOwnOnTheirTab() =
		runComposeUiTest {
			var settings by mutableStateOf(Settings(Preferences.DEFAULT, page = Page.Bindings))
			var applied = Preferences.DEFAULT

			setContent {
				BrowserTheme {
					SettingsView(
						settings,
						{ settings = it },
						onApply = {
							applied = settings.over(applied)
							settings = settings.applied(applied)
						},
						onClose = {},
					)
				}
			}
			waitForIdle()
			onNodeWithText("Pickers").performClick()
			waitForIdle()
			// Until they have their own there is nothing to bind, and the page says whose keys a picker has.
			onNodeWithText("Pickers use the explorer's shortcuts").assertExists()
			assertEquals(0, onAllNodesWithTag("bindings-filter").fetchSemanticsNodes().size)

			onNodeWithTag("picker-keys-own").performClick()
			waitForIdle()
			onNodeWithTag("binding:Back").assertExists()
			// What a picker does not do is not there to be bound.
			assertEquals(0, onAllNodesWithTag("binding:NewWindow").fetchSemanticsNodes().size)

			onNodeWithTag("chord:Alt+Left").performClick()
			onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
			waitForIdle()
			onNodeWithText("Apply").assertIsEnabled().performClick()
			waitForIdle()

			assertEquals(mapOf("Back" to listOf("Ctrl+B", "Mouse Back")), applied.pickerBindings)
			assertEquals(emptyMap<String, List<String>>(), applied.bindings)
			onNodeWithText("Apply").assertIsNotEnabled()
			// The explorer's tab still has the explorer's.
			onNodeWithText("Explorer").performClick()
			waitForIdle()
			onNodeWithTag("chord:Alt+Left").assertExists()
		}

	@Test
	fun theLaunchersSettingsAreChangedAndItsKeysReboundInAWindowMadeAsThePreferencesIs() =
		runComposeUiTest {
			var settings by mutableStateOf(LauncherSettings(Preferences.DEFAULT))
			var applied = Preferences.DEFAULT
			val asked = ArrayList<String>()

			setContent {
				BrowserTheme {
					LauncherSettingsView(
						settings,
						applications,
						{ settings = it },
						onApply = {
							applied = settings.over(applied)
							settings = settings.applied(applied)
						},
						onClose = { asked += "close" },
					)
				}
			}
			waitForIdle()
			onNodeWithText("Apply").assertIsNotEnabled()
			onNodeWithTag("launcher-width:larger").performClick()
			onNodeWithTag("launcher-rows:smaller").performClick()
			onNodeWithTag("launcher-files").performClick()
			waitForIdle()
			// Nothing is written until it is applied.
			assertEquals(Preferences.DEFAULT, applied)
			onNodeWithText("Apply").assertIsEnabled()

			// Its keys, on the page an explorer's are rebound on: in their groups, and none that types.
			onNodeWithTag("page:Key bindings").performClick()
			waitForIdle()
			onNodeWithTag("key-group:Result").assertExists()
			onNodeWithTag("chord:Ctrl+Enter").performClick()
			onNode(isFocused()).performKeyInput { pressKey(Key.K) }
			waitForIdle()
			onNodeWithTag("bindings-refused").assertExists()
			onNode(
				isFocused(),
			).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
			waitForIdle()
			assertEquals(0, onAllNodesWithTag("bindings-refused").fetchSemanticsNodes().size)

			// An application switched off, found by its name.
			onNodeWithTag("page:Applications").performClick()
			waitForIdle()
			onNode(
				hasSetTextAction() and hasAnyAncestor(hasTestTag("launcher-applications-filter")),
			).performTextReplacement("fire")
			waitForIdle()
			assertEquals(
				0,
				onAllNodesWithTag("launcher-application:foot.desktop").fetchSemanticsNodes().size,
			)
			onNodeWithTag("launcher-application:firefox.desktop").performClick()
			waitForIdle()

			onNodeWithText("Apply").performClick()
			waitForIdle()
			assertEquals(
				LauncherPreferences(
					width = LauncherWidth.of(800),
					rows = LauncherRows.of(8),
					files = false,
					hidden = setOf("firefox.desktop"),
					bindings = mapOf("Reveal" to listOf("Alt+Enter")),
				),
				applied.launcher,
			)
			onNodeWithText("Apply").assertIsNotEnabled()
			onNodeWithText("Close").performClick()
			assertEquals(listOf("close"), asked)
			assertNotNull(settings)
		}

	/** Drawn headless, in both palettes: with `-Pexplorer.screenshots=DIR`, kept there. */
	@Test
	fun theKeysPagesAndTheLaunchersSettingsDraw() {
		val changed =
			Settings(
				Preferences(
					bindings =
						mapOf(
							"Back" to listOf("Ctrl+B", "Mouse Back"),
							"NewFile" to listOf("Ctrl+Alt+N"),
						),
				),
				page = Page.Bindings,
			)

		val drawn: Map<String, @androidx.compose.runtime.Composable () -> Unit> =
			mapOf(
				"keys-explorer" to { SettingsView(changed, {}, onApply = {}, onClose = {}) },
				"keys-pickers" to {
					SettingsView(changed.copy(keys = KeyTab.Picker).pickersOwn(true), {
					}, onApply = {}, onClose = {})
				},
				"keys-pickers-shared" to {
					SettingsView(changed.copy(keys = KeyTab.Picker), {}, onApply = {}, onClose = {})
				},
				"launcher-settings" to {
					LauncherSettingsView(LauncherSettings(Preferences.DEFAULT), applications, {}, {}, {})
				},
				"launcher-keys" to {
					LauncherSettingsView(
						LauncherSettings(Preferences.DEFAULT, page = LauncherPage.Bindings),
						applications,
						{},
						{},
						{},
					)
				},
				"launcher-applications" to {
					LauncherSettingsView(
						LauncherSettings(
							Preferences(launcher = LauncherPreferences(hidden = setOf("foot.desktop"))),
							page = LauncherPage.Applications,
						),
						applications,
						{},
						{},
						{},
					)
				},
			)

		for ((name, content) in drawn)
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png = renderedPng(980, 760, palette, scale = 2f) { content() }

				assertTrue(png.size > 10_000, "$name: ${png.size} bytes")
				System.getProperty("explorer.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
					Path
						.of(dir)
						.also { it.createDirectories() }
						.resolve("$name-$tone.png")
						.toFile()
						.writeBytes(png)
				}
			}
	}
}
