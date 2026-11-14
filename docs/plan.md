# The plan

prism is a file picker for Linux desktops on Wayland. It replaces the open and save dialogs that applications get
through the desktop portal with one that is fast, that can find any file on the machine by a few typed words, and
that is never out of date. This page says what prism is for, how it is put together, and how the parts that do not
exist yet are meant to work. docs/inprogress.md says where the work stands today.

## What it is for

When a Wayland application wants the user to choose a file, it does not draw a dialog itself. It asks the desktop
portal (`xdg-desktop-portal`) over D-Bus, and the portal hands the request to a backend, which draws the dialog and
answers with the chosen file. Today those backends are GNOME's and KDE's, and they are folder browsers: to open a
file, you walk to its folder.

prism is a backend of its own. Its dialog is a familiar one (the layout of a Windows file dialog, which most people
already know how to use), but behind it is an index of every file and folder on the machine, kept current as the
kernel changes them, so that a few typed words find a file wherever it is. The goals, in no particular order:

- **A normal file picker first.** A list of the folder's entries in columns, the places down the side, the path
  across the top, the name and the buttons at the bottom. Modern, declarative, built on immutable state, and fast.
  Features are added to it over time.
- **An index of everything.** Every file and folder on the machine, read once at the start by walking the tree (as
  fd-find does) or, where a file system offers something faster, by a backend for that file system.
- **Kept current by the kernel.** An eBPF program sees every entry made, removed or moved, as it happens, so the
  index does not go stale between scans.
- **Not authoritative.** The index is a cache of the file system, not a replacement for it. Whenever what the picker
  shows disagrees with what is on disk (a folder the index has not seen, an entry it does not know, one it holds that
  is gone), prism reads that one folder again and puts the index right.
- **Idempotent.** Every change to the index can be applied twice with the same result, so a change seen twice (by the
  watcher and by a rescan) is harmless, and recovering from anything is reading the folder again.
- **Leaving out what nobody picks from.** `.git`, `node_modules`, `.cache`, `.zfs` and their like are skipped by the
  scan and by the watcher alike. The list is configurable.
- **A search language.** Something short to type that says what is wanted: named aliases for places, and a search
  under one (`@work report pdf`).
- **Performance everywhere.** The scan, the index's file, the watcher and the view are each written to be fast.
- **Linux only.** prism uses Linux's own interfaces (getdents64, statx, eBPF, the portal) directly.

## How it is put together

prism is a Kotlin program on the JVM, built with Gradle the way kotlin-engine-test is: convention plugins in an
included `build-logic`, one version catalog, the house ktlint rules, JDK 25 provisioned by the toolchain resolver. It
runs as a daemon in the user's session, which opens a picker window whenever the portal asks for one, and an explorer
window whenever a `prism` is started (docs/running.md).

