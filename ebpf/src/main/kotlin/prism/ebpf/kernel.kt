package prism.ebpf

/*
 * The kernel as a program may reach it: the structures whose fields it reads, and the functions and tracepoints it
 * hooks. Every one is declared here in Kotlin and nowhere else: the emitted C declares exactly these, the check
 * against the running kernel's own BTF (check.kt) reads exactly these, and an access the declaration does not hold
 * does not compile. A declaration names a field or a parameter once, as a Kotlin property, and the C name follows
 * from it (`dName` is `d_name`).
 *
 * Only declared fields are reachable, and only of the structure the pointer points at: a `Ptr<Dentry>` read through
 * [Inode]'s fields is a compile error, because a field carries the structure that declared it in its type.
 */

/** Something with parameters a hook's body reads: a kernel function, or a tracepoint. */
sealed class Callable<Self : Callable<Self>>(
	/** The name the kernel knows it by, which the hook's section names. */
	val cName: String,
) {
	private val declared = ArrayList<Parameter<Self, *>>()

	/** Its parameters, in the order declared, which is the order the kernel passes them. */
	val parameters: List<Parameter<Self, *>> get() = declared.toList()

	/**
	 * One parameter, of [type]. Declared with `by`, so its name is the property's; the kernel passes them in the
	 * order declared, which is the order they must be declared in.
	 */
	protected fun <T : BpfType> param(type: T): Declaring<Parameter<Self, T>> =
		Declaring { name ->
			Parameter(self(), declared.size, name, type).also { declared += it }
		}

	/** Its value, for a function that gives one back: an exit hook of this function reads it, and nothing else can. */
	protected fun <T : BpfType> returning(type: T): Returned<Self, T> =
		Returned(self(), type)

	@Suppress("UNCHECKED_CAST")
	private fun self(): Self = this as Self

	override fun toString(): String = cName
}

/**
 * One parameter of [function]: its place among them, its name and its type. A hook's body reads it with
 * `parameter.value()`, which is a member of the body of a hook of that same function, so a parameter of another
 * function cannot be read by mistake.
 */
class Parameter<out F : Callable<*>, out T : BpfType> internal constructor(
	val function: F,
	val index: Int,
	val cName: String,
	val type: T,
) {
	override fun toString(): String = "${function.cName}.$cName"
}

/**
 * A kernel function a program hooks on entry ([Program.fentry]) or on exit ([Program.fexit]): its name, its
 * parameters in order, and what it gives back.
 */
abstract class KernelFunction<Self : KernelFunction<Self>>(
	cName: String,
) : Callable<Self>(cName) {
	/** What it gives back: [returning] for a value, [NoReturn] for a `void` function. */
	abstract val returns: Returns
}

/**
 * A kernel tracepoint a program hooks through BTF (`tp_btf`): its name as `bpf_trace_<name>` has it, and the
 * arguments it passes. The kernel passes them as a row of words, as it does a function's.
 */
abstract class KernelTracepoint<Self : KernelTracepoint<Self>>(
	cName: String,
) : Callable<Self>(cName)

/**
 * A system call's entry or exit tracepoint (`tp/syscalls/sys_enter_openat`), whose context is a record the kernel
 * fills rather than a row of arguments: the common header, the call's number, then a word per argument. A program
 * hooks one where it wants the arguments as the caller gave them, before the kernel has looked at them.
 */
abstract class SyscallTracepoint<Self : SyscallTracepoint<Self>>(
	/** The tracepoint's own name: `sys_enter_openat`. */
	cName: String,
) : Callable<Self>(cName) {
	companion object {
		/** Where the arguments start in the context: `struct trace_entry` (8), then the call's number (8, padded). */
		const val ARGUMENTS_AT = 16
	}
}

/**
 * One field of a kernel structure. It carries the structure that declared it, so it can only be read through a
 * pointer to that structure; and how it is reached, which is a load for a value and an address for a structure
 * embedded in another ([KernelStruct.embedded]), as C's `&x->field`.
 */
class KernelField<out S : KernelStruct<*>, out T : BpfType> internal constructor(
	val owner: S,
	val cName: String,
	val type: T,
	/** Whether reading it loads the field or takes its address (an embedded structure). */
	internal val address: Boolean,
) {
	/**
	 * How the emitted C declares it. An embedded structure is declared as the structure itself, which is what the
	 * kernel holds there and what CO-RE checks the access against; reading it gives its address, so its own fields
	 * are reached exactly as any other structure's.
	 */
	internal fun declaration(): String =
		if (address)
			"${(type as Ptr<*>).target.cDeclaration} $cName"
		else
			type.declare(cName)

	override fun toString(): String = "${owner.cName}.$cName"
}

/**
 * A kernel structure, declared with the fields a program reads and no others. The emitted C declares the same
 * subset with `preserve_access_index`, so every access carries a CO-RE relocation and the kernel's own offsets are
 * used at load time: what is declared here is a set of names, never a layout, and a kernel whose layout differs
 * needs no change here. What the kernel really holds is checked against these declarations before a load
 * ([prism.ebpf.kernelMismatches]), so a renamed or retyped field is a named mismatch rather than a wrong read.
 */
abstract class KernelStruct<Self : KernelStruct<Self>>(
	val cName: String,
) {
	private val declared = ArrayList<KernelField<Self, *>>()

	val fields: List<KernelField<Self, *>> get() = declared.toList()

	/** How C names the type: `struct inode`, or the name alone for one that is not a structure ([Bytes]). */
	open val cDeclaration: String get() = "struct $cName"

	/** Whether the emitted C declares it: a structure of the kernel's does, a plain type such as [Bytes] does not. */
	open val needsDeclaration: Boolean get() = true

	/** A field holding a value, or a pointer to another structure. */
	protected fun <T : BpfType> field(type: T): Declaring<KernelField<Self, T>> =
		Declaring { name -> declare(name, type, address = false) }

	/**
	 * A field that is itself a structure, laid out inside this one (`dentry.d_name`, a `struct qstr`): reading it
	 * gives a pointer to it, so its own fields are read exactly as any other structure's.
	 */
	protected fun <S : KernelStruct<S>> embedded(
		struct: S,
	): Declaring<KernelField<Self, Ptr<S>>> =
		Declaring { name -> declare(name, Ptr(struct), address = true) }

	private fun <T : BpfType> declare(
		name: String,
		type: T,
		address: Boolean,
	): KernelField<Self, T> {
		@Suppress("UNCHECKED_CAST")
		val field = KernelField(this as Self, name, type, address)

		declared += field

		return field
	}

	override fun toString(): String = cDeclaration
}
