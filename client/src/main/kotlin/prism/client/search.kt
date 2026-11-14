package prism.client

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import prism.cli.Args
import prism.cli.Command
import prism.cli.InputError
import prism.cli.Outcome
import prism.cli.ParamType
import prism.cli.Ran

/*
 * `prism search`: a query in the search's own language (docs/search.md), run from a shell, and the paths it finds,
 * best first, a line each. It is the picker's search of everything without the picker: what a script reads, what
 * `cd` is given, what a person chooses from on the terminal.
 *
 * A shell's folder is the shell's own, which no program it starts can change: so a search only ever prints, and
 * `--select` is how it prints exactly one path or nothing, for a function of the shell's to go to (docs/shell.md).
 *
 * Nothing here searches. The index is the daemon's, kept current, so the running prism is asked for what it holds
 * ([RunningPrism], the plumbing command `prism matches`); where none is running, or the one that is keeps no index,
 * whoever made the command says what is done instead ([Seeker]): the Java prism reads the index's file, and the
 * program compiled ahead of time hands its words to the Java prism.
 */

/** What a search is asked for: a query's [text], and which of what it finds, the [limit] from the [from]th on. */
data class Sought(
	val text: String,
	val from: Int = 0,
	val limit: Int,
)

/** What a search came to. */
sealed interface Sifted {
	/** Of [total] found, these [paths], best first. */
	data class Found(
		val total: Int,
		val paths: List<String>,
	) : Sifted

	/** The query cannot be searched for: what is wrong with it, a line each. */
	data class Wrong(
		val problems: List<String>,
	) : Sifted

	/** There is no index to search: [why], and what would make one. */
	data class NoIndex(
		val why: String,
	) : Sifted
}

/** Something that searches an index. */
fun interface Seeker {
	fun seek(sought: Sought): Sifted
}

/**
 * How what a search came to is said by one prism to another: what `prism matches` prints, and what is made of it.
 * What was found is the count of everything found on the first line, then the paths, a NUL between them, since a
 * path may have anything else in it. What is wrong with a query, a line each, and why there is no index, are
 * printed in its place, each with a status of its own.
 */
object Matches {
	/** The command a running prism is asked: `prism matches --from N --limit N -- QUERY`. */
	const val COMMAND = "matches"
	const val FROM = "--from"
	const val LIMIT = "--limit"

	/** The status of a query that cannot be searched for, and of there being no index. */
	const val WRONG = 2
	const val NO_INDEX = 4

	private const val BETWEEN = '\u0000'

	/** The words that ask for [sought]. */
	fun words(sought: Sought): List<String> =
		listOf(COMMAND, FROM, "${sought.from}", LIMIT, "${sought.limit}", "--", sought.text)

	/** [sifted] as `prism matches` says it. */
	fun said(sifted: Sifted): Ran =
		when (sifted) {
			is Sifted.Found ->
				Ran("${sifted.total}\n" + sifted.paths.joinToString(BETWEEN.toString()), "", 0)
			is Sifted.Wrong -> Ran(sifted.problems.joinToString("\n"), "", WRONG)
			is Sifted.NoIndex -> Ran(sifted.why, "", NO_INDEX)
		}

	/** What [ran], which `prism matches` said, says was found; null where it is nothing this reads. */
	fun read(ran: Ran): Sifted? =
		when (ran.status) {
			0 -> {
				val total = ran.out.substringBefore('\n').toIntOrNull()
				val paths = ran.out.substringAfter('\n', "")

				total?.let {
					Sifted.Found(
						it,
						if (paths.isEmpty())
							emptyList()
						else
							paths.split(BETWEEN),
					)
				}
			}
			WRONG -> Sifted.Wrong(ran.out.lines().filter { it.isNotEmpty() })
			NO_INDEX -> Sifted.NoIndex(ran.out)
			else -> null
		}
}

/**
 * The prism listening at [place], asked what it finds: null where none listens there, or the one that does keeps no
 * index, or says nothing this reads (another version's), which is for whoever asks to do it some other way.
 */
class RunningPrism(
	private val place: InstancePlace? = InstancePlace.of(),
	private val caller: () -> Caller = { Caller.here() },
) {
	fun seek(sought: Sought): Sifted? =
		place
			?.let { ask(it, Request(Matches.words(sought), caller())) as? Asking.Answered }
			?.let { it.reply as? Reply.Done }
			?.let { Matches.read(it.ran) }
			?.takeUnless { it is Sifted.NoIndex }
}

