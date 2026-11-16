package prism.browser

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import prism.ffi.KeepsErrno
import prism.ffi.Library

internal fun interface XOpenDisplay {
	operator fun invoke(name: MemorySegment): MemorySegment
}

internal fun interface XCloseDisplay {
	operator fun invoke(display: MemorySegment): Int
}

internal fun interface XFlush {
	operator fun invoke(display: MemorySegment): Int
}

internal fun interface XInternAtom {
	operator fun invoke(display: MemorySegment, name: MemorySegment, onlyIfExists: Int): Long
}

internal fun interface XChangeProperty {
	operator fun invoke(
		display: MemorySegment,
		window: Long,
		property: Long,
		type: Long,
		format: Int,
		mode: Int,
		data: MemorySegment,
		count: Int,
	): Int
}

/** What of libX11 the picker needs (its title bar, and being an application's dialog), through the FFM API. */
internal class X11(
	library: Library,
) {
	private val openCall: XOpenDisplay = library.bind("XOpenDisplay")
	private val closeCall: XCloseDisplay = library.bind("XCloseDisplay")
	private val flushingCall: XFlush = library.bind("XFlush")
	private val atomCall: XInternAtom = library.bind("XInternAtom")
	private val changeCall: XChangeProperty = library.bind("XChangeProperty")

	/** A connection of its own to the X display (`$DISPLAY`); null where it cannot be opened. */
	fun openDisplay(): MemorySegment? =
		(openCall(MemorySegment.NULL)).takeUnless {
			it ==
				MemorySegment.NULL
		}

	fun closeDisplay(display: MemorySegment) {
		closeCall(display)
	}

	fun flush(display: MemorySegment) {
		flushingCall(display)
	}

	/** [window]'s property [name] set to the UTF-8 text [value], replacing what it was. */
	fun setText(display: MemorySegment, window: Long, name: String, value: String) {
		Arena.ofConfined().use { arena ->
			val property = atomCall(display, arena.allocateFrom(name), 0)
			val utf8 = atomCall(display, arena.allocateFrom("UTF8_STRING"), 0)
			val bytes = value.encodeToByteArray()
			val data =
				arena.allocate(bytes.size.toLong().coerceAtLeast(1)).also {
					it.copyFrom(MemorySegment.ofArray(bytes))
				}

			changeCall(display, window, property, utf8, 8, PROP_MODE_REPLACE, data, bytes.size)
		}
	}

	/** [window]'s property [name] set to the window [value], replacing what it was: `WM_TRANSIENT_FOR`, say. */
	fun setWindow(display: MemorySegment, window: Long, name: String, value: Long) {
		Arena.ofConfined().use { arena ->
			val property = atomCall(display, arena.allocateFrom(name), 0)
			val type = atomCall(display, arena.allocateFrom("WINDOW"), 0)
			// Xlib takes 32-bit properties as an array of C longs, whatever their size.
			val data = arena.allocate(JAVA_LONG).also { it.set(JAVA_LONG, 0, value) }

			changeCall(display, window, property, type, 32, PROP_MODE_REPLACE, data, 1)
		}
	}

	companion object {
		/** libX11, where it is installed (SDL on X11 needs it, so wherever this is needed it is). */
		val instance: X11? by lazy { Library.open("libX11.so.6")?.let(::X11) }

		private const val PROP_MODE_REPLACE = 0
	}
}
