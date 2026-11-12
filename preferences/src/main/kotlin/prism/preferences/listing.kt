package prism.preferences

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * How a listing looks, and what it leaves out. What a window lists is of a kind ([ListingKind]): a folder, a search's
 * results, the trash, or a place of some type (an archive). A window is an explorer's or a picker's ([Role]), and draws
 * its listing as rows in columns or as tiles ([Layout]).
 *
 * Each kind of listing, in each role, has a look of its own ([Look]): its order, and its columns ([Columns]: which,
 * in what order, aligned how). Each kind in each role and each layout has its own filters ([Filters]): which hidden
 * entries, and which kinds of file, it shows. The preferences say how each is as a window opens ([Listings]); a window
 * keeps what the user gives it as its own from then on. What the preferences do not say for a picker is the explorer's;
 * what they do not say for the explorer is prism's own.
 *
 * The columns are a catalogue ([ColumnId]) that a kind of listing offers some of: one added to the catalogue is a column
 * every listing that offers it can show, and a listing a user has arranged gains it, hidden, at its end.
 */

/** What a window is for: exploring, or choosing for another application. */
@Serializable
enum class Role {
	Explorer,
	Picker,
}

/** How a listing is drawn: rows in columns (details), or tiles with a picture of each (a gallery). */
@Serializable
enum class Layout {
	List,
	Gallery,
}

/** How a column's values sit in it. */
@Serializable
enum class Align {
	Start,
	Center,
	End,
}

/**
 * How wide a column is: a [Share] of the room the fixed columns leave, never less than its [least][Share.least]; or as
 * wide as a [Date] and its time are written, so that neither is ever cut and every row's line up. A listing whose
 * columns' least is more than its window has scrolls across, rather than cutting any off.
 */
sealed interface Width {
	/** [of] the room left, and at least [least] characters of the listing's text. */
	data class Share(
		val of: Float,
		val least: Int,
	) : Width

	data object Date : Width
}

/**
 * A column a listing can have: what its heading says ([label]), how wide it is, and how its values sit unless the user
 * says otherwise ([align]).
 */
@Serializable
enum class ColumnId(
	val label: String,
	val width: Width,
	val align: Align = Align.Start,
) {
	Name("Name", Width.Share(0.44f, least = 16)),

	/** Where an entry is: a search's result's folder; in the trash, where it was. */
	Location("Folder", Width.Share(0.3f, least = 14)),
	Modified("Date modified", Width.Date),

	/** When it was made: its birth time, where the file system keeps one. */
	Created("Date created", Width.Date),

	/** When it was trashed: the trash's own. */
	Deleted("Date deleted", Width.Date),
	Type("Type", Width.Share(0.18f, least = 10)),
	Size("Size", Width.Share(0.12f, least = 8), Align.End),

	/** What its name ends in, after its last dot. */
	Extension("Extension", Width.Share(0.13f, least = 6)),
}

/** A column as a listing shows it: [shown] or left out, its values sitting as [align] says (the column's own, where null). */
@Serializable
data class ColumnSetting(
	val id: ColumnId,
	val shown: Boolean = true,
	val align: Align? = null,
) {
	/** How its values sit. */
	val aligned: Align get() = align ?: id.align
}

/**
 * A listing's columns, in order: each that [kind] offers exactly once, the name's first and always shown. Made only by
 * [ListingKind.columnsOf], which keeps them so, whatever a file says.
 */
class Columns internal constructor(
	val kind: ListingKind,
	val settings: List<ColumnSetting>,
) {
	/** Those shown, in order. */
	val shown: List<ColumnSetting> get() = settings.filter { it.shown }

	fun isShown(id: ColumnId): Boolean = settings.any { it.id == id && it.shown }

	/** These, with the column at [from] moved to [to]; the name's stays first. */
	fun moved(from: Int, to: Int): Columns {
		if (from !in settings.indices || settings[from].id == ColumnId.Name)
			return this

		val rest = settings.filterIndexed { at, _ -> at != from }
		val place = to.coerceIn(1, settings.lastIndex)

		return kind.columnsOf(rest.take(place) + settings[from] + rest.drop(place))
	}

	/** These, with [id] shown or not; the name's is always shown. */
	fun showing(id: ColumnId, shown: Boolean): Columns =
		kind.columnsOf(settings.map { if (it.id == id) it.copy(shown = shown) else it })

	/** These, with [id]'s values sitting as [align] says. */
	fun aligning(id: ColumnId, align: Align): Columns =
		kind.columnsOf(
			settings.map {
				if (it.id ==
					id
				)
					it.copy(align = align.takeIf { a -> a != id.align })
				else
					it
			},
		)

	override fun equals(other: Any?): Boolean =
		other is Columns && other.kind == kind && other.settings == settings

	override fun hashCode(): Int = settings.hashCode()

	override fun toString(): String = "Columns($kind, $settings)"
}

