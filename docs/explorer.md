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

## Quick access, and preferences every window shares

The sidebar's quick access is the user's preference, not a window's. It holds prism's own places (the home folder, its
well-known folders, and the trash) and the folders the user pinned, in the user's order (`QuickAccess`, in
`preferences/quick.kt`). Prism's own are always in it, each once: the user hides one, and moves it, but cannot remove
it, so a place a later version adds reaches every user's list, at its end. That is kept by construction: a
`QuickAccess` is made only by `QuickAccess.of`, which drops what is named twice and adds what is left out, whatever a
file says. A folder's menu pins it ("Pin to sidebar"); the menu of something quick access shows takes it off ("Unpin
from sidebar": a folder pinned is unpinned, and one of prism's own hidden from it). A picker's sidebar leaves out the trash,
whose entries it cannot give an application.

The button beside the "Quick access" heading opens a window over the browser that arranges it (`quickaccess.kt`):
every entry, shown or hidden, taken anywhere on it but its box and buttons and dragged to where it goes (the rest
making room as it goes, as `ReorderableColumn` draws any list the pointer reorders), several chosen together (a click
chooses one, Shift and a click another) dragged together, or one moved up or down a place; prism's own shown or hidden
by their boxes, the folders pinned removed; a folder pinned by its path; and "Reset", prism's own in their own order,
every one shown, before the folders pinned. Each change is made as it is made, so the sidebar behind shows it, and is
undone and done again by the keys for that (Ctrl+Z, Ctrl+Shift+Z) while the window is open: its own changes, not what
the browser undoes of the disk; closed, they are forgotten. The sidebar, and this window, are drawn as large as
the preferences' sidebar size says (Display, "Sidebar"); Ctrl and the wheel do not zoom them.

Arranging changes the window's sidebar at once, and asks for `Effect.Arrange(quick)`, which the session writes to the
`PreferenceStore`. Every session collects the store's `StateFlow<Preferences>` and makes its sidebar again whenever it
changes, so every other window follows.

