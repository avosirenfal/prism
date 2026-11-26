package prism.browser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.preferences.Layout
import prism.preferences.MemoryPreferences
import prism.preferences.RecentSection
import prism.preferences.RecentsView
import prism.preferences.Relevance
import prism.preferences.preferences
import prism.shared.Used
import prism.shared.Uses

/**
 * The Recents page's most relevant folders, and how they are tuned; its tabs; the gallery under its headings; and
 * what the recent files keep of each pick that these go by: whether it was to open, or to save.
 */
class RelevantTest {
	@TempDir
	lateinit var root: Path

	private val now = Instant.parse("2026-11-12T10:00:00Z")
	private val firefox = AppKey.of("org.mozilla.firefox")!!
	private val gimp = AppKey.of("org.gimp.GIMP")!!
	private val home = Fixtures.home

	private fun ago(days: Long = 0, hours: Long = 0, minutes: Long = 0): Instant =
		now
			.minus(Duration.ofDays(days))
			.minus(Duration.ofHours(hours))
			.minus(Duration.ofMinutes(minutes))

	private fun at(path: String): Path = home.resolve(path)

	// ---- what is kept of a pick --------------------------------------------------------------------------------

	@Test
	fun theFileSaysWhichPickWasToOpenAndWhichToSave_AndALineOfAnOlderVersionIsToOpen() {
		val state = root.resolve("state")
		val store = RecentsFiles(state)
		val photo = root.resolve("Pictures/photo.png").also { it.parent.createDirectories() }
		val statement =
			root.resolve("Downloads/statement.pdf").also {
				it.parent.createDirectories()
			}

		photo.writeText("photo")
		store.picked(listOf(photo), Choosing.OneFile(), firefox, ago(minutes = 20))
		store.picked(
			listOf(statement),
			Choosing.SaveAs("statement.pdf"),
			firefox,
			ago(minutes = 10),
		)

		val lines = Files.readAllLines(state.resolve("recent-files"))

		assertTrue(lines.any { it.endsWith("\topen\t$photo") }, lines.toString())
		assertTrue(lines.any { it.endsWith("\tsave\t$statement") }, lines.toString())
		assertEquals(
			listOf(Picked.Saved, Picked.Opened),
			store
				.read()
				.files
				.of(firefox)
				.entries
				.map { it.picked },
		)

		// A file the application never wrote (its download failed, say) is let go at the next pick, as anything
		// gone is.
		store.picked(listOf(photo), Choosing.OneFile(), gimp, ago(minutes = 5))
		assertEquals(
			listOf(photo),
			store
				.read()
				.files
				.of(firefox)
				.paths,
		)

		// Where each was answered from is kept, beside them.
		assertEquals(
			setOf(
				AppFolder(firefox, Picked.Opened, photo.parent),
				AppFolder(firefox, Picked.Saved, statement.parent),
				AppFolder(gimp, Picked.Opened, photo.parent),
			),
			RecentsFiles(state)
				.read()
				.answered.uses.keys,
		)

		// What an older version wrote says nothing of which: it was to open.
		Files.write(
			state.resolve("recent-files"),
			listOf("${ago(minutes = 1).toEpochMilli()}\torg.mozilla.firefox\t$photo"),
		)
		assertEquals(
			listOf(Recent(photo, ago(minutes = 1), Picked.Opened)),
			RecentsFiles(state)
				.read()
				.files
				.of(firefox)
				.entries,
		)
	}

	@Test
	fun aPickerThatSavesFindsWhereFilesWereSaved_OneThatOpensWhereTheyWereOpenedFrom() {
		assertEquals(setOf(Picked.Saved), picksOf(Choosing.SaveAs("a.txt")))
		assertEquals(setOf(Picked.Opened), picksOf(Choosing.OneFile()))
		assertEquals(setOf(Picked.Opened), picksOf(Choosing.Files()))
		assertEquals(Picked.entries.toSet(), picksOf(Choosing.Folder()))
		assertEquals(Picked.entries.toSet(), picksOf(Choosing.Exploring()))
	}

	// ---- the most relevant folders -----------------------------------------------------------------------------

