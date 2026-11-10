package barion.cli

private val WHITESPACE = Regex("\\s+")

/**
 * [text] filled to [width]: its words, each line its indent and as many words as fit. A word is never broken, so
 * one longer than a line (a path) has a line of its own and runs past the width.
 */
fun wrap(
	text: String,
	width: Int,
	initialIndent: String = "",
	subsequentIndent: String = "",
): List<String> =
	text.split(WHITESPACE).filter { it.isNotEmpty() }.fold(emptyList()) { lines, word ->
		val last = lines.lastOrNull()

		when {
			last == null -> listOf(initialIndent + word)
			last.length + 1 + word.length <= width -> lines.dropLast(1) + "$last $word"
			else -> lines + (subsequentIndent + word)
		}
	}

/** A node as its group lists it: its name, and the words it takes. */
private fun synopsis(node: Node): String =
	when (node) {
		is Group -> "${node.name} COMMAND"
		is Command ->
			(
				listOf(node.name) +
					node.params.filterIsInstance<Positional<*, *>>().map {
						when (it) {
							is Arguments<*> -> "${it.name}..."
							is OptionalArgument<*> -> "[${it.name}]"
							is Argument<*> -> it.name
						}
					}
			).joinToString(" ")
	}

private fun usage(node: Node, path: List<String>): String =
	"usage: " +
		when (node) {
			is Group -> "${path.joinToString(" ")} COMMAND ..."
			is Command ->
				(
					path.dropLast(1) + synopsis(node) +
						listOfNotNull("[OPTIONS]".takeIf { node.params.any { it is Opt<*> } })
				).joinToString(" ")
		}

/** How wide help is: its descriptions wrap within it. */
private const val HELP_WIDTH = 100

/** Rows of a term and its description, the descriptions in one column [width] past the terms' indent, wrapped. */
private fun rows(
	items: List<Pair<String, String>>,
	width: Int =
		items.maxOfOrNull { it.first.length }
			?: 0,
): List<String> =
	items.flatMap { (term, text) ->
		wrap(text, HELP_WIDTH, "  ${term.padEnd(width)}  ", " ".repeat(width + 4)).ifEmpty {
			listOf("  $term")
		}
	}

/** What help says of a parameter beyond its own words: its default, and whether it repeats or is needed. */
private fun Param<*>.notes(): List<String> =
	when (this) {
		is Defaulted<*> -> listOf("default $defaultShown")
		is OptionalArgument<*> -> listOf("default $defaultShown")
		is Required<*> -> listOf("required")
		is Repeated<*> ->
			listOf("repeatable") +
				listOfNotNull("required".takeIf { atLeastOnce })
		is Flag, is Single<*>, is ValueOptional<*> -> emptyList()
		is Argument<*>, is Arguments<*> -> emptyList()
	}

private fun Param<*>.described(): String =
	(listOf(help).filter { it.isNotEmpty() } + notes().map { "($it)" }).joinToString(" ")

private fun Opt<*>.term(): String =
	names.joinToString(", ") +
		when (this) {
			is ValueOptional<*> -> " [${type.metavar}]"
			is Valued<*, *> -> " ${type.metavar}"
			is Flag -> ""
		}

/** [node]'s help, reached by [path]. */
fun help(node: Node, path: List<String>): String =
	(
		listOf(usage(node, path), "") + wrap(node.help, HELP_WIDTH) +
			when (node) {
				is Group -> {
					val listed = node.categories.filter { it.nodes.isNotEmpty() }
					val width = listed.flatMap { it.nodes }.maxOfOrNull { synopsis(it).length } ?: 0

					listed.flatMap { category ->
						listOf("", "${category.name}:") +
							rows(category.nodes.map { synopsis(it) to it.help }, width)
					} + listOf("", "${path.joinToString(" ")} COMMAND --help: what a command takes")
				}
				is Command -> {
					val positional = node.params.filterIsInstance<Positional<*, *>>()
					val opts = node.params.filterIsInstance<Opt<*>>()
					val arguments =
						if (positional.isEmpty())
							emptyList()
						else
							listOf("", "arguments:") + rows(positional.map { it.name to it.described() })

					arguments + listOf("", "options:") +
						rows(opts.map { it.term() to it.described() } + ("-h, --help" to "this help"))
				}
			}
	).joinToString("\n")

/** [path]'s mistake, as said on stderr: what is wrong, and the usage. */
fun mistake(node: Node, path: List<String>, message: String): String =
	"${path.joinToString(
		" ",
	)}: $message\n${usage(node, path)}; ${path.joinToString(" ")} --help says more"

/**
 * What [words] run in [root], as the process would show it: the text for stdout, the text for stderr, and the exit
 * status. A command's [InputError] is its message on stderr and status 1; anything else it throws is a bug and
 * propagates.
 */
data class Ran(
	val out: String,
	val err: String,
	val status: Int,
)

fun execute(root: Node, words: List<String>): Ran =
	when (val invocation = invocation(root, words)) {
		is Invocation.Help -> Ran(help(invocation.node, invocation.path), "", 0)
		is Invocation.Wrong ->
			Ran(
				"",
				mistake(invocation.node, invocation.path, invocation.message),
				1,
			)
		is Invocation.Run ->
			try {
				val outcome = invocation.command.run(invocation.args)

				Ran(outcome.text, "", outcome.status)
			} catch (error: InputError) {
				Ran("", "${invocation.path.joinToString(" ")}: ${error.message}", 1)
			}
	}
