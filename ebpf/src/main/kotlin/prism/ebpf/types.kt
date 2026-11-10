package prism.ebpf

import kotlin.reflect.KProperty

/*
 * What a value in a program is. A type is a Kotlin type and a value at once: `U64` is an object, so an expression
 * is typed `Expr<U64>` and the compiler refuses arithmetic between widths, while the same object says how wide the
 * value is and how C spells it, which is what the emitter and the layouts read. [Expr] is invariant in its type,
 * which is what makes `Expr<U32> + Expr<U64>` a compile error rather than a widening nobody asked for.
 */

/** A type a value of a program may have. */
sealed interface BpfType {
	/** C's declaration of [name] at this type (an array's brackets follow the name, so this is not a prefix). */
	fun declare(name: String): String

	val sizeBytes: Int

	/** What C aligns a value of this type to, which the record layouts and the static assertions are built on. */
	val alignment: Int

	/** How C names the type on its own, in a cast. */
	val cast: String get() = declare("").trim()
}

/** A type whose values lie in memory the host reads: what a [Record] may hold. */
sealed interface Plain : BpfType

/** A whole number of a width and a signedness, both part of its type. */
sealed class Integral(
	val cName: String,
	final override val sizeBytes: Int,
	val signed: Boolean,
) : Plain {
	final override val alignment: Int get() = sizeBytes

	final override fun declare(name: String): String = "$cName $name"

	/** The suffix C needs on a literal of this width, so a 64-bit mask is not computed in 32 bits. */
	internal val literalSuffix: String
		get() =
			when {
				sizeBytes < 8 -> if (signed) "" else "u"
				signed -> "LL"
				else -> "ULL"
			}

	override fun toString(): String = cName
}

// Plain objects, not data objects: a data object would generate a toString of its own, which would hide the C
// name [Integral] gives it, and structural equality is identity for a singleton anyway.
object U8 : Integral("__u8", 1, signed = false)

object U16 : Integral("__u16", 2, signed = false)

object U32 : Integral("__u32", 4, signed = false)

object U64 : Integral("__u64", 8, signed = false)

object S32 : Integral("__s32", 4, signed = true)

object S64 : Integral("__s64", 8, signed = true)

/**
 * A pointer to a kernel structure, 64 bits wide. Its target is part of its type, so a `Ptr<Dentry>` cannot be read
 * through [Inode]'s fields; and no [Plain] type is a pointer, so a kernel address cannot be put in a record and
 * handed to the host, where it would mean nothing.
 */
data class Ptr<out S : KernelStruct<*>>(
	val target: S,
) : BpfType {
	override val sizeBytes: Int get() = 8
	override val alignment: Int get() = 8

	override fun declare(name: String): String = "${target.cDeclaration} *$name"

	override fun toString(): String = "${target.cDeclaration} *"
}

/** Bytes of kernel memory with no structure: what a name is read from, as a `Ptr<Bytes>`. */
object Bytes : KernelStruct<Bytes>("char") {
	override val cDeclaration: String get() = "const char"

	override val needsDeclaration: Boolean get() = false
}

/**
 * What a pointer points at when a program never reads through it: a parameter declared so that the ones after it are
 * in their places (`struct inode **delegated_inode`). Nothing can be read through it, since it declares no fields.
 */
object Opaque : KernelStruct<Opaque>("void") {
	override val cDeclaration: String get() = "void"

	override val needsDeclaration: Boolean get() = false
}

/**
 * A pointer to a record: what a ring buffer's reservation and a map's lookup give back, and what a hook's body
 * reaches a record's fields through. The record is part of the type, so one record's pointer cannot be read
 * through another's fields.
 */
data class RecordPtr<R : Record<R>>(
	val record: R,
) : BpfType {
	override val sizeBytes: Int get() = 8
	override val alignment: Int get() = 8

	override fun declare(name: String): String = "struct ${record.cName} *$name"

	override fun toString(): String = "struct ${record.cName} *"
}

/** A record of the program's own, on its stack: a key it looks something up by. */
data class RecordValue<R : Record<R>>(
	val record: R,
) : BpfType {
	override val sizeBytes: Int get() = record.layout.sizeBytes
	override val alignment: Int get() = record.layout.alignment

	override fun declare(name: String): String = "struct ${record.cName} $name"

	override fun toString(): String = "struct ${record.cName}"
}

/** A pointer to one plain value: what a per-CPU counter's lookup gives back. */
data class ValuePtr<T : Plain>(
	val target: T,
) : BpfType {
	override val sizeBytes: Int get() = 8
	override val alignment: Int get() = 8

	override fun declare(name: String): String = target.declare("*$name")

	override fun toString(): String = "$target *"
}

/**
 * [length] bytes in a record, holding text the kernel wrote with a terminating NUL: a file's name. Its length is
 * part of the field, so the helper that fills it is given that length and no other, and a name longer than the
 * field arrives cut rather than overflowing it.
 */
data class Chars(
	val length: Int,
) : Plain {
	init {
		require(length in 2..4096) { "a name field holds 2 to 4096 bytes, not $length" }
	}

	override val sizeBytes: Int get() = length
	override val alignment: Int get() = 1

	override fun declare(name: String): String = "char $name[$length]"

	override fun toString(): String = "char[$length]"
}

/**
 * What a kernel function gives back, as its own declaration says it. A `void` function's [NoReturn] has no value to
 * read, and an exit hook reads a [Returned] only of the function it is a hook of, so neither mistake compiles.
 */
sealed interface Returns

/** A `void` function: nothing comes back, so an exit hook of it has nothing to read. */
data object NoReturn : Returns

/** The value [function] gives back. */
class Returned<out F : Callable<*>, out T : BpfType> internal constructor(
	val function: F,
	val type: T,
) : Returns

/** `camelCase` as the kernel and C spell it: `dName` is `d_name`, `iSb` is `i_sb`, `DirectoryMade` is `directory_made`. */
internal fun snakeCase(name: String): String =
	name
		.flatMapIndexed { at, letter ->
			when {
				!letter.isUpperCase() -> listOf(letter)
				at == 0 -> listOf(letter.lowercaseChar())
				else -> listOf('_', letter.lowercaseChar())
			}
		}.joinToString("")

/**
 * A declaration being made by `by`: it takes the declaring property's name, turns it into the C one unless the
 * declaration names it itself, and registers the token with whatever is declaring it. Reading the property
 * afterwards gives that token, so the declaration is the one place a field or a parameter is named.
 */
class Declaring<D> internal constructor(
	private val named: (String) -> D,
) {
	operator fun provideDelegate(owner: Any?, property: KProperty<*>): Delegate<D> =
		Delegate(named(snakeCase(property.name)))
}

/** What a `by` declaration reads back: the token it registered. */
class Delegate<D> internal constructor(
	private val token: D,
) {
	operator fun getValue(owner: Any?, property: KProperty<*>): D = token
}
