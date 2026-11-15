package prism.browser

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces
import prism.index.Index
import prism.index.Indexer
import prism.index.query.AliasProblem
import prism.index.query.Aliases

/**
 * A search kept as an alias from the bar that says its results are shown: its name asked for and held to the rule
 * every alias is, the search written as one query where it was made within pinned searches, and what is kept in
 * effect at once. And the two things a pinned search and an alias have to do with each other: an alias in a pinned
 * search is stood for in what refines it, and a name being typed is completed while refining as anywhere.
 */
class SaveAliasTest {
	@TempDir
	lateinit var root: Path

	private val disk get() = JvmDisk()

	private lateinit var finder: Finder

	private fun made(vararg files: String) {
		for (file in files)
			root.resolve(file).also { it.parent.createDirectories() }.writeText(file)
		finder =
			StaticFinder(Index.EMPTY.with(Indexer(mounts = emptyList()).index(root)), home = root)
	}

	private fun tree() =
		made(
			"Photos/a.raw",
			"Photos/2024/b.raw",
			"Photos/2024/c.jpg",
			"elsewhere/d.raw",
			"elsewhere/2024/e.raw",
			"docs/tax return.pdf",
			"docs/tax return.txt",
		)

	private val aliases by lazy { aliasesOf(mapOf("raws" to "$root/Photos/ ext:raw")) }

	private fun explorer(): BrowserState =
		after(
			opened(
				Choosing.Exploring(),
				root,
				root,
				sidebarOf(XdgPlaces(), root),
				search = Search.Ready(),
			).settled(disk, finder),
			Answer.Aliased(aliases),
		)

	private fun after(state: BrowserState, vararg inputs: Input): BrowserState =
		inputs.fold(state) { at, input -> step(at, input).settled(disk, finder) }

	/** [text] searched for from [state], and its results shown in the listing. */
	private fun explored(state: BrowserState, text: String): BrowserState =
		after(
			state,
			BrowserEvent.OpenedSearch,
			BrowserEvent.SearchTyped(text),
			BrowserEvent.SearchEntered,
		)

	private val BrowserState.found: Location.Results get() = at as Location.Results

	private val BrowserState.ready: Search.Ready get() = search as Search.Ready

	private val BrowserState.naming: Notice.NamingAlias get() = notice as Notice.NamingAlias

	private fun BrowserState.names(): Set<String> = rows.list.map { it.name }.toSet()

	private fun Menu<BrowserEvent>.labels(): List<String> =
		entries.filterIsInstance<MenuEntry.Item<BrowserEvent>>().map { it.label }

	@Test
	fun theResultsBarsMenuKeepsTheSearchAsAnAliasOnceItIsNamed() {
		tree()

		val shown = explored(explorer(), "2024/ ext:raw")

		assertEquals(setOf("b.raw", "e.raw"), shown.names())
		assertEquals(
			listOf("Pin search", "Save as alias…"),
			resultsMenuOf(shown.found).labels(),
		)
		// Under a line of its own, apart from what pins.
		assertTrue(resultsMenuOf(shown.found).entries[1] is MenuEntry.Separator)

		// Its name is asked for, of the search as it was typed, among the aliases there are.
		val asked = after(shown, BrowserEvent.SavingAlias)

		assertEquals(Notice.NamingAlias("2024/ ext:raw", "", aliases), asked.notice)
		assertEquals(AliasProblem.Unnamed, asked.naming.problem)
		// Nothing is kept while it cannot be one: the notice stays, saying why.
		assertEquals(asked.notice, step(asked, BrowserEvent.Confirmed).state.notice)
		assertEquals(emptyList<Effect>(), step(asked, BrowserEvent.Confirmed).effects)

		fun named(name: String) = after(asked, BrowserEvent.AnswerTyped(name))

		assertEquals(AliasProblem.Misnamed, named("new raws").naming.problem)
		assertEquals("“raws@” is already defined", named("Raws").naming.problem?.message)
		assertEquals(emptyList<Effect>(), step(named("Raws"), BrowserEvent.Confirmed).effects)
		// The `@` a name is used with, typed after it, is not part of it.
		assertEquals("raws2024", named("raws2024@").naming.name)
		assertNull(named("raws2024").naming.problem)

		// Named, it is kept: for every window, and in this one's searches at once.
		val saving = step(named("raws2024"), BrowserEvent.Confirmed)
		val saved = saving.state

		assertEquals(listOf(Effect.Alias("raws2024", "2024/ ext:raw")), saving.effects)
		assertNull(saved.notice)
		assertEquals(
			listOf("raws" to "$root/Photos/ ext:raw", "raws2024" to "2024/ ext:raw"),
			saved.aliases.written,
		)
		// The results it was kept from are still what is shown, and searching for it by name finds them.
		assertEquals(shown.at, saved.at)
		assertEquals(setOf("b.raw", "e.raw"), explored(saved, "raws2024@").names())
		assertEquals(setOf("b.raw"), explored(saved, "raws2024@ raws@").names())

		// Let go of, nothing is kept.
		val dismissed = step(named("raws2024"), BrowserEvent.Dismissed)

		assertNull(dismissed.state.notice)
		assertEquals(emptyList<Effect>(), dismissed.effects)
		assertEquals(aliases, dismissed.state.aliases)

		// Where no results are shown there is nothing to name.
		assertNull(after(explorer(), BrowserEvent.SavingAlias).notice)
	}

