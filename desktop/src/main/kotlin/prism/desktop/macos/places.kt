package prism.desktop.macos

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import prism.desktop.Place
import prism.desktop.Places
import prism.macos.MacMounts
import prism.macos.MountFlag
import prism.macos.contains
import prism.platform.Mount

/**
 * The places a Mac's Finder offers: the home folder and the well-known folders in it (Desktop, Documents, Downloads,
 * Pictures, Music, Movies), and the volumes: the computer's own, under its name ("Macintosh HD"), then each mounted
 * under /Volumes that Finder shows, by name.
 */
class FinderPlaces(
	private val mounted: () -> List<Mount> = MacMounts::current,
	/** What Finder calls the volume at a folder. */
	private val nameOf: (Path) -> String? = ::displayName,
) : Places {
	override fun known(home: Path): List<Place> =
		listOf(Place("Home", Place.Kind.Home, home)) +
			listOf(
				Place.Kind.Desktop to "Desktop",
				Place.Kind.Documents to "Documents",
				Place.Kind.Downloads to "Downloads",
				Place.Kind.Pictures to "Pictures",
				Place.Kind.Music to "Music",
				Place.Kind.Videos to "Movies",
			).mapNotNull { (kind, name) ->
				home
					.resolve(name)
					.takeIf {
						Files.isDirectory(it)
					}?.let { Place(nameOf(it) ?: name, kind, it) }
			}

	override fun drives(home: Path): List<Place> {
		val root = Path.of("/")

		return listOf(Place(nameOf(root) ?: COMPUTER, Place.Kind.Drive, root)) +
			mounted()
				.filter { it.at.parent == VOLUMES && MountFlag.NoBrowse !in it }
				.sortedBy { it.at.name.lowercase() }
				.map { Place(nameOf(it.at) ?: it.at.name, Place.Kind.Drive, it.at) }
	}

	companion object {
		/** What the computer's own volume is called where Finder does not say. */
		const val COMPUTER = "Macintosh HD"

		private val VOLUMES = Path.of("/Volumes")
	}
}

/** What Finder calls the file or folder at [path], in the user's language ("Macintosh HD", "Schreibtisch"). */
fun displayName(path: Path): String? = prism.macos.displayNameOf(path)
