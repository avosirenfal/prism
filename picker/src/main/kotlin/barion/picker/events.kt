package barion.picker

import androidx.compose.runtime.Immutable
import java.nio.file.Path

/*
 * What changes the picker, and what the picker asks for. Two things change it: what the user does ([PickerEvent],
 * which is all the view can report) and what a folder read or a name looked up turned out to be ([Answer], which
 * only whoever carries out an [Effect] can report). The reducer (step.kt) takes either and gives the next state with
 * the effects it wants, as a [Step].
 */

/** Anything the reducer takes. */
sealed interface Input

/** What the user did, as the view reports it. The view changes nothing itself. */
sealed interface PickerEvent : Input {
	// ---- going places ----

	/** A folder opened from the path across the top or from the sidebar. */
	data class Opened(
		val path: Path,
	) : PickerEvent

	/** Back: the toolbar's button, Alt+Left, or the mouse's back button. */
	data object Back : PickerEvent

	/** Forward: the toolbar's button, Alt+Right, or the mouse's forward button. */
	data object Forward : PickerEvent

	data object Up : PickerEvent

	/** The folder shown, read again. */
	data object Refreshed : PickerEvent

	// ---- the listing ----

	/** A row pressed with the pointer, with [click] saying what the keys held made of it. */
	data class Clicked(
		val row: RowId,
		val click: Click,
	) : PickerEvent

	/** A row chosen and accepted at once, from its menu: a file opened, a folder given by a folder picker. */
	data class Picked(
		val row: RowId,
	) : PickerEvent

	/** The listing pressed where there is no row: nothing chosen. */
	data object ClickedNowhere : PickerEvent

	/** A row double-clicked: a folder opened, a file accepted. */
	data class Activated(
		val row: RowId,
	) : PickerEvent

	/** The keyboard's cursor moved, choosing on its way as [carry] says. */
	data class Moved(
		val move: Move,
		val carry: Carry,
	) : PickerEvent

	/** The row at the cursor chosen, or no longer chosen where several can be (Space). */
	data object Toggled : PickerEvent

	/** Every row shown chosen, where several can be (Ctrl+A). */
	data object ChoseAll : PickerEvent

	/** Letters typed in the listing: the cursor to the next row whose name starts with them. */
	data class Jumped(
		val prefix: String,
	) : PickerEvent

	data class Sorted(
		val by: Sort.Column,
	) : PickerEvent

	/** Hidden files shown, or hidden again. */
	data object ToggledHiddenFiles : PickerEvent

	/** Hidden folders shown, or hidden again. */
	data object ToggledHiddenFolders : PickerEvent

	/** Every hidden entry shown, or none where every one was (Ctrl+H). */
	data object ToggledEveryHidden : PickerEvent

	/** Only one kind of entry shown: a filter chosen from the filter menu. */
	data class ShowedOnlyKind(
		val kind: Kind,
	) : PickerEvent

	/** A kind of entry added to the kinds shown, or taken away: a filter chosen with Ctrl held. */
	data class ToggledKind(
		val kind: Kind,
	) : PickerEvent

	/** Every kind shown again: no kind chosen. */
	data object ShowedEveryKind : PickerEvent

	/** The listing ordered as [sort] says: chosen from a menu, where the header's click turns the order round. */
	data class SortedBy(
		val sort: Sort,
	) : PickerEvent

	/** The listing drawn [steps] sizes larger, or smaller where negative (Ctrl and the wheel, Ctrl+= and Ctrl+-). */
	data class Zoomed(
		val steps: Int,
	) : PickerEvent

	/** The listing drawn at its usual size again (Ctrl+0). */
	data object Unzoomed : PickerEvent

	/** The gallery's room measured, as the window is made or resized: [whole] is the size a tile fills it at. */
	data class Measured(
		val whole: Float,
	) : PickerEvent

	/** The latest of the picker's own changes to the disk undone (Ctrl+Z). */
	data object Undone : PickerEvent

	/** The latest change undone, done again (Ctrl+Shift+Z, Ctrl+Y). */
	data object Redone : PickerEvent

	/** The listing drawn as rows or as tiles. */
	data class Viewed(
		val layout: Layout,
	) : PickerEvent

	// ---- changing what is on disk ----

	/** A row's name made into a field to type a new one into (F2). */
	data class StartedRenaming(
		val row: RowId,
	) : PickerEvent

	data class RenameTyped(
		val text: String,
	) : PickerEvent

	/** The new name typed, to be given (Enter, or the field left). */
	data object Renamed : PickerEvent

	/** The name left as it was (Escape). */
	data object RenameCancelled : PickerEvent

	/** Rows moved to the trash at once: from their menu, or Shift+Delete. */
	data class Trashed(
		val rows: List<RowId>,
	) : PickerEvent

	/** Rows to move to the trash once the user says yes (Delete, a key easily pressed by mistake). */
	data class AskedToTrash(
		val rows: List<RowId>,
	) : PickerEvent

	/** A folder made in the folder shown, its name then typed over (Ctrl+Shift+N). */
	data object MadeFolder : PickerEvent

	/** Paths put on the clipboard as text, one to a line. */
	data class CopiedPaths(
		val paths: List<Path>,
	) : PickerEvent

	// ---- the fields ----

	data class Searched(
		val query: String,
	) : PickerEvent

	/** The name field typed into. */
	data class Named(
		val text: String,
	) : PickerEvent

	data class Filtered(
		val filter: TypeFilter,
	) : PickerEvent

	/** The path across the top made into a field to type a path into (Ctrl+L). */
	data object EditedAddress : PickerEvent

