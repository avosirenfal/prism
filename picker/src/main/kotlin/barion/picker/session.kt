package barion.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A picker running: its states, folded from its first [Step] by the reducer over every input in the order they come,
 * and its effects carried out as each step asks for them. A folder read or a name looked up is done on [io] against
 * [disk], and its answer joins the inputs; a finish completes [outcome]. The fold is the only thing that holds a
 * state, and it holds each one only until the next.
 */
class Session(
	first: Step,
	private val disk: Disk,
	private val scope: CoroutineScope,
	private val io: CoroutineDispatcher = Dispatchers.IO,
) {
	private val inputs = Channel<Input>(Channel.UNLIMITED)
	private val ending = CompletableDeferred<Outcome>()

	/** How the picker ended, once it has. */
	val outcome: Deferred<Outcome> get() = ending

	val state: StateFlow<PickerState> =
		inputs
			.receiveAsFlow()
			.runningFold(first) { last, input -> step(last.state, input) }
			.onEach { it.effects.forEach(::perform) }
			.map { it.state }
			.stateIn(scope, SharingStarted.Eagerly, first.state)

	/** What the user did, to be folded in after whatever came before it. */
	fun send(event: PickerEvent) {
		inputs.trySend(event)
	}

	private fun perform(effect: Effect) {
		when (effect) {
			is Effect.Asking -> scope.launch(io) { inputs.send(answerOf(effect)) }
			is Effect.Finish -> ending.complete(effect.outcome)
		}
	}

	/** The disk's answer, or, where the disk failed outright, the answer that says nothing could be found. */
	private fun answerOf(asking: Effect.Asking): Answer =
		try {
			disk.answer(asking)
		} catch (failed: RuntimeException) {
			when (asking) {
				is Effect.Read ->
					Answer.Arrived(
						asking.folder,
						Listing.Unreadable(failed.message ?: failed.toString()),
					)
				is Effect.Look -> Answer.Looked(asking.lookup, emptyMap())
			}
		}
}

/** The picker [session] runs, drawn: its state as it changes, and what the user does sent to it. */
@Composable
fun SessionPicker(session: Session, modifier: Modifier = Modifier) {
	val state by session.state.collectAsState()

	FilePicker(state, modifier, session::send)
}
