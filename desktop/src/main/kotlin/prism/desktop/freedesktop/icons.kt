package prism.desktop.freedesktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.math.abs
import prism.desktop.Icons
import prism.desktop.asChildOfPrism

/*
 * Icons as freedesktop.org's Icon Theme specification says them. An application names its icon (`firefox`), and the
 * picture is a file of that name in the user's icon theme, drawn at one size or another: a theme is a folder of that
 * name under any of the icon folders (`~/.icons`, each data folder's `icons`), whose `index.theme` lists its
 * directories and says of each what size its pictures are drawn at, for what scale, and how far from that size they
 * may be used. A theme that lacks an icon is followed by the themes it says it inherits from, then by `hicolor`,
 * where applications put their own; then by a file of that name lying in an icon folder itself, or in `pixmaps`.
 *
 * Which theme is the user's is said in several places, none of them freedesktop.org's: GTK's settings file, KDE's,
 * GNOME's settings (asked of `gsettings`). The first that names a theme that is installed is believed.
 *
 * A launcher asks for a few hundred icons at once, and again each time it opens; so a theme is read once and kept,
 * and so is each answer, until [IconThemes.reread].
 */

/**
 * The icons of [xdg]'s desktop. [ask] is how a setting kept in no file is asked for: a command's words, and what it
 * printed, or null where it could not be run (a process, unless a test says otherwise). [pixmaps] are the folders
 * of pictures in no theme, beside each data folder's own.
 */
