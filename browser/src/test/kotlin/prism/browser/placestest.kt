package prism.browser

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces

/**
 * Places (docs/menus.md): what a command prints read into a tree, rows that are files on disk told from rows only the
 * place knows, and the archive tool that ships with prism, made of nothing but declared places and actions, run here
 * against real archives with the machine's own `tar`, `zip` and `unzip`: listed, gone about, extracted (by the menu,
 * by a drop, by a paste, and to be opened), added to, and removed from.
 */
class PlacesTest {
	@TempDir
	lateinit var root: Path

	@TempDir
	lateinit var bin: Path

	private val services by lazy { Services(JvmDisk(trash = Trash(bin)), home = root) }
	private val world get() = services.world

	/** What ships, read for this machine: each action as the first of its ways whose programs are installed here. */
	private val shipped by lazy {
		Actions.read(listOf(Actions.SHIPPED), installed = world::installed)
	}

	private fun sh(script: String) {
		val process =
			ProcessBuilder(
				"sh",
				"-c",
				script,
			).directory(root.toFile()).inheritIO().start()

		assertEquals(0, process.waitFor(), script)
	}

	private fun has(vararg programs: String) =
		assumeTrue(programs.all(world::installed), "needs ${programs.joinToString()}")

	/** A folder with a few files, and the same files as a tar and as a zip. */
	private fun archives() {
		has("tar", "zip", "unzip", "zipinfo")
		root.resolve("src/deep").createDirectories()
		root.resolve("with space").createDirectories()
		root.resolve("src/main.c").writeText("int main;")
		root.resolve("src/deep/a.txt").writeText("deep")
		root.resolve("with space/my file.txt").writeText("spaced")
		root.resolve("README.md").writeText("read me")
		sh("tar -czf code.tar.gz src 'with space' README.md && tar -cf plain.tar src README.md")
		sh("zip -qr code.zip src 'with space' README.md")
		sh("mkdir out other")
	}

	/** An explorer on [folder], with the actions that ship, settled. */
	private fun exploring(folder: Path = root): BrowserState =
		step(
			opened(
				Choosing.Exploring(),
				folder,
				root,
				sidebarOf(XdgPlaces(), root),
			).settled(services),
			Answer.Configured(shipped),
		).state

	private fun BrowserState.then(input: Input): BrowserState =
		step(this, input).settled(services)

	private fun BrowserState.row(name: String): Row = rows.list.single { it.name == name }

	private fun BrowserState.names(): List<String> = rows.list.map { it.name }

