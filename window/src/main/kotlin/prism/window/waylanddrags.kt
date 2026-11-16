package prism.window

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandles
import kotlin.math.ceil
import prism.shared.Guarded
import prism.shared.Published

/*
 * Drags out of a window on Wayland, by Wayland's own drag and drop: a `wl_data_source` offering what is dragged, as
 * MIME types, started by `wl_data_device.start_drag` from the press of the pointer's button that began it. The
 * compositor then carries it to whatever window the pointer is let go over, this process's or another program's, and
 * that window asks the source for what it wants, by MIME type, through a pipe.
 *
 * SDL speaks none of this for a drag out (it takes drops, and starts no drags), so it is spoken here, on prism's own
 * connection beside SDL's ([WaylandSeat]), whose queue has a thread of its own: a window of this process that a drag
 * is dropped on is SDL's, which reads what is dropped at once, on the windows' thread, waiting for the source to write
 * it; the source's writing it must not wait for that thread.
 *
 * As it goes, a drag shows two things, as other file managers' do:
 *
 * - A picture under the pointer, as the drag's icon: a surface of its own, given to `start_drag`, the picture drawn
 *   into it at the window's density. Its top left corner is at the pointer, as thunar puts a file's icon.
 * - What letting go would do, as the pointer's cursor: a copy, a move, a link, or nothing taking it there. The
 *   compositor says what it chose to make of a drop on the window under the pointer (`action`), of what this offers
 *   and what that window takes: nothing, where it takes nothing, or the pointer is over no window. That is what the
 *   protocol has the cursor follow, and what GTK's follows. (Whether the window takes what is offered at all is said
 *   by `target` too, but not by every compositor: Smithay's, niri's, never sends it.) Over one of this process's own
 *   windows, the place under the pointer says, since SDL takes every drag it is given as a copy, whatever prism will
 *   make of it. The cursor is set by cursor-shape, where the compositor lets the process a drag began in set it while
 *   it goes.
 */

/** A drag begun by the platform's own protocol: what a [NativeDrags] starts. */
internal interface NativeDrags : AutoCloseable {
	/**
	 * A drag of [offer] begun from [window], the pointer's button held there, showing [picture] under the pointer
	 * (where there is one); [onEnd] told how it ended, on the windows' thread. False where it cannot be begun here (no
	 * press of the pointer's to begin it from).
	 */
	fun start(
		window: NativeWindow,
		offer: ClipOffer,
		picture: DragPicture?,
		onEnd: (DragEnd) -> Unit,
	): Boolean

	/**
	 * The drag over one of this process's windows, where the place under the pointer would make [effect] of a drop
	 * (null: nothing there would take it): what the pointer shows, over what the compositor says.
	 */
	fun overOwn(effect: DropEffect?)

	/** The drag gone from this process's windows, or let go on one: what the compositor says is shown again. */
	fun offOwn()

	companion object {
		/** The drags of [seat]'s compositor, where it has drag and drop; none where it has not, or there is no seat. */
		fun of(seat: WaylandSeat?): NativeDrags? = seat?.let(WaylandDrags::of)
	}
}

/** Over one of this process's windows, where a drop would make [effect] of what is dragged; null: nothing takes it. */
internal data class OwnPlace(
	val effect: DropEffect?,
)

/**
 * What the pointer's cursor during a drag is decided by: the [action] the compositor chose for a drop on the window
 * under the pointer (one of `wl_data_device_manager`'s, or none); and, over one of this process's windows, [own], what
 * the place there says.
 */
internal data class DragSeen(
	val action: Int = WaylandDrags.ACTION_NONE,
	val own: OwnPlace? = null,
) {
	/**
	 * The cursor for what letting go would do. Over this process's windows, as the place there says; elsewhere, as the
	 * compositor chose: a move or a copy (or the user asked, where the target asks them), and nothing where it chose
	 * nothing, as it does over a window that takes nothing, and over none. None to show where the compositor's data
	 * device is older than [version] 3, which says nothing of actions.
	 */
	fun cursor(version: Int): CursorShape? =
		when {
			own != null ->
				when (own.effect) {
					DropEffect.Copy -> CursorShape.Copy
					DropEffect.Move -> CursorShape.Move
					DropEffect.Link -> CursorShape.Alias
					null -> CursorShape.NoDrop
				}
			version < 3 -> null
			action == WaylandDrags.ACTION_COPY -> CursorShape.Copy
			action == WaylandDrags.ACTION_MOVE -> CursorShape.Move
			action == WaylandDrags.ACTION_ASK -> CursorShape.DndAsk
			else -> CursorShape.NoDrop
		}
}

