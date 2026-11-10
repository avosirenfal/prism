package prism.ebpfloader

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.VarHandle
import prism.ebpf.Record
import prism.ebpf.RingBuffer
import prism.linux.Epoll
import prism.linux.Fd
import prism.linux.Mapping
import prism.linux.Protection
import prism.linux.Sys
import prism.linux.Wait
import prism.linux.map
import prism.linux.pageSize
import prism.linux.then
import prism.linux.unmap

/*
 * A BPF ring buffer, read by the host in place. The kernel lays a ring out as three mappings of its descriptor: a
 * page holding where the host has read to (which the host writes), a page holding where the kernel has written to,
 * and the data, mapped twice back to back so that a record that wraps past the end is still contiguous in memory.
 * Reading is reading those positions with the memory ordering the kernel writes them with: the kernel publishes a
 * record by releasing its header and then its position, so the host acquires the position, then each header, and
 * releases its own position once it is done with a record, which is what tells the kernel the space is free.
 *
 * libbpf has a reader of its own, which calls back into the host once per record. This one hands each record to
 * Kotlin as a view of the ring's own memory, with no copy and no upcall, and drains everything there is in one go.
 */

/** The kernel's ring buffer header: the record's length, with these two bits taken from the top of it. */
private const val BUSY = 1 shl 31
private const val DISCARDED = 1 shl 30
private const val HEADER_BYTES = 8

private val position: VarHandle = JAVA_LONG.varHandle()
private val header: VarHandle = JAVA_INT.varHandle()

/**
 * The host's side of [ring]: what the kernel has submitted, read in order. One thread's at a time; it is the only
 * reader of its ring, since the position it releases is the ring's one consumer position.
 */
class RingReader<R : Record<R>> private constructor(
	val ring: RingBuffer<R>,
	private val consumer: Mapping,
	private val producer: Mapping,
	private val epoll: Epoll,
) : AutoCloseable {
	private val data: MemorySegment =
		producer.segment.asSlice(
			pageSize,
			2L * ring.maxEntries,
		)
	private val mask: Long = ring.maxEntries - 1L

	/**
	 * Every record submitted since the last read, each handed to [visit] where it lies: valid only while [visit]
	 * runs. Stops at a record the kernel has reserved but not yet submitted, which the next read starts from, since
	 * the ring is read in order. How many records [visit] was given.
	 */
	fun drain(visit: (RecordView<R>) -> Unit): Int {
		var read = position.getAcquire(consumer.segment, 0L) as Long
		var visited = 0

		while (true) {
			val written = position.getAcquire(producer.segment, 0L) as Long

			if (read >= written)
				return visited

			val at = read and mask
			val word = header.getAcquire(data, at) as Int

			if (word and BUSY != 0)
				return visited

			val length = word and (BUSY or DISCARDED).inv()

			if (word and DISCARDED == 0 && length >= ring.record.layout.sizeBytes) {
				visit(ring.record.view(data.asSlice(at + HEADER_BYTES, length.toLong())))
				visited++
			}
			read += (length + HEADER_BYTES + 7L) and 7L.inv()
			position.setRelease(consumer.segment, 0L, read)
		}
	}

	/** Waits as [wait] says for the kernel to submit something, then [drain]s. */
	fun await(wait: Wait, visit: (RecordView<R>) -> Unit): Sys<Int> =
		epoll.await(wait).then { Sys.Ok(drain(visit)) }

	override fun close() {
		epoll.close()
		unmap(producer)
		unmap(consumer)
	}

	internal companion object {
		/**
		 * The reader of [ring], whose map's descriptor is [fd]: its mappings, and an epoll that wakes on a submit.
		 * What was made before a step that failed is undone, so a failure leaves nothing mapped behind it.
		 */
		fun <R : Record<R>> open(
			ring: RingBuffer<R>,
			fd: Fd,
			arena: Arena,
		): Sys<RingReader<R>> {
			val consumer =
				when (val mapped = map(fd, pageSize, 0, Protection.READ or Protection.WRITE)) {
					is Sys.Ok -> mapped.value
					is Sys.Failed -> return mapped
				}

			val producer =
				when (
					val mapped =
						map(
							fd,
							pageSize + 2L * ring.maxEntries,
							pageSize,
							Protection.READ,
						)
				) {
					is Sys.Ok -> mapped.value
					is Sys.Failed -> {
						unmap(consumer)

						return mapped
					}
				}

			val epoll =
				when (val made = Epoll.create(arena)) {
					is Sys.Ok -> made.value
					is Sys.Failed -> {
						unmap(producer)
						unmap(consumer)

						return made
					}
				}

			return when (val watched = epoll.watchInput(fd, 0L)) {
				is Sys.Ok -> Sys.Ok(RingReader(ring, consumer, producer, epoll))
				is Sys.Failed -> {
					epoll.close()
					unmap(producer)
					unmap(consumer)

					watched
				}
			}
		}
	}
}
