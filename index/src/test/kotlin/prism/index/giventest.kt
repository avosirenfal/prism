package prism.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import prism.index.query.QueryText

/** An index of a tree that was given, and not read from a disk: held as any index is, and searched as one. */
class GivenTest {
	private val index =
		Index.given(
			listOf(
				Given(listOf("src"), folder = true),
				Given(listOf("src", "main.c"), folder = false),
				Given(listOf("src", "deep", "a.txt"), folder = false),
				Given(listOf("README.md"), folder = false),
				Given(listOf("README.md"), folder = false),
				Given(listOf("empty"), folder = true),
			),
		)

	private fun found(text: String): Set<String> {
		val results = (index.search(QueryText.read(text).query) as Searched.Found).results

		return (0 until results.size).map { results[it].where.toString() }.toSet()
	}

	@Test
	fun aGivenTreeIsHeldWithTheFoldersItImplies() {
		// The root, `src`, `src/deep` (implied by what is under it), `empty`, and the three files, one given twice.
		assertEquals(7, index.size)
		assertEquals(setOf("/src", "/src/deep", "/empty"), found("is:dir"))
	}

	@Test
	fun itIsSearchedWithTheLanguageTheIndexOfTheDiskIs() {
		assertEquals(setOf("/src/main.c"), found("main"))
		assertEquals(setOf("/src/deep/a.txt"), found("ext:txt"))
		assertEquals(setOf("/src/main.c", "/src/deep", "/src/deep/a.txt"), found("dir:src"))
		assertEquals(setOf("/README.md"), found("=readme"))
		assertEquals(setOf("/src/deep/a.txt"), found("src/deep/*.txt"))
	}
}
