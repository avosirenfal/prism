package prism.window

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import prism.ffi.Library
import prism.ffi.Variadic

/*
 * A window's pixels on Wayland, drawn by the CPU, with nothing copied: Skia draws into shared memory, which is handed
 * to the compositor as the window's buffer. SDL has no way of its own to do this on Wayland (it puts a window's pixels
 * on the screen through a GPU renderer, which needs EGL, and copies them into a texture first), so this speaks to the
 * compositor itself, through libwayland-client, on SDL's own connection: its globals bound on an event queue of its
 * own, so that nothing of SDL's is read or answered here, and its buffers attached to the surface SDL made.
 *
 * libwayland-client's functions, each declared once as the interface that is its signature (the ffi module), so that a
 * call is checked against what the linker was told of it. `wl_proxy_marshal_flags` is variadic, and is one function to
 * C; here it is an interface for each shape of arguments a request sends, after the five every request has: the
 * object, the request's number, what it makes (its interface, or none), the object's version, and its flags.
 */

internal fun interface WlOfProxy {
	operator fun invoke(proxy: MemorySegment): MemorySegment
}

internal fun interface WlEnd {
	operator fun invoke(proxy: MemorySegment)
}

internal fun interface WlSetQueue {
	operator fun invoke(proxy: MemorySegment, queue: MemorySegment)
}

internal fun interface WlVersion {
	operator fun invoke(proxy: MemorySegment): Int
}

internal fun interface WlAddListener {
	operator fun invoke(
		proxy: MemorySegment,
		listener: MemorySegment,
		data: MemorySegment,
	): Int
}

/** `wl_display_roundtrip_queue`, `wl_display_dispatch_queue_pending`, `wl_display_dispatch_queue`. */
internal fun interface WlOnQueue {
	operator fun invoke(display: MemorySegment, queue: MemorySegment): Int
}

internal fun interface WlFlush {
	operator fun invoke(display: MemorySegment): Int
}

/** A request of no arguments, or of a new object alone. */
internal fun interface MarshalNone {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		argument: MemorySegment,
	): MemorySegment
}

/** A request of one number (`wl_data_source.set_actions`). */
internal fun interface MarshalInt {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		number: Int,
	): MemorySegment
}

/** A request of a new object and another object (`wl_data_device_manager.get_data_device`). */
internal fun interface MarshalObject {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		new: MemorySegment,
		other: MemorySegment,
	): MemorySegment
}

/** `wl_data_device.start_drag`: the source, the surface it starts on, its icon, the press's serial. */
internal fun interface MarshalStartDrag {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		source: MemorySegment,
		origin: MemorySegment,
		icon: MemorySegment,
		serial: Int,
	): MemorySegment
}

/** A request of a number and an object (`xdg_activation_token_v1.set_serial`: the serial, the seat). */
internal fun interface MarshalSerial {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		serial: Int,
		other: MemorySegment,
	): MemorySegment
}

/** A request of two numbers (`wp_cursor_shape_device_v1.set_shape`: the serial, the shape). */
internal fun interface MarshalInts {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		first: Int,
		second: Int,
	): MemorySegment
}

/** `wl_registry.bind`: name, interface name, version, new id. */
internal fun interface MarshalBind {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		name: Int,
		interfaceName: MemorySegment,
		bound: Int,
		new: MemorySegment,
	): MemorySegment
}

/** `wl_shm.create_pool`: new id, fd, size. */
internal fun interface MarshalPool {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		new: MemorySegment,
		fd: Int,
		size: Int,
	): MemorySegment
}

/** `wl_shm_pool.create_buffer`: new id, offset, width, height, stride, format. */
internal fun interface MarshalBuffer {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		new: MemorySegment,
		offset: Int,
		width: Int,
		height: Int,
		stride: Int,
		format: Int,
	): MemorySegment
}

/** `wl_surface.attach`: buffer, x, y. */
internal fun interface MarshalAttach {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		buffer: MemorySegment,
		x: Int,
		y: Int,
	): MemorySegment
}

