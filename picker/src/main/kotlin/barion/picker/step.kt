package barion.picker

import java.nio.file.InvalidPathException
import java.nio.file.Path

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
 * - Hidden entries are shown until the user hides them. The kinds of entry chosen above the listing narrow it to
 *   those kinds together (images and videos: both), and stay chosen from folder to folder; none chosen is everything.
 */

/** The picker opened on [folder]: its first state, and the read of its folder that it waits for. */
fun opened(
	choosing: Choosing,
	folder: Path,
	home: Path,
	sidebar: Sidebar,
	title: String = titleOf(choosing),
	filters: List<TypeFilter> = listOf(TypeFilter.ALL_FILES),
): Step {
	require(filters.isNotEmpty()) { "a picker offers at least one filter" }

	val shown = folder.toAbsolutePath().normalize()

	val state =
		PickerState(
			title = title,
			choosing = choosing.unchosen(),
			folder = shown,
			home = home.toAbsolutePath().normalize(),
			history = History(),
			sidebar = sidebar,
			listing = Listing.Reading(Landing.Top),
			arrangement = Arrangement(filter = filters.first()),
			filters = filters,
			cursor = null,
			address = Address.Crumbs,
			notice = null,
			waiting = null,
		)

	return Step(state, listOf(Effect.Read(shown)))
}

fun titleOf(choosing: Choosing): String =
	when (choosing) {
		is Choosing.OneFile -> "Open"
		is Choosing.Files -> "Open files"
		is Choosing.SaveAs -> "Save as"
		is Choosing.Folder -> "Select folder"
	}

/** [state] after [input]. */
fun step(state: PickerState, input: Input): Step =
	when (input) {
		is Answer -> answered(state, input)
		is PickerEvent ->
			when (val notice = state.notice) {
				null -> did(state, input)
				else -> noticed(state, notice, input)
			}
	}

// ---- what the user did ---------------------------------------------------------------------------------------

private fun did(state: PickerState, event: PickerEvent): Step =
	when (event) {
		is PickerEvent.Opened -> state.went(event.path)
		PickerEvent.Back -> state.back()
		PickerEvent.Forward -> state.forward()
		PickerEvent.Up -> state.folder.parent?.let { state.went(it) } ?: Step(state)
		PickerEvent.Refreshed -> Step(state, listOf(Effect.Read(state.folder)))
		is PickerEvent.Clicked -> Step(state.clicked(event.row, event.click))
		PickerEvent.ClickedNowhere -> Step(state.copy(choosing = state.choosing.unchosen()))
		is PickerEvent.Activated -> state.activated(event.row)
		is PickerEvent.Moved -> Step(state.moved(event.move, event.carry))
		PickerEvent.Toggled -> Step(state.toggled())
		PickerEvent.ChoseAll -> Step(state.choseAll())
		is PickerEvent.Jumped -> Step(state.jumped(event.prefix))
		is PickerEvent.Sorted -> state.arranged { copy(sort = sort.toggled(event.by)) }
		PickerEvent.ToggledHidden -> state.arranged { copy(showHidden = !showHidden) }
		is PickerEvent.ToggledKind ->
			state.arranged {
				copy(
					kinds =
						if (event.kind in kinds)
							kinds - event.kind
						else
							kinds + event.kind,
				)
			}
		PickerEvent.ShowedEveryKind -> state.arranged { copy(kinds = emptySet()) }
		is PickerEvent.Viewed -> Step(state.copy(layout = event.layout))
		is PickerEvent.Searched -> state.arranged { copy(query = event.query) }
		is PickerEvent.Filtered ->
			if (event.filter in state.filters)
				state.arranged { copy(filter = event.filter) }
			else
				Step(state)
		is PickerEvent.Named ->
			Step(
				state.copy(choosing = state.choosing.named(event.text, state.rows)),
			)
		PickerEvent.EditedAddress ->
			Step(
				state.copy(address = Address.Editing(state.folder.toString())),
			)
		is PickerEvent.AddressTyped ->
			Step(
				if (state.address is Address.Editing)
					state.copy(address = Address.Editing(event.text))
				else
					state,
			)
		PickerEvent.Went -> state.goingTo()
		PickerEvent.LeftAddress -> Step(state.copy(address = Address.Crumbs))
		PickerEvent.Accepted -> state.accepted()
		PickerEvent.Cancelled -> Step(state, listOf(Effect.Finish(Outcome.Cancelled)))
		// Answers to a notice, with none shown.
		PickerEvent.Confirmed, PickerEvent.Dismissed -> Step(state)
	}

