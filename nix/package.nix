# prism, built from this checkout: its jars (Gradle's `:app:installJars`), run by a wrapper with nixpkgs' Java, and
# what makes a desktop know of it (a desktop entry, the portal backend's file, a D-Bus service file, a systemd user
# unit). docs/nixos.md says how it is used; nix/module.nix is the NixOS module that sets it up.
#
# Gradle fetches nothing while it builds: every artifact it needs is in deps.json, by hash (nixpkgs' way of building
# with Gradle). After a change to a dependency, or to the Gradle or Kotlin version, write it again:
#
#   $(nix build .#prism.mitmCache.updateScript --print-out-paths --no-link)
{
  lib,
  stdenv,
  gradle_9,
  jdk25,
  llvmPackages,
  makeDesktopItem,
  copyDesktopItems,
  addDriverRunpath,
  util-linux,
  libbpf,
  libGL,
  wayland,
  libxkbcommon,
  libdecor,
  fontconfig,
  libx11,
  libxext,
  libxrender,
  libxtst,
  libxi,
  libxrandr,
  libxcursor,
  # More words for the Java that runs prism, after prism's own: `-Xmx2g`, say.
  extraJvmArgs ? [ ],
}:

let
  # What the JVM loads at run time and finds by name: libbpf for the watcher, and what SDL's windows (prism.window)
  # speak and draw with: Wayland and its keyboard, X11, OpenGL and EGL through glvnd, libdecor for title bars where
  # the compositor draws none, and fontconfig for Skia's fonts. They are prebuilt libraries inside jars (LWJGL's SDL,
  # Skia), with no run path of their own to find these by, so the wrapper puts these on the loader's path.
  runtimeLibs = [
    libbpf
    libGL
    wayland
    libxkbcommon
    libdecor
    fontconfig
    libx11
    libxext
    libxrender
    libxtst
    libxi
    libxrandr
    libxcursor
  ];

  # NixOS's graphics drivers (/run/opengl-driver/lib) first: glvnd finds NVIDIA's EGL and GL there by name, and
  # without them only Mesa's is found, which cannot drive an NVIDIA card.
  libraryPath = "${addDriverRunpath.driverLink}/lib:${lib.makeLibraryPath runtimeLibs}";

  # The names the session bus and the portal know prism by, which prism itself serves (portal/filechooser.kt): a
  # test of prism's (PackagingTest) reads this file, and fails where the two differ.
  backendName = "org.freedesktop.impl.portal.desktop.prism";
  chooserInterface = "org.freedesktop.impl.portal.FileChooser";
