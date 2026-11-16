package prism.app

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import prism.browser.Action
import prism.browser.Search
import prism.cli.Args
import prism.cli.Command
import prism.cli.InputError
import prism.cli.Outcome
import prism.cli.ParamType
import prism.daemon.Daemon
import prism.daemon.DaemonSettings
import prism.daemon.LiveIndex
import prism.daemon.LiveSearch
import prism.dbus.Connection
import prism.dbus.Opening
import prism.desktop.asChildOfPrism
import prism.index.Exclusion
import prism.index.IndexStore
import prism.index.Indexer
import prism.index.Usage
import prism.index.UsageFile
import prism.index.query.QueryText
import prism.macos.DiskAccess
import prism.macos.FULL_DISK_ACCESS_REFUSED
import prism.macos.fullDiskAccess
import prism.platform.Os
import prism.platform.OwnFolders
import prism.preferences.preferences
import prism.shared.Loaded
import prism.shared.Stored
import prism.shared.orElse

/**
 * `prism daemon`: the one prism that stays. It keeps the index current (the watcher started first, then the index read
 * from its file and its roots read again, or built, and every change the watcher sees made in it as it is seen), and
 * it is the session's prism: every window another prism is started for opens here, as do the portal's pickers and
 * what a file manager is asked to show, each searching the index as it is at that moment. With no window open it
 * goes on; nothing that goes wrong in a window, or in all of them, stops the index being kept (host.kt, report.kt).
 *
 * Run from a terminal, it starts itself apart from the terminal and returns, saying where what it says is written;
 * `--foreground` runs it in place, saying it on the terminal, which is how a service manager runs it (systemd keeps
 * what it says in the journal) and how it is looked at when something is wrong.
 */