	/** The menu of the rows named, or of the folder where none is. */
	private fun BrowserState.menu(vararg names: String): Menu<BrowserEvent> =
		contextMenuOf(
			this,
			if (names.isEmpty())
				Target.Folder
			else
				Target.Rows(names.map { row(it) }),
			world = world,
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

	/** What [label] of the menu of the rows named does, done. */
	private fun BrowserState.choose(label: String, vararg names: String): BrowserState =
		then(menu(*names).event(label))

	/** The archive named, opened as its place, here. */
	private fun BrowserState.viewing(name: String): BrowserState {
		// Opening an archive is looking into it: what "Open" is, and a double click.
		val view = menu(name).event("Open") as BrowserEvent.Did
		// As the action says, a window of its own; here, in this one, which is all a test has.
		val here = (view.action.deed as Deed.Show).copy(window = false)

		return then(view.copy(action = view.action.copy(deed = here)))
	}

	// ---- what a command prints, read ----------------------------------------------------------------------------------

	private fun type(read: PlaceRead, tree: Boolean = false, nul: Boolean = false) =
		PlaceType(
			id = "t",
			title = null,
			takes = emptyList(),
			window = false,
			list = (Command.read(listOf("true"), emptySet()) as Reading.Right).value,
			folder = null,
			read = read,
			tree = tree,
			nul = nul,
			out = null,
			into = null,
		)

	@Test
	fun pathsAreFilesOnDiskAllTogetherOrUnderTheirFolders() {
		root.resolve("a/b").createDirectories()
		root.resolve("a/b/one.txt").writeText("1")
		root.resolve("two.txt").writeText("22")

		val flat = PlaceTree(entriesOf("a/b/one.txt\ntwo.txt\n", type(PlaceRead.Paths), root))

		assertEquals(listOf("one.txt", "two.txt"), flat.rows(emptyList()).map { it.name })
		assertEquals(
			listOf(root.resolve("a/b/one.txt"), root.resolve("two.txt")),
			flat.rows(emptyList()).map { it.path },
		)
		assertEquals(
			2L,
			flat
				.rows(emptyList())
				.last()
				.size
				?.value,
		)

		// The same, separated by NULs and laid out as the disk is: the folders are the place's own groupings.
		val tree =
			PlaceTree(
				entriesOf(
					"a/b/one.txt\u0000two.txt\u0000",
					type(PlaceRead.Paths, tree = true, nul = true),
					root,
				),
			)

		val top = tree.rows(emptyList())

		assertEquals(listOf("a", "two.txt"), top.map { it.name })
		assertTrue(top.first().isFolder)
		assertNull(top.first().path)
		assertEquals(listOf("b"), tree.rows(listOf("a")).map { it.name })
		assertEquals(
			listOf(root.resolve("a/b/one.txt")),
			tree.rows(listOf("a", "b")).map {
				it.path
			},
		)
	}

	@Test
	fun namesAreEntriesOnlyThePlaceKnowsWithWhatIsSaidOfEach() {
		val tree =
			PlaceTree(
				entriesOf(
					"src/\tsize=0\ttime=2026-01-02 03:04\nsrc/main.c\tsize=1834\ttime=20260102.030405\nloose/deep/x\n",
					type(PlaceRead.Names),
					root,
				),
			)

		val top = tree.rows(emptyList())

		assertEquals(listOf("src", "loose"), top.map { it.name })
		assertEquals(listOf(true, true), top.map { it.isFolder })
		// A folder the command listed keeps what it was called; one only implied is called by where it sits.
		assertEquals(
			listOf<RowId>(RowId.Entry("src/"), RowId.Entry("loose/")),
			top.map { it.id },
		)

		val main = tree.rows(listOf("src")).single()

		assertEquals(Item.InPlace("src/main.c"), main.item)
		assertNull(main.path)
		assertEquals(1834L, main.size?.value)
		assertNotNull(main.modified)
		assertEquals(listOf("x"), tree.rows(listOf("loose", "deep")).map { it.name })
	}

	@Test
	fun entriesAreJsonAndAFolderWithEntriesUnderItIsNeverTheFolderOnDisk() {
		root.resolve("real").createDirectories()

		val tree =
			PlaceTree(
				entriesOf(
					"""
					{"at": "Cluster/one.txt", "path": "${root.resolve(
						"one.txt",
					)}", "size": 3, "time": "1767322800"}
					{"at": "real/", "path": "${root.resolve("real")}"}
					{"at": "real/inside.txt"}
					{"at": "empty", "path": "${root.resolve("real")}"}
					not json at all
					""".trimIndent(),
					type(PlaceRead.Entries),
					root,
				),
			)

		val top = tree.rows(emptyList()).associateBy { it.name }

		assertEquals(setOf("Cluster", "real", "empty"), top.keys)
		// A folder shown with only some of what it holds is a grouping: nothing done to it could be done to the folder.
		assertNull(top.getValue("real").path)
		// A folder with nothing under it here is the folder itself, whole.
		assertEquals(root.resolve("real"), top.getValue("empty").path)
		assertEquals(root.resolve("one.txt"), tree.rows(listOf("Cluster")).single().path)
	}

	// ---- the archive tool ---------------------------------------------------------------------------------------------

	@Test
	fun anArchivesMenuOffersWhatArchivesAreForAndOtherFilesAreNotOfferedIt() {
		archives()

		val state = exploring()

		assertTrue(
			state.menu("code.tar.gz").labels().containsAll(
				listOf(
					"Open",
					"Extract here",
					"Extract into a folder",
					"Extract to…",
					"Compress ▸ As a .zip…",
				),
			),
		)
		assertTrue(
			state.menu("code.zip").labels().containsAll(listOf("Open", "Extract here")),
		)
		assertFalse("Extract here" in state.menu("README.md").labels())
		assertTrue("Compress ▸ As a .tar.gz…" in state.menu("README.md", "src").labels())
		// In the folder they are in, files have no containing folder to open.
		assertFalse("Open containing folder" in state.menu("README.md").labels())
	}

	@Test
	fun extractingHereAndIntoAFolderRunTheArchivesOwnTool() {
		archives()

		val out = exploring(root.resolve("out"))

		sh("cp code.tar.gz code.zip out/")

		val extracted = out.then(BrowserEvent.Refreshed).choose("Extract here", "code.tar.gz")

		assertEquals("deep", root.resolve("out/src/deep/a.txt").readText())
		assertEquals("spaced", root.resolve("out/with space/my file.txt").readText())
		// The folder is read again once the command has run, and nothing is left running.
		assertTrue(extracted.names().containsAll(listOf("src", "with space", "README.md")))
		assertEquals(emptyList<Work>(), extracted.running)
		assertNull(extracted.notice)

		val foldered = extracted.choose("Extract into a folder", "code.zip")

		assertEquals("int main;", root.resolve("out/code/src/main.c").readText())
		assertEquals(RowId("code"), foldered.cursor?.at)
	}

	@Test
	fun extractToAsksForAFolderFirstAndCompressAsksForAName() {
		archives()

		val asked = step(exploring(), exploring().menu("code.zip").event("Extract to…"))

		assertEquals(
			listOf<Effect>(Effect.Ask(Request.PickFolder("Extract to", root))),
			asked.effects,
		)

		asked.state.then(BrowserEvent.Answered(root.resolve("other")))
		assertEquals("read me", root.resolve("other/README.md").readText())

		val naming = exploring().choose("Compress ▸ As a .tar.gz…", "src", "README.md")

		assertEquals(Notice.Asking("Archive name", "src"), naming.notice)

		val made = naming.then(BrowserEvent.AnswerTyped("sources")).then(BrowserEvent.Confirmed)

		assertTrue(root.resolve("sources.tar.gz").exists())
		assertEquals(RowId("sources.tar.gz"), made.cursor?.at)
	}

	@Test
	fun anArchiveIsGoneAboutAsAFolderIsWithoutBeingExtracted() {
		archives()

		for (archive in listOf("code.tar.gz", "code.zip")) {
			val top = exploring().viewing(archive)

			assertEquals(archive, top.place?.placed?.title)
			assertEquals(setOf("src", "with space", "README.md"), top.names().toSet(), archive)
			assertEquals(7L, top.row("README.md").size?.value)
			assertNotNull(top.row("README.md").modified)
			// Nothing in an archive is on disk: what prism does to files is not offered for it.
			assertNull(top.row("README.md").path)
			assertEquals(
				listOf("Open", "Extract to…", "Extract next to archive") +
					listOf("Remove from archive").filter { archive.endsWith(".zip") } + "Copy",
				top.menu("README.md").labels(),
			)

			// A double click goes into a folder; Up comes back, the cursor on the folder left; Back does too.
			val src = top.then(BrowserEvent.Activated(top.row("src").id))

			assertEquals(setOf("main.c", "deep"), src.names().toSet())
			assertEquals(listOf("src"), src.place?.within)

			val deep = src.then(BrowserEvent.Activated(src.row("deep").id))

			assertEquals(listOf("a.txt"), deep.names())
			assertEquals(
				top.row("src").id,
				deep
					.then(BrowserEvent.Up)
					.then(BrowserEvent.Up)
					.cursor
					?.at,
			)
			assertEquals(listOf("src"), deep.then(BrowserEvent.Back).place?.within)
			assertEquals(root, top.then(BrowserEvent.Back).folder)
			// A step of the path across the top goes there too.
			assertEquals(
				emptyList<String>(),
				deep.then(BrowserEvent.WentWithin(emptyList())).place?.within,
			)
		}
	}

	@Test
	fun entriesAreExtractedByTheMenuAndBesideTheArchive() {
		archives()

		for ((archive, into) in listOf("code.tar.gz" to "out", "code.zip" to "other")) {
			val top = exploring().viewing(archive)
			val asked = step(top, top.menu("src", "README.md").event("Extract to…"))

			assertEquals(
				listOf<Effect>(Effect.Ask(Request.PickFolder("Extract to", root))),
				asked.effects,
			)
			asked.state.then(BrowserEvent.Answered(root.resolve(into)))

			assertEquals("deep", root.resolve("$into/src/deep/a.txt").readText())
			assertEquals("read me", root.resolve("$into/README.md").readText())
			assertFalse(root.resolve("$into/with space").exists())
		}

		// "Extract next to archive" is "Extract to…" with its folder given: nothing is asked.
		sh("mkdir away && mv code.zip away/")

		val moved = exploring(root.resolve("away")).viewing("code.zip")

		val spaced = moved.then(BrowserEvent.Activated(moved.row("with space").id))

		// An entry is brought out as it is shown: the file itself, and not the folders it sits under in the archive.
		spaced.choose("Extract next to archive", "my file.txt")
		assertEquals("spaced", root.resolve("away/my file.txt").readText())
		assertFalse(root.resolve("away/with space").exists())
	}

	@Test
	fun entriesDraggedOutOfAnArchiveAreExtractedWhereTheyAreDropped() {
		archives()

		val top = exploring().viewing("code.tar.gz")
		val carried = top.carrying(listOf(top.row("src"), top.row("README.md")))!!

		// Another window, on another folder: the drop is made there, and it is that folder that is read again.
		val elsewhere =
			exploring(
				root.resolve("out"),
			).then(BrowserEvent.DroppedCarried(carried, DropSpot.Here))

		assertEquals("deep", root.resolve("out/src/deep/a.txt").readText())
		assertEquals(setOf("src", "README.md"), elsewhere.names().toSet())

		// On a folder's row, into that folder.
		exploring().then(
			BrowserEvent.DroppedCarried(carried, DropSpot.Into(root.resolve("other"))),
		)
		assertEquals("int main;", root.resolve("other/src/main.c").readText())

		// A picker takes no drops of the kind, and a place is no folder to extract into.
		assertEquals(
			emptyList<Effect>(),
			step(top, BrowserEvent.DroppedCarried(carried, DropSpot.Here)).effects,
		)
	}

	@Test
	fun entriesCopiedInAnArchiveArePastedIntoAFolder() {
		archives()

		val top = exploring().viewing("code.zip")
		val copied = top.then(BrowserEvent.CopiedFiles(listOf(top.row("with space").id)))

		assertNotNull(services.carried.get())
		exploring(root.resolve("out")).then(BrowserEvent.Pasted())
		assertEquals("spaced", root.resolve("out/with space/my file.txt").readText())

		// Files copied afterwards are what a paste means from then on.
		val files = exploring().then(BrowserEvent.CopiedFiles(listOf(RowId("README.md"))))

		assertNull(services.carried.get())
		files.then(BrowserEvent.Pasted(root.resolve("other")))
		assertEquals("read me", root.resolve("other/README.md").readText())
		assertNotNull(copied)
	}

	@Test
	fun anEntryOpenedIsBroughtOutIntoAFolderOfItsOwnAndOpenedFromThere() {
		archives()

		val top = exploring().viewing("code.tar.gz")
		val src = top.then(BrowserEvent.Activated(top.row("src").id))
		val opening = step(src, BrowserEvent.Activated(src.row("main.c").id))
		val temp = opening.effects.single() as Effect.Temp
		val made = Files.createTempDirectory(root, "open")
		// A folder for the command to work in first, which is where it extracts to; then what it made is brought to
		// the folder the entry is opened from, by its own name, and the folder it worked in is gone.
		val asked = step(opening.state, Answer.TempMade(made, temp.carried))
		val scratch = Files.createTempDirectory(root, "scratch")

		val extracting =
			step(
				asked.state,
				Answer.ScratchMade(scratch, (asked.effects.single() as Effect.Scratch).pending),
			)

		val job = (extracting.effects.single() as Effect.Run).job

		assertEquals(After.Open(listOf(made.resolve("main.c"))), job.after)
		assertEquals(Delivery(scratch, made, listOf("src/main.c" to "main.c")), job.bring)

		val ran = step(extracting.state, services.answer(Effect.Run(job)))

		assertEquals("int main;", made.resolve("main.c").readText())
		assertFalse(made.resolve("src").exists())
		assertEquals(listOf<Effect>(Effect.Open(listOf(made.resolve("main.c")))), ran.effects)
	}

	@Test
	fun entriesAreRemovedFromAnArchiveOnceTheUserSaysSoAndItIsListedAgain() {
		archives()

		for (archive in listOf("plain.tar", "code.zip")) {
			val top = exploring().viewing(archive)
			val asked = top.choose("Remove from archive", "src")

			assertInstanceOf(Notice.ConfirmAction::class.java, asked.notice)
			assertTrue("src" in asked.names())

			val removed = asked.then(BrowserEvent.Confirmed)

			assertFalse("src" in removed.names(), archive)
			assertTrue("README.md" in removed.names(), archive)
			assertNull(removed.notice)
		}

		// A compressed tar cannot have entries removed in place, so it is not offered there.
		assertFalse(
			"Remove from archive" in exploring().viewing("code.tar.gz").menu("src").labels(),
		)
	}

	@Test
	fun filesDroppedOnAnArchiveOrPastedIntoItAreAddedToIt() {
		archives()
		root.resolve("new.txt").writeText("new")
		root.resolve("more.txt").writeText("more")

		for (archive in listOf("plain.tar", "code.zip")) {
			val top = exploring().viewing(archive)

			val added =
				top.then(
					BrowserEvent.Dropped(listOf(root.resolve("new.txt")), DropSpot.Here, Transfer.Copy),
				)

			assertTrue("new.txt" in added.names(), archive)

			exploring().then(BrowserEvent.CopiedFiles(listOf(RowId("more.txt"))))
			assertTrue("Paste" in added.menu().labels())
			assertTrue("more.txt" in added.then(BrowserEvent.Pasted()).names(), archive)
		}
	}

	@Test
	fun anArchiveThatCannotBeListedSaysWhy() {
		has("tar")
		root
			.resolve(
				"broken.tar",
			).writeText("this is not a tar archive at all, whatever its name says")

		val listing = exploring().viewing("broken.tar").listing

		assertInstanceOf(Listing.Unreadable::class.java, listing)
		assertTrue((listing as Listing.Unreadable).why.isNotBlank())
	}

	@Test
	fun entriesAreBroughtOutAsTheyAreShownWithoutTheFoldersAboveThem() {
		archives()

		for ((archive, into) in listOf("code.tar.gz" to "out", "code.zip" to "other")) {
			val top = exploring().viewing(archive)
			val src = top.then(BrowserEvent.Activated(top.row("src").id))
			val carried = src.carrying(listOf(src.row("main.c"), src.row("deep")))!!

			exploring(root.resolve(into)).then(BrowserEvent.DroppedCarried(carried, DropSpot.Here))

			// The file, and the folder with what is in it; not the `src` they are under in the archive.
			assertEquals("int main;", root.resolve("$into/main.c").readText(), archive)
			assertEquals("deep", root.resolve("$into/deep/a.txt").readText(), archive)
			assertFalse(root.resolve("$into/src").exists(), archive)
			// Nothing a command worked in is left behind, where it was extracted or anywhere else.
			assertEquals(
				setOf("main.c", "deep"),
				Files.list(root.resolve(into)).use { all ->
					all.map { it.fileName.toString() }.toList().toSet()
				},
			)
		}
	}

	@Test
	fun aPlaceIsSearchedByItsOwnIndexAndNothingElseIsFound() {
		archives()
		// On disk beside the archive, and never in it: a search of the archive must not find it.
		root.resolve("a.txt.elsewhere").writeText("not in the archive")

		for (archive in listOf("code.tar.gz", "code.zip")) {
			val top = exploring().viewing(archive)

			// With no index of the disk at all, a place is still searched: by what it listed.
			assertInstanceOf(Search.Unavailable::class.java, top.search)

			val typed = top.then(BrowserEvent.OpenedSearch).then(BrowserEvent.SearchTyped("a.txt"))
			val found = (typed.search as Search.Ready).matched!!.matches

			assertEquals(listOf("a.txt"), found.ranks.map(found::nameOf), archive)

			// Every match shown: results that are the archive's entries, with the archive's actions.
			val shown = typed.then(BrowserEvent.ShowedResults)

			assertEquals(listOf("a.txt"), shown.names())
			assertEquals(
				Item.InPlace("src/deep/a.txt"),
				shown.rows.list
					.single()
					.item,
			)
			assertEquals(archive, shown.inPlace?.placed?.title)
			assertTrue("Extract to…" in shown.menu("a.txt").labels())
			assertFalse("Move to trash" in shown.menu("a.txt").labels())
			assertNotNull(
				shown.rows.list
					.single()
					.size,
			)

			// The search language is the index's: an extension, a folder, a glob.
			fun finds(text: String) =
				top
					.then(
						BrowserEvent.OpenedSearch,
					).then(
						BrowserEvent.SearchTyped(text),
					).then(BrowserEvent.ShowedResults)
					.names()
					.toSet()

			assertEquals(setOf("main.c"), finds("ext:c"))
			assertEquals(setOf("deep", "main.c", "a.txt"), finds("dir:src"))
			assertEquals(setOf("my file.txt"), finds("\"my file\""))
			assertEquals(setOf("src", "deep", "with space"), finds("is:dir"))

			// A match chosen in the search's window is gone to in the archive: the folder it sits in, the cursor on it.
			val gone = typed.then(BrowserEvent.MatchChosen(0))

			assertEquals(listOf("src", "deep"), gone.place?.within)
			assertEquals(RowId.Entry("src/deep/a.txt"), gone.cursor?.at)

			// A folder among the results is gone into where it sits, and Back returns to the results.
			val folders =
				top
					.then(
						BrowserEvent.OpenedSearch,
					).then(BrowserEvent.SearchTyped("deep"))
					.then(BrowserEvent.ShowedResults)

			val into =
				folders.then(
					BrowserEvent.Activated(
						folders.rows.list
							.single()
							.id,
					),
				)

			assertEquals(listOf("src", "deep"), into.place?.within)
			assertEquals(listOf("deep"), into.then(BrowserEvent.Back).names())
		}
	}

	@Test
	fun aPlacesResultsPinnedAreSearchedWithinInThePlace() {
		archives()
		// On disk beside the archive: a search within the archive's results must not find it.
		root.resolve("main.c.elsewhere").writeText("not in the archive")

		val top = exploring().viewing("code.tar.gz")

		val pinned =
			top
				.then(BrowserEvent.OpenedSearch)
				.then(BrowserEvent.SearchTyped("dir:src"))
				.then(BrowserEvent.ShowedResults)
				.then(BrowserEvent.PinnedSearch(true))
		val at = pinned.at as Location.Results

		assertEquals(setOf("deep", "main.c", "a.txt"), pinned.names().toSet())
		assertTrue(at.pinned)
		assertEquals(top.place?.placed, at.within?.placed)

		// What is typed is found among them, by the place's own listing: the archive's entry, and nothing of the disk.
		val refined =
			pinned
				.then(BrowserEvent.OpenedSearch)
				.then(BrowserEvent.SearchTyped("ext:c"))
				.then(BrowserEvent.ShowedResults)
		val within = refined.at as Location.Results

		assertEquals(listOf("main.c"), refined.names())
		assertEquals(
			Item.InPlace("src/main.c"),
			refined.rows.list
				.single()
				.item,
		)
		assertEquals(at.within, within.within)
		assertEquals(listOf("dir:src", "ext:c"), within.searches)
		assertTrue(within.pinned)

		// And a search within those finds what all three ask for, in the place still.
		val third =
			refined
				.then(BrowserEvent.OpenedSearch)
				.then(BrowserEvent.SearchTyped("a"))
				.then(BrowserEvent.ShowedResults)

		assertEquals(listOf("main.c"), third.names())
		assertEquals(at.within, (third.at as Location.Results).within)
	}

	@Test
	fun whatACommandMadeIsMergedIntoWhereItGoesOverNothing() {
		val from = root.resolve("from")
		val into = root.resolve("into")

		from.resolve("a/b/deep").createDirectories()
		from.resolve("a/b/c.txt").writeText("new c")
		from.resolve("a/b/deep/d.txt").writeText("new d")
		from.resolve("a/b/deep/e.txt").writeText("new e")
		into.resolve("deep").createDirectories()
		into.resolve("deep/d.txt").writeText("old d")

		// The entries by their own names: a file, and a folder added to the folder that is there already.
		assertNull(
			delivered(
				Delivery(
					from,
					into,
					listOf(
						"a/b/c.txt" to "c.txt",
						"a/b/deep/" to "deep",
					),
				),
			),
		)
		assertEquals("new c", into.resolve("c.txt").readText())
		assertEquals("new e", into.resolve("deep/e.txt").readText())
		// What was there is as it was.
		assertEquals("old d", into.resolve("deep/d.txt").readText())
		assertFalse(into.resolve("a").exists())

		// With no entries named, everything the command made.
		val all = root.resolve("all")

		from.resolve("top.txt").writeText("top")
		assertNull(delivered(Delivery(from, all, null)))
		assertTrue(all.resolve("top.txt").exists())
		// What was not made is said, and nothing outside the folder the command worked in is ever taken.
		assertTrue(
			"wasn't found" in delivered(Delivery(from, into, listOf("missing" to "missing")))!!,
		)
		root.resolve("secret").writeText("s")
		assertTrue(
			"wasn't found" in delivered(Delivery(from, into, listOf("../secret" to "secret")))!!,
		)
		assertFalse(into.resolve("secret").exists())
	}

	@Test
	fun temporaryFoldersAreRemovedWhenDoneWithAndThoseOfProcessesGoneAreSwept() {
		val under = Files.createDirectory(root.resolve("prism"))
		// Left by a process that is not running any more, and by one that is.
		val stale = Files.createDirectories(under.resolve("999999999-old/inside"))
		val live = Files.createDirectory(under.resolve("${ProcessHandle.current().pid()}-mine"))
		val other = Files.createDirectory(under.resolve("something-else"))
		val temps = Temps(under, process = 4242)

		assertFalse(stale.parent.exists())
		assertTrue(live.exists())
		assertTrue(other.exists())

		val made = temps.make()

		made.resolve("deep").createDirectories()
		made.resolve("deep/file").writeText("x")
		assertEquals(under, made.parent)
		assertTrue(made.fileName.toString().startsWith("4242-"))
		temps.remove(made)
		assertFalse(made.exists())

		val more = listOf(temps.make(), temps.make())

		temps.clear()
		assertTrue(more.none { it.exists() })
		// Only what these made is theirs to remove.
		temps.remove(other)
		assertTrue(other.exists())

		// Where nothing says otherwise, they are in `prism` under the system's temporary folder: `/tmp/prism`.
		val shared = Temps()
		val real = shared.make()

		assertEquals(Path.of(System.getProperty("java.io.tmpdir"), "prism"), real.parent)
		shared.remove(real)
		assertFalse(real.exists())
	}

	@Test
	fun aFolderShownIsReadAgainWhenWhatItHoldsChangesOnDisk() =
		runBlocking {
			val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
			val watched = root.resolve("watched").createDirectories()
			val beside = root.resolve("beside").createDirectories()

			val session =
				Session(
					opened(Choosing.Exploring(), watched, root, sidebarOf(XdgPlaces(), root)),
					services.copy(watcher = JvmFolderWatcher),
					scope,
				)

			try {
				withTimeout(15_000) {
					session.state.first { it.listing is Listing.Read }
					// Made by something that is not prism: the folder is read again without being asked to.
					delay(200)
					watched.resolve("made.txt").writeText("made elsewhere")
					session.state.first { state -> state.rows.list.any { it.name == "made.txt" } }

					// What is chosen stays chosen as it is read again.
					session.send(BrowserEvent.Clicked(RowId("made.txt"), Click.Only))
					watched.resolve("more.txt").writeText("more")

					val both =
						session.state.first { state ->
							state.rows.list.any { it.name == "more.txt" }
						}

					assertEquals(setOf<RowId>(RowId("made.txt")), both.choosing.rows)
					Files.delete(watched.resolve("made.txt"))
					session.state.first { state -> state.rows.list.none { it.name == "made.txt" } }

					// Gone elsewhere, it is that folder that is watched, and the one left is not.
					session.send(BrowserEvent.Opened(beside))
					session.state.first { it.folder == beside && it.listing is Listing.Read }
					delay(200)
					beside.resolve("here.txt").writeText("here")
					session.state.first { state -> state.rows.list.any { it.name == "here.txt" } }
				}
			} finally {
				scope.cancel()
			}
		}

	@Test
	fun aCommandUnderWayIsToldOfAndStoppedAndABrowserClosedLeavesNothingBehind() =
		runBlocking {
			has("sh", "sleep")

			val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
			val temps = Temps(Files.createDirectory(root.resolve("tmp")))

			val session =
				Session(
					opened(Choosing.Exploring(), root, root, sidebarOf(XdgPlaces(), root)),
					services.copy(temps = temps),
					scope,
				)

			val slow =
				Actions
					.read(
						listOf(
							prism.preferences.ActionsFile.parse(
								"""{"actions": [{"id": "slow", "label": "Slow", "when": {"target": "folder"},
								"run": ["sh", "-c", "echo started; echo \"in $1\" >&2; sleep 60 | cat", "sh", "{temp}"],
								"how": {"watched": true}}]}""",
							),
						),
					).actions
					.single()

			try {
				withTimeout(15_000) {
					val state = session.state.first { it.listing is Listing.Read }

					session.send(BrowserEvent.Did(slow, state.subjectOf(Target.Folder)))

					// What it writes, to its output and to standard error, is told as it writes it.
					val told =
						session.state
							.first {
								it.running
									.singleOrNull()
									?.said
									?.size == 2
							}.running
							.single()

					val scratch = Path.of(told.said.single { it.startsWith("in ") }.removePrefix("in "))

					assertTrue("started" in told.said)
					assertTrue(scratch.exists())

					// Stopped, it ends at once, with the pipeline it started; nothing is said to have failed, and the
					// folder it worked in is gone.
					val began = System.nanoTime()

					session.send(BrowserEvent.Stopped(told.task))

					val ended = session.state.first { it.running.isEmpty() }

					assertTrue(System.nanoTime() - began < 5_000_000_000L)
					assertNull(ended.notice)
					assertFalse(scratch.exists())

					// A browser closed while a command runs ends it, and takes the folders made for it with it.
					session.send(BrowserEvent.Did(slow, state.subjectOf(Target.Folder)))

					val again =
						session.state
							.first {
								it.running
									.singleOrNull()
									?.said
									?.size == 2
							}.running
							.single()

					val left = Path.of(again.said.single { it.startsWith("in ") }.removePrefix("in "))

					scope.cancel()
					while (left.exists())
						delay(20)
				}
			} finally {
				scope.cancel()
			}
		}

	/** [png] kept where `-Pbrowser.screenshots=DIR` says, for a person to look at. */
	private fun kept(name: String, png: ByteArray) {
		assertTrue(png.size > 1000, name)
		System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
			Files.createDirectories(Path.of(dir))
			Files.write(Path.of(dir, "$name.png"), png)
		}
	}

