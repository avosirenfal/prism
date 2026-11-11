package prism.explorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import prism.browser.Actions
import prism.browser.Bindable
import prism.browser.Bindings
import prism.browser.Chord
import prism.browser.Dims
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.PushButton
import prism.browser.Scope

/*
 * The preferences window. Its edits are one value ([Settings]) that the window replaces with each change and saves
 * when the user does; the window only draws it and says what was done to it. On the bindings page, a binding clicked
 * waits for a chord: whatever is pressed next, a key with what modifiers are held, or a mouse button (middle, back or
 * forward), is taken; Escape stops waiting instead.
 */

/**
 * The preferences window over [settings], each change reported to [onChange]: saved by [onSave], or not, by [onCancel].
 * [mouseChords] are the mouse's buttons pressed over the window, which a binding waiting for a chord takes.
 */
@Composable
fun SettingsView(
	settings: Settings,
	onChange: (Settings) -> Unit,
	onSave: () -> Unit,
	onCancel: () -> Unit,
	mouseChords: Flow<Chord> = emptyFlow(),
) {
	val palette = LocalPalette.current
	val root = remember { FocusRequester() }
	val latest by rememberUpdatedState(settings)
	val change by rememberUpdatedState(onChange)
	val rebinding = settings.rebinding

	LaunchedEffect(mouseChords) {
		mouseChords.collect { chord ->
			if (latest.rebinding.awaited != null)
				change(latest.copy(rebinding = latest.rebinding.pressed(chord)))
		}
	}
	LaunchedEffect(rebinding.awaited) {
		if (rebinding.awaited != null)
			root.requestFocus()
	}

	Row(
		Modifier
			.fillMaxSize()
			.focusRequester(root)
			.focusable()
			.onPreviewKeyEvent { key ->
				// While a chord is awaited, every key is it: none reaches a field, nor moves the keyboard on.
				when {
					rebinding.awaited == null -> false
					key.type != KeyEventType.KeyDown -> true
					key.key == Key.Escape ->
						true.also {
							onChange(settings.copy(rebinding = rebinding.cancelled()))
						}
					else ->
						true.also {
							Chord.of(key)?.let { chord ->
								onChange(settings.copy(rebinding = rebinding.pressed(chord)))
							}
						}
				}
			},
	) {
		Column(
			Modifier
				.width(180.dp)
				.fillMaxHeight()
				.background(palette.pane)
				.padding(Dims.gap),
		) {
			Page.entries.forEach { page ->
				PageButton(page.label, page == settings.page) { onChange(settings.copy(page = page)) }
			}
		}
		Column(Modifier.weight(1f).fillMaxHeight().padding(Dims.gap * 2)) {
			Box(Modifier.weight(1f).fillMaxWidth()) {
				when (settings.page) {
					Page.Sidebar -> SidebarPage(settings, onChange)
					Page.Terminal -> TerminalPage(settings, onChange)
					Page.Bindings -> BindingsPage(rebinding) { onChange(settings.copy(rebinding = it)) }
					Page.Actions -> ActionsPage(settings.actions)
				}
			}
			Spacer(Modifier.height(Dims.gap))
			Row(
				Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(Dims.gap, Alignment.End),
			) {
				PushButton("Cancel", Emphasis.Secondary, enabled = true, onClick = onCancel)
				PushButton("Save", Emphasis.Primary, enabled = settings.changed, onClick = onSave)
			}
		}
	}
}

@Composable
private fun PageButton(label: String, chosen: Boolean, onClick: () -> Unit) {
	val palette = LocalPalette.current

	Text(
		label,
		style = LocalType.current.body,
		modifier =
			Modifier
				.fillMaxWidth()
				.background(
					if (chosen) palette.chosen else palette.pane,
					RoundedCornerShape(Dims.radius),
				).clickable(onClick = onClick)
				.padding(horizontal = Dims.gap, vertical = 7.dp)
				.testTag("page:$label"),
	)
}

@Composable
private fun Heading(text: String, help: String) {
	val type = LocalType.current

	Text(text, style = type.title)
	Spacer(Modifier.height(Dims.gapSmall))
	Text(help, style = type.muted)
	Spacer(Modifier.height(Dims.gap * 2))
}

