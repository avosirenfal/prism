package prism.window

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/*
 * Drags out of a window on Wayland, by Wayland's own drag and drop: a `wl_data_source` offering what is dragged, as
 * MIME types, started by `wl_data_device.start_drag` from the press of the pointer's button that began it. The
 * compositor then carries it to whatever window the pointer is let go over, this process's or another program's, and
 * that window asks the source for what it wants, by MIME type, through a pipe.
 *
 * SDL speaks none of this for a drag out (it takes drops, and starts no drags), so it is spoken here, through
 * libwayland-client, on SDL's own connection. Everything of this one's is on an event queue of its own, dispatched by
 * a thread of its own: a window of this process that a drag is dropped on is SDL's, which reads what is dropped at
 * once, on the windows' thread, waiting for the source to write it; the source's writing it must not wait for that
 * thread. And a drag must be started with the serial of the button press that began it, which SDL keeps to itself;
 * so the seat is bound here too, and its pointer's presses heard (the compositor tells every pointer a client has).
 */

/** A drag begun by the platform's own protocol: what a [NativeDrags] starts. */
internal interface NativeDrags : AutoCloseable {
	/**
	 * A drag of [offer] begun from [window], the pointer's button held there; [onEnd] told how it ended, on the windows'
	 * thread. False where it cannot be begun here (no press of the pointer's to begin it from).
	 */
	fun start(window: NativeWindow, offer: ClipOffer, onEnd: (DragEnd) -> Unit): Boolean

	companion object {
		/** The drags of [handle]'s platform, where this speaks its protocol; none where it does not yet. */
		fun of(handle: NativeHandle): NativeDrags? =
			when (handle) {
				is NativeHandle.Wayland -> WaylandDrags.of(MemorySegment.ofAddress(handle.display))
				else -> null
			}
	}
}

