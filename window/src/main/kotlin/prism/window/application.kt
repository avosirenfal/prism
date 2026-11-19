@file:OptIn(InternalComposeUiApi::class)

package prism.window

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import java.lang.foreign.MemorySegment
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_QUIT
import org.lwjgl.sdl.SDLKeyboard.SDL_GetModState
import org.lwjgl.sdl.SDLKeycode.SDL_KMOD_GUI
import org.lwjgl.sdl.SDLMouse.SDL_DestroyCursor
import org.lwjgl.sdl.SDLProperties.SDL_GetGlobalProperties
import org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty
import org.lwjgl.sdl.SDLVideo.SDL_PROP_GLOBAL_VIDEO_WAYLAND_WL_DISPLAY_POINTER
import org.lwjgl.sdl.SDL_Event
import prism.shared.Published
import prism.shared.ScrollDirection

/*
 * An application of windows, as Compose's own `application` and `Window` are used, on SDL's windows: a composition
 * of windows, each window opened while it is composed and closed when it leaves, and the thread that called
 * [application] the windows' thread until the application ends.
 *
 * A window is a composition of its own, with a recomposer of its own, so what goes wrong in one (composing it, laying
 * it out, drawing it, giving it an event, or in any effect or coroutine of its content) is that window's alone: it is
 * told to whoever opened the window ([Window]'s `onFailure`), which closes it, and every other window goes on. Only
 * what goes wrong in the composition of windows itself, which every window is opened from, ends the application.
 */

/** What the application's content can do with the application itself. */
interface ApplicationScope {
	/** The application ended: every window closed, and [application] returns. */
	fun exitApplication()
}

/** What a window's content can do with its window. */
interface WindowScope {
	val window: NativeWindow
}

/** The composition of windows itself failed, or a window nobody answers for did: every window is closed with it. */
class WindowsFailed(
	cause: Throwable,
) : RuntimeException("the windows failed: ${cause.message ?: cause}", cause)

/**
 * Windows, as [content] composes them, until it calls [ApplicationScope.exitApplication]: run on the calling thread,
 * which is the windows' thread meanwhile (on macOS it must be the process's first, `-XstartOnFirstThread`). [appId]
 * names the application to the desktop. Throws [NoDisplay] where there is no display to open windows on, and
 * [WindowsFailed] where the composition of windows fails, or a window does that was given nobody to tell.
 */
fun application(
	appId: String,
	drawing: Drawing = Drawing.configured(),
	content: @Composable ApplicationScope.() -> Unit,
) {
	Ui.claim()
	try {
		Sdl.start(appId, drawing).use { sdl -> Application(sdl).run(content) }
	} finally {
		Ui.release()
	}
}

