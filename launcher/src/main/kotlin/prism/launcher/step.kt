package prism.launcher

import java.nio.file.Path
import java.time.Instant
import prism.desktop.Application
import prism.index.query.Own
import prism.index.query.Query
import prism.index.query.QueryText
import prism.preferences.LauncherPreferences

/*
 * What the launcher does with what happens to it: a pure function from a state and an event to the next state and
 * what is to be done about it. Nothing here starts a program or reads a disk; the session does what the effects say
 * and sends back what came of it, as an event.
 */

/** Something that happened to the launcher. */
sealed interface LauncherEvent {
	/** The field typed into. */
	data class Typed(
		val query: String,
	) : LauncherEvent

	/** The arrows: [by] rows down, or up where negative, stopping at either end. */
	data class Moved(
		val by: Int,
	) : LauncherEvent

	/** To the first result, or the last. */
	data class MovedTo(
		val last: Boolean,
	) : LauncherEvent

	/** The result the arrows are on chosen (Enter). */
	data object Entered : LauncherEvent

	/** The result at [at] chosen (a click). */
	data class Chosen(
		val at: Int,
	) : LauncherEvent

	/** The result the arrows are on shown where it is: its folder, in an explorer's window, with it chosen. */
	data object Revealed : LauncherEvent

	/** The path of the result the arrows are on (an application's: its desktop entry's) put on the clipboard. */
	data object CopiedPath : LauncherEvent

	/** The launcher's settings asked for. */
	data object AskedForSettings : LauncherEvent

	/**
	 * Escape: how a query is written left, where that is shown; otherwise what is typed let go, or, with nothing
	 * typed, the launcher put away.
	 */
	data object Dismissed : LauncherEvent

	/** How a query is written left (its ×), back to the field and what was found. */
	data object HelpClosed : LauncherEvent

	/** One of the queries that page shows tried: typed into the field, and the page left. */
	data class Tried(
		val query: String,
	) : LauncherEvent

	/** The launcher to be put away, whatever is typed: its window closed, or the keyboard gone elsewhere. */
	data object Closed : LauncherEvent

	/** The index's search for [query] back: the first [files] of [total]. */
	data class FilesArrived(
		val query: String,
		val files: List<Entry.File>,
		val total: Int,
	) : LauncherEvent

	/** The index's search for [query] went wrong, which is prism's mistake and has been reported. */
	data class SearchFailed(
		val query: String,
	) : LauncherEvent

	/** What is installed read again. */
	data class ApplicationsRead(
		val applications: List<Application>,
	) : LauncherEvent

	/** The user's settings changed. */
	data class PreferencesChanged(
		val preferences: LauncherPreferences,
	) : LauncherEvent

	/** What was chosen started, [at], or [failed] to, and why. */
	data class Started(
		val entry: Entry,
		val at: Instant,
		val failed: String? = null,
	) : LauncherEvent
}

/** Something the launcher asks to be done. */
sealed interface LauncherEffect {
	/** The index searched for [query] (as [text] read it), for the first [most] files. */
	data class Search(
		val text: String,
		val query: Query,
		val most: Int,
	) : LauncherEffect

	/** [entry] started, or opened: what is told back as [LauncherEvent.Started]. */
	data class Start(
		val entry: Entry,
	) : LauncherEffect

	/** [path] shown where it is, in an explorer's window. */
	data class Reveal(
		val path: Path,
	) : LauncherEffect

	/** [text] put on the clipboard. */
	data class Copy(
		val text: String,
	) : LauncherEffect

	/** The launcher's settings, in their window. */
	data object Settings : LauncherEffect

	/** The launcher's settings, in their window, at what its keys do. */
	data object Bindings : LauncherEffect

	/** An explorer's window on the page of the system's drives. */
	data object System : LauncherEffect

	/** An explorer's window on the home folder. */
	data object Explore : LauncherEffect

	/** The use of [key] at [at] kept. */
	data class Remember(
		val key: String,
		val at: Instant,
	) : LauncherEffect

	/** The launcher's window closed. */
	data object Close : LauncherEffect
}

/** A state, and what is to be done on the way to it. */
data class LauncherStep(
	val state: LauncherState,
	val effects: List<LauncherEffect> = emptyList(),
)

/**
 * [state] after [event], and what is to be done. While how a query is written is shown, what moves through the
 * results or chooses one of them does nothing: they are not there to be seen.
 */
fun step(state: LauncherState, event: LauncherEvent): LauncherStep =
	if (state.help && event.isOfTheResults)
		LauncherStep(state)
	else
		stepped(state, event)

/** Whether this is done to what was found, or to the field: what there is none of while the help is shown. */
private val LauncherEvent.isOfTheResults: Boolean
	get() =
		when (this) {
			is LauncherEvent.Typed, is LauncherEvent.Moved, is LauncherEvent.MovedTo,
			LauncherEvent.Entered, is LauncherEvent.Chosen, LauncherEvent.Revealed,
			LauncherEvent.CopiedPath,
			-> true
			LauncherEvent.AskedForSettings, LauncherEvent.Dismissed, LauncherEvent.HelpClosed,
			is LauncherEvent.Tried, LauncherEvent.Closed, is LauncherEvent.FilesArrived,
			is LauncherEvent.SearchFailed, is LauncherEvent.ApplicationsRead,
			is LauncherEvent.PreferencesChanged, is LauncherEvent.Started,
			-> false
		}

