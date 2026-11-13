package prism.browser

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.Application
import prism.desktop.Applications
import prism.desktop.CommandLine
import prism.desktop.Desktop
import prism.desktop.FileTypes
import prism.desktop.JvmFacts
import prism.desktop.Launched
import prism.desktop.Launcher
import prism.desktop.MimeType
import prism.desktop.Place
import prism.desktop.Places
import prism.desktop.Read
import prism.desktop.TerminalChoice
import prism.preferences.Builtin
import prism.preferences.Held
import prism.preferences.MemoryPreferences
import prism.preferences.Preferences
import prism.preferences.QuickAccess
import prism.preferences.QuickItem

/**
 * The browser as the explorer has it: nothing to give back, a file accepted opened with its application (or, where
 * none opens it, offered to one the user chooses), files copied, cut and pasted as a file manager's are, and made; and
 * pins, which are the user's preferences, reaching the sidebar of every window from any.
 */
class ExploreTest {
	@TempDir
	lateinit var root: Path

	@TempDir
	lateinit var bin: Path

	/** What the desktop was asked to start. */
	private val started = ArrayList<Pair<String, List<Path>>>()

	/** A desktop where `.txt` files open with an editor, and nothing opens anything else. */
	private val desktop =
		object : Desktop {
			val editor =
				Application(
					"editor.desktop",
					"Editor",
					setOf(MimeType.TEXT),
					listOf("editor", "%F"),
					false,
				)

			override val types =
				object : FileTypes {
					override fun of(path: Path) =
						if (path.toString().endsWith(".txt"))
							MimeType.TEXT
						else
							MimeType.BINARY

					override fun describe(type: MimeType) = null

					override fun lineage(type: MimeType) = listOf(type)
				}
			override val applications =
				object : Applications {
					override fun all() = listOf(editor)

					override fun forType(type: MimeType) =
						listOfNotNull(
							editor.takeIf {
								type ==
									MimeType.TEXT
							},
						)

					override fun defaultFor(type: MimeType) = forType(type).firstOrNull()

					override fun setDefault(type: MimeType, application: Application) = Unit
				}
			override val launcher =
				object : Launcher {
					override fun launch(
						application: Application,
						paths: List<Path>,
						terminal: TerminalChoice,
					): Launched {
						started += application.name to paths

						return Launched.Started(application.name)
					}

					override fun run(command: List<String>, folder: Path) =
						Launched.Started(command.first())

					override fun terminal(folder: Path, choice: TerminalChoice) =
						Launched.Started("terminal")
				}
			override val places =
				object : Places {
					override fun known(home: Path) = listOf(Place("Home", Place.Kind.Home, home))

					override fun drives(home: Path) =
						listOf(Place("File system", Place.Kind.Drive, Path.of("/")))
				}
			override val facts = JvmFacts(types)
		}

	private val preferences = MemoryPreferences()
	private val clipboard = MemoryFileClipboard()

	private fun services() =
		Services(
			JvmDisk(Trash(bin)),
			desktop = desktop,
			clipboard = clipboard,
			preferences = preferences,
		)

	private fun made(vararg files: String) =
		files.forEach {
			root.resolve(it).also { path -> path.parent.createDirectories() }.writeText(it)
		}

	private fun explorer(folder: Path = root): BrowserState =
		opened(
			Choosing.Exploring(),
			folder,
			root,
			sidebarOf(desktop.places, root),
		).settled(services())

	private fun after(state: BrowserState, vararg inputs: Input): BrowserState =
		inputs.fold(state) { at, input -> step(at, input).settled(services()) }

	private fun BrowserState.id(name: String) = rows.list.single { it.name == name }.id

	@Test
	fun aFileAcceptedIsOpenedWithItsApplicationAndOneNothingOpensIsOfferedToOne() {
		made("notes.txt", "data.bin", "docs/a.txt")

		val explorer = explorer()

		// Accepted as a double click accepts it: opened, the explorer staying where it is.
		val opening = step(explorer, BrowserEvent.Activated(explorer.id("notes.txt")))

		assertEquals(listOf(Effect.Open(listOf(root.resolve("notes.txt")))), opening.effects)
		opening.settled(services())
		assertEquals(listOf("Editor" to listOf(root.resolve("notes.txt"))), started)

		// Nothing opens the other: the application around the explorer is asked to offer a choice.
		val unopened = step(explorer, BrowserEvent.Activated(explorer.id("data.bin")))
		val answer = services().answer(unopened.effects.single() as Effect.Asking)

		assertEquals(
			listOf(Effect.Ask(Request.OpenWith(listOf(root.resolve("data.bin"))))),
			step(unopened.state, answer).effects,
		)

		// Several chosen and accepted: the files opened, together; a folder alone, gone into.
		val both =
			after(
				explorer,
				BrowserEvent.Clicked(explorer.id("notes.txt"), Click.Only),
				BrowserEvent.Clicked(explorer.id("data.bin"), Click.Toggle),
			)

		assertEquals(
			listOf(Effect.Open(listOf(root.resolve("data.bin"), root.resolve("notes.txt")))),
			step(both, BrowserEvent.Accepted).effects,
		)
		assertEquals(
			root.resolve("docs"),
			after(
				explorer,
				BrowserEvent.Clicked(explorer.id("docs"), Click.Only),
				BrowserEvent.Accepted,
			).folder,
		)
		// And Escape lets go of what is chosen, rather than ending anything.
		assertTrue(after(both, BrowserEvent.ClickedNowhere).choosing.rows.isEmpty())
	}

