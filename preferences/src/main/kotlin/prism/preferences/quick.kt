package prism.preferences

import java.nio.file.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The sidebar's quick access: prism's own places (what is recent, the system's drives, the home folder, its
 * well-known folders, the trash) and the folders the user pinned, in the user's order. Prism's own are always there,
 * each once: the user shows or hides one, and moves it, but cannot remove it, so a place this version adds is in
 * every user's list without asking, where prism's own order puts it among those already there.
 *
 * There is one list, in one order, for every window; what differs between the explorer's windows and the pickers'
 * is which of its entries each shows ([ShownIn]), and whether each shows the file systems under it.
 */

/** A place prism itself offers in quick access, in the order it offers them. */
@Serializable
enum class Builtin {
	/** What was lately made, and the folders lately picked from: the Recents page. */
	Recents,

	/** The machine's drives, and how full each is: the System page. */
	System,
	Home,
	Desktop,
	Documents,
	Downloads,
	Pictures,
	Music,
	Videos,
	Trash,
}

/**
 * A section of the Recents page, by what it holds: the application's folders are one section, whichever
 * application a picker is for.
 */
@Serializable
enum class RecentSection {
	/** The files lately made. */
	@SerialName("new-files")
	NewFiles,

	/** The files the application that asked was lately answered with. */
	@SerialName("application-files")
	ApplicationFiles,

	/** The files any application was. */
	@SerialName("files")
	Files,

	/** The folders the application that asked was lately answered with. */
	@SerialName("application")
	Application,

	/** The folders any application was. */
	@SerialName("folders")
	Folders,

	/** The folders gone to and picked from most. */
	@SerialName("frequent")
	Frequent,
}

/**
 * Which windows show something of the sidebar: the [explorer]'s, the [picker]s', both or neither. What an older
 * version wrote as one answer for every window (`true`, `false`) reads as that answer for both.
 */
@Serializable(with = ShownInSerializer::class)
data class ShownIn(
	val explorer: Boolean = true,
	val picker: Boolean = true,
) {
	/** Whether a window that is for [role] shows it. */
	operator fun get(role: Role): Boolean =
		when (role) {
			Role.Explorer -> explorer
			Role.Picker -> picker
		}

	/** This, with the windows for [role] showing it, or not. */
	fun with(role: Role, shown: Boolean): ShownIn =
		when (role) {
			Role.Explorer -> copy(explorer = shown)
			Role.Picker -> copy(picker = shown)
		}

	/** Whether no window shows it. */
	val nowhere: Boolean get() = !explorer && !picker

	companion object {
		val EVERYWHERE = ShownIn()
		val NOWHERE = ShownIn(explorer = false, picker = false)

		/** One answer for every window. */
		fun both(shown: Boolean): ShownIn =
			if (shown)
				EVERYWHERE
			else
				NOWHERE
	}
}

/** [ShownIn] as the file has it: `{"explorer": …, "picker": …}`; read from an older version's `true` or `false` too. */
object ShownInSerializer : KSerializer<ShownIn> {
	override val descriptor = JsonElement.serializer().descriptor

	override fun serialize(encoder: Encoder, value: ShownIn) =
		(encoder as JsonEncoder).encodeJsonElement(written(value))

	override fun deserialize(decoder: Decoder): ShownIn =
		read((decoder as JsonDecoder).decodeJsonElement())

	fun written(value: ShownIn): JsonElement =
		buildJsonObject {
			put("explorer", JsonPrimitive(value.explorer))
			put("picker", JsonPrimitive(value.picker))
		}

	fun read(element: JsonElement): ShownIn =
		when (element) {
			is JsonPrimitive -> ShownIn.both(element.booleanOrNull ?: true)
			is JsonObject ->
				ShownIn(
					explorer = element["explorer"]?.jsonPrimitive?.booleanOrNull ?: true,
					picker = element["picker"]?.jsonPrimitive?.booleanOrNull ?: true,
				)
			else -> ShownIn.EVERYWHERE
		}
}

/** One entry of quick access, and which windows show it ([shown]). */
@Serializable
sealed interface QuickItem {
	val shown: ShownIn

	/** This, shown as [shown] says. */
	fun showing(shown: ShownIn): QuickItem

	/** One of prism's own places. */
	@Serializable
	@SerialName("builtin")
	data class Own(
		val place: Builtin,
		override val shown: ShownIn = ShownIn.EVERYWHERE,
	) : QuickItem {
		override fun showing(shown: ShownIn): Own = copy(shown = shown)
	}

	/** A folder the user pinned. */
	@Serializable
	@SerialName("folder")
	data class Pinned(
		@Serializable(with = PathSerializer::class)
		val path: Path,
		override val shown: ShownIn = ShownIn.EVERYWHERE,
	) : QuickItem {
		override fun showing(shown: ShownIn): Pinned = copy(shown = shown)
	}
}

/**
 * Quick access, in order: every [Builtin] exactly once, and each folder pinned once; and which windows show the file
 * systems under it ([filesystems]). Made only by [of], which keeps it so, whatever it is given: a list read from a
 * file that leaves one of prism's own out, or names one twice.
 */
