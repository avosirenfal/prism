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
as windows are drawn with it: SDL would otherwise unload it with the last window made for it (the hidden one, at
first) and load it again for the next, shutting EGL's display and starting it anew, after which NVIDIA's EGL under
Wayland makes no context current (`EGL_BAD_DISPLAY`). Each window then makes OpenGL and Skia's context of its own; where it cannot,
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
them. So SDL's keys are made into AWT's key events, which are only values: nothing of AWT's toolkit runs, and the app
runs with `java.awt.headless=true` to be sure of it. Every key handler, text field shortcut and binding of prism's then
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
own, for two reasons. A drag must be started with the serial of the button press that began it, which SDL keeps to
itself; so the seat is bound here too (asked for its pointer only once it has one), and its pointer's presses heard. And
a window of this process that a drag is dropped on is SDL's, which reads what is dropped at once, on the windows'
thread, waiting for the source to write it: the source's writing it is on the other thread, so neither waits for the
other.

## When no window opens

`PRISM_WINDOW_LOG=1` says on standard error which display server SDL speaks to, what OpenGL's renderer is (or that
there is none), how windows are drawn, and with what. A window that cannot be drawn at all stops the application, with
SDL's reason, rather than staying empty: a Wayland window shows nothing until it is given a buffer.

On NixOS, NVIDIA's EGL and GL are in `/run/opengl-driver/lib`, which glvnd finds them in by name; a program that is not
NixOS's own (a JVM loading SDL from LWJGL) finds them only with that folder on `LD_LIBRARY_PATH`, which the flake's
shell puts there. Without it only Mesa's EGL is found, which cannot drive an NVIDIA card (`libEGL warning: egl: failed
to create dri2 screen`), and prism draws on the CPU.

## Not yet

- **Dragging out to other programs on X11, Windows and macOS**: XDND, OLE's `DoDragDrop` and an `NSDraggingSession`,
  each a `NativeDrags` of its own, as Wayland's is. Until then, a drag there goes between prism's own windows, and copy
  and paste is the way to other programs.
- **A drag's picture**: the pointer is all that is drawn as a drag goes; Wayland's `start_drag` takes a surface for an
  icon, which is given none.
- **Input methods' preedit**: text composed in an input method (CJK) arrives once it is committed; what is being
  composed is not drawn in the field meanwhile.
- **Wayland parents**: a picker for a Wayland application is not made its dialog yet (`xdg-foreign`).
- **Metal and Direct3D**: macOS and Windows draw through OpenGL, which both still have; Skia's Metal and Direct3D
  backends would be their own way.

## Tested

- `KeysTest`: keys by layout and by place, modifiers, typed text, and which OpenGL renderers are the CPU's.
- `WindowTest` (`-Pwindow.display`): a real window clicked, typed in, dropped on from another program, and copied
  from, through SDL's own events. It passes under Xvfb and under weston's headless Wayland, drawn by the GPU, by the
  CPU, and with no EGL at all (`__EGL_VENDOR_LIBRARY_FILENAMES=/nonexistent.json`); the clipboard is left out under
  weston's, which has no seat to give the input serial Wayland's clipboard asks for.
- `tools/wayland/check.sh`: prism's app image on a headless sway, as native Wayland windows, and a pointer of wlroots'
  virtual-pointer protocol pressing, moving and letting go: a file dragged from one prism to another, moved there; a file
  dragged between two windows of one prism, moved there; and an archive's entry dragged to its folder's window,
  brought out there. It passes.
- prism's `DragTest` and `SelectionTest` (`-Pbrowser.display`, X11): an archive's entry dragged by X's own pointer
  from one prism window to another and extracted there; and a failure's text selected and copied with Ctrl+C.
- By hand, in the development container: `prism explore` on X11, and as a native Wayland client under weston
  (`WAYLAND_DEBUG` shows its `xdg_toplevel`, app id `prism`, and its frames as shared-memory buffers); both ways of
  drawing on both.
