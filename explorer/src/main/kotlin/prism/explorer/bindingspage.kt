package prism.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import prism.browser.Act
import prism.browser.Bindable
import prism.browser.ButtonRow
import prism.browser.Card
import prism.browser.Chip
import prism.browser.Chord
import prism.browser.Divider
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.KeyGroup
import prism.browser.LazyScrollArea
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Radius
import prism.browser.Space
import prism.browser.ToolButton
import prism.browser.mouseChords
import prism.window.WindowLocals
import prism.browser.Page as PageLayout

/*
 * The page that rebinds keys, of whatever window has keys to rebind: an explorer's, a picker's, the launcher's. Its
 * actions are listed in the groups they belong to (what gets around, what acts on files, what moves the cursor), each
 * with its chords on caps; one whose chords are not as they came is marked, and can be put back.
 *
 * A cap clicked waits for a chord: whatever is pressed next, a key with what modifiers are held, or a mouse button
 * (middle, back or forward), is taken; Escape stops waiting instead. The actions shown are narrowed by words typed
 * (an action's name, its group's, or a chord's), by a chord pressed in the window that finds one, or to those that
 * are changed; a group none of whose actions is shown is not listed.
 */

/**
 * Every action of [rebinding], in its groups, each with its chords, clicked to be bound anew, removed, or added to;
 * the changes made through [onChange]. [filter] is the field Ctrl+F goes to. [above] is drawn over the list (whose
 * keys these are, where a window has more than one set); where there are no bindings to show ([rebinding] is null:
 * these keys are another set's), [empty] is drawn in the list's place.
 */
@Composable
internal fun BindingsPage(
	rebinding: Rebinding?,
	filter: FocusRequester,
	onChange: (Rebinding) -> Unit,
	empty: @Composable () -> Unit = {},
	above: @Composable ColumnScope.() -> Unit = {},
) {
	PageLayout(
		"Key bindings",
		"Click a shortcut, then press a key or mouse button. Escape cancels.",
		scrolls = false,
	) {
		above()
		if (rebinding == null)
			Box(Modifier.weight(1f).fillMaxWidth()) { empty() }
		else
			Bindings(rebinding, filter, onChange)
	}
}

/** The filter, what the last chord was taken from or why it was refused, the groups of actions, and Reset all. */
@Composable
private fun ColumnScope.Bindings(
	rebinding: Rebinding,
	filter: FocusRequester,
	onChange: (Rebinding) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val list = rememberLazyListState()
	val finding = rebinding.finding
	val seeking = rebinding.hearing as? Hearing.Seeking

	// Those shown, in their groups, in the order the actions are in: a group with none shown is not there.
	val groups =
		rebinding.bindings.actions
			.filter { finding.shows(it, rebinding.bindings.of(it)) }
			.groupBy { it.group }

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		Field(
			(finding as? Finding.Words)?.text.orEmpty(),
			{ onChange(rebinding.finding(Finding.Words(it))) },
			Modifier.weight(1f).testTag("bindings-filter"),
			hint = "Search actions, groups and keys (Ctrl+F)",
			leading = Glyph.Search,
			focus = filter,
		)
		// What the window that finds a shortcut found, while it is what is shown: let go of by its ×.
		(finding as? Finding.Shortcut)?.let { found ->
			Chip(
				found.chord.toString(),
				removeTag = "found:remove",
				onRemove = { onChange(rebinding.finding(Finding.ALL)) },
			) { onChange(rebinding.seeking()) }
		}
		// How many are not as they came, where any is: clicked, only those are shown; clicked again, all.
		if (rebinding.changed > 0 || finding == Finding.Changed)
			Chip(
				"${rebinding.changed} changed",
				Modifier.testTag("bindings-changed"),
				strong = finding == Finding.Changed,
			) {
				onChange(
					rebinding.finding(
						if (finding == Finding.Changed)
							Finding.ALL
						else
							Finding.Changed,
					),
				)
			}
		Box {
			ToolButton(
				Glyph.FindKey,
				"Find by shortcut",
				enabled = true,
				Modifier.testTag("find-by-key"),
				selected = seeking != null || finding is Finding.Shortcut,
			) {
				// Opened afresh, every action is shown until a shortcut is pressed; pressed again, it closes.
				onChange(
					if (seeking != null)
						rebinding.finding(Finding.ALL)
					else
						rebinding.seeking(),
				)
			}
			if (seeking != null)
				FindShortcut(seeking.found, onPressed = { onChange(rebinding.pressed(it)) }) {
					onChange(rebinding.cancelled())
				}
		}
	}
	rebinding.took?.let { took ->
		Text(
			"${took.chord} was removed from ${namesOf(took.from)}.",
			style = type.body.copy(color = palette.accentText),
			modifier = Modifier.testTag("bindings-took"),
		)
	}
	rebinding.refused?.let { chord ->
		Text(
			"$chord types text, so it needs Ctrl or Alt.",
			style = type.body.copy(color = palette.dangerText),
			modifier = Modifier.testTag("bindings-refused"),
		)
	}
	Card(Modifier.weight(1f)) {
		LazyScrollArea(
			Modifier.fillMaxSize(),
			state = list,
			padding = PaddingValues(horizontal = Space.xs, vertical = Space.xs),
			tag = "bindings",
			scrollbarTag = "bindings-scrollbar",
		) {
			groups.entries.forEachIndexed { at, (group, actions) ->
				item(key = "group:${group.label}") { GroupHeading(group, first = at == 0) }
				items(actions, key = { it.key }) { action ->
					BindingRow(action, rebinding, onChange)
				}
			}
			if (groups.isEmpty())
				item(key = "none") {
					Text(
						when (finding) {
							is Finding.Shortcut -> "${finding.chord} isn't assigned to anything."
							Finding.Changed -> "No shortcuts have been changed."
							is Finding.Words -> null
						} ?: "No matching actions.",
						style = type.muted,
						modifier = Modifier.padding(Space.m),
					)
				}
		}
	}
	ButtonRow(
		null,
		aside =
			listOf(
				Act("Reset all", "reset-all", rebinding.changed > 0) {
					onChange(rebinding.resetAll())
				},
			),
	)
}

