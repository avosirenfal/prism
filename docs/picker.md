# The picker

The picker is the dialog itself: the window an application's "Open" or "Save as" shows. This page explains how it
works, starting with one example followed from start to finish, and then each part of it in turn.

![Saving over a file asks first](picker-replace.png)

## One example, followed through

Say the picker opens on your home folder to choose one file, and you press the Down arrow once and then Enter.
Here is everything that happens, in order.

**It opens.** `barion pick` calls `opened`, which makes the picker's first state and says what it needs:

```kotlin
val first: Step = opened(Choosing.OneFile(), folder = home, home = home, sidebar = sidebarOf(home))

first.state.listing   // Listing.Reading(Landing.Top): the folder is not read yet
first.effects         // [Effect.Read(/home/avery)]: please read it
```

A `Step` is a state and a list of **effects**: things the picker wants done that it cannot do itself, because the
code that decides what the picker does never touches the disk. Here it wants the home folder read.

**The folder is read.** A `Session` holds the running picker. It sees `Effect.Read`, reads the folder on a background
thread through a `Disk` (the file system, through the JVM), and sends the answer back in:

```kotlin
Answer.Arrived(folder = /home/avery, read = Listing.Shown(entries))
```

The same function that made the first state now makes the next one from it and this answer. That function is
`step(state, input)`, the **reducer**. It puts the rows in the state, and the window draws them.

**You press Down.** The listing has the keyboard (a picker that opens files starts there), so the view turns the key
into an event and sends it to the session:

```kotlin
PickerEvent.Moved(Move.By(1), Carry.Choose)
```

The reducer moves the cursor to the first row (with no cursor yet, the first move lands on the first row) and
chooses it. The view draws the row highlighted, and the name field shows its name, because the name field shows what
is chosen.

**You press Enter.** That is `PickerEvent.Accepted`. If the row is a folder, accepting goes into it: the reducer
answers with a new state reading that folder, and an `Effect.Read` for it. If it is a file, the reducer answers with
`Effect.Finish(Outcome.Chosen([that file]))`. The session completes its outcome with that, the window closes, and
`barion pick` prints the path.

That is the whole loop: **the view reports what the user did, the reducer decides the next state and what to ask
for, the session carries out what was asked and sends the answers back.** Everything below is a closer look at one
part of it.

## The parts, and where they are

| file | what |
|---|---|
| `model.kt` | the state: `PickerState` and everything it holds |
| `events.kt` | what changes it (`PickerEvent` from the user, `Answer` from the disk) and what it asks for (`Effect`) |
| `step.kt` | the reducer, `step(state, input): Step`, and every rule of what the picker does |
| `session.kt` | `Session`: the reducer run over every input in turn, the effects carried out |
| `source.kt` | `Disk`, and `JvmDisk`: a folder read into rows, a typed path looked up; the rows' text worked out |
| `elements.kt` | the controls: a button, a tool button, a text field, a drop-down choice, the keyboard's ring |
| `view.kt` | `FilePicker(state, onEvent)`: the picker drawn from a state, made of those controls |

The reducer is a pure function: it reads nothing and runs nothing. So every rule can be checked as values, by
giving a state and an event and looking at what comes back, and that is what `StepTest` does, forty-odd times.

## What the state holds

`PickerState` is immutable. Each change makes a new one. It holds:

| field | what |
|---|---|
| `choosing` | what the picker is for, and what is chosen so far (below) |
| `folder`, `home` | the folder shown, and the home folder the path across the top starts from |
| `history` | the folders Back and Forward go to |
| `listing` | the folder's entries: being read, read, or unreadable (with why) |
| `arrangement` | the order, the type filter, whether hidden files are shown, the search words |
| `cursor` | the row the keyboard is on, and the row a Shift-range starts from |
| `address` | the path across the top: steps to click, or a path being typed |
| `notice` | a question or a message drawn over the picker ("already exists; replace it?") |
| `waiting` | a typed path being looked up, whose answer is waited for |

Some things the view shows are not stored, because they can be worked out from what is: the button's words and
whether it can be pressed (`accept`), what the name field says (`nameField`), the steps of the path (`crumbs`), and
the rows the listing shows (`rows`). Each has one source, so none can disagree with it.

### What is chosen

What the picker is for is a sealed type, and each kind holds only what it can:

