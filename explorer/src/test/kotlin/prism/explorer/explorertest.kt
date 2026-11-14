package prism.explorer

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.browser.Actions
import prism.browser.BrowserEvent
import prism.browser.Choosing
import prism.browser.Disk
import prism.browser.JvmDisk
import prism.browser.Listing
import prism.browser.MemoryFileClipboard
import prism.browser.Request
import prism.browser.Search
import prism.browser.Start
import prism.browser.Target
import prism.browser.TypeFilter
import prism.browser.UserAction
import prism.browser.subjectOf
import prism.desktop.MimeType
import prism.launcher.Entry
import prism.launcher.LauncherEvent
import prism.preferences.ActionsFile
import prism.preferences.LauncherRows
import prism.preferences.MemoryPreferences
import prism.preferences.QuickAccess

/**
 * The explorer's windows: a browser's opened on a folder, and closed as it ends; what a browser asks for opened beside
 * it; one preferences window, raised when asked for again; and a place pinned in the preferences reaching every browser.
 */
class ExplorerTest {
	@TempDir
	lateinit var root: Path

	/** What the explorer reported, as the tests' in place of standard error: what failed, and with what. */
	private val reported = CopyOnWriteArrayList<Pair<String, Throwable>>()

	private fun explored(
		disk: Disk = JvmDisk(),
		test: suspend (Explorer, FakeDesktop, MemoryPreferences) -> Unit,
	) = runBlocking {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val desktop = FakeDesktop(root)
		val preferences = MemoryPreferences()

		val explorer =
			Explorer(
				disk,
				null,
				desktop,
				MemoryFileClipboard(),
				preferences,
				scope,
				root,
				Search.Unavailable("none"),
				report = { what, failure -> reported += what to failure },
			)

		try {
			withTimeout(10_000) { test(explorer, desktop, preferences) }
		} finally {
			scope.cancel()
		}
	}

	private val Explorer.browsers get() =
		windows.value
			.filterIsInstance<ExplorerWindow.Browsing>()

	@Test
	fun whatABrowserAsksForOpensBesideItAndItClosesAsItEnds() =
		explored { explorer, _, _ ->
			explorer.browse(root)

			val browsing = explorer.browsers.single()

			browsing.session.send(BrowserEvent.Requested(Request.Properties(listOf(root))))
			browsing.session.send(
				BrowserEvent.Requested(Request.OpenWith(listOf(root.resolve("a.txt")))),
			)
			browsing.session.send(BrowserEvent.Requested(Request.NewWindow(root)))

			val opened = explorer.windows.first { it.size == 4 }

			assertEquals(ExplorerWindow.Properties(2, listOf(root)), opened[1])
			assertEquals(
				ExplorerWindow.OpenWith(3, Choice.Open(listOf(root.resolve("a.txt")), MimeType.TEXT)),
				opened[2],
			)
			assertInstanceOf(ExplorerWindow.Browsing::class.java, opened[3])

			// For files an action of the user's opens, another application can be chosen, and no default set.
			browsing.session.send(
				BrowserEvent.Requested(
					Request.OpenWith(listOf(root.resolve("a.txt")), settable = false),
				),
			)
			assertEquals(
				ExplorerWindow.OpenWith(5, Choice.Open(listOf(root.resolve("a.txt")), null)),
				explorer.windows.first { it.size == 5 }.last(),
			)
			explorer.close(explorer.windows.value.last())

			// Ctrl+W, or the menu's "Close window": the browser ends, and its window is gone.
			browsing.session.send(BrowserEvent.Cancelled)
			explorer.windows.first { windows -> windows.none { it.id == browsing.id } }

			explorer.close(opened[1])
			assertEquals(listOf(3, 4), explorer.windows.value.map { it.id })
		}

