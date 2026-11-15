package prism.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import prism.desktop.Launched
import prism.desktop.Opening
import prism.window.ClipOffer
import prism.window.SystemClipboard

/**
 * A browser running (a picker, or an explorer): its states, folded from its first [Step] by the reducer over every
 * input in the order they come, and its effects carried out as each step asks for them. What is asked of the disk,
 * the index, the desktop or the clipboard is done on [io] against [services], and each answer joins the inputs; a
 * request for a window is handed to [requests], the application's; a finish completes [outcome], and tells the finder
 * what was picked. The fold is the only thing that holds a state, and it holds each one only until the next.
 *
 * The searches the search's window asks for as the query is typed are run one at a time, the latest only: one asked
 * for while another runs takes the place of any waiting, and the answer of one overtaken is dropped.
 *
 * The preferences are the user's, every window's: when they change (a place pinned here, or in another window), the
 * sidebar is made again from them, here as everywhere.
 */
class Session(
	first: Step,
	private val services: Services,
	private val scope: CoroutineScope,
	private val io: CoroutineDispatcher = Dispatchers.IO,
	private val clipboard: (String) -> Unit = ::toClipboard,
	private val requests: (Request) -> Unit = {},
	private val clock: () -> Instant = Instant::now,
) {
	/** A session with only a disk and an index: a picker that opens nothing and has nothing pinned. */
	constructor(
		first: Step,
		disk: Disk,
		scope: CoroutineScope,
		io: CoroutineDispatcher = Dispatchers.IO,
		clipboard: (String) -> Unit = ::toClipboard,
		finder: Finder? = null,
		clock: () -> Instant = Instant::now,
	) : this(first, Services(disk, finder), scope, io, clipboard, {}, clock)

	private val inputs = Channel<Input>(Channel.UNLIMITED)

	// How to stop each task under way, once its command has started; and the tasks asked to stop before theirs had.
	private val stops = ConcurrentHashMap<Task, () -> Unit>()
	private val stopped: MutableSet<Task> = ConcurrentHashMap.newKeySet()

	// The folders made for this browser, to open a place's entries from: gone when it is.
	private val temps: MutableSet<java.nio.file.Path> = ConcurrentHashMap.newKeySet()
	private val ending = CompletableDeferred<Outcome>()

	// The latest search asked for the window, waiting while one runs: a newer one takes its place, and the same one
	// asked for again (the window opened again) is searched again. Kept for whoever runs them to find when it
	// starts: one asked for before then (a session's first event) would otherwise be asked of nobody.
	private val previews =
		MutableSharedFlow<Effect.Search>(
			replay = 1,
			onBufferOverflow = DROP_OLDEST,
		)

	/** How the picker ended, once it has. */
	val outcome: Deferred<Outcome> get() = ending

	// The state as it last was made, for whatever carries out an effect to read: set before the state's effects are
	// begun, and there from the first, which [state] is not while this is being made.
	private val latest = AtomicReference(first.state)

	val state: StateFlow<BrowserState> =
		inputs
			.receiveAsFlow()
			.runningFold(first) { last, input -> step(last.state, input) }
			.onEach {
				latest.set(it.state)
				it.effects.forEach(::perform)
			}.map { it.state }
			.stateIn(scope, SharingStarted.Eagerly, first.state)

	init {
		val home = first.state.home
		val places = services.desktop?.places

		if (places != null)
			scope.launch(io) {
				services.preferences.preferences.collect {
					inputs.send(Answer.Placed(sidebarOf(places, home, it)))
				}
			}

		// The search aliases, as the preferences now define them.
		scope.launch(io) {
			services.preferences.preferences
				.map { it.aliases }
				.distinctUntilChanged()
				.collect { inputs.send(Answer.Aliased(aliasesOf(it))) }
		}

		// How listings look, as the preferences now say: a window takes up what they changed.
		scope.launch(io) {
			services.preferences.preferences
				.map { it.listings }
				.distinctUntilChanged()
				.collect { inputs.send(Answer.Relisted(it)) }
		}

		// The user's actions and places, with prism's own, and the terminal chosen: read again whenever what the
		// preferences say of them changes.
		scope.launch(io) {
			services.preferences.preferences
				.map { Triple(it.written, it.menu, it.terminal) }
				.distinctUntilChanged()
				.collect { (written, menu, terminal) ->
					inputs.send(
						Answer.Configured(
							// Read for this machine: each action as the first of its ways whose programs are here.
							Actions.read(
								listOf(Actions.SHIPPED, written),
								menu.hidden.toSet(),
								services.world::installed,
							),
							terminal,
							menu,
						),
					)
				}
		}

		// The folder shown is watched, whichever it is, and read again a moment after it changes: a moment, so that
		// many changes made at once are one reading, and then no sooner than a little later, so that a folder written
		// to without pause is not read without pause.
		scope.launch(io) {
			state.map { it.folder }.distinctUntilChanged().collectLatest { folder ->
				if (folder == null)
					return@collectLatest

				val touched = Channel<Unit>(Channel.CONFLATED)
				val watch = services.watcher.watch(folder) { touched.trySend(Unit) }

				try {
					for (touch in touched) {
						delay(SETTLES_MILLIS)
						inputs.send(Answer.Touched(folder))
						delay(RESTS_MILLIS)
					}
				} finally {
					watch.close()
				}
			}
		}

		// The System page, while it is shown, is read again every few seconds: a drive plugged in, a share mounted, a
		// file system filling up, are seen without asking.
		scope.launch(io) {
			state.map { it.system != null }.distinctUntilChanged().collectLatest { shown ->
				// Read until it is left, or until prism goes wrong reading it: that is said once, not every few seconds.
				while (shown) {
					delay(STORAGE_MILLIS)

					val answer = answerOf(Effect.ReadStorage)

					inputs.send(answer)
					if (answer is Answer.Broke)
						break
				}
			}
		}

		// A browser done with (its window closed) leaves nothing behind it, and nothing running.
		// Done as the browser is cancelled, and not once its work has ended: its work is waiting on those commands.
		scope.launch {
			try {
				awaitCancellation()
			} finally {
				stops.values.forEach { it() }
				temps.forEach(services.temps::remove)
			}
		}

		scope.launch(io) {
			previews.collectLatest { search ->
				val answer = answerOf(search)

				// Overtaken while it ran: a later search's answer is the one wanted.
				ensureActive()
				inputs.send(answer)
			}
		}
	}

	/** What the conditions of the user's actions are decided by. */
	val world: World get() = services.world

	/** Whether [a] and [b] are on one volume, as the disk says: what a drop with no key held is told by. */
	fun sameVolume(a: java.nio.file.Path, b: java.nio.file.Path): Boolean =
		services.disk.sameVolume(a, b)

	private companion object {
		/** How long after a folder changes it is read again: long enough for what changes together to have. */
		const val SETTLES_MILLIS = 120L

		/** How long after reading a changed folder before it is read again for changing still. */
		const val RESTS_MILLIS = 400L

		/** How often the System page, while it is shown, is read again. */
		const val STORAGE_MILLIS = 3000L
	}

	/** What the user did, to be folded in after whatever came before it. */
	fun send(event: BrowserEvent) {
		inputs.trySend(event)
	}

	private fun perform(effect: Effect) {
		when (effect) {
			is Effect.Search ->
				if (effect.purpose == Purpose.Preview)
					previews.tryEmit(effect)
				else
					scope.launch(io) { inputs.send(answerOf(effect)) }
			is Effect.Asking -> scope.launch(io) { inputs.send(answerOf(effect)) }
			is Effect.Copy -> clipboard(effect.text)
			is Effect.Clip ->
				scope.launch(io) {
					services.carried.set(null)
					services.clipboard.put(effect.clip)
				}
			is Effect.Carry -> services.carried.set(effect.carried)
			is Effect.Stop -> stops[effect.task]?.invoke() ?: stopped.add(effect.task).let {}
			is Effect.Arrange ->
				scope.launch(io) {
					services.preferences.update { it.arranging(effect.quick) }
				}
			is Effect.Alias ->
				scope.launch(io) {
					// Added to the aliases as they are now, which a settings window may have changed meanwhile:
					// where it is no longer one among them, they are left as they are.
					services.preferences.update {
						it.copy(
							aliases = aliasAdded(it.aliases, effect.name, effect.query) ?: it.aliases,
						)
					}
				}
			is Effect.Ask -> requests(effect.request)
			is Effect.Finish -> {
				(effect.outcome as? Outcome.Chosen)?.let { chosen ->
					services.finder?.let { scope.launch(io) { it.picked(chosen.paths, clock()) } }

					// A picker answered: the folders it was answered from are the latest used, by whoever asked.
					val state = latest.get()
					val folders = foldersUsed(chosen.paths, state.choosing)

					if (folders.isNotEmpty())
						scope.launch(io) { services.recents.used(folders, state.asker?.app, clock()) }
				}
				ending.complete(effect.outcome)
			}
		}
	}

	/** prism went wrong answering [asking]: [failed] said in full, and the answer that there is none. */
	private fun broke(asking: Effect.Asking, failed: Exception): Answer {
		services.report(failingOf(asking), failed)

		return Answer.Broke(asking, Fault.of(failed))
	}

	/**
	 * Who watches [task]'s command: this session, which keeps how to stop it while it runs (and stops it at once where
	 * that was already asked), and is told what it writes. A listing's output is its entries, which are not for
	 * reading; what it writes to standard error is.
	 */
	private fun watching(task: Task): Watch =
		object : Watch {
			override fun started(stop: () -> Unit) {
				stops[task] = stop
				if (stopped.remove(task))
					stop()
			}

			override fun said(line: String, error: Boolean) {
				if (error || task is Task.Running)
					inputs.trySend(Answer.Said(task, line))
			}
		}

	/**
	 * The answer to [asking]. What the system refused outright, thrown past whatever answers (an I/O failure), is
	 * that effect's own failure, in the system's words, where it has one to say it with. Anything else thrown is a
	 * mistake of prism's: said in full where prism says what goes wrong, and answered as that ([Answer.Broke]), never
	 * as an answer made up to look like one.
	 */
	private fun answerOf(asking: Effect.Asking): Answer =
		try {
			services.answer(asking, ::watching).also {
				if (it is Answer.TempMade)
					temps.add(it.folder)

				// A folder's rows, sorted and narrowed as the window now shows a folder's, here where it was read:
				// left to the first time they are drawn, a large folder is sorted on the windows' thread, which
				// draws nothing while it does. The listing keeps them; arranged otherwise by then, it arranges again.
				val read = (it as? Answer.Arrived)?.read as? Listing.Shown

				read?.rows(latest.get().arrangement)?.ready()
			}
		} catch (stopped: kotlinx.coroutines.CancellationException) {
			throw stopped
		} catch (failed: java.io.IOException) {
			refusedOf(asking, failed.message ?: failed.toString()) ?: broke(asking, failed)
		} catch (failed: RuntimeException) {
			(failed as? java.io.UncheckedIOException)
				?.let { refusedOf(asking, it.message ?: it.toString()) }
				?: broke(asking, failed)
		} finally {
			when (asking) {
				is Effect.Run -> Task.Running(asking.job)
				is Effect.ListPlace -> Task.Listing(asking.placed)
				else -> null
			}?.let {
				stops.remove(it)
				stopped.remove(it)
			}
		}
}

