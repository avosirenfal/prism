package barion.dbus

import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `RequestName`'s arguments: the name, and how to take it. */
@Serializable
data class NameRequest(
	val name: String,
	val flags: UInt,
)

@Serializable
data class Greeting(
	val text: String,
	val times: Int,
)

@Serializable
data class Changed(
	val key: String,
	val value: AnyVariant,
)

private const val SERVICE = "dev.barion.Test"
private const val PATH = "/dev/barion/Test"
private const val INTERFACE = "dev.barion.Test"

private val getId =
	method<Unit, String>(
		"org.freedesktop.DBus",
		"/org/freedesktop/DBus",
		"org.freedesktop.DBus",
		"GetId",
	)
private val listNames =
	method<Unit, List<String>>(
		"org.freedesktop.DBus",
		"/org/freedesktop/DBus",
		"org.freedesktop.DBus",
		"ListNames",
	)
private val requestName =
	method<NameRequest, UInt>(
		"org.freedesktop.DBus",
		"/org/freedesktop/DBus",
		"org.freedesktop.DBus",
		"RequestName",
	)
private val noSuchMethod =
	method<Unit, Unit>(
		"org.freedesktop.DBus",
		"/org/freedesktop/DBus",
		"org.freedesktop.DBus",
		"NoSuchMethod",
	)
private val greet = method<Greeting, List<String>>(SERVICE, PATH, INTERFACE, "Greet")
private val readValue = method<String, AnyVariant>(SERVICE, PATH, INTERFACE, "Read")
private val changed = signal<Changed>(PATH, INTERFACE, "Changed")

/**
 * The connection against a real bus: a dbus-daemon of the tests' own, which checks every message it is sent, and
 * GLib's gdbus, another implementation, on the other end where it is installed. Self-skips without dbus-daemon.
 */
class ConnectionTest {
	private lateinit var bus: PrivateBus

	@BeforeEach
	fun startBus() {
		bus = PrivateBus.start()
	}

	@AfterEach
	fun stopBus() {
		if (::bus.isInitialized)
			bus.close()
	}

	private fun live(test: suspend CoroutineScope.() -> Unit) =
		runBlocking {
			withTimeout(30.seconds) { test() }
		}

	/** A connection that owns [SERVICE], and greets whoever calls [greet]. */
	private suspend fun service(): Connection {
		val service = bus.connect()

		assertEquals(
			1u,
			service.call(requestName, NameRequest(SERVICE, 0u)).orThrow(),
			"the name's primary owner",
		)
		service.serve(greet) { called ->
			Answer.Returned(
				List(called.arguments.times) {
					"hello, ${called.arguments.text}"
				},
			)
		}

		return service
	}

	@Test
	fun openingMakesTheHandshakeAndTheBusNamesTheConnection() =
		live {
			bus.connect().use { connection ->
				assertTrue(connection.uniqueName.isUnique, "${connection.uniqueName}")
				assertTrue(connection.uniqueName.value in connection.call(listNames).orThrow())
			}
		}

	@Test
	fun theBusAnswersACall() =
		live {
			bus.connect().use { connection ->
				assertTrue(Regex("[0-9a-f]{32}").matches(connection.call(getId).orThrow()))
			}
		}

	@Test
	fun aMethodTheOtherSideDoesNotHaveIsItsError() =
		live {
			bus.connect().use { connection ->
				val failed = assertInstanceOf(Reply.Failed::class.java, connection.call(noSuchMethod))
				val remote = assertInstanceOf(DBusError.Remote::class.java, failed.error)

				assertEquals(ErrorName.UNKNOWN_METHOD, remote.name)
			}
		}

	@Test
	fun aReplyOfAnotherTypeIsAMismatchNotAWrongRead() =
		live {
			val wrong =
				method<Unit, UInt>(
					"org.freedesktop.DBus",
					"/org/freedesktop/DBus",
					"org.freedesktop.DBus",
					"GetId",
				)

			bus.connect().use { connection ->
				val failed = assertInstanceOf(Reply.Failed::class.java, connection.call(wrong))

				assertEquals(
					DBusError.Mismatched(
						Signature.of(Type.Basic.UInt32),
						Signature.of(Type.Basic.String),
					),
					failed.error,
				)
			}
		}

	@Test
	fun twoConnectionsCallEachOther() =
		live {
			service().use {
				bus.connect().use { client ->
					assertEquals(
						listOf("hello, world", "hello, world"),
						client.call(greet, Greeting("world", 2)).orThrow(),
					)
				}
			}
		}

	@Test
	fun aServedMethodCalledWithOtherArgumentsSaysSo() =
		live {
			val greetWrongly = method<String, List<String>>(SERVICE, PATH, INTERFACE, "Greet")

			service().use {
				bus.connect().use { client ->
					val failed =
						assertInstanceOf(Reply.Failed::class.java, client.call(greetWrongly, "world"))

					assertEquals(ErrorName.INVALID_ARGS, (failed.error as DBusError.Remote).name)
				}
			}
		}

	@Test
	fun signalsArriveAsAFlowAndOnlyThoseAskedFor() =
		live {
			service().use { service ->
				bus.connect().use { client ->
					client
						.subscribe(
							changed,
							matching = listOf(changed.where(Changed::key, "color-scheme")),
						).orThrow()
						.use { subscription ->
							service.emit(changed, Changed("font", AnyVariant.of("Inter 11"))).orThrow()
							service.emit(changed, Changed("color-scheme", AnyVariant.of(1u))).orThrow()
							service.emit(changed, Changed("color-scheme", AnyVariant.of(2u))).orThrow()

							val received = subscription.received.take(2).toList()

							assertEquals(
								listOf(1u, 2u),
								received.map {
									it.arguments.value
										.decode<UInt>()
										.orNull()
								},
							)
							assertEquals(ObjectPath(PATH), received.first().path)
						}
				}
			}
		}

