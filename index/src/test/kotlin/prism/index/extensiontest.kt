package prism.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ExtensionTest {
	@Test
	fun anExtensionIsWhatFollowsTheLastDotOfANameThatHasSomethingBeforeItInLowerCase() {
		assertEquals(Extension.of("photo.jpg"), Extension.of("PHOTO.JPG"))
		assertEquals(Extension.of("a.gz"), Extension.of("archive.tar.gz"))
		assertNotEquals(Extension.of("a.jpg"), Extension.of("a.jpeg"))
		assertNotEquals(Extension.of("a.c"), Extension.of("a.h"))
		assertNotEquals(Extension.NONE, Extension.of("a.c"))
	}

	@Test
	fun aNameWithNoDotOrOnlyAFirstOrALastOneHasNone() {
		for (name in listOf("Makefile", ".gitignore", "name.", ".", "", "a.averylongextension"))
			assertEquals(Extension.NONE, Extension.of(name), name)
	}

	@Test
	fun pointsAreThoseOfTheEntriesThatHaveAnyAndNothingForTheRest() {
		val points = Points.of(mapOf(7 to 30, 2 to 5, 400 to 1))

		assertEquals(listOf(5, 30, 1), listOf(2, 7, 400).map { points[it] })
		assertEquals(listOf(0, 0, 0, 0), listOf(0, 3, 399, 401).map { points[it] })
		assertEquals(0, Points.of(emptyMap())[7])
	}
}