	/**
	 * What [picks] leave, one after another, each a picker answered as the store is told of it; with what [also] has
	 * been gone to besides.
	 */
	private fun usedBy(
		vararg picks: Triple<String, Choosing, Pair<AppKey, Instant>>,
		also: Map<Path, Used> = emptyMap(),
	): Recents =
		MemoryRecents()
			.also { store ->
				for ((path, choosing, by) in picks)
					store.picked(listOf(at(path)), choosing, by.first, by.second)
			}.read()
			.let { it.copy(accesses = Uses(it.accesses.uses + also)) }

	private fun saved(path: String, app: AppKey, at: Instant) =
		Triple(path, Choosing.SaveAs(path.substringAfterLast('/')) as Choosing, app to at)

	private fun opened(path: String, app: AppKey, at: Instant) =
		Triple(
			path,
			Choosing.OneFile() as Choosing,
			app to at,
		)

	private fun pickedFolder(path: String, app: AppKey, at: Instant) =
		Triple(path, Choosing.Folder() as Choosing, app to at)

	/**
	 * GIMP picked Work, a folder; Firefox saved into Documents, and twice into Downloads, and opened a file from
	 * Uploads; GIMP saved into Art. Projects is gone to a great deal, and Music a little, long ago.
	 */
	private val used =
		usedBy(
			pickedFolder("Work", gimp, ago(days = 4)),
			saved("Documents/receipt.pdf", firefox, ago(days = 3)),
			saved("Downloads/b.pdf", firefox, ago(days = 1)),
			saved("Downloads/a.pdf", firefox, ago(hours = 1)),
			opened("Uploads/photo.png", firefox, ago(minutes = 30)),
			saved("Art/x.png", gimp, ago(minutes = 5)),
			also =
				mapOf(
					at("Projects") to Used(30.0, ago(days = 6)),
					at("Music") to Used(1.0, ago(days = 20)),
				),
		)

	/** What is on disk: a file, for a name with a dot in it; a folder, for any other. */
	private val disk = { path: Path ->
		val name = path.fileName.toString()

		Fixtures.row(name, 100L.takeIf { '.' in name }, folder = path.parent)
	}

	private fun page(
		asker: AppKey?,
		picks: Set<Picked>,
		recent: Recents = used,
		files: Boolean = true,
	): RecentPage = RecentPage.of(emptyList(), recent, asker, now, files, picks, disk)

	/** The names of the most relevant folders of [page], as [relevance] weighs them, as they are shown. */
	private fun relevant(
		page: RecentPage,
		relevance: Relevance = Relevance(),
	): List<String> {
		val shown = page.shown(Arrangement(recent = RecentArranged(relevance = relevance)))
		val heading =
			shown.headings.firstOrNull { it.kind == RecentPage.Kind.Relevant }
				?: return emptyList()

		return shown.rows.list
			.subList(heading.before, heading.before + heading.count)
			.map { it.name }
	}

	private fun RecentPage.candidateNames(): Set<String> =
		candidates.mapTo(HashSet()) {
			it.row.name
		}

	@Test
	fun aPickIsKeptAsWhereItsApplicationWasAnsweredFrom_AsItPicked() {
		assertEquals(
			mapOf(
				AppFolder(gimp, Picked.Opened, at("Work")) to 1,
				AppFolder(firefox, Picked.Saved, at("Documents")) to 1,
				AppFolder(firefox, Picked.Saved, at("Downloads")) to 2,
				AppFolder(firefox, Picked.Opened, at("Uploads")) to 1,
				AppFolder(gimp, Picked.Saved, at("Art")) to 1,
			),
			// Each a use, as every use of a thing counts: about how many times lately.
			used.answered.uses.mapValues { (_, use) -> Math.round(use.weightAt(use.at)).toInt() },
		)
	}

