package prism.preferences

import java.nio.file.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * The sidebar's quick access: prism's own places (what is recent, the system's drives, the home folder, its well-known
 * folders, the trash) and the folders the user pinned, in the user's order. Prism's own are always there, each once: the user shows
 * or hides one, and moves it, but cannot remove it, so a place this version adds is in every user's list without
 * asking, where prism's own order puts it among those already there.
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

/** One entry of quick access. */
@Serializable
sealed interface QuickItem {
	/** One of prism's own places, [shown] in the sidebar or not. */
	@Serializable
	@SerialName("builtin")
	data class Own(
		val place: Builtin,
		val shown: Boolean = true,
	) : QuickItem

	/** A folder the user pinned. */
	@Serializable
	@SerialName("folder")
	data class Pinned(
		@Serializable(with = PathSerializer::class)
		val path: Path,
	) : QuickItem
}

/**
 * Quick access, in order: every [Builtin] exactly once, and each folder pinned once. Made only by [of], which keeps it
 * so, whatever it is given: a list read from a file that leaves one of prism's own out, or names one twice.
 */
@Serializable(with = QuickAccessSerializer::class)
class QuickAccess private constructor(
	val items: List<QuickItem>,
) {
	/** The folders pinned, in order. */
	val pins: List<Path> get() = items.filterIsInstance<QuickItem.Pinned>().map { it.path }

	/** Whether [place] is shown. */
	fun shows(place: Builtin): Boolean =
		items.any {
			it is QuickItem.Own && it.place == place &&
				it.shown
		}

	/** These, with [move] made. */
	fun moved(move: Moved): QuickAccess = QuickAccess(items.moved(move))

	/** These, with the entry at [index] moved [by] places (up, for a negative [by]), as far as it can go. */
	fun moved(index: Int, by: Int): QuickAccess =
		moved(Moved.of(index, (index + by).coerceIn(0, items.lastIndex)))

	/** These, with [place] shown or hidden. */
	fun showing(place: Builtin, shown: Boolean): QuickAccess =
		QuickAccess(
			items.map {
				if (it is QuickItem.Own && it.place == place)
					it.copy(shown = shown)
				else
					it
			},
		)

	/** These, with [path] pinned last, where it is not pinned already. */
	fun pinning(path: Path): QuickAccess = of(items + QuickItem.Pinned(path))

	/** These, without [path] pinned. Prism's own places are hidden, not removed: [showing]. */
	fun unpinning(path: Path): QuickAccess =
		QuickAccess(
			items.filterNot {
				it is QuickItem.Pinned &&
					it.path == path
			},
		)

	override fun equals(other: Any?): Boolean = other is QuickAccess && other.items == items

	override fun hashCode(): Int = items.hashCode()

	override fun toString(): String = "QuickAccess($items)"

	companion object {
		/** Prism's own places, every one shown, in their own order; nothing pinned. */
		val DEFAULT: QuickAccess = of(emptyList())

		/**
		 * [items], each of prism's own places once (the first of any named twice) and each folder once, with any of
		 * prism's own left out added, shown, where prism's own order puts it: before the first of prism's own there
		 * that comes after it in that order, or at the end where none does. The Recents page, first of them, goes
		 * before whichever of prism's own the user has first.
		 */
		fun of(items: List<QuickItem>): QuickAccess {
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
			)
		}
	}
}

/** Quick access as the file writes it: its entries, in order, read back through [QuickAccess.of]. */
object QuickAccessSerializer : KSerializer<QuickAccess> {
	private val list = ListSerializer(QuickItem.serializer())

	override val descriptor = list.descriptor

	override fun serialize(encoder: Encoder, value: QuickAccess) =
		list.serialize(encoder, value.items)

	override fun deserialize(decoder: Decoder): QuickAccess =
		QuickAccess.of(list.deserialize(decoder))
}
