package prism.explorer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import prism.index.Exclusion
import prism.index.Exclusions
import prism.preferences.Excluded
import prism.preferences.Excluding
import prism.preferences.Preferences

/**
 * The page of what the index leaves out: what it edits as a value (prism's own rules switched and changed, the
 * user's own added and removed, and only what reads as a rule ever added), and the page itself, in the preferences'
 * window and in the launcher's settings, typed into and applied.
 */
@OptIn(ExperimentalTestApi::class)
class ExcludingPageTest {
	private val home = Path.of("/home/u")

	private fun edits(kept: Excluding = Excluding()) = ExcludingEdits(kept, home = home)

	// ---- the edits, as a value ---------------------------------------------------------------------------------------

	@Test
	fun prismsOwnRulesAreSwitchedAndChangedAndWhatIsKeptIsOnlyWhatDiffers() {
		val first = edits()

		assertEquals(Exclusions.DEFAULTS, first.builtIn.map { it.written })
		assertTrue(first.own.isEmpty())
		assertFalse(first.changed)

		val off = first.switched(".git/", false)

		assertEquals(Excluding(off = setOf(".git/")), off.kept)
		assertFalse(off.builtIn.single { it.written == ".git/" }.on)
		assertEquals(Excluding(), off.switched(".git/", true).kept)

		val hidden = first.doing("/nix", Excluded.Hidden)

		assertEquals(Excluding(changed = mapOf("/nix" to Excluded.Hidden)), hidden.kept)
		assertEquals(Exclusion.Hidden, hidden.builtIn.single { it.written == "/nix" }.exclusion)
		// Back to what it does as it comes is no difference kept.
		assertEquals(Excluding(), hidden.doing("/nix", Excluded.Ignored).kept)

		// Reset puts every one of prism's own back, and leaves the user's.
		val own = hidden.switched(".git/", false).typing { copy(text = "build/") }.added()

		assertTrue(own.changed)
		assertEquals(Excluding(own = mapOf("build/" to Excluded.Ignored)), own.reset().kept)
	}

	@Test
	fun aRuleIsAddedOnlyOnceItReadsAsOneAndSaysHowItWasRead() {
		val first = edits()

		// Nothing typed is nothing wrong, and nothing to add.
		assertNull(first.addingProblem)
		assertFalse(first.addable)
		assertEquals(first, first.added())

		val folder = first.typing { copy(text = "~/Games", excluded = Excluded.Hidden) }

		assertEquals(RuleKind.Folder, folder.addingKind)
		assertTrue(folder.addable)
		// A folder that is not there can be added, and is said not to be.
		assertEquals(home.resolve("Games"), folder.missing { false })
		assertNull(folder.missing { true })

		val added = folder.added()

		assertEquals(Excluding(own = mapOf("~/Games" to Excluded.Hidden)), added.kept)
		// The field is empty for the next, which is the same kind unless said otherwise.
		assertEquals(RuleDraft(excluded = Excluded.Hidden), added.adding)

		val query = added.typing { copy(text = "build/", excluded = Excluded.Ignored) }

		assertEquals(RuleKind.Search, query.addingKind)

		val both = query.added()

		assertEquals(listOf("~/Games", "build/"), both.own.map { it.written })

		// What is no rule, or is one already, is not added, and says why.
		fun problem(text: String) = both.typing { copy(text = text) }.addingProblem

		assertEquals("A rule can't use recent:", problem("recent:1w"))
		assertEquals("There is already a rule for this", problem("build/"))
		assertEquals("There is already a rule for this", problem("/home/u/Games/"))
		assertEquals("There is already a rule for this", problem(".git/"))
		assertEquals("The root folder can't be left out", problem("/"))
		assertEquals(
			both,
			both
				.typing {
					copy(text = "recent:1w")
				}.added()
				.typing { copy(text = "") },
		)

		// One of the user's own is made the other kind where it is, and removed.
		val hidden = both.doing("build/", Excluded.Hidden)

		assertEquals(
			Excluding(own = mapOf("~/Games" to Excluded.Hidden, "build/" to Excluded.Hidden)),
			hidden.kept,
		)
		assertEquals(
			Excluding(own = mapOf("build/" to Excluded.Hidden)),
			hidden.removed("~/Games").kept,
		)
	}

	@Test
	fun eachWindowWritesTheRulesOnlyWhereItChangedThem() {
		val base = Preferences(excluding = Excluding(off = setOf(".git/")))
		// Changed meanwhile by another window.
		val now = base.copy(excluding = Excluding(off = setOf(".hg/")))

		// Not changed here: what is there now is kept.
		assertEquals(now.excluding, Settings(base).over(now).excluding)
		assertEquals(now.excluding, LauncherSettings(base).over(now).excluding)

		val changed = ExcludingEdits.of(base.excluding).switched(".svn/", false)

		assertEquals(changed.kept, Settings(base, excluding = changed).over(now).excluding)
		assertEquals(
			changed.kept,
			LauncherSettings(base, excluding = changed).over(now).excluding,
		)
		assertTrue(Settings(base, excluding = changed).changed)
	}

