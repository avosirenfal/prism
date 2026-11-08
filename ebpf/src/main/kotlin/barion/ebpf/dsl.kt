package barion.ebpf

/*
 * What a program says, said. A program is declared as its maps and its hooks; a hook's body is written in the
 * vocabulary of this file, each word a statement or a value, and nothing else can be said. What the verifier insists
 * on is what the types insist on here:
 *
 *   - a pointer a helper gave back may be null, so a map lookup and a ring buffer's reservation hand their pointer
 *     only to the block that runs where it is not null, and there is no other way to come by one;
 *   - a reservation is filled and submitted in one word ([Body.send]), so one cannot be left unsubmitted;
 *   - a record travels with its own length, so the helper that fills a name field is given that field's length;
 *   - a hook's body always returns, because the body is the statements before a return the emitter writes itself;
 *   - arithmetic is between values of one type, and a conversion is said ([convertedTo]), never inferred.
 *
 * What the types cannot refuse (a record field left unset, a GPL-only helper under another licence, more on the
 * stack than the verifier allows) is recorded as a [ProgramError] and reported by [Program.check], never thrown, so
 * a declaration always runs to its end and says everything that is wrong with it at once.
 */

/** The licence a program is loaded under: the kernel refuses many helpers to a program that is not GPL. */
enum class License(
	val text: String,
) {
	Gpl("GPL"),
	DualBsdGpl("Dual BSD/GPL"),
	Proprietary("Proprietary"),
	;

	val allowsGplOnlyHelpers: Boolean get() = this != Proprietary
}

/** Text in kernel memory, to be read into a record's [Chars] field. */
class KernelText internal constructor(
	internal val from: Expr<*>,
)

/**
 * A record in memory, as a place its fields are written to and read from: one the program filled itself
 * ([Body.filled]), one it reserved in a ring buffer, or one a map lookup gave back.
 */
class Place<R : Record<R>> internal constructor(
	val record: R,
	internal val base: Expr<*>,
	internal val throughPointer: Boolean,
	private val body: Body,
) {
	internal val written = LinkedHashSet<Field<R, *>>()

	/** The field's value, read back. */
	operator fun <T : Plain> get(field: Field<R, T>): Expr<T> =
		FieldOf(base, field, throughPointer)

	operator fun <T : Plain> set(field: Field<R, T>, value: Expr<T>) {
		written += field
		body.say(Stmt.Assign(FieldOf(base, field, throughPointer), value))
	}

	/** Text the kernel holds, read into [field], as many bytes as the field holds and no more. */
	operator fun set(field: Field<R, Chars>, text: KernelText) {
		written += field
		body.say(Stmt.ReadText(FieldOf(base, field, throughPointer), text.from))
	}

	/** [by] added to [field] as one atomic operation: what several CPUs may do to one entry of a shared map at once. */
	fun <T : Integral> add(field: Field<R, T>, by: Expr<T>) {
		written += field
		body.say(Stmt.Add(FieldOf(base, field, throughPointer), by, atomically = true))
	}

	/** The fields of the record the program has not written. */
	internal fun unset(): List<Field<R, *>> = record.fields.filterNot { it in written }
}

/**
 * The body of a hook, as it is being said. Statements land in the block being said; a block within a block
 * ([branch], [send], [find]) is said into its own list and nothing of it escapes, which is the one piece of
 * bookkeeping here that changes as the body is written. [Program.declare] freezes the result.
 */
