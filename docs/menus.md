# Context menus the user configures: actions and places

The explorer's context menus offer what every file manager's do: open, open with, cut, copy, paste, rename, the
trash, properties, a terminal here. Those are prism's own, and are written in Kotlin (`browser/menus.kt`). This
document is about what the user adds to them: **actions**, which a menu offers and a key does, and **places**, which
are what a window shows when it is not showing a folder on disk. Both are declared in the preferences file, as JSON.

To show that the two are enough, prism's archive support is made of nothing else. There is no archive code in
prism: viewing a `.zip` or a `.tar.gz`, extracting from it by a menu or by dragging, adding to it and removing from it
are declarations in one file (`browser/src/main/resources/prism/browser/actions.json`), which run `tar`, `zip` and
`unzip`. A user can write the same for any other tool.

## One action, followed through

Say the explorer shows `~/Downloads`, and the user right-clicks `photos.zip` and chooses "Extract to…". This is the
action, written out with one program (as it ships, it names several, and is read as the first that is installed):

```json
{
  "id": "archive.zip.extract-to",
  "label": "Extract to…",
  "when": { "ext": ["zip", "jar", "epub"], "kind": "file", "programs": ["unzip"] },
  "ask": { "dest": { "folder": "Extract to", "start": "{folder}" } },
  "run": ["unzip", "-n", "-q", "{path}", "-d", "{dest}"],
  "how": { "watched": true },
  "then": { "reread": true }
}
```

**The menu is built.** `contextMenuOf(state, target, bindings, world)` builds prism's own entries, and among them
every action whose conditions hold for what was pressed. What was pressed is a `Subject`: what the menu is of (rows),
the things (one file, `photos.zip`), and where the window is (a folder). This action's `when` asks three things of
it: that each name ends in one of those extensions, that each is a file, and that `unzip` is on the `PATH`. None of
those runs anything, so the menu opens at once.

**Choosing it is an event.** The entry's action is `BrowserEvent.Did(action, subject)`, which the reducer takes, as it
takes every other thing the user does. The reducer runs nothing; it works out what should happen next
(`browser/acting.kt`).

**What it asks is asked first.** The action has a question, `dest`, of the kind that wants a folder. The reducer keeps
the action as `pending` and asks the application for a picker: `Effect.Ask(Request.PickFolder("Extract to",
~/Downloads))`. The explorer opens a window for it, which is prism's own folder picker, and sends what it chose back
as `BrowserEvent.Answered(folder)`. Had the picker been cancelled, the action would be let go, and nothing run.

**The command is filled in.** With `dest` answered, the words of `run` are filled: `{path}` becomes the file's path,
as one word, and `{dest}` the folder chosen. The result is a value, `Effect.Run(Job(...))`, whose words are
`unzip -n -q /home/avery/Downloads/photos.zip -d /home/avery/Pictures`, which is what the tests read.

**It is run, and what follows it follows.** The session runs the job and sends back `Answer.Ran`. While it runs, the
status line says "Extract to…". Because the action says `"then": { "reread": true }`, the folder is read again. Had
`unzip` failed, what it wrote to standard error would be shown in a notice.

The rest of this document is the parts: what an action can say, then places.

## An action, declared

Actions are under `"actions"` in `$XDG_CONFIG_HOME/prism/preferences.json`, beside the pins and the bindings, so a
change reaches every window as the file is saved. An action written there with the id of one that ships replaces it.

```json
{
  "actions": [
    {
      "id": "half",
      "label": "Half size",
      "submenu": "Image/Resize",
      "when": { "types": ["image/*"], "programs": ["magick"] },
      "run": ["magick", "{path}", "-resize", "50%", "{stem}-half.{ext}"],
      "how": { "watched": true },
      "then": { "reread": true }
    },
    {
      "id": "code",
      "label": "Open in VS Code",
      "when": { "kind": "folder", "programs": ["code"] },
      "run": ["code", "{paths}"],
      "key": "Ctrl+Shift+O"
    }
  ],
  "menu": { "hidden": ["builtin.copy-path"] }
}
```

### When it is offered