/** A group's name over its actions, with what is true of them all under it, and a rule over every group but the first. */
@Composable
private fun GroupHeading(group: KeyGroup, first: Boolean) {
	val type = LocalType.current

	Column(Modifier.fillMaxWidth().testTag("key-group:${group.label}")) {
		if (!first) {
			Spacer(Modifier.height(Space.s))
			Divider()
		}
		Text(
			group.label,
			style = type.strong,
			maxLines = 1,
			modifier = Modifier.padding(start = Space.s, end = Space.s, top = Space.m),
		)
		group.note?.let {
			Text(it, style = type.muted, modifier = Modifier.padding(horizontal = Space.s))
		}
		Spacer(Modifier.height(Space.xs))
	}
}

/**
 * The window that finds a shortcut, as JetBrains' "Find by shortcut": below its button, over the page, taking the keys
 * while it is open. The shortcut pressed ([chord], none yet where null) is shown, and the bindings with it are what the
 * page shows, as it is pressed; another pressed takes its place. A mouse's middle, back or forward button pressed over
 * its lower part is a shortcut too. Escape, or a click elsewhere, closes it ([onClose]), what it found still shown.
 */
@Composable
private fun FindShortcut(chord: Chord?, onPressed: (Chord) -> Unit, onClose: () -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val keys = remember { FocusRequester() }
	val shape = RoundedCornerShape(Radius.large)

	Popup(
		alignment = Alignment.TopEnd,
		offset = IntOffset(0, 44),
		onDismissRequest = onClose,
		properties = PopupProperties(focusable = true),
	) {
		WindowLocals {
			Column(
				Modifier
					.width(360.dp)
					.shadow(16.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
					.background(palette.overlay, shape)
					.border(1.dp, palette.border, shape)
					.testTag("find-shortcut"),
			) {
				Column(
					Modifier.padding(Space.l),
					verticalArrangement = Arrangement.spacedBy(Space.m),
				) {
					Text(
						"Find by shortcut",
						style = type.title,
						textAlign = TextAlign.Center,
						modifier = Modifier.fillMaxWidth(),
					)
					Row(
						Modifier
							.fillMaxWidth()
							.height(type.control)
							.background(palette.field, RoundedCornerShape(Radius.medium))
							.border(2.dp, palette.focus, RoundedCornerShape(Radius.medium))
							.focusRequester(keys)
							.focusable()
							.onPreviewKeyEvent { key ->
								when {
									key.type != KeyEventType.KeyDown -> true
									key.key == Key.Escape -> true.also { onClose() }
									else -> true.also { Chord.of(key)?.let(onPressed) }
								}
							}.testTag("shortcut-field")
							.padding(horizontal = Space.m),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.s),
					) {
						GlyphIcon(Glyph.Keyboard, palette.textMuted, type.icon * 0.9f)
						Text(
							chord?.toString() ?: "Press a shortcut",
							style =
								if (chord ==
									null
								)
									type.body.copy(color = palette.textFaint)
								else
									type.strong,
							maxLines = 1,
						)
					}
				}
				Divider()
				Row(
					Modifier
						.fillMaxWidth()
						.background(
							palette.sunken,
							RoundedCornerShape(bottomStart = Radius.large, bottomEnd = Radius.large),
						).mouseChords(onPressed)
						.padding(horizontal = Space.l, vertical = Space.m)
						.testTag("mouse-shortcut"),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						"For a mouse shortcut, press the middle, back or forward button here.",
						style = type.muted,
					)
				}
			}
			LaunchedEffect(Unit) { keys.requestFocus() }
		}
	}
}

