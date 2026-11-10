# The daemon: keeping the index current

The index is read once, and then kept as the disk is by being told what changes. This document says what tells it
(the watcher), what runs the two together (the daemon), why the watcher starts first and why that is enough for no
change to be missed, how an index can be told to read places again, and how a search can reach places the index
leaves out. The code is in three modules, each usable without the next: `index` reads, holds and searches; `watcher`
watches; `daemon` runs them together.

## An example, first

Say the daemon starts on a machine whose index was written yesterday, and someone saves `~/notes/plan.md` while the
daemon is still reading `/` again (as it does at every start, to find what changed while nothing was watching).

1. The watcher was started before anything was read, so it sees the save: `vfs_create` returned 0 in the folder
   whose device and inode are those of `~/notes`, for the name `plan.md`. That goes into the watcher's ring, then into
   the daemon's queue.
2. The daemon takes it from the queue and tells the live index: something named `plan.md` changed in the folder
   (device, inode). The index looks the folder up by those two numbers, finds `~/notes`, and reads `~/notes/plan.md`
   from the disk: a file, which it holds from then on.
3. Meanwhile the scan of `/` goes on. Perhaps it read `~/notes` before the save, perhaps after; it cannot say. When it
   lands, it replaces what the index held of `/` with what it read. But the change seen while it read was kept beside
   it, and is made again once its result is: `~/notes/plan.md` is read again, and is there whichever way the scan
   went.

The rest of this document is why each of those steps is right, and what the parts are.

## The watcher

The watcher (`probe/fswatch.kt`, declared in Kotlin and compiled to BPF by the build) hooks the exit of each VFS
function that adds an entry to a directory or takes one away: `vfs_mkdir`, `vfs_rmdir`, `vfs_unlink`, `vfs_create`,
`vfs_symlink`, `vfs_link`, `vfs_mknod`, `vfs_rename`, and `do_filp_open` for files made by `open(O_CREAT)`. Hooking the
VFS rather than system calls sees every way of making a change (io_uring's, nfsd's, overlayfs's); hooking the exit
sends only changes the kernel made. Each change is sent through a ring buffer as: the device of the folder's file
system, the folder's inode (and for a move, the inode of the folder it went to), the entry's name (after a move, its new
name), its inode and mode, the process, and what happened.

`barion.watcher.Watcher` loads it, attaches it, and reads the ring on a thread of its own, handing each batch it reads
to whoever started it. It reads on however long its listener takes, so the daemon's listener only queues. It counts
what it could not send because the ring was full (`lost`), and says why it stopped, if it does.

Loading it needs `CAP_BPF` and `CAP_PERFMON` (root will do). Where the kernel refuses (this project's development
container does, for every `fentry` and `fexit` program, whoever asks), `Watcher.start` says so, and nothing else
changes: the index never depends on a watcher.

## What a change becomes

A change names a folder by the kernel's device and the folder's inode, so the index keeps every entry's inode, and
every device's number as the kernel has it (the one mountinfo lists, which on btrfs is not the one a stat of a
subvolume gives; the index finds it by the mount each folder is under). `Index.foldersAt` finds the folder.

What the daemon tells the index is `Changed(folder, name)`:

| the watcher saw | the index is told | and reads |
|---|---|---|
| something made, removed or linked in a folder | that folder, and the name | that one entry, with everything under it (or that it is gone) |
| a move | the folder it left, with no name; the folder it went to, and the name | the folder left, reconciled; the entry where it went |
| a swap (`RENAME_EXCHANGE`) | both folders, with no name | both, reconciled |

A move gives only the entry's new name, so the folder it left is **reconciled**: listed once, and compared with what
the index holds of it by name, kind and inode. A name gone is absent; a name new, or with another kind or inode (as a
rename over it or a swap leaves it), is read again; the rest is left as it is. One level is all a reconcile reads, so
the one thing it cannot see is an entry removed and made again under the same name, of the same kind, given the same
inode (ext4 reuses one freed a moment before). The watcher sends those changes by name, which read the entry whole,
and what lost changes may have hidden is read again whole, never reconciled.

Every change is read from the disk as it is made, rather than taken from the watcher's word: so a change made twice
is made once, and two changes to one entry in either order leave it as the disk is.

## The live index

`barion.daemon.LiveIndex` is an index open and kept current: an `IndexStore` (written to its file, or in memory
alone) and the `Indexer` that reads the disk for it. It needs no watcher; what it is told, it makes:

