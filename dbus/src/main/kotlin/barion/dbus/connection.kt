package barion.dbus

import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * A connection to a bus. Opening one connects to the bus's socket, makes the handshake and calls the bus's `Hello`,
 * which every connection must call first, so a connection that exists is one the bus has named. Through it a program
 * calls methods ([Connection.call]), sends signals ([Connection.emit]), listens for them ([Connection.signals],
 * [Connection.watch]) and serves methods of its own ([Connection.serve]).
 *
 * Underneath, one thread reads: each message the bus sends is read whole, decoded, and handed to whoever waits for
 * it (the call it replies to, the subscriptions it matches, the method it calls). What goes wrong is said as a value:
 * a call's [Reply], an open's [Opening]. A flow, which can only say why it stopped by throwing, ends with a
 * [DBusException].
 */

/** What became of a call sent on a link: answered, unanswered within the time it was given, or cut off. */
internal sealed interface Exchange {
	data class Answered(
		val reply: Message,
	) : Exchange

	data class TimedOut(
		val after: Duration,
	) : Exchange

	data class Closed(
		val why: String,
	) : Exchange
}

/** Where a served method is: an object, an interface, a member. */
internal data class Route(
	val path: ObjectPath,
	val interfaceName: InterfaceName,
	val member: MemberName,
)

/** A served method: what it answers a call with. */
internal fun interface Handler {
	suspend fun answer(call: Message.Call): Message
}

/** A subscription as the reader sees it: what it asked for, and where the signals go. */
internal class Subscriber(
	val match: Match,
	val signals: Channel<Message.Signal>,
)

private class LinkClosed(
	why: String,
) : Exception(why)

/**
 * A connection's socket and everything that waits on it: the calls waiting for replies, the subscriptions, the served
 * methods. The one piece of the library that changes as it runs; everything it hands out is immutable.
 */
