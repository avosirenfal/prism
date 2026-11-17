package prism.browser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces
import prism.preferences.MemoryPreferences
import prism.preferences.Preferences
import prism.preferences.preferences

/**
 * A text search as a user meets it (docs/text.md): the explorer composed headless around a running [Session] over real
 * files, the search opened by its key, typed into, moved about by the keyboard and the pointer, and left; and a command
 * that goes on running, whose lines are shown as they come, stopped.
 */
@OptIn(ExperimentalTestApi::class)
class TextViewTest {
	@TempDir
	lateinit var work: Path

	@TempDir
	lateinit var bin: Path

	private fun project() {
		work.resolve("src").createDirectories()
		work.resolve("src/one.kt").writeText("class One\n\nfun one() = 1\n\nclass Uno\n")
		work.resolve("src/two.kt").writeText("// two\nclass Two\n")
		work.resolve("readme.md").writeText("no such word here\n")
	}

	/** An explorer running over the folder, with [preferences], and [check] to do to it once the folder is shown. */
	private fun exploring(
		preferences: Preferences = Preferences(),
		check: ComposeUiTest.(Session) -> Unit,
	) = runComposeUiTest {
		val running = arrayOfNulls<Session>(1)
		val store = MemoryPreferences(preferences)

		setContent {
			val scope = rememberCoroutineScope()

			val session =
				remember {
					Session(
						opened(
							Choosing.Exploring(),
							work,
							work,
							sidebarOf(XdgPlaces(), work, store.preferences.value),
						),
						// No editor is the environment's, whatever the machine the tests run on says.
						Services(
							JvmDisk(trash = Trash(bin)),
							preferences = store,
							home = work,
							environment = { null },
						),
						scope,
					)
				}

			running[0] = session
			BrowserTheme {
				CompositionLocalProvider(
					LocalBindings provides Bindings.of(preferences),
					LocalMotion provides false,
				) { SessionBrowser(session) }
			}
		}
		waitUntil(timeoutMillis = TIMEOUT) {
			running[0]?.state?.value?.let {
				it.listing is Listing.Read && it.actions.searches.isNotEmpty()
			} == true
		}
		waitForIdle()
		check(running[0]!!)
	}

	private fun ComposeUiTest.focusIsIn(tag: String) =
		onNode(
			isFocused() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
		).assertExists()

	private fun ComposeUiTest.press(key: Key) =
		onNode(isFocused()).performKeyInput {
			pressKey(key)
		}

	private fun ComposeUiTest.type(text: String) =
		onNode(
			hasSetTextAction() and hasAnyAncestor(hasTestTag("text-query")),
		).performTextInput(text)

	private fun ComposeUiTest.until(session: Session, holds: (Location.Text) -> Boolean) {
		waitUntil(timeoutMillis = TIMEOUT) {
			session.state.value.text
				?.let(holds) == true
		}
		waitForIdle()
	}

	@Test
	fun aSearchIsOpenedByItsKey_TypedInto_MovedAbout_AndLeftByEscape() {
		assumeTrue(
			listOf("rg", "xargs").all(SystemWorld(work, null)::installed),
			"needs rg and xargs",
		)
		project()

		exploring { session ->
			// From the listing, by its key: the field has the keyboard, and nothing is looked for yet.
			onNode(isFocused()).performKeyInput {
				withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.G) } }
			}
			until(session) { true }
			focusIsIn("text-query")
			onNodeWithTag("text-nothing").assertExists()
			assertEquals(
				null,
				session.state.value.text!!
					.running,
			)

			type("class")
			until(session) { it.running == null && it.rows.lines == 3 }

			// The first line found is under the cursor, and its file beside the list at that line.
			val first =
				session.state.value.text!!
					.cursor as TextRowId.Line

			until(session) { it.previewed?.ask?.path == first.path }
			onNodeWithTag("text-preview-name").assertExists()
			onNode(
				hasTestTag("text-preview-name") and hasText(first.path.fileName.toString()),
			).assertExists()

			// Down, from the field, moves the cursor and leaves the keyboard where it is.
			press(Key.DirectionDown)
			until(session) { it.cursor != first }
			focusIsIn("text-query")

			// A line pressed is under the cursor, and the list has the keyboard; its file pressed twice is folded.
			onNodeWithTag("text-line:two.kt:2").performMouseInput { click() }
			until(session) { it.cursor == TextRowId.Line(work.resolve("src/two.kt"), 0) }
			onNodeWithTag("text-line:two.kt:2").assertIsSelected()
			focusIsIn("text")
			onNodeWithTag("text-fold:two.kt").performMouseInput { click() }
			until(session) { work.resolve("src/two.kt") in it.folded }
			assertEquals(
				0,
				onAllNodes(hasTestTag("text-line:two.kt:2")).fetchSemanticsNodes().size,
			)
			press(Key.DirectionRight)
			until(session) { it.folded.isEmpty() }

			// Its menu, by the right button.
			onNodeWithTag("text-line:one.kt:5").performMouseInput {
				rightClick(
					Offset(
						40f,
						height / 2f,
					),
				)
			}
			waitForIdle()
			onNode(hasText("Open in editor")).assertExists()
			onNode(hasText("Copy line")).assertExists()
			// The menu has a focus of its own while it is open, and is what Escape closes.
			onNode(isFocused() and hasTestTag("menu")).performKeyInput { pressKey(Key.Escape) }
			waitForIdle()

			// Escape leaves it for the folder, whose listing has the keyboard again; Forward comes back to what was found.
			press(Key.Escape)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.text == null }
			waitForIdle()
			focusIsIn("listing")
			onNode(isFocused()).performKeyInput {
				withKeyDown(Key.AltLeft) { pressKey(Key.DirectionRight) }
			}
			until(session) { it.rows.lines == 3 && it.running == null }
		}
	}

	@Test
	fun aCommandsLinesAreShownAsTheyCome_AndItIsStoppedByItsButton() {
		assumeTrue(SystemWorld(work, null).installed("sh"), "needs sh")
		project()

		val slow =
			Json.parseToJsonElement(
				"""{"id": "slow", "label": "Slowly…", "key": "Ctrl+Shift+Y", "query": "narrows",
					"run": ["sh", "-c", "cat > /dev/null; echo readme.md:1:first; echo ${'$'}${'$'} > pid; sleep 600"]}""",
			)

		exploring(Preferences(textSearches = listOf(slow))) { session ->
			onNode(isFocused()).performKeyInput {
				withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Y) } }
			}
			// Its one line is shown while the command is still running.
			until(session) { it.rows.lines == 1 && it.running != null }
			onNodeWithTag("text-line:readme.md:1").assertExists()

			val pid =
				work
					.resolve("pid")
					.toFile()
					.readText()
					.trim()
					.toLong()

			assertTrue(ProcessHandle.of(pid).isPresent)
			// What stops it is there once it has run for a moment.
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("text-stop")).fetchSemanticsNodes().isNotEmpty()
			}
			onNodeWithTag("text-stop").performMouseInput { click() }
			until(session) { it.running == null }
			assertEquals(
				TextEnding.Stopped,
				session.state.value.text!!
					.results.ending,
			)
			assertEquals(
				1,
				session.state.value.text!!
					.rows.lines,
			)
			waitUntil(timeoutMillis = TIMEOUT) {
				ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)
			}
		}
	}

	private companion object {
		const val TIMEOUT = 10_000L
	}
}
