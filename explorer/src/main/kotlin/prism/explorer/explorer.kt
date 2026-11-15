package prism.explorer

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import prism.browser.AppKey
import prism.browser.BrowserEvent
import prism.browser.Choosing
import prism.browser.Disk
import prism.browser.FileClipboard
import prism.browser.Finder
import prism.browser.FolderWatcher
import prism.browser.Landing
import prism.browser.Location
import prism.browser.MemoryRecents
import prism.browser.Outcome
import prism.browser.RecentStore
import prism.browser.Request
import prism.browser.RowId
import prism.browser.Search
import prism.browser.Services
import prism.browser.Session
import prism.browser.Start
import prism.browser.Step
import prism.browser.TypeFilter
import prism.browser.World
import prism.browser.aliasesOf
import prism.browser.opened
import prism.browser.openedIn
import prism.browser.openedOnSystem
import prism.browser.sidebarOf
import prism.browser.toClipboard
import prism.browser.zoomOf
import prism.desktop.Application
import prism.desktop.Desktop
import prism.desktop.MimeType
import prism.launcher.Dmenu
import prism.launcher.DmenuAnswer
import prism.launcher.DmenuEvent
import prism.launcher.DmenuSession
import prism.launcher.IconImages
import prism.launcher.LaunchStore
import prism.launcher.LauncherEvent
import prism.launcher.LauncherHost
import prism.launcher.LauncherServices
import prism.launcher.LauncherSession
import prism.launcher.MemoryLaunches
import prism.preferences.PreferenceStore
import prism.preferences.Role
import prism.preferences.preferences
import prism.window.openWindows

/*
 * The explorer: the browser as a file manager, in as many windows as the user opens. What it has open is one immutable
 * list of windows, each made from a request (a folder to browse, the properties of some files, an application to open
 * them with, the preferences) and gone when it is closed. The windows are drawn from that list (windows.kt), so the
 * list is all there is to know of what is open: the explorer run on its own (`prism explore`) ends when it is empty,
 * and the daemon, which runs the explorer for every application that asks, keeps it while it runs.
 *
 * A window is on its own: what it does is done in a scope of its own, and what goes wrong there, or in drawing it
 * ([Explorer.failed]), closes that window and no other, is said on standard error, and is shown in a window that says
 * what happened ([ExplorerWindow.Failed]). The explorer, its other windows, and whatever else the process is doing
 * (the daemon's index) go on.
 */

/** What tells one of the explorer's windows from the others: a number no other window has had, and nothing else's. */
@JvmInline
value class WindowId(
	val number: Int,
)

/** A window the explorer has open, told apart from the others by [id]. */
sealed interface ExplorerWindow {
	val id: WindowId

	/**
	 * Files browsed: a browser's [session], whose work is done in [scope], which ends with the window. [activation]
	 * is the desktop's token for whoever asked for the window, where it was asked for from outside, with which the
	 * window is given the keyboard as it opens.
	 */
	class Browsing internal constructor(
		override val id: WindowId,
		val session: Session,
		internal val scope: CoroutineScope,
		val activation: String? = null,
	) : ExplorerWindow

	/**
	 * A folder chosen for an action that asked for one: a picker's [session], titled [title], whose outcome is the
	 * answer, given to the window that asked.
	 */
	class Picking internal constructor(
		override val id: WindowId,
		val title: String,
		val session: Session,
		internal val scope: CoroutineScope,
		/** The X11 window of the application that asked, which this is a dialog of; none for the explorer's own. */
		val parent: Long? = null,
	) : ExplorerWindow

	/** What can be known of [paths], and their permissions to change. */
	data class Properties(
		override val id: WindowId,
		val paths: List<Path>,
	) : ExplorerWindow

	/** An application chosen, for [choice]. */
	data class OpenWith(
		override val id: WindowId,
		val choice: Choice,
	) : ExplorerWindow

	/**
	 * The preferences, of which there is one window; [raised] counts the times it was asked for again while open,
	 * and [page] is the page it was last asked for at (its key bindings, say), where it was asked for at one: it
	 * opens there, and goes there when asked for again.
	 */
	data class Settings(
		override val id: WindowId,
		val raised: Int = 0,
		val page: Page? = null,
	) : ExplorerWindow

	/**
	 * The launcher, of which there is one window: its [session], whose work is done in [scope], which ends with the
	 * window. [activation] is the desktop's token for whoever asked for it, with which it is given the keyboard.
	 */
	class Launching internal constructor(
		override val id: WindowId,
		val session: LauncherSession,
		internal val scope: CoroutineScope,
		val activation: String? = null,
	) : ExplorerWindow

