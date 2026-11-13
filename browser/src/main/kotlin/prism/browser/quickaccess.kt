package prism.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.nio.file.Files
import java.nio.file.Path
import prism.preferences.Builtin
import prism.preferences.QuickAccess
import prism.preferences.QuickItem
import prism.window.WindowLocals

/*
 * The window that arranges quick access, over the browser, as large as the sidebar is drawn. Every entry is in it, in
 * order, each moved up or down: prism's own places shown or hidden by their boxes (they are never removed, so one hidden
 * is always there to show again), and the folders pinned removed. Each change is made as it is made, so the sidebar
 * behind shows it; Done, or Escape, closes it.
 */

/** The window that arranges [sidebar]'s quick access. */
@Composable
internal fun QuickAccessLayer(sidebar: Sidebar, onEvent: (BrowserEvent) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val quick = sidebar.quick
	val entries = sidebar.arranging
	val done = remember { FocusRequester() }
	var typed by remember { mutableStateOf("") }
	val folder = typed.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) }
	val pinnable = folder?.let { it.isAbsolute && Files.isDirectory(it) } == true

	fun arranged(quick: QuickAccess) = onEvent(BrowserEvent.Arranged(quick))

	fun close() = onEvent(BrowserEvent.ArrangingQuickAccess(false))

	fun pin() {
		folder?.takeIf { pinnable }?.let { arranged(quick.pinning(it)) }
		typed = ""
	}

	Scaled(LocalSidebarScale.current) {
		Modal(
			"Quick access",
			onDismiss = ::close,
			width = ModalWidth.Large,
			tag = "quick-access",
			focus = done,
			buttons = {
				ButtonRow(
					Act("Done", "done", focus = done, onClick = ::close),
					aside =
						listOf(
							Act("Reset", "reset", quick != reset(quick)) { arranged(reset(quick)) },
						),
				)
			},
		) {
			Text(
				"What the sidebar shows first, in this order: drag an entry by its grip to move " +
					"it, or several chosen by Shift and a click together. Prism's own places are " +
					"hidden by their boxes, and can always be shown again; folders you pinned can be " +
					"removed. A folder's menu pins it.",
				style = type.muted,
			)
			// As tall as the window has room for, and scrolled where it has not.
			ScrollArea(Modifier.fillMaxWidth().weight(1f, fill = false)) {
				Card {
					ReorderableColumn(
						entries,
						key = { it.builtin ?: it.item?.path ?: it },
						moving = Moving.Several,
						onMove = { arranged(quick.moved(it)) },
						divided = true,
					) { entry, grip ->
						val index = entries.indexOf(entry)

						EntryLine(
							entry,
							labelOf(entry),
							sidebar.known[Builtin.Home]?.path,
							index,
							last = entries.lastIndex,
							grip,
							onShown = { shown -> entry.builtin?.let { arranged(quick.showing(it, shown)) } },
							onMoved = { by -> arranged(quick.moved(index, by)) },
							onRemoved = { (entry.item?.path)?.let { arranged(quick.unpinning(it)) } },
						)
					}
				}
			}
			Labelled(
				"Pin a folder",
				help = "That is not a folder's whole path.".takeIf { folder != null && !pinnable },
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Space.s),
				) {
					Field(
						typed,
						{ typed = it },
						Modifier.weight(1f).testTag("pin-path"),
						hint = "/home/you/Projects",
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

/** One entry: shown or not (prism's own), its icon and name, and moved up, down, or (a folder pinned) away. */
@Composable
private fun EntryLine(
	entry: Sidebar.Arranged,
	label: String,
	home: Path?,
	index: Int,
	last: Int,
	grip: Grip,
	onShown: (Boolean) -> Unit,
	onMoved: (Int) -> Unit,
	onRemoved: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val builtin = entry.builtin

	Row(
		Modifier
			.fillMaxWidth()
			.then(grip.entry)
			.background(if (grip.lit) palette.chosen else palette.raised)
			.testTag("quick:$label")
			.padding(horizontal = Space.m, vertical = Space.s),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		GlyphIcon(Glyph.Grip, palette.textFaint, type.icon, grip.handle.testTag("grip:$label"))
		Box(Modifier.width(type.icon), contentAlignment = Alignment.Center) {
			if (builtin != null)
				CheckBox("", entry.shown, Modifier.testTag("shown:$label"), onChange = onShown)
		}
		GlyphIcon(
			entry.item?.let(::glyphOf) ?: builtin?.let(::glyphOf) ?: Glyph.Folder,
			palette.textMuted,
			type.icon,
		)
		Column(Modifier.weight(1f)) {
			Text(
				label,
				style =
					if (entry.shown)
						type.body
					else
						type.body.copy(color = palette.textFaint),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)

			// Where it is, for a folder; and, for one of prism's own that is not on this computer, that it is not.
			val where =
				when {
					entry.item == null -> "Not on this computer"
					entry.item is SidebarItem.Trash -> "Every drive's trash"
					else -> entry.item.path?.let { tildeOf(it, home) }
				}

			where?.let {
				Text(it, style = type.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
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
	}

/** [path], from `~` where it is in [home]. */
private fun tildeOf(path: Path, home: Path?): String =
	if (home != null && path.startsWith(home))
		"~" + path.toString().removePrefix(home.toString())
	else
		path.toString()

/** [quick] with prism's own places in their own order, every one shown, before the folders pinned, in theirs. */
private fun reset(quick: QuickAccess): QuickAccess =
	QuickAccess.of(quick.pins.map(QuickItem::Pinned))