/** One application's windows, and the loop that runs them. */
internal class Application(
	val sdl: Sdl,
) {
	private val open = HashMap<Int, NativeWindow>()
	val windows: Collection<NativeWindow> get() = open.values
	val drags = Drags(this)

	/**
	 * What is spoken to the platform beside SDL: prism's own connection, where it has one (Wayland's), and the drags
	 * spoken through it.
	 */
	private class Beside(
		val seat: WaylandSeat?,
		val drags: NativeDrags?,
	)

	/** Made before the first window, on SDL's connection: null until then. */
	private var beside: Beside? = null

	/**
	 * prism's own connection to the compositor, where the platform is Wayland: made the first time it is asked for,
	 * which is before the first window is, since what kind of surface a window is can depend on what the compositor
	 * has ([NativeWindow]).
	 */
	internal fun seat(): WaylandSeat? = spoken().seat

	private fun spoken(): Beside {
		beside?.let { return it }

		val display =
			if (sdl.video == Video.Wayland)
				SDL_GetPointerProperty(
					SDL_GetGlobalProperties(),
					SDL_PROP_GLOBAL_VIDEO_WAYLAND_WL_DISPLAY_POINTER,
					0L,
				)
			else
				0L

		val seat =
			display.takeIf { it != 0L }?.let { WaylandSeat.of(MemorySegment.ofAddress(it)) }

		// A Mac's drags are AppKit's; Wayland's are spoken on prism's own connection; elsewhere, this process's alone.
		val made =
			Beside(
				seat,
				if (sdl.video ==
					Video.Cocoa
				)
					CocoaDrags
				else
					NativeDrags.of(seat),
			)

		beside = made
		Log.say { "drags: ${made.drags?.javaClass?.simpleName ?: "this process's own alone"}" }

		return made
	}

	/** The platform's drags, where its protocol is spoken: begun with the first window, which they need to be. */
	val nativeDrags: NativeDrags? get() = beside?.drags
	private val cursors = HashMap<Int, Long>()

	private var running by mutableStateOf(true)
	private var wantsFrame = true

	/** What ended the application, where something went wrong that no window answers for. */
	private var failure: Throwable? = null

	// Whatever fails in the composition of windows, or in an effect of it, comes here rather than to nobody: a
	// composition that has stopped composing opens no window ever again, so the application ends, and says why.
	private val recomposer =
		FrameRecomposer(Ui + CoroutineExceptionHandler { _, failed -> fail(failed) }) {
			wantsFrame = true
			Ui.wake()
		}

	/** The application ended by [failed], which [run] throws once every window is closed. */
	fun fail(failed: Throwable) {
		if (!Ui.isCurrent)
			return Ui.dispatch(EmptyCoroutineContext) { fail(failed) }
		if (failure == null)
			failure = failed
		else
			failure?.addSuppressed(failed)
		running = false
		Ui.wake()
	}

	/** SDL's cursor of [type], made once. */
	fun cursor(type: Int): Long = cursors.getOrPut(type) { createCursor(type) }

	fun opened(window: NativeWindow) {
		open[window.id] = window
		// Heard from before any drag: a drag is begun from a press of the pointer's, which must be heard as it happens.
		spoken()
	}

	/**
	 * A token for bringing forward a window of the application [appId] (where known), as the platform makes it from
	 * the user's last press of a key or a button, and the window it was in, if that is open still; on its way, as the
	 * platform answers. None where the platform makes none.
	 */
	fun activation(appId: String?): CompletableFuture<String>? {
		val seat = beside?.seat ?: return null
		val press = seat.lastPress
		val surface =
			windows
				.mapNotNull { (it.handle as? NativeHandle.Wayland)?.surface }
				.firstOrNull { it == press?.surface }

		Log.say {
			"activation: from the press of serial ${press?.serial}, in ${surface?.let {
				"a window"
			} ?: "none open"}"
		}

		return seat.token(press?.serial, surface?.let(MemorySegment::ofAddress), appId)
	}

	fun closed(window: NativeWindow) {
		open.remove(window.id)
		window.close()
	}

	/** [work] done with [window], and what it throws the window's own failure ([NativeWindow.fail]). */
	private inline fun guarded(window: NativeWindow, work: (NativeWindow) -> Unit) {
		try {
			work(window)
		} catch (failed: Throwable) {
			window.fail(failed)
		}
	}

	fun run(content: @Composable ApplicationScope.() -> Unit) {
		val scope =
			object : ApplicationScope {
				override fun exitApplication() {
					running = false
				}
			}
		val composition = Composition(UnitApplier, recomposer.compositionContext)

		current = this
		try {
			composition.setContent {
				if (running)
					CompositionLocalApplication(this) { scope.content() }
			}

			Queue().use { queue ->
				while (running || open.isNotEmpty()) {
					val turn = Stalls.turn()

					Ui.drain()
					turn?.did("what other threads left for this one")

					val now = System.nanoTime()

					if (wantsFrame) {
						wantsFrame = false
						recomposer.performFrame(now)
					}
					turn?.did("composing")

					open.values.toList().forEach { guarded(it) { window -> window.frame(now) } }
					turn?.did("laying out and drawing")

					val waiting = wantsFrame || Ui.hasWork || open.values.any { it.wantsFrame }

					// What is queued now, before the next frame; what comes meanwhile, after it. Waiting for an event
					// is not a stall, so the turn is over before it; what handling the events took is the next's.
					turn?.done()
					queue.take(if (waiting) 0 else IDLE_MILLIS) { event ->
						val handling = Stalls.turn()

						handle(event)
						handling?.did("an event")
						handling?.done()
					}

					if (!running)
						open.values.toList().forEach(::closed)
				}
			}
		} catch (failed: Throwable) {
			// The loop itself, or the composition of windows as it was first composed: nothing is left to run them.
			fail(failed)
		} finally {
			// Each let go of whatever the one before came to: SDL is stopped after this, whatever is left.
			val closing =
				listOf(
					{ open.values.toList().forEach(::closed) },
					composition::dispose,
					recomposer::close,
					{ beside?.drags?.close() ?: Unit },
					{ beside?.seat?.close() ?: Unit },
					{ cursors.values.forEach(::SDL_DestroyCursor) },
				)

			for (close in closing)
				try {
					close()
				} catch (failed: Throwable) {
					fail(failed)
				}
		}

		current = null
		failure?.let { throw WindowsFailed(it) }
	}

	private fun handle(event: SDL_Event) {
		when (event.type()) {
			// The platform's own way of ending an application (a display that went away, macOS's Quit): closing the
			// last window is not one (sdl.kt), since which windows are open is the application's to say.
			SDL_EVENT_QUIT ->
				// On a Mac, Command and Q quits "the application", which to its user is the windows in front of them:
				// each is asked to close, as its own button asks, and whether the process goes on without them is the
				// application's to say, as it is when the last is closed. A daemon stays. Asked to quit any other way
				// (its menu, the Dock, the user logging out), it ends.
				if (sdl.video == Video.Cocoa && SDL_GetModState().toInt() and SDL_KMOD_GUI != 0 &&
					open.isNotEmpty()
				)
					open.values.toList().forEach { window -> guarded(window) { it.onCloseRequest() } }
				else
					running = false
			Ui.wakeType -> Unit
			// Every event of a window's says which, in the same place.
			else ->
				open[event.window().windowID()]?.let {
					guarded(it) { window -> window.handle(event) }
				}
		}
	}

	companion object {
		/** How long the loop waits for an event before looking again at what it was asked to do meanwhile. */
		private const val IDLE_MILLIS = 250

		/** The application running, of which there is one at a time ([Ui] has one thread); null where none is. */
		var current: Application? by Published(null)
			private set
	}
}

