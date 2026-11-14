package prism.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import prism.browser.BrowserState
import prism.browser.Finder
import prism.browser.JvmDisk
import prism.browser.JvmFolderWatcher
import prism.browser.Search
import prism.browser.SystemFileClipboard
import prism.cli.Args
import prism.cli.Command
import prism.cli.InputError
import prism.cli.Invocation
import prism.cli.Outcome
import prism.cli.Ran
import prism.cli.invocation
import prism.client.Caller
import prism.client.InstancePlace
import prism.client.Reply
import prism.client.Request
import prism.client.ask
import prism.dbus.Connection
import prism.desktop.Desktop
import prism.explorer.Explorer
import prism.explorer.ExplorerWindows
import prism.launcher.LaunchFile
import prism.portal.FileChooser
import prism.portal.FileManager
import prism.portal.Owning
import prism.portal.Showing
import prism.portal.own
import prism.portal.serveFileChooser
import prism.portal.serveFileManager
import prism.preferences.PreferenceFile
import prism.preferences.PreferenceStore
import prism.window.NoDisplay
import prism.window.WindowsFailed
import prism.window.application
import prism.browser.Request as Wanted

/*
 * A prism with windows. Whatever opens them (`prism explore`, `prism portal serve`, `prism daemon`), they are opened
 * one way: one explorer, on the windows' thread, asked for windows by whoever wants one ([Windows.with]): this
 * process's own command, another prism through the socket ([Instance]), an application through the portal, a file
 * manager's caller through the bus ([serveOnBus]). The three commands differ only in what the windows are opened with
 * ([Hosting]: the index they search), how long they last ([Lasting]), and what else the process does (a daemon keeps
 * an index).
 *
 * What goes wrong in a window is that window's (the explorer closes it and says so). What goes wrong in a request is
 * that request's: whoever asked is told. What goes wrong in the windows themselves, every one of them at once (the
 * composition they are all opened from, or the display going away), ends the windows and not the process, where the
 * process is one that stays: they are started again for the next that is asked for, and, where they keep failing,
 * given up, and every asker told so, who then opens a window of their own. A daemon's index goes on through all of it.
 */

/** What a prism's windows are opened with: the same for every window of the process. */
internal data class Hosting(
	val theme: Theme = Theme.Auto,
	/** Whether the window manager draws each window a title bar. */
	val decorated: Boolean = false,
	/** How a window's search of the index starts: ready, or saying why there is none. */
	val search: Search = Search.Unavailable(BrowserState.NO_INDEX),
	/** What a search searches; none where there is no index. */
	val finder: Finder? = null,
)

/** How long a prism's windows last. */
internal enum class Lasting {
	/** Until the last of them is closed, once what the prism was started for has been done: `prism explore`. */
	UntilLastCloses,

	/** Until the prism is stopped, with windows or without: the daemon, and `prism portal serve`. */
	UntilStopped,
}

/** There are no windows to do it with, and why: what an asker is told, to do it some other way. */
internal class NoWindows(
	why: String,
) : RuntimeException(why)

/**
 * The windows of one prism: run on the thread that calls [run] (which is theirs until they end), and asked for from
 * any thread by [with]. Until [run] is called, and between one display and the next, what is asked waits.
 */
