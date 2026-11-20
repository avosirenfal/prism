package prism.thumbnails

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.shared.Used

/**
 * Which thumbnails are kept (keeping.kt), over caches as they come to be on a real machine: one within its budget, a
 * folder of thousands scrolled through once, a month of new pictures a day with some looked at again, thumbnails far
 * too large for what they are, pictures used often and not lately, a cache full of another program's making, and
 * thousands of records of files that have no thumbnail.
 */
class KeepingTest {
	@TempDir
	lateinit var work: Path

	private val start: Instant = Instant.parse("2026-01-01T00:00:00Z")

	private fun days(count: Long): Duration = Duration.ofDays(count)

	/** The [number]th entry there could be, at [size]; or a record of a failure. */
	private fun entry(number: Int, size: ThumbnailSize? = ThumbnailSize.Large): Entry =
		Entry("${size?.folder ?: "fail/prism-1"}/${"%032x".format(number)}.png")

	/** What a photograph's thumbnail takes at [size]: about a byte a pixel. */
	private fun usual(size: ThumbnailSize): Long = size.pixels.toLong() * size.pixels

	/** An entry first known [at], and shown again at each of [again]. */
	private fun kept(bytes: Long, at: Instant, vararg again: Instant): Kept =
		again.fold(Kept.new(bytes, at)) { kept, shown -> kept.shown(shown) }

	/** One shown once a week for [weeks] weeks, the last time at [last]. */
	private fun weekly(bytes: Long, weeks: Int, last: Instant): Kept =
		(weeks - 1 downTo 0)
			.map { last.minus(days(7L * it)) }
			.let { shown -> kept(bytes, shown.first(), *shown.drop(1).toTypedArray()) }

	private fun Map<Entry, Kept>.bytes(): Long = values.sumOf { it.bytes }

	private fun Map<Entry, Kept>.without(removed: List<Entry>): Map<Entry, Kept> =
		this - removed.toSet()

	@Test
	fun nothingIsRemovedWhileTheCacheIsWithinItsBudget() {
		val entries = (1..1000).associate { entry(it) to Kept.new(60_000, start) }

		assertEquals(
			emptyList<Entry>(),
			Keeping.removed(entries, Budget(100_000_000, 1000), start),
		)
		assertEquals(emptyList<Entry>(), Keeping.removed(emptyMap(), Budget(1, 1), start))
		// Over by one file, or by one byte, it is cut to a little under: not removed from again at the next thumbnail.
		assertEquals(101, Keeping.removed(entries, Budget(100_000_000, 999), start).size)
		assertTrue(
			entries.without(Keeping.removed(entries, Budget(59_999_999, 1000), start)).bytes() <=
				54_000_000,
		)
	}

	@Test
	fun anOccasionIsNotAShowing() {
		val made = Kept.new(1000, start)

		assertFalse(made.used.established)

		// Scrolled past ten times in a minute, it was looked at once.
		val scrolled =
			(1..10).fold(made) { kept, second -> kept.shown(start.plusSeconds(second * 6L)) }

		assertEquals(made, scrolled)

		// Looked at again that evening, it is one that is looked at.
		val again = scrolled.shown(start.plus(Duration.ofHours(6)))

		assertTrue(again.used.established)
		assertEquals(start.plus(Duration.ofHours(6)), again.used.at)
		// What its uses weigh halves in sixty days, not fourteen: a season on, it still counts.
		assertEquals(
			1.0,
			Used(2.0, start).weightAt(start.plus(days(60)), Keeping.HALF_LIFE),
			1e-9,
		)
	}

	@Test
	fun aFolderOfThousandsScrolledThroughOnceDoesNotPushOutWhatIsLookedAtEveryWeek() {
		val now = start.plus(days(100))
		// Three hundred photographs looked at every week for two months, the last time five days ago.
		val favourites =
			(1..300).associate {
				entry(it) to
					weekly(usual(ThumbnailSize.Large), 8, now.minus(days(5)))
			}

		// Five thousand never seen before, scrolled through in the last hour, a second apart.
		val flood =
			(1..5000).associate {
				entry(10_000 + it) to
					Kept.new(usual(ThumbnailSize.Large), now.minusSeconds(5000L - it))
			}
		val entries = favourites + flood

		val removed = Keeping.removed(entries, Budget(Long.MAX_VALUE, 2000), now).toSet()
		val left = entries - removed

		// Every favourite stays, though each was last used days before any of the flood.
		assertTrue(favourites.keys.all { it in left })
		assertEquals(1800, left.size)

		// What is kept of the flood is its latest: nothing kept is older than anything removed.
		val oldestKept = flood.filterKeys { it in left }.values.minOf { it.used.at }
		val newestRemoved = flood.filterKeys { it in removed }.values.maxOf { it.used.at }

		assertTrue(oldestKept > newestRemoved)
	}

