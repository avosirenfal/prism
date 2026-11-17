package prism.browser

import java.nio.file.Path
import prism.index.query.QueryText

/*
 * The reducer's part in text searches (docs/text.md): what opening one, typing into it, adding a step to it, moving
 * about what it found and opening a line come to. Like the rest of the reducer it runs nothing: a command to run is
 * an effect ([Effect.FindText]), and so are the files a query of names finds ([Effect.FilterText]) and the file to
 * show beside the list ([Effect.Preview]).
 *
 * A text search is steps (pipeline.kt), and they are run one at a time, in order, each given what the one before it
 * gave. What changes a step (a letter typed for it, an option turned) leaves the steps before it as they are, and it
 * and every step after it to be run again: the step under way, if it is one of those, is stopped, and what a run that
 * was overtaken found, arriving late, is of no run the window knows, and is dropped. What the last step gave stays
 * shown until it gives something again, so that the list is not emptied at every letter.
 */

/** What [target] holds to look in; everything the window shows, where it is null. */
internal fun BrowserState.textScope(target: Target?): TextScope {
	val base = folder ?: home

	fun of(rows: List<Row>): TextScope =
		TextScope(
			rows.singleOrNull()?.name ?: "${rows.size} selected items",
			base,
			rows.mapNotNull { it.path },
		)

	return when (target) {
		is Target.Rows -> of(target.rows)
		is Target.Sidebar ->
			TextScope(target.item.label, target.item.path ?: base, listOfNotNull(target.item.path))
		Target.Folder, null ->
			when (val at = at) {
				// A search begun from what another found looks where that one looked.
				is Location.Text -> at.scope
				// A place's folders are its own groupings, which are nowhere on disk: its files are what is looked in,
				// those under the folder of it shown.
				is Location.Place ->
					TextScope(
						placeName,
						base,
						at.tree
							?.entries
							.orEmpty()
							.filter { !it.folder && it.at.take(at.within.size) == at.within }
							.mapNotNull { it.path },
					)
				else -> rows.let { shown -> TextScope(placeName, base, shown.size, shown::pathAt) }
			}
	}
}

/**
 * Whether [target] holds anything on disk for a text search to look in: not the entries of an archive, which are
 * nowhere on disk, nor a place's own groupings of them, nor a folder that shows nothing.
 */
fun BrowserState.searchable(target: Target?): Boolean = textScope(target).paths.any()

/**
 * [search] opened on [target]: shown in place of what is, which goes behind it in the history. Opened on everything a
 * text search shows, it is a step more of that search, looking in what its last step gave. Nothing, where there is
 * nothing on disk to look in, or it is a search of lines, which only follows something that found some.
 */
internal fun BrowserState.searchingText(search: TextSearch, target: Target?): Step =
	when {
		text != null && target == null -> textStepAdded(search)
		search.takes != Takes.Files -> Step(this)
		else ->
			textScope(target).let { scope ->
				if (scope.paths.none())
					Step(this)
				else
					shown(Location.Text(search, scope), History(history.back + at, emptyList()), null)
			}
	}

/**
 * [text] shown, the window just come to it: its steps run on from the first that has not been, which is all of them
 * where it is new, the one that was under way where it was left while it ran, and none where they had all ended.
 */
internal fun BrowserState.textShown(text: Location.Text): Step =
	texted(advanced(text.copy(under = null)))

/** The text search shown, every step of it run again: what it shows stays until the last gives something. */
internal fun BrowserState.textReran(): Step = texting { it.from(0) }

/** This place, with [changed] of its text search, and what that asks for: its cursor on a row it shows, and that row's file. */
private fun BrowserState.texted(changed: Pair<Location.Text, List<Effect>>): Step {
	val (pointed, asks) = changed.first.pointing()

	return Step(copy(at = pointed), changed.second + asks)
}