internal class Windows(
	private val hosting: Hosting,
	private val lasting: Lasting,
	private val home: Path = Path(System.getProperty("user.home")),
) {
	/** Something asked of the explorer, and its answer; a request no longer wanted is not begun, or is stopped. */
	private class Ask<T>(
		private val work: suspend (Explorer) -> T,
	) {
		val answer = CompletableDeferred<T>()
		private val running = AtomicReference<Job?>()

		@Volatile
		private var unwanted = false

		suspend fun run(explorer: Explorer) {
			if (unwanted)
				return

			running.set(currentCoroutineContext()[Job])
			try {
				answer.complete(work(explorer))
			} catch (stopped: CancellationException) {
				answer.completeExceptionally(NoWindows("prism's windows stopped before it was done"))

				throw stopped
			} catch (failed: Throwable) {
				// The asker's to hear of, and nobody else's: the windows go on.
				answer.completeExceptionally(failed)
			}
		}

		fun cancel() {
			unwanted = true
			running.get()?.cancel()
		}

		fun refuse(why: String) {
			answer.completeExceptionally(NoWindows(why))
		}
	}

	/** How many requests have been done, and how many are being: what says a prism started for one thing has done it. */
	private data class Tally(
		val done: Int = 0,
		val doing: Int = 0,
	)

	private val asks = Channel<Ask<*>>(Channel.UNLIMITED)
	private val stopping = CompletableDeferred<Unit>()

	/** Why there are no windows now, where there are none: said to `prism status`. */
	@Volatile
	private var unable: String? = "none has been asked for yet"

	/** Why there will be no more windows, once they have failed too often to be started again. */
	@Volatile
	private var givenUp: String? = null

	/** What a search of this prism's searches: its index; none, where it keeps none. */
	val finder: Finder? get() = hosting.finder

	/** The explorer, while the windows run. */
	@Volatile
	private var explorer: Explorer? = null

	/** How the windows are, in a few words. */
	fun describe(): String =
		explorer?.windows?.value?.size?.let { open ->
			when (open) {
				0 -> "none open"
				1 -> "1 open"
				else -> "$open open"
			}
		}
			?: "none open: ${givenUp ?: unable ?: "they are starting"}"

	/**
	 * [work] done with the explorer, on the windows' thread, and what it gives: once there are windows to do it with,
	 * which, where the display is not there yet, is when it is. [NoWindows] where there can be none; what [work]
	 * itself throws, as it threw it. An asker cancelled while it waits has what it asked stopped.
	 */
	suspend fun <T> with(work: suspend (Explorer) -> T): T {
		givenUp?.let { throw NoWindows(it) }

		val ask = Ask(work)

		asks.send(ask)

		return try {
			ask.answer.await()
		} catch (stopped: CancellationException) {
			ask.cancel()

			throw stopped
		}
	}

	/** The windows ended, and [run] with them: from any thread. */
	fun stop() {
		stopping.complete(Unit)
	}

	/**
	 * The windows, run on this thread until they end: [Lasting.UntilLastCloses], when the last is closed, and
	 * anything that fails them is thrown ([NoDisplay], [WindowsFailed]); [Lasting.UntilStopped], when [stop] is
	 * called, and whatever fails them meanwhile is reported, answered to whoever was asking, and started again from
	 * for the next that asks.
	 */
	fun run() {
		val desktop = windowsDesktop()

		PreferenceFile.open().use { preferences ->
			preferences.problem?.let {
				Report.say("the preferences can't be read ($it); every default is used meanwhile")
			}

			val staying = lasting == Lasting.UntilStopped
			val failures = ArrayDeque<Long>()
			var first: Ask<*>? = null

			while (!stopping.isCompleted) {
				try {
					shown(desktop, preferences, first)
					if (stopping.isCompleted || !staying)
						return

					// Ended by the platform, with nothing asking it to: the display went away.
					unable = "the display went away"
					Report.say("the display went away; windows are opened again when one is asked for")
				} catch (none: NoDisplay) {
					if (!staying)
						throw none

					val why = "there is no display to open windows on (${none.message})"

					// Said once for as long as it stays so, not at every request.
					if (unable != why)
						Report.say("$why; windows are opened once there is one, when one is asked for")
					unable = why
					first?.refuse(why)
					refuseWaiting(why)
				} catch (failed: WindowsFailed) {
					if (!staying)
						throw failed

					Report.problem(
						"prism's windows failed, every one of them; whatever else prism is doing goes on",
						failed.cause ?: failed,
					)
					unable = "they failed, and are started again when one is asked for"

					val now = System.nanoTime()

					failures.addLast(now)
					while (now - failures.first() > FAILING_WITHIN.inWholeNanoseconds)
						failures.removeFirst()
					if (failures.size >= FAILING_TOO_OFTEN) {
						val why =
							"the windows of the prism already running failed ${failures.size} times in " +
								"${FAILING_WITHIN.inWholeMinutes} minutes, and it has given up opening them"

						givenUp = why
						Report.say("$why; restart it to have them again")

						return refusing(why)
					}
				} finally {
					explorer = null
				}

				// With no windows to keep, nothing runs until one is asked for.
				first = awaited() ?: return
			}
		}
	}

	/** The next thing asked, waited for; null once the prism is stopped. */
	private fun awaited(): Ask<*>? =
		runBlocking {
			select {
				asks.onReceive { it }
				stopping.onAwait { null }
			}
		}

	/** Whatever is waiting, answered that there are no windows, and [why]. */
	private fun refuseWaiting(why: String) {
		generateSequence { asks.tryReceive().getOrNull() }.forEach { it.refuse(why) }
	}

	/** Everything asked from here on answered that there are no windows, and [why], until the prism is stopped. */
	private fun refusing(why: String) {
		while (true)
			(awaited() ?: return).refuse(why)
	}

	/**
	 * The windows, on this thread, until the application ends: [first] done as they open, then whatever is asked, as
	 * it is asked.
	 */
	private fun shown(desktop: Desktop, preferences: PreferenceStore, first: Ask<*>?) {
		Appearance.follow(hosting.theme).use { appearance ->
			application(APP_ID) {
				val scheme by appearance.changes.collectAsState(appearance.first)
				val scope = rememberCoroutineScope()

				val explorer =
					remember {
						Explorer(
							JvmDisk(),
							hosting.finder,
							desktop,
							SystemFileClipboard,
							preferences,
							scope,
							home,
							hosting.search,
							JvmFolderWatcher,
							Report::problem,
							LaunchFile(stateFolder().resolve("launches"), Report::problem),
						)
					}

				LaunchedEffect(explorer) {
					val tally = MutableStateFlow(Tally())

					suspend fun done(ask: Ask<*>) {
						tally.update { it.copy(doing = it.doing + 1) }
						try {
							ask.run(explorer)
						} finally {
							tally.update { Tally(it.done + 1, it.doing - 1) }
						}
					}

					this@Windows.explorer = explorer
					unable = null

					// Each request on its own: one that fails, or is cancelled, is no other's concern.
					supervisorScope {
						first?.let { launch { done(it) } }
						launch {
							for (ask in asks)
								launch { done(ask) }
						}
						launch {
							stopping.await()
							exitApplication()
						}
						if (lasting == Lasting.UntilLastCloses)
							launch {
								// Done once something was asked and done, nothing more is being, and no window is open.
								combine(tally, explorer.windows) { asked, open ->
									asked.done > 0 && asked.doing == 0 && open.isEmpty()
								}.first { it }
								exitApplication()
							}
					}
				}

				ExplorerWindows(explorer, paletteOf(hosting.theme, scheme), hosting.decorated)
			}
		}
	}

	private companion object {
		/** How many times the windows may fail, every one at once, within [FAILING_WITHIN], before they are given up. */
		const val FAILING_TOO_OFTEN = 3
		val FAILING_WITHIN = 2.minutes
	}
}

