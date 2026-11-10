package prism.picker

import prism.picker.Fixtures.home
import prism.picker.Fixtures.picker
import java.nio.file.Path
import kotlin.io.path.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reducer's rules, as values: a state, what changed it, and the state and effects that follow. Folders are
 * read, and names looked up, from [Fixtures.Memory].
 */
class StepTest {
	private fun id(name: String) = RowId(name)

	private fun names(state: PickerState) = state.chosenRows.map { it.name }

	private fun chosen(paths: List<Path>) = listOf(Effect.Finish(Outcome.Chosen(paths)))

	/** Folders, as places in the history. */
	private fun folders(vararg paths: Path): List<Location> = paths.map(Location::Folder)

	// ---- going places ----

	@Test
	fun aPickerOpensReadingItsFolder() {
		val first = opened(Choosing.OneFile(), home, home, Fixtures.sidebar)

		assertEquals(Listing.Reading, first.state.listing)
		assertEquals(listOf(Effect.Read(home)), first.effects)
		assertTrue(first.state.rows.isEmpty())
	}

	@Test
	fun aFolderOpenedIsReadAndRemembered() {
		val documents = home.resolve("Documents")
		val opening = step(picker(), PickerEvent.Opened(documents))

		assertEquals(listOf(Effect.Read(documents)), opening.effects)
		assertEquals(History(back = folders(home)), opening.state.history)

		val there =
			opening.state.let {
				step(it, Answer.Arrived(documents, Fixtures.Memory.read(documents))).state
			}

		assertEquals(listOf("Taxes", "cv.pdf", "letter.odt"), there.rows.list.map { it.name })
	}

	@Test
	fun aReadOfAFolderLeftIsTooLate() {
		val leaving = step(picker(), PickerEvent.Opened(home.resolve("Documents"))).state
		val late = step(leaving, Answer.Arrived(home, Fixtures.Memory.read(home))).state

		assertEquals(leaving.listing, late.listing)
	}

	@Test
	fun backAndForwardRetraceTheWay() {
		val documents = home.resolve("Documents")
		val taxes = documents.resolve("Taxes")
		val deep = after(picker(), PickerEvent.Opened(documents), PickerEvent.Opened(taxes))

		val back = after(deep, PickerEvent.Back)

		assertEquals(documents, back.folder)
		assertEquals(History(back = folders(home), forward = folders(taxes)), back.history)

		val forward = after(back, PickerEvent.Forward)

		assertEquals(taxes, forward.folder)
		assertEquals(History(back = folders(home, documents)), forward.history)
		// Going somewhere new forgets what was ahead.
		assertEquals(
			History(back = folders(home, documents)),
			after(back, PickerEvent.Opened(Path("/etc"))).history,
		)
	}

	@Test
	fun goingUpLandsOnTheFolderLeft() {
		val up = after(picker(folder = home.resolve("Documents")), PickerEvent.Up)

		assertEquals(home, up.folder)
		assertEquals(Cursor(id("Documents"), id("Documents")), up.cursor)
		// Landed on, not chosen.
		assertTrue(up.choosing.rows.isEmpty())
	}

	@Test
	fun theRootHasNothingAbove() {
		val root = picker(folder = Path("/"))

		assertEquals(Step(root), step(root, PickerEvent.Up))
	}

	@Test
	fun goingToTheFolderShownReadsItAgainAndKeepsWhatIsChosen() {
		val chosen = after(picker(), PickerEvent.Clicked(id("notes.md"), Click.Only))
		val again = step(chosen, PickerEvent.Opened(home))

		assertEquals(listOf(Effect.Read(home)), again.effects)
		assertEquals(chosen.history, again.state.history)
		assertEquals(listOf("notes.md"), names(after(chosen, PickerEvent.Opened(home))))
	}

	// ---- choosing with the pointer ----

	@Test
	fun aClickChoosesOneRow() {
		val state =
			after(
				picker(),
				PickerEvent.Clicked(id("notes.md"), Click.Only),
				PickerEvent.Clicked(id("todo.txt"), Click.Only),
			)

		assertEquals(listOf("todo.txt"), names(state))
		assertEquals("todo.txt", state.nameField)
		assertEquals(Accept("Open", true), state.accept)
	}

	@Test
	fun ctrlAndShiftChooseSeveralWhereSeveralCanBe() {
		val files = picker(Choosing.Files())

		val toggled =
			after(
				files,
				PickerEvent.Clicked(id("main.kt"), Click.Only),
				PickerEvent.Clicked(id("todo.txt"), Click.Toggle),
			)

		assertEquals(listOf("main.kt", "todo.txt"), names(toggled))
		assertEquals("\"main.kt\" \"todo.txt\"", toggled.nameField)
		assertEquals(
			listOf("main.kt"),
			names(after(toggled, PickerEvent.Clicked(id("todo.txt"), Click.Toggle))),
		)

		// A run, from the row last clicked without Shift.
		val run =
			after(
				files,
				PickerEvent.Clicked(id("main.kt"), Click.Only),
				PickerEvent.Clicked(id("screenshot 10.png"), Click.Range),
			)

		assertEquals(
			listOf(
				"main.kt",
				"notes.md",
				"Quarterly report.pdf",
				"screenshot 2.png",
				"screenshot 10.png",
			),
			names(run),
		)

		// A second Shift-click moves the run's end, not its start.
		assertEquals(
			listOf("Holiday in Lisbon.jpg", "main.kt"),
			names(after(run, PickerEvent.Clicked(id("Holiday in Lisbon.jpg"), Click.Range))),
		)
	}