/** The text search shown changed by [change], and its steps run on from the first that is then to be run. */
private fun BrowserState.texting(
	change: (Location.Text) -> Pair<Location.Text, List<Effect>>,
): Step =
	text?.let {
		val (changed, stops) = change(it)
		val (advanced, starts) = advanced(changed)

		// A run started takes the place of the one under way, which need not be stopped besides.
		texted(
			advanced to
				(
					stops
						.takeIf {
							starts.none { start ->
								start is Effect.FindText
							}
						}.orEmpty() + starts
				),
		)
	} ?: Step(this)

/**
 * This search with every step from [index] on to be run again. The step under way, where it is one of those, is
 * stopped; one before them runs on.
 */
private fun Location.Text.from(index: Int): Pair<Location.Text, List<Effect>> {
	val overtaken = under?.takeIf { it.index >= index }

	return copy(
		stages =
			stages.mapIndexed { at, stage ->
				if (at >=
					index
				)
					stage.copy(done = null)
				else
					stage
			},
		under = under.takeIf { overtaken == null },
	) to listOfNotNull(Effect.FindText(null).takeIf { overtaken?.run != null })
}

/** What the step at [index] is given: what the one before it gave; nothing, for the first, which is given the files shown. */
private fun Location.Text.inputOf(index: Int): TextFlow? =
	stages.getOrNull(index - 1)?.done?.out

/** This search, the step at [index] having come to [done]: where it is the last, what it gave is what is shown. */
private fun Location.Text.done(index: Int, done: StepDone): Location.Text =
	copy(
		stages =
			stages.mapIndexed { at, stage ->
				if (at ==
					index
				)
					stage.copy(done = done)
				else
					stage
			},
		under = under.takeIf { it?.index != index },
	).let {
		if (index == stages.size - 1)
			it.showing(done.out)
		else
			it
	}

/** This search showing [flow] in its list. */
private fun Location.Text.showing(flow: TextFlow): Location.Text =
	if (flow.hits === shown.hits)
		this
	else
		copy(shown = flow)

/**
 * [text], its steps run on: the first that has not run since something before it changed is started, unless one is
 * under way already, or one before it failed, after which nothing is run. A step with nothing to look for gives what
 * it was given, at once, and the one after it is come to.
 */
private tailrec fun BrowserState.advanced(
	text: Location.Text,
	asked: List<Effect> = emptyList(),
): Pair<Location.Text, List<Effect>> {
	val index = text.stages.indexOfFirst { it.done == null }

	if (text.failed != null)
		return text.copy(shown = TextFlow.NONE) to asked
	// Every step has run: what the last gave is what is shown, whichever was the last to be changed.
	if (index < 0)
		return text.showing(
			text.stages
				.last()
				.done
				?.out ?: text.shown,
		) to asked
	if (text.under != null)
		return text to asked

	val input = text.inputOf(index)
	val step = text.stages[index].step

	// What is waited for, where the step has something to look for; none, where it gives what it was given.
	val awaited: Pair<Awaited, Effect>? =
		when (step) {
			is TextStep.Search ->
				if (step.idle)
					null
				else
					TextRun(
						step.search,
						input?.let { scopeOf(it, text.scope.said, text.scope.folder) } ?: text.scope,
						step.sought,
						step.on,
						input?.hits?.lines?.filter { it.hit != null }?.toList().takeIf {
							step.search.takes == Takes.Lines
						},
						step.without,
					).let { Awaited.Run(it) to Effect.FindText(it) }
			is TextStep.Names ->
				input?.takeIf { step.text.isNotBlank() }?.let {
					FilterAsk(step.text, QueryText.read(step.text, aliases).query, it.hits, home)
						.let { ask -> Awaited.Names(ask) to Effect.FilterText(ask) }
				}
		}

	return if (awaited == null)
		advanced(text.done(index, passed(input)), asked)
	else
		text.copy(under = Under(index, awaited.first)) to asked + awaited.second
}

/**
 * This search with its cursor on a row its list shows (the first line found, where it was on none that is), and the
 * file that row is in asked to be shown, where it is not the one shown or asked for already.
 */
