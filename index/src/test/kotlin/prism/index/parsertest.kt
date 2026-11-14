package prism.index

import java.time.DayOfWeek
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.index.query.BodySyntax
import prism.index.query.Case
import prism.index.query.Depth
import prism.index.query.Flag
import prism.index.query.Group
import prism.index.query.ModifierSyntax
import prism.index.query.Problem
import prism.index.query.Query
import prism.index.query.QueryBuilder
import prism.index.query.QueryGrammar
import prism.index.query.QueryText
import prism.index.query.RegexOn
import prism.index.query.Since
import prism.index.query.Span
import prism.index.query.ValueSyntax
import prism.index.query.query

/**
 * The search language's text, read: every example of it reads to the query the DSL builds for it; every text it can
 * be on the way to being typed reads without a term being thrown out; text that is wrong is said to be, where it is,
 * and the rest of the query is still searched for; and each part of the text is marked with what it is.
 */
class ParserTest {
	/** Every example of docs/search.md, and the query each is. */
	private val examples: List<Pair<String, QueryBuilder.() -> Unit>> =
		listOf(
			"report" to { +word("report") },
			"notes" to { +word("notes") },
			"report.pdf" to { +word("report.pdf") },
			".git" to { +word(".git") },
			"^report" to { +word("report", start = true) },
			"report$" to { +word("report", end = true) },
			"report.pdf$" to { +word("report.pdf", end = true) },
			"=thing" to { +exactly("thing") },
			"=thing.zip" to { +exactly("thing.zip") },
			"=.bashrc" to { +exactly(".bashrc") },
			"archive" to { +word("archive") },
			"*.jpg" to { +word("*.jpg") },
			"*.{jpg,png}" to { +word("*.{jpg,png}") },
			"img_????" to { +word("img_????") },
			"img_[0-9]*" to { +word("img_[0-9]*") },
			"\\[draft\\]*" to { +word("\\[draft\\]*") },
			"\"tax return\"" to { +word("tax return") },
			"\"tax *.pdf\"" to { +word("tax *.pdf") },
			"c\"README\"" to { +word("README", case = Case.Sensitive) },
			"i\"readme\"" to { +word("readme", case = Case.Insensitive) },
			"r\"img_*\"" to { +raw("img_*") },
			"rc\"Img_*\"" to { +raw("Img_*", Case.Sensitive) },
			"cr\"Img_*\"" to { +raw("Img_*", Case.Sensitive) },
			"=r\"!weird*name\"" to { +raw("!weird*name", start = true, end = true) },
			"r\"!x\"$" to { +raw("!x", end = true) },
			"r#\"say \"hi\"\"#" to { +raw("say \"hi\"") },
			"\"-notes\"" to { +word("-notes") },
			"funny/*.jpg" to { +path("funny/*.jpg") },
			"funny/** *.jpg" to {
				+path("funny/**")
				+word("*.jpg")
			},
			"funny/**.jpg" to { +path("funny/**.jpg") },
			"funny/rep" to { +path("funny/rep") },
			"funny/* =report" to {
				+path("funny/*")
				+exactly("report")
			},
			"work/2024/" to { +path("work/2024/") },
			"/mnt/photos/" to { +path("/mnt/photos/") },
			"/home/*/Documents/*.pdf" to { +path("/home/*/Documents/*.pdf") },
			"~/Downloads/" to { +path("~/Downloads/") },
			"~/Downloads/inv" to { +path("~/Downloads/inv") },
			"~reports/ *.pdf" to {
				+path("~reports/")
				+word("*.pdf")
			},
			"r/^img_\\d{4}\\.jpe?g$/" to { +regex("^img_\\d{4}\\.jpe?g$") },
			"r/^img_\\d{4}$/s" to { +regex("^img_\\d{4}$", RegexOn.Stem) },
			"r/photos\\/20\\d\\d\\//p" to { +regex("photos\\/20\\d\\d\\/", RegexOn.Path) },
			"r/README/c" to { +regex("README", case = Case.Sensitive) },
			"r/readme/i" to { +regex("readme", case = Case.Insensitive) },
			"r/readme/pc" to { +regex("readme", RegexOn.Path, Case.Sensitive) },
			"dir:funny *.jpg" to {
				+dir("funny")
				+word("*.jpg")
			},
			"dir:funny:1 type:image" to {
				+dir("funny", Depth.exactly(1))
				+type(Group.Image)
			},
			"dir:funny:3-5 *.jpg" to {
				+dir("funny", Depth.between(3, 5))
				+word("*.jpg")
			},
			"dir:funny:2- is:dir" to {
				+dir("funny", Depth.atLeast(2))
				+isA(Flag.Dir)
			},
			"dir:work dir:2024" to {
				+dir("work")
				+dir("2024")
			},
			"dir:work/2024" to { +dir("work/2024") },
			"dir:{a,b}" to { +dir("{a,b}") },
			"dir:\"old photos\"" to { +dir("old photos") },
			"dir:~proj ^k" to {
				+dir("~proj")
				+word("k", start = true)
			},
			"ext:pdf" to { +ext("pdf") },
			"ext:gz" to { +ext("gz") },
			"ext:tar.gz" to { +ext("tar.gz") },
			"ext:{jpg,png,webp}" to { +ext("{jpg,png,webp}") },
			"ext:/jpe?g/i" to { +extRegex("jpe?g", Case.Insensitive) },
			"type:image" to { +type(Group.Image) },
			"type:image -ext:jpg" to {
				+type(Group.Image)
				-ext("jpg")
			},
			"is:file" to { +isA(Flag.File) },
			"is:file -is:symlink" to {
				+isA(Flag.File)
				-isA(Flag.Symlink)
			},
			"is:dir is:symlink" to {
				+isA(Flag.Dir)
				+isA(Flag.Symlink)
			},
			"is:symlink" to { +isA(Flag.Symlink) },
			"is:{file,symlink}" to { +isA(Flag.File, Flag.Symlink) },
			"/srv/data/ -is:symlink" to {
				+path("/srv/data/")
				-isA(Flag.Symlink)
			},
			"recent:today" to { +recent(Since.Today) },
			"recent:yesterday" to { +recent(Since.Yesterday) },
			"recent:wednesday" to { +recent(Since.Weekday(DayOfWeek.WEDNESDAY)) },
			"recent:5m" to { +recent(Since.Within(5.minutes)) },
			"recent:24h" to { +recent(Since.Within(24.hours)) },
			"new:today" to { +new(Since.Today) },
			"new:2d" to { +new(Since.Within(2.days)) },
			"recent:1w ext:pdf" to {
				+recent(Since.Within(7.days))
				+ext("pdf")
			},
			"dir:funny recent:1w" to {
				+dir("funny")
				+recent(Since.Within(7.days))
			},
			"mount:zfs" to { +mount("zfs") },
			"-mount:ntfs *.mkv" to {
				-mount("ntfs")
				+word("*.mkv")
			},
			"ext:jpg ext:png" to {
				+ext("jpg")
				+ext("png")
			},
			"-ext:jpg -ext:png" to {
				-ext("jpg")
				-ext("png")
			},
		)

