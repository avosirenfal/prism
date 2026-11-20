# prism

A file picker for Linux desktops on Wayland, over an index of every file on the machine that the kernel keeps
current. It is meant to stand in for the open and save dialogs applications get through the desktop portal: a
familiar dialog in front, and behind it an index that finds a file by a few typed words, wherever it is, and never
goes stale, because an eBPF program sees every entry the kernel makes, removes or moves. It follows the desktop's
light or dark preference through the portal, over a D-Bus library of its own.

The same browser is also a file manager, `prism explore`, inspired by Explorer and Thunar: it opens files with the
desktop's applications (detached from prism), offers "Open with" and changes the default for a type, shows files'
properties and permissions, and has a preferences window where pinned places, the terminal, and every key and mouse
binding are set. Preferences are one file, watched, so a place pinned in one window is in every picker's and
explorer's sidebar at once.

![The picker, light](docs/picker-light.png)

It runs as one process for the session, the daemon: it keeps the index, and every prism window opens in it, so a
`prism` typed in a terminal or started from a launcher hands over what it was asked and is gone, rather than starting
a Java program of its own. A window that fails is closed and says so; the rest, and the index, go on
(docs/running.md). On NixOS, the flake's module sets it all up in one line (docs/nixos.md).

It is early: the kernel's watcher has not yet been loaded on a real machine, and a user's service cannot load it at
all yet, so the daemon reads its folders again every half hour in its place. What macOS needs is written too (FSEvents for the
watcher, Launch Services and uniform types for the desktop, the system's trash, a launcher held by a key of its own),
and not yet tried on a Mac: docs/platform.md says where it stands, and what to try first. docs/plan.md says what prism is for and
how it is meant to work; docs/inprogress.md says where it stands.

```bash
./gradlew build
```

```bash
./gradlew :app:run --args="pick"
```

```bash
./gradlew :app:run --args="explore"
```

```bash
./gradlew :app:run --args="daemon --foreground"
```

```bash
./gradlew :app:run --args="scan /usr --runs 5"
```

## Commands

| command | what it does |
|---|---|
| `pick [FOLDER]` | the picker in a window over a folder (your home folder by default), in the desktop's light or dark palette as it changes; prints each path chosen on a line of its own, or nothing and exit status 1 when cancelled; `--save NAME`, `--multiple`, `--folder`, `--theme auto\|light\|dark`; `--decorated` for the window manager's title bar (none by default); `--index [FILE]` searches an index with Ctrl+Shift+F (the usual one, or FILE's), read once and never updated |
| `launcher` | the launcher (docs/launcher.md): a field, and under it the applications installed and then the index's files that fit what is typed, started or opened with Enter; shown by the prism already running where there is one, and put away where it is shown, so one key bound to it in a window manager shows and hides it; its settings, keys included, in a window of their own |
| `dmenu` | the launcher's window as dmenu is used: lines on standard input to choose one of, and the one chosen printed (`echo -e "25%\n50%" \| prism dmenu --placeholder "Window size..."`); its options are Vicinae's (docs/launcher.md, "dmenu") |
| `search QUERY...` | the paths the index holds that fit a query (docs/search.md), best first, a line each (docs/shell.md): `-b` the best alone, `-n N` so many, `-a` all, `-0` a NUL after each; `-s` exactly one match, or an error; `-i` a menu on the terminal to choose from, so that `-si` is always one path or none, for a shell's function to go to. A word that starts with a dash is the query's (`-draft`: not draft) unless it is exactly an option; with a `--`, what is before it is options alone, which can be run together, and what follows it the query. Answered by the prism already running, of its live index, or from the index's file |
| `explore [ITEM...]`, or no command at all | the file explorer on a folder (your home, unless named), or on a file's folder with the file chosen: opened by the prism already running where there is one, which this hands its words to and is done; where none is, by this one, which is then the session's prism until its last window is closed. Double-click opens with the desktop's application, Ctrl+W closes a window; `--isolated` keeps it to a process of its own, which searches the index's file as it was when it started; `--index [FILE]`, `--theme`, `--decorated` as `pick` takes them, each of which also asks for a process of its own |
| `daemon [PATH...]` | the one prism that stays (docs/running.md): the index kept current (the watcher started first, or why it could not be, then the index read or built, every change made as it is seen), every prism's windows, each searching that index, and the portal's file chooser and `org.freedesktop.FileManager1`. Started apart from the terminal, unless `--foreground` runs it in place; `--no-windows`, `--no-file-chooser`, `--no-file-manager`, `--replace`, `--theme`, `--decorated`; `--find QUERY` searches once the roots are read, reading places the rules leave out for the search alone; `--in-memory`, `--for N` |
| `status` | what the prism already running says of itself: its index, whether changes are watched, its windows |
| `agent install`, `agent uninstall` | on a Mac, the daemon as a launchd agent: started as you log in, and again should it fail; and taken away (docs/platform.md) |
| `agent stop`, `agent start`, `agent restart` | on a Mac, that agent stopped until it is started or you log in again; started; and started anew |
| `cli install`, `cli uninstall` | on a Mac, `prism` linked into a folder of the PATH (`/usr/local/bin`), to the application's own; and the link taken away |
| `portal serve` | the daemon without its index: the desktop's file chooser (as the portal's backend) and file manager (`org.freedesktop.FileManager1`), a window for each request, until stopped; what D-Bus starts, once it is installed, where no daemon runs |
| `portal install`, `portal uninstall` | make prism your file chooser and file manager, or stop it being them: files in your own folders only, each prism's (docs/portal.md) |
| `portal show [ITEM...]` | show folders and files in the file manager; what opening a folder runs, once installed |
| `snapshot [FOLDER] --out FILE` | the picker drawn headless into a PNG |
| `index [PATH...]` | folders indexed into the index's file (`/` by default), or the file read where there is one, with what it holds and how long that took; `--find QUERY` searches it, in the search language, with what is wrong with the query underlined and how long each search took; `--fresh` indexes anew |
| `scan [PATH]` | a tree read by each scanner backend, several times, with what each found and how fast |
| `probe c` | the watcher's C, as emitted from its Kotlin declaration |
| `probe check` | every hook of the watcher against the running kernel's own description of its types |
| `probe watch` | the watcher loaded, and the changes it sees printed (needs `CAP_BPF` and `CAP_PERFMON`); `--pinned` reads the one `probe load` left in the kernel instead, which needs nothing |
| `probe load`, `probe unload` | as root: the watcher loaded and left in the kernel, pinned, for the daemon to read with no privileges (`--user NAME` or `--group NAME` says who may); and taken out again (docs/running.md) |

`prism --help` and `prism COMMAND --help` say more.

On a Mac, prism is Prism.app: `nix build .#prism-dmg` makes a disk image of it to pass on, and opened the first time
it sets up its own daemon and puts `prism` on the PATH; the flake's `darwinModules.prism` does the same for nix-darwin (docs/platform.md, "Setting
it up on a Mac").

## Layout

| module | depends on | what it is |
|---|---|---|
| `cli` | nothing | the command line: typed parameters, a one-pass reader that refuses what it cannot read, help from the same declarations |
| `platform` | nothing | which platform this is (`Os`), where prism keeps its own files on it (`OwnFolders`), and what every platform's calls answer with: an entry's kind, a device, an inode's facts, a mount |
| `ffi` | nothing | native functions as typed Kotlin functions: a C function declared once, as an interface whose one method is its signature, from which both what the linker is told and what a call may pass are made |
| `linux` | platform, ffi | libc through the FFM API: openat, getdents64 read in place, statx, mmap, epoll, each with its errno |
| `macos` | platform, ffi | macOS through the FFM API: libSystem (getfsstat, getattrlist), the Objective-C runtime (each method declared once as a typed value, and checked against the runtime's own account of it) for UTType, NSWorkspace, NSBundle and NSFileManager, FSEvents, Carbon's hot keys; every handle made as it is first called, so it loads anywhere |
| `shared` | coroutines | what is shared and how it stays whole: a value kept in a file, read, changed and written as one step and handed to its readers (`Stored`, `OnDisk`, `Format`), and state shared between threads as the type that says what it guarantees: reached only under its lock (`Guarded`), read whole without one (`Published`), or set once (`Once`) |
| `ebpf` | nothing | the eBPF DSL: programs in typed Kotlin, emitted as C for clang's BPF target, checked against the kernel's BTF |
| `ebpf-loader` | ebpf, linux | a compiled program loaded and attached through libbpf, its ring buffer read in place |
| `probe` | ebpf | the watcher: hooks on the exits of the VFS functions that change a directory |
| `parsing` | nothing | parser combinators: grammars made of small parsers, each saying where it went wrong and what it would have taken there |
| `calc` | parsing | the calculator's language: Kotlin's arithmetic over exact Decimals, and over Kotlin's own number types where a value is converted to one, with their overflow said; typed overloads, and completion |
| `index` | linux, parsing, zstd | the scanner and its backends, the ignore rules, the index as columns and its compressed file, queries (read from text by parser combinators that recover from anything, or built with a Kotlin DSL) and the search that runs them and ranks what it finds, and the usage kept in `$XDG_STATE_HOME`; nothing of eBPF |
| `watcher` | probe, ebpf-loader, linux | the watcher: the probe's program compiled into the module, loaded, attached, and its ring read on a thread of its own |
| `daemon` | index, watcher | the index kept current: a live index that reads places again when told, makes the changes it is told of (again, once a scan they happened during lands), and reads places the rules leave out for searches alone, in memory; and the daemon, which starts the watcher before the index is read |
| `thumbnails` | platform, shared, kotlinx.serialization, coroutines | small pictures of files (docs/thumbnails.md): freedesktop.org's thumbnail cache read and written; what makes a thumbnail (a picture decoded, the preview a raw photograph carries, ffmpeg or a declared program run in a process of its own); the queue that makes what a window shows first and stops what is no longer wanted; and which thumbnails are kept within a budget, by how much and how lately each is used |
| `dbus` | kotlinx.serialization, coroutines | D-Bus: the bus's socket, its handshake and `Hello` as a connection opens; messages as data; bodies by kotlinx.serialization, their signatures worked out from the Kotlin types; calls, signals as flows, served methods |
| `portal` | dbus | the desktop portal as prism speaks to it: the appearance settings (the colour scheme, and its changes), the file chooser backend it serves, and `org.freedesktop.FileManager1` |
| `preferences` | kotlinx.serialization, coroutines | what the user has set (pinned places, key bindings, the terminal) as one immutable value, in a JSON file read once by the one store a process has of it and written whole at each change, which the store hands every window |
| `desktop` | linux, macos | the desktop as interfaces (file types, applications and defaults, launching, places, a file's facts), and freedesktop.org's implementation: shared-mime-info, desktop entries and their `Exec` lines, `mimeapps.list`, and starting applications detached from prism |
| `window` | Compose, SDL3 and OpenGL (LWJGL) | Compose in native windows on every platform (Wayland, X11, Windows, macOS): drawn on the GPU where there is one and on the CPU into the window's own pixels where not, keys, the pointer, the clipboard by MIME type, and drag and drop (docs/window.md) |
| `browser` | index, desktop, preferences, window, coroutines | browsing, as the picker and the explorer both do: an immutable state, a pure reducer from one state to the next, a session that carries out what it asks for, and a view made of controls of its own, worked by the pointer and the keyboard alike; a filter menu of kinds, a gallery with thumbnails, zoom, context menus (menus as data, one component to draw them) that rename, trash, copy, cut, paste and make files and folders, with undo; every key an action the user can bind again; and Ctrl+Shift+F's search of the index, its results shown as a folder holding them all |
| `client` | cli | what one prism asks another, and how (the session's prism, at its socket); `prism search`; and a main that is the `prism` a shell starts, compiled ahead of time by GraalVM (`./gradlew :client:nativeImage`), which asks the prism running and hands what nobody answers to the Java prism |
| `launcher` | browser, calc | the launcher: modules (the calculator first) that answer what is typed along the top or among the results, or in a view of their own; an immutable state, a pure reducer, a session that starts applications and searches the index, a view in the browser's theme with the browser's list of results, and its settings |
| `explorer` | browser, launcher | the file explorer: its windows as one immutable list, properties, "Open with", and the preferences window with its bindings page |
| `app` | everything | the command line; the windows, opened one way whatever opens them; the socket one prism asks another through; and the daemon, which is the index and the windows in one process |

The modules share their Gradle setup through convention plugins in `build-logic`, as kotlin-engine-test's do
(build-logic/README.md); style is the same house ktlint rules (`./gradlew ktlintCheck`, `./gradlew ktlintFormat`).

## The eBPF DSL

The watcher is written in Kotlin, not C. A program the verifier would refuse is, wherever a type can say so, a
program that does not compile: a value of one width cannot be added to another, a map's value cannot be reached
without the null check the verifier insists on, a ring buffer's reservation cannot be left unsubmitted, a parameter of
one function cannot be read in a hook of another. What a type cannot refuse is reported as data. The C it emits
includes nothing, so building needs only a clang with the BPF target, and every declaration is checked against the
running kernel's BTF before anything is loaded. docs/ebpf.md has the whole of it.

```kotlin
fexit(VfsMkdir) {
    val made = VfsMkdir.returns.value()

    stopIf(made.isErrorOrNull())
    send(changes, onFull = { count(lost, Lost.RingFull) }) {
        it[FsChange.parentIno] = VfsMkdir.dir.value()[Inode.iIno]
        it[FsChange.name] = kernelText(made[Dentry.dName][Qstr.name])
        // ...
    }
}
```

## Setup

build.md has every build and how each is installed, updated and removed. In short:

The build needs a JDK to run Gradle (the toolchain resolver fetches JDK 25 for the modules), a clang with the BPF
target (the `index` module compiles the watcher), and libbpf at run time to load it. With Nix, `nix develop` (or
direnv) gives all of them; the flake points `PRISM_CLANG` at an unwrapped clang, since the wrapped one adds hardening
flags the BPF target refuses. Without Nix, on Debian or Ubuntu:

```bash
sudo apt-get install clang libbpf1
```

A clang other than the one on the PATH is named with `-Pprism.clang=PATH` or `$PRISM_CLANG`.

The flake also builds prism as a package (`nix build`, `nix run`), and has a NixOS module that installs it, runs the
daemon in your session and makes it your file chooser and file manager: `programs.prism.enable = true`
(docs/nixos.md). The same flake makes what is passed to a machine with no Nix: `nix build .#prism-portable` (an
archive for any Linux) and, on a Mac, `nix build .#prism-dmg` (Prism.app in a disk image); docs/platform.md, "What is
built, and by what".

## Docs

| doc | what |
|---|---|
| docs/plan.md | what prism is for, how it is put together, and how what is not built yet is meant to work |
| docs/running.md | how prism runs: the daemon, one prism for a session's windows and how another finds it, `--isolated` and `--foreground`, and what happens when a window, every window, or the indexer fails |
| docs/nixos.md | prism on NixOS: the lines for a system's flake, the module's options, the same written out by hand, and what to look at when something is not as it should be |
| docs/picker.md | the picker: one example followed through, then its state, its rules, its filters, its gallery, its session, its controls and its keys |
| docs/portal.md | prism as the desktop's file chooser (a portal backend) and file manager: installing it, a request followed from an application through the portal to a picker and back, and how it is tested end to end |
| docs/explorer.md | the explorer: a double-click followed from the listing to a detached application, then its windows, the preferences every window shares, properties, "Open with", the bindings window, and what other platforms would need |
| docs/design.md | how prism's windows are built: one row of the preferences followed from its code to the screen, then the tokens (spacing, palette, type), the controls and layouts that space themselves, scrolling by rows, and the rules they keep |
| docs/window.md | prism's windows: SDL3's, native on Wayland and X11 alike; why not AWT; how a frame is drawn on the GPU or the CPU with nothing copied, how keys, the pointer, the clipboard and drag and drop reach Compose; and what is not done yet |
| docs/platform.md | what is the same on every platform and what is each one's; drag and drop followed through every toolkit; how macOS is reached (Objective-C's runtime through FFM), where it stands and what to try on a Mac; what Windows needs; the command-line picker that is prism's picker on both |
| docs/menus.md | a plan for context menus the user configures: actions declared with conditions and commands as words, run detached, in a terminal or watched; the menu's layout; items made as it opens; an editor that shows what an action would do |
| docs/text.md | text searches: a declared command given the files a window shows, whose lines of output are shown under their files with the file beside them; how its output is read, the preview, and the editor a line is opened in |
| docs/inprogress.md | where the work stands, what is not verified, what is next |
| docs/ebpf.md | the eBPF DSL: what its types refuse, what it reports, what it checks against the kernel, the C it emits |
| docs/scanning.md | how a directory is read, and the measurement that chose getdents64 over the JVM |
| docs/search.md | the search language, by example; how its text is read, however unfinished, into a tree, problems and a query; queries in Kotlin; how a search runs over the index's columns, and how fast |
| docs/daemon.md | keeping the index current: the watcher, what a change becomes, the live index and its scans, places read for searches alone, and why the watcher starting first means nothing is missed |
| docs/format.md | the index's file, byte by byte: a compressed snapshot, then changes appended, compacted by rename |
| docs/dbus.md | D-Bus from the ground up, and how the library maps it onto Kotlin types, calls, flows and served methods |
| docs/testing.md | what the tests need, where they skip, and how to run the watcher on a real machine |
