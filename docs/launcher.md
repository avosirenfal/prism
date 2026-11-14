# The launcher

`prism launcher` shows a field in the middle of the screen. Type a few letters, and under the field are the
applications that fit them, then the files that do; Enter starts or opens the one the arrows are on, and the launcher
is gone. It is prism's answer to Vicinae and Raycast, run by the daemon, with the daemon's index to search.

![The launcher, with something typed](launcher.png)

## Using it

Bind `prism launcher` to a key in your window manager (below). Press the key, and:

- **Nothing typed** lists every application installed, the ones you start most and most lately first, then the two
  things prism itself does from here ("Files", a window on your home folder, and "Settings").
- **Typing** narrows the list as you type. `fire` finds Firefox by its name; `browser` finds it by what kind of program
  it says it is; `www` by a word it asks to be found by; `vsc` finds Visual Studio Code by the first letters of its
  words. With several words, every one must be found somewhere.
- **Files** come after the applications, under their own heading: what the index holds that fits what is typed, read
  in the search's own language (docs/search.md), so `report .pdf`, `~/Documents/`, `new:1d` and the rest all work
  here.
- **The arrows** move through the results, the list scrolling to keep the one they are on in sight; Page Up and Page
  Down move a page; Tab and Shift+Tab, Ctrl+N and Ctrl+P, Ctrl+J and Ctrl+K move as Down and Up do.
- **Enter** starts an application, opens a file with whatever opens its kind, and opens a folder in an explorer's
  window. **Ctrl+Enter** shows the result in its folder instead, chosen. **Ctrl+Shift+C** copies its path.
- **Escape** empties the field, and with the field empty puts the launcher away. So does pressing your key again, and
  so does clicking another window.

Along the bottom is what the keys do to the result the arrows are on, and, where something could not be started,
why.

![The launcher as it opens](launcher-light.png)

### What is listed, and in what order

The applications are the desktop entries in `applications/` under each of `$XDG_DATA_HOME` and `$XDG_DATA_DIRS`, as
the Desktop Entry specification reads them (docs/explorer.md, "Applications, as they are listed, and their icons"):
an entry with `NoDisplay=true` or `Hidden=true` is left out, so is one whose `OnlyShowIn` does not name your desktop
or whose `NotShowIn` does, and one whose `TryExec` is not installed; an entry in your own folder takes the place of
the system's of the same name, whatever it says. Names and descriptions are in your language where the entry has them.
Each has the icon your icon theme has for it, and the first letter of its name on a tile where the theme has none.

What is typed is words, and each word counts for the best place it is found in:

| found in | counts |
|---|---|
| the application's name | most |
| what kind of program it is ("Web Browser") | less |
| the command that runs it (`code`), and the words it asks to be found by | less again |
| what it says it is for | least |

and within each, the whole of it before its start, its start before the start of a word in it, and that before
somewhere inside. An application you have started from the launcher gains a little for each doubling of its uses, each
use counting half as much every two weeks, so of two that fit equally the one you use comes first; a far better fit
still wins. Among equals, by name.

An application's other actions (a browser's "New Private Window") are listed where a word is in the action's own
name, or where what is typed is the application's whole name; always after the application itself.

What was started is kept in `$XDG_STATE_HOME/prism/launches`, a line an entry. Deleting it forgets the order and
nothing else.

## Settings

