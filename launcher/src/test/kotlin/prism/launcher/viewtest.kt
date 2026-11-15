package prism.launcher

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.browser.BrowserTheme
import prism.browser.Category
import prism.browser.Finder
import prism.browser.Palette
import prism.browser.StaticFinder
import prism.browser.renderedPng
import prism.desktop.Activation
import prism.desktop.Icons
import prism.desktop.freedesktop.Freedesktop
import prism.index.Index
import prism.index.Indexer
import prism.index.Searched
import prism.index.query.Own
import prism.index.query.Query
import prism.window.OpenWindow
import prism.window.OpenWindows

/**
 * The launcher drawn and worked as a user works it: typed into, moved through with the arrows, and what is chosen
 * started; its icons drawn from the files an icon theme has; and pictures of it, for a person to look at.
 */
@OptIn(ExperimentalTestApi::class)
class LauncherViewTest {
	@TempDir
	lateinit var home: Path

	private val now = Instant.parse("2026-11-14T10:00:00Z")

	private fun made(vararg files: String) =
		files.forEach {
			home.resolve(it).also { path -> path.parent.createDirectories() }.writeText(it)
		}

	/** A launcher over the tests' desktop and an index of [home], drawn; what it started and asked for kept. */
	private fun launching(
		searching: Finder? = null,
		report: (
			String,
			Throwable,
		) -> Unit = { doing, failure -> throw AssertionError(doing, failure) },
		windows: OpenWindows = OpenWindows.Unavailable,
		check: ComposeUiTest.(LauncherSession, FakeDesktop, FakeHost, MemoryLaunches) -> Unit,
	) = runComposeUiTest {
		val desktop = FakeDesktop(home)
		val host = FakeHost()
		val launches = MemoryLaunches()

		val finder =
			searching
				?: StaticFinder(
					Index.EMPTY.with(Indexer(mounts = emptyList()).index(home)),
					home = home,
				)

		val running = arrayOfNulls<LauncherSession>(1)

		setContent {
			val scope = rememberCoroutineScope()

			val session =
				remember {
					LauncherSession(
						LauncherServices(
							desktop,
							finder,
							launches,
							home,
							clock = Clock.fixed(now, ZoneOffset.UTC),
							windows = { windows },
							report = report,
						),
						host,
						scope,
					).also { running[0] = it }
				}

			BrowserTheme { LauncherView(session, launcherBindings()) }
		}
		waitForIdle()
		check(running[0]!!, desktop, host, launches)
	}

	private fun ComposeUiTest.press(key: Key) =
		onNodeWithTag("launcher-query").performKeyInput { pressKey(key) }

	private fun ComposeUiTest.until(what: () -> Boolean) =
		waitUntil(timeoutMillis = 10_000, condition = what)

	@Test
	fun whatIsTypedIsFoundTheArrowsMoveThroughItAndEnterStartsIt() =
		launching { session, desktop, host, launches ->
			onNodeWithTag("launcher-query").performTextInput("fire")
			waitForIdle()
			assertEquals(
				listOf("Firefox", "Firewall Configuration"),
				session.state.value.entries
					.take(2)
					.map { it.title },
			)
			onNodeWithTag("entry:app:firefox.desktop").assertIsSelected()

			// Down, and the row after is the one; Up, and the first is again.
			press(Key.DirectionDown)
			waitForIdle()
			onNodeWithTag("entry:app:firewall.desktop").assertIsSelected()
			press(Key.DirectionUp)
			waitForIdle()
			onNodeWithTag("entry:app:firefox.desktop").assertIsSelected()

			press(Key.Enter)
			until { "close" in host.asked }
			assertEquals(listOf(Programs.firefox to emptyList<Path>()), desktop.started.toList())
			assertEquals(1.0, launches.launches.weightOf("app:firefox.desktop", now), 1e-9)
		}

