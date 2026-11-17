package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import prism.ffi.Library

/*
 * Methods as values. A class prism sends messages to is an object ([ObjCClass]), and each method it is sent is a value
 * of that object, made from its selector and the kinds of what it takes and returns. A message with one argument is a
 * [Message1], and calling it takes the receiver and one value of that argument's Kotlin type; a class's own method
 * ([Static0]...) is called with no receiver, since its class is its receiver.
 */

/** A method as it is declared here, whatever it takes: what the runtime's own account of it is compared with. */
sealed class Declared(
	val owner: ObjCClass,
	val selector: String,
	/** Whether it is the class's own method (`+`), and not its instances' (`-`). */
	val ofClass: Boolean,
	val returns: Returned<*>,
	val takes: List<Passed<*>>,
) {
	init {
		require(selector.count { it == ':' } == takes.size) {
			"${owner.name} $selector: a selector has a colon for each argument, and ${takes.size} are declared"
		}
	}

	/** The kinds of what it returns and takes, as declared: the receiver and the selector, then its arguments. */
	val declaredCodes: List<Code> get() =
		listOf(returns.code, Code.Object, Code.Selector) +
			takes.map { it.code }

	/**
	 * How this differs from the method the runtime has, on a Mac: null where they agree. The class is not there, it
	 * has no such method, or the method's own type encoding says other kinds than are declared.
	 */
	fun mismatch(): String? {
		val cls = owner.cls.orNull() ?: return "there is no class ${owner.name}"
		val method =
			ObjCRuntime.method(cls, selector, ofClass)
				?: return "${owner.name} has no ${if (ofClass) "+" else "-"}$selector"
		val actual = Encoding.codes(ObjCRuntime.encodingOf(method))

		return "${owner.name} $selector is declared $declaredCodes, and is $actual".takeIf {
			actual !=
				declaredCodes
		}
	}

	protected fun <R> send(receiver: Id, returns: Returned<R>, vararg arguments: Any): R =
		ObjC.send(receiver, selector, returns, takes, arguments.toList())

	override fun toString(): String = "${if (ofClass) "+" else "-"}[${owner.name} $selector]"
}

class Message0<R>(
	owner: ObjCClass,
	selector: String,
	ofClass: Boolean,
	private val result: Returned<R>,
) : Declared(owner, selector, ofClass, result, emptyList()) {
	operator fun invoke(receiver: Id): R = send(receiver, result)
}

class Message1<A, R>(
	owner: ObjCClass,
	selector: String,
	ofClass: Boolean,
	private val a: Passed<A>,
	private val result: Returned<R>,
) : Declared(owner, selector, ofClass, result, listOf(a)) {
	operator fun invoke(receiver: Id, first: A): R = send(receiver, result, a.into(first))
}

class Message2<A, B, R>(
	owner: ObjCClass,
	selector: String,
	ofClass: Boolean,
	private val a: Passed<A>,
	private val b: Passed<B>,
	private val result: Returned<R>,
) : Declared(owner, selector, ofClass, result, listOf(a, b)) {
	operator fun invoke(receiver: Id, first: A, second: B): R =
		send(receiver, result, a.into(first), b.into(second))
}

class Message3<A, B, C, R>(
	owner: ObjCClass,
	selector: String,
	ofClass: Boolean,
	private val a: Passed<A>,
	private val b: Passed<B>,
	private val c: Passed<C>,
	private val result: Returned<R>,
) : Declared(owner, selector, ofClass, result, listOf(a, b, c)) {
	operator fun invoke(receiver: Id, first: A, second: B, third: C): R =
		send(receiver, result, a.into(first), b.into(second), c.into(third))
}

class Message4<A, B, C, D, R>(
	owner: ObjCClass,
	selector: String,
	ofClass: Boolean,
	private val a: Passed<A>,
	private val b: Passed<B>,
	private val c: Passed<C>,
	private val d: Passed<D>,
	private val result: Returned<R>,
) : Declared(owner, selector, ofClass, result, listOf(a, b, c, d)) {
	operator fun invoke(
		receiver: Id,
		first: A,
		second: B,
		third: C,
		fourth: D,
	): R =
		send(receiver, result, a.into(first), b.into(second), c.into(third), d.into(fourth))
}

/**
 * A class's own method of more arguments than the others here are written for: each a value with the kind it is to
 * be passed as, so that a value of another type is not one that can be written.
 */
class StaticOf<R>(
	owner: ObjCClass,
	selector: String,
	takes: List<Passed<*>>,
	private val result: Returned<R>,
) : Declared(owner, selector, true, result, takes) {
	/** A value, as the kind it is passed as. */
	class Given<T>(
		val kind: Passed<T>,
		val value: T,
	) {
		internal fun raw(): Any = kind.into(value)
	}

	operator fun invoke(vararg given: Given<*>): R {
		require(given.map { it.kind } == takes) { "$this takes $takes" }

		return send(owner.cls, result, *given.map { it.raw() }.toTypedArray())
	}
}

