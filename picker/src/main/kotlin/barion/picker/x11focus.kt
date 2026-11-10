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
 * So the picker watches its own window from a connection to the X server of its own, for focus arriving at the window
 * itself (never focus passing on to something inside it, which is what AWT's own taking of the keyboard looks like),
 * and when it does and AWT has not noticed, asks AWT for the keyboard, which AWT then takes to its proxy as it would
 * have for a click.
 */

/** For as long as this is composed, [window] takes the keyboard whenever the X server gives it to the window itself. */
@Composable
fun KeepsKeyboard(window: ComposeWindow) {
	DisposableEffect(window) {
		val watch = FocusWatch.of(window)

		onDispose { watch?.close() }
	}
}

/** A thread watching [window] (and the windows above it) on an X connection of its own, until closed. */
private class FocusWatch private constructor(
	private val x: X11,
	private val display: MemorySegment,
	private val window: ComposeWindow,
) : AutoCloseable {
	@Volatile
	private var open = true

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
					it.await(Wait.AtMost(250))
					while (open && x.pending(display) > 0) {
						x.nextEvent(display, event)
						if (arrived(event))
							EventQueue.invokeLater(::takeKeyboard)
					}
				}
			}
		}
		x.closeDisplay(display)
	}

	/** Whether [event] is the X input focus set on a watched window itself, not passing on to a window inside it. */
	private fun arrived(event: MemorySegment): Boolean =
		event.get(JAVA_INT, 0) == X11.FOCUS_IN &&
			event.get(JAVA_INT, X11.MODE) == X11.NOTIFY_NORMAL &&
			event.get(JAVA_INT, X11.DETAIL) in X11.ARRIVING

	/** The keyboard asked of AWT, for the control that last had it, where AWT does not think the window has it. */
	fun takeKeyboard() {
		if (open && window.isShowing && !window.isFocused)
			(window.mostRecentFocusOwner ?: window).requestFocus()
	}

	override fun close() {
		open = false
		thread.join(1000)
	}

	companion object {
		/** A watch of [window], where it is on X11 and libX11 is there to watch it with; null otherwise. */
		fun of(window: ComposeWindow): FocusWatch? {
			val onX11 =
				!GraphicsEnvironment.isHeadless() &&
					Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit"
			val handle = window.windowHandle
			val x = X11.instance

			if (!onX11 || handle == 0L || x == null)
				return null

			val display = x.openDisplay() ?: return null

			// The window and every window above it but the root: whichever of them the focus is set on (a window
			// manager that reparents sets it on its frame, or on the window inside it).
			val watched = x.ancestry(display, handle)

			for (each in watched)
				x.selectInput(display, each, X11.FOCUS_CHANGE_MASK)
			x.flush(display)

			// Focus given before the watch began (as the window was first shown) is not an event it will see.
			val already = x.inputFocus(display) in watched

			return FocusWatch(x, display, window).also {
				if (already)
					EventQueue.invokeLater(it::takeKeyboard)
			}
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
		const val FOCUS_IN = 9

		/** `FocusChangeMask`. */
		const val FOCUS_CHANGE_MASK = 1L shl 21

		/** Where `XFocusChangeEvent`'s mode and detail are: after its type, serial, send_event, display and window. */
		const val MODE = 40L
		const val DETAIL = 44L
		const val NOTIFY_NORMAL = 0

		/**
		 * The details of focus set on a window itself: from an ancestor of it (`NotifyAncestor`), or from elsewhere
		 * (`NotifyNonlinear`). Focus passing through it to a window inside is `NotifyVirtual` or
		 * `NotifyNonlinearVirtual`, and focus that was inside it is `NotifyInferior`: none of those.
		 */
		val ARRIVING = setOf(0, 3)
	}
}
