package prism.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import prism.browser.Chord
import prism.browser.Divider
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.PushButton
import prism.browser.Radius
import prism.browser.Space
import prism.browser.Stepper
import prism.browser.Switch
import prism.desktop.Application
import prism.preferences.LauncherPreferences
import prism.preferences.Preferences
import prism.preferences.Scales

/*
 * The launcher's settings, in a window of their own: how it looks, what it finds, how it behaves, its keys, and which
 * of the applications installed it lists. Each change is made to the preferences as it is made here, and every
 * launcher shown after (and the one shown now) is as they then say; nothing waits to be applied. A key is bound by
 * clicking the chord to replace (or the + after an action's chords) and pressing the keys.
 */

/** A chord being waited for: which of [action]'s it will be. */
private data class Awaited(
	val action: LauncherAction,
	val index: Int,
)

/**
 * The launcher's settings as [preferences] have them, each change made through [onChange]; [applications] are those
 * installed, to be listed or left out. [onClose] is its Close button.
 */
@Composable
fun LauncherSettingsView(
	preferences: Preferences,
	applications: List<Application>,
	onChange: ((Preferences) -> Preferences) -> Unit,
	onClose: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val set = preferences.launcher.bounded
	val bindings = remember(preferences.bindings) { LauncherBindings.of(preferences) }
	var awaited by remember { mutableStateOf<Awaited?>(null) }
	var refused by remember { mutableStateOf<String?>(null) }

	fun changed(change: LauncherPreferences.() -> LauncherPreferences) =
		onChange {
			it.copy(
				launcher =
					it.launcher.bounded
						.change()
						.bounded,
			)
		}

	/** A key pressed while a chord is waited for: Escape leaves it as it was; a chord no field types is bound. */
	fun pressed(key: KeyEvent): Boolean {
		val waiting = awaited ?: return false

		if (key.type != KeyEventType.KeyDown)
			return true

		val chord = Chord.of(key) ?: return true

		val types =
			!key.isCtrlPressed && !key.isAltPressed && !key.isMetaPressed &&
				key.utf16CodePoint > ' '.code && key.utf16CodePoint != 0x7F &&
				key.utf16CodePoint != 0xFFFF

		when {
			key.key == Key.Escape && chord.toString() == "Escape" &&
				waiting.action != LauncherAction.Dismiss ->
				awaited = null
			types -> refused = "$chord types into the field: hold Ctrl or Alt with it."
			else -> {
				onChange { LauncherBindings.bound(it, waiting.action, waiting.index, chord) }
				awaited = null
				refused = null
			}
		}

		return true
	}

	Column(
		Modifier
			.fillMaxSize()
			.background(palette.canvas)
			.onPreviewKeyEvent(::pressed)
			.testTag("launcher-settings"),
	) {
		Column(
			Modifier
				.weight(1f)
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = Space.xl, vertical = Space.l),
			verticalArrangement = Arrangement.spacedBy(Space.s),
		) {
			Text(
				"Shown by “prism launcher”: bind that to a key in your window manager. " +
					"Changes here are kept as they are made.",
				style = type.muted,
			)

			Section("Appearance")
			Setting("Width", "How wide the launcher is.") {
				Stepper(
					"${set.width}",
					set.width > LauncherPreferences.WIDTHS.first,
					set.width < LauncherPreferences.WIDTHS.last,
					tag = "launcher-width",
				) { step -> changed { copy(width = width + 40 * step) } }
			}
			Setting("Results in sight", "How many rows it shows before it scrolls.") {
				Stepper(
					"${set.rows}",
					set.rows > LauncherPreferences.ROWS.first,
					set.rows < LauncherPreferences.ROWS.last,
					tag = "launcher-rows",
				) { step -> changed { copy(rows = rows + step) } }
			}
			Setting("Size", "How large its text and pictures are drawn.") {
				Stepper(
					"${(set.scale * 100).roundToInt()}%",
					set.scale > Scales.SMALLEST,
					set.scale < Scales.LARGEST,
					tag = "launcher-scale",
				) { step -> changed { copy(scale = ((scale * 10).roundToInt() + step) / 10f) } }
			}

			Section("What it finds")
			Setting(
				"Files and folders",
				"After the applications, what the index holds that fits what is typed, " +
					"in the search's own language.",
			) {
				Switch(set.files, Modifier.testTag("launcher-files")) { on ->
					changed {
						copy(files = on)
					}
				}
			}
			Setting("Most files listed", "How many of the files found are shown.") {
				Stepper(
					"${set.fileResults}",
					set.files && set.fileResults > LauncherPreferences.FILE_RESULTS.first,
					set.files && set.fileResults < LauncherPreferences.FILE_RESULTS.last,
					tag = "launcher-file-results",
				) { step -> changed { copy(fileResults = fileResults + 5 * step) } }
			}
			Setting(
				"Application actions",
				"What else an application can be started to do: a browser's new private window.",
			) {
				Switch(set.actions, Modifier.testTag("launcher-actions")) { on ->
					changed { copy(actions = on) }
				}
			}

			Section("Behaviour")
			Setting(
				"Close when it loses the keyboard",
				"Put away when another window is clicked or switched to.",
			) {
				Switch(set.closeUnfocused, Modifier.testTag("launcher-close-unfocused")) { on ->
					changed { copy(closeUnfocused = on) }
				}
			}

			Section("Keys")
			refused?.let {
				Text(
					it,
					style = type.muted.copy(color = palette.dangerText),
					modifier = Modifier.testTag("launcher-key-refused"),
				)
			}
			for (action in LauncherAction.entries)
				KeyRow(
					action,
					bindings.of(action),
					awaited?.takeIf { it.action == action }?.index,
					onAwait = {
						awaited = Awaited(action, it)
						refused = null
					},
					onRemove = { index -> onChange { LauncherBindings.unbound(it, action, index) } },
					onReset = { onChange { LauncherBindings.reset(it, action) } },
				)

			Section("Applications")
			Applications(applications, set.hidden) { id, shown ->
				changed {
					copy(
						hidden =
							if (shown)
								hidden - id
							else
								hidden + id,
					)
				}
			}
		}
		Divider()
		Row(
			Modifier.fillMaxWidth().background(palette.pane).padding(Space.m),
			horizontalArrangement = Arrangement.End,
		) {
			PushButton(
				"Close",
				Emphasis.Secondary,
				true,
				Modifier.testTag("launcher-settings-close"),
				onClick = onClose,
			)
		}
	}
}