	/**
	 * The launcher's window with lines to choose one of in place of what the launcher finds (`prism dmenu`): its
	 * [session], whose work is done in [scope], which ends with the window. It is the launcher's one window: shown,
	 * the launcher is not, and asked for, whatever of the two is shown is put away for it.
	 */
	class Dmenu internal constructor(
		override val id: WindowId,
		val session: DmenuSession,
		internal val scope: CoroutineScope,
		val activation: String? = null,
	) : ExplorerWindow

	/**
	 * The launcher's settings, of which there is one window; [raised] as the preferences' is. [asked] is the page it
	 * was last asked for at, which it turns to each time it is; none, where it was asked for as it is.
	 */
	data class LauncherSettings(
		override val id: WindowId,
		val raised: Int = 0,
		val asked: LauncherPage? = null,
	) : ExplorerWindow

	/**
	 * What is shown in place of a window that failed and was closed: [what] it was ("Documents", "Preferences"), the
	 * [failure], and where a browser can be opened [again], for a window that was browsing.
	 */
	data class Failed(
		override val id: WindowId,
		val what: String,
		val failure: Failure,
		val again: Start?,
	) : ExplorerWindow
}

/** Something that went wrong, as it is shown and copied: in a line ([summary]), and in full ([trace]). */
data class Failure(
	val summary: String,
	val trace: String,
) {
	companion object {
		fun of(failure: Throwable): Failure =
			Failure(
				// What kind of thing went wrong, and what it says of itself, where it says anything.
				listOfNotNull(
					failure.javaClass.simpleName.ifEmpty {
						failure.javaClass.name
					},
					failure.message,
				).joinToString(": "),
				failure.stackTraceToString().trimEnd(),
			)
	}
}

/** A picker's window failed before the picker ended: what whoever asked for it is told, in place of an answer. */
class PickerFailed(
	cause: Throwable?,
) : RuntimeException(
		"the picker's window failed" + (cause?.let { ": ${Failure.of(it).summary}" } ?: ""),
		cause,
	)

/** A picker another application asks for. */
data class Asking(
	val title: String,
	val choosing: Choosing,
	/** Where it opens. */
	val folder: Path,
	/** The filters it offers: at least one; the first, unless [filter] is one of them, is the one in use at first. */
	val filters: List<TypeFilter>,
	val filter: TypeFilter? = null,
	/** What the button that accepts says, in the application's words. */
	val acceptLabel: String? = null,
	/** The X11 window of the application that asked, which the picker is a dialog of. */
	val parent: Long? = null,
	/** The application that asked, where the desktop could tell which: whose recent folders the picker shows. */
	val asker: AppKey? = null,
) {
	init {
		require(filters.isNotEmpty()) { "a picker offers at least one filter" }
	}
}

/** What a picker another application asked for ended with, and the filter in use as it did. */
data class Answered(
	val outcome: Outcome,
	val filter: TypeFilter,
)

/** What an application is chosen for. */
sealed interface Choice {
	/** [paths] opened with it; [type], where they are all of one, the type it can be made the default for. */
	data class Open(
		val paths: List<Path>,
		val type: MimeType?,
	) : Choice

	/** It made the default for [type]. */
	data class Default(
		val type: MimeType,
	) : Choice
}