	@Test
	fun newPicturesStayLongEnoughToBeLookedAtAgainAndThenTheyAreKept() {
		// A cache with room for five thousand: three thousand established, and for a month, two hundred new
		// pictures a day, twenty of each day's looked at again two days later.
		val budget = Budget(Long.MAX_VALUE, 5000)
		val catalogue = Catalogue()
		val bytes = usual(ThumbnailSize.Large)

		(1..3000).forEach { number ->
			val entry = entry(number)

			catalogue.found(Lying(entry, bytes, start.minus(days(30))))
			catalogue.shown(entry, start.minus(days(20)))
			catalogue.shown(entry, start.minus(days(10)))
		}

		val revisited = ArrayList<Entry>()

		for (day in 0 until 30) {
			val at = start.plus(days(day.toLong()))

			val made = (1..200).map { entry(100_000 + day * 1000 + it) }

			made.forEachIndexed { index, entry ->
				catalogue.found(
					Lying(entry, bytes, at.plusSeconds(index.toLong())),
				)
			}
			// The day before yesterday's first twenty, looked at again: each is still there to be.
			if (day >= 2)
				(1..20).map { entry(100_000 + (day - 2) * 1000 + it) }.forEach { entry ->
					assertTrue(
						catalogue.of(entry) != null,
						"day $day: $entry was removed before it was seen again",
					)
					catalogue.shown(entry, at.plusSeconds(3600))
					revisited += entry
				}
			catalogue.removing(budget, at.plusSeconds(7200))
			assertTrue(catalogue.totals.files <= 5000)
		}

		val end = start.plus(days(30))
		val left = catalogue.all()

		// Every one looked at again is established and still kept, a month on; and what was established before
		// the month began was not pushed out by six thousand new ones.
		assertTrue(revisited.all { left[it]?.used?.established == true })
		assertEquals(560, revisited.size)
		assertTrue((1..3000).all { entry(it) in left })

		// What went was what was looked at once: the oldest of it.
		val once = left.filterValues { !it.used.established }

		assertTrue(
			once.values.all {
				Duration.between(it.used.at, end) < days(8)
			},
			"${once.values.minOf { it.used.at }}",
		)
	}

	@Test
	fun aFloodLargerThanTheTrialsRoomTakesTheTrialsRoomAndNothingElse() {
		val now = start.plus(days(1))

		// A cache all but full of what is established, and twice the trial's room in new thumbnails.
		val established =
			(1..1400).associate {
				entry(
					it,
				) to weekly(1000, 4, now.minus(days(1)))
			}

		val flood =
			(1..800).associate {
				entry(5000 + it) to
					Kept.new(1000, now.minusSeconds(800L - it))
			}

		val left =
			(established + flood).without(
				Keeping.removed(established + flood, Budget(Long.MAX_VALUE, 2000), now),
			)

		assertEquals(1800, left.size)
		assertTrue(established.keys.all { it in left })
		// The four hundred latest of the flood are kept: what is new always has a place.
		assertEquals((401..800).map { entry(5000 + it) }.toSet(), left.keys - established.keys)
	}

	@Test
	fun whatIsUsedOftenStaysThoughItWasNotUsedLately() {
		val now = start.plus(days(200))
		// Shown every week for thirty weeks, and then not for three months.
		val old = entry(1) to weekly(usual(ThumbnailSize.Large), 30, now.minus(days(90)))
		// A hundred shown twice, last week; two hundred shown once, yesterday.
		val twice =
			(1..100).associate {
				entry(100 + it) to
					kept(usual(ThumbnailSize.Large), now.minus(days(9)), now.minus(days(7)))
			}

		val once =
			(1..200).associate {
				entry(1000 + it) to
					Kept.new(usual(ThumbnailSize.Large), now.minus(days(1)))
			}
		val entries = twice + once + old

		// Room for ninety in all, and seventy-two of those for the established, of which there are a hundred and one.
		val left = entries.without(Keeping.removed(entries, Budget(Long.MAX_VALUE, 100), now))

		assertTrue(old.first in left)
		assertTrue(
			Keeping.worth(old.first, old.second, now) >
				2 * Keeping.worth(entry(101), twice.getValue(entry(101)), now),
		)
		assertEquals(90, left.size)
		assertEquals(72, left.count { it.value.used.established })
	}

