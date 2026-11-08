package barion.ebpfloader

import barion.linux.CallState
import barion.linux.NativeLibrary
import barion.linux.Sys
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/*
 * libbpf through the FFM API: what barion needs of it and nothing more. libbpf opens an object clang compiled,
 * applies its CO-RE relocations against the running kernel's BTF, creates its maps, loads its programs through the
 * verifier and attaches them. Everything after that (reading a ring buffer, the counters, a table) barion does on
 * the map's descriptor itself.
 *
 * An object, a program, a map and a link are libbpf's own structures, reached only through these pointers, so each
 * is a value class over the pointer: one cannot be passed where another is wanted.
 */

@JvmInline
internal value class ObjectHandle(
	val pointer: MemorySegment,
)

@JvmInline
internal value class ProgramHandle(
	val pointer: MemorySegment,
)

@JvmInline
internal value class MapHandle(
	val pointer: MemorySegment,
)

@JvmInline
internal value class LinkHandle(
	val pointer: MemorySegment,
)

/** libbpf's functions, bound once. Only made where the library is installed ([LibBpf.installed]). */
internal class LibBpf private constructor(
	library: NativeLibrary,
) {
	private val openMem =
		library.capturing(
			"bpf_object__open_mem",
			FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS),
		)
	private val load =
		library.capturing(
			"bpf_object__load",
			FunctionDescriptor.of(JAVA_INT, ADDRESS),
		)
	private val close =
		library.plain(
			"bpf_object__close",
			FunctionDescriptor.ofVoid(ADDRESS),
		)
	private val findProgram =
		library.plain(
			"bpf_object__find_program_by_name",
			FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
		)
	private val setAutoload =
		library.plain(
			"bpf_program__set_autoload",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_BOOLEAN),
		)
	private val setLogBuffer =
		library.plain(
			"bpf_program__set_log_buf",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG),
		)
	private val attach =
		library.capturing(
			"bpf_program__attach",
			FunctionDescriptor.of(ADDRESS, ADDRESS),
		)
	private val destroyLink =
		library.plain(
			"bpf_link__destroy",
			FunctionDescriptor.of(JAVA_INT, ADDRESS),
		)
	private val findMap =
		library.plain(
			"bpf_object__find_map_by_name",
			FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
		)
	private val mapFd =
		library.plain(
			"bpf_map__fd",
			FunctionDescriptor.of(JAVA_INT, ADDRESS),
		)
	private val update =
		library.capturing(
			"bpf_map_update_elem",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG),
		)
	private val lookup =
		library.capturing(
			"bpf_map_lookup_elem",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS),
		)
	private val delete =
		library.capturing(
			"bpf_map_delete_elem",
			FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS),
		)
	private val possibleCpus =
		library.plain(
			"libbpf_num_possible_cpus",
			FunctionDescriptor.of(JAVA_INT),
		)

	/** An object read from [bytes], not yet loaded: its maps are not created and its programs not verified. */
	fun open(bytes: MemorySegment): Sys<ObjectHandle> {
		val opened =
			openMem.invokeExact(
				CallState.segment(),
				bytes,
				bytes.byteSize(),
				MemorySegment.NULL,
			) as MemorySegment

		return if (opened.address() == 0L)
			Sys.Failed("bpf_object__open_mem", CallState.errno())
		else
			Sys.Ok(ObjectHandle(opened))
	}

	/** Creates the object's maps, relocates and verifies its programs: all of them, or none. */
	fun load(handle: ObjectHandle): Sys<Unit> {
		val result = load.invokeExact(CallState.segment(), handle.pointer) as Int

		return CallState.outcome("bpf_object__load", result.toLong()) { }
	}

	fun close(handle: ObjectHandle) {
		close.invokeExact(handle.pointer)
	}

	fun program(handle: ObjectHandle, name: String, arena: Arena): ProgramHandle? =
		(findProgram.invokeExact(handle.pointer, arena.allocateFrom(name)) as MemorySegment)
			.takeIf { it.address() != 0L }
			?.let(::ProgramHandle)

	fun skip(program: ProgramHandle) {
		setAutoload.invokeExact(program.pointer, false) as Int
	}

	/**
	 * [buffer] as where the verifier's log of [program] goes. libbpf asks for a log only when a load fails and then
	 * loads again with one, so a program that verifies costs nothing for it.
	 */
	fun logInto(program: ProgramHandle, buffer: MemorySegment) {
		setLogBuffer.invokeExact(program.pointer, buffer, buffer.byteSize()) as Int
	}

	fun attach(program: ProgramHandle): Sys<LinkHandle> {
		val link = attach.invokeExact(CallState.segment(), program.pointer) as MemorySegment

		return if (link.address() == 0L)
			Sys.Failed("bpf_program__attach", CallState.errno())
		else
			Sys.Ok(LinkHandle(link))
	}

	fun detach(link: LinkHandle) {
		destroyLink.invokeExact(link.pointer) as Int
	}

	fun map(handle: ObjectHandle, name: String, arena: Arena): MapHandle? =
		(findMap.invokeExact(handle.pointer, arena.allocateFrom(name)) as MemorySegment)
			.takeIf { it.address() != 0L }
			?.let(::MapHandle)

	fun descriptor(map: MapHandle): Int = mapFd.invokeExact(map.pointer) as Int

	fun update(fd: Int, key: MemorySegment, value: MemorySegment): Sys<Unit> {
		val result = update.invokeExact(CallState.segment(), fd, key, value, ANY) as Int

		return CallState.outcome("bpf_map_update_elem", result.toLong()) { }
	}

	/** The value under [key] into [value]; ENOENT where there is none. */
	fun lookup(fd: Int, key: MemorySegment, value: MemorySegment): Sys<Unit> {
		val result = lookup.invokeExact(CallState.segment(), fd, key, value) as Int

		return CallState.outcome("bpf_map_lookup_elem", result.toLong()) { }
	}

	fun delete(fd: Int, key: MemorySegment): Sys<Unit> {
		val result = delete.invokeExact(CallState.segment(), fd, key) as Int

		return CallState.outcome("bpf_map_delete_elem", result.toLong()) { }
	}

	/** The CPUs the kernel could ever bring up: how many values a per-CPU map holds under each key. */
	fun possibleCpus(): Int = possibleCpus.invokeExact() as Int

	companion object {
		/** `BPF_ANY`: written whether or not the key was there. */
		private const val ANY = 0L

		/** The system's libbpf, or null where it is not installed. */
		val installed: LibBpf? by lazy { NativeLibrary.open("libbpf.so.1")?.let(::LibBpf) }
	}
}
