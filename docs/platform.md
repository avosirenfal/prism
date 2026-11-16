# Platforms

prism is written and run on Linux, and has what macOS (on Apple silicon) needs written beside what Linux has, not yet
tried on a Mac. This document says what in it is the same everywhere, what is each platform's, and what Windows would
need, with drag and drop, the feature that touches every platform's toolkit, worked through first as the example of
how prism stays out of the platforms' way; then where macOS stands, and what to try on a Mac first.

## What prism is on each platform

On Linux, prism is three things: the index of every file (kept current by an eBPF watcher), the file explorer, and the
file picker, both as the desktop portal's chooser, which every application's open and save dialogs are, and on the
command line: `prism pick` opens the picker and prints what is chosen on standard output, one path a line, or nothing
and exit status 1, the way scripts use dmenu or zenity.

```bash
vim "$(prism pick ~/Documents)"
```

On macOS and Windows an application's own open and save dialogs are the system's, and cannot be replaced. That takes
away one way to the picker, not the picker: the command line is the same on every platform, so a script, a terminal
tool, an editor's command or a launcher can still ask prism for a file, with its index's Ctrl+Shift+F search, and get
the path back. The explorer and the search are the same everywhere too.

## Drag and drop, followed through

Say the user drags `report.pdf` from prism onto a browser's upload box. Three things happen, on every platform:

1. **prism offers the files** in a form the platform knows: on X11 a URI list through XDND; on Wayland a URI list on a
   data offer; on macOS file URLs on the drag pasteboard; on Windows a `CF_HDROP` through OLE.
2. **The target takes the form it understands**, and reads the files.
3. **The two agree on an action**, copy, move or link, which the user steers with the keys they hold.

prism's windows are SDL3's (docs/window.md), and SDL speaks each platform's protocol for files dropped on a window:
they come to prism as a list of paths, whatever the platform. A drag of prism's own, between its own windows, carries
what it drags as itself, and needs no protocol; a drag out to another application does, and SDL starts none, so that
is each platform's for prism to speak (below).

**A move is the target's to make.** When files are dropped with "move" on another application, that application moves
them; prism is only told how the drag ended, and reads its folder again. It never deletes what it dragged. Windows is
where this matters most: Explorer, as the target, makes the move itself, and a source that deleted the files as well
would lose them.

**Which action a drop makes** is the platform's convention for the keys held: on Linux and Windows, Ctrl copies,
Shift moves and both link; on macOS, Option copies and Cmd moves (`askedOf`). With no key held, desktops agree that a
drop moves within a volume and copies across one, which `Disk.sameVolume` decides. A drag from another application is
dropped as no key asks, since the keys held over it are that application's. The rule is a pure function
(`transferOf`), the same on every platform; so is what it is told, since `sameVolume` asks the JVM for the device a
path is on (the `unix:dev` attribute, on Linux and macOS) or, failing that, its file store.

What a drop does once made is the reducer's, as for a paste: a `Change.Copy`, `Change.Move` or `Change.Link`, with
free names ("a (copy).txt", "Link to a.txt") and undo. Dropped on the listing of a picker, files are not copied
anywhere: the picker goes to the first of them, as to a path typed, since files dropped on a picker are files to choose.

