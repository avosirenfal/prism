package prism.desktop

import java.nio.file.Path
import java.time.Instant
import prism.desktop.freedesktop.ThumbnailCache
import prism.desktop.macos.QuickLookThumbnails
import prism.platform.Os

/**
 * The pictures of files a desktop has beyond what prism draws itself (images Skia reads): of a video, a document, a
 * picture of a kind only the system reads. On Linux they are the thumbnails the desktop's programs share, read and
 * never written; on a Mac, Quick Look makes them, as it does for Finder.
 */
fun interface SharedThumbnails {
	/**
	 * The file of a picture of [file] to draw [wanted] pixels across, [file] being as it was last [modified] and of
	 * [bytes]; null where there is none. For a file prism draws itself ([smaller] false), only a picture at least as
	 * large as [wanted], so that it is as sharp as prism's own; for anything else, the best there is.
	 */
	fun find(
		file: Path,
		modified: Instant,
		bytes: Long?,
		wanted: Int,
		smaller: Boolean,
	): Path?

	companion object {
		/** The platform's own: freedesktop.org's shared cache on Linux, Quick Look on a Mac, none on Windows yet. */
		fun system(os: Os = Os.current): SharedThumbnails? =
			when (os) {
				Os.Linux -> ThumbnailCache.of()
				Os.MacOS -> QuickLookThumbnails()
				Os.Windows -> null
			}
	}
}