/**
 * One action: a dot where its chords are not as they came, its name, where it acts (where that is not everywhere),
 * its chords on caps, and, where they are changed, what puts them back. The row is tinted under the pointer, to be
 * read across.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BindingRow(
	action: Bindable,
	rebinding: Rebinding,
	onChange: (Rebinding) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val chords = rebinding.bindings.of(action)
	val changed = chords != action.defaults
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()

	Row(
		Modifier
			.fillMaxWidth()
			.background(
				if (hovered) palette.hover else Color.Transparent,
				RoundedCornerShape(Radius.medium),
			).hoverable(hover)
			.padding(horizontal = Space.s, vertical = Space.xs)
			.testTag("binding:${action.key}"),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.m),
	) {
		Row(
			Modifier.weight(0.45f),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
		) {
			// The dot's room is kept where there is none, so every name starts at the same place.
			Box(
				Modifier
					.size(6.dp)
					.background(
						if (changed)
							palette.accent
						else
							Color.Transparent,
						CircleShape,
					).then(
						if (changed)
							Modifier.testTag("binding-changed:${action.key}")
						else
							Modifier,
					),
			)
			Column(Modifier.weight(1f)) {
				Text(action.label, style = type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
				action.where?.let {
					Text(it, style = type.muted.copy(color = palette.textFaint), maxLines = 1)
				}
			}
		}
		FlowRow(
			Modifier.weight(0.55f),
			horizontalArrangement = Arrangement.spacedBy(Space.xs),
			verticalArrangement = Arrangement.spacedBy(Space.xs),
		) {
			chords.forEach { chord ->
				val slot = Slot.Bound(action, chord)
				val awaited = rebinding.awaited == slot

				Chip(
					if (awaited) "Press a key…" else chord.toString(),
					Modifier.testTag("chord:${if (awaited) "awaited" else chord.toString()}"),
					strong = awaited,
					removeTag = "remove:$chord",
					onRemove = { onChange(rebinding.removed(slot)) }.takeIf { !awaited },
				) { onChange(rebinding.awaiting(slot)) }
			}

			val added = Slot.New(action)
			val adding = rebinding.awaited == added

			Chip(
				if (adding) "Press a key…" else "+",
				Modifier.testTag(if (adding) "chord:awaited" else "chord:+"),
				strong = adding,
			) { onChange(rebinding.awaiting(added)) }
		}
		// Its room is kept where there is nothing to reset, so the chords of every row start at the same place.
		Text(
			"Reset",
			style =
				type.body.copy(
					color =
						if (changed)
							palette.accentText
						else
							Color.Transparent,
				),
			modifier =
				Modifier
					.then(
						if (changed)
							Modifier
								.clickable { onChange(rebinding.reset(action)) }
								.testTag("reset:${action.key}")
						else
							Modifier,
					).padding(horizontal = Space.xs),
		)
	}
}

/** “Back” and “Forward”: actions named as the bindings page names them. */
private fun namesOf(actions: List<Bindable>): String =
	actions.joinToString(" and ") { "“${it.label}”" }
