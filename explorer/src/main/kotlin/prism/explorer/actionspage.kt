package prism.explorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap
import prism.browser.Act
import prism.browser.ButtonRow
import prism.browser.Card
import prism.browser.Chip
import prism.browser.Emphasis
import prism.browser.EmptyState
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.GlyphIcon
import prism.browser.Labelled
import prism.browser.LazyScrollArea
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.MenuButton
import prism.browser.Modal
import prism.browser.Notice
import prism.browser.On
import prism.browser.PushButton
import prism.browser.Radius
import prism.browser.Select
import prism.browser.Space
import prism.browser.Switch
import prism.browser.Takes
import prism.browser.Tone
import prism.browser.ToolButton
import prism.browser.World
import prism.browser.menu
import prism.preferences.ActionSpec
import prism.preferences.Declared
import prism.preferences.TextSearchSpec
import prism.browser.Page as PageLayout

/*
 * The two pages that list what the menus offer beyond prism's own: the actions done to files, and the text searches.
 * Each lists everything there is of its kind, prism's and the user's alike, under the window it is offered in, with
 * a switch that turns it off, what it is for, and what is the matter with it where anything is: a program it needs
 * that is not installed, or how it is written wrong. One is made, changed, copied and moved in a window over the page
 * (actioneditor.kt); one of prism's that was changed is put back, and one of the user's deleted, from its row.
 *
 * A list of several hundred is still one to find something in: it is narrowed by words (a name, an id, an extension, a
 * program), to one window, to what a file of a given name would be offered, and to what is the user's, changed, off,
 * unavailable or wrong; a window's entries fold away under its heading; and the rows are drawn only as they are
 * scrolled to.
 */

/** What a page calls its kind, where the two differ. */
private class Wording(
	val title: String,
	val description: String,
	val one: String,
	val many: String,
	val new: String,
	val hint: String,
)

private val ScriptKind<*>.wording: Wording get() =
	when (this) {
		ScriptKind.Action ->
			Wording(
				"File actions",
				"Commands the context menus offer for files and folders, beyond prism's own. Turn off the ones " +
					"you don't use, change them, or add your own.",
				"action",
				"actions",
				"New action",
				"Filter by name, id, extension or program (Ctrl+F)",
			)
		ScriptKind.Search ->
			Wording(
				"Text searches",
				"Commands that look inside the files a window shows and list the lines they find. Turn off the " +
					"ones you don't use, change them, or add your own.",
				"search",
				"searches",
				"New search",
				"Filter by name, id or program (Ctrl+F)",
			)
	}

/** Which of [kind]'s entries these settings show. */
internal fun Settings.shown(kind: ScriptKind<*>): Narrowing =
	when (kind) {
		ScriptKind.Action -> actionsShown
		ScriptKind.Search -> searchesShown
	}

/** These, showing those of [kind]'s entries [narrowing] says. */
internal fun Settings.showing(kind: ScriptKind<*>, narrowing: Narrowing): Settings =
	when (kind) {
		ScriptKind.Action -> copy(actionsShown = narrowing)
		ScriptKind.Search -> copy(searchesShown = narrowing)
	}

/** Whether each program is installed, as [world] says, asked of it once: a page asks of the same few hundreds of times. */
@Composable
internal fun rememberInstalled(world: World): (String) -> Boolean =
	remember(world) {
		val known = ConcurrentHashMap<String, Boolean>()

		return@remember { program: String ->
			known.getOrPut(program) {
				world.installed(program)
			}
		}
	}

