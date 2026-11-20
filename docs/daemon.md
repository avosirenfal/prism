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
`vfs_symlink`, `vfs_link`, `vfs_mknod`, `vfs_rename`, and `do_filp_open` (`do_file_open` since Linux 7.0) for files made
by `open(O_CREAT)`. The kernel changes these functions' parameters from one version to the next, so each is declared
in every shape it has had since 6.17, and the one that is the running kernel's is attached (`prism probe check` says
which, or what a kernel newer than any declared differs on). Hooking the
VFS rather than system calls sees every way of making a change (io_uring's, nfsd's, overlayfs's); hooking the exit
sends only changes the kernel made. Each change is sent through a ring buffer as: the device of the folder's file
system, the folder's inode (and for a move, the inode of the folder it went to), the entry's name (after a move, its new
name), its inode and mode, the process, and what happened.

`prism.watcher.Watcher` loads it, attaches it, and reads the ring on a thread of its own, handing each batch it reads
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

`prism.daemon.LiveIndex` is an index open and kept current: an `IndexStore` (written to its file, or in memory
alone) and the `Indexer` that reads the disk for it. It needs no watcher; what it is told, it makes:

- `LiveIndex.open(file, roots)` opens at once, with what the file holds (or nothing), and reads its roots behind:
  those the file does not hold, and (unless told otherwise) those it does, for what changed while nothing watched.
  Roots the file holds that are no longer asked for are let go. `opened` completes once the roots are read.
- `rescan(paths)` reads each path again, a file or a folder, with everything under it; `index(paths)` adds roots;
  `rescanRoots()` reads every root again. Each completes once what it read is made.
- `changed(changes)` makes changes the watcher saw.
- `search(query, context)` searches, as the next section says.
- `write()` writes the index to its file, whole, where the file is behind it; `close()` stops the scans and writes.
  A daemon asks for a write once an hour (`DaemonSettings.write`), and once the first index there is has been read.

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

## File systems that come and go

A file system that is unmounted is no longer on the disk, so its entries leave the index, and no search finds them.
They used to be thrown away, and the whole file system read again from nothing when it came back. They are kept
now, and put back the moment it does:

1. **Which file system is which.** A mount point and a device number say nothing lasting: a drive plugged in again
   is another device, perhaps mounted somewhere else. `Volumes` (`index/volumes.kt`) gives each mount a `VolumeId`
   from something the file system carries itself:

   | the file system | is known by |
   |---|---|
   | on a block device (ext4, xfs, btrfs, FAT, NTFS, anything whose source is under `/dev`) | the UUID written in it, as udev read it (`ID_FS_UUID` in `/run/udev/data`), or as `/dev/disk/by-uuid` names it; and the folder of it that is mounted, so each btrfs subvolume is its own |
   | ZFS | the dataset's name and its `f_fsid`, which ZFS makes from an id written in the pool |
   | a share on another machine (NFS, SMB, sshfs and the like) | its type, what was mounted (`server:/export`) and the folder of it |
   | anything else (memory, overlays, the kernel's own) | nothing: it is never taken for one seen before |

2. **Which is mounted where** is noted as it is seen (every two seconds the daemon looks at `/proc/self/mountinfo`),
   since a file system that has gone cannot be asked what it was.
3. **Going.** When the entries under a mount point are about to leave the index and its file system is no longer
   mounted there, the tree is kept first (`Away`, `index/away.kt`): as a file in the index's own format in a folder
   beside the index's file (`index.prism.away/`), each entry with when it was first seen. That is done wherever the
   entries would leave, whatever takes them out: the next look at the mounts, a scan of the root above, or the
   watcher seeing the mount point itself removed, which a desktop does the instant it unmounts a drive.
4. **Coming back.** A file system mounted whose tree is kept is put back where it is mounted now, the same place or
   another, as it was, so it is searched at once; then it is read again, for what changed while it was away. What
   was there before is as old as it was (`new:` and the order of results know when each entry was first seen), and
   what is new is new.
5. **New.** A file system mounted under what the index holds, and never seen, is read at once.
6. **Letting go.** A tree whose file system has not been seen for 30 days is deleted; the daemon looks once a day.

A file system that comes and goes with something outside the machine (`Volume.portable`: on a drive that says it
is removable or is on USB, or on another machine) is not read only because it was mounted, and a scan of a folder
above it does not go into it: reading someone's whole NAS because it was mounted is nobody's wish. One asked for
outright is read, and kept, like any other: `prism daemon / /run/media/you/Backup` indexes both.

`prism status` says what is kept (`unmounted file systems: 1 kept until mounted again (/mnt/data, 81,204 entries)`).
`AwayTest` has each of the above with folders standing in for file systems; `VolumesTest` reads the identities
from a made-up `/sys`, `/dev` and `/run/udev`.

## What is left out, and what is hidden

What the index leaves out is said by rules (`Exclusions`, `index/exclusions.kt`). Take three:

| rule | what it names |
|---|---|
| `/nix` | the folder at that path |
| `build/` | every folder named `build`, wherever it is |
| `ext:{o,pyc}` | every file with one of those extensions |

**What a rule names.** A folder by its path (`/nix`, `~/Games`), or entries by a query in the search language
(docs/search.md). A rule covers every entry it names and everything under one: nothing under a folder left out is
held, at any depth. A rule of folder terms alone names the folders, as the same words typed as a search find them
(docs/search.md, "Folders, and what is in them"): `build/` as a rule is each folder named build and all it holds (a
file named `build` is no folder, and is kept). A rule tells upper case from lower, as the disk does, unless a term of it says otherwise.
A query a rule may be is one that can be decided of an entry by where it is, what it is called and what kind of thing
it is, as the disk is read: `recent:`, `new:`, `mount:`, an alias (`raws@`) and a named folder (`~work`) are refused,
with why.

**What a rule does** (`Exclusion`), one of two things:

- **Ignored**: not read, not held, not watched. A search that names a path into it has it read for that search
  alone (the next section).
- **Hidden**: held as everything else is, and left out of what every search finds (the search window, the launcher,
  a picker's search, `prism search`, the Recents page's new files) unless a path term of the search names the entry
  the rule named, or something under it. With `/games` hidden: `foo` finds nothing under it; `/games/ foo` finds what
  is under it; `/games/whatever/ foo` only what is under that. A path above it (`/ foo`) does not show it, and
  one hidden inside another is hidden still until it is named itself. Browsing a hidden folder is as ever: it is
  searches that leave it out.

**The defaults, and the user's own.** Prism's own rules (`Exclusions.DEFAULTS`) are trees that can hold more entries
than everything a person made and that nobody picks a file from (version control, package managers' downloads, build
output and caches, browsers' caches, snapshots, the system's own stores: `/nix` whole, `/boot`, `/tmp`, ...). Each can
be switched off, or made hidden instead; the user's own are added beside them. The preferences keep only the
differences from the defaults (`"excluding"`: which are off, which do something else, and the user's own with what
each does), so a default added later is the user's too. The "Indexing" page of the preferences sets them
(docs/explorer.md).

**Places included under one left out** (`Included`, `index/included.kt`). A Mac keeps its own applications under
`/System`, which is ignored: `/System/Applications` (Safari, Mail), `/System/Applications/Utilities`,
`/System/Library/CoreServices/Applications` and `/System/Library/CoreServices/Finder.app`. These four are read all
the same, as roots of their own: a place asked for outright is read whatever the rules say, so a scan goes into them
and into nothing else of `/System`. They are hidden, as a hidden rule is: held, so the launcher lists what they hold,
and left out of what a search finds until a path term names them (`/System/Applications/ safari`). The daemon adds
each as a root where a root it was given holds it (`/` does); other platforms include nothing.

**How they are applied.** A rule that is a folder's path, or folders by the names their paths end with (`build/`,
`.yarn/cache/`: every default is one of these), is one a scan never goes into (`IgnoreRules.directories`), which is
what makes leaving out `node_modules` cheap. Any other query is looked for in what a scan read, by the search engine
itself, and what it finds is taken out with all under it before the index holds it (`IgnoreRules.sought`,
`Indexer.treeAt`); the same is done to each entry the watcher says changed, so a scan and a change agree. A hidden
rule is looked for in each block of the index once, the first time a search walks it, and what it found there is kept
for as long as the block is; a search then skips those entries' whole ranges, so hidden entries cost a search nothing
to pass over (`Hiding`). Scanning `/usr` here (103,000 entries): 53 ms with no rules, 33 ms with four folder rules
(less is read), 60 ms with four query rules. A search of it: 2.7 ms with nothing hidden, 3.8 ms with three hidden
rules (25 ms the first time, when what they hide is worked out).

**Changed while the daemon runs** (`LiveIndex.ruled`), from the preferences as a settings window applies them, with
no file watched: where all that differs is folders by their paths, one now ignored is taken out of the index at once
and one no longer ignored is read; where a query or a folder name differs, which may be anywhere, the roots are read
again. Making something hidden, or showing it again, reads nothing. `prism status` says how many rules there are
(`rules: 103 not indexed, 1 hidden from searches`). `ExclusionsTest` and `RuledTest` have all of this.

## Searching places the rules leave out

The index leaves out `/nix`, `node_modules`, `~/.cache` and the rest, so a search does not find what is in them.
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
future that completes once they are, for the search to be run again. `prism daemon --find '~/.gradle/ ext:jar'`
shows it: the place is read for that search (in this container, in under a second), and found in.

## The daemon

`prism.daemon.Daemon` runs a feed of changes (the watcher) and a live index together:

1. **The feed starts first.** From that moment every change is seen, and waits in a queue.
2. The live index opens, and reads its roots in the background.
3. The queue is emptied, on a thread of its own, into the live index: what was seen before the index could make it
   is made now, and kept beside the scans in flight to be made again when they land.
4. Every few seconds, a tick writes what was made, lets go of places read for searches no longer asked for, and looks
   at the watcher: where it lost changes (its ring was full), the roots are read again, since which changes were lost
   cannot be known.

Without a watcher (the kernel would not load it, or there was no build of it), the daemon runs all the same, and reads
its roots again every half hour. `prism daemon --foreground` runs one in place, saying which it is doing, and
`prism status` asks one that is running.

### What the daemon does on its own

Each of the three things above is the user's to turn off (`Upkeep`, from the preferences' `"indexing"`), and each is
changed as the daemon runs (`Daemon.upkeep`), on the thread that ticks, so that the feed is started and stopped by one
thread alone:

