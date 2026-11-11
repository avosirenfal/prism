# Platforms

prism is written and run on Linux. This document says what in it is the same everywhere, what is Linux's, and what
macOS (on Apple silicon) and Windows would need, with drag and drop, the feature that touches every platform's
toolkit, worked through first as the example of how prism stays out of the platforms' way.

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

prism names none of those forms. It offers a Java `Transferable` holding a list of files, and AWT, under Compose,
turns that into the platform's own: its X11 toolkit into XDND, its macOS toolkit into a dragging session, its Windows
toolkit into OLE, and JetBrains' Wayland toolkit into a Wayland data offer. Files dropped on prism come back the same
way, as a list of files, or as a URI list where a toolkit leaves it at that (`browser/dragging.kt`).

**A move is the target's to make.** When files are dropped with "move" on another application, that application moves
them; prism is only told how the drag ended, and reads its folder again. It never deletes what it dragged. Windows is
where this matters most: Explorer, as the target, makes the move itself, and a source that deleted the files as well
would lose them.

**Which action a drop makes** is the platform's convention for the keys held, which AWT already applies: on Linux and
Windows, Ctrl copies, Shift moves (and Ctrl+Shift, or Windows' Alt, links); on macOS, Option copies and Cmd moves. With
no key held, desktops agree that a drop moves within a volume and copies across one. A drag from another application
arrives with its toolkit's choice made. A drag of prism's own, though, AWT calls "move" whether Shift asked for it or
nothing did, so prism watches the keys held during its own drags through AWT's drag source (which every platform's
toolkit drives) and applies the rule itself: with no key held, `Disk.sameVolume` decides between a move and a copy. The
rule is a pure function (`transferOf`), the same on every platform; so is what it is told, since `sameVolume` asks the
JVM for the device a path is on (the `unix:dev` attribute, on Linux and macOS) or, failing that, its file store.

What a drop does once made is the reducer's, as for a paste: a `Change.Copy`, `Change.Move` or `Change.Link`, with
free names ("a (copy).txt", "Link to a.txt") and undo. Dropped on the listing of a picker, files are not copied
anywhere: the picker goes to the first of them, as to a path typed, since files dropped on a picker are files to choose.

Compose decides the action AWT shows the pointer with, and always takes the platform's; so where prism will copy
across volumes while the pointer says "move", the line under the listing says what will happen ("Copy 2 items into
“Backup”"), and that line is the truth.

### The four ways prism's windows reach the desktop

**X11, under XWayland, under xwayland-satellite on niri.** Between X11 windows, a drag is XDND inside one X server,
and works (checked on a virtual display: prism to prism, prism to another Java application in another process, and
back, with the actions and the keys as above). Between an X11 window and a Wayland one, the X11 drag must be turned into
a Wayland one: GNOME's, KDE's and wlroots' compositors do it in their own XWayland window managers; on niri,
xwayland-satellite would have to. Whether it does is to be tried on a real desktop: drag from prism into a native
Wayland application, and from Nautilus into prism. If it does not, it is xwayland-satellite's gap, and copy and paste,
whose clipboard it does bridge, is the way between them meanwhile.

A test display needs a window manager for drags between windows: AWT finds the window under the pointer by the
`WM_STATE` property a window manager gives each application's window, so on a bare Xvfb a drag between two
applications finds no target (docs/testing.md).

**Wayland, directly.** JetBrains' Wayland toolkit (WLToolkit) has drag and drop; what prism waits for is Compose and
skiko running on it. prism's drag and drop needs nothing new for it, since it is AWT's. What does: the mouse's side
buttons are read by AWT's button numbers, which `mouseButtonOf` assumes are X's on Linux; the focus watch and the title
bar's tone are X11's, and already do nothing elsewhere; and a window with no decorations of the window manager's may
need decorations of its own there.

**macOS.** AWT's toolkit gives and takes file drags as Finder does. Its keys are Option to copy and Cmd to move, which
the rule above takes as they are. It shows a picture of what is dragged (as Windows does; X11's AWT does not).

**Windows.** AWT's toolkit speaks OLE with `CF_HDROP`, which Explorer gives and takes. Ctrl copies, Shift moves, Alt
links.

## What else a port needs

| part | Linux | macOS | Windows |
|---|---|---|---|
| keeping the index current | the eBPF watcher | FSEvents, file by file: its "must scan this folder again" is `LiveIndex.rescan`, and its event ids let a start resume where the last run stopped | the USN journal, or `ReadDirectoryChangesW` |
| reading folders | getdents64 through FFM | the JVM's reader now; `getattrlistbulk` through FFM for speed | the JVM's reader; `FindFirstFileEx` for speed |
| what is left out | mountinfo's pseudo file systems | `getfsstat`'s; and APFS shows the same files at `/` and `/System/Volumes/Data`, so one is left out | the drives' types |
| permission to read | none asked | Full Disk Access for `~/Library` and the like, granted per signed application, and a question for Desktop, Documents and Downloads; what is refused is said, with how to grant it | none asked |
| running the daemon | a systemd user service | a launchd agent | a scheduled task, or a service |
| the picker for other applications | the portal, over D-Bus | none: the command line | none: the command line |
| file types, applications, defaults, launching | freedesktop's: shared-mime-info, desktop entries, `mimeapps.list`, `setsid` | uniform type identifiers, Launch Services, `open -a` | the registry, `ShellExecuteEx` |
| the trash | freedesktop's trash (`Trash`) | the system's (`NSFileManager trashItemAtURL`, which says where each item went, for undo) | the Recycle Bin |
| the colour scheme | the portal's | the system's appearance | the registry's app theme |
| keys | Ctrl | Cmd, as the defaults' "primary" modifier | Ctrl |
| windows | no decorations by default, `--decorated` for the window manager's | the system's title bar, made transparent, and the menu bar | the system's title bar |
| hidden files | names starting with a dot | those, and the `UF_HIDDEN` flag (`~/Library`) | the hidden attribute |
| packaging | AppImage | a signed, notarized DMG (and signing is what keeps Full Disk Access granted across updates) | MSI |

Where the code already stands apart from the platform, a port is an implementation: the `Desktop` interfaces
(docs/explorer.md, "Across platforms"), `Disk`, the `Places`, and drag and drop. Where it does not yet, it has to be
made to first: the trash is freedesktop's in the browser itself, and wants to move behind `Desktop`; the default
bindings say Ctrl, and want a "primary" modifier that is Cmd on macOS; and the index's watcher and reader are
Linux's, behind the daemon.

macOS's own interfaces are Objective-C, and some take blocks (callbacks), which FFM reaches poorly. The plan for them is
a small library of prism's own in Swift, exporting plain C functions (`@_cdecl`) that Kotlin calls through FFM as it
calls libc now: the Swift side deals with Objective-C's objects and blocks, and Kotlin sees functions.

In order: the parts above that do not stand apart yet; then a macOS `Desktop` and the explorer over the JVM's reader,
with the index read but not kept current; then FSEvents, the daemon as a launchd agent, and Full Disk Access; then
signing and the DMG. The middle two need an Apple-silicon Mac to build and try on.
