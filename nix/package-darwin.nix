# prism on a Mac, built from this checkout: Prism.app, as Compose's packaging makes it (`:app:createDistributable`: an
# application whose own program runs the Java inside it, so the Dock, the menu bar and the system's permissions know
# it as Prism), and the client that asks the prism already running, compiled ahead of time by GraalVM
# (`:client:nativeImage`), which is the `prism` a shell starts. nix/darwin-module.nix is the nix-darwin module that
# sets it up; docs/platform.md says how it is used.
#
# Gradle fetches nothing while it builds: every artifact it needs is in deps-darwin.json, by hash. A Mac's are not
# Linux's (Skia's and SDL's natives are each platform's), so it is a lock of its own, written on a Mac, and again
# after a change to a dependency or to the Gradle or Kotlin version:
#
#   $(nix build .#prism.mitmCache.updateScript --print-out-paths --no-link)
{
  lib,
  stdenv,
  gradle_9,
  temurin-bin-25,
  graalvmPackages,
}:

let
  # The Java the application is built with and carries inside it: Adoptium's build, which is linked against the
  # system alone, where nixpkgs' own is linked against the Nix store. So Prism.app runs on a Mac with no Nix, which is
  # what the disk image made of it is for (nix/dmg.nix).
  jdk = temurin-bin-25;

  # Where in the package the Java prism is: the application's own program, which the daemon's agent runs directly
  # and the client hands its words to (it looks for it beside itself: prism.client, main.kt, javaPrism).
  application = "Applications/Prism.app";
  javaPrism = "${application}/Contents/MacOS/Prism";
in
stdenv.mkDerivation (finalAttrs: {
  pname = "prism";
  version = "0.1.0";

  # What Gradle builds from, and nothing else, but for the icon, which the application is given.
  src = lib.fileset.toSource {
    root = ../.;
    fileset = lib.fileset.difference ../. (
      lib.fileset.unions [
        ../docs
        ../tools
        ../nix/package.nix
        ../nix/package-darwin.nix
        ../nix/module.nix
        ../nix/darwin-module.nix
        ../nix/deps.json
        ../nix/deps-darwin.json
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
  ];

  mitmCache = gradle_9.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./deps-darwin.json;
  };

  gradleFlags = [
    "-Dfile.encoding=utf-8"
    "-Dorg.gradle.java.installations.auto-download=false"
    "-Dorg.gradle.java.installations.auto-detect=false"
    "-Dorg.gradle.java.installations.paths=${jdk}"
    "--no-configuration-cache"
  ];

  # The client is compiled ahead of time by GraalVM's native-image, which Gradle is told the place of.
  env.GRAALVM_HOME = "${graalvmPackages.graalvm-ce}";

  gradleBuildTask = ":app:createDistributable :client:nativeImage";
  # What is fetched for the lock is what the application is built from: compiling the client fetches nothing more.
  gradleUpdateTask = ":app:createDistributable";

  doCheck = false;

  installPhase = ''
    runHook preInstall

    mkdir -p $out/Applications $out/bin
    cp -R app/build/compose/binaries/main/app/Prism.app $out/${application}

    # The client beside the application's own program, where it looks for it; and on the PATH, as `prism`.
    install -m755 client/build/native/prism $out/${application}/Contents/MacOS/prism-client
    ln -s $out/${application}/Contents/MacOS/prism-client $out/bin/prism

    # Sealed again with the client in it: what an Apple-silicon Mac runs is sealed, if only for no one in particular.
    /usr/bin/codesign --force --deep --sign - $out/${application} || true

    runHook postInstall
  '';

  passthru = {
    inherit javaPrism application jdk;
    graalvm = graalvmPackages.graalvm-ce;
  };

  meta = {
    description = "A file explorer and a launcher, over an index of every file that FSEvents keeps current";
    homepage = "https://github.com/avosirenfal/prism";
    mainProgram = "prism";
    platforms = [ "aarch64-darwin" ];
    sourceProvenance = with lib.sourceTypes; [
      fromSource
      binaryBytecode
      binaryNativeCode
    ];
  };
})