sealed class Body(
	internal val program: ProgramBuilder,
) {
	private val blocks: MutableList<MutableList<Stmt>> = mutableListOf(mutableListOf())
	private var locals = 0

	internal fun say(statement: Stmt) {
		blocks.last().add(statement)
	}

	/** [said] said into a block of its own: the statements it said, and what it gave back. */
	internal fun <T> block(said: () -> T): Pair<List<Stmt>, T> {
		blocks.add(mutableListOf())

		val value = said()
		val statements = blocks.removeLast()

		return statements.toList() to value
	}

	internal fun statements(): List<Stmt> = blocks.single().toList()

	/** A name no local of this body has, for a pointer or a record the vocabulary itself names. */
	internal fun nameFor(what: String): String = "${what}_${locals++}"

	// ---- values the kernel gives for the asking --------------------------------------------------------------

	/** Nanoseconds on the kernel's monotonic clock. */
	fun now(): Expr<U64> = called(U64, Helpers.ktimeNs)

	/** The calling thread's id in the low word, its process' in the high one. */
	fun pidTgid(): Expr<U64> = called(U64, Helpers.pidTgid)

	/** The calling task's cgroup, which says which container made the change. */
	fun cgroup(): Expr<U64> = called(U64, Helpers.cgroupId)

	fun cpu(): Expr<U32> = called(U32, Helpers.cpu)

	private fun <T : BpfType> called(
		type: T,
		helper: Helper,
		arguments: List<Argument> = emptyList(),
	): Expr<T> {
		program.uses(helper)

		return Called(type, helper, arguments)
	}

	// ---- locals and records ---------------------------------------------------------------------------------

	/**
	 * [value] under a name of its own, worked out once however often the name is read. Declared with `by`, so the
	 * name in the emitted C is the name in the Kotlin.
	 */
	fun <T : BpfType> local(value: Expr<T>): Declaring<Expr<T>> =
		Declaring { name ->
			val named = Named(value.type, name)

			say(Stmt.Declare(named, value))

			named
		}

	/**
	 * A record of the program's own, zeroed and then filled by [fill]: a key to look something up by. Every field
	 * must be filled, since a key is matched by all of its bytes; what is left out is a [ProgramError.FieldUnset].
	 */
	fun <R : Record<R>> filled(record: R, fill: (Place<R>) -> Unit): Place<R> {
		val named = Named(RecordValue(record), nameFor(record.cName))
		val place = Place(record, named, throughPointer = false, body = this)

		program.holdsOnStack(record)
		say(Stmt.DeclareZeroed(named))
		fill(place)
		program.mustHaveFilled(place, "${record.cName} is looked up by every byte it holds")

		return place
	}

	// ---- what a program does ---------------------------------------------------------------------------------

	/** The program gives up here if [condition] holds: there is nothing of interest in this call. */
	fun stopIf(condition: Condition) = say(Stmt.Stop(condition))

	/** The program goes on only if [condition] holds. */
	fun stopUnless(condition: Condition) = say(Stmt.Stop(Condition.Not(condition)))

	/** [then] where [condition] holds, [otherwise] where it does not. */
	fun branch(condition: Condition, otherwise: () -> Unit = {}, then: () -> Unit) {
		val (ifSo, _) = block(then)
		val (ifNot, _) = block(otherwise)

		say(Stmt.Branch(condition, ifSo, ifNot))
	}

	/**
	 * A record reserved in [ring], filled by [fill] and submitted: the host reads it in order. [onFull] is what to do
	 * when the host has not kept up and there is no room, which a program must say, because an event lost without
	 * anyone knowing is an index that is quietly wrong. Every field must be filled, since the host reads all of
	 * them; what is left out is a [ProgramError.FieldUnset].
	 */
	fun <R : Record<R>> send(
		ring: RingBuffer<R>,
		onFull: () -> Unit,
		fill: (Place<R>) -> Unit,
	) {
		program.uses(Helpers.ringReserve)
		program.uses(Helpers.ringSubmit)

		val reserved = Named(RecordPtr(ring.record), nameFor(ring.record.cName))
		val place = Place(ring.record, reserved, throughPointer = true, body = this)
		val (filled, _) = block { fill(place) }
		val (full, _) = block(onFull)

		program.mustHaveFilled(place, "the host reads every field of ${ring.record.cName}")
		say(Stmt.Send(ring, reserved, filled, full))
	}

	/**
	 * What [table] holds under [key], where it holds anything: [found] runs with the value as a place, [missing]
	 * where there is none. There is no other way to a map's value, which is the verifier's own rule about the null a
	 * lookup may give back, here as the only shape the lookup has.
	 */
	fun <K : Record<K>, V : Record<V>> find(
		table: Table<K, V>,
		key: Place<K>,
		missing: () -> Unit = {},
		found: (Place<V>) -> Unit,
	) {
		val lookup = lookupOf(table, key)
		val pointer = Named(RecordPtr(table.value), nameFor(table.value.cName))
		val place = Place(table.value, pointer, throughPointer = true, body = this)
		val (whenFound, _) = block { found(place) }
		val (whenMissing, _) = block(missing)

		say(Stmt.WhilePresent(pointer, lookup, whenFound, whenMissing))
	}

	/** Whether [table] holds anything under [key], without reading it. */
	fun <K : Record<K>, V : Record<V>> holds(table: Table<K, V>, key: Place<K>): Condition =
		Condition.Not(Condition.IsNull(lookupOf(table, key)))

	private fun <K : Record<K>, V : Record<V>> lookupOf(
		table: Table<K, V>,
		key: Place<K>,
	): Called<RecordPtr<V>> {
		program.uses(Helpers.mapLookup)

		return Called(
			RecordPtr(table.value),
			Helpers.mapLookup,
			listOf(Argument.Map(table), Argument.AddressOf(key.base)),
		)
	}

	/** [table]'s entry under [key] set to the record [value] fills, whether or not it held one. */
	fun <K : Record<K>, V : Record<V>> put(
		table: Table<K, V>,
		key: Place<K>,
		value: Place<V>,
	) {
		program.uses(Helpers.mapUpdate)
		say(
			Stmt.Call(
				Called(
					S64,
					Helpers.mapUpdate,
					listOf(
						Argument.Map(table),
						Argument.AddressOf(key.base),
						Argument.AddressOf(value.base),
						Argument.Value(Const(U64, ANY)),
					),
				),
			),
		)
	}

	/** [table]'s entry under [key] removed, if it has one. */
	fun <K : Record<K>, V : Record<V>> remove(table: Table<K, V>, key: Place<K>) {
		program.uses(Helpers.mapDelete)
		say(
			Stmt.Call(
				Called(
					S64,
					Helpers.mapDelete,
					listOf(Argument.Map(table), Argument.AddressOf(key.base)),
				),
			),
		)
	}

	/**
	 * One added to [entry]'s counter on this CPU. Per-CPU, so it costs no atomic and no contention; the host sums the
	 * CPUs. A counter the kernel will not give (an index out of range cannot happen, since the map is as long as the
	 * enum) is counted nowhere rather than crashing.
	 */
	fun <E : Enum<E>> count(counters: Counters<E>, entry: E) {
		program.uses(Helpers.mapLookup)

		program.names(entry)

		val index = filledIndex(entry.ordinal)
		val pointer = Named(ValuePtr(U64), nameFor("count"))

		val lookup =
			Called(
				ValuePtr(U64),
				Helpers.mapLookup,
				listOf(Argument.Map(counters), Argument.AddressOf(index)),
			)

		val (bump, _) =
			block {
				say(Stmt.Add(Pointed(pointer), Const(U64, 1), atomically = false))
			}

		say(Stmt.WhilePresent(pointer, lookup, bump, emptyList()))
	}

	/** An index as a local, for a per-CPU array's lookup, which takes the address of its key. */
	private fun filledIndex(index: Int): Named<U32> {
		val named = Named(U32, nameFor("index"))

		say(Stmt.Declare(named, Const(U32, index.toLong())))

		return named
	}

	/**
	 * [entry]'s number, as the record's field holds it. The enum travels with the program: the emitted C names each
	 * entry, and the host reads the field back as the same Kotlin enum, so neither side holds a number of its own.
	 */
	fun <E : Enum<E>> E.code(): Expr<U32> {
		program.names(this)

		return Const(U32, ordinal.toLong(), cCode(this))
	}

	/** Text the kernel holds at [pointer], for a record's [Chars] field. */
	fun kernelText(pointer: Expr<Ptr<Bytes>>): KernelText {
		program.uses(Helpers.readKernelText)

		return KernelText(pointer)
	}

	private companion object {
		/** `BPF_ANY`: the entry is written whether or not it was there. */
		const val ANY = 0L
	}
}