private fun Location.Text.pointing(): Pair<Location.Text, List<Effect>> {
	val rows = rows
	val cursor =
		cursor?.takeIf { it in rows } ?: rows.firstLine
			?: rows.paths.firstOrNull()?.let(TextRowId::File)
	val pointed = copy(cursor = cursor)
	val ask = cursor?.let(pointed::previewOf)

	return when {
		// Nothing to show a file of: what was shown is of a list that is gone.
		ask == null -> pointed.copy(previewed = null, asked = null) to emptyList()
		asked?.let(ask::same) == true -> pointed to emptyList()
		previewed?.let { ask.same(it.ask) && it.suffices(ask) } == true ->
			pointed.copy(asked = null) to emptyList()
		else -> pointed.copy(asked = ask) to listOf(Effect.Preview(ask))
	}
}

/** Whether what is shown is enough for [ask], of the same file: it has the line wanted, or could never have. */
private fun Previewed.suffices(ask: PreviewAsk): Boolean {
	val shown = preview as? Preview.Shown ?: return true

	// A command's output is all there is of it; a file prism reads is read again about a line it does not show.
	return ask.words != null || ask.around == null || shown.whole || shown.covers(ask.around)
}

/** What shows the file [id] is in, or is: the search's command for it, or prism reading it. */
private fun Location.Text.previewOf(id: TextRowId): PreviewAsk? {
	val values = valuesOf(id)
	val folder = id.path.parent ?: scope.folder
	val position =
		(rows[id] as? TextRow.Line)?.hit?.position.takeIf {
			search.positions == Positions.Lines
		}

	return when (val command = search.preview) {
		null -> PreviewAsk(id.path, null, folder, position)
		else ->
			command
				.fill(Filling(listOf(Thing.file(id.path)), folder, values))
				?.singleOrNull()
				?.let { PreviewAsk(id.path, it, folder, position) }
	}
}

/**
 * What is typed for the step typed for: looked for, by that step and those after it; or, of a search whose command is
 * run once, what it found narrowed by it, and only the steps after it run again.
 */
internal fun BrowserState.textTyped(typed: String): Step =
	texting {
		val stage = it.stages[it.at]
		val step = stage.step.typing(typed)

		val stepped =
			it.copy(
				stages =
					it.stages.mapIndexed { at, each ->
						if (at ==
							it.at
						)
							each.copy(step = step)
						else
							each
					},
			)

		val old = stage.step

		when {
			step == old -> it to emptyList()
			old is TextStep.Search && step is TextStep.Search &&
				!old.reruns(
					step,
				)
			-> stepped.remade(it.at)
			else -> stepped.from(it.at)
		}
	}

/**
 * This search, what the step at [index] found made again into what it gives, as it is now typed for: its command is
 * not run again. The steps after it are, since what they are given has changed.
 */
private fun Location.Text.remade(index: Int): Pair<Location.Text, List<Effect>> {
	val step = stages[index].step as TextStep.Search
	val done = stages[index].done
	val found = under?.takeIf { it.index == index }?.found

	return when {
		// Run and ended: what it found, given anew.
		done != null ->
			from(index + 1).let { (stale, stops) ->
				stale.done(index, done.copy(out = given(step, inputOf(index), done.raw))) to stops
			}
		// Under way, and the last: what it has found so far, shown as it is now narrowed.
		found != null && index == stages.size - 1 ->
			showing(given(step, inputOf(index), found)) to emptyList()
		else -> this to emptyList()
	}
}

/** An option of the step typed for turned the other way: that step and those after it are run again. */
internal fun BrowserState.textOptioned(name: String): Step =
	texting {
		val step = it.step as? TextStep.Search

		if (step == null || step.search.options.none { option -> option.name == name })
			it to emptyList()
		else
			it.stepped(
				step.copy(
					on =
						if (name in step.on)
							step.on - name
						else
							step.on + name,
				),
			)
	}

/** This search with the step typed for [step] instead, and it and those after it to be run again. */
private fun Location.Text.stepped(step: TextStep): Pair<Location.Text, List<Effect>> =
	copy(
		stages =
			stages.mapIndexed { index, each ->
				if (index ==
					at
				)
					each.copy(step = step)
				else
					each
			},
	).from(at)

