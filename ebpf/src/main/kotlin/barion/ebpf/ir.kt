package barion.ebpf

/*
 * What a program says, as immutable data: expressions, the conditions they are compared into, and the statements a
 * hook's body is. Nothing here is written by hand; the vocabulary in dsl.kt builds it, and emit.kt turns it into C.
 * Keeping it data is what lets a program be checked (check.kt) and compared in a test without a kernel.
 *
 * [Expr] is invariant in its type on purpose: it is what makes `Expr<U32> + Expr<U64>` a compile error instead of a
 * widening nobody asked for, and what keeps a `Ptr<Dentry>` out of a field declared for an inode's number.
 */

/** A value a program computes. */
sealed class Expr<T : BpfType>(
	val type: T,
)

/** A number written in the program, under the name the emitted C gives it where it has one (an enum's entry). */
class Const<T : Integral> internal constructor(
	type: T,
	val value: Long,
	val label: String? = null,
) : Expr<T>(type)

/** A local, a parameter the hook read, or a pointer a lookup gave: something with a name in the emitted C. */
class Named<T : BpfType> internal constructor(
	type: T,
	val cName: String,
) : Expr<T>(type)

/**
 * One word of the hook's context: an argument the kernel passed, or the value the hooked function gave back. A hook
 * reads each at most once, into a local named after the declaration, so the C reads as the kernel's own signature.
 */
class ContextWord<T : BpfType> internal constructor(
	type: T,
	val index: Int,
) : Expr<T>(type)

/** A kernel structure's field, read through a pointer to it: `owner->field`, or `&owner->field` for an embedded one. */
class KernelRead<T : BpfType> internal constructor(
	val owner: Expr<*>,
	val field: KernelField<*, T>,
) : Expr<T>(field.type)

/** A record's field, in memory the program owns: a place, so it is what an assignment writes to. */
class FieldOf<T : Plain> internal constructor(
	val record: Expr<*>,
	val field: Field<*, T>,
	/** Whether the record is reached through a pointer (`r->f`) or is the program's own (`r.f`). */
	val throughPointer: Boolean,
) : Expr<T>(field.type)

/** A helper's result. */
class Called<T : BpfType> internal constructor(
	type: T,
	val helper: Helper,
	val arguments: List<Argument>,
) : Expr<T>(type)

/** What a helper is given: a value, a map by name, or the address of something the program owns. */
sealed interface Argument {
	data class Value(
		val expr: Expr<*>,
	) : Argument

	data class Map(
		val map: BpfMap,
	) : Argument

	data class AddressOf(
		val expr: Expr<*>,
	) : Argument

	data class Size(
		val bytes: Int,
	) : Argument
}

/** What a pointer points at, as a place: `*count`. */
class Pointed<T : Plain> internal constructor(
	val pointer: Expr<ValuePtr<T>>,
) : Expr<T>(pointer.type.target)

/** A value read at another width or signedness, said in the program and never inferred. */
class Converted<T : BpfType> internal constructor(
	type: T,
	val from: Expr<*>,
) : Expr<T>(type)

/** What arithmetic a program does. */
enum class Operator(
	val c: String,
) {
	Add("+"),
	Subtract("-"),
	Multiply("*"),
	BitAnd("&"),
	BitOr("|"),
	ShiftLeft("<<"),
	ShiftRight(">>"),
}

/** Two values of one type, combined: the type keeps the width, so nothing is computed at a width nobody asked for. */
class Arithmetic<T : Integral> internal constructor(
	type: T,
	val operator: Operator,
	val left: Expr<T>,
	val right: Expr<T>,
) : Expr<T>(type)

/** How two values are compared. */
enum class Comparison(
	val c: String,
) {
	Equal("=="),
	NotEqual("!="),
	Less("<"),
	LessOrEqual("<="),
	Greater(">"),
	GreaterOrEqual(">="),
}

/**
 * Something a program can branch on. A condition is not a value, so it cannot be added to a number or stored in a
 * record, and a number cannot be branched on without saying what it is compared against.
 */
sealed interface Condition {
	data class Compared(
		val comparison: Comparison,
		val left: Expr<*>,
		val right: Expr<*>,
	) : Condition

	data class Both(
		val left: Condition,
		val right: Condition,
	) : Condition

	data class Either(
		val left: Condition,
		val right: Condition,
	) : Condition

	data class Not(
		val condition: Condition,
	) : Condition

	/** A pointer that is null. */
	data class IsNull(
		val pointer: Expr<*>,
	) : Condition

	/**
	 * A pointer the kernel returned that is null or one of the error values it packs into a pointer: the top page of
	 * the address space, as the kernel's own `IS_ERR_OR_NULL` has it. The one way to read a kernel function's
	 * returned pointer safely, which is why it is a word of the vocabulary rather than arithmetic at the use site.
	 */
	data class IsErrorOrNull(
		val pointer: Expr<*>,
	) : Condition

	/** Every bit of [mask] set in [value]: a flag test, which cannot be written as a comparison by mistake. */
	data class BitsSet(
		val value: Expr<*>,
		val mask: Long,
	) : Condition
}

/** One statement of a hook's body. */
sealed interface Stmt {
	/** A local named and given its value, which is the only way a local comes to exist. */
	data class Declare(
		val local: Named<*>,
		val value: Expr<*>,
	) : Stmt

