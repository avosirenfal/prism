# prism on NixOS

The flake builds prism as a package, and has a NixOS module that installs it, runs its daemon in your graphical
session, and makes it your file chooser and your file manager. This document shows the lines to add to a system's
flake first, then what each of them does, then the same thing written out without the module, for whoever wants to
set only part of it.

## The lines

In your system's `flake.nix`, prism is an input, and its module one of the system's modules:

```nix
{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    prism.url = "github:avosirenfal/prism";
  };

  outputs =
    { nixpkgs, prism, ... }:
    {
      nixosConfigurations.yourhost = nixpkgs.lib.nixosSystem {
        system = "x86_64-linux";
        modules = [
          ./configuration.nix
          prism.nixosModules.prism
        ];
      };
    };
}
```

and in `configuration.nix` (or any module of yours):

```nix
{
  programs.prism.enable = true;
}
```

That one line is everything: prism is installed, its daemon runs in your session and keeps the index, it is the file
chooser applications get through the portal, it opens folders, and it answers "Show in folder". After
`nixos-rebuild switch`, log out and in again (the daemon starts with the graphical session, and the portal reads its
backends as it starts), or, to have it now:

```bash
systemctl --user restart xdg-desktop-portal.service
```

```bash
systemctl --user start prism.service
```

Then `prism status` says what the daemon is doing, and `journalctl --user -u prism` what it has said.

Three notes on the input:

- **Do not make prism's nixpkgs follow your own** (`prism.inputs.nixpkgs.follows = "nixpkgs"`). prism is built by
  the Gradle of the nixpkgs in prism's own lock, and `nix/deps.json` holds exactly what that Gradle fetches; Gradle's
  own plugins are of a version with Gradle itself, so another nixpkgs' Gradle asks for ones the lock does not hold,
  and the build fails with a plugin that "was not found" (`org.gradle.kotlin.kotlin-dsl`). Left to its own lock,
  prism costs a second nixpkgs in your store, and draws through your system's graphics drivers all the same
  (`/run/opengl-driver`).
- prism is built on your machine: there is no cache of it. The first build takes a few minutes (Gradle compiles
  every module), and later builds only when prism's sources change.
- It builds for `x86_64-linux` only for now: the lock of what Gradle fetches (`nix/deps.json`) holds that system's
  artifacts.

## What you asked for, and the one more thing

There are three separate things a desktop means by "the file manager", and prism is each of them:

| what | how a desktop finds it | the option |
|---|---|---|
| **the file chooser**: the dialog applications open and save files with, and choose folders with | the desktop portal (`xdg-desktop-portal`) is told which backend serves `org.freedesktop.impl.portal.FileChooser` | `programs.prism.portal.enable` |
| **what opens a folder**: `xdg-open ~/Documents`, a desktop's "open folder" | the default application for the type `inode/directory` | `programs.prism.fileManager.enable` |
| **"Show in folder"**: a browser's downloads, an editor's "reveal in file manager", and the portal itself when a sandboxed application asks for a folder to be opened | whoever owns the name `org.freedesktop.FileManager1` on the session bus | `programs.prism.fileManager.enable` |

The third is the one that is easy to forget, since it is not a portal and has no setting of its own anywhere: it
is simply whichever program has the name. The daemon takes it.

The folder picker is not a fourth thing: choosing a folder is the file chooser asked for a directory.

## The options

| option | default | what it does |
|---|---|---|
| `programs.prism.enable` | `false` | everything below, as each of them says |
| `programs.prism.package` | the flake's `prism` | the prism that is installed and run |
| `programs.prism.daemon.enable` | `true` | runs `prism daemon --foreground` as the systemd user service `prism.service`, started with `graphical-session.target` and ended with it, started again where it fails. Turned off, nothing is indexed and nothing starts with the session: `prism.service` is then `prism portal serve` (the windows, with no index), which D-Bus starts the first time the portal or a file manager's caller asks for prism |
| `programs.prism.daemon.roots` | `[ ]` | the folders it indexes; `/` where none is named |
| `programs.prism.daemon.extraArgs` | `[ ]` | more words for `prism daemon`: `[ "--decorated" ]` for title bars, `[ "--theme" "dark" ]` |
| `programs.prism.portal.enable` | `true` | prism is the file chooser |
| `programs.prism.fileManager.enable` | `true` | prism opens folders, and answers `org.freedesktop.FileManager1` |
| `programs.prism.watcher.enable` | `false` | loads the eBPF watcher into the kernel at boot (a system service, `prism-watcher.service`) for the daemon to read, so the index follows every change as it is made |
| `programs.prism.watcher.group` | `"prism"` | the group whose members may read it; made where it is not there |

Each can be turned off without the others.

The watcher is off unless asked for, since what it tells its readers is the name of every file anyone makes on the
machine. To have it, turn it on and put yourself in its group, then rebuild and log in again:

```nix
{
  programs.prism.watcher.enable = true;
  users.users.you.extraGroups = [ "prism" ];
}
```

