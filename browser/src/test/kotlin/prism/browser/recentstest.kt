package prism.browser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
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
import prism.index.Index
import prism.index.Indexer
import prism.index.Where
import prism.preferences.Builtin
import prism.preferences.MemoryPreferences
import prism.preferences.PreferenceFile
import prism.preferences.Preferences
import prism.preferences.QuickAccess
import prism.preferences.QuickItem
import prism.preferences.RecentSection

/**
 * What is recent: the folders pickers were answered from (kept ten for every application and ten for each, the latest
 * first, in a file every prism writes), the files the index saw made last, and the Recents page that shows both.
 */
class RecentsTest {
	@TempDir
	lateinit var root: Path

	private val now = Instant.parse("2026-11-12T10:00:00Z")
	private val firefox = AppKey.of("org.mozilla.firefox")!!
	private val gimp = AppKey.of("org.gimp.GIMP")!!

	private fun ago(days: Long = 0, minutes: Long = 0): Instant =
		now.minus(Duration.ofDays(days)).minus(Duration.ofMinutes(minutes))

	private fun folder(name: String): Path = root.resolve(name).createDirectories()

	private fun file(name: String): Path =
		root.resolve(name).also {
			it.parent.createDirectories()
			it.writeText(name)
		}

	// ---- the lists ---------------------------------------------------------------------------------------------

	@Test
	fun anApplicationIsKnownByWhatThePortalCallsIt_AndOneItCannotTellIsNobody() {
		assertEquals("org.mozilla.firefox", AppKey.of(" org.mozilla.firefox ")?.id)
		assertNull(AppKey.of(""))
		assertNull(AppKey.of("   "))
		assertNull(AppKey.of(null))
	}

	@Test
	fun aListKeepsTheTenLastUsed_TheLatestFirst_EachOnce() {
		val folders = (1..12).map { Path("/data/$it") }
		val list =
			folders.foldIndexed(RecentList.NONE) { index, list, folder ->
				list.used(listOf(folder), now.plusSeconds(index.toLong()))
			}

		// The two first used have gone, to make room.
		assertEquals(RecentList.MOST, list.entries.size)
		assertEquals(folders.drop(2).reversed(), list.folders)
		assertEquals(now.plusSeconds(11), list.entries.first().at)

		// One used again is first again, once, with when it was used now.
		val again = list.used(listOf(Path("/data/5")), now.plusSeconds(60))

		assertEquals(Path("/data/5"), again.folders.first())
		assertEquals(RecentList.MOST, again.entries.size)
		assertEquals(1, again.folders.count { it == Path("/data/5") })
		assertEquals(now.plusSeconds(60), again.entries.first().at)

		// However it is made, it is so: twelve given at once are ten, and one given twice is its latest use.
		val given =
			RecentList.of(
				folders.mapIndexed { index, it -> Recent(it, now.plusSeconds(index.toLong())) } +
					Recent(Path("/data/1"), now.plusSeconds(100)),
			)

		assertEquals(RecentList.MOST, given.entries.size)
		assertEquals(Path("/data/1"), given.folders.first())
	}

	@Test
	fun aUseByAnApplicationIsInItsListAndInEveryApplications_AndOneByNobodyInThatAlone() {
		val downloads = Path("/home/avery/Downloads")
		val pictures = Path("/home/avery/Pictures")
		val work = Path("/home/avery/Work")

		val used =
			RecentFolders()
				.used(listOf(downloads), firefox, ago(minutes = 30))
				.used(listOf(pictures), gimp, ago(minutes = 20))
				.used(listOf(work), null, ago(minutes = 10))

		assertEquals(listOf(work, pictures, downloads), used.all.folders)
		assertEquals(listOf(downloads), used.of(firefox).folders)
		assertEquals(listOf(pictures), used.of(gimp).folders)
		assertEquals(emptyList<Path>(), used.of(null).folders)
		assertEquals(setOf(firefox, gimp), used.apps.keys)

		// Several folders at once (files of a search's results, of more than one folder): each, in the order chosen.
		val several = used.used(listOf(pictures, work), firefox, now)

		assertEquals(listOf(pictures, work, downloads), several.of(firefox).folders)
		assertEquals(listOf(pictures, work, downloads), several.all.folders)
		// Nothing used changes nothing.
		assertEquals(several, several.used(emptyList(), firefox, now))
	}

	@Test
	fun whatAPickerWasAnsweredFromIsTheFolderItself_OrTheFoldersOfTheFiles() {
		val a = Path("/data/a/one.txt")
		val b = Path("/data/a/two.txt")
		val c = Path("/data/c/three.txt")

		assertEquals(
			listOf(Path("/data/a")),
			foldersUsed(listOf(Path("/data/a")), Choosing.Folder()),
		)
		assertEquals(listOf(Path("/data/a")), foldersUsed(listOf(a), Choosing.OneFile()))
		assertEquals(
			listOf(Path("/data/a"), Path("/data/c")),
			foldersUsed(listOf(a, b, c), Choosing.Files()),
		)
		assertEquals(
			listOf(Path("/data/c")),
			foldersUsed(listOf(c), Choosing.SaveAs("three.txt")),
		)
		assertEquals(emptyList<Path>(), foldersUsed(listOf(a), Choosing.Exploring()))
	}

	@Test
	fun aPickerOpensWhereItsApplicationWasLastAnswered_ThatIsStillThere() {
		val gone = Path("/data/gone")
		val there = Path("/data/there")
		val other = Path("/data/other")

		val used =
			RecentFolders()
				.used(listOf(there), firefox, ago(minutes = 30))
				.used(listOf(gone), firefox, ago(minutes = 20))
				.used(listOf(other), gimp, ago(minutes = 10))

		// The latest of its own that is there; of every application's, for one nobody can tell.
		assertEquals(there, used.last(firefox) { it != gone })
		assertEquals(other, used.last(null) { it != gone })
		// An application answered nowhere yet has no folder of its own, whatever others have.
		assertNull(used.last(AppKey.of("org.example.New")) { true })
		assertNull(RecentFolders().last(null) { true })
		assertNull(used.last(firefox) { false })
	}

	// ---- the file ----------------------------------------------------------------------------------------------

