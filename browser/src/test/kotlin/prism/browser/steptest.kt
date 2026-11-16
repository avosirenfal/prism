package prism.browser

import androidx.compose.ui.input.pointer.PointerButton
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.Fixtures.home
import prism.browser.Fixtures.picker
import prism.desktop.Trashed
import prism.preferences.Align
import prism.preferences.ColumnId
import prism.preferences.Filters
import prism.preferences.Hidden
import prism.preferences.Kind
import prism.preferences.Layout
import prism.preferences.ListingKind
import prism.preferences.Listings
import prism.preferences.Look
import prism.preferences.Order
import prism.preferences.Role
import prism.preferences.Sort

/**
 * The reducer's rules, as values: a state, what changed it, and the state and effects that follow. Folders are
 * read, and names looked up, from [Fixtures.Memory].
 */
class StepTest {
	private fun id(name: String) = RowId(name)

	private fun names(state: BrowserState) = state.chosenRows.map { it.name }

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
		val opening = step(picker(), BrowserEvent.Opened(documents))

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
		val leaving = step(picker(), BrowserEvent.Opened(home.resolve("Documents"))).state
		val late = step(leaving, Answer.Arrived(home, Fixtures.Memory.read(home))).state

		assertEquals(leaving.listing, late.listing)
	}

	@Test
	fun backAndForwardRetraceTheWay() {
		val documents = home.resolve("Documents")
		val taxes = documents.resolve("Taxes")
		val deep = after(picker(), BrowserEvent.Opened(documents), BrowserEvent.Opened(taxes))

		val back = after(deep, BrowserEvent.Back)

		assertEquals(documents, back.folder)
		assertEquals(History(back = folders(home), forward = folders(taxes)), back.history)

		val forward = after(back, BrowserEvent.Forward)

		assertEquals(taxes, forward.folder)
		assertEquals(History(back = folders(home, documents)), forward.history)
		// Going somewhere new forgets what was ahead.
		assertEquals(
			History(back = folders(home, documents)),
			after(back, BrowserEvent.Opened(Path("/etc"))).history,
		)
	}

	@Test
	fun aListingIsOrderedByWhenItsFilesWereMadeThoseNotKnownLastAndTheDateColumnSaysWhichTime() {
		val folder = home.resolve("Few")
		val old = Instant.parse("2020-01-01T00:00:00Z")
		val new = Instant.parse("2025-01-01T00:00:00Z")

		val state =
			step(
				opened(Choosing.Exploring(), folder, home, Fixtures.sidebar).state,
				Answer.Arrived(
					folder,
					Listing.Shown(
						mapOf("a" to old, "b" to null, "c" to new, "d" to null).map { (name, made) ->
							Fixtures
								.row(name, 1, folder = folder)
								.copy(created = made?.let(Created::At) ?: Created.Unkept)
						},
					),
				),
			).state

		fun order(state: BrowserState) = (0 until state.rows.size).map { state.rows[it].name }

		val newest = step(state, BrowserEvent.Sorted(ColumnId.Created)).state

		assertEquals(listOf("c", "a", "b", "d"), order(newest))
		assertEquals(
			listOf("a", "c", "b", "d"),
			order(step(newest, BrowserEvent.Sorted(ColumnId.Created)).state),
		)

		// When files were made is a column of its own, shown as any other is.
		assertFalse(state.columns.isShown(ColumnId.Created))
		assertTrue(
			step(
				state,
				BrowserEvent.ShowedColumn(ColumnId.Created, true),
			).state.columns.isShown(ColumnId.Created),
		)
	}

	@Test
	fun aWindowOpensInTheDefaultLookAndKeepsTheLookItIsGivenWhereverItGoes() {
		val newest = Sort(ColumnId.Modified, ascending = false)
		val look =
			Look(Order.By(newest)).let {
				it.with(it.columns(ListingKind.Folder).showing(ColumnId.Type, false))
			}

		val first =
			opened(
				Choosing.Exploring(),
				home.resolve("Documents"),
				home,
				Fixtures.sidebar,
				listings = Listings().looking(Role.Explorer, ListingKind.Folder, look),
			).state

		assertEquals(newest, first.look.order.sort)
		assertFalse(first.columns.isShown(ColumnId.Type))

		// Ordered otherwise by its headings, its columns shown and aligned otherwise, it keeps them as it goes up, and on.
		val changed =
			listOf(
				BrowserEvent.Sorted(ColumnId.Size),
				BrowserEvent.ShowedColumn(ColumnId.Type, true),
				BrowserEvent.AlignedColumn(ColumnId.Type, Align.End),
			).fold(first) { state, event -> step(state, event).state }

		assertEquals(
			ColumnId.Size,
			changed.look.order.sort
				?.by,
		)
		assertTrue(changed.columns.isShown(ColumnId.Type))
		assertEquals(
			Align.End,
			changed.columns.settings
				.single {
					it.id == ColumnId.Type
				}.aligned,
		)
		assertEquals(changed.look, step(changed, BrowserEvent.Up).state.look)
		// A picker opened from the same preferences looks as the explorer would, where they say nothing of pickers.
		assertEquals(
			newest,
			opened(
				Choosing.OneFile(),
				home,
				home,
				Fixtures.sidebar,
				listings = Listings().looking(Role.Explorer, ListingKind.Folder, look),
			).state.look.order.sort,
		)
	}

	@Test
	fun theDefaultsChanged_AWindowTakesUpWhatChanged_AndKeepsTheRestAsItMadeIt() {
		val first =
			opened(Choosing.Exploring(), home, home, Fixtures.sidebar).state

		// Made its own in the window: folders ordered by size, the trash by name, and a gallery's hidden files left out.
		val made =
			listOf(
				BrowserEvent.Sorted(ColumnId.Size),
				BrowserEvent.Viewed(Layout.Gallery),
				BrowserEvent.ToggledHiddenFiles,
			).fold(first) { state, event -> step(state, event).state }
				.let {
					it.copy(looks = it.looks.looking(ListingKind.Trash, Look(Order.By(Sort.BY_NAME))))
				}

		assertEquals(
			ColumnId.Size,
			made.look.order.sort
				?.by,
		)
		assertFalse(made.entryFilters.hidden.files)

		// The preferences now order folders newest first, and show a folder's gallery hidden folders as well as files.
		val newest = Sort(ColumnId.Modified, ascending = false)

		val changed =
			Listings()
				.looking(Role.Explorer, ListingKind.Folder, Look(Order.By(newest)))
				.filtering(
					Role.Explorer,
					ListingKind.Folder,
					Layout.Gallery,
					Filters(Hidden(files = true, folders = true)),
				)

		val relisted = step(made, Answer.Relisted(changed)).state

		// What they changed, the window takes up, its own given up; the trash's, which they did not change, it keeps.
		assertEquals(newest, relisted.look.order.sort)
		assertEquals(Hidden(files = true, folders = true), relisted.entryFilters.hidden)
		assertEquals(
			Look(Order.By(Sort.BY_NAME)),
			relisted.looks.look(Role.Explorer, ListingKind.Trash),
		)
		// The same defaults again change nothing.
		assertEquals(relisted.looks, step(relisted, Answer.Relisted(changed)).state.looks)
	}

	@Test
	fun eachKindOfListingLooksAsItsOwnDefaultSays_AndEachLayoutFiltersAsItsOwnDoes() {
		val listings =
			Listings()
				.looking(
					Role.Explorer,
					ListingKind.Folder,
					Look(Order.By(Sort(ColumnId.Size, false))),
				).looking(
					Role.Explorer,
					ListingKind.Results,
					Look(Order.By(Sort(ColumnId.Name, ascending = false))),
				).filtering(
					Role.Explorer,
					ListingKind.Folder,
					Layout.Gallery,
					Filters(Hidden(files = false)),
				)

		val first =
			opened(
				Choosing.Exploring(),
				home,
				home,
				Fixtures.sidebar,
				listings = listings,
				zoom = 1.5f,
			).state

		assertEquals(
			ColumnId.Size,
			first.look.order.sort
				?.by,
		)
		assertEquals(
			ListingKind.Results.offered,
			ListingKind.Results
				.columnsOf(
					emptyList(),
				).settings
				.map {
					it.id
				},
		)
		// A list shows hidden files, as the defaults have it; a gallery of the same folder, as its own filters say, does not.
		assertTrue(first.entryFilters.hidden.files)
		assertFalse(
			step(first, BrowserEvent.Viewed(Layout.Gallery))
				.state.entryFilters.hidden.files,
		)
		// The listing as large as the default says, the nearest the wheel's steps come to it.
		assertEquals(1.5f, first.zooms.list.factor)
	}

	@Test
	fun goingUpLandsOnTheFolderLeft() {
		val up = after(picker(folder = home.resolve("Documents")), BrowserEvent.Up)

		assertEquals(home, up.folder)
		assertEquals(Cursor(id("Documents"), id("Documents")), up.cursor)
		// Landed on, not chosen.
		assertTrue(up.choosing.rows.isEmpty())
	}

	@Test
	fun theRootHasNothingAbove() {
		val root = picker(folder = Path("/"))

		assertEquals(Step(root), step(root, BrowserEvent.Up))
	}

	@Test
	fun goingToTheFolderShownReadsItAgainAndKeepsWhatIsChosen() {
		val chosen = after(picker(), BrowserEvent.Clicked(id("notes.md"), Click.Only))
		val again = step(chosen, BrowserEvent.Opened(home))

		assertEquals(listOf(Effect.Read(home)), again.effects)
		assertEquals(chosen.history, again.state.history)
		assertEquals(listOf("notes.md"), names(after(chosen, BrowserEvent.Opened(home))))
	}

	// ---- choosing with the pointer ----

	@Test
	fun aClickChoosesOneRow() {
		val state =
			after(
				picker(),
				BrowserEvent.Clicked(id("notes.md"), Click.Only),
				BrowserEvent.Clicked(id("todo.txt"), Click.Only),
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
				BrowserEvent.Clicked(id("main.kt"), Click.Only),
				BrowserEvent.Clicked(id("todo.txt"), Click.Toggle),
			)

		assertEquals(listOf("main.kt", "todo.txt"), names(toggled))
		assertEquals("\"main.kt\" \"todo.txt\"", toggled.nameField)
		assertEquals(
			listOf("main.kt"),
			names(after(toggled, BrowserEvent.Clicked(id("todo.txt"), Click.Toggle))),
		)

		// A run, from the row last clicked without Shift.
		val run =
			after(
				files,
				BrowserEvent.Clicked(id("main.kt"), Click.Only),
				BrowserEvent.Clicked(id("screenshot 10.png"), Click.Range),
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
			names(after(run, BrowserEvent.Clicked(id("Holiday in Lisbon.jpg"), Click.Range))),
		)
	}

	@Test
	fun whereOneCanBeChosenCtrlAndShiftChooseOne() {
		val state =
			after(
				picker(),
				BrowserEvent.Clicked(id("main.kt"), Click.Only),
				BrowserEvent.Clicked(id("todo.txt"), Click.Toggle),
				BrowserEvent.Clicked(id("notes.md"), Click.Range),
			)

		assertEquals(listOf("notes.md"), names(state))
	}

	@Test
	fun aClickWhereThereIsNoRowChoosesNothing() {
		val state =
			after(
				picker(),
				BrowserEvent.Clicked(id("main.kt"), Click.Only),
				BrowserEvent.ClickedNowhere,
			)

		assertTrue(state.choosing.rows.isEmpty())
		assertEquals(Accept("Open", false), state.accept)
	}

	@Test
	fun aDoubleClickOpensAFolderAndAcceptsAFile() {
		assertEquals(
			home.resolve("Music"),
			after(picker(), BrowserEvent.Activated(id("Music"))).folder,
		)
		assertEquals(
			chosen(listOf(home.resolve("notes.md"))),
			effectsOf(picker(), BrowserEvent.Activated(id("notes.md"))),
		)
	}

	// ---- choosing with the keyboard ----

	@Test
	fun theArrowsMoveTheCursorAndChoose() {
		val first = after(picker(), BrowserEvent.Moved(Move.By(1), Carry.Choose))

		// The first move with nothing under the cursor lands on the first row.
		assertEquals(listOf("Desktop"), names(first))

		val third = after(first, BrowserEvent.Moved(Move.By(2), Carry.Choose))

		assertEquals(listOf("Downloads"), names(third))
		assertEquals(Cursor(id("Downloads"), id("Downloads")), third.cursor)
		// Up from the top stays at the top; End goes to the last row.
		assertEquals(
			listOf("Desktop"),
			names(after(third, BrowserEvent.Moved(Move.By(-10), Carry.Choose))),
		)
		assertEquals(
			listOf("trailer.mp4"),
			names(after(third, BrowserEvent.Moved(Move.Last, Carry.Choose))),
		)
	}

	@Test
	fun shiftChoosesARunAndCtrlMovesWithoutChoosing() {
		val files =
			after(picker(Choosing.Files()), BrowserEvent.Clicked(id("main.kt"), Click.Only))
		val run = after(files, BrowserEvent.Moved(Move.By(2), Carry.Extend))

		assertEquals(listOf("main.kt", "notes.md", "Quarterly report.pdf"), names(run))

		val wandered = after(files, BrowserEvent.Moved(Move.By(3), Carry.Stay))

		assertEquals(listOf("main.kt"), names(wandered))
		assertEquals(id("screenshot 2.png"), wandered.cursor?.at)

		// Space then chooses the row the cursor wandered to as well.
		assertEquals(
			listOf("main.kt", "screenshot 2.png"),
			names(after(wandered, BrowserEvent.Toggled)),
		)
	}

	@Test
	fun ctrlAChoosesEveryRowShown() {
		val all = after(picker(Choosing.Files()), BrowserEvent.ChoseAll)

		val one = picker()

		assertEquals(all.rows.size, all.choosing.rows.size)
		assertEquals(Step(one), step(one, BrowserEvent.ChoseAll))
	}

	/** [text] typed into the listing of [state], a letter every tenth of a second from [from]. */
	private fun typed(state: BrowserState, text: String, from: Long = 0): BrowserState =
		after(
			state,
			*text
				.mapIndexed {
					at,
					letter,
					->
					BrowserEvent.Jumped(letter, from + at * 100L)
				}.toTypedArray(),
		)

	@Test
	fun lettersTypedGoToTheRowTheyStart() {
		val s = typed(picker(), "s")

		assertEquals(listOf("screenshot 2.png"), names(s))
		assertEquals(Jump("s", 0, found = true), s.jump)
		// The same letter again goes on to the next row it starts.
		assertEquals(listOf("screenshot 10.png"), names(typed(s, "s", from = 100)))

		// More letters narrow it from where the cursor is; and case is not told apart.
		val so = typed(s, "O", from = 100)

		assertEquals(listOf("song.flac"), names(so))
		assertEquals(Jump("sO", 100, found = true), so.jump)
		assertEquals(listOf("Holiday in Lisbon.jpg"), names(typed(picker(), "hol")))

		// A space is a letter of a name begun, and begins none.
		assertEquals(listOf("screenshot 10.png"), names(typed(picker(), "screenshot 1")))
		assertNull(typed(picker(), " ").jump)
		assertEquals(emptyList<String>(), names(typed(picker(), " ")))
	}

	@Test
	fun lettersNoNameStartsWithGoToTheRowThatHasThemAndWhereNoneHasNothingMoves() {
		// "Pictures" starts with a p; nothing starts with "po", and "Quarterly report.pdf" has it.
		assertEquals(listOf("Pictures"), names(typed(picker(), "p")))

		val port = typed(picker(), "port")

		assertEquals(listOf("Quarterly report.pdf"), names(port))
		assertEquals(Jump("port", 300, found = true), port.jump)

		// No name has it: the cursor stays where the letters before it put it, and the jump says nothing was found.
		val none = typed(picker(), "szq")

		assertEquals(listOf("screenshot 2.png"), names(none))
		assertEquals(Jump("szq", 200, found = false), none.jump)
	}

	@Test
	fun aPauseEndsTheLettersTypedAndSoDoesGoingElsewhere() {
		val so = typed(picker(), "so")

		// Typed within the pause, a letter is one more of them; after it, the first of new ones.
		assertEquals("son", typed(so, "n", from = 100 + Jump.PAUSE - 1).jump?.typed)

		val anew = typed(so, "n", from = 100 + Jump.PAUSE)

		assertEquals(Jump("n", 100 + Jump.PAUSE, found = true), anew.jump)
		assertEquals(listOf("notes.md"), names(anew))

		// The pause run out ends them, unless something was typed since; let go, they are ended whenever.
		assertNull(after(so, BrowserEvent.JumpEnded(100)).jump)
		assertEquals(so.jump, after(so, BrowserEvent.JumpEnded(0)).jump)
		assertNull(after(so, BrowserEvent.JumpEnded()).jump)
		assertEquals(listOf("song.flac"), names(after(so, BrowserEvent.JumpEnded())))

		// The last letter taken back: what is left names a row from where the cursor is, and none left is none.
		val back = after(so, BrowserEvent.JumpErased(200))

		assertEquals(Jump("s", 200, found = true), back.jump)
		assertEquals(listOf("song.flac"), names(back))
		assertNull(after(back, BrowserEvent.JumpErased(300)).jump)
		assertNull(after(picker(), BrowserEvent.JumpErased(0)).jump)

		// Gone into another folder, what was typed named a row of the one left.
		assertNull(after(so, BrowserEvent.Activated(id("Documents"))).jump)
	}

	// ---- arranging ----

	@Test
	fun aHeaderClickedSortsByItAndAgainTheOtherWay() {
		val bySize = after(picker(), BrowserEvent.Sorted(ColumnId.Size))

		assertEquals(Sort(ColumnId.Size, ascending = false), bySize.look.order.sort)
		assertEquals(
			"backup.tar.gz",
			bySize.rows.list
				.first { !it.isFolder }
				.name,
		)

		val smallest = after(bySize, BrowserEvent.Sorted(ColumnId.Size))

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
				BrowserEvent.Clicked(id("main.kt"), Click.Only),
				BrowserEvent.Clicked(id("todo.txt"), Click.Toggle),
			)

		val searched = after(chosen, BrowserEvent.Searched("todo"))

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

		assertEquals(Hidden(files = true, folders = false), opened.entryFilters.hidden)
		assertEquals(
			listOf(".bashrc"),
			opened.rows.list
				.filter { it.hidden }
				.map { it.name },
		)

		val folders = after(opened, BrowserEvent.ToggledHiddenFolders)

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
			after(folders, BrowserEvent.ToggledHiddenFiles)
				.rows.list
				.filter {
					it.hidden
				}.map { it.name },
		)
	}

	@Test
	fun ctrlHShowsEveryHiddenEntryOrNone() {
		val all = after(picker(), BrowserEvent.ToggledEveryHidden)

		assertEquals(Hidden(files = true, folders = true), all.entryFilters.hidden)
		assertEquals(
			Fixtures.NO_HIDDEN,
			after(all, BrowserEvent.ToggledEveryHidden).entryFilters.hidden,
		)
		// From the usual (files shown, folders not), it shows every one.
		assertEquals(
			Hidden(files = true, folders = true),
			after(picker(hidden = Hidden()), BrowserEvent.ToggledEveryHidden).entryFilters.hidden,
		)
	}

	@Test
	fun aKindShownAloneIsTheOnlyOneWhicheverWereShown() {
		fun shown(state: BrowserState) = state.rows.list.map { it.name }

		val two =
			after(
				picker(),
				BrowserEvent.ToggledKind(Kind.Images),
				BrowserEvent.ToggledKind(Kind.Audio),
			)

		assertEquals(setOf(Kind.Images, Kind.Audio), two.entryFilters.kinds)

		// One that was not among them: it alone, the two let go.
		val videos = after(two, BrowserEvent.ShowedOnlyKind(Kind.Videos))

		assertEquals(setOf(Kind.Videos), videos.entryFilters.kinds)
		assertEquals(listOf("trailer.mp4"), shown(videos))
		// One that was: it stays, and the other goes.
		assertEquals(
			setOf(Kind.Images),
			after(two, BrowserEvent.ShowedOnlyKind(Kind.Images)).entryFilters.kinds,
		)
		// The only one there is, shown alone, is as it was; and with none shown before, it is the one.
		assertEquals(videos, after(videos, BrowserEvent.ShowedOnlyKind(Kind.Videos)))
		assertEquals(
			setOf(Kind.Images),
			after(picker(), BrowserEvent.ShowedOnlyKind(Kind.Images)).entryFilters.kinds,
		)
	}

	@Test
	fun theFiltersClearedShowEveryKindAndLeaveWhatIsHiddenAsItWas() {
		val hidden = Hidden(files = false, folders = true)

		val state =
			after(
				picker(hidden = hidden),
				BrowserEvent.ToggledKind(Kind.Images),
				BrowserEvent.ToggledKind(Kind.Videos),
				BrowserEvent.ClearedFilters,
			)

		assertEquals(emptySet<Kind>(), state.entryFilters.kinds)
		assertEquals(hidden, state.entryFilters.hidden)
		assertEquals(picker(hidden = hidden).rows.list, state.rows.list)
		// With none to clear, nothing changes.
		assertEquals(state, after(state, BrowserEvent.ClearedFilters))
	}

	@Test
	fun aKindChosenShowsOnlyThatKindAndSeveralShowThemTogether() {
		fun shown(state: BrowserState) = state.rows.list.map { it.name }

		val images = after(picker(), BrowserEvent.ToggledKind(Kind.Images))

		assertEquals(
			listOf("Holiday in Lisbon.jpg", "screenshot 2.png", "screenshot 10.png"),
			shown(images),
		)

		val andVideos = after(images, BrowserEvent.ToggledKind(Kind.Videos))

		assertEquals(
			listOf(
				"Holiday in Lisbon.jpg",
				"screenshot 2.png",
				"screenshot 10.png",
				"trailer.mp4",
			),
			shown(andVideos),
		)
		// Turned off again, a kind leaves the rest; the filters cleared, everything is shown.
		assertEquals(
			listOf("trailer.mp4"),
			shown(after(andVideos, BrowserEvent.ToggledKind(Kind.Images))),
		)
		assertEquals(shown(picker()), shown(after(andVideos, BrowserEvent.ClearedFilters)))
	}

	@Test
	fun foldersAreAKindLikeAnyOther() {
		val state =
			after(
				picker(),
				BrowserEvent.ToggledKind(Kind.Folders),
				BrowserEvent.ToggledKind(Kind.Audio),
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
				BrowserEvent.ShowedOnlyKind(Kind.Folders),
			)

		assertTrue(folders.rows.list.any { it.name == ".config" })

		val hiding = after(folders, BrowserEvent.ToggledHiddenFolders)

		assertFalse(hiding.rows.list.any { it.name == ".config" })
		assertEquals(setOf(Kind.Folders), hiding.entryFilters.kinds)
	}

	@Test
	fun theKindsChosenStayFromFolderToFolder() {
		val pictures =
			after(
				picker(),
				BrowserEvent.ToggledKind(Kind.Images),
				BrowserEvent.Opened(home.resolve("Pictures")),
			)

		assertEquals(setOf(Kind.Images), pictures.entryFilters.kinds)
		assertEquals(listOf("cat.png"), pictures.rows.list.map { it.name })
	}

	@Test
	fun theStatusCountsEveryEntryThatCouldBeShown() {
		val images = after(picker(), BrowserEvent.ShowedOnlyKind(Kind.Images))

		assertEquals(3, images.rows.size)
		// Every entry the folder holds, hidden ones too: "3 of 20 items".
		assertEquals(20, images.entryCount)
	}

	@Test
	fun aRowAFilterLeavesOutIsNoLongerChosen() {
		val state =
			after(
				picker(),
				BrowserEvent.Clicked(id("main.kt"), Click.Only),
				BrowserEvent.ToggledKind(Kind.Images),
			)

		assertTrue(state.choosing.rows.isEmpty())
		assertNull(state.cursor)
	}

	@Test
	fun theLayoutIsKeptFromFolderToFolder() {
		val gallery =
			after(
				picker(),
				BrowserEvent.Viewed(Layout.Gallery),
				BrowserEvent.Opened(home.resolve("Pictures")),
			)

		assertEquals(Layout.Gallery, gallery.layout)
		assertEquals(Layout.List, after(gallery, BrowserEvent.Viewed(Layout.List)).layout)
	}

	@Test
	fun aFilterShowsItsFilesAndEveryFolder() {
		val images = after(picker(), BrowserEvent.Filtered(Fixtures.filters[1]))

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
			step(all, BrowserEvent.Filtered(TypeFilter("Other", listOf("*.x")))),
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
				BrowserEvent.Clicked(id("main.kt"), Click.Only),
				BrowserEvent.Clicked(id("todo.txt"), Click.Toggle),
			)

		val fewer = Listing.Shown(Fixtures.homeRows.filterNot { it.name == "todo.txt" })
		val again = step(chosen, Answer.Arrived(home, fewer)).state

		assertEquals(listOf("main.kt"), names(again))
		assertNull(again.cursor)
	}

	// ---- accepting ----

	@Test
	fun acceptingAFileOpensIt() {
		val state = after(picker(), BrowserEvent.Clicked(id("notes.md"), Click.Only))

		assertEquals(
			chosen(listOf(home.resolve("notes.md"))),
			effectsOf(state, BrowserEvent.Accepted),
		)
	}

	@Test
	fun acceptingAFolderGoesIntoIt() {
		val state = after(picker(), BrowserEvent.Clicked(id("Documents"), Click.Only))

		// A folder's name is not one to open, so the name field stays empty; the button still opens the folder.
		assertEquals("", state.nameField)
		assertEquals(Accept("Open", true), state.accept)
		assertEquals(home.resolve("Documents"), after(state, BrowserEvent.Accepted).folder)
	}

	@Test
	fun acceptingSeveralOpensTheFilesAmongThem() {
		val state =
			after(
				picker(Choosing.Files()),
				BrowserEvent.Clicked(id("Music"), Click.Only),
				BrowserEvent.Clicked(id("todo.txt"), Click.Toggle),
				BrowserEvent.Clicked(id("main.kt"), Click.Toggle),
			)

		assertEquals(
			chosen(listOf(home.resolve("main.kt"), home.resolve("todo.txt"))),
			effectsOf(state, BrowserEvent.Accepted),
		)
	}

	@Test
	fun aFolderPickerGivesTheFolderChosenOrTheOneShown() {
		val folders = picker(Choosing.Folder())

		assertEquals(chosen(listOf(home)), effectsOf(folders, BrowserEvent.Accepted))
		assertEquals(
			chosen(listOf(home.resolve("Music"))),
			effectsOf(
				after(folders, BrowserEvent.Clicked(id("Music"), Click.Only)),
				BrowserEvent.Accepted,
			),
		)
		// A double click goes into it instead.
		assertEquals(
			home.resolve("Music"),
			after(folders, BrowserEvent.Activated(id("Music"))).folder,
		)
	}

	@Test
	fun aNameTypedIsLookedUpBeforeItIsOpened() {
		val typed = after(picker(), BrowserEvent.Named("notes.md"))
		val looking = step(typed, BrowserEvent.Accepted)
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
			after(picker(), BrowserEvent.Named("Documents/Taxes"), BrowserEvent.Accepted).folder,
		)
		assertEquals(
			Path("/etc"),
			after(picker(), BrowserEvent.Named("/etc"), BrowserEvent.Accepted).folder,
		)
		assertEquals(
			home.resolve("Pictures"),
			after(
				picker(folder = Path("/etc")),
				BrowserEvent.Named("~/Pictures"),
				BrowserEvent.Accepted,
			).folder,
		)

		val typed = after(picker(), BrowserEvent.Named("~/Documents/cv.pdf"))

		assertEquals(
			chosen(listOf(home.resolve("Documents/cv.pdf"))),
			step(typed, BrowserEvent.Accepted).settledEffects(),
		)
	}

	@Test
	fun aNameThatIsNotThereIsSaidToBeNotFound() {
		val missing = after(picker(), BrowserEvent.Named("nothing.txt"), BrowserEvent.Accepted)

		assertEquals(Notice.NotFound("nothing.txt"), missing.notice)

		// While the notice is shown, nothing else is done; put away, the picker is as it was.
		assertEquals(Step(missing), step(missing, BrowserEvent.Up))
		assertNull(after(missing, BrowserEvent.Dismissed).notice)
	}

	@Test
	fun anAnswerNoLongerWaitedForIsTooLate() {
		val looking =
			step(
				after(picker(), BrowserEvent.Named("notes.md")),
				BrowserEvent.Accepted,
			).state

		val lookup = looking.waiting!!
		val moved = step(looking, BrowserEvent.Opened(home.resolve("Music"))).state

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
			step(save, BrowserEvent.Accepted).settledEffects(),
		)
		assertEquals(Accept("Save", false), after(save, BrowserEvent.Named("  ")).accept)
	}

	@Test
	fun savingARowChosenIsOnlyChosenAndTheNameIsWhatIsSavedUnder() {
		val save =
			after(
				picker(Choosing.SaveAs("draft.txt")),
				BrowserEvent.Clicked(id("notes.md"), Click.Only),
			)

		// A click chooses the file and nothing else: the name is what it was, and Save saves under it.
		assertEquals(listOf("notes.md"), names(save))
		assertEquals("draft.txt", save.nameField)
		assertEquals(Accept("Save", true), save.accept)
		assertEquals(
			chosen(listOf(home.resolve("draft.txt"))),
			step(save, BrowserEvent.Accepted).settledEffects(),
		)

		// Moved to with the keyboard, the same.
		val moved = after(save, BrowserEvent.Moved(Move.By(1), Carry.Choose))

		assertEquals(1, names(moved).size)
		assertTrue(names(moved) != names(save))
		assertEquals("draft.txt", moved.nameField)

		// What was typed stays what was typed, whatever is chosen after it.
		val typed =
			after(
				save,
				BrowserEvent.Named("other.txt"),
				BrowserEvent.Clicked(id("todo.txt"), Click.Only),
			)

		assertEquals("other.txt", typed.nameField)
		assertEquals(listOf("todo.txt"), names(typed))
		assertEquals(
			chosen(listOf(home.resolve("other.txt"))),
			step(typed, BrowserEvent.Accepted).settledEffects(),
		)

		// With no name there is nothing to save under, a file chosen or not: the button is off, and does nothing.
		val unnamed = after(save, BrowserEvent.Named(""))

		assertEquals(Accept("Save", false), unnamed.accept)
		assertEquals(Step(unnamed), step(unnamed, BrowserEvent.Accepted))
	}

	@Test
	fun savingOverAFileAsksFirst() {
		val save = picker(Choosing.SaveAs("draft.txt"))

		// A file opened (a double click, Enter on it) is the file to save over, once the user says so.
		val asked = after(save, BrowserEvent.Activated(id("notes.md")))

		assertEquals(Notice.Replace(home.resolve("notes.md")), asked.notice)
		assertEquals(listOf("notes.md"), names(asked))
		assertEquals("draft.txt", asked.nameField)
		assertEquals(
			chosen(listOf(home.resolve("notes.md"))),
			effectsOf(asked, BrowserEvent.Confirmed),
		)

		// Answered no, nothing is saved, and the name is still what it was.
		val kept = after(asked, BrowserEvent.Dismissed)

		assertNull(kept.notice)
		assertEquals("draft.txt", kept.nameField)

		// Its menu's "Replace" asks the same; and so does the name of a file there is, typed and accepted.
		assertEquals(
			Notice.Replace(home.resolve("notes.md")),
			after(save, BrowserEvent.Picked(id("notes.md"))).notice,
		)
		assertEquals(
			Notice.Replace(home.resolve("notes.md")),
			after(save, BrowserEvent.Named("notes.md"), BrowserEvent.Accepted).notice,
		)
	}

	@Test
	fun savingAFolderChosenIsOnlyChosenAndOpenedIsGoneInto() {
		val save =
			after(
				picker(Choosing.SaveAs("draft.txt")),
				BrowserEvent.Clicked(id("Documents"), Click.Only),
			)

		// Chosen, it changes neither the name nor what the button does: Save saves here, under the name.
		assertEquals(Accept("Save", true), save.accept)
		assertEquals("draft.txt", save.nameField)
		assertEquals(
			chosen(listOf(home.resolve("draft.txt"))),
			step(save, BrowserEvent.Accepted).settledEffects(),
		)

		// Opened, it is gone into, with the name still to save under.
		val there = after(save, BrowserEvent.Activated(id("Documents")))

		assertEquals(home.resolve("Documents"), there.folder)
		assertEquals("draft.txt", there.nameField)
		assertEquals(Accept("Save", true), there.accept)
	}

	@Test
	fun savingInAFolderThatIsNotThereIsNotFound() {
		val save =
			after(
				picker(Choosing.SaveAs("draft.txt")),
				BrowserEvent.Named("nowhere/draft.txt"),
				BrowserEvent.Accepted,
			)

		assertEquals(Notice.NotFound(home.resolve("nowhere").toString()), save.notice)
	}

	// ---- the path across the top ----

	@Test
	fun aPathTypedAcrossTheTopIsGoneTo() {
		val editing = after(picker(), BrowserEvent.EditedAddress)

		assertEquals(Address.Editing(home.toString()), editing.address)

		val there = after(editing, BrowserEvent.AddressTyped("/etc"), BrowserEvent.Went)

		assertEquals(Path("/etc"), there.folder)
		assertEquals(Address.Crumbs, there.address)
	}

	@Test
	fun aFileTypedAcrossTheTopIsChosenInItsFolder() {
		val there =
			after(
				picker(),
				BrowserEvent.EditedAddress,
				BrowserEvent.AddressTyped("~/Documents/cv.pdf"),
				BrowserEvent.Went,
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
				BrowserEvent.EditedAddress,
				BrowserEvent.AddressTyped("/nowhere"),
				BrowserEvent.Went,
			)

		assertEquals(Notice.NotFound("/nowhere"), there.notice)
		assertEquals(Address.Editing("/nowhere"), there.address)
		assertEquals(home, there.folder)
	}

	// ---- changing what is on disk ----

	@Test
	fun aRenameIsAskedOfTheDiskAndLandsOnTheNewName() {
		val renaming = after(picker(), BrowserEvent.StartedRenaming(id("notes.md")))

		assertEquals(Renaming(id("notes.md"), "notes.md"), renaming.renaming)
		assertEquals(listOf("notes.md"), names(renaming))

		val done =
			step(after(renaming, BrowserEvent.RenameTyped("journal.md")), BrowserEvent.Renamed)
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
					BrowserEvent.StartedRenaming(id("notes.md")),
					BrowserEvent.RenameTyped("a/b"),
				),
				BrowserEvent.Renamed,
			)

		assertTrue(refused.effects.isEmpty())
		assertInstanceOf(Notice.Failed::class.java, refused.state.notice)
		// Still being typed, to be put right once the notice is put away.
		assertEquals("a/b", refused.state.renaming?.text)
		// The same name renames nothing, and Escape leaves it.
		assertTrue(
			step(
				after(picker(), BrowserEvent.StartedRenaming(id("notes.md"))),
				BrowserEvent.Renamed,
			).effects.isEmpty(),
		)
		assertNull(
			after(
				picker(),
				BrowserEvent.StartedRenaming(id("notes.md")),
				BrowserEvent.RenameCancelled,
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
	fun theDeleteKeyTrashesWithoutAsking() {
		assertEquals(
			listOf(
				Effect.Make(Change.Trash(listOf(home.resolve("main.kt"), home.resolve("todo.txt")))),
			),
			effectsOf(picker(), BrowserEvent.Trashed(listOf(id("todo.txt"), id("main.kt")))),
		)
		// A row the listing does not show is not trashed.
		assertTrue(effectsOf(picker(), BrowserEvent.Trashed(listOf(id("not there")))).isEmpty())
	}

	@Test
	fun whatHasNoTrashIsDeletedForGoodOnlyOnceTheUserSaysSo() {
		val scratch = Path.of("/tmp/scratch.txt")
		val notes = home.resolve("notes.md")

		val trashed =
			step(
				picker(),
				Answer.Changed(
					Change.Trash(listOf(scratch, notes)),
					ChangeResult.Trashed(
						Change.PutBack(listOf(Trashed(notes, "notes.md", Fixtures.Memory.TRASH))),
						listOf(Untrashable(scratch, "/tmp is kept in memory (tmpfs), which has no trash")),
					),
				),
			).state

		// What went to the trash is undone as ever; what had none is asked about, and nothing more is done yet.
		assertEquals(1, trashed.changes.undo.size)
		assertEquals(
			Notice.ConfirmErase(
				Erasing.Files(listOf(scratch)),
				"/tmp is kept in memory (tmpfs), which has no trash",
			),
			trashed.notice,
		)
		assertEquals(
			listOf(Effect.Erase(Erasing.Files(listOf(scratch)))),
			effectsOf(trashed, BrowserEvent.Confirmed),
		)
		assertTrue(effectsOf(trashed, BrowserEvent.Dismissed).isEmpty())
		// Deleting for good was not what was asked for: the keyboard starts on the way out of it.
		assertTrue((trashed.notice as Notice.ConfirmErase).wary)
	}

	@Test
	fun shiftDeleteDeletesForGoodOnceAsked() {
		val asked = after(picker(), BrowserEvent.AskedToErase(listOf(id("main.kt"))))

		assertEquals(
			Notice.ConfirmErase(Erasing.Files(listOf(home.resolve("main.kt")))),
			asked.notice,
		)
		// It was asked for, so the keyboard starts on Yes; the whole trash at once starts on the way out.
		assertFalse((asked.notice as Notice.ConfirmErase).wary)
		assertTrue(Notice.ConfirmErase(Erasing.Everything).wary)
		assertEquals(
			listOf(Effect.Erase(Erasing.Files(listOf(home.resolve("main.kt"))))),
			effectsOf(asked, BrowserEvent.Confirmed),
		)
		assertTrue(effectsOf(asked, BrowserEvent.Dismissed).isEmpty())

		// Done: read again, and nothing to undo, since it cannot be.
		val erased =
			step(
				asked.copy(notice = null),
				Answer.Erased(Erasing.Files(listOf(home.resolve("main.kt"))), null),
			)

		assertEquals(listOf(Effect.Read(home)), erased.effects)
		assertTrue(
			erased.state.changes.undo
				.isEmpty(),
		)
	}

	@Test
	fun theTrashShowsEveryTrashsEntries_PutBackOrDeletedForGood() {
		val trash = step(picker(Choosing.Exploring()), BrowserEvent.OpenedTrash)

		assertEquals(listOf(Effect.ReadTrash), trash.effects)

		val shown = trash.settled(Fixtures.Memory)
		val draft = shown.rows.list.single { it.name == "draft" }
		val trashed = (draft.item as Item.InTrash).trashed

		assertEquals(Location.Trash, shown.at)
		assertEquals(listOf("draft", "old notes.md"), shown.rows.list.map { it.name })
		assertEquals(
			listOf(Effect.Make(Change.PutBack(listOf(trashed)))),
			effectsOf(shown, BrowserEvent.PutBack(listOf(draft.id))),
		)
		// Delete, in the trash, deletes for good, once asked, as Shift+Delete does.
		assertEquals(
			Notice.ConfirmErase(Erasing.FromTrash(listOf(trashed))),
			after(shown, BrowserEvent.Trashed(listOf(draft.id))).notice,
		)
		assertEquals(
			Notice.ConfirmErase(Erasing.Everything),
			after(shown, BrowserEvent.AskedToEmpty).notice,
		)
		// Back from it to where the window was.
		assertEquals(home, after(shown, BrowserEvent.Back).folder)
	}

	@Test
	fun aChangeDoneCanBeUndoneAndRedone() {
		val rename = Change.Rename(home.resolve("notes.md"), home.resolve("journal.md"))
		val back = Change.Rename(home.resolve("journal.md"), home.resolve("notes.md"))
		val done = step(picker(), Answer.Changed(rename, ChangeResult.Done(back))).state

		// Done, what undoes it is kept, with what it was.
		assertEquals(Changes(undo = listOf(Undoable(rename, back))), done.changes)

		val undoing = step(done, BrowserEvent.Undone)

		assertEquals(listOf(Effect.Make(back, Doing.Undo(rename))), undoing.effects)
		assertEquals(Changes(), undoing.state.changes)

		// Undone, what does it again is kept to redo.
		val undone =
			step(
				undoing.state,
				Answer.Changed(back, ChangeResult.Done(rename), Doing.Undo(rename)),
			).state

		assertEquals(Changes(redo = listOf(Undoable(rename, rename))), undone.changes)

		val redoing = step(undone, BrowserEvent.Redone)

		assertEquals(listOf(Effect.Make(rename, Doing.Redo(rename))), redoing.effects)
		assertEquals(
			Changes(undo = listOf(Undoable(rename, back))),
			step(
				redoing.state,
				Answer.Changed(rename, ChangeResult.Done(back), Doing.Redo(rename)),
			).state.changes,
		)
		// With nothing to undo or to redo, nothing is done.
		assertTrue(effectsOf(picker(), BrowserEvent.Undone).isEmpty())
		assertTrue(effectsOf(picker(), BrowserEvent.Redone).isEmpty())
	}

	@Test
	fun aNewChangeLeavesNothingToRedo() {
		val redoable =
			picker().copy(
				changes =
					Changes(
						redo =
							listOf(
								Undoable(
									Change.MakeFolder(home.resolve("x")),
									Change.MakeFolder(home.resolve("x")),
								),
							),
					),
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

		assertEquals(
			Changes(
				undo = listOf(Undoable(Change.MakeFolder(folder), Change.RemoveFolder(folder))),
			),
			made.changes,
		)
	}

	@Test
	fun whatIsPutBackIsChosenAndAnUndoThatFailsIsSaidSo() {
		val back =
			Change.PutBack(
				listOf(Trashed(home.resolve("main.kt"), "main.kt", Fixtures.Memory.TRASH)),
			)

		val put =
			step(
				picker(),
				Answer.Changed(
					back,
					ChangeResult.Done(Change.Trash(listOf(home.resolve("main.kt")))),
					Doing.Undo(Change.Trash(listOf(home.resolve("main.kt")))),
				),
			)

		assertEquals(Landing.On(id("main.kt"), choose = true), put.state.landing)

		val failed =
			step(
				picker(),
				Answer.Changed(
					back,
					ChangeResult.Failed("“main.kt” is no longer in the trash."),
					Doing.Undo(Change.Trash(listOf(home.resolve("main.kt")))),
				),
			)

		assertEquals(
			Notice.Failed(
				"Couldn't undo move to trash",
				"“main.kt” is no longer in the trash.",
			),
			failed.state.notice,
		)
	}

	@Test
	fun aNewFolderHasAFreeNameAndItsNameTypedOver() {
		val made = step(picker(), BrowserEvent.MadeFolder)
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
			effectsOf(after(arrived, BrowserEvent.RenameCancelled), BrowserEvent.MadeFolder),
		)
	}

	@Test
	fun pathsAreCopiedOneToALine() {
		assertEquals(
			listOf(Effect.Copy("/home/avery/a\n/home/avery/b")),
			effectsOf(
				picker(),
				BrowserEvent.CopiedPaths(listOf(home.resolve("a"), home.resolve("b"))),
			),
		)
	}

	@Test
	fun pickedIsChosenAndAcceptedAtOnce() {
		assertEquals(
			chosen(listOf(home.resolve("Music"))),
			effectsOf(picker(Choosing.Folder()), BrowserEvent.Picked(id("Music"))),
		)
		assertEquals(
			chosen(listOf(home.resolve("notes.md"))),
			effectsOf(picker(), BrowserEvent.Picked(id("notes.md"))),
		)
	}

	// ---- how it is drawn ----

	@Test
	fun theListZoomsInStepsUpToTwiceItsSize() {
		val larger = after(picker(), BrowserEvent.Zoomed(1))

		assertEquals(Zoom.By(3), larger.zoom)
		assertEquals(1.25f, larger.scale)
		// As far as there are sizes for a list.
		assertEquals(Zoom.LIST_LARGEST, after(larger, BrowserEvent.Zoomed(10)).zoom)
		assertEquals(Zoom.By(0), after(larger, BrowserEvent.Zoomed(-10)).zoom)

		// The gallery has a size of its own, and the list keeps its own meanwhile.
		val gallery = after(larger, BrowserEvent.Viewed(Layout.Gallery))

		assertEquals(Zoom.NORMAL, gallery.zoom)
		assertEquals(Zoom.By(3), after(gallery, BrowserEvent.Viewed(Layout.List)).zoom)
		assertEquals(Zoom.NORMAL, after(larger, BrowserEvent.Unzoomed).zoom)
	}

	@Test
	fun theGalleryZoomsUpToOneTileAtATimeAsTheWindowAllows() {
		// Room for a tile 4.2 times its usual size.
		val gallery =
			after(picker(), BrowserEvent.Viewed(Layout.Gallery), BrowserEvent.Measured(4.2f))
		val largest = after(gallery, BrowserEvent.Zoomed(100))

		assertEquals(Zoom.Whole, largest.zoom)
		assertEquals(4.2f, largest.scale)
		// A step down from the whole is the largest size smaller than it, four times; a step up from four is the whole,
		// since five would not fit.
		assertEquals(Zoom.By(8), after(largest, BrowserEvent.Zoomed(-1)).zoom)
		assertEquals(
			Zoom.Whole,
			after(gallery, BrowserEvent.Zoomed(6), BrowserEvent.Zoomed(1)).zoom,
		)
	}

	@Test
	fun aWindowMadeSmallerMakesTheTilesSmallerWithIt() {
		val four =
			after(
				picker(),
				BrowserEvent.Viewed(Layout.Gallery),
				BrowserEvent.Measured(6f),
				BrowserEvent.Zoomed(6),
			)

		assertEquals(Zoom.By(8), four.zoom)
		assertEquals(4f, four.scale)

		// Room only for 2.2: drawn as large as fits, the size chosen kept for when there is room again.
		val smaller = after(four, BrowserEvent.Measured(2.2f))

		assertEquals(2.2f, smaller.scale)
		assertEquals(4f, after(smaller, BrowserEvent.Measured(6f)).scale)
		// A step smaller is smaller than what is shown, not than what was chosen: two times.
		assertEquals(Zoom.By(5), after(smaller, BrowserEvent.Zoomed(-1)).zoom)
		// One at a time stays one at a time, whatever the room.
		assertEquals(
			2.2f,
			after(four, BrowserEvent.Zoomed(100), BrowserEvent.Measured(2.2f)).scale,
		)
	}

	@Test
	fun aMenuSortsAsItSays() {
		val sort = Sort(ColumnId.Size, ascending = true)

		assertEquals(
			sort,
			after(picker(), BrowserEvent.SortedBy(sort)).look.order.sort,
		)
	}

	// ---- cancelling ----

	@Test
	fun cancellingEndsThePickerWithNothing() {
		assertEquals(
			listOf(Effect.Finish(Outcome.Cancelled)),
			effectsOf(picker(), BrowserEvent.Cancelled),
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
	fun theMiddleAndSideButtonsAreChordsAndTheTwoEveryClickIsAreNot() {
		assertEquals(MouseButton.Back, mouseButtonOf(PointerButton.Back))
		assertEquals(MouseButton.Forward, mouseButtonOf(PointerButton.Forward))
		assertEquals(MouseButton.Middle, mouseButtonOf(PointerButton.Tertiary))
		assertNull(mouseButtonOf(PointerButton.Primary))
		assertNull(mouseButtonOf(PointerButton.Secondary))
		assertNull(mouseButtonOf(null))
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
	fun aStateMarksOnlyRowsItShowsWhateverItIsAskedToMark() {
		val state = picker()
		val shown = id("notes.md")
		val stray = id("not there.txt")

		// Asked to choose, point at, or rename a row it does not show, it does none of them: and making it does not fail.
		val asked =
			state.copy(
				chose = Choosing.OneFile(stray),
				pointed = Cursor(stray, stray),
				naming = Renaming(stray, "new name"),
			)

		assertEquals(Choosing.OneFile(), asked.choosing)
		assertNull(asked.cursor)
		assertNull(asked.renaming)
		assertEquals(emptyList<Row>(), asked.chosenRows)

		// Of several, those it shows; a cursor whose anchor is not shown reaches from its own row.
		val some =
			state.copy(
				chose = Choosing.Files(linkedSetOf(shown, stray)),
				pointed = Cursor(shown, stray),
				naming = Renaming(shown, "new name"),
			)

		assertEquals(Choosing.Files(setOf(shown)), some.choosing)
		assertEquals(Cursor(shown, shown), some.cursor)
		assertEquals(Renaming(shown, "new name"), some.renaming)

		// What kind of thing is chosen, and what was typed, are as asked: only rows are let go.
		assertEquals(
			Choosing.Folder(typed = "elsewhere"),
			state.copy(chose = Choosing.Folder(stray, "elsewhere")).choosing,
		)

		// What a filter takes out of the listing is not marked while it is out, however the state came by it.
		val chosen = after(state, BrowserEvent.Clicked(shown, Click.Only))

		assertEquals(Choosing.OneFile(shown), chosen.choosing)
		assertEquals(Choosing.OneFile(), chosen.copy(query = "no such name").choosing)
		assertNull(chosen.copy(query = "no such name").cursor)

		// A copy that changes nothing marked and nothing shown is given the same marks, not read again.
		val copied = chosen.copy(notice = Notice.NotFound("x"))

		assertTrue(copied.choosing === chosen.choosing)
		assertTrue(copied.cursor === chosen.cursor)
	}

	@Test
	fun theFilterInUseIsOneOfThoseOfferedAndThereIsAlwaysOne() {
		val images = Fixtures.filters[1]
		val other = TypeFilter("Spreadsheets", listOf("*.ods"))
		val offered = Offered.of(Fixtures.filters)

		assertEquals(Fixtures.filters.first(), offered.using)
		assertEquals(images, offered.using(images).using)
		assertEquals(images, Offered.of(Fixtures.filters, images).using)
		// One that is not offered is not taken up, to begin with or after.
		assertEquals(offered, offered.using(other))
		assertEquals(offered, Offered.of(Fixtures.filters, other))
		// Nothing offered is every file offered.
		assertEquals(Offered.ALL, Offered.of(emptyList()))
		assertEquals(listOf(TypeFilter.ALL_FILES), Offered.of(emptyList(), other).filters)

		val state = picker()

		assertEquals(state.filters, after(state, BrowserEvent.Filtered(other)).filters)
		assertEquals(state.typeFilter, after(state, BrowserEvent.Filtered(other)).typeFilter)
		assertEquals(images, after(state, BrowserEvent.Filtered(images)).typeFilter)
	}
}
