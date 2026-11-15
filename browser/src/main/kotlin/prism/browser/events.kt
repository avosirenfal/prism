package prism.browser

import androidx.compose.runtime.Immutable
import java.nio.file.Path
import java.time.Instant
import prism.desktop.Launched
import prism.desktop.Opening
import prism.desktop.Storage
import prism.desktop.StoreKind
import prism.desktop.TerminalChoice
import prism.index.query.Query
import prism.preferences.Align
import prism.preferences.ColumnId
import prism.preferences.Kind
import prism.preferences.Layout
import prism.preferences.Listings
import prism.preferences.MenuSpec
import prism.preferences.QuickAccess
import prism.preferences.Sort

/*
 * What changes the picker, and what the picker asks for. Two things change it: what the user does ([BrowserEvent],
 * which is all the view can report) and what a folder read or a name looked up turned out to be ([Answer], which
 * only whoever carries out an [Effect] can report). The reducer (step.kt) takes either and gives the next state with
 * the effects it wants, as a [Step].
 */

/** Anything the reducer takes. */
sealed interface Input

/** What the user did, as the view reports it. The view changes nothing itself. */
sealed interface BrowserEvent : Input {
	// ---- going places ----

	/** A folder opened from the path across the top or from the sidebar. */
	data class Opened(
		val path: Path,
	) : BrowserEvent

	/** Back: the toolbar's button, Alt+Left, or the mouse's back button. */
	data object Back : BrowserEvent

	/** Forward: the toolbar's button, Alt+Right, or the mouse's forward button. */
	data object Forward : BrowserEvent

	data object Up : BrowserEvent

	/** The folder shown, read again. */
	data object Refreshed : BrowserEvent

	// ---- the listing ----

	/** A row pressed with the pointer, with [click] saying what the keys held made of it. */
	data class Clicked(
		val row: RowId,
		val click: Click,
	) : BrowserEvent

	/** A row chosen and accepted at once, from its menu: a file opened, a folder given by a folder picker. */
	data class Picked(
		val row: RowId,
	) : BrowserEvent

	/** The listing pressed where there is no row: nothing chosen. */
	data object ClickedNowhere : BrowserEvent

	/** A row double-clicked: a folder opened, a file accepted. */
	data class Activated(
		val row: RowId,
	) : BrowserEvent

	/** The keyboard's cursor moved, choosing on its way as [carry] says. */
	data class Moved(
		val move: Move,
		val carry: Carry,
	) : BrowserEvent

	/** The row at the cursor chosen, or no longer chosen where several can be (Space). */
	data object Toggled : BrowserEvent

	/** Every row shown chosen, where several can be (Ctrl+A). */
	data object ChoseAll : BrowserEvent

	/**
	 * A character typed in the listing, at [at] (milliseconds): one more of the letters that name a row ([Jump]),
	 * where the one before it was typed within the pause, and the first of them otherwise; the cursor goes to the
	 * row they name.
	 */
	data class Jumped(
		val typed: Char,
		val at: Long,
	) : BrowserEvent

	/** The last of the letters that name a row taken back (Backspace while they are being typed), at [at]. */
	data class JumpErased(
		val at: Long,
	) : BrowserEvent

	/**
	 * The letters that name a row are done with: the pause after the one typed at [at] ran out, which ends them
	 * only where none was typed since; or, with no [at], they were let go (Escape).
	 */
	data class JumpEnded(
		val at: Long? = null,
	) : BrowserEvent

	data class Sorted(
		val by: ColumnId,
	) : BrowserEvent

	/** Hidden files shown, or hidden again. */
	data object ToggledHiddenFiles : BrowserEvent

	/** Hidden folders shown, or hidden again. */
	data object ToggledHiddenFolders : BrowserEvent

	/** Every hidden entry shown, or none where every one was (Ctrl+H). */
	data object ToggledEveryHidden : BrowserEvent

	/** Only one kind of entry shown: a filter chosen from the filter menu. */
	data class ShowedOnlyKind(
		val kind: Kind,
	) : BrowserEvent

	/** A kind of entry added to the kinds shown, or taken away: a filter chosen with Ctrl held. */
	data class ToggledKind(
		val kind: Kind,
	) : BrowserEvent

	/** Every kind shown again: no kind chosen. */
	data object ShowedEveryKind : BrowserEvent

