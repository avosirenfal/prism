package prism.browser

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.preferences.Relevance

/**
 * Two months of a person's pickers, played out on a disk of their own: six applications opening and saving files and
 * picking folders all over it, each in its own way (every day, every week, once, and a habit taken up or given up),
 * and the person going about the folders between. What the Recents page then shows each application, and each kind
 * of picker, is what is looked at: that every section is the newest first, that what is saved is kept, and that the
 * most relevant folders are those a person would look for first.
 */
class RecentSimulationTest {
	@TempDir
	lateinit var root: Path

	private val start = Instant.parse("2026-09-01T07:00:00Z")
	private val days = 60

	private val firefox = AppKey.of("org.mozilla.firefox")!!
	private val gimp = AppKey.of("org.gimp.GIMP")!!
	private val writer = AppKey.of("org.libreoffice.LibreOffice")!!
	private val code = AppKey.of("com.visualstudio.code")!!
	private val mail = AppKey.of("org.mozilla.Thunderbird")!!
	private val inkscape = AppKey.of("org.inkscape.Inkscape")!!

	private val home: Path get() = root.resolve("home")

	private fun at(path: String): Path = home.resolve(path)

	/** When on [day] (from the first) at [hour], and [minute]. */
	private fun on(day: Int, hour: Int, minute: Int = 0): Instant =
		start
			.plus(
				Duration.ofDays(day.toLong()),
			).plus(Duration.ofHours(hour.toLong() - 7))
			.plusSeconds(minute * 60L)

	/** The life played out: the store it was kept in, the files it made (the newest first), and when it ended. */
	private class Lived(
		val store: RecentStore,
		val made: List<NewFile>,
		val now: Instant,
	)

	/** The life played out, [evening] told of it as it is at the end of each day. */
	private fun lived(evening: (Int, Lived) -> Unit = { _, _ -> }): Lived {
		val store = RecentsFiles(root.resolve("state"))
		val random = Random(7)
		val made = ArrayList<NewFile>()
		var count = 0

		fun folder(path: String): Path = at(path).also { it.createDirectories() }

		/** A file there to be opened: made before, by whatever made it. */
		fun there(path: String, at: Instant): Path =
			at(path).also {
				if (!it.exists()) {
					it.parent.createDirectories()
					it.writeText(path)
					made += NewFile(it, at)
				}
			}

		/** [app] saved a file of [name] into [into] at [at]: picked, then written, as an application does. */
		fun saved(app: AppKey, into: String, name: String, at: Instant) {
			val file = folder(into).resolve("${count++}-$name")

			store.picked(listOf(file), Choosing.SaveAs(file.fileName.toString()), app, at)
			file.writeText(name)
			made += NewFile(file, at.plusSeconds(2))
		}

		fun opened(app: AppKey, from: String, name: String, at: Instant) =
			store.picked(
				listOf(there("$from/$name", at.minusSeconds(3_600))),
				Choosing.OneFile(),
				app,
				at,
			)

		fun went(path: String, at: Instant) = store.went(folder(path), at)

		for (day in 0 until days) {
			val workday = day % 7 < 5

			// Firefox: a download or three every day; a receipt every week; the taxes once, early; the trip's pictures
			// once, late. It uploads a screenshot every other day, a passport scan once, early, and, the last week,
			// pictures for a site every day.
			repeat(1 + random.nextInt(3)) {
				saved(
					firefox,
					"Downloads",
					"download.pdf",
					on(day, 9 + random.nextInt(10), random.nextInt(60)),
				)
			}
			if (day % 7 == 3)
				saved(firefox, "Documents/Receipts", "receipt.pdf", on(day, 20))
			if (day == 10)
				saved(firefox, "Documents/Taxes/2026", "return.pdf", on(day, 21))
			if (day == 52)
				saved(firefox, "Pictures/Trips/Iceland", "aurora.jpg", on(day, 22))
			if (day % 2 == 0)
				opened(firefox, "Pictures/Screenshots", "screenshot-$day.png", on(day, 14))
			if (day == 3)
				opened(firefox, "Documents/Passport", "passport.pdf", on(day, 11))
			if (day >= 53)
				opened(firefox, "Projects/site/assets", "banner-$day.png", on(day, 16))

			// GIMP: a screenshot edited and saved every other day or so; an export for the site every week.
			if (day % 3 != 1) {
				opened(gimp, "Pictures/Screenshots", "screenshot-$day.png", on(day, 15))
				saved(gimp, "Pictures/Edited", "edited.png", on(day, 15, 40))
			}
			if (day % 7 == 5)
				saved(gimp, "Projects/site/assets", "export.png", on(day, 17))

			// LibreOffice: the quarter's work every workday, the third quarter the first month and the fourth the
			// second; a letter every ten days.
			if (workday) {
				val quarter =
					if (day < 30)
						"Q3"
					else
						"Q4"

				opened(writer, "Documents/Work/$quarter", "report-${day / 7}.odt", on(day, 10))
				saved(writer, "Documents/Work/$quarter", "report.odt", on(day, 12))
			}
			if (day % 10 == 4)
				saved(writer, "Documents/Letters", "letter.odt", on(day, 19))

			// Visual Studio Code: prism's folder most mornings, the site's every week, an old game's once.
			if (workday)
				store.picked(listOf(folder("Projects/prism")), Choosing.Folder(), code, on(day, 8))
			if (day % 7 == 2)
				store.picked(listOf(folder("Projects/site")), Choosing.Folder(), code, on(day, 18))
			if (day == 20)
				store.picked(
					listOf(folder("Projects/old-game")),
					Choosing.Folder(),
					code,
					on(day, 21),
				)

			// Thunderbird: attachments saved twice a week, letters into their folder every week; and, the second
			// month, the quarter's reports sent.
			if (day % 7 in setOf(1, 4))
				saved(mail, "Downloads", "attachment.pdf", on(day, 9))
			if (day % 7 == 6)
				saved(mail, "Documents/Letters", "letter.pdf", on(day, 10))
			if (day >= 31 && day % 7 in setOf(0, 3))
				opened(mail, "Documents/Work/Q4", "report-${day / 7}.odt", on(day, 13))

			// The explorer: Downloads and prism every day; music some evenings, a different album each time; and
			// one afternoon, a walk through an old archive's every folder.
			went("Downloads", on(day, 8, 30))
			went("Projects/prism", on(day, 8, 45))
			if (random.nextInt(3) == 0)
				went("Music/Album ${random.nextInt(40)}", on(day, 21))
			if (day == 45)
				for (year in 2015..2019)
					for (month in 1..12)
						went("Archive/$year/$month", on(day, 15, month))

			store.flush(on(day, 23))
			evening(day, Lived(store, made.sortedByDescending { it.at }, on(day, 23)))
		}

		// Inkscape, once, at the very end: a logo for the site.
		saved(inkscape, "Projects/site/logo", "logo.svg", on(days - 1, 22))

		return Lived(store, made.sortedByDescending { it.at }, on(days - 1, 23))
	}