/** The places pinned to every sidebar, the pickers' too: moved, unpinned, typed in and pinned. */
@Composable
private fun SidebarPage(settings: Settings, onChange: (Settings) -> Unit) {
	val type = LocalType.current
	val palette = LocalPalette.current
	var typed by remember { mutableStateOf("") }
	val folder = typed.takeIf { it.isNotBlank() }?.let { Path(it.trim()) }
	val pinnable = folder?.let { it.isAbsolute && it.isDirectory() } == true

	Column {
		Heading(
			"Pinned places",
			"What is pinned here is at the top of every window's sidebar, " +
				"the file pickers' too. A folder's menu pins it, and a pinned " +
				"place's menu unpins it.",
		)
		LazyColumn(Modifier.weight(1f, fill = false)) {
			items(settings.pins.withIndex().toList(), key = {
				it.value.toString()
			}) { (index, path) ->
				PinRow(
					path,
					settings.labelOf(path),
					index,
					settings.pins.lastIndex,
					onChange = { onChange(it(settings)) },
				)
			}
		}
		Spacer(Modifier.height(Dims.gap))
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Dims.gap),
		) {
			Field(typed, {
				typed = it
			}, Modifier.weight(1f), hint = "A folder to pin, as its whole path")
			PushButton("Pin", Emphasis.Secondary, enabled = pinnable) {
				folder?.let { onChange(settings.pinned(it)) }
				typed = ""
			}
		}
		if (folder != null && !pinnable)
			Text(
				"That is not a folder's whole path.",
				style = type.muted.copy(color = palette.textFaint),
			)
		Spacer(Modifier.height(Dims.gap))
		PushButton("Desktop's own", Emphasis.Secondary, enabled = settings.pinned != null) {
			onChange(settings.copy(pinned = null))
		}
	}
}

/**
 * The actions the menus offer beyond prism's own, and the places they show: which there are, and, of those the
 * preferences write that did not read, why not, so that one written wrong is not simply missing from a menu. They are
 * written in the preferences file, as docs/menus.md says how.
 */