	@Test
	fun theFileKeepsBothLists_AndIsReadBackAsItWasWritten() {
		val file = root.resolve("state/recent-folders")
		val odd = folder("an odd\tname\nof a folder \\ here")
		val downloads = folder("Downloads")
		val pictures = folder("Pictures")
		val store = RecentsFile(file)

		assertEquals(RecentFolders(), store.read())

		store.used(listOf(downloads), firefox, ago(minutes = 30))
		store.used(listOf(pictures), null, ago(minutes = 20))
		store.used(listOf(odd), firefox, ago(minutes = 10))

		val expected =
			RecentFolders()
				.used(listOf(downloads), firefox, ago(minutes = 30))
				.used(listOf(pictures), null, ago(minutes = 20))
				.used(listOf(odd), firefox, ago(minutes = 10))

		assertEquals(expected, store.read())
		// Another prism, reading the same file, finds the same; and each use carries when it was.
		assertEquals(expected, RecentsFile(file).read())
		assertEquals(
			ago(minutes = 10),
			store
				.read()
				.all.entries
				.first()
				.at,
		)
		assertEquals(listOf(odd, pictures, downloads), store.read().all.folders)
		assertEquals(listOf(odd, downloads), store.read().of(firefox).folders)
	}

	@Test
	fun aFolderThatIsGoneIsLeftOutOfWhatIsNextWritten() {
		val file = root.resolve("recent-folders")
		val kept = folder("kept")
		val gone = folder("gone")
		val store = RecentsFile(file)

		store.used(listOf(gone), firefox, ago(minutes = 30))
		store.used(listOf(kept), firefox, ago(minutes = 20))
		assertEquals(listOf(kept, gone), store.read().all.folders)

		Files.delete(gone)
		store.used(listOf(kept), null, ago(minutes = 10))

		assertEquals(listOf(kept), store.read().all.folders)
		assertEquals(listOf(kept), store.read().of(firefox).folders)
	}

	@Test
	fun writersAtOnce_OfOnePrismAndOfSeveral_LoseNoUse() {
		val file = root.resolve("recent-folders")
		val folders = (1..8).map { folder("used-$it") }

		// Each its own store, as each prism has; all writing at once.
		val writers =
			folders.mapIndexed { index, used ->
				Thread {
					RecentsFile(file).used(
						listOf(used),
						if (index % 2 == 0) firefox else gimp,
						now.plusSeconds(index.toLong()),
					)
				}
			}

		writers.forEach(Thread::start)
		writers.forEach(Thread::join)

		val read = RecentsFile(file).read()

		assertEquals(folders.reversed(), read.all.folders)
		assertEquals(
			folders
				.filterIndexed {
					index,
					_,
					->
					index % 2 == 0
				}.reversed(),
			read.of(firefox).folders,
		)
		assertEquals(
			folders
				.filterIndexed {
					index,
					_,
					->
					index % 2 == 1
				}.reversed(),
			read.of(gimp).folders,
		)
	}

	@Test
	fun aFileThatIsNotOneIsNoFolders_AndIsWrittenOverByTheNextUse() {
		val file = root.resolve("recent-folders")
		val used = folder("used")

		file.writeBytes(byteArrayOf(0, 1, 2, '\n'.code.toByte(), 9))
		assertEquals(RecentFolders(), RecentsFile(file).read())

		RecentsFile(file).used(listOf(used), null, now)
		assertEquals(listOf(used), RecentsFile(file).read().all.folders)
	}

	// ---- the page ----------------------------------------------------------------------------------------------

	private val asker = firefox

	/** The page of [newest] and [folders] for [asker], everything there, each a file unless its name ends in `/`. */
	private fun page(
		newest: List<NewFile>,
		folders: RecentFolders,
		asker: AppKey? = this.asker,
		gone: Set<Path> = emptySet(),
	): RecentPage {
		val files = newest.map { it.path }.toSet()

		return RecentPage.of(newest, folders, asker, now) { path ->
			Fixtures
				.row(path.fileName.toString(), 100L.takeIf { path in files }, folder = path.parent)
				.copy(id = RowId.Recent(path))
				.takeUnless { path in gone }
		}
	}

	private fun RecentPage.shown(): List<Pair<String, List<String>>> =
		sections.map { section ->
			section.kind.title to
				section.entries.map { it.row.name + if (it.stale) " (stale)" else "" }
		}

	@Test
	fun thePageShowsNewFiles_TheApplicationsFolders_ThenTheOthers_EachFolderOnce() {
		val downloads = Path("/home/avery/Downloads")
		val pictures = Path("/home/avery/Pictures")
		val work = Path("/home/avery/Work")

		val folders =
			RecentFolders()
				.used(listOf(work), null, ago(days = 2))
				.used(listOf(downloads), firefox, ago(minutes = 30))
				.used(listOf(pictures), AppKey.of("org.gimp.GIMP"), ago(minutes = 20))

		val newest =
			listOf(
				NewFile(Path("/home/avery/Pictures/shot.png"), ago(minutes = 2)),
				NewFile(Path("/home/avery/Downloads/report.pdf"), ago(days = 1)),
			)

		assertEquals(
			listOf(
				"New files" to listOf("shot.png", "report.pdf"),
				"Recent folders for this application" to listOf("Downloads"),
				// Downloads is every application's too, and shown once: as Firefox's.
				"Recent folders" to listOf("Pictures", "Work"),
			),
			page(newest, folders).shown(),
		)

		// For nobody that can be told: every application's folders, all of them, under the one heading.
		assertEquals(
			listOf(
				"New files" to listOf("shot.png", "report.pdf"),
				"Recent folders" to listOf("Pictures", "Downloads", "Work"),
			),
			page(newest, folders, asker = null).shown(),
		)

		// A section with nothing in it is not there; nor is a page of nothing anything.
		assertEquals(
			listOf("Recent folders for this application" to listOf("Downloads")),
			page(emptyList(), RecentFolders().used(listOf(downloads), firefox, now)).shown(),
		)
		assertEquals(
			emptyList<Pair<String, List<String>>>(),
			page(emptyList(), RecentFolders()).shown(),
		)
	}