	@Test
	fun ofTwoOtherwiseAlikeTheOneFarTooLargeForWhatItIsGoesFirst() {
		val usual = usual(ThumbnailSize.Large)
		// As large as its pixels uncompressed, and more: forty times what a thumbnail of its size takes.
		val heavy = usual * 40

		assertEquals(1.0, Keeping.heaviness(entry(1), usual))
		assertEquals(1.0, Keeping.heaviness(entry(1), usual / 10))
		assertEquals(20.0, Keeping.heaviness(entry(1), heavy))
		// What is large because it is a large thumbnail is not heavy.
		assertEquals(
			1.0,
			Keeping.heaviness(entry(1, ThumbnailSize.XxLarge), usual(ThumbnailSize.XxLarge)),
		)
		assertEquals(1.0, Keeping.heaviness(entry(1, size = null), 200))

		// Eleven shown once at the same moment, room for nine: the heavy one is among the two that go.
		val once =
			(1..11).associate {
				entry(it) to
					Kept.new(if (it == 4) heavy else usual, start)
			}

		assertTrue(
			entry(4) in Keeping.removed(once, Budget(Long.MAX_VALUE, 10), start.plusSeconds(60)),
		)

		// Eleven as established as each other: the same.
		val often =
			(1..11).associate {
				entry(it) to
					weekly(if (it == 7) heavy else usual, 5, start)
			}

		assertTrue(
			entry(7) in Keeping.removed(often, Budget(Long.MAX_VALUE, 10), start.plusSeconds(60)),
		)

		// But one twice as large as it should be, used three times as much as the rest, is worth its room.
		val worth = often + (entry(7) to weekly(usual * 4, 300, start))

		assertFalse(
			entry(7) in Keeping.removed(worth, Budget(Long.MAX_VALUE, 10), start.plusSeconds(60)),
		)

		// Over its bytes, a cache is brought under them, and removing the heavy one alone may do it.
		val removed = Keeping.removed(once, Budget(usual * 45, 100), start.plusSeconds(60))

		assertEquals(listOf(entry(4)), removed)
	}

	@Test
	fun aCacheOverItsBytesKeepsWhatIsUsedOfEverySize() {
		val now = start.plus(days(30))
		val large = usual(ThumbnailSize.XxLarge)
		val small = usual(ThumbnailSize.Normal)
		// Forty pictures looked at one at a time, large, every week; sixty looked at so once, last week; and a
		// thousand small thumbnails of a folder scrolled through yesterday.
		val viewed =
			(1..40).associate {
				entry(it, ThumbnailSize.XxLarge) to
					weekly(large, 4, now.minus(days(2)))
			}

		val glanced =
			(1..60).associate {
				entry(100 + it, ThumbnailSize.XxLarge) to
					Kept.new(large, now.minus(days(7)).plusSeconds(it.toLong()))
			}

		val scrolled =
			(1..1000).associate {
				entry(1000 + it, ThumbnailSize.Normal) to
					Kept.new(small, now.minus(days(1)).plusSeconds(it.toLong()))
			}
		val entries = viewed + glanced + scrolled
		// Sixty-four megabytes: the hundred large ones alone are over a hundred.
		val budget = Budget(64L shl 20, 100_000)

		val left = entries.without(Keeping.removed(entries, budget, now))

		assertTrue(left.bytes() <= (budget.bytes * Keeping.CUT_TO).toLong())
		// The large ones looked at every week are kept, large as they are; the small ones are all kept, being the
		// latest; what goes is the large ones looked at once, the oldest first.
		assertTrue(viewed.keys.all { it in left })
		assertTrue(scrolled.keys.all { it in left })

		val gone = glanced.keys.filter { it !in left }

		assertTrue(gone.isNotEmpty())
		assertEquals(gone.size, entries.size - left.size)
		assertTrue(
			gone.maxOf { glanced.getValue(it).used.at } <
				(glanced.keys - gone.toSet()).minOfOrNull { glanced.getValue(it).used.at }
					?: Instant.MAX,
		)
	}

	@Test
	fun aCacheFullOfAnotherProgramsThumbnailsIsTakenOnAsIfPrismHadMadeEachWhenItWasWritten() {
		val now = start.plus(days(730))
		val catalogue = Catalogue()

		// Fifty thousand thumbnails a file manager made over two years, an even number a day.
		val lying =
			(1..50_000).map { Lying(entry(it), 50_000, start.plusSeconds(it * 1261L)) }

		catalogue.reconcile(lying.asSequence().map { it to null })
		assertEquals(Totals(50_000, 50_000L * 50_000), catalogue.totals)
		// None has been shown by prism: each is as new as its file.
		assertEquals(Kept.new(50_000, start.plusSeconds(1261)), catalogue.of(entry(1)))

		val began = System.nanoTime()
		val removed = catalogue.removing(Budget(Long.MAX_VALUE, 10_000), now).toSet()

		assertTrue((System.nanoTime() - began) / 1_000_000 < 5000, "deciding took too long")
		// The nine thousand written last are kept.
		assertEquals((1..41_000).map(::entry).toSet(), removed)
		assertEquals(9000, catalogue.totals.files)
		// Within its budget now, nothing more goes; nor at the next thumbnail made.
		assertEquals(
			emptyList<Entry>(),
			catalogue.removing(Budget(Long.MAX_VALUE, 10_000), now),
		)
		catalogue.found(Lying(entry(60_000), 50_000, now))
		assertEquals(
			emptyList<Entry>(),
			catalogue.removing(Budget(Long.MAX_VALUE, 10_000), now),
		)
	}

