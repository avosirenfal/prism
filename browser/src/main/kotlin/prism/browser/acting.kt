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
		is Target.Place -> Subject(On.Place, listOf(Thing.folder(target.place.path)), where)
	}

/** [pending] taken a step further: its next question asked, or its confirmation, or, with nothing left to ask, done. */
internal fun BrowserState.doing(pending: Pending): Step {
	val action = pending.action
	val before = pending.subject.filling(pending.answers)
	val given = action.given.mapValues { (_, phrase) -> phrase.fill(before) }

	if (given.values.any { it == null })
		return failed(
			action,
			"What it is given could not be worked out from what it was chosen for.",
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
			if (action.confirm != null && !pending.confirmed)
				Step(
					copy(pending = filled, notice = Notice.ConfirmAction(action.label, action.confirm)),
				)
			else
				copy(pending = null).done(filled, filling)
	}
}

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
					?: return failed(action, "Something its command names is not there to give it.")
			val folder = filling.folder ?: home
			// The values with names are the command's environment too, which is how a script reads them safely.
			val environment = filling.values + ("folder" to folder.toString())
			val invocations = runs.map { Invocation(it, folder, environment) }

			when (val how = deed.how) {
				How.Detached ->
					Step(
						this,
						listOf(Effect.Launch(Job(action.label, invocations), terminal = false)),
					)
				How.Terminal ->
					Step(
						this,
						listOf(Effect.Launch(Job(action.label, invocations), terminal = true)),
					)
				is How.Watched -> {
					val after =
						pending.after
							?: if (how.then.reread || how.then.choose != null)
								After.Reread(how.then.choose?.fill(filling))
							else
								After.None

					Step(
						copy(running = running + action.label),
						listOf(Effect.Run(Job(action.label, invocations, after))),
					)
				}
			}
		}
		is Deed.Show ->
			when (val what = deed.what) {
				is Showing.Folder -> {
					val path =
						what.path.fill(filling)?.let { pathOf(it, filling.folder, home) }
							?: return failed(action, "The folder to show could not be worked out.")

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
						return failed(action, "What the place is opened with could not be worked out.")

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
		copy(pending = null, notice = Notice.Failed("“${action.label}” couldn't be done", why)),
	)

/**
 * What [job]'s commands came to. It is no longer under way; where it failed, why is said; and what follows it is done:
 * what is shown read again (after a failure too, since a command half done has changed something), or what it brought
 * out opened.
 */
internal fun BrowserState.ran(job: Job, failure: String?): Step {
	val rested = copy(running = running - job.label)

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
	val place = place?.takeIf { it.placed == placed } ?: return Step(this)

	return Step(
		when (listing) {
			is PlaceListing.Listed ->
				copy(
					at = place.copy(tree = listing.tree),
				).arrived(Listing.Shown(listing.tree.rows(place.within)))
			is PlaceListing.Failed -> arrived(Listing.Unreadable(listing.why))
		},
	)
}

/** The folder of the place shown at [within] gone to, the folder left behind it in the history. */
internal fun BrowserState.wentWithin(
	within: List<String>,
	landing: Landing? = null,
): Step {
	val place = place ?: return Step(this)

	return if (within == place.within)
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
	val place = place ?: return Step(this)

	return when {
		row.isFolder -> wentWithin(place.within + row.name)
		choosing is Choosing.Exploring ->
			carrying(listOf(row))?.let { Step(this, listOf(Effect.Temp(it))) } ?: Step(this)
		else -> Step(this)
	}
}

/** A folder made for [carried]'s entries: they are brought out into it, and opened once they are. */
internal fun BrowserState.openingOut(folder: Path, carried: Carried): Step =
	doing(
		carried.into(folder).copy(
			after =
				After.Open(
					carried.pending.subject.things
						.mapNotNull {
							it.key
						}.map(folder::resolve),
				),
		),
	)

/**
 * [rows], entries of the place shown, as something carried out of it: its way out begun for them, where it has one
 * that is for them, with where to still to say.
 */
fun BrowserState.carrying(rows: List<Row>): Carried? {
	val place = place ?: return null
	val out = actions.outOf(place.placed) ?: return null
	val subject = Subject(On.Files, rows.map(Thing::of), where)

	return out.destination
		?.takeIf { rows.isNotEmpty() && out.fits(subject) }
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