	/** A record of the program's own, every byte of it zero before anything is written into it. */
	data class DeclareZeroed(
		val local: Named<*>,
	) : Stmt

	data class Assign(
		val place: FieldOf<*>,
		val value: Expr<*>,
	) : Stmt

	/**
	 * Text read from kernel memory into a record's field of [Chars]: the helper is given the field's own length, so
	 * the one number that could overflow the field comes from the field's declaration.
	 */
	data class ReadText(
		val place: FieldOf<Chars>,
		val from: Expr<*>,
	) : Stmt

	/**
	 * [value] added to [place]. [atomically] where several CPUs may reach the same place at once, which a shared
	 * map's entry is; a per-CPU counter is the CPU's own, so adding to it needs no atomic and pays for none.
	 */
	data class Add(
		val place: Expr<*>,
		val value: Expr<*>,
		val atomically: Boolean,
	) : Stmt

	/** The program gives up and returns, when [condition] holds, or always when it is null. */
	data class Stop(
		val condition: Condition?,
	) : Stmt

	data class Branch(
		val condition: Condition,
		val then: List<Stmt>,
		val otherwise: List<Stmt>,
	) : Stmt

	/** A helper called for what it does, its result dropped: a map updated, an entry removed. */
	data class Call(
		val call: Called<*>,
	) : Stmt

	/**
	 * A pointer a helper gave, which may be null, named and used only where it is not: the verifier's own rule for a
	 * map lookup and a ring buffer's reservation, here as the only shape in which such a pointer exists at all.
	 */
	data class WhilePresent(
		val pointer: Named<*>,
		val from: Called<*>,
		val body: List<Stmt>,
		/** What runs when it is null: a counter for a lookup that found nothing, a drop count for a full ring. */
		val otherwise: List<Stmt>,
	) : Stmt

	/** A record reserved in a ring buffer, filled, and submitted: the three cannot come apart. */
	data class Send(
		val ring: RingBuffer<*>,
		val record: Named<*>,
		val fill: List<Stmt>,
		val onFull: List<Stmt>,
	) : Stmt
}

/**
 * A kernel helper, by the number the kernel knows it by (a stable part of its ABI) and the declaration the emitted C
 * gives it. The emitted program declares only the helpers it calls, as function pointers at their numbers, which is
 * why it needs no kernel headers to compile.
 */
class Helper internal constructor(
	val id: Int,
	val cName: String,
	/** How C declares the pointer to it, the name written as `%s`. */
	private val signature: String,
	/** Whether the kernel refuses it to a program that is not GPL. */
	val gplOnly: Boolean,
) {
	/** `static long (*bpf_probe_read_kernel_str)(void *, __u32, const void *) = (void *) 115;` */
	fun declaration(): String = "static ${signature.format(cName)} = (void *) $id;"

	override fun toString(): String = cName
}

/**
 * The helpers barion's programs call. Each is declared once, with the number and the signature the kernel's own
 * `bpf.h` gives it; nothing else may be called, so a helper that does not exist, or whose arguments are in another
 * order, is not expressible.
 */
object Helpers {
	val mapLookup =
		Helper(
			1,
			"bpf_map_lookup_elem",
			"void *(*%s)(void *map, const void *key)",
			gplOnly = false,
		)
	val mapUpdate =
		Helper(
			2,
			"bpf_map_update_elem",
			"long (*%s)(void *map, const void *key, const void *value, __u64 flags)",
			gplOnly = false,
		)
	val mapDelete =
		Helper(
			3,
			"bpf_map_delete_elem",
			"long (*%s)(void *map, const void *key)",
			gplOnly = false,
		)
	val ktimeNs = Helper(5, "bpf_ktime_get_ns", "__u64 (*%s)(void)", gplOnly = false)
	val cpu = Helper(8, "bpf_get_smp_processor_id", "__u32 (*%s)(void)", gplOnly = false)
	val pidTgid =
		Helper(14, "bpf_get_current_pid_tgid", "__u64 (*%s)(void)", gplOnly = false)
	val comm =
		Helper(16, "bpf_get_current_comm", "long (*%s)(void *buf, __u32 size)", gplOnly = false)
	val cgroupId =
		Helper(80, "bpf_get_current_cgroup_id", "__u64 (*%s)(void)", gplOnly = false)
	val readUserText =
		Helper(
			114,
			"bpf_probe_read_user_str",
			"long (*%s)(void *dst, __u32 size, const void *src)",
			gplOnly = true,
		)
	val readKernelText =
		Helper(
			115,
			"bpf_probe_read_kernel_str",
			"long (*%s)(void *dst, __u32 size, const void *src)",
			gplOnly = true,
		)
	val ringReserve =
		Helper(
			131,
			"bpf_ringbuf_reserve",
			"void *(*%s)(void *ringbuf, __u64 size, __u64 flags)",
			gplOnly = false,
		)
	val ringSubmit =
		Helper(
			132,
			"bpf_ringbuf_submit",
			"void (*%s)(void *data, __u64 flags)",
			gplOnly = false,
		)
	val ringDiscard =
		Helper(
			133,
			"bpf_ringbuf_discard",
			"void (*%s)(void *data, __u64 flags)",
			gplOnly = false,
		)
}
