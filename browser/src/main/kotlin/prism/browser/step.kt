package prism.browser

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import prism.desktop.Launched
import prism.index.query.Own
import prism.index.query.QueryText
import prism.preferences.Builtin
import prism.preferences.ColumnId
import prism.preferences.Filters
import prism.preferences.Kind
import prism.preferences.Layout
import prism.preferences.Listings
import prism.preferences.Look
import prism.preferences.Order
import prism.preferences.Preferences
import prism.preferences.QuickAccess
import prism.preferences.Role
import prism.preferences.Sort

/*
 * The reducer: the picker's next state from its last and what changed it, and what it asks to be done. It is pure:
 * it reads nothing and runs nothing, so every rule here is a test of values (picker's StepTest), and whoever runs
 * the picker (session.kt) only carries out the effects and sends their answers back.
 *
 * The rules are a desktop's own file dialog's, so that nothing surprises:
 *
 * - A click chooses a row; Ctrl adds it or takes it away, and Shift chooses the run from the last row clicked,
 *   where several can be chosen. The arrows do the same from the keyboard, Ctrl moving without choosing.
 * - A double click, or Enter, opens a folder, and accepts a file.
 * - Accepting opens a chosen folder rather than returning it, except in a folder picker, which returns it.
 * - What is typed into the name field is what is accepted, looked up on disk first: a folder typed is gone into, a
 *   file is opened, and a name that is not there is said to be not found (or, saving, saved under).
 * - Saving over a file asks first.
 * - Going up lands the cursor on the folder just left.
 * - Hidden files are shown and hidden folders are not, until the user says otherwise. A kind chosen from the filter
 *   menu narrows the listing to that kind alone; chosen with Ctrl, it is added to the kinds shown (images and videos:
 *   both). The kinds stay chosen from folder to folder; none chosen is everything.
 * - Renaming, the trash and making a folder are asked of the disk, and the folder is read again after, whatever came
 *   of it. The Delete key asks before it moves anything to the trash; Shift+Delete and the menu do not. A name with a
 *   slash in it, or `.` or `..`, is refused before the disk is asked.
 * - F2 searches the index, in a window over the picker that lists
 *   the first few matches as the query is typed, its field empty each time it opens. A match chosen is gone to: a folder, into it; a file, to the folder it is in, chosen (saving, its name to
 *   save over). The search given shows every match in the listing, as a folder holding them all would show them,
 *   filtered as a folder is and ordered best first; Back from a folder gone into from them returns to them. A folder
 *   picker's search finds only folders.
 */

/**
 * The picker opened on [folder]: its first state, and the read of its folder that it waits for. Its listings look as
 * [listings] says, the user's defaults for a window, which the window keeps as its own from then on, whatever it goes
 * to; its listing is drawn [zoom] times its usual size, the nearest the wheel's steps come to it.
 */
fun opened(
	choosing: Choosing,
	folder: Path,
	home: Path,
	sidebar: Sidebar,
	title: String = titleOf(choosing),
	filters: List<TypeFilter> = listOf(TypeFilter.ALL_FILES),
	search: Search = Search.Unavailable(BrowserState.NO_INDEX),
	listings: Listings = Listings(),
	zoom: Float = 1f,
	/** The filter in use as it opens, where it is one of [filters]; their first, otherwise. */
	filter: TypeFilter? = null,
): Step {
	val shown = folder.toAbsolutePath().normalize()

	val state =
		BrowserState(
			title = title,
			chose = choosing.unchosen(),
			at = Location.Folder(shown),
			home = home.toAbsolutePath().normalize(),
			history = History(),
			sidebar = sidebar,
			listing = Listing.Reading,
			looks = Looks(listings),
			offered = Offered.of(filters, filter),
			query = "",
			pointed = null,
			address = Address.Crumbs,
			notice = null,
			waiting = null,
			search = search,
			zooms =
				Zoom.nearest(zoom).let {
					Zooms(list = Zoom.By(minOf(it.step, Zoom.LIST_LARGEST.step)), gallery = it)
				},
		)

	return Step(state, listOf(Effect.Read(shown)))
}

/** How large [preferences] say a listing is drawn as a window of [role] opens. */
fun zoomOf(preferences: Preferences, role: Role): Float =
	when (role) {
		Role.Explorer -> preferences.scales.explorer
		Role.Picker -> preferences.scales.picker
	}

/** An explorer opened on [placed]: its first state, and the listing of the place that it waits for. */
fun openedIn(
	placed: Placed,
	home: Path,
	sidebar: Sidebar,
	search: Search = Search.Unavailable(BrowserState.NO_INDEX),
	listings: Listings = Listings(),
	zoom: Float = 1f,
): Step =
	opened(
		Choosing.Exploring(),
		home,
		home,
		sidebar,
		search = search,
		listings = listings,
		zoom = zoom,
	).let {
		it.state.copy(at = Location.Place(placed)).listing(placed)
	}

/** An explorer opened on the System page: its first state, and the read of the machine's storage that it waits for. */
fun openedOnSystem(
	home: Path,
	sidebar: Sidebar,
	search: Search = Search.Unavailable(BrowserState.NO_INDEX),
	listings: Listings = Listings(),
	zoom: Float = 1f,
): Step =
	opened(
		Choosing.Exploring(),
		home,
		home,
		sidebar,
		search = search,
		listings = listings,
		zoom = zoom,
	).state.shown(Location.System(), History(), null)

fun titleOf(choosing: Choosing): String =
	when (choosing) {
		is Choosing.OneFile -> "Open"
		is Choosing.Files -> "Open files"
		is Choosing.SaveAs -> "Save as"
		is Choosing.Folder -> "Select folder"
		is Choosing.Exploring -> "Files"
	}

/** [state] after [input]. */
fun step(state: BrowserState, input: Input): Step =
	when (input) {
		is Answer -> answered(state, input)
		is BrowserEvent ->
			when (val notice = state.notice) {
				null -> did(state, input)
				else -> noticed(state, notice, input)
			}
	}

// ---- what the user did ---------------------------------------------------------------------------------------

