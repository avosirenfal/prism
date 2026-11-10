package barion.picker

import barion.index.Index
import barion.index.Indexer
import barion.index.Searched
import barion.index.query.QueryText
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The search of the index from the picker, as values: the window's matches as the query is typed, a match gone to
 * as the picker's kind says, and every match shown in the listing as a folder holding them all would show them,
 * filtered and ordered as a folder is, read on disk as they are shown, and left and come back to through the history.
 * The index is a real one, of a folder made for each test, searched as the picker run on its own searches one.
 */
class FindTest {
	@TempDir
	lateinit var root: Path

	private val disk get() = JvmDisk(ZoneOffset.UTC)

	private lateinit var finder: Finder

	/** [files] made under [root] (a name ending in `/` a folder), indexed, and a finder over that index. */
	private fun made(vararg files: String) {
		for (file in files) {
			val path = root.resolve(file.removeSuffix("/"))

			if (file.endsWith("/")) {
				path.createDirectories()
			} else {
				path.parent.createDirectories()
				path.writeText(file)
			}
		}
		finder =
			StaticFinder(Index.EMPTY.with(Indexer(mounts = emptyList()).index(root)), home = root)
	}

	/** A picker over [root], choosing [choosing], with the index to search, settled. */
	private fun picker(choosing: Choosing = Choosing.OneFile()): PickerState =
		opened(
			choosing,
			root,
			root,
			sidebarOf(root),
			search = Search.Ready(),
		).settled(disk, finder)

	/** [state] after each of [inputs], everything they ask of the disk and the index answered. */
	private fun after(state: PickerState, vararg inputs: Input): PickerState =
		inputs.fold(state) { at, input -> step(at, input).settled(disk, finder) }

	private fun searched(state: PickerState, text: String): PickerState =
		after(state, PickerEvent.OpenedSearch, PickerEvent.SearchTyped(text))

	private val PickerState.ready: Search.Ready get() = search as Search.Ready

	/** The names the window lists. */
	private fun PickerState.listed(): List<String> {
		val found = ready.found as Finding.Matched

		return (0 until ready.listed).map { found.matches.nameOf(it) }
	}

	private fun PickerState.shown(): List<String> = rows.list.map { it.name }

	@Test
	fun aPickerWithNoIndexSaysWhyItHasNoSearch() {
		made("a.txt")

		val without = opened(Choosing.OneFile(), root, root, sidebarOf(root)).settled(disk)
		val asked = after(without, PickerEvent.OpenedSearch)

		assertTrue(asked.search.open)
		assertEquals(PickerState.NO_INDEX, (asked.search as Search.Unavailable).why)
		assertFalse(after(asked, PickerEvent.ClosedSearch).search.open)
	}

	@Test
	fun theWindowListsTheBestMatchesAsTheQueryIsTypedAndKeepsItWhenClosed() {
		made("docs/report.pdf", "old/q4-report.pdf", "myreport.txt", "reports/", "notes.md")

		val typed = searched(picker(), "report")

		assertTrue(typed.search.open)
		assertEquals(
			listOf("report.pdf", "reports", "q4-report.pdf", "myreport.txt"),
			typed.listed(),
		)

		// Closed, it keeps what was typed and found; opened again, it is all there.
		val closed = after(typed, PickerEvent.ClosedSearch)

		assertFalse(closed.search.open)
		assertEquals("report", closed.ready.text)
		assertEquals(typed.listed(), after(closed, PickerEvent.OpenedSearch).listed())

		// The query is read as it is typed, for the field to colour it.
		assertEquals(QueryText.read("report").marks, typed.ready.read.marks)

		// No text, no matches.
		assertNull(after(typed, PickerEvent.SearchTyped("")).ready.found)
	}