	@Test
	fun theArchiveToolIsDrawn() {
		archives()

		val folder = exploring()
		val zip = folder.row("code.zip")
		val inside =
			folder.viewing("code.zip").let {
				it.then(BrowserEvent.Activated(it.row("src").id))
			}
		val chosen = inside.then(BrowserEvent.Clicked(inside.row("main.c").id, Click.Only))
		val bindings = Bindings.of(prism.preferences.Preferences())

		kept(
			"archive-menu-of-a-file",
			renderedPng(1000, 640) {
				FileBrowser(folder.then(BrowserEvent.Clicked(zip.id, Click.Only)))
				MenuPopup(
					contextMenuOf(folder, Target.Rows(listOf(zip)), bindings, world),
					MenuAnchor.At(
						androidx.compose.ui.unit
							.IntOffset(420, 120),
					),
					{},
					{},
				)
			},
		)
		kept(
			"archive-inside",
			renderedPng(1000, 640, Palette.Dark) {
				FileBrowser(chosen)
				MenuPopup(
					contextMenuOf(chosen, Target.Rows(chosen.chosenRows), bindings, world),
					MenuAnchor.At(
						androidx.compose.ui.unit
							.IntOffset(420, 150),
					),
					{},
					{},
				)
			},
		)
	}

	// ---- a place of files on disk ---------------------------------------------------------------------------------------