private fun did(state: BrowserState, event: BrowserEvent): Step =
	when (event) {
		is BrowserEvent.Opened -> state.went(event.path)
		BrowserEvent.Back -> state.back()
		BrowserEvent.Forward -> state.forward()
		BrowserEvent.Up -> state.folder?.parent?.let { state.went(it) } ?: state.upWithin()
		BrowserEvent.Refreshed -> state.reread()
		is BrowserEvent.Clicked -> Step(state.clicked(event.row, event.click))
		BrowserEvent.ClickedNowhere -> Step(state.copy(chose = state.choosing.unchosen()))
		is BrowserEvent.Activated -> state.activated(event.row)
		// A row given as the answer, from its menu: saving, a file to save over, which is asked first as opening it
		// asks; otherwise chosen, and accepted.
		is BrowserEvent.Picked ->
			when {
				event.row !in state.rows -> Step(state)
				state.choosing is Choosing.SaveAs -> state.activated(event.row)
				else ->
					state.copy(chose = state.choosing.chose(setOf(event.row), state.rows)).accepted()
			}
		is BrowserEvent.Moved -> Step(state.moved(event.move, event.carry))
		BrowserEvent.Toggled -> Step(state.toggled())
		BrowserEvent.ChoseAll -> Step(state.choseAll())
		is BrowserEvent.Jumped -> Step(state.jumped(event.prefix))
		// What cannot be ordered so (too many results to read every one's date) stays as it is.
		is BrowserEvent.Sorted ->
			if (state.orders(event.by))
				state.looking { copy(order = order.toggled(event.by)) }
			else
				Step(state)
		is BrowserEvent.SortedBy ->
			if (state.orders(event.sort.by))
				state.looking { copy(order = Order.By(event.sort)) }
			else
				Step(state)
		BrowserEvent.SortedByRelevance -> state.looking { copy(order = Order.Relevance) }
		is BrowserEvent.ShowedColumn ->
			state.looking {
				with(columns(state.kind).showing(event.column, event.shown))
			}
		is BrowserEvent.AlignedColumn ->
			state.looking {
				with(columns(state.kind).aligning(event.column, event.align))
			}
		is BrowserEvent.Scrolled ->
			state.copy(sight = event.first..event.last).let { seen ->
				seen.scrolled(event.first, event.last).then { it.bornNext() }
			}
		BrowserEvent.ToggledHiddenFiles ->
			state.filtering {
				copy(hidden = hidden.copy(files = !hidden.files))
			}
		BrowserEvent.ToggledHiddenFolders ->
			state.filtering {
				copy(hidden = hidden.copy(folders = !hidden.folders))
			}
		BrowserEvent.ToggledEveryHidden -> state.filtering { copy(hidden = hidden.toggled()) }
		is BrowserEvent.ShowedOnlyKind -> state.filtering { copy(kinds = setOf(event.kind)) }
		is BrowserEvent.ToggledKind ->
			state.filtering {
				copy(
					kinds =
						if (event.kind in kinds)
							kinds - event.kind
						else
							kinds + event.kind,
				)
			}
		BrowserEvent.ShowedEveryKind -> state.filtering { copy(kinds = emptySet()) }
		// Each layout has its own filters (a gallery may leave out what a list shows), so what is shown is arranged anew.
		is BrowserEvent.Viewed ->
			state.rearranged(layout = event.layout).let {
				Step(it, it.ordering())
			}
		is BrowserEvent.Zoomed -> Step(state.zoomed(event.steps))
		BrowserEvent.Unzoomed ->
			Step(
				state.copy(
					zooms =
						when (state.layout) {
							Layout.List -> state.zooms.copy(list = Zoom.NORMAL)
							Layout.Gallery -> state.zooms.copy(gallery = Zoom.NORMAL)
						},
				),
			)
		is BrowserEvent.Measured -> Step(state.copy(whole = event.whole))
		BrowserEvent.Undone ->
			state.changes.undo.lastOrNull()?.let {
				state
					.copy(
						changes = state.changes.copy(undo = state.changes.undo.dropLast(1)),
					).making(it.by, Doing.Undo(it.done))
			} ?: Step(state)
		BrowserEvent.Redone ->
			state.changes.redo.lastOrNull()?.let {
				state
					.copy(
						changes = state.changes.copy(redo = state.changes.redo.dropLast(1)),
					).making(it.by, Doing.Redo(it.done))
			} ?: Step(state)
		is BrowserEvent.StartedRenaming -> Step(state.renaming(event.row))
		is BrowserEvent.RenameTyped ->
			Step(
				state.copy(naming = state.renaming?.copy(text = event.text)),
			)
		BrowserEvent.Renamed -> state.renamed()
		BrowserEvent.RenameCancelled -> Step(state.copy(naming = null))
		// In the trash, what is trashed already can only be deleted for good, which is asked first.
		is BrowserEvent.Trashed ->
			if (state.inTrash)
				state.askingToErase(event.rows)
			else
				state
					.pathsOf(event.rows)
					.takeIf { it.isNotEmpty() }
					?.let { state.making(Change.Trash(it)) }
					?: Step(state)
		is BrowserEvent.AskedToErase -> state.askingToErase(event.rows)
		is BrowserEvent.PutBack ->
			state
				.trashedOf(event.rows)
				.takeIf { it.isNotEmpty() }
				?.let { state.making(Change.PutBack(it)) }
				?: Step(state)
		BrowserEvent.AskedToEmpty -> state.notice(Notice.ConfirmErase(Erasing.Everything))
		BrowserEvent.OpenedTrash ->
			if (state.inTrash)
				state.reread()
			else
				state.shown(Location.Trash, History(state.history.back + state.at, emptyList()), null)
		BrowserEvent.OpenedSystem ->
			if (state.system != null)
				state.reread()
			else
				state.shown(
					Location.System(),
					History(state.history.back + state.at, emptyList()),
					null,
				)
		is BrowserEvent.ShowedStores ->
			Step(state.system?.let { state.copy(at = it.copy(shows = event.kind)) } ?: state)
		is BrowserEvent.FoldedStore ->
			Step(
				state.system?.let {
					state.copy(
						at =
							it.copy(
								folded =
									if (event.key in it.folded)
										it.folded - event.key
									else
										it.folded + event.key,
							),
					)
				} ?: state,
			)
		BrowserEvent.MadeFolder ->
			state.folder?.let {
				state.making(Change.MakeFolder(it.resolve(state.freeName("New folder"))))
			} ?: Step(state)
		is BrowserEvent.CopiedPaths ->
			Step(
				state,
				if (event.paths.isEmpty())
					emptyList()
				else
					listOf(Effect.Copy(event.paths.joinToString("\n"))),
			)
		is BrowserEvent.Searched -> Step(state.rearranged(query = event.query))
		is BrowserEvent.Filtered ->
			Step(state.rearranged(offered = state.offered.using(event.filter)))
		is BrowserEvent.Named ->
			Step(
				state.copy(chose = state.choosing.named(event.text)),
			)
		BrowserEvent.EditedAddress ->
			Step(
				state.copy(address = Address.Editing(state.folder?.toString().orEmpty())),
			)
		is BrowserEvent.AddressTyped ->
			Step(
				if (state.address is Address.Editing)
					state.copy(address = Address.Editing(event.text))
				else
					state,
			)
		BrowserEvent.Went -> state.goingTo()
		BrowserEvent.LeftAddress -> Step(state.copy(address = Address.Crumbs))
		BrowserEvent.Accepted -> state.accepted()
		BrowserEvent.Cancelled -> Step(state, listOf(Effect.Finish(Outcome.Cancelled)))
		// Answers to a notice, with none shown.
		BrowserEvent.Confirmed, BrowserEvent.Dismissed -> Step(state)
		BrowserEvent.OpenedSearch -> state.openedSearch("")
		is BrowserEvent.EditedSearch -> state.openedSearch(event.text)
		BrowserEvent.ToggledSearchHelp -> Step(state.withSearch { copy(help = !help) })
		BrowserEvent.ClosedSearch ->
			Step(
				state.copy(
					search =
						when (val search = state.search) {
							is Search.Unavailable -> search.copy(open = false)
							is Search.Ready -> search.closed()
						},
				),
			)
		is BrowserEvent.SearchTyped -> state.searchTyped(event.text)
		is BrowserEvent.MatchMoved -> Step(state.withSearch { moved(event.by) })
		is BrowserEvent.MatchChosen -> state.chosenMatch(event.at)
		BrowserEvent.SearchEntered ->
			(state.search as? Search.Ready)?.highlighted?.let(state::chosenMatch)
				?: state.showingMatches()
		BrowserEvent.ShowedResults -> state.showingMatches()
		BrowserEvent.MadeFile ->
			state.folder?.let {
				state.making(Change.MakeFile(it.resolve(state.freeName("New file"))))
			} ?: Step(state)
		is BrowserEvent.CopiedFiles -> state.clipping(event.rows, cut = false)
		is BrowserEvent.CutFiles -> state.clipping(event.rows, cut = true)
		is BrowserEvent.Pasted ->
			(event.into ?: state.folder)?.let { Step(state, listOf(Effect.ReadClip(it))) }
				?: state.pastingIn()
		is BrowserEvent.Dropped -> state.dropped(event.paths, event.spot, event.transfer)
		// One of prism's own folders is shown again, rather than pinned beside itself.
		is BrowserEvent.Pinned ->
			state.sidebar.let { sidebar ->
				state.arranging(
					sidebar.known.entries
						.firstOrNull { it.value.path == event.path }
						?.let { sidebar.quick.showing(it.key, true) }
						?: sidebar.quick.pinning(event.path),
				)
			}
		is BrowserEvent.Unpinned ->
			state.arranging(
				when (val item = event.item) {
					is SidebarItem.Own -> state.sidebar.quick.showing(item.builtin, false)
					SidebarItem.Trash -> state.sidebar.quick.showing(Builtin.Trash, false)
					SidebarItem.System -> state.sidebar.quick.showing(Builtin.System, false)
					is SidebarItem.Pinned -> state.sidebar.quick.unpinning(item.path)
					is SidebarItem.Drive -> state.sidebar.quick
				},
			)
		is BrowserEvent.Arranged -> state.arranging(event.quick)
		is BrowserEvent.ArrangingQuickAccess -> Step(state.copy(arrangingQuick = event.open))
		// Where the terminal chosen has no place for a folder, nothing opens one in a folder: its menu item is off.
		is BrowserEvent.OpenedTerminal ->
			(event.folder ?: state.folder)
				?.takeIf { state.terminal.opensInFolder }
				?.let { Step(state, listOf(Effect.Terminal(it))) }
				?: Step(state)
		is BrowserEvent.Requested -> Step(state, listOf(Effect.Ask(event.request)))
		is BrowserEvent.Did -> state.doing(Pending(event.action, event.subject, event.answers))
		is BrowserEvent.Answered -> state.answered(event.folder)
		is BrowserEvent.WentWithin -> state.wentWithin(event.within)
		is BrowserEvent.DroppedCarried -> state.droppedCarried(event.carried, event.spot)
		is BrowserEvent.DroppedEntries ->
			state.carrying(event.entries)?.let { state.droppedCarried(it, event.spot) }
				?: Step(state)
		is BrowserEvent.Stopped -> state.stopping(event.task)
		is BrowserEvent.CopiedText -> Step(state, listOf(Effect.Copy(event.text)))
		is BrowserEvent.Banded ->
			Step(
				if (state.choosing.many) {
					val taken = event.rows.filterTo(LinkedHashSet()) { it in state.rows }

					state.copy(
						chose = state.choosing.chose(taken, state.rows),
						pointed = taken.lastOrNull()?.let { Cursor(it, it) } ?: state.cursor,
					)
				} else {
					state
				},
			)
		// A line typed with nothing asking for one.
		is BrowserEvent.AnswerTyped -> Step(state)
	}

