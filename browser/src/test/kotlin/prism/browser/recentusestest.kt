package prism.browser

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces
import prism.preferences.MemoryPreferences
import prism.preferences.RecentSection
import prism.preferences.preferences
import prism.shared.Used
import prism.shared.Uses

/**
 * What is recent, beyond the folders folder pickers are answered with (recentstest.kt): the files file pickers are,
 * how much each folder is gone to, and what the user hides from the page.
 */
class RecentUsesTest {
	@TempDir
	lateinit var root: Path

	private val now = Instant.parse("2026-11-12T10:00:00Z")
	private val firefox = AppKey.of("org.mozilla.firefox")!!
	private val home = Fixtures.home

	private fun ago(days: Long = 0, minutes: Long = 0): Instant =
		now.minus(Duration.ofDays(days)).minus(Duration.ofMinutes(minutes))

	private fun folder(name: String): Path = root.resolve(name).createDirectories()

	private fun file(name: String): Path =
		root.resolve(name).also {
			it.parent.createDirectories()
			it.writeText(name)
		}

	// ---- how much a folder is used -----------------------------------------------------------------------------

	@Test
	fun aFoldersUsesAreAWeightThatHalves_AndTheMostUsedAreFoundByIt() {
		val often = Path("/data/often")
		val once = Path("/data/once")
		val longAgo = Path("/data/long-ago")

		val uses =
			(5 downTo 1)
				.fold(Uses<Path>()) { uses, day -> uses.used(often, ago(days = day.toLong())) }
				.used(once, ago(minutes = 1))
				.let { uses ->
					(1..20).fold(uses) { all, _ -> all.used(longAgo, ago(days = 200)) }
				}

		// Five uses this week outweigh one a minute ago, and twenty of most of a year ago.
		assertEquals(
			listOf(often, once, longAgo),
			uses.most(10, now).map { it.first },
		)
		assertEquals(ago(days = 1), uses.uses.getValue(often).at)
		// A weight halves in two weeks.
		assertEquals(0.5, Used(1.0, now.minus(Used.HALF_LIFE)).weightAt(now), 1e-9)
		// The most of them, of those admitted; and capped, the least used are let go.
		assertEquals(listOf(once), uses.most(1, now) { it != often }.map { it.first })
		assertEquals(setOf(often, once), uses.capped(2, now).uses.keys)
		assertEquals(uses, uses.capped(3, now))
	}

	@Test
	fun accessesAreHeldAndWrittenTogether_ReadWithWhatIsHeld_AndKeptInTheirFile() {
		val state = root.resolve("state")
		val a = folder("a")
		val b = folder("an odd\tname\nhere")
		val store = RecentsFiles(state)
		val file = state.resolve("folder-accesses")

		// The first is written at once; those soon after are held, and read all the same.
		store.went(a, now)
		assertTrue(Files.exists(file))

		val written = Files.readAllBytes(file).toList()

		store.went(a, now.plusSeconds(1))
		store.went(b, now.plusSeconds(2))
		assertEquals(written, Files.readAllBytes(file).toList())
		assertEquals(
			setOf(a, b),
			store
				.read()
				.accesses.uses.keys,
		)
		assertTrue(
			store.read().accesses.weightOf(a, now.plusSeconds(2)) > 1.9,
		)

		// Another prism reads what is written, and not what this one holds; flushed, both read the same.
		assertEquals(
			setOf(a),
			RecentsFiles(state)
				.read()
				.accesses.uses.keys,
		)
		store.flush(now.plusSeconds(3))
		assertEquals(store.read().accesses, RecentsFiles(state).read().accesses)
		assertEquals(
			now.plusSeconds(2),
			RecentsFiles(state)
				.read()
				.accesses.uses
				.getValue(b)
				.at,
		)

		// One told long enough after the last writing is written with it.
		store.went(b, now.plus(RecentStore.WRITE_EVERY).plusSeconds(4))
		assertTrue(
			RecentsFiles(state).read().accesses.weightOf(b, now.plusSeconds(20)) > 1.9,
		)
	}

