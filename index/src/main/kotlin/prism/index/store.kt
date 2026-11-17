package prism.index

import java.nio.file.Path
import prism.shared.Guarded
import prism.shared.Published

/**
 * An index and its file. Changes are made to the index in memory at once; the file is written when [write] is asked
 * for, whole: the index compacted, as one snapshot, beside the file and then renamed over it, so that the file is
 * the index as it was last written or as it is now, and never part of either. Nothing is added to a file once it is
 * written: the index, compressed, is small enough to write whole, and a file of one snapshot is read at once. How
 * often it is written is whoever keeps it's to say (a daemon: once an hour, and as it closes). A store without a file
 * ([inMemory]) keeps its index in memory only, and its writes write nothing.
 *
 * Opening a store reads its file, or, where there is none (or one that cannot be read), starts from an empty index.
 * What it holds is what was made in it: reading the disk is the caller's ([Indexer]). The one thing here that changes
 * is which [Index] a store holds, and whether the file is behind it; the index itself never changes, so a search runs
 * on the index it was given while changes go on.
 */
class IndexStore private constructor(
	val file: Path?,
	index: Index,
	/** Whether a file is there that holds an index. */
	onDisk: Boolean,
	/** Whether the file is behind the index, or not as a file is now written. */
	behind: Boolean,
	/** How the store came to hold what it does. */
	val origin: Origin,
) {
	/** How a store was opened. */
	sealed interface Origin {
		/** From its file. [torn]: the file ended in a frame cut short or damaged, which was dropped. */
		data class Read(
			val torn: Boolean,
		) : Origin

		/** Empty, there being no file yet. */
		data object Missing : Origin

		/** Empty, the file being one this cannot read, which the first write writes over. */
		data class Unreadable(
			val why: String,
		) : Origin

		/** Without a file. */
		data object Memory : Origin
	}

	/** The index as it is now, every change made. */
	var index: Index by Published(index)
		private set

	/**
	 * How the file stands to the index: reached only by one writer at a time ([writing]), which is also what sets
	 * [index], so that the index and whether it is written change together.
	 */
	private class Writing(
		var onDisk: Boolean,
		var behind: Boolean,
	)

	private val writing = Guarded(Writing(onDisk, behind))

	/**
	 * Whether no file holds an index yet: none was read, and none has been written. The first index built is worth
	 * writing as soon as it is, since building it is the one thing that takes long.
	 */
	val unwritten: Boolean get() = file != null && writing.guarded { !onDisk }

	/**
	 * The index with [changes] made, in memory; on disk at the next [write]. Where the changes made since it was last
	 * compact have left an eighth of what it holds to be done away with, it is compacted, so that a search does not go
	 * over what is no longer there for an hour.
	 */
	fun make(changes: List<Change>) {
		writing.guarded {
			val made = index.with(changes)

			index =
				if (made.loose * 8L > made.size)
					made.compacted()
				else
					made
			behind = true
		}
	}

	/**
	 * The index written to its file, whole, where the file is behind it or there is none: compacted, beside the file,
	 * and renamed over it ([IndexFile.write]). The file that was there is there, whole, until the new one is.
	 */
	fun write() {
		writing.guarded {
			val file = file

			if (file != null && (behind || !onDisk)) {
				index = index.compacted()
				IndexFile.write(file, index)
				onDisk = true
			}
			behind = false
		}
	}

	companion object {
		/** The store whose file is [file]: what the file holds, or an empty index where there is no file it can read. */
		fun open(file: Path): IndexStore =
			when (val read = IndexFile.read(file)) {
				IndexRead.Missing ->
					IndexStore(file, Index.EMPTY, onDisk = false, behind = false, Origin.Missing)
				is IndexRead.Unreadable ->
					IndexStore(
						file,
						Index.EMPTY,
						onDisk = false,
						behind = false,
						Origin.Unreadable(read.why),
					)
				// A file with changes after its snapshot, as versions before this wrote them, or one cut short, is
				// not as a file is written now: the next write makes it one snapshot.
				is IndexRead.Read ->
					IndexStore(
						file,
						read.index,
						onDisk = true,
						behind = read.changes > 0 || read.torn,
						Origin.Read(read.torn),
					)
			}

		/** A store of [index] in memory alone, never written. */
		fun inMemory(index: Index = Index.EMPTY): IndexStore =
			IndexStore(null, index, onDisk = false, behind = false, Origin.Memory)
	}
}