	@Test
	fun theCandidatesAreTheFoldersTheGroupsShow_AndEveryOneTheApplicationWasAnsweredFrom() {
		// Ten albums gone to more than anything but Projects: the folders used most are Projects and nine of them.
		val busy =
			usedBy(
				pickedFolder("Work", gimp, ago(days = 4)),
				saved("Documents/receipt.pdf", firefox, ago(days = 3)),
				saved("Downloads/b.pdf", firefox, ago(days = 1)),
				saved("Downloads/a.pdf", firefox, ago(hours = 1)),
				opened("Uploads/photo.png", firefox, ago(minutes = 30)),
				saved("Art/x.png", gimp, ago(minutes = 5)),
				also =
					mapOf(at("Projects") to Used(30.0, ago(days = 6))) +
						(1..10).associate { at("Albums/$it") to Used(3.0 + it, ago(days = 2)) },
			)

		val usedMost = setOf("Projects") + (2..10).map { "$it" }

		// To save: those the groups show (the folder GIMP picked; the folders used most), and where Firefox saved,
		// though it is not among the folders used most; not where it opened from, nor where GIMP saved.
		assertEquals(
			setOf("Work", "Documents", "Downloads") + usedMost,
			page(firefox, setOf(Picked.Saved), busy).candidateNames(),
		)
		// To open: where it opened from, in place of where it saved.
		assertEquals(
			setOf("Work", "Uploads") + usedMost,
			page(firefox, setOf(Picked.Opened), busy).candidateNames(),
		)
		// For nobody (the explorer): where anyone was answered from, as anything.
		assertEquals(
			setOf("Work", "Documents", "Downloads", "Uploads", "Art") + usedMost,
			page(null, Picked.entries.toSet(), busy).candidateNames(),
		)
		// With nothing recent, there are none, and no heading.
		assertEquals(
			emptyList<String>(),
			relevant(page(firefox, setOf(Picked.Saved), Recents())),
		)
	}

	@Test
	fun theFewShownAreThoseThatScoreMost_AsTheTuningWeighsThem_AndTheyChangeAsItIsTuned() {
		val saving = page(firefox, setOf(Picked.Saved))

		// Every candidate, where there are no more than are shown: the newest first, as every section is.
		assertEquals(
			listOf("Art", "Uploads", "Downloads", "Documents", "Work", "Projects", "Music"),
			relevant(saving),
		)
		// Two: where Firefox saved, lately and twice, before all; and then the folder used last, by anyone.
		assertEquals(listOf("Art", "Downloads"), relevant(saving, Relevance(shown = 2)))
		// Only how much each is gone to counting: the two most gone to, the newest of them first.
		assertEquals(
			listOf("Downloads", "Projects"),
			relevant(
				saving,
				Relevance(recency = 0.0, frequency = 2.0, application = 0.0, shown = 2),
			),
		)
		// Only how lately: the two latest.
		assertEquals(
			listOf("Art", "Uploads"),
			relevant(
				saving,
				Relevance(recency = 1.0, frequency = 0.0, application = 0.0, shown = 2),
			),
		)
		// Only how much Firefox saved there.
		assertEquals(
			listOf("Downloads", "Documents"),
			relevant(
				saving,
				Relevance(recency = 0.0, frequency = 0.0, application = 1.0, shown = 2),
			),
		)
		// How lately counting for a long while (a month's half-life), and how much a great deal: Projects is in.
		assertEquals(
			listOf("Downloads", "Projects"),
			relevant(
				saving,
				Relevance(
					recency = 0.4,
					frequency = 2.0,
					application = 0.4,
					recencyHalfLifeHours = 720.0,
					shown = 2,
				),
			),
		)
		// Uploading, where Firefox opened from counts, and where it saved does not.
		assertEquals(
			listOf("Uploads"),
			relevant(
				page(firefox, setOf(Picked.Opened)),
				Relevance(recency = 0.0, frequency = 0.0, application = 1.0, shown = 1),
			),
		)

		// A candidate's score: each of what says so, by its weight.
		val downloads = saving.candidates.single { it.row.name == "Downloads" }

		assertEquals(Candidate.saturated(2.0, Candidate.ANSWERED), downloads.application, 0.01)
		assertEquals(Candidate.saturated(2.0, Candidate.FREQUENT), downloads.frequency, 0.01)
		assertEquals(
			0.5,
			downloads
				.copy(last = ago(hours = 48))
				.score(Relevance(1.0, 0.0, 0.0, recencyHalfLifeHours = 48.0), now),
			1e-9,
		)
		// A preferences file of a half-life of nothing weighs by no more than a moment's.
		assertTrue(downloads.score(Relevance(recencyHalfLifeHours = 0.0), now).isFinite())
	}

	@Test
	fun aUseIsAHalfAtSoMany_AndNearerOneTheMore_WhateverAnyOtherIs() {
		assertEquals(0.0, Candidate.saturated(0.0, 4.0), 1e-9)
		assertEquals(0.5, Candidate.saturated(4.0, 4.0), 1e-9)
		assertEquals(0.75, Candidate.saturated(8.0, 4.0), 1e-9)
		assertTrue(Candidate.saturated(40.0, 4.0) in 0.999..1.0)
		assertEquals(0.0, Candidate.saturated(-1.0, 4.0), 1e-9)
	}