The store is a file, `$XDG_CONFIG_HOME/prism/preferences.json`, and the file is watched. A change written by another
process (another `prism explore`, a `prism pick`, the daemon, or the user's editor) is read back and given to every
window here. So a folder pinned in the explorer is in the next picker's sidebar too, and in any picker already open.

```json
{
    "quickAccess": [
        { "type": "builtin", "place": "Home" },
        { "type": "folder", "path": "/home/avery/Projects" },
        { "type": "builtin", "place": "Music", "shown": false },
        { "type": "builtin", "place": "Trash" }
    ],
    "bindings": {
        "SearchIndex": ["Ctrl+K"],
        "Up": ["Alt+Up", "Backspace", "Mouse Back"]
    },
    "terminal": "ghostty"
}
```

`quickAccess` is null until the user arranges it; until then the sidebar shows prism's own places, in their own order
(and those of the list above that it leaves out after it, shown). An older version kept a list of paths, `pinned`, in
its place: where only that is there, it is read as quick access (the paths in their order, prism's own among them, and
those of prism's own it left out hidden, since the user had unpinned them; the trash, newer than it, shown), and it is
dropped the first time quick access is arranged. `bindings` holds only the actions whose chords differ from their
defaults; an action named with an empty list has no chord at all. `terminal` is null for the desktop's own terminal;
otherwise the terminal chosen, in one of the forms "Choosing the terminal" (below) shows. A file that does not read as
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

A drag of prism's own carries what it drags as itself to any of prism's windows. On Wayland it is Wayland's own drag,
which goes to other programs too, as files (a URI list), or a place's entries' names as text; on X11, Windows and
macOS, it goes between prism's windows by where the pointer is on the screen, and not yet out to other programs
(docs/window.md).

A drop is made by the reducer, as a paste is: `BrowserEvent.Dropped`, the files, where (a folder, or the listing
itself), and copy, move or link. Which of those is the platform's convention for the keys held, and, for a drag of
prism's own with no key held, a move within a volume and a copy across one. A move into another application is that
application's to make; prism reads its folder again afterwards, and deletes nothing itself. docs/platform.md follows a
drag through, and says what each platform does with it.

## The listing's columns, order, filters and text

A row's menu opens over its name, and over the space after it in the name's column; the other columns are the
listing's, whose menu is the folder's.

**The columns** are a catalogue (`ColumnId`, in `preferences/listing.kt`): the name, where an entry is, when it was
modified, made and trashed, its type, its size and its extension. Each kind of listing offers some of them
(`ListingKind`): a folder's listing every one but where an entry is and when it was trashed; a search's results where
each is, and not when it was made, which is not read of results; the trash where each was ("Original location") and
when it was trashed; and a place (an archive, by its type, `place:archive.zip`) what its command lists of an entry.
Each listing's columns (`Columns`) are each that its kind offers, exactly once, in the user's order, each shown or not
and aligned left, centre or right; the name's is always first and always shown. That is kept by construction:
`Columns` are made only by `ListingKind.columnsOf`, which drops what a kind does not offer, and adds what it offers and
is left out (hidden, at the end, where the user has arranged the rest), so a column added to the catalogue reaches
every listing that offers it without a word in anyone's preferences.

A window keeps a look for each kind of listing (`Looks`): its order and its columns, as the preferences gave them as it
opened, and as the user has made them since, through the column headings (clicked to order by one, the other way round
when clicked again) and the headings' menu (a column shown or hidden; the heading pressed aligned). Going from folder
to folder keeps a folder's look; a search's results, the trash and each type of place have their own. What the window
shows is worked out from its looks for what it shows (`BrowserState.arrangement`), never kept beside them.

When a file was made is its birth time, which statx(2) gives where the file system keeps it (ext4, btrfs, XFS, tmpfs
do; FAT and NFS do not, and those show "—"); it is not Unix's "ctime", which is when the file's inode last changed. It
is read behind the folder, a batch at a time: those in sight first, then the rest in the folder's order, each answer
asking for the next, so that what is scrolled to is read next. Ordered by it, those not known are last, by name.

**The filters** (which hidden entries are shown, and which kinds of file) are kept the same way, for each kind of
listing and each layout: a gallery can leave out hidden files that a list of the same folder shows. The filter menu
changes the window's own, for what it shows and how.

**As a window opens**, its looks and filters are the preferences' (`Listings`): for each kind of listing in an explorer
and in a file picker, and the filters for each layout too. What they do not say for a picker is the explorer's; what
they do not say for the explorer is prism's own. When they change (the preferences window applied, or the file
edited), every open window takes them too, for the kinds whose defaults changed; a window whose look or filters were
changed from its own menus keeps them until then.

```json
{
    "listings": {
        "looks": {
            "explorer/folder": {
                "order": { "type": "by", "sort": { "by": "Modified", "ascending": false } },
                "columns": [ { "id": "Name" }, { "id": "Extension" }, { "id": "Modified" }, { "id": "Size", "align": "End" } ]
            },
            "picker/place:archive.zip": { "order": { "type": "relevance" } }
        },
        "filters": { "explorer/folder/gallery": { "hidden": { "files": false }, "kinds": [ "Images" ] } }
    },
    "typography": { "menus": { "points": 12.5 }, "listing": { "family": "Inter", "points": null }, "general": {} },
    "rows": "Larger",
    "scales": { "explorer": 1.25, "picker": 1.0, "sidebar": 1.0, "windows": { "Preferences": 1.1 } },
    "dates": "Iso"
}
```

**How it is drawn.** The list's rows are larger, with icons as large as Thunar draws them, or compact (`rows`); either
is as tall as its text and its icon need, so text made larger never outgrows its row. Each column has room either side
of what it shows, and the list room at its edges.

A date column is a table of its dates' parts. `Nov 2, 2026, 9:05 AM` is read from its format's pattern (`MMM d, yyyy`
and `h:mm a`, `DateFormat`) into fields and the words between them (`DatePart`): the month's name, a space, the day, a
comma, the year; the hour, a colon, the minutes, a space, AM or PM. Each field is drawn in a width the widest it ever
writes fits (measured once, of a day of each month at hours that write widest), a number at the end of its width and a
word at its start, its digits each as wide as the next (tabular figures). So down a column every month's name starts
in one place, every day ends in one place, and every year and every time line up, whatever the font, though "May" is
narrower than "Nov" and the 2nd than the 12th. The system's own format is read the same way, from the pattern its
language and country write.

A date column is exactly as wide as that (`Width.Date`). The other columns each have a least width, in characters of
the listing's text (`Width.Share`: the name's sixteen, and its icon), and share what room is left by their shares, so a
listing drawn larger takes its room from them, the name's first. Where even their least is more than the listing has,
nothing is cut down further: the rows are as wide as their columns need, and they and their headings scroll across
together, with a bar under them, by the wheel with Shift held or a touchpad's sideways scroll. Every column's width is
worked out in one place (`layoutOf`), which the headings and the rows both draw from, so a heading is always over its
column.

A gallery's tiles have room between them, and around them, for a rectangle to be started or the folder's menu opened.

