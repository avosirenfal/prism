package prism.preferences

import java.nio.file.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * What the user has set, as one immutable value. Every window reads the same one, and a change to it is a new value
 * every window is given (store.kt). Anything not set is the default, so a file that sets nothing, or names a setting
 * this version does not know, still reads.
 */

/** What the user has set. */
@Serializable
data class Preferences(
	/**
	 * The places pinned to the sidebar's quick access, in order; null for the desktop's own (the home folder and its
	 * well-known folders), which is what it is until the user pins or unpins something.
	 */
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
	 * The command "Open terminal here" runs, as its words, in the folder it is for; null for the desktop's own
	 * terminal, as the platform finds it.
	 */
	val terminal: List<String>? = null,
) {
	/** These, with [pinned] in place of the places pinned. */
	fun pinning(pinned: List<Path>): Preferences = copy(pinned = pinned)

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
