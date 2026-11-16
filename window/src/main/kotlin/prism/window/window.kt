@file:OptIn(InternalComposeUiApi::class)

package prism.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.awt.Cursor
import java.lang.foreign.MemorySegment
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_BEGIN
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_COMPLETE
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_FILE
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_POSITION
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_TEXT
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_DOWN
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_UP
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_MOTION
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_WHEEL
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_TEXT_INPUT
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_EXPOSED
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FOCUS_GAINED
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FOCUS_LOST
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_MOUSE_ENTER
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_MOUSE_LEAVE
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_RESIZED
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_SHOWN
import org.lwjgl.sdl.SDLKeyboard.SDL_GetModState
import org.lwjgl.sdl.SDLKeyboard.SDL_StartTextInput
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_ALT
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CAPS
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_GUI
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_MODE
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_NUM
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_SHIFT
import org.lwjgl.sdl.SDLMouse.SDL_BUTTON_LEFT
import org.lwjgl.sdl.SDLMouse.SDL_BUTTON_MIDDLE
import org.lwjgl.sdl.SDLMouse.SDL_BUTTON_RIGHT
import org.lwjgl.sdl.SDLMouse.SDL_BUTTON_X1
import org.lwjgl.sdl.SDLMouse.SDL_BUTTON_X2
import org.lwjgl.sdl.SDLMouse.SDL_CreateSystemCursor
import org.lwjgl.sdl.SDLMouse.SDL_MOUSEWHEEL_FLIPPED
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_CROSSHAIR
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_DEFAULT
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_EW_RESIZE
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_E_RESIZE
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_MOVE
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_NE_RESIZE
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_NS_RESIZE
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_NW_RESIZE
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_N_RESIZE
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_POINTER
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_SE_RESIZE
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_SW_RESIZE
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_S_RESIZE
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_TEXT
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_WAIT
import org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_W_RESIZE
import org.lwjgl.sdl.SDLMouse.SDL_SetCursor
import org.lwjgl.sdl.SDLProperties.SDL_CreateProperties
import org.lwjgl.sdl.SDLProperties.SDL_DestroyProperties
import org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty
import org.lwjgl.sdl.SDLProperties.SDL_SetBooleanProperty
import org.lwjgl.sdl.SDLProperties.SDL_SetNumberProperty
import org.lwjgl.sdl.SDLProperties.SDL_SetPointerProperty
import org.lwjgl.sdl.SDLProperties.SDL_SetStringProperty
import org.lwjgl.sdl.SDLStdinc.SDL_GetEnvironment
import org.lwjgl.sdl.SDLStdinc.SDL_SetEnvironmentVariable
import org.lwjgl.sdl.SDLStdinc.SDL_UnsetEnvironmentVariable
import org.lwjgl.sdl.SDLVideo.SDL_CreateWindow
import org.lwjgl.sdl.SDLVideo.SDL_CreateWindowWithProperties
import org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow
import org.lwjgl.sdl.SDLVideo.SDL_GetDisplayName
import org.lwjgl.sdl.SDLVideo.SDL_GetDisplayProperties
import org.lwjgl.sdl.SDLVideo.SDL_GetDisplays
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowDisplayScale
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowID
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowPixelDensity
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowProperties
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels
import org.lwjgl.sdl.SDLVideo.SDL_PROP_DISPLAY_WAYLAND_WL_OUTPUT_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_COCOA_WINDOW_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_HEIGHT_NUMBER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_HIDDEN_BOOLEAN
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_TITLE_STRING
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_TRANSPARENT_BOOLEAN
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_WAYLAND_SURFACE_ROLE_CUSTOM_BOOLEAN
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_WIDTH_NUMBER
import org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_WAYLAND_SURFACE_POINTER
import org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow
import org.lwjgl.sdl.SDLVideo.SDL_SetWindowPosition
import org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize
import org.lwjgl.sdl.SDLVideo.SDL_SetWindowTitle
import org.lwjgl.sdl.SDLVideo.SDL_ShowWindow
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_ALWAYS_ON_TOP
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_BORDERLESS
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OPENGL
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_RESIZABLE
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_TRANSPARENT
import org.lwjgl.sdl.SDL_Event
import org.lwjgl.system.MemoryStack
import prism.macos.Panel

/** The window something is drawn in: given to everything in it, as [LocalNativeWindow]. */
val LocalNativeWindow = staticCompositionLocalOf<NativeWindow?> { null }

/**
 * A window of SDL's, with a Compose scene in it: drawn as [Pixels] say, each event SDL has for it given to the scene
 * as Compose's own windows give theirs. Made, used and closed on the windows' thread ([Ui]).
 *
 * Its scene and its recomposer are its own, so what fails in it fails it alone ([fail]): it is told to [onFailure]
 * once, and from then on the window draws nothing and takes no event, until it is closed.
 */
