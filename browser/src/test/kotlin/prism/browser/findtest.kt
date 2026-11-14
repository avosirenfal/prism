package prism.browser

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
import prism.desktop.freedesktop.XdgPlaces
import prism.index.Index
import prism.index.Indexer
import prism.index.Searched
import prism.index.query.QueryText
import prism.preferences.ColumnId
import prism.preferences.Kind
import prism.preferences.Layout
import prism.preferences.Order
import prism.preferences.Sort

/**
 * The search of the index from the picker, as values: the window's matches as the query is typed, a match gone to
 * as the picker's kind says, and every match shown in the listing as a folder holding them all would show them,
 * filtered and ordered as a folder is, read on disk as they are shown, and left and come back to through the history.
 * The index is a real one, of a folder made for each test, searched as the picker run on its own searches one.
 */
class FindTest {
	@TempDir
	lateinit var root: Path

	private val disk get() = JvmDisk()

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
	private fun picker(choosing: Choosing = Choosing.OneFile()): BrowserState =
		opened(
			choosing,
			root,
			root,
			sidebarOf(XdgPlaces(), root),
			search = Search.Ready(),
		).settled(disk, finder)

	/** [state] after each of [inputs], everything they ask of the disk and the index answered. */
	private fun after(state: BrowserState, vararg inputs: Input): BrowserState =
		inputs.fold(state) { at, input -> step(at, input).settled(disk, finder) }

	private fun searched(state: BrowserState, text: String): BrowserState =
		after(state, BrowserEvent.OpenedSearch, BrowserEvent.SearchTyped(text))

	private val BrowserState.ready: Search.Ready get() = search as Search.Ready

	/** The names the window lists. */
	private fun BrowserState.listed(): List<String> {
		val found = ready.found as Finding.Matched

		return (0 until ready.listed).map { found.matches.nameOf(it) }
	}

	private fun BrowserState.shown(): List<String> = rows.list.map { it.name }

	@Test
	fun aPickerWithNoIndexSaysWhyItHasNoSearch() {
		made("a.txt")

		val without =
			opened(
				Choosing.OneFile(),
				root,
				root,
				sidebarOf(XdgPlaces(), root),
			).settled(disk)

		val asked = after(without, BrowserEvent.OpenedSearch)

		assertTrue(asked.search.open)
		assertEquals(BrowserState.NO_INDEX, (asked.search as Search.Unavailable).why)
		assertFalse(after(asked, BrowserEvent.ClosedSearch).search.open)
	}

	@Test
	fun theWindowListsTheBestMatchesAsTheQueryIsTypedAndOpensEmptyTheNextTime() {
		made("docs/report.pdf", "old/q4-report.pdf", "myreport.txt", "reports/", "notes.md")

		val typed = searched(picker(), "report")

		assertTrue(typed.search.open)
		assertEquals(
			listOf("report.pdf", "reports", "q4-report.pdf", "myreport.txt"),
			typed.listed(),
		)

		// Closed and opened again, it starts from nothing: what was searched for last is seldom what is wanted next.
		val closed = after(typed, BrowserEvent.ClosedSearch)

		assertFalse(closed.search.open)

		val again = after(closed, BrowserEvent.OpenedSearch)

		assertTrue(again.search.open)
		assertEquals("", again.ready.text)
		assertEquals(null, again.ready.found)

		// Opened to change what was searched for (the results' bar, clicked), it holds that, searched for again.
		val edited = after(closed, BrowserEvent.EditedSearch("report"))

		assertTrue(edited.search.open)
		assertEquals("report", edited.ready.text)
		assertEquals(typed.listed(), edited.listed())

		// The query is read as it is typed, for the field to colour it.
		assertEquals(QueryText.read("report").marks, typed.ready.read.marks)

		// No text, no matches.
		assertNull(after(typed, BrowserEvent.SearchTyped("")).ready.found)
	}

