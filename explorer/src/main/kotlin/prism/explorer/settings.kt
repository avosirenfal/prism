package prism.explorer

import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import prism.browser.Actions
import prism.browser.Bindable
import prism.browser.Bindings
import prism.browser.Chord
import prism.desktop.Place
import prism.desktop.TerminalChoice
import prism.preferences.Preferences
import prism.preferences.TerminalChoiceSerializer

/*
 * The preferences as their window edits them, as one immutable value: the places pinned, the terminal, and every
 * binding, each as the user has made it, beside what they were when the window opened. Nothing is written until the
 * user saves; then only what they changed is written, over the preferences as they are by then, so a place pinned
 * from a window meanwhile is kept unless the pins were changed here too.
 */

/** Which of the window's pages is shown. */
enum class Page(
	val label: String,
) {
	Sidebar("Sidebar"),
	Terminal("Terminal"),
	Bindings("Key bindings"),
	Actions("Actions"),
}

/** A chord's place in the bindings table: [action]'s chord at [index], or, past its last, a chord to add. */
data class Slot(
	val action: Bindable,
	val index: Int,
)

/** That [chord] was given to one action, and taken from [from], which had it. */
data class Took(
	val chord: Chord,
	val from: List<Bindable>,
)

/**
 * The bindings as the bindings page edits them: [bindings], the slot a chord is [awaited] for, where one is, and what
 * the last chord given was taken from. A chord pressed while one is awaited is put there and taken from any other
 * action that had it, since a chord does one thing.
 */
data class Rebinding(
	val bindings: Bindings,
	val awaited: Slot? = null,
	val took: Took? = null,
) {
	/** These, awaiting a chord for [slot]. */
	fun awaiting(slot: Slot): Rebinding = copy(awaited = slot, took = null)

	/** These, no longer awaiting a chord. */
	fun cancelled(): Rebinding = copy(awaited = null)

	/** These, with [chord] in the slot awaited, if one is. */
	fun pressed(chord: Chord): Rebinding {
		val slot = awaited ?: return this
		val from = bindings.clashes(slot.action, chord)

		return Rebinding(
			bindings.bound(slot.action, slot.index, chord),
			took = Took(chord, from).takeIf { from.isNotEmpty() },
		)
	}

	/** These, without the chord in [slot]. */
	fun removed(slot: Slot): Rebinding = Rebinding(bindings.unbound(slot.action, slot.index))

	/** These, with [action]'s defaults back. Its default chords are taken from whatever has them meanwhile. */
	fun reset(action: Bindable): Rebinding =
		action.defaults
			.foldIndexed(
				Rebinding(bindings.with(action, emptyList())),
			) { index, rebinding, chord ->
				rebinding.awaiting(Slot(action, index)).pressed(chord)
			}.copy(took = null)

	/** Every default back. */
	fun resetAll(): Rebinding = Rebinding(bindings.defaults())
}

/**
 * The preferences window's edits: [base], the preferences as they were when it opened; the places [pinned] (null for
 * the desktop's own, those of the [known] places); the [terminal] as its fields are typed; the bindings being rebound; and the
 * [page] shown.
 */
data class Settings(
	val base: Preferences,
	val known: List<Place>,
	val pinned: List<Path>? = base.pinned,
	val terminal: TerminalForm = TerminalForm.of(base.terminal),
	val rebinding: Rebinding = Rebinding(Bindings.of(base)),
	val page: Page = Page.Sidebar,
) {
	/** The actions and places there are, prism's own and those the preferences write, with what of them did not read. */
	val actions: Actions by lazy { Actions.of(base) }

	/** The places pinned, as the sidebar shows them. */
	val pins: List<Path> get() = pinned ?: known.map { it.path }

	/** What the sidebar calls the place pinned at [path]: a known place's name, or the folder's. */
	fun labelOf(path: Path): String =
		known.firstOrNull { it.path == path }?.label ?: (path.fileName ?: path).toString()

	/** Whether anything is changed from what the window opened on. */
	val changed: Boolean get() = over(base) != base

	/** [current], with what was changed here. */
	fun over(current: Preferences): Preferences {
		fun <T> kept(field: (Preferences) -> T, edited: T): T =
			if (edited !=
				field(base)
			)
				edited
			else
				field(current)

		val bindings = rebinding.bindings.takeIf { it != Bindings.of(base) }?.differences()

		return current.copy(
			pinned = kept(Preferences::pinned, pinned),
			terminal = kept(Preferences::terminal, terminal.choice()),
			bindings = bindings ?: current.bindings,
		)
	}

	/** These, with the place at [index] moved [by] places (up, for a negative [by]), as far as it can go. */
	fun moved(index: Int, by: Int): Settings {
		val to = (index + by).coerceIn(0, pins.lastIndex)
		val place = pins[index]

		// A path is a list of its names too: it is added to the list as one element, never as its names.
		return copy(
			pinned =
				pins.filterIndexed { at, _ -> at != index }.let {
					it.take(to) +
						listOf(place) +
						it.drop(to)
				},
		)
	}

	/** These, without the place at [index]. */
	fun unpinned(index: Int): Settings =
		copy(
			pinned =
				pins.filterIndexed { at, _ ->
					at !=
						index
				},
		)

	/** These, with [path] pinned last, where it is not pinned already. */
	fun pinned(path: Path): Settings =
		if (path in
			pins
		)
			this
		else
			copy(pinned = pins.plusElement(path))
}

