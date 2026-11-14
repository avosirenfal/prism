package prism.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import prism.index.query.Own

/** Prism's own things, found by their own words, by the word `prism`, or by a bare `?`. */
class OwnTest {
	private fun found(text: String, offered: List<Own> = Own.ALL) = Own.found(text, offered)

	@Test
	fun eachIsFoundByItsOwnWordsOnceAFewLettersAreTyped() {
		assertEquals(listOf(Own.Settings), found("settings"))
		assertEquals(listOf(Own.Settings), found("set"))
		assertEquals(listOf(Own.Settings), found("Preferences"))
		assertEquals(listOf(Own.System), found("system"))
		assertEquals(listOf(Own.Bindings), found("key bind"))
		assertEquals(listOf(Own.Bindings), found("shortcuts"))
		assertEquals(listOf(Own.Help), found("help"))
		// A letter or two is on the way to anything: nothing of prism's yet.
		assertEquals(emptyList<Own>(), found("s"))
		assertEquals(emptyList<Own>(), found("se"))
		assertEquals(emptyList<Own>(), found(""))
		// Every word has to begin one of its words.
		assertEquals(emptyList<Own>(), found("settings report"))
		assertEquals(emptyList<Own>(), found("report"))
		assertEquals(emptyList<Own>(), found("resettings"))
	}

	@Test
	fun theWordPrismScopesASearchToPrismsOwn() {
		assertEquals(Own.ALL, found("prism"))
		assertEquals(Own.ALL, found("  PRISM  "))
		assertEquals(listOf(Own.Settings), found("prism settings"))
		assertEquals(listOf(Own.Settings), found("settings prism"))
		// With it, a letter is enough: those whose title it begins first, then those found by another word.
		assertEquals(listOf(Own.Settings, Own.System, Own.Help, Own.Bindings), found("prism s"))
		assertEquals(emptyList<Own>(), found("prism nothing"))
		// The word whole and alone: these are names.
		assertEquals(emptyList<Own>(), found("prisms"))
		assertEquals(emptyList<Own>(), found("prism.md"))
	}

	@Test
	fun aBareQuestionMarkAsksHowAndOnlyAlone() {
		assertEquals(listOf(Own.Help), found("?"))
		assertEquals(listOf(Own.Help), found(" ? "))
		assertEquals(emptyList<Own>(), found("??"))
		assertEquals(emptyList<Own>(), found("? report"))
		assertEquals(emptyList<Own>(), found("img_?"))
	}

	@Test
	fun onlyWhatIsOfferedIsFound() {
		assertEquals(listOf(Own.Help), found("prism", listOf(Own.Help)))
		assertEquals(emptyList<Own>(), found("settings", listOf(Own.Help, Own.System)))
		assertEquals(emptyList<Own>(), found("?", listOf(Own.Settings)))
		// In the order they were offered in.
		assertEquals(
			listOf(Own.Help, Own.Settings),
			found("prism", listOf(Own.Help, Own.Settings)),
		)
	}
}
