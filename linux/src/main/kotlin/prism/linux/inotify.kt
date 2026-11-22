package prism.linux

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_LONG
import prism.ffi.KeepsErrno

/*
 * inotify, as far as being told that something happened in a folder needs it: watches added by path, and what they
 * say read and let go of, its kind not looked at (whoever is told looks again at what they watch). And an eventfd,
 * which wakes a thread waiting on an epoll set (to be stopped) without its waiting having to time out to look.
 */

internal fun interface InotifyInit1 {
	@KeepsErrno
	operator fun invoke(flags: Int): Int
}

internal fun interface InotifyAddWatch {
	@KeepsErrno
	operator fun invoke(fd: Int, path: MemorySegment, mask: Int): Int
}

internal fun interface Read {
	@KeepsErrno
	operator fun invoke(fd: Int, buffer: MemorySegment, size: Long): Long
}

internal fun interface Write {
	@KeepsErrno
	operator fun invoke(fd: Int, buffer: MemorySegment, size: Long): Long
}

internal fun interface EventFd {
	@KeepsErrno
	operator fun invoke(initial: Int, flags: Int): Int
}

private val initCall: InotifyInit1 = Libc.bind("inotify_init1")
private val addWatchCall: InotifyAddWatch = Libc.bind("inotify_add_watch")
private val readCall: Read = Libc.bind("read")
private val writeCall: Write = Libc.bind("write")
private val eventFdCall: EventFd = Libc.bind("eventfd")

/** `IN_NONBLOCK` and `IN_CLOEXEC` (`EFD_` the same): reads that do not wait, descriptors not inherited. */
private const val NONBLOCK = 0x800
private const val CLOEXEC = 0x80000

/** What an inotify watch is told of, by name. */
@JvmInline
value class InotifyEvents(
	val bits: Int,
) {
	infix fun or(other: InotifyEvents) = InotifyEvents(bits or other.bits)

	companion object {
		val ATTRIB = InotifyEvents(0x4)
		val MOVED_FROM = InotifyEvents(0x40)
		val MOVED_TO = InotifyEvents(0x80)
		val CREATE = InotifyEvents(0x100)
		val DELETE = InotifyEvents(0x200)
		val DELETE_SELF = InotifyEvents(0x400)
		val MOVE_SELF = InotifyEvents(0x800)
	}
}

/**
 * An inotify instance: what it watches says on [fd], which an epoll set can wait on, that something happened. Its
 * descriptor is the owner's to [close].
 */
class Inotify private constructor(
	val fd: Fd,
	private val buffer: MemorySegment,
) : AutoCloseable {
	/**
	 * [path] watched for [events], or why it cannot be (nothing is there). Watching what is watched already is the
	 * same watch; a watch of what is taken away, or unmounted, ends by itself.
	 */
	fun watch(path: String, events: InotifyEvents): Sys<Int> =
		Arena.ofConfined().use { arena ->
			val result = addWatchCall(fd.number, arena.allocateFrom(path), events.bits)

			CallState.outcome("inotify_add_watch", result.toLong()) { it.toInt() }
		}

	/** What it has said read and let go of: whether it had said anything. */
	fun drained(): Sys<Boolean> {
		var any = false

		while (true) {
			val read = readCall(fd.number, buffer, buffer.byteSize())

			when {
				read > 0 -> any = true
				read == 0L -> return Sys.Ok(any)
				CallState.errno() == Errno.EAGAIN -> return Sys.Ok(any)
				CallState.errno() == Errno.EINTR -> continue
				else -> return Sys.Failed("read", CallState.errno())
			}
		}
	}

	override fun close() {
		prism.linux.close(fd)
	}

	companion object {
		/** A new instance, read through a buffer of [arena]'s. */
		fun create(arena: Arena): Sys<Inotify> {
			val result = initCall(NONBLOCK or CLOEXEC)

			return CallState.outcome("inotify_init1", result.toLong()) {
				Inotify(Fd(it.toInt()), arena.allocate(BUFFER, 8))
			}
		}

		/** Room for a few dozen events a read. */
		private const val BUFFER = 4096L
	}
}

/** An eventfd: [wake] makes it readable, for an epoll set waiting on it to return. Its descriptor is the owner's. */
class Waker private constructor(
	val fd: Fd,
) : AutoCloseable {
	/** Whatever waits on it woken. */
	fun wake(): Sys<Unit> =
		Arena.ofConfined().use { arena ->
			val one = arena.allocate(JAVA_LONG).also { it.set(JAVA_LONG, 0, 1L) }

			CallState.outcome("write", writeCall(fd.number, one, 8)) { }
		}

	override fun close() {
		prism.linux.close(fd)
	}

	companion object {
		fun create(): Sys<Waker> =
			CallState.outcome("eventfd", eventFdCall(0, NONBLOCK or CLOEXEC).toLong()) {
				Waker(Fd(it.toInt()))
			}
	}
}