	@Test
	fun anEntryLongBeforeItsSectionsNewestIsStale_HoweverLongAgoTheNewestWas() {
		val folders =
			RecentFolders()
				// The newest here was a month ago: the others are counted from it, not from today.
				.used(listOf(Path("/data/a")), firefox, ago(days = 45))
				.used(listOf(Path("/data/b")), firefox, ago(days = 39))
				.used(listOf(Path("/data/c")), firefox, ago(days = 30))
				.used(listOf(Path("/data/d")), null, ago(days = 11))
				.used(listOf(Path("/data/e")), null, ago(days = 0))

		val newest =
			listOf(
				NewFile(Path("/data/new.txt"), ago(minutes = 5)),
				NewFile(Path("/data/week.txt"), ago(days = 9)),
				NewFile(Path("/data/old.txt"), ago(days = 12)),
			)

		assertEquals(
			listOf(
				"New files" to listOf("new.txt", "week.txt", "old.txt (stale)"),
				"Recent folders for this application" to listOf("c", "b", "a (stale)"),
				"Recent folders" to listOf("e", "d (stale)"),
			),
			page(newest, folders).shown(),
		)

		// What the page says of a row is what its section does.
		val made = page(newest, folders)
		val old = made.marks.getValue(RowId.Recent(Path("/data/old.txt")))

		assertEquals(RecentPage.Kind.NewFiles, old.section)
		assertEquals(ago(days = 12), old.at)
		assertTrue(old.stale)
		assertEquals(made.rows.map { it.id }, made.marks.keys.toList())
	}

	@Test
	fun whatIsGoneIsNotShown_AndTheTenNewestFilesThatAreThereAre() {
		val newest =
			(1..30).map {
				NewFile(Path("/data/file-%02d.txt".format(it)), ago(minutes = it.toLong()))
			}
		// Every other one deleted since the index saw it.
		val gone = newest.map { it.path }.filterIndexed { index, _ -> index % 2 == 0 }.toSet()

		val folders =
			RecentFolders()
				.used(listOf(Path("/data/kept")), firefox, ago(minutes = 2))
				.used(listOf(Path("/data/removed")), firefox, ago(minutes = 1))

		val made = page(newest, folders, gone = gone + setOf(Path("/data/removed")))

		assertEquals(
			listOf(
				"New files" to (1..RecentPage.NEW_SHOWN).map { "file-%02d.txt".format(it * 2) },
				"Recent folders for this application" to listOf("kept"),
			),
			made.shown(),
		)
	}

	@Test
	fun howLongAgoIsSaidInAFewWords() {
		val zone = ZoneOffset.UTC

		fun said(at: Instant) = agoOf(at, now, zone)

		assertEquals("Just now", said(now))
		assertEquals("Just now", said(now.minusSeconds(59)))
		// A clock set back since says nothing odd.
		assertEquals("Just now", said(now.plusSeconds(600)))
		assertEquals("1 minute ago", said(now.minusSeconds(60)))
		assertEquals("45 minutes ago", said(ago(minutes = 45)))
		assertEquals("1 hour ago", said(ago(minutes = 60)))
		assertEquals("9 hours ago", said(ago(minutes = 9 * 60 + 59)))
		// Yesterday is the day before, however few hours ago, unless it was only late last night.
		assertEquals("Yesterday", said(Instant.parse("2026-11-11T20:00:00Z")))
		assertEquals("Yesterday", said(Instant.parse("2026-11-11T00:00:00Z")))
		assertEquals(
			"2 hours ago",
			agoOf(
				Instant.parse("2026-11-11T23:30:00Z"),
				Instant.parse("2026-11-12T01:30:00Z"),
				zone,
			),
		)
		assertEquals("2 days ago", said(ago(days = 2)))
		assertEquals("29 days ago", said(ago(days = 29)))
		assertEquals("13 Oct", said(ago(days = 30)))
		assertEquals("12 Nov 2025", said(Instant.parse("2025-11-12T10:00:00Z")))
	}

	// ---- the index's newest, and the page read ---------------------------------------------------------------

	private fun indexer(at: Instant) =
		Indexer(mounts = emptyList(), clock = Clock.fixed(at, ZoneOffset.UTC))

	/** An index of [root] as it was a month ago, with what each of [made] makes since seen when it says. */
	private fun indexed(vararg made: Pair<Instant, () -> Unit>): Index =
		made.fold(Index.EMPTY.with(indexer(ago(days = 30)).index(root))) { index, (at, make) ->
			make()
			index.with(indexer(at).reread(Where.of(root))!!)
		}

	private fun services(index: Index, recents: RecentStore = MemoryRecents()) =
		Services(
			JvmDisk(),
			StaticFinder(index, home = root),
			recents = recents,
			clock = { now },
		)

	private fun Services.page(
		files: TypeFilter? = TypeFilter.ALL_FILES,
		asker: AppKey? = null,
	): RecentPage = (answer(Effect.ReadRecents(asker, files)) as Answer.RecentsRead).page

	private fun RecentPage.newFiles(): List<String> =
		sections
			.firstOrNull { it.kind == RecentPage.Kind.NewFiles }
			?.entries
			.orEmpty()
			.map { root.relativize(it.row.path!!).toString() }

	@Test
	fun theNewestFilesAreThoseTheIndexSawMadeLast_ThatAPersonWouldKnowAsTheirOwn() {
		file("there-before.txt")

		val index =
			indexed(
				ago(days = 3) to { file("docs/older.txt") },
				ago(minutes = 30) to {
					file("docs/report.pdf")
					file("pictures/shot.png")
					// What programs keep for themselves, and folders, are not a person's new files.
					file(".cache/state.json")
					file("docs/.hidden.txt")
					folder("docs/made")
				},
				ago(minutes = 5) to { file("docs/made/notes.md") },
			)

		val all = services(index).page()

		assertEquals(
			listOf("docs/made/notes.md", "docs/report.pdf", "pictures/shot.png", "docs/older.txt"),
			all.newFiles().take(1) +
				all
					.newFiles()
					.drop(1)
					.take(2)
					.sorted() +
				all.newFiles().drop(3),
		)
		// When each was made is when the index saw it, and the page says when it was read.
		assertEquals(
			ago(minutes = 5),
			all.sections
				.single()
				.entries
				.first()
				.at,
		)
		assertEquals(now, all.read)
		// Each is a row as a folder's is, of where it is, so two of one name are two rows.
		assertEquals(
			RowId.Recent(root.resolve("docs/made/notes.md")),
			all.rows.first().id,
		)

		// Of those the picker's filter of names admits; and none at all, for a picker of folders.
		assertEquals(
			listOf("pictures/shot.png"),
			services(index).page(TypeFilter("Images", listOf("*.png", "*.jpg"))).newFiles(),
		)
		assertEquals(emptyList<String>(), services(index).page(files = null).newFiles())
		// With no index, there are none to show.
		assertEquals(
			emptyList<RecentPage.Section>(),
			Services(JvmDisk(), clock = { now }).page().sections,
		)
	}