	@Test
	fun theFilesAndTheFoldersAreKeptApart_AndTheOldFoldersFileIsReadAsItWas() {
		val state = root.resolve("state").createDirectories()
		val downloads = folder("Downloads")
		val report = file("Documents/report.pdf")

		// As a prism before this one wrote it: a folder for Firefox, in everyone's list and its own.
		state.resolve("recent-folders").writeBytes(
			(
				"${ago(minutes = 30).toEpochMilli()}\t\t$downloads\n" +
					"${ago(minutes = 30).toEpochMilli()}\torg.mozilla.firefox\t$downloads\n"
			).toByteArray(),
		)

		val store = RecentsFiles(state)

		assertEquals(
			listOf(downloads),
			store
				.read()
				.folders.all.paths,
		)
		assertEquals(
			listOf(downloads),
			store
				.read()
				.folders
				.of(firefox)
				.paths,
		)

		store.picked(listOf(report), Choosing.SaveAs("report.pdf"), firefox, now)

		val read = RecentsFiles(state).read()

		assertEquals(listOf(report), read.files.of(firefox).paths)
		assertEquals(listOf(report), read.files.all.paths)
		// The folder the file is in is no recent folder: those are as they were.
		assertEquals(listOf(downloads), read.folders.all.paths)
		assertTrue(Files.exists(state.resolve("recent-files")))

		// A file that is gone is left out of what is next written.
		Files.delete(report)
		store.picked(
			listOf(file("Documents/other.pdf")),
			Choosing.OneFile(),
			null,
			now.plusSeconds(1),
		)
		assertEquals(
			listOf(root.resolve("Documents/other.pdf")),
			RecentsFiles(state)
				.read()
				.files.all.paths,
		)
	}

	// ---- the page ----------------------------------------------------------------------------------------------

	/** What is recent, as a person's might be: files and folders, the application's and others', and what is used. */
	private val used: Recents =
		Recents(
			folders =
				Recently()
					.used(listOf(home.resolve("Projects/prism/docs")), null, ago(days = 3))
					.used(listOf(home.resolve("Pictures/Screenshots")), firefox, ago(days = 2))
					.used(listOf(home.resolve("Downloads")), firefox, ago(minutes = 9)),
			files =
				Recently()
					.used(listOf(home.resolve("Documents/Taxes/2025 return.pdf")), null, ago(days = 12))
					.used(listOf(home.resolve("Downloads/invoice-4471.pdf")), firefox, ago(minutes = 14))
					.used(listOf(home.resolve("Pictures/cat.png")), null, ago(minutes = 5)),
			accesses =
				Uses(
					mapOf(
						home.resolve("Downloads") to Used(9.0, ago(minutes = 9)),
						home.resolve("Projects/prism") to Used(14.0, ago(days = 1)),
						home.resolve("Music") to Used(2.0, ago(days = 5)),
						home.resolve("Gone") to Used(30.0, ago(days = 1)),
					),
				),
		)

	private val files =
		setOf("2025 return.pdf", "invoice-4471.pdf", "cat.png", "new.txt")

	private fun page(asker: AppKey?, shows: Boolean = true): RecentPage =
		RecentPage.of(
			listOf(NewFile(home.resolve("Documents/new.txt"), ago(minutes = 2))),
			used,
			asker,
			now,
			files = shows,
		) { path ->
			Fixtures
				.row(
					path.fileName.toString(),
					100L.takeIf { path.fileName.toString() in files },
					folder = path.parent,
				).takeUnless { path.fileName.toString() == "Gone" }
		}

	private fun RecentPage.said(): List<Pair<String, List<String>>> =
		sections.map { section -> section.kind.title to section.entries.map { it.row.name } }

	@Test
	fun thePageHasItsSixSectionsInOrder_ForAnApplication_ForNobody_AndForAFolderPicker() {
		assertEquals(
			listOf(
				"New files" to listOf("new.txt"),
				"Recent files for this application" to listOf("invoice-4471.pdf"),
				// Everyone's, without the application's own, the latest first.
				"Recent files" to listOf("cat.png", "2025 return.pdf"),
				"Recent folders for this application" to listOf("Downloads", "Screenshots"),
				"Recent folders" to listOf("docs"),
				// The most used first, whenever each was last used; one that is gone is not there.
				"Frequently accessed" to listOf("prism", "Downloads", "Music"),
			),
			page(firefox).said(),
		)
		// For nobody (an explorer, an application nobody can tell): nothing is the application's.
		assertEquals(
			listOf(
				"New files" to listOf("new.txt"),
				"Recent files" to listOf("cat.png", "invoice-4471.pdf", "2025 return.pdf"),
				"Recent folders" to listOf("Downloads", "Screenshots", "docs"),
				"Frequently accessed" to listOf("prism", "Downloads", "Music"),
			),
			page(null).said(),
		)
		// A folder picker shows no files: the folders' sections alone.
		assertEquals(
			listOf(
				"Recent folders for this application",
				"Recent folders",
				"Frequently accessed",
			),
			page(firefox, shows = false).sections.map { it.kind.title },
		)

		// A folder in two sections is two rows, each its section's; and what is frequent is never long before.
		val whole = page(firefox)
		val downloads = whole.rows.filter { it.name == "Downloads" }

		assertEquals(
			listOf(
				RowId.Recent(home.resolve("Downloads"), RecentSection.Application),
				RowId.Recent(home.resolve("Downloads"), RecentSection.Frequent),
			),
			downloads.map { it.id },
		)
		assertEquals(whole.rows.size, whole.marks.size)
		assertTrue(
			whole.sections
				.last()
				.entries
				.none { it.stale },
		)
		// When a frequent folder was last used is what is said of it.
		assertEquals(
			ago(days = 1),
			whole.marks
				.getValue(
					RowId.Recent(home.resolve("Projects/prism"), RecentSection.Frequent),
				).at,
		)
		// A recent file long before its section's newest is drawn quieter, as a folder is.
		assertTrue(
			whole.marks
				.getValue(
					RowId.Recent(home.resolve("Documents/Taxes/2025 return.pdf"), RecentSection.Files),
				).stale,
		)
	}

