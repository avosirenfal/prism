package prism.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import prism.browser.Act
import prism.browser.ButtonRow
import prism.browser.CheckBox
import prism.browser.Commands
import prism.browser.Count
import prism.browser.Divider
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.HitRead
import prism.browser.Labelled
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.Modal
import prism.browser.ModalWidth
import prism.browser.Notice
import prism.browser.On
import prism.browser.Positions
import prism.browser.PushButton
import prism.browser.QueryUse
import prism.browser.Radius
import prism.browser.ScrollArea
import prism.browser.Segmented
import prism.browser.Select
import prism.browser.Space
import prism.browser.Spinner
import prism.browser.Takes
import prism.browser.TextEnding
import prism.browser.ThingKind
import prism.browser.Tone
import prism.browser.ToolButton
import prism.browser.World
import prism.browser.felt
import prism.browser.ring
import prism.preferences.Declared
import prism.preferences.WhenSpec

/*
 * The windows that make and change an action and a text search, over the preferences' window. Each is a form on the
 * left and, on the right, what the form comes to, as it is typed: when the thing is offered, in a person's words;
 * the command it would run for an example of what it is for, filled in; and what is wrong with it, where anything
 * is. Test opens a window over it that runs it for real on files the user names, and shows everything it printed.
 *
 * So what is asked of someone writing a command is answered where they write it: what will be run (the example),
 * what the command is given and must give back (said beside it, with a line of each form of output), where it will
 * be offered (the sentences), and whether it works (the trial).
 */

// ---- parts of a form -------------------------------------------------------------------------------------------------

/** A part of a form: its [title] over what it holds. */
@Composable
private fun FormSection(
	title: String,
	help: String? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	val type = LocalType.current

	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
		Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
			Text(title, style = type.title)
			Divider()
			help?.let { Text(it, style = type.muted) }
		}
		content()
	}
}

/** Several lines to type into, in a field's look: as many lines tall as it holds, [lines] at least. */
@Suppress("DEPRECATION") // The window feeds value-based fields.
@Composable
internal fun TextArea(
	text: String,
	onText: (String) -> Unit,
	modifier: Modifier = Modifier,
	hint: String = "",
	lines: Int = 2,
	style: TextStyle = LocalType.current.mono,
) {
	val palette = LocalPalette.current
	val shape = RoundedCornerShape(Radius.medium)
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()

	Box(
		modifier
			.ring(felt.active, palette.focus, outset = 0.dp, width = 2.dp)
			.background(palette.field, shape)
			.border(
				1.dp,
				if (felt.hovered)
					palette.fieldEdgeHover
				else
					palette.fieldEdge,
				shape,
			).padding(horizontal = Space.m, vertical = Space.s),
	) {
		if (text.isEmpty() && hint.isNotEmpty())
			Text(hint, style = style.copy(color = palette.textFaint))
		BasicTextField(
			text,
			onText,
			Modifier.fillMaxWidth(),
			textStyle = style.copy(color = palette.text),
			minLines = lines,
			maxLines = 14,
			cursorBrush = SolidColor(palette.accent),
			interactionSource = source,
		)
	}
}

/** Fields side by side, each with its label, sharing the row's width as [weights] say. */
@Composable
private fun Fields(vararg fields: Pair<Float, @Composable () -> Unit>) {
	Row(
		Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(Space.m),
		verticalAlignment = Alignment.Top,
	) {
		fields.forEach { (weight, field) -> Box(Modifier.weight(weight)) { field() } }
	}
}

/** A line to type into under its [label]. */
@Composable
private fun Typed(
	label: String,
	text: String,
	tag: String,
	hint: String = "",
	help: String? = null,
	readOnly: Boolean = false,
	mono: Boolean = false,
	onText: (String) -> Unit,
) {
	Labelled(label, help = help) {
		Field(
			text,
			onText,
			Modifier.fillMaxWidth().testTag(tag),
			hint = hint,
			readOnly = readOnly,
			style =
				if (mono)
					LocalType.current.mono
				else
					LocalType.current.body,
			selecting = {
				androidx.compose.ui.text
					.TextRange(it.length)
			},
		)
	}
}

/**
 * A command, edited as a line or as its words apart: the line typed as a shell would be given it; the words each in a
 * field of its own, added to and taken from, for a command with a script in it.
 */
@Composable
private fun CommandEditor(
	command: CommandForm,
	tag: String,
	hint: String,
	onChange: (CommandForm) -> Unit,
) {
	val type = LocalType.current

	Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
		when (command) {
			is CommandForm.Line ->
				TextArea(
					command.text,
					{ onChange(CommandForm.Line(it)) },
					Modifier.fillMaxWidth().testTag(tag),
					hint = hint,
				)
			is CommandForm.Words ->
				command.words.forEachIndexed { at, word ->
					Row(
						verticalAlignment = Alignment.Top,
						horizontalArrangement = Arrangement.spacedBy(Space.s),
					) {
						Text(
							"${at + 1}",
							style = type.muted,
							modifier = Modifier.width(Space.l).padding(top = Space.s),
						)
						TextArea(
							word,
							{ typed ->
								onChange(
									CommandForm.Words(
										command.words.mapIndexed { index, was ->
											if (index == at)
												typed
											else
												was
										},
									),
								)
							},
							Modifier.weight(1f).testTag("$tag:$at"),
							lines = 1,
						)
						ToolButton(
							Glyph.Close,
							"Remove this argument",
							enabled = command.words.size > 1,
							Modifier.testTag("$tag-remove:$at"),
						) {
							onChange(
								CommandForm.Words(
									command.words.filterIndexed {
										index,
										_,
										->
										index != at
									},
								),
							)
						}
					}
				}
		}
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
		) {
			if (command is CommandForm.Words)
				PushButton(
					"Add argument",
					Emphasis.Quiet,
					enabled = true,
					Modifier.testTag("$tag-add"),
					glyph = Glyph.Plus,
				) { onChange(CommandForm.Words(command.words + "")) }
			Text(
				if (command is CommandForm.Words)
					"Each box is one argument, passed as written."
				else
					"Split at spaces; quote what has spaces in it. No shell runs it.",
				style = type.muted,
				maxLines = 2,
				modifier = Modifier.weight(1f),
			)
			Segmented(
				listOf(false, true),
				command is CommandForm.Words,
				{ apart ->
					if (apart)
						"Arguments"
					else
						"One line"
				},
				tag = "$tag-as",
			) { apart ->
				onChange(
					if (apart)
						command.apart()
					else
						command.line(),
				)
			}
		}
	}
}