	// ---- the page ----------------------------------------------------------------------------------------------------

	private fun field(tag: String) = hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))

	@Test
	fun rulesAreAddedSwitchedRemovedAndAppliedOnThePreferencesPage() =
		runComposeUiTest {
			var applied = Preferences.DEFAULT
			var settings by mutableStateOf(Settings(applied, page = Page.Indexing))

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
			onNodeWithTag("rule-add").assertIsNotEnabled()

			// What is no rule says why, and cannot be added.
			onNode(field("rule-add-text")).performTextReplacement("new:1d")
			waitForIdle()
			onNodeWithTag("rule-add-problem").assertExists()
			onNodeWithTag("rule-add").assertIsNotEnabled()

			onNode(field("rule-add-text")).performTextReplacement("build/")
			waitForIdle()
			onNodeWithTag("rule-add-kind").assertExists()
			onNodeWithTag("rule-add-doing:Hidden").performClick()
			waitForIdle()
			onNodeWithTag("rule-add").assertIsEnabled().performClick()
			waitForIdle()
			onNodeWithTag("rule:build/").assertExists()

			// One of prism's own, switched off; another, made hidden.
			onNodeWithTag("rule-on:.git/").performClick()
			waitForIdle()
			onNodeWithTag("rule-doing:.hg/:Hidden").performClick()
			waitForIdle()

			onNodeWithText("Apply").assertIsEnabled().performClick()
			waitForIdle()
			assertEquals(
				Excluding(
					off = setOf(".git/"),
					changed = mapOf(".hg/" to Excluded.Hidden),
					own = mapOf("build/" to Excluded.Hidden),
				),
				applied.excluding,
			)

			// Removed, and put back as they come.
			onNodeWithTag("rule-remove:build/").performClick()
			waitForIdle()
			assertEquals(0, onAllNodesWithTag("rule:build/").fetchSemanticsNodes().size)
			onNodeWithTag("rules-reset").performClick()
			waitForIdle()
			onNodeWithText("Apply").performClick()
			waitForIdle()
			assertEquals(Excluding(), applied.excluding)
		}

	@Test
	fun theLaunchersSettingsHaveTheSamePageOverTheSameRules() =
		runComposeUiTest {
			var applied =
				Preferences(
					excluding =
						Excluding(
							own =
								mapOf("~/Games" to Excluded.Hidden),
						),
				)

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
			onNodeWithTag("page:Indexing").performClick()
			waitForIdle()
			onNodeWithTag("rule:~/Games").assertExists()
			onNodeWithTag("rule-doing:~/Games:Ignored").performClick()
			waitForIdle()
			onNodeWithText("Apply").assertIsEnabled().performClick()
			waitForIdle()
			assertEquals(Excluding(own = mapOf("~/Games" to Excluded.Ignored)), applied.excluding)
			assertEquals(Preferences.DEFAULT.launcher, applied.launcher)
		}

	/** Drawn headless, in both palettes: with `-Pexplorer.screenshots=DIR`, kept there. */
	@Test
	fun theIndexingPageDraws() {
		val some =
			ExcludingEdits.of(
				Excluding(
					off = setOf(".hg/", ".svn/"),
					changed = mapOf(".cache/" to Excluded.Hidden),
					own =
						linkedMapOf(
							"~/Games" to Excluded.Hidden,
							"build/" to Excluded.Ignored,
							"/mnt/archive/old" to Excluded.Hidden,
							"ext:{o,obj,pyc} -~/src/" to Excluded.Ignored,
							"is:dir ^tmp_" to Excluded.Ignored,
						),
				),
			)

		val drawn: Map<String, @androidx.compose.runtime.Composable () -> Unit> =
			mapOf(
				"indexing-first" to {
					SettingsView(Settings(Preferences.DEFAULT, page = Page.Indexing), {}, {}, {})
				},
				"indexing" to {
					SettingsView(
						Settings(Preferences.DEFAULT, page = Page.Indexing, excluding = some),
						{},
						{},
						{},
					)
				},
				"indexing-adding" to {
					SettingsView(
						Settings(
							Preferences.DEFAULT,
							page = Page.Indexing,
							excluding =
								some.typing {
									copy(
										text = "~/Videos/raw*/",
										excluded = Excluded.Hidden,
									)
								},
						),
						{},
						{},
						{},
					)
				},
				"indexing-problem" to {
					SettingsView(
						Settings(
							Preferences.DEFAULT,
							page = Page.Indexing,
							excluding = some.typing { copy(text = "report new:1w") },
						),
						{},
						{},
						{},
					)
				},
				"indexing-launcher" to {
					LauncherSettingsView(
						LauncherSettings(
							Preferences.DEFAULT,
							page = LauncherPage.Indexing,
							excluding = some,
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