/** Which of what a search finds is wanted, as its options say. */
internal sealed interface Wanted {
	/** The first [most], printed. */
	data class Listed(
		val most: Int,
	) : Wanted

	/** The one match there is (`--select`): more than one is a mistake of whoever asked, and said to be. */
	data object Only : Wanted

	/**
	 * One, chosen from a menu of the first [most] (`--interactive`). Where [alone] (`--select` too), a search that
	 * finds one thing gives it with no menu to choose it from.
	 */
	data class Chosen(
		val most: Int,
		val alone: Boolean,
	) : Wanted
}

/** What a search came to, for what was wanted of it. */
internal sealed interface Came {
	/** These paths, to be printed. */
	data class Paths(
		val paths: List<String>,
	) : Came

	/** Nothing matches. */
	data object Nothing : Came

	/** One was wanted, and [total] match: the [first] few of them, to show why. */
	data class Several(
		val total: Int,
		val first: List<String>,
	) : Came

	/** The menu was left with nothing chosen. */
	data object Cancelled : Came

	/** The query cannot be searched for: what is wrong with it, a line each. */
	data class Wrong(
		val problems: List<String>,
	) : Came
}

/**
 * `prism search QUERY...`, which asks [running] and, where that has no answer, [otherwise]. What it prints goes
 * through [out] as it is (a path a line, or a NUL after each), and what it says to the person through [err]; one of
 * several paths is chosen by [choose].
 */