/** While a notice is shown, it is all the picker answers to: the rest of it is behind the notice. */
private fun noticed(state: BrowserState, notice: Notice, event: BrowserEvent): Step =
	when (event) {
		BrowserEvent.Confirmed ->
			when (notice) {
				is Notice.Replace -> state.finished(listOf(notice.path))
				is Notice.ConfirmErase ->
					Step(
						state.copy(notice = null),
						listOf(Effect.Erase(notice.erasing)),
					)
				is Notice.NotFound, is Notice.NotAFolder, is Notice.Trouble, is Notice.NoFolder ->
					Step(
						state.copy(notice = null),
					)
				is Notice.Asking -> state.copy(notice = null).typed(notice.text)
				is Notice.ConfirmAction ->
					state.pending?.let { state.copy(notice = null).doing(it.copy(confirmed = true)) }
						?: Step(state.copy(notice = null))
			}
		// An action that was asking is let go with its question.
		BrowserEvent.Dismissed ->
			Step(
				state.copy(
					notice = null,
					pending =
						state.pending.takeUnless {
							notice is Notice.Asking ||
								notice is Notice.ConfirmAction
						},
				),
			)
		is BrowserEvent.AnswerTyped ->
			Step(
				if (notice is Notice.Asking)
					state.copy(notice = notice.copy(text = event.text))
				else
					state,
			)
		BrowserEvent.Cancelled -> Step(state, listOf(Effect.Finish(Outcome.Cancelled)))
		is BrowserEvent.Stopped -> state.stopping(event.task)
		// Copying what the notice says leaves it shown.
		is BrowserEvent.CopiedText -> Step(state, listOf(Effect.Copy(event.text)))
		else -> Step(state)
	}

// ---- going places ----------------------------------------------------------------------------------------------

/**
 * [to] gone to: the place left goes behind it in the history, and nothing is ahead. Going to the folder shown reads
 * it again, and lands where [landing] says when that read arrives.
 */
internal fun BrowserState.went(to: Path, landing: Landing? = landingFrom(to)): Step {
	val target = to.toAbsolutePath().normalize()

	return if (target == folder)
		reread(landing).let { Step(it.state.copy(address = Address.Crumbs), it.effects) }
	else
		shown(Location.Folder(target), History(history.back + at, emptyList()), landing)
}

/**
 * The place shown read again, the cursor to land where [landing] says (if anywhere) when the read arrives: a folder
 * read, or a search's results searched for again, shown in their place when they are found.
 */
internal fun BrowserState.reread(landing: Landing? = null): Step =
	when (val at = at) {
		is Location.Folder ->
			Step(
				copy(landing = landing ?: this.landing),
				listOf(Effect.Read(at.path)),
			)
		is Location.Results -> {
			val text = at.finding.text

			Step(
				this,
				listOf(
					Effect.Search(
						text,
						queryOf(QueryText.read(text), choosing),
						Purpose.Refresh,
						inPlace?.tree,
					),
				),
			)
		}
		// A place is listed again, what it shows staying until the new listing arrives.
		is Location.Place -> copy(landing = landing ?: this.landing).listing(at.placed)
		Location.Trash ->
			Step(
				copy(landing = landing ?: this.landing),
				listOf(Effect.ReadTrash),
			)
		// What it shows stays until the new read arrives.
		is Location.System -> Step(this, listOf(Effect.ReadStorage))
	}

private fun BrowserState.back(): Step =
	history.back.lastOrNull()?.let {
		shown(
			it,
			History(history.back.dropLast(1), listOf(at) + history.forward),
			landingFrom(it),
		)
	} ?: Step(this)

private fun BrowserState.forward(): Step =
	history.forward.firstOrNull()?.let {
		shown(
			it,
			History(history.back + at, history.forward.drop(1)),
			landingFrom(it),
		)
	} ?: Step(this)

/** Going from the folder shown to its parent lands on the folder left, as every file manager does. */
private fun BrowserState.landingFrom(to: Location): Landing? =
	(to as? Location.Folder)?.let { landingFrom(it.path) }

private fun BrowserState.landingFrom(to: Path): Landing? =
	folder
		?.takeIf { it.parent == to }
		?.fileName
		?.let { Landing.On(RowId(it.toString()), choose = false) }

/**
 * [to] shown in place of what is shown, with nothing chosen and nothing searched for in the listing: a folder,
 * waiting for its read; a search's results, at once, and their sizes and dates read as [rows] in sight need them.
 */
internal fun BrowserState.shown(to: Location, history: History, landing: Landing?): Step {
	val left =
		copy(
			at = to,
			history = history,
			listing = Listing.Reading,
			landing = landing,
			naming = null,
			query = "",
			chose = choosing.unchosen(),
			pointed = null,
			address = Address.Crumbs,
			waiting = null,
		)

	return when (to) {
		is Location.Folder -> Step(left, listOf(Effect.Read(to.path)))
		is Location.Results -> left.showingResults(to.finding)
		// A place listed already is shown from what its listing gave; one that is not, once it is listed.
		is Location.Place ->
			to.tree?.let { Step(left.arrived(Listing.Shown(it.rows(to.within)))) }
				?: left.listing(to.placed)
		Location.Trash -> Step(left, listOf(Effect.ReadTrash))
		// Read afresh: what it showed when it was left is not what is so now.
		// Which kind it showed, and what was folded, are as they were.
		is Location.System ->
			Step(
				left.copy(at = to.copy(storage = null)),
				listOf(Effect.ReadStorage),
			)
	}
}

/**
 * [finding]'s results in the listing, nothing of them read on disk yet: few enough, every one read at once, the best
 * first; more, those in sight first, as the view says what is (and the rest as it is scrolled to). Their order is the
 * arrangement's, where it can be had of these results, and best first where it cannot.
 */
private fun BrowserState.showingResults(finding: Finding.Matched): Step {
	val listing = Listing.Found(finding, Stats.none(finding.matches.size))
	// The window's order of results, where these can be had in it; best first, from now on, where they cannot.
	val orderable =
		look.order.sort
			?.by
			?.let(listing::orders) != false

	val shown =
		rearranged(
			looks =
				if (orderable)
					looks
				else
					looks.looking(kind, look.copy(order = Order.Relevance)),
			listing = listing,
		)

	// What is shown is the listing just made's, whatever else: with none of it shown, those found, best first.
	val rows = shown.rows as? Rows.Found
	val inSight = rows?.ranksBetween(0, if (listing.few) rows.size else PAGE) ?: Ranks.NONE
	val first =
		if (listing.few)
			inSight.then(listing.matches.ranks)
		else
			inSight

	return shown.stating(first).let {
		Step(
			it.state,
			it.effects + it.state.ordering(),
		)
	}
}

/** The results at [ranks] asked to be read on disk, those not asked for already, in batches. */
private fun BrowserState.stating(ranks: Ranks): Step {
	val listing = listing as? Listing.Found ?: return Step(this)
	val asked = listing.stats.unasked(ranks)

	if (asked.isEmpty())
		return Step(this)

	return Step(
		copy(
			listing = listing.knowing(listing.stats.asking(asked), asked),
		),
		asked.chunked(STAT_BATCH).map { Effect.Stat(listing.matches, it) },
	)
}

/** The rows from [first] to [last] in sight: of a search's results, those and the next few read, where not yet. */
private fun BrowserState.scrolled(first: Int, last: Int): Step {
	val rows = rows as? Rows.Found ?: return Step(this)

	return stating(rows.ranksBetween(first - BEHIND, last + 1 + AHEAD))
}

/** The order the arrangement asks of a search's results, asked to be worked out behind where it is not yet. */
private fun BrowserState.ordering(): List<Effect> {
	val listing = listing as? Listing.Found ?: return emptyList()

	return listOfNotNull(
		listing.pending(arrangement.order)?.let {
			Effect.Order(listing.matches, it)
		},
	)
}

/** How many results are read on disk at a time. */
private const val STAT_BATCH = 256

/** How many of too many results are read first, before the view says which are in sight: a window's worth and more. */
private const val PAGE = 120

/** How many rows behind and ahead of those in sight are read with them, so a scroll finds them read. */
private const val BEHIND = 40
private const val AHEAD = 160

/** The cursor where [landing] says, if that row is shown. */
internal fun BrowserState.landed(landing: Landing): BrowserState =
	if (landing.row !in rows)
		this
	else
		when (landing) {
			is Landing.On ->
				copy(
					pointed = Cursor(landing.row, landing.row),
					chose =
						if (landing.choose)
							choosing.chose(setOf(landing.row), rows)
						else
							choosing,
				)
			is Landing.Renaming -> renaming(landing.row)
		}

// ---- the listing ------------------------------------------------------------------------------------------------

/**
 * The listing's look changed by [change], and kept for its kind in this window, wherever it goes; the listing arranged
 * anew, and an order of results asked for behind where it is not worked out yet.
 */
private fun BrowserState.looking(change: Look.() -> Look): Step =
	rearranged(looks = looks.looking(kind, look.change())).let { Step(it, it.ordering()) }

/** What the listing leaves out changed by [change], kept for its kind and its layout in this window. */
private fun BrowserState.filtering(change: Filters.() -> Filters): Step =
	Step(rearranged(looks = looks.filtering(kind, layout, entryFilters.change())))

/** Whether the listing can be put in an order by [column]: a search's results not always (too many to read). */
private fun BrowserState.orders(column: ColumnId): Boolean =
	(listing as? Listing.Found)?.orders(column) ?: true