	data class AddressTyped(
		val text: String,
	) : PickerEvent

	/** The typed path gone to (Enter). */
	data object Went : PickerEvent

	/** The typed path left, the steps shown again (Escape). */
	data object LeftAddress : PickerEvent

	// ---- the end ----

	data object Accepted : PickerEvent

	data object Cancelled : PickerEvent

	/** The notice shown answered yes: replace the file. */
	data object Confirmed : PickerEvent

	/** The notice shown put away. */
	data object Dismissed : PickerEvent
}

/** What a click does to what is chosen, by the keys held as it was made. */
enum class Click {
	/** Chooses that row alone. */
	Only,

	/** Chooses that row as well, or no longer (Ctrl). */
	Toggle,

	/** Chooses the rows from the cursor's anchor to that row (Shift). */
	Range,
}

/** Where a key moves the keyboard's cursor. */
sealed interface Move {
	/** [rows] down, or up where negative: the arrows, and Page Up and Down by a listing's height. */
	data class By(
		val rows: Int,
	) : Move

	data object First : Move

	data object Last : Move
}

/** What moving the cursor does to what is chosen, by the keys held. */
enum class Carry {
	/** The row it lands on is chosen, alone. */
	Choose,

	/** The rows from the anchor to where it lands are chosen (Shift). */
	Extend,

	/** Nothing chosen changes; only the cursor moves (Ctrl), where several can be chosen. */
	Stay,
}

/** What a folder read or a name looked up turned out to be, as whoever carried out an [Effect] reports it. */
sealed interface Answer : Input {
	data class Arrived(
		val folder: Path,
		val read: Listing.Read,
	) : Answer

	data class Looked(
		val lookup: Lookup,
		val found: Map<Path, Found>,
	) : Answer

	data class Changed(
		val change: Change,
		val result: ChangeResult,
		val doing: Doing = Doing.New,
	) : Answer
}

/** Why a change is made: asked for anew, or to undo or redo one; which says where what undoes it is kept. */
enum class Doing {
	New,
	Undo,
	Redo,
}

/** A change to what is on disk, asked of the disk; what came of it comes back as [Answer.Changed]. */
sealed interface Change {
	/** [from] renamed to [to], in the same folder; never over something already there. */
	data class Rename(
		val from: Path,
		val to: Path,
	) : Change

	/** [paths] moved to the trash, from where they can be put back. */
	data class Trash(
		val paths: List<Path>,
	) : Change

	/** Entries put back from the trash where they were: what undoes moving them there. */
	data class PutBack(
		val entries: List<Trashed>,
	) : Change {
		/** Where they go back to. */
		val originals: List<Path> get() = entries.map { it.original }
	}

	/** An empty folder made at [path]. */
	data class MakeFolder(
		val path: Path,
	) : Change

	/** The empty folder at [path] removed: what undoes making it. Refused if it is not empty. */
	data class RemoveFolder(
		val path: Path,
	) : Change
}

/** An entry in the trash: where it was, and the name the trash keeps it under. */
@Immutable
data class Trashed(
	val original: Path,
	val name: String,
)

/** What came of a [Change]. */
sealed interface ChangeResult {
	/** Done, and [undo] is what undoes it: only the disk knows some of that, such as what the trash named an entry. */
	data class Done(
		val undo: Change,
	) : ChangeResult

	/** Not done, or not all of it: why, as the system said. */
	data class Failed(
		val why: String,
	) : ChangeResult
}

/** Paths to look at on disk before the picker knows what to do with them: what the user typed, made into paths. */
sealed interface Lookup {
	val paths: List<Path>

	/** The path typed across the top, to go to. */
	data class Going(
		val text: String,
		val path: Path,
	) : Lookup {
		override val paths: List<Path> get() = listOf(path)
	}

	/** The names typed into the name field, to accept. */
	data class Accepting(
		val text: String,
		override val paths: List<Path>,
	) : Lookup
}

/** What is at a path. */
sealed interface Found {
	data object Folder : Found

	data object File : Found

	/** Nothing; [inFolder] says whether the folder it would be in is there, so that a file could be saved there. */
	data class Nothing(
		val inFolder: Boolean,
	) : Found
}

/** What the picker asks to be done, by whoever runs it. */
sealed interface Effect {
	/** Something to find out on disk, whose answer comes back as an [Answer]. */
	sealed interface Asking : Effect

	/** [folder] read, and the answer sent back as [Answer.Arrived]. */
	data class Read(
		val folder: Path,
	) : Asking

	/** What is at each of [lookup]'s paths, sent back as [Answer.Looked]. */
	data class Look(
		val lookup: Lookup,
	) : Asking

	/** [change] made on disk, for [doing], and what came of it sent back as [Answer.Changed]. */
	data class Make(
		val change: Change,
		val doing: Doing = Doing.New,
	) : Asking

	/** [text] put on the clipboard. */
	data class Copy(
		val text: String,
	) : Effect

	/** The picker done: what it gives the application that asked. */
	data class Finish(
		val outcome: Outcome,
	) : Effect
}

/** How a picker ended. */
sealed interface Outcome {
	/** The paths chosen: at least one, absolute. */
	data class Chosen(
		val paths: List<Path>,
	) : Outcome {
		init {
			require(paths.isNotEmpty()) { "a picker that chose nothing was cancelled" }
		}
	}

	data object Cancelled : Outcome
}

/** The reducer's answer: the next state, and what it asks to be done. */
data class Step(
	val state: PickerState,
	val effects: List<Effect> = emptyList(),
)
