package prism.index.query

/*
 * The user's aliases: a name for a query written often, used in another by its name and an `@`. `projects@ prism/`,
 * where `projects` was defined as `~/projects`, is everything under that folder with `prism/` in its path;
 * `raws@`, defined as `~/Photos ext:raw`, is two terms under one name.
 *
 * An alias stands for what its query means, not for its text: each definition is read once, on its own, when the
 * aliases are made ([Aliases.of]), and a query that uses one is given the terms it came to. So nothing in a
 * definition's text (a quote left open, a `-` at its start) reaches the query around it, and using an alias costs a
 * search nothing: by the time the index is walked there are only terms.
 *
 * A definition may use other aliases. One that would come back to itself is not defined, and neither is one written
 * wrong: the aliases there are can always be stood for, and standing for one always ends.
 */

/**
 * An alias's name: letters and digits, at least one ([of]). Two names that differ only in case are one name, shown
 * as it was written.
 */
class AliasName private constructor(
	val text: String,
) {
	/** What it is known by, whatever case it is written in. */
	val key: String = text.lowercase()

	/** As it is written in a query: `projects@`. */
	val written: String get() = "$text$SIGN"

	override fun equals(other: Any?): Boolean = other is AliasName && other.key == key

	override fun hashCode(): Int = key.hashCode()

	override fun toString(): String = text

	companion object {
		/** What follows a name where it is used. */
		const val SIGN = '@'

		/** Whether a name may have [c] in it. */
		fun allows(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

		/** [text] as a name, where it is one. */
		fun of(text: String): AliasName? =
			text.takeIf { it.isNotEmpty() && it.all(::allows) }?.let(::AliasName)
	}
}

/**
 * One alias: its [name], the query it stands for as it was written ([text]) and as that reads ([read]), and what it
 * means ([query]), with every alias it uses stood for already. Made only by [Aliases.of], which makes none whose
 * query cannot be searched for.
 */
class Alias internal constructor(
	val name: AliasName,
	val text: String,
	val read: ReadQuery,
) {
	val query: Query = read.query

	override fun equals(other: Any?): Boolean =
		other is Alias && other.name.text == name.text && other.text == text

	override fun hashCode(): Int = name.text.hashCode() * 31 + text.hashCode()

	override fun toString(): String = "${name.written} = $text"
}

/** What looking for an alias by its name gives. */
sealed interface Looked {
	data class Found(
		val alias: Alias,
	) : Looked

	/** There is none of that name. */
	data object Unknown : Looked

	/** It is the alias being defined. */
	data object Itself : Looked

	/** It leads back to the alias being defined, or to itself. */
	data object Loops : Looked

	/** One was written of that name, with something wrong with it. */
	data object Invalid : Looked
}

/** Where a query's aliases are looked for. */
fun interface AliasSource {
	fun find(name: AliasName): Looked
}

/** Why something written as an alias is not one. */
sealed interface AliasProblem {
	val message: String

	/** Nothing for a name. */
	data object Unnamed : AliasProblem {
		override val message get() = "Enter a name"
	}

	/** A name with something other than letters and digits in it. */
	data object Misnamed : AliasProblem {
		override val message get() = "Use only letters and digits"
	}

	/** A name another alias, written before it, has. */
	data class Taken(
		val name: AliasName,
	) : AliasProblem {
		override val message get() = "“${name.written}” is already defined"
	}

	/** Nothing for a query, or nothing in it to search for. */
	data object Empty : AliasProblem {
		override val message get() = "Enter a search"
	}

	/** Something wrong with its query, where in its text and what. */
	data class Wrong(
		val problem: Problem,
	) : AliasProblem {
		override val message get() = problem.message
	}
}

/**
 * The aliases there are, each by its name, in the order they were written. Made only by [of], from what was written:
 * so each has a name that is one, no two have one name, and every one's query reads and ends.
 */
class Aliases private constructor(
	private val defined: Map<String, Alias>,
) : AliasSource {
	/** Every one, in the order they were written. */
	val all: List<Alias> get() = defined.values.toList()

	val isEmpty: Boolean get() = defined.isEmpty()

	operator fun get(name: AliasName): Alias? = defined[name.key]

	override fun find(name: AliasName): Looked =
		this[name]?.let(Looked::Found) ?: Looked.Unknown

	/**
	 * Those a name being typed could be: each whose name begins with [typed], the shortest names first. None for
	 * nothing typed, or for what is no start of a name.
	 */
	fun beginning(typed: String): List<Alias> =
		AliasName
			.of(typed)
			?.let { start ->
				defined.values.filter { it.name.key.startsWith(start.key) }.sortedBy {
					it.name.key.length
				}
			}.orEmpty()

	override fun equals(other: Any?): Boolean = other is Aliases && other.all == all

	override fun hashCode(): Int = all.hashCode()

	override fun toString(): String = "Aliases($all)"

	/** What reading what was written gave: the [aliases] that could be made, and what is wrong with each of the rest. */
	class Read internal constructor(
		val aliases: Aliases,
		/** For each alias written, in order: what is wrong with it, or null where it was made. */
		val problems: List<AliasProblem?>,
	) {
		/**
		 * What stops the alias written [at]th from being kept as it is: what is wrong with it, or what its query
		 * has left unfinished (a quote not closed), which is searched for as well as it can be but is no way to
		 * leave a definition.
		 */
		fun unfit(at: Int, name: String): AliasProblem? =
			problems.getOrNull(at)
				?: AliasName
					.of(name)
					?.let { aliases[it] }
					?.read
					?.problems
					?.firstOrNull()
					?.let(AliasProblem::Wrong)

		/** Whether every one written was made, with nothing left unfinished. */
		fun fit(written: List<Pair<String, String>>): Boolean =
			written.indices.all { unfit(it, written[it].first) == null }
	}

	companion object {
		/** None. */
		val NONE = Aliases(emptyMap())

		/**
		 * The aliases [written], each a name and its query's text: those that can be made, and why each of the rest
		 * cannot. One written wrong does not lose the others, unless they use it.
		 */
		fun of(written: List<Pair<String, String>>): Read {
			val names = written.map { (name, _) -> AliasName.of(name) }
			// The first written of a name has it.
			val first =
				names
					.withIndex()
					.filter { it.value != null }
					.groupBy({ it.value!!.key }, { it.index })
					.mapValues { it.value.first() }

			val resolving =
				Resolving(
					first.mapValues { (_, at) -> names[at]!! to written[at].second },
				)

			val problems =
				written.indices.map { at ->
					val name = names[at]

					when {
						written[at].first.isEmpty() -> AliasProblem.Unnamed
						name == null -> AliasProblem.Misnamed
						first[name.key] != at -> AliasProblem.Taken(names[first.getValue(name.key)]!!)
						else -> (resolving.resolved(name.key) as? Resolved.Broken)?.problem
					}
				}

			return Read(
				Aliases(
					first.keys
						.mapNotNull { key ->
							(resolving.resolved(key) as? Resolved.Made)?.let { key to it.alias }
						}.toMap(),
				),
				problems,
			)
		}

		/** The aliases [written], name to query: those that can be made. */
		fun of(written: Map<String, String>): Read = of(written.toList())
	}
}

/**
 * A name being typed at the end of a query: the [aliases] it could be on its way to, and the query's [text] with
 * what was typed of the name, which is from [from] to its end.
 */
data class AliasCompletion(
	val text: String,
	val from: Int,
	val aliases: List<Alias>,
) {
	/** The text with [alias] where the name was being typed, and a space after it, for the next term. */
	fun completed(alias: Alias): String = text.substring(0, from) + alias.name.written + " "
}

/**
 * The aliases the last term of [read] could be on its way to naming, where it is bare words at the very end of
 * the text (negated or not) that begin a name: `pro`, for `projects@`. None where a space was typed after it,
 * where it is anything but a name's letters and digits, or where it is an alias already.
 */
fun Aliases.completing(read: ReadQuery): AliasCompletion? {
	val term = read.syntax.terms.lastOrNull() ?: return null
	val words = term.body as? BodySyntax.Words ?: return null
	val typed = words.text as? TextSyntax.Bare ?: return null

	if (term.span.end != read.text.length || words.start != null || words.end != null ||
		term.junk != null
	)
		return null

	return beginning(typed.text).takeIf { it.isNotEmpty() }?.let {
		AliasCompletion(read.text, typed.span.start, it)
	}
}

/** What an alias written came to. */
private sealed interface Resolved {
	data class Made(
		val alias: Alias,
	) : Resolved

	data class Broken(
		val problem: AliasProblem,
		/** Whether it is not made because it comes back to itself. */
		val loops: Boolean,
	) : Resolved
}

/**
 * The aliases [written] (by key: name and text) being made, each once and the ones it uses before it. The one place
 * that remembers anything while they are: which are done, and which are being read now, so that one that comes back
 * to itself is seen to.
 */
private class Resolving(
	private val written: Map<String, Pair<AliasName, String>>,
) {
	private val done = HashMap<String, Resolved>()
	private val reading = ArrayList<String>()

	/** The alias known by [key], made, with everything it uses made first. */
	fun resolved(key: String): Resolved? {
		val (name, text) = written[key] ?: return null

		return done[key] ?: read(key, name, text).also { done[key] = it }
	}

	private fun read(key: String, name: AliasName, text: String): Resolved {
		var loops = false

		reading += key

		val read =
			QueryText.read(
				text,
				AliasSource { used ->
					when {
						used.key == key -> Looked.Itself
						used.key in reading -> Looked.Loops
						else ->
							when (val resolved = resolved(used.key)) {
								null -> Looked.Unknown
								is Resolved.Made -> Looked.Found(resolved.alias)
								is Resolved.Broken ->
									if (resolved.loops)
										Looked.Loops
									else
										Looked.Invalid
							}
					}.also { loops = loops || it == Looked.Itself || it == Looked.Loops }
				},
			)

		reading.removeLast()

		val wrong = read.problems.firstOrNull { it.kind == Problem.Kind.Invalid }

		return when {
			wrong != null -> Resolved.Broken(AliasProblem.Wrong(wrong), loops)
			text.isBlank() -> Resolved.Broken(AliasProblem.Empty, loops = false)
			read.query.terms.isEmpty() ->
				Resolved.Broken(
					read.problems.firstOrNull()?.let(AliasProblem::Wrong) ?: AliasProblem.Empty,
					loops = false,
				)
			else -> Resolved.Made(Alias(name, text, read))
		}
	}
}
