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
	val shape = RoundedCornerShape(8.dp)
	var typed by remember { mutableStateOf("") }
	val folder = typed.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) }
	val pinnable = folder?.let { it.isAbsolute && Files.isDirectory(it) } == true

	fun arranged(quick: QuickAccess) = onEvent(BrowserEvent.Arranged(quick))

	fun close() = onEvent(BrowserEvent.ArrangingQuickAccess(false))

	Popup(
		alignment = Alignment.Center,
		onDismissRequest = ::close,
		properties = PopupProperties(focusable = true),
	) {
		WindowLocals {
			Scaled(LocalSidebarScale.current) {
				Box(
					Modifier
						.fillMaxSize()
						.background(palette.scrim)
						.onKeyEvent {
							(it.type == KeyEventType.KeyDown && it.key == Key.Escape).also { escaped ->
								if (escaped)
									close()
							}
						}.testTag("quick-access"),
					contentAlignment = Alignment.Center,
				) {
					Column(
						Modifier
							.width(520.dp)
							.background(palette.surface, shape)
							.border(1.dp, palette.border, shape)
							.padding(20.dp),
					) {
						Text("Quick access", style = type.title)
						Text(
							"What the sidebar shows first, in this order. Prism's own places are hidden by their boxes, " +
								"and can always be shown again; folders you pinned can be removed. A folder's menu pins it.",
							style = type.muted,
							modifier = Modifier.padding(top = Dims.gapSmall, bottom = Dims.gap),
						)
						// Dragged by their grips, or moved a place at a time by their arrows.
						ReorderableColumn(
							entries,
							key = { it.builtin ?: it.item?.path ?: it },
							onMove = { from, to -> arranged(quick.moved(from, to - from)) },
							modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
						) { entry, handle, dragged ->
							val index = entries.indexOf(entry)

							EntryLine(
								entry,
								labelOf(entry),
								sidebar.known[Builtin.Home]?.path,
								index,
								last = entries.lastIndex,
								handle = handle,
								dragged = dragged,
								onShown = { shown -> entry.builtin?.let { arranged(quick.showing(it, shown)) } },
								onMoved = { by -> arranged(quick.moved(index, by)) },
								onRemoved = { (entry.item?.path)?.let { arranged(quick.unpinning(it)) } },
							)
						}
						Spacer(Modifier.size(Dims.gap))
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(Dims.gap),
						) {
							Field(
								typed,
								{ typed = it },
								Modifier.weight(1f).testTag("pin-path"),
								hint = "A folder to pin, as its whole path",
							)
							PushButton(
								"Pin",
								Emphasis.Secondary,
								enabled = pinnable,
								Modifier.testTag("pin"),
							) {
								folder?.let { arranged(quick.pinning(it)) }
								typed = ""
							}
						}
						if (folder != null && !pinnable)
							Text(
								"That is not a folder's whole path.",
								style = type.muted.copy(color = palette.textFaint),
							)
						Row(
							Modifier.fillMaxWidth().padding(top = 20.dp),
							horizontalArrangement = Arrangement.spacedBy(Dims.gap),
						) {
							PushButton(
								"Reset",
								Emphasis.Secondary,
								enabled = quick != reset(quick),
								Modifier.testTag("reset"),
							) { arranged(reset(quick)) }
							Spacer(Modifier.weight(1f))
							PushButton(
								"Done",
								Emphasis.Primary,
								enabled = true,
								Modifier.testTag("done"),
								done,
							) {
								close()
							}
						}
					}
				}
			}
			LaunchedEffect(Unit) { done.requestFocus() }
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
	handle: Modifier,
	dragged: Boolean,
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
			.background(
				if (dragged)
					palette.chosen
				else
					Color.Transparent,
				RoundedCornerShape(Dims.radius),
			).padding(vertical = 2.dp)
			.testTag("quick:$label"),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Dims.gap),
	) {
		GlyphIcon(Glyph.Grip, palette.textFaint, 18.dp, handle.testTag("grip:$label"))
		if (builtin != null)
			CheckBox("", entry.shown, Modifier.testTag("shown:$label"), onChange = onShown)
		else
			Spacer(Modifier.width(18.dp))
		GlyphIcon(
			entry.item?.let(::glyphOf) ?: builtin?.let(::glyphOf) ?: Glyph.Folder,
			palette.textMuted,
			Dims.icon,
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
			size = 28.dp,
			icon = 16.dp,
		) {
			onMoved(-1)
		}
		ToolButton(
			Glyph.Down,
			"Move down",
			enabled = index < last,
			Modifier.testTag("down:$label"),
			size = 28.dp,
			icon = 16.dp,
		) { onMoved(1) }
		if (builtin == null)
			PushButton(
				"Remove",
				Emphasis.Secondary,
				enabled = true,
				Modifier.testTag("remove:$label"),
				onClick = onRemoved,
			)
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
