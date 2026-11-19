package prism.desktop

import java.nio.file.Path
import java.time.Instant
import prism.desktop.macos.ApplicationSource
import prism.platform.Os

/*
 * The desktop prism runs on, as interfaces. What a platform says (what type a file is, which applications open it,
 * how one is started, where the user's folders are) is asked of a [Desktop]; everything above it is the same on every
 * platform. Linux's desktop is freedesktop.org's (`freedesktop/`); macOS's would be Launch Services and uniform type
 * identifiers, Windows' the registry and the shell, each another implementation of these.
 */

/** The desktop: its file types, its applications, how it starts them, and its places. */
interface Desktop {
	val types: FileTypes
	val applications: Applications
	val launcher: Launcher
	val places: Places
	val facts: FileFacts

	/** Where the pictures applications are known by are found; nowhere, where the desktop has none. */
	val icons: Icons get() = Icons.NONE

	/** The user's trash: the platform's own, which its file manager shows too. */
	val trash: Trash get() = Trash.ofUser()

	/** The machine's drives and what is on them, as the System page shows them; none, where the desktop knows none. */
	val storage: StorageReader get() = StorageReader { Storage(emptyList()) }

	/**
	 * [paths] opened, each with the default application for its type: one start of an application for every file it
	 * is given where it takes one at a time, or one for all of them where it takes several. What no application opens
	 * is said, for the user to choose one ("Open with").
	 */
	fun open(paths: List<Path>, terminal: TerminalChoice = TerminalChoice.Found): Opening {
		val defaults =
			paths.map { it to types.of(it) }.associate { (path, type) ->
				path to
					applications.defaultFor(type)
			}
		val unopened = paths.filter { defaults[it] == null }
		val started =
			paths
				.filter { defaults[it] != null }
				.groupBy { defaults.getValue(it)!! }
				.map { (application, files) -> launcher.launch(application, files, terminal) }

		return Opening(started, unopened)
	}
}

/**
 * The desktop prism is running on: freedesktop.org's on Linux, which brings forward what it starts by [activation];
 * a Mac's, whose Launch Services brings forward what it opens itself. Windows' is still to write.
 */
fun currentDesktop(
	activation: Activation,
	applicationsFrom: ApplicationSource = ApplicationSource.Folders,
): Desktop =
	when (Os.current) {
		Os.Linux -> prism.desktop.freedesktop.Freedesktop(activation = activation)
		Os.MacOS -> prism.desktop.macos.MacDesktop(applicationsFrom)
		Os.Windows -> throw UnsupportedOperationException(
			"prism has no desktop for Windows yet",
		)
	}

/** What opening files came to: each application started (or why not), and the files nothing opens. */
data class Opening(
	val launched: List<Launched>,
	val unopened: List<Path>,
) {
	val failures: List<Launched.Failed> get() = launched.filterIsInstance<Launched.Failed>()
}

/** A MIME type, as `image/png` names it. */
@JvmInline
value class MimeType(
	val name: String,
) {
	/** `image`, of `image/png`. */
	val media: String get() = name.substringBefore('/')

	override fun toString(): String = name

	companion object {
		val FOLDER = MimeType("inode/directory")
		val TEXT = MimeType("text/plain")
		val BINARY = MimeType("application/octet-stream")
		val EMPTY = MimeType("application/x-zerosize")
	}
}

/** What type a file is, and what types are kinds of others. */
interface FileTypes {
	/** [path]'s type: by its name, then, for a name that says nothing, by what it holds. */
	fun of(path: Path): MimeType

	/** What [type] is called to a person ("PNG image"), where the desktop says. */
	fun describe(type: MimeType): String?

	/** [type], then each type it is a kind of, nearest first: `text/x-kotlin`, then `text/plain`. */
	fun lineage(type: MimeType): List<MimeType>

	/**
	 * The name patterns of files of [type] (`*.png`), and of every type that is a kind of it; `image/` and a star is
	 * every image. What a filter by type is, to something that filters by name. None where the desktop does not say.
	 */
	fun patternsOf(type: MimeType): List<String> = emptyList()
}

