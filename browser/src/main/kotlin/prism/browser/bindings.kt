package prism.browser

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import prism.preferences.Preferences
import prism.preferences.Role
import java.awt.event.KeyEvent as AwtKeys

/*
 * What every key and mouse button does, as the user has bound them. Each thing the browser does from the keyboard is
 * an [Action], with chords that do it by default; the user's preferences say which differ (by the action's name, and
 * the chords written as they are shown: `Ctrl+Shift+F`). The view looks a chord up rather than naming keys itself, so
 * every one can be bound again, in the explorer's bindings window.
 *
 * Where an action acts depends on what it acts on. Some act on the window (back, a search, a view); some on the rows of
 * the listing (copy, rename, the trash), and only from the listing, since in a text field the same keys type or edit
 * text; some move the listing's cursor, with Shift extending what is chosen and Ctrl moving without choosing, whatever
 * key moves it; and some are left to whatever has the keyboard first (Escape, which a field uses to stop what it is
 * doing, and undo, which a field uses for its own typing). A window's action acts from anywhere only for a chord no
 * text field types (one with Ctrl, Alt or Meta, a function key, Escape, or a mouse button); bound to a plain key, it
 * acts from the listing alone.
 */

/** Where an action acts. */
enum class Scope(
	/** Where that is, in a word or two, said beside an action that does not act from anywhere; null for one that does. */
	val said: String?,
) {
	/** The window: anywhere, for a chord no text field types; from the listing, for any. */
	Window(null),

	/** The listing's rows: only from the listing. */
	Listing("from the list"),

	/** The listing's cursor: from the listing, Shift and Ctrl changing what moving it chooses. */
	Movement(null),

	/** Whatever has the keyboard first, and the window where it does not use the key. */
	Leftover("unless a field uses the key"),
}

/**
 * Actions that belong together, as the window that binds keys lists them: under [label], with a line under that where
 * something is true of every one of them ([note]).
 */
@Immutable
data class KeyGroup(
	val label: String,
	val note: String? = null,
)

/** The groups the browser's own actions are in, in the order they are listed. */
object KeyGroups {
	val Places = KeyGroup("Getting around")
	val Finding = KeyGroup("Finding")
	val View = KeyGroup("The view")
	val Cursor =
		KeyGroup(
			"Moving through the list",
			"Shift chooses as it moves, and Ctrl moves without choosing, whatever key moves it.",
		)
	val Choosing = KeyGroup("Choosing")
	val Files = KeyGroup("Files and folders")
	val History = KeyGroup("Undoing")
	val Windows = KeyGroup("Windows")
	val Own = KeyGroup("Your actions", "Each is done to what is chosen, from the list.")
}

/**
 * Something a chord can be bound to: one of the browser's own actions ([Action]), one of the user's ([Custom]), or
 * something another window does (the launcher's). The bindings, and the window they are rebound in, know them alike.
 */
interface Bindable {
	/** What the preferences call it, where its chords are not its defaults. */
	val key: String
	val label: String

	/** The actions it is listed among. */
	val group: KeyGroup

	/** The chords that do it until the user says otherwise. */
	val defaults: List<Chord>

	/**
	 * Whether it moves a cursor, and so claims its key with Shift and Ctrl held too, which say how moving chooses: no
	 * other action can have that key with either.
	 */
	val moves: Boolean get() = false

	/** Where it acts, said beside it, where that is not everywhere. */
	val where: String? get() = null
}

/** Something the browser does, which acts in one [scope] of its window. */
sealed interface Scoped : Bindable {
	val scope: Scope

	override val moves: Boolean get() = scope == Scope.Movement
	override val where: String? get() = scope.said
}

/**
 * One of the user's own actions, as something bound: done from the listing, to the rows chosen (or, where none is, to
 * the folder shown), by the chord its action names until the user binds another.
 */
@Immutable
data class Custom(
	val id: String,
	override val label: String,
	val default: Chord?,
) : Scoped {
	/**
	 * [action], as something bound. It is known by its id, and not by what it runs: the same action is read with one
	 * program's command on one machine and another's on another, and is the same action to bind.
	 */
	constructor(action: UserAction) : this(action.id, action.label, action.key)

	override val key: String get() = "action:$id"
	override val scope: Scope get() = Scope.Listing
	override val group: KeyGroup get() = KeyGroups.Own
	override val defaults: List<Chord> get() = listOfNotNull(default)

	// What the group says of them all.
	override val where: String? get() = null
}

/**
 * Everything the browser does from the keyboard or a mouse button, with the chords that do it by default, in the
 * order the window that binds them lists them: each [group] together.
 */