	private fun read(text: String, aliases: Set<String>? = null) =
		QueryText.read(text, aliases)

	/** Each problem as its kind, the text it is about, and its message. */
	private fun problems(text: String, aliases: Set<String>? = null): List<String> =
		read(text, aliases).problems.map {
			"${it.kind} ${text.substring(it.span.start, it.span.end)}"
		}

	@Test
	fun everyExampleReadsToTheQueryTheDslBuilds() {
		for ((text, build) in examples) {
			val read = read(text)

			assertEquals(emptyList<Problem>(), read.problems, text)
			assertEquals(query(build), read.query, text)
		}
	}

	@Test
	fun everyTextOnTheWayToAnExampleReadsWithNothingInvalid() {
		// Typed a character at a time, every example is, at every moment, a query with nothing in it thrown out: what
		// is not finished yet is finished as well as it can be, so there are results to show as it is typed.
		for ((example) in examples) {
			for (typed in 0 until example.length) {
				val text = example.substring(0, typed)
				val read = read(text)

				assertEquals(
					emptyList<String>(),
					read.problems
						.filter {
							it.kind == Problem.Kind.Invalid
						}.map { "${it.message} at ${it.span}" },
					"“$text”, on the way to “$example”",
				)
				for (span in read.problems.map { it.span } + read.marks.map { it.span })
					assertTrue(span.end <= text.length, "“$text”: $span")
				read.marks.zipWithNext().forEach { (one, next) ->
					assertTrue(one.span.end <= next.span.start, "“$text”: $one overlaps $next")
				}
			}
		}
	}

