package prism.explorer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.BrowserTheme
import prism.browser.Palette
import prism.browser.renderedPng
import prism.preferences.Preferences

/**
 * The page of search aliases: what it edits as a value (what is typed, what is wrong with it, what is written), and
 * the page itself, in the preferences' window and in the launcher's settings, typed into and applied.
 */
@OptIn(ExperimentalTestApi::class)
class AliasesPageTest {
	private val written = mapOf("projects" to "~/projects/", "raws" to "~/Photos/ ext:raw")

	// ---- the edits, as a value ---------------------------------------------------------------------------------------

	@Test
	fun whatIsTypedIsWrittenOnlyWhenNothingIsWrongWithIt() {
		val edits = AliasEdits.of(written)

		assertEquals(written, edits.written)
		assertTrue(edits.fit)

		// One more, as it is typed: nothing of it yet is nothing wrong; half of it is.
		val adding = edits.added()

		assertEquals(written, adding.written)
		assertNull(adding.problemAt(2))

		val named = adding.set(2) { copy(name = "docs") }

		assertEquals("Enter a search", named.problemAt(2)?.message)
		assertNull(named.written)
		assertFalse(named.fit)

		val whole = named.set(2) { copy(query = "~/Documents/ ext:pdf") }

		assertNull(whole.problemAt(2))
		assertEquals(written + ("docs" to "~/Documents/ ext:pdf"), whole.written)

		// Renamed, edited and removed.
		assertEquals(
			mapOf("code" to "~/projects/", "raws" to "~/Photos/ ext:{raw,dng}"),
			edits
				.set(0) {
					copy(name = "code")
				}.set(1) { copy(query = "~/Photos/ ext:{raw,dng}") }
				.written,
		)
		assertEquals(mapOf("raws" to "~/Photos/ ext:raw"), edits.removed(0).written)
	}

	@Test
	fun eachThingWrongIsSaidOfTheRowItIsWrongWith() {
		fun problem(name: String, query: String, among: Map<String, String> = written) =
			AliasEdits
				.of(among)
				.added(AliasDraft(name, query))
				.let {
					it.problemAt(it.drafts.lastIndex)
				}?.message

		assertEquals("Use only letters and digits", problem("my docs", "x"))
		assertEquals("Use only letters and digits", problem("docs@", "x"))
		assertEquals("Enter a name", problem("", "x"))
		assertEquals("“projects@” is already defined", problem("Projects", "x"))
		assertEquals("Unknown alias “nosuch@”", problem("docs", "nosuch@ x"))
		assertEquals("An alias can't use itself", problem("docs", "docs@ x"))
		assertEquals("Missing closing quote", problem("docs", "\"tax"))
		assertTrue(problem("docs", "foo:bar").orEmpty().startsWith("Unknown modifier"))
		assertNull(problem("docs", "raws@ 2024/"))

		// Two that use each other: neither is one, and both say so.
		val looped =
			AliasEdits.of(mapOf("a" to "b@ x", "b" to "a@ y"))

		assertEquals("“b@” leads back to this alias", looped.problemAt(0)?.message)
		assertEquals("“a@” leads back to this alias", looped.problemAt(1)?.message)
		assertNull(looped.written)

		// Removing one that another uses leaves the other with a problem, not written.
		val orphaned = AliasEdits.of(written + ("r24" to "raws@ 2024/")).removed(1)

		assertEquals("Unknown alias “raws@”", orphaned.problemAt(1)?.message)
		assertNull(orphaned.written)
	}

	@Test
	fun aFolderNamedWithoutItsSlashIsHintedAtAndMended() {
		val home = Path.of("/home/me")
		val folders = setOf(Path.of("/home/me/projects"), Path.of("/srv/Photos"))

		fun hint(query: String) =
			AliasEdits(listOf(AliasDraft("x", query))).hintAt(0, home) { it in folders }

		assertEquals(FolderHint("~/projects", "~/projects/"), hint("~/projects"))
		assertEquals(
			FolderHint("/srv/Photos", "/srv/Photos/ ext:raw"),
			hint("/srv/Photos ext:raw"),
		)
		assertEquals(
			FolderHint("/srv/Photos", "ext:raw /srv/Photos/"),
			hint("ext:raw /srv/Photos"),
		)
		// Already inside it, not a folder, or not a path from somewhere known: nothing to say.
		assertNull(hint("~/projects/"))
		assertNull(hint("~/notes.txt"))
		assertNull(hint("projects"))
		assertNull(hint("-~/projects"))
		assertNull(hint("~/proj*"))
		// And nothing of a query with something wrong with it.
		assertNull(hint("~/projects nosuch@"))
	}

