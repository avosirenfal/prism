package prism.window

import androidx.compose.ui.geometry.Offset
import java.nio.file.Path
import org.lwjgl.sdl.SDLKeyboard.SDL_GetModState
import org.lwjgl.sdl.SDLKeycode.SDLK_ESCAPE
import org.lwjgl.sdl.SDLMouse.SDL_CaptureMouse
import org.lwjgl.sdl.SDLMouse.SDL_GetGlobalMouseState
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowPixelDensity
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowPosition
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowSize
import org.lwjgl.system.MemoryStack

/*
 * Drags this process starts: something carried out of a window, given to the place it is let go on. A drag is the
 * process's own from start to end, so what it carries to this process's places is the value itself, not a description
 * of it written out and read back.
 *
 * Where the platform's protocol is spoken ([NativeDrags]: Wayland's data device, so far), a drag is the platform's: the
 * compositor carries it to whatever window it is let go over, another program's (given [Outgoing.offer]) or one of
 * this process's, which SDL tells of it as of any drop, and which knows it for its own. Where it is not yet (X11,
 * Windows, macOS), a drag goes from window to window of this process's by where the pointer is on the screen, which
 * those platforms say, and not out to other programs.
 */

/**
 * What a drag carries: [local], the value given to this process's places; and what other programs are given, [files]
 * (a URI list, and their paths as text), or, where there are none, [text].
 */
data class Outgoing(
	val local: Any,
	val files: List<Path>,
	val text: String? = null,
) {
	/**
	 * What other programs are offered, by MIME type: files as a URI list alone, which is what every program that takes
	 * files reads; text only where there are no files. (SDL, given both, asks for the text and reads it as URIs: a
	 * drop of files on a program of SDL's, prism's own windows among them, would come to nothing.)
	 */
	val offer: ClipOffer
		get() =
			if (files.isNotEmpty())
				ClipOffer(
					mapOf(
						"text/uri-list" to
							files.joinToString("") { it.toUri().toString() + "\r\n" }.encodeToByteArray(),
					),
				)
			else
				ClipOffer.text(text.orEmpty())
}

/** How a drag ended. */
sealed interface DragEnd {
	/** Let go on a place that took it. */
	data object Taken : DragEnd

	/** Let go where nothing took it, or called off (Escape). */
	data object Cancelled : DragEnd
}

