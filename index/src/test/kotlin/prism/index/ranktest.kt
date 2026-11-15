package prism.index

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.index.query.QueryBuilder
import prism.index.query.query
import prism.shared.Loaded
import prism.shared.orElse

/**
 * The order a search gives its results in, best first: by how each word matches the name, by how much the user has
 * used a result and its folder, by how new it is, and by where it is; and the usage that ranks them, kept in a file of
 * its own.
 */
class RankTest {
	@TempDir
	lateinit var root: Path

	@TempDir
	lateinit var state: Path

	private val now = Instant.parse("2026-11-12T10:00:00Z")

	private fun indexer(at: Instant = now) =
		Indexer(mounts = emptyList(), clock = Clock.fixed(at, ZoneOffset.UTC))

	private fun made(vararg files: String) =
		files.forEach {
			root
				.resolve(it)
				.also { path ->
					path.parent.createDirectories()
				}.writeText(it)
		}

	private fun where(path: String) = Where.of(root.resolve(path))

	/** A context with nowhere as home, so that being under it changes nothing, unless a test says otherwise. */
	private fun context(usage: Usage = Usage.NONE, home: Path = state) =
		SearchContext(home = home, now = now, zone = ZoneOffset.UTC, usage = usage)

	/** What [build] finds in [index], best first, as paths under [root]. */
	private fun ranked(
		index: Index,
		context: SearchContext = context(),
		build: QueryBuilder.() -> Unit,
	): List<String> =
		(index.search(query(build), context) as Searched.Found).results.map {
			it.where.toString().removePrefix("$root/")
		}

	private fun indexed(): Index = Index.EMPTY.with(indexer().index(root))

	@Test
	fun theWholeNameComesFirstThenItsStartThenAWordsStartThenAnywhereInIt() {
		made(
			"myreport.txt",
			"q4-report.pdf",
			"QuarterlyReport.doc",
			"reports.txt",
			"report.pdf",
			"report",
		)

		assertEquals(
			listOf(
				// The whole name, and the name but for its extension.
				"report",
				"report.pdf",
				// Its start.
				"reports.txt",
				// A word's start: after a dash, or a capital after a small letter; equals, so from A to Z, whatever
				// their case.
				"q4-report.pdf",
				"QuarterlyReport.doc",
				// Inside a word.
				"myreport.txt",
			),
			ranked(indexed()) { +word("report") },
		)
	}

	@Test
	fun aWordStartsAfterWhatIsNotALetterAtAChangeOfDigitAndLetterAndAtACapital() {
		fun starts(name: String, at: Int) = Ranker.startsWord(name.encodeToByteArray(), 0, at)

		assertTrue(starts("q4-report", 3))
		assertTrue(starts("tax 2023", 4))
		assertTrue(starts("report2023", 6))
		assertTrue(starts("QuarterlyReport", 9))
		assertTrue(starts("my_report", 3))
		assertTrue(starts("anything", 0))
		// What is neither a letter nor a digit starts a word itself: the dot an extension starts at.
		assertTrue(starts("photo.jpg", 5))
		assertFalse(starts("myreport", 2))
		assertFalse(starts("REPORT", 2))
	}

	@Test
	fun aFilePickedComesBeforeOneMatchedAsWellAndAFolderPickedFromRaisesWhatIsInIt() {
		made("notes-a.txt", "notes-b.txt", "work/plan.txt", "other/plan.txt", "work/x.txt")

		val index = indexed()

		assertEquals(listOf("notes-a.txt", "notes-b.txt"), ranked(index) { +word("notes") })

		val picked = Usage.NONE.picked(listOf(where("notes-b.txt")), now)

		assertEquals(
			listOf("notes-b.txt", "notes-a.txt"),
			ranked(index, context(picked)) {
				+word("notes")
			},
		)

		// What was picked from `work` makes it a folder used, and what is in it comes first.
		val fromWork = Usage.NONE.picked(listOf(where("work/x.txt")), now)

		// Equal names are in the order of their paths.
		assertEquals(listOf("other/plan.txt", "work/plan.txt"), ranked(index) { +word("plan") })
		assertEquals(
			listOf("work/plan.txt", "other/plan.txt"),
			ranked(index, context(fromWork)) { +word("plan") },
		)
	}

