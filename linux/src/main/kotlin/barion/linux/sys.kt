package barion.linux

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle

/*
 * The plumbing every binding shares: libc's symbols, downcalls that capture errno the moment the call returns (the
 * JVM may run code between a call and a read of errno that changes it), and the typed outcome of a call.
 */

/** Why a system call failed: an errno, named when it is one barion meets. */
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
 * The errno each thread's last capturing call left. The JVM may run code between a native call and a read of errno
 * that changes it, so a capturing downcall has the linker copy errno into a segment the moment the call returns:
 * one segment per thread, which every capturing call of that thread writes and [errno] reads straight after.
 */
object CallState {
	internal val option: Linker.Option = Linker.Option.captureCallState("errno")
	private val layout: StructLayout = Linker.Option.captureStateLayout()
	private val errnoHandle: VarHandle =
		layout.varHandle(
			MemoryLayout.PathElement.groupElement("errno"),
		)
	private val state: ThreadLocal<MemorySegment> =
		ThreadLocal.withInitial {
			Arena.ofAuto().allocate(layout)
		}

	/** The current thread's segment, which a capturing call takes as its first argument. */
	fun segment(): MemorySegment = state.get()

	/** The errno the current thread's last capturing call left. */
	fun errno(): Errno = Errno(errnoHandle.get(state.get(), 0L) as Int)

	/** [result] of [call] as a [Sys]: a negative result is the errno the call left. */
	inline fun <T> outcome(call: String, result: Long, value: (Long) -> T): Sys<T> =
		if (result < 0)
			Sys.Failed(call, errno())
		else
			Sys.Ok(value(result))
}

/**
 * A shared library as the FFM API reaches it: libc, or one barion opens by its soname (libbpf). A library that is not
 * installed is null from [open], never an exception later, so whoever needs it says what to do without it.
 */
class NativeLibrary private constructor(
	val name: String,
	private val lookup: SymbolLookup,
) {
	/**
	 * A downcall to [symbol], of [descriptor] as C declares it; the linker puts the errno segment
	 * ([CallState.segment]) before the arguments. [variadicFrom] is the index of the first argument of a `...`.
	 */
	fun capturing(
		symbol: String,
		descriptor: FunctionDescriptor,
		variadicFrom: Int? = null,
	): MethodHandle =
		linker.downcallHandle(
			find(symbol),
			descriptor,
			*listOfNotNull(
				CallState.option,
				variadicFrom?.let {
					Linker.Option.firstVariadicArg(it)
				},
			).toTypedArray(),
		)

	/** A downcall to [symbol] that cannot fail, or whose failure is in its result alone. */
	fun plain(symbol: String, descriptor: FunctionDescriptor): MethodHandle =
		linker.downcallHandle(find(symbol), descriptor)

	private fun find(symbol: String): MemorySegment =
		lookup.find(symbol).orElseThrow { UnsatisfiedLinkError("$name has no $symbol") }

	override fun toString(): String = name

	companion object {
		internal val linker: Linker = Linker.nativeLinker()

		val libc: NativeLibrary = NativeLibrary("libc", linker.defaultLookup())

		/** The shared library [soname] (`libbpf.so.1`), loaded for the life of the process, or null where there is none. */
		fun open(soname: String): NativeLibrary? =
			try {
				NativeLibrary(soname, SymbolLookup.libraryLookup(soname, Arena.global()))
			} catch (missing: IllegalArgumentException) {
				null
			}
	}
}

internal val Libc: NativeLibrary = NativeLibrary.libc
