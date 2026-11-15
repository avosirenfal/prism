package prism.index

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import prism.index.query.AliasName
import prism.index.query.AliasProblem
import prism.index.query.Aliases
import prism.index.query.BodySyntax
import prism.index.query.Problem
import prism.index.query.QueryGrammar
import prism.index.query.QueryText
import prism.index.query.Role
import prism.index.query.Test.All
import prism.linux.Dev
import prism.linux.Fd
import prism.linux.KernelDev
import prism.linux.Mount
import prism.linux.orThrow
import prism.linux.statx

/**
 * Aliases (`name@`): what is one as written, what each stands for, and what a query that uses them finds in a tree
 * made here and indexed by the real indexer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AliasesTest {
	private lateinit var root: Path
	private lateinit var index: Index

	private val files =
		listOf(
			"projects/prism/build.kts",
			"projects/prism/src/main.kt",
			"projects/other/readme.md",
			"Photos/a.raw",
			"Photos/2024/b.raw",
			"Photos/2024/c.jpg",
			"elsewhere/d.raw",
			"elsewhere/prism/e.txt",
			"tax report.pdf",
			"tax draft.pdf",
			"report.pdf",
		)

	@BeforeAll
	fun made(
		@TempDir folder: Path,
	) {
		root = folder
		for (file in files)
			root.resolve(file).also { it.parent.createDirectories() }.writeText(file)

		val dev = statx(Fd.CWD, root.toString()).orThrow().dev

		val indexer =
			Indexer(
				mounts =
					listOf(
						Mount(1, KernelDev(Dev(dev.major, dev.minor)), "/", root, "ext4", "disk"),
					),
			)

		index = Index.EMPTY.with(indexer.index(root))
	}

	/** The user's own two, and a few that try the edges. */
	private val aliases by lazy {
		Aliases
			.of(
				listOf(
					"projects" to "$root/projects/",
					"raws" to "$root/Photos/ ext:raw",
					"raws2024" to "raws@ 2024/",
					"tax" to "\"tax",
					"final" to "-draft",
				),
			).also { read -> assertTrue(read.problems.all { it == null }) { "${read.problems}" } }
			.aliases
	}

	private fun found(text: String, aliases: Aliases = this.aliases): Set<String> {
		val read = QueryText.read(text, aliases)

		assertEquals(
			emptyList<Problem>(),
			read.problems.filter {
				it.kind ==
					Problem.Kind.Invalid
			},
		)

		return when (val searched = index.search(read.query)) {
			is Searched.Found ->
				searched.results.map { it.where.toString().removePrefix("$root/") }.toSet()
			is Searched.Refused -> error("refused: ${searched.why}")
		}
	}

	private fun body(text: String) =
		QueryGrammar
			.read(text)
			.terms
			.single()
			.body

	// ---- what is one, as written -------------------------------------------------------------------------------------

	@Test
	fun aNameAndAnAtAloneInATermIsAnAlias() {
		assertEquals("projects", (body("projects@") as BodySyntax.Alias).name.value)
		assertEquals("Raws2024", (body("Raws2024@") as BodySyntax.Alias).name.value)
		assertEquals("7", (body("7@") as BodySyntax.Alias).name.value)

		val negated = QueryGrammar.read("-raws@").terms.single()

		assertNotNull(negated.negation)
		assertTrue(negated.body is BodySyntax.Alias)

		// One among other terms, wherever it is.
		assertEquals(
			listOf(true, false, true),
			QueryGrammar.read("a@ prism/ b@").terms.map { it.body is BodySyntax.Alias },
		)
	}

	@Test
	fun anythingElseWithAnAtInItIsWords() {
		for (text in listOf(
			"me@example.com",
			"foo@bar",
			"@",
			"@home",
			"a_b@",
			"a.b@",
			"über@",
			"\"foo@\"",
			"r\"foo@\"",
			"foo@@",
			"foo@$",
			"^foo@",
			"=foo@",
		))
			assertTrue(body(text) is BodySyntax.Words) { text }

		// A modifier's value is its own.
		assertTrue(body("ext:raw@") is BodySyntax.Modifier)
	}

	@Test
	fun anAliasIsMarkedAsOneWhole() {
		val marks = QueryText.read("-raws@ x", aliases).marks

		assertEquals(listOf(Role.Negation, Role.Alias, Role.Word), marks.map { it.role })
		assertEquals(1 to 6, marks[1].span.let { it.start to it.end })
	}

	// ---- the aliases there are -------------------------------------------------------------------------------------

	@Test
	fun aNameIsLettersAndDigitsAndOneWhateverItsCase() {
		assertNotNull(AliasName.of("projects"))
		assertNotNull(AliasName.of("P2"))
		for (wrong in listOf("", "a b", "a-b", "a@", "a.b", "é"))
			assertNull(AliasName.of(wrong)) { wrong }

		assertEquals(AliasName.of("Raws"), AliasName.of("raws"))
		assertEquals("Raws@", AliasName.of("Raws")?.written)

		// Used in whatever case.
		assertEquals(found("raws@"), found("RAWS@"))
	}

	@Test
	fun whatIsWrittenWrongIsNotAnAliasAndSaysWhy() {
		val read =
			Aliases.of(
				listOf(
					"good" to "report",
					"" to "x",
					"not ok" to "x",
					"Good" to "y",
					"empty" to "  ",
					"unknown" to "nosuch@ x",
					"modifier" to "foo:bar",
					"unfinished" to "ext:",
					"uses" to "good@ pdf",
				),
			)

		assertEquals(listOf("good", "uses"), read.aliases.all.map { it.name.text })
		assertNull(read.problems[0])
		assertEquals(AliasProblem.Unnamed, read.problems[1])
		assertEquals(AliasProblem.Misnamed, read.problems[2])
		assertEquals("“good@” is already defined", read.problems[3]?.message)
		assertEquals(AliasProblem.Empty, read.problems[4])
		assertEquals("Unknown alias “nosuch@”", read.problems[5]?.message)
		assertTrue(
			read.problems[6]
				?.message
				.orEmpty()
				.startsWith("Unknown modifier"),
		)
		assertEquals("“ext:” needs a value", read.problems[7]?.message)
		assertNull(read.problems[8])
	}

	@Test
	fun anAliasThatComesBackToItselfIsNotOne() {
		val read =
			Aliases.of(
				listOf(
					"self" to "x self@",
					"a" to "b@",
					"b" to "c@ y",
					"c" to "a@",
					"outside" to "a@ z",
					"fine" to "z",
				),
			)

		assertEquals(listOf("fine"), read.aliases.all.map { it.name.text })
		assertEquals("An alias can't use itself", read.problems[0]?.message)
		assertEquals("“b@” leads back to this alias", read.problems[1]?.message)
		assertEquals("“c@” leads back to this alias", read.problems[2]?.message)
		assertEquals("“a@” leads back to this alias", read.problems[3]?.message)
		assertEquals("“a@” leads back to this alias", read.problems[4]?.message)
		assertNull(read.problems[5])

		// What is wrong is where the alias is used.
		val wrong = (read.problems[0] as AliasProblem.Wrong).problem

		assertEquals("self@", "x self@".substring(wrong.span.start, wrong.span.end))
	}

	@Test
	fun aDefinitionLeftUnfinishedIsSearchedForButIsNotFitToKeep() {
		val written = listOf("tax" to "\"tax", "ok" to "report")
		val read = Aliases.of(written)

		assertEquals(listOf("tax", "ok"), read.aliases.all.map { it.name.text })
		assertEquals("Missing closing quote", read.unfit(0, "tax")?.message)
		assertNull(read.unfit(1, "ok"))
		assertTrue(!read.fit(written))
		assertTrue(read.fit(written.drop(1)))
	}

	@Test
	fun thoseANameBeingTypedCouldBe() {
		assertEquals(listOf("raws", "raws2024"), aliases.beginning("ra").map { it.name.text })
		assertEquals(listOf("raws", "raws2024"), aliases.beginning("RAWS").map { it.name.text })
		assertEquals(listOf("projects"), aliases.beginning("p").map { it.name.text })
		assertEquals(emptyList<String>(), aliases.beginning("").map { it.name.text })
		assertEquals(emptyList<String>(), aliases.beginning("x").map { it.name.text })
		assertEquals(emptyList<String>(), aliases.beginning("ra/").map { it.name.text })
	}

	// ---- what a query that uses them finds --------------------------------------------------------------------------

	@Test
	fun anAliasStandsForItsQueryAmongTheOtherTerms() {
		// The user's own two.
		assertEquals(
			setOf("projects/prism/build.kts", "projects/prism/src", "projects/prism/src/main.kt"),
			found("projects@ prism/"),
		)
		assertEquals(setOf("Photos/a.raw", "Photos/2024/b.raw"), found("raws@"))
		assertEquals(setOf("Photos/2024/b.raw"), found("raws@ 2024/"))
		// One that uses another.
		assertEquals(setOf("Photos/2024/b.raw"), found("raws2024@"))
		// And what it stands for is terms like any others.
		assertEquals(
			QueryText.read("$root/Photos/ ext:raw b").query,
			QueryText.read("raws@ b", aliases).query,
		)
	}

	@Test
	fun anAliasNegatedExcludesWhatItFinds() {
		// Not (under Photos and a raw): the raws elsewhere.
		assertEquals(setOf("elsewhere/d.raw"), found("-raws@ ext:raw"))
		// Of several terms it stays a group, since no one term says it.
		assertTrue(
			QueryText
				.read("-raws@", aliases)
				.query.terms
				.single()
				.test is All,
		)
		// Of one term it is that term the other way about.
		assertEquals(
			QueryText.read("-$root/projects/ prism").query,
			QueryText.read("-projects@ prism", aliases).query,
		)
		assertEquals(setOf("elsewhere/prism/e.txt"), found("-projects@ prism/"))
		assertEquals(QueryText.read("draft").query, QueryText.read("-final@", aliases).query)
	}

	@Test
	fun nothingInAnAliasesTextReachesTheQueryAroundIt() {
		// A quote left open in the definition closes there: `report` is a term of its own, not more of the phrase.
		assertEquals(
			QueryText.read("\"tax\" report").query,
			QueryText.read("tax@ report", aliases).query,
		)
		assertEquals(setOf("tax report.pdf"), found("tax@ report"))
		// A `-` at its start negates its own term alone.
		assertEquals(setOf("tax report.pdf"), found("tax@ final@"))
	}

	@Test
	fun anAliasThereIsNoneOfIsAProblemAndIsLeftOut() {
		val read = QueryText.read("nosuch@ report", aliases)
		val problem = read.problems.single()

		assertEquals(Problem.Kind.Invalid, problem.kind)
		assertEquals("Unknown alias “nosuch@”", problem.message)
		assertEquals(0 to 7, problem.span.start to problem.span.end)
		assertEquals(QueryText.read("report").query, read.query)

		// With no aliases at all, every one is unknown.
		assertEquals(1, QueryText.read("raws@").problems.size)
	}

	@Test
	fun oneMoreAliasIsFitOrNotByTheRuleEveryAliasWrittenIsHeldTo() {
		val written = listOf("raws" to "ext:raw", "docs" to "ext:pdf")

		assertNull(Aliases.unfitAdded(written, "reports", "report docs@"))
		assertEquals(AliasProblem.Unnamed, Aliases.unfitAdded(written, "", "report"))
		assertEquals(AliasProblem.Misnamed, Aliases.unfitAdded(written, "my docs", "report"))
		// A name one of them has, whatever its case.
		assertEquals(
			"“raws@” is already defined",
			Aliases.unfitAdded(written, "RAWS", "report")?.message,
		)
		assertEquals(AliasProblem.Empty, Aliases.unfitAdded(written, "nothing", "  "))
		assertEquals(
			"Unknown alias “nosuch@”",
			Aliases.unfitAdded(written, "bad", "nosuch@ x")?.message,
		)
		// One left unfinished is searched for, but is no way to leave a definition.
		assertTrue(Aliases.unfitAdded(written, "open", "\"tax ret") is AliasProblem.Wrong)

		// What was written is what the aliases made of it say they were written as.
		assertEquals(written, Aliases.of(written).aliases.written)
	}

	@Test
	fun searchesMadeWithinEachOtherAreWrittenAsOneWhereThatMeansTheSame() {
		assertEquals("report .pdf", QueryText.joined(listOf("report", ".pdf")))
		assertEquals(
			"\"tax return\" -draft r/^inv\\d+/ docs/ ext:pdf",
			QueryText.joined(listOf(" \"tax return\" -draft ", "r/^inv\\d+/", "docs/ ext:pdf")),
		)
		assertEquals("raws@ 2024/", QueryText.joined(listOf("raws@", "", "2024/")))
		assertEquals("report", QueryText.joined(listOf("report")))

		// As one, they read as the terms they read as apart.
		assertEquals(
			QueryText.read("report").query.terms + QueryText.read(".pdf").query.terms,
			QueryText.read(QueryText.joined(listOf("report", ".pdf"))!!).query.terms,
		)

		// A quote left open would take what is written after it for its own: there is no writing those as one.
		assertNull(QueryText.joined(listOf("\"tax ret", ".pdf")))
		assertNotNull(QueryText.unfinished("\"tax ret"))
		assertNull(QueryText.unfinished("\"tax ret\""))
		// Left open in the last of them it takes nothing, but is still unfinished.
		assertNull(QueryText.joined(listOf(".pdf", "\"tax ret")))
	}
}