	@Test
	fun everySectionIsTheNewestFirst_TheFrequentTooThoughTheyAreTheMostUsed() {
		val frequent =
			Recents(
				accesses =
					Uses(
						(1..14).associate { day ->
							at("folder-$day") to
								Used(day.toDouble(), ago(days = (day % 5).toLong(), minutes = day.toLong()))
						},
					),
			)

		val shown = page(null, Picked.entries.toSet(), frequent).shown(Arrangement())
		val heading = shown.headings.single { it.kind == RecentPage.Kind.Frequent }
		val rows = shown.rows.list.subList(heading.before, heading.before + heading.count)

		// The ten most used (5 to 14), the latest used first.
		assertEquals((5..14).map { "folder-$it" }.toSet(), rows.map { it.name }.toSet())
		assertEquals(
			rows.map { shown.marks.getValue(it.id).at }.sortedDescending(),
			rows.map { shown.marks.getValue(it.id).at },
		)
		assertTrue(rows.none { shown.marks.getValue(it.id).stale })
	}

	// ---- tabs --------------------------------------------------------------------------------------------------

	private val tabbed = RecentArranged(tabbed = true)

	@Test
	fun inTabs_TheRowsAreTheChosenTabsAlone_TheFirstWhereNoneIs_AndThereIsNoTabOfNothing() {
		val saving = page(firefox, setOf(Picked.Saved))
		val first = saving.shown(Arrangement(recent = tabbed))
		val tabs = first.grouping as RecentPage.Grouping.Tabs

		assertEquals(
			listOf(
				RecentPage.Kind.Relevant to 7,
				RecentPage.Kind.ApplicationFiles to 4,
				RecentPage.Kind.Files to 1,
				RecentPage.Kind.Folders to 1,
				RecentPage.Kind.Frequent to 7,
			),
			tabs.tabs.map { it.kind to it.count },
		)
		assertEquals(RecentPage.Kind.Relevant, tabs.chosen)
		assertEquals(
			listOf("Art", "Uploads", "Downloads", "Documents", "Work", "Projects", "Music"),
			first.rows.list.map {
				it.name
			},
		)
		// No headings over them, and nothing over a row.
		assertEquals(emptyList<RecentPage.Heading>(), first.headings)
		assertEquals(emptyMap<Int, List<RecentPage.Heading>>(), first.over)
		assertEquals(7, first.listed)

		// Another chosen: its entries alone, the newest first; what is collapsed in groups is nothing in tabs.
		val files =
			saving.shown(
				Arrangement(
					recent =
						tabbed.copy(
							tab = RecentSection.ApplicationFiles,
							folded = RecentSection.entries.toSet(),
						),
				),
			)

		assertEquals(
			RecentPage.Kind.ApplicationFiles,
			(files.grouping as RecentPage.Grouping.Tabs).chosen,
		)
		assertEquals(
			listOf("photo.png", "a.pdf", "b.pdf", "receipt.pdf"),
			files.rows.list.map {
				it.name
			},
		)
		assertEquals(files.rows.list.map { it.id }, files.marks.keys.toList())

		// A tab chosen that has nothing to show (a folder picker's files; a filter's leaving nothing of it): the first.
		val folders =
			page(firefox, setOf(Picked.Saved), files = false)
				.shown(Arrangement(recent = tabbed.copy(tab = RecentSection.ApplicationFiles)))

		assertEquals(
			RecentPage.Kind.Relevant,
			(folders.grouping as RecentPage.Grouping.Tabs).chosen,
		)

		val pdfs = TypeFilter("PDF", listOf("*.pdf"))

		val filtered =
			saving.shown(
				Arrangement(filter = pdfs, recent = tabbed.copy(tab = RecentSection.Files)),
			)

		assertFalse(
			(filtered.grouping as RecentPage.Grouping.Tabs).tabs.any {
				it.kind ==
					RecentPage.Kind.Files
			},
		)
		assertEquals(RecentPage.Kind.Relevant, filtered.grouping.chosen)

		// A page of nothing has no tabs, and nothing to choose among them.
		assertEquals(
			RecentPage.Grouping.Groups(emptyList()),
			RecentPage(emptyList(), now).shown(Arrangement(recent = tabbed)).grouping,
		)
	}

