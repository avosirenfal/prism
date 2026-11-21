package prism.watcher

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import prism.probe.FsWatch

/*
 * Whether the watcher a daemon reads is still the one pinned. The loader that pinned it can be run again (a system
 * that updates prism restarts it): it takes away what it pinned, which detaches the hooks, and pins a new watcher with
 * a ring of its own. The daemon's ring is kept for as long as it has it open, and is read as ever, but nothing writes
 * to it again. Reading it never fails, so the daemon would wait for changes for good, and its count of what was lost,
 * which is in the old watcher's maps too, never grows. So it looks at the pin of the ring it opened: gone, or a file
 * other than the one it opened, and what it reads is no longer the watcher's.
 */

/**
 * The pin of a ring at [path], as it was when the ring was opened: which file it was ([key]), by its inode and when
 * it was made. A BPF file system gives each file it makes an inode none had before (`get_next_ino`), so a ring pinned
 * anew is never taken for the one before; when it was made tells them apart elsewhere too, where an inode let go is
 * given again.
 */
class Pin private constructor(
	private val path: Path,
	private val key: Any,
) {
	/** Why what was opened at [path] is no longer what is pinned there; null while it is. */
	fun gone(): String? =
		when (val now = keyOf(path)) {
			null -> "the watcher pinned at ${path.parent} was unloaded"
			key -> null
			else -> "the watcher pinned at ${path.parent} was replaced by a new one"
		}

	companion object {
		/** The pin at [path] as it is now; none where nothing is there, or it cannot be looked at. */
		fun at(path: Path): Pin? = keyOf(path)?.let { Pin(path, it) }

		/** The ring of the watcher pinned in [folder]. */
		fun ringIn(folder: Path): Path = folder.resolve(FsWatch.maps.changes.cName)

		/** Which file is at [path] (its device and inode); null where none is, or it cannot be looked at. */
		private fun keyOf(path: Path): Any? =
			try {
				Files.readAttributes(path, BasicFileAttributes::class.java).let {
					listOf(it.fileKey(), it.lastModifiedTime())
				}
			} catch (failed: IOException) {
				null
			}
	}
}
