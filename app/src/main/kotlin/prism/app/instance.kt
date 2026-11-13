package prism.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import prism.cli.Ran

/*
 * One prism for a session's windows. Starting a Java program takes a moment that opening a window does not, so a
 * prism that has windows open listens for others, and a prism started to open a window asks it to, and is done: the
 * window opens in the process already running, as a file manager's windows do (Thunar's daemon, by D-Bus; Emacs's
 * server, by a socket as here).
 *
 * The place is a socket in the user's runtime folder, `$XDG_RUNTIME_DIR/prism/prism.sock`, which is the user's alone
 * (mode 0700, as the session makes it), gone at logout, and where sockets of a session are kept (Wayland's own is
 * there). Who listens there is whoever holds the lock beside it (`prism.lock`): a lock the kernel lets go of when its
 * process ends, however it ends, so a socket left by a prism that was killed is known to be nobody's, and is taken.
 *
 * What is asked is a command line: its words, the folder they were said in (a path among them is read from there, not
 * from where the listening prism happens to be), and the little of the asker's environment an answer needs (the
 * desktop's token for giving a new window the keyboard). What is answered is what running the words printed and their
 * exit status, as the asker then prints and exits with; or that it cannot be done here, and why, and the asker does it
 * itself. So whatever a prism cannot do for another, for any reason, costs only the start it would have cost anyway.
 */

/** Who asks a prism for something: where their words are read from, and what of their environment an answer needs. */
internal data class Caller(
	/** The folder the words were said in: what a path among them that does not start at the root is under. */
	val folder: Path,
	/** Of the asker's environment, the variables in [CARRIED] that it has. */
	val environment: Map<String, String>,
) {
	/** [path] as the asker meant it: under the folder they said it in, where it does not start at the root. */
	fun resolved(path: Path): Path = folder.resolve(path).normalize()

	/** The desktop's token for giving a window opened for the asker the keyboard, where it gave them one. */
	val activation: String? get() = environment[ACTIVATION]?.takeIf { it.isNotEmpty() }

	companion object {
		/** What a launcher gives a program it starts, for the window it opens to be given the keyboard (Wayland's). */
		const val ACTIVATION = "XDG_ACTIVATION_TOKEN"

		/** The variables of an asker's environment that go with what it asks: nothing else of it is another's to use. */
		val CARRIED = listOf(ACTIVATION, "DESKTOP_STARTUP_ID")

		/** This process, asking for itself. */
		fun here(environment: Map<String, String> = System.getenv()): Caller =
			Caller(Path("").toAbsolutePath(), environment.filterKeys { it in CARRIED })
	}
}

/** What one prism asks another: a command line's [words] (after `prism`), and who asks. */
internal data class Request(
	val words: List<String>,
	val caller: Caller,
)

/** What a prism answers another. */
internal sealed interface Reply {
	/** The words were run: what they printed, and their exit status, which are the asker's to print and exit with. */
	data class Done(
		val ran: Ran,
	) : Reply

	/** They were not, and nothing came of them: [why], for the asker to say as it does them itself. */
	data class Unable(
		val why: String,
	) : Reply
}

/** Where a session's prism listens, and the lock that says who it is. */
internal data class InstancePlace(
	val socket: Path,
	val lock: Path,
) {
	companion object {
		/**
		 * The place in the runtime folder [environment] names; none where it names none (a session without one, a
		 * shell reached some other way), and then every prism is on its own.
		 */
		fun of(environment: Map<String, String> = System.getenv()): InstancePlace? =
			environment["XDG_RUNTIME_DIR"]
				?.takeIf { it.startsWith("/") }
				?.let { Path(it, "prism") }
				?.let(::within)

		/** The place kept in [folder]. */
		fun within(folder: Path): InstancePlace =
			InstancePlace(folder.resolve("prism.sock"), folder.resolve("prism.lock"))
	}
}

