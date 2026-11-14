package prism.browser

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces
import prism.preferences.Kind

/**
 * The archives that ship with prism (docs/menus.md), each kind through each program that can do it: a kind is read as
 * the first of its ways whose programs are installed, so here each way is made the only one in turn, by saying which
 * programs are there, and run against a real archive of that kind. A way whose programs this machine has not got is
 * not run, and its test says so by not being among them.
 */
class ArchivesTest {
	private companion object {
		/** Enough entries, with names long enough, for an archive's listing to be longer than 128 KiB. */
		const val MANY = 2000
	}

	@TempDir
	lateinit var root: Path

	@TempDir
	lateinit var bin: Path

	private val real = SystemWorld(Path.of("/"), null)

	private fun has(vararg programs: String) = programs.all(real::installed)

	private fun sh(script: String, folder: Path = root) {
		val process =
			ProcessBuilder(
				"sh",
				"-c",
				script,
			).directory(folder.toFile()).inheritIO().start()

		assertEquals(0, process.waitFor(), script)
	}

	/** The files every archive here is made of. */
	private fun files(): Path =
		root.resolve("stage").also {
			it.resolve("src/deep").createDirectories()
			it.resolve("with space").createDirectories()
			it.resolve("src/main.c").writeText("int main;")
			it.resolve("src/deep/a.txt").writeText("deep")
			it.resolve("with space/my file.txt").writeText("spaced")
			it.resolve("README.md").writeText("read me")
		}

	/** A kind of archive: a file of it, what makes one here, and the programs of each way of reading it, as [place]. */
	private class Kind(
		val file: String,
		val make: String,
		val makers: List<String>,
		val ways: List<Set<String>>,
		val place: String,
		/** The programs with which entries can be removed from one and files added, where any can. */
		val changing: List<Set<String>> = emptyList(),
	)

	private val seven =
		setOf("7zz", "7z", "7za", "7zr")
			.filter {
				real.installed(it)
			}.take(1)
			.toSet()

	private val kinds =
		listOf(
			Kind(
				"code.tar.gz",
				"tar -czf ../code.tar.gz src 'with space' README.md",
				listOf("tar", "gzip"),
				listOf(setOf("bsdtar"), setOf("tar")),
				"archive.tar",
			),
			Kind(
				"code.tzst",
				"tar --zstd -cf ../code.tzst src 'with space' README.md",
				listOf("tar", "zstd"),
				listOf(setOf("bsdtar"), setOf("tar")),
				"archive.tar",
			),
			Kind(
				"code.tar",
				"tar -cf ../code.tar src 'with space' README.md",
				listOf("tar"),
				listOf(setOf("bsdtar"), setOf("tar")),
				"archive.tar",
				changing = listOf(setOf("tar")),
			),
			Kind(
				"code.zip",
				"zip -qr ../code.zip src 'with space' README.md",
				listOf("zip"),
				listOf(setOf("unzip", "zipinfo"), seven, setOf("bsdtar")),
				"archive.zip",
				changing = listOf(setOf("unzip", "zipinfo", "zip"), seven),
			),
			Kind(
				"code.7z",
				"7z a -bso0 -bsp0 ../code.7z src 'with space' README.md",
				listOf("7z"),
				listOf(seven, setOf("bsdtar")),
				"archive.7z",
				changing = listOf(seven),
			),
			Kind(
				"code.rar",
				"rar a -r -idq ../code.rar src 'with space' README.md",
				listOf("rar"),
				listOf(setOf("unrar"), seven, setOf("bsdtar")),
				"archive.rar",
				changing = listOf(setOf("unrar", "rar")),
			),
			Kind(
				"code.iso",
				"genisoimage -quiet -R -J -o ../code.iso .",
				listOf("genisoimage"),
				listOf(seven, setOf("bsdtar")),
				"archive.disk",
			),
		)

	/** An explorer on [folder], with what ships read as if only [programs] were installed. */
	private fun exploring(folder: Path, programs: Set<String>): BrowserState {
		val services =
			Services(JvmDisk(trash = Trash(bin)), home = root, temps = Temps(root.resolve("tmp")))

		this.services = services

		return step(
			opened(
				Choosing.Exploring(),
				folder,
				root,
				sidebarOf(XdgPlaces(), root),
			).settled(services),
			Answer.Configured(Actions.read(listOf(Actions.SHIPPED)) { it in programs }),
		).state
	}

