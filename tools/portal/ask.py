# An application asking the portal for a file, as GTK does: OpenFile on the portal, then the Response signal on the
# request object, which the portal sends to this connection alone. Prints the response and the URIs; exits on it.
import sys
from gi.repository import Gio, GLib

folder, title = sys.argv[1], sys.argv[2]
parent = sys.argv[3] if len(sys.argv) > 3 else ""
bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
sender = bus.get_unique_name()[1:].replace(".", "_")
token = "fruit"
path = f"/org/freedesktop/portal/desktop/request/{sender}/{token}"
loop = GLib.MainLoop()

def answered(connection, sender_name, object_path, interface, signal, parameters):
    response, results = parameters.unpack()
    print("response", response)
    print("uris", results.get("uris"))
    print("filter", results.get("current_filter"))
    loop.quit()

bus.signal_subscribe(None, "org.freedesktop.portal.Request", "Response", path, None, Gio.DBusSignalFlags.NO_MATCH_RULE, answered)
bus.call_sync(
    "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "AddMatch",
    GLib.Variant("(s)", (f"type='signal',interface='org.freedesktop.portal.Request',path='{path}'",)),
    None, Gio.DBusCallFlags.NONE, -1, None)
options = {
    "handle_token": GLib.Variant("s", token),
    "current_folder": GLib.Variant("ay", folder.encode() + b"\0"),
    "accept_label": GLib.Variant("s", "_Take it"),
    "filters": GLib.Variant("a(sa(us))", [("Text", [(0, "*.txt")]), ("Everything", [(0, "*")])]),
}
handle = bus.call_sync(
    "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop", "org.freedesktop.portal.FileChooser", "OpenFile",
    GLib.Variant("(ssa{sv})", (parent, title, options)), None, Gio.DBusCallFlags.NONE, -1, None)
print("handle", handle.unpack()[0], flush=True)
GLib.timeout_add_seconds(60, loop.quit)
loop.run()