`prism status` then says `changes: watched through the watcher pinned at /run/prism-watcher/...`. How it works, and
why the daemon is not simply given the privileges, is in docs/running.md ("The watcher, loaded by root and read by
the daemon").

The case of prism as the explorer with another backend for the picker:

```nix
{
  programs.prism = {
    enable = true;
    # prism as the explorer, the indexer still running, and the file picker left to another backend.
    portal.enable = false;
  };
}
```

With that, the daemon is run with `--no-file-chooser`: it keeps the index and opens prism's own windows, and nothing
of the portal's is touched (the module does not turn `xdg.portal` on, add a backend, or write a preference).

### The portal's configuration

The portal chooses a backend for each interface from `portals.conf` files. NixOS writes them from `xdg.portal.config`:
`xdg.portal.config.common` is `/etc/xdg/xdg-desktop-portal/portals.conf`, and `xdg.portal.config.niri` is
`niri-portals.conf` beside it.

Say your configuration has gtk for everything, and GNOME's backend for niri's screen casting:

```nix
{
  xdg.portal.config = {
    niri = {
      default = [ "gtk" ];
      "org.freedesktop.impl.portal.ScreenCast" = [ "gnome" ];
    };
    common.default = [ "gtk" ];
  };
}
```

With `programs.prism.enable`, the files NixOS writes are those, each with one line more at its end:

```ini
[preferred]
default=gtk
org.freedesktop.impl.portal.ScreenCast=gnome

org.freedesktop.impl.portal.FileChooser=prism
```

So prism is the file chooser, gtk is everything else, and GNOME casts niri's screen, with nothing of yours changed.

The line goes in every file, not `portals.conf` alone, because in a single folder the portal reads **one** file:
`<desktop>-portals.conf` if there is one for your desktop, and `portals.conf` only if there is not. In a niri session
with the configuration above, `portals.conf` is never read. Where you have set nothing under `xdg.portal.config`, the
module writes a `portals.conf` holding the one line, and every other interface is decided by the files after it (your
desktop's own).

If one of your files names a file chooser itself, prism's line comes after it, and of two lines for one key the
portal takes the last: `programs.prism.portal.enable` is what says who the file chooser is. Turn it off to have
another.

To see what the portal decided:

```bash
journalctl --user -u xdg-desktop-portal -b | grep -i filechooser
```

## What the module does, written out

For setting part of it by hand, or for seeing what `enable = true` amounts to. `prismPackage` here is
`prism.packages.${pkgs.stdenv.hostPlatform.system}.prism`, or `pkgs.prism` with `prism.overlays.default` among your
overlays.

**Installed**, which also puts its desktop entry (`prism.desktop`) where launchers look:

```nix
{
  environment.systemPackages = [ prismPackage ];
}
```

**The file chooser.** The package holds the backend's description (`share/xdg-desktop-portal/portals/prism.portal`)
and the D-Bus file that starts the daemon when the portal calls it and nothing has the name yet; `extraPortals` is what
shows both to the portal and to D-Bus:

```nix
{
  xdg.portal = {
    enable = true;
    extraPortals = [ prismPackage ];
    config.common."org.freedesktop.impl.portal.FileChooser" = "prism";
  };
}
```

with that last line said again in every other configuration you have (`config.niri`, say), for the reason above. The
module does not go through `xdg.portal.config` for it: it adds the line to each file as that option makes it, since
it reads which configurations there are from the option, and a module cannot define an option from what it reads of
it.

**What opens folders.** This writes the system's `mimeapps.list`; one of your own in `~/.config` still wins over it:

```nix
{
  xdg.mime.defaultApplications."inode/directory" = "prism.desktop";
}
```

**The daemon**, which is what answers `org.freedesktop.FileManager1`, keeps the index, and opens the windows:

```nix
{
  systemd.user.services.prism = {
    description = "prism: the index kept current, and every prism's windows";
    partOf = [ "graphical-session.target" ];
    after = [ "graphical-session.target" ];
    wantedBy = [ "graphical-session.target" ];
    enableDefaultPath = false;
    serviceConfig = {
      ExecStart = "${prismPackage}/bin/prism daemon --foreground";
      Restart = "on-failure";
      RestartSec = 2;
      # Java, told to stop, ends with status 143: its having been stopped, and no failure.
      SuccessExitStatus = 143;
    };
  };
}
```

`enableDefaultPath = false` matters. The daemon starts the applications your files are opened with, and they are
found on, and are given, the daemon's `PATH`. NixOS gives a unit a `PATH` of its own (a handful of basic tools)
unless it is told not to, and with that `PATH` none of your applications would be found. Without one, the daemon has
the `PATH` your session gave the user's systemd, which is yours.

That in turn means your session must give systemd its environment, which every desktop's own session does
(`niri-session`, GNOME, KDE, sway's `systemd` integration: whatever makes `graphical-session.target` start). If
`systemctl --user show-environment` shows `WAYLAND_DISPLAY` and your `PATH`, it has.

The module adds one more small thing: a D-Bus service file for `org.freedesktop.FileManager1` that starts
`prism.service`, so that "Show in folder" asked while the daemon is not running starts it.

## What is in the package

| path | what |
|---|---|
| `bin/prism` | a wrapper: nixpkgs' Java 25, prism's jars, and the libraries prism loads by name on the loader's path |
| `share/prism/lib/*.jar` | prism and what it runs with |
| `share/prism/prism.aot` | Java's cache of the classes a start loads, already read and linked, which takes about a third off the time `prism` takes to hand its words to the daemon |
| `share/applications/prism.desktop` | the desktop entry, "Prism": what a launcher lists and what opens `inode/directory`, run by its place in the store, with its icon and two actions (a new window; a new window in a process of its own). Its name is its windows' app id, so a compositor shows the icon and the name for prism's windows |
| `share/icons/hicolor/scalable/apps/prism.svg` | its icon |
| `share/xdg-desktop-portal/portals/prism.portal` | the portal backend's description |
| `share/dbus-1/services/org.freedesktop.impl.portal.desktop.prism.service` | what starts `prism.service` when the portal calls the backend and nothing has its name |
| `share/systemd/user/prism.service` | the daemon's unit, for use without the module |
| `lib/systemd/system/prism-watcher.service`, `lib/sysusers.d/prism.conf` | the watcher's loader as a system service, and the group it gives the watcher to, for use without the module: on another distribution, enable the one and put a user in the other |

Two things about the wrapper are worth knowing.

It puts NixOS's graphics drivers (`/run/opengl-driver/lib`) and the libraries SDL and Skia load by name on
`LD_LIBRARY_PATH`: they are prebuilt libraries inside jars, with no way of their own to find a library in the Nix
store. It also keeps what `LD_LIBRARY_PATH` was, and prism gives that back to every program it starts
(docs/running.md, "Programs prism starts"), so an application opened from prism does not inherit prism's libraries.

`extraJvmArgs` gives its Java more words:

```nix
{
  programs.prism.package = prism.packages.${pkgs.stdenv.hostPlatform.system}.prism.override {
    extraJvmArgs = [ "-Xmx2g" ];
  };
}
```

## The rest of the flake

| output | what |
|---|---|
| `packages.x86_64-linux.prism`, `.default` | the package |
| `overlays.default` | the package as `pkgs.prism` |
| `nixosModules.prism`, `.default` | the module above |
| `apps.x86_64-linux.default` | `nix run github:avosirenfal/prism` |
| `devShells.x86_64-linux.default` | what building with Gradle needs: `nix develop`, or direnv |
| `checks.x86_64-linux` | the package built; the module evaluated as it is by default, with the portal off, and with the daemon and the file manager off, and what it then says checked |
| `formatter.x86_64-linux` | `nix fmt` |

```bash
nix flake check
```

### After changing a dependency

Gradle fetches nothing while Nix builds prism: every artifact is in `nix/deps.json`, by hash. After a change to
`gradle/libs.versions.toml`, to a plugin, or to the Gradle wrapper's version, write it again, from the checkout's root:

```bash
$(nix build .#prism.mitmCache.updateScript --print-out-paths --no-link)
```

It runs the same Gradle task the package is built by, against the network, through a proxy that records what was
fetched. Where its sandbox cannot be made (inside a container), `USE_BWRAP=0` before it runs it without.

## When something is not as it should be

| what you see | where to look |
|---|---|
| an application's file dialog is not prism's | `prism status` (is the daemon running, and is the file chooser served?); then whether the portal chose prism: `journalctl --user -u xdg-desktop-portal -b`. A portal started before prism was installed has not read its backend: restart it |
| `prism` takes a second or two to open a window | the daemon is not running, so each `prism` starts its own windows: `systemctl --user status prism` |
| the daemon runs, and `prism status` says its windows cannot be opened | the daemon was started without the session's display in its environment: `systemctl --user show-environment` should show `WAYLAND_DISPLAY`. `prism` opens its own window meanwhile |
| files open in the wrong application, or in none | the daemon's `PATH`: `systemctl --user show-environment` |
| a window closed and one saying "ran into a problem" opened | that is prism reporting a mistake of its own (docs/running.md): Copy gives the text to report, and it is in `journalctl --user -u prism` |
| after an upgrade, the old prism is still running | `systemctl --user restart prism`; NixOS does not restart user services when it switches |
| `prism status` says changes are not watched | without `programs.prism.watcher.enable`, expected: a user's service cannot load the watcher (docs/running.md). With it: `systemctl status prism-watcher` (did it load?), `ls -l /run/prism-watcher/*/` (who may read it?), and `id` (are you in the group, in this login?) |

To watch the daemon directly, stop the service and run it in a terminal:

```bash
systemctl --user stop prism
```

```bash
prism daemon --foreground
```
