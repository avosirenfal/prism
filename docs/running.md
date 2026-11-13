# Running prism: the daemon, one prism for the session, and what happens when something goes wrong

prism is meant to be running all the time you are logged in: one process, the **daemon**, which keeps the index
current and opens every prism window. This document says how that process is run, how a `prism` typed in a terminal
or started from a launcher finds it, what happens when it is not there, and what happens when something in it fails.
docs/nixos.md says how NixOS sets all of it up; docs/daemon.md says how the index itself is kept.

## An example, first

Say you are logged in, the daemon was started with your session, and you press the key your launcher binds to prism.

1. The launcher runs `prism`. That is a new process, and a small one: it reads its words (none, which means
   `prism explore`, on your home folder), and looks for a socket at `$XDG_RUNTIME_DIR/prism/prism.sock`.
2. The daemon is listening there. The new process writes it what it was asked: the words `explore`, the folder it
   was started in, and the token the launcher gave it for the window to be given the keyboard. Then it waits.
3. The daemon reads the words exactly as it would read its own command line, and opens an explorer window on your
   home folder, beside whatever windows it already has. It answers: done, nothing printed, status 0.
4. The new process prints what it was answered (nothing), exits with the status it was answered (0), and is gone.

The window is the daemon's. It searches the daemon's index, as it is at that moment. The process the launcher started
lived for a fraction of a second and never loaded a window toolkit, which is the point: starting a Java program that
opens windows takes a second or two, and asking one that is already running takes a fraction of that (the numbers
are below).

Now say something goes wrong in that window: a mistake in prism's drawing code throws while the window is being
laid out. The window is closed. A small window opens in its place, which says what was closed, what went wrong in a
line, and where, in full, with buttons to copy that and to open the folder again. The same text is written to the
daemon's standard error, which under systemd is the journal. Every other window is as it was, and so is the index.

![The window shown in place of one that failed](window-failed.png)

The rest of this document is each of those parts in turn.

## The commands

| command | what it is |
|---|---|
| `prism`, `prism FOLDER`, `prism explore FOLDER...` | an explorer window on a folder (your home, with none named), or on a file's folder with the file chosen; opened by the prism already running where there is one |
| `prism --isolated`, `prism explore --isolated` | the same, in a process of its own, whether or not a prism is running: it asks nobody, and nobody asks it |
| `prism daemon` | the daemon, started apart from the terminal; returns once it has said it started |
| `prism daemon --foreground` | the daemon, run in place, saying what it does on the terminal until stopped: how systemd runs it, and how to watch it |
| `prism status` | what the prism already running says of itself; status 1 where none is |

`prism --help` lists the rest. A word after `prism` that names no command is `prism explore`'s, so `prism ~/Documents`
is `prism explore ~/Documents`; a folder whose name is a command's is written with a path (`prism ./index`).

## One prism for the session's windows

**Who listens.** Any prism that opens explorer windows listens, not only the daemon. The place is
`$XDG_RUNTIME_DIR/prism/`: a socket (`prism.sock`) and, beside it, a lock file (`prism.lock`). The runtime folder is
where a session's sockets are kept (Wayland's own is there), it is yours alone, and it is gone at logout. Whoever
holds the lock is the session's prism. The lock is the kernel's, so it is let go when its process ends, however it
ends: a socket file left behind by a prism that was killed is known to be nobody's, and the next prism takes the
place. Where `XDG_RUNTIME_DIR` is not set there is no place, and every prism is on its own.

**What is asked.** A command line: its words, the folder they were said in, and two variables of the asker's
environment (`XDG_ACTIVATION_TOKEN` and `DESKTOP_STARTUP_ID`, with which a desktop lets a new window take the
keyboard). The folder matters: `prism docs` typed in `~/work` means `~/work/docs`, and the daemon's own folder is
somewhere else, so a path that does not start at the root is read from where it was said. Nothing else of the asker's
environment is used: a window opened by the daemon is the daemon's, and what it starts is started with the session's
environment, as with any file manager that runs this way.

