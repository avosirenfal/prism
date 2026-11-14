package prism.desktop

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.Freedesktop
import prism.desktop.freedesktop.IconThemes
import prism.desktop.freedesktop.Xdg

/**
 * Icons found as the Icon Theme specification says, over themes made in a folder for each test: which theme is the
 * user's, wherever it is said; an icon in the theme, in one it inherits from, in `hicolor`, in no theme at all; the
 * directory whose pictures are for the size and scale asked for, or the nearest; and that what was found is kept
 * until it is asked to be read again.
 */
class IconsTest {
	@TempDir
	lateinit var root: Path

	private val home get() = root.resolve("home")
	private val user get() = home.resolve(".local/share")
	private val system get() = root.resolve("usr/share")
	private val config get() = home.resolve(".config")
	private val pixmaps get() = root.resolve("usr/share/pixmaps-of-old")

	private fun xdg(vararg more: Pair<String, String>) =
		Xdg(
			mapOf(
				"XDG_DATA_HOME" to user.toString(),
				"XDG_DATA_DIRS" to system.toString(),
				"XDG_CONFIG_HOME" to config.toString(),
				"PATH" to "",
			) + more,
			home,
		)

	/** The icons, with GNOME's settings saying [gnome], and saying nothing where that is null. */
	private fun icons(xdg: Xdg = xdg(), gnome: String? = null) =
		IconThemes(xdg, ask = { gnome?.let { "'$it'\n" } }, pixmaps = listOf(pixmaps))

	private fun write(path: Path, text: String = "picture"): Path =
		path.also { it.parent.createDirectories() }.apply { writeText(text) }

	/**
	 * A theme [name] under [base], inheriting from [inherits], with [directories]: each a path and what its group
	 * says of it.
	 */
	private fun theme(
		base: Path,
		name: String,
		inherits: String? = null,
		vararg directories: Pair<String, String>,
	) {
		write(
			base.resolve("icons/$name/index.theme"),
			buildString {
				appendLine("[Icon Theme]")
				appendLine("Name=$name")
				inherits?.let { appendLine("Inherits=$it") }
				appendLine("Directories=${directories.joinToString(",") { it.first }}")
				for ((path, says) in directories) {
					appendLine()
					appendLine("[$path]")
					appendLine(says.replace(';', '\n'))
				}
			},
		)
	}

	private fun icon(base: Path, theme: String, file: String) =
		write(base.resolve("icons/$theme/$file"))

	/** `hicolor` as applications find it: a few fixed sizes, one for a display of twice the density, and one that scales. */
	private fun hicolor(base: Path = system) =
		theme(
			base,
			"hicolor",
			null,
			"16x16/apps" to "Size=16;Type=Fixed",
			"32x32/apps" to "Size=32;Type=Fixed",
			"48x48/apps" to "Size=48;Type=Threshold",
			"32x32@2/apps" to "Size=32;Scale=2;Type=Fixed",
			"256x256/apps" to "Size=256;Type=Fixed",
			"scalable/apps" to "Size=64;MinSize=64;MaxSize=512;Type=Scalable",
		)

	@Test
	fun theUsersThemeIsTheFirstSaidThatIsInstalled() {
		hicolor()
		for (name in listOf("Named", "Gtk4", "Gtk3", "Kde", "Gnome", "breeze", "Papirus"))
			theme(system, name)

		fun gtk(version: String, theme: String) =
			write(
				config.resolve("gtk-$version/settings.ini"),
				"[Settings]\ngtk-icon-theme-name=$theme\n",
			)

		gtk("4.0", "Gtk4")
		gtk("3.0", "Gtk3")

		val kde = write(config.resolve("kdeglobals"), "[General]\nx=y\n\n[Icons]\nTheme=Kde\n")

		assertEquals("Named", icons(xdg("PRISM_ICON_THEME" to "Named"), "Gnome").theme)
		assertEquals("Gtk4", icons(gnome = "Gnome").theme)
		// One named that is not installed is passed over for the next said.
		assertEquals("Gtk4", icons(xdg("PRISM_ICON_THEME" to "Missing"), "Gnome").theme)

		config.resolve("gtk-4.0/settings.ini").deleteExisting()
		assertEquals("Gtk3", icons(gnome = "Gnome").theme)
		gtk("3.0", "Uninstalled")
		assertEquals("Kde", icons(gnome = "Gnome").theme)
		kde.deleteExisting()
		assertEquals("Gnome", icons(gnome = "Gnome").theme)
		// Nothing said: the first there is of those desktops come with.
		assertEquals("breeze", icons().theme)
		assertEquals("breeze", icons(gnome = "../breeze").theme)
	}

