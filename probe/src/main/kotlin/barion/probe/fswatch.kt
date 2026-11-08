package barion.probe

import barion.ebpf.Body
import barion.ebpf.Counters
import barion.ebpf.Expr
import barion.ebpf.Ptr
import barion.ebpf.Record
import barion.ebpf.RingBuffer
import barion.ebpf.S32
import barion.ebpf.U32
import barion.ebpf.U64
import barion.ebpf.convertedTo
import barion.ebpf.declared
import barion.ebpf.get
import barion.ebpf.hasBits
import barion.ebpf.isErrorOrNull
import barion.ebpf.ne
import barion.ebpf.of
import barion.ebpf.shr

/*
 * The watcher: a hook on the way out of each VFS function that adds an entry to a directory or takes one away, which
 * sends what changed to the host once the change has been made. An exit hook sees the function's result, so a change
 * the kernel refused is never sent, and the host never has to wonder whether one happened.
 *
 * What is sent is what the host needs to find the change in its index and nothing more: the device, the directory
 * the entry is in (or was in, and moved to), the entry's name and inode. The host keeps the index by inode, so it
 * knows the directory by that number, and the name is all it needs to look the entry up. A change in a directory the
 * index does not hold (one it ignores, or has not reached yet) is one the host drops or scans for itself.
 */

/** What happened to an entry of a directory. */
enum class Change {
	FileMade,
	DirectoryMade,
	SymlinkMade,

	/** A FIFO, a socket or a device node. */
	NodeMade,

	/** A second name for a file that had one. */
	Linked,
	FileRemoved,
	DirectoryRemoved,

	/** Renamed, within one directory or into another. */
	Moved,

	/** Two entries swapped (`RENAME_EXCHANGE`): both directories are worth reading again. */
	Exchanged,
}

/** What the program counts rather than sends: the changes it could not send. */
enum class Lost {
	/**
	 * The host had not kept up and the ring had no room. The host cannot know which changes these were, so it reads
	 * again whatever directories it holds that may have changed; the count says that it has to.
	 */
	RingFull,
}

/** One change, as the host reads it. Ordered widest first, so the layout has no gaps for the C to pad. */
object FsChange : Record<FsChange>("fs_change") {
	/** The entry's inode; 0 for an entry that is gone, which the host finds by its name. */
	val ino by u64()

	/** The directory the entry is in, or was in before a move. */
	val parentIno by u64()

	/** Where a move took the entry; 0 for anything that is not a move. */
	val toParentIno by u64()
	val atNs by u64()

	/** The device, in the kernel's own encoding. */
	val dev by u32()
	val change by u32()

	/** The entry's mode (its kind and permissions); 0 for an entry that is gone. */
	val mode by u32()

	/** The process that made the change. */
	val tgid by u32()

	/** The entry's name, as the directory holds it: bytes, which need not be UTF-8. */
	val name by chars(NAME_BYTES)

	/** A file's name is at most 255 bytes on Linux, and the field holds its NUL as well. */
	const val NAME_BYTES = 256
}

/** The watcher's maps, as the host reaches them. */
class FsWatchMaps(
	val changes: RingBuffer<FsChange>,
	val lost: Counters<Lost>,
)

/** `RENAME_EXCHANGE`: the two entries swap. */
private const val EXCHANGE = 1L shl 1

