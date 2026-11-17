package prism.desktop.macos

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import prism.desktop.SharedThumbnails
import prism.macos.QuickLook
import prism.macos.UniformTypes
import prism.platform.OwnFolders

/**
 * Quick Look's pictures of files, kept as PNGs in [cache] so each is made once: named by the file, when it was last
 * modified and how large the picture is, so a file changed is drawn anew. What prism draws itself is left to it: Quick
 * Look is asked only of what prism cannot draw. A bundle is its own icon to Finder, and to prism (`Packages`).
 */
class QuickLookThumbnails(
	private val cache: Path =
		OwnFolders
			.of()
			.cache.path
			.resolve("thumbnails"),
) : SharedThumbnails {
	override fun find(
		file: Path,
		modified: Instant,
		bytes: Long?,
		wanted: Int,
		smaller: Boolean,
	): Path? {
		if (!smaller)
			return null

		val kept =
			cache.resolve(
				"${Integer.toHexString(
					file.toString().hashCode(),
				)}-${modified.toEpochMilli()}-$wanted.png",
			)

		if (Files.isRegularFile(kept))
			return kept

		val png = QuickLook.png(file, wanted) ?: return null

		return try {
			Files.createDirectories(cache)

			// Written beside, then moved into place whole: a reader never sees half a picture.
			val written = Files.createTempFile(cache, "thumbnail", ".png")

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
