package barion.index

import java.nio.file.Path

/**
 * An index and its file, kept in step: what a daemon holds. Changes are made to the index in memory at once, and kept
 * until [flush] appends them to the file as one frame; once the changes appended since the last snapshot are a good
 * part of it, or the index in memory has drifted far from compact, the index is compacted and the file written anew.
 *
 * Opening a store reads its file, or, where there is none (or one that cannot be read), indexes [roots] and writes
 * one. The one thing in the index that changes is which [Index] a store holds, and what it has not yet written; the
 * index itself never changes, so a search runs on the index it was given while changes go on.
 */
class IndexStore private constructor(
	val file: Path,
	index: Index,
	/** How much of the file is good: what the next frame is written after. */
	private var length: Long,
	/** How much of the file is changes after its snapshot. */
	private var changes: Long,
	/** How the store came to hold what it does. */
	val origin: Origin,
) {
	/** How a store was opened. */
	sealed interface Origin {
		/** From its file. [torn]: the file ended in a frame cut short or damaged, which was dropped. */
		data class Read(
			val torn: Boolean,
		) : Origin

		/** By indexing, there being no file. */
		data object Built : Origin

		/** By indexing, the file being one this cannot read. */
		data class Rebuilt(
			val why: String,
		) : Origin
	}

	/** The index as it is now, every change made. */
	@Volatile
	var index: Index = index
		private set

	/** The changes made since the last flush. */
	private var pending: List<Change> = emptyList()

	/** The index with [changes] made, in memory; on disk at the next [flush]. */
	@Synchronized
	fun make(changes: List<Change>) {
		index = index.with(changes)
		pending = pending + changes
	}

	/** What was made since the last flush, written to the file; and the file compacted, where it is time. */
	@Synchronized
	fun flush() {
		if (pending.isNotEmpty()) {
			val appended = IndexFile.append(file, length, pending)

			changes += appended - length
			length = appended
			pending = emptyList()
		}
		if (timeToCompact())
			compact()
	}

	/** The index compacted, and written anew as one snapshot. */
	@Synchronized
	fun compact() {
		index = index.compacted()
		length = IndexFile.write(file, index)
		changes = 0
		pending = emptyList()
	}

	/**
	 * Whether compacting gains enough: the changes on disk are a quarter of the snapshot, or the entries in memory
	 * that compacting would do away with are an eighth of what is held.
	 */
	private fun timeToCompact(): Boolean =
		changes * 4 > length - changes || index.loose * 8L > index.size

	companion object {
		/**
		 * The store whose file is [file]: read, or where there is none, [roots] indexed by [indexer] (each in turn,
		 * however long that takes) and written.
		 */
		fun open(file: Path, roots: List<Path>, indexer: Indexer = Indexer()): IndexStore {
			fun built(origin: Origin): IndexStore {
				val index =
					roots
						.fold(
							Index.EMPTY,
						) { index, root -> index.with(indexer.index(root)) }
						.compacted()

				return IndexStore(file, index, IndexFile.write(file, index), 0, origin)
			}

			return when (val read = IndexFile.read(file)) {
				IndexRead.Missing -> built(Origin.Built)
				is IndexRead.Unreadable -> built(Origin.Rebuilt(read.why))
				is IndexRead.Read ->
					IndexStore(
						file,
						read.index,
						read.length,
						read.changes,
						Origin.Read(read.torn),
					)
			}
		}
	}
}
