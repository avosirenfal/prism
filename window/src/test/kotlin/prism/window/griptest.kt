package prism.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How an overlay that is a layer surface comes by the keyboard, on compositors that answer its asking differently:
 * what it asks of each, in order, with no compositor and no clock.
 */
class GripTest {
	private val moment = Grip.TAKEN_AT_ONCE_NANOS

	/** What a surface that began as [first] asked the compositor for as each of [told] happened, and how it ended. */
	private fun asked(first: Grip, vararg told: Told): Pair<List<Interactivity>, Grip> =
		told.fold(emptyList<Interactivity>() to first) { (asked, grip), heard ->
			grip.told(heard).let { step -> asked + listOfNotNull(step.toAsk?.asks) to step.grip }
		}

	@Test
	fun anOverlayAsksOnDemandWhereItSharesTheKeyboardAndExclusivelyWhereItHoldsIt() {
		assertEquals(Interactivity.OnDemand, Grip.of(shares = true).asks)
		assertEquals(Interactivity.Exclusive, Grip.of(shares = false).asks)
		assertEquals(1 to 2, Interactivity.Exclusive.wire to Interactivity.OnDemand.wire)
	}

	@Test
	fun givenTheKeyboardForAskingOnDemandItAsksNothingMore() {
		// niri: a surface that appears asking on demand has the keyboard by the time it is looked for.
		assertEquals(
			emptyList<Interactivity>() to Grip.Asking,
			asked(Grip.Asking, Told.Given(10), Told.Waited(has = true)),
		)
		// And whatever is clicked next takes it, which is nothing to ask about.
		assertEquals(
			emptyList<Interactivity>() to Grip.Asking,
			asked(Grip.Asking, Told.Given(10), Told.Waited(has = true), Told.Taken(20)),
		)
	}

	@Test
	fun givenNothingForAskingOnDemandItAsksExclusivelyThenOnDemandOnceItHasTheKeyboard() {
		// sway: nothing for the asking, the keyboard for asking exclusively, and it stays once that is on demand.
		assertEquals(
			listOf(Interactivity.Exclusive, Interactivity.OnDemand) to Grip.Shared(since = 40),
			asked(Grip.Asking, Told.Waited(has = false), Told.Given(40)),
		)
		// Taken a person's while later, it is the person's doing: nothing is asked, and it is shared still.
		assertEquals(
			listOf(Interactivity.Exclusive, Interactivity.OnDemand) to Grip.Shared(since = 40),
			asked(Grip.Asking, Told.Waited(has = false), Told.Given(40), Told.Taken(40 + moment)),
		)
	}

	@Test
	fun losingTheKeyboardInTheSameBreathAsSharingItItHoldsItForGood() {
		val taken =
			arrayOf(Told.Waited(has = false), Told.Given(40), Told.Taken(40 + moment - 1))

		assertEquals(
			listOf(Interactivity.Exclusive, Interactivity.OnDemand, Interactivity.Exclusive) to
				Grip.Held,
			asked(Grip.Asking, *taken),
		)
		// Held, nothing that happens after has it ask for anything again.
		assertEquals(
			listOf(Interactivity.Exclusive, Interactivity.OnDemand, Interactivity.Exclusive) to
				Grip.Held,
			asked(
				Grip.Asking,
				*taken,
				Told.Given(90),
				Told.Taken(91),
				Told.Waited(has = false),
			),
		)
	}

	@Test
	fun anOverlayThatHoldsTheKeyboardAsksNothingWhateverHappens() {
		assertEquals(
			emptyList<Interactivity>() to Grip.Held,
			asked(
				Grip.Held,
				Told.Waited(has = false),
				Told.Given(1),
				Told.Taken(2),
				Told.Given(3),
			),
		)
	}

	@Test
	fun aSurfaceThatIsGoneAsksNothingAndStaysGone() {
		for (first in listOf(Grip.Held, Grip.Asking, Grip.ToBeGiven, Grip.Shared(since = 5))) {
			assertEquals(
				emptyList<Interactivity>() to Grip.Gone,
				asked(
					first,
					Told.Closed,
					Told.Waited(has = false),
					Told.Given(6),
					Told.Taken(7),
					Told.Closed,
				),
				"$first",
			)
		}
	}
}
