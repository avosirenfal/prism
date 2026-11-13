"""A stand-in for an editor that is running already: a window (its app id `prism-test-editor`) which, given an
activation token in a file, asks with it to be brought forward, as a text editor's window does when a new start of the
editor hands it a file and the token that start was given. Each new token the file holds is asked with once.
Run as: editor.py TOKEN_FILE PROTOCOLS_DIR SECONDS. Prints `shown`, then `activating TOKEN` as it asks."""
import mmap, os, sys, time
sys.path.insert(0, os.path.dirname(sys.argv[2].rstrip("/")))
from pywayland.client import Display
from protocols.wayland import WlCompositor, WlShm
from protocols.xdg_shell import XdgWmBase
from protocols.xdg_activation_v1 import XdgActivationV1

path, seconds = sys.argv[1], float(sys.argv[3])
display = Display()
display.connect()
registry = display.get_registry()
found = {}

def global_(registry, name, interface, version):
    found.setdefault(interface, (name, version))

registry.dispatcher["global"] = global_
display.roundtrip()
compositor = registry.bind(found["wl_compositor"][0], WlCompositor, 4)
shm = registry.bind(found["wl_shm"][0], WlShm, 1)
wm = registry.bind(found["xdg_wm_base"][0], XdgWmBase, 1)
activation = registry.bind(found["xdg_activation_v1"][0], XdgActivationV1, 1)
wm.dispatcher["ping"] = lambda wm, serial: wm.pong(serial)

surface = compositor.create_surface()
xdg = wm.get_xdg_surface(surface)
toplevel = xdg.get_toplevel()
toplevel.set_app_id("prism-test-editor")
toplevel.set_title("editor")
configured = []

def configure(xdg_surface, serial):
    xdg_surface.ack_configure(serial)
    configured.append(serial)

xdg.dispatcher["configure"] = configure
surface.commit()
while not configured:
    display.dispatch(block=True)

# A window is shown once it has a buffer: an orange one.
W, H = 200, 150
fd = os.memfd_create("editor")
os.ftruncate(fd, W * H * 4)
pixels = mmap.mmap(fd, W * H * 4)
pixels.write(b"\x40\x80\xff\xff" * (W * H))
buffer = shm.create_pool(fd, W * H * 4).create_buffer(0, W, H, W * 4, 1)
surface.attach(buffer, 0, 0)
surface.damage(0, 0, W, H)
surface.commit()
display.roundtrip()
print("shown", flush=True)

asked = None
deadline = time.time() + seconds
while time.time() < deadline:
    token = open(path).read().strip() if os.path.exists(path) else ""
    if token and token != asked:
        asked = token
        activation.activate(token, surface)
        print("activating", token, flush=True)
    display.roundtrip()
    time.sleep(0.1)

# Left at once: pywayland's own cleanup, as the interpreter ends, can crash once all is done.
sys.stdout.flush()
os._exit(0)
