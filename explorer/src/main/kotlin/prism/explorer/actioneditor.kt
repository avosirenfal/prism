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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import prism.browser.Act
import prism.browser.ButtonRow
import prism.browser.Caption
import prism.browser.CheckBox
import prism.browser.Commands
import prism.browser.Count
import prism.browser.Divider
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.HitRead
import prism.browser.Labelled
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.MenuButton
import prism.browser.Modal
import prism.browser.ModalWidth
import prism.browser.Notice
import prism.browser.On
import prism.browser.Positions
import prism.browser.PushButton
import prism.browser.QueryUse
import prism.browser.Radius
import prism.browser.Reading
import prism.browser.ScrollArea
import prism.browser.Segmented
import prism.browser.Select
import prism.browser.Space
import prism.browser.Spinner
import prism.browser.Tabs
import prism.browser.Takes
import prism.browser.TextEnding
import prism.browser.ThingKind
import prism.browser.Tip
import prism.browser.Tone
import prism.browser.ToolButton
import prism.browser.World
import prism.browser.felt
import prism.browser.menu
import prism.browser.ring
import prism.preferences.Declared
import prism.preferences.WhenSpec

/*
 * The windows that make and change an action and a text search, over the preferences' window. Each is one column of
 * a form, in a few parts shown one at a time, so that what is in sight is only what is being set: what it does, how
 * it runs, when it is offered.
 *
 * What a part comes to is said in that part, as it is typed, and not beside the form: under a command, the command as
 * it would be run for an example of what the action is for; over the conditions, when they offer it, in a person's
 * words. What is wrong with what is typed is one line over the buttons. What is done to a command rather than typed
 * into it (a placeholder put in, a fallback added, its arguments set apart) is in two small menus at its corner, and
 * takes no room until it is used. Test opens a window over this one that runs it for real.
 */

// ---- parts of a form -------------------------------------------------------------------------------------------------

/**
 * Several lines to type into, in a field's look: as many lines tall as it holds, [lines] at least. Tab moves the
 * keyboard on, and Shift+Tab back, as a field of one line does, and is never typed into it.
 */
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
	val focus = LocalFocusManager.current

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
			Modifier.fillMaxWidth().onPreviewKeyEvent { key -> movedOn(key, focus) },
			textStyle = style.copy(color = palette.text),
			minLines = lines,
			maxLines = 14,
			cursorBrush = SolidColor(palette.accent),
			interactionSource = source,
		)
	}
}

/**
 * Whether [key] is Tab, or Shift+Tab, with nothing else held: the keyboard moved on to the next thing that takes it,
 * or back, as it is pressed, and every event of the key taken, so that none of them is typed.
 */
private fun movedOn(key: KeyEvent, focus: FocusManager): Boolean =
	(key.key == Key.Tab && !key.isCtrlPressed && !key.isAltPressed && !key.isMetaPressed)
		.also { tab ->
			if (tab && key.type == KeyEventType.KeyDown)
				focus.moveFocus(
					if (key.isShiftPressed)
						FocusDirection.Previous
					else
						FocusDirection.Next,
				)
		}

/**
 * What each label of these windows says when the pointer rests on it: what a word of prism's means (a place, a
 * submenu), and what a choice comes to, for whoever has not met it. A label with nothing to add to its name has none.
 */