/**
 * The ways something is run, one after another: each the programs it needs and its command. The first whose programs
 * are installed is the one used; one way that needs none said is a command and no more. For a text search's
 * ([reads]), a way may say how its own output is read.
 */
@Composable
private fun WaysEditor(
	ways: List<WayForm>,
	tag: String,
	hint: String,
	reads: Boolean = false,
	least: Int = 1,
	another: String = "Add a fallback command",
	onChange: (List<WayForm>) -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.medium)

	fun changed(at: Int, way: WayForm) =
		onChange(ways.mapIndexed { index, was -> if (index == at) way else was })

	Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
		ways.forEachIndexed { at, way ->
			val several = ways.size > 1 || way.programs.isNotBlank()

			Column(
				if (several)
					Modifier
						.fillMaxWidth()
						.border(1.dp, palette.border, shape)
						.padding(Space.m)
				else
					Modifier.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(Space.s),
			) {
				if (several)
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.s),
					) {
						Text(
							when {
								ways.size == 1 -> "Needs"
								at == 0 -> "First choice, needs"
								else -> "Otherwise, needs"
							},
							style = type.strong,
						)
						Field(
							way.programs,
							{ changed(at, way.copy(programs = it)) },
							Modifier.weight(1f).testTag("$tag-programs:$at"),
							hint = "Programs: unzip, 7zz|7z",
							style = type.mono,
						)
						if (ways.size > least)
							ToolButton(
								Glyph.Trash,
								"Remove this command",
								enabled = true,
								Modifier.testTag("$tag-way-remove:$at"),
							) { onChange(ways.filterIndexed { index, _ -> index != at }) }
					}
				CommandEditor(
					way.command,
					if (ways.size == 1)
						tag
					else
						"$tag:$at",
					hint,
				) { changed(at, way.copy(command = it)) }
				if (reads && ways.size > 1)
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.s),
					) {
						Text("Its output is read as", style = type.muted)
						Select(
							listOf<HitRead?>(null) + HitRead.entries,
							way.read,
							{ it?.word ?: "the search says" },
							Modifier.weight(1f).testTag("$tag-read:$at"),
						) { changed(at, way.copy(read = it)) }
						Select(
							listOf(null, true, false),
							way.nul,
							{
								when (it) {
									null -> "paths as the search says"
									true -> "a NUL ends each path"
									false -> "no NUL after paths"
								}
							},
							Modifier.weight(1f).testTag("$tag-nul:$at"),
						) { changed(at, way.copy(nul = it)) }
					}
			}
		}
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s),
		) {
			PushButton(
				another,
				Emphasis.Quiet,
				enabled = true,
				Modifier.testTag("$tag-way-add"),
				glyph = Glyph.Plus,
			) {
				onChange(
					// The first is said to need something once there is another to fall back to.
					ways + WayForm(),
				)
			}
			if (ways.size > 1)
				Text(
					"The first whose programs are installed is the one that runs.",
					style = type.muted,
					modifier = Modifier.weight(1f),
				)
		}
	}
}

/**
 * What a command can name, each with what it becomes: folded away under a button until it is asked for, since it is
 * looked up and not read each time.
 */
@Composable
private fun Legend(names: List<Pair<String, String>>, note: String? = null) {
	val palette = LocalPalette.current
	val type = LocalType.current
	var open by remember { mutableStateOf(false) }

	Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
		PushButton(
			if (open)
				"Hide placeholders"
			else
				"Show placeholders",
			Emphasis.Quiet,
			enabled = true,
			Modifier.testTag("legend-toggle"),
			glyph =
				if (open)
					Glyph.ChevronDown
				else
					Glyph.Chevron,
		) { open = !open }
		if (open)
			Column(
				Modifier
					.fillMaxWidth()
					.background(palette.sunken, RoundedCornerShape(Radius.medium))
					.padding(Space.m)
					.testTag("legend"),
				verticalArrangement = Arrangement.spacedBy(Space.xs),
			) {
				names.forEach { (name, meaning) ->
					Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
						Text(name, style = type.mono, modifier = Modifier.width(150.dp))
						Text(meaning, style = type.muted, modifier = Modifier.weight(1f))
					}
				}
				note?.let { Text(it, style = type.muted) }
			}
	}
}

/**
 * A list a form adds to (questions, options): under its [label] and what it is for once it holds [any], and until
 * then only the button that adds the first, so that what is not used takes a line and no more.
 */
@Composable
private fun Listing(
	label: String,
	help: String,
	any: Boolean,
	content: @Composable ColumnScope.() -> Unit,
) {
	if (any)
		Labelled(label, help = help, content = content)
	else
		Column(content = content)
}

/** A choice of a few, where none is one of them. */
@Composable
private fun <T> Choice(
	label: String,
	options: List<T>,
	chosen: T,
	tag: String,
	name: (T) -> String,
	onChoose: (T) -> Unit,
) {
	Labelled(label) { Segmented(options, chosen, name, tag = tag, onChoose = onChoose) }
}

