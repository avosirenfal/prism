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
import prism.client.Asking
import prism.client.InstancePlace
import prism.client.Reply
import prism.client.Request
import prism.client.Wire
import prism.client.ask

/*
 * The session's prism, as the one that is it: listening at the place others ask at (the asking, and what is asked
 * and answered, are the client's: prism.client, protocol.kt), and how a prism that would open windows comes to be
 * it, or to ask the one that is.
 */

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
								"the running prism is a different version (protocol ${Wire.VERSION}, this one ${asked.version})",
							)
						is Wire.Asked.Read ->
							try {
								serve(asked.request)
							} catch (failed: Throwable) {
								Report.problem(
									"answering prism ${asked.request.words.joinToString(" ")} failed",
									failed,
								)
								Reply.Unable("the running prism failed: $failed")
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
					return Claim.Cannot("can't create ${place.lock} (${failed.message ?: failed})")
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
				Claim.Cannot("can't listen on ${place.socket} (${failed.message ?: failed})")
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
				Report.say("${claim.why}, so this prism runs on its own")

				return Standing.Alone(null)
			}
			// Held by one that did not answer: starting, most likely, and asked again in a moment.
			Claim.Held ->
				if (System.nanoTime() > until) {
					Report.say(
						"the prism at ${place.socket} isn't answering, so this prism runs on its own",
					)

					return Standing.Alone(null)
				}
		}
		Thread.sleep(RETRY.inWholeMilliseconds)
	}
}

/** How long between tries of [stand]. */
private val RETRY = 40.milliseconds
