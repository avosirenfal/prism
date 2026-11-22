package prism.ebpf

/*
 * A program as it is declared, and as it comes out. [program] takes a declaration and gives a [Program]: its maps,
 * its hooks as statements, the helpers it calls, the kernel structures it reads and the records it and the host
 * share, with everything wrong with it as data ([ProgramError]). Nothing is thrown for a mistake in a declaration,
 * so one run says everything that is wrong at once; nothing is emitted or loaded from a program that has errors.
 */

/** Where a hook sits, and how the section the loader reads names it. */
sealed interface HookKind {
	/** The section name libbpf attaches by. */
	fun section(callable: Callable<*>): String

	/** What the C function of a hook of this kind on [callable] is called before its shape is told: `exit_vfs_mkdir`. */
	fun function(callable: Callable<*>): String

	/** On the way into a kernel function, before it has done anything. */
	data object Entry : HookKind {
		override fun section(callable: Callable<*>) = "fentry/${callable.cName}"

		override fun function(callable: Callable<*>) = "entry_${callable.cName}"
	}

	/** On the way out of a kernel function, where what it did and what it gave back are both known. */
	data object Exit : HookKind {
		override fun section(callable: Callable<*>) = "fexit/${callable.cName}"

		override fun function(callable: Callable<*>) = "exit_${callable.cName}"
	}

	/** A kernel tracepoint, reached through BTF so its arguments are typed. */
	data object Tracepoint : HookKind {
		override fun section(callable: Callable<*>) = "tp_btf/${callable.cName}"

		override fun function(callable: Callable<*>) = "tp_${callable.cName}"
	}

	/** A system call's tracepoint, whose context is the record the kernel fills. */
	data object Syscall : HookKind {
		override fun section(callable: Callable<*>) = "tp/syscalls/${callable.cName}"

		override fun function(callable: Callable<*>) = "sys_${callable.cName}"
	}
}

/**
 * What a program's hooks' C functions are called, which is what the verifier's log, the loader's errors, `bpftool` and
 * the names a loader pins them under say. Two shapes of one kernel function are two C functions, so a name tells them
 * apart.
 */
sealed interface HookNaming {
	/** The C function of a hook of [kind] on [callable]. */
	fun name(kind: HookKind, callable: Callable<*>): String

	/** As its kind has it, and the shape it is of, where it says one: `exit_vfs_mkdir_since_6_19`. */
	data object ByShape : HookNaming {
		override fun name(kind: HookKind, callable: Callable<*>): String =
			kind.function(callable) +
				callable.shape
					?.let { shape ->
						"_" +
							shape.toString().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
					}.orEmpty()
	}

	/**
	 * [prefix], as its kind has it, and the oldest version it works on that is [oldest] or newer:
	 * `prism_exit_do_file_open_7_0`, and `prism_exit_vfs_unlink_6_19` for a function of one shape, which works on
	 * versions before the oldest the program is for too. A shape that ended before [oldest] (one kept for the kernels
	 * a program's tests run on) is named for the version it began in, which no shape for [oldest] and after is.
	 */
	data class Versioned(
		val prefix: String,
		val oldest: KernelVersion,
	) : HookNaming {
		override fun name(kind: HookKind, callable: Callable<*>): String =
			"${prefix}_${kind.function(callable)}_${versionOf(callable.shape).cName}"

		/** The oldest version [shape] works on, as these name it. */
		fun versionOf(shape: Shape?): KernelVersion {
			val until = shape?.until
			val since = shape?.since

			return if (until != null && until < oldest)
				requireNotNull(since) { "a shape that ended before $oldest says when it began" }
			else
				maxOf(since ?: oldest, oldest)
		}
	}
}

/** One hook of a program: where it sits, what it hooks, what it says, and what its C function is called. */
class Hook internal constructor(
	val kind: HookKind,
	val callable: Callable<*>,
	val body: List<Stmt>,
	/** The bytes of the verifier's stack the hook's own records take. */
	val stackBytes: Int,
	val cName: String,
) {
	val section: String get() = kind.section(callable)

	override fun toString(): String = section + callable.shape?.let { " ($it)" }.orEmpty()
}

/** A Kotlin enum a program's records carry, so the emitted C names each entry and the host reads the same names. */
class EnumGroup internal constructor(
	val cPrefix: String,
	val entries: List<Enum<*>>,
)

