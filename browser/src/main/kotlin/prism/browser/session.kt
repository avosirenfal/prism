package prism.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import prism.desktop.Launched
import prism.desktop.Opening

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
	private val ending = CompletableDeferred<Outcome>()

	// The latest search asked for the window, waiting while one runs: a newer one takes its place, and the same one
	// asked for again (the window opened again) is searched again.
	private val previews =
		MutableSharedFlow<Effect.Search>(
			extraBufferCapacity = 1,
			onBufferOverflow = DROP_OLDEST,
		)

	/** How the picker ended, once it has. */
	val outcome: Deferred<Outcome> get() = ending

	val state: StateFlow<BrowserState> =
		inputs
			.receiveAsFlow()
			.runningFold(first) { last, input -> step(last.state, input) }
			.onEach { it.effects.forEach(::perform) }
			.map { it.state }
			.stateIn(scope, SharingStarted.Eagerly, first.state)

	init {
		val home = first.state.home
		val places = services.desktop?.places

		if (places != null)
			scope.launch(io) {
				services.preferences.preferences.collect {
					inputs.send(Answer.Placed(sidebarOf(places, home, it.pinned)))
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
			is Effect.Clip -> scope.launch(io) { services.clipboard.put(effect.clip) }
			is Effect.Pin ->
				scope.launch(io) {
					services.preferences.update { it.pinning(effect.paths) }
				}
			is Effect.Ask -> requests(effect.request)
			is Effect.Finish -> {
				(effect.outcome as? Outcome.Chosen)?.let { chosen ->
					services.finder?.let { scope.launch(io) { it.picked(chosen.paths, clock()) } }
				}
				ending.complete(effect.outcome)
			}
		}
	}

	/** The answer to [asking], or, where it failed outright, the answer that says nothing could be had. */
	private fun answerOf(asking: Effect.Asking): Answer =
		try {
			services.answer(asking)
		} catch (failed: RuntimeException) {
			val why = failed.message ?: failed.toString()

			when (asking) {
				is Effect.Read -> Answer.Arrived(asking.folder, Listing.Unreadable(why))
				is Effect.Look -> Answer.Looked(asking.lookup, emptyMap())
				is Effect.Make ->
					Answer.Changed(
						asking.change,
						ChangeResult.Failed(why),
						asking.doing,
					)
				is Effect.Search -> Answer.Searched(Finding.Refused(asking.text, why), asking.purpose)
				is Effect.Stat ->
					Answer.Stated(
						asking.matches,
						asking.places.associateWith {
							Stat.Read(null, null)
						},
					)
				is Effect.Order ->
					Answer.Ordered(asking.matches, asking.column, IntArray(asking.matches.size) { it })
				is Effect.Open ->
					Answer.Opened(
						Opening(listOf(Launched.Failed(countOf(asking.paths), why)), emptyList()),
					)
				is Effect.Terminal -> Answer.Started(Launched.Failed("a terminal", why))
				is Effect.ReadClip -> Answer.Clipped(asking.into, null)
			}
		}
}

/** [text] on the system's clipboard, where there is one: a picker with no display has none, and copies nothing. */
fun toClipboard(text: String) {
	try {
		Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
	} catch (headless: HeadlessException) {
		Unit
	} catch (busy: IllegalStateException) {
		Unit
	}
}

/**
 * The browser [session] runs, drawn: its state as it changes, and what the user does sent to it; the mouse's buttons
 * read from AWT ([mouseChords]) bound as the keys are.
 */
@Composable
fun SessionBrowser(
	session: Session,
	modifier: Modifier = Modifier,
	mouseChords: kotlinx.coroutines.flow.Flow<Chord> = kotlinx.coroutines.flow.emptyFlow(),
) {
	val state by session.state.collectAsState()

	FileBrowser(state, modifier, mouseChords, session::send)
}
