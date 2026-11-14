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

/**
 * `prism search QUERY...`, which asks [running] and, where that has no answer, [otherwise]. What it prints goes
 * through [out] as it is (a path a line, or a NUL after each), and what it says to the person through [err]; one of
 * several paths is chosen by [choose]. [environment] is where a shell's wrapper says how it wants to be told where to
 * go ([GOTO_FILE]).
 */
class SearchCommand(
	private val running: (Sought) -> Sifted? = RunningPrism()::seek,
	private val otherwise: Seeker,
	private val environment: Map<String, String> = System.getenv(),
	private val out: (String) -> Unit = { System.out.print(it) },
	private val err: (String) -> Unit = { System.err.println(it) },
	private val choose: (
		List<String>,
	) -> String? = { paths -> chosenFrom(paths, IN_SIGHT) { it } },
	private val isFolder: (Path) -> Boolean = { Files.isDirectory(it) },
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
					"of the options below; write such a term after -- " +
					"(prism search report -- -g)",
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
	private val goto =
		flag(
			"-g",
			"--goto",
			help =
				"print the folder to go to in place of the match: the match, if it is a " +
					"folder, or the folder it is in. With more than one match (and no " +
					"-b or -i) they are listed on standard error, with status " +
					"$SEVERAL. A shell changes folder itself: " +
					"eval \"\$(prism shell zsh)\" makes prism search -g do it",
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

	override fun run(args: Args): Outcome {
		val text =
			(
				args[words].map(::termOf) +
					listOfNotNull("is:file".takeIf { args[files] }, "is:dir".takeIf { args[folders] })
			).joinToString(" ")

		if (args[words].isEmpty())
			throw InputError("what to search for is missing")

		val wanted =
			when {
				args[best] -> 1
				args[all] -> Int.MAX_VALUE
				else -> args[count]
			}

		val found =
			when (val sifted = sifted(text, wanted)) {
				is Sifted.Found -> sifted
				is Sifted.Wrong -> {
					sifted.problems.forEach { err("prism search: $it") }

					return Outcome("", Matches.WRONG)
				}
				is Sifted.NoIndex -> throw InputError(sifted.why)
			}

		val end = if (args[nul]) "\u0000" else "\n"

		/** [path] printed; or, where a folder is what was asked for, the folder to go to for it. */
		fun given(path: String): Outcome {
			if (!args[goto]) {
				out(path + end)

				return Outcome("")
			}

			val folder =
				Path(path)
					.let {
						if (isFolder(it))
							it
						else
							it.parent ?: it
					}.toString()

			// A shell's wrapper reads where to go from a file it named, so that everything else printed is the
			// user's to read; without one, the folder is what is printed.
			when (val file = environment[GOTO_FILE]?.takeIf { it.isNotEmpty() }) {
				null -> out(folder + end)
				else ->
					try {
						Files.writeString(Path(file), folder)
					} catch (failed: IOException) {
						throw InputError("$file cannot be written (${failed.message ?: failed})")
					}
			}

			return Outcome("")
		}

		return when {
			found.paths.isEmpty() -> {
				if (args[goto] || args[interactive])
					err("prism search: nothing matches")

				Outcome("", NOTHING)
			}
			args[interactive] ->
				try {
					choose(found.paths)?.let(::given) ?: Outcome("", CANCELLED)
				} catch (none: NoTerminal) {
					throw InputError(none.why)
				}
			args[goto] && found.paths.size > 1 -> {
				err(
					"prism search: ${"%,d".format(found.total)} matches, so nowhere to go: " +
						"narrow it, take the best (-b), or choose (-i)",
				)
				found.paths.forEach(err)
				Outcome("", SEVERAL)
			}
			args[goto] -> given(found.paths.single())
			else -> {
				out(found.paths.joinToString("") { it + end })
				Outcome("")
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

		/** The statuses of nothing found, of more than one place to go, and of a menu cancelled. */
		const val NOTHING = 1
		const val SEVERAL = 3
		const val CANCELLED = 130

		/**
		 * The variable a shell's wrapper names a file in, for `--goto` to write where to go into: the shell reads it
		 * and changes folder, which no program it starts can do for it (shell.kt).
		 */
		const val GOTO_FILE = "PRISM_GOTO_FILE"

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
