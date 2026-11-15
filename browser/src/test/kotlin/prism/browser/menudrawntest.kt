package prism.browser

import androidx.compose.ui.unit.IntOffset
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.Fixtures.picker
import prism.preferences.Held
import prism.preferences.Hidden
import prism.preferences.Kind

/**
 * The menus as they are drawn, in both palettes: something done, a box and a ring each look like what they are, in
 * every menu. Drawn headless, which is the test that they compose at all; with `-Pbrowser.screenshots=DIR` what was
 * drawn is kept there to look at.
 */
class MenuDrawnTest {
	private fun kept(name: String, png: ByteArray) {
		assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
		System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
			Path
				.of(dir)
				.createDirectories()
				.resolve("$name.png")
				.writeBytes(png)
		}
	}

	/** [state]'s window with each of [menus] open over it at its point, in both palettes. */
	private fun drawn(
		name: String,
		state: BrowserState,
		vararg menus: Pair<Menu<BrowserEvent>, IntOffset>,
	) {
		for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark))
			kept(
				"$name-$tone",
				renderedPng(1100, 680, palette, scale = 2f) {
					FileBrowser(state)
					for ((menu, at) in menus)
						MenuPopup(menu, MenuAnchor.At(at), {}, {})
				},
			)
	}

	@Test
	fun theFilterMenuDrawsWithNothingTickedAndWithTwo() {
		val none = picker(hidden = Hidden())

		val two =
			after(
				none,
				BrowserEvent.ToggledKind(Kind.Images),
				BrowserEvent.ToggledKind(Kind.Videos),
			)

		drawn("menu-filters", none, filterMenuOf(none) to UNDER_FILTERS)
		drawn("menu-filters-two", two, filterMenuOf(two) to UNDER_FILTERS)
	}

	@Test
	fun aFoldersMenuWithItsBoxesItsRingsAndAFilesMenuDraw() {
		// A picker's menu of its folder, opened with Shift held, has boxes in it; its "Sort by" is rings; an
		// explorer's menu of a file is only things to do.
		val picking = picker(hidden = Hidden())
		val folder = contextMenuOf(picking, Target.Folder, held = setOf(Held.Shift))
		val sort =
			folder.entries
				.filterIsInstance<MenuEntry.Submenu<BrowserEvent>>()
				.first { it.label == "Sort by" }
				.menu

		val exploring = picker(Choosing.Exploring())
		val file = exploring.rows.list.filter { it.name == "notes.md" }

		drawn(
			"menu-folder",
			picking,
			folder to IntOffset(700, 300),
			sort to IntOffset(1250, 290),
		)
		drawn(
			"menu-file",
			exploring,
			contextMenuOf(exploring, Target.Rows(file)) to IntOffset(900, 300),
		)
	}

	private companion object {
		/** Under the filters button, in the pixels of a window drawn at twice its size. */
		val UNDER_FILTERS = IntOffset(1740, 84)
	}
}