internal class Link(
	private val channel: SocketChannel,
) {
	private val serials = AtomicInteger()
	private val pending = ConcurrentHashMap<UInt, CompletableDeferred<Message>>()
	private val subscribers = CopyOnWriteArrayList<Subscriber>()
	private val routes = ConcurrentHashMap<Route, Handler>()
	private val writing = ReentrantLock()

	@Volatile
	var closedBecause: String? = null
		private set

	/** Where served methods run, and where a closed subscription tells the bus it is done; cancelled on close. */
	val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("dbus"))

	private val reader = Thread(::read, "dbus-reader").apply { isDaemon = true }

	fun start() = reader.start()

	/** The next serial: never 0, which no message may carry. */
	fun serial(): UInt {
		while (true) {
			val next = serials.incrementAndGet().toUInt()

			if (next != 0u)
				return next
		}
	}

	/** [message] written to the bus whole; false where the connection is closed or the write failed. */
	fun send(message: Message): Boolean {
		val bytes = ByteBuffer.wrap(message.encoded())

		return writing.withLock {
			try {
				while (bytes.hasRemaining())
					channel.write(bytes)
				true
			} catch (failed: IOException) {
				false
			}
		}
	}

	/** [call] sent, and its reply awaited for at most [timeout]. */
	suspend fun exchange(call: Message.Call, timeout: Duration): Exchange {
		val answer = CompletableDeferred<Message>()

		pending[call.serial] = answer
		// Registered before it is sent, so a reply cannot arrive before anyone waits for it; and checked after, so a
		// link that closed in between is not waited on until the time runs out.
		closedBecause?.let { why ->
			pending.remove(call.serial)

			return Exchange.Closed(why)
		}
		if (!withContext(Dispatchers.IO) { send(call) }) {
			pending.remove(call.serial)

			return Exchange.Closed(closedBecause ?: "the write failed")
		}

		return try {
			withTimeoutOrNull(timeout) { answer.await() }?.let(Exchange::Answered)
				?: Exchange.TimedOut(timeout).also { pending.remove(call.serial) }
		} catch (closed: LinkClosed) {
			Exchange.Closed(closed.message ?: "closed")
		}
	}

	fun subscribe(subscriber: Subscriber) {
		subscribers += subscriber
		closedBecause?.let { subscriber.signals.close(DBusException(DBusError.Closed(it))) }
	}

	fun unsubscribe(subscriber: Subscriber) {
		subscribers -= subscriber
		subscriber.signals.close()
	}

	fun route(route: Route, handler: Handler) {
		require(routes.putIfAbsent(route, handler) == null) {
			"${route.interfaceName}.${route.member} at ${route.path} is served already"
		}
	}

	fun unroute(route: Route) {
		routes.remove(route)
	}

	/** Closes the socket; the reader then ends everything that waits, saying [why]. */
	fun close(why: String) {
		if (closedBecause == null)
			closedBecause = why
		try {
			channel.close()
		} catch (ignored: IOException) {
		}
	}

	private fun read() {
		val why =
			try {
				while (true)
					dispatch(readMessage())
				@Suppress("UNREACHABLE_CODE")
				"unreachable"
			} catch (closed: ClosedChannelException) {
				closedBecause ?: "closed"
			} catch (ended: EOFException) {
				"the bus closed the connection"
			} catch (malformed: Malformed) {
				"the bus sent a malformed message: ${malformed.message}"
			} catch (failed: IOException) {
				failed.toString()
			} catch (failed: Exception) {
				// Anything else the reader meets is a bug of this library's; it still ends what waits, and says what.
				"the reader failed: $failed"
			}

		close(why)
		shutDown(closedBecause ?: why)
	}

	/** Everything that waits, told the link is closed. */
	private fun shutDown(why: String) {
		val closed = LinkClosed(why)

		for (answer in pending.values)
			answer.completeExceptionally(closed)
		pending.clear()
		for (subscriber in subscribers)
			subscriber.signals.close(DBusException(DBusError.Closed(why)))
		subscribers.clear()
		scope.cancel()
	}

	private fun readMessage(): Message {
		val start = exactly(MESSAGE_START)

		return decodeMessage(start + exactly(messageLength(start) - MESSAGE_START))
	}

	private fun exactly(bytes: Int): ByteArray {
		val buffer = ByteBuffer.allocate(bytes)

		while (buffer.hasRemaining())
			if (channel.read(buffer) < 0)
				throw EOFException()

		return buffer.array()
	}

	private fun dispatch(message: Message) {
		when (message) {
			is Message.Return -> pending.remove(message.replyTo)?.complete(message)
			is Message.Error -> pending.remove(message.replyTo)?.complete(message)
			is Message.Signal ->
				for (subscriber in subscribers)
					if (subscriber.match.matches(message))
						subscriber.signals.trySend(message)
			is Message.Call -> answer(message)
		}
	}

	/** A call to this connection: served, a peer's ping, or a method this connection does not have. */
	private fun answer(call: Message.Call) {
		val handler =
			routes.entries
				.firstOrNull { (route, _) ->
					route.path == call.path && route.member == call.member &&
						(call.interfaceName == null || call.interfaceName == route.interfaceName)
				}?.value

		when {
			handler != null ->
				scope.launch {
					val reply = handler.answer(call)

					if (!call.flags.noReplyExpected)
						withContext(Dispatchers.IO) { send(reply) }
				}
			// Every peer answers org.freedesktop.DBus.Peer.Ping, by the specification.
			call.interfaceName?.value == "org.freedesktop.DBus.Peer" && call.member.value == "Ping" ->
				send(Message.Return(serial(), call.serial, call.sender))
			!call.flags.noReplyExpected ->
				send(
					errorReply(
						call,
						ErrorName.UNKNOWN_METHOD,
						"no method ${call.interfaceName?.let {
							"$it."
						} ?: ""}${call.member} at ${call.path}",
					),
				)
		}
	}

	fun errorReply(call: Message.Call, name: ErrorName, text: String): Message =
		Message.Error(
			serial(),
			call.serial,
			name,
			call.sender,
			Body(listOf(Value.String(text))),
		)
}

/**
 * A connection to a bus, named by it ([uniqueName]). Closing it ends every call that waits, every subscription and
 * every served method.
 */
