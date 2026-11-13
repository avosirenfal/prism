package prism.explorer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.BrowserTheme
import prism.browser.Palette
import prism.browser.World
import prism.browser.renderedPng
import prism.preferences.ActionsFile
import prism.preferences.Held
import prism.preferences.ListingKind
import prism.preferences.MenuKey
import prism.preferences.MenuOf
import prism.preferences.Placed
import prism.preferences.Preferences
import prism.preferences.Show

@OptIn(ExperimentalTestApi::class)
class MenusPageTest {
	private val world = World.Bare(Path.of("/home/someone"))
	private val fileMenu = MenuKey.Listing(ListingKind.Folder, MenuOf.File)
	private val spaceMenu = MenuKey.Listing(ListingKind.Folder, MenuOf.Space)

	/** The preferences of someone with an action of their own, for the .epub files they read. */
	private val reader =
		Preferences(
			actions =
				ActionsFile
					.parse(
						"""{"actions": [
							{"id": "mine.read", "label": "Read it", "when": {"ext": ["epub"], "kind": "file"},
							 "run": ["sh", "-c", "true", "{path}"]}
						]}""",
					).actions,
		)

	private fun linesOf(
		settings: Settings,
		key: MenuKey,
		sample: Sample? = null,
	): List<MenuLine> =
		menuLinesOf(key, settings.menus, settings.actions, settings.specs, sample, world)

	private fun entryOf(lines: List<MenuLine>, id: String): MenuLine.Entry =
		lines
			.filterIsInstance<MenuLine.Entry>()
			.flatMap { listOf(it) + it.within }
			.single { it.id == id }

	@Test
	fun prismsOwnMenusLeaveWhatIsSeldomWantedForWhenShiftIsHeld() {
		val settings = Settings(Preferences.DEFAULT)
		val space = linesOf(settings, spaceMenu)
		val shift = Show.With(setOf(Held.Shift))

		for (id in listOf(
			"builtin.pin",
			"builtin.hide-from-sidebar",
			"builtin.hidden-files",
			"builtin.hidden-folders",
			"builtin.refresh",
		))
			assertEquals(shift, entryOf(space, id).show, id)
		assertEquals(Show.Always, entryOf(space, "builtin.paste").show)
		// Shift-only entries are not in the menu opened without it, and are with it.
		assertFalse(entryOf(space, "builtin.refresh").shownWith(emptySet()))
		assertTrue(entryOf(space, "builtin.refresh").shownWith(setOf(Held.Shift, Held.Ctrl)))
		// Prism's own arrangement has no line first, last, or two together: each is drawn.
		assertTrue(space.first() is MenuLine.Entry && space.last() is MenuLine.Entry)
		assertEquals(
			space
				.filterIsInstance<MenuLine.Line>()
				.map {
					it.at
				}.toSet(),
			drawnLines(space, setOf(Held.Shift)),
		)
	}

	@Test
	fun aLineAddedStaysWhereItIsPut_FaintUntilItIsBetweenEntries_AndTwoTogetherAreOne() {
		val settings = Settings(Preferences.DEFAULT)
		val prisms = linesOf(settings, fileMenu)

		fun arranged(placed: List<Placed>) =
			linesOf(settings.copy(menus = settings.menus.arranging(fileMenu, placed)), fileMenu)

		// Added, a line is last, where the menu does not draw it.
		val added = arranged(lined(prisms))
		val last = added.last() as MenuLine.Line

		assertEquals(prisms.size + 1, added.size)
		assertFalse(last.at in drawnLines(added, emptySet()))

		// Moved under the first entry, it is drawn.
		val moved = arranged(moved(added, last.at, 1))

		assertTrue(moved[1] is MenuLine.Line)
		assertTrue(1 in drawnLines(moved, emptySet()))

		// Moved next to another line, the two are one; taken away, the menu is as it was.
		val other = moved.indexOfFirst { it is MenuLine.Line && it.at > 1 }
		val together = arranged(moved(moved, 1, other - 1))

		assertEquals(prisms.size, together.size)
		assertEquals(placedOf(prisms), placedOf(arranged(unlined(moved, 1))))
	}