	@Test
	fun aTabIsGoneToFromTheKeyboard_RoundFromTheLastToTheFirst() {
		val tabs =
			(
				page(
					firefox,
					setOf(Picked.Saved),
				).shown(Arrangement(recent = tabbed)).grouping as RecentPage.Grouping.Tabs
			).tabs

		assertEquals(
			RecentPage.Kind.ApplicationFiles,
			tabAfter(tabs, RecentPage.Kind.Relevant, onward = true)?.kind,
		)
		assertEquals(
			RecentPage.Kind.Frequent,
			tabAfter(tabs, RecentPage.Kind.Relevant, onward = false)?.kind,
		)
		assertEquals(
			RecentPage.Kind.Relevant,
			tabAfter(tabs, RecentPage.Kind.Frequent, onward = true)?.kind,
		)
		assertNull(tabAfter(emptyList(), RecentPage.Kind.Relevant, onward = true))
	}

	// ---- the window --------------------------------------------------------------------------------------------

	private fun shown(
		choosing: Choosing,
		asker: AppKey?,
		view: RecentsView = RecentsView(),
		page: RecentPage = page(asker, picksOf(choosing)),
	): BrowserState {
		val window =
			opened(choosing, home, home, Fixtures.sidebar, filters = Fixtures.filters)
				.settled(Fixtures.Memory)
				.copy(asker = asker, recentsView = view)

		return step(
			step(window, BrowserEvent.OpenedRecents).state,
			Answer.RecentsRead(page),
		).state
	}

	private fun BrowserState.names(): List<String> = rows.list.map { it.name }

	@Test
	fun thePageIsInTabsAtFirst_InGroupsOnceAsked_AndThatIsKeptForEveryWindow() {
		val picker = shown(Choosing.SaveAs("c.pdf"), firefox)

		assertTrue(picker.recentsView.tabbed)
		assertEquals(
			listOf("Art", "Uploads", "Downloads", "Documents", "Work", "Projects", "Music"),
			picker.names(),
		)

		val grouping = step(picker, BrowserEvent.RecentsTabbed(false))

		assertEquals(
			listOf<Effect>(Effect.KeepRecentsView(RecentsView(tabbed = false), 1)),
			grouping.effects,
		)
		assertFalse(grouping.state.recentsView.tabbed)
		assertEquals(
			listOf(
				"Most relevant folders",
				"Recent files for this application",
				"Recent files",
				"Recent folders",
				"Frequently accessed",
			),
			(grouping.state.listing as Listing.Recent)
				.shown(
					grouping.state.arrangement,
				).headings
				.map {
					it.kind.title
				},
		)
		assertEquals(20, grouping.state.rows.size)

		// Another window's change is this one's too.
		assertEquals(
			20,
			step(picker, Answer.RecentsViewed(RecentsView(tabbed = false))).state.rows.size,
		)

		// But not while what this one asked is still to be kept: what the preferences say then is what was asked
		// before, on the way to it. Once it is kept, what they then say is taken up, whoever asked it.
		val back = step(grouping.state, BrowserEvent.RecentsTabbed(true))

		assertEquals(
			listOf<Effect>(Effect.KeepRecentsView(RecentsView(tabbed = true), 2)),
			back.effects,
		)

		val meanwhile =
			step(
				back.state,
				Answer.RecentsViewed(RecentsView(tabbed = false)),
			).state

		assertTrue(meanwhile.recentsView.tabbed)
		assertTrue(
			step(
				meanwhile,
				Answer.RecentsViewKept(1, RecentsView(tabbed = false)),
			).state.recentsView.tabbed,
		)

		val kept = step(meanwhile, Answer.RecentsViewKept(2, RecentsView(tabbed = true))).state

		assertTrue(kept.recentsView.tabbed)
		assertNull(kept.recentsKeeping)
		assertFalse(
			step(kept, Answer.RecentsViewed(RecentsView(tabbed = false))).state.recentsView.tabbed,
		)
		assertFalse(
			step(
				meanwhile,
				Answer.RecentsViewKept(2, RecentsView(tabbed = false)),
			).state.recentsView.tabbed,
		)
	}

