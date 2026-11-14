package prism.launcher

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Path
import kotlin.io.path.createDirectories
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.BrowserTheme
import prism.browser.Chord
import prism.browser.Palette
import prism.browser.renderedPng
import prism.preferences.LauncherPreferences
import prism.preferences.MemoryPreferences
import prism.preferences.Preferences

/**
 * The launcher's settings, changed as a user changes them: each change is in the preferences as it is made, a key is
 * bound by pressing it, and one that types is refused.
 */
@OptIn(ExperimentalTestApi::class)
class LauncherSettingsTest {
	private fun setting(
		first: Preferences = Preferences.DEFAULT,
		check: ComposeUiTest.(MemoryPreferences, MutableList<String>) -> Unit,
	) = runComposeUiTest {
		val store = MemoryPreferences(first)
		val asked = ArrayList<String>()

		setContent {
			val preferences by store.preferences.collectAsState()

			BrowserTheme {
				LauncherSettingsView(preferences, Programs.all, store::update) { asked += "close" }
			}
		}
		waitForIdle()
		check(store, asked)
	}

	private val MemoryPreferences.launcher: LauncherPreferences
		get() = preferences.value.launcher

	private fun chord(text: String) = Chord.parse(text) ?: error("“$text” is not a chord")

	@Test
	fun eachChangeIsInThePreferencesAsItIsMade() =
		setting { store, asked ->
			onNodeWithTag("launcher-width:larger").performClick()
			onNodeWithTag("launcher-rows:smaller").performClick()
			onNodeWithTag("launcher-rows:smaller").performClick()
			onNodeWithTag("launcher-scale:larger").performClick()
			onNodeWithTag("launcher-files").performClick()
			onNodeWithTag("launcher-actions").performClick()
			onNodeWithTag("launcher-close-unfocused").performScrollTo().performClick()
			waitForIdle()

			assertEquals(
				LauncherPreferences(
					width = 800,
					rows = 7,
					scale = 1.1f,
					files = false,
					actions = false,
					closeUnfocused = false,
				),
				store.launcher,
			)

			// An application switched off is left out, and switched on again is back; found by what is typed.
			onNodeWithTag("launcher-applications-filter").performScrollTo()
			onNode(
				hasSetTextAction() and hasAnyAncestor(hasTestTag("launcher-applications-filter")),
			).performTextInput("fire")
			waitForIdle()
			assertEquals(
				0,
				onAllNodes(
					hasTestTag("launcher-application:foot.desktop"),
				).fetchSemanticsNodes().size,
			)
			onNodeWithTag("launcher-application:firefox.desktop").performScrollTo().performClick()
			waitForIdle()
			assertEquals(setOf("firefox.desktop"), store.launcher.hidden)
			onNodeWithTag("launcher-application:firefox.desktop").performScrollTo().performClick()
			waitForIdle()
			assertEquals(emptySet<String>(), store.launcher.hidden)

			onNodeWithTag("launcher-settings-close").performClick()
			waitForIdle()
			assertEquals(listOf("close"), asked)
		}

	@Test
	fun aNumberStopsAtWhatTheLauncherCanBeDrawnWith() =
		setting(
			Preferences(
				launcher = LauncherPreferences(rows = LauncherPreferences.ROWS.first, width = 99999),
			),
		) { store, _ ->
			onNodeWithTag("launcher-rows:smaller").performClick()
			onNodeWithTag("launcher-width:larger").performClick()
			waitForIdle()
			assertEquals(LauncherPreferences.ROWS.first, store.launcher.bounded.rows)
			assertEquals(LauncherPreferences.WIDTHS.last, store.launcher.bounded.width)
		}

	@Test
	fun aKeyIsBoundByPressingItAndOneThatTypesIsRefused() =
		setting { store, _ ->
			fun bindings() = LauncherBindings.of(store.preferences.value)

			// The settings' chord clicked, then Ctrl+G: that is its chord now.
			onNodeWithTag("launcher-chord:Settings:0").performScrollTo().performClick()
			waitForIdle()
			onNodeWithTag("launcher-settings").performKeyInput {
				withKeyDown(Key.CtrlLeft) { pressKey(Key.G) }
			}
			waitForIdle()
			assertEquals(listOf(chord("Ctrl+G")), bindings().of(LauncherAction.Settings))

			// A letter alone would type into the launcher's field: refused, and said, and still waited for.
			onNodeWithTag("launcher-chord:Settings:+").performScrollTo().performClick()
			waitForIdle()
			onNodeWithTag("launcher-settings").performKeyInput { pressKey(Key.K) }
			waitForIdle()
			onNodeWithTag("launcher-key-refused").assertExists()
			assertEquals(listOf(chord("Ctrl+G")), bindings().of(LauncherAction.Settings))
			// Escape leaves it as it was.
			onNodeWithTag("launcher-settings").performKeyInput { pressKey(Key.Escape) }
			waitForIdle()

			// A chord another action has is taken from it.
			onNodeWithTag("launcher-chord:Settings:+").performScrollTo().performClick()
			waitForIdle()
			onNodeWithTag("launcher-settings").performKeyInput {
				withKeyDown(Key.CtrlLeft) { pressKey(Key.N) }
			}
			waitForIdle()
			assertEquals(
				listOf(chord("Ctrl+G"), chord("Ctrl+N")),
				bindings().of(LauncherAction.Settings),
			)
			assertFalse(chord("Ctrl+N") in bindings().of(LauncherAction.Down))

			// A chord taken away, and the defaults put back.
			onNodeWithTag("launcher-chord:Settings:0:remove").performScrollTo().performClick()
			waitForIdle()
			assertEquals(listOf(chord("Ctrl+N")), bindings().of(LauncherAction.Settings))
			onNodeWithTag("launcher-chord-reset:Settings").performScrollTo().performClick()
			onNodeWithTag("launcher-chord-reset:Down").performScrollTo().performClick()
			waitForIdle()
			assertEquals(LauncherBindings.DEFAULT, bindings())
			assertTrue(
				store.preferences.value.bindings
					.isEmpty(),
			)
		}

	@Test
	fun theSettingsDraw() {
		for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
			val png =
				renderedPng(760, 1500, palette, scale = 2f) {
					LauncherSettingsView(Preferences.DEFAULT, Programs.all, {}) {}
				}

			assertTrue(png.size > 10_000, "${png.size} bytes")
			System.getProperty("launcher.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
				Path
					.of(dir)
					.also { it.createDirectories() }
					.resolve("launcher-settings-$tone.png")
					.toFile()
					.writeBytes(png)
			}
		}
	}
}
