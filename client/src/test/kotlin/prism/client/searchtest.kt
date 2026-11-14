package prism.client

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.cli.Ran
import prism.cli.execute

/**
 * `prism search`, over an index of the tests' own: which of its words are options and which the query, what each
 * option prints and ends with, where `--goto` goes, and how what is found is said by one prism to another.
 */
class SearchTest {
	/** What the tests' index holds for any query, best first; the folders among them. */
	private val held =
		listOf("/home/a/Documents/report.pdf", "/home/a/Documents", "/home/a/old/report.txt") +
			(4..50).map { "/home/a/more/file$it" }
	private val folders = setOf("/home/a/Documents")

	/** What a search printed, said and ended with, and what it asked for. */
	private class Run(
		val out: String,
		val err: List<String>,
		val status: Int,
		val asked: List<Sought>,
	)

	/**
	 * `prism search` given [words], over [held] (or, where [running] is false, with no prism to ask and [otherwise]
	 * asked in its place); [chosen] is what the user chooses from a menu.
	 */
	private fun search(
		vararg words: String,
		answer: (Sought) -> Sifted = { sought ->
			Sifted.Found(held.size, held.drop(sought.from).take(sought.limit))
		},
		running: Boolean = true,
		chosen: (List<String>) -> String? = { error("no menu was expected") },
		environment: Map<String, String> = emptyMap(),
	): Run {
		val out = StringBuilder()
		val err = ArrayList<String>()
		val asked = ArrayList<Sought>()

		val command =
			SearchCommand(
				running = { sought -> answer(sought).takeIf { running }?.also { asked += sought } },
				otherwise = { sought -> answer(sought).also { asked += sought } },
				environment = environment,
				out = { out.append(it) },
				err = { err += it },
				choose = chosen,
				isFolder = { it.toString() in folders },
			)

		val ran: Ran = execute(command, words.toList())

		return Run(
			out.toString() + ran.out,
			err +
				listOfNotNull(
					ran.err.ifEmpty {
						null
					},
				),
			ran.status,
			asked,
		)
	}

	@Test
	fun aWordThatIsExactlyAnOptionIsTheOptionAndEveryOtherIsTheQuerys() {
		// -draft and -ext:pdf are the query's; -b is the option; a word with a space in it is a quoted term.
		val mixed = search("report", "-draft", "-b", "-ext:pdf", "tax return", "dir:old photos")

		assertEquals(
			"report -draft -ext:pdf \"tax return\" dir:\"old photos\"",
			mixed.asked.single().text,
		)
		assertEquals(1, mixed.asked.single().limit)
		// What collides with an option is written after two dashes; quotes of its own are left as they are.
		assertEquals(
			"report -g -b -\"g\"",
			search("report", "--", "-g", "-b", "-\"g\"").asked.single().text,
		)
		assertEquals(SearchCommand.COUNT, search("report", "--", "-b").asked.single().limit)
		// Files only, folders only: said in the query's own words.
		assertEquals("report is:file", search("report", "--files").asked.single().text)
		assertEquals("report is:dir", search("report", "--dirs").asked.single().text)
		// A name that only looks like a modifier is quoted whole.
		assertEquals("\"re: notes\"", SearchCommand.termOf("re: notes"))
		assertEquals("-\"old photos\"", SearchCommand.termOf("-old photos"))
		assertEquals("=\"tax return\"", SearchCommand.termOf("=tax return"))
	}

	@Test
	fun theBestTwentyArePrintedALineEachUnlessToldHowMany() {
		val usual = search("file")

		assertEquals(held.take(20).joinToString("") { "$it\n" }, usual.out)
		assertEquals(0, usual.status)
		assertEquals(emptyList<String>(), usual.err)
		assertEquals(held.take(1).joinToString("") { "$it\n" }, search("file", "-b").out)
		assertEquals(held.take(3).joinToString("") { "$it\n" }, search("file", "-n", "3").out)
		assertEquals(held.take(3).joinToString("") { "$it\n" }, search("file", "--count=3").out)
		assertEquals(held.joinToString("") { "$it\n" }, search("file", "-a").out)
		// A NUL after each, for what reads them so.
		assertEquals(
			held.take(2).joinToString("") {
				"$it\u0000"
			},
			search("file", "-0", "-n", "2").out,
		)

		// Nothing found is no output and status 1, as grep has it.
		val nothing = search("zzz", answer = { Sifted.Found(0, emptyList()) })

		assertEquals("", nothing.out)
		assertEquals(SearchCommand.NOTHING, nothing.status)
	}

	@Test
	fun everyMatchIsAskedForAPageAtATime() {
		val many = (1..SearchCommand.PAGE * 2 + 5).map { "/f/$it" }

		val all =
			search(
				"f",
				"-a",
				answer = { Sifted.Found(many.size, many.drop(it.from).take(it.limit)) },
			)

		assertEquals(many.size, all.out.lines().count { it.isNotEmpty() })
		assertEquals(
			listOf(0, SearchCommand.PAGE, SearchCommand.PAGE * 2),
			all.asked.map {
				it.from
			},
		)
		assertTrue(all.asked.all { it.limit <= SearchCommand.PAGE })
	}

