package prism.preferences

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * How the context menus are arranged: each menu's entries in order, with lines between groups of them, and when each
 * is shown, always, never, or only with keys held as the menu opens. An entry is known by its id: one of prism's own
 * (`builtin.copy-path`), one of the user's actions, or a submenu of them (`submenu:Compress`).
 *
 * A menu is one of a listing's, by the kind of listing (a folder, a search's results, the trash, a type of place) and
 * what it is of (a file, a folder, several, the empty space around them); or the sidebar's. A listing's menu arranged
 * for folders is every other listing's of the same, until that listing is given its own. What a menu arranges is how
 * the user left it; what the menu holds that it does not name (an action added since, say) goes where prism would have
 * it, next to what comes before it there.
 *
 * In the preferences file each place in a menu is a line of its own:
 *
 * ```json
 * "arranged": {
 *   "folder/space": ["builtin.undo", "builtin.redo", "-", "builtin.paste", "-", "builtin.refresh with Shift"],
 *   "sidebar": ["builtin.open", "builtin.properties", "builtin.arrange hidden"]
 * }
 * ```
 */

/** A key held as a context menu opens, which an entry can be shown only with. */
@Serializable
enum class Held(
	val label: String,
) {
	Shift("Shift"),
	Ctrl("Ctrl"),
	Alt("Alt"),
}

/** When an entry of a menu is shown: always, never, or only with [With.keys] held as the menu opens. */
sealed interface Show {
	/** Whether the entry is shown in a menu opened with [held] held. */
	fun admits(held: Set<Held>): Boolean

	data object Always : Show {
		override fun admits(held: Set<Held>): Boolean = true
	}

	data object Never : Show {
		override fun admits(held: Set<Held>): Boolean = false
	}

	/** Shown where every one of [keys] is held, whatever else is too. */
	data class With(
		val keys: Set<Held>,
	) : Show {
		init {
			require(keys.isNotEmpty()) { "an entry shown with keys held needs a key" }
		}

		override fun admits(held: Set<Held>): Boolean = held.containsAll(keys)
	}

	companion object {
		/** Shown only with [keys] held; always, where there are none. */
		fun with(keys: Set<Held>): Show = if (keys.isEmpty()) Always else With(keys)
	}
}

/** A place in a menu's arrangement: an entry, by its id, and when it is shown; or a line between groups. */
@Serializable(with = PlacedSerializer::class)
sealed interface Placed {
	data object Separator : Placed

	data class Slot(
		val id: String,
		val show: Show = Show.Always,
	) : Placed
}

/** What a listing's context menu is of: the row pressed, file or folder; several chosen; or the space around them. */
enum class MenuOf(
	val key: String,
	val label: String,
) {
	File("file", "File"),
	Folder("folder", "Folder"),
	Several("several", "Multiple items"),
	Space("space", "Empty space"),
}

/** Which menu an arrangement is for: one of a listing's, by its [kind] and what it is [of]; or the sidebar's. */
@Serializable(with = MenuKeySerializer::class)
sealed interface MenuKey {
	/** How it is named in the preferences file. */
	val key: String

	data class Listing(
		val kind: ListingKind,
		val of: MenuOf,
	) : MenuKey {
		override val key: String get() = "${kind.key}/${of.key}"
	}

	data object Sidebar : MenuKey {
		override val key = "sidebar"
	}

	companion object {
		/** [key] as the menu it names; null for one that names none. */
		fun of(key: String): MenuKey? =
			if (key == Sidebar.key)
				Sidebar
			else
				key
					.substringBeforeLast(
						'/',
						"",
					).takeIf { it.isNotEmpty() }
					?.let(ListingKind::of)
					?.let { kind ->
						MenuOf.entries
							.firstOrNull {
								it.key == key.substringAfterLast('/')
							}?.let { Listing(kind, it) }
					}
	}
}

object MenuKeySerializer : KSerializer<MenuKey> {
	override val descriptor =
		PrimitiveSerialDescriptor("prism.MenuKey", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: MenuKey) =
		encoder.encodeString(value.key)

	override fun deserialize(decoder: Decoder): MenuKey =
		decoder.decodeString().let {
			MenuKey.of(it)
				?: throw SerializationException("no menu “$it”")
		}
}

/**
 * A place in a menu as the preferences file writes it: `-` for a line; an entry's id for an entry always shown; with
 * ` hidden` after it for one never shown, or ` with ` and the keys (`Shift`, `Ctrl+Alt`) for one shown only with them.
 */
object PlacedSerializer : KSerializer<Placed> {
	override val descriptor = PrimitiveSerialDescriptor("prism.Placed", PrimitiveKind.STRING)

	private const val LINE = "-"
	private const val HIDDEN = " hidden"
	private const val WITH = " with "

	override fun serialize(encoder: Encoder, value: Placed) =
		encoder.encodeString(textOf(value))

	override fun deserialize(decoder: Decoder): Placed =
		decoder.decodeString().let {
			placedOf(it)
				?: throw SerializationException("no place in a menu “$it”")
		}

	fun textOf(placed: Placed): String =
		when (placed) {
			Placed.Separator -> LINE
			is Placed.Slot ->
				when (val show = placed.show) {
					Show.Always -> placed.id
					Show.Never -> placed.id + HIDDEN
					is Show.With ->
						placed.id + WITH +
							Held.entries.filter { it in show.keys }.joinToString("+") { it.label }
				}
		}

	/** [text] as a place in a menu; null where it is none. */
	fun placedOf(text: String): Placed? {
		val trimmed = text.trim()

		return when {
			trimmed == LINE || trimmed == "—" -> Placed.Separator
			trimmed.isEmpty() -> null
			trimmed.endsWith(HIDDEN) ->
				idOf(trimmed.removeSuffix(HIDDEN))?.let {
					Placed.Slot(it, Show.Never)
				}
			WITH in trimmed -> {
				val keys =
					trimmed.substringAfterLast(WITH).split('+').map { word ->
						Held.entries.firstOrNull { it.label.equals(word.trim(), ignoreCase = true) }
							?: return null
					}

				idOf(
					trimmed.substringBeforeLast(WITH),
				)?.let { Placed.Slot(it, Show.with(keys.toSet())) }
			}
			else -> Placed.Slot(trimmed, Show.Always)
		}
	}

	private fun idOf(text: String): String? = text.trim().takeIf { it.isNotEmpty() }
}