	@Test
	fun anAnswerForWhatWasTypedBeforeIsDropped() {
		made("alpha.txt", "beta.txt")

		val typing = step(picker(), PickerEvent.SearchTyped("alpha"))
		val late =
			(typing.effects.single() as Effect.Search).let {
				it to
					Fixtures.Memory.answer(it, finder)
			}
		val retyped = step(typing.state, PickerEvent.SearchTyped("beta")).state

		assertNull(step(retyped, late.second).state.ready.found)
	}

	@Test
	fun theArrowsGoThroughTheMatchesFromTheFieldAndBack() {
		made("plan-a.txt", "plan-b.txt", "plan-c.txt")

		val typed = searched(picker(), "plan")

		assertNull(typed.ready.highlighted)
		assertEquals(0, after(typed, PickerEvent.MatchMoved(1)).ready.highlighted)
		assertEquals(
			2,
			after(typed, PickerEvent.MatchMoved(1), PickerEvent.MatchMoved(5)).ready.highlighted,
		)
		// Up from the first is back to the field, where Enter shows every match.
		assertNull(
			after(typed, PickerEvent.MatchMoved(1), PickerEvent.MatchMoved(-1)).ready.highlighted,
		)
	}

	@Test
	fun aFileChosenToOpenIsGoneToChosenAndOpenedOnlyWhenTheUserSays() {
		made("deep/down/letter.odt", "other.txt")

		val chosen =
			after(
				searched(picker(), "letter"),
				PickerEvent.MatchMoved(1),
				PickerEvent.SearchEntered,
			)

		assertFalse(chosen.search.open)
		assertEquals(root.resolve("deep/down"), chosen.folder)
		assertEquals(listOf("letter.odt"), chosen.chosenRows.map { it.name })
		assertEquals(
			listOf(Effect.Finish(Outcome.Chosen(listOf(root.resolve("deep/down/letter.odt"))))),
			step(chosen, PickerEvent.Accepted).effects,
		)
		// Back is where it was chosen from.
		assertEquals(root, after(chosen, PickerEvent.Back).folder)
	}

	@Test
	fun aFileChosenToSaveOverIsGoneToNamedAndReplacedOnlyOnceTheUserSaysSo() {
		made("deep/budget.xlsx", "other.txt")

		val chosen =
			after(
				searched(picker(Choosing.SaveAs("untitled.xlsx")), "budget"),
				PickerEvent.MatchChosen(0),
			)

		assertEquals(root.resolve("deep"), chosen.folder)
		assertEquals("budget.xlsx", chosen.nameField)
		assertEquals(listOf("budget.xlsx"), chosen.chosenRows.map { it.name })

		val saving = after(chosen, PickerEvent.Accepted)

		assertEquals(Notice.Replace(root.resolve("deep/budget.xlsx")), saving.notice)
	}

	@Test
	fun aFolderChosenIsGoneInto() {
		made("projects/barion/README.md", "barion.txt")

		val typed = searched(picker(), "barion")
		val folder = typed.listed().indexOf("barion")
		val gone = after(typed, PickerEvent.MatchChosen(folder))

		assertEquals(root.resolve("projects/barion"), gone.folder)
		assertEquals(listOf("README.md"), gone.shown())
	}

	@Test
	fun aFolderPickerFindsOnlyFoldersThoughNothingTypedSaysSo() {
		made("notes/", "notes.txt", "old/notes/")

		val typed = searched(picker(Choosing.Folder()), "notes")

		assertEquals(listOf("notes", "notes"), typed.listed())
		assertEquals("notes", typed.ready.text)

		val gone = after(typed, PickerEvent.MatchChosen(0))

		assertEquals(root.resolve("notes"), gone.folder)
	}