/**
 * The explorer, browsing over [disk] (and searching with [finder]) on [desktop], with the [clipboard] files are copied
 * to and the user's [preferences], every window's. Its windows' work is done in children of [scope], which should run
 * on the thread the windows are drawn on, as a composition's does; a new window opens on [search], the state a
 * search's window starts in (one ready to search, or one that says why there is none). What goes wrong in a window is
 * told to [report], with what the window was, before it is shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Explorer(
	private val disk: Disk,
	finder: Finder?,
	val desktop: Desktop,
	clipboard: FileClipboard,
	val preferences: PreferenceStore,
	private val scope: CoroutineScope,
	val home: Path,
	private val search: Search,
	/** What says a folder shown has changed on disk, for its window to read it again. */
	watcher: FolderWatcher = FolderWatcher.NONE,
	/** Where what goes wrong in a window is said, beside being shown: standard error, unless told otherwise. */
	private val report: (String, Throwable) -> Unit = { what, failure ->
		System.err.println("prism: $what: ${failure.stackTraceToString().trimEnd()}")
	},
	/** What the launcher has started, and how lately: kept in memory alone, unless a file's is given. */
	private val launches: LaunchStore = MemoryLaunches(),
	/** The folders pickers were lately answered from: kept in memory alone, unless a file's is given. */
	val recents: RecentStore = MemoryRecents(),
) {
	/** What a search is asked of: the launcher's, as every window's. */
	private val finder = finder

	/** The pictures applications are known by, drawn once and kept for every launcher shown. */
	val icons = IconImages(desktop.icons)

	private val services =
		Services(
			disk,
			finder,
			desktop,
			clipboard,
			preferences,
			watcher = watcher,
			report = report,
			recents = recents,
		)

	/** What the user's actions are decided by: the types of files, the programs installed. */
	val world: World get() = services.world

	private val ids = AtomicInteger()
	private val open = MutableStateFlow<List<ExplorerWindow>>(emptyList())
	private val defaultsSet = MutableStateFlow(0)

	/** The windows open, in the order they opened. */
	val windows: StateFlow<List<ExplorerWindow>> = open.asStateFlow()

	/** How many defaults have been set: what shows an application a type opens with reads it again as this changes. */
	val defaults: StateFlow<Int> = defaultsSet.asStateFlow()

	/** A window browsing [folder]; with [chosen], the entry of that name chosen in it, the cursor on it. */
	fun browse(folder: Path, chosen: String? = null, activation: String? = null) =
		browse(Start.Folder(folder), chosen, activation)

	/**
	 * A window browsing from [start]: a folder, a place, or the System page; in a folder, [chosen] chosen where it is
	 * given. With
	 * [activation], the desktop's token for whoever asked, the window is given the keyboard as it opens.
	 */
	fun browse(start: Start, chosen: String? = null, activation: String? = null) {
		val making = Making()
		val (id, window) = making
		val sidebar = sidebarOf(desktop.places, home, preferences.preferences.value)

		val first =
			when (start) {
				is Start.Folder ->
					opened(
						Choosing.Exploring(),
						start.path,
						home,
						sidebar,
						search = search,
						listings = preferences.preferences.value.listings,
						zoom = zoomOf(preferences.preferences.value, Role.Explorer),
					).let { step ->
						chosen?.let {
							Step(step.state.copy(landing = Landing.On(RowId(it), choose = true)), step.effects)
						}
							?: step
					}
				is Start.Place ->
					openedIn(
						start.placed,
						home,
						sidebar,
						search,
						preferences.preferences.value.listings,
						zoomOf(preferences.preferences.value, Role.Explorer),
					)
				Start.System ->
					openedOnSystem(
						home,
						sidebar,
						search,
						preferences.preferences.value.listings,
						zoomOf(preferences.preferences.value, Role.Explorer),
					)
			}

		// What the window asks for is answered to it: the session is made after what it asks through, so it is found
		// through this once it is.
		val asking = CompletableDeferred<Session>()

		val browsing =
			ExplorerWindow.Browsing(
				id,
				Session(first, services, window, requests = {
					ask(it) { event -> asking.getCompleted().send(event) }
				}),
				window,
				activation,
			)

		asking.complete(browsing.session)

		making.opened(browsing)
		// A browser ends as a picker is cancelled: closed (Ctrl+W, or its menu), it is closed here.
		window.launch {
			browsing.session.outcome.await()
			close(browsing)
		}
	}

	/**
	 * What a window asked for, opened. What is asked for an answer (a folder to choose) is answered through [reply],
	 * to the window that asked.
	 */
	fun ask(request: Request, reply: (BrowserEvent) -> Unit = {}) {
		when (request) {
			is Request.NewWindow -> browse(request.start)
			is Request.PickFolder -> pick(request, reply)
			is Request.Properties -> opening { ExplorerWindow.Properties(it, request.paths) }
			is Request.OpenWith ->
				opening {
					val types = request.paths.map(desktop.types::of).distinct()

					ExplorerWindow.OpenWith(
						it,
						// With no one type to be the default for, the chooser offers no default to set.
						Choice.Open(request.paths, types.singleOrNull().takeIf { request.settable }),
					)
				}
			Request.Preferences -> preferences(null)
			Request.KeyBindings -> preferences(Page.Bindings)
		}
	}

	/**
	 * The launcher shown, or, where it is shown already, put away: asked for by one key, it comes and goes by that
	 * key. What it asks for (a folder's window, its settings, itself closed) is done here, on the windows' thread,
	 * whichever thread it asks from.
	 */
	fun launch(activation: String? = null) {
		launcherShown()?.let { return close(it) }

		val making = Making()
		val (id, window) = making

		val host =
			object : LauncherHost {
				private fun doing(work: () -> Unit) {
					scope.launch { work() }
				}

				override fun close() =
					doing {
						open.value.firstOrNull { it.id == id }?.let { this@Explorer.close(it) }
					}

				override fun settings() = doing { launcherSettings() }

				override fun bindings() = doing { launcherSettings(LauncherPage.Bindings) }

				override fun system() = doing { this@Explorer.browse(Start.System) }

				override fun browse(folder: Path) = doing { this@Explorer.browse(folder) }

				override fun reveal(path: Path) =
					doing {
						path.parent?.let {
							this@Explorer.browse(it, chosen = path.fileName.toString())
						} ?: this@Explorer.browse(path)
					}

				override fun copy(text: String) = doing { toClipboard(text) }

				override fun openWith(path: Path) = doing { ask(Request.OpenWith(listOf(path))) }

				override fun properties(path: Path) = doing { ask(Request.Properties(listOf(path))) }

				override fun hide(id: String) {
					preferences.update {
						copy(launcher = launcher.copy(hidden = launcher.hidden + id))
					}
				}
			}

		val session =
			LauncherSession(
				LauncherServices(
					desktop,
					finder,
					launches,
					home,
					terminal = { preferences.preferences.value.terminal },
					disk = disk,
					windows = ::openWindows,
					report = report,
				),
				host,
				window,
				preferences.preferences.value.launcher,
				aliasesOf(preferences.preferences.value.aliases),
			)

		making.opened(ExplorerWindow.Launching(id, session, window, activation))
		// Its settings changed while it is shown (their window is another), it is as they now say. What they are as
		// this begins is said too: they may have changed since the session was made with them.
		window.launch {
			preferences.preferences
				.map { it.launcher }
				.distinctUntilChanged()
				.collect { session.send(LauncherEvent.PreferencesChanged(it)) }
		}
		window.launch {
			preferences.preferences
				.map { it.aliases }
				.distinctUntilChanged()
				.collect { session.send(LauncherEvent.AliasesChanged(aliasesOf(it))) }
		}
	}

	/** The launcher's one window, where it is shown: the launcher, or a dmenu in its place. */
	private fun launcherShown(): ExplorerWindow? =
		open.value.firstOrNull { it is ExplorerWindow.Launching || it is ExplorerWindow.Dmenu }

	/**
	 * [asked] shown in the launcher's window, in place of whatever it showed, until it is answered: by a line chosen,
	 * by what was typed, or by its being put away, which is also what closing its window any other way comes to (the
	 * launcher asked for, another dmenu, the window failing).
	 */
	suspend fun dmenu(asked: Dmenu, activation: String? = null): DmenuAnswer {
		launcherShown()?.let(::close)

		val making = Making()
		val (id, window) = making
		val session = DmenuSession(asked, window, preferences.preferences.value.launcher)
		val showing = ExplorerWindow.Dmenu(id, session, window, activation)

		making.opened(showing)
		window.launch {
			preferences.preferences
				.map { it.launcher }
				.distinctUntilChanged()
				.collect { session.send(DmenuEvent.PreferencesChanged(it)) }
		}

		// Waited for in the window's own scope, which ends with the window: one closed with nothing chosen has
		// no answer of its own, and is not waited for for ever.
		val ending = window.async { session.answer.await() }

		return try {
			ending.await()
		} catch (stopped: CancellationException) {
			// Cancelled from outside, as it is; otherwise it was the window that went.
			currentCoroutineContext().ensureActive()

			DmenuAnswer.Dismissed
		} finally {
			close(showing)
		}
	}

	/** The preferences, in their window, at [page] where one is asked for: the one there is, raised, where it is open. */
	private fun preferences(page: Page?) =
		raising({ ExplorerWindow.Settings(it, page = page) }) {
			it.copy(raised = it.raised + 1, page = page)
		}

	/**
	 * The launcher's settings, in their window: the one there is, raised, where it is open already. With [page],
	 * the window is at that page: opened there, or, open already, turned to it.
	 */
	fun launcherSettings(page: LauncherPage? = null) =
		raising({ ExplorerWindow.LauncherSettings(it, asked = page) }) {
			it.copy(raised = it.raised + 1, asked = page)
		}

	/** The one window of its kind there is, opened by [make], or, where it is open, changed by [raise] to be raised. */
	private inline fun <reified W : ExplorerWindow> raising(
		crossinline make: (WindowId) -> W,
		crossinline raise: (W) -> W,
	) = open.update { windows ->
		val held = windows.filterIsInstance<W>().firstOrNull()

		if (held == null)
			windows.plusElement(make(WindowId(ids.incrementAndGet())))
		else
			windows.map { if (it == held) raise(held) else it }
	}

	/** A picker of folders opened for [request]: what it chooses, or that it was cancelled, is the answer. */
	private fun pick(request: Request.PickFolder, reply: (BrowserEvent) -> Unit) {
		val making = Making()
		val (id, window) = making

		val picking =
			ExplorerWindow.Picking(
				id,
				request.title,
				Session(
					opened(
						Choosing.Folder(),
						request.start,
						home,
						sidebarOf(desktop.places, home, preferences.preferences.value),
						title = request.title,
						listings = preferences.preferences.value.listings,
						zoom = zoomOf(preferences.preferences.value, Role.Picker),
						search = search,
					),
					services,
					window,
				),
				window,
			)

		making.opened(picking)
		window.launch {
			// Answered however the picker ends: one whose window failed chose nothing, and whoever asked is told so
			// rather than left waiting.
			val chosen =
				try {
					(picking.session.outcome.await() as? Outcome.Chosen)?.paths?.firstOrNull()
				} catch (stopped: CancellationException) {
					null
				}

			reply(BrowserEvent.Answered(chosen))
			close(picking)
		}
	}

	/**
	 * A picker for another application, as [asking] says, in a window of its own until it ends: what it ends with,
	 * and the filter in use as it did. Cancelled from outside (the application went, or the portal took it away), its
	 * window is closed as it is. Where its window fails before it ends, there is no answer: [PickerFailed] says why.
	 */
	suspend fun choose(asking: Asking): Answered {
		val making = Making()
		val (id, window) = making

		val first =
			opened(
				asking.choosing,
				asking.folder,
				home,
				sidebarOf(desktop.places, home, preferences.preferences.value),
				title = asking.title,
				filters = asking.filters,
				listings = preferences.preferences.value.listings,
				zoom = zoomOf(preferences.preferences.value, Role.Picker),
				search = search,
				filter = asking.filter,
			).let { step ->
				Step(
					step.state.copy(acceptLabel = asking.acceptLabel, asker = asking.asker),
					step.effects,
				)
			}

		val picking =
			ExplorerWindow.Picking(
				id,
				asking.title,
				Session(first, services, window),
				window,
				asking.parent,
			)

		making.opened(picking)

		// Waited for in the window's own scope, which ends with the window: a picker whose window failed never ends,
		// and its answer is not waited for for ever.
		val ending = window.async { picking.session.outcome.await() }

		return try {
			Answered(ending.await(), picking.session.state.value.typeFilter)
		} catch (stopped: CancellationException) {
			// Cancelled from outside, as it is; otherwise it was the window that stopped, and what it failed with,
			// where it failed, is what its work was stopped for ([failed]).
			currentCoroutineContext().ensureActive()

			throw PickerFailed(
				generateSequence<Throwable>(stopped) { it.cause }
					.firstOrNull { it !is CancellationException },
			)
		} finally {
			close(picking)
		}
	}

	/** The application for [type] chosen anew, from its properties. */
	fun chooseDefault(type: MimeType) =
		opening { ExplorerWindow.OpenWith(it, Choice.Default(type)) }

	/** [application] made the default for [type]. */
	fun setDefault(type: MimeType, application: Application) {
		desktop.applications.setDefault(type, application)
		defaultsSet.update { it + 1 }
	}

	/** [window] closed, and what it was doing stopped. */
	fun close(window: ExplorerWindow) {
		open.update { windows -> windows.filterNot { it.id == window.id } }

		when (window) {
			is ExplorerWindow.Browsing -> window.scope.cancel()
			is ExplorerWindow.Picking -> window.scope.cancel()
			is ExplorerWindow.Launching -> window.scope.cancel()
			is ExplorerWindow.Dmenu -> window.scope.cancel()
			else -> Unit
		}
	}

	/**
	 * [window] failed with [failure], in drawing it or in what it was doing: it is closed and what it was doing
	 * stopped, the failure is reported, and a window that says what happened is opened in its place, from which a
	 * browser can be opened again where it was. Every other window is left as it is.
	 */
	fun failed(window: ExplorerWindow, failure: Throwable) = failed(window.id, failure)

	private fun failed(id: WindowId, failure: Throwable) {
		val window = open.value.firstOrNull { it.id == id }
		val what = window?.let(::describe) ?: "a window that had closed"

		report("$what failed", failure)
		if (window == null)
			return

		// A picker's work is stopped for the failure, which is how whoever waits for its answer comes to know of it.
		if (window is ExplorerWindow.Picking)
			window.scope.cancel(CancellationException("the picker's window failed", failure))
		close(window)
		// The window that says so failing in its turn is said on standard error alone.
		if (window !is ExplorerWindow.Failed)
			opening {
				ExplorerWindow.Failed(it, titleOf(window), Failure.of(failure), againFor(window))
			}
	}

	/** Where a browser is opened again, for [window] that failed: where it was browsing, or its home. */
	private fun againFor(window: ExplorerWindow): Start? =
		(window as? ExplorerWindow.Browsing)?.let {
			Start.Folder(it.session.state.value.folder ?: home)
		}

	private fun opening(window: (WindowId) -> ExplorerWindow) =
		open.update {
			it.plusElement(window(WindowId(ids.incrementAndGet())))
		}

	/** Whether a window being made is in the list of those open yet. */
	private sealed interface Birth {
		/** Not yet: and what its work has already failed with, if it has. */
		data class Unborn(
			val failure: Throwable?,
		) : Birth

		data object Born : Birth
	}

	/**
	 * A window about to be made: its [id], and the [scope] its work is done in. The work stops with the window, one
	 * part of it failing stops no other, and what fails in it with nobody waiting for it is the window's failure. A
	 * window's work can fail before the window is in the list (a session starts as it is made): the failure is kept
	 * here, with the window being made and nowhere else, until the window is in the list ([opened]), and is its
	 * failure then.
	 */
	private inner class Making {
		val id = WindowId(ids.incrementAndGet())

		// Changed in one atomic step that also says what it was (getAndUpdate, getAndSet): an AtomicReference's own.
		private val birth = AtomicReference<Birth>(Birth.Unborn(null))

		val scope =
			CoroutineScope(
				this@Explorer.scope.coroutineContext +
					SupervisorJob(this@Explorer.scope.coroutineContext[Job]) +
					CoroutineExceptionHandler { _, failure ->
						val was =
							birth.getAndUpdate {
								if (it is Birth.Unborn)
									Birth.Unborn(it.failure ?: failure)
								else
									it
							}

						if (was is Birth.Born)
							failed(id, failure)
					},
			)

		operator fun component1(): WindowId = id

		operator fun component2(): CoroutineScope = scope

		/** [window], made, in the list of those open; and failed at once, where its work already has. */
		fun opened(window: ExplorerWindow) {
			open.update { it.plusElement(window) }
			(birth.getAndSet(Birth.Born) as? Birth.Unborn)?.failure?.let { failed(id, it) }
		}
	}

	companion object {
		/** What [window] is called, as its title bar would say. */
		fun titleOf(window: ExplorerWindow): String =
			when (window) {
				is ExplorerWindow.Browsing ->
					when (val at = window.session.state.value.at) {
						is Location.Folder -> (at.path.fileName ?: at.path).toString()
						is Location.Results -> "Search"
						is Location.Place -> at.placed.title
						Location.Trash -> "Trash"
						is Location.System -> "System"
						Location.Recents -> "Recents"
					}
				is ExplorerWindow.Picking -> window.title
				is ExplorerWindow.Properties -> propertiesTitleOf(window.paths)
				is ExplorerWindow.OpenWith -> openWithTitleOf(window.choice)
				is ExplorerWindow.Settings -> "Preferences"
				is ExplorerWindow.Launching -> "Launcher"
				is ExplorerWindow.Dmenu -> "Menu"
				is ExplorerWindow.LauncherSettings -> "Launcher settings"
				is ExplorerWindow.Failed -> "${window.what} failed"
			}

		/** What [window] is, as a report of its failure says. */
		private fun describe(window: ExplorerWindow): String =
			when (window) {
				is ExplorerWindow.Browsing ->
					"the window on ${window.session.state.value.folder ?: titleOf(window)}"
				is ExplorerWindow.Picking -> "the picker “${window.title}”"
				is ExplorerWindow.Launching -> "the launcher"
				is ExplorerWindow.Dmenu -> "the launcher's menu"
				is ExplorerWindow.Failed -> "the window that says ${window.what} failed"
				else -> "the window “${titleOf(window)}”"
			}
	}
}
