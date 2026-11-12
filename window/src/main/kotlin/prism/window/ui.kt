package prism.window

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.lwjgl.sdl.SDLEvents.SDL_PushEvent
import org.lwjgl.sdl.SDLEvents.SDL_RegisterEvents
import org.lwjgl.sdl.SDL_Event

/*
 * The one thread every window lives on. SDL wants its windows and its events handled on one thread (on macOS, the
 * process's first), and Compose wants a scene's composition, input and drawing on one thread; this is both. Work
 * from any other thread comes here through [Ui] as a coroutine dispatcher, or [Ui.call] to wait for an answer, and
 * wakes the thread out of its wait for events by an event of its own.
 */

/** The windows' thread, as a dispatcher: what runs here runs between events, never during one. */
object Ui : CoroutineDispatcher() {
	private val queue = ConcurrentLinkedQueue<Runnable>()

	/** Whether a wake is already on its way: one is enough, however many tasks come before it is read. */
	private val woken = AtomicBoolean(false)

	@Volatile
	private var thread: Thread? = null

	/** The event type that wakes the thread; 0 until SDL is running. */
	@Volatile
	internal var wakeType: Int = 0
		private set

	/** Whether this is the windows' thread. */
	val isCurrent: Boolean get() = Thread.currentThread() === thread

	override fun dispatch(context: CoroutineContext, block: Runnable) {
		queue.add(block)
		wake()
	}

	/** [block] run on the windows' thread, and its answer: at once where this is that thread. */
	fun <T> call(block: () -> T): T {
		if (isCurrent || thread == null)
			return block()

		val answer = CompletableFuture<T>()

		dispatch(kotlin.coroutines.EmptyCoroutineContext) {
			try {
				answer.complete(block())
			} catch (failed: Throwable) {
				answer.completeExceptionally(failed)
			}
		}

		return try {
			answer.get()
		} catch (wrapped: ExecutionException) {
			throw wrapped.cause ?: wrapped
		}
	}

	/** The thread taken as the windows' own, from here until [release]. */
	internal fun claim() {
		check(thread == null) { "the windows' thread is already running" }
		thread = Thread.currentThread()
		wakeType = SDL_RegisterEvents(1)
	}

	internal fun release() {
		thread = null
		wakeType = 0
		// What was left for a thread that is gone is run by nobody, and not by the next.
		queue.clear()
	}

	/** The thread woken out of its wait for events, if it waits: an event of its own is pushed, once. */
	internal fun wake() {
		val type = wakeType

		if (type != 0 && woken.compareAndSet(false, true))
			SDL_Event.calloc().use { event ->
				event.type(type)
				SDL_PushEvent(event)
			}
	}

	/** Everything dispatched so far run, in order; what it dispatches in turn waits for the next time. */
	internal fun drain() {
		woken.set(false)

		repeat(queue.size) {
			queue.poll()?.run() ?: return
		}
	}

	internal val hasWork: Boolean get() = queue.isNotEmpty()
}