	@Test
	fun whatIsUnfinishedAtTheEndIsFinishedAndSearchedFor() {
		// "tax re: the quote runs to the end, and is searched for as typed so far.
		assertEquals(query { +word("tax re") }, read("\"tax re").query)
		assertEquals(listOf("Unfinished \"tax re"), problems("\"tax re"))
		// img_[0-9: the class is closed at the end.
		assertEquals(query { +word("img_[0-9]") }, read("img_[0-9").query)
		assertEquals(listOf("Unfinished [0-9"), problems("img_[0-9"))
		// *.{jpg,pn: so is the choice.
		assertEquals(query { +word("*.{jpg,pn}") }, read("*.{jpg,pn").query)
		// r/^img_\d: a regex not closed yet is searched for as far as it goes.
		assertEquals(query { +regex("^img_\\d") }, read("r/^img_\\d").query)
		// report ext: and is:{file,sym: what has nothing to search for yet is left out, and the rest searched for.
		assertEquals(query { +word("report") }, read("report ext:").query)
		assertEquals(listOf("Unfinished ext:"), problems("report ext:"))
		assertEquals(query { +isA(Flag.File) }, read("is:{file,sym").query)
		assertEquals(query { +word("report") }, read("report type:ima").query)
		assertEquals(query { +word("report") }, read("report recent:5").query)
		assertEquals(listOf("Unfinished 5"), problems("report recent:5"))
		assertEquals(query { +word("report") }, read("report -").query)
	}

	@Test
	fun whatIsInvalidIsSaidWhereItIsAndLeftOut() {
		// The same, with more typed after it: no longer unfinished, so wrong.
		assertEquals(query { +word("report") }, read("img_[0-9 report").query)
		assertEquals(listOf("Invalid [0-9"), problems("img_[0-9 report"))
		assertEquals(listOf("Invalid ext:"), problems("ext: report"))
		assertEquals(listOf("Invalid -"), problems("- report"))
		assertEquals(listOf("Invalid imag"), problems("type:imag report"))

		// Wrong however it ends.
		assertEquals(listOf("Invalid c", "Invalid i"), problems("ic\"readme\"").sorted())
		assertEquals(query { +word("report") }, read("ic\"readme\" report").query)
		assertEquals(listOf("Invalid foo"), problems("foo:bar report"))
		assertEquals(query { +word("report") }, read("foo:bar report").query)
		assertEquals(listOf("Invalid def"), problems("\"abc\"def"))
		assertEquals(listOf("Invalid :5-3"), problems("dir:funny:5-3"))
		assertEquals(listOf("Invalid :0"), problems("dir:funny:0"))
		assertEquals(listOf("Invalid nope"), problems("is:{file,nope}"))
		assertEquals(listOf("Invalid 0m"), problems("recent:0m"))
		assertEquals(listOf("Invalid ("), problems("r/(/"))
		assertEquals(listOf("Invalid p", "Invalid s"), problems("r/x/sp").sorted())
		assertEquals(listOf("Invalid #"), problems("c#\"x\"#"))
		assertEquals(listOf("Invalid \"\""), problems("\"\""))
		assertEquals(listOf("Invalid x"), problems("ext:/jpe?g/x"))

		// An alias is only checked where the aliases are known.
		assertEquals(listOf("Invalid ~nope"), problems("~nope/ x", setOf("reports")))
		assertEquals(emptyList<String>(), problems("~nope/ x"))
		assertEquals(query { +word("x") }, read("~nope/ x", setOf("reports")).query)
	}

