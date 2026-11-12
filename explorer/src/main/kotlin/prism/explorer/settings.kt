package prism.explorer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import prism.browser.Actions
import prism.browser.Bindable
import prism.browser.Bindings
import prism.browser.Chord
import prism.desktop.TerminalChoice
import prism.preferences.DateFormat
import prism.preferences.Layout
import prism.preferences.ListingKind
import prism.preferences.Listings
import prism.preferences.LookKey
import prism.preferences.Preferences
import prism.preferences.Role
import prism.preferences.Rows
import prism.preferences.Scales
import prism.preferences.TerminalChoiceSerializer
import prism.preferences.Typography

/*
 * The preferences as their window edits them, as one immutable value: how windows look, the terminal, and every
 * binding, each as the user has made it, beside what they were when the window opened. Nothing is written until the
 * user saves; then only what they changed is written, over the preferences as they are by then, so what was changed
 * meanwhile elsewhere (quick access arranged from a window, a window zoomed) is kept unless it was changed here too.
 */

/** Which of the window's pages is shown. */
enum class Page(
	val label: String,
) {
	Display("Display"),
	Listings("Listings"),
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
 * Which of the bindings page's actions are shown: those whose names or chords have [Words] in them (every one, for
 * none); or those bound to a [Shortcut] pressed in the window that finds one, as JetBrains' "Find Shortcut" does.
 */
sealed interface Finding {
	data class Words(
		val text: String,
	) : Finding

	/**
	 * Those bound to [chord], pressed while the window that finds it was [open] (every one, until one is). A chord is
	 * taken only while it is open: closed, the keys are the page's again, and the actions found stay found.
	 */
	data class Shortcut(
		val chord: Chord?,
		val open: Boolean,
	) : Finding

	/** Whether [action], bound to [chords], is shown. */
	fun shows(action: Bindable, chords: List<Chord>): Boolean =
		when (this) {
			// By its name, as shown or as the preferences file writes it ("ZoomIn" is "Larger"), or by its chords.
			is Words ->
				text.isBlank() ||
					action.label.contains(text.trim(), ignoreCase = true) ||
					action.key.contains(text.trim().replace(" ", ""), ignoreCase = true) ||
					chords.any { it.toString().contains(text.trim(), ignoreCase = true) }
			is Shortcut -> chord == null || chord in chords
		}

	companion object {
		val ALL: Finding = Words("")

		/** The window that finds a shortcut, opened: every action shown until one is pressed. */
		val OPENED: Finding = Shortcut(null, open = true)
	}
}

/**
 * The bindings as the bindings page edits them: [bindings], the slot a chord is [awaited] for, where one is, what the
 * last chord given was taken from, and which actions are shown ([finding]). A chord pressed while one is awaited is put
 * there and taken from any other action that had it, since a chord does one thing; pressed while the window that finds
 * a shortcut is open, it is found.
 */