	@Test
	fun whereOneCanBeChosenCtrlAndShiftChooseOne() {
		val state =
			after(
				picker(),
				PickerEvent.Clicked(id("main.kt"), Click.Only),
				PickerEvent.Clicked(id("todo.txt"), Click.Toggle),
				PickerEvent.Clicked(id("notes.md"), Click.Range),
			)

		assertEquals(listOf("notes.md"), names(state))
	}

	@Test
	fun aClickWhereThereIsNoRowChoosesNothing() {
		val state =
			after(
				picker(),
				PickerEvent.Clicked(id("main.kt"), Click.Only),
				PickerEvent.ClickedNowhere,
			)

		assertTrue(state.choosing.rows.isEmpty())
		assertEquals(Accept("Open", false), state.accept)
	}

	@Test
	fun aDoubleClickOpensAFolderAndAcceptsAFile() {
		assertEquals(
			home.resolve("Music"),
			after(picker(), PickerEvent.Activated(id("Music"))).folder,
		)
		assertEquals(
			chosen(listOf(home.resolve("notes.md"))),
			effectsOf(picker(), PickerEvent.Activated(id("notes.md"))),
		)
	}

	// ---- choosing with the keyboard ----

	@Test
	fun theArrowsMoveTheCursorAndChoose() {
		val first = after(picker(), PickerEvent.Moved(Move.By(1), Carry.Choose))

		// The first move with nothing under the cursor lands on the first row.
		assertEquals(listOf("Desktop"), names(first))

		val third = after(first, PickerEvent.Moved(Move.By(2), Carry.Choose))

		assertEquals(listOf("Downloads"), names(third))
		assertEquals(Cursor(id("Downloads"), id("Downloads")), third.cursor)
		// Up from the top stays at the top; End goes to the last row.
		assertEquals(
			listOf("Desktop"),
			names(after(third, PickerEvent.Moved(Move.By(-10), Carry.Choose))),
		)
		assertEquals(
			listOf("trailer.mp4"),
			names(after(third, PickerEvent.Moved(Move.Last, Carry.Choose))),
		)
	}

	@Test
	fun shiftChoosesARunAndCtrlMovesWithoutChoosing() {
		val files =
			after(picker(Choosing.Files()), PickerEvent.Clicked(id("main.kt"), Click.Only))
		val run = after(files, PickerEvent.Moved(Move.By(2), Carry.Extend))

		assertEquals(listOf("main.kt", "notes.md", "Quarterly report.pdf"), names(run))

		val wandered = after(files, PickerEvent.Moved(Move.By(3), Carry.Stay))

		assertEquals(listOf("main.kt"), names(wandered))
		assertEquals(id("screenshot 2.png"), wandered.cursor?.at)

		// Space then chooses the row the cursor wandered to as well.
		assertEquals(
			listOf("main.kt", "screenshot 2.png"),
			names(after(wandered, PickerEvent.Toggled)),
		)
	}

	@Test
	fun ctrlAChoosesEveryRowShown() {
		val all = after(picker(Choosing.Files()), PickerEvent.ChoseAll)

		val one = picker()

		assertEquals(all.rows.size, all.choosing.rows.size)
		assertEquals(Step(one), step(one, PickerEvent.ChoseAll))
	}

	@Test
	fun lettersTypedGoToTheRowTheyStart() {
		val s = after(picker(), PickerEvent.Jumped("s"))

		assertEquals(listOf("screenshot 2.png"), names(s))
		// The same letter again goes on to the next row it starts.
		assertEquals(listOf("screenshot 10.png"), names(after(s, PickerEvent.Jumped("ss"))))
		// More letters narrow it from where the cursor is.
		assertEquals(listOf("song.flac"), names(after(s, PickerEvent.Jumped("so"))))
		// Nothing starting so: nothing moves.
		assertEquals(listOf("screenshot 2.png"), names(after(s, PickerEvent.Jumped("zz"))))
	}

	// ---- arranging ----

	@Test
	fun aHeaderClickedSortsByItAndAgainTheOtherWay() {
		val bySize = after(picker(), PickerEvent.Sorted(Sort.Column.Size))

		assertEquals(Sort(Sort.Column.Size, ascending = false), bySize.arrangement.sort)
		assertEquals(
			"backup.tar.gz",
			bySize.rows.list
				.first { !it.isFolder }
				.name,
		)

		val smallest = after(bySize, PickerEvent.Sorted(Sort.Column.Size))

		assertEquals(
			"todo.txt",
			smallest.rows.list
				.first { !it.isFolder }
				.name,
		)
	}

