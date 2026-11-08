package barion.linux

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

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

private val mmapHandle =
	Libc.capturing(
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
private val munmapHandle =
	Libc.capturing(
		"munmap",
		FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG),
	)
private val sysconfHandle =
	Libc.plain(
		"sysconf",
		FunctionDescriptor.of(JAVA_LONG, JAVA_INT),
	)

/** `MAP_FAILED`. */
private const val MAP_FAILED = -1L

/** The size of a page of memory: what a mapping's offsets and lengths are multiples of. */
val pageSize: Long = sysconfHandle.invokeExact(30) as Long

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
		mmapHandle.invokeExact(
			CallState.segment(),
			MemorySegment.NULL,
			length,
			protection.bits,
			MAP_SHARED,
			fd.number,
			offset,
		)
			as MemorySegment

	return if (address.address() == MAP_FAILED)
		Sys.Failed("mmap", CallState.errno())
	else
		Sys.Ok(Mapping(address.reinterpret(length)))
}

fun unmap(mapping: Mapping): Sys<Unit> {
	val result =
		munmapHandle.invokeExact(
			CallState.segment(),
			mapping.segment,
			mapping.length,
		) as Int

	return CallState.outcome("munmap", result.toLong()) { }
}