/** Which way the wheel scrolls in the windows composed within: as the system has it, unless the user asked otherwise. */
val LocalScrollDirection =
	androidx.compose.runtime.compositionLocalOf { ScrollDirection.AsTheSystem }

/** The application the content of [content] is in, for the windows it opens. */
private val LocalApplication =
	androidx.compose.runtime
		.staticCompositionLocalOf<Application?> {
			null
		}

@Composable
private fun CompositionLocalApplication(
	app: Application,
	content: @Composable () -> Unit,
) = androidx.compose.runtime.CompositionLocalProvider(
	LocalApplication provides app,
	content = content,
)

/**
 * A window, open for as long as this is composed: [title], first [size] (in dp), with the platform's title bar where
 * [decorated], showing [content]; [at] a place on the screen, in the platform's points, where the platform lets an
 * application place its windows (not Wayland's), and where the platform likes otherwise. [onCloseRequest] is what closing it by its title bar (or the platform's own way)
 * asks for; whether it closes is the caller's, by composing it no longer.
 *
 * [onFailure] is told what went wrong in the window, once: it could not be made, or something failed as its content
 * was composed, laid out, drawn or given an event, or in an effect of its content. A window that failed draws nothing
 * more and takes no more events, and is to be closed, as any window is, by composing it no longer. Without
 * [onFailure], a window's failure is the application's, and ends it ([WindowsFailed]).
 *
 * [activation] is the token the desktop gave whoever asked for this window (`XDG_ACTIVATION_TOKEN`, from a launcher
 * or another program), which lets a Wayland compositor give the window the keyboard as it opens, as it would not to
 * a window that opened unasked.
 *
 * A window that is not [resizable] keeps the size it opened at, which a window manager that tiles takes for a dialog's:
 * it is left floating over the tiles, where one that can be resized is made a tile.
 *
 * [role] is what the window is among the others on the screen (role.kt): a window as the platform has them, or over
 * them all, as a launcher is, where the platform has a way.
 *
 * [backdrop] is what is behind what the window draws (backdrop.kt): nothing, or, for a window that asks to be
 * [Backdrop.Clear], whatever is behind the window, where the platform and the way it is drawn can blend it. Whether it
 * turned out so is [NativeWindow.backdrop], which the content reads ([LocalNativeWindow]) before it leaves anything
 * unpainted.
 */
@Composable
fun Window(
	onCloseRequest: () -> Unit,
	title: String,
	size: DpSize,
	decorated: Boolean = true,
	at: IntOffset? = null,
	onFailure: ((Throwable) -> Unit)? = null,
	activation: String? = null,
	resizable: Boolean = true,
	role: WindowRole = WindowRole.Normal,
	backdrop: Backdrop = Backdrop.Opaque,
	content: @Composable WindowScope.() -> Unit,
) {
	val app =
		checkNotNull(LocalApplication.current) { "a Window is opened within an application" }
	val locals = currentCompositionLocalContext
	val latest by rememberUpdatedState(content)
	val close by rememberUpdatedState(onCloseRequest)
	val failed by rememberUpdatedState(onFailure)
	val scrolling = LocalScrollDirection.current

	// Made here, in the composition of windows, but never failing it: a window that cannot be made, or whose content
	// cannot be composed for the first time, is one window's failure.
	val made =
		remember {
			runCatching {
				val window =
					NativeWindow(app, title, size, decorated, at, activation, resizable, role, backdrop)

				val scope =
					object : WindowScope {
						override val window: NativeWindow = window
					}

				app.opened(window)
				window.onCloseRequest = { close() }
				window.onFailure = { failure -> failed?.invoke(failure) ?: app.fail(failure) }
				try {
					window.setContent(locals) { scope.latest() }
				} catch (failure: Throwable) {
					app.closed(window)

					throw failure
				}

				window
			}
		}

	val window = made.getOrNull()

	LaunchedEffect(made) {
		made.exceptionOrNull()?.let { failure -> failed?.invoke(failure) ?: app.fail(failure) }
	}

	if (window == null)
		return

	SideEffect {
		window.title = title
		window.scrolling = scrolling
		window.updateLocals(locals)
	}

	DisposableEffect(window) {
		onDispose { app.closed(window) }
	}
}

/** A drag begun from this window, carrying [outgoing]: given to the place it is let go on, and [onEnd] told how it ended. */
fun NativeWindow.startDrag(outgoing: Outgoing, onEnd: (DragEnd) -> Unit) =
	app.drags.start(this, outgoing, onEnd)

/** The composition of windows has no nodes of its own: each window is a window of SDL's. */
private object UnitApplier : AbstractApplier<Unit>(Unit) {
	override fun insertTopDown(index: Int, instance: Unit) = Unit

	override fun insertBottomUp(index: Int, instance: Unit) = Unit

	override fun remove(index: Int, count: Int) = Unit

	override fun move(from: Int, to: Int, count: Int) = Unit

	override fun onClear() = Unit
}