/**
 * [listing] shown, arranged as [looks], [layout], the filter [offered] has in use and [query] say, in place of what
 * was and how it was arranged. What was chosen is kept where it is still shown, and let go where it is not: a state
 * says nothing is marked that it does not show whatever it is given ([Marks]), and what is let go here is not marked
 * again when the row comes back (a query cleared, a filter lifted).
 */
private fun BrowserState.rearranged(
	looks: Looks = this.looks,
	layout: Layout = this.layout,
	offered: Offered = this.offered,
	query: String = this.query,
	listing: Listing = this.listing,
): BrowserState {
	val rows =
		rowsOf(
			listing,
			Arrangement(
				looks.look(role, kind).order,
				looks.filters(role, kind, layout),
				offered.using,
				query,
			),
		)

	return copy(
		listing = listing,
		looks = looks,
		layout = layout,
		offered = offered,
		query = query,
		chose = choosing.within(rows),
		pointed = cursor.within(rows),
		naming = renaming?.takeIf { it.row in rows },
	)
}

/** [listing] shown in place of what was, arranged as the window arranges it, what was chosen kept where it still is. */
private fun BrowserState.relisted(listing: Listing): BrowserState =
	rearranged(listing = listing)

private fun BrowserState.clicked(row: RowId, click: Click): BrowserState {
	if (row !in rows)
		return this

	val anchor = cursor?.anchor ?: row

	return when {
		!choosing.many || click == Click.Only ->
			copy(chose = choosing.chose(setOf(row), rows), pointed = Cursor(row, row))
		click == Click.Toggle ->
			copy(
				chose = choosing.toggled(row, rows),
				pointed = Cursor(row, row),
			)
		else ->
			copy(
				chose = choosing.chose(rows.between(anchor, row), rows),
				pointed = Cursor(row, anchor),
			)
	}
}

private fun BrowserState.moved(move: Move, carry: Carry): BrowserState {
	if (rows.isEmpty())
		return this

	val from = cursor?.at?.let(rows::indexOf)
	val to =
		when (move) {
			Move.First -> 0
			Move.Last -> rows.size - 1
			// The first move with no cursor yet lands on the first row, whichever way it goes.
			is Move.By -> from?.let { (it + move.rows).coerceIn(0, rows.size - 1) } ?: 0
		}
	val target = rows.idAt(to)
	val anchor = cursor?.anchor ?: target

	return when {
		carry == Carry.Extend && choosing.many ->
			copy(
				chose = choosing.chose(rows.between(anchor, target), rows),
				pointed = Cursor(target, anchor),
			)
		carry == Carry.Stay && choosing.many -> copy(pointed = Cursor(target, anchor))
		else ->
			copy(
				chose = choosing.chose(setOf(target), rows),
				pointed = Cursor(target, target),
			)
	}
}

private fun BrowserState.toggled(): BrowserState =
	cursor?.let {
		copy(chose = choosing.toggled(it.at, rows), pointed = Cursor(it.at, it.at))
	}
		?: moved(Move.First, Carry.Choose)

private fun BrowserState.choseAll(): BrowserState =
	if (choosing.many)
		copy(
			chose =
				choosing.chose(
					(0 until rows.size).mapTo(LinkedHashSet(), rows::idAt),
					rows,
				),
		)
	else
		this

/**
 * The cursor to the next row whose name starts with [prefix], from the cursor's own row (a second letter typed
 * narrows where it is) or, for one letter or the same letter again, from the row after it, so that pressing a
 * letter again goes through the rows it starts.
 */
private fun BrowserState.jumped(prefix: String): BrowserState {
	if (prefix.isEmpty() || rows.isEmpty())
		return this

	val cycling = prefix.all { it.equals(prefix[0], ignoreCase = true) }
	val sought =
		if (cycling)
			prefix.take(1)
		else
			prefix
	val from = cursor?.at?.let(rows::indexOf)
	val start =
		when {
			from == null -> 0
			cycling -> from + 1
			else -> from
		}

	val found =
		(0 until rows.size)
			.asSequence()
			.map { (start + it).mod(rows.size) }
			.firstOrNull { rows.nameAt(it).startsWith(sought, ignoreCase = true) }
			?.let(rows::idAt)
			?: return this

	return copy(
		chose = choosing.chose(setOf(found), rows),
		pointed = Cursor(found, found),
	)
}

internal fun BrowserState.activated(id: RowId): Step {
	val row = rows[id] ?: return Step(this)

	val path = row.path ?: return activatedInPlace(row)

	return when {
		row.isFolder -> went(path)
		choosing is Choosing.Exploring -> Step(this, listOf(Effect.Open(listOf(path))))
		// Saving, a file opened is the file to save over: chosen, and saved over once the user says so. The name
		// typed is left as it is, for the answer to be no.
		choosing is Choosing.SaveAs ->
			copy(chose = choosing.chose(setOf(id), rows)).notice(Notice.Replace(path))
		else -> finished(listOf(path))
	}
}

// ---- accepting ---------------------------------------------------------------------------------------------------

internal fun BrowserState.accepted(): Step {
	if (waiting != null)
		return Step(this)

	val chosen = chosenRows
	val files = chosen.filterNot { it.isFolder }

	return when (choosing) {
		// What is chosen opened: the files with their applications; a folder alone, gone into.
		is Choosing.Exploring ->
			when {
				// An entry of a place is opened as a double click opens it; several are not opened together.
				chosen.any { it.path == null } ->
					chosen.singleOrNull()?.let { activated(it.id) }
						?: Step(this)
				files.isNotEmpty() -> Step(this, listOf(Effect.Open(files.mapNotNull { it.path })))
				else -> chosen.singleOrNull()?.let { activated(it.id) } ?: Step(this)
			}
		is Choosing.OneFile ->
			when {
				choosing.typed != null -> looking(choosing.typed)
				else -> chosen.singleOrNull()?.let { opening(it) } ?: Step(this)
			}
		is Choosing.Files ->
			when {
				choosing.typed != null -> looking(choosing.typed)
				files.isNotEmpty() -> finished(files.mapNotNull { it.path })
				else -> chosen.singleOrNull()?.let { opening(it) } ?: Step(this)
			}
		// The name typed is what is saved under, whatever row is chosen.
		is Choosing.SaveAs -> looking(choosing.name)
		is Choosing.Folder ->
			when {
				!choosing.typed.isNullOrBlank() -> looking(choosing.typed)
				else ->
					(chosen.singleOrNull()?.path ?: folder)?.let { finished(listOf(it)) } ?: Step(this)
			}
	}
}

private fun BrowserState.opening(row: Row): Step {
	val path = row.path ?: return activated(row.id)

	return if (row.isFolder)
		went(path)
	else
		finished(listOf(path))
}

/** What [text] names, looked up on disk before anything is done with it. */
private fun BrowserState.looking(text: String): Step {
	val names = namesIn(text)

	if (names.isEmpty())
		return Step(this)

	val paths = names.mapNotNull { pathOf(it, base, home) }

	if (paths.size < names.size)
		return Step(
			copy(
				notice =
					if (base ==
						null
					)
						Notice.NoFolder(text.trim())
					else
						Notice.NotFound(text.trim()),
			),
		)

	val lookup = Lookup.Accepting(text, paths)

	return Step(copy(waiting = lookup), listOf(Effect.Look(lookup)))
}

private fun BrowserState.goingTo(): Step {
	val text = (address as? Address.Editing)?.text?.trim() ?: return Step(this)

	if (text.isEmpty())
		return Step(copy(address = Address.Crumbs))

	val path =
		pathOf(text, folder, home)
			?: return Step(
				copy(
					notice =
						if (folder ==
							null
						)
							Notice.NoFolder(text)
						else
							Notice.NotFound(text),
				),
			)

	return goingTo(text, path)
}

/** [path] gone to as a path typed as [text] is: a folder gone into, a file's folder gone to with it chosen. */
private fun BrowserState.goingTo(text: String, path: Path): Step {
	val lookup = Lookup.Going(text, path)

	return Step(copy(waiting = lookup), listOf(Effect.Look(lookup)))
}

/**
 * Files dropped on [spot]. On a folder, they are given to it as [transfer] says. On the listing itself, an explorer
 * gives them to the folder shown (results, which are no folder, take nothing); a picker goes to the first of them
 * instead, as to a path typed, since files dropped on a picker are files to choose.
 */
private fun BrowserState.dropped(
	paths: List<Path>,
	spot: DropSpot,
	transfer: Transfer,
): Step =
	when (spot) {
		is DropSpot.Into -> transferring(paths, spot.folder, transfer)
		DropSpot.Here ->
			when {
				choosing is Choosing.Exploring ->
					folder?.let { transferring(paths, it, transfer) }
						?: givingIn(paths)
				else -> paths.firstOrNull()?.let { goingTo(it.toString(), it) } ?: Step(this)
			}
	}

/**
 * [sources] given to [into] as [transfer] says, as a paste or a drop gives them. Nothing is done for what would be no
 * change at all: a folder given to itself, or files moved to the folder they are in.
 */