enum class Action(
	override val label: String,
	override val scope: Scope,
	override val group: KeyGroup,
	vararg defaults: String,
) : Scoped {
	Back("Back", Scope.Window, KeyGroups.Places, "Alt+Left", "Mouse Back"),
	Forward("Forward", Scope.Window, KeyGroups.Places, "Alt+Right", "Mouse Forward"),
	Up("Folder above", Scope.Window, KeyGroups.Places, "Alt+Up", "Backspace"),
	EditAddress("Type a path", Scope.Window, KeyGroups.Places, "Ctrl+L", "Alt+D"),
	SearchFolder("Search this folder", Scope.Window, KeyGroups.Finding, "Ctrl+F", "F3"),
	SearchIndex("Search everything", Scope.Window, KeyGroups.Finding, "F2"),
	Refresh("Read again", Scope.Window, KeyGroups.View, "F5", "Ctrl+R"),
	ToggleHidden("Show hidden files and folders", Scope.Window, KeyGroups.View, "Ctrl+H"),
	ViewList("View as a list", Scope.Window, KeyGroups.View, "Ctrl+1"),
	ViewGallery("View as a gallery", Scope.Window, KeyGroups.View, "Ctrl+2"),
	ZoomIn("Larger", Scope.Window, KeyGroups.View, "Ctrl+Equals", "Ctrl+Plus", "Ctrl+Add"),
	ZoomOut("Smaller", Scope.Window, KeyGroups.View, "Ctrl+Minus", "Ctrl+Subtract"),
	ZoomReset("Usual size", Scope.Window, KeyGroups.View, "Ctrl+0", "Ctrl+NumPad0"),
	MoveUp("Up a row", Scope.Movement, KeyGroups.Cursor, "Up"),
	MoveDown("Down a row", Scope.Movement, KeyGroups.Cursor, "Down"),
	MoveLeft("Left, in the gallery", Scope.Movement, KeyGroups.Cursor, "Left"),
	MoveRight("Right, in the gallery", Scope.Movement, KeyGroups.Cursor, "Right"),
	PageUp("Up a page", Scope.Movement, KeyGroups.Cursor, "PageUp"),
	PageDown("Down a page", Scope.Movement, KeyGroups.Cursor, "PageDown"),
	First("The first row", Scope.Movement, KeyGroups.Cursor, "Home"),
	Last("The last row", Scope.Movement, KeyGroups.Cursor, "End"),
	Toggle("Choose, or no longer", Scope.Listing, KeyGroups.Choosing, "Space"),
	SelectAll("Choose every row", Scope.Listing, KeyGroups.Choosing, "Ctrl+A"),
	Cancel(
		"Cancel, or let go of what is chosen",
		Scope.Leftover,
		KeyGroups.Choosing,
		"Escape",
	),
	Open("Open", Scope.Listing, KeyGroups.Files, "Enter"),
	Rename("Rename", Scope.Listing, KeyGroups.Files, "F6"),
	CopyFiles("Copy", Scope.Listing, KeyGroups.Files, "Ctrl+C"),
	CutFiles("Cut", Scope.Listing, KeyGroups.Files, "Ctrl+X"),
	Paste("Paste", Scope.Listing, KeyGroups.Files, "Ctrl+V"),
	CopyPaths("Copy the paths", Scope.Listing, KeyGroups.Files, "Ctrl+Shift+C"),
	Trash(
		"Move to the trash (deleting, where a drive has none, once asked)",
		Scope.Listing,
		KeyGroups.Files,
		"Delete",
	),
	Erase("Delete for good, once asked", Scope.Listing, KeyGroups.Files, "Shift+Delete"),
	NewFolder("New folder", Scope.Window, KeyGroups.Files, "Ctrl+Shift+N"),
	NewFile("New file", Scope.Window, KeyGroups.Files),
	Properties("Properties", Scope.Listing, KeyGroups.Files, "Alt+Enter"),
	ContextMenu(
		"The menu of what is chosen",
		Scope.Listing,
		KeyGroups.Files,
		"Shift+F10",
		"ContextMenu",
	),
	OpenTerminal("Open a terminal here", Scope.Window, KeyGroups.Files, "Shift+F4"),
	Undo("Undo", Scope.Leftover, KeyGroups.History, "Ctrl+Z"),
	Redo("Redo", Scope.Leftover, KeyGroups.History, "Ctrl+Shift+Z", "Ctrl+Y"),
	NewWindow("New window", Scope.Window, KeyGroups.Windows, "Ctrl+N"),
	Preferences("Preferences", Scope.Window, KeyGroups.Windows, "Ctrl+P"),
	CloseWindow(
		"Close the window (a picker: cancel)",
		Scope.Window,
		KeyGroups.Windows,
		"Ctrl+W",
	),
	;

	override val key: String get() = name

	override val defaults: List<Chord> =
		defaults.map {
			Chord.parse(it)
				?: error("$name's default “$it” is not a chord")
		}

	companion object {
		/** What only an explorer does: a picker has no key for these, since it would do nothing with one. */
		val EXPLORING: Set<Action> =
			setOf(NewFile, OpenTerminal, NewWindow, Preferences, Properties)

		/** What a picker does, in the order it is listed. */
		val PICKING: List<Action> = entries - EXPLORING
	}
}

