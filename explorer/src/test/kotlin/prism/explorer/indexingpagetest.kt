package prism.explorer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import prism.browser.BrowserTheme
import prism.browser.Finder
import prism.index.Act
import prism.index.ActivityLog
import prism.index.FeedKind
import prism.index.FileId
import prism.index.Keeping
import prism.index.Searched
import prism.index.Unfollowed
import prism.index.query.Query
import prism.platform.Dev
import prism.platform.KernelDev
import prism.preferences.IndexUpkeep
import prism.preferences.Preferences

@OptIn(ExperimentalTestApi::class)
class IndexingPageTest {
	private val home = FileId(KernelDev(Dev(8, 1)), 1234)

	/** The time the log is told it is: a moment ago, so that what is noted is within the last minute of the clock. */
	private var now = System.currentTimeMillis()

	private val log =
		ActivityLog(
			window = 5.seconds,
			keep = 60.seconds,
			perBurst = 3,
			processName = mapOf(5 to "touch", 6 to "cargo")::get,
			clock = { now },
		)

	/** An index kept here as the page is told of it: kept as [keeping] says, its log [log]. */
	private inner class Kept(
		var keeping: Keeping,
	) : Finder {
		var rebuilt = 0

		override fun find(query: Query): Searched = Searched.Refused("nothing is searched here")

		override fun picked(paths: List<Path>, at: Instant) = Unit

		override val rebuilds: Boolean get() = true

		override fun rebuild() {
			rebuilt++
		}

		override fun keeping(): Keeping = keeping

		override val activity: ActivityLog = log

		override fun folderAt(folder: FileId): String? = "/home/avery".takeIf { folder == home }
	}

	private fun ComposeUiTest.shows(tag: String, text: String) =
		waitUntil(timeoutMillis = 5_000) {
			runCatching { onNodeWithTag(tag).assertTextEquals(text) }.isSuccess
		}

	/** [text] typed over what the monitor's find field holds. */
	private fun ComposeUiTest.typed(text: String) =
		onNode(
			hasSetTextAction() and hasAnyAncestor(hasTestTag("monitor-find")),
		).performTextReplacement(text)

	private fun ComposeUiTest.count(tag: String): Int =
		onAllNodesWithTag(tag).fetchSemanticsNodes().size

	@Test
	fun thePageSaysHowTheIndexIsKept_AndItsSwitchesSetWhatTheIndexDoesOnItsOwn() =
		runComposeUiTest {
			val index =
				Kept(Keeping.Followed(FeedKind.EbpfProbe, "the watcher pinned at /sys/fs/bpf/prism"))
			var settings by mutableStateOf(Settings(Preferences.DEFAULT, page = Page.Indexing))

			setContent {
				BrowserTheme {
					SettingsView(settings, { settings = it }, onApply = {}, onClose = {}, index = index)
				}
			}

			shows("index-state", "Changes are followed as they happen by the eBPF probe.")
			shows(
				"index-state-detail",
				"Through the watcher pinned at /sys/fs/bpf/prism. Rebuilding reads every folder again.",
			)

			index.keeping =
				Keeping.Rescanned(Unfollowed.Failed("the kernel would not load it"), 30.minutes)
			shows("index-state-detail", "The kernel would not load it.")

			index.keeping = Keeping.Unkept(Unfollowed.TurnedOff)
			shows(
				"index-state",
				"The index isn't kept up to date on its own: rebuild it to bring it up to date.",
			)

			onNodeWithTag("index-rebuild").performClick()
			waitForIdle()
			assertEquals(1, index.rebuilt)

			// Each switch is a setting, applied with the rest.
			onNodeWithTag("upkeep-scan-on-start").performClick()
			onNodeWithTag("upkeep-watch").performClick()
			onNodeWithTag("upkeep-fallback").performClick()
			onNodeWithTag("upkeep-monitor").performClick()
			waitForIdle()
			assertEquals(IndexUpkeep(false, false, false, false), settings.indexing)
			assertEquals(
				IndexUpkeep(false, false, false, false),
				settings.over(Preferences.DEFAULT).indexing,
			)
		}

	@Test
	fun theMonitorShowsWhatWasHeard_FindsItByProcessThenByPath_AndHoldsItWhilePaused() =
		runComposeUiTest {
			val index =
				Kept(Keeping.Followed(FeedKind.EbpfProbe, "a watcher of this process's own"))

			log.heard {
				repeat(5) { inFolder(5, Act.FileMade, home, "touched $it".encodeToByteArray()) }
			}
			log.heard { inFolder(6, Act.FolderMade, home, "target".encodeToByteArray()) }

			setContent {
				BrowserTheme {
					SettingsView(
						Settings(Preferences.DEFAULT, page = Page.Indexing),
						{},
						onApply = {},
						onClose = {},
						index = index,
					)
				}
			}

			onNodeWithTag("index-monitor-open").performClick()
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 2 }
			// Latest first; and of five changes, the three a burst keeps.
			assertEquals(
				listOf("1 change", "3 of 5 changes"),
				onAllNodesWithTag("burst-count").fetchSemanticsNodes().map {
					it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
				},
			)

			// By the process's name; by a path only cargo's change has; and by one both have.
			typed("touch")
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 1 }
			typed("target")
			waitUntil(timeoutMillis = 5_000) {
				count("burst-count") == 1 &&
					count("burst-open:${log.live.bursts().first().id}") == 1
			}
			typed("avery")
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 2 }
			typed("")
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 2 }

			// Paused, what is heard meanwhile is noted, and not shown until the log is resumed.
			onNodeWithTag("monitor-pause").performClick()
			log.heard { inFolder(7, Act.FileRemoved, home, "gone".encodeToByteArray()) }
			Thread.sleep(1_200)
			waitForIdle()
			assertEquals(2, count("burst-count"))
			onNodeWithTag("monitor-pause").performClick()
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 3 }

			// A burst's changes, in its place; narrowed by their paths; and back to the bursts.
			val touch = log.live.bursts().single { it.seen == 5 }

			onNodeWithTag("burst-open:${touch.id}").performClick()
			waitUntil(timeoutMillis = 5_000) { count("change") == 3 }
			assertEquals(
				listOf("/home/avery/touched 0", "/home/avery/touched 1", "/home/avery/touched 2"),
				onAllNodesWithTag("change-path").fetchSemanticsNodes().map {
					it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
				},
			)
			typed("touched 1")
			waitUntil(timeoutMillis = 5_000) { count("change") == 1 }
			onNodeWithTag("monitor-back").performClick()
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 3 }
			onNodeWithTag("monitor-close").performClick()
			waitUntil(timeoutMillis = 5_000) {
				onAllNodes(hasTestTag("index-monitor")).fetchSemanticsNodes().isEmpty()
			}
		}
}
