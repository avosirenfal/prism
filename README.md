# barion

A file picker for Linux desktops on Wayland, over an index of every file on the machine that the kernel keeps
current. It is meant to stand in for the open and save dialogs applications get through the desktop portal: a
familiar dialog in front, and behind it an index that finds a file by a few typed words, wherever it is, and never
goes stale, because an eBPF program sees every entry the kernel makes, removes or moves.

![The picker, light](docs/picker-light.png)

It is early: the parts exist and are tested, and are not yet joined into the daemon. docs/plan.md says what barion is
for and how it is meant to work; docs/inprogress.md says where it stands.

```bash
./gradlew build
```

```bash
./gradlew :app:run --args="pick"
```

```bash
./gradlew :app:run --args="scan /usr --runs 5"
```

## Commands

| command | what it does |
|---|---|
| `pick [FOLDER]` | the picker in a window over a folder (your home folder by default), printing what you do in it; `--save NAME`, `--multiple`, `--folder`, `--dark` |
| `snapshot [FOLDER] --out FILE` | the picker drawn headless into a PNG |
| `scan [PATH]` | a tree read by each scanner backend, several times, with what each found and how fast |
| `probe c` | the watcher's C, as emitted from its Kotlin declaration |
| `probe check` | every hook of the watcher against the running kernel's own description of its types |
| `probe watch` | the watcher loaded, and the changes it sees printed (needs `CAP_BPF` and `CAP_PERFMON`) |

`barion --help` and `barion COMMAND --help` say more.

## Layout

| module | depends on | what it is |
|---|---|---|
| `cli` | nothing | the command line: typed parameters, a one-pass reader that refuses what it cannot read, help from the same declarations |
| `linux` | nothing | libc through the FFM API: openat, getdents64 read in place, statx, mmap, epoll, each with its errno |
| `ebpf` | nothing | the eBPF DSL: programs in typed Kotlin, emitted as C for clang's BPF target, checked against the kernel's BTF |
| `ebpf-loader` | ebpf, linux | a compiled program loaded and attached through libbpf, its ring buffer read in place |
| `probe` | ebpf | the watcher: hooks on the exits of the VFS functions that change a directory |
| `index` | probe, ebpf-loader, linux | the scanner and its backends, the ignore rules, the watcher as the index runs it |
| `picker` | Compose | the picker: an immutable model and a pure view of it |
| `app` | everything | the command line and the window |

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

The build needs a JDK to run Gradle (the toolchain resolver fetches JDK 25 for the modules), a clang with the BPF
target (the `index` module compiles the watcher), and libbpf at run time to load it. With Nix, `nix develop` (or
direnv) gives all of them; the flake points `BARION_CLANG` at an unwrapped clang, since the wrapped one adds hardening
flags the BPF target refuses. Without Nix, on Debian or Ubuntu:

```bash
sudo apt-get install clang libbpf1
```

A clang other than the one on the PATH is named with `-Pbarion.clang=PATH` or `$BARION_CLANG`.

## Docs

| doc | what |
|---|---|
| docs/plan.md | what barion is for, how it is put together, and how what is not built yet is meant to work |
| docs/inprogress.md | where the work stands, what is not verified, what is next |
| docs/ebpf.md | the eBPF DSL: what its types refuse, what it reports, what it checks against the kernel, the C it emits |
| docs/scanning.md | how a directory is read, and the measurement that chose getdents64 over the JVM |
| docs/testing.md | what the tests need, where they skip, and how to run the watcher on a real machine |