private val TIPS: Map<String, String> =
	mapOf(
		"Name" to "The label shown in the context menu.",
		"Submenu" to
			"Places the item in a submenu instead of the top level. Use / to nest, as in Image/Convert.",
		"Type" to
			"Run command runs a program on the selection. Open folder and Open place navigate instead; a " +
			"place is a view listed by a command, such as an archive's contents. Based on another reuses " +
			"another action's command with some of its prompts already answered.",
		"Command" to
			"The program and its arguments. It runs directly, not through a shell, so wrap pipes and " +
			"redirection in sh -c. Use the {} button to insert placeholders such as the file's path.",
		"requires" to
			"Programs this command requires, separated by commas; a|b accepts either. If one is missing, " +
			"the next fallback is used.",
		"Prompts" to
			"Questions asked before the command runs. Text asks for a line of text; Folder opens a folder " +
			"picker.",
		"Folder" to "The folder to open. Placeholders work here: {parent} is the file's folder.",
		"Then select" to
			"The item to select afterwards, such as the file the command created: {stem}.png.",
		"Open in" to "Where to open it. Default uses the place's own setting.",
		"Place" to
			"A view whose contents are listed by a command, such as an archive. Places are defined in the " +
			"preferences file.",
		"Based on" to "Runs that action's command, with the prompts below already answered.",
		"Move results from" to
			"After the command finishes, moves everything in this folder to the destination without " +
			"overwriting. For commands that write to {temp}.",
		"To" to "The destination for the moved results.",
		"Confirmation message" to "Shown before running, with the option to cancel.",
		"Menu" to
			"Which context menu shows it: the one for selected items, for a folder's background, or for " +
			"an item in the sidebar.",
		"Views" to
			"Limits it to certain views: folders, search results, or a place such as an archive. Empty " +
			"means every view.",
		"Selection" to "How many items must be selected.",
		"Item type" to "Requires every selected item to be of this type.",
		"Extensions" to
			"Requires every selected file to have one of these extensions. Comma-separated, without the dot.",
		"Name patterns" to
			"Requires every name to match one of these patterns. * matches anything.",
		"MIME types" to
			"Requires every file to have one of these MIME types. image/* matches any image.",
		"Excluded extensions" to
			"Extensions to skip even though they match above, such as tar.gz for an action on gz.",
		"Required programs" to
			"Hides it unless these programs are installed. Comma-separated; a|b accepts either.",
		"Within folders" to
			"Requires every item to be inside one of these folders. ~ is your home folder.",
		"Inside a folder containing" to
			"Requires an enclosing folder that contains one of these names, such as .git for files in a " +
			"repository.",
		"Location" to
			"On disk means ordinary files. In a place means items that exist only in a view such as an " +
			"archive, which commands receive as {entry}.",
		"Listed outside own folder" to
			"Yes limits it to views that list items from many folders together, such as search results.",
		"Shortcut" to "The default keyboard shortcut. Change it later under Key bindings.",
		"Id" to "A unique identifier, used in the preferences file and key bindings.",
		"Input" to
			"What the command receives. Files in view are the listed files or search results. Previous " +
			"results makes it a refining step, which filters the lines an earlier step found.",
		"Output format" to
			"How to parse the command's output. Test lists any lines that don't parse.",
		"Position" to
			"Line numbers let the preview jump to the result. Byte offsets, as printed by strings -t d, " +
			"are shown but not jumped to.",
		"Preview command" to
			"Prints the file for the preview pane, colors included. For example, bat for syntax highlighting.",
		"Open command" to
			"Runs when you double-click a result, instead of your editor. Use {path} and {line}.",
	)

/** A labelled control of these windows: as [Labelled], with what its label says under the pointer, where it says more. */
@Composable
private fun Headed(
	label: String,
	help: String? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	Labelled(label, help = help, tip = TIPS[label], content = content)
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
	Headed(label, help = help) {
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

/** What a command's two menus do. */
private sealed interface CommandDeed {
	/** [text] put after the rest of the command. */
	data class Insert(
		val text: String,
	) : CommandDeed

	/** A question added, for text or for a [folder], and its answer put into the command. */
	data class Ask(
		val folder: Boolean,
	) : CommandDeed

	/** The command edited as its arguments apart, or as one line. */
	data class Apart(
		val apart: Boolean,
	) : CommandDeed

	/** Another command added after this one, for where this one's programs are not installed. */
	data object Fallback : CommandDeed

	data object Remove : CommandDeed
}

/** A command as it is typed: a line, or its arguments each in a box of its own, added to and taken from. */
@Composable
private fun CommandBody(
	command: CommandForm,
	tag: String,
	hint: String,
	onChange: (CommandForm) -> Unit,
) {
	val type = LocalType.current

	when (command) {
		is CommandForm.Line ->
			TextArea(
				command.text,
				{ onChange(CommandForm.Line(it)) },
				Modifier.fillMaxWidth().testTag(tag),
				hint = hint,
			)
		is CommandForm.Words ->
			Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
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
							"Remove argument",
							enabled = command.words.size > 1,
							Modifier.testTag("$tag-remove:$at"),
						) {
							onChange(
								CommandForm.Words(command.words.filterIndexed { index, _ -> index != at }),
							)
						}
					}
				}
				PushButton(
					"Add argument",
					Emphasis.Quiet,
					enabled = true,
					Modifier.testTag("$tag-add"),
					glyph = Glyph.Plus,
				) { onChange(CommandForm.Words(command.words + "")) }
			}
	}
}

/**
 * The ways something is run, one after another: each its command, under a line that names it and holds its two menus.
 * One menu puts in something the command can name ([names], each with what it becomes; and, where [onAsk] is given,
 * a question whose answer it then names). The other sets its arguments apart, adds a way to fall back to, or takes
 * this one away. A way that needs programs says which on the same line; the first whose programs are installed is the
 * one that runs. For a text search's ([reads]), a way may say how its own output is read.
 */
