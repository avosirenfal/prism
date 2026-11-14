package prism.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.awt.EventQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import javax.swing.Timer
import kotlin.concurrent.thread
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.lwjgl.sdl.SDLInit.SDL_INIT_EVENTS
import org.lwjgl.sdl.SDLInit.SDL_Init
import org.lwjgl.sdl.SDLInit.SDL_QuitSubSystem

/**
 * What Compose leaves for AWT's thread, run on the windows' thread: with no display, the test's thread taken as the
 * windows' and drained as their loop drains it.
 */
class AwtEventsTest {
	@BeforeEach
	fun start() {
		assertTrue(SDL_Init(SDL_INIT_EVENTS))
	}

	@AfterEach
	fun stop() = SDL_QuitSubSystem(SDL_INIT_EVENTS)

	@Test
	fun whatAwtIsLeftRunsOnTheWindowsThread() =
		asWindowsThread {
			val ran = CopyOnWriteArrayList<Thread>()

			EventQueue.invokeLater { ran += Thread.currentThread() }
			Timer(1) { ran += Thread.currentThread() }.apply { isRepeats = false }.start()
			drainedUntil { ran.size == 2 }

			assertEquals(List(2) { Thread.currentThread() }, ran)
		}

	@Test
	fun withNoWindowsThreadAwtRunsItsOwn() {
		// Made AWT's queue by a windows' thread that has since gone.
		asWindowsThread { }

		val ran = CompletableFuture<Thread>()

		EventQueue.invokeLater { ran.complete(Thread.currentThread()) }

		assertNotSame(Thread.currentThread(), ran.get(WAIT_SECONDS, TimeUnit.SECONDS))
	}

	@Test
	fun aCallerLeftWaitingWhenTheThreadGoesIsAnswered() {
		val answer = CompletableFuture<Int>()

		asWindowsThread {
			thread { answer.complete(Ui.call { 42 }) }
			// Left with the call, and gone without ever draining it.
			waitUntil { Ui.hasWork }
		}

		assertEquals(42, answer.get(WAIT_SECONDS, TimeUnit.SECONDS))
	}

	/**
	 * The crash this is for, in small. A node that asks to hear where it is laid out is told by the very dispatch that
	 * tidies the scene's list of rectangles, a frame's time after it is placed; what is placed after it takes several
	 * frames' time, so the telling comes due while the layout is still running. It must wait for the layout and be on
	 * its thread, not run beside it on AWT's.
	 */
	@OptIn(InternalComposeUiApi::class)
	@Test
	fun aLayoutLongerThanAFrameIsNotToldOfBesideItself() =
		asWindowsThread {
			registerSkikoComposeImplementation()

			val told = CopyOnWriteArrayList<Thread>()
			val placing = AtomicBoolean(false)
			val toldWhilePlacing = AtomicBoolean(false)

			val recomposer = FrameRecomposer(Ui) {}

			val scene =
				CanvasLayersComposeScene(
					frameRecomposer = recomposer,
					density = Density(1f),
					size = IntSize(100, 100),
					platformContext = PlatformContext.Empty(),
					invalidateLayout = {},
					invalidateDraw = {},
				)

			try {
				scene.setContent {
					Column {
						Box(
							Modifier.size(10.dp).onLayoutRectChanged(0, 0) {
								told += Thread.currentThread()
								if (placing.get())
									toldWhilePlacing.set(true)
							},
						)
						Layout({}) { _, _ ->
							layout(1, 1) {
								placing.set(true)
								LockSupport.parkNanos(SLOW_PLACING_NANOS)
								placing.set(false)
							}
						}
					}
				}
				Ui.drain()
				recomposer.performFrame(System.nanoTime())
				scene.measureAndLayout()
				drainedUntil { told.isNotEmpty() }

				assertEquals(setOf(Thread.currentThread()), told.toSet())
				assertFalse(toldWhilePlacing.get(), "told while the layout was still placing")
			} finally {
				scene.close()
				recomposer.close()
			}
		}

	/** [body] run with this thread taken as the windows' thread, and let go after. */
	private fun asWindowsThread(body: () -> Unit) {
		Ui.claim()
		try {
			body()
		} finally {
			Ui.release()
		}
	}

	/** What the windows' thread was left run, as its loop runs it, until [done]. */
	private fun drainedUntil(done: () -> Boolean) =
		waitUntil {
			Ui.drain()
			done()
		}

	private fun waitUntil(done: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)

		while (!done()) {
			check(System.nanoTime() < deadline) { "waited $WAIT_SECONDS s" }
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
		}
	}

	private companion object {
		const val WAIT_SECONDS = 20L

		/** Several frames' time (a frame is 16 ms): long enough for what a frame's time brings due to come due. */
		val SLOW_PLACING_NANOS = TimeUnit.MILLISECONDS.toNanos(150)
	}
}
