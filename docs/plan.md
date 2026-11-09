# The plan

barion is a file picker for Linux desktops on Wayland. It replaces the open and save dialogs that applications get
through the desktop portal with one that is fast, that can find any file on the machine by a few typed words, and
that is never out of date. This page says what barion is for, how it is put together, and how the parts that do not
exist yet are meant to work. docs/inprogress.md says where the work stands today.

## What it is for

When a Wayland application wants the user to choose a file, it does not draw a dialog itself. It asks the desktop
portal (`xdg-desktop-portal`) over D-Bus, and the portal hands the request to a backend, which draws the dialog and
answers with the chosen file. Today those backends are GNOME's and KDE's, and they are folder browsers: to open a
file, you walk to its folder.

barion is a backend of its own. Its dialog is a familiar one (the layout of a Windows file dialog, which most people
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
  is gone), barion reads that one folder again and puts the index right.
- **Idempotent.** Every change to the index can be applied twice with the same result, so a change seen twice (by the
  watcher and by a rescan) is harmless, and recovering from anything is reading the folder again.
- **Leaving out what nobody picks from.** `.git`, `node_modules`, `.cache`, `.zfs` and their like are skipped by the
  scan and by the watcher alike. The list is configurable.
- **A search language.** Something short to type that says what is wanted: named aliases for places, and a search
  under one (`@work report pdf`).
- **Performance everywhere.** The scan, the index's file, the watcher and the view are each written to be fast.
- **Linux only.** barion uses Linux's own interfaces (getdents64, statx, eBPF, the portal) directly.

## How it is put together

barion is a Kotlin program on the JVM, built with Gradle the way kotlin-engine-test is: convention plugins in an
included `build-logic`, one version catalog, the house ktlint rules, JDK 25 provisioned by the toolchain resolver. It
runs as a daemon in the user's session, which opens a picker window whenever the portal asks for one.

