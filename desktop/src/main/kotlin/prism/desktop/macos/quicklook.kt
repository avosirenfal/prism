package prism.desktop.macos

import java.nio.file.Path
import prism.macos.QuickLook
import prism.macos.UniformTypes
import prism.thumbnails.SystemPictures

/**
 * Quick Look's pictures of files, as it makes them for Finder: asked for one where nothing of prism's own makes a
 * file's picture, and kept in the cache as any other thumbnail is. A bundle is its own icon to Finder, and to prism
 * (`Packages`).
 */
object QuickLookPictures : SystemPictures {
	override fun png(file: Path, side: Int): ByteArray? =
		try {
			QuickLook.png(file, side)
		} catch (unavailable: LinkageError) {
			null
		}
}

/**
 * The folders a Mac shows as one thing, and opens as one: an application, a document kept as a folder (a Photos
 * library, an Xcode project, an RTFD), anything whose type is a package's. Known by the extension of its name, as
 * Finder knows it, each extension asked of the system once.
 */
object Packages {
	private val known = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

	/** Whether a folder named [name] is a package; never, off a Mac. */
	fun holds(name: String): Boolean {
		val extension = name.substringAfterLast('.', "").lowercase()

		if (prism.platform.Os.current != prism.platform.Os.MacOS || extension.isEmpty() ||
			extension == name.lowercase()
		)
			return false

		return known.getOrPut(extension) {
			try {
				UniformTypes.ofExtension(extension)?.conformsTo(PACKAGE) == true
			} catch (unavailable: LinkageError) {
				false
			}
		}
	}

	private const val PACKAGE = "com.apple.package"
}