	@Test
	fun whatIsNoLongerShownIsNoLongerChosen() {
		val chosen =
			after(
				picker(Choosing.Files()),
				PickerEvent.Clicked(id("main.kt"), Click.Only),
				PickerEvent.Clicked(id("todo.txt"), Click.Toggle),
			)

		val searched = after(chosen, PickerEvent.Searched("todo"))

		assertEquals(listOf("todo.txt"), names(searched))
		assertEquals(id("todo.txt"), searched.cursor?.at)
		assertEquals(listOf("todo.txt"), searched.rows.list.map { it.name })
	}

	@Test
	fun hiddenFilesAreShownAndHiddenFoldersAreNotUntilTheUserSaysOtherwise() {
		val opened =
			opened(
				Choosing.OneFile(),
				home,
				home,
				Fixtures.sidebar,
			).settled(Fixtures.Memory)

		assertEquals(Hidden(files = true, folders = false), opened.arrangement.hidden)
		assertEquals(
			listOf(".bashrc"),
			opened.rows.list
				.filter { it.hidden }
				.map { it.name },
		)

		val folders = after(opened, PickerEvent.ToggledHiddenFolders)

		assertEquals(
			listOf(".config", ".bashrc"),
			folders.rows.list
				.filter {
					it.hidden
				}.map { it.name },
		)
		assertEquals(
			listOf(
				".config",
			),
			after(folders, PickerEvent.ToggledHiddenFiles)
				.rows.list
				.filter {
					it.hidden
				}.map { it.name },
		)
	}

	@Test
	fun ctrlHShowsEveryHiddenEntryOrNone() {
		val all = after(picker(), PickerEvent.ToggledEveryHidden)

		assertEquals(Hidden(files = true, folders = true), all.arrangement.hidden)
		assertEquals(
			Fixtures.NO_HIDDEN,
			after(all, PickerEvent.ToggledEveryHidden).arrangement.hidden,
		)
		// From the usual (files shown, folders not), it shows every one.
		assertEquals(
			Hidden(files = true, folders = true),
			after(picker(hidden = Hidden()), PickerEvent.ToggledEveryHidden).arrangement.hidden,
		)
	}

	@Test
	fun aFilterChosenShowsOnlyItsKindAndWithCtrlAddsIt() {
		fun shown(state: PickerState) = state.rows.list.map { it.name }

		val images = after(picker(), PickerEvent.ShowedOnlyKind(Kind.Images))

		assertEquals(
			listOf("Holiday in Lisbon.jpg", "screenshot 2.png", "screenshot 10.png"),
			shown(images),
		)
		// Another chosen plainly replaces it; chosen with Ctrl, it is added.
		assertEquals(
			listOf("trailer.mp4"),
			shown(after(images, PickerEvent.ShowedOnlyKind(Kind.Videos))),
		)
		assertEquals(
			setOf(Kind.Images, Kind.Videos),
			after(images, PickerEvent.ToggledKind(Kind.Videos)).arrangement.kinds,
		)
	}

	@Test
	fun aKindChosenShowsOnlyThatKindAndSeveralShowThemTogether() {
		fun shown(state: PickerState) = state.rows.list.map { it.name }

		val images = after(picker(), PickerEvent.ToggledKind(Kind.Images))

		assertEquals(
			listOf("Holiday in Lisbon.jpg", "screenshot 2.png", "screenshot 10.png"),
			shown(images),
		)

		val andVideos = after(images, PickerEvent.ToggledKind(Kind.Videos))

		assertEquals(
			listOf(
				"Holiday in Lisbon.jpg",
				"screenshot 2.png",
				"screenshot 10.png",
				"trailer.mp4",
			),
			shown(andVideos),
		)
		// Turned off again, a kind leaves the rest; every kind shown again is everything.
		assertEquals(
			listOf("trailer.mp4"),
			shown(after(andVideos, PickerEvent.ToggledKind(Kind.Images))),
		)
		assertEquals(shown(picker()), shown(after(andVideos, PickerEvent.ShowedEveryKind)))
	}

	@Test
	fun foldersAreAKindLikeAnyOther() {
		val state =
			after(
				picker(),
				PickerEvent.ToggledKind(Kind.Folders),
				PickerEvent.ToggledKind(Kind.Audio),
			)

		assertEquals(
			listOf(
				"Desktop",
				"Documents",
				"Downloads",
				"Music",
				"Pictures",
				"Projects",
				"Videos",
				"song.flac",
			),
			state.rows.list.map { it.name },
		)
	}

	@Test
	fun hiddenEntriesAreShownOrNotWhateverKindsAreChosen() {
		val folders =
			after(
				picker(hidden = Hidden(files = true, folders = true)),
				PickerEvent.ShowedOnlyKind(Kind.Folders),
			)

		assertTrue(folders.rows.list.any { it.name == ".config" })

		val hiding = after(folders, PickerEvent.ToggledHiddenFolders)

		assertFalse(hiding.rows.list.any { it.name == ".config" })
		assertEquals(setOf(Kind.Folders), hiding.arrangement.kinds)
	}

	@Test
	fun theKindsChosenStayFromFolderToFolder() {
		val pictures =
			after(
				picker(),
				PickerEvent.ToggledKind(Kind.Images),
				PickerEvent.Opened(home.resolve("Pictures")),
			)

		assertEquals(setOf(Kind.Images), pictures.arrangement.kinds)
		assertEquals(listOf("cat.png"), pictures.rows.list.map { it.name })
	}

