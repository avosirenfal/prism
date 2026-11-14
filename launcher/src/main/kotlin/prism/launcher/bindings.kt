package prism.launcher

import androidx.compose.runtime.Immutable
import prism.browser.Chord
import prism.preferences.LauncherPreferences
import prism.preferences.Preferences

/*
 * The launcher's keys: each thing it does from the keyboard, and the chords that do it, the user's where they have
 * bound others. Kept among the rest of the bindings in the preferences, each action named `launcher.` and its name, and
 * written as a chord is shown (`Ctrl+Enter`).
 */

/** Something the launcher does from the keyboard, with the chords that do it until the user says otherwise. */
enum class LauncherAction(
	val label: String,
	vararg defaults: String,
) {
	Open("Open what is chosen", "Enter"),
	Reveal("Show it in its folder", "Ctrl+Enter"),
	CopyPath("Copy its path", "Ctrl+Shift+C"),
	Down("Down a result", "Down", "Ctrl+N", "Ctrl+J", "Tab"),
	Up("Up a result", "Up", "Ctrl+P", "Ctrl+K", "Shift+Tab"),
	PageDown("Down a page", "PageDown"),
	PageUp("Up a page", "PageUp"),
	First("The first result", "Ctrl+Home"),
	Last("The last result", "Ctrl+End"),
	Settings("The launcher's settings", "Ctrl+Comma"),
	Dismiss("Clear what is typed, or close", "Escape"),
	;

	val defaults: List<Chord> =
		defaults.map { Chord.parse(it) ?: error("$name's default “$it” is not a chord") }

	/** What it is named among the preferences' bindings. */
	val key: String get() = LauncherPreferences.bindingOf(name)
}

/** Every action's chords, as the user has them. */
@Immutable
data class LauncherBindings(
	val chords: Map<LauncherAction, List<Chord>>,
) {
	fun of(action: LauncherAction): List<Chord> = chords[action].orEmpty()

	/** What [chord] does; nothing, where it is bound to nothing. */
	fun action(chord: Chord): LauncherAction? =
		chords.entries.firstOrNull { chord in it.value }?.key

	/** The actions other than [action] that [chord] is bound to. */
	fun clashes(action: LauncherAction, chord: Chord): List<LauncherAction> =
		chords.entries.filter { it.key != action && chord in it.value }.map { it.key }

	companion object {
		val DEFAULT = LauncherBindings(LauncherAction.entries.associateWith { it.defaults })

		/** The bindings [preferences] make: an action named there has the chords written there that read as chords. */
		fun of(preferences: Preferences): LauncherBindings =
			LauncherBindings(
				LauncherAction.entries.associateWith { action ->
					preferences.bindings[action.key]?.mapNotNull(Chord::parse) ?: action.defaults
				},
			)

		/**
		 * [preferences] with [chord] as [action]'s chord at [index] (after its last, for an index past them), and
		 * taken from whatever else of the launcher's had it: a chord does one thing.
		 */
		fun bound(
			preferences: Preferences,
			action: LauncherAction,
			index: Int,
			chord: Chord,
		): Preferences {
			val now = of(preferences)

			val freed =
				now.clashes(action, chord).fold(preferences) { set, other ->
					set.binding(other.key, (now.of(other) - chord).map { it.toString() })
				}
			val mine = now.of(action)
			val placed =
				if (index < mine.size)
					mine.mapIndexed { at, it -> if (at == index) chord else it }
				else
					mine.plusElement(chord)

			return freed.binding(action.key, placed.distinct().map { it.toString() })
		}

		/** [preferences] without [action]'s chord at [index]. */
		fun unbound(preferences: Preferences, action: LauncherAction, index: Int): Preferences =
			preferences.binding(
				action.key,
				of(preferences)
					.of(action)
					.filterIndexed {
						at,
						_,
						->
						at != index
					}.map { it.toString() },
			)

		/** [preferences] with [action]'s chords its defaults again. */
		fun reset(preferences: Preferences, action: LauncherAction): Preferences =
			preferences.binding(action.key, null)
	}
}

/** What [action] sends the launcher. */
internal fun eventOf(action: LauncherAction, page: Int): LauncherEvent =
	when (action) {
		LauncherAction.Open -> LauncherEvent.Entered
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
