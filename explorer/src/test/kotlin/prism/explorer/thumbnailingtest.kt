package prism.explorer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.browser.BrowserTheme
import prism.browser.Palette
import prism.browser.renderedPng
import prism.preferences.MemoryPreferences
import prism.preferences.PreferenceFormat
import prism.preferences.Preferences
import prism.preferences.preferences
import prism.shared.Loaded
import prism.thumbnails.Budget
import prism.thumbnails.Ffmpeg
import prism.thumbnails.Png
import prism.thumbnails.Relocated
import prism.thumbnails.Subject
import prism.thumbnails.ThumbnailCache
import prism.thumbnails.ThumbnailSettings
import prism.thumbnails.ThumbnailSize
import prism.thumbnails.Totals

/**
 * The thumbnails' settings (thumbnailing.kt, thumbnailspage.kt): the page's place among the pages; its edits, and what
 * of them is applied; the folder, which is changed at once and by itself, with what the old one holds moved, removed
 * or left, on real folders, and a move stopped part of the way; and the page and its windows clicked and typed at.
 */
@OptIn(ExperimentalTestApi::class)
class ThumbnailingTest {
	@TempDir
	lateinit var work: Path

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	@AfterEach
	fun stop() {
		scope.cancel()
	}

	private val default = Path.of("/home/avery/.cache/thumbnails")

	/** A cache in [folder] of [count] thumbnails of files that need not be there. */
	private fun filled(folder: Path, count: Int): ThumbnailCache =
		ThumbnailCache(folder).also { cache ->
			for (number in 1..count)
				cache.write(
					Subject(work.resolve("pictures/$number.jpg"), Instant.ofEpochSecond(1000)),
					ThumbnailSize.Large,
					Png.BLANK,
				)
		}

	private fun ThumbnailCache.count(): Int = entries().count()

	@Test
	fun thePageComesAfterIndexingWhichComesAfterTheRenderer() {
		assertEquals(
			listOf("Display", "Renderer", "Indexing", "Thumbnails", "Layout"),
			Page.entries.take(5).map { it.label },
		)
	}

	@Test
	fun whatIsTypedForTheFolderIsAChangeOnlyWhereItIsAnotherFolderWrittenInFull() {
		val edits = ThumbnailEdits()

		// Nothing typed: the folder shown is the one thumbnails are kept in, and there is nothing to change.
		assertEquals("$default", edits.folderShown(default))
		assertNull(edits.proposed(default))
		assertNull(edits.toDefault(default))

		assertEquals(
			ThumbnailEdits.Proposal.Unfit("Enter a folder."),
			edits.typing("  ").proposed(default),
		)
		assertEquals(
			ThumbnailEdits.Proposal.Unfit("Enter the folder's full path, from the root."),
			edits.typing("thumbs/here").proposed(default),
		)
		// The folder they are kept in already, however it is written: no change.
		assertNull(edits.typing("/home/avery/.cache/./thumbnails/").proposed(default))
		assertEquals(
			ThumbnailEdits.Proposal.Fit(
				FolderChange(default, Path.of("/mnt/fast/thumbs"), "/mnt/fast/thumbs"),
			),
			edits.typing(" /mnt/fast/thumbs ").proposed(default),
		)

		// Kept elsewhere, the platform's own folder is a change to make, and is written as no folder set.
		val elsewhere = ThumbnailEdits(ThumbnailSettings(folder = "/mnt/fast/thumbs"))

		assertEquals("/mnt/fast/thumbs", elsewhere.folderShown(default))
		assertEquals(
			FolderChange(Path.of("/mnt/fast/thumbs"), default, null),
			elsewhere.toDefault(default),
		)
		assertEquals(
			ThumbnailEdits.Proposal.Fit(FolderChange(Path.of("/mnt/fast/thumbs"), default, null)),
			elsewhere.typing("$default").proposed(default),
		)
		// Changed, nothing is typed any more, and the field shows where they now are.
		assertEquals(elsewhere, edits.typing("/x").relocated("/mnt/fast/thumbs"))
	}