data class Rebinding(
	val bindings: Bindings,
	val awaited: Slot? = null,
	val took: Took? = null,
	val finding: Finding = Finding.ALL,
) {
	/** Whether a chord pressed is taken, for a binding or for finding what has it: nothing else sees it then. */
	val listening: Boolean get() =
		awaited != null ||
			(finding as? Finding.Shortcut)?.open == true

	/** These, awaiting a chord for [slot]. */
	fun awaiting(slot: Slot): Rebinding = copy(awaited = slot, took = null)

	/**
	 * These, no longer awaiting a chord; the window that finds one closed, what it found still found (every action, where
	 * it found nothing).
	 */
	fun cancelled(): Rebinding =
		copy(
			awaited = null,
			finding =
				when (val finding = finding) {
					is Finding.Shortcut ->
						finding.chord?.let { Finding.Shortcut(it, open = false) }
							?: Finding.ALL
					is Finding.Words -> finding
				},
		)

	/** These, finding as [finding] says. */
	fun finding(finding: Finding): Rebinding = copy(finding = finding, awaited = null)

	/** These, with [chord] in the slot awaited, if one is; or found, while the window that finds one is open. */
	fun pressed(chord: Chord): Rebinding {
		if ((finding as? Finding.Shortcut)?.open == true && awaited == null)
			return copy(finding = Finding.Shortcut(chord, open = true))

		val slot = awaited ?: return this
		val from = bindings.clashes(slot.action, chord)

		return Rebinding(
			bindings.bound(slot.action, slot.index, chord),
			took = Took(chord, from).takeIf { from.isNotEmpty() },
			finding = finding,
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
 * The preferences window's edits: [base], the preferences as they were when it opened, or were last applied; how
 * windows' listings look and what they leave out ([listings]); each part's font and size, and how large things are
 * drawn; how dates are written; the [terminal] as its fields are typed; the bindings being rebound; the [page] shown,
 * and on the listings' page, which listing ([looking]), and which layout of it its filters are for ([filteringIn]).
 */
data class Settings(
	val base: Preferences,
	val terminal: TerminalForm = TerminalForm.of(base.terminal),
	val rebinding: Rebinding = Rebinding(Bindings.of(base)),
	val listings: Listings = base.listings,
	/** Each part's font, and its size. */
	val typography: Typography = base.typography,
	val rows: Rows = base.rows,
	/** How large the listing of a new window is drawn, an explorer's and a picker's, and the sidebar's quick access. */
	val scales: Scales = base.scales,
	val dates: DateFormat = base.dates,
	val page: Page = Page.Display,
	val looking: LookKey = LookKey(Role.Explorer, ListingKind.Folder),
	val filteringIn: Layout = Layout.List,
) {
	/** The actions and places there are, prism's own and those the preferences write, with what of them did not read. */
	val actions: Actions by lazy { Actions.of(base) }

	/** Whether anything is changed from what the window opened on, or last applied. */
	val changed: Boolean get() = over(base) != base

	/**
	 * These, applied: the preferences as they now are ([now]) what the window edits from, the page, and where on it,
	 * kept; and what the bindings page shows.
	 */
	fun applied(now: Preferences): Settings =
		Settings(now, page = page, looking = looking, filteringIn = filteringIn)
			.let { it.copy(rebinding = it.rebinding.copy(finding = rebinding.finding)) }

	/** [current], with what was changed here. */
	fun over(current: Preferences): Preferences {
		fun <T> kept(field: (Preferences) -> T, edited: T): T =
			if (edited != field(base))
				edited
			else
				field(current)

		val bindings = rebinding.bindings.takeIf { it != Bindings.of(base) }?.differences()

		return current.copy(
			terminal = kept(Preferences::terminal, terminal.choice()),
			listings = kept(Preferences::listings, listings),
			typography = kept(Preferences::typography, typography),
			rows = kept(Preferences::rows, rows),
			dates = kept(Preferences::dates, dates),
			// The other windows' sizes are each window's own, as Ctrl and the wheel leave them: never this window's.
			scales =
				current.scales.copy(
					explorer = kept({ it.scales.explorer }, scales.explorer),
					picker = kept({ it.scales.picker }, scales.picker),
					sidebar = kept({ it.scales.sidebar }, scales.sidebar),
				),
			bindings = bindings ?: current.bindings,
		)
	}
}

/** What the Terminal page has chosen: the desktop's own terminal, one by its name, or command lines of the user's own. */
sealed interface TerminalPick {
	data object Automatic : TerminalPick

	data class Named(
		val name: String,
	) : TerminalPick

	data object Custom : TerminalPick
}

/**
 * The terminal as the Terminal page has it: what is chosen ([pick]), and the custom command lines as typed, [open]
 * (opened in a folder) and [run] (opened in a folder running a command), which are kept while another is chosen, to
 * come back to. [kept] is a choice the file holds that could not be read, kept as it is while nothing is changed.
 */
data class TerminalForm(
	val pick: TerminalPick = TerminalPick.Automatic,
	val open: String = "",
	val run: String = "",
	val kept: TerminalChoice.Unreadable? = null,
) {
	/** The choice the page makes; custom lines that cannot be one, as a choice that cannot be read, which says why. */
	fun choice(): TerminalChoice =
		kept ?: when (pick) {
			TerminalPick.Automatic -> TerminalChoice.Found
			is TerminalPick.Named -> TerminalChoice.Named(pick.name)
			TerminalPick.Custom ->
				if (wordsOf(open).isEmpty() && wordsOf(run).isEmpty())
					wrongly("a custom terminal is its command lines, and none is written")
				else
					TerminalChoiceSerializer.choiceOf(json())
		}

	/** These, with [pick] chosen. */
	fun picked(pick: TerminalPick): TerminalForm = copy(pick = pick, kept = null)

	/** These, the custom command lines typed as [open] and [run]. */
	fun typed(open: String = this.open, run: String = this.run): TerminalForm =
		copy(pick = TerminalPick.Custom, open = open, run = run, kept = null)

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
		/** [choice] on the page. */
		fun of(choice: TerminalChoice): TerminalForm =
			when (choice) {
				TerminalChoice.Found -> TerminalForm()
				is TerminalChoice.Named -> TerminalForm(TerminalPick.Named(choice.name))
				is TerminalChoice.CommandLines ->
					TerminalForm(
						TerminalPick.Custom,
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
				is TerminalChoice.Unreadable -> TerminalForm(TerminalPick.Custom, kept = choice)
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
