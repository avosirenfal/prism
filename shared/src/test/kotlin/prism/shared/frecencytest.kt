package prism.shared

import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What is used, how much and how lately ([Uses]): the weight, what is kept of a full store, and its file. */
class FrecencyTest {
	private val now = Instant.parse("2026-11-15T12:00:00Z")

	private fun ago(days: Long = 0) = now.minus(Duration.ofDays(days))

	/** [key] used once a day for [days] days, the last of them [until] days ago. */
	private fun Uses<String>.daily(key: String, days: Int, until: Long = 0) =
		(days - 1 downTo 0).fold(this) { uses, day -> uses.used(key, ago(days = until + day)) }

	/** [key] used once, [seconds] after now, and what is kept of a store of [BOUND] then. */
	private fun Uses<String>.passing(key: String, seconds: Long) =
		used(key, now.plusSeconds(seconds)).capped(BOUND, now.plusSeconds(seconds))

	@Test
	fun aUseWeighsOneAndHalfAsMuchForEveryHalfLifeSince() {
		val uses = Uses<String>().used("a", now)

		assertEquals(1.0, uses.weightOf("a", now), 1e-9)
		assertEquals(0.5, uses.weightOf("a", now.plus(Used.HALF_LIFE)), 1e-9)
		assertEquals(0.25, uses.weightOf("a", now.plus(Used.HALF_LIFE.multipliedBy(2))), 1e-9)
		assertEquals(0.0, uses.weightOf("never", now))
		// One use is one occasion; a second, however long after, makes it established.
		assertFalse(uses.uses.getValue("a").established)
		assertTrue(
			uses
				.used("a", now.plus(Duration.ofDays(400)))
				.uses
				.getValue("a")
				.established,
		)
	}

	@Test
	fun underItsBoundNothingIsLetGo() {
		val uses = Uses<String>().used("a", ago(days = 300)).used("b", now)

		assertEquals(uses, uses.capped(2, now))
		assertEquals(uses, uses.capped(BOUND, now))
	}

	@Test
	fun aThingUsedForTheFirstTimeIsKeptThoughEveryOtherIsHeavier() {
		// A store at its bound, every one of them used daily for a month: all far heavier than one use.
		val full = (1..BOUND).fold(Uses<String>()) { uses, at -> uses.daily("old-$at", 30) }
		val kept = full.used("new", now).capped(BOUND, now)

		assertEquals(BOUND, kept.uses.size)
		assertTrue("new" in kept.uses)

		// It stays while fewer than the trial's places are taken by others used once after it,
		val later = (1..99).fold(kept) { uses, at -> uses.passing("passing-$at", at.toLong()) }

		assertTrue("new" in later.uses)

		// and is gone once that many have been, if it was never used again.
		assertFalse("new" in later.passing("passing-100", 100).uses)
	}

	@Test
	fun usedAgainWhileOnTrialAThingIsEstablishedAndNoLongerPutOutByWhatPasses() {
		// A store at its bound: some used daily, the rest things used once, a while ago.
		val full =
			(1..300)
				.fold(Uses<String>()) { uses, at -> uses.daily("old-$at", 30) }
				.let { (1..200).fold(it) { uses, at -> uses.used("once-$at", ago(days = 3)) } }

		val again = full.passing("new", 1).passing("new", 2)
		val after = (1..400).fold(again) { uses, at -> uses.passing("passing-$at", 10L + at) }

		assertTrue(again.uses.getValue("new").established)
		assertTrue("new" in after.uses)
		assertFalse("once-1" in after.uses)
	}

	@Test
	fun aThingUsedSteadilyClimbsPastOlderHeavierOnesAsTheyAge() {
		// Twelve folders used every day for two months, until a month ago; then a new one, daily for three weeks.
		val old =
			(1..12).fold(
				Uses<String>(),
			) { uses, at -> uses.daily("old-$at", 60, until = 30) }

		val first = old.used("new", ago(days = 20))

		// The day it is first used it is the lightest of them, and is not among the ten used most.
		assertFalse("new" in first.most(10, ago(days = 20)).map { it.first })
		assertEquals(
			"new",
			old
				.daily("new", 21)
				.most(10, now)
				.first()
				.first,
		)
	}

	@Test
	fun aWalkThroughHundredsOfFoldersOncePutsOutNothingThatIsUsed() {
		// What is used every day, and what was used a few times a month ago: lighter by now than one fresh use.
		val daily = (1..20).map { "daily-$it" }
		val sometimes = (1..50).map { "sometimes-$it" }
		val base =
			daily
				.fold(Uses<String>()) { uses, key -> uses.daily(key, 30) }
				.let { sometimes.fold(it) { uses, key -> uses.daily(key, 3, until = 40) } }

		assertTrue(sometimes.all { base.weightOf(it, now) < 1.0 })

		// Six hundred folders gone through once each, the store capped as each is.
		val walked = (1..600).fold(base) { uses, at -> uses.passing("walked-$at", at.toLong()) }

		assertEquals(BOUND, walked.uses.size)
		assertTrue(daily.all { it in walked.uses })
		assertTrue(sometimes.all { it in walked.uses })
		// Of the walk, as many as there is room for are on trial, the latest of it; the rest are gone.
		assertEquals(BOUND - 70, walked.uses.keys.count { it.startsWith("walked-") })
		assertTrue("walked-600" in walked.uses)
		assertFalse("walked-1" in walked.uses)
		// And what is shown as used most is what is used.
		assertTrue(walked.most(10, now.plusSeconds(600)).all { it.first.startsWith("daily-") })
	}

	@Test
	fun aStoreFullOfTheEstablishedLeavesTheTrialItsPlaces() {
		// More established than the whole bound: the heaviest take all but the trial's places, and the rest of
		// them and what is used once share those, the latest used first.
		val full =
			(1..BOUND + 50).fold(
				Uses<String>(),
			) { uses, at -> uses.daily("old-$at", 30) }

		val walked = (1..300).fold(full) { uses, at -> uses.passing("walked-$at", at.toLong()) }

		assertEquals(BOUND, walked.uses.size)
		assertEquals(
			BOUND - Uses.trialOf(BOUND),
			walked.uses.keys.count { it.startsWith("old-") },
		)
		assertEquals(Uses.trialOf(BOUND), walked.uses.keys.count { it.startsWith("walked-") })
	}

	@Test
	fun usesAreWrittenALineEachAndReadBackAsTheyWere() {
		val format = Uses.format<String>({ it }, { it })
		val uses = Uses<String>().daily("a b", 3).used("c", ago(days = 9))

		assertEquals(Loaded.Read(uses), format.read(format.written(uses)))

		// A line that says nothing is left out, and the rest are read.
		val damaged = String(format.written(uses)) + "nonsense\n\t\t\n"

		assertEquals(Loaded.Read(uses), format.read(damaged.toByteArray()))
	}

	private companion object {
		const val BOUND = 500
	}
}
