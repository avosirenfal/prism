# Testing

`./gradlew build` runs every module's tests. They need no privileges and no display. What a test needs beyond the
JDK, it checks for first and skips without (JUnit's assumptions), the way kotlin-engine-test's GPU tests skip without
a device, so a test that ran and a test that could not run are never confused.

## What runs where

| tests | need | where they skip |
|---|---|---|
| the DSL (`ebpf`: `CheckTest`, most of `EmitTest`) | the JDK | never |
| the emitted C compiling (`EmitTest`, `FsWatchTest`) | a clang with the BPF target | without one |
| the declarations against the kernel (`BtfTest`, `FsWatchTest`) | `/sys/kernel/btf/vmlinux` | on a kernel built without BTF |
| the loader, end to end (`LoadTest`) | clang, libbpf, and a process allowed to load BPF | without either, or where the kernel says EPERM |
| the scanner (`ScanTest`) | the JDK | never |
| libc's calls (`FilesTest`) | Linux | never |
| the picker, model and headless drawing (`PickerTest`) | the JDK | never |
| D-Bus's wire format and types (`WireTest`, `SerialTest`) | the JDK | never |
| D-Bus, live (`ConnectionTest`, `AppearanceTest`, `ThemeTest`) | dbus-daemon, which each test starts on a socket of its own | without dbus-daemon |
| D-Bus against other implementations (in `ConnectionTest`) | gdbus, dbus-send, busctl | each, without that tool |

In this project's development container everything runs and nothing skips. On a CI runner, the loader's tests skip
(they run as an unprivileged user) and everything else runs.

## The loader, end to end

`LoadTest` loads a small program from the DSL into the kernel the test runs on and reads it back: a BTF tracepoint on
system call entry, which sends this process's `close()` calls once the host has put this process in a table. That
covers every step the watcher takes (emitting the C, compiling it, libbpf's load and attach, a table the host
writes, the ring buffer read in place, the counters) through a hook any kernel that loads BPF at all will attach.

It needs a process allowed to load BPF: root, or one with `CAP_BPF` and `CAP_PERFMON`. To run it on your machine:

```bash
sudo -E ./gradlew :ebpf-loader:test
```

## The watcher on a real machine

The watcher's hooks are on function exits (fexit), which need the kernel's BPF trampolines and a process allowed to
load tracing programs. Check first that every hook matches your kernel; this needs no privileges:

```bash
./gradlew :app:run --args="probe check"
```

Then build the app and run the watcher with the privileges it needs, for thirty seconds:

```bash
./gradlew :app:createDistributable
```

```bash
sudo build/compose/binaries/main/app/barion/bin/barion probe watch --for 30
```

and, in another terminal, make changes for it to see:

```bash
mkdir /tmp/barion-test && touch /tmp/barion-test/a && mv /tmp/barion-test/a /tmp/barion-test/b && rm -r /tmp/barion-test
```

Each line it prints is a change: what happened, the device, the directory's inode, the name, and the process that
made it. A hook the kernel refuses is listed as refused, with why; one whose function has moved is listed as left out,
with what moved.

## Pictures of the picker

The picker's tests draw it headless (skiko's software renderer) in both palettes and for every kind of picker. To
keep the pictures:

```bash
./gradlew :picker:test -Ppicker.screenshots=build/shots
```

To draw it over a real folder:

```bash
./gradlew :app:run --args="snapshot $HOME --out build/home.png"
```

## The C the DSL emits

To keep the C the DSL's tests emit, to read or to compile by hand:

```bash
./gradlew :ebpf:test -Pebpf.emit=build/emitted
```

and the watcher's own:

```bash
./gradlew :app:run --args="probe c"
```
