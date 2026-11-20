package prism.explorer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import prism.browser.Action
import prism.browser.Actions
import prism.browser.Bindable
import prism.browser.Bindings
import prism.browser.Chord
import prism.browser.Edits
import prism.desktop.TerminalChoice
import prism.preferences.DateFormat
import prism.preferences.Held
import prism.preferences.IndexUpkeep
import prism.preferences.JumpPause
import prism.preferences.Layout
import prism.preferences.ListingKind
import prism.preferences.Listings
import prism.preferences.LookKey
import prism.preferences.MenuKey
import prism.preferences.MenuOf
import prism.preferences.MenuSpec
import prism.preferences.Preferences
import prism.preferences.Role
import prism.preferences.Rows
import prism.preferences.Scales
import prism.preferences.ScrollSpeed
import prism.preferences.TerminalChoiceSerializer
import prism.preferences.Typography
import prism.preferences.WhenSpec
import prism.shared.ScrollDirection

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
	Renderer("Renderer"),
	Indexing("Indexing"),
	Thumbnails("Thumbnails"),
	Layout("Layout"),
	Menus("Context menus"),
	Terminal("Terminal"),
	Input("Input"),
	Bindings("Key bindings"),
	Aliases("Aliases"),
	Actions("File actions"),
	Searches("Text searches"),
}

/**
 * A chord's place in the bindings table: one of [action]'s chords, to be given another ([Bound]), or the place after
 * its last, for one more ([New]). A place is named by the chord that is in it, so there is none past an action's last
 * to name.
 */
sealed interface Slot {
	val action: Bindable

	/** The place [chord] is in, among [action]'s. */
	data class Bound(
		override val action: Bindable,
		val chord: Chord,
	) : Slot

	/** The place for one more of [action]'s. */
	data class New(
		override val action: Bindable,
	) : Slot
}

/** That [chord] was given to one action, and taken from [from], which had it. */
data class Took(
	val chord: Chord,
	val from: List<Bindable>,
)

/**
 * Which of the bindings page's actions are shown: those whose names or chords have [Words] in them (every one, for
 * none); those bound to a [Shortcut], pressed in the window that finds one, as JetBrains' "Find Shortcut" does; or
 * those whose chords are not their defaults ([Changed]).
 */
sealed interface Finding {
	data class Words(
		val text: String,
	) : Finding

	/** Those bound to [chord]. */
	data class Shortcut(
		val chord: Chord,
	) : Finding

	/** Those the user has bound otherwise than they come. */
	data object Changed : Finding

	/** Whether [action], bound to [chords], is shown. */
	fun shows(action: Bindable, chords: List<Chord>): Boolean =
		when (this) {
			// By its name, as shown or as the preferences file writes it ("ZoomIn" is "Larger"), by the group it is
			// listed in ("view" shows the view's), or by its chords.
			is Words ->
				text.isBlank() ||
					action.label.contains(text.trim(), ignoreCase = true) ||
					action.key.contains(text.trim().replace(" ", ""), ignoreCase = true) ||
					action.group.label.contains(text.trim(), ignoreCase = true) ||
					chords.any { it.toString().contains(text.trim(), ignoreCase = true) }
			is Shortcut -> chord in chords
			Changed -> chords != action.defaults
		}

	companion object {
		val ALL: Finding = Words("")
	}
}

/** Which chords a set of bindings can be given. */
enum class Taking {
	/** Any: a key that types acts only where nothing is typed, which the browser's listing is. */
	Any,

	/** None that types a character: the keyboard is always in a field (the launcher's), which would have it first. */
	NothingTyped,
}

/**
 * What the bindings page does with a chord pressed, and with it which actions it shows ([finding]): one of these,
 * and so never a chord awaited and a shortcut looked for at once, nor a chord refused with no place waiting for one.
 */
sealed interface Hearing {
	val finding: Finding

	/** Nothing: the keys are the page's own. [took] is what the last chord given was taken from, where it was. */
	data class Idle(
		override val finding: Finding = Finding.ALL,
		val took: Took? = null,
	) : Hearing

	/**
	 * A chord, for [slot]. One these bindings do not take ([Taking]) is [refused], the last pressed that was, and the
	 * slot goes on waiting.
	 */
	data class Awaiting(
		val slot: Slot,
		override val finding: Finding,
		val refused: Chord? = null,
	) : Hearing

