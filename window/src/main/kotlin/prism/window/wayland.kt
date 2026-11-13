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

/*
 * A window's pixels on Wayland, drawn by the CPU, with nothing copied: Skia draws into shared memory, which is handed
 * to the compositor as the window's buffer. SDL has no way of its own to do this on Wayland (it puts a window's pixels
 * on the screen through a GPU renderer, which needs EGL, and copies them into a texture first), so this speaks to the
 * compositor itself, through libwayland-client, on SDL's own connection: its globals bound on an event queue of its
 * own, so that nothing of SDL's is read or answered here, and its buffers attached to the surface SDL made.
 */

/** libwayland-client's calls, as this needs them. */
internal object Wl {
	private val linker = Linker.nativeLinker()

	/** Already loaded by SDL, when it chose Wayland; loaded again here, which finds the same. */
	private val library: SymbolLookup =
		SymbolLookup.libraryLookup(
			"libwayland-client.so.0",
			Arena.global(),
		)
	private val libc: SymbolLookup = linker.defaultLookup()

	fun function(
		name: String,
		descriptor: FunctionDescriptor,
		vararg options: Linker.Option,
	): MethodHandle = linker.downcallHandle(library.findOrThrow(name), descriptor, *options)

	private fun c(name: String, descriptor: FunctionDescriptor): MethodHandle =
		linker.downcallHandle(libc.findOrThrow(name), descriptor)

	fun iface(name: String): MemorySegment = library.findOrThrow(name)

	val createQueue =
		function("wl_display_create_queue", FunctionDescriptor.of(ADDRESS, ADDRESS))
	val destroyQueue = function("wl_event_queue_destroy", FunctionDescriptor.ofVoid(ADDRESS))
	val wrapper =
		function("wl_proxy_create_wrapper", FunctionDescriptor.of(ADDRESS, ADDRESS))
	val wrapperDestroy =
		function("wl_proxy_wrapper_destroy", FunctionDescriptor.ofVoid(ADDRESS))
	val setQueue =
		function("wl_proxy_set_queue", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
	val version = function("wl_proxy_get_version", FunctionDescriptor.of(JAVA_INT, ADDRESS))
	val addListener =
		function(
			"wl_proxy_add_listener",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
		)
	val destroy = function("wl_proxy_destroy", FunctionDescriptor.ofVoid(ADDRESS))
	val roundtrip =
		function(
			"wl_display_roundtrip_queue",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
		)
	val dispatchPending =
		function(
			"wl_display_dispatch_queue_pending",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
		)
	val flush = function("wl_display_flush", FunctionDescriptor.of(JAVA_INT, ADDRESS))

	/** `wl_proxy_marshal_flags`, which is variadic: one handle for each shape of arguments this sends. */
	fun marshal(vararg arguments: java.lang.foreign.MemoryLayout): MethodHandle =
		function(
			"wl_proxy_marshal_flags",
			FunctionDescriptor.of(
				ADDRESS,
				ADDRESS,
				JAVA_INT,
				ADDRESS,
				JAVA_INT,
				JAVA_INT,
				*arguments,
			),
			Linker.Option.firstVariadicArg(5),
		)

	/** A request of no arguments, or of a new object alone. */
	val marshalNone = marshal(ADDRESS)

	/** A request of one number (`wl_data_source.set_actions`). */
	val marshalInt = marshal(JAVA_INT)

	/** A request of a new object and another object (`wl_data_device_manager.get_data_device`). */
	val marshalObject = marshal(ADDRESS, ADDRESS)

	/** `wl_data_device.start_drag`: the source, the surface it starts on, its icon, the press's serial. */
	val marshalStartDrag = marshal(ADDRESS, ADDRESS, ADDRESS, JAVA_INT)

	/** A request of a number and an object (`xdg_activation_token_v1.set_serial`: the serial, the seat). */
	val marshalSerial = marshal(JAVA_INT, ADDRESS)

	/** A request of two numbers (`wp_cursor_shape_device_v1.set_shape`: the serial, the shape). */
	val marshalInts = marshal(JAVA_INT, JAVA_INT)

	/** `wl_display_dispatch_queue`: [queue]'s events read and dispatched, waiting for one where there is none. */
	val dispatchQueue =
		function("wl_display_dispatch_queue", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))