| kind | chosen | the button | accepting |
|---|---|---|---|
| `OneFile` | one row or none | Open, once something is chosen or typed | a file is opened; a folder is gone into |
| `Files` | any number of rows | Open, once a file is chosen (or one folder) | the files among them are opened; one folder alone is gone into |
| `SaveAs` | one row or none, and always a name | Save, once the name is not blank; Open while a folder is chosen | saved under the name, asking first if it is a file already; a chosen folder is gone into |
| `Folder` | one folder or none | Select folder, always | the chosen folder, or the folder shown when none is |

So a picker for one file cannot hold two chosen rows: the type has room for one. And a saving picker always has a
name: there is no `SaveAs` without one.

What the types cannot say is checked once, when a state is made: that every chosen row is a row the listing shows,
that the cursor is on one, and that the type filter is one the picker offers. Since every change makes a new state,
no state that breaks those can exist, and so the view is never given one.

### The name field

The name field shows what is chosen ("notes.md", or `"a.txt" "b.txt"` for several) until you type into it. From then
on, what you typed is what the picker accepts, until you choose a row again. That is stored as the `typed` text of the
`Choosing`: null while the field follows the rows, the text once you have typed.

What is typed can be a name, several names in quotes, a path (`Documents/2026`), a path from your home (`~/notes`),
or a path from the root (`/etc/hosts`). Before the picker does anything with it, it is looked up on disk:

```kotlin
step(state with typed = "~/Documents", PickerEvent.Accepted)
// state.waiting = Lookup.Accepting("~/Documents", [/home/avery/Documents])
// effects       = [Effect.Look(that lookup)]
```

and the answer (`Answer.Looked`, each path a `Found.Folder`, a `Found.File`, or `Found.Nothing`) decides: a folder is
gone into, a file is opened, nothing there is "Can't find". Saving, nothing there is the answer wanted (a new file),
as long as the folder it would be in is there; a file there is the question whether to replace it.

The path across the top works the same way: Ctrl+L makes it a field, Enter looks up what was typed, and a folder is
gone to, while a file has its folder gone to and is chosen there.

### Answers that are too late

Reading a folder takes time, and you can go somewhere else meanwhile. So the reducer takes an answer only if the
picker is still waiting for it: a folder's rows only if it is still the folder shown, a lookup only if it is still
`waiting`. Anything else is dropped. That is what makes clicking quickly through folders safe: whatever order the
reads finish in, only the last one counts.

### Going places

Going to a folder puts the folder left on the history's back list and clears the forward list, as a browser does;
Back and Forward move between the two. Going to the folder shown already reads it again (F5 does the same), keeping
what is chosen where it is still there.

When a folder is gone to, the state says where the cursor lands once its rows arrive (`Landing`). Going up lands it on
the folder you came from, so that going down again is Enter.

## The listing's rows, and keeping them fast

A folder is read once into its **entries**: every one, hidden files included, in no particular order. What the
listing shows is the entries arranged: sorted, then narrowed by the filter, the search words and whether hidden files
are shown. That is `rows`.

Working that out on every key press would be slow for a large folder: moving the cursor through a folder of a hundred
thousand entries would sort it again each time. So the listing keeps what it last worked out: the entries sorted by
the last order asked for, and those narrowed by the last arrangement asked for. Moving the cursor asks for the same
arrangement and gets the same rows back at once; typing a search word narrows the sorted rows again but does not sort
them; clicking a column's header sorts once. The rows also keep where each row is, so finding the cursor's row is a
lookup, not a search. (This is the one place a value changes inside something immutable, and nothing outside can see
it change.)

Names are compared as people read them: without case, and with numbers by value, so `file2` comes before `file10`.
That comparison is made n log n times for every sort, so it walks both names in place rather than splitting them up.

## The session

`Session` is the picker running. It folds the reducer over every input in the order they arrive, starting from the
first step:

```kotlin
inputs.runningFold(first) { last, input -> step(last.state, input) }
```

and for each step, carries out its effects: a read or a lookup runs on a background thread, and its answer joins the
inputs; a finish completes `outcome`. The current state is a `StateFlow` the window collects. A disk that fails
outright (an exception rather than an error it reports) is answered as unreadable or not found, so a failure never
stops the picker.

