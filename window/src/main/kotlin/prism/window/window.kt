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
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.awt.Cursor
import java.nio.file.Path
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
import org.lwjgl.sdl.SDLVideo.SDL_CreateWindow
import org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowDisplayScale
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowID
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowPixelDensity
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels
import org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow
import org.lwjgl.sdl.SDLVideo.SDL_SetWindowPosition
import org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize
import org.lwjgl.sdl.SDLVideo.SDL_SetWindowTitle
import org.lwjgl.sdl.SDLVideo.SDL_ShowWindow
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_BORDERLESS
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OPENGL
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_RESIZABLE
import org.lwjgl.sdl.SDL_Event
import org.lwjgl.system.MemoryStack

/** The window something is drawn in: given to everything in it, as [LocalNativeWindow]. */
val LocalNativeWindow = staticCompositionLocalOf<NativeWindow?> { null }

/**
 * A window of SDL's, with a Compose scene in it: drawn as [Pixels] say, each event SDL has for it given to the scene
 * as Compose's own windows give theirs. Made, used and closed on the windows' thread ([Ui]).
 */
class NativeWindow internal constructor(
	internal val app: Application,
	title: String,
	size: DpSize,
	decorated: Boolean,
	at: IntOffset?,
) {
	private val place = at
	private val sdl: Long
	internal val id: Int

	/** What the window is to its platform. */
	val handle: NativeHandle

	/** The platform this window is on. */
	val video: Video get() = app.sdl.video

	private val pixels: Pixels

	/** What closing the window by its title bar, or the platform's own means, asks for. */
	internal var onCloseRequest: () -> Unit = {}

	internal val drops = Drops()

	private var density by mutableStateOf(Density(1f))
	private var pixelSize = IntSize.Zero
	private var focused by mutableStateOf(false)
	private var closed = false

	/** Whether the scene asked to be drawn again: what the windows' thread draws, at the next frame. */
	internal var wantsFrame = true
		private set

	private val recomposer = FrameRecomposer(Ui) { invalidate() }

	private val info =
		object : WindowInfo {
			override val isWindowFocused: Boolean get() = focused
			override val keyboardModifiers: PointerKeyboardModifiers get() =
				modifiersOf(
					SDL_GetModState().toInt(),
				)
			override val containerSize: IntSize get() = pixelSize
		}

	private val context =
		object : PlatformContext {
			override val windowInfo: WindowInfo = info

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

	/** Whether the window is on the screen yet. */
	private var shown = false

	/** Whether a frame has ever been put on the screen: a window none can be put on is said to be so, not left blank. */
	private var drawn = false

	init {
		val gpu = app.sdl.drawer == Drawer.Gpu
		val flags =
			SDL_WINDOW_RESIZABLE or SDL_WINDOW_HIGH_PIXEL_DENSITY or SDL_WINDOW_HIDDEN or
				(if (gpu) SDL_WINDOW_OPENGL else 0L) or
				(if (decorated) 0L else SDL_WINDOW_BORDERLESS)

		sdl =
			SDL_CreateWindow(title, size.width.value.toInt(), size.height.value.toInt(), flags)
		check(sdl != 0L) {
			org.lwjgl.sdl.SDLError
				.SDL_GetError() ?: "SDL made no window"
		}
		id = SDL_GetWindowID(sdl)
		handle = NativeHandle.of(sdl, video)

		// Sized in Compose's units: SDL sizes a window in the platform's points, and a dp is the display's scale of a
		// pixel, which a point may be only part of (Wayland's, macOS's).
		val scale = SDL_GetWindowDisplayScale(sdl).takeIf { it > 0f } ?: 1f
		val perPoint = SDL_GetWindowPixelDensity(sdl).takeIf { it > 0f } ?: 1f

		SDL_SetWindowSize(
			sdl,
			(size.width.value * scale / perPoint).toInt(),
			(size.height.value * scale / perPoint).toInt(),
		)

		at?.let { SDL_SetWindowPosition(sdl, it.x, it.y) }
		pixels =
			(
				if (gpu)
					Pixels.Gpu.of(sdl, video)
				else
					null
			)
				// Drawn by the CPU: on Wayland into buffers of prism's own, since SDL has none there but through EGL.
				?: (handle as? NativeHandle.Wayland)?.let(WaylandPixels::of)
				?: Pixels.Cpu(sdl)
		Log.say { "window $id: ${pixels.javaClass.simpleName}" }

		// A Wayland window takes a buffer only once the compositor has said how it is to be: shown first, then drawn.
		if (pixels is WaylandPixels) {
			SDL_ShowWindow(sdl)
			shown = true
		}
		density = Density(scale)
		pixelSize = pixelSizeOf()

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

	internal fun invalidate() {
		if (!wantsFrame) {
			wantsFrame = true
			Ui.wake()
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
		if (closed || !wantsFrame)
			return

		wantsFrame = false

		val drew =
			pixels.frame(pixelSize.width, pixelSize.height) { canvas ->
				canvas.clear(0)
				recomposer.performFrame(nanos)
				scene.measureAndLayout()
				scene.draw(canvas.asComposeCanvas())
			}

		if (drew)
			drawn = true
		else if (!drawn)
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
		if (!shown) {
			shown = true
			SDL_ShowWindow(sdl)
			// Placed again once shown: a window not yet on the screen is placed as the platform likes when it is.
			place?.let { SDL_SetWindowPosition(sdl, it.x, it.y) }
		}

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

	/** One of SDL's events for this window, handled. */
	internal fun handle(event: SDL_Event) {
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
			SDL_EVENT_WINDOW_FOCUS_GAINED -> focused = true
			SDL_EVENT_WINDOW_FOCUS_LOST -> focused = false
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

				drops.hover(
					pixelOf(drop.x(), drop.y()),
					app.drags.ownDropped()?.let {
						DragOver.Own(it.local, dragKeysOf(SDL_GetModState().toInt()))
					}
						?: DragOver.Foreign,
				)
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
				// what other programs are given of it.
				val own = app.drags.ownDropped()

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

	internal val sdlWindow: Long get() = sdl

	private fun cursorFor(icon: PointerIcon) {
		val type = awtCursorOf(icon)?.type ?: Cursor.DEFAULT_CURSOR

		SDL_SetCursor(app.cursor(systemCursorOf(type)))
	}

	internal fun close() {
		if (closed)
			return

		closed = true
		app.drags.closed(this)
		scene.close()
		recomposer.close()
		pixels.close()
		SDL_DestroyWindow(sdl)
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