/** When something is offered: which menu, which windows, how many of what, and what they are named. */
@Composable
private fun ConditionsEditor(
	form: WhenForm,
	scenes: Scenes,
	search: Boolean,
	onChange: (WhenForm) -> Unit,
) {
	var more by remember { mutableStateOf(form.more) }

	Choice(
		"In the menu of",
		(if (search) listOf<On?>(null) else emptyList()) + On.entries,
		form.target,
		"when-target",
		{
			when (it) {
				null -> "Any"
				On.Files -> "Selected files"
				On.Folder -> "The folder itself"
				On.Place -> "A sidebar place"
			}
		},
	) { onChange(form.copy(target = it)) }
	Labelled("In windows showing") {
		ScenesPicker(form.within, scenes) { onChange(form.copy(within = it)) }
	}
	Fields(
		1f to {
			Choice(
				"How many",
				listOf(null) + Count.entries,
				form.count,
				"when-count",
				{
					when (it) {
						null -> "Any"
						Count.One -> "One"
						Count.Some -> "1 or more"
						Count.Many -> "2 or more"
					}
				},
			) { onChange(form.copy(count = it)) }
		},
		1f to {
			Choice(
				"Of what",
				listOf(null) + ThingKind.entries,
				form.kind,
				"when-kind",
				{
					when (it) {
						null -> "Anything"
						ThingKind.File -> "Files"
						ThingKind.Folder -> "Folders"
						ThingKind.Link -> "Links"
					}
				},
			) { onChange(form.copy(kind = it)) }
		},
	)
	Typed(
		"Extensions",
		form.ext,
		"when-ext",
		hint = "zip, tar.gz (without the dot; empty for any name)",
	) { onChange(form.copy(ext = it)) }
	Fields(
		1f to {
			Typed("Names", form.names, "when-names", hint = "Makefile, *.min.js") {
				onChange(form.copy(names = it))
			}
		},
		1f to {
			Typed("Types", form.types, "when-types", hint = "image/*, application/pdf") {
				onChange(form.copy(types = it))
			}
		},
	)
	if (!more) {
		PushButton(
			"More conditions",
			Emphasis.Quiet,
			enabled = true,
			Modifier.testTag("when-more"),
			glyph = Glyph.Plus,
		) { more = true }
	} else {
		Fields(
			1f to {
				Typed("But not extensions", form.notExt, "when-not-ext", hint = "tar.gz") {
					onChange(form.copy(notExt = it))
				}
			},
			1f to {
				Typed(
					"Only if installed",
					form.programs,
					"when-programs",
					hint = "git, magick|convert",
				) {
					onChange(form.copy(programs = it))
				}
			},
		)
		Fields(
			1f to {
				Typed("Under folders", form.under, "when-under", hint = "~/Projects") {
					onChange(form.copy(under = it))
				}
			},
			1f to {
				Typed("Inside a folder that has", form.inside, "when-inside", hint = ".git") {
					onChange(form.copy(inside = it))
				}
			},
		)
		Fields(
			1f to {
				Choice(
					"What they are",
					listOf(null, true, false),
					form.real,
					"when-real",
					{
						when (it) {
							null -> "Either"
							true -> "Files on disk"
							false -> "Place entries"
						}
					},
				) { onChange(form.copy(real = it)) }
			},
			1f to {
				Choice(
					"Shown outside their folder",
					listOf(null, true, false),
					form.elsewhere,
					"when-elsewhere",
					{
						when (it) {
							null -> "Either"
							true -> "Yes"
							false -> "No"
						}
					},
				) { onChange(form.copy(elsewhere = it)) }
			},
		)
	}
}

// ---- what a form comes to --------------------------------------------------------------------------------------------

/** A box of the panel beside a form: its [title] over what it says. */
@Composable
private fun Panel(
	title: String,
	tag: String,
	content: @Composable ColumnScope.() -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.large)

	Column(
		Modifier
			.fillMaxWidth()
			.background(palette.raised, shape)
			.border(1.dp, palette.border, shape)
			.padding(Space.l)
			.testTag(tag),
		verticalArrangement = Arrangement.spacedBy(Space.s),
	) {
		Text(title, style = type.heading)
		content()
	}
}

/** Text a program printed, or a command as it is run: fixed-width, selectable, in a box that scrolls where it is long. */
@Composable
private fun Printed(text: String, tag: String, bad: Boolean = false) {
	val palette = LocalPalette.current
	val type = LocalType.current

	SelectionContainer {
		Text(
			text.trimEnd('\n'),
			style =
				type.mono.copy(
					color =
						if (bad)
							palette.dangerText
						else
							palette.text,
				),
			modifier =
				Modifier
					.fillMaxWidth()
					.heightIn(max = 190.dp)
					.background(palette.sunken, RoundedCornerShape(Radius.medium))
					.verticalScroll(rememberScrollState())
					.padding(Space.s)
					.testTag(tag),
		)
	}
}

/**
 * A command as it is run, to be read: on one line, where that reads as typed; and where an argument has quotes or
 * lines in it (a script), each argument on a line of its own, numbered, with nothing added to it.
 */
internal fun runSaid(words: List<String>): String =
	if (words.any { word -> word.any { it in "\"'\\\n" } })
		words.withIndex().joinToString("\n") { (at, word) -> "${at + 1}  $word" }
	else
		textOf(words)

/** What would be run for an example, or what would be opened. */
@Composable
private fun ExampleSaid(example: Example) {
	val type = LocalType.current

	when (example) {
		Example.None ->
			Text(
				"Nothing to show until the command is complete.",
				style = type.muted,
			)
		is Example.Opens ->
			Text(
				example.what,
				style = type.body,
				modifier = Modifier.testTag("example"),
			)
		is Example.Runs -> {
			Text(
				when {
					example.files.isEmpty() -> "It runs:"
					example.runs.size > 1 ->
						"For ${example.files.joinToString(" and ")}, it runs once for each:"
					else -> "For ${example.files.joinToString(" and ")}, it runs:"
				},
				style = type.muted,
			)
			Printed(example.runs.joinToString("\n", transform = ::runSaid), "example")
		}
	}
}

/** What is wrong with what is typed, and the programs it needs that this machine has not. */
@Composable
private fun CheckedSaid(checked: Checked) {
	if (checked.wrong.isNotEmpty())
		if (checked.unfinished)
			Notice(
				"Not finished yet",
				Tone.Plain,
				Modifier.testTag("editor-unfinished"),
				detail = checked.wrong.joinToString(" "),
			)
		else
			Notice(
				"This can't be saved yet",
				Tone.Bad,
				Modifier.testTag("editor-wrong"),
				detail = checked.wrong.joinToString(" "),
			)
	checked.missing?.let {
		Notice(
			"Not available on this computer",
			Tone.Plain,
			Modifier.testTag("editor-missing"),
			detail = "It needs $it, which isn't installed. It can be saved, and will be offered once it is.",
		)
	}
	checked.unfound?.let {
		Notice(
			"“$it” wasn't found on this computer",
			Tone.Plain,
			Modifier.testTag("editor-unfound"),
			detail =
				"The menus will still offer this, and it will fail. To hide it where “$it” is missing, name " +
					"it under “Only if installed”, in More conditions.",
		)
	}
}