	@Test
	fun aTabChosenIsThisWindowsAlone_AndWhatWasChosenInAnotherIsLetGo() {
		val picker = shown(Choosing.Files(), firefox)

		val chose =
			step(
				picker,
				BrowserEvent.Clicked(RowId.Recent(at("Uploads"), RecentSection.Relevant), Click.Only),
			).state

		assertEquals(1, chose.choosing.rows.size)

		val files = step(chose, BrowserEvent.RecentTabChosen(RecentSection.ApplicationFiles))

		assertEquals(emptyList<Effect>(), files.effects)
		assertEquals(RecentSection.ApplicationFiles, files.state.recentsTab)
		assertEquals(listOf("photo.png", "a.pdf", "b.pdf", "receipt.pdf"), files.state.names())
		assertEquals(emptySet<RowId>(), files.state.choosing.rows)
		assertNull(files.state.cursor)
		// Anywhere but on the page, a tab arranges nothing.
		assertEquals(
			RecentArranged.NONE,
			files.state
				.copy(at = Location.Folder(home))
				.arrangement.recent,
		)
	}

	@Test
	fun theTuningIsShownAtOnce_AndKeptForEveryWindow() {
		val picker = shown(Choosing.SaveAs("c.pdf"), firefox)
		val two = Relevance(shown = 2)
		val tuned = step(picker, BrowserEvent.RelevanceTuned(two))

		assertEquals(
			listOf<Effect>(Effect.KeepRecentsView(RecentsView(relevance = two), 1)),
			tuned.effects,
		)
		assertEquals(listOf("Art", "Downloads"), tuned.state.names())

		val frequent = Relevance(recency = 0.0, frequency = 2.0, application = 0.0, shown = 2)

		assertEquals(
			listOf("Downloads", "Projects"),
			step(tuned.state, BrowserEvent.RelevanceTuned(frequent)).state.names(),
		)
	}

	@Test
	fun aSliderDraggedEndsAtWhatItWasDraggedTo_InThisWindowAndInThePreferences() {
		val preferences = MemoryPreferences()
		val services = Services(Fixtures.Memory, preferences = preferences, clock = { now })
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val session = Session(Step(shown(Choosing.SaveAs("c.pdf"), firefox)), services, scope)

		try {
			runBlocking {
				withTimeout(30_000) {
					// Dragged across every step there is, as fast as events are sent.
					val steps =
						(4..16).map { Relevance(shown = it) } + (16 downTo 3).map { Relevance(shown = it) }

					for (relevance in steps)
						session.send(BrowserEvent.RelevanceTuned(relevance))

					val last = steps.last().copy()

					preferences.preferences.first { it.recentsView.relevance == last }
					session.state.first { it.recentsView.relevance == last }
				}
			}

			// Kept, and nothing on the way to it come back after.
			Thread.sleep(300)
			assertEquals(Relevance(shown = 3), session.state.value.recentsView.relevance)
			assertEquals(Relevance(shown = 3), preferences.preferences.value.recentsView.relevance)

			// Another window's tuning, once this one waits on nothing of its own, is this one's.
			preferences.update { copy(recentsView = recentsView.copy(tabbed = false)) }
			runBlocking { withTimeout(10_000) { session.state.first { !it.recentsView.tabbed } } }
		} finally {
			scope.cancel()
		}
	}

	// ---- the gallery under headings ----------------------------------------------------------------------------

	private fun heading(kind: RecentPage.Kind, before: Int, collapsed: Boolean = false) =
		RecentPage.Heading(kind, 3, collapsed, before)

	@Test
	fun aGalleryUnderHeadingsHasAPlaceForThemBeforeTheirRows_AndALineDownKeepsToItsColumn() {
		// Five rows under the first heading, three under the second, and a collapsed one after the last.
		val places =
			GalleryPlaces.Headed(
				mapOf(
					0 to listOf(heading(RecentPage.Kind.Relevant, 0)),
					5 to listOf(heading(RecentPage.Kind.Files, 5)),
					8 to listOf(heading(RecentPage.Kind.Frequent, 8, collapsed = true)),
				),
				8,
			)

		assertEquals(11, places.size)
		assertEquals(listOf(1, 2, 3, 4, 5, 7, 8, 9), (0 until 8).map(places::placeOf))
		assertEquals(
			listOf(null, 0, 1, 2, 3, 4, null, 5, 6, 7, null),
			(0 until 11).map(places::rowAt),
		)
		assertEquals(RecentPage.Kind.Frequent, places.headingsAt(10)?.single()?.kind)
		assertNull(places.headingsAt(1))

		// Three across: 0 1 2 / 3 4, then 5 6 7 under their own heading.
		assertEquals(3, places.down(0, 1, 3))
		assertEquals(4, places.down(2, 1, 3))
		assertEquals(5, places.down(3, 1, 3))
		assertEquals(6, places.down(4, 1, 3))
		assertEquals(7, places.down(7, 1, 3))
		assertEquals(3, places.down(5, -1, 3))
		assertEquals(4, places.down(7, -1, 3))
		assertEquals(1, places.down(4, -1, 3))
		assertEquals(0, places.down(1, -1, 3))
		assertEquals(6, places.down(1, 2, 3))

		// Any other gallery's places are its rows', and a line is so many rows.
		val plain = GalleryPlaces.Plain(8)

		assertEquals(5, plain.placeOf(5))
		assertEquals(5, plain.rowAt(5))
		assertNull(plain.rowAt(8))
		assertEquals(3, plain.down(0, 1, 3))
		assertEquals(7, plain.down(6, 1, 3))
		assertEquals(0, plain.down(1, -1, 3))
	}