| switch | on | off |
|---|---|---|
| rescan on startup | the roots read as the index opens (`OnOpen.ReadAll`) | none read (`OnOpen.ReadNone`): the index is as its file holds it, or empty, until it is rebuilt |
| live monitoring | the feed started | the feed not started, or stopped; started again, it hears what happens from then on |
| periodic rescan | the roots read again every half hour while there is no feed | not read again |

What is mounted is looked at while either of the last two is on. With all three off, nothing changes the index but a
rebuild (`LiveIndex.rescanRoots`, which reads the roots asked for whether or not the index holds them yet), and every
search uses it as it is. How the index is kept now is said as a `Keeping` (`index/keeping.kt`): live monitoring, and
by what; periodic rescan, when the next is due, and why live monitoring is not running; or neither. `prism status`
says it (`changes: live monitoring via eBPF (probe pinned at ...)`), and so does the preferences' Indexing page.

## The index monitor

A change the index seems to have missed is one of two things: the feed never heard it, or it heard it and the index
made nothing of it (a folder the index does not hold, or one the rules leave out). The index monitor tells them apart.
Every batch a feed hears is noted, before anything is made of it, in an `ActivityLog` (`index/activity.kt`): the eBPF
probe's by the process that made each change (`tgid`), FSEvents' by nobody it can say, since FSEvents says no process.

