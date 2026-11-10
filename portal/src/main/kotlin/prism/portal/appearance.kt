package prism.portal

import prism.dbus.BusName
import prism.dbus.Code
import prism.dbus.Connection
import prism.dbus.DBusError
import prism.dbus.ErrorName
import prism.dbus.Opening
import prism.dbus.Reply
import prism.dbus.Variant
import prism.dbus.method
import prism.dbus.signal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/*
 * The desktop's appearance, as the portal reports it: the colour scheme the user prefers (`org.freedesktop.appearance`
 * `color-scheme`), now and as it changes. The portal's Settings interface reads a setting with `ReadOne` (since
 * version 2 of the interface; `Read`, whose value comes in a variant within a variant, before) and says a setting
 * changed with the `SettingChanged` signal.
 */

/** The colour scheme the user prefers, by the numbers the portal's documentation gives them. */
@Serializable
enum class ColorScheme {
	@Code(0)
	NoPreference,

	@Code(1)
	PreferDark,

	@Code(2)
	PreferLight,
}

/** A setting, by its namespace and its key. */
@Serializable
data class SettingKey(
	val namespace: String,
	val key: String,
)

/** A setting that changed, and its new value, which must be a [T] for the change to be read. */
@Serializable
data class SettingChanged<T>(
	val namespace: String,
	val key: String,
	val value: Variant<T>,
)

/** The portal's Settings interface, as prism uses it. */
object Settings {
	const val DESKTOP = "org.freedesktop.portal.Desktop"
	const val PATH = "/org/freedesktop/portal/desktop"
	const val INTERFACE = "org.freedesktop.portal.Settings"
	const val APPEARANCE = "org.freedesktop.appearance"
	const val COLOR_SCHEME = "color-scheme"

	val colorSchemeKey = SettingKey(APPEARANCE, COLOR_SCHEME)

	/** One setting's value: the colour scheme, as `ReadOne` gives it, a variant holding a `u`. */
	val readColorScheme =
		method<SettingKey, Variant<ColorScheme>>(DESKTOP, PATH, INTERFACE, "ReadOne")

	/** The same, as a portal older than version 2 of the interface gives it: a variant within a variant. */
	val readColorSchemeBeforeVersion2 =
		method<SettingKey, Variant<Variant<ColorScheme>>>(DESKTOP, PATH, INTERFACE, "Read")

	/** A change of the colour scheme. The bus is asked for those of this namespace and key alone. */
	val colorSchemeChanged =
		signal<SettingChanged<ColorScheme>>(PATH, INTERFACE, "SettingChanged")
}

/**
 * The colour scheme the user prefers now: [ColorScheme.NoPreference] where the portal has no answer (no portal on the
 * bus, no such setting, a value of another type), since that is what the absence of a preference means.
 */
suspend fun Connection.currentColorScheme(): ColorScheme =
	when (val read = call(Settings.readColorScheme, Settings.colorSchemeKey)) {
		is Reply.Returned -> read.value.value
		is Reply.Failed ->
			if ((read.error as? DBusError.Remote)?.name == ErrorName.UNKNOWN_METHOD)
				(
					call(
						Settings.readColorSchemeBeforeVersion2,
						Settings.colorSchemeKey,
					) as? Reply.Returned
				)?.value?.value?.value
					?: ColorScheme.NoPreference
			else
				ColorScheme.NoPreference
	}

/**
 * The colour scheme the user prefers, now and each time it changes, the same value never twice in a row. Subscribed
 * before it reads, so a change the user makes as the picker opens is not lost.
 */
fun Connection.colorScheme(): Flow<ColorScheme> {
	val changed = Settings.colorSchemeChanged

	return watch(
		changed,
		sender = BusName(Settings.DESKTOP),
		matching =
			listOf(
				changed.where(SettingChanged<ColorScheme>::namespace, Settings.APPEARANCE),
				changed.where(SettingChanged<ColorScheme>::key, Settings.COLOR_SCHEME),
			),
		read = { currentColorScheme() },
		changed = { it.arguments.value.value },
	).distinctUntilChanged()
}

/**
 * The colour scheme from the session bus, for as long as it is collected: [ColorScheme.NoPreference] alone where
 * there is no session bus to ask, and nothing more once the bus goes away, so whoever shows it keeps the last one.
 */
fun sessionColorScheme(
	environment: Map<String, String> = System.getenv(),
): Flow<ColorScheme> =
	flow {
		when (val opening = Connection.session(environment)) {
			is Opening.Failed -> emit(ColorScheme.NoPreference)
			is Opening.Opened -> opening.connection.use { emitAll(it.colorScheme()) }
		}
	}.catch { /* The bus went away: what was said last stands. */ }