/**
 * How a request and its answer are written on the socket: a mark and a version first, both ways, so that a prism of
 * another version is known for one before anything else of it is read; then counts and lengths as four bytes, and
 * text as UTF-8. Nothing is read that is longer than a command line could be.
 */
internal object Wire {
	/** "PRSM". */
	private const val MARK = 0x5052534D

	/** What this prism writes, and reads: raised whenever what follows it changes. */
	const val VERSION = 1

	private const val DONE = 1
	private const val UNABLE = 2

	/** The most words, or variables, a request holds; and the longest any text of it or of an answer is, in bytes. */
	private const val MOST = 1 shl 16
	private const val LONGEST = 1 shl 24

	/** What reading a request gave: the request, or that its writer is a prism of another [version]. */
	sealed interface Asked {
		data class Read(
			val request: Request,
		) : Asked

		data class Other(
			val version: Int,
		) : Asked
	}

	fun write(out: DataOutputStream, request: Request) {
		out.writeInt(MARK)
		out.writeInt(VERSION)
		text(out, request.caller.folder.toString())
		out.writeInt(request.words.size)
		request.words.forEach { text(out, it) }
		out.writeInt(request.caller.environment.size)
		request.caller.environment.forEach { (name, value) ->
			text(out, name)
			text(out, value)
		}
		out.flush()
	}

	/** The request [input] holds. An [IOException] where it holds none: not a prism's, cut short, or too long. */
	fun asked(input: DataInputStream): Asked {
		val version = versionOf(input)

		if (version != VERSION)
			return Asked.Other(version)

		val folder = Path(text(input))
		val words = List(count(input)) { text(input) }
		val environment = List(count(input)) { text(input) to text(input) }.toMap()

		return Asked.Read(Request(words, Caller(folder, environment)))
	}

	/** [reply] written; in the one shape every version reads the start of, so that being unable can always be said. */
	fun write(out: DataOutputStream, reply: Reply) {
		out.writeInt(MARK)
		out.writeInt(VERSION)
		when (reply) {
			is Reply.Done -> {
				out.writeInt(DONE)
				out.writeInt(reply.ran.status)
				text(out, reply.ran.out)
				text(out, reply.ran.err)
			}
			is Reply.Unable -> {
				out.writeInt(UNABLE)
				text(out, reply.why)
			}
		}
		out.flush()
	}

	/** The answer [input] holds; one of another version's is that it was unable, whatever else it says. */
	fun reply(input: DataInputStream): Reply {
		val version = versionOf(input)

		if (version != VERSION)
			return Reply.Unable(
				"the prism already running is another version (its $version to this one's $VERSION)",
			)

		return when (val kind = input.readInt()) {
			DONE -> {
				val status = input.readInt()
				val out = text(input)

				Reply.Done(Ran(out, text(input), status))
			}
			UNABLE -> Reply.Unable(text(input))
			else -> throw IOException("an answer of no kind this reads ($kind)")
		}
	}

	private fun versionOf(input: DataInputStream): Int {
		if (input.readInt() != MARK)
			throw IOException("not a prism's")

		return input.readInt()
	}

	private fun count(input: DataInputStream): Int =
		input.readInt().also {
			if (it !in 0..MOST)
				throw IOException("a count no command line has ($it)")
		}

	private fun text(out: DataOutputStream, text: String) {
		val bytes = text.encodeToByteArray()

		out.writeInt(bytes.size)
		out.write(bytes)
	}

	private fun text(input: DataInputStream): String {
		val length = input.readInt()

		if (length !in 0..LONGEST)
			throw IOException("text longer than any command line's ($length bytes)")

		return ByteArray(length).also(input::readFully).decodeToString()
	}
}

/**
 * A prism listening at its [place] for others, each of whose requests [serve] answers, on a thread of that request's
 * own: until closed, when the socket is taken away and the lock let go for the next prism to take.
 */
