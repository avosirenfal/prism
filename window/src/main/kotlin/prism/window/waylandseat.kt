package prism.window

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/*
 * prism's own connection to the compositor, beside SDL's: on SDL's display, but on an event queue of its own,
 * dispatched by a thread of its own, so that nothing of SDL's is read or answered here. It is for what prism needs of
 * Wayland that SDL does not do, or keeps to itself:
 *
 * - Drags out of prism's windows (waylanddrags.kt). A drag must be started with the serial of the press of the
 *   pointer's button that began it, which SDL keeps to itself.
 * - Tokens for bringing forward the window of a program prism starts (xdg-activation). A token is made from the
 *   serial of the user's last press of a button or a key, which tells the compositor that the user asked for it.
 * - The pointer's cursor during a drag (cursor-shape), which says what letting go there would do.
 * - Surfaces over every window (layer-shell, waylandoverlay.kt), which SDL has no windows of.
 *
 * So the seat is bound here too, and its pointer and keyboard heard: the compositor tells every pointer and keyboard
 * a client has of what is done in its windows. Each is asked for once the seat says it has one, as a seat must not be
 * asked for a pointer it has not got.
 */

/** A cursor the pointer shows, by the number cursor-shape gives it; [since] its version that has it, [otherwise] before. */
internal enum class CursorShape(
	val number: Int,
	val since: Int = 1,
	val otherwise: CursorShape? = null,
) {
	Alias(11),
	Copy(12),
	Move(13),
	NoDrop(14),
	DndAsk(35, since = 2, otherwise = Copy),
}

/**
 * A press of a key or a button: its [serial], and the [surface] of this process's it was in, by its address (0 where
 * none is known). Only an address, compared with the windows open: a surface is its window's, and gone with it,
 * perhaps before the compositor says the pointer or the keyboard left it.
 */
internal data class Press(
	val serial: Int,
	val surface: Long,
)

/** A global bound: its proxy, and the version it is bound at. */
internal class Bound(
	val proxy: MemorySegment,
	val version: Int,
)