/** While a notice is shown, it is all the picker answers to: the rest of it is behind the notice. */
private fun noticed(state: PickerState, notice: Notice, event: PickerEvent): Step =
	when (event) {
		PickerEvent.Confirmed ->
			when (notice) {
				is Notice.Replace -> state.finished(listOf(notice.path))
				is Notice.NotFound, is Notice.NotAFolder -> Step(state.copy(notice = null))
			}
		PickerEvent.Dismissed -> Step(state.copy(notice = null))
		PickerEvent.Cancelled -> Step(state, listOf(Effect.Finish(Outcome.Cancelled)))
		else -> Step(state)
	}

// ---- going places ----------------------------------------------------------------------------------------------

/**
 * [to] gone to: the folder left goes behind it in the history, and nothing is ahead. (A path is a list of its own
 * names, so a list of paths takes one with `plusElement`: `+` would add its names.) Going to the folder shown reads
 * it again, and lands at once where [landing] says.
 */
private fun PickerState.went(to: Path, landing: Landing = landingFrom(to)): Step {
	val target = to.toAbsolutePath().normalize()

	return if (target == folder)
		Step(copy(address = Address.Crumbs).landed(landing), listOf(Effect.Read(folder)))
	else
		shown(target, History(history.back.plusElement(folder), emptyList()), landing)
}

private fun PickerState.back(): Step =
	history.back.lastOrNull()?.let {
		shown(
			it,
			History(history.back.dropLast(1), listOf(folder) + history.forward),
			landingFrom(it),
		)
	} ?: Step(this)

private fun PickerState.forward(): Step =
	history.forward.firstOrNull()?.let {
		shown(
			it,
			History(history.back.plusElement(folder), history.forward.drop(1)),
			landingFrom(it),
		)
	} ?: Step(this)

/** Going from the folder shown to its parent lands on the folder left, as every file manager does. */
private fun PickerState.landingFrom(to: Path): Landing =
	folder.fileName
		?.takeIf { folder.parent == to }
		?.let { Landing.On(RowId(it.toString()), choose = false) }
		?: Landing.Top

/** [to] shown in place of the folder shown, waiting for its read: nothing chosen, nothing searched for. */
private fun PickerState.shown(to: Path, history: History, landing: Landing): Step =
	Step(
		copy(
			folder = to,
			history = history,
			listing = Listing.Reading(landing),
			arrangement = arrangement.copy(query = ""),
			choosing = choosing.unchosen(),
			cursor = null,
			address = Address.Crumbs,
			waiting = null,
		),
		listOf(Effect.Read(to)),
	)

/** The cursor where [landing] says, if that row is shown. */
private fun PickerState.landed(landing: Landing): PickerState =
	when (landing) {
		Landing.Top -> this
		is Landing.On ->
			if (landing.row in rows)
				copy(
					cursor = Cursor(landing.row, landing.row),
					choosing =
						if (landing.choose)
							choosing.chose(setOf(landing.row), rows)
						else
							choosing,
				)
			else
				this
	}

// ---- the listing ------------------------------------------------------------------------------------------------

/** The listing arranged anew, what was chosen kept where it is still shown. */
private fun PickerState.arranged(change: Arrangement.() -> Arrangement): Step {
	val arrangement = arrangement.change()
	val rows = rowsOf(listing, arrangement)

	return Step(
		copy(
			arrangement = arrangement,
			choosing = choosing.within(rows),
			cursor = cursor.within(rows),
		),
	)
}

private fun PickerState.clicked(row: RowId, click: Click): PickerState {
	if (row !in rows)
		return this

	val anchor = cursor?.anchor ?: row

	return when {
		!choosing.many || click == Click.Only ->
			copy(choosing = choosing.chose(setOf(row), rows), cursor = Cursor(row, row))
		click == Click.Toggle ->
			copy(
				choosing = choosing.toggled(row, rows),
				cursor = Cursor(row, row),
			)
		else ->
			copy(
				choosing = choosing.chose(rows.between(anchor, row), rows),
				cursor = Cursor(row, anchor),
			)
	}
}

