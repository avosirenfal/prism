package prism.ebpfloader

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import prism.ffi.KeepsErrno
import prism.ffi.Library
import prism.linux.CallState
import prism.linux.Sys

/*
 * libbpf through the FFM API: what prism needs of it and nothing more. libbpf opens an object clang compiled,
 * applies its CO-RE relocations against the running kernel's BTF, creates its maps, loads its programs through the
 * verifier and attaches them. Everything after that (reading a ring buffer, the counters, a table) prism does on
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

internal fun interface BpfObjectOpenMem {
	@KeepsErrno
	operator fun invoke(
		bytes: MemorySegment,
		size: Long,
		options: MemorySegment,
	): MemorySegment
}

internal fun interface BpfObjectLoad {
	@KeepsErrno
	operator fun invoke(bpfObject: MemorySegment): Int
}

internal fun interface BpfObjectClose {
	operator fun invoke(bpfObject: MemorySegment)
}

internal fun interface BpfObjectFindProgram {
	operator fun invoke(bpfObject: MemorySegment, name: MemorySegment): MemorySegment
}

internal fun interface BpfProgramSetAutoload {
	operator fun invoke(program: MemorySegment, autoload: Boolean): Int
}

internal fun interface BpfProgramSetLogBuf {
	operator fun invoke(program: MemorySegment, buffer: MemorySegment, size: Long): Int
}

internal fun interface BpfProgramAttach {
	@KeepsErrno
	operator fun invoke(program: MemorySegment): MemorySegment
}

internal fun interface BpfLinkDestroy {
	operator fun invoke(link: MemorySegment): Int
}

internal fun interface BpfObjectFindMap {
	operator fun invoke(bpfObject: MemorySegment, name: MemorySegment): MemorySegment
}

internal fun interface BpfMapFd {
	operator fun invoke(map: MemorySegment): Int
}

internal fun interface BpfMapUpdateElem {
	@KeepsErrno
	operator fun invoke(fd: Int, key: MemorySegment, value: MemorySegment, flags: Long): Int
}

internal fun interface BpfMapLookupElem {
	@KeepsErrno
	operator fun invoke(fd: Int, key: MemorySegment, value: MemorySegment): Int
}

internal fun interface BpfMapDeleteElem {
	@KeepsErrno
	operator fun invoke(fd: Int, key: MemorySegment): Int
}

internal fun interface LibbpfNumPossibleCpus {
	operator fun invoke(): Int
}

internal fun interface BpfLinkPin {
	@KeepsErrno
	operator fun invoke(link: MemorySegment, path: MemorySegment): Int
}

internal fun interface BpfObjPin {
	@KeepsErrno
	operator fun invoke(fd: Int, path: MemorySegment): Int
}

internal fun interface BpfObjGet {
	@KeepsErrno
	operator fun invoke(path: MemorySegment): Int
}

/** libbpf's functions, bound once. Only made where the library is installed ([LibBpf.installed]). */
internal class LibBpf private constructor(
	library: Library,
) {
	private val openMemCall: BpfObjectOpenMem = library.bind("bpf_object__open_mem")
	private val loadCall: BpfObjectLoad = library.bind("bpf_object__load")
	private val closeCall: BpfObjectClose = library.bind("bpf_object__close")
	private val findProgramCall: BpfObjectFindProgram =
		library.bind(
			"bpf_object__find_program_by_name",
		)
	private val setAutoloadCall: BpfProgramSetAutoload =
		library.bind(
			"bpf_program__set_autoload",
		)
	private val setLogBufferCall: BpfProgramSetLogBuf =
		library.bind(
			"bpf_program__set_log_buf",
		)
	private val attachCall: BpfProgramAttach = library.bind("bpf_program__attach")
	private val destroyLinkCall: BpfLinkDestroy = library.bind("bpf_link__destroy")
	private val findMapCall: BpfObjectFindMap = library.bind("bpf_object__find_map_by_name")
	private val mapFdCall: BpfMapFd = library.bind("bpf_map__fd")
	private val updateCall: BpfMapUpdateElem = library.bind("bpf_map_update_elem")
	private val lookupCall: BpfMapLookupElem = library.bind("bpf_map_lookup_elem")
	private val deleteCall: BpfMapDeleteElem = library.bind("bpf_map_delete_elem")
	private val possibleCpusCall: LibbpfNumPossibleCpus =
		library.bind(
			"libbpf_num_possible_cpus",
		)
	private val pinLinkCall: BpfLinkPin = library.bind("bpf_link__pin")
	private val pinObjectCall: BpfObjPin = library.bind("bpf_obj_pin")
	private val getObjectCall: BpfObjGet = library.bind("bpf_obj_get")

	/** An object read from [bytes], not yet loaded: its maps are not created and its programs not verified. */
	fun open(bytes: MemorySegment): Sys<ObjectHandle> {
		val opened =
			openMemCall(bytes, bytes.byteSize(), MemorySegment.NULL)

		return if (opened.address() == 0L)
			Sys.Failed("bpf_object__open_mem", CallState.errno())
		else
			Sys.Ok(ObjectHandle(opened))
	}

	/** Creates the object's maps, relocates and verifies its programs: all of them, or none. */
	fun load(handle: ObjectHandle): Sys<Unit> {
		val result = loadCall(handle.pointer)

		return CallState.outcome("bpf_object__load", result.toLong()) { }
	}

	fun close(handle: ObjectHandle) {
		closeCall(handle.pointer)
	}

	fun program(handle: ObjectHandle, name: String, arena: Arena): ProgramHandle? =
		(findProgramCall(handle.pointer, arena.allocateFrom(name)))
			.takeIf { it.address() != 0L }
			?.let(::ProgramHandle)

	fun skip(program: ProgramHandle) {
		setAutoloadCall(program.pointer, false)
	}

	/**
	 * [buffer] as where the verifier's log of [program] goes. libbpf asks for a log only when a load fails and then
	 * loads again with one, so a program that verifies costs nothing for it.
	 */
	fun logInto(program: ProgramHandle, buffer: MemorySegment) {
		setLogBufferCall(program.pointer, buffer, buffer.byteSize())
	}

	fun attach(program: ProgramHandle): Sys<LinkHandle> {
		val link = attachCall(program.pointer)

		return if (link.address() == 0L)
			Sys.Failed("bpf_program__attach", CallState.errno())
		else
			Sys.Ok(LinkHandle(link))
	}

	fun detach(link: LinkHandle) {
		destroyLinkCall(link.pointer)
	}

	fun map(handle: ObjectHandle, name: String, arena: Arena): MapHandle? =
		(findMapCall(handle.pointer, arena.allocateFrom(name)))
			.takeIf { it.address() != 0L }
			?.let(::MapHandle)

	fun descriptor(map: MapHandle): Int = mapFdCall(map.pointer)

	fun update(fd: Int, key: MemorySegment, value: MemorySegment): Sys<Unit> {
		val result = updateCall(fd, key, value, ANY)

		return CallState.outcome("bpf_map_update_elem", result.toLong()) { }
	}

	/** The value under [key] into [value]; ENOENT where there is none. */
	fun lookup(fd: Int, key: MemorySegment, value: MemorySegment): Sys<Unit> {
		val result = lookupCall(fd, key, value)

		return CallState.outcome("bpf_map_lookup_elem", result.toLong()) { }
	}

	fun delete(fd: Int, key: MemorySegment): Sys<Unit> {
		val result = deleteCall(fd, key)

		return CallState.outcome("bpf_map_delete_elem", result.toLong()) { }
	}

	/**
	 * [link] given the name [path] in a BPF file system: the hook stays attached for as long as the name is there,
	 * whatever becomes of the process that attached it.
	 */
	fun pin(link: LinkHandle, path: String): Sys<Unit> =
		Arena.ofConfined().use { arena ->
			val result =
				pinLinkCall(link.pointer, arena.allocateFrom(path))

			CallState.outcome("bpf_link__pin", result.toLong()) { }
		}

	/** What [fd] is (a map) given the name [path] in a BPF file system, which another process opens it by. */
	fun pin(fd: Int, path: String): Sys<Unit> =
		Arena.ofConfined().use { arena ->
			val result =
				pinObjectCall(fd, arena.allocateFrom(path))

			CallState.outcome("bpf_obj_pin", result.toLong()) { }
		}

	/** What is pinned at [path], opened: a descriptor of it, which is the caller's to close. */
	fun pinned(path: String): Sys<Int> =
		Arena.ofConfined().use { arena ->
			val result =
				getObjectCall(arena.allocateFrom(path))

			CallState.outcome("bpf_obj_get", result.toLong()) { it.toInt() }
		}

	/** The CPUs the kernel could ever bring up: how many values a per-CPU map holds under each key. */
	fun possibleCpus(): Int = possibleCpusCall()

	companion object {
		/** `BPF_ANY`: written whether or not the key was there. */
		private const val ANY = 0L

		/** The system's libbpf, or null where it is not installed. */
		val installed: LibBpf? by lazy { Library.open("libbpf.so.1")?.let(::LibBpf) }
	}
}