/**
 * A program being declared: its maps, then its hooks. Every word of the vocabulary that records anything comes
 * here, and [built] freezes the result.
 */
class ProgramBuilder internal constructor(
	val name: String,
	val license: License,
	private val naming: HookNaming,
) {
	private val maps = ArrayList<BpfMap>()
	private val hooks = ArrayList<Hook>()
	private val helpers = LinkedHashSet<Helper>()
	private val enums = LinkedHashMap<String, EnumGroup>()
	private val errors = ArrayList<ProgramError>()
	private var stackBytes = 0

	/** A ring buffer of [record]s, [bytes] long: declared with `by`, so its name in the kernel is its name here. */
	fun <R : Record<R>> ringBuffer(record: R, bytes: Int): Declaring<RingBuffer<R>> =
		Declaring { mapName ->
			if (record.layout.sizeBytes * 2 > bytes)
				errors += ProgramError.RingTooSmall(mapName, record, bytes)

			RingBuffer(mapName, record, bytes).also { declare(it) }
		}

	/**
	 * A map from [key] to [value], holding at most [maxEntries]. [leastRecentlyUsed] has the kernel drop the entries
	 * nothing has read lately rather than refuse an insert, which is what a cache of what the host has told the
	 * program wants.
	 */
	fun <K : Record<K>, V : Record<V>> table(
		key: K,
		value: V,
		maxEntries: Int,
		leastRecentlyUsed: Boolean = false,
	): Declaring<Table<K, V>> =
		Declaring { mapName ->
			Table(mapName, key, value, maxEntries, leastRecentlyUsed).also { declare(it) }
		}

	/** A counter per CPU for each of [entries], which the host sums; `Dropped.entries` of a Kotlin enum. */
	fun <E : Enum<E>> counters(entries: List<E>): Declaring<Counters<E>> =
		Declaring { mapName ->
			entries.firstOrNull()?.let(::names)

			Counters(mapName, entries).also { declare(it) }
		}

	private fun declare(map: BpfMap) {
		if (maps.any { it.cName == map.cName })
			errors += ProgramError.MapNamedTwice(map.cName)
		maps += map
	}

	/** A hook on the way into [function]. */
	fun <F : KernelFunction<F>> fentry(function: F, body: EntryBody<F>.() -> Unit) =
		hook(HookKind.Entry, function) { EntryBody(this, function).apply(body) }

	/** A hook on the way out of [function], where what it gave back can be read. */
	fun <F : KernelFunction<F>> fexit(function: F, body: ExitBody<F>.() -> Unit) =
		hook(HookKind.Exit, function) { ExitBody(this, function).apply(body) }

	/** A hook on a kernel tracepoint, through BTF. */
	fun <T : KernelTracepoint<T>> tracepoint(tracepoint: T, body: EntryBody<T>.() -> Unit) =
		hook(HookKind.Tracepoint, tracepoint) { EntryBody(this, tracepoint).apply(body) }

	/** A hook on a system call's tracepoint, whose arguments are the caller's own. */
	fun <T : SyscallTracepoint<T>> syscall(tracepoint: T, body: EntryBody<T>.() -> Unit) =
		hook(HookKind.Syscall, tracepoint) {
			EntryBody(this, tracepoint, SyscallTracepoint.ARGUMENTS_AT / WORD).apply(body)
		}

	private fun hook(kind: HookKind, callable: Callable<*>, said: () -> Body) {
		stackBytes = 0

		val body = said()

		// Twice is the same declaration again, or two declarations the C could not tell apart (two shapes of one
		// function under one name).
		val cName = naming.name(kind, callable)

		if (hooks.any {
				it.kind == kind &&
					(it.callable === callable || it.cName == cName)
			}
		)
			errors += ProgramError.HookedTwice(kind.section(callable))
		if (stackBytes > STACK_BYTES)
			errors += ProgramError.StackTooLarge(kind.section(callable), stackBytes)
		hooks += Hook(kind, callable, body.statements(), stackBytes, cName)
	}

	internal fun uses(helper: Helper) {
		helpers += helper

		if (helper.gplOnly && !license.allowsGplOnlyHelpers)
			errors += ProgramError.HelperNeedsGpl(helper, license)
	}

	/** An enum the program's records carry, named in the emitted C so the two sides read the same numbers. */
	internal fun names(entry: Enum<*>) {
		val type = entry.javaClass.takeIf { it.isEnum } ?: entry.javaClass.superclass
		val prefix = snakeCase(type.simpleName).uppercase()

		enums.getOrPut(prefix) {
			@Suppress("UNCHECKED_CAST")
			EnumGroup(prefix, (type.enumConstants as Array<Enum<*>>).toList())
		}
	}

	internal fun holdsOnStack(record: Record<*>) {
		stackBytes += roundUp(record.layout.sizeBytes, WORD)
	}

	internal fun <R : Record<R>> mustHaveFilled(place: Place<R>, why: String) {
		val unset = place.unset()

		if (unset.isNotEmpty())
			errors += ProgramError.FieldUnset(place.record, unset.map { it.cName }, why)
	}

	internal fun built(): Program {
		if (hooks.isEmpty())
			errors += ProgramError.NoHooks(name)

		return Program(
			name,
			license,
			maps.toList(),
			hooks.toList(),
			helpers.toList(),
			enums.values.toList(),
			errors.toList(),
		)
	}

	private companion object {
		/** What the verifier allows a program's stack to be. */
		const val STACK_BYTES = 512
		const val WORD = 8
	}
}