/** `wl_surface.damage` and `damage_buffer`: x, y, width, height. */
internal fun interface MarshalDamage {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		x: Int,
		y: Int,
		width: Int,
		height: Int,
	): MemorySegment
}

/** `zwlr_layer_shell_v1.get_layer_surface`: new id, surface, output, layer, namespace. */
internal fun interface MarshalLayerSurface {
	@Variadic(from = 5)
	operator fun invoke(
		proxy: MemorySegment,
		opcode: Int,
		made: MemorySegment,
		version: Int,
		flags: Int,
		new: MemorySegment,
		surface: MemorySegment,
		output: MemorySegment,
		layer: Int,
		namespace: MemorySegment,
	): MemorySegment
}

internal fun interface CWrite {
	operator fun invoke(fd: Int, bytes: MemorySegment, count: Long): Long
}

internal fun interface CMemfdCreate {
	operator fun invoke(name: MemorySegment, flags: Int): Int
}

internal fun interface CFtruncate {
	operator fun invoke(fd: Int, length: Long): Int
}

internal fun interface CMmap {
	operator fun invoke(
		address: MemorySegment,
		length: Long,
		protection: Int,
		flags: Int,
		fd: Int,
		offset: Long,
	): MemorySegment
}

internal fun interface CMunmap {
	operator fun invoke(address: MemorySegment, length: Long): Int
}

internal fun interface CClose {
	operator fun invoke(fd: Int): Int
}

/** libwayland-client's calls, as this needs them. */
internal object Wl {
	/** Already loaded by SDL, when it chose Wayland; loaded again here, which finds the same. */
	private val library = Library.at("libwayland-client.so.0")
	private val libc = Library.system

	fun iface(name: String): MemorySegment = library.address(name)

	val createQueue: WlOfProxy = library.bind("wl_display_create_queue")
	val destroyQueue: WlEnd = library.bind("wl_event_queue_destroy")
	val wrapper: WlOfProxy = library.bind("wl_proxy_create_wrapper")
	val wrapperDestroy: WlEnd = library.bind("wl_proxy_wrapper_destroy")
	val setQueue: WlSetQueue = library.bind("wl_proxy_set_queue")
	val version: WlVersion = library.bind("wl_proxy_get_version")
	val addListener: WlAddListener = library.bind("wl_proxy_add_listener")
	val destroy: WlEnd = library.bind("wl_proxy_destroy")
	val roundtrip: WlOnQueue = library.bind("wl_display_roundtrip_queue")
	val dispatchPending: WlOnQueue = library.bind("wl_display_dispatch_queue_pending")

	/** `wl_display_dispatch_queue`: a queue's events read and dispatched, waiting for one where there is none. */
	val dispatchQueue: WlOnQueue = library.bind("wl_display_dispatch_queue")
	val flush: WlFlush = library.bind("wl_display_flush")

	private const val MARSHAL = "wl_proxy_marshal_flags"

	val marshalNone: MarshalNone = library.bind(MARSHAL)
	val marshalInt: MarshalInt = library.bind(MARSHAL)
	val marshalObject: MarshalObject = library.bind(MARSHAL)
	val marshalStartDrag: MarshalStartDrag = library.bind(MARSHAL)
	val marshalSerial: MarshalSerial = library.bind(MARSHAL)
	val marshalInts: MarshalInts = library.bind(MARSHAL)
	val marshalBind: MarshalBind = library.bind(MARSHAL)
	val marshalPool: MarshalPool = library.bind(MARSHAL)
	val marshalBuffer: MarshalBuffer = library.bind(MARSHAL)
	val marshalAttach: MarshalAttach = library.bind(MARSHAL)
	val marshalDamage: MarshalDamage = library.bind(MARSHAL)
	val marshalLayerSurface: MarshalLayerSurface = library.bind(MARSHAL)

	val write: CWrite = libc.bind("write")
	val memfd: CMemfdCreate = libc.bind("memfd_create")
	val truncate: CFtruncate = libc.bind("ftruncate")
	val mmap: CMmap = libc.bind("mmap")
	val munmap: CMunmap = libc.bind("munmap")
	val close: CClose = libc.bind("close")

