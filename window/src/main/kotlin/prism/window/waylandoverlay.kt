package prism.window

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandles
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/*
 * Surfaces in the compositor's overlay layer, as wlr-layer-shell has them: what an overlay is on Wayland (role.kt).
 *
 * A window of SDL's is a surface with the role of a window (an `xdg_toplevel`). SDL can be told to make a window whose
 * surface it gives no role, and to go on drawing into it and hearing its keys all the same; the role is then given
 * here. A surface is given the role of a layer surface by asking layer-shell for one of it: in which layer, on which
 * output, under what name. It is then told how large it wishes to be, which edges of the screen it holds to, and
 * whether the keyboard is its own, and committed with nothing in it. The compositor answers with `configure`, saying
 * how large it is to be; only once that is acknowledged may anything be drawn in it. That order is the protocol's,
 * and a surface drawn in before then is an error that ends the connection: so a layer surface is made, and its first
 * `configure` waited for, before its window draws its first frame.
 *
 * Its events arrive on prism's own queue, on the seat's thread (waylandseat.kt): `configure` is acknowledged there at
 * once, and what follows from it (the window made another size, the window closed) is done on the windows' thread.
 *
 * The keyboard is a layer surface's in one of two ways: exclusively, when it is the surface's alone until the surface
 * is gone; or on demand, when it is the surface's as a window's is, until something else is clicked. An overlay
 * that holds the keyboard asks for it exclusively, and that is all. One that shares it asks on demand, and what
 * comes of that is the compositor's to say, and they say different things:
 *
 * - niri gives a surface that appears asking on demand the keyboard, and takes it from one that had it exclusively
 *   and now asks on demand;
 * - sway gives a surface that appears asking on demand nothing until it is clicked, and leaves the keyboard with one
 *   that had it exclusively and now asks on demand.
 *
 * So an overlay that shares the keyboard asks on demand as it is made. Not given the keyboard a moment after it is
 * on the screen, it asks exclusively, to be given it, and once it has it asks on demand again; and where that costs
 * it the keyboard at once, it asks exclusively for good. On either compositor it ends typed into, and, where the
 * compositor has a way to it, with a click elsewhere taking the keyboard. How a surface stands in this, and what
 * follows from each thing that happens, is one state with nothing else to it ([Grip], grip.kt); the surface keeps
 * it ([LayerSurface.told]), and window.kt tells it what SDL says of the keyboard.
 */