	@Test
	fun everyMatchIsShownInTheListingAsAFolderHoldingThemWouldShowThem() {
		made("a/plan.txt", "b/plans/", "c/the-plan.md", "d/planet.png", "e/.plan-secret")

		val shown = after(searched(picker(), "plan"), PickerEvent.SearchEntered)

		assertFalse(shown.search.open)
		assertInstanceOf(Location.Results::class.java, shown.at)
		assertNull(shown.folder)
		assertEquals("results for “plan”", shown.placeName)
		// Folders first, then best first; the hidden file shown, as a folder's hidden files are.
		assertEquals(
			listOf("plans", "plan.txt", "planet.png", "the-plan.md", ".plan-secret"),
			shown.shown(),
		)

		// Each row is where it is, and read on disk: there are few, so every one at once.
		val plan = shown.rows.list.single { it.name == "plan.txt" }

		assertEquals(root.resolve("a/plan.txt"), plan.path)
		assertEquals("a/plan.txt".length.toLong(), plan.size?.value)
		assertTrue((shown.listing as Listing.Found).stats.complete)

		// Filtered as a folder is.
		assertEquals(
			listOf("planet.png"),
			after(shown, PickerEvent.ShowedOnlyKind(Kind.Images)).shown(),
		)
		assertEquals(
			listOf("plans", "plan.txt", "planet.png", "the-plan.md"),
			after(shown, PickerEvent.ToggledHiddenFiles).shown(),
		)
		assertEquals(listOf("the-plan.md"), after(shown, PickerEvent.Searched("md")).shown())

		// Ordered by a column, and best first again.
		val bySize = after(shown, PickerEvent.Sorted(Sort.Column.Size))

		assertEquals(
			Order.By(Sort(Sort.Column.Size, ascending = false)),
			bySize.arrangement.results,
		)
		assertEquals("plans", bySize.shown().first())
		assertEquals(shown.shown(), after(bySize, PickerEvent.SortedByRelevance).shown())
	}

	@Test
	fun aFolderAmongTheResultsIsGoneIntoAndBackIsTheResultsAgain() {
		made("x/plans/one.txt", "plan.txt")

		val shown = after(searched(picker(), "plan"), PickerEvent.ShowedResults)
		val plans = shown.rows.list.single { it.name == "plans" }
		val inside = after(shown, PickerEvent.Activated(plans.id))

		assertEquals(root.resolve("x/plans"), inside.folder)
		assertEquals(listOf("one.txt"), inside.shown())

		val back = after(inside, PickerEvent.Back)

		assertEquals(shown.at, back.at)
		assertEquals(shown.shown(), back.shown())
		assertEquals(root.resolve("x/plans"), (after(back, PickerEvent.Forward).folder))
	}

	@Test
	fun aFileAmongTheResultsIsOpenedWhereItIs() {
		made("far/away/plan.txt")

		val shown = after(searched(picker(), "plan"), PickerEvent.SearchEntered)
		val plan = shown.rows.list.single()

		assertEquals(
			listOf(Effect.Finish(Outcome.Chosen(listOf(root.resolve("far/away/plan.txt"))))),
			step(shown, PickerEvent.Activated(plan.id)).effects,
		)
	}

	@Test
	fun aNameTypedAmongTheResultsIsSavedBesideTheResultChosenAndNowhereWithoutOne() {
		made("far/plan.txt")

		val shown =
			after(searched(picker(Choosing.SaveAs("new.txt")), "plan"), PickerEvent.SearchEntered)

		assertEquals(Notice.NoFolder("new.txt"), after(shown, PickerEvent.Accepted).notice)

		val named =
			after(
				shown,
				PickerEvent.Clicked(
					shown.rows.list
						.single()
						.id,
					Click.Only,
				),
				PickerEvent.Named("new.txt"),
			)

		val accepting = step(named, PickerEvent.Accepted)
		val lookup = (accepting.effects.single() as Effect.Look).lookup

		assertEquals(
			listOf(Effect.Finish(Outcome.Chosen(listOf(root.resolve("far/new.txt"))))),
			step(accepting.state, Answer.Looked(lookup, disk.look(lookup.paths))).effects,
		)
	}

	@Test
	fun aResultTrashedIsGoneOnceTheResultsAreSearchedAgain() {
		made("plan-a.txt", "plan-b.txt")

		val shown = after(searched(picker(), "plan"), PickerEvent.SearchEntered)

		// The index read once still holds it; read on disk, it is not there, and not shown.
		root.resolve("plan-a.txt").deleteExisting()

		assertEquals(listOf("plan-b.txt"), after(shown, PickerEvent.Refreshed).shown())
	}

