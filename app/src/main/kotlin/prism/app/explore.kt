package prism.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import prism.browser.Action
import prism.cli.Args
import prism.cli.Command
import prism.cli.InputError
import prism.cli.Outcome
import prism.cli.ParamType
import prism.client.Asking
import prism.client.Caller
import prism.client.InstancePlace
import prism.client.Reply
import prism.client.Request
import prism.client.ask
import prism.platform.OwnFolders
import prism.platform.Runtime
import prism.portal.pathOf

/**
 * `prism explore`, and `prism` with no command: the file explorer, on a folder: the one it was asked from, where none
 * is named (the asker's own, sent with what it asks to the prism that answers). Where a prism with windows is already
 * running (the daemon, or one started before this), that one opens the window and this one is done at once, so a
 * window costs no start of a Java program; where none is, this one opens it, is the session's prism for as long as it
 * has a window open, and ends when its last is closed. `--isolated` keeps it to itself either way.
 */
internal object ExploreCommand :
	Command(
		"explore",
		"open folders in the file explorer (default the current folder)",
	),
	Served {
	private val items =
		arguments(
			"ITEM",
			ParamType.TEXT.named("FOLDER|FILE|URI"),
			help =
				"a folder to open, or a file to show selected in its folder; a path or a " +
					"file:// URI, one window each",
		)

	private val isolated =
		flag(
			ISOLATED,
			help = "open in a separate process, even if a prism is already running",
		)
	private val indexing = Indexing(this)
	private val theme = themeOption()
	private val decorated =
		decoratedFlag(
			"close the window from its menu or with ${Action.CloseWindow.defaults.first()}",
		)

	/** The word that keeps a prism to a process of its own, which a desktop entry's action gives it. */
	const val ISOLATED = "--isolated"

	/**
	 * What [args] ask to be shown, as [caller] meant them: paths read from the folder they were said in, and that
	 * folder itself where they name none.
	 */
	private fun shownBy(args: Args, caller: Caller): List<Path> =
		args[items]
			.map { word ->
				val path =
					if (word.startsWith("file:"))
						pathOf(word) ?: throw InputError("$word is not a valid file:// URI")
					else
						caller.resolved(Path(word))

				if (!Files.exists(path))
					throw InputError("$word does not exist")

				path
			}.ifEmpty { listOf(caller.folder) }

	override suspend fun serve(args: Args, caller: Caller, instance: Instance): Outcome {
		val shown = shownBy(args, caller)

		instance.windows.with { explorer ->
			shown.forEach {
				explorer.show(it, caller.activation)
			}
		}

		return Outcome("")
	}

	/** What is said of how its windows are is said of a prism of its own: a running one's are as it was started. */
	override fun apart(args: Args): Boolean =
		args[isolated] || indexing.given(args) || args[theme] != Theme.Auto || args[decorated]

	override fun run(args: Args): Outcome {
		val home = Path(System.getProperty("user.home"))

		return opened(
			this,
			Request(listOf(name, "--") + args[items], Caller.here()),
			apart = apart(args),
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
		"show the running prism's index, file watching and windows",
	),
	Served {
	override suspend fun serve(args: Args, caller: Caller, instance: Instance): Outcome =
		Outcome(instance.describe().joinToString("\n"))

	override fun run(args: Args): Outcome {
		val place =
			when (val runtime = OwnFolders.of().runtime) {
				is Runtime.Missing -> return Outcome(
					"${runtime.why}, so a running prism can't be reached.",
					1,
				)
				is Runtime.At -> InstancePlace.within(runtime.folder.path)
			}

		return when (val asked = ask(place, Request(listOf(name), Caller.here()))) {
			Asking.Nobody -> Outcome("No prism is running.", 1)
			is Asking.Answered ->
				when (val reply = asked.reply) {
					is Reply.Done -> Outcome(reply.ran.out, reply.ran.status)
					is Reply.Unable ->
						Outcome(
							"A prism is running but couldn't answer: ${reply.why}.",
							1,
						)
				}
		}
	}
}
