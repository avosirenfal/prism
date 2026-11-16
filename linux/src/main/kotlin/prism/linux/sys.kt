package prism.linux

import prism.ffi.Library
import prism.ffi.lastErrno

/*
 * The plumbing every binding shares: libc's symbols, downcalls that capture errno the moment the call returns (the
 * JVM may run code between a call and a read of errno that changes it), and the typed outcome of a call.
 */

/** Why a system call failed: an errno, named when it is one prism meets. */
@JvmInline
value class Errno(
	val code: Int,
) {
	val name: String get() = NAMES[code] ?: "errno $code"

	override fun toString(): String = name

	companion object {
		val EPERM = Errno(1)
		val ENOENT = Errno(2)
		val EINTR = Errno(4)
		val EIO = Errno(5)
		val EBADF = Errno(9)
		val EAGAIN = Errno(11)
		val ENOMEM = Errno(12)
		val EACCES = Errno(13)
		val EFAULT = Errno(14)
		val EBUSY = Errno(16)
		val EEXIST = Errno(17)
		val ENOTDIR = Errno(20)
		val EISDIR = Errno(21)
		val EINVAL = Errno(22)
		val EMFILE = Errno(24)
		val ENOSPC = Errno(28)
		val ENAMETOOLONG = Errno(36)
		val ENOSYS = Errno(38)
		val ELOOP = Errno(40)
		val EOPNOTSUPP = Errno(95)
		val ESTALE = Errno(116)
		val ENOTSUPP = Errno(524)

		private val NAMES =
			mapOf(
				1 to "EPERM",
				2 to "ENOENT",
				4 to "EINTR",
				5 to "EIO",
				9 to "EBADF",
				11 to "EAGAIN",
				12 to "ENOMEM",
				13 to "EACCES",
				14 to "EFAULT",
				16 to "EBUSY",
				17 to "EEXIST",
				20 to "ENOTDIR",
				21 to "EISDIR",
				22 to "EINVAL",
				24 to "EMFILE",
				28 to "ENOSPC",
				36 to "ENAMETOOLONG",
				38 to "ENOSYS",
				40 to "ELOOP",
				95 to "EOPNOTSUPP",
				116 to "ESTALE",
				524 to "ENOTSUPP",
			)
	}
}

/** What a system call gave: its value, or the errno it failed with and the call that failed. */
sealed interface Sys<out T> {
	data class Ok<T>(
		val value: T,
	) : Sys<T>

	data class Failed(
		val call: String,
		val errno: Errno,
	) : Sys<Nothing> {
		override fun toString(): String = "$call: $errno"
	}
}

inline fun <T, U> Sys<T>.map(transform: (T) -> U): Sys<U> =
	when (this) {
		is Sys.Ok -> Sys.Ok(transform(value))
		is Sys.Failed -> this
	}

inline fun <T, U> Sys<T>.then(next: (T) -> Sys<U>): Sys<U> =
	when (this) {
		is Sys.Ok -> next(value)
		is Sys.Failed -> this
	}

/** The value, or a [SystemCallFailed] naming the call and its errno. */
fun <T> Sys<T>.orThrow(): T =
	when (this) {
		is Sys.Ok -> value
		is Sys.Failed -> throw SystemCallFailed(this)
	}

class SystemCallFailed(
	val failure: Sys.Failed,
) : Exception(failure.toString())

/**
 * The errno the current thread's last call of a function that keeps it left (the ffi module's `KeepsErrno`), and such
 * a call's result as a [Sys].
 */
object CallState {
	/** The errno the current thread's last such call left. */
	fun errno(): Errno = Errno(lastErrno())

	/** [result] of [call] as a [Sys]: a negative result is the errno the call left. */
	inline fun <T> outcome(call: String, result: Long, value: (Long) -> T): Sys<T> =
		if (result < 0)
			Sys.Failed(call, errno())
		else
			Sys.Ok(value(result))
}

/** libc, whose functions are each bound to the interface that is its signature (the ffi module). */
internal val Libc: Library = Library.system
