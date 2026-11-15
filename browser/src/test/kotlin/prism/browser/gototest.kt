package prism.browser

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces
import prism.index.Index
import prism.index.Indexer
import prism.index.query.Aliases
import prism.index.query.Own
import prism.preferences.MemoryPreferences
import prism.preferences.preferences

/**
 * Going to a place: the search's window opened to go somewhere (Ctrl+G), where Enter goes to the first thing listed
 * and nothing is shown in the listing; a path typed that is on the disk listed first, held by the index or not. And
 * the thumbnails turned off and on, which is every window's.
 */
class GoToTest {
	@TempDir
	lateinit var root: Path

	private val disk get() = JvmDisk()

	private lateinit var finder: Finder

	/** [files] made under [root] (a name ending in `/` a folder), and [indexed] of them indexed, for a finder. */
	private fun made(vararg files: String, indexed: Path = root) {
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
			StaticFinder(
				Index.EMPTY.with(Indexer(mounts = emptyList()).index(indexed)),
				home = root,
			)
	}

	private fun browser(choosing: Choosing = Choosing.Exploring()): BrowserState =
		opened(
			choosing,
			root,
			root,
			sidebarOf(XdgPlaces(), root),
			search = Search.Ready(),
		).settled(disk, finder)

	private fun after(state: BrowserState, vararg inputs: Input): BrowserState =
		inputs.fold(state) { at, input -> step(at, input).settled(disk, finder) }

	private fun going(state: BrowserState, text: String): BrowserState =
		after(state, BrowserEvent.OpenedGoTo, BrowserEvent.SearchTyped(text))

	private val BrowserState.ready: Search.Ready get() = search as Search.Ready

	private fun Search.Ready.names(): List<String> =
		(0 until listed).map {
			when (val listed = listedAt(it)) {
				is Search.Listed.Match -> listed.matches.nameOf(listed.rank)
				is Search.Listed.Place -> "place:${listed.path.fileName}"
				is Search.Listed.Named -> "alias:${listed.alias.name.written}"
				is Search.Listed.Prisms -> "own:${listed.own.title}"
				null -> "?"
			}
		}

	@Test
	fun theWindowOpensToGoSomewhereWithTheArrowsOnTheFirstThingListed() {
		made("Documents/letter.odt", "Documents/Letters/", "notes.txt")

		val opened = after(browser(), BrowserEvent.OpenedGoTo)

		assertTrue(opened.ready.open)
		assertEquals(Aim.Go(), opened.ready.aim)
		assertEquals("", opened.ready.text)
		// Nothing typed, nothing listed: the arrows are on nothing, and Enter does nothing.
		assertNull(opened.ready.highlighted)
		assertEquals(opened, after(opened, BrowserEvent.SearchEntered))

		// Typed, what is found is listed, and the arrows are on the first of it without being moved there.
		val typed = going(browser(), "letter")

		assertEquals(listOf("Letters", "letter.odt"), typed.ready.names().sorted())
		assertEquals(0, typed.ready.highlighted)
		// None of prism's own is a place to go to, whatever word is typed.
		assertEquals(emptyList<Own>(), going(browser(), "settings").ready.own)
		assertEquals(emptyList<Own>(), going(browser(), "prism").ready.own)
	}