/**
 * The step typed for turned about, or back: giving what it was given and found nothing in. Not the first step, which
 * is given what the window showed, of which only its command knows the files.
 */
internal fun BrowserState.textWithout(): Step =
	texting {
		val step = it.step as? TextStep.Search

		if (step == null || it.at == 0)
			it to emptyList()
		else
			it.stepped(step.copy(without = !step.without))
	}

/**
 * A step added after the last, and made the one typed for: [search], or a query of the files' names where it is null.
 * Where the last step is that already, with nothing typed for it, it is the one typed for, and none is added. A
 * search of lines is added only after lines.
 */
internal fun BrowserState.textStepAdded(search: TextSearch?): Step =
	texting {
		val step = search?.let { found -> TextStep.Search(found) } ?: TextStep.Names()
		val last = it.stages.last().step

		when {
			last == step -> it.copy(at = it.stages.size - 1) to emptyList()
			search?.takes == Takes.Lines && it.lined(it.stages.size) == null -> it to emptyList()
			else ->
				it.copy(stages = it.stages + Stage(step), at = it.stages.size) to emptyList()
		}
	}

/**
 * The search whose lines a step at [index] would be given: the nearest before it that finds lines in files. None,
 * where every step before it gives files alone.
 */
fun Location.Text.lined(index: Int): TextSearch? =
	stages
		.take(index)
		.lastOrNull { stage ->
			(stage.step as? TextStep.Search)?.let {
				it.search.takes == Takes.Files && it.search.read != HitRead.Paths && !it.without
			} == true
		}?.let { (it.step as TextStep.Search).search }

internal fun BrowserState.textStepChosen(index: Int): Step =
	texting {
		if (index in it.stages.indices)
			it.copy(at = index) to emptyList()
		else
			it to emptyList()
	}

/**
 * The step at [index] taken out: those after it are run again, given what the one before it gives. Not the first,
 * which is what the search is of: that is left by going back.
 */
internal fun BrowserState.textStepRemoved(index: Int): Step =
	texting {
		if (index !in 1 until it.stages.size)
			it to emptyList()
		else
			it.from(index).let { (stale, stops) ->
				stale.copy(
					stages = stale.stages.filterIndexed { at, _ -> at != index },
					at =
						if (it.at >= index)
							it.at - 1
						else
							it.at,
				) to stops
			}
	}

/** The cursor of the list moved as [move] says, among the rows it shows. */
internal fun BrowserState.textMoved(move: Move): Step =
	texting {
		val rows = it.rows
		val from = it.cursor?.let(rows::indexOf)

		val to =
			when (move) {
				is Move.By -> (from ?: if (move.rows > 0) -1 else rows.size) + move.rows
				Move.First -> 0
				Move.Last -> rows.size - 1
			}

		if (rows.isEmpty())
			it to emptyList()
		else
			it.copy(cursor = rows[to.coerceIn(0, rows.size - 1)].id) to emptyList()
	}

internal fun BrowserState.textPointed(row: TextRowId): Step =
	texting { it.copy(cursor = row) to emptyList() }

/** [path]'s lines put away under it, or shown again; the cursor, on one put away, goes to its file. */
internal fun BrowserState.textFolded(path: Path, folded: Boolean?): Step =
	texting {
		val away = folded ?: (path !in it.folded)

		// A path is its names, one after another, to whatever adds or takes away several: one is added, here.
		it.copy(
			folded =
				if (away)
					it.folded.plusElement(path)
				else
					it.folded.minusElement(path),
			cursor =
				it.cursor?.let { at ->
					if (away &&
						at.path == path
					)
						TextRowId.File(path)
					else
						at
				},
		) to emptyList()
	}

internal fun BrowserState.textFoldedAll(folded: Boolean): Step =
	texting {
		it.copy(
			folded =
				if (folded)
					it.shown.hits.groups
						.mapTo(HashSet()) { group -> group.path }
				else
					emptySet(),
			cursor = it.cursor?.let { at -> if (folded) TextRowId.File(at.path) else at },
		) to emptyList()
	}

