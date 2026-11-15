package prism.window

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.invoke.MethodHandles

/*
 * Other applications' windows, as wlr-foreign-toplevel-management tells of them: what [OpenWindows] is on Wayland.
 *
 * The compositor's manager, once bound, announces every window open (`toplevel`, with a new object for each: its
 * handle), and each one opened after. A handle then says what its window is: its title, its application's id, which
 * outputs it is on, whether it is active; and `done` once it has said all that goes together. So a window's title and
 * id are kept as they are said, and only listed (or listed anew) at `done`. `closed` says the window is gone, and its
 * handle is then given up.
 *
 * A handle is asked to `activate` its window on a seat, which is prism's own (waylandseat.kt): the compositor brings
 * the window forward and gives it the keyboard, as its own switcher would. Unlike xdg-activation there is no token:
 * the protocol is one a compositor gives only to programs it trusts to manage windows, which is what a launcher is.
 *
 * Its events arrive on prism's own queue, on the seat's thread; the first of them (the windows open as the manager is
 * bound) are read before that thread begins, so that what is listed is whole from the first time it is asked for.
 */

/** The windows the compositor tells of, kept as it tells of them: made with the seat, where it has the protocol. */
internal class Toplevels(
	private val display: MemorySegment,
	private val manager: Bound,
	private val seat: MemorySegment,
	arena: Arena,
) : OpenWindows.Listed() {
	/** A window as the compositor is telling of it: its handle, and what it has said of itself so far. */
	private data class Telling(
		val handle: MemorySegment,
		val appId: String = "",
		val title: String = "",
	)

	/** Every handle there is, by its address: changed on the seat's thread, and read by [focus] from another. */
	private val handles = HashMap<Long, Telling>()

	/** The windows that have said `done`, by their handles' addresses, in the order they were first told of in. */
	@Volatile
	private var listed: Map<Long, OpenWindow> = emptyMap()

	private val lookup = MethodHandles.lookup()

	private val handleListener =
		listenerOf(
			arena,
			lookup,
			this,
			"title" to SAID,
			"appId" to SAID,
			"outputEnter" to SAID,
			"outputLeave" to SAID,
			"state" to SAID,
			"done" to HAPPENED,
			"closed" to HAPPENED,
			"parent" to SAID,
		)

	init {
		Wl.addListener.invokeExact(
			manager.proxy,
			listenerOf(arena, lookup, this, "toplevel" to SAID, "finished" to HAPPENED),
			MemorySegment.NULL,
		) as Int
	}

	override fun now(): List<OpenWindow> = listed.values.toList()

	override fun focus(window: OpenWindow) {
		synchronized(this) {
			// The window that had this address when it was listed, and not another opened at it since. Its title may
			// well have changed meanwhile (a terminal's does with every command), and it is the same window still.
			val telling =
				handles[window.id]?.takeIf { listed[window.id]?.appId == window.appId } ?: return

			Wl.marshalNone.invokeExact(
				telling.handle,
				HANDLE_ACTIVATE,
				MemorySegment.NULL,
				manager.version,
				0,
				seat,
			) as MemorySegment
		}
		Wl.flush.invokeExact(display) as Int
		Log.say { "toplevels: asked for “${window.appId}” to be brought forward" }
	}

	/** What [handle] says of its window, kept until it says `done`. */
	private fun said(handle: MemorySegment, change: (Telling) -> Telling) {
		synchronized(this) {
			handles.computeIfPresent(handle.address()) { _, telling -> change(telling) }
		}
	}

	private fun textOf(text: MemorySegment): String =
		text.reinterpret(Long.MAX_VALUE).getString(0)

	// ---- what the compositor says, on the seat's thread ----------------------------------------------------------

	/** `zwlr_foreign_toplevel_manager_v1.toplevel`: a window there is, by a new handle, heard from here on. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun toplevel(data: MemorySegment, manager: MemorySegment, handle: MemorySegment) {
		synchronized(this) { handles[handle.address()] = Telling(handle) }
		Wl.addListener.invokeExact(handle, handleListener, MemorySegment.NULL) as Int
	}

	/** `finished`: the compositor tells of no more windows; those it told of are closed one by one, as ever. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun finished(data: MemorySegment, manager: MemorySegment) = Unit

	@Suppress("unused", "UNUSED_PARAMETER")
	fun title(data: MemorySegment, handle: MemorySegment, text: MemorySegment) =
		said(handle) { it.copy(title = textOf(text)) }

	@Suppress("unused", "UNUSED_PARAMETER")
	fun appId(data: MemorySegment, handle: MemorySegment, text: MemorySegment) =
		said(handle) { it.copy(appId = textOf(text)) }

	@Suppress("unused", "UNUSED_PARAMETER")
	fun outputEnter(data: MemorySegment, handle: MemorySegment, output: MemorySegment) = Unit

	@Suppress("unused", "UNUSED_PARAMETER")
	fun outputLeave(data: MemorySegment, handle: MemorySegment, output: MemorySegment) = Unit

	/** Whether it is maximized, minimized, active, fullscreen: nothing that is asked of it here. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun state(data: MemorySegment, handle: MemorySegment, states: MemorySegment) = Unit

	@Suppress("unused", "UNUSED_PARAMETER")
	fun parent(data: MemorySegment, handle: MemorySegment, parent: MemorySegment) = Unit

	/** `done`: what the handle said since the last is its window as it now is, and is listed so. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun done(data: MemorySegment, handle: MemorySegment) {
		val id = handle.address()

		synchronized(this) {
			handles[id]?.let { listed = listed + (id to OpenWindow(id, it.appId, it.title)) }
		}
	}

	/** `closed`: the window is gone, and its handle is given up, as the protocol asks. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun closed(data: MemorySegment, handle: MemorySegment) {
		synchronized(this) {
			if (handles.remove(handle.address()) == null)
				return

			listed = listed - handle.address()
			Wl.marshalNone.invokeExact(
				handle,
				HANDLE_DESTROY,
				MemorySegment.NULL,
				manager.version,
				Wl.MARSHAL_DESTROY,
				MemorySegment.NULL,
			) as MemorySegment
		}
	}

	private companion object {
		// zwlr_foreign_toplevel_handle_v1's requests, as waylandprotocols.kt lists them.
		const val HANDLE_ACTIVATE = 4
		const val HANDLE_DESTROY = 7

		/** An event that says one thing (a string, an object, an array), and one that only happens. */
		val SAID: FunctionDescriptor = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
		val HAPPENED: FunctionDescriptor = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
	}
}
