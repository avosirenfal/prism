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

## Other applications' windows

A launcher brings forward an application that is running already (docs/launcher.md). For that, prism has to know
which windows other applications have open, and a compositor tells a program that only where it has a protocol made
for it. On Wayland that is wlr-foreign-toplevel-management, which sway, niri, Hyprland and other compositors built
on wlroots or Smithay have, and GNOME and KDE do not.

`openWindows()` (`openwindows.kt`) is what anything in prism asks, from any thread. Its answer is one of two things:

```kotlin
sealed interface OpenWindows {
	data object Unavailable : OpenWindows

	abstract class Listed : OpenWindows {
		abstract fun now(): List<OpenWindow>
		abstract fun focus(window: OpenWindow)
	}
}
```

`Unavailable` is the answer on X11, on a compositor without the protocol, and with no application running. An
`OpenWindow` is an id, the app id the window gives, and its title.

On Wayland, `Listed` is `Toplevels` (`waylandtoplevels.kt`). The compositor's manager is bound with prism's seat,
on prism's own queue (`waylandseat.kt`). It announces each window by a new object, a handle. The handle says the
window's title and app id, then `done`; the window is listed, or listed anew, at each `done`, and taken off the list
at `closed`. The windows open when the manager is bound are read with one round trip before the seat's thread
starts, so the list is whole the first time it is asked for.

`focus` sends the handle's `activate` with prism's seat. There is no token as there is for a program prism starts:
a compositor gives this protocol to programs it trusts to manage windows. A window is only activated if a window
with the same app id is still at that handle; its title may have changed since it was listed, as a terminal's does.

## A window that floats

`Window(resizable = false)` makes a window whose size the user cannot change. To a window manager that tiles (niri,
sway, Hyprland), a window that says its smallest and largest sizes are the same is a dialog: it is left floating
over the tiles at the size it asked for, rather than made a tile.

## A window over the others

A launcher is not a window among the others: it is over all of them, a fullscreen one included, where it says to be
rather than where a window manager puts it, and typed into for as long as it is shown. Every platform has its own
way of saying that, or none, so what is asked for is said without any of them (`role.kt`):

```kotlin
Window(
    onCloseRequest = ...,
    title = "Prism Launcher",
    size = DpSize(760.dp, 574.dp),
    role = WindowRole.Overlay(
        name = "prism-launcher",            // what the platform's own rules know it by
        placement = Placement.Centred,      // or Placement.Against(Edge.Top, 120.dp)
        keyboard = Keyboard.Shared,         // or Keyboard.Held: its alone until it is closed
        screen = Screen.Current,            // or Screen.Named("DP-2")
    ),
) { ... }
```

and each platform makes of it what it has:

| platform | an overlay is |
|---|---|
| Wayland, where the compositor has layer-shell (niri, sway, Hyprland, KDE, COSMIC) | a surface in the overlay layer (below) |
| Wayland without it (GNOME), and X11 | a window with no title bar that cannot be resized, kept in front on X11 (Wayland lets no window say so) |
| macOS, when prism is on it | a panel above the windows' level that does not make prism the active application (`NSPanel`, as Spotlight's is) |
| Windows, when prism is on it | a topmost tool window, in no taskbar |

`WindowRole.Normal`, the default, is a window as the platform has them. Which a window turned out to be is
`NativeWindow.overlaid`, and `PRISM_WINDOW_LOG=1` says it as each is made, with why where it is a window after all.
The decision is one function of the role, the platform and what the compositor has (`surfaceFor`), made before any
window is, and tested without either (`RoleTest`).

`Keyboard.Held` is the keyboard for as long as the overlay is shown, whatever is clicked. `Keyboard.Shared` is the
keyboard as it opens, which a click elsewhere takes, as it would from a window. A platform that gives a new overlay
the keyboard only if it holds it makes a shared one held after all (below).

### On Wayland: a layer surface