	@Test
	fun whatThePageSetsIsAppliedWithTheRestAndTheFolderIsNot() {
		val base = Preferences.DEFAULT
		val opened = Settings(base)

		assertFalse(opened.changed)
		// Typing a folder is not a change to apply: it is changed by its own button.
		assertFalse(opened.copy(thumbnailing = opened.thumbnailing.typing("/mnt/x")).changed)

		val edited =
			opened.copy(
				titlebar = false,
				thumbnailing =
					opened.thumbnailing
						.budgeted(Budget(1L shl 30, 50_000))
						.sized(ThumbnailSize.XLarge)
						.ahead(300)
						.switched("ffmpeg", on = false),
			)

		assertTrue(edited.changed)
		assertEquals(
			ThumbnailSettings(
				budget = Budget(1L shl 30, 50_000),
				size = ThumbnailSize.XLarge,
				nearby = 300,
				off = setOf("ffmpeg"),
			),
			edited.written.thumbnailing,
		)
		assertEquals(
			emptySet<String>(),
			edited.thumbnailing
				.switched("ffmpeg", on = true)
				.kept.off,
		)

		// The folder changed, by its button: the preferences hold it already, and it is what the window edits from.
		// Everything else changed here still waits to be applied, and is applied over it.
		val moved = edited.relocated("/mnt/fast/thumbs")
		val now = base.copy(thumbnailing = base.thumbnailing.copy(folder = "/mnt/fast/thumbs"))

		assertEquals("/mnt/fast/thumbs", moved.base.thumbnailing.folder)
		assertTrue(moved.changed)
		assertEquals(false, moved.over(now).titlebar)
		assertEquals("/mnt/fast/thumbs", moved.over(now).thumbnailing.folder)
		assertEquals(Budget(1L shl 30, 50_000), moved.over(now).thumbnailing.budget)
		// With nothing else changed, the window has nothing to apply after it.
		assertFalse(opened.relocated("/mnt/fast/thumbs").changed)

		// And the preferences' file holds all of it, written and read back.
		val read =
			PreferenceFormat.read(
				PreferenceFormat.written(moved.over(now)),
			) as Loaded.Read

		assertEquals(moved.over(now).thumbnailing, read.value.thumbnailing)
	}

	@Test
	fun theProgramsAreTheUsersOwnThenFfmpegAndWhatEachIsForIsSaidShortly() {
		val pdf =
			Json.parseToJsonElement(
				"""{"id": "pdf", "name": "PDF pages", "command": ["pdftoppm", "-png", "{input}"], "extensions": ["pdf"]}""",
			)

		val ffmpeg =
			Ffmpeg(Path.of("/usr/bin/ffmpeg"), setOf("matroska", "mov", "avi", "mp3", "png_pipe"))

		val edits =
			ThumbnailEdits(
				ThumbnailSettings(thumbnailers = listOf(pdf)),
			).switched("pdf", on = false)

		val thumbnailers = edits.thumbnailers(ffmpeg)

		assertEquals(listOf("pdf", "ffmpeg"), thumbnailers.all.map { it.id })
		assertEquals(listOf("ffmpeg"), thumbnailers.on.map { it.id })
		assertEquals("pdf", ThumbnailEdits.filesOf(thumbnailers.all[0]))
		assertEquals(
			"3g2, 3gp, avi, avif, divx, f4v, heic, heif and 11 more",
			ThumbnailEdits.filesOf(thumbnailers.all[1]),
		)
		// With no ffmpeg there is only what was declared.
		assertEquals(listOf("pdf"), edits.thumbnailers(null).all.map { it.id })
		// The steps a limit is set through hold what is set by default.
		assertTrue(Budget.BYTES in ThumbnailEdits.BYTES && Budget.FILES in ThumbnailEdits.FILES)
		assertTrue(ThumbnailSettings.NEARBY in ThumbnailEdits.NEARBY)
		assertEquals(2, ThumbnailEdits.nearest(ThumbnailEdits.FILES, 30_000))
	}

	/** A relocator over preferences kept in memory, noting each folder it says was written. */
	private fun relocator(
		store: prism.preferences.PreferenceStore,
		written: MutableList<String?> = CopyOnWriteArrayList(),
	) = Relocator(store, scope, written = { written += it }) to written

	private suspend fun settled(relocator: Relocator) =
		withTimeout(20.seconds) {
			relocator.state.first {
				it ==
					null
			}
		}

