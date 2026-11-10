package barion.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.awt.ComposeWindow
import barion.linux.Epoll
import barion.linux.Fd
import barion.linux.NativeLibrary
import barion.linux.Sys
import barion.linux.Wait
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/*
 * The keyboard given back to a window that a compositor focuses as AWT does not expect.
 *
 * AWT on X11 keeps the keyboard in a small window of its own inside each of its windows (its focus proxy), and takes
 * it there itself when the window manager offers it (ICCCM's WM_TAKE_FOCUS). A window manager or Wayland compositor
 * that instead sets the X input focus on the window itself (niri through xwayland-satellite does; so does
 * `xdotool windowfocus`) gives AWT no event it reads as the window gaining focus: the shell's own handler of that
 * event has the line that would pass it to the proxy commented out. So AWT goes on thinking the window is in the
 * background, and drops every key, until a click makes it take the keyboard itself.
 *
 * The same happens as a window is shown: AWT gives its proxy the keyboard, and the compositor then sets it on the
 * window, taking it from the proxy, in whatever order its events say.
 *
 * So the picker watches its own window from a connection to the X server of its own, and whenever the X input focus is
 * anywhere in the window while AWT does not think the window has the keyboard, asks AWT for it, which AWT then takes
 * to its proxy as it would have for a click. `BARION_FOCUS_LOG=1` has it say what it sees.
 */

/** For as long as this is composed, [window] takes the keyboard whenever the X server gives it to the window. */
@Composable
fun KeepsKeyboard(window: ComposeWindow) {
	DisposableEffect(window) {
		val watch = FocusWatch.of(window)

		onDispose { watch?.close() }
	}
}

/**
 * A thread watching [window] on an X connection of its own, until closed. What it checks is a state, not an event:
 * whether the X input focus is anywhere in the window (the window itself, a window above it such as a frame, or one
 * inside it) while AWT does not think the window has the keyboard. It checks whenever focus changes on the window and
 * four times a second besides, so focus given before the watch began, or in an order no event says, is found too; and
 * the keyboard asked of AWT makes AWT think the window has it, so it asks once.
 */
private class FocusWatch private constructor(
	private val x: X11,
	private val display: MemorySegment,
	private val window: ComposeWindow,
) : AutoCloseable {
	@Volatile
	private var open = true

	/** The window and the windows above it but the root, once the window has a native handle to find them by. */
	private var watched: List<Long> = emptyList()

	/** What the last check found, so the log says only what changed. */
	private var last: String? = null

	private val thread =
		Thread({ watch() }, "barion-x11-focus").apply {
			isDaemon = true
			start()
		}

	private fun watch() {
		Arena.ofConfined().use { arena ->
			val epoll = (Epoll.create(arena) as? Sys.Ok)?.value ?: return
			val event = arena.allocate(X11.EVENT_SIZE, 8)

			epoll.use {
				it.watchInput(Fd(x.connectionNumber(display)), 0)
				while (open) {
					if (watched.isEmpty())
						watched = watching()
					check()
					it.await(Wait.AtMost(250))
					while (open && x.pending(display) > 0)
						x.nextEvent(display, event)
				}
			}
		}
		x.closeDisplay(display)
	}

	/** The windows to watch, selected for focus changes; none while the window has no native handle yet. */
	private fun watching(): List<Long> {
		val handle = window.windowHandle

		if (handle == 0L)
			return emptyList()

		val windows = x.ancestry(display, handle)

		for (each in windows)
			x.selectInput(display, each, X11.FOCUS_CHANGE_MASK)
		x.flush(display)
		log("watching $windows")

		return windows
	}

	/** Whether the X input focus is in the window while AWT does not know it; if so, the keyboard asked of AWT. */
	private fun check() {
		if (watched.isEmpty() || window.isFocused) {
			said("AWT has the keyboard")

			return
		}

		val focus = x.inputFocus(display)
		val inside = focus > 1 && x.ancestry(display, focus).any { it in watched }

		said(
			"X focus on $focus, ${if (inside) "in" else "not in"} the window; AWT does not have the keyboard",
		)
		if (inside)
			EventQueue.invokeLater(::takeKeyboard)
	}

	/** The keyboard asked of AWT, for the control that last had it, where AWT does not think the window has it. */
	private fun takeKeyboard() {
		if (open && window.isShowing && !window.isFocused) {
			log("asking AWT for the keyboard")
			(window.mostRecentFocusOwner ?: window).requestFocus()
		}
	}

	private fun said(state: String) {
		if (state != last)
			log(state)
		last = state
	}

	override fun close() {
		open = false
		thread.join(1000)
	}

	companion object {
		/** `BARION_FOCUS_LOG=1` has the watch say on standard error what it sees and does. */
		private val logging = System.getenv("BARION_FOCUS_LOG") != null

		private fun log(line: String) {
			if (logging)
				System.err.println("barion focus: $line")
		}

		/** A watch of [window], where it is on X11 and libX11 is there to watch it with; null otherwise. */
		fun of(window: ComposeWindow): FocusWatch? {
			val onX11 =
				!GraphicsEnvironment.isHeadless() &&
					Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit"
			val x = X11.instance
			val display = x?.takeIf { onX11 }?.openDisplay()

			log(
				when {
					!onX11 -> "not on X11 (${Toolkit.getDefaultToolkit().javaClass.name}): nothing to watch"
					x == null -> "no libX11: nothing to watch"
					display == null -> "the X display cannot be opened: nothing to watch"
					else -> "watching for the window's handle"
				},
			)

			return display?.let { FocusWatch(x, it, window) }
		}
	}
}