private fun stepped(state: LauncherState, event: LauncherEvent): LauncherStep =
	when (event) {
		is LauncherEvent.Typed ->
			state.copy(query = event.query, arrowsOn = null, notice = null).searching()
		is LauncherEvent.Moved -> LauncherStep(state.arrowsAt(state.highlighted + event.by))
		is LauncherEvent.MovedTo ->
			LauncherStep(
				state.arrowsAt(
					if (event.last)
						state.entries.size - 1
					else
						0,
				),
			)
		LauncherEvent.Entered -> state.chosen(state.highlighted)
		is LauncherEvent.Chosen -> state.chosen(event.at)
		LauncherEvent.Revealed ->
			LauncherStep(
				state,
				state.current
					?.let(::pathOf)
					?.let {
						listOf(LauncherEffect.Reveal(it), LauncherEffect.Close)
					}.orEmpty(),
			)
		LauncherEvent.CopiedPath ->
			LauncherStep(
				state,
				state.current
					?.let(::pathOf)
					?.let {
						listOf(LauncherEffect.Copy(it.toString()), LauncherEffect.Close)
					}.orEmpty(),
			)
		LauncherEvent.AskedForSettings ->
			LauncherStep(state, listOf(LauncherEffect.Settings, LauncherEffect.Close))
		LauncherEvent.Dismissed ->
			when {
				state.help -> LauncherStep(state.copy(help = false))
				state.query.isEmpty() -> LauncherStep(state, listOf(LauncherEffect.Close))
				else -> state.copy(query = "", arrowsOn = null, notice = null).searching()
			}
		LauncherEvent.HelpClosed -> LauncherStep(state.copy(help = false))
		is LauncherEvent.Tried ->
			state
				.copy(
					query = event.query,
					arrowsOn = null,
					notice = null,
					help = false,
				).searching()
		LauncherEvent.Closed -> LauncherStep(state, listOf(LauncherEffect.Close))
		is LauncherEvent.FilesArrived ->
			// An answer for what was typed before is dropped: the one for what is typed now is on its way.
			if (event.query != state.query)
				LauncherStep(state)
			else
				LauncherStep(
					state.copy(files = FilesFound.Found(event.query, event.files, event.total)),
				)
		is LauncherEvent.SearchFailed ->
			if (event.query != state.query)
				LauncherStep(state)
			else
				LauncherStep(state.copy(files = FilesFound.Failed(event.query)))
		is LauncherEvent.ApplicationsRead ->
			LauncherStep(state.copy(installed = Installed.of(event.applications)))
		is LauncherEvent.PreferencesChanged ->
			state.copy(preferences = event.preferences).let {
				// Files newly asked for are looked for; those no longer asked for are let go.
				if (it.preferences.files != state.preferences.files ||
					it.preferences.fileResults != state.preferences.fileResults
				)
					it.copy(files = FilesFound.None).searching()
				else
					LauncherStep(it)
			}
		is LauncherEvent.Started ->
			if (event.failed == null)
				LauncherStep(
					state.copy(launches = state.launches.used(event.entry.key, event.at)),
					listOf(LauncherEffect.Remember(event.entry.key, event.at), LauncherEffect.Close),
				)
			else
				LauncherStep(state.copy(notice = event.failed))
	}

/** This state with the index asked for the files that fit what is typed, where files are found at all. */
private fun LauncherState.searching(): LauncherStep {
	if (!preferences.files || !searchable)
		return LauncherStep(copy(files = FilesFound.None))

	val read = QueryText.read(query)

	// Nothing typed, or nothing yet that can be searched for, would find everything: nothing is found.
	return if (read.query.terms.isEmpty())
		LauncherStep(copy(files = FilesFound.None))
	else
		LauncherStep(
			this,
			listOf(LauncherEffect.Search(query, read.query, preferences.fileResults.number)),
		)
}

/** The entry at [at] chosen: started or opened, or, for what prism does, done. */
private fun LauncherState.chosen(at: Int): LauncherStep =
	when (val entry = entries.getOrNull(at)) {
		null -> LauncherStep(this)
		is Entry.Command ->
			LauncherStep(
				this,
				listOf(
					when (entry.command) {
						LauncherCommand.Files -> LauncherEffect.Explore
					},
					LauncherEffect.Close,
				),
			)
		is Entry.Prism ->
			when (entry.own) {
				Own.Settings ->
					LauncherStep(
						this,
						listOf(LauncherEffect.Settings, LauncherEffect.Close),
					)
				Own.Bindings ->
					LauncherStep(
						this,
						listOf(LauncherEffect.Bindings, LauncherEffect.Close),
					)
				Own.System -> LauncherStep(this, listOf(LauncherEffect.System, LauncherEffect.Close))
				// Shown here, in the launcher's own window, which stays.
				Own.Help -> LauncherStep(copy(help = true, notice = null))
			}
		else ->
			LauncherStep(
				copy(arrowsOn = entry.key, notice = null),
				listOf(LauncherEffect.Start(entry)),
			)
	}

/** Where [entry] is on disk: a file's own path, an application's desktop entry; nowhere, for what prism does. */
private fun pathOf(entry: Entry): Path? =
	when (entry) {
		is Entry.File -> entry.path
		is Entry.App -> entry.application.source
		is Entry.Action -> entry.application.source
		is Entry.Command, is Entry.Prism -> null
	}