A window of SDL's is a surface with the role of a window (an `xdg_toplevel`). SDL can be asked for a window whose
surface it gives no role (`SDL_PROP_WINDOW_CREATE_WAYLAND_SURFACE_ROLE_CUSTOM_BOOLEAN`), and goes on drawing into it,
scaling it, and hearing its keys and its pointer all the same. The role is then given by prism, on its own connection
to the compositor (`waylandoverlay.kt`, beside the seat's in `waylandseat.kt`), in `wlr-layer-shell`'s words, whose
tables are in `waylandprotocols.kt` with the others SDL does not speak:

1. `zwlr_layer_shell_v1.get_layer_surface`: the surface, the output (none, for the compositor to choose the one the
   user is on; or the `wl_output` SDL has for a screen named), the overlay layer, and the role's name as its
   namespace.
2. `set_size`, `set_anchor` (no edge, for the middle; one edge, for against it), `set_margin`,
   `set_keyboard_interactivity` (exclusive for held; on demand for shared, where the compositor's layer-shell is
   version 4, and exclusive before that).
3. The surface committed with nothing in it, and the compositor's `configure` waited for and acknowledged. This is
   the protocol's order, and nothing may be drawn before it: the window's first frame is drawn only after. A
   compositor that does not answer within three seconds is done without: the window is destroyed, and one of the
   usual kind made in its place.
4. From then on SDL draws and commits as it does any window's. A later `configure` (the output's size changed) is
   acknowledged as it arrives, on the seat's thread, and the window is told its new size on the windows' thread, by
   `SDL_SetWindowSize`, which is how SDL learns the size of a surface it gave no role. `closed` (the output went
   away) is the window asked to close, as its title bar would ask.
5. Closed, the layer surface is destroyed before SDL destroys the surface it was the role of.