/** A mouse button a chord can be made of: those a person presses on purpose, and not the two every click is. */
enum class MouseButton(
	val label: String,
) {
	Middle("Mouse Middle"),
	Back("Mouse Back"),
	Forward("Mouse Forward"),
}

/** What a chord is pressed on: a key, by AWT's code for it (which Compose's keys carry too), or a mouse button. */
@Immutable
sealed interface Trigger {
	data class Key(
		val code: Int,
	) : Trigger

	data class Mouse(
		val button: MouseButton,
	) : Trigger
}

/** A key or a mouse button, with the modifiers held as it is pressed. */
@Immutable
data class Chord(
	val trigger: Trigger,
	val ctrl: Boolean = false,
	val shift: Boolean = false,
	val alt: Boolean = false,
	val meta: Boolean = false,
) {
	/** Whether no text field types it: it has Ctrl, Alt or Meta, or is a function key, Escape, or a mouse button. */
	val typesNothing: Boolean
		get() =
			ctrl || alt || meta ||
				when (trigger) {
					is Trigger.Mouse -> true
					is Trigger.Key -> trigger.code in Keys.untyped
				}

	/**
	 * Whether it types a character where text is typed: a letter, a digit, a mark or a space, with nothing but Shift
	 * held. A window whose keyboard is always in a field (the launcher's) can do nothing else with such a chord.
	 */
	val typesText: Boolean
		get() =
			!ctrl && !alt && !meta && (trigger as? Trigger.Key)?.code in Keys.printing

	/** The chord without Shift and Ctrl: what moving the cursor is matched by. */
	val bare: Chord get() = copy(ctrl = false, shift = false)

	/** As it is shown, and written in the preferences: `Ctrl+Shift+F`, `Alt+Left`, `Mouse Back`. */
	override fun toString(): String =
		(
			listOfNotNull(
				"Ctrl".takeIf {
					ctrl
				},
				"Alt".takeIf { alt },
				"Shift".takeIf { shift },
				"Meta".takeIf { meta },
			) +
				when (trigger) {
					is Trigger.Key -> Keys.nameOf(trigger.code)
					is Trigger.Mouse -> trigger.button.label
				}
		).joinToString("+")

	companion object {
		/** A chord from how it is written; null for text that is not one. */
		fun parse(text: String): Chord? {
			val parts = text.split('+').map { it.trim() }
			val modifiers = parts.dropLast(1).map { it.lowercase() }
			val last = parts.lastOrNull() ?: return null
			val trigger =
				MouseButton.entries
					.firstOrNull {
						it.label.equals(last, ignoreCase = true)
					}?.let(Trigger::Mouse)
					?: Keys.codeOf(last)?.let(Trigger::Key)
					?: return null

			if (modifiers.any { it !in setOf("ctrl", "shift", "alt", "meta") })
				return null

			return Chord(
				trigger,
				"ctrl" in modifiers,
				"shift" in modifiers,
				"alt" in modifiers,
				"meta" in modifiers,
			)
		}

		/** The chord [event] is: null for a modifier pressed alone, which is no chord yet. */
		fun of(event: KeyEvent): Chord? {
			val code = event.key.nativeKeyCode

			if (code in Keys.modifiers)
				return null

			return Chord(
				Trigger.Key(code),
				event.isCtrlPressed,
				event.isShiftPressed,
				event.isAltPressed,
				event.isMetaPressed,
			)
		}
	}
}

