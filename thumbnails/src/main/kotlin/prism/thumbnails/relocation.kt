package prism.thumbnails

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/*
 * A cache taken to another folder, or done away with, when the user sets another folder for thumbnails. Either is done
 * an entry at a time, and each entry whole: a thumbnail is in the folder it came from or the one it goes to, never
 * half in either. So moving can be stopped at any moment and leaves two good caches, the new one holding what was
 * moved, and the old one what was not.
 *
 * Only the cache's own entries are touched ([ThumbnailCache.entries]: PNGs named as thumbnails are, in the folders
 * thumbnails are kept in). A folder the user named that holds other things too loses none of them, whichever is
 * chosen; and a folder is removed only once it is empty.
 */

/** How far a cache's entries have been gone through. */
data class Relocated(
	/** How many have been moved, or removed. */
	val done: Int,
	/** How many could not be: left where they were. */
	val failed: Int,
	/** How many there were to do. */
	val of: Int,
) {
	val finished: Boolean get() = done + failed >= of

	/** How far through, from 0 to 1. */
	val fraction: Float
		get() =
			if (of == 0)
				1f
			else
				(done + failed).toFloat() / of
}

object Relocation {
	/** Whether [cache] holds any entry at all: whether there is anything to ask what to do with. */
	fun holds(cache: ThumbnailCache): Boolean = cache.entries().any()

	/**
	 * Every entry of [from] moved to [to], [progress] told after each. Stopped where whoever waits is cancelled:
	 * what was moved is in [to], and the rest is where it was. An entry [to] already has is replaced by the one
	 * moved, which is of the same file and at least as new.
	 */
	suspend fun move(
		from: ThumbnailCache,
		to: ThumbnailCache,
		progress: (Relocated) -> Unit = {},
	): Relocated =
		each(from, progress) { entry ->
			if (from.root != to.root)
				moved(from.path(entry), to.path(entry))
		}

	/** Every entry of [cache] removed, [progress] told after each. */
	suspend fun delete(
		cache: ThumbnailCache,
		progress: (Relocated) -> Unit = {},
	): Relocated = each(cache, progress) { Files.deleteIfExists(cache.path(it)) }

	private suspend fun each(
		cache: ThumbnailCache,
		progress: (Relocated) -> Unit,
		act: (Entry) -> Unit,
	): Relocated =
		withContext(Dispatchers.IO) {
			val folders = cache.folders()
			val entries = cache.entries().map { it.entry }.toList()
			var at = Relocated(0, 0, entries.size)

			progress(at)
			for (entry in entries) {
				coroutineContext.ensureActive()
				at =
					try {
						act(entry)
						at.copy(done = at.done + 1)
					} catch (failed: IOException) {
						at.copy(failed = at.failed + 1)
					}
				progress(at)
			}
			// The folders they were in, where nothing is left in them: never one that holds anything else.
			// One more folder, not its names: a path added to a list of paths is each of its parts.
			folders.plusElement(cache.root.resolve(ThumbnailCache.FAIL)).forEach(::removedIfEmpty)
			at
		}

	/** The file at [from] made the file at [to], whole: renamed, or where they are on two disks, copied beside and renamed. */
	private fun moved(from: Path, to: Path) {
		Files.createDirectories(to.parent)
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
		} catch (apart: AtomicMoveNotSupportedException) {
			val beside = Files.createTempFile(to.parent, ".prism", ".tmp")

			try {
				Files.copy(from, beside, StandardCopyOption.REPLACE_EXISTING)
				Files.move(beside, to, StandardCopyOption.ATOMIC_MOVE)
			} finally {
				Files.deleteIfExists(beside)
			}
			Files.delete(from)
		}
	}

	private fun removedIfEmpty(folder: Path) {
		try {
			Files.deleteIfExists(folder)
		} catch (held: DirectoryNotEmptyException) {
			// Something else is kept there: it is not the cache's to remove.
		} catch (failed: IOException) {
			// Nor is one that cannot be removed.
		}
	}
}
