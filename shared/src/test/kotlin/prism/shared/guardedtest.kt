package prism.shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * State shared between threads: what is guarded is changed by one thread at a time, and again by the thread that has
 * it; what is published is read whole, and each change to it made to what the last left; what is set once is what
 * the first to set it said.
 */
class GuardedTest {
	/** [times] of [each], on each of [threads] threads at once. */
	private fun atOnce(threads: Int, times: Int, each: (Int) -> Unit) {
		val begin = CountDownLatch(1)

		val running =
			(0 until threads).map { number ->
				thread {
					begin.await()
					repeat(times) { each(number) }
				}
			}

		begin.countDown()
		running.forEach { it.join() }
	}

	private class Tally {
		var count = 0
		val seen = ArrayList<Int>()
	}

	@Test
	fun whatIsGuardedIsChangedByOneThreadAtATime() {
		val tally = Guarded(Tally())

		// Two things that must change together, changed in place: neither is ever seen without the other.
		atOnce(threads = 8, times = 500) {
			tally.guarded {
				seen += count
				count++
			}
		}

		assertEquals(4000, tally.guarded { count })
		assertEquals((0 until 4000).toList(), tally.guarded { seen.toList() })
	}

	@Test
	fun theThreadThatHasItMayAskForItAgain_AndItIsLetGoWhateverIsDoneUnderIt() {
		val tally = Guarded(Tally())

		// What is done under it asks for it again, as one guarded function calls another.
		assertEquals(2, tally.guarded { count++.let { tally.guarded { ++count } } })

		// What is done under it fails, or leaves early: another thread has it afterwards all the same.
		assertThrows(IllegalStateException::class.java) { tally.guarded { error("failed") } }

		fun early(): Int {
			tally.guarded { return count }
		}

		assertEquals(2, early())

		val other = AtomicInteger()

		thread { other.set(tally.guarded { count }) }.join()
		assertEquals(2, other.get())
	}

	@Test
	fun whatIsPublishedIsTheValueLastSet_AndEachChangeIsMadeToWhatTheLastLeft() {
		val published = Published(emptyList<Int>())

		assertEquals(emptyList<Int>(), published.now)
		published.set(listOf(1))
		assertEquals(listOf(1), published.now)
		assertEquals(listOf(1, 2), published.update { this + 2 })

		// Many writers: no change is made to a value another has since replaced.
		val count = Published(0)

		atOnce(threads = 8, times = 1000) { count.update { this + 1 } }
		assertEquals(8000, count.now)

		// As a property: read as what it is now, assigned as set.
		var words by Published(listOf("a"))

		words = words + "b"
		assertEquals(listOf("a", "b"), words)
	}

	@Test
	fun aValueIsSwappedOnlyWhereItIsStillTheOneExpected() {
		val first = Any()
		val published = Published<Any?>(first)

		// What two threads would both do once it is as it is, one does.
		val did = AtomicInteger()

		atOnce(threads = 8, times = 1) {
			if (published.swapped(first, null))
				did.incrementAndGet()
		}

		assertEquals(1, did.get())
		assertNull(published.now)
		assertFalse(published.swapped(first, first))
	}

	@Test
	fun whatIsSetOnceIsWhatTheFirstSetItTo() {
		val once = Once<String>()

		assertNull(once.value)

		val set = AtomicInteger()

		atOnce(threads = 8, times = 1) { number ->
			if (once.set("thread $number"))
				set.incrementAndGet()
		}

		assertEquals(1, set.get())

		val value = once.value

		assertTrue(value != null && value.startsWith("thread "))
		assertFalse(once.set("again"))
		assertSame(value, once.value)
	}
}
