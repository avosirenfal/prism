package prism.desktop.macos

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import prism.desktop.Icons
import prism.macos.Applications
import prism.platform.OwnFolders

/**
 * Icons as a Mac draws them: an application's (and any file's) as NSWorkspace has it, drawn at the size asked for and
 * kept as a PNG in [cache], so each is drawn once. An icon is named by the path of what it is the icon of, as an
 * application names its own here (`/Applications/Safari.app`).
 */
class WorkspaceIcons(
	private val cache: Path =
		OwnFolders
			.of()
			.cache.path
			.resolve("icons"),
) : Icons {
	override fun find(name: String, size: Int, scale: Int): Path? {
		val of = Path.of(name).takeIf { it.isAbsolute && Files.exists(it) } ?: return null
		val pixels = size * scale

		val kept =
			cache.resolve(
				"${Integer.toHexString(name.hashCode())}-${of.fileName}-$pixels.png",
			)

		if (Files.isRegularFile(kept))
			return kept

		val png = Applications.iconPng(of, pixels) ?: return null

		return try {
			Files.createDirectories(cache)

			// Written beside, then moved into place whole: a reader never sees half a picture.
			val written = Files.createTempFile(cache, "icon", ".png")

			Files.write(written, png)
			Files.move(
				written,
				kept,
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING,
			)
		} catch (failed: IOException) {
			null
		}
	}
}
