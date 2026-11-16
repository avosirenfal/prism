package prism.preferences

import kotlin.math.roundToInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A whole number of one kind, kept within its kind's bounds: there is none outside them, whatever it was made from (a
 * file written by hand, a step past the last), so nothing that reads one has to bring it within them first.
 */
interface Bounded<T : Bounded<T>> {
	val number: Int

	/** The kind it is of. */
	val bounds: Bounds<T>
}

/** This and [more], or the nearest to it there is. */
operator fun <T : Bounded<T>> T.plus(more: Int): T = bounds.of(number + more)

/** Whether there is none less. */
val Bounded<*>.least: Boolean get() = number == bounds.range.first

/** Whether there is none greater. */
val Bounded<*>.most: Boolean get() = number == bounds.range.last

/**
 * A kind of bounded number: the [range] its numbers are within, and how one is made ([of]) and written, as the plain
 * number it is: read from a file, a number outside the range is the nearest within it, never refused.
 */
abstract class Bounds<T : Bounded<T>>(
	name: String,
	val range: IntRange,
	private val make: (Int) -> T,
) : KSerializer<T> {
	/** [number], or the nearest to it within the range. */
	fun of(number: Int): T = make(number.coerceIn(range))

	override val descriptor: SerialDescriptor =
		PrimitiveSerialDescriptor(name, PrimitiveKind.INT)

	override fun serialize(encoder: Encoder, value: T) = encoder.encodeInt(value.number)

	override fun deserialize(decoder: Decoder): T = of(decoder.decodeInt())
}

/** How wide the launcher's window is, in the units text is sized in. */
@Serializable(with = LauncherWidth.Companion::class)
@JvmInline
value class LauncherWidth private constructor(
	override val number: Int,
) : Bounded<LauncherWidth> {
	override val bounds: Bounds<LauncherWidth> get() = Companion

	override fun toString(): String = "$number"

	companion object : Bounds<LauncherWidth>(
		"prism.LauncherWidth",
		480..1400,
		::LauncherWidth,
	)
}

/** How many of the launcher's results are in sight at once. */
@Serializable(with = LauncherRows.Companion::class)
@JvmInline
value class LauncherRows private constructor(
	override val number: Int,
) : Bounded<LauncherRows> {
	override val bounds: Bounds<LauncherRows> get() = Companion

	override fun toString(): String = "$number"

	companion object : Bounds<LauncherRows>("prism.LauncherRows", 4..20, ::LauncherRows)
}

/** The most files a search of the launcher's lists. */
@Serializable(with = FileResults.Companion::class)
@JvmInline
value class FileResults private constructor(
	override val number: Int,
) : Bounded<FileResults> {
	override val bounds: Bounds<FileResults> get() = Companion

	override fun toString(): String = "$number"

	companion object : Bounds<FileResults>("prism.FileResults", 1..200, ::FileResults)
}

/**
 * How large the launcher is drawn: [times] the size every window starts at, within what can be read and laid out
 * ([Scales.SMALLEST] to [Scales.LARGEST]), whatever it was made from.
 */
@Serializable(with = LauncherScale.Companion::class)
@JvmInline
value class LauncherScale private constructor(
	val times: Float,
) {
	/** This, [tenths] tenths larger, or the nearest to it there is. */
	fun stepped(tenths: Int): LauncherScale = of(((times * 10).roundToInt() + tenths) / 10f)

	val least: Boolean get() = times <= Scales.SMALLEST
	val most: Boolean get() = times >= Scales.LARGEST

	companion object : KSerializer<LauncherScale> {
		/** [times], or the nearest to it the launcher can be drawn at. */
		fun of(times: Float): LauncherScale =
			LauncherScale(times.coerceIn(Scales.SMALLEST, Scales.LARGEST))

		override val descriptor: SerialDescriptor =
			PrimitiveSerialDescriptor("prism.LauncherScale", PrimitiveKind.FLOAT)

		override fun serialize(encoder: Encoder, value: LauncherScale) =
			encoder.encodeFloat(value.times)

		override fun deserialize(decoder: Decoder): LauncherScale = of(decoder.decodeFloat())
	}
}

/**
 * The launcher's key on a Mac where none is set: ⌘Space, Spotlight's, which the launcher stands in for. Spotlight's own
 * is turned off for it (System Settings › Keyboard › Keyboard Shortcuts › Spotlight); until it is, Spotlight has the key.
 */
const val DEFAULT_HOT_KEY = "Cmd+Space"

/** How the launcher looks, what it finds, and its keys. */
@Serializable
data class LauncherPreferences(
	/** How wide its window is. */
	val width: LauncherWidth = LauncherWidth.of(760),
	/** How many results are in sight at once; the rest are scrolled to. */
	val rows: LauncherRows = LauncherRows.of(9),
	/** How large it is drawn: 1 is the size every window starts at. */
	val scale: LauncherScale = LauncherScale.of(1f),
	/**
	 * Whether its corners are round, where its window can be clear at them: where what is behind a window shows
	 * through what it does not paint (a Wayland compositor's surfaces, an X11 screen that is composited). Where it
	 * cannot be, its corners are square, whatever this says.
	 */
	val rounded: Boolean = true,
	/** Whether files and folders are found too, after the applications, by a search of the index. */
	val files: Boolean = true,
	/** The most files a search lists. */
	val fileResults: FileResults = FileResults.of(30),
	/** Whether the other things an application can be started to do (a new private window) are found. */
	val actions: Boolean = true,
	/** Whether it closes when another window is given the keyboard. */
	val closeUnfocused: Boolean = true,
	/**
	 * Whether it is shown over every window, fullscreen ones included, in the middle of the screen, with the keyboard
	 * its own until it is put away, where the platform has such a surface (Wayland's layer-shell); otherwise, and
	 * where the platform has none, it is a window that floats, placed and stacked as the window manager does any.
	 */
	val overlay: Boolean = true,
	/** The applications left out of it, by the desktop's names for them. */
	val hidden: Set<String> = emptySet(),
	/**
	 * On a Mac, the key that shows it from anywhere and puts it away, written as a chord is shown (`Cmd+Space`,
	 * `Option+Space`); none for [DEFAULT_HOT_KEY]. Elsewhere the launcher's key is
	 * the window manager's to bind, and this is not read.
	 */
	val hotKey: String? = null,
	/**
	 * Its keys that differ from the defaults: each action's name, and the chords that do it, written as they are
	 * shown (`Ctrl+Enter`). An action not named here has its default chords; one named with none has none.
	 */
	val bindings: Map<String, List<String>> = emptyMap(),
)
