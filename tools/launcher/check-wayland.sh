#!/usr/bin/env bash
# The launcher as a surface over every window, tried on a real compositor: a daemon run in it, and the launcher shown,
# typed into, and put away, as a person would; and lines piped to `prism dmenu` chosen from in the same surface.
#
#   tools/launcher/check-wayland.sh          in a sway of its own, with no screen (sway's headless backend)
#   tools/launcher/check-wayland.sh --here   in the Wayland session it is run in: yours, on your screen, for a minute
#
# It needs grim, wtype, jq and foot, sway (but for --here), and prism's app image (./gradlew :app:createDistributable),
# or another prism named by $PRISM. It touches nothing of yours: every folder it uses is one it made, and the daemon it
# runs is its own, beside yours if you have one. Pictures of what it saw are left in $SHOTS, where that names a folder.
#
# What it can ask of the compositor it asks sway (swaymsg) and niri (niri msg) in their own words; of another, with
# --here, it checks what can be seen on the screen and leaves the rest.
#
# A sway with no screen draws on the CPU, and so prism draws its windows on the CPU there too; PRISM_DRAWING=gpu has
# it draw them with OpenGL all the same (Mesa's, on the CPU), which is the way a window is drawn on a machine with a
# GPU:
#
#   PRISM_DRAWING=gpu tools/launcher/check-wayland.sh
here="$(cd "$(dirname "$0")/../.." && pwd)"
PRISM="${PRISM:-$here/app/build/compose/binaries/main/app/prism/bin/prism}"
NIRI="${NIRI:-niri}"
session="${1:-own}"

for needed in grim wtype jq foot; do
	command -v "$needed" > /dev/null || { echo "this needs $needed"; exit 2; }
done
[ -x "$PRISM" ] || { echo "no prism at $PRISM: ./gradlew :app:createDistributable, or name one with PRISM="; exit 2; }

work="$(mktemp -d)"