	@Test
	fun filesCopiedArePastedBesideThemselvesUnderAFreeNameAndTheCopyUndone() {
		made("report.txt")

		val copied =
			after(explorer(), BrowserEvent.CopiedFiles(listOf(explorer().id("report.txt"))))

		assertEquals(
			FileClip(listOf(root.resolve("report.txt")), cut = false),
			clipboard.read(),
		)

		val pasted = after(copied, BrowserEvent.Pasted())
		val twice = after(pasted, BrowserEvent.Pasted())

		assertEquals(
			listOf(
				"report (copy 2).txt",
				"report (copy).txt",
				"report.txt",
			),
			twice.rows.list.map {
				it.name
			},
		)
		// The copy made is the row chosen.
		assertEquals(listOf("report (copy 2).txt"), twice.chosenRows.map { it.name })

		val undone = after(twice, BrowserEvent.Undone)

		assertFalse(root.resolve("report (copy 2).txt").exists())
		// Named as what the user did: a copy is undone by a move to the trash, but it is still the copy undone.
		assertEquals(
			listOf("Undo the copy", "Redo the copy"),
			labels(contextMenuOf(undone, Target.Folder)).take(2),
		)
	}

	@Test
	fun filesCutAreMovedWherePastedAndPutBackByAnUndo() {
		made("a/one.txt", "b/two.txt")

		val inA = explorer(root.resolve("a"))
		val cut = after(inA, BrowserEvent.CutFiles(listOf(inA.id("one.txt"))))

		assertEquals(FileClip(listOf(root.resolve("a/one.txt")), cut = true), clipboard.read())

		val moved = after(cut, BrowserEvent.Pasted(root.resolve("b")))

		assertTrue(root.resolve("b/one.txt").exists())
		assertFalse(root.resolve("a/one.txt").exists())
		assertTrue(moved.rows.isEmpty())

		val back = after(moved, BrowserEvent.Undone)

		assertEquals("a/one.txt", root.resolve("a/one.txt").readText())
		assertEquals(listOf("one.txt"), back.rows.list.map { it.name })

		// A folder never into itself.
		root.resolve("c/inner").createDirectories()

		val folder = explorer()

		val intoItself =
			after(
				after(folder, BrowserEvent.CutFiles(listOf(folder.id("c")))),
				BrowserEvent.Pasted(root.resolve("c/inner")),
			)

		assertInstanceOf(Notice.Failed::class.java, intoItself.notice)
		assertTrue(root.resolve("c/inner").exists())
	}

	@Test
	fun aFileMadeIsNamedAsItIsTypedAndRemovedByAnUndoWhileEmpty() {
		val made = after(explorer(), BrowserEvent.MadeFile)

		assertTrue(Files.exists(root.resolve("New file")))
		assertEquals("New file", made.renaming?.text)

		after(made, BrowserEvent.RenameCancelled, BrowserEvent.Undone)
		assertFalse(Files.exists(root.resolve("New file")))
	}

	@Test
	fun aPlacePinnedIsTheUsersPreferenceAndEveryWindowsSidebarFollowsIt() {
		made("projects/x.txt")

		val explorer = explorer()
		val pinning = step(explorer, BrowserEvent.Pinned(root.resolve("projects")))
		val pinned = QuickAccess.DEFAULT.pinning(root.resolve("projects"))

		assertEquals(listOf(Effect.Arrange(pinned)), pinning.effects)
		pinning.settled(services())
		assertEquals(pinned, preferences.preferences.value.quickAccess)

		// A window is given the sidebar the preference makes: prism's own places that are here, then the folder pinned.
		val placed =
			step(
				explorer,
				Answer.Placed(sidebarOf(desktop.places, root, preferences.preferences.value)),
			).state

		val shown = placed.sidebar.shown

		assertEquals(listOf("Home", "Trash", "System", "projects"), shown.map { it.label })
		assertInstanceOf(SidebarItem.Pinned::class.java, shown.last())

		// One of prism's own is hidden, never removed; a folder pinned is unpinned.
		assertEquals(
			listOf(Effect.Arrange(pinned.showing(Builtin.Home, false))),
			step(placed, BrowserEvent.Unpinned(shown.first())).effects,
		)
		assertEquals(
			listOf(Effect.Arrange(pinned.unpinning(root.resolve("projects")))),
			step(placed, BrowserEvent.Unpinned(shown.last())).effects,
		)
		// Pinning what quick access shows changes nothing; pinning one of prism's own that is hidden shows it again.
		assertEquals(emptyList<Effect>(), step(placed, BrowserEvent.Pinned(root)).effects)

		val hidden = step(placed, BrowserEvent.Unpinned(shown.first())).state

		assertEquals(
			listOf(Effect.Arrange(pinned)),
			step(hidden, BrowserEvent.Pinned(root)).effects,
		)
	}

