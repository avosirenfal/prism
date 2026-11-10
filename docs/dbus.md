# D-Bus

prism talks to the desktop over D-Bus: to the portal today (to follow the user's colour scheme), and as a portal
backend later (to be the file chooser applications ask for). It does so through a library of its own, the `dbus`
module, rather than one of the JVM's existing ones. This page explains D-Bus from the ground up, using one call as
the running example, and then how the library maps it onto Kotlin.

## The example

This is the call the picker makes to learn whether the user prefers a dark or a light desktop:

```bash
busctl --user call org.freedesktop.portal.Desktop /org/freedesktop/portal/desktop \
    org.freedesktop.portal.Settings ReadOne ss org.freedesktop.appearance color-scheme
```

and the answer:

```
v u 1
```

Everything below is about what those words mean, and what goes over the wire to get that answer.

## What D-Bus is

D-Bus is a message bus. Each user's session has one bus, run by a daemon (dbus-daemon, or dbus-broker), which every
program in the session connects to. Programs never talk to each other directly: they send messages to the bus, and the
bus delivers them.

Each connection has a **unique name** the bus gives it when it connects, like `:1.42`. A program that offers a service
can also ask the bus for a **well-known name**, like `org.freedesktop.portal.Desktop`, so that others can find it
without knowing its unique name. In the example, `org.freedesktop.portal.Desktop` is the portal's well-known name.

A program exposes **objects**, each at a **path** like `/org/freedesktop/portal/desktop`. An object implements
**interfaces**, like `org.freedesktop.portal.Settings`, and an interface has **members**: **methods**, which others
call and which reply (`ReadOne`), and **signals**, which the object broadcasts and which others may listen for
(`SettingChanged`).

So the example reads: on the connection named `org.freedesktop.portal.Desktop`, on its object at
`/org/freedesktop/portal/desktop`, call the method `ReadOne` of the interface `org.freedesktop.portal.Settings`.

## Types and signatures

Every value D-Bus carries has a type, written as a short code. A **signature** is a string of those codes:

| code | type | code | type |
|---|---|---|---|
| `y` | byte | `s` | string (UTF-8) |
| `b` | boolean | `o` | object path |
| `n`, `q` | 16-bit signed, unsigned | `g` | signature |
| `i`, `u` | 32-bit signed, unsigned | `aT` | array of T |
| `x`, `t` | 64-bit signed, unsigned | `(TU...)` | struct of T, U, ... |
| `d` | double | `a{KV}` | dictionary from K (a basic type) to V |
| `v` | variant: a value that carries its own signature | | |

In the example, `ss` is the signature of the arguments (two strings: the namespace and the key). The answer `v u 1`
is a variant (`v`) whose own signature is `u` and whose value is `1`. A variant is how a method that can return values
of different types (any setting at all) says which type this one is.

## What happens on the wire

**Connecting.** A program connects to the bus's Unix socket (for a session, normally `$XDG_RUNTIME_DIR/bus`). It sends
a single NUL byte, then a short text exchange: `AUTH EXTERNAL` followed by the user's id; the bus checks that id
against the socket's own credentials and answers `OK`; the program says `BEGIN`. From then on, everything is binary
messages. The first one must be a call of the bus's own `Hello` method, which returns the connection's unique name.

**A message** is a fixed header (the byte order, the message's kind, some flags, the protocol version, the body's
length, and a serial number the sender chooses), then a list of **header fields** (the path, the interface, the
member, the destination, the sender, and the body's signature), then the **body**: the values, one after another, as
the signature says.

**Values** are laid out simply, with one rule that matters: each value starts at a multiple of its own size, counted
from the start of the message, with zero bytes as padding. A 32-bit number starts on a multiple of 4; a struct, on a
multiple of 8. The reply in the example has a body of signature `v`, holding `u 1`, which is these eight bytes:

```
01 75 00     the variant's signature: length 1, "u", NUL
00           padding, so the number starts on a multiple of 4
01 00 00 00  the number 1, little-endian
```

**Replies** carry the serial of the call they answer, so a program can have many calls in flight and match each reply
to its call. An error reply carries an error name (`org.freedesktop.DBus.Error.UnknownMethod`) and usually a message.

**Signals** are broadcast, but the bus delivers one to a connection only if the connection asked for it, by calling
the bus's `AddMatch` with a rule such as
`type='signal',interface='org.freedesktop.portal.Settings',member='SettingChanged',arg0='org.freedesktop.appearance'`.
The `arg0` part asks only for signals whose first argument is that string, so the bus filters them before they are
sent.

## The library

The library is in two layers. The lower one is the wire format: values as bytes and back (`wire.kt`), knowing nothing
of Kotlin types. The upper one maps Kotlin types to D-Bus types with kotlinx.serialization (`serial.kt`). Between them
is an immutable tree of D-Bus values (`Value`), which is also what a variant of unknown type holds until it is decoded.

### Kotlin types are D-Bus types

Nobody writes a signature. The library works one out from a type's serial descriptor:

| Kotlin | D-Bus | Kotlin | D-Bus |
|---|---|---|---|
| `Boolean` | `b` | `String` | `s` |
| `Byte`, `UByte` | `y` | `ObjectPath` | `o` |
| `Short`, `UShort` | `n`, `q` | `Signature` | `g` |
| `Int`, `UInt` | `i`, `u` | `List<T>`, `Set<T>`, `ByteArray` | `aT` |
| `Long`, `ULong` | `x`, `t` | `Map<K, V>` | `a{KV}` |
| `Float`, `Double` | `d` | a class | a struct of its fields |
| `Variant<T>`, `AnyVariant` | `v` | an enum | `u` (ordinal or `@Code`), or `s` with `@AsString` |
| a value class | what it holds | a custom serializer | the type its descriptor says |

A message's body is a **class's fields**, one value each: `data class SettingKey(val namespace: String, val key:
String)` is a body of signature `ss`. `Unit` is an empty body, and any other type is a body of one value.

Anything D-Bus cannot hold is refused when the method is declared, with the path to the field and why:
`WithNull.maybe: D-Bus has no null; give it a value, or carry it in a variant`. That covers nullables, `Char`, sealed
and open classes, a class with no fields used as a value, and a dictionary keyed by a struct.

### Declaring and calling

A method is declared once, with its names and its argument and reply types:

```kotlin
@Serializable
data class SettingKey(val namespace: String, val key: String)

@Serializable
enum class ColorScheme { @Code(0) NoPreference, @Code(1) PreferDark, @Code(2) PreferLight }

val readColorScheme =
    method<SettingKey, Variant<ColorScheme>>(
        "org.freedesktop.portal.Desktop",
        "/org/freedesktop/portal/desktop",
        "org.freedesktop.portal.Settings",
        "ReadOne",
    )
```

and called on a connection, which has already done the handshake and `Hello` when `Connection.session()` returns it:

```kotlin
when (val opening = Connection.session()) {
    is Opening.Failed -> println(opening.message)
    is Opening.Opened -> opening.connection.use { bus ->
        when (val reply = bus.call(readColorScheme, SettingKey("org.freedesktop.appearance", "color-scheme"))) {
            is Reply.Returned -> println(reply.value.value)   // PreferDark
            is Reply.Failed -> println(reply.error.message)
        }
    }
}
```

The reply's signature is checked against the declared type **before** anything is decoded. A reply of another shape
is `DBusError.Mismatched` (expected `v`, got `s`), and a `Variant<ColorScheme>` holding a string instead of a `u` is a
mismatch too, rather than a wrong value. An error the other side sends is `DBusError.Remote`, with its name and
message. Nothing here is thrown; every outcome is a value to match on.

### Signals as flows

A signal is declared the same way, with the type of its arguments. `where` names an argument by the property that
holds it, and becomes an `argN` match the bus applies, so the argument's position is never written by hand:

```kotlin
@Serializable
data class SettingChanged<T>(val namespace: String, val key: String, val value: Variant<T>)

val colorSchemeChanged =
    signal<SettingChanged<ColorScheme>>("/org/freedesktop/portal/desktop", "org.freedesktop.portal.Settings", "SettingChanged")

bus.signals(colorSchemeChanged, matching = listOf(colorSchemeChanged.where(SettingChanged<ColorScheme>::key, "color-scheme")))
    .collect { println(it.arguments.value.value) }
```

`signals` is a cold `Flow`: collecting it calls `AddMatch`, and cancelling it calls `RemoveMatch`. A signal that does
not decode as its declared type is passed over.

Following a value is a common need: read it once, then follow its changes. `watch` does that, and in an order that
loses nothing: it subscribes first, then reads, so a change made between the two is still seen.

```kotlin
bus.watch(
    colorSchemeChanged,
    matching = listOf(...),
    read = { call(readColorScheme, key).orNull()?.value ?: ColorScheme.NoPreference },
    changed = { it.arguments.value.value },
)
```

### Serving

A connection can answer calls too, which is what the portal backend will do:

```kotlin
bus.serve(readColorScheme) { called -> Answer.Returned(Variant(ColorScheme.PreferDark)) }
```

A call whose arguments are not the declared type is answered `InvalidArgs` without the handler seeing it. A handler
that throws is answered `Failed`, and the connection carries on. Every connection also answers
`org.freedesktop.DBus.Peer.Ping`, as the specification asks of every peer.

### What it does not do (yet)

- **File descriptors** (`h`): the library does not negotiate passing them, so it never receives one.
- **Addresses** other than a Unix socket with a path: not TCP, and not Linux's abstract socket namespace, which the
  JDK's Unix sockets cannot name. The session and system buses use path sockets on every current distribution.
- **Introspection and properties**: there is no built-in `org.freedesktop.DBus.Properties` or `Introspectable`. They
  are messages like any other, for a program to declare when it needs them.

The library declares no messages of its own beyond the bus's plumbing (`Hello`, `AddMatch`, `RemoveMatch`), which it
calls itself. What to call and what to listen for is the program's to declare.

## How it is tested

- **The wire format** against bytes worked out by hand from the specification (the `v u 1` above among them), every
  type there and back in both byte orders, and the reader's refusals: non-zero padding, invalid UTF-8, a NUL in a
  string, a boolean of 2, arrays that run past the end.
- **The type mapping**: signatures worked out from real types, each refusal with its path, values there and back
  through the wire, and mismatches reported as values.
- **Live**, against a dbus-daemon each test starts on a socket of its own, so every message the library sends is one
  the real daemon checked: the handshake and `Hello`, calls and their errors, two connections calling each other,
  signals with the bus's own filtering, `watch`, and closing. Then against three other implementations: GLib's `gdbus`
  calls a method this library serves, libdbus's `dbus-send` and systemd's `busctl` send it signals, and `busctl` calls
  it too.

(`gdbus emit`, given a bus address, sends its signal before it has called `Hello`; dbus-daemon passes on nothing from
a connection it has not named, so that is not used as a test.)

## The colour scheme

The `portal` module declares the portal's Settings methods and signal, and `Connection.colorScheme()` follows the
colour scheme as a flow. The app (`prism pick`) asks for the first value before its window opens, so the window
opens in the right palette, then switches palette whenever the user changes their preference. Where there is no
session bus or no portal, the preference is "none", and the picker is light. `--theme light` or `--theme dark`
overrides it.

A portal older than version 2 of the Settings interface has no `ReadOne`; for those, the older `Read` is used, whose
value comes in a variant within a variant (`Variant<Variant<ColorScheme>>`).
