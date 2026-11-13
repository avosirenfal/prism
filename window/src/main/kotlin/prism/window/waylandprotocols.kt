package prism.window

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/*
 * The interfaces of Wayland's protocols that libwayland-client does not have built in: it has the core protocol's
 * alone (wl_seat, wl_surface, wl_data_device and the rest). A program written in C has wayland-scanner generate the
 * others from each protocol's XML, as tables that libwayland-client reads to send a request and to read an event: an
 * interface's name, its version and its messages; a message's name, its signature (a letter for the type of each
 * argument: `u` a number, `s` a string, `o` an object, `n` a new object) and the interface of each argument that is an
 * object. These are those tables, as the protocols' XML says them, for the two prism speaks beside SDL:
 *
 * - xdg-activation (staging), for a program prism starts to be brought forward ([Protocols.activation]);
 * - cursor-shape (staging), for the pointer to show what a drag would do ([Protocols.cursorShapeManager]).
 *
 * A message's arguments are given to `wl_proxy_marshal_flags` in its signature's order, and its number is its place
 * among its interface's requests: each interface's requests are listed here in the XML's order, which is what numbers
 * them, and the numbers this sends by are beside where they are sent from.
 */

/** A request or an event: its [name], its [signature], and the interface of each argument (null where it is not an object). */
private class Message(
	val name: String,
	val signature: String,
	val types: List<MemorySegment?> = emptyList(),
)

/** The interfaces prism speaks that libwayland-client does not have, each made once, for as long as the process runs. */
internal object Protocols {
	private val arena = Arena.global()

	private val message =
		MemoryLayout.structLayout(
			ADDRESS.withName("name"),
			ADDRESS.withName("signature"),
			ADDRESS.withName("types"),
		)

	private val wlInterface =
		MemoryLayout.structLayout(
			ADDRESS.withName("name"),
			JAVA_INT.withName("version"),
			JAVA_INT.withName("method_count"),
			ADDRESS.withName("methods"),
			JAVA_INT.withName("event_count"),
			MemoryLayout.paddingLayout(4),
			ADDRESS.withName("events"),
		)

	/** [messages] as libwayland-client's `wl_message` array; NULL for none. */
	private fun messagesOf(messages: List<Message>): MemorySegment {
		if (messages.isEmpty())
			return MemorySegment.NULL

		val table = arena.allocate(message, messages.size.toLong())

		messages.forEachIndexed { at, it ->
			val entry = table.asSlice(at * message.byteSize(), message)

			val types =
				if (it.types.isEmpty())
					MemorySegment.NULL
				else
					arena.allocate(ADDRESS, it.types.size.toLong()).also { array ->
						it.types.forEachIndexed { place, type ->
							array.setAtIndex(ADDRESS, place.toLong(), type ?: MemorySegment.NULL)
						}
					}

			entry.set(ADDRESS, 0, arena.allocateFrom(it.name))
			entry.set(ADDRESS, ADDRESS.byteSize(), arena.allocateFrom(it.signature))
			entry.set(ADDRESS, 2 * ADDRESS.byteSize(), types)
		}

		return table
	}

	private fun interfaceOf(
		name: String,
		version: Int,
		requests: List<Message>,
		events: List<Message> = emptyList(),
	): MemorySegment =
		arena.allocate(wlInterface).also {
			it.set(ADDRESS, offsetOf("name"), arena.allocateFrom(name))
			it.set(JAVA_INT, offsetOf("version"), version)
			it.set(JAVA_INT, offsetOf("method_count"), requests.size)
			it.set(ADDRESS, offsetOf("methods"), messagesOf(requests))
			it.set(JAVA_INT, offsetOf("event_count"), events.size)
			it.set(ADDRESS, offsetOf("events"), messagesOf(events))
		}

	private fun offsetOf(field: String): Long =
		wlInterface.byteOffset(MemoryLayout.PathElement.groupElement(field))

	private val seat get() = Wl.iface("wl_seat_interface")
	private val surface get() = Wl.iface("wl_surface_interface")
	private val pointer get() = Wl.iface("wl_pointer_interface")

	/** `xdg_activation_token_v1`: a token being made, told who asks and why, then committed; `done` gives it. */
	val activationToken: MemorySegment =
		interfaceOf(
			"xdg_activation_token_v1",
			1,
			requests =
				listOf(
					Message("set_serial", "uo", listOf(null, seat)),
					Message("set_app_id", "s", listOf(null)),
					Message("set_surface", "o", listOf(surface)),
					Message("commit", ""),
					Message("destroy", ""),
				),
			events = listOf(Message("done", "s", listOf(null))),
		)

	/** `xdg_activation_v1`: what tokens are asked of, and what a window of this process's is activated by. */
	val activation: MemorySegment =
		interfaceOf(
			"xdg_activation_v1",
			1,
			requests =
				listOf(
					Message("destroy", ""),
					Message("get_activation_token", "n", listOf(activationToken)),
					Message("activate", "so", listOf(null, surface)),
				),
		)

	/** `wp_cursor_shape_device_v1`: a pointer's cursor, set by the name of its shape. Version 2 adds `dnd_ask`. */
	val cursorShapeDevice: MemorySegment =
		interfaceOf(
			"wp_cursor_shape_device_v1",
			2,
			requests =
				listOf(
					Message("destroy", ""),
					Message("set_shape", "uu", listOf(null, null)),
				),
		)

	/** `wp_cursor_shape_manager_v1`: what a pointer's [cursorShapeDevice] is asked of. */
	val cursorShapeManager: MemorySegment =
		interfaceOf(
			"wp_cursor_shape_manager_v1",
			2,
			requests =
				listOf(
					Message("destroy", ""),
					Message("get_pointer", "no", listOf(cursorShapeDevice, pointer)),
					// A tablet's tool, which prism does not ask for: any object, as far as this table says.
					Message("get_tablet_tool_v2", "no", listOf(cursorShapeDevice, null)),
				),
		)
}