/** A section's title, with room above it. */
@Composable
private fun Section(title: String) {
	Spacer(Modifier.height(Space.m))
	Text(title, style = LocalType.current.title)
	Divider()
}

/** One setting: what it is, what it does, and at the row's end what changes it. */
@Composable
private fun Setting(label: String, detail: String, control: @Composable () -> Unit) {
	val type = LocalType.current

	Row(
		Modifier.fillMaxWidth().padding(vertical = Space.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(Modifier.weight(1f)) {
			Text(label, style = type.body)
			Text(detail, style = type.muted)
		}
		Spacer(Modifier.width(Space.l))
		control()
	}
}

/**
 * One action's keys: each chord on a cap, clicked to be replaced by the next chord pressed ([awaiting] is the one
 * waited for), with an × that takes it away; a + that adds one; and, where they are not its defaults, what puts those
 * back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyRow(
	action: LauncherAction,
	chords: List<Chord>,
	awaiting: Int?,
	onAwait: (Int) -> Unit,
	onRemove: (Int) -> Unit,
	onReset: () -> Unit,
) {
	val type = LocalType.current

	Row(
		Modifier.fillMaxWidth().padding(vertical = Space.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(action.label, style = type.body, modifier = Modifier.width(240.dp))
		FlowRow(
			Modifier.weight(1f),
			horizontalArrangement = Arrangement.spacedBy(Space.s),
			verticalArrangement = Arrangement.spacedBy(Space.xs),
		) {
			chords.forEachIndexed { at, chord ->
				Cap(
					if (awaiting == at)
						"Press keys…"
					else
						chord.toString(),
					"launcher-chord:${action.name}:$at",
					waiting = awaiting == at,
					onClick = { onAwait(at) },
					onRemove = { onRemove(at) },
				)
			}
			Cap(
				if (awaiting == chords.size)
					"Press keys…"
				else
					"+",
				"launcher-chord:${action.name}:+",
				waiting = awaiting == chords.size,
				onClick = { onAwait(chords.size) },
				onRemove = null,
			)
		}
		if (chords != action.defaults)
			PushButton(
				"Reset",
				Emphasis.Quiet,
				true,
				Modifier.testTag("launcher-chord-reset:${action.name}"),
				onClick = onReset,
			)
	}
}

/** A chord on a cap: clicked to be changed, with an × beside it where it can be taken away. */
@Composable
private fun Cap(
	text: String,
	tag: String,
	waiting: Boolean,
	onClick: () -> Unit,
	onRemove: (() -> Unit)?,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.medium)

	Row(
		Modifier
			.clip(shape)
			.background(if (waiting) palette.chosen else palette.sunken)
			.border(1.dp, if (waiting) palette.accent else palette.border, shape),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text,
			style = type.muted.copy(color = palette.text),
			maxLines = 1,
			modifier =
				Modifier
					.clickable(onClick = onClick)
					.padding(horizontal = Space.s, vertical = Space.xs)
					.testTag(tag),
		)
		onRemove?.let {
			Box(
				Modifier.clickable(onClick = it).padding(Space.xs).testTag("$tag:remove"),
				contentAlignment = Alignment.Center,
			) { GlyphIcon(Glyph.Close, palette.textMuted, 12.dp) }
		}
	}
}