	const val MARSHAL_DESTROY = 1
	const val REGISTRY_BIND = 0
	const val DISPLAY_GET_REGISTRY = 1
	const val SHM_CREATE_POOL = 0
	const val POOL_CREATE_BUFFER = 0
	const val POOL_DESTROY = 1
	const val BUFFER_DESTROY = 0
	const val COMPOSITOR_CREATE_SURFACE = 0
	const val SURFACE_DESTROY = 0
	const val SURFACE_ATTACH = 1
	const val SURFACE_DAMAGE = 2
	const val SURFACE_COMMIT = 6
	const val SURFACE_SET_BUFFER_SCALE = 8
	const val SURFACE_DAMAGE_BUFFER = 9
	const val FORMAT_ARGB8888 = 0
	const val FORMAT_XRGB8888 = 1

	fun versionOf(proxy: MemorySegment): Int = version(proxy)

	/** [buffer] attached to [surface] (where it is to go, of [scale] pixels a point), all of it damaged, and committed. */
	fun show(surface: MemorySegment, buffer: ShmBuffer, scale: Int = 1) {
		val at = versionOf(surface)

		marshalAttach(
			surface,
			SURFACE_ATTACH,
			MemorySegment.NULL,
			at,
			0,
			buffer.proxy,
			0,
			0,
		)
		if (scale != 1 && at >= 3)
			marshalInt(
				surface,
				SURFACE_SET_BUFFER_SCALE,
				MemorySegment.NULL,
				at,
				0,
				scale,
			)
		if (at >= 4)
			marshalDamage(
				surface,
				SURFACE_DAMAGE_BUFFER,
				MemorySegment.NULL,
				at,
				0,
				0,
				0,
				buffer.width,
				buffer.height,
			)
		else
			marshalDamage(
				surface,
				SURFACE_DAMAGE,
				MemorySegment.NULL,
				at,
				0,
				0,
				0,
				Int.MAX_VALUE,
				Int.MAX_VALUE,
			)
		marshalNone(
			surface,
			SURFACE_COMMIT,
			MemorySegment.NULL,
			at,
			0,
			MemorySegment.NULL,
		)
	}
}

/**
 * A buffer the compositor is given, in shared memory: its memory, Skia's surface over it, and whether the compositor
 * holds it now. Made by [ShmBuffer.of].
 */
