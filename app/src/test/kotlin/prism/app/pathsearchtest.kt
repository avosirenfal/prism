package prism.app

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.browser.StaticFinder
import prism.client.Caller
import prism.client.Matches
import prism.client.Reply
import prism.client.Request
import prism.client.Sifted
import prism.client.Sought
import prism.index.Index
import prism.index.IndexFile
import prism.index.Indexer

/**
 * What `prism search` is answered with: by a prism that is running, of the index it keeps, and by the index's file
 * where none is; and what a prism refuses to do for another, which the client then leaves to the Java prism.
 */
class PathSearchTest {
	@TempDir
	lateinit var root: Path

	private fun made(vararg files: String) =
		files.forEach {
			root.resolve(it).also { path -> path.parent.createDirectories() }.writeText(it)
		}

	private fun indexed(): Index =
		Index.EMPTY.with(Indexer(mounts = emptyList()).index(root))

	private fun Sifted.names(): List<String> =
		(this as Sifted.Found).paths.map { it.removePrefix("$root/") }

	/** A prism with [index] and no windows running, as another asks it. */
	private fun instance(index: Index?): Instance =
		Instance(
			Windows(
				Hosting(
					finder =
						index?.let {
							StaticFinder(it, home = root)
						},
				),
				Lasting.UntilStopped,
			),
			"a prism of the tests'",
		)

	private fun asked(instance: Instance, vararg words: String): Reply =
		instance.answer(Request(words.toList(), Caller(root, emptyMap())))

	@Test
	fun aQuerysMatchesAreGivenBestFirstTheOnesAskedFor() {
		made("docs/report.pdf", "docs/q4-report.pdf", "old/myreport.txt", "notes.md")

		val find = StaticFinder(indexed(), home = root)::find
		val all = sifted(Sought("report", 0, 20), find)

		assertEquals(
			listOf("docs/report.pdf", "docs/q4-report.pdf", "old/myreport.txt"),
			all.names(),
		)
		assertEquals(3, (all as Sifted.Found).total)
		// From the second, one: and the count is still of everything found.
		assertEquals(
			Sifted.Found(3, listOf("$root/docs/q4-report.pdf")),
			sifted(Sought("report", 1, 1), find),
		)
		assertEquals(Sifted.Found(3, emptyList()), sifted(Sought("report", 9, 5), find))
		assertEquals(
			3,
			(sifted(Sought("report", 0, Int.MAX_VALUE), find) as Sifted.Found).paths.size,
		)
		assertEquals(
			listOf("docs/report.pdf", "docs/q4-report.pdf"),
			sifted(Sought("report .pdf", 0, 20), find).names(),
		)
		assertEquals(Sifted.Found(0, emptyList()), sifted(Sought("zzz", 0, 20), find))
	}

	@Test
	fun aQueryThatIsWrongIsRefusedWithWhatIsWrongWithIt() {
		val find = StaticFinder(indexed(), home = root)::find

		val wrong =
			assertInstanceOf(
				Sifted.Wrong::class.java,
				sifted(Sought("report foo:bar", 0, 20), find),
			)

		assertTrue(
			wrong.problems.single().startsWith("Unknown modifier “foo:”"),
			wrong.problems.single(),
		)
		assertTrue(wrong.problems.single().endsWith("(“foo”)"), wrong.problems.single())
		assertInstanceOf(Sifted.Wrong::class.java, sifted(Sought("ext:", 0, 20), find))
		// An alias nobody defined cannot be searched for.
		assertInstanceOf(Sifted.Wrong::class.java, sifted(Sought("~nosuchalias/", 0, 20), find))
	}

	@Test
	fun theIndexsFileIsReadWhereNoPrismIsAskedAndSaidToBeMissingWhereItIs() {
		made("docs/report.pdf")

		val file = root.resolve("cache/index.prism")

		assertTrue(
			(IndexFileSeeker({ file }, root).seek(Sought("report", 0, 20)) as Sifted.NoIndex)
				.why
				.startsWith("no index at $file"),
		)

		IndexFile.write(file, indexed())
		assertEquals(
			listOf("docs/report.pdf"),
			IndexFileSeeker({ file }, root).seek(Sought("report", 0, 20)).names(),
		)

		file.writeText("not an index")
		assertInstanceOf(
			Sifted.NoIndex::class.java,
			IndexFileSeeker({ file }, root).seek(Sought("report", 0, 20)),
		)
	}

	@Test
	fun aPrismThatIsRunningAnswersFromItsIndexAndOneWithoutSaysItHasNone() {
		made("docs/report.pdf", "docs/report-final.md")

		val answer =
			assertInstanceOf(
				Reply.Done::class.java,
				asked(instance(indexed()), *Matches.words(Sought("report", 0, 1)).toTypedArray()),
			)

		assertEquals(Sifted.Found(2, listOf("$root/docs/report.pdf")), Matches.read(answer.ran))

		val wrong =
			asked(
				instance(indexed()),
				*Matches.words(Sought("foo:bar", 0, 5)).toTypedArray(),
			) as Reply.Done

		assertEquals(Matches.WRONG, wrong.ran.status)
		assertInstanceOf(Sifted.Wrong::class.java, Matches.read(wrong.ran))

		val none =
			asked(
				instance(null),
				*Matches.words(Sought("report", 0, 5)).toTypedArray(),
			) as Reply.Done

		assertEquals(
			Sifted.NoIndex("the running prism has no index"),
			Matches.read(none.ran),
		)
	}

	@Test
	fun whatAsksForAPrismOfItsOwnIsNotDoneForAnother() {
		val instance = instance(null)

		for (words in listOf(
			listOf("explore", "--isolated"),
			listOf("--isolated", "/tmp"),
			listOf("explore", "--theme", "dark"),
			listOf("explore", "--index"),
			listOf("launcher", "--isolated"),
			listOf("launcher", "--theme", "light"),
		)) {
			val reply =
				assertInstanceOf(Reply.Unable::class.java, asked(instance, *words.toTypedArray()))

			assertTrue(reply.why.endsWith("was asked to run in a separate process"), reply.why)
		}
		// What no prism does for another, and words it reads as no command of its own.
		assertInstanceOf(Reply.Unable::class.java, asked(instance, "index", "--fresh"))
		assertInstanceOf(Reply.Unable::class.java, asked(instance, "search", "report"))
		assertInstanceOf(Reply.Unable::class.java, asked(instance, "--help"))
		assertInstanceOf(
			Reply.Unable::class.java,
			asked(instance, "explore", "--no-such-option"),
		)
	}
}