if [ "$session" = --here ]; then
	[ -n "${WAYLAND_DISPLAY:-}" ] || { echo "--here is for a Wayland session: WAYLAND_DISPLAY is not set"; exit 2; }
	# The session's own socket, by its whole path, since the folder it is in is about to be another.
	case "$WAYLAND_DISPLAY" in
		/*) ;;
		*) export WAYLAND_DISPLAY="$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ;;
	esac
else
	command -v sway > /dev/null || { echo "this needs sway (or --here, in a Wayland session)"; exit 2; }
	unset WAYLAND_DISPLAY SWAYSOCK NIRI_SOCKET
fi

export HOME="$work/home"
export XDG_RUNTIME_DIR="$work/run" XDG_CACHE_HOME="$work/cache" XDG_STATE_HOME="$work/state"
export XDG_CONFIG_HOME="$work/config" XDG_DATA_HOME="$work/data"
mkdir -p "$HOME/Documents" "$XDG_RUNTIME_DIR" "$XDG_CACHE_HOME" "$XDG_STATE_HOME" "$XDG_CONFIG_HOME"
mkdir -p "$XDG_DATA_HOME/applications"
chmod 700 "$XDG_RUNTIME_DIR"
echo hello > "$HOME/Documents/zzfile.txt"
unset DISPLAY DBUS_SESSION_BUS_ADDRESS JAVA_TOOL_OPTIONS

# An application of the check's own: started, it leaves a file, which says that Enter started it.
cat > "$XDG_DATA_HOME/applications/zzmark.desktop" << EOF
[Desktop Entry]
Type=Application
Name=Zzmark
Exec=touch $work/started
EOF

failed=0
cleanup() {
	[ -n "${keyboard:-}" ] && kill "$keyboard" 2> /dev/null
	[ -n "${terminal:-}" ] && kill "$terminal" 2> /dev/null
	[ -n "${running:-}" ] && kill "$running" 2> /dev/null
	pkill -f "$XDG_RUNTIME_DIR" 2> /dev/null
	[ -f "$work/daemon.pid" ] && kill "$(cat "$work/daemon.pid")" 2> /dev/null
	[ -n "${sway:-}" ] && kill "$sway" 2> /dev/null
	[ "$failed" -eq 0 ] && rm -rf "$work" || echo "what it left is in $work"
}
trap cleanup EXIT

if [ "$session" != --here ]; then
	cat > "$work/sway.conf" << 'EOF'
output HEADLESS-1 resolution 1600x1000
default_border none
EOF
	WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman sway -c "$work/sway.conf" > "$work/sway.log" 2>&1 &
	sway=$!
	for _ in $(seq 50); do
		ls "$XDG_RUNTIME_DIR"/wayland-? > /dev/null 2>&1 && break
		sleep 0.2
	done
	export WAYLAND_DISPLAY="$(basename "$(ls "$XDG_RUNTIME_DIR"/wayland-? 2> /dev/null | head -1)")"
	export SWAYSOCK="$(ls "$XDG_RUNTIME_DIR"/sway-ipc* 2> /dev/null | head -1)"
	[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ] || { echo "sway did not start: $(tail -3 "$work/sway.log")"; exit 2; }
fi

# Which compositor is asked what it has, in its own words.
if [ -n "${NIRI_SOCKET:-}" ]; then
	compositor=niri
elif [ -n "${SWAYSOCK:-}" ]; then
	compositor=sway
else
	compositor=another
fi
echo "--- on $compositor ($session)"

# A keyboard, plugged in for as long as this runs: a wtype that waits. A sway with no screen has no keyboard but the
# one each wtype brings and takes away with it; and a seat that loses its last keyboard takes the keyboard from
# whatever had it, which no session with a real one does.
wtype -s 3600000 -k Escape &
keyboard=$!

check() {
	local what="$1" wanted="$2" got="$3"

	if [ "$got" = "$wanted" ]; then
		echo "ok      $what"
	else
		echo "FAILED  $what: wanted “$wanted”, got “$got”"
		failed=1
	fi
}

eventually() {
	local what="$1" seconds="$2"
	shift 2

	for _ in $(seq $((seconds * 5))); do
		"$@" > /dev/null 2>&1 && { echo "ok      $what"; return 0; }
		sleep 0.2
	done
	echo "FAILED  $what: not within $seconds seconds"
	failed=1
}

shot() { [ -n "${SHOTS:-}" ] && mkdir -p "$SHOTS" && grim "$SHOTS/$1.png"; }
windows() { "$PRISM" status 2> /dev/null | tail -1; }
have_windows() { [ "$(windows)" = "windows: $1" ]; }

# The windows the compositor has, by their titles: a layer surface is none of them.
titles() {
	case "$compositor" in
		sway) swaymsg -t get_tree | jq -r '.. | objects | select(.pid? != null and .name != null) | .name' ;;
		niri) "$NIRI" msg --json windows | jq -r '.[].title' ;;
	esac
}
has_title() { titles | grep -qx "$1"; }
has_no_title() { ! has_title "$1"; }
# Whether the compositor has a window of the application [1].
has_app() {
	case "$compositor" in
		sway) swaymsg -t get_tree | jq -e --arg app "$1" '[.. | objects | select(.app_id? == $app)] | length > 0' ;;
		niri) "$NIRI" msg --json windows | jq -e --arg app "$1" '[.[] | select(.app_id == $app)] | length > 0' ;;
	esac
}
# The application whose window is the one in front: the one the keyboard is in, or goes back to after the launcher.
focused() {
	case "$compositor" in
		sway) swaymsg -t get_tree | jq -r '.. | objects | select(.focused? == true) | .app_id' ;;
		niri) "$NIRI" msg --json focused-window | jq -r '.app_id' ;;
	esac
}
focused_is() { [ "$(focused)" = "$1" ]; }
# The surfaces in the compositor's layers, by the names they gave: only niri says.
layers() { "$NIRI" msg --json layers | jq -r '.[] | "\(.namespace) \(.layer) \(.keyboard_interactivity)"'; }
fullscreen() {
	case "$compositor" in
		sway) swaymsg fullscreen enable ;;
		niri) "$NIRI" msg action fullscreen-window ;;
	esac
}

# How large the screen is, and the colour of one pixel of it: what is drawn there.
read -r wide high <<< "$(grim -t ppm - | head -c 40 | sed -n 2p)"
middle="$((wide / 2)) $((high / 2))"
pixel() { grim -g "$1,$2 1x1" -t ppm - | tail -c 3 | od -An -tu1 | tr -s ' ' | sed 's/^ //'; }
# Whether the middle of the screen is another colour than [1]: something is drawn there over what was.
drawn_over() { [ "$(pixel $middle)" != "$1" ]; }
# Where, going along the row (x) or the column (y) through the middle of the screen from its edge, the colour is first
# other than [2], what is behind: the launcher's left edge, or its top. Found by halving, since what is behind is one
# colour up to the launcher and the launcher's line from there.
edge() {
	local axis="$1" behind="$2" before=0 from at

	[ "$axis" = x ] && from=$((wide / 2)) || from=$((high / 2))
	while [ $((from - before)) -gt 1 ]; do
		at=$(((before + from) / 2))
		if [ "$axis" = x ]; then
			[ "$(pixel "$at" $((high / 2)))" = "$behind" ] && before=$at || from=$at
		else
			[ "$(pixel $((wide / 2)) "$at")" = "$behind" ] && before=$at || from=$at
		fi
	done
	echo "$from"
}
# The launcher's corners, looked at where it is on the screen over [1], what is behind it: round, the very corner is
# what is behind and a little way in is the launcher's own; square, the very corner is the line around it.
corners() {
	local behind="$1" wanted="$2" left top corner line inside own

	left="$(edge x "$behind")"
	top="$(edge y "$behind")"
	corner="$(pixel "$left" "$top")"
	line="$(pixel "$left" $((high / 2)))"
	inside="$(pixel $((left + 6)) $((top + 6)))"
	own="$(pixel $((left + 60)) $((top + 6)))"
	check "the line around it is not what is behind it" "true" "$([ "$line" != "$behind" ] && echo true || echo false)"
	if [ "$wanted" = round ]; then
		check "its very corner is what is behind it" "$behind" "$corner"
		check "a little way in from the corner is the launcher's own" "$own" "$inside"
		check "which is not what is behind it" "true" "$([ "$inside" != "$behind" ] && echo true || echo false)"
	else
		check "its very corner is the line around it" "$line" "$corner"
	fi
}

# The daemon, started apart from this script: which process it is, kept for stopping it.
started() {
	PRISM_WINDOW_LOG=1 "$PRISM" daemon "$HOME" > "$work/daemon.out" 2>&1
	status=$?
	grep -o 'process [0-9]*' "$work/daemon.out" | head -1 | cut -d' ' -f2 > "$work/daemon.pid"
	return "$status"
}

# The preferences made [1] (nothing given: none at all), for a daemon started anew: prism reads them as it starts,
# and what it is told in its settings window after that, never the file again while it runs.
prefer() {
	daemon="$(cat "$work/daemon.pid")"
	kill "$daemon"
	eventually "the daemon ends, for its preferences to be set" 15 sh -c "! kill -0 $daemon 2> /dev/null"
	mkdir -p "$XDG_CONFIG_HOME/prism"
	if [ -n "${1:-}" ]; then
		echo "$1" > "$XDG_CONFIG_HOME/prism/preferences.json"
	else
		rm -f "$XDG_CONFIG_HOME/prism/preferences.json"
	fi
	started
	eventually "and answers again, with them" 20 "$PRISM" status
}

cd "$HOME" || exit 2
echo "--- a daemon, in a Wayland session"
check "the daemon starts" "0" "$(started; echo $?)"
eventually "it answers" 20 "$PRISM" status

echo "--- the launcher is a surface over the windows, not one of them"
before="$(pixel $middle)"
check "the launcher is asked for" "0" "$("$PRISM" launcher > /dev/null 2>&1; echo $?)"
eventually "the daemon has it open" 10 have_windows "1 open"
eventually "it says it is in the compositor's overlay layer" 10 \
	grep -q "an overlay, in the compositor.s overlay layer" "$XDG_STATE_HOME/prism/daemon.log"
# Its first frame takes a moment, drawn by OpenGL on a CPU: waited for, rather than looked at too soon.
eventually "something is drawn in the middle of the screen" 10 drawn_over "$before"
shot launcher-shown
if [ "$compositor" != another ]; then
	check "it is no window of the compositor's" "" "$(titles | grep -x 'Prism Launcher')"
fi
# It asks for the keyboard as a window has it, for a click elsewhere to take: niri gives it the keyboard for that; sway
# gives it nothing until it asks for the keyboard as its own, and leaves it the keyboard when it then shares it.
if [ "$compositor" = niri ]; then
	eventually "the compositor has it in its overlay layer, the keyboard its own for the asking" 10 \
		sh -c "'$NIRI' msg --json layers | jq -e '.[] | select(.namespace == \"prism-launcher\") | .layer == \"Overlay\" and .keyboard_interactivity == \"OnDemand\"'"
fi
if [ "$compositor" = sway ]; then
	eventually "not given the keyboard for the asking, it holds it, and shares it once it has it" 10 \
		grep -q "shares the keyboard, now that it has it" "$XDG_STATE_HOME/prism/daemon.log"
fi
# In the middle: what is far to its left is what is as far to its right, which is not the launcher.
side="$(pixel $((wide / 2 - 500)) $((high / 2)))"
check "it is in the middle, side to side" "$side" "$(pixel $((wide / 2 + 500)) $((high / 2)))"

echo "--- its corners are round, and show what is behind it"
eventually "its window is clear behind what it draws" 10 \
	grep -q "asked to be clear, and is clear" "$XDG_STATE_HOME/prism/daemon.log"
# Drawn as it was asked to be: by OpenGL where PRISM_DRAWING=gpu says so, and otherwise as the session has it.
if [ "${PRISM_DRAWING:-}" = gpu ]; then
	check "it is drawn by OpenGL" "1" "$(grep -c "window [0-9]*: Gpu" "$XDG_STATE_HOME/prism/daemon.log")"
fi
echo "    drawn by $(grep -o "window [0-9]*: [A-Za-z]*Pixels\|window [0-9]*: Gpu\|window [0-9]*: Cpu" "$XDG_STATE_HOME/prism/daemon.log" | tail -1 | cut -d' ' -f3)"
corners "$side" round

echo "--- it has the keyboard: what is typed arrives, and Enter starts what was found"
wtype zzmark
sleep 1.5
shot launcher-typed
wtype -k Return
eventually "Enter started the application typed for" 10 test -e "$work/started"
eventually "and the launcher was put away" 10 have_windows "none open"

echo "--- the key that shows it puts it away, and so does Escape"
"$PRISM" launcher
eventually "shown again" 10 have_windows "1 open"
"$PRISM" launcher
eventually "asked for again, put away" 10 have_windows "none open"
"$PRISM" launcher
eventually "shown a third time" 10 have_windows "1 open"
sleep 1
wtype something
sleep 0.5
wtype -k Escape
sleep 0.5
check "Escape lets go of what was typed first" "windows: 1 open" "$(windows)"
wtype -k Escape
eventually "and then puts it away" 10 have_windows "none open"

echo "--- lines piped to prism dmenu are shown in its place, and the one chosen is printed"
# As a script uses it: the lines on its standard input, and what is chosen for the next command to read.
(printf '25%%\n50%%\n75%%\n100%%\n' | "$PRISM" dmenu --placeholder "Window size..." > "$work/chosen"
	echo $? > "$work/chosen.status") &
eventually "it is shown" 10 have_windows "1 open"
sleep 1.5
shot dmenu
wtype 7
sleep 1
shot dmenu-typed
wtype -k Return
eventually "Enter puts it away" 10 have_windows "none open"
eventually "and the command ends" 10 test -s "$work/chosen.status"
check "having printed the line that what was typed left" "75%" "$(cat "$work/chosen")"
check "with status 0" "0" "$(cat "$work/chosen.status")"
(printf 'a\nb\n' | "$PRISM" dmenu > "$work/escaped"
	echo $? > "$work/escaped.status") &
eventually "shown again" 10 have_windows "1 open"
sleep 1.5
wtype -k Escape
eventually "Escape puts it away" 10 have_windows "none open"
eventually "and the command ends" 10 test -s "$work/escaped.status"
check "having printed nothing" "" "$(cat "$work/escaped")"
check "with status 1, as dmenu's is" "1" "$(cat "$work/escaped.status")"
(printf 'a\nb\n' | "$PRISM" dmenu > "$work/toggled"
	echo $? > "$work/toggled.status") &
eventually "shown a third time" 10 have_windows "1 open"
sleep 1
"$PRISM" launcher
eventually "the launcher's key puts it away, and shows no launcher" 10 have_windows "none open"
eventually "and the command ends" 10 test -s "$work/toggled.status"
check "as one put away" "1" "$(cat "$work/toggled.status")"

echo "--- told to have square corners, it has them"
prefer '{"launcher": {"rounded": false}}'
behind="$(pixel $middle)"
"$PRISM" launcher
eventually "it is shown" 10 have_windows "1 open"
eventually "and drawn" 10 drawn_over "$behind"
sleep 0.5
shot launcher-square
corners "$(pixel $((wide / 2 - 500)) $((high / 2)))" square
wtype -k Escape
sleep 0.5
wtype -k Escape
eventually "put away" 10 have_windows "none open"
prefer

if [ "$compositor" != another ]; then
	echo "--- a window that fills the screen does not cover it"
	foot > /dev/null 2>&1 &
	terminal=$!
	eventually "a terminal opens" 10 has_app foot
	fullscreen > /dev/null
	sleep 1
	full="$(pixel $middle)"
	"$PRISM" launcher
	eventually "the launcher is shown over it" 10 have_windows "1 open"
	eventually "and drawn over it" 10 drawn_over "$full"
	shot launcher-over-fullscreen
	# Its corners show the terminal, which is what is behind it now.
	corners "$(pixel $((wide / 2 - 500)) $((high / 2)))" round
	# A moment, as a person takes: on sway the keyboard is the launcher's a moment after it is on the screen.
	sleep 1
	if [ "$compositor" = sway ]; then
		check "the terminal still fills the screen" "true" \
			"$(swaymsg -t get_tree | jq -r '[.. | objects | select(.app_id? == "foot") | .fullscreen_mode == 1] | any')"
	fi
	wtype -k Escape
	eventually "put away again" 10 have_windows "none open"
	kill "$terminal"
	terminal=""
	sleep 0.5

	echo "--- the keyboard gone to another window, it is put away; told to stay, it keeps the keyboard"
	"$PRISM" launcher
	eventually "the launcher is shown" 10 have_windows "1 open"
	sleep 1.5
	foot > /dev/null 2>&1 &
	terminal=$!
	eventually "a window opened beside it takes the keyboard, and it is put away" 10 have_windows "none open"
	kill "$terminal"
	terminal=""
	prefer '{"launcher": {"closeUnfocused": false}}'
	"$PRISM" launcher
	eventually "shown, to stay" 10 have_windows "1 open"
	if [ "$compositor" = niri ]; then
		eventually "the keyboard is its own" 10 sh -c "'$NIRI' msg --json layers | jq -e '.[] | select(.namespace == \"prism-launcher\") | .keyboard_interactivity == \"Exclusive\"'"
	fi
	sleep 1
	foot > /dev/null 2>&1 &
	terminal=$!
	eventually "another window opens beside it" 10 has_app foot
	sleep 1
	check "which does not put it away" "windows: 1 open" "$(windows)"
	wtype -k Escape
	eventually "and Escape, which is still its own, does" 10 have_windows "none open"
	kill "$terminal"
	terminal=""
	prefer

	echo "--- an application that is running is brought forward from its result's menu"
	# An application of the check's own, running: a terminal whose window says it is that application's (its app id is
	# the desktop entry's name). Started from the launcher, it would leave a file; brought forward, it leaves none.
	cat > "$XDG_DATA_HOME/applications/zzterm.desktop" << EOF
[Desktop Entry]
Type=Application
Name=Zzterm
Exec=touch $work/again
EOF
	foot --app-id zzterm > /dev/null 2>&1 &
	running=$!
	eventually "the application's window opens" 10 has_app zzterm
	foot > /dev/null 2>&1 &
	terminal=$!
	eventually "and another application's after it" 10 has_app foot
	eventually "which is the one in front" 10 focused_is foot
	"$PRISM" launcher
	eventually "the launcher is shown" 10 have_windows "1 open"
	sleep 1.5
	wtype zzterm
	sleep 1.5
	# The right arrow shows what can be done to it: Open, then Focus window, which Down and Enter choose.
	wtype -k Right
	sleep 0.5
	shot launcher-actions
	wtype -k Down
	sleep 0.3
	wtype -k Return
	eventually "the launcher is put away" 10 have_windows "none open"
	eventually "and the application's window is the one in front" 10 focused_is zzterm
	check "it was brought forward, and not started again" "" "$(ls "$work/again" 2> /dev/null)"
	kill "$running" "$terminal"
	running=""
	terminal=""
	rm "$XDG_DATA_HOME/applications/zzterm.desktop"
	sleep 0.5

	echo "--- an explorer's window is a window still"
	"$PRISM" "$HOME/Documents"
	eventually "it opens" 10 have_windows "1 open"
	eventually "as a window of the compositor's" 10 has_title "Documents"
	shot explorer

	echo "--- told to be a window, the launcher is one"
	prefer '{"launcher": {"overlay": false}}'
	"$PRISM" launcher
	eventually "it opens as a window of the compositor's" 10 has_title "Prism Launcher"
	"$PRISM" launcher
	eventually "and is put away by being asked for" 10 has_no_title "Prism Launcher"
	prefer
fi

[ "$failed" -eq 0 ] && echo && echo PASSED
exit "$failed"
