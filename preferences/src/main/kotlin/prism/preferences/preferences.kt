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
import prism.shared.ScrollDirection
import prism.thumbnails.ThumbnailSettings

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
	 * The bindings of a picker's window that differ from the defaults, written as [bindings] are; null where pickers
	 * have no keys of their own, and an explorer's are theirs too.
	 */
	val pickerBindings: Map<String, List<String>>? = null,
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
	/** The user's own text searches: commands that find lines in the files a window shows (docs/text.md). */
	val textSearches: List<JsonElement> = emptyList(),
	/**
	 * What opens a file at a line, from a text search: the user's editor. Where none is said, the editor the
	 * environment names (`VISUAL`, then `EDITOR`), in the terminal; and where it names none, the file's application.
	 */
	val editor: OpenSpec? = null,
	/**
	 * The ids of the actions and text searches turned off, prism's own among them: offered in no menu and done by no
	 * key, as though they were not written, and kept as they are written, to be turned on again.
	 */
	val disabled: Set<String> = emptySet(),
	/** What of the menus is left out. */
	val menu: MenuSpec = MenuSpec(),
	/**
	 * How listings look and what they leave out: each kind's columns and order, and its filters, in an explorer and in a
	 * picker. A window keeps what the user gives it from its own menus, until these change what they say of the same
	 * kind: then it looks as these now say, open or new.
	 */
	val listings: Listings = Listings(),
	/**
	 * The font and size of each part of a window's text, as the user chose them, the default's where they did not. An
	 * older version's `text` and `fonts`, which wrote its defaults as though they were chosen, are no longer read.
	 */
	val typography: Typography = Typography(),
	/** How tall the list's rows are, and how large their icons. */
	val rows: Rows = Rows.Larger,
	/** How large the listing, the sidebar and the other windows are drawn. */
	val scales: Scales = Scales(),
	/** How dates are written. */
	val dates: DateFormat = DateFormat.Written,
	/**
	 * Whether a window has a title bar, to move it by and close it from. On a Mac it is the system's. On Linux it is
	 * asked of the compositor, which says through xdg-decoration who draws it: itself (KDE, sway), the window
	 * (GNOME, where it is drawn on prism's side), or nobody, where the compositor draws none (niri); on X11, the
	 * window manager's. Off, a window has none, and is closed with its keys.
	 */
	val titlebar: Boolean = true,
	/** Which way the wheel, and a touchpad, scrolls every window: as the system is set, or the other way. */
	val scrolling: ScrollDirection = ScrollDirection.AsTheSystem,
	/** How far the wheel, and a touchpad, scrolls every window's lists: so many times as far as usual. */
	val scrollSpeed: ScrollSpeed = ScrollSpeed.USUAL,
	/** How the launcher looks and what it finds. */
	val launcher: LauncherPreferences = LauncherPreferences(),
	/**
	 * The search aliases: each one's name, and the query it stands for where a search says `name@`, as it was
	 * typed. Whatever searches reads them (the explorer, pickers, the launcher, `prism search`), each on its own, so
	 * that one written wrong does not lose the rest.
	 */
	val aliases: Map<String, String> = emptyMap(),
	/** The sections of the Recents page shown as their headings alone, in every window. */
	val recentsCollapsed: Set<RecentSection> = emptySet(),
	/** Whether files are shown by small pictures of themselves where there are any: icons alone, where not. */
	val thumbnails: Boolean = true,
	/**
	 * The files and folders, by their paths, the Recents page leaves out, in every window: still kept and counted
	 * as any other is, and only not shown.
	 */
	val recentsHidden: Set<String> = emptySet(),
	/** Which windows show their sidebar: the explorer's, the pickers', each as the user last left theirs. */
	val sidebarShown: ShownIn = ShownIn.EVERYWHERE,
	/** What the index leaves out, and what it holds that searches leave out. */
	val excluding: Excluding = Excluding(),
	/**
	 * How the small pictures of files are made and kept, where they are shown ([thumbnails]): the folder they are
	 * kept in, how much is kept, how large they are made, and the programs that make them.
	 */
	val thumbnailing: ThumbnailSettings = ThumbnailSettings(),
) {
	/** The actions, places and text searches written here, and the editor. */
	val written: ActionsFile get() = ActionsFile(actions, places, textSearches, editor)

	/** These, with quick access as [quick]: what an older version kept in its place forgotten. */
	fun arranging(quick: QuickAccess): Preferences = copy(quickAccess = quick, pinned = null)

	/** These, with the windows for [role] showing their sidebar, or not. */
	fun showingSidebar(role: Role, shown: Boolean): Preferences =
		copy(sidebarShown = sidebarShown.with(role, shown))

	/** These, with [section] of the Recents page collapsed, or open. */
	fun folding(section: RecentSection, collapsed: Boolean): Preferences =
		copy(
			recentsCollapsed =
				if (collapsed)
					recentsCollapsed + section
				else
					recentsCollapsed - section,
		)

	/** These, with [paths] left out of the Recents page ([hidden]), or shown on it again. */
	fun hidingRecent(paths: Collection<String>, hidden: Boolean): Preferences =
		copy(
			recentsHidden =
				if (hidden)
					recentsHidden + paths
				else
					recentsHidden - paths.toSet(),
		)

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