private fun PickerState.moved(move: Move, carry: Carry): PickerState {
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
	val target = rows.list[to].id
	val anchor = cursor?.anchor ?: target

	return when {
		carry == Carry.Extend && choosing.many ->
			copy(
				choosing = choosing.chose(rows.between(anchor, target), rows),
				cursor = Cursor(target, anchor),
			)
		carry == Carry.Stay && choosing.many -> copy(cursor = Cursor(target, anchor))
		else ->
			copy(
				choosing = choosing.chose(setOf(target), rows),
				cursor = Cursor(target, target),
			)
	}
}

private fun PickerState.toggled(): PickerState =
	cursor?.let {
		copy(choosing = choosing.toggled(it.at, rows), cursor = Cursor(it.at, it.at))
	}
		?: moved(Move.First, Carry.Choose)

private fun PickerState.choseAll(): PickerState =
	if (choosing.many)
		copy(choosing = choosing.chose(rows.list.mapTo(LinkedHashSet()) { it.id }, rows))
	else
		this

/**
 * The cursor to the next row whose name starts with [prefix], from the cursor's own row (a second letter typed
 * narrows where it is) or, for one letter or the same letter again, from the row after it, so that pressing a
 * letter again goes through the rows it starts.
 */
private fun PickerState.jumped(prefix: String): PickerState {
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
			.map { rows.list[(start + it).mod(rows.size)] }
			.firstOrNull { it.name.startsWith(sought, ignoreCase = true) }
			?: return this

	return copy(
		choosing = choosing.chose(setOf(found.id), rows),
		cursor = Cursor(found.id, found.id),
	)
}

private fun PickerState.activated(id: RowId): Step {
	val row = rows[id] ?: return Step(this)

	return when {
		row.isFolder -> went(folder.resolve(row.name))
		choosing is Choosing.SaveAs ->
			copy(
				choosing = choosing.chose(setOf(id), rows),
			).accepted()
		else -> finished(listOf(folder.resolve(row.name)))
	}
}

// ---- accepting ---------------------------------------------------------------------------------------------------

private fun PickerState.accepted(): Step {
	if (waiting != null)
		return Step(this)

	val chosen = chosenRows
	val files = chosen.filterNot { it.isFolder }

	return when (choosing) {
		is Choosing.OneFile ->
			when {
				choosing.typed != null -> looking(choosing.typed)
				else -> chosen.singleOrNull()?.let { opening(it) } ?: Step(this)
			}
		is Choosing.Files ->
			when {
				choosing.typed != null -> looking(choosing.typed)
				files.isNotEmpty() -> finished(files.map { folder.resolve(it.name) })
				else -> chosen.singleOrNull()?.let { opening(it) } ?: Step(this)
			}
		is Choosing.SaveAs ->
			when (val one = chosen.singleOrNull()) {
				null -> looking(choosing.name)
				else ->
					if (one.isFolder)
						opening(one)
					else
						looking(choosing.name)
			}
		is Choosing.Folder ->
			when {
				!choosing.typed.isNullOrBlank() -> looking(choosing.typed)
				else ->
					finished(
						listOf(chosen.singleOrNull()?.let { folder.resolve(it.name) } ?: folder),
					)
			}
	}
}

private fun PickerState.opening(row: Row): Step =
	if (row.isFolder)
		went(folder.resolve(row.name))
	else
		finished(listOf(folder.resolve(row.name)))

/** What [text] names, looked up on disk before anything is done with it. */
private fun PickerState.looking(text: String): Step {
	val names = namesIn(text)

	if (names.isEmpty())
		return Step(this)

	val paths = names.mapNotNull { pathOf(it, folder, home) }

	if (paths.size < names.size)
		return Step(copy(notice = Notice.NotFound(text.trim())))

	val lookup = Lookup.Accepting(text, paths)

	return Step(copy(waiting = lookup), listOf(Effect.Look(lookup)))
}

private fun PickerState.goingTo(): Step {
	val text = (address as? Address.Editing)?.text?.trim() ?: return Step(this)

	if (text.isEmpty())
		return Step(copy(address = Address.Crumbs))

	val path =
		pathOf(text, folder, home) ?: return Step(copy(notice = Notice.NotFound(text)))
	val lookup = Lookup.Going(text, path)

	return Step(copy(waiting = lookup), listOf(Effect.Look(lookup)))
}

private fun PickerState.finished(paths: List<Path>): Step =
	Step(this, listOf(Effect.Finish(Outcome.Chosen(paths))))

