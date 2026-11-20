package prism.app

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.browser.IndexSource
import prism.browser.Search
import prism.cli.Args
import prism.cli.Command
import prism.cli.Invocation
import prism.cli.Outcome
import prism.cli.invocation
import prism.index.Index
import prism.index.IndexFile
import prism.index.Indexer

class IndexingTest {
	@TempDir
	lateinit var root: Path

	/** A command that takes `--index`, as the explorer's does. */
	private object Searching : Command(
		"searching",
		"a command of the tests' that searches an index",
	) {
		val indexing = Indexing(this)

		override fun run(args: Args): Outcome = Outcome("")
	}

	private fun args(vararg words: String) =
		(invocation(Searching, words.toList()) as Invocation.Run).args

	/** An index of a folder with one file in it, written to [file]. */
	private fun written(file: Path) {
		root
			.resolve("tree")
			.createDirectories()
			.resolve("a.txt")
			.writeText("a")
		IndexFile.write(
			file,
			Index.EMPTY.with(Indexer(mounts = emptyList()).index(root.resolve("tree"))),
		)
	}

	@Test
	fun anIsolatedPrismReadsTheIndexsOwnFile_AndOneThatIsNotDoesOnlyWhenAsked() {
		val usual = root.resolve("index.prism")

		written(usual)

		val isolated = Searching.indexing.searchOf(args(), root, isolated = true, usual = usual)
		val source = isolated.source as IndexSource.File

		assertEquals(usual, source.file)
		assertEquals(true, source.isolated)
		assertEquals(2L, source.entries)
		assertNotNull(isolated.finder)
		assertInstanceOf(Search.Ready::class.java, isolated.search)
		// Its finder searches the file as it was: it is not one to rebuild.
		assertEquals(false, isolated.finder?.rebuilds)

		// Not isolated, and not asked to: no index, and the file it would be read from named.
		val alone = Searching.indexing.searchOf(args(), root, isolated = false, usual = usual)

		assertNull(alone.finder)
		assertEquals(usual, (alone.source as IndexSource.None).file)
		// Asked to, it reads it.
		assertInstanceOf(
			IndexSource.File::class.java,
			Searching.indexing.searchOf(args("--index"), root, usual = usual).source,
		)
	}

	@Test
	fun anIsolatedPrismWithNoIndexFileSaysSoAndWhereItLooked() {
		val usual = root.resolve("missing.prism")
		val read = Searching.indexing.searchOf(args(), root, isolated = true, usual = usual)
		val none = read.source as IndexSource.None

		assertNull(read.finder)
		assertEquals(usual, none.file)
		assertEquals(true, none.isolated)
	}
}
