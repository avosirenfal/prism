# prism on a Mac, as nix-darwin sets it up (`programs.prism`): Prism.app and the `prism` a shell starts installed, and
# the daemon run in every user's session by launchd, started as they log in and again should it fail. It is the same
# agent `prism agent install` makes where there is no Nix (the same label, so the two are never both running).
self:
{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.programs.prism;

  javaPrism = "${cfg.package}/${cfg.package.javaPrism}";

  inherit (lib)
    mkEnableOption
    mkIf
    mkOption
    types
    ;

  command = [
    javaPrism
    "daemon"
    "--foreground"
  ]
  ++ cfg.daemon.extraArgs
  ++ map toString cfg.daemon.roots;

  # Where what the daemon says is kept: beside the rest of what prism keeps of its own doing, in the user's Library.
  # launchd knows no user's home, so a shell says it, and is then the daemon itself.
  state = "$HOME/Library/Application Support/prism";
in
{
  options.programs.prism = {
    enable = mkEnableOption "prism, a file explorer and launcher over an index of every file that FSEvents keeps current";

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
          Whether to run prism's daemon in every user's session (a launchd agent, `dev.prism.daemon`), started as
          they log in.

          The daemon keeps the index current, holds the launcher's key, and opens every prism window: `prism` run
          from a terminal hands what it was asked to the daemon and is done.

          What it says is in `~/Library/Application Support/prism/daemon.log`.
        '';
      };

      roots = mkOption {
        type = types.listOf types.path;
        default = [ ];
        example = [ "/Users" ];
        description = "The folders the daemon indexes. Everything (`/`) where none is named, less what its rules leave out.";
      };

      extraArgs = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [
          "--theme"
          "dark"
        ];
        description = "More words for `prism daemon`: `prism daemon --help` lists them.";
      };
    };
  };

  config = mkIf cfg.enable {
    # Prism.app is linked among the system's applications by nix-darwin, and `prism` is on the PATH.
    environment.systemPackages = [ cfg.package ];

    launchd.user.agents.prism = mkIf cfg.daemon.enable {
      serviceConfig = {
        Label = "dev.prism.daemon";
        ProgramArguments = [
          "/bin/sh"
          "-c"
          ''mkdir -p "${state}" && exec ${lib.escapeShellArgs command} >> "${state}/daemon.log" 2>&1''
        ];
        RunAtLoad = true;
        # Started again where it ends by failing, and not where it is stopped or quit.
        KeepAlive.SuccessfulExit = false;
        # A program the user works with, in their graphical session, where windows can be opened.
        ProcessType = "Interactive";
        LimitLoadToSessionType = "Aqua";
      };
    };
  };
}
