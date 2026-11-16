package prism.window

import org.lwjgl.sdl.SDLError.SDL_GetError
import org.lwjgl.sdl.SDLHints.SDL_HINT_APP_ID
import org.lwjgl.sdl.SDLHints.SDL_HINT_APP_NAME
import org.lwjgl.sdl.SDLHints.SDL_HINT_FRAMEBUFFER_ACCELERATION
import org.lwjgl.sdl.SDLHints.SDL_HINT_MAC_BACKGROUND_APP
import org.lwjgl.sdl.SDLHints.SDL_HINT_MAC_CTRL_CLICK_EMULATE_RIGHT_CLICK
import org.lwjgl.sdl.SDLHints.SDL_HINT_MOUSE_FOCUS_CLICKTHROUGH
import org.lwjgl.sdl.SDLHints.SDL_HINT_QUIT_ON_LAST_WINDOW_CLOSE
import org.lwjgl.sdl.SDLHints.SDL_HINT_VIDEO_ALLOW_SCREENSAVER
import org.lwjgl.sdl.SDLHints.SDL_HINT_VIDEO_DRIVER
import org.lwjgl.sdl.SDLHints.SDL_SetHint
import org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO
import org.lwjgl.sdl.SDLInit.SDL_Init
import org.lwjgl.sdl.SDLInit.SDL_Quit
import org.lwjgl.sdl.SDLProperties.SDL_GetNumberProperty
import org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty
import org.lwjgl.sdl.SDLStdinc.SDL_getenv_unsafe
import org.lwjgl.sdl.SDLStdinc.SDL_setenv_unsafe
import org.lwjgl.sdl.SDLVideo.SDL_GL_LoadLibrary
import org.lwjgl.sdl.SDLVideo.SDL_GL_UnloadLibrary
import org.lwjgl.sdl.SDLVideo.SDL_GetCurrentVideoDriver
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowProperties
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_WAYLAND_DISPLAY_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_WAYLAND_SURFACE_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_X11_DISPLAY_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_X11_WINDOW_NUMBER
import org.lwjgl.system.Platform

/** Which display server SDL speaks to, as its video driver is named. */
enum class Video {
	Wayland,
	X11,
	Windows,
	Cocoa,
	Other,
	;

	companion object {
		fun of(driver: String?): Video =
			when (driver) {
				"wayland" -> Wayland
				"x11" -> X11
				"windows" -> Windows
				"cocoa" -> Cocoa
				else -> Other
			}
	}
}

/** What a window is to the platform it is on: what another program, or the platform's own calls, name it by. */
sealed interface NativeHandle {
	/** An X11 window, on the connection SDL opened ([display], an Xlib `Display*`). */
	data class X11(
		val display: Long,
		val window: Long,
	) : NativeHandle

	/** A Wayland surface, on SDL's connection ([display], a `wl_display*`). */
	data class Wayland(
		val display: Long,
		val surface: Long,
	) : NativeHandle

	data class Windows(
		val hwnd: Long,
	) : NativeHandle

	data class Cocoa(
		val window: Long,
	) : NativeHandle

	data object Unknown : NativeHandle

	companion object {
		internal fun of(window: Long, video: Video): NativeHandle {
			val properties = SDL_GetWindowProperties(window)

			fun pointer(name: String) = SDL_GetPointerProperty(properties, name, 0L)

			return when (video) {
				Video.X11 ->
					X11(
						pointer(SDL_PROP_WINDOW_X11_DISPLAY_POINTER),
						SDL_GetNumberProperty(properties, SDL_PROP_WINDOW_X11_WINDOW_NUMBER, 0L),
					)
				Video.Wayland ->
					Wayland(
						pointer(SDL_PROP_WINDOW_WAYLAND_DISPLAY_POINTER),
						pointer(SDL_PROP_WINDOW_WAYLAND_SURFACE_POINTER),
					)
				Video.Windows -> Windows(pointer(SDL_PROP_WINDOW_WIN32_HWND_POINTER))
				Video.Cocoa -> Cocoa(pointer(SDL_PROP_WINDOW_COCOA_WINDOW_POINTER))
				Video.Other -> Unknown
			}
		}
	}
}

/** SDL could not be started: no display to speak to, or none SDL can. */
class NoDisplay(
	message: String,
) : RuntimeException(message)

/** A window was made, but nothing could be put in it: no way of drawing works here. */
class CannotDraw(
	message: String,
) : RuntimeException(message)

/**
 * What says where the windows' thread was slow: with `PRISM_STALL_LOG` set to a number of milliseconds, every turn of
 * the windows' loop that took longer is said on standard error, with what each part of it took (what other threads
 * left to be done, composing, laying out and drawing, each event). A window stutters when its thread is kept from the
 * next frame, by its own work or by the whole process being stopped (a collection of garbage); this tells which
 * turn, and how long, for whoever is looking for why. Not set, it costs a comparison a turn.
 */
internal object Stalls {
	private val over: Long? =
		System
			.getenv("PRISM_STALL_LOG")
			?.toLongOrNull()
			?.takeIf { it >= 0 }
			?.times(1_000_000)

	/** One turn of the loop, timed part by part. */
	class Turn(
		private val over: Long,
	) {
		private val began = System.nanoTime()
		private var last = began
		private val parts = StringBuilder()

		/** [what] was done since the part before. */
		fun did(what: String) {
			val now = System.nanoTime()

			if (now - last >= 1_000_000)
				parts
					.append(
						", ",
					).append(what)
					.append(' ')
					.append((now - last) / 1_000_000)
					.append(" ms")
			last = now
		}

		/** The turn is over: said, where it took too long. */
		fun done() {
			val took = System.nanoTime() - began

			if (took > over)
				System.err.println("prism.window: a turn of ${took / 1_000_000} ms$parts")
		}
	}