	@Test
	fun filesAreFoundAfterTheApplicationsAndOpenedWithWhatOpensThemAndAFolderInAWindow() {
		made("Documents/firewall-rules.txt", "Projects/fireworks/readme.md")

		launching { session, desktop, host, _ ->
			onNodeWithTag("launcher-query").performTextInput("fire")
			until {
				session.state.value.entries
					.any { it is Entry.File }
			}

			val entries = session.state.value.entries

			// Applications first, then the files and folders the index holds.
			assertTrue(
				entries.indexOfFirst {
					it is Entry.File
				} > entries.indexOfLast { it is Entry.App },
			)
			assertEquals(
				setOf("firewall-rules.txt", "fireworks"),
				entries.filterIsInstance<Entry.File>().map { it.title }.toSet(),
			)

			val file = entries.indexOfFirst { it.title == "firewall-rules.txt" }

			repeat(file) { press(Key.DirectionDown) }
			waitForIdle()
			press(Key.Enter)
			until { "close" in host.asked }
			assertEquals(
				listOf(Programs.editor to listOf(home.resolve("Documents/firewall-rules.txt"))),
				desktop.started.toList(),
			)
		}
		launching { session, desktop, host, _ ->
			onNodeWithTag("launcher-query").performTextInput("fireworks")
			until {
				session.state.value.entries
					.any { it is Entry.File }
			}
			press(Key.Enter)
			until { "close" in host.asked }
			assertEquals(
				listOf("browse ${home.resolve("Projects/fireworks")}", "close"),
				host.asked.toList(),
			)
			assertEquals(emptyList<Any>(), desktop.started.toList())
		}
	}

	@Test
	fun aSearchThatGoesWrongIsReportedAndSaidAndTheLauncherGoesOnFindingApplications() {
		val reported = CopyOnWriteArrayList<Pair<String, Throwable>>()

		val broken =
			object : Finder {
				override fun find(query: Query): Searched =
					throw IndexOutOfBoundsException("Index -1")

				override fun picked(paths: List<Path>, at: Instant) = Unit
			}

		launching(
			broken,
			report = { doing, failure -> reported += doing to failure },
		) { session, _, _, _ ->
			onNodeWithTag("launcher-query").performTextInput("fire")
			until { session.state.value.files is FilesFound.Failed }

			// Said as prism's mistake, with nothing of the exception's own words; and reported in full, once.
			onNodeWithTag(
				"launcher-status",
			).assertTextContains("bug in prism", substring = true)
			assertEquals(listOf("searching for “fire”"), reported.map { it.first })
			assertInstanceOf(IndexOutOfBoundsException::class.java, reported.single().second)
			// The launcher is as it was otherwise: what is installed is found, and nothing is being waited for.
			assertEquals(
				"Firefox",
				session.state.value.current
					?.title,
			)
			assertFalse(session.state.value.searching)
		}
	}

