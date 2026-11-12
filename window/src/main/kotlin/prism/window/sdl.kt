package prism.window

import org.lwjgl.sdl.SDLError.SDL_GetError
import org.lwjgl.sdl.SDLHints.SDL_HINT_APP_ID
import org.lwjgl.sdl.SDLHints.SDL_HINT_APP_NAME
import org.lwjgl.sdl.SDLHints.SDL_HINT_MOUSE_FOCUS_CLICKTHROUGH
import org.lwjgl.sdl.SDLHints.SDL_HINT_VIDEO_ALLOW_SCREENSAVER
import org.lwjgl.sdl.SDLHints.SDL_HINT_VIDEO_DRIVER
import org.lwjgl.sdl.SDLHints.SDL_SetHint
import org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO
import org.lwjgl.sdl.SDLInit.SDL_Init
import org.lwjgl.sdl.SDLInit.SDL_Quit
import org.lwjgl.sdl.SDLProperties.SDL_GetNumberProperty
import org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty
import org.lwjgl.sdl.SDLVideo.SDL_GetCurrentVideoDriver
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowProperties
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_WAYLAND_DISPLAY_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_WAYLAND_SURFACE_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_X11_DISPLAY_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_X11_WINDOW_NUMBER

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

/** What SDL was started with, for as long as it runs. */
internal class Sdl private constructor(
	val video: Video,
	val drawer: Drawer,
) : AutoCloseable {
	override fun close() = SDL_Quit()

	companion object {
		/**
		 * SDL started for windows, as [appId] (Wayland's app id and X11's class, which a desktop matches its entry and
		 * icon by). On Linux, Wayland is preferred where the session has it, and X11 is the way back; `SDL_VIDEO_DRIVER`
		 * set by the user says otherwise.
		 */
		fun start(appId: String, drawing: Drawing): Sdl {
			SDL_SetHint(SDL_HINT_APP_ID, appId)
			SDL_SetHint(SDL_HINT_APP_NAME, appId)
			// A click on a window that is not focused acts as well as focusing it, as on every desktop but macOS.
			SDL_SetHint(SDL_HINT_MOUSE_FOCUS_CLICKTHROUGH, "1")
			SDL_SetHint(SDL_HINT_VIDEO_ALLOW_SCREENSAVER, "1")
			if (System.getenv("SDL_VIDEO_DRIVER") == null &&
				System.getenv("WAYLAND_DISPLAY") != null
			)
				SDL_SetHint(SDL_HINT_VIDEO_DRIVER, "wayland,x11")

			if (!SDL_Init(SDL_INIT_VIDEO))
				throw NoDisplay(SDL_GetError() ?: "SDL could not start its video")

			val video = Video.of(SDL_GetCurrentVideoDriver())

			Pixels.Gpu.configure()

			return Sdl(video, drawerOf(drawing, video))
		}
	}
}
