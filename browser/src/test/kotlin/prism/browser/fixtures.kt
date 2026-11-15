package prism.browser

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import prism.desktop.Place
import prism.preferences.Builtin
import prism.preferences.Hidden
import prism.preferences.QuickAccess

/** What the picker's tests share: rows made as the source makes them, a home folder of them, and a disk in memory. */
object Fixtures {
	val home: Path = Path("/home/avery")

	/**
	 * A row as the source would make it, from a size in bytes (none for a folder) and a time, said in UTC, in [folder]
	 * (where [Memory] reads it from, whatever this says).
	 */
	fun row(
		name: String,
		bytes: Long? = null,
		at: String = "2026-11-08T09:41:00Z",
		folder: Path = home,
	): Row {
		val isFolder = bytes == null
		val category =
			if (isFolder)
				Category.Folder
			else
				categoryOf(name)
		val instant = Instant.parse(at)

		return Row(
			RowId(name),
			Item.OnDisk(folder.resolve(name)),
			name,
			category,
			if (isFolder)
				"Folder"
			else
				typeOf(name, category),
			bytes?.let { Shown(it, sizeOf(it)) },
			instant,
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

	/** Prism's own folders, as a desktop finds them in [home]. */
	val known: Map<Builtin, Place> =
		mapOf(
			Builtin.Home to Place("Home", Place.Kind.Home, home),
			Builtin.Desktop to Place("Desktop", Place.Kind.Desktop, home.resolve("Desktop")),
			Builtin.Documents to
				Place("Documents", Place.Kind.Documents, home.resolve("Documents")),
			Builtin.Downloads to
				Place("Downloads", Place.Kind.Downloads, home.resolve("Downloads")),
			Builtin.Pictures to Place("Pictures", Place.Kind.Pictures, home.resolve("Pictures")),
			Builtin.Music to Place("Music", Place.Kind.Music, home.resolve("Music")),
			Builtin.Videos to Place("Videos", Place.Kind.Videos, home.resolve("Videos")),
		)

	val sidebar: Sidebar =
		Sidebar(
			quick = QuickAccess.DEFAULT,
			known = known,
			drives =
				listOf(
					Place("Root", Place.Kind.Drive, Path("/")),
					Place("Data", Place.Kind.Drive, Path("/mnt/data")),
				),
		)

	val filters =
		listOf(TypeFilter.ALL_FILES, TypeFilter("Images", listOf("*.png", "*.jpg")))

	/** A disk of [tree]: folders it holds are folders, rows that are not are files, and the rest is not there. */
	object Memory : Disk {
		override fun read(folder: Path): Listing.Read =
			tree[folder]?.let { rows ->
				Listing.Shown(rows.map { it.copy(item = Item.OnDisk(folder.resolve(it.name))) })
			}
				?: Listing.Unreadable("No such file or directory")

		/** What [tree] says of each of [names] in [folder]: its row, or none where it holds no such row. */
		override fun entries(folder: Path, names: Set<String>): Map<String, Row?> =
			names.associateWith { name ->
				tree[folder]
					?.firstOrNull { it.name == name }
					?.let { it.copy(item = Item.OnDisk(folder.resolve(name))) }
			}

		/** What [tree] says of each path: its row's size and date, or gone where it holds no such row. */
		override fun stat(paths: List<Path>): List<Stat> =
			paths.map { path ->
				val row =
					path.parent?.let(tree::get)?.firstOrNull {
						it.name ==
							path.fileName.toString()
					}

				row?.let { Stat.Read(it.size, it.modified) } ?: Stat.Gone
			}

		/** One volume, the whole tree. */
		override fun sameVolume(a: Path, b: Path): Boolean = true

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

		/**
		 * Every change done, as far as a disk held in code can say (the tree itself never changes), and answered with
		 * what would undo it, the trash keeping each entry under its own name.
		 */
		override fun make(change: Change): ChangeResult =
			if (change is Change.Trash)
				ChangeResult.Trashed(
					Change.PutBack(
						change.paths
							.filterNot { it.startsWith(NO_TRASH) }
							.map { Trashed(it, it.fileName.toString(), TRASH) },
					),
					change.paths
						.filter { it.startsWith(NO_TRASH) }
						.map { Untrashable(it, "$NO_TRASH is kept in memory (tmpfs), which has no trash") },
				)
			else
				ChangeResult.Done(
					when (change) {
						is Change.Rename -> Change.Rename(change.to, change.from)
						is Change.MakeFolder -> Change.RemoveFolder(change.path)
						is Change.RemoveFolder -> Change.MakeFolder(change.path)
						// Answered apart, below.
						is Change.Trash -> error("trashed apart")
						is Change.PutBack -> Change.Trash(change.entries.map { it.original })
						is Change.MakeFile -> Change.RemoveFile(change.path)
						is Change.RemoveFile -> Change.MakeFile(change.path)
						// Copies and moves keep their names: the tree holds nothing to clash with.
						is Change.Copy ->
							Change.Trash(
								change.sources.map { change.into.resolve(it.fileName) },
							)
						is Change.Link ->
							Change.Trash(
								change.sources.map { change.into.resolve(it.fileName) },
							)
						is Change.Move ->
							Change.Relocate(change.sources.map { Moved(change.into.resolve(it.fileName), it) })
						is Change.Relocate -> Change.Relocate(change.moves.map { Moved(it.to, it.from) })
					},
				)

		/** Everything deleted, as far as a disk held in code can say. */
		override fun erase(erasing: Erasing): String? = null

		/** A trash holding [trashRows]. */
		override fun trash(): Listing.Read = Listing.Shown(trashRows)

		/** Where the trash keeps what is trashed, and the folder (kept in memory) that has none. */
		val TRASH: Path = home.resolve(".local/share/Trash")
		val NO_TRASH: Path = Path("/tmp")
	}

	/**
	 * What the trash of [Memory] holds: a file and a folder, from two folders, trashed on two days. Made when first
	 * asked for: [Memory] is made from this object's [home] and these from [Memory]'s trash, so whichever a test
	 * touches first, neither is read half made.
	 */
	val trashRows: List<Row> by lazy {
		listOf(
			Trashed(home.resolve("old notes.md"), "old notes.md", Memory.TRASH) to
				"2026-11-01T10:00:00Z",
			Trashed(home.resolve("Projects/draft"), "draft", Memory.TRASH) to
				"2026-11-03T09:30:00Z",
		).map { (trashed, at) ->
			val folder = trashed.name == "draft"

			Row(
				RowId.Trashed(trashed.bin, trashed.name),
				Item.InTrash(trashed, Instant.parse(at)),
				trashed.original.fileName.toString(),
				if (folder)
					Category.Folder
				else
					categoryOf(trashed.name),
				if (folder)
					"Folder"
				else
					typeOf(trashed.name, categoryOf(trashed.name)),
				if (folder)
					null
				else
					Shown(512L, sizeOf(512L)),
				Instant.parse(at),
				hidden = false,
			)
		}
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
	): BrowserState {
		val opened = opened(choosing, folder, home, sidebar, filters = filters).settled(Memory)
		val shown = opened.entryFilters.hidden

		return listOfNotNull(
			BrowserEvent.ToggledHiddenFiles.takeIf { hidden.files != shown.files },
			BrowserEvent.ToggledHiddenFolders.takeIf { hidden.folders != shown.folders },
		).fold(opened) { state, toggle -> step(state, toggle).state }
	}

	/** No hidden entry shown. */
	val NO_HIDDEN = Hidden(files = false, folders = false)
}

/** [state] after each of [inputs] in turn, with every folder read and name looked up answered by [Fixtures.Memory]. */
fun after(state: BrowserState, vararg inputs: Input): BrowserState =
	inputs.fold(state) { at, input -> step(at, input).settled(Fixtures.Memory) }

/** The effects [input] asks for of [state], answered or not. */
fun effectsOf(state: BrowserState, input: Input): List<Effect> =
	step(state, input).effects
