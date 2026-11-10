package barion.picker

import barion.index.Searched
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readLines

/*
 * The one place the picker meets a real file system: a [Disk] reads a folder into rows, with every row's text worked
 * out here, says what is at a typed path, and reads a search's results for their sizes and dates. The reducer never
 * reads anything; whoever runs the picker asks a disk (and a [Finder], for a search) for what an effect wants.
 */

/** What the picker reads its folders and looks up typed paths through. */
interface Disk {
	/** [folder]'s entries, every one, hidden ones too; or why it cannot be read. */
	fun read(folder: Path): Listing.Read

	/** What is at each of [paths]. */
	fun look(paths: List<Path>): Map<Path, Found>

	/** [change] made, and what came of it. */
	fun make(change: Change): ChangeResult

	/** What each of [paths] is on disk now: its size and date, as a row of its folder would show them; or gone. */
	fun stat(paths: List<Path>): List<Stat>
}

/** The answer to [asking], from this disk, and from [finder] for a search. */
fun Disk.answer(asking: Effect.Asking, finder: Finder? = null): Answer =
	when (asking) {
		is Effect.Read -> Answer.Arrived(asking.folder, read(asking.folder))
		is Effect.Look -> Answer.Looked(asking.lookup, look(asking.lookup.paths))
		is Effect.Make -> Answer.Changed(asking.change, make(asking.change), asking.doing)
		is Effect.Search -> Answer.Searched(finder.findingOf(asking), asking.purpose)
		is Effect.Stat ->
			Answer.Stated(
				asking.matches,
				asking.places.zip(stat(asking.places.map(asking.matches::pathOf))).toMap(),
			)
		is Effect.Order ->
			Answer.Ordered(
				asking.matches,
				asking.column,
				orderOf(asking.matches, Stats.none(asking.matches.size), asking.column),
			)
	}

/** What [search] finds through this finder; refused where there is none. */
fun Finder?.findingOf(search: Effect.Search): Finding =
	when (val found = this?.find(search.query)) {
		null -> Finding.Refused(search.text, PickerState.NO_INDEX)
		is Searched.Found -> Finding.Matched(search.text, Matches(found.results))
		is Searched.Refused -> Finding.Refused(search.text, found.why)
	}

/** The file system through the JVM, its times said in [zone]. */
class JvmDisk(
	private val zone: ZoneId = ZoneId.systemDefault(),
	private val trash: Trash = Trash.ofUser(),
) : Disk {
	override fun read(folder: Path): Listing.Read = listingOf(folder, zone)

	override fun look(paths: List<Path>): Map<Path, Found> = paths.associateWith(::foundAt)

	/**
	 * [change] made, answered with what undoes it. An entry trashed is put back from where the trash keeps it; trashing
	 * several stops at the first that fails, and what was trashed before it is still put back by the undo.
	 */
	override fun make(change: Change): ChangeResult =
		attempted {
			when (change) {
				// Never over what is there: the JVM refuses to move onto an existing entry without being told to.
				is Change.Rename -> Change.Rename(Files.move(change.from, change.to), change.from)
				is Change.MakeFolder -> Change.RemoveFolder(Files.createDirectory(change.path))
				// Only an empty folder: what is in it would be lost, and an undo should lose nothing.
				is Change.RemoveFolder -> Change.MakeFolder(change.path.also(Files::delete))
				is Change.Trash -> Change.PutBack(change.paths.map(trash::put))
				is Change.PutBack -> Change.Trash(change.entries.map(trash::putBack))
			}
		}

	override fun stat(paths: List<Path>): List<Stat> = paths.map { statOf(it, zone) }
}

/** What [path] is now, its link followed, with its size and date said as its row would say them; gone if it is not there. */
private fun statOf(path: Path, zone: ZoneId): Stat =
	try {
		val read = Files.readAttributes(path, BasicFileAttributes::class.java)

		Stat.Read(
			if (read.isDirectory)
				null
			else
				read.size().let { Shown(it, sizeOf(it)) },
			read.lastModifiedTime().toInstant().let { Shown(it, dateOf(it, zone)) },
		)
	} catch (gone: IOException) {
		Stat.Gone
	} catch (denied: SecurityException) {
		Stat.Read(null, null)
	}

/** [work] done, answered with what undoes it, or why it was not done, in the words a person reads. */
private fun attempted(work: () -> Change): ChangeResult =
	try {
		ChangeResult.Done(work())
	} catch (taken: FileAlreadyExistsException) {
		ChangeResult.Failed(
			"There is already something called “${Path(taken.file).fileName}” here.",
		)
	} catch (gone: NoSuchFileException) {
		ChangeResult.Failed("“${Path(gone.file).fileName}” isn't there any more.")
	} catch (denied: AccessDeniedException) {
		ChangeResult.Failed("You aren't allowed to change “${Path(denied.file).fileName}”.")
	} catch (full: DirectoryNotEmptyException) {
		ChangeResult.Failed("“${Path(full.file).fileName}” isn't empty any more.")
	} catch (failed: IOException) {
		ChangeResult.Failed(failed.message ?: failed.toString())
	} catch (denied: SecurityException) {
		ChangeResult.Failed(denied.message ?: "Not allowed.")
	}