/**
 * What a window lists, as far as how it looks goes: a folder, a search's results, the trash, or a place of a [type]
 * (`archive`, as the place's command names its type). Each offers its own columns, and has its own default look.
 */
@Serializable(with = ListingKindSerializer::class)
sealed interface ListingKind {
	/** How it is named in the preferences file. */
	val key: String

	/** The columns it can show, in their default order. */
	val offered: List<ColumnId>

	/** Those it shows unless the user says otherwise. */
	val shownByDefault: Set<ColumnId>

	/** How it looks unless the user says otherwise. */
	val defaultLook: Look get() = Look()

	data object Folder : ListingKind {
		override val key = "folder"
		override val offered =
			listOf(
				ColumnId.Name,
				ColumnId.Modified,
				ColumnId.Created,
				ColumnId.Type,
				ColumnId.Size,
				ColumnId.Extension,
			)
		override val shownByDefault =
			setOf(ColumnId.Name, ColumnId.Modified, ColumnId.Type, ColumnId.Size)
	}

	/** A search's results, best first until the user says otherwise. When a result was made is not read. */
	data object Results : ListingKind {
		override val key = "results"
		override val offered =
			listOf(
				ColumnId.Name,
				ColumnId.Location,
				ColumnId.Modified,
				ColumnId.Type,
				ColumnId.Size,
				ColumnId.Extension,
			)
		override val shownByDefault =
			setOf(
				ColumnId.Name,
				ColumnId.Location,
				ColumnId.Modified,
				ColumnId.Type,
				ColumnId.Size,
			)
		override val defaultLook: Look get() = Look(Order.Relevance)
	}

	data object Trash : ListingKind {
		override val key = "trash"
		override val offered =
			listOf(
				ColumnId.Name,
				ColumnId.Location,
				ColumnId.Deleted,
				ColumnId.Modified,
				ColumnId.Type,
				ColumnId.Size,
				ColumnId.Extension,
			)
		override val shownByDefault =
			setOf(ColumnId.Name, ColumnId.Location, ColumnId.Deleted, ColumnId.Type, ColumnId.Size)
	}

	/** A place of [type]: what its command lists of an entry is its name, its size and when it was modified. */
	data class Place(
		val type: String,
	) : ListingKind {
		override val key: String get() = "place:$type"
		override val offered: List<ColumnId>
			get() =
				listOf(
					ColumnId.Name,
					ColumnId.Modified,
					ColumnId.Type,
					ColumnId.Size,
					ColumnId.Extension,
				)
		override val shownByDefault: Set<ColumnId>
			get() = setOf(ColumnId.Name, ColumnId.Modified, ColumnId.Type, ColumnId.Size)
	}

	/** What [id]'s heading says here: the trash's location is where an entry was. */
	fun labelOf(id: ColumnId): String =
		if (this == Trash && id == ColumnId.Location)
			"Original location"
		else
			id.label

	/**
	 * [settings] as this kind's columns: those it offers, each once (the first of any named twice), the name's first
	 * and shown; any it offers that are left out added at the end, hidden where the user has arranged the rest, and as
	 * by default where nothing is said.
	 */
	fun columnsOf(settings: List<ColumnSetting>): Columns {
		val kept = settings.filter { it.id in offered }.distinctBy { it.id }
		val named = kept.map { it.id }.toSet()

		val added =
			offered.filterNot { it in named }.map {
				ColumnSetting(
					it,
					shown =
						kept.isEmpty() && it in shownByDefault,
				)
			}

		val all = kept + added
		val name = all.first { it.id == ColumnId.Name }.copy(shown = true)

		return Columns(this, listOf(name) + all.filterNot { it.id == ColumnId.Name })
	}

	companion object {
		/** [key] as the kind it names; null for a key that names none. */
		fun of(key: String): ListingKind? =
			when {
				key == Folder.key -> Folder
				key == Results.key -> Results
				key == Trash.key -> Trash
				key.startsWith(
					"place:",
				) && key.length > "place:".length -> Place(key.removePrefix("place:"))
				else -> null
			}
	}
}

