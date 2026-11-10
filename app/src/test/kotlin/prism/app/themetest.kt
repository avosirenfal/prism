package prism.app

import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.browser.Choosing
import prism.browser.Palette
import prism.browser.pickerOf
import prism.browser.renderedPng
import prism.dbus.Answer
import prism.dbus.PrivateBus
import prism.dbus.Variant
import prism.dbus.method
import prism.dbus.orThrow
import prism.portal.ColorScheme
import prism.portal.SettingChanged
import prism.portal.Settings

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

/**
 * The picker's palette from the desktop's colour scheme: the rule, and the whole way from a portal on a bus to the
 * picture the picker draws. The portal is one of the test's own, on a private bus. Self-skips without dbus-daemon.
 */
class ThemeTest {
	@TempDir
	lateinit var folder: Path

	@Test
	fun darkOnlyWherePreferredAndANamedThemeIsThatTheme() {
		assertEquals(Palette.Dark, paletteOf(Theme.Auto, ColorScheme.PreferDark))
		assertEquals(Palette.Light, paletteOf(Theme.Auto, ColorScheme.PreferLight))
		assertEquals(Palette.Light, paletteOf(Theme.Auto, ColorScheme.NoPreference))
		assertEquals(Palette.Dark, paletteOf(Theme.Dark, ColorScheme.PreferLight))
		assertEquals(Palette.Light, paletteOf(Theme.Light, ColorScheme.PreferDark))
	}

	@Test
	fun withNoBusThePickerOpensLight() {
		Appearance.follow(Theme.Auto, environment = emptyMap()).use { appearance ->
			assertEquals(ColorScheme.NoPreference, appearance.first)
			assertEquals(Palette.Light, paletteOf(Theme.Auto, appearance.first))
		}
	}

	@Test
	fun thePickerOpensInTheDesktopsSchemeAndFollowsItsChanges() {
		PrivateBus.start().use { bus ->
			runBlocking {
				withTimeout(30.seconds) {
					bus.connect().use { portal ->
						assertEquals(
							1u,
							portal.call(requestName, NameRequest(Settings.DESKTOP, 0u)).orThrow(),
						)
						portal.serve(Settings.readColorScheme) {
							Answer.Returned(Variant(ColorScheme.PreferDark))
						}

						Appearance
							.follow(
								Theme.Auto,
								mapOf("DBUS_SESSION_BUS_ADDRESS" to bus.address),
							).use { appearance ->
								// What the window opens in, and the picture it draws: the window's own background is dark.
								assertEquals(ColorScheme.PreferDark, appearance.first)
								assertEquals(
									Palette.Dark.window.toArgb(),
									cornerOf(paletteOf(Theme.Auto, appearance.first)),
								)

								// The user changes their preference; the window follows. (The changes replay the latest, so
								// the light scheme is waited for as such: it may arrive before the waiting starts.)

								portal
									.emit(
										Settings.colorSchemeChanged,
										SettingChanged(
											Settings.APPEARANCE,
											Settings.COLOR_SCHEME,
											Variant(ColorScheme.PreferLight),
										),
									).orThrow()
								appearance.changes.first { it == ColorScheme.PreferLight }
							}
					}
				}
			}
		}
	}

	/** The colour of the top-left corner of the picker drawn over [folder] in [palette]: the window's background. */
	private fun cornerOf(palette: Palette): Int {
		val png =
			renderedPng(pickerOf(folder, Choosing.OneFile(null), home = folder), 400, 300, palette)

		return ImageIO.read(ByteArrayInputStream(png)).getRGB(2, 2)
	}

	private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
		(
			((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or
				((green * 255).toInt() shl 8) or
				(blue * 255).toInt()
		)
}
