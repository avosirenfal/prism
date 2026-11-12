#!/bin/bash
# prism as the desktop's file chooser and file manager, end to end, with the real pieces (docs/portal.md): installed
# into a home of its own; an application asking the real xdg-desktop-portal for a file; D-Bus starting prism from the
# service file install wrote; prism's picker showing, a file in it double-clicked; the application answered. Then the
# file manager asked to show a file, and everything installed taken away again.
#
#     ./gradlew :app:createDistributable
#     tools/portal/check.sh
#
# Needs Xvfb, dbus-daemon, xdg-desktop-portal, xdotool, xprop and xwininfo, ImageMagick's import, and Python with
# GLib's bindings (gi).
# Screenshots of the picker and the explorer are left in the folder it says.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PRISM=${PRISM:-$HERE/../../app/build/compose/binaries/main/app/prism/bin/prism}
PYTHON=${PYTHON:-python3}
PORTAL=${PORTAL:-/usr/libexec/xdg-desktop-portal}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/prism-portal-check.XXXXXX")
set -- "$OUT"
for tool in Xvfb dbus-daemon xdotool xprop xwininfo import "$PORTAL" "$PRISM"; do
	command -v "$tool" >/dev/null || [ -x "$tool" ] || { echo "missing: $tool"; exit 2; }
done
W=$(mktemp -d "$1/home.XXXXXX")
mkdir -p "$W/home/Docs" "$W/config" "$W/data" "$W/run" "$W/etc"
chmod 700 "$W/run"
echo apples > "$W/home/Docs/apple.txt"
echo bananas > "$W/home/Docs/banana.txt"

export HOME="$W/home" XDG_CONFIG_HOME="$W/config" XDG_DATA_HOME="$W/data" XDG_RUNTIME_DIR="$W/run"
export XDG_CONFIG_DIRS="$W/etc" XDG_CURRENT_DESKTOP=test DISPLAY=:93
# The portal before 1.20 reads its backends from one folder: the one install wrote prism's into.
export XDG_DESKTOP_PORTAL_DIR="$W/data/xdg-desktop-portal/portals"

Xvfb :93 -screen 0 1400x900x24 -nolisten tcp >/dev/null 2>&1 &
XVFB=$!
sleep 1

echo "== install"
"$PRISM" portal install 2>&1
echo "== the portals.conf"; cat "$W/config/xdg-desktop-portal/portals.conf"
# Told where its backends are, a portal before 1.20 reads its configuration from there too.
cp "$W/config/xdg-desktop-portal/portals.conf" "$XDG_DESKTOP_PORTAL_DIR/"

# A bus whose D-Bus services include the user's own folder, where install wrote prism's service files.
dbus-daemon --session --fork --print-address=1 --print-pid=1 --address="unix:path=$W/run/bus" > "$W/bus" 2>"$W/bus.log"
export DBUS_SESSION_BUS_ADDRESS=$(head -1 "$W/bus")
BUS=$(sed -n 2p "$W/bus")

"$PORTAL" -v -r > "$W/portal.log" 2>&1 &
PORTAL_PID=$!
sleep 2

echo "== an application asks the portal for a file"
# Asked from a window: the screen's root stands for the application's, which the picker is to be a dialog of.
PARENT=$(printf "x11:%x" "$(xwininfo -root | sed -n 's/.*Window id: \(0x[0-9a-f]*\).*/\1/p')")
(cd /tmp && "$PYTHON" -I "$HERE/ask.py" "$W/home/Docs" "Pick a fruit" "$PARENT" > "$W/asked.log" 2>&1) &
ASKER=$!

echo "== the picker prism shows"
for i in $(seq 1 60); do
	WIN=$(xdotool search --name "^Pick a fruit$" 2>/dev/null | head -1)
	[ -n "$WIN" ] && break
	sleep 1
done
echo "window: ${WIN:-none} after ${i}s"
pgrep -af "portal serve" | grep -v pgrep | cut -c1-160 | head -2
if [ -n "$WIN" ]; then
	sleep 3
	timeout 3 import -window "$WIN" "$1/e2e-picker.png" 2>/dev/null
	TRANSIENT=$(xprop -id "$WIN" WM_TRANSIENT_FOR 2>&1)
	echo "a dialog of $PARENT: $TRANSIENT"
	[ "$(printf "x11:%x" "$(echo "$TRANSIENT" | sed -n 's/.*# \(0x[0-9a-f]*\).*/\1/p')")" = "$PARENT" ] && DIALOG=1
	# banana.txt, double-clicked: chosen and accepted. (No window manager here gives a window the keyboard.)
	xdotool mousemove --window "$WIN" 300 133 click --repeat 2 --delay 80 1
	sleep 2
	timeout 3 import -window "$WIN" "$1/e2e-after.png" 2>/dev/null
	echo "picker still there: $(xdotool search --name '^Pick a fruit$' 2>/dev/null | head -1)"
fi
sleep 3
echo "== what the application was answered"
wait $ASKER; cat "$W/asked.log"

echo "== the file manager, asked to show a file"
gdbus call --session --dest org.freedesktop.FileManager1 --object-path /org/freedesktop/FileManager1 \
	--method org.freedesktop.FileManager1.ShowItems "['file://$W/home/Docs/apple.txt']" "" 2>&1
for i in $(seq 1 20); do
	SHOWN=$(xdotool search --name "^Docs$" 2>/dev/null | head -1)
	[ -n "$SHOWN" ] && break
	sleep 1
done
echo "explorer window: ${SHOWN:-none}"
[ -n "$SHOWN" ] && sleep 3 && timeout 3 import -window "$SHOWN" "$1/e2e-explorer.png" 2>/dev/null

echo "== prism said"; grep -v "JAVA_TOOL\|activat" "$W/bus.log" | tail -20
echo "== uninstall"
"$PRISM" portal uninstall 2>&1
ls -R "$W/data" | grep -v "^$" | head; ls "$W/config/xdg-desktop-portal" 2>&1

echo "== portal log"; grep -v "^XDP: Looking\|^XDP: load\|^XDP: loading\|^XDP: portal impl\|^XDP: providing\|^XDP: skipping" "$W/portal.log" | tail -25
kill $PORTAL_PID $BUS $XVFB 2>/dev/null
pkill -f "$W" 2>/dev/null
echo "== screenshots in $OUT"
grep -q "^response 0" "$W/asked.log" && [ -n "${SHOWN:-}" ] && [ -n "${DIALOG:-}" ] && echo "PASSED" || { echo "FAILED"; exit 1; }
