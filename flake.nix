{
  description = "prism: a file explorer and a file picker for Wayland's portals, over an eBPF-kept index";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  # What the flake gives (docs/nixos.md):
  #
  #   packages.<system>.prism (and .default)   prism: `environment.systemPackages = [ prism.packages.${system}.prism ]`
  #   overlays.default                         the same, as `pkgs.prism`
  #   nixosModules.prism (and .default)        `programs.prism`: the package, its daemon, the portal and the file manager
  #   darwinModules.prism (and .default)       nix-darwin's `programs.prism`: Prism.app, `prism`, and its daemon's agent
  #   apps.<system>.default                    `nix run`
  #   devShells.<system>.default               what building it with Gradle needs
  #   checks.<system>                          the package built, and the module evaluated each way it can be set
  #   formatter.<system>                       `nix fmt`
  outputs =
    { self, nixpkgs }:
    let
      inherit (nixpkgs) lib;

      # Where prism is built: the lock of what Gradle fetches (nix/deps.json) holds this system's artifacts.
      systems = [ "x86_64-linux" ];

      eachSystem = perSystem: lib.genAttrs systems (system: perSystem nixpkgs.legacyPackages.${system});

      # A Mac's prism is another package (nix/package-darwin.nix): Prism.app, with a lock of its own.
      macSystems = [ "aarch64-darwin" ];

      eachMac = perSystem: lib.genAttrs macSystems (system: perSystem nixpkgs.legacyPackages.${system});
    in
    {
      packages =
        eachSystem (pkgs: rec {
          prism = pkgs.callPackage ./nix/package.nix { };
          default = prism;
        })
        // eachMac (pkgs: rec {
          prism = pkgs.callPackage ./nix/package-darwin.nix { };
          default = prism;
        });

      overlays.default = final: _prev: {
        prism = final.callPackage (
          if final.stdenv.hostPlatform.isDarwin then ./nix/package-darwin.nix else ./nix/package.nix
        ) { };
      };

      # nix-darwin's: `programs.prism`, the package and its daemon as a launchd agent.
      darwinModules = rec {
        prism = import ./nix/darwin-module.nix self;
        default = prism;
      };

      nixosModules = rec {
        prism = import ./nix/module.nix self;
        default = prism;
      };

      apps = (eachSystem (pkgs: {
        default = {
          type = "app";
          program = lib.getExe self.packages.${pkgs.stdenv.hostPlatform.system}.prism;
          meta.description = "prism: the file explorer, in the prism already running where there is one";
        };
      }))
      // eachMac (pkgs: {
        default = {
          type = "app";
          program = lib.getExe self.packages.${pkgs.stdenv.hostPlatform.system}.prism;
          meta.description = "prism: the file explorer, in the prism already running where there is one";
        };
      });

      devShells = (eachSystem (
        pkgs:
        let
          prism = self.packages.${pkgs.stdenv.hostPlatform.system}.prism;
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              jdk25
              gradle_9
              # The BPF target. The wrapped clang adds hardening flags (stack protector, zeroed call-used registers)
              # the BPF target refuses, so the build is pointed at the unwrapped one.
              llvmPackages.clang-unwrapped
              llvmPackages.llvm
              libbpf
            ];

            JAVA_HOME = pkgs.jdk25.home;
            PRISM_CLANG = prism.PRISM_CLANG;
            # What `./gradlew :client:nativeImage` compiles the client with; nothing else in the build needs it.
            GRAALVM_HOME = prism.graalvm;
            # What the JVM loads at run time, as the package's wrapper gives it (nix/package.nix says what and why).
            LD_LIBRARY_PATH = prism.libraryPath;
          };
        }
      ))
      // eachMac (pkgs: {
        default = pkgs.mkShell {
          packages = with pkgs; [
            jdk25
            gradle_9
          ];

          JAVA_HOME = pkgs.jdk25.home;
          # What `./gradlew :client:nativeImage` compiles the client with; nothing else in the build needs it.
          GRAALVM_HOME = pkgs.graalvmPackages.graalvm-ce;
        };
      });

      checks = eachSystem (
        pkgs:
        let
          system = pkgs.stdenv.hostPlatform.system;

          # A NixOS that has the module, set as [settings] say, evaluated: its options read, nothing built.
          evaluated =
            settings:
            (lib.nixosSystem {
              inherit system;
              modules = [
                self.nixosModules.prism
                {
                  programs.prism = {
                    enable = true;
                  }
                  // settings;
                  boot.loader.grub.enable = false;
                  fileSystems."/" = {
                    device = "none";
                    fsType = "tmpfs";
                  };
                  system.stateVersion = lib.trivial.release;
                }
              ];
            }).config;

          asDefault = evaluated { };

          withoutPortal = evaluated {
            portal.enable = false;
            daemon.roots = [ "/home" ];
            watcher.enable = true;
          };

          withoutDaemon = evaluated {
            daemon.enable = false;
            fileManager.enable = false;
          };

          # A system that has portal configurations of its own, for a desktop and for every desktop.
          withDesktops =
            (lib.nixosSystem {
              inherit system;
              modules = [
                self.nixosModules.prism
                (
                  { pkgs, ... }:
                  {
                    programs.prism.enable = true;
                    xdg.portal = {
                      enable = true;
                      extraPortals = [ pkgs.xdg-desktop-portal-gtk ];
                      config = {
                        niri = {
                          default = [ "gtk" ];
                          "org.freedesktop.impl.portal.ScreenCast" = [ "gnome" ];
                        };
                        common.default = [ "gtk" ];
                      };
                    };
                    boot.loader.grub.enable = false;
                    fileSystems."/" = {
                      device = "none";
                      fsType = "tmpfs";
                    };
                    system.stateVersion = lib.trivial.release;
                  }
                )
              ];
            }).config;

          chooserLine = "org.freedesktop.impl.portal.FileChooser=prism";

          said = condition: message: lib.assertMsg condition "prism's module: ${message}";
        in
        {
          package = self.packages.${system}.prism;

          # The module, each way it is meant to be set: what it says of the unit, the portal and the file manager.
          module =
            assert said (
              asDefault.environment.etc."xdg/xdg-desktop-portal/portals.conf".text
              == "[preferred]\n${chooserLine}\n"
            ) "the file chooser is prism's by default, in a portals.conf of that one line";
            assert said (
              withDesktops.environment.etc."xdg/xdg-desktop-portal/niri-portals.conf".text
              == "[preferred]\ndefault=gtk\norg.freedesktop.impl.portal.ScreenCast=gnome\n\n${chooserLine}\n"
            ) "a desktop's own portal configuration is given prism's line after its own";
            assert said (
              withDesktops.environment.etc."xdg/xdg-desktop-portal/portals.conf".text
              == "[preferred]\ndefault=gtk\n\n${chooserLine}\n"
            ) "and so is the common one beside it";
            assert said (
              !(withoutPortal.environment.etc ? "xdg/xdg-desktop-portal/portals.conf")
            ) "with the portal off, no portal configuration is written";
            assert said
              (lib.hasSuffix "/libexec/prism/prism daemon --foreground" asDefault.systemd.user.services.prism.serviceConfig.ExecStart)
              "the daemon is run in the foreground, with nothing turned off, by default";
            assert said (
              asDefault.xdg.mime.defaultApplications."inode/directory" == "prism.desktop"
            ) "folders open in prism by default";
            assert said (
              !(asDefault.systemd.user.services.prism.environment ? PATH)
            ) "the daemon's unit sets no PATH of its own";
            assert said (
              !(asDefault.systemd.services ? prism-watcher)
            ) "the watcher is not loaded unless asked for";
            assert said
              (lib.hasSuffix "/libexec/prism/prism probe load --group prism" withoutPortal.systemd.services.prism-watcher.serviceConfig.ExecStart)
              "with the watcher on, a system service loads it for the group";
            assert said (withoutPortal.users.groups ? prism) "with the watcher on, its group is made";
            assert said (
              !withoutPortal.xdg.portal.enable
            ) "with the portal off, the portal is not turned on for prism";
            assert said
              (lib.hasSuffix "daemon --foreground --no-file-chooser /home" withoutPortal.systemd.user.services.prism.serviceConfig.ExecStart)
              "with the portal off, the daemon runs all the same, without the file chooser";
            assert said (
              withoutDaemon.systemd.user.services.prism.wantedBy == [ ]
            ) "with the daemon off, nothing starts prism with the session";
            assert said
              (lib.hasSuffix "/libexec/prism/prism portal serve --no-file-manager" withoutDaemon.systemd.user.services.prism.serviceConfig.ExecStart)
              "with the daemon off, what D-Bus starts for the portal keeps no index";
            assert said (
              !(withoutDaemon.xdg.mime.defaultApplications ? "inode/directory")
            ) "with the file manager off, folders are left to whatever opens them";
            pkgs.runCommand "prism-module-evaluates" { } "touch $out";
        }
      );

      formatter = eachSystem (pkgs: pkgs.nixfmt-tree);
    };
}