	@Test
	fun anyTextAtAllReadsAndSearches() {
		// Text made at random from the characters the language gives meaning to: none of it may fail to read, or make a
		// query that fails to run.
		val alphabet = "\"\\/[]{},*?^$=-:#~!.rcisp aZ09dirext:type:is:recent:new:mount:"
		val random = kotlin.random.Random(System.getProperty("parser.seed")?.toIntOrNull() ?: 7)

		val context =
			SearchContext(
				aliases =
					mapOf(
						"a" to
							listOf(
								java.nio.file.Path
									.of("/tmp"),
							),
					),
			)

		repeat(System.getProperty("parser.texts")?.toIntOrNull() ?: 30_000) {
			val text =
				String(CharArray(random.nextInt(24)) { alphabet[random.nextInt(alphabet.length)] })

			val read =
				try {
					read(text, setOf("a"))
				} catch (failed: Exception) {
					throw AssertionError("“$text” did not read", failed)
				}

			for (span in read.problems.map { it.span } + read.marks.map { it.span })
				assertTrue(span.end <= text.length, "“$text”: $span")
			try {
				Index.EMPTY.search(read.query, context)
			} catch (failed: Exception) {
				throw AssertionError("“$text” read to ${read.query}, which did not run", failed)
			}
		}
	}

	@Test
	fun everyModifiersNameReadsAsThatModifierWithTheValueItTakesAndNoOtherNameDoes() {
		fun said(text: String) =
			(
				QueryGrammar
					.read(text)
					.terms
					.single()
					.body as BodySyntax.Modifier
			).said

		for (name in ModifierSyntax.NAMES) {
			assertFalse(said("$name:x") is ModifierSyntax.Unknown, name)
			assertFalse(said("${name.uppercase()}:x") is ModifierSyntax.Unknown, name)
			// Nothing after the colon yet: the same modifier, with no value.
			assertEquals(null, said("$name:").value, name)
		}
		assertTrue(said("dir:photos:2") is ModifierSyntax.Dir)
		assertTrue(said("ext:/jpe?g/").value is ValueSyntax.Regex)
		assertTrue(said("type:/jpe?g/").value is ValueSyntax.Words)
		assertTrue(said("colour:red") is ModifierSyntax.Unknown)
	}

	@Test
	fun aDotBeforeAnExtensionIsItsBoundary() {
		assertEquals(query { +ext("pdf") }, read("ext:.pdf").query)
	}

	@Test
	fun everyPartOfTheTextIsMarkedWithWhatItIs() {
		val text = "-ext:{jpg,png} c\"tax *\"$ ~/Downloads/ dir:a:2 r/x\\d/i"
		val marks =
			read(text).marks.map {
				"${it.role}:${text.substring(it.span.start, it.span.end)}"
			}

		assertEquals(
			listOf(
				"Negation:-",
				"Modifier:ext",
				"Colon::",
				"Wildcard:{",
				"Value:jpg",
				"Wildcard:,",
				"Value:png",
				"Wildcard:}",
				"Flag:c",
				"Quote:\"",
				"Word:tax ",
				"Wildcard:*",
				"Quote:\"",
				"Anchor:$",
				"Place:~",
				"Slash:/",
				"Word:Downloads",
				"Slash:/",
				"Modifier:dir",
				"Colon::",
				"Value:a",
				"Depth::2",
				"RegexDelimiter:r/",
				"Regex:x\\d",
				"RegexDelimiter:/",
				"Flag:i",
			),
			marks,
		)
	}

	@Test
	fun theTreeKeepsWhereEveryPartIs() {
		val read = read("-dir:\"old photos\":2 report")
		val first = read.syntax.terms.first()

		assertEquals(Span(0, 19), first.span)
		assertEquals(Span(0, 1), first.negation)
		assertEquals(Span(20, 26), read.syntax.terms[1].span)
		assertEquals(
			Query(listOf(read.terms[0].searched!!, read.terms[1].searched!!)),
			read.query,
		)
		assertEquals(
			query {
				-dir("old photos", Depth.exactly(2))
				+word("report")
			},
			read.query,
		)
	}

	@Test
	fun everyExampleTheHelpGivesReadsWithNothingWrongAndAsksForSomething() {
		for (example in prism.index.query.QueryHelp.EXAMPLES) {
			val read = QueryText.read(example.text)

			assertEquals(emptyList<Any>(), read.problems, example.text)
			assertTrue(read.query.terms.isNotEmpty(), example.text)
		}
	}
}
