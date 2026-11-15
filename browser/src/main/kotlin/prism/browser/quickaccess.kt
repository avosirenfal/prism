package prism.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import prism.preferences.Builtin
import prism.preferences.QuickAccess
import prism.preferences.QuickItem
import prism.preferences.Role
import prism.preferences.ShownIn

/*
 * The window that arranges quick access, over the browser, as large as the sidebar is drawn. Every entry is in it, in
 * order, each moved up or down, with a box for each kind of window that shows it: the explorer's, and the pickers'.
 * Prism's own places are never removed (one shown nowhere is always there to show again); the folders pinned are.
 * Over each column of boxes is one that stands for them all, and sets or clears them all. Under the entries, apart
 * from them, is whether each kind of window shows the file systems under its quick access.
 *
 * There may be hundreds of entries: they scroll, between the columns' headings and what is under them, which stay
 * where they are, and what is dragged near the top or the bottom scrolls them under it.
 *
 * What is changed is changed in a copy of its own, which the sidebar behind does not show: Apply keeps it, and every
 * window then shows it, and the window stays; Close, or Escape, leaves it, and what was not applied is forgotten, as
 * a settings window's is. Its changes are undone and done again by the window's keys for that (Ctrl+Z,
 * Ctrl+Shift+Z), while it is open, apart from what the window undoes of the disk.
 */