	@Test
	fun theStatusCountsEveryEntryThatCouldBeShown() {
		val images = after(picker(), PickerEvent.ShowedOnlyKind(Kind.Images))

		assertEquals(3, images.rows.size)
		// Every entry the folder holds, hidden ones too: "3 of 20 items".
		assertEquals(20, images.entryCount)
	}

	@Test
	fun aRowAFilterLeavesOutIsNoLongerChosen() {
		val state =
			after(
				picker(),
				PickerEvent.Clicked(id("main.kt"), Click.Only),
				PickerEvent.ToggledKind(Kind.Images),
			)

		assertTrue(state.choosing.rows.isEmpty())
		assertNull(state.cursor)
	}

	@Test
	fun theLayoutIsKeptFromFolderToFolder() {
		val gallery =
			after(
				picker(),
				PickerEvent.Viewed(Layout.Gallery),
				PickerEvent.Opened(home.resolve("Pictures")),
			)

		assertEquals(Layout.Gallery, gallery.layout)
		assertEquals(Layout.List, after(gallery, PickerEvent.Viewed(Layout.List)).layout)
	}

	@Test
	fun aFilterShowsItsFilesAndEveryFolder() {
		val images = after(picker(), PickerEvent.Filtered(Fixtures.filters[1]))

		assertEquals(
			listOf("Holiday in Lisbon.jpg", "screenshot 2.png", "screenshot 10.png"),
			images.rows.list
				.filterNot { it.isFolder }
				.map { it.name },
		)
		assertTrue(images.rows.list.any { it.isFolder })

		// A filter the picker does not offer is not taken.
		val all = picker()

		assertEquals(
			Step(all),
			step(all, PickerEvent.Filtered(TypeFilter("Other", listOf("*.x")))),
		)
	}

	@Test
	fun aFolderPickerShowsOnlyFolders() {
		val folders = picker(Choosing.Folder())

		assertTrue(folders.rows.list.all { it.isFolder })
		// Accepted with nothing chosen, it gives the folder shown, which the name field says faintly.
		assertEquals("Home", folders.nameHint)
	}

	@Test
	fun aReadAgainKeepsWhatIsStillThere() {
		val chosen =
			after(
				picker(Choosing.Files()),
				PickerEvent.Clicked(id("main.kt"), Click.Only),
				PickerEvent.Clicked(id("todo.txt"), Click.Toggle),
			)

		val fewer = Listing.Shown(Fixtures.homeRows.filterNot { it.name == "todo.txt" })
		val again = step(chosen, Answer.Arrived(home, fewer)).state

		assertEquals(listOf("main.kt"), names(again))
		assertNull(again.cursor)
	}

	// ---- accepting ----

	@Test
	fun acceptingAFileOpensIt() {
		val state = after(picker(), PickerEvent.Clicked(id("notes.md"), Click.Only))

		assertEquals(
			chosen(listOf(home.resolve("notes.md"))),
			effectsOf(state, PickerEvent.Accepted),
		)
	}

	@Test
	fun acceptingAFolderGoesIntoIt() {
		val state = after(picker(), PickerEvent.Clicked(id("Documents"), Click.Only))

		// A folder's name is not one to open, so the name field stays empty; the button still opens the folder.
		assertEquals("", state.nameField)
		assertEquals(Accept("Open", true), state.accept)
		assertEquals(home.resolve("Documents"), after(state, PickerEvent.Accepted).folder)
	}

	@Test
	fun acceptingSeveralOpensTheFilesAmongThem() {
		val state =
			after(
				picker(Choosing.Files()),
				PickerEvent.Clicked(id("Music"), Click.Only),
				PickerEvent.Clicked(id("todo.txt"), Click.Toggle),
				PickerEvent.Clicked(id("main.kt"), Click.Toggle),
			)

		assertEquals(
			chosen(listOf(home.resolve("main.kt"), home.resolve("todo.txt"))),
			effectsOf(state, PickerEvent.Accepted),
		)
	}

	@Test
	fun aFolderPickerGivesTheFolderChosenOrTheOneShown() {
		val folders = picker(Choosing.Folder())

		assertEquals(chosen(listOf(home)), effectsOf(folders, PickerEvent.Accepted))
		assertEquals(
			chosen(listOf(home.resolve("Music"))),
			effectsOf(
				after(folders, PickerEvent.Clicked(id("Music"), Click.Only)),
				PickerEvent.Accepted,
			),
		)
		// A double click goes into it instead.
		assertEquals(
			home.resolve("Music"),
			after(folders, PickerEvent.Activated(id("Music"))).folder,
		)
	}

	@Test
	fun aNameTypedIsLookedUpBeforeItIsOpened() {
		val typed = after(picker(), PickerEvent.Named("notes.md"))
		val looking = step(typed, PickerEvent.Accepted)
		val lookup = Lookup.Accepting("notes.md", listOf(home.resolve("notes.md")))

		assertEquals(listOf(Effect.Look(lookup)), looking.effects)
		assertEquals(lookup, looking.state.waiting)

		val answered =
			step(
				looking.state,
				Answer.Looked(
					lookup,
					mapOf(home.resolve("notes.md") to Found.File),
				),
			)

		assertEquals(chosen(listOf(home.resolve("notes.md"))), answered.effects)
	}

