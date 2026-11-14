package prism.browser

import java.nio.file.Path

/*
 * The reducer's part in the user's own actions and in places (docs/menus.md): what choosing an action comes to, step by
 * step, and how a place is gone about. Like the rest of the reducer it runs nothing: a command to run is an effect, its
 * words already filled in here from what the action was chosen for, so what will be run is a value a test can read.
 *
 * An action chosen is done in this order: what it asks is asked, one question at a time (a line of text, in a notice
 * over the browser; a folder, in a picker the application opens); then, where it wants to be confirmed, it is; then it
 * is done. A question let go (Escape, the picker cancelled) lets go of the action, and nothing is run.
 *
 * A question that asks for a folder has other ways of being answered than asking: entries dragged out of a place and
 * dropped on a folder, or copied and pasted into one, are the place's way out begun, with that folder the answer
 * ([Carried]). So "Extract to…", a drag out of an archive, and a paste of what was copied in one, are one command.
 */

/** What [target] is, as an action is offered for it and done to it. */
fun BrowserState.subjectOf(target: Target): Subject =
	when (target) {
		is Target.Rows -> Subject(On.Files, target.rows.map(Thing::of), where)
		// The folder shown is what its own menu is for; a place, or a search's results, is no folder to name.
		Target.Folder -> Subject(On.Folder, listOfNotNull(folder?.let(Thing::folder)), where)
		// The trash is no one folder: an action is given nothing of it.
		is Target.Sidebar ->
			Subject(
				On.Place,
				listOfNotNull(target.item.path?.let(Thing::folder)),
				where,
			)
	}

/** [pending] taken a step further: its next question asked, or its confirmation, or, with nothing left to ask, done. */
internal fun BrowserState.doing(pending: Pending): Step {
	val action = pending.action
	val before = pending.subject.filling(pending.answers)
	val given = action.given.mapValues { (_, phrase) -> phrase.fill(before) }

	if (given.values.any { it == null })
		return failed(
			action,
			"Some of its values couldn't be filled in from the selection.",
		)

	val answers = given.mapValues { it.value!! } + pending.answers
	val filled = pending.copy(answers = answers)
	val filling = pending.subject.filling(answers)

	return when (val next = action.questions.firstOrNull { it.name !in answers }) {
		is Question.Text ->
			Step(
				copy(
					pending = filled,
					notice = Notice.Asking(next.prompt, next.start?.fill(filling).orEmpty()),
				),
			)
		is Question.Folder ->
			Step(
				copy(pending = filled),
				listOf(
					Effect.Ask(
						Request.PickFolder(
							next.prompt,
							next.start?.fill(filling)?.let { pathOf(it, filling.folder, home) }
								?: filling.folder
								?: home,
						),
					),
				),
			)
		null ->
			when {
				action.confirm != null && !pending.confirmed ->
					Step(
						copy(pending = filled, notice = Notice.ConfirmAction(action.label, action.confirm)),
					)
				// A command that works in a folder of its own is given one first.
				action.scratch && TEMP !in answers ->
					Step(copy(pending = null), listOf(Effect.Scratch(filled)))
				else -> copy(pending = null).done(filled, filling)
			}
	}
}

/** A folder made for [pending]'s command to work in: it is done, with that its `{temp}`. */
internal fun BrowserState.scratched(folder: Path, pending: Pending): Step =
	doing(pending.copy(answers = pending.answers + (TEMP to folder.toString())))

/** The line an action asked for, typed and given: its answer. */
internal fun BrowserState.typed(text: String): Step {
	val pending = pending ?: return Step(this)
	val asked =
		pending.action.questions
			.filterIsInstance<Question.Text>()
			.firstOrNull { it.name !in pending.answers && it.name !in pending.action.given }
			?: return Step(copy(pending = null))

	return doing(pending.copy(answers = pending.answers + (asked.name to text)))
}

