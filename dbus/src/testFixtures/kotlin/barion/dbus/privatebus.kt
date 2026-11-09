package barion.dbus

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * A bus of the tests' own: a dbus-daemon with the session bus's configuration, listening on a socket in a directory
 * of its own, stopped when closed. The real thing, so every message the tests send is one dbus-daemon checked and
 * passed on. A test that needs one self-skips where dbus-daemon is not installed.
 */
class PrivateBus private constructor(
	private val daemon: Process,
	private val directory: Path,
	/** The address to give a client: `unix:path=...`. */
	val address: String,
) : AutoCloseable {
	val addresses: List<BusAddress> get() = BusAddress.parse(address)

	/** A connection to this bus, opened. */
	suspend fun connect(): Connection =
		when (val opening = Connection.open(addresses)) {
			is Opening.Opened -> opening.connection
			is Opening.Failed -> error("could not connect to the private bus: ${opening.message}")
		}

	@OptIn(kotlin.io.path.ExperimentalPathApi::class)
	override fun close() {
		daemon.destroy()
		daemon.waitFor(5, TimeUnit.SECONDS)
		directory.deleteRecursively()
	}

	companion object {
		/** A private bus, started; the test is skipped where there is no dbus-daemon to start. */
		fun start(): PrivateBus {
			val directory = Files.createTempDirectory("barion-bus")
			val socket = directory / "bus"

			val daemon =
				try {
					ProcessBuilder(
						"dbus-daemon",
						"--session",
						"--nofork",
						"--nopidfile",
						"--print-address=1",
						"--address=unix:path=$socket",
					).redirectError(ProcessBuilder.Redirect.DISCARD)
						.start()
				} catch (missing: java.io.IOException) {
					null
				}

			assumeTrue(daemon != null, "no dbus-daemon to start a bus with")

			// The daemon prints its address once it listens.
			val address = daemon!!.inputReader().readLine()

			assumeTrue(address != null, "dbus-daemon did not start")

			return PrivateBus(daemon, directory, address)
		}
	}
}