`Disk` is an interface, so what reads folders can change without anything else changing. Today it is `JvmDisk`. When
the index is behind the picker, it will be another `Disk`.

## The view and its controls

`FilePicker(state, onEvent)` draws a state and reports events. It changes nothing itself.

It is made of the controls in `elements.kt`. Each draws its own looks (hovered, pressed, focused, disabled) from the
palette, takes the keys a desktop's controls take, and reports only what it is for:

| control | what it is | keys |
|---|---|---|
| `PickerButton` | a button with words: Open, Cancel, Replace | Enter, Space |
| `ToolButton` | a button with a glyph: Back, Forward, Up | none: they have shortcuts of their own, so Tab passes over them |
| `Field` | a line of text: the path, the search, the name | as any text field; it selects all its text when the keyboard arrives (saving, the name without its extension, so typing replaces only the name) |
| `Select` | a choice from a list: the type filter | Enter, Space or Alt+Down open it; the arrows choose without opening it |
| `ring` | the ring around what the keyboard is on | |

A control keeps only what a control keeps on any desktop: whether the pointer is over it, whether its menu is open,
where the caret is in its text. The view adds three things of the same kind: which place the sidebar's arrows are on,
the letters typed in quick succession in the listing, and the last row pressed (for a double click).

### Double clicks

A row is chosen the moment it is pressed, not after waiting to see whether a second press follows. Whether two presses
are a double click is decided by the listing, not by the row, because only the listing sees every press: a double
click is two plain presses of the same row, soon enough, with nothing between. So clicking a row, Ctrl-clicking
another, and Shift-clicking the first again chooses a run of rows; it does not open the first.

### The keyboard

Tab goes through the picker in this order: the path, the search, the places, the listing, the name, the filter, then
the buttons. The places and the listing are one stop each, inside which the arrows move. When the picker opens, the
keyboard is on the listing, or on the name for a picker that saves.

| keys | where | what |
|---|---|---|
| arrows, Page Up and Down, Home, End | listing | the cursor; with Shift, choosing a run; with Ctrl, not choosing |
| Space | listing | the row at the cursor chosen, or no longer |
| Enter | listing, name | accept (a chosen folder is gone into) |
| letters | listing | the next row whose name starts with them; the same letter again, the next such row |
| Backspace | listing | the folder above |
| Ctrl+A | listing | every row chosen |
| Alt+Left, Alt+Right, Alt+Up | anywhere | back, forward, up |
| Ctrl+L, Alt+D | anywhere | type a path |
| Ctrl+F, F3 | anywhere | search |
| Ctrl+H | anywhere | hidden files shown, or not |
| F5, Ctrl+R | anywhere | the folder read again |
| Escape | anywhere | cancel; in the path or a search with words in it, first leave that |

The listing keeps the cursor's row in sight, scrolling as little as it takes: a row just past the bottom scrolls the
list by one row, not a page, and a row far away is scrolled to directly (never by laying out every row between).

## How it is tested

- **The rules** (`StepTest`): states and events in, states and effects out, with folders read from a small file
  system held in memory. Every rule above has a test there.
- **The picker as a user meets it** (`InteractionTest`): composed headless around a running `Session` over a real
  temporary folder, then clicked, double-clicked, Ctrl- and Shift-clicked, typed at and tabbed through with
  Compose's own test input, and what it shows and how it ends checked. These are what found that a Shift-click was
  being read as a double click, and that the listing scrolled before it had been laid out.
- **The drawing** (`PickerTest`): every kind of picker drawn headless, in both palettes, with a notice up, a path
  being typed, a search and an unreadable folder; `-Ppicker.screenshots=DIR` keeps the pictures.

`barion pick` itself has been run in a real window on a virtual X display (Xvfb), driven by xdotool: opening a file
from the keyboard, several with Ctrl-click and the Open button, saving under a typed name, choosing a folder, and
cancelling with Escape, each printing what it should. docs/testing.md says how to do the same.

## Not yet

- The portal: each request of an application becoming a picker (docs/plan.md, "The portal").
- Reading folders from the index, and the search language searching all of it rather than the folder shown.
- Filters by MIME type, as the portal can send them; today a filter is name patterns (`*.png`).
- Thumbnails, a preview pane, column widths kept, drag and drop out of the picker, and a menu on a row (a new folder,
  rename).