	@Test
	fun equalsAreInTheOrderOfTheirNamesButTheirNumbersFromTheGreatestSoTheLatestDateLeads() {
		made(
			"dumps/java_2026_11_13_13_36_32.dump",
			"dumps/java_2026_11_13_09_00_01.dump",
			"dumps/java_2026_2_1_10_00_00.dump",
			"dumps/java_2025_12_31_23_59_59.dump",
			"dumps/Java_2024_01_01_00_00_00.dump",
			"older/java_2026_11_12_08_00_00.dump",
			"dumps/kotlin_2027_01_01.dump",
			"dumps/c_2020.dump",
			"shots/img_9.png",
			"shots/img_10.png",
			"shots/img_010.png",
		)

		val index = indexed()

		// Whatever folder each is in, and whatever case it is written in: a name's numbers as numbers, the greatest
		// first, so the eleventh month is before the second.
		assertEquals(
			listOf(
				"dumps/java_2026_11_13_13_36_32.dump",
				"dumps/java_2026_11_13_09_00_01.dump",
				"older/java_2026_11_12_08_00_00.dump",
				"dumps/java_2026_2_1_10_00_00.dump",
				"dumps/java_2025_12_31_23_59_59.dump",
				"dumps/Java_2024_01_01_00_00_00.dump",
			),
			ranked(index) { +word("java") },
		)
		// Equal as numbers, they are in the order of their paths.
		assertEquals(
			listOf("shots/img_010.png", "shots/img_10.png", "shots/img_9.png"),
			ranked(index) { +word("img") },
		)
		// What is not a number goes from A to Z, whatever numbers follow it.
		assertEquals(
			listOf(
				"dumps/c_2020.dump",
				"dumps/java_2026_11_13_13_36_32.dump",
				"dumps/java_2026_11_13_09_00_01.dump",
				"older/java_2026_11_12_08_00_00.dump",
				"dumps/java_2026_2_1_10_00_00.dump",
				"dumps/java_2025_12_31_23_59_59.dump",
				"dumps/Java_2024_01_01_00_00_00.dump",
				"dumps/kotlin_2027_01_01.dump",
			),
			ranked(index) { +word(".dump") },
		)
	}

	@Test
	fun ofMoreEqualsThanAreOrderedByNameTheFirstByNameLeadAndTheRestFollowByPath() {
		val count = Results.EXACT + 200

		made(*(1..count).map { "logs/log_%05d.txt".format(it) }.toTypedArray())

		val found = ranked(indexed()) { +word("log_") }

		assertEquals(count, found.size)
		// The greatest numbers first, as far as names are put in order.
		assertEquals(
			(count downTo 201).map { "logs/log_%05d.txt".format(it) },
			found.take(Results.EXACT),
		)
		// The rest as the folder holds them.
		assertEquals(
			(1..200).map { "logs/log_%05d.txt".format(it) },
			found.drop(Results.EXACT),
		)
	}

	@Test
	fun whatIsNewIsFoundByWhenItTurnedUpAndGivenNewestFirst() {
		made("docs/old.txt")

		val first = Index.EMPTY.with(indexer(now.minus(Duration.ofDays(30))).index(root))

		made("docs/week.txt")

		val second = first.with(indexer(now.minus(Duration.ofDays(5))).reread(where("docs"))!!)

		made("docs/hour.txt", "docs/made/inside.txt")

		val third = second.with(indexer(now.minus(Duration.ofHours(1))).reread(where("docs"))!!)

		// new:1d, new:1w, and what was there when the root was indexed, which was never seen to turn up.
		assertEquals(
			setOf("docs/hour.txt", "docs/made", "docs/made/inside.txt"),
			ranked(third) {
				+new(
					prism.index.query.Since
						.Within(kotlin.time.Duration.parse("1d")),
				)
			}.toSet(),
		)
		assertEquals(
			setOf("docs/hour.txt", "docs/made", "docs/made/inside.txt", "docs/week.txt"),
			ranked(third) {
				+new(
					prism.index.query.Since
						.Within(kotlin.time.Duration.parse("7d")),
				)
			}.toSet(),
		)

		fun newest(count: Int) =
			third.newest(count) { it.kind == Kind.File }.map {
				it.entry.where
					.toString()
					.removePrefix("$root/") to it.at
			}

		assertEquals(
			listOf(
				"docs/hour.txt" to now.minus(Duration.ofHours(1)),
				"docs/made/inside.txt" to now.minus(Duration.ofHours(1)),
				"docs/week.txt" to now.minus(Duration.ofDays(5)),
			),
			newest(10).sortedWith(
				compareByDescending<Pair<String, Instant>> {
					it.second
				}.thenBy { it.first },
			),
		)
		assertEquals(listOf("docs/week.txt"), newest(3).drop(2).map { it.first })
		assertEquals(2, newest(2).size)
		assertEquals(now.minus(Duration.ofHours(1)), newest(1).single().second)
		// As a file holds it, and compacted, the same.
		assertEquals(
			newest(10),
			third.compacted().newest(10) { it.kind == Kind.File }.map {
				it.entry.where
					.toString()
					.removePrefix("$root/") to it.at
			},
		)
	}