/** [value], to be passed as [this] kind. */
infix fun <T> Passed<T>.of(value: T): StaticOf.Given<T> = StaticOf.Given(this, value)

/** A class's own method of no arguments: called of its class, or [on] a class made from it. */
class Static0<R>(
	private val message: Message0<R>,
) {
	operator fun invoke(): R = message(message.owner.cls)

	fun on(cls: Id): R = message(cls)
}

class Static1<A, R>(
	private val message: Message1<A, R>,
) {
	operator fun invoke(first: A): R = message(message.owner.cls, first)
}

class Static2<A, B, R>(
	private val message: Message2<A, B, R>,
) {
	operator fun invoke(first: A, second: B): R = message(message.owner.cls, first, second)
}

/**
 * A class of Objective-C's that prism sends messages to: [name], in [framework]. Its methods are declared as values of
 * the object that is it, each kept in [declared] as it is made.
 */
sealed class ObjCClass(
	val name: String,
	private val framework: Library = Frameworks.foundation,
) {
	/** The class itself, found the first time it is asked for; nil where the system has none of the name. */
	val cls: Id by lazy { ObjC.classNamed(name, framework) }

	private val made = ArrayList<Declared>()

	/** Every method declared of it. */
	val declared: List<Declared> get() = made.toList()

	private fun <M : Declared> kept(message: M): M = message.also(made::add)

	protected fun <R> message(selector: String, returns: Returned<R>) =
		kept(Message0(this, selector, false, returns))

	protected fun <A, R> message(selector: String, a: Passed<A>, returns: Returned<R>) =
		kept(Message1(this, selector, false, a, returns))

	protected fun <A, B, R> message(
		selector: String,
		a: Passed<A>,
		b: Passed<B>,
		returns: Returned<R>,
	) = kept(Message2(this, selector, false, a, b, returns))

	protected fun <A, B, C, R> message(
		selector: String,
		a: Passed<A>,
		b: Passed<B>,
		c: Passed<C>,
		returns: Returned<R>,
	) = kept(Message3(this, selector, false, a, b, c, returns))

	protected fun <A, B, C, D, R> message(
		selector: String,
		a: Passed<A>,
		b: Passed<B>,
		c: Passed<C>,
		d: Passed<D>,
		returns: Returned<R>,
	) = kept(Message4(this, selector, false, a, b, c, d, returns))

	protected fun <R> staticOf(
		selector: String,
		takes: List<Passed<*>>,
		returns: Returned<R>,
	) = kept(StaticOf(this, selector, takes, returns))

	protected fun <R> static(selector: String, returns: Returned<R>) =
		Static0(kept(Message0(this, selector, true, returns)))

	protected fun <A, R> static(selector: String, a: Passed<A>, returns: Returned<R>) =
		Static1(kept(Message1(this, selector, true, a, returns)))

	protected fun <A, B, R> static(
		selector: String,
		a: Passed<A>,
		b: Passed<B>,
		returns: Returned<R>,
	) = Static2(kept(Message2(this, selector, true, a, b, returns)))

	companion object {
		/** Every class declared here: the objects that are [ObjCClass]es, all of them, as the compiler lists them. */
		fun all(): List<ObjCClass> =
			ObjCClass::class.java.permittedSubclasses.map {
				it.getField("INSTANCE").get(null) as ObjCClass
			}
	}
}

/** A method's type encoding, as the runtime writes one (`B40@0:8@16^@24^@32`), read into the kinds it names. */
object Encoding {
	/** The kinds [encoding] names: what is returned, then each thing taken, the receiver and the selector first. */
	fun codes(encoding: String): List<Code> {
		val codes = ArrayList<Code>()
		var at = 0

		while (at < encoding.length) {
			val (code, next) = one(encoding, at)

			code?.let(codes::add)
			at = next
		}

		return codes
	}

	/** The kind that starts at [from] (none, for a qualifier or an offset), and where the next thing starts. */
	private fun one(text: String, from: Int): Pair<Code?, Int> =
		when (val char = text[from]) {
			// How far into the arguments each is, and qualifiers (const, in, out, ...): nothing of the kind itself.
			in '0'..'9', 'r', 'n', 'N', 'o', 'O', 'R', 'V', 'A', 'j', '+', '-' -> null to from + 1
			'@' ->
				Code.Object to
					when (text.getOrNull(from + 1)) {
						// A block; and an object whose class is named.
						'?' -> from + 2
						'"' -> text.indexOf('"', from + 2) + 1
						else -> from + 1
					}
			'#' -> Code.Object to from + 1
			':' -> Code.Selector to from + 1
			'*' -> Code.Pointer to from + 1
			// A pointer to something, which is read past, whatever it is.
			'^' -> Code.Pointer to one(text, from + 1).second
			'q', 'Q', 'l', 'L' -> Code.Int64 to from + 1
			'i', 'I' -> Code.Int32 to from + 1
			'B' -> Code.Bool to from + 1
			'd' -> Code.Float64 to from + 1
			'f' -> Code.Float32 to from + 1
			'v' -> Code.Void to from + 1
			'{', '(' -> Code.Struct to closing(text, from) + 1
			'[' -> Code.Pointer to closing(text, from) + 1
			'?' -> Code.Pointer to from + 1
			else -> throw IllegalArgumentException(
				"“$char” in the encoding “$text” is of no kind known here",
			)
		}