	/** The listing ordered as [sort] says: chosen from a menu, where the header's click turns the order round. */
	data class SortedBy(
		val sort: Sort,
	) : BrowserEvent

	/** The listing drawn [steps] sizes larger, or smaller where negative (Ctrl and the wheel, Ctrl+= and Ctrl+-). */
	data class Zoomed(
		val steps: Int,
	) : BrowserEvent

	/** The listing drawn at its usual size again (Ctrl+0). */
	data object Unzoomed : BrowserEvent

	/** The gallery's room measured, as the window is made or resized: [whole] is the size a tile fills it at. */
	data class Measured(
		val whole: Float,
	) : BrowserEvent

	/** The latest of the picker's own changes to the disk undone (Ctrl+Z). */
	data object Undone : BrowserEvent

	/** The latest change undone, done again (Ctrl+Shift+Z, Ctrl+Y). */
	data object Redone : BrowserEvent

	/** The listing drawn as rows or as tiles. */
	data class Viewed(
		val layout: Layout,
	) : BrowserEvent

	/** A search's results ordered best first again, as they were found (from the sort menu). */
	data object SortedByRelevance : BrowserEvent

	/** The rows from [first] to [last] (counting from the top) in sight, as the listing is laid out or scrolled. */
	data class Scrolled(
		val first: Int,
		val last: Int,
	) : BrowserEvent

	/** The date column made to show [date]: when files were last modified, or when they were made. */

	/** [column] shown in the listing, or left out. */
	data class ShowedColumn(
		val column: ColumnId,
		val shown: Boolean,
	) : BrowserEvent

	/** [column]'s values set as [align] says. */
	data class AlignedColumn(
		val column: ColumnId,
		val align: Align,
	) : BrowserEvent

	// ---- the search of the index ----

	/** The search's window opened over the picker, with nothing typed into it yet (F2). */
	data object OpenedSearch : BrowserEvent

	/**
	 * The search's window opened with [text] in it, to be searched for again or changed: what the results shown were
	 * searched for (the bar that says so, clicked).
	 */
	data class EditedSearch(
		val text: String,
	) : BrowserEvent

	/** How a query is written shown in place of the search's field and matches (its `?`), or left for them again. */
	data object ToggledSearchHelp : BrowserEvent

	/** The search's window put away (Escape, or a click outside it). */
	data object ClosedSearch : BrowserEvent

	/** The search's field typed into. */
	data class SearchTyped(
		val text: String,
	) : BrowserEvent

	/** The arrows moved through the matches: [by] down, or up where negative; up from the first is back to the field. */
	data class MatchMoved(
		val by: Int,
	) : BrowserEvent

	/** What the search's window lists [at] clicked: a match gone to, as the picker's kind says; one of prism's own done. */
	data class MatchChosen(
		val at: Int,
	) : BrowserEvent

	/**
	 * Enter in the search's window: the match the arrows are on gone to, or, from the field, every match shown; with
	 * a result's menu open, the action the arrows are on done.
	 */
	data object SearchEntered : BrowserEvent

	/**
	 * What can be done to what the search's window lists [at] shown as a menu by it (a right click); with no [at],
	 * to what the arrows are on (the right arrow).
	 */
	data class MatchMenuOpened(
		val at: Int? = null,
	) : BrowserEvent

	/** That menu put away (the left arrow, Escape, a click outside it). */
	data object MatchMenuClosed : BrowserEvent

	/** The menu's action [at] done (a click on it). */
	data class MatchActionChosen(
		val at: Int,
	) : BrowserEvent

	/** The search's button: every match shown in the listing. */
	data object ShowedResults : BrowserEvent

	// ---- changing what is on disk ----

	/** A row's name made into a field to type a new one into (F6). */
	data class StartedRenaming(
		val row: RowId,
	) : BrowserEvent

	data class RenameTyped(
		val text: String,
	) : BrowserEvent

	/** The new name typed, to be given (Enter, or the field left). */
	data object Renamed : BrowserEvent

	/** The name left as it was (Escape). */
	data object RenameCancelled : BrowserEvent

	/**
	 * Rows moved to their trashes (Delete, or their menu): at once, where each is on a drive with a trash; those on a
	 * drive with none are deleted for good once the user says so.
	 */
	data class Trashed(
		val rows: List<RowId>,
	) : BrowserEvent