	@Test
	fun aPathTypedIsGoneToOrOpened() {
		assertEquals(
			home.resolve("Documents/Taxes"),
			after(picker(), PickerEvent.Named("Documents/Taxes"), PickerEvent.Accepted).folder,
		)
		assertEquals(
			Path("/etc"),
			after(picker(), PickerEvent.Named("/etc"), PickerEvent.Accepted).folder,
		)
		assertEquals(
			home.resolve("Pictures"),
			after(
				picker(folder = Path("/etc")),
				PickerEvent.Named("~/Pictures"),
				PickerEvent.Accepted,
			).folder,
		)

		val typed = after(picker(), PickerEvent.Named("~/Documents/cv.pdf"))

		assertEquals(
			chosen(listOf(home.resolve("Documents/cv.pdf"))),
			step(typed, PickerEvent.Accepted).settledEffects(),
		)
	}

	@Test
	fun aNameThatIsNotThereIsSaidToBeNotFound() {
		val missing = after(picker(), PickerEvent.Named("nothing.txt"), PickerEvent.Accepted)

		assertEquals(Notice.NotFound("nothing.txt"), missing.notice)

		// While the notice is shown, nothing else is done; put away, the picker is as it was.
		assertEquals(Step(missing), step(missing, PickerEvent.Up))
		assertNull(after(missing, PickerEvent.Dismissed).notice)
	}

	@Test
	fun anAnswerNoLongerWaitedForIsTooLate() {
		val looking =
			step(
				after(picker(), PickerEvent.Named("notes.md")),
				PickerEvent.Accepted,
			).state

		val lookup = looking.waiting!!
		val moved = step(looking, PickerEvent.Opened(home.resolve("Music"))).state

		assertEquals(
			Step(moved),
			step(
				moved,
				Answer.Looked(
					lookup,
					mapOf(home.resolve("notes.md") to Found.File),
				),
			),
		)
	}

	// ---- saving ----

	@Test
	fun savingUnderANewNameFinishes() {
		val save = picker(Choosing.SaveAs("draft.txt"))

		assertEquals(Accept("Save", true), save.accept)
		assertEquals(
			chosen(listOf(home.resolve("draft.txt"))),
			step(save, PickerEvent.Accepted).settledEffects(),
		)
		assertEquals(Accept("Save", false), after(save, PickerEvent.Named("  ")).accept)
	}

	@Test
	fun savingOverAFileAsksFirst() {
		val save =
			after(
				picker(Choosing.SaveAs("draft.txt")),
				PickerEvent.Clicked(id("notes.md"), Click.Only),
			)

		// Choosing a file gives its name.
		assertEquals("notes.md", save.nameField)

		val asked = after(save, PickerEvent.Accepted)

		assertEquals(Notice.Replace(home.resolve("notes.md")), asked.notice)
		assertEquals(
			chosen(listOf(home.resolve("notes.md"))),
			effectsOf(asked, PickerEvent.Confirmed),
		)
		assertNull(after(asked, PickerEvent.Dismissed).notice)
	}

	@Test
	fun savingWithAFolderChosenGoesIntoIt() {
		val save =
			after(
				picker(Choosing.SaveAs("draft.txt")),
				PickerEvent.Clicked(id("Documents"), Click.Only),
			)

		assertEquals(Accept("Open", true), save.accept)
		assertEquals("draft.txt", save.nameField)

		val there = after(save, PickerEvent.Accepted)

		assertEquals(home.resolve("Documents"), there.folder)
		assertEquals("draft.txt", there.nameField)
		// Typing a name lets the folder go, so the button saves again.
		assertEquals(Accept("Save", true), after(save, PickerEvent.Named("other.txt")).accept)
	}

	@Test
	fun savingInAFolderThatIsNotThereIsNotFound() {
		val save =
			after(
				picker(Choosing.SaveAs("draft.txt")),
				PickerEvent.Named("nowhere/draft.txt"),
				PickerEvent.Accepted,
			)

		assertEquals(Notice.NotFound(home.resolve("nowhere").toString()), save.notice)
	}

	// ---- the path across the top ----

	@Test
	fun aPathTypedAcrossTheTopIsGoneTo() {
		val editing = after(picker(), PickerEvent.EditedAddress)

		assertEquals(Address.Editing(home.toString()), editing.address)

		val there = after(editing, PickerEvent.AddressTyped("/etc"), PickerEvent.Went)

		assertEquals(Path("/etc"), there.folder)
		assertEquals(Address.Crumbs, there.address)
	}

	@Test
	fun aFileTypedAcrossTheTopIsChosenInItsFolder() {
		val there =
			after(
				picker(),
				PickerEvent.EditedAddress,
				PickerEvent.AddressTyped("~/Documents/cv.pdf"),
				PickerEvent.Went,
			)

		assertEquals(home.resolve("Documents"), there.folder)
		assertEquals(listOf("cv.pdf"), names(there))
		assertEquals("cv.pdf", there.nameField)
	}

