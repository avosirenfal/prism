package barion.index

import barion.index.query.Case
import barion.index.query.Depth
import barion.index.query.Flag
import barion.index.query.Group
import barion.index.query.QueryBuilder
import barion.index.query.RegexOn
import barion.index.query.Since
import barion.index.query.query
import barion.linux.Fd
import barion.linux.orThrow
import barion.linux.statx
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir

/**
 * Every example of the search language (docs/search.md), written in the DSL and run against a tree made here and
 * indexed by the real indexer: what each finds, entry for entry. The tree has something for each example to find and
 * something for it to pass over. A comment above each query is the text it will be parsed from.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchTest {
	/** The tree's folder, made for the class, and filled once before its tests. */
	private lateinit var root: Path

	private lateinit var index: Index

	/** The files of the tree, by their paths under [root]. */
	private val files =
		listOf(
			"q4-report.pdf",
			"v1.2 notes.txt",
			"report.pdf",
			"annual report.docx",
			"report-final.md",
			"thing.zip",
			"thing.tar.gz",
			"thing2.txt",
			".gitignore",
			".github/workflows.yml",
			".bashrc",
			"archive.tar.gz",
			"log.gz",
			"photo.jpg",
			"PHOTO2.JPG",
			"img_2024.jpg",
			"img_2025.jpeg",
			"img_1.jpg",
			"img_abcd.png",
			"[draft] plan.txt",
			"tax return 2023.pdf",
			"tax 2023.pdf",
			"README.md",
			"readme.txt",
			"img_*.txt",
			"Img_*copy.txt",
			"!weird*name.txt",
			"note!x.txt",
			"say \"hi\".txt",
			"-notes.txt",
			"movie.mkv",
			"clip.webp",
			"Ärger.txt",
			"funny/a.jpg",
			"funny/b.png",
			"funny/report.txt",
			"funny/sub/c.jpg",
			"funny/sub/deep/d.jpg",
			"funny/sub/deep/deeper/e.jpg",
			"funny/sub/deep/deeper/deepest/f.jpg",
			"work/2024/q1.xlsx",
			"work/2024/notes/x.md",
			"work/2023/w.txt",
			"other/work/2024/y.txt",
			"2024/z.txt",
			"2024/work/v.txt",
			"home/alice/Documents/a.pdf",
			"home/alice/Downloads/invoice.pdf",
			"home/alice/Downloads/sub/invoice2.pdf",
			"home/bob/Documents/b.pdf",
			"home/bob/Documents/old/c.pdf",
			"proj/kotlin.kt",
			"proj/src/keep.txt",
			"proj/src/main.kt",
			"a/x.txt",
			"b/y.txt",
			"c/z.txt",
			"old photos/p.jpg",
			"srv/data/file.txt",
			"mnt/photos/2023/a.jpg",
			"mnt/photos/b.jpg",
			// Left out by the default rules, with everything in them.
			".git/HEAD",
			"node_modules/left-pad/index.js",
			"proj/.gradle/caches/x.bin",
			".cache/thumbnails/t.png",
		)

	/** Links, by where they are and what they point to. */
	private val links =
		mapOf(
			"link-to-file" to "photo.jpg",
			"link-to-dir" to "funny",
			"broken" to "nowhere",
			"srv/data/link" to "../../photo.jpg",
		)

	/** Thursday, 12 November 2026, at ten in the morning, in UTC. */
	private val now = Instant.parse("2026-11-12T10:00:00Z")

	/** What the picker gave when: what `recent:` asks about, which is kept beside the index rather than in it. */
	private val usage: Usage by lazy {
		listOf(
			"report.pdf" to "2026-11-12T08:00:00Z",
			"funny/a.jpg" to "2026-11-11T20:00:00Z",
			"photo.jpg" to "2026-11-12T09:58:00Z",
			"q4-report.pdf" to "2026-11-06T12:00:00Z",
			"tax 2023.pdf" to "2026-10-01T12:00:00Z",
			"funny/sub/c.jpg" to "2026-11-10T12:00:00Z",
		).fold(Usage.NONE) { usage, (path, at) ->
			usage.picked(listOf(Where.of(root.resolve(path))), Instant.parse(at))
		}
	}

	private val context by lazy {
		SearchContext(
			now = now,
			zone = ZoneOffset.UTC,
			usage = usage,
		)
	}

	@BeforeAll
	fun made(
		@TempDir folder: Path,
	) {
		root = folder
		for (file in files)
			root.resolve(file).also { it.parent.createDirectories() }.writeText(file)
		for ((link, target) in links)
			root.resolve(link).createSymbolicLinkPointingTo(Path.of(target))

		// The tree's own device is said to be ZFS, for `mount:`.
		val dev = statx(Fd.CWD, root.toString()).orThrow().dev
		val indexer = Indexer(mounts = listOf(Mount(root, "zfs", dev.major, dev.minor)))

		index = Index.EMPTY.with(indexer.index(root))
	}

	/** What the query [build] makes finds, as paths under [root]. */
	private fun found(
		context: SearchContext = this.context,
		build: QueryBuilder.() -> Unit,
	): Set<String> =
		when (val searched = index.search(query(build), context)) {
			is Searched.Found ->
				searched.results
					.map {
						it.where.toString().removePrefix("$root/")
					}.toSet()
			is Searched.Refused -> error("refused: ${searched.why}")
		}

	private val jpgs =
		setOf(
			"photo.jpg",
			"PHOTO2.JPG",
			"img_2024.jpg",
			"img_1.jpg",
			"funny/a.jpg",
			"funny/sub/c.jpg",
			"funny/sub/deep/d.jpg",
			"funny/sub/deep/deeper/e.jpg",
			"funny/sub/deep/deeper/deepest/f.jpg",
			"old photos/p.jpg",
			"mnt/photos/2023/a.jpg",
			"mnt/photos/b.jpg",
		)

	private val pdfs =
		setOf(
			"q4-report.pdf",
			"report.pdf",
			"tax return 2023.pdf",
			"tax 2023.pdf",
			"home/alice/Documents/a.pdf",
			"home/alice/Downloads/invoice.pdf",
			"home/alice/Downloads/sub/invoice2.pdf",
			"home/bob/Documents/b.pdf",
			"home/bob/Documents/old/c.pdf",
		)

	/** The files the default rules let in. */
	private val kept =
		files
			.filterNot {
				listOf(".git/", "node_modules/", ".gradle/", ".cache/").any(it::contains)
			}.toSet()

	@Test
	fun aWordIsFoundInTheStemOrWithADotInTheWholeName() {
		// report
		assertEquals(
			setOf(
				"q4-report.pdf",
				"report.pdf",
				"annual report.docx",
				"report-final.md",
				"funny/report.txt",
			),
			found { +word("report") },
		)
		// notes: the stem of "v1.2 notes.txt" ends at its last dot
		assertEquals(
			setOf("v1.2 notes.txt", "-notes.txt", "work/2024/notes"),
			found {
				+word("notes")
			},
		)
		// report.pdf
		assertEquals(setOf("q4-report.pdf", "report.pdf"), found { +word("report.pdf") })
		// .git: a leading dot is the stem's, so this is the stem
		assertEquals(setOf(".gitignore", ".github"), found { +word(".git") })
		// archive: .tar.gz is one extension
		assertEquals(setOf("archive.tar.gz"), found { +word("archive") })
		// ärger: a letter outside ASCII is folded too
		assertEquals(setOf("Ärger.txt"), found { +word("ärger") })
	}

	@Test
	fun anchorsHoldATermToTheStartOrTheEnd() {
		// ^report
		assertEquals(
			setOf("report.pdf", "report-final.md", "funny/report.txt"),
			found {
				+word("report", start = true)
			},
		)
		// report$
		assertEquals(
			setOf("q4-report.pdf", "report.pdf", "annual report.docx", "funny/report.txt"),
			found { +word("report", end = true) },
		)
		// report.pdf$
		assertEquals(
			setOf("q4-report.pdf", "report.pdf"),
			found {
				+word("report.pdf", end = true)
			},
		)
		// =thing
		assertEquals(setOf("thing.zip", "thing.tar.gz"), found { +exactly("thing") })
		// =thing.zip
		assertEquals(setOf("thing.zip"), found { +exactly("thing.zip") })
		// =.bashrc
		assertEquals(setOf(".bashrc"), found { +exactly(".bashrc") })
	}

	@Test
	fun aGlobMatchesTheWholeStemOrName() {
		// *.jpg
		assertEquals(jpgs, found { +word("*.jpg") })
		// *.{jpg,png}
		assertEquals(
			jpgs + setOf("img_abcd.png", "funny/b.png"),
			found { +word("*.{jpg,png}") },
		)
		// img_????
		assertEquals(
			setOf("img_2024.jpg", "img_2025.jpeg", "img_abcd.png"),
			found {
				+word("img_????")
			},
		)
		// img_[0-9]*
		assertEquals(
			setOf("img_2024.jpg", "img_2025.jpeg", "img_1.jpg"),
			found {
				+word("img_[0-9]*")
			},
		)
		// \[draft\]*
		assertEquals(setOf("[draft] plan.txt"), found { +word("\\[draft\\]*") })
	}

	@Test
	fun quotedAndRawTermsAndTheirCases() {
		// "tax return"
		assertEquals(setOf("tax return 2023.pdf"), found { +word("tax return") })
		// "tax *.pdf"
		assertEquals(setOf("tax return 2023.pdf", "tax 2023.pdf"), found { +word("tax *.pdf") })
		// c"README"
		assertEquals(setOf("README.md"), found { +word("README", case = Case.Sensitive) })

		// i"readme", with the toggle for the whole search on
		val sensitive = context.copy(caseSensitive = true)

		assertEquals(setOf("readme.txt"), found(sensitive) { +word("readme") })
		assertEquals(
			setOf("README.md", "readme.txt"),
			found(sensitive) {
				+word("readme", case = Case.Insensitive)
			},
		)
		// r"img_*": nothing in it special
		assertEquals(setOf("img_*.txt", "Img_*copy.txt"), found { +raw("img_*") })
		// rc"Img_*", and cr"Img_*", which is the same
		assertEquals(setOf("Img_*copy.txt"), found { +raw("Img_*", Case.Sensitive) })
		// =r"!weird*name"
		assertEquals(
			setOf("!weird*name.txt"),
			found {
				+raw("!weird*name", start = true, end = true)
			},
		)
		// r"!x"$
		assertEquals(setOf("note!x.txt"), found { +raw("!x", end = true) })
		// r#"say "hi""#
		assertEquals(setOf("say \"hi\".txt"), found { +raw("say \"hi\"") })
		// "-notes"
		assertEquals(setOf("-notes.txt"), found { +word("-notes") })
	}

	@Test
	fun pathsMatchTheEndOfAPathOrTheWholeOfIt() {
		// funny/*.jpg
		assertEquals(setOf("funny/a.jpg"), found { +path("funny/*.jpg") })

		val underFunny =
			setOf(
				"funny/a.jpg",
				"funny/sub/c.jpg",
				"funny/sub/deep/d.jpg",
				"funny/sub/deep/deeper/e.jpg",
				"funny/sub/deep/deeper/deepest/f.jpg",
			)

		// funny/** *.jpg
		assertEquals(
			underFunny,
			found {
				+path("funny/**")
				+word("*.jpg")
			},
		)
		// funny/**.jpg
		assertEquals(underFunny, found { +path("funny/**.jpg") })
		// funny/rep
		assertEquals(setOf("funny/report.txt"), found { +path("funny/rep") })
		// funny/* =report
		assertEquals(
			setOf("funny/report.txt"),
			found {
				+path("funny/*")
				+exactly("report")
			},
		)
		// work/2024/
		assertEquals(
			setOf(
				"work/2024/q1.xlsx",
				"work/2024/notes",
				"work/2024/notes/x.md",
				"other/work/2024/y.txt",
			),
			found { +path("work/2024/") },
		)
		// /mnt/photos/
		assertEquals(
			setOf("mnt/photos/2023", "mnt/photos/2023/a.jpg", "mnt/photos/b.jpg"),
			found { +path("$root/mnt/photos/") },
		)
		// /home/*/Documents/*.pdf
		assertEquals(
			setOf("home/alice/Documents/a.pdf", "home/bob/Documents/b.pdf"),
			found { +path("$root/home/*/Documents/*.pdf") },
		)
	}

	@Test
	fun theHomeFolderAndAliasesAreWhereTheContextSays() {
		val named =
			context.copy(
				home = root.resolve("home/alice"),
				aliases = mapOf("reports" to listOf(root.resolve("work"), root.resolve("home/bob"))),
			)

		// ~/Downloads/
		assertEquals(
			setOf(
				"home/alice/Downloads/invoice.pdf",
				"home/alice/Downloads/sub",
				"home/alice/Downloads/sub/invoice2.pdf",
			),
			found(named) { +path("~/Downloads/") },
		)
		// ~/Downloads/inv
		assertEquals(
			setOf("home/alice/Downloads/invoice.pdf"),
			found(named) {
				+path("~/Downloads/inv")
			},
		)
		// ~reports/ *.pdf
		assertEquals(
			setOf("home/bob/Documents/b.pdf", "home/bob/Documents/old/c.pdf"),
			found(named) {
				+path("~reports/")
				+word("*.pdf")
			},
		)
		// ~nowhere/: an alias not defined is said, not taken as nothing
		assertEquals(
			Searched.Refused("“~nowhere” is not a folder you have named"),
			index.search(query { +path("~nowhere/") }, named),
		)
	}

	@Test
	fun regexesAreFoundInTheNameTheStemOrThePath() {
		// r/^img_\d{4}\.jpe?g$/
		assertEquals(
			setOf("img_2024.jpg", "img_2025.jpeg"),
			found {
				+regex("^img_\\d{4}\\.jpe?g$")
			},
		)
		// r/^img_\d{4}$/s
		assertEquals(
			setOf("img_2024.jpg", "img_2025.jpeg"),
			found {
				+regex("^img_\\d{4}$", RegexOn.Stem)
			},
		)
		// r/photos\/20\d\d\//p
		assertEquals(
			setOf("mnt/photos/2023/a.jpg"),
			found {
				+regex("photos/20\\d\\d/", RegexOn.Path)
			},
		)
		// r/README/c
		assertEquals(setOf("README.md"), found { +regex("README", case = Case.Sensitive) })
		// r/readme/i, with the toggle on
		assertEquals(
			setOf("README.md", "readme.txt"),
			found(
				context.copy(caseSensitive = true),
			) { +regex("readme", case = Case.Insensitive) },
		)
		// r/readme/pc
		assertEquals(
			setOf("readme.txt"),
			found {
				+regex("readme", RegexOn.Path, Case.Sensitive)
			},
		)
	}

	@Test
	fun dirFindsWhatIsUnderAFolderWithinADistance() {
		// dir:funny *.jpg
		assertEquals(
			jpgs.filter { it.startsWith("funny/") }.toSet(),
			found {
				+dir("funny")
				+word("*.jpg")
			},
		)
		// dir:funny:1 type:image
		assertEquals(
			setOf("funny/a.jpg", "funny/b.png"),
			found {
				+dir("funny", Depth.exactly(1))
				+type(Group.Image)
			},
		)
		// dir:funny:3-5 *.jpg
		assertEquals(
			setOf(
				"funny/sub/deep/d.jpg",
				"funny/sub/deep/deeper/e.jpg",
				"funny/sub/deep/deeper/deepest/f.jpg",
			),
			found {
				+dir("funny", Depth.between(3, 5))
				+word("*.jpg")
			},
		)
		// dir:funny:2- is:dir
		assertEquals(
			setOf("funny/sub/deep", "funny/sub/deep/deeper", "funny/sub/deep/deeper/deepest"),
			found {
				+dir("funny", Depth.atLeast(2))
				+isA(Flag.Dir)
			},
		)
		// dir:work dir:2024: nested, either order
		assertEquals(
			setOf(
				"work/2024/q1.xlsx",
				"work/2024/notes",
				"work/2024/notes/x.md",
				"other/work/2024/y.txt",
				"2024/work/v.txt",
			),
			found {
				+dir("work")
				+dir("2024")
			},
		)
		// dir:work/2024
		assertEquals(
			setOf(
				"work/2024/q1.xlsx",
				"work/2024/notes",
				"work/2024/notes/x.md",
				"other/work/2024/y.txt",
			),
			found { +dir("work/2024") },
		)
		// dir:{a,b}
		assertEquals(setOf("a/x.txt", "b/y.txt"), found { +dir("{a,b}") })
		// dir:"old photos"
		assertEquals(setOf("old photos/p.jpg"), found { +dir("old photos") })

		// dir:~proj ^k
		val proj = context.copy(aliases = mapOf("proj" to listOf(root.resolve("proj"))))

		assertEquals(
			setOf("proj/kotlin.kt", "proj/src/keep.txt"),
			found(proj) {
				+dir("~proj")
				+word("k", start = true)
			},
		)
	}

	@Test
	fun extensionsAndGroups() {
		// ext:pdf
		assertEquals(pdfs, found { +ext("pdf") })
		// ext:gz
		assertEquals(setOf("archive.tar.gz", "thing.tar.gz", "log.gz"), found { +ext("gz") })
		// ext:tar.gz
		assertEquals(setOf("archive.tar.gz", "thing.tar.gz"), found { +ext("tar.gz") })
		// ext:{jpg,png,webp}
		assertEquals(
			jpgs + setOf("img_abcd.png", "funny/b.png", "clip.webp"),
			found {
				+ext("{jpg,png,webp}")
			},
		)
		// ext:/jpe?g/i
		assertEquals(jpgs + "img_2025.jpeg", found { +extRegex("jpe?g", Case.Insensitive) })

		// type:image
		val images = jpgs + setOf("img_2025.jpeg", "img_abcd.png", "funny/b.png", "clip.webp")

		assertEquals(images, found { +type(Group.Image) })
		// type:image -ext:jpg
		assertEquals(
			setOf("img_2025.jpeg", "img_abcd.png", "funny/b.png", "clip.webp"),
			found {
				+type(Group.Image)
				-ext("jpg")
			},
		)
	}

	@Test
	fun isTellsFilesFoldersLinksAndHiddenApart() {
		val regular = kept + "Ärger.txt"
		val linksToFiles = setOf("link-to-file", "srv/data/link")

		// is:file
		assertEquals(regular + linksToFiles, found { +isA(Flag.File) })
		// is:file -is:symlink
		assertEquals(
			regular,
			found {
				+isA(Flag.File)
				-isA(Flag.Symlink)
			},
		)
		// is:dir is:symlink
		assertEquals(
			setOf("link-to-dir"),
			found {
				+isA(Flag.Dir)
				+isA(Flag.Symlink)
			},
		)
		// is:symlink: the broken one too
		assertEquals(links.keys, found { +isA(Flag.Symlink) })
		// is:{file,symlink}
		assertEquals(regular + links.keys, found { +isA(Flag.File, Flag.Symlink) })
		// /srv/data/ -is:symlink
		assertEquals(
			setOf("srv/data/file.txt"),
			found {
				+path("$root/srv/data/")
				-isA(Flag.Symlink)
			},
		)
		// is:hidden
		assertEquals(setOf(".gitignore", ".github", ".bashrc"), found { +isA(Flag.Hidden) })
	}

	@Test
	fun recentIsWhatThePickerGaveSinceThen() {
		// recent:today
		assertEquals(setOf("report.pdf", "photo.jpg"), found { +recent(Since.Today) })
		// recent:yesterday
		assertEquals(
			setOf("report.pdf", "photo.jpg", "funny/a.jpg"),
			found {
				+recent(Since.Yesterday)
			},
		)
		// recent:wednesday: today is Thursday
		assertEquals(
			setOf("report.pdf", "photo.jpg", "funny/a.jpg"),
			found {
				+recent(Since.Weekday(DayOfWeek.WEDNESDAY))
			},
		)
		// recent:thursday: today
		assertEquals(
			setOf("report.pdf", "photo.jpg"),
			found {
				+recent(Since.Weekday(DayOfWeek.THURSDAY))
			},
		)
		// recent:5m
		assertEquals(setOf("photo.jpg"), found { +recent(Since.Within(5.minutes)) })
		// recent:24h
		assertEquals(
			setOf("report.pdf", "photo.jpg", "funny/a.jpg"),
			found {
				+recent(Since.Within(24.hours))
			},
		)
		// recent:1w ext:pdf
		assertEquals(
			setOf("report.pdf", "q4-report.pdf"),
			found {
				+recent(Since.Within(7.days))
				+ext("pdf")
			},
		)
		// dir:funny recent:1w
		assertEquals(
			setOf("funny/a.jpg", "funny/sub/c.jpg"),
			found {
				+dir("funny")
				+recent(Since.Within(7.days))
			},
		)
	}

	@Test
	fun mountIsTheFileSystemAnEntryIsOn() {
		// mount:zfs
		assertEquals(index.entries().size, found { +mount("zfs") }.size)
		// -mount:ntfs *.mkv
		assertEquals(
			setOf("movie.mkv"),
			found {
				-mount("ntfs")
				+word("*.mkv")
			},
		)
		// mount:ntfs
		assertEquals(emptySet<String>(), found { +mount("ntfs") })
	}

	@Test
	fun termsAreNeverOrd() {
		// ext:jpg ext:png
		assertEquals(
			emptySet<String>(),
			found {
				+ext("jpg")
				+ext("png")
			},
		)
		// -ext:jpg -ext:png
		assertEquals(
			setOf("clip.webp", "img_2025.jpeg"),
			found {
				-ext("jpg")
				-ext("png")
				+type(Group.Image)
			},
		)
	}

	@Test
	fun whatTheRulesLeaveOutIsNotIndexed() {
		val everything = found { +word("*") }

		assertEquals(
			emptySet<String>(),
			everything
				.filter {
					listOf(".git", "node_modules", ".gradle", ".cache").any { left ->
						left in
							it.split('/')
					}
				}.toSet(),
		)
		assertEquals(true, "proj/src/main.kt" in everything)
	}
}