	@Test
	fun aFolderThatHoldsNothingIsChangedFromAtOnce() =
		runBlocking {
			val store = MemoryPreferences()
			val (relocator, written) = relocator(store)
			val change =
				FolderChange(work.resolve("old"), work.resolve("new"), "${work.resolve("new")}")

			relocator.ask(Relocate.Proposed(change))
			withTimeout(10.seconds) { store.preferences.first { it.thumbnailing.folder != null } }
			assertEquals("${work.resolve("new")}", store.preferences.value.thumbnailing.folder)
			assertEquals(listOf<String?>("${work.resolve("new")}"), written.toList())
			assertNull(relocator.state.value)
		}

	@Test
	fun aFolderThatHoldsThumbnailsIsAskedAboutAndNothingIsWrittenUntilItIsAnswered() =
		runBlocking {
			val store = MemoryPreferences()
			val (relocator, written) = relocator(store)
			val old = filled(work.resolve("old"), 30)
			val change = FolderChange(old.root, work.resolve("new"), "${work.resolve("new")}")

			relocator.ask(Relocate.Proposed(change))
			assertEquals(
				Relocating.Asking(change),
				withTimeout(10.seconds) {
					relocator.state.first {
						it !=
							null
					}
				},
			)
			assertNull(store.preferences.value.thumbnailing.folder)

			// Never mind: nothing is changed, and nothing written.
			relocator.ask(Relocate.Stopped)
			assertNull(relocator.state.value)
			assertNull(store.preferences.value.thumbnailing.folder)
			assertEquals(emptyList<String?>(), written.toList())
			assertEquals(30, old.count())

			// Asked again, and left: the folder is changed, and what the old one holds is as it was.
			relocator.ask(Relocate.Proposed(change))
			withTimeout(10.seconds) { relocator.state.first { it != null } }
			relocator.ask(Relocate.Chosen(Leftover.Leave))
			assertNull(relocator.state.value)
			assertEquals("${work.resolve("new")}", store.preferences.value.thumbnailing.folder)
			assertEquals(30, old.count())
			assertEquals(0, ThumbnailCache(work.resolve("new")).count())
		}

	@Test
	fun movedOrDeletedTheSettingIsWrittenFirstAndTheOldFoldersThumbnailsFollow() =
		runBlocking {
			val store = MemoryPreferences()
			val (relocator, written) = relocator(store)
			val old = filled(work.resolve("old"), 40)
			val new = ThumbnailCache(work.resolve("new"))
			val change = FolderChange(old.root, new.root, "${new.root}")

			relocator.ask(Relocate.Proposed(change))
			withTimeout(10.seconds) { relocator.state.first { it != null } }
			relocator.ask(Relocate.Chosen(Leftover.Move))
			// Written by the time choosing returns, whatever the move has got to.
			assertEquals("${new.root}", store.preferences.value.thumbnailing.folder)
			assertEquals(listOf<String?>("${new.root}"), written.toList())
			settled(relocator)
			assertEquals(0 to 40, old.count() to new.count())

			// Back to the platform's own folder, which is written as no folder; the thumbnails deleted.
			val back = FolderChange(new.root, work.resolve("default"), null)

			relocator.ask(Relocate.Proposed(back))
			withTimeout(10.seconds) { relocator.state.first { it != null } }
			relocator.ask(Relocate.Chosen(Leftover.Delete))
			assertNull(store.preferences.value.thumbnailing.folder)
			settled(relocator)
			assertEquals(0, new.count())
			assertEquals(0, ThumbnailCache(work.resolve("default")).count())
			assertEquals(listOf("${new.root}", null), written.toList())
		}