	private lateinit var services: Services

	private fun BrowserState.then(input: Input): BrowserState =
		step(this, input).settled(services)

	private fun BrowserState.row(name: String): Row = rows.list.single { it.name == name }

	private fun BrowserState.names(): Set<String> =
		rows.list.mapTo(LinkedHashSet()) {
			it.name
		}

	/** A world in which only [programs] are installed: what a menu's conditions are decided by here. */
	private fun worldOf(programs: Set<String>): World =
		object : World by real {
			override fun installed(program: String): Boolean = program in programs
		}

	private fun BrowserState.menu(
		programs: Set<String>,
		vararg names: String,
	): Menu<BrowserEvent> =
		contextMenuOf(
			this,
			if (names.isEmpty())
				Target.Folder
			else
				Target.Rows(names.map { row(it) }),
			world = worldOf(programs),
		)

	private fun Menu<BrowserEvent>.labels(): List<String> =
		entries.flatMap {
			when (it) {
				is MenuEntry.Item -> listOf(it.label)
				is MenuEntry.Submenu -> it.menu.labels().map { label -> "${it.label} ▸ $label" }
				MenuEntry.Separator -> emptyList()
			}
		}

	private fun Menu<BrowserEvent>.event(label: String): BrowserEvent =
		entries.firstNotNullOf {
			when (it) {
				is MenuEntry.Item -> it.action.takeIf { _ -> it.label == label }
				is MenuEntry.Submenu ->
					label
						.removePrefix("${it.label} ▸ ")
						.takeIf { rest ->
							rest != label
						}?.let { rest -> it.menu.event(rest) }
				MenuEntry.Separator -> null
			}
		}

	/** The archive named, opened as its place, in this window: what "Open" on it does, but for the window. */
	private fun BrowserState.opening(programs: Set<String>, name: String): BrowserState {
		val open = menu(programs, name).event("Open") as BrowserEvent.Did

		assertTrue(open.action.default)

		return then(
			open.copy(
				action =
					open.action.copy(
						deed = (open.action.deed as Deed.Show).copy(window = false),
					),
			),
		)
	}

	/** Everything under [folder], as paths from it, folders with a `/` after them. */
	private fun under(folder: Path): Set<String> =
		Files.walk(folder).use { all ->
			all
				.filter { it != folder }
				.map { folder.relativize(it).toString() + if (Files.isDirectory(it)) "/" else "" }
				.toList()
				.toSet()
		}