| module | depends on | what it is |
|---|---|---|
| `cli` | nothing | the command line: typed parameters, a one-pass reader that refuses what it cannot read, help made from the same declarations (metis's, with its errors returned rather than thrown) |
| `linux` | nothing | libc through the FFM API: openat, getdents64 read in place, statx, mmap, epoll, each with its errno captured at the call |
| `ebpf` | nothing | the eBPF DSL: programs declared in typed Kotlin and emitted as C for clang's BPF target; a reader of the kernel's BTF and a check of a program's declarations against it (docs/ebpf.md) |
| `ebpf-loader` | ebpf, linux | a program's compiled object loaded and attached through libbpf, and its ring buffer read in place |
| `probe` | ebpf | the watcher, declared in the DSL: hooks on the exits of the VFS functions that change a directory |
| `index` | probe, ebpf-loader, linux | the scanner and its backends, the ignore rules, and the watcher as the index runs it; the build compiles the probe into this module's resources |
| `dbus` | kotlinx.serialization, coroutines | D-Bus of prism's own: a connection over the bus's Unix socket that makes the handshake and `Hello` as it opens, messages as data, bodies by kotlinx.serialization with their signatures worked out from the Kotlin types, signals as flows, served methods (docs/dbus.md) |
| `portal` | dbus | the desktop portal as prism speaks to it: the colour scheme now, the file chooser it will serve |
| `preferences` | kotlinx.serialization | what the user has set, in a file every window of every process watches |
| `desktop` | linux | the desktop as interfaces (file types, applications, launching, places, a file's facts), and freedesktop.org's implementation of them |
| `browser` | index, desktop, preferences, Compose, coroutines | browsing, the picker's and the explorer's: an immutable state, a pure reducer, a session that carries out what the reducer asks for against a disk and the desktop, and a view made of controls of its own (docs/picker.md) |
| `explorer` | browser | the file explorer's windows: properties, "Open with", and the preferences (docs/explorer.md) |
| `app` | everything | the command line and the windows: `prism pick`, `explore`, `snapshot`, `scan`, `probe` |

The index module could ship without the picker: it knows nothing of windows or portals. The picker knows nothing of
the index yet; the app joins them.

## The index

### Reading the tree

A scan reads a directory tree into an immutable tree of what it holds (`prism.index.Scanner`). How a directory is
read is a backend; the traversal (one fork-join task per directory) is the same for all of them.

The first backend reads `getdents64` through prism's own bindings, which gives each entry's kind and inode with its
name, so nothing is stat'ed that does not have to be. Measured against the JVM's own directory stream, which must
stat every entry to learn its kind, it takes under a quarter of the JVM's time on one thread and about 40% on four,
and it reads names that are not UTF-8, which the JVM cannot (docs/scanning.md).

Later backends read a whole file system's namespace at once where the file system offers a way: btrfs's tree search
ioctl, an NTFS volume's MFT, a ZFS dataset's object list. Each is a `Backend` that answers for its mount and falls
back to getdents64 elsewhere.

### What is left out

`IgnoreRules` names directories left out with everything in them, in two ways. A rule without a leading `/` applies
anywhere: a name (`.git`, `node_modules`, `.cache`, `.gradle`, `.direnv`, `.dotnet`, `__pycache__`, ...) or the end
of a path (`.local/share/containers`, `go/pkg/mod`, `drive_c/windows`). A rule with one is that one directory
(`/nix/store`, `/boot`, `/tmp`, `/var/cache`, `/var/lib/docker`, ...). The defaults are trees that can hold more
entries than everything a person made and that nobody picks a file from: version control, package managers'
downloads, build output and caches, browsers' caches, snapshots, the system's own stores and temporary files.
Generic names people give their own folders (`build`, `target`, `dist`, `vendor`) are not among them.

File systems are left out by type wherever they are mounted (`proc`, `sysfs`, `tmpfs`, `ramfs`, `cgroup2`,
`debugfs`, ...): `/proc/self/mountinfo` is read once as indexing begins, and the mount points of those types are kept
in a set that each directory is looked up in as it is met. The index holds only folders, files and links: devices,
pipes and sockets are left out. A scan keeps a left-out directory in its tree by name, marked skipped, for the
benchmark's counts; the index holds nothing of it. The rules will come from a configuration file
(`$XDG_CONFIG_HOME/prism/prism.toml`), with the defaults for anything it does not say.

### In memory, and on disk

The index holds its entries as columns (`prism.index.Block`), in preorder, so everything under a folder is the
slice of every column that follows it:

- per entry: its parent, its name (one byte array of every name, back to back, and where each starts), its kind
  (folder, file, or a link to a folder, to a file, to something else, or to nowhere), its inode, and its device (an
  index into the block's devices, each a device number and the file system type mountinfo gives it); a folder's
  device and inode are what the watcher names it by, and the index finds a folder by them;
- worked out as it is loaded, never stored: where what is under each entry ends, every name folded to lower case at
  the same offsets, and every name's stem length.

An index is a value: a change makes a new one, sharing what did not change. Blocks are never changed, so taking
entries out hides them (each block keeps the ranges it no longer holds), and putting a tree in places a block of its
own beside the others. Compacting makes one block of each root again. A search reads the blocks as they are, so it
finds the same before and after.

The file is a compressed snapshot followed by frames of changes appended as they are flushed, and is compacted, written
anew as one snapshot, once the changes are a quarter of it (docs/format.md says it byte by byte). An earlier plan was
a file mapped into memory as it is; the file is compressed instead (zstd), which makes it a fraction of the size, and
reading it back is fast enough not to need mapping (under a third of a second for three million entries). Every change says what is
so now (this entry and everything under it is as this tree says, or it is absent), so making one twice is making it
once, and a frame read twice does no harm.

### Keeping it current

The watcher (`probe/fswatch.kt`, run by the `watcher` module) is an eBPF program with a hook on the exit of each VFS
function that adds an entry to a directory or takes one away: `vfs_mkdir`, `vfs_rmdir`, `vfs_unlink`, `vfs_create`,
`vfs_symlink`, `vfs_link`, `vfs_mknod`, `vfs_rename`, and `do_filp_open` (`do_file_open` since Linux 7.0) for files made
by `open(O_CREAT)`. The kernel changes these functions' parameters from one version to the next, so each is declared
in every shape it has had since 6.17, and the one that is the running kernel's is attached (`prism probe check` says
which, or what a kernel newer than any declared differs on). Hooking the
VFS rather than the system calls means every way of making a change is seen (io_uring's, nfsd's, overlayfs's), and
hooking the exit means only a change the kernel made is sent. Each change goes to the host through a ring buffer as
the device, the inode of the directory it is in, the entry's name and inode, and what happened.

The host finds the folder a change is in by those two numbers (the index keeps every entry's inode, and every
device's number as the kernel has it), and applies the change by **reading the disk** rather than trusting it: the
entry named is read again, and a folder a move left, which the change does not name the entry of, is reconciled one
level deep. That is idempotent by construction, and makes the order of changes and their duplicates harmless:

- a change in a directory the index does not hold (one it ignores, or one it has not reached) is dropped, but kept
  beside any scan in flight and made again once it lands;
- a move is the entry absent from one directory and present in another;
- a swap (`RENAME_EXCHANGE`) is both directories reconciled;
- when the ring was full and changes were lost (the watcher counts them), the index cannot know which, and reads its
  roots again in the background.

The daemon (the `daemon` module) runs the two together: the watcher starts first, before the index is read or built,
so from the moment the index begins to be read every change is seen, and a change seen while a scan was reading is
made again once the scan's result is made. docs/daemon.md says it all, with an example. The index itself needs no
watcher, and can be told to read places again (`LiveIndex.rescan`); a search that names, by an absolute path, a place
the rules leave out has it read for it alone, in memory, never written.

The watcher's ignore rules are applied by the host today: a change under `.git` reaches the host and is dropped there.
The DSL will gain a bounded loop so that the watcher can walk a few parents up from a change and drop it in the kernel,
which matters for the floods a `git checkout` or `npm install` makes.

### Privileges

Loading a tracing program needs `CAP_BPF` and `CAP_PERFMON`. A user's daemon has neither, and is not to: it starts
the user's applications, which would have them too. So a loader, run as root once at boot, loads the watcher and
leaves it in the kernel under names in a BPF file system (pins), and ends; the user's daemon opens the ring by its
name, which takes leave to open a file and no privilege, and only reads (docs/running.md). Where that is not set up,
the daemon runs without the watcher and keeps the index current by reading its roots again now and then, which is
slower to notice changes but never wrong. BPF tokens (Linux 6.9) are another way to delegate the capability, and
will be looked at when distributions enable them.

## The picker

The picker (docs/picker.md) is a loop of three parts. The view draws an immutable `BrowserState` and reports what the
user does as `BrowserEvent`s; a pure reducer makes the next state from the last and an event, with the effects it
wants (a folder read, a typed path looked up, the picker finished); and a session carries those out against a `Disk`
and sends the answers back in. What it is choosing is a sealed type, so a picker for one file cannot hold two and one
that saves always has a name; the button that accepts is worked out from that, never stored beside it. Everything a
row shows is formatted when the folder is read, so drawing is reading strings.

It browses, chooses and accepts as a desktop's own dialog does, by the pointer and by the keyboard throughout: back,
forward and up (the mouse's side buttons too), the places, a path typed, a search of the folder shown, sorting,
Ctrl- and Shift-click, type-ahead, saving over a file only when the user says so. A filter menu chooses a kind of
entry (images, videos, folders, ...), or several with Ctrl; hidden files are shown and hidden folders are not until
the user says otherwise. The listing is rows in columns or a gallery of tiles, with thumbnails of images made on IO
threads, drawn larger or smaller with Ctrl and the wheel, the gallery up to one picture at a time. Context menus (menus as data, worked out from the state
for what was pressed, and one component that draws any of them) rename, trash and make folders, and Ctrl+Z undoes
any of it. Nothing is deleted for good: the trash is as far as anything goes.

Ctrl+Shift+F searches the whole index, Everything's and Listary's way: a window over the picker lists the first matches
as the query is typed, coloured as it is read; a match chosen is gone to (a file chosen in its folder, its name ready
to save over); Enter shows every match in the listing as a folder holding them all would, filtered as a folder is,
best first, millions of them without making millions of rows, and Back returns to them from a folder gone into.

Features to come, roughly in order: type filters from the portal's request (MIME types as well as patterns); the
folders read from the index; a details pane with a preview;
thumbnails of videos and documents, and the desktop's own thumbnail cache; the list's column widths and order kept per
user; drag and drop out of the picker.

## The explorer

The browser the picker is made of is also a file manager (docs/explorer.md): a browser choosing `Exploring` gives
nothing back, and opens what is accepted with the desktop's applications instead, through a `Desktop` that says what
type a file is, which application opens it, and starts that application detached from prism. Around the browser, the
explorer keeps its windows as one immutable list, and adds properties, "Open with", and a preferences window in which
the places pinned, the terminal and every key binding are set, the keys as a game's controls are. The preferences are
one file every window of every process watches, so what is pinned in one is pinned in all, pickers included.

The daemon hosts the explorer as it hosts the picker, and answers `org.freedesktop.FileManager1` so applications can
ask it to show a folder or a file; a `prism` started for a window asks the daemon for it and is gone (docs/running.md).
Context menus the user configures are planned in docs/menus.md.

## The search language

A query is short and says what is wanted: terms, every one of which a result must pass. A bare word is found in the
stem (`report`), or in the whole name where it has a dot after its first character (`report.pdf`); a word with glob
characters is a glob (`*.{jpg,png}`); a term with a `/` is a path (`funny/*.jpg`, `~/Downloads/`, `work/2024/`); a
regex is `r/.../flags`; modifiers say the rest (`dir:`, `ext:`, `type:`, `is:`, `recent:`, `mount:`); `-` negates.
Aliases (`~reports`) are named folders, declared in the configuration file and offered in the sidebar.

docs/search.md says it all, with every example and how a search runs. A query is an immutable tree
(`prism.index.query`), built in Kotlin with a DSL, or read from text by a parser of combinators that reads any text at
all: into a tree of what each part is (to colour it), what is wrong where, and a query that searches for what can be
searched for while the rest is still being typed.

## The portal

prism is a portal backend (docs/portal.md): it implements `org.freedesktop.impl.portal.FileChooser` (`OpenFile`,
`SaveFile`, `SaveFiles`) on the session bus, with a `prism.portal` file in xdg-desktop-portal's portals directory that
says so, and the user choosing it in `portals.conf` (`org.freedesktop.impl.portal.FileChooser=prism`), all of which
`prism portal install` writes. Each request becomes a
`BrowserState` (its title, whether it chooses one file or many or a folder, its filters, its current folder and name),
and the chosen files go back as URIs.

D-Bus is prism's own (the `dbus` module, docs/dbus.md): serving `FileChooser` is declaring its methods and answering
them with `Connection.serve`, as the colour scheme is already read and followed through it. What is open is the
window: on Wayland the dialog should be shown over the application that asked (the request's `parent_window`, through the `xdg-foreign` protocol), which AWT under XWayland cannot do; the
JetBrains Runtime's native Wayland toolkit is the way to look at first.

## Testing

Every module's tests run without privileges and without a display; what needs more self-skips where it is not there,
as the engine's GPU tests do without a device:

- the DSL, the scanner, the picker's rules, its headless rendering, and the picker clicked and typed at headless
  (Compose's own test input) run anywhere;
- the emitted C is compiled by clang's BPF target where there is one;
- the declarations are checked against the running kernel's BTF where it publishes one;
- the loader loads a program into the kernel and reads its ring end to end where BPF may be loaded (a BTF tracepoint,
  which most kernels allow to a privileged process).

The watcher's own hooks (function exits) are verified against the kernel's BTF and compiled, but loading them needs a
kernel and a process that allow tracing programs; docs/testing.md says how to run that check on a real machine.