/** An application the desktop knows: one a file can be opened with. */
data class Application(
	/** The desktop's name for it: `org.gnome.TextEditor.desktop`. */
	val id: String,
	val name: String,
	/** The types it says it opens. */
	val types: Set<MimeType>,
	/** How it is started, as its words with the desktop's placeholders for what it is given (`%f`, `%U`). */
	val command: List<String>,
	/** Whether it runs in a terminal. */
	val inTerminal: Boolean,
	/** Its icon, as the desktop names it, where it has one. */
	val icon: String? = null,
	/** Where the desktop keeps what says all this: a desktop entry's path. */
	val source: Path? = null,
	/** What kind of program it is, where it says: "Web Browser", of Firefox. */
	val generic: String? = null,
	/** What it is for, in a line, where it says. */
	val comment: String? = null,
	/** Other words it is found by: "internet", "www", of a browser. */
	val keywords: List<String> = emptyList(),
	/** The other things it can be started to do: a new private window, of a browser. */
	val actions: List<ApplicationAction> = emptyList(),
	/** What its windows name as their application, where that is not [id] without `.desktop`: `StartupWMClass`. */
	val wmClass: String? = null,
) {
	/** This application started to do [action] in place of what it does when simply started. */
	fun doing(action: ApplicationAction): Application =
		copy(
			name = "$name: ${action.name}",
			command = action.command,
			icon =
				action.icon ?: icon,
		)
}

/** Something an application can be started to do other than open: a desktop entry's action. */
data class ApplicationAction(
	/** The desktop's name for it, among its application's: `new-private-window`. */
	val id: String,
	val name: String,
	/** How the application is started to do it, as [Application.command] is written. */
	val command: List<String>,
	val icon: String? = null,
)

/** The pictures the desktop's applications (and anything else it names by an icon) are known by. */
fun interface Icons {
	/**
	 * The file of the icon [name], as an application names its own: a name in the user's icon theme, or a path. The
	 * one drawn nearest [size] pixels across at [scale] (2, on a display of twice the density) is given, a picture that
	 * scales (SVG) or a PNG; null where there is none.
	 */
	fun find(name: String, size: Int, scale: Int): Path?

	/** What was read of the icons forgotten, where the desktop keeps it: a theme installed or chosen since is seen. */
	fun reread() = Unit

	companion object {
		/** No icons: every application without a picture. */
		val NONE = Icons { _, _, _ -> null }
	}
}

/** The applications the desktop knows, and which opens each type by default. */
interface Applications {
	/** Every application a person would choose from, by name. */
	fun all(): List<Application>

	/** What is installed read again, where the desktop keeps what it read: one installed or removed since is seen. */
	fun reread() = Unit

	/** The applications that say they open [type] (or a type it is a kind of): the default first. */
	fun forType(type: MimeType): List<Application>

	/** The application that opens [type] when nothing else is said; null where none does. */
	fun defaultFor(type: MimeType): Application?

	/** [application] made the default for [type], for this user. */
	fun setDefault(type: MimeType, application: Application)
}

/**
 * What brings forward the window of a program prism starts: a token the desktop makes from the user's last press of a
 * key or a button in prism, which the program hands to the window it shows, or, where it is running already, to the
 * window it has. A desktop does not bring forward a window that asks without one, since the user did not ask for it.
 * A token is used once, so one is asked for each start.
 */
fun interface Activation {
	/** A token for a start of the application [appId] (its desktop entry's name, where it is one); null where there is none. */
	fun token(appId: String?): ActivationToken?

	companion object {
		/** No token: where nothing that prism starts is brought forward, as where it has no windows. */
		val None = Activation { null }
	}
}

/** A token for bringing forward the window of a program started: the desktop's own, passed on as it is. */
@JvmInline
value class ActivationToken(
	val text: String,
)

/** What starting a process came to. */
sealed interface Launched {
	data class Started(
		val what: String,
	) : Launched

	data class Failed(
		val what: String,
		val why: String,
	) : Launched
}