/** How a trial went. */
@Composable
private fun TriedSaid(tried: Tried?, read: String = "") {
	val palette = LocalPalette.current
	val type = LocalType.current

	when (tried) {
		null -> Unit
		Tried.Running ->
			Row(
				Modifier.testTag("tried-running"),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.s),
			) {
				Spinner(palette.accent, size = 16.dp, stroke = 2.dp)
				Text("Running…", style = type.muted)
			}
		is Tried.Refused ->
			Text(
				tried.why,
				style = type.body.copy(color = palette.dangerText),
				modifier = Modifier.testTag("tried-refused"),
			)
		is Tried.Opens ->
			Text(
				tried.what,
				style = type.body,
				modifier = Modifier.testTag("tried-opens"),
			)
		is Tried.Ran -> {
			tried.note?.let { Text(it, style = type.muted) }
			tried.runs.forEachIndexed { at, run ->
				Printed(runSaid(run.words), "tried-command:$at")
				Text(
					when (run.status) {
						null -> "It couldn't be started."
						0 -> "Finished successfully (exit status 0)."
						else -> "Failed, with exit status ${run.status}."
					},
					style =
						type.strong.copy(
							color =
								if (run.status == 0)
									palette.accentText
								else
									palette.dangerText,
						),
					modifier = Modifier.testTag("tried-status:$at"),
				)
				if (run.output.isNotBlank()) {
					Text("It printed:", style = type.muted)
					Printed(run.output, "tried-output:$at")
				}
				if (run.errors.isNotBlank()) {
					Text("On its error output:", style = type.muted)
					Printed(run.errors, "tried-errors:$at", bad = run.status != 0)
				}
				if (run.output.isBlank() && run.errors.isBlank())
					Text("It printed nothing.", style = type.muted)
			}
		}
		is Tried.Found -> {
			Printed(runSaid(tried.words), "tried-command:0")
			when (val ended = tried.ended) {
				is TextEnding.Failed ->
					Text(
						"It failed: ${ended.why.ifBlank { "it printed nothing" }}",
						style = type.body.copy(color = palette.dangerText),
						modifier = Modifier.testTag("tried-failed"),
					)
				else ->
					Text(
						(
							if (tried.first.isEmpty())
								"It found nothing."
							else
								"Found ${tried.lines} ${if (tried.first.first().hit == null)
									"files"
								else
									"lines"}" +
									" in ${tried.files} ${if (tried.files == 1) "file" else "files"}."
						) +
							(ended as? TextEnding.Done)
								?.note
								?.let { " It also wrote: $it" }
								.orEmpty(),
						style =
							type.strong.copy(
								color =
									if (tried.first.isEmpty())
										palette.text
									else
										palette.accentText,
							),
						modifier = Modifier.testTag("tried-found"),
					)
			}
			if (tried.first.isNotEmpty())
				Printed(
					tried.first.joinToString("\n") { found ->
						listOfNotNull(
							found.path.fileName?.toString(),
							found.hit?.position?.toString(),
							found.hit?.text,
						).joinToString(":")
					},
					"tried-hits",
				)
			if (tried.unread.isNotEmpty()) {
				Text(
					"These lines of its output aren't $read, so they were ignored:",
					style = type.body.copy(color = palette.dangerText),
					modifier = Modifier.testTag("tried-unread-said"),
				)
				Printed(tried.unread.joinToString("\n"), "tried-unread", bad = true)
			}
		}
	}
}

/** The room between what scrolls in one of these windows and the bar it scrolls by. */
private val BAR_ROOM = PaddingValues(end = Space.m)

/** The frame both windows share: the form at the left, what it comes to at the right, and Save under them. */
@Composable
private fun EditorModal(
	title: String,
	tag: String,
	fresh: Boolean,
	checked: Checked,
	onSave: () -> Unit,
	onClose: () -> Unit,
	onTest: () -> Unit,
	form: @Composable ColumnScope.() -> Unit,
	said: @Composable ColumnScope.() -> Unit,
) {
	Modal(
		title,
		onDismiss = onClose,
		width = ModalWidth.Wide,
		outside = false,
		tag = tag,
		buttons = {
			ButtonRow(
				Act(
					if (fresh)
						"Add"
					else
						"Save",
					"editor-save",
					checked.savable,
					onClick = onSave,
				),
				secondary = listOf(Act("Cancel", "editor-cancel", onClick = onClose)),
				// Tried out in a window of its own, once it is something that can be run.
				aside = listOf(Act("Test…", "editor-test", checked.savable, onClick = onTest)),
				// Why it cannot be saved, in as few words as say it: the whole of it is beside the form.
				note =
					checked.wrong
						.firstOrNull()
						?.takeIf { !checked.unfinished }
						?.substringBefore(". ")
						.orEmpty(),
			)
		},
	) {
		Row(
			Modifier.fillMaxWidth().weight(1f, fill = false),
			horizontalArrangement = Arrangement.spacedBy(Space.xl),
		) {
			// Each side keeps room between what it holds and its bar, so that no field's edge is against it.
			ScrollArea(Modifier.weight(1.3f), padding = BAR_ROOM, tag = "editor-form") {
				Column(verticalArrangement = Arrangement.spacedBy(Space.xl), content = form)
			}
			ScrollArea(Modifier.weight(1f), padding = BAR_ROOM, tag = "editor-said") {
				Column(verticalArrangement = Arrangement.spacedBy(Space.m), content = said)
			}
		}
	}
}

/** The name, submenu, id and shortcut every form begins with. */
@Composable
private fun Naming(
	label: String,
	submenu: String,
	id: String,
	key: String,
	fresh: Boolean,
	example: Pair<String, String>,
	onLabel: (String) -> Unit,
	onSubmenu: (String) -> Unit,
	onId: (String) -> Unit,
	onKey: (String) -> Unit,
) {
	FormSection("Name") {
		Fields(
			1.4f to
				{
					Typed(
						"Name in the menu",
						label,
						"form-label",
						hint = example.first,
						onText = onLabel,
					)
				},
			1f to {
				Typed(
					"Submenu",
					submenu,
					"form-submenu",
					hint = example.second,
					onText = onSubmenu,
				)
			},
		)
		Fields(
			1.4f to {
				Typed(
					"Id",
					id,
					"form-id",
					help = "Can't be changed once saved.".takeIf { !fresh },
					readOnly = !fresh,
					mono = true,
					onText = onId,
				)
			},
			1f to {
				Typed(
					"Shortcut",
					key,
					"form-key",
					hint = "Ctrl+Shift+E",
					onText = onKey,
				)
			},
		)
	}
}

// ---- an action -------------------------------------------------------------------------------------------------------

/** A name and a submenu an action might have, shown where none is typed. */
private val EXAMPLE_NAMES = "Convert to PNG" to "Image/Convert"