	@Test
	fun eachWindowWritesTheAliasesOnlyWhereItChangedThem() {
		val base = Preferences(aliases = written)
		val elsewhere = base.copy(aliases = written + ("docs" to "~/Documents/"))

		// Unchanged here, what another window wrote meanwhile is kept, by either window of settings.
		assertEquals(elsewhere, Settings(base).over(elsewhere))
		assertEquals(elsewhere, LauncherSettings(base).over(elsewhere))

		val edits = AliasEdits.of(written).removed(0)

		assertEquals(
			mapOf("raws" to "~/Photos/ ext:raw"),
			Settings(base, aliasing = edits).written.aliases,
		)
		assertEquals(
			mapOf("raws" to "~/Photos/ ext:raw"),
			LauncherSettings(base, aliasing = edits).written.aliases,
		)
		assertTrue(Settings(base, aliasing = edits).changed)
		assertNull(Settings(base, aliasing = edits).blocked)

		// Something wrong with one: nothing of them is written, and the window says why it does not apply.
		val wrong = AliasEdits.of(written).added(AliasDraft("docs", ""))

		assertEquals(written, Settings(base, aliasing = wrong).written.aliases)
		assertEquals(AliasEdits.UNFIT, Settings(base, aliasing = wrong).blocked)
		assertEquals(AliasEdits.UNFIT, LauncherSettings(base, aliasing = wrong).blocked)
		assertFalse(LauncherSettings(base, aliasing = wrong).changed)
	}

	// ---- the page ----------------------------------------------------------------------------------------------------