object DaemonCommand : Command(
	"daemon",
	"keep the index up to date and open windows for other prism commands and the portal, until stopped",
) {
	private val roots =
		arguments(
			"PATH",
			ParamType.FOLDER,
			help = "folders to index (default /)",
		)

	private val foreground =
		flag(
			"--foreground",
			help =
				"run in this terminal and log to it until stopped, as a service manager " +
					"would; without it, the daemon starts in the background and this command returns",
		)

	private val file =
		option(
			"--file",
			type = ParamType.PATH,
			default = defaultIndexFile(),
			help = "index file",
			shown = defaultIndex.said,
		)

	private val memory =
		flag(
			"--in-memory",
			help = "keep the index in memory only and never write it",
		)

	private val noWindows =
		flag(
			"--no-windows",
			help =
				"only keep the index: open no windows for other prism commands or the " +
					"session bus",
		)

	private val noChooser =
		flag(
			"--no-file-chooser",
			help = "don't serve the portal's file chooser",
		)
	private val noManager = noManagerFlag()
	private val replace = replaceFlag()
	private val theme = themeOption()
	private val decorated =
		decoratedFlag(
			"close a window from its menu or with ${Action.CloseWindow.defaults.first()}",
		)

	private val seconds =
		option(
			"--for",
			type = ParamType.COUNT,
			help =
				"stop after N seconds; implies --foreground",
		)

	private val queries =
		repeated(
			"--find",
			type = ParamType.TEXT.named("QUERY"),
			help =
				"run a search query once the folders have been read; implies --foreground",
		)

	override fun run(args: Args): Outcome {
		val file =
			if (args[memory])
				null
			else
				args[file].toAbsolutePath().normalize()

		// What says something on the terminal as it goes is run on the terminal.
		return if (args[foreground] || args[seconds] != null || args[queries].isNotEmpty())
			kept(args, file)
		else
			startedApart(file)
	}

	/** The daemon, run in this process until it is stopped. */
	private fun kept(args: Args, file: Path?): Outcome {
		val home = Path(System.getProperty("user.home"))

		// One daemon to an index's file: two would each write over what the other wrote.
		val lock =
			file?.let {
				IndexLock.take(it)
					?: throw InputError(
						"a prism daemon is already using $it; see prism status",
					)
			}

		val settings =
			DaemonSettings(
				file = file,
				roots =
					args[roots].map { it.toAbsolutePath().normalize() }.ifEmpty {
						listOf(
							Path("/"),
						)
					},
				indexer = Indexer(rules = exclusionsNow().ignore),
				report = Report::whileDoing,
			)

		val began = System.nanoTime()
		val daemon = Daemon.start(settings)
		val closed = AtomicBoolean()

		// Written however the process ends, short of being killed outright: stopped by its service manager, ended
		// by something it could not go on from (report.kt), or done.
		fun close() {
			if (closed.compareAndSet(false, true)) {
				daemon.close()
				lock?.close()
				say(
					"stopped: ${"%,d".format(
						daemon.live.index.size,
					)} entries, ${settings.file?.let { "written to $it" } ?: "not saved"}",
				)
			}
		}

		Runtime.getRuntime().addShutdownHook(Thread(::close))

		say("$STARTED (process ${ProcessHandle.current().pid()})")
		if (Os.current == Os.MacOS && fullDiskAccess() == DiskAccess.Refused)
			warn(this, FULL_DISK_ACCESS_REFUSED)
		say(watchingLine(daemon, settings))
		say("reading ${settings.roots.joinToString()} (${originOf(daemon.live.origin)})")
		daemon.live.opened.whenCompleteAsync { _, failed ->
			// What it failed with is said by the daemon; what it holds is said either way.
			val reading = (System.nanoTime() - began).nanoseconds

			say(
				"${if (failed == null)
					"finished reading"
				else
					"finished reading, with errors,"} in ${millis(reading)}: " +
					"${"%,d".format(daemon.live.index.size)} entries",
			)
			args[queries].forEach { found(daemon.live, it) }
		}

		val stopped = CountDownLatch(1)

		// The rules of what is left out, changed in a settings window: the index made to agree, as it runs.
		Thread.ofPlatform().name("prism-rules").daemon().start {
			runBlocking {
				preferencesHere.preferences
					.map { it.excluding }
					.distinctUntilChanged()
					.drop(1)
					.collect { daemon.ruled(exclusionsNow().ignore) }
			}
		}

		val windows =
			if (args[noWindows])
				null
			else
				Windows(
					Hosting(
						args[theme],
						args[decorated],
						Search.Ready(),
						finderOf(
							daemon.live,
							home,
							file != null,
						),
					),
					Lasting.UntilStopped,
				)

		args[seconds]?.let { limit ->
			Thread.ofPlatform().name("prism-for").daemon().start {
				Thread.sleep(limit * 1000L)
				windows?.stop()
				stopped.countDown()
			}
		}

		if (windows == null) {
			stopped.await()
		} else {
			val instance =
				Instance(
					windows,
					"prism daemon (process ${ProcessHandle.current().pid()}), running since " +
						LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
				)

			instance.says += { indexLine(daemon, settings) }
			instance.says += { watchingLine(daemon, settings) }
			instance.says += { keptLine(daemon) }
			instance.says += { rulesLine() }
			if (Os.current == Os.MacOS)
				instance.says += { accessLine() }

			val names =
				BusNames(
					chooser = !args[noChooser],
					manager = !args[noManager],
					replace = args[replace],
				)

			val bus =
				if (names.any)
					when (val opening = runBlocking { Connection.session() }) {
						is Opening.Opened -> opening.connection
						is Opening.Failed ->
							null.also {
								warn(
									this,
									"no session bus (${opening.message}), so prism can't act as the " +
										"file chooser or file manager; other prism commands still open " +
										"their windows here",
								)
							}
					}
				else
					null

			try {
				stayed(this, instance, names, home, bus)
			} finally {
				bus?.close()
			}
		}

		close()

		return Outcome(
			"${"%,d".format(
				daemon.live.index.size,
			)} entries, ${settings.file?.let {
				"written to $it"
			} ?: "not saved (in memory only)"}",
		)
	}

	/** What the daemon's windows search: its index as it is, ranked by what was picked, which is kept where [written]. */
	private fun finderOf(live: LiveIndex, home: Path, written: Boolean): LiveFinder {
		val file = UsageFile.default()
		val read = UsageFile.read(file)

		if (read is Loaded.Unreadable)
			warn(this, "can't read the usage file (${read.why}); results are ranked without it")

		val usage =
			if (written)
				UsageFile.open(file) {
					Report.problem("writing what was picked to $file failed", it)
				}
			else
				Stored.inMemory(read.orElse(Usage.NONE))

		return LiveFinder(live, home, usage) { exclusionsNow().hiding }
	}

	/** How the index was opened, as the daemon says it as it starts. */
	private fun originOf(origin: IndexStore.Origin): String =
		when (origin) {
			IndexStore.Origin.Memory -> "in memory only"
			IndexStore.Origin.Missing -> "no index yet, building one"
			is IndexStore.Origin.Unreadable ->
				"can't read the existing index (${origin.why}), rebuilding it"
			is IndexStore.Origin.Read ->
				"index loaded from its file" +
					(
						if (origin.torn)
							", dropping a truncated last frame"
						else
							""
					) +
					"; rescanning for changes since"
		}

	private fun indexLine(daemon: Daemon, settings: DaemonSettings): String {
		val reading = daemon.live.reading

		return "index: ${"%,d".format(
			daemon.live.index.size,
		)} entries under ${settings.roots.joinToString()}, " +
			(settings.file?.let { "saved in $it" } ?: "in memory only") +
			if (reading.isEmpty())
				""
			else
				"; reading ${reading.joinToString()} now"
	}

	/** How many rules leave things out, as `prism status` says it. */
	private fun rulesLine(): String {
		val rules = exclusionsNow()

		return "rules: ${rules.count(Exclusion.Ignored)} not indexed, " +
			"${rules.count(Exclusion.Hidden)} hidden from searches"
	}

	/** Whether macOS lets the daemon read everything, as `prism status` says it on a Mac. */
	private fun accessLine(): String =
		when (fullDiskAccess()) {
			DiskAccess.Granted -> "disk access: Full Disk Access, so every folder can be read"
			DiskAccess.Refused -> "disk access: no Full Disk Access. $FULL_DISK_ACCESS_REFUSED"
			DiskAccess.Unknown -> "disk access: macOS does not say if there is Full Disk Access"
		}

	/** The file systems that are not mounted and whose entries are kept for when they are. */
	private fun keptLine(daemon: Daemon): String {
		val kept = daemon.live.kept

		return "unmounted file systems: " +
			if (kept.isEmpty())
				"none kept"
			else
				"${kept.size} kept until mounted again (" +
					kept.joinToString { "${it.at}, ${"%,d".format(it.entries)} entries" } + ")"
	}

	private fun watchingLine(daemon: Daemon, settings: DaemonSettings): String =
		when (val now = daemon.watching) {
			Daemon.Watching.On ->
				"changes: watched through ${daemon.through} and applied to the index as they happen"
			is Daemon.Watching.Off ->
				"changes: not watched (${now.why}); rescanning every " +
					"${settings.rescanWithoutWatcher.inWholeMinutes} minutes instead"
		}

	/**
	 * The daemon started apart from this terminal, as this same command line with `--foreground`, what it says
	 * written to a file; and this returns once it has said it started, or says what it said instead where it did not.
	 */
	private fun startedApart(file: Path?): Outcome {
		if (file != null && IndexLock.isHeld(file))
			return Outcome(
				"A prism daemon is already running, using $file. See prism status.",
			)

		val launcher =
			Self.launcher()
				?: throw InputError(
					"can't tell how prism was started, so it can't start in the background; use --foreground",
				)

		val log = stateFolder().resolve("daemon.log")

		try {
			Files.createDirectories(log.parent)
			// Kept to a size: what was said before the last few megabytes is kept once, beside it.
			if (Files.exists(log) && Files.size(log) > LOG_KEPT)
				Files.move(
					log,
					log.resolveSibling("daemon.log.old"),
					StandardCopyOption.REPLACE_EXISTING,
				)
		} catch (failed: IOException) {
			throw InputError(
				"can't write $log (${failed.message ?: failed}); use --foreground",
			)
		}

		val from = if (Files.exists(log)) Files.size(log) else 0L

		// A session of its own, so that closing the terminal closes nothing of the daemon's: setsid's on Linux. A Mac has
		// no setsid, and nohup keeps the hang-up of a terminal that closes from it, which is what would end it.
		val detacher = if (Os.current == Os.MacOS) "nohup" else "setsid"
		val detach =
			System
				.getenv("PATH")
				.orEmpty()
				.split(':')
				.map { Path(it, detacher) }
				.firstOrNull { Files.isExecutable(it) }

		if (detach == null)
			warn(
				this,
				"$detacher not found, so the daemon will stop when this terminal closes",
			)

		val process =
			ProcessBuilder(
				listOfNotNull(detach?.toString()) + launcher + Self.words + "--foreground",
			)
				// As this process was started, not as its launcher then arranged things for it: the launcher does again.
				.asChildOfPrism()
				.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
				.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
				.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
				.start()

		fun said(): String =
			try {
				FileChannel.open(log, StandardOpenOption.READ).use { channel ->
					val bytes =
						java.nio.ByteBuffer.allocate(
							(channel.size() - from).coerceIn(0, LOG_SHOWN).toInt(),
						)

					channel.read(bytes, from)
					String(bytes.array(), 0, bytes.position())
				}
			} catch (unread: IOException) {
				""
			}

		val until = System.nanoTime() + STARTS_WITHIN.inWholeNanoseconds

		while (System.nanoTime() < until) {
			if (STARTED in said())
				return Outcome(
					"prism daemon is running (process ${process.pid()}). Its log is $log.",
				)
			if (!process.isAlive)
				throw InputError(
					"the daemon failed to start (status ${process.exitValue()}):\n${said().trimEnd()}",
				)
			Thread.sleep(100)
		}

		return Outcome(
			"prism daemon (process ${process.pid()}) is still starting. Its log is $log.",
			1,
		)
	}

	/** [text] searched for, waiting for places it looks under that the rules leave out to be read for it. */
	private fun found(live: LiveIndex, text: String) {
		val read = QueryText.read(text, aliasesNow())

		read.problems.forEach {
			say("$text: ${it.kind.name.lowercase()} at ${it.span}: ${it.message}")
		}

		val first = live.search(read.query)
		val searched =
			if (first is LiveSearch.Found && first.reading.isNotEmpty()) {
				say("$text: reading ${first.reading.joinToString()} into memory for this search")
				first.ready.get()
				live.search(read.query)
			} else {
				first
			}

		when (searched) {
			is LiveSearch.Refused -> say("$text: ${searched.why}")
			is LiveSearch.Found -> {
				say("$text: ${"%,d".format(searched.results.size)} found")
				searched.results.take(5).forEach { say("  ${it.where}") }
			}
		}
	}

	private fun say(line: String) {
		println(line)
		System.out.flush()
	}

	private fun millis(elapsed: Duration): String =
		"%.1f ms".format(elapsed.inWholeMicroseconds / 1000.0)

	/** What the daemon says first, once it is keeping its index: what starting it apart waits to read. */
	private const val STARTED = "prism daemon: started"

	/** How long a daemon started apart is waited for to say it started. */
	private val STARTS_WITHIN = 30.seconds

	/** How much of what a daemon started apart has said is kept in its file, and how much of it is shown. */
	private const val LOG_KEPT = 4L shl 20
	private const val LOG_SHOWN = 1L shl 16
}