/** The folder an action asked for, chosen in a picker; or the picker cancelled, and the action let go. */
internal fun BrowserState.answered(folder: Path?): Step {
	val pending = pending ?: return Step(this)
	val asked =
		pending.action.questions
			.firstOrNull { it.name !in pending.answers && it.name !in pending.action.given }
			as? Question.Folder

	return when {
		asked == null -> Step(this)
		folder == null -> Step(copy(pending = null))
		else ->
			doing(
				pending.copy(answers = pending.answers + (asked.name to folder.toString())),
			)
	}
}

/** [pending]'s action done, with everything it asks answered in [filling]. */
private fun BrowserState.done(pending: Pending, filling: Filling): Step {
	val action = pending.action

	return when (val deed = action.deed) {
		is Deed.Run -> {
			val runs =
				deed.command.fill(filling)
					?: return failed(action, "Its command uses a value that isn't available here.")
			val folder = filling.folder ?: home
			// The values with names are the command's environment too, which is how a script reads them safely.
			val environment = filling.values + ("folder" to folder.toString())
			val invocations = runs.map { Invocation(it, folder, environment) }
			val scratch = filling.values[TEMP]?.let { Path.of(it) }

			when (val how = deed.how) {
				How.Detached ->
					Step(
						this,
						listOf(
							Effect.Launch(Job(action.label, invocations, scratch = scratch), terminal = false),
						),
					)
				How.Terminal ->
					if (!terminal.runsCommands)
						failed(
							action,
							"The terminal set in Preferences can't run commands: its command line has no " +
								"{command}.",
						)
					else
						Step(
							this,
							listOf(
								Effect.Launch(Job(action.label, invocations, scratch = scratch), terminal = true),
							),
						)
				is How.Watched -> {
					val after =
						pending.after
							?: if (how.then.reread || how.then.choose != null)
								After.Reread(how.then.choose?.fill(filling))
							else
								After.None

					// What the command makes is brought to where it is wanted: the entries it was for, each by its own
					// name, whatever folders they sit under in the place; or, where it names none, all it made.
					val bring =
						how.then.bring?.let { bring ->
							val from = bring.from.fill(filling)?.let { pathOf(it, folder, home) }
							val into = bring.to.fill(filling)?.let { pathOf(it, folder, home) }

							if (from == null || into == null)
								return failed(
									action,
									"The source or destination of its output couldn't be determined.",
								)

							Delivery(
								from,
								into,
								filling.things
									.mapNotNull { thing -> thing.key?.let { it to thing.name } }
									.takeIf { it.isNotEmpty() },
							)
						}

					val job = Job(action.label, invocations, after, scratch, bring)

					Step(copy(running = running + Work(Task.Running(job))), listOf(Effect.Run(job)))
				}
			}
		}
		is Deed.Show ->
			when (val what = deed.what) {
				is Showing.Folder -> {
					val path =
						what.path.fill(filling)?.let { pathOf(it, filling.folder, home) }
							?: return failed(action, "The folder to open couldn't be determined.")

					if (deed.window == true && choosing is Choosing.Exploring)
						Step(this, listOf(Effect.Ask(Request.NewWindow(Start.Folder(path)))))
					else
						went(
							path,
							what.choose?.fill(filling)?.let { Landing.On(RowId(it), choose = true) },
						)
				}
				is Showing.Place -> {
					val type =
						actions.places[what.place]
							?: return failed(action, "There is no place called “${what.place}”.")
					val values = what.with.mapValues { (_, phrase) -> phrase.fill(filling) }

					if (values.values.any { it == null })
						return failed(action, "The values needed to open the place couldn't be filled in.")

					val placed = Placed(type, values.mapValues { it.value!! })

					if ((deed.window ?: type.window) && choosing is Choosing.Exploring)
						Step(this, listOf(Effect.Ask(Request.NewWindow(Start.Place(placed)))))
					else
						shown(Location.Place(placed), History(history.back + at, emptyList()), null)
				}
			}
	}
}

private fun BrowserState.failed(action: UserAction, why: String): Step =
	Step(
		copy(pending = null, notice = Notice.Failed("“${action.label}” failed", why)),
	)

/**
 * What [job]'s commands came to. It is no longer under way; where it failed, why is said; and what follows it is done:
 * what is shown read again (after a failure too, since a command half done has changed something), or what it brought
 * out opened.
 */