	@Test
	fun aQueryThatCannotBeSearchedForSaysWhyAndNoIndexSaysHowToMakeOne() {
		val wrong =
			search("foo:bar", answer = { Sifted.Wrong(listOf("there is no “foo:”: foo:bar")) })

		assertEquals(Matches.WRONG, wrong.status)
		assertEquals(listOf("prism search: there is no “foo:”: foo:bar"), wrong.err)
		assertEquals("", wrong.out)

		val none =
			search("x", running = false, answer = { Sifted.NoIndex("there is no index at /i") })

		assertEquals(1, none.status)
		assertEquals(listOf("search: there is no index at /i"), none.err)
		assertEquals(listOf("search: what to search for is missing"), search().err)
		// The prism running is asked first, and whoever made the command only where it has no answer.
		assertEquals(1, search("x").asked.size)
		assertEquals(1, search("x", running = false).asked.size)
	}

	@Test
	fun gotoPrintsTheFolderOfTheOneMatchAndListsSeveralWithAStatusOfItsOwn(
		@TempDir folder: Path,
	) {
		fun only(path: String): (Sought) -> Sifted = { Sifted.Found(1, listOf(path)) }

		// A folder is gone into; a file's folder is gone to.
		assertEquals(
			"/home/a/Documents\n",
			search("doc", "-g", answer = only("/home/a/Documents")).out,
		)
		assertEquals(
			"/home/a/Documents\n",
			search("report", "-g", answer = only("/home/a/Documents/report.pdf")).out,
		)

		val several = search("report", "-g")

		assertEquals(SearchCommand.SEVERAL, several.status)
		assertEquals("", several.out)
		assertTrue(several.err.first().startsWith("prism search: 50 matches, so nowhere to go"))
		assertEquals(held.take(20), several.err.drop(1))

		val nothing = search("zzz", "-g", answer = { Sifted.Found(0, emptyList()) })

		assertEquals(SearchCommand.NOTHING, nothing.status)
		assertEquals(listOf("prism search: nothing matches"), nothing.err)
		// The best of several is somewhere to go.
		assertEquals("/home/a/Documents\n", search("report", "-g", "-b").out)

		// A shell's wrapper is told through the file it names, and nothing is printed.
		val file = folder.resolve("goto")

		val told =
			search(
				"report",
				"-g",
				"-b",
				environment = mapOf(SearchCommand.GOTO_FILE to file.toString()),
			)

		assertEquals("", told.out)
		assertEquals(0, told.status)
		assertEquals("/home/a/Documents", file.readText())
	}

	@Test
	fun interactiveGivesTheMatchChosenOrGoesToItAndCancelledGivesNothing() {
		val menus = ArrayList<List<String>>()

		val picked =
			search("report", "-i", chosen = { paths ->
				menus += paths
				paths[2]
			})

		assertEquals("${held[2]}\n", picked.out)
		assertEquals(0, picked.status)
		// The menu is of what would have been printed: as many as -n says, or all.
		assertEquals(held.take(20), menus.single())
		search("report", "-i", "-a", chosen = { paths -> paths.last().also { menus += paths } })
		assertEquals(held, menus.last())

		assertEquals(
			"/home/a/old\n",
			search("report", "-i", "-g", chosen = { it[2] }).out,
		)

		val cancelled = search("report", "-i", chosen = { null })

		assertEquals("", cancelled.out)
		assertEquals(SearchCommand.CANCELLED, cancelled.status)

		val nowhere =
			search(
				"report",
				"-i",
				chosen = { throw NoTerminal("there is no terminal to choose on") },
			)

		assertEquals(listOf("search: there is no terminal to choose on"), nowhere.err)
		assertEquals(1, nowhere.status)
	}

	@Test
	fun whatWasFoundIsSaidByOnePrismToAnotherAndReadBackAsItWas() {
		val found = Sifted.Found(1284, listOf("/a/one", "/a/two words", "/a/new\nline"))

		val each: List<Sifted> =
			listOf(
				found,
				Sifted.Found(0, emptyList()),
				Sifted.Found(7, emptyList()),
				Sifted.Wrong(listOf("the quote is not closed", "there is no “foo:”")),
				Sifted.NoIndex("the prism already running keeps no index"),
			)

		for (sifted in each)
			assertEquals(sifted, Matches.read(Matches.said(sifted)))

		assertNull(Matches.read(Ran("", "", 70)))
		assertNull(Matches.read(Ran("not a count\n/a", "", 0)))
		assertEquals(
			listOf("matches", "--from", "40", "--limit", "20", "--", "report -draft"),
			Matches.words(Sought("report -draft", 40, 20)),
		)
		assertEquals(
			Path("/a/one"),
			Path((Matches.read(Matches.said(found)) as Sifted.Found).paths.first()),
		)
	}
}