class NativeWindow internal constructor(
	internal val app: Application,
	title: String,
	size: DpSize,
	decorated: Boolean,
	at: IntOffset?,
	/** The desktop's token for whoever asked for this window, which lets it be given the keyboard as it is shown. */
	private val activation: String? = null,
	/**
	 * Whether its size can be changed by the user. One that cannot is, to a window manager that tiles, a dialog: left
	 * floating at the size it asked for, over the windows it tiles, rather than made a tile among them.
	 */
	resizable: Boolean = true,
	/** What it is among the others on the screen: a window as the platform has them, or over them all (role.kt). */
	role: WindowRole = WindowRole.Normal,
	/** What is behind what it draws, as it asks: its own, or whatever is behind the window (backdrop.kt). */
	backdrop: Backdrop = Backdrop.Opaque,
) {
	private val place = at
	private val sdl: Long
	internal val id: Int

	/** What the window is to its platform. */
	val handle: NativeHandle

	/** The layer surface the window is, where it is an overlay and the compositor has layers; closed before it. */
	private val layer: LayerSurface?

	/** The Mac's panel this is, where it is one: closed with the window, after SDL has let go of it. */
	private val panel: Panel?

	/**
	 * Whether the window is over every other, as an overlay asks to be: false for a window of the usual kind, and for
	 * an overlay where the platform has none, which is then a window in front as far as the platform keeps one there.
	 */
	val overlaid: Boolean get() = layer != null || panel != null

	/**
	 * What is behind what the window draws, as it turned out: clear only where it asked to be and the platform gave its
	 * pixels an alpha channel that something blends by. What draws a shape that is not the window's rectangle draws it
	 * only where this is clear: on an opaque window what is not painted is black.
	 */
	val backdrop: Backdrop

	/** The platform this window is on. */
	val video: Video get() = app.sdl.video

	/** How prism's windows are drawn, and why: on the GPU or the CPU, and what OpenGL is here. */
	val rendering: Rendering get() = app.sdl.rendering

	/** How this window turned out to be drawn: by OpenGL, or on the CPU, and why. */
	val drawnOn: Drawn

	private val pixels: Pixels

	/** What closing the window by its title bar, or the platform's own means, asks for. */
	internal var onCloseRequest: () -> Unit = {}

	/** Who is told what went wrong in the window, once: the application itself, until someone else is. */
	internal var onFailure: (Throwable) -> Unit = app::fail

	/**
	 * Where the window is in its life (life.kt): on the screen or not yet, with a frame in it or none, failed,
	 * closed. Read and written on the windows' thread alone.
	 */
	private var life: Life = Life.Hidden

	internal val drops = Drops()

	private var density by mutableStateOf(Density(1f))
	private var pixelSize = IntSize.Zero
	private var focused by mutableStateOf(false)

	/** Whether the scene asked to be drawn again: what the windows' thread draws, at the next frame. */
	internal var wantsFrame = true
		private set

	// What fails as the window's content is composed again, or in an effect or a coroutine of it, never reaches
	// whoever asked for the frame: the recomposer's own coroutine ends with it, and tells whoever is in its context.
	private val recomposer =
		FrameRecomposer(
			Ui +
				CoroutineExceptionHandler {
					_,
					failure,
					->
					fail(failure)
				},
		) { invalidate() }

	private val info =
		object : WindowInfo {
			override val isWindowFocused: Boolean get() = focused
			override val keyboardModifiers: PointerKeyboardModifiers get() =
				modifiersOf(
					SDL_GetModState().toInt(),
				)
			override val containerSize: IntSize get() = pixelSize
		}

	/** The window's layout trees, as Compose makes and disposes of them: read only to say what a failed frame was laying out. */
	private val owners = LinkedHashSet<SemanticsOwner>()

	// Compose's own for what a window of prism's does not do itself: the clipboard, links, fonts, background work.
	private val context =
		object : PlatformContext.Empty() {
			override val windowInfo: WindowInfo = info

			override val semanticsOwnerListener =
				object : PlatformContext.SemanticsOwnerListener {
					override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
						owners += semanticsOwner
					}

					override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
						owners -= semanticsOwner
					}

					override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit

					override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) =
						Unit
				}

			override val inputModeManager: InputModeManager =
				object : InputModeManager {
					override val inputMode: InputMode = InputMode.Keyboard

					override fun requestInputMode(inputMode: InputMode): Boolean =
						inputMode == InputMode.Keyboard
				}

			override fun setPointerIcon(pointerIcon: PointerIcon) = cursorFor(pointerIcon)

			override fun requestFocus(): Boolean = true
		}

	private val scene: ComposeScene

	init {
		val gpu = app.sdl.rendering.onGpu
		val layers = app.seat()?.layers
		val surfacing = surfaceFor(role, video, layers?.version, decorated, resizable)
		val clear = mayBeClear(backdrop, video, X11Compositing::present)

		// A window to be clear is made transparent, for which SDL chooses what has an alpha channel (a visual, an EGL
		// configuration) and leaves the surface's opaque region unset; OpenGL is asked for alpha only for its making.
		if (gpu)
			Pixels.Gpu.configure(alpha = clear)

		// An overlay in the compositor's overlay layer, where it has one and will have this; and a window of the
		// platform's own otherwise, as an overlay is where there are no layers at all.
		val layered =
			(surfacing as? Surfacing.Layer)?.let { asked ->
				layers?.let { layered(it, asked, title, size, gpu, clear) }
			}

		// A Mac's overlay is a panel of prism's making, which SDL is given for its window; where none can be made,
		// a window of SDL's, as an overlay is where a platform has nothing for one.
		val panelled =
			(surfacing as? Surfacing.Panel)?.let {
				panelled(
					title,
					size,
					gpu,
					clear,
				)
			}

		// What a window of the platform's own looks like, where this is one: the overlay's fallback, where an overlay
		// could not be what its platform has for one.
		val looks =
			surfacing as? Surfacing.Toplevel
				?: Surfacing.Toplevel(
					decorated = false,
					resizable = false,
					inFront =
						video != Video.Wayland,
				)

		panel = panelled?.second
		sdl =
			layered?.first
				?: panelled?.first
				?: SDL_CreateWindow(
					title,
					size.width.value.toInt(),
					size.height.value.toInt(),
					SDL_WINDOW_HIGH_PIXEL_DENSITY or SDL_WINDOW_HIDDEN or
						(if (looks.resizable) SDL_WINDOW_RESIZABLE else 0L) or
						(if (gpu) SDL_WINDOW_OPENGL else 0L) or
						(if (clear) SDL_WINDOW_TRANSPARENT else 0L) or
						(
							if (looks.decorated)
								0L
							else
								SDL_WINDOW_BORDERLESS
						) or
						(
							if (looks.inFront)
								SDL_WINDOW_ALWAYS_ON_TOP
							else
								0L
						),
				)
		check(sdl != 0L) {
			org.lwjgl.sdl.SDLError
				.SDL_GetError() ?: "SDL made no window"
		}
		layer = layered?.second
		id = SDL_GetWindowID(sdl)
		handle = NativeHandle.of(sdl, video)
		if (role is WindowRole.Overlay)
			Log.say {
				"window $id: an overlay, " +
					when {
						layer != null ->
							"in the compositor's overlay layer (layer-shell ${layers?.version})"
						panel != null -> "a non-activating panel"
						surfacing is Surfacing.Panel -> "as a window in its place: no panel could be made"
						surfacing is Surfacing.Layer ->
							"as a window in its place: the compositor would not have it in a layer"
						video == Video.Wayland ->
							"as a window in its place: the compositor has no layer-shell"
						else -> "as a window in its place: $video has no layers"
					}
			}

		// Sized in Compose's units: SDL sizes a window in the platform's points, and a dp is the display's scale of a
		// pixel, which a point may be only part of (Wayland's, macOS's).
		val scale = SDL_GetWindowDisplayScale(sdl).takeIf { it > 0f } ?: 1f

		// A layer surface was sized as it was made, since the compositor is told its size before it is shown.
		if (layer == null) {
			pointsOf(sdl, size).let { (width, height) -> SDL_SetWindowSize(sdl, width, height) }
			at?.let { SDL_SetWindowPosition(sdl, it.x, it.y) }
		}

		// Drawn by the CPU: on Wayland into buffers of prism's own, since SDL has none there but through EGL.
		fun onCpu(): Pixels =
			(handle as? NativeHandle.Wayland)?.let { WaylandPixels.of(it, opaque = !clear) }
				?: Pixels.Cpu(sdl)

		val made =
			if (gpu)
				when (val gpu = Pixels.Gpu.of(sdl, video)) {
					is Pixels.Made.Drawing -> gpu.gpu to Drawn.ByOpenGl
					is Pixels.Made.Failed -> onCpu() to Drawn.FellBack(gpu.why)
				}
			else
				onCpu() to Drawn.OnCpu

		pixels = made.first
		drawnOn = made.second
		if (gpu)
			Pixels.Gpu.configure(alpha = false)
		this.backdrop = backdropOf(clear, pixels.alpha)
		if (backdrop == Backdrop.Clear)
			Log.say {
				"window $id: asked to be clear, and is ${this.backdrop.name.lowercase()}" +
					when {
						this.backdrop == Backdrop.Clear -> ""
						!clear && video == Video.X11 -> ": nothing composites this X11 screen"
						!clear -> ": $video has no clear windows yet"
						else -> ": its pixels have no alpha channel (${pixels.javaClass.simpleName})"
					}
			}
		// Said whatever the log says: a window that should have been drawn on the GPU and is not costs the CPU dearly.
		(drawnOn as? Drawn.FellBack)?.let {
			System.err.println(
				"prism: window $id is drawn on the CPU, since OpenGL could not draw it: ${it.why}",
			)
		}
		Log.say { "window $id: ${pixels.javaClass.simpleName}" }

		// A Wayland window takes a buffer only once the compositor has said how it is to be: shown first, then drawn.
		if (pixels is WaylandPixels) {
			show()
			life = Life.Shown(drawn = false)
		}
		density = Density(scale)
		pixelSize = pixelSizeOf()

		// Compose draws and sets text through whatever implementation is registered for the process, Skia's here; its
		// own windows register it as they open, and so does this one (again is nothing).
		registerSkikoComposeImplementation()
		scene =
			CanvasLayersComposeScene(
				frameRecomposer = recomposer,
				density = density,
				size = pixelSize,
				platformContext = context,
				invalidateLayout = ::invalidate,
				invalidateDraw = ::invalidate,
			)

		// Text typed arrives as text, from the platform's input method, for as long as the window is open.
		SDL_StartTextInput(sdl)
	}

	/**
	 * A panel made for a window [size] across, and SDL's window of it; null where either could not be made, the panel
	 * closed again. SDL draws in a window it is given and hears its events, and leaves closing it to its maker.
	 */
	private fun panelled(
		title: String,
		size: DpSize,
		gpu: Boolean,
		clear: Boolean,
	): Pair<Long, Panel>? {
		val panel =
			try {
				Panel.make(size.width.value.toDouble(), size.height.value.toDouble())
			} catch (failed: Throwable) {
				Log.say { "no panel could be made (${failed.message ?: failed}): a window instead" }

				return null
			}
		val properties = SDL_CreateProperties()

		SDL_SetPointerProperty(
			properties,
			SDL_PROP_WINDOW_CREATE_COCOA_WINDOW_POINTER,
			panel.window,
		)
		SDL_SetBooleanProperty(
			properties,
			SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN,
			true,
		)
		SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN, gpu)
		SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_TRANSPARENT_BOOLEAN, clear)
		SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_HIDDEN_BOOLEAN, true)
		SDL_SetStringProperty(properties, SDL_PROP_WINDOW_CREATE_TITLE_STRING, title)

		val window = SDL_CreateWindowWithProperties(properties)

		SDL_DestroyProperties(properties)
		if (window == 0L) {
			Log.say {
				"SDL took no panel for a window (${org.lwjgl.sdl.SDLError.SDL_GetError()}): a window instead"
			}
			panel.close()

			return null
		}

		return window to panel
	}

	/**
	 * A window of SDL's with no role, given the role of a layer surface as [asked] says, and configured: the window,
	 * and what it is. Null where SDL makes no such window, or the compositor does not answer for it, when the window
	 * is done away with and one of the usual kind is made in its place.
	 */
	private fun layered(
		layers: Layers,
		asked: Surfacing.Layer,
		title: String,
		size: DpSize,
		gpu: Boolean,
		clear: Boolean,
	): Pair<Long, LayerSurface>? {
		val properties = SDL_CreateProperties()

		SDL_SetBooleanProperty(
			properties,
			SDL_PROP_WINDOW_CREATE_WAYLAND_SURFACE_ROLE_CUSTOM_BOOLEAN,
			true,
		)
		// With this, SDL scales the surface itself, as it does a window's: sizes are in points, drawing in pixels.
		SDL_SetBooleanProperty(
			properties,
			SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN,
			true,
		)
		SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN, gpu)
		SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_TRANSPARENT_BOOLEAN, clear)
		SDL_SetStringProperty(properties, SDL_PROP_WINDOW_CREATE_TITLE_STRING, title)
		SDL_SetNumberProperty(
			properties,
			SDL_PROP_WINDOW_CREATE_WIDTH_NUMBER,
			size.width.value.toLong(),
		)
		SDL_SetNumberProperty(
			properties,
			SDL_PROP_WINDOW_CREATE_HEIGHT_NUMBER,
			size.height.value.toLong(),
		)

		val window = SDL_CreateWindowWithProperties(properties)

		SDL_DestroyProperties(properties)
		if (window == 0L)
			return null

		val surface =
			SDL_GetPointerProperty(
				SDL_GetWindowProperties(window),
				SDL_PROP_WINDOW_WAYLAND_SURFACE_POINTER,
				0L,
			)

		val (width, height) = pointsOf(window, size)

		SDL_SetWindowSize(window, width, height)

		val made =
			layers.surface(
				MemorySegment.ofAddress(surface),
				outputOf(asked.screen),
				asked.layer,
				width,
				height,
				// Told on the seat's thread; done on the windows'. SDL is told the surface's size, which it has no
				// other way to know, and says in its turn that the window was resized.
				onSized = { wide, high ->
					Ui.dispatch(EmptyCoroutineContext) {
						if (life !is Life.Closed)
							SDL_SetWindowSize(window, wide, high)
					}
				},
				onClosed = { Ui.dispatch(EmptyCoroutineContext) { onCloseRequest() } },
			)

		if (made == null) {
			SDL_DestroyWindow(window)

			return null
		}
		made.size?.let { (wide, high) -> SDL_SetWindowSize(window, wide, high) }

		return window to made
	}

	/**
	 * An overlay that shares the keyboard, on the screen for the first time, and what the compositor made of its
	 * asking for the keyboard on demand (waylandoverlay.kt): a moment on, it is told whether it was given it.
	 */
	private fun awaitKeyboard(layer: LayerSurface) {
		if (layer.grip != Grip.Asking)
			return

		CompletableFuture.delayedExecutor(UNGIVEN_MILLIS, TimeUnit.MILLISECONDS).execute {
			Ui.dispatch(EmptyCoroutineContext) { keyboard(layer, Told.Waited(has = focused)) }
		}
	}

	/**
	 * What became of the keyboard [told] to a layer surface, which asks the compositor for whatever follows from it
	 * ([Grip.told]); said, where that is something it did not ask before.
	 */
	private fun keyboard(layer: LayerSurface, told: Told) {
		layer.told(told).toAsk?.let { Log.say { "window $id: ${it.said}" } }
	}

	internal fun invalidate() {
		if (!wantsFrame && !life.failed) {
			wantsFrame = true
			Ui.wake()
		}
	}

	/**
	 * Something went wrong in the window: [failure] told to [onFailure], the first time, and the window left as it is,
	 * drawing nothing more and taking no more events, for whoever opened it to close. What its telling throws is the
	 * application's.
	 */
	internal fun fail(failure: Throwable) {
		// A coroutine of the content's that fails on another thread fails the window here, on its own.
		if (!Ui.isCurrent)
			return Ui.dispatch(EmptyCoroutineContext) { fail(failure) }
		life = life.failing() ?: return
		wantsFrame = false
		try {
			onFailure(failure)
		} catch (untold: Throwable) {
			app.fail(untold.also { it.addSuppressed(failure) })
		}
	}

	/** The window put on the screen, asking the compositor for the keyboard with [activation] where it was given one. */
	private fun show() {
		// SDL activates a Wayland window as it shows it with the token its environment holds, as a program started by
		// a launcher finds it there; for a window asked for by another process, the token is put there for the showing.
		val environment = SDL_GetEnvironment()

		if (activation != null && environment != 0L) {
			SDL_SetEnvironmentVariable(environment, ACTIVATION_TOKEN, activation, true)
			SDL_ShowWindow(sdl)
			SDL_UnsetEnvironmentVariable(environment, ACTIVATION_TOKEN)
		} else {
			SDL_ShowWindow(sdl)
		}
	}

	/** What the window shows, with [locals] as its composition's, from the composition that opened it. */
	internal fun setContent(
		locals: CompositionLocalContext,
		content: @Composable () -> Unit,
	) {
		scene.compositionLocalContext = locals
		scene.setContent {
			CompositionLocalProvider(
				LocalNativeWindow provides this,
				LocalClipboard provides ComposeClipboard,
				content = content,
			)
		}
	}

	internal fun updateLocals(locals: CompositionLocalContext) {
		scene.compositionLocalContext = locals
	}

	var title: String = title
		set(value) {
			if (field != value) {
				field = value
				SDL_SetWindowTitle(sdl, value)
			}
		}

	/** The window brought in front of the others, and given the keyboard, where the platform lets it be. */
	fun raise() {
		SDL_RaiseWindow(sdl)
	}

	/** A frame drawn, if one was asked for: the scene's frame, its layout and its drawing, into the window's pixels. */
	internal fun frame(nanos: Long) {
		// As it was before the frame: what the frame is the first of, and what it has shown, is told by that.
		val before = life as? Life.Sound ?: return

		if (!wantsFrame)
			return

		wantsFrame = false

		val drew =
			try {
				pixels.frame(pixelSize.width, pixelSize.height) { canvas ->
					canvas.clear(0)
					recomposer.performFrame(nanos)
					scene.measureAndLayout()
					scene.draw(canvas.asComposeCanvas())
				}
			} catch (error: RuntimeException) {
				// Said with what it was laying out, for a fault of Compose's own to be found; then failed as before.
				System.err.println(frameReport(error, title, rootsOf(owners)))
				throw error
			}

		if (drew && !before.drawn)
			layer?.let(::awaitKeyboard)
		else if (!drew && !before.drawn)
			throw CannotDraw(
				"window $id cannot be drawn (${pixels.javaClass.simpleName}): " +
					(
						org.lwjgl.sdl.SDLError
							.SDL_GetError()
							?.takeIf { it.isNotEmpty() }
							?: "SDL says nothing of why"
					),
			)

		// Shown once there is something in it, rather than empty first.
		if (before == Life.Hidden) {
			show()
			// Placed again once shown: a window not yet on the screen is placed as the platform likes when it is.
			place?.let { SDL_SetWindowPosition(sdl, it.x, it.y) }
		}
		// On the screen with a frame in it from here on; unless it failed as the frame was drawn, which it stays.
		life = life.drawnIn()

		if (scene.hasPendingDraw || scene.hasPendingMeasureOrLayout ||
			recomposer.hasPendingWork()
		)
			invalidate()
	}

	private fun pixelSizeOf(): IntSize =
		MemoryStack.stackPush().use { stack ->
			val width = stack.mallocInt(1)
			val height = stack.mallocInt(1)

			SDL_GetWindowSizeInPixels(sdl, width, height)
			IntSize(width.get(0), height.get(0))
		}

	/** Where SDL's [x], [y] (in the window's points) is in the scene's pixels. */
	private fun pixelOf(x: Float, y: Float): Offset {
		val perPoint = SDL_GetWindowPixelDensity(sdl).takeIf { it > 0f } ?: 1f

		return Offset(x * perPoint, y * perPoint)
	}

	private var pointer = Offset.Zero
	private var pressed = PointerButtons()

	/** One of SDL's events for this window, handled; none but a request to close it, once it has failed. */
	internal fun handle(event: SDL_Event) {
		if (life.failed && event.type() != SDL_EVENT_WINDOW_CLOSE_REQUESTED)
			return

		when (event.type()) {
			SDL_EVENT_WINDOW_CLOSE_REQUESTED -> onCloseRequest()
			SDL_EVENT_WINDOW_RESIZED, SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED -> {
				pixelSize = pixelSizeOf()
				scene.size = pixelSize
				invalidate()
			}
			SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED -> {
				density = Density(SDL_GetWindowDisplayScale(sdl))
				scene.density = density
				pixelSize = pixelSizeOf()
				scene.size = pixelSize
				invalidate()
			}
			SDL_EVENT_WINDOW_EXPOSED, SDL_EVENT_WINDOW_SHOWN -> {
				wantsFrame = false
				invalidate()
			}
			SDL_EVENT_WINDOW_FOCUS_GAINED -> {
				Log.say { "window $id: has the keyboard" }
				focused = true
				layer?.let { keyboard(it, Told.Given(System.nanoTime())) }
			}
			SDL_EVENT_WINDOW_FOCUS_LOST -> {
				Log.say { "window $id: has the keyboard no longer" }
				focused = false
				layer?.let { keyboard(it, Told.Taken(System.nanoTime())) }
			}
			SDL_EVENT_WINDOW_MOUSE_ENTER -> pointerEvent(PointerEventType.Enter)
			SDL_EVENT_WINDOW_MOUSE_LEAVE ->
				if (!app.drags.isDragging)
					pointerEvent(PointerEventType.Exit)
			SDL_EVENT_MOUSE_MOTION -> {
				val motion = event.motion()

				pointer = pixelOf(motion.x(), motion.y())
				if (!app.drags.moved(this, pointer))
					pointerEvent(PointerEventType.Move)
			}
			SDL_EVENT_MOUSE_BUTTON_DOWN, SDL_EVENT_MOUSE_BUTTON_UP -> {
				val press = event.button()

				Log.say {
					"window $id: button ${press.button()} ${if (press.down()) "down" else "up"} at ${press.x()}, ${press.y()}"
				}

				val down = event.type() == SDL_EVENT_MOUSE_BUTTON_DOWN
				val button = buttonOf(press.button().toInt())

				pointer = pixelOf(press.x(), press.y())
				pressed = buttonsWith(pressed, button, down)
				if (down || !app.drags.released(this, pointer))
					pointerEvent(
						if (down)
							PointerEventType.Press
						else
							PointerEventType.Release,
						button,
					)
			}
			SDL_EVENT_MOUSE_WHEEL -> {
				val wheel = event.wheel()

				Log.say {
					"window $id: wheel ${wheel.x()}, ${wheel.y()} at ${wheel.mouse_x()}, ${wheel.mouse_y()}"
				}

				val flip =
					if (wheel.direction() == SDL_MOUSEWHEEL_FLIPPED)
						-1f
					else
						1f

				pointer = pixelOf(wheel.mouse_x(), wheel.mouse_y())
				// SDL's y is up, away from the user; Compose's scrolling is down for a positive y.
				pointerEvent(
					PointerEventType.Scroll,
					scroll =
						Offset(
							wheel.x() * flip,
							-wheel.y() * flip,
						),
				)
			}
			SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP -> {
				val key = event.key()
				val down = key.down()

				if (down && app.drags.keyed(key.key()))
					return

				keyEventOf(down, key.key(), key.scancode(), key.mod().toInt(), video == Video.Cocoa)
					?.let(scene::sendKeyEvent)
			}
			SDL_EVENT_TEXT_INPUT ->
				typedEventsOf(
					event.text().textString() ?: "",
					SDL_GetModState().toInt(),
				).forEach(scene::sendKeyEvent)
			SDL_EVENT_DROP_BEGIN -> foreign = ForeignDrop(emptyList(), null)
			SDL_EVENT_DROP_POSITION -> {
				val drop = event.drop()
				val own = app.drags.ownDropped()

				val effect =
					drops.hover(
						pixelOf(drop.x(), drop.y()),
						own?.let { DragOver.Own(it.local, dragKeysOf(SDL_GetModState().toInt())) }
							?: DragOver.Foreign,
					)

				// The pointer shows what the place under it would make of this process's own drag.
				if (own != null)
					app.drags.overOwn(effect)
				foreign = foreign ?: ForeignDrop(emptyList(), null)
			}
			SDL_EVENT_DROP_FILE ->
				foreign =
					(foreign ?: ForeignDrop(emptyList(), null)).let {
						it.copy(
							files =
								it.files +
									listOfNotNull(
										event.drop().dataString()?.let { path ->
											Path.of(path)
										},
									),
						)
					}
			SDL_EVENT_DROP_TEXT ->
				foreign =
					(foreign ?: ForeignDrop(emptyList(), null)).copy(text = event.drop().dataString())
			SDL_EVENT_DROP_COMPLETE -> {
				val drop = event.drop()

				Log.say {
					"window $id: drop complete, ${foreign?.files?.size ?: 0} files, text ${foreign?.text != null}"
				}

				val at = pixelOf(drop.x(), drop.y())
				val dropped =
					foreign?.let { (files, text) ->
						when {
							files.isNotEmpty() -> Dropped.Files(files)
							text != null -> Dropped.Text(text)
							else -> null
						}
					}

				foreign = null

				// A drop of this process's own drag, by the platform's protocol: what it carries, as itself, rather than
				// what other programs are given of it. Gone from the window, or let go on it, it is shown as the
				// compositor says again.
				val own = app.drags.ownDropped()

				app.drags.offOwn()

				when {
					dropped == null -> drops.leave()
					own != null ->
						app.drags.droppedHere(
							drops.drop(at, Dropped.Own(own.local, dragKeysOf(SDL_GetModState().toInt()))),
						)
					else -> drops.drop(at, dropped)
				}
			}
		}
	}

	/** What another program is dragging over the window, as SDL has said so far. */
	private data class ForeignDrop(
		val files: List<Path>,
		val text: String?,
	)

	private var foreign: ForeignDrop? = null

	private fun pointerEvent(
		type: PointerEventType,
		button: PointerButton? = null,
		scroll: Offset = Offset.Zero,
	) {
		scene.sendPointerEvent(
			eventType = type,
			position = pointer,
			scrollDelta = scroll,
			buttons = pressed,
			keyboardModifiers = modifiersOf(SDL_GetModState().toInt()),
			button = button,
		)
	}

	/** The window's own drag, from here on, as [Drags] says; the scene's gesture let go. */
	internal fun cancelPointer() {
		scene.cancelPointerInput()
		// The button that began it is let go of where the platform carries the drag, and this window never told so.
		pressed = PointerButtons()
	}

	/** The pointer's position in the window, in its pixels. */
	internal val pointerPosition: Offset get() = pointer

	/** How many of the window's pixels a point of the platform's is: more than one on a screen scaled up. */
	internal val pixelsPerPoint: Float get() =
		SDL_GetWindowPixelDensity(sdl).takeIf {
			it >
				0f
		}
			?: 1f

	/** How many of the window's pixels a dp is: the scale its display is shown at. */
	internal val displayScale: Float get() =
		SDL_GetWindowDisplayScale(sdl).takeIf { it > 0f }
			?: 1f

	internal val sdlWindow: Long get() = sdl

	private fun cursorFor(icon: PointerIcon) {
		val type = awtCursorOf(icon)?.type ?: Cursor.DEFAULT_CURSOR

		SDL_SetCursor(app.cursor(systemCursorOf(type)))
	}

	internal fun close() {
		life = life.closing() ?: return

		// Each let go of whatever the one before came to, and SDL's window destroyed whatever they did: a window whose
		// scene failed is closed all the same, and what closing a sound one throws is its failure.
		val closing =
			listOf(
				{ app.drags.closed(this) },
				scene::close,
				recomposer::close,
				pixels::close,
				// The role given up before the surface it is the role of.
				{ layer?.close() ?: Unit },
			)

		for (close in closing)
			try {
				close()
			} catch (failure: Throwable) {
				fail(failure)
			}
		SDL_DestroyWindow(sdl)
		// SDL lets go of a window it was given and closes nothing of it: its maker does.
		panel?.close()
	}

	private companion object {
		/** The variable a program finds the desktop's activation token in, and SDL reads as it shows a window. */
		const val ACTIVATION_TOKEN = "XDG_ACTIVATION_TOKEN"

		/** How long an overlay that shares the keyboard waits to be given it, once it is on the screen, before it holds it. */
		const val UNGIVEN_MILLIS = 150L

		/**
		 * [size], which is in Compose's units, in the platform's points, as [window] is sized: a dp is the display's
		 * scale of a pixel, which a point may be only part of (Wayland's, macOS's).
		 */
		fun pointsOf(window: Long, size: DpSize): Pair<Int, Int> {
			val scale = SDL_GetWindowDisplayScale(window).takeIf { it > 0f } ?: 1f
			val perPoint = SDL_GetWindowPixelDensity(window).takeIf { it > 0f } ?: 1f

			return (size.width.value * scale / perPoint).toInt() to
				(size.height.value * scale / perPoint).toInt()
		}

		/** The `wl_output` of [screen], where it names one SDL knows; none, for the compositor to choose. */
		fun outputOf(screen: Screen): MemorySegment? =
			(screen as? Screen.Named)?.let { named ->
				val displays = SDL_GetDisplays() ?: return null

				(0 until displays.remaining())
					.map(displays::get)
					.firstOrNull { SDL_GetDisplayName(it) == named.name }
					?.let {
						SDL_GetPointerProperty(
							SDL_GetDisplayProperties(it),
							SDL_PROP_DISPLAY_WAYLAND_WL_OUTPUT_POINTER,
							0L,
						)
					}?.takeIf { it != 0L }
					?.let(MemorySegment::ofAddress)
			}
	}
}

