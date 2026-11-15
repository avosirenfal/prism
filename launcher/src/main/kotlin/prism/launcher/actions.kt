package prism.launcher

import androidx.compose.runtime.Immutable
import prism.browser.ActionMenu
import prism.desktop.ApplicationAction

/*
 * What can be done to a result of the launcher's, from its menu (the right arrow on it, or a right click): the first
 * is always what Enter does to it, and the rest are what a launcher does besides: show it where it is, copy what it
 * is called, open it with something else. Which there are depends only on what the result is ([actionsOf]).
 */

/** Something done to a result of the launcher's, as its menu says it. */
@Immutable
sealed interface EntryAction {
	val label: String

	/** The launcher's own key that does the same to the result the arrows are on without the menu, where it has one. */
	val key: LauncherAction? get() = null

	/** What Enter does: an application started, a file opened, a folder shown, what prism does done. */
	data object Open : EntryAction {
		override val label: String get() = "Open"
		override val key: LauncherAction get() = LauncherAction.Open
	}

	/** Something else an application can be started to do: one of its desktop entry's actions. */
	data class Run(
		val action: ApplicationAction,
	) : EntryAction {
		override val label: String get() = action.name
	}

	/** Shown where it is, in an explorer's window: a file in its folder; an application's [entry], its desktop entry. */
	data class Reveal(
		val entry: Boolean,
	) : EntryAction {
		override val label: String
			get() =
				if (entry)
					"Show desktop entry in folder"
				else
					"Show in folder"
		override val key: LauncherAction get() = LauncherAction.Reveal
	}

	/** A window to choose what a file is opened with. */
	data object OpenWith : EntryAction {
		override val label: String get() = "Open with…"
	}

	/** Its path put on the clipboard: a file's; an application's [entry], its desktop entry's. */
	data class CopyPath(
		val entry: Boolean,
	) : EntryAction {
		override val label: String
			get() =
				if (entry)
					"Copy desktop entry path"
				else
					"Copy path"
		override val key: LauncherAction get() = LauncherAction.CopyPath
	}

	data object CopyName : EntryAction {
		override val label: String get() = "Copy name"
	}

	/** A terminal in the folder itself, or in the folder a file is in. */
	data object Terminal : EntryAction {
		override val label: String get() = "Open terminal here"
	}

	data object Properties : EntryAction {
		override val label: String get() = "Properties"
	}

	data object Trash : EntryAction {
		override val label: String get() = "Move to trash"
	}

	/** An application left out of the launcher from now on, until its settings say otherwise. */
	data object Hide : EntryAction {
		override val label: String get() = "Hide from launcher"
	}
}

/**
 * The menu of what can be done to a result: the [entry] it is of, and its [actions], with the one the arrows are on.
 * It holds its entry, so it is never a menu of nothing, whatever is listed by now.
 */
@Immutable
data class EntryMenu(
	val entry: Entry,
	val actions: ActionMenu<EntryAction>,
)

/** What can be done to [entry], the first being what Enter does to it. */
fun actionsOf(entry: Entry): List<EntryAction> =
	when (entry) {
		is Entry.App ->
			listOf(EntryAction.Open) +
				entry.application.actions.map(EntryAction::Run) +
				described(entry.application.source != null) +
				EntryAction.Hide
		is Entry.Action ->
			listOf(EntryAction.Open) +
				described(entry.application.source != null)
		is Entry.File ->
			listOfNotNull(
				EntryAction.Open,
				EntryAction.Reveal(entry = false),
				EntryAction.OpenWith.takeUnless { entry.isFolder },
				EntryAction.CopyPath(entry = false),
				EntryAction.CopyName,
				EntryAction.Terminal,
				EntryAction.Properties,
				EntryAction.Trash,
			)
		is Entry.Command, is Entry.Prism -> listOf(EntryAction.Open)
	}

/** What is done with an application's desktop entry, where it [has] one on disk. */
private fun described(has: Boolean): List<EntryAction> =
	if (has)
		listOf(EntryAction.Reveal(entry = true), EntryAction.CopyPath(entry = true))
	else
		emptyList()
