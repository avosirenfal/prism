package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/*
 * Objective-C through its runtime's C functions, which FFM calls as it calls libc: a class by its name
 * (`objc_getClass`), a selector by its name (`sel_registerName`), and a message sent by calling `objc_msgSend` as the
 * method's own C function would be called, the receiver and the selector first. On Apple silicon that is how every
 * method is called, whatever it returns, so the one function, given each method's signature, reaches them all; a
 * message to nil does nothing and answers nil, zero or false. Foundation's objects are CoreFoundation's (an NSString
 * is a CFString), so the two are used together.
 *
 * What a method answers is autoreleased unless its name says otherwise (`alloc`, `new`, `copy`): it lives until the
 * autorelease pool it was made in is drained. So the objects are used inside [ObjC.pooled], which drains its own, and
 * only Kotlin's values (text, paths, numbers) leave it. None of this is called where blocks would be needed: the
 * methods prism uses take none, or take nil for one.
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

/** The Objective-C runtime. */
object ObjC {
	private val runtime = Library.dylib("/usr/lib/libobjc.A.dylib")
	private val getClass =
		runtime.function(
			"objc_getClass",
			FunctionDescriptor.of(ADDRESS, ADDRESS),
		)
	private val registerName =
		runtime.function(
			"sel_registerName",
			FunctionDescriptor.of(ADDRESS, ADDRESS),
		)
	private val poolPush =
		runtime.function(
			"objc_autoreleasePoolPush",
			FunctionDescriptor.of(ADDRESS),
		)
	private val poolPop =
		runtime.function(
			"objc_autoreleasePoolPop",
			FunctionDescriptor.ofVoid(ADDRESS),
		)
	private val msgSend: MemorySegment by lazy { runtime.address("objc_msgSend") }

	/** A handle for each method signature met, made once. */
	private val handles = ConcurrentHashMap<FunctionDescriptor, MethodHandle>()
	private val selectors = ConcurrentHashMap<String, MemorySegment>()
	private val classes = ConcurrentHashMap<String, Id>()

	/** The class [name], of [framework] (loaded first, which is what makes its classes known); nil where there is none. */
	fun cls(name: String, framework: Library = Frameworks.foundation): Id =
		classes.getOrPut(name) {
			framework.load()
			Arena.ofConfined().use { arena ->
				Id(getClass.handle.invokeExact(arena.allocateFrom(name)) as MemorySegment)
			}
		}

	private fun selector(name: String): MemorySegment =
		selectors.getOrPut(name) {
			Arena.ofConfined().use { arena ->
				registerName.handle.invokeExact(arena.allocateFrom(name)) as MemorySegment
			}
		}

	/** [block] run in an autorelease pool of its own, drained as it ends, however it ends. */
	fun <T> pooled(block: () -> T): T {
		val pool = poolPush.handle.invokeExact() as MemorySegment

		try {
			return block()
		} finally {
			poolPop.handle.invokeExact(pool)
		}
	}

	/** [selector] sent to [receiver] with [arguments], answered as [returns] lays it out (none, for a void method). */
	internal fun send(
		returns: MemoryLayout?,
		receiver: Id,
		selector: String,
		arguments: Array<out Any>,
	): Any? {
		val layouts = listOf<MemoryLayout>(ADDRESS, ADDRESS) + arguments.map(::layoutOf)
		val descriptor =
			if (returns == null)
				FunctionDescriptor.ofVoid(*layouts.toTypedArray())
			else
				FunctionDescriptor.of(returns, *layouts.toTypedArray())

		val handle =
			handles.getOrPut(descriptor) {
				Library.linker.downcallHandle(msgSend, descriptor)
			}

		return handle.invokeWithArguments(
			listOf(receiver.address, selector(selector)) + arguments.map(::valueOf),
		)
	}

	/** How an argument is passed: an object or a pointer as an address, `NSInteger` as a long, `BOOL` as a bool. */
	private fun layoutOf(argument: Any): MemoryLayout =
		when (argument) {
			is Id, is MemorySegment -> ADDRESS
			is Long -> JAVA_LONG
			is Int -> JAVA_INT
			is Boolean -> JAVA_BOOLEAN
			is Double -> JAVA_DOUBLE
			else -> throw IllegalArgumentException(
				"no Objective-C argument is a ${argument::class.simpleName}",
			)
		}

