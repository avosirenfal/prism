package barion.picker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Container
import java.awt.event.InputEvent
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

	/** The trash the tests' disk uses, so that nothing goes to the real one. */
	@TempDir
	lateinit var bin: Path

	/** What the picker put on the clipboard, in order. */
	private val copied = mutableListOf<String>()

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

	/**
	 * The picker running over [folder] and choosing [choosing], with [check] to do to it once its folder is shown; as
	 * wide as the window, or as [width] says, which the check can change, as a window is made wider or narrower.
	 */
	private fun picking(
		choosing: Choosing = Choosing.OneFile(),
		folder: Path = home(),
		width: State<Dp>? = null,
		check: ComposeUiTest.(Session) -> Unit,
	) = runComposeUiTest {
		val running = arrayOfNulls<Session>(1)

		setContent {
			val scope = rememberCoroutineScope()

			val session =
				remember {
					Session(
						opened(choosing, folder, work, sidebarOf(work)),
						JvmDisk(trash = Trash(bin)),
						scope,
						clipboard = { copied += it },
					)
				}

			running[0] = session
			PickerTheme {
				Box(width?.let { Modifier.width(it.value) } ?: Modifier) { SessionPicker(session) }
			}
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

	/** The small home, with a real picture, a video, a hidden file and a hidden folder besides. */
	private fun media(): Path =
		home().also {
			ThumbnailTest.picture(it, "photo.png", 400, 300)
			it.resolve("clip.mp4").writeText("not really")
			it.resolve(".secret").writeText("")
			it.resolve(".stash").createDirectories()
		}

	/** No menu is open. */
	private fun ComposeUiTest.noMenu() =
		assertEquals(0, onAllNodes(hasTestTag("menu")).fetchSemanticsNodes().size)

	/** [key] pressed with Ctrl held, on whatever has the keyboard. */
	private fun ComposeUiTest.ctrl(key: Key) =
		focused().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }

	/** The listing pressed with the right button where there is no row: its lower right corner. */
	private fun ComposeUiTest.rightClickNowhere() =
		onNodeWithTag("listing").performMouseInput {
			rightClick(Offset(width - 20f, height - 20f))
		}

	/**
	 * What has the keyboard. A menu or a notice open has a focus of its own, beside the window's, and it is what keys
	 * go to while it is open.
	 */
	private fun ComposeUiTest.focused(): SemanticsNodeInteraction =
		listOf(
			isFocused() and hasTestTag("menu"),
			isFocused() and hasAnyAncestor(hasTestTag("notice")),
		).firstOrNull { onAllNodes(it).fetchSemanticsNodes().isNotEmpty() }
			?.let { onNode(it) }
			?: onNode(isFocused())

	/** The keyboard is on the control tagged [tag], or on the part of it that takes the keyboard. */
	private fun ComposeUiTest.focusIsIn(tag: String) =
		onNode(
			isFocused() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
		).assertExists()

	/** The text field inside the field tagged [tag]. */
	private fun ComposeUiTest.field(tag: String): SemanticsNodeInteraction =
		onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))

	private fun ComposeUiTest.press(key: Key) = focused().performKeyInput { pressKey(key) }

	/**
	 * [key] pressed as a real keyboard sends it: down, then the [character] it types, then up. Compose's test keyboard
	 * sends only the first and last, so the character is sent as the window would have it, from an AWT event, through
	 * a maker of key events Compose keeps for itself (which may change with Compose's version, as this test would).
	 */
	@OptIn(InternalComposeUiApi::class)
	private fun ComposeUiTest.typing(key: Key, character: Char, ctrl: Boolean = false) {
		val held = if (ctrl) listOf(Key.CtrlLeft) else emptyList()

		focused().performKeyInput { (held + key).forEach { keyDown(it) } }
		focused().performKeyPress(
			KeyEvent(
				key,
				KeyEventType.Unknown,
				character.code,
				isCtrlPressed = ctrl,
				nativeEvent =
					java.awt.event.KeyEvent(
						Container(),
						java.awt.event.KeyEvent.KEY_TYPED,
						0,
						if (ctrl) InputEvent.CTRL_DOWN_MASK else 0,
						java.awt.event.KeyEvent.VK_UNDEFINED,
						character,
					),
			),
		)
		focused().performKeyInput { (held + key).reversed().forEach { keyUp(it) } }
	}

	/** [key] pressed with Ctrl and Shift held. */
	private fun ComposeUiTest.ctrlShift(key: Key) =
		focused().performKeyInput {
			withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(key) } }
		}

	/** The wheel turned [notches] away from the user (larger) with Ctrl held, over the listing. */
	private fun ComposeUiTest.zoomByWheel(notches: Int) {
		focused().performKeyInput { keyDown(Key.CtrlLeft) }
		onNodeWithTag("listing").performMouseInput {
			moveTo(center)
			scroll(-notches.toFloat())
		}
		focused().performKeyInput { keyUp(Key.CtrlLeft) }
	}

	/** Whether the picker is asking something, in a notice over it. */
	private fun ComposeUiTest.asked(): Boolean =
		onAllNodes(hasTestTag("notice")).fetchSemanticsNodes().isNotEmpty()

	private fun ComposeUiTest.bounds(name: String) =
		row(name).fetchSemanticsNode().boundsInRoot

	private fun ComposeUiTest.left(name: String): Float = bounds(name).left

	private fun ComposeUiTest.top(name: String): Float = bounds(name).top

	private fun ComposeUiTest.width(name: String): Float = bounds(name).width

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
			// Round from the end to the start: the path, the search, the places, then the listing.
			press(Key.Tab)
			press(Key.Tab)
			focusIsIn("crumbs")
			press(Key.Tab)
			focusIsIn("search")
			press(Key.Tab)
			focusIsIn("places")
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
			// Back from the listing: the places.
			focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
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
	fun theFilterMenuShowsOnlyTheKindChosenAndWithCtrlMore() =
		picking(folder = media()) {
			// Hidden files are shown at first, hidden folders not.
			row(".secret").assertExists()
			absent(".stash")

			onNodeWithTag("filters").performClick()
			onNodeWithTag("menu:All kinds").assertIsSelected()
			onNodeWithTag("menu:Images").performClick()

			// Only the images, and the menu closed.
			row("photo.png").assertExists()
			absent("notes.md", "Documents", "clip.mp4", ".secret")
			noMenu()
			onNodeWithTag("filters").assertIsSelected()

			// With Ctrl, the videos as well, and the menu stays open to choose more.
			onNodeWithTag("filters").performClick()
			focused().performKeyInput { keyDown(Key.CtrlLeft) }
			onNodeWithTag("menu:Videos").performMouseInput { click() }
			focused().performKeyInput { keyUp(Key.CtrlLeft) }
			onNodeWithTag("menu:Videos").assertIsSelected()
			onNodeWithTag("menu:Images").assertIsSelected()
			row("photo.png").assertExists()
			row("clip.mp4").assertExists()
			absent("notes.md")
			onNodeWithTag("status").assertTextEquals("2 of 9 items")

			// Every kind again, then the hidden folders, apart from the kinds.
			onNodeWithTag("menu:All kinds").performClick()
			noMenu()
			row("notes.md").assertExists()
			onNodeWithTag("filters").performClick()
			onNodeWithTag("menu:Show hidden folders").performClick()
			row(".stash").assertExists()
			onNodeWithTag("filters").performClick()
			onNodeWithTag("menu:Show hidden files").performClick()
			absent(".secret")
			row(".stash").assertExists()
		}

	@Test
	fun aMenuIsWorkedByTheKeyboard() =
		picking(folder = media()) {
			onNodeWithTag("filters").performClick()
			// Down lights All kinds, then Folders; Enter chooses it.
			press(Key.DirectionDown)
			press(Key.DirectionDown)
			press(Key.Enter)
			noMenu()
			row("Documents").assertExists()
			absent("notes.md")

			// Escape closes a menu without closing the picker.
			onNodeWithTag("filters").performClick()
			press(Key.Escape)
			noMenu()
			row("Documents").assertExists()
		}

	@Test
	fun aRowsMenuRenamesIt() =
		picking { session ->
			// The right button chooses the row it is pressed on, and opens its menu.
			row("notes.md").performMouseInput { rightClick() }
			row("notes.md").assertIsSelected()
			onNodeWithTag("menu:Rename").performClick()

			field("rename").assertIsFocused()
			field("rename").assertTextEquals("notes.md")
			field("rename").performTextReplacement("journal.md")
			press(Key.Enter)

			waitUntil(timeoutMillis = TIMEOUT) { work.resolve("journal.md").exists() }
			assertFalse(work.resolve("notes.md").exists())
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.chosenRows
					.map { it.name } ==
					listOf("journal.md")
			}
			// The keyboard is back in the listing.
			focusIsIn("listing")
		}

	@Test
	fun f2RenamesAndEscapeLeavesTheNameAsItWas() =
		picking { session ->
			row("todo.txt").performClick()
			press(Key.F2)
			field("rename").performTextReplacement("done.txt")
			press(Key.Escape)

			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.renaming == null }
			assertTrue(work.resolve("todo.txt").exists())
			assertFalse(work.resolve("done.txt").exists())
			// Escape left the name; it did not cancel the picker.
			assertFalse(session.outcome.isCompleted)
		}

	@Test
	fun deleteAsksBeforeTheTrashAndCtrlZPutsBack() =
		picking { session ->
			row("todo.txt").performClick()
			press(Key.Delete)

			waitUntil(timeoutMillis = TIMEOUT) { asked() }
			assertTrue(work.resolve("todo.txt").exists())
			onNodeWithTag("yes").performClick()

			waitUntil(timeoutMillis = TIMEOUT) { !work.resolve("todo.txt").exists() }
			assertEquals("everything", bin.resolve("files/todo.txt").readText())
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("row:todo.txt")).fetchSemanticsNodes().isEmpty()
			}
			// The keyboard is back in the listing, where Ctrl+Z puts the file back, chosen, and Ctrl+Shift+Z moves it
			// to the trash again.
			focusIsIn("listing")
			ctrl(Key.Z)
			waitUntil(timeoutMillis = TIMEOUT) { work.resolve("todo.txt").exists() }
			assertFalse(bin.resolve("files/todo.txt").exists())
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.chosenRows
					.map { it.name } ==
					listOf("todo.txt")
			}
			ctrlShift(Key.Z)
			waitUntil(timeoutMillis = TIMEOUT) { !work.resolve("todo.txt").exists() }
		}

	@Test
	fun shiftDeleteAndTheMenuMoveToTheTrashWithoutAsking() =
		picking {
			row("todo.txt").performClick()
			focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Delete) } }
			waitUntil(timeoutMillis = TIMEOUT) { !work.resolve("todo.txt").exists() }
			assertFalse(asked())

			row("main.kt").performMouseInput { rightClick() }
			onNodeWithTag("menu:Move to trash").performClick()
			waitUntil(timeoutMillis = TIMEOUT) { !work.resolve("main.kt").exists() }
			assertFalse(asked())
			assertEquals("fun main() {}", bin.resolve("files/main.kt").readText())
		}

	@Test
	fun theFoldersMenuSortsAndMakesAFolder() =
		picking { session ->
			rightClickNowhere()
			// Hovering a submenu's entry opens it.
			onNodeWithTag("menu:Sort by").performMouseInput { moveTo(center) }
			onNodeWithTag("menu:Size").performClick()
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.arrangement.sort.by ==
					Sort.Column.Size
			}
			noMenu()

			rightClickNowhere()
			onNodeWithTag("menu:New folder").performClick()
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("rename")).fetchSemanticsNodes().isNotEmpty()
			}
			assertTrue(work.resolve("New folder").exists())
			field("rename").assertTextEquals("New folder")
			field("rename").performTextReplacement("Archive")
			press(Key.Enter)

			waitUntil(timeoutMillis = TIMEOUT) { work.resolve("Archive").exists() }
			assertFalse(work.resolve("New folder").exists())

			// Undone the latest first: the rename, from the menu, which says what it undoes; then the folder itself.
			rightClickNowhere()
			onNodeWithTag("menu:Undo the rename").performClick()
			waitUntil(timeoutMillis = TIMEOUT) { work.resolve("New folder").exists() }
			assertFalse(work.resolve("Archive").exists())
			ctrl(Key.Z)
			waitUntil(timeoutMillis = TIMEOUT) { !work.resolve("New folder").exists() }

			// And done again in the order it was done.
			ctrlShift(Key.Z)
			waitUntil(timeoutMillis = TIMEOUT) { work.resolve("New folder").exists() }
			rightClickNowhere()
			onNodeWithTag("menu:Redo the rename").performClick()
			waitUntil(timeoutMillis = TIMEOUT) { work.resolve("Archive").exists() }
		}

	@Test
	fun copyPathPutsItOnTheClipboard() =
		picking {
			row("notes.md").performMouseInput { rightClick() }
			onNodeWithTag("menu:Copy path").performClick()
			waitUntil(timeoutMillis = TIMEOUT) { copied.isNotEmpty() }
			assertEquals(listOf(work.resolve("notes.md").toString()), copied)
		}

	@Test
	fun shiftF10OpensTheMenuFromTheKeyboard() =
		picking { session ->
			press(Key.DirectionDown)
			focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
			// The menu of the row chosen, which is a folder.
			onNodeWithTag("menu:Open").assertExists()
			press(Key.Escape)
			noMenu()
			assertFalse(session.outcome.isCompleted)
		}

	@Test
	fun ctrlAndTheWheelMakeTheListingLargerAndSmaller() =
		picking { session ->
			focused().performKeyInput { keyDown(Key.CtrlLeft) }
			// A test's mouse starts at the window's corner, and a wheel turns where the mouse is: over the listing, then.
			onNodeWithTag("listing").performMouseInput {
				moveTo(center)
				scroll(-1f)
			}
			focused().performKeyInput { keyUp(Key.CtrlLeft) }
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.zoom == Zoom.By(3) }

			focused().performKeyInput { keyDown(Key.CtrlLeft) }
			onNodeWithTag("listing").performMouseInput {
				moveTo(center)
				scroll(2f)
			}
			focused().performKeyInput { keyUp(Key.CtrlLeft) }
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.zoom == Zoom.By(1) }

			ctrl(Key.Zero)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.zoom == Zoom.NORMAL }

			// Anywhere in the window, whatever has the keyboard: over the places, typing in the search.
			ctrl(Key.F)
			focusIsIn("search")
			focused().performKeyInput { keyDown(Key.CtrlLeft) }
			onNodeWithTag("places").performMouseInput {
				moveTo(center)
				scroll(-1f)
			}
			focused().performKeyInput { keyUp(Key.CtrlLeft) }
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.zoom == Zoom.By(3) }
			focusIsIn("search")
		}

	@Test
	fun theKeysWithCtrlWorkInTheSearchAndTypeNothingIntoIt() =
		picking { session ->
			ctrl(Key.F)
			focusIsIn("search")

			// A letter typed is searched for: what a key types does reach the field.
			typing(Key.N, 'n')
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.arrangement.query == "n" }

			// Ctrl+2 types its "2" as well, and that goes nowhere; Ctrl+= its "=".
			typing(Key.Two, '2', ctrl = true)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.layout == Layout.Gallery }
			typing(Key.Equals, '=', ctrl = true)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.zoom == Zoom.By(3) }
			typing(Key.One, '1', ctrl = true)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.layout == Layout.List }

			waitForIdle()
			focusIsIn("search")
			field("search").assertTextEquals("n")
			assertEquals("n", session.state.value.arrangement.query)
		}

	@Test
	fun theGalleryZoomsInToOneTileAtATimeAndAWindowMadeSmallerMakesItSmaller() {
		val wide = mutableStateOf(1000.dp)

		picking(folder = media(), width = wide) { session ->
			ctrl(Key.Two)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.layout == Layout.Gallery }

			// Twice the usual size: the gallery, some 770 wide, has room for two tiles a line.
			zoomByWheel(3)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.zoom == Zoom.By(5) }
			waitForIdle()
			assertTrue(left("Music") > left("Documents"), "beside each other")

			// The window made so narrow that not even one tile of that size fits: it is drawn smaller, to fit, one a
			// line. The size chosen is kept, for when there is room again.
			wide.value = 420.dp
			waitForIdle()
			assertEquals(left("Documents"), left("Music"), "one above the other")
			assertTrue(
				width(
					"Documents",
				) <= onNodeWithTag("listing").fetchSemanticsNode().boundsInRoot.width,
			)
			assertEquals(Zoom.By(5), session.state.value.zoom)
			wide.value = 1000.dp
			waitForIdle()
			assertTrue(left("Music") > left("Documents"), "beside each other again")

			// As far in as it goes, however far the wheel turns: one tile at a time, filling the gallery.
			zoomByWheel(20)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.zoom == Zoom.Whole }
			waitForIdle()

			val gallery = onNodeWithTag("listing").fetchSemanticsNode().boundsInRoot

			assertTrue(
				bounds("Documents").height > gallery.height * 0.9f,
				"as high as the gallery",
			)
			assertTrue(width("Documents") > gallery.width * 0.9f, "as wide as the gallery")
			// The next is below it, out of sight.
			absent("Music")
		}
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
