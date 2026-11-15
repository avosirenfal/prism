package prism.launcher

import prism.browser.Bindable
import prism.browser.Bindings
import prism.browser.Chord
import prism.browser.KeyGroup
import prism.preferences.LauncherPreferences

/*
 * The launcher's keys: each thing it does from the keyboard, and the chords that do it, the user's where they have
 * bound others. They are bindings as the browser's are ([Bindings]), so the same page rebinds them; kept in the
 * launcher's own preferences, each action by its name, written as a chord is shown (`Ctrl+Enter`).
 */

/** The groups the launcher's actions are listed in. */
object LauncherGroups {
	val Result = KeyGroup("Result")
	val Moving = KeyGroup("Navigation")
	val Launcher = KeyGroup("Launcher")
}

/** Something the launcher does from the keyboard, with the chords that do it until the user says otherwise. */
enum class LauncherAction(
	override val label: String,
	override val group: KeyGroup,
	vararg defaults: String,
) : Bindable {
	Open("Open", LauncherGroups.Result, "Enter"),
	ShowActions("Show actions", LauncherGroups.Result, "Right"),
	Reveal("Show in folder", LauncherGroups.Result, "Ctrl+Enter"),
	CopyPath("Copy path", LauncherGroups.Result, "Ctrl+Shift+C"),
	Down("Move down", LauncherGroups.Moving, "Down", "Ctrl+J", "Tab"),
	Up("Move up", LauncherGroups.Moving, "Up", "Ctrl+K", "Shift+Tab"),
	PageDown("Page down", LauncherGroups.Moving, "PageDown"),
	PageUp("Page up", LauncherGroups.Moving, "PageUp"),
	First("Go to first", LauncherGroups.Moving, "Ctrl+Home"),
	Last("Go to last", LauncherGroups.Moving, "Ctrl+End"),
	Settings("Settings", LauncherGroups.Launcher, "Ctrl+P"),
	Dismiss("Clear or close", LauncherGroups.Launcher, "Escape"),
	;

	override val key: String get() = name

	override val defaults: List<Chord> =
		defaults.map { Chord.parse(it) ?: error("$name's default “$it” is not a chord") }
}

/** The launcher's bindings as [preferences] have them: each action's chords written there, or its defaults. */
fun launcherBindings(preferences: LauncherPreferences = LauncherPreferences()): Bindings =
	Bindings.of(LauncherAction.entries, preferences.bindings)

/** What [action] sends the launcher. */
internal fun eventOf(action: LauncherAction, page: Int): LauncherEvent =
	when (action) {
		LauncherAction.Open -> LauncherEvent.Entered
		LauncherAction.ShowActions -> LauncherEvent.MenuOpened()
		LauncherAction.Reveal -> LauncherEvent.Revealed
		LauncherAction.CopyPath -> LauncherEvent.CopiedPath
		LauncherAction.Down -> LauncherEvent.Moved(1)
		LauncherAction.Up -> LauncherEvent.Moved(-1)
		LauncherAction.PageDown -> LauncherEvent.Moved(page)
		LauncherAction.PageUp -> LauncherEvent.Moved(-page)
		LauncherAction.First -> LauncherEvent.MovedTo(last = false)
		LauncherAction.Last -> LauncherEvent.MovedTo(last = true)
		LauncherAction.Settings -> LauncherEvent.AskedForSettings
		LauncherAction.Dismiss -> LauncherEvent.Dismissed
	}