@Serializable(with = QuickAccessSerializer::class)
class QuickAccess private constructor(
	val items: List<QuickItem>,
	val filesystems: ShownIn,
) {
	/** The folders pinned, in order. */
	val pins: List<Path> get() = items.filterIsInstance<QuickItem.Pinned>().map { it.path }

	/** Whether a window for [role] shows [place]. */
	fun shows(place: Builtin, role: Role): Boolean =
		items.any {
			it is QuickItem.Own && it.place == place &&
				it.shown[role]
		}

	/** These, with [move] made. */
	fun moved(move: Moved): QuickAccess = QuickAccess(items.moved(move), filesystems)

	/** These, with the entry at [index] moved [by] places (up, for a negative [by]), as far as it can go. */
	fun moved(index: Int, by: Int): QuickAccess =
		moved(Moved.of(index, (index + by).coerceIn(0, items.lastIndex)))

	/** These, with the entry at [index] shown in the windows for [role], or not. */
	fun showing(index: Int, role: Role, shown: Boolean): QuickAccess =
		QuickAccess(
			items.mapIndexed { at, item ->
				if (at == index)
					item.showing(item.shown.with(role, shown))
				else
					item
			},
			filesystems,
		)

	/** These, with every entry shown in the windows for [role], or none. */
	fun showingAll(role: Role, shown: Boolean): QuickAccess =
		QuickAccess(items.map { it.showing(it.shown.with(role, shown)) }, filesystems)

	/** These, with [place] shown in the windows for [role], or not. */
	fun showing(place: Builtin, role: Role, shown: Boolean): QuickAccess =
		showing(items.indexOfFirst { it is QuickItem.Own && it.place == place }, role, shown)

	/** These, with the file systems shown under them in the windows for [role], or not. */
	fun showingFilesystems(role: Role, shown: Boolean): QuickAccess =
		QuickAccess(items, filesystems.with(role, shown))

	/**
	 * These, with [path] pinned, as a window for [role] pins it: a folder not pinned yet is pinned last and shown
	 * in every window (a pin is usually wanted in all of them; which show it is narrowed where quick access is
	 * arranged), and one pinned already, and shown elsewhere alone, is shown in these windows too.
	 */
	fun pinning(path: Path, role: Role): QuickAccess {
		val at = items.indexOfFirst { it is QuickItem.Pinned && it.path == path }

		return if (at < 0)
			of(items + QuickItem.Pinned(path), filesystems)
		else
			showing(at, role, true)
	}

	/**
	 * These, with [path] no longer shown in the windows for [role]: unpinned, where no other window shows it.
	 * Prism's own places are hidden, not removed: [showing].
	 */
	fun unpinning(path: Path, role: Role): QuickAccess =
		QuickAccess(
			items.mapNotNull { item ->
				if (item is QuickItem.Pinned && item.path == path)
					item.showing(item.shown.with(role, false)).takeUnless { it.shown.nowhere }
				else
					item
			},
			filesystems,
		)

	/** These, without [path] pinned at all. */
	fun removing(path: Path): QuickAccess =
		QuickAccess(items.filterNot { it is QuickItem.Pinned && it.path == path }, filesystems)

	override fun equals(other: Any?): Boolean =
		other is QuickAccess && other.items == items && other.filesystems == filesystems

	override fun hashCode(): Int = 31 * items.hashCode() + filesystems.hashCode()

	override fun toString(): String = "QuickAccess($items, filesystems=$filesystems)"

	companion object {
		/** Prism's own places, every one shown, in their own order; nothing pinned. */
		val DEFAULT: QuickAccess = of(emptyList())

		/**
		 * [items], each of prism's own places once (the first of any named twice) and each folder once, with any of
		 * prism's own left out added, shown, where prism's own order puts it: before the first of prism's own there
		 * that comes after it in that order, or at the end where none does. The Recents page, first of them, goes
		 * before whichever of prism's own the user has first.
		 */
		fun of(
			items: List<QuickItem>,
			filesystems: ShownIn = ShownIn.EVERYWHERE,
		): QuickAccess {
			val distinct =
				items.distinctBy {
					when (it) {
						is QuickItem.Own -> it.place
						is QuickItem.Pinned -> it.path
					}
				}
			val named = distinct.filterIsInstance<QuickItem.Own>().map { it.place }.toSet()

			return QuickAccess(
				Builtin.entries
					.filterNot { it in named }
					.fold(distinct) { placed, missing ->
						val before =
							placed.indexOfFirst { it is QuickItem.Own && it.place.ordinal > missing.ordinal }

						if (before < 0)
							placed + QuickItem.Own(missing)
						else
							placed.take(before) + QuickItem.Own(missing) + placed.drop(before)
					},
				filesystems,
			)
		}
	}
}

/**
 * Quick access as the file writes it: `{"items": […], "filesystems": {…}}`, read back through [QuickAccess.of]. An
 * older version wrote the entries alone, as a list, which is read as those with the file systems shown everywhere.
 */
object QuickAccessSerializer : KSerializer<QuickAccess> {
	private val list = ListSerializer(QuickItem.serializer())

	override val descriptor = JsonElement.serializer().descriptor

	override fun serialize(encoder: Encoder, value: QuickAccess) {
		val json = (encoder as JsonEncoder).json

		encoder.encodeJsonElement(
			buildJsonObject {
				put("items", json.encodeToJsonElement(list, value.items))
				put("filesystems", ShownInSerializer.written(value.filesystems))
			},
		)
	}

	override fun deserialize(decoder: Decoder): QuickAccess {
		val json = (decoder as JsonDecoder).json

		return when (val read = decoder.decodeJsonElement()) {
			is JsonArray -> QuickAccess.of(json.decodeFromJsonElement(list, read))
			else ->
				QuickAccess.of(
					read.jsonObject["items"]?.let { json.decodeFromJsonElement(list, it) }.orEmpty(),
					read.jsonObject["filesystems"]?.let(ShownInSerializer::read) ?: ShownIn.EVERYWHERE,
				)
		}
	}
}
