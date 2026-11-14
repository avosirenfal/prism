package prism.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import prism.index.query.Own

/** Prism's own things, found by a search that says `prism`, or that is a bare `?`. */
class OwnTest {
	private fun found(text: String, offered: List<Own> = Own.ALL) = Own.found(text, offered)

	@Test
	fun theWordPrismWholeAndAloneAsksForPrismsOwnAndNothingElseDoes() {
		assertEquals(Own.ALL, found("prism"))
		assertEquals(Own.ALL, found("  PRISM  "))
		assertEquals(emptyList<Own>(), found("prisms"))
		assertEquals(emptyList<Own>(), found("prism.md"))
		assertEquals(emptyList<Own>(), found("settings"))
		assertEquals(emptyList<Own>(), found(""))
	}

	@Test
	fun theOtherWordsBeginTheWordsOfWhatIsFoundWhereverPrismComes() {
		assertEquals(listOf(Own.Settings), found("prism settings"))
		assertEquals(listOf(Own.Settings), found("prism set"))
		assertEquals(listOf(Own.Settings), found("settings prism"))
		assertEquals(listOf(Own.Settings), found("prism pref"))
		assertEquals(listOf(Own.System), found("prism system"))
		assertEquals(listOf(Own.Bindings), found("prism bindings"))
		assertEquals(listOf(Own.Bindings), found("prism key bind"))
		assertEquals(listOf(Own.Help), found("prism help"))
		assertEquals(emptyList<Own>(), found("prism nothing"))
		// One whose title a word begins before one found by another of its words: System, before Settings'
		// nothing and Help's "search" and "syntax", and Bindings' "shortcuts".
		assertEquals(listOf(Own.Settings, Own.System, Own.Help, Own.Bindings), found("prism s"))
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
		assertEquals(emptyList<Own>(), found("prism settings", listOf(Own.Help, Own.System)))
		assertEquals(emptyList<Own>(), found("?", listOf(Own.Settings)))
		// In the order they were offered in.
		assertEquals(
			listOf(Own.Help, Own.Settings),
			found("prism", listOf(Own.Help, Own.Settings)),
		)
	}
}