	private fun field(tag: String) = hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))

	@Test
	fun aliasesAreAddedTypedRemovedAndAppliedOnThePreferencesPage() =
		runComposeUiTest {
			var applied = Preferences.DEFAULT
			var settings by mutableStateOf(Settings(applied, page = Page.Aliases))

			setContent {
				BrowserTheme {
					SettingsView(
						settings,
						{ settings = it },
						onApply = {
							applied = settings.over(applied)
							settings = settings.applied(applied)
						},
						onClose = {},
					)
				}
			}
			waitForIdle()

			// None yet: the page shows what one is, and an example is added as it is.
			onNodeWithText("No aliases").assertExists()
			onNodeWithTag("alias-example:projects").performClick()
			waitForIdle()
			assertEquals(listOf(AliasDraft("projects", "~/projects/")), settings.aliasing.drafts)
			onNodeWithText("Apply").assertIsEnabled()

			// One more, typed: its name takes the keyboard, and Enter goes on to its query.
			onNodeWithTag("alias-add").performClick()
			waitForIdle()
			onNode(field("alias-name:1")).assertIsFocused()
			onNode(field("alias-name:1")).performTextReplacement("my raws")
			waitForIdle()
			onNodeWithTag("alias-problem:1").assertExists()
			onNodeWithText("Use only letters and digits").assertExists()
			onNodeWithTag("settings-state").assertExists()
			onNodeWithText(AliasEdits.UNFIT).assertExists()
			onNodeWithText("Apply").assertIsNotEnabled()

			onNode(field("alias-name:1")).performTextReplacement("raws")
			onNode(field("alias-name:1")).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()
			onNode(field("alias-query:1")).assertIsFocused()
			onNodeWithText("Enter a search").assertExists()
			onNode(field("alias-query:1")).performTextReplacement("projects@ ext:raw")
			waitForIdle()
			assertEquals(0, onAllNodesWithTag("alias-problem:1").fetchSemanticsNodes().size)

			// Enter in the last query begins another, which is nothing until something is typed in it.
			onNode(field("alias-query:1")).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()
			onNode(field("alias-name:2")).assertIsFocused()

			onNodeWithText("Apply").assertIsEnabled().performClick()
			waitForIdle()
			assertEquals(
				mapOf("projects" to "~/projects/", "raws" to "projects@ ext:raw"),
				applied.aliases,
			)
			onNodeWithText("Apply").assertIsNotEnabled()

			// The one another uses, removed: the other says so, and nothing is applied until it is mended.
			onNodeWithTag("alias-remove:0").performClick()
			waitForIdle()
			onNodeWithText("Unknown alias “projects@”").assertExists()
			onNodeWithText("Apply").assertIsNotEnabled()
			onNode(field("alias-query:0")).performTextReplacement("ext:raw")
			waitForIdle()
			onNodeWithText("Apply").assertIsEnabled().performClick()
			waitForIdle()
			assertEquals(mapOf("raws" to "ext:raw"), applied.aliases)
		}

	@Test
	fun theLaunchersSettingsHaveTheSamePageOverTheSameAliases() =
		runComposeUiTest {
			var applied = Preferences(aliases = written)
			var settings by mutableStateOf(LauncherSettings(applied))

			setContent {
				BrowserTheme {
					LauncherSettingsView(
						settings,
						emptyList(),
						{ settings = it },
						onApply = {
							applied = settings.over(applied)
							settings = settings.applied(applied)
						},
						onClose = {},
					)
				}
			}
			waitForIdle()
			onNodeWithTag("page:Aliases").performClick()
			waitForIdle()
			onNode(field("alias-query:1")).performTextReplacement("~/Photos/ ext:{raw,dng}")
			waitForIdle()
			onNodeWithText("Apply").assertIsEnabled().performClick()
			waitForIdle()
			assertEquals(
				mapOf("projects" to "~/projects/", "raws" to "~/Photos/ ext:{raw,dng}"),
				applied.aliases,
			)
			// The launcher's own settings are as they were.
			assertEquals(Preferences.DEFAULT.launcher, applied.launcher)
		}

	/** Drawn headless, in both palettes: with `-Pexplorer.screenshots=DIR`, kept there. */
	@Test
	fun theAliasesPageDraws(
		@org.junit.jupiter.api.io.TempDir folder: Path,
	) {
		val photos = folder.resolve("Photos").createDirectories()

		val some =
			AliasEdits.of(
				written +
					mapOf(
						"raws2024" to "raws@ 2024/ -is:hidden",
						"invoices" to
							"~/Documents/Accounts/ r/^inv(oice)?[-_ ]?\\d{4}/i ext:{pdf,xlsx} -draft new:1w",
					),
			)

		val wrong =
			some
				.set(2) { copy(query = "rawz@ 2024/") }
				.added(AliasDraft("my docs", "~/Documents/"))
				.added(AliasDraft("loop", "loop@ x"))
				.added(AliasDraft("scans", "$photos ext:tiff"))
				.added()

		val drawn: Map<String, @androidx.compose.runtime.Composable () -> Unit> =
			mapOf(
				"aliases-empty" to {
					SettingsView(Settings(Preferences.DEFAULT, page = Page.Aliases), {}, {}, {})
				},
				"aliases" to {
					SettingsView(
						Settings(Preferences.DEFAULT, page = Page.Aliases, aliasing = some),
						{},
						{},
						{},
					)
				},
				"aliases-problems" to {
					SettingsView(
						Settings(Preferences.DEFAULT, page = Page.Aliases, aliasing = wrong),
						{},
						{},
						{},
					)
				},
				"aliases-launcher" to {
					LauncherSettingsView(
						LauncherSettings(
							Preferences.DEFAULT,
							page = LauncherPage.Aliases,
							aliasing = some,
						),
						emptyList(),
						{},
						{},
						{},
					)
				},
			)

		for ((name, content) in drawn)
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png = renderedPng(980, 760, palette, scale = 2f) { content() }

				assertTrue(png.size > 10_000, "$name: ${png.size} bytes")
				System.getProperty("explorer.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
					Path
						.of(dir)
						.also { it.createDirectories() }
						.resolve("$name-$tone.png")
						.writeBytes(png)
				}
			}
	}
}
