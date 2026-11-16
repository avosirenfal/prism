package prism.ffi

import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandleProxies
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.invoke.VarHandle

/** A shared library's symbols, looked up the first time one is asked for: the C library, or one opened by its path. */
class Library private constructor(
	val name: String,
	lookup: () -> SymbolLookup,
) {
	private val symbols: SymbolLookup by lazy(lookup)

	/** The library loaded, if it was not: what makes a framework's Objective-C classes known to the runtime. */
	fun load(): Library = also { symbols }

	/** The address of [symbol]: a function, or a constant the library exports. */
	fun address(symbol: String): MemorySegment =
		symbols.find(symbol).orElseThrow { UnsatisfiedLinkError("$name has no $symbol") }

	/** The function [symbol], as [declared]'s one method declares it ([Signature]): bound now. */
	fun <T : Any> bind(symbol: String, declared: Class<T>): T {
		val signature = Signature.of(declared)

		val downcall =
			linker.downcallHandle(
				address(symbol),
				signature.descriptor,
				*signature.options.toTypedArray(),
			)

		// A function whose errno is kept is given the thread's segment for it first, which no call need pass.
		val handle =
			if (signature.capturesErrno)
				MethodHandles.foldArguments(downcall, CallState.segment)
			else
				downcall

		return MethodHandleProxies.asInterfaceInstance(declared, handle)
	}

	/** The function [symbol], as [T]'s one method declares it: bound now, so a symbol the library lacks is said here. */
	inline fun <reified T : Any> bind(symbol: String): T = bind(symbol, T::class.java)

	/**
	 * The function [symbol], as [T]'s one method declares it, bound the first time it is called: for a library that is
	 * only there on one platform, whose bindings are declared wherever the code is loaded.
	 */
	inline fun <reified T : Any> binding(symbol: String): Lazy<T> =
		lazy { bind(symbol, T::class.java) }

	override fun toString(): String = name

	companion object {
		private val linker: Linker = Linker.nativeLinker()

		/** The platform's linker, for the call whose signature is only known as it is made (Objective-C's `objc_msgSend`). */
		fun linker(): Linker = linker

		/** The C library and what else the process has already: libc on Linux, libSystem on macOS. */
		val system: Library = Library("the C library") { linker.defaultLookup() }

		/** The library at [path] (or of that name, as the system's loader finds it), loaded as it is first used. */
		fun at(path: String): Library =
			Library(
				path.substringAfterLast('/'),
			) { SymbolLookup.libraryLookup(path, Arena.global()) }

		/** The library [name], loaded for the life of the process now; null where there is none. */
		fun open(name: String): Library? =
			try {
				val lookup = SymbolLookup.libraryLookup(name, Arena.global())

				Library(name) { lookup }
			} catch (missing: IllegalArgumentException) {
				null
			}

		/**
		 * [implementation] as a C function pointer, of the signature [declared]'s one method declares, for as long as
		 * [arena] lives: what a native library calls back. What it throws ends the process, so it must throw nothing.
		 */
		fun <T : Any> upcall(
			declared: Class<T>,
			implementation: T,
			arena: Arena,
		): MemorySegment {
			val signature = Signature.of(declared)

			require(signature.options.isEmpty()) {
				"${declared.name}: a callback keeps no errno and is not variadic"
			}

			return linker.upcallStub(
				MethodHandles.publicLookup().unreflect(signature.method).bindTo(implementation),
				signature.descriptor,
				arena,
			)
		}

		inline fun <reified T : Any> upcall(implementation: T, arena: Arena): MemorySegment =
			upcall(T::class.java, implementation, arena)
	}
}

/**
 * The errno each thread's last call of a [KeepsErrno] function left. The JVM may run code between a native call and a
 * read of errno that changes it, so the linker copies errno into a segment the moment such a call returns: one segment
 * a thread, which every such call of the thread writes and [lastErrno] reads straight after.
 */
internal object CallState {
	val option: Linker.Option = Linker.Option.captureCallState("errno")
	private val layout = Linker.Option.captureStateLayout()
	private val errno: VarHandle =
		layout.varHandle(
			MemoryLayout.PathElement.groupElement("errno"),
		)
	private val state: ThreadLocal<MemorySegment> =
		ThreadLocal.withInitial {
			Arena.ofAuto().allocate(layout)
		}

	@JvmStatic
	fun current(): MemorySegment = state.get()

	/** [current], as a handle: what a call that keeps errno is given first. */
	val segment =
		MethodHandles.lookup().findStatic(
			CallState::class.java,
			"current",
			MethodType.methodType(MemorySegment::class.java),
		)

	fun last(): Int = errno.get(state.get(), 0L) as Int
}

/** The errno this thread's last call of a [KeepsErrno] function left. */
fun lastErrno(): Int = CallState.last()