internal class Listening private constructor(
	private val place: InstancePlace,
	private val held: FileLock,
	private val server: ServerSocketChannel,
	private val serve: (Request) -> Reply,
) : AutoCloseable {
	// The socket is taken away however the process ends, short of being killed outright: one left behind is known
	// for nobody's by the lock, and does no harm, but is not left where it need not be.
	private val atExit = Thread(::close)

	init {
		Runtime.getRuntime().addShutdownHook(atExit)
		Thread
			.ofPlatform()
			.name("prism-instance")
			.daemon()
			.start(::accept)
	}

	private fun accept() {
		while (server.isOpen) {
			val asker =
				try {
					server.accept()
				} catch (closed: IOException) {
					break
				}

			Thread
				.ofPlatform()
				.name("prism-request")
				.daemon()
				.start { answer(asker) }
		}
	}

	/** One asker answered: whatever answering throws is said, and answered as being unable, and nobody else's. */
	private fun answer(asker: SocketChannel) {
		try {
			asker.use {
				val input = DataInputStream(BufferedInputStream(Channels.newInputStream(it)))
				val out = DataOutputStream(BufferedOutputStream(Channels.newOutputStream(it)))

				val reply =
					when (val asked = Wire.asked(input)) {
						is Wire.Asked.Other ->
							Reply.Unable(
								"the prism already running is another version (its ${Wire.VERSION} to this one's ${asked.version})",
							)
						is Wire.Asked.Read ->
							try {
								serve(asked.request)
							} catch (failed: Throwable) {
								Report.problem(
									"answering prism ${asked.request.words.joinToString(" ")} failed",
									failed,
								)
								Reply.Unable("the prism already running failed at it: $failed")
							}
					}

				Wire.write(out, reply)
			}
		} catch (gone: IOException) {
			// The asker went, or was no prism: nobody is left to answer.
		}
	}

	@Synchronized
	override fun close() {
		if (!server.isOpen)
			return

		try {
			server.close()
			Files.deleteIfExists(place.socket)
		} catch (failed: IOException) {
			Report.problem("closing ${place.socket} failed", failed)
		} finally {
			// Let go last: whoever takes the lock next finds no socket of this one's to mistake for its own.
			if (held.isValid)
				held.release()
			held.channel().close()
			try {
				Runtime.getRuntime().removeShutdownHook(atExit)
			} catch (ending: IllegalStateException) {
				// Closed by the hook itself, as the process ends.
			}
		}
	}

	companion object {
		/**
		 * This prism listening at [place], where no other is; or that another holds the lock, which is then the
		 * session's prism, or about to be; or why the place cannot be listened at at all (a folder that cannot be
		 * made), which leaves this prism on its own.
		 */
		fun claim(place: InstancePlace, serve: (Request) -> Reply): Claim =
			claimed(place, serve) { it.tryLock() }

		/**
		 * This prism listening at [place] once no other is: waits for whoever holds the lock to end, however long.
		 * What a daemon does where a prism was started before it: that one's windows are its own until its last
		 * closes, and the daemon's are the session's from then on.
		 */
		fun await(place: InstancePlace, serve: (Request) -> Reply): Claim =
			claimed(place, serve) { it.lock() }

		private fun claimed(
			place: InstancePlace,
			serve: (Request) -> Reply,
			lock: (FileChannel) -> FileLock?,
		): Claim {
			val channel =
				try {
					// The user's alone, as the runtime folder it is in is.
					Files.createDirectories(
						place.lock.parent,
						PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
					)
					FileChannel.open(place.lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
				} catch (failed: IOException) {
					return Claim.Cannot("${place.lock} cannot be made (${failed.message ?: failed})")
				}

			return try {
				val held =
					try {
						lock(channel)
					} catch (ours: OverlappingFileLockException) {
						// Held by this very process, which the kernel does not count as another's.
						null
					} ?: return Claim.Held.also { channel.close() }

				// The lock is this prism's, so a socket there is one left by a prism that ended without taking it away.
				Files.deleteIfExists(place.socket)

				val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)

				server.bind(UnixDomainSocketAddress.of(place.socket))
				Claim.Got(Listening(place, held, server, serve))
			} catch (failed: IOException) {
				// Closing the channel lets go of the lock, where it was had.
				channel.close()
				Claim.Cannot("${place.socket} cannot be listened at (${failed.message ?: failed})")
			}
		}
	}
}