/** A command a prism that has windows carries out for another prism, which then need not start any of its own. */
internal interface Served {
	/** What this prism does for [args], asked for by [caller], with what it has ([instance]). */
	suspend fun serve(args: Args, caller: Caller, instance: Instance): Outcome

	/**
	 * Whether [args] ask for a prism of their own: said of how its windows should be (a theme, an index of its own),
	 * or told to keep to itself. One prism does not do those for another; whoever asked does them itself. What the
	 * client asks (prism.client, main.kt) is a command line's words as they were typed, these among them.
	 */
	fun apart(args: Args): Boolean = false
}

/** A prism with windows, as other prisms ask it. */
internal class Instance(
	val windows: Windows,
	/** What this prism is, in a line: what `prism status` says first. */
	private val what: String,
) {
	/** What else `prism status` says of this prism, a line each, as it is when asked. */
	val says = CopyOnWriteArrayList<() -> String>()

	/** What `prism status` prints. */
	fun describe(): List<String> =
		listOf(what) + says.map { it() } + "windows: ${windows.describe()}"

	/**
	 * [request] answered: its words read as this prism's own command line would be, and done, where they are a command
	 * one prism does for another ([Served]). Anything else is answered as being unable, and the asker does it itself:
	 * words this prism reads otherwise than the asker did (another version's), or windows it has not got.
	 */
	fun answer(request: Request): Reply =
		when (val asked = invocation(PRISM, request.words)) {
			is Invocation.Run -> {
				val served = asked.command as? Served
				val said = asked.path.joinToString(" ")

				try {
					if (served == null)
						Reply.Unable("$said is not something one prism does for another")
					else if (served.apart(asked.args))
						Reply.Unable("$said was asked to be a prism of its own")
					else
						runBlocking { served.serve(asked.args, request.caller, this@Instance) }.let {
							Reply.Done(Ran(it.text, "", it.status))
						}
				} catch (wrong: InputError) {
					Reply.Done(Ran("", "$said: ${wrong.message}", 1))
				} catch (none: NoWindows) {
					Reply.Unable(none.message ?: "the prism already running has no windows")
				}
			}
			is Invocation.Help, is Invocation.Wrong ->
				Reply.Unable(
					"the prism already running reads “${request.words.joinToString(
						" ",
					)}” otherwise than this one " +
						"(it is another version, most likely)",
				)
		}
}