/**
 * What says the system refused [asking], for [why]: the failure each effect that has one answers with. None for an
 * effect whose answer has no way to say so (what it gives back is what was read, or nothing): a failure of one of
 * those is not one prism knows how to tell, and is told as its own mistake.
 */
private fun refusedOf(asking: Effect.Asking, why: String): Answer? =
	when (asking) {
		is Effect.Read -> Answer.Arrived(asking.folder, Listing.Unreadable(why))
		is Effect.Make -> Answer.Changed(asking.change, ChangeResult.Failed(why), asking.doing)
		is Effect.Erase -> Answer.Erased(asking.erasing, why)
		Effect.ReadTrash -> Answer.TrashRead(Listing.Unreadable(why))
		is Effect.Open ->
			Answer.Opened(
				Opening(listOf(Launched.Failed(countOf(asking.paths), why)), emptyList()),
			)
		is Effect.Terminal -> Answer.Started(Launched.Failed("a terminal", why))
		is Effect.ListPlace -> Answer.Listed(asking.placed, PlaceListing.Failed(why))
		is Effect.Run -> Answer.Ran(asking.job, why)
		is Effect.Launch -> Answer.Started(Launched.Failed(asking.job.label, why))
		// With no folder to bring them out into, nothing is opened: the action is let go.
		is Effect.Scratch -> Answer.Ran(Job(asking.pending.action.label, emptyList()), why)
		is Effect.Temp -> Answer.Ran(Job(asking.carried.pending.action.label, emptyList()), why)
		is Effect.Look, Effect.ReadStorage, is Effect.Search, is Effect.Stat, is Effect.Born,
		is Effect.Order, is Effect.ReadClip, is Effect.ReadRecents,
		-> null
	}

/** [text] on the system's clipboard, where there is one: a picker with no display has none, and copies nothing. */
fun toClipboard(text: String) {
	SystemClipboard.put(ClipOffer.text(text))
}

/**
 * The browser [session] runs, drawn: its state as it changes, and what the user does sent to it; the mouse's buttons
 * ([mouseChords]) bound as the keys are.
 */
@Composable
fun SessionBrowser(
	session: Session,
	modifier: Modifier = Modifier,
	mouseChords: kotlinx.coroutines.flow.Flow<Chord> = kotlinx.coroutines.flow.emptyFlow(),
) {
	val state by session.state.collectAsState()

	CompositionLocalProvider(
		LocalVolumes provides session::sameVolume,
		LocalWorld provides session.world,
	) {
		FileBrowser(state, modifier, mouseChords, session::send)
	}
}
