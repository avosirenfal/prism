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
| macOS | SDL's dragging destination | AppKit's dragging session, as out to other applications | an `NSDraggingSession` with file URLs, begun on the window's view, each file shown as its icon (`FileDrags`) |

## What else a port needs

| part | Linux | macOS | Windows |
|---|---|---|---|
| which platform this is, and where prism keeps its own files | `Os`, `OwnFolders` (platform module): the XDG folders | the same: `~/Library/Application Support/prism`, `~/Library/Caches/prism`, the socket in the temporary folder the system names for the user (under /var/folders) | the same: `%APPDATA%`, `%LOCALAPPDATA%` |
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
| the launcher over every window | a layer surface, or a window kept in front | a non-activating panel, as Spotlight's is: made by prism as AppKit makes one and given to SDL for its window, at a pop-up menu's level, on every Space and over full-screen applications | a topmost tool window |
| hidden files | names starting with a dot | those, and the `UF_HIDDEN` flag (`~/Library`), later | the hidden attribute |
| what is left out by default | Linux's own, after what every platform has: containers, snapshots, the system's stores (`Exclusions.defaultsOn`) | a Mac's own: the Library's folders of applications' data, what is inside a bundle, what the system keeps on a volume for itself, the system's trees and Homebrew's | what every platform has, until Windows' own are written |
| the application | a desktop entry, with "New Window" | Prism.app, whose own program is what runs (the daemon too), so the Dock and the system's permissions know it as Prism; opened again while it runs, it shows a window where it has none; "New Window" in its Dock menu and in prism's launcher | a shortcut |
| quitting | closing the last window | Command-Q asks each window to close, and a daemon stays; Quit asked any other way ends it | closing the last window |
| setting it up | the flake's NixOS module, or `prism portal install` | Prism.app sets itself up the first time it is opened; or the flake's nix-darwin module | |
| packaging | AppImage | Prism.app, and a DMG of it; signed and notarized, later (signing is what keeps Full Disk Access granted across updates) | MSI |

Where a part stands apart from the platform, a port is an implementation behind an interface: the `Desktop`
interfaces (docs/explorer.md, "Across platforms") with the trash among them, the index's `FileSystems`, the daemon's
`Feed`, the browser's `FolderWatcher`, `OwnFolders`, the `Keyboard`, and drag and drop.

### Reaching what is native

A native function is described twice by the FFM API: to the linker, as a descriptor of layouts, and at each call, by
the Java types of what is passed and expected back, which are compared only as the call runs. So a call that passes
a struct where a long was declared, or is compiled as returning a value where the function returns nothing, compiles,
and fails the first time that line is reached; both happened on the first Mac prism ran on.

So a function is declared once (the ffi module), as an interface whose one method is its signature:

```kotlin
internal fun interface Free { operator fun invoke(pointer: MemorySegment) }

private val free: Free = Library.system.bind("free")
```

What the linker is told is made from that method's own types (`Signature.of`), and the function is called through
the interface, so the compiler checks every call against the very declaration the linker was given. A struct passed by
value, a function whose errno is kept, and a C function's `...` are said beside it (`@ByValue`, `@KeepsErrno`,
`@Variadic`); a callback is an interface likewise (`Library.upcall`). Every native function prism calls is bound so:
libc's (the linux module), libbpf's, libX11's, libwayland-client's and everything of macOS's. Wayland's one variadic
function, `wl_proxy_marshal_flags`, is an interface for each shape of arguments a request sends. Wayland's listeners
and SDL's one callback are still made from a method's name and types, which the linker checks as each is made.

macOS's own interfaces are Objective-C, reached through its runtime's C functions (macos module): a class by its name,
a selector by its name, and a message sent by calling `objc_msgSend` as the method's own C function would be called,
which on Apple silicon is how every method is called. Each method prism sends is declared once too, as a value of its
class's object (`classes.kt`):

```kotlin
data object NSFileManager : ObjCClass("NSFileManager") {
	val trashItemAtURL = message("trashItemAtURL:resultingItemURL:error:", Obj, Ptr, Ptr, Bool)
}
```

from which both the signature it is sent with and the Kotlin types of a call are made, so a call that passes the
wrong thing does not compile, and no message is sent that is not declared. What the compiler cannot know is whether a
declaration is the method Apple wrote. The runtime can: it keeps every method's own type encoding, and a test on a Mac
compares each declaration with it (`MessagesTest`), every one, whether or not anything calls it.