@Composable
private fun WaysEditor(
	ways: List<WayForm>,
	tag: String,
	hint: String,
	names: List<Pair<String, String>>,
	label: String = "Command",
	reads: Boolean = false,
	least: Int = 1,
	fallbacks: Boolean = true,
	onAsk: ((at: Int, folder: Boolean) -> Unit)? = null,
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
			val its =
				if (ways.size == 1)
					tag
				else
					"$tag:$at"

			Column(
				if (ways.size > 1)
					Modifier
						.fillMaxWidth()
						.border(1.dp, palette.border, shape)
						.padding(Space.m)
				else
					Modifier.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(Space.s),
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Space.s),
				) {
					Caption(
						when {
							ways.size == 1 -> label
							at == 0 -> label
							else -> "Fallback"
						},
						TIPS[label],
					)
					if (several) {
						Tip(TIPS.getValue("requires")) { Text("requires", style = type.muted) }
						Field(
							way.programs,
							{ changed(at, way.copy(programs = it)) },
							Modifier.weight(1f).testTag("$tag-programs:$at"),
							hint = "unzip, 7zz|7z",
							style = type.mono,
						)
					} else {
						Box(Modifier.weight(1f))
					}
					MenuButton(
						Glyph.Braces,
						"Insert a placeholder",
						menu = {
							menu {
								names.forEach { (name, meaning) ->
									item("$name   $meaning", CommandDeed.Insert(name.substringBefore(' ')))
								}
								if (onAsk != null) {
									separator()
									item("Prompt for text…", CommandDeed.Ask(folder = false))
									item("Prompt for a folder…", CommandDeed.Ask(folder = true))
								}
							}
						},
						onAction = { deed ->
							when (deed) {
								is CommandDeed.Insert ->
									changed(
										at,
										way.copy(command = way.command.with(deed.text)),
									)
								is CommandDeed.Ask -> onAsk?.invoke(at, deed.folder)
								else -> Unit
							}
						},
						Modifier.testTag("$its-insert"),
					)
					MenuButton(
						Glyph.More,
						"More",
						menu = {
							menu {
								if (way.command is CommandForm.Words)
									item("Edit as command line", CommandDeed.Apart(false))
								else
									item("Edit as separate arguments", CommandDeed.Apart(true))
								if (fallbacks)
									item("Add fallback command", CommandDeed.Fallback)
								if (ways.size > least)
									item("Remove command", CommandDeed.Remove)
							}
						},
						onAction = { deed ->
							when (deed) {
								is CommandDeed.Apart ->
									changed(
										at,
										way.copy(
											command =
												if (deed.apart)
													way.command.apart()
												else
													way.command.line(),
										),
									)
								CommandDeed.Fallback ->
									onChange(ways.take(at + 1) + WayForm() + ways.drop(at + 1))
								CommandDeed.Remove -> onChange(ways.filterIndexed { index, _ -> index != at })
								else -> Unit
							}
						},
						Modifier.testTag("$its-more"),
					)
				}
				CommandBody(way.command, its, hint) { changed(at, way.copy(command = it)) }
				if (reads && ways.size > 1)
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.s),
					) {
						Text("Output format", style = type.muted)
						Select(
							listOf<HitRead?>(null) + HitRead.entries,
							way.read,
							{ it?.word ?: "Same as search" },
							Modifier.weight(1f).testTag("$tag-read:$at"),
						) { changed(at, way.copy(read = it)) }
						Select(
							listOf(null, true, false),
							way.nul,
							{
								when (it) {
									null -> "NUL: same as search"
									true -> "NUL after paths"
									false -> "No NUL after paths"
								}
							},
							Modifier.weight(1f).testTag("$tag-nul:$at"),
						) { changed(at, way.copy(nul = it)) }
					}
			}
		}
	}
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
	Headed(label) { Segmented(options, chosen, name, tag = tag, onChoose = onChoose) }
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
		"Menu",
		(if (search) listOf<On?>(null) else emptyList()) + On.entries,
		form.target,
		"when-target",
		{
			when (it) {
				null -> "Any"
				On.Files -> "Selected items"
				On.Folder -> "Folder background"
				On.Place -> "Sidebar item"
			}
		},
	) { onChange(form.copy(target = it)) }
	Headed("Views") {
		ScenesPicker(form.within, scenes) { onChange(form.copy(within = it)) }
	}
	Fields(
		1f to {
			Choice(
				"Selection",
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
				"Item type",
				listOf(null) + ThingKind.entries,
				form.kind,
				"when-kind",
				{
					when (it) {
						null -> "Any"
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
		hint = "zip, tar.gz",
	) { onChange(form.copy(ext = it)) }
	Fields(
		1f to {
			Typed("Name patterns", form.names, "when-names", hint = "Makefile, *.min.js") {
				onChange(form.copy(names = it))
			}
		},
		1f to {
			Typed("MIME types", form.types, "when-types", hint = "image/*, application/pdf") {
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
				Typed("Excluded extensions", form.notExt, "when-not-ext", hint = "tar.gz") {
					onChange(form.copy(notExt = it))
				}
			},
			1f to {
				Typed(
					"Required programs",
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
				Typed("Within folders", form.under, "when-under", hint = "~/Projects") {
					onChange(form.copy(under = it))
				}
			},
			1f to {
				Typed("Inside a folder containing", form.inside, "when-inside", hint = ".git") {
					onChange(form.copy(inside = it))
				}
			},
		)
		Fields(
			1f to {
				Choice(
					"Location",
					listOf(null, true, false),
					form.real,
					"when-real",
					{
						when (it) {
							null -> "Any"
							true -> "On disk"
							false -> "In a place"
						}
					},
				) { onChange(form.copy(real = it)) }
			},
			1f to {
				Choice(
					"Listed outside own folder",
					listOf(null, true, false),
					form.elsewhere,
					"when-elsewhere",
					{
						when (it) {
							null -> "Any"
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

/**
 * What a command comes to, under it: the command as it would be run for an example of what it is for, or what would
 * be opened; and that no shell runs it, which is what is most often not known of a command typed as a line.
 */
@Composable
private fun ExampleSaid(example: Example) {
	val type = LocalType.current

	when (example) {
		Example.None ->
			Unit
		is Example.Opens ->
			Text(
				example.what,
				style = type.muted,
				modifier = Modifier.testTag("example"),
			)
		is Example.Runs -> {
			Text(
				when {
					example.files.isEmpty() -> "Example:"
					example.runs.size > 1 ->
						"Example, once for each of ${example.files.joinToString(" and ")}:"
					else -> "Example, for ${example.files.joinToString(" and ")}:"
				},
				style = type.muted,
			)
			Printed(example.runs.joinToString("\n", transform = ::runSaid), "example")
		}
	}
}

/** When something is offered, in a person's words, over the conditions that say so. */
@Composable
private fun OfferedSaid(sentences: List<String>) {
	val palette = LocalPalette.current
	val type = LocalType.current

	Column(
		Modifier
			.fillMaxWidth()
			.background(palette.sunken, RoundedCornerShape(Radius.medium))
			.padding(horizontal = Space.m, vertical = Space.s)
			.testTag("said-offered"),
		verticalArrangement = Arrangement.spacedBy(Space.xxs),
	) {
		sentences.forEach { Text(it, style = type.body) }
	}
}

/**
 * What is the matter with what is typed, in a line over the buttons: what stops it being saved; or, where nothing
 * does, the programs it needs that are not here, or the program it runs that was not found.
 */
@Composable
private fun CheckedSaid(checked: Checked) {
	val palette = LocalPalette.current
	val type = LocalType.current

	val (tag, said) =
		when {
			checked.wrong.isNotEmpty() && checked.unfinished ->
				"editor-unfinished" to checked.wrong.joinToString(" ")
			checked.wrong.isNotEmpty() -> "editor-wrong" to checked.wrong.joinToString(" ")
			checked.missing != null ->
				"editor-missing" to
					"Unavailable on this computer: requires ${checked.missing}. You can still save it."
			checked.unfound != null ->
				"editor-unfound" to
					"“${checked.unfound}” isn't installed, so this will fail. Add it under Required programs " +
					"to hide this when it's missing."
			else -> return
		}

	val bad = tag == "editor-wrong"

	Row(
		Modifier.fillMaxWidth().testTag(tag),
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		GlyphIcon(
			if (bad)
				Glyph.Warning
			else
				Glyph.Info,
			if (bad)
				palette.dangerText
			else
				palette.textMuted,
			type.icon * 0.85f,
		)
		Text(
			said,
			style =
				type.muted.copy(
					color =
						if (bad)
							palette.dangerText
						else
							palette.textMuted,
				),
			maxLines = 3,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
	}
}

/** How much a search's trial found, counted: "Found 3 lines in 2 files." */
private fun found(tried: Tried.Found): String {
	fun counted(count: Int, one: String) =
		"$count ${if (count == 1)
			one
		else
			"${one}s"}"

	return if (tried.first.first().hit == null)
		"Found ${counted(tried.lines, "file")}."
	else
		"Found ${counted(tried.lines, "line")} in ${counted(tried.files, "file")}."
}

/** How a trial went. */
@Composable
private fun TriedSaid(tried: Tried?) {
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
						null -> "Couldn't start."
						0 -> "Succeeded (exit status 0)"
						else -> "Failed (exit status ${run.status})"
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
					Text("Output:", style = type.muted)
					Printed(run.output, "tried-output:$at")
				}
				if (run.errors.isNotBlank()) {
					Text("Error output:", style = type.muted)
					Printed(run.errors, "tried-errors:$at", bad = run.status != 0)
				}
				if (run.output.isBlank() && run.errors.isBlank())
					Text("No output.", style = type.muted)
			}
		}
		is Tried.Found -> {
			Printed(runSaid(tried.words), "tried-command:0")
			when (val ended = tried.ended) {
				is TextEnding.Failed ->
					Text(
						"Failed: ${ended.why.ifBlank { "no output" }}",
						style = type.body.copy(color = palette.dangerText),
						modifier = Modifier.testTag("tried-failed"),
					)
				else ->
					Text(
						(
							if (tried.first.isEmpty())
								"No results."
							else
								found(tried)
						) +
							(ended as? TextEnding.Done)
								?.note
								?.let { " Error output: $it" }
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
					"Ignored output (not ${tried.readAs}):",
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

/** How tall a form's part is, whichever is shown, so that the window does not change size from one to the next. */
private val FORM_HEIGHT = 470.dp

/**
 * The frame both windows share: the form's parts named across the top, the one [shown] under them, what is the matter
 * with what is typed over the buttons, and Test, Cancel and Save.
 */
@Composable
private fun <T> EditorModal(
	title: String,
	tag: String,
	fresh: Boolean,
	checked: Checked,
	parts: List<T>,
	shown: T,
	named: (T) -> String,
	onPart: (T) -> Unit,
	onSave: () -> Unit,
	onClose: () -> Unit,
	onTest: () -> Unit,
	content: @Composable ColumnScope.(T) -> Unit,
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
			)
		},
	) {
		Tabs(parts, shown, named, Modifier.testTag("editor-tabs"), onPart)
		// Each part scrolls from its own top, and keeps room between what it holds and its bar.
		key(shown) {
			ScrollArea(
				Modifier.fillMaxWidth().weight(1f, fill = false).height(FORM_HEIGHT),
				padding = BAR_ROOM,
				tag = "editor-form",
			) {
				Column(verticalArrangement = Arrangement.spacedBy(Space.l)) { content(shown) }
			}
		}
		// Room kept for what is the matter, whether or not anything is: the window does not move as it comes and goes.
		Box(Modifier.fillMaxWidth().heightIn(min = LocalType.current.line * 2)) {
			CheckedSaid(checked)
		}
	}
}

/** The name and the submenu a form begins with. */
@Composable
private fun Naming(
	label: String,
	submenu: String,
	example: Pair<String, String>,
	onLabel: (String) -> Unit,
	onSubmenu: (String) -> Unit,
) {
	Fields(
		1.5f to
			{
				Typed("Name", label, "form-label", hint = example.first, onText = onLabel)
			},
		1f to
			{
				Typed(
					"Submenu",
					submenu,
					"form-submenu",
					hint = example.second,
					onText = onSubmenu,
				)
			},
	)
}

/** The shortcut and the id a form ends with. */
@Composable
private fun Knowing(
	key: String,
	id: String,
	fresh: Boolean,
	onKey: (String) -> Unit,
	onId: (String) -> Unit,
) {
	Fields(
		1f to { Typed("Shortcut", key, "form-key", hint = "Ctrl+Shift+E", onText = onKey) },
		1.5f to {
			Typed(
				"Id",
				id,
				"form-id",
				help = "Can't be changed after saving.".takeIf { !fresh },
				readOnly = !fresh,
				mono = true,
				onText = onId,
			)
		},
	)
}

// ---- an action -------------------------------------------------------------------------------------------------------

/** A name and a submenu an action might have, shown where none is typed. */
private val EXAMPLE_NAMES = "Convert to PNG" to "Image/Convert"

/** What an action's command can name, each with what it becomes. */
private fun actionNames(
	asks: List<AskForm>,
	placeValues: List<String>,
): List<Pair<String, String>> =
	listOf(
		"{path}" to "File path (runs once per file)",
		"{paths}" to "All file paths (runs once)",
		"{name}" to "File name",
		"{names}" to "All file names",
		"{stem}" to "File name without extension",
		"{ext}" to "Extension",
		"{parent}" to "Containing folder",
		"{folder}" to "Current folder",
		"{entry}" to "Entry of a place",
		"{entries}" to "All entries of a place",
		"{temp}" to "Temporary folder, deleted afterwards",
	) +
		asks.filter { it.name.isNotBlank() }.map {
			"{${it.name.trim()}}" to
				"Answer to “${it.prompt}”"
		} +
		placeValues.map { "{$it}" to "Value the place was opened with" }

/** A name for a question's answer that none of [asks] has: `value`, `value2`; `dest` for a folder. */
private fun answerName(asks: List<AskForm>, folder: Boolean): String {
	val wanted = if (folder) "dest" else "value"

	return generateSequence(1) { it + 1 }
		.map {
			if (it == 1)
				wanted
			else
				"$wanted$it"
		}.first { name -> asks.none { it.name == name } }
}

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
		ActionTab.entries,
		modal.tab,
		ActionTab::label,
		onPart = { onChange(settings.copy(modal = modal.copy(tab = it))) },
		onSave = {
			onChange(settings.copy(acting = ScriptKind.Action.put(acting, spec), modal = null))
		},
		onClose = { close() },
		onTest = { onChange(settings.copy(modal = modal.copy(trying = true))) },
	) { part ->
		when (part) {
			ActionTab.Does -> {
				Naming(
					form.label,
					form.submenu,
					EXAMPLE_NAMES,
					onLabel = { typed(form.named(it, catalog.ids)) },
					onSubmenu = { typed(form.copy(submenu = it)) },
				)
				Headed("Type") {
					Segmented(Does.entries, form.does, Does::label, tag = "does") {
						typed(form.copy(does = it))
					}
				}
				when (form.does) {
					Does.Run -> {
						WaysEditor(
							form.ways,
							"command",
							"ffmpeg -i {path} {stem}.mp4",
							actionNames(
								form.asks,
								form.conditions.within
									.flatMap { places[it]?.takes.orEmpty() }
									.distinct(),
							),
							// A question added from a command's menu is asked first, and its answer named there.
							onAsk = { at, folder ->
								val name = answerName(form.asks, folder)

								typed(
									form.copy(
										asks =
											form.asks +
												AskForm(
													name,
													folder,
													if (folder)
														"Choose a folder"
													else
														"Enter a value",
												),
										ways =
											form.ways.mapIndexed { index, way ->
												if (index == at)
													way.copy(command = way.command.with("{$name}"))
												else
													way
											},
									),
								)
							},
						) { typed(form.copy(ways = it)) }
						ExampleSaid(example)
						if (form.asks.isNotEmpty())
							Headed(
								"Prompts",
								help = "Use each answer in the command as {name}.",
							) {
								form.asks.forEachIndexed { at, ask ->
									fun asking(to: AskForm) =
										typed(
											form.copy(
												asks =
													form.asks.mapIndexed { index, was ->
														if (index == at)
															to
														else
															was
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
											Modifier.width(100.dp).testTag("ask-name:$at"),
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
											hint = "Prompt",
										)
										Field(
											ask.start,
											{ asking(ask.copy(start = it)) },
											Modifier.width(120.dp).testTag("ask-start:$at"),
											hint = "Default",
										)
										ToolButton(
											Glyph.Close,
											"Remove prompt",
											enabled = true,
											Modifier.testTag("ask-remove:$at"),
										) {
											typed(
												form.copy(asks = form.asks.filterIndexed { index, _ -> index != at }),
											)
										}
									}
								}
							}
					}
					Does.Folder -> {
						Typed(
							"Folder",
							form.folder,
							"show-folder",
							hint = "{parent}",
							mono = true,
						) { typed(form.copy(folder = it)) }
						Fields(
							1f to {
								Typed(
									"Then select",
									form.folderChoose,
									"show-choose",
									hint = "{name}",
									mono = true,
								) { typed(form.copy(folderChoose = it)) }
							},
							1f to {
								Choice("Open it in", Opens.entries, form.opens, "opens", Opens::label) {
									typed(form.copy(opens = it))
								}
							},
						)
						ExampleSaid(example)
					}
					Does.Place -> {
						Headed("Place") {
							Select(
								listOf("") + places.keys,
								form.place.takeIf { it in places }.orEmpty(),
								{ it.ifEmpty { "Choose a place…" } },
								Modifier.fillMaxWidth().testTag("show-place"),
							) { typed(form.copy(place = it)) }
						}
						places[form.place]?.takes?.forEach { name ->
							Typed(
								"Value for “$name”",
								form.placeWith[name].orEmpty(),
								"show-with:$name",
								hint = "{path}",
								mono = true,
							) { typed(form.copy(placeWith = form.placeWith + (name to it))) }
						}
						Choice("Open it in", Opens.entries, form.opens, "opens", Opens::label) {
							typed(form.copy(opens = it))
						}
						ExampleSaid(example)
					}
					Does.Like -> {
						Headed(
							"Action",
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
								hint = "Empty: ask",
								mono = true,
							) { typed(form.copy(given = form.given + (question.name to it))) }
						}
						Typed("Then select", form.choose, "then-choose", hint = "{stem}", mono = true) {
							typed(form.copy(choose = it))
						}
						ExampleSaid(example)
					}
				}
			}
			ActionTab.Runs -> {
				if (form.does == Does.Run) {
					Headed("Run", help = form.running.help) {
						Segmented(Running.entries, form.running, Running::label, tag = "running") {
							typed(form.copy(running = it))
						}
					}
					if (form.running == Running.Watched) {
						CheckBox(
							"Refresh when finished",
							form.reread,
							Modifier.testTag("then-reread"),
						) { typed(form.copy(reread = it)) }
						Typed(
							"Then select",
							form.choose,
							"then-choose",
							hint = "{stem}.png",
							mono = true,
						) { typed(form.copy(choose = it)) }
						Fields(
							1f to {
								Typed(
									"Move results from",
									form.bringFrom,
									"then-from",
									hint = "{temp}",
									mono = true,
								) { typed(form.copy(bringFrom = it)) }
							},
							1f to {
								Typed("To", form.bringTo, "then-to", hint = "{dest}", mono = true) {
									typed(form.copy(bringTo = it))
								}
							},
						)
					}
				} else {
					Text("This type has no run options.", style = type.muted)
				}
				Typed(
					"Confirmation message",
					form.confirm,
					"confirm",
					hint = "Optional",
				) { typed(form.copy(confirm = it)) }
			}
			ActionTab.Offered -> {
				OfferedSaid(offeredSaid(spec.conditions, On.Files, catalog.scenes))
				ConditionsEditor(form.conditions, catalog.scenes, search = false) {
					typed(form.copy(conditions = it))
				}
				CheckBox(
					"Run on double-click instead of opening",
					form.default,
					Modifier.testTag("form-default"),
				) { typed(form.copy(default = it)) }
				Knowing(
					form.key,
					form.id,
					form.fresh,
					onKey = { typed(form.copy(key = it)) },
					onId = { typed(form.copy(id = it)) },
				)
			}
		}
	}
}

// ---- a text search -----------------------------------------------------------------------------------------------------

/** What a search's command is given, in a sentence. */
private fun givenSaid(takes: Takes): String =
	when (takes) {
		Takes.Files ->
			"Receives file paths on standard input, NUL-separated (as for xargs -0)."
		Takes.Lines ->
			"Receives the found lines on standard input, one per line."
	}

/** What a search's command must print, in a sentence, and a line of it. */
private fun printsSaid(form: SearchForm): Pair<String, String> {
	val position =
		when (form.positions) {
			Positions.Lines -> "line number"
			Positions.Offsets -> "byte offset"
		}

	return when (form.read) {
		HitRead.Lines ->
			if (form.takes == Takes.Lines)
				"One kept line per line: its number in the input, a colon, and the text." to
					"3:val answer = 42"
			else if (form.nul)
				"One result per line: path, NUL, $position, a colon, and the text." to
					"src/main.kt␀12:val answer = 42"
			else
				"One result per line: path, $position and text, separated by colons." to
					"src/main.kt:12:val answer = 42"
		HitRead.RgJson -> "The output of rg --json." to """{"type":"match","data":{…}}"""
		HitRead.Json ->
			"One JSON object per line: path, at ($position), text, and marks (match ranges)." to
				"""{"path":"src/main.kt","at":12,"text":"val answer = 42","marks":[[4,10]]}"""
		HitRead.Paths ->
			(
				if (form.nul)
					"File paths, NUL-separated."
				else
					"One file path per line."
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

	// What a command for one line found can name.
	val ofLine =
		listOf(
			"{path}" to "File path",
			(
				if (form.positions == Positions.Lines)
					"{line}"
				else
					"{offset}"
			) to
				if (form.positions == Positions.Lines)
					"Line number"
				else
					"Byte offset",
			"{text}" to "Line text",
			"{query}" to "Search text",
		)

	EditorModal(
		when {
			!form.fresh -> "Edit “${catalog[form.id]?.spec?.label ?: form.label}”"
			form.label.isBlank() -> "New text search"
			else -> "New text search: ${form.label}"
		},
		"search-editor",
		form.fresh,
		checked,
		SearchTab.entries,
		modal.tab,
		SearchTab::label,
		onPart = { onChange(settings.copy(modal = modal.copy(tab = it))) },
		onSave = {
			onChange(settings.copy(acting = ScriptKind.Search.put(acting, spec), modal = null))
		},
		onClose = { close() },
		onTest = { onChange(settings.copy(modal = modal.copy(trying = true))) },
	) { part ->
		when (part) {
			SearchTab.Does -> {
				Naming(
					form.label,
					form.submenu,
					"Find TODOs…" to "Search",
					onLabel = { typed(form.named(it, catalog.ids)) },
					onSubmenu = { typed(form.copy(submenu = it)) },
				)
				Headed("Input", help = givenSaid(form.takes)) {
					Segmented(
						Takes.entries,
						form.takes,
						{
							when (it) {
								Takes.Files -> "Files in view"
								Takes.Lines -> "Previous results"
							}
						},
						tag = "takes",
					) { typed(form.copy(takes = it)) }
				}
				WaysEditor(
					form.ways,
					"command",
					"xargs -0 rg --json {options} -e {query} --",
					listOf(
						"{query}" to "Search text",
						"{options}" to "Arguments from the options",
						"{folder}" to "Folder the command runs in",
					),
					reads = true,
				) { typed(form.copy(ways = it)) }
				ExampleSaid(example)
				Headed(
					"Query",
					help =
						when (form.query) {
							QueryUse.Typed ->
								"Reruns the command with {query} as you type."
							QueryUse.Narrows ->
								"Runs the command once; typing filters its output."
						},
				) {
					Segmented(
						QueryUse.entries,
						form.query,
						{
							when (it) {
								QueryUse.Typed -> "Passed to command"
								QueryUse.Narrows -> "Filters output"
							}
						},
						tag = "query-use",
					) { typed(form.copy(query = it)) }
				}
			}
			SearchTab.Prints -> {
				Headed("Output format", help = prints) {
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
				}
				Printed(printed, "prints-example")
				if (form.read == HitRead.Lines || form.read == HitRead.Paths)
					CheckBox(
						"Paths end with NUL (grep -Z, rg -0)",
						form.nul,
						Modifier.testTag("nul"),
					) { typed(form.copy(nul = it)) }
				if (form.read != HitRead.Paths)
					Choice(
						"Position",
						Positions.entries,
						form.positions,
						"positions",
						{
							when (it) {
								Positions.Lines -> "Line number"
								Positions.Offsets -> "Byte offset"
							}
						},
					) { typed(form.copy(positions = it)) }
				Headed(
					"Options",
					help = "Toggles next to the search field. Each adds arguments at {options}.",
				) {
					form.options.forEachIndexed { at, option ->
						fun opting(to: OptionForm) =
							typed(
								form.copy(
									options =
										form.options.mapIndexed { index, was ->
											if (index == at)
												to
											else
												was
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
								hint = "On: -s",
								style = type.mono,
							)
							Field(
								option.off,
								{ opting(option.copy(off = it)) },
								Modifier.weight(1f).testTag("option-off:$at"),
								hint = "Off: -i",
								style = type.mono,
							)
							ToolButton(
								Glyph.Close,
								"Remove option",
								enabled = true,
								Modifier.testTag("option-remove:$at"),
							) {
								typed(
									form.copy(options = form.options.filterIndexed { index, _ -> index != at }),
								)
							}
						}
					}
					PushButton(
						"Add option",
						Emphasis.Quiet,
						enabled = true,
						Modifier.testTag("option-add"),
						glyph = Glyph.Plus,
					) { typed(form.copy(options = form.options + OptionForm())) }
				}
			}
			SearchTab.Shows -> {
				Text(
					"Without a preview command, files are shown as plain text.",
					style = type.muted,
				)
				if (form.previews.isEmpty())
					PushButton(
						"Add preview command",
						Emphasis.Secondary,
						enabled = true,
						Modifier.testTag("preview-way-add"),
						glyph = Glyph.Plus,
					) { typed(form.copy(previews = listOf(WayForm()))) }
				else
					WaysEditor(
						form.previews,
						"preview",
						"bat --color=always --style=plain {path}",
						ofLine,
						label = "Preview command",
						least = 0,
					) { typed(form.copy(previews = it)) }
				CheckBox(
					"Open results with a custom command",
					form.opening,
					Modifier.testTag("opening"),
				) { typed(form.copy(opening = it)) }
				if (form.opening) {
					WaysEditor(
						listOf(WayForm(command = form.open)),
						"open",
						"code --goto {path}:{line}",
						ofLine,
						label = "Open command",
						fallbacks = false,
					) { ways -> typed(form.copy(open = ways.first().command)) }
					CheckBox(
						"Run in terminal",
						form.openInTerminal,
						Modifier.testTag("open-terminal"),
					) {
						typed(form.copy(openInTerminal = it))
					}
				}
			}
			SearchTab.Offered -> {
				if (form.takes == Takes.Files) {
					OfferedSaid(offeredSaid(spec.conditions, null, catalog.scenes))
					ConditionsEditor(form.conditions, catalog.scenes, search = true) {
						typed(form.copy(conditions = it))
					}
				} else {
					OfferedSaid(
						listOf("Available as a later step of a search, once lines have been found."),
					)
				}
				Knowing(
					form.key,
					form.id,
					form.fresh,
					onKey = { typed(form.copy(key = it)) },
					onId = { typed(form.copy(id = it)) },
				)
			}
		}
	}
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
				TriedSaid(trial.result)
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
		"Runs the command on the files below and shows its output. Nothing is saved.",
		settings,
		onChange,
		work = { tried(acting, spec, it, world, commands) },
	) { trying ->
		Headed("Files", help = "One path per line.") {
			TextArea(
				trial.given,
				{ trying(trial.copy(given = it)) },
				Modifier.fillMaxWidth().testTag("trial-given"),
				hint = "~/Pictures/holiday.jpg",
			)
		}
		asked.forEach { name ->
			Headed("{$name}") {
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
	// Its ways, and the one it is run by here: any can be tried, installed here or not.
	val read = remember(acting, spec) { searchTried(acting, spec, world, null) }
	val ways = (read as? Reading.Right)?.value?.ways

	TrialModal(
		modal,
		"Test “${spec.label}”",
		"Runs the command and shows the results, and any output that couldn't be parsed. Nothing is saved.",
		settings,
		onChange,
		work = { tried(acting, spec, it, world, commands) },
	) { trying ->
		if (ways != null && ways.all.size > 1)
			Headed(
				"Way",
				help = "The first with its programs installed is run; any can be tried.",
			) {
				Segmented(
					ways.all.indices.toList(),
					trial.way ?: ways.at,
					{ at ->
						val way = ways.all[at]
						val named = way.programs.joinToString(" + ").ifEmpty { "Way ${at + 1}" }

						if (way.needsAll(world::installed))
							named
						else
							"$named (not installed)"
					},
					tag = "trial-way",
				) { trying(trial.copy(way = it, result = null)) }
			}
		Headed(
			if (lines)
				"Input lines"
			else
				"Files or folders",
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
		Headed("Query") {
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
