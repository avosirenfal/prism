package prism.cli

/** What a command line asks for. */
sealed interface Invocation {
	/** The command named, run with these values. */
	data class Run(
		val command: Command,
		val args: Args,
		val path: List<String>,
	) : Invocation

	/** A command's or a group's help. */
	data class Help(
		val node: Node,
		val path: List<String>,
	) : Invocation

	/** A mistake in the words, as said of the command or group it was found in. */
	data class Wrong(
		val node: Node,
		val path: List<String>,
		val message: String,
	) : Invocation
}

private val HELP = setOf("-h", "--help")

/** What [words] ask of [node], reached by [path]: a command run, its help, or the mistake in them. */
tailrec fun invocation(
	node: Node,
	words: List<String>,
	path: List<String> = listOf(node.name),
): Invocation =
	when (node) {
		is Command ->
			if (words.takeWhile { it != "--" }.any { it in HELP })
				Invocation.Help(node, path)
			else
				when (val read = read(node, words)) {
					is Parsed.Value -> Invocation.Run(node, read.value, path)
					is Parsed.Invalid -> Invocation.Wrong(node, path, read.reason)
				}
		is Group -> {
			val first = words.firstOrNull()
			val child = first?.let(node::child)

			when {
				first == null || first in HELP -> Invocation.Help(node, path)
				child == null ->
					Invocation.Wrong(
						node,
						path,
						if (first.startsWith("-"))
							"unknown option $first"
						else
							"no command '$first'",
					)
				else -> invocation(child, words.drop(1), path + child.name)
			}
		}
	}

/** The words read so far: each option's values, in order, and the words that are not options. */
private data class Read(
	val options: Map<Opt<*>, List<Any?>>,
	val positional: List<String>,
)

/** [words] as [command]'s parameters' values, or the mistake in them. */
private fun read(command: Command, words: List<String>): Parsed<Args> {
	val opts = command.params.filterIsInstance<Opt<*>>()
	val byName = opts.flatMap { opt -> opt.names.map { it to opt } }.toMap()
	val positional = command.params.filterIsInstance<Positional<*, *>>()

	return options(words, byName, Read(emptyMap(), emptyList())).then { read ->
		optionValues(opts, read.options).then { options ->
			positionalValues(positional, read.positional).then { Parsed.Value(Args(options + it)) }
		}
	}
}

/** An option's word: `--name`, `--name=VALUE`, or `-o`; a lone `-` and a negative number are words of their own. */
private fun isOption(word: String) =
	word.length > 1 && word.startsWith("-") && word[1] != '.' && !word[1].isDigit()

private fun <T> value(type: ParamType<T>, word: String, of: String): Parsed<T> =
	when (val parsed = type.read(word)) {
		is Parsed.Value -> parsed
		is Parsed.Invalid -> Parsed.Invalid("$of: ${parsed.reason}")
	}

/** [words] read from the first on, options by [byName], into [read]. */
private tailrec fun options(
	words: List<String>,
	byName: Map<String, Opt<*>>,
	read: Read,
): Parsed<Read> {
	val word = words.firstOrNull() ?: return Parsed.Value(read)

	if (word == "--")
		return Parsed.Value(read.copy(positional = read.positional + words.drop(1)))
	if (!isOption(word))
		return options(words.drop(1), byName, read.copy(positional = read.positional + word))

	val name = word.substringBefore('=')
	val inline = word.substringAfter('=', "").takeIf { '=' in word }
	val opt = byName[name] ?: return Parsed.Invalid("unknown option $name")
	val given = read.options[opt].orEmpty()

	if (given.isNotEmpty() && opt !is Repeated<*>)
		return Parsed.Invalid("$name given twice")

	val taken: Parsed<Pair<Int, Any?>> =
		when (opt) {
			is Flag ->
				if (inline !=
					null
				)
					Parsed.Invalid("$name takes no value")
				else
					Parsed.Value(0 to true)
			is ValueOptional<*> -> {
				val next =
					words
						.getOrNull(1)
						?.takeUnless { isOption(it) || it == "--" }
						?.let { opt.type.read(it) as? Parsed.Value }

				when {
					inline != null ->
						value(opt.type, inline, name).then { Parsed.Value(0 to Given.With(it)) }
					next != null -> Parsed.Value(1 to Given.With(next.value))
					else -> Parsed.Value(0 to Given.Alone)
				}
			}
			is Valued<*, *> -> {
				val next =
					words.getOrNull(1)?.takeUnless {
						isOption(it) &&
							it.substringBefore('=') in byName
					}

				when {
					inline != null -> value(opt.type, inline, name).then { Parsed.Value(0 to it) }
					next != null -> value(opt.type, next, name).then { Parsed.Value(1 to it) }
					else -> Parsed.Invalid("$name needs a value (${opt.type.metavar})")
				}
			}
		}

	return when (taken) {
		is Parsed.Invalid -> taken
		is Parsed.Value -> {
			val (count, value) = taken.value

			options(
				words.drop(1 + count),
				byName,
				read.copy(
					options =
						read.options + (opt to given + value),
				),
			)
		}
	}
}

/** Each option's value from what was given of it: its default, or null, where it was not. */
private fun optionValues(
	opts: List<Opt<*>>,
	given: Map<Opt<*>, List<Any?>>,
): Parsed<Map<Param<*>, Any?>> =
	parseAll(opts) { opt ->
		val values = given[opt].orEmpty()

		when (opt) {
			is Flag -> Parsed.Value(values.isNotEmpty())
			is Single<*> -> Parsed.Value(values.singleOrNull())
			is ValueOptional<*> -> Parsed.Value(values.singleOrNull())
			is Defaulted<*> -> Parsed.Value(values.singleOrNull() ?: opt.default)
			is Required<*> ->
				values.singleOrNull()?.let { Parsed.Value(it) }
					?: Parsed.Invalid("${opt.names.last()} is needed (${opt.type.metavar})")
			is Repeated<*> ->
				if (opt.atLeastOnce && values.isEmpty())
					Parsed.Invalid("${opt.names.last()} is needed at least once (${opt.type.metavar})")
				else
					Parsed.Value(values)
		}.then { Parsed.Value(opt to it) }
	}.then { Parsed.Value(it.toMap()) }

/** The words that are not options, as [params] in order: the optional ones take what is there, the last [Arguments] what is left. */
private fun positionalValues(
	params: List<Positional<*, *>>,
	words: List<String>,
): Parsed<Map<Param<*>, Any?>> {
	val required = params.filterIsInstance<Argument<*>>()
	val optional = params.filterIsInstance<OptionalArgument<*>>()
	val rest = params.filterIsInstance<Arguments<*>>().singleOrNull()
	val single = required + optional

	if (words.size < required.size)
		return Parsed.Invalid(
			"missing ${required.drop(words.size).joinToString(" ") { it.name }}",
		)
	if (rest == null && words.size > single.size)
		return Parsed.Invalid("unexpected word '${words[single.size]}'")

	val given =
		parseAll(single.zip(words)) { (param, word) ->
			value(param.type, word, param.name).then {
				Parsed.Value(param to it)
			}
		}

	val defaults =
		optional.drop((words.size - required.size).coerceAtLeast(0)).map {
			it to
				it.default()
		}

	val remaining =
		rest?.let { param ->
			parseAll(words.drop(single.size)) { value(param.type, it, param.name) }.then {
				Parsed.Value(
					listOf(param to it),
				)
			}
		} ?: Parsed.Value(emptyList())

	return given.then { values ->
		remaining.then { last -> Parsed.Value((values + defaults + last).toMap()) }
	}
}