/** The drags of one application's windows: at most one at a time, as there is one pointer. */
internal class Drags(
	private val app: Application,
) {
	/** How a drag goes: by the platform's protocol, or by the pointer's events this process is given. */
	private enum class Way {
		Native,
		Local,
	}

	private class Active(
		val from: NativeWindow,
		val outgoing: Outgoing,
		val onEnd: (DragEnd) -> Unit,
		val way: Way,
		var over: NativeWindow?,
	)

	private var active: Active? = null

	val isDragging: Boolean get() = active != null

	/** A drag of [outgoing] begun from [from], the pointer held down there; [onEnd] told how it ended. */
	fun start(from: NativeWindow, outgoing: Outgoing, onEnd: (DragEnd) -> Unit) {
		end(DragEnd.Cancelled)
		from.cancelPointer()

		// Told how it ended later, on the windows' thread, by which time it is made; told of a drag no longer the
		// active one, it does nothing (finish).
		lateinit var drag: Active
		val native = app.nativeDrags?.start(from, outgoing.offer) { how -> finish(drag, how) } == true

		drag = Active(from, outgoing, onEnd, if (native) Way.Native else Way.Local, null)
		Log.say { "drag: ${drag.way}" }
		active = drag
		if (!native) {
			// The pointer's events kept coming to this window wherever the pointer goes, until it is let go.
			SDL_CaptureMouse(true)
			moved(from, from.pointerPosition)
		}
	}

	/** What a drop a window is told of by the platform is, where it is this process's own drag: what it carries. */
	fun ownDropped(): Outgoing? = active?.takeIf { it.way == Way.Native }?.outgoing

	/** This process's own drag, by the platform's protocol, let go on a window of its own: [taken] or not. */
	fun droppedHere(taken: Boolean) {
		val drag = active?.takeIf { it.way == Way.Native } ?: return

		finish(drag, if (taken) DragEnd.Taken else DragEnd.Cancelled)
	}

	/** The pointer moved to [position] in [window] during a drag: what is under it told; false where there is no drag. */
	fun moved(window: NativeWindow, position: Offset): Boolean {
		val drag = active?.takeIf { it.way == Way.Local } ?: return false
		val (target, at) = targetOf(window, position)

		if (target !== drag.over)
			drag.over?.drops?.leave()
		drag.over = target
		target?.drops?.hover(
			at,
			DragOver.Own(drag.outgoing.local, dragKeysOf(SDL_GetModState().toInt())),
		)

		return true
	}

	/** The pointer let go at [position] in [window]: the drag given to what is there; false where there is no drag. */
	fun released(window: NativeWindow, position: Offset): Boolean {
		val drag = active?.takeIf { it.way == Way.Local } ?: return false
		val (target, at) = targetOf(window, position)
		val keys = dragKeysOf(SDL_GetModState().toInt())

		drag.over
			?.takeIf { it !== target }
			?.drops
			?.leave()

		val taken = target?.drops?.drop(at, Dropped.Own(drag.outgoing.local, keys)) ?: false

		finish(drag, if (taken) DragEnd.Taken else DragEnd.Cancelled)

		return true
	}

	/** A key pressed during a drag: Escape calls it off. Whether the key was the drag's. */
	fun keyed(key: Int): Boolean {
		val drag = active?.takeIf { it.way == Way.Local } ?: return false

		if (key != SDLK_ESCAPE)
			return false

		drag.over?.drops?.leave()
		finish(drag, DragEnd.Cancelled)

		return true
	}

	/** [window] closed: a drag from it ends, and one over it is over nothing. */
	fun closed(window: NativeWindow) {
		val drag = active ?: return

		if (drag.from === window)
			end(DragEnd.Cancelled)
		else if (drag.over === window)
			drag.over = null
	}

	private fun end(how: DragEnd) {
		active?.let {
			it.over?.drops?.leave()
			finish(it, how)
		}
	}

	private fun finish(drag: Active, how: DragEnd) {
		if (active !== drag)
			return
		active = null
		if (drag.way == Way.Local)
			SDL_CaptureMouse(false)
		drag.onEnd(how)
	}

	/**
	 * The window the pointer is over, and where in it, for a pointer at [position] in [window] (where the events come,
	 * which is the window the drag began in): where it is on the screen, and which of the application's windows is
	 * there, where the platform says; the window itself, where it holds the pointer, otherwise.
	 */
	private fun targetOf(
		window: NativeWindow,
		position: Offset,
	): Pair<NativeWindow?, Offset> {
		if (window.holds(position))
			return window to position

		if (window.video == Video.Wayland)
			return null to position

		val screen =
			MemoryStack.stackPush().use { stack ->
				val x = stack.mallocFloat(1)
				val y = stack.mallocFloat(1)

				SDL_GetGlobalMouseState(x, y)
				x.get(0) to y.get(0)
			}

		return app.windows
			.asSequence()
			.filter { it !== window }
			.mapNotNull { other ->
				other.pointOf(screen.first, screen.second)?.let { other to it }
			}.firstOrNull()
			?: (null to position)
	}
}

/** Whether [position] (in the window's pixels) is inside it. */
internal fun NativeWindow.holds(position: Offset): Boolean =
	MemoryStack.stackPush().use { stack ->
		val width = stack.mallocInt(1)
		val height = stack.mallocInt(1)
		val perPoint = SDL_GetWindowPixelDensity(sdlWindow).takeIf { it > 0f } ?: 1f

		SDL_GetWindowSize(sdlWindow, width, height)
		position.x >= 0 && position.y >= 0 && position.x < width.get(0) * perPoint &&
			position.y < height.get(0) * perPoint
	}

/** The screen's point ([x], [y], in points) in this window's pixels, if the window is there. */
private fun NativeWindow.pointOf(x: Float, y: Float): Offset? =
	MemoryStack.stackPush().use { stack ->
		val left = stack.mallocInt(1)
		val top = stack.mallocInt(1)
		val perPoint = SDL_GetWindowPixelDensity(sdlWindow).takeIf { it > 0f } ?: 1f

		SDL_GetWindowPosition(sdlWindow, left, top)
		Offset((x - left.get(0)) * perPoint, (y - top.get(0)) * perPoint).takeIf(::holds)
	}