	// ---- hidden ------------------------------------------------------------------------------------------------

	private fun shown(choosing: Choosing, asker: AppKey?): BrowserState {
		val window =
			opened(choosing, home, home, Fixtures.sidebar, filters = Fixtures.filters)
				.settled(Fixtures.Memory)
				.copy(asker = asker)

		return step(
			step(window, BrowserEvent.OpenedRecents).state,
			Answer.RecentsRead(page(asker, shows = choosing !is Choosing.Folder)),
		).state
	}

	private fun BrowserState.names(): List<String> = rows.list.map { it.name }

	private fun BrowserState.menu(name: String): List<String> =
		contextMenuOf(this, Target.Rows(rows.list.filter { it.name == name }.take(1)))
			.entries
			.filterIsInstance<MenuEntry.Item<BrowserEvent>>()
			.map { it.label }

	@Test
	fun whatIsHiddenFromRecentIsInNoSection_IsKeptForEveryWindow_AndIsStillCounted() {
		val picker = shown(Choosing.OneFile(), firefox)
		val downloads = home.resolve("Downloads")

		assertEquals(2, picker.names().count { it == "Downloads" })
		assertTrue("Hide from recent" in picker.menu("Downloads"))
		assertTrue("Hide from recent" in picker.menu("invoice-4471.pdf"))

		// Chosen and hidden: gone from both its sections at once, and what was chosen of it let go.
		val chosen =
			step(
				picker,
				BrowserEvent.Clicked(
					RowId.Recent(downloads, RecentSection.Application),
					Click.Only,
				),
			).state

		val hid = step(chosen, BrowserEvent.RecentHid(listOf(downloads), hidden = true))

		assertEquals(0, hid.state.names().count { it == "Downloads" })
		assertEquals(emptySet<RowId>(), hid.state.choosing.rows)
		assertEquals(picker.entryCount - 2, hid.state.entryCount)
		assertEquals(listOf<Effect>(Effect.HideRecent(listOf(downloads), true)), hid.effects)

		// A section left with nothing has no heading.
		val all =
			step(
				hid.state,
				BrowserEvent.RecentHid(listOf(home.resolve("Pictures/Screenshots")), hidden = true),
			).state

		assertFalse(
			(all.listing as Listing.Recent)
				.shown(all.arrangement)
				.headings
				.any { it.kind == RecentPage.Kind.Application },
		)

		// Kept in the preferences, where every window reads it; and another window is told.
		val preferences = MemoryPreferences()

		hid.settled(Services(Fixtures.Memory, preferences = preferences))
		assertEquals(setOf(downloads.toString()), preferences.preferences.value.recentsHidden)

		val other = step(picker, Answer.RecentsHid(setOf(downloads))).state

		assertEquals(0, other.names().count { it == "Downloads" })

		// A picker is another application's window: it never offers to show what is hidden.
		assertFalse(hid.state.recentsHides)
		assertFalse(
			step(hid.state, BrowserEvent.RecentHiddenShown(true)).state.recentsHides,
		)

		// It is only not shown: a store goes on recording it, and counting it.
		val store = MemoryRecents(used)

		store.picked(listOf(downloads), Choosing.Folder(), firefox, now)
		assertEquals(
			downloads,
			store
				.read()
				.folders
				.of(firefox)
				.paths
				.first(),
		)
		assertTrue(store.read().accesses.weightOf(downloads, now) > 9.0)
	}