/** What of libX11 the watch needs, through the FFM API. */
private class X11(
	library: NativeLibrary,
) {
	private val open = library.plain("XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS))
	private val close =
		library.plain(
			"XCloseDisplay",
			FunctionDescriptor.of(JAVA_INT, ADDRESS),
		)
	private val connection =
		library.plain(
			"XConnectionNumber",
			FunctionDescriptor.of(JAVA_INT, ADDRESS),
		)
	private val select =
		library.plain(
			"XSelectInput",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG),
		)
	private val flushing = library.plain("XFlush", FunctionDescriptor.of(JAVA_INT, ADDRESS))
	private val pending = library.plain("XPending", FunctionDescriptor.of(JAVA_INT, ADDRESS))
	private val next =
		library.plain(
			"XNextEvent",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
		)
	private val free = library.plain("XFree", FunctionDescriptor.of(JAVA_INT, ADDRESS))
	private val focus =
		library.plain(
			"XGetInputFocus",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
		)
	private val queryTree =
		library.plain(
			"XQueryTree",
			FunctionDescriptor.of(
				JAVA_INT,
				ADDRESS,
				JAVA_LONG,
				ADDRESS,
				ADDRESS,
				ADDRESS,
				ADDRESS,
			),
		)

	/** A connection of its own to the display AWT is on (`$DISPLAY`); null where it cannot be opened. */
	fun openDisplay(): MemorySegment? =
		(open.invokeExact(MemorySegment.NULL) as MemorySegment).takeUnless {
			it ==
				MemorySegment.NULL
		}

	fun closeDisplay(display: MemorySegment) {
		close.invokeExact(display) as Int
	}

	fun connectionNumber(display: MemorySegment): Int =
		connection.invokeExact(display) as Int

	fun selectInput(display: MemorySegment, window: Long, mask: Long) {
		select.invokeExact(display, window, mask) as Int
	}

	fun flush(display: MemorySegment) {
		flushing.invokeExact(display) as Int
	}

	fun pending(display: MemorySegment): Int = pending.invokeExact(display) as Int

	fun nextEvent(display: MemorySegment, event: MemorySegment) {
		next.invokeExact(display, event) as Int
	}

	/** The window the X input focus is on now. */
	fun inputFocus(display: MemorySegment): Long =
		Arena.ofConfined().use { arena ->
			val window = arena.allocate(JAVA_LONG)
			val revert = arena.allocate(JAVA_INT)

			focus.invokeExact(display, window, revert) as Int
			window.get(JAVA_LONG, 0)
		}

	/** [window], and each window above it up to the root, the root left out. */
	fun ancestry(display: MemorySegment, window: Long): List<Long> =
		generateSequence(window) { parentOf(display, it) }.toList()

	/** The parent of [window], or null where it is the root's child, or the tree cannot be read. */
	private fun parentOf(display: MemorySegment, window: Long): Long? =
		Arena.ofConfined().use { arena ->
			val root = arena.allocate(JAVA_LONG)
			val parent = arena.allocate(JAVA_LONG)
			val children = arena.allocate(ADDRESS)
			val count = arena.allocate(JAVA_INT)
			val read = queryTree.invokeExact(display, window, root, parent, children, count) as Int

			if (read == 0)
				return null

			children
				.get(ADDRESS, 0)
				.takeUnless {
					it == MemorySegment.NULL
				}?.let { free.invokeExact(it) as Int }
			parent.get(JAVA_LONG, 0).takeUnless { it == 0L || it == root.get(JAVA_LONG, 0) }
		}

	companion object {
		/** libX11, where it is installed (AWT on X11 needs it, so wherever this is needed it is). */
		val instance: X11? by lazy { NativeLibrary.open("libX11.so.6")?.let(::X11) }

		/** `sizeof(XEvent)` on 64 bits: a union padded to 24 longs. */
		const val EVENT_SIZE = 192L

		/** `FocusChangeMask`. */
		const val FOCUS_CHANGE_MASK = 1L shl 21
	}
}
