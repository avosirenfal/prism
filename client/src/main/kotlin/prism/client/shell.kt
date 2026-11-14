package prism.client

import prism.cli.Args
import prism.cli.Command
import prism.cli.Outcome
import prism.cli.ParamType

/*
 * A shell's folder is the shell's own: no program it starts can change it. So `prism search --goto` only says where
 * to go, and what makes the shell go there is a function of the shell's, which `prism shell` prints for it to read
 * once, as it starts (`eval "$(prism shell zsh)"`, as zoxide and direnv are set up).
 *
 * The function is called `prism`, and stands before the program: anything but a search it passes on untouched. For a
 * search it names a file for prism to write where to go into ([SearchCommand.GOTO_FILE]), and goes there after, if
 * anything was written. A file, and not what the search prints, so that whatever else it prints (its matches, a menu,
 * its mistakes) reaches the terminal as it would without the function, and the search need not be told apart by its
 * words.
 */

/** The shells a function is written for. */
enum class Shell(
	/** What the user adds to the file their shell reads as it starts, to have the function. */
	val setup: String,
	val function: String,
) {
	Bash("""eval "$(prism shell bash)"""", POSIX),
	Zsh("""eval "$(prism shell zsh)"""", POSIX),
	Fish(
		"prism shell fish | source",
		"""
		function prism --wraps prism --description 'prism, whose search --goto changes folder'
			if test "${'$'}argv[1]" != search
				command prism ${'$'}argv
				return
			end
			set -l __prism_goto (mktemp); or return
			${SearchCommand.GOTO_FILE}=${'$'}__prism_goto command prism ${'$'}argv
			set -l __prism_status ${'$'}status
			if test -s ${'$'}__prism_goto
				cd -- (cat ${'$'}__prism_goto); or set __prism_status ${'$'}status
			end
			rm -f ${'$'}__prism_goto
			return ${'$'}__prism_status
		end
		""".trimIndent(),
	),
	;

	companion object {
		val TYPE = ParamType.choice(entries) { it.name.lowercase() }
	}
}

/** The function for the shells that read the Bourne shell's language: bash and zsh. */
private val POSIX =
	"""
	prism() {
		if [ "${'$'}1" != search ]; then
			command prism "${'$'}@"
			return
		fi
		local __prism_goto __prism_status
		__prism_goto="${'$'}(mktemp)" || return
		${SearchCommand.GOTO_FILE}="${'$'}__prism_goto" command prism "${'$'}@"
		__prism_status=${'$'}?
		if [ -s "${'$'}__prism_goto" ]; then
			cd -- "${'$'}(cat "${'$'}__prism_goto")" || __prism_status=${'$'}?
		fi
		rm -f "${'$'}__prism_goto"
		return ${'$'}__prism_status
	}
	""".trimIndent()

/** `prism shell SHELL`: the function that lets `prism search --goto` change that shell's folder. */
object ShellCommand : Command(
	"shell",
	"print a shell's function that makes prism search --goto change folder: " +
		"eval \"\$(prism shell zsh)\" in the file your shell reads as it starts",
) {
	private val shell =
		argument(
			"SHELL",
			Shell.TYPE,
			help = Shell.entries.joinToString(", ") { "${it.name.lowercase()} (${it.setup})" },
		)

	override fun run(args: Args): Outcome = Outcome(args[shell].function)
}
