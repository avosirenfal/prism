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
action, as it ships (with fewer extensions shown):

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
| `programs` | every one of these is on the `PATH` |
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
| `"watched": true` | prism runs it and waits, shown as running (below); if it fails, what it wrote to standard error is shown |
| `"confirm": "…"` | this question is asked first, with the action's label on the button |

`then` follows a watched command: `"reread": true` reads again what is shown, and `"choose": "{stem}.zip"` puts the
cursor on what was made. It can only be said of a watched command, since prism knows nothing of how the others ended.

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
"ask": { "to": { "text": "Name of the archive", "start": "{stem}" } }
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

{ "id": "archive.zip.view", "label": "View contents",
  "when": { "ext": ["zip"], "count": "one" },
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

`"default": true` makes a double click (or Enter) on what the action is offered for do it, in place of opening.

`menu.hidden` hides entries by id: an action's, or one of prism's own. Prism's ids are `builtin.open`,
`builtin.open-in-new-window`, `builtin.open-with`, `builtin.cut`, `builtin.copy`, `builtin.paste`,
`builtin.paste-into`, `builtin.rename`, `builtin.copy-path`, `builtin.pin`, `builtin.terminal`, `builtin.trash`,
`builtin.properties`, `builtin.new-folder`, `builtin.new-file`, `builtin.refresh`, `builtin.undo`, `builtin.redo`.

### One command under two names

`like` makes an action that does what another does, with some of its questions answered beforehand:

```json
{ "id": "archive.zip.extract-beside", "label": "Extract beside the archive",
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

**Entries are brought out as they are shown.** An entry dragged from `src/` in an archive arrives as itself, and not
under a new `src/`: the folders above the one shown are left behind. That is what `{within}` and `{depth}` are for:
`tar` is given `--strip-components={depth}`.

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

- **Archives**, tar (`.tar`, `.tar.gz`, `.tar.xz`, `.tar.zst`, …) and zip (`.zip`, `.jar`, `.epub`, …), each with the
  tool for it. From the folder an archive is in: "View contents", "Extract here", "Extract into a folder", "Extract
  to…". In its window: "Extract to…" and "Extract beside the archive" on entries, "Extract all to…" on the place,
  "Remove from archive" (Delete), entries dragged or copied out, files dropped or pasted in. Removing and adding are
  offered where the tool can do it: any zip, and a tar that is not compressed.
- **Compress** ▸ as a `.zip`, `.tar.gz`, `.tar.xz`, `.tar.zst` or `.tar`, each asking for a name, and each offered
  where its program is installed.
- **Open containing folder**, here or in a new window, for a file shown elsewhere than its folder.
- **Changed files (git)**, on a folder in a repository: a place of real files, opened here as a search's results are.

Another archive tool is another pair of declarations: a place whose `list` runs `7z l`, and actions that run `7z x`
and `7z d`.

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

- **The menu's order.** Entries can be hidden, and actions put in submenus, but prism's own entries cannot be
  rearranged, nor an action placed among them by name.
- **Items made as the menu opens** (a submenu whose items depend on the files, from a program asked as the menu is
  built).
- **An editor.** Actions are written by hand in the preferences file. The "Actions" page only shows them.
- **How far along a command is.** It is shown as running, with what it writes, and can be stopped; there is no bar.
- **Dragging entries of a place to another application.** They are dragged between prism's windows. Another
  application would need files, which would mean extracting as the drag starts; it is given their names, as text.
- **Listing a folder of a place only when it is gone into**, for places too large to list at once.
- **Adding into a folder inside an archive.** Files given to an archive go to its top.
- **Undo** of what a command did, which prism cannot know.