	@Test
	fun thousandsOfRecordsOfFilesWithNoThumbnailDoNotPushOutThumbnails() {
		val now = start.plus(days(30))
		val thumbnails =
			(1..500).associate {
				entry(it) to
					weekly(usual(ThumbnailSize.Large), 3, now.minus(days(3)))
			}

		// A folder of three thousand damaged films, each tried once and remembered.
		val records =
			(1..3000).associate {
				entry(10_000 + it, size = null) to
					Kept.new(180, now.minusSeconds(3000L - it))
			}
		val entries = thumbnails + records

		val left = entries.without(Keeping.removed(entries, Budget(Long.MAX_VALUE, 2000), now))

		assertTrue(thumbnails.keys.all { it in left })
		assertEquals(1800, left.size)
		// The records kept are the latest: a file tried long ago may be tried again.
		assertTrue(entry(13_000, size = null) in left)
		assertFalse(entry(10_001, size = null) in left)
	}

	@Test
	fun whatIsKnownIsKeptInAFileBetweenRunsAndMadeToAgreeWithTheCache() {
		val file = work.resolve("state/thumbnails.used")
		val catalogue = Catalogue(file)

		catalogue.found(Lying(entry(1), 1000, start))
		catalogue.found(Lying(entry(2), 2000, start))
		catalogue.found(Lying(entry(3, size = null), 180, start))
		catalogue.shown(entry(1), start.plus(days(1)))
		// What is not known is not shown: it is told of when it is found.
		catalogue.shown(entry(99), start.plusSeconds(100))
		assertNull(catalogue.of(entry(99)))
		catalogue.write()

		val again = Catalogue(file).also { it.read() }

		assertEquals(catalogue.all(), again.all())
		assertEquals(Totals(3, 3180), again.totals)
		assertTrue(again.of(entry(1))!!.used.established)

		// The cache as it now lies: one gone, one written again at another size, one never seen.
		again.reconcile(
			sequenceOf(
				Lying(entry(1), 1500, start.plus(days(2))),
				Lying(entry(3, size = null), 180, start),
				Lying(entry(4), 4000, start.plus(days(3))),
			).map { it to null },
		)
		assertNull(again.of(entry(2)))
		// Written again, it is the same thumbnail, used as it was.
		assertEquals(again.of(entry(1))!!.used, catalogue.of(entry(1))!!.used)
		assertEquals(1500, again.of(entry(1))!!.bytes)
		assertEquals(Kept.new(4000, start.plus(days(3))), again.of(entry(4)))
		assertEquals(Totals(3, 5680), again.totals)

		// One made while the cache was being listed is not forgotten for not being in the listing.
		again.found(Lying(entry(5), 500, start.plus(days(5))))
		again.reconcile(
			sequenceOf(Lying(entry(1), 1500, start) to null),
			since = start.plus(days(4)),
		)
		assertEquals(setOf(entry(1), entry(5)), again.all().keys)

		// A thumbnail that was in the folder thumbnails were last kept in is known as it was there.
		val moved = Catalogue()
		val was = weekly(700, 5, start)

		moved.found(Lying(entry(8), 700, start.plus(days(50))), carried = was)
		assertEquals(was, moved.of(entry(8)))

		// A file of something else, or none, is nothing known, and no error; a line that says nothing is passed over.
		Files.writeString(
			file,
			"not\tthe\tformat\n1.0\t17\t300\tlarge/${"0".repeat(32)}.png\n\n",
		)
		assertEquals(
			mapOf(
				Entry("large/${"0".repeat(32)}.png") to
					Kept(300, Used(1.0, Instant.ofEpochSecond(17))),
			),
			Catalogue(file).also { it.read() }.all(),
		)
		assertEquals(
			emptyMap<Entry, Kept>(),
			Catalogue(work.resolve("none"))
				.also {
					it.read()
				}.all(),
		)
	}

	@Test
	fun theDefaultBudgetHasRoomForAFloodAndForWhatIsEstablished() {
		val budget = Budget()

		assertEquals(4L shl 30, budget.bytes)
		assertEquals(200_000, budget.files)

		// The trial's fifth of it, once it has been cut: tens of thousands of new thumbnails before any new one goes.
		val trial =
			Keeping.trialOf(
				Budget(
					(budget.bytes * Keeping.CUT_TO).toLong(),
					(
						budget.files *
							Keeping.CUT_TO
					).toInt(),
				),
			)

		assertEquals(36_000, trial.files)
		assertTrue(trial.bytes / usual(ThumbnailSize.Large) > 10_000)
	}
}