internal fun BrowserState.ran(job: Job, failure: String?): Step {
	val work = running.firstOrNull { it.task == Task.Running(job) }
	val rested = copy(running = running.filterNot { it === work })
	// A command the user stopped did not fail: they know why it ended.
	val failure = failure.takeUnless { work?.stopping == true }

	val followed =
		when (val after = job.after) {
			After.None -> Step(rested)
			is After.Reread ->
				rested.reread(
					after.choose
						?.takeIf {
							failure == null
						}?.let { Landing.On(rested.rowNamed(it), choose = true) },
				)
			is After.Open ->
				if (failure == null)
					Step(rested, listOf(Effect.Open(after.paths)))
				else
					Step(rested)
		}

	return if (failure == null)
		followed
	else
		Step(
			followed.state.copy(notice = Notice.Failed("“${job.label}” failed", failure)),
			followed.effects,
		)
}

/** The row called [name], as the place shown knows its rows: a folder's by name, a place's by where they sit. */
private fun BrowserState.rowNamed(name: String): RowId =
	when (val place = place) {
		null -> RowId(name)
		else -> RowId.Entry((place.within + name).joinToString("/"))
	}

// ---- going about a place -------------------------------------------------------------------------------------------

/** What listing [placed] gave: shown, where that place is still what is shown. */
internal fun BrowserState.listed(placed: Placed, listing: PlaceListing): Step {
	val rested = copy(running = running.filterNot { it.task == Task.Listing(placed) })
	val place = rested.place?.takeIf { it.placed == placed } ?: return Step(rested)

	return Step(
		when (listing) {
			is PlaceListing.Listed ->
				rested
					.copy(
						at = place.copy(tree = listing.tree),
					).arrived(Listing.Shown(listing.tree.rows(place.within)))
			is PlaceListing.Failed -> rested.arrived(Listing.Unreadable(listing.why))
		},
	)
}

/** [placed] asked to be listed: shown as under way until its listing arrives, unless it already is. */
internal fun BrowserState.listing(placed: Placed): Step =
	Step(
		if (running.any { it.task == Task.Listing(placed) })
			this
		else
			copy(running = running + Work(Task.Listing(placed))),
		listOf(Effect.ListPlace(placed)),
	)

/** [task], under way, stopped by the user: its command is ended, and its ending is then not a failure to tell of. */
internal fun BrowserState.stopping(task: Task): Step =
	if (running.none { it.task == task })
		Step(this)
	else
		Step(
			copy(
				running =
					running.map {
						if (it.task == task)
							it.copy(stopping = true)
						else
							it
					},
			),
			listOf(Effect.Stop(task)),
		)

/** A line [task] wrote, kept with what it has written, for whoever waits on it to read. */
internal fun BrowserState.said(task: Task, line: String): Step =
	Step(copy(running = running.map { if (it.task == task) it.saying(line) else it }))

/** The folder of the place shown at [within] gone to, the folder left behind it in the history. */
internal fun BrowserState.wentWithin(
	within: List<String>,
	landing: Landing? = null,
): Step {
	val place = inPlace ?: return Step(this)

	// From a search's results in the place, any folder of it is somewhere else to go.
	return if (this.place != null && within == place.within)
		Step(this)
	else
		shown(place.copy(within = within), History(history.back + at, emptyList()), landing)
}

/** Up, where no folder on disk is shown: in a place, the folder above, the cursor on the one left. */
internal fun BrowserState.upWithin(): Step {
	val within = place?.within?.takeIf { it.isNotEmpty() } ?: return Step(this)

	return wentWithin(
		within.dropLast(1),
		Landing.On(RowId.Entry(within.joinToString("/", postfix = "/")), choose = false),
	)
}

/**
 * [row], an entry of the place shown, opened: a folder gone into; a file brought out into a folder made for it, by the
 * place's way out, and opened from there, as an archive's files are looked at without extracting the archive.
 */