	@Test
	fun aPathThatIsNotThereLeavesItToBeTypedAgain() {
		val there =
			after(
				picker(),
				PickerEvent.EditedAddress,
				PickerEvent.AddressTyped("/nowhere"),
				PickerEvent.Went,
			)

		assertEquals(Notice.NotFound("/nowhere"), there.notice)
		assertEquals(Address.Editing("/nowhere"), there.address)
		assertEquals(home, there.folder)
	}

	// ---- changing what is on disk ----

	@Test
	fun aRenameIsAskedOfTheDiskAndLandsOnTheNewName() {
		val renaming = after(picker(), PickerEvent.StartedRenaming(id("notes.md")))

		assertEquals(Renaming(id("notes.md"), "notes.md"), renaming.renaming)
		assertEquals(listOf("notes.md"), names(renaming))

		val done =
			step(after(renaming, PickerEvent.RenameTyped("journal.md")), PickerEvent.Renamed)
		val change = Change.Rename(home.resolve("notes.md"), home.resolve("journal.md"))

		assertEquals(listOf(Effect.Make(change)), done.effects)
		assertNull(done.state.renaming)

		// The disk did it: the folder is read again, and the cursor lands on the new name, chosen.
		val answered =
			step(
				done.state,
				Answer.Changed(change, ChangeResult.Done(Change.Rename(change.to, change.from))),
			)

		assertEquals(listOf(Effect.Read(home)), answered.effects)
		assertEquals(Landing.On(id("journal.md"), choose = true), answered.state.landing)

		val renamed =
			Fixtures.homeRows.map {
				if (it.name ==
					"notes.md"
				)
					Fixtures.row("journal.md", 6_250)
				else
					it
			}

		val arrived = step(answered.state, Answer.Arrived(home, Listing.Shown(renamed))).state

		assertEquals(listOf("journal.md"), names(arrived))
		assertNull(arrived.landing)
	}

	@Test
	fun aNameThatCannotBeANameIsRefusedBeforeTheDiskIsAsked() {
		val refused =
			step(
				after(
					picker(),
					PickerEvent.StartedRenaming(id("notes.md")),
					PickerEvent.RenameTyped("a/b"),
				),
				PickerEvent.Renamed,
			)

		assertTrue(refused.effects.isEmpty())
		assertInstanceOf(Notice.Failed::class.java, refused.state.notice)
		// Still being typed, to be put right once the notice is put away.
		assertEquals("a/b", refused.state.renaming?.text)
		// The same name renames nothing, and Escape leaves it.
		assertTrue(
			step(
				after(picker(), PickerEvent.StartedRenaming(id("notes.md"))),
				PickerEvent.Renamed,
			).effects.isEmpty(),
		)
		assertNull(
			after(
				picker(),
				PickerEvent.StartedRenaming(id("notes.md")),
				PickerEvent.RenameCancelled,
			).renaming,
		)
		assertEquals(null, refusalOf("fine name.txt"))
		assertTrue(listOf("..", ".", "x".repeat(256)).all { refusalOf(it) != null })
	}

	@Test
	fun whatTheDiskRefusesIsSaidWithWhy() {
		val change = Change.Rename(home.resolve("notes.md"), home.resolve("todo.txt"))

		val failed =
			step(
				picker(),
				Answer.Changed(
					change,
					ChangeResult.Failed("There is already something called “todo.txt” here."),
				),
			)

		assertEquals(
			Notice.Failed(
				"Couldn't rename “notes.md”",
				"There is already something called “todo.txt” here.",
			),
			failed.state.notice,
		)
		// Read again all the same: a change half done is a change.
		assertEquals(listOf(Effect.Read(home)), failed.effects)
	}

	@Test
	fun theTrashIsUsedWithoutAsking() {
		assertEquals(
			listOf(
				Effect.Make(Change.Trash(listOf(home.resolve("main.kt"), home.resolve("todo.txt")))),
			),
			effectsOf(picker(), PickerEvent.Trashed(listOf(id("todo.txt"), id("main.kt")))),
		)
		// A row the listing does not show is not trashed.
		assertTrue(effectsOf(picker(), PickerEvent.Trashed(listOf(id("not there")))).isEmpty())
	}

	@Test
	fun theDeleteKeyAsksBeforeTheTrash() {
		val asked = after(picker(), PickerEvent.AskedToTrash(listOf(id("main.kt"))))

		assertEquals(Notice.ConfirmTrash(listOf(home.resolve("main.kt"))), asked.notice)
		assertEquals(
			listOf(Effect.Make(Change.Trash(listOf(home.resolve("main.kt"))))),
			effectsOf(asked, PickerEvent.Confirmed),
		)
		assertTrue(effectsOf(asked, PickerEvent.Dismissed).isEmpty())
	}

