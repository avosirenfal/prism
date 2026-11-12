# The explorer

`prism explore` is a file manager made from the picker's own parts. The browser it shows (the listing, the sidebar,
the search, the menus, undo) is the picker's, in a mode that gives nothing back; around it, the explorer adds what a
file manager has and a picker does not: files opened with the desktop's applications, "Open with", the properties of
files, and a preferences window in which every key can be bound again. This document follows one double-click from
the listing to a running application first, since that one action passes through nearly every part; then it takes
the parts one at a time.

```bash
./gradlew :app:run --args="explore"
```

```bash
./gradlew :app:run --args="explore ~/Documents --index"
```

## A double-click, followed through

Say the explorer shows `~/Documents`, and the user double-clicks `report.pdf`.

**The browser decides what a double-click means.** The view sends `BrowserEvent.Activated(row)` to the reducer, the
pure function `step(state, input)` that every picker uses too. A picker would choose the file and finish. An explorer
is `Choosing.Exploring`, which chooses nothing to give back, so for a file the reducer asks for an effect instead:
`Effect.Open([~/Documents/report.pdf])`. A folder would have been gone into, as a picker goes into one. The reducer
does not open anything itself: it only says what should happen, which is what makes it testable without a desktop.

**The session carries the effect out.** `Session` runs every effect the reducer asks for, on a background thread,
against its `Services`: the disk, the index, the clipboard, the preferences, and the `Desktop`. `Effect.Open` is
answered by `desktop.open(paths)`.

**The desktop finds the file's type.** On Linux the desktop is freedesktop.org's (`desktop/freedesktop/`), and a
file's type is shared-mime-info's: `report.pdf` matches the glob `*.pdf` in the `globs2` file under each data folder's
`mime/`, so its type is `application/pdf`. A name that matches nothing is looked into: empty, text, or bytes.

**The desktop finds the application.** Which application opens `application/pdf` is said by the `mimeapps.list`
files, read in the order the specification gives: the user's own (`~/.config/mimeapps.list`, after the desktop's own
variant of it), then the system's, then the data folders'. The first default named for the type that is installed
wins; where none is named, the type's parents are tried (`text/x-kotlin` is a kind of `text/plain`), and then the
applications that say they open it. Say that is `org.gnome.Evince.desktop`.

**The launcher starts it, detached.** The desktop entry's `Exec=evince %U` is split into words as the specification
quotes them, and `%U` is replaced by the file, as a word of its own, so a name with spaces or quotes in it is one
argument and never text a shell reads. The command is then started through `setsid --fork`, which gives it a session
of its own and leaves it a child of init rather than of prism; where the user's systemd is running, it goes through
`systemd-run --user --scope --slice=app.slice` too, as desktops start applications, so it is not in prism's group of
processes either. Closing the window, or stopping the daemon, takes nothing it opened with it.

**What came of it goes back to the reducer.** `Desktop.open` answers with an `Opening`: what was started (or why not),
and the files nothing opens. The session sends that back as `Answer.Opened`. A failure becomes a notice in the window;
a file nothing opens becomes a request, `Request.OpenWith([file])`, which the reducer asks of the window's owner with
`Effect.Ask`.

**The explorer opens a window for the request.** The session hands every request to the `Explorer` that made it,
which adds an "Open with" window to its list of windows; the list is what is drawn, so the window appears.

So a double-click touches the reducer (what it means), the session (doing it), the desktop (types, applications,
launching), and the explorer (windows). The rest of this document is those parts.

## Windows

The explorer's state is one immutable list of `ExplorerWindow`s, each one of:

| window | made by | what it is |
|---|---|---|
| `Browsing` | `prism explore`, "New window", "Open in new window" | a browser's `Session`, and the coroutine scope its work runs in |
| `Properties` | "Properties" (Alt+Enter) | the properties of some paths |
| `OpenWith` | "Open with…", a file nothing opens, the properties' "Change…" | an application chosen, to open files with or to be a type's default |
| `Settings` | "Preferences…" (Ctrl+Comma) | the preferences window; there is only ever one |
| `Picking` | an action of the user's that asks for a folder ("Extract to…") | prism's folder picker, whose choice is the answer, given to the window that asked |

A `Browsing` window starts in a folder or in a place (`Start`): opening an archive opens one on the
archive's place, which a command lists (docs/menus.md).

`ExplorerWindows(explorer, palette, decorated)` is a composable that draws one window for each entry, keyed by its id.
Nothing else says what is open, so there is nothing to keep in step: a window is opened by adding it to the list and
closed by taking it out. A browser's window also closes when its session ends, which is what Ctrl+W, the app menu's
"Close window", and the window manager's close button all do, the same way a picker is cancelled.

`prism explore` composes `ExplorerWindows` inside its own `application {}` and exits when the list is empty. The daemon
is meant to compose the same thing inside its own application, where it would stay running with no windows, and open
one when an application asks it to show a folder (`org.freedesktop.FileManager1`'s `ShowFolders` and `ShowItems` are
the D-Bus methods desktops use for that). That hosting is not written yet.