object ListingKindSerializer : KSerializer<ListingKind> {
	override val descriptor =
		PrimitiveSerialDescriptor("prism.ListingKind", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: ListingKind) =
		encoder.encodeString(value.key)

	override fun deserialize(decoder: Decoder): ListingKind =
		decoder.decodeString().let {
			ListingKind.of(it)
				?: throw kotlinx.serialization.SerializationException("no listing “$it”")
		}
}

/**
 * How a listing is ordered. Folders come first whatever it is. [Relevance] is a search's ranking, best first; any other
 * listing, which has none, is in the order it was listed in (a place's command's, or a folder's own).
 */
@Serializable
sealed interface Order {
	@Serializable
	@SerialName("relevance")
	data object Relevance : Order

	/** By a column. */
	@Serializable
	@SerialName("by")
	data class By(
		override val sort: Sort,
	) : Order

	/** The order after [column]'s header is clicked: the other way round if it is already the order, otherwise by it. */
	fun toggled(column: ColumnId): Order =
		when (this) {
			Relevance -> By(Sort.of(column))
			is By -> By(sort.toggled(column))
		}

	/** The sort this is, where it is by a column. */
	val sort: Sort? get() = null
}

/**
 * How a listing looks: its [order], and its [columns] as the preferences file writes them (none, for its kind's own),
 * which [columns] of a kind makes the listing's columns of.
 */
@Serializable
data class Look(
	val order: Order = Order.By(Sort.BY_NAME),
	private val columns: List<ColumnSetting> = emptyList(),
) {
	/** The columns of a listing of [kind] that looks so. */
	fun columns(kind: ListingKind): Columns = kind.columnsOf(columns)

	/** This, with [columns]. */
	fun with(columns: Columns): Look = copy(columns = columns.settings)
}

/** Which hidden entries a listing shows, and which kinds of file ([kinds]: every kind, where none). */
@Serializable
data class Filters(
	val hidden: Hidden = Hidden(),
	val kinds: Set<Kind> = emptySet(),
)

/**
 * Which hidden entries (a name starting with a dot) are shown: hidden files are, and hidden folders are not, until the
 * user says otherwise. The folders are kept back by default because they are where programs keep their own things
 * (`.config`, `.cache`, `.local`), which are rarely what a person picks from; a hidden file is more often a person's.
 */
@Serializable
data class Hidden(
	val files: Boolean = true,
	val folders: Boolean = false,
) {
	/** Whether an entry is shown, as far as being hidden goes. */
	fun shows(hidden: Boolean, folder: Boolean): Boolean =
		when {
			!hidden -> true
			folder -> folders
			else -> files
		}

	/** Every hidden entry shown, or, where every one is already, none: what Ctrl+H does. */
	fun toggled(): Hidden =
		if (files && folders)
			Hidden(files = false, folders = false)
		else
			Hidden(files = true, folders = true)
}

/** What the filters choose among: kinds of entry, each a few categories of file together. */
@Serializable
enum class Kind(
	val label: String,
) {
	Folders("Folders"),
	Images("Images"),
	Videos("Videos"),
	Audio("Audio"),
	Documents("Documents"),
	Archives("Archives"),
	Code("Code"),
}

/** How tall the list's rows are, and how large their icons: larger, as Thunar draws them, or compact. */
@Serializable
enum class Rows {
	Larger,
	Compact,
}

/** A listing of [kind] in a window of [role]: what a look is kept for. */
@Serializable(with = LookKeySerializer::class)
data class LookKey(
	val role: Role,
	val kind: ListingKind,
)

/** A listing of [kind] in a window of [role] drawn in [layout]: what filters are kept for. */
@Serializable(with = FilterKeySerializer::class)
data class FilterKey(
	val role: Role,
	val kind: ListingKind,
	val layout: Layout,
)

object LookKeySerializer : KSerializer<LookKey> {
	override val descriptor =
		PrimitiveSerialDescriptor("prism.LookKey", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: LookKey) =
		encoder.encodeString("${value.role.name.lowercase()}/${value.kind.key}")

	override fun deserialize(decoder: Decoder): LookKey {
		val text = decoder.decodeString()
		val (role, kind) = text.split('/', limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }

		return LookKey(roleOf(role, text), kindOf(kind, text))
	}
}

