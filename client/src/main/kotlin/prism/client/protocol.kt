package prism.client

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.time.Duration
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
data class Caller(
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
data class Request(
	val words: List<String>,
	val caller: Caller,
)

/** What a prism answers another. */
sealed interface Reply {
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
data class InstancePlace(
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
object Wire {
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
				"the running prism is a different version (protocol $version, this one $VERSION)",
			)

		return when (val kind = input.readInt()) {
			DONE -> {
				val status = input.readInt()
				val out = text(input)

				Reply.Done(Ran(out, text(input), status))
			}
			UNABLE -> Reply.Unable(text(input))
			else -> throw IOException("unknown kind of reply ($kind)")
		}
	}

	private fun versionOf(input: DataInputStream): Int {
		if (input.readInt() != MARK)
			throw IOException("not a prism reply")

		return input.readInt()
	}

	private fun count(input: DataInputStream): Int =
		input.readInt().also {
			if (it !in 0..MOST)
				throw IOException("implausible count ($it)")
		}

	private fun text(out: DataOutputStream, text: String) {
		val bytes = text.encodeToByteArray()

		out.writeInt(bytes.size)
		out.write(bytes)
	}

	private fun text(input: DataInputStream): String {
		val length = input.readInt()

		if (length !in 0..LONGEST)
			throw IOException("text too long ($length bytes)")

		return ByteArray(length).also(input::readFully).decodeToString()
	}
}

/** What asking the session's prism came to. */
sealed interface Asking {
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
fun ask(
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
