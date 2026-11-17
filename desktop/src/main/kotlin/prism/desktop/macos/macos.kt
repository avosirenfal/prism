package prism.desktop.macos

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import prism.desktop.Desktop
import prism.desktop.FileFacts
import prism.desktop.JvmFacts
import prism.desktop.Opening
import prism.desktop.TerminalChoice

/**
 * A Mac's desktop: uniform types for what a file is, Launch Services for which application opens it and for starting
 * it, NSWorkspace for icons, Finder's places, the volumes, and the system's trash. The JVM's views of a file say all
 * its facts here, when it was made among them (APFS keeps it, and the JVM asks).
 */
class MacDesktop : Desktop {
	override val types = UniformFileTypes()
	override val applications = LaunchServicesApplications(types)
	override val launcher = OpenLauncher()
	override val icons = WorkspaceIcons()
	override val places = FinderPlaces()
	override val storage = VolumeStorage()
	override val trash = SystemTrash()
	override val facts: FileFacts = JvmFacts(types, ::bornAt)

	/**
	 * [paths] opened as Finder opens them. A package (an application, a document kept as a folder) is Launch
	 * Services' to open, which starts an application and gives a document to whatever opens it; anything else is
	 * opened with its type's application, as on every platform.
	 */
	override fun open(paths: List<Path>, terminal: TerminalChoice): Opening {
		val (packages, files) =
			paths.partition {
				Files.isDirectory(it) &&
					Packages.holds(it.fileName.toString())
			}

		val opened =
			packages.map {
				launcher.run(listOf(LaunchServicesApplications.OPEN, it.toString()), it.parent)
			}

		val rest =
			if (files.isEmpty())
				Opening(emptyList(), emptyList())
			else
				super.open(files, terminal)

		return Opening(opened + rest.launched, rest.unopened)
	}

	private companion object {
		fun bornAt(path: Path) =
			Files
				.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
				.creationTime()
				.toInstant()
	}
}