class Connection internal constructor(
	private val link: Link,
	val uniqueName: BusName,
) : AutoCloseable {
	val isOpen: Boolean get() = link.closedBecause == null

	/** [method] called with [arguments], its reply awaited for at most [timeout]. */
	suspend fun <A, R> call(
		method: Method<A, R>,
		arguments: A,
		timeout: Duration = DEFAULT_TIMEOUT,
	): Reply<R> = call(link, method, arguments, timeout)

	/** [method], which takes no arguments, called. */
	suspend fun <R> call(
		method: Method<Unit, R>,
		timeout: Duration = DEFAULT_TIMEOUT,
	): Reply<R> = call(link, method, Unit, timeout)

	/** [signal] sent with [arguments], from [path] (the signal's own where it declares one), to everyone who listens. */
	suspend fun <A> emit(
		signal: Signal<A>,
		arguments: A,
		path: ObjectPath? = null,
		destination: BusName? = null,
	): Reply<Unit> {
		val from =
			requireNotNull(path ?: signal.path) { "$signal comes from any object; say which" }

		val message =
			Message.Signal(
				link.serial(),
				from,
				signal.interfaceName,
				signal.member,
				destination,
				encodeBody(signal.arguments, arguments),
			)

		return if (withContext(Dispatchers.IO) { link.send(message) })
			Reply.Returned(Unit)
		else
			Reply.Failed(DBusError.Closed(link.closedBecause ?: "the write failed"))
	}

	/**
	 * [signal], from [sender] where it is given, whose arguments are as [matching] says, from now on: the bus is asked
	 * for them (AddMatch) before this returns, so every one sent after it returns is received. The subscription keeps
	 * them until they are read; closing it tells the bus it is done.
	 */
	suspend fun <A> subscribe(
		signal: Signal<A>,
		sender: BusName? = null,
		matching: List<Matching<A>> = emptyList(),
	): Reply<Subscription<A>> {
		val match =
			Match(
				sender,
				signal.path,
				signal.interfaceName,
				signal.member,
				matching.associate {
					it.index to
						it.value
				},
			)

		val subscriber = Subscriber(match, Channel(Channel.UNLIMITED))

		// Listening before the bus is asked, so a signal it sends the moment it has the rule is not missed.
		link.subscribe(subscriber)

		return when (val added = call(link, BusMethods.addMatch, match.rule, DEFAULT_TIMEOUT)) {
			is Reply.Returned -> Reply.Returned(Subscription(link, subscriber, signal))
			is Reply.Failed -> {
				link.unsubscribe(subscriber)

				added
			}
		}
	}

	/** [signal]'s signals as a flow: subscribed when collected, unsubscribed when the collector stops. */
	fun <A> signals(
		signal: Signal<A>,
		sender: BusName? = null,
		matching: List<Matching<A>> = emptyList(),
	): Flow<Received<A>> =
		flow {
			subscribe(signal, sender, matching).orThrow().use { subscription ->
				subscription.received.collect { emit(it) }
			}
		}

	/**
	 * A value kept current: what [read] gives now, then each new value [changed] finds in a [signal] (null for a
	 * signal about something else). Subscribed before it reads, so a change made between the read and the
	 * subscription is not lost: at worst it is seen twice, once in the read and once as a signal.
	 */
	fun <A, T> watch(
		signal: Signal<A>,
		sender: BusName? = null,
		matching: List<Matching<A>> = emptyList(),
		read: suspend Connection.() -> T,
		changed: (Received<A>) -> T?,
	): Flow<T> =
		flow {
			subscribe(signal, sender, matching).orThrow().use { subscription ->
				emit(read())
				subscription.received.collect { received -> changed(received)?.let { emit(it) } }
			}
		}

	/**
	 * Calls of [method], at its path and on its interface, answered by [answer] until the returned handle is closed.
	 * A call whose arguments are not [method]'s is answered `InvalidArgs` without [answer] seeing it; an [answer]
	 * that throws is answered `Failed`, and the connection goes on.
	 */
	fun <A, R> serve(
		method: Method<A, R>,
		answer: suspend (Called<A>) -> Answer<R>,
	): AutoCloseable {
		val route = Route(method.path, method.interfaceName, method.member)

		link.route(route) { call ->
			when (val decoded = decodeBody(method.arguments, call.body)) {
				is Decoded.Mismatched ->
					link.errorReply(
						call,
						ErrorName.INVALID_ARGS,
						"expected arguments of '${decoded.expected}', got '${decoded.got}'",
					)
				is Decoded.Unreadable -> link.errorReply(call, ErrorName.INVALID_ARGS, decoded.why)
				is Decoded.Read ->
					when (
						val answered =
							answered {
								answer(
									Called(call.sender, call.path, decoded.value),
								)
							}
					) {
						is Answer.Returned ->
							Message.Return(
								link.serial(),
								call.serial,
								call.sender,
								encodeBody(method.reply, answered.value),
							)
						is Answer.Failed -> link.errorReply(call, answered.name, answered.text)
					}
			}
		}

		return AutoCloseable { link.unroute(route) }
	}

	override fun close() {
		link.close("closed by this side")
	}

	companion object {
		/** How long a call waits for its reply unless it says otherwise: D-Bus's own default. */
		val DEFAULT_TIMEOUT: Duration = 25.seconds

		/** The user's session bus. */
		suspend fun session(environment: Map<String, String> = System.getenv()): Opening =
			open(BusAddress.session(environment))

		/** The system bus. */
		suspend fun system(environment: Map<String, String> = System.getenv()): Opening =
			open(BusAddress.system(environment))

		/** The first of [addresses] that opens, or why none did. */
		suspend fun open(addresses: List<BusAddress>): Opening =
			withContext(Dispatchers.IO) {
				if (addresses.isEmpty())
					return@withContext Opening.Failed(listOf(OpenFailure.NoAddress))

				val failures = ArrayList<OpenFailure>()

				for (address in addresses)
					when (val tried = open(address)) {
						is Opening.Opened -> return@withContext tried
						is Opening.Failed -> failures += tried.failures
					}

				Opening.Failed(failures)
			}

		private suspend fun open(address: BusAddress): Opening {
			val path =
				when (address) {
					is BusAddress.UnixPath -> address.path
					is BusAddress.UnixAbstract, is BusAddress.Unsupported -> return Opening.Failed(
						listOf(OpenFailure.Unsupported(address)),
					)
				}

			val channel =
				try {
					SocketChannel.open(StandardProtocolFamily.UNIX).apply {
						connect(UnixDomainSocketAddress.of(path))
					}
				} catch (failed: IOException) {
					return Opening.Failed(listOf(OpenFailure.Unreachable(address, failed.toString())))
				}

			val authenticated =
				try {
					authenticate(channel)
				} catch (failed: IOException) {
					channel.close()

					return Opening.Failed(listOf(OpenFailure.Unreachable(address, failed.toString())))
				}

			if (authenticated is Authenticated.Refused) {
				channel.close()

				return Opening.Failed(listOf(OpenFailure.Refused(address, authenticated.said)))
			}

			val link = Link(channel).also { it.start() }

			return when (val hello = call(link, BusMethods.hello, Unit, DEFAULT_TIMEOUT)) {
				is Reply.Returned -> Opening.Opened(Connection(link, BusName(hello.value)))
				is Reply.Failed -> {
					link.close("the bus did not answer Hello")

					Opening.Failed(listOf(OpenFailure.NoHello(address, hello.error)))
				}
			}
		}

		/** [method] called on [link]: its reply decoded as [method] says it is, or why there is none. */
		private suspend fun <A, R> call(
			link: Link,
			method: Method<A, R>,
			arguments: A,
			timeout: Duration,
		): Reply<R> {
			val call =
				Message.Call(
					link.serial(),
					method.path,
					method.interfaceName,
					method.member,
					method.destination,
					encodeBody(method.arguments, arguments),
				)

			return when (val exchange = link.exchange(call, timeout)) {
				is Exchange.TimedOut -> Reply.Failed(DBusError.TimedOut(exchange.after))
				is Exchange.Closed -> Reply.Failed(DBusError.Closed(exchange.why))
				is Exchange.Answered ->
					when (val reply = exchange.reply) {
						is Message.Error -> Reply.Failed(DBusError.Remote(reply.name, reply.text))
						else ->
							when (val decoded = decodeBody(method.reply, reply.body)) {
								is Decoded.Read -> Reply.Returned(decoded.value)
								is Decoded.Mismatched ->
									Reply.Failed(
										DBusError.Mismatched(decoded.expected, decoded.got),
									)
								is Decoded.Unreadable -> Reply.Failed(DBusError.Unreadable(decoded.why))
							}
					}
			}
		}

		/** What [answer] gave, or `Failed` with what it threw. */
		private suspend fun <R> answered(answer: suspend () -> Answer<R>): Answer<R> =
			try {
				answer()
			} catch (failed: kotlinx.coroutines.CancellationException) {
				throw failed
			} catch (failed: Exception) {
				Answer.Failed(ErrorName.FAILED, failed.toString())
			}
	}
}