	@Test
	fun whatIsUnderTheHomeFolderShallowAndNotHiddenComesFirst() {
		made(
			"elsewhere/todo.txt",
			"home/todo.txt",
			"a/b/c/d/e/todo.txt",
			"a/todo.txt",
			".config/todo.txt",
			"config/todo.txt",
		)

		val index = indexed()
		val atHome = context(home = root.resolve("home"))

		assertEquals("home/todo.txt", ranked(index, atHome) { +word("todo") }.first())

		val order = ranked(index) { +word("todo") }

		assertTrue(order.indexOf("a/todo.txt") < order.indexOf("a/b/c/d/e/todo.txt"), "$order")
		assertTrue(
			order.indexOf("config/todo.txt") < order.indexOf(".config/todo.txt"),
			"$order",
		)
	}

	@Test
	fun whatTurnedUpLatelyComesBeforeWhatWasThereWhenTheIndexBegan() {
		made("docs/plan-old.txt")

		val first = Index.EMPTY.with(indexer(now.minus(Duration.ofDays(30))).index(root))

		made("docs/plan-new.txt")

		val second = first.with(indexer(now.minus(Duration.ofHours(1))).reread(where("docs"))!!)

		assertEquals(
			listOf("docs/plan-new.txt", "docs/plan-old.txt"),
			ranked(second) {
				+word("plan")
			},
		)
	}

	@Test
	fun whatPartsOfAnIndexFoundNothingAreInNoResultsWay() {
		made(
			"docs/plan.txt",
			"docs/plan-b.txt",
			"docs/plan-c.txt",
			"a/x.txt",
			"b/y.txt",
			"c/z.txt",
		)

		// An index much changed is many blocks, and most of them find nothing of what is searched for: here the
		// root's block finds three, and three grafts after it none.
		val index =
			listOf("a", "b", "c").fold(indexed()) { index, folder ->
				index.with(indexer().reread(where(folder))!!)
			}
		val found = (index.search(query { +word("plan") }, context()) as Searched.Found).results

		assertEquals(3, found.size)
		// Every result is read, by every way there is to read one.
		assertEquals(
			listOf("plan.txt", "plan-b.txt", "plan-c.txt"),
			found.indices.map(found::nameOf),
		)
		assertEquals(
			listOf("docs/plan.txt", "docs/plan-b.txt", "docs/plan-c.txt"),
			found.map { it.where.toString().removePrefix("$root/") },
		)
		assertEquals(List(3) { Kind.File }, found.indices.map(found::kindOf))

		// And where the parts that found nothing come first, and between.
		val none = (index.search(query { +word("zzz") }, context()) as Searched.Found).results
		val together = Results.of(listOf(none, none, found, none, none, found, none))

		assertEquals(6, together.size)
		assertEquals(
			listOf("plan.txt", "plan.txt", "plan-b.txt", "plan-b.txt", "plan-c.txt", "plan-c.txt"),
			together.indices.map(together::nameOf),
		)
	}

