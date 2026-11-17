package prism.preferences

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The user's own actions, places and text searches, as the preferences file writes them: the shapes of the JSON, and
 * nothing of what they mean. An action is something a context menu offers (or a key does) beyond what prism does
 * itself; a place is something a window shows that is not a folder on disk, listed by a command (docs/menus.md); a
 * text search is a command that finds lines in the files a window shows, which the window then shows in their place
 * (docs/text.md).
 *
 * Each of them is read on its own, so one written wrong is said to be, with why, and the rest are still
 * read. What they mean, and whether what they say can be done (a placeholder nothing fills, a place nothing declares),
 * is checked by whoever uses them (the browser's actions.kt), which says so in the same way.
 */

/** An action as it is written. */
@Serializable
data class ActionSpec(
	val id: String,
	val label: String,
	/** When it is offered: every condition named must hold. */
	@SerialName("when")
	val conditions: WhenSpec = WhenSpec(),
	/** What is asked for before anything is done: each question by the name its answer is used under. */
	val ask: Map<String, AskSpec> = emptyMap(),
	/** Answers given beforehand to the questions of the action this one is [like]. */
	val given: Map<String, String> = emptyMap(),
	/** A command, as its words: what the action runs. One of this, [show] and [like]. */
	val run: List<String>? = null,
	/**
	 * Several commands that do the same with different programs, the most to be preferred first: the first whose
	 * programs are all installed is what the action runs. In place of [run].
	 */
	val ways: List<WaySpec> = emptyList(),
	/** Somewhere gone to: what the action shows. */
	val show: ShowSpec? = null,
	/** Another action's id: this one does what that one does, with the answers [given]. */
	val like: String? = null,
	val how: HowSpec = HowSpec(),
	val then: ThenSpec = ThenSpec(),
	/** The submenu it is offered in, levels apart by `/`: `Archive`, `Image/Convert`. */
	val submenu: String? = null,
	/** The chord that does it, until the user binds another. */
	val key: String? = null,
	/** Whether a double click (or Enter) on what it is offered for does it, in place of opening. */
	val default: Boolean = false,
)

/** When an action is offered. */
@Serializable
data class WhenSpec(
	/** MIME types, or patterns of them (`image/` and a star): each file's type, or one it is a kind of, is one. */
	val types: List<String>? = null,
	/** Globs of names: each file's name matches one. */
	val names: List<String>? = null,
	/** Extensions, without their dot (`zip`, `tar.gz`): each file's name ends in one. */
	val ext: List<String>? = null,
	/** `file`, `folder` or `link`: what each is. */
	val kind: String? = null,
	/** `one`, `some` (one or more) or `many` (two or more). */
	val count: String? = null,
	/** Folders each is under; `~` is the home folder. */
	val under: List<String>? = null,
	/** Names (`.git`) one of which each is, or is in a folder holding. */
	val inside: List<String>? = null,
	/** Programs every one of which is on the `PATH`. */
	val programs: List<String>? = null,
	/** What the menu is of: `files` (unless said), `folder` (the folder shown itself), or `place` (the sidebar's). */
	val target: String? = null,
	/** Where the window is: `folder`, `results`, or a place's id. */
	@SerialName("in")
	val within: List<String>? = null,
	/** Whether each is shown somewhere other than the folder it is in (a search's results, a place of real files). */
	val elsewhere: Boolean? = null,
	/** Whether each is a file on disk (true), or an entry only its place knows (false). */
	val real: Boolean? = null,
	/** Globs one of which each named value of the place shown matches: `{"archive": ["*.zip"]}`. */
	val with: Map<String, List<String>>? = null,
	/** Conditions that must not all hold. */
	val not: WhenSpec? = null,
)

/** A question: a line of text to type, or a folder to choose. Exactly one of [text] and [folder] is its prompt. */
@Serializable
data class AskSpec(
	val text: String? = null,
	val folder: String? = null,
	/** What the text starts as, or the folder the choosing starts in. */
	val start: String? = null,
)

/** Somewhere to go: a folder ([folder]), or a declared place ([place], opened [with] its values). */
@Serializable
data class ShowSpec(
	val folder: String? = null,
	/** The name in [folder] the cursor lands on. */
	val choose: String? = null,
	val place: String? = null,
	val with: Map<String, String> = emptyMap(),
	/** `here` or `window`; unless said, a folder is shown here and a place where it says it opens. */
	@SerialName("in")
	val within: String? = null,
)

/** How a command is run: detached and forgotten unless one of these says otherwise. */
@Serializable
data class HowSpec(
	/** Run in the user's terminal. */
	val terminal: Boolean = false,
	/** Run by prism, shown while it runs, and what it wrote to standard error said if it fails. */
	val watched: Boolean = false,
	/** A question asked first, to be answered yes. */
	val confirm: String? = null,
)

/** What follows a watched command that worked. */
@Serializable
data class ThenSpec(
	/** What is shown is read again. */
	val reread: Boolean = false,
	/** The name the cursor lands on. */
	val choose: String? = null,
	/** What the command made in one folder, brought into another, each by its own name and over nothing. */
	val bring: BringSpec? = null,
)

/**
 * What a command put in [from] brought into [to]: each entry the action was for, from where the command put it (under
 * the folders it sits in) to [to] by its own name; or, where the action names no entries, everything in [from].
 * Nothing already in [to] is written over.
 */
@Serializable
data class BringSpec(
	val from: String,
	val to: String,
)

/** One way of doing what an action does (or of listing a place): the programs it needs, and its command. */
@Serializable
data class WaySpec(
	/** Programs that must be on the `PATH` for this way; `a|b` is either. */
	val programs: List<String> = emptyList(),
	/** The command, for an action. */
	val run: List<String>? = null,
	/** The command, for a place's listing. */
	val list: List<String>? = null,
	/** For a text search: how what this way's command prints is read, where that is not as the search says. */
	val read: String? = null,
	/** For a text search: whether a NUL character ends each path this way's command prints, where that is not as the search says. */
	val nul: Boolean? = null,
)

/** A place as it is written. */
@Serializable
data class PlaceSpec(
	val id: String,
	/** What it is called, in the window's title and above the listing. */
	val title: String? = null,
	/** The values it is opened with, by name: what its commands, and the actions offered in it, can name. */
	val takes: List<String> = emptyList(),
	/** `here` (unless said) or `window`: where an action that shows it opens it. */
	val opens: String? = null,
	/** Whether a window in it shows the sidebar of places on disk: not, for a place that is a tool of its own. */
	val sidebar: Boolean = true,
	/** The command that lists it, as its words. */
	val list: List<String>? = null,
	/** Several commands that list it with different programs, the most to be preferred first. In place of [list]. */
	val ways: List<WaySpec> = emptyList(),
	/** The folder [list] is run in, and relative paths it prints are in; the home folder unless said. */
	val folder: String? = null,
	/** How what [list] prints is read: `paths` (unless said), `names`, or `entries`. */
	val read: String? = null,
	/** For `paths`: whether each is shown under the folders it is in, below [folder], and not all together. */
	val tree: Boolean = false,
	/** Whether what is printed is separated by NUL characters, and not by lines. */
	val nul: Boolean = false,
	/** The action that brings its entries out into a folder: what dragging them, or copying and pasting them, does. */
	val out: String? = null,
	/** The action that takes files in: what dropping them on it, or pasting them, does. */
	@SerialName("in")
	val into: String? = null,
)

/**
 * A text search as it is written: a command given the files a window shows, whose lines of output are lines found in
 * them, shown in the window grouped by file, each file beside its lines (docs/text.md).
 */
@Serializable
data class TextSearchSpec(
	val id: String,
	val label: String,
	/** When it is offered: every condition named must hold. Whatever the menu is of, unless `target` says. */
	@SerialName("when")
	val conditions: WhenSpec = WhenSpec(),
	/** The submenu it is offered in, levels apart by `/`. */
	val submenu: String? = null,
	/** The chord that opens it on everything the window shows, until the user binds another. */
	val key: String? = null,
	/**
	 * What is typed into it: `typed` (unless said), which the command is run again for as it changes, and names as
	 * `{query}`; or `narrows`, which the command is run once without, and which narrows what it printed.
	 */
	val query: String? = null,
	/** What can be turned on beside the query, each by its name: the words `{options}` becomes say which are. */
	val options: Map<String, OptionSpec> = emptyMap(),
	/** The command, as its words. One of this and [ways]. */
	val run: List<String>? = null,
	/** Several commands that find the same with different programs, the most to be preferred first. */
	val ways: List<WaySpec> = emptyList(),
	/**
	 * What it is given on its standard input: `files` (unless said), each ended by a NUL character, to find lines in;
	 * or `lines`, the lines found already, to keep some of, saying which by the number each has among them.
	 */
	val takes: String? = null,
	/** How what it prints is read: `lines` (unless said), `rg-json`, `json`, or `paths` (files, and no lines of them). */
	val read: String? = null,
	/** Whether a NUL character ends each path, as `grep -Z` and `rg -0` print them, and not a colon or a line's end. */
	val nul: Boolean = false,
	/** What the number printed with each line is: `lines` (unless said), a line of the file; or `offsets`, a byte of it. */
	val positions: String? = null,
	/** The command whose output is shown beside a line found: the file, coloured. Prism reads the file, where none is said. */
	val preview: List<String>? = null,
	/** Several commands that show the same with different programs: the first whose programs are installed. */
	val previews: List<WaySpec> = emptyList(),
	/** What opening a line found does, in place of the user's editor. */
	val open: OpenSpec? = null,
)

/** Something turned on or off beside a text search's query: the words its command is given when it is [on], and when [off]. */
@Serializable
data class OptionSpec(
	val label: String,
	val on: List<String> = emptyList(),
	val off: List<String> = emptyList(),
)

/** A command that opens a file at a line: its words, and whether it runs in the user's terminal. */
@Serializable
data class OpenSpec(
	val run: List<String>,
	val terminal: Boolean = false,
)

/** How the context menus are arranged (menus.kt), and what of them is left out wherever it would be. */
@Serializable
data class MenuSpec(
	/** The ids of entries not shown in any menu: an action's, or one of prism's own (`builtin.copy-path`). */
	val hidden: List<String> = emptyList(),
	/** Each menu the user has arranged: its entries in order, and when each is shown. */
	val arranged: Map<MenuKey, List<Placed>> = emptyMap(),
) {
	/**
	 * How the menu [key] is arranged: as it was arranged itself; or, a listing's other than a folder's, as the same
	 * menu of a folder was; or not at all (null), as prism has it.
	 */
	fun arrangementOf(key: MenuKey): List<Placed>? =
		arranged[key]
			?: (key as? MenuKey.Listing)
				?.takeIf { it.kind != ListingKind.Folder }
				?.let { arranged[MenuKey.Listing(ListingKind.Folder, it.of)] }

	/** Whether the menu [key] is arranged itself, rather than following another's or prism's. */
	fun arrangesItself(key: MenuKey): Boolean = key in arranged

	/** These, with the menu [key] arranged as [placed]; as it would be otherwise, where [placed] is null. */
	fun arranging(key: MenuKey, placed: List<Placed>?): MenuSpec =
		copy(
			arranged =
				if (placed == null)
					arranged - key
				else
					arranged + (key to placed),
		)
}

/** Something read from the preferences one at a time: what it is, or why it could not be read. */
sealed interface Spec<out T> {
	data class Read<T>(
		val value: T,
	) : Spec<T>

	/** [id], where that much could be read, did not read: [why]. */
	data class Unread(
		val id: String?,
		val why: String,
	) : Spec<Nothing>
}

/** Actions, places and text searches together: what a file of them holds, and what the preferences hold of them. */
@Serializable
data class ActionsFile(
	val actions: List<JsonElement> = emptyList(),
	val places: List<JsonElement> = emptyList(),
	val textSearches: List<JsonElement> = emptyList(),
	/** What opens a file at a line, as the user says it: their editor. */
	val editor: OpenSpec? = null,
) {
	fun actionSpecs(): List<Spec<ActionSpec>> =
		actions.map {
			read(ActionSpec.serializer(), it)
		}

	fun placeSpecs(): List<Spec<PlaceSpec>> = places.map { read(PlaceSpec.serializer(), it) }

	fun textSearchSpecs(): List<Spec<TextSearchSpec>> =
		textSearches.map { read(TextSearchSpec.serializer(), it) }

	companion object {
		// Strict about names: a condition written wrong (`"typs"`) must be said, not taken as no condition at all.
		private val json = Json
		private val lenient = Json { ignoreUnknownKeys = true }

		/** A file of actions and places, as prism's own are kept. */
		fun parse(text: String): ActionsFile = lenient.decodeFromString(serializer(), text)

		private fun <T> read(
			serializer: kotlinx.serialization.KSerializer<T>,
			element: JsonElement,
		): Spec<T> =
			try {
				Spec.Read(json.decodeFromJsonElement(serializer, element))
			} catch (failed: SerializationException) {
				Spec.Unread(
					idOf(element),
					failed.message?.lineSequence()?.firstOrNull() ?: failed.toString(),
				)
			} catch (failed: IllegalArgumentException) {
				Spec.Unread(idOf(element), failed.message ?: failed.toString())
			}

		private fun idOf(element: JsonElement): String? =
			try {
				(element as? JsonObject)?.get("id")?.jsonPrimitive?.content
			} catch (notText: IllegalArgumentException) {
				null
			}
	}
}