/**
 * [declared] as a program named [name]. The result holds everything the emitter and the loader need and nothing
 * else; what is wrong with it is in [Program.errors], which [Program.emitted] and the loader both refuse to go past.
 */
fun program(
	name: String,
	license: License = License.Gpl,
	naming: HookNaming = HookNaming.ByShape,
	declared: ProgramBuilder.() -> Unit,
): Program = declared(name, license, naming, declared).program

/**
 * A program and what its declaration handed back: the maps the host reaches it through, as the declaration's own
 * typed handles. So the ring the host reads is the ring the program sends to, by construction, and not a map found
 * by a name typed twice.
 */
class Declared<M> internal constructor(
	val program: Program,
	val maps: M,
)

/** [declared] as a program named [name], its hooks named as [naming] says, with the maps it hands back for the host. */
fun <M> declared(
	name: String,
	license: License = License.Gpl,
	naming: HookNaming = HookNaming.ByShape,
	declared: ProgramBuilder.() -> M,
): Declared<M> {
	val builder = ProgramBuilder(name, license, naming)
	val maps = builder.declared()

	return Declared(builder.built(), maps)
}

/**
 * A program as it came out: its maps, its hooks, and what it reaches of the kernel and shares with the host. The
 * kernel structures and the records are read off the statements themselves, so what the emitted C declares is what
 * the program really touches, and a declaration that is not used is not emitted.
 */
class Program internal constructor(
	val name: String,
	val license: License,
	val maps: List<BpfMap>,
	val hooks: List<Hook>,
	val helpers: List<Helper>,
	val enums: List<EnumGroup>,
	val errors: List<ProgramError>,
) {
	/**
	 * The kernel structures whose fields the program reads: those and no others are declared in the emitted C, each
	 * after any structure embedded in it, since C needs an embedded structure complete where it is held.
	 */
	val structs: List<KernelStruct<*>> by lazy {
		val read =
			buildList {
				for (hook in hooks)
					walk(hook.body) { expr ->
						if (expr is KernelRead) {
							add(expr.field.owner)
							// A structure held inside another is declared whole, so it is declared even if nothing
							// is read through it.
							if (expr.field.address)
								add((expr.field.type as Ptr<*>).target)
						}
					}
			}.distinct().filter { it.needsDeclaration }

		embeddedFirst(read)
	}

	/** The records the program and the host share: its maps' keys and values, and the ones it holds on its stack. */
	val records: List<Record<*>> by lazy {
		buildList {
			for (map in maps) {
				(map as? RingBuffer<*>)?.let { add(it.record) }
				(map.keys as? Holds.OneRecord)?.let { add(it.record) }
				(map.values as? Holds.OneRecord)?.let { add(it.record) }
			}
			for (hook in hooks)
				walk(hook.body) { expr ->
					when (val type = expr.type) {
						is RecordValue<*> -> add(type.record)
						is RecordPtr<*> -> add(type.record)
						else -> Unit
					}
				}
		}.distinct()
	}

	/**
	 * Every structure of the kernel the program names, whether or not it reads a field of one: the emitted C declares
	 * each, so a pointer to one can be cast and passed about even where nothing is read from it.
	 */
	val mentionedStructs: List<KernelStruct<*>> by lazy {
		buildList {
			for (hook in hooks) {
				for (parameter in hook.callable.parameters)
					(parameter.type as? Ptr<*>)?.let { add(it.target) }
				walk(hook.body) { expr ->
					(expr.type as? Ptr<*>)?.let { add(it.target) }
					if (expr is KernelRead)
						add(expr.field.owner)
				}
			}
			for (hook in hooks)
				((hook.callable as? KernelFunction<*>)?.returns as? Returned<*, *>)?.let { returned ->
					(returned.type as? Ptr<*>)?.let { add(it.target) }
				}
		}.distinct().filter { it.needsDeclaration }
	}

	val isLoadable: Boolean get() = errors.isEmpty()

	/** What is wrong with the program, as one message a line. */
	fun why(): String = errors.joinToString("\n") { it.message }

	override fun toString(): String = "$name: ${hooks.size} hooks, ${maps.size} maps"
}