internal fun BrowserState.transferring(
	sources: List<Path>,
	into: Path,
	transfer: Transfer,
): Step {
	val given =
		sources
			.filter { it != into }
			.filter { transfer != Transfer.Move || it.parent != into }

	return if (given.isEmpty())
		Step(this)
	else
		making(transfer.changeOf(given, into))
}

private fun BrowserState.finished(paths: List<Path>): Step =
	Step(this, listOf(Effect.Finish(Outcome.Chosen(paths))))

internal fun BrowserState.notice(notice: Notice): Step = Step(copy(notice = notice))

// ---- what the disk said -------------------------------------------------------------------------------------------

private fun answered(state: BrowserState, answer: Answer): Step =
	when (answer) {
		// What nothing opened is offered to an application the user chooses; what failed to start is said.
		is Answer.Opened ->
			Step(
				state.copy(
					notice =
						answer.opening.failures.firstOrNull()?.let {
							Notice.Failed("Couldn't open “${it.what}”", it.why)
						}
							?: state.notice,
				),
				listOfNotNull(
					answer.opening.unopened
						.takeIf {
							it.isNotEmpty()
						}?.let { Effect.Ask(Request.OpenWith(it)) },
				),
			)
		is Answer.Started ->
			when (val launched = answer.launched) {
				is Launched.Started -> Step(state)
				is Launched.Failed ->
					state.notice(
						Notice.Failed("Couldn't open ${launched.what}", launched.why),
					)
			}
		is Answer.Broke -> state.broke(answer.asking, answer.fault)
		is Answer.Clipped -> state.pasted(answer.into, answer.clip, answer.carried)
		is Answer.Listed -> state.listed(answer.placed, answer.listing)
		is Answer.Ran -> state.ran(answer.job, answer.failure)
		is Answer.Said -> state.said(answer.task, answer.line)
		// The folder shown changed on disk: read again, what is chosen and where the cursor is kept as they are.
		is Answer.Touched ->
			if (answer.folder == state.folder)
				state.reread()
			else
				Step(state)
		is Answer.TempMade -> state.openingOut(answer.folder, answer.carried)
		is Answer.ScratchMade -> state.scratched(answer.folder, answer.pending)
		is Answer.Configured ->
			Step(
				state.copy(
					actions = answer.actions,
					terminal = answer.terminal,
					menus = answer.menus,
				),
			)
		is Answer.Placed -> Step(state.copy(sidebar = answer.sidebar))
		is Answer.Relisted ->
			Step(
				state.copy(looks = state.looks.relisted(state.role, answer.listings)),
			)
		// A read of a folder no longer shown is too late to matter.
		is Answer.Arrived ->
			if (answer.folder == state.folder)
				state.arrived(answer.read).copy(bornReading = emptySet()).bornNext()
			else
				Step(state)
		is Answer.Born ->
			if (answer.folder == state.folder)
				state
					.born(
						answer.times.mapValues { (_, time) ->
							time?.let(Created::At) ?: Created.Unkept
						},
					).bornNext()
			else
				Step(state)
		is Answer.Changed -> state.changed(answer.change, answer.result, answer.doing)
		// Gone for good: whatever is shown read again, and where something could not be, why.
		is Answer.Erased ->
			state.reread().let {
				Step(
					it.state.copy(
						notice =
							answer.failed?.let { why ->
								Notice.Failed("Couldn't delete ${erasedOf(answer.erasing)}", why)
							}
								?: it.state.notice,
					),
					it.effects,
				)
			}
		is Answer.TrashRead ->
			if (state.inTrash)
				Step(state.arrived(answer.read))
			else
				Step(state)
		// The System page lists no files: once read, there is nothing more to wait for.
		is Answer.StorageRead ->
			state.system?.let {
				Step(
					state.copy(
						at = it.copy(storage = answer.storage),
						listing = Listing.Shown(emptyList()),
					),
				)
			} ?: Step(state)
		is Answer.Searched -> state.searched(answer.finding, answer.purpose)
		// What was read of results, or an order of them, for results no longer shown, is too late too.
		is Answer.Stated ->
			when (val listing = state.listing) {
				is Listing.Found ->
					Step(
						if (listing.matches === answer.matches)
							state.relisted(
								listing.knowing(listing.stats.read(answer.ranks, answer.stats), answer.ranks),
							)
						else
							state,
					)
				else -> Step(state)
			}
		is Answer.Ordered ->
			when (val listing = state.listing) {
				is Listing.Found ->
					Step(
						if (listing.matches === answer.matches)
							state.relisted(
								Listing.Found(
									listing.finding,
									listing.stats,
									listing.orders + (answer.column to answer.order),
								),
							)
						else
							state,
					)
				else -> Step(state)
			}
		// So is a lookup the picker no longer waits for.
		is Answer.Looked ->
			if (answer.lookup == state.waiting)
				state.copy(waiting = null).looked(answer.lookup) {
					answer.found[it]
						?: Found.Nothing(inFolder = false)
				}
			else
				Step(state)
	}

/**
 * prism went wrong answering [asking] ([fault]). Whatever waited for the answer is told there is none, each in the way
 * that is true of it (a folder not read is shown as that; what was being read of a result is not known, and is not
 * asked again; an order that cannot be had is given up), and nothing is put in an answer's place. It is said as
 * prism's mistake: in the listing, where that is where it shows; in the search's window, for a search; and otherwise
 * as a notice. An answer too late to matter (for a folder since left) changes nothing, as any such answer.
 */
private fun BrowserState.broke(asking: Effect.Asking, fault: Fault): Step {
	fun Step.said() =
		Step(state.copy(notice = Notice.Broke(failingOf(asking), fault)), effects)

	val unread = Listing.Unreadable(fault.said)

	return when (asking) {
		is Effect.Read ->
			if (asking.folder == folder)
				Step(arrived(unread).copy(bornReading = emptySet()))
			else
				Step(this)
		Effect.ReadTrash ->
			if (inTrash)
				Step(arrived(unread))
			else
				Step(this)
		is Effect.ListPlace -> listed(asking.placed, PlaceListing.Failed(fault.said))
		is Effect.Look ->
			if (asking.lookup == waiting)
				Step(copy(waiting = null)).said()
			else
				Step(this)
		// A change half done is a change: what is shown is read again, and nothing is kept to undo.
		is Effect.Make, is Effect.Erase -> reread().said()
		// What was last read of the machine's storage stays, which is what it is.
		Effect.ReadStorage ->
			if (system != null)
				Step(this).said()
			else
				Step(this)
		// The search's window says so itself; results shown that were to be searched again stay, and it is said.
		is Effect.Search ->
			searched(Finding.Failed(asking.text, fault), asking.purpose).let {
				if (asking.purpose == Purpose.Refresh && results?.text == asking.text)
					it.said()
				else
					it
			}
		is Effect.Stat ->
			(listing as? Listing.Found)
				?.takeIf { it.matches === asking.matches }
				?.let {
					Step(
						relisted(it.knowing(it.stats.unknown(asking.ranks), asking.ranks)),
					).said()
				}
				?: Step(this)
		// Every time not read yet is given up, not only those asked for: the next would be asked the same way.
		is Effect.Born ->
			if (asking.folder == folder)
				Step(
					born(
						(listing as? Listing.Shown)
							?.entries
							.orEmpty()
							.filter { it.created == Created.Unread }
							.associate { it.name to Created.Unknown },
					).copy(bornReading = emptySet()),
				).said()
			else
				Step(this)
		// The order that could not be worked out is given up: the results are best first, as they are before any.
		is Effect.Order ->
			(listing as? Listing.Found)
				?.takeIf {
					it.matches === asking.matches &&
						it.pending(arrangement.order) == asking.column
				}?.let {
					Step(
						rearranged(looks = looks.looking(kind, look.copy(order = Order.Relevance))),
					).said()
				}
				?: Step(this)
		// It is no longer under way, and what it may have half done is read again; nothing follows it.
		is Effect.Run -> ran(asking.job, fault.said).said()
		is Effect.Open, is Effect.Terminal, is Effect.ReadClip, is Effect.Launch,
		is Effect.Scratch, is Effect.Temp,
		-> Step(this).said()
	}
}

/** What could not be done, [asking] having gone wrong, as what is said of it names it. */
internal fun failingOf(asking: Effect.Asking): String =
	when (asking) {
		is Effect.Read -> "Couldn't read “${asking.folder.fileName ?: asking.folder}”"
		is Effect.Look -> "Couldn't look up the path"
		is Effect.Make -> failingOf(asking.change, asking.doing)
		is Effect.Erase -> "Couldn't delete ${erasedOf(asking.erasing)}"
		Effect.ReadTrash -> "Couldn't read the trash"
		Effect.ReadStorage -> "Couldn't read this computer's storage"
		is Effect.Search -> "Couldn't search for “${asking.text}”"
		is Effect.Stat -> "Couldn't read the sizes and dates of the results"
		is Effect.Born ->
			"Couldn't read creation dates in “${asking.folder.fileName ?: asking.folder}”"
		is Effect.Order -> "Couldn't sort the results"
		is Effect.Open -> "Couldn't open ${countOf(asking.paths)}"
		is Effect.Terminal -> "Couldn't open a terminal"
		is Effect.ReadClip -> "Couldn't read the clipboard"
		is Effect.ListPlace -> "Couldn't open “${asking.placed.title}”"
		is Effect.Run -> "“${asking.job.label}” failed"
		is Effect.Launch -> "Couldn't open ${asking.job.label}"
		is Effect.Scratch -> "“${asking.pending.action.label}” failed"
		is Effect.Temp -> "“${asking.carried.pending.action.label}” failed"
	}

