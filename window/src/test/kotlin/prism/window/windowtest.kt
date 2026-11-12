package prism.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_BEGIN
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_COMPLETE
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_FILE
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_POSITION
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_MOTION
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_TEXT_INPUT
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FOCUS_GAINED
import org.lwjgl.sdl.SDLEvents.SDL_PushEvent
import org.lwjgl.sdl.SDLMouse.SDL_BUTTON_LEFT
import org.lwjgl.sdl.SDLStdinc.nSDL_malloc
import org.lwjgl.sdl.SDL_Event
import org.lwjgl.system.MemoryUtil

/**
 * Real windows on a display, given SDL's own events as a platform would give them: run where there is a display, as
 * `xvfb-run ./gradlew :window:test -Pwindow.display` (or under a Wayland compositor, `WAYLAND_DISPLAY` set).
 */
class WindowTest {
	private fun push(window: NativeWindow, type: Int, fill: (SDL_Event) -> Unit = {}) {
		SDL_Event.calloc().use { event ->
			event.type(type)
			event.window().windowID(window.id)
			fill(event)
			SDL_PushEvent(event)
		}
	}

	/** Text for an event, in SDL's own memory: SDL frees an event's text once the event is read. */
	private fun text(value: String): java.nio.ByteBuffer {
		val bytes = value.encodeToByteArray()
		val memory = MemoryUtil.memByteBuffer(nSDL_malloc(bytes.size + 1L), bytes.size + 1)

		memory.put(bytes).put(0.toByte()).flip()

		return memory
	}

	@Test
	fun aWindowIsClickedTypedInDroppedOnAndCopiedFrom() {
		assumeTrue(
			System.getProperty("window.display") != null,
			"needs a display: -Pwindow.display",
		)

		var clicks = 0
		var typed = ""
		var gotten: Dropped? = null
		var copied: String? = null

		application("prism-test") {
			var field by remember { mutableStateOf("") }
			val focus = remember { FocusRequester() }

			Window(
				onCloseRequest = ::exitApplication,
				title = "test",
				size = DpSize(300.dp, 200.dp),
			) {
				Column(Modifier.fillMaxSize()) {
					Row {
						Box(Modifier.size(100.dp, 50.dp).background(Color.Blue).clickable { clicks++ })
						Box(Modifier.width(50.dp))
						Box(
							Modifier.size(100.dp, 50.dp).dropTarget(
								object : DropTarget {
									override fun hover(over: DragOver?) = Unit

									override fun drop(dropped: Dropped): Boolean {
										gotten = dropped

										return true
									}
								},
							),
						)
					}
					BasicTextField(
						field,
						{
							field = it
							typed = it
						},
						Modifier.focusRequester(focus),
					)
				}

				LaunchedEffect(Unit) {
					// The window given the keyboard, as a window manager gives it.
					push(window, SDL_EVENT_WINDOW_FOCUS_GAINED)
					focus.requestFocus()
					delay(500)

					// Typed into the field, which has the keyboard; then a click on the box, which takes it.
					push(window, SDL_EVENT_TEXT_INPUT) {
						org.lwjgl.sdl.SDL_TextInputEvent
							.ntext(it.address(), text("héllo"))
					}
					delay(200)

					// A click on the first box: at 10, 10 in the window's points.
					push(window, SDL_EVENT_MOUSE_MOTION) { it.motion().x(10f).y(10f) }
					push(window, SDL_EVENT_MOUSE_BUTTON_DOWN) {
						it
							.button()
							.button(SDL_BUTTON_LEFT.toByte())
							.down(true)
							.clicks(1)
							.x(10f)
							.y(10f)
					}
					push(window, SDL_EVENT_MOUSE_BUTTON_UP) {
						it
							.button()
							.button(SDL_BUTTON_LEFT.toByte())
							.clicks(1)
							.x(10f)
							.y(10f)
					}

					// A file dragged in from another program and dropped on the box beside the first, at 200, 10.
					push(window, SDL_EVENT_DROP_BEGIN)
					push(window, SDL_EVENT_DROP_POSITION) { it.drop().x(200f).y(10f) }
					push(window, SDL_EVENT_DROP_FILE) {
						org.lwjgl.sdl.SDL_DropEvent
							.ndata(it.address(), text("/tmp/a file.txt"))
						it.drop().x(200f).y(10f)
					}
					push(window, SDL_EVENT_DROP_COMPLETE) { it.drop().x(200f).y(10f) }
					delay(500)

					// Wayland takes a clipboard only with the serial of input a person gave (a key, a click), which a
					// compositor with no seat (weston's headless one) never sends: there, it is left out.
					if (window.video != Video.Wayland) {
						SystemClipboard.put(
							ClipOffer(
								mapOf("text/uri-list" to "file:///tmp/a".encodeToByteArray()) +
									ClipOffer.text("/tmp/a").types,
							),
						)
						copied = SystemClipboard.read("text/uri-list")?.decodeToString()
					}
					exitApplication()
				}
			}
		}

		assertEquals(1, clicks)
		assertEquals("héllo", typed)
		assertEquals(Dropped.Files(listOf(Path.of("/tmp/a file.txt"))), gotten)
		if (copied != null || System.getenv("WAYLAND_DISPLAY") == null)
			assertEquals("file:///tmp/a", copied)
	}
}