/** The window that arranges [sidebar]'s quick access; a folder typed into it with `~` is in [home]. */
@Composable
internal fun QuickAccessLayer(
	sidebar: Sidebar,
	home: Path,
	onEvent: (BrowserEvent) -> Unit,
) {
	val type = LocalType.current
	// Quick access as it is kept, and as this window has it: what is changed here is changed in a copy, which the
	// sidebar behind does not show until it is applied.
	val kept = sidebar.quick
	var from by remember { mutableStateOf(kept) }
	var quick by remember { mutableStateOf(kept) }
	val entries = remember(sidebar, quick) { sidebar.copy(quick = quick).arranging }
	val changed = quick != kept
	val done = remember { FocusRequester() }
	var typed by remember { mutableStateOf("") }
	// What is typed, as the path across the top reads one: from the home folder after `~`.
	val folder = typed.trim().takeIf { it.isNotEmpty() }?.let { pathOf(it, null, home) }
	val pinnable = folder?.let(Files::isDirectory) == true
	val bindings = LocalBindings.current
	// What this window has changed, to be undone: its own, not the browser's, and gone with it.
	var edits by remember { mutableStateOf(Edits<QuickAccess>()) }
	// The copy as it is now, for the keys to undo from: the modal hears them through what it was first given.
	val now by rememberUpdatedState(quick)
	val scroll = rememberScrollState()

	// Changed from elsewhere while this is open (a folder pinned from its menu in another window): a copy nothing
	// was changed in is that now; one that was changed stays as it is, and applied, is what is kept, as a settings
	// window's own fields are.
	LaunchedEffect(kept) {
		if (quick == from)
			quick = kept
		from = kept
	}

	fun arranged(to: QuickAccess) {
		if (to != quick) {
			edits = edits.edited(quick)
			quick = to
		}
	}

	/** Undone or done again, where there was something to: the copy goes back to what it was. */
	fun stepped(undone: Undone<QuickAccess>?): Boolean =
		undone
			?.also {
				edits = it.edits
				quick = it.to
			} != null

	/**
	 * The copy kept, and shown by every window: the window stays, for more to be changed. The keyboard goes to the
	 * way out, since the button it was on can no longer be pressed, and left on nothing no key would reach the window.
	 */
	fun apply() {
		onEvent(BrowserEvent.Arranged(quick))
		done.requestFocus()
	}

	fun undoing(key: KeyEvent): Boolean =
		key.type == KeyEventType.KeyDown &&
			when (Chord.of(key)?.let(bindings::leftover)) {
				Action.Undo -> stepped(edits.undo(now))
				Action.Redo -> stepped(edits.redo(now))
				else -> false
			}

	fun close() = onEvent(BrowserEvent.ArrangingQuickAccess(false))

	fun pin() {
		// Pinned here, it is shown everywhere, whichever window this is over: where is what the boxes are for.
		folder?.takeIf { pinnable }?.let {
			arranged(QuickAccess.of(quick.items + QuickItem.Pinned(it), quick.filesystems))
		}
		typed = ""
	}

	Scaled(LocalSidebarScale.current) {
		Modal(
			"Quick access",
			onDismiss = ::close,
			width = ModalWidth.Large,
			tag = "quick-access",
			focus = done,
			onKey = ::undoing,
			buttons = {
				// As a settings window's: what waits to be applied said, Apply, and Close, which applies nothing.
				ButtonRow(
					Act("Apply", "apply", changed, onClick = ::apply),
					secondary = listOf(Act("Close", "done", focus = done, onClick = ::close)),
					aside =
						listOf(
							Act("Reset", "reset", quick != reset(quick)) { arranged(reset(quick)) },
						),
					note = if (changed) "Unsaved changes" else "",
				)
			},
		) {
			Text(
				"Drag to reorder. Tick where each place is shown. To pin a folder, use its " +
					"context menu or enter its path below. Changes take effect when applied.",
				style = type.muted,
			)
			// The columns' headings, which stay where they are over the entries as those scroll.
			Matrix(
				Modifier.padding(end = Dims.scrollbarRoom).padding(horizontal = Space.m),
				explorer = {
					AllBox("Explorer", entries, Role.Explorer) {
						arranged(quick.showingAll(Role.Explorer, it))
					}
				},
				picker = {
					AllBox("Pickers", entries, Role.Picker) {
						arranged(quick.showingAll(Role.Picker, it))
					}
				},
			) {
				// Over the entries' names, which come after each one's grip and icon.
				Spacer(Modifier.width(type.icon * 2 + Space.s))
				Text("Place", style = type.strong.copy(color = LocalPalette.current.textMuted))
			}
			// As tall as the window has room for, and scrolled where it has not.
			ScrollArea(
				Modifier.fillMaxWidth().weight(1f, fill = false),
				scroll,
				tag = "quick-entries",
			) {
				Card {
					ReorderableColumn(
						entries,
						key = { it.builtin ?: it.item?.path ?: it },
						moving = Moving.Several,
						onMove = { arranged(quick.moved(it)) },
						divided = true,
						scroll = scroll,
					) { entry, grip ->
						val index = entries.indexOf(entry)

						EntryLine(
							entry,
							labelOf(entry),
							home,
							index,
							last = entries.lastIndex,
							grip,
							onShown = { role, shown -> arranged(quick.showing(index, role, shown)) },
							onMoved = { by -> arranged(quick.moved(index, by)) },
							onRemoved = { (entry.item?.path)?.let { arranged(quick.removing(it)) } },
						)
					}
				}
			}
			// The file systems: a group of the sidebar's, not an entry of quick access, and so apart from those.
			Box(Modifier.padding(end = Dims.scrollbarRoom)) {
				Card {
					Matrix(
						Modifier
							.testTag(
								"quick-filesystems",
							).padding(horizontal = Space.m, vertical = Space.s),
						explorer = {
							CheckBox(
								"",
								quick.filesystems.explorer,
								Modifier.testTag("filesystems:explorer"),
							) { arranged(quick.showingFilesystems(Role.Explorer, it)) }
						},
						picker = {
							CheckBox(
								"",
								quick.filesystems.picker,
								Modifier.testTag("filesystems:picker"),
							) { arranged(quick.showingFilesystems(Role.Picker, it)) }
						},
					) {
						Spacer(Modifier.width(type.icon))
						GlyphIcon(Glyph.Drive, LocalPalette.current.textMuted, type.icon)
						Column(Modifier.weight(1f)) {
							Text(Sidebar.FILESYSTEMS, style = type.body, maxLines = 1)
							Text(
								"Mounted drives",
								style = type.muted,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
				}
			}
			Labelled(
				"Pin a folder",
				help = "Enter the path of a folder.".takeIf { typed.isNotBlank() && !pinnable },
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Space.s),
				) {
					Field(
						typed,
						{ typed = it },
						Modifier.weight(1f).testTag("pin-path"),
						hint = "~/Projects",
						onKey = {
							(
								it.type == KeyEventType.KeyDown &&
									(it.key == Key.Enter || it.key == Key.NumPadEnter)
							).also { entered ->
								if (entered)
									pin()
							}
						},
					)
					PushButton(
						"Pin",
						Emphasis.Secondary,
						enabled = pinnable,
						Modifier.testTag("pin"),
						onClick = ::pin,
					)
				}
			}
		}
	}
}

/** How wide a column of boxes is: its heading's word, and the box under it at its start. */
private val CELL = 112.dp

/**
 * One line of the matrix, whatever it is of (the headings, an entry, the file systems): what it is ([what]), then
 * what is under "Explorer" and under "Pickers", each in a column as wide in every line, and room after them as wide
 * as an entry's buttons, so that the boxes of every line are under their headings.
 */
@Composable
private fun Matrix(
	modifier: Modifier,
	explorer: @Composable () -> Unit,
	picker: @Composable () -> Unit,
	trailing: @Composable RowScope.() -> Unit = {},
	what: @Composable RowScope.() -> Unit,
) {
	val type = LocalType.current

	Row(
		modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		Row(
			Modifier.weight(1f),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
			content = what,
		)
		Box(Modifier.width(CELL)) { explorer() }
		Box(Modifier.width(CELL)) { picker() }
		// An entry's three buttons, or the room they would take.
		Row(
			Modifier.width(type.control * 3),
			horizontalArrangement = Arrangement.End,
			verticalAlignment = Alignment.CenterVertically,
			content = trailing,
		)
	}
}

/**
 * The box over a column, which stands for every entry's box under it: ticked where the windows for [role] show
 * every entry, empty where none, and barred where some. Pressed, every one is ticked; all ticked already, none is.
 */
@Composable
private fun AllBox(
	label: String,
	entries: List<Sidebar.Arranged>,
	role: Role,
	onAll: (Boolean) -> Unit,
) {
	val shown = entries.count { it.shown[role] }
	val state =
		when (shown) {
			entries.size -> ToggleableState.On
			0 -> ToggleableState.Off
			else -> ToggleableState.Indeterminate
		}

	TriBox(label, state, Modifier.testTag("all:${role.name.lowercase()}")) {
		onAll(state != ToggleableState.On)
	}
}

/**
 * One entry: its icon and name, a box for each kind of window that shows it, and moved up, down, or (a folder
 * pinned) away.
 */
@Composable
private fun EntryLine(
	entry: Sidebar.Arranged,
	label: String,
	home: Path?,
	index: Int,
	last: Int,
	grip: Grip,
	onShown: (Role, Boolean) -> Unit,
	onMoved: (Int) -> Unit,
	onRemoved: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val builtin = entry.builtin

	Matrix(
		Modifier
			.then(grip.entry)
			.background(if (grip.lit) palette.chosen else palette.raised)
			.testTag("quick:$label")
			.padding(horizontal = Space.m, vertical = Space.s),
		explorer = {
			CheckBox("", entry.shown.explorer, Modifier.testTag("explorer:$label")) {
				onShown(Role.Explorer, it)
			}
		},
		picker = {
			CheckBox("", entry.shown.picker, Modifier.testTag("picker:$label")) {
				onShown(Role.Picker, it)
			}
		},
		trailing = {
			ToolButton(
				Glyph.Up,
				"Move up",
				enabled = index > 0,
				Modifier.testTag("up:$label"),
				icon =
					type.icon * 0.8f,
			) {
				onMoved(-1)
			}
			ToolButton(
				Glyph.Down,
				"Move down",
				enabled = index < last,
				Modifier.testTag("down:$label"),
				icon =
					type.icon * 0.8f,
			) {
				onMoved(1)
			}
			// A folder pinned is removed; one of prism's own, never, so it has an empty place where the button would be.
			if (builtin == null)
				ToolButton(
					Glyph.Close,
					"Remove from quick access",
					enabled = true,
					Modifier.testTag("remove:$label"),
					icon =
						type.icon * 0.8f,
				) {
					onRemoved()
				}
			else
				Spacer(Modifier.width(type.control))
		},
	) {
		GlyphIcon(Glyph.Grip, palette.textFaint, type.icon, Modifier.testTag("grip:$label"))
		GlyphIcon(
			entry.item?.let(::glyphOf) ?: builtin?.let(::glyphOf) ?: Glyph.Folder,
			palette.textMuted,
			type.icon,
		)
		Column(Modifier.weight(1f)) {
			Text(
				label,
				style =
					if (entry.shown.nowhere)
						type.body.copy(color = palette.textFaint)
					else
						type.body,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)

			// Where it is, for a folder; and, for one of prism's own that is not on this computer, that it is not.
			val where =
				when {
					entry.item == null -> "Not on this computer"
					entry.item is SidebarItem.Trash -> "Deleted items from every drive"
					entry.item is SidebarItem.System -> "Drives and storage"
					else -> entry.item.path?.let { tildeOf(it, home) }
				}

			where?.let {
				Text(it, style = type.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
	}
}

/** What [entry] is called: its place's name where it is there, or the name of the place of prism's own it would be. */
private fun labelOf(entry: Sidebar.Arranged): String =
	entry.item?.label ?: entry.builtin?.let(::nameOf).orEmpty()

/** What one of prism's own places is called where the desktop does not name it (it is not on this computer). */
private fun nameOf(builtin: Builtin): String =
	when (builtin) {
		Builtin.Home -> "Home"
		Builtin.Desktop -> "Desktop"
		Builtin.Documents -> "Documents"
		Builtin.Downloads -> "Downloads"
		Builtin.Pictures -> "Pictures"
		Builtin.Music -> "Music"
		Builtin.Videos -> "Videos"
		Builtin.Trash -> "Trash"
		Builtin.System -> "System"
		Builtin.Recents -> "Recents"
	}

/** [path], from `~` where it is in [home]. */
private fun tildeOf(path: Path, home: Path?): String =
	if (home != null && path.startsWith(home))
		"~" + path.toString().removePrefix(home.toString())
	else
		path.toString()

/**
 * [quick] with prism's own places in their own order before the folders pinned, in theirs; every one of them, and
 * the file systems, shown in every window.
 */
private fun reset(quick: QuickAccess): QuickAccess =
	QuickAccess.of(quick.pins.map { QuickItem.Pinned(it, ShownIn.EVERYWHERE) })
