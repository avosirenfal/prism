package prism.browser

import androidx.compose.runtime.Immutable

/*
 * Undo and redo of a value edited step by step, a window's or a dialog's own: what it was before each edit, and what it
 * was before each undo, for each to be gone back to. The value itself is wherever it is kept (the dialog's state, the
 * browser's); this keeps only the steps, so the two cannot disagree.
 */

/** The steps of a value's edits: what it was before each, the latest last; and what was undone, the latest first. */
@Immutable
data class Edits<T>(
	val before: List<T> = emptyList(),
	val undone: List<T> = emptyList(),
) {
	/** After an edit of what was [was]: it is what an undo goes back to, and nothing undone can be done again. */
	fun edited(was: T): Edits<T> = Edits(before + was, emptyList())

	/** [now] undone: the value it goes back to, and the history after; null where there is nothing to undo. */
	fun undo(now: T): Undone<T>? =
		before.lastOrNull()?.let {
			Undone(
				it,
				Edits(
					before.dropLast(1),
					listOf(now) + undone,
				),
			)
		}

	/** What [now] undid, done again; null where nothing was undone. */
	fun redo(now: T): Undone<T>? =
		undone.firstOrNull()?.let {
			Undone(it, Edits(before + now, undone.drop(1)))
		}
}

/** An undo or a redo: the value [to] go to, and the [edits] after it. */
@Immutable
data class Undone<T>(
	val to: T,
	val edits: Edits<T>,
)
