package prism.app

import kotlin.io.path.Path
import prism.cli.Args
import prism.cli.Command
import prism.cli.Outcome

/**
 * `prism launcher`: the launcher, shown by the prism already running (the daemon, which has its index to search and
 * what is installed read already), or, where none is, by this process, until it is put away. Asked for while it is
 * shown, it is put away: bound to one key in a window manager, that key shows it and hides it.
 *
 * It finds the applications installed, then the files the index holds, as what is typed into it says; Enter starts
 * or opens what the arrows are on, and its "Settings" opens a window of them (docs/launcher.md).
 */
internal object LaunchCommand :
	Command(
		"launcher",
		"show the launcher (applications, then files) in the prism already running, where one is; " +
			"put it away, where it is shown",
	),
	Served {
	private val isolated =
		flag(
			ExploreCommand.ISOLATED,
			help =
				"show it in a process of its own, whether or not a prism is already running",
		)
	private val indexing = Indexing(this)
	private val theme = themeOption()

	override suspend fun serve(args: Args, caller: Caller, instance: Instance): Outcome {
		instance.windows.with { it.launch(caller.activation) }

		return Outcome("")
	}

	override fun run(args: Args): Outcome {
		val home = Path(System.getProperty("user.home"))
		val own = args[isolated] || indexing.given(args) || args[theme] != Theme.Auto

		return opened(this, Request(listOf(name), Caller.here()), apart = own) {
			val (search, finder) = indexing.searchOf(args, home)

			Hosting(args[theme], decorated = false, search, finder)
		}
	}
}
