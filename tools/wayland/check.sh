#!/bin/bash
# Drags on Wayland, end to end (docs/window.md): prism's app image, native Wayland windows on a headless sway, and a
# pointer of wlroots' virtual-pointer protocol pressing, moving and letting go as a hand does. Three drags:
#
#   1. a file from one prism to another (two processes): given as a URI list, and moved there, its picture under the
#      pointer as it goes (during-drag.png);
#   2. a file from one window to another of one prism: carried as itself, and moved there;
#   3. an archive's entry to its folder's window: carried as itself, and brought out there.
#
# And a file opened in an editor that is running already (editor.py stands for it): the token prism gives the
# editor's new start, handed to the editor's window, brings it forward; a made-up one does not.
#
# Needs sway, grim, python3 with venv, libwayland-dev (for wayland.xml), and network to fetch pywayland and the
# protocols once. Run after `./gradlew :app:createDistributable`. Prints PASSED or FAILED.
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
protocols=https://gitlab.freedesktop.org/wayland/wayland-protocols/-/raw/main
curl -sSf -o "$work/xdg-shell.xml" "$protocols/stable/xdg-shell/xdg-shell.xml" || exit 1
curl -sSf -o "$work/xdg-activation-v1.xml" "$protocols/staging/xdg-activation/xdg-activation-v1.xml" || exit 1
"$work/venv/bin/python" -m pywayland.scanner -i /usr/share/wayland/wayland.xml "$work/virtual-pointer.xml" \
  "$work/xdg-shell.xml" "$work/xdg-activation-v1.xml" -o "$work/protocols" 2>/dev/null || exit 1

# The editor floats in a corner, clear of prism's rows; a window asking with a good token is given the keyboard.
cat > "$work/sway.conf" <<'CONF'
output HEADLESS-1 resolution 1280x800
default_border none
for_window [app_id="prism-test-editor"] floating enable, resize set 200 150, move position 1060 630
focus_on_window_activation focus
CONF
WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman sway -c "$work/sway.conf" > "$work/sway.log" 2>&1 &
sway=$!
sleep 2
export WAYLAND_DISPLAY=$(cd "$XDG_RUNTIME_DIR" && ls wayland-* | grep -v lock | head -1)

open() { (env -u DISPLAY PRISM_WINDOW_LOG=1 timeout 90 "$prism" explore "$1" >> "$work/prism.log" 2>&1 &); sleep 6; }
pointer() { printf '%s\n' "sleep 1" "$@" | timeout 30 "$work/venv/bin/python" "$here/pointer.py" 1280 800 "$work/protocols" >> "$work/pointer.log" 2>&1; }
# A drag from (x, y) to (x', y'), in steps, let go after a moment over its target; the screen shot there first, to the
# file a fifth argument names.
drag() { pointer "abs $1 $2" "sleep 0.3" "press" "sleep 0.2" "abs $(($1 + 10)) $(($2 + 7))" "abs $(($1 + 40)) $(($2 + 27))" \
  "abs $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))" "abs $3 $4" "sleep 0.5" "abs $(($3 + 5)) $(($4 + 2))" "sleep 0.5" \
  "${5:+shoot $5}" "release" "sleep 3"; }
done_with() { pkill -f "binaries/main/app/prism"; sleep 1; }
check() { if eval "$2"; then echo "ok: $1"; else echo "FAILED: $1"; failed=1; fi; }
# The app id of the window that has the keyboard, as sway says.
focused() {
  SWAYSOCK=$(ls "$XDG_RUNTIME_DIR"/sway-ipc.*.sock | head -1) swaymsg -t get_tree | python3 -c '
import json, sys
def focused(node):
    if node.get("focused"):
        return node.get("app_id") or node.get("name") or "?"
    for child in node.get("nodes", []) + node.get("floating_nodes", []):
        found = focused(child)
        if found:
            return found
print(focused(json.load(sys.stdin)))'
}

# 1. Two processes, side by side: a.txt from the left one's listing to the right one's.
mkdir -p "$work/one/src" "$work/one/dst" && echo hello > "$work/one/src/a.txt"
open "$work/one/src"; open "$work/one/dst"
drag 300 103 900 400 "$work/during-drag.png"
grim "$work/across-processes.png"
check "a file dragged to another prism is moved there" "[ -f '$work/one/dst/a.txt' ] && [ ! -e '$work/one/src/a.txt' ]"
done_with

# 2. One process: the folder "inner" opened in a new window from its menu, and a.txt dragged into it.
mkdir -p "$work/two/src/inner" && echo hello > "$work/two/src/a.txt"
open "$work/two/src"
pointer "abs 300 103" "sleep 0.3" "rpress" "rrelease" "sleep 1" "abs 360 155" "sleep 0.3" "press" "release" "sleep 3"
drag 290 157 900 400
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

# 4. A file opened in an editor that is running already, which a start of the editor hands the file and its token.
four="$work/four"
mkdir -p "$four/files" "$four/data/applications" "$four/config" "$four/bin" && echo hello > "$four/files/note.txt"
cat > "$four/bin/hand-over" <<HAND
#!/bin/sh
printf '%s' "\$XDG_ACTIVATION_TOKEN" > "$four/token.part" && mv "$four/token.part" "$four/token"
HAND
chmod +x "$four/bin/hand-over"
printf '[Desktop Entry]\nType=Application\nName=Test editor\nExec=%s %%f\nMimeType=text/plain;\n' "$four/bin/hand-over" \
  > "$four/data/applications/prism-test-editor.desktop"
printf '[Default Applications]\ntext/plain=prism-test-editor.desktop\n' > "$four/config/mimeapps.list"
(timeout 60 "$work/venv/bin/python" "$here/editor.py" "$four/token" "$work/protocols" 50 >> "$work/editor.log" 2>&1 &)
sleep 2
export XDG_DATA_HOME="$four/data" XDG_CONFIG_HOME="$four/config"
open "$four/files"
check "prism, opened last, has the keyboard" "[ \"\$(focused)\" != prism-test-editor ]"
echo not-a-token > "$four/token"; sleep 1.5
check "a made-up token brings the editor nothing" "[ \"\$(focused)\" != prism-test-editor ]"
rm -f "$four/token"
pointer "abs 300 103" "sleep 0.3" "press" "release" "sleep 0.05" "press" "release" "sleep 4"
check "the editor's start was given a token" "[ -s '$four/token' ] && [ \"\$(cat '$four/token')\" != not-a-token ]"
check "the editor's window is brought forward with it" "[ \"\$(focused)\" = prism-test-editor ]"
done_with

kill $sway 2>/dev/null
echo "== the drags, as prism said them"
grep "prism.window: \(drag\|window [0-9]*: drop\|activation\|wayland\)" "$work/prism.log"
echo "== the editor"
cat "$work/editor.log"
echo "== screenshots in $work"
[ $failed = 0 ] && echo PASSED || echo FAILED
