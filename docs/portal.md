# prism as the desktop's file chooser and file manager

An application that wants the user to choose a file, on a Linux desktop today, usually does not draw a dialog of its
own. It asks the **desktop portal** (`xdg-desktop-portal`), a service on the session bus, and the portal hands the
request to a **backend**: GNOME's, KDE's, GTK's, or, once it is installed, prism's. The backend shows its dialog and
answers with what was chosen, and the portal passes that back to the application. Sandboxed applications (Flatpak,
Snap) can only choose files this way; GTK and Qt applications outside a sandbox do it too, where they are told to
(`GTK_USE_PORTAL=1`, or by default in recent versions), as do Firefox, Chromium and Electron applications.

A **file manager** is reached in a different way, which is not a portal: applications call
`org.freedesktop.FileManager1` on the session bus ("Show in folder"), and whichever program owns that name answers.
What opens a folder from `xdg-open` and from a desktop's menus is the desktop entry that is the default for
`inode/directory`.

prism does all three. The daemon (`prism daemon`, docs/running.md) serves the file chooser backend and
`org.freedesktop.FileManager1`, one process, a window for each request, each searching the daemon's index;
`prism portal serve` is the same without the index, for where no daemon runs. `prism portal install` tells the
desktop to use it, and `prism portal uninstall` undoes that. On NixOS, the flake's module does what install does, in
the system's configuration (docs/nixos.md).

## Using it

Build the app image, which has a launcher that runs prism with its own Java:

```bash
./gradlew :app:createDistributable
```

Then install from that launcher (it says which command to run; elsewhere, give it with `--command`):

```bash
app/build/compose/binaries/main/app/prism/bin/prism portal install
```

```bash
systemctl --user restart xdg-desktop-portal.service
```

That writes, in your own folders only, and says each:

| file | what it is for |
|---|---|
| `~/.local/share/xdg-desktop-portal/portals/prism.portal` | tells the portal there is a backend called prism, which serves the file chooser on the bus name `org.freedesktop.impl.portal.desktop.prism` |
| `~/.local/share/dbus-1/services/org.freedesktop.impl.portal.desktop.prism.service` | tells D-Bus to start `prism portal serve` when the portal first calls that name |
| `~/.local/share/dbus-1/services/org.freedesktop.FileManager1.service` | the same, for the file manager's name |
| `~/.local/share/applications/prism-folder.desktop` | a desktop entry that opens folders (`prism portal show`), made the default for `inode/directory` in `~/.config/mimeapps.list` |
| one line in `~/.config/xdg-desktop-portal/portals.conf` | `org.freedesktop.impl.portal.FileChooser=prism`: prism for the file chooser, every other portal left as it was |

Nothing has to be running: D-Bus starts `prism portal serve` the first time an application asks, and it stays running
after, so the next picker opens at once. `--no-file-manager` leaves the file manager alone, `--no-file-chooser` the
file chooser, and `--dry-run` says what would be written without writing it. Install refuses to write over a file
that is there and is not prism's.

`prism portal uninstall` takes away every file prism wrote (each says so on its first line), takes its line out of
`portals.conf` and puts back what that line said before, and makes whatever opened folders before the default again.

### The preference, on older portals

The portal reads its preferences from `portals.conf` files: the user's, the system's, and the desktop's own (for niri,
`niri-portals.conf`). Since version 1.21 it reads all of them, in order, interface by interface, so a user file that
says only "prism for the file chooser" leaves every other interface to the system's file. Before 1.21 it read only the
first file it found, so that same file would hide everything else (screencasting, screenshots, the colour scheme).

So where you have no `portals.conf` of your own, install copies the one the portal reads now and changes only the
file chooser's line in the copy; the copy's first line says where it came from, and uninstall removes it whole. Where
you have one (for your desktop, or `portals.conf`), install adds its line to that.

Versions before 1.20 do not read backends from your own data folder at all; on those, `prism.portal` must be in the
system's (`/usr/share/xdg-desktop-portal/portals`), where a package would put it.

### NixOS

Current nixpkgs (`xdg-desktop-portal` 1.22) reads backends from your data folder as above, so `prism portal install`
works as it does elsewhere. To say it in the system's configuration instead, the flake has a package and a module
(docs/nixos.md):

```nix
programs.prism.enable = true;
```

which amounts, for the file chooser, to the package in `xdg.portal.extraPortals` (it holds `prism.portal` and the
service file) and

```nix
xdg.portal.config.common."org.freedesktop.impl.portal.FileChooser" = "prism";
```

One thing about that file is easy to trip on. In each folder the portal reads one file: the one for your desktop
(`niri-portals.conf`) where there is one, and `portals.conf` only where there is not. So a system that sets
`xdg.portal.config.niri` as well must say prism there too, which the module's `portal.desktops` is for.

## A request, followed through

Say Firefox wants a picture to upload. It calls the portal's `org.freedesktop.portal.FileChooser.OpenFile` with a
title, its window, and options: several files may be chosen, two filters ("Images", by MIME type; "All files"), the
button should say "Upload".

**The portal calls prism.** `portals.conf` names prism for the file chooser, and `prism.portal` names prism's bus
name, so the portal calls `org.freedesktop.impl.portal.FileChooser.OpenFile` on `org.freedesktop.impl.portal.desktop.prism`.
If nothing owns that name, D-Bus starts `prism portal serve` from the service file and holds the call until it does.
prism serves its methods before it asks for its names, so a call that arrives the moment it owns them is answered.