/**
 * Where what prism keeps of its own doing is, the usage and what a daemon started from a terminal says
 * ([OwnFolders.state]): `$XDG_STATE_HOME/prism` (`~/.local/state/prism`) on Linux, `~/Library/Application
 * Support/prism` on macOS.
 */
internal fun stateFolder(): Path = OwnFolders.of().state.path

/**
 * That this process is the one keeping an index's file: a lock on a file beside it, which the kernel lets go of when
 * the process ends, however it ends. Two daemons keeping one file would each write over what the other wrote.
 */
internal class IndexLock private constructor(
	private val held: FileLock,
) : AutoCloseable {
	override fun close() {
		if (held.isValid)
			held.release()
		held.channel().close()
	}

	companion object {
		/** The lock on [index]'s file, taken; null where another process has it. */
		fun take(index: Path): IndexLock? {
			val beside = index.resolveSibling("${index.fileName}.lock")

			return try {
				Files.createDirectories(beside.parent)

				val channel =
					FileChannel.open(
						beside,
						StandardOpenOption.CREATE,
						StandardOpenOption.WRITE,
					)

				val held =
					try {
						channel.tryLock()
					} catch (ours: OverlappingFileLockException) {
						null
					}

				held?.let(::IndexLock) ?: null.also { channel.close() }
			} catch (failed: IOException) {
				throw InputError("can't lock $beside (${failed.message ?: failed})")
			}
		}

		/** Whether another process is keeping [index]'s file. */
		fun isHeld(index: Path): Boolean = take(index)?.also(IndexLock::close) == null
	}
}
