package prism.cli

/** What a command printed, and its exit status. */
data class Outcome(
	val text: String,
	val status: Int = 0,
)

/** A problem with what a command was given: printed as "prism COMMAND: message", exit status 1. */
class InputError(
	message: String,
) : Exception(message)

/** A command, or a group of them: what the command line names. */
sealed interface Node {
	val name: String
	val help: String
}

/** The commands of a group listed together, under one word. */
data class Category(
	val name: String,
	val nodes: List<Node>,
)

/** Commands named after a word: `prism probe watch`. */
class Group(
	override val name: String,
	override val help: String,
	val categories: List<Category>,
) : Node {
	init {
		val names = categories.flatMap { category -> category.nodes.map { it.name } }
		val twice = names.groupBy { it }.filterValues { it.size > 1 }.keys

		require(twice.isEmpty()) { "$name: commands named twice: $twice" }
	}

	fun child(name: String): Node? =
		categories.firstNotNullOfOrNull { category ->
			category.nodes.firstOrNull {
				it.name ==
					name
			}
		}
}

/**
 * A command: what it takes, declared as it is constructed ([flag], [option], [argument], ...; never after), and what
 * it does with their values. The declarations are the one statement of the command's words: the reader reads them,
 * help lists them, and [run] reads their values by the same handles.
 */
abstract class Command(
	override val name: String,
	override val help: String,
) : Node {
	private val declared = ArrayList<Param<*>>()

	/** Its parameters, in the order declared: what it reads, and what its help lists. */
	val params: List<Param<*>> get() = declared.toList()

	/** What it prints for [args], and its exit status; a problem with what it was given is an [InputError]. */
	abstract fun run(args: Args): Outcome

	private fun <P : Param<*>> declared(param: P): P {
		val taken = declared.filterIsInstance<Opt<*>>().flatMap { it.names }.toSet()
		val positional = declared.filterIsInstance<Positional<*, *>>()

		require(
			param !is Opt<*> ||
				param.names.none {
					it in taken
				},
		) { "$name: an option named twice: $param" }
		require(positional.none { it is Arguments<*> } || param !is Positional<*, *>) {
			"$name: only the last argument takes the rest of the words"
		}
		require(param !is Argument<*> || positional.none { it is OptionalArgument<*> }) {
			"$name: a required argument after an optional one"
		}
		declared += param

		return param
	}

	fun flag(vararg names: String, help: String): Param<Boolean> =
		declared(Flag(names.toList(), help))

	fun <T> option(vararg names: String, type: ParamType<T>, help: String): Param<T?> =
		declared(Single(names.toList(), type, help))

	fun <T> option(
		vararg names: String,
		type: ParamType<T>,
		default: T,
		help: String,
		shown: String = default.toString(),
	): Param<T> = declared(Defaulted(names.toList(), type, default, shown, help))

	/** An option given alone or with a value ([ValueOptional]): null where it is not given. */
	fun <T> optionalValue(
		vararg names: String,
		type: ParamType<T>,
		help: String,
	): Param<Given<T>?> = declared(ValueOptional(names.toList(), type, help))

	fun <T> required(vararg names: String, type: ParamType<T>, help: String): Param<T> =
		declared(Required(names.toList(), type, help))

	fun <T> repeated(
		vararg names: String,
		type: ParamType<T>,
		help: String,
		atLeastOnce: Boolean = false,
	): Param<List<T>> = declared(Repeated(names.toList(), type, help, atLeastOnce))

	fun <T> argument(name: String, type: ParamType<T>, help: String = ""): Param<T> =
		declared(Argument(name, type, help))

	fun <T> argument(
		name: String,
		type: ParamType<T>,
		default: () -> T,
		shown: String,
		help: String = "",
	): Param<T> = declared(OptionalArgument(name, type, default, shown, help))

	fun <T> arguments(name: String, type: ParamType<T>, help: String = ""): Param<List<T>> =
		declared(Arguments(name, type, help))
}
