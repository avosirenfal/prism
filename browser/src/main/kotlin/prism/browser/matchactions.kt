package prism.browser

import prism.preferences.Role

/*
 * What can be done to something the search's window lists, from its menu (the right arrow on it, or a right click):
 * the first is always what Enter does to it, and the rest are what a file manager does to a file found. Which there
 * are depends only on what was found and on the window ([actionsOf]): an explorer does everything, a picker, which is
 * another application's window for a moment, only goes to things and copies what they are called.
 */

/** Something done to what the search's window lists, as its menu says it. */
enum class MatchAction(
	val label: String,
) {
	/** A picker's Enter: to the folder a file is in, with it selected; into a folder. */
	GoTo("Go to"),

	/** An explorer's Enter on a folder: into it. */
	Enter("Open"),

	/** To the folder it is in, with it selected: an explorer's Enter on a file. */
	Reveal("Show in folder"),

	/** A file opened with the application that opens its type. */
	OpenFile("Open"),
	OpenWith("Open with…"),
	CopyPath("Copy path"),
	CopyName("Copy name"),

	/** A terminal in the folder itself, or in the folder a file is in. */
	Terminal("Open terminal here"),
	Properties("Properties"),
	Trash("Move to trash"),

	/** One of prism's own things done: all there is to do with one. */
	Own("Open"),

	/** An alias's name put where it was being typed: all there is to do with one. */
	Use("Use alias"),
}

/**
 * What can be done to [listed] in a window that is [role], the first being what Enter does to it. [terminal] says
 * whether the terminal chosen can be opened in a folder at all.
 *
 * A match of a search in a place (an archive's entries) is only gone to: it has no path on disk to copy or open.
 */
fun actionsOf(listed: Search.Listed, role: Role, terminal: Boolean): List<MatchAction> =
	when (listed) {
		is Search.Listed.Prisms -> listOf(MatchAction.Own)
		is Search.Listed.Named -> listOf(MatchAction.Use)
		is Search.Listed.Place -> listOf(MatchAction.GoTo, MatchAction.CopyPath)
		is Search.Listed.Match -> {
			val folder = listed.matches.isFolder(listed.rank)
			val onDisk = listed.matches.tree == null

			when (role) {
				Role.Picker ->
					listOfNotNull(
						MatchAction.GoTo,
						MatchAction.Reveal.takeIf { folder && onDisk },
						MatchAction.CopyPath.takeIf { onDisk },
						MatchAction.CopyName,
					)
				Role.Explorer ->
					listOfNotNull(
						if (folder)
							MatchAction.Enter
						else
							MatchAction.Reveal,
						(
							if (folder)
								MatchAction.Reveal
							else
								MatchAction.OpenFile
						).takeIf { onDisk },
						MatchAction.OpenWith.takeIf { !folder && onDisk },
						MatchAction.CopyPath.takeIf { onDisk },
						MatchAction.CopyName,
						MatchAction.Terminal.takeIf { terminal && onDisk },
						MatchAction.Properties.takeIf { onDisk },
						MatchAction.Trash.takeIf { onDisk },
					)
			}
		}
	}