/** What trying to be the session's prism came to. */
internal sealed interface Claim {
	data class Got(
		val listening: Listening,
	) : Claim

	/** Another prism holds the place. */
	data object Held : Claim

	/** The place cannot be held by anyone: [why]. */
	data class Cannot(
		val why: String,
	) : Claim
}

/** What asking the session's prism came to. */
internal sealed interface Asking {
	/** It answered. */
	data class Answered(
		val reply: Reply,
	) : Asking

	/** Nobody listens: no socket, one nobody has open, or one that said nothing a prism says. */
	data object Nobody : Asking
}

/**
 * [request] asked of the prism listening at [place], waiting no longer than [patience] for its answer: a prism that
 * listens and does not answer (its windows' thread held up) is, to an asker, nobody.
 */
internal fun ask(
	place: InstancePlace,
	request: Request,
	patience: Duration = 20.seconds,
): Asking =
	try {
		SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
			channel.connect(UnixDomainSocketAddress.of(place.socket))

			// A blocking read has no timeout of its own: closing the channel from elsewhere is what ends one.
			val giveUp =
				CompletableFuture.runAsync(
					{ channel.close() },
					CompletableFuture.delayedExecutor(
						patience.inWholeMilliseconds,
						TimeUnit.MILLISECONDS,
					),
				)

			try {
				Wire.write(
					DataOutputStream(BufferedOutputStream(Channels.newOutputStream(channel))),
					request,
				)
				Asking.Answered(
					Wire.reply(DataInputStream(BufferedInputStream(Channels.newInputStream(channel)))),
				)
			} finally {
				giveUp.cancel(false)
			}
		}
	} catch (nobody: IOException) {
		Asking.Nobody
	}

/** How a prism that would open windows stands to the session's prism. */
internal sealed interface Standing {
	/** There was one, and it answered. */
	data class Asked(
		val reply: Reply,
	) : Standing

	/**
	 * There is none that answers, so this prism opens its own windows: [listening] for others where it could take the
	 * place, and on its own where it could not.
	 */
	data class Alone(
		val listening: Listening?,
	) : Standing
}

/**
 * [request] asked of the session's prism, or this prism made it. Asking and taking the place are tried in turn for
 * [patience], since between a prism taking the lock and its listening is a moment in which it is neither answering nor
 * out of the way: two prisms started at once end as one that listens and one that asked it, not as two on their own.
 */
internal fun stand(
	place: InstancePlace,
	request: Request,
	patience: Duration = 3.seconds,
	serve: (Request) -> Reply,
): Standing {
	val until = System.nanoTime() + patience.inWholeNanoseconds

	while (true) {
		(ask(place, request) as? Asking.Answered)?.let { return Standing.Asked(it.reply) }
		when (val claim = Listening.claim(place, serve)) {
			is Claim.Got -> return Standing.Alone(claim.listening)
			is Claim.Cannot -> {
				Report.say("${claim.why}, so this prism is on its own")

				return Standing.Alone(null)
			}
			// Held by one that did not answer: starting, most likely, and asked again in a moment.
			Claim.Held ->
				if (System.nanoTime() > until) {
					Report.say(
						"the prism at ${place.socket} does not answer, so this prism is on its own",
					)

					return Standing.Alone(null)
				}
		}
		Thread.sleep(RETRY.inWholeMilliseconds)
	}
}

/** How long between tries of [stand]. */
private val RETRY = 40.milliseconds
