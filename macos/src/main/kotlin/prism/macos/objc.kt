package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import prism.ffi.Library

/*
 * Objective-C through its runtime's C functions, which FFM calls as it calls libc: a class by its name
 * (`objc_getClass`), a selector by its name (`sel_registerName`), and a message sent by calling `objc_msgSend` as the
 * method's own C function would be called, the receiver and the selector first. On Apple silicon that is how every
 * method is called, whatever it returns, so the one function, given each method's signature, reaches them all; a
 * message to nil does nothing and answers nil, zero or false.
 *
 * A method is declared once, as a value of its class's object (classes.kt): its selector, what it takes and what it
 * returns ([Passed], [Returned]), from which both the signature `objc_msgSend` is called with and the Kotlin types of
 * a call are made. So a call that passes the wrong thing does not compile, and no message is sent that is not
 * declared; and since every declaration is a value, a test on a Mac asks the runtime for each method's own signature
 * and compares ([Declared.mismatch]), whether or not anything calls it.
 *
 * What a method answers is autoreleased unless its name says otherwise (`alloc`, `new`, `copy`): it lives until the
 * autorelease pool it was made in is drained. So objects are used inside [ObjC.pooled], which drains its own, and only
 * Kotlin's values (text, paths, numbers) leave it. No method prism calls takes a block.
 */

/** An Objective-C object, by its address; nil for none. What is held here is not owned: nothing is released. */
@JvmInline
value class Id(
	val address: MemorySegment,
) {
	val isNil: Boolean get() = address.address() == 0L

	/** This, where it is not nil. */
	fun orNull(): Id? = takeUnless { isNil }

	companion object {
		val NIL = Id(MemorySegment.NULL)
	}
}

/** What a value is to Objective-C, as a method's type encoding says it: what a declaration is checked against. */
enum class Code {
	Object,
	Selector,
	Pointer,
	Int64,
	Int32,
	Bool,
	Float64,
	Float32,
	Struct,
	Void,
}

/** What a method returns: how it is laid out (nothing, for a method that returns nothing), and Kotlin's value of it. */
sealed interface Returned<T> {
	val layout: MemoryLayout?
	val code: Code

	fun out(raw: Any?): T
}

/** What a method takes, and may return: how it is laid out, and how Kotlin's value of it is passed. */
sealed interface Passed<T> : Returned<T> {
	override val layout: MemoryLayout

	fun into(value: T): Any
}

/** An object, or nil. */
data object Obj : Passed<Id> {
	override val layout: MemoryLayout get() = ValueLayout.ADDRESS
	override val code get() = Code.Object

	override fun into(value: Id): Any = value.address

	override fun out(raw: Any?): Id = Id(raw as MemorySegment)
}

/** A C pointer: text, bytes, somewhere for a method to write an answer. */
data object Ptr : Passed<MemorySegment> {
	override val layout: MemoryLayout get() = ValueLayout.ADDRESS
	override val code get() = Code.Pointer

	override fun into(value: MemorySegment): Any = value

	override fun out(raw: Any?): MemorySegment = raw as MemorySegment
}

/** A selector, by its name: what a menu's item is told to send. */
data object Sel : Passed<String> {
	override val layout: MemoryLayout get() = ValueLayout.ADDRESS
	override val code get() = Code.Selector

	override fun into(value: String): Any = ObjC.selector(value)

	override fun out(raw: Any?): String =
		throw UnsupportedOperationException("no method prism calls returns a selector")
}

/** `NSInteger`, `NSUInteger`, and the enumerations made of them. */
data object Int64 : Passed<Long> {
	override val layout: MemoryLayout get() = ValueLayout.JAVA_LONG
	override val code get() = Code.Int64

	override fun into(value: Long): Any = value

	override fun out(raw: Any?): Long = raw as Long
}

/** `int`: a `pid_t`. */
data object Int32 : Passed<Int> {
	override val layout: MemoryLayout get() = ValueLayout.JAVA_INT
	override val code get() = Code.Int32

	override fun into(value: Int): Any = value

	override fun out(raw: Any?): Int = raw as Int
}

/** `BOOL`, which on Apple silicon is C's `bool`. */
data object Bool : Passed<Boolean> {
	override val layout: MemoryLayout get() = ValueLayout.JAVA_BOOLEAN
	override val code get() = Code.Bool

	override fun into(value: Boolean): Any = value

	override fun out(raw: Any?): Boolean = raw as Boolean
}

/** `double`: a `CGFloat`, an `NSTimeInterval`. */
data object Float64 : Passed<Double> {
	override val layout: MemoryLayout get() = ValueLayout.JAVA_DOUBLE
	override val code get() = Code.Float64

	override fun into(value: Double): Any = value

	override fun out(raw: Any?): Double = raw as Double
}

/** `float`: the pressure of a pointer's press. */
data object Float32 : Passed<Float> {
	override val layout: MemoryLayout get() = ValueLayout.JAVA_FLOAT
	override val code get() = Code.Float32

	override fun into(value: Float): Any = value

	override fun out(raw: Any?): Float = raw as Float
}

/** A struct passed by value, laid out as [layout] says: an `NSRect`. */
data class Struct(
	override val layout: MemoryLayout,
) : Passed<MemorySegment> {
	override val code get() = Code.Struct

	override fun into(value: MemorySegment): Any = value

	override fun out(raw: Any?): MemorySegment = raw as MemorySegment
}