class IconThemes(
	private val xdg: Xdg,
	private val ask: (List<String>) -> String? = { asked(it, xdg) },
	private val pixmaps: List<Path> = listOf(Path.of("/usr/share/pixmaps")),
) : Icons {
	/** What has been read and found, until it is read again: the themes looked in, in order, and each answer. */
	private class Kept(
		themes: () -> List<Theme>,
	) {
		val themes: List<Theme> by lazy(themes)
		val found = ConcurrentHashMap<Asked, Optional<Path>>()
	}

	private data class Asked(
		val name: String,
		val size: Int,
		val scale: Int,
	)

	@Volatile
	private var kept = Kept(::chain)

	/** The folders themes are in, the user's first. */
	private val bases: List<Path>
		get() = listOf(xdg.home.resolve(".icons")) + xdg.data.map { it.resolve("icons") }

	/** The user's theme, by name: the first said that is installed ([said]), or `hicolor`. */
	val theme: String get() = kept.themes.first().name

	override fun find(name: String, size: Int, scale: Int): Path? {
		val kept = kept

		return kept.found
			.computeIfAbsent(Asked(name, size, scale)) {
				Optional.ofNullable(looked(kept.themes, name, size, scale))
			}.orElse(null)
	}

	/** Everything read forgotten: a theme installed, or chosen, since is seen the next time an icon is asked for. */
	override fun reread() {
		kept = Kept(::chain)
	}

	private fun looked(themes: List<Theme>, name: String, size: Int, scale: Int): Path? {
		if (name.isEmpty())
			return null
		// A path is the picture itself, where it is there.
		if (name.startsWith("/"))
			return Path.of(name).takeIf { it.isRegularFile() }

		// A name written with what kind of file it is (`app.png`) is the name without: the theme says which kind.
		val plain =
			EXTENSIONS.map { ".$it" }.firstOrNull(name::endsWith)?.let(name::removeSuffix) ?: name

		// The whole name wherever it could be, and only then the name with less of it: `a-b-symbolic`, then `a-b`.
		return generateSequence(plain) {
			it.substringBeforeLast('-', "").takeIf(String::isNotEmpty)
		}.firstNotNullOfOrNull { each ->
			themes.firstNotNullOfOrNull { it.find(each, size, scale) } ?: unthemed(each)
		}
	}

	/** A picture of [name] in no theme: lying in a folder of themes, or in a folder of pixmaps. */
	private fun unthemed(name: String): Path? {
		val folders = bases + xdg.data.map { it.resolve("pixmaps") } + pixmaps

		return folders.firstNotNullOfOrNull { folder ->
			KINDS.firstNotNullOfOrNull { kind ->
				folder.resolve("$name.$kind").takeIf { it.isRegularFile() }
			}
		}
	}

	/** The themes an icon is looked for in, in order: the user's, those it inherits from, each once, then `hicolor`. */
	private fun chain(): List<Theme> {
		fun following(name: String, seen: Set<String>): List<Theme> {
			val theme = themeOf(name)?.takeIf { name !in seen } ?: return emptyList()

			return theme.inherits.fold(listOf(theme)) { themes, parent ->
				themes + following(parent, seen + themes.map { it.name })
			}
		}

		val users = said().firstOrNull { themeOf(it) != null }
		val themes = users?.let { following(it, emptySet()) }.orEmpty()

		return (
			themes +
				listOfNotNull(themeOf(HICOLOR).takeIf { themes.none { it.name == HICOLOR } })
		)
			// With no theme at all, there is still one to ask: of no directories, which finds nothing.
			.ifEmpty { listOf(Theme(HICOLOR, emptyList(), emptyList(), emptyList())) }
	}

	/**
	 * The themes said to be the user's, the most believed first, each asked for only if those before it were not
	 * installed: `PRISM_ICON_THEME`; GTK 4's settings, then GTK 3's; KDE's; GNOME's, asked of `gsettings`; and then
	 * the themes most desktops come with.
	 */
	private fun said(): Sequence<String> =
		sequence {
			yield(xdg["PRISM_ICON_THEME"])
			for (gtk in listOf("gtk-4.0", "gtk-3.0"))
				yield(
					KeyFile
						.read(xdg.configHome.resolve("$gtk/settings.ini"))
						?.get("Settings")
						?.get("gtk-icon-theme-name"),
				)
			yield(KeyFile.read(xdg.configHome.resolve("kdeglobals"))?.get("Icons")?.get("Theme"))
			yield(
				ask(listOf("gsettings", "get", "org.gnome.desktop.interface", "icon-theme"))
					?.trim()
					?.removeSurrounding("'"),
			)
			yieldAll(USUAL)
		}.filterNotNull().filter { it.isNotEmpty() }

	/**
	 * The theme [name]: what its `index.theme` says, read from the first folder of themes that has one, and its
	 * folder under every folder of themes, since an application adds its icons to a theme (`hicolor`) in a folder of
	 * its own. Null where no folder of themes has an `index.theme` for it.
	 */
	private fun themeOf(name: String): Theme? {
		// A theme's name is a folder's: one that is a path would be somewhere else's.
		if ('/' in name || name == "." || name == "..")
			return null

		val folders = bases.map { it.resolve(name) }.filter { it.isDirectory() }
		val index =
			folders.firstNotNullOfOrNull { KeyFile.read(it.resolve("index.theme")) } ?: return null
		val says = index["Icon Theme"]

		val directories =
			(itemsOf(says["Directories"], ',') + itemsOf(says["ScaledDirectories"], ','))
				.distinct()
				.mapNotNull { path -> Directory.of(path, index[path]) }

		return Theme(name, folders, directories, itemsOf(says["Inherits"], ','))
	}

	/** A theme: where its pictures are, the directories it says it has, and the themes it falls back to. */
	private class Theme(
		val name: String,
		val folders: List<Path>,
		val directories: List<Directory>,
		val inherits: List<String>,
	) {
		/** The directories in the order they are looked in for each size and scale asked for, worked out once. */
		private val orders = ConcurrentHashMap<Pair<Int, Int>, List<Directory>>()

		/**
		 * The picture of [name] for [size] at [scale]: in a directory that says it is for that size and scale, the
		 * first there is; failing that, in the one whose size is nearest (the specification's `LookupIcon`).
		 */
		fun find(name: String, size: Int, scale: Int): Path? =
			orders
				.computeIfAbsent(size to scale) {
					directories.sortedWith(
						compareBy({ !it.fits(size, scale) }, { it.distance(size, scale) }),
					)
				}.firstNotNullOfOrNull { directory ->
					folders.firstNotNullOfOrNull { folder ->
						directory.kinds.firstNotNullOfOrNull { kind ->
							folder
								.resolve(directory.path)
								.resolve("$name.$kind")
								.takeIf { it.isRegularFile() }
						}
					}
				}
	}

	/** How a directory's pictures may be used at sizes other than the one they were drawn at. */
	private enum class Sizing {
		/** At their size alone. */
		Fixed,

		/** At any size from the least to the most the directory says. */
		Scalable,

		/** Within a few pixels of their size. */
		Threshold,
	}

	/** One of a theme's directories, as its `index.theme` says: pictures of [size] pixels, for a display of [scale]. */
	private class Directory(
		val path: String,
		val size: Int,
		val scale: Int,
		val sizing: Sizing,
		val least: Int,
		val most: Int,
	) {
		/** The kinds of picture looked for here, the likeliest first: one that scales, in a directory of those. */
		val kinds: List<String> get() =
			if (sizing == Sizing.Scalable)
				SCALABLE_FIRST
			else
				KINDS

		/** Whether its pictures are for [wanted] pixels at [scale] (the specification's `DirectoryMatchesSize`). */
		fun fits(wanted: Int, scale: Int): Boolean =
			this.scale == scale && wanted in least..most

		/** How far its pictures are from [wanted] pixels at [scale], in real pixels (`DirectorySizeDistance`). */
		fun distance(wanted: Int, scale: Int): Int {
			val pixels = wanted * scale

			return when {
				sizing == Sizing.Fixed -> abs(size * this.scale - pixels)
				pixels < least * this.scale -> least * this.scale - pixels
				pixels > most * this.scale -> pixels - most * this.scale
				else -> 0
			}
		}

		companion object {
			/** The directory at [path], as its group's [keys] say; null where they do not say its size. */
			fun of(path: String, keys: Map<String, String>): Directory? {
				fun number(key: String) = keys[key]?.trim()?.toIntOrNull()

				val size = number("Size") ?: return null

				val sizing =
					Sizing.entries.firstOrNull {
						it.name.equals(
							keys["Type"]?.trim(),
							ignoreCase = true,
						)
					}
						?: Sizing.Threshold

				val threshold = number("Threshold") ?: 2

				return Directory(
					path,
					size,
					number("Scale") ?: 1,
					sizing,
					least =
						when (sizing) {
							Sizing.Fixed -> size
							Sizing.Scalable -> number("MinSize") ?: size
							Sizing.Threshold -> size - threshold
						},
					most =
						when (sizing) {
							Sizing.Fixed -> size
							Sizing.Scalable -> number("MaxSize") ?: size
							Sizing.Threshold -> size + threshold
						},
				)
			}
		}
	}

	private companion object {
		const val HICOLOR = "hicolor"

		/** The themes most desktops come with, looked for where nothing says which is the user's. */
		val USUAL = listOf("Adwaita", "breeze", "Papirus")

		/** The kinds of picture prism draws, by their files' extensions: XPM, which the specification also has, it does not. */
		val KINDS = listOf("png", "svg")
		val SCALABLE_FIRST = KINDS.reversed()

		/** What a name may be written with after it, and is looked for without. */
		val EXTENSIONS = KINDS + "xpm"

		/**
		 * What [command] printed, run as any program prism starts is, and given two seconds; null where it is not
		 * installed, did not end in time, or failed.
		 */
		fun asked(command: List<String>, xdg: Xdg): String? {
			if (xdg.program(command.first()) == null)
				return null

			return try {
				val process =
					ProcessBuilder(command)
						.asChildOfPrism()
						.redirectErrorStream(true)
						.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
						.start()

				// What it prints is a line, which waits in the pipe until it is read: so it is waited for first.
				if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) {
					process.inputStream.bufferedReader().use { it.readText() }
				} else {
					process.destroyForcibly()
					null
				}
			} catch (failed: IOException) {
				null
			}
		}
	}
}