/**
 * The state [step] comes to once everything it asks of the disk (and of [finder]) has been answered, in turn, here:
 * the picker as it is once it has settled, for drawing it headless and for tests.
 */
tailrec fun Step.settled(disk: Disk, finder: Finder? = null): PickerState {
	val asking = effects.filterIsInstance<Effect.Asking>()
	val first = asking.firstOrNull() ?: return state
	val next = step(state, disk.answer(first, finder))

	return Step(next.state, asking.drop(1) + next.effects).settled(disk, finder)
}

/** [folder] read into a picker that is [choosing], settled: what `barion snapshot` and the tests draw. */
fun pickerOf(
	folder: Path,
	choosing: Choosing,
	disk: Disk = JvmDisk(),
	home: Path = Path(System.getProperty("user.home")),
	title: String = titleOf(choosing),
): PickerState = opened(choosing, folder, home, sidebarOf(home), title).settled(disk)

/** What [folder] holds, as rows in no particular order, or why it cannot be read. */
fun listingOf(folder: Path, zone: ZoneId = ZoneId.systemDefault()): Listing.Read =
	try {
		Listing.Shown(
			Files.newDirectoryStream(folder).use { entries ->
				entries.mapNotNull { rowOf(it, zone) }
			},
		)
	} catch (failed: IOException) {
		Listing.Unreadable(failed.message ?: failed.toString())
	} catch (failed: DirectoryIteratorException) {
		Listing.Unreadable(failed.cause?.message ?: failed.toString())
	} catch (denied: SecurityException) {
		Listing.Unreadable(denied.message ?: "not allowed")
	}

/** What is at [path], following a link to what it leads to. */
private fun foundAt(path: Path): Found =
	try {
		when {
			Files.isDirectory(path) -> Found.Folder
			Files.exists(path) -> Found.File
			else -> Found.Nothing(inFolder = path.parent?.let(Files::isDirectory) ?: false)
		}
	} catch (denied: SecurityException) {
		Found.Nothing(inFolder = false)
	}

/** One entry as a row, its symlink followed for what it is and left as it is otherwise; null if it vanished. */
private fun rowOf(entry: Path, zone: ZoneId): Row? {
	val own =
		try {
			Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
		} catch (gone: IOException) {
			return null
		}

	// A link to a folder is shown as the folder it leads to, since that is what opening it does.
	val target =
		if (own.isSymbolicLink)
			followed(entry) ?: own
		else
			own
	val name = entry.name
	val folder = target.isDirectory
	val category =
		when {
			folder -> Category.Folder
			own.isSymbolicLink && !target.isRegularFile -> Category.Link
			else -> categoryOf(name)
		}

	return Row(
		id = RowId(name),
		path = entry,
		name = name,
		category = category,
		type =
			if (folder)
				"Folder"
			else
				typeOf(name, category),
		size =
			if (folder)
				null
			else
				target.size().let { Shown(it, sizeOf(it)) },
		modified = target.lastModifiedTime().toInstant().let { Shown(it, dateOf(it, zone)) },
		hidden = name.startsWith("."),
	)
}

private fun followed(link: Path): BasicFileAttributes? =
	try {
		Files.readAttributes(link, BasicFileAttributes::class.java)
	} catch (dangling: IOException) {
		null
	}

/**
 * [rows] in [sort]'s order, folders before files whatever it is, as every file manager has them. Names compare as
 * people read them: case aside, and numbers by their value, so `file2` comes before `file10`.
 */
fun sorted(rows: List<Row>, sort: Sort): List<Row> {
	val byName = Comparator<Row> { a, b -> naturalOrder(a.name, b.name) }
	val byColumn: Comparator<Row> =
		when (sort.by) {
			Sort.Column.Name -> byName
			Sort.Column.Modified -> compareBy<Row> { it.modified }.then(byName)
			Sort.Column.Type -> compareBy<Row> { it.type.lowercase() }.then(byName)
			Sort.Column.Size -> compareBy<Row> { it.size }.then(byName)
		}

	val directed =
		if (sort.ascending)
			byColumn
		else
			byColumn.reversed()

	return rows.sortedWith(compareBy<Row> { !it.isFolder }.then(directed))
}

/**
 * Two names in the order people read them: letters without case, and runs of digits by their value. It is the
 * comparison every sort of a folder makes n log n times, so it walks both names in place rather than splitting them.
 */
