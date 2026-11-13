# In progress

Scratch space for where the work stands: what is done, what was measured, what is not yet verified, and what is
next. Rewrite freely; the finished account of anything here goes into its own doc (docs/plan.md, docs/ebpf.md,
docs/scanning.md, docs/testing.md).

## Where the scaffolding left things

The project is set up and every part named in the first brief exists, builds, and is tested:

| part | state |
|---|---|
| the build | Gradle as kotlin-engine-test's: build-logic conventions (`prism.kotlin-jvm`, `prism.compose`, `prism.ktlint`), one catalog, JDK 25 by the toolchain resolver, configuration and build caches, the house ktlint rules; a flake for the dev shell and a CI workflow |
| the command line | metis's, in `cli`, its reader returning `Parsed` values instead of throwing for control flow; `prism pick`, `snapshot`, `scan`, `probe c / check / watch` |
| libc through FFM | `linux`: openat, getdents64 read in place, statx, mmap, epoll, errno captured at the call; shared with libbpf's bindings as `NativeLibrary` and `CallState` |
| the eBPF DSL | `ebpf`: typed programs emitted as header-free CO-RE C; the verifier's rules as types where a type can say them, and what it cannot as reported `ProgramError`s; a BTF reader and a check of every declaration against the running kernel (docs/ebpf.md) |
| the loader | `ebpf-loader`: libbpf through FFM, each hook checked against BTF and left out if it moved, per-hook verifier logs, the ring buffer read in place with acquire/release ordering |
| the watcher | `probe`: hooks on the exits of `vfs_mkdir`, `vfs_rmdir`, `vfs_unlink`, `vfs_create`, `vfs_symlink`, `vfs_link`, `vfs_mknod`, `vfs_rename` and `do_filp_open`; compiled by the build into `index`'s resources |
| the scanner | `index`: one traversal, two backends (getdents64 through FFM, and the JVM's own), names as bytes, what each directory is gathered into the caller's to say; the ignore rules (names and path endings anywhere, absolute paths), pseudo file systems skipped from mountinfo, read once |
| the index | `index`: entries as columns in preorder (`Block`), inodes among them, a folder found by device and inode as the watcher names it; an immutable `Index` that changes by hiding and grafting and compacts back to a block a root, changes that say what is so and so can be made twice; its file a zstd-compressed snapshot and appended frames of changes, a torn frame dropped, compacted by rename (docs/format.md); `IndexStore` keeps an index and its file in step; `prism index` builds or reads one and searches it |
| the daemon | `watcher`: the eBPF watcher, its program compiled into the module, read on a thread of its own; `daemon`: a `LiveIndex` that opens at once and reads its roots behind, reads places again when told (`rescan`), makes changes it is told of and makes them again once a scan they happened during lands, and reads places the rules leave out for searches alone, in memory, never written; and a `Daemon` that starts the watcher before the index is read, queues what it sees onto a thread of its own, writes every few seconds, reads the roots again where changes were lost or there is no watcher, and reports and goes on from whatever fails in it (docs/daemon.md); `prism daemon` runs one, as the session's prism |
| running | `app`: one prism for a session's windows (a socket in the runtime folder, held by a lock; a prism started for a window asks the one running, and does it itself where that one cannot); the windows opened one way whatever opens them, on a host that starts them again when they fail all at once and needs no display to start; the daemon as the index, the windows (searching the live index) and the bus's names in one process, started apart from a terminal or run in the foreground; `prism status`; a window that fails closed alone, shown and reported (docs/running.md) |
| packaging | the flake: prism as a Nix package (its jars, run by nixpkgs' Java, with a cache of the classes a start loads), an overlay, and a NixOS module (`programs.prism`) for the daemon's user service, the portal and the file manager, each of which can be turned off (docs/nixos.md) |
| search | `index`: queries as an immutable tree, for every term of the language (names, globs, quoted and raw terms, paths, regexes, `dir:`, `ext:`, `type:`, `is:`, `recent:`, `mount:`, negation), built with a Kotlin DSL or read from text by parser combinators that read any text at all into a tree with spans, problems (unfinished, and searched for as far as it goes; or invalid, and left out) and marks to colour it by; run over the columns as bytes, with ancestors' states kept per level, on every core; each result scored as it is found (how the words match the name, how much it and its folder have been picked, how new it is, where it is) and the results given best first; what was picked kept in a file of its own, in `$XDG_STATE_HOME` (docs/search.md) |
| the picker | `browser`: an immutable `BrowserState`, a pure reducer, a session that reads folders, looks up typed paths and makes changes on a background thread, and a view made of its own controls; it browses (back, forward, up, the mouse's side buttons, places, a typed path), chooses (click, Ctrl, Shift, the arrows, type-ahead), searches, sorts, filters by kind from a menu (one, or several with Ctrl; hidden files shown and hidden folders not until the user says), zooms with Ctrl and the wheel (the gallery up to one picture at a time, fitted to the window), and saves (asking before replacing), as a list or a gallery with thumbnails of images; context menus (menus as data, one component for any menu) rename, trash (the desktop's own; the Delete key asks first) and make folders, each undone and redone with Ctrl+Z and Ctrl+Shift+Z; Ctrl+Shift+F searches the index, its first matches listed as the query is typed (coloured as it is read), a match gone to as the kind of picker says, every match shown in the listing as a folder holding them would show them (filtered, ordered best first or by a column, read on disk as they come into sight, millions without making millions of rows), Back returning to them; `prism pick` prints what is chosen, and `--index [FILE]` gives it an index to search; the same browser is the explorer's, below (docs/picker.md) |
| the explorer | `explorer`: `prism explore`, the browser as a file manager in as many windows as it is asked for, kept as one immutable list; a file accepted opened with the desktop's application, started detached (its own session, and a systemd scope where there is one); copy, cut and paste through the desktop's clipboard, with undo; new files; properties (type, size counted, times, what opens it, permissions changed as ticked); "Open with", setting a type's default in `mimeapps.list`; a preferences window for pins, the terminal, and every key and mouse binding, rebound as a game's are (docs/explorer.md); a plan for configurable context menus (docs/menus.md) |
| the desktop | `desktop`: file types, applications and defaults, launching, places and a file's facts as interfaces; freedesktop.org's implementation (shared-mime-info's globs, desktop entries and `Exec` lines, `mimeapps.list` in the specification's order, `setsid` and `systemd-run`, the XDG user directories, statx for when a file was made) |
| the preferences | `preferences`: pins, bindings and the terminal as one immutable value, a JSON file in `$XDG_CONFIG_HOME/prism` watched across processes, so a change reaches every window; one that does not read is kept aside, not overwritten |
| D-Bus | `dbus`: the connection (handshake and `Hello` as it opens), messages as data, kotlinx.serialization for bodies with signatures worked out from the types and checked before decoding, calls with replies as values, signals as flows, `watch`, served methods (docs/dbus.md) |
| the colour scheme | `portal`: the portal's Settings declared; `prism pick` opens in the desktop's palette and follows its changes; light where there is no bus or portal; `--theme` overrides |

About 640 tests, passing in the development container but for one of the archives' (`code.zip` with `unzip` and
`zipinfo` alone), which fails there with or without this work; eleven skipped there, those that need a display or
are run only when asked for. The D-Bus ones run against a real dbus-daemon each test starts, and against gdbus,
dbus-send and busctl.

## Measured

**The index of `/`** in this container: 185,359 entries (its caches, build trees and pseudo file systems left out),
indexed in 1.6 s on 4 cores, written to a file of 540 KB, read back in 57 ms; a search for a word takes 2 ms, a glob
7 ms. **A made-up index of 3,000,000 entries** (`SearchBenchmark`): searches, their results ranked and ordered, from
9 ms (`is:dir`) to 76 ms (`*.jpg`, which finds over a million), a regex over paths 280 ms; its file is 8.4 MB, written
in 1.2 s and read in 0.28 s (docs/search.md has every query's time). Reading a query's text: 30,000 random texts read and searched for in
0.4 s.

**getdents64 against the JVM** (docs/scanning.md), `/usr`, 92,611 entries, warm caches, 4 cores: getdents64 takes
**0.23x** the JVM's time on one thread (48.5 ms against 214.8 ms) and **0.44x** on four (32.4 ms against 73.4 ms);
`/root`, 38,272 entries, four threads: 0.40x. Both read identical trees. The FFM backend is the one the index uses.

**A window asked of a running prism** (docs/running.md): `prism FOLDER` handed to an idle daemon takes about 0.35 s
from the app image and about 0.2 s from the Nix package (whose Java has a cache of the classes a start loads), of
which 0.14 s is Java starting in the process that asks; a prism of its own takes about 2.2 s to have its window on
the screen.

Not measured yet: a cold scan on real hardware, and a home directory of millions of entries. `prism scan ~ --runs 5`
does both; the first run is the cold one.

## Not verified yet

- **The watcher has not been loaded.** This container's kernel refuses fentry, fexit and BPF-LSM programs with EPERM
  even to root, before the verifier runs (a policy of the sandbox: tracepoints, BTF tracepoints, kprobes and ring
  buffers all load). So the watcher's nine hooks are verified as far as they can be without loading them: the C
  compiles under `-Wall -Werror`, and every hook's signature and every field it reads matches this kernel's BTF
  (`prism probe check`). The loading path itself is verified end to end with a BTF tracepoint program
  (`LoadTest`). What remains is to run `prism probe watch` on a real machine (docs/testing.md).
- **The window has not been opened under Wayland.** It has been opened under X: `prism pick` ran on a virtual X
  display (Xvfb, no window manager) and was driven by xdotool through opening a file from the keyboard, several with
  Ctrl-click, saving under a typed name, choosing a folder, and cancelling, each printing what it should
  (docs/testing.md says how). Its windows are now SDL3's (docs/window.md), native under Wayland: `prism explore` has been run as a Wayland client
  under weston's headless backend. How it sits with a real compositor (niri: focus on opening, the title bar, drags
  from Nautilus) is untried.
- **The daemon has not been run as a real session's service.** It has been run in the development container on a
  virtual X display: started alone and apart from the terminal, asked for windows by other prisms, serving a picker
  and a file manager's request on a session bus, taking the session's place from a prism started before it, and
  stopped with its index written (docs/running.md, "How it is tested"). Under a real systemd user manager, with a
  real compositor's environment, with the NixOS module's unit: not yet. The module is evaluated by the flake's checks,
  and the package is built and run from its wrapper there; neither has been through a `nixos-rebuild`.
- **The explorer has been opened under X too**, on the same virtual display, and driven by xdotool: a file's menu,
  its properties and a folder's (contents counted, a permission ticked and unticked on disk), "Open with" filtered,
  confirmed with "always" (Vim started in a terminal, its parent init and its session its own, and the default
  written to `mimeapps.list`), a folder pinned (written to the preferences and shown in the sidebar at once), and a
  key and a mouse button rebound and saved, the explorer following them at once. Not tried: a real desktop's
  applications under Wayland, and several processes sharing the preferences file while a person uses them.

## Next

In the order they unblock each other:

1. **Run the watcher on a real machine**, with the privileges it needs, and read what it sends for a `mkdir`, a
   `touch`, a `mv`, an `rm -r`, a `git checkout` and an `npm install`. Fix what the verifier says about any hook.
   Then `sudo prism daemon --foreground --no-windows` there, and the same changes seen made in its index.
2. **The watcher for a user's daemon.** The daemon is a user's service now, which cannot load the watcher, so it
   reads its roots again every half hour instead. What is wanted is the privileged loader (docs/plan.md,
   "Privileges"): a small system service that holds `CAP_BPF` and `CAP_PERFMON`, loads the watcher, and gives the
   user's daemon its ring (a pinned map the daemon may open, or a descriptor passed over a socket), with a NixOS
   option beside the module's others. Giving the daemon the capabilities itself would hand them on to every
   application it starts, a thread's capabilities being its own and a JVM having many threads.
3. **The picker reading its folders from the index** (an index-backed `Disk`), with the picker's kinds and `type:`
   from one table of extensions; and a window telling the index what it saw change.
4. **A client that is not Java**, a few dozen lines of C or a Graal native image, that speaks the socket: of the
   0.2 s a window takes from the daemon, 0.14 s is Java starting in the process that asks. The same program is the
   command line that asks the daemon for searches in a terminal.
5. **Asking the daemon for more through the socket**: to read places again, and to search.
6. **Filtering in the kernel**: a bounded loop in the DSL, so the watcher drops a change under an ignored directory
   before it is sent.

## Notes for whoever picks this up

- Changes being idempotent does not make their order harmless: a change read from the disk before another, made
  after it, puts back what the other took away. So a live index reads the disk for each change as it makes it, under
  one lock, and only a scan reads without it, with the changes made meanwhile kept to be made again when it lands.
- ext4 gives a folder made a moment after another was removed the removed one's inode, so comparing a folder's
  entries by name, kind and inode cannot see one removed and made again (`ReconcileTest` found this). Changes by name
  read the entry whole; only a move's source is reconciled.
- On btrfs, a stat of a subvolume gives a device number of its own, while the kernel, mountinfo and the watcher give
  the file system's; the index keeps both for every device, the kernel's found by the mount a folder is under.

- Kotlin nests comments: `/*` inside a comment opens another, so an example like `funny/*.jpg` in a KDoc leaves the
  rest of the file a comment ("Unclosed comment"). Write such examples so a `/` is never followed by a `*`.
- JUnit sets a `@TempDir` field only after `@BeforeAll` runs, even with one instance per class; a `@BeforeAll` that
  needs the folder takes it as a parameter (`fun made(@TempDir folder: Path)`).
- `Indexer.reread` of a place the rules leave out gives no change at all, not `Absent`: absent would take away
  everything under the place, a root indexed there on purpose included.

- A key pressed with Ctrl reaches a Compose window twice: as the key (`KeyDown`) and as the character it types
  (`KeyEventType.Unknown`, with `utf16CodePoint` set), so Ctrl+2 types a "2" into a focused text field unless
  something above it drops that second event. Compose's test keyboard sends only the first; a test of typing has to
  send the second itself (`InteractionTest.typing`).
- A `companion object` of a sealed interface is set up with the interface; a value in it that makes an instance whose
  `init` reads the companion (`val NORMAL = By(2)`, with `By` checking its step against `SCALE`) finds it not yet
  there, and the class fails to load (`NoClassDefFoundError`, after an `ExceptionInInitializerError`). Make such a
  value a getter (`val NORMAL: By get() = By(2)`).
- On Linux, `Files.move(..., ATOMIC_MOVE)` replaces what is at the target without asking, whatever the options say;
  check that nothing is there first (the trash does, both ways).
- Compose's `awaitFirstDown` waits for the primary button only (on a mouse); anything that wants another button (a
  context menu) watches the raw pointer events instead (`Modifier.contextMenu`).