	@TestFactory
	fun eachKindOfArchiveIsReadByEachProgramThatCan(): List<DynamicTest> =
		kinds
			.filter { has(*it.makers.toTypedArray()) }
			.flatMap { kind ->
				kind.ways.filter { it.isNotEmpty() && has(*it.toTypedArray()) }.map {
					kind to
						it
				}
			}.map { (kind, programs) ->
				dynamicTest("${kind.file} with ${programs.joinToString()}") {
					val work = Files.createTempDirectory(root, "case")

					root = work
					sh(kind.make, files())

					val archive = work.resolve(kind.file)
					val folder = exploring(work, programs)

					// ---- from the folder it is in: opening it is looking into it, and it is extracted whole
					val labels = folder.menu(programs, kind.file).labels()

					assertTrue(
						labels.containsAll(
							listOf("Open", "Extract here", "Extract into a folder", "Extract to…"),
						),
						"$labels",
					)
					assertFalse("View contents" in labels)

					val into =
						folder.then(
							folder.menu(programs, kind.file).event("Extract into a folder"),
						)

					val stem = stemOf(kind.file)

					assertEquals(
						setOf(
							"README.md",
							"src/",
							"src/main.c",
							"src/deep/",
							"src/deep/a.txt",
							"with space/",
							"with space/my file.txt",
						),
						under(work.resolve(stem)),
					)
					assertEquals("int main;", work.resolve("$stem/src/main.c").readText())
					assertEquals(RowId(stem), into.cursor?.at)
					assertNull(into.notice)

					// Nothing there is written over: what was changed since stays as it was changed.
					work.resolve("$stem/README.md").writeText("mine now")
					into.then(into.menu(programs, kind.file).event("Extract into a folder"))
					assertEquals("mine now", work.resolve("$stem/README.md").readText())

					// ---- in its window: listed, with what each entry is and how large
					val top = folder.opening(programs, kind.file)

					assertNull(top.notice)
					assertEquals(setOf("src", "with space", "README.md"), top.names(), "${top.listing}")
					assertTrue(top.row("src").isFolder)
					assertEquals(7L, top.row("README.md").size?.value)
					assertNotNull(top.row("README.md").modified)
					assertNull(top.row("README.md").path)

					val src = top.then(BrowserEvent.Activated(top.row("src").id))

					assertEquals(setOf("main.c", "deep"), src.names())
					assertEquals(
						listOf("a.txt"),
						src.then(BrowserEvent.Activated(src.row("deep").id)).names().toList(),
					)

					// ---- entries brought out are the entries: a file, and a folder with what is in it, by their own names
					val out = work.resolve("out").createDirectories()

					exploring(out, programs).then(
						BrowserEvent.DroppedEntries(
							EntriesOut.of(src.carrying(listOf(src.row("main.c"), src.row("deep")))!!)!!,
							DropSpot.Here,
						),
					)
					assertEquals(setOf("main.c", "deep/", "deep/a.txt"), under(out))
					assertEquals("int main;", out.resolve("main.c").readText())
					assertEquals("deep", out.resolve("deep/a.txt").readText())

					// One found by a search of the archive is brought out as itself too, wherever it sits.
					val found =
						top
							.then(BrowserEvent.OpenedSearch)
							.then(BrowserEvent.SearchTyped("my file"))
							.then(BrowserEvent.ShowedResults)
					val other = work.resolve("other").createDirectories()

					assertEquals(setOf("my file.txt"), found.names())
					exploring(
						other,
						programs,
					).then(BrowserEvent.DroppedCarried(found.carrying(found.rows.list)!!, DropSpot.Here))
					assertEquals(setOf("my file.txt"), under(other))
					assertEquals("spaced", other.resolve("my file.txt").readText())

					// What the commands worked in is gone once they are done.
					assertEquals(
						emptyList<Path>(),
						work
							.resolve("tmp")
							.takeIf {
								it.exists()
							}?.listDirectoryEntries()
							.orEmpty(),
					)

					// ---- everything, to a folder asked for
					val asked = step(top, top.menu(programs).event("Extract all to…"))
					val all = work.resolve("all").createDirectories()

					assertInstanceOf(
						Request.PickFolder::class.java,
						(asked.effects.single() as Effect.Ask).request,
					)
					asked.state.then(BrowserEvent.Answered(all))
					assertEquals("spaced", all.resolve("with space/my file.txt").readText())

					// ---- changed, where this kind can be and these programs can do it
					val changes = kind.changing.any { it == programs || programs.containsAll(it) }

					assertEquals(
						changes,
						"Remove from archive" in top.menu(programs, "README.md").labels(),
						"remove",
					)

					if (changes) {
						val removed =
							top
								.then(top.menu(programs, "src", "README.md").event("Remove from archive"))
								.then(BrowserEvent.Confirmed)

						assertNull(removed.notice)
						assertEquals(setOf("with space"), removed.names())

						work.resolve("new.txt").writeText("new")

						val added =
							removed.then(
								BrowserEvent.Dropped(
									listOf(work.resolve("new.txt")),
									DropSpot.Here,
									Transfer.Copy,
								),
							)

						assertNull(added.notice)
						assertEquals(setOf("with space", "new.txt"), added.names())
					}
					assertTrue(archive.exists())
				}
			}

