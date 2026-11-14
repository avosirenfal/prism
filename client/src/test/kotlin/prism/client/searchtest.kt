package prism.client

import kotlin.io.path.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.cli.Ran
import prism.cli.execute

/**
 * `prism search`, over an index of the tests' own: which of its words are options and which the query, what each
 * option prints and ends with, the one match `--select` gives, and how what is found is said by one prism to another.
 */
class SearchTest {
	/** What the tests' index holds for any query, best first. */
	private val held =
		listOf("/home/a/Documents/report.pdf", "/home/a/Documents", "/home/a/old/report.txt") +
			(4..50).map { "/home/a/more/file$it" }

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
	): Run {
		val out = StringBuilder()
		val err = ArrayList<String>()
		val asked = ArrayList<Sought>()

		val command =
			SearchCommand(
				running = { sought -> answer(sought).takeIf { running }?.also { asked += sought } },
				otherwise = { sought -> answer(sought).also { asked += sought } },
				out = { out.append(it) },
				err = { err += it },
				choose = chosen,
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
			"report -s -b -\"s\"",
			search("report", "--", "-s", "-b", "-\"s\"").asked.single().text,
		)
		assertEquals(SearchCommand.COUNT, search("report", "--", "-b").asked.single().limit)
		// With no two dashes, short options are not run together: -bi is "not bi", the query's.
		assertEquals("report -bi", search("report", "-bi").asked.single().text)
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
		assertEquals(listOf("search: missing query"), search().err)
		// The prism running is asked first, and whoever made the command only where it has no answer.
		assertEquals(1, search("x").asked.size)
		assertEquals(1, search("x", running = false).asked.size)
	}

	@Test
	fun withTwoDashesWhatIsBeforeThemIsOptionsAloneWhichCanBeRunTogether() {
		val menus = ArrayList<List<String>>()

		// -si is -s and -i; what follows the dashes is the query, dashed words and all.
		val both =
			search("-si", "--", "report", "-draft", "-b", chosen = { paths ->
				menus += paths
				paths[1]
			})

		assertEquals("report -draft -b", both.asked.single().text)
		assertEquals("${held[1]}\n", both.out)
		assertEquals(held.take(SearchCommand.COUNT), menus.single())

		// One that takes a value is the last of those run together, and its value the word after.
		val counted = search("-bn", "3", "--", "report")

		assertEquals("report", counted.asked.single().text)
		assertEquals("${held[0]}\n", counted.out)
		assertEquals(
			held.take(2).joinToString("") { "$it\u0000" },
			search("-0n", "2", "--", "file").out,
		)

		val early = search("-nb", "3", "--", "report")

		assertEquals(Matches.WRONG, early.status)
		assertTrue(
			early.err.single().startsWith(
				"search: -n takes a value, so it must come last in -nb",
			),
		)
		assertEquals(emptyList<Sought>(), early.asked)

		// A dashed word before the dashes that is no option, and no run of them, is a mistake, and nothing is
		// searched for: the query is what follows the dashes.
		for (stray in listOf("-draft", "-x", "--nope", "-ext:pdf", "-5")) {
			val wrong = search("report", stray, "--", "more")

			assertEquals(Matches.WRONG, wrong.status, stray)
			assertTrue(
				wrong.err.single().startsWith(
					"search: unknown option ${stray.substringBefore('=')}",
				),
				"$stray: ${wrong.err}",
			)
			assertEquals(emptyList<Sought>(), wrong.asked, stray)
		}
		// A word with no dash before them is the query's still, before what follows them.
		assertEquals("report more", search("report", "-b", "--", "more").asked.single().text)
	}

	@Test
	fun selectGivesTheOneMatchAndSaysSoWhereThereAreSeveralOrNone() {
		fun only(path: String): (Sought) -> Sifted = { Sifted.Found(1, listOf(path)) }

		val one = search("doc", "-s", answer = only("/home/a/Documents"))

		assertEquals("/home/a/Documents\n", one.out)
		assertEquals(0, one.status)
		assertEquals(emptyList<String>(), one.err)
		// Whether there is one is all it asks: a few, to say which they are where there are more.
		assertEquals(SearchCommand.SHOWN_OF_SEVERAL, one.asked.single().limit)

		val several = search("report", "--select")

		assertEquals(SearchCommand.SEVERAL, several.status)
		assertEquals("", several.out)
		assertTrue(
			several.err.first().startsWith(
				"prism search: 50 matches, but --select needs exactly one",
			),
		)
		assertEquals(
			held.take(SearchCommand.SHOWN_OF_SEVERAL) + "… and 45 more",
			several.err.drop(1),
		)
		// However many are to be printed, it is every match that is counted.
		assertEquals(SearchCommand.SEVERAL, search("report", "-s", "-n", "1").status)

		val two =
			search("x", "-s", answer = { Sifted.Found(2, listOf("/a/one", "/a/two")) })

		assertEquals(listOf("/a/one", "/a/two"), two.err.drop(1))

		val nothing = search("zzz", "-s", answer = { Sifted.Found(0, emptyList()) })

		assertEquals(SearchCommand.NOTHING, nothing.status)
		assertEquals("", nothing.out)
		assertEquals(listOf("prism search: no matches"), nothing.err)
		// The best of several is one match, whatever else is asked.
		assertEquals("${held[0]}\n", search("report", "-s", "-b").out)
		assertEquals("${held[0]}\n", search("report", "-s", "-i", "-b").out)
		assertEquals(
			"/home/a/Documents\u0000",
			search("doc", "-s", "-0", answer = only("/home/a/Documents")).out,
		)
	}

	@Test
	fun selectWithAMenuGivesTheOneMatchWithoutItAndTheOneChosenOfSeveral() {
		val menus = ArrayList<List<String>>()

		// One match is given as it is: there is nothing to choose.
		val one =
			search("doc", "-s", "-i", answer = { Sifted.Found(1, listOf("/home/a/Documents")) })

		assertEquals("/home/a/Documents\n", one.out)
		assertEquals(0, one.status)

		// Several are chosen among, the menu of as many as would be printed.
		val chosen =
			search("report", "-s", "-i", "-n", "5", chosen = { paths ->
				menus += paths
				paths[3]
			})

		assertEquals("${held[3]}\n", chosen.out)
		assertEquals(0, chosen.status)
		assertEquals(held.take(5), menus.single())
		assertEquals(emptyList<String>(), chosen.err)

		val cancelled = search("report", "-s", "-i", chosen = { null })

		assertEquals("", cancelled.out)
		assertEquals(SearchCommand.CANCELLED, cancelled.status)

		val nothing =
			search("zzz", "-si", "--", "zzz", answer = { Sifted.Found(0, emptyList()) })

		assertEquals(SearchCommand.NOTHING, nothing.status)
		assertEquals(listOf("prism search: no matches"), nothing.err)
		// Without --select, a menu of one is still a menu: what -i alone has always been.
		search("doc", "-i", answer = {
			Sifted.Found(
				1,
				listOf("/home/a/Documents"),
			)
		}, chosen = { paths ->
			menus += paths
			paths.single()
		})
		assertEquals(listOf("/home/a/Documents"), menus.last())
	}

	@Test
	fun interactiveGivesTheMatchChosenAndCancelledGivesNothing() {
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

		val cancelled = search("report", "-i", chosen = { null })

		assertEquals("", cancelled.out)
		assertEquals(SearchCommand.CANCELLED, cancelled.status)

		val nowhere =
			search(
				"report",
				"-i",
				chosen = { throw NoTerminal("no terminal to show the menu on") },
			)

		assertEquals(listOf("search: no terminal to show the menu on"), nowhere.err)
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
				Sifted.NoIndex("the running prism has no index"),
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