/** What an action's command can name. */
private fun actionLegend(
	asks: List<AskForm>,
	placeValues: List<String>,
): List<Pair<String, String>> =
	listOf(
		"{path}" to "A file's full path. The command runs once for each file.",
		"{paths}" to "Every file's path, each an argument. The command runs once.",
		"{name}  {names}" to "The same, as names without their folders.",
		"{stem}  {ext}" to "A file's name without its extension, and the extension.",
		"{parent}" to "The folder a file is in.",
		"{folder}" to "The folder the window shows.",
		"{entry}  {entries}" to "In a place: its entries, as the place names them.",
		"{temp}" to "An empty temporary folder, removed afterwards.",
	) +
		asks.filter { it.name.isNotBlank() }.map {
			"{${it.name.trim()}}" to
				"The answer to “${it.prompt}”."
		} +
		placeValues.map { "{$it}" to "What the place was opened with." }

/** The window that makes or changes an action. */
@Composable
internal fun ActionEditor(
	modal: ActingModal.EditingAction,
	settings: Settings,
	world: World,
	onChange: (Settings) -> Unit,
) {
	val type = LocalType.current
	val form = modal.form
	val acting = settings.acting
	val installed = rememberInstalled(world)
	val spec = form.spec()
	val catalog =
		remember(acting, installed) { ScriptKind.Action.catalog(acting, installed) }

	val checked =
		remember(acting, spec, form.fresh, installed) {
			ScriptKind.Action.checked(acting, spec, form.fresh, installed)
		}
	val example = remember(acting, spec) { exampleOf(acting, spec, world) }

	fun typed(form: ActionForm) = onChange(settings.copy(modal = modal.copy(form = form)))

	fun close() = onChange(settings.copy(modal = null))

	// The places there are to open, and the actions there are to be like: those that are not like another themselves.
	val places = settings.actions.places
	val bases = catalog.entries.filter { it.spec.like == null && it.id != form.id }

	EditorModal(
		when {
			!form.fresh -> "Edit “${catalog[form.id]?.spec?.label ?: form.label}”"
			form.label.isBlank() -> "New action"
			else -> "New action: ${form.label}"
		},
		"action-editor",
		form.fresh,
		checked,
		onSave = {
			onChange(settings.copy(acting = ScriptKind.Action.put(acting, spec), modal = null))
		},
		onClose = { close() },
		onTest = { onChange(settings.copy(modal = modal.copy(trying = true))) },
		form = {
			Naming(
				form.label,
				form.submenu,
				form.id,
				form.key,
				form.fresh,
				EXAMPLE_NAMES,
				onLabel = { typed(form.named(it, catalog.ids)) },
				onSubmenu = { typed(form.copy(submenu = it)) },
				onId = { typed(form.copy(id = it)) },
				onKey = { typed(form.copy(key = it)) },
			)
			FormSection("What it does") {
				Segmented(Does.entries, form.does, Does::label, tag = "does") {
					typed(form.copy(does = it))
				}
				when (form.does) {
					Does.Run -> {
						WaysEditor(form.ways, "command", "ffmpeg -i {path} {stem}.mp4") {
							typed(form.copy(ways = it))
						}
						Legend(
							actionLegend(
								form.asks,
								form.conditions.within
									.flatMap { places[it]?.takes.orEmpty() }
									.distinct(),
							),
							"Each named value is also in the command's environment (\$dest), which is the " +
								"safe way to use one inside sh -c.",
						)
						Listing(
							"Ask first",
							"Each answer is used in the command by its name: {dest}.",
							form.asks.isNotEmpty(),
						) {
							form.asks.forEachIndexed { at, ask ->
								fun asking(to: AskForm) =
									typed(
										form.copy(
											asks =
												form.asks.mapIndexed { index, was ->
													if (index ==
														at
													) to else was
												},
										),
									)

								Row(
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(Space.s),
								) {
									Field(
										ask.name,
										{ asking(ask.copy(name = it)) },
										Modifier.width(110.dp).testTag("ask-name:$at"),
										hint = "name",
										style = type.mono,
									)
									Segmented(
										listOf(false, true),
										ask.folder,
										{ if (it) "Folder" else "Text" },
										tag = "ask-kind:$at",
									) { asking(ask.copy(folder = it)) }
									Field(
										ask.prompt,
										{ asking(ask.copy(prompt = it)) },
										Modifier.weight(1f).testTag("ask-prompt:$at"),
										hint = "What to ask",
									)
									Field(
										ask.start,
										{ asking(ask.copy(start = it)) },
										Modifier.width(120.dp).testTag("ask-start:$at"),
										hint = "Starts as",
									)
									ToolButton(
										Glyph.Close,
										"Remove this question",
										enabled = true,
										Modifier.testTag("ask-remove:$at"),
									) {
										typed(form.copy(asks = form.asks.filterIndexed { index, _ -> index != at }))
									}
								}
							}
							PushButton(
								"Add a question",
								Emphasis.Quiet,
								enabled = true,
								Modifier.testTag("ask-add"),
								glyph = Glyph.Plus,
							) { typed(form.copy(asks = form.asks + AskForm())) }
						}
						Labelled("How it runs", help = form.running.help) {
							Segmented(Running.entries, form.running, Running::label, tag = "running") {
								typed(form.copy(running = it))
							}
						}
						if (form.running == Running.Watched) {
							CheckBox(
								"Refresh the listing when it finishes",
								form.reread,
								Modifier.testTag("then-reread"),
							) { typed(form.copy(reread = it)) }
							Typed(
								"Then select",
								form.choose,
								"then-choose",
								hint = "{stem}.png",
								help = "The name the cursor lands on afterwards.",
								mono = true,
							) { typed(form.copy(choose = it)) }
							Fields(
								1f to {
									Typed(
										"Bring what it made from",
										form.bringFrom,
										"then-from",
										hint = "{temp}",
										mono = true,
									) { typed(form.copy(bringFrom = it)) }
								},
								1f to {
									Typed("Into", form.bringTo, "then-to", hint = "{dest}", mono = true) {
										typed(form.copy(bringTo = it))
									}
								},
							)
						}
					}
					Does.Folder -> {
						Typed(
							"Folder",
							form.folder,
							"show-folder",
							hint = "{parent}",
							help = "A path, which may name the file: {parent} is the folder it is in.",
							mono = true,
						) { typed(form.copy(folder = it)) }
						Typed(
							"Then select",
							form.folderChoose,
							"show-choose",
							hint = "{name}",
							mono = true,
						) {
							typed(form.copy(folderChoose = it))
						}
						Choice("Open it in", Opens.entries, form.opens, "opens", Opens::label) {
							typed(form.copy(opens = it))
						}
					}
					Does.Place -> {
						Labelled("Place") {
							Select(
								listOf("") + places.keys,
								form.place.takeIf { it in places }.orEmpty(),
								{ it.ifEmpty { "Choose a place…" } },
								Modifier.fillMaxWidth().testTag("show-place"),
							) { typed(form.copy(place = it)) }
						}
						places[form.place]?.takes?.forEach { name ->
							Typed(
								"Its “$name”",
								form.placeWith[name].orEmpty(),
								"show-with:$name",
								hint = "{path}",
								mono = true,
							) { typed(form.copy(placeWith = form.placeWith + (name to it))) }
						}
						Choice("Open it in", Opens.entries, form.opens, "opens", Opens::label) {
							typed(form.copy(opens = it))
						}
					}
					Does.Like -> {
						Labelled(
							"Action",
							help = "This one does what that one does, with some of its questions answered already.",
						) {
							Select(
								listOf("") + bases.map { it.id },
								form.like.takeIf { like -> bases.any { it.id == like } }.orEmpty(),
								{ id ->
									catalog[id]?.spec?.let { "${it.label}  (${it.id})" } ?: "Choose an action…"
								},
								Modifier.fillMaxWidth().testTag("like"),
							) { typed(form.copy(like = it)) }
						}
						settings.actions[form.like]?.questions?.forEach { question ->
							Typed(
								"Answer to “${question.prompt}”",
								form.given[question.name].orEmpty(),
								"given:${question.name}",
								hint = "Leave empty to be asked",
								mono = true,
							) { typed(form.copy(given = form.given + (question.name to it))) }
						}
						Typed("Then select", form.choose, "then-choose", hint = "{stem}", mono = true) {
							typed(form.copy(choose = it))
						}
					}
				}
				Typed(
					"Confirm first",
					form.confirm,
					"confirm",
					hint = "A warning to agree to before it is done (optional)",
				) { typed(form.copy(confirm = it)) }
			}
			FormSection("When it's offered") {
				ConditionsEditor(form.conditions, catalog.scenes, search = false) {
					typed(form.copy(conditions = it))
				}
				CheckBox(
					"A double click does this instead of opening the file",
					form.default,
					Modifier.testTag("form-default"),
				) { typed(form.copy(default = it)) }
			}
		},
		said = {
			Panel("When it's offered", "said-offered") {
				offeredSaid(spec.conditions, On.Files, catalog.scenes).forEach {
					Text(it, style = type.body)
				}
			}
			Panel("What it does", "said-does") {
				ExampleSaid(example)
			}
			CheckedSaid(checked)
		},
	)
}