	/** The page [app] is shown by a picker that is [choosing], at the end of [life]. */
	private fun page(life: Lived, app: AppKey?, choosing: Choosing): RecentPage =
		RecentPage.of(
			life.made,
			life.store.read(),
			app,
			life.now,
			files = choosing !is Choosing.Folder,
			picks = picksOf(choosing),
			row = ::recentRowOf,
		)

	/** Every section of [page] as it is shown in groups, by title: each row's path from home, and when. */
	private fun sections(
		page: RecentPage,
		relevance: Relevance = Relevance(),
	): Map<String, List<Pair<String, Instant>>> {
		val shown = page.shown(Arrangement(recent = RecentArranged(relevance = relevance)))

		return shown.headings.associate { heading ->
			heading.kind.title to
				shown.rows.list.subList(heading.before, heading.before + heading.count).map { row ->
					home.relativize(row.path!!).toString() to shown.marks.getValue(row.id).at
				}
		}
	}

	private fun relevant(
		page: RecentPage,
		relevance: Relevance = Relevance(),
	): List<String> =
		sections(page, relevance)["Most relevant folders"].orEmpty().map { it.first }

	/** What [page] shows, as a person would read it: a section a heading, a row a line, and when it was. */
	private fun said(name: String, page: RecentPage, now: Instant): String =
		buildString {
			appendLine("== $name")
			for ((title, rows) in sections(page)) {
				appendLine("  $title")
				for ((path, at) in rows)
					appendLine("    %-42s %s".format(path, agoOf(at, now)))
			}
		}

