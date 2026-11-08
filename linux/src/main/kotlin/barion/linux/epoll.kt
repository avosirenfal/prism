package barion.linux

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * `struct epoll_event`: the events, then 64 bits the caller chose. Packed on x86-64 (12 bytes, the data unaligned)
 * and naturally aligned elsewhere (16), as the kernel's own header has it.
 */
private val packed = System.getProperty("os.arch") in setOf("amd64", "x86_64")
private val eventSize: Long = if (packed) 12 else 16
private val dataOffset: Long = if (packed) 4 else 8
private val unalignedLong = JAVA_LONG.withByteAlignment(1)

/** `EPOLLIN`: there is something to read. */
private const val EPOLLIN = 1
private const val EPOLL_CTL_ADD = 1
private const val EPOLL_CLOEXEC = 0x80000

private val createHandle = Libc.capturing("epoll_create1", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
private val ctlHandle = Libc.capturing("epoll_ctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS))
private val waitHandle = Libc.capturing("epoll_wait", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))

/** How long a wait waits: not at all, a bound, or until something is ready. */
sealed interface Wait {
	val millis: Int

	data object Never : Wait {
		override val millis get() = 0
	}

	data class AtMost(
		override val millis: Int,
	) : Wait {
		init {
			require(millis > 0) { "a bounded wait is positive; Never is the zero one" }
		}
	}

	data object Forever : Wait {
		override val millis get() = -1
	}
}

/**
 * An epoll instance watching descriptors for something to read, each under a tag of the caller's: what a wait
 * returns are the tags of the ready ones. The instance's descriptor is the owner's to [close].
 */
class Epoll private constructor(
	val fd: Fd,
	private val events: MemorySegment,
	private val capacity: Int,
) : AutoCloseable {
	/** Watches [watched] for input, reported under [tag]. */
	fun watchInput(watched: Fd, tag: Long): Sys<Unit> =
		Arena.ofConfined().use { arena ->
			val event = arena.allocate(eventSize, 8)

			event.set(JAVA_INT, 0, EPOLLIN)
			event.set(unalignedLong, dataOffset, tag)

			val result = ctlHandle.invokeExact(Libc.callState(), fd.number, EPOLL_CTL_ADD, watched.number, event) as Int

			Libc.outcome("epoll_ctl", result.toLong()) { }
		}

	/** The tags of the descriptors with input, after waiting as [wait] says; none when it timed out or was interrupted. */
	fun await(wait: Wait): Sys<List<Long>> {
		val ready = waitHandle.invokeExact(Libc.callState(), fd.number, events, capacity, wait.millis) as Int

		return when {
			ready >= 0 -> Sys.Ok((0 until ready).map { events.get(unalignedLong, it * eventSize + dataOffset) })
			Libc.errno() == Errno.EINTR -> Sys.Ok(emptyList())
			else -> Sys.Failed("epoll_wait", Libc.errno())
		}
	}

	override fun close() {
		barion.linux.close(fd)
	}

	companion object {
		/** A new instance reporting up to [capacity] ready descriptors a wait; its events live in [arena]. */
		fun create(arena: Arena, capacity: Int = 16): Sys<Epoll> {
			val result = createHandle.invokeExact(Libc.callState(), EPOLL_CLOEXEC) as Int

			return Libc.outcome("epoll_create1", result.toLong()) {
				Epoll(Fd(it.toInt()), arena.allocate(capacity * eventSize, 8), capacity)
			}
		}
	}
}
