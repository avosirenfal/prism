package barion.picker

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The picker as a user meets it: composed headless around a running [Session] over a real folder, then clicked,
 * double-clicked and typed at through Compose's own test input, and what it shows and how it ends checked.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class InteractionTest {
	@TempDir
	lateinit var work: Path

	/** A small home: two folders, one with a folder of its own, and a few files. */
	private fun home(): Path {
		work.resolve("Documents/Taxes").createDirectories()
		work.resolve("Music").createDirectories()
		work.resolve("Documents/letter.odt").writeText("Dear")
		work.resolve("main.kt").writeText("fun main() {}")
		work.resolve("notes.md").writeText("# notes")
		work.resolve("todo.txt").writeText("everything")

		return work
	}

	/** The picker running over [folder] and choosing [choosing], with [check] to do to it once its folder is shown. */
	private fun picking(
		choosing: Choosing = Choosing.OneFile(),
		folder: Path = home(),
		check: ComposeUiTest.(Session) -> Unit,
	) = runComposeUiTest {
		val running = arrayOfNulls<Session>(1)

		setContent {
			val scope = rememberCoroutineScope()

			val session =
				remember {
					Session(
						opened(choosing, folder, work, sidebarOf(work)),
						JvmDisk(),
						scope,
					)
				}

			running[0] = session
			PickerTheme { SessionPicker(session) }
		}
		waitUntil(timeoutMillis = TIMEOUT) { running[0]?.state?.value?.listing is Listing.Read }
		waitForIdle()
		check(running[0]!!)
	}

	private fun ComposeUiTest.row(name: String): SemanticsNodeInteraction =
		onNodeWithTag("row:$name")

	/** No row of any of [names] is shown. */
	private fun ComposeUiTest.absent(vararg names: String) =
		names.forEach {
			assertEquals(0, onAllNodes(hasTestTag("row:$it")).fetchSemanticsNodes().size, it)
		}

	/** The small home, with a real picture, a video and a hidden file besides. */
	private fun media(): Path =
		home().also {
			ThumbnailTest.picture(it, "photo.png", 400, 300)
			it.resolve("clip.mp4").writeText("not really")
			it.resolve(".secret").writeText("")
		}

	private fun ComposeUiTest.focused(): SemanticsNodeInteraction = onNode(isFocused())

	/** The keyboard is on the control tagged [tag], or on the part of it that takes the keyboard. */
	private fun ComposeUiTest.focusIsIn(tag: String) =
		onNode(
			isFocused() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
		).assertExists()

	/** The text field inside the field tagged [tag]. */
	private fun ComposeUiTest.field(tag: String): SemanticsNodeInteraction =
		onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))

	private fun ComposeUiTest.press(key: Key) = focused().performKeyInput { pressKey(key) }

	private fun ComposeUiTest.shows(folder: Path, row: String) {
		waitUntil(timeoutMillis = TIMEOUT) {
			onAllNodes(hasTestTag("row:$row")).fetchSemanticsNodes().isNotEmpty()
		}
		onNodeWithTag(
			"crumb:${if (folder == work) "Home" else folder.fileName}",
		).assertIsDisplayed()
	}

	private fun ComposeUiTest.ended(session: Session): Outcome {
		waitUntil(timeoutMillis = TIMEOUT) { session.outcome.isCompleted }

		return session.outcome.getCompleted()
	}

	@Test
	fun aClickChoosesARowAndNamesIt() =
		picking { session ->
			row("notes.md").performClick()
			row("notes.md").assertIsSelected()
			field("name").assertTextEquals("notes.md")

			row("todo.txt").performClick()
			row("notes.md").assertIsNotSelected()
			field("name").assertTextEquals("todo.txt")

			onNodeWithTag("accept").performClick()
			assertEquals(Outcome.Chosen(listOf(work.resolve("todo.txt"))), ended(session))
		}

	@Test
	fun aDoubleClickOpensAFolderAndTheToolbarGoesBack() =
		picking {
			row("Documents").performMouseInput { doubleClick() }
			shows(work.resolve("Documents"), "letter.odt")

			onNodeWithTag("back").performClick()
			shows(work, "notes.md")

			onNodeWithTag("forward").performClick()
			shows(work.resolve("Documents"), "Taxes")

			onNodeWithTag("up").performClick()
			shows(work, "notes.md")
			// Up lands on the folder it came from.
			waitUntil(timeoutMillis = TIMEOUT) {
				it.state.value.cursor
					?.at == RowId("Documents")
			}
		}

	@Test
	fun theKeyboardBrowsesAndChooses() =
		picking { session ->
			// The keyboard starts at the listing.
			onNodeWithTag("listing").assertIsFocused()

			press(Key.DirectionDown)
			row("Documents").assertIsSelected()

			press(Key.Enter)
			shows(work.resolve("Documents"), "letter.odt")

			press(Key.Backspace)
			shows(work, "notes.md")
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.cursor
					?.at ==
					RowId("Documents")
			}

			// Letters go to the row they start.
			press(Key.T)
			row("todo.txt").assertIsSelected()

			press(Key.Enter)
			assertEquals(Outcome.Chosen(listOf(work.resolve("todo.txt"))), ended(session))
		}

	@Test
	fun ctrlAndShiftChooseSeveral() =
		picking(Choosing.Files()) { session ->
			row("main.kt").performClick()
			onNodeWithTag("listing").performKeyInput { keyDown(Key.CtrlLeft) }
			row("todo.txt").performMouseInput { click() }
			onNodeWithTag("listing").performKeyInput { keyUp(Key.CtrlLeft) }

			row("main.kt").assertIsSelected()
			row("notes.md").assertIsNotSelected()
			row("todo.txt").assertIsSelected()

			onNodeWithTag("listing").performKeyInput { keyDown(Key.ShiftLeft) }
			row("main.kt").performMouseInput { click() }
			onNodeWithTag("listing").performKeyInput { keyUp(Key.ShiftLeft) }

			// Shift chooses the run from the last row clicked without it.
			row("main.kt").assertIsSelected()
			row("notes.md").assertIsSelected()
			row("todo.txt").assertIsSelected()
			field("name").assertTextEquals("\"main.kt\" \"notes.md\" \"todo.txt\"")

			onNodeWithTag("accept").performClick()
			assertEquals(
				Outcome.Chosen(listOf("main.kt", "notes.md", "todo.txt").map(work::resolve)),
				ended(session),
			)
		}

	@Test
	fun tabGoesThroughThePickerInOrder() =
		picking {
			focusIsIn("listing")
			press(Key.Tab)
			focusIsIn("name")
			// Open cannot be pressed with nothing chosen, so it is passed over; with one filter of the application's
			// own (all files), there is no choice of them to show.
			press(Key.Tab)
			focusIsIn("cancel")
			focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
			focusIsIn("name")
			// Round from the end to the start: the path, the search, the places, the filters, then the listing.
			press(Key.Tab)
			press(Key.Tab)
			focusIsIn("crumbs")
			press(Key.Tab)
			focusIsIn("search")
			press(Key.Tab)
			focusIsIn("places")
			press(Key.Tab)
			focusIsIn("kinds")
			press(Key.Tab)
			focusIsIn("hidden")
			press(Key.Tab)
			focusIsIn("listing")
		}

	@Test
	fun savingStartsAtTheNameAndSavesWhatIsTyped() =
		picking(Choosing.SaveAs("draft.txt")) { session ->
			field("name").assertIsFocused()
			field("name").performTextReplacement("report.txt")
			press(Key.Enter)

			assertEquals(Outcome.Chosen(listOf(work.resolve("report.txt"))), ended(session))
		}

	@Test
	fun savingOverAFileAsksFirst() =
		picking(Choosing.SaveAs("draft.txt")) { session ->
			row("notes.md").performClick()
			field("name").assertTextEquals("notes.md")
			onNodeWithTag("accept").performClick()

			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("notice")).fetchSemanticsNodes().isNotEmpty()
			}
			onNodeWithTag("yes").assertIsFocused()
			onNodeWithTag("yes").performClick()

			assertEquals(Outcome.Chosen(listOf(work.resolve("notes.md"))), ended(session))
		}

	@Test
	fun aPathTypedAcrossTheTopIsGoneTo() =
		picking {
			focused().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.L) } }
			field("address").assertIsFocused()
			field("address").assertTextEquals(work.toString())

			field("address").performTextReplacement(work.resolve("Documents/Taxes").toString())
			press(Key.Enter)

			waitUntil(timeoutMillis = TIMEOUT) {
				it.state.value.folder ==
					work.resolve("Documents/Taxes")
			}
			onNodeWithTag("crumb:Taxes").assertIsDisplayed()
			// The keyboard goes back to the listing.
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("listing") and isFocused()).fetchSemanticsNodes().isNotEmpty()
			}

			// Escape leaves a path being typed, and takes the keyboard back to the listing too.
			focused().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.L) } }
			field("address").assertIsFocused()
			press(Key.Escape)
			focusIsIn("listing")
			onNodeWithTag("crumbs").assertIsDisplayed()
		}

	@Test
	fun theSearchNarrowsTheListing() =
		picking {
			focused().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
			field("search").assertIsFocused()
			field("search").performTextReplacement("no")

			row("notes.md").assertIsDisplayed()
			assertEquals(0, onAllNodes(hasTestTag("row:todo.txt")).fetchSemanticsNodes().size)
		}

	@Test
	fun aDoubleClickIsTwoPlainPressesOfOneRowWithNothingBetween() =
		picking(Choosing.Files()) { session ->
			// Pressed, another row pressed, then the first pressed again: three clicks, no double click.
			row("main.kt").performClick()
			row("todo.txt").performClick()
			row("main.kt").performClick()
			row("main.kt").assertIsSelected()
			assertEquals(false, session.outcome.isCompleted)

			// Pressed, then pressed again with Ctrl: a click taking it away, not a double click.
			onNodeWithTag("listing").performKeyInput { keyDown(Key.CtrlLeft) }
			row("main.kt").performMouseInput { click() }
			onNodeWithTag("listing").performKeyInput { keyUp(Key.CtrlLeft) }
			row("main.kt").assertIsNotSelected()
			assertEquals(false, session.outcome.isCompleted)

			// Two plain presses of one row: opened.
			row("notes.md").performMouseInput { doubleClick() }
			assertEquals(Outcome.Chosen(listOf(work.resolve("notes.md"))), ended(session))
		}

	@Test
	fun thePlacesAreOneStopWhoseArrowsMoveAndEnterOpens() =
		picking {
			// Back from the listing: past Show hidden and the kinds to the places.
			repeat(3) {
				focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
			}
			focusIsIn("places")
			// The arrows start at the place shown (Home); the next one down is Documents.
			press(Key.DirectionDown)
			press(Key.Enter)
			shows(work.resolve("Documents"), "letter.odt")
		}

	@Test
	fun escapeEmptiesTheSearchBeforeItCancels() =
		picking { session ->
			focused().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
			field("search").performTextReplacement("no")
			press(Key.Escape)
			field("search").assertTextEquals("")
			assertEquals(false, session.outcome.isCompleted)

			press(Key.Escape)
			assertEquals(Outcome.Cancelled, ended(session))
		}

	@Test
	fun goingUpLandsOnTheFolderLeftAndScrollsOnlyAsFarAsIt() =
		picking(
			folder =
				work
					.also {
						(1..60).forEach { n ->
							it.resolve("dir %02d".format(n)).createDirectories()
						}
					}.resolve("dir 30"),
		) {
			onNodeWithTag("up").performClick()
			waitUntil(timeoutMillis = TIMEOUT) {
				it.state.value.cursor
					?.at == RowId("dir 30")
			}
			waitForIdle()
			// The window holds some sixteen rows; the thirtieth is scrolled up to the bottom of them, not to the top,
			// so the tenth row above it is in sight too.
			row("dir 30").assertIsDisplayed()
			row("dir 20").assertIsDisplayed()
		}

	@Test
	fun theButtonsComposeCallBackAndForwardAreASidewaysScrollOnLinuxAndDoNothing() =
		picking {
			row("Documents").performMouseInput { doubleClick() }
			shows(work.resolve("Documents"), "letter.odt")

			// What Compose calls back is X's button 6, a scroll to the left (sidebuttons.kt): nothing is gone back to.
			onNodeWithTag("listing").performMouseInput {
				press(COMPOSE_BACK)
				release(COMPOSE_BACK)
			}
			waitForIdle()
			shows(work.resolve("Documents"), "letter.odt")
		}

	@Test
	fun theFiltersShowOnlyTheKindsChosenAndHiddenEntriesApart() =
		picking(folder = media()) {
			// Everything at first, hidden entries too.
			row(".secret").assertExists()
			row("Documents").assertExists()
			onNodeWithTag("kind:All").assertIsSelected()
			onNodeWithTag("hidden").assertIsOn()

			onNodeWithTag("kind:Images").performClick()
			row("photo.png").assertExists()
			absent("notes.md", "Documents", "clip.mp4")
			onNodeWithTag("kind:All").assertIsNotSelected()

			// A second kind shows its entries as well.
			onNodeWithTag("kind:Videos").performClick()
			row("photo.png").assertExists()
			row("clip.mp4").assertExists()
			absent("notes.md", "Documents")
			onNodeWithTag("status").assertTextEquals("2 of 8 items")

			// Hidden entries are shown or not whatever kinds are on: none here are images or videos.
			onNodeWithTag("kind:All").performClick()
			row(".secret").assertExists()
			onNodeWithTag("hidden").performClick()
			onNodeWithTag("hidden").assertIsOff()
			absent(".secret")
			row("notes.md").assertExists()
		}

	@Test
	fun theFiltersAreOneStopOfTheKeyboard() =
		picking(folder = media()) {
			// Back from the listing: Show hidden, then the kinds.
			focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
			focusIsIn("hidden")
			press(Key.Spacebar)
			absent(".secret")
			focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
			focusIsIn("kinds")
			// All, Folders, Images: two to the right, and on.
			press(Key.DirectionRight)
			press(Key.DirectionRight)
			press(Key.Spacebar)
			onNodeWithTag("kind:Images").assertIsSelected()
			absent("notes.md")
		}

	@Test
	fun theGalleryShowsEachEntryAsATileWithAPicture() =
		picking(folder = media()) { session ->
			onNodeWithTag("layout:Gallery").performClick()
			onNodeWithTag("layout:Gallery").assertIsSelected()
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("picture:photo.png")).fetchSemanticsNodes().isNotEmpty()
			}
			// Only an image has a picture; the rest have their icons.
			assertEquals(0, onAllNodes(hasTestTag("picture:notes.md")).fetchSemanticsNodes().size)

			// Left and Right move along a line of tiles; Down goes a whole line down, and Up back.
			press(Key.MoveHome)
			press(Key.DirectionRight)
			row("Music").assertIsSelected()
			press(Key.DirectionDown)
			assertTrue(
				(
					session.state.value.cursor
						?.at
						?.let(session.state.value.rows::indexOf) ?: 0
				) > 2,
			)
			press(Key.DirectionUp)
			row("Music").assertIsSelected()

			// A double click on a folder's tile opens it, in the gallery still.
			row("Documents").performMouseInput { doubleClick() }
			shows(work.resolve("Documents"), "letter.odt")
			assertEquals(Layout.Gallery, session.state.value.layout)

			// Ctrl+1 for the list, Ctrl+2 for the gallery.
			focused().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.One) } }
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.layout == Layout.List }
			focused().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Two) } }
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.layout == Layout.Gallery }
		}

	@Test
	fun escapeCancels() =
		picking { session ->
			press(Key.Escape)

			assertEquals(Outcome.Cancelled, ended(session))
		}

	@Test
	fun theCursorIsKeptInSightAndNothingElseMoves() =
		picking(
			folder =
				work.also {
					(1..60).forEach { n ->
						it.resolve("file %02d.txt".format(n)).writeText("")
					}
				},
		) {
			row("file 01.txt").performClick()
			row("file 01.txt").assertIsDisplayed()

			press(Key.MoveEnd)
			row("file 60.txt").assertIsDisplayed()

			press(Key.MoveHome)
			row("file 01.txt").assertIsDisplayed()

			// A row already in sight, chosen, scrolls nothing.
			row("file 06.txt").performClick()
			row("file 01.txt").assertIsDisplayed()

			// Down past the last row in sight (the window holds some sixteen) scrolls by a row, not a page: the rows
			// just above the cursor are still in sight, where a page's jump would have left them above it.
			press(Key.MoveHome)
			repeat(19) { press(Key.DirectionDown) }
			row("file 20.txt").assertIsSelected()
			row("file 20.txt").assertIsDisplayed()
			row("file 19.txt").assertIsDisplayed()
			row("file 10.txt").assertIsDisplayed()
		}

	private companion object {
		const val TIMEOUT = 5_000L

		/** What Compose calls the back button: AWT's fourth, which on Linux is X's sideways scroll. */
		val COMPOSE_BACK = MouseButton(3)
	}
}