	@Test
	fun aFileMadeAndDeletedTakesNoPlace_TheTenNewestThatAreThereAreShown() {
		val kept = (1..10).map { "kept/file-%02d.txt".format(it) }
		val passing = (1..95).map { "passing/tmp-%02d.txt".format(it) }

		val index =
			indexed(
				ago(days = 1) to { kept.forEach(::file) },
				// More than are asked of the index at first, every one newer than those kept.
				ago(minutes = 10) to { passing.forEach(::file) },
			)

		assertTrue(passing.size + kept.size > RecentPage.NEW_KEPT)
		// The index has not caught up with their going: it lists them still.
		passing.forEach { root.resolve(it).deleteExisting() }

		val shown = services(index).page().newFiles()

		assertEquals(RecentPage.NEW_SHOWN, shown.size)
		assertEquals(kept, shown.sorted())
		assertFalse(shown.any { it.startsWith("passing/") })
	}

	@Test
	fun thePageReadShowsTheStoresFolders_ForTheApplicationAsking() {
		val downloads = folder("Downloads")
		val pictures = folder("Pictures")
		val gone = root.resolve("gone")

		val recents =
			MemoryRecents(
				RecentFolders()
					.used(listOf(downloads), firefox, ago(minutes = 30))
					.used(listOf(gone), firefox, ago(minutes = 20))
					.used(listOf(pictures), null, ago(minutes = 10)),
			)

		val read = services(Index.EMPTY, recents).page(asker = asker)

		assertEquals(
			listOf(
				"Recent folders for this application" to listOf("Downloads"),
				"Recent folders" to listOf("Pictures"),
			),
			read.sections.map { section ->
				section.kind.title to
					section.entries.map { it.row.name }
			},
		)
	}

	// ---- drawn --------------------------------------------------------------------------------------------------

	private val home = Fixtures.home

	/** A page as a person's might be: new files of several folders, an application's folders, and the rest. */
	private fun drawnPage(asker: AppKey?, read: Instant = now): RecentPage {
		val newest =
			listOf(
				"Pictures/Screenshots/Screenshot 2026-11-12 09-58-12.png" to ago(minutes = 2),
				"Downloads/invoice-4471.pdf" to ago(minutes = 14),
				"Downloads/prism-0.9.2.tar.gz" to ago(minutes = 51),
				"Documents/Letters/Landlord, November.odt" to ago(minutes = 3 * 60 + 10),
				"Projects/prism/docs/picker-recents.png" to ago(minutes = 5 * 60),
				"Music/Field recordings/harbour.flac" to ago(days = 1),
				"Videos/Clips/trailer-cut-3.mp4" to ago(days = 2),
				"Documents/Budget 2026.xlsx" to ago(days = 6),
				"Projects/notes/ideas.md" to ago(days = 19),
				"Documents/Taxes/2025 return.pdf" to ago(days = 24),
			).map { (path, at) -> NewFile(home.resolve(path), at) }

		val folders =
			RecentFolders()
				.used(listOf(Path("/mnt/data/Archive/2019")), null, ago(days = 40))
				.used(listOf(home.resolve("Documents/Taxes")), firefox, ago(days = 33))
				.used(listOf(home.resolve("Projects/prism/docs")), null, ago(days = 3))
				.used(listOf(home.resolve("Pictures/Screenshots")), firefox, ago(days = 2))
				.used(listOf(home.resolve("Documents/Letters")), null, ago(days = 1))
				.used(listOf(home.resolve("Pictures")), null, ago(minutes = 4 * 60))
				.used(listOf(home.resolve("Downloads")), firefox, ago(minutes = 9))

		val files = newest.map { it.path }.toSet()

		return RecentPage.of(newest, folders, asker, read) { path ->
			Fixtures
				.row(path.fileName.toString(), 48_000L.takeIf { path in files }, folder = path.parent)
				.copy(id = RowId.Recent(path))
		}
	}

	private fun drawn(
		choosing: Choosing,
		asker: AppKey?,
		page: RecentPage = drawnPage(asker),
	): BrowserState {
		val window =
			opened(choosing, home, home, Fixtures.sidebar, filters = Fixtures.filters)
				.settled(Fixtures.Memory)
				.copy(asker = asker)

		return step(
			step(window, BrowserEvent.OpenedRecents).state,
			Answer.RecentsRead(page),
		).state
	}

