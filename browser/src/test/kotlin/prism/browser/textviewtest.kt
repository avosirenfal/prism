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
		try {
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.text
					?.let(holds) == true
			}
		} catch (late: androidx.compose.ui.test.ComposeTimeoutException) {
			// What it was, where it never came to what was waited for.
			throw AssertionError("still ${session.state.value.text}", late)
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

	/** How wide what is tagged [tag] is drawn, in the window's own units. */
	private fun ComposeUiTest.widthOf(tag: String): Float =
		onNodeWithTag(tag).fetchSemanticsNode().size.width / density.density

	private fun ComposeUiTest.searching(session: Session, query: String, lines: Int) {
		onNode(isFocused()).performKeyInput {
			withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.G) } }
		}
		until(session) { true }
		type(query)
		until(session) { it.running == null && it.rows.lines == lines && it.previewed != null }
	}

	@Test
	fun theLineBetweenTheListAndTheFileIsDragged_AndNeitherSideIsMadeTooNarrowToRead() {
		assumeTrue(
			listOf("rg", "xargs").all(SystemWorld(work, null)::installed),
			"needs rg and xargs",
		)
		project()

		exploring { session ->
			searching(session, "class", 3)

			val whole = widthOf("text")

			// Three tenths of the window, until it is dragged.
			assertEquals(whole * 0.3f, widthOf("text-list"), 1.5f)

			fun dragBy(across: Float) {
				onNodeWithTag("text-splitter").performMouseInput {
					moveTo(center)
					press()
					// In steps, as a hand moves it.
					repeat(4) { moveBy(Offset(across / 4f, 0f)) }
					release()
				}
				waitForIdle()
			}

			val before = widthOf("text-list")

			dragBy(180f)
			assertEquals(before + 180f / density.density, widthOf("text-list"), 2f)
			dragBy(-120f)
			assertEquals(before + 60f / density.density, widthOf("text-list"), 2f)
			// Dragged as far as it goes either way, each side keeps its least width.
			dragBy(-2000f)
			assertEquals(200f, widthOf("text-list"), 1.5f)
			dragBy(4000f)
			assertEquals(whole - 200f, widthOf("text-list"), 2.5f)
			// And it is taken hold of again from where it was left.
			dragBy(-100f)
			assertEquals(whole - 200f - 100f / density.density, widthOf("text-list"), 2.5f)
		}
	}

	@Test
	fun controlFLooksInTheFileShown_ApartFromWhatTheSearchLooksFor() {
		assumeTrue(
			listOf("rg", "xargs").all(SystemWorld(work, null)::installed),
			"needs rg and xargs",
		)
		project()

		exploring { session ->
			searching(session, "Uno", 1)

			fun ctrlF() =
				onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }

			ctrlF()
			until(session) { it.find != null }
			focusIsIn("text-find-field")
			onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("text-find-field")))
				.performTextInput("one")
			// "class One" and "fun one() = 1", without case: of the file, whatever the search looked for in it.
			until(session) { it.find?.text == "one" }
			// Neither is at or after the line the file is shown at, its last: the first is gone to.
			onNode(hasTestTag("text-find-count") and hasText("1 of 2")).assertExists()
			assertEquals(
				"Uno",
				session.state.value.text!!
					.query,
			)
			press(Key.Enter)
			until(session) { it.find!!.index(it.occurrences) == 1 }
			onNode(hasTestTag("text-find-count") and hasText("2 of 2")).assertExists()
			onNode(
				isFocused(),
			).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
			until(session) { it.find!!.index(it.occurrences) == 0 }
			onNodeWithTag("text-find-next").performMouseInput { click() }
			until(session) { it.find!!.index(it.occurrences) == 1 }

			// Ctrl+F again, from elsewhere, takes the keyboard back to it; Escape puts it away.
			onNodeWithTag("text-line:one.kt:5").performMouseInput { click() }
			waitForIdle()
			focusIsIn("text-list")
			ctrlF()
			waitForIdle()
			focusIsIn("text-find-field")
			press(Key.Escape)
			until(session) { it.find == null }
			assertEquals(0, onAllNodes(hasTestTag("text-find")).fetchSemanticsNodes().size)
			// The search is still shown: Escape put away what looked in the file, and no more.
			assertEquals(
				"Uno",
				session.state.value.text!!
					.query,
			)

			// Ctrl+L, which elsewhere is where a path is typed, takes the keyboard to what the search looks for.
			onNodeWithTag("text-line:one.kt:5").performMouseInput { click() }
			waitForIdle()
			onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.L) } }
			waitForIdle()
			focusIsIn("text-query")
		}
	}

	@Test
	fun theFilesAreNarrowedFromTheListsButton_AndALongLineIsScrolledAcrossUnderItsFile() {
		assumeTrue(
			listOf("rg", "xargs").all(SystemWorld(work, null)::installed),
			"needs rg and xargs",
		)
		project()
		work.resolve("src/wide.kt").writeText("class Wide // " + "and on ".repeat(60) + "\n")

		exploring { session ->
			searching(session, "class", 4)
			onNode(hasTestTag("text-count") and hasText("3 files, 4 lines")).assertExists()

			// Its button adds a step, which what is typed is then for; a query of the index's language narrows the files.
			onNodeWithTag("text-filter").performMouseInput { click() }
			until(session) { it.stages.size == 2 && it.step is TextStep.Names }
			focusIsIn("text-query")
			type("^w*.kt")
			until(session) { it.rows.files == 1 && !it.busy }
			onNode(hasTestTag("text-count") and hasText("1 file, 1 line")).assertExists()
			onNodeWithTag("text-file:wide.kt").assertExists()
			assertEquals(0, onAllNodes(hasTestTag("text-file:one.kt")).fetchSemanticsNodes().size)

			// The line is longer than the list is wide: the wheel, with Shift, scrolls it across, and its file's row
			// stays where it is.
			fun leftOf(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.x

			val line = leftOf("text-line:wide.kt:1")
			val file = leftOf("text-file:wide.kt")

			onNodeWithTag("text-rows").performMouseInput {
				moveTo(center)
				scroll(3f, androidx.compose.ui.test.ScrollWheel.Horizontal)
			}
			waitForIdle()
			mainClock.advanceTimeBy(1000)
			waitForIdle()
			assertTrue(leftOf("text-line:wide.kt:1") < line - 20f, "the line moved across")
			assertEquals(file, leftOf("text-file:wide.kt"), 1f)

			// The step's cross takes it out, and every file is listed again.
			onNodeWithTag("text-step-remove:1").performMouseInput { click() }
			until(session) { it.stages.size == 1 && it.rows.files == 3 }
		}
	}

	@Test
	fun stepsAreAddedChosenTurnedAboutAndTakenOut_AndTheFilesShownAsAListing() {
		assumeTrue(
			listOf("rg", "xargs").all(SystemWorld(work, null)::installed),
			"needs rg and xargs",
		)
		project()

		exploring { session ->
			searching(session, "class", 3)
			onNodeWithTag("text-step:0").assertIsSelected()
			// Its chip says what it is, what is typed for it, and how many files it gave.
			onNode(
				hasTestTag("text-step:0") and hasText("“class”") and hasText("2"),
			).assertExists()

			// The search's key, beside what it found, adds a step that looks in those files; what is typed is for it.
			onNode(isFocused()).performKeyInput {
				withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.G) } }
			}
			until(session) { it.stages.size == 2 }
			focusIsIn("text-query")
			onNodeWithTag("text-step:1").assertIsSelected()
			type("Uno")
			until(session) { !it.busy && it.rows.files == 1 && it.rows.lines == 1 }
			onNodeWithTag("text-file:one.kt").assertExists()

			// Turned about, it gives the file with "class" and no "Uno", with its line of "class".
			onNodeWithTag("text-without:Without").performMouseInput { click() }
			until(session) {
				!it.busy &&
					it.rows.paths.map { path -> path.fileName.toString() } == listOf("two.kt")
			}
			onNodeWithTag("text-line:two.kt:2").assertExists()

			// The first step's chip clicked, the field holds what it looks for, and typing there runs both again.
			onNodeWithTag("text-step:0").performMouseInput { click() }
			until(session) { it.at == 0 }
			focusIsIn("text-query")
			assertEquals(
				"class",
				session.state.value.text!!
					.query,
			)

			// A step added from the menu of steps; and taken out by its cross.
			onNodeWithTag("text-step-add").performMouseInput { click() }
			waitForIdle()
			onNode(hasText("Filter files by name…")).performMouseInput { click() }
			until(session) { it.stages.size == 3 && it.at == 2 }
			onNodeWithTag("text-step-remove:2").performMouseInput { click() }
			until(session) { it.stages.size == 2 && !it.busy }

			// The files it lists, shown as files: the window is no longer on the search, and Back is the search again.
			onNodeWithTag("text-as-files").performMouseInput { click() }
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.let { it.text == null && it.rows.size == 1 }
			}
			waitForIdle()
			onNodeWithTag("row:two.kt").assertExists()
			onNode(isFocused()).performKeyInput {
				withKeyDown(Key.AltLeft) { pressKey(Key.DirectionLeft) }
			}
			until(session) { it.stages.size == 2 && it.rows.files == 1 }
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
					.ending,
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