	@Test
	fun anEntryShownWithKeysOrNeverIsKeptSo_AndOneOfASubmenuToo() {
		val settings = Settings(Preferences.DEFAULT)
		val lines = linesOf(settings, fileMenu)

		val ctrl = showing(lines, "builtin.copy-path", toggled(Show.Always, Held.Ctrl))

		val both =
			showing(
				linesOfPlaced(settings, ctrl),
				"builtin.copy-path",
				Show.With(setOf(Held.Ctrl, Held.Alt)),
			)

		val nested = showing(lines, "compress.zip", Show.Never)

		assertEquals(
			Show.With(setOf(Held.Ctrl)),
			entryOf(linesOfPlaced(settings, ctrl), "builtin.copy-path").show,
		)
		assertEquals(
			Show.With(setOf(Held.Alt)),
			toggled(entryOf(linesOfPlaced(settings, both), "builtin.copy-path").show, Held.Ctrl),
		)
		assertEquals(Show.Always, toggled(Show.With(setOf(Held.Alt)), Held.Alt))
		// An item of a submenu is kept after it, by its own id.
		assertEquals(Show.Never, entryOf(linesOfPlaced(settings, nested), "compress.zip").show)
		assertTrue(
			entryOf(linesOfPlaced(settings, nested), "submenu:Compress").within.any {
				it.id ==
					"compress.zip"
			},
		)
	}

	private fun linesOfPlaced(settings: Settings, placed: List<Placed>): List<MenuLine> =
		linesOf(settings.copy(menus = settings.menus.arranging(fileMenu, placed)), fileMenu)

	@Test
	fun aMenuIsSeenForAFileOfATypeTheActionsAreFor_TheirsFaintThatAreNotForIt() {
		val settings = Settings(reader)
		val samples = samplesOf(fileMenu, settings.actions, settings.specs)
		val epub = samples.single { it.label == ".epub" }

		// The files a menu can be seen for are those the actions are written for; none for the empty space.
		assertEquals("file.epub", epub.name)
		assertTrue(samples.any { it.label == ".zip" })
		assertEquals(
			emptyList<Sample>(),
			samplesOf(spaceMenu, settings.actions, settings.specs),
		)

		// The user's action is said to be for .epub, and is in the menu for one; a zip's are not.
		val forEpub = linesOf(settings, fileMenu, epub)

		assertEquals("For .epub", entryOf(forEpub, "mine.read").note)
		assertTrue(entryOf(forEpub, "mine.read").fits)
		assertFalse(entryOf(forEpub, "archive.7z.unpack").fits)
		assertFalse(
			entryOf(
				linesOf(
					settings,
					fileMenu,
					samples.single {
						it.label == ".zip"
					},
				),
				"mine.read",
			).fits,
		)
		// Seen for any file, every entry is as the keys and the switch say.
		assertTrue(
			linesOf(settings, fileMenu).filterIsInstance<MenuLine.Entry>().all { it.fits },
		)
	}

	@Test
	fun theUsersActionIsOfferedOnlyInTheMenusItCanBeIn() {
		val settings = Settings(reader)

		assertTrue(
			linesOf(settings, fileMenu).any {
				it is MenuLine.Entry &&
					it.id == "mine.read"
			},
		)
		assertFalse(
			linesOf(settings, spaceMenu).any {
				it is MenuLine.Entry &&
					it.id == "mine.read"
			},
		)
		assertFalse(
			linesOf(settings, MenuKey.Listing(ListingKind.Trash, MenuOf.File)).any {
				it is MenuLine.Entry &&
					it.id == "mine.read"
			},
		)
		assertFalse(
			linesOf(settings, MenuKey.Sidebar).any {
				it is MenuLine.Entry &&
					it.id == "mine.read"
			},
		)
	}

