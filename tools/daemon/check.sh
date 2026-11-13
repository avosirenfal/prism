#!/usr/bin/env bash
# prism as it runs for a session, end to end, on a virtual display (docs/running.md): a prism started alone, a second
# handed to it, a daemon started meanwhile waiting its turn and taking the session's windows when the first prism's
# last window closes; the daemon started apart from the terminal, started twice, asked for windows, and stopped with
# its index written and its socket taken away.
#
# It needs Xvfb and xdotool, and prism's app image (./gradlew :app:createDistributable), or another prism named by
# $PRISM (a Nix build's result/bin/prism). It touches nothing of yours: every folder it uses is one it made.
set -u

here="$(cd "$(dirname "$0")/../.." && pwd)"
PRISM="${PRISM:-$here/app/build/compose/binaries/main/app/prism/bin/prism}"

for needed in Xvfb xdotool; do
	command -v "$needed" > /dev/null || { echo "needs $needed"; exit 2; }
done
[ -x "$PRISM" ] || { echo "no prism at $PRISM: ./gradlew :app:createDistributable, or name one with PRISM="; exit 2; }

work="$(mktemp -d)"
export XDG_RUNTIME_DIR="$work/run" XDG_CACHE_HOME="$work/cache" XDG_STATE_HOME="$work/state" XDG_CONFIG_HOME="$work/config"
mkdir -p "$XDG_RUNTIME_DIR" "$XDG_CACHE_HOME" "$XDG_STATE_HOME" "$XDG_CONFIG_HOME" "$work/tree/docs"
chmod 700 "$XDG_RUNTIME_DIR"
echo hello > "$work/tree/docs/a.txt"
unset WAYLAND_DISPLAY DBUS_SESSION_BUS_ADDRESS JAVA_TOOL_OPTIONS

display=":$((RANDOM % 400 + 600))"
Xvfb "$display" -screen 0 1600x1000x24 > /dev/null 2>&1 &
xvfb=$!
export DISPLAY="$display"

failed=0
cleanup() {
	pkill -f "$XDG_RUNTIME_DIR" 2> /dev/null
	[ -n "${first:-}" ] && kill "$first" 2> /dev/null
	[ -f "$XDG_STATE_HOME/prism/daemon.pid" ] && kill "$(cat "$XDG_STATE_HOME/prism/daemon.pid")" 2> /dev/null
	kill "$xvfb" 2> /dev/null
	[ "$failed" -eq 0 ] && rm -rf "$work" || echo "what it left is in $work"
}
trap cleanup EXIT

# [what] is so where [got] is [wanted].
check() {
	local what="$1" wanted="$2" got="$3"

	if [ "$got" = "$wanted" ]; then
		echo "ok      $what"
	else
		echo "FAILED  $what: wanted “$wanted”, got “$got”"
		failed=1
	fi
}

# Waits, up to [2] seconds, for the command [3...] to succeed.
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

windows() { "$PRISM" status 2> /dev/null | tail -1; }
who() { "$PRISM" status 2> /dev/null | head -1 | cut -d' ' -f1-2; }
have_windows() { [ "$(windows)" = "windows: $1" ]; }

# The window opened last, closed as a person closes one: given the keyboard, and Ctrl+W. With no window manager
# nothing gives a window the keyboard as another closes, so it is given by name.
close_window() {
	local window=""

	# A window just asked for is on the screen a moment after it is counted.
	for _ in $(seq 25); do
		window="$(xdotool search --onlyvisible --class prism 2> /dev/null | tail -1)"
		[ -n "$window" ] && break
		sleep 0.2
	done
	sleep 0.5
	xdotool windowfocus "$window" 2> /dev/null
	sleep 0.3
	xdotool key --window "$window" ctrl+w 2> /dev/null
}

cd "$work" || exit 2

echo "--- a prism on its own is the session's until its last window closes"
check "nothing is running to start with" "No prism is running." "$("$PRISM" status)"
"$PRISM" tree > first.out 2> first.err &
first=$!
eventually "the first prism opens its window" 30 have_windows "1 open"
check "it is not the daemon" "prism explore" "$(who)"

"$PRISM" tree/docs
check "a second prism is answered and exits 0" "0" "$?"
check "its window opened in the first" "windows: 2 open" "$(windows)"
check "a path is read from where it was said, and one that is not there is refused" \
	"prism explore: nowhere does not exist" "$("$PRISM" nowhere 2>&1)"

"$PRISM" --isolated tree 2> /dev/null &
isolated=$!
sleep 4
check "a prism kept to itself opens its window in no other" "windows: 2 open" "$(windows)"
kill "$isolated" 2> /dev/null

echo "--- a daemon started meanwhile keeps its index and waits for the session's windows"
check "the daemon starts apart from the terminal" "0" "$("$PRISM" daemon tree > daemon.out 2>&1; echo $?)"
grep -o 'process [0-9]*' daemon.out | head -1 | cut -d' ' -f2 > "$XDG_STATE_HOME/prism/daemon.pid"
check "the first prism still has the windows" "prism explore" "$(who)"
check "a second daemon says the first is running" "A prism daemon is already running" \
	"$("$PRISM" daemon tree | cut -d, -f1)"
check "the index's file is the daemon's alone" "1" "$("$PRISM" index --fresh tree > /dev/null 2>&1; echo $?)"

close_window
eventually "one window closed, one left" 10 have_windows "1 open"
close_window
eventually "the first prism ends with its last window" 15 sh -c "! kill -0 $first"
eventually "the daemon has the session's windows from then" 15 sh -c "[ \"\$('$PRISM' status | head -1 | cut -d' ' -f1-2)\" = 'prism daemon' ]"
check "with none open" "windows: none open" "$(windows)"

echo "--- the daemon opens windows, and goes on with none"
"$PRISM" tree/docs/a.txt
eventually "a file is shown in its folder's window" 10 have_windows "1 open"
close_window
eventually "the window closes" 10 have_windows "none open"
check "the daemon is still there" "prism daemon" "$(who)"
check "its index holds the tree" "1" "$("$PRISM" status | grep -c 'index: 3 entries')"

echo "--- stopped, it writes its index and takes its socket away"
daemon="$(cat "$XDG_STATE_HOME/prism/daemon.pid")"
kill "$daemon"
eventually "the daemon ends" 15 sh -c "! kill -0 $daemon"
check "nobody is listening" "No prism is running." "$("$PRISM" status)"
check "the socket is gone" "0" "$(find "$XDG_RUNTIME_DIR/prism" -name prism.sock | wc -l)"
check "the index was written, and reads back" "1" \
	"$("$PRISM" index --file "$XDG_CACHE_HOME/prism/index.prism" | grep -c '3 entries')"
check "it said it stopped" "1" "$(grep -c '^stopped: 3 entries' "$XDG_STATE_HOME/prism/daemon.log")"

echo
[ "$failed" -eq 0 ] && echo PASSED || echo FAILED
exit "$failed"
