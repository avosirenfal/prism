# Context menus the user configures: a plan

The explorer's context menus offer what every file manager's do: open, open with, cut, copy, paste, rename, the
trash, properties, a terminal here. This document plans how the user will add their own items, and rearrange or hide
the built-in ones. None of it is built yet; the last section says in what order it would be.

## What the user wants to say

Five actions show the range:

1. **"Extract here"** on an archive (a `.zip`, a `.tar.gz`, a `.7z`): run `bsdtar -xf FILE -C FOLDER`, once for each
   archive chosen, and read the folder again when done.
2. **"Compress…"** on any files: ask for a name, then run `tar --zstd -cf NAME.tar.zst FILES...` once, with every
   file, and put the cursor on the archive made.
3. **"Resize to half"** on images only: run ImageMagick's `magick FILE -resize 50% STEM-half.EXT` for each.
4. **"Git log"** on a folder inside a git repository: open a terminal running `git log` there.
5. **"Open in VS Code"** on a folder, but only where `code` is installed, and on Ctrl+Shift+O.

Each action is the same three things: **when** it is offered (which files, how many, where), **what** it runs, and
**how** (detached, in a terminal, or watched until it finishes), plus where in the menu it goes and what key does it.

## What Thunar's custom actions cannot say

Thunar's custom actions (`~/.config/Thunar/uca.xml`, edited in a dialog) get the idea right and the details wrong:

- **When** is a list of name globs and six boxes for kinds of file (folders, audio, images, text, video, other). There
  is no MIME type, so "every archive" is a long list of globs; no "exactly one file" or "two or more"; no condition
  on where the files are (inside a git repository, under `~/Projects`); and no "only if the program is installed".
- **What** is one string, given to a shell, with `%f`, `%F`, `%n` and the rest pasted into it. A file name with a
  quote or a `$` in it either breaks the command or runs part of it, and every action has to be quoted defensively.
- **How** is: started and forgotten. A command that fails says nothing; one that takes a while shows nothing; there is
  no "run in a terminal" except by writing the terminal into the command; there is no question before running, and no
  way to ask for a name first.
- **Where** is a flat list at one place in the menu, in the order the dialog has them; the built-in items cannot be
  moved or hidden; there are no submenus.
- **Keys** for custom actions exist only by editing `accels.scm` by hand.
- **Dynamic items** (a submenu whose items depend on the files, like "Open with" or "Send to") are not possible at
  all without writing a C plugin against Thunar's extension API.
- **Testing** an action means saving it, right-clicking, and guessing why it did not appear.

## The design

### One menu, of entries with names

Menus are already data: `Menu<A>` with `MenuEntry.Item`, `Submenu` and `Separator` (browser/menu.kt), built by
`contextMenuOf(state, target, bindings)`. The first change gives each built-in entry an id (`builtin.cut`,
`builtin.open-with`, `builtin.properties`) and builds the menu in two steps:

1. **Available entries**: every built-in entry that applies to the target, and every user action whose conditions
   hold for it.
2. **Layout**: the user's arrangement applied to them. The layout is an ordered list of sections, each a list of entry
   ids and submenus; an id the layout does not mention goes where its action says, or at the end of its section; an
   id in the `hidden` list is left out.

Built-in and user entries are the same kind of thing from then on, so "move Properties above Rename", "hide Copy
path", and "put my archive actions in a submenu next to Cut" are all the same operation on the layout.

### An action, declared

Actions live in the preferences, beside the pins and bindings, so they reach every window as soon as they change.
The five examples above, as they would be written:

