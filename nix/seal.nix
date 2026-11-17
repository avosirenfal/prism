# How a Mac's application is sealed (signed), as shell: a function `seal APP` that signs everything in APP that is
# code, from the inside out, and then APP itself, with the identity given ("-" is no one in particular: "ad hoc").
#
# Every library and program is signed by name. `codesign --deep` signs only what is where a bundle keeps code, and
# the Java inside Prism.app is not (Contents/runtime/Contents/Home/lib): its libraries would keep Adoptium's own
# signature, and macOS does not load into one process code signed by two different teams.
#
# The hardened runtime, which is what makes macOS hold a process to one team's code, is asked for only with a real
# identity, since Apple's notary wants it; and then with the leave a Java needs (to make code as it runs, and to
# load the libraries LWJGL and Skia unpack from their jars, which are signed by their own makers).
{ lib, writeText }:

identity:

let
  entitlements = writeText "prism.entitlements" ''
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
    <dict>
      <key>com.apple.security.cs.allow-jit</key><true/>
      <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
      <key>com.apple.security.cs.disable-library-validation</key><true/>
      <key>com.apple.security.cs.allow-dyld-environment-variables</key><true/>
    </dict>
    </plist>
  '';

  how =
    if identity == "-" then
      "--sign -"
    else
      "--sign ${lib.escapeShellArg identity} --options runtime --timestamp --entitlements ${entitlements}";
in
''
  seal() {
    find "$1" -type f \( -name '*.dylib' -o -name '*.jnilib' -o -perm -u+x \) -print0 |
      while IFS= read -r -d "" file; do
        if /usr/bin/file -b "$file" | grep -q 'Mach-O'; then
          /usr/bin/codesign --force ${how} "$file"
        fi
      done
    if [ -d "$1/Contents/runtime" ]; then
      /usr/bin/codesign --force ${how} "$1/Contents/runtime" || true
    fi
    /usr/bin/codesign --force ${how} "$1"
    # Said, and not what the build fails by: a bundle macOS runs can still be one its strictest reading faults.
    /usr/bin/codesign --verify --deep --strict "$1" || echo "seal: $1 does not verify strictly" >&2
  }
''