	/**
	 * Each kind listed by each program that can, an archive whose listing is longer than one word of a program's
	 * environment may be (128 KiB), where the environment exports names the listing scripts use for their own (a Nix
	 * shell exports `out`): the listing is the script's own, and never reaches what it runs.
	 */
	@TestFactory
	fun aLongListingIsReadWhateverTheEnvironmentExports(): List<DynamicTest> =
		kinds
			.filter { has(*it.makers.toTypedArray()) }
			.flatMap { kind ->
				kind.ways.filter { it.isNotEmpty() && has(*it.toTypedArray()) }.map {
					kind to
						it
				}
			}.map { (kind, programs) ->
				dynamicTest("${kind.file} with ${programs.joinToString()}") {
					val work = Files.createTempDirectory(root, "long")

					val stage =
						work.resolve("stage").also { stage ->
							stage
								.resolve(
									"with space",
								).createDirectories()
								.resolve("my file.txt")
								.writeText("spaced")
							stage.resolve("README.md").writeText("read me")
							stage.resolve("src").createDirectories().let { src ->
								repeat(MANY) {
									src
										.resolve(
											"an entry with a long enough name to fill a listing $it.txt",
										).writeText("")
								}
							}
						}

					sh(kind.make, stage)

					val type = exploring(work, programs).actions.places.getValue(kind.place)

					val nixShell =
						Commands { invocation, watch ->
							ProcessCommands.run(
								invocation.copy(
									environment =
										invocation.environment + ("out" to "/nix/store/shell-out") + ("listed" to "x"),
								),
								watch,
							)
						}

					val listing =
						listingOf(
							Placed(
								type,
								mapOf("archive" to work.resolve(kind.file).toString()),
							),
							nixShell,
							work,
						)

					assertTrue(listing is PlaceListing.Listed, "$listing")
					assertTrue(
						(listing as PlaceListing.Listed).tree.entries.size >= MANY,
						"${listing.tree.entries.size}",
					)
				}
			}

	@TestFactory
	fun archivesAreMadeByEachProgramThatCan(): List<DynamicTest> =
		listOf(
			"As a .zip…" to setOf("zip"),
			"As a .zip…" to seven,
			"As a .7z…" to seven,
			"As a .rar…" to setOf("rar"),
			"As a .tar.gz…" to setOf("bsdtar"),
			"As a .tar.gz…" to setOf("tar", "gzip"),
			"As a .tar.zst…" to setOf("bsdtar"),
			"As a .tar.zst…" to setOf("tar", "zstd"),
			"As a .tar.xz…" to setOf("bsdtar"),
			"As a .tar.xz…" to setOf("tar", "xz"),
		).filter { (_, programs) -> programs.isNotEmpty() && has(*programs.toTypedArray()) }
			.map { (label, programs) ->
				dynamicTest("$label with ${programs.joinToString()}") {
					val work = Files.createTempDirectory(root, "make")

					root = work
					work.resolve("src/deep").createDirectories()
					work.resolve("src/main.c").writeText("int main;")
					work.resolve("a b.txt").writeText("spaced")

					val state = exploring(work, programs)

					val naming =
						state.then(
							state.menu(programs, "src", "a b.txt").event("Compress ▸ $label"),
						)

					assertEquals(Notice.Asking("Archive name", "src"), naming.notice)

					val made =
						naming
							.then(
								BrowserEvent.AnswerTyped("packed"),
							).then(BrowserEvent.Confirmed)

					val file = "packed." + label.removePrefix("As a .").removeSuffix("…")

					assertNull(made.notice)
					assertEquals(RowId(file), made.cursor?.at)

					// What was made is an archive of what was chosen: read back by whatever reads its kind here.
					val readers =
						programs +
							setOf(
								"bsdtar",
								"unzip",
								"zipinfo",
								"unrar",
								"tar",
								"gzip",
								"zstd",
								"xz",
							).filter(real::installed)

					val inside = exploring(work, readers).opening(readers, file)

					assertEquals(setOf("src", "a b.txt"), inside.names(), "${inside.listing}")
				}
			}

	@TestFactory
	fun aFileCompressedByItselfIsDecompressedBesideItselfAndNotLookedInto(): List<DynamicTest> =
		listOf(
			Triple("notes.txt.gz", "gzip -k notes.txt", setOf("gzip")),
			Triple("notes.txt.gz", "gzip -k notes.txt", seven),
			Triple("notes.txt.zst", "zstd -q notes.txt", setOf("zstd")),
			Triple("notes.txt.zstd", "zstd -q notes.txt -o notes.txt.zstd", setOf("zstd")),
			Triple("notes.txt.xz", "xz -k notes.txt", setOf("xz")),
			Triple("notes.txt.bz2", "bzip2 -k notes.txt", setOf("bzip2")),
			Triple("notes.txt.lz4", "lz4 -q notes.txt notes.txt.lz4", setOf("lz4")),
			Triple("notes.txt.lz", "lzip -k notes.txt", setOf("lzip")),
			Triple("notes.txt.br", "brotli -k notes.txt", setOf("brotli")),
		).filter { (_, _, programs) -> programs.isNotEmpty() && has(*programs.toTypedArray()) }
			.filter { (_, make, _) -> has(make.substringBefore(' ')) }
			.map { (file, make, programs) ->
				dynamicTest("$file with ${programs.joinToString()}") {
					val work = Files.createTempDirectory(root, "one")

					root = work
					work.resolve("notes.txt").writeText("some notes")
					sh(make, work)
					Files.delete(work.resolve("notes.txt"))

					val state = exploring(work, programs)
					val labels = state.menu(programs, file).labels()

					// It is not an archive: there is nothing to look into, and opening it is its application's.
					assertTrue("Decompress" in labels, "$labels")
					assertFalse("Extract here" in labels)
					assertInstanceOf(
						BrowserEvent.Picked::class.java,
						state.menu(programs, file).event("Open"),
					)

					val done = state.then(state.menu(programs, file).event("Decompress"))

					assertNull(done.notice)
					assertEquals("some notes", work.resolve("notes.txt").readText())
					// What it was made from is still there.
					assertTrue(work.resolve(file).exists())
				}
			}