	// ---- drawn -------------------------------------------------------------------------------------------------

	/**
	 * A page as a picker shows it to [asker] (to save from Firefox, unless it is said otherwise), finding its most
	 * relevant folders by [picks]: files in its tabs, and folders of many kinds.
	 */
	private fun drawnPage(
		asker: AppKey? = firefox,
		picks: Set<Picked> = setOf(Picked.Saved),
	): RecentPage {
		val made =
			listOf(
				"Pictures/Screenshots/Screenshot 2026-11-12 09-58-12.png" to ago(minutes = 2),
				"Downloads/invoice-4471.pdf" to ago(minutes = 14),
				"Documents/Letters/Landlord, November.odt" to ago(hours = 3),
				"Music/Field recordings/harbour.flac" to ago(days = 1),
			).map { (path, at) -> NewFile(at(path), at) }

		val recent =
			Recents(
				folders =
					Recently()
						.used(listOf(at("Documents/Taxes")), firefox, ago(days = 33))
						.used(listOf(at("Projects/prism/docs")), null, ago(days = 3))
						.used(listOf(at("Pictures")), null, ago(hours = 4)),
				files =
					Recently()
						.used(
							listOf(at("Documents/Taxes/2025 return.pdf")),
							firefox,
							ago(days = 12),
							Picked.Saved,
						).used(
							listOf(at("Downloads/boarding-pass.pdf")),
							firefox,
							ago(days = 2),
							Picked.Saved,
						).used(listOf(at("Pictures/cat.png")), firefox, ago(hours = 5), Picked.Opened)
						.used(
							listOf(at("Downloads/invoice-4471.pdf")),
							firefox,
							ago(minutes = 14),
							Picked.Saved,
						).used(listOf(at("Projects/prism/notes.md")), null, ago(hours = 2)),
				accesses =
					Uses(
						mapOf(
							at("Projects/prism") to Used(14.0, ago(days = 1)),
							at("Downloads") to Used(9.0, ago(minutes = 9)),
							at("Music") to Used(2.0, ago(days = 5)),
						),
					),
			)

		return RecentPage.of(made, recent, asker, now, picks = picks) { path ->
			val name = path.fileName.toString()

			Fixtures.row(name, 48_000L.takeIf { '.' in name }, folder = path.parent)
		}
	}