	/** Rows deleted for good once the user says so (Shift+Delete, or their menu): in the trash's window, from the trash. */
	data class AskedToErase(
		val rows: List<RowId>,
	) : BrowserEvent

	/** Entries of the trash put back where they were (the trash's window). */
	data class PutBack(
		val rows: List<RowId>,
	) : BrowserEvent

	/** Every trash emptied, once the user says so. */
	data object AskedToEmpty : BrowserEvent

	/** The trash shown: every trash's entries together. */
	data object OpenedTrash : BrowserEvent

	/** The System page shown: the machine's drives, and how full each is. */
	data object OpenedSystem : BrowserEvent

	/** The System page's stores of [kind] shown: those in the machine, removable ones, or the network's. */
	data class ShowedStores(
		val kind: StoreKind,
	) : BrowserEvent

	/** The System page's store of [key] folded to its bar, or, folded, unfolded. */
	data class FoldedStore(
		val key: String,
	) : BrowserEvent

	/** A folder made in the folder shown, its name then typed over (Ctrl+Shift+N). */
	data object MadeFolder : BrowserEvent

	/** [text] put on the clipboard: what a notice says, copied from it. */
	data class CopiedText(
		val text: String,
	) : BrowserEvent

	/** Paths put on the clipboard as text, one to a line. */
	data class CopiedPaths(
		val paths: List<Path>,
	) : BrowserEvent

	/** An empty file made in the folder shown, its name then typed over. */
	data object MadeFile : BrowserEvent

	/** Rows put on the clipboard as files, to be copied where they are pasted (Ctrl+C). */
	data class CopiedFiles(
		val rows: List<RowId>,
	) : BrowserEvent

	/** Rows put on the clipboard as files, to be moved where they are pasted (Ctrl+X). */
	data class CutFiles(
		val rows: List<RowId>,
	) : BrowserEvent

	/** The files on the clipboard copied, or moved where they were cut, into [into]: the folder shown, where null. */
	data class Pasted(
		val into: Path? = null,
	) : BrowserEvent

	/**
	 * Files dropped on [spot], to be copied, moved or linked there as [transfer] says; the spot says where. The files
	 * may be the browser's own, dragged from its listing, or another application's.
	 */
	data class Dropped(
		val paths: List<Path>,
		val spot: DropSpot,
		val transfer: Transfer,
	) : BrowserEvent

	// ---- the desktop, and the application around the browser ----

	/** [path] pinned to the sidebar, after the places pinned already; shown, where it is one of prism's own hidden. */
	data class Pinned(
		val path: Path,
	) : BrowserEvent

	/** [item] taken off the sidebar: unpinned, where it is a folder the user pinned; hidden, where it is prism's own. */
	data class Unpinned(
		val item: SidebarItem,
	) : BrowserEvent

	/** Quick access arranged anew, as its window arranges it. */
	data class Arranged(
		val quick: QuickAccess,
	) : BrowserEvent

	/** The window that arranges quick access opened, or closed. */
	data class ArrangingQuickAccess(
		val open: Boolean,
	) : BrowserEvent

	/** A terminal opened in [folder]: the folder shown, where null. */
	data class OpenedTerminal(
		val folder: Path? = null,
	) : BrowserEvent

	/** Something only the application around the browser can do: a window of its own. */
	data class Requested(
		val request: Request,
	) : BrowserEvent

	// ---- the user's own actions, and places ----

	/**
	 * [action] chosen for [subject]: from a menu, by its key, or, with [answers] to some of what it asks, by something
	 * dropped or pasted, which answers where to.
	 */
	data class Did(
		val action: UserAction,
		val subject: Subject,
		val answers: Map<String, String> = emptyMap(),
	) : BrowserEvent

	/** The line an action asked for typed into. */
	data class AnswerTyped(
		val text: String,
	) : BrowserEvent

	/** The folder an action asked for, chosen in a picker; null where the picker was cancelled. */
	data class Answered(
		val folder: Path?,
	) : BrowserEvent

	/** The folder of the place shown at [within] gone to: a step of its path, clicked. */
	data class WentWithin(
		val within: List<String>,
	) : BrowserEvent

	/** The rows a rectangle dragged out over the listing, from where no row is, takes in: chosen, in place of what was. */
	data class Banded(
		val rows: Set<RowId>,
	) : BrowserEvent