/** Wayland's drags, on prism's own connection to the seat: see the top of this file. Made by [WaylandDrags.of]. */
internal class WaylandDrags private constructor(
	private val seat: WaylandSeat,
	private val manager: Bound,
	private val device: MemorySegment,
) : NativeDrags {
	/** A drag's picture: a surface of its own, and the one buffer it shows, of [scale] pixels a point. */
	private class Icon(
		val surface: MemorySegment,
		val buffer: ShmBuffer,
		val scale: Int,
	)

	/** The drag under way: its source, what it offers, its picture, and who is told how it ended. */
	private class Under(
		val source: MemorySegment,
		val offer: ClipOffer,
		val icon: Icon?,
		val onEnd: (DragEnd) -> Unit,
	) {
		// A flag or a number of one word, set by one thread and read by others: volatile is all it needs, unboxed.
		@Volatile
		var dropped: Boolean = false

		/**
		 * What decides the pointer, and the pointer shown for it: changed on the seat's thread (what the compositor
		 * says) and the windows' (what the place says), and together.
		 */
		class Showing {
			var seen = DragSeen()
			var shown: CursorShape? = null
		}

		val showing = Guarded(Showing())
	}

	/** The drag under way, set as one begins and let go by whichever thread ends it first ([ended]). */
	private val held = Published<Under?>(null)
	private var under: Under? by held

	override fun start(
		window: NativeWindow,
		offer: ClipOffer,
		picture: DragPicture?,
		onEnd: (DragEnd) -> Unit,
	): Boolean {
		val serial =
			seat.pressed
				?: return false.also { Log.say { "drag: no press heard to begin it from" } }

		Log.say {
			"drag: begun by Wayland's protocol, from the press of serial $serial, offering ${offer.types.keys}"
		}

		val surface = (window.handle as? NativeHandle.Wayland)?.surface ?: return false

		under?.let { ended(it, DragEnd.Cancelled) }

		val source =
			Wl.marshalNone(
				manager.proxy,
				MANAGER_CREATE_DATA_SOURCE,
				Wl.iface("wl_data_source_interface"),
				manager.version,
				0,
				MemorySegment.NULL,
			)

		Wl.addListener(source, sourceListener, MemorySegment.NULL)
		Arena.ofConfined().use { names ->
			offer.types.keys.forEach { type ->
				Wl.marshalNone(
					source,
					SOURCE_OFFER,
					MemorySegment.NULL,
					manager.version,
					0,
					names.allocateFrom(type),
				)
			}
		}
		// Copied or moved, as the target and the keys held choose; a move is the target's to make.
		if (manager.version >= 3)
			Wl.marshalInt(
				source,
				SOURCE_SET_ACTIONS,
				MemorySegment.NULL,
				manager.version,
				0,
				ACTION_COPY or ACTION_MOVE,
			)

		val icon = picture?.let { iconOf(it, window) }
		val drag = Under(source, offer, icon, onEnd)

		under = drag
		Wl.marshalStartDrag(
			device,
			DEVICE_START_DRAG,
			MemorySegment.NULL,
			manager.version,
			0,
			source,
			MemorySegment.ofAddress(surface),
			icon?.surface ?: MemorySegment.NULL,
			serial,
		)
		// Its picture given once it is the drag's: a compositor places a drag's icon by what it is given from then on.
		icon?.let { Wl.show(it.surface, it.buffer, it.scale) }
		Wl.flush(seat.display)

		return true
	}

	/**
	 * [picture] drawn for a drag from [window]: into a buffer of whole pixels for each of the window's points (a
	 * compositor scales a buffer by whole numbers; one scaling the screen by a fraction draws it a little smaller), as
	 * large as it is at the window's density, on a surface of its own. Null where the compositor has no surfaces or
	 * buffers for this connection.
	 */
	private fun iconOf(picture: DragPicture, window: NativeWindow): Icon? {
		val compositor = seat.compositor ?: return null
		val shm = seat.shm ?: return null
		val perPoint = window.pixelsPerPoint
		val scale = ceil(perPoint).toInt().coerceAtLeast(1)
		// A dp in the buffer's pixels: as many of the window's points as it is, each [scale] of the buffer's pixels.
		val density = Density(window.displayScale / perPoint * scale)

		/** [length] in the buffer's pixels, made a whole number of points, as a buffer's size must be. */
		fun pixels(length: Float): Int =
			(ceil(length * density.density / scale).toInt() * scale).coerceAtLeast(scale)

		val width = pixels(picture.size.width.value)
		val height = pixels(picture.size.height.value)
		val buffer = ShmBuffer.of(shm.proxy, width, height, opaque = false) ?: return null

		CanvasDrawScope().draw(
			density,
			LayoutDirection.Ltr,
			buffer.surface.canvas.asComposeCanvas(),
			Size(width.toFloat(), height.toFloat()),
		) {
			picture.draw(this)
		}

		val surface =
			Wl.marshalNone(
				compositor.proxy,
				Wl.COMPOSITOR_CREATE_SURFACE,
				Wl.iface("wl_surface_interface"),
				compositor.version,
				0,
				MemorySegment.NULL,
			)

		return Icon(surface, buffer, scale)
	}

	override fun overOwn(effect: DropEffect?) {
		under?.let { drag -> seen(drag) { it.copy(own = OwnPlace(effect)) } }
	}

	override fun offOwn() {
		under?.let { drag -> seen(drag) { it.copy(own = null) } }
	}

	/** What [drag]'s pointer is decided by, changed as [change] says, and the cursor it comes to shown, if it is another. */
	private fun seen(drag: Under, change: (DragSeen) -> DragSeen) =
		drag.showing.guarded {
			seen = change(seen)

			val shape = seen.cursor(manager.version)

			if (shape != null && shape != shown) {
				shown = shape
				Log.say { "drag: the pointer shows $shape, as $seen" }
				seat.show(shape)
			}
		}

	// ---- what the compositor says of the drag, on the seat's thread --------------------------------------------------

	/**
	 * The window under the pointer takes what is offered as [type], or (NULL) nothing of it. Not what the cursor
	 * follows: Smithay's compositors never say it, and what they choose to make of a drop ([action]) says as much.
	 */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun target(data: MemorySegment, source: MemorySegment, type: MemorySegment) = Unit

	/** A window asks for what is dragged as [type], to be written to [fd]: written, and the pipe closed. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun send(data: MemorySegment, source: MemorySegment, type: MemorySegment, fd: Int) {
		val asked = type.reinterpret(Long.MAX_VALUE).getString(0)
		val bytes = under?.offer?.types?.get(asked)

		Log.say { "drag: asked for $asked, ${bytes?.size ?: 0} bytes" }

		try {
			if (bytes != null)
				Arena.ofConfined().use { memory ->
					val buffer = memory.allocate(bytes.size.toLong().coerceAtLeast(1))
					var written = 0L

					buffer.copyFrom(MemorySegment.ofArray(bytes))
					while (written < bytes.size) {
						val wrote =
							Wl.write(
								fd,
								buffer.asSlice(written),
								bytes.size - written,
							)

						if (wrote <= 0)
							break
						written += wrote
					}
				}
		} finally {
			Wl.close(fd)
		}
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun cancelled(data: MemorySegment, source: MemorySegment) {
		mine(source)?.let { ended(it, DragEnd.Cancelled) }
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun dropPerformed(data: MemorySegment, source: MemorySegment) {
		mine(source)?.dropped = true
	}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun finished(data: MemorySegment, source: MemorySegment) {
		mine(source)?.let { ended(it, DragEnd.Taken) }
	}

	/** What the compositor chose to make of a drop there, of the actions offered and those the target takes. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun action(data: MemorySegment, source: MemorySegment, action: Int) {
		mine(source)?.let { drag -> seen(drag) { it.copy(action = action) } }
	}

	/** The drag under way, where [source] is its. */
	private fun mine(source: MemorySegment): Under? =
		under?.takeIf {
			it.source.address() ==
				source.address()
		}

	/** [drag] over: its source and its picture let go of, and who began it told how it ended, on the windows' thread. */
	private fun ended(drag: Under, how: DragEnd) {
		// Ended once, by whichever of the seat's thread and the windows' comes first.
		if (!held.swapped(drag, null))
			return
		Log.say { "drag: ended, $how" }
		Wl.marshalNone(
			drag.source,
			SOURCE_DESTROY,
			MemorySegment.NULL,
			manager.version,
			Wl.MARSHAL_DESTROY,
			MemorySegment.NULL,
		)
		drag.icon?.let {
			Wl.marshalNone(
				it.surface,
				Wl.SURFACE_DESTROY,
				MemorySegment.NULL,
				Wl.versionOf(it.surface),
				Wl.MARSHAL_DESTROY,
				MemorySegment.NULL,
			)
			it.buffer.free()
		}
		Wl.flush(seat.display)
		Ui.dispatch(kotlin.coroutines.EmptyCoroutineContext) { drag.onEnd(how) }
	}

	/** The listener for each data source: what the compositor says of a drag, each an upcall to this one. */
	private val sourceListener: MemorySegment =
		listenerOf(
			seat.arena,
			MethodHandles.lookup(),
			this,
			"target" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
			"send" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
			"cancelled" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
			"dropPerformed" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
			"finished" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
			"action" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT),
		)

	/** Nothing of its own to close: its connection is the seat's, closed with it. */
	override fun close() = Unit

	companion object {
		private const val MANAGER_CREATE_DATA_SOURCE = 0
		private const val MANAGER_GET_DATA_DEVICE = 1
		private const val DEVICE_START_DRAG = 0
		private const val SOURCE_OFFER = 0
		private const val SOURCE_DESTROY = 1
		private const val SOURCE_SET_ACTIONS = 2
		const val ACTION_NONE = 0
		const val ACTION_COPY = 1
		const val ACTION_MOVE = 2
		const val ACTION_ASK = 4

		/** Drags through [seat]'s data device, where its compositor has drag and drop; null where it has not. */
		fun of(seat: WaylandSeat): WaylandDrags? {
			val manager = seat.dataDevices ?: return null

			val device =
				Wl.marshalObject(
					manager.proxy,
					MANAGER_GET_DATA_DEVICE,
					Wl.iface("wl_data_device_interface"),
					manager.version,
					0,
					MemorySegment.NULL,
					seat.seat,
				)

			Wl.flush(seat.display)

			return WaylandDrags(seat, manager, device)
		}
	}
}