// ---- a text search -----------------------------------------------------------------------------------------------------

/** What a search's command is given, in a sentence. */
private fun givenSaid(takes: Takes): String =
	when (takes) {
		Takes.Files ->
			"On its standard input, it gets the path of every file the window shows, each ended by a NUL " +
				"character, as xargs -0 reads them."
		Takes.Lines ->
			"On its standard input, it gets the lines found so far, one per line. It is offered as a further " +
				"step, once something has found lines."
	}

/** What a search's command must print, in a sentence, and a line of it. */
private fun printsSaid(form: SearchForm): Pair<String, String> {
	val position =
		when (form.positions) {
			Positions.Lines -> "the line's number"
			Positions.Offsets -> "its byte offset in the file"
		}

	return when (form.read) {
		HitRead.Lines ->
			if (form.takes == Takes.Lines)
				"For each line it keeps: its number among the lines it was given, a colon, and the line." to
					"3:val answer = 42"
			else if (form.nul)
				"For each line found: the file's path, a NUL character, $position, a colon, and the line." to
					"src/main.kt␀12:val answer = 42"
			else
				"For each line found: the file's path, $position, and the line, with colons between." to
					"src/main.kt:12:val answer = 42"
		HitRead.RgJson -> "ripgrep's own --json output." to """{"type":"match","data":{…}}"""
		HitRead.Json ->
			"A JSON object on each line: path, at ($position), text, and marks, the start and end of each match." to
				"""{"path":"src/main.kt","at":12,"text":"val answer = 42","marks":[[4,10]]}"""
		HitRead.Paths ->
			(
				if (form.nul)
					"The path of each file found, each ended by a NUL character."
				else
					"The path of each file found, one per line."
			) to "src/main.kt"
	}
}