	/**
	 * A shortcut, in the window that finds one: what is shown is what the one pressed last does ([found]), and every
	 * action until one is.
	 */
	data class Seeking(
		val found: Chord? = null,
	) : Hearing {
		override val finding: Finding get() = found?.let(Finding::Shortcut) ?: Finding.ALL
	}
}

/**
 * The bindings as the bindings page edits them: [bindings], and what the page listens for and shows ([hearing]). A
 * chord pressed while one is awaited is put there and taken from any other action that had it, since a chord does one
 * thing; pressed while the window that finds a shortcut is open, it is found.
 */
data class Rebinding(
	val bindings: Bindings,
	val hearing: Hearing = Hearing.Idle(),
	val takes: Taking = Taking.Any,
) {
	/** Which actions are shown. */
	val finding: Finding get() = hearing.finding

	/** Whether a chord pressed is taken, for a binding or for finding what has it: nothing else sees it then. */
	val listening: Boolean get() = hearing !is Hearing.Idle

	/** The slot a chord is awaited for, where one is. */
	val awaited: Slot? get() = (hearing as? Hearing.Awaiting)?.slot

	/** The chord pressed for the slot awaited that these do not take, where the last one was. */
	val refused: Chord? get() = (hearing as? Hearing.Awaiting)?.refused

	/** What the last chord given was taken from, where it was taken from anything. */
	val took: Took? get() = (hearing as? Hearing.Idle)?.took

	/** How many actions are bound otherwise than they come. */
	val changed: Int get() = bindings.actions.count { bindings.of(it) != it.defaults }

	/** These, with [bindings] and nothing listened for: what a change to the bindings leaves. */
	private fun bound(bindings: Bindings, took: Took? = null): Rebinding =
		copy(bindings = bindings, hearing = Hearing.Idle(finding, took))

	/** These, awaiting a chord for [slot]. */
	fun awaiting(slot: Slot): Rebinding = copy(hearing = Hearing.Awaiting(slot, finding))

	/** These, with the window that finds a shortcut open: every action shown, until one is pressed. */
	fun seeking(): Rebinding = copy(hearing = Hearing.Seeking())

	/**
	 * These, no longer awaiting a chord; the window that finds one closed, what it found still found (every action, where
	 * it found nothing).
	 */
	fun cancelled(): Rebinding =
		when (hearing) {
			is Hearing.Idle -> this
			is Hearing.Awaiting, is Hearing.Seeking -> copy(hearing = Hearing.Idle(finding))
		}

	/** These, finding as [finding] says, and listening for nothing. */
	fun finding(finding: Finding): Rebinding = copy(hearing = Hearing.Idle(finding, took))

	/** These, with [chord] in the slot awaited, if one is; or found, while the window that finds one is open. */
	fun pressed(chord: Chord): Rebinding =
		when (val hearing = hearing) {
			is Hearing.Idle -> this
			is Hearing.Seeking -> copy(hearing = Hearing.Seeking(chord))
			is Hearing.Awaiting ->
				if (takes == Taking.NothingTyped && chord.typesText)
					copy(hearing = hearing.copy(refused = chord))
				else
					given(hearing.slot, chord)
		}

	/** Where [slot] is among its action's chords: after the last, for one more, and nowhere, for a chord not there. */
	private fun indexOf(slot: Slot): Int? =
		when (slot) {
			is Slot.New -> bindings.of(slot.action).size
			is Slot.Bound -> bindings.of(slot.action).indexOf(slot.chord).takeIf { it >= 0 }
		}

	/** These, with [chord] in [slot], taken from whatever had it; as they are, for a slot that is no longer there. */
	private fun given(slot: Slot, chord: Chord): Rebinding {
		val index = indexOf(slot) ?: return cancelled()
		val from = bindings.clashes(slot.action, chord)

		return bound(
			bindings.bound(slot.action, index, chord),
			Took(chord, from).takeIf { from.isNotEmpty() },
		)
	}

	/** These, without the chord in [slot]. */
	fun removed(slot: Slot.Bound): Rebinding =
		bound(indexOf(slot)?.let { bindings.unbound(slot.action, it) } ?: bindings)

	/** These, with [action]'s defaults back. Its default chords are taken from whatever has them meanwhile. */
	fun reset(action: Bindable): Rebinding =
		action.defaults
			.fold(bound(bindings.with(action, emptyList()))) { rebinding, chord ->
				rebinding.given(Slot.New(action), chord)
			}.let { it.bound(it.bindings) }

	/** Every default back. */
	fun resetAll(): Rebinding = bound(bindings.defaults())
}

