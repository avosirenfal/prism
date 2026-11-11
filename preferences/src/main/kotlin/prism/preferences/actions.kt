package prism.preferences

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The user's own actions and places, as the preferences file writes them: the shapes of the JSON, and nothing of what
 * they mean. An action is something a context menu offers (or a key does) beyond what prism does itself; a place is
 * something a window shows that is not a folder on disk, listed by a command (docs/menus.md).
 *
 * Each action and each place is read on its own, so one written wrong is said to be, with why, and the rest are still
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
	val list: List<String>,
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

/** What of the menus is left out. */
@Serializable
data class MenuSpec(
	/** The ids of entries not shown: an action's, or one of prism's own (`builtin.copy-path`). */
	val hidden: List<String> = emptyList(),
)

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

/** Actions and places together: what a file of them holds, and what the preferences hold of them. */
@Serializable
data class ActionsFile(
	val actions: List<JsonElement> = emptyList(),
	val places: List<JsonElement> = emptyList(),
) {
	fun actionSpecs(): List<Spec<ActionSpec>> =
		actions.map {
			read(ActionSpec.serializer(), it)
		}

	fun placeSpecs(): List<Spec<PlaceSpec>> = places.map { read(PlaceSpec.serializer(), it) }

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
