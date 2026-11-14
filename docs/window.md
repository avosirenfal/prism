# Windows

prism's windows are SDL3's, not AWT's. A window is a native one on whichever platform it opens: a Wayland toplevel
under a Wayland compositor (niri, sway, GNOME, KDE), an X11 window under X, a Win32 window on Windows and a Cocoa one
on macOS. One code path serves all four: the `window` module, which hosts Compose in SDL's windows. Nothing in it is
prism's, so it can be lifted out whole for another program (a game's UI, say) that wants Compose in a window of its
own.

## Why not AWT

Compose for the desktop draws its windows through AWT, and the JDK's AWT speaks X11 alone on Linux: under a Wayland
compositor every Compose window is an X11 window, through XWayland (on niri, xwayland-satellite). That cost prism:

- **Keys lost**: AWT keeps the keyboard in a window of its own inside each window, and a compositor that focuses the
  window itself (niri does) gives AWT nothing it reads as focus, so typing went nowhere until a click.
- **Drags lost**: a compositor that does not tell X where windows are (niri, again) leaves AWT unable to find the
  window under a drag, so a drag from one prism window to another was dropped on nothing.
- **The mouse's side buttons**: the JDK renumbers X's buttons on Linux, and Compose then reports a sideways scroll as
  back and forward and the side buttons as nothing.
- **Blurry at fractional scales**, as everything through XWayland is.

prism had a workaround for each. With native windows, none of them is needed, and all of them are gone.

JetBrains' runtime has a Wayland version of AWT (WLToolkit), which IntelliJ uses by default since 2026.1. Compose
cannot draw on it yet: skiko binds its drawing to an X11 window handle, which WLToolkit has not got. A window of
Swing's with a `ComposePanel` in it can, drawing on the CPU only; SDL's draws on the GPU, and its one way serves every
platform.

## How a window works

**The thread.** `application { }` runs on the thread that calls it, which is the windows' thread until it returns:
SDL wants its windows and events on one thread (on macOS, the process's first), and Compose wants a scene's
composition, input and drawing on one thread. Work from other threads comes through `Ui`, a coroutine dispatcher that
queues it and wakes the loop out of its wait for events with an event of its own.

Compose for the desktop takes AWT's event thread for that one thread, and leaves work there for its scenes: what a
layout moved is told to whoever asked, and the scene's list of where every node is gets tidied, by a timer of AWT's a
frame's time later (`RectManager`, through skiko's `MainUIDispatcher`), unless the layout's own end got to it first.
A scene on any other thread would have that run beside it. So prism makes AWT's event queue one of its own
(`AwtEvents`, in `ui.kt`), which gives each event to the windows' thread, to run between its own events, and waits for
it; with no windows' thread, AWT's runs them as it would. See "When a frame fails" for what it did before.

**A frame.** Each window has a Compose scene (`CanvasLayersComposeScene`) and its recomposer (`FrameRecomposer`, as
Compose 1.12 has platforms drive frames). When the scene says something changed, the window asks for a frame; the
loop then runs the recomposer's frame, lays the scene out and draws it. A window with nothing to change draws nothing,
and the loop sleeps until an event comes.

**Events, a frame's worth at a time** (`queue.kt`). Between frames the loop takes the events queued when it looks, and
no more: what comes while they are handled waits for the next frame. Of the pointer's moves one after another (the same
mouse, over the same window, with the same buttons held) only the last is told, since each says only where the pointer
is now. A mouse can report a thousand moves a second, and a move can take longer than a millisecond to answer: Compose
lays a list out again within the move that scrolls it, so dragging a long list by its bar lays out every row the bar
brings into sight. Told each move, the loop fell behind for as long as the mouse moved, and drew nothing meanwhile; told
the last of each run, it lays the list out once a frame, where the pointer is.

**Drawing, with nothing copied.** Two ways, chosen once, before the first window:

| | where it draws | when |
|---|---|---|
| the GPU | Skia's OpenGL backend, into the window's own framebuffer, which is then swapped onto the screen | there is a GPU, integrated or not |
| the CPU | Skia's CPU backend, into memory the platform presents as it is: on Wayland, shared-memory buffers of prism's own, attached to SDL's surface (`WaylandPixels`); on X11, SDL's shared-memory image; on Windows and macOS, SDL's window surface | there is no GPU, OpenGL cannot be had, or OpenGL runs on the CPU (llvmpipe and the like, which Skia's own CPU drawing beats) |

