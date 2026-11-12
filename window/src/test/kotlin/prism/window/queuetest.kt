package prism.window

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_MOTION
import org.lwjgl.sdl.SDLEvents.SDL_PushEvent
import org.lwjgl.sdl.SDLInit.SDL_INIT_EVENTS
import org.lwjgl.sdl.SDLInit.SDL_Init
import org.lwjgl.sdl.SDLInit.SDL_QuitSubSystem
import org.lwjgl.sdl.SDL_Event

/** SDL's own queue, with no display: events pushed into it, and taken as the windows' loop takes them. */
class QueueTest {
	/** An event as the tests see it: a move, to where, or a press. */
	private sealed interface Seen {
		data class Move(
			val window: Int,
			val x: Float,
			val buttons: Int,
		) : Seen

		data class Press(
			val window: Int,
		) : Seen
	}

	@BeforeEach
	fun start() {
		assertTrue(SDL_Init(SDL_INIT_EVENTS))
	}

	@AfterEach
	fun stop() = SDL_QuitSubSystem(SDL_INIT_EVENTS)

	@Test
	fun ofMovesOneAfterAnotherOnlyTheLastIsTold() {
		(1..10).forEach { move(window = 1, x = it.toFloat()) }
		press(window = 1)
		(11..15).forEach { move(window = 1, x = it.toFloat()) }
		// The same pointer over another window, and then with a button held: each a run of its own.
		move(window = 2, x = 16f)
		move(window = 2, x = 17f, buttons = 1)
		move(window = 2, x = 18f, buttons = 1)

		assertEquals(
			listOf(
				Seen.Move(1, 10f, 0),
				Seen.Press(1),
				Seen.Move(1, 15f, 0),
				Seen.Move(2, 16f, 0),
				Seen.Move(2, 18f, 1),
			),
			taken(),
		)
	}

	@Test
	fun whatComesWhileTheQueueIsTakenWaitsForTheNextTake() {
		Queue().use { queue ->
			val seen = mutableListOf<Seen>()

			move(window = 1, x = 1f)
			press(window = 1)
			queue.take(0) {
				seen += seenOf(it)
				// Each event handled brings another, as a mouse reporting faster than its moves are answered does.
				move(window = 1, x = 100f + seen.size)
			}

			assertEquals(listOf(Seen.Move(1, 1f, 0), Seen.Press(1)), seen)

			// SDL ends each take of what it had then with a mark of its own, which the next take meets first.
			seen.clear()
			repeat(2) { queue.take(0) { seen += seenOf(it) } }

			// Two came while the first two were handled: the second overtook the first.
			assertEquals(listOf(Seen.Move(1, 102f, 0)), seen)
		}
	}

	private fun taken(): List<Seen> =
		Queue().use { queue ->
			buildList { queue.take(0) { add(seenOf(it)) } }
		}

	private fun seenOf(event: SDL_Event): Seen =
		when (event.type()) {
			SDL_EVENT_MOUSE_MOTION ->
				event.motion().let {
					Seen.Move(it.windowID(), it.x(), it.state())
				}
			else -> Seen.Press(event.button().windowID())
		}

	private fun move(window: Int, x: Float, buttons: Int = 0) =
		SDL_Event.calloc().use { event ->
			event.type(SDL_EVENT_MOUSE_MOTION)
			event
				.motion()
				.type(SDL_EVENT_MOUSE_MOTION)
				.windowID(window)
				.x(x)
				.state(buttons)
			assertTrue(SDL_PushEvent(event))
		}

	private fun press(window: Int) =
		SDL_Event.calloc().use { event ->
			event.type(SDL_EVENT_MOUSE_BUTTON_DOWN)
			event
				.button()
				.type(SDL_EVENT_MOUSE_BUTTON_DOWN)
				.windowID(window)
				.down(true)
			assertTrue(SDL_PushEvent(event))
		}
}
