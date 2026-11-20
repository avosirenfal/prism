package prism.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import prism.browser.Act
import prism.browser.ButtonRow
import prism.browser.Card
import prism.browser.CardItem
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Modal
import prism.browser.ModalWidth
import prism.browser.Notice
import prism.browser.PushButton
import prism.browser.Radius
import prism.browser.Section
import prism.browser.Segmented
import prism.browser.Select
import prism.browser.SettingRow
import prism.browser.Space
import prism.browser.Spinner
import prism.browser.Stepper
import prism.browser.Switch
import prism.browser.Tone
import prism.browser.sizeOf
import prism.thumbnails.Medium
import prism.thumbnails.ThumbnailSize
import prism.thumbnails.Thumbnailer
import prism.browser.Page as PageLayout

/*
 * The Thumbnails page of the preferences: where thumbnails are kept, how much is kept, how they are made, and the
 * programs that make them. What it sets is applied as every page's is, but the folder, which has a button of its own
 * and is changed when that is pressed (thumbnailing.kt).
 */

/** How wide the page's selects are. */
private val CHOICE_WIDTH = 200.dp

/**
 * The page over [edits], each change reported to [onChange]. What the running prism says of its thumbnails is
 * [facts]; a change of folder is asked through [onRelocate], and is no part of what the window applies.
 */
@Composable
internal fun ThumbnailsPage(
	edits: ThumbnailEdits,
	facts: ThumbnailFacts,
	onRelocate: (Relocate) -> Unit,
	onChange: (ThumbnailEdits) -> Unit,
) {
	PageLayout(
		"Thumbnails",
		"Small pictures of files, shown in the gallery. They are made the first time a folder is looked " +
			"at, and kept so they need not be made again.",
	) {
		FolderSection(edits, facts, onRelocate, onChange)
		LimitsSection(edits, onChange)
		MakingSection(edits, onChange)
		ProgramsSection(edits, facts, onChange)
	}
}

/** The folder thumbnails are kept in: typed, and changed to by its own button, at once. */
@Composable
private fun FolderSection(
	edits: ThumbnailEdits,
	facts: ThumbnailFacts,
	onRelocate: (Relocate) -> Unit,
	onChange: (ThumbnailEdits) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val proposal = edits.proposed(facts.default)
	val fit = (proposal as? ThumbnailEdits.Proposal.Fit)?.change
	val toDefault = edits.toDefault(facts.default)

	fun change() {
		fit?.let { onRelocate(Relocate.Proposed(it)) }
	}

	Section(
		"Cache folder",
		description =
			"Where thumbnails are kept. On Linux the default is the folder other file managers use, " +
				"so each shows the thumbnails the others have made. Changing the folder takes effect " +
				"immediately, without Apply.",
	) {
		Card {
			CardItem {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Space.s),
				) {
					Field(
						edits.folderShown(facts.default),
						{ onChange(edits.typing(it)) },
						Modifier.weight(1f).testTag("thumbnails-folder"),
						hint = facts.default.toString(),
						style = type.mono,
						onKey = { key ->
							(key.type == KeyEventType.KeyDown && key.key == Key.Enter && fit != null)
								.also {
									if (it)
										change()
								}
						},
					)
					PushButton(
						"Change",
						if (fit != null)
							Emphasis.Primary
						else
							Emphasis.Secondary,
						enabled = fit != null,
						Modifier.testTag("thumbnails-change"),
						onClick = ::change,
					)
					PushButton(
						"Use default",
						Emphasis.Secondary,
						enabled = toDefault != null,
						Modifier.testTag("thumbnails-default"),
					) { toDefault?.let { onRelocate(Relocate.Proposed(it)) } }
				}
				Text(
					when (proposal) {
						is ThumbnailEdits.Proposal.Unfit -> proposal.why
						is ThumbnailEdits.Proposal.Fit, null ->
							facts.totals?.let {
								"${"%,d".format(it.files)} thumbnails, ${sizeOf(it.bytes)}"
							} ?: "Counting thumbnails…"
					},
					style =
						if (proposal is ThumbnailEdits.Proposal.Unfit)
							type.muted.copy(color = palette.dangerText)
						else
							type.muted,
					modifier = Modifier.testTag("thumbnails-state"),
				)
			}
		}
	}
}

