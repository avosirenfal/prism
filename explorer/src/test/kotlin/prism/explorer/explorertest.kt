package prism.explorer

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.browser.Actions
import prism.browser.BrowserEvent
import prism.browser.JvmDisk
import prism.browser.Listing
import prism.browser.MemoryFileClipboard
import prism.browser.Request
import prism.browser.Search
import prism.browser.Target
import prism.browser.UserAction
import prism.browser.subjectOf
import prism.desktop.MimeType
import prism.preferences.ActionsFile
import prism.preferences.MemoryPreferences
import prism.preferences.QuickAccess

/**
 * The explorer's windows: a browser's opened on a folder, and closed as it ends; what a browser asks for opened beside
 * it; one preferences window, raised when asked for again; and a place pinned in the preferences reaching every browser.
 */
class ExplorerTest {
	@TempDir
	lateinit var root: Path

	private fun explored(test: suspend (Explorer, FakeDesktop, MemoryPreferences) -> Unit) =
		runBlocking {
			val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
			val desktop = FakeDesktop(root)
			val preferences = MemoryPreferences()

			val explorer =
				Explorer(
					JvmDisk(),
					null,
					desktop,
					MemoryFileClipboard(),
					preferences,
					scope,
					root,
					Search.Unavailable("none"),
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
