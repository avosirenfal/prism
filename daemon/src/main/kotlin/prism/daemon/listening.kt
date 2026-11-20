package prism.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import prism.index.FileSystems
import prism.platform.FileId
import prism.platform.Mount
import prism.shared.Guarded

/**
 * Folders listened to in a daemon's feed: what is told, of each folder asked about, which of its entries changed, as
 * the feed says it ([Telling.changed]). The feed sees every change on the machine, whatever the index holds, so a
 * folder the index leaves out can be listened to all the same, at no cost beyond looking each change's folder up:
 * the folder thumbnails are kept in is under the user's cache, which the index never reads, and what other programs
 * put there is heard of this way.
 *
 * A feed names a folder as the kernel does, by its file system's device and its inode, so a folder listened to is
 * looked at once, as it is asked about, for those two. One that is not there yet cannot be listened to; nor is one
 * followed if it is removed and made again, which is a new folder to the kernel.
 */
class Listening(
	private val mounts: () -> List<Mount> = FileSystems.SYSTEM::mounted,
) {
	private val listeners = Guarded(HashMap<FileId, List<(Set<String>?) -> Unit>>())

	/**
	 * [changed] told the names of the entries of [folder] that change, or null where the feed says only that
	 * something in it did, until what this gives is closed. Nothing is told of a folder that is not there.
	 */
	fun watch(folder: Path, changed: (Set<String>?) -> Unit): AutoCloseable {
		val id = idOf(folder, mounts()) ?: return AutoCloseable {}

		listeners.guarded { merge(id, listOf(changed)) { before, added -> before + added } }

		return AutoCloseable {
			listeners.guarded {
				computeIfPresent(id) { _, told -> (told - changed).takeIf { it.isNotEmpty() } }
			}
		}
	}

	/** A batch of the feed's changes: each folder listened to told of its own. */
	fun heard(batch: List<Changed>) {
		val told =
			listeners.guarded {
				if (isEmpty())
					return
				else
					HashMap(this)
			}

		batch
			.filter { it.folder in told }
			.groupBy({ it.folder }, { it.name })
			.forEach { (folder, names) ->
				// A change that names no entry is of the folder as a whole, and so is the batch it is in.
				val said =
					if (names.any { it == null })
						null
					else
						names.mapNotNullTo(HashSet()) {
							it?.let { name ->
								String(name.bytes(), Charsets.UTF_8)
							}
						}

				told.getValue(folder).forEach { it(said) }
			}
	}

	companion object {
		/**
		 * [folder] as a feed names it: its inode, and the kernel's device of the file system it is on, which is that of
		 * the mount it is under (not always the device a stat of it gives: on btrfs, each subvolume's stat gives its
		 * own). Null where it is not there.
		 */
		fun idOf(folder: Path, mounts: List<Mount>): FileId? =
			try {
				val real = folder.toRealPath()
				val inode = Files.getAttribute(real, "unix:ino") as Long

				mounts
					.filter { real.startsWith(it.at) }
					.maxByOrNull { it.at.nameCount }
					?.let { FileId(it.dev, inode) }
			} catch (gone: IOException) {
				null
			} catch (unsupported: UnsupportedOperationException) {
				null
			} catch (unsupported: IllegalArgumentException) {
				null
			}
	}
}
