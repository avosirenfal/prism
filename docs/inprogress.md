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
| the scanner | `index`: one traversal, two backends (getdents64 through FFM, and the JVM's own), names as bytes, the ignore rules, pseudo file systems skipped from mountinfo |
| the picker | `picker`: an immutable `PickerState`, a pure reducer, a session that reads folders and looks up typed paths on a background thread, and a view made of its own controls with hover, press and focus looks; it browses (back, forward, up, places, a typed path), chooses (click, Ctrl, Shift, the arrows, type-ahead), searches, sorts, filters and saves (asking before replacing), by pointer and keyboard alike; `barion pick` prints what is chosen (docs/picker.md) |
| D-Bus | `dbus`: the connection (handshake and `Hello` as it opens), messages as data, kotlinx.serialization for bodies with signatures worked out from the types and checked before decoding, calls with replies as values, signals as flows, `watch`, served methods (docs/dbus.md) |
| the colour scheme | `portal`: the portal's Settings declared; `barion pick` opens in the desktop's palette and follows its changes; light where there is no bus or portal; `--theme` overrides |

169 tests (154 of barion's own, 15 of the house formatting rules), all passing in the development container; none
skipped there. The D-Bus ones run against a real dbus-daemon each test starts, and against gdbus, dbus-send and busctl.

## Measured

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
2. **The index in memory as arrays**, built from a scan, with lookup by (device, inode) and by path; then
   reconciliation: a watcher's change applied by statting that one name (plan.md, "Keeping it current").
3. **The index's file format** (docs/format.md first): the arrays, memory-mapped, rewritten whole by rename, with a
   journal of the changes since.
4. **The picker reading its folders from the index**: an index-backed `Disk`, reconciling a folder the index holds
   against what the picker reads of it; then search across the index from the search field.
5. **The portal backend**: `org.freedesktop.impl.portal.FileChooser` served on the session bus with
   `Connection.serve`, each request a `PickerState`, the daemon opening a window per request.
6. **The search language**, over the index's name trigrams.
7. **Filtering in the kernel**: a bounded loop in the DSL, so the watcher drops a change under an ignored directory
   before it is sent; and the privileged loader service that hands the ring to the user's daemon.

## Notes for whoever picks this up

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