	/** [task], under way, to be stopped: the button of what shows it running, or Escape. */
	data class Stopped(
		val task: Task,
	) : BrowserEvent

	/**
	 * Entries of a place dragged out of it, as the drag describes them ([entries]), dropped on [spot]: brought out into
	 * the folder there, by this window's own actions, whichever window of prism (in whichever process) they came from.
	 */
	data class DroppedEntries(
		val entries: EntriesOut,
		val spot: DropSpot,
	) : BrowserEvent

	/** Entries of a place, [carried] from it, dropped on [spot]: brought out into the folder there. */
	data class DroppedCarried(
		val carried: Carried,
		val spot: DropSpot,
	) : BrowserEvent

	// ---- the fields ----

	data class Searched(
		val query: String,
	) : BrowserEvent

	/** The name field typed into. */
	data class Named(
		val text: String,
	) : BrowserEvent

	data class Filtered(
		val filter: TypeFilter,
	) : BrowserEvent

	/** The path across the top made into a field to type a path into (Ctrl+L). */
	data object EditedAddress : BrowserEvent

	data class AddressTyped(
		val text: String,
	) : BrowserEvent

	/** The typed path gone to (Enter). */
	data object Went : BrowserEvent

	/** The typed path left, the steps shown again (Escape). */
	data object LeftAddress : BrowserEvent

	// ---- the end ----

	data object Accepted : BrowserEvent

	data object Cancelled : BrowserEvent

	/** The notice shown answered yes: replace the file. */
	data object Confirmed : BrowserEvent

	/** The notice shown put away. */
	data object Dismissed : BrowserEvent
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

	/** What [erasing] deleted for good: all of it, or, where [failed] says why, what came before it. */
	data class Erased(
		val erasing: Erasing,
		val failed: String?,
	) : Answer

	/** Every trash's entries, read for the trash's window. */
	data class TrashRead(
		val read: Listing.Read,
	) : Answer

	/** The machine's storage, read for the System page. */
	data class StorageRead(
		val storage: Storage,
	) : Answer

	/** What a search asked for [purpose] found. */
	data class Searched(
		val finding: Finding,
		val purpose: Purpose,
	) : Answer

	/** When the entries of [folder] named were made: each one's time, or none where its file system keeps none. */
	data class Born(
		val folder: Path,
		val times: Map<String, Instant?>,
	) : Answer

	/** What reading the results at [ranks] among [matches] on disk said of each, one for one ([Stats.read]). */
	data class Stated(
		val matches: Matches,
		val ranks: Ranks,
		val stats: List<Stat>,
	) : Answer

	/** [matches] in order by [column], from the least, as [Matches.orderedBy] works it out. */
	data class Ordered(
		val matches: Matches,
		val column: ColumnId,
		val order: Permutation,
	) : Answer

	/** What opening files with their applications came to. */
	data class Opened(
		val opening: Opening,
	) : Answer

	/** What starting a program (a terminal) came to. */
	data class Started(
		val launched: Launched,
	) : Answer

	/**
	 * What was copied, read to be pasted into [into] (the place shown, where null): the files on the clipboard, or the
	 * entries of a place copied in this process since, which are what a paste then means.
	 */
	data class Clipped(
		val into: Path?,
		val clip: FileClip?,
		val carried: Carried? = null,
	) : Answer

	/** What listing [placed] gave. */
	data class Listed(
		val placed: prism.browser.Placed,
		val listing: PlaceListing,
	) : Answer

	/** What [job]'s commands came to: nothing where they all worked, or why the first that did not, did not. */
	data class Ran(
		val job: Job,
		val failure: String?,
	) : Answer

	/**
	 * prism itself went wrong answering [asking]: not what the disk, the desktop or a command refused, which each
	 * answer says in its own way, but a mistake of prism's ([fault]). There is no answer, and none is made up: what
	 * waited for one is told there is none.
	 */
	data class Broke(
		val asking: Effect.Asking,
		val fault: Fault,
	) : Answer

	/** What [folder] holds changed on disk, as whatever watches it says: made by anything, prism or not. */
	data class Touched(
		val folder: Path,
	) : Answer

	/** A line [task] wrote as it runs. */
	data class Said(
		val task: Task,
		val line: String,
	) : Answer

	/** A folder made for [pending]'s command to work in, which is its `{temp}`. */
	data class ScratchMade(
		val folder: Path,
		val pending: Pending,
	) : Answer

