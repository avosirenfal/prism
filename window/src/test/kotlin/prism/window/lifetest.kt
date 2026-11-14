package prism.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Where a window is in its life, and what each thing that happens to it makes of that. */
class LifeTest {
	private val sound =
		listOf(Life.Hidden, Life.Shown(drawn = false), Life.Shown(drawn = true))

	@Test
	fun aFramePutInAWindowHasItOnTheScreenWithOneWhereverItWas() {
		for (life in sound)
			assertEquals(Life.Shown(drawn = true), life.drawnIn(), "$life")
		assertEquals(false, Life.Hidden.drawn)
	}

	@Test
	fun whatGoesWrongInAWindowIsToldOnceOpenOrClosed() {
		for (life in sound) {
			assertEquals(Life.Failed, life.failing(), "$life")
			assertEquals(false, life.failed, "$life")
		}
		assertNull(Life.Failed.failing())
		assertEquals(true, Life.Failed.failed)

		// As it closes, too: what closing it throws is its failure, told if nothing was before.
		assertEquals(Life.Closed(failed = true), Life.Closed(failed = false).failing())
		assertNull(Life.Closed(failed = true).failing())
	}

	@Test
	fun aWindowIsClosedOnceAndWhatWentWrongInItIsRememberedClosed() {
		for (life in sound)
			assertEquals(Life.Closed(failed = false), life.closing(), "$life")
		assertEquals(Life.Closed(failed = true), Life.Failed.closing())
		assertNull(Life.Closed(failed = false).closing())
		assertNull(Life.Closed(failed = true).closing())
	}

	@Test
	fun aWindowThatFailedOrIsClosedIsNotOneFramesArePutIn() {
		for (life in listOf(
			Life.Failed,
			Life.Closed(failed = false),
			Life.Closed(failed = true),
		))
			assertEquals(life, life.drawnIn(), "$life")
	}
}