The pointer cannot be told which action prism will make, so the line under the listing says it ("Copy 2 items into
“Backup”"), and that line is the truth. Of another application's drag it says "Drop", since what is dragged, and so
whether it is on the same volume, is known only once it is dropped.

### Each platform

| | files dropped on prism | prism's drags between its windows | prism's drags out to other applications |
|---|---|---|---|
| Wayland | SDL's data device | Wayland's drag protocol, as out to other applications | Wayland's data device (`wl_data_source`, `start_drag`), spoken through libwayland-client beside SDL's connection, with the drag's picture as its icon and its cursor by cursor-shape |
| X11 | SDL's XDND | between windows, by where the pointer is on the screen | XDND, to be spoken through Xlib |
| Windows | SDL's OLE drop target | between windows, as on X11 | OLE's `DoDragDrop` with `CF_HDROP` |
| macOS | SDL's dragging destination | between windows, as on X11 | an `NSDraggingSession` with file URLs |

## What else a port needs

| part | Linux | macOS | Windows |
|---|---|---|---|
| which platform this is, and where prism keeps its own files | `Os`, `OwnFolders` (platform module): the XDG folders | the same: `~/Library/Application Support/prism`, `~/Library/Caches/prism`, the socket in `$TMPDIR` | the same: `%APPDATA%`, `%LOCALAPPDATA%` |
| keeping the index current | the eBPF watcher | FSEvents (`Feed.fsEvents`): each entry made, removed or renamed, by its folder's device and inode; a folder to read again, or events dropped, counted as lost and the roots read again | the USN journal, or `ReadDirectoryChangesW` |
| a folder shown kept current | the JVM's watch service (inotify) | FSEvents (`FsEventsFolderWatcher`), since the JVM's only polls there | the JVM's (`ReadDirectoryChangesW`) |
| reading folders | getdents64 through FFM | the JVM's reader (`FileSystems.MacOS`); `getattrlistbulk` through FFM for speed, later | the JVM's reader; `FindFirstFileEx` for speed |
| what is mounted, and what is left out | mountinfo's pseudo file systems (`FileSystems.Linux`) | getfsstat; what Finder does not show (`nobrowse`: the system's own volumes, devfs, and the data volume, whose files are reached from `/` through its firmlinks) | the drives' types |
| knowing a file system again | udev's UUIDs, the file systems' own ids | each volume's UUID (getattrlist's `ATTR_VOL_UUID`); one under /Volumes comes and goes | the volume's GUID |
| permission to read | none asked | Full Disk Access, known by reading what only it lets a program read (`fullDiskAccess`); without it, the index leaves out the folders macOS would ask about one by one (`protectedFolders`), so reading it asks nothing, System Settings' page is opened once, and they are read the moment it is given; a folder the explorer cannot read says how to allow it | none asked |
| running the daemon | a systemd user service | a launchd agent: `prism agent install` | a scheduled task, or a service |
| the picker for other applications | the portal, over D-Bus | none: the command line | none: the command line |
| file types, applications, defaults, launching | freedesktop's: shared-mime-info, desktop entries, `mimeapps.list`, `setsid` | uniform type identifiers said as MIME types, the bundles in the applications folders, Launch Services' defaults, `open -a` (`MacDesktop`) | the registry, `ShellExecuteEx` |
| icons | the icon theme | NSWorkspace's, drawn to a PNG and kept in prism's cache | the executable's own |
| drives, and how full each is (the System page) | /sys/block, mountinfo, udev's records, statvfs, `zfs list` (`LinuxStorage`) | the volumes Finder shows, each a store of its own (`VolumeStorage`); drives and APFS containers told apart by IOKit and DiskArbitration, later | the volume management API |
| bringing forward what is started | Wayland's xdg-activation token, in `XDG_ACTIVATION_TOKEN`; on X11, a startup notification (not yet) | Launch Services, which activates what it opens | `ShellExecuteEx`, from a process the user just pressed something in |
| the trash | freedesktop's trash, one for each mount (`FreedesktopTrash`) | the system's (`SystemTrash`: NSFileManager's `trashItemAtURL`, where it came from kept on the item as an extended attribute) | the Recycle Bin |
| the colour scheme | the portal's | the appearance chosen (`AppleInterfaceStyle`, read each second) | the registry's app theme |
| keys | Ctrl | Cmd (`Keyboard.Mac`): defaults written with `Primary`, and Finder's own where it has them | Ctrl |
| OpenGL | the driver's default | a core profile of 3.2 or later, which macOS gives as 4.1 | the driver's default |
| windows | a title bar unless Display's "Titlebar" is off: asked of the compositor (xdg-decoration), which draws it, has prism draw it (GNOME), or draws none (niri) | the system's title bar, and the menu bar | the system's title bar |
| in the Dock | | only while a window of the usual kind is open, with prism's icon; the daemon and the launcher are an accessory application | |
| the launcher's key | the window manager's binding of `prism launcher` | the daemon's own: a Carbon hot key, ⌘Space once Spotlight's is turned off (docs/launcher.md, "On a Mac") | a hot key of the system's (`RegisterHotKey`) |
| the launcher over every window | a layer surface, or a window kept in front | a non-activating panel, as Spotlight's is: SDL's window made one at run time, at a pop-up menu's level, on every Space and over full-screen applications | a topmost tool window |
| hidden files | names starting with a dot | those, and the `UF_HIDDEN` flag (`~/Library`), later | the hidden attribute |
| packaging | AppImage | a signed, notarized DMG (and signing is what keeps Full Disk Access granted across updates) | MSI |

Where a part stands apart from the platform, a port is an implementation behind an interface: the `Desktop`
interfaces (docs/explorer.md, "Across platforms") with the trash among them, the index's `FileSystems`, the daemon's
`Feed`, the browser's `FolderWatcher`, `OwnFolders`, the `Keyboard`, and drag and drop.

### Reaching macOS

macOS's own interfaces are Objective-C. prism reaches them as it reaches libc: through FFM, calling the Objective-C
runtime's C functions (macos module, `objc.kt`). A class is found by its name (`objc_getClass`), a selector by its
name (`sel_registerName`), and a message is sent by calling `objc_msgSend` as the method's own C function would be
called, with the receiver and the selector first. On Apple silicon that is how every method is called, whatever it
returns, so the one function, given each method's signature, reaches them all. Foundation's objects are
CoreFoundation's (an NSString is a CFString), so CoreFoundation's C functions are used beside them.

What an Objective-C method returns lives until its autorelease pool is drained, so the objects are used inside
`ObjC.pooled` and only Kotlin's values leave it: a `UniformType`, a `Bundle`, a path, a PNG's bytes. Blocks
(Objective-C's closures), which FFM cannot make, are not needed. The methods prism calls take none, or take nil for
one, and what needs a callback has a C function for it: FSEvents and Carbon's hot keys call an FFM upcall, and
`dispatch_sync_f` is libdispatch's block-free form. So nothing of Swift is built, and the whole of it compiles on
Linux. What it says of a type, a bundle or a volume is tested on a Mac, against the system itself.

## macOS, as it stands

Everything above in the macOS column is written, and builds and is tested on Linux as far as it can be: the
`statfs` layout read from bytes, a uniform type said as a MIME type, the Mac's key bindings with no two clashing,
the launch agent's property list, the launcher's key read as Carbon takes it. The tests that ask macOS itself are
`@EnabledOnOs(OS.MAC)`, and none has run yet: CI's job on macOS (`.github/workflows/ci.yml`) has not been started
by a push to this repository, so the first Mac they run on is a person's.

What to try on an Apple-silicon Mac, in order, since each needs the one before:

1. **The build.** `./gradlew assemble testClasses`. The probes are left out on macOS (Apple's clang has no BPF
   target), and nothing else is Linux's to build.
2. **The bindings, against the system.** `./gradlew :macos:test :platform:test` and
   `./gradlew :desktop:test --tests 'prism.desktop.macos.*'`. These say whether the Objective-C runtime is reached
   rightly (text there and back, uniform types, a bundle, Launch Services' default, an icon), whether getfsstat's
   layout is right, whether FSEvents says what is made and removed, and whether the trash puts back what it took.
   A mistake in a method's signature shows here as a wrong answer or a crash, with the method's name in the trace.
3. **A window.** `./gradlew :window:demo`. This is the core profile and Skia on it. If the window is blank or no
   window opens, `PRISM_DRAWING=cpu ./gradlew :window:demo` draws on the CPU, which tells the drawing apart from
   the window.
4. **The explorer.** `./gradlew :app:run --args="explore"`: the listing, opening a file (Launch Services), "Open
   with", the trash, the sidebar's places and volumes, the System page, icons, and Cmd's shortcuts.
5. **The daemon.** `./gradlew --no-daemon :app:run --args="daemon --foreground"` from a terminal that has Full Disk
   Access (System Settings › Privacy & Security), then `prism status` from another: the index of `/`, kept current by
   FSEvents, and the line on disk access. macOS asks the program responsible for a process, which for one run by
   Gradle's daemon is whatever started that daemon (an IDE, as often as not): `--no-daemon`, or `./gradlew --stop`
   first, makes it the terminal.
6. **The launcher.** With the daemon running, ⌘Space, once Spotlight's is turned off (System Settings › Keyboard ›
   Keyboard Shortcuts… › Spotlight); the daemon says so where it is not.
7. **The agent.** `prism agent install`, once there is a `prism` to run (an app image from
   `./gradlew :app:createDistributable`, or `--command`).

Not written yet: drags out to other applications (an `NSDraggingSession`, which wants an Objective-C class of
prism's own made at run time, with `objc_allocateClassPair`); resuming FSEvents from the last event id a daemon saw;
`getattrlistbulk` for reading folders; `UF_HIDDEN`; IOKit's and DiskArbitration's drives; the shared thumbnails
(QuickLook's); a package opened as Finder opens it (an application started, not gone into); signing and the DMG.