/**
 * The AWT cursor a pointer icon is, on the desktop: every one of Compose's is one, in a class Compose keeps to itself,
 * so its cursor is read by its accessor's name.
 */
private fun awtCursorOf(icon: PointerIcon): Cursor? =
	try {
		icon.javaClass.getMethod("getCursor").invoke(icon) as? Cursor
	} catch (missing: NoSuchMethodException) {
		null
	} catch (denied: ReflectiveOperationException) {
		null
	}

/** SDL's button as Compose's. */
private fun buttonOf(button: Int): PointerButton? =
	when (button) {
		SDL_BUTTON_LEFT -> PointerButton.Primary
		SDL_BUTTON_RIGHT -> PointerButton.Secondary
		SDL_BUTTON_MIDDLE -> PointerButton.Tertiary
		SDL_BUTTON_X1 -> PointerButton.Back
		SDL_BUTTON_X2 -> PointerButton.Forward
		else -> null
	}

/** [buttons] with [button] pressed or let go. */
private fun buttonsWith(
	buttons: PointerButtons,
	button: PointerButton?,
	down: Boolean,
): PointerButtons {
	fun now(which: PointerButton, was: Boolean) = if (button == which) down else was

	return PointerButtons(
		isPrimaryPressed = now(PointerButton.Primary, buttons.isPrimaryPressed),
		isSecondaryPressed = now(PointerButton.Secondary, buttons.isSecondaryPressed),
		isTertiaryPressed = now(PointerButton.Tertiary, buttons.isTertiaryPressed),
		isBackPressed = now(PointerButton.Back, buttons.isBackPressed),
		isForwardPressed = now(PointerButton.Forward, buttons.isForwardPressed),
	)
}

