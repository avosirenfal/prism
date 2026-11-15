package prism.launcher

import androidx.compose.runtime.CompositionLocalProvider
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.Palette
import prism.browser.aliasesOf
import prism.browser.renderedPng
import prism.desktop.Icons
import prism.index.query.Aliases
import prism.index.query.QueryText
import prism.preferences.LauncherPreferences

/**
 * Search aliases in the launcher: the files are looked for as a `name@` typed means, the aliases a word could be
 * naming are listed after what is started, and one chosen is put where the word was.
 */
class LauncherAliasesTest {
	private val now = Instant.parse("2026-11-14T10:00:00Z")

	private val aliases =
		aliasesOf(
			mapOf(
				"projects" to "~/projects/",
				"raws" to "~/Photos/ ext:raw",
				"fire" to "ext:log",
			),
		)

	private fun state(query: String = "") =
		LauncherState(installed = Installed.of(Programs.all), aliases = aliases, now = now)
			.let {
				if (query.isEmpty())
					it
				else
					step(it, LauncherEvent.Typed(query)).state
			}

	private fun LauncherState.titles(section: String) =
		sections
			.firstOrNull { it.title == section }
			?.entries
			?.map { it.title }
			.orEmpty()

	@Test
	fun theFilesAreLookedForAsAnAliasMeans() {
		val asked = step(state(), LauncherEvent.Typed("raws@ 2024"))

		assertEquals(
			listOf<LauncherEffect>(
				LauncherEffect.Search(
					"raws@ 2024",
					QueryText.read("~/Photos/ ext:raw 2024").query,
					LauncherPreferences().fileResults.number,
				),
			),
			asked.effects,
		)

		// Changed in a settings window while the launcher is shown: looked for again, as it now means.
		val changed =
			step(
				asked.state,
				LauncherEvent.AliasesChanged(aliasesOf(mapOf("raws" to "~/Pictures/ ext:dng"))),
			)

		assertEquals(
			QueryText.read("~/Pictures/ ext:dng 2024").query,
			(changed.effects.single() as LauncherEffect.Search).query,
		)
		// The same again changes nothing, and asks for nothing.
		assertEquals(
			emptyList<LauncherEffect>(),
			step(changed.state, LauncherEvent.AliasesChanged(changed.state.aliases)).effects,
		)
	}

	@Test
	fun theAliasesAWordCouldNameAreListedAfterWhatIsStartedAndChosenIntoTheField() {
		val typed = state("fire")

		// An application of that name is still the first thing shown, which Enter starts.
		assertEquals("Firefox", typed.current?.title)
		assertEquals(listOf("fire@"), typed.titles("Aliases"))
		assertTrue(
			typed.sections.map { it.title }.let {
				it.indexOf("Applications") <
					it.indexOf("Aliases")
			},
		)

		val alias = typed.entries.single { it is Entry.Named }

		assertEquals("ext:log", alias.detail)
		assertEquals(listOf<EntryAction>(EntryAction.Use), actionsOf(alias))

		// Chosen, its name is in the field with a space after it, the launcher stays, and the files are looked for.
		val chosen = step(typed, LauncherEvent.Chosen(typed.entries.indexOf(alias)))

		assertEquals("fire@ ", chosen.state.query)
		assertEquals(
			QueryText.read("ext:log").query,
			(chosen.effects.single() as LauncherEffect.Search).query,
		)
		assertNull(chosen.state.completing)

		// Only the last word is one being named; and none is where the launcher finds no files.
		assertEquals(listOf("projects@"), state("x pro").titles("Aliases"))
		assertEquals(emptyList<String>(), state("pro ").titles("Aliases"))
		assertEquals(
			emptyList<String>(),
			step(
				state("pro"),
				LauncherEvent.PreferencesChanged(LauncherPreferences(files = false)),
			).state.titles("Aliases"),
		)
		assertEquals(
			emptyList<String>(),
			state()
				.copy(aliases = Aliases.NONE)
				.let {
					step(it, LauncherEvent.Typed("pro")).state
				}.titles("Aliases"),
		)
	}

	/** Drawn headless, in both palettes: with `-Plauncher.screenshots=DIR`, kept there. */
	@Test
	fun theLauncherWithAliasesDraws() {
		val typed = state("fire")

		val used =
			step(
				typed,
				LauncherEvent.Chosen(typed.entries.indexOfFirst { it is Entry.Named }),
			).state

		val (width, height) = LauncherDims.sizeOf(typed.preferences)

		for ((name, state) in mapOf("alias-completing" to typed, "alias-used" to used))
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png =
					renderedPng(width.value.toInt(), height.value.toInt(), palette, scale = 2f) {
						CompositionLocalProvider(
							LocalIconImages provides IconImages(Icons { _, _, _ -> null }),
						) {
							LauncherPane(state, corners = Corners.Round)
						}
					}

				assertTrue(png.size > 10_000, "$name: ${png.size} bytes")
				System.getProperty("launcher.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
					Path
						.of(dir)
						.also { it.createDirectories() }
						.resolve("$name-$tone.png")
						.toFile()
						.writeBytes(png)
				}
			}
	}
}