	@Test
	fun aFolderTermAloneFindsTheFolderWhichEnterGoesInto() {
		made(
			"funny/a.jpg",
			"funny/sub/b.jpg",
			"x/funny/c.jpg",
			"funny-videos/v.mp4",
			"other/funny",
		)

		// The folders named so, not what is in them: in the window to go somewhere, and the one to find things.
		val going = going(browser(), "funny/")

		assertEquals(listOf("funny", "funny"), going.ready.names())
		assertEquals(
			setOf(root.resolve("funny"), root.resolve("x/funny")),
			setOf(
				after(going, BrowserEvent.SearchEntered).folder,
				after(going, BrowserEvent.MatchMoved(1), BrowserEvent.SearchEntered).folder,
			),
		)

		val finding =
			after(browser(), BrowserEvent.OpenedSearch, BrowserEvent.SearchTyped("funny/"))

		assertEquals(listOf("funny", "funny"), finding.ready.names())
		// With something asked of what is in them, that.
		assertEquals(
			listOf("a.jpg", "b.jpg", "c.jpg"),
			going(browser(), "funny/ .jpg").ready.names().sorted(),
		)
		assertEquals(listOf("sub"), going(browser(), "funny/ sub/").ready.names())

		// A folder picker's own `is:dir` is not the user's: the folders named so still, where typed it is those
		// in them.
		val picker = browser(Choosing.Folder())

		assertEquals(listOf("funny", "funny"), going(picker, "funny/").ready.names())
		assertEquals(listOf("sub"), going(picker, "funny/ is:dir").ready.names())

		// Shown as a listing, they are folders; pinned, a search from them is within them: the terms of both.
		val shown = after(finding, BrowserEvent.SearchEntered)

		assertEquals(listOf("funny", "funny"), shown.rows.list.map { it.name })

		val pinned = after(shown, BrowserEvent.PinnedSearch(true))

		fun refined(text: String) =
			after(
				pinned,
				BrowserEvent.OpenedSearch,
				BrowserEvent.SearchTyped(text),
				BrowserEvent.SearchEntered,
			).rows.list
				.map { it.name }
				.sorted()

		assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), refined(".jpg"))
		assertEquals(listOf("sub"), refined("sub/"))
	}

	@Test
	fun enterGoesIntoAFolderAndToAFilesFolderWithTheFileChosen() {
		made("Documents/Letters/a.txt", "Documents/report.pdf", "notes.txt")

		// A folder: into it.
		val folder = after(going(browser(), "Letters"), BrowserEvent.SearchEntered)

		assertFalse(folder.search.open)
		assertEquals(root.resolve("Documents/Letters"), folder.folder)

		// A file: to the folder it is in, with it chosen, and nothing opened or accepted; in a picker too.
		for (choosing in listOf(Choosing.Exploring(), Choosing.OneFile())) {
			val gone = going(browser(choosing), "report")
			val file = step(gone, BrowserEvent.SearchEntered)

			assertTrue(file.effects.none { it is Effect.Finish || it is Effect.Open }, "$choosing")

			val there = file.settled(disk, finder)

			assertFalse(there.search.open)
			assertEquals(root.resolve("Documents"), there.folder)
			assertEquals(listOf("report.pdf"), there.chosenRows.map { it.name })
		}
	}

	@Test
	fun thereIsNoShowingEveryMatchWhereThePlaceIsToBeGoneTo() {
		made("Documents/report.pdf", "report.txt")

		val typed = going(browser(), "report")

		// What shows every match in the listing does nothing here: the window stays, and what is shown is as it was.
		val shown = after(typed, BrowserEvent.ShowedResults)

		assertTrue(shown.search.open)
		assertNull(shown.results)
		assertEquals(root, shown.folder)

		// The arrows moved up to the field, Enter still goes to the first thing listed.
		val up = after(typed, BrowserEvent.MatchMoved(-1))

		assertNull(up.ready.highlighted)
		assertEquals(up, after(up, BrowserEvent.SearchEntered))
	}

	@Test
	fun itSearchesEverythingWhateverResultsArePinnedAndItsResultsAreNotPinned() {
		made("Documents/report.pdf", "Documents/letter.odt", "report.txt")

		val pinned =
			after(
				browser(),
				BrowserEvent.OpenedSearch,
				BrowserEvent.SearchTyped("report"),
				BrowserEvent.SearchEntered,
				BrowserEvent.PinnedSearch(true),
			)

		assertTrue((pinned.at as Location.Results).pinned)

		// From pinned results a search is made within them; a place gone to is looked for among everything.
		val going = going(pinned, "letter")

		assertFalse(going.ready.refines.any)
		assertFalse(going.ready.pins)
		assertEquals(listOf("letter.odt"), going.ready.names())
	}

	@Test
	fun aPathTypedThatIsOnTheDiskIsListedFirstWhetherOrNotTheIndexHoldsIt() {
		// Only Documents is indexed: Elsewhere is on the disk and nowhere in the index.
		made(
			"Documents/report.pdf",
			"Elsewhere/deep/file.bin",
			indexed = root.resolve("Documents").also { it.createDirectories() },
		)

		val elsewhere = root.resolve("Elsewhere/deep")
		val folder = going(browser(), elsewhere.toString())

		assertEquals(
			Aim.There(elsewhere.toString(), elsewhere, folder = true),
			folder.ready.there,
		)
		assertEquals(Search.Listed.Place(elsewhere, folder = true), folder.ready.listedAt(0))
		assertEquals(0, folder.ready.highlighted)
		assertEquals(elsewhere, after(folder, BrowserEvent.SearchEntered).folder)

		// A file: its folder, with it chosen. `~` is the home folder, which is the root here.
		val file = going(browser(), "~/Elsewhere/deep/file.bin")

		assertEquals(
			Search.Listed.Place(elsewhere.resolve("file.bin"), folder = false),
			file.ready.listedAt(0),
		)

		val there = after(file, BrowserEvent.SearchEntered)

		assertEquals(elsewhere, there.folder)
		assertEquals(listOf("file.bin"), there.chosenRows.map { it.name })

		// Nothing there: nothing is listed for it. And what is typed on is no longer what was looked up.
		assertNull(going(browser(), root.resolve("nowhere").toString()).ready.there)
		assertNull(
			after(folder, BrowserEvent.SearchTyped("$elsewhere/no")).ready.there,
		)

		// Where the index holds the very place, it is listed once: as its match.
		val held = going(browser(), root.resolve("Documents/report.pdf").toString())

		assertEquals(listOf("report.pdf"), held.ready.names())

		// A search of everything looks up no path: that is for going somewhere.
		val searched =
			after(
				browser(),
				BrowserEvent.OpenedSearch,
				BrowserEvent.SearchTyped(elsewhere.toString()),
			)

		assertNull(searched.ready.there)
		assertEquals(Aim.Find, searched.ready.aim)
	}

	@Test
	fun aPlaceTypedHasAMenuOfItsOwnAndAnAliasIsStillNamed() {
		made(
			"Elsewhere/",
			"Documents/a.txt",
			indexed =
				root.resolve("Documents").also {
					it.createDirectories()
				},
		)

		val place = going(browser(), root.resolve("Elsewhere").toString())
		val menu = after(place, BrowserEvent.MatchMenuOpened())

		assertEquals(
			listOf(MatchAction.GoTo, MatchAction.CopyPath),
			menu.ready.menu
				?.actions
				?.actions,
		)

		val copied = step(menu, BrowserEvent.MatchActionChosen(1))

		assertEquals(
			listOf<Effect>(Effect.Copy(root.resolve("Elsewhere").toString())),
			copied.effects,
		)

		// An alias the word being typed could name is offered, as in any search.
		val aliased =
			after(
				browser().copy(aliases = aliasesOf(mapOf("docs" to "$root/Documents/"))),
				BrowserEvent.OpenedGoTo,
				BrowserEvent.SearchTyped("do"),
			)

		assertEquals(Aliases::class, aliased.ready.aliases::class)
		assertTrue("alias:docs@" in aliased.ready.names(), aliased.ready.names().toString())
	}

	@Test
	fun thumbnailsAreTurnedOffAndOnForEveryWindowAndKept() {
		made("a.png")

		val preferences = MemoryPreferences()
		val services = Services(disk, finder, preferences = preferences)
		val first = browser()

		assertTrue(first.thumbnails)

		val off = step(first, BrowserEvent.ToggledThumbnails)

		assertFalse(off.state.thumbnails)
		assertEquals(listOf<Effect>(Effect.Thumbnail(false)), off.effects)
		off.settled(services)
		assertFalse(preferences.preferences.value.thumbnails)

		// Another window is told what the preferences now say, and is so too.
		assertFalse(step(browser(), Answer.Thumbnailed(false)).state.thumbnails)

		val on = step(off.state, BrowserEvent.ToggledThumbnails)

		assertTrue(on.state.thumbnails)
		on.settled(services)
		assertTrue(preferences.preferences.value.thumbnails)
	}

	@Test
	fun theWindowAndTheToolbarAreDrawn() {
		made(
			"Documents/Letters/a.txt",
			"Documents/letter.odt",
			"Pictures/cover.png",
			"notes.txt",
		)

		val going = going(browser(), "letter")
		val path = going(browser(), root.resolve("Documents").toString())
		val gallery = after(browser(), BrowserEvent.Viewed(prism.preferences.Layout.Gallery))

		val drawn =
			listOf(
				"goto/goto-empty" to after(browser(), BrowserEvent.OpenedGoTo),
				"goto/goto-typed" to going,
				"goto/goto-path" to path,
				"goto/goto-picker" to going(browser(Choosing.OneFile()), "letter"),
				"thumbtoggle/thumbnails-on" to gallery,
				"thumbtoggle/thumbnails-off" to after(gallery, BrowserEvent.ToggledThumbnails),
				"thumbtoggle/thumbnails-off-list" to after(browser(), BrowserEvent.ToggledThumbnails),
			)

		for ((name, state) in drawn)
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png = renderedPng(state, 1100, 680, palette, scale = 2f)

				assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
				System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
					Path
						.of(dir)
						.resolve("$name-$tone.png")
						.also { it.parent.createDirectories() }
						.toFile()
						.writeBytes(png)
				}
			}
	}
}