	@Test
	fun anAnswerForWhatWasTypedBeforeIsDropped() {
		made("alpha.txt", "beta.txt")

		val typing = step(picker(), BrowserEvent.SearchTyped("alpha"))
		val late =
			(typing.effects.single() as Effect.Search).let {
				it to
					Fixtures.Memory.answer(it, finder)
			}
		val retyped = step(typing.state, BrowserEvent.SearchTyped("beta")).state

		assertNull(step(retyped, late.second).state.ready.found)
	}

	@Test
	fun theArrowsGoThroughTheMatchesFromTheFieldAndBack() {
		made("plan-a.txt", "plan-b.txt", "plan-c.txt")

		val typed = searched(picker(), "plan")

		assertNull(typed.ready.highlighted)
		assertEquals(0, after(typed, BrowserEvent.MatchMoved(1)).ready.highlighted)
		assertEquals(
			2,
			after(typed, BrowserEvent.MatchMoved(1), BrowserEvent.MatchMoved(5)).ready.highlighted,
		)
		// Up from the first is back to the field, where Enter shows every match.
		assertNull(
			after(
				typed,
				BrowserEvent.MatchMoved(1),
				BrowserEvent.MatchMoved(-1),
			).ready.highlighted,
		)
	}

	@Test
	fun aFileChosenToOpenIsGoneToChosenAndOpenedOnlyWhenTheUserSays() {
		made("deep/down/letter.odt", "other.txt")

		val chosen =
			after(
				searched(picker(), "letter"),
				BrowserEvent.MatchMoved(1),
				BrowserEvent.SearchEntered,
			)

		assertFalse(chosen.search.open)
		assertEquals(root.resolve("deep/down"), chosen.folder)
		assertEquals(listOf("letter.odt"), chosen.chosenRows.map { it.name })
		assertEquals(
			listOf(Effect.Finish(Outcome.Chosen(listOf(root.resolve("deep/down/letter.odt"))))),
			step(chosen, BrowserEvent.Accepted).effects,
		)
		// Back is where it was chosen from.
		assertEquals(root, after(chosen, BrowserEvent.Back).folder)
	}

	@Test
	fun aFileChosenToSaveOverIsGoneToNamedAndReplacedOnlyOnceTheUserSaysSo() {
		made("deep/budget.xlsx", "other.txt")

		val chosen =
			after(
				searched(picker(Choosing.SaveAs("untitled.xlsx")), "budget"),
				BrowserEvent.MatchChosen(0),
			)

		assertEquals(root.resolve("deep"), chosen.folder)
		assertEquals("budget.xlsx", chosen.nameField)
		assertEquals(listOf("budget.xlsx"), chosen.chosenRows.map { it.name })

		val saving = after(chosen, BrowserEvent.Accepted)

		assertEquals(Notice.Replace(root.resolve("deep/budget.xlsx")), saving.notice)
	}

	@Test
	fun aFolderChosenIsGoneInto() {
		made("projects/prism/README.md", "prism.txt")

		val typed = searched(picker(), "prism")
		val folder = typed.listed().indexOf("prism")
		val gone = after(typed, BrowserEvent.MatchChosen(folder))

		assertEquals(root.resolve("projects/prism"), gone.folder)
		assertEquals(listOf("README.md"), gone.shown())
	}

	@Test
	fun aFolderPickerFindsOnlyFoldersThoughNothingTypedSaysSo() {
		made("notes/", "notes.txt", "old/notes/")

		val typed = searched(picker(Choosing.Folder()), "notes")

		assertEquals(listOf("notes", "notes"), typed.listed())
		assertEquals("notes", typed.ready.text)

		val gone = after(typed, BrowserEvent.MatchChosen(0))

		assertEquals(root.resolve("notes"), gone.folder)
	}

	@Test
	fun everyMatchIsShownInTheListingAsAFolderHoldingThemWouldShowThem() {
		made("a/plan.txt", "b/plans/", "c/the-plan.md", "d/planet.png", "e/.plan-secret")

		val shown = after(searched(picker(), "plan"), BrowserEvent.SearchEntered)

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
			after(shown, BrowserEvent.ShowedOnlyKind(Kind.Images)).shown(),
		)
		assertEquals(
			listOf("plans", "plan.txt", "planet.png", "the-plan.md"),
			after(shown, BrowserEvent.ToggledHiddenFiles).shown(),
		)
		assertEquals(listOf("the-plan.md"), after(shown, BrowserEvent.Searched("md")).shown())

