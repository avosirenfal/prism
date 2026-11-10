package prism.explorer

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.browser.BrowserEvent
import prism.browser.JvmDisk
import prism.browser.MemoryFileClipboard
import prism.browser.Request
import prism.browser.Search
import prism.desktop.MimeType
import prism.preferences.MemoryPreferences

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
			preferences.update { it.pinning(listOf(root, projects)) }

			for (browsing in explorer.browsers)
				browsing.session.state.first { state ->
					state.sidebar.pinned.map { it.path } ==
						listOf(root, projects)
				}
		}
}