	@Test
	fun aPositionIsInTheLastPartThatStartsAtOrBeforeItWhateverPartsAreEmpty() {
		val random = java.util.Random(7)

		repeat(500) {
			// Parts of every size, a good half of them empty, in runs and alone, first, last and between.
			val sizes =
				List(1 + random.nextInt(12)) {
					if (random.nextBoolean())
						0
					else
						random.nextInt(5)
				}
			val laid = Laid(sizes)
			val expected = sizes.flatMapIndexed { part, size -> List(size) { part to it } }

			assertEquals(expected.size, laid.total)
			assertEquals(
				expected,
				List(laid.total) { position ->
					laid.at(Position(position)) { part, within ->
						part to
							within
					}
				},
				"$sizes",
			)
			expected.forEachIndexed { position, (part, within) ->
				assertEquals(Position(position), laid.positionOf(part, within), "$sizes")
			}
		}
		// A position there is none of is refused, and never read as some other.
		assertThrows(IllegalArgumentException::class.java) {
			Laid(listOf(0, 0)).at(Position(0)) { _, _ -> }
		}
		assertThrows(IllegalArgumentException::class.java) {
			Laid(listOf(2)).at(Position(2)) { _, _ -> }
		}
		assertThrows(IllegalArgumentException::class.java) {
			Laid(listOf(2)).at(Position(-1)) { _, _ -> }
		}
		// An entry and its score are one number, and come apart as they went together.
		for ((entry, score) in listOf(
			0 to 0,
			5 to Ranking.MOST,
			Int.MAX_VALUE to 1,
			123_456_789 to 4321,
		)) {
			assertEquals(entry, Hit.of(entry, score).entry)
			assertEquals(score, Hit.of(entry, score).score)
		}
	}

	@Test
	fun theResultsOfSeveralSearchesTogetherAreRankedTogether() {
		made("one/plan.txt", "two/plans.txt", "two/the-plan.txt")

		val one = Index.EMPTY.with(indexer().index(root.resolve("one")))
		val two = Index.EMPTY.with(indexer().index(root.resolve("two")))

		fun found(index: Index) =
			(
				index.search(
					query {
						+word("plan")
					},
					context(),
				) as Searched.Found
			).results

		val together = Results.of(listOf(found(two), found(one)))

		assertEquals(
			listOf("one/plan.txt", "two/plans.txt", "two/the-plan.txt"),
			together.map { it.where.toString().removePrefix("$root/") },
		)
		assertEquals("plan.txt", together.nameOf(0))
		assertEquals(Kind.File, together.kindOf(0))
		assertTrue(together.scoreOf(0) > together.scoreOf(1))
	}

	@Test
	fun aUseCountsHalfAsMuchEveryHalfLifeAndAPickUsesItsFolderToo() {
		val used = Usage.NONE.picked(listOf(where("docs/a.txt")), now)

		assertEquals(1.0, used.weightOf(where("docs/a.txt"), now), 1e-9)
		assertEquals(0.5, used.weightOf(where("docs/a.txt"), now.plus(Usage.HALF_LIFE)), 1e-9)
		assertEquals(1.0, used.weightOf(where("docs"), now), 1e-9)
		assertEquals(now, used.pickedAt(where("docs/a.txt")))
		// The folder was used, not picked.
		assertEquals(null, used.pickedAt(where("docs")))

		// Picked again later: what was left of the first use, and one more.
		val again = used.picked(listOf(where("docs/a.txt")), now.plus(Usage.HALF_LIFE))

		assertEquals(1.5, again.weightOf(where("docs/a.txt"), now.plus(Usage.HALF_LIFE)), 1e-9)

		// Long unused and not picked lately, a place is let go; one picked lately is kept.
		val late = now.plus(Duration.ofDays(200))

		assertEquals(0, again.pruned(late).size)
		assertEquals(2, again.pruned(now).size)
	}

	@Test
	fun usageWrittenIsUsageReadAndAFileDamagedIsSaidToBe() {
		val file = state.resolve("usage.prism")

		assertEquals(Loaded.Missing, UsageFile.read(file))

		val usage =
			Usage.NONE
				.picked(listOf(where("docs/a.txt"), where("docs/b.txt")), now)
				.picked(listOf(where("music")), now.plusSeconds(60))

		UsageFile.open(file).update { usage }
		assertEquals(Loaded.Read(usage), UsageFile.read(file))

		file.writeBytes(
			file.toFile().readBytes().also {
				it[it.size / 2] =
					(it[it.size / 2] + 1).toByte()
			},
		)
		assertInstanceOf(Loaded.Unreadable::class.java, UsageFile.read(file))
		assertEquals(Usage.NONE, UsageFile.read(file).orElse(Usage.NONE))
	}
}