Say `touch` (process 5123) makes seven files in three seconds, and a build makes 1,523 in two:

1. The first of `touch`'s changes begins a **burst**: process 5123, from now. Each of its changes in the five seconds
   after that is one more of the burst's; the burst's process name is read once, as it begins (`/proc/5123/comm`),
   since a process that runs for a moment is gone by the time anyone looks.
2. The build's first 200 changes are kept in its burst, and the rest only counted: the burst says 200 kept of 1,523
   seen. One busy program cannot push everything else out of the log.
3. A burst is kept for a minute from when it began, and its changes with it.

The changes are records in memory outside the heap (`HeardRecords`, `index/heardrecords.kt`): a fixed header (when,
the folder's device and inode, the burst, what was done) and the entry's name (or FSEvents' whole path) after it.
They are written into chunks of 64 KiB in the order they are heard, and since they are let go in that order too, a
chunk is let go whole once its newest record is too old; chunks let go are kept to be written in again. The log never
takes more than 16 MiB: where every chunk is full, the oldest is let go however new, and its bursts count fewer kept.
So a log of a hundred thousand changes is a few megabytes of bytes, with no object a change for the collector to walk.
A folder is said by its device and inode as the kernel names it, and placed as a path only when it is shown (by the
index's `foldersAt`), so noting a change looks nothing up.

The log is written by the feed's thread and read by the window's, each under its lock (`Guarded`). Its bursts, and a
burst's 200 changes at most, are read under it as they are shown. What would take long (finding a path among all the
changes) is done in a **copy**: the log's memory copied once into memory of the copy's own, with its bursts as they
were, which is then that moment's for good and which nothing waits on. Pausing the monitor holds such a copy.
Each event is one of ten kinds of change (`Act`), which are each one of three (`ActKind`: created, deleted, moved),
and a search can be narrowed to any of the three. `ActivityTest` has each of the above.

Nothing that fails in it stops it: each failure is reported and gone on from, by reading the disk again
(docs/running.md, "What happens when something goes wrong", has each case). The daemon the command runs is also the
session's prism: every prism window opens in it, and searches this index through a `Finder` over `LiveIndex.search`
(`LiveFinder`), which gives its results ranked, its own and those of places read for a search alone together, and
writes the usage file as things are picked.

## Not yet

- A ring for each reader of a watcher that root loaded and left pinned for an unprivileged daemon to read
  (docs/running.md): there is one, so one daemon.
- Telling a running daemon to read places again from outside it: the API is `LiveIndex.rescan`; a command asked
  through the socket other prisms ask it by (docs/running.md) is where it belongs.
- Dropping changes in left-out folders in the kernel, before they reach the ring: a bounded loop in the DSL, to walk a
  few parents up from a change.
- The watcher has not been loaded on a real machine yet; this container refuses it.
- A window telling the index what it saw: a window watches the folder it shows and reads it again as it changes,
  and the index finds out only at its next reading.