/**
 * [read] shown: what was chosen kept where it is still there (nothing was, in a folder just gone to), and the cursor
 * landed where the state says it should. A folder picker's listing holds only folders.
 */
internal fun BrowserState.arrived(read: Listing.Read): BrowserState {
	val listing =
		when {
			read is Listing.Shown && choosing is Choosing.Folder ->
				Listing
					.Shown(
						read.entries.filter {
							it.isFolder
						},
					)
			else -> read
		}

	val shown = relisted(listing).copy(landing = null)

	return landing?.let(shown::landed) ?: shown
}

private fun BrowserState.looked(lookup: Lookup, at: (Path) -> Found): Step =
	when (lookup) {
		is Lookup.Going ->
			when (at(lookup.path)) {
				Found.Folder -> went(lookup.path)
				Found.File ->
					if (choosing is Choosing.Folder)
						notice(Notice.NotAFolder(lookup.text))
					else
						lookup.path.parent?.let {
							went(it, Landing.On(RowId(lookup.path.fileName.toString()), choose = true))
						}
							?: notice(Notice.NotFound(lookup.text))
				is Found.Nothing -> notice(Notice.NotFound(lookup.text))
			}
		is Lookup.Accepting -> accepting(lookup, lookup.paths.map { it to at(it) })
	}

private fun BrowserState.accepting(
	lookup: Lookup.Accepting,
	found: List<Pair<Path, Found>>,
): Step {
	val one = found.singleOrNull()
	val notFound = notice(Notice.NotFound(lookup.text.trim()))

	return when (choosing) {
		// Nothing is typed to be accepted while exploring: a lookup of one is too late to matter.
		is Choosing.Exploring -> Step(this)
		is Choosing.OneFile ->
			when (one?.second) {
				Found.Folder -> went(one.first)
				Found.File -> finished(listOf(one.first))
				else -> notFound
			}
		is Choosing.Files -> {
			val missing = found.firstOrNull { it.second is Found.Nothing }
			val files = found.filter { it.second == Found.File }

			when {
				missing != null ->
					notice(
						Notice.NotFound(missing.first.fileName?.toString() ?: lookup.text),
					)
				files.isNotEmpty() -> finished(files.map { it.first })
				one != null -> went(one.first)
				else -> Step(this)
			}
		}
		is Choosing.SaveAs ->
			when (val what = one?.second) {
				// A folder named is gone into, to save in, with the name to save under still to give.
				Found.Folder -> copy(chose = Choosing.SaveAs(name = "")).went(one.first)
				Found.File -> notice(Notice.Replace(one.first))
				is Found.Nothing ->
					if (what.inFolder)
						finished(listOf(one.first))
					else
						notice(Notice.NotFound(one.first.parent?.toString() ?: lookup.text))
				null -> notFound
			}
		is Choosing.Folder ->
			when (one?.second) {
				Found.Folder -> finished(listOf(one.first))
				Found.File -> notice(Notice.NotAFolder(lookup.text.trim()))
				else -> notFound
			}
	}
}

// ---- the search of the index ------------------------------------------------------------------------------------

/** The search as [change] makes it, where there is an index to search. */
private fun BrowserState.withSearch(change: Search.Ready.() -> Search): BrowserState =
	when (val search = search) {
		is Search.Ready -> copy(search = search.change())
		is Search.Unavailable -> this
	}

/**
 * The search's window opened with [text] in its field and searched for: nothing, for a search begun (and begun again,
 * where it was open already), since what was searched for last is seldom what is wanted next; what the results shown
 * were searched for, where those are to be changed. With no index, the window says why there is no search.
 */
private fun BrowserState.openedSearch(text: String): Step =
	when (val search = search) {
		// A place is searched by its own listing, whether or not there is an index of the disk.
		is Search.Unavailable ->
			if (inPlace?.tree != null)
				copy(search = Search.Ready(open = true, offers = offers).typed(text)).previewing()
			else
				Step(copy(search = search.copy(open = true)))
		is Search.Ready ->
			copy(
				search = Search.Ready(open = true, offers = offers).typed(text),
			).previewing()
	}

/** The search's text searched for, for the window's matches; nothing, for no text. */
private fun BrowserState.previewing(): Step {
	val search = search as? Search.Ready ?: return Step(this)

	// Nothing typed, or nothing yet that can be searched for (`ext:` alone), would find everything: nothing is found.
	if (!search.searchable)
		return Step(copy(search = search.copy(found = null)))

	return Step(
		this,
		listOf(
			Effect.Search(
				search.text,
				queryOf(search.read, choosing),
				Purpose.Preview,
				inPlace?.tree,
			),
		),
	)
}

private fun Search.Ready.closed(): Search =
	copy(open = false, highlighted = null, showing = false)

private fun BrowserState.searchTyped(text: String): Step =
	withSearch {
		typed(text)
	}.previewing()

/**
 * Which of prism's own things a search in this window can find: all of them, in an explorer; in a picker, which is
 * another application's window for a moment and has no preferences of its own to open, the system's page and how a
 * query is written.
 */
private val BrowserState.offers: List<Own>
	get() =
		when (role) {
			Role.Explorer -> Own.ALL
			Role.Picker -> listOf(Own.System, Own.Help)
		}

/** The arrows' match [by] down: from the field, the first; up from the first, back to the field. */
private fun Search.Ready.moved(by: Int): Search {
	if (listed == 0)
		return copy(highlighted = null)

	val next = (highlighted ?: -1) + by

	return copy(
		highlighted =
			if (next < 0)
				null
			else
				next.coerceAtMost(listed - 1),
	)
}

/**
 * One of prism's [own] chosen in the search's window: how a query is written, shown there in place of the field and
 * what it found; anything else done with the window put away, by whoever keeps this one's windows where it is another
 * window's to show.
 */
private fun BrowserState.chosenOwn(search: Search.Ready, own: Own): Step {
	val closed = copy(search = search.closed())

	return when (own) {
		Own.Help -> Step(copy(search = search.copy(help = true)))
		Own.Settings -> Step(closed, listOf(Effect.Ask(Request.Preferences)))
		Own.Bindings -> Step(closed, listOf(Effect.Ask(Request.KeyBindings)))
		Own.System -> step(closed, BrowserEvent.OpenedSystem)
	}
}

/**
 * The [at]th match the window lists gone to, the window put away: a folder, into it; a file, to the folder it is in,
 * chosen, so that saving saves over it once the user says so, and opening opens it once they do.
 */
private fun BrowserState.chosenMatch(at: Int): Step {
	val search = search as? Search.Ready ?: return Step(this)

	val (matches, rank) =
		when (val listed = search.listedAt(at)) {
			null -> return Step(this)
			is Search.Listed.Prisms -> return chosenOwn(search, listed.own)
			is Search.Listed.Match -> listed
		}

	val path = matches.pathOf(rank)
	val closed = copy(search = search.closed())

	// A match of a search in a place is gone to in the place: into a folder, or to the folder an entry sits in.
	matches.tree?.let { tree ->
		val sits = path.map { it.toString() }
		val row = tree.row(sits)?.id ?: return Step(closed)

		if (matches.isFolder(rank))
			return closed.wentWithin(sits)

		val gone = closed.wentWithin(sits.dropLast(1), Landing.On(row, choose = true))

		// Already in that folder, there is nowhere to go: the cursor is put on the entry where it is.
		return if (gone.state.at == closed.at)
			Step(closed.landed(Landing.On(row, choose = true)))
		else
			gone
	}

	return if (matches.isFolder(rank))
		closed.went(path)
	else
		path.parent?.let {
			closed.went(it, Landing.On(RowId(path.fileName.toString()), choose = true))
		}
			?: Step(closed)
}

/** Every match shown in the listing: at once where the search for what is typed is back, and once it is otherwise. */
private fun BrowserState.showingMatches(): Step {
	val search = search as? Search.Ready ?: return Step(this)

	if (!search.searchable)
		return Step(this)

	// What was found is shown only if it was found where the window is: in this place, or on the disk.
	search.matched?.takeIf { it.matches.tree === inPlace?.tree }?.let {
		return copy(search = search.closed()).showing(it)
	}

	// Refused for this text: nothing to show, and the window says why.
	if (search.found?.text == search.text)
		return Step(this)

	return Step(
		copy(search = search.copy(showing = true)),
		listOf(
			Effect.Search(
				search.text,
				queryOf(search.read, choosing),
				Purpose.Show,
				inPlace?.tree,
			),
		),
	)
}

/** [finding]'s results shown in the listing, in place of what is shown, which goes behind them in the history. */
private fun BrowserState.showing(finding: Finding.Matched): Step =
	shown(Location.Results(finding, inPlace), History(history.back + at, emptyList()), null)

