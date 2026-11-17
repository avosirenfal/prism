# prism for a Linux that has no Nix: one folder, in an archive, that runs wherever it is unpacked.
#
# prism is a Java program, so it cannot be one statically linked file; what "needs nothing of the system" comes to is
# this. Its jars are the package's own (they are the same on every Linux). The Java that runs them is in the folder
# too: Adoptium's build (Temurin), as Adoptium publishes it, which is linked against a C library old enough for every
# distribution in use, where nixpkgs' own Java is linked against the Nix store's. And the `prism` a shell starts, the
# client, is compiled ahead of time against musl and linked statically: it is one file that needs nothing at all.
#
# What is still the system's is what a desktop's programs all share: its Wayland or X11 libraries and its OpenGL,
# which SDL (inside the jars) finds by name; and libbpf, where the watcher is wanted.
#
#   nix build .#prism-portable     # result/prism-0.1.0-linux-x86_64.tar.gz
{
  lib,
  stdenv,
  runCommand,
  prism,
  temurin-jre-bin-25,
  graalvmPackages,
}:

let
  name = "prism-${prism.version}-linux-${stdenv.hostPlatform.parsed.cpu.name}";

  # Adoptium's archive itself, as nixpkgs fetches it, before nixpkgs makes it the Nix store's.
  runtime = builtins.head temurin-jre-bin-25.srcs;

  # The client, compiled again for musl and linked statically, from the jars the package was built to.
  client =
    runCommand "prism-client-static"
      {
        nativeBuildInputs = [ graalvmPackages.graalvm-ce-musl ];
      }
      ''
        lib=${prism}/share/prism/lib
        mkdir -p $out/bin
        native-image \
          --static --libc=musl \
          --class-path "$(ls $lib/client-*.jar $lib/cli-*.jar $lib/platform-*.jar $lib/org.jetbrains.kotlin-kotlin-stdlib-*.jar | paste -sd:)" \
          --install-exit-handlers \
          -march=compatibility \
          -o $out/bin/prism \
          prism.client.MainKt
      '';
in
runCommand name
  {
    passthru = { inherit client; };
    meta = prism.meta // {
      description = "prism for a Linux without Nix: its jars, a Java to run them, and a statically linked client, in one archive";
    };
  }
  ''
    root=${name}
    mkdir -p $root/bin $root/libexec/prism $root/share/prism/lib $root/share/prism/runtime \
      $root/share/applications $root/share/icons/hicolor/scalable/apps

    cp ${prism}/share/prism/lib/*.jar $root/share/prism/lib/
    tar -xzf ${runtime} --strip-components=1 -C $root/share/prism/runtime
    install -m755 ${client}/bin/prism $root/bin/prism
    cp ${prism}/share/icons/hicolor/scalable/apps/prism.svg $root/share/icons/hicolor/scalable/apps/

    # The Java prism, where the client looks for it (bin/../libexec/prism/prism): found from wherever the folder is.
    cat > $root/libexec/prism/prism <<'LAUNCHER'
    #!/bin/sh
    here=$(dirname "$(readlink -f "$0")")
    root=$(cd "$here/../.." && pwd)
    exec "$root/share/prism/runtime/bin/java" \
      --enable-native-access=ALL-UNNAMED \
      -Djava.awt.headless=true \
      -Dprism.launcher="$root/libexec/prism/prism" \
      -XX:G1PeriodicGCInterval=300000 -XX:MinHeapFreeRatio=20 -XX:MaxHeapFreeRatio=50 \
      -cp "$root/share/prism/lib/*" \
      prism.app.MainKt "$@"
    LAUNCHER
    sed -i 's/^    //' $root/libexec/prism/prism
    chmod +x $root/libexec/prism/prism

    # A desktop entry to copy into ~/.local/share/applications, once `prism` is on the PATH.
    cat > $root/share/applications/prism.desktop <<'ENTRY'
    [Desktop Entry]
    Version=1.5
    Type=Application
    Name=Prism
    GenericName=File Manager
    Comment=Browse and search your files
    Icon=prism
    TryExec=prism
    Exec=prism %U
    Terminal=false
    StartupNotify=true
    StartupWMClass=prism
    MimeType=inode/directory;
    Categories=System;FileTools;FileManager;
    Keywords=files;folders;explorer;browse;search;directory;
    Actions=new-window;

    [Desktop Action new-window]
    Name=New Window
    Exec=prism
    ENTRY
    sed -i 's/^    //' $root/share/applications/prism.desktop

    mkdir -p $out
    tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@1 -czf $out/${name}.tar.gz $root
  ''
