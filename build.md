# Building and installing prism

There are two kinds of build. One is for working on prism: Gradle, run from the checkout. The other is for
installing it or passing it on, and every one of those is made by the Nix flake, which brings its own Java, Gradle
and GraalVM, so that nothing is installed first but Nix.

| for | made by | what comes out | section |
|---|---|---|---|
| working on prism, on Linux or a Mac | `./gradlew` | prism run from the checkout | [Working on it](#working-on-it) |
| NixOS | the flake as an input | the package, the daemon as a systemd user service | [NixOS](#nixos) |
| nix-darwin, on Apple silicon | the flake as an input | Prism.app, the daemon as a launchd agent | [nix-darwin](#nix-darwin) |
| a Linux with no Nix | `nix build .#prism-portable`, on Linux | `prism-0.1.0-linux-x86_64.tar.gz` | [A Linux with no Nix](#a-linux-with-no-nix) |
| a Mac with no Nix | `nix build .#prism-dmg`, on a Mac | `Prism-0.1.0-macos-arm64.dmg` | [A Mac with no Nix](#a-mac-with-no-nix) |
| Windows | nothing yet | prism does not run on Windows | |

Each of the four that are installed holds the Java prism and the `prism` a shell starts: the client, compiled ahead
of time, which asks the prism already running and is done in a few thousandths of a second.

Nix builds a Mac's programs on a Mac and Linux's on Linux, so each is built on its own kind of machine.

Where Nix says `nix-command` or `flakes` is an experimental feature that is off, add
`--extra-experimental-features 'nix-command flakes'` to the command, or put
`experimental-features = nix-command flakes` in `~/.config/nix/nix.conf`.

## Working on it

With Nix, the flake's shell has everything the build needs (a JDK, Gradle, a clang with the BPF target, libbpf, and a
GraalVM for the client):

```bash
nix develop
```

Without Nix, the build needs a JDK to run Gradle, which fetches JDK 25 for the modules itself. On Linux it also needs
a clang with the BPF target, and libbpf at run time (`sudo apt-get install clang libbpf1` on Debian or Ubuntu). On a
Mac it needs nothing more.

Build everything and run the tests:

```bash
./gradlew build
```

Run prism from the checkout. With no words it is the explorer; `--args` gives it a command line:

```bash
./gradlew :app:run
```

```bash
./gradlew :app:run --args "daemon --foreground"
```

Make prism as a folder that runs without Gradle, with its own Java inside. On Linux that is
`app/build/compose/binaries/main/app/prism`, and on a Mac it is `app/build/compose/binaries/main/app/Prism.app`:

```bash
./gradlew :app:createDistributable
```

On a Mac, that Prism.app copied to /Applications is a whole installation: once copied, it has nothing to do with the
checkout or with Gradle. It is the quickest way to try a change as an installed application. What it lacks, beside
the disk image below, is the client compiled ahead of time, so `prism` in a terminal starts a Java each time.

The client alone, where there is a GraalVM (the flake's shell has one):

```bash
./gradlew :client:nativeImage
```

## NixOS

prism is an input of the system's flake, and its module one of the system's modules:

```nix
{
  inputs.prism.url = "github:avosirenfal/prism";

  outputs = { nixpkgs, prism, ... }: {
    nixosConfigurations.yourhost = nixpkgs.lib.nixosSystem {
      system = "x86_64-linux";
      modules = [
        ./configuration.nix
        prism.nixosModules.prism
        { programs.prism.enable = true; }
      ];
    };
  };
}
```

```bash
sudo nixos-rebuild switch --flake .#yourhost
```

That installs prism, puts `prism` on the PATH, runs the daemon in the graphical session, and makes prism the file
chooser and the file manager. Log out and in again, or start it now:

```bash
systemctl --user start prism.service
```

| to | run |
|---|---|
| see how it is | `prism status`, `journalctl --user -u prism` |
| stop, start, restart | `systemctl --user stop prism`, `start prism`, `restart prism` |
| update | `nix flake update prism` in the system's flake, then `nixos-rebuild switch` |
| remove | take the lines out, then `nixos-rebuild switch` |

Do not make prism's nixpkgs follow your own: the lock of what Gradle fetches belongs to the Gradle of prism's own
nixpkgs. docs/nixos.md has the options and what the module does, written out.

To build the package by hand, or run it once without installing it:

```bash
nix build
```

```bash
nix run github:avosirenfal/prism
```

## nix-darwin

The same shape, with nix-darwin's module:

```nix
{
  inputs.prism.url = "github:avosirenfal/prism";

  outputs = { nix-darwin, prism, ... }: {
    darwinConfigurations.mac = nix-darwin.lib.darwinSystem {
      modules = [
        prism.darwinModules.prism
        { programs.prism.enable = true; }
      ];
    };
  };
}
```

```bash
darwin-rebuild switch --flake .#mac
```

That installs Prism.app, puts `prism` on the PATH, and runs the daemon as a launchd agent (`dev.prism.daemon`).
Updating and removing are `darwin-rebuild switch`, as on NixOS. Two things are yours to do in System Settings: give
Prism Full Disk Access, and turn off Spotlight's shortcut so Command-Space is prism's
([below](#what-is-yours-to-do-in-system-settings)).

The package does not build until the lock of what Gradle fetches on a Mac is written
([The locks](#the-locks)); it has not been built on a Mac yet.

## A Linux with no Nix

Built on a Linux that has Nix, for one that has not:

```bash
nix build .#prism-portable
```

`result/prism-0.1.0-linux-x86_64.tar.gz` is one folder. It holds prism's jars, a Java to run them (Adoptium's, as
published, which runs on any distribution), and the client linked statically. A Java program cannot be one
statically linked file, since what runs it is a Java; this is the same thing reached another way. What it takes from
the system is what every desktop program does: the Wayland or X11 libraries and OpenGL.

To install it, unpack it where it is to stay, and link its `prism` into a folder of the PATH:

```bash
mkdir -p ~/.local/opt ~/.local/bin ~/.local/share/applications
```

```bash
tar -xzf prism-0.1.0-linux-x86_64.tar.gz -C ~/.local/opt
```

```bash
ln -sfn ~/.local/opt/prism-0.1.0-linux-x86_64/bin/prism ~/.local/bin/prism
```

```bash
cp ~/.local/opt/prism-0.1.0-linux-x86_64/share/applications/prism.desktop ~/.local/share/applications/
```

The daemon is started by hand (`prism daemon`, which starts it apart from the terminal), or by a systemd user
service, which starts it with the session. Written as `~/.config/systemd/user/prism.service`:

```ini
[Unit]
Description=prism daemon
PartOf=graphical-session.target
After=graphical-session.target

[Service]
ExecStart=%h/.local/bin/prism daemon --foreground
Restart=on-failure

[Install]
WantedBy=graphical-session.target
```

```bash
systemctl --user enable --now prism.service
```

To make prism the file chooser and the file manager (docs/portal.md):

```bash
prism portal install
```

The watcher that tells the daemon of every change as it happens is loaded by root, once after each boot
(docs/running.md, "The watcher"); without it the daemon reads its folders again every half hour.

```bash
sudo ~/.local/bin/prism probe load --user "$USER"
```

| to | do |
|---|---|
| see how it is | `prism status`, `journalctl --user -u prism` |
| stop, start, restart | `systemctl --user stop prism`, `start prism`, `restart prism` |
| update | unpack the new archive beside the old, point the link at it, `systemctl --user restart prism`, delete the old folder |
| remove | `prism portal uninstall`, `systemctl --user disable --now prism`, then delete the service's file, the link, the desktop entry and the folder. What prism kept is in `~/.config/prism`, `~/.cache/prism` and `~/.local/state/prism` |

## A Mac with no Nix

Built on a Mac that has Nix, for one that has not. The lock of what Gradle fetches on a Mac is written first, once
([The locks](#the-locks)); then:

```bash
nix build .#prism-dmg
```

`result/Prism-0.1.0-macos-arm64.dmg` is a disk image that opens to Prism.app beside a link to Applications. The
application carries its own Java and the client, and runs on a Mac with no Nix. This build has not been run on a Mac
yet.

### Installing it

1. Open the disk image and drag Prism onto Applications.
2. Open Prism from /Applications. A disk image only holds files, so the application sets itself up the first time it
   is opened: it makes its daemon a launchd agent, which starts now and at every login, and it asks for your
   password once to link `/usr/local/bin/prism`, so that `prism` is a command in every terminal.
3. Do the two things below in System Settings.

If the image came through a browser or AirDrop, the first opening is refused, since the application is signed for no
one. Allow it once in System Settings › Privacy & Security › "Open Anyway", or:

```bash
xattr -dr com.apple.quarantine /Applications/Prism.app
```

### What is yours to do in System Settings

- **Full Disk Access.** Privacy & Security › Full Disk Access, and turn Prism on (add it with "+" if it is not
  listed). The daemon opens that page the first time it finds it has none. Until then the index leaves out Desktop,
  Documents, Downloads and the other folders macOS would ask about one by one; it reads them as soon as access is
  given, and `prism status` says which it is.
- **Command-Space.** Keyboard › Keyboard Shortcuts… › Spotlight, and turn off "Show Spotlight search". The daemon
  says so while Spotlight still holds the key.

### Day to day

| to | run |
|---|---|
| see how it is | `prism status`; the daemon's log is `~/Library/Application Support/prism/daemon.log` |
| stop it | `prism agent stop` (until you log in again, open Prism, or start it) |
| start, restart | `prism agent start`, `prism agent restart` |
| stop it for good | `prism agent uninstall` (opening Prism sets it up again) |

### A new build over an old one

Drag the new Prism onto Applications and say "Replace". The daemon sees that its application was replaced and starts
again from the new one within about fifteen seconds. Full Disk Access is given again in System Settings (remove the
old Prism from the list and add the new), because an application signed for no one is known by its bytes.

### Removing it

```bash
prism agent uninstall
```

```bash
prism cli uninstall
```

Then Prism.app to the Trash. `prism cli uninstall` says to use `sudo rm /usr/local/bin/prism` where the folder is
not yours to write. What prism kept is in `~/Library/Application Support/prism` and `~/Library/Caches/prism`.
Dragging the application to the Trash without the two commands also stops the daemon, after a minute, and leaves
the agent's file and the link behind, neither of which does anything.

### Trying an installation from nothing

To see what someone who never had prism sees, take away what an earlier one left, and what macOS remembers of it:

```bash
prism agent uninstall
```

```bash
sudo rm -f /usr/local/bin/prism
```

```bash
rm -rf /Applications/Prism.app ~/Library/Application\ Support/prism ~/Library/Caches/prism
```

```bash
tccutil reset SystemPolicyAllFiles dev.prism
```

Then install as above. What should happen, in order: Prism opens a window; the password is asked for, for the link;
System Settings opens at Full Disk Access; `prism status` in a new terminal answers and says there is no access;
with access given, the daemon's log says it is reading the folders it left out; with Spotlight's shortcut off,
Command-Space shows the launcher.

## The locks

Gradle fetches nothing while Nix builds prism: every artifact is in a lock, by hash. Linux's is `nix/deps.json`, and
a Mac's is `nix/deps-darwin.json`, since each platform fetches its own. A lock is written again, on its own kind of
machine and from the checkout's root, after a change to `gradle/libs.versions.toml`, to a plugin, or to the Gradle
wrapper's version:

```bash
$(nix build .#prism.mitmCache.updateScript --print-out-paths --no-link)
```

The Mac's lock is empty until this has been run on a Mac once, and both Mac builds fail until it has. Commit the file
it writes.

## What CI makes

`.github/workflows/ci.yml` builds the archive (`portable`) and the disk image (`dmg`) on every run, and on one started
by hand from the Actions page, and keeps each with the run to download. The `dmg` job writes the Mac's lock first
and keeps that too.
