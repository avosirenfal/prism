package prism.preferences

import java.nio.file.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import prism.desktop.TerminalChoice

/*
 * What the user has set, as one immutable value. Every window reads the same one, and a change to it is a new value
 * every window is given (store.kt). Anything not set is the default, so a file that sets nothing, or names a setting
 * this version does not know, still reads.
 */

/** What the user has set. */
@Serializable
data class Preferences(
	/**
	 * The sidebar's quick access, as the user arranged it; null where they have not, which is prism's own places, in
	 * their own order, unless an older version's [pinned] says otherwise.
	 */
	val quickAccess: QuickAccess? = null,
	/**
	 * What an older version kept in place of [quickAccess]: the places pinned, in order, the desktop's own among them.
	 * Read where [quickAccess] is not, and written no more.
	 */
	@OptIn(ExperimentalSerializationApi::class)
	@EncodeDefault(EncodeDefault.Mode.NEVER)
	val pinned: List<
		@Serializable(with = PathSerializer::class)
		Path,
	>? = null,
	/**
	 * Key and mouse bindings that differ from the defaults: each action's name, and the chords that do it, written as
	 * they are shown (`Ctrl+Shift+F`, `Alt+Left`, `Mouse Back`). An action not named here has its default chords; one
	 * named with none has none.
	 */
	val bindings: Map<String, List<String>> = emptyMap(),
	/**
	 * The terminal "Open terminal here" opens, and what runs in a terminal runs in: the desktop's own, as the platform
	 * finds it, unless one is chosen here (terminal.kt).
	 */
	@Serializable(with = TerminalChoiceSerializer::class)
	val terminal: TerminalChoice = TerminalChoice.Found,
	/**
	 * The user's own actions, each as it is written (actions.kt): what the context menus offer beyond prism's own. Each
	 * is read on its own, so that one written wrong does not lose the rest.
	 */
	val actions: List<JsonElement> = emptyList(),
	/** The user's own places: what a window shows that is not a folder, as a command lists it. */
	val places: List<JsonElement> = emptyList(),
	/** What of the menus is left out. */
	val menu: MenuSpec = MenuSpec(),
	/**
	 * How a new window lists a folder ([folders]) and a search's results ([results]). A window keeps the look the user
	 * gives it as its own from then on, wherever it goes, and these change no window already open.
	 */
	val folders: FolderView = FolderView(),
	val results: ResultsView = ResultsView(),
	/** How large the text of each part of a window is. */
	val text: TextSizes = TextSizes(),
	/** How large the listing, the sidebar and the other windows are drawn. */
	val scales: Scales = Scales(),
	/** How dates are written. */
	val dates: DateFormat = DateFormat.Written,
) {
	/** The actions and places written here. */
	val written: ActionsFile get() = ActionsFile(actions, places)

	/** These, with quick access as [quick]: what an older version kept in its place forgotten. */
	fun arranging(quick: QuickAccess): Preferences = copy(quickAccess = quick, pinned = null)

	/** These, with [action]'s chords set to [chords], or put back to its defaults where [chords] is null. */
	fun binding(action: String, chords: List<String>?): Preferences =
		copy(
			bindings =
				if (chords == null)
					bindings - action
				else
					bindings + (action to chords),
		)

	companion object {
		/** Nothing set: every default. */
		val DEFAULT = Preferences()
	}
}

/** A path as the file writes it: as text, absolute as it was given. */
object PathSerializer : KSerializer<Path> {
	override val descriptor = PrimitiveSerialDescriptor("prism.Path", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: Path) =
		encoder.encodeString(value.toString())

	override fun deserialize(decoder: Decoder): Path = Path.of(decoder.decodeString())
}
