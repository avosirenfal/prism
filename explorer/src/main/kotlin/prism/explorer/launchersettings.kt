package prism.explorer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import prism.browser.Bindings
import prism.browser.Card
import prism.browser.Chord
import prism.browser.Edits
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.LazyScrollArea
import prism.browser.LocalType
import prism.browser.Section
import prism.browser.SettingRow
import prism.browser.Space
import prism.browser.Stepper
import prism.browser.Switch
import prism.desktop.Application
import prism.launcher.LocalIconImages
import prism.launcher.launcherBindings
import prism.preferences.LauncherPreferences
import prism.preferences.Preferences
import prism.preferences.Scales
import prism.preferences.least
import prism.preferences.most
import prism.preferences.plus
import prism.browser.Page as PageLayout

/*
 * The launcher's settings, in a window of their own made as the preferences' is (settingsframe.kt): pages down the
 * left, the one chosen beside them, nothing written until it is applied. How it looks, what it finds and how it
 * behaves are one page; its keys another, the same page that rebinds an explorer's (bindingspage.kt); and which of
 * the applications installed it lists, a third.
 */

/** Which of the window's pages is shown. */
enum class LauncherPage(
	val label: String,
	val glyph: Glyph,
) {
	General("Launcher", Glyph.Search),
	Bindings("Key bindings", Glyph.Keyboard),
	Applications("Applications", Glyph.ViewGrid),
	Aliases("Aliases", Glyph.At),
	Indexing("Indexing", Glyph.Filter),
}

/**
 * The launcher's settings as their window edits them: [base], the preferences as they were when it opened, or were
 * last applied; the [launcher]'s own settings, its keys aside, which are being rebound ([rebinding]: none that types,
 * since the launcher's keyboard is always in its field); the [page] shown; and what is typed over the list of
 * applications to find one ([finding]); and the search aliases, which are every search's and the preferences'
 * window's to edit too ([aliasing]).
 */
data class LauncherSettings(
	val base: Preferences,
	val launcher: LauncherPreferences = base.launcher,
	val rebinding: Rebinding =
		Rebinding(launcherBindings(base.launcher), takes = Taking.NothingTyped),
	val page: LauncherPage = LauncherPage.General,
	val finding: String = "",
	val aliasing: AliasEdits = AliasEdits.of(base.aliases),
	/** What the index leaves out, which is every search's too, as the Indexing page has it. */
	val excluding: ExcludingEdits = ExcludingEdits.of(base.excluding),
) {
	/** What stops these from being applied, where anything does: an alias changed here with something wrong with it. */
	val blocked: String?
		get() =
			AliasEdits.UNFIT.takeIf {
				aliasing != AliasEdits.of(base.aliases) &&
					!aliasing.fit
			}

	/** The launcher's settings as these would write them: what the page set, with the keys as they are bound. */
	private val edited: LauncherPreferences
		get() =
			launcher.copy(
				bindings =
					if (rebinding.bindings != launcherBindings(base.launcher))
						rebinding.bindings.differences()
					else
						base.launcher.bindings,
			)

	/** What the window would write over its preferences, as these are. */
	val written: Preferences get() = over(base)

	/** Whether anything is changed from what the window opened on, or last applied. */
	val changed: Boolean get() = written != base

	/**
	 * [current], with what was changed here: the launcher's settings are this window's alone to change, and the
	 * aliases are written where they were changed here and nothing is wrong with them.
	 */
	fun over(current: Preferences): Preferences =
		current.copy(
			launcher =
				if (edited != base.launcher)
					edited
				else
					current.launcher,
			aliases = aliasing.written?.takeIf { it != base.aliases } ?: current.aliases,
			excluding = excluding.kept.takeIf { it != base.excluding } ?: current.excluding,
		)

	/** These, applied: the preferences as they now are ([now]) what the window edits from, and where it is, kept. */
	fun applied(now: Preferences): LauncherSettings =
		LauncherSettings(now, page = page, finding = finding).let {
			it.copy(rebinding = it.rebinding.finding(rebinding.finding))
		}

	/** These, with the launcher's own settings changed by [change]. */
	fun set(change: LauncherPreferences.() -> LauncherPreferences): LauncherSettings =
		copy(launcher = launcher.change())
}

/**
 * [edits] with the window's settings changed [from] one [to] another: a step to undo where what the window would write
 * changed; none where only where the window is did.
 */
fun Edits<LauncherSettings>.changed(
	from: LauncherSettings,
	to: LauncherSettings,
): Edits<LauncherSettings> =
	if (to.written != from.written)
		edited(from)
	else
		this

/**
 * The launcher's settings' window over [settings], each change reported to [onChange]: applied by [onApply], and left
 * by [onClose]. [applications] are those installed, to be listed by the launcher or left out. [mouseChords] are the
 * mouse's buttons pressed over the window, which a binding waiting for a chord takes; undo and redo, as [keys] bind
 * them, are [onUndo] and [onRedo].
 */