	@Test
	fun aMoveStoppedPartOfTheWayLeavesWhatWasMovedInTheNewFolderAndTheRestWhereItWas() =
		runBlocking {
			val store = MemoryPreferences()
			val (relocator, _) = relocator(store)
			val old = filled(work.resolve("old"), 3000)
			val new = ThumbnailCache(work.resolve("new"))
			val before = old.entries().map { it.entry }.toSet()

			// Something that is not the cache's, in the folder: never touched.
			old.root.resolve("large/notes.txt").writeText("mine")

			relocator.ask(Relocate.Proposed(FolderChange(old.root, new.root, "${new.root}")))
			withTimeout(10.seconds) { relocator.state.first { it != null } }
			relocator.ask(Relocate.Chosen(Leftover.Move))
			// Shown as it goes; stopped once some have been moved.
			withTimeout(20.seconds) {
				relocator.state.first {
					((it as? Relocating.Working)?.at?.done ?: 0) >= 50 ||
						it == null
				}
			}
			relocator.ask(Relocate.Stopped)
			settled(relocator)

			val moved = new.entries().map { it.entry }.toSet()
			val left = old.entries().map { it.entry }.toSet()

			// Thumbnails are kept in the new folder all the same, as if the rest had been left.
			assertEquals("${new.root}", store.preferences.value.thumbnailing.folder)
			assertTrue(moved.size >= 50, "${moved.size} moved")
			assertTrue(
				left.isNotEmpty(),
				"it was done before it could be stopped: make more to move",
			)
			// Each is in one folder or the other: none in both, none in neither.
			assertEquals(before, moved + left)
			assertEquals(emptySet<Any>(), moved intersect left)
			// And each that was moved is whole: it still says what it is of.
			assertTrue(moved.take(40).all { new.says(new.path(it))?.uri != null })
			assertEquals("mine", Files.readString(old.root.resolve("large/notes.txt")))
			// Nothing half written is left in either.
			assertTrue(
				Files.walk(work).use { all ->
					all
						.filter {
							Files.isRegularFile(it)
						}.noneMatch { it.fileName.toString().endsWith(".tmp") }
				},
			)
		}

	// ---- the page ------------------------------------------------------------------------------------------------

	private val installed =
		ThumbnailFacts(
			default = default,
			totals = Totals(12_408, 1_340_000_000),
			ffmpeg =
				ThumbnailFacts.Found.Installed(
					Ffmpeg(
						Path.of("/usr/bin/ffmpeg"),
						setOf("matroska", "mov", "avi", "mp3", "png_pipe", "jpeg_pipe"),
					),
				),
		)

	@Test
	fun thePageIsSetByItsControlsAndTheFolderByItsOwnButton() =
		runComposeUiTest {
			var settings by mutableStateOf(Settings(Preferences.DEFAULT, page = Page.Thumbnails))
			val asked = ArrayList<Relocate>()

			setContent {
				BrowserTheme {
					SettingsView(
						settings,
						{ settings = it },
						onApply = {},
						onClose = {},
						thumbnails = installed,
						onRelocate = { asked += it },
					)
				}
			}
			// What is kept is said; nothing is changed, and there is no folder to change to yet.
			onNodeWithText("12,408 thumbnails, 1.2 GB").assertExists()
			onNodeWithText("Apply").assertIsNotEnabled()
			onNodeWithTag("thumbnails-change").assertIsNotEnabled()
			onNodeWithTag("thumbnails-default").assertIsNotEnabled()

			fun type(text: String) =
				onNode(
					hasSetTextAction() and
						(
							hasTestTag(
								"thumbnails-folder",
							) or hasAnyAncestor(hasTestTag("thumbnails-folder"))
						),
				).performTextReplacement(text)

			// A folder not written in full cannot be changed to, and why is said.
			type("thumbs")
			waitForIdle()
			onNodeWithText("Enter the folder's full path, from the root.").assertExists()
			onNodeWithTag("thumbnails-change").assertIsNotEnabled()

			// One that is, is asked for by the button: it is not among what Apply applies.
			type("/mnt/fast/thumbs")
			waitForIdle()
			onNodeWithText("Apply").assertIsNotEnabled()
			onNodeWithTag("thumbnails-change").assertIsEnabled().performClick()
			waitForIdle()
			assertEquals(
				listOf<Relocate>(
					Relocate.Proposed(
						FolderChange(default, Path.of("/mnt/fast/thumbs"), "/mnt/fast/thumbs"),
					),
				),
				asked,
			)

			// The limits, a step at a time.
			onNodeWithText("4.0 GB").assertExists()
			onNodeWithTag("thumbnails-bytes:larger").performClick()
			onNodeWithTag("thumbnails-files:smaller").performClick()
			waitForIdle()
			assertEquals(Budget(8L shl 30, 100_000), settings.thumbnailing.kept.budget)
			onNodeWithText("Apply").assertIsEnabled()

			// One size for every thumbnail, chosen once Custom is; and Automatic again.
			onNodeWithText("Custom").performClick()
			waitForIdle()
			assertEquals(ThumbnailSize.Large, settings.thumbnailing.kept.size)
			onNodeWithTag("thumbnails-size").assertExists()
			onNodeWithText("Automatic").performClick()
			waitForIdle()
			assertNull(settings.thumbnailing.kept.size)

			onNodeWithTag("thumbnails-nearby:larger").performClick()
			waitForIdle()
			assertEquals(300, settings.thumbnailing.kept.nearby)

			// ffmpeg's command is shown, and it is turned off by its switch.
			onNodeWithTag("thumbnailer:ffmpeg").assertExists()
			// Under what the window shows of the page: pressed as the keyboard would press it.
			onNodeWithTag("thumbnailer-on:ffmpeg").performSemanticsAction(SemanticsActions.OnClick)
			waitForIdle()
			assertEquals(setOf("ffmpeg"), settings.thumbnailing.kept.off)
			assertEquals(
				ThumbnailSettings(
					budget = Budget(8L shl 30, 100_000),
					nearby = 300,
					off = setOf("ffmpeg"),
				),
				settings.written.thumbnailing,
			)
		}

