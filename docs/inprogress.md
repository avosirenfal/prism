# In progress

Scratch space for where the work stands: what is done, what was measured, what is not yet verified, and what is
next. Rewrite freely; the finished account of anything here goes into its own doc (docs/plan.md, docs/ebpf.md,
docs/scanning.md, docs/testing.md).

## Where the scaffolding left things

The project is set up and every part named in the first brief exists, builds, and is tested:

| part | state |
|---|---|
| the build | Gradle as kotlin-engine-test's: build-logic conventions (`barion.kotlin-jvm`, `barion.compose`, `barion.ktlint`), one catalog, JDK 25 by the toolchain resolver, configuration and build caches, the house ktlint rules; a flake for the dev shell and a CI workflow |
| the command line | metis's, in `cli`, its reader returning `Parsed` values instead of throwing for control flow; `barion pick`, `snapshot`, `scan`, `probe c / check / watch` |
| libc through FFM | `linux`: openat, getdents64 read in place, statx, mmap, epoll, errno captured at the call; shared with libbpf's bindings as `NativeLibrary` and `CallState` |
| the eBPF DSL | `ebpf`: typed programs emitted as header-free CO-RE C; the verifier's rules as types where a type can say them, and what it cannot as reported `ProgramError`s; a BTF reader and a check of every declaration against the running kernel (docs/ebpf.md) |
| the loader | `ebpf-loader`: libbpf through FFM, each hook checked against BTF and left out if it moved, per-hook verifier logs, the ring buffer read in place with acquire/release ordering |
| the watcher | `probe`: hooks on the exits of `vfs_mkdir`, `vfs_rmdir`, `vfs_unlink`, `vfs_create`, `vfs_symlink`, `vfs_link`, `vfs_mknod`, `vfs_rename` and `do_filp_open`; compiled by the build into `index`'s resources |
| the scanner | `index`: one traversal, two backends (getdents64 through FFM, and the JVM's own), names as bytes, what each directory is gathered into the caller's to say; the ignore rules (names and path endings anywhere, absolute paths), pseudo file systems skipped from mountinfo, read once |
| the index | `index`: entries as columns in preorder (`Block`), inodes among them, a folder found by device and inode as the watcher names it; an immutable `Index` that changes by hiding and grafting and compacts back to a block a root, changes that say what is so and so can be made twice; its file a zstd-compressed snapshot and appended frames of changes, a torn frame dropped, compacted by rename (docs/format.md); `IndexStore` keeps an index and its file in step; `barion index` builds or reads one and searches it |
| the daemon | `watcher`: the eBPF watcher, its program compiled into the module, read on a thread of its own; `daemon`: a `LiveIndex` that opens at once and reads its roots behind, reads places again when told (`rescan`), makes changes it is told of and makes them again once a scan they happened during lands, and reads places the rules leave out for searches alone, in memory, never written; and a `Daemon` that starts the watcher before the index is read, queues what it sees onto a thread of its own, writes every few seconds, and reads the roots again where changes were lost or there is no watcher (docs/daemon.md); `barion daemon` runs one |
| search | `index`: queries as an immutable tree, for every term of the language (names, globs, quoted and raw terms, paths, regexes, `dir:`, `ext:`, `type:`, `is:`, `recent:`, `mount:`, negation), built with a Kotlin DSL or read from text by parser combinators that read any text at all into a tree with spans, problems (unfinished, and searched for as far as it goes; or invalid, and left out) and marks to colour it by; run over the columns as bytes, with ancestors' states kept per level, on every core (docs/search.md) |
| the picker | `picker`: an immutable `PickerState`, a pure reducer, a session that reads folders, looks up typed paths and makes changes on a background thread, and a view made of its own controls; it browses (back, forward, up, the mouse's side buttons, places, a typed path), chooses (click, Ctrl, Shift, the arrows, type-ahead), searches, sorts, filters by kind from a menu (one, or several with Ctrl; hidden files shown and hidden folders not until the user says), zooms with Ctrl and the wheel (the gallery up to one picture at a time, fitted to the window), and saves (asking before replacing), as a list or a gallery with thumbnails of images; context menus (menus as data, one component for any menu) rename, trash (the desktop's own; the Delete key asks first) and make folders, each undone and redone with Ctrl+Z and Ctrl+Shift+Z; `barion pick` prints what is chosen (docs/picker.md) |
| D-Bus | `dbus`: the connection (handshake and `Hello` as it opens), messages as data, kotlinx.serialization for bodies with signatures worked out from the types and checked before decoding, calls with replies as values, signals as flows, `watch`, served methods (docs/dbus.md) |
| the colour scheme | `portal`: the portal's Settings declared; `barion pick` opens in the desktop's palette and follows its changes; light where there is no bus or portal; `--theme` overrides |

288 tests (273 of barion's own, 15 of the house formatting rules), all passing in the development container; one
skipped there, the search benchmark, which runs only when asked for (`-Pindex.benchmark=N`). The D-Bus ones run against a real dbus-daemon each test starts, and against gdbus, dbus-send and busctl.

## Measured

**The index of `/`** in this container: 184,676 entries (its caches, build trees and pseudo file systems left out),
indexed in 1.9 s on 4 cores, written to a file of 537 KB, read back in 39 ms; a search for a word takes 2 ms, a glob
7 ms. **A made-up index of 3,000,000 entries** (`SearchBenchmark`): searches from 5 ms (`is:dir`) to 50 ms
(`*.jpg`, which finds over a million), a regex over paths 200 ms; its file is 8.4 MB, written in 1.1 s and read in
0.29 s (docs/search.md has every query's time). Reading a query's text: 30,000 random texts read and searched for in
0.4 s.

**getdents64 against the JVM** (docs/scanning.md), `/usr`, 92,611 entries, warm caches, 4 cores: getdents64 takes
**0.23x** the JVM's time on one thread (48.5 ms against 214.8 ms) and **0.44x** on four (32.4 ms against 73.4 ms);
`/root`, 38,272 entries, four threads: 0.40x. Both read identical trees. The FFM backend is the one the index uses.

Not measured yet: a cold scan on real hardware, and a home directory of millions of entries. `barion scan ~ --runs 5`
does both; the first run is the cold one.

## Not verified yet

- **The watcher has not been loaded.** This container's kernel refuses fentry, fexit and BPF-LSM programs with EPERM
  even to root, before the verifier runs (a policy of the sandbox: tracepoints, BTF tracepoints, kprobes and ring
  buffers all load). So the watcher's nine hooks are verified as far as they can be without loading them: the C
  compiles under `-Wall -Werror`, and every hook's signature and every field it reads matches this kernel's BTF
  (`barion probe check`). The loading path itself is verified end to end with a BTF tracepoint program
  (`LoadTest`). What remains is to run `barion probe watch` on a real machine (docs/testing.md).
- **The window has not been opened under Wayland.** It has been opened under X: `barion pick` ran on a virtual X
  display (Xvfb, no window manager) and was driven by xdotool through opening a file from the keyboard, several with
  Ctrl-click, saving under a typed name, choosing a folder, and cancelling, each printing what it should
  (docs/testing.md says how). Under Wayland it runs through XWayland, as any AWT window does; that, and how it sits
  with a real window manager (focus on opening, the title bar), is untried.

## Next

In the order they unblock each other:

1. **Run the watcher on a real machine**, with the privileges it needs, and read what it sends for a `mkdir`, a
   `touch`, a `mv`, an `rm -r`, a `git checkout` and an `npm install`. Fix what the verifier says about any hook.
   Then `barion daemon` there, and the same changes seen made in its index.
2. **The picker searching the daemon's index** from its search field (the marks colouring the field, the problems
   drawn over it), and reading its folders from the index (an index-backed `Disk`), with the picker's kinds and
   `type:` from one table of extensions.
3. **The portal backend**: `org.freedesktop.impl.portal.FileChooser` served on the session bus with
   `Connection.serve`, each request a `PickerState`, the daemon opening a window per request; and a D-Bus method of
   the daemon's to read places again from outside it.
4. **Filtering in the kernel**: a bounded loop in the DSL, so the watcher drops a change under an ignored directory
   before it is sent; and the privileged loader service that hands the ring to the user's daemon.

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
- The mouse's side buttons cannot be had from Compose on Linux: X numbers them 8 and 9, the JDK passes them on as
  AWT's 6 and 7, and Compose drops them, while reporting a sideways scroll (X's 6 and 7, AWT's 4 and 5) as back and
  forward. They are read from AWT itself (`picker/sidebuttons.kt`); nothing should use Compose's `PointerButton.Back`.
- Headless drawing (`renderedPng`) draws two frames and returns, so it never shows a thumbnail: those arrive from an
  IO thread after. The gallery's pictures are checked by the interaction tests, and seen in a real window.

- A path is a list of its own names in Kotlin (`Path` is `Iterable<Path>`), so `paths + path` adds the path's names,
  not the path. Use `paths.plusElement(path)`. The picker's history had this bug until a test caught it.
- Compose's `application { }` ends the process when its last window closes unless given `exitProcessOnExit = false`;
  anything after it (printing what was picked) needs that.
- A `LazyListState` knows nothing of what is in sight until the list has been laid out once. An effect that scrolls
  as a folder arrives must wait for that (`snapshotFlow { layoutInfo }.first { ... }`), or it scrolls blind.
- Headless drawing (`renderedPng`) runs on AWT's event thread, with the scene's effects dispatched there too; drawn
  from the test thread, an effect could run on the event thread while the test thread measured, which Compose refuses.

- The house formatter (`./gradlew ktlintFormat`) breaks a comparison inside an `if` condition across lines when the
  `if` is the value of an assignment; name the condition first (`val allMatch = moved == 0`) and it leaves it alone.
- The `index` module's build needs a clang with the BPF target. A Nix shell's wrapped `clang` adds hardening flags
  the BPF target refuses; the flake points `BARION_CLANG` at the unwrapped one.
- The JVM names files in the locale's encoding. Under a POSIX locale it cannot make a `Path` of a non-ASCII name; the
  tests run under `C.UTF-8` (the `barion.kotlin-jvm` convention), and the getdents64 backend never needs a `Path`.