/** SDL's modifier state as Compose's. */
internal fun modifiersOf(mods: Int): PointerKeyboardModifiers =
	PointerKeyboardModifiers(
		isCtrlPressed = mods and SDL_KMOD_CTRL != 0,
		isMetaPressed = mods and SDL_KMOD_GUI != 0,
		isAltPressed = mods and SDL_KMOD_ALT != 0,
		isShiftPressed = mods and SDL_KMOD_SHIFT != 0,
		isAltGraphPressed = mods and SDL_KMOD_MODE != 0,
		isCapsLockOn = mods and SDL_KMOD_CAPS != 0,
		isNumLockOn = mods and SDL_KMOD_NUM != 0,
	)

/** The keys held, for a drag. */
internal fun dragKeysOf(mods: Int): DragKeys =
	DragKeys(
		ctrl = mods and SDL_KMOD_CTRL != 0,
		shift = mods and SDL_KMOD_SHIFT != 0,
		alt = mods and SDL_KMOD_ALT != 0,
		meta = mods and SDL_KMOD_GUI != 0,
	)

/** SDL's system cursor for AWT's cursor [type], which is what Compose's pointer icons are on the desktop. */
private fun systemCursorOf(type: Int): Int =
	when (type) {
		Cursor.TEXT_CURSOR -> SDL_SYSTEM_CURSOR_TEXT
		Cursor.HAND_CURSOR -> SDL_SYSTEM_CURSOR_POINTER
		Cursor.CROSSHAIR_CURSOR -> SDL_SYSTEM_CURSOR_CROSSHAIR
		Cursor.WAIT_CURSOR -> SDL_SYSTEM_CURSOR_WAIT
		Cursor.MOVE_CURSOR -> SDL_SYSTEM_CURSOR_MOVE
		Cursor.N_RESIZE_CURSOR -> SDL_SYSTEM_CURSOR_N_RESIZE
		Cursor.S_RESIZE_CURSOR -> SDL_SYSTEM_CURSOR_S_RESIZE
		Cursor.E_RESIZE_CURSOR -> SDL_SYSTEM_CURSOR_E_RESIZE
		Cursor.W_RESIZE_CURSOR -> SDL_SYSTEM_CURSOR_W_RESIZE
		Cursor.NE_RESIZE_CURSOR -> SDL_SYSTEM_CURSOR_NE_RESIZE
		Cursor.NW_RESIZE_CURSOR -> SDL_SYSTEM_CURSOR_NW_RESIZE
		Cursor.SE_RESIZE_CURSOR -> SDL_SYSTEM_CURSOR_SE_RESIZE
		Cursor.SW_RESIZE_CURSOR -> SDL_SYSTEM_CURSOR_SW_RESIZE
		else -> SDL_SYSTEM_CURSOR_DEFAULT
	}.let {
		// Compose's horizontal and vertical resize icons are AWT's E and N ones; SDL draws them double-headed.
		when (it) {
			SDL_SYSTEM_CURSOR_E_RESIZE, SDL_SYSTEM_CURSOR_W_RESIZE -> SDL_SYSTEM_CURSOR_EW_RESIZE
			SDL_SYSTEM_CURSOR_N_RESIZE, SDL_SYSTEM_CURSOR_S_RESIZE -> SDL_SYSTEM_CURSOR_NS_RESIZE
			else -> it
		}
	}

internal fun createCursor(type: Int): Long = SDL_CreateSystemCursor(type)