/** Nothing: what a method that returns nothing returns. */
data object NoValue : Returned<Unit> {
	override val layout: MemoryLayout? get() = null
	override val code get() = Code.Void

	override fun out(raw: Any?) = Unit
}

internal fun interface ObjcGetClass {
	operator fun invoke(name: MemorySegment): MemorySegment
}

internal fun interface SelRegisterName {
	operator fun invoke(name: MemorySegment): MemorySegment
}

internal fun interface AutoreleasePoolPush {
	operator fun invoke(): MemorySegment
}

internal fun interface AutoreleasePoolPop {
	operator fun invoke(pool: MemorySegment)
}

/** The Objective-C runtime. */
object ObjC {
	private val getClass by Frameworks.objc.binding<ObjcGetClass>("objc_getClass")
	private val registerName by Frameworks.objc.binding<SelRegisterName>("sel_registerName")
	private val poolPush by Frameworks.objc.binding<AutoreleasePoolPush>(
		"objc_autoreleasePoolPush",
	)
	private val poolPop by Frameworks.objc.binding<AutoreleasePoolPop>(
		"objc_autoreleasePoolPop",
	)
	private val msgSend: MemorySegment by lazy { Frameworks.objc.address("objc_msgSend") }

	/** A handle for each method signature met, made once. */
	private val handles = ConcurrentHashMap<FunctionDescriptor, MethodHandle>()
	private val selectors = ConcurrentHashMap<String, MemorySegment>()

	/** The class [name], of [framework] (loaded first, which is what makes its classes known); nil where there is none. */
	internal fun classNamed(name: String, framework: Library): Id {
		framework.load()

		return Arena.ofConfined().use { arena -> Id(getClass(arena.allocateFrom(name))) }
	}

	internal fun selector(name: String): MemorySegment =
		selectors.getOrPut(name) {
			Arena.ofConfined().use { arena ->
				registerName(arena.allocateFrom(name))
			}
		}

	/** [block] run in an autorelease pool of its own, drained as it ends, however it ends. */
	fun <T> pooled(block: () -> T): T {
		val pool = poolPush()

		try {
			return block()
		} finally {
			poolPop(pool)
		}
	}

	/**
	 * [selector] sent to [receiver] with [arguments], each as its [takes] says, answered as [returns] says. The one
	 * place a message is sent: its signature is made here from a declaration's own kinds, and from nothing else.
	 */
	internal fun <R> send(
		receiver: Id,
		selector: String,
		returns: Returned<R>,
		takes: List<Passed<*>>,
		arguments: List<Any>,
	): R {
		val layouts =
			listOf<MemoryLayout>(ValueLayout.ADDRESS, ValueLayout.ADDRESS) +
				takes.map { it.layout }

		val descriptor =
			returns.layout?.let { FunctionDescriptor.of(it, *layouts.toTypedArray()) }
				?: FunctionDescriptor.ofVoid(*layouts.toTypedArray())

		val handle =
			handles.getOrPut(descriptor) {
				Library.linker().downcallHandle(msgSend, descriptor)
			}

		return returns.out(
			handle.invokeWithArguments(listOf(receiver.address, selector(selector)) + arguments),
		)
	}
}

/*
 * Foundation's values, to and from Kotlin's.
 */

/** [text] as an NSString, autoreleased. */
fun nsString(text: String): Id =
	Arena.ofConfined().use { arena ->
		NSString.stringWithUTF8String(arena.allocateFrom(text))
	}

/** This NSString's text; null for nil. */
fun Id.text(): String? =
	orNull()
		?.let(NSString.utf8String::invoke)
		?.takeIf {
			it.address() != 0L
		}?.reinterpret(Long.MAX_VALUE)
		?.getString(0)

/** This NSArray's objects, in order; none for nil. */
fun Id.elements(): List<Id> =
	(0 until NSArray.count(this)).map {
		NSArray.objectAtIndex(this, it)
	}

/** This NSSet's objects, in no order; none for nil. */
fun Id.members(): List<Id> = NSSet.allObjects(this).elements()

/** What this NSDictionary holds for [key]; nil where nothing. */
operator fun Id.get(key: String): Id = NSDictionary.objectForKey(this, nsString(key))

/** Whether this is of [cls], or of a class made from it; false for nil. */
fun Id.isA(cls: ObjCClass): Boolean = NSObject.isKindOfClass(this, cls.cls)

/** [path] as a file URL (an NSURL), autoreleased. */
fun fileUrl(path: Path): Id = NSURL.fileURLWithPath(nsString(path.toString()))

/** This NSURL's path; null for nil, or a URL that is not a file's. */
fun Id.filePath(): Path? = orNull()?.let(NSURL.path::invoke)?.text()?.let(Path::of)

/** The object a framework exports as a constant (`NSURLContentTypeKey`): what its symbol holds. */
fun Library.constant(symbol: String): Id =
	Id(
		address(
			symbol,
		).reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0L),
	)

/** An NSError's `localizedDescription`, for what an out-parameter of one was given; null where none was. */
fun errorOf(out: MemorySegment): String? =
	Id(
		out.get(ValueLayout.ADDRESS, 0L),
	).orNull()?.let(NSError.localizedDescription::invoke)?.text()