Every condition named must hold, and each is optional.

| condition | holds when |
|---|---|
| `types` | each file's MIME type, or a type it is a kind of, is one of these (`image/*` is every image; `text/plain` matches source code, whose types are kinds of text) |
| `names` | each name matches one of these globs (`*.foo`, `Makefile`) |
| `ext` | each name ends in one of these extensions (`foo`, `tar.gz`), whether or not prism knows the type |
| `kind` | each is a `file`, a `folder`, or a `link` |
| `count` | the things chosen are `one`, `some` (one or more), or `many` (two or more) |
| `under` | each is under one of these folders (`~/Projects`) |
| `inside` | each is, or is under, a folder that holds one of these names (`.git`, `Cargo.toml`) |
| `programs` | every one of these is on the `PATH`; `7zz|7z` is either |
| `target` | the menu is of rows (`files`, unless said), of the folder shown itself (`folder`), or of a sidebar place (`place`) |
| `in` | the window is in a `folder`, in a search's `results`, or in the place with one of these ids |
| `elsewhere` | each is a file on disk shown somewhere other than the folder it is in (a search's results, a place) |
| `real` | each is a file on disk (`true`), or an entry only its place knows (`false`) |
| `with` | the place shown was opened with values matching these globs: `{"archive": ["*.tar"]}` |
| `not` | the conditions inside it do not all hold |

`not` is how anything is excluded: `"not": { "in": ["results"] }` is "anywhere but a search's results".

`ext` and `names` are there so that an action does not depend on what prism knows of a type: an action for `*.blend`
files needs no MIME type to exist for them. `kind: "folder"` makes one for folders.

A condition written wrong (`"typs"`) makes the action unreadable, and it is said to be; it is never taken for no
condition at all, which would offer the action everywhere.

Two things are decided without being written. An action whose command names `{path}` needs files on disk, so it is
not offered for an entry of an archive; one that names `{entries}` is offered only for those. And an action is offered
in a picker's menus not at all: a picker chooses for another application, and does nothing else to files.

### What it runs

A command is a list of words, never text for a shell. A placeholder is replaced by whole words, so a file called
`-rf "$(…)".txt` is one argument, whatever is in its name.

| placeholder | becomes | the command runs |
|---|---|---|
| `{path}` | one file's path | once for each file |
| `{parent}` | the folder one file is in | once for each file |
| `{name}`, `{stem}`, `{ext}` | one name; without its extension; its extension | once for each |
| `{entry}` | what one entry of a place is called by the place's command | once for each |
| `{paths}` | every file's path, each a word | once |
| `{names}` | every name, each a word | once |
| `{entries}` | every entry, each a word | once |
| `{folder}` | the folder shown; where there is none, the folder the first file is in | as the others say |
| `{dest}`, `{archive}`, … | a value with a name: a question's answer, or one the place shown was opened with | as the others say |
| `{within}`, `{depth}` | in a place: the folder of it shown, as a path with a `/` after it (empty at the top), and how many folders deep that is | as the others say |
| `{temp}` | a folder prism makes for the command, empty, and removes with all in it when the command ends | as the others say |

A value with a name has parts: `{archive.parent}`, `{archive.name}`, `{archive.stem}`, `{archive.ext}`.

`{temp}` is how a command gets somewhere to work without having to clean up after itself: no script that ships
deletes anything. The zip extraction uses it, since `unzip` cannot leave the folders above an entry behind: it
extracts into `{temp}`, and what is under the folder shown is copied from there to where it was asked for.

`{paths}`, `{names}` and `{entries}` stand for several words, so each must be a word by itself. A placeholder that
fills one word can be part of it: `{stem}-half.{ext}`.

A command runs in the folder shown (or, where there is none, the folder the first file is in), which is why `{names}`
are enough to name files.

Someone who wants a shell writes one, with the files as its arguments and never pasted into its text:

```json
"run": ["sh", "-c", "for f; do magick \"$f\" \"${f%.*}.webp\"; done", "sh", "{paths}"]
```

Every value with a name is also in the command's environment, under its name, with `folder`. So a script reads
`$archive` and `$dest`, and the files as `"$@"`:

```json
"run": ["sh", "-c", "tar -xf \"$archive\" -C \"$dest\" -- \"$@\"", "sh", "{entries}"]
```

In a word, a `{` after a `$` is the shell's own (`${f%.*}`), braces around anything that is not a name are text (an
awk program's `{ print $1 }`), and `\{` is a brace as itself.

All of this is checked when the preferences are read, and held in the types (`browser/actions.kt`). A `Command` is a
list of `Word`s, each a `Phrase` of text and `Hole`s or a `Spread` of every file. Whether it runs once or once for
each file is read off which placeholders it holds, and so is whether it needs files or entries, so no setting beside
them can disagree. A command that names one file at a time and every file at once is refused; so is a placeholder
that nothing fills.

### How it is run

| `how` | what happens |
|---|---|
| (nothing said) | started detached, as opening a file is, and forgotten |
| `"terminal": true` | started in the user's terminal |
| `"watched": true` | prism runs it and waits, shown as running (below); if it fails, how it ended and what it wrote are shown |
| `"confirm": "…"` | this question is asked first, with the action's label on the button |

`then` follows a watched command: `"reread": true` reads again what is shown, `"choose": "{stem}.zip"` puts the
cursor on what was made, and `"bring"` (below, with places) brings what it made to where it is wanted. It can only be
said of a watched command, since prism knows nothing of how the others ended.

**When it fails.** A watched command that ends with anything but success is said to have failed, in a notice over the
browser: how it ended, and the last of what it wrote, to standard error where it wrote any there and otherwise to its
output, in a box that scrolls. The words can be selected and copied, and "Copy" copies all of it. A place whose listing fails says the same where its entries would be. Nothing fails
without saying so.

**What a command is given.** A command runs with prism's own environment, which is the one prism was started from, and
with the values it names (`archive`, `folder`) added to it. Whatever the shell prism was started from exported is
there too: a Nix shell exports `out`, say. A script's own variables should be cleared before it fills them (`unset
listed; listed=$(…)`): a shell keeps exporting a variable the environment brought, so filling one of the same name
would hand its value to every program the script runs after, and a value longer than 128 KiB (an archive's whole
listing) stops each of them starting ("Argument list too long"). prism's own listings do this.

### Several ways, and the programs there are

The same thing is done by different programs on different machines: a zip is read by `unzip`, by 7-Zip, by `bsdtar`.
An action can say several ways of doing what it does, the one to be preferred first:

```json
{ "id": "compress.zip", "label": "As a .zip…", "submenu": "Compress",
  "ask": { "to": { "text": "Archive name", "start": "{stem}" } },
  "ways": [
    { "programs": ["zip"], "run": ["zip", "-q", "-r", "{to}.zip", "--", "{names}"] },
    { "programs": ["7zz|7z|7za"], "run": ["{which:7zz|7z|7za}", "a", "-tzip", "{to}.zip", "--", "{names}"] }
  ],
  "how": { "watched": true }, "then": { "reread": true } }
```

It is read as the first of its ways whose programs are all on the `PATH`. `a|b` in `programs` is either, and a word
of a command that is `{which:a|b|c}` is the first of those that is installed, for a program that goes by several
names. A place's listing has `ways` too, each with its `list`.

An action none of whose ways can be run here is left out: it is not offered, has no key, and is not said to be wrong,
since it is written rightly for a machine that has one of the programs. A place that cannot be listed here is left
out with the actions that show it. So what a machine's menus offer is what its programs can do, with nothing written
about it but the ways.

### While it runs

A watched command, and a place being listed, never hold the window up: they run elsewhere, and what they come to is
sent back when they end. One that takes more than a moment (about a third of a second) is shown over the browser:
what it is called, a spinner, the last lines it has written (to its output and to standard error), and a Cancel
button, which Escape presses too. It goes when the command ends.

Cancel ends the command and everything it started: a shell's pipeline is several processes, and each is ended. A
command the user stopped is not then said to have failed. A window closed while a command runs stops it as well.

### What it asks

`ask` is questions by name. Each answer is a value with that name, for the command to use.

```json
"ask": { "to": { "text": "Archive name", "start": "{stem}" } }
"ask": { "dest": { "folder": "Extract to", "start": "{folder}" } }
```

A `text` question is a line typed in a notice over the browser, starting as `start` says. A `folder` question is a
folder chosen in prism's folder picker, which opens on `start`.

### An action that shows somewhere

An action runs a command or shows somewhere, never both. Showing is going to a folder, or to a place:

```json
{ "id": "containing-folder", "label": "Open containing folder",
  "when": { "count": "one", "elsewhere": true },
  "show": { "folder": "{parent}", "choose": "{name}" } }

{ "id": "archive.zip.open", "label": "Open", "default": true,
  "when": { "ext": ["zip"], "kind": "file", "count": "one" },
  "show": { "place": "archive.zip", "with": { "archive": "{path}" } } }
```

`"in": "window"` opens it in a window of its own, and `"in": "here"` in this one. Unless it says, a folder is shown
here, and a place where the place says it opens.

### Where it goes, and its key

`submenu` puts an action in a submenu, nested with `/`: `"Image/Resize"`. `key` is the chord that does it, until the
user binds another: every action is in the preferences window's "Key bindings" page, under "On the files, from the
list", and is rebound there like any of prism's own. From the keyboard an action is done to the rows chosen, or to the
folder shown where none is.

A chord can be bound to one of prism's actions and one of the user's when they are for different things. Delete moves
files to the trash; inside an archive, where there are no files to trash, it is "Remove from archive". Each action
bound to the chord is tried in turn until one has something to do.

`"default": true` makes the action what opening is, for what it is offered for: a double click does it, Enter does
it, and the menu's "Open" is it, in place of the file's application; it is not offered a second time under its own
label. That is how an archive opens in prism's own window. "Open with…" is still there, to open it with an
application this once, but the application chosen cannot be made the default for the type from there: the default is
the action. Where the action cannot be run (none of its programs is installed) it is not there, and the file opens
with its application, as any file does.

`menu.hidden` hides entries by id, in every menu: an action's, or one of prism's own. Prism's ids are `builtin.open`,
`builtin.put-back`, `builtin.open-in-new-window`, `builtin.select-folder`, `builtin.save-over`, `builtin.open-with`,
`builtin.cut`, `builtin.copy`, `builtin.paste-into`, `builtin.rename`, `builtin.copy-path`, `builtin.pin`,
`builtin.unpin`, `builtin.terminal`, `builtin.trash`, `builtin.erase`,
`builtin.properties`, `builtin.undo`, `builtin.redo`, `builtin.empty-trash`, `builtin.paste`, `builtin.sort`,
`builtin.view`, `builtin.hidden-files`, `builtin.hidden-folders`, `builtin.new-folder`, `builtin.new-file`,
`builtin.refresh`, `builtin.arrange`.

### The menu's order, and the keys an entry waits for

Each context menu can be arranged: its entries in order, lines between groups, and when each shows. The preferences
window's "Context menus" page does it by dragging; the file keeps it under `menu.arranged`, one list for each menu:

```json
"menu": {
  "arranged": {
    "folder/space": ["builtin.paste", "-", "builtin.new-folder", "builtin.new-file", "-",
                     "builtin.refresh with Shift", "builtin.properties hidden"],
    "folder/file": ["builtin.open", "mine.read with Ctrl+Alt", "submenu:Compress", "compress.zip", "-", "builtin.copy"]
  }
}
```

A menu is named by where it opens and what on: `folder`, `results`, `trash` or a place's type (`archive.zip`), then
`/` and `file`, `folder`, `several` or `space` (the empty space around the rows); or `sidebar`. An entry is its id:
prism's, an action's, or `submenu:` and a submenu's name. After it, ` hidden` never shows it, and ` with ` and keys
(`Shift`, `Ctrl`, `Alt`, joined by `+`) shows it only when they are held as the menu opens, whatever else is. `-` is a
line; the menu draws none first, last, or two together, nor one between groups it shows nothing of.

What a list does not name goes where prism would put it: after the nearest entry before it in prism's own arrangement
that the list does name. So an action added later turns up next to its neighbours, and an arrangement written before a
new entry of prism's still has it. An item of a submenu is placed after the submenu, by its own id, for when it shows;
and its order among the submenu's others is the arrangement's (as the actions are written, where it names them not). A listing's menu that is not arranged is arranged as the same menu of a folder
is (`results/file` as `folder/file`), so a folder's arrangement is everyone's until one is given its own.

Unless arranged otherwise, prism shows a few entries only with Shift held: Pin to sidebar, Unpin from sidebar, Show
hidden files, Show hidden folders and Refresh. A menu opened from the keyboard (the Menu key, Shift+F10) counts no key
as held.

### One command under two names

`like` makes an action that does what another does, with some of its questions answered beforehand:

```json
{ "id": "archive.zip.extract-beside", "label": "Extract next to archive",
  "when": { "in": ["archive.zip"] },
  "like": "archive.zip.extract", "given": { "dest": "{archive.parent}" } }
```

So the `unzip` command is written once.

## Places

A place is what a window shows that is not a folder on disk. Prism has always had one: a search's results, shown as if
a folder held them all. A declared place is the same idea with a command in the index's stead.

```json
{
  "places": [
    {
      "id": "archive.zip",
      "title": "{archive.name}",
      "takes": ["archive"],
      "opens": "window",
      "list": ["sh", "-c", "zipinfo -T \"$archive\" | awk '…'"],
      "read": "names",
      "out": "archive.zip.extract",
      "in": "archive.zip.add"
    }
  ]
}
```

**What it is opened with.** `takes` names the values a place is opened with; an action that shows it gives them
(`"with": { "archive": "{path}" }`). That is how an archive's window remembers which file it is of. The type and its
values together (`Placed`) say where a window is, as a path does for a folder, so a place can be in the history, be
read again, and be what a new window opens on.

The values can be named only by the place's own commands and by actions that say they are only in it
(`"in": ["archive.zip"]`). An action that names `{archive}` anywhere else is refused when it is read.

**What its window is.** `"sidebar": false` leaves out the sidebar of places on disk, for a place that is a tool of
its own: an archive's window is the archive's, with nothing of the home folder beside it.

**How it is listed.** `list` is run once, in `folder` (the home folder unless said). It prints every entry, and
`read` says how they are printed:

| `read` | each line is |
|---|---|
| `paths` (unless said) | a file on disk, by its path: what `fd`, `rg -l` and `git diff --name-only` print. With `"tree": true`, each is shown under the folders it is in, below `folder`; otherwise all together |
| `names` | where an entry sits in the place (`src/main.c`; a `/` at the end says a folder), then, apart by tabs, any of `size=`, `time=`, `path=` and `kind=folder` |
| `entries` | a JSON object: `at`, and `path`, `size`, `time` and `kind` where they are said |

`"nul": true` reads what is printed as separated by NUL characters, as `-z` and `-print0` print. A `time` is seconds
since 1970, an instant, or a date and time of day as `tar` and `unzip` print them.

So a pipe that puts files under folders of its own prints two columns:

```
Staged/step.kt	path=/repo/browser/step.kt
Untracked/notes.md	path=/repo/notes.md
```

The browser then goes about the tree it was given, without asking the command again: into a folder, up, back. Reading
again (F5, or an action's `then`) lists it again.

### Searching a place

What a place lists is made into an index of its own (`Index.given`, in the index module): the same columns the index
of the disk is held in, built from the entries the command printed. Ctrl+Shift+F in a place searches that index and
nothing else, with the whole search language and the same ranking (`ext:c`, `dir:src`, `*.txt`, `is:dir`), whether or
not the window was opened with an index of the disk at all. A match chosen is gone to in the place; every match shown
is a listing of the place's entries, with the place's actions. Ctrl+F narrows the folder shown, as in any folder.

This holds for every place, whether its entries are files on disk or not: in "Changed files (git)", a search finds
only among the files that changed.

### Files on disk, and entries only the place knows

What a row is decides what can be done with it, and it is in the types (`Item`, in `browser/places.kt`):

- A row with a path is **a file on disk** (`Item.OnDisk`), wherever it is shown. Everything prism does with files
  applies: open, cut, copy, rename, the trash, properties, dragging. "Changed files (git)" is a place of these.
- A row without one is **an entry only its place knows** (`Item.InPlace`). A folder of them can be gone into. Beyond
  that, only what the place's actions do can be done: prism's own entries are built from paths, so "Move to trash" on
  an archive's entry is not hidden by a rule; there is no path to build it from.

A folder that has entries under it in a place is always the place's own grouping, even where a folder on disk has
its name. In "Changed files (git)" shown as a tree, `browser/` holds the two files that changed, and the folder on
disk holds four hundred: if that row were the folder, trashing it would trash all of them. What is done to a row must
be what is shown in it. A folder with nothing under it in the place (every `node_modules` a command found) is the
folder itself, whole.

### The way out, and the way in

`out` names the action that brings entries out of the place into a folder. It must ask for exactly one folder. That
one question can then be answered four ways, all running the same command:

| what the user does | what answers the question |
|---|---|
| chooses the action from the menu | the picker |
| drags entries onto a folder, in this window or another | the folder they are dropped on |
| copies entries, and pastes in a folder | the folder pasted into |
| double-clicks an entry | a folder made for it, from which it is then opened |

So "Extract to…", dragging out of an archive, copy and paste, and looking at a file without extracting the archive,
are one declaration.

**Entries are brought out as themselves.** A file dragged from `src/` in an archive arrives as that file, and not
under a new `src/`; a folder arrives as that folder, with what is in it. No archive program does that by itself, and
each would do it differently, so prism does it, once, for all of them. The way out extracts into a folder prism makes
for it (`{temp}`), under the folders the entries sit in, and says where they are wanted:

```json
"run": ["7z", "x", "-o{temp}", "{archive}", "--", "{entries}"],
"then": { "bring": { "from": "{temp}", "to": "{dest}" } }
```

Once the command has worked, each entry is moved from where it was put to `to`, by its own name. A folder already
there is added to; a file already there is left as it is, so nothing is written over. An action that names no entries
brings everything in `from`.

**A drag says what it carries.** Dragged, entries go as a description (`EntriesOut`): which place, what it was opened
with, the folder dragged from, and what each entry is called. It names no command. The window dropped on brings them
out by its own actions, so it can be a window of another prism process, which reads the same preferences.

**What is made to open an entry from is removed.** The folder an entry is brought out into to be opened, like the
`{temp}` a command is given, is in `/tmp/prism` (`prism`, under the system's temporary folder), named with prism's
process id. It is removed when the window it was made for closes, and
any left are removed when prism ends. Those of a process that was killed are removed by the next prism to start. A place with no `out` has entries that cannot be dragged or copied at all; nothing has to forbid
it.

`in` names the action that takes files in: what dropping files on the place, or pasting them, does. A place with no
`in` takes none.

Only a folder on disk takes a drop of entries. A place's own folders are not targets.

## What ships

`browser/src/main/resources/prism/browser/actions.json` holds prism's own actions and places, which are the examples
the format is learnt from:

- **Archives**, each kind read by the first program installed that can:

  | kind | names | read by, the preferred first | changed by |
  |---|---|---|---|
  | tar, compressed or not | `.tar`, `.tar.gz` `.tgz`, `.tar.bz2` `.tbz2`, `.tar.xz` `.txz`, `.tar.zst` `.tzst`, `.tar.lz`, `.tar.lzma`, `.tar.lz4`, `.tar.Z`, … | `bsdtar`, `tar` | `tar`, a `.tar` that is not compressed |
  | zip | `.zip`, `.jar`, `.apk`, `.epub`, `.cbz`, office documents, … | `unzip`, 7-Zip, `bsdtar` | `zip`, 7-Zip |
  | 7z | `.7z`, `.cb7` | 7-Zip, `bsdtar` | 7-Zip |
  | rar | `.rar`, `.cbr` | `unrar`, 7-Zip, `bsdtar` | `rar` |
  | disk images | `.iso`, `.dmg` | 7-Zip, `bsdtar` | never |

  7-Zip is `7zz`, `7z`, `7za` or `7zr`, whichever is there. `tar` is GNU's on most of Linux and `bsdtar` on macOS, and
  the commands ask which. `bsdtar` comes first for tars because it decompresses by itself, where GNU's runs `gzip`,
  `zstd` and the rest.

  Opening an archive (a double click, Enter, "Open") opens its window. From the folder it is in: "Extract here",
  "Extract into a folder", "Extract to…", none of which writes over what is there. In its window: "Extract to…" and
  "Extract next to archive" on entries, "Extract all to…" on the place, "Remove from archive" (Delete), entries
  dragged or copied out, files dropped or pasted in. Removing and adding are offered for the kinds, and with the
  programs, in the last column.
- **Decompress**, for one file compressed by itself and not a tar (`.gz`, `.zst` `.zstd`, `.xz` `.lzma`, `.bz2`,
  `.lz4`, `.lz`, `.br`, `.Z`), with the program for each, or 7-Zip for those it reads: what it was, beside it, and
  the compressed file left. These are not archives, and are not looked into.
- **Compress** ▸ as a `.zip` (`zip`, or 7-Zip), `.7z`, `.rar` (`rar`), `.tar.gz`, `.tar.zst` or `.tar.xz` (`bsdtar`,
  or `tar` with the program that compresses), each asking for a name.
- **Open containing folder**, here or in a new window, for a file shown elsewhere than its folder.
- **Changed files (git)**, on a folder in a repository: a place of real files, opened here as a search's results are.

Names are names, whatever is in them. Several of these programs read `[`, `]`, `*` and `?` in what they are given
as patterns, so the commands escape them (`unzip`, `zip`, `bsdtar`), or tell the program not to (7-Zip's `-spd`, GNU
tar's `--no-wildcards`). `unrar` and `rar` cannot be told: extracting is still right, since only the entries named are
brought out of what was extracted, but `rar` refuses to remove an entry whose name holds `*` or `?`, and says why,
rather than risk removing more than was asked. A folder is named as `folder/*` to `unzip` and `zip`, which is right
whether or not the archive has an entry for the folder itself (a zip made on macOS has none).

`EntriesTest` brings out every shape (a file at the top, a folder at the top, a file in a folder, a file and a folder
several folders down, an empty folder, several at once, names with brackets, `*` and `?`) of every kind, made with and
without folder entries, by every program that reads it, and removes them from every kind that can be changed.

Every one of these was run in the tests against a real archive, with each program in turn made the only one there
(`ArchivesTest`), but for `.dmg`, for which there was no image to hand: it is declared as `.iso` is, and 7-Zip reads
both.

## Where it is

| | |
|---|---|
| `preferences/actions.kt` | the shapes of the JSON, each action and place read on its own |
| `browser/actions.kt` | what they mean: commands, conditions, actions and places as types, and the checks |
| `browser/places.kt` | what a row is, a place's tree, what a command prints read into it, and running commands |
| `browser/acting.kt` | the reducer's part: an action asked, confirmed and done; a place gone about; the way out and in |
| `browser/menus.kt` | prism's own entries, with ids, and the user's among them |
| `browser/bindings.kt` | `Bindable`: prism's actions and the user's, bound alike |
| `explorer/explorer.kt` | a window on a place, and the folder picker a question opens |

The preferences window's "Actions" page lists what was read, and, for what was not, why not.

## Not built yet

- **Items made as the menu opens** (a submenu whose items depend on the files, from a program asked as the menu is
  built).
- **An editor.** Actions are written by hand in the preferences file. The "Actions" page only shows them.
- **How far along a command is.** It is shown as running, with what it writes, and can be stopped; there is no bar.
- **Dragging entries of a place to another application.** They are dragged between prism's windows. Another
  application would need files, which would mean extracting as the drag starts; it is given their names, as text.
- **Listing a folder of a place only when it is gone into**, for places too large to list at once.
- **Adding into a folder inside an archive.** Files given to an archive go to its top.
- **Undo** of what a command did, which prism cannot know.