**What is answered.** One of two things. Either the words were run, and here is what they printed and their exit
status, which the asker prints and exits with (so `prism nosuchfolder` says `prism explore: nosuchfolder does not
exist` and exits 1, whoever ran it). Or they could not be run here, and why, and the asker runs them itself. The
second answer is what makes the arrangement safe to rely on: whatever the running prism cannot do for another (it has
no display, its windows have failed, it is another version and reads the words differently), the cost is only the
start the asker would have paid anyway. A prism that listens but does not answer within twenty seconds is treated as
not there.

**Which commands are asked.** `explore` and `status`. Every other command runs in the process that was started.
`prism explore` given anything that says how its windows should be (`--theme`, `--decorated`, `--index`) is one of
those too: a running prism's windows are as it was started, so asking it would ignore what you said. It opens in a
process of its own, as `--isolated` does.

**Without a daemon.** The first `prism` finds nobody listening. It takes the place, opens its own window, and is the
session's prism for as long as it has a window open: a second `prism` hands it its words, and its window opens in the
first. When the last window is closed, the process ends, and the place is free. Only the daemon stays with no window
open. Two started at the same moment end as one that listens and one that asked it: taking the place and asking are
tried in turn for a few seconds, since between a prism taking the lock and its listening there is a moment when it is
neither.

**A daemon started while another prism has the place** (you ran `prism` before the service came up) keeps its index
and waits. When that prism's last window is closed, the daemon takes the place, and says so.

**`--isolated`** takes no place and asks nobody: a process with its own windows, which ends when they are closed.
It is for looking at one prism without another being involved.

### How long it takes

Measured in the development container, on a virtual display, from the command being run to its returning, which is
when the daemon has opened the window:

| | time |
|---|---|
| `prism FOLDER` with no prism running: a prism of its own, until its window is on the screen | about 2.2 s |
| `prism FOLDER` handed to an idle daemon, from the app image (`createDistributable`) | about 0.35 s |
| the same, from the Nix package, whose Java has a cache of the classes a start loads | about 0.2 s |
| `prism status` from the Nix package: the asking alone, with nothing for the daemon to do | about 0.14 s |
| `java -version`, for scale: the least a Java program can take | about 0.04 s |

So a window from the daemon comes about ten times sooner than one from a prism of its own. Of the 0.2 s, about 0.14 s
is Java starting in the process that asks, which loads a few hundred classes, none of them a window's; the rest is
the daemon making the window. What is left of the first part is the virtual machine itself, and going below it means
a program that is not Java for this one job, a few dozen lines of C that speak the socket, which is not written yet.

## The daemon

`prism daemon --foreground` is one process that does three things, each of which is also done alone by some other
command:

| it does | as | what the daemon adds |
|---|---|---|
| keeps the index current | `prism daemon --no-windows` | (docs/daemon.md) |
| opens every prism's windows | any `prism explore` that found nobody listening | its windows search the live index, and it stays with none open |
| serves the portal's file chooser and `org.freedesktop.FileManager1` | `prism portal serve` | the same |

It holds two locks: the session's place, above, and one on its index's file (`index.prism.lock`, beside it), since two
daemons keeping one file would each write over what the other wrote. A second daemon on the same file says the first
is running and ends; `prism index --fresh` says the same rather than write over the daemon's file.

Its windows search through a `Finder` over the daemon's own index (`LiveFinder`): each search runs on the index as it
is at that moment. A search that names a place the rules leave out (`/nix/store/`, `~/.cache/`) has it read for that
search, and waits up to five seconds for the reading before it answers, so the first search of such a place finds
what is in it. What is picked is written to the usage file as it is picked, so it ranks higher after a restart too.

**`--foreground`, and without it.** A service manager wants the process it started to be the daemon, and keeps what
it writes: that is `--foreground`. Typed in a terminal without it, `prism daemon` starts the same command line again
with `--foreground` added, in a session of its own (so closing the terminal closes nothing), with what it says
appended to `$XDG_STATE_HOME/prism/daemon.log`; it waits until the daemon has said it started, tells you its process
and its log, and returns. If the daemon ends instead, what it said is shown. `--for N` and `--find QUERY` say things as
they go, so they run in the foreground whether or not you say so.

**The other switches.** `--no-file-chooser` leaves the portal's file chooser to another backend, `--no-file-manager`
leaves `org.freedesktop.FileManager1`, `--replace` takes a name another has, `--no-windows` is the index and nothing
else, and `--theme` and `--decorated` are how every window it opens is drawn.