/** prism's own connection to the compositor's seat, beside SDL's: see the top of this file. Made by [WaylandSeat.of]. */
internal class WaylandSeat private constructor(
	val display: MemorySegment,
	private val queue: MemorySegment,
	val arena: Arena,
	val seat: MemorySegment,
	/** `wl_data_device_manager`, where the compositor has one: what drags are made by. */
	val dataDevices: Bound?,
	/** `wl_compositor` and `wl_shm`: what a drag's picture is a surface of, and its buffer in. */
	val compositor: Bound?,
	val shm: Bound?,
	private val activation: Bound?,
	private val cursorShapes: Bound?,
	layerShell: Bound?,
) : AutoCloseable {
	/** The compositor's layers, where it has layer-shell: what an overlay's window is a surface of. */
	val layers: Layers? = layerShell?.let { Layers(display, it, arena) }

	@Volatile
	private var running = true

	/** The serial of the pointer's last press of a button: what a drag is begun from. */
	@Volatile
	var pressed: Int? = null
		private set

	/** The user's last press of a button or a key, and where: what a token for activation is made from. */
	@Volatile
	var lastPress: Press? = null
		private set

	/** The serial of the pointer's last entering one of this process's windows: what its cursor is set by. */
	@Volatile
	private var entered: Int? = null

	/** Where the pointer and the keyboard are, as the compositor says: the address of the surface each is in; 0 for none. */
	@Volatile
	private var pointerIn: Long = 0

	@Volatile
	private var keyboardIn: Long = 0

	// Made and let go of on this one's thread, as the seat says it has them; read from others.
	@Volatile
	private var pointer: MemorySegment? = null

	@Volatile
	private var cursor: MemorySegment? = null

	@Volatile
	private var keyboard: MemorySegment? = null

	/** The tokens asked for and not yet given, by their proxies' addresses. */
	private val asked = ConcurrentHashMap<Long, CompletableFuture<String>>()

	private val lookup = MethodHandles.lookup()

	private val pointerListener =
		listenerOf(
			arena,
			lookup,
			this,
			"pointerEnter" to
				FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
			"pointerLeave" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS),
			"pointerMotion" to
				FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
			"pointerButton" to
				FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
			"pointerAxis" to
				FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
		)

	private val keyboardListener =
		listenerOf(
			arena,
			lookup,
			this,
			"keymap" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
			"keyboardEnter" to
				FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
			"keyboardLeave" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS),
			"key" to
				FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
			"modifiers" to
				FunctionDescriptor.ofVoid(
					ADDRESS,
					ADDRESS,
					JAVA_INT,
					JAVA_INT,
					JAVA_INT,
					JAVA_INT,
					JAVA_INT,
				),
		)

	private val seatListener =
		listenerOf(
			arena,
			lookup,
			this,
			"capabilities" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT),
		)

	private val tokenListener =
		listenerOf(
			arena,
			lookup,
			this,
			"done" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
		)

	private val thread =
		Thread({ dispatching() }, "prism wayland seat").apply {
			isDaemon = true
		}

	private fun dispatching() {
		while (running)
			if ((Wl.dispatchQueue.invokeExact(display, queue) as Int) < 0)
				return
	}

	/**
	 * A token for bringing forward the window of a program about to be started, the application [appId] where that is
	 * known: made from the user's press of serial [serial], in [surface], as the compositor gives it. Null where the
	 * compositor has no xdg-activation.
	 *
	 * A compositor gives a token without a press or a window too, which may only mark the program's window as wanting
	 * attention, rather than bring it forward: niri checks the press, sway that there is a window, and wlroots that it
	 * is the one with the keyboard.
	 */
	fun token(
		serial: Int?,
		surface: MemorySegment?,
		appId: String?,
	): CompletableFuture<String>? {
		val activation = activation ?: return null
		val answer = CompletableFuture<String>()

		val token =
			Wl.marshalNone.invokeExact(
				activation.proxy,
				ACTIVATION_GET_TOKEN,
				Protocols.activationToken,
				activation.version,
				0,
				MemorySegment.NULL,
			) as MemorySegment

		// Heard before it is committed, which is what has the compositor answer.
		asked[token.address()] = answer
		Wl.addListener.invokeExact(token, tokenListener, MemorySegment.NULL) as Int
		serial?.let {
			Wl.marshalSerial.invokeExact(
				token,
				TOKEN_SET_SERIAL,
				MemorySegment.NULL,
				1,
				0,
				it,
				seat,
			)
				as MemorySegment
		}
		appId?.let { id ->
			Arena.ofConfined().use { text ->
				Wl.marshalNone.invokeExact(
					token,
					TOKEN_SET_APP_ID,
					MemorySegment.NULL,
					1,
					0,
					text.allocateFrom(id),
				) as MemorySegment
			}
		}
		surface?.let {
			Wl.marshalNone.invokeExact(token, TOKEN_SET_SURFACE, MemorySegment.NULL, 1, 0, it)
				as MemorySegment
		}
		Wl.marshalNone.invokeExact(
			token,
			TOKEN_COMMIT,
			MemorySegment.NULL,
			1,
			0,
			MemorySegment.NULL,
		)
			as MemorySegment
		Wl.flush.invokeExact(display) as Int

		return answer
	}

	/**
	 * The pointer's cursor shown as [shape] (or what stands for it, where the compositor's cursor-shape is older):
	 * where the compositor has cursor-shape, and lets this process set the cursor now. Some let the process a drag
	 * began in set it while the drag goes (niri, and others built on Smithay); others draw their own for a drag, and
	 * leave this unheard.
	 */
	fun show(shape: CursorShape) {
		val device = cursor ?: return
		val version = cursorShapes?.version ?: return
		val shown =
			if (shape.since <= version)
				shape
			else
				shape.otherwise ?: return

		Wl.marshalInts.invokeExact(
			device,
			SHAPE_SET_SHAPE,
			MemorySegment.NULL,
			version,
			0,
			entered ?: 0,
			shown.number,
		) as MemorySegment
		Wl.flush.invokeExact(display) as Int
	}

	// ---- what the compositor says, on this one's thread --------------------------------------------------------------

	@Suppress("unused", "UNUSED_PARAMETER")
	fun capabilities(data: MemorySegment, seat: MemorySegment, capabilities: Int) {
		val hasPointer = capabilities and SEAT_POINTER != 0
		val hasKeyboard = capabilities and SEAT_KEYBOARD != 0

		if (hasPointer && pointer == null) {
			val made = got(SEAT_GET_POINTER, "wl_pointer_interface", pointerListener)

			pointer = made
			cursor =
				cursorShapes?.let { shapes ->
					Wl.marshalObject.invokeExact(
						shapes.proxy,
						SHAPES_GET_POINTER,
						Protocols.cursorShapeDevice,
						shapes.version,
						0,
						MemorySegment.NULL,
						made,
					) as MemorySegment
				}
		} else if (!hasPointer) {
			pointerIn = 0
			cursor?.let {
				Wl.marshalNone.invokeExact(
					it,
					SHAPE_DESTROY,
					MemorySegment.NULL,
					cursorShapes?.version ?: 1,
					Wl.MARSHAL_DESTROY,
					MemorySegment.NULL,
				) as MemorySegment
			}
			pointer?.let { Wl.destroy.invoke(it) }
			cursor = null
			pointer = null
			pressed = null
		}

		if (hasKeyboard && keyboard == null) {
			keyboard = got(SEAT_GET_KEYBOARD, "wl_keyboard_interface", keyboardListener)
		} else if (!hasKeyboard) {
			keyboard?.let { Wl.destroy.invoke(it) }
			keyboard = null
			keyboardIn = 0
		}
	}

	/** The seat's pointer or keyboard asked for, by the request [opcode], and heard by [listener]. */
	private fun got(opcode: Int, iface: String, listener: MemorySegment): MemorySegment =
		(
			Wl.marshalNone.invokeExact(
				seat,
				opcode,
				Wl.iface(iface),
				1,
				0,
				MemorySegment.NULL,
			) as MemorySegment
		).also { Wl.addListener.invokeExact(it, listener, MemorySegment.NULL) as Int }

	@Suppress("unused", "UNUSED_PARAMETER")
	fun pointerEnter(
		data: MemorySegment,
		pointer: MemorySegment,
		serial: Int,
		surface: MemorySegment,
		x: Int,
		y: Int,
	) {
		entered = serial
		pointerIn = surface.address()
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun pointerLeave(
		data: MemorySegment,
		pointer: MemorySegment,
		serial: Int,
		surface: MemorySegment,
	) {
		if (pointerIn == surface.address())
			pointerIn = 0
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun pointerMotion(
		data: MemorySegment,
		pointer: MemorySegment,
		time: Int,
		x: Int,
		y: Int,
	) = Unit

	@Suppress("unused", "UNUSED_PARAMETER")
	fun pointerButton(
		data: MemorySegment,
		pointer: MemorySegment,
		serial: Int,
		time: Int,
		button: Int,
		state: Int,
	) {
		if (state == PRESSED) {
			pressed = serial
			lastPress = Press(serial, pointerIn)
		}
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun pointerAxis(
		data: MemorySegment,
		pointer: MemorySegment,
		time: Int,
		axis: Int,
		value: Int,
	) = Unit

	/** The keyboard's keymap, which is SDL's to read: its file, given to this one too, closed. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun keymap(
		data: MemorySegment,
		keyboard: MemorySegment,
		format: Int,
		fd: Int,
		size: Int,
	) {
		Wl.close.invokeExact(fd) as Int
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun keyboardEnter(
		data: MemorySegment,
		keyboard: MemorySegment,
		serial: Int,
		surface: MemorySegment,
		keys: MemorySegment,
	) {
		keyboardIn = surface.address()
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun keyboardLeave(
		data: MemorySegment,
		keyboard: MemorySegment,
		serial: Int,
		surface: MemorySegment,
	) {
		if (keyboardIn == surface.address())
			keyboardIn = 0
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun key(
		data: MemorySegment,
		keyboard: MemorySegment,
		serial: Int,
		time: Int,
		key: Int,
		state: Int,
	) {
		if (state == PRESSED)
			lastPress = Press(serial, keyboardIn)
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun modifiers(
		data: MemorySegment,
		keyboard: MemorySegment,
		serial: Int,
		depressed: Int,
		latched: Int,
		locked: Int,
		group: Int,
	) = Unit

	/** A token made: given to who asked for it, and its proxy let go of. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun done(data: MemorySegment, token: MemorySegment, text: MemorySegment) {
		val answer = asked.remove(token.address())
		val made = text.reinterpret(Long.MAX_VALUE).getString(0)

		Wl.marshalNone.invokeExact(
			token,
			TOKEN_DESTROY,
			MemorySegment.NULL,
			1,
			Wl.MARSHAL_DESTROY,
			MemorySegment.NULL,
		) as MemorySegment
		Log.say { "activation: token made" }
		answer?.complete(made)
	}

	override fun close() {
		running = false

		// The thread woken from its wait, by a reply on its queue, to see that it is to stop.
		val wrapped = Wl.wrapper.invokeExact(display) as MemorySegment

		Wl.setQueue.invoke(wrapped, queue)
		Wl.marshalNone.invokeExact(
			wrapped,
			DISPLAY_SYNC,
			Wl.iface("wl_callback_interface"),
			1,
			0,
			MemorySegment.NULL,
		) as MemorySegment
		Wl.wrapperDestroy.invoke(wrapped)
		Wl.flush.invokeExact(display) as Int
		thread.join(1000)
		asked.values.forEach { it.cancel(false) }
	}

	companion object {
		private const val DISPLAY_SYNC = 0
		private const val SEAT_GET_POINTER = 0
		private const val SEAT_GET_KEYBOARD = 1
		private const val SEAT_POINTER = 1
		private const val SEAT_KEYBOARD = 2
		private const val PRESSED = 1

		// xdg_activation_v1's and xdg_activation_token_v1's requests, as waylandprotocols.kt lists them.
		private const val ACTIVATION_GET_TOKEN = 1
		private const val TOKEN_SET_SERIAL = 0
		private const val TOKEN_SET_APP_ID = 1
		private const val TOKEN_SET_SURFACE = 2
		private const val TOKEN_COMMIT = 3
		private const val TOKEN_DESTROY = 4

		// wp_cursor_shape_manager_v1's and wp_cursor_shape_device_v1's.
		private const val SHAPES_GET_POINTER = 1
		private const val SHAPE_DESTROY = 0
		private const val SHAPE_SET_SHAPE = 1

		/** The globals the registry announces, by their interfaces' names. */
		private class Globals {
			val found = HashMap<String, Pair<Int, Int>>()

			@Suppress("unused", "UNUSED_PARAMETER")
			fun global(
				data: MemorySegment,
				registry: MemorySegment,
				name: Int,
				iface: MemorySegment,
				version: Int,
			) {
				found.putIfAbsent(iface.reinterpret(256).getString(0), name to version)
			}

			@Suppress("unused", "UNUSED_PARAMETER")
			fun removed(data: MemorySegment, registry: MemorySegment, name: Int) = Unit
		}

		/**
		 * prism's own connection to the seat on [display] (SDL's connection): its globals bound on a queue of their own,
		 * and its thread begun. Null where the compositor has no seat, or libwayland-client cannot be had.
		 */
		fun of(display: MemorySegment): WaylandSeat? {
			val arena = Arena.ofShared()

			return try {
				val queue = Wl.createQueue.invokeExact(display) as MemorySegment
				val wrapped = Wl.wrapper.invokeExact(display) as MemorySegment

				Wl.setQueue.invoke(wrapped, queue)

				val registry =
					Wl.marshalNone.invokeExact(
						wrapped,
						Wl.DISPLAY_GET_REGISTRY,
						Wl.iface("wl_registry_interface"),
						Wl.versionOf(wrapped),
						0,
						MemorySegment.NULL,
					) as MemorySegment

				Wl.wrapperDestroy.invoke(wrapped)

				val globals = Globals()

				Wl.addListener.invokeExact(
					registry,
					listenerOf(
						arena,
						MethodHandles.lookup(),
						globals,
						"global" to
							FunctionDescriptor.ofVoid(
								ADDRESS,
								ADDRESS,
								JAVA_INT,
								ADDRESS,
								JAVA_INT,
							),
						"removed" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT),
					),
					MemorySegment.NULL,
				) as Int
				Wl.roundtrip.invokeExact(display, queue) as Int

				/** [name] bound at its version or [highest], whichever is lower; null where the compositor has none. */
				fun bound(name: String, iface: MemorySegment, highest: Int): Bound? =
					globals.found[name]?.let { (global, version) ->
						val at = version.coerceAtMost(highest)

						Bound(
							Wl.marshalBind.invokeExact(
								registry,
								Wl.REGISTRY_BIND,
								iface,
								at,
								0,
								global,
								arena.allocateFrom(name),
								at,
								MemorySegment.NULL,
							) as MemorySegment,
							at,
						)
					}

				fun core(name: String, highest: Int): Bound? =
					bound(name, Wl.iface("${name}_interface"), highest)

				val seat = core("wl_seat", 1) ?: return null.also { arena.close() }

				val made =
					WaylandSeat(
						display,
						queue,
						arena,
						seat.proxy,
						dataDevices = core("wl_data_device_manager", 3),
						// Version 4 at most: from 5, a buffer's offset is a request of its own.
						compositor = core("wl_compositor", 4),
						shm = core("wl_shm", 1),
						activation = bound("xdg_activation_v1", Protocols.activation, 1),
						cursorShapes = bound("wp_cursor_shape_manager_v1", Protocols.cursorShapeManager, 2),
						layerShell = bound("zwlr_layer_shell_v1", Protocols.layerShell, 4),
					)

				Wl.destroy.invoke(registry)
				// The pointer and the keyboard are asked for when the seat says it has them: on the queue's thread.
				Wl.addListener.invokeExact(seat.proxy, made.seatListener, MemorySegment.NULL) as Int
				Wl.flush.invokeExact(display) as Int
				made.thread.start()
				Log.say {
					"wayland: seat bound; drags ${made.dataDevices != null}, " +
						"activation ${made.activation != null}, " +
						"cursor shapes ${made.cursorShapes != null}, " +
						"layer-shell ${made.layers?.version ?: "none"}"
				}

				made
			} catch (missing: IllegalArgumentException) {
				arena.close()
				null
			}
		}
	}
}

/**
 * A listener for a proxy's events: a table of upcalls to [target]'s methods named, in the order the protocol numbers
 * the events (a listener must answer every event of its version), made in [arena]. Each method is found through
 * [lookup], the caller's own, which can see [target]'s class.
 */
internal fun listenerOf(
	arena: Arena,
	lookup: MethodHandles.Lookup,
	target: Any,
	vararg events: Pair<String, FunctionDescriptor>,
): MemorySegment {
	val table = arena.allocate(ADDRESS, events.size.toLong())

	events.forEachIndexed { at, (name, descriptor) ->
		val types =
			descriptor.argumentLayouts().map {
				when (it) {
					JAVA_INT -> Int::class.javaPrimitiveType!!
					JAVA_LONG -> Long::class.javaPrimitiveType!!
					else -> MemorySegment::class.java
				}
			}

		val handle =
			lookup
				.findVirtual(target.javaClass, name, MethodType.methodType(Void.TYPE, types))
				.bindTo(target)

		table.setAtIndex(
			ADDRESS,
			at.toLong(),
			Linker.nativeLinker().upcallStub(handle, descriptor, arena),
		)
	}

	return table
}
