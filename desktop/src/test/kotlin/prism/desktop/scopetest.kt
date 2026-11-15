package prism.desktop

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import prism.desktop.freedesktop.AppScope

/**
 * The scope an application is started in is named so that the desktop's portal reads the application back from it.
 * The portal's own reading (xdg-desktop-portal's `_xdp_app_info_host_parse_app_id_from_unit_name`, the same in 1.18
 * and 1.20) is written out here as [Portal], and every name prism makes is read back with it.
 */
class ScopeTest {
	/** What xdg-desktop-portal makes of a unit's name: its two patterns, and systemd's `cunescape` as it calls it. */
	private object Portal {
		private val scope =
			Regex("""^app-(?:\p{Alnum}+-)?(.+?)(?:-\p{Alnum}*)(?:\.scope|\.slice)$""")
		private val service =
			Regex("""^app-(?:\p{Alnum}+-)?(.+?)(?:@\p{Alnum}*|-autostart)?\.service$""")

		fun appId(unit: String): String =
			(scope.find(unit) ?: service.find(unit))
				?.groupValues
				?.get(1)
				?.let(::unescaped)
				.orEmpty()

		private const val HEX = "0123456789abcdefABCDEF"

		/** `cunescape(…, UNESCAPE_RELAX)`: C's escapes undone, and one that is none left as it is written. */
		private fun unescaped(text: String): String {
			val bytes = text.toByteArray(Charsets.UTF_8)
			val out = ByteArrayOutputStream()
			var at = 0

			while (at < bytes.size) {
				val next = bytes.getOrNull(at + 1)?.toInt()?.toChar()
				val hex =
					(2..3).mapNotNull { bytes.getOrNull(at + it)?.toInt()?.toChar() }.joinToString("")

				when {
					bytes[at] != '\\'.code.toByte() -> out.write(bytes[at].toInt()).also { at += 1 }
					next == 'x' && hex.length == 2 && hex.all { it in HEX } && hex.toInt(16) != 0 -> {
						out.write(hex.toInt(16))
						at += 4
					}
					next != null && next in SIMPLE -> {
						out.write(SIMPLE.getValue(next).code)
						at += 2
					}
					else -> out.write(bytes[at].toInt()).also { at += 1 }
				}
			}

			return out.toString(Charsets.UTF_8)
		}

		private val SIMPLE =
			mapOf(
				'a' to '\u0007',
				'b' to '\b',
				'f' to '\u000c',
				'n' to '\n',
				'r' to '\r',
				't' to '\t',
				'v' to '\u000b',
				'\\' to '\\',
				'"' to '"',
				'\'' to '\'',
				's' to ' ',
			)
	}

	@Test
	fun thePortalsReadingIsTheOneItsSourcesAndSystemdsDocumentShow() {
		mapOf(
			"app-gnome-org.gnome.Evince@12345.service" to "org.gnome.Evince",
			"app-flatpak-org.telegram.desktop@12345.service" to "org.telegram.desktop",
			"app-KDE-org.kde.okular@12345.service" to "org.kde.okular",
			"app-org.kde.amarok.service" to "org.kde.amarok",
			"app-org.gnome.Evince-12345.scope" to "org.gnome.Evince",
			"""app-gnome-org.gnome.Evolution\x2dalarm\x2dnotify-2437.scope""" to
				"org.gnome.Evolution-alarm-notify",
			// A unit that is no application's: the portal says nobody it knows.
			"prism.service" to "",
		).forEach { (unit, id) -> assertEquals(id, Portal.appId(unit), unit) }
	}

	@Test
	fun aScopeIsNamedAsSystemdsDocumentNamesOne() {
		assertEquals(
			"app-prism-org.gnome.Evince-1a2b3c4d.scope",
			AppScope.of("org.gnome.Evince.desktop", 0x1a2b3c4d).unit,
		)
		// A command no entry starts is named by its program; a start is told from another by eight hex digits.
		assertEquals("app-prism-foot-00000000.scope", AppScope.of("/usr/bin/foot", 0).unit)
		assertEquals("app-prism-foot-ffffffff.scope", AppScope.of("foot", -1).unit)
		assertEquals(
			"""app-prism-org.gnome.Evolution\x2dalarm\x2dnotify-000000ff.scope""",
			AppScope.of("org.gnome.Evolution-alarm-notify.desktop", 0xff).unit,
		)
		assertTrue(
			Regex("""app-prism-foot-[0-9a-f]{8}\.scope""").matches(AppScope.of("foot").unit),
		)
	}

	private val awkward =
		listOf(
			"firefox",
			"org.mozilla.firefox",
			"my-app",
			"a-1",
			"ends-",
			"-starts",
			"--",
			"prism",
			"app-prism-x",
			"x-autostart",
			"12345",
			".hidden",
			"..",
			"with space",
			"at@sign",
			"plus+plus",
			"under_score:colon",
			"""back\slash""",
			"""already\x2descaped""",
			"café",
			"日本語",
			"🎨.paint",
			"UPPER.Case",
			"semi;colon&amp",
			"new\nline",
			"x.scope",
			"x.service",
		)

	@Test
	fun thePortalReadsBackTheApplicationOfEveryName() {
		awkward.forEach { id ->
			listOf(0, 1, -1, 0x1a2b3c4d, Int.MIN_VALUE).forEach { instance ->
				val unit = AppScope.of("$id.desktop", instance).unit

				assertEquals(id, Portal.appId(unit), unit)
				// What systemd takes for a unit's name: these characters, and no more than 255 of them.
				assertTrue(Regex("""[A-Za-z0-9:_.\\-]{1,255}""").matches(unit), unit)
			}
		}
	}

	@Test
	fun anIdTooLongIsCutBetweenCharactersAndOneWithNothingInItIsNamed() {
		listOf("a", "é", "🎨", "-").map { it.repeat(400) }.forEach { id ->
			val unit = AppScope.of(id, 7).unit
			val read = Portal.appId(unit)

			assertTrue(unit.length <= 255, unit)
			assertTrue(unit.length > 255 - 16, unit)
			assertTrue(read.isNotEmpty() && id.startsWith(read), "$unit read as $read")
		}

		listOf("", ".desktop", "/", "\u0000").forEach {
			assertEquals("app-prism-unknown-00000007.scope", AppScope.of(it, 7).unit)
		}
	}

	@Test
	fun anIdIsEscapedAsSystemdEscapesOne() {
		val escape =
			System
				.getenv("PATH")
				.orEmpty()
				.split(':')
				.map { Path.of(it, "systemd-escape") }
				.firstOrNull(Files::isExecutable)

		assumeTrue(escape != null, "systemd-escape is not installed")

		// It makes a `/` a `-`, as a path's parts are joined, and an id has none: a file's name cannot.
		awkward.filter { '\n' !in it }.forEach { id ->
			val process =
				ProcessBuilder(escape.toString(), "--", id).redirectErrorStream(true).start()

			val said =
				process.inputStream
					.readAllBytes()
					.toString(Charsets.UTF_8)
					.trim()

			assertTrue(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0, said)
			assertEquals(said, AppScope.escaped(id), id)
		}
	}
}
