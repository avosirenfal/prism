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
import kotlinx.coroutines.runBlocking
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
import prism.index.IndexStore
import prism.index.Indexer
import prism.index.UsageFile
import prism.index.UsageRead
import prism.index.query.QueryText

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
	"keep the index current, and open every prism's windows, the portal's pickers among them, until stopped",
) {
	private val roots =
		arguments(
			"PATH",
			ParamType.FOLDER,
			help = "the folders to index (/ unless named)",
		)

	private val foreground =
		flag(
			"--foreground",
			help =
				"run here, saying what it does on this terminal, until stopped: how a service " +
					"manager runs it, and how to watch it. Without it, the daemon is started apart " +
					"from the terminal, and this returns",
		)

	private val file =
		option(
			"--file",
			type = ParamType.PATH,
			default = defaultIndexFile(),
			help = "the index's file",
			shown = "\$XDG_CACHE_HOME/prism/index.prism",
		)

	private val memory =
		flag(
			"--in-memory",
			help = "keep the index in memory alone, never written",
		)

	private val noWindows =
		flag(
			"--no-windows",
			help =
				"keep the index and nothing else: open no windows, for other prisms or for the " +
					"session bus",
		)

	private val noChooser =
		flag(
			"--no-file-chooser",
			help = "leave the portal's file chooser to another backend, and do not serve it",
		)
	private val noManager = noManagerFlag()
	private val replace = replaceFlag()
	private val theme = themeOption()
	private val decorated = decoratedFlag("the window's menu or Ctrl+W closes it")

	private val seconds =
		option(
			"--for",
			type = ParamType.COUNT,
			help =
				"stop after this many seconds (run until stopped without it); runs in the " +
					"foreground",
		)

	private val queries =
		repeated(
			"--find",
			type = ParamType.TEXT.named("QUERY"),
			help =
				"a query in the search language, searched once the roots are read; runs in the " +
					"foreground",
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
						"a prism daemon is already keeping $it; prism status says how it is",
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
				indexer = Indexer(),
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
					)} entries, ${settings.file?.let { "written to $it" } ?: "kept nowhere"}",
				)
			}
		}

		Runtime.getRuntime().addShutdownHook(Thread(::close))

		say("$STARTED (process ${ProcessHandle.current().pid()})")
		say(watchingLine(daemon, settings))
		say("reading ${settings.roots.joinToString()} (${originOf(daemon.live.origin)})")
		daemon.live.opened.whenCompleteAsync { _, failed ->
			// What it failed with is said by the daemon; what it holds is said either way.
			val reading = (System.nanoTime() - began).nanoseconds

			say(
				"${if (failed == null)
					"read"
				else
					"could not all be read,"} in ${millis(reading)}: " +
					"${"%,d".format(daemon.live.index.size)} entries",
			)
			args[queries].forEach { found(daemon.live, it) }
		}

		val stopped = CountDownLatch(1)

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
									"there is no session bus (${opening.message}), so prism is not the " +
										"file chooser or the file manager; other prisms still open their " +
										"windows here",
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
			)} entries, written to ${settings.file ?: "nowhere (in memory)"}",
		)
	}

	/** What the daemon's windows search: its index as it is, ranked by what was picked, which is kept where [written]. */
	private fun finderOf(live: LiveIndex, home: Path, written: Boolean): LiveFinder {
		val usage = UsageFile.read(UsageFile.default())

		if (usage is UsageRead.Unreadable)
			warn(this, "the usage can't be read (${usage.why}); results are ranked without it")

		return LiveFinder(live, home, usage.usage, UsageFile.default().takeIf { written })
	}

	/** How the index was opened, as the daemon says it as it starts. */
	private fun originOf(origin: IndexStore.Origin): String =
		when (origin) {
			IndexStore.Origin.Memory -> "kept in memory alone"
			IndexStore.Origin.Missing -> "there is no index yet: it is built"
			is IndexStore.Origin.Unreadable ->
				"the index there cannot be read (${origin.why}): it is built anew"
			is IndexStore.Origin.Read ->
				"the index was read from its file" +
					(
						if (origin.torn)
							", its last frame, cut short, dropped"
						else
							""
					) +
					"; read again for what changed since"
		}

	private fun indexLine(daemon: Daemon, settings: DaemonSettings): String {
		val reading = daemon.live.reading

		return "index: ${"%,d".format(
			daemon.live.index.size,
		)} entries under ${settings.roots.joinToString()}, " +
			(settings.file?.let { "kept in $it" } ?: "in memory alone") +
			if (reading.isEmpty())
				""
			else
				"; reading ${reading.joinToString()} now"
	}

	private fun watchingLine(daemon: Daemon, settings: DaemonSettings): String =
		when (val now = daemon.watching) {
			Daemon.Watching.On ->
				"changes: watched through ${daemon.through}, each made in the index as the kernel makes it"
			is Daemon.Watching.Off ->
				"changes: not watched (${now.why}); in its place, the roots are read again every " +
					"${settings.rescanWithoutWatcher.inWholeMinutes} minutes"
		}

	/**
	 * The daemon started apart from this terminal, as this same command line with `--foreground`, what it says
	 * written to a file; and this returns once it has said it started, or says what it said instead where it did not.
	 */
	private fun startedApart(file: Path?): Outcome {
		if (file != null && IndexLock.isHeld(file))
			return Outcome(
				"A prism daemon is already running, keeping $file. prism status says how it is.",
			)

		val launcher =
			Self.launcher()
				?: throw InputError(
					"what started this prism cannot be told, to start it again; run it with --foreground",
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
				"$log cannot be written (${failed.message ?: failed}); run it with --foreground",
			)
		}

		val from = if (Files.exists(log)) Files.size(log) else 0L

		// A session of its own, so that closing the terminal closes nothing of the daemon's.
		val setsid =
			System
				.getenv("PATH")
				.orEmpty()
				.split(':')
				.map { Path(it, "setsid") }
				.firstOrNull { Files.isExecutable(it) }

		if (setsid == null)
			warn(
				this,
				"there is no setsid to start it in a session of its own: it ends when this terminal does",
			)

		val process =
			ProcessBuilder(
				listOfNotNull(setsid?.toString()) + launcher + Self.words + "--foreground",
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
					"prism daemon is running (process ${process.pid()}). What it says is in $log.",
				)
			if (!process.isAlive)
				throw InputError(
					"it did not start (status ${process.exitValue()}). It said:\n${said().trimEnd()}",
				)
			Thread.sleep(100)
		}

		return Outcome(
			"prism daemon (process ${process.pid()}) has not said it started yet. What it says is in $log.",
			1,
		)
	}

	/** [text] searched for, waiting for places it looks under that the rules leave out to be read for it. */
	private fun found(live: LiveIndex, text: String) {
		val read = QueryText.read(text)

		read.problems.forEach {
			say("$text: ${it.kind.name.lowercase()} at ${it.span}: ${it.message}")
		}

		val first = live.search(read.query)
		val searched =
			if (first is LiveSearch.Found && first.reading.isNotEmpty()) {
				say("$text: reading ${first.reading.joinToString()} for searches alone, in memory")
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
 * `$XDG_STATE_HOME/prism` (`~/.local/state/prism`): where what prism keeps of its own doing is, the usage and what a
 * daemon started from a terminal says.
 */
internal fun stateFolder(): Path =
	(
		System.getenv("XDG_STATE_HOME")?.takeIf { it.startsWith("/") }?.let(::Path)
			?: Path(System.getProperty("user.home"), ".local", "state")
	).resolve("prism")

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
				throw InputError("$beside cannot be locked (${failed.message ?: failed})")
			}
		}

		/** Whether another process is keeping [index]'s file. */
		fun isHeld(index: Path): Boolean = take(index)?.also(IndexLock::close) == null
	}
}