/** The names of keys, from AWT's: `VK_PAGE_UP` is `PageUp`, `VK_F5` is `F5`, `VK_A` is `A`. */
object Keys {
	private val named: Map<Int, String> by lazy {
		val renamed = mapOf("BACK_SPACE" to "Backspace", "NUMPAD" to "NumPad")

		AwtKeys::class.java.fields
			.filter { it.name.startsWith("VK_") && it.type == Int::class.javaPrimitiveType }
			.map { field ->
				val raw = field.name.removePrefix("VK_")

				val name =
					renamed[raw]
						?: raw.split('_').joinToString("") { word ->
							renamed.entries.firstOrNull { word.startsWith(it.key) }?.let {
								it.value +
									word.removePrefix(it.key)
							}
								?: (word.take(1) + word.drop(1).lowercase())
						}

				field.getInt(null) to name
			}.distinctBy { it.first }
			.toMap()
	}

	private val codes: Map<String, Int> by lazy {
		named.entries.associate { (code, name) ->
			name.lowercase() to
				code
		}
	}

	fun nameOf(code: Int): String = named[code] ?: "Key$code"

	fun codeOf(name: String): Int? =
		codes[name.lowercase()] ?: name.removePrefix("Key").toIntOrNull()

	/** The keys that are modifiers, and make no chord alone. */
	val modifiers: Set<Int> =
		setOf(
			AwtKeys.VK_SHIFT,
			AwtKeys.VK_CONTROL,
			AwtKeys.VK_ALT,
			AwtKeys.VK_META,
			AwtKeys.VK_ALT_GRAPH,
			AwtKeys.VK_WINDOWS,
		)

	/** The keys that type a character: the letters, the digits, the numeric pad's, the marks, and the space bar. */
	val printing: Set<Int> =
		(AwtKeys.VK_A..AwtKeys.VK_Z).toSet() + (AwtKeys.VK_0..AwtKeys.VK_9) +
			(AwtKeys.VK_NUMPAD0..AwtKeys.VK_DIVIDE) +
			setOf(
				AwtKeys.VK_SPACE,
				AwtKeys.VK_COMMA,
				AwtKeys.VK_MINUS,
				AwtKeys.VK_PERIOD,
				AwtKeys.VK_SLASH,
				AwtKeys.VK_SEMICOLON,
				AwtKeys.VK_EQUALS,
				AwtKeys.VK_OPEN_BRACKET,
				AwtKeys.VK_BACK_SLASH,
				AwtKeys.VK_CLOSE_BRACKET,
				AwtKeys.VK_BACK_QUOTE,
				AwtKeys.VK_QUOTE,
				AwtKeys.VK_PLUS,
			)

	/** The keys no text field types: the function keys, Escape, and the menu key. */
	val untyped: Set<Int> =
		(AwtKeys.VK_F1..AwtKeys.VK_F12).toSet() + (AwtKeys.VK_F13..AwtKeys.VK_F24) +
			AwtKeys.VK_ESCAPE +
			AwtKeys.VK_CONTEXT_MENU
}

/**
 * Every action's chords, as the user has them: the defaults, but where the preferences say otherwise. One set of
 * actions' own: an explorer's, a picker's, the launcher's.
 */
