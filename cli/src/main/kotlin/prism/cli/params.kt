package prism.cli

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/*
 * What a command takes. A command declares its parameters, each typed ([Param]), and is given their values
 * ([Args]); the words are read in one pass (reading.kt) that refuses what it cannot read rather than read it some
 * other way, and each command's --help is made from the same declarations (help.kt).
 */

/** A word read as a value, or why it is not one. */
sealed interface Parsed<out T> {
	data class Value<T>(
		val value: T,
	) : Parsed<T>

	data class Invalid(
		val reason: String,
	) : Parsed<Nothing>
}

/** What [next] makes of this value, or this reason it is not one. */
inline fun <T, U> Parsed<T>.then(next: (T) -> Parsed<U>): Parsed<U> =
	when (this) {
		is Parsed.Value -> next(value)
		is Parsed.Invalid -> this
	}

/** Each of [items] read by [read], in order, or the first reason one is not. */
inline fun <T, U> parseAll(items: List<T>, read: (T) -> Parsed<U>): Parsed<List<U>> =
	items.fold(Parsed.Value(emptyList<U>()) as Parsed<List<U>>) { done, item ->
		done.then { values -> read(item).then { Parsed.Value(values + it) } }
	}

/** What a parameter's words are: how one is read, and what help calls it. */
class ParamType<T>(
	val metavar: String,
	val read: (String) -> Parsed<T>,
) {
	/** The same words, called [metavar] in help. */
	fun named(metavar: String): ParamType<T> = ParamType(metavar, read)

	companion object {
		val TEXT = ParamType("TEXT") { Parsed.Value(it) }

		val INT =
			ParamType("N") { word ->
				word.toIntOrNull()?.let { Parsed.Value(it) }
					?: Parsed.Invalid("'$word' is not a whole number")
			}

		/** A whole number above zero. */
		val COUNT =
			ParamType("N") { word ->
				word.toIntOrNull()?.takeIf { it > 0 }?.let { Parsed.Value(it) }
					?: Parsed.Invalid("'$word' is not a positive whole number")
			}

		val PATH = ParamType("PATH") { Parsed.Value(Path(it)) }

		/** A folder that is there. */
		val FOLDER =
			ParamType("DIR") { word ->
				when {
					!Path(word).exists() -> Parsed.Invalid("$word does not exist")
					!Path(word).isDirectory() -> Parsed.Invalid("$word is not a folder")
					else -> Parsed.Value(Path(word))
				}
			}

		/** One of [entries], by its [name] as help lists it. */
		fun <E> choice(entries: List<E>, name: (E) -> String): ParamType<E> =
			ParamType(entries.joinToString("|", transform = name)) { word ->
				entries.firstOrNull { name(it) == word }?.let { Parsed.Value(it) }
					?: Parsed.Invalid(
						"'$word' is not one of ${entries.joinToString(", ", transform = name)}",
					)
			}
	}
}

/** A width by a height in pixels: `WxH`, both above zero. */
data class Size(
	val width: Int,
	val height: Int,
) {
	init {
		require(width > 0 && height > 0) {
			"a size is above zero both ways, not ${width}x$height"
		}
	}

	companion object {
		val TYPE =
			ParamType("WxH") { word ->
				val parts = word.split('x').mapNotNull { it.toIntOrNull()?.takeIf { n -> n > 0 } }

				if (parts.size == 2 && word.count { it == 'x' } == 1)
					Parsed.Value(Size(parts[0], parts[1]))
				else
					Parsed.Invalid("'$word' is not a size in the form WxH")
			}
	}
}

/** One of a command's parameters, whose value is a [T]. */
sealed interface Param<out T> {
	val help: String
}

/** A parameter given by name: `--name VALUE`, `--name=VALUE`, or a flag alone. */
sealed interface Opt<out T> : Param<T> {
	val names: List<String>
}

/** An option that takes a value, read as [type] says. */
sealed interface Valued<V, out T> : Opt<T> {
	val type: ParamType<V>
}

/** Given or not; nothing follows it. */
data class Flag(
	override val names: List<String>,
	override val help: String,
) : Opt<Boolean>

/** Given at most once: its value, or null. */
data class Single<T>(
	override val names: List<String>,
	override val type: ParamType<T>,
	override val help: String,
) : Valued<T, T?>

/** Given at most once: its value, or [default]. */
data class Defaulted<T>(
	override val names: List<String>,
	override val type: ParamType<T>,
	val default: T,
	/** How help names the default, when its value's own text is not what the user would type. */
	val defaultShown: String,
	override val help: String,
) : Valued<T, T>

/** How an option whose value may be left out was given: alone, or with a value. Not given at all is null. */
sealed interface Given<out T> {
	data object Alone : Given<Nothing>

	data class With<T>(
		val value: T,
	) : Given<T>
}

/**
 * Given at most once, alone or with a value: `--name`, `--name VALUE` or `--name=VALUE`. The word after it is its value
 * only where [type] reads it as one; where it does not, the option is given alone, and the word is read as what it is
 * (an argument, say), so a type that tells its values apart from the arguments' keeps the two from being mistaken.
 */
data class ValueOptional<T>(
	override val names: List<String>,
	override val type: ParamType<T>,
	override val help: String,
) : Valued<T, Given<T>?>

/** Given exactly once. */
data class Required<T>(
	override val names: List<String>,
	override val type: ParamType<T>,
	override val help: String,
) : Valued<T, T>

/** Given any number of times (at least once when [atLeastOnce]): its values, in order. */
data class Repeated<T>(
	override val names: List<String>,
	override val type: ParamType<T>,
	override val help: String,
	val atLeastOnce: Boolean,
) : Valued<T, List<T>>

/** A parameter given by its place among the words that are not options. */
sealed interface Positional<V, out T> : Param<T> {
	val name: String
	val type: ParamType<V>
}

/** One word. */
data class Argument<T>(
	override val name: String,
	override val type: ParamType<T>,
	override val help: String,
) : Positional<T, T>

/** One word, or none: its value, or [default]. Only ever after the [Argument]s. */
data class OptionalArgument<T>(
	override val name: String,
	override val type: ParamType<T>,
	val default: () -> T,
	val defaultShown: String,
	override val help: String,
) : Positional<T, T>

/** Every word left, none or more: only ever the last. */
data class Arguments<T>(
	override val name: String,
	override val type: ParamType<T>,
	override val help: String,
) : Positional<T, List<T>>

/** The values a command was given, by parameter: every parameter of the command has one. */
class Args internal constructor(
	private val values: Map<Param<*>, Any?>,
) {
	/**
	 * The value of [param]. The reader put a [T] there for every parameter of the command it read, so the cast holds
	 * for any parameter of that command; one of another command's is refused by name.
	 */
	@Suppress("UNCHECKED_CAST")
	operator fun <T> get(param: Param<T>): T {
		require(param in values) { "$param is not a parameter of this command" }

		return values[param] as T
	}
}