- `LiveIndex.open(file, roots)` opens at once, with what the file holds (or nothing), and reads its roots behind:
  those the file does not hold, and (unless told otherwise) those it does, for what changed while nothing watched.
  Roots the file holds that are no longer asked for are let go. `opened` completes once the roots are read.
- `rescan(paths)` reads each path again, a file or a folder, with everything under it; `index(paths)` adds roots;
  `rescanRoots()` reads every root again. Each completes once what it read is made.
- `changed(changes)` makes changes the watcher saw.
- `search(query, context)` searches, as the next section says.
- `flush()` writes what was made to the file; `close()` stops the scans and flushes.

**Scans, and what happens during them.** A scan of a large place takes seconds, so it reads without holding the
index, and makes what it read once it has. Changes made meanwhile are made at once, as far as the index holds their
folders (during the first scan of an empty index, it holds none), and are also kept beside every scan in flight, to be
made again once that scan's result is made. That is what step 3 of the example was. Every change says what is so, so
making it again is harmless, and it is what the index ends with whichever way the scan read.

Changes are made one at a time, under one lock, each read from the disk as it is made, so a change read later is
never made before one read earlier. Only a scan reads without the lock, and the changes kept beside it are what make
that safe.

A test holds a scan after it has read and before what it read is made, makes a file and removes another, says so,
and lets the scan land: the index ends as the disk is. Without the changes being made again, the test fails, the
removed file back and the made one missing.

## Searching places the rules leave out

The index leaves out `/nix/store`, `node_modules`, `~/.cache` and the rest, so a search does not find what is in them.
But a search that names such a place by an absolute path (`/nix/store/` and a glob, `~/.cache/thumbnails/`,
`~/project/node_modules/`) is asking for it on purpose. So a live index reads it for that search:

1. `Query.places` says where each of the query's paths starting at the root, the home folder or an alias leads, up to
   its first glob: the folders the query looks only under.
2. Each that the index does not hold and that the rules leave out (by its own name or path, or a folder's above it)
   is read into an index of its own, with the rules that left it out set aside, so a `node_modules` within a
   `node_modules` is read too.
3. That index lives in memory alone: never written, never mixed with the index proper. It is kept current like the
   index (the watcher's changes are made in it too), and let go once no search has asked for it for ten minutes.

The search does not wait for the place to be read: it gives what the index holds, the places still being read, and a
future that completes once they are, for the search to be run again. `barion daemon --find '~/.gradle/ ext:jar'`
shows it: the place is read for that search (in this container, in under a second), and found in.

## The daemon

`barion.daemon.Daemon` runs a feed of changes (the watcher) and a live index together:

1. **The feed starts first.** From that moment every change is seen, and waits in a queue.
2. The live index opens, and reads its roots in the background.
3. The queue is emptied, on a thread of its own, into the live index: what was seen before the index could make it
   is made now, and kept beside the scans in flight to be made again when they land.
4. Every few seconds, a tick writes what was made, lets go of places read for searches no longer asked for, and looks
   at the watcher: where it lost changes (its ring was full), the roots are read again, since which changes were lost
   cannot be known.

Without a watcher (the kernel would not load it, or there was no build of it), the daemon runs all the same, and reads
its roots again every half hour. `barion daemon` runs one in the foreground, saying which it is doing.

## Not yet

- A system service that holds `CAP_BPF` and `CAP_PERFMON`, loads the watcher at boot and hands its ring to the user's
  daemon (`SCM_RIGHTS`), so the daemon runs unprivileged; or BPF tokens, where distributions enable them.
- Telling a running daemon to read places again from outside it: the API is `LiveIndex.rescan`; a D-Bus method for it
  belongs with the portal's.
- Dropping changes in left-out folders in the kernel, before they reach the ring: a bounded loop in the DSL, to walk a
  few parents up from a change.
- The watcher has not been loaded on a real machine yet; this container refuses it.
- Handing a live index to the pickers the daemon opens: the picker searches through a `Finder` (docs/picker.md,
  "Where a search comes from"), and the daemon's is a few lines over `LiveIndex.search`, which gives its results ranked
  (docs/search.md, "Which results come first"), its own and those of places read for a search alone ranked together;
  it comes with the portal, which is what opens them. With it, the usage file is written as things are
  picked, which `barion pick --index` only reads.