`typography` names the font of the menus, the listing and the rest (a family the system has, by name), and its size in
points, as desktops measure type (11 points by default, 12 for the menus: GNOME's and KDE's body text is 10 to 11).
Either left null is the default's, so a later default reaches whoever never chose one. An older version's `text` and
`fonts` are no longer read: they wrote the defaults of the day as though the user had chosen them.

**Scrolling.** The wheel scrolls a list by its own rows, three a notch (a gallery by a line of tiles), however large it
is drawn: a notch moves as far through the folder at 300 % as at 100 %. A touchpad's small scrolls move it as much
less as they are smaller. Every list and page that scrolls has its bar beside it whenever there is more than fits.

`scales` says how large things are drawn, as many times their usual size: the listing of a new explorer's window and
of a new picker's (the nearest of the steps Ctrl and the wheel zoom the listing through), the sidebar's quick access
and the window that arranges it, and each of the other windows (the preferences, a file's properties, "Open with"), as
Ctrl and the wheel last left one of its kind. In those windows Ctrl and the wheel zoom as a web browser zooms a page,
through 50 % to 500 %: text, and everything measured in dp, laid out again at that size; Ctrl with + and -, and
Ctrl+0, do too. `dates` is how the listing writes a date: day, month, year (`12 Nov 2026, 14:03`, the default); month,
day, year; ISO's year-month-day; day/month/year; month/day/year; day.month.year; or as the system's language writes
it. A file's properties write dates in full, to the second, whatever it says.

**The preferences window** sets all of these, and Apply makes them so without closing it. Its changes not yet applied
are undone and done again by the keys for that (Ctrl+Z, Ctrl+Shift+Z), each as it was made, the window going back to
where it was made; a move about the window (another page, another menu shown) is no change to undo, and a field
undoes its own typing first. Applied, or closed, they are forgotten; no other window's are touched. Display: each part's font
and size, the rows, the sizes, the dates. Renderer: whether prism's windows are drawn on the GPU or the CPU, and why,
first, in a sentence and what to do about it (a window that OpenGL could not draw, though it runs, says so, with the
reason it was given); then each graphics device the computer has, with its driver, its nodes and
the library OpenGL reaches its driver through, and why it is used or not (docs/window.md, "Why a window is drawn as it
is"). Copy report copies all of it as text, to paste where someone can help. Layout: every kind of listing down the
page's side (prism's own, then each type of place), narrowed by a field over them so that any of however many places
is found, each marked where it has anything of its own; and beside them the one chosen, in an explorer or a file
picker: its order, its columns, dragged into order (several chosen together: a click, and Shift and a click),
ticked shown or not, and aligned; and its filters,
for a list and for a gallery. A picker's are the explorer's until its switch gives it its own (changing anything does
too). Context menus: the same list down the side, and the sidebar after it; beside it, the menu chosen, opened on a
file, a folder, several, or the empty space around them, drawn as a menu is, with every entry it can hold, prism's and
the actions'; a submenu's items are drawn in under it, and its arrow folds them away and back. Each entry is dragged by
anywhere on it but its controls into order, or several chosen together (a click chooses one, Shift and a click
another), a submenu's items among themselves; its keys
(Shift, Ctrl, Alt) lit keep it for a menu opened with them held; switched off, it never shows. A right-click on an entry
puts a line before or after it, where that sets something apart; on a line, takes it away, as its × does. What differs
from prism's own arrangement is tinted, with a mark at its start, and said: moved, added, shown differently. The menu
is seen for all files, where what is only for some (an action for a .zip, or for images) is left out, and the page says
how many entries that is; or for a file of each group of extensions, names or types the same actions are written for
(".zip, .jar, .war and 12 more", which the zip actions open; ".epub", which an action of the user's is for too), where
what is not offered for it is left out. What is left out stays where it is, whatever is moved around it; a line
with only that between it and the one before sets nothing apart, and is left out too. And it is seen with keys held:
what the menu would not show then is faint. A listing's menus other than a folder's are arranged as a folder's until
one is given its own (its switch, or any change to it). Beside the menu's name, "Cancel" takes back every change to the
context menus not yet applied, "Reset menu" forgets the menu's own arrangement, and "Reset all menus" every menu's and
every entry hidden; each says so as the pointer rests on it, none is kept until applied, and each is undone as any
change is. Of two entries that changed places, the one dragged is the one said to have moved. Key bindings: a filter (Ctrl+F goes to it) that finds an action by its name, as shown or as the file
writes it, or by its chords; and, as JetBrains' keymap has it, a button that opens "Find Shortcut", which takes the
next shortcut pressed (a key with its modifiers, or the mouse's middle, back or forward button over its lower part) for
as long as it is open, and shows what it does, binding nothing. Closed, the keys are the page's again, and what it
found stays shown, beside the filter, until its × or the button again. The list's scrollbar is drawn while there is
more than fits.

## Open terminal here

A terminal has to be told the folder to start in: starting it in the folder is not enough for every one. Ghostty runs
one process for all its windows, and a launch given no options only asks that process for a window, which opens where
it likes (the home folder); told the folder (`--working-directory=`), it opens there. Every terminal takes the folder,
and a command to run, by an option of its own (`--working-directory=`, `--directory=`, `--workdir`, `--cwd=`, or
none). So a terminal is described by what its command line takes (`Terminal`, `desktop/terminals.kt`), and opened by a
function of that description.

Which terminal is the user's is not standardized on Linux. prism asks, the most deliberate first: its own preference;
`$TERMINAL`; `xdg-terminals.list`, the proposed standard's list of desktop entries (xdg-terminal-exec's), the user's,
then the system's, each folder's file for the session's desktop first; KDE's own setting; Debian's
`x-terminal-emulator`; the installed desktop entries of the category `TerminalEmulator`; and the terminals prism knows,
on the `PATH` (`desktop/freedesktop/terminals.kt`). A terminal named by a desktop entry is told the folder and a command
as the entry says (`X-TerminalArgDir=`, `X-TerminalArgExec=`), or else as prism knows it (`KnownTerminals`, about twenty),
or else is started in the folder and given `-e`. On macOS (Terminal, iTerm, Ghostty, through `open`) and Windows
(Windows Terminal, PowerShell) the forms are described the same way, for the launchers those platforms are to have.

### Choosing the terminal

Where prism finds the wrong terminal, or none, or knows one wrong, or the user wants options of their own, the
preferences say which. The preferences window's Terminal page chooses it from a combo box, which is clicked to list
every choice, or typed into to narrow them:

1. Automatic, the desktop's own as found, and every terminal installed: each prism knows on the `PATH`, and each
   desktop entry of the category `TerminalEmulator` whose program it doesn't know (`Launcher.terminals`).
2. Custom, under them; or alone, where the desktop names none and none is installed.
3. Set apart and faint, but still choosable, the terminals prism knows that are not on the `PATH`. One chosen is said
   in red, under the box, not to be.

Custom shows its two command lines, kept as typed while another is chosen. What would run in the home folder is
always shown, as it is chosen and typed. In the file, the choice is one of these:

```json
"terminal": null
```

The desktop's own, as found.

```json
"terminal": "ghostty"
```

A terminal by its name, or its desktop entry's (`"com.mitchellh.ghostty.desktop"`): told the folder and given a command
as its desktop entry says, or as prism knows it to take them. A name neither says anything of is not guessed at: prism
says to write its command line.

```json
"terminal": {
    "open": ["ghostty", "--font-size=14", "--working-directory={folder}"],
    "run": ["ghostty", "--font-size=14", "--working-directory={folder}", "-e", "{command}"]
}
```

Its command lines, each written out in full for what it is used for: `open`, a terminal opened in a folder ("Open
terminal here"); and `run`, a terminal opened in a folder running a command there (the actions and applications that
run in a terminal; an application runs in the home folder). `{folder}` is the folder, once in each line, a word of its
own or joined to an option; `{command}`, once in `run` and a word of its own, is a command's words. A line is only
filled in: nothing is taken out of it, and neither is made from the other. A line left out is not used, and what it is
for is off ("Open terminal here" in every menu, or the actions that run in a terminal), with nothing else run in its
place. prism never opens a terminal in no folder, so there is no line for that.

A choice that cannot be read (a line without `{folder}`, an `open` line with `{command}`, a `run` line without it, or
either said twice) is kept as it was written, and "Open
terminal here" says why it cannot open it, rather than losing the rest of the preferences, or guessing.

## Not done yet

- **Hosting in the daemon.** `prism portal serve` hosts the explorer and answers `org.freedesktop.FileManager1`
  (docs/portal.md), in a process of its own; the daemon, which keeps the index, does not host it yet.
- **The rest of the context menus the user configures**: docs/menus.md says what is built (actions, places, the
  archive tool made of them) and what is not (the menu's order, items made as it opens, an editor).
- **Renaming from the properties window** (rename from the listing, with F2, as before), and the properties of several
  files' permissions together.
- **Icons**: applications and files are drawn with prism's own glyphs, not the desktop's icon theme.
- **Content sniffing** with shared-mime-info's magic, for files whose names say nothing.
- **Dragging out to other applications on X11, Windows and macOS**: on Wayland prism speaks the platform's drag
  protocol itself; the others' are to be spoken the same way (docs/window.md). Copy and paste is the way meanwhile.