**The watcher.** The daemon tries to load the eBPF watcher as it starts. Loading it needs `CAP_BPF` and
`CAP_PERFMON`, which a user's service has not got, so run as a user service the daemon says the watcher was refused,
and reads its roots again every half hour instead. Nothing else changes: the index is never wrong for long, and a
folder you are looking at is watched by the window showing it. The plan for the watcher's privileges (a small system
service that loads it and hands its ring to the user's daemon) is in docs/plan.md, and is the next thing to build.
`prism status` says which it is doing.

### `prism status`

```
prism daemon (process 6957), running since 2026-11-13 22:42
index: 412,530 entries under /, kept in /home/you/.cache/prism/index.prism
changes: not watched (the kernel would not load the watcher: ...); in its place, the roots are read again every 30 minutes
other prisms: open their windows here
session bus: the portal's file chooser and the file manager (org.freedesktop.FileManager1) served
windows: 2 open
```

Where the session's prism is not the daemon, its first line says what it is instead, and that no index is kept.

## What happens when something goes wrong

The rule is that a failure costs as little as it can, is always said, and never takes the index with it. There are
four sizes of failure, each contained at its own edge.

| what failed | what is lost | what you see | where it is contained |
|---|---|---|---|
| something a window was doing that can be said of one thing: a folder that cannot be read, a file that cannot be renamed, an application that would not start | nothing | the listing says the folder cannot be read, or a notice says what could not be done | the browser's session, which turns an exception from the disk or the desktop into an answer (`Session.answerOf`) |
| a window: composing it, laying it out, drawing it, giving it a click or a key, an effect or a coroutine of its content, or its own logic | that window | a window that says so, with the failure in full, Copy, and Open again | the window module (`NativeWindow.fail`) and the explorer (`Explorer.failed`) |
| every window at once: the composition they are all opened from, or the display going away | every window | they are opened again for the next one asked for; whoever was waiting on one is told | the window host (`Windows.run` in `app/host.kt`) |
| the virtual machine itself: memory run out | the process | systemd starts it again | nothing can be; the index is written on the way out |

**A window on its own.** Each window is a Compose composition of its own, with a recomposer of its own
(docs/window.md). So what is thrown while one window is composed, laid out or drawn, or while it is handed an event,
is caught around that window's frame or event and no further. What is thrown later, in an effect or a coroutine of
its content, comes to the handler in that window's own recomposer. And what the window's work does off the windows'
thread (reading a folder, running a command) is done in a scope of the window's own, with a handler of its own.
All three end in one place, `Explorer.failed`: the window is closed, what it was doing is stopped, the failure is
written to standard error, and a window that says what happened is opened. A picker the portal asked for answers the
portal that it failed (response 2) rather than leaving the application waiting. If the window that says what happened
fails in its turn, that is written to standard error and no further window is opened.

A window's failure is told once. After it, the window draws nothing more and takes no more events until it is closed,
since a scene that threw halfway through a frame is not one to hand another.

**Every window at once.** The windows are opened from one composition, on one thread. If that composition fails,
there is nothing left to open or close windows with, so the application of windows ends (`WindowsFailed`), and with
it every window. In a prism that was started for a window, that is the end of the process, with the failure said. In
the daemon it is not: the failure is reported, whoever was waiting on a window is answered that there are none (and
opens one of their own), and the windows are started afresh for the next one asked for. If they fail three times in
two minutes the daemon gives up opening windows, says so, and answers every request from then on that it cannot, until
it is restarted. Its index is kept throughout.

The same loop is what makes the daemon not need a display to start. Where there is none yet, it says so once, keeps
its index, and tries again when a window is asked for.

**The indexer.** Each of its threads catches what its work throws, reports it, and goes on, and what it could not do
is made up for the way everything is in an index that is a cache of the disk: by reading the disk again.

| what failed | what is done |
|---|---|
| a scan of the roots | reported; read again at the next reading of the roots |
| making a batch of the watcher's changes | reported; the roots are read again, since which changes were made cannot be known |
| writing the index's file (a disk that is full, a folder that cannot be written) | reported once, not at every tick; what could not be written is kept and written at the next tick |
| the watcher: starting, or reading its ring | said; the daemon runs without, reading its roots again every half hour |
| an index file that cannot be read | said; the index is built anew, and written over it |