	@Test
	fun whatIsKeptIsAddedToThePreferencesByTheRuleThePageHoldsThemTo() {
		val written = mapOf("raws" to "ext:raw")

		assertEquals(
			mapOf("raws" to "ext:raw", "docs" to "ext:pdf"),
			aliasAdded(written, "docs", "ext:pdf"),
		)
		// One a settings window defined meanwhile is not written over, whatever its case.
		assertNull(aliasAdded(written, "RAWS", "ext:pdf"))
		assertNull(aliasAdded(written, "my docs", "ext:pdf"))
		assertNull(aliasAdded(written, "docs", "nosuch@"))
	}

	@Test
	fun theAliasesChangingWhileANameIsAskedForAreTheOnesItIsHeldTo() {
		tree()

		val naming =
			after(
				explored(explorer(), "ext:raw"),
				BrowserEvent.SavingAlias,
				BrowserEvent.AnswerTyped("camera"),
			)

		assertNull(naming.naming.problem)

		// Defined in a settings window meanwhile: the name is taken now, and is not kept.
		val taken = aliasesOf(mapOf("camera" to "ext:cr2"))
		val changed = after(naming, Answer.Aliased(taken))

		assertEquals(taken, changed.naming.among)
		assertEquals("“camera@” is already defined", changed.naming.problem?.message)
		assertEquals(emptyList<Effect>(), step(changed, BrowserEvent.Confirmed).effects)
	}

	@Test
	fun aSearchMadeWithinPinnedSearchesIsKeptAsAllOfThemWrittenAsOne() {
		tree()

		val pinned = after(explored(explorer(), "raws@"), BrowserEvent.PinnedSearch(true))
		val refined = explored(pinned, "2024/")

		assertEquals(listOf("raws@", "2024/"), refined.found.searches)
		assertEquals(setOf("b.raw"), refined.names())

		val saving =
			step(
				after(refined, BrowserEvent.SavingAlias, BrowserEvent.AnswerTyped("raws2024")),
				BrowserEvent.Confirmed,
			)

		// One query, which uses the alias the pinned search used, and finds what the two found together.
		assertEquals(listOf(Effect.Alias("raws2024", "raws@ 2024/")), saving.effects)
		assertEquals(setOf("b.raw"), explored(saving.state, "raws2024@").names())

		// A pinned search left unfinished refines as it reads, but cannot be written before another: that is said,
		// and nothing is named.
		val open = after(explored(explorer(), "\"tax ret"), BrowserEvent.PinnedSearch(true))
		val within = explored(open, ".pdf")

		assertEquals(setOf("tax return.pdf"), within.names())

		val refused = after(within, BrowserEvent.SavingAlias)

		assertEquals(
			Notice.Failed(
				"Couldn't save as alias",
				"Missing closing quote in “\"tax ret”. Edit the search, then save it.",
			),
			refused.notice,
		)

		// Unfinished and alone, the same is said: it is no way to leave a definition.
		val alone = after(open, BrowserEvent.SavingAlias)

		assertEquals(
			Notice.Failed(
				"Couldn't save as alias",
				"Missing closing quote in “\"tax ret”. Edit the search, then save it.",
			),
			alone.notice,
		)
	}

