package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle

/*
 * The plumbing every binding shares. libSystem is what the linker's default lookup finds on macOS (libc, libdispatch,
 * the rest of /usr/lib/libSystem.B.dylib); a framework is opened by its path. A handle is made the first time it is
 * called ([Symbol]), so nothing here asks for a symbol on a platform that has none, and a binding is a value that can
 * be held anywhere.
 */

/** Why a call failed: an errno, as Darwin numbers them. */
@JvmInline
value class Errno(
	val code: Int,
) {
	override fun toString(): String = NAMES[code] ?: "errno $code"

	companion object {
		private val NAMES =
			mapOf(
				1 to "EPERM",
				2 to "ENOENT",
				5 to "EIO",
				9 to "EBADF",
				12 to "ENOMEM",
				13 to "EACCES",
				14 to "EFAULT",
				20 to "ENOTDIR",
				22 to "EINVAL",
				45 to "ENOTSUP",
				62 to "ELOOP",
				63 to "ENAMETOOLONG",
				78 to "ENOSYS",
			)
	}
}

/** What a call gave: its value, or the call that failed and its errno. */
sealed interface Called<out T> {
	data class Ok<T>(
		val value: T,
	) : Called<T>

	data class Failed(
		val call: String,
		val errno: Errno,
	) : Called<Nothing> {
		override fun toString(): String = "$call: $errno"
	}
}

/** The value, or null where the call failed. */
fun <T> Called<T>.orNull(): T? = (this as? Called.Ok)?.value

/** A library's symbols: libSystem's, or a framework's. */
class Library private constructor(
	private val name: String,
	private val lookup: () -> SymbolLookup,
) {
	private val symbols: SymbolLookup by lazy(lookup)

	/** The address of [symbol]: a function, or a constant the library exports (`kCFTypeArrayCallBacks`). */
	fun address(symbol: String): MemorySegment =
		symbols.find(symbol).orElseThrow { UnsatisfiedLinkError("$name has no $symbol") }

	/** A function of this library, [descriptor] as C declares it, its handle made the first time it is called. */
	fun function(
		symbol: String,
		descriptor: FunctionDescriptor,
		capturesErrno: Boolean = false,
	): Symbol = Symbol(this, symbol, descriptor, capturesErrno)

	override fun toString(): String = name

	companion object {
		internal val linker: Linker = Linker.nativeLinker()

		/** libSystem: libc and the rest, as the process has them already. */
		val system: Library = Library("libSystem") { linker.defaultLookup() }

		/** The framework [name] (`CoreFoundation`), from /System/Library/Frameworks. */
		fun framework(name: String): Library =
			Library(name) {
				SymbolLookup.libraryLookup(
					"/System/Library/Frameworks/$name.framework/$name",
					Arena.global(),
				)
			}
	}
}

/** A function of a [Library], whose handle is made the first time it is called. */
class Symbol internal constructor(
	private val library: Library,
	private val name: String,
	private val descriptor: FunctionDescriptor,
	private val capturesErrno: Boolean,
) {
	/** The downcall: with the errno segment ([CallState.segment]) first, for a function that [capturesErrno]. */
	val handle: MethodHandle by lazy {
		if (capturesErrno)
			Library.linker.downcallHandle(library.address(name), descriptor, CallState.option)
		else
			Library.linker.downcallHandle(library.address(name), descriptor)
	}

	override fun toString(): String = name
}

/** The errno each thread's last capturing call left, copied as the call returned, before the JVM could change it. */
object CallState {
	internal val option: Linker.Option = Linker.Option.captureCallState("errno")
	private val layout = Linker.Option.captureStateLayout()
	private val errno: VarHandle =
		layout.varHandle(
			MemoryLayout.PathElement.groupElement("errno"),
		)
	private val state: ThreadLocal<MemorySegment> =
		ThreadLocal.withInitial {
			Arena.ofAuto().allocate(layout)
		}

	/** The current thread's segment, which a capturing call takes as its first argument. */
	fun segment(): MemorySegment = state.get()

	/** [result] of [call] as a [Called]: a negative result is the errno the call left. */
	fun <T> outcome(call: Symbol, result: Int, value: (Int) -> T): Called<T> =
		if (result < 0)
			Called.Failed(call.toString(), Errno(errno.get(state.get(), 0L) as Int))
		else
			Called.Ok(value(result))
}
