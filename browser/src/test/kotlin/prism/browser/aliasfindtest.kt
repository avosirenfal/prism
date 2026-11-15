package prism.browser

import androidx.compose.ui.text.style.TextDecoration
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
import prism.index.query.Aliases
import prism.preferences.Role

/**
 * Search aliases in a browser's search: a `name@` typed stands for the alias's query, the aliases a word could be
 * naming are listed to be chosen, and a change to them is in the search that is open.
 */
class AliasFindTest {
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

	private val aliases by lazy {
		aliasesOf(
			mapOf(
				"projects" to "$root/projects/",
				"raws" to "$root/Photos/ ext:raw",
				"raws2024" to "raws@ 2024/",
			),
		)
	}

	/** An explorer over [root], with the index to search and the aliases the preferences define. */
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

	private fun searched(state: BrowserState, text: String): BrowserState =
		after(state, BrowserEvent.OpenedSearch, BrowserEvent.SearchTyped(text))

	private val BrowserState.ready: Search.Ready get() = search as Search.Ready

	private fun BrowserState.listed(): Set<String> =
		(ready.found as Finding.Matched).matches.let { matches ->
			matches.ranks.map(matches::nameOf).toSet()
		}

	private fun tree() =
		made(
			"projects/prism/notes.md",
			"projects/other/notes.md",
			"Photos/a.raw",
			"Photos/2024/b.raw",
			"Photos/2024/c.jpg",
			"elsewhere/d.raw",
			"rare.txt",
		)

	@Test
	fun anAliasTypedStandsForItsQuery() {
		tree()

		assertEquals(setOf("a.raw", "b.raw"), searched(explorer(), "raws@").listed())
		assertEquals(setOf("b.raw"), searched(explorer(), "raws2024@").listed())
		assertEquals(setOf("d.raw"), searched(explorer(), "-raws@ ext:raw").listed())

		val prism = searched(explorer(), "projects@ prism/").ready.found as Finding.Matched

		assertEquals(
			listOf("$root/projects/prism/notes.md"),
			prism.matches.ranks.map { prism.matches.pathOf(it).toString() },
		)

		// One there is none of is said, and the rest is searched for without it.
		val unknown = searched(explorer(), "nosuch@ .raw")

		assertEquals(
			listOf("Unknown alias “nosuch@”"),
			unknown.ready.read.problems.map {
				it.message
			},
		)
		assertEquals(setOf("a.raw", "b.raw", "d.raw"), unknown.listed())

		// The results shown as a folder are found again as the aliases say, when they are read again.
		val shown = after(searched(explorer(), "raws@"), BrowserEvent.SearchEntered)

		assertTrue(shown.at is Location.Results)
		assertEquals(
			setOf("a.raw", "b.raw"),
			shown.rows.list
				.map { it.name }
				.toSet(),
		)
		assertEquals(
			setOf("a.raw", "b.raw"),
			after(shown, BrowserEvent.Refreshed)
				.rows.list
				.map { it.name }
				.toSet(),
		)
	}

	@Test
	fun aChangeToTheAliasesIsInTheSearchThatIsOpen() {
		tree()

		val typed = searched(explorer(), "raws@")

		assertEquals(setOf("a.raw", "b.raw"), typed.listed())

		// Defined otherwise in a settings window meanwhile: searched for again, as it now means.
		val changed =
			after(typed, Answer.Aliased(aliasesOf(mapOf("raws" to "$root/elsewhere/ ext:raw"))))

		assertEquals(setOf("d.raw"), changed.listed())
		assertEquals("raws@", changed.ready.text)

		// And the next search opened is read with them too.
		val removed = after(changed, Answer.Aliased(Aliases.NONE), BrowserEvent.ClosedSearch)

		assertEquals(
			1,
			searched(removed, "raws@")
				.ready.read.problems.size,
		)
	}

	@Test
	fun theAliasesAWordCouldNameAreListedAndChosenIntoTheField() {
		tree()

		val typed = searched(explorer(), "ra")

		// After prism's own (none here) and before the matches; the arrows stay in the field, so that Enter still
		// shows what the word itself finds.
		assertEquals(
			listOf("raws", "raws2024"),
			typed.ready.completing?.aliases?.map {
				it.name.text
			},
		)
		assertEquals(
			listOf("raws@", "raws2024@"),
			(0..1).map { (typed.ready.listedAt(it) as Search.Listed.Named).alias.name.written },
		)
		assertTrue(typed.ready.listedAt(2) is Search.Listed.Match)
		assertNull(typed.ready.highlighted)
		assertEquals(2 + typed.listed().size, typed.ready.listed)

		// Chosen, its name is where the word was, with a space for what comes next, and the window stays.
		val chosen = after(typed, BrowserEvent.MatchChosen(0))

		assertEquals("raws@ ", chosen.ready.text)
		assertTrue(chosen.search.open)
		assertEquals(setOf("a.raw", "b.raw"), chosen.listed())
		assertNull(chosen.ready.completing)

		// By the arrows and Enter the same; and among other terms, negated or not, only the last word is completed.
		val entered =
			after(
				searched(explorer(), "2024/ -raws2"),
				BrowserEvent.MatchMoved(1),
				BrowserEvent.SearchEntered,
			)

		assertEquals("2024/ -raws2024@ ", entered.ready.text)
		assertEquals(setOf("c.jpg"), entered.listed())

		// From its menu too, where all there is to do is use it.
		assertEquals(
			listOf(MatchAction.Use),
			actionsOf(typed.ready.listedAt(0)!!, Role.Explorer, terminal = true),
		)

		// Nothing is offered once the word is ended, for what is no name, or for one that is an alias already.
		for (text in listOf("ra ", "ra/", "\"ra", "^ra", "ext:ra", "raws@", "zz", ""))
			assertNull(searched(explorer(), text).ready.completing) { text }
	}

	@Test
	fun anAliasIsColouredAsOneAndOneThereIsNoneOfIsMarkedWrong() {
		val palette = Palette.Light
		val known = colouredQuery("raws@ x", aliasesOf(mapOf("raws" to "ext:raw")), palette)

		assertEquals(
			listOf(palette.audio to (0 to 5), palette.text to (6 to 7)),
			known.spanStyles.map { it.item.color to (it.start to it.end) },
		)

		val unknown = colouredQuery("raws@ x", Aliases.NONE, palette)
		val marked =
			unknown.spanStyles.single {
				it.item.textDecoration ==
					TextDecoration.Underline
			}

		assertEquals(palette.pdf to (0 to 5), marked.item.color to (marked.start to marked.end))
	}

	/** Drawn headless, in both palettes: with `-Pbrowser.screenshots=DIR`, kept there. */
	@Test
	fun theSearchWithAliasesDraws() {
		tree()

		val drawn =
			mapOf(
				"completing" to searched(explorer(), "ra"),
				"used" to searched(explorer(), "raws@ 2024/"),
				"unknown" to searched(explorer(), "rawz@ 2024/"),
				"help" to after(searched(explorer(), "raws@"), BrowserEvent.ToggledSearchHelp),
				"help-none" to
					after(
						searched(after(explorer(), Answer.Aliased(Aliases.NONE)), "x"),
						BrowserEvent.ToggledSearchHelp,
					),
			)

		for ((name, state) in drawn)
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png = renderedPng(state, 1100, 760, palette, scale = 2f)

				assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
				System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
					Path
						.of(dir)
						.also { it.createDirectories() }
						.resolve("alias-$name-$tone.png")
						.toFile()
						.writeBytes(png)
				}
			}
	}
}