@Composable
private fun ActionsPage(actions: Actions) {
	val type = LocalType.current
	val palette = LocalPalette.current

	Column(Modifier.fillMaxSize()) {
		Text(
			"Actions are what a context menu offers beyond prism's own. Places are what a " +
				"window shows that is not a folder. Both are written in the preferences " +
				"file, under “actions” and “places”.",
			style = type.muted,
		)
		Spacer(Modifier.height(Dims.gap))
		LazyColumn(Modifier.weight(1f).testTag("actions")) {
			if (actions.broken.isNotEmpty()) {
				item(key = "broken") {
					Text(
						"Not read",
						style = type.heading,
						modifier = Modifier.padding(top = Dims.gap, bottom = Dims.gap),
					)
				}
				items(actions.broken) { broken ->
					Column(
						Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("broken:${broken.id}"),
					) {
						Text(
							"The ${broken.what} ${broken.id?.let { "“$it”" } ?: "with no id"}",
							style = type.body.copy(color = palette.accent),
						)
						Text(broken.why, style = type.muted)
					}
				}
			}
			item(key = "actions") {
				Text(
					"Actions",
					style = type.heading,
					modifier = Modifier.padding(top = Dims.gap * 2, bottom = Dims.gap),
				)
			}
			items(actions.actions, key = { "action:${it.id}" }) { action ->
				Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
					Text(
						(action.submenu + action.label).joinToString(" ▸ "),
						style = type.body,
						maxLines = 1,
						modifier = Modifier.width(320.dp),
					)
					Text(
						action.id + " (hidden)".takeIf { action.id in actions.hidden }.orEmpty(),
						style = type.muted,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			item(key = "places") {
				Text(
					"Places",
					style = type.heading,
					modifier = Modifier.padding(top = Dims.gap * 2, bottom = Dims.gap),
				)
			}
			items(actions.places.values.toList(), key = { "place:${it.id}" }) { place ->
				Text(place.id, style = type.body, modifier = Modifier.padding(vertical = 3.dp))
			}
		}
	}
}

@Composable
private fun PinRow(
	path: Path,
	label: String,
	index: Int,
	last: Int,
	onChange: ((Settings) -> Settings) -> Unit,
) {
	val type = LocalType.current

	Row(
		Modifier.fillMaxWidth().padding(vertical = 3.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Dims.gap),
	) {
		Column(Modifier.weight(1f)) {
			Text(label, style = type.body, maxLines = 1)
			Text(
				path.toString(),
				style = type.muted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		PushButton("Move up", Emphasis.Secondary, enabled = index > 0) {
			onChange { it.moved(index, -1) }
		}
		PushButton("Move down", Emphasis.Secondary, enabled = index < last) {
			onChange { it.moved(index, 1) }
		}
		PushButton("Unpin", Emphasis.Secondary, enabled = true) {
			onChange { it.unpinned(index) }
		}
	}
}

/** The terminal "Open terminal here" runs. */
@Composable
private fun TerminalPage(settings: Settings, onChange: (Settings) -> Unit) {
	val type = LocalType.current

	Column {
		Heading(
			"Terminal",
			"What “Open terminal here” runs, in the folder it is for; " +
				"{folder} stands for the folder's path. Left empty, the " +
				"desktop's own is run: \$TERMINAL, xdg-terminal-exec, or the " +
				"first terminal found.",
		)
		Field(
			settings.terminal,
			{ onChange(settings.copy(terminal = it)) },
			Modifier.fillMaxWidth(),
			hint = "The desktop's own",
		)
		Spacer(Modifier.height(Dims.gap))
		Text(
			settings.terminalWords?.let { words -> "Runs ${words.joinToString(" ") { "“$it”" }}" }
				?: "Runs the desktop's own",
			style = type.muted,
		)
	}
}

/** Where each scope's actions act, as the bindings page heads them. */
private fun headingOf(scope: Scope): String =
	when (scope) {
		Scope.Window -> "Anywhere in the window"
		Scope.Listing -> "On the files, from the list"
		Scope.Movement ->
			"Moving through the list (Shift chooses as it moves, " +
				"Ctrl moves without choosing)"
		Scope.Leftover -> "Wherever the keyboard is, once it is done with the key"
	}

/** Every action, with its chords, each one clicked to bind it anew, removed, or added to. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BindingsPage(rebinding: Rebinding, onChange: (Rebinding) -> Unit) {
	val type = LocalType.current
	val palette = LocalPalette.current

	Column {
		Heading(
			"Key bindings",
			"Click a binding, then press a key (with Ctrl, Alt or Shift, if " +
				"you like) or a mouse button: the middle, back or forward. " +
				"Escape stops waiting. A chord does one thing, so one given " +
				"here is taken from whatever had it.",
		)
		rebinding.took?.let { took ->
			Text(
				"${took.chord} was taken from ${namesOf(took.from)}.",
				style = type.body.copy(color = palette.accent),
			)
			Spacer(Modifier.height(Dims.gap))
		}
		LazyColumn(Modifier.weight(1f)) {
			Scope.entries.forEach { scope ->
				item(key = scope) {
					Text(
						headingOf(scope),
						style = type.heading,
						modifier =
							Modifier.padding(
								top =
									Dims.gap * 2,
								bottom = Dims.gap,
							),
					)
				}
				items(
					rebinding.bindings.actions.filter {
						it.scope == scope
					},
					key = { it.key },
				) { action ->
					BindingRow(action, rebinding, onChange)
				}
			}
		}
		Spacer(Modifier.height(Dims.gap))
		PushButton(
			"Reset all",
			Emphasis.Secondary,
			enabled =
				rebinding.bindings != rebinding.bindings.defaults(),
		) {
			onChange(rebinding.resetAll())
		}
	}
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BindingRow(
	action: Bindable,
	rebinding: Rebinding,
	onChange: (Rebinding) -> Unit,
) {
	val type = LocalType.current
	val chords = rebinding.bindings.of(action)

	Row(
		Modifier.fillMaxWidth().padding(vertical = 3.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(action.label, style = type.body, modifier = Modifier.width(260.dp))
		FlowRow(
			Modifier.weight(1f),
			horizontalArrangement = Arrangement.spacedBy(Dims.gapSmall),
			verticalArrangement = Arrangement.spacedBy(Dims.gapSmall),
		) {
			chords.forEachIndexed { index, chord ->
				val slot = Slot(action, index)

				ChordChip(
					if (rebinding.awaited == slot)
						null
					else
						chord.toString(),
					onClick = { onChange(rebinding.awaiting(slot)) },
					onRemove = { onChange(rebinding.removed(slot)) },
				)
			}

			val added = Slot(action, chords.size)

			ChordChip(
				if (rebinding.awaited ==
					added
				) null else "+",
				onClick = { onChange(rebinding.awaiting(added)) },
			)
		}
		if (chords != action.defaults)
			Text(
				"Reset",
				style = type.body.copy(color = LocalPalette.current.accent),
				modifier =
					Modifier
						.clickable {
							onChange(rebinding.reset(action))
						}.padding(horizontal = Dims.gap),
			)
	}
}

/** A chord's chip: its [text], or, where it is null, the chord awaited; clicked to bind it, and removed by its ×. */
@Composable
private fun ChordChip(
	text: String?,
	onClick: () -> Unit,
	onRemove: (() -> Unit)? = null,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Dims.radius)
	val awaiting = text == null

	Row(
		Modifier
			.height(Dims.chip)
			.background(if (awaiting) palette.accent else palette.field, shape)
			.border(
				1.dp,
				if (awaiting)
					palette.accent
				else
					palette.fieldEdge,
				shape,
			).clickable(
				remember { MutableInteractionSource() },
				indication = null,
				onClick = onClick,
			).padding(horizontal = Dims.gap)
			.testTag("chord:${text ?: "awaited"}"),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text ?: "Press a key…",
			style = type.body.copy(color = if (awaiting) palette.onAccent else palette.text),
		)
		if (onRemove != null && !awaiting)
			Text(
				"  ×",
				style = type.muted,
				modifier = Modifier.clickable(onClick = onRemove).testTag("remove:$text"),
			)
	}
}

/** “Back” and “Forward”: actions named as the bindings page names them. */
private fun namesOf(actions: List<Bindable>): String =
	actions.joinToString(" and ") { "“${it.label}”" }
