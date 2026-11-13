"""A virtual pointer for a wlroots compositor, driven from stdin: `abs X Y`, `press`/`release` (left), `rpress`/`rrelease`
(right), `sleep S`, and `shoot FILE`, the screen with its cursor, as grim takes it. Run as: pointer.py WIDTH HEIGHT
PROTOCOLS_DIR."""
import os, subprocess, sys, time
sys.path.insert(0, __import__("os").path.dirname(sys.argv[3].rstrip("/")))
from pywayland.client import Display
from protocols.wayland import WlSeat
from protocols.wlr_virtual_pointer_unstable_v1 import ZwlrVirtualPointerManagerV1

W, H = int(sys.argv[1]), int(sys.argv[2])  # the output's size; argv[3] the protocols generated
display = Display()
display.connect()
registry = display.get_registry()
found = {}

def global_(registry, name, interface, version):
    found[interface] = (name, version)

registry.dispatcher["global"] = global_
display.roundtrip()
seat = registry.bind(found["wl_seat"][0], WlSeat, 1)
manager = registry.bind(found["zwlr_virtual_pointer_manager_v1"][0], ZwlrVirtualPointerManagerV1, 1)
pointer = manager.create_virtual_pointer(seat)
display.roundtrip()

def now():
    return int(time.monotonic() * 1000) & 0xFFFFFFFF

for line in sys.stdin:
    words = line.split()
    if not words:
        continue
    if words[0] == "abs":
        pointer.motion_absolute(now(), int(words[1]), int(words[2]), W, H)
        pointer.frame()
    elif words[0] in ("press", "release", "rpress", "rrelease"):
        pointer.button(now(), 0x111 if words[0] in ("rpress", "rrelease") else 0x110, 1 if words[0] in ("press", "rpress") else 0)
        pointer.frame()
    elif words[0] == "sleep":
        display.flush()
        time.sleep(float(words[1]))
    elif words[0] == "shoot":
        subprocess.run(["grim", "-c", words[1]], check=False)
    display.flush()
    display.roundtrip()
    print("done", line.strip(), flush=True)

# Left at once: pywayland's own cleanup, as the interpreter ends, can crash once all is done.
sys.stdout.flush()
os._exit(0)