	@Test
	fun whatToDoWithTheOldFoldersThumbnailsIsAskedAndAMoveIsShownAndStopped() =
		runComposeUiTest {
			val change = FolderChange(default, Path.of("/mnt/fast/thumbs"), "/mnt/fast/thumbs")
			var relocating by mutableStateOf<Relocating?>(Relocating.Asking(change))
			val asked = ArrayList<Relocate>()

			setContent {
				BrowserTheme {
					SettingsView(
						Settings(Preferences.DEFAULT, page = Page.Thumbnails),
						{},
						onApply = {},
						onClose = {},
						thumbnails = installed.copy(ffmpeg = ThumbnailFacts.Found.Missing),
						relocating = relocating,
						onRelocate = { asked += it },
					)
				}
			}
			// Without ffmpeg, the page says so.
			onNodeWithTag("ffmpeg-missing").assertExists()

			onNodeWithText("Move existing thumbnails?").assertExists()
			onNodeWithTag("relocate-move").performClick()
			onNodeWithTag("relocate-delete").performClick()
			onNodeWithTag("relocate-leave").performClick()
			onNodeWithTag("relocate-cancel").performClick()
			waitForIdle()
			assertEquals(
				listOf(
					Relocate.Chosen(Leftover.Move),
					Relocate.Chosen(Leftover.Delete),
					Relocate.Chosen(Leftover.Leave),
					Relocate.Stopped,
				),
				asked,
			)

			// Moving: how far it has got, and a way to stop it. Its spinner turns for as long as it is shown, so the
			// clock is the test's to move while it is.
			mainClock.autoAdvance = false
			relocating = Relocating.Working(change, removing = false, Relocated(4_200, 0, 12_408))
			mainClock.advanceTimeBy(100)
			onNodeWithText("Moving thumbnails").assertExists()
			onNodeWithText("4,200 of 12,408").assertExists()
			onNodeWithTag("relocate-stop").performClick()
			mainClock.advanceTimeBy(100)
			assertEquals(Relocate.Stopped, asked.last())

			relocating = null
			mainClock.advanceTimeBy(100)
			mainClock.autoAdvance = true
			waitForIdle()
			onNodeWithText("Moving thumbnails").assertDoesNotExist()
		}

	/** The page and its windows, drawn headless in both palettes. With `-Pexplorer.screenshots=DIR`, kept there. */
	@Test
	fun thePageIsDrawn() =
		runBlocking {
			val change = FolderChange(default, Path.of("/mnt/fast/thumbs"), "/mnt/fast/thumbs")

			val shown =
				listOf(
					"thumbnails" to (null as Relocating?),
					"thumbnails-asking" to Relocating.Asking(change),
					"thumbnails-moving" to
						Relocating.Working(change, removing = false, Relocated(4_200, 0, 12_408)),
				)

			for ((name, relocating) in shown)
				for ((scheme, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
					val png =
						renderedPng(1180, 1240, palette, scale = 2f) {
							SettingsView(
								Settings(Preferences.DEFAULT, page = Page.Thumbnails),
								{},
								onApply = {},
								onClose = {},
								thumbnails = installed,
								relocating = relocating,
							)
						}

					assertTrue(png.size > 20_000, "$name-$scheme: ${png.size} bytes")
					System.getProperty("explorer.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
						Files.createDirectories(Path.of(dir))
						Files.write(Path.of(dir).resolve("$name-$scheme.png"), png)
					}
				}
			delay(1)
		}
}
