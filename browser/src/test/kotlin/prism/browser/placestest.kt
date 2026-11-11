package prism.browser

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
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

	private val shipped = Actions.read(listOf(Actions.SHIPPED))
	private val services by lazy { Services(JvmDisk(trash = Trash(bin)), home = root) }
	private val world get() = services.world

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
				sidebarOf(XdgPlaces(), root, null),
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
		val view = menu(name).event("View contents") as BrowserEvent.Did
		// As the action says, a window of its own; here, in this one, which is all a test has.
		val here = (view.action.deed as Deed.Show).copy(window = false)

		return then(view.copy(action = view.action.copy(deed = here)))
	}

	// ---- what a command prints, read ----------------------------------------------------------------------------------

	private fun type(read: PlaceRead, tree: Boolean = false, nul: Boolean = false) =
		PlaceType(
			"t",
			null,
			emptyList(),
			false,
			(Command.read(listOf("true"), emptySet()) as Reading.Right).value,
			null,
			read,
			tree,
			nul,
			null,
			null,
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
					"View contents",
					"Extract here",
					"Extract into a folder",
					"Extract to…",
					"Compress ▸ As a .zip…",
				),
			),
		)
		assertTrue(
			state.menu("code.zip").labels().containsAll(listOf("View contents", "Extract here")),
		)
		assertFalse("View contents" in state.menu("README.md").labels())
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
		assertEquals(emptyList<String>(), extracted.running)
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

		assertEquals(Notice.Asking("Name of the archive", "src"), naming.notice)

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
				listOf("Open", "Extract to…", "Extract beside the archive") +
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

		// "Extract beside the archive" is "Extract to…" with its folder given: nothing is asked.
		sh("mkdir away && mv code.zip away/")

		val moved = exploring(root.resolve("away")).viewing("code.zip")

		moved.then(
			moved.then(BrowserEvent.Activated(moved.row("with space").id)).let {
				it.menu("my file.txt").event("Extract beside the archive")
			},
		)
		assertEquals("spaced", root.resolve("away/with space/my file.txt").readText())
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
		val extracting = step(opening.state, Answer.TempMade(made, temp.carried))
		val job = (extracting.effects.single() as Effect.Run).job

		assertEquals(After.Open(listOf(made.resolve("src/main.c"))), job.after)

		val ran = step(extracting.state, services.answer(Effect.Run(job)))

		assertEquals("int main;", made.resolve("src/main.c").readText())
		assertEquals(
			listOf<Effect>(Effect.Open(listOf(made.resolve("src/main.c")))),
			ran.effects,
		)
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