- A menu's submenu is a popup of its own, so a press outside it would close only it; every level closes the whole menu
  on a press outside, and Escape, handled by the menu itself, goes back a level.
- A test's mouse (Compose's `performMouseInput`) starts at the window's top-left corner, and a wheel turns wherever the
  mouse is: move it over what is to be scrolled first.
- The mouse's side buttons reach Compose as its `PointerButton.Back` and `Forward` (SDL names them as the platform
  does; AWT on Linux did not).
- Headless drawing (`renderedPng`) draws two frames and returns, so it never shows a thumbnail: those arrive from an
  IO thread after. The gallery's pictures are checked by the interaction tests, and seen in a real window.

- A drag of prism's own carries what it drags as itself, and the keys held decide its action (`askedOf`); one from
  another application comes as files, with no action said, and is dropped as no key asks (docs/window.md).
- Compose gives each popup a clipboard of its own making (AWT's, which has none under SDL): a popup's content is
  wrapped in `WindowLocals` for the window's.
- A clickable is focusable on the desktop: a click on one takes the keyboard from a text field.
- A path is a list of its own names in Kotlin (`Path` is `Iterable<Path>`), so `paths + path` adds the path's names,
  not the path. Use `paths.plusElement(path)`. The picker's history had this bug until a test caught it, and so,
  later, did pinning a folder and moving a pin in the preferences window.
- `application { }` (prism.window's) returns once `exitApplication()` is called, whatever windows are open; anything
  after it (printing what was picked) runs then.
- A `LazyListState` knows nothing of what is in sight until the list has been laid out once. An effect that scrolls
  as a folder arrives must wait for that (`snapshotFlow { layoutInfo }.first { ... }`), or it scrolls blind.
- Headless drawing (`renderedPng`) runs on a thread of its own, with the scene's effects dispatched there too; drawn
  from the test thread, an effect could run on the event thread while the test thread measured, which Compose refuses.

- The house formatter (`./gradlew ktlintFormat`) breaks a comparison inside an `if` condition across lines when the
  `if` is the value of an assignment; name the condition first (`val allMatch = moved == 0`) and it leaves it alone.
- The `index` module's build needs a clang with the BPF target. A Nix shell's wrapped `clang` adds hardening flags
  the BPF target refuses; the flake points `PRISM_CLANG` at the unwrapped one.
- The JVM names files in the locale's encoding. Under a POSIX locale it cannot make a `Path` of a non-ASCII name; the
  tests run under `C.UTF-8` (the `prism.kotlin-jvm` convention), and the getdents64 backend never needs a `Path`.