/**
 * The step under way stopped by the user: what its command found so far is what it found, and no step after it is
 * run until something is changed.
 */
internal fun BrowserState.textStopped(): Step {
	val text = text ?: return Step(this)
	val under = text.under ?: return Step(this)
	val step = text.stages[under.index].step as? TextStep.Search ?: return Step(this)

	return texted(
		text.done(
			under.index,
			StepDone(
				TextEnding.Stopped,
				under.found,
				given(step, text.inputOf(under.index), under.found),
			),
		) to listOf(Effect.FindText(null)),
	)
}

/** Lines [run] found: added to what it has found; shown, where it is the last step's. */
internal fun BrowserState.textFound(run: TextRun, hits: List<FileHit>): Step =
	texting {
		val under = it.under
		val step = under?.let { at -> it.stages[at.index].step as? TextStep.Search }

		if (under?.run !== run || step == null) {
			it to emptyList()
		} else {
			val found = under.found + hits
			val grown = it.copy(under = under.copy(found = found))

			if (under.index == it.stages.size - 1)
				grown
					.showing(given(step, it.inputOf(under.index), found))
					// The first of what a run finds takes the place of what the run before it did: so does the cursor.
					.let { shown ->
						if (under.found ===
							Hits.NONE
						)
							shown.copy(cursor = null)
						else
							shown
					} to
					emptyList()
			else
				grown to emptyList()
		}
	}

/** [run]'s command ended: its step has come to what it found, and the step after it is come to. */
internal fun BrowserState.textEnded(run: TextRun, ending: TextEnding): Step =
	texting {
		val under = it.under
		val step = under?.let { at -> it.stages[at.index].step as? TextStep.Search }

		if (under?.run !== run || step == null)
			it to emptyList()
		else
			it.done(
				under.index,
				StepDone(ending, under.found, given(step, it.inputOf(under.index), under.found)),
			) to emptyList()
	}

/** Which files [ask]'s query found: its step gives those; or, where it could not be run, has failed, and says why. */
internal fun BrowserState.textFiltered(
	ask: FilterAsk,
	admitted: Reading<Set<Path>>,
): Step =
	texting {
		val under = it.under
		val input = under?.let { at -> it.inputOf(at.index) }

		if ((under?.awaited as? Awaited.Names)?.ask !== ask || input == null)
			it to emptyList()
		else
			it.done(
				under.index,
				when (admitted) {
					is Reading.Right ->
						StepDone(
							TextEnding.Done(),
							input.hits,
							named(input, admitted.value),
						)
					is Reading.Wrong ->
						StepDone(
							TextEnding.Failed(admitted.why),
							Hits.NONE,
							TextFlow.NONE,
						)
				},
			) to emptyList()
	}

/**
 * The file [ask] asked for, read: shown, where it is still the one wanted. What is looked for in the file shown is
 * looked for in this one from its first place.
 */
internal fun BrowserState.previewed(ask: PreviewAsk, preview: Preview): Step =
	Step(
		text
			?.takeIf { it.asked == ask }
			?.let {
				copy(
					at =
						it.copy(
							previewed = Previewed(ask, preview),
							asked = null,
							find = it.find?.copy(at = 0),
						),
				)
			}
			?: this,
	)

/**
 * The files the text search shown lists, asked to be shown as a listing of files: a place of them, in which each is
 * the file it is, to be chosen, copied, and given to any action.
 */
internal fun BrowserState.textListed(): Step {
	val text = text ?: return Step(this)
	val paths = text.shown.hits.paths

	return if (paths.isEmpty())
		Step(this)
	else
		Step(this, listOf(Effect.ListFound(FoundAsk(text.said, paths))))
}

/** What its steps look for, one after another: what a listing of the files it found is called. */
val Location.Text.said: String get() =
	stages
		.mapNotNull { stage ->
			stage.step.typed.takeIf { it.isNotBlank() }?.let { typed ->
				if ((stage.step as? TextStep.Search)?.without == true)
					"not “$typed”"
				else
					"“$typed”"
			}
		}.joinToString(" › ")
		.ifEmpty { first.label.removeSuffix("…") }

