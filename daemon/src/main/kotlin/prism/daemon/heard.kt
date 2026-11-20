package prism.daemon

import prism.index.Act
import prism.index.ActivityLog
import prism.macos.FsEvent
import prism.macos.FsFlag
import prism.watcher.Done
import prism.watcher.Observed

/*
 * What a feed hears, noted in the index monitor's log ([ActivityLog]) as it is heard, before anything is made of it:
 * so the log has what the disk was heard doing, whatever the index then made of it.
 */

/** [batch] of the eBPF probe's changes noted in [log]: each by the process that made it, of the entry where it is now. */
internal fun ActivityLog.heardObserved(batch: List<Observed>) =
	heard {
		for (observed in batch) {
			val name = observed.name.bytes()

			when (val what = observed.what) {
				is Done.Made -> inFolder(observed.tgid, actOf(what.made), observed.folder, name)
				is Done.Removed -> inFolder(observed.tgid, actOf(what.removed), observed.folder, name)
				is Done.Moved ->
					inFolder(
						observed.tgid,
						if (what.swapped)
							Act.Swapped
						else
							Act.Moved,
						what.to,
						name,
					)
				// Where it went is not known: the folder it left is all there is to say.
				is Done.Left ->
					inFolder(
						observed.tgid,
						if (what.swapped)
							Act.Swapped
						else
							Act.Moved,
						observed.folder,
						null,
					)
			}
		}
	}

private fun actOf(made: Done.Making): Act =
	when (made) {
		Done.Making.File -> Act.FileMade
		Done.Making.Directory -> Act.FolderMade
		Done.Making.Symlink -> Act.LinkMade
		Done.Making.Node -> Act.NodeMade
		Done.Making.Link -> Act.Linked
	}

private fun actOf(removed: Done.Removing): Act =
	when (removed) {
		Done.Removing.File -> Act.FileRemoved
		Done.Removing.Directory -> Act.FolderRemoved
	}

/**
 * [batch] of FSEvents' events noted in [log], those of an entry made, removed or renamed: by nobody it can say, since
 * FSEvents says no process. One event can say several of these at once (FSEvents gathers what happened to a path in a
 * moment into one), and each is noted.
 */
internal fun ActivityLog.heardFsEvents(batch: List<FsEvent>) =
	heard {
		for (event in batch) {
			val path = event.path.toString()

			for (act in actsOf(event))
				atPath(null, act, path)
		}
	}

/** What an event of FSEvents' says was done to its path, of what the index monitor notes. */
private fun actsOf(event: FsEvent): List<Act> {
	val flags = event.flags

	return listOfNotNull(
		when {
			FsFlag.Created !in flags -> null
			FsFlag.IsDir in flags -> Act.FolderMade
			FsFlag.IsSymlink in flags -> Act.LinkMade
			else -> Act.FileMade
		},
		when {
			FsFlag.Removed !in flags -> null
			FsFlag.IsDir in flags -> Act.FolderRemoved
			else -> Act.FileRemoved
		},
		Act.Renamed.takeIf { FsFlag.Renamed in flags },
	)
}