	@Test
	fun aChangeDoneCanBeUndoneAndRedone() {
		val rename = Change.Rename(home.resolve("notes.md"), home.resolve("journal.md"))
		val back = Change.Rename(home.resolve("journal.md"), home.resolve("notes.md"))
		val done = step(picker(), Answer.Changed(rename, ChangeResult.Done(back))).state

		// Done, what undoes it is kept.
		assertEquals(Changes(undo = listOf(back)), done.changes)

		val undoing = step(done, PickerEvent.Undone)

		assertEquals(listOf(Effect.Make(back, Doing.Undo)), undoing.effects)
		assertEquals(Changes(), undoing.state.changes)

		// Undone, what does it again is kept to redo.
		val undone =
			step(
				undoing.state,
				Answer.Changed(back, ChangeResult.Done(rename), Doing.Undo),
			).state

		assertEquals(Changes(redo = listOf(rename)), undone.changes)

		val redoing = step(undone, PickerEvent.Redone)

		assertEquals(listOf(Effect.Make(rename, Doing.Redo)), redoing.effects)
		assertEquals(
			Changes(undo = listOf(back)),
			step(
				redoing.state,
				Answer.Changed(rename, ChangeResult.Done(back), Doing.Redo),
			).state.changes,
		)
		// With nothing to undo or to redo, nothing is done.
		assertTrue(effectsOf(picker(), PickerEvent.Undone).isEmpty())
		assertTrue(effectsOf(picker(), PickerEvent.Redone).isEmpty())
	}

	@Test
	fun aNewChangeLeavesNothingToRedo() {
		val redoable =
			picker().copy(
				changes = Changes(redo = listOf(Change.MakeFolder(home.resolve("x")))),
			)

		val folder = home.resolve("y")

		val made =
			step(
				redoable,
				Answer.Changed(
					Change.MakeFolder(folder),
					ChangeResult.Done(Change.RemoveFolder(folder)),
				),
			).state

		assertEquals(Changes(undo = listOf(Change.RemoveFolder(folder))), made.changes)
	}

	@Test
	fun whatIsPutBackIsChosenAndAnUndoThatFailsIsSaidSo() {
		val back = Change.PutBack(listOf(Trashed(home.resolve("main.kt"), "main.kt")))

		val put =
			step(
				picker(),
				Answer.Changed(
					back,
					ChangeResult.Done(Change.Trash(listOf(home.resolve("main.kt")))),
					Doing.Undo,
				),
			)

		assertEquals(Landing.On(id("main.kt"), choose = true), put.state.landing)

		val failed =
			step(
				picker(),
				Answer.Changed(
					back,
					ChangeResult.Failed("“main.kt” isn't in the trash any more."),
					Doing.Undo,
				),
			)

		assertEquals(
			Notice.Failed(
				"Couldn't undo the move to the trash",
				"“main.kt” isn't in the trash any more.",
			),
			failed.state.notice,
		)
	}

	@Test
	fun aNewFolderHasAFreeNameAndItsNameTypedOver() {
		val made = step(picker(), PickerEvent.MadeFolder)
		val change = Change.MakeFolder(home.resolve("New folder"))

		assertEquals(listOf(Effect.Make(change)), made.effects)

		val answered =
			step(
				made.state,
				Answer.Changed(change, ChangeResult.Done(Change.RemoveFolder(change.path))),
			).state

		assertEquals(Landing.Renaming(id("New folder")), answered.landing)

		val arrived =
			step(
				answered,
				Answer.Arrived(
					home,
					Listing.Shown(Fixtures.homeRows + Fixtures.row("New folder")),
				),
			).state

		assertEquals(Renaming(id("New folder"), "New folder"), arrived.renaming)
		// With one there, the next is "New folder 2".
		assertEquals(
			listOf(Effect.Make(Change.MakeFolder(home.resolve("New folder 2")))),
			effectsOf(after(arrived, PickerEvent.RenameCancelled), PickerEvent.MadeFolder),
		)
	}

	@Test
	fun pathsAreCopiedOneToALine() {
		assertEquals(
			listOf(Effect.Copy("/home/avery/a\n/home/avery/b")),
			effectsOf(
				picker(),
				PickerEvent.CopiedPaths(listOf(home.resolve("a"), home.resolve("b"))),
			),
		)
	}

	@Test
	fun pickedIsChosenAndAcceptedAtOnce() {
		assertEquals(
			chosen(listOf(home.resolve("Music"))),
			effectsOf(picker(Choosing.Folder()), PickerEvent.Picked(id("Music"))),
		)
		assertEquals(
			chosen(listOf(home.resolve("notes.md"))),
			effectsOf(picker(), PickerEvent.Picked(id("notes.md"))),
		)
	}

	// ---- how it is drawn ----

	@Test
	fun theListZoomsInStepsUpToTwiceItsSize() {
		val larger = after(picker(), PickerEvent.Zoomed(1))

		assertEquals(Zoom.By(3), larger.zoom)
		assertEquals(1.25f, larger.scale)
		// As far as there are sizes for a list.
		assertEquals(Zoom.LIST_LARGEST, after(larger, PickerEvent.Zoomed(10)).zoom)
		assertEquals(Zoom.By(0), after(larger, PickerEvent.Zoomed(-10)).zoom)

		// The gallery has a size of its own, and the list keeps its own meanwhile.
		val gallery = after(larger, PickerEvent.Viewed(Layout.Gallery))

		assertEquals(Zoom.NORMAL, gallery.zoom)
		assertEquals(Zoom.By(3), after(gallery, PickerEvent.Viewed(Layout.List)).zoom)
		assertEquals(Zoom.NORMAL, after(larger, PickerEvent.Unzoomed).zoom)
	}