```json
{
  "actions": [
    {
      "id": "extract-here",
      "label": "Extract here",
      "when": { "types": ["application/zip", "application/x-tar", "application/x-7z-compressed",
                          "application/x-compressed-tar", "application/x-zstd-compressed-tar"],
                "kind": "file", "programs": ["bsdtar"] },
      "run": ["bsdtar", "-xf", "{path}", "-C", "{folder}"],
      "how": { "watched": true },
      "then": { "reread": true },
      "submenu": "Archive"
    },
    {
      "id": "compress",
      "label": "Compress…",
      "when": { "count": "some" },
      "ask": { "input": "Name of the archive", "default": "{stem}" },
      "run": ["tar", "--zstd", "-cf", "{input}.tar.zst", "--", "{names}"],
      "how": { "watched": true },
      "then": { "reread": true, "choose": "{input}.tar.zst" },
      "submenu": "Archive"
    },
    {
      "id": "half",
      "label": "Resize to half",
      "when": { "types": ["image/*"], "programs": ["magick"] },
      "run": ["magick", "{path}", "-resize", "50%", "{stem}-half.{ext}"],
      "how": { "watched": true },
      "then": { "reread": true }
    },
    {
      "id": "git-log",
      "label": "Git log",
      "when": { "kind": "folder", "count": "one", "inside": ".git" },
      "run": ["git", "-C", "{path}", "log"],
      "how": { "terminal": true }
    },
    {
      "id": "code",
      "label": "Open in VS Code",
      "when": { "kind": "folder", "programs": ["code"] },
      "run": ["code", "{paths}"],
      "key": "Ctrl+Shift+O"
    }
  ],
  "menu": { "hidden": ["builtin.copy-path"], "order": ["builtin.open", "builtin.open-with", "code", "—", "..."] }
}
```

**When**: every condition named must hold, and each is optional.

| condition | holds when |
|---|---|
| `types` | each file's type, or a type it is a kind of, matches one of these (`image/*` matches every image; `text/plain` matches source code, since its types are kinds of text) |
| `kind` | each file is a `file`, a `folder`, or a `link` |
| `count` | the files chosen are `one`, `some` (one or more), or `many` (two or more) |
| `names` | each name matches one of these globs |
| `under` | each file is under one of these folders (`~/Projects`) |
| `inside` | a folder holding this name (`.git`, `Cargo.toml`) is the file itself or one of its ancestors |
| `programs` | each of these is on the `PATH` |
| `target` | the menu is a file's (`files`, the default), the folder's own (`folder`), or a sidebar place's (`place`) |

`types` uses the desktop's own lineage of types (`FileTypes.lineage`), so it means the same thing on every platform
that has MIME types, and conditions never run anything: whether an action is offered is decided from facts prism
already has (or, for `inside`, a few `stat` calls), so opening a menu never waits on a program.

**What**: a command is a list of words, never a string for a shell. A placeholder is a whole word, and is replaced by
whole words:

| placeholder | becomes | the command runs |
|---|---|---|
| `{path}` | one file's path | once for each file |
| `{paths}` | every file's path, each a word | once |
| `{names}` | every file's name, each a word (run in their folder) | once |
| `{name}`, `{stem}`, `{ext}` | one file's name, without its extension, its extension | once for each file |
| `{folder}` | the folder shown | once |
| `{input}` | what the user typed, where the action asks | as the others say |

Because a placeholder is replaced by whole words, a file called `-rf "$(…)".txt` is one argument, whatever is in it;
the only care an action takes is a `--` before its paths, where the program reads options. Someone who does want a
shell writes one, with the files passed as its arguments rather than pasted into its text, which is the safe way:
`["sh", "-c", "for f; do convert \"$f\" \"${f%.*}.webp\"; done", "sh", "{paths}"]`.

These rules are checked when the preferences are read, not when the action runs. In the types, a command is not text
but a list of `Word`s, each a `Literal` or a `Placeholder` of a known kind, and whether it runs once or once per file
follows from which placeholders it holds, so it is never a separate setting that could disagree with them. A command
that mixes `{path}` with `{paths}` cannot be represented. An action that does not read (an unknown placeholder, an
unknown condition) is shown as broken in the editor, with why, and the rest still load, as an unreadable preferences
file leaves the defaults in place today.

**How**:

| how | what happens |
|---|---|
| (nothing said) | started detached, as opening a file is, and forgotten |
| `terminal` | started in the user's terminal (the preferences' `terminal`, or the desktop's), which stays open |
| `watched` | prism runs it and shows it in the status bar while it runs, with a way to stop it; if it fails, what it wrote to standard error is shown |
| `confirm` | a question first ("Resize 12 images?") |
| `ask` | a line of text asked for first, given to the command as `{input}` |
| `then` | once a watched command finishes: `reread` the folder, `choose` what it made |