internal class ShmBuffer private constructor(
	val proxy: MemorySegment,
	private val memory: MemorySegment,
	private val size: Long,
	val width: Int,
	val height: Int,
	val surface: Surface,
) {
	var busy = false

	/** Let go of: Skia's surface, the compositor's buffer, and the memory. */
	fun free() {
		surface.close()
		Wl.marshalNone(
			proxy,
			Wl.BUFFER_DESTROY,
			MemorySegment.NULL,
			Wl.versionOf(proxy),
			Wl.MARSHAL_DESTROY,
			MemorySegment.NULL,
		)
		Wl.munmap(memory, size)
	}

	companion object {
		private const val MFD_CLOEXEC = 1
		private const val PROT_READ = 1
		private const val PROT_WRITE = 2
		private const val MAP_SHARED = 1
		private const val MAP_FAILED = -1L

		/**
		 * A buffer of [width] by [height] pixels in shared memory, from [shm], and Skia's surface over it: [opaque], as a
		 * window is, or with its alpha, as a drag's picture is. Null where none can be made.
		 */
		fun of(shm: MemorySegment, width: Int, height: Int, opaque: Boolean): ShmBuffer? {
			val stride = width * 4
			val size = stride.toLong() * height
			val fd =
				Arena.ofConfined().use {
					Wl.memfd(it.allocateFrom("prism-buffer"), MFD_CLOEXEC)
				}

			if (fd < 0)
				return null

			try {
				if (Wl.truncate(fd, size) != 0)
					return null

				val memory =
					(
						Wl.mmap(
							MemorySegment.NULL,
							size,
							PROT_READ or PROT_WRITE,
							MAP_SHARED,
							fd,
							0L,
						)
					).takeIf { it.address() != MAP_FAILED }
						?.reinterpret(size)
						?: return null

				val pool =
					Wl.marshalPool(
						shm,
						Wl.SHM_CREATE_POOL,
						Wl.iface("wl_shm_pool_interface"),
						Wl.versionOf(shm),
						0,
						MemorySegment.NULL,
						fd,
						size.toInt(),
					)

				val proxy =
					Wl.marshalBuffer(
						pool,
						Wl.POOL_CREATE_BUFFER,
						Wl.iface("wl_buffer_interface"),
						Wl.versionOf(pool),
						0,
						MemorySegment.NULL,
						0,
						width,
						height,
						stride,
						if (opaque)
							Wl.FORMAT_XRGB8888
						else
							Wl.FORMAT_ARGB8888,
					)

				// The pool is needed no longer once its one buffer is made: the buffer keeps the memory shared.
				Wl.marshalNone(
					pool,
					Wl.POOL_DESTROY,
					MemorySegment.NULL,
					Wl.versionOf(pool),
					Wl.MARSHAL_DESTROY,
					MemorySegment.NULL,
				)

				// XRGB8888 and ARGB8888, as Wayland names them from the most significant bit: B, G, R and A (or a byte
				// unused) in memory order. With alpha, its colours are premultiplied by it, as Wayland's are.
				val skia =
					Surface.makeRasterDirect(
						ImageInfo(
							width,
							height,
							ColorType.BGRA_8888,
							if (opaque)
								ColorAlphaType.OPAQUE
							else
								ColorAlphaType.PREMUL,
						),
						memory.address(),
						stride,
					)

				return ShmBuffer(proxy, memory, size, width, height, skia)
			} finally {
				Wl.close(fd)
			}
		}
	}
}

/**
 * The pixels of the Wayland surface [surface] on SDL's connection [display], drawn by the CPU into shared memory and
 * attached to the surface as they are: without alpha, or, for a window that is to be clear, with it, each colour
 * already multiplied by it, as a compositor blends. Two buffers at most are in use: one shown, one drawn; a third is made only while
 * the compositor holds both.
 */
