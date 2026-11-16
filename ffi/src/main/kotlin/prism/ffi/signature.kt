package prism.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KClass

/*
 * A native function's signature, said once.
 *
 * The FFM API has a function described twice: to the linker, as a `FunctionDescriptor` of layouts, and at each call,
 * by the Java types of what is passed and expected back, which `invokeExact` compares with the first only as the call
 * runs. A call that passes a struct where a long was declared, or is compiled as returning a value where nothing is
 * returned, compiles, and fails the first time that line is reached.
 *
 * Here a function is an interface with one method, and that method is the signature:
 *
 *     fun interface Free { operator fun invoke(pointer: MemorySegment) }
 *     val free: Free = libc.bind("free")
 *     free(list)
 *
 * The descriptor is made from the method's own parameter and return types ([Signature.of]), and the function is
 * called through the interface, so what a call passes and expects is checked by the compiler against the very
 * declaration the linker was given. Primitives stay primitives: nothing is boxed on the way.
 *
 * What a Java type cannot say alone is said beside it: a struct passed by value ([ByValue], since a `MemorySegment`
 * is a pointer otherwise), a call whose errno is wanted ([KeepsErrno]), and where a C function's `...` begins ([Variadic]).
 */

/** A struct's layout, for a parameter passed by value: an object that says it. */
interface StructType {
	val layout: MemoryLayout
}

/** A `MemorySegment` parameter that is a struct passed by value, laid out as [type] says, and not a pointer. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ByValue(
	val type: KClass<out StructType>,
)

/** A function whose errno is kept as it returns ([lastErrno]), before the JVM can change it. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class KeepsErrno

/** A C function declared with `...`: its parameter at index [from] is the first of the variable ones. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Variadic(
	val from: Int,
)

/** What an interface's one method says of a native function: its [method], and what the linker is told. */
class Signature private constructor(
	val method: Method,
	val descriptor: FunctionDescriptor,
	val capturesErrno: Boolean,
	val options: List<Linker.Option>,
) {
	companion object {
		/** The signature [declared] declares: an interface with exactly one abstract method, of types C has. */
		fun of(declared: Class<*>): Signature {
			require(declared.isInterface) { "${declared.name} is not an interface" }
			// The proxy that calls the function is made in a class of its own, which must be able to see the interface.
			require(Modifier.isPublic(declared.modifiers)) {
				"${declared.name} must be public or internal for a native function to be bound to it"
			}

			val method =
				declared.methods.singleOrNull { Modifier.isAbstract(it.modifiers) }
					?: throw IllegalArgumentException(
						"${declared.name} must have exactly one abstract method",
					)

			val parameters =
				method.parameters.map { parameter ->
					parameter.getAnnotation(ByValue::class.java)?.let { byValue ->
						require(parameter.type == MemorySegment::class.java) {
							"${declared.name}: a struct by value is a MemorySegment"
						}
						layoutOf(byValue.type)
					} ?: layoutOf(parameter.type, declared)
				}

			val descriptor =
				if (method.returnType == Void.TYPE)
					FunctionDescriptor.ofVoid(*parameters.toTypedArray())
				else
					FunctionDescriptor.of(
						layoutOf(method.returnType, declared),
						*parameters.toTypedArray(),
					)

			val errno = method.isAnnotationPresent(KeepsErrno::class.java)

			return Signature(
				method,
				descriptor,
				errno,
				listOfNotNull(
					CallState.option.takeIf { errno },
					method.getAnnotation(Variadic::class.java)?.let {
						Linker.Option.firstVariadicArg(it.from)
					},
				),
			)
		}

		/** The layout an object that is a [StructType] says: found as Kotlin compiles an object, by its `INSTANCE`. */
		private fun layoutOf(type: KClass<out StructType>): MemoryLayout =
			try {
				(type.java.getField("INSTANCE").get(null) as StructType).layout
			} catch (notAnObject: NoSuchFieldException) {
				throw IllegalArgumentException("${type.java.name} must be an object")
			}

		/** C's layout for a Java type: the integers and floats as they are, and a `MemorySegment` as a pointer. */
		private fun layoutOf(type: Class<*>, declared: Class<*>): MemoryLayout =
			when (type) {
				Int::class.javaPrimitiveType -> ValueLayout.JAVA_INT
				Long::class.javaPrimitiveType -> ValueLayout.JAVA_LONG
				Short::class.javaPrimitiveType -> ValueLayout.JAVA_SHORT
				Byte::class.javaPrimitiveType -> ValueLayout.JAVA_BYTE
				Boolean::class.javaPrimitiveType -> ValueLayout.JAVA_BOOLEAN
				Double::class.javaPrimitiveType -> ValueLayout.JAVA_DOUBLE
				Float::class.javaPrimitiveType -> ValueLayout.JAVA_FLOAT
				MemorySegment::class.java -> ValueLayout.ADDRESS
				else -> throw IllegalArgumentException(
					"${declared.name}: ${type.name} is no type of C's (a struct returned by value is not supported)",
				)
			}
	}
}

/**
 * The signature of every interface declared beside [anchor] (in its package, among its module's classes) that is a C
 * function's: one abstract method, of C's types alone. What a test makes of a module's bindings where the library
 * they are of is not installed, or is another platform's: each declaration is read as binding it would read it, so
 * one that is not a signature fails there, whether or not the function can be called.
 */
fun signaturesBeside(anchor: Class<*>): List<Signature> {
	val folder =
		java.nio.file.Path
			.of(
				anchor.protectionDomain.codeSource.location
					.toURI(),
			).resolve(anchor.packageName.replace('.', '/'))

	val c = setOf<Class<*>>(MemorySegment::class.java, Void.TYPE) + PRIMITIVES

	return java.nio.file.Files.list(folder).use { files ->
		files
			.map { it.fileName.toString() }
			.filter { it.endsWith(".class") }
			.map {
				Class.forName(
					"${anchor.packageName}.${it.removeSuffix(".class")}",
					false,
					anchor.classLoader,
				)
			}.filter { declared ->
				declared.isInterface &&
					declared.methods.singleOrNull { Modifier.isAbstract(it.modifiers) }?.let { method ->
						method.returnType in c && method.parameterTypes.all { it in c }
					} == true
			}.map(Signature::of)
			.toList()
	}
}

private val PRIMITIVES: Set<Class<*>> =
	setOfNotNull(
		Int::class.javaPrimitiveType,
		Long::class.javaPrimitiveType,
		Short::class.javaPrimitiveType,
		Byte::class.javaPrimitiveType,
		Boolean::class.javaPrimitiveType,
		Double::class.javaPrimitiveType,
		Float::class.javaPrimitiveType,
	)