	/** Where the bracket opened at [from] is closed, those opened inside it closed first. */
	private fun closing(text: String, from: Int): Int {
		val open = text[from]
		val close =
			when (open) {
				'{' -> '}'
				'(' -> ')'
				else -> ']'
			}
		var depth = 0

		for (at in from until text.length)
			when (text[at]) {
				open -> depth += 1
				close -> {
					depth -= 1
					if (depth == 0)
						return at
				}
			}

		throw IllegalArgumentException("“$text” opens a bracket it does not close")
	}
}

internal fun interface ClassGetMethod {
	operator fun invoke(cls: MemorySegment, selector: MemorySegment): MemorySegment
}

internal fun interface MethodGetTypeEncoding {
	operator fun invoke(method: MemorySegment): MemorySegment
}

internal fun interface ObjcLookUpClass {
	operator fun invoke(name: MemorySegment): MemorySegment
}

internal fun interface ObjcAllocateClassPair {
	operator fun invoke(
		superclass: MemorySegment,
		name: MemorySegment,
		extraBytes: Long,
	): MemorySegment
}

internal fun interface ObjcRegisterClassPair {
	operator fun invoke(cls: MemorySegment)
}

internal fun interface ObjectGetClass {
	operator fun invoke(instance: MemorySegment): MemorySegment
}

internal fun interface ClassAddMethod {
	operator fun invoke(
		cls: MemorySegment,
		selector: MemorySegment,
		implementation: MemorySegment,
		types: MemorySegment,
	): Boolean
}

/** A method of a class made here: what it is called by, how it is encoded, and the C function that is it. */
internal class Implemented(
	val selector: String,
	val encoding: String,
	val function: MemorySegment,
)

/** The runtime's functions for asking of methods, and for a class made while the process runs. */
internal object ObjCRuntime {
	private val instanceMethod by Frameworks.objc.binding<ClassGetMethod>(
		"class_getInstanceMethod",
	)
	private val classMethod by Frameworks.objc.binding<ClassGetMethod>(
		"class_getClassMethod",
	)
	private val typeEncoding by Frameworks.objc.binding<MethodGetTypeEncoding>(
		"method_getTypeEncoding",
	)
	private val lookUpClass by Frameworks.objc.binding<ObjcLookUpClass>("objc_lookUpClass")
	private val allocatePair by Frameworks.objc.binding<ObjcAllocateClassPair>(
		"objc_allocateClassPair",
	)
	private val registerPair by Frameworks.objc.binding<ObjcRegisterClassPair>(
		"objc_registerClassPair",
	)
	private val addMethod by Frameworks.objc.binding<ClassAddMethod>("class_addMethod")
	private val objectGetClass by Frameworks.objc.binding<ObjectGetClass>("object_getClass")

	/** The method [selector] of [cls], its own ([ofClass]) or its instances'; null where it has none. */
	fun method(cls: Id, selector: String, ofClass: Boolean): MemorySegment? =
		(if (ofClass) classMethod else instanceMethod)(
			cls.address,
			ObjC.selector(selector),
		).takeIf {
			it.address() !=
				0L
		}

	/** The class [instance] is of. */
	fun classOf(instance: Id): Id = Id(objectGetClass(instance.address))

	/** [method] given to [cls], where it has none of that selector itself; whether it was. */
	fun add(cls: Id, method: Implemented): Boolean =
		Arena.ofConfined().use { arena ->
			addMethod(
				cls.address,
				ObjC.selector(method.selector),
				method.function,
				arena.allocateFrom(method.encoding),
			)
		}

	fun encodingOf(method: MemorySegment): String =
		typeEncoding(method).reinterpret(Long.MAX_VALUE).getString(0)

	/**
	 * The class [name], made from [superclass] with [methods] in place of its superclass's of those selectors, and
	 * registered: made once, and found the next time. This is the runtime's own way of making a subclass, as the
	 * compiler makes one; the class has no fields of its own.
	 */
	fun subclass(name: String, superclass: ObjCClass, methods: List<Implemented>): Id =
		Arena.ofConfined().use { arena ->
			val named = arena.allocateFrom(name)
			val known = lookUpClass(named)

			if (known.address() != 0L)
				return Id(known)

			val made = allocatePair(superclass.cls.address, named, 0L)

			check(made.address() != 0L) { "the runtime would not make the class $name" }
			for (method in methods)
				check(
					addMethod(
						made,
						ObjC.selector(method.selector),
						method.function,
						arena.allocateFrom(method.encoding),
					),
				) {
					"$name could not be given ${method.selector}"
				}
			registerPair(made)
			Id(made)
		}
}
