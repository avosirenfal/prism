package prism.explorer

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import prism.browser.BrowserEvent
import prism.browser.Choosing
import prism.browser.Disk
import prism.browser.FileClipboard
import prism.browser.Finder
import prism.browser.FolderWatcher
import prism.browser.Landing
import prism.browser.Look
import prism.browser.Outcome
import prism.browser.Request
import prism.browser.RowId
import prism.browser.Search
import prism.browser.Services
import prism.browser.Session
import prism.browser.Start
import prism.browser.Step
import prism.browser.TypeFilter
import prism.browser.opened
import prism.browser.openedIn
import prism.browser.sidebarOf
import prism.desktop.Application
import prism.desktop.Desktop
import prism.desktop.MimeType
import prism.preferences.PreferenceStore

/*
 * The explorer: the browser as a file manager, in as many windows as the user opens. What it has open is one immutable
 * list of windows, each made from a request (a folder to browse, the properties of some files, an application to open
 * them with, the preferences) and gone when it is closed. The windows are drawn from that list (windows.kt), so the
 * list is all there is to know of what is open: the explorer run on its own (`prism explore`) ends when it is empty,
 * and the daemon, which will run the explorer for every application that asks, keeps it while it runs.
 */

/** A window the explorer has open, told apart from the others by [id]. */
sealed interface ExplorerWindow {
	val id: Int

	/** Files browsed: a browser's [session], whose work is done in [scope], which ends with the window. */
	class Browsing internal constructor(
		override val id: Int,
		val session: Session,
		internal val scope: CoroutineScope,
	) : ExplorerWindow

	/**
	 * A folder chosen for an action that asked for one: a picker's [session], titled [title], whose outcome is the
	 * answer, given to the window that asked.
	 */
	class Picking internal constructor(
		override val id: Int,
		val title: String,
		val session: Session,
		internal val scope: CoroutineScope,
		/** The X11 window of the application that asked, which this is a dialog of; none for the explorer's own. */
		val parent: Long? = null,
	) : ExplorerWindow

	/** What can be known of [paths], and their permissions to change. */
	data class Properties(
		override val id: Int,
		val paths: List<Path>,
	) : ExplorerWindow

	/** An application chosen, for [choice]. */
	data class OpenWith(
		override val id: Int,
		val choice: Choice,
	) : ExplorerWindow

	/** The preferences, of which there is one window; [raised] counts the times it was asked for again while open. */
	data class Settings(
		override val id: Int,
		val raised: Int = 0,
	) : ExplorerWindow
}

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
 * search's window starts in (one ready to search, or one that says why there is none).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Explorer(
	disk: Disk,
	finder: Finder?,
	val desktop: Desktop,
	clipboard: FileClipboard,
	val preferences: PreferenceStore,
	private val scope: CoroutineScope,
	val home: Path,
	private val search: Search,
	/** What says a folder shown has changed on disk, for its window to read it again. */
	watcher: FolderWatcher = FolderWatcher.NONE,
) {
	private val services =
		Services(disk, finder, desktop, clipboard, preferences, watcher = watcher)
	private val ids = AtomicInteger()
	private val open = MutableStateFlow<List<ExplorerWindow>>(emptyList())
	private val defaultsSet = MutableStateFlow(0)

	/** The windows open, in the order they opened. */
	val windows: StateFlow<List<ExplorerWindow>> = open.asStateFlow()

	/** How many defaults have been set: what shows an application a type opens with reads it again as this changes. */
	val defaults: StateFlow<Int> = defaultsSet.asStateFlow()

	/** A window browsing [folder]; with [chosen], the entry of that name chosen in it, the cursor on it. */
	fun browse(folder: Path, chosen: String? = null) = browse(Start.Folder(folder), chosen)

	/** A window browsing from [start]: a folder, or a place; in a folder, [chosen] chosen where it is given. */
	fun browse(start: Start, chosen: String? = null) {
		val window = scope.child()
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
						look = Look.of(preferences.preferences.value, picker = false),
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
						Look.of(preferences.preferences.value, picker = false),
					)
			}

		// What the window asks for is answered to it: the session is made after what it asks through, so it is found
		// through this once it is.
		val asking = CompletableDeferred<Session>()

		val browsing =
			ExplorerWindow.Browsing(
				ids.incrementAndGet(),
				Session(first, services, window, requests = {
					ask(it) { event -> asking.getCompleted().send(event) }
				}),
				window,
			)

		asking.complete(browsing.session)

		open.update { it.plusElement(browsing) }
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
			Request.Preferences ->
				open.update { windows ->
					val settings = windows.filterIsInstance<ExplorerWindow.Settings>().firstOrNull()

					if (settings == null)
						windows.plusElement(ExplorerWindow.Settings(ids.incrementAndGet()))
					else
						windows.map {
							if (it ==
								settings
							)
								settings.copy(raised = settings.raised + 1)
							else
								it
						}
				}
		}
	}

	/** A picker of folders opened for [request]: what it chooses, or that it was cancelled, is the answer. */
	private fun pick(request: Request.PickFolder, reply: (BrowserEvent) -> Unit) {
		val window = scope.child()

		val picking =
			ExplorerWindow.Picking(
				ids.incrementAndGet(),
				request.title,
				Session(
					opened(
						Choosing.Folder(),
						request.start,
						home,
						sidebarOf(desktop.places, home, preferences.preferences.value),
						title = request.title,
						look = Look.of(preferences.preferences.value, picker = true),
						search = search,
					),
					services,
					window,
				),
				window,
			)

		open.update { it.plusElement(picking) }
		window.launch {
			val outcome = picking.session.outcome.await()

			reply(BrowserEvent.Answered((outcome as? Outcome.Chosen)?.paths?.firstOrNull()))
			close(picking)
		}
	}

	/**
	 * A picker for another application, as [asking] says, in a window of its own until it ends: what it ends with,
	 * and the filter in use as it did. Cancelled from outside (the application went, or the portal took it away), its
	 * window is closed as it is.
	 */
	suspend fun choose(asking: Asking): Answered {
		val window = scope.child()

		val first =
			opened(
				asking.choosing,
				asking.folder,
				home,
				sidebarOf(desktop.places, home, preferences.preferences.value),
				title = asking.title,
				filters = asking.filters,
				look = Look.of(preferences.preferences.value, picker = true),
				search = search,
			).let { step ->
				Step(
					step.state.copy(
						acceptLabel = asking.acceptLabel,
						arrangement =
							step.state.arrangement.copy(
								filter = asking.filter?.takeIf { it in asking.filters } ?: asking.filters.first(),
							),
					),
					step.effects,
				)
			}

		val picking =
			ExplorerWindow.Picking(
				ids.incrementAndGet(),
				asking.title,
				Session(first, services, window),
				window,
				asking.parent,
			)

		open.update { it.plusElement(picking) }

		return try {
			val outcome = picking.session.outcome.await()

			Answered(outcome, picking.session.state.value.arrangement.filter)
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
			else -> Unit
		}
	}

	private fun opening(window: (Int) -> ExplorerWindow) =
		open.update {
			it.plusElement(window(ids.incrementAndGet()))
		}

	private fun CoroutineScope.child(): CoroutineScope =
		CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
}
