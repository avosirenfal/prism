package prism.calc

import prism.parsing.Parsed
import prism.parsing.Span

/*
 * What the calculator makes of a text: read ([Grammar]), worked out ([evaluated]), and, for a text typed where it may
 * or may not be meant as arithmetic (the launcher's field), how sure it is that it is ([Shape]).
 */

/** What working out a text came to. */
sealed interface Outcome {
	/** Its [value], and what is to be said of how it came about: an Int's overflow, a fraction rounded away. */
	data class Answer(
		val value: Value,
		val notes: List<String> = emptyList(),
	) : Outcome {
		/** The value as it is shown: its thousands apart, and with an exponent only where it is very large or small. */
		val shown: String get() = shown(value)

		/** The value as it is copied: every digit, nothing between them. */
		val copied: String get() = copied(value)

		/** Its type, where it is said: none for a Decimal, which is what is typed. */
		val type: String? get() = typeSaid(value)
	}

	/** It could not be read: [why], [at] that place in the text. */
	data class Unread(
		val why: String,
		val at: Int,
	) : Outcome

	/** It was read, and could not be worked out: [why], because of what is [at] that span. */
	data class Wrong(
		val why: String,
		val at: Span,
	) : Outcome
}

/** How much a text looks like something to be worked out, rather than something else typed into the same field. */
enum class Shape {
	/** Arithmetic: it has an operator, or calls a function. */
	Calculation,

	/** Arithmetic, and as likely something else: numbers joined by dashes or slashes alone, as a date is (2024-11-18). */
	Ambiguous,

	/** It reads, and is nothing to work out: a number on its own, or a name. */
	Plain,
}

/** What kind of thing a completion names. */
enum class Named(
	val written: String,
) {
	Function("Function"),
	Property("Property"),
	Constant("Constant"),
}

/** A name that what is being typed could be on its way to: a function or a constant, and what it is. */
data class Completion(
	val name: String,
	/** What is written in place of the name begun: a function's name and its opening parenthesis. */
	val inserted: String,
	/** How it is called (each overload), or what a constant's value is. */
	val signatures: List<String>,
	val does: String,
	val kind: Named,
)

/** What a text typed into a calculator is: read, worked out, and what it could be on its way to. */
class Calculation private constructor(
	val text: String,
	val read: Parsed<Expression>,
) {
	val outcome: Outcome by lazy {
		when (read) {
			is Parsed.Wrong -> Outcome.Unread(read.said, read.at)
			is Parsed.Read -> evaluated(read.value)
		}
	}

	/** How much this looks like arithmetic; none where it does not read as any. */
	val shape: Shape? by lazy { (read as? Parsed.Read)?.value?.let { shapeOf(it, text) } }

	/**
	 * The functions and constants the name being typed at the end of the text could be: none where the text does not
	 * end in a name begun, or ends in one that is a name whole and the start of no other.
	 */
	val completions: List<Completion> by lazy { completionsOf(text) }

	companion object {
		fun of(text: String): Calculation = Calculation(text, Grammar.read(text))
	}
}

/** What a part of an expression came to: a value and the notes on the way to it, or what went wrong where. */
private sealed interface Worked {
	data class Value(
		val value: prism.calc.Value,
		val notes: List<String>,
	) : Worked

	data class Wrong(
		val why: String,
		val at: Span,
	) : Worked
}

/** [computed] as the work of [at]: what went wrong said of it, and the notes before kept. */
private fun worked(computed: Computed, at: Span, before: List<String>): Worked =
	when (computed) {
		is Computed.Done -> Worked.Value(computed.value, before + computed.notes)
		is Computed.Failed -> Worked.Wrong(computed.why, at)
	}

/** [parts] worked out, one after another, and what [whole] makes of their values; the first that went wrong, if one did. */
private fun all(
	parts: List<Expression>,
	whole: (List<Value>, List<String>) -> Worked,
): Worked {
	val values =
		parts.map {
			when (val part = work(it)) {
				is Worked.Wrong -> return part
				is Worked.Value -> part
			}
		}

	return whole(values.map { it.value }, values.flatMap { it.notes })
}

