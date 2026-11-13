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
import prism.preferences.Moved
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

	private fun menuOf(settings: Settings, key: MenuKey, sample: Sample? = null): SeenMenu =
		seenMenuOf(key, settings.menus, settings.actions, settings.specs, sample, world)

	/** The menu [key] of [settings], arranged as [placed]. */
	private fun arranged(settings: Settings, key: MenuKey, placed: List<Placed>): SeenMenu =
		menuOf(settings.copy(menus = settings.menus.arranging(key, placed)), key)

	private fun entryOf(lines: List<MenuLine>, id: String): MenuLine.Entry =
		lines.filterIsInstance<MenuLine.Entry>().flatMap { listOf(it) + it.within }.single {
			it.id ==
				id
		}

	private fun SeenMenu.shows(id: String): Boolean =
		shown.filterIsInstance<MenuLine.Entry>().flatMap { listOf(it) + it.within }.any {
			it.id ==
				id
		}

	private fun SeenMenu.holds(id: String): Boolean =
		all.filterIsInstance<MenuLine.Entry>().flatMap { listOf(it) + it.within }.any {
			it.id ==
				id
		}

	@Test
	fun prismsOwnMenusLeaveWhatIsSeldomWantedForWhenShiftIsHeld() {
		val settings = Settings(Preferences.DEFAULT)
		val space = menuOf(settings, spaceMenu).shown
		val shift = Show.With(setOf(Held.Shift))

		for (id in listOf(
			"builtin.pin",
			"builtin.unpin",
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
			space.filterIsInstance<MenuLine.Line>().map { it.at }.toSet(),
			drawnLines(space, setOf(Held.Shift)),
		)
	}

	@Test
	fun aLineAddedStaysWhereItIsPut_FaintUntilItIsBetweenEntries_AndTwoTogetherAreOne() {
		val settings = Settings(Preferences.DEFAULT)
		val prisms = menuOf(settings, fileMenu)

		// Added, a line is last, where the menu does not draw it.
		val added = arranged(settings, fileMenu, lined(prisms))
		val last = added.shown.last() as MenuLine.Line

		assertEquals(prisms.all.size + 1, added.all.size)
		assertFalse(last.at in drawnLines(added.shown, emptySet()))

		// Moved under the first entry, it is drawn.
		val moved =
			arranged(settings, fileMenu, moved(added, Moved.of(added.shown.lastIndex, 1)))
		val line = moved.shown[1] as MenuLine.Line

		assertTrue(line.at in drawnLines(moved.shown, emptySet()))

		// Moved next to another line, the two are one; taken away, the menu is as it was.
		val other = moved.shown.indexOfFirst { it is MenuLine.Line && it != line }
		val together = arranged(settings, fileMenu, moved(moved, Moved.of(1, other - 1)))

		assertEquals(prisms.all.size, together.all.size)
		assertEquals(
			placedOf(prisms),
			placedOf(arranged(settings, fileMenu, unlined(moved, line.at))),
		)
	}

	@Test
	fun anEntryShownWithKeysOrNeverIsKeptSo_AndOneOfASubmenuToo() {
		val settings = Settings(Preferences.DEFAULT)
		val menu = menuOf(settings, fileMenu)

		val ctrl =
			arranged(
				settings,
				fileMenu,
				showing(menu, "builtin.copy-path", toggled(Show.Always, Held.Ctrl)),
			)

		val both =
			arranged(
				settings,
				fileMenu,
				showing(ctrl, "builtin.copy-path", Show.With(setOf(Held.Ctrl, Held.Alt))),
			)

		val nested = arranged(settings, fileMenu, showing(menu, "compress.zip", Show.Never))

		assertEquals(Show.With(setOf(Held.Ctrl)), entryOf(ctrl.shown, "builtin.copy-path").show)
		assertEquals(
			Show.With(setOf(Held.Alt)),
			toggled(entryOf(both.shown, "builtin.copy-path").show, Held.Ctrl),
		)
		assertEquals(Show.Always, toggled(Show.With(setOf(Held.Alt)), Held.Alt))
		// An item of a submenu is kept after it, by its own id.
		assertEquals(Show.Never, entryOf(nested.shown, "compress.zip").show)
		assertTrue(
			entryOf(nested.shown, "submenu:Compress").within.any {
				it.id ==
					"compress.zip"
			},
		)
	}

	@Test
	fun seenForAllFiles_WhatIsOnlyForSomeIsLeftOut_AndForOneFile_WhatIsNotForIt() {
		val settings = Settings(reader)
		val samples = samplesOf(fileMenu, settings.actions, settings.specs)
		val epub = samples.single { it.label == ".epub" }
		val zip = samples.single { it.label == ".zip" }

		// The files a menu can be seen for are those the actions are written for; none for the empty space.
		assertEquals("file.epub", epub.name)
		assertEquals(
			emptyList<Sample>(),
			samplesOf(spaceMenu, settings.actions, settings.specs),
		)

		// For all files: prism's own, and what is for every file (the Compress submenu); not what is for a .zip or an
		// .epub, which the menu holds all the same, and says how many.
		val all = menuOf(settings, fileMenu)

		assertTrue(all.shows("builtin.copy-path"))
		assertTrue(all.shows("compress.zip"))
		assertFalse(all.shows("mine.read"))
		assertFalse(all.shows("archive.zip.unpack"))
		assertTrue(all.holds("mine.read"))
		assertEquals(all.all.count { it is MenuLine.Entry && !it.fits }, all.unshown)
		assertTrue(all.unshown > 0)

		// For an .epub, the user's action, said to be for it, and a zip's (an .epub is a zip); not a 7z's.
		val forEpub = menuOf(settings, fileMenu, epub)

		assertEquals("For .epub", entryOf(forEpub.shown, "mine.read").note)
		assertTrue(forEpub.shows("archive.zip.unpack"))
		assertFalse(forEpub.shows("archive.7z.unpack"))
		assertFalse(menuOf(settings, fileMenu, zip).shows("mine.read"))

		// A search's results, several chosen, for all files: their actions are all for some, so the lines either side of
		// them set apart nothing here. One is shown; the other is kept, for when they are.
		val several = menuOf(settings, MenuKey.Listing(ListingKind.Results, MenuOf.Several))

		assertFalse(
			several.shown.zipWithNext().any { (a, b) ->
				a is MenuLine.Line &&
					b is MenuLine.Line
			},
		)
		assertTrue(
			several.all.count {
				it is MenuLine.Line
			} > several.shown.count { it is MenuLine.Line },
		)
	}

	@Test
	fun anEntryMovedAmongThoseShown_TheRestStayWhereTheyAre() {
		val settings = Settings(reader)
		val before = menuOf(settings, fileMenu)
		val copyPath = before.shown.indexOfFirst { it.key == "builtin.copy-path" }
		val after = arranged(settings, fileMenu, moved(before, Moved.of(copyPath, 0)))

		assertEquals("builtin.copy-path", after.shown.first().key)

		// Without what was moved, the whole menu, what it does not show among it, is as it was; a line is a line.
		fun rest(menu: SeenMenu) =
			menu.all.map { if (it is MenuLine.Line) "-" else it.key }.filter {
				it !=
					"builtin.copy-path"
			}

		assertEquals(rest(before), rest(after))
	}

	@Test
	fun theUsersActionIsOfferedOnlyInTheMenusItCanBeIn() {
		val settings = Settings(reader)

		assertTrue(menuOf(settings, fileMenu).holds("mine.read"))
		assertFalse(menuOf(settings, spaceMenu).holds("mine.read"))
		assertFalse(
			menuOf(settings, MenuKey.Listing(ListingKind.Trash, MenuOf.File)).holds("mine.read"),
		)
		assertFalse(menuOf(settings, MenuKey.Sidebar).holds("mine.read"))
	}

	@Test
	fun forAllFilesThePageSaysHowManyMoreThereAre() =
		runComposeUiTest {
			val settings = Settings(reader, page = Page.Menus)

			setContent {
				BrowserTheme {
					SettingsView(settings, {}, onApply = {}, onClose = {}, world = world)
				}
			}

			onNodeWithText("All files").assertExists()
			onNodeWithTag("entry:builtin.copy-path").assertExists()
			onNodeWithTag("entry:mine.read").assertDoesNotExist()
			onNodeWithTag("unshown").assertExists()
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
		val withLine = base.menus.arranging(fileMenu, lined(menuOf(base, fileMenu)))

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
