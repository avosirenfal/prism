# How prism's windows are built

Every window of prism (the explorer, the picker, the preferences, a file's properties, "Open with") is made of the
same few parts, and every part takes its sizes, its room and its colours from one place. This page shows one row of
the preferences being built, then says what each part is and the rules they keep.

## One row, built

The Display page's row for the listing's font reads, on screen:

```
┌──────────────────────────────────────────────────────────────────────────┐
│ The listing                                  [Default font ▾] [− 11 pt +] │
│ A folder's rows and tiles: Ctrl and the wheel zoom them from here         │
└──────────────────────────────────────────────────────────────────────────┘
```

Its code says what is in it, and nothing about how it is spaced:

```kotlin
Section("Text", description = "The font and size of each part of a window.") {
	Card {
		SettingRow("The listing", description = "A folder's rows and tiles: …") {
			Select(families, typography.listing.family, { it ?: "Default font" }) { … }
			Stepper("11 pt", smaller = true, larger = true) { … }
		}
	}
}
```

Where the room comes from:

- **The section** sets its title over the card, with `Space.m` between them.
- **The card** draws the raised surface and its rounded edge. It also draws a line between each two things in it, so
  no caller places a divider.
- **The setting row** keeps the card's room inside its edges (`CardInsets`: `Space.l` across, `Space.m` down) and sets
  its title and description at its start. It puts its controls at its end, `Space.s` apart, and is never shorter than
  a control and its room.
- **The controls** are as tall as their text needs (`Type.control`: the body's line and 14 dp).

Change the part's font to 14 points and every one of these grows with it; nothing has a height of its own.

## The tokens (`browser/theme.kt`)

**Spacing** (`Space`) is one scale: 2, 4, 8, 12, 16, 24 and 32 dp. Things that belong together are a step or two
apart, and things that do not are further. Nothing is spaced outside the scale. Corners are `Radius.small` (a check
box), `Radius.medium` (a control, a row) and `Radius.large` (a card, a popup).

**The palette** (`Palette`) names colours by what they are for, not by what they look like:

- **Surfaces**, from the window's frame inward:
  - `window`: the toolbar and the status bar;
  - `pane`: a sidebar;
  - `surface`: the listing;
  - `canvas`: what a page's cards sit on;
  - `raised`: a card;
  - `overlay`: a menu or a modal.
- **One accent**, a calm blue, fills what a dialog is for, and `danger` fills what cannot be undone.
- **Hover and press** are translucent, so they show on any surface.

The light palette and the dark one have the same names; a theme from the desktop is a palette made from it.

**Type** (`Type`) is a scale of styles from one body size for each part of a window: the general text, the listing,
and the menus (`Types`). The sizes are in points, as desktops measure type. A part's controls and rows are measured
from its own body text (`Type.control`, `Type.row`, `Type.icon`), which is how the listing's rows grow with the
listing's text and a menu's rows with the menus'.

## The parts

**Controls** (`browser/elements.kt`):

- `PushButton`, in four emphases: `Primary`, `Danger`, `Secondary` and `Quiet`.
- `ToolButton`: a glyph that says what it does when the pointer rests on it.
- `Field`, `CheckBox`, `Switch` and `Radio`.
- `Segmented`: a few choices in a track, the chosen one raised. A toolbar's takes no keyboard focus. Its choices are
  each as wide as the widest where that fits, each as wide as it needs where only that does, so a narrow window reads
  every choice whole.
- `ReorderableColumn`: a list the pointer puts in order, an entry dragged by its handle and the rest making room for
  it. Each use says whether its entries move one at a time or several together (`Moving`), with no default: where
  several do, Shift and a click choose an entry or no longer (kept from what is under it, a switch, a button), and a
  chosen one dragged gathers every one chosen under it, in their order. A click without Shift, or another entry
  dragged, lets the choice go. Each entry is drawn with a `Grip`: the modifier of its handle, of the part of it a
  Shift-click chooses it by (all of it, but a list nested in it, as a submenu's items are, which chooses its own), and
  whether it is lit. What it reports is a `Moved` (which entries, and where the first of them lands), made on a list by
  `moved`, or among those of it shown by `movedAmong`, so every list is reordered by the same rule.
- `Toggles`: a few options in one outlined track, any number of them on, each turned on or off by itself; one on is
  filled in the accent, one off is only its name, so that down a long list of rows only what is on stands out (the
  keys an entry of a context menu waits for).
- `Stepper`: a value, and − and + either side of it.
- `Chip`, `Tabs`, `Spinner` and `EmptyState`.
- `Select` (`select.kt`): a drop-down list, with a field that narrows a long one (every font the system has).
- `ComboBox` (`combo.kt`): a field that is a drop-down list too. Clicked, it lists every option; typed into, those whose
  names have what is typed in them, the first lit for Enter. Its options come in groups (`OptionGroup`), each set apart
  from the one before, under a heading where it has one, and faint where they can be chosen but are not the likely
  choice. The field keeps the keyboard throughout, and its text is selected when it is clicked, so typing starts a
  new search.

Each control draws its own hovered, pressed, focused and disabled looks. Its ring for the keyboard is drawn only
while the keyboard was used last.

**Layouts** (`browser/layout.kt`):

- `Page`: a headline, what the page is for, then its sections, scrolled with a bar beside them.
- `Section`, `Card`, `CardItem`, `SettingRow`, `Labelled`, `ListRow` and `ListHeading`.
- `CellText` (`cells.kt`): a line of a listing's text, laid out once in a cache every row shares (`LocalCells`) and
  drawn from there, so a row coming into sight lays out only what no row before it showed. A date's parts are runs
  side by side in one node, each in the width of the widest of its kind; a size is drawn a figure at a time
  (`figuresOf`), so every size comes from the same ten figures. A listing's names, sizes, types and dates are drawn so.
- `Notice`: a line of a card that says how something is, rather than setting it: a glyph for its `Tone` (a tick where
  it is as it should be, a warning, in red, where it is not, an i otherwise), its title, and what more there is to say.
- `ButtonRow`: the buttons at a dialog's foot. They are given as what they are, not where they go: what is aside (Reset,
  Copy) at its start; then the ways out (Cancel, Close); and what the dialog is for last, at the far end. Every
  dialog's buttons are in that order.
- `Modal`: a card over a scrim. Escape and a click outside dismiss it, and the keyboard starts where it says.

None of them takes padding from its caller.

**Scrolling** (`browser/scrolling.kt`): everything that scrolls is a `ScrollArea` (a column of anything) or a
`LazyScrollArea` (a lazy list), each with its bar drawn whenever there is more than fits. The wheel scrolls either by
`LINES_A_NOTCH` (three) of its own lines a notch, gliding there, rather than by a distance that knows nothing of what
is shown. The listing and the gallery use the same rule with their rows and lines of tiles (`wheelScrolled`), so a
notch moves three rows at any zoom. A touchpad's scrolls, and a wheel's that turns smoothly (fractions of a notch,
many a second), move the list at once, by what they say. A notched wheel's glide to where the notches turned so far
take it, a share of what is left each frame from the first. A notch turned meanwhile adds to where the glide is going,
and never starts it again from rest, which would hold the list still for as long as the wheel kept turning. What is wider than its room (the listing, zoomed in far) scrolls across too, with a
bar under it, by the wheel with Shift held or a touchpad's sideways scroll; nothing is cut off out of reach.

The wheel is handled by what holds the list and its bar, and the list's own scrolling by the wheel is off. The wheel
is heard as it comes back up from what it is over, so a list inside another scrolls first.

**Menus** (`browser/menuview.kt`): prism's context menus, and the menu Compose opens for a text field (Cut, Copy,
Paste, Select all, `PrismContextMenus`), are drawn by one `MenuPopup`, so they look and work alike.

## Rules

- A size, a gap or a colour is a token; a number in a view is a sign that a part is missing.
- A part spaces itself; a caller says what is in it.
- Whatever scrolls has a bar.
- Whatever has no words has a tip.
- A dialog's buttons are in one order, the primary last.
- Text sizes are the user's, in points, and everything measured from text follows them.