	@Test
	fun aWatchedValueIsReadThenFollowed() =
		live {
			service().use { service ->
				val read = CompletableDeferred<Unit>()

				service.serve(readValue) {
					read.complete(Unit)
					Answer.Returned(AnyVariant.of(0u))
				}

				bus.connect().use { client ->
					val watched =
						async {
							client
								.watch(
									changed,
									matching = listOf(changed.where(Changed::key, "color-scheme")),
									read = {
										call(
											readValue,
											"color-scheme",
										).orThrow().decode<UInt>().orNull() ?: 0u
									},
									changed = {
										it.arguments.value
											.decode<UInt>()
											.orNull()
									},
								).take(3)
								.toList()
						}

					// A watch subscribes before it reads, so once it has read, every change after is one it sees.
					read.await()
					service.emit(changed, Changed("font", AnyVariant.of("Inter 11"))).orThrow()
					service.emit(changed, Changed("color-scheme", AnyVariant.of(1u))).orThrow()
					service.emit(changed, Changed("color-scheme", AnyVariant.of(2u))).orThrow()

					assertEquals(listOf(0u, 1u, 2u), watched.await())
				}
			}
		}

	@Test
	fun closingEndsWhatWaits() =
		live {
			val client = bus.connect()
			val subscription = client.subscribe(changed).orThrow()

			client.close()

			assertThrows<DBusException> { subscription.received.first() }
			assertFalse(client.isOpen)
			assertInstanceOf(
				DBusError.Closed::class.java,
				(client.call(getId) as Reply.Failed).error,
			)
		}

	@Test
	fun gdbusCallsWhatThisConnectionServes() =
		live {
			service().use {
				val said =
					gdbus(
						"call",
						"--dest",
						SERVICE,
						"--object-path",
						PATH,
						"--method",
						"$INTERFACE.Greet",
						"glib",
						"2",
					)

				assertEquals("(['hello, glib', 'hello, glib'],)", said)
				// Every peer answers Ping.
				assertEquals(
					"()",
					gdbus(
						"call",
						"--dest",
						SERVICE,
						"--object-path",
						PATH,
						"--method",
						"org.freedesktop.DBus.Peer.Ping",
					),
				)
			}
		}

	@Test
	fun busctlCallsWhatThisConnectionServes() =
		live {
			service().use {
				// systemd's sd-bus, reading this connection's reply: an array of two strings.
				assertEquals(
					"as 2 \"hello, sd-bus\" \"hello, sd-bus\"",
					tool(
						"busctl",
						"--address=${bus.address}",
						"call",
						SERVICE,
						PATH,
						INTERFACE,
						"Greet",
						"si",
						"sd-bus",
						"2",
					),
				)
			}
		}

	/**
	 * Signals another implementation sends, read here: libdbus's dbus-send and systemd's busctl. (Not gdbus's `emit`:
	 * given an address, it sends before it has called Hello, and the bus passes on nothing from a connection it has
	 * not named.)
	 */
	@Test
	fun thisConnectionReadsWhatOtherImplementationsSend() =
		live {
			bus.connect().use { client ->
				client.subscribe(changed).orThrow().use { subscription ->
					tool(
						"dbus-send",
						"--bus=${bus.address}",
						"--type=signal",
						PATH,
						"$INTERFACE.Changed",
						"string:color-scheme",
						"variant:uint32:1",
					)
					tool(
						"busctl",
						"--address=${bus.address}",
						"emit",
						PATH,
						INTERFACE,
						"Changed",
						"sv",
						"color-scheme",
						"u",
						"2",
					)

					val received = subscription.received.take(2).toList()

					assertEquals(
						listOf("color-scheme", "color-scheme"),
						received.map { it.arguments.key },
					)
					assertEquals(
						listOf(Decoded.Read(1u), Decoded.Read(2u)),
						received.map {
							it.arguments.value.decode<UInt>()
						},
					)
				}
			}
		}

	/** [command] run with [arguments]: what it printed. Skips the test where [command] is not installed. */
	private suspend fun tool(command: String, vararg arguments: String): String =
		withContext(Dispatchers.IO) {
			val process =
				try {
					ProcessBuilder(listOf(command) + arguments).redirectErrorStream(true).start()
				} catch (missing: IOException) {
					null
				}

			assumeTrue(process != null, "no $command")

			val said =
				process!!
					.inputStream
					.readBytes()
					.decodeToString()
					.trim()

			assertEquals(0, process.waitFor(), said)
			said
		}

	/** gdbus, on this bus, with [arguments]: what it printed. Skips the test where gdbus is not installed. */
	private suspend fun gdbus(vararg arguments: String): String =
		withContext(Dispatchers.IO) {
			val process =
				try {
					ProcessBuilder(
						listOf("gdbus", arguments[0], "--address", bus.address) + arguments.drop(1),
					).redirectErrorStream(true).start()
				} catch (missing: IOException) {
					null
				}

			assumeTrue(process != null, "no gdbus")

			val said =
				process!!
					.inputStream
					.readBytes()
					.decodeToString()
					.trim()

			assertEquals(0, process.waitFor(), said)
			said
		}
}
