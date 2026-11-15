package prism.launcher

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.BrowserTheme
import prism.browser.Palette
import prism.browser.renderedPng
import prism.preferences.LauncherPreferences

/**
 * The launcher's window as a dmenu: lines given, narrowed to what is typed, and one of them answered. The reducer
 * alone first, then a session looking through its lines, then the whole of it drawn and worked as a user works it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DmenuTest {
	private val sizes =
		Dmenu(Dmenu.linesOf("25%\n50%\n75%\n100%\n"), placeholder = "Window size...")

	private fun DmenuState.after(vararg events: DmenuEvent) =
		events.fold(DmenuStep(this)) { at, event -> step(at.state, event) }

	private fun DmenuState.texts() = lines.map { it.text }

	private fun answerOf(step: DmenuStep) =
		step.effects
			.filterIsInstance<DmenuEffect.Answer>()
			.singleOrNull()
			?.answer

	/** What fits [query] among [state]'s lines, as a session would find it. */
	private fun matched(state: DmenuState, query: String) =
		DmenuEvent.Matched(query, runBlocking { matching(state.asked.lines, query) })

	@Test
	fun theLinesAreThoseWithAnythingOnThemInOrderEachWhereItIsAmongThem() {
		val lines = Dmenu.linesOf("one\n\n  two  \r\n   \nthree")

		assertEquals(listOf("one", "  two  ", "three"), lines.map { it.text })
		assertEquals(listOf(0, 1, 2), lines.map { it.at })
		assertEquals(emptyList<Dmenu.Line>(), Dmenu.linesOf(""))
	}

	@Test
	fun itOpensWithEveryLineAsGivenAndTheArrowsMoveThroughThem() {
		val opened = opened(sizes)

		assertEquals(emptyList<DmenuEffect>(), opened.effects)
		assertEquals(listOf("25%", "50%", "75%", "100%"), opened.state.texts())
		assertEquals("25%", opened.state.current?.text)
		assertTrue(opened.state.settled)

		val moved = opened.state.after(DmenuEvent.Moved(2))

		assertEquals("75%", moved.state.current?.text)
		// The arrows stop at either end.
		assertEquals(
			"100%",
			moved.state
				.after(DmenuEvent.Moved(9))
				.state.current
				?.text,
		)
		assertEquals(
			"25%",
			moved.state
				.after(DmenuEvent.MovedTo(last = false))
				.state.current
				?.text,
		)
		assertEquals(
			"100%",
			moved.state
				.after(DmenuEvent.MovedTo(last = true))
				.state.current
				?.text,
		)
	}

	@Test
	fun enterAnswersTheLineTheArrowsAreOnAndAClickTheLineClicked() {
		val state = opened(sizes).state

		assertEquals(
			DmenuAnswer.Chose(sizes.lines[1]),
			answerOf(state.after(DmenuEvent.Moved(1), DmenuEvent.Entered)),
		)
		assertEquals(
			DmenuAnswer.Chose(sizes.lines[3]),
			answerOf(state.after(DmenuEvent.Chosen(3))),
		)
		// A click where no line is answers nothing.
		assertNull(answerOf(state.after(DmenuEvent.Chosen(7))))
	}

	@Test
	fun whatIsTypedIsLookedForAndWhatIsShownIsWhatFitsItTheBestFirst() {
		val typed = opened(sizes).state.after(DmenuEvent.Typed("5"))

		// Looked for elsewhere: until it is found, what was shown is.
		assertEquals(listOf(DmenuEffect.Match("5")), typed.effects)
		assertFalse(typed.state.settled)
		assertEquals(4, typed.state.lines.size)

		val found = typed.state.after(matched(typed.state, "5"))

		// 50% starts with it; 25% and 75% only have it, in the order they were given.
		assertEquals(listOf("50%", "25%", "75%"), found.state.texts())
		assertTrue(found.state.settled)
		assertEquals("50%", found.state.current?.text)

		// What fitted something typed before is dropped.
		val later = found.state.after(DmenuEvent.Typed("10"), matched(found.state, "7"))

		assertEquals(listOf("50%", "25%", "75%"), later.state.texts())
		assertEquals(
			listOf("100%"),
			later.state
				.after(matched(later.state, "10"))
				.state
				.texts(),
		)

		// Every word typed is somewhere in the line.
		val fruit = opened(Dmenu(Dmenu.linesOf("red apple\ngreen apple\nred pear"))).state

		assertEquals(
			listOf("red apple"),
			fruit.after(DmenuEvent.Typed("app red"), matched(fruit, "app red")).state.texts(),
		)
	}

	@Test
	fun enterBeforeWhatIsTypedHasBeenLookedForWaitsAndThenAnswersWhatFitsIt() {
		val typed = opened(sizes).state.after(DmenuEvent.Typed("75"), DmenuEvent.Entered)

		// Nothing is answered of what was shown for what was typed before: that would be 25%.
		assertNull(answerOf(typed))
		assertEquals(
			DmenuAnswer.Chose(sizes.lines[2]),
			answerOf(typed.state.after(matched(typed.state, "75"))),
		)

		// Typed on meanwhile, the Enter is let go of.
		val on = typed.state.after(DmenuEvent.Typed("7"))

		assertNull(answerOf(on.state.after(matched(on.state, "7"))))
	}

	@Test
	fun enterOnTextThatFitsNoLineAnswersTheTextAndWithNothingTypedNothing() {
		val state = opened(sizes).state
		val typed = state.after(DmenuEvent.Typed("60%"), matched(state, "60%"))

		assertEquals(emptyList<String>(), typed.state.texts())
		assertEquals(DmenuAnswer.Typed("60%"), answerOf(typed.state.after(DmenuEvent.Entered)))

		// Given no lines at all, it is a question, and what is typed its answer; with nothing typed, there is none.
		val asked = opened(Dmenu(emptyList(), placeholder = "Name")).state

		assertNull(answerOf(asked.after(DmenuEvent.Entered)))

		// With no line to look through, nothing is looked for.
		val named = asked.after(DmenuEvent.Typed("ember"))

		assertEquals(emptyList<DmenuEffect>(), named.effects)
		assertTrue(named.state.settled)
		assertEquals(
			DmenuAnswer.Typed("ember"),
			answerOf(named.state.after(DmenuEvent.Entered)),
		)
	}

	@Test
	fun escapeLetsGoOfWhatIsTypedFirstAndThenPutsItAwayAndClosingItDoesAtOnce() {
		val state = opened(sizes).state
		val typed = state.after(DmenuEvent.Typed("5"), matched(state, "5"))
		val cleared = typed.state.after(DmenuEvent.Dismissed)

		assertNull(answerOf(cleared))
		assertEquals("", cleared.state.query)
		assertEquals(4, cleared.state.lines.size)
		assertEquals(DmenuAnswer.Dismissed, answerOf(cleared.state.after(DmenuEvent.Dismissed)))
		assertEquals(DmenuAnswer.Dismissed, answerOf(typed.state.after(DmenuEvent.Closed)))
	}

	@Test
	fun itOpensWithWhatItWasGivenTypedAndItsTitleCountsWhatIsShown() {
		val asked = sizes.copy(query = "5", section = "Sizes ({count})")
		val opened = opened(asked)

		assertEquals("5", opened.state.query)
		assertEquals(listOf(DmenuEffect.Match("5")), opened.effects)
		assertEquals("Sizes (4)", opened.state.heading)
		assertEquals(
			"Sizes (3)",
			opened.state
				.after(matched(opened.state, "5"))
				.state.heading,
		)
		// No title over no lines.
		assertNull(
			opened.state
				.after(DmenuEvent.Typed("x"), matched(opened.state, "x"))
				.state.heading,
		)
	}

	@Test
	fun aSessionLooksThroughAGreatManyLinesAndAnswersOnce() {
		val scope = CoroutineScope(Dispatchers.Default)

		try {
			val many = Dmenu(Dmenu.linesOf((1..100_000).joinToString("\n") { "line number $it" }))
			val session = DmenuSession(many, scope)

			session.send(DmenuEvent.Typed("99999"))
			session.send(DmenuEvent.Entered)

			val answer = runBlocking { withTimeout(20_000) { session.answer.await() } }

			assertEquals("line number 99999", (answer as DmenuAnswer.Chose).line.text)
			assertEquals(99_998, answer.line.at)
			// What comes after is nobody's answer.
			session.send(DmenuEvent.Closed)
			assertEquals(answer, session.answer.getCompleted())
		} finally {
			scope.cancel()
		}
	}

	/** [asked] drawn, in a session of its own; what it answered kept. */
	private fun showing(
		asked: Dmenu,
		preferences: LauncherPreferences = LauncherPreferences(),
		check: ComposeUiTest.(DmenuSession) -> Unit,
	) = runComposeUiTest {
		val running = CompletableDeferred<DmenuSession>()

		setContent {
			val scope = rememberCoroutineScope()

			val session =
				remember { DmenuSession(asked, scope, preferences).also(running::complete) }

			BrowserTheme { DmenuView(session, launcherBindings()) }
		}
		waitForIdle()
		check(running.getCompleted())
	}

	private fun ComposeUiTest.press(key: Key) =
		onNodeWithTag("launcher-query").performKeyInput { pressKey(key) }

	private fun ComposeUiTest.until(what: () -> Boolean) =
		waitUntil(timeoutMillis = 10_000, condition = what)

	private fun ComposeUiTest.shown(tag: String) =
		onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

	@Test
	fun drawnItIsTypedIntoMovedThroughWithTheLaunchersKeysAndEnterAnswers() =
		showing(sizes) { session ->
			onNodeWithTag("launcher-query").assertIsFocused()
			onNodeWithTag("launcher-placeholder").assertTextEquals("Window size...")
			onNodeWithTag("line:0").assertIsSelected()
			onNodeWithTag("launcher-status").assertTextEquals("4 items")

			press(Key.DirectionDown)
			waitForIdle()
			onNodeWithTag("line:1").assertIsSelected()

			onNodeWithTag("launcher-query").performTextInput("7")
			until { session.state.value.settled }
			waitForIdle()
			assertFalse(shown("line:0"))
			onNodeWithTag("line:2").assertIsSelected()
			onNodeWithTag("launcher-status").assertTextEquals("1 of 4")

			press(Key.Enter)
			until { session.answer.isCompleted }
			assertEquals(DmenuAnswer.Chose(sizes.lines[2]), session.answer.getCompleted())
		}

	@Test
	fun drawnALineClickedIsTheAnswerAndTextThatFitsNoneSaysWhatEnterDoes() {
		showing(sizes) { session ->
			onNodeWithTag("line:3").performClick()
			until { session.answer.isCompleted }
			assertEquals(DmenuAnswer.Chose(sizes.lines[3]), session.answer.getCompleted())
		}
		showing(sizes) { session ->
			onNodeWithTag("launcher-query").performTextInput("60%")
			until { session.state.value.settled }
			waitForIdle()
			onNodeWithTag("launcher-nothing").assertTextEquals("Press Enter to use “60%”.")
			// Escape lets go of what is typed, and the lines are back; again, and it is put away.
			press(Key.Escape)
			waitForIdle()
			onNodeWithTag("line:0").assertIsSelected()
			assertFalse(session.answer.isCompleted)
			press(Key.Escape)
			until { session.answer.isCompleted }
			assertEquals(DmenuAnswer.Dismissed, session.answer.getCompleted())
		}
	}

	@Test
	fun theKeysThatAreTheLaunchersAloneDoNothingAndTheFooterCanBeLeftOut() {
		showing(sizes.copy(footer = false, title = "Sizes")) { session ->
			assertFalse(shown("launcher-status"))
			// Its settings' key, and the one for a result's menu, are nobody's here.
			onNodeWithTag("launcher-query").performKeyInput {
				keyDown(Key.CtrlLeft)
				pressKey(Key.P)
				keyUp(Key.CtrlLeft)
			}
			press(Key.DirectionRight)
			waitForIdle()
			assertFalse(session.answer.isCompleted)
			assertEquals(0, session.state.value.highlighted)
		}
		showing(sizes.copy(title = "Sizes")) {
			onNodeWithTag("launcher-status").assertTextEquals("Sizes · 4 items")
		}
	}

	@Test
	fun picturesOfItForAPersonToLookAt() {
		val state = opened(sizes).state.arrowsAt(1)
		val typed = state.after(DmenuEvent.Typed("5"), matched(state, "5")).state
		val nothing = state.after(DmenuEvent.Typed("60%"), matched(state, "60%")).state

		val titled =
			opened(
				Dmenu(
					Dmenu.linesOf("Lock\nLog out\nSuspend\nRestart\nShut down"),
					placeholder = "Power…",
					section = "Session ({count})",
					title = "Power menu",
				),
			).state

		val secret =
			opened(Dmenu(emptyList(), placeholder = "Passphrase", redacted = true))
				.state
				.after(DmenuEvent.Typed("hunter2"))
				.state

		val drawn =
			mapOf(
				"dmenu" to state,
				"dmenu-typed" to typed,
				"dmenu-nothing" to nothing,
				"dmenu-titled" to titled,
				"dmenu-password" to secret,
			)

		val (width, height) = LauncherDims.sizeOf(state.preferences)

		for ((name, shown) in drawn)
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png =
					renderedPng(width.value.toInt(), height.value.toInt(), palette, scale = 2f) {
						DmenuPane(shown, corners = Corners.Round)
					}

				assertTrue(png.size > 10_000, "$name: ${png.size} bytes")
				System.getProperty("launcher.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
					Path
						.of(dir)
						.also { it.createDirectories() }
						.resolve("launcher-$name-$tone.png")
						.toFile()
						.writeBytes(png)
				}
			}
	}
}