/** The layer surfaces of this process, and what hears their events: one, made with the seat, where there is layer-shell. */
internal class Layers(
	private val display: MemorySegment,
	private val shell: Bound,
	arena: Arena,
) {
	private val surfaces = ConcurrentHashMap<Long, LayerSurface>()

	private val listener =
		listenerOf(
			arena,
			MethodHandles.lookup(),
			this,
			"configure" to
				FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
			"closed" to FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
		)

	/** The version of layer-shell the compositor has, as far as prism speaks it. */
	val version: Int get() = shell.version

	/**
	 * [surface] (one with no role yet) made a layer surface as [spec] says, [width] by [height] in the compositor's
	 * units, on [output] (the compositor's choice, where null), and configured: the size the compositor says it is to
	 * be, where it says another, is given to [onSized], on the seat's thread, each time it does; [onClosed] when the
	 * compositor has done away with it. Null where the compositor did not answer in [patience]: the surface is then
	 * left without the role, for the caller to do without.
	 */
	fun surface(
		surface: MemorySegment,
		output: MemorySegment?,
		spec: LayerSpec,
		width: Int,
		height: Int,
		onSized: (Int, Int) -> Unit,
		onClosed: () -> Unit,
		patience: Long = PATIENCE_MILLIS,
	): LayerSurface? {
		val proxy =
			Arena.ofConfined().use {
				GET_LAYER_SURFACE.invokeExact(
					shell.proxy,
					SHELL_GET_LAYER_SURFACE,
					Protocols.layerSurface,
					shell.version,
					0,
					MemorySegment.NULL,
					surface,
					output ?: MemorySegment.NULL,
					LAYER_OVERLAY,
					it.allocateFrom(spec.namespace),
				) as MemorySegment
			}

		val first = Grip.of(spec.shares)

		val made =
			LayerSurface(
				this,
				proxy,
				surface,
				shell.version,
				first,
				onSized,
				onClosed,
			)

		surfaces[proxy.address()] = made
		Wl.addListener.invokeExact(proxy, listener, MemorySegment.NULL) as Int

		made.send(Wl.marshalInts, SURFACE_SET_SIZE, width, height)
		made.send(Wl.marshalInt, SURFACE_SET_ANCHOR, spec.anchor)
		Wl.marshalDamage.invokeExact(
			proxy,
			SURFACE_SET_MARGIN,
			MemorySegment.NULL,
			shell.version,
			0,
			spec.margins.top,
			spec.margins.right,
			spec.margins.bottom,
			spec.margins.left,
		) as MemorySegment
		made.send(Wl.marshalInt, SURFACE_SET_KEYBOARD, first.asks.wire)
		// Committed with nothing in it: what asks the compositor to say how it is to be.
		commit(surface)

		val configured =
			try {
				made.first.get(patience, TimeUnit.MILLISECONDS)
				true
			} catch (late: TimeoutException) {
				false
			} catch (closed: ExecutionException) {
				false
			}

		if (!configured) {
			Log.say {
				"wayland: the compositor did not configure the layer surface “${spec.namespace}”"
			}
			made.close()

			return null
		}

		return made
	}

	/** [surface]'s pending state committed, and sent. */
	fun commit(surface: MemorySegment) {
		Wl.marshalNone.invokeExact(
			surface,
			Wl.SURFACE_COMMIT,
			MemorySegment.NULL,
			Wl.versionOf(surface),
			0,
			MemorySegment.NULL,
		) as MemorySegment
		Wl.flush.invokeExact(display) as Int
	}

	fun forget(surface: LayerSurface) {
		surfaces.remove(surface.proxy.address())
		Wl.flush.invokeExact(display) as Int
	}

	/** `zwlr_layer_surface_v1.configure`: acknowledged at once, and its size told to the surface it is for. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun configure(
		data: MemorySegment,
		proxy: MemorySegment,
		serial: Int,
		width: Int,
		height: Int,
	) {
		val surface = surfaces[proxy.address()] ?: return

		surface.send(Wl.marshalInt, SURFACE_ACK_CONFIGURE, serial)
		Wl.flush.invokeExact(display) as Int
		surface.configured(width, height)
	}

	/** `zwlr_layer_surface_v1.closed`: the output it was on is gone, or the compositor will have it no longer. */
	@Suppress("unused", "UNUSED_PARAMETER")
	fun closed(data: MemorySegment, proxy: MemorySegment) {
		surfaces[proxy.address()]?.closedByCompositor()
	}

	internal companion object {
		// zwlr_layer_shell_v1's and zwlr_layer_surface_v1's requests, as waylandprotocols.kt lists them.
		private const val SHELL_GET_LAYER_SURFACE = 0
		private const val SURFACE_SET_SIZE = 0
		private const val SURFACE_SET_ANCHOR = 1
		private const val SURFACE_SET_MARGIN = 3
		const val SURFACE_SET_KEYBOARD = 4
		private const val SURFACE_ACK_CONFIGURE = 6
		const val SURFACE_DESTROY = 7

		/** The layer over every window, fullscreen ones included. */
		private const val LAYER_OVERLAY = 3

		/** How long a compositor has to say how a layer surface is to be, before prism does without one. */
		private const val PATIENCE_MILLIS = 3000L

		/** `get_layer_surface`: the new surface, the `wl_surface`, the output, the layer, the namespace. */
		private val GET_LAYER_SURFACE = Wl.marshal(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
	}
}

/**
 * A surface given the role of a layer surface: what an overlay's window is on Wayland, for as long as the window is
 * open. Closed before the surface it is the role of.
 */
internal class LayerSurface(
	private val layers: Layers,
	val proxy: MemorySegment,
	/** The `wl_surface` this is the role of. */
	private val surface: MemorySegment,
	private val version: Int,
	/** How it stands with the keyboard as it is made: what the compositor is asked for it first. */
	first: Grip.Live,
	private val onSized: (Int, Int) -> Unit,
	private val onClosed: () -> Unit,
) : AutoCloseable {
	/**
	 * How it stands with the keyboard (grip.kt), and whether it still is at all: the one thing about it that changes,
	 * written on the windows' thread alone.
	 */
	@Volatile
	var grip: Grip = first
		private set

	/**
	 * What its window heard of the keyboard: how it stands from then, the compositor asked for whatever that is where
	 * it is not what it was asked last, and what came of it given.
	 */
	fun told(told: Told): Grip.Step =
		grip.told(told).also { step ->
			step.toAsk?.let {
				send(Wl.marshalInt, Layers.SURFACE_SET_KEYBOARD, it.asks.wire)
				layers.commit(surface)
			}
			grip = step.grip
		}

	/** Completed by the first `configure`: until then nothing may be drawn. */
	val first = CompletableFuture<Unit>()

	/** The size the compositor first said, where it said one: what the window is made, before it draws. */
	@Volatile
	var size: Pair<Int, Int>? = null
		private set

	/** A request of this surface's sent: [arguments] as [marshal] takes them. */
	fun send(marshal: java.lang.invoke.MethodHandle, opcode: Int, vararg arguments: Int) {
		if (grip == Grip.Gone)
			return

		when (arguments.size) {
			1 -> marshal.invoke(proxy, opcode, MemorySegment.NULL, version, 0, arguments[0])
			else ->
				marshal.invoke(
					proxy,
					opcode,
					MemorySegment.NULL,
					version,
					0,
					arguments[0],
					arguments[1],
				)
		}
	}

	/** The compositor said it is to be [width] by [height]; nothing of either means as large as it likes. */
	fun configured(width: Int, height: Int) {
		val said = (width to height).takeIf { width > 0 && height > 0 }

		if (!first.isDone) {
			size = said
			first.complete(Unit)
		} else if (said != null && said != size) {
			size = said
			onSized(width, height)
		}
	}

	fun closedByCompositor() {
		if (!first.isDone)
			first.completeExceptionally(IllegalStateException("closed before it was configured"))
		else
			onClosed()
	}

	/** The role given up: before the surface itself is. */
	override fun close() {
		if (grip == Grip.Gone)
			return

		told(Told.Closed)
		Wl.marshalNone.invokeExact(
			proxy,
			Layers.SURFACE_DESTROY,
			MemorySegment.NULL,
			version,
			Wl.MARSHAL_DESTROY,
			MemorySegment.NULL,
		) as MemorySegment
		layers.forget(this)
	}
}
