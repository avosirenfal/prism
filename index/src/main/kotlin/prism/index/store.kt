package prism.index

import java.nio.file.Path
import prism.shared.Guarded
import prism.shared.Published

/**
 * An index and its file, kept in step. Changes are made to the index in memory at once, and kept until [flush] appends
 * them to the file as one frame; once the changes appended since the last snapshot are a good part of it, or the
 * index in memory has drifted far from compact, the index is compacted and the file written anew. A store without a
 * file ([inMemory]) keeps its index in memory only, and its flushes write nothing.
 *
 * Opening a store reads its file, or, where there is none (or one that cannot be read), starts from an empty index;
 * the first flush writes the file. What it holds is what was made in it: reading the disk is the caller's
 * ([Indexer]). The one thing here that changes is which [Index] a store holds, and what it has not yet written; the
 * index itself never changes, so a search runs on the index it was given while changes go on.
 */
class IndexStore private constructor(
	val file: Path?,
	index: Index,
	/** How much of the file is good: what the next frame is written after; 0 for a file not written yet. */
	length: Long,
	/** How much of the file is changes after its snapshot. */
	changes: Long,
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

		/** Empty, the file being one this cannot read, which the first flush writes over. */
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
	 * What is written of the index, and what is not yet: reached only by one writer at a time ([writing]), which is
	 * also what sets [index], so that the index and what is to be written of it change together.
	 */
	private inner class Writing(
		/** How much of the file is good: what the next frame is written after; 0 for a file not written yet. */
		var length: Long,
		/** How much of the file is changes after its snapshot. */
		var changes: Long,
	) {
		/** The changes made since the last flush. */
		var pending: List<Change> = emptyList()

		fun compact() {
			index = index.compacted()
			file?.let { length = IndexFile.write(it, index) }
			changes = 0
			pending = emptyList()
		}

		/**
		 * Whether compacting gains enough: the changes on disk are a quarter of the snapshot, or the entries in
		 * memory that compacting would do away with are an eighth of what is held.
		 */
		fun timeToCompact(): Boolean =
			changes * 4 > length - changes || index.loose * 8L > index.size
	}

	private val writing = Guarded(Writing(length, changes))

	/** The index with [changes] made, in memory; on disk at the next [flush]. */
	fun make(changes: List<Change>) {
		writing.guarded {
			index = index.with(changes)
			pending = pending + changes
		}
	}

	/**
	 * What was made since the last flush, written to the file: appended, or, where there is no file yet, the whole
	 * index written as one; and the file compacted, where it is time.
	 */
	fun flush() {
		writing.guarded {
			val file = file

			if (file == null) {
				pending = emptyList()
			} else if (length == 0L) {
				compact()
			} else {
				if (pending.isNotEmpty()) {
					val appended = IndexFile.append(file, length, pending)

					changes += appended - length
					length = appended
					pending = emptyList()
				}
				if (timeToCompact())
					compact()
			}
		}
	}

	/** The index compacted, and written anew as one snapshot. */
	fun compact() {
		writing.guarded { compact() }
	}

	companion object {
		/** The store whose file is [file]: what the file holds, or an empty index where there is no file it can read. */
		fun open(file: Path): IndexStore =
			when (val read = IndexFile.read(file)) {
				IndexRead.Missing -> IndexStore(file, Index.EMPTY, 0, 0, Origin.Missing)
				is IndexRead.Unreadable ->
					IndexStore(
						file,
						Index.EMPTY,
						0,
						0,
						Origin.Unreadable(read.why),
					)
				is IndexRead.Read ->
					IndexStore(
						file,
						read.index,
						read.length,
						read.changes,
						Origin.Read(read.torn),
					)
			}

		/** A store of [index] in memory alone, never written. */
		fun inMemory(index: Index = Index.EMPTY): IndexStore =
			IndexStore(null, index, 0, 0, Origin.Memory)
	}
}
