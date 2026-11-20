package prism.index

import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.platform.Dev
import prism.platform.KernelDev

class ActivityTest {
	/** The time the log is told it is, in milliseconds; moved on by the tests. */
	private var now = 1_000_000L

	private val names = mapOf(5 to "touch", 6 to "cargo", 7 to "rsync")

	private fun log(perBurst: Int = 200, maxBytes: Long = ActivityLog.DEFAULT_MAX_BYTES) =
		ActivityLog(
			window = 5.seconds,
			keep = 60.seconds,
			perBurst = perBurst,
			maxBytes = maxBytes,
			processName = names::get,
			clock = { now },
		)

	private val home = FileId(KernelDev(Dev(8, 1)), 1234)

	/** [count] files made in [home] by [pid], heard as one batch now. */
	private fun ActivityLog.made(pid: Int?, count: Int = 1, first: Int = 0) =
		heard {
			repeat(count) {
				inFolder(pid, Act.FileMade, home, "file ${first + it}".encodeToByteArray())
			}
		}

	private val folders = { id: FileId -> "/home/avery".takeIf { id == home } }

	@Test
	fun oneProcesssChangesWithinTheWindowAreOneBurstAndLaterOnesAnother() {
		val log = log()

		log.made(5, 4)
		now += 3_000
		log.made(5, 3, first = 4)
		log.made(6)

		// Seven files in three seconds are one burst; the other process's is its own, and latest first.
		val (cargo, touch) = log.live.bursts()

		assertEquals(Doer.Process(5, "touch"), touch.by)
		assertEquals(7, touch.kept)
		assertEquals(Instant.ofEpochMilli(1_000_000), touch.from)
		assertEquals(Doer.Process(6, "cargo"), cargo.by)
		assertEquals(
			(0 until 7).map { "/home/avery/file $it" },
			log.live.heardIn(touch.id).map { it.spot.said(folders) },
		)

		// Five seconds after its first, the same process's next change begins a burst of its own.
		now += 2_000
		log.made(5)
		assertEquals(listOf(5, 6, 5), log.live.bursts().map { (it.by as Doer.Process).pid })
	}

	@Test
	fun aBurstKeepsOnlySoManyChangesAndCountsTheRest() {
		val log = log(perBurst = 200)

		log.made(7, 1_523)

		val burst = log.live.bursts().single()

		assertEquals(200, burst.kept)
		assertEquals(1_523, burst.seen)
		assertTrue(burst.overflowed)
		assertEquals(200, log.live.heardIn(burst.id).size)
	}

	@Test
	fun burstsOlderThanAMinuteAreLetGoAndSoAreTheirChanges() {
		val log = log()

		log.made(5)

		val old = log.live.bursts().single()

		now += 59_000
		assertEquals(listOf(old), log.live.bursts())
		now += 2_000
		assertEquals(emptyList<Burst>(), log.live.bursts())
		log.made(6)
		assertEquals(emptyList<Heard>(), log.live.heardIn(old.id))
		assertEquals(listOf("cargo"), log.live.bursts().map { (it.by as Doer.Process).name })
	}

	@Test
	fun theLogNeverTakesMoreMemoryThanItWasGiven_TheOldestChangesGoFirst() {
		// Two chunks of 64 KiB: some two thousand records of a short name.
		val log = log(perBurst = Int.MAX_VALUE, maxBytes = 128L shl 10)

		log.made(5, 10_000)

		val burst = log.live.bursts().single()
		val kept = log.live.heardIn(burst.id)

		assertEquals(10_000, burst.seen)
		assertEquals(kept.size, burst.kept)
		assertTrue(kept.size in 1_000 until 10_000, "${kept.size}")
		// What is kept is the newest.
		assertEquals("/home/avery/file 9999", kept.last().spot.said(folders))
	}

	@Test
	fun aCopyIsWhatTheLogHeldWhenItWasMadeAndTheLogGoesOn() {
		val log = log()

		log.made(5, 2)

		val copy = log.copy()

		log.made(5, 3, first = 2)
		log.made(6)

		assertEquals(1, copy.bursts().size)
		assertEquals(2, copy.bursts().single().kept)
		assertEquals(2, copy.heardIn(copy.bursts().single().id).size)
		assertEquals(5, log.live.heardIn(copy.bursts().single().id).size)
		assertEquals(2, log.live.bursts().size)
	}

	@Test
	fun aSearchFindsProcessesByNameFirstThenByThePathsOfTheirChanges() {
		val log = log()

		log.heard {
			inFolder(6, Act.FolderMade, home, "touched".encodeToByteArray())
			atPath(null, Act.Renamed, "/Users/avery/touch-ups.txt")
		}
		now += 1_000
		log.made(5)
		now += 1_000
		log.made(7)

		val found = log.live.found("TOUCH", folders)

		// touch by its name; then cargo and the unknown process by what they changed; and rsync not at all.
		assertEquals(
			listOf(Doer.Process(5, "touch"), Doer.Unknown, Doer.Process(6, "cargo")),
			found.map { it.by },
		)
		assertEquals(log.live.bursts(), log.live.found(" ", folders))
		// A folder the index does not hold is said by its numbers.
		assertEquals(
			"(the folder with inode 1 on device 8:1)/x",
			Spot.InFolder(FileId(KernelDev(Dev(8, 1)), 1), "x").said(folders),
		)
	}

	@Test
	fun nothingIsNotedWhileTheLogIsNotRecording() {
		val log = log()

		log.recording = false
		log.made(5)
		assertEquals(emptyList<Burst>(), log.live.bursts())
		log.recording = true
		log.made(5)
		assertFalse(log.live.bursts().isEmpty())
	}
}