SDL has no way of its own to put a CPU's pixels on a Wayland window: it would draw them through a GPU renderer (EGL
again), copying them into a texture first. So on Wayland prism speaks to the compositor itself, through
libwayland-client on SDL's own connection: it binds `wl_shm` on an event queue of its own (so nothing of SDL's is read
there), makes buffers in a memfd that Skia draws into, and attaches, damages and commits them to the surface SDL made,
using a buffer again once the compositor has let it go. On X11, SDL is told to give its shared-memory image to the X
server as it is, rather than first trying a GPU renderer.

Which one is found by a hidden OpenGL window, asked for its renderer's name. `PRISM_DRAWING=gpu` or `cpu` (or
`-Dprism.window.drawing=`) says otherwise. OpenGL's library is loaded once, before that window, and kept for as long
as windows are drawn with it, rather than unloaded with the hidden window and loaded again for the first real one, as
SDL would.

NVIDIA's driver (580) threads a process's OpenGL of its own accord where the process loads LWJGL (an application
profile of the driver's), and under Wayland its EGL display then goes bad once the first context is made: every EGL
call that needs the display after it fails with `EGL_BAD_DISPLAY`, so every window fell back to the CPU. On Linux,
prism turns that threading off (`__GL_THREADED_OPTIMIZATIONS=0`) in its own environment, where the driver reads it,
before SDL starts; a value the user set is kept. Nothing but NVIDIA's driver reads it.

Each window then makes OpenGL and Skia's context of its own; where it cannot,
it is drawn on the CPU, and says why (`Drawn.FellBack`): on standard error, whatever the log says, and on the Renderer
page, since a window that should be drawn on the GPU and is not costs the CPU dearly while it scrolls. Skia is given OpenGL's functions as SDL finds them, so it draws with
whichever of EGL, GLX, WGL or CGL made the context; skiko's own lookup is GLX's alone, and finds nothing under Wayland's
EGL.

**Why a window is drawn as it is** (`graphics.kt`, `sysfs.kt`, `libraries.kt`). Drawing on a GPU takes three things,
and the preferences' Renderer page looks at each, for each graphics device, so that a user can see why the GPU is not
used without knowing where to look:

1. A driver in the kernel bound to the device. On Linux, the display controllers on the PCI bus are read from sysfs
   (`/sys/bus/pci/devices/*`, class `0x03`), each with the driver its `driver` link names and that module's version;
   those on no PCI bus (a system on a chip's) from `/sys/class/drm`. Each is named from the PCI ids' list where the
   system has one. A device with no driver, or one handed to a virtual machine (`vfio-pci`), goes no further.
2. Its nodes, open to this user: its DRM nodes (`/dev/dri/renderD128`), and NVIDIA's own (`/dev/nvidiactl`, and the
   device's, by the minor number NVIDIA's driver gives it under `/proc/driver/nvidia`). A node the user may not open
   is said to be, with the group that may.
3. The library through which OpenGL reaches the driver, loaded as the system's loader loads it (`dlopen`), so that one
   that cannot be is said not to be in the loader's own words, and one that can is said to be from where (the
   process's own map of its files). On Linux these are glvnd's names, which every distribution has: the loader
   (`libEGL.so.1` under Wayland, `libGL.so.1` under X11), and the driver's vendor library (`libEGL_nvidia.so.0` or
   `libGLX_nvidia.so.0` for NVIDIA's driver, `libEGL_mesa.so.0` or `libGLX_mesa.so.0` for every other). NVIDIA's
   libraries are of one version with its driver and their files say which, so a library of another version than the
   driver (after an update, before a restart) is said to be.

The first of these missing is the device's verdict; a device that OpenGL draws on is drawing, whatever else is said of
it, unless the window could not be drawn with it, which is said instead, with the reason the window was given. A device
the PCI ids' list does not name is named as OpenGL's renderer names it, where OpenGL runs on it. What draws the windows is said in one (`Summary`): the GPU, as OpenGL found it; or the CPU, and why, by the device
held back that got furthest toward drawing, since what stopped it is all that is left in the way. Where devices are
found and which libraries a driver draws through are each platform's (`GraphicsDevices`, `DriverLibraries`); only
Linux's are looked for yet, and the others say so. Loading is `dlopen` on Linux and macOS, `LoadLibrary` on Windows.

**Keys.** Compose for the desktop knows a key by AWT's key code and where on the keyboard it is (`Key.ShiftLeft` is
AWT's Shift on the left), reads text typed from AWT's "typed" events, and asks an event for its modifiers as AWT keeps
them. So SDL's keys are made into AWT's key events, which are only values: no window or display of AWT's is ever
opened, and the app runs with `java.awt.headless=true` to be sure of it (its event queue still runs, headless or not,
and is prism's: "The thread", above). Every key handler, text field shortcut and binding of prism's then
works as it did. A key is known by what it types on the user's layout where that is a Latin letter, digit or mark, so
Ctrl+Z is the key marked Z on AZERTY too; on a layout of other letters (Cyrillic, Greek), by where it is, as browsers
do. Text typed comes from the platform's input method.

**The pointer.** SDL names the mouse's buttons as the platform does, side buttons included, and they reach Compose as
its Back and Forward. Pointer icons, scrolling (SDL's up is Compose's negative), and the window's scale (a dp is the
display's scale of a pixel, which a point may be only part of, as on Wayland and macOS) are as Compose's own windows
have them.

**The clipboard** is by MIME type, as every platform's is underneath: what is copied is offered in each of the types
given, and what is there read in whichever type a reader wants. prism's files go as a URI list, GNOME's
`x-special/gnome-copied-files` (which says whether they were cut) and plain text. Compose's own clipboard, which text
fields copy and paste through, is over it. A popup is given AWT's clipboard by Compose whatever its window provides, so
prism's popups provide the window's again (`WindowLocals`).

**Drag and drop.** A place that takes drops is a `dropTarget` modifier; a window keeps its places and their bounds,
and tells the innermost one under a drag. Files dragged in from another program come as SDL's drop events, through the
platform's own protocol (XDND, Wayland's data device, OLE, macOS's dragging).

A drag out is the platform's protocol to speak (`NativeDrags`), which SDL does not: on Wayland, prism speaks it
(`WaylandDrags`), and on X11, Windows and macOS not yet, where a drag goes between prism's own windows by where the
pointer is on the screen. On Wayland a drag is a `wl_data_source`, started by `wl_data_device.start_drag` from the press
that began it, offering files as a URI list (alone: SDL, offered both, asks for the text and reads it as URIs, and a drop
on a program of SDL's came to nothing) or, for what is not files (a place's entries), their names as text. The
compositor carries it to whatever window it is let go over: another program's, which asks for the URI list; or one of
prism's own, of this process or another, which SDL tells of it as of any drop. A window of this process knows the drag
for its own and is given what it carries as itself.

It is spoken through libwayland-client, on SDL's connection, on an event queue of its own dispatched by a thread of its
own (`WaylandSeat`), for two reasons. A drag must be started with the serial of the button press that began it, which
SDL keeps to itself; so the seat is bound here too (asked for its pointer and keyboard only once it has them), and
their presses heard. And a window of this process that a drag is dropped on is SDL's, which reads what is dropped at
once, on the windows' thread, waiting for the source to write it: the source's writing it is on the other thread, so
neither waits for the other.

A drag shows two things as it goes, as other file managers' do. The first is a picture under the pointer
(`DragPicture`, which the browser draws: the icon of the row taken, and how many go with it on a badge). It is the
drag's icon: a surface of its own given to `start_drag`, drawn into shared memory at the window's density, its top left
corner at the pointer, as thunar puts a file's icon. The second is the pointer's cursor, which says what letting go
would do: a copy, a move, a link, or nothing taking it there (`no-drop`). It follows the action the compositor chose
for a drop on the window under the pointer (`wl_data_source.action`), as the protocol has it, and GTK's does: none
over a window that takes nothing, or over no window. Whether the window takes what is offered at all
(`wl_data_source.target`) is not followed, since Smithay's compositors (niri) never send it: a cursor waiting for it
showed `no-drop` over every program there. Over one of prism's own windows the place under the pointer says
(`DropTarget.effect`), since SDL takes every drag it is given as a copy, whatever prism will make of it. The cursor is set through cursor-shape (`wp_cursor_shape_device_v1`) on
prism's own pointer, which compositors built on Smithay (niri) let the program a drag began in do while it goes;
others (GNOME's, KDE's) draw their own cursor for a drag from what was chosen, and sway leaves the pointer as it was.

## Bringing forward what prism starts

A compositor does not let a program take the screen of its own accord: a window that asks to be brought forward is
brought forward only where the user asked for it. On Wayland that is said by a token (xdg-activation). The program the
user pressed something in asks the compositor for one, from the serial of that press and the window it was in, and
gives it to the program it starts in `XDG_ACTIVATION_TOKEN`; that program asks with it for its window to come forward.
A program running already, given a file by a new start of itself (Kate, with one window for many files), hands the
token to its window, which comes forward. Without a token, the file opens in a window behind prism's, and nothing
says it did.

So every program prism starts (an application for a file, a terminal, an action) is given a token of its own
(`activationToken`, asked for by the desktop's launcher for each start, on the windows' thread, and waited for for
half a second at most). It is made from the user's last press of a key or a button in prism, and the window it was in,
if that is open still: niri checks the press is newer than the keyboard's coming into prism, wlroots that the window
has the keyboard, and sway brings nothing forward by a token that names no window (it only marks it as wanting
attention). The token goes in `DESKTOP_STARTUP_ID` too, as GLib's launchers do on Wayland, for programs that read that
name; and a token prism was started with is never passed on, since it was used. The protocol's tables, which
libwayland-client does not have built in, are written out in `waylandprotocols.kt`, as wayland-scanner would make them.

**A window that fails.** Each window's scene and recomposer are its own, so what goes wrong in one window is that
window's alone. What is thrown while it is composed for the first time, composed again, laid out, drawn, or handed an
event is caught around that one window's frame or event; what is thrown later, in an effect or a coroutine of its
content (on whatever thread), comes to a handler in that window's own recomposer. Either way it is told, once, to
whoever opened the window (`Window`'s `onFailure`), and from then on the window draws nothing more and takes no more
events, since a scene that threw halfway through a frame is not one to give another; it is closed as any window is,
by being composed no longer. A window opened with no `onFailure` has nobody to tell, and its failure is the
application's: `application` ends, throwing `WindowsFailed`. So does a failure in the composition of windows itself,
which every window is opened from. prism's explorer gives every window an `onFailure` (docs/running.md).

Closing the last window does not end the application: which windows are open, and whether the application goes on
with none, is the application's to say (SDL's `QUIT_ON_LAST_WINDOW_CLOSE` is turned off), which is what lets a daemon
stay with no window open. SDL's quit event is then only the platform's own: a display that went away.

**Being given the keyboard.** A Wayland compositor gives a new window the keyboard only when it is shown to have been
asked for, by a token (`XDG_ACTIVATION_TOKEN`) a launcher gives the program it starts. A window opened by one process
for another has the other's token, so `Window` takes one (`activation`), and puts it where SDL looks as it shows the
window.

## A window that floats

`Window(resizable = false)` makes a window whose size the user cannot change. To a window manager that tiles (niri,
sway, Hyprland), a window that says its smallest and largest sizes are the same is a dialog: it is left floating
over the tiles at the size it asked for, rather than made a tile. The launcher's window is one (docs/launcher.md).

## When no window opens

`PRISM_WINDOW_LOG=1` says on standard error which display server SDL speaks to, what OpenGL's renderer is (or that
there is none), how windows are drawn, and with what. A window that cannot be drawn at all fails, with SDL's reason,
as any window that fails does (above), rather than staying empty: a Wayland window shows nothing until it is given a
buffer.

On NixOS, NVIDIA's EGL and GL are in `/run/opengl-driver/lib`, which glvnd finds them in by name; a program that is not
NixOS's own (a JVM loading SDL from LWJGL) finds them only with that folder on `LD_LIBRARY_PATH`, which the flake's
shell puts there. Without it only Mesa's EGL is found, which cannot drive an NVIDIA card (`libEGL warning: egl: failed
to create dri2 screen`), and prism draws on the CPU.

## When a frame fails

Compose's own errors in layout say little of where they are: "LayoutNode 1309 not found in RectList" names a node by its
id, and its stack says only which kinds of layout it passed through. A frame that throws is said, on standard error,
with what it was laying out (`framereport.kt`): the node the error names, found in the window's layout trees, and each
node above it, with its id, whether it is placed, its size and its modifiers (`Padding`, `GraphicsLayer`, a test tag by
its tag), and the node's own children. That is enough to tell which composable it is. The window then fails as it
would have; the report only reads.

Compose gives no public way down its layout tree. The window keeps the trees as Compose makes and disposes of them
(`PlatformContext.semanticsOwnerListener`), and reads a node's children by the name it answers to as Compose's
semantics tree (`getChildrenInfo`), through reflection; where Compose stops answering to that, the report says it
cannot find the node, and gives the error.

This came of a crash opening the preferences window's Layout page, in Compose 1.12's bookkeeping of where each node is
(a node placed only to measure its baseline was taken for placed). 1.12.2 fixed half of it and 1.13.0-beta02 the rest;
prism is on 1.13.0-beta02 for that, and its window's platform context is Compose's own (`PlatformContext.Empty`) for
what prism's does not do itself.

The same words came once more, opening a file picker on a folder, and were prism's own doing. That bookkeeping (a
`RectList`: every placed node's rectangle, in two arrays) is tidied a frame's time (16 ms) after a layout moves
anything, on AWT's event thread, unless the layout's end tidies it first; tidying copies the list from one array to
the other, leaving out what was removed, and swaps them. A window's first layout of a whole listing takes longer than
a frame, so the timer came due in the middle of it, and on AWT's thread the tidying ran beside the layout on the
windows' thread: a row the layout put in the list while it was being copied was in the array thrown away, and the
row's first cell, placed next and looking its parent up, did not find it. Whether it happened was a matter of
microseconds, so it was rare, and most likely in a window's first frames, which are its slowest. AWT's events run on
the windows' thread now ("The thread", above), where nothing runs beside a layout.

## Not yet

- **Dragging out to other programs on X11, Windows and macOS**: XDND, OLE's `DoDragDrop` and an `NSDraggingSession`,
  each a `NativeDrags` of its own, as Wayland's is. Until then, a drag there goes between prism's own windows, and copy
  and paste is the way to other programs.
- **A drag's picture on X11, Windows and macOS**: drawn only where prism speaks the platform's drag (Wayland's).
- **Bringing forward what prism starts, on X11**: a startup notification (`DESKTOP_STARTUP_ID`, with the time of the
  user's last press, which SDL does not give), which window managers there check as Wayland's compositors check a
  token.
- **The drag's cursor without cursor-shape**: a compositor without it is left to draw its own; prism does not load a
  cursor theme of its own to set one by `wl_pointer.set_cursor`.
- **Input methods' preedit**: text composed in an input method (CJK) arrives once it is committed; what is being
  composed is not drawn in the field meanwhile.
- **Wayland parents**: a picker for a Wayland application is not made its dialog yet (`xdg-foreign`).
- **Metal and Direct3D**: macOS and Windows draw through OpenGL, which both still have; Skia's Metal and Direct3D
  backends would be their own way.

## Tested

- `KeysTest`: keys by layout and by place, modifiers, typed text, and which OpenGL renderers are the CPU's.
- `AwtEventsTest`: what is left for AWT's thread (an `invokeLater`, a Swing timer) runs on the windows' thread, and on
  AWT's own when there is none; a scene whose layout takes several frames' time is told of what it moved on the
  windows' thread, after the layout, not on AWT's in the middle of it (which, with AWT's queue left alone, it is); a
  caller waiting on the windows' thread as it goes is answered.
- `WindowTest` (`-Pwindow.display`): a real window clicked, typed in, dropped on from another program, and copied
  from, through SDL's own events; six windows failing in six ways, each told of alone, beside one that goes on working;
  and a window with nobody to tell ending the application. It passes under Xvfb and under weston's headless Wayland, drawn by the GPU, by the
  CPU, and with no EGL at all (`__EGL_VENDOR_LIBRARY_FILENAMES=/nonexistent.json`); the clipboard is left out under
  weston's, which has no seat to give the input serial Wayland's clipboard asks for.
- `tools/wayland/check.sh`: prism's app image on a headless sway, as native Wayland windows, and a pointer of wlroots'
  virtual-pointer protocol pressing, moving and letting go: a file dragged from one prism to another, moved there, its
  picture under the pointer as it goes (a screenshot); a file dragged between two windows of one prism, moved there;
  and an archive's entry dragged to its folder's window, brought out there. Then a file opened in an editor that is
  running already (`editor.py`, a window of its own that asks with whatever token it is handed): a made-up token
  brings it nothing, and the one prism gave the editor's new start brings it forward, as sway says. It passes. The
  cursor a drag shows is read from prism's log, since sway does not let it be set.
- prism's `DragTest` and `SelectionTest` (`-Pbrowser.display`, X11): an archive's entry dragged by X's own pointer
  from one prism window to another and extracted there; and a failure's text selected and copied with Ctrl+C.
- By hand, in the development container: `prism explore` on X11, and as a native Wayland client under weston
  (`WAYLAND_DEBUG` shows its `xdg_toplevel`, app id `prism`, and its frames as shared-memory buffers); both ways of
  drawing on both.
