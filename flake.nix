{
  description = "prism: a file picker for Wayland's portals, over an eBPF-kept index";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };

      # What the JVM loads at run time: libbpf for the watcher, and what SDL's windows (prism.window) speak and draw
      # with: Wayland and its keyboard, X11, OpenGL and EGL through glvnd, and libdecor for title bars where the
      # compositor draws none.
      runtimeLibs = with pkgs; [
        libbpf
        libGL
        wayland
        libxkbcommon
        libdecor
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
        PRISM_CLANG = "${pkgs.llvmPackages.clang-unwrapped}/bin/clang";
        # NixOS's graphics drivers (/run/opengl-driver/lib) first: glvnd finds NVIDIA's EGL and GL there by name, as
        # NixOS's own packages do through their run path, and without them only Mesa's is found, which cannot drive
        # an NVIDIA card ("failed to create dri2 screen").
        LD_LIBRARY_PATH = "${pkgs.addDriverRunpath.driverLink}/lib:" + pkgs.lib.makeLibraryPath runtimeLibs;
      };
    };
}