		// Ordered by a column, and best first again.
		val bySize = after(shown, BrowserEvent.Sorted(ColumnId.Size))

		assertEquals(
			Order.By(Sort(ColumnId.Size, ascending = false)),
			bySize.look.order,
		)
		assertEquals("plans", bySize.shown().first())
		assertEquals(shown.shown(), after(bySize, BrowserEvent.SortedByRelevance).shown())
	}

	@Test
	fun aFolderAmongTheResultsIsGoneIntoAndBackIsTheResultsAgain() {
		made("x/plans/one.txt", "plan.txt")

		val shown = after(searched(picker(), "plan"), BrowserEvent.ShowedResults)
		val plans = shown.rows.list.single { it.name == "plans" }
		val inside = after(shown, BrowserEvent.Activated(plans.id))

		assertEquals(root.resolve("x/plans"), inside.folder)
		assertEquals(listOf("one.txt"), inside.shown())

		val back = after(inside, BrowserEvent.Back)

		assertEquals(shown.at, back.at)
		assertEquals(shown.shown(), back.shown())
		assertEquals(root.resolve("x/plans"), (after(back, BrowserEvent.Forward).folder))
	}

	@Test
	fun aFileAmongTheResultsIsOpenedWhereItIs() {
		made("far/away/plan.txt")

		val shown = after(searched(picker(), "plan"), BrowserEvent.SearchEntered)
		val plan = shown.rows.list.single()

		assertEquals(
			listOf(Effect.Finish(Outcome.Chosen(listOf(root.resolve("far/away/plan.txt"))))),
			step(shown, BrowserEvent.Activated(plan.id)).effects,
		)
	}

	@Test
	fun aNameTypedAmongTheResultsIsSavedBesideTheResultChosenAndNowhereWithoutOne() {
		made("far/plan.txt")

		val shown =
			after(searched(picker(Choosing.SaveAs("new.txt")), "plan"), BrowserEvent.SearchEntered)

		assertEquals(Notice.NoFolder("new.txt"), after(shown, BrowserEvent.Accepted).notice)

		val named =
			after(
				shown,
				BrowserEvent.Clicked(
					shown.rows.list
						.single()
						.id,
					Click.Only,
				),
				BrowserEvent.Named("new.txt"),
			)

		val accepting = step(named, BrowserEvent.Accepted)
		val lookup = (accepting.effects.single() as Effect.Look).lookup

		assertEquals(
			listOf(Effect.Finish(Outcome.Chosen(listOf(root.resolve("far/new.txt"))))),
			step(accepting.state, Answer.Looked(lookup, disk.look(lookup.paths))).effects,
		)
	}

	@Test
	fun aResultTrashedIsGoneOnceTheResultsAreSearchedAgain() {
		made("plan-a.txt", "plan-b.txt")

		val shown = after(searched(picker(), "plan"), BrowserEvent.SearchEntered)

		// The index read once still holds it; read on disk, it is not there, and not shown.
		root.resolve("plan-a.txt").deleteExisting()

		assertEquals(listOf("plan-b.txt"), after(shown, BrowserEvent.Refreshed).shown())
	}

	@Test
	fun manyResultsAreReadAsTheyComeIntoSightAndOrderedByWhatTheIndexKnowsBehind() {
		made(*(0..FEW_RESULTS).map { "many/file-%05d.txt".format(it) }.toTypedArray())

		val shown = after(searched(picker(), "file"), BrowserEvent.SearchEntered)
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
				BrowserEvent.Scrolled(
					shown.rows.size - 20,
					shown.rows.size - 1,
				),
			)

		assertTrue(scrolled.rows[scrolled.rows.size - 1].size != null)

		// Too many to read every one: neither size nor date orders them; name does, worked out behind.
		assertFalse(listing.orders(ColumnId.Size))
		assertEquals(
			shown.arrangement,
			after(shown, BrowserEvent.Sorted(ColumnId.Size)).arrangement,
		)

		val byName =
			step(shown, BrowserEvent.SortedBy(Sort(ColumnId.Name, ascending = false)))

		assertEquals(
			ColumnId.Name,
			(byName.state.listing as Listing.Found).pending(
				byName.state.look.order,
			),
		)
		assertEquals(listOf(Effect.Order(listing.matches, ColumnId.Name)), byName.effects)

		val ordered = byName.settled(disk, finder)

		assertEquals("file-%05d.txt".format(FEW_RESULTS), ordered.rows[0].name)
		assertNull(
			(ordered.listing as Listing.Found).pending(ordered.look.order),
		)
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
			"Projects/prism/docs/reporting.md",
			"Pictures/report cover.png",
			".cache/report.tmp",
		)

		val typed =
			after(
				searched(picker(), "report -ext:tmp"),
				BrowserEvent.MatchMoved(1),
				BrowserEvent.MatchMoved(1),
			)

		val shown = after(typed, BrowserEvent.MatchMoved(-2), BrowserEvent.SearchEntered)
		val wrong = searched(picker(), "report ext: [a")
		val help = after(searched(picker(), "report .pdf"), BrowserEvent.ToggledSearchHelp)

		assertTrue(help.ready.help)
		// Asked for again it is put away, and it is not there the next time the window opens.
		assertFalse(after(help, BrowserEvent.ToggledSearchHelp).ready.help)
		assertFalse(
			after(help, BrowserEvent.ClosedSearch, BrowserEvent.OpenedSearch).ready.help,
		)

		val gallery = after(shown, BrowserEvent.Viewed(Layout.Gallery))

		val drawn =
			mapOf(
				"search" to typed,
				"results" to shown,
				"search-wrong" to wrong,
				"search-help" to help,
				"gallery" to gallery,
			)

		for ((name, state) in drawn)
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png = renderedPng(state, 1100, 680, palette, scale = 2f)

				assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
				System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
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

	@Test
	fun theArrowsGoThroughEveryMatchAndNotOnlyThoseInSight() {
		made(*(1..30).map { "notes/note-%02d.md".format(it) }.toTypedArray())

		val typed = searched(picker(), "note-")

		// Every match is listed, a few of them in sight at once.
		assertEquals(30, typed.ready.listed)
		assertTrue(typed.ready.listed > Search.SHOWN)

		val down = after(typed, *Array(12) { BrowserEvent.MatchMoved(1) })

		assertEquals(11, down.ready.highlighted)

		// A page at a time, stopping at the last; and back up to the field.
		val paged =
			after(down, *Array(4) { BrowserEvent.MatchMoved(Search.SHOWN) })

		assertEquals(29, paged.ready.highlighted)
		assertEquals(
			null,
			after(paged, *Array(5) { BrowserEvent.MatchMoved(-Search.SHOWN) }).ready.highlighted,
		)

		// The one the arrows are on, however far down, is the one Enter goes to.
		val gone = after(down, BrowserEvent.SearchEntered)

		assertFalse(gone.search.open)
		assertEquals(root.resolve("notes"), gone.folder)
	}

	@Test
	fun askedForAgainWhileOpenTheSearchStartsAgain() {
		made("docs/report.pdf", "notes.md")

		val typed = searched(picker(), "report")

		assertTrue(typed.ready.open)
		assertEquals("report", typed.ready.text)

		val again = after(typed, BrowserEvent.OpenedSearch)

		assertTrue(again.ready.open)
		assertEquals("", again.ready.text)
		assertEquals(null, again.ready.found)
		assertEquals(null, again.ready.highlighted)

		// Closed and opened again, the same.
		val closed = after(typed, BrowserEvent.ClosedSearch, BrowserEvent.OpenedSearch)

		assertEquals("", closed.ready.text)
	}
}