internal class WaylandPixels private constructor(
	private val display: MemorySegment,
	private val surface: MemorySegment,
	private val queue: MemorySegment,
	private val shm: MemorySegment,
	private val arena: Arena,
	/** Whether the buffers are without alpha, as a window's are unless it is to be clear (backdrop.kt). */
	private val opaque: Boolean,
) : Pixels {
	override val alpha: Boolean get() = !opaque

	private val buffers = ArrayList<ShmBuffer>()

	/** What tells this a buffer is the compositor's no longer: `wl_buffer.release`, an upcall to [released]. */
	private val listener: MemorySegment =
		arena.allocate(ADDRESS).also {
			it.set(
				ADDRESS,
				0,
				Linker.nativeLinker().upcallStub(
					MethodHandles
						.lookup()
						.findVirtual(
							WaylandPixels::class.java,
							"released",
							MethodType.methodType(
								Void.TYPE,
								MemorySegment::class.java,
								MemorySegment::class.java,
							),
						).bindTo(this),
					FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
					arena,
				),
			)
		}

	@Suppress("unused", "UNUSED_PARAMETER")
	fun released(data: MemorySegment, buffer: MemorySegment) {
		buffers.firstOrNull { it.proxy.address() == buffer.address() }?.busy = false
	}

	override fun frame(width: Int, height: Int, draw: (Canvas) -> Unit): Boolean {
		if (width <= 0 || height <= 0)
			return false

		// What the compositor has let go of since, read from this one's own queue.
		Wl.dispatchPending(display, queue)

		// Buffers of another size are of no more use: let go of those the compositor does not hold.
		buffers
			.filter { !it.busy && (it.width != width || it.height != height) }
			.forEach {
				it.free()
				buffers.remove(it)
			}

		val buffer =
			buffers.firstOrNull { !it.busy && it.width == width && it.height == height }
				?: made(width, height)?.also { buffers += it }
				?: return false

		draw(buffer.surface.canvas)

		buffer.busy = true
		Wl.show(surface, buffer)
		Wl.flush(display)

		return true
	}

	/** A buffer of [width] by [height] pixels, told to say when the compositor lets go of it; null where none can be made. */
	private fun made(width: Int, height: Int): ShmBuffer? =
		ShmBuffer.of(shm, width, height, opaque)?.also {
			Wl.addListener(it.proxy, listener, MemorySegment.NULL)
		}

	override fun close() {
		buffers.forEach(ShmBuffer::free)
		buffers.clear()
		Wl.destroy.invoke(shm)
		Wl.flush(display)
		Wl.destroyQueue.invoke(queue)
		arena.close()
	}

	companion object {
		/** The registry's globals, as it announces them: what binding `wl_shm` needs, its name and version. */
		private class Globals {
			var shm: Pair<Int, Int>? = null

			@Suppress("unused", "UNUSED_PARAMETER")
			fun global(
				data: MemorySegment,
				registry: MemorySegment,
				name: Int,
				iface: MemorySegment,
				version: Int,
			) {
				if (iface.reinterpret(64).getString(0) == "wl_shm")
					shm = name to version
			}

			@Suppress("unused", "UNUSED_PARAMETER")
			fun removed(data: MemorySegment, registry: MemorySegment, name: Int) = Unit
		}

		/**
		 * The pixels of [handle]'s surface, `wl_shm` bound from the registry on a queue of this one's own; null where
		 * libwayland-client cannot be had, or the compositor has no `wl_shm` (which every one has).
		 */
		fun of(handle: NativeHandle.Wayland, opaque: Boolean = true): WaylandPixels? {
			val arena = Arena.ofShared()

			return try {
				val display = MemorySegment.ofAddress(handle.display)
				val surface = MemorySegment.ofAddress(handle.surface)
				val queue = Wl.createQueue(display)
				val wrapped = Wl.wrapper(display)

				Wl.setQueue.invoke(wrapped, queue)

				val registry =
					Wl.marshalNone(
						wrapped,
						Wl.DISPLAY_GET_REGISTRY,
						Wl.iface("wl_registry_interface"),
						Wl.version(wrapped),
						0,
						MemorySegment.NULL,
					)

				Wl.wrapperDestroy.invoke(wrapped)

				val globals = Globals()
				val lookup = MethodHandles.lookup()

				val listener =
					arena.allocate(ADDRESS, 2).also {
						it.setAtIndex(
							ADDRESS,
							0,
							Linker.nativeLinker().upcallStub(
								lookup
									.findVirtual(
										Globals::class.java,
										"global",
										MethodType.methodType(
											Void.TYPE,
											MemorySegment::class.java,
											MemorySegment::class.java,
											Int::class.javaPrimitiveType,
											MemorySegment::class.java,
											Int::class.javaPrimitiveType,
										),
									).bindTo(globals),
								FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
								arena,
							),
						)
						it.setAtIndex(
							ADDRESS,
							1,
							Linker.nativeLinker().upcallStub(
								lookup
									.findVirtual(
										Globals::class.java,
										"removed",
										MethodType.methodType(
											Void.TYPE,
											MemorySegment::class.java,
											MemorySegment::class.java,
											Int::class.javaPrimitiveType,
										),
									).bindTo(globals),
								FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT),
								arena,
							),
						)
					}

				Wl.addListener(registry, listener, MemorySegment.NULL)
				Wl.roundtrip(display, queue)

				val (name, version) = globals.shm ?: return null.also { arena.close() }

				val shm =
					Wl.marshalBind(
						registry,
						Wl.REGISTRY_BIND,
						Wl.iface("wl_shm_interface"),
						1,
						0,
						name,
						arena.allocateFrom("wl_shm"),
						1.coerceAtMost(version),
						MemorySegment.NULL,
					)

				Wl.destroy.invoke(registry)

				WaylandPixels(display, surface, queue, shm, arena, opaque)
			} catch (missing: IllegalArgumentException) {
				arena.close()
				null
			}
		}
	}
}
