package barion.ebpf

/*
 * The values a program computes, and the conditions it branches on. Every word here is a pure function from
 * expressions to an expression: arithmetic between two values of one type, a comparison, a field read through a
 * pointer to the structure that declared it. Width and signedness travel in the type, so nothing is widened,
 * narrowed or reinterpreted unless the program says so ([convertedTo]).
 */

/** [value] at this type: `U64 of 1`. */
infix fun <T : Integral> T.of(value: Long): Expr<T> = Const(this, value)

infix fun <T : Integral> T.of(value: Int): Expr<T> = Const(this, value.toLong())

// ---- arithmetic, between values of one type ------------------------------------------------------------------

operator fun <T : Integral> Expr<T>.plus(other: Expr<T>): Expr<T> =
	Arithmetic(type, Operator.Add, this, other)

operator fun <T : Integral> Expr<T>.minus(other: Expr<T>): Expr<T> =
	Arithmetic(type, Operator.Subtract, this, other)

operator fun <T : Integral> Expr<T>.times(other: Expr<T>): Expr<T> =
	Arithmetic(type, Operator.Multiply, this, other)

infix fun <T : Integral> Expr<T>.bitAnd(other: Expr<T>): Expr<T> =
	Arithmetic(type, Operator.BitAnd, this, other)

infix fun <T : Integral> Expr<T>.bitOr(other: Expr<T>): Expr<T> =
	Arithmetic(type, Operator.BitOr, this, other)

infix fun <T : Integral> Expr<T>.shl(bits: Int): Expr<T> =
	Arithmetic(type, Operator.ShiftLeft, this, Const(type, bits.toLong()))

infix fun <T : Integral> Expr<T>.shr(bits: Int): Expr<T> =
	Arithmetic(type, Operator.ShiftRight, this, Const(type, bits.toLong()))

/** This value at another width or signedness, said here and nowhere implied. */
fun <T : Integral> Expr<*>.convertedTo(type: T): Expr<T> = Converted(type, this)

// ---- comparisons --------------------------------------------------------------------------------------------

infix fun <T : Integral> Expr<T>.eq(other: Expr<T>): Condition =
	Condition.Compared(Comparison.Equal, this, other)

infix fun <T : Integral> Expr<T>.ne(other: Expr<T>): Condition =
	Condition.Compared(Comparison.NotEqual, this, other)

infix fun <T : Integral> Expr<T>.lt(other: Expr<T>): Condition =
	Condition.Compared(Comparison.Less, this, other)

infix fun <T : Integral> Expr<T>.le(other: Expr<T>): Condition =
	Condition.Compared(Comparison.LessOrEqual, this, other)

infix fun <T : Integral> Expr<T>.gt(other: Expr<T>): Condition =
	Condition.Compared(Comparison.Greater, this, other)

infix fun <T : Integral> Expr<T>.ge(other: Expr<T>): Condition =
	Condition.Compared(Comparison.GreaterOrEqual, this, other)

/** Every bit of [mask] set: a flag test, which cannot be mistaken for a comparison against the mask. */
infix fun <T : Integral> Expr<T>.hasBits(mask: Long): Condition =
	Condition.BitsSet(this, mask)

infix fun Condition.and(other: Condition): Condition = Condition.Both(this, other)

infix fun Condition.or(other: Condition): Condition = Condition.Either(this, other)

operator fun Condition.not(): Condition = Condition.Not(this)

/** This pointer is null. */
fun <S : KernelStruct<S>> Expr<Ptr<S>>.isNull(): Condition = Condition.IsNull(this)

/**
 * This pointer, as a kernel function gave it back, is null or an error the kernel packed into a pointer, as its own
 * `IS_ERR_OR_NULL` reads it. What every returned pointer is checked with before it is read.
 */
fun <S : KernelStruct<S>> Expr<Ptr<S>>.isErrorOrNull(): Condition =
	Condition.IsErrorOrNull(this)

// ---- the kernel's own fields -------------------------------------------------------------------------------

/**
 * [field] of the structure this points at. The field carries the structure that declared it, so only a pointer to
 * that same structure can read it; CO-RE relocates the access against the kernel's own layout at load time.
 */
operator fun <S : KernelStruct<S>, T : BpfType> Expr<Ptr<S>>.get(
	field: KernelField<S, T>,
): Expr<T> = KernelRead(this, field)

/** How the emitted C names an enum's entry: `Change.DirectoryMade` is `CHANGE_DIRECTORY_MADE`. */
internal fun cCode(entry: Enum<*>): String {
	val type = entry.javaClass.takeIf { it.isEnum } ?: entry.javaClass.superclass

	return "${snakeCase(type.simpleName).uppercase()}_${snakeCase(entry.name).uppercase()}"
}
