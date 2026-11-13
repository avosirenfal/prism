# Testing

`./gradlew build` runs every module's tests. They need no privileges and no display. What a test needs beyond the
JDK, it checks for first and skips without (JUnit's assumptions), the way kotlin-engine-test's GPU tests skip without
a device, so a test that ran and a test that could not run are never confused.

## What runs where

| tests | need | where they skip |
|---|---|---|
| the DSL (`ebpf`: `CheckTest`, most of `EmitTest`) | the JDK | never |
| the emitted C compiling (`EmitTest`, `FsWatchTest`) | a clang with the BPF target | without one |
| the declarations against the kernel (`BtfTest`, `FsWatchTest`) | `/sys/kernel/btf/vmlinux` | on a kernel built without BTF |
| the loader, end to end (`LoadTest`) | clang, libbpf, and a process allowed to load BPF | without either, or where the kernel says EPERM |
| the scanner (`ScanTest`) | the JDK | never |
| libc's calls (`FilesTest`) | Linux | never |
| the picker's model, source and headless drawing (`PickerTest`) | the JDK | never |
| the picker's rules (`StepTest`) | the JDK | never |
| the picker clicked, typed and scrolled at, headless, around a running session: among the rest, a notch of the wheel moving the list three rows at any zoom, a touchpad's scroll moving it on the next frame and a notch turned mid-glide adding to it, a listing zoomed wider than its window scrolled across with its headings, and a modal put away by Escape (`InteractionTest`) | the JDK | never |
| the gallery's thumbnails (`ThumbnailTest`) | the JDK | never |
| the picker's menus, as values (`MenuTest`) | the JDK | never |
| the browser exploring: files opened, or offered to an application; copied, cut and pasted, with undo; made; quick access arranged, and an older version's pins read as it (`ExploreTest`) | Linux | never |
| the user's actions: commands read and filled word by word, what is refused and why, conditions, the menus they are in, their keys, and each step of doing one (`ActionsTest`) | the JDK | never |
| places: what a command prints read into a tree, files told from entries, and the archive tool listed, gone about, extracted from (by the menu, a drop, a paste, and to open), added to and removed from, on real archives (`PlacesTest`) | Linux | without `tar`, `zip`, `unzip` or `git`, the tests that run them |
| a drag between two real windows, made by X's own pointer (xdotool): an archive's entry dropped on a folder's window and extracted there, and a click with an unsteady hand and the other buttons dragging nothing (`DragTest`) | an X display, xdotool, and `-Pbrowser.display`: `xvfb-run ./gradlew :browser:test --tests prism.browser.DragTest -Pbrowser.display` | unless asked for |
| drags on native Wayland, end to end: a file from one prism to another (its picture under the pointer), between two windows of one prism, and an archive's entry out of its window, by a pointer of wlroots' virtual-pointer protocol on a headless sway; and a file opened in an editor running already, brought forward by the token prism gave (`tools/wayland/check.sh`) | sway, grim, python3 with venv, libwayland-dev; the app image built | unless asked for |
| a real window of the window module's, clicked, typed in, dropped on from another program and copied from, through SDL's own events (`WindowTest`) | a display, X11's or Wayland's, and `-Pwindow.display`: `xvfb-run ./gradlew :window:test -Pwindow.display` | unless asked for |
| an index of a tree that was given, held and searched as one read from disk (`GivenTest`) | the JDK | never |
| every shape of entry (file, folder, nested, empty, several, names with `[x]`, `*`, `?`) brought out of every kind of archive, with and without folder entries, by every program that reads it; and removed from every kind that can be changed (`EntriesTest`) | Linux | each case, without the programs it is of |
| what a notice says, selected with the pointer and copied with Ctrl+C, in a real window with the system's clipboard (`SelectionTest`) | a display, and `-Pbrowser.display` | unless asked for |
| the file chooser backend and the file manager served on a bus of the tests' own, called by prism's library and by GLib's `gdbus`: requests read, choices answered, the portal's Close, names owned and replaced (`FileChooserTest`) | dbus-daemon, and gdbus for its case | without them |
| a portal request made into a picker, and the picker's ending into the answer; install's edits of `portals.conf` and its files (`PortalTest`) | the JDK | never |
| the archives that ship: each kind listed, extracted whole and by entry, searched, and changed, by each program that can, made the only one installed in turn; archives made; single files decompressed; and a kind none of whose programs is there (`ArchivesTest`) | Linux | each case, without the programs it is of |
| chords read and written, where each action acts, the user's bindings, and clashes (`BindingsTest`) | the JDK | never |
| the preferences in memory and in a watched file, written by another process, unreadable and kept aside (`preferences`) | Linux | never |
| file types, desktop entries, `Exec` lines, defaults in `mimeapps.list`, places, and a real launch left detached and given its activation token (`desktop`) | Linux, `setsid` | never |
| the explorer's windows around real sessions, properties, the application chooser, the preferences' edits merged on saving (`ExplorerTest`, `PropertiesTest`, `OpenWithTest`, `SettingsTest`) | the JDK | never |
| the preferences window clicked, typed and mouse-pressed at, headless: the bindings found by words (Ctrl+F), and by a shortcut pressed in "Find Shortcut", which takes keys only while it is open; a listing found by its name, a gallery's filters set apart from a list's, a column aligned, and a picker given its own; a terminal found by typing its name, one not installed said so, and Custom's lines shown (`SettingsViewTest`) | the JDK | never |
| drops: the default action's rule, the line under the listing, files read from what another application drags, drops on folders, an explorer's listing and a picker's, links on disk, undo, volumes told apart (`DropTest`) | Linux | never (the second volume, `/dev/shm`, where it is one) |
| renaming, making and removing folders, and the trash both ways, on real files (`DiskTest`) | Linux | never |
| a trash for each mount, over mounts made up of folders: the home trash, one made at a drive's top, a shared one used only where it is sticky, none on tmpfs; deleting for good, emptying, every trash's entries (`TrashTest`) | Linux | never |
| every example of the search language, against a tree indexed from disk (`SearchTest`) | Linux | never |
| the search language's text read: every example to the DSL's query, every prefix of one with nothing invalid, recovery, marks, and random text (`ParserTest`) | the JDK | never |
| the index as changes are made to it, and its rules (`IndexTest`) | Linux | never |
| the index's file and the store that keeps it (`FileTest`) | Linux | never |
| a folder reconciled, devices by mount, the rules matching a place, a query's places (`ReconcileTest`) | Linux | never |
| the live index: opening, reading again, changes, changes during a scan made again, places read for searches alone (`LiveIndexTest`) | Linux | never |
| the daemon, with a feed of the test's own, and once with the kernel's watcher, which runs or says why not (`DaemonTest`) | Linux | never (the kernel's watcher is refused in the development container, and the test sees that said) |
| search over an index the size of a desktop (`SearchBenchmark`) | `-Pindex.benchmark=N` | unless asked for |
| D-Bus's wire format and types (`WireTest`, `SerialTest`) | the JDK | never |
| D-Bus, live (`ConnectionTest`, `AppearanceTest`, `ThemeTest`) | dbus-daemon, which each test starts on a socket of its own | without dbus-daemon |
| D-Bus against other implementations (in `ConnectionTest`) | gdbus, dbus-send, busctl | each, without that tool |

In this project's development container everything runs and nothing skips. On a CI runner, the loader's tests skip
(they run as an unprivileged user) and everything else runs.

## The loader, end to end

`LoadTest` loads a small program from the DSL into the kernel the test runs on and reads it back: a BTF tracepoint on
system call entry, which sends this process's `close()` calls once the host has put this process in a table. That
covers every step the watcher takes (emitting the C, compiling it, libbpf's load and attach, a table the host
writes, the ring buffer read in place, the counters) through a hook any kernel that loads BPF at all will attach.

It needs a process allowed to load BPF: root, or one with `CAP_BPF` and `CAP_PERFMON`. To run it on your machine:

```bash
sudo -E ./gradlew :ebpf-loader:test
```

## The watcher on a real machine

The watcher's hooks are on function exits (fexit), which need the kernel's BPF trampolines and a process allowed to
load tracing programs. Check first that every hook matches your kernel; this needs no privileges:

```bash
./gradlew :app:run --args="probe check"
```

Then build the app and run the watcher with the privileges it needs, for thirty seconds:

```bash
./gradlew :app:createDistributable
```

```bash
sudo app/build/compose/binaries/main/app/prism/bin/prism probe watch --for 30
```

and, in another terminal, make changes for it to see:

```bash
mkdir /tmp/prism-test && touch /tmp/prism-test/a && mv /tmp/prism-test/a /tmp/prism-test/b && rm -r /tmp/prism-test
```

Each line it prints is a change: what happened, the device, the directory's inode, the name, and the process that
made it. A hook the kernel refuses is listed as refused, with why; one whose function has moved is listed as left out,
with what moved.

## Pictures of the picker

The picker's tests draw it headless (skiko's software renderer) in both palettes and for every kind of picker. To
keep the pictures:

```bash
./gradlew :browser:test -Pbrowser.screenshots=build/shots
```

The explorer's tests draw its Renderer page the same way, for a GPU whose OpenGL library is missing and for one that
draws:

```bash
./gradlew :explorer:test -Pexplorer.screenshots=build/shots
```

To draw it over a real folder:

```bash
./gradlew :app:run --args="snapshot $HOME --out build/home.png"
```

## The picker in a real window, without a display

`prism pick` can be run in a real window on a virtual X display, and driven from the command line. With Xvfb and
xdotool installed (`sudo apt-get install xvfb xdotool`), build the app and start a display:

```bash
./gradlew :app:createDistributable
```

```bash
Xvfb :99 -screen 0 1280x800x24 &
```

then open the picker on it, with what it prints kept:

```bash
DISPLAY=:99 app/build/compose/binaries/main/app/prism/bin/prism pick ~ > picked.txt &
```

With no window manager, the window has the keyboard only once it has been clicked, so click a row first, then use the
keys, and look at the screen as it goes:

```bash
DISPLAY=:99 xdotool mousemove 350 211 click 1 && DISPLAY=:99 xdotool key Down Return
```

```bash
DISPLAY=:99 import -window root screen.png
```

Drags from another application need a window manager on the display: X's drag and drop (XDND) finds the window under
the pointer by the `WM_STATE` property a window manager gives each application's window, so with none, a drag from
another application finds no target. A drag between prism's own windows needs none: prism finds the window itself. Any will do; twm, placing windows
where they ask, is the smallest:

```bash
printf 'UsePPosition "on"\nRandomPlacement\nNoTitle\n' > twmrc && DISPLAY=:99 twm -f twmrc &
```

A drag is xdotool's button held while the pointer moves in steps (a jump is no drag), with a key held for an action:

```bash
DISPLAY=:99 xdotool mousemove 334 211 keydown ctrl mousedown 1 mousemove 350 190 mousemove 370 160 mousemove 380 153 mouseup 1 keyup ctrl
```

`picked.txt` then holds the path chosen. The OpenGL warning skiko prints on a display without it goes to standard
error, so standard output holds only the paths.

The mouse's side buttons can only be checked this way, since the picker reads them from AWT rather than Compose
(docs/picker.md, "The mouse's side buttons"). X numbers them 8 and 9; after going into a folder,

```bash
DISPLAY=:99 xdotool click 8
```

goes back, and `click 9` forward again, while `click 6` and `click 7` (a sideways scroll) do nothing.

Real keys are worth it for a chord with Ctrl in a text field, since X sends the character the chord types as well,
which Compose's test keyboard does not. With the search open (Ctrl+F) and something typed in it, Ctrl+2 should switch
to the gallery and leave the search as it was:

```bash
DISPLAY=:99 xdotool key ctrl+f && DISPLAY=:99 xdotool type p && DISPLAY=:99 xdotool key ctrl+2
```

Ctrl and the wheel is `keydown ctrl`, `click 4` (up, larger) or `click 5`, then `keyup ctrl`. With no window manager,
move the pointer onto the window and wait a moment before turning the wheel, after another window over it has closed:
SDL hears the wheel only over a window it has seen the pointer enter. To see the gallery fit a
smaller window, resize the picker's window (there is one: `xdotool search --name Open`):

```bash
DISPLAY=:99 xdotool windowsize --sync "$(DISPLAY=:99 xdotool search --name Open)" 640 420
```

To try the trash without touching the real one, start the picker with `XDG_DATA_HOME` pointing somewhere temporary;
the trash is then `$XDG_DATA_HOME/Trash`.

To try the search of the index, index a folder into a file of its own and give the picker that file:

```bash
app/build/compose/binaries/main/app/prism/bin/prism index --file /tmp/idx/index.prism ~/Documents
```

```bash
DISPLAY=:99 app/build/compose/binaries/main/app/prism/bin/prism pick --index /tmp/idx/index.prism ~ > picked.txt &
```

Then, once a click has given the window the keyboard, Ctrl+Shift+F, a query typed as fast as a person types, Enter to
show every match in the listing, and the arrows and Enter to go to one:

```bash
DISPLAY=:99 xdotool key ctrl+shift+f && DISPLAY=:99 xdotool type --delay 30 "report -ext:pdf"
```

```bash
DISPLAY=:99 xdotool key Return
```

```bash
DISPLAY=:99 xdotool key ctrl+shift+f Down Down Return
```

`--index` with a file that is not there says so on standard error, and the picker opens without a search.

## The C the DSL emits

To keep the C the DSL's tests emit, to read or to compile by hand:

```bash
./gradlew :ebpf:test -Pebpf.emit=build/emitted
```

and the watcher's own:

```bash
./gradlew :app:run --args="probe c"
```

## The portal, end to end

`tools/portal/check.sh` runs prism as the desktop's file chooser with the real pieces, in a home of its own on a
virtual display: installed; an application (`tools/portal/ask.py`, as GTK asks) asking the real xdg-desktop-portal for
a file; D-Bus starting prism from the service file install wrote; a file double-clicked in prism's picker; the
application answered with it. Then the file manager is asked to show a file, and everything is uninstalled. It says
PASSED or FAILED, and leaves screenshots of the picker and the explorer:

```bash
./gradlew :app:createDistributable
```

```bash
tools/portal/check.sh
```

It needs Xvfb, dbus-daemon, xdg-desktop-portal, xdotool, xprop and xwininfo, ImageMagick's `import`, and Python with
GLib's bindings
(`PYTHON=python3.12` where the default Python is not the one they were built for).