/**
 * [request] carried out, and what it printed: by the session's prism, where there is one that will; otherwise by this
 * process, which opens its own windows ([hosting]) until the last is closed, and is the session's prism meanwhile.
 * [apart] is a prism told to keep to itself (`--isolated`): it asks nobody, and nobody asks it.
 */
internal fun opened(
	command: Command,
	request: Request,
	apart: Boolean,
	hosting: () -> Hosting,
): Outcome {
	val instance by lazy {
		Instance(
			Windows(hosting(), Lasting.UntilLastCloses),
			"prism ${command.name} (process ${ProcessHandle.current().pid()}): the session's " +
				"windows until its last is closed; no daemon is running, so no index is kept",
		)
	}

	val place = InstancePlace.of().takeUnless { apart }

	val standing =
		place?.let { stand(it, request) { asked -> instance.answer(asked) } }
			?: Standing.Alone(null)

	val listening =
		when (standing) {
			is Standing.Alone -> standing.listening
			is Standing.Asked ->
				when (val reply = standing.reply) {
					is Reply.Done -> return printed(reply.ran)
					is Reply.Unable -> {
						warn(command, "${reply.why}; opened in a prism of its own instead")
						null
					}
				}
		}

	// This prism's own request is done as another's would be: asked of the instance, once its windows run.
	val asking = CoroutineScope(Dispatchers.Default)

	return listening.use {
		val own = asking.async { instance.answer(request) }

		try {
			instance.windows.run()
		} catch (none: NoDisplay) {
			throw InputError("there is no display to open a window on (${none.message})")
		} catch (failed: WindowsFailed) {
			Report.problem("prism's windows failed", failed.cause ?: failed)

			return@use Outcome("", FAILED_STATUS)
		} finally {
			asking.cancel()
		}

		when (val reply = runBlocking { runCatching { own.await() }.getOrNull() }) {
			is Reply.Done -> printed(reply.ran)
			is Reply.Unable -> throw InputError(reply.why)
			// Ended before it was answered: closed, or stopped, as it opened.
			null -> Outcome("")
		}
	}
}

/** What another prism ran for this one, said as this one's own: its errors on standard error, the rest its outcome. */
private fun printed(ran: Ran): Outcome {
	if (ran.err.isNotEmpty())
		System.err.println(ran.err)

	return Outcome(ran.out, ran.status)
}

/** What a prism that stays serves on the session bus. */
internal data class BusNames(
	/** The portal's file chooser backend. */
	val chooser: Boolean,
	/** `org.freedesktop.FileManager1`. */
	val manager: Boolean,
	/** Whether a name another has is taken from it, where it lets it go. */
	val replace: Boolean,
) {
	val any: Boolean get() = chooser || manager
}

/**
 * The file chooser and the file manager served on [bus] with [windows], as [names] say: a picker for each request of
 * the portal's, and a window for each thing a file manager is asked to show. What is served is said to [instance]'s
 * status; a name that cannot be had is said through [warn], and left. Closing it stops both.
 */