/**
 * [structs] with every structure after the ones embedded in it: C needs an embedded structure complete where it is
 * held, while a pointer to one needs no more than the forward declaration every structure gets.
 */
private fun embeddedFirst(structs: List<KernelStruct<*>>): List<KernelStruct<*>> {
	val ordered = LinkedHashSet<KernelStruct<*>>()

	fun place(struct: KernelStruct<*>) {
		if (struct in ordered)
			return
		// Added before its own embedded structures are walked, so a cycle (which C could not hold anyway) ends here.
		ordered += struct

		val held =
			struct.fields.filter { it.address }.map { (it.type as Ptr<*>).target }.filter {
				it in
					structs
			}

		for (inner in held) {
			ordered -= inner
			place(inner)
		}
		ordered -= struct
		ordered += struct
	}

	structs.forEach(::place)

	return ordered.toList()
}

/** Every expression of [statements], the nested blocks' included, given to [visit]. */
internal fun walk(statements: List<Stmt>, visit: (Expr<*>) -> Unit) {
	for (statement in statements)
		when (statement) {
			is Stmt.Declare -> {
				visit(statement.local)
				walk(statement.value, visit)
			}
			is Stmt.DeclareZeroed -> visit(statement.local)
			is Stmt.Assign -> {
				walk(statement.place, visit)
				walk(statement.value, visit)
			}
			is Stmt.ReadText -> {
				walk(statement.place, visit)
				walk(statement.from, visit)
			}
			is Stmt.Add -> {
				walk(statement.place, visit)
				walk(statement.value, visit)
			}
			is Stmt.Stop -> statement.condition?.let { walk(it, visit) }
			is Stmt.Branch -> {
				walk(statement.condition, visit)
				walk(statement.then, visit)
				walk(statement.otherwise, visit)
			}
			is Stmt.Call -> walk(statement.call, visit)
			is Stmt.WhilePresent -> {
				visit(statement.pointer)
				walk(statement.from, visit)
				walk(statement.body, visit)
				walk(statement.otherwise, visit)
			}
			is Stmt.Send -> {
				visit(statement.record)
				walk(statement.fill, visit)
				walk(statement.onFull, visit)
			}
		}
}

private fun walk(expr: Expr<*>, visit: (Expr<*>) -> Unit) {
	visit(expr)

	when (expr) {
		is KernelRead -> walk(expr.owner, visit)
		is FieldOf -> walk(expr.record, visit)
		is Pointed -> walk(expr.pointer, visit)
		is Converted -> walk(expr.from, visit)
		is Arithmetic -> {
			walk(expr.left, visit)
			walk(expr.right, visit)
		}
		is Called ->
			for (argument in expr.arguments)
				when (argument) {
					is Argument.Value -> walk(argument.expr, visit)
					is Argument.AddressOf -> walk(argument.expr, visit)
					is Argument.Map, is Argument.Size -> Unit
				}
		is Const, is Named, is ContextWord -> Unit
	}
}

private fun walk(condition: Condition, visit: (Expr<*>) -> Unit) {
	when (condition) {
		is Condition.Compared -> {
			walk(condition.left, visit)
			walk(condition.right, visit)
		}
		is Condition.Both -> {
			walk(condition.left, visit)
			walk(condition.right, visit)
		}
		is Condition.Either -> {
			walk(condition.left, visit)
			walk(condition.right, visit)
		}
		is Condition.Not -> walk(condition.condition, visit)
		is Condition.IsNull -> walk(condition.pointer, visit)
		is Condition.IsErrorOrNull -> walk(condition.pointer, visit)
		is Condition.BitsSet -> walk(condition.value, visit)
	}
}