	/** A turn to time; none, where nothing is asked to be said. */
	fun turn(): Turn? = over?.let(::Turn)
}

/** What the window module says of itself on standard error, where `PRISM_WINDOW_LOG` is set: how it draws, and why. */
internal object Log {
	private val on = System.getenv("PRISM_WINDOW_LOG") != null

	fun say(what: () -> String) {
		if (on)
			System.err.println("prism.window: " + what())
	}
}

/** NVIDIA's switch for its OpenGL's threading, as its driver reads it from the environment. */
internal const val NVIDIA_THREADED = "__GL_THREADED_OPTIMIZATIONS"

/**
 * NVIDIA's threading of OpenGL turned off, unless the user has set it. Its driver (580) turns it on of its own accord
 * for a process that loads LWJGL (by an application profile), and under Wayland its EGL display then goes bad once the
 * first context is made: every EGL call that needs it after fails with EGL_BAD_DISPLAY, and every window falls back to
 * the CPU. Set in the process's own environment, where the driver reads it, before EGL is loaded; nothing but NVIDIA's
 * driver reads it, and prism's drawing gains nothing from it.
 */
internal fun unthreadNvidia() {
	if (Platform.get() == Platform.LINUX)
		SDL_setenv_unsafe(NVIDIA_THREADED, "0", 0)
}

/**
 * What SDL was started with, for as long as it runs: how windows are drawn, and whether OpenGL's library is kept loaded
 * for them ([keepsGl]).
 */
internal class Sdl private constructor(
	val rendering: Rendering,
	private val keepsGl: Boolean,
) : AutoCloseable {
	val video: Video get() = rendering.video

	override fun close() {
		if (keepsGl)
			SDL_GL_UnloadLibrary()
		SDL_Quit()
	}

	companion object {
		/**
		 * SDL started for windows, as [appId] (Wayland's app id and X11's class, which a desktop matches its entry and
		 * icon by). On Linux, Wayland is preferred where the session has it, and X11 is the way back; `SDL_VIDEO_DRIVER`
		 * set by the user says otherwise.
		 */
		fun start(appId: String, drawing: Drawing): Sdl {
			SDL_SetHint(SDL_HINT_APP_ID, appId)
			SDL_SetHint(SDL_HINT_APP_NAME, appId)
			// A click on a window that is not focused acts as well as focusing it, as on every desktop but macOS, where
			// the first click only brings the window forward; and Ctrl with a click is a right click there, as a Mac has
			// it for a mouse of one button.
			if (Platform.get() == Platform.MACOSX)
				SDL_SetHint(SDL_HINT_MAC_CTRL_CLICK_EMULATE_RIGHT_CLICK, "1")
			else
				SDL_SetHint(SDL_HINT_MOUSE_FOCUS_CLICKTHROUGH, "1")
			SDL_SetHint(SDL_HINT_VIDEO_ALLOW_SCREENSAVER, "1")
			// On a Mac, an application in no Dock and no ⌘Tab until it says otherwise: one that opens windows for
			// others (a daemon, a launcher) is no application of its own to switch to, and one that has a window of
			// the usual kind says so as it opens it.
			if (Platform.get() == Platform.MACOSX)
				SDL_SetHint(SDL_HINT_MAC_BACKGROUND_APP, "1")
			// Which windows are open, and whether the application goes on with none, is the application's to say: a
			// process that opens windows for others (prism's daemon) is not ended by its last one being closed.
			SDL_SetHint(SDL_HINT_QUIT_ON_LAST_WINDOW_CLOSE, "0")
			if (System.getenv("SDL_VIDEO_DRIVER") == null &&
				System.getenv("WAYLAND_DISPLAY") != null
			)
				SDL_SetHint(SDL_HINT_VIDEO_DRIVER, "wayland,x11")

			unthreadNvidia()
			Log.say { "$NVIDIA_THREADED=${SDL_getenv_unsafe(NVIDIA_THREADED)}" }
			if (!SDL_Init(SDL_INIT_VIDEO))
				throw NoDisplay(SDL_GetError() ?: "SDL could not start its video")

			val video = Video.of(SDL_GetCurrentVideoDriver())

			Pixels.Gpu.configure()

			// OpenGL's library, loaded once for the probe and for every window drawn with it after: left to SDL, it is
			// unloaded with the last window made for it (the probe's, at first) and loaded anew for the next, EGL's
			// display shut and started again in between. Kept only where windows will be drawn with it.
			val loaded = drawing != Drawing.Cpu && SDL_GL_LoadLibrary(null as CharSequence?)
			val rendering = Rendering(video, drawing, openGlFor(drawing, loaded))
			val keepsGl = loaded && rendering.onGpu

			if (loaded && !keepsGl)
				SDL_GL_UnloadLibrary()

			// Drawn by the CPU on X11, the pixels go to the X server as they are (shared memory), not through a GPU
			// renderer SDL would otherwise try first, copying them into a texture.
			if (!rendering.onGpu && video == Video.X11)
				SDL_SetHint(SDL_HINT_FRAMEBUFFER_ACCELERATION, "0")
			Log.say {
				"$video, drawn on the ${if (rendering.onGpu) "GPU" else "CPU"} (asked for: $drawing)"
			}

			return Sdl(rendering, keepsGl)
		}
	}
}
