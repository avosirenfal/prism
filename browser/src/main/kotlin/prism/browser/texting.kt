package prism.browser

import java.nio.file.Path

/*
 * The reducer's part in text searches (docs/text.md): what opening one, typing into it, moving about what it found and
 * opening a line come to. Like the rest of the reducer it runs nothing: the command to run is an effect
 * ([Effect.FindText]), and so is the file to show beside the list ([Effect.Preview]).
 *
 * One run of the command is under way at most. Whatever asks for another (a letter typed, an option turned) takes the
 * place of the one under way, which is stopped; and what a run that was overtaken found, arriving late, is of no run
 * the window knows, and is dropped. What the last run found stays shown until the next finds something or ends, so
 * that the list is not emptied at every letter.
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

/** [search] opened on [target]: shown in place of what is, which goes behind it in the history. */
internal fun BrowserState.searchingText(search: TextSearch, target: Target?): Step =
	shown(
		Location.Text(search, textScope(target)),
		History(history.back + at, emptyList()),
		null,
	)

/**
 * [text] shown, the window just come to it: its command run, where it has found nothing yet or was stopped by
 * being left; as it was, where it had ended.
 */
internal fun BrowserState.textShown(text: Location.Text): Step =
	if (text.running != null || text.results.of == null)
		texted(text.rerun())
	else
		texted(text to emptyList())

/** This place, with [changed] of its text search, and what that asks for. */
private fun BrowserState.texted(changed: Pair<Location.Text, List<Effect>>): Step {
	val (pointed, asks) = changed.first.pointing()

	return Step(copy(at = pointed), changed.second + asks)
}

/** The text search shown changed by [change], where one is shown. */
private fun BrowserState.texting(
	change: (Location.Text) -> Pair<Location.Text, List<Effect>>,
): Step = text?.let { texted(change(it)) } ?: Step(this)

/**
 * This search's command run for what is typed now: the run under way, if one is, gives way to it. A search that looks
 * for what is typed, with nothing typed, runs nothing and has found nothing.
 */
private fun Location.Text.rerun(): Pair<Location.Text, List<Effect>> =
	if (search.query == QueryUse.Typed && query.isBlank())
		copy(running = null, results = TextResults.NONE, cursor = null) to
			listOf(Effect.FindText(null))
	else
		TextRun(
			search,
			scope,
			query
				.takeIf {
					search.query == QueryUse.Typed
				}.orEmpty(),
			on,
		).let {
			copy(running = it) to listOf(Effect.FindText(it))
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

/** What a text search looks for typed: looked for, or what was found narrowed by it. */
internal fun BrowserState.textTyped(typed: String): Step =
	texting {
		val next = it.copy(query = typed)

		when {
			typed == it.query -> it to emptyList()
			it.search.query == QueryUse.Typed -> next.rerun()
			else -> next to emptyList()
		}
	}

/** An option of the text search shown turned the other way: its command is run again, with it so. */
internal fun BrowserState.textOptioned(name: String): Step =
	texting {
		if (it.search.options.none { option -> option.name == name })
			it to emptyList()
		else
			it
				.copy(
					on =
						if (name in it.on)
							it.on - name
						else
							it.on + name,
				).rerun()
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

		it.copy(
			folded =
				if (away)
					it.folded + path
				else
					it.folded - path,
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
					it.results.hits.groups
						.mapTo(HashSet()) { group -> group.path }
				else
					emptySet(),
			cursor = it.cursor?.let { at -> if (folded) TextRowId.File(at.path) else at },
		) to emptyList()
	}

/** The run under way stopped by the user: what it found so far is what it found. */
internal fun BrowserState.textStopped(): Step =
	texting {
		val run = it.running

		if (run == null)
			it to emptyList()
		else
			it.ended(run, TextEnding.Stopped) to listOf(Effect.FindText(null))
	}

/** This search, [run] having ended as [ending] says: what it found is all it found, which is nothing where it sent none. */
private fun Location.Text.ended(run: TextRun, ending: TextEnding): Location.Text =
	copy(
		running = null,
		results =
			if (results.of === run)
				results.copy(ending = ending)
			else
				TextResults(run, Hits.NONE, ending),
		cursor = cursor.takeIf { results.of === run },
	)

/** Lines [run] found: added to what it has found, or shown in place of what the run before it did. */
internal fun BrowserState.textFound(run: TextRun, hits: List<FileHit>): Step =
	texting {
		when {
			it.running !== run -> it to emptyList()
			it.results.of === run ->
				it.copy(
					results =
						it.results.copy(
							hits =
								it.results.hits + hits,
						),
				) to
					emptyList()
			else ->
				it.copy(results = TextResults(run, Hits.NONE + hits, null), cursor = null) to
					emptyList()
		}
	}

/** [run]'s command ended. */
internal fun BrowserState.textEnded(run: TextRun, ending: TextEnding): Step =
	texting {
		if (it.running !== run)
			it to emptyList()
		else
			it.ended(run, ending) to emptyList()
	}

/** The file [ask] asked for, read: shown, where it is still the one wanted. */
internal fun BrowserState.previewed(ask: PreviewAsk, preview: Preview): Step =
	Step(
		text
			?.takeIf { it.asked == ask }
			?.let { copy(at = it.copy(previewed = Previewed(ask, preview), asked = null)) }
			?: this,
	)

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