/**
 * What a search came back with. For the window, kept where it is for what is typed now (a search overtaken by typing
 * is dropped), and shown in the listing where that was asked for meanwhile. For the results shown, searched again,
 * shown in their place, where they are still shown.
 */
private fun BrowserState.searched(finding: Finding, purpose: Purpose): Step {
	val search = search as? Search.Ready ?: return Step(this)

	return when (purpose) {
		Purpose.Preview, Purpose.Show -> {
			if (finding.text != search.text)
				return Step(this)

			// The arrows stay on the match they were on, where the new matches still list that many.
			val found =
				search.copy(found = finding).let {
					it.copy(highlighted = it.highlighted?.takeIf { at -> at < it.listed })
				}

			when {
				!search.showing -> Step(copy(search = found))
				finding is Finding.Matched -> copy(search = found.closed()).showing(finding)
				else -> Step(copy(search = found.copy(showing = false)))
			}
		}
		Purpose.Refresh -> {
			val shown = results

			// What was chosen was a result of the old search, and is none of the new one's ([RowId.Found]): it is not
			// among the rows shown, and is let go with them.
			if (shown == null || finding !is Finding.Matched || finding.text != shown.text)
				Step(this)
			else
				copy(at = Location.Results(finding, inPlace)).showingResults(finding)
		}
	}
}

// ---- how large it is drawn ----------------------------------------------------------------------------------------

/**
 * [steps] larger, or smaller where negative, as far as there are sizes. The list's sizes are the scale's, up to twice
 * the usual. The gallery's are the scale's that leave a tile smaller than the listing, then the whole listing, so a
 * size the window has grown too small for counts as the whole, and a step smaller from it is a step smaller than what
 * is shown.
 */
private fun BrowserState.zoomed(steps: Int): BrowserState =
	when (layout) {
		Layout.List ->
			copy(
				zooms =
					zooms.copy(
						list = Zoom.By((zooms.list.step + steps).coerceIn(0, Zoom.LIST_LARGEST.step)),
					),
			)
		Layout.Gallery -> {
			val sizes =
				Zoom.SCALE.indices
					.filter { Zoom.SCALE[it] < whole }
					.map { Zoom.By(it) } + Zoom.Whole
			val at = sizes.indexOf(zooms.gallery).takeIf { it >= 0 } ?: sizes.lastIndex

			copy(zooms = zooms.copy(gallery = sizes[(at + steps).coerceIn(0, sizes.lastIndex)]))
		}
	}

// ---- changing what is on disk -----------------------------------------------------------------------------------

/** [id]'s name made into a field to type over, the row chosen alone. */
private fun BrowserState.renaming(id: RowId): BrowserState {
	val row = rows[id] ?: return this

	return copy(
		naming = Renaming(id, row.name),
		chose = choosing.chose(setOf(id), rows),
		pointed = Cursor(id, id),
	)
}

/** The name typed given: refused here if it cannot be a name, left alone if it is the same, asked of the disk. */
private fun BrowserState.renamed(): Step {
	val naming = renaming ?: return Step(this)
	val row = rows[renaming.row] ?: return Step(copy(naming = null))
	val name = renaming.text.trim()
	val old = row.name
	val refusal = refusalOf(name)

	return when {
		name == old || name.isEmpty() -> Step(copy(naming = null))
		refusal != null -> notice(Notice.Failed("Couldn't rename “$old”", refusal))
		else ->
			copy(
				naming = null,
			).let { state ->
				row.path?.let { state.making(Change.Rename(it, it.resolveSibling(name))) }
					?: Step(state)
			}
	}
}

/** [change] asked of the disk, for [doing]. */
private fun BrowserState.making(change: Change, doing: Doing = Doing.New): Step =
	Step(this, listOf(Effect.Make(change, doing)))

/**
 * What came of a change. Whatever it was, the folder is read again, since a change half done is a change. Done, what
 * undoes it is kept: a change asked for anew, or redone, can then be undone (and a change anew leaves nothing to redo);
 * a change undone can be redone. The cursor lands on what the change made: a renamed entry, chosen; a folder just
 * made, its name to type over (or chosen, made again by a redo); entries put back from the trash, chosen. What failed
 * is said, with why.
 */
private fun BrowserState.changed(
	change: Change,
	result: ChangeResult,
	doing: Doing,
): Step =
	when (result) {
		is ChangeResult.Done ->
			copy(
				changes = changes.after(change, result.undo, doing),
			).reread(landingAfter(change, doing, result.undo))
		is ChangeResult.Failed ->
			reread().let {
				Step(
					it.state.copy(notice = Notice.Failed(failingOf(change, doing), result.why)),
					it.effects,
				)
			}
		// What had a trash went to it, and is put back by an undo; what had none is asked about, unless a move failed,
		// which is said instead: the rest was not tried, and Delete again asks of all of it.
		is ChangeResult.Trashed -> {
			val moved =
				if (result.undo.entries.isEmpty())
					this
				else
					copy(changes = changes.after(change, result.undo, doing))

			moved.reread().let {
				Step(
					it.state.copy(
						notice =
							when {
								result.failed != null -> Notice.Failed(failingOf(change, doing), result.failed)
								result.nowhere.isNotEmpty() ->
									Notice.ConfirmErase(
										Erasing.Files(result.nowhere.map { it.path }),
										result.nowhere
											.map { it.why }
											.distinct()
											.joinToString("; "),
									)
								else -> it.state.notice
							},
					),
					it.effects,
				)
			}
		}
	}

/** What [erasing] deletes, as what is said of it names it. */
fun erasedOf(erasing: Erasing): String =
	when (erasing) {
		is Erasing.Files -> countOf(erasing.paths)
		is Erasing.FromTrash -> countOf(erasing.entries.map { it.original })
		Erasing.Everything -> "everything in the trash"
	}

/** The changes after [change] is done for [doing]: what takes it back, [by], kept to undo, or, for an undo, to redo. */
private fun Changes.after(change: Change, by: Change, doing: Doing): Changes =
	when (doing) {
		Doing.New -> Changes(undo = undo.plusElement(Undoable(change, by)), redo = emptyList())
		is Doing.Redo -> copy(undo = undo.plusElement(Undoable(doing.done, by)))
		is Doing.Undo -> copy(redo = redo.plusElement(Undoable(doing.done, by)))
	}

/**
 * Where the cursor goes once [change] is done, if it made something in the folder shown; [undo] is what undoes it,
 * which says what a copy or a move made, since the disk chose their names.
 */
private fun BrowserState.landingAfter(
	change: Change,
	doing: Doing,
	undo: Change,
): Landing? {
	fun here(path: Path) = path.parent == folder

	fun rowOf(path: Path) = RowId(path.fileName.toString())

	val made =
		when (undo) {
			is Change.Trash -> undo.paths.takeIf { change is Change.Copy || change is Change.Link }
			is Change.Relocate -> undo.moves.map { it.from }
			else -> null
		}

	return when (change) {
		is Change.Copy, is Change.Link, is Change.Move, is Change.Relocate ->
			made?.firstOrNull(::here)?.let { Landing.On(rowOf(it), choose = true) }
		is Change.MakeFile ->
			change.path.takeIf(::here)?.let {
				if (doing == Doing.New)
					Landing.Renaming(rowOf(it))
				else
					Landing.On(rowOf(it), choose = true)
			}
		is Change.RemoveFile -> null
		is Change.Rename ->
			change.to
				.takeIf(
					::here,
				)?.let { Landing.On(rowOf(it), choose = true) }
		is Change.MakeFolder ->
			change.path.takeIf(::here)?.let {
				if (doing == Doing.New)
					Landing.Renaming(rowOf(it))
				else
					Landing.On(rowOf(it), choose = true)
			}
		is Change.PutBack ->
			change.originals.firstOrNull(::here)?.let { Landing.On(rowOf(it), choose = true) }
		is Change.Trash, is Change.RemoveFolder -> null
	}
}

/** What [change], made for [doing], did, as the notice of its failure says it. */
private fun failingOf(change: Change, doing: Doing): String =
	when (doing) {
		Doing.New ->
			when (change) {
				is Change.Rename -> "Couldn't rename “${change.from.fileName}”"
				is Change.Trash -> "Couldn't move ${countOf(change.paths)} to the trash"
				is Change.MakeFolder -> "Couldn't create the folder"
				is Change.PutBack -> "Couldn't restore ${countOf(change.originals)}"
				is Change.RemoveFolder -> "Couldn't remove “${change.path.fileName}”"
				is Change.MakeFile -> "Couldn't create the file"
				is Change.RemoveFile -> "Couldn't remove “${change.path.fileName}”"
				is Change.Copy -> "Couldn't copy ${countOf(change.sources)}"
				is Change.Link -> "Couldn't link to ${countOf(change.sources)}"
				is Change.Move -> "Couldn't move ${countOf(change.sources)}"
				is Change.Relocate -> "Couldn't move ${countOf(change.moves.map { it.from })}"
			}
		is Doing.Undo -> "Couldn't undo ${doneBy(doing.done)}"
		is Doing.Redo -> "Couldn't redo ${doneBy(doing.done)}"
	}