	private fun valueOf(argument: Any): Any =
		if (argument is Id)
			argument.address
		else
			argument
}

/** [selector] sent to this, answered with an object (nil where none). */
fun Id.send(selector: String, vararg arguments: Any): Id =
	Id(ObjC.send(ADDRESS, this, selector, arguments) as MemorySegment)

/** [selector] sent to this, answered with a C pointer (`const char *`, `const void *`). */
fun Id.pointer(selector: String, vararg arguments: Any): MemorySegment =
	ObjC.send(ADDRESS, this, selector, arguments) as MemorySegment

/** [selector] sent to this, answered with an `NSInteger` or `NSUInteger`. */
fun Id.integer(selector: String, vararg arguments: Any): Long =
	ObjC.send(JAVA_LONG, this, selector, arguments) as Long

/** [selector] sent to this, answered with an `int` (`pid_t`). */
fun Id.int(selector: String, vararg arguments: Any): Int =
	ObjC.send(JAVA_INT, this, selector, arguments) as Int

/** [selector] sent to this, answered with a `BOOL`. */
fun Id.bool(selector: String, vararg arguments: Any): Boolean =
	ObjC.send(JAVA_BOOLEAN, this, selector, arguments) as Boolean

/** [selector] sent to this, answered with a `double` (`CGFloat`, `NSTimeInterval`). */
fun Id.double(selector: String, vararg arguments: Any): Double =
	ObjC.send(JAVA_DOUBLE, this, selector, arguments) as Double

/** [selector] sent to this, for what it does. */
fun Id.perform(selector: String, vararg arguments: Any) {
	ObjC.send(null, this, selector, arguments)
}

/** The frameworks prism's classes are in, each loaded the first time one of its classes is asked for. */
object Frameworks {
	val foundation = Library.framework("Foundation")
	val appKit = Library.framework("AppKit")
	val coreFoundation = Library.framework("CoreFoundation")
	val coreServices = Library.framework("CoreServices")
	val uniformTypes = Library.framework("UniformTypeIdentifiers")
}

/*
 * Foundation's values, to and from Kotlin's.
 */

/** [text] as an NSString, autoreleased. */
fun nsString(text: String): Id =
	Arena.ofConfined().use { arena ->
		ObjC.cls("NSString").send("stringWithUTF8String:", arena.allocateFrom(text))
	}

/** This NSString's text; null for nil. */
fun Id.text(): String? =
	orNull()
		?.pointer("UTF8String")
		?.takeIf {
			it.address() != 0L
		}?.reinterpret(Long.MAX_VALUE)
		?.getString(0)

/** This NSArray's objects, in order; none for nil. */
fun Id.elements(): List<Id> =
	(0 until integer("count")).map {
		send("objectAtIndex:", it)
	}

/** This NSSet's objects, in no order; none for nil. */
fun Id.members(): List<Id> = send("allObjects").elements()

/** What this NSDictionary holds for [key]; nil where nothing. */
operator fun Id.get(key: String): Id = send("objectForKey:", nsString(key))

/** Whether this is of the class [name], or one made from it. */
fun Id.isA(name: String): Boolean = bool("isKindOfClass:", ObjC.cls(name))

/** [path] as a file URL (an NSURL), autoreleased. */
fun fileUrl(path: Path): Id =
	ObjC.cls("NSURL").send("fileURLWithPath:", nsString(path.toString()))

/** This NSURL's path; null for nil, or a URL that is not a file's. */
fun Id.filePath(): Path? = orNull()?.send("path")?.text()?.let(Path::of)

/** The NSString a framework exports as a constant (`NSURLContentTypeKey`): the object its symbol holds. */
fun Library.constant(symbol: String): Id =
	Id(address(symbol).reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0L))

/** An NSError's `localizedDescription`, for what an out-parameter of one was given; null where none was. */
fun errorOf(out: MemorySegment): String? =
	Id(out.get(ADDRESS, 0L)).orNull()?.send("localizedDescription")?.text()