/** The window that makes or changes a text search. */
@Composable
internal fun SearchEditor(
	modal: ActingModal.EditingSearch,
	settings: Settings,
	world: World,
	onChange: (Settings) -> Unit,
) {
	val type = LocalType.current
	val form = modal.form
	val acting = settings.acting
	val installed = rememberInstalled(world)
	val spec = form.spec()
	val catalog =
		remember(acting, installed) { ScriptKind.Search.catalog(acting, installed) }

	val checked =
		remember(acting, spec, form.fresh, installed) {
			ScriptKind.Search.checked(acting, spec, form.fresh, installed)
		}
	val example = remember(acting, spec) { exampleOf(acting, spec, world) }
	val (prints, printed) = printsSaid(form)

	fun typed(form: SearchForm) = onChange(settings.copy(modal = modal.copy(form = form)))

	fun close() = onChange(settings.copy(modal = null))

	val named =
		if (form.positions == Positions.Lines)
			"{line}"
		else
			"{offset}"

	EditorModal(
		when {
			!form.fresh -> "Edit “${catalog[form.id]?.spec?.label ?: form.label}”"
			form.label.isBlank() -> "New text search"
			else -> "New text search: ${form.label}"
		},
		"search-editor",
		form.fresh,
		checked,
		onSave = {
			onChange(settings.copy(acting = ScriptKind.Search.put(acting, spec), modal = null))
		},
		onClose = { close() },
		onTest = { onChange(settings.copy(modal = modal.copy(trying = true))) },
		form = {
			Naming(
				form.label,
				form.submenu,
				form.id,
				form.key,
				form.fresh,
				"Find TODOs…" to "Search",
				onLabel = { typed(form.named(it, catalog.ids)) },
				onSubmenu = { typed(form.copy(submenu = it)) },
				onId = { typed(form.copy(id = it)) },
				onKey = { typed(form.copy(key = it)) },
			)
			FormSection("What it looks through") {
				Segmented(
					Takes.entries,
					form.takes,
					{
						when (it) {
							Takes.Files -> "The files a window shows"
							Takes.Lines -> "Lines already found"
						}
					},
					tag = "takes",
				) { typed(form.copy(takes = it)) }
				Text(givenSaid(form.takes), style = type.muted)
				Labelled(
					"What is typed into it",
					help =
						when (form.query) {
							QueryUse.Typed ->
								"The command is run again as the query changes, and names it as {query}."
							QueryUse.Narrows ->
								"The command is run once, without the query; what is typed filters what it printed."
						},
				) {
					Segmented(
						QueryUse.entries,
						form.query,
						{
							when (it) {
								QueryUse.Typed -> "Is given to the command"
								QueryUse.Narrows -> "Filters its output"
							}
						},
						tag = "query-use",
					) { typed(form.copy(query = it)) }
				}
			}
			FormSection("The command") {
				WaysEditor(
					form.ways,
					"command",
					"xargs -0 rg --json {options} -e {query} --",
					reads = true,
				) {
					typed(form.copy(ways = it))
				}
				Legend(
					listOf(
						"{query}" to "What is typed. Also in the environment, as \$query.",
						"{options}" to
							"The words of the options below, as each is on or off. An argument of its own.",
						"{folder}" to "The folder the command runs in, which paths are shown from.",
					),
				)
				Listing(
					"Options",
					"Switches shown beside the query. Each adds its words where the command says {options}.",
					form.options.isNotEmpty(),
				) {
					form.options.forEachIndexed { at, option ->
						fun opting(to: OptionForm) =
							typed(
								form.copy(
									options =
										form.options.mapIndexed { index, was ->
											if (index ==
												at
											) to else was
										},
								),
							)

						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(Space.s),
						) {
							Field(
								option.name,
								{ opting(option.copy(name = it)) },
								Modifier.width(90.dp).testTag("option-name:$at"),
								hint = "name",
								style = type.mono,
							)
							Field(
								option.label,
								{ opting(option.copy(label = it)) },
								Modifier.weight(1f).testTag("option-label:$at"),
								hint = "Match case",
							)
							Field(
								option.on,
								{ opting(option.copy(on = it)) },
								Modifier.weight(1f).testTag("option-on:$at"),
								hint = "When on: -s",
								style = type.mono,
							)
							Field(
								option.off,
								{ opting(option.copy(off = it)) },
								Modifier.weight(1f).testTag("option-off:$at"),
								hint = "When off: -i",
								style = type.mono,
							)
							ToolButton(
								Glyph.Close,
								"Remove this option",
								enabled = true,
								Modifier.testTag("option-remove:$at"),
							) {
								typed(form.copy(options = form.options.filterIndexed { index, _ -> index != at }))
							}
						}
					}
					PushButton(
						"Add an option",
						Emphasis.Quiet,
						enabled = true,
						Modifier.testTag("option-add"),
						glyph = Glyph.Plus,
					) { typed(form.copy(options = form.options + OptionForm())) }
				}
			}
			FormSection("What it prints") {
				Segmented(
					HitRead.entries,
					form.read,
					{
						when (it) {
							HitRead.Lines -> "Lines"
							HitRead.RgJson -> "ripgrep JSON"
							HitRead.Json -> "JSON"
							HitRead.Paths -> "Paths only"
						}
					},
					tag = "read",
				) { typed(form.copy(read = it)) }
				Text(prints, style = type.muted, modifier = Modifier.testTag("prints-said"))
				Printed(printed, "prints-example")
				if (form.read == HitRead.Lines || form.read == HitRead.Paths)
					CheckBox(
						"A NUL character ends each path, as grep -Z and rg -0 print them",
						form.nul,
						Modifier.testTag("nul"),
					) { typed(form.copy(nul = it)) }
				if (form.read != HitRead.Paths)
					Choice(
						"The number with each line is",
						Positions.entries,
						form.positions,
						"positions",
						{
							when (it) {
								Positions.Lines -> "A line number"
								Positions.Offsets -> "A byte offset"
							}
						},
					) { typed(form.copy(positions = it)) }
			}
			FormSection(
				"The file shown beside a line",
				help =
					"A command whose output is shown as the preview, colours and all. With none, prism shows " +
						"the file's text itself.",
			) {
				WaysEditor(
					form.previews,
					"preview",
					"bat --color=always --style=plain {path}",
					least = 0,
					another =
						if (form.previews.isEmpty())
							"Add a preview command"
						else
							"Add a fallback command",
				) { typed(form.copy(previews = it)) }
				if (form.previews.isNotEmpty())
					Legend(
						listOf(
							"{path}" to "The file the line is in.",
							named to "Where in it the line is.",
							"{text}  {query}" to "The line's text, and what was typed.",
						),
					)
			}
			FormSection("Opening a line") {
				CheckBox(
					"Open with a command of its own, instead of your editor",
					form.opening,
					Modifier.testTag("opening"),
				) { typed(form.copy(opening = it)) }
				if (form.opening) {
					CommandEditor(form.open, "open", "code --goto {path}:{line}") {
						typed(form.copy(open = it))
					}
					CheckBox(
						"Run it in a terminal",
						form.openInTerminal,
						Modifier.testTag("open-terminal"),
					) {
						typed(form.copy(openInTerminal = it))
					}
				}
			}
			if (form.takes == Takes.Files)
				FormSection("When it's offered") {
					ConditionsEditor(form.conditions, catalog.scenes, search = true) {
						typed(form.copy(conditions = it))
					}
				}
		},
		said = {
			Panel("When it's offered", "said-offered") {
				if (form.takes == Takes.Files)
					offeredSaid(spec.conditions, null, catalog.scenes).forEach {
						Text(it, style = type.body)
					}
				else
					Text(
						"As a further step in a text search, once something has found lines.",
						style = type.body,
					)
			}
			Panel("What it does", "said-does") {
				ExampleSaid(example)
				Text("It must print: $prints", style = type.muted)
			}
			CheckedSaid(checked)
		},
	)
}

// ---- trying it out ---------------------------------------------------------------------------------------------------

/**
 * The window that tries out what is being edited, over the window that edits it: what it is to be run on, typed in
 * [inputs]; Run, which does [work] off the window's thread and shows how it went; and Close, back to the form with
 * what was typed here kept.
 */
