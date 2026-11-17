# prism for a Mac that has no Nix: Prism.app in a disk image, beside a link to Applications to drag it onto. The
# application is the package's (nix/package-darwin.nix), which is built with Adoptium's Java, linked against the
# system alone, so that it runs on a Mac with no Nix store. It is sealed for no one in particular ("ad hoc"), or with
# the identity named (`signingIdentity`: a "Developer ID Application: ..." certificate in the builder's keychain).
#
#   nix build .#prism-dmg     # result/Prism-0.1.0-macos-arm64.dmg
#
# The image is made by macOS's own tools (hdiutil, codesign), which are the system's and not nixpkgs': the build
# reaches them where they are, which a Mac's builds may (its sandbox is off unless it was turned on).
{
  lib,
  stdenv,
  runCommand,
  prism,
  signingIdentity ? "-",
}:

let
  name = "Prism-${prism.version}-macos-${
    if stdenv.hostPlatform.isAarch64 then "arm64" else stdenv.hostPlatform.parsed.cpu.name
  }";
in
runCommand name
  {
    __noChroot = true;
    meta = prism.meta // {
      description = "prism for a Mac without Nix: Prism.app in a disk image";
    };
  }
  ''
    mkdir -p stage $out
    cp -R ${prism}/${prism.application} stage/Prism.app
    chmod -R u+w stage/Prism.app

    # Sealed again: it was copied out of the store, and is to run somewhere else.
    /usr/bin/codesign --force --deep --options runtime --sign ${lib.escapeShellArg signingIdentity} stage/Prism.app
    ln -s /Applications stage/Applications
    /usr/bin/hdiutil create -volname Prism -srcfolder stage -ov -format UDZO $out/${name}.dmg
  ''