/** How much is kept: in bytes, and in files. */
@Composable
private fun LimitsSection(edits: ThumbnailEdits, onChange: (ThumbnailEdits) -> Unit) {
	val budget = edits.kept.budget
	val bytes = ThumbnailEdits.nearest(ThumbnailEdits.BYTES, budget.bytes)
	val files = ThumbnailEdits.nearest(ThumbnailEdits.FILES, budget.files)

	Section(
		"Limits",
		description =
			"When either limit is reached, the thumbnails used least are removed. Ones you look at " +
				"often are kept, and new ones are kept long enough to be looked at again.",
	) {
		Card {
			SettingRow("Maximum size", description = "Disk space the thumbnails may use.") {
				Stepper(
					sizeOf(ThumbnailEdits.BYTES[bytes]),
					bytes > 0,
					bytes < ThumbnailEdits.BYTES.lastIndex,
					tag = "thumbnails-bytes",
				) {
					onChange(
						edits.budgeted(
							budget.copy(
								bytes = ThumbnailEdits.BYTES[(bytes + it).coerceIn(ThumbnailEdits.BYTES.indices)],
							),
						),
					)
				}
			}
			SettingRow("Maximum number", description = "How many thumbnails may be kept.") {
				Stepper(
					"%,d".format(ThumbnailEdits.FILES[files]),
					files > 0,
					files < ThumbnailEdits.FILES.lastIndex,
					tag = "thumbnails-files",
				) {
					onChange(
						edits.budgeted(
							budget.copy(
								files = ThumbnailEdits.FILES[(files + it).coerceIn(ThumbnailEdits.FILES.indices)],
							),
						),
					)
				}
			}
		}
	}
}

/** How large thumbnails are made, and how far ahead of what is shown. */
@Composable
private fun MakingSection(edits: ThumbnailEdits, onChange: (ThumbnailEdits) -> Unit) {
	val size = edits.kept.size
	val nearby = ThumbnailEdits.nearest(ThumbnailEdits.NEARBY, edits.kept.nearby)

	Section("Thumbnails") {
		Card {
			SettingRow(
				"Resolution",
				description =
					if (size == null)
						"Automatic: as large as each thumbnail is drawn, so zooming in makes sharper ones."
					else
						"Every thumbnail is made at this size, and scaled to fit when drawn.",
			) {
				Segmented(
					listOf(false, true),
					size != null,
					{ if (it) "Custom" else "Automatic" },
					tag = "thumbnails-resolution",
				) { custom ->
					onChange(edits.sized(ThumbnailSize.Large.takeIf { custom }))
				}
				if (size != null)
					Select(
						ThumbnailSize.entries,
						size,
						{ "${it.pixels} px" },
						Modifier.width(CHOICE_WIDTH).testTag("thumbnails-size"),
					) { onChange(edits.sized(it)) }
			}
			SettingRow(
				"Make ahead",
				description =
					"Files before and after the ones in view whose thumbnails are made in advance, so " +
						"scrolling finds them ready.",
			) {
				Stepper(
					if (ThumbnailEdits.NEARBY[nearby] == 0)
						"Off"
					else
						"${ThumbnailEdits.NEARBY[nearby]} each way",
					nearby > 0,
					nearby < ThumbnailEdits.NEARBY.lastIndex,
					tag = "thumbnails-nearby",
				) {
					onChange(
						edits.ahead(
							ThumbnailEdits.NEARBY[(nearby + it).coerceIn(ThumbnailEdits.NEARBY.indices)],
						),
					)
				}
			}
		}
	}
}

/** The programs that make thumbnails: each with what it is for and what it runs, and a switch. */
@Composable
private fun ProgramsSection(
	edits: ThumbnailEdits,
	facts: ThumbnailFacts,
	onChange: (ThumbnailEdits) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	val thumbnailers =
		edits.thumbnailers(
			(facts.ffmpeg as? ThumbnailFacts.Found.Installed)?.ffmpeg,
		)

	Section(
		"Programs",
		description =
			"Images are decoded by prism itself, and photos in raw formats use the preview inside the " +
				"file. Everything else is made by these programs, tried in order. More can be added in " +
				"the preferences file, by file extension or MIME type.",
	) {
		Card {
			for (thumbnailer in thumbnailers.all)
				ProgramRow(thumbnailer, thumbnailer.id !in edits.kept.off) {
					onChange(edits.switched(thumbnailer.id, it))
				}
			when (facts.ffmpeg) {
				ThumbnailFacts.Found.Looking ->
					Notice("Looking for ffmpeg…", Tone.Plain, Modifier.testTag("ffmpeg-looking"))
				ThumbnailFacts.Found.Missing ->
					Notice(
						"ffmpeg isn't installed",
						Tone.Bad,
						Modifier.testTag("ffmpeg-missing"),
						detail =
							"Videos, and images prism can't decode itself, have no thumbnails until it is. " +
								"Install ffmpeg and restart prism.",
					)
				is ThumbnailFacts.Found.Installed -> {}
			}
			for (broken in thumbnailers.broken)
				Notice(broken, Tone.Bad, Modifier.testTag("thumbnailer-broken"))
		}
	}
}

