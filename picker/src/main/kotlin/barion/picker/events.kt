package barion.picker

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

	data object Back : PickerEvent

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

	/** Hidden rows shown, or hidden again (Ctrl+H). */
	data object ToggledHidden : PickerEvent

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