	/** A folder made for [carried]'s entries to be brought out into, to be opened from. */
	data class TempMade(
		val folder: Path,
		val carried: Carried,
	) : Answer

	/**
	 * The actions and places, the terminal chosen, and how the menus are arranged, as the preferences (which another
	 * window may have changed) now make them.
	 */
	data class Configured(
		val actions: Actions,
		val terminal: TerminalChoice = TerminalChoice.Found,
		val menus: MenuSpec = MenuSpec(),
	) : Answer

	/** How listings look as the preferences (which the preferences window may have changed) now say. */
	data class Relisted(
		val listings: Listings,
	) : Answer

	/** The sidebar, as the places pinned (which another window may have changed) and the drives now make it. */
	data class Placed(
		val sidebar: Sidebar,
	) : Answer
}

/** What files given to a folder (dropped, or pasted) become there. */
enum class Transfer {
	Copy,
	Move,
	Link,
	;

	/** [sources] given to [into] this way, as a change of the disk's. */
	fun changeOf(sources: List<Path>, into: Path): Change =
		when (this) {
			Copy -> Change.Copy(sources, into)
			Move -> Change.Move(sources, into)
			Link -> Change.Link(sources, into)
		}
}

/** Where files are dropped. */
@Immutable
sealed interface DropSpot {
	/** On the listing itself, between its rows: the folder shown, or, in a picker, the files to choose. */
	data object Here : DropSpot

	/** On a folder: a folder's row, a place in the sidebar, a step of the path. */
	data class Into(
		val folder: Path,
	) : DropSpot
}

/** Files on the clipboard, and whether they were cut (to be moved) or copied. */
@Immutable
data class FileClip(
	val paths: List<Path>,
	val cut: Boolean,
)

/** What only the application around a browser can do: open a window of its own. */
@Immutable
sealed interface Request {
	/** A window to choose an application to open [paths] with, and to make it the default. */
	data class OpenWith(
		val paths: List<Path>,
		/** Whether the application chosen can be made the default for their type: not where an action opens them. */
		val settable: Boolean = true,
	) : Request

	/** A window of [paths]' properties. */
	data class Properties(
		val paths: List<Path>,
	) : Request

	/** Another explorer, on [start]. */
	data class NewWindow(
		val start: Start,
	) : Request {
		constructor(folder: Path) : this(Start.Folder(folder))
	}

	/** A folder chosen in a picker titled [title] that opens on [start]: answered with [BrowserEvent.Answered]. */
	data class PickFolder(
		val title: String,
		val start: Path,
	) : Request

	/** The preferences: pinned places, the terminal, and every binding. */
	data object Preferences : Request

	/** The preferences, at their page of key bindings. */
	data object KeyBindings : Request
}

/** Where a window starts: in a folder, in a place, or on the page of the system's drives. */
@Immutable
sealed interface Start {
	data class Folder(
		val path: Path,
	) : Start

	data class Place(
		val placed: Placed,
	) : Start

	/** The System page, whether or not it is among the sidebar's places. */
	data object System : Start
}

/** Something a command is run for, and waited on: what is shown as running, and can be stopped. */
@Immutable
sealed interface Task {
	val label: String

	/** An action's commands. */
	data class Running(
		val job: Job,
	) : Task {
		override val label: String get() = job.label
	}

	/** A place being listed. */
	data class Listing(
		val placed: prism.browser.Placed,
	) : Task {
		override val label: String get() = "Loading ${placed.title}"
	}
}

/** A task under way: what it has written so far (its last lines), and whether the user has asked for it to stop. */
@Immutable
data class Work(
	val task: Task,
	val said: List<String> = emptyList(),
	val stopping: Boolean = false,
) {
	/** This, having written [line]: the latest lines are kept, which are what a person waiting reads. */
	fun saying(line: String): Work = copy(said = (said + line).takeLast(KEPT))

	companion object {
		const val KEPT = 200
	}
}

/** What follows a job's commands, once they have all worked. */
@Immutable
sealed interface After {
	data object None : After

	/** What is shown read again, the cursor on the entry named [choose] where it is said. */
	data class Reread(
		val choose: String?,
	) : After

	/** [paths] opened with their applications: what a place's entries, brought out, were brought out for. */
	data class Open(
		val paths: List<Path>,
	) : After
}

