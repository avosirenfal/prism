package barion.picker

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
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
 * The one place a picker's state is made from a real directory: read through the JVM, every row's text worked out
 * here, the rows sorted here. The view never reads the file system and never formats anything. When the index is
 * behind the picker, this is what it replaces.
 */

/** What a folder of the picker is read with: which rows to show, and in what order. */
data class Reading(
	val sort: Sort = Sort.BY_NAME,
	val showHidden: Boolean = false,
	val zone: ZoneId = ZoneId.systemDefault(),
)

/** [folder] read into a picker that is [choosing], with everything it shows worked out. */
fun pickerOf(
	folder: Path,
	choosing: Choosing,
	reading: Reading = Reading(),
	home: Path = Path(System.getProperty("user.home")),
	title: String = titleOf(choosing),
): PickerState {
	val shown = folder.toAbsolutePath().normalize()

	return PickerState(
		title = title,
		choosing = choosing,
		folder = shown,
		crumbs = crumbsOf(shown, home),
		sidebar = sidebarOf(home),
		listing = listingOf(shown, reading),
		filters = listOf(TypeFilter.ALL_FILES),
		filter = TypeFilter.ALL_FILES,
		query = "",
		canGoBack = false,
		canGoForward = false,
	)
}

fun titleOf(choosing: Choosing): String =
	when (choosing) {
		is Choosing.OneFile -> "Open"
		is Choosing.Files -> "Open files"
		is Choosing.SaveAs -> "Save as"
		is Choosing.Folder -> "Select folder"
	}

/** What [folder] holds, as rows in [reading]'s order, or why it cannot be read. */
fun listingOf(folder: Path, reading: Reading): Listing =
	try {
		val rows =
			Files.newDirectoryStream(folder).use { entries ->
				entries.mapNotNull { rowOf(it, reading.zone) }.filter {
					reading.showHidden ||
						!it.hidden
				}
			}

		Listing.Shown(sorted(rows, reading.sort), reading.sort)
	} catch (failed: IOException) {
		Listing.Unreadable(failed.message ?: failed.toString())
	} catch (denied: SecurityException) {
		Listing.Unreadable(denied.message ?: "not allowed")
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
				sizeOf(target.size()),
		modified = dateOf(target.lastModifiedTime().toInstant(), zone),
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
	val byColumn: Comparator<Row> =
		when (sort.by) {
			Sort.Column.Name -> Comparator { a, b -> naturalOrder(a.name, b.name) }
			Sort.Column.Modified -> compareBy { it.modified }
			Sort.Column.Type ->
				compareBy<Row> { it.type.lowercase() }.thenComparator {
					a,
					b,
					->
					naturalOrder(a.name, b.name)
				}
			Sort.Column.Size -> compareBy { it.size?.let(::sizeKey) }
		}

	val directed =
		if (sort.ascending)
			byColumn
		else
			byColumn.reversed()

	return rows.sortedWith(compareBy<Row> { !it.isFolder }.then(directed))
}

/** Two names in the order people read them: letters without case, and runs of digits by their value. */
fun naturalOrder(a: String, b: String): Int {
	val chunks = Regex("\\d+|\\D+")
	val left = chunks.findAll(a.lowercase()).map { it.value }.toList()
	val right = chunks.findAll(b.lowercase()).map { it.value }.toList()

	for ((x, y) in left.zip(right)) {
		val numbers = x[0].isDigit() && y[0].isDigit()
		val compared =
			if (numbers)
				compareValuesBy(x, y, { it.trimStart('0').length }, { it.trimStart('0') })
			else
				x.compareTo(y)

		if (compared != 0)
			return compared
	}

	return compareValues(left.size, right.size).takeIf { it != 0 } ?: a.compareTo(b)
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

/** A size column's text back to bytes, for ordering by size. */
private fun sizeKey(size: String): Double {
	val (number, unit) =
		size.split(' ').let {
			it[0].toDouble() to
				it.getOrElse(1) { "bytes" }
		}

	val power =
		listOf("byte", "bytes", "KB", "MB", "GB", "TB").indexOf(unit).let {
			if (it <=
				1
			)
				0
			else
				it - 1
		}

	return number * Math.pow(1024.0, power.toDouble())
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