	@Test
	fun theKeysShowAResultInItsFolderOpenTheSettingsAndPutTheLauncherAway() {
		made("Documents/notes.md")

		launching { session, _, host, _ ->
			onNodeWithTag("launcher-query").performTextInput("notes")
			until {
				session.state.value.entries
					.any { it is Entry.File }
			}
			onNodeWithTag("launcher-query").performKeyInput {
				withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) }
			}
			until { "close" in host.asked }
			assertEquals(
				listOf("reveal ${home.resolve("Documents/notes.md")}", "close"),
				host.asked.toList(),
			)
		}
		launching { _, _, host, _ ->
			onNodeWithTag("launcher-query").performKeyInput {
				withKeyDown(Key.CtrlLeft) { pressKey(Key.P) }
			}
			until { "close" in host.asked }
			assertEquals(listOf("settings", "close"), host.asked.toList())
		}
		launching { session, _, host, _ ->
			// Escape lets go of what is typed first, and then of the launcher.
			onNodeWithTag("launcher-query").performTextInput("fire")
			waitForIdle()
			press(Key.Escape)
			waitForIdle()
			assertEquals("", session.state.value.query)
			assertEquals(emptyList<String>(), host.asked.toList())
			press(Key.Escape)
			until { "close" in host.asked }
		}
	}

	@Test
	fun prismsOwnAreFoundByTheWordPrismAndDoneByWhoeverShowsTheLauncher() {
		launching { session, _, host, _ ->
			onNodeWithTag("launcher-query").performTextInput("prism")
			waitForIdle()
			// Every one of them, first, the arrows on the first.
			assertEquals(
				listOf("Settings", "Key bindings", "System", "Search help"),
				session.state.value.entries
					.filterIsInstance<Entry.Prism>()
					.map { it.title },
			)
			onNodeWithTag("entry:own:Settings").assertIsSelected()
			press(Key.DirectionDown)
			waitForIdle()
			onNodeWithTag("entry:own:Key bindings").assertIsSelected()
			press(Key.Enter)
			until { "close" in host.asked }
			assertEquals(listOf("bindings", "close"), host.asked.toList())
		}
		launching { _, _, host, _ ->
			onNodeWithTag("launcher-query").performTextInput("prism set")
			waitForIdle()
			press(Key.Enter)
			until { "close" in host.asked }
			assertEquals(listOf("settings", "close"), host.asked.toList())
		}
		launching { _, _, host, _ ->
			onNodeWithTag("launcher-query").performTextInput("prism system")
			waitForIdle()
			onNodeWithTag("entry:own:System").performClick()
			until { "close" in host.asked }
			assertEquals(listOf("system", "close"), host.asked.toList())
		}
	}

	@Test
	fun aBareQuestionMarkShowsHowAQueryIsWrittenInPlaceOfTheFieldAndWhatWasFound() =
		launching { session, _, host, _ ->
			fun shown(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

			onNodeWithTag("launcher-query").performTextInput("?")
			waitForIdle()
			onNodeWithTag("entry:own:Search help").assertIsSelected()
			assertEquals(0, shown("search-help-shown"))
			press(Key.Enter)
			until { session.state.value.help }
			waitForIdle()

			// The page, and neither the field nor what was found; the launcher is still there.
			assertEquals(1, shown("search-help-shown"))
			assertEquals(0, shown("launcher-query"))
			assertEquals(0, shown("launcher-results"))
			assertEquals(0, shown("launcher-status"))
			assertEquals(emptyList<String>(), host.asked.toList())

			// Enter and the arrows are the page's now: nothing found is chosen or moved to behind it.
			onNodeWithTag("search-help-shown").performKeyInput {
				pressKey(Key.DirectionDown)
				pressKey(Key.Enter)
			}
			waitForIdle()
			assertTrue(session.state.value.help)
			assertEquals(Entry.Prism(Own.Help), session.state.value.current)

			// Escape leaves it for the launcher as it was, the keyboard in its field again.
			onNodeWithTag("search-help-shown").performKeyInput { pressKey(Key.Escape) }
			until { !session.state.value.help }
			waitForIdle()
			assertEquals(0, shown("search-help-shown"))
			assertEquals("?", session.state.value.query)
			onNodeWithTag("launcher-query").assertIsFocused()
			assertEquals(emptyList<String>(), host.asked.toList())

			// Its ×, likewise.
			press(Key.Enter)
			until { session.state.value.help }
			waitForIdle()
			onNodeWithTag("search-help-close").performClick()
			until { !session.state.value.help }
			waitForIdle()
			onNodeWithTag("launcher-query").assertIsFocused()

			// A query of the page's clicked is typed into the field, and the page left.
			press(Key.Enter)
			until { session.state.value.help }
			waitForIdle()
			onNodeWithTag("help:is:dir").performClick()
			until { !session.state.value.help }
			waitForIdle()
			assertEquals("is:dir", session.state.value.query)
			onNodeWithTag("launcher-query").assertTextContains("is:dir")
			onNodeWithTag("launcher-query").assertIsFocused()
		}

	@Test
	fun aRowClickedIsChosenAndOneThatCannotBeStartedSaysWhy() =
		launching { session, desktop, host, _ ->
			desktop.failing = "It is not installed."
			onNodeWithTag("launcher-query").performTextInput("foot")
			waitForIdle()
			onNodeWithTag("entry:app:foot.desktop").performClick()
			until { session.state.value.notice != null }
			assertEquals(
				LauncherNotice.Failed("Couldn't start Foot. It is not installed."),
				session.state.value.notice,
			)
			assertEquals(emptyList<String>(), host.asked.toList())

			desktop.failing = null
			onNodeWithTag("entry:app:foot.desktop").performClick()
			until { "close" in host.asked }
		}

	@Test
	fun theResultsScrollToKeepTheOneTheArrowsAreOnInSight() =
		launching { session, desktop, _, _ ->
			desktop.installed =
				(1..40).map { Programs.application("app%02d".format(it), "Program %02d".format(it)) }
			session.send(LauncherEvent.ApplicationsRead(desktop.applications.all()))
			waitForIdle()
			assertEquals(
				0,
				onAllNodes(hasTestTag("entry:app:app30.desktop")).fetchSemanticsNodes().size,
			)

			repeat(29) { press(Key.DirectionDown) }
			waitForIdle()
			onNodeWithTag("entry:app:app30.desktop").assertIsSelected()
			// And back up, a page at a time.
			repeat(6) { press(Key.PageUp) }
			waitForIdle()
			onNodeWithTag("entry:app:app01.desktop").assertIsSelected()
		}

	@Test
	fun anIconIsDrawnFromAPngOrAnSvgAtTheSizeAskedForAndKept(
		@TempDir icons: Path,
	) {
		val png = icons.resolve("round.png")
		val svg = icons.resolve("square.svg")

		Surface.makeRasterN32Premul(64, 32).use { surface ->
			surface.canvas.clear(0xFF3366CC.toInt())
			Files.write(
				png,
				surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes,
			)
		}
		svg.writeText(
			"""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16">""" +
				"""<rect width="16" height="16" fill="#cc3333"/></svg>""",
		)
		icons.resolve("broken.png").writeText("not a picture")

		// A PNG keeps its shape within the square; an SVG with no view box is drawn from its own size, to fill it.
		assertEquals(48 to 24, IconImages.drawn(png, 48)!!.let { it.width to it.height })

		val square = IconImages.drawn(svg, 48)!!

		assertEquals(48 to 48, square.width to square.height)
		assertNull(IconImages.drawn(icons.resolve("broken.png"), 48))
		assertNull(IconImages.drawn(icons.resolve("missing.png"), 48))

		var asked = 0

		val images =
			IconImages(
				Icons { name, _, _ ->
					asked++
					icons.resolve("$name.png").takeIf(Files::exists)
				},
			)

		assertNull(images.kept("round", 48))
		runBlocking {
			assertNotNull(images.of("round", 48))
			assertNotNull(images.of("round", 48))
			assertNull(images.of("nothing", 48))
			assertNull(images.of("nothing", 48))
		}
		// Each is looked for once, whether or not it was found.
		assertEquals(2, asked)
		assertNotNull(images.kept("round", 48))
	}

	@Test
	fun theLauncherDraws() {
		// What this machine has installed, with the pictures its icon theme has for them, among the tests' own.
		val desktop = Freedesktop(activation = Activation.None)
		val images = IconImages(desktop.icons)
		val installed = desktop.applications.all()
		val applications = (Programs.all + installed).sortedBy { it.name.lowercase() }

		runBlocking { applications.forEach { app -> app.icon?.let { images.of(it, 56) } } }

		val first =
			LauncherState(
				installed = Installed.of(applications),
				launches =
					Launches.NONE
						.used(Entry.App(Programs.firefox).key, now)
						.used(Entry.App(Programs.terminal).key, now),
				now = now,
			)

		val typed =
			step(
				step(first, LauncherEvent.Typed("re")).state,
				LauncherEvent.FilesArrived(
					"re",
					listOf(
						Entry.File(
							home.resolve("Documents/Quarterly report.pdf"),
							Category.Pdf,
							"~/Documents",
							"PDF document",
						),
						Entry.File(
							home.resolve("Projects/prism/README.md"),
							Category.Text,
							"~/Projects/prism",
							"Markdown document",
						),
						Entry.File(
							home.resolve("Pictures/Screenshots"),
							Category.Folder,
							"~/Pictures",
							"Folder",
						),
						Entry.File(
							home.resolve("Music/recording.flac"),
							Category.Audio,
							"~/Music",
							"FLAC audio",
						),
					),
					1284,
				),
			).state.let { step(it, LauncherEvent.Moved(1)).state }

		val nothing = step(first.copy(searchable = false), LauncherEvent.Typed("zzzz")).state

		val failed =
			step(
				typed,
				LauncherEvent.Started(
					typed.entries.first(),
					now,
					"Couldn't start Firefox. No such file.",
				),
			).state

		val own =
			step(
				step(first, LauncherEvent.Typed("prism")).state,
				LauncherEvent.FilesArrived("prism", emptyList(), 0),
			).state

		val help =
			step(
				step(first, LauncherEvent.Typed("?")).state,
				LauncherEvent.Entered,
			).state

		assertTrue(help.help)

		// A result's menu: an application's, under its row; one at the foot of the window, where it has no room under
		// its row and is over it; and a file's.
		val actions =
			step(step(first, LauncherEvent.MenuOpened()).state, LauncherEvent.Moved(1)).state

		val above = step(typed, LauncherEvent.MenuOpened(8)).state

		val file =
			step(
				step(first, LauncherEvent.Typed("quarterly")).state,
				LauncherEvent.FilesArrived(
					"quarterly",
					typed.entries.filterIsInstance<Entry.File>().take(1),
					1,
				),
			).state.let { found ->
				step(
					found,
					LauncherEvent.MenuOpened(found.entries.indexOfFirst { it is Entry.File }),
				).state
			}

		assertTrue(file.menu?.entry is Entry.File)

		// An application that is running: marked by its icon, and its window brought forward from its menu.
		val running =
			step(
				first.copy(
					installed =
						Installed.of(
							applications,
							listOf(
								OpenWindow(1, "firefox", "Mozilla Firefox"),
								OpenWindow(2, "foot", "~"),
							),
						),
				),
				LauncherEvent.MenuOpened(),
			).state.let { step(it, LauncherEvent.Moved(1)).state }

		assertTrue(running.menu?.actions?.current is EntryAction.Focus)

		val drawn =
			mapOf(
				"first" to first,
				"typed" to typed,
				"nothing" to nothing,
				"failed" to failed,
				"prism" to own,
				"help" to help,
				"actions" to actions,
				"actions-above" to above,
				"actions-file" to file,
				"actions-running" to running,
			)

		val (width, height) = LauncherDims.sizeOf(first.preferences)

		for ((name, state) in drawn)
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png =
					renderedPng(width.value.toInt(), height.value.toInt(), palette, scale = 2f) {
						CompositionLocalProvider(LocalIconImages provides images) {
							// As it is where its window can be clear: the picture is clear at its corners.
							LauncherPane(state, corners = Corners.Round)
						}
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

	@Test
	fun theRightArrowShowsWhatCanBeDoneToAResultAndLeftAndEscapeLeaveOnlyTheMenu() =
		launching { session, _, host, _ ->
			fun shown(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

			fun bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

			// The menu is just under the row it is of, and inside the window.
			fun under(row: String): Boolean =
				(bounds("actions").top - bounds(row).bottom) in 0f..8f &&
					bounds("actions").right <= bounds("launcher").right

			onNodeWithTag("launcher-query").performTextInput("fire")
			waitForIdle()
			onNodeWithTag("entry:app:firefox.desktop").assertIsSelected()

			// The caret is at the end of what is typed: the right arrow shows the result's actions, by its row.
			press(Key.DirectionRight)
			waitForIdle()
			onNodeWithTag("actions").assertIsDisplayed()
			onNodeWithTag("actions:Open").assertIsSelected()
			assertEquals(
				"Firefox",
				session.state.value.menu
					?.entry
					?.title,
			)
			assertTrue(under("entry:app:firefox.desktop"))

			// The arrows are the menu's while it is open.
			press(Key.DirectionDown)
			waitForIdle()
			onNodeWithTag("actions:New Window").assertIsSelected()
			onNodeWithTag("entry:app:firefox.desktop").assertIsSelected()

			// The left arrow puts the menu away, and only the menu.
			press(Key.DirectionLeft)
			waitForIdle()
			assertFalse(shown("actions"))
			assertEquals("fire", session.state.value.query)

			// With the menu away the left arrow is the caret's, and the right arrow too until the caret is at the
			// end of what is typed again.
			press(Key.DirectionLeft)
			waitForIdle()
			press(Key.DirectionRight)
			waitForIdle()
			assertFalse(shown("actions"))
			press(Key.DirectionRight)
			waitForIdle()
			onNodeWithTag("actions").assertIsDisplayed()

			// Escape puts the menu away, and neither clears what is typed nor closes the launcher.
			press(Key.Escape)
			waitForIdle()
			assertFalse(shown("actions"))
			assertEquals("fire", session.state.value.query)
			assertEquals(emptyList<String>(), host.asked.toList())

			// A right click on a result shows its actions too, and a click on one does it.
			onNodeWithTag("entry:app:firewall.desktop").performMouseInput { rightClick() }
			waitForIdle()
			assertEquals(
				"Firewall Configuration",
				session.state.value.menu
					?.entry
					?.title,
			)
			assertTrue(under("entry:app:firewall.desktop"))
			onNodeWithTag("actions:Copy desktop entry path").performClick()
			until { "close" in host.asked }
			assertEquals(
				listOf("copy /usr/share/applications/firewall.desktop", "close"),
				host.asked.toList(),
			)
		}

	@Test
	fun anApplicationThatIsRunningIsMarkedAndItsWindowBroughtForwardFromItsMenu() {
		val window = OpenWindow(7, "foot", "~")
		val focused = CopyOnWriteArrayList<OpenWindow>()

		val open =
			object : OpenWindows.Listed() {
				override fun now() = listOf(window)

				override fun focus(window: OpenWindow) {
					focused += window
				}
			}

		launching(windows = open) { _, desktop, host, _ ->
			onNodeWithTag("launcher-query").performTextInput("foot")
			waitForIdle()
			onNodeWithTag("entry:app:foot.desktop").assertIsSelected()
			// Marked as running, by its icon.
			onNodeWithTag("running:app:foot.desktop", useUnmergedTree = true).assertExists()

			press(Key.DirectionRight)
			waitForIdle()
			onNodeWithTag("actions:Open").assertIsSelected()
			onNodeWithTag("actions:Focus window").performClick()
			until { "close" in host.asked }
			// Its window was brought forward, and it was not started again.
			assertEquals(listOf(window), focused.toList())
			assertEquals(emptyList<Any>(), desktop.started.toList())
		}
	}

	@Test
	fun whereTheDesktopTellsOfNoWindowsNoneIsMarkedAndNoneOfferedToBeBroughtForward() =
		launching { _, _, _, _ ->
			fun shown(tag: String) =
				onAllNodes(hasTestTag(tag), useUnmergedTree = true)
					.fetchSemanticsNodes()
					.isNotEmpty()

			onNodeWithTag("launcher-query").performTextInput("foot")
			waitForIdle()
			onNodeWithTag("entry:app:foot.desktop").assertIsSelected()
			assertFalse(shown("running:app:foot.desktop"))

			press(Key.DirectionRight)
			waitForIdle()
			onNodeWithTag("actions:Open").assertIsSelected()
			assertFalse(shown("actions:Focus window"))
		}

	@Test
	fun whatIsDoneFromAMenuAndStaysSaysWhatCameOfIt() =
		launching { session, _, host, _ ->
			onNodeWithTag("launcher-query").performTextInput("firefox")
			waitForIdle()
			press(Key.DirectionRight)
			waitForIdle()
			onNodeWithTag("actions:Hide from launcher").performClick()
			until { session.state.value.notice != null }
			waitForIdle()
			onNodeWithTag("launcher-status")
				.assertTextEquals("“Firefox” is hidden. Show it again in Settings.")
			assertEquals(listOf("hide firefox.desktop"), host.asked.toList())
		}
}
