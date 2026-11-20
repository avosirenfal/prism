package prism.explorer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.BrowserTheme
import prism.browser.Finder
import prism.browser.IndexSource
import prism.browser.LocalDateFormat
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
import prism.preferences.DateFormat
import prism.preferences.IndexUpkeep
import prism.preferences.Preferences
import prism.preferences.TimePrecision

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
			processName = mapOf(5 to "touch", 6 to "cargo", 7 to "rsync")::get,
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

	/** What the Index card says, line by line: its title, what is said under it, and its file. */
	private fun ComposeUiTest.said(): List<String> =
		onNodeWithTag(
			"index-state",
		).fetchSemanticsNode()
			.config
			.getOrElse(SemanticsProperties.Text) {
				emptyList()
			}.map { it.text }

	/** Waits for the Index card to say [lines] first. */
	private fun ComposeUiTest.shows(vararg lines: String) =
		waitUntil(timeoutMillis = 5_000) {
			runCatching { said().take(lines.size) == lines.toList() }.getOrDefault(false)
		}

	/** [text] typed over what the monitor's find field holds. */
	private fun ComposeUiTest.typed(text: String) =
		onNode(
			hasSetTextAction() and hasAnyAncestor(hasTestTag("monitor-find")),
		).performTextReplacement(text)

	/** How many nodes are tagged [tag], within a row (whose clicking merges what is in it) or not. */
	private fun ComposeUiTest.count(tag: String): Int =
		onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

	/**
	 * The texts of the nodes tagged [tag], each node's joined: of the text itself, within a row or not; or, [merged],
	 * of a node that merges its descendants' (a notice).
	 */
	private fun ComposeUiTest.texts(tag: String, merged: Boolean = false): List<String> =
		onAllNodesWithTag(tag, useUnmergedTree = !merged).fetchSemanticsNodes().map {
			it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString()
		}

	@Test
	fun theIndexCardSaysHowTheIndexIsKept_AndTheSwitchesSetWhatItDoesOnItsOwn() =
		runComposeUiTest {
			val index =
				Kept(Keeping.Followed(FeedKind.EbpfProbe, "probe pinned at /run/prism-watcher"))
			var settings by mutableStateOf(Settings(Preferences.DEFAULT, page = Page.Indexing))

			setContent {
				BrowserTheme {
					SettingsView(settings, { settings = it }, onApply = {}, onClose = {}, index = index)
				}
			}

			shows("Live monitoring via eBPF", "Probe pinned at /run/prism-watcher")

			index.keeping = Keeping.Rescanned(Unfollowed.TurnedOff, 30.minutes, 12.minutes)
			shows("Periodic rescan, next in 12 minutes", "Live monitoring is off.")

			index.keeping =
				Keeping.Rescanned(Unfollowed.Failed("the kernel refused it"), 30.minutes, 30.seconds)
			waitUntil(timeoutMillis = 5_000) {
				texts(
					"index-state",
					merged = true,
				).singleOrNull()?.startsWith("Periodic rescan, next in under a minute") ==
					true
			}

			index.keeping = Keeping.Unkept(Unfollowed.TurnedOff)
			shows(
				"Indexing disabled",
				"Live monitoring and periodic rescan are off. The index only changes when rebuilt.",
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
	fun theMonitorShowsWhatWasHeard_SearchesAndFiltersIt_HoldsItWhilePaused_AndCopiesIt() =
		runComposeUiTest {
			val index = Kept(Keeping.Followed(FeedKind.EbpfProbe, "probe loaded by prism"))
			val copied = mutableListOf<String>()

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
						onCopy = { copied += it },
						index = index,
					)
				}
			}

			onNodeWithTag("index-monitor-open").performClick()
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 2 }
			// Latest first; and of five events, the three a group keeps.
			assertEquals(listOf("1", "5 (3 kept)"), texts("burst-count"))

			// By the process's name; by a path only cargo's event has; and by one both have.
			typed("touch")
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 1 }
			typed("target")
			waitUntil(timeoutMillis = 5_000) {
				count("burst-count") == 1 && count("burst:${log.live.bursts().first().id}") == 1
			}
			typed("avery")
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 2 }
			typed("")
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 2 }

			// Narrowed to deletions there are none of; then to creations, which both made.
			onNodeWithTag("monitor-kind:Deleted").performClick()
			waitUntil(timeoutMillis = 5_000) { count("monitor-bursts-empty") == 1 }
			onNodeWithTag("monitor-kind:Deleted").performClick()
			onNodeWithTag("monitor-kind:Created").performClick()
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 2 }
			onNodeWithTag("monitor-kind:Created").performClick()

			// Paused, what is heard meanwhile is recorded, and not shown until the log is resumed.
			onNodeWithTag("monitor-pause").performClick()
			waitUntil(timeoutMillis = 5_000) {
				texts("monitor-state").single().startsWith("Paused at ")
			}
			log.heard { inFolder(7, Act.FileRemoved, home, "gone".encodeToByteArray()) }
			Thread.sleep(1_200)
			waitForIdle()
			assertEquals(2, count("burst-count"))
			onNodeWithTag("monitor-pause").performClick()
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 3 }

			// The whole table copied, a line a group, latest first.
			onNodeWithTag("monitor-copy-list").performClick()
			waitForIdle()
			assertEquals(
				listOf("rsync\t7\t1", "cargo\t6\t1", "touch\t5\t5"),
				copied.last().lines().map { it.substringAfter('\t') },
			)

			// A group's events, in its place; filtered by their paths; their PID and paths copied; and back.
			val touch = log.live.bursts().single { it.seen == 5 }

			onNodeWithTag("burst:${touch.id}").performClick()
			waitUntil(timeoutMillis = 5_000) { count("change") == 3 }
			assertEquals(
				listOf("/home/avery/touched 0", "/home/avery/touched 1", "/home/avery/touched 2"),
				texts("change-path"),
			)
			onNodeWithTag("changes-copy-pid").performClick()
			waitForIdle()
			assertEquals("5", copied.last())
			typed("touched 1")
			waitUntil(timeoutMillis = 5_000) { count("change") == 1 }
			onNodeWithTag("change").performMouseInput { moveTo(center) }
			waitUntil(timeoutMillis = 5_000) { count("change-copy-path") == 1 }
			onNodeWithTag("change-copy-path").performClick()
			waitForIdle()
			assertEquals("/home/avery/touched 1", copied.last())
			// Narrowed to moves, of which it has none.
			onNodeWithTag("monitor-kind:Moved").performClick()
			waitUntil(timeoutMillis = 5_000) { count("monitor-changes-empty") == 1 }
			onNodeWithTag("monitor-back").performClick()
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 3 }
			onNodeWithTag("monitor-close").performClick()
			waitUntil(timeoutMillis = 5_000) {
				onAllNodes(hasTestTag("index-monitor")).fetchSemanticsNodes().isEmpty()
			}
		}

	@Test
	fun theMonitorWritesItsTimesAsTheDateFormatSays() =
		runComposeUiTest {
			val index = Kept(Keeping.Followed(FeedKind.EbpfProbe, "probe loaded by prism"))

			log.heard { inFolder(5, Act.FileMade, home, "a".encodeToByteArray()) }

			setContent {
				BrowserTheme {
					CompositionLocalProvider(LocalDateFormat provides DateFormat.MonthFirst) {
						SettingsView(
							Settings(Preferences.DEFAULT, page = Page.Indexing),
							{},
							onApply = {},
							onClose = {},
							index = index,
						)
					}
				}
			}

			onNodeWithTag("index-monitor-open").performClick()
			waitUntil(timeoutMillis = 5_000) { count("burst-count") == 1 }

			val from =
				log.live
					.bursts()
					.single()
					.from

			waitUntil(timeoutMillis = 5_000) {
				DateFormat.MonthFirst.writeTime(from, precision = TimePrecision.Seconds) in
					texts("burst-time")
			}
			assertTrue(texts("burst-time").single().endsWith("M"), texts("burst-time").toString())
		}

	@Test
	fun theCardNamesTheDaemonsIndexFile_AndAClickShowsIt() =
		runComposeUiTest {
			val index = Kept(Keeping.Followed(FeedKind.EbpfProbe, "probe loaded by prism"))
			val file = Path.of(System.getProperty("user.home"), ".local/share/prism/index.prism")
			val shown = mutableListOf<Path>()

			setContent {
				BrowserTheme {
					SettingsView(
						Settings(Preferences.DEFAULT, page = Page.Indexing),
						{},
						onApply = {},
						onClose = {},
						index = index,
						source = IndexSource.Daemon(file),
						onShowFile = { shown.add(it) },
					)
				}
			}

			// The path is a link of its own, beside its label.
			shows("Live monitoring via eBPF", "Probe loaded by prism", "Index file")
			assertEquals(listOf("~/.local/share/prism/index.prism"), texts("index-file"))
			onNodeWithTag("index-file", useUnmergedTree = true).performClick()
			waitForIdle()
			assertEquals(listOf(file), shown)
			assertEquals(1, count("index-rebuild"))
		}

	@Test
	fun anIsolatedPrismSaysItReadItsIndexFromItsFile_AndOffersNoRebuild() =
		runComposeUiTest {
			val file = Path.of("/var/tmp/prism/index.prism")

			var source by mutableStateOf<IndexSource>(
				IndexSource.File(
					file,
					Instant.parse("2026-11-20T14:03:00Z"),
					1_234_567,
					isolated = true,
				),
			)

			setContent {
				BrowserTheme {
					CompositionLocalProvider(LocalDateFormat provides DateFormat.Iso) {
						SettingsView(
							Settings(Preferences.DEFAULT, page = Page.Indexing),
							{},
							onApply = {},
							onClose = {},
							// What an isolated prism searches: the file's index, as it was read.
							index =
								Kept(Keeping.Followed(FeedKind.Other, "")).let { kept ->
									object : Finder by kept {
										override val rebuilds: Boolean get() = false

										override fun keeping(): Keeping? = null

										override val activity: ActivityLog? get() = null
									}
								},
							source = source,
						)
					}
				}
			}

			val at = DateFormat.Iso.write(Instant.parse("2026-11-20T14:03:00Z"))

			shows(
				"Isolated instance",
				"Loaded at $at, 1,234,567 entries. Not updated while isolated.",
				"Index file",
			)
			assertEquals(listOf("/var/tmp/prism/index.prism"), texts("index-file"))
			assertEquals(0, count("index-rebuild"))
			assertEquals(0, count("index-monitor-open"))

			// None in the file: said so, with where it was looked for.
			source =
				IndexSource.None(
					file,
					"No index file yet. Run prism daemon or prism index to create one.",
					true,
				)
			shows(
				"Isolated instance",
				"No index file yet. Run prism daemon or prism index to create one.",
				"Index file",
			)
			assertEquals(listOf("/var/tmp/prism/index.prism"), texts("index-file"))
			assertEquals(0, count("index-rebuild"))
		}

	@Test
	fun aDaemonKeepingItsIndexInMemorySaysSo() =
		runComposeUiTest {
			setContent {
				BrowserTheme {
					SettingsView(
						Settings(Preferences.DEFAULT, page = Page.Indexing),
						{},
						onApply = {},
						onClose = {},
						index = Kept(Keeping.Followed(FeedKind.EbpfProbe, "probe loaded by prism")),
						source = IndexSource.Daemon(null),
					)
				}
			}

			shows(
				"Live monitoring via eBPF",
				"Probe loaded by prism",
				"Kept in memory only, never written to disk",
			)
			assertEquals(1, count("index-rebuild"))
		}
}