/** Whose keys the bindings page shows: an explorer's windows', or a picker's. */
enum class KeyTab(
	val label: String,
) {
	Explorer("Explorer"),
	Picker("Pickers"),
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
	/** An explorer's keys, and a picker's where pickers have none of their own. */
	val rebinding: Rebinding = Rebinding(Bindings.of(base)),
	/** A picker's own keys; null where an explorer's are a picker's too. */
	val picking: Rebinding? =
		base.pickerBindings?.let { Rebinding(Bindings.of(base, Role.Picker)) },
	/** Whose keys the bindings page shows. */
	val keys: KeyTab = KeyTab.Explorer,
	val listings: Listings = base.listings,
	/** Each part's font, and its size. */
	val typography: Typography = base.typography,
	val rows: Rows = base.rows,
	/** How large the listing of a new window is drawn, an explorer's and a picker's, and the sidebar's quick access. */
	val scales: Scales = base.scales,
	val dates: DateFormat = base.dates,
	/** Whether windows have a title bar. */
	val titlebar: Boolean = base.titlebar,
	/** Which way the wheel scrolls the windows. */
	val scrolling: ScrollDirection = base.scrolling,
	/** How far the wheel scrolls the windows' lists. */
	val scrollSpeed: ScrollSpeed = base.scrollSpeed,
	/** How long a pause in typing into a listing ends the letters that go to a row by its name. */
	val jumpPause: JumpPause = base.jumpPause,
	/** What the index does on its own to stay as the disk is, and whether what it hears is kept to be looked at. */
	val indexing: IndexUpkeep = base.indexing,
	val page: Page = Page.Display,
	val looking: LookKey = LookKey(Role.Explorer, ListingKind.Folder),
	val filteringIn: Layout = Layout.List,
	/** How the context menus are arranged, as the Context menus page has them. */
	val menus: MenuSpec = base.menu,
	/** The menu the Context menus page shows. */
	val menuing: MenuKey = MenuKey.Listing(ListingKind.Folder, MenuOf.File),
	/** The file it shows that menu for: any, where null. */
	val seenFor: Sample? = null,
	/** The keys it shows that menu with, as held as it opens. */
	val holding: Set<Held> = emptySet(),
	/**
	 * The entries dragged on the Context menus page, by id: of two that changed places, the one dragged is the one said
	 * to have moved.
	 */
	val dragged: Set<String> = emptySet(),
	/** The search aliases, as the Aliases page has them typed. */
	val aliasing: AliasEdits = AliasEdits.of(base.aliases),
	/** What the index leaves out, as the Indexing page has it. */
	val excluding: ExcludingEdits = ExcludingEdits.of(base.excluding),
	/** How thumbnails are made and kept, as the Thumbnails page has it. */
	val thumbnailing: ThumbnailEdits = ThumbnailEdits(base.thumbnailing),
	/** The user's actions and text searches, which are turned off, and the editor, as their two pages have them. */
	val acting: Acting = Acting.of(base),
	/** Which actions the File actions page shows. */
	val actionsShown: Narrowing = Narrowing(),
	/** Which text searches the Text searches page shows. */
	val searchesShown: Narrowing = Narrowing(),
	/** The window open over those pages, where one is: an action or a search being edited, or moved. */
	val modal: ActingModal? = null,
) {
	/** What stops these from being applied, where anything does: an alias changed here with something wrong with it. */
	val blocked: String?
		get() =
			AliasEdits.UNFIT.takeIf {
				aliasing != AliasEdits.of(base.aliases) &&
					!aliasing.fit
			}

	/** The actions and places there are, prism's own and those the preferences write, with what of them did not read. */
	val actions: Actions by lazy {
		Actions.read(
			listOf(Actions.SHIPPED, acting.file),
			menus.hidden.toSet(),
			acting.disabled,
			prism.desktop.SessionEnvironment::get,
		)
	}

	/** What each action is written to be offered for, by its id: prism's own, then the preferences'. */
	val specs: Map<String, WhenSpec> by lazy { specsOf(Actions.SHIPPED, acting.file) }

	/**
	 * The bindings the page shows and rebinds: an explorer's, or a picker's own; none, where it shows a picker's and
	 * they are an explorer's.
	 */
	val rebound: Rebinding?
		get() =
			when (keys) {
				KeyTab.Explorer -> rebinding
				KeyTab.Picker -> picking
			}

	/** These, with the bindings the page shows as [rebound]. */
	fun rebound(rebound: Rebinding): Settings =
		when (keys) {
			KeyTab.Explorer -> copy(rebinding = rebound)
			KeyTab.Picker -> copy(picking = rebound)
		}

	/**
	 * These, with pickers given keys of their [own], which start as an explorer's are here for everything a picker
	 * does; or, not their own, given an explorer's again.
	 */
	fun pickersOwn(own: Boolean): Settings =
		copy(
			picking =
				if (own)
					picking ?: Rebinding(
						Bindings(Action.PICKING.associateWith { rebinding.bindings.of(it) }),
					)
				else
					null,
		)

	/** What the window would write over its preferences, as these are. */
	val written: Preferences get() = over(base)

	/**
	 * These, the folder thumbnails are kept in changed to what is [written], which the preferences already hold: it is
	 * changed at once, by itself (thumbnailing.kt), so it is what the window edits from, and whatever else is changed
	 * here still waits to be applied.
	 */
	fun relocated(written: String?): Settings =
		copy(
			base = base.copy(thumbnailing = base.thumbnailing.copy(folder = written)),
			thumbnailing = thumbnailing.relocated(written),
		)

	/** Whether anything is changed from what the window opened on, or last applied. */
	val changed: Boolean get() = written != base

	/**
	 * These, applied: the preferences as they now are ([now]) what the window edits from, the page, and where on it,
	 * kept; and what the bindings page shows.
	 */
	fun applied(now: Preferences): Settings =
		Settings(
			now,
			page = page,
			looking = looking,
			filteringIn = filteringIn,
			menuing = menuing,
			seenFor = seenFor,
			holding = holding,
			dragged = dragged,
			keys = keys,
			actionsShown = actionsShown,
			searchesShown = searchesShown,
		).let {
			it.copy(
				rebinding = it.rebinding.finding(rebinding.finding),
				picking = it.picking?.finding(picking?.finding ?: Finding.ALL),
			)
		}

	/** [current], with what was changed here. */
	fun over(current: Preferences): Preferences {
		fun <T> kept(field: (Preferences) -> T, edited: T): T =
			if (edited != field(base))
				edited
			else
				field(current)

		val bindings = rebinding.bindings.takeIf { it != Bindings.of(base) }?.differences()
		val pickersWere = base.pickerBindings?.let { Bindings.of(base, Role.Picker) }

		return current.copy(
			terminal = kept(Preferences::terminal, terminal.choice()),
			listings = kept(Preferences::listings, listings),
			menu = kept(Preferences::menu, menus),
			typography = kept(Preferences::typography, typography),
			rows = kept(Preferences::rows, rows),
			dates = kept(Preferences::dates, dates),
			titlebar = kept(Preferences::titlebar, titlebar),
			scrolling = kept(Preferences::scrolling, scrolling),
			scrollSpeed = kept(Preferences::scrollSpeed, scrollSpeed),
			jumpPause = kept(Preferences::jumpPause, jumpPause),
			indexing = kept(Preferences::indexing, indexing),
			// The other windows' sizes are each window's own, as Ctrl and the wheel leave them: never this window's.
			scales =
				current.scales.copy(
					explorer = kept({ it.scales.explorer }, scales.explorer),
					picker = kept({ it.scales.picker }, scales.picker),
					sidebar = kept({ it.scales.sidebar }, scales.sidebar),
				),
			bindings = bindings ?: current.bindings,
			// Never written with anything wrong in them: what is typed stays on the page until it is right.
			aliases = aliasing.written?.let { kept(Preferences::aliases, it) } ?: current.aliases,
			excluding = kept(Preferences::excluding, excluding.kept),
			thumbnailing = kept(Preferences::thumbnailing, thumbnailing.kept),
			actions = kept(Preferences::actions, acting.actions),
			textSearches = kept(Preferences::textSearches, acting.searches),
			disabled = kept(Preferences::disabled, acting.disabled),
			editor = kept(Preferences::editor, acting.editor.spec()),
			pickerBindings =
				if (picking?.bindings != pickersWere)
					picking?.bindings?.differences()
				else
					current.pickerBindings,
		)
	}
}

/**
 * [edits] with the window's settings changed [from] one [to] another: a step to undo where what the window would write
 * changed; none where only where the window is did (its page, the menu it shows).
 */
fun Edits<Settings>.changed(from: Settings, to: Settings): Edits<Settings> =
	if (to.written != from.written)
		edited(from)
	else
		this

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
					wrongly("enter a command line for the custom terminal")
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
			"the terminal setting is invalid: $why",
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
