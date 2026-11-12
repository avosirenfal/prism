package prism.window

import org.lwjgl.sdl.SDLEvents.SDL_EVENT_FIRST
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_LAST
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_MOTION
import org.lwjgl.sdl.SDLEvents.SDL_PEEKEVENT
import org.lwjgl.sdl.SDLEvents.SDL_PeepEvents
import org.lwjgl.sdl.SDLEvents.SDL_PollEvent
import org.lwjgl.sdl.SDLEvents.SDL_WaitEventTimeout
import org.lwjgl.sdl.SDL_Event

/*
 * SDL's queue of events, taken a frame's worth at a time: what is queued when the loop looks, and none that come while
 * those are handled, which wait for the frame after (SDL marks the end of what it had when it looked, too). And of moves
 * of the pointer one after another, only the last is told, since each says only where the pointer is now. A mouse can
 * report its moves a thousand times a second, and a move can take longer than that to answer (one that scrolls a list
 * lays out every row it brings into sight): told each, they took longer to answer than they took to come, and held the
 * next frame back for as long as the mouse moved. Told the last, a list dragged by its bar is laid out once a frame,
 * where it is going, rather than once for each place the pointer passed on the way.
 */

/** What a move of the pointer is of: the window it is over, the mouse, and the buttons held. */
internal data class Moving(
	val window: Int,
	val mouse: Int,
	val buttons: Int,
) {
	companion object {
		/** What [event] is a move of; null where it is not a move of the pointer. */
		fun of(event: SDL_Event): Moving? =
			if (event.type() == SDL_EVENT_MOUSE_MOTION)
				event.motion().let { Moving(it.windowID(), it.which(), it.state()) }
			else
				null
	}
}

/** SDL's queue, taken from a frame's worth at a time. One thread's, as SDL's events are. */
internal class Queue : AutoCloseable {
	private val event = SDL_Event.malloc()

	/** What is queued, looked at without taking it: at most this many at a time; the rest wait for the next frame. */
	private val ahead = SDL_Event.malloc(AHEAD)

	/**
	 * Each event queued now, given to [handle] in turn, but a move of the pointer that the next queued event overtakes:
	 * a move of the same, with the same buttons held. Where none is queued, waits up to [millis] for one (none, for 0).
	 */
	fun take(millis: Int, handle: (SDL_Event) -> Unit) {
		val got =
			if (millis > 0)
				SDL_WaitEventTimeout(event, millis)
			else
				SDL_PollEvent(event)

		if (!got)
			return

		var left = queued()

		tell(handle)
		while (left-- > 0 && SDL_PollEvent(event))
			tell(handle)
	}

	private fun tell(handle: (SDL_Event) -> Unit) {
		val moving = Moving.of(event)

		if (moving == null || moving != next()?.let(Moving::of))
			handle(event)
	}

	/** How many events are queued, up to [AHEAD]. */
	private fun queued(): Int =
		SDL_PeepEvents(ahead.clear(), SDL_PEEKEVENT, SDL_EVENT_FIRST, SDL_EVENT_LAST)

	/** The event queued next, left in the queue; null where there is none. */
	private fun next(): SDL_Event? =
		ahead
			.clear()
			.limit(1)
			.takeIf { SDL_PeepEvents(it, SDL_PEEKEVENT, SDL_EVENT_FIRST, SDL_EVENT_LAST) > 0 }
			?.get(0)

	override fun close() {
		event.free()
		ahead.free()
	}

	private companion object {
		const val AHEAD = 256
	}
}