/** What the user did, [change], in words: what the undo and redo menu items, and a failed undo or redo, name. */
fun doneBy(change: Change): String =
	when (change) {
		is Change.Rename -> "rename"
		is Change.Trash -> "move to trash"
		is Change.MakeFolder -> "new folder"
		is Change.PutBack -> "restore"
		is Change.RemoveFolder -> "folder removal"
		is Change.MakeFile -> "new file"
		is Change.RemoveFile -> "file removal"
		is Change.Copy -> "copy"
		is Change.Link -> "link"
		is Change.Move, is Change.Relocate -> "move"
	}

/** "“a.txt”" for one path, "3 items" for several: how a change's paths are named in what is said of it. */
fun countOf(paths: List<Path>): String =
	paths.singleOrNull()?.let { "“${it.fileName}”" } ?: "${paths.size} items"

/** Why [name] cannot be a name of an entry, if it cannot. */
fun refusalOf(name: String): String? =
	when {
		'/' in name -> "A name can't contain “/”."
		'\u0000' in name -> "A name can't contain a NUL character."
		name == "." || name == ".." -> "“$name” can't be used as a name."
		name.toByteArray().size > 255 -> "The name is too long."
		else -> null
	}

/** The rows [ids] put on the clipboard as files, to be moved where they are pasted where [cut]. */
private fun BrowserState.clipping(ids: List<RowId>, cut: Boolean): Step {
	val chosen = rows.inOrder(ids.toSet())

	// Entries of a place are copied as what brings them out of it, where its place says what does: never cut.
	if (chosen.any { it.path == null })
		return carrying(chosen)?.let { Step(this, listOf(Effect.Carry(it))) } ?: Step(this)

	return chosen
		.mapNotNull { it.path }
		.takeIf { it.isNotEmpty() }
		?.let { Step(this, listOf(Effect.Clip(FileClip(it, cut)))) }
		?: Step(this)
}

/** The paths of [ids] that the listing shows, in the order they are shown. */
private fun BrowserState.pathsOf(ids: List<RowId>): List<Path> =
	rows.inOrder(ids.toSet()).mapNotNull { it.path }

/** [wanted], or, if the folder holds an entry of that name, the first of "[wanted] 2", "[wanted] 3", ... it does not. */
private fun BrowserState.freeName(wanted: String): String {
	val taken = (listing as? Listing.Shown)?.entries?.mapTo(HashSet()) { it.name }.orEmpty()

	return (
		sequenceOf(
			wanted,
		) + generateSequence(2) { it + 1 }.map { "$wanted $it" }
	).first {
		it !in
			taken
	}
}

// ---- what is chosen ---------------------------------------------------------------------------------------------

/**
 * [ids] chosen in place of what was: all of them where several can be, and otherwise the one (callers give one).
 * Opening, choosing a row puts the name field back to showing what is chosen; saving, the name to save under is
 * left as it is.
 */
internal fun Choosing.chose(ids: Set<RowId>, rows: Rows): Choosing {
	val one = ids.lastOrNull()

	return when (this) {
		is Choosing.OneFile -> Choosing.OneFile(one)
		is Choosing.Files -> Choosing.Files(ids)
		is Choosing.SaveAs -> copy(chosen = one)
		is Choosing.Folder -> Choosing.Folder(one)
		is Choosing.Exploring -> Choosing.Exploring(ids)
	}
}

/** [id] added to what is chosen, or taken away, where several can be; chosen alone otherwise. */
private fun Choosing.toggled(id: RowId, rows: Rows): Choosing =
	when (this) {
		is Choosing.Files ->
			Choosing.Files(
				if (id in chosen)
					chosen - id
				else
					chosen + id,
			)
		is Choosing.Exploring ->
			Choosing.Exploring(
				if (id in chosen)
					chosen - id
				else
					chosen + id,
			)
		else -> chose(setOf(id), rows)
	}

/** Nothing chosen; what was typed stays. */
internal fun Choosing.unchosen(): Choosing =
	when (this) {
		is Choosing.OneFile -> copy(chosen = null)
		is Choosing.Files -> copy(chosen = emptySet())
		is Choosing.SaveAs -> copy(chosen = null)
		is Choosing.Folder -> copy(chosen = null)
		is Choosing.Exploring -> copy(chosen = emptySet())
	}

/** [text] typed into the name field. */
private fun Choosing.named(text: String): Choosing =
	when (this) {
		is Choosing.OneFile -> copy(typed = text)
		is Choosing.Files -> copy(typed = text)
		is Choosing.SaveAs -> copy(name = text)
		is Choosing.Folder -> copy(typed = text)
		// Nothing is typed into a name field while exploring: there is none.
		is Choosing.Exploring -> this
	}

// ---- what is typed ------------------------------------------------------------------------------------------------

/** The names [text] gives: several in quotes (`"a.txt" "b.txt"`), as the name field writes them, or one as it is. */
fun namesIn(text: String): List<String> {
	val quoted =
		Regex("\"([^\"]*)\"")
			.findAll(text)
			.map { it.groupValues[1].trim() }
			.filter { it.isNotEmpty() }
			.toList()

	return quoted.ifEmpty { listOfNotNull(text.trim().takeIf { it.isNotEmpty() }) }
}

/**
 * [name] as a path: absolute as it is, from [home] after `~`, and in [folder] otherwise; null if it cannot be one, or
 * if it needs a folder and there is none.
 */
fun pathOf(name: String, folder: Path?, home: Path): Path? =
	try {
		when {
			name == "~" -> home
			name.startsWith("~/") -> home.resolve(name.drop(2))
			Path.of(name).isAbsolute -> Path.of(name)
			else -> folder?.resolve(name)
		}?.normalize()
	} catch (bad: InvalidPathException) {
		null
	}

// ---- when the folder's files were made ----------------------------------------------------------------------------

/** How many of the rows in sight have when they were made read at once: a window's worth, back soon. */
private const val BORN_IN_SIGHT = 256

/** How many of the rest at the least, once those in sight are read; for a large folder, an eighth of it at a time. */
private const val BORN_BEHIND = 2048

/**
 * When the folder's rows were made asked for, a batch at a time, where none is being read: those in sight that are not
 * read yet first, then the rest in the folder's order. Each answer asks for the next, so a scroll meanwhile is read
 * next, after the batch being read.
 */
internal fun BrowserState.bornNext(): Step {
	val listing = listing as? Listing.Shown ?: return Step(this)
	val folder = folder ?: return Step(this)

	if (bornReading.isNotEmpty())
		return Step(this)

	fun unread(row: Row) = row.created == Created.Unread && row.path != null

	val shown = rows
	val inSight =
		sight
			?.let { (it.first.coerceAtLeast(0)..it.last.coerceAtMost(shown.size - 1)) }
			?.map(shown::get)
			.orEmpty()
			.filter(::unread)
			.take(BORN_IN_SIGHT)

	val batch =
		inSight.ifEmpty {
			listing.entries
				.asSequence()
				.filter(::unread)
				.take(maxOf(BORN_BEHIND, listing.entries.size / 8))
				.toList()
		}

	if (batch.isEmpty())
		return Step(this)

	return Step(
		copy(
			bornReading =
				batch
					.map {
						it.id
					}.toSet(),
		),
		listOf(Effect.Born(folder, batch.map { it.name })),
	)
}

/** The folder's rows with when they were made, as [times] says of those it names. */
private fun BrowserState.born(times: Map<String, Created>): BrowserState {
	val listing = listing as? Listing.Shown ?: return copy(bornReading = emptySet())

	val entries =
		listing.entries.map { row ->
			times[row.name]?.let { row.copy(created = it) }
				?: row
		}

	return relisted(Listing.Shown(entries)).copy(
		bornReading =
			bornReading - times.keys.map(::RowId).toSet(),
	)
}

/** Quick access arranged as [quick], shown so at once, and kept for every window. */
private fun BrowserState.arranging(quick: QuickAccess): Step =
	if (quick == sidebar.quick)
		Step(this)
	else
		Step(copy(sidebar = sidebar.copy(quick = quick)), listOf(Effect.Arrange(quick)))

/** The user asked whether to delete [rows] for good: entries of the trash, in the trash; files where they are elsewhere. */
private fun BrowserState.askingToErase(rows: List<RowId>): Step {
	val erasing =
		if (inTrash)
			trashedOf(rows).takeIf { it.isNotEmpty() }?.let(Erasing::FromTrash)
		else
			pathsOf(rows).takeIf { it.isNotEmpty() }?.let(Erasing::Files)

	return erasing?.let { notice(Notice.ConfirmErase(it)) } ?: Step(this)
}

/** The entries of the trash [rows] are, of those shown. */
private fun BrowserState.trashedOf(rows: List<RowId>): List<Trashed> =
	rows.mapNotNull { (this.rows[it]?.item as? Item.InTrash)?.trashed }

/** This step, and then what [next] makes of the state it leaves: both steps' effects, in order. */
private fun Step.then(next: (BrowserState) -> Step): Step =
	next(state).let {
		Step(
			it.state,
			effects + it.effects,
		)
	}
