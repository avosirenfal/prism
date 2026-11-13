package prism.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import prism.cli.Args
import prism.cli.Command
import prism.cli.InputError
import prism.cli.Outcome
import prism.cli.ParamType
import prism.portal.pathOf

/**
 * `prism explore`, and `prism` with no command: the file explorer, on a folder. Where a prism with windows is already
 * running (the daemon, or one started before this), that one opens the window and this one is done at once, so a
 * window costs no start of a Java program; where none is, this one opens it, is the session's prism for as long as it
 * has a window open, and ends when its last is closed. `--isolated` keeps it to itself either way.
 */
internal object ExploreCommand :
	Command(
		"explore",
		"open the file explorer on a folder (your home, unless named): in the prism already running, where one is",
	),
	Served {
	private val items =
		arguments(
			"ITEM",
			ParamType.TEXT.named("FOLDER|FILE|URI"),
			help =
				"what to show, each in a window: a folder; or a file, shown in its folder, " +
					"chosen; or either as a file:// URI",
		)

	private val isolated =
		flag(
			"--isolated",
			help =
				"open it in a process of its own, whether or not a prism is already running, " +
					"which no other prism then opens its windows in",
		)
	private val indexing = Indexing(this)
	private val theme = themeOption()
	private val decorated = decoratedFlag("the window's menu or Ctrl+W closes it")

	/** What [args] ask to be shown, as [caller] meant them: paths read from the folder they were said in. */
	private fun shownBy(args: Args, caller: Caller, home: Path): List<Path> =
		args[items]
			.map { word ->
				val path =
					if (word.startsWith("file:"))
						pathOf(word) ?: throw InputError("$word is not a file:// URI of a path")
					else
						caller.resolved(Path(word))

				if (!Files.exists(path))
					throw InputError("$word does not exist")

				path
			}.ifEmpty { listOf(home) }

	override suspend fun serve(args: Args, caller: Caller, instance: Instance): Outcome {
		val shown = shownBy(args, caller, Path(System.getProperty("user.home")))

		instance.windows.with { explorer ->
			shown.forEach {
				explorer.show(it, caller.activation)
			}
		}

		return Outcome("")
	}

	override fun run(args: Args): Outcome {
		val home = Path(System.getProperty("user.home"))

		// What is said of how its windows are is said of a prism of its own: a running one's are as it was started.
		val own =
			args[isolated] || indexing.given(args) || args[theme] != Theme.Auto || args[decorated]

		return opened(
			this,
			Request(listOf(name, "--") + args[items], Caller.here()),
			apart = own,
		) {
			val (search, finder) = indexing.searchOf(args, home)

			Hosting(args[theme], args[decorated], search, finder)
		}
	}
}

/**
 * `prism status`: what the prism already running says of itself: what it is, its index and whether the kernel is
 * telling it of changes, its windows. Status 1, and that none is, where none is running.
 */
internal object StatusCommand :
	Command(
		"status",
		"say what the prism already running is doing: its index, whether changes are watched, its windows",
	),
	Served {
	override suspend fun serve(args: Args, caller: Caller, instance: Instance): Outcome =
		Outcome(instance.describe().joinToString("\n"))

	override fun run(args: Args): Outcome {
		val place =
			InstancePlace.of()
				?: return Outcome(
					"XDG_RUNTIME_DIR names no folder a running prism could be asked in.",
					1,
				)

		return when (val asked = ask(place, Request(listOf(name), Caller.here()))) {
			Asking.Nobody -> Outcome("No prism is running.", 1)
			is Asking.Answered ->
				when (val reply = asked.reply) {
					is Reply.Done -> Outcome(reply.ran.out, reply.ran.status)
					is Reply.Unable -> Outcome("A prism is running, and cannot say: ${reply.why}.", 1)
				}
		}
	}
}