object FilterKeySerializer : KSerializer<FilterKey> {
	override val descriptor =
		PrimitiveSerialDescriptor("prism.FilterKey", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: FilterKey) =
		encoder.encodeString(
			"${value.role.name.lowercase()}/${value.kind.key}/${value.layout.name.lowercase()}",
		)

	override fun deserialize(decoder: Decoder): FilterKey {
		val text = decoder.decodeString()
		val parts = text.split('/')
		val layout =
			Layout.entries.firstOrNull { it.name.equals(parts.lastOrNull(), ignoreCase = true) }
				?: throw kotlinx.serialization.SerializationException("no layout in “$text”")

		return FilterKey(
			roleOf(parts.firstOrNull(), text),
			kindOf(parts.drop(1).dropLast(1).joinToString("/"), text),
			layout,
		)
	}
}

private fun roleOf(name: String?, text: String): Role =
	Role.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
		?: throw kotlinx.serialization.SerializationException("no window “$name” in “$text”")

private fun kindOf(key: String?, text: String): ListingKind =
	key?.let(ListingKind::of)
		?: throw kotlinx.serialization.SerializationException("no listing in “$text”")

/**
 * How listings are as a window opens: each kind's look in each role ([looks]), and its filters in each role and layout
 * ([filters]). What is not said for a picker is the explorer's; what is not said for the explorer is prism's own.
 */
@Serializable
data class Listings(
	val looks: Map<LookKey, Look> = emptyMap(),
	val filters: Map<FilterKey, Filters> = emptyMap(),
) {
	/** How a listing of [kind] in a window of [role] looks. */
	fun look(role: Role, kind: ListingKind): Look =
		looks[LookKey(role, kind)] ?: looks[LookKey(Role.Explorer, kind)] ?: kind.defaultLook

	/** What a listing of [kind] in a window of [role] drawn in [layout] shows. */
	fun filters(role: Role, kind: ListingKind, layout: Layout): Filters =
		filters[FilterKey(role, kind, layout)]
			?: filters[FilterKey(Role.Explorer, kind, layout)]
			?: Filters()

	/** Whether a picker's listing of [kind] has a look of its own, not the explorer's. */
	fun ownLook(role: Role, kind: ListingKind): Boolean = LookKey(role, kind) in looks

	/** Whether a picker's listing of [kind] in [layout] has filters of its own, not the explorer's. */
	fun ownFilters(role: Role, kind: ListingKind, layout: Layout): Boolean =
		FilterKey(role, kind, layout) in filters

	/** These, with a listing of [kind] in a window of [role] looking as [look]; as the explorer's (or prism's), where null. */
	fun looking(role: Role, kind: ListingKind, look: Look?): Listings =
		copy(
			looks =
				if (look ==
					null
				)
					looks - LookKey(role, kind)
				else
					looks + (LookKey(role, kind) to look),
		)

	/** These, with [kind]'s filters in [role] and [layout] as [filters]; as the explorer's (or prism's), where null. */
	fun filtering(
		role: Role,
		kind: ListingKind,
		layout: Layout,
		filters: Filters?,
	): Listings =
		copy(
			filters =
				if (filters == null)
					this.filters - FilterKey(role, kind, layout)
				else
					this.filters + (FilterKey(role, kind, layout) to filters),
		)
}

/**
 * How large things are drawn, as many times their usual size: the listing of an explorer's window and of a picker's as
 * each opens ([explorer], [picker]; Ctrl and the wheel zoom a window's own from there), the sidebar's quick access and
 * the window that arranges it ([sidebar]), and each of the other windows, as Ctrl and the wheel last left it
 * ([windows]).
 */
@Serializable
data class Scales(
	val explorer: Float = 1f,
	val picker: Float = 1f,
	val sidebar: Float = 1f,
	val windows: Map<WindowKind, Float> = emptyMap(),
) {
	/** How large [kind] is drawn. */
	fun of(kind: WindowKind): Float = (windows[kind] ?: 1f).coerceIn(SMALLEST, LARGEST)

	/** These, with [kind] drawn [scale] times its usual size, within what can be read and laid out. */
	fun with(kind: WindowKind, scale: Float): Scales =
		copy(
			windows =
				windows + (kind to scale.coerceIn(SMALLEST, LARGEST)),
		)

	companion object {
		const val SMALLEST = 0.5f
		const val LARGEST = 5f
	}
}

/** A window that is not a browser's, each drawn as large as the user last zoomed one of its kind. */
@Serializable
enum class WindowKind {
	Preferences,
	Properties,
	OpenWith,
}
