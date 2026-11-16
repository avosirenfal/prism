package prism.index

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import prism.index.query.Aliases
import prism.index.query.Flag
import prism.index.query.Problem
import prism.index.query.Query
import prism.index.query.QueryText
import prism.index.query.Term
import prism.index.query.Test.Folders
import prism.index.query.Test.Is
import prism.linux.Fd
import prism.linux.orThrow
import prism.linux.statx
import prism.platform.Dev
import prism.platform.KernelDev
import prism.platform.Mount

/**
 * What a query of folder terms finds ([Query.aimed]): the folders themselves where nothing is asked of an entry, and
 * what is in them where something is. Each of the user's own examples, as they wrote it, in a tree made here and
 * indexed by the real indexer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FoldersTest {
	private lateinit var root: Path
	private lateinit var index: Index

	private val files =
		listOf(
			"funny/a.jpg",
			"funny/b.png",
			"funny/notes.txt",
			"funny/sub/c.jpg",
			"funny/funny/d.jpg",
			"funny-videos/v.mp4",
			"x/funny/e.jpg",
			"x/sub/f.txt",
			// A file of the name is no folder of it.
			"other/funny",
			"nsight_a/1.txt",
			"my_nsight/2.txt",
			"the_nsight_sdk/3.txt",
			"projects/prism/main.kt",
			"projects/prism/build/out.bin",
			"elsewhere/prism/x.txt",
			"draft/funny/g.jpg",
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

	private val aliases by lazy {
		Aliases
			.of(listOf("projects" to "$root/projects/", "fun" to "funny/"))
			.also { read -> assertTrue(read.problems.all { it == null }) { "${read.problems}" } }
			.aliases
	}

	private fun read(text: String): Query {
		val read = QueryText.read(text, aliases)

		assertEquals(
			emptyList<Problem>(),
			read.problems.filter {
				it.kind ==
					Problem.Kind.Invalid
			},
		)

		return read.query
	}

	private fun found(query: Query): Set<String> =
		when (val searched = index.search(query, SearchContext(home = root))) {
			is Searched.Found ->
				searched.results.map { it.where.toString().removePrefix("$root/") }.toSet()
			is Searched.Refused -> error("refused: ${searched.why}")
		}

	private fun found(text: String): Set<String> = found(read(text))

	/** What [text] finds, the best first. */
	private fun ranked(text: String): List<String> =
		when (val searched = index.search(read(text), SearchContext(home = root))) {
			is Searched.Found ->
				searched.results.map {
					it.where.toString().removePrefix(
						"$root/",
					)
				}
			is Searched.Refused -> error("refused: ${searched.why}")
		}

	@Test
	fun ofFoldersNamedExactlyWhatIsAskedTheNearestTheTopIsFirst() {
		val order = ranked("funny/")

		// One level down before two: the folder meant is the one with the shortest way to it.
		assertEquals("funny", order.first())
		assertEquals(setOf("funny/funny", "x/funny", "draft/funny"), order.drop(1).toSet())
	}

	private val funnies = setOf("funny", "funny/funny", "x/funny", "draft/funny")

	private val inFunny =
		setOf(
			"funny/a.jpg",
			"funny/b.png",
			"funny/notes.txt",
			"funny/sub",
			"funny/sub/c.jpg",
			"funny/funny",
			"funny/funny/d.jpg",
			"x/funny/e.jpg",
			"draft/funny/g.jpg",
		)

	@Test
	fun aFolderTermAloneFindsTheFoldersNamedSo() {
		// Named exactly: not the folder whose name it begins, and not a file of the name.
		assertEquals(funnies, found("funny/"))
		// By what a name starts with, ends with, and holds.
		assertEquals(setOf("nsight_a"), found("nsight*/"))
		assertEquals(setOf("my_nsight"), found("*nsight/"))
		assertEquals(setOf("nsight_a", "my_nsight", "the_nsight_sdk"), found("*nsight*/"))
		// At a path from the root, and from the home folder: the one folder there.
		assertEquals(setOf("projects/prism"), found("$root/projects/prism/"))
		assertEquals(setOf("projects"), found("~/projects/"))
		// A path of several levels names the folder at its end.
		assertEquals(setOf("x/funny"), found("x/funny/"))
	}

	@Test
	fun withSomethingAskedOfAnEntryAFolderTermSaysWhereItIs() {
		// What is directly in them, and everything under them.
		assertEquals(
			setOf(
				"funny/a.jpg",
				"funny/b.png",
				"funny/notes.txt",
				"funny/sub",
				"funny/funny",
				"funny/funny/d.jpg",
				"x/funny/e.jpg",
				"draft/funny/g.jpg",
			),
			found("funny/*"),
		)
		assertEquals(inFunny, found("funny/ *"))
		assertEquals(inFunny, found("funny/**"))

		val jpgs =
			setOf(
				"funny/a.jpg",
				"funny/sub/c.jpg",
				"funny/funny/d.jpg",
				"x/funny/e.jpg",
				"draft/funny/g.jpg",
			)

		assertEquals(jpgs, found("funny/ .jpg"))
		assertEquals(jpgs + "funny/b.png", found("funny/ type:image"))
		// A word, and an `is:` the user typed themselves: the folders in them, not they.
		assertEquals(setOf("funny/notes.txt"), found("funny/ notes"))
		assertEquals(setOf("funny/sub", "funny/funny"), found("funny/ is:dir"))
		// What is asked not to be is asked of an entry too.
		assertEquals(inFunny - "funny/notes.txt", found("funny/ -notes"))
		// `dir:` asks for what is in a folder, in so many words.
		assertEquals(inFunny, found("dir:funny"))
	}

	@Test
	fun severalFolderTermsFindTheFolderOneNamesInsideTheOthers() {
		// In either order: the folder named sub in a folder named funny.
		assertEquals(setOf("funny/sub"), found("funny/ sub/"))
		assertEquals(setOf("funny/sub"), found("sub/ funny/"))
		// One from the root and one named.
		assertEquals(setOf("projects/prism"), found("$root/projects/ prism/"))
		assertEquals(setOf("projects/prism/build"), found("prism/ build/ $root/projects/"))
		// A folder term negated says where not to look, and what is found is still the folders.
		assertEquals(funnies - "draft/funny", found("funny/ -draft/"))
		assertEquals(setOf("elsewhere/prism"), found("prism/ -$root/projects/"))
	}

	@Test
	fun anAliasOfFolderTermsIsFolderTerms() {
		assertEquals(setOf("projects"), found("projects@"))
		assertEquals(setOf("projects/prism"), found("projects@ prism/"))
		assertEquals(setOf("projects/prism/main.kt"), found("projects@ prism/ main"))
		assertEquals(funnies, found("fun@"))
		assertEquals(setOf("funny/sub"), found("fun@ sub/"))
		assertEquals(setOf("funny/sub/c.jpg"), found("fun@ sub/ .jpg"))
	}

	@Test
	fun aQueryIsAimedOnceAndWhatAnotherAddsIsNotTheUsers() {
		val aimed = read("funny/").aimed()

		assertTrue(aimed.terms.single().test is Folders)
		assertEquals(aimed, aimed.aimed())
		// A query with something asked of an entry is as it was written.
		assertEquals(read("funny/ .jpg"), read("funny/ .jpg").aimed())
		assertEquals(read("funny/**"), read("funny/**").aimed())
		assertEquals(read("-funny/"), read("-funny/").aimed())
		// A folder picker's `is:dir`, said after: the folders named so, where typed it would be those in them.
		assertEquals(funnies, found(Query(aimed.terms + Term(Is(setOf(Flag.Dir))))))
		// A search within another (a pinned one): the terms of both, aimed together.
		assertEquals(
			setOf("funny/sub"),
			found(Query(read("funny/").terms + read("sub/").terms)),
		)
		assertEquals(
			setOf("funny/sub/c.jpg"),
			found(Query(read("funny/").terms + read("sub/").terms + read(".jpg").terms)),
		)
	}
}