Each window has the title bar the window manager draws only with `--decorated`; without it, the dialogs show their
title at the top and close with their own buttons or Escape.

## Pins, and preferences every window shares

The places pinned to the sidebar's quick access are the user's preference, not a window's. Pinning a folder from its
menu does not change the window's sidebar directly. The reducer asks for `Effect.Pin(newList)`, and the session writes
it to the `PreferenceStore`. Every session collects the store's `StateFlow<Preferences>` and rebuilds its sidebar
whenever it changes. The window that pinned the folder learns of the pin the same way every other window does.

The store is a file, `$XDG_CONFIG_HOME/prism/preferences.json`, and the file is watched. A change written by another
process (another `prism explore`, a `prism pick`, the daemon, or the user's editor) is read back and given to every
window here. So a folder pinned in the explorer is in the next picker's sidebar too, and in any picker already open.

```json
{
    "pinned": ["/home/avery", "/home/avery/Documents", "/home/avery/Projects"],
    "bindings": {
        "SearchIndex": ["Ctrl+K"],
        "Up": ["Alt+Up", "Backspace", "Mouse Back"]
    },
    "terminal": ["foot", "--working-directory", "{folder}"]
}
```

`pinned` is null until the user pins or unpins something; until then the sidebar shows the desktop's own places (the
home folder and the well-known folders in it). `bindings` holds only the actions whose chords differ from their
defaults; an action named with an empty list has no chord at all. `terminal` is null for the desktop's own terminal
(`$TERMINAL`, then `xdg-terminal-exec`, then the first of the usual terminals found). A file that does not read as
preferences leaves every default in place, and is copied beside itself with `.unreadable` after its name before
anything is written over it, so nothing written by hand is lost.

## Properties

The properties window shows what Explorer's and Thunar's do, worked out from the file's `Facts` (`desktop/facts.kt`)
by plain functions (`generalOf`, `tallyOf`) that the tests call directly:

- the name, the type as the desktop names it ("PDF document (application/pdf)"), and the folder it is in;
- what opens it, with "Change…", which opens the application chooser to set the type's default;
- the size: for a folder, what it holds is added up on a background thread while the rest is shown ("Counting…",
  then "4.2 MB (4,400,000 bytes)" and "120 files and 8 folders");