	@Test
	fun theGalleryZoomsUpToOneTileAtATimeAsTheWindowAllows() {
		// Room for a tile 4.2 times its usual size.
		val gallery =
			after(picker(), PickerEvent.Viewed(Layout.Gallery), PickerEvent.Measured(4.2f))
		val largest = after(gallery, PickerEvent.Zoomed(100))

		assertEquals(Zoom.Whole, largest.zoom)
		assertEquals(4.2f, largest.scale)
		// A step down from the whole is the largest size smaller than it, four times; a step up from four is the whole,
		// since five would not fit.
		assertEquals(Zoom.By(8), after(largest, PickerEvent.Zoomed(-1)).zoom)
		assertEquals(
			Zoom.Whole,
			after(gallery, PickerEvent.Zoomed(6), PickerEvent.Zoomed(1)).zoom,
		)
	}

	@Test
	fun aWindowMadeSmallerMakesTheTilesSmallerWithIt() {
		val four =
			after(
				picker(),
				PickerEvent.Viewed(Layout.Gallery),
				PickerEvent.Measured(6f),
				PickerEvent.Zoomed(6),
			)

		assertEquals(Zoom.By(8), four.zoom)
		assertEquals(4f, four.scale)

		// Room only for 2.2: drawn as large as fits, the size chosen kept for when there is room again.
		val smaller = after(four, PickerEvent.Measured(2.2f))

		assertEquals(2.2f, smaller.scale)
		assertEquals(4f, after(smaller, PickerEvent.Measured(6f)).scale)
		// A step smaller is smaller than what is shown, not than what was chosen: two times.
		assertEquals(Zoom.By(5), after(smaller, PickerEvent.Zoomed(-1)).zoom)
		// One at a time stays one at a time, whatever the room.
		assertEquals(
			2.2f,
			after(four, PickerEvent.Zoomed(100), PickerEvent.Measured(2.2f)).scale,
		)
	}

	@Test
	fun aMenuSortsAsItSays() {
		val sort = Sort(Sort.Column.Size, ascending = true)

		assertEquals(sort, after(picker(), PickerEvent.SortedBy(sort)).arrangement.sort)
	}

	// ---- cancelling ----

	@Test
	fun cancellingEndsThePickerWithNothing() {
		assertEquals(
			listOf(Effect.Finish(Outcome.Cancelled)),
			effectsOf(picker(), PickerEvent.Cancelled),
		)
	}

	// ---- what is typed ----

	@Test
	fun namesAreReadAsTheNameFieldWritesThem() {
		assertEquals(listOf("a.txt"), namesIn(" a.txt "))
		assertEquals(listOf("a b.txt", "c.txt"), namesIn("\"a b.txt\" \"c.txt\""))
		assertEquals(emptyList<String>(), namesIn("  "))
		assertEquals(home.resolve("x"), pathOf("~/x", Path("/etc"), home))
		assertEquals(Path("/etc/hosts"), pathOf("../etc/hosts", Path("/home"), home))
		assertNull(pathOf("bad\u0000name", home, home))
	}

	@Test
	fun theSideButtonsAreXsEightAndNineWhichAwtNumbersSixAndSeven() {
		assertEquals(PickerEvent.Back, sideButtonOf(6))
		assertEquals(PickerEvent.Forward, sideButtonOf(7))
		// AWT's 4 and 5 are X's sideways scroll on Linux, and the rest are clicks: none of them goes anywhere.
		assertEquals(
			listOf(null, null, null, null, null),
			listOf(1, 2, 3, 4, 5).map(::sideButtonOf),
		)
	}

	@Test
	fun patternsAreMatchedAsAShellMatchesThem() {
		val filter =
			TypeFilter("Some", listOf("*.png", "report-??.txt", "[ab]*.md", "[!x]*.kt"))

		assertTrue(filter.admits("cat.PNG"))
		assertTrue(filter.admits("report-07.txt"))
		assertFalse(filter.admits("report-7.txt"))
		assertTrue(filter.admits("about.md"))
		assertFalse(filter.admits("notes.md"))
		assertTrue(filter.admits("main.kt"))
		assertFalse(filter.admits("xmain.kt"))
		assertFalse(filter.admits("cat.png.bak"))
	}

	/** The effects that remain once every folder and name this asks for has been answered by the memory disk. */
	private fun Step.settledEffects(): List<Effect> {
		val asking = effects.filterIsInstance<Effect.Asking>()

		return if (asking.isEmpty())
			effects
		else
			asking
				.fold(Step(state)) { at, effect ->
					step(at.state, Fixtures.Memory.answer(effect)).let {
						Step(
							it.state,
							at.effects + it.effects,
						)
					}
				}.settledEffects()
	}

	@Test
	fun aStateCannotChooseOrPointAtARowItDoesNotShow() {
		val state = picker()

		assertInstanceOf(
			IllegalArgumentException::class.java,
			runCatching {
				state.copy(choosing = Choosing.OneFile(id("not there.txt")))
			}.exceptionOrNull(),
		)
		assertInstanceOf(
			IllegalArgumentException::class.java,
			runCatching {
				state.copy(cursor = Cursor(id("not there.txt"), id("not there.txt")))
			}.exceptionOrNull(),
		)
	}
}
