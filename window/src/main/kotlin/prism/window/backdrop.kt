package prism.window

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/*
 * What is behind what a window draws. Most windows are opaque: where they paint nothing, they are black, and nothing
 * of what is under them is seen. A window whose shape is not a rectangle (a launcher with round corners) is clear
 * instead: its pixels have an alpha channel the platform blends by, so where it paints nothing, what is behind it
 * shows. Every platform, and each way prism draws, has its own way of being so, or none:
 *
 * | platform | drawn on the GPU | drawn on the CPU |
 * |---|---|---|
 * | Wayland (a window, or a layer surface) | an EGL configuration with alpha, which SDL chooses for a window made transparent, and no opaque region | buffers of `ARGB8888` in place of `XRGB8888` |
 * | X11, with a compositing manager | a visual of 32 bits, which SDL chooses for a window made transparent | none: SDL's window surface is of the screen's own visual, which has no alpha |
 * | X11 without one | none: alpha is nobody's to blend, and what is clear would be black | none |
 * | macOS, Windows | not yet | not yet |
 *
 * So a window asks ([Backdrop.Clear]), the platform gives what it has, and the window says how it turned out
 * ([NativeWindow.backdrop]), for what is drawn in it to be drawn square where its corners would be black. Whether it
 * may be asked for at all is decided before the window is made ([mayBeClear]); whether it was got is read from the
 * pixels the window ended up with ([backdropOf]), so a window is never said to be clear on a path that is not.
 */

/** What is behind what a window draws. */
enum class Backdrop {
	/** The window's own: where nothing is painted, nothing shows. What a window is unless it asks otherwise. */
	Opaque,

	/** Whatever is behind the window: where nothing is painted, that shows, and what is half painted is blended with it. */
	Clear,
}

/**
 * Whether a window on [video] that asks to be [asked] can be made clear at all, before it is made: on Wayland, always
 * (a compositor blends every surface); on X11, where something is compositing ([composited]); elsewhere, not yet.
 */
internal fun mayBeClear(
	asked: Backdrop,
	video: Video,
	composited: () -> Boolean,
): Boolean =
	asked == Backdrop.Clear &&
		when (video) {
			Video.Wayland -> true
			Video.X11 -> composited()
			Video.Windows, Video.Cocoa, Video.Other -> false
		}

/**
 * What is behind a window that [may] be clear, now that it is made: clear where its pixels turned out to have an
 * [alpha] channel (an EGL configuration or a visual with one was to be had), and opaque where they did not.
 */
internal fun backdropOf(may: Boolean, alpha: Boolean): Backdrop =
	if (may && alpha)
		Backdrop.Clear
	else
		Backdrop.Opaque

/**
 * Whether something composites the X11 screen prism is on: whether a compositing manager owns the selection one
 * takes (`_NET_WM_CM_S` and the screen's number), as the Extended Window Manager Hints say. Asked on a connection of
 * its own, opened and closed for the asking; false where libX11 cannot be had or the display cannot be opened.
 */
internal object X11Compositing {
	private val linker = Linker.nativeLinker()

	fun present(): Boolean =
		try {
			Arena.ofConfined().use { arena ->
				val x = SymbolLookup.libraryLookup("libX11.so.6", arena)

				fun function(name: String, descriptor: FunctionDescriptor) =
					linker.downcallHandle(x.find(name).orElseThrow(), descriptor)

				val display =
					function("XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS))
						.invokeExact(MemorySegment.NULL) as MemorySegment

				if (display.address() == 0L)
					return false

				try {
					val screen =
						function("XDefaultScreen", FunctionDescriptor.of(JAVA_INT, ADDRESS))
							.invokeExact(display) as Int

					val atom =
						function(
							"XInternAtom",
							FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT),
						).invokeExact(display, arena.allocateFrom("_NET_WM_CM_S$screen"), 0) as Long

					val owner =
						function(
							"XGetSelectionOwner",
							FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG),
						).invokeExact(display, atom) as Long

					owner != 0L
				} finally {
					function("XCloseDisplay", FunctionDescriptor.of(JAVA_INT, ADDRESS))
						.invokeExact(display) as Int
				}
			}
		} catch (unavailable: IllegalArgumentException) {
			false
		} catch (unavailable: NoSuchElementException) {
			false
		}
}