/**
 * The applications installed, each with whether the launcher lists it; those whose names hold what is typed over
 * them, where anything is.
 */
@Composable
private fun Applications(
	applications: List<Application>,
	hidden: Set<String>,
	onShown: (String, Boolean) -> Unit,
) {
	val type = LocalType.current
	var filter by remember { mutableStateOf("") }

	val listed =
		remember(applications, filter) {
			applications.filter { it.name.contains(filter.trim(), ignoreCase = true) }
		}

	Text(
		"Those switched off are left out of the launcher; they are still installed, and still open files.",
		style = type.muted,
	)
	Field(
		filter,
		{ filter = it },
		Modifier.fillMaxWidth().testTag("launcher-applications-filter"),
		hint = "Find an application",
		leading = Glyph.Search,
	)
	for (application in listed)
		Row(
			Modifier.fillMaxWidth().padding(vertical = Space.xxs),
			verticalAlignment = Alignment.CenterVertically,
		) {
			SmallIcon(application)
			Spacer(Modifier.width(Space.m))
			Column(Modifier.weight(1f)) {
				Text(
					application.name,
					style = type.body,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					application.comment ?: application.generic ?: application.id,
					style = type.muted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Spacer(Modifier.width(Space.l))
			Switch(
				application.id !in hidden,
				Modifier.testTag("launcher-application:${application.id}"),
			) { onShown(application.id, it) }
		}
	if (listed.isEmpty())
		Text("No application's name holds “${filter.trim()}”.", style = type.muted)
}

/** An application's icon, small, where the icon theme has it; room for one, where it has not. */
@Composable
private fun SmallIcon(application: Application) {
	val images = LocalIconImages.current
	val size = 24.dp
	val pixels = with(LocalDensity.current) { size.toPx().roundToInt() }
	val name = application.icon

	val image by produceState(name?.let { images.kept(it, pixels) }, name, pixels) {
		value = name?.let { images.of(it, pixels) }
	}

	Box(Modifier.size(size)) {
		image?.let { Image(it, null, Modifier.size(size)) }
	}
}
