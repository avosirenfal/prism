package prism.desktop.freedesktop

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readLines
import prism.desktop.Place
import prism.desktop.Places
import prism.linux.Mounts

/**
 * The places freedesktop.org's desktops offer: the home folder and the XDG user directories in it, as
 * `~/.config/user-dirs.dirs` names them (in the user's language), and the drives mounted where desktops mount them.
 */
class XdgPlaces(
	/** Where a file system is mounted now: what the kernel says, unless told otherwise. */
	private val mounted: () -> List<Path> = { Mounts.current().map { it.at } },
) : Places {
	override fun known(home: Path): List<Place> {
		val named = userDirectories(home)

		return listOf(Place("Home", Place.Kind.Home, home)) +
			listOf(
				Place.Kind.Desktop to "XDG_DESKTOP_DIR",
				Place.Kind.Documents to "XDG_DOCUMENTS_DIR",
				Place.Kind.Downloads to "XDG_DOWNLOAD_DIR",
				Place.Kind.Pictures to "XDG_PICTURES_DIR",
				Place.Kind.Music to "XDG_MUSIC_DIR",
				Place.Kind.Videos to "XDG_VIDEOS_DIR",
			).mapNotNull { (kind, key) ->
				named[key]?.takeIf { it.isDirectory() && it != home }?.let {
					Place(
						it.name,
						kind,
						it,
					)
				}
			}
	}

	/**
	 * The root, and what is mounted in the folders desktops mount drives in (`/media`, `/mnt`, `/run/media/<user>`),
	 * in those folders' order and by name within each. A folder there that nothing is mounted at is a folder, not a
	 * file system, and is not among them.
	 */
	override fun drives(home: Path): List<Place> {
		val mounts = mounted().toSet()

		return listOf(Place(ROOT, Place.Kind.Drive, Path.of("/"))) +
			listOf(Path.of("/media"), Path.of("/mnt"), Path.of("/run/media/${home.name}"))
				.flatMap { under -> mounts.filter { it.parent == under }.sortedBy { it.name } }
				.map { Place(it.name, Place.Kind.Drive, it) }
	}

	companion object {
		/** What the root of the file system is called among the file systems. */
		const val ROOT = "Root"

		/**
		 * The XDG user directories as `~/.config/user-dirs.dirs` names them (`XDG_DESKTOP_DIR="$HOME/Desktop"`), with
		 * the usual English names where it does not.
		 */
		fun userDirectories(home: Path): Map<String, Path> {
			val defaults =
				mapOf(
					"XDG_DESKTOP_DIR" to "Desktop",
					"XDG_DOCUMENTS_DIR" to "Documents",
					"XDG_DOWNLOAD_DIR" to "Downloads",
					"XDG_PICTURES_DIR" to "Pictures",
					"XDG_MUSIC_DIR" to "Music",
					"XDG_VIDEOS_DIR" to "Videos",
				).mapValues { (_, name) -> home.resolve(name) }

			val file = home.resolve(".config/user-dirs.dirs")

			val configured =
				if (file.exists())
					file
						.readLines()
						.mapNotNull { Regex("""^(XDG_\w+_DIR)="(.*)"$""").find(it.trim())?.destructured }
						.associate { (key, value) ->
							key to
								Path.of(value.replace("\$HOME", home.toString()))
						}
				else
					emptyMap()

			return defaults + configured
		}
	}
}