private fun PickerState.notice(notice: Notice): Step = Step(copy(notice = notice))

// ---- what the disk said -------------------------------------------------------------------------------------------

private fun answered(state: PickerState, answer: Answer): Step =
	when (answer) {
		// A read of a folder no longer shown is too late to matter.
		is Answer.Arrived ->
			Step(
				if (answer.folder == state.folder)
					state.arrived(answer.read)
				else
					state,
			)
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
 * [read] shown: landed on as the folder was gone to, or, read again, with what was chosen kept where it is still
 * there. A folder picker's listing holds only folders.
 */
private fun PickerState.arrived(read: Listing.Read): PickerState {
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

	val rows = rowsOf(listing, arrangement)

	return when (val before = this.listing) {
		is Listing.Reading -> copy(listing = listing).landed(before.landing)
		is Listing.Read ->
			copy(
				listing = listing,
				choosing = choosing.within(rows),
				cursor = cursor.within(rows),
			)
	}
}

private fun PickerState.looked(lookup: Lookup, at: (Path) -> Found): Step =
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

private fun PickerState.accepting(
	lookup: Lookup.Accepting,
	found: List<Pair<Path, Found>>,
): Step {
	val one = found.singleOrNull()
	val notFound = notice(Notice.NotFound(lookup.text.trim()))

	return when (choosing) {
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
				Found.Folder -> copy(choosing = Choosing.SaveAs(name = "")).went(one.first)
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

// ---- what is chosen ---------------------------------------------------------------------------------------------

/**
 * [ids] chosen in place of what was: all of them where several can be, and otherwise the one (callers give one).
 * Choosing a row puts the name field back to showing what is chosen; saving, a file chosen gives its name.
 */
private fun Choosing.chose(ids: Set<RowId>, rows: Rows): Choosing {
	val one = ids.lastOrNull()

	return when (this) {
		is Choosing.OneFile -> Choosing.OneFile(one)
		is Choosing.Files -> Choosing.Files(ids)
		is Choosing.SaveAs ->
			Choosing.SaveAs(
				name = one?.let(rows::get)?.takeUnless { it.isFolder }?.name ?: name,
				chosen = one,
			)
		is Choosing.Folder -> Choosing.Folder(one)
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
		else -> chose(setOf(id), rows)
	}

/** Nothing chosen; what was typed stays. */
private fun Choosing.unchosen(): Choosing =
	when (this) {
		is Choosing.OneFile -> copy(chosen = null)
		is Choosing.Files -> copy(chosen = emptySet())
		is Choosing.SaveAs -> copy(chosen = null)
		is Choosing.Folder -> copy(chosen = null)
	}

/** What is chosen, kept where it is in [rows]. */
private fun Choosing.within(rows: Rows): Choosing =
	when (this) {
		is Choosing.OneFile -> copy(chosen = chosen?.takeIf { it in rows })
		is Choosing.Files -> copy(chosen = chosen.filterTo(LinkedHashSet()) { it in rows })
		is Choosing.SaveAs -> copy(chosen = chosen?.takeIf { it in rows })
		is Choosing.Folder -> copy(chosen = chosen?.takeIf { it in rows })
	}

/** [text] typed into the name field. Saving, a folder chosen is let go, so the button saves again. */
private fun Choosing.named(text: String, rows: Rows): Choosing =
	when (this) {
		is Choosing.OneFile -> copy(typed = text)
		is Choosing.Files -> copy(typed = text)
		is Choosing.SaveAs ->
			copy(
				name = text,
				chosen =
					chosen?.takeUnless {
						rows[it]?.isFolder ==
							true
					},
			)
		is Choosing.Folder -> copy(typed = text)
	}

private fun Cursor?.within(rows: Rows): Cursor? =
	this?.takeIf { it.at in rows }?.let {
		if (it.anchor in rows)
			it
		else
			Cursor(it.at, it.at)
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

/** [name] as a path: absolute as it is, from [home] after `~`, and in [folder] otherwise; null if it cannot be one. */
fun pathOf(name: String, folder: Path, home: Path): Path? =
	try {
		when {
			name == "~" -> home
			name.startsWith("~/") -> home.resolve(name.drop(2))
			else -> folder.resolve(name)
		}.normalize()
	} catch (bad: InvalidPathException) {
		null
	}