- when it was made (statx's birth time, where the file system keeps one), modified, and last read;
- where a link leads.

Several files are counted together ("3 items, 2 files and 1 folder"), with one type and one location where they share
them.

For one file, the Permissions tab shows its owner and group and a box for each of read, write and run (for a folder,
"go into") for the owner, the group, and everyone else. A box ticked changes the file at once, as Thunar's does, and
the file is read again, so what is shown is what is on disk. Someone other than the owner (or root) sees the boxes but
cannot tick them.

## Open with, and defaults

The chooser lists first the applications that open every type among the files ("Recommended"), then every other
application, by name. Typing narrows both; the arrows move through what is shown, and Enter or a double-click
confirms. For files that are all of one type, a box offers to make the one chosen that type's default as well.

A default is set as every desktop's settings set it: in the user's `mimeapps.list`, as the `[Default Applications]`
entry for the type, with the application added to `[Added Associations]` too, so other file managers and `xdg-open`
see the same choice. The explorer counts the defaults it has set, and the properties window reads "Opens with" again
when that count changes.

## The bindings window

Every key the browser answers is an `Action` (`browser/bindings.kt`) with default chords: "Search everything" is
Ctrl+Shift+F, "Back" is Alt+Left and the mouse's back button. The view never names a key; it looks up the chord it
was given. So any action can be bound to any chord, and the preferences window's "Key bindings" page is where that is
done, the way a game's controls are set.

The user's own actions (docs/menus.md) are in the same table, under "On the files, from the list", each with the chord
it names as its default. The pickers read the same bindings, so a key bound here is bound in every file and folder
dialog too.

Each action is a row, with a chip for each of its chords and a "+" for another. Clicking a chip makes it wait ("Press a
key…"). The next chord pressed is taken: a key with whatever of Ctrl, Alt, Shift and Meta are held, or a mouse button
(the middle, back or forward; the left and right buttons are what clicking is). Escape stops waiting instead. A chip's
× removes the chord, and "Reset" puts an action's defaults back.

A chord does one thing, so a chord given to one action is taken from any other that had it, and the page says so
("Mouse Back was taken from “Back”"). For the actions that move the cursor through the list, Shift and Ctrl are part of
the move (Shift extends what is chosen, Ctrl moves without choosing), so a move is bound to its key alone and claims
that key with any modifiers. This rule lives in one place (`Bindings.bound`, using the same test as `Bindings.clashes`),
and the page only shows its result.

Where an action acts depends on its scope:

| scope | acts | examples |
|---|---|---|
| Window | anywhere, for a chord no text field types (one with Ctrl, Alt or Meta, a function key, a mouse button); from the listing, for any chord | Back, Search everything, Read again, New window |
| Listing | from the listing only, since a text field uses the same keys for its own text | Copy (Ctrl+C), Rename, Move to trash |
| Movement | from the listing | the arrows, Page Up, Home |
| Leftover | wherever the focused control does not use the key itself | Escape, Undo |

So binding "Folder above" to plain Backspace makes it act from the listing alone, since in the address field
Backspace must delete a character.

Nothing is written while the window is open. Saving writes only what was changed there, over the preferences as they
are by then: a folder pinned in another window while the bindings were being changed stays pinned. The window holds
its edits as one immutable value, `Settings`, whose `over(current)` does that merge, and the tests check it.

## Across platforms

Everything above the `Desktop` interfaces (`desktop/desktop.kt`) is the same on every platform: the browser, the
explorer's windows, the properties, the chooser, the bindings. What differs is behind the interfaces:

| interface | Linux (written) | macOS (to write) | Windows (to write) |
|---|---|---|---|
| `FileTypes` | shared-mime-info's globs | uniform type identifiers, mapped to MIME types where they have one | the registry's extensions and their content types |
| `Applications` | desktop entries and `mimeapps.list` | Launch Services (`LSCopyApplicationURLsForURL`, `LSSetDefaultRoleHandlerForContentType`) | the registry's `OpenWithProgids` and the user's choice |
| `Launcher` | `setsid --fork`, in a systemd scope where there is one | `open -a`, which Launch Services starts outside prism already | `ShellExecuteEx`, likewise |
| `Places` | the XDG user directories, and `/media`, `/mnt` | `~/Library`'s well-known folders, and `/Volumes` | the known folders, and the drives |
| `FileFacts` | the JVM's POSIX views, statx for when a file was made | the JVM's POSIX views give all of it | the JVM's DOS and ACL views |

`currentDesktop()` picks the implementation, and says plainly that there is none yet elsewhere. The two X11 helpers
the windows use (the focus watch and the title bar's tone) do nothing where the window is not on X11. The index and
its watcher are Linux's alone (eBPF), and are the larger part of a port: on macOS, FSEvents would take the watcher's
place; on Windows, the USN journal.

## A folder shown is kept as it is on disk

A window reads its folder again whenever what the folder holds changes, whoever changed it: a file extracted beside
an archive, a download finishing, something made in a terminal. What says so is a `FolderWatcher` (`browser/source.kt`),
which a browser's services are given: the JVM's watch service here (inotify on Linux), and whatever another platform
has behind the same interface. The folder is read a moment after it changes, so that many changes made together are
one reading, and what is chosen and where the cursor is stay as they were. The pickers are given the same watcher.

## Choosing with a rectangle

A press where no row is, held and dragged, draws out a rectangle, and the rows it touches are chosen, in the list and
in the gallery, in a folder, a search's results, or a place. With Ctrl held it adds to what was chosen. It takes in
the rows in sight: it does not scroll the listing as it is dragged.

## Drag and drop

Rows and tiles drag as files, into other applications and onto folders in prism; the folder rows, the listing between
them, the places in the sidebar and the steps of the path take files dropped on them. Pressing one of several chosen
rows keeps them all chosen until the press ends, so that a drag of it drags them all; let go without dragging, it alone
is chosen, as a click of it chooses it. A folder a drag waits over for a moment opens, for the files to go further in,
and a line under the listing says what a drop where the pointer is would make ("Move 2 items into “Documents”").

A drag starts when the first button is held and the pointer has moved eight pixels, as desktops have it, so a click
with an unsteady hand is a click; the other buttons drag nothing. (Compose's own drag source starts one after a pixel
or two, with any button.)

Under XWayland on a compositor that does not tell windows where they are (niri, through xwayland-satellite), the X
server does not know where prism's windows are on screen, so a drag from one to another is never seen to be over the
other, and ends dropped on nothing. Where a drag of prism's own ends that way and the pointer then comes at once into
something that takes drops in another of its windows, that is where it was dropped (`StrayDrop`, in
`browser/dragging.kt`). This is only done under XWayland.

A drop is made by the reducer, as a paste is: `BrowserEvent.Dropped`, the files, where (a folder, or the listing
itself), and copy, move or link. Which of those is the platform's convention for the keys held, and, for a drag of
prism's own with no key held, a move within a volume and a copy across one. A move into another application is that
application's to make; prism reads its folder again afterwards, and deletes nothing itself. docs/platform.md follows a
drag through, and says what each platform does with it.

## Not done yet

- **Hosting in the daemon.** `prism portal serve` hosts the explorer and answers `org.freedesktop.FileManager1`
  (docs/portal.md), in a process of its own; the daemon, which keeps the index, does not host it yet.
- **The rest of the context menus the user configures**: docs/menus.md says what is built (actions, places, the
  archive tool made of them) and what is not (the menu's order, items made as it opens, an editor).
- **Renaming from the properties window** (rename from the listing, with F2, as before), and the properties of several
  files' permissions together.
- **Icons**: applications and files are drawn with prism's own glyphs, not the desktop's icon theme.
- **Content sniffing** with shared-mime-info's magic, for files whose names say nothing.
- **Drag and drop between X11 and Wayland windows under niri**: it works between X11 windows (checked on a virtual
  display); between an X11 window and a Wayland one, it is xwayland-satellite's to bridge, which is to be tried on a
  real desktop (docs/platform.md).
