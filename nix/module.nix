# The NixOS module: prism installed, its daemon run in every user's graphical session, and (each of them unless
# turned off) prism made the file chooser applications get through the desktop portal, and the file manager. It is a
# function of the flake, for the package it defaults to; docs/nixos.md says how it is used.
self:
{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.programs.prism;

  inherit (lib)
    mkEnableOption
    mkIf
    mkMerge
    mkOption
    types
    ;

  # `org.freedesktop.FileManager1` is the file manager's own name, not a portal's: whoever has it is asked to show a
  # folder, or a file in its folder ("Show in folder"). With the daemon running, it has it; this is what starts the
  # daemon where it is asked for and is not, as the package's own file does for the portal's backend. It is the
  # module's, not the package's, since a package's D-Bus services are the session's as soon as it is installed, and
  # prism installed is not always to be the file manager.
  fileManagerService = pkgs.writeTextDir "share/dbus-1/services/org.freedesktop.FileManager1.service" ''
    [D-BUS Service]
    Name=org.freedesktop.FileManager1
    Exec=${lib.escapeShellArgs ([ (lib.getExe cfg.package) ] ++ command)}
    SystemdService=prism.service
  '';

  # What `prism.service` runs. With the daemon: the daemon, started with the session. Without it: prism serving the
  # bus alone, with no index (`prism portal serve`), which nothing starts until D-Bus does, because the portal or a
  # file manager's caller asked for it.
  command =
    if cfg.daemon.enable then
      [
        "daemon"
        "--foreground"
      ]
      ++ lib.optional (!cfg.portal.enable) "--no-file-chooser"
      ++ lib.optional (!cfg.fileManager.enable) "--no-file-manager"
      ++ cfg.daemon.extraArgs
      ++ map toString cfg.daemon.roots
    else
      [
        "portal"
        "serve"
      ]
      ++ lib.optional (!cfg.fileManager.enable) "--no-file-manager";

  # Whether anything would run the unit: the session (the daemon), or D-Bus (the portal's backend, the file manager).
  served = cfg.daemon.enable || cfg.portal.enable || cfg.fileManager.enable;
in
{
  options.programs.prism = {
    enable = mkEnableOption "prism, a file explorer and file picker over an index of every file that the kernel keeps current";

    package = mkOption {
      type = types.package;
      default = self.packages.${pkgs.stdenv.hostPlatform.system}.prism;
      defaultText = lib.literalExpression "prism.packages.\${pkgs.stdenv.hostPlatform.system}.prism";
      description = "The prism to install and run.";
    };

    daemon = {
      enable = mkOption {
        type = types.bool;
        default = true;
        description = ''
          Whether to run prism's daemon in every user's graphical session (a systemd user service, `prism.service`).

          The daemon keeps the index current, and opens every prism window: `prism` run from a terminal or a
          launcher hands what it was asked to the daemon and is done, so a window opens without a Java program
          starting.

          Without the daemon, nothing is indexed and nothing runs until it is asked for. The first `prism` started
          opens its own windows, and is what later ones hand theirs to until its last window is closed. Where prism
          is the file chooser or the file manager, D-Bus starts `prism.service` the first time the portal or a file
          manager's caller asks, as `prism portal serve`: the same windows, with no index.

          What it says is in the journal: `journalctl --user -u prism`.
        '';
      };

      roots = mkOption {
        type = types.listOf types.path;
        default = [ ];
        example = [
          "/home"
          "/data"
        ];
        description = "The folders the daemon indexes. Everything (`/`) where none is named, less what its rules leave out.";
      };

      extraArgs = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [
          "--decorated"
          "--theme"
          "dark"
        ];
        description = "More words for `prism daemon`: `prism daemon --help` lists them.";
      };
    };

    portal = {
      enable = mkOption {
        type = types.bool;
        default = true;
        description = ''
          Whether prism is the file chooser: the dialog applications open and save files and choose folders with,
          through the desktop portal (`org.freedesktop.portal.FileChooser`).

          This turns the portal on (`xdg.portal.enable`), adds prism to its backends, and prefers prism for the file
          chooser in the portal configurations named in {option}`programs.prism.portal.desktops`. Every other portal
          interface (screenshots, screen casting, the colour scheme) is left to whatever you have set for it.

          Turned off, prism is still installed, and its daemon still runs and indexes where
          {option}`programs.prism.daemon.enable` says so: only the file chooser is left to another backend.
        '';
      };

      desktops = mkOption {
        type = types.listOf types.str;
        default = [ "common" ];
        example = [
          "common"
          "niri"
        ];
        description = ''
          The portal configurations (the attributes of {option}`xdg.portal.config`) that prefer prism for the file
          chooser. `common` is `portals.conf`, read for every desktop.

          Where you set `xdg.portal.config.<desktop>` for your own desktop as well, name that desktop here too: the
          portal reads `<desktop>-portals.conf` *instead of* the `portals.conf` beside it, not after it.
        '';
      };
    };

    watcher = {
      enable = mkOption {
        type = types.bool;
        default = false;
        description = ''
          Whether to load prism's watcher into the kernel at boot (a system service, `prism-watcher.service`), for the
          daemon to read: an eBPF program that sees every file and folder made, removed or renamed, as it happens, so
          the index is never out of date. Without it, the daemon reads its folders again every half hour.

          Loading it is root's, and is done apart from the daemon so that the daemon, and the applications it
          starts, have no privileges: the loader leaves the watcher in the kernel and ends, and the daemon reads it
          by a file only the members of {option}`programs.prism.watcher.group` may open. What it tells is the name
          of every file anyone makes on the machine, so put in that group only users trusted with that:

              users.users.you.extraGroups = [ "prism" ];

          (and log in again, for the daemon to be in it).
        '';
      };

      group = mkOption {
        type = types.str;
        default = "prism";
        description = "The group whose members may read the watcher. Made, where it is not there.";
      };
    };

    fileManager.enable = mkOption {
      type = types.bool;
      default = true;
      description = ''
        Whether prism is the file manager: what opens folders (the default application for `inode/directory`, which
        is what `xdg-open` on a folder and a desktop's "open folder" run), and what answers
        `org.freedesktop.FileManager1` on the session bus, which is what an application's "Show in folder" calls.

        Turned off, prism is still installed and can be started by name; folders open in whatever else is set.
      '';
    };
  };

  config = mkIf cfg.enable (mkMerge [
    {
      environment.systemPackages = [ cfg.package ];
    }

    (mkIf served {
      systemd.user.services.prism = {
        description =
          if cfg.daemon.enable then
            "prism: the index kept current, and every prism's windows"
          else
            "prism: the file chooser and the file manager, with no index";
        documentation = [ "https://github.com/avosirenfal/prism" ];

        # Ended with the graphical session, since it is the session's windows it opens; and, where it is the daemon,
        # started with it. Where it is not, D-Bus starts it when it is first asked for.
        partOf = [ "graphical-session.target" ];
        after = [ "graphical-session.target" ];
        wantedBy = lib.optional cfg.daemon.enable "graphical-session.target";

        # No PATH of the unit's own: the daemon starts the applications files are opened with, which are found on,
        # and are given, the PATH the session gave the user's service manager.
        enableDefaultPath = false;

        serviceConfig = {
          ExecStart = lib.escapeShellArgs ([ (lib.getExe cfg.package) ] ++ command);
          # Started again where it ended by failing, a moment later; not where it was stopped. Java, told to stop,
          # ends with the status 143, which is its having been stopped, and no failure.
          Restart = "on-failure";
          RestartSec = 2;
          SuccessExitStatus = 143;
          # Stopping it stops the daemon and what it was waiting on (an archive being listed). The applications files
          # were opened with are not among them: each was started in a scope of its own (desktop/freedesktop/launcher.kt).
          TimeoutStopSec = 15;
        };
      };
    })

    (mkIf cfg.watcher.enable {
      users.groups.${cfg.watcher.group} = { };

      systemd.services.prism-watcher = {
        description = "prism: the watcher loaded into the kernel, for prism's daemon to read";
        documentation = [ "https://github.com/avosirenfal/prism" ];
        wantedBy = [ "multi-user.target" ];

        serviceConfig = {
          # Loaded, pinned, and done: what stays is in the kernel, with no process of root's behind it.
          Type = "oneshot";
          RemainAfterExit = true;
          ExecStart = lib.escapeShellArgs [
            (lib.getExe cfg.package)
            "probe"
            "load"
            "--group"
            cfg.watcher.group
          ];
          ExecStop = lib.escapeShellArgs [
            (lib.getExe cfg.package)
            "probe"
            "unload"
          ];
          # What loading, mounting the file system it is pinned in, and giving it to the group take, and no more.
          # Nothing that gives the service mounts of its own (PrivateTmp, ProtectSystem): what it mounts is for
          # the daemon to see.
          CapabilityBoundingSet = "CAP_BPF CAP_PERFMON CAP_SYS_ADMIN CAP_CHOWN CAP_FOWNER CAP_DAC_OVERRIDE";
          NoNewPrivileges = true;
        };
      };
    })

    (mkIf cfg.portal.enable {
      xdg.portal = {
        enable = true;
        extraPortals = [ cfg.package ];
        config = lib.genAttrs cfg.portal.desktops (_: {
          "org.freedesktop.impl.portal.FileChooser" = "prism";
        });
      };
    })

    (mkIf cfg.fileManager.enable {
      xdg.mime.defaultApplications."inode/directory" = "prism.desktop";
      services.dbus.packages = [ fileManagerService ];
    })
  ]);
}