	@Test
	fun thereIsOnePreferencesWindowRaisedWhenAskedForAgain() =
		explored { explorer, desktop, _ ->
			explorer.ask(Request.Preferences)
			explorer.ask(Request.Preferences)

			assertEquals(listOf(ExplorerWindow.Settings(1, raised = 1)), explorer.windows.value)

			explorer.chooseDefault(MimeType.TEXT)
			explorer.setDefault(MimeType.TEXT, desktop.editor)

			assertEquals(
				ExplorerWindow.OpenWith(2, Choice.Default(MimeType.TEXT)),
				explorer.windows.value.last(),
			)
			assertEquals(listOf(MimeType.TEXT to desktop.editor), desktop.defaultsSet)
			// What shows a default reads it again.
			assertEquals(1, explorer.defaults.value)
		}

	@Test
	fun theLauncherIsShownAndPutAwayByBeingAskedForAndOpensWhatItIsAskedTo() =
		explored { explorer, _, preferences ->
			suspend fun settled(what: () -> Boolean) {
				while (!what())
					delay(10)
			}

			explorer.launch()

			val launching = explorer.windows.value.single() as ExplorerWindow.Launching

			assertEquals("Launcher", Explorer.titleOf(launching))
			// The tests' desktop has one application, and the launcher lists it.
			assertEquals(
				listOf("Editor"),
				launching.session.state.value.entries
					.filterIsInstance<Entry.App>()
					.map { it.title },
			)

			// Asked for again, it is put away; and again, shown anew.
			explorer.launch()
			assertEquals(emptyList<ExplorerWindow>(), explorer.windows.value)
			explorer.launch()

			val again = explorer.windows.value.single() as ExplorerWindow.Launching

			assertTrue(again.id != launching.id)

			// Its settings changed while it is shown, it is as they now say.
			preferences.update {
				it.copy(
					launcher =
						it.launcher.copy(rows = LauncherRows.of(12), hidden = setOf("editor.desktop")),
				)
			}
			settled { again.session.state.value.preferences.rows.number == 12 }
			assertEquals(
				emptyList<Entry>(),
				again.session.state.value.entries
					.filterIsInstance<Entry.App>(),
			)

			// Its settings asked for open in a window of their own, and it is put away; asked for again, the one
			// window is raised.
			again.session.send(LauncherEvent.AskedForSettings)
			settled { explorer.windows.value.singleOrNull() is ExplorerWindow.LauncherSettings }
			explorer.launcherSettings()
			assertEquals(
				1,
				(explorer.windows.value.single() as ExplorerWindow.LauncherSettings).raised,
			)

			// What it starts is started, and it is put away; a folder it is asked for is a browser's window.
			preferences.update { it.copy(launcher = it.launcher.copy(hidden = emptySet())) }
			explorer.launch()

			val third =
				explorer.windows.value
					.filterIsInstance<ExplorerWindow.Launching>()
					.single()

			third.session.send(LauncherEvent.Typed("files"))
			third.session.send(LauncherEvent.Entered)
			settled {
				explorer.browsers.size == 1 &&
					explorer.windows.value.none { it is ExplorerWindow.Launching }
			}
			assertEquals(
				root,
				explorer.browsers
					.single()
					.session.state.value.folder,
			)
		}

	@Test
	fun aPlacePinnedReachesEveryBrowsersSidebar() =
		explored { explorer, _, preferences ->
			val projects = root.resolve("projects").createDirectories()

			explorer.browse(root)
			explorer.browse(projects)
			preferences.update { it.arranging(QuickAccess.DEFAULT.pinning(projects)) }

			for (browsing in explorer.browsers)
				browsing.session.state.first { state ->
					projects in state.sidebar.shown.map { it.path }
				}
		}

	/**
	 * A disk that reads every folder but [broken], which it fails at as a mistake in prism would that a session does
	 * not answer for: a session says of a folder that throws an exception that it cannot be read, and goes on, so this
	 * throws what is no exception.
	 */
	private fun diskFailingAt(broken: Path): Disk {
		val disk = JvmDisk()

		return object : Disk by disk {
			override fun read(folder: Path): Listing.Read =
				if (folder == broken)
					throw NotImplementedError("the disk is on fire")
				else
					disk.read(folder)
		}
	}