/**
 * The body of a hook of [callable]: its parameters are read here and nowhere else, and only its own, since a
 * parameter carries the function that declared it in its type.
 */
sealed class HookBody<F : Callable<F>>(
	program: ProgramBuilder,
	internal val callable: F,
	private val argumentsAt: Int,
) : Body(program) {
	private val read = HashMap<Parameter<*, *>, Named<*>>()

	/** The value the kernel passed for this parameter, under its own name. */
	fun <T : BpfType> Parameter<F, T>.value(): Expr<T> {
		@Suppress("UNCHECKED_CAST")
		val already = read[this] as Named<T>?

		if (already != null)
			return already

		val named = Named(type, cName)

		read[this] = named
		say(Stmt.Declare(named, ContextWord(type, argumentsAt + index)))

		return named
	}
}

/** The body of a hook on the way in: the parameters, as the caller gave them. */
class EntryBody<F : Callable<F>> internal constructor(
	program: ProgramBuilder,
	callable: F,
	argumentsAt: Int = 0,
) : HookBody<F>(program, callable, argumentsAt)

/** The body of a hook on the way out: the parameters, and what the function gave back. */
class ExitBody<F : KernelFunction<F>> internal constructor(
	program: ProgramBuilder,
	callable: F,
) : HookBody<F>(program, callable, 0) {
	/**
	 * What the function gave back. Only an exit hook has one to read, and only of the function it is a hook of; a
	 * `void` function's [NoReturn] is not a [Returned] at all, so there is nothing to call this on.
	 */
	fun <T : BpfType> Returned<F, T>.value(): Expr<T> {
		val named = Named(type, "returned")

		say(Stmt.Declare(named, ContextWord(type, function.parameters.size)))

		return named
	}
}