	@Test
	fun anExplorerShowsWhatIsHiddenWhereAsked_Dimmed_AndPutsItBack() {
		val downloads = home.resolve("Downloads")
		val explorer = shown(Choosing.Exploring(), null)

		// Nothing hidden: nothing to show.
		assertFalse(explorer.recentsHides)

		val hid = step(explorer, BrowserEvent.RecentHid(listOf(downloads), hidden = true)).state

		assertTrue(hid.recentsHides)
		assertEquals(0, hid.names().count { it == "Downloads" })

		// Shown all the same: in place, in each of its sections, and marked as what is hidden.
		val showing = step(hid, BrowserEvent.RecentHiddenShown(true)).state
		val page = (showing.listing as Listing.Recent).shown(showing.arrangement)

		assertEquals(explorer.names(), showing.names())
		assertEquals(
			setOf(
				RowId.Recent(downloads, RecentSection.Folders),
				RowId.Recent(downloads, RecentSection.Frequent),
			),
			page.dimmed,
		)
		assertTrue("Show in recent" in showing.menu("Downloads"))
		assertFalse("Hide from recent" in showing.menu("Downloads"))
		assertTrue("Hide from recent" in showing.menu("Music"))

		// Put back: no longer hidden, here and in the preferences, and nothing is left to show.
		val back = step(showing, BrowserEvent.RecentHid(listOf(downloads), hidden = false))

		assertEquals(listOf<Effect>(Effect.HideRecent(listOf(downloads), false)), back.effects)
		assertEquals(
			emptySet<RowId>(),
			(back.state.listing as Listing.Recent).shown(back.state.arrangement).dimmed,
		)
		assertFalse(back.state.recentsHides)
		assertEquals(explorer.names(), back.state.names())

		// What a window shows of the hidden is its own: it is not kept, and another window does not show them.
		assertFalse(step(explorer, Answer.RecentsHid(setOf(downloads))).state.recentsShowHidden)
		// Anywhere but on the page, nothing is hidden from a listing.
		assertEquals(
			RecentArranged.NONE,
			picker().copy(recentsHidden = setOf(downloads)).arrangement.recent,
		)
	}

	@Test
	fun thePageOfSixSectionsIsDrawn_SomeCollapsed_AndWithWhatIsHiddenShown() {
		val picker = shown(Choosing.OneFile(), firefox)
		val folders = shown(Choosing.Folder(), firefox)
		val explorer = shown(Choosing.Exploring(), null)

		val some =
			step(
				picker,
				Answer.RecentsFolded(setOf(RecentSection.NewFiles, RecentSection.Folders)),
			).state

		val hid =
			step(
				explorer,
				BrowserEvent.RecentHid(
					listOf(home.resolve("Downloads"), home.resolve("Pictures/cat.png")),
					hidden = true,
				),
			).state

		val showing = step(hid, BrowserEvent.RecentHiddenShown(true)).state

		listOf(
			"picker-light" to Triple(picker, Palette.Light, 1100 to 900),
			"picker-dark" to Triple(picker, Palette.Dark, 1100 to 900),
			"picker-narrow" to Triple(picker, Palette.Light, 760 to 900),
			"picker-collapsed" to Triple(some, Palette.Light, 1100 to 760),
			"folder-picker" to Triple(folders, Palette.Dark, 1100 to 700),
			"explorer-hid" to Triple(hid, Palette.Light, 1100 to 760),
			"explorer-hidden-shown-light" to Triple(showing, Palette.Light, 1100 to 760),
			"explorer-hidden-shown-dark" to Triple(showing, Palette.Dark, 1100 to 760),
		).forEach { (name, look) ->
			val (state, palette, size) = look
			val png = renderedPng(state, size.first, size.second, palette, scale = 2f)

			assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
			System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
				Path(dir).createDirectories().resolve("recents3-$name.png").writeBytes(png)
			}
		}
	}

	private fun picker(): BrowserState =
		opened(Choosing.OneFile(), root, root, sidebarOf(XdgPlaces(), root)).settled(JvmDisk())

	// ---- going to a folder -------------------------------------------------------------------------------------

	@Test
	fun aFolderGoneToIsCounted_ByAnyMeans_AndOneReadAgainIsNot() {
		val docs = folder("docs")

		folder("docs/inner")
		file("docs/a.txt")

		val recents = MemoryRecents()

		runBlocking {
			val scope =
				kotlinx.coroutines.CoroutineScope(
					kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
				)

			val session =
				Session(
					opened(Choosing.Exploring(), docs, root, sidebarOf(XdgPlaces(), root)),
					Services(JvmDisk(), recents = recents),
					scope,
					io = kotlinx.coroutines.Dispatchers.Unconfined,
					clock = { now },
				)

			suspend fun at(folder: Path) =
				kotlinx.coroutines.withTimeout(15_000) {
					session.state.first { it.folder == folder && it.listing is Listing.Shown }
				}

			try {
				at(docs)
				// Read again: the same folder, and no going to it.
				session.send(BrowserEvent.Refreshed)
				at(docs)
				session.send(BrowserEvent.Activated(RowId("inner")))
				at(docs.resolve("inner"))
				session.send(BrowserEvent.Back)
				at(docs)
			} finally {
				scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
			}
		}

		val accesses = recents.read().accesses

		assertEquals(setOf(docs, docs.resolve("inner")), accesses.uses.keys)
		// Opened in, and come back to: twice. Gone into once.
		assertEquals(2.0, accesses.weightOf(docs, now), 1e-9)
		assertEquals(1.0, accesses.weightOf(docs.resolve("inner"), now), 1e-9)
		assertNull(accesses.uses[root])
	}
}
