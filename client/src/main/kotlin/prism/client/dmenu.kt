package prism.client

import kotlin.time.Duration
import prism.cli.Args
import prism.cli.Command
import prism.cli.Outcome
import prism.cli.Param
import prism.cli.ParamType

/*
 * `prism dmenu`: lines on standard input, shown in the launcher's window to choose one of, and the one chosen
 * printed: what dmenu does, and rofi's and fuzzel's and Vicinae's dmenu modes, so a script written for one of those
 * works with this. Its options are Vicinae's (`vicinae dmenu --help`), by the same names, so that a command line
 * written for that works here with the program's name changed; `-p` is also what dmenu itself calls its prompt.
 *
 * Nothing here shows a window. The lines are read here, since they are on this program's standard input and nobody
 * else's, and handed with the words to the prism already running ([Caller.input]), which shows them at once and
 * answers what was chosen, however long the choosing takes. Where no prism is running, whoever made the command
 * says what is done instead ([otherwise]): the Java prism shows them itself, and the program compiled ahead of time
 * hands its words, and the lines it read, to the Java prism.
 */

/** How `prism dmenu` prints what was chosen. */
enum class DmenuFormat {
	/** The line itself. */
	Data,

	/** Where the line is among those given, from 0, counting only the lines that have anything on them. */
	Index,
	;

	val word: String get() = name.lowercase()

	companion object {
		val TYPE = ParamType.choice(entries) { it.word }

		/** What is printed for text that was typed and is no line, where what is printed is where a line is. */
		const val NO_LINE = -1
	}
}

/** What `prism dmenu`'s options ask for. */
data class DmenuOptions(
	val placeholder: String? = null,
	val query: String = "",
	val section: String? = null,
	val title: String? = null,
	val format: DmenuFormat = DmenuFormat.Data,
	val width: Int? = null,
	val height: Int? = null,
	val footer: Boolean = true,
	val redacted: Boolean = false,
)

/**
 * `prism dmenu`, which asks the prism listening at [place] and, where that has no answer, [otherwise], which is
 * given what was asked: the words, and with them what [input] read from standard input.
 */
open class DmenuCommand(
	private val otherwise: (Request) -> Outcome,
	private val place: () -> InstancePlace? = { InstancePlace.of() },
	private val input: () -> String = { System.`in`.readBytes().decodeToString() },
	private val caller: () -> Caller = { Caller.here() },
	private val err: (String) -> Unit = { System.err.println(it) },
) : Command(
		NAME,
		"choose one line of standard input in the launcher and print it, as dmenu does",
	) {
	/** Each option that takes a value, by the name it is said back by ([words]). */
	private val valued = ArrayList<Pair<String, Param<Any?>>>()
	private val flags = ArrayList<Pair<String, Param<Boolean>>>()

	private fun <T> valued(
		short: String,
		long: String,
		type: ParamType<T>,
		help: String,
	): Param<T?> =
		option(short, long, type = type, help = help).also { valued += long to it }

	private fun flagged(long: String, help: String): Param<Boolean> =
		flag(long, help = help).also { flags += long to it }

	private val placeholder =
		valued(
			"-p",
			"--placeholder",
			ParamType.TEXT,
			"text shown in the search field while it is empty",
		)
	private val query = valued("-q", "--query", ParamType.TEXT, "initial search text")
	private val section =
		valued(
			"-s",
			"--section-title",
			ParamType.TEXT.named("TITLE"),
			"heading above the list; {count} is replaced by the number of lines shown",
		)
	private val title =
		valued(
			"-n",
			"--navigation-title",
			ParamType.TEXT.named("TITLE"),
			"title shown in the footer",
		)
	private val format =
		valued(
			"-f",
			"--format",
			DmenuFormat.TYPE,
			"what to print: the selected line (data, the default) or its position among the " +
				"non-empty lines, from 0 (index)",
		)
	private val width =
		valued("-W", "--width", ParamType.COUNT.named("PIXELS"), "window width")
	private val height =
		valued("-H", "--height", ParamType.COUNT.named("PIXELS"), "window height")
	private val noSection = flagged("--no-section", "don't show a heading above the list")
	private val noFooter = flagged("--no-footer", "hide the footer")
	private val password = flagged("--password", "show the search text as dots")

	/** What [args] ask for. */
	fun options(args: Args): DmenuOptions =
		DmenuOptions(
			placeholder = args[placeholder],
			query = args[query].orEmpty(),
			section = args[section].takeUnless { args[noSection] },
			title = args[title],
			format = args[format] ?: DmenuFormat.Data,
			width = args[width],
			height = args[height],
			footer = !args[noFooter],
			redacted = args[password],
		)

	/** The words that say [args] again, for another prism to read: each option given, with its value. */
	private fun words(args: Args): List<String> =
		listOf(name) +
			valued.mapNotNull { (name, param) ->
				args[param]?.let { "$name=${(it as? DmenuFormat)?.word ?: it}" }
			} +
			flags.mapNotNull { (name, param) -> name.takeIf { args[param] } }

	override fun run(args: Args): Outcome {
		val request = Request(words(args), caller().copy(input = input()))

		// Asked for as long as the choosing takes: a person is answering, and not a program.
		val reply =
			place()
				?.takeIf {
					request.caller.input
						.orEmpty()
						.length <
						Wire.LONGEST_INPUT / BYTES_A_CHARACTER
				}?.let { ask(it, request, patience = Duration.INFINITE) as? Asking.Answered }
				?.reply

		return when (reply) {
			is Reply.Done -> {
				if (reply.ran.err.isNotEmpty())
					err(reply.ran.err)

				Outcome(reply.ran.out, reply.ran.status)
			}
			is Reply.Unable, null -> otherwise(request)
		}
	}

	companion object {
		const val NAME = "dmenu"

		/** The status of a dmenu put away with nothing chosen, as dmenu's own is. */
		const val DISMISSED = 1

		/** The most bytes a character is written as. */
		private const val BYTES_A_CHARACTER = 4
	}
}
