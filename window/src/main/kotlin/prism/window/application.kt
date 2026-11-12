@file:OptIn(InternalComposeUiApi::class)

package prism.window

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
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
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_QUIT
import org.lwjgl.sdl.SDLEvents.SDL_PollEvent
import org.lwjgl.sdl.SDLEvents.SDL_WaitEventTimeout
import org.lwjgl.sdl.SDLMouse.SDL_DestroyCursor
import org.lwjgl.sdl.SDL_Event

/*
 * An application of windows, as Compose's own `application` and `Window` are used, on SDL's windows: a composition
 * of windows, each window opened while it is composed and closed when it leaves, and the thread that called
 * [application] the windows' thread until the application ends.
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

/**
 * Windows, as [content] composes them, until it calls [ApplicationScope.exitApplication]: run on the calling thread,
 * which is the windows' thread meanwhile (on macOS it must be the process's first, `-XstartOnFirstThread`). [appId]
 * names the application to the desktop. Throws [NoDisplay] where there is no display to open windows on.
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
	private val cursors = HashMap<Int, Long>()

	private var running by mutableStateOf(true)
	private var wantsFrame = true
	private val recomposer =
		FrameRecomposer(Ui) {
			wantsFrame = true
			Ui.wake()
		}

	/** SDL's cursor of [type], made once. */
	fun cursor(type: Int): Long = cursors.getOrPut(type) { createCursor(type) }

	fun opened(window: NativeWindow) {
		open[window.id] = window
	}

	fun closed(window: NativeWindow) {
		open.remove(window.id)
		window.close()
	}

	fun run(content: @Composable ApplicationScope.() -> Unit) {
		val scope =
			object : ApplicationScope {
				override fun exitApplication() {
					running = false
				}
			}
		val composition = Composition(UnitApplier, recomposer.compositionContext)

		composition.setContent {
			if (running)
				CompositionLocalApplication(this) { scope.content() }
		}

		SDL_Event.malloc().use { event ->
			while (running || open.isNotEmpty()) {
				Ui.drain()

				val now = System.nanoTime()

				if (wantsFrame) {
					wantsFrame = false
					recomposer.performFrame(now)
				}

				open.values.toList().forEach { it.frame(now) }

				val waiting = wantsFrame || Ui.hasWork || open.values.any { it.wantsFrame }
				val got =
					if (waiting)
						SDL_PollEvent(event)
					else
						SDL_WaitEventTimeout(event, IDLE_MILLIS)

				if (got) {
					handle(event)
					while (SDL_PollEvent(event))
						handle(event)
				}

				if (!running)
					open.values.toList().forEach(::closed)
			}
		}

		composition.dispose()
		recomposer.close()
		cursors.values.forEach(::SDL_DestroyCursor)
	}

	private fun handle(event: SDL_Event) {
		when (event.type()) {
			SDL_EVENT_QUIT -> running = false
			Ui.wakeType -> Unit
			// Every event of a window's says which, in the same place.
			else -> open[event.window().windowID()]?.handle(event)
		}
	}

	companion object {
		/** How long the loop waits for an event before looking again at what it was asked to do meanwhile. */
		private const val IDLE_MILLIS = 250
	}
}

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
 * [decorated], showing [content]. [onCloseRequest] is what closing it by its title bar (or the platform's own way)
 * asks for; whether it closes is the caller's, by composing it no longer.
 */
@Composable
fun Window(
	onCloseRequest: () -> Unit,
	title: String,
	size: DpSize,
	decorated: Boolean = true,
	content: @Composable WindowScope.() -> Unit,
) {
	val app =
		checkNotNull(LocalApplication.current) { "a Window is opened within an application" }
	val locals = currentCompositionLocalContext
	val latest by rememberUpdatedState(content)
	val close by rememberUpdatedState(onCloseRequest)

	val window =
		remember {
			NativeWindow(app, title, size, decorated).also { window ->
				val scope =
					object : WindowScope {
						override val window: NativeWindow = window
					}

				app.opened(window)
				window.onCloseRequest = { close() }
				window.setContent(locals) { scope.latest() }
			}
		}

	SideEffect {
		window.title = title
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