internal fun serveOnBus(
	bus: Connection,
	windows: Windows,
	instance: Instance,
	names: BusNames,
	home: Path,
	warn: (String) -> Unit,
): BusServing {
	val desktop = windowsDesktop()
	val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	/** Whether this has [name] now; where it has not, why, said. */
	fun owned(name: String): Boolean =
		when (runBlocking { bus.own(name, names.replace) }) {
			Owning.Owned -> true
			Owning.Taken ->
				false.also { warn("another has $name; --replace takes it, where it lets it go") }
			Owning.Failed -> false.also { warn("the bus would not give $name") }
		}

	/** [serving] kept where [name] is this prism's, and closed where it is not. */
	fun AutoCloseable.named(name: String): AutoCloseable? =
		takeIf { owned(name) } ?: null.also { close() }

	// Served before the names are asked for: D-Bus hands a call that started this process to it the moment it has the
	// name, and a call before its method is served would be answered that there is no such method.
	val chooser =
		if (names.chooser)
			bus
				.serveFileChooser(scope) { request ->
					windows.with { explorer ->
						val asking = askingOf(request, desktop.types, home)

						pickedOf(request, asking, explorer.choose(asking), desktop.types)
					}
				}.named(FileChooser.BACKEND)
		else
			null

	val manager =
		if (names.manager)
			bus
				.serveFileManager { showing ->
					scope.launch {
						try {
							windows.with { it.show(showing) }
						} catch (none: NoWindows) {
							Report.say("asked to show ${showing.paths.joinToString()}, but ${none.message}")
						}
					}
				}.named(FileManager.NAME)
		else
			null

	instance.says += {
		"session bus: " +
			listOfNotNull(
				"the portal's file chooser".takeIf { chooser != null },
				"the file manager (${FileManager.NAME})".takeIf { manager != null },
			).ifEmpty { listOf("nothing") }.joinToString(" and ") + " served"
	}

	return BusServing(chooser != null, manager != null) {
		chooser?.close()
		manager?.close()
		scope.cancel()
	}
}

/** What of [BusNames] is being served, until closed. */
internal class BusServing(
	val chooser: Boolean,
	val manager: Boolean,
	private val stop: () -> Unit,
) : AutoCloseable {
	val any: Boolean get() = chooser || manager

	override fun close() = stop()
}

/** What a file manager is asked to show, shown: folders in windows, items chosen in theirs, or properties. */
internal fun Explorer.show(showing: Showing) {
	when (showing) {
		is Showing.Folders -> showing.paths.forEach { browse(it) }
		// Each folder once, with the first of its items asked for chosen in it.
		is Showing.Items ->
			showing.paths
				.groupBy { it.parent ?: it }
				.forEach { (folder, items) -> browse(folder, items.first().fileName?.toString()) }
		is Showing.Properties -> ask(Wanted.Properties(showing.paths))
	}
}

/**
 * [path] shown, as `prism explore` shows what it is given: a folder in a window, and anything else in its folder's,
 * chosen there; the window given the keyboard by [activation], where the desktop gave whoever asked a token for it.
 */
internal fun Explorer.show(path: Path, activation: String?) {
	if (Files.isDirectory(path))
		browse(path, activation = activation)
	else
		browse(path.parent ?: path, path.fileName?.toString(), activation)
}

/**
 * A prism that stays, run on this thread until it is stopped: [instance]'s windows, asked for through the socket by
 * other prisms and, where [names] says and there is a session bus, through the bus. Where another prism holds the
 * socket (one started before this, with windows open), this one waits for it to end and holds it from then on. A
 * prism that is for the bus alone ([needsBus]) and can serve nothing on it ends at once, saying so.
 */
internal fun stayed(
	command: Command,
	instance: Instance,
	names: BusNames,
	home: Path,
	bus: Connection?,
	needsBus: Boolean = false,
) {
	val closing = CopyOnWriteArrayList<AutoCloseable>()

	fun listening(listening: Listening) {
		closing += listening
		instance.says += { "other prisms: open their windows here" }
	}

	when (val place = InstancePlace.of()) {
		null ->
			warn(
				command,
				"XDG_RUNTIME_DIR names no folder to listen in, so other prisms open windows of their own",
			)
		else ->
			when (val claim = Listening.claim(place, instance::answer)) {
				is Claim.Got -> listening(claim.listening)
				is Claim.Cannot ->
					warn(
						command,
						"${claim.why}, so other prisms open windows of their own",
					)
				Claim.Held -> {
					warn(
						command,
						"another prism has the session's windows until its last is closed; this one has them from then",
					)
					Thread.ofPlatform().name("prism-instance-wait").daemon().start {
						(Listening.await(place, instance::answer) as? Claim.Got)?.let {
							listening(it.listening)
							Report.say("the other prism ended: this one has the session's windows now")
						}
					}
				}
			}
	}

	val serving =
		bus?.takeIf { names.any }?.let {
			serveOnBus(it, instance.windows, instance, names, home) { said -> warn(command, said) }
		}

	serving?.let(closing::add)
	if (needsBus && serving?.any != true) {
		warn(command, "nothing to serve")
		closing.forEach { runCatching(it::close) }

		return
	}

	try {
		instance.windows.run()
	} finally {
		closing.forEach { runCatching(it::close) }
	}
}