	@Test
	fun aPlaceOfRealFilesOpensHereAndItsFilesAreFilesWhereverTheyAreShown() {
		has("git")
		sh(
			"git init -q . && git -c user.name=t -c user.email=t@t commit -q --allow-empty -m first",
		)
		root.resolve("docs").createDirectories()
		root.resolve("docs/menus.md").writeText("one")
		root.resolve("top.txt").writeText("two")
		sh("git add -A && git -c user.name=t -c user.email=t@t commit -q -m second")
		root.resolve("docs/menus.md").writeText("one, changed")
		root.resolve("top.txt").writeText("two, changed")

		val changes = exploring().choose("Changed files (git)")

		assertEquals("Changes in ${root.fileName}", changes.place?.placed?.title)
		assertEquals(listOf("docs", "top.txt"), changes.names())
		assertNull(changes.row("docs").path)
		assertEquals(root.resolve("top.txt"), changes.row("top.txt").path)

		val docs = changes.then(BrowserEvent.Activated(changes.row("docs").id))
		val labels = docs.menu("menus.md").labels()

		// A file on disk is one here too: prism's own entries, and its folder to go to, since it is shown elsewhere.
		assertTrue(
			labels.containsAll(
				listOf("Open containing folder", "Cut", "Rename", "Move to trash", "Properties"),
			),
		)

		val went = docs.choose("Open containing folder", "menus.md")

		assertEquals(root.resolve("docs"), went.folder)
		assertEquals(RowId("menus.md"), went.cursor?.at)
		// The place's own folders are nothing to act on.
		assertEquals(listOf("Open"), changes.menu("docs").labels())
	}
}