@Composable
private fun TrialModal(
	modal: ActingModal.Editing,
	title: String,
	about: String,
	settings: Settings,
	onChange: (Settings) -> Unit,
	work: (Trial) -> Tried,
	read: String = "",
	inputs: @Composable ColumnScope.((Trial) -> Unit) -> Unit,
) {
	val type = LocalType.current
	val scope = rememberCoroutineScope()
	val latest by rememberUpdatedState(settings)
	val change by rememberUpdatedState(onChange)
	val trial = modal.trial

	// Each of these is given as a lambda and never by reference: a reference to one is equal to the last, whatever
	// it has closed over since, and so would go on being the one that knew the window as it first was.
	fun trying(trial: Trial) = onChange(settings.copy(modal = modal.trying(trial)))

	fun close() = onChange(settings.copy(modal = modal.trying(trial, open = false)))

	fun run() {
		trying(trial.copy(result = Tried.Running))
		scope.launch {
			val tried = withContext(Dispatchers.IO) { work(trial) }

			// Shown in whichever window is editing by then: this one, if it is still open, and the form's otherwise.
			(latest.modal as? ActingModal.Editing)?.let { now ->
				change(latest.copy(modal = now.trying(now.trial.copy(result = tried), now.trying)))
			}
		}
	}

	Modal(
		title,
		onDismiss = { close() },
		width = ModalWidth.Large,
		outside = false,
		tag = "trial",
		buttons = {
			ButtonRow(
				Act("Run", "trial-run", trial.result != Tried.Running, onClick = { run() }),
				secondary = listOf(Act("Close", "trial-close", onClick = { close() })),
			)
		},
	) {
		Text(about, style = type.muted)
		ScrollArea(
			Modifier.fillMaxWidth().weight(1f, fill = false),
			padding = BAR_ROOM,
			tag = "trial-scroll",
		) {
			Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
				inputs { trying(it) }
				TriedSaid(trial.result, read)
			}
		}
	}
}

/** An action tried out: the files it is run on, and the answers to what it asks. */
@Composable
private fun ActionTrial(
	modal: ActingModal.EditingAction,
	settings: Settings,
	world: World,
	commands: Commands,
	onChange: (Settings) -> Unit,
) {
	val type = LocalType.current
	val acting = settings.acting
	val spec = modal.form.spec()
	val asked = remember(acting, spec) { valuesAsked(acting, spec, world) }
	val trial = modal.trial

	TrialModal(
		modal,
		"Test “${spec.label}”",
		"Runs the command for real on the files you name here, and shows everything it printed. " +
			"Nothing is saved by testing.",
		settings,
		onChange,
		work = { tried(acting, spec, it, world, commands) },
	) { trying ->
		Labelled("Files to run it on", help = "One per line. ~ is your home folder.") {
			TextArea(
				trial.given,
				{ trying(trial.copy(given = it)) },
				Modifier.fillMaxWidth().testTag("trial-given"),
				hint = "~/Pictures/holiday.jpg",
			)
		}
		asked.forEach { name ->
			Labelled("{$name}") {
				Field(
					trial.answers[name].orEmpty(),
					{ trying(trial.copy(answers = trial.answers + (name to it))) },
					Modifier.fillMaxWidth().testTag("trial-answer:$name"),
					style = type.mono,
				)
			}
		}
	}
}

/** A text search tried out: what it looks in, what for, and with which of its options on. */
@Composable
private fun SearchTrial(
	modal: ActingModal.EditingSearch,
	settings: Settings,
	world: World,
	commands: Commands,
	onChange: (Settings) -> Unit,
) {
	val acting = settings.acting
	val form = modal.form
	val spec = form.spec()
	val trial = modal.trial
	val lines = form.takes == Takes.Lines

	TrialModal(
		modal,
		"Test “${spec.label}”",
		"Runs the command for real and shows what it found, and any of its output that couldn't be read. " +
			"Nothing is saved by testing.",
		settings,
		onChange,
		work = { tried(acting, spec, it, world, commands) },
		read =
			when (form.read) {
				HitRead.Lines ->
					if (lines)
						"a number, a colon and a line"
					else
						"a path, a number and a line"
				HitRead.RgJson -> "ripgrep's JSON"
				HitRead.Json -> "JSON objects"
				HitRead.Paths -> "paths"
			},
	) { trying ->
		Labelled(
			if (lines)
				"Lines for it to look through"
			else
				"Files or folders to look in",
			help = "One per line.",
		) {
			TextArea(
				trial.given,
				{ trying(trial.copy(given = it)) },
				Modifier.fillMaxWidth().testTag("trial-given"),
				hint =
					if (lines)
						"val answer = 42"
					else
						"~/Projects",
			)
		}
		Labelled("What to look for") {
			Field(
				trial.query,
				{ trying(trial.copy(query = it)) },
				Modifier.fillMaxWidth().testTag("trial-query"),
				leading = Glyph.Search,
			)
		}
		form.options.filter { it.name.isNotBlank() }.forEach { option ->
			CheckBox(
				option.label.ifBlank { option.name },
				option.name in trial.on,
				Modifier.testTag("trial-option:${option.name}"),
			) { on ->
				trying(
					trial.copy(
						on =
							if (on)
								trial.on + option.name
							else
								trial.on - option.name,
					),
				)
			}
		}
	}
}

/** Whatever window is open over the pages of actions and searches, and over that the one that tries it out. */
@Composable
internal fun ActingModals(
	settings: Settings,
	world: World,
	commands: Commands,
	onChange: (Settings) -> Unit,
) {
	when (val modal = settings.modal) {
		null -> Unit
		is ActingModal.EditingAction -> {
			ActionEditor(modal, settings, world, onChange)
			if (modal.trying)
				ActionTrial(modal, settings, world, commands, onChange)
		}
		is ActingModal.EditingSearch -> {
			SearchEditor(modal, settings, world, onChange)
			if (modal.trying)
				SearchTrial(modal, settings, world, commands, onChange)
		}
		is ActingModal.Moving -> MoveModal(modal, settings, world, onChange)
	}
}

/** What a kind's conditions are for where they do not say, which is all a caller with a [Declared] needs of its kind. */
internal fun unsaidOf(spec: Declared): On? =
	when (spec) {
		is prism.preferences.ActionSpec -> On.Files
		is prism.preferences.TextSearchSpec -> null
	}

/** [conditions] in a sentence or two, for whoever has no window's names to say them with. */
internal fun offeredSaid(
	conditions: WhenSpec,
	spec: Declared,
	scenes: Scenes,
): List<String> = offeredSaid(conditions, unsaidOf(spec), scenes)
