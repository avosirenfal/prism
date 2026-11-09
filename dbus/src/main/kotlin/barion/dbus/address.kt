package barion.dbus

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

/*
 * Where a bus is, and getting in: the addresses a bus may be reached at, and the handshake that opens a connection
 * before any message (D-Bus's SASL: a NUL byte, `AUTH EXTERNAL` with the user's id, the bus's `OK`, `BEGIN`).
 */

/** Where a bus listens, as an address names it. */
sealed interface BusAddress {
	/** A Unix socket at a path in the file system: what systemd's user bus and dbus-daemon use. */
	data class UnixPath(
		val path: Path,
	) : BusAddress

	/** A Unix socket in Linux's abstract namespace, which the JDK's Unix sockets cannot name. */
	data class UnixAbstract(
		val name: String,
	) : BusAddress

	/** An address of a transport this library does not speak (tcp, launchd, ...). */
	data class Unsupported(
		val text: String,
	) : BusAddress

	companion object {
		/** An address string's addresses, in the order to try them: `;` between them, `transport:key=value,...` each. */
		fun parse(text: String): List<BusAddress> =
			text.split(';').filter { it.isNotBlank() }.map { address ->
				val transport = address.substringBefore(':')
				val keys =
					address
						.substringAfter(':', "")
						.split(',')
						.filter { '=' in it }
						.associate { it.substringBefore('=') to unescaped(it.substringAfter('=')) }

				when {
					transport == "unix" && "path" in keys -> UnixPath(Path(keys.getValue("path")))
					transport == "unix" && "abstract" in keys -> UnixAbstract(keys.getValue("abstract"))
					else -> Unsupported(address)
				}
			}

		/**
		 * The session bus's addresses: `DBUS_SESSION_BUS_ADDRESS`, or the socket systemd's user bus listens on
		 * (`$XDG_RUNTIME_DIR/bus`) where nothing names one.
		 */
		fun session(environment: Map<String, String> = System.getenv()): List<BusAddress> =
			environment["DBUS_SESSION_BUS_ADDRESS"]?.takeIf { it.isNotBlank() }?.let(::parse)
				?: listOfNotNull(
					environment["XDG_RUNTIME_DIR"]
						?.let {
							Path(it, "bus")
						}?.takeIf { it.exists() }
						?.let(::UnixPath),
				)

		/** The system bus's addresses: `DBUS_SYSTEM_BUS_ADDRESS`, or its well-known socket. */
		fun system(environment: Map<String, String> = System.getenv()): List<BusAddress> =
			environment["DBUS_SYSTEM_BUS_ADDRESS"]?.takeIf { it.isNotBlank() }?.let(::parse)
				?: listOf(UnixPath(Path("/run/dbus/system_bus_socket")))

		/** An address value's `%XX` escapes, as bytes, read back as UTF-8. */
		private fun unescaped(value: String): String {
			val bytes = ArrayList<Byte>()
			var at = 0

			while (at < value.length) {
				// A `%` and two hex digits is a byte; anything else, a `%` that is not one included, is itself.
				val escaped =
					if (value[at] == '%' &&
						at + 2 < value.length
					)
						value.substring(at + 1, at + 3).toIntOrNull(16)
					else
						null

				if (escaped != null) {
					bytes += escaped.toByte()
					at += 3
				} else {
					bytes += value[at].toString().encodeToByteArray().toList()
					at++
				}
			}

			return bytes.toByteArray().decodeToString()
		}
	}
}

/** Why one address could not be opened. */
sealed interface OpenFailure {
	val message: String

	/** Nothing says where the bus is: no address, and no socket where one usually is. */
	data object NoAddress : OpenFailure {
		override val message: String get() = "no bus address: DBUS_SESSION_BUS_ADDRESS is not set and no socket is at \$XDG_RUNTIME_DIR/bus"
	}

	data class Unsupported(
		val address: BusAddress,
	) : OpenFailure {
		override val message: String get() = "$address: a transport this library does not speak"
	}

	data class Unreachable(
		val address: BusAddress,
		val why: String,
	) : OpenFailure {
		override val message: String get() = "$address: $why"
	}

	/** The bus did not accept who this process is. */
	data class Refused(
		val address: BusAddress,
		val said: String,
	) : OpenFailure {
		override val message: String get() = "$address refused the connection: $said"
	}

	/** Connected, but the bus did not answer `Hello`, which every connection must call first. */
	data class NoHello(
		val address: BusAddress,
		val error: DBusError,
	) : OpenFailure {
		override val message: String get() = "$address did not answer Hello: ${error.message}"
	}
}

/** What opening a connection gave: the connection, or why each address tried could not be opened. */
sealed interface Opening {
	data class Opened(
		val connection: Connection,
	) : Opening

	data class Failed(
		val failures: List<OpenFailure>,
	) : Opening {
		val message: String get() = failures.joinToString("; ") { it.message }
	}
}

/** The bus's answer to the handshake: let in, with the bus's id, or refused, with what it said. */
internal sealed interface Authenticated {
	data class Accepted(
		val guid: String,
	) : Authenticated

	data class Refused(
		val said: String,
	) : Authenticated
}

/** This process's user id: the owner of `/proc/self`, which is the process's own. */
internal fun currentUid(): Int = Files.getAttribute(Path("/proc/self"), "unix:uid") as Int

/**
 * The handshake on [channel]: the NUL byte every client starts with, `AUTH EXTERNAL` with the user's id (its decimal
 * digits in hex, as the specification has it), and `BEGIN` once the bus says `OK`. The bus checks the id against the
 * socket's own credentials, so there is nothing else to prove.
 */
internal fun authenticate(
	channel: SocketChannel,
	uid: Int = currentUid(),
): Authenticated {
	val id = uid.toString().encodeToByteArray().joinToString("") { "%02x".format(it) }

	write(channel, byteArrayOf(0) + "AUTH EXTERNAL $id\r\n".encodeToByteArray())

	var answer = line(channel)

	// A bus that wants the id in a DATA exchange instead asks with an empty DATA; the id was given already.
	if (answer.startsWith("DATA")) {
		write(channel, "DATA\r\n".encodeToByteArray())
		answer = line(channel)
	}

	if (!answer.startsWith("OK "))
		return Authenticated.Refused(answer).also {
			write(channel, "CANCEL\r\n".encodeToByteArray())
		}

	write(channel, "BEGIN\r\n".encodeToByteArray())

	return Authenticated.Accepted(answer.removePrefix("OK ").trim())
}

private fun write(channel: SocketChannel, bytes: ByteArray) {
	val buffer = ByteBuffer.wrap(bytes)

	while (buffer.hasRemaining())
		channel.write(buffer)
}

/** One line of the handshake, up to its `\r\n`, a byte at a time so that nothing after it is read. */
private fun line(channel: SocketChannel): String {
	val bytes = ArrayList<Byte>()
	val one = ByteBuffer.allocate(1)

	while (bytes.size < MAX_LINE) {
		one.clear()
		if (channel.read(one) < 0)
			throw IOException("the bus closed the connection during the handshake")

		val byte = one.get(0)

		if (byte == '\n'.code.toByte() && bytes.lastOrNull() == '\r'.code.toByte())
			return bytes.dropLast(1).toByteArray().decodeToString()
		bytes += byte
	}

	throw IOException("a handshake line longer than $MAX_LINE bytes")
}

private const val MAX_LINE = 16 * 1024