private fun work(expression: Expression): Worked =
	when (expression) {
		is Expression.Literal ->
			when (val made = implicitly(DecimalValue(expression.exact), expression.type)) {
				is Converted.Done -> Worked.Value(made.value, made.notes)
				is Converted.Refused -> Worked.Wrong(made.why, expression.span)
			}
		is Expression.Grouped -> work(expression.inner)
		is Expression.Property ->
			all(listOf(expression.of)) { (of), notes ->
				when (val literal = Library.property(expression.name)) {
					null ->
						Worked.Wrong(
							if (Library.function(expression.name) != null)
								"${expression.name} is a function: call it, as .${expression.name}()"
							else
								"there is no property ${expression.name}",
							expression.named,
						)
					else ->
						when (val made = implicitly(of.number, literal.type)) {
							is Converted.Done -> Worked.Value(made.value, notes)
							is Converted.Refused -> Worked.Wrong(made.why, expression.named)
						}
				}
			}
		is Expression.Name ->
			Library.constant(expression.name)?.let { Worked.Value(it.value, emptyList()) }
				?: Worked.Wrong(
					if (Library.function(expression.name) != null)
						"${expression.name} is a function: call it, as ${expression.name}(…)"
					else
						"there is no ${expression.name}",
					expression.span,
				)
		is Expression.Prefixed ->
			all(listOf(expression.operand)) { (operand), notes ->
				worked(prefixed(expression.operator, operand.number), expression.span, notes)
			}
		is Expression.Binary ->
			all(listOf(expression.left, expression.right)) { (left, right), notes ->
				if (expression.operator == Operator.Power)
					called("pow", listOf(left, right), expression.at, notes)
				else
					worked(binary(expression.operator, left.number, right.number), expression.at, notes)
			}
		is Expression.Call ->
			all(expression.arguments) { values, notes ->
				called(expression.name, values, expression.named, notes)
			}
	}

private fun called(
	name: String,
	values: List<Value>,
	at: Span,
	notes: List<String>,
): Worked {
	val function =
		Library.function(name) ?: return Worked.Wrong("there is no function $name", at)

	return when (val made = call(function, values.map { it.number })) {
		is Called.Refused -> Worked.Wrong(made.why, at)
		is Called.Done -> worked(made.computed, at, notes)
	}
}

/** [expression] worked out. */
fun evaluated(expression: Expression): Outcome =
	when (val made = work(expression)) {
		is Worked.Value -> Outcome.Answer(made.value, made.notes.distinct())
		is Worked.Wrong -> Outcome.Wrong(made.why, made.at)
	}

/** How much [expression], read from [text], looks like something to be worked out. */
private fun shapeOf(expression: Expression, text: String): Shape {
	fun plain(of: Expression): Boolean =
		when (of) {
			is Expression.Literal, is Expression.Name -> true
			is Expression.Property -> plain(of.of)
			is Expression.Grouped -> plain(of.inner)
			is Expression.Prefixed -> of.operand is Expression.Literal
			else -> false
		}

	/** Whole numbers joined by `-` or `/` alone, with nothing between them: as a date or a version is written. */
	fun dashed(of: Expression): Boolean =
		when (of) {
			is Expression.Literal -> of.type == NumberType.Decimal && of.exact.scale() <= 0
			is Expression.Binary ->
				(of.operator == Operator.Minus || of.operator == Operator.Divide) &&
					dashed(of.left) && dashed(of.right)
			else -> false
		}

	return when {
		plain(expression) -> Shape.Plain
		dashed(expression) && text.none(Char::isWhitespace) -> Shape.Ambiguous
		else -> Shape.Calculation
	}
}

/** The functions and constants the name at the end of [text] could be on its way to. */
private fun completionsOf(text: String): List<Completion> {
	val begun = text.takeLastWhile { it.isLetterOrDigit() || it == '_' }

	if (begun.isEmpty() || !(begun.first().isLetter() || begun.first() == '_'))
		return emptyList()

	val before = text.dropLast(begun.length).trimEnd()
	// After a dot, what is called on something: a function that takes it, as its first argument.
	val method = before.endsWith('.')

	val functions =
		Library.FUNCTIONS
			.filter { it.name.startsWith(begun, ignoreCase = true) }
			.filter {
				!method ||
					it.overloads.any { overload ->
						overload.parameters.isNotEmpty()
					}
			}.map { Completion(it.name, "${it.name}(", it.signatures, it.does, Named.Function) }

	val constants =
		Library.CONSTANTS
			.takeUnless { method }
			.orEmpty()
			.filter { it.name.startsWith(begun, ignoreCase = true) }
			.map {
				Completion(
					it.name,
					it.name,
					listOf("${it.name} = ${said(it.value)}"),
					it.does,
					Named.Constant,
				)
			}

	val properties =
		Library.PROPERTIES
			.takeIf { method }
			.orEmpty()
			.filter { it.name.startsWith(begun, ignoreCase = true) }
			.map {
				Completion(
					it.name,
					it.name,
					listOf("x.${it.name}: ${it.type.written}"),
					it.does,
					Named.Property,
				)
			}

	val found =
		(constants + properties + functions).sortedWith(
			compareBy({ it.name.length }, { it.name }),
		)

	// A name typed whole, and the start of nothing else: there is nothing to finish.
	return found.takeUnless { it.size == 1 && it.single().name == begun }.orEmpty()
}