	@Test
	fun aWindowWhoseWorkFailsIsClosedReportedAndShownAndTheOthersGoOn() {
		val broken = root.resolve("broken").createDirectories()

		explored(diskFailingAt(broken)) { explorer, _, _ ->
			explorer.browse(root)
			explorer.browse(broken)

			val sound = explorer.browsers.first()

			// The window on the folder that cannot be read is gone, and one that says so is in its place.
			val failed =
				explorer.windows
					.first { windows -> windows.any { it is ExplorerWindow.Failed } }
					.filterIsInstance<ExplorerWindow.Failed>()
					.single()

			assertEquals("broken", failed.what)
			assertEquals("NotImplementedError: the disk is on fire", failed.failure.summary)
			assertTrue("diskFailingAt" in failed.failure.trace, failed.failure.trace)
			assertEquals(Start.Folder(broken), failed.again)
			assertEquals("the window on $broken failed", reported.single().first)
			assertEquals("the disk is on fire", reported.single().second.message)

			// The other window is as it was, and still browses.
			assertEquals(listOf(sound.id, failed.id), explorer.windows.value.map { it.id })
			sound.session.state.first { it.listing is Listing.Read }
			sound.session.send(BrowserEvent.Requested(Request.Properties(listOf(root))))
			explorer.windows.first { windows -> windows.any { it is ExplorerWindow.Properties } }
		}
	}

	@Test
	fun aWindowThatCannotBeDrawnIsClosedAndTheWindowThatSaysSoFailingIsOnlyReported() =
		explored { explorer, _, _ ->
			explorer.ask(Request.Properties(listOf(root)))
			explorer.ask(Request.Preferences)

			val (properties, settings) = explorer.windows.value

			// As the windows' thread tells it, when drawing a window throws.
			explorer.failed(properties, IllegalArgumentException("no such glyph"))

			val failed = explorer.windows.value.last() as ExplorerWindow.Failed

			assertEquals(listOf(settings, failed), explorer.windows.value)
			assertEquals("IllegalArgumentException: no such glyph", failed.failure.summary)
			// Nothing to open again: it was not browsing.
			assertEquals(null, failed.again)

			// The window that says so fails too: closed, said on standard error, and no window opened to say that.
			explorer.failed(failed, IllegalStateException("again"))
			assertEquals(listOf(settings), explorer.windows.value)
			assertEquals(2, reported.size)
			assertTrue("the window that says" in reported.last().first, reported.last().first)

			// A window already closed that is said to have failed (its last frame, say) changes nothing.
			explorer.failed(properties, IllegalStateException("late"))
			assertEquals(listOf(settings), explorer.windows.value)
		}

	@Test
	fun aPickerWhoseWindowFailsAnswersWhoeverAskedThatItFailed() =
		explored { explorer, _, _ ->
			val asking =
				Asking("Open", Choosing.OneFile(), root, listOf(TypeFilter.ALL_FILES))

			suspend fun picker(): ExplorerWindow.Picking =
				explorer.windows
					.first { windows -> windows.any { it is ExplorerWindow.Picking } }
					.filterIsInstance<ExplorerWindow.Picking>()
					.single()

			coroutineScope {
				val answer = async { runCatching { explorer.choose(asking) } }

				explorer.failed(picker(), IllegalStateException("drawn wrong"))

				val failure =
					assertInstanceOf(PickerFailed::class.java, answer.await().exceptionOrNull())

				assertEquals(
					"the picker's window failed: IllegalStateException: drawn wrong",
					failure.message,
				)
				assertTrue(explorer.windows.value.none { it is ExplorerWindow.Picking })

				// One cancelled from outside is cancelled, as it was before, and its window closed.
				val cancelled = async { explorer.choose(asking) }

				picker()
				cancelled.cancelAndJoin()
				assertTrue(cancelled.isCancelled)
				assertTrue(explorer.windows.value.none { it is ExplorerWindow.Picking })
			}
		}