	@Test
	fun anAliasInAPinnedSearchIsStoodForInWhatRefinesItAndFoundAgainAsItNowIs() {
		tree()

		val pinned = after(explored(explorer(), "raws@"), BrowserEvent.PinnedSearch(true))

		assertEquals(setOf("a.raw", "b.raw"), pinned.names())

		// What is typed is found among what the alias finds, in the window's matches and in the results shown.
		val typing = after(pinned, BrowserEvent.OpenedSearch, BrowserEvent.SearchTyped("2024/"))
		val matches = (typing.ready.found as Finding.Matched).matches

		assertEquals(listOf("b.raw"), matches.ranks.map(matches::nameOf))

		val refined = after(typing, BrowserEvent.SearchEntered)

		assertEquals(setOf("b.raw"), refined.names())
		assertTrue(refined.found.pinned)

		// The alias defined otherwise in a settings window while they are shown: read again, they are what the
		// pinned search now finds, refined as they were.
		val elsewhere = aliasesOf(mapOf("raws" to "$root/elsewhere/ ext:raw"))
		val changed = after(refined, Answer.Aliased(elsewhere), BrowserEvent.Refreshed)

		assertEquals(listOf("raws@", "2024/"), changed.found.searches)
		assertEquals(setOf("e.raw"), changed.names())
		// And a search within them is within what it now finds.
		assertEquals(setOf("e.raw"), explored(changed, ".raw").names())
		// So are the pinned results themselves, gone back to and read again.
		assertEquals(
			setOf("d.raw", "e.raw"),
			after(changed, BrowserEvent.Back, BrowserEvent.Refreshed).names(),
		)

		// An alias there is no longer any of is left out, as in any search: what refines it is all that is asked,
		// which alone is a folder term, and finds the folders of that name.
		val gone = after(refined, Answer.Aliased(Aliases.NONE), BrowserEvent.Refreshed)

		assertEquals(setOf("2024"), gone.names())
	}

	@Test
	fun aNameBeingTypedIsCompletedWhileRefiningAPinnedSearchAsAnywhere() {
		tree()

		val pinned = after(explored(explorer(), "2024/"), BrowserEvent.PinnedSearch(true))
		val typed = after(pinned, BrowserEvent.OpenedSearch, BrowserEvent.SearchTyped("ra"))

		// Prism's own things are not among any search's results; how a query is written, an alias included, still is.
		assertTrue(typed.ready.refines.any)
		assertEquals(
			listOf("raws"),
			typed.ready.completing
				?.aliases
				?.map { it.name.text },
		)
		assertEquals(
			"raws@",
			(typed.ready.listedAt(0) as Search.Listed.Named).alias.name.written,
		)

		// Chosen, it is in the field, and what it finds is found among the pinned results.
		val chosen = after(typed, BrowserEvent.MatchChosen(0))
		val matches = (chosen.ready.found as Finding.Matched).matches

		assertEquals("raws@ ", chosen.ready.text)
		assertEquals(listOf("b.raw"), matches.ranks.map(matches::nameOf))
		assertEquals(setOf("b.raw"), after(chosen, BrowserEvent.SearchEntered).names())
	}

	/** Drawn headless, in both palettes: with `-Pbrowser.screenshots=DIR`, kept there. */
	@Test
	fun theMenuAndTheNameAskedForDraw() {
		tree()

		val pinned = after(explored(explorer(), "raws@"), BrowserEvent.PinnedSearch(true))
		val refined = explored(pinned, "2024/")
		val asked = after(refined, BrowserEvent.SavingAlias)

		val pictures =
			listOf(
				Triple("menu", refined, true),
				Triple("empty", asked, false),
				Triple("named", after(asked, BrowserEvent.AnswerTyped("raws2024")), false),
				Triple("taken", after(asked, BrowserEvent.AnswerTyped("raws")), false),
				Triple("misnamed", after(asked, BrowserEvent.AnswerTyped("raws 2024")), false),
			)

		for ((name, state, menu) in pictures)
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png =
					renderedPng(1100, 680, palette, scale = 2f) {
						FileBrowser(state)
						if (menu)
							MenuPopup(
								resultsMenuOf(state.found),
								MenuAnchor.At(
									androidx.compose.ui.unit
										.IntOffset(480, 84),
								),
								{},
								{},
							)
					}

				assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
				System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
					Path
						.of(dir)
						.also { it.createDirectories() }
						.resolve("savealias-$name-$tone.png")
						.toFile()
						.writeBytes(png)
				}
			}
	}
}