	@Test
	fun manyResultsAreReadAsTheyComeIntoSightAndOrderedByWhatTheIndexKnowsBehind() {
		made(*(0..FEW_RESULTS).map { "many/file-%05d.txt".format(it) }.toTypedArray())

		val shown = after(searched(picker(), "file"), PickerEvent.SearchEntered)
		val listing = shown.listing as Listing.Found

		assertEquals(FEW_RESULTS + 1, listing.matches.size)
		// Only those first in sight are read.
		assertFalse(listing.stats.complete)
		assertTrue(listing.stats.known in 1..1000, "${listing.stats}")
		assertTrue(shown.rows[0].size != null)
		assertNull(shown.rows[shown.rows.size - 1].size)

		// Scrolled to the end, the end is read.
		val scrolled =
			after(
				shown,
				PickerEvent.Scrolled(
					shown.rows.size - 20,
					shown.rows.size - 1,
				),
			)

		assertTrue(scrolled.rows[scrolled.rows.size - 1].size != null)

		// Too many to read every one: neither size nor date orders them; name does, worked out behind.
		assertFalse(listing.orders(Sort.Column.Size))
		assertEquals(
			shown.arrangement,
			after(shown, PickerEvent.Sorted(Sort.Column.Size)).arrangement,
		)

		val byName =
			step(shown, PickerEvent.SortedBy(Sort(Sort.Column.Name, ascending = false)))

		assertEquals(
			Sort.Column.Name,
			(byName.state.listing as Listing.Found).pending(byName.state.arrangement.results),
		)
		assertEquals(listOf(Effect.Order(listing.matches, Sort.Column.Name)), byName.effects)

		val ordered = byName.settled(disk, finder)

		assertEquals("file-%05d.txt".format(FEW_RESULTS), ordered.rows[0].name)
		assertNull((ordered.listing as Listing.Found).pending(ordered.arrangement.results))
	}

	@Test
	fun whatIsPickedRanksHigherThroughTheFinderThatPickedIt() {
		made("notes-a.txt", "notes-b.txt")

		fun names() =
			(finder.find(QueryText.read("notes").query) as Searched.Found).results.map {
				it.where.toString().substringAfterLast('/')
			}

		assertEquals(listOf("notes-a.txt", "notes-b.txt"), names())
		finder.picked(listOf(root.resolve("notes-b.txt")), Instant.now())
		assertEquals(listOf("notes-b.txt", "notes-a.txt"), names())
	}

	@Test
	fun theSearchAndItsResultsDraw() {
		made(
			"Documents/Quarterly report.pdf",
			"Documents/report-draft.odt",
			"Documents/Reports/",
			"Projects/barion/docs/reporting.md",
			"Pictures/report cover.png",
			".cache/report.tmp",
		)

		val typed =
			after(
				searched(picker(), "report -ext:tmp"),
				PickerEvent.MatchMoved(1),
				PickerEvent.MatchMoved(1),
			)

		val shown = after(typed, PickerEvent.MatchMoved(-2), PickerEvent.SearchEntered)
		val wrong = searched(picker(), "report ext: [a")

		val gallery = after(shown, PickerEvent.Viewed(Layout.Gallery))

		val drawn =
			mapOf(
				"search" to typed,
				"results" to shown,
				"search-wrong" to wrong,
				"gallery" to gallery,
			)

		for ((name, state) in drawn)
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png = renderedPng(state, 1100, 680, palette, scale = 2f)

				assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
				System.getProperty("picker.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
					Path
						.of(dir)
						.also {
							it.createDirectories()
						}.resolve("find-$name-$tone.png")
						.toFile()
						.writeBytes(png)
				}
			}

		assertTrue(shown.search.open.not() && shown.results != null)
	}
}