in
stdenv.mkDerivation (finalAttrs: {
  pname = "prism";
  version = "0.1.0";

  # What Gradle builds from, and nothing else: a change to the docs, or to the flake, builds nothing again.
  src = lib.fileset.toSource {
    root = ../.;
    fileset = lib.fileset.difference ../. (
      lib.fileset.unions [
        ../docs
        ../tools
        ../nix
        ../flake.nix
        ../flake.lock
        ../README.md
        (lib.fileset.maybeMissing ../.github)
        (lib.fileset.maybeMissing ../.envrc)
        (lib.fileset.maybeMissing ../.direnv)
        (lib.fileset.maybeMissing ../.gradle)
        (lib.fileset.maybeMissing ../.kotlin)
        (lib.fileset.maybeMissing ../build)
        (lib.fileset.maybeMissing ../result)
      ]
    );
  };

  nativeBuildInputs = [
    gradle_9
    copyDesktopItems
  ];

  mitmCache = gradle_9.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./deps.json;
  };

  # The modules are compiled for Java 25 (gradle/libs.versions.toml), whichever Java runs Gradle: the one here is
  # nixpkgs', given to Gradle's toolchains, and none is fetched.
  gradleFlags = [
    "-Dfile.encoding=utf-8"
    "-Dorg.gradle.java.installations.auto-download=false"
    "-Dorg.gradle.java.installations.auto-detect=false"
    "-Dorg.gradle.java.installations.paths=${jdk25}"
    # The cache of configurations is for a developer's second build: a package is built once.
    "--no-configuration-cache"
  ];

  # The watcher is compiled by clang's BPF target; the wrapped clang adds hardening flags that target refuses.
  env.PRISM_CLANG = "${llvmPackages.clang-unwrapped}/bin/clang";

  gradleBuildTask = ":app:installJars";
  # What is fetched for the lock is what the build fetches: the same task, run against the network.
  gradleUpdateTask = finalAttrs.gradleBuildTask;

  # prism's tests are Gradle's to run (`./gradlew build`), on a machine with a display server's and a bus's programs.
  doCheck = false;

  desktopItems = [
    (makeDesktopItem {
      name = "prism";
      desktopName = "prism";
      genericName = "File Manager";
      comment = "Browse the file system";
      exec = "prism %U";
      icon = "system-file-manager";
      # Wayland's app id and X11's class, which prism's windows have (APP_ID in app/main.kt).
      startupWMClass = "prism";
      mimeTypes = [ "inode/directory" ];
      categories = [
        "System"
        "FileTools"
        "FileManager"
      ];
      keywords = [
        "files"
        "folders"
        "explorer"
      ];
    })
  ];

  installPhase = ''
    runHook preInstall

    lib=$out/share/prism/lib
    mkdir -p $lib $out/bin
    cp app/build/install/lib/*.jar $lib/

    # The jars in an order of their own: those every start reads from first (a prism that only hands its words to
    # the one already running reads little else), then the rest, by name. Written out in full rather than left to
    # Java to list, so that it is the same wherever the package is copied to.
    first="$(ls $lib/app-*.jar $lib/cli-*.jar $lib/org.jetbrains.kotlin-kotlin-stdlib-*.jar $lib/index-*.jar)"
    classpath="$( (echo "$first"; ls $lib/*.jar | grep -v -x -F "$first") | paste -sd: )"

    # What Java is given, the same every time: here once, for the wrapper and for the archive made below.
    cat > $out/share/prism/java.args <<ARGS
    --enable-native-access=ALL-UNNAMED
    -Djava.awt.headless=true
    -Dprism.launcher=$out/bin/prism
    -XX:G1PeriodicGCInterval=300000
    -XX:MinHeapFreeRatio=10
    -XX:MaxHeapFreeRatio=30
    ${lib.concatStringsSep "\n" extraJvmArgs}
    -cp
    $classpath
    ARGS
    sed -i 's/^ *//; /^$/d' $out/share/prism/java.args

    # A cache of the classes a start loads, already read and linked (Java's "ahead-of-time cache"), made by running
    # the start that matters most once: a prism asking whether one is running, which is what a prism does before it
    # hands its words to it. It takes about a third off that. Java uses such a cache only where the jars are as they
    # were when it was made, by size and by date, and the store gives every file the same date, so they are given
    # it first. A cache that cannot be used costs only the time it would have saved.
    touch --no-dereference --date=@1 $lib/*.jar
    XDG_RUNTIME_DIR=$TMPDIR HOME=$TMPDIR ${jdk25}/bin/java -XX:AOTCacheOutput=$out/share/prism/prism.aot \
      @$out/share/prism/java.args prism.app.MainKt status > /dev/null 2>&1 || true
    test -s $out/share/prism/prism.aot

    cat > $out/bin/prism <<WRAPPER
    #!${stdenv.shell}
    # The libraries prism loads by name, found; and what the path was kept, to be given back to whatever prism
    # starts, which is the user's program and not prism's (desktop/environment.kt).
    export PRISM_RESTORE_LD_LIBRARY_PATH="\''${LD_LIBRARY_PATH-}"
    export LD_LIBRARY_PATH="${libraryPath}\''${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
    # setsid, which starts what prism opens in a session of its own: after everything else, for where it is not there.
    export PRISM_RESTORE_PATH="\''${PATH-}"
    export PATH="\''${PATH:+\$PATH:}${util-linux}/bin"
    exec ${jdk25}/bin/java -XX:AOTCache=$out/share/prism/prism.aot -Xlog:aot=off -Xlog:cds=off \\
      @$out/share/prism/java.args prism.app.MainKt "\$@"
    WRAPPER
    sed -i 's/^    //' $out/bin/prism
    chmod +x $out/bin/prism

    # The portal's backend: what tells xdg-desktop-portal there is one called prism, and D-Bus how to start it,
    # which is by starting the daemon's unit (it is the daemon that serves it).
    mkdir -p $out/share/xdg-desktop-portal/portals $out/share/dbus-1/services $out/share/systemd/user
    cat > $out/share/xdg-desktop-portal/portals/prism.portal <<PORTAL
    [portal]
    DBusName=${backendName}
    Interfaces=${chooserInterface};
    PORTAL
    cat > $out/share/dbus-1/services/${backendName}.service <<SERVICE
    [D-BUS Service]
    Name=${backendName}
    Exec=$out/bin/prism daemon --foreground
    SystemdService=prism.service
    SERVICE

    # The daemon, as the user's systemd runs it where nothing else says how (NixOS's module says it itself).
    cat > $out/share/systemd/user/prism.service <<UNIT
    [Unit]
    Description=prism: the index kept current, and every prism's windows
    Documentation=https://github.com/avosirenfal/prism
    PartOf=graphical-session.target
    After=graphical-session.target

    [Service]
    ExecStart=$out/bin/prism daemon --foreground
    Restart=on-failure
    RestartSec=2
    SuccessExitStatus=143

    [Install]
    WantedBy=graphical-session.target
    UNIT
    # The watcher's loader, as the system's systemd runs it where nothing else says how (NixOS's module says it
    # itself): root's, once, at boot, leaving the watcher in the kernel for the members of a group to read
    # (docs/running.md). No sandboxing that gives it mounts of its own: the file system it mounts is for others.
    mkdir -p $out/lib/systemd/system $out/lib/sysusers.d
    cat > $out/lib/systemd/system/prism-watcher.service <<UNIT
    [Unit]
    Description=prism: the watcher loaded into the kernel, for prism's daemon to read
    Documentation=https://github.com/avosirenfal/prism

    [Service]
    Type=oneshot
    RemainAfterExit=yes
    ExecStart=$out/bin/prism probe load --group prism
    ExecStop=$out/bin/prism probe unload
    CapabilityBoundingSet=CAP_BPF CAP_PERFMON CAP_SYS_ADMIN CAP_CHOWN CAP_FOWNER CAP_DAC_OVERRIDE
    NoNewPrivileges=yes

    [Install]
    WantedBy=multi-user.target
    UNIT
    cat > $out/lib/sysusers.d/prism.conf <<GROUP
    # Whoever is in it may read the names of the files made on this machine, as prism's watcher sees them.
    g prism -
    GROUP
    sed -i 's/^    //' $out/lib/systemd/system/prism-watcher.service $out/lib/sysusers.d/prism.conf

    sed -i 's/^    //' $out/share/xdg-desktop-portal/portals/prism.portal \
      $out/share/dbus-1/services/${backendName}.service $out/share/systemd/user/prism.service

    runHook postInstall
  '';

  passthru = {
    inherit backendName libraryPath;
    jdk = jdk25;
  };

  meta = {
    description = "A file picker for Wayland's portals and a file explorer, over an index of every file that the kernel keeps current";
    homepage = "https://github.com/avosirenfal/prism";
    mainProgram = "prism";
    platforms = [ "x86_64-linux" ];
    sourceProvenance = with lib.sourceTypes; [
      fromSource
      # Gradle's dependencies, and the libraries inside them (LWJGL's SDL, Skia).
      binaryBytecode
      binaryNativeCode
    ];
  };
})
