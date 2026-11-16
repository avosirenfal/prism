package prism.linux

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import prism.ffi.KeepsErrno

/** `mmap(2)` protections. */
@JvmInline
value class Protection(
	val bits: Int,
) {
	infix fun or(other: Protection) = Protection(bits or other.bits)

	companion object {
		val READ = Protection(1)
		val WRITE = Protection(2)
	}
}

/** `mmap(2)` sharing: the mapping is the file's (a BPF map's) own memory, not a copy. */
private const val MAP_SHARED = 1

internal fun interface Mmap {
	@KeepsErrno
	operator fun invoke(
		address: MemorySegment,
		length: Long,
		protection: Int,
		flags: Int,
		fd: Int,
		offset: Long,
	): MemorySegment
}

internal fun interface Munmap {
	@KeepsErrno
	operator fun invoke(address: MemorySegment, length: Long): Int
}

internal fun interface Sysconf {
	operator fun invoke(name: Int): Long
}

private val mmapCall: Mmap = Libc.bind("mmap")
private val munmapCall: Munmap = Libc.bind("munmap")
private val sysconfCall: Sysconf = Libc.bind("sysconf")

/** `MAP_FAILED`. */
private const val MAP_FAILED = -1L

/** The size of a page of memory: what a mapping's offsets and lengths are multiples of. */
val pageSize: Long = sysconfCall(30)

/**
 * A shared memory map of [length] bytes of [fd] from [offset]: the bytes themselves, which the caller unmaps with
 * [unmap] and must not touch after.
 */
class Mapping internal constructor(
	val segment: MemorySegment,
) {
	val length: Long get() = segment.byteSize()
}

fun map(fd: Fd, length: Long, offset: Long, protection: Protection): Sys<Mapping> {
	val address =
		mmapCall(MemorySegment.NULL, length, protection.bits, MAP_SHARED, fd.number, offset)

	return if (address.address() == MAP_FAILED)
		Sys.Failed("mmap", CallState.errno())
	else
		Sys.Ok(Mapping(address.reinterpret(length)))
}

fun unmap(mapping: Mapping): Sys<Unit> {
	val result =
		munmapCall(mapping.segment, mapping.length)

	return CallState.outcome("munmap", result.toLong()) { }
}
