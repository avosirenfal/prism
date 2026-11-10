package barion.picker

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

/**
 * A picker running: its states, folded from its first [Step] by the reducer over every input in the order they come,
 * and its effects carried out as each step asks for them. A folder read, a name looked up or results read are done on
 * [io] against [disk], a search against [finder], and each answer joins the inputs; a finish completes [outcome], and
 * tells [finder] what was picked. The fold is the only thing that holds a state, and it holds each one only until the
 * next.
 *
 * The searches the search's window asks for as the query is typed are run one at a time, the latest only: one asked
 * for while another runs takes the place of any waiting, and the answer of one overtaken is dropped.
 */
class Session(
	first: Step,
	private val disk: Disk,
	private val scope: CoroutineScope,
	private val io: CoroutineDispatcher = Dispatchers.IO,
	private val clipboard: (String) -> Unit = ::toClipboard,
	private val finder: Finder? = null,
	private val clock: () -> Instant = Instant::now,
) {
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

	val state: StateFlow<PickerState> =
		inputs
			.receiveAsFlow()
			.runningFold(first) { last, input -> step(last.state, input) }
			.onEach { it.effects.forEach(::perform) }
			.map { it.state }
			.stateIn(scope, SharingStarted.Eagerly, first.state)

	init {
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
	fun send(event: PickerEvent) {
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
			is Effect.Finish -> {
				(effect.outcome as? Outcome.Chosen)?.let { chosen ->
					finder?.let { scope.launch(io) { it.picked(chosen.paths, clock()) } }
				}
				ending.complete(effect.outcome)
			}
		}
	}

	/** The answer to [asking], or, where it failed outright, the answer that says nothing could be had. */
	private fun answerOf(asking: Effect.Asking): Answer =
		try {
			disk.answer(asking, finder)
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

/** The picker [session] runs, drawn: its state as it changes, and what the user does sent to it. */
@Composable
fun SessionPicker(session: Session, modifier: Modifier = Modifier) {
	val state by session.state.collectAsState()

	FilePicker(state, modifier, session::send)
}