	@Test
	fun aCompressedTarIsAnArchiveAndNotAFileToDecompress() {
		val state =
			exploring(
				root.also {
					it.resolve("a.tar.gz").writeText("")
					it.resolve("b.tgz").writeText("")
				},
				setOf("tar", "gzip"),
			)

		for (file in listOf("a.tar.gz", "b.tgz")) {
			val labels = state.menu(setOf("tar", "gzip"), file).labels()

			assertTrue("Extract here" in labels, file)
			assertFalse("Decompress" in labels, file)
		}
	}

	@Test
	fun withNoneOfItsProgramsAnArchiveIsAFileLikeAnyOther() {
		root.resolve("code.7z").writeText("not really")
		root.resolve("code.zip").writeText("not really")

		val none = exploring(root, emptySet())

		for (file in listOf("code.7z", "code.zip")) {
			val menu = none.menu(emptySet(), file)

			// Opened with its application, as any file is; nothing of extracting; a default can be set for its type.
			assertInstanceOf(BrowserEvent.Picked::class.java, menu.event("Open"))
			assertEquals(
				emptyList<String>(),
				menu.labels().filter {
					it.startsWith("Extract") ||
						it.startsWith("Compress")
				},
			)
			assertEquals(
				Request.OpenWith(listOf(root.resolve(file)), settable = true),
				(menu.event("Open with…") as BrowserEvent.Requested).request,
			)
		}
		assertTrue(
			none.actions.unavailable.containsAll(
				listOf("archive.7z", "archive.7z.open", "archive.zip.extract"),
			),
			"${none.actions.unavailable}",
		)
		assertEquals(emptyList<Broken>(), none.actions.broken)

		// With a program that reads zips and none that reads 7z, the zip is opened by prism and the 7z is not.
		val some = exploring(root, setOf("unzip", "zipinfo"))

		assertInstanceOf(
			BrowserEvent.Did::class.java,
			some.menu(setOf("unzip", "zipinfo"), "code.zip").event("Open"),
		)
		assertInstanceOf(
			BrowserEvent.Picked::class.java,
			some.menu(setOf("unzip", "zipinfo"), "code.7z").event("Open"),
		)
		// Another application can be chosen for it, this once; the default for its type is not that window's to set.
		assertEquals(
			Request.OpenWith(listOf(root.resolve("code.zip")), settable = false),
			(
				some
					.menu(
						setOf("unzip", "zipinfo"),
						"code.zip",
					).event("Open with…") as BrowserEvent.Requested
			).request,
		)
	}

	@Test
	fun aCommandThatFailsSaysHowItEndedAndWhatItWrote() {
		root.resolve("broken.zip").writeText("this is not a zip archive at all")

		val programs = setOf("unzip", "zipinfo")

		if (!has(*programs.toTypedArray()))
			return

		val state = exploring(root, programs)
		val failed = state.then(state.menu(programs, "broken.zip").event("Extract here"))
		val notice = failed.notice as Notice.Failed

		assertEquals("“Extract here” failed", notice.doing)
		assertTrue("Exited with status" in notice.why, notice.why)
		assertTrue("broken.zip" in notice.why, notice.why)
		assertEquals(emptyList<Work>(), failed.running)

		// A place that cannot be listed says the same where its listing would be.
		val listing = state.opening(programs, "broken.zip").listing as Listing.Unreadable

		assertTrue("Exited with status" in listing.why, listing.why)
	}
}
