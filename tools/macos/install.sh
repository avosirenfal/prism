#!/bin/bash
# prism set up on a Mac, with no Nix: Prism.app built and put among the applications, `prism` put on the PATH, and the
# daemon made a launchd agent, started now and whenever you log in. Run again, it puts a newer build in the old one's
# place. `install.sh --uninstall` takes all three away. docs/platform.md, "Setting it up on a Mac", says more.
#
#   tools/macos/install.sh [--uninstall]
#
#   PRISM_APPLICATIONS   where Prism.app goes (/Applications)
#   PRISM_BIN            where `prism` is linked (/usr/local/bin); "none" for nowhere
#   GRAALVM_HOME         a GraalVM, to compile the `prism` a shell starts ahead of time; without one, `prism` is the
#                        application's own program, which does the same a moment slower
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
applications="${PRISM_APPLICATIONS:-/Applications}"
bin="${PRISM_BIN:-/usr/local/bin}"
app="$applications/Prism.app"
program="$app/Contents/MacOS/Prism"
client="$app/Contents/MacOS/prism-client"

if [ "$(uname -s)" != "Darwin" ]; then
	echo "install.sh is for macOS; on Linux, see docs/nixos.md and docs/portal.md" >&2
	exit 1
fi

# A link of prism's own making, and no file of anyone else's, is taken away or replaced.
unlinked() {
	if [ "$bin" != "none" ] && [ -L "$bin/prism" ]; then
		case "$(readlink "$bin/prism")" in
			"$app"/*) rm -f "$bin/prism" 2>/dev/null || sudo rm -f "$bin/prism" ;;
		esac
	fi
}

# The agent stopped and taken away, where there is one and a prism to ask.
stopped() {
	if [ -x "$program" ]; then
		"$program" agent uninstall || true
	fi
}

if [ "${1:-}" = "--uninstall" ]; then
	stopped
	unlinked
	rm -rf "$app"
	echo "prism is uninstalled. Its preferences and index are in ~/Library/Application Support/prism and ~/Library/Caches/prism."
	exit 0
fi

cd "$root"
./gradlew :app:createDistributable
built="$root/app/build/compose/binaries/main/app/Prism.app"
if [ ! -d "$built" ]; then
	echo "the build made no application at $built" >&2
	exit 1
fi

if [ -n "${GRAALVM_HOME:-}" ]; then
	./gradlew :client:nativeImage
fi

# The one running stopped before its application is replaced under it.
stopped
unlinked
rm -rf "$app"
mkdir -p "$applications"
ditto "$built" "$app"

linked="$program"
if [ -n "${GRAALVM_HOME:-}" ] && [ -x "$root/client/build/native/prism" ]; then
	# Beside the application's own program, which is where it looks for it.
	cp "$root/client/build/native/prism" "$client"
	linked="$client"
	# The application was sealed as it was built: sealed again, with what was added.
	codesign --force --deep --sign - "$app" 2>/dev/null || true
fi

if [ "$bin" != "none" ]; then
	if [ -e "$bin/prism" ] && [ ! -L "$bin/prism" ]; then
		echo "$bin/prism is there and is not a link of prism's, so it was left; set PRISM_BIN to link it elsewhere" >&2
	elif mkdir -p "$bin" 2>/dev/null && ln -sf "$linked" "$bin/prism" 2>/dev/null; then
		echo "linked $bin/prism"
	else
		echo "$bin is not yours to write: asking for your password to link $bin/prism"
		sudo mkdir -p "$bin"
		sudo ln -sf "$linked" "$bin/prism"
	fi
fi

"$program" agent install

cat <<SAID

Prism is installed in $applications, and its daemon starts as you log in.

Two things are yours to do, in System Settings:
  - Privacy & Security > Full Disk Access: turn Prism on, so that the index reads every folder without asking.
  - Keyboard > Keyboard Shortcuts... > Spotlight: untick "Show Spotlight search", so that Command-Space is prism's.
SAID
