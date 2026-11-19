package prism.launcher

import java.nio.file.Path
import java.time.Instant
import prism.browser.ActionMenu
import prism.desktop.Application
import prism.index.query.Aliases
import prism.index.query.Own
import prism.index.query.Query
import prism.preferences.LauncherPreferences
import prism.window.OpenWindow

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

	/**
	 * What can be done to the result at [at] shown as a menu by it (a right click); with no [at], to the one the
	 * arrows are on (the right arrow).
	 */
	data class MenuOpened(
		val at: Int? = null,
	) : LauncherEvent

	/** That menu put away (the left arrow, a click outside it). */
	data object MenuClosed : LauncherEvent

	/** The menu's action [at] done (a click on it). */
	data class MenuChosen(
		val at: Int,
	) : LauncherEvent

	/**
	 * Something done from a result's menu came to this: nothing to say, and the launcher is put away; or [notice],
	 * said along its bottom, and it stays.
	 */
	data class Acted(
		val notice: LauncherNotice? = null,
	) : LauncherEvent

	/** The result the arrows are on shown where it is: its folder, in an explorer's window, with it chosen. */
	data object Revealed : LauncherEvent

	/** The banner's own key: what the banner along the top says its key does, done. */
	data object Answered : LauncherEvent

	/** A module's view left, back to the launcher's own field (Backspace with nothing typed in it). */
	data object LeftView : LauncherEvent

	/** In a module's view, the row the arrows are on used, the field staying the module's (Tab): a name finished. */
	data object Completed : LauncherEvent

	/** The first module found by what is typed opened, its view taking the field (Tab, where one is found). */
	data object ModuleAsked : LauncherEvent

	/** The file or folder the arrows are on put on the clipboard, to be pasted; nothing, on any other result. */
	data object Copied : LauncherEvent

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

	/** The user's search aliases changed. */
	data class AliasesChanged(
		val aliases: Aliases,
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

	/** The file or folder at [path] put on the clipboard as a file, copied: what a paste in a file manager brings. */
	data class CopyFile(
		val path: Path,
	) : LauncherEffect

	/** A window to choose what [path] is opened with. */
	data class OpenWith(
		val path: Path,
	) : LauncherEffect

	/** A window of [path]'s properties. */
	data class Properties(
		val path: Path,
	) : LauncherEffect

	/** A terminal opened in [folder]: what came of it is told back as [LauncherEvent.Acted]. */
	data class Terminal(
		val folder: Path,
	) : LauncherEffect

	/** [path] moved to the trash: what came of it is told back as [LauncherEvent.Acted]. */
	data class Trash(
		val path: Path,
	) : LauncherEffect

	/** [window], which an application has open, brought forward and given the keyboard. */
	data class Focus(
		val window: OpenWindow,
	) : LauncherEffect

	/** [application] left out of the launcher from now on: its settings kept so. */
	data class Hide(
		val application: Application,
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
	when {
		state.help && event.isOfTheResults -> LauncherStep(state)
		state.menu != null -> inMenu(state, state.menu, event)
		else -> stepped(state, event)
	}

/**
 * [state] after [event] while [menu] is open: the arrows move through its actions, Enter does the one they are on,
 * and Escape puts it away and nothing more. What comes back from work under way changes what it would have changed,
 * the menu staying; anything else the user does puts the menu away first.
 */
private fun inMenu(
	state: LauncherState,
	menu: EntryMenu,
	event: LauncherEvent,
): LauncherStep {
	fun moved(actions: ActionMenu<EntryAction>) =
		LauncherStep(state.copy(menu = menu.copy(actions = actions)))

	val closed = state.copy(menu = null)

	return when (event) {
		is LauncherEvent.Moved -> moved(menu.actions.moved(event.by))
		is LauncherEvent.MovedTo -> moved(menu.actions.movedTo(event.last))
		LauncherEvent.Entered -> closed.acted(menu.entry, menu.actions.current)
		is LauncherEvent.MenuChosen ->
			menu.actions.actions
				.getOrNull(event.at)
				?.let { closed.acted(menu.entry, it) }
				?: LauncherStep(state)
		LauncherEvent.MenuClosed, LauncherEvent.Dismissed -> LauncherStep(closed)
		// Asked for again with no result named: it is open already.
		is LauncherEvent.MenuOpened ->
			if (event.at == null)
				LauncherStep(state)
			else
				stepped(closed, event)
		is LauncherEvent.FilesArrived, is LauncherEvent.SearchFailed,
		is LauncherEvent.ApplicationsRead, is LauncherEvent.PreferencesChanged,
		is LauncherEvent.AliasesChanged, is LauncherEvent.Started, is LauncherEvent.Acted,
		-> stepped(state, event)
		is LauncherEvent.Typed, is LauncherEvent.Chosen, LauncherEvent.Revealed,
		LauncherEvent.Copied, LauncherEvent.CopiedPath, LauncherEvent.AskedForSettings,
		LauncherEvent.HelpClosed, LauncherEvent.Answered, LauncherEvent.LeftView,
		LauncherEvent.Completed, LauncherEvent.ModuleAsked,
		is LauncherEvent.Tried, LauncherEvent.Closed,
		-> stepped(closed, event)
	}
}

/** Whether this is done to what was found, or to the field: what there is none of while the help is shown. */
private val LauncherEvent.isOfTheResults: Boolean
	get() =
		when (this) {
			is LauncherEvent.Typed, is LauncherEvent.Moved, is LauncherEvent.MovedTo,
			LauncherEvent.Entered, is LauncherEvent.Chosen, LauncherEvent.Revealed,
			LauncherEvent.Copied, LauncherEvent.CopiedPath, is LauncherEvent.MenuOpened,
			LauncherEvent.MenuClosed,
			is LauncherEvent.MenuChosen, LauncherEvent.Answered, LauncherEvent.LeftView,
			LauncherEvent.Completed, LauncherEvent.ModuleAsked,
			-> true
			LauncherEvent.AskedForSettings, LauncherEvent.Dismissed, LauncherEvent.HelpClosed,
			is LauncherEvent.Tried, LauncherEvent.Closed, is LauncherEvent.FilesArrived,
			is LauncherEvent.SearchFailed, is LauncherEvent.ApplicationsRead,
			is LauncherEvent.PreferencesChanged, is LauncherEvent.AliasesChanged,
			is LauncherEvent.Started, is LauncherEvent.Acted,
			-> false
		}

private fun stepped(state: LauncherState, event: LauncherEvent): LauncherStep =
	when (event) {
		is LauncherEvent.Typed -> state.typed(event.query)
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
		// In a module's view, Enter is the banner's key, where it has one: the rows there are finished by Tab.
		LauncherEvent.Entered ->
			if (state.viewing != null && state.shown?.banner?.key != null)
				state.answered()
			else
				state.chosen(state.highlighted)
		LauncherEvent.Answered -> state.answered()
		LauncherEvent.ModuleAsked ->
			state.entries.filterIsInstance<Entry.Module>().firstOrNull()?.let {
				state.intoView(it.module, "")
			}
				?: LauncherStep(state)
		LauncherEvent.LeftView -> state.leftView()
		LauncherEvent.Completed ->
			(state.current as? Entry.Answer)
				?.takeIf {
					state.viewing != null
				}?.let { state.did(it.row.act) }
				?: LauncherStep(state)
		is LauncherEvent.Chosen -> state.chosen(event.at)
		is LauncherEvent.MenuOpened ->
			LauncherStep(
				state.menuOpened(event.at ?: state.highlighted),
			)
		// With no menu open, there is none to put away, and nothing in one to choose.
		LauncherEvent.MenuClosed, is LauncherEvent.MenuChosen -> LauncherStep(state)
		is LauncherEvent.Acted ->
			if (event.notice == null)
				LauncherStep(state, listOf(LauncherEffect.Close))
			else
				// What was done may have changed what there is to find: a file in the trash is no longer one.
				state.copy(notice = event.notice).searching()
		LauncherEvent.Revealed ->
			LauncherStep(
				state,
				state.current
					?.let(::pathOf)
					?.let {
						listOf(LauncherEffect.Reveal(it), LauncherEffect.Close)
					}.orEmpty(),
			)
		LauncherEvent.Copied ->
			LauncherStep(
				state,
				(state.current as? Entry.File)
					?.let { listOf(LauncherEffect.CopyFile(it.path), LauncherEffect.Close) }
					.orEmpty(),
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
				state.viewing != null -> state.leftView()
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
			LauncherStep(state.copy(installed = state.installed.read(event.applications)))
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
		// What is typed may mean something else now: the files are looked for again.
		is LauncherEvent.AliasesChanged ->
			if (event.aliases == state.aliases)
				LauncherStep(state)
			else
				state.copy(aliases = event.aliases, files = FilesFound.None).searching()
		is LauncherEvent.Started ->
			if (event.failed == null)
				LauncherStep(
					state.copy(launches = state.launches.used(event.entry.key, event.at)),
					listOf(LauncherEffect.Remember(event.entry.key, event.at), LauncherEffect.Close),
				)
			else
				LauncherStep(state.copy(notice = LauncherNotice.Failed(event.failed)))
	}

/**
 * [text] typed into the field: into the module's, in a module's view; otherwise what is typed bare, unless it begins
 * with a module's prefix, which opens the module's view with what follows it.
 */
private fun LauncherState.typed(text: String): LauncherStep {
	viewing?.let {
		return LauncherStep(
			copy(viewing = it.copy(text = text), arrowsOn = null, notice = null),
		)
	}

	return modules.prefixed(text)?.let { (module, rest) -> intoView(module, rest) }
		?: copy(query = text, arrowsOn = null, notice = null).searching()
}

/** [module]'s view opened, with [text] typed in it: what is typed bare let go, and the files found for it with it. */
private fun LauncherState.intoView(module: LauncherModule, text: String): LauncherStep =
	LauncherStep(
		copy(
			query = "",
			viewing = Viewing(module.id, text),
			files = FilesFound.None,
			arrowsOn = null,
			notice = null,
			menu = null,
		),
	)

/** The module's view left, with nothing typed: the launcher as it opens. */
private fun LauncherState.leftView(): LauncherStep =
	copy(viewing = null, query = "", arrowsOn = null, notice = null).searching()

/** What the banner along the top says its key does, done; nothing, where there is none or it has no key. */
private fun LauncherState.answered(): LauncherStep {
	val (module, banner) = shown ?: return LauncherStep(this)

	return when (val key = banner.key) {
		null -> LauncherStep(this)
		is BannerKey.Does -> did(key.act)
		is BannerKey.View -> intoView(module, query)
		is BannerKey.Menu ->
			ActionMenu.of<EntryAction>(key.choices.map(EntryAction::Choice))?.let { actions ->
				val of =
					ModuleRow(
						"banner",
						banner.title,
						banner.badge.orEmpty(),
						module.title,
						key.choices.first().act,
					)

				LauncherStep(
					copy(
						menu =
							EntryMenu(
								Entry.Answer(module.id, module.title, of),
								actions,
								ofBanner = true,
							),
						notice = null,
					),
				)
			} ?: LauncherStep(this)
	}
}

/** What a module says is to be done, done. */
internal fun LauncherState.did(act: ModuleAct): LauncherStep =
	when (act) {
		is ModuleAct.Copy ->
			LauncherStep(
				copy(notice = null),
				listOf(LauncherEffect.Copy(act.text), LauncherEffect.Close),
			)
		is ModuleAct.Put -> typed(act.text)
	}

/** This state with the index asked for the files that fit what is typed, where files are found at all. */
private fun LauncherState.searching(): LauncherStep {
	if (!preferences.files || !searchable || viewing != null)
		return LauncherStep(copy(files = FilesFound.None))

	// Nothing typed, or nothing yet that can be searched for, would find everything: nothing is found.
	return if (read.query.terms.isEmpty())
		LauncherStep(copy(files = FilesFound.None))
	else
		LauncherStep(
			this,
			listOf(LauncherEffect.Search(query, read.query, preferences.fileResults.number)),
		)
}

/** The menu of what can be done to the entry at [at] opened, the arrows put on that entry; nothing, with none there. */
private fun LauncherState.menuOpened(at: Int): LauncherState =
	entries.getOrNull(at)?.let { entry ->
		ActionMenu
			.of(actionsOf(entry))
			?.let { copy(arrowsOn = entry.key, notice = null, menu = EntryMenu(entry, it)) }
	} ?: this

/** The entry at [at] chosen: started or opened, or, for what prism does, done. */
private fun LauncherState.chosen(at: Int): LauncherStep =
	entries.getOrNull(at)?.let(::opened) ?: LauncherStep(this)

/** [entry] started or opened, or, for what prism does, done: what Enter does to it. */
private fun LauncherState.opened(entry: Entry): LauncherStep =
	when (entry) {
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
		// Its name goes where it was being typed, and the launcher stays for the rest of the query.
		is Entry.Named ->
			copy(
				query = entry.completed,
				arrowsOn = null,
				notice = null,
			).searching()
		is Entry.App, is Entry.Action, is Entry.File ->
			LauncherStep(
				copy(arrowsOn = entry.key, notice = null),
				listOf(LauncherEffect.Start(entry)),
			)
		is Entry.Answer -> did(entry.row.act)
		is Entry.Module -> intoView(entry.module, "")
	}

/**
 * [action] done to [entry], from its menu. What opens another window, or puts something on the clipboard, puts the
 * launcher away; what is done here and may fail (a terminal, the trash) is told back, and the launcher goes or stays
 * by what came of it ([LauncherEvent.Acted]).
 */
private fun LauncherState.acted(entry: Entry, action: EntryAction): LauncherStep {
	val on = copy(arrowsOn = entry.key, notice = null)
	val path = pathOf(entry)

	/** [effects], and the launcher put away; nothing, for an entry that is nowhere on disk. */
	fun away(effect: (Path) -> LauncherEffect): LauncherStep =
		LauncherStep(on, path?.let { listOf(effect(it), LauncherEffect.Close) }.orEmpty())

	return when (action) {
		EntryAction.Open, EntryAction.Use -> on.opened(entry)
		is EntryAction.Focus ->
			LauncherStep(on, listOf(LauncherEffect.Focus(action.window), LauncherEffect.Close))
		is EntryAction.Run ->
			LauncherStep(
				on,
				listOfNotNull(
					(entry as? Entry.App)?.let {
						LauncherEffect.Start(Entry.Action(it.application, action.action))
					},
				),
			)
		is EntryAction.Reveal -> away(LauncherEffect::Reveal)
		EntryAction.Copy -> away(LauncherEffect::CopyFile)
		is EntryAction.CopyPath -> away { LauncherEffect.Copy(it.toString()) }
		EntryAction.CopyName ->
			LauncherStep(
				on,
				listOf(LauncherEffect.Copy(entry.title), LauncherEffect.Close),
			)
		EntryAction.OpenWith -> away(LauncherEffect::OpenWith)
		EntryAction.Properties -> away(LauncherEffect::Properties)
		EntryAction.Terminal ->
			LauncherStep(
				on,
				listOfNotNull(
					(entry as? Entry.File)
						?.let { if (it.isFolder) it.path else it.path.parent }
						?.let(LauncherEffect::Terminal),
				),
			)
		EntryAction.Trash ->
			LauncherStep(
				on,
				listOfNotNull(
					(entry as? Entry.File)?.let {
						LauncherEffect.Trash(it.path)
					},
				),
			)
		is EntryAction.Choice -> did(action.choice.act)
		EntryAction.Hide ->
			LauncherStep(
				on,
				listOfNotNull((entry as? Entry.App)?.let { LauncherEffect.Hide(it.application) }),
			)
	}
}

/** Where [entry] is on disk: a file's own path, an application's desktop entry; nowhere, for what prism does. */
private fun pathOf(entry: Entry): Path? =
	when (entry) {
		is Entry.File -> entry.path
		is Entry.App -> entry.application.source
		is Entry.Action -> entry.application.source
		is Entry.Command, is Entry.Prism, is Entry.Named, is Entry.Answer, is Entry.Module -> null
	}