**Written on the way out.** The daemon writes its index every few seconds, and once more as it ends: when systemd
stops it, when it is interrupted, and when prism ends itself after something it could not go on from. The file is a
snapshot followed by frames of changes, and a frame cut short is dropped when the file is next read
(docs/format.md), so even a daemon killed outright loses only what it had not yet written, which the reading of the
roots at its next start finds again.

**Where it is said.** Everything that goes wrong is written to standard error as `prism: ` and what failed, then the
failure in full, whether or not it was gone on from. Run as a service, that is the journal:

```bash
journalctl --user -u prism -f
```

What a thread throws that nothing caught is said the same way, by a handler set for every thread as prism starts.

## Programs prism starts

A program started from a file manager is yours, not the file manager's, and should find the environment your session
has. Two things arrange prism's own: a launcher that puts the libraries prism loads on `LD_LIBRARY_PATH` (the Nix
package's), and an app image's launcher, which also marks the process as one it started. Neither is passed on
(`desktop/environment.kt`): a variable a launcher changed is given back as it was, from a `PRISM_RESTORE_` variable
the launcher keeps it in, and the app image's mark and folder are taken out. This is also why a daemon started apart
from a terminal starts cleanly from an app image, where it otherwise would not: the image's launcher, started with its
own mark, reads no configuration and fails.

## How it is tested

- **The socket** (`InstanceTest`): requests and answers written and read back; a prism of another version known for
  one; a listener answering an asker, and nobody answering where none listens; a socket left by a killed prism taken;
  an asker giving up on a listener that does not answer; two started at once ending as one that listens and one that
  asked.
- **A window failing** (`WindowTest`, with a display): six windows that go wrong in six ways (composing, composing
  again, drawing, an effect, a coroutine on another thread, a click), each told of once and closed, and a seventh that
  is clicked afterwards and still works; and a window with nobody to tell ending the application, saying why.
- **The explorer** (`ExplorerTest`): a window whose work fails closed, reported and shown, the others untouched; a
  picker whose window fails answering whoever asked; the window that says so failing in its turn.
- **The indexer** (`DaemonTest`): a watcher that fails to start; an index that cannot be written said once and kept
  current all the same.
- **What a started program is given** (`EnvironmentTest`).
- **The window that says a window failed** (`FailedTest`): what it says, what Copy copies, Open again and Close, and
  that it draws, in both palettes.
- **End to end** (`tools/daemon/check.sh`), with a real prism on a virtual X display, driven by xdotool: a prism
  started alone, a second handed to it, a path that is not there refused, a prism kept to itself opening in no other;
  a daemon started meanwhile, apart from the terminal, keeping its index and waiting its turn, a second daemon and a
  `prism index --fresh` each told the first is running; the first prism ending as its last window closes, and the
  daemon taking the session's windows from then; the daemon opening a window, going on with none, and, stopped,
  writing its index and taking its socket away. It needs Xvfb and xdotool, and says PASSED or FAILED:

  ```bash
  ./gradlew :app:createDistributable && tools/daemon/check.sh
  ```

  `PRISM=result/bin/prism tools/daemon/check.sh` runs it against a Nix build.
- **By hand, in the development container**: the daemon serving a picker and a file manager's request on a session
  bus of its own.

## Not yet

- **The watcher under systemd**: the privileged loader the daemon would be handed the ring by (docs/plan.md).
- **A client that is not Java**, for the 0.14 s of Java starting that is left in every window asked for.
- **Restarting the daemon after an upgrade.** A daemon started before prism was upgraded goes on running the old
  prism until it is restarted or you log in again; a new `prism` asks it all the same, and the two understand each
  other for as long as the words mean the same to both. Where they do not, the old one answers that it cannot, and the
  new one opens its own window.
- **One place for each display.** The place is the runtime folder's, so a user with two graphical sessions at once
  has one session's prism open windows for both.
- **Telling the daemon's index what a window saw.** A window watches the folder it shows, and reads it again when it
  changes; it does not yet tell the index, which finds out at its next reading.