/** One program: its name and switch, the files it is for, and its command, which is shown and not changed here. */
@Composable
private fun ProgramRow(
	thumbnailer: Thumbnailer,
	on: Boolean,
	onSwitch: (Boolean) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current

	val commands =
		when (thumbnailer) {
			is Thumbnailer.Program -> listOf(null to thumbnailer.command)
			is Thumbnailer.Ffmpeg ->
				thumbnailer.found.commands.map { (medium, command) -> labelOf(medium) to command }
		}

	CardItem(Modifier.testTag("thumbnailer:${thumbnailer.id}")) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.m),
		) {
			Column(Modifier.weight(1f)) {
				Text(thumbnailer.name, style = type.body)
				Text("For ${ThumbnailEdits.filesOf(thumbnailer)}", style = type.muted)
			}
			Switch(on, Modifier.testTag("thumbnailer-on:${thumbnailer.id}"), onChange = onSwitch)
		}
		for ((label, command) in commands)
			Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
				label?.let { Text(it, style = type.muted) }
				Text(
					textOf(command.written),
					style = type.mono,
					modifier =
						Modifier
							.fillMaxWidth()
							.background(palette.sunken, RoundedCornerShape(Radius.medium))
							.padding(horizontal = Space.m, vertical = Space.s),
				)
			}
	}
}

private fun labelOf(medium: Medium): String =
	when (medium) {
		Medium.Picture -> "Images"
		Medium.Film -> "Videos"
		Medium.Sound -> "Audio with cover art"
	}

/**
 * What is asked, or shown, about a change of folder, over the window's pages: what to do with the thumbnails in the
 * folder changed from; and, while they are moved or removed, how far that has got, with a way to stop it.
 */
@Composable
internal fun RelocatingModal(relocating: Relocating?, onRelocate: (Relocate) -> Unit) {
	val palette = LocalPalette.current
	val type = LocalType.current

	when (relocating) {
		null -> {}
		is Relocating.Asking ->
			Modal(
				"Move existing thumbnails?",
				onDismiss = { onRelocate(Relocate.Stopped) },
				width = ModalWidth.Medium,
				outside = false,
				tag = "relocating-asking",
				buttons = {
					ButtonRow(
						Act("Move", "relocate-move") { onRelocate(Relocate.Chosen(Leftover.Move)) },
						secondary =
							listOf(
								Act("Cancel", "relocate-cancel") { onRelocate(Relocate.Stopped) },
								Act("Leave", "relocate-leave") { onRelocate(Relocate.Chosen(Leftover.Leave)) },
							),
						aside =
							listOf(
								Act("Delete", "relocate-delete") { onRelocate(Relocate.Chosen(Leftover.Delete)) },
							),
					)
				},
			) {
				Text(
					"${relocating.change.from} already holds thumbnails. They can be moved to " +
						"${relocating.change.to}, deleted, or left where they are.",
					style = type.body,
				)
				Text(
					"Left where they are, they take up space until you remove them, and the new folder " +
						"starts empty. Cancel keeps the current folder.",
					style = type.muted,
				)
			}
		is Relocating.Working ->
			Modal(
				if (relocating.removing)
					"Deleting thumbnails"
				else
					"Moving thumbnails",
				onDismiss = { onRelocate(Relocate.Stopped) },
				width = ModalWidth.Medium,
				outside = false,
				tag = "relocating-working",
				leading = { Spinner(palette.accent) },
				buttons = {
					ButtonRow(
						null,
						secondary = listOf(Act("Stop", "relocate-stop") { onRelocate(Relocate.Stopped) }),
					)
				},
			) {
				val at = relocating.at

				Text(
					at?.let { "${"%,d".format(it.done + it.failed)} of ${"%,d".format(it.of)}" }
						?: "Counting…",
					style = type.body,
					modifier = Modifier.testTag("relocating-progress"),
				)
				Box(
					Modifier
						.fillMaxWidth()
						.height(BAR_HEIGHT)
						.clip(RoundedCornerShape(BAR_HEIGHT))
						.background(palette.border),
				) {
					Box(
						Modifier
							.fillMaxWidth(at?.fraction ?: 0f)
							.fillMaxHeight()
							.background(palette.accent),
					)
				}
				Text(
					if (relocating.removing)
						"Stopping leaves the rest where they are."
					else
						"Stopping keeps what has been moved in the new folder and leaves the rest where " +
							"they are.",
					style = type.muted,
				)
			}
	}
}

private val BAR_HEIGHT = 4.dp