/**
 * The terminal chosen, as the preferences window's fields have it, each as typed: a terminal's [name]; or its command
 * lines, [open] (opened in a folder) and [run] (opened in a folder running a command); all empty for the desktop's own.
 * [kept] is a choice the file holds that could not be read, kept as it is while nothing is typed over it.
 */
data class TerminalForm(
	val name: String = "",
	val open: String = "",
	val run: String = "",
	val kept: TerminalChoice.Unreadable? = null,
) {
	/** The choice the fields make; one that cannot be, as a choice that cannot be read, which says why. */
	fun choice(): TerminalChoice {
		val named = name.trim()
		val lines = wordsOf(open) to wordsOf(run)

		return when {
			named.isEmpty() && lines.first.isEmpty() && lines.second.isEmpty() ->
				kept
					?: TerminalChoice.Found
			named.isNotEmpty() && (lines.first.isNotEmpty() || lines.second.isNotEmpty()) ->
				wrongly("it is a terminal's name, or its command lines, not both")
			named.isNotEmpty() -> TerminalChoice.Named(named)
			else -> TerminalChoiceSerializer.choiceOf(json())
		}
	}

	/** What the command lines' fields say, as the file writes it. */
	private fun json() =
		buildJsonObject {
			wordsOf(open)
				.takeIf {
					it.isNotEmpty()
				}?.let { put("open", JsonArray(it.map(::JsonPrimitive))) }
			wordsOf(run)
				.takeIf {
					it.isNotEmpty()
				}?.let { put("run", JsonArray(it.map(::JsonPrimitive))) }
		}

	private fun wrongly(why: String) =
		TerminalChoice.Unreadable(
			json().toString(),
			"the terminal in the preferences can't be read: $why",
		)

	companion object {
		/** [choice] in the fields. */
		fun of(choice: TerminalChoice): TerminalForm =
			when (choice) {
				TerminalChoice.Found -> TerminalForm()
				is TerminalChoice.Named -> TerminalForm(name = choice.name)
				is TerminalChoice.CommandLines ->
					TerminalForm(
						open =
							choice.inFolder
								?.words
								?.let(::textOf)
								.orEmpty(),
						run =
							choice.running
								?.words
								?.let(::textOf)
								.orEmpty(),
					)
				is TerminalChoice.Unreadable -> TerminalForm(kept = choice)
			}
	}
}

/**
 * The words [text] is made of, as a shell would split them, though nothing in it is run by a shell: split at spaces,
 * but not inside quotes, where a backslash keeps the quote it comes before.
 */
fun wordsOf(text: String): List<String> {
	data class Splitting(
		val words: List<String> = emptyList(),
		val word: String? = null,
		val quote: Char? = null,
		val escaped: Boolean = false,
	) {
		fun ended(): List<String> = words + listOfNotNull(word)
	}

	return text
		.fold(Splitting()) { at, char ->
			when {
				at.escaped -> at.copy(word = at.word.orEmpty() + char, escaped = false)
				at.quote != null && char == '\\' -> at.copy(escaped = true)
				at.quote != null && char == at.quote -> at.copy(quote = null)
				at.quote != null -> at.copy(word = at.word.orEmpty() + char)
				char == '"' || char == '\'' -> at.copy(word = at.word.orEmpty(), quote = char)
				char.isWhitespace() -> Splitting(at.ended())
				else -> at.copy(word = at.word.orEmpty() + char)
			}
		}.ended()
}

/** [words] as they are typed: each with a space, a quote or a backslash in it quoted, so that [wordsOf] reads it back. */
fun textOf(words: List<String>): String =
	words.joinToString(" ") { word ->
		if (word.isEmpty() ||
			word.any { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' }
		)
			"\"" + word.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
		else
			word
	}