/** Wayland's drags, on [display] (SDL's connection), on an event queue of their own: see the top of this file. */
internal class WaylandDrags private constructor(
	private val display: MemorySegment,
	private val queue: MemorySegment,
	private val arena: Arena,
) : NativeDrags {
	@Volatile
	private var running = true

	/** The serial of the last press of a pointer's button: what a drag is started from. */
	@Volatile
	var pressed: Int? = null
		private set

	private lateinit var device: MemorySegment
	private lateinit var manager: MemorySegment
	private var managerVersion = 0

	/** The drag under way: its source, what it offers, and who is told how it ended. */
	private class Under(
		val source: MemorySegment,
		val offer: ClipOffer,
		val onEnd: (DragEnd) -> Unit,
		@Volatile var dropped: Boolean = false,
	)

	@Volatile
	private var under: Under? = null

	private val thread =
		Thread({ dispatching() }, "prism wayland drags").apply {
			isDaemon = true
		}

	private fun dispatching() {
		while (running)
			if ((Wl.dispatchQueue.invokeExact(display, queue) as Int) < 0)
				return
	}

	override fun start(
		window: NativeWindow,
		offer: ClipOffer,
		onEnd: (DragEnd) -> Unit,
	): Boolean {
		val serial =
			pressed ?: return false.also { Log.say { "drag: no press heard to begin it from" } }

		Log.say {
			"drag: begun by Wayland's protocol, from the press of serial $serial, offering ${offer.types.keys}"
		}

		val surface = (window.handle as? NativeHandle.Wayland)?.surface ?: return false

		under?.let { ended(it, DragEnd.Cancelled) }

		val source =
			Wl.marshalNone.invokeExact(
				manager,
				DEVICE_MANAGER_CREATE_DATA_SOURCE,
				Wl.iface("wl_data_source_interface"),
				managerVersion,
				0,
				MemorySegment.NULL,
			) as MemorySegment

		Wl.addListener.invokeExact(source, sourceListener, MemorySegment.NULL) as Int
		Arena.ofConfined().use { names ->
			offer.types.keys.forEach { type ->
				Wl.marshalNone.invokeExact(
					source,
					SOURCE_OFFER,
					MemorySegment.NULL,
					managerVersion,
					0,
					names.allocateFrom(type),
				) as MemorySegment
			}
		}
		// Copied or moved, as the target and the keys held choose; a move is the target's to make.
		if (managerVersion >= 3)
			Wl.marshalInt.invokeExact(
				source,
				SOURCE_SET_ACTIONS,
				MemorySegment.NULL,
				managerVersion,
				0,
				ACTION_COPY or ACTION_MOVE,
			) as MemorySegment

		under = Under(source, offer, onEnd)
		Wl.marshalStartDrag.invokeExact(
			device,
			DEVICE_START_DRAG,
			MemorySegment.NULL,
			managerVersion,
			0,
			source,
			MemorySegment.ofAddress(surface),
			MemorySegment.NULL,
			serial,
		) as MemorySegment
		Wl.flush.invokeExact(display) as Int

		return true
	}

	// ---- what the compositor says, on this one's thread --------------------------------------------------------------

	@Suppress("unused", "UNUSED_PARAMETER")
	fun button(
		data: MemorySegment,
		pointer: MemorySegment,
		serial: Int,
		time: Int,
		button: Int,
		state: Int,
	) {
		if (state == BUTTON_PRESSED)
			pressed = serial
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun target(data: MemorySegment, source: MemorySegment, type: MemorySegment) = Unit

	/** A window asks for what is dragged as [type], to be written to [fd]: written, and the pipe closed. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun send(data: MemorySegment, source: MemorySegment, type: MemorySegment, fd: Int) {
		val bytes = under?.offer?.types?.get(type.reinterpret(Long.MAX_VALUE).getString(0))

		Log.say {
			"drag: asked for ${type.reinterpret(
				Long.MAX_VALUE,
			).getString(0)}, ${bytes?.size ?: 0} bytes"
		}

		try {
			if (bytes != null)
				Arena.ofConfined().use { memory ->
					val buffer = memory.allocate(bytes.size.toLong().coerceAtLeast(1))
					var written = 0L

					buffer.copyFrom(MemorySegment.ofArray(bytes))
					while (written < bytes.size) {
						val wrote =
							Wl.write.invokeExact(
								fd,
								buffer.asSlice(written),
								bytes.size - written,
							) as Long

						if (wrote <= 0)
							break
						written += wrote
					}
				}
		} finally {
			Wl.close.invokeExact(fd) as Int
		}
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun cancelled(data: MemorySegment, source: MemorySegment) {
		under
			?.takeIf {
				it.source.address() == source.address()
			}?.let { ended(it, DragEnd.Cancelled) }
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun dropPerformed(data: MemorySegment, source: MemorySegment) {
		under?.takeIf { it.source.address() == source.address() }?.dropped = true
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun finished(data: MemorySegment, source: MemorySegment) {
		under
			?.takeIf {
				it.source.address() == source.address()
			}?.let { ended(it, DragEnd.Taken) }
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun action(data: MemorySegment, source: MemorySegment, action: Int) = Unit

	/** [drag] over: its source let go, and who began it told how it ended, on the windows' thread. */
	private fun ended(drag: Under, how: DragEnd) {
		if (under !== drag)
			return
		Log.say { "drag: ended, $how" }
		under = null
		Wl.marshalNone.invokeExact(
			drag.source,
			SOURCE_DESTROY,
			MemorySegment.NULL,
			managerVersion,
			Wl.MARSHAL_DESTROY,
			MemorySegment.NULL,
		) as MemorySegment
		Ui.dispatch(kotlin.coroutines.EmptyCoroutineContext) { drag.onEnd(how) }
	}

	/** The listener for each data source: what the compositor says of a drag, each an upcall to this one. */
	private val sourceListener: MemorySegment by lazy {
		listenerOf(
			"target" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
			"send" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
			"cancelled" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
			"dropPerformed" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
			"finished" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
			"action" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT),
		)
	}

	/** A listener: a table of upcalls, one for each event, to the methods of this one named, in the events' order. */
	private fun listenerOf(vararg events: Pair<String, FunctionDescriptor>): MemorySegment {
		val table = arena.allocate(ADDRESS, events.size.toLong())

		events.forEachIndexed { at, (name, descriptor) ->
			table.setAtIndex(ADDRESS, at.toLong(), upcall(this, name, descriptor))
		}

		return table
	}

	private fun upcall(
		target: Any,
		name: String,
		descriptor: FunctionDescriptor,
	): MemorySegment =
		Linker.nativeLinker().upcallStub(handleOf(target, name, descriptor), descriptor, arena)

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
	}

	companion object {
		private const val DISPLAY_SYNC = 0
		private const val SEAT_GET_POINTER = 0
		private const val DEVICE_MANAGER_CREATE_DATA_SOURCE = 0
		private const val DEVICE_MANAGER_GET_DATA_DEVICE = 1
		private const val DEVICE_START_DRAG = 0
		private const val SOURCE_OFFER = 0
		private const val SOURCE_DESTROY = 1
		private const val SOURCE_SET_ACTIONS = 2
		private const val ACTION_COPY = 1
		private const val ACTION_MOVE = 2
		private const val BUTTON_PRESSED = 1
		private const val SEAT_POINTER = 1

		private fun handleOf(
			target: Any,
			name: String,
			descriptor: FunctionDescriptor,
		): MethodHandle {
			val types =
				descriptor.argumentLayouts().map {
					when (it) {
						JAVA_INT -> Int::class.javaPrimitiveType!!
						JAVA_LONG -> Long::class.javaPrimitiveType!!
						else -> MemorySegment::class.java
					}
				}

			return MethodHandles
				.lookup()
				.findVirtual(target.javaClass, name, MethodType.methodType(Void.TYPE, types))
				.bindTo(target)
		}

		/** The globals the registry announces: each one's name and version, by its interface. */
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

		/** A pointer's events, all heard and only its presses kept (a listener must answer every event of its version). */
		private class PointerEvents(
			val drags: WaylandDrags,
		) {
			@Suppress("unused", "UNUSED_PARAMETER")
			fun enter(
				data: MemorySegment,
				pointer: MemorySegment,
				serial: Int,
				surface: MemorySegment,
				x: Int,
				y: Int,
			) = Unit

			@Suppress("unused", "UNUSED_PARAMETER")
			fun leave(
				data: MemorySegment,
				pointer: MemorySegment,
				serial: Int,
				surface: MemorySegment,
			) = Unit

			@Suppress("unused", "UNUSED_PARAMETER")
			fun motion(
				data: MemorySegment,
				pointer: MemorySegment,
				time: Int,
				x: Int,
				y: Int,
			) = Unit

			@Suppress("unused")
			fun button(
				data: MemorySegment,
				pointer: MemorySegment,
				serial: Int,
				time: Int,
				button: Int,
				state: Int,
			) = drags.button(data, pointer, serial, time, button, state)

			@Suppress("unused", "UNUSED_PARAMETER")
			fun axis(
				data: MemorySegment,
				pointer: MemorySegment,
				time: Int,
				axis: Int,
				value: Int,
			) = Unit
		}

		/**
		 * A seat's events (version 1's alone, which the seat is bound at): its pointer asked for once it has one, and
		 * let go of if it loses it. A seat must not be asked for a pointer it has not got.
		 */
		private class SeatEvents(
			val drags: WaylandDrags,
			val pointerListener: MemorySegment,
		) {
			private var pointer: MemorySegment? = null

			@Suppress("unused", "UNUSED_PARAMETER")
			fun capabilities(data: MemorySegment, seat: MemorySegment, capabilities: Int) {
				val has = capabilities and SEAT_POINTER != 0

				if (has && pointer == null) {
					val made =
						Wl.marshalNone.invokeExact(
							seat,
							SEAT_GET_POINTER,
							Wl.iface("wl_pointer_interface"),
							1,
							0,
							MemorySegment.NULL,
						) as MemorySegment

					Wl.addListener.invokeExact(made, pointerListener, MemorySegment.NULL) as Int
					pointer = made
				} else if (!has && pointer != null) {
					Wl.destroy.invoke(pointer)
					pointer = null
					drags.pressed = null
				}
			}
		}

		/**
		 * Wayland's drags on [display], the seat and the data device manager bound on a queue of their own; null where
		 * the compositor has neither, or libwayland-client cannot be had.
		 */
		fun of(display: MemorySegment): WaylandDrags? {
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
						Wl.version.invokeExact(wrapped) as Int,
						0,
						MemorySegment.NULL,
					) as MemorySegment

				Wl.wrapperDestroy.invoke(wrapped)

				val globals = Globals()
				val registryListener = arena.allocate(ADDRESS, 2)

				fun stub(target: Any, name: String, descriptor: FunctionDescriptor) =
					Linker.nativeLinker().upcallStub(
						handleOf(target, name, descriptor),
						descriptor,
						arena,
					)

				registryListener.setAtIndex(
					ADDRESS,
					0,
					stub(
						globals,
						"global",
						FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
					),
				)
				registryListener.setAtIndex(
					ADDRESS,
					1,
					stub(globals, "removed", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)),
				)
				Wl.addListener.invokeExact(registry, registryListener, MemorySegment.NULL) as Int
				Wl.roundtrip.invokeExact(display, queue) as Int

				val (seatName, _) = globals.found["wl_seat"] ?: return null.also { arena.close() }
				val (managerName, managerVersion) =
					globals.found["wl_data_device_manager"] ?: return null.also { arena.close() }
				val version = managerVersion.coerceAtMost(3)

				fun bind(name: Int, iface: String, at: Int): MemorySegment =
					Wl.marshalBind.invokeExact(
						registry,
						Wl.REGISTRY_BIND,
						Wl.iface("${iface}_interface"),
						at,
						0,
						name,
						arena.allocateFrom(iface),
						at,
						MemorySegment.NULL,
					) as MemorySegment

				val seat = bind(seatName, "wl_seat", 1)
				val manager = bind(managerName, "wl_data_device_manager", version)
				val drags = WaylandDrags(display, queue, arena)

				drags.manager = manager
				drags.managerVersion = version

				val events = PointerEvents(drags)
				val pointerListener = arena.allocate(ADDRESS, 5)

				listOf(
					"enter" to
						FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
					"leave" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS),
					"motion" to
						FunctionDescriptor.ofVoid(
							ADDRESS,
							ADDRESS,
							JAVA_INT,
							JAVA_INT,
							JAVA_INT,
						),
					"button" to
						FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
					"axis" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
				).forEachIndexed { at, (name, descriptor) ->
					pointerListener.setAtIndex(ADDRESS, at.toLong(), stub(events, name, descriptor))
				}

				// The pointer is asked for when the seat says it has one: on the queue's thread, from its capabilities.
				val seatListener = arena.allocate(ADDRESS, 1)

				seatListener.setAtIndex(
					ADDRESS,
					0,
					stub(
						SeatEvents(drags, pointerListener),
						"capabilities",
						FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT),
					),
				)
				Wl.addListener.invokeExact(seat, seatListener, MemorySegment.NULL) as Int

				drags.device =
					Wl.marshalObject.invokeExact(
						manager,
						DEVICE_MANAGER_GET_DATA_DEVICE,
						Wl.iface("wl_data_device_interface"),
						version,
						0,
						MemorySegment.NULL,
						seat,
					) as MemorySegment
				Wl.flush.invokeExact(display) as Int
				drags.thread.start()

				drags
			} catch (missing: IllegalArgumentException) {
				arena.close()
				null
			}
		}
	}
}
