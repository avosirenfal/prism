package prism.ffi

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Native functions bound to interfaces, against the C library itself: values there and back, a function that returns
 * nothing, errno kept, a variadic function, a struct by value, a callback; and declarations that are refused.
 */
class BindingTest {
	internal fun interface Strlen {
		operator fun invoke(text: MemorySegment): Long
	}

	internal fun interface Malloc {
		operator fun invoke(size: Long): MemorySegment
	}

	internal fun interface Free {
		operator fun invoke(pointer: MemorySegment)
	}

	internal fun interface Close {
		@KeepsErrno
		operator fun invoke(fd: Int): Int
	}

	internal fun interface Snprintf {
		@Variadic(from = 3)
		operator fun invoke(
			into: MemorySegment,
			size: Long,
			format: MemorySegment,
			number: Int,
			text: MemorySegment,
		): Int
	}

	/** `struct in_addr`: four bytes. */
	object InAddr : StructType {
		override val layout: MemoryLayout = MemoryLayout.structLayout(ValueLayout.JAVA_INT)
	}

	internal fun interface InetNtoa {
		operator fun invoke(
			@ByValue(InAddr::class) address: MemorySegment,
		): MemorySegment
	}

	internal fun interface Compare {
		operator fun invoke(left: MemorySegment, right: MemorySegment): Int
	}

	internal fun interface Qsort {
		operator fun invoke(
			base: MemorySegment,
			count: Long,
			size: Long,
			compare: MemorySegment,
		)
	}

	private val libc = Library.system

	@Test
	fun valuesGoThereAndBackAndAFunctionThatReturnsNothingIsCalled() {
		val strlen: Strlen = libc.bind("strlen")
		val malloc: Malloc = libc.bind("malloc")
		val free by libc.binding<Free>("free")

		Arena.ofConfined().use { arena -> assertEquals(5, strlen(arena.allocateFrom("hello"))) }

		val made = malloc(16)

		assertTrue(made.address() != 0L)

		// The last thing in a block, which is its value: Unit, as the interface says, whatever is made of it.
		val nothing = run { free(made) }

		assertEquals(Unit, nothing)
	}

	@Test
	fun errnoIsKeptAsTheCallReturns() {
		val close: Close = libc.bind("close")

		assertEquals(-1, close(-1))
		// EBADF, on Linux and macOS alike.
		assertEquals(9, lastErrno())
	}

	@Test
	fun aVariadicFunctionAndAStructByValueAreCalledAsCDeclaresThem() {
		val snprintf: Snprintf = libc.bind("snprintf")
		val ntoa: InetNtoa = libc.bind("inet_ntoa")

		Arena.ofConfined().use { arena ->
			val into = arena.allocate(64)

			snprintf(into, 64, arena.allocateFrom("%d %s"), 42, arena.allocateFrom("files"))
			assertEquals("42 files", into.getString(0))

			// 127.0.0.1, in the network's byte order.
			val address =
				arena.allocate(InAddr.layout).also {
					it.set(ValueLayout.JAVA_INT, 0, 0x0100007F)
				}

			assertEquals("127.0.0.1", ntoa(address).reinterpret(16).getString(0))
		}
	}

	@Test
	fun aCallbackIsAFunctionPointerOfItsInterfacesSignature() {
		val qsort: Qsort = libc.bind("qsort")

		Arena.ofConfined().use { arena ->
			val numbers = arena.allocateFrom(ValueLayout.JAVA_INT, 3, 1, 2)

			val compare =
				Library.upcall<Compare>(
					Compare { left, right ->
						left
							.reinterpret(
								4,
							).get(
								ValueLayout.JAVA_INT,
								0,
							).compareTo(right.reinterpret(4).get(ValueLayout.JAVA_INT, 0))
					},
					arena,
				)

			qsort(numbers, 3, 4, compare)
			assertEquals(listOf(1, 2, 3), numbers.toArray(ValueLayout.JAVA_INT).toList())
		}
	}

	internal fun interface TakesText {
		operator fun invoke(text: String): Int
	}

	internal interface TwoMethods {
		fun one(): Int

		fun two(): Int
	}

	@Test
	fun whatCHasNoTypeForAndWhatIsNotOneFunctionAreRefusedAsTheyAreBound() {
		assertThrows(IllegalArgumentException::class.java) { libc.bind<TakesText>("strlen") }
		assertThrows(IllegalArgumentException::class.java) { libc.bind<TwoMethods>("getpid") }
		assertThrows(UnsatisfiedLinkError::class.java) {
			libc.bind<Strlen>("no_such_function_anywhere")
		}
	}
}
