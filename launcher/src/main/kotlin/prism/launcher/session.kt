package prism.launcher

import java.nio.file.Path
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import prism.browser.Category
import prism.browser.Change
import prism.browser.ChangeResult
import prism.browser.Disk
import prism.browser.Finder
import prism.browser.categoryOf
import prism.browser.folderOf
import prism.browser.typeOf
import prism.desktop.Desktop
import prism.desktop.Launched
import prism.desktop.TerminalChoice
import prism.index.Results
import prism.index.Searched
import prism.index.query.Aliases
import prism.index.query.Query
import prism.preferences.LauncherPreferences
import prism.shared.Guarded
import prism.window.OpenWindows

/*
 * The launcher running: its state as it changes, and what its reducer asks for carried out. Applications are started
 * and the index searched off the thread the window is drawn on; what comes of each is sent back as an event, so the
 * state is only ever changed by the reducer, one event at a time.
 */

/** What the launcher asks of whoever shows it: its own window closed, and other windows opened. */
interface LauncherHost {
	/** The launcher's window closed. */
	fun close()

	/** The launcher's settings, in a window of their own. */
	fun settings()

	/** The launcher's settings, at what its keys do: their window opened there, or, open already, turned to it. */
	fun bindings()

	/** An explorer's window on the page of the system's drives. */
	fun system()

	/** An explorer's window on [folder]. */
	fun browse(folder: Path)

	/** An explorer's window on the folder [path] is in, with it chosen. */
	fun reveal(path: Path)

	/** [text] put on the clipboard. */
	fun copy(text: String)

	/** The file or folder at [path] put on the clipboard as a file, copied. */
	fun copyFile(path: Path)

	/** A window to choose what [path] is opened with. */
	fun openWith(path: Path)

	/** A window of [path]'s properties. */
	fun properties(path: Path)

	/** The application the desktop calls [id] left out of the launcher from now on, as its settings then say. */
	fun hide(id: String)
}

/** What the launcher works with: the desktop's applications, the index (where there is one), and what it started. */
class LauncherServices(
	val desktop: Desktop,
	val finder: Finder?,
	val launches: LaunchStore,
	val home: Path,
	/** The terminal an application that runs in one is started in, as the user has it now. */
	val terminal: () -> TerminalChoice = { TerminalChoice.Found },
	val clock: Clock = Clock.systemUTC(),
	/** What moves a file to the trash: the disk as a browser changes it. None, where nothing here may change it. */
	val disk: Disk? = null,
	/**
	 * What the desktop tells of the windows applications have open, asked as the launcher is shown: what an
	 * application that is running is marked by, and brought forward through. Nothing, unless told otherwise.
	 */
	val windows: () -> OpenWindows = { OpenWindows.Unavailable },
	/** Where what blocks is done: starting programs, searching, reading what is installed. */
	val io: CoroutineDispatcher = Dispatchers.IO,
	/**
	 * Where what goes wrong that is prism's own mistake is said in full, with what was being done: standard error,
	 * unless told otherwise. What the launcher shows of it is a line.
	 */
	val report: (String, Throwable) -> Unit = { doing, failure ->
		System.err.println("prism: $doing failed: ${failure.stackTraceToString().trimEnd()}")
	},
)