"Settings" in the launcher (or Ctrl+comma) opens them in a window of their own, made as the explorer's preferences
are: its pages down the left, the one chosen beside them, and nothing written until Apply (docs/explorer.md, "The
bindings window"). They are kept in `~/.config/prism/preferences.json` under `launcher`, and the launcher is as they
say from then on.

![The launcher's settings](launcher-settings.png)

| page | what is on it |
|---|---|
| Launcher | whether its corners are round ("Round corners", below; on as it starts), how wide it is, how many results are in sight before it scrolls, and how large it is drawn; whether files and folders are found at all, and how many are listed (30); whether what else an application can be started to do is found; whether it is shown over every window (a layer surface, where the compositor has them), or as a floating window of the window manager's; whether clicking another window puts it away |
| Key bindings | every key above, on the page that binds the explorer's: in groups, found by name or by pressing a key, a chord clicked and the next one pressed taking its place, the × taking it away, the + adding one, Reset for the defaults. A chord another of the launcher's actions has is taken from it. A key that types (a letter alone) is refused, since it would never get past the field |
| Applications | each application installed, switched off to leave it out of the launcher (it is still installed, and still opens files) |

![The launcher's keys](launcher-keys.png)

The launcher's keys are its own, kept under `launcher.bindings`, each action by its name (`Open`, `Reveal`, `Down`,
...): binding the explorer's keys changes none of them, and binding these none of the explorer's.

## Showing it

`prism launcher` is asked of the prism already running, as `prism explore` is (docs/running.md): a short-lived process
writes the daemon what was asked, the daemon shows the window, and the process ends. In the flake's package that
process is the client, compiled ahead of time (docs/shell.md, "The client"), so the asking takes a few thousandths of
a second, and what is left is the daemon making a window; its results are there with it, since the daemon read what
is installed when it last showed the launcher, keeps the pictures it drew, and has the index in memory. Asked for
while it is shown, it is put away, so one key shows and hides it.

With no prism running, `prism launcher` shows the launcher in its own process, with the applications but no index
(`--index [FILE]` gives it one read from a file), and the process ends when the launcher is put away.

### Over every window

Where the platform has a surface for such things, the launcher is one: over every window, a fullscreen one included,
in the middle of the screen, wherever the window manager would have put a window. On Wayland that is a layer surface
(`wlr-layer-shell`, which niri, sway, Hyprland, KDE and COSMIC have), in the overlay layer, named `prism-launcher`.
It is no window to the compositor: it is in no workspace, has no place in a row of tiles, and is not in a window
switcher.

What it has of the keyboard follows "Close when it loses the keyboard":

| the setting | the keyboard | so |
|---|---|---|
| on (as it starts) | the launcher's as it opens, and whatever is clicked next takes it | a click on another window, or another window opening, puts the launcher away |
| off | the launcher's alone, until it is put away | nothing else can be typed into meanwhile; Escape, your key, or starting something puts it away |

A launcher that holds the keyboard does not see a click beside it at all (it is no bigger than it looks), so it
stays; which is why one that is to close at a click shares the keyboard instead. What a compositor makes of a
surface that shares the keyboard is its own to say, and prism finds its way to the same end on each:

- **niri** gives the launcher the keyboard as it appears, and that is all there is to it.
- **sway** gives a surface that shares the keyboard nothing until it is clicked. So the launcher, not given the
  keyboard a moment after it is on the screen (150 ms), holds it, is given it, and then shares it, which sway lets
  it keep. A key pressed in that moment goes to the window that had the keyboard before; switch the setting off
  there, if that is ever lost to you, and the launcher holds the keyboard from the start.
- A compositor that takes the keyboard back the moment a surface shares it is one the launcher holds the keyboard on
  from then, as if the setting were off: a launcher that cannot be typed into is worse than one that does not close
  at a click.

`PRISM_WINDOW_LOG=1` has the daemon say which of these happened, for each launcher shown.

Where there is no such surface (GNOME's Wayland, X11), or with "Over every window" switched off in the preferences
(`launcher.overlay`), the launcher is an ordinary window with no title bar, whose size cannot be changed. A window
manager that tiles takes such a window for a dialog and leaves it floating, in the middle of the screen, at the size
it asked for; niri, sway and Hyprland all do. Its title is "Prism Launcher" (every window of prism's has the app id
`prism`), for a rule to know it by. `PRISM_WINDOW_LOG=1` has the daemon say which it was, as each is opened.

### Round corners

The launcher's corners are round ("Rounded corners", in its settings under Appearance; `launcher.rounded`, on as it
starts): a radius of 12, times the size the launcher is drawn at. prism draws them itself, so they are the same on
every compositor, and nothing of the compositor's has to be configured for them: the window is clear at its corners
(docs/window.md, "A window that is clear"), and what is behind the launcher shows there.

![The launcher's corner, enlarged: on niri, with the shadow of the rule below](launcher-corner.png)

The line round the launcher is prism's too, and follows the corner. It is drawn as the whole shape filled with the
line's colour and the launcher's inside laid over it, one line's width in and clipped to a radius one line's width
less, so the line is as thick round the corner as along the sides, and nothing of the inside (the footer's
background, the field's) shows past it or through its edge.

Round corners need a window that can be clear, and where it cannot be the launcher is square, whatever the setting
says, rather than round with black corners:

| where | the launcher's corners |
|---|---|
| Wayland, as a layer surface or as a window, drawn either way | round |
| X11 with a compositing manager (picom, xcompmgr, KWin, Mutter), drawn by OpenGL | round |
| X11 with a compositing manager, drawn on the CPU | square |
| X11 with nothing compositing the screen | square |
| the setting switched off | square |

Whether its window is clear is decided as the launcher is shown, so the setting switched on is seen the next time
it is shown (switched off, it is square at once).

What a compositor draws round the launcher is drawn round a rectangle unless it is told the shape, so it is worth
telling it. As a layer surface the launcher has no border, focus ring or title bar of the compositor's at all (they
are for windows), on niri, sway or any other; only a shadow, if one is asked for. As a window ("Over every window"
off) it has whatever the compositor gives a floating window.

### niri

```kdl
binds {
    Mod+Space { spawn "prism" "launcher"; }
}

// A shadow under the launcher. It is a layer surface, so it is a layer rule that finds it, by its name.
// The radius is the launcher's own, so the shadow is round where the launcher is: without it the shadow
// is a rectangle's, and its square corners show past the launcher's round ones.
layer-rule {
    match namespace="^prism-launcher$"
    geometry-corner-radius 12
    shadow {
        on
    }
}
```

The radius is 12 times the launcher's size in its settings (12 at 100%, 15 at 125%). niri rounds nothing of a layer
surface itself (`geometry-corner-radius` in a layer rule shapes the shadow only), which is why prism draws the
corners. With "Over every window" off, the launcher is a window, and a window rule finds it:

```kdl
window-rule {
    match app-id="^prism$" title="^Prism Launcher$"
    geometry-corner-radius 12
    clip-to-geometry true
    shadow {
        on
    }
    // niri's focus ring and border follow the radius above. The launcher has a line of its own, so they
    // can as well be left off it:
    focus-ring {
        off
    }
    border {
        off
    }
}
```

Without `geometry-corner-radius`, niri's focus ring (and its border, if you have one on) is drawn square round the
window, and its corners are seen past the launcher's round ones; with it, the ring follows the launcher's shape.
Both rules are as `niri validate` accepts them (26.04), and both were looked at on it: the shadow round the layer
surface, and the ring round the window.

On NixOS with the module (docs/nixos.md), `prism` is on the path and the daemon is the user service `prism.service`,
so the bind is all there is to add.

### Others

- sway: `bindsym $mod+space exec prism launcher`. sway draws nothing round a layer surface and has no shadows or
  round corners of its own, so there is nothing to tell it. Should it draw a border round the launcher as a window
  ("Over every window" off), `for_window [app_id="^prism$" title="^Prism Launcher$"] border none` takes it off.
- Hyprland: `bind = SUPER, space, exec, prism launcher`
- GNOME, KDE: a custom shortcut whose command is `prism launcher`

## How it is made

The launcher is a module of its own (`launcher`), made as the browser is:

- **`LauncherState`** (`model.kt`) is everything: what is typed, what is installed, what was started, what the index
  found, which result the arrows are on. What is shown is worked out from it (`sections`), so nothing shown can be out
  of step with it.
- **`step`** (`step.kt`) is a pure function from a state and an event to the next state and what is to be done: start
  this, search for that, close. It starts nothing itself.
- **`LauncherSession`** (`session.kt`) does what `step` asks, off the thread the window is drawn on, and sends back
  what came of it as an event.
- **`LauncherView`** (`view.kt`) draws a state. The list under the field is the browser's `ResultList`, which the
  search of the index in an explorer or a picker is drawn with too: rows of one height, one of them highlighted, the
  list scrolling to keep it in sight while the keyboard stays in the field.
- **`IconImages`** (`icons.kt`) draws an icon from the file the desktop's icon theme has for it, a PNG or an SVG, at
  the size asked for, once.

The launcher's window is one of the explorer's (`ExplorerWindow.Launching`), so it is opened, closed and, if it ever
fails, reported and replaced by a window that says what happened, as every window is; a launcher that fails takes
nothing else with it.

## Tested

- `LauncherStateTest`: what is found for what is typed and in what order, actions, hidden applications, uses, files
  asked for and stale answers dropped, the arrows' ends, what Enter, Escape, Ctrl+Enter and the copy do, launches kept
  in a file and halved by time, and its keys as the preferences write and read them.
- `LauncherViewTest`: the launcher composed and typed into as a user would, over the tests' desktop and a real index
  of a folder: Enter starts the application, a file is opened with what opens it, a folder in a window, a failure is
  said, forty results scroll under the arrows; an icon drawn from a PNG and from an SVG; and the launcher drawn, in
  both palettes, with whatever this machine has installed and its icon theme's pictures.
- `KeysTest` (explorer): the launcher's settings changed in their window and written only once applied, its keys
  rebound on the explorer's own page, one that types refused, an application switched off; and the pages drawn.
- `ExplorerTest`: the launcher shown, put away by being asked for again, following its settings as they change,
  opening its settings and a folder's window.
- `tools/daemon/check.sh`: `prism launcher` asked of a running daemon shows a window titled "Prism Launcher", and
  asked again puts it away.
- `tools/launcher/check-wayland.sh`: the launcher on a real compositor (sway, with no screen; or, with `--here`,
  the session it is run in), looked at by screenshots and typed into as a keyboard would: it is in the overlay layer
  and no window of the compositor's, in the middle of the screen, typed into, Enter starts what was found, the same
  key and Escape put it away, it is drawn over a window that fills the screen, an explorer's window is a window
  still, and with `overlay` off it is a window. Its corners are read off the screenshots: the pixel at the launcher's
  very corner is what is behind it (the screen's background, and then the terminal that fills the screen), the pixel
  just inside is the launcher's, and with `rounded` off the corner is the line's colour. Run on sway 1.9 and on niri
  26.04, each with prism drawing on the CPU and with OpenGL.
- `CornersTest`: corners are round only where they are asked for and the window is clear; and the launcher drawn
  with no display, in both palettes at 1x and 2x: its corners clear, the line unbroken along the arc, and every pixel
  that is partly clear the line's colour and nothing else's (no rim of the inside past the line).
- By hand, under Xvfb: with nothing compositing, the launcher is square and its corner the line's colour, not
  black; with xcompmgr, it is round drawn by OpenGL (the corner is xcompmgr's background) and square drawn on the
  CPU.

## Not yet

- **A shadow of its own, and a click outside.** A layer surface is exactly as large as the launcher: a shadow is the
  compositor's to draw (niri's rule, above), and a click beside it is another surface's. Now that its window can be
  clear, the launcher drawn larger than it looks, with what is around it clear, would have a shadow of its own on
  every compositor, and would see the click that should put it away.
- **Round on X11 drawn on the CPU**, and on macOS and Windows: a window there cannot be clear yet (docs/window.md,
  "Not yet"), so the launcher is square.
- **Kept warm.** The window is made each time it is shown; kept hidden between, it would be there sooner still.
- **More to find.** A calculator, windows to switch to, the clipboard's history: what Vicinae and Raycast do beyond
  applications and files.
- **Icons for files** from the icon theme; they have prism's own, as a listing's rows do.
