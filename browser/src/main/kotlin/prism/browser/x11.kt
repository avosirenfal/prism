package prism.browser

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import prism.linux.NativeLibrary

/** What of libX11 the picker needs (its focus watch, its title bar), through the FFM API. */
internal class X11(
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
	private val atom =
		library.plain(
			"XInternAtom",
			FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT),
		)
	private val change =
		library.plain(
			"XChangeProperty",
			FunctionDescriptor.of(
				JAVA_INT,
				ADDRESS,
				JAVA_LONG,
				JAVA_LONG,
				JAVA_LONG,
				JAVA_INT,
				JAVA_INT,
				ADDRESS,
				JAVA_INT,
			),
		)
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

	/** [window]'s property [name] set to the UTF-8 text [value], replacing what it was. */
	fun setText(display: MemorySegment, window: Long, name: String, value: String) {
		Arena.ofConfined().use { arena ->
			val property = atom.invokeExact(display, arena.allocateFrom(name), 0) as Long
			val utf8 = atom.invokeExact(display, arena.allocateFrom("UTF8_STRING"), 0) as Long
			val bytes = value.encodeToByteArray()
			val data =
				arena.allocate(bytes.size.toLong().coerceAtLeast(1)).also {
					it.copyFrom(MemorySegment.ofArray(bytes))
				}

			change.invokeExact(
				display,
				window,
				property,
				utf8,
				8,
				PROP_MODE_REPLACE,
				data,
				bytes.size,
			) as Int
		}
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

		private const val PROP_MODE_REPLACE = 0

		/** `FocusChangeMask`. */
		const val FOCUS_CHANGE_MASK = 1L shl 21
	}
}
