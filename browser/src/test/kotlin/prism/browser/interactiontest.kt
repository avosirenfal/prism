package prism.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.MouseInjectionScope
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
import androidx.compose.ui.test.performTouchInput
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces
import prism.preferences.Builtin
import prism.preferences.ColumnId
import prism.preferences.Hidden
import prism.preferences.Layout
import prism.preferences.Preferences
import prism.preferences.QuickItem
import prism.preferences.Sort

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
		finder: Finder? = null,
		check: ComposeUiTest.(Session) -> Unit,
	) = runComposeUiTest {
		val running = arrayOfNulls<Session>(1)

		setContent {
			val scope = rememberCoroutineScope()

			val session =
				remember {
					Session(
						opened(
							choosing,
							folder,
							work,
							sidebarOf(XdgPlaces(), work),
							search =
								finder?.let { Search.Ready() } ?: Search.Unavailable(
									BrowserState.NO_INDEX,
								),
						),
						JvmDisk(trash = Trash(bin)),
						scope,
						clipboard = { copied += it },
						finder = finder,
					)
				}

			running[0] = session
			BrowserTheme {
				// The keys as a window has them: the defaults, and those of the actions that ship.
				CompositionLocalProvider(
					LocalBindings provides Bindings.of(Preferences()),
					// Nothing moves for its own sake, so that the view comes to rest and can be looked at.
					LocalMotion provides false,
				) {
					Box(width?.let { Modifier.width(it.value) } ?: Modifier) { SessionBrowser(session) }
				}
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

	/** A row pressed with the right button on its name's column: a fifth of the way across, where names are. */
	private fun MouseInjectionScope.rightClickName() =
		rightClick(
			Offset(
				width * 0.2f,
				height / 2f,
			),
		)

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
			isFocused() and hasAnyAncestor(hasTestTag("search-layer")),
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

	/** The wheel turned [notches] toward the user, down the list, over the listing. */
	private fun ComposeUiTest.wheel(notches: Int) {
		onNodeWithTag("listing").performMouseInput {
			moveTo(center)
			scroll(notches.toFloat())
		}
		waitForIdle()
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
	fun aRowsMenuOpensOnItsNameAndTheFoldersOnItsOtherColumns() =
		picking { session ->
			// On the name, or the space after it in the name's column: the row's menu, the row chosen.
			row("notes.md").performMouseInput { rightClick(Offset(width * 0.4f, height / 2f)) }
			row("notes.md").assertIsSelected()
			onNodeWithTag("menu:Rename").assertExists()
			press(Key.Escape)
			noMenu()

			// On its date, its type or its size: the folder's menu, and nothing chosen by it.
			for (across in listOf(0.55f, 0.75f, 0.95f)) {
				row("todo.txt").performMouseInput { rightClick(Offset(width * across, height / 2f)) }
				onNodeWithTag("menu:New folder").assertExists()
				onNodeWithTag("menu:Rename").assertDoesNotExist()
				row("todo.txt").assertIsNotSelected()
				press(Key.Escape)
				noMenu()
			}
		}

	@Test
	fun whatIsRarelyWantedIsInAContextMenuOpenedWithShiftHeld() =
		picking { session ->
			rightClickNowhere()
			onNodeWithTag("menu:New folder").assertExists()
			onNodeWithTag("menu:Refresh").assertDoesNotExist()
			onNodeWithTag("menu:Show hidden files").assertDoesNotExist()
			press(Key.Escape)
			noMenu()

			// Shift held as it opens: refreshing, and showing what is hidden, are there too.
			onNodeWithTag("listing").performKeyInput { keyDown(Key.ShiftLeft) }
			rightClickNowhere()
			onNodeWithTag("listing").performKeyInput { keyUp(Key.ShiftLeft) }
			onNodeWithTag("menu:Refresh").assertExists()
			onNodeWithTag("menu:Show hidden files").assertExists()
		}

	@Test
	fun aRowsMenuRenamesIt() =
		picking { session ->
			// The right button chooses the row it is pressed on, and opens its menu.
			row("notes.md").performMouseInput { rightClickName() }
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
	fun deleteTrashesWithoutAskingAndCtrlZPutsBack() =
		picking { session ->
			row("todo.txt").performClick()
			press(Key.Delete)

			waitUntil(timeoutMillis = TIMEOUT) { !work.resolve("todo.txt").exists() }
			assertFalse(asked())
			assertEquals("everything", bin.resolve("files/todo.txt").readText())
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("row:todo.txt")).fetchSemanticsNodes().isEmpty()
			}
			// The keyboard is still in the listing, where Ctrl+Z puts the file back, chosen, and Ctrl+Shift+Z moves it
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

			// The menu's "Move to trash" is the same.
			row("main.kt").performMouseInput { rightClickName() }
			onNodeWithTag("menu:Move to trash").performClick()
			waitUntil(timeoutMillis = TIMEOUT) { !work.resolve("main.kt").exists() }
			assertFalse(asked())
			assertEquals("fun main() {}", bin.resolve("files/main.kt").readText())
		}

	@Test
	fun theTrashIsInQuickAccess_ItsEntriesPutBackFromIt() =
		picking(Choosing.Exploring()) { session ->
			row("todo.txt").performClick()
			press(Key.Delete)
			waitUntil(timeoutMillis = TIMEOUT) { !work.resolve("todo.txt").exists() }

			onNodeWithTag("place:Trash").performClick()
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("row:todo.txt")).fetchSemanticsNodes().isNotEmpty()
			}
			assertTrue(session.state.value.inTrash)
			onNodeWithTag("trash-bar").assertExists()

			row("todo.txt").performMouseInput { rightClickName() }
			onNodeWithTag("menu:Put back").performClick()
			waitUntil(timeoutMillis = TIMEOUT) { work.resolve("todo.txt").exists() }
			assertEquals("everything", work.resolve("todo.txt").readText())
		}

	@Test
	fun quickAccessIsArrangedInAWindowOfItsOwn_WhereOneOfPrismsOwnIsHiddenNotRemoved() =
		picking(Choosing.Exploring()) { session ->
			onNodeWithTag("place:Music").assertExists()

			// The button beside quick access's heading opens its window.
			onNodeWithTag("arrange-quick-access").performClick()
			onNodeWithTag("quick-access").assertExists()
			// Prism's own have boxes, and no way to remove them; unticked, one goes from the sidebar.
			onNodeWithTag("remove:Music").assertDoesNotExist()
			onNodeWithTag("shown:Music").performClick()
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("place:Music")).fetchSemanticsNodes().isEmpty()
			}
			assertFalse(
				session.state.value.sidebar.quick
					.shows(Builtin.Music),
			)

			// Moved, it is moved in what the sidebar shows from.
			val trashWas =
				session.state.value.sidebar.quick.items
					.indexOf(QuickItem.Own(Builtin.Trash))

			onNodeWithTag("up:Trash").performClick()
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.sidebar.quick.items
					.indexOf(QuickItem.Own(Builtin.Trash)) == trashWas - 1
			}

			// Undone in the window, it is back where it was; done again, moved again. The browser's own undo, of what
			// it did to the disk, is not this.
			val undoneBefore = session.state.value.changes

			fun quickKeys(chord: KeyInjectionScope.() -> Unit) {
				onNode(
					isFocused() and hasAnyAncestor(hasTestTag("quick-access")),
				).performKeyInput(chord)
			}

			quickKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.sidebar.quick.items
					.indexOf(QuickItem.Own(Builtin.Trash)) == trashWas
			}
			quickKeys {
				withKeyDown(
					Key.CtrlLeft,
				) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } }
			}
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.sidebar.quick.items
					.indexOf(QuickItem.Own(Builtin.Trash)) == trashWas - 1
			}
			assertEquals(undoneBefore, session.state.value.changes)

			// Dragged by its grip, an entry goes where it is let go: the trash, up past the two above it.
			val before = session.state.value.sidebar.quick.items
			val trashAt = before.indexOf(QuickItem.Own(Builtin.Trash))

			val line =
				onNodeWithTag("quick:Trash")
					.fetchSemanticsNode()
					.size.height
					.toFloat()

			onNodeWithTag("grip:Trash").performTouchInput {
				down(center)
				repeat(10) { moveBy(Offset(0f, -line * 0.25f)) }
				up()
			}
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.sidebar.quick.items
					.indexOf(QuickItem.Own(Builtin.Trash)) == trashAt - 2
			}
			onNodeWithTag("done").performClick()
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("quick-access")).fetchSemanticsNodes().isEmpty()
			}

			// Opened again, Escape puts it away, wherever in it the keyboard is.
			onNodeWithTag("arrange-quick-access").performClick()
			onNode(isFocused() and hasAnyAncestor(hasTestTag("quick-access"))).performKeyInput {
				pressKey(Key.Escape)
			}
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("quick-access")).fetchSemanticsNodes().isEmpty()
			}
		}

	@Test
	fun shiftDeleteDeletesForGoodOnlyOnceAsked_TheKeyboardStartingOnCancel() =
		picking {
			row("todo.txt").performClick()
			focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Delete) } }

			waitUntil(timeoutMillis = TIMEOUT) { asked() }
			// Enter, as the notice opens, cancels: what cannot be undone is not done by a key pressed in passing.
			press(Key.Enter)
			waitUntil(timeoutMillis = TIMEOUT) { !asked() }
			assertTrue(work.resolve("todo.txt").exists())

			focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Delete) } }
			waitUntil(timeoutMillis = TIMEOUT) { asked() }
			onNodeWithTag("yes").performClick()
			waitUntil(timeoutMillis = TIMEOUT) { !work.resolve("todo.txt").exists() }
			// Gone for good: not in the trash.
			assertFalse(bin.resolve("files/todo.txt").exists())
		}

	/** Whether each of [programs] is on the `PATH`: what the archive actions that ship need, to be offered. */
	private fun has(vararg programs: String): Boolean =
		programs.all { program ->
			System
				.getenv(
					"PATH",
				).orEmpty()
				.split(':')
				.any { java.io.File(it, program).canExecute() }
		}

	/** A home with a zip of its notes beside them, made by the machine's own `zip`. */
	private fun zipped(): Path =
		home().also {
			assumeTrue(has("zip", "unzip", "zipinfo"))
			assertEquals(
				0,
				ProcessBuilder("zip", "-q", "old.zip", "notes.md", "todo.txt")
					.directory(it.toFile())
					.start()
					.waitFor(),
			)
			it.resolve("notes.md").toFile().delete()
		}

	@Test
	fun theUsersActionsAreInAFilesMenuAndASubmenusAsksBeforeItRuns() =
		picking(Choosing.Exploring(), folder = zipped()) { session ->
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.actions.actions
					.isNotEmpty()
			}

			// An archive is extracted from its menu, and the folder read again once it has been.
			row("old.zip").performMouseInput { rightClickName() }
			onNodeWithTag("menu:Extract here").performClick()
			waitUntil(timeoutMillis = TIMEOUT) { work.resolve("notes.md").exists() }
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("row:notes.md")).fetchSemanticsNodes().isNotEmpty()
			}
			noMenu()

			// An action in a submenu asks for a name first, in a field, with what it would be called to type over.
			row("main.kt").performMouseInput { rightClickName() }
			onNodeWithTag("menu:Compress").performMouseInput { moveTo(center) }
			onNodeWithTag("menu:As a .zip…").performClick()
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.notice is Notice.Asking }
			field("answer").assertTextEquals("main")
			field("answer").performTextReplacement("sources")
			press(Key.Enter)
			waitUntil(timeoutMillis = TIMEOUT) { work.resolve("sources.zip").exists() }
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.cursor
					?.at ==
					RowId("sources.zip")
			}
		}

	@Test
	fun inAnArchiveDeleteRemovesAnEntryAndAskedFirst() =
		picking(Choosing.Exploring(), folder = zipped()) { session ->
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.actions.actions
					.isNotEmpty()
			}

			// The archive's place, in this window: as opening it shows it, but for the window.
			val state = session.state.value
			val view = state.actions["archive.zip.open"]!!

			session.send(
				BrowserEvent.Did(
					view.copy(deed = (view.deed as Deed.Show).copy(window = false)),
					state.subjectOf(
						Target.Rows(listOf(state.rows.list.single { it.name == "old.zip" })),
					),
				),
			)
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("row:todo.txt")).fetchSemanticsNodes().isNotEmpty() &&
					session.state.value.place != null
			}
			onNodeWithTag("place").assertExists()
			// An archive's window is the archive's: the places on disk are not beside it.
			assertEquals(0, onAllNodes(hasTestTag("places")).fetchSemanticsNodes().size)

			// Ctrl+Shift+F searches the archive, by its own listing, though this window has no index of the disk.
			session.send(BrowserEvent.OpenedSearch)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.search.open }
			session.send(BrowserEvent.SearchTyped("todo"))
			waitUntil(timeoutMillis = TIMEOUT) {
				(session.state.value.search as? Search.Ready)?.matched?.matches?.size == 1
			}
			session.send(BrowserEvent.ShowedResults)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.results != null }
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("row:todo.txt")).fetchSemanticsNodes().size == 1 &&
					onAllNodes(hasTestTag("row:notes.md")).fetchSemanticsNodes().isEmpty()
			}
			session.send(BrowserEvent.Back)
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("row:notes.md")).fetchSemanticsNodes().isNotEmpty()
			}

			// The menu of an entry is its place's: nothing of the trash, or of renaming.
			row("todo.txt").performMouseInput { rightClickName() }
			onNodeWithTag("menu:Extract to…").assertExists()
			onNodeWithTag("menu:Remove from archive").assertExists()
			assertEquals(
				0,
				onAllNodes(hasTestTag("menu:Move to trash")).fetchSemanticsNodes().size,
			)
			press(Key.Escape)

			// Delete, which the place's action is bound to here, asks first; then the entry is gone from the archive.
			row("todo.txt").performClick()
			press(Key.Delete)
			waitUntil(
				timeoutMillis = TIMEOUT,
			) { session.state.value.notice is Notice.ConfirmAction }
			onNodeWithTag("yes").performClick()
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.running
					.isEmpty() &&
					onAllNodes(hasTestTag("row:todo.txt")).fetchSemanticsNodes().isEmpty()
			}
			row("notes.md").assertExists()
		}

	@Test
	fun aCommandUnderWayIsShownOverTheBrowserWithWhatItSaysAndAButtonThatStopsIt() =
		picking(Choosing.Exploring()) { session ->
			assumeTrue(has("sh", "sleep"))

			val slow =
				Actions
					.read(
						listOf(
							prism.preferences.ActionsFile.parse(
								"""{"actions": [{"id": "slow", "label": "Counting", "when": {"target": "folder"},
								"run": ["sh", "-c", "echo one; echo two; sleep 60"], "how": {"watched": true}}]}""",
							),
						),
					).actions
					.single()

			session.send(BrowserEvent.Did(slow, session.state.value.subjectOf(Target.Folder)))
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("work")).fetchSemanticsNodes().isNotEmpty() &&
					session.state.value.running
						.singleOrNull()
						?.said == listOf("one", "two")
			}
			onNodeWithTag("work-said").assertTextEquals("one\ntwo")

			// The button stops it, and what showed it running goes with it; nothing is said to have failed.
			onNodeWithTag("stop").performClick()
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.running
					.isEmpty()
			}
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("work")).fetchSemanticsNodes().isEmpty()
			}
			assertEquals(null, session.state.value.notice)
		}

	@Test
	fun aRectangleDraggedOutFromWhereNoRowIsChoosesTheRowsItTakesIn() =
		picking(Choosing.Exploring()) { session ->
			val listing = onNodeWithTag("listing")
			// The last two rows, from below them: the home's rows are Documents, Music, main.kt, notes.md, todo.txt.
			val last = onNodeWithTag("row:todo.txt").fetchSemanticsNode().boundsInRoot
			val top = listing.fetchSemanticsNode().boundsInRoot.top

			listing.performMouseInput {
				moveTo(Offset(width - 60f, height - 40f))
				press()
				moveTo(Offset(width - 120f, last.bottom - top + 40f))
				moveTo(Offset(width - 200f, last.top - top - last.height / 2))
			}
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.choosing.rows ==
					setOf<RowId>(RowId("notes.md"), RowId("todo.txt"))
			}
			listing.performMouseInput { release() }
			waitForIdle()
			assertEquals(
				setOf<RowId>(RowId("notes.md"), RowId("todo.txt")),
				session.state.value.choosing.rows,
			)

			// With Ctrl held, a rectangle adds to what is chosen; a plain press where no row is lets go of it all.
			onNodeWithTag("row:Documents").performClick()
			listing.performKeyInput { keyDown(Key.CtrlLeft) }
			listing.performMouseInput {
				moveTo(Offset(width - 60f, height - 40f))
				press()
				moveTo(Offset(width - 200f, last.top - top + last.height / 2))
				release()
			}
			listing.performKeyInput { keyUp(Key.CtrlLeft) }
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.choosing.rows ==
					setOf<RowId>(RowId("Documents"), RowId("todo.txt"))
			}
			listing.performMouseInput { click(Offset(width - 60f, height - 40f)) }
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.choosing.rows
					.isEmpty()
			}
		}

	@Test
	fun theSearchsChordPressedAgainEmptiesItsFieldAndKeepsTheKeyboardThere() =
		picking(
			Choosing.Exploring(),
			finder = StaticFinder(prism.index.Index.EMPTY, home = work),
		) { session ->
			ctrlShift(Key.F)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.search.open }
			field("search-query").performTextReplacement("report")
			waitUntil(timeoutMillis = TIMEOUT) {
				(session.state.value.search as Search.Ready).text ==
					"report"
			}

			ctrlShift(Key.F)
			waitUntil(timeoutMillis = TIMEOUT) {
				(session.state.value.search as Search.Ready).text.isEmpty()
			}
			field("search-query").assertTextEquals("")
			assertTrue(session.state.value.search.open)
			focusIsIn("search-query")
		}

	@Test
	fun whatFailedCanBeSelectedAndCopied() =
		picking(Choosing.Exploring()) { session ->
			val why = (1..12).joinToString("\n") { "line $it of what the command wrote" }

			// A failure as a command's is said: how it ended, and many lines of what it wrote.
			session.send(
				BrowserEvent.Did(failing(why), session.state.value.subjectOf(Target.Folder)),
			)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.notice is Notice.Failed }
			onNodeWithTag("failure").assertExists()

			// Copy takes all of it, and leaves the notice where it is.
			onNodeWithTag("copy").performClick()
			waitUntil(timeoutMillis = TIMEOUT) { copied.isNotEmpty() }
			assertTrue(copied.last().startsWith("“Fails” failed"), copied.last())
			assertTrue("line 12 of what the command wrote" in copied.last(), copied.last())
			assertTrue(session.state.value.notice is Notice.Failed)
		}

	/** An action whose command writes [why] to standard error, line by line, and fails. */
	private fun failing(why: String): UserAction =
		Actions
			.read(
				listOf(
					prism.preferences.ActionsFile.parse(
						"""{"actions": [{"id": "fails", "label": "Fails", "when": {"target": "folder"},
						"run": ["sh", "-c", "printf '%s\\n' \"$1\" >&2; exit 3", "sh", ${kotlinx.serialization.json.JsonPrimitive(
							why,
						)}],
						"how": {"watched": true}}]}""",
					),
				),
			).actions
			.single()

	@Test
	fun aSubmenuClosesWhenThePointerMovesToAnotherEntry() =
		picking {
			rightClickNowhere()
			onNodeWithTag("menu:Sort by").performMouseInput { moveTo(center) }
			onNodeWithTag("menu:Size").assertExists()

			// Down the menu, off the submenu's entry: its submenu closes, and the entry under the pointer is lit.
			onNodeWithTag("menu:New folder").performMouseInput { moveTo(center) }
			waitForIdle()
			assertEquals(0, onAllNodes(hasTestTag("menu:Size")).fetchSemanticsNodes().size)

			// From one submenu's entry to the next, the first closes and the second opens.
			onNodeWithTag("menu:Sort by").performMouseInput { moveTo(center) }
			onNodeWithTag("menu:View").performMouseInput { moveTo(center) }
			waitForIdle()
			assertEquals(0, onAllNodes(hasTestTag("menu:Size")).fetchSemanticsNodes().size)
			onNodeWithTag("menu:Gallery").assertExists()
		}

	@Test
	fun theFoldersMenuSortsAndMakesAFolder() =
		picking { session ->
			rightClickNowhere()
			// Hovering a submenu's entry opens it.
			onNodeWithTag("menu:Sort by").performMouseInput { moveTo(center) }
			onNodeWithTag("menu:Size").performClick()
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.look.order.sort
					?.by ==
					ColumnId.Size
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
			row("notes.md").performMouseInput { rightClickName() }
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
	fun aNotchOfTheWheelMovesTheListThreeRows_HoweverLargeItIsDrawn() =
		picking(
			folder =
				work.also {
					(1..60).forEach { n ->
						it.resolve("dir %02d".format(n)).createDirectories()
					}
				},
		) {
			waitForIdle()

			val first = top("dir 01")

			// At its usual size, a notch down: the fourth row is where the first was.
			wheel(1)
			assertEquals(first, top("dir 04"), 1.5f)

			// Drawn larger, a notch still moves three of its rows, and not a third as many.
			zoomByWheel(4)
			waitForIdle()
			assertEquals(first, top("dir 04"), 1.5f)
			wheel(1)
			assertEquals(first, top("dir 07"), 1.5f)
		}

	@Test
	fun aTouchpadsScrollsMoveTheListAsTheyCome_AndANotchGlidesFromItsFirstFrameOn() =
		picking(
			folder =
				work.also {
					(1..60).forEach { n ->
						it.resolve("dir %02d".format(n)).createDirectories()
					}
				},
		) {
			waitForIdle()

			val first = top("dir 05")
			// A row's height, from eight of them, each top whole pixels.
			val row = (top("dir 09") - top("dir 01")) / 8

			fun turned(notches: Float) =
				onNodeWithTag("listing").performMouseInput {
					moveTo(center)
					scroll(notches)
				}

			mainClock.autoAdvance = false

			// A touchpad's scroll, half a notch: moved at once, a frame later, by all of it, a row and a half.
			turned(0.5f)
			mainClock.advanceTimeByFrame()
			assertEquals(first - row * 1.5f, top("dir 05"), 1.5f)

			// A notch moves from its first frame; another turned while it glides adds to where it is going, and it
			// goes on rather than stopping to start again.
			val before = top("dir 05")

			turned(1f)
			mainClock.advanceTimeByFrame()

			val once = top("dir 05")

			assertTrue(once < before, "moved on the first frame")
			turned(1f)
			mainClock.advanceTimeByFrame()
			assertTrue(top("dir 05") < once, "still moving")

			// Done, it is where the three scrolls take it, seven and a half rows down: the twelfth row, seven below the
			// fifth, is half a row above where the fifth was.
			mainClock.autoAdvance = true
			waitForIdle()
			assertEquals(first - row * 0.5f, top("dir 12"), 1.5f)
		}

	@Test
	fun aListingTooWideForItsWindowScrollsAcross_ItsHeadingsWithItsRows() {
		val wide = mutableStateOf(1000.dp)

		picking(width = wide) {
			val listing = onNodeWithTag("listing").fetchSemanticsNode().boundsInRoot

			// Where something is laid out across, in sight or not: what is out of sight is clipped from its bounds.
			fun across(tag: String): ClosedFloatingPointRange<Float> =
				onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates.let {
					val left = it.positionInRoot().x

					left..(left + it.size.width)
				}

			// At its usual size, every column is in sight.
			assertTrue(across("column:Size").endInclusive <= listing.right)

			// Drawn twice as large, the columns are wider than the listing: the last is past its edge, not cut down.
			zoomByWheel(4)
			waitForIdle()
			assertTrue(
				across("column:Size").start >= listing.right,
				"the size's heading is out of sight",
			)
			assertTrue(
				across("row:Documents").endInclusive > listing.right,
				"the rows are wider than the listing",
			)

			// Shift and the wheel bring it in, the rows with it.
			val before = across("row:Documents").start

			focused().performKeyInput { keyDown(Key.ShiftLeft) }
			onNodeWithTag("listing").performMouseInput {
				moveTo(center)
				scroll(10f)
			}
			focused().performKeyInput { keyUp(Key.ShiftLeft) }
			waitForIdle()
			assertTrue(
				across("column:Size").endInclusive <= listing.right,
				"the size's heading is in sight",
			)
			assertTrue(across("row:Documents").start < before, "the rows moved across with it")
		}
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
	fun aRowOfSeveralChosenPressedKeepsThemAllUntilThePressEndsWithoutADrag() {
		picking(Choosing.Files()) { session ->
			fun chosen() =
				session.state.value.chosenRows
					.map { it.name }
					.toSet()

			row("notes.md").performClick()
			onNodeWithTag("listing").performKeyInput { keyDown(Key.CtrlLeft) }
			row("todo.txt").performMouseInput { click() }
			onNodeWithTag("listing").performKeyInput { keyUp(Key.CtrlLeft) }
			waitForIdle()
			assertEquals(setOf("notes.md", "todo.txt"), chosen())

			// Pressed and held, as a drag of them both would begin: both are still chosen.
			row("notes.md").performMouseInput {
				moveTo(center)
				press()
			}
			waitForIdle()
			assertEquals(setOf("notes.md", "todo.txt"), chosen())

			// Let go without dragging: it alone, as a click of it is.
			row("notes.md").performMouseInput { release() }
			waitForIdle()
			assertEquals(setOf("notes.md"), chosen())
		}
	}

	@Test
	fun aSearchsTilesShowTheirOwnPicturesNotThoseOfTheSearchBefore() {
		// A search's rows are known by their places among its results, so the first result of one search and the first of
		// the next are the same tile to the gallery. Here the first search finds only pictures, and the next only
		// folders: the tiles that showed pictures now show folders, and must not go on showing the pictures.
		val folder = home().resolve("shots").createDirectories()

		ThumbnailTest.picture(folder, "a1.png", 400, 300)
		ThumbnailTest.picture(folder, "a2.png", 400, 300)
		folder.resolve("album").createDirectories()
		folder.resolve("art").createDirectories()

		picking(folder = folder, finder = finderOf(folder)) { session ->
			fun search(text: String, shown: (List<Row>) -> Boolean) {
				session.send(BrowserEvent.OpenedSearch)
				session.send(BrowserEvent.SearchTyped(text))
				waitUntil(timeoutMillis = TIMEOUT) {
					(session.state.value.search as? Search.Ready)?.found is Finding.Matched
				}
				session.send(BrowserEvent.ShowedResults)
				waitUntil(timeoutMillis = TIMEOUT) {
					session.state.value.let { it.results != null && shown(it.rows.list) }
				}
			}

			fun pictured(name: String) =
				onAllNodes(hasTestTag("picture:$name")).fetchSemanticsNodes().isNotEmpty()

			session.send(BrowserEvent.Viewed(Layout.Gallery))
			search("ext:png") { rows -> rows.map { it.name }.toSet() == setOf("a1.png", "a2.png") }
			waitUntil(timeoutMillis = TIMEOUT) { pictured("a1.png") && pictured("a2.png") }

			search(
				"is:dir",
			) { rows -> rows.any { it.name == "album" } && rows.all { it.isFolder } }
			waitForIdle()

			assertFalse(pictured("album"), "the folder album shows a picture")
			assertFalse(pictured("art"), "the folder art shows a picture")
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

	/** A finder over an index of [folder], as `prism pick --index` searches one. */
	private fun finderOf(folder: Path): Finder =
		StaticFinder(
			prism.index.Index.EMPTY.with(
				prism.index.Indexer(mounts = emptyList()).index(folder),
			),
			home = folder,
		)

	@Test
	fun ctrlShiftFSearchesTheIndexAndEnterShowsEveryMatchInTheListing() {
		val home = home()

		picking(folder = home, finder = finderOf(home)) { session ->
			ctrlShift(Key.F)
			waitForIdle()
			onNodeWithTag("search-layer").assertExists()
			focusIsIn("search-query")

			field("search-query").performTextReplacement("notes")
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("match:notes.md")).fetchSemanticsNodes().isNotEmpty()
			}

			// Enter from the field: every match, in the listing, and the window put away.
			press(Key.Enter)
			waitUntil(timeoutMillis = TIMEOUT) { session.state.value.results != null }
			waitForIdle()
			assertEquals(0, onAllNodes(hasTestTag("search-layer")).fetchSemanticsNodes().size)
			onNodeWithTag("results").assertIsDisplayed()
			row("notes.md").assertIsDisplayed()
			onNodeWithTag("status").assertTextEquals("1 result")

			// Opened again, what was typed is there.
			ctrlShift(Key.F)
			waitForIdle()
			field("search-query").assertTextEquals("notes")
		}
	}

	@Test
	fun aMatchChosenWithTheArrowsGoesToTheFileChosen() {
		val home = home()

		picking(folder = home, finder = finderOf(home)) { session ->
			ctrlShift(Key.F)
			waitForIdle()
			field("search-query").performTextReplacement("letter")
			waitUntil(timeoutMillis = TIMEOUT) {
				onAllNodes(hasTestTag("match:letter.odt")).fetchSemanticsNodes().isNotEmpty()
			}
			press(Key.DirectionDown)
			waitForIdle()
			onNodeWithTag("match:letter.odt").assertIsSelected()
			press(Key.Enter)
			waitUntil(timeoutMillis = TIMEOUT) {
				session.state.value.chosenRows
					.map { it.name } == listOf("letter.odt")
			}
			assertEquals(work.resolve("Documents"), session.state.value.folder)
			row("letter.odt").assertIsSelected()
		}
	}

	@Test
	fun withNoIndexTheSearchSaysSo() {
		picking { _ ->
			ctrlShift(Key.F)
			waitForIdle()
			onNodeWithTag("search-ok").assertIsDisplayed()
			press(Key.Escape)
			waitForIdle()
			assertEquals(0, onAllNodes(hasTestTag("search-layer")).fetchSemanticsNodes().size)
		}
	}
}