**The request is read** (`portal/filechooser.kt`). Every method takes the same five things: a handle (an object path),
the application's id, its window (`x11:3c00007`, or `wayland:` and a handle), a title, and options by name, each a
D-Bus variant. Each option is read as the type it should be (`multiple` a boolean, `filters` an array of names and
rules, `current_folder` a NUL-ended byte string), and one of the wrong type is taken as not given. The result is an
`Asked`: `Opening`, `Saving` or `SavingMany`, with nothing in it that could be wrong.

**A picker is made of it** (`app/chooser.kt`). An `Opening` with `multiple` is the picker that chooses several files;
with `directory`, the one that chooses a folder; a `Saving` is the one that saves, with the name it suggests; a
`SavingMany` chooses a folder, and its button says "Save here". The picker opens in the folder asked for (or your home,
where that is not a folder), titled as asked, its button in the application's words ("Upload", its mnemonic
underscore taken out). A filter by MIME type is a filter by name to the picker: `image/png` is `*.png`, and `image/*` is
every image's patterns, from shared-mime-info's database (`FileTypes.patternsOf`). A filter that comes to no patterns at
all is left out, since a filter of no patterns would show every file.

**Its window is the explorer's** (`Explorer.choose`): a picker in a window of its own, beside any explorer windows
open, with the sidebar, the search of the index, and every key as the user has bound them. Where the application's
window is an X11 one, the picker is made its dialog (`WM_TRANSIENT_FOR`), so the window manager keeps it over the
application. Over a Wayland window the picker opens as a window of its own for now: making it the application's dialog
is Wayland's `xdg-foreign` protocol, which prism's windows (native Wayland ones, docs/window.md) do not speak yet.

**The portal can take the dialog away.** For as long as the picker is open, prism serves
`org.freedesktop.impl.portal.Request.Close` at the request's handle. The portal calls it when the application goes
away, or is done waiting; prism closes the picker, and answers that it was cancelled.

**The answer** is a response (0 chosen, 1 cancelled, 2 anything else) and results: the files chosen as `file://` URIs
(`uris`), the filter in use as the application's own (`current_filter`), and each of its extra controls (`choices`) at
the value it asked for at first, since the picker does not show them. Saving several, each name asked for is placed in
the folder chosen, under a free name ("a (copy).txt") where something there has it already.

## The file manager

`org.freedesktop.FileManager1` has three methods, each given `file://` URIs:

| method | prism |
|---|---|
| `ShowFolders` | an explorer window on each folder |
| `ShowItems` | an explorer window on each item's folder, the first item asked for in it chosen |
| `ShowItemProperties` | the properties window of the items |

`prism portal show PATH...` asks whichever file manager owns the name to show what it is given, folders as folders and
files in theirs. Where no file manager answers (none installed, or D-Bus not running), it shows them as `prism explore`
does: in the prism already running, or one of its own.

What the folder entry runs is `prism explore`, given what is opened, so `xdg-open ~/Documents` opens prism: the
prism already running shows it, through the socket one prism asks another by (docs/running.md), which is quicker than
going through the bus.

`prism portal serve --no-file-manager` serves the file chooser alone. Where another file manager has the name already,
`serve` says so and serves the file chooser alone; `--replace` takes the name, where the other lets it go.

## How it is tested

- **The bus side** (`portal`'s `FileChooserTest`): the backend served on a bus of the tests' own and called as the
  portal calls it, by prism's own library and by GLib's `gdbus`, whose way of writing a request is the portal's own
  (the portal is GLib's too): a request read into what it asks, the choice answered, saving asked with a name, a
  folder and a file, the portal's `Close` cancelling the dialog, the file manager's three methods, and names owned
  and replaced.
- **Between the bus and the picker** (`app`'s `PortalTest`): what each request makes of the picker (what it chooses,
  where it opens, its title, button and window), filters by type made into filters by name, the answer from what the
  picker ended with, names placed when saving several; and install's edits of `portals.conf` and the files it writes,
  as text.
- **End to end**, with the real pieces (`tools/portal/check.sh`): prism installed into a home of its own; an
  application asking the real `xdg-desktop-portal` for a file, as GTK asks it (`tools/portal/ask.py`); D-Bus starting
  prism from the service file install wrote; prism's picker shown, made a dialog of the window the application
  named, and a file in it double-clicked; the application answered with that file and the filter in use. Then the file manager asked to show a file, the explorer opened on its
  folder with it chosen, and everything installed taken away. It needs Xvfb, dbus-daemon, xdg-desktop-portal, xdotool,
  xprop and xwininfo, ImageMagick and Python's GLib bindings; it passed in the development container with xdg-desktop-portal 1.18.4.

## Not yet

- **Wayland parents.** A picker over a Wayland application is not made its dialog: the application's handle is an
  `xdg-foreign` one, which prism's windows, native Wayland ones now (docs/window.md), are to import and be made the
  child of; SDL does not do that for them yet.
- **Choices.** The extra controls an application asks for (a list, a check box) are not shown; each is answered at the
  value it asked for at first.
- **A package for other distributions.** The flake builds one for Nix (docs/nixos.md); nothing installs
  `prism.portal` system-wide elsewhere yet, where `prism portal install` is the way.
- **Other portals.** prism serves the file chooser alone; every other portal stays with the desktop's backends.