/**
 * A subscription to [signal]'s signals: they are kept from the moment it was made until they are read. A signal that
 * does not decode as [A] is passed over. Closing it stops the keeping and tells the bus.
 */
class Subscription<A> internal constructor(
	private val link: Link,
	private val subscriber: Subscriber,
	private val signal: Signal<A>,
) : AutoCloseable {
	/** The signals received, in order, decoded; read once. */
	val received: Flow<Received<A>> =
		subscriber.signals.receiveAsFlow().mapNotNull { message ->
			decodeBody(signal.arguments, message.body).orNull()?.let {
				Received(message.sender, message.path, it)
			}
		}

	override fun close() {
		link.unsubscribe(subscriber)
		if (link.closedBecause == null)
			link.scope.launch {
				val call =
					Message.Call(
						link.serial(),
						BusMethods.removeMatch.path,
						BusMethods.removeMatch.interfaceName,
						BusMethods.removeMatch.member,
						BusMethods.removeMatch.destination,
						encodeBody(BusMethods.removeMatch.arguments, subscriber.match.rule),
						Flags(noReplyExpected = true),
					)

				withContext(Dispatchers.IO) { link.send(call) }
			}
	}
}

/** The bus's own methods this library calls to do its work: plumbing, not anything a program declares. */
internal object BusMethods {
	private const val BUS = "org.freedesktop.DBus"
	private const val PATH = "/org/freedesktop/DBus"

	/** The first call on every connection, which the bus answers with the connection's unique name. */
	val hello = method<Unit, String>(BUS, PATH, BUS, "Hello")
	val addMatch = method<String, Unit>(BUS, PATH, BUS, "AddMatch")
	val removeMatch = method<String, Unit>(BUS, PATH, BUS, "RemoveMatch")
}