/** Commands to run for an action called [label], and what follows them. */
@Immutable
data class Job(
	val label: String,
	val invocations: List<Invocation>,
	val after: After = After.None,
	/** A folder made for these commands to work in (their `{temp}`): removed, with all in it, when they end. */
	val scratch: Path? = null,
	/** What the commands made, to be brought to where it is wanted once they have all worked. */
	val bring: Delivery? = null,
)

/**
 * What a command put in [from], brought into [into]: each of [entries] (what its command called it, which is where it
 * was put under [from], and its own name, which is what it is called in [into]); or, with no entries, everything in
 * [from]. Nothing already in [into] is written over.
 */
@Immutable
data class Delivery(
	val from: Path,
	val into: Path,
	val entries: List<Pair<String, String>>?,
)

/**
 * An action begun for [subject] and not yet done: [answers] are what has been answered of what it asks. It is done
 * once every question is answered and, where it asks to be confirmed, the user has said yes ([confirmed]). [after],
 * where it is said, follows its command in place of what the action says does.
 */
@Immutable
data class Pending(
	val action: UserAction,
	val subject: Subject,
	val answers: Map<String, String> = emptyMap(),
	val confirmed: Boolean = false,
	val after: After? = null,
)

/**
 * Entries of a place on their way out of it: the action that brings them out, begun, with only where to still to
 * answer ([question]). Dragged, a drop on a folder answers it; copied, a paste into one does.
 */
@Immutable
data class Carried(
	val pending: Pending,
	val question: String,
) {
	/** These, brought out into [folder]. */
	fun into(folder: Path): Pending =
		pending.copy(answers = pending.answers + (question to folder.toString()))
}

/**
 * Entries of a place as a drag carries them: which place (its type by id, and what it was opened with), the folder of
 * it they were dragged from, and what each is called. It names no action and holds no command: whatever window takes
 * the drop brings them out by its own actions, read from the same preferences, so this is all another process needs
 * to be told, and nothing in it is run.
 */
data class EntriesOut(
	val place: String,
	val values: Map<String, String>,
	val within: List<String>,
	val entries: List<Entry>,
) : java.io.Serializable {
	data class Entry(
		val key: String,
		val name: String,
		val folder: Boolean,
	) : java.io.Serializable

	companion object {
		private const val serialVersionUID = 1L

		/** [carried], described; null where it is not of entries of a place. */
		fun of(carried: Carried): EntriesOut? {
			val subject = carried.pending.subject
			val where = subject.where as? Where.Place ?: return null
			val entries =
				subject.things.map {
					Entry(it.key ?: return null, it.name, it.kind == ThingKind.Folder)
				}

			// Plain collections, which are what is written when this goes to another process.
			return EntriesOut(
				where.placed.type.id,
				HashMap(where.placed.values),
				ArrayList(where.within),
				ArrayList(entries),
			)
		}
	}
}

/**
 * Why a change is made: asked for anew, or to undo or redo what the user [did][Undo.done]. It says where what takes the
 * change back is kept, and what a change that fails is called.
 */
sealed interface Doing {
	data object New : Doing

	data class Undo(
		val done: Change,
	) : Doing

	data class Redo(
		val done: Change,
	) : Doing
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

	/** An empty file made at [path]. */
	data class MakeFile(
		val path: Path,
	) : Change

	/** The empty file at [path] removed: what undoes making it. Refused if anything has been written into it. */
	data class RemoveFile(
		val path: Path,
	) : Change

	/** [sources] copied into [into], a name taken by something there given a free one ("a (copy).txt"). */
	data class Copy(
		val sources: List<Path>,
		val into: Path,
	) : Change

	/** A link to each of [sources] made in [into], under the source's name, or a free one ("Link to a.txt"). */
	data class Link(
		val sources: List<Path>,
		val into: Path,
	) : Change

	/** [sources] moved into [into], a name taken by something there given a free one. */
	data class Move(
		val sources: List<Path>,
		val into: Path,
	) : Change

	/** Each of [moves] moved exactly where it says, never over something there: what undoes a move. */
	data class Relocate(
		val moves: List<Moved>,
	) : Change
}

/** An entry moved, from where to where. */
@Immutable
data class Moved(
	val from: Path,
	val to: Path,
)