/** A launcher open: its [state], changed by what is [send]t to it, its work done in [scope]. */
class LauncherSession(
	private val services: LauncherServices,
	private val host: LauncherHost,
	private val scope: CoroutineScope,
	preferences: LauncherPreferences = LauncherPreferences(),
	aliases: Aliases = Aliases.NONE,
) {
	/** The windows open, as the desktop tells of them now; where it tells of none, nothing here asks for any. */
	private val windows: OpenWindows = services.windows()

	private val held =
		MutableStateFlow(
			LauncherState(
				installed =
					Installed.of(
						services.desktop.applications.all(),
						when (windows) {
							OpenWindows.Unavailable -> emptyList()
							is OpenWindows.Listed -> windows.now()
						},
					),
				launches = services.launches.launches,
				preferences = preferences,
				aliases = aliases,
				searchable = services.finder != null,
				now = services.clock.instant(),
			),
		)

	val state: StateFlow<LauncherState> = held.asStateFlow()

	/**
	 * What one event at a time changes: the state ([held], which anything reads), and the search under way, which
	 * the next takes the place of. An event is taken in, and a search begun, as the one thread that has this.
	 */
	private class Turn {
		var searching: Job? = null
	}

	private val turn = Guarded(Turn())

	init {
		// What is installed is shown as it was last read, at once, and read again behind: an application installed
		// since the daemon started is there a moment later.
		scope.launch(services.io) {
			services.desktop.applications.reread()
			send(LauncherEvent.ApplicationsRead(services.desktop.applications.all()))
		}
	}

	/** [event] taken in: the state changed as the reducer says, and what it asks for done. */
	fun send(event: LauncherEvent) {
		val effects =
			turn.guarded {
				step(held.value, event).also { held.value = it.state }.effects
			}

		effects.forEach(::carryOut)
	}

	private fun carryOut(effect: LauncherEffect) {
		when (effect) {
			is LauncherEffect.Search -> search(effect.text, effect.query, effect.most)
			is LauncherEffect.Start ->
				scope.launch(services.io) {
					send(
						LauncherEvent.Started(
							effect.entry,
							services.clock.instant(),
							failed = started(effect.entry),
						),
					)
				}
			is LauncherEffect.Reveal -> host.reveal(effect.path)
			is LauncherEffect.Copy -> host.copy(effect.text)
			is LauncherEffect.CopyFile -> host.copyFile(effect.path)
			is LauncherEffect.OpenWith -> host.openWith(effect.path)
			is LauncherEffect.Properties -> host.properties(effect.path)
			is LauncherEffect.Terminal ->
				scope.launch(services.io) {
					send(
						LauncherEvent.Acted(
							failureOf(
								services.desktop.launcher.terminal(effect.folder, services.terminal()),
							)?.let(LauncherNotice::Failed),
						),
					)
				}
			is LauncherEffect.Trash ->
				scope.launch(services.io) { send(LauncherEvent.Acted(trashed(effect.path))) }
			is LauncherEffect.Focus ->
				when (windows) {
					// No window was listed, so none was offered to be brought forward.
					OpenWindows.Unavailable -> Unit
					is OpenWindows.Listed -> windows.focus(effect.window)
				}
			is LauncherEffect.Hide -> {
				host.hide(effect.application.id)
				send(
					LauncherEvent.Acted(
						LauncherNotice.Done(
							"“${effect.application.name}” is hidden. Show it again in Settings.",
						),
					),
				)
			}
			LauncherEffect.Settings -> host.settings()
			LauncherEffect.Bindings -> host.bindings()
			LauncherEffect.System -> host.system()
			LauncherEffect.Explore -> host.browse(services.home)
			is LauncherEffect.Remember -> services.launches.used(effect.key, effect.at)
			LauncherEffect.Close -> host.close()
		}
	}

	private fun search(text: String, query: Query, most: Int) {
		val finder = services.finder ?: return

		turn.guarded {
			searching?.cancel()
			searching =
				scope.launch(Dispatchers.Default) {
					// A search that throws is a mistake of prism's: reported, and said in the launcher, which goes
					// on finding applications; it is not the launcher's window's to fail for.
					send(
						try {
							val found =
								when (val searched = finder.find(query)) {
									is Searched.Found -> searched.results
									is Searched.Refused -> Results.NONE
								}

							LauncherEvent.FilesArrived(
								text,
								List(minOf(most, found.size)) { fileOf(found, it) },
								found.size,
							)
						} catch (stopped: CancellationException) {
							throw stopped
						} catch (failed: RuntimeException) {
							services.report("searching for “$text”", failed)

							LauncherEvent.SearchFailed(text)
						},
					)
				}
		}
	}

	/** The [at]th of [found], as the launcher lists it. */
	private fun fileOf(found: Results, at: Int): Entry.File {
		val name = found.nameOf(at)
		val path = found[at].where.let { it.path ?: Path.of(it.toString()) }

		val category =
			if (found.kindOf(at).isDirectory)
				Category.Folder
			else
				categoryOf(name)

		return Entry.File(path, category, folderOf(path, services.home), typeOf(name, category))
	}

	/** [entry] started or opened; why not, where it could not be. */
	private fun started(entry: Entry): String? {
		val launcher = services.desktop.launcher
		val terminal = services.terminal()

		return when (entry) {
			is Entry.App -> failureOf(launcher.launch(entry.application, emptyList(), terminal))
			is Entry.Action ->
				failureOf(
					launcher.launch(entry.application.doing(entry.action), emptyList(), terminal),
				)
			is Entry.File ->
				if (entry.isFolder) {
					host.browse(entry.path)
					null
				} else {
					val opening = services.desktop.open(listOf(entry.path), terminal)

					services.finder?.picked(listOf(entry.path), services.clock.instant())
					opening.failures.firstOrNull()?.let(::failureOf)
						?: "No application can open “${entry.title}”".takeIf {
							opening.unopened.isNotEmpty()
						}
				}
			is Entry.Command, is Entry.Prism, is Entry.Named -> null
		}
	}

	/** [path] moved to the trash, as a browser moves it there: what is said of how that went. */
	private fun trashed(path: Path): LauncherNotice {
		val name = path.fileName?.toString() ?: path.toString()
		val disk =
			services.disk
				?: return LauncherNotice.Failed("Moving to the trash isn't available here.")

		fun failed(why: String) =
			LauncherNotice.Failed("Couldn't move “$name” to the trash. $why")

		return when (val result = disk.make(Change.Trash(listOf(path)))) {
			is ChangeResult.Done -> LauncherNotice.Done("Moved “$name” to the trash.")
			is ChangeResult.Failed -> failed(result.why)
			is ChangeResult.Trashed ->
				when {
					result.failed != null -> failed(result.failed.orEmpty())
					result.nowhere.isNotEmpty() ->
						LauncherNotice.Failed("“$name” is on a drive with no trash. It was not deleted.")
					else -> LauncherNotice.Done("Moved “$name” to the trash.")
				}
		}
	}

	private fun failureOf(launched: Launched): String? =
		(launched as? Launched.Failed)?.let { "Couldn't start ${it.what}. ${it.why}" }
}