	private fun screenshot(name: String, png: ByteArray) {
		System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
			Path(dir).createDirectories().resolve("$name.png").writeBytes(png)
		}
	}

	@Test
	fun thePageIsDrawnInTabsAndInGroups_AsAListAndAsAGallery_InBothPalettes() {
		val tabs = shown(Choosing.SaveAs("c.pdf"), firefox, page = drawnPage())
		val groups = step(tabs, Answer.RecentsViewed(GROUPED)).state

		val files =
			step(
				tabs,
				BrowserEvent.RecentTabChosen(RecentSection.ApplicationFiles),
			).state

		val gallery = step(tabs, BrowserEvent.Viewed(Layout.Gallery)).state
		val groupsGallery = step(groups, BrowserEvent.Viewed(Layout.Gallery)).state

		val explorer =
			shown(Choosing.Exploring(), null, page = drawnPage(null, Picked.entries.toSet())).let {
				step(
					it,
					BrowserEvent.Clicked(
						RowId.Recent(at("Downloads"), RecentSection.Relevant),
						Click.Only,
					),
				).state
			}

		// The page as docs/picker.md shows it.
		screenshot("picker-recents", renderedPng(tabs, 1100, 900, Palette.Light, scale = 2f))

		listOf(
			"tabs" to tabs,
			"tabs-files" to files,
			"groups" to groups,
			"tabs-gallery" to gallery,
			"groups-gallery" to groupsGallery,
			"explorer" to explorer,
		).forEach { (name, state) ->
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png = renderedPng(state, 1100, 720, palette, scale = 2f)

				assertTrue(png.size > 20_000, "$name-$tone: ${png.size} bytes")
				screenshot("relevant-$name-$tone", png)
			}
		}
	}

	@OptIn(ExperimentalTestApi::class)
	@Test
	fun onlyTheRecentsPageHasTheButtons_ATabPressedIsShown_TheTuningOpensBelow() {
		for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark))
			runSkikoComposeUiTest(Size(2200f, 1440f), Density(2f)) {
				val events = mutableListOf<BrowserEvent>()
				var state = shown(Choosing.SaveAs("c.pdf"), firefox, page = drawnPage())

				setContent {
					BrowserTheme(palette) {
						CompositionLocalProvider(LocalMotion provides false) {
							FileBrowser(state) { events += it }
						}
					}
				}
				waitForIdle()

				onNodeWithTag("recent-tabs").assertExists()
				onNodeWithTag("recent-tab:Most relevant folders")
					.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
				onNodeWithTag("recent-tabbed")
					.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
					.performClick()
				onNodeWithTag("recent-tab:Recent files").performClick()
				onNodeWithTag("listing").performKeyInput {
					withKeyDown(Key.CtrlLeft) { pressKey(Key.PageDown) }
				}
				assertEquals(
					listOf(
						BrowserEvent.RecentsTabbed(false),
						BrowserEvent.RecentTabChosen(RecentSection.Files),
						// The window is drawn as it was: the tab after the first.
						BrowserEvent.RecentTabChosen(RecentSection.NewFiles),
					),
					events.filter {
						it is BrowserEvent.RecentsTabbed || it is BrowserEvent.RecentTabChosen
					},
				)

				// The tuning, open: what each is set to; a slider moved is a tuning of the page.
				onNodeWithTag("relevance").performClick()
				waitForIdle()
				onNodeWithTag("relevance-tuning").assertExists()
				onNodeWithTag("relevance-shown-value").assertExists()
				// The keyboard is on the first slider, and moves it; the pointer sets any other.
				onNodeWithTag("relevance-recency").performKeyInput { pressKey(Key.DirectionRight) }
				waitForIdle()
				assertEquals(
					BrowserEvent.RelevanceTuned(Relevance(recency = 1.1)),
					events.last { it is BrowserEvent.RelevanceTuned },
				)
				onNodeWithTag(
					"relevance-shown",
				).performSemanticsAction(SemanticsActions.SetProgress) {
					it(5f)
				}
				waitForIdle()
				assertEquals(
					BrowserEvent.RelevanceTuned(Relevance(shown = 9)),
					events.last { it is BrowserEvent.RelevanceTuned },
				)

				// The window, and the tuning over it in a layer of its own.
				val layers =
					onAllNodes(isRoot()).fetchSemanticsNodes().indices.map {
						onAllNodes(isRoot())[it]
					}
				val window = layers.first().captureToImage().toAwtImage()

				layers.drop(1).forEach { layer ->
					window.graphics.drawImage(layer.captureToImage().toAwtImage(), 0, 0, null)
				}
				screenshot(
					"relevant-tuning-$tone",
					java.io
						.ByteArrayOutputStream()
						.also { javax.imageio.ImageIO.write(window, "png", it) }
						.toByteArray(),
				)
			}

		// Anywhere else, neither is there.
		runSkikoComposeUiTest(Size(2200f, 1440f), Density(2f)) {
			setContent {
				BrowserTheme {
					CompositionLocalProvider(LocalMotion provides false) {
						FileBrowser(
							opened(Choosing.OneFile(), home, home, Fixtures.sidebar).settled(Fixtures.Memory),
						)
					}
				}
			}
			waitForIdle()
			onNodeWithTag("thumbnails").assertExists()
			onNodeWithTag("recent-tabbed").assertDoesNotExist()
			onNodeWithTag("relevance").assertDoesNotExist()
		}
	}
}