What a method answers lives until its autorelease pool is drained, so objects are used inside `ObjC.pooled` and only
Kotlin's values leave it. No method prism calls takes a block, and what needs a callback has a C function for it
(FSEvents, Carbon's hot keys, `dispatch_sync_f`), so nothing of Swift is built, and all of it compiles on Linux.

## What is built, and by what

Every build that is installed or passed on is made by the flake, which brings its own Java, Gradle and GraalVM (the
compiler of the client): nothing is installed first but Nix. Each holds the Java prism and the `prism` a shell
starts, the client compiled ahead of time.

| for | made by | what it is | `prism` on the PATH |
|---|---|---|---|
| NixOS | the flake as an input, and `programs.prism.enable = true` (docs/nixos.md) | the package (nix/package.nix), the daemon as a systemd user service | by NixOS |
| nix-darwin, on Apple silicon | the flake as an input, and `programs.prism.enable = true` (below, "With nix-darwin") | Prism.app in the Nix store (nix/package-darwin.nix), the daemon as a launchd agent | by nix-darwin |
| a Linux with no Nix | `nix build .#prism-portable`, on Linux | `prism-0.1.0-linux-x86_64.tar.gz` (nix/package-portable.nix): one folder to unpack anywhere, holding the jars, a Java to run them (Adoptium's, as published, which runs on any distribution) and the client, linked statically against musl | by linking `bin/prism` of the folder into `~/.local/bin` |
| a Mac with no Nix | `nix build .#prism-dmg`, on a Mac | `Prism-0.1.0-macos-arm64.dmg` (nix/dmg.nix): Prism.app to drag onto Applications | by the application, the first time it is opened (below) |
| Windows | nothing yet | prism does not run on Windows yet | |

A Java program cannot be one statically linked file: what runs it is a Java, which is many files and loads the
system's C library. So "needs nothing of the system" is reached another way for the Linux archive: the Java is in
the folder, and is one built against a C library old enough for every distribution in use, and the one part that is
compiled to machine code, the client, is linked statically. What the archive still takes from the system is what
every desktop program does: the Wayland or X11 libraries and OpenGL, which are the desktop's own, and libbpf where
the watcher is wanted.

The archive's daemon is started by hand or by a systemd user service (docs/running.md); its desktop entry is in
`share/applications` of the folder, to copy into `~/.local/share/applications`.

Each is built on its own kind of machine: Nix builds a Mac's programs on a Mac and Linux's on Linux. CI does both
(the `portable` and `dmg` jobs) and keeps what they made with the run.

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
7. **The application.** `./gradlew :app:createDistributable`, and Prism.app (in
   `app/build/compose/binaries/main/app`) put in /Applications and opened
   ("Setting it up on a Mac", below): Prism.app in the Dock as Prism
   while a window is open, its icon opening a window, "New Window" in its Dock menu, and Command-Q closing windows
   and leaving the daemon.

## Setting it up on a Mac

A Mac's application is a folder, Prism.app, that is put in /Applications and opened. There is no installer: what
other platforms have one do, a Mac's application does for itself the first time it is opened. So:

1. **Put Prism.app in /Applications**, from its disk image (below) or from a build.
2. **Open it.** The first time (and again if it is ever moved), it makes its daemon a launchd agent of that very
   application (`dev.prism.daemon`, a file in `~/Library/LaunchAgents`), which launchd starts at once and whenever
   you log in, and starts again should it fail; then it shows a window. From then on the daemon is what runs: it
   keeps the index, holds the launcher's key, and opens every window, and opening Prism.app asks it for one.
3. **It asks for your password once**, to link `/usr/local/bin/prism` to the client inside the application, so that
   `prism` is a command in every terminal. That folder is the administrator's, which is why it asks. Said no to, it
   does not ask again; `Prism.app/Contents/MacOS/Prism cli install` does it later (`--bin DIR` for a folder of yours).
4. **Two things are yours to do in System Settings**, and the daemon says so where either is not done: give Prism
   Full Disk Access (Privacy & Security), so the index reads every folder without asking; and turn off Spotlight's
   shortcut (Keyboard › Keyboard Shortcuts… › Spotlight), so Command-Space is prism's.

### The daemon, day to day

The daemon has no window and no icon in the Dock, so it is spoken to through `prism`:

| to | run | which is |
|---|---|---|
| see how it is | `prism status` | asked of the daemon itself; what it has said is in `~/Library/Application Support/prism/daemon.log` |
| stop it | `prism agent stop` | launchd told to unload the agent (`launchctl bootout`). It stays stopped until you log in again, open Prism.app, or start it |
| start it | `prism agent start` | launchd told to load the agent and set it going |
| restart it | `prism agent restart` | `launchctl kickstart -k` |
| stop it for good | `prism agent uninstall` | the agent stopped and its file removed, so nothing starts at login. Opening Prism.app sets it up again |

Quitting a window (Command-Q, or Command-W) closes windows and leaves the daemon, as closing a Finder window leaves
Finder.

### A new build over an old one

Drag the new Prism.app onto /Applications and say "Replace". Nothing else is needed: the daemon looks at its own
program's file every few seconds, and when another has settled in its place it ends by failing, which is what
launchd starts it again for, now from the new application (agent.kt, `followOwnApplication`). The link in
/usr/local/bin points into /Applications/Prism.app, so it is the new client already.

One thing does not carry over while the application is sealed for no one in particular: Full Disk Access. The system
knows such an application by what it is byte for byte, so a new build is a stranger, and is given access again in
System Settings (remove the old Prism from the list, add the new). One signed with a Developer ID is known by that,
and keeps it.

### Taking it away

Dragging Prism.app to the Trash is enough to stop it: the daemon sees its application gone and, after a minute, ends
without failing, which launchd leaves ended. What is left does nothing and can stay or go:

| left behind | what it is | removed by |
|---|---|---|
| `~/Library/LaunchAgents/dev.prism.daemon.plist` | the agent, naming a program that is no longer there; launchd finds nothing to start | `prism agent uninstall` before trashing, or deleting the file |
| `/usr/local/bin/prism` | a link to nothing | `prism cli uninstall` before trashing, or `sudo rm /usr/local/bin/prism` |
| `~/Library/Application Support/prism`, `~/Library/Caches/prism` | the index, the settings, the log; thumbnails | deleting them |

### Passing it on

```bash
nix build .#prism-dmg
```

makes `result/Prism-0.1.0-macos-arm64.dmg` on a Mac (nix/dmg.nix): a disk image that opens to Prism.app beside a link
to Applications, to drag the one onto the other. The application in it is the flake's package, which carries its own
Java (Adoptium's, linked against the system alone) and the client compiled ahead of time, so it runs on a Mac with
no Nix. CI's `dmg` job makes the same image on every run, and on one started by hand from the Actions page, to
download from the run's page ("Prism-macos-arm64").

A disk image holds files and runs nothing: it cannot start a daemon or change the PATH. That is why the application
does both for itself when it is first opened, as above.

Whether another Mac opens it without a word depends on how it is sealed:

| sealed | made by | on another Mac |
|---|---|---|
| for no one ("ad hoc") | `nix build .#prism-dmg`, and CI | the first opening is refused ("Apple could not verify…"), since it came from the network and nobody vouches for it. The user allows it once: System Settings › Privacy & Security › "Open Anyway"; or `xattr -dr com.apple.quarantine /Applications/Prism.app`. A copy that never touched a browser (AirDrop is one that does; a USB stick, `scp` or `rsync` are not) is opened without a word |
| with a Developer ID | the package overridden: `prism-dmg.override { signingIdentity = "Developer ID Application: NAME (TEAM)"; }`, the certificate being in the keychain | the same refusal, worded as "from an identified developer", until it is also notarized |
| and notarized | the image sent to Apple (`xcrun notarytool submit Prism.dmg --keychain-profile NAME --wait`, then `xcrun stapler staple` the same), which needs Apple's developer programme | opened as any application is |

### With nix-darwin

The flake has a module (`darwinModules.prism`) that installs Prism.app and `prism`, and runs the daemon as the same
agent:

```nix
{
  inputs.prism.url = "github:avosirenfal/prism";

  outputs = { nix-darwin, prism, ... }: {
    darwinConfigurations.mac = nix-darwin.lib.darwinSystem {
      modules = [
        prism.darwinModules.prism
        { programs.prism.enable = true; }
      ];
    };
  };
}
```

`programs.prism.daemon.roots` and `.extraArgs` are as on NixOS (docs/nixos.md). The package (nix/package-darwin.nix)
is Prism.app built by Gradle, with the client beside its program and linked as `bin/prism`, which nix-darwin puts on
the PATH. An application in the Nix store sets nothing up by being opened: the module is what runs the daemon, and
`darwin-rebuild switch` is what stops, replaces and removes it.

Two things about the Nix package are not as they should be yet. Its lock of what Gradle fetches
(nix/deps-darwin.json) is empty: a Mac's artifacts are not Linux's, and the lock is written by running, on a Mac,

```bash
$(nix build .#prism.mitmCache.updateScript --print-out-paths --no-link)
```

until which the package does not build (CI's `dmg` job writes it, and keeps it with the run, to be committed). And
since it is not signed, Full Disk Access is given again after an update, as above.

### Run from Gradle

Run by `./gradlew :app:run`, prism is `java` to the Dock and to the system's permissions, since the program running
is java's and no application's: that is for working on prism, and Prism.app is for using it.

Not written yet: resuming FSEvents from the last event id a daemon saw;
`getattrlistbulk` for reading folders; `UF_HIDDEN`; IOKit's and DiskArbitration's drives; a Developer ID to sign
with, and notarizing.