class SearchCommand(
	private val running: (Sought) -> Sifted? = RunningPrism()::seek,
	private val otherwise: Seeker,
	private val out: (String) -> Unit = { System.out.print(it) },
	private val err: (String) -> Unit = { System.err.println(it) },
	private val choose: (
		List<String>,
	) -> String? = { paths -> chosenFrom(paths, IN_SIGHT) { it } },
) : Command(
		"search",
		"print the paths the index holds that fit a query (docs/search.md), the best first: " +
			"prism search report .pdf -draft",
	) {
	private val words =
		arguments(
			"QUERY",
			ParamType.TEXT,
			help =
				"the query, in the search's language; its words are joined with spaces, " +
					"and one with a space in it is a quoted term. A word that starts with " +
					"a dash is the query's (-draft: not draft) unless it is exactly one " +
					"of the options below. Where the words have a -- among them, the " +
					"query is what follows it, and what is before it is options alone, " +
					"which can then be run together (prism search -si -- report -g)",
		)
	private val best = flag("-b", "--best", help = "only the best match")
	private val count =
		option(
			"-n",
			"--count",
			type = ParamType.COUNT,
			default = COUNT,
			help = "how many matches at most",
		)
	private val all = flag("-a", "--all", help = "every match, however many")
	private val interactive =
		flag(
			"-i",
			"--interactive",
			help =
				"choose one of the matches from a menu on the terminal ($IN_SIGHT in " +
					"sight; the arrows or j and k move, Enter chooses, Esc cancels), " +
					"and print it",
		)
	private val select =
		flag(
			"-s",
			"--select",
			help =
				"print the match where there is exactly one; with more than one, say so " +
					"on standard error, print nothing, and end with status $SEVERAL. " +
					"With -i, more than one are chosen among from the menu, and one is " +
					"printed without it: prism search -si -- report is always one path, " +
					"or none",
		)
	private val files = flag("--files", help = "files only: is:file")
	private val folders = flag("--dirs", help = "folders only: is:dir")
	private val nul =
		flag(
			"-0",
			"--null",
			help = "a NUL after each path in place of a new line, for xargs -0",
		)

	override val dashedArguments: Boolean get() = true

	// A mistake in its words is, to a script, what a query that cannot be searched for is: nothing was looked for.
	override val mistaken: Int get() = Matches.WRONG

	override fun run(args: Args): Outcome {
		if (args[words].isEmpty())
			throw InputError("what to search for is missing")

		val text =
			(
				args[words].map(::termOf) +
					listOfNotNull("is:file".takeIf { args[files] }, "is:dir".takeIf { args[folders] })
			).joinToString(" ")

		val most =
			when {
				args[best] -> 1
				args[all] -> Int.MAX_VALUE
				else -> args[count]
			}

		val wanted =
			when {
				// The best is one match whatever else is asked: nothing is left to choose, or to be too many.
				args[best] -> Wanted.Listed(1)
				args[interactive] -> Wanted.Chosen(most, alone = args[select])
				args[select] -> Wanted.Only
				else -> Wanted.Listed(most)
			}

		val end = if (args[nul]) "\u0000" else "\n"

		return when (val came = came(text, wanted)) {
			is Came.Paths -> {
				out(came.paths.joinToString("") { it + end })
				Outcome("")
			}
			Came.Nothing -> {
				// Said where one path was asked for, and a person or a script is waiting for it.
				if (wanted !is Wanted.Listed)
					err("prism search: nothing matches")

				Outcome("", NOTHING)
			}
			is Came.Several -> {
				err(
					"prism search: ${"%,d".format(came.total)} matches, and --select takes " +
						"one: narrow it, take the best (-b), or choose (-i)",
				)
				came.first.forEach(err)
				if (came.total > came.first.size)
					err("… and ${"%,d".format(came.total - came.first.size)} more")
				Outcome("", SEVERAL)
			}
			Came.Cancelled -> Outcome("", CANCELLED)
			is Came.Wrong -> {
				came.problems.forEach { err("prism search: $it") }
				Outcome("", Matches.WRONG)
			}
		}
	}

	/** What a search for [text] comes to, for what is [wanted] of it. */
	private fun came(text: String, wanted: Wanted): Came {
		// One is wanted, and whether there is one is all that is asked: the first few, to say which, and how many.
		val asked =
			when (wanted) {
				is Wanted.Listed -> wanted.most
				Wanted.Only -> SHOWN_OF_SEVERAL
				is Wanted.Chosen -> wanted.most
			}

		val found =
			when (val sifted = sifted(text, asked)) {
				is Sifted.Found -> sifted
				is Sifted.Wrong -> return Came.Wrong(sifted.problems)
				is Sifted.NoIndex -> throw InputError(sifted.why)
			}

		if (found.paths.isEmpty())
			return Came.Nothing

		val alone = found.total == 1

		return when (wanted) {
			is Wanted.Listed -> Came.Paths(found.paths)
			Wanted.Only ->
				if (alone)
					Came.Paths(found.paths.take(1))
				else
					Came.Several(found.total, found.paths)
			is Wanted.Chosen ->
				if (alone && wanted.alone)
					Came.Paths(found.paths.take(1))
				else
					try {
						choose(found.paths)?.let { Came.Paths(listOf(it)) } ?: Came.Cancelled
					} catch (none: NoTerminal) {
						throw InputError(none.why)
					}
		}
	}

	/**
	 * The first [wanted] of what [text] finds: asked for a page at a time, since a prism answers no more at once than
	 * a command line could hold, and every match there is can be more than that.
	 */
	private fun sifted(text: String, wanted: Int): Sifted {
		tailrec fun paged(had: List<String>): Sifted {
			val sought = Sought(text, had.size, minOf(PAGE, wanted - had.size))

			return when (val page = running(sought) ?: otherwise.seek(sought)) {
				is Sifted.Found -> {
					val have = had + page.paths

					if (page.paths.isEmpty() || have.size >= minOf(wanted, page.total))
						Sifted.Found(page.total, have)
					else
						paged(have)
				}
				else -> page
			}
		}

		return paged(emptyList())
	}

	companion object {
		/** How many matches are printed, unless told otherwise. */
		const val COUNT = 20

		/** How many matches a menu has in sight. */
		const val IN_SIGHT = 8

		/** How many matches are asked for at once. */
		internal const val PAGE = 20_000

		/** How many of several matches are shown where one was wanted: enough to see why there is not one. */
		internal const val SHOWN_OF_SEVERAL = 5

		/** The statuses of nothing found, of more than one where one was wanted, and of a menu cancelled. */
		const val NOTHING = 1
		const val SEVERAL = 3
		const val CANCELLED = 130

		/**
		 * A word of the command line as a term of the query. A shell has taken a word's quotes by the time it is
		 * here, so one with a space in it (`"tax return"`, `dir:"old photos"`) is quoted again: after its `-`, `=`
		 * or `^` and its modifier's name, where it has them. A word with quotes of its own is left as it is.
		 */
		internal fun termOf(word: String): String {
			if (word.none { it.isWhitespace() } || '"' in word)
				return word

			// A modifier's name is followed at once by its value: `re: notes` is a name, not a modifier.
			val before = Regex("^[-=^]?([a-z]+:(?=\\S))?").find(word)?.value.orEmpty()

			return before + '"' + word.removePrefix(before) + '"'
		}
	}
}