### Where it goes, and its key

`submenu` puts an action in a submenu (nested with `/`: `"Image/Convert"`); `menu.order` arranges the whole menu, by
id, with `—` for a separator and `...` for "everything not named, where it would go". `menu.hidden` hides entries,
built-in or not.

`key` binds the action, but bindings are already the bindings window's business, so a user action becomes an action
like any built-in one: `Action` (browser/bindings.kt) turns from an enum into a sealed type, `Builtin(kind)` or
`User(id)`, and every user action appears in the bindings window under "On the files, from the list", rebindable like
the rest. `key` in the action is only its default.

### Items made as the menu opens

Some items cannot be declared, because what they are depends on the files: "Open with ▸" lists the applications for
their types; "Compress ▸" could list the formats whose programs are installed; "Send to ▸" the devices plugged in. A
**provider** makes such items when the menu is built:

```kotlin
interface MenuProvider {
	val id: String

	/** The entries for [files] (their facts known), each with its action; none where there are none. */
	fun entriesFor(target: Target, files: List<Facts>): List<ProvidedEntry>
}
```

prism's own providers are Kotlin ("Open with" becomes one). A user's provider is a program, run with the files as
JSON on its standard input and answering with entries as JSON on its standard output (a label, an id, the command to
run if chosen). It never runs inside prism's process, so a broken one cannot take the explorer down, and it is given a
short time (around 150 ms): the menu opens without waiting, and a provider's items fill in when they come, or its
submenu says it is still asking. Its answers are kept for a while for the same types, so a second right-click does not
ask again.

### The editor

A page of the preferences window, beside the bindings: the actions in a list (each with on/off, and whether it reads),
and for the one chosen a form with its label, conditions, command and how it runs. Thunar's guesswork goes away with
two things the form shows as it is edited:

- **Against what is chosen**: in the explorer window it was opened from, which of the chosen files the conditions hold
  for, and for the ones they do not, which condition fails.
- **The commands it would run**: each command in full, with the real paths in place of the placeholders, as words, so
  what will happen is seen before it does.

The menu's layout is edited on the same page, as a list to drag entries in, built-in and the user's alike. An action
can be copied out as JSON and pasted in, which is how people would share them.

### What ships

A few actions ship with prism as ordinary actions in a defaults file, which the user can switch off or copy and change:
"Extract here" and "Extract to a folder" (with whichever of `bsdtar`, `7z` or `unzip` is installed), "Compress…",
and "Open in a terminal" for a sidebar place. They are the examples the format is learnt from.

### Across platforms

Nothing in the format is Linux's: commands are lists of words (which the JVM's `ProcessBuilder` quotes correctly for
Windows' `CreateProcess` too), conditions are decided from `Facts` and `FileTypes`, which every `Desktop` provides,
and running goes through the `Launcher`. On macOS, `types` matches the MIME types its uniform type identifiers map to.
A defaults file per platform would ship the actions that make sense there.

## In what order it would be built

1. **Ids and the layout.** Built-in entries get ids; `menu.hidden` and `menu.order` are read from the preferences and
   applied by a pure function from the available entries to the menu, tested as the menus are now. This alone lets the
   user hide and reorder the built-in items.
2. **Declared actions.** `UserAction`, its conditions and its command as types in the preferences module, read by
   kotlinx.serialization with the checks above; a pure `available(action, target, facts)` and
   `commandsOf(action, target)`, tested with the examples here. Running is a new effect, carried out by the session
   through the launcher, which already starts commands detached or in a terminal.
3. **Watched commands.** A runner that reports progress and failure back to the session as answers, shown in the
   status bar; `then` reads the folder again and lands the cursor.
4. **Keys.** `Action` becomes sealed, and the bindings window lists user actions.
5. **Providers**, prism's own first ("Open with" moved onto the interface), then programs.
6. **The editor.**

Each step is useful alone, and none needs the ones after it.