	@Test
	fun withNoThemeSaidOrInstalledItIsHicolorAndNothingIsFoundWhereThereIsNone() {
		assertEquals("hicolor", icons().theme)
		assertNull(icons().find("anything", 32, 1))

		hicolor()
		assertEquals("hicolor", icons().theme)
	}

	@Test
	fun anIconIsLookedForInTheThemeThenWhatItInheritsThenHicolorThenNoThemeAtAll() {
		hicolor()
		theme(system, "Grandparent", null, "apps" to "Size=32;Type=Fixed")
		theme(system, "Parent", "Grandparent", "apps" to "Size=32;Type=Fixed")
		theme(system, "Other", "Mine", "apps" to "Size=32;Type=Fixed")
		// Each inherits from the other: neither is looked in twice, and the looking ends.
		theme(system, "Mine", "Parent,Other,Missing", "apps" to "Size=32;Type=Fixed")

		val mine = icon(system, "Mine", "apps/everywhere.png")

		icon(system, "Parent", "apps/everywhere.png")
		icon(system, "hicolor", "32x32/apps/everywhere.png")

		val parents = icon(system, "Parent", "apps/in-parent.png")
		val grandparents = icon(system, "Grandparent", "apps/in-grandparent.png")
		val others = icon(system, "Other", "apps/in-other.png")
		val hicolors = icon(system, "hicolor", "32x32/apps/in-hicolor.png")

		// A theme's nearer parents, and what they inherit from, before its further ones.
		icon(system, "Other", "apps/in-grandparent.png")

		val loose = write(user.resolve("icons/loose.svg"))
		val pixmap = write(system.resolve("pixmaps/pixmap.png"))
		val old = write(pixmaps.resolve("old.png"))

		val icons = icons(xdg("PRISM_ICON_THEME" to "Mine"))

		fun found(name: String) = icons.find(name, 32, 1)

		assertEquals(mine, found("everywhere"))
		assertEquals(parents, found("in-parent"))
		assertEquals(grandparents, found("in-grandparent"))
		assertEquals(others, found("in-other"))
		assertEquals(hicolors, found("in-hicolor"))
		assertEquals(loose, found("loose"))
		assertEquals(pixmap, found("pixmap"))
		assertEquals(old, found("old"))
		assertNull(found("nowhere"))
		assertNull(found(""))
	}

	@Test
	fun theDirectoryForTheSizeAndScaleAskedForIsTheOneOrTheNearestThereIs() {
		hicolor()

		fun at(directory: String, name: String = "app.png") =
			icon(system, "hicolor", "$directory/$name")

		val small = at("16x16/apps")
		val middling = at("32x32/apps")
		val doubled = at("32x32@2/apps")
		val large = at("256x256/apps")
		val icons = icons()

		assertEquals(small, icons.find("app", 16, 1))
		assertEquals(middling, icons.find("app", 32, 1))
		// For a display of twice the density, the directory drawn for one.
		assertEquals(doubled, icons.find("app", 32, 2))
		// No directory for the size: the nearest in pixels, which for 30 at twice the density is 60, so 32 at twice.
		assertEquals(small, icons.find("app", 20, 1))
		assertEquals(middling, icons.find("app", 28, 1))
		assertEquals(doubled, icons.find("app", 30, 2))
		// Of two as near, the first the theme lists.
		assertEquals(middling, icons.find("app", 24, 2))
		assertEquals(large, icons.find("app", 200, 1))
		assertEquals(large, icons.find("app", 128, 2))

		// Within a few pixels of a directory that allows it, that one.
		val near = at("48x48/apps", "near.png")

		at("32x32/apps", "near.png")
		assertEquals(near, icons.find("near", 46, 1))
		assertEquals(near, icons.find("near", 50, 1))

		// One that scales is for every size it says, and its SVG is taken before a PNG beside it.
		val scales = at("scalable/apps", "scales.svg")

		at("scalable/apps", "scales.png")
		at("16x16/apps", "scales.png")
		assertEquals(scales, icons.find("scales", 64, 1))
		assertEquals(scales, icons.find("scales", 300, 1))
		assertEquals(scales, icons.find("scales", 96, 2))
		// Where a fixed directory is of the size, its PNG is the one.
		assertEquals(
			system.resolve("icons/hicolor/16x16/apps/scales.png"),
			icons.find("scales", 16, 1),
		)

		// A fixed directory holds what it is given: an SVG alone is found there too.
		val only = at("32x32/apps", "only.svg")

		assertEquals(only, icons.find("only", 32, 1))
	}