/** The watcher. */
val FsWatch =
	declared("fswatch") {
		// 4 MiB: about 13,000 changes, enough for a burst (a large `git checkout`, an `npm install`) between reads.
		val changes by ringBuffer(FsChange, bytes = 1 shl 22)
		val lost by counters(Lost.entries)
		val maps = FsWatchMaps(changes, lost)

		fexit(VfsMkdir) {
			val made = VfsMkdir.returns.value()

			stopIf(made.isErrorOrNull())
			sent(maps, Change.DirectoryMade, VfsMkdir.dir.value(), made, present = true)
		}

		fexit(VfsRmdir) {
			stopIf(VfsRmdir.returns.value() ne (S32 of 0))
			sent(
				maps,
				Change.DirectoryRemoved,
				VfsRmdir.dir.value(),
				VfsRmdir.dentry.value(),
				present = false,
			)
		}

		fexit(VfsUnlink) {
			stopIf(VfsUnlink.returns.value() ne (S32 of 0))
			sent(
				maps,
				Change.FileRemoved,
				VfsUnlink.dir.value(),
				VfsUnlink.dentry.value(),
				present = false,
			)
		}

		fexit(VfsCreate) {
			stopIf(VfsCreate.returns.value() ne (S32 of 0))
			sent(
				maps,
				Change.FileMade,
				VfsCreate.dir.value(),
				VfsCreate.dentry.value(),
				present = true,
			)
		}

		fexit(VfsSymlink) {
			stopIf(VfsSymlink.returns.value() ne (S32 of 0))
			sent(
				maps,
				Change.SymlinkMade,
				VfsSymlink.dir.value(),
				VfsSymlink.dentry.value(),
				present = true,
			)
		}

		fexit(VfsLink) {
			stopIf(VfsLink.returns.value() ne (S32 of 0))
			sent(maps, Change.Linked, VfsLink.dir.value(), VfsLink.made.value(), present = true)
		}

		fexit(VfsMknod) {
			stopIf(VfsMknod.returns.value() ne (S32 of 0))
			sent(
				maps,
				Change.NodeMade,
				VfsMknod.dir.value(),
				VfsMknod.dentry.value(),
				present = true,
			)
		}

		fexit(DoFilpOpen) {
			val opened = DoFilpOpen.returns.value()

			stopIf(opened.isErrorOrNull())
			stopUnless(opened[File.fMode] hasBits DoFilpOpen.CREATED)

			val entry by local(opened[File.fPath][Path.dentry])

			sent(
				maps,
				Change.FileMade,
				entry[Dentry.dParent][Dentry.dInode],
				entry,
				present = true,
			)
		}

		fexit(VfsRename) {
			stopIf(VfsRename.returns.value() ne (S32 of 0))

			val data = VfsRename.data.value()
			val from by local(data[RenameData.oldParent][Dentry.dInode])
			val to by local(data[RenameData.newParent][Dentry.dInode])
			// After the move, the dentry that moved carries its new name and its new parent.
			val moved by local(data[RenameData.oldDentry])

			branch(data[RenameData.flags] hasBits EXCHANGE, otherwise = {
				sent(maps, Change.Moved, from, moved, present = true, to = to)
			}) {
				sent(maps, Change.Exchanged, from, moved, present = true, to = to)
			}
		}

		maps
	}

/**
 * [change] to [entry] of [dir] sent to the host. [present] says whether the entry is there now, so that its inode
 * and mode are read; an entry that is gone is sent with neither, and the host finds it by its name. [to] is a
 * move's destination.
 */
private fun Body.sent(
	maps: FsWatchMaps,
	change: Change,
	dir: Expr<Ptr<Inode>>,
	entry: Expr<Ptr<Dentry>>,
	present: Boolean,
	to: Expr<Ptr<Inode>>? = null,
) {
	send(maps.changes, onFull = { count(maps.lost, Lost.RingFull) }) {
		it[FsChange.ino] =
			if (present)
				entry[Dentry.dInode][Inode.iIno]
			else
				U64 of 0
		it[FsChange.parentIno] = dir[Inode.iIno]
		it[FsChange.toParentIno] =
			to?.let { destination -> destination[Inode.iIno] } ?: (U64 of 0)
		it[FsChange.atNs] = now()
		it[FsChange.dev] = dir[Inode.iSb][SuperBlock.sDev]
		it[FsChange.change] = change.code()
		it[FsChange.mode] =
			if (present)
				entry[Dentry.dInode][Inode.iMode].convertedTo(U32)
			else
				U32 of 0
		it[FsChange.tgid] = (pidTgid() shr 32).convertedTo(U32)
		it[FsChange.name] = kernelText(entry[Dentry.dName][Qstr.name])
	}
}