@Immutable
class Bindings(
	chords: Map<Bindable, List<Chord>>,
) {
	/**
	 * Each action's chords, none of them twice, however they were given: a chord is one of an action's places, and a
	 * file written by hand may say one twice.
	 */
	val chords: Map<Bindable, List<Chord>> = chords.mapValues { (_, its) -> its.distinct() }

	fun of(action: Bindable): List<Chord> = chords[action].orEmpty()

	/** Everything that can be bound here: the browser's own actions, then the user's. */
	val actions: List<Bindable> get() = chords.keys.toList()

	private val byChord: Map<Chord, List<Bindable>> by lazy {
		chords.entries
			.flatMap { (action, its) ->
				its.map { it to action }
			}.groupBy({ it.first }, { it.second })
	}

	/** What [chord] does, where everything bound acts in one place: the first thing bound to it. */
	fun action(chord: Chord): Bindable? = byChord[chord]?.firstOrNull()

	private fun find(chord: Chord, where: (Scope) -> Boolean): Bindable? =
		byChord[chord]?.firstOrNull { (it as? Scoped)?.scope?.let(where) == true }

	/** The window's action [chord] does from anywhere: one bound to a chord no text field types. */
	fun window(chord: Chord): Action? =
		(find(chord) { it == Scope.Window } as? Action).takeIf { chord.typesNothing }

	/** What [chord] does from the listing: a row's action, or a window's, or one left to whatever has the keyboard. */
	fun listing(chord: Chord): Bindable? = listings(chord).firstOrNull()

	/**
	 * Everything [chord] could do from the listing, what is tried first first: the rows' actions (the browser's own,
	 * then the user's), then the window's. A chord does one thing wherever it is pressed, but which thing can depend
	 * on what it is pressed on: Delete moves files to the trash, and removes entries from an archive, where an action
	 * of the user's says so; each is tried in turn until one has something to do.
	 */
	fun listings(chord: Chord): List<Bindable> =
		byChord[chord].orEmpty().filterIsInstance<Scoped>().let { bound ->
			bound.filter { it.scope == Scope.Listing } +
				bound.filter { it.scope == Scope.Window || it.scope == Scope.Leftover }
		}

	/** The cursor's move [chord] makes, Shift and Ctrl aside. */
	fun movement(chord: Chord): Action? =
		find(chord.bare) { it == Scope.Movement } as? Action

	/** What [chord] does where whatever had the keyboard did not use it. */
	fun leftover(chord: Chord): Action? = find(chord) { it == Scope.Leftover } as? Action

	/** These, with [action]'s chords [chords]. */
	fun with(action: Bindable, chords: List<Chord>): Bindings =
		Bindings(this.chords + (action to chords))

	/**
	 * The actions other than [action] that [chord] would also do: a chord does one thing. A move of the cursor claims its
	 * key with Shift and Ctrl too, since those say how it chooses as it moves.
	 */
	fun clashes(action: Bindable, chord: Chord): List<Bindable> =
		chords.entries
			.filter { (other, its) ->
				other != action && its.any { same(action, chord, other, it) }
			}.map { it.key }

	/**
	 * These, with [chord] as [action]'s chord at [index] (or after its last, for an index past them), and taken from
	 * every action it [clashes] with. A move of the cursor is bound to its key alone, Shift and Ctrl aside.
	 */
	fun bound(action: Bindable, index: Int, chord: Chord): Bindings {
		val given =
			if (action.moves)
				chord.bare
			else
				chord

		val freed =
			chords.mapValues { (other, its) ->
				if (other == action)
					its
				else
					its.filterNot { same(action, given, other, it) }
			}
		val mine = freed[action].orEmpty()
		val placed =
			if (index < mine.size)
				mine.mapIndexed { at, it -> if (at == index) given else it }
			else
				mine.plusElement(given)

		return Bindings(freed + (action to placed))
	}

	/** These, without [action]'s chord at [index]. */
	fun unbound(action: Bindable, index: Int): Bindings =
		with(action, of(action).filterIndexed { at, _ -> at != index })

	/** These, with [action]'s chords its defaults again. */
	fun reset(action: Bindable): Bindings = with(action, action.defaults)

	/** These, with every action's chords its defaults again. */
	fun defaults(): Bindings = Bindings(chords.keys.associateWith { it.defaults })

	/** Whether [its], one of [other]'s chords, is [chord] of [action]'s: the same, or, for a move, the same key. */
	private fun same(action: Bindable, chord: Chord, other: Bindable, its: Chord): Boolean =
		its == chord ||
			(
				(other.moves || action.moves) &&
					its.bare == chord.bare
			)

	/** What the preferences say of these: each action whose chords are not its defaults. */
	fun differences(): Map<String, List<String>> =
		chords.keys
			.filter { of(it) != it.defaults }
			.associate { action -> action.key to of(action).map { it.toString() } }

	override fun equals(other: Any?): Boolean = other is Bindings && chords == other.chords

	override fun hashCode(): Int = chords.hashCode()

	companion object {
		val DEFAULT =
			Bindings(Action.entries.associateWith<Bindable, List<Chord>> { it.defaults })

		/**
		 * The bindings [preferences] make for a window that is [role]. An explorer's are of the browser's own actions
		 * and of [actions], the user's: an action named in the preferences has the chords written there that read as
		 * chords. A picker's are those too, until the user gives pickers keys of their own; then they are of what a
		 * picker does, each with the chords written for pickers.
		 */
		fun of(
			preferences: Preferences,
			role: Role = Role.Explorer,
			actions: Actions = Actions.of(preferences),
		): Bindings {
			val written = preferences.pickerBindings.takeIf { role == Role.Picker }

			return if (written == null)
				of(Action.entries + actions.actions.map(::Custom), preferences.bindings)
			else
				of(Action.PICKING, written)
		}

		/** The bindings of [actions]: each as [written] has it, where it is named there, and its defaults where not. */
		fun of(actions: List<Bindable>, written: Map<String, List<String>>): Bindings =
			Bindings(
				actions.associateWith { action ->
					written[action.key]?.mapNotNull(Chord::parse) ?: action.defaults
				},
			)
	}
}

/** The bindings the view looks chords up in: the user's, given by whoever runs the browser. */
val LocalBindings = staticCompositionLocalOf { Bindings.DEFAULT }
