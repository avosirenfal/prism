package barion.index

import barion.index.query.QueryBuilder
import barion.index.query.query
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
				// A word's start: after a dash, or a capital after a small letter.
				"QuarterlyReport.doc",
				"q4-report.pdf",
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

		assertEquals(listOf("other/plan.txt", "work/plan.txt"), ranked(index) { +word("plan") })
		assertEquals(
			listOf("work/plan.txt", "other/plan.txt"),
			ranked(index, context(fromWork)) { +word("plan") },
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
		val file = state.resolve("usage.barion")

		assertEquals(UsageRead.Missing, UsageFile.read(file))

		val usage =
			Usage.NONE
				.picked(listOf(where("docs/a.txt"), where("docs/b.txt")), now)
				.picked(listOf(where("music")), now.plusSeconds(60))

		UsageFile.write(file, usage)
		assertEquals(UsageRead.Read(usage), UsageFile.read(file))

		file.writeBytes(
			file.toFile().readBytes().also {
				it[it.size / 2] =
					(it[it.size / 2] + 1).toByte()
			},
		)
		assertInstanceOf(UsageRead.Unreadable::class.java, UsageFile.read(file))
		assertEquals(Usage.NONE, UsageFile.read(file).usage)
	}
}