	private fun screenshot(name: String, png: ByteArray) {
		System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
			Path(dir).createDirectories().resolve("$name.png").writeBytes(png)
		}
	}

	@Test
	fun thePageIsDrawn_InAPickerAndAnExplorer_InBothPalettes() {
		val picker = drawn(Choosing.OneFile(), asker)
		val explorer = drawn(Choosing.Exploring(), null)

		// A file chosen, and the keyboard on it: as any row is.
		val chosen =
			step(
				picker,
				BrowserEvent.Clicked(
					RowId.Recent(home.resolve("Downloads/invoice-4471.pdf")),
					Click.Only,
				),
			).state

		val empty = drawn(Choosing.OneFile(), asker, RecentPage(emptyList(), now))

		assertEquals(
			listOf("New files", "Recent folders for this application", "Recent folders"),
			picker.listing
				.let { it as Listing.Recent }
				.page.sections
				.map { it.kind.title },
		)
		assertEquals(
			listOf("New files", "Recent folders"),
			explorer.listing
				.let { it as Listing.Recent }
				.page.sections
				.map { it.kind.title },
		)

		// One section collapsed, and every one: each its heading alone, saying how many it holds.
		val one = step(picker, Answer.RecentsFolded(setOf(RecentSection.NewFiles))).state
		val all = step(picker, Answer.RecentsFolded(RecentSection.entries.toSet())).state

		listOf(
			"collapse-open-light" to Triple(picker, Palette.Light, 1100 to 760),
			"collapse-open-dark" to Triple(picker, Palette.Dark, 1100 to 760),
			"collapse-one-light" to Triple(one, Palette.Light, 1100 to 760),
			"collapse-one-dark" to Triple(one, Palette.Dark, 1100 to 760),
			"collapse-all-light" to Triple(all, Palette.Light, 1100 to 760),
			"collapse-all-dark" to Triple(all, Palette.Dark, 1100 to 760),
			"picker-light" to Triple(picker, Palette.Light, 1100 to 760),
			"picker-dark" to Triple(picker, Palette.Dark, 1100 to 760),
			"picker-chosen" to Triple(chosen, Palette.Light, 1100 to 760),
			"picker-narrow" to Triple(picker, Palette.Light, 760 to 620),
			"explorer-light" to Triple(explorer, Palette.Light, 1100 to 700),
			"explorer-dark" to Triple(explorer, Palette.Dark, 1100 to 700),
			"empty" to Triple(empty, Palette.Light, 1100 to 680),
		).forEach { (name, look) ->
			val (state, palette, size) = look
			val png = renderedPng(state, size.first, size.second, palette, scale = 2f)

			assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
			screenshot("recents-$name", png)
		}
	}

	// ---- collapsing ---------------------------------------------------------------------------------------------

	private fun BrowserState.headings(): List<String> =
		(listing as Listing.Recent).shown(arrangement).headings.map {
			"${it.kind.title}: ${it.count}" +
				(if (it.collapsed) " collapsed" else "") + " before ${it.before}"
		}

	private fun recent(path: String): RowId = RowId.Recent(home.resolve(path))

	@Test
	fun aSectionCollapsedIsItsHeadingAlone_ItsEntriesNoRowsOfTheListing() {
		val open = drawn(Choosing.Files(), asker)

		assertEquals(17, open.rows.size)
		assertEquals(17, open.entryCount)
		assertEquals(
			listOf(
				"New files: 10 before 0",
				"Recent folders for this application: 3 before 10",
				"Recent folders: 4 before 13",
			),
			open.headings(),
		)

		// Its heading pressed: collapsed, and kept so.
		val collapsing = step(open, BrowserEvent.RecentSectionToggled(RecentSection.NewFiles))
		val collapsed = collapsing.state

		assertEquals(
			listOf(Effect.FoldRecents(RecentSection.NewFiles, true)),
			collapsing.effects,
		)
		assertEquals(setOf(RecentSection.NewFiles), collapsed.recentsCollapsed)
		// Its heading is still there, over the next section's first row, saying how many it holds.
		assertEquals(
			listOf(
				"New files: 10 collapsed before 0",
				"Recent folders for this application: 3 before 0",
				"Recent folders: 4 before 3",
			),
			collapsed.headings(),
		)
		// Its entries are no rows: not counted, not chosen with every row, not gone to by the keys.
		assertEquals(
			listOf("Downloads", "Screenshots", "Taxes", "Pictures", "Letters", "docs", "2019"),
			collapsed.rows.list.map { it.name },
		)
		assertEquals(7, collapsed.entryCount)
		assertEquals(
			7,
			step(collapsed, BrowserEvent.ChoseAll)
				.state.choosing.rows.size,
		)
		assertEquals(
			recent("Downloads"),
			step(collapsed, BrowserEvent.Moved(Move.First, Carry.Choose)).state.cursor?.at,
		)
		assertNull(collapsed.rows[recent("Downloads/invoice-4471.pdf")])

		// Pressed again: open again, and kept so.
		val opening = step(collapsed, BrowserEvent.RecentSectionToggled(RecentSection.NewFiles))

		assertEquals(listOf(Effect.FoldRecents(RecentSection.NewFiles, false)), opening.effects)
		assertEquals(open.rows.list, opening.state.rows.list)
		assertEquals(open.headings(), opening.state.headings())

		// Every section collapsed: no rows, and three headings, past the last row there is none of.
		val all =
			RecentSection.entries.fold(open) { state, section ->
				step(state, BrowserEvent.RecentSectionToggled(section)).state
			}

		assertEquals(0, all.rows.size)
		assertEquals(0, all.entryCount)
		assertEquals(
			listOf(
				"New files: 10 collapsed before 0",
				"Recent folders for this application: 3 collapsed before 0",
				"Recent folders: 4 collapsed before 0",
			),
			all.headings(),
		)

		// The last collapsed: its heading after the last row, over no row.
		assertEquals(
			listOf(
				"New files: 10 before 0",
				"Recent folders for this application: 3 before 10",
				"Recent folders: 4 collapsed before 13",
			),
			step(open, BrowserEvent.RecentSectionToggled(RecentSection.Folders)).state.headings(),
		)

		// What the filters leave nothing of has no heading, collapsed or open; what they leave some of counts those.
		val narrowed = step(collapsed, BrowserEvent.Searched("s")).state

		assertEquals(
			listOf(
				"New files: 5 collapsed before 0",
				"Recent folders for this application: 3 before 0",
				"Recent folders: 3 before 3",
			),
			narrowed.headings(),
		)
		assertEquals(
			listOf("Recent folders for this application: 1 before 0"),
			step(collapsed, BrowserEvent.Searched("taxes")).state.headings(),
		)
	}

	@Test
	fun theCursorAndWhatIsChosenLeaveASectionAsItCollapses() {
		val open = drawn(Choosing.Files(), asker)
		val invoice = recent("Downloads/invoice-4471.pdf")
		val flac = recent("Music/Field recordings/harbour.flac")

		// Two new files chosen, the cursor on the second, and a folder of another section with them.
		val chosen =
			listOf(
				BrowserEvent.Clicked(invoice, Click.Only),
				BrowserEvent.Clicked(flac, Click.Toggle),
				BrowserEvent.Clicked(recent("Pictures"), Click.Toggle),
				BrowserEvent.Clicked(flac, Click.Toggle),
				BrowserEvent.Clicked(flac, Click.Toggle),
			).fold(open) { state, event -> step(state, event).state }

		assertEquals(setOf(invoice, flac, recent("Pictures")), chosen.choosing.rows)
		assertEquals(flac, chosen.cursor?.at)

		val collapsed =
			step(chosen, BrowserEvent.RecentSectionToggled(RecentSection.NewFiles)).state

		// What was chosen of it is let go; the rest stays. The cursor is on the first row after it.
		assertEquals(setOf(recent("Pictures")), collapsed.choosing.rows)
		assertEquals(recent("Downloads"), collapsed.cursor?.at)
		// And they are not chosen again as it opens.
		assertEquals(
			setOf(recent("Pictures")),
			step(collapsed, BrowserEvent.RecentSectionToggled(RecentSection.NewFiles))
				.state.choosing.rows,
		)

		// In the last section, with nothing after it: the cursor goes to the row before.
		val last =
			step(open, BrowserEvent.Clicked(recent("Documents/Letters"), Click.Only)).state
		val folded = step(last, BrowserEvent.RecentSectionToggled(RecentSection.Folders)).state

		assertEquals(recent("Documents/Taxes"), folded.cursor?.at)
		assertEquals(emptySet<RowId>(), folded.choosing.rows)

		// A cursor elsewhere stays where it is; and with every section collapsed there is nowhere for one to be.
		val elsewhere = step(open, BrowserEvent.Clicked(recent("Pictures"), Click.Only)).state

		assertEquals(
			recent("Pictures"),
			step(
				elsewhere,
				BrowserEvent.RecentSectionToggled(RecentSection.NewFiles),
			).state.cursor?.at,
		)
		assertNull(
			RecentSection.entries
				.fold(elsewhere) { state, section ->
					step(state, BrowserEvent.RecentSectionToggled(section)).state
				}.cursor,
		)
	}

	@Test
	fun whatIsCollapsedIsThePreferences_EveryWindows_AndTheApplicationsSectionWhoeverAsks() {
		val preferences = MemoryPreferences()
		val services = Services(Fixtures.Memory, preferences = preferences, clock = { now })
		val open = drawn(Choosing.OneFile(), asker)

		// Collapsed here, it is written where every window reads it.
		step(
			open,
			BrowserEvent.RecentSectionToggled(RecentSection.Application),
		).settled(services)
		assertEquals(
			setOf(RecentSection.Application),
			preferences.preferences.value.recentsCollapsed,
		)

		// And kept in the file, as it was: read back, the same.
		val file = root.resolve("config/preferences.json")

		PreferenceFile.write(file, preferences.preferences.value)
		assertEquals(
			setOf(RecentSection.Application),
			PreferenceFile.read(file).preferences.recentsCollapsed,
		)
		assertTrue("\"application\"" in Files.readString(file))

		// Another window, told what the preferences say: collapsed there too; and told otherwise, open again.
		val other = step(open, Answer.RecentsFolded(setOf(RecentSection.Application))).state

		assertEquals(
			listOf(
				"New files: 10 before 0",
				"Recent folders for this application: 3 collapsed before 10",
				"Recent folders: 4 before 10",
			),
			other.headings(),
		)
		assertEquals(
			open.headings(),
			step(other, Answer.RecentsFolded(emptySet())).state.headings(),
		)

		// A picker another application asked for: its own folders are that section, and collapsed with it.

		val theirs =
			page(
				emptyList(),
				RecentFolders()
					.used(listOf(Path("/data/brushes")), gimp, ago(minutes = 3))
					.used(listOf(Path("/data/other")), null, ago(minutes = 9)),
				gimp,
			)

		val asked =
			step(
				drawn(Choosing.OneFile(), gimp, theirs),
				Answer.RecentsFolded(setOf(RecentSection.Application)),
			).state

		assertEquals(
			listOf(
				"Recent folders for this application: 1 collapsed before 0",
				"Recent folders: 1 before 0",
			),
			asked.headings(),
		)
		assertEquals(listOf("other"), asked.rows.list.map { it.name })

		// Read while collapsed, a page arrives collapsed: the window knows before the page does.
		val reading =
			step(
				step(open, Answer.RecentsFolded(setOf(RecentSection.NewFiles))).state,
				BrowserEvent.Refreshed,
			)

		assertEquals(
			7,
			step(reading.state, Answer.RecentsRead(drawnPage(asker))).state.rows.size,
		)

		// Elsewhere than on the page, what is collapsed arranges nothing: a folder's rows are the same rows.
		val folder =
			opened(
				Choosing.OneFile(),
				home,
				home,
				Fixtures.sidebar,
			).settled(Fixtures.Memory)

		assertTrue(
			folder.rows ===
				step(folder, Answer.RecentsFolded(setOf(RecentSection.NewFiles))).state.rows,
		)
	}

	/**
	 * A heading under the pointer: its arrow and its title darker, together, and nothing lit across the row. Drawn in
	 * a window of the test's, since only there is there a pointer to rest on it; what it draws is kept where the
	 * pictures are. A row is chosen in it, to be seen in from its heading.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aHeadingUnderThePointerIsDarker_ArrowAndTitleTogether() {
		val picker = drawn(Choosing.OneFile(), asker)

		val one =
			step(
				step(picker, Answer.RecentsFolded(setOf(RecentSection.NewFiles))).state,
				BrowserEvent.Clicked(RowId.Recent(home.resolve("Pictures/Screenshots")), Click.Only),
			).state

		for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark))
			runSkikoComposeUiTest(Size(2200f, 1520f), Density(2f)) {
				setContent {
					BrowserTheme(palette) {
						CompositionLocalProvider(LocalMotion provides false) { FileBrowser(one) }
					}
				}
				waitForIdle()
				onNodeWithTag("recent-section:Recent folders").performMouseInput { moveTo(center) }
				waitForIdle()
				screenshot(
					"recents-heading-hovered-$tone",
					onRoot().captureToImage().toAwtImage().let { image ->
						java.io
							.ByteArrayOutputStream()
							.also {
								javax.imageio.ImageIO.write(image, "png", it)
							}.toByteArray()
					},
				)
			}
	}

	// ---- in the browser ----------------------------------------------------------------------------------------

	private fun picker(
		choosing: Choosing = Choosing.OneFile(),
		asker: AppKey? = null,
	): BrowserState =
		opened(choosing, root, root, sidebarOf(XdgPlaces(), root))
			.settled(JvmDisk())
			.copy(asker = asker)

	@Test
	fun recentsIsFirstInQuickAccess_AndAddedToOneArrangedBeforeItWas() {
		assertEquals(
			Builtin.Recents,
			(QuickAccess.DEFAULT.items.first() as QuickItem.Own).place,
		)

		// A list written before there was such a page gains it, shown, before the first of prism's own.
		val before =
			QuickAccess.of(
				listOf(
					QuickItem.Pinned(Path("/data/work")),
					QuickItem.Own(Builtin.Home),
					QuickItem.Own(Builtin.System, shown = false),
				) +
					Builtin.entries
						.minus(
							setOf(Builtin.Recents, Builtin.Home, Builtin.System),
						).map(QuickItem::Own),
			)

		assertEquals(
			listOf(
				QuickItem.Pinned(Path("/data/work")),
				QuickItem.Own(Builtin.Recents),
				QuickItem.Own(Builtin.Home),
			),
			before.items.take(3),
		)
		assertTrue(before.shows(Builtin.Recents))
		assertFalse(before.shows(Builtin.System))
	}

	@Test
	fun thePageIsOpenedFromTheSidebar_ReadForWhoeverAsked_AndComeBackToAsAnyPlace() {
		file("docs/older.txt")

		val index = indexed(ago(minutes = 5) to { file("docs/new.txt") })
		val used = folder("used")
		val recents =
			MemoryRecents(RecentFolders().used(listOf(used), firefox, ago(minutes = 1)))
		val services = services(index, recents)
		val picker = picker(asker = asker)

		assertTrue(SidebarItem.Recents in picker.sidebar.shown)

		val opening = step(picker, BrowserEvent.OpenedRecents)

		assertEquals(listOf(Effect.ReadRecents(asker, TypeFilter.ALL_FILES)), opening.effects)
		assertEquals(Location.Recents, opening.state.at)
		assertEquals(Listing.Reading, opening.state.listing)

		val shown = opening.settled(services)

		assertEquals("Recents", shown.placeName)
		assertEquals(listOf("new.txt", "used"), shown.rows.list.map { it.name })
		assertEquals(listOf(Location.Folder(root)), shown.history.back)

		// A folder of it gone into, and Back: the page again, read again.
		val into = step(shown, BrowserEvent.Activated(RowId.Recent(used))).settled(services)

		assertEquals(Location.Folder(used), into.at)

		val back = step(into, BrowserEvent.Back)

		assertEquals(Location.Recents, back.state.at)
		assertEquals(listOf(Effect.ReadRecents(asker, TypeFilter.ALL_FILES)), back.effects)

		// Asked for where it is shown, and refreshed, it is read again, what it shows staying meanwhile.
		for (again in listOf(BrowserEvent.OpenedRecents, BrowserEvent.Refreshed)) {
			val rereading = step(shown, again)

			assertEquals(
				listOf(Effect.ReadRecents(asker, TypeFilter.ALL_FILES)),
				rereading.effects,
			)
			assertEquals(shown.rows.list, rereading.state.rows.list)
		}

		// A picker of folders asks for no files; one whose filter changes asks for those it now admits.
		assertEquals(
			listOf(Effect.ReadRecents(null, null)),
			step(picker(Choosing.Folder()), BrowserEvent.OpenedRecents).effects,
		)

		val images = TypeFilter("Images", listOf("*.png"))

		val filtering =
			opened(
				Choosing.OneFile(),
				root,
				root,
				sidebarOf(XdgPlaces(), root),
				filters = listOf(TypeFilter.ALL_FILES, images),
			).settled(JvmDisk())

		assertEquals(
			listOf(Effect.ReadRecents(null, images)),
			step(
				step(filtering, BrowserEvent.OpenedRecents).settled(services),
				BrowserEvent.Filtered(images),
			).effects,
		)

		// Unpinned from the sidebar, it is hidden, as prism's own places are.
		val unpinned = step(picker, BrowserEvent.Unpinned(SidebarItem.Recents))

		assertEquals(
			listOf(Effect.Arrange(picker.sidebar.quick.showing(Builtin.Recents, false))),
			unpinned.effects,
		)
	}

	@Test
	fun aRowOfThePageIsARowAsAnyOther_ChosenOpenedAndSavedOver() {
		val index = indexed(ago(minutes = 5) to { file("docs/new.txt") })
		val used = folder("used")

		val services =
			services(
				index,
				MemoryRecents(RecentFolders().used(listOf(used), null, ago(minutes = 1))),
			)

		val new = RowId.Recent(root.resolve("docs/new.txt"))

		fun onPage(choosing: Choosing) =
			step(picker(choosing), BrowserEvent.OpenedRecents).settled(services)

		// Opening: a file clicked is chosen, and the button opens it; double clicked, it is the answer at once.
		val opening = onPage(Choosing.OneFile())
		val clicked = step(opening, BrowserEvent.Clicked(new, Click.Only)).state

		assertEquals(setOf(new), clicked.choosing.rows)
		assertTrue(clicked.accept.enabled)
		assertEquals(
			listOf(Effect.Finish(Outcome.Chosen(listOf(root.resolve("docs/new.txt"))))),
			step(clicked, BrowserEvent.Accepted).effects,
		)
		assertEquals(
			listOf(Effect.Finish(Outcome.Chosen(listOf(root.resolve("docs/new.txt"))))),
			step(opening, BrowserEvent.Activated(new)).effects,
		)

		// Saving: a file opened is the file to save over, which is asked first.
		val saving =
			step(
				onPage(Choosing.SaveAs("draft.txt")),
				BrowserEvent.Activated(new),
			).state

		assertEquals(Notice.Replace(root.resolve("docs/new.txt")), saving.notice)

		// Exploring: a file is opened with its application, and a folder gone into.
		val exploring = onPage(Choosing.Exploring())

		assertEquals(
			listOf(Effect.Open(listOf(root.resolve("docs/new.txt")))),
			step(exploring, BrowserEvent.Activated(new)).effects,
		)
		assertEquals(
			Location.Folder(used),
			step(exploring, BrowserEvent.Activated(RowId.Recent(used))).state.at,
		)

		// From its menu, a row is shown in its folder, chosen there; and its path copied.
		val menu =
			contextMenuOf(
				exploring,
				Target.Rows(listOf(exploring.rows[new]!!)),
				Bindings.of(Preferences()),
			)

		val items = menu.entries.filterIsInstance<MenuEntry.Item<BrowserEvent>>()

		assertEquals(
			BrowserEvent.Revealed(new),
			items.single { it.label == "Show in folder" }.action,
		)
		assertEquals(
			BrowserEvent.CopiedPaths(listOf(root.resolve("docs/new.txt"))),
			items.single { it.label == "Copy path" }.action,
		)

		val revealed = step(exploring, BrowserEvent.Revealed(new)).settled(services)

		assertEquals(Location.Folder(root.resolve("docs")), revealed.at)
		assertEquals(listOf("new.txt"), revealed.chosenRows.map { it.name })
		// In a folder, where every row is in the folder shown, there is no such entry.
		assertFalse(
			contextMenuOf(revealed, Target.Rows(revealed.chosenRows), Bindings.of(Preferences()))
				.entries
				.filterIsInstance<MenuEntry.Item<BrowserEvent>>()
				.any { it.label == "Show in folder" },
		)

		// What is typed over the listing narrows the page, as it narrows a folder.
		assertEquals(
			listOf("used"),
			step(exploring, BrowserEvent.Searched("use"))
				.state.rows.list
				.map { it.name },
		)
	}

	@OptIn(ExperimentalTestApi::class)
	@Test
	fun inAWindow_ThePageIsUnderItsHeadings_AndItsRowsAreClickedAndMovedThrough() =
		runComposeUiTest {
			val index = indexed(ago(minutes = 5) to { file("docs/new.txt") })
			val used = folder("used")
			val old = folder("old")

			val recents =
				MemoryRecents(
					RecentFolders()
						.used(listOf(old), null, ago(days = 20))
						.used(listOf(used), null, ago(minutes = 1)),
				)

			val services = services(index, recents)
			val running = arrayOfNulls<Session>(1)

			setContent {
				val scope = rememberCoroutineScope()

				val session =
					remember {
						Session(
							opened(Choosing.Exploring(), root, root, sidebarOf(XdgPlaces(), root)),
							services,
							scope,
						)
					}

				running[0] = session
				BrowserTheme {
					CompositionLocalProvider(
						LocalBindings provides Bindings.of(Preferences()),
						LocalMotion provides false,
					) { SessionBrowser(session) }
				}
			}

			val session = running[0]!!

			waitUntil(timeoutMillis = 5_000) { session.state.value.listing is Listing.Read }
			onNodeWithTag("place:Recents").performClick()
			waitUntil(timeoutMillis = 5_000) { session.state.value.listing is Listing.Recent }
			waitForIdle()

			// Each section under its heading, and a line before what is long before its section's newest.
			onNodeWithTag("recents-bar").assertExists()
			onNodeWithTag("recent-section:New files").assertExists()
			onNodeWithTag("recent-section:Recent folders").assertExists()
			onNodeWithTag("recent-older").assertExists()
			assertEquals(
				listOf("new.txt", "used", "old"),
				session.state.value.rows.list.map {
					it.name
				},
			)

			// A heading's button pressed collapses its section: the rows go, the heading stays and says how many
			// it holds, and it is kept where every window reads it. Its title pressed does as the button does: they
			// are back.
			onNodeWithTag("recent-fold:Recent folders").performClick()
			waitUntil(timeoutMillis = 5_000) { session.state.value.rows.size == 1 }
			waitForIdle()
			onNodeWithTag("row:used").assertDoesNotExist()
			onNodeWithTag("recent-section:Recent folders").assertExists()
			onNodeWithTag("recent-count:Recent folders").assertExists()
			onNodeWithTag("recent-older").assertDoesNotExist()
			waitUntil(timeoutMillis = 5_000) {
				services.preferences.preferences.value.recentsCollapsed ==
					setOf(RecentSection.Folders)
			}
			onNodeWithTag("recent-section:Recent folders").performClick()
			waitUntil(timeoutMillis = 5_000) { session.state.value.rows.size == 3 }
			waitForIdle()
			onNodeWithTag("row:used").assertExists()
			onNodeWithTag("recent-count:Recent folders").assertDoesNotExist()
			waitUntil(timeoutMillis = 5_000) {
				services.preferences.preferences.value.recentsCollapsed
					.isEmpty()
			}

			// A row clicked is chosen; the arrows go on from it, across the heading, and Enter goes into a folder.
			onNodeWithTag("row:new.txt").performClick()
			waitUntil(timeoutMillis = 5_000) {
				session.state.value.choosing.rows ==
					setOf<RowId>(RowId.Recent(root.resolve("docs/new.txt")))
			}
			onNodeWithTag("listing").performKeyInput { pressKey(Key.DirectionDown) }
			waitUntil(
				timeoutMillis = 5_000,
			) {
				session.state.value.cursor
					?.at == RowId.Recent(used)
			}
			onNodeWithTag("listing").performKeyInput { pressKey(Key.Enter) }
			waitUntil(timeoutMillis = 5_000) { session.state.value.at == Location.Folder(used) }
		}

	@Test
	fun aPickerAnsweredRecordsTheFolderForWhoeverAsked_AndOneCancelledRecordsNothing() {
		file("docs/a.txt")
		file("docs/b.txt")

		fun ended(asker: AppKey?, recents: RecentStore, vararg events: BrowserEvent): Outcome =
			runBlocking {
				val scope =
					kotlinx.coroutines.CoroutineScope(
						kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
					)

				val first =
					opened(Choosing.OneFile(), root.resolve("docs"), root, sidebarOf(XdgPlaces(), root))

				val session =
					Session(
						Step(first.state.copy(asker = asker), first.effects),
						Services(JvmDisk(), recents = recents),
						scope,
						io = kotlinx.coroutines.Dispatchers.Unconfined,
						clock = { now },
					)

				try {
					kotlinx.coroutines.withTimeout(15_000) {
						session.state.first { it.listing is Listing.Shown }
						events.forEach(session::send)
						session.outcome.await()
					}
				} finally {
					scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
				}
			}

		val recents = MemoryRecents()

		// Cancelled: nothing.
		assertEquals(Outcome.Cancelled, ended(asker, recents, BrowserEvent.Cancelled))
		assertEquals(RecentFolders(), recents.read())

		// Answered for Firefox: its own, and every application's.
		assertEquals(
			Outcome.Chosen(listOf(root.resolve("docs/a.txt"))),
			ended(asker, recents, BrowserEvent.Activated(RowId("a.txt"))),
		)
		assertEquals(
			RecentFolders().used(listOf(root.resolve("docs")), firefox, now),
			recents.read(),
		)

		// Answered for nobody that can be told: every application's alone.
		val nobody = MemoryRecents()

		ended(null, nobody, BrowserEvent.Activated(RowId("b.txt")))
		assertEquals(listOf(root.resolve("docs")), nobody.read().all.folders)
		assertEquals(emptyMap<AppKey, RecentList>(), nobody.read().apps)
	}
}