	val write = c("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG))

	/** `wl_registry.bind`: name, interface name, version, new id. */
	val marshalBind = marshal(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)

	/** `wl_shm.create_pool`: new id, fd, size. */
	val marshalPool = marshal(ADDRESS, JAVA_INT, JAVA_INT)

	/** `wl_shm_pool.create_buffer`: new id, offset, width, height, stride, format. */
	val marshalBuffer = marshal(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)

	/** `wl_surface.attach`: buffer, x, y. */
	val marshalAttach = marshal(ADDRESS, JAVA_INT, JAVA_INT)

	/** `wl_surface.damage` and `damage_buffer`: x, y, width, height. */
	val marshalDamage = marshal(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)

	val memfd = c("memfd_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
	val truncate = c("ftruncate", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG))
	val mmap =
		c(
			"mmap",
			FunctionDescriptor.of(
				ADDRESS,
				ADDRESS,
				JAVA_LONG,
				JAVA_INT,
				JAVA_INT,
				JAVA_INT,
				JAVA_LONG,
			),
		)
	val munmap = c("munmap", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG))
	val close = c("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))

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

	fun versionOf(proxy: MemorySegment): Int = version.invokeExact(proxy) as Int

	/** [buffer] attached to [surface] (where it is to go, of [scale] pixels a point), all of it damaged, and committed. */
	fun show(surface: MemorySegment, buffer: ShmBuffer, scale: Int = 1) {
		val at = versionOf(surface)

		marshalAttach.invokeExact(surface, SURFACE_ATTACH, MemorySegment.NULL, at, 0, buffer.proxy, 0, 0)
			as MemorySegment
		if (scale != 1 && at >= 3)
			marshalInt.invokeExact(surface, SURFACE_SET_BUFFER_SCALE, MemorySegment.NULL, at, 0, scale)
				as MemorySegment
		if (at >= 4)
			marshalDamage.invokeExact(
				surface,
				SURFACE_DAMAGE_BUFFER,
				MemorySegment.NULL,
				at,
				0,
				0,
				0,
				buffer.width,
				buffer.height,
			) as MemorySegment
		else
			marshalDamage.invokeExact(
				surface,
				SURFACE_DAMAGE,
				MemorySegment.NULL,
				at,
				0,
				0,
				0,
				Int.MAX_VALUE,
				Int.MAX_VALUE,
			) as MemorySegment
		marshalNone.invokeExact(surface, SURFACE_COMMIT, MemorySegment.NULL, at, 0, MemorySegment.NULL)
			as MemorySegment
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
		Wl.marshalNone.invokeExact(
			proxy,
			Wl.BUFFER_DESTROY,
			MemorySegment.NULL,
			Wl.versionOf(proxy),
			Wl.MARSHAL_DESTROY,
			MemorySegment.NULL,
		) as MemorySegment
		Wl.munmap.invokeExact(memory, size) as Int
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
					Wl.memfd.invokeExact(it.allocateFrom("prism-buffer"), MFD_CLOEXEC) as Int
				}

			if (fd < 0)
				return null

			try {
				if (Wl.truncate.invokeExact(fd, size) as Int != 0)
					return null

				val memory =
					(
						Wl.mmap.invokeExact(
							MemorySegment.NULL,
							size,
							PROT_READ or PROT_WRITE,
							MAP_SHARED,
							fd,
							0L,
						) as MemorySegment
					).takeIf { it.address() != MAP_FAILED }
						?.reinterpret(size)
						?: return null

				val pool =
					Wl.marshalPool.invokeExact(
						shm,
						Wl.SHM_CREATE_POOL,
						Wl.iface("wl_shm_pool_interface"),
						Wl.versionOf(shm),
						0,
						MemorySegment.NULL,
						fd,
						size.toInt(),
					) as MemorySegment

				val proxy =
					Wl.marshalBuffer.invokeExact(
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
						if (opaque) Wl.FORMAT_XRGB8888 else Wl.FORMAT_ARGB8888,
					) as MemorySegment

				// The pool is needed no longer once its one buffer is made: the buffer keeps the memory shared.
				Wl.marshalNone.invokeExact(
					pool,
					Wl.POOL_DESTROY,
					MemorySegment.NULL,
					Wl.versionOf(pool),
					Wl.MARSHAL_DESTROY,
					MemorySegment.NULL,
				)
					as MemorySegment

				// XRGB8888 and ARGB8888, as Wayland names them from the most significant bit: B, G, R and A (or a byte
				// unused) in memory order. With alpha, its colours are premultiplied by it, as Wayland's are.
				val skia =
					Surface.makeRasterDirect(
						ImageInfo(
							width,
							height,
							ColorType.BGRA_8888,
							if (opaque) ColorAlphaType.OPAQUE else ColorAlphaType.PREMUL,
						),
						memory.address(),
						stride,
					)

				return ShmBuffer(proxy, memory, size, width, height, skia)
			} finally {
				Wl.close.invokeExact(fd) as Int
			}
		}
	}
}

/**
 * The pixels of the Wayland surface [surface] on SDL's connection [display], drawn by the CPU into shared memory and
 * attached to the surface as they are. Two buffers at most are in use: one shown, one drawn; a third is made only while
 * the compositor holds both.
 */
internal class WaylandPixels private constructor(
	private val display: MemorySegment,
	private val surface: MemorySegment,
	private val queue: MemorySegment,
	private val shm: MemorySegment,
	private val arena: Arena,
) : Pixels {
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
		Wl.dispatchPending.invokeExact(display, queue) as Int

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
		Wl.flush.invokeExact(display) as Int

		return true
	}

	/** A buffer of [width] by [height] pixels, told to say when the compositor lets go of it; null where none can be made. */
	private fun made(width: Int, height: Int): ShmBuffer? =
		ShmBuffer.of(shm, width, height, opaque = true)?.also {
			Wl.addListener.invokeExact(it.proxy, listener, MemorySegment.NULL) as Int
		}

	override fun close() {
		buffers.forEach(ShmBuffer::free)
		buffers.clear()
		Wl.destroy.invoke(shm)
		Wl.flush.invokeExact(display) as Int
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
		fun of(handle: NativeHandle.Wayland): WaylandPixels? {
			val arena = Arena.ofShared()

			return try {
				val display = MemorySegment.ofAddress(handle.display)
				val surface = MemorySegment.ofAddress(handle.surface)
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

				Wl.addListener.invokeExact(registry, listener, MemorySegment.NULL) as Int
				Wl.roundtrip.invokeExact(display, queue) as Int

				val (name, version) = globals.shm ?: return null.also { arena.close() }

				val shm =
					Wl.marshalBind.invokeExact(
						registry,
						Wl.REGISTRY_BIND,
						Wl.iface("wl_shm_interface"),
						1,
						0,
						name,
						arena.allocateFrom("wl_shm"),
						1.coerceAtMost(version),
						MemorySegment.NULL,
					) as MemorySegment

				Wl.destroy.invoke(registry)

				WaylandPixels(display, surface, queue, shm, arena)
			} catch (missing: IllegalArgumentException) {
				arena.close()
				null
			}
		}
	}
}