/** An entry of a trash: where it was, the name the trash keeps it under, and the trash ([bin], its folder). */
@Immutable
data class Trashed(
	val original: Path,
	val name: String,
	val bin: Path,
)

/** A file the trash could not take, on a drive with none: and why there is none. */
@Immutable
data class Untrashable(
	val path: Path,
	val why: String,
)

/** What is deleted for good. */
@Immutable
sealed interface Erasing {
	/** Files where they are. */
	data class Files(
		val paths: List<Path>,
	) : Erasing

	/** Entries of the trash. */
	data class FromTrash(
		val entries: List<Trashed>,
	) : Erasing

	/** Everything in every trash. */
	data object Everything : Erasing
}

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

	/**
	 * Moved to the trash as far as there were trashes to move to: [undo] puts back what was (none, where nothing was);
	 * [nowhere] were on drives with no trash, and were left as they are; and where a move failed for another reason,
	 * [failed] says why, and what came after it was not tried.
	 */
	data class Trashed(
		val undo: Change.PutBack,
		val nowhere: List<Untrashable>,
		val failed: String? = null,
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

	/** [erasing] deleted for good, and what came of it sent back as [Answer.Erased]. */
	data class Erase(
		val erasing: Erasing,
	) : Asking

	/** Every trash's entries read, and sent back as [Answer.TrashRead]. */
	data object ReadTrash : Asking

	/** The machine's storage read, and sent back as [Answer.StorageRead]. */
	data object ReadStorage : Asking

	/**
	 * [query] searched for, as [text] was read, and what it found sent back as [Answer.Searched]. A search for the
	 * search's window is overtaken by the next one asked for: only the latest is answered.
	 */
	data class Search(
		val text: String,
		val query: Query,
		val purpose: Purpose,
		/** The place searched, in the index's stead: only what it holds is found. */
		val within: PlaceTree? = null,
	) : Asking

	/** When the entries [names] of [folder] were made, read, and sent back as [Answer.Born]. */
	data class Born(
		val folder: Path,
		val names: List<String>,
	) : Asking

	/** The results at [ranks] among [matches] read on disk, and what was read sent back as [Answer.Stated]. */
	data class Stat(
		val matches: Matches,
		val ranks: Ranks,
	) : Asking

	/** [matches] ordered by [column] off the picker's thread, sent back as [Answer.Ordered]. */
	data class Order(
		val matches: Matches,
		val column: ColumnId,
	) : Asking

	/** [paths] opened with their applications, sent back as [Answer.Opened]. */
	data class Open(
		val paths: List<Path>,
	) : Asking

	/** A terminal started in [folder], sent back as [Answer.Started]. */
	data class Terminal(
		val folder: Path,
	) : Asking

	/** What was copied read, to be pasted into [into] (the place shown, where null), sent back as [Answer.Clipped]. */
	data class ReadClip(
		val into: Path?,
	) : Asking

	/** [placed] listed by its command, sent back as [Answer.Listed]. */
	data class ListPlace(
		val placed: Placed,
	) : Asking

	/** [job]'s commands run, each waited for, and what came of them sent back as [Answer.Ran]. */
	data class Run(
		val job: Job,
	) : Asking

	/** [job]'s commands started detached, or in a terminal, and forgotten; a failure to start is sent back. */
	data class Launch(
		val job: Job,
		val terminal: Boolean,
	) : Asking

	/** A folder made for [pending]'s command to work in, sent back as [Answer.ScratchMade]. */
	data class Scratch(
		val pending: Pending,
	) : Asking

	/** A folder made that nothing else uses, for [carried], sent back as [Answer.TempMade]. */
	data class Temp(
		val carried: Carried,
	) : Asking

	/** [task], under way, stopped, with whatever its command started. */
	data class Stop(
		val task: Task,
	) : Effect

	/** [carried] kept as what was copied last, for a paste in any window; null lets go of what was. */
	data class Carry(
		val carried: Carried?,
	) : Effect

	/** [clip] put on the clipboard. */
	data class Clip(
		val clip: FileClip,
	) : Effect

	/** Quick access arranged as [quick]: a preference, so every window is given it. */
	data class Arrange(
		val quick: QuickAccess,
	) : Effect

	/** [request] made of the application around the browser. */
	data class Ask(
		val request: Request,
	) : Effect

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
	val state: BrowserState,
	val effects: List<Effect> = emptyList(),
)