	@Test
	fun twoMonthsOfPickers_AreShownAsAPersonWouldLookForThem() {
		// The evening Firefox saved the trip's pictures, once: where its picker to save is shown then.
		var thatEvening = emptyList<String>()
		val life =
			lived { day, so ->
				if (day == 52)
					thatEvening = relevant(page(so, firefox, Choosing.SaveAs("a.pdf")))
			}

		val pages =
			listOf(
				"Firefox, saving" to page(life, firefox, Choosing.SaveAs("a.pdf")),
				"Firefox, uploading" to page(life, firefox, Choosing.OneFile()),
				"LibreOffice, opening" to page(life, writer, Choosing.OneFile()),
				"LibreOffice, saving" to page(life, writer, Choosing.SaveAs("b.odt")),
				"Visual Studio Code, opening a folder" to page(life, code, Choosing.Folder()),
				"Thunderbird, saving" to page(life, mail, Choosing.SaveAs("c.pdf")),
				"Thunderbird, attaching" to page(life, mail, Choosing.Files()),
				"GIMP, opening" to page(life, gimp, Choosing.OneFile()),
				"Inkscape, saving" to page(life, inkscape, Choosing.SaveAs("d.svg")),
				"The explorer" to page(life, null, Choosing.Exploring()),
			)

		// What a person would see, said where the test's output is kept: to be read, and judged.
		println(pages.joinToString("\n") { (name, page) -> said(name, page, life.now) })

		// Every section is the newest first, whatever it is chosen by.
		for ((name, page) in pages)
			for ((title, rows) in sections(page))
				assertEquals(
					rows
						.map {
							it.second
						}.sortedDescending(),
					rows.map { it.second },
					"$name: $title",
				)

		val saving = relevant(pages[0].second)

		// Where Firefox saves: every day, and every week; not where it saved once, long ago, nor where it only opened
		// from, nor the archive walked through once. Where it saved once lately is there while it is lately: the
		// evening of it, and not a week of busy days after.
		assertTrue("Downloads" in saving, saving.toString())
		assertTrue("Documents/Receipts" in saving, saving.toString())
		assertTrue("Pictures/Trips/Iceland" in thatEvening, thatEvening.toString())
		assertFalse("Documents/Taxes/2026" in saving, saving.toString())
		assertFalse("Documents/Passport" in saving, saving.toString())
		assertTrue(saving.none { it.startsWith("Archive") }, saving.toString())
		assertEquals(Relevance().shown, saving.size)

		// Where it uploads from: the screenshots, and the site's pictures, a habit of the last week.
		val uploading = relevant(pages[1].second)

		assertTrue("Pictures/Screenshots" in uploading, uploading.toString())
		assertTrue("Projects/site/assets" in uploading, uploading.toString())
		assertFalse("Documents/Passport" in uploading, uploading.toString())

		// What Firefox saved is its recent files, which is what was lost before.
		assertTrue(
			sections(pages[0].second)
				.getValue("Recent files for this application")
				.any { it.first.startsWith("Downloads/") && it.first.endsWith("-download.pdf") },
		)

		// LibreOffice's work moved from the third quarter to the fourth: the fourth comes before the third, if the
		// third is there at all.
		val office = page(life, writer, Choosing.SaveAs("b.odt"))
		val scores =
			office.candidates.associate {
				home.relativize(it.row.path!!).toString() to it.score(Relevance(), life.now)
			}

		assertTrue("Documents/Work/Q4" in relevant(office))
		assertTrue(
			scores.getValue("Documents/Work/Q4") > (scores["Documents/Work/Q3"] ?: 0.0),
			scores.toString(),
		)
		assertTrue("Documents/Letters" in relevant(office))

		// Code's folders: prism's first among them, and the old game, picked once a month and more ago, not at all.
		val folders = relevant(pages[4].second)

		assertTrue("Projects/prism" in folders, folders.toString())
		assertTrue("Projects/site" in folders, folders.toString())
		assertFalse("Projects/old-game" in folders, folders.toString())

		// Thunderbird saves into Downloads and Letters, and attaches the quarter's reports.
		assertTrue(
			relevant(pages[5].second).containsAll(listOf("Downloads", "Documents/Letters")),
		)
		assertTrue("Documents/Work/Q4" in relevant(pages[6].second))

		// Inkscape, used once: where it was used is its most relevant, among those everyone uses.
		assertTrue("Projects/site/logo" in relevant(pages[8].second))

		// The explorer: nothing of the walk through the archive, however many folders it was.
		assertTrue(relevant(pages[9].second).none { it.startsWith("Archive") })
	}
}