| module | depends on | what it is |
|---|---|---|
| `cli` | nothing | the command line: typed parameters, a one-pass reader that refuses what it cannot read, help made from the same declarations (metis's, with its errors returned rather than thrown) |
| `linux` | nothing | libc through the FFM API: openat, getdents64 read in place, statx, mmap, epoll, each with its errno captured at the call |
| `ebpf` | nothing | the eBPF DSL: programs declared in typed Kotlin and emitted as C for clang's BPF target; a reader of the kernel's BTF and a check of a program's declarations against it (docs/ebpf.md) |
| `ebpf-loader` | ebpf, linux | a program's compiled object loaded and attached through libbpf, and its ring buffer read in place |
| `probe` | ebpf | the watcher, declared in the DSL: hooks on the exits of the VFS functions that change a directory |
| `index` | probe, ebpf-loader, linux | the scanner and its backends, the ignore rules, and the watcher as the index runs it; the build compiles the probe into this module's resources |
| `dbus` | kotlinx.serialization, coroutines | D-Bus of barion's own: a connection over the bus's Unix socket that makes the handshake and `Hello` as it opens, messages as data, bodies by kotlinx.serialization with their signatures worked out from the Kotlin types, signals as flows, served methods (docs/dbus.md) |
| `portal` | dbus | the desktop portal as barion speaks to it: the colour scheme now, the file chooser it will serve |
| `picker` | Compose, coroutines | the picker: an immutable state, a pure reducer, a session that carries out what the reducer asks for against a disk, and a view made of controls of its own (docs/picker.md) |
| `app` | everything | the command line and the window: `barion pick`, `snapshot`, `scan`, `probe` |

The index module could ship without the picker: it knows nothing of windows or portals. The picker knows nothing of
the index yet; the app joins them.

## The index

### Reading the tree

A scan reads a directory tree into an immutable tree of what it holds (`barion.index.Scanner`). How a directory is
read is a backend; the traversal (one fork-join task per directory) is the same for all of them.

The first backend reads `getdents64` through barion's own bindings, which gives each entry's kind and inode with its
name, so nothing is stat'ed that does not have to be. Measured against the JVM's own directory stream, which must
stat every entry to learn its kind, it takes under a quarter of the JVM's time on one thread and about 40% on four,
and it reads names that are not UTF-8, which the JVM cannot (docs/scanning.md).

Later backends read a whole file system's namespace at once where the file system offers a way: btrfs's tree search
ioctl, an NTFS volume's MFT, a ZFS dataset's object list. Each is a `Backend` that answers for its mount and falls
back to getdents64 elsewhere.

### What is left out

`IgnoreRules` names directories left out wherever they are (`.git`, `.hg`, `.svn`, `node_modules`, `.cache`,
`.zfs`, `.snapshots`, `__pycache__`, `.gradle`, `.venv`, ...) and file systems left out wherever they are mounted
(`proc`, `sysfs`, `cgroup2`, `debugfs`, ...). A directory left out is held in the tree by name, marked skipped, so the
picker can still show it, and is never read. The rules will come from a configuration file
(`$XDG_CONFIG_HOME/barion/barion.toml`), with the defaults for anything it does not say.

### In memory, and on disk

A scan builds a tree of objects today, which is right for comparing backends. The index proper holds the tree as
arrays, which is both what makes it small and fast in memory and what its file is:

- one byte array of every name, back to back;
- per entry: its parent's index, where its name starts and how long it is, its kind, its inode, its device's index;
- per directory: where its children start and how many there are, the children sorted by name;
- for search: an index of the names' trigrams (or a suffix array over the name bytes), so that a substring is found
  without reading every name.

The file is that, written as one memory-mappable file with a header (a magic number, a version, the sizes of each
array, a checksum) so that barion starts by mapping it rather than reading it. It is replaced whole by writing a new
file beside it and renaming it over the old one, so it is never seen half written. Between those rewrites, the
changes applied since are appended to a journal, which is read on start and folded into the next rewrite. A
dedicated document (docs/format.md) will specify the format byte for byte before it is written.

### Keeping it current

The watcher (`probe/fswatch.kt`) is an eBPF program with a hook on the exit of each VFS function that adds an entry
to a directory or takes one away: `vfs_mkdir`, `vfs_rmdir`, `vfs_unlink`, `vfs_create`, `vfs_symlink`, `vfs_link`,
`vfs_mknod`, `vfs_rename`, and `do_filp_open` for files made by `open(O_CREAT)`. Hooking the VFS rather than the
system calls means every way of making a change is seen (io_uring's, nfsd's, overlayfs's), and hooking the exit means
only a change the kernel made is sent. Each change goes to the host through a ring buffer as the device, the inode of
the directory it is in, the entry's name and inode, and what happened.

The host keeps its directories by device and inode, so it finds the directory a change is in by those two numbers.
It then applies the change by **reconciling** rather than trusting it: it stats that one name in that one directory
and sets the index's entry to what is there now (made, changed, or absent). That is idempotent by construction, and
makes the order of changes and their duplicates harmless:

- a change in a directory the index does not hold (one it ignores, or one it has not reached) is dropped;
- a move is the entry absent from one directory and present in another, found by its inode;
- a swap (`RENAME_EXCHANGE`) is both directories read again;
- when the ring was full and changes were lost (the watcher counts them), the index cannot know which, and reads
  again the directories that changed most recently, then everything, in the background.

The watcher's ignore rules are applied by the host today: a change under `.git` reaches the host and is dropped there.
The DSL will gain a bounded loop so that the watcher can walk a few parents up from a change and drop it in the kernel,
which matters for the floods a `git checkout` or `npm install` makes.

### Privileges

Loading a tracing program needs `CAP_BPF` and `CAP_PERFMON`. A user's daemon has neither. The plan is a small
system service that holds those capabilities, loads the watcher at boot, and hands the ring buffer's descriptor to the
user's daemon over a Unix socket (`SCM_RIGHTS`), so the daemon itself runs unprivileged and only reads. Where that is
not set up, the daemon runs without the watcher and keeps the index current by reading folders as the picker shows
them, which is slower to notice changes but never wrong. BPF tokens (Linux 6.9) are the other way to delegate the
capability, and will be looked at when distributions enable them.

## The picker

The picker (docs/picker.md) is a loop of three parts. The view draws an immutable `PickerState` and reports what the
user does as `PickerEvent`s; a pure reducer makes the next state from the last and an event, with the effects it
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

Features to come, roughly in order: type filters from the portal's request (MIME types as well as patterns); the
folders read from the index, and search across the index from the search field; a details pane with a preview;
thumbnails of videos and documents, and the desktop's own thumbnail cache; the list's column widths and order kept per
user; drag and drop out of the picker.

## The search language

A query is short and says what is wanted. A sketch, to be settled when it is built:

| query | means |
|---|---|
| `report` | names containing "report", anywhere |
| `report pdf` | names containing "report" and ending in `.pdf` (a word that is a known extension is the extension) |
| `@work report` | the same, under the place named `work` |
| `@work/2026 report` | under a folder of that place |
| `"q3 report"` | the phrase, spaces and all |
| `report -draft` | without "draft" |
| `report >1m <1w` | bigger than a megabyte, changed in the last week |

Aliases (`work` above) are named places, declared in the configuration file and offered in the sidebar. The language
is parsed into an immutable query (a sealed tree of terms), and run against the index's name trigrams, then checked
against the names themselves.

## The portal

barion becomes a portal backend by implementing `org.freedesktop.impl.portal.FileChooser` (`OpenFile`, `SaveFile`,
`SaveFiles`) on the session bus, with a `barion.portal` file in xdg-desktop-portal's portals directory that says so,
and the user choosing it in `portals.conf` (`org.freedesktop.impl.portal.FileChooser=barion`). Each request becomes a
`PickerState` (its title, whether it chooses one file or many or a folder, its filters, its current folder and name),
and the chosen files go back as URIs.

D-Bus is barion's own (the `dbus` module, docs/dbus.md): serving `FileChooser` is declaring its methods and answering
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
