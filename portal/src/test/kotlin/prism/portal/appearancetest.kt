package prism.portal

import prism.dbus.Answer
import prism.dbus.Connection
import prism.dbus.PrivateBus
import prism.dbus.Variant
import prism.dbus.method
import prism.dbus.orThrow
import prism.dbus.signal
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@Serializable
data class NameRequest(
	val name: String,
	val flags: UInt,
)

private val requestName =
	method<NameRequest, UInt>(
		"org.freedesktop.DBus",
		"/org/freedesktop/DBus",
		"org.freedesktop.DBus",
		"RequestName",
	)

/** A change of a setting whose value is text: what the portal says when the font changes, say. */
private val textSettingChanged =
	signal<SettingChanged<String>>(Settings.PATH, Settings.INTERFACE, "SettingChanged")

/**
 * The colour scheme against a portal of the tests' own, on a bus of their own: a connection that owns the portal's
 * name and serves its Settings methods as the portal does. Self-skips without dbus-daemon.
 */
class AppearanceTest {
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

	/** A portal that owns the portal's name, its colour scheme [scheme], read through ReadOne (or only Read, if [old]). */
	private suspend fun portal(
		scheme: ColorScheme,
		old: Boolean = false,
		read: CompletableDeferred<Unit>? = null,
	): Connection {
		val portal = bus.connect()

		assertEquals(1u, portal.call(requestName, NameRequest(Settings.DESKTOP, 0u)).orThrow())
		if (old)
			portal.serve(Settings.readColorSchemeBeforeVersion2) {
				read?.complete(Unit)
				Answer.Returned(Variant(Variant(scheme)))
			}
		else
			portal.serve(Settings.readColorScheme) {
				read?.complete(Unit)
				Answer.Returned(Variant(scheme))
			}

		return portal
	}

	@Test
	fun theSchemeIsReadThenFollowed() =
		live {
			val read = CompletableDeferred<Unit>()

			portal(ColorScheme.PreferDark, read = read).use { portal ->
				bus.connect().use { client ->
					val seen = async { client.colorScheme().take(3).toList() }

					read.await()
					// Another setting's change, which the bus does not pass on; the same scheme again, which the flow
					// does not repeat; a scheme of another type, which is passed over; then two changes.
					portal
						.emit(
							textSettingChanged,
							SettingChanged("org.gnome.desktop.interface", "font-name", Variant("Inter 11")),
						).orThrow()
					portal
						.emit(
							Settings.colorSchemeChanged,
							SettingChanged(
								Settings.APPEARANCE,
								Settings.COLOR_SCHEME,
								Variant(ColorScheme.PreferDark),
							),
						).orThrow()
					portal
						.emit(
							textSettingChanged,
							SettingChanged(Settings.APPEARANCE, Settings.COLOR_SCHEME, Variant("dark")),
						).orThrow()
					portal
						.emit(
							Settings.colorSchemeChanged,
							SettingChanged(
								Settings.APPEARANCE,
								Settings.COLOR_SCHEME,
								Variant(ColorScheme.PreferLight),
							),
						).orThrow()
					portal
						.emit(
							Settings.colorSchemeChanged,
							SettingChanged(
								Settings.APPEARANCE,
								Settings.COLOR_SCHEME,
								Variant(ColorScheme.NoPreference),
							),
						).orThrow()

					assertEquals(
						listOf(ColorScheme.PreferDark, ColorScheme.PreferLight, ColorScheme.NoPreference),
						seen.await(),
					)
				}
			}
		}

	@Test
	fun aPortalBeforeVersion2IsReadThroughRead() =
		live {
			portal(ColorScheme.PreferDark, old = true).use {
				bus.connect().use { client ->
					assertEquals(ColorScheme.PreferDark, client.currentColorScheme())
				}
			}
		}

	@Test
	fun noPortalIsNoPreference() =
		live {
			bus.connect().use { client ->
				assertEquals(ColorScheme.NoPreference, client.colorScheme().first())
			}
		}

	@Test
	fun noBusIsNoPreference() =
		live {
			// No address in the environment, and no runtime directory to find a socket in.
			assertEquals(
				listOf(ColorScheme.NoPreference),
				sessionColorScheme(environment = emptyMap()).toList(),
			)
		}

	@Test
	fun theSessionBusIsTheOneTheEnvironmentNames() =
		live {
			portal(ColorScheme.PreferLight).use {
				val environment = mapOf("DBUS_SESSION_BUS_ADDRESS" to bus.address)

				assertEquals(ColorScheme.PreferLight, sessionColorScheme(environment).first())
			}
		}
}