	/** The one action written as [text], as the preferences would have it. */
	private fun action(text: String): UserAction =
		Actions.read(listOf(ActionsFile.parse("""{"actions": [$text]}"""))).actions.single()

	@Test
	fun aFolderAnActionAsksForIsChosenInAPickerAndGivenToTheWindowThatAsked() =
		explored { explorer, _, _ ->
			val into = root.resolve("into").createDirectories()
			val made = into.resolve("made")

			val touch =
				action(
					"""{"id": "t", "label": "Touch in…", "when": {"target": "folder"},
					"ask": {"dest": {"folder": "Touch in", "start": "${into.toString().replace(
						"\\",
						"/",
					)}"}},
					"run": ["touch", "{dest}/made"], "how": {"watched": true}}""",
				)

			explorer.browse(root)

			val browsing = explorer.browsers.single()
			val state = browsing.session.state.first { it.listing is Listing.Read }

			browsing.session.send(BrowserEvent.Did(touch, state.subjectOf(Target.Folder)))

			// A picker of folders opens beside the browser, on the folder the action says to start in.
			val picking =
				explorer.windows
					.first { windows -> windows.any { it is ExplorerWindow.Picking } }
					.filterIsInstance<ExplorerWindow.Picking>()
					.single()

			assertEquals("Touch in", picking.title)
			assertEquals(
				into,
				picking.session.state
					.first { it.listing is Listing.Read }
					.folder,
			)

			// Accepted, it gives the folder it shows; the window closes, and the command runs with the answer.
			picking.session.send(BrowserEvent.Accepted)
			explorer.windows.first { windows -> windows.none { it is ExplorerWindow.Picking } }
			withTimeout(5_000) {
				while (!made.exists())
					delay(20)
			}

			// Cancelled, nothing runs, and the browser asks nothing more.
			made.deleteExisting()
			browsing.session.send(BrowserEvent.Did(touch, state.subjectOf(Target.Folder)))
			explorer.windows
				.first { windows -> windows.any { it is ExplorerWindow.Picking } }
				.filterIsInstance<ExplorerWindow.Picking>()
				.single()
				.session
				.send(BrowserEvent.Cancelled)
			explorer.windows.first { windows -> windows.none { it is ExplorerWindow.Picking } }
			browsing.session.state.first { it.pending == null }
			assertFalse(made.exists())
		}

	@Test
	fun aPlaceOpensInAWindowOfItsOwnListedByItsCommand() =
		explored { explorer, _, preferences ->
			root.resolve("a.txt").writeText("a")
			root.resolve("b.txt").writeText("b")

			// Declared in the preferences, as the user would: every window is given them.
			val written =
				ActionsFile.parse(
					"""{"places": [{"id": "all", "title": "Everything in {where.name}", "takes": ["where"],
					"opens": "window", "list": ["ls", "{where}"], "folder": "{where}"}],
					"actions": [{"id": "all", "label": "All", "when": {"target": "folder"},
					"show": {"place": "all", "with": {"where": "{path}"}}}]}""",
				)

			preferences.update { it.copy(actions = written.actions, places = written.places) }
			explorer.browse(root)

			val browsing = explorer.browsers.single()
			val state =
				browsing.session.state.first {
					it.listing is Listing.Read &&
						it.actions["all"] != null
				}

			browsing.session.send(
				BrowserEvent.Did(state.actions["all"]!!, state.subjectOf(Target.Folder)),
			)

			val window = explorer.windows.first { it.size == 2 }.last() as ExplorerWindow.Browsing
			val shown = window.session.state.first { it.listing is Listing.Read }

			assertEquals("Everything in ${root.fileName}", shown.place?.placed?.title)
			assertEquals(listOf("a.txt", "b.txt"), shown.rows.list.map { it.name })
			// The first window is where it was.
			assertEquals(root, browsing.session.state.value.folder)
		}
}