/**
 * How the desktop starts what prism opens: as processes of their own, that outlive prism and are not in its session,
 * nor (where the desktop has them) in its group of processes, so that closing a window, or stopping the daemon, takes
 * nothing it opened with it.
 */
interface Launcher {
	/** [application] started with [paths]: once, or once for each where it takes one at a time; in [terminal] if it runs in one. */
	fun launch(
		application: Application,
		paths: List<Path>,
		terminal: TerminalChoice = TerminalChoice.Found,
	): Launched

	/** [command] started in [folder]. */
	fun run(command: List<String>, folder: Path): Launched

	/**
	 * The command [choice]'s terminal is opened with in [folder], running [command] there where one is given; or why
	 * it cannot be. A launcher that knows no terminal says so.
	 */
	fun terminalCommand(
		folder: Path,
		choice: TerminalChoice,
		command: List<String>? = null,
	): TerminalCommand =
		TerminalCommand.Failed("a terminal", "No terminal is available on this desktop.")

	/** The terminals there are to choose from, and the one the desktop says is the user's; none, for a launcher that knows none. */
	fun terminals(): TerminalOptions = TerminalOptions.NONE

	/** [choice]'s terminal started in [folder]. */
	fun terminal(folder: Path, choice: TerminalChoice): Launched =
		started(terminalCommand(folder, choice), folder)

	/** [command] started in [choice]'s terminal, in [folder]. */
	fun inTerminal(
		command: List<String>,
		folder: Path,
		choice: TerminalChoice = TerminalChoice.Found,
	): Launched = started(terminalCommand(folder, choice, command), folder)

	private fun started(command: TerminalCommand, folder: Path): Launched =
		when (command) {
			is TerminalCommand.Ready -> run(command.words, folder)
			is TerminalCommand.Failed -> Launched.Failed(command.what, command.why)
		}
}

/** A place the sidebar offers. */
data class Place(
	val label: String,
	val kind: Kind,
	val path: Path,
) {
	enum class Kind {
		Home,
		Desktop,
		Documents,
		Downloads,
		Pictures,
		Music,
		Videos,
		Folder,
		Drive,
	}
}

/** The user's folders and the drives the desktop shows. */
interface Places {
	/** The home folder, then the well-known folders in it that are there: Desktop, Documents, Downloads, ... */
	fun known(home: Path): List<Place>

	/** The file system, then each drive mounted where the desktop mounts them. */
	fun drives(home: Path): List<Place>
}

/** What can be known of a file for its properties. */
data class Facts(
	val path: Path,
	val type: MimeType,
	val kind: FileKind,
	/** The size in bytes: of a file; of a folder, what it holds is added up elsewhere, as it takes a while. */
	val size: Long,
	val modified: Instant?,
	val accessed: Instant?,
	/** When it was made, where the file system keeps it. */
	val created: Instant?,
	val owner: String?,
	val group: String?,
	/** Who may read, write and run it, where the file system says so: null on one that does not. */
	val permissions: Permissions?,
	/** Where a link leads. */
	val target: Path?,
)

enum class FileKind {
	File,
	Folder,
	Link,
	Other,
}

/** Read, write and run, for the owner, the group, and everyone else. */
data class Permissions(
	val owner: Access,
	val group: Access,
	val others: Access,
) {
	data class Access(
		val read: Boolean,
		val write: Boolean,
		val run: Boolean,
	)
}

/** A file's facts, and the one fact the properties change. */
interface FileFacts {
	/** [path]'s facts; null where it is not there. */
	fun of(path: Path): Facts?

	/** What [folder] holds, added up: its files, its folders, and their sizes. Long, for a large folder. */
	fun contents(folder: Path): Contents

	/** [path]'s permissions made [permissions]; why not, where they could not be. */
	fun permit(path: Path, permissions: Permissions): String?
}

/** What a folder holds, all of it: how many files and folders, and how many bytes the files take. */
data class Contents(
	val files: Long,
	val folders: Long,
	val bytes: Long,
)