@Composable
fun LauncherSettingsView(
	settings: LauncherSettings,
	applications: List<Application>,
	onChange: (LauncherSettings) -> Unit,
	onApply: () -> Unit,
	onClose: () -> Unit,
	mouseChords: Flow<Chord> = emptyFlow(),
	keys: Bindings = Bindings.DEFAULT,
	onUndo: () -> Unit = {},
	onRedo: () -> Unit = {},
) {
	val filter = remember { FocusRequester() }

	SettingsFrame(
		LauncherPage.entries,
		settings.page,
		LauncherPage::label,
		LauncherPage::glyph,
		onPage = { onChange(settings.copy(page = it)) },
		rebinding = settings.rebinding.takeIf { settings.page == LauncherPage.Bindings },
		onRebinding = { onChange(settings.copy(rebinding = it)) },
		changed = settings.changed,
		onApply = onApply,
		onClose = onClose,
		mouseChords = mouseChords,
		keys = keys,
		onUndo = onUndo,
		onRedo = onRedo,
		filter = filter,
		blocked = settings.blocked,
	) { page ->
		when (page) {
			LauncherPage.General -> GeneralPage(settings, onChange)
			LauncherPage.Bindings ->
				BindingsPage(settings.rebinding, filter, { onChange(settings.copy(rebinding = it)) })
			LauncherPage.Applications -> ApplicationsPage(settings, applications, onChange)
			LauncherPage.Aliases ->
				AliasesPage(settings.aliasing) { onChange(settings.copy(aliasing = it)) }
			LauncherPage.Indexing ->
				ExcludingPage(settings.excluding) { onChange(settings.copy(excluding = it)) }
		}
	}
}

/** How the launcher looks, what it finds, and how it behaves. */
@Composable
private fun GeneralPage(
	settings: LauncherSettings,
	onChange: (LauncherSettings) -> Unit,
) {
	val set = settings.launcher

	fun changed(change: LauncherPreferences.() -> LauncherPreferences) =
		onChange(settings.set(change))

	PageLayout(
		"Launcher",
		"Bind “prism launcher” to a key in your window manager to show and hide the launcher.",
	) {
		Section("Appearance") {
			Card {
				SettingRow("Width") {
					Stepper(
						"${set.width}",
						!set.width.least,
						!set.width.most,
						tag = "launcher-width",
					) { step -> changed { copy(width = width + 40 * step) } }
				}
				SettingRow(
					"Visible results",
					description = "Rows shown before the list scrolls.",
				) {
					Stepper(
						"${set.rows}",
						!set.rows.least,
						!set.rows.most,
						tag = "launcher-rows",
					) { step -> changed { copy(rows = rows + step) } }
				}
				SettingRow(
					"Rounded corners",
					description = "Needs Wayland, or a compositor on X11.",
				) {
					Switch(set.rounded, Modifier.testTag("launcher-rounded")) { on ->
						changed { copy(rounded = on) }
					}
				}
				SettingRow("Scale", description = "Size of text and icons.") {
					Stepper(
						"${(set.scale.times * 100).roundToInt()} %",
						!set.scale.least,
						!set.scale.most,
						tag = "launcher-scale",
					) { step -> changed { copy(scale = scale.stepped(step)) } }
				}
			}
		}
		Section("Results") {
			Card {
				SettingRow(
					"Files and folders",
					description = "Show matching files from the index after applications.",
				) {
					Switch(set.files, Modifier.testTag("launcher-files")) { on ->
						changed {
							copy(files = on)
						}
					}
				}
				SettingRow(
					"Maximum file results",
					enabled = set.files,
				) {
					Stepper(
						"${set.fileResults}",
						set.files && !set.fileResults.least,
						set.files && !set.fileResults.most,
						tag = "launcher-file-results",
					) { step -> changed { copy(fileResults = fileResults + 5 * step) } }
				}
				SettingRow(
					"Application actions",
					description =
						"Show an application's extra actions, such as a browser's private window.",
				) {
					Switch(set.actions, Modifier.testTag("launcher-actions")) { on ->
						changed { copy(actions = on) }
					}
				}
			}
		}
		Section("Behaviour") {
			Card {
				SettingRow(
					"Show above all windows",
					description =
						"Uses Wayland's layer shell to show the launcher centered above everything, " +
							"including fullscreen windows. When off, it is a floating window.",
				) {
					Switch(set.overlay, Modifier.testTag("launcher-overlay")) { on ->
						changed { copy(overlay = on) }
					}
				}
				SettingRow(
					"Close when focus is lost",
				) {
					Switch(set.closeUnfocused, Modifier.testTag("launcher-close-unfocused")) { on ->
						changed { copy(closeUnfocused = on) }
					}
				}
			}
		}
	}
}

/**
 * The applications installed, each with whether the launcher lists it; those whose names hold what is typed over
 * them, where anything is.
 */
@Composable
private fun ApplicationsPage(
	settings: LauncherSettings,
	applications: List<Application>,
	onChange: (LauncherSettings) -> Unit,
) {
	val type = LocalType.current
	val hidden = settings.launcher.hidden

	val listed =
		remember(applications, settings.finding) {
			applications.filter { it.name.contains(settings.finding.trim(), ignoreCase = true) }
		}

	PageLayout(
		"Applications",
		"Applications that are turned off are hidden from the launcher.",
		scrolls = false,
	) {
		Field(
			settings.finding,
			{ onChange(settings.copy(finding = it)) },
			Modifier.fillMaxWidth().testTag("launcher-applications-filter"),
			hint = "Filter applications",
			leading = Glyph.Search,
		)
		Card(Modifier.weight(1f)) {
			LazyScrollArea(
				Modifier.fillMaxSize(),
				padding = PaddingValues(horizontal = Space.xs, vertical = Space.xs),
				tag = "launcher-applications",
			) {
				items(listed, key = { it.id }) { application ->
					Row(
						Modifier
							.fillMaxWidth()
							.padding(horizontal = Space.s, vertical = Space.xs),
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
						) { shown ->
							onChange(
								settings.set {
									copy(
										hidden =
											if (shown)
												this.hidden - application.id
											else
												this.hidden + application.id,
									)
								},
							)
						}
					}
				}
				if (listed.isEmpty())
					item(key = "none") {
						Text(
							"No applications match “${settings.finding.trim()}”.",
							style = type.muted,
							modifier = Modifier.padding(Space.m),
						)
					}
			}
		}
	}
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