	@Test
	fun anOlderVersionsPinsAreReadAsQuickAccess() {
		val known = Fixtures.known

		val older =
			Preferences(
				pinned =
					listOf(
						Fixtures.home.resolve("Documents"),
						Path.of("/srv/media"),
						Fixtures.home,
					),
			)

		val quick = quickAccessOf(older, known)

		// The pins in their order, prism's own among them; those of prism's own they left out, hidden, since the user
		// had unpinned them; and the trash and the System page, which are newer than they are, shown.
		assertEquals(
			listOf(
				QuickItem.Own(Builtin.Documents),
				QuickItem.Pinned(Path.of("/srv/media")),
				QuickItem.Own(Builtin.Home),
				QuickItem.Own(Builtin.Desktop, shown = false),
				QuickItem.Own(Builtin.Downloads, shown = false),
				QuickItem.Own(Builtin.Pictures, shown = false),
				QuickItem.Own(Builtin.Music, shown = false),
				QuickItem.Own(Builtin.Videos, shown = false),
				QuickItem.Own(Builtin.Trash),
				QuickItem.Own(Builtin.System),
			),
			quick.items,
		)
		// Nothing written by an older version: prism's own, every one shown.
		assertEquals(QuickAccess.DEFAULT, quickAccessOf(Preferences(), known))
		// Once arranged, what the older version wrote is forgotten.
		assertEquals(null, older.arranging(quick).pinned)
	}

	@Test
	fun theExplorersMenusOfferWhatAFileManagersDo() {
		made("notes.txt", "docs/a.txt")

		val explorer = explorer()
		val file = explorer.rows.list.single { it.name == "notes.txt" }
		val folder = explorer.rows.list.single { it.name == "docs" }

		assertEquals(
			listOf(
				"Open",
				"Open with…",
				"—",
				"Cut",
				"Copy",
				"—",
				"Rename",
				"Copy path",
				"—",
				"Move to trash",
				"Delete permanently",
				"—",
				"Properties",
			),
			labels(contextMenuOf(explorer, Target.Rows(listOf(file)))),
		)
		assertEquals(
			listOf(
				"Open",
				"Open in new window",
				"—",
				"Cut",
				"Copy",
				"Paste into folder",
				"—",
				"Rename",
				"Copy path",
				// Pinning is there with Shift held as the menu opens.
				"Open terminal here",
				"—",
				"Move to trash",
				"Delete permanently",
				"—",
				"Properties",
			),
			labels(contextMenuOf(explorer, Target.Rows(listOf(folder)))),
		)
		assertTrue(
			"Pin to sidebar" in
				labels(
					contextMenuOf(explorer, Target.Rows(listOf(folder)), held = setOf(Held.Shift)),
				),
		)
		assertTrue("New file" in labels(contextMenuOf(explorer, Target.Folder)))
		assertTrue("Paste" in labels(contextMenuOf(explorer, Target.Folder)))
		assertEquals(
			listOf("New window", "Open terminal here", "—", "Preferences…", "Close window"),
			labels(appMenuOf(explorer)),
		)

		// A terminal chosen whose command line has no place for a folder opens in none: its items are off, and its
		// key does nothing.
		val noFolder =
			explorer.copy(
				terminal =
					TerminalChoice.CommandLines.of(
						null,
						(CommandLine.of(listOf("foot", "{folder}", "{command}")) as Read.Right).value,
					)!!,
			)

		fun terminalItems(menu: Menu<BrowserEvent>) =
			menu.entries.filterIsInstance<MenuEntry.Item<BrowserEvent>>().filter {
				it.label ==
					"Open terminal here"
			}

		assertTrue(terminalItems(appMenuOf(explorer)).all { it.enabled })
		assertTrue(terminalItems(appMenuOf(noFolder)).none { it.enabled })
		assertTrue(
			terminalItems(contextMenuOf(noFolder, Target.Rows(listOf(folder)))).none {
				it.enabled
			},
		)
		assertEquals(emptyList<Effect>(), step(noFolder, BrowserEvent.OpenedTerminal()).effects)
		assertEquals(
			listOf(Effect.Terminal(explorer.folder!!)),
			step(explorer, BrowserEvent.OpenedTerminal()).effects,
		)

		// Closing the window ends the explorer's session, as cancelling ends a picker's.
		assertEquals(
			listOf(Effect.Finish(Outcome.Cancelled)),
			step(explorer, BrowserEvent.Cancelled).effects,
		)
	}

	private fun labels(menu: Menu<BrowserEvent>) =
		menu.entries.map {
			when (it) {
				is MenuEntry.Item -> it.label
				is MenuEntry.Submenu -> it.label
				MenuEntry.Separator -> "—"
			}
		}
}