fun naturalOrder(a: String, b: String): Int {
	var i = 0
	var j = 0

	while (i < a.length && j < b.length) {
		if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
			// Two runs of digits: by value, which for digits without their leading zeros is by length, then by digit.
			val aStart = a.skipping('0', i)
			val bStart = b.skipping('0', j)
			val aEnd = a.digitsEnd(aStart)
			val bEnd = b.digitsEnd(bStart)
			val byLength = (aEnd - aStart).compareTo(bEnd - bStart)

			if (byLength != 0)
				return byLength

			for (k in 0 until aEnd - aStart) {
				val byDigit = a[aStart + k].compareTo(b[bStart + k])

				if (byDigit != 0)
					return byDigit
			}

			i = a.digitsEnd(i)
			j = b.digitsEnd(j)
		} else {
			val byLetter = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())

			if (byLetter != 0)
				return byLetter

			i++
			j++
		}
	}

	val byRest = (a.length - i).compareTo(b.length - j)

	return if (byRest != 0)
		byRest
	else
		a.compareTo(b)
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun String.skipping(char: Char, from: Int): Int {
	var at = from

	while (at < length - 1 && this[at] == char && this[at + 1].isAsciiDigit())
		at++

	return at
}

private fun String.digitsEnd(from: Int): Int {
	var at = from

	while (at < length && this[at].isAsciiDigit())
		at++

	return at
}

/** "4.2 KB", by the binary units Windows and most file managers show. */
fun sizeOf(bytes: Long): String {
	val units = listOf("bytes", "KB", "MB", "GB", "TB")
	var value = bytes.toDouble()
	var unit = 0

	while (value >= 1024 && unit < units.lastIndex) {
		value /= 1024
		unit++
	}

	return when {
		unit == 0 ->
			if (bytes == 1L)
				"1 byte"
			else
				"$bytes bytes"
		value < 10 -> "%.1f %s".format(Locale.ROOT, value, units[unit])
		else -> "%.0f %s".format(Locale.ROOT, value, units[unit])
	}
}

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

fun dateOf(instant: Instant, zone: ZoneId): String = DATE.format(instant.atZone(zone))

/** The path to [folder] as crumbs, from the home folder where it is under it and from the root otherwise. */
fun crumbsOf(folder: Path, home: Path): List<Crumb> {
	val underHome = folder.startsWith(home)
	val start = if (underHome) home else folder.root
	val first = Crumb(if (underHome) "Home" else "File system", start)
	val rest = start.relativize(folder).filter { it.toString().isNotEmpty() }

	return rest.runningFold(
		first,
	) { previous, part -> Crumb(part.toString(), previous.path.resolve(part)) }
}

/** The places the sidebar offers: the home folder and its well-known folders, then the file systems. */
fun sidebarOf(home: Path): Sidebar {
	val known = userDirectories(home)

	val quick =
		listOf(Place("Home", Place.Kind.Home, home)) +
			listOf(
				Place.Kind.Desktop to "XDG_DESKTOP_DIR",
				Place.Kind.Documents to "XDG_DOCUMENTS_DIR",
				Place.Kind.Downloads to "XDG_DOWNLOAD_DIR",
				Place.Kind.Pictures to "XDG_PICTURES_DIR",
				Place.Kind.Music to "XDG_MUSIC_DIR",
				Place.Kind.Videos to "XDG_VIDEOS_DIR",
			).mapNotNull { (kind, key) ->
				known[key]?.takeIf { it.isDirectory() && it != home }?.let {
					Place(
						it.name,
						kind,
						it,
					)
				}
			}

	val drives =
		listOf(Place("File system", Place.Kind.Drive, Path("/"))) +
			listOf(Path("/media"), Path("/mnt"), Path("/run/media/${home.name}"))
				.filter { it.isDirectory() }
				.flatMap { mounts ->
					Files.list(mounts).use { it.toList() }.filter { it.isDirectory() }
				}.map { Place(it.name, Place.Kind.Drive, it) }

	return Sidebar(
		listOf(Sidebar.Group("Quick access", quick), Sidebar.Group("This computer", drives)),
	)
}

/**
 * The XDG user directories as `~/.config/user-dirs.dirs` names them (`XDG_DESKTOP_DIR="$HOME/Desktop"`), with the
 * usual English names where it does not.
 */
fun userDirectories(home: Path): Map<String, Path> {
	val defaults =
		mapOf(
			"XDG_DESKTOP_DIR" to "Desktop",
			"XDG_DOCUMENTS_DIR" to "Documents",
			"XDG_DOWNLOAD_DIR" to "Downloads",
			"XDG_PICTURES_DIR" to "Pictures",
			"XDG_MUSIC_DIR" to "Music",
			"XDG_VIDEOS_DIR" to "Videos",
		).mapValues { (_, name) -> home.resolve(name) }

	val file = home.resolve(".config/user-dirs.dirs")
	val configured =
		if (file.exists())
			file
				.readLines()
				.mapNotNull { Regex("""^(XDG_\w+_DIR)="(.*)"$""").find(it.trim())?.destructured }
				.associate { (key, value) -> key to Path(value.replace("\$HOME", home.toString())) }
		else
			emptyMap()

	return defaults + configured
}