	@Test
	fun aMenuIsChosenByWhereItIsAndWhatItIsOf_ItsEntriesChanged_AndApplyKeepsThem() =
		runComposeUiTest {
			var settings by mutableStateOf(Settings(Preferences.DEFAULT, page = Page.Menus))

			setContent {
				BrowserTheme {
					SettingsView(settings, { settings = it }, onApply = {}, onClose = {}, world = world)
				}
			}

			// A folder's empty space: Refresh only with Shift, until Shift is taken from it. Its end is wheeled to.
			onNodeWithTag("menu-of:Empty space").performClick()
			waitForIdle()
			assertEquals(spaceMenu, settings.menuing)
			onNodeWithTag("menu-editor").performMouseInput {
				moveTo(center)
				scroll(40f)
			}
			waitForIdle()
			onNodeWithTag("key:builtin.refresh:Shift").performClick()
			waitForIdle()
			assertEquals(
				Placed.Slot("builtin.refresh"),
				settings.menus.arrangementOf(spaceMenu)?.single {
					it is Placed.Slot &&
						it.id == "builtin.refresh"
				},
			)

			// Properties switched off is never shown; the preferences keep what was changed.
			onNodeWithTag("shown:builtin.properties").performClick()
			waitForIdle()
			assertNotNull(
				settings.over(Preferences.DEFAULT).menu.arranged[spaceMenu]?.single {
					it == Placed.Slot("builtin.properties", Show.Never)
				},
			)
			onNodeWithText("Apply").assertIsEnabled()

			// Put back as prism has it.
			onNodeWithTag("reset-menu").performClick()
			waitForIdle()
			assertFalse(settings.menus.arrangesItself(spaceMenu))
			onNodeWithTag("reset-menu").assertIsNotEnabled()

			// A search's results are menued as folders are, until they are given their own.
			onNodeWithTag("menus:Search results").performClick()
			waitForIdle()

			val results = MenuKey.Listing(ListingKind.Results, MenuOf.Space)

			assertEquals(results, settings.menuing)
			assertFalse(settings.menus.arrangesItself(results))
			onNodeWithTag("own-menu").performClick()
			waitForIdle()
			assertTrue(settings.menus.arrangesItself(results))

			// The sidebar has no files to be of.
			onNodeWithTag("menus:Sidebar").performClick()
			waitForIdle()
			assertEquals(MenuKey.Sidebar, settings.menuing)
			onNodeWithTag("menu-of:Empty space").assertDoesNotExist()
			onNodeWithTag("entry:builtin.unpin").assertExists()
		}

	@Test
	fun theMenuIsSeenWithTheKeysChosenHeld() =
		runComposeUiTest {
			var settings by mutableStateOf(
				Settings(Preferences.DEFAULT, page = Page.Menus, menuing = spaceMenu),
			)

			setContent {
				BrowserTheme {
					SettingsView(settings, { settings = it }, onApply = {}, onClose = {}, world = world)
				}
			}

			onNodeWithTag("holding:Shift").performClick()
			waitForIdle()
			assertEquals(setOf(Held.Shift), settings.holding)
			onNodeWithTag("holding:Shift").performClick()
			waitForIdle()
			assertEquals(emptySet<Held>(), settings.holding)
			// What only changes how the menu is seen changes nothing to apply.
			onNodeWithText("Apply").assertIsNotEnabled()
		}

	/**
	 * The page drawn headless, in both palettes: a file's menu seen for an .epub with Shift held, a line added and not
	 * yet moved; and the empty space's, as prism arranges it. With `-Pexplorer.screenshots=DIR`, kept there.
	 */
	@Test
	fun thePageIsDrawn() {
		val base = Settings(reader, page = Page.Menus)
		val epub = samplesOf(fileMenu, base.actions, base.specs).single { it.label == ".epub" }
		val withLine = base.menus.arranging(fileMenu, lined(linesOf(base, fileMenu)))

		for ((name, settings) in listOf(
			"file" to base.copy(menus = withLine, seenFor = epub, holding = setOf(Held.Shift)),
			"space" to base.copy(menuing = spaceMenu),
			"results" to base.copy(menuing = MenuKey.Listing(ListingKind.Results, MenuOf.Several)),
		))
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png =
					renderedPng(1100, 1300, palette, scale = 2f) {
						SettingsView(settings, {}, onApply = {}, onClose = {}, world = world)
					}

				assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
				System.getProperty("explorer.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
					Path
						.of(dir)
						.createDirectories()
						.resolve("menus-$name-$tone.png")
						.writeBytes(png)
				}
			}
	}
}