/**
 * The page of [kind]: everything there is of it, as [settings] have it, in [world]. [filter] is the field Ctrl+F goes
 * to; [foot] is drawn under the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <S : Declared> ScriptsPage(
	kind: ScriptKind<S>,
	settings: Settings,
	world: World,
	filter: FocusRequester,
	onChange: (Settings) -> Unit,
	foot: @Composable ColumnScope.() -> Unit = {},
) {
	val type = LocalType.current
	val wording = kind.wording
	val installed = rememberInstalled(world)
	val acting = settings.acting
	val catalog = remember(acting, installed) { kind.catalog(acting, installed) }
	val narrowing = settings.shown(kind)
	val groups = remember(catalog, narrowing) { catalog.groups(narrowing) }
	val shown = groups.flatMap { it.entries }.distinctBy { it.id }.size

	fun narrow(to: Narrowing) = onChange(settings.showing(kind, to))

	fun acted(to: Acting) = onChange(settings.copy(acting = to))

	fun open(modal: ActingModal) = onChange(settings.copy(modal = modal))

	PageLayout(wording.title, wording.description, scrolls = false) {
		Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.s),
			) {
				Field(
					narrowing.words,
					{ narrow(narrowing.copy(words = it)) },
					Modifier.weight(1f).testTag("scripts-filter"),
					hint = wording.hint,
					leading = Glyph.Search,
					focus = filter,
					onClear = { narrow(narrowing.copy(words = "")) },
				)
				// The window: all of them, or one of those something here is offered in, found by typing where there
				// are many. Where everything is offered in the same one, there is none to choose.
				if (catalog.offeredIn.size > 1)
					Select(
						listOf<Scene?>(null) + catalog.offeredIn,
						narrowing.scene,
						{ scene -> scene?.let(catalog.scenes::titled) ?: "All windows" },
						Modifier.width(190.dp).testTag("scripts-scene"),
					) { narrow(narrowing.copy(scene = it)) }
				if (kind == ScriptKind.Action)
					Field(
						narrowing.named,
						{ narrow(narrowing.copy(named = it)) },
						Modifier.width(170.dp).testTag("scripts-named"),
						hint = "For a file: zip",
						leading = Glyph.File,
						onClear = { narrow(narrowing.copy(named = "")) },
					)
				PushButton(
					wording.new,
					Emphasis.Primary,
					enabled = true,
					Modifier.testTag("script-new"),
					glyph = Glyph.Plus,
				) {
					// Made where the page is looking: for the window it shows, where it shows one.
					val within = listOfNotNull((narrowing.scene as? Scene.In)?.word)

					open(
						when (kind) {
							ScriptKind.Action ->
								ActingModal.EditingAction(ActionForm(conditions = WhenForm(within = within)))
							ScriptKind.Search ->
								ActingModal.EditingSearch(
									SearchForm(conditions = WhenForm(target = null, within = within)),
								)
						},
					)
				}
			}
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.s),
			) {
				catalog.marked.forEach { (mark, count) ->
					Chip(
						"$count ${mark.label}",
						Modifier.testTag("mark:${mark.name}"),
						strong = mark in narrowing.marks,
					) {
						narrow(
							narrowing.copy(
								marks =
									if (mark in narrowing.marks)
										narrowing.marks - mark
									else
										narrowing.marks + mark,
							),
						)
					}
				}
				Text(
					if (narrowing.narrowed)
						"$shown of ${catalog.entries.size} ${wording.many} shown"
					else
						"${catalog.entries.size} ${wording.many}, " +
							"${catalog.entries.count { it.offered }} available",
					style = type.muted,
					textAlign = androidx.compose.ui.text.style.TextAlign.End,
					maxLines = 1,
					modifier = Modifier.weight(1f).testTag("scripts-count"),
				)
				if (narrowing.narrowed)
					PushButton(
						"Clear filters",
						Emphasis.Quiet,
						enabled = true,
						Modifier.testTag("scripts-clear"),
					) { narrow(narrowing.cleared()) }
			}
		}
		if (catalog.unread.isNotEmpty())
			Card {
				catalog.unread.forEach { unread ->
					Notice(
						"One of your ${wording.many} in the preferences file can't be read" +
							unread.id
								?.let { " (“$it”)" }
								.orEmpty(),
						Tone.Bad,
						Modifier.testTag("unread:${unread.at}"),
						detail = unread.why,
					) {
						PushButton(
							"Remove it",
							Emphasis.Secondary,
							enabled = true,
							Modifier.testTag("unread-remove:${unread.at}"),
						) { acted(kind.withoutAt(acting, unread.at)) }
					}
				}
			}
		Card(Modifier.weight(1f)) {
			if (groups.isEmpty())
				EmptyState(
					Glyph.Search,
					if (narrowing.narrowed)
						"No ${wording.many} match"
					else
						"No ${wording.many}",
					if (narrowing.narrowed)
						"Change or clear the filters above."
					else
						null,
					Modifier.fillMaxSize(),
				)
			else
				LazyScrollArea(
					Modifier.fillMaxSize(),
					line = type.row * 1.6f,
					padding = PaddingValues(horizontal = Space.xs, vertical = Space.xs),
					tag = "scripts",
					scrollbarTag = "scripts-scrollbar",
				) {
					groups.forEach { group ->
						val folded = group.scene in narrowing.folded

						item(key = "group:${group.scene}") {
							SceneHeading(catalog.scenes.titled(group.scene), group.entries.size, folded) {
								narrow(
									narrowing.copy(
										folded =
											if (folded)
												narrowing.folded - group.scene
											else
												narrowing.folded + group.scene,
									),
								)
							}
						}
						if (!folded)
							items(group.entries, key = { "${group.scene}:${it.id}" }) { entry ->
								EntryRow(
									entry,
									catalog,
									onEnable = { acted(acting.enabling(entry.id, it)) },
									onEdit = { open(ActingModal.editing(entry.spec)) },
									onCopy = {
										open(ActingModal.editing(copyOf(entry.spec, catalog.ids), fresh = true))
									},
									onMove = {
										open(ActingModal.Moving(kind, entry.id, Retarget.of(entry.spec)))
									},
									onRemove = { acted(kind.without(acting, entry.id)) },
								)
							}
					}
				}
		}
		foot()
	}
}

/** A window's name over what is offered in it, with how many there are: clicked, they fold away, or come back. */
@Composable
private fun SceneHeading(
	title: String,
	count: Int,
	folded: Boolean,
	onFold: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val hovered by source.collectIsHoveredAsState()

	Row(
		Modifier
			.fillMaxWidth()
			.padding(top = Space.s)
			.background(
				if (hovered)
					palette.hover
				else
					Color.Transparent,
				RoundedCornerShape(Radius.medium),
			).hoverable(source)
			.combinedFold(source, onFold)
			.testTag("scene:$title")
			.padding(horizontal = Space.s, vertical = Space.s),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		GlyphIcon(
			if (folded)
				Glyph.Chevron
			else
				Glyph.ChevronDown,
			palette.textMuted,
			type.icon * 0.7f,
		)
		Text(title, style = type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text("$count", style = type.muted)
	}
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedFold(
	source: MutableInteractionSource,
	onFold: () -> Unit,
): Modifier = combinedClickable(source, indication = null, onClick = onFold)

/** A small label on a row: where it comes from. */
@Composable
internal fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
	val type = LocalType.current
	val shape = RoundedCornerShape(Radius.small)

	Text(
		text,
		style = type.muted.copy(color = color),
		maxLines = 1,
		modifier =
			modifier
				.border(1.dp, color.copy(alpha = 0.45f), shape)
				.padding(horizontal = Space.xs + Space.xxs),
	)
}

/** What [spec] is for and does, in a line under its name. */
private fun briefOf(spec: Declared, catalog: Catalog<*>): String =
	when (spec) {
		is ActionSpec ->
			listOfNotNull(
				offeredBrief(spec.conditions, On.Files).takeIf { it.isNotEmpty() },
				doesBrief(spec) { id -> catalog[id]?.spec?.label },
			)
		is TextSearchSpec ->
			listOfNotNull(
				if (spec.takes == Takes.Lines.word)
					"A further step, on lines already found"
				else
					"Looks in files",
				offeredBrief(spec.conditions, null).takeIf { it.isNotEmpty() },
				doesBrief(spec) { null },
			)
	}.joinToString(" · ")

/**
 * One action or text search: its switch, its name (after the submenu it is in), what it is for, and what is the matter
 * with it where anything is; its shortcut; and what can be done with it. A double click opens it to be changed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <S : Declared> EntryRow(
	entry: Entry<S>,
	catalog: Catalog<S>,
	onEnable: (Boolean) -> Unit,
	onEdit: () -> Unit,
	onCopy: () -> Unit,
	onMove: () -> Unit,
	onRemove: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val hovered by source.collectIsHoveredAsState()
	val spec = entry.spec
	val trouble = entry.trouble

	Row(
		Modifier
			.fillMaxWidth()
			.heightIn(min = type.row * 1.6f)
			.background(
				if (hovered)
					palette.hover
				else
					Color.Transparent,
				RoundedCornerShape(Radius.medium),
			).hoverable(source)
			.combinedClickable(source, indication = null, onDoubleClick = onEdit) {}
			.testTag("script:${entry.id}")
			.padding(horizontal = Space.s, vertical = Space.xs),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.m),
	) {
		Switch(entry.enabled, Modifier.testTag("script-on:${entry.id}"), onChange = onEnable)
		Column(
			Modifier.weight(1f).alpha(if (entry.offered) 1f else 0.6f),
			verticalArrangement = Arrangement.spacedBy(Space.xxs),
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.s),
			) {
				Text(
					buildAnnotatedString {
						spec.submenu?.takeIf { it.isNotBlank() }?.let { submenu ->
							withStyle(SpanStyle(color = palette.textMuted)) {
								append(submenu.split('/').joinToString(" ▸ ") { it.trim() } + " ▸ ")
							}
						}
						append(spec.label)
					},
					style = type.body,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f, fill = false),
				)
				when (entry.source) {
					is Source.Own ->
						Pill(
							"Yours",
							palette.accentText,
							Modifier.testTag("own:${entry.id}"),
						)
					is Source.Changed ->
						Pill("Changed", palette.accentText, Modifier.testTag("changed:${entry.id}"))
					is Source.Shipped -> Unit
				}
			}
			Text(
				when (trouble) {
					is Trouble.Missing -> "Not available here: needs ${trouble.needs}"
					is Trouble.Wrong -> trouble.why
					null -> briefOf(spec, catalog).ifEmpty { spec.id }
				},
				style =
					type.muted.copy(
						color =
							if (trouble is Trouble.Wrong)
								palette.dangerText
							else
								palette.textMuted,
					),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.testTag("script-note:${entry.id}"),
			)
		}
		spec.key?.takeIf { it.isNotBlank() }?.let {
			Text(it, style = type.muted, maxLines = 1)
		}
		Row(verticalAlignment = Alignment.CenterVertically) {
			ToolButton(
				Glyph.Edit,
				"Edit",
				enabled = true,
				Modifier.testTag("script-edit:${entry.id}"),
			) {
				onEdit()
			}
			ToolButton(
				Glyph.Copy,
				"Duplicate",
				enabled = true,
				Modifier.testTag("script-copy:${entry.id}"),
				onClick = onCopy,
			)
			MenuButton(
				Glyph.More,
				"More",
				menu = {
					menu {
						item("Move or copy to…", RowDeed.Move)
						when (entry.source) {
							is Source.Changed -> item("Reset to default", RowDeed.Remove)
							is Source.Own -> item("Delete", RowDeed.Remove)
							is Source.Shipped -> Unit
						}
					}
				},
				onAction = {
					when (it) {
						RowDeed.Move -> onMove()
						RowDeed.Remove -> onRemove()
					}
				},
				Modifier.testTag("script-more:${entry.id}"),
			)
		}
	}
}

/** What a row's menu does. */
private enum class RowDeed {
	Move,

	/** Reset, of one of prism's; delete, of one of the user's: either way what the user wrote is taken away. */
	Remove,
}

/**
 * The windows something is offered in, chosen: each a chip that is taken away by its ×, and a list of the rest to add
 * from. None chosen is anywhere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScenesPicker(
	within: List<String>,
	scenes: Scenes,
	onChange: (List<String>) -> Unit,
) {
	val type = LocalType.current
	val rest = (scenes.words + within).distinct().filter { it !in within }

	FlowRow(
		Modifier.testTag("scenes"),
		verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically),
		horizontalArrangement = Arrangement.spacedBy(Space.s),
	) {
		if (within.isEmpty())
			Text(
				"Any window",
				style = type.body,
				modifier = Modifier.align(Alignment.CenterVertically).padding(end = Space.s),
			)
		within.forEach { word ->
			Chip(
				scenes.titled(Scene.In(word)),
				Modifier.testTag("scene-in:$word"),
				removeTag = "scene-out:$word",
				onRemove = { onChange(within - word) },
			) {}
		}
		if (rest.isNotEmpty())
			Select(
				listOf<String?>(null) + rest,
				null,
				{ word ->
					word?.let { scenes.titled(Scene.In(it)) }
						?: if (within.isEmpty()) "Only in…" else "Also in…"
				},
				Modifier.width(220.dp).testTag("scene-add"),
			) { added -> added?.let { onChange(within + it) } }
	}
}

/**
 * The window that moves or copies something to other windows and extensions: where it is to be offered, the extensions
 * it is to be for, and what that comes to, said; then Move, which changes it, or Copy, which leaves it and makes
 * another.
 */
@Composable
internal fun MoveModal(
	modal: ActingModal.Moving,
	settings: Settings,
	world: World,
	onChange: (Settings) -> Unit,
) {
	val type = LocalType.current
	val installed = rememberInstalled(world)
	val catalog =
		remember(settings.acting, modal.kind, installed) {
			modal.kind.catalog(settings.acting, installed)
		}
	val entry = catalog[modal.id]

	fun close() = onChange(settings.copy(modal = null))

	if (entry == null) {
		close()

		return
	}

	fun typed(to: Retarget) = onChange(settings.copy(modal = modal.copy(to = to)))

	fun done(copy: Boolean) =
		onChange(
			settings.copy(
				acting = modal.kind.moved(settings.acting, modal.id, modal.to, copy),
				modal = null,
			),
		)

	val unsaid =
		when (modal.kind) {
			ScriptKind.Action -> On.Files
			ScriptKind.Search -> null
		}

	Modal(
		"Move or copy “${entry.spec.label}”",
		onDismiss = ::close,
		tag = "move-modal",
		buttons = {
			ButtonRow(
				Act("Move", "move-apply", modal.to != Retarget.of(entry.spec)) { done(copy = false) },
				secondary =
					listOf(
						Act("Cancel", "move-cancel", onClick = ::close),
						Act("Copy", "move-copy") { done(copy = true) },
					),
			)
		},
	) {
		Labelled(
			"Offered in",
			help = "The kinds of window whose menus offer it.",
		) {
			ScenesPicker(
				modal.to.within,
				catalog.scenes,
			) { typed(modal.to.copy(within = it)) }
		}
		Labelled(
			"For files ending in",
			help = "Extensions without the dot, separated by commas. Leave empty for any file.",
		) {
			Field(
				modal.to.ext,
				{ typed(modal.to.copy(ext = it)) },
				Modifier.fillMaxWidth().testTag("move-ext"),
				hint = "zip, tar.gz",
			)
		}
		Column(
			Modifier.testTag("move-said"),
			verticalArrangement = Arrangement.spacedBy(Space.xxs),
		) {
			offeredSaid(modal.to.applied(entry.spec).conditions, unsaid, catalog.scenes).forEach {
				Text(it, style = type.muted)
			}
		}
		Text(
			when (entry.source) {
				is Source.Own -> "Move changes this ${modal.kind.wording.one}. Copy leaves it and adds another."
				else ->
					"Move changes this built-in ${modal.kind.wording.one}, which you can reset later. " +
						"Copy leaves it and adds one of your own."
			},
			style = type.muted,
		)
	}
}
