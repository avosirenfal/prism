package prism.window

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
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

	/**
	 * [block] run on the windows' thread, and its answer: at once where this is that thread, or there is none. A
	 * caller left waiting when the thread goes is answered still ([release]).
	 */
	fun <T> call(block: () -> T): T {
		if (isCurrent || thread == null)
			return block()

		val asked = Asked(block)

		dispatch(kotlin.coroutines.EmptyCoroutineContext, asked)
		// The thread gone since it was looked for: what it was left is run by nobody, so it is run here.
		if (thread == null)
			asked.run()

		return asked.answer()
	}

	/** What a caller waits for the windows' thread to run: run once, whoever runs it, and its answer kept. */
	private class Asked<T>(
		block: () -> T,
	) : Runnable {
		private val answered = CountDownLatch(1)
		private val result by lazy { runCatching(block) }

		override fun run() {
			result
			answered.countDown()
		}

		fun answer(): T {
			answered.await()

			return result.getOrThrow()
		}
	}

	/** The thread taken as the windows' own, from here until [release]. */
	internal fun claim() {
		check(thread == null) { "the windows' thread is already running" }
		AwtEvents.take()
		thread = Thread.currentThread()
		wakeType = SDL_RegisterEvents(1)
	}

	internal fun release() {
		thread = null
		wakeType = 0
		// What was left for a thread that is gone is run by nobody, and not by the next; but for what a caller
		// waits on, which is run now, as the thread's last, rather than leave the caller waiting for ever.
		generateSequence(queue::poll).filterIsInstance<Asked<*>>().forEach(Asked<*>::run)
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

/**
 * AWT's events, run on the windows' thread while there is one.
 *
 * Compose for the desktop takes AWT's event thread for the one its scenes live on, and leaves work for it there. One
 * such piece reaches a scene in a window of SDL's: a frame's time after a layout has moved anything, whoever asked
 * to hear of it is told and the list of where every node is gets tidied (`RectManager.dispatchCallbacks`), through
 * a timer of AWT's (`postDelayed`, on skiko's `MainUIDispatcher`), unless the end of the layout got there first.
 * A layout that takes longer than a frame (a window's first, of a folder's whole listing) is still running when the
 * timer fires. On AWT's thread that ran beside it, both writing the one list with nothing between them: a node the
 * layout had just put in the list was gone from it, and the next node placed under that one failed the frame
 * ("LayoutNode 5664 not found in RectList").
 *
 * So AWT's queue is made this one, which gives each event to the windows' thread to run, between its own events as
 * anything from another thread is ([Ui]), and waits for it: what Compose leaves for "the UI thread" is run on the
 * thread its scenes do live on. With no windows' thread, events run on AWT's own, as they would have.
 */
internal object AwtEvents : EventQueue() {
	private val taken by lazy { Toolkit.getDefaultToolkit().systemEventQueue.push(this) }

	/** AWT's queue made this one, the first time; nothing after. */
	fun take() = taken

	override fun dispatchEvent(event: AWTEvent) = Ui.call { super.dispatchEvent(event) }
}