A keyboard held is asked for exclusively, and that is all. A keyboard shared is asked for on demand, and the two
compositors it was tried on answer that differently: niri gives a surface that appears asking on demand the keyboard,
and takes it from one that had it exclusively and then asks on demand; sway gives a surface that appears asking on
demand nothing until it is clicked, and leaves the keyboard with one that had it exclusively and then asks on demand.
So an overlay that shares the keyboard asks on demand as it is made; not given the keyboard 150 ms after its first
frame, it asks exclusively, to be given it, and asks on demand again once it has it; and where that costs it the
keyboard at once (within 300 ms, which no person's click is), it asks exclusively for good. How a surface stands in
this is one value (`Grip`, `window/grip.kt`: held, asking, to be given, shared since when, or gone), and what follows
from each thing its window hears (a moment waited, the keyboard given, taken, the surface closed) is a function of
it that says what to ask the compositor, with no surface and no clock: `GripTest` goes through what each compositor
does. `NativeWindow` tells it SDL's own account of the keyboard, and the log says each step.

SDL scales such a surface itself where it was made high-density, so sizes are in points and drawing in pixels, as
for a window, and both of prism's ways of drawing a window on Wayland (OpenGL through EGL, and shared memory) draw
into it unchanged.

Tried for real with `tools/launcher/check-wayland.sh`, on sway 1.9 and niri 26.04, each drawn both ways
(docs/launcher.md, "Tested").

## A window that is clear

A window is a rectangle, and what it does not paint is black: nothing of what is under it is seen. A window whose
shape is not a rectangle (a launcher with round corners) asks to be clear instead (`backdrop.kt`):

```kotlin
Window(
    onCloseRequest = ...,
    title = "Prism Launcher",
    role = WindowRole.Overlay("prism-launcher"),
    backdrop = Backdrop.Clear,              // Backdrop.Opaque is what a window is unless it asks
) {
    val clear = LocalNativeWindow.current?.backdrop == Backdrop.Clear
    // round the corners only where `clear`: anywhere else they would be black
}
```

Being clear is the window's pixels having an alpha channel that whatever puts windows on the screen blends by. A
frame is cleared to nothing before the content is drawn into it, and what the content draws is premultiplied, which
is what Wayland's compositors and X11's compositing managers expect. Each platform, and each of prism's ways of
drawing on it, gets its alpha its own way, or has none:

| platform | drawn on the GPU | drawn on the CPU |
|---|---|---|
| Wayland, a window or a layer surface | SDL makes the window transparent (`SDL_WINDOW_TRANSPARENT`): an EGL configuration with 8 bits of alpha (`SDL_GL_ALPHA_SIZE`, asked for around this window's making only), and no opaque region said of the surface | prism's own buffers are `ARGB8888` in place of `XRGB8888` (`WaylandPixels`) |
| X11, with a compositing manager (picom, xcompmgr, KWin, Mutter) | SDL chooses a visual of 32 bits for a transparent window | none: SDL's window surface is of the screen's own visual, which has no alpha, so the window is opaque |
| X11 with nothing compositing | none: alpha would be nobody's to blend, and a clear corner would be black | none |
| macOS, Windows | not yet | not yet |

Whether it is asked for at all is decided before the window is made, from the platform and, on X11, from whether a
compositing manager owns the selection one takes (`_NET_WM_CM_S0`, asked of libX11 directly): `mayBeClear`. Whether
it was got is read back from the pixels the window ended up with (`Pixels.alpha`: the alpha bits of the OpenGL
context, the format of the buffers): `backdropOf`. So `NativeWindow.backdrop` is `Clear` only where a pixel left
unpainted really shows what is behind it, and what draws in the window can believe it. `PRISM_WINDOW_LOG=1` says how
each window that asked turned out, and why where it is opaque:

```
window 5: asked to be clear, and is clear
window 3: asked to be clear, and is opaque: nothing composites this X11 screen
window 3: asked to be clear, and is opaque: its pixels have no alpha channel (Cpu)
```

A window that does not ask is made exactly as it was before there was anything to ask: an explorer's and a
picker's windows are opaque, with their opaque region said on Wayland, which lets a compositor skip what is under
them.

Tried for real: on sway 1.9 and niri 26.04, as a layer surface and as a window, drawn both ways
(`tools/launcher/check-wayland.sh` reads the pixel at the launcher's very corner off a screenshot, and it is what is
behind the launcher); under Xvfb with nothing compositing (opaque, and the launcher square); and under Xvfb with
xcompmgr, where it is clear drawn by OpenGL and opaque drawn on the CPU.

## When a window stutters

A window stutters when its thread is kept from the next frame: by its own work, or by the whole process being stopped
for a moment (Java collecting garbage). `PRISM_STALL_LOG=12` has every turn of the windows' loop that took longer
than that many milliseconds said on standard error, with what each part of it took:

```
prism.window: a turn of 87 ms, what other threads left for this one 9 ms, laying out and drawing 78 ms
```

The parts are what other threads left to be done on this one (an answer delivered, a state made), composing, laying
out and drawing, and each event from the platform. A long turn whose parts are all short was a stop of the whole
process, which `-Xlog:gc` (given to Java, docs/nixos.md's `extraJvmArgs`) says the times of. Not set, it costs one
comparison a turn.

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

- **An overlay on macOS and Windows**: `WindowRole.Overlay` says what is wanted, and each has its row in the table
  above to be made of.
- **A clear window on macOS and Windows, and on X11 drawn on the CPU**: `Backdrop.Clear` says what is wanted, and a
  window there is opaque and says so. X11's would be a window of a 32 bit visual and an image put to it by prism
  itself, as Wayland's shared memory is.
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
- `RoleTest`, `BackdropTest`: what a window of each role is made as, and whether one that asks to be clear is, on
  each platform, with and without what the platform might have (layer-shell, a compositing manager, pixels with
  alpha); no window is made.
- `OpenWindowsTest`: with no application running, other applications' windows are `Unavailable`. The protocol itself
  is tried on real compositors by `tools/launcher/check-wayland.sh` (docs/launcher.md), on sway and on niri.
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
