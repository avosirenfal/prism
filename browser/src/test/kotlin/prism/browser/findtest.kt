package prism.browser

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces
import prism.index.Index
import prism.index.Indexer
import prism.index.Searched
import prism.index.query.Own
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

		return found.matches.ranks.map(found.matches::nameOf)
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
	fun aFileGoneToWhileSavingIsChosenAndTheNameIsLeftAsItWas() {
		made("deep/budget.xlsx", "other.txt")

		val chosen =
			after(
				searched(picker(Choosing.SaveAs("untitled.xlsx")), "budget"),
				BrowserEvent.MatchChosen(0),
			)

		// Gone to and chosen, as a click would choose it: the name to save under is what it was.
		assertEquals(root.resolve("deep"), chosen.folder)
		assertEquals(listOf("budget.xlsx"), chosen.chosenRows.map { it.name })
		assertEquals("untitled.xlsx", chosen.nameField)

		// Save saves under the name, beside it; opened, it is saved over once the user says so.
		assertNull(after(chosen, BrowserEvent.Accepted).notice)
		assertEquals(
			Notice.Replace(root.resolve("deep/budget.xlsx")),
			after(chosen, BrowserEvent.Activated(chosen.chosenRows.single().id)).notice,
		)
	}

	@Test
	fun aFolderChosenIsGoneInto() {
		made("projects/prism/README.md", "prism.txt")

		val typed = searched(picker(), "prism")
		// The matches are listed after what of prism's own the word asks for.
		val folder = typed.ready.own.size + typed.listed().indexOf("prism")
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
	fun whatIsReadOfManyResultsLeavesTheirRowsWhereTheyAreAndOneFoundGoneIsTakenAway() {
		made(*(0..FEW_RESULTS).map { "many/file-%05d.txt".format(it) }.toTypedArray())

		val shown = after(searched(picker(), "file"), BrowserEvent.SearchEntered)
		val listing = shown.listing as Listing.Found
		val rows = listing.rows(shown.arrangement)
		val some = listing.matches.ranks.between(5_000, 5_003)
		val ids = some.map { RowId.Found(listing.matches, it) }
		val places = ids.map(rows::indexOf)
		val there = Stat.Read(null, null)

		// Read, and all there: the same rows in the same places, with what was read of them.
		val read = listing.knowing(listing.stats.read(some, listOf(there, there, there)), some)
		val after = read.rows(shown.arrangement)

		assertEquals(rows.size, after.size)
		assertEquals(places, ids.map(after::indexOf))
		assertEquals((0 until 20).map(rows::idAt), (0 until 20).map(after::idAt))
		assertEquals(there, read.stats[some[1]])

		// One found gone: it has no row, and the rest close up.
		val gone = read.knowing(read.stats.read(some, listOf(Stat.Gone, there, there)), some)
		val left = gone.rows(shown.arrangement)

		assertEquals(rows.size - 1, left.size)
		assertNull(left.indexOf(ids[0]))
		assertEquals(places[1]!! - 1, left.indexOf(ids[1]))
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
	fun resultsAreShownAsTheyWereUntilTheyCanBeInTheOrderAskedForAndThenInIt() {
		made(*(0 until 600).map { "few/file-%03d.txt".format(it) }.toTypedArray())
		// Each changed at a time of its own, in no order of names.
		for (at in 0 until 600)
			java.nio.file.Files.setLastModifiedTime(
				root.resolve("few/file-%03d.txt".format(at)),
				java.nio.file.attribute.FileTime.from(
					Instant.parse("2026-01-01T00:00:00Z").plusSeconds((at * 37L) % 600),
				),
			)

		fun newestFirst(state: BrowserState) =
			state.rows.list
				.zipWithNext()
				.all { (a, b) -> a.modified!! >= b.modified!! }

		// Found, and shown best first before any is read on disk: three batches of them are asked for.
		val found = step(searched(picker(), "file"), BrowserEvent.SearchEntered)
		val stats = found.effects.filterIsInstance<Effect.Stat>()
		val best = found.state.shown()

		assertEquals(600, best.size)
		assertEquals(3, stats.size)
		assertNull(found.state.wait)

		// Asked for by date, which none is read for yet: they stay as they were, and it is waited for.
		val asked = step(found.state, BrowserEvent.Sorted(ColumnId.Modified)).state

		assertEquals(Wait.Ordering(ColumnId.Modified), asked.wait)
		assertEquals(best, asked.shown())

		// As each batch is read, still as they were, whatever is now known of some: never in an order of half of them.
		val some =
			stats
				.dropLast(
					1,
				).fold(asked) { at, stat -> step(at, disk.answer(stat, finder)).state }

		assertEquals(Wait.Ordering(ColumnId.Modified), some.wait)
		assertEquals(best, some.shown())
		assertTrue(some.rows[0].modified != null)

		// The last read, they are in the order asked for, once.
		val all = step(some, disk.answer(stats.last(), finder)).state

		assertNull(all.wait)
		assertEquals(600, all.rows.size)
		assertTrue(newestFirst(all))

		// Searched for again in that order (refreshed), there is nothing they were shown as: nothing is shown until
		// every one is read, and then all of them, in order.
		val again = step(all, BrowserEvent.Refreshed)
		val search = again.effects.filterIsInstance<Effect.Search>().single()
		val fresh = step(again.state, disk.answer(search, finder))
		val batches = fresh.effects.filterIsInstance<Effect.Stat>()

		assertEquals(Wait.Ordering(ColumnId.Modified), fresh.state.wait)
		assertEquals(0, fresh.state.rows.size)
		assertEquals(3, batches.size)

		val partly =
			batches
				.dropLast(
					1,
				).fold(fresh.state) { at, stat -> step(at, disk.answer(stat, finder)).state }

		assertEquals(0, partly.rows.size)

		val whole = step(partly, disk.answer(batches.last(), finder)).state

		assertNull(whole.wait)
		assertEquals(600, whole.rows.size)
		assertTrue(newestFirst(whole))

		// What an order by name needs is all there as they are found: nothing is waited for, read or not.
		assertNull(step(found.state, BrowserEvent.Sorted(ColumnId.Name)).state.wait)
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
	fun prismsOwnAreFoundByTheirWordsFirstAndTheArrowsStartOnTheFirstOfThem() {
		made("Projects/prism/notes.md", "settings.json", "report.pdf")

		val explorer = picker(Choosing.Exploring())
		val settings = searched(explorer, "settings")

		// Prism's own, and then what the index found for the same words; the arrows on the first.
		assertEquals(listOf(Own.Settings), settings.ready.own)
		assertEquals(0, settings.ready.highlighted)
		assertEquals(listOf("settings.json"), settings.listed())
		assertEquals(2, settings.ready.listed)
		assertEquals(Search.Listed.Prisms(Own.Settings), settings.ready.listedAt(0))
		assertTrue(settings.ready.listedAt(1) is Search.Listed.Match)
		assertNull(settings.ready.listedAt(2))
		// Found as they are typed, once a few letters are.
		assertEquals(emptyList<Own>(), searched(explorer, "se").ready.own)
		assertEquals(listOf(Own.Settings), searched(explorer, "set").ready.own)

		// The word prism scopes a search to them: alone, every one.
		val all = searched(explorer, "prism")

		assertEquals(Own.ALL, all.ready.own)
		assertEquals(Own.ALL.size + all.listed().size, all.ready.listed)

		// A picker is another application's window: it has no preferences to open.
		assertEquals(emptyList<Own>(), searched(picker(), "settings").ready.own)
		assertEquals(listOf(Own.System, Own.Help), searched(picker(), "prism").ready.own)

		// What is no word of theirs finds nothing of prism's, and the arrows are in the field.
		val plain = searched(explorer, "report")

		assertEquals(emptyList<Own>(), plain.ready.own)
		assertNull(plain.ready.highlighted)
		// Up from the first is the field again, from which Enter shows every match and opens nothing of prism's.
		assertNull(after(settings, BrowserEvent.MatchMoved(-1)).ready.highlighted)
	}

	@Test
	fun eachOfPrismsOwnChosenDoesWhatItIsAndPutsTheWindowAway() {
		made("report.pdf")

		val explorer = picker(Choosing.Exploring())

		fun entered(text: String): Step =
			step(searched(explorer, text), BrowserEvent.SearchEntered)

		val settings = entered("settings")

		assertEquals(listOf<Effect>(Effect.Ask(Request.Preferences)), settings.effects)
		assertFalse(settings.state.search.open)

		val bindings = entered("key bind")

		assertEquals(listOf<Effect>(Effect.Ask(Request.KeyBindings)), bindings.effects)
		assertFalse(bindings.state.search.open)

		// The system's page, whether or not it is pinned: gone to in this window.
		val system = entered("system").settled(disk, finder)

		assertTrue(system.system != null)
		assertFalse(system.search.open)

		// Clicked rather than entered, the same.
		val clicked = step(searched(explorer, "prism"), BrowserEvent.MatchChosen(1))

		assertEquals(listOf<Effect>(Effect.Ask(Request.KeyBindings)), clicked.effects)
	}

	@Test
	fun aBareQuestionMarkOffersHowAQueryIsWrittenWhichTakesTheWindowsPlaceUntilLeft() {
		made("a.txt", "b.txt")

		val asked = searched(picker(), "?")

		// The first thing listed, with the arrows on it; what one character names is still found, after.
		assertEquals(listOf(Own.Help), asked.ready.own)
		assertEquals(0, asked.ready.highlighted)
		assertFalse(asked.ready.help)

		val shown = after(asked, BrowserEvent.SearchEntered)

		assertTrue(shown.ready.help)
		assertTrue(shown.search.open)

		// Left, the search is as it was: what was typed, and what it found.
		val left = after(shown, BrowserEvent.ToggledSearchHelp)

		assertFalse(left.ready.help)
		assertEquals("?", left.ready.text)
		assertTrue(left.search.open)

		// One of its queries tried: typed into the field, and the search shown again.
		val tried = after(shown, BrowserEvent.SearchTyped(".txt"))

		assertFalse(tried.ready.help)
		assertEquals(listOf("a.txt", "b.txt"), tried.listed().sorted())
		// Arrowed past, the next thing is opened instead.
		assertEquals(1, after(asked, BrowserEvent.MatchMoved(1)).ready.highlighted)
	}

	/** Where the match named [name] is in what the search's window lists. */
	private fun BrowserState.listedAt(name: String): Int =
		ready.own.size + listed().indexOf(name)

	/** What the menu of the thing listed [at] says can be done to it. */
	private fun BrowserState.actions(at: Int): List<String> =
		after(this, BrowserEvent.MatchMenuOpened(at))
			.ready.menu!!
			.actions.actions
			.map { it.label }

	/** The action labelled [label] of the thing listed [at] done, from its menu. */
	private fun BrowserState.acting(at: Int, label: String): Step =
		after(this, BrowserEvent.MatchMenuOpened(at)).let {
			step(
				it,
				BrowserEvent.MatchActionChosen(
					it.ready.menu!!.actions.actions.indexOfFirst { action ->
						action.label ==
							label
					},
				),
			)
		}

	@Test
	fun whatCanBeDoneToAMatchIsItsMenuWithWhatEnterDoesFirst() {
		made("Documents/notes.md", "Documents/Notes/", "settings.json")

		val explorer = searched(picker(Choosing.Exploring()), "notes")
		val picking = searched(picker(), "notes")

		// An explorer's Enter shows a file where it is, and goes into a folder; everything else follows.
		assertEquals(
			listOf(
				"Show in folder",
				"Open",
				"Open with…",
				"Copy path",
				"Copy name",
				"Open terminal here",
				"Properties",
				"Move to trash",
			),
			explorer.actions(explorer.listedAt("notes.md")),
		)
		assertEquals(
			listOf(
				"Open",
				"Show in folder",
				"Copy path",
				"Copy name",
				"Open terminal here",
				"Properties",
				"Move to trash",
			),
			explorer.actions(explorer.listedAt("Notes")),
		)
		// A picker is another application's window: it goes to things, and says what they are called.
		assertEquals(
			listOf("Go to", "Copy path", "Copy name"),
			picking.actions(picking.listedAt("notes.md")),
		)
		assertEquals(
			listOf("Go to", "Show in folder", "Copy path", "Copy name"),
			picking.actions(picking.listedAt("Notes")),
		)
		// One of prism's own is only done, so the right arrow never does nothing.
		assertEquals(
			listOf("Open"),
			searched(picker(Choosing.Exploring()), "settings").actions(0),
		)
		// A terminal that cannot be opened in a folder is not offered one to open in.
		assertFalse(
			MatchAction.Terminal in
				actionsOf(
					explorer.ready.listedAt(explorer.listedAt("Notes"))!!,
					explorer.role,
					terminal = false,
				),
		)
	}

	@Test
	fun theMenuOfAMatchOpensOnItAndTheArrowsAndEnterAreItsOwnWhileItIsOpen() {
		made("Documents/notes.md", "Documents/Notes/")

		val typed = searched(picker(Choosing.Exploring()), "notes")

		// From the field, with the arrows on nothing, there is nothing to have a menu.
		assertNull(after(typed, BrowserEvent.MatchMenuOpened()).ready.menu)

		val on = after(typed, BrowserEvent.MatchMoved(1))
		val opened = after(on, BrowserEvent.MatchMenuOpened())
		val menu = opened.ready.menu!!

		assertEquals(on.ready.listedAt(0), menu.of)
		assertEquals(0, menu.actions.on)
		assertTrue(opened.search.open)

		// The arrows move through its actions, stopping at its ends, and the match stays the one it is.
		val down = after(opened, BrowserEvent.MatchMoved(1), BrowserEvent.MatchMoved(1))

		assertEquals(
			2,
			down.ready.menu
				?.actions
				?.on,
		)
		assertEquals(0, down.ready.highlighted)
		assertEquals(
			menu.actions.actions.lastIndex,
			after(down, BrowserEvent.MatchMoved(40))
				.ready.menu
				?.actions
				?.on,
		)
		assertEquals(
			0,
			after(down, BrowserEvent.MatchMoved(-40))
				.ready.menu
				?.actions
				?.on,
		)

		// Left, the search is as it was; typing leaves it too.
		val left = after(down, BrowserEvent.MatchMenuClosed)

		assertNull(left.ready.menu)
		assertTrue(left.search.open)
		assertEquals(0, left.ready.highlighted)
		assertNull(after(down, BrowserEvent.SearchTyped("note")).ready.menu)

		// A right click on another match: its menu, and the arrows on it.
		val other = after(down, BrowserEvent.MatchMenuOpened(1))

		assertEquals(1, other.ready.highlighted)
		assertEquals(other.ready.listedAt(1), other.ready.menu?.of)

		// Enter does the action the arrows are on, and the window is put away.
		val entered =
			step(after(opened, BrowserEvent.MatchMoved(3)), BrowserEvent.SearchEntered)

		assertFalse(entered.state.search.open)
		assertNull((entered.state.search as Search.Ready).menu)
		assertEquals(1, entered.effects.count { it is Effect.Copy })
	}

	@Test
	fun eachActionOfAMatchIsDoneAndTheWindowPutAway() {
		made("Documents/notes.md", "Documents/Notes/inside.txt", "settings.json")

		val explorer = searched(picker(Choosing.Exploring()), "notes")
		val file = explorer.listedAt("notes.md")
		val folder = explorer.listedAt("Notes")
		val path = root.resolve("Documents/notes.md")

		fun effects(at: Int, label: String): List<Effect> =
			explorer.acting(at, label).also { assertFalse(it.state.search.open, label) }.effects

		// What Enter does: a file shown in its folder, selected; a folder gone into.
		val shown = explorer.acting(file, "Show in folder").settled(disk, finder)

		assertEquals(root.resolve("Documents"), shown.folder)
		assertEquals(listOf("notes.md"), shown.chosenRows.map { it.name })
		assertEquals(
			root.resolve("Documents/Notes"),
			explorer.acting(folder, "Open").settled(disk, finder).folder,
		)

		// A folder shown in the folder it is in.
		val revealed = explorer.acting(folder, "Show in folder").settled(disk, finder)

		assertEquals(root.resolve("Documents"), revealed.folder)
		assertEquals(listOf("Notes"), revealed.chosenRows.map { it.name })

		assertEquals(listOf<Effect>(Effect.Open(listOf(path))), effects(file, "Open"))
		assertEquals(
			listOf<Effect>(Effect.Ask(Request.OpenWith(listOf(path)))),
			effects(file, "Open with…"),
		)
		assertEquals(listOf<Effect>(Effect.Copy("$path")), effects(file, "Copy path"))
		assertEquals(listOf<Effect>(Effect.Copy("notes.md")), effects(file, "Copy name"))
		// A terminal in the folder a file is in, and in a folder itself.
		assertEquals(
			listOf<Effect>(Effect.Terminal(root.resolve("Documents"))),
			effects(file, "Open terminal here"),
		)
		assertEquals(
			listOf<Effect>(Effect.Terminal(root.resolve("Documents/Notes"))),
			effects(folder, "Open terminal here"),
		)
		assertEquals(
			listOf<Effect>(Effect.Ask(Request.Properties(listOf(path)))),
			effects(file, "Properties"),
		)
		// To the trash as the listing's own Delete moves it there, to be undone as that is.
		assertEquals(
			listOf<Effect>(Effect.Make(Change.Trash(listOf(path)))),
			effects(file, "Move to trash"),
		)

		// One of prism's own, done.
		assertEquals(
			listOf<Effect>(Effect.Ask(Request.Preferences)),
			searched(picker(Choosing.Exploring()), "settings").acting(0, "Open").effects,
		)

		// A picker goes to a file as its Enter does: its folder, with the file selected.
		val picking = searched(picker(), "notes")
		val gone = picking.acting(picking.listedAt("notes.md"), "Go to").settled(disk, finder)

		assertEquals(root.resolve("Documents"), gone.folder)
		assertEquals(listOf("notes.md"), gone.chosenRows.map { it.name })
	}

	@Test
	fun showingActionsIsAChordOfTheSearchWindowsAloneAndTheUsersToBindAgain() {
		val bindings = Bindings.DEFAULT
		val right = Chord.parse("Right")!!

		assertEquals(Action.ShowActions, bindings.search(right))
		// The same key still moves the listing's cursor: the two are never heard in one place.
		assertEquals(Action.MoveRight, bindings.movement(right))
		assertEquals(emptyList<Bindable>(), bindings.clashes(Action.ShowActions, right))
		assertEquals("in the search window", Action.ShowActions.where)
		// A picker has it too.
		assertTrue(Action.ShowActions in Action.PICKING)

		val bound = bindings.bound(Action.ShowActions, 0, Chord.parse("Ctrl+O")!!)

		assertEquals(Action.ShowActions, bound.search(Chord.parse("Ctrl+O")!!))
		assertNull(bound.search(right))
		assertEquals(mapOf("ShowActions" to listOf("Ctrl+O")), bound.differences())
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
				"search-empty" to after(picker(), BrowserEvent.OpenedSearch),
				"search-nothing" to searched(picker(), "nothing-is-named-this"),
				"search-own" to searched(picker(Choosing.Exploring()), "prism"),
				"search-own-one" to searched(picker(Choosing.Exploring()), "settings"),
				"search-how" to searched(picker(Choosing.Exploring()), "?"),
				"search-actions" to
					after(
						searched(picker(Choosing.Exploring()), "report -ext:tmp"),
						BrowserEvent.MatchMoved(2),
						BrowserEvent.MatchMenuOpened(),
						BrowserEvent.MatchMoved(1),
					),
				"search-actions-last" to
					after(
						searched(picker(), "report -ext:tmp"),
						BrowserEvent.MatchMoved(5),
						BrowserEvent.MatchMenuOpened(),
					),
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
	fun pinnedResultsAndASearchWithinThemDraw() {
		made(
			"Documents/Quarterly report.pdf",
			"Documents/report-draft.odt",
			"Documents/Reports/",
			"Projects/prism/docs/reporting.md",
			"Pictures/report cover.png",
			"Documents/Annual report.pdf",
		)

		val exploring = picker(Choosing.Exploring())
		val results = after(searched(exploring, "report"), BrowserEvent.SearchEntered)
		val pinned = after(results, BrowserEvent.PinnedSearch(true))
		val within =
			after(pinned, BrowserEvent.OpenedSearch, BrowserEvent.SearchTyped(".pdf"))
		val refined = after(within, BrowserEvent.SearchEntered)

		assertEquals(
			listOf("Annual report.pdf", "Quarterly report.pdf"),
			refined.shown().sorted(),
		)

		/** [state] drawn, with the menu of its results' bar open under "Search results" where [menu] says so. */
		fun drawn(state: BrowserState, palette: Palette, menu: Boolean): ByteArray =
			renderedPng(1100, 680, palette, scale = 2f) {
				FileBrowser(state)
				if (menu)
					MenuPopup(
						resultsMenuOf(state.at as Location.Results),
						MenuAnchor.At(
							androidx.compose.ui.unit
								.IntOffset(480, 84),
						),
						{},
						{},
					)
			}

		val pictures =
			listOf(
				Triple("pin-menu", results, true),
				Triple("pinned", pinned, false),
				Triple("pinned-menu", pinned, true),
				Triple("pinned-search-empty", after(pinned, BrowserEvent.OpenedSearch), false),
				Triple("pinned-search", within, false),
				Triple("pinned-refined", refined, false),
			)

		for ((name, state, menu) in pictures)
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png = drawn(state, palette, menu)

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
	fun aSearchThatGoesWrongSaysItIsPrismsMistakeAndIsReportedInFull() =
		kotlinx.coroutines.runBlocking {
			made("notes.md")

			val reported = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Throwable>>()
			val broken =
				object : Finder {
					override fun find(query: prism.index.query.Query): prism.index.Searched =
						throw ArrayIndexOutOfBoundsException("Index -1 out of bounds for length 0")

					override fun picked(paths: List<Path>, at: java.time.Instant) = Unit
				}

			val scope =
				kotlinx.coroutines.CoroutineScope(
					kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
				)

			val session =
				Session(
					opened(
						Choosing.OneFile(),
						root,
						root,
						sidebarOf(XdgPlaces(), root),
						search = Search.Ready(),
					),
					Services(disk, broken, report = { doing, failure -> reported += doing to failure }),
					scope,
				)

			try {
				kotlinx.coroutines.withTimeout(15_000) {
					session.send(BrowserEvent.OpenedSearch)
					session.send(BrowserEvent.SearchTyped("notes"))

					val failed =
						session.state
							.first { (it.search as? Search.Ready)?.found is Finding.Failed }
							.ready.found as Finding.Failed

					// The window is told what kind of thing went wrong, in a line; all of it is where prism says
					// what goes wrong, with what was being done.
					assertEquals("notes", failed.text)
					assertEquals(
						"ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 0",
						failed.fault.line,
					)
					assertEquals(listOf("Couldn't search for “notes”"), reported.map { it.first })
					assertTrue(reported.single().second is ArrayIndexOutOfBoundsException)
					// Nothing is listed, and the window stays, to be typed into again.
					assertEquals(0, session.state.value.ready.listed)
					assertTrue(session.state.value.search.open)
				}
			} finally {
				scope.cancel()
			}
		}

	@Test
	fun aResultChosenIsNoRowOfTheSameSearchMadeAgainAndIsLetGoWithoutBeingToldTo() {
		made("a/plan.txt", "b/plan.md", "c/plans/")

		val shown =
			after(searched(picker(Choosing.Exploring()), "plan"), BrowserEvent.ShowedResults)
		val row = shown.rows.list.single { it.name == "plan.md" }
		val chosen = after(shown, BrowserEvent.Clicked(row.id, Click.Only))

		assertEquals(listOf("plan.md"), chosen.chosenRows.map { it.name })

		// The same search, made again: other matches, however alike, and so other rows.
		val again =
			finder.findingOf(Effect.Search("plan", QueryText.read("plan").query, Purpose.Refresh))

		val refreshed =
			step(
				chosen,
				Answer.Searched(again, Purpose.Refresh),
			).settled(disk, finder)

		val old = row.id as RowId.Found
		val now =
			refreshed.rows.list
				.single { it.name == "plan.md" }
				.id as RowId.Found

		assertEquals(chosen.shown(), refreshed.shown())
		// The same rank of other matches is another id: the old one is not a row shown, and names nothing.
		assertEquals(old.rank, now.rank)
		assertTrue(old.of !== now.of)
		assertFalse(old in refreshed.rows)
		assertNull(refreshed.rows[old])
		assertTrue(now in refreshed.rows)
		// So what was chosen, and where the cursor was, went with the old results, nothing having let them go.
		assertEquals(emptyList<Row>(), refreshed.chosenRows)
		assertNull(refreshed.cursor)
	}

	@Test
	fun anOrderOfResultsHoldsEveryOneOfThemOnceAndRanksAreKeptAsNumbers() {
		made("b.txt", "a.txt", "c.png", "d.md", "e/f.kt")

		val matches =
			(
				finder.findingOf(
					Effect.Search("", QueryText.read("ext:*").query, Purpose.Show),
				) as Finding.Matched
			).matches

		val none = Stats.none(matches.size)

		for (column in ColumnId.entries) {
			val order = matches.orderedBy(column, none)

			assertEquals(matches.size, order.size, "$column")
			assertEquals(
				matches.ranks.map { it },
				(0 until order.size).map { order[it] }.sortedBy { it.at },
				"$column",
			)
		}
		assertEquals(
			matches.ranks.map(matches::nameOf).sorted(),
			matches.orderedBy(ColumnId.Name, none).let { order ->
				(0 until order.size).map { matches.nameOf(order[it]) }
			},
		)

		// Ranks: a stretch of them, some and then the rest each once, a few at a time; and none past the last.
		val all = matches.ranks

		assertEquals(matches.size, all.size)
		assertEquals(all, all.between(-5, all.size + 5))
		assertEquals(Ranks.NONE, all.between(2, 2))
		assertEquals(
			listOf(2, 3, 0, 1),
			all
				.between(2, 4)
				.then(all)
				.map { it.at }
				.take(4),
		)
		assertEquals(all, all.chunked(3).fold(Ranks.NONE, Ranks::then))
		assertNull(matches.rank(matches.size))
		assertNull(matches.rank(-1))

		// What was read of fewer than were asked for leaves the rest not known, which is so.
		val read = none.read(all.between(0, 2), listOf(Stat.Gone))

		assertEquals(listOf(Stat.Gone, Stat.Unknown, null), all.between(0, 3).map { read[it] })
	}

	private val fault = Fault.of(IllegalStateException("no such column"))

	@Test
	fun whatPrismGotWrongReadingResultsIsNotKnownAndNotAskedAgainAndIsSaidAsItsMistake() {
		made("a/plan.txt", "b/plans/", "c/the-plan.md")

		// The results shown, and what they ask of the disk not yet answered.
		val asked = step(searched(picker(), "plan"), BrowserEvent.SearchEntered)
		val stat = asked.effects.filterIsInstance<Effect.Stat>().single()
		val broke = step(asked.state, Answer.Broke(stat, fault))
		val listing = broke.state.listing as Listing.Found

		// Nothing is made up of them: each is not known, which is not a size and a date read as none.
		assertEquals(stat.ranks.map { Stat.Unknown }, stat.ranks.map { listing.stats[it] })
		assertEquals(Ranks.NONE, listing.stats.unasked(stat.ranks))
		assertTrue(listing.stats.complete)
		assertEquals(emptyList<Effect>(), broke.effects)
		assertEquals(
			listOf(null, null, null),
			broke.state.rows.list
				.map { it.size },
		)
		assertEquals(
			Notice.Broke("Couldn't read the sizes and dates of the results", fault),
			broke.state.notice,
		)
		assertTrue(Fault.MISTAKE in (broke.state.notice as Notice.Trouble).detail)
		assertTrue(
			"IllegalStateException: no such column" in
				(broke.state.notice as Notice.Trouble).detail,
		)

		// An answer for results no longer shown is too late to say anything of.
		val left = after(asked.state, BrowserEvent.Back)

		assertEquals(left, step(left, Answer.Broke(stat, fault)).state)
	}

	@Test
	fun aFolderPrismGotWrongReadingIsShownAsNotReadAndWhatChangedInItNotReadIsSaid() {
		made("a.txt", "b.txt")

		val state = picker()
		val unread = step(state, Answer.Broke(Effect.Read(root), fault)).state

		// Said where the folder would be, as a folder that cannot be read is: there is no notice over it as well.
		assertEquals(Listing.Unreadable(fault.said), unread.listing)
		assertNull(unread.notice)

		// Read, every row says when it was made, or that its file system does not: nothing more is asked for.
		val reading = step(state, Answer.Arrived(root, disk.read(root)))

		assertEquals(emptyList<Effect>(), reading.effects)
		assertTrue(
			reading.state.rows.list.all {
				it.created is Created.At ||
					it.created == Created.Unkept
			},
		)

		// What changed in it, asked for and not answered: the folder is shown as it was, and it is said.
		val touched = step(reading.state, Answer.Touched(root, Touch.Entries(setOf("a.txt"))))
		val asked = touched.effects.filterIsInstance<Effect.ReadEntries>().single()
		val broke = step(touched.state, Answer.Broke(asked, fault))

		assertSame(reading.state.rows, broke.state.rows)
		assertEquals(emptyList<Effect>(), broke.effects)
		assertInstanceOf(Notice.Broke::class.java, broke.state.notice)
	}

	@Test
	fun aChangePrismGotWrongMakingIsNotKeptToUndoAndWhatIsShownIsReadAgain() {
		made("a.txt")

		val change = Change.MakeFolder(root.resolve("new"))
		val broke = step(picker(), Answer.Broke(Effect.Make(change), fault))

		assertEquals(Notice.Broke("Couldn't create the folder", fault), broke.state.notice)
		assertEquals(Changes(), broke.state.changes)
		assertEquals(listOf<Effect>(Effect.Read(root)), broke.effects)
	}

	@Test
	fun aDiskThatThrowsIsToldAsWhatItRefusedWhereThatCanBeSaidAndAsPrismsMistakeWhereItCannot() =
		kotlinx.coroutines.runBlocking {
			made("a/plan.txt", "b/plan.md")

			val reported = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Throwable>>()
			val real = disk

			val throwing =
				object : Disk by real {
					// The system's refusal, thrown past what reads: the folder's own way of saying it cannot be read.
					override fun read(folder: Path): Listing.Read =
						if (folder.fileName.toString() == "a")
							throw java.io.UncheckedIOException(java.io.IOException("a: Input/output error"))
						else
							real.read(folder)

					// A mistake of prism's: nothing a result's size and date can say.
					override fun stat(paths: List<Path>): List<Stat> = error("no such column")
				}

			val scope =
				kotlinx.coroutines.CoroutineScope(
					kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
				)

			val session =
				Session(
					opened(
						Choosing.OneFile(),
						root,
						root,
						sidebarOf(XdgPlaces(), root),
						search = Search.Ready(),
					),
					Services(throwing, finder, report = {
						doing,
						failure,
						->
						reported += doing to failure
					}),
					scope,
				)

			try {
				kotlinx.coroutines.withTimeout(15_000) {
					session.state.first { it.listing is Listing.Shown }
					session.send(BrowserEvent.Opened(root.resolve("a")))

					val refused = session.state.first { it.listing is Listing.Unreadable }

					assertEquals(
						Listing.Unreadable("java.io.IOException: a: Input/output error"),
						refused.listing,
					)
					assertEquals(emptyList<Pair<String, Throwable>>(), reported.toList())

					session.send(BrowserEvent.OpenedSearch)
					session.send(BrowserEvent.SearchTyped("plan"))
					session.state.first { it.ready.matched != null }
					session.send(BrowserEvent.SearchEntered)

					val broke =
						try {
							session.state.first { it.notice is Notice.Broke }
						} catch (late: kotlinx.coroutines.TimeoutCancellationException) {
							val at = session.state.value

							throw AssertionError(
								"no notice: at ${at.at}, ${at.listing}, ${at.search}, reported $reported",
								late,
							)
						}

					assertEquals(
						Notice.Broke(
							"Couldn't read the sizes and dates of the results",
							Fault.of(IllegalStateException("no such column")),
						),
						broke.notice,
					)
					assertEquals(
						listOf("Couldn't read the sizes and dates of the results"),
						reported.map { it.first },
					)
					assertTrue(reported.single().second is IllegalStateException)
					// The results are shown all the same, with nothing said of what could not be read of them.
					assertEquals(
						listOf("plan.md", "plan.txt"),
						broke.rows.list
							.map { it.name }
							.sorted(),
					)
					assertEquals(listOf(null, null), broke.rows.list.map { it.size })
				}
			} finally {
				scope.cancel()
			}
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

	// ---- results pinned, and searched within ------------------------------------------------------------------------

	private val BrowserState.found get() = at as Location.Results

	/** [text] searched for from [state] as it is, and every match shown. */
	private fun explored(state: BrowserState, text: String): BrowserState =
		after(searched(state, text), BrowserEvent.SearchEntered)

	private fun reports() =
		made(
			"docs/report-2024.pdf",
			"docs/report-draft.pdf",
			"old/report-2023.txt",
			"misc/draft-notes.txt",
			"misc/summary.pdf",
		)

	@Test
	fun resultsArePinnedWhereTheyAreWithNothingSearchedAgainOrMoved() {
		reports()

		val shown = explored(picker(), "report")
		val on = after(shown, BrowserEvent.Clicked(shown.rows.list[1].id, Click.Only))

		assertFalse(on.found.pinned)
		assertEquals("Pin search", resultsMenuOf(on.found).labels().first())

		val pinning = step(on, BrowserEvent.PinnedSearch(true))
		val pinned = pinning.state

		// Said of the view, and nothing asked of the index or the disk for it.
		assertEquals(emptyList<Effect>(), pinning.effects)
		assertTrue(pinned.found.pinned)
		// The same results, the same rows, the cursor where it was, and nothing more behind it.
		assertTrue(pinned.found.finding === on.found.finding)
		assertTrue(pinned.listing === on.listing)
		assertEquals(on.rows.list, pinned.rows.list)
		assertEquals(on.cursor, pinned.cursor)
		assertEquals(on.history, pinned.history)
		// What the view keeps of the listing (how far it is scrolled) it keeps.
		assertEquals(on.at.listed, pinned.at.listed)
		assertEquals("Unpin search", resultsMenuOf(pinned.found).labels().first())

		// Its menu's item lets it go again: the view it was.
		val menu = resultsMenuOf(pinned.found).entries.first() as MenuEntry.Item

		assertEquals(on.at, after(pinned, menu.action).at)
		assertEquals(on.at, after(pinned, BrowserEvent.PinnedSearch(false)).at)
		// Anywhere but among results, there is nothing to pin.
		assertEquals(picker().at, after(picker(), BrowserEvent.PinnedSearch(true)).at)
	}

	private fun Menu<BrowserEvent>.labels(): List<String> =
		entries.filterIsInstance<MenuEntry.Item<BrowserEvent>>().map { it.label }

	@Test
	fun aSearchFromPinnedResultsIsMadeWithinThemAndItsResultsArePinnedToo() {
		reports()

		val pinned = after(explored(picker(), "report"), BrowserEvent.PinnedSearch(true))
		val opened = after(pinned, BrowserEvent.OpenedSearch)

		// The field is empty, as it always is, and the window knows what it searches within.
		assertEquals("", opened.ready.text)
		assertTrue(opened.ready.refines.any)
		assertEquals(listOf("report"), opened.ready.refines.texts)

		// What is typed is found among the pinned results alone: not the draft's notes, which no report is.
		val typed = after(opened, BrowserEvent.SearchTyped("-draft"))

		assertEquals("-draft", typed.ready.text)
		assertEquals(setOf("report-2024.pdf", "report-2023.txt"), typed.listed().toSet())

		// Shown, they are results of their own, behind which the pinned ones are as they were; and pinned as those are.
		val refined = after(typed, BrowserEvent.SearchEntered)

		assertEquals(setOf("report-2024.pdf", "report-2023.txt"), refined.shown().toSet())
		assertEquals(listOf("report", "-draft"), refined.found.searches)
		assertEquals("results for “report” › “-draft”", refined.placeName)
		assertTrue(refined.found.pinned)
		assertEquals(pinned.at, refined.history.back.last())

		// So a search from them is made within them, with no pin clicked: the second within the first.
		val twice = explored(refined, ".pdf")

		assertEquals(listOf("report-2024.pdf"), twice.shown())
		assertEquals(listOf("report", "-draft", ".pdf"), twice.found.searches)
		assertTrue(twice.found.pinned)

		// Searched again (something changed on disk), they are still what they were found within, and pinned.
		root.resolve("docs/report-2024.pdf").deleteExisting()

		val again = after(twice, BrowserEvent.Refreshed)

		assertEquals(emptyList<String>(), again.shown())
		assertEquals(twice.found.searches, again.found.searches)
		assertTrue(again.found.pinned)

		// Unpinned, a refinement alone is: what is behind it is pinned still, with its own search.
		val let = after(refined, BrowserEvent.PinnedSearch(false))

		assertFalse(let.found.pinned)
		assertEquals(listOf("report", "-draft"), let.found.searches)
		assertFalse(after(let, BrowserEvent.OpenedSearch).ready.refines.any)

		val back = after(let, BrowserEvent.Back)

		assertEquals(pinned.at, back.at)
		assertEquals(
			listOf("report"),
			after(back, BrowserEvent.OpenedSearch).ready.refines.texts,
		)
	}

	@Test
	fun aPinIsTheViewsKeptThroughTheHistoryAndOtherResultsAreSearchedFromAsEver() {
		reports()

		// Two searches shown, one after the other, and only the second pinned.
		val first = explored(picker(), "report")
		val second = after(explored(first, "draft"), BrowserEvent.PinnedSearch(true))

		assertFalse(second.found.refines.any)

		// Away to a folder and back: the results, pinned still, and searched within.
		val away = after(second, BrowserEvent.Opened(root.resolve("docs")))

		assertEquals(root.resolve("docs"), away.folder)
		assertFalse(after(away, BrowserEvent.OpenedSearch).ready.refines.any)

		val back = after(away, BrowserEvent.Back)

		assertTrue(back.found.pinned)
		assertEquals(second.shown(), back.shown())
		assertEquals(
			listOf("report-draft.pdf"),
			after(back, BrowserEvent.OpenedSearch, BrowserEvent.SearchTyped("report")).listed(),
		)

		// Back again, to the results never pinned: a search from them is of everything, and what it shows is not pinned.
		val earlier = after(back, BrowserEvent.Back)

		assertEquals(listOf("report"), earlier.found.searches)
		assertFalse(earlier.found.pinned)

		val global =
			after(earlier, BrowserEvent.OpenedSearch, BrowserEvent.SearchTyped("summary"))

		assertFalse(global.ready.refines.any)
		assertEquals(listOf("summary.pdf"), global.listed())
		assertFalse(after(global, BrowserEvent.SearchEntered).found.pinned)

		// And forward again, to the pinned ones.
		assertTrue(after(earlier, BrowserEvent.Forward).found.pinned)
	}

	@Test
	fun theSearchOfResultsShownIsChangedWithinWhatTheyWereFoundWithin() {
		reports()

		val pinned = after(explored(picker(), "report"), BrowserEvent.PinnedSearch(true))

		// A pinned search's own words, changed, are a search of everything: it refines nothing.
		val own = after(pinned, BrowserEvent.EditedSearch("report"))

		assertEquals("report", own.ready.text)
		assertFalse(own.ready.refines.any)

		// A refinement's words, changed, are still a search within what it refined.
		val refined = explored(pinned, "-draft")
		val edited = after(refined, BrowserEvent.EditedSearch("-draft"))

		assertEquals("-draft", edited.ready.text)
		assertEquals(listOf("report"), edited.ready.refines.texts)
		assertEquals(
			setOf("report-2024.pdf", "report-draft.pdf"),
			after(edited, BrowserEvent.SearchTyped(".pdf")).listed().toSet(),
		)
	}

	@Test
	fun eachSearchIsReadOnItsOwnSoNothingInOneChangesWhatAnotherMeans() {
		made(
			"taxes/tax return 2024.pdf",
			"taxes/tax return 2023.txt",
			"taxes/return tax.pdf",
			"docs/report-2024.pdf",
			"docs/report-2023.txt",
			"docs/report-draft.pdf",
			"old/docs/report-1999.pdf",
			"misc/notes-2024.pdf",
		)

		/** What [typed] finds within [pinned]'s results, pinned. */
		fun within(pinned: String, typed: String): Set<String> =
			after(
				explored(picker(), pinned),
				BrowserEvent.PinnedSearch(true),
				BrowserEvent.OpenedSearch,
				BrowserEvent.SearchTyped(typed),
			).listed().toSet()

		/** What [text] finds of everything. */
		fun everywhere(text: String): Set<String> = searched(picker(), text).listed().toSet()

		// A quote left open takes everything after it: typed after it, `.pdf` would be more of the phrase, and find
		// nothing. Within it, it is a term of its own.
		assertEquals(
			setOf("tax return 2024.pdf", "tax return 2023.txt"),
			everywhere("\"tax ret"),
		)
		assertEquals(emptySet<String>(), everywhere("\"tax ret .pdf"))
		assertEquals(setOf("tax return 2024.pdf"), within("\"tax ret", ".pdf"))
		// And a quote left open in what is typed takes nothing of what it is typed within.
		assertEquals(setOf("tax return 2024.pdf"), within(".pdf", "\"tax ret"))

		// Where both are whole, within is what both together find: a glob with alternatives, a regular expression, a
		// path, something left out.
		for (
		(pinned, typed) in
		listOf(
			"report-*.{pdf,txt}" to "2024",
			"r/^report-\\d{4}/" to "-2023",
			"docs/" to ".pdf",
			"dir:docs" to "-old/",
			"-draft" to "report",
			"report" to "r/\\d{4}\\.pdf$/",
		)
		) {
			val both = everywhere("$pinned $typed")

			assertTrue(both.isNotEmpty(), "$pinned $typed")
			assertEquals(both, within(pinned, typed), "$typed within $pinned")
			assertTrue(
				everywhere(typed).size > both.size,
				"$typed alone finds more than within $pinned",
			)
		}
	}

	@Test
	fun prismsOwnThingsAreNotAmongPinnedResultsButHowAQueryIsWrittenIsStillAsked() {
		reports()

		val pinned = after(explored(picker(), "report"), BrowserEvent.PinnedSearch(true))

		// Of everything, a picker's search finds the system's page by its name.
		assertEquals(listOf(Own.System), searched(picker(), "system").ready.own)
		// Within pinned results, it does not; a question mark alone still asks how a query is written.
		assertEquals(emptyList<Own>(), searched(pinned, "system").ready.own)
		assertEquals(listOf(Own.Help), searched(pinned, "?").ready.own)
	}

	@Test
	fun aFolderPickersSearchWithinPinnedResultsFindsOnlyFolders() {
		made("plans/", "old/plans-2023/", "old/plan.txt", "plans/draft.txt")

		val pinned =
			after(
				explored(picker(Choosing.Folder()), "plan"),
				BrowserEvent.PinnedSearch(true),
			)

		assertEquals(setOf("plans", "plans-2023"), pinned.shown().toSet())
		assertEquals(
			listOf("plans-2023"),
			after(pinned, BrowserEvent.OpenedSearch, BrowserEvent.SearchTyped("2023")).listed(),
		)
	}
}
