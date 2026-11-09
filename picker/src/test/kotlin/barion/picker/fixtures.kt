package barion.picker

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.Path

/** What the picker's tests share: rows made as the source makes them, a home folder of them, and a disk in memory. */
object Fixtures {
	val home: Path = Path("/home/avery")

	/** A row as the source would make it, from a size in bytes (none for a folder) and a time, said in UTC. */
	fun row(name: String, bytes: Long? = null, at: String = "2026-11-08T09:41:00Z"): Row {
		val folder = bytes == null
		val category = if (folder) Category.Folder else categoryOf(name)
		val instant = Instant.parse(at)

		return Row(
			RowId(name),
			name,
			category,
			if (folder)
				"Folder"
			else
				typeOf(name, category),
			bytes?.let { Shown(it, sizeOf(it)) },
			Shown(instant, dateOf(instant, ZoneOffset.UTC)),
			name.startsWith("."),
		)
	}

	/** A home folder as a person's might be, every kind of row in it. */
	val homeRows: List<Row> =
		listOf(
			row("Desktop"),
			row("Documents"),
			row("Downloads"),
			row("Music"),
			row("Pictures"),
			row("Projects"),
			row("Videos"),
			row(".config"),
			row("Budget 2026.xlsx", 48_000, "2026-11-02T17:05:00Z"),
			row("Holiday in Lisbon.jpg", 4_400_000, "2026-10-21T12:30:00Z"),
			row("Quarterly report.pdf", 1_150_000, "2026-11-07T16:12:00Z"),
			row("backup.tar.gz", 327_000_000, "2026-10-30T03:00:00Z"),
			row("main.kt", 2_460, "2026-11-08T11:20:00Z"),
			row("notes.md", 6_250, "2026-11-08T10:02:00Z"),
			row("screenshot 2.png", 901_000, "2026-11-05T14:44:00Z"),
			row("screenshot 10.png", 934_000, "2026-11-06T09:13:00Z"),
			row("song.flac", 39_800_000, "2026-09-12T21:40:00Z"),
			row("trailer.mp4", 100_600_000, "2026-11-01T19:27:00Z"),
			row("todo.txt", 214, "2026-11-08T08:55:00Z"),
			row(".bashrc", 3_771, "2026-08-01T10:00:00Z"),
		)

	/** The folders of [homeRows]' home, what each holds, and the rest of a small file system around them. */
	val tree: Map<Path, List<Row>> =
		mapOf(
			Path("/") to listOf(row("home"), row("etc")),
			Path("/home") to listOf(row("avery")),
			Path("/etc") to listOf(row("hosts", 220)),
			home to homeRows,
			home.resolve("Documents") to
				listOf(row("Taxes"), row("letter.odt", 22_000), row("cv.pdf", 88_000)),
			home.resolve("Documents/Taxes") to listOf(row("2025.pdf", 310_000)),
			home.resolve("Pictures") to listOf(row("cat.png", 1_200_000)),
		) +
			homeRows
				.filter {
					it.isFolder &&
						home.resolve(it.name) !in setOf(home.resolve("Documents"), home.resolve("Pictures"))
				}.associate { home.resolve(it.name) to emptyList() }

	val sidebar: Sidebar =
		Sidebar(
			listOf(
				Sidebar.Group(
					"Quick access",
					listOf(
						Place("Home", Place.Kind.Home, home),
						Place("Desktop", Place.Kind.Desktop, home.resolve("Desktop")),
						Place("Documents", Place.Kind.Documents, home.resolve("Documents")),
						Place("Downloads", Place.Kind.Downloads, home.resolve("Downloads")),
						Place("Pictures", Place.Kind.Pictures, home.resolve("Pictures")),
						Place("Music", Place.Kind.Music, home.resolve("Music")),
						Place("Videos", Place.Kind.Videos, home.resolve("Videos")),
					),
				),
				Sidebar.Group(
					"This computer",
					listOf(
						Place("File system", Place.Kind.Drive, Path("/")),
						Place("Data", Place.Kind.Drive, Path("/mnt/data")),
					),
				),
			),
		)

	val filters =
		listOf(TypeFilter.ALL_FILES, TypeFilter("Images", listOf("*.png", "*.jpg")))

	/** A disk of [tree]: folders it holds are folders, rows that are not are files, and the rest is not there. */
	object Memory : Disk {
		override fun read(folder: Path): Listing.Read =
			tree[folder]?.let { Listing.Shown(it) }
				?: Listing.Unreadable("No such file or directory")

		override fun look(paths: List<Path>): Map<Path, Found> =
			paths.associateWith { path ->
				val name = path.fileName.toString()
				val row = path.parent?.let(tree::get)?.firstOrNull { it.name == name }

				when {
					path in tree || row?.isFolder == true -> Found.Folder
					row != null -> Found.File
					else -> Found.Nothing(inFolder = path.parent in tree)
				}
			}

		/** Every change done, as far as a disk held in code can say: the tree itself never changes. */
		override fun make(change: Change): ChangeResult = ChangeResult.Done
	}

	/**
	 * A picker opened on [folder] over [Memory], choosing [choosing], settled. A picker shows hidden files until the
	 * user hides them; most of the tests count rows without the fixture's two dotfiles (`.bashrc`, and the folder
	 * `.config`), so this shows neither, unless [hidden] says otherwise.
	 */
	fun picker(
		choosing: Choosing = Choosing.OneFile(),
		folder: Path = home,
		filters: List<TypeFilter> = this.filters,
		hidden: Hidden = NO_HIDDEN,
	): PickerState {
		val opened = opened(choosing, folder, home, sidebar, filters = filters).settled(Memory)
		val shown = opened.arrangement.hidden

		return listOfNotNull(
			PickerEvent.ToggledHiddenFiles.takeIf { hidden.files != shown.files },
			PickerEvent.ToggledHiddenFolders.takeIf { hidden.folders != shown.folders },
		).fold(opened) { state, toggle -> step(state, toggle).state }
	}

	/** No hidden entry shown. */
	val NO_HIDDEN = Hidden(files = false, folders = false)
}

/** [state] after each of [inputs] in turn, with every folder read and name looked up answered by [Fixtures.Memory]. */
fun after(state: PickerState, vararg inputs: Input): PickerState =
	inputs.fold(state) { at, input -> step(at, input).settled(Fixtures.Memory) }

/** The effects [input] asks for of [state], answered or not. */
fun effectsOf(state: PickerState, input: Input): List<Effect> = step(state, input).effects
