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

**Drawing, with nothing copied.** Two ways, chosen once, before the first window:

| | where it draws | when |
|---|---|---|
| the GPU | Skia's OpenGL backend, into the window's own framebuffer, which is then swapped onto the screen | there is a GPU, integrated or not |
| the CPU | Skia's CPU backend, into the very pixels SDL hands the platform (an X11 shared-memory image, a Wayland shared-memory buffer, a GDI or Quartz bitmap), which are then presented | there is no GPU, or OpenGL runs on the CPU (llvmpipe and the like, which Skia's own CPU drawing beats) |

Which one is found by a hidden OpenGL window, asked for its renderer's name. `PRISM_DRAWING=gpu` or `cpu` (or
`-Dprism.window.drawing=`) says otherwise. Skia is given OpenGL's functions as SDL finds them, so it draws with
whichever of EGL, GLX, WGL or CGL made the context; skiko's own lookup is GLX's alone, and finds nothing under Wayland's
EGL.

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
platform's own protocol (XDND, Wayland's data device, OLE, macOS's dragging). A drag of prism's own carries what it
drags as itself (`Outgoing`): to the same window always, and to another of prism's windows where the platform says
where windows are on the screen (X11, Windows, macOS).

## Not yet

- **Dragging out to other programs**, and on Wayland between prism's own windows. SDL takes drops but does not start
  drags, so this is each platform's protocol to speak: Wayland's data device (a `wl_data_source` and `start_drag`,
  through libwayland-client with FFM, beside SDL's own connection), XDND on X11, OLE's `DoDragDrop` on Windows, and an
  `NSDraggingSession` on macOS. Until then, copy and paste is the way to other programs.
- **Input methods' preedit**: text composed in an input method (CJK) arrives once it is committed; what is being
  composed is not drawn in the field meanwhile.
- **Wayland parents**: a picker for a Wayland application is not made its dialog yet (`xdg-foreign`).
- **Metal and Direct3D**: macOS and Windows draw through OpenGL, which both still have; Skia's Metal and Direct3D
  backends would be their own way.

## Tested

- `KeysTest`: keys by layout and by place, modifiers, typed text, and which OpenGL renderers are the CPU's.
- `WindowTest` (`-Pwindow.display`): a real window clicked, typed in, dropped on from another program, and copied
  from, through SDL's own events; it passes under Xvfb and under weston's headless Wayland.
- prism's `DragTest` and `SelectionTest` (`-Pbrowser.display`, X11): an archive's entry dragged by X's own pointer
  from one prism window to another and extracted there; and a failure's text selected and copied with Ctrl+C.
- By hand, in the development container: `prism explore` on X11, and as a native Wayland client under weston
  (`WAYLAND_DEBUG` shows its `xdg_toplevel`, app id `prism`, and its frames as shared-memory buffers); both ways of
  drawing on both.
