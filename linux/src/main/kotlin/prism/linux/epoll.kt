package prism.linux

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import prism.ffi.KeepsErrno

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

/**
 * `EPOLLPRI` and `EPOLLERR`: what a file of the kernel's says something about it changed with, as
 * `/proc/self/mountinfo` says the mounts did.
 */
private const val EPOLLPRI = 2
private const val EPOLLERR = 8
private const val EPOLL_CTL_ADD = 1
private const val EPOLL_CLOEXEC = 0x80000

internal fun interface EpollCreate1 {
	@KeepsErrno
	operator fun invoke(flags: Int): Int
}

internal fun interface EpollCtl {
	@KeepsErrno
	operator fun invoke(epoll: Int, operation: Int, fd: Int, event: MemorySegment): Int
}

internal fun interface EpollWait {
	@KeepsErrno
	operator fun invoke(epoll: Int, events: MemorySegment, most: Int, timeout: Int): Int
}

private val createCall: EpollCreate1 = Libc.bind("epoll_create1")
private val ctlCall: EpollCtl = Libc.bind("epoll_ctl")
private val waitCall: EpollWait = Libc.bind("epoll_wait")

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
	fun watchInput(watched: Fd, tag: Long): Sys<Unit> = watch(watched, tag, EPOLLIN)

	/**
	 * Watches [watched] for what a file of the kernel's says changed (`/proc/self/mountinfo`: the mounts), reported
	 * under [tag]. Such a file says it once a change: looking at it is what takes the change as seen.
	 */
	fun watchChange(watched: Fd, tag: Long): Sys<Unit> =
		watch(
			watched,
			tag,
			EPOLLPRI or EPOLLERR,
		)

	private fun watch(watched: Fd, tag: Long, events: Int): Sys<Unit> =
		Arena.ofConfined().use { arena ->
			val event = arena.allocate(eventSize, 8)

			event.set(JAVA_INT, 0, events)
			event.set(unalignedLong, dataOffset, tag)

			val result =
				ctlCall(fd.number, EPOLL_CTL_ADD, watched.number, event)

			CallState.outcome("epoll_ctl", result.toLong()) { }
		}

	/** The tags of the descriptors with input, after waiting as [wait] says; none when it timed out or was interrupted. */
	fun await(wait: Wait): Sys<List<Long>> {
		val ready =
			waitCall(fd.number, events, capacity, wait.millis)

		return when {
			ready >= 0 ->
				Sys.Ok(
					(0 until ready).map {
						events.get(
							unalignedLong,
							it * eventSize + dataOffset,
						)
					},
				)
			CallState.errno() == Errno.EINTR -> Sys.Ok(emptyList())
			else -> Sys.Failed("epoll_wait", CallState.errno())
		}
	}

	override fun close() {
		prism.linux.close(fd)
	}

	companion object {
		/** A new instance reporting up to [capacity] ready descriptors a wait; its events live in [arena]. */
		fun create(arena: Arena, capacity: Int = 16): Sys<Epoll> {
			val result = createCall(EPOLL_CLOEXEC)

			return CallState.outcome("epoll_create1", result.toLong()) {
				Epoll(Fd(it.toInt()), arena.allocate(capacity * eventSize, 8), capacity)
			}
		}
	}
}
