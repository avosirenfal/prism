#!/bin/bash
# Drags on Wayland, end to end (docs/window.md): prism's app image, native Wayland windows on a headless sway, and a
# pointer of wlroots' virtual-pointer protocol pressing, moving and letting go as a hand does. Three drags:
#
#   1. a file from one prism to another (two processes): given as a URI list, and moved there;
#   2. a file from one window to another of one prism: carried as itself, and moved there;
#   3. an archive's entry to its folder's window: carried as itself, and brought out there.
#
# Needs sway, grim, python3 with venv, libwayland-dev (for wayland.xml), and network to fetch pywayland and the
# virtual pointer protocol once. Run after `./gradlew :app:createDistributable`. Prints PASSED or FAILED.
set -u
here=$(cd "$(dirname "$0")" && pwd)
prism="$here/../../app/build/compose/binaries/main/app/prism/bin/prism"
work=$(mktemp -d /tmp/prism-wayland-check.XXXXXX)
export XDG_RUNTIME_DIR="$work/runtime"
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"
failed=0

# The pointer's client: pywayland, and the protocols it speaks generated from their descriptions.
python3 -m venv "$work/venv" >/dev/null && "$work/venv/bin/pip" install --quiet pywayland || exit 1
curl -sSf -o "$work/virtual-pointer.xml" \
  https://raw.githubusercontent.com/swaywm/wlr-protocols/master/unstable/wlr-virtual-pointer-unstable-v1.xml || exit 1
"$work/venv/bin/python" -m pywayland.scanner -i /usr/share/wayland/wayland.xml "$work/virtual-pointer.xml" \
  -o "$work/protocols" 2>/dev/null || exit 1

printf 'output HEADLESS-1 resolution 1280x800\ndefault_border none\n' > "$work/sway.conf"
WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman sway -c "$work/sway.conf" > "$work/sway.log" 2>&1 &
sway=$!
sleep 2
export WAYLAND_DISPLAY=$(cd "$XDG_RUNTIME_DIR" && ls wayland-* | grep -v lock | head -1)

open() { (env -u DISPLAY PRISM_WINDOW_LOG=1 timeout 90 "$prism" explore "$1" >> "$work/prism.log" 2>&1 &); sleep 6; }
pointer() { printf '%s\n' "sleep 1" "$@" | timeout 30 "$work/venv/bin/python" "$here/pointer.py" 1280 800 "$work/protocols" >> "$work/pointer.log" 2>&1; }
# A drag from (x, y) to (x', y'), in steps, let go after a moment over its target.
drag() { pointer "abs $1 $2" "sleep 0.3" "press" "sleep 0.2" "abs $(($1 + 10)) $(($2 + 7))" "abs $(($1 + 40)) $(($2 + 27))" \
  "abs $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))" "abs $3 $4" "sleep 0.5" "abs $(($3 + 5)) $(($4 + 2))" "sleep 0.5" "release" "sleep 3"; }
done_with() { pkill -f "binaries/main/app/prism"; sleep 1; }
check() { if eval "$2"; then echo "ok: $1"; else echo "FAILED: $1"; failed=1; fi; }

# 1. Two processes, side by side: a.txt from the left one's listing to the right one's.
mkdir -p "$work/one/src" "$work/one/dst" && echo hello > "$work/one/src/a.txt"
open "$work/one/src"; open "$work/one/dst"
drag 300 103 900 400
grim "$work/across-processes.png"
check "a file dragged to another prism is moved there" "[ -f '$work/one/dst/a.txt' ] && [ ! -e '$work/one/src/a.txt' ]"
done_with

# 2. One process: the folder "inner" opened in a new window from its menu, and a.txt dragged into it.
mkdir -p "$work/two/src/inner" && echo hello > "$work/two/src/a.txt"
open "$work/two/src"
pointer "abs 300 103" "sleep 0.3" "rpress" "rrelease" "sleep 1" "abs 360 155" "sleep 0.3" "press" "release" "sleep 3"
drag 290 133 900 400
grim "$work/within-a-process.png"
check "a file dragged to another window of one prism is moved there" "[ -f '$work/two/src/inner/a.txt' ] && [ ! -e '$work/two/src/a.txt' ]"
done_with

# 3. One process: an archive opened with a double click, and its entry dragged to the folder's window.
mkdir -p "$work/three/code" && echo "int main;" > "$work/three/code/main.c"
(cd "$work/three" && tar -cf code.tar code && rm -rf code)
open "$work/three"
pointer "abs 300 103" "sleep 0.3" "press" "release" "sleep 0.05" "press" "release" "sleep 4"
drag 700 103 400 400
grim "$work/an-archives-entry.png"
check "an archive's entry dragged to its folder's window is brought out there" "[ -f '$work/three/code/main.c' ]"
done_with

kill $sway 2>/dev/null
echo "== the drags, as prism said them"
grep "prism.window: \(drag\|window [0-9]*: drop\)" "$work/prism.log"
echo "== screenshots in $work"
[ $failed = 0 ] && echo PASSED || echo FAILED