	@Test
	fun aThemesPicturesAreInItsFolderUnderEveryFolderOfThemesTheUsersFirst() {
		hicolor()

		icon(system, "hicolor", "32x32/apps/both.png")
		icon(system, "hicolor", "32x32/apps/all.png")

		val users = icon(user, "hicolor", "32x32/apps/both.png")
		val added = icon(user, "hicolor", "32x32/apps/added.png")
		val dotted = write(home.resolve(".icons/hicolor/32x32/apps/all.png"))

		icon(user, "hicolor", "32x32/apps/all.png")

		val icons = icons()

		// The user's folders have no index of their own: the system's says what the theme's directories are.
		assertEquals(users, icons.find("both", 32, 1))
		assertEquals(added, icons.find("added", 32, 1))
		// `~/.icons` before the data folders.
		assertEquals(dotted, icons.find("all", 32, 1))
	}

	@Test
	fun aPathIsThePictureItselfAndANameWithItsKindIsTheNameWithout() {
		hicolor()

		val png = icon(system, "hicolor", "32x32/apps/app.png")
		val anywhere = write(root.resolve("opt/app/logo.xpm"))
		val icons = icons()

		assertEquals(anywhere, icons.find(anywhere.toString(), 32, 1))
		assertNull(icons.find(root.resolve("opt/app/missing.png").toString(), 32, 1))
		assertNull(icons.find(root.resolve("opt/app").toString(), 32, 1))
		assertEquals(png, icons.find("app.png", 32, 1))
		assertEquals(png, icons.find("app.svg", 32, 1))
		assertEquals(png, icons.find("app.xpm", 32, 1))

		// A name with dots in it that is not a kind of picture is the name: `org.gnome.Nautilus`.
		val dotted = icon(system, "hicolor", "32x32/apps/org.example.App.png")

		assertEquals(dotted, icons.find("org.example.App", 32, 1))
	}

	@Test
	fun aNameNotFoundAnywhereIsLookedForWithLessOfIt() {
		hicolor()
		theme(system, "Mine", null, "apps" to "Size=32;Type=Fixed")

		val general = icon(system, "hicolor", "32x32/apps/folder.png")
		val particular = icon(system, "hicolor", "32x32/apps/folder-music.png")
		val icons = icons(xdg("PRISM_ICON_THEME" to "Mine"))

		assertEquals(particular, icons.find("folder-music-symbolic", 32, 1))
		assertEquals(general, icons.find("folder-videos-symbolic", 32, 1))
		// The whole name in a later theme, before less of it in an earlier one.
		icon(system, "Mine", "apps/folder.png")
		icons.reread()
		assertEquals(particular, icons.find("folder-music", 32, 1))
		assertNull(icons.find("nothing-like-it", 32, 1))
	}

	@Test
	fun whatWasFoundIsKeptUntilItIsReadAgain() {
		hicolor()

		val icons: Icons = Freedesktop(xdg()).icons
		val first = icon(system, "hicolor", "32x32/apps/app.png")

		assertEquals(first, icons.find("app", 32, 1))
		assertNull(icons.find("later", 32, 1))

		// A theme chosen, and an icon installed, since: neither is seen until asked for again.
		theme(system, "Chosen", null, "apps" to "Size=32;Type=Fixed")

		val chosen = icon(system, "Chosen", "apps/app.png")
		val later = icon(system, "hicolor", "32x32/apps/later.png")

		write(
			config.resolve("gtk-3.0/settings.ini"),
			"[Settings]\ngtk-icon-theme-name = Chosen\n",
		)
		assertEquals(first, icons.find("app", 32, 1))
		assertNull(icons.find("later", 32, 1))

		icons.reread()
		assertEquals(chosen, icons.find("app", 32, 1))
		assertEquals(later, icons.find("later", 32, 1))
	}
}