internal fun BrowserState.activatedInPlace(row: Row): Step {
	val place = inPlace ?: return Step(this)
	// Where the folder sits is the place's to say: among a search's results, it is not under what is shown.
	val sits = (row.item as? Item.InPlace)?.let { place.tree?.at(it.key) }

	return when {
		row.isFolder -> wentWithin(sits ?: (place.within + row.name))
		choosing is Choosing.Exploring ->
			carrying(listOf(row))?.let { Step(this, listOf(Effect.Temp(it))) } ?: Step(this)
		else -> Step(this)
	}
}

/**
 * A folder made for [carried]'s entries: they are brought out into it, and opened once they are. Brought out, an entry
 * is in the folder by its own name: the folders it was under in the place are left behind.
 */
internal fun BrowserState.openingOut(folder: Path, carried: Carried): Step =
	doing(
		carried.into(folder).copy(
			after =
				After.Open(
					carried.pending.subject.things
						.map { folder.resolve(it.name) },
				),
		),
	)

/**
 * [rows], entries of the place shown, as something carried out of it: its way out begun for them, where it has one
 * that is for them, with where to still to say.
 */
fun BrowserState.carrying(rows: List<Row>): Carried? {
	val place = inPlace ?: return null
	val out = actions.outOf(place.placed) ?: return null
	val subject = Subject(On.Files, rows.map(Thing::of), where)

	return out.destination
		?.takeIf { rows.isNotEmpty() && out.fits(subject) }
		?.let { Carried(Pending(out, subject), it.name) }
}

/**
 * [entries], as a drag described them, as something carried out of their place: that place's way out, as this window's
 * actions have it, begun for them. Null where this window knows no such place, or it has no way out for them.
 */
fun BrowserState.carrying(entries: EntriesOut): Carried? {
	val placed = Placed(actions.places[entries.place] ?: return null, entries.values)
	val out = actions.outOf(placed) ?: return null

	val subject =
		Subject(
			On.Files,
			entries.entries.map {
				Thing(
					Item.InPlace(it.key),
					it.name,
					if (it.folder)
						ThingKind.Folder
					else
						ThingKind.File,
				)
			},
			Where.Place(placed, entries.within),
		)

	return out.destination
		?.takeIf { subject.things.isNotEmpty() && out.fits(subject) }
		?.let { Carried(Pending(out, subject), it.name) }
}

/** Entries carried out of a place dropped on [spot]: brought out into the folder there, if it is one on disk. */
internal fun BrowserState.droppedCarried(carried: Carried, spot: DropSpot): Step {
	val into =
		when (spot) {
			is DropSpot.Into -> spot.folder
			DropSpot.Here -> folder
		}

	return if (into == null || choosing !is Choosing.Exploring)
		Step(this)
	else
		doing(carried.into(into))
}

/** A paste where no folder is shown: asked for only in a place that says what taking files in does. */
internal fun BrowserState.pastingIn(): Step =
	if (place?.let { actions.into(it.placed) } != null)
		Step(this, listOf(Effect.ReadClip(null)))
	else
		Step(this)

/**
 * What was copied, pasted [into] a folder (or, where null, into the place shown). Entries of a place copied last are
 * brought out into the folder; files are copied or moved there, as ever. A place takes files only, by its way in.
 */
internal fun BrowserState.pasted(into: Path?, clip: FileClip?, carried: Carried?): Step =
	when {
		into == null -> clip?.let { givingIn(it.paths) } ?: Step(this)
		carried != null -> doing(carried.into(into))
		clip != null ->
			transferring(
				clip.paths,
				into,
				if (clip.cut) Transfer.Move else Transfer.Copy,
			)
		else -> Step(this)
	}

/** Files given to the place shown, dropped on it or pasted: its way in done for them, where it has one. */
internal fun BrowserState.givingIn(paths: List<Path>): Step {
	val place = place ?: return Step(this)
	val into = actions.into(place.placed) ?: return Step(this)
	val subject = Subject(On.Files, paths.map(Thing::file), where)

	return if (paths.isEmpty() || !into.fits(subject))
		Step(this)
	else
		doing(Pending(into, subject))
}
