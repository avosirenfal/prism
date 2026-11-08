{
  description = "barion: a file picker for Wayland's portals, over an eBPF-kept index";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };

      # What the JVM loads at run time: libbpf for the watcher, and what Compose Desktop's windows draw with.
      runtimeLibs = with pkgs; [
        libbpf
        libGL
        wayland
        libxkbcommon
        fontconfig
        xorg.libX11
        xorg.libXext
        xorg.libXrender
        xorg.libXtst
        xorg.libXi
        xorg.libXrandr
        xorg.libXcursor
      ];
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          jdk25
          gradle
          # The BPF target. The wrapped clang adds hardening flags (stack protector, zeroed call-used registers) the
          # BPF target refuses, so the build is pointed at the unwrapped one.
          llvmPackages.clang-unwrapped
          llvmPackages.llvm
          libbpf
        ];

        JAVA_HOME = pkgs.jdk25.home;
        BARION_CLANG = "${pkgs.llvmPackages.clang-unwrapped}/bin/clang";
        LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath runtimeLibs;
      };
    };
}