/** [ask]'s files read: shown, every one, as what a search of a place of them finds, where the search is still shown. */
internal fun BrowserState.foundListed(
	ask: FoundAsk,
	tree: PlaceTree,
	finding: Finding,
): Step =
	when {
		text == null -> Step(this)
		finding is Finding.Matched ->
			shown(
				Location.Results(
					finding,
					within =
						Location.Place(
							Placed(FOUND_FILES, mapOf(FoundAsk.TITLE to ask.title)),
							tree = tree,
						),
				),
				History(history.back + at, emptyList()),
				null,
			)
		else -> notice(Notice.Failed("Couldn't list the files found", "They couldn't be read."))
	}

/** What looks in the file shown opened, or put away. */
internal fun BrowserState.previewFinding(open: Boolean): Step =
	texting {
		it.copy(
			find =
				if (open)
					it.find ?: PreviewFind()
				else
					null,
		) to emptyList()
	}

/**
 * What is looked for in the file shown typed: the place gone to is the first at or after the line found that the
 * file is shown at, so that what is looked for is found near what is being looked at.
 */
internal fun BrowserState.previewFindTyped(typed: String): Step =
	texting {
		val shown = it.previewed?.preview as? Preview.Shown
		val from =
			it.line
				?.hit
				?.position
				?.let { line -> (line - (shown?.first ?: 1L)).toInt() } ?: 0
		val found = shown?.occurrences(typed).orEmpty()

		it.copy(
			find =
				PreviewFind(
					typed,
					found
						.indexOfFirst { place ->
							place.line >= from
						}.coerceAtLeast(0),
				),
		) to emptyList()
	}

/** The next place what is looked for is at gone to, or the one before; round, past the last and the first. */
internal fun BrowserState.previewFindStepped(by: Int): Step =
	texting {
		val find = it.find

		if (find == null)
			it to emptyList()
		else
			it.copy(find = find.copy(at = find.index(it.occurrences) + by)) to emptyList()
	}

/** The folder [path] is in gone to, with it chosen there. */
internal fun BrowserState.textRevealed(path: Path): Step =
	path.parent?.let { went(it, Landing.On(RowId(path.fileName.toString()), choose = true)) }
		?: Step(this)

/** What an action is offered for, and done to, on [row] of the text search shown: the file it is, or is in. */
fun BrowserState.textSubject(row: TextRowId, at: Long? = null): Subject? =
	text?.let {
		Subject(
			On.Files,
			listOf(Thing.file(row.path)),
			Where.Text(it.search, it.valuesOf(row, at)),
		)
	}

/**
 * [row] opened, at line [at] of its file where that is said and at its own otherwise: by what the search says opens
 * its lines, or by the user's editor; or, where there is no editor, by the file's application, which is not told the
 * line.
 */
internal fun BrowserState.textOpened(row: TextRowId?, at: Long?): Step {
	val text = text ?: return Step(this)
	val id = row ?: text.cursor ?: return Step(this)
	val subject = textSubject(id, at) ?: return Step(this)
	val opener = text.search.open ?: (actions.editor as? Editor.Program)?.opener
	val label = "the editor"

	if (opener == null)
		return Step(this, listOf(Effect.Open(listOf(id.path))))

	val filling = subject.filling()
	val folder = id.path.parent ?: home

	val words =
		opener.command.fill(filling.copy(folder = folder))?.singleOrNull()
			?: return notice(
				Notice.Failed(
					"Couldn't open $label",
					"Its command uses a value that isn't available here.",
				),
			)

	return if (opener.terminal && !terminal.runsCommands)
		notice(
			Notice.Failed(
				"Couldn't open $label",
				"The terminal set in Preferences can't run commands: its command line has no {command}.",
			),
		)
	else
		Step(
			this,
			listOf(
				Effect.Launch(
					Job(label, listOf(Invocation(words, folder, filling.values))),
					terminal = opener.terminal,
				),
			),
		)
}
