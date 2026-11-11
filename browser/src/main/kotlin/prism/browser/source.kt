package prism.browser

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
import prism.desktop.Desktop
import prism.desktop.Launched
import prism.desktop.Opening
import prism.desktop.Place
import prism.desktop.Places
import prism.desktop.currentDesktop
import prism.index.Searched
import prism.preferences.MemoryPreferences
import prism.preferences.PreferenceStore

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

	/**
	 * Whether [a] and [b] are on the same volume, so that moving between them is a rename rather than a copy: what
	 * decides whether files dragged with no key held are moved or copied. Where it cannot be told, they are taken to be.
	 */
	fun sameVolume(a: Path, b: Path): Boolean
}

/**
 * What a browser's effects are carried out against: the [disk], the index it searches ([finder]), the [desktop] it opens
 * files and starts terminals on, the [clipboard] files are copied to, and the user's [preferences]. Without a desktop,
 * nothing can be opened, and is said not to be.
 */
data class Services(
	val disk: Disk,
	val finder: Finder? = null,
	val desktop: Desktop? = null,
	val clipboard: FileClipboard = MemoryFileClipboard(),
	val preferences: PreferenceStore = MemoryPreferences(),
)

/** The answer to [asking], from these. */
fun Services.answer(asking: Effect.Asking): Answer =
	when (asking) {
		is Effect.Read -> Answer.Arrived(asking.folder, disk.read(asking.folder))
		is Effect.Look -> Answer.Looked(asking.lookup, disk.look(asking.lookup.paths))
		is Effect.Make -> Answer.Changed(asking.change, disk.make(asking.change), asking.doing)
		is Effect.Search -> Answer.Searched(finder.findingOf(asking), asking.purpose)
		is Effect.Stat ->
			Answer.Stated(
				asking.matches,
				asking.places.zip(disk.stat(asking.places.map(asking.matches::pathOf))).toMap(),
			)
		is Effect.Order ->
			Answer.Ordered(
				asking.matches,
				asking.column,
				orderOf(asking.matches, Stats.none(asking.matches.size), asking.column),
			)
		is Effect.Open ->
			Answer.Opened(desktop?.open(asking.paths) ?: Opening(emptyList(), asking.paths))
		is Effect.Terminal ->
			Answer.Started(
				desktop?.launcher?.terminal(asking.folder, preferences.preferences.value.terminal)
					?: Launched.Failed("a terminal", "there is no desktop to start one on"),
			)
		is Effect.ReadClip -> Answer.Clipped(asking.into, clipboard.read())
	}

/** The answer to [asking], from this disk, and from [finder] for a search. */
fun Disk.answer(asking: Effect.Asking, finder: Finder? = null): Answer =
	Services(this, finder).answer(asking)

/** What [search] finds through this finder; refused where there is none. */
fun Finder?.findingOf(search: Effect.Search): Finding =
	when (val found = this?.find(search.query)) {
		null -> Finding.Refused(search.text, BrowserState.NO_INDEX)
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
				is Change.MakeFile -> Change.RemoveFile(Files.createFile(change.path))
				// Only an empty file: what was written into it since would be lost.
				is Change.RemoveFile -> {
					if (Files.size(change.path) != 0L)
						throw IOException("“${change.path.fileName}” has something in it now")
					Files.delete(change.path)
					Change.MakeFile(change.path)
				}
				// What undoes copies is trashing them, which can be undone in turn; links likewise.
				is Change.Copy -> Change.Trash(change.sources.map { copied(it, change.into) })
				is Change.Link -> Change.Trash(change.sources.map { linked(it, change.into) })
				is Change.Move ->
					Change.Relocate(
						change.sources
							.filter { it.parent != change.into }
							.map { Moved(moved(it, change.into), it) },
					)
				is Change.Relocate ->
					Change.Relocate(change.moves.map { Moved(Files.move(it.from, it.to), it.from) })
			}
		}

	/** [source] copied into [into], with all it holds, under a name free there; where it went. */
	private fun copied(source: Path, into: Path): Path {
		refuseInto(source, into)

		val target = into.resolve(freeName(into, source.fileName.toString()))

		Files.walk(source).use { entries ->
			for (entry in entries)
				Files.copy(
					entry,
					target.resolve(source.relativize(entry).toString()),
					StandardCopyOption.COPY_ATTRIBUTES,
					LinkOption.NOFOLLOW_LINKS,
				)
		}

		return target
	}

	/** A link to [source] made in [into], under a name free there; where it was made. */
	private fun linked(source: Path, into: Path): Path =
		Files.createSymbolicLink(
			into.resolve(freeLinkName(into, source.fileName.toString())),
			source.toAbsolutePath(),
		)

	/** [source] moved into [into] under a name free there; where it went. */
	private fun moved(source: Path, into: Path): Path {
		refuseInto(source, into)

		return Files.move(source, into.resolve(freeName(into, source.fileName.toString())))
	}

	/** A folder cannot go inside itself. */
	private fun refuseInto(source: Path, into: Path) {
		if (into.toAbsolutePath().normalize().startsWith(source.toAbsolutePath().normalize()))
			throw IOException("“${source.fileName}” can't go inside itself")
	}

	override fun stat(paths: List<Path>): List<Stat> = paths.map { statOf(it, zone) }

	/**
	 * By the device each is on, where the platform says (Linux and macOS, through the JVM's unix view), or else by the
	 * file store; the nearest folder that is there stands for a path that is not.
	 */
	override fun sameVolume(a: Path, b: Path): Boolean =
		try {
			val (one, other) = listOf(a, b).map(::nearestThere)

			try {
				Files.getAttribute(one, "unix:dev") == Files.getAttribute(other, "unix:dev")
			} catch (unsupported: UnsupportedOperationException) {
				Files.getFileStore(one) == Files.getFileStore(other)
			}
		} catch (failed: IOException) {
			true
		}

	private fun nearestThere(path: Path): Path =
		generateSequence(path.toAbsolutePath()) { it.parent }.first { Files.exists(it) }
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

/**
 * [name], or, where [folder] holds something of that name, the first of "a (copy).txt", "a (copy 2).txt", ... it does
 * not: the copy's mark before the extension, as file managers write it.
 */
fun freeName(folder: Path, name: String): String {
	val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
	val stem = name.substring(0, dot)
	val extension = name.substring(dot)

	return (
		sequenceOf(name, "$stem (copy)$extension") +
			generateSequence(2) { it + 1 }.map { "$stem (copy $it)$extension" }
	).first { !Files.exists(folder.resolve(it), LinkOption.NOFOLLOW_LINKS) }
}

/** [name], or, where [folder] holds something of that name, "Link to NAME", then "Link to NAME (2)", ... */
fun freeLinkName(folder: Path, name: String): String =
	(
		sequenceOf(name, "Link to $name") +
			generateSequence(2) { it + 1 }.map { "Link to $name ($it)" }
	).first { !Files.exists(folder.resolve(it), LinkOption.NOFOLLOW_LINKS) }

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
fun Step.settled(disk: Disk, finder: Finder? = null): BrowserState =
	settled(Services(disk, finder))

/** As [settled], with everything asked answered by [services]: a clip and a pin are made there too, at once. */
tailrec fun Step.settled(services: Services): BrowserState {
	effects.filterIsInstance<Effect.Clip>().forEach { services.clipboard.put(it.clip) }
	effects.filterIsInstance<Effect.Pin>().forEach { pin ->
		services.preferences.update { it.pinning(pin.paths) }
	}

	val asking = effects.filterIsInstance<Effect.Asking>()
	val first = asking.firstOrNull() ?: return state
	val next = step(state, services.answer(first))

	return Step(next.state, asking.drop(1) + next.effects).settled(services)
}

/** [folder] read into a picker that is [choosing], settled: what `prism snapshot` and the tests draw. */
fun pickerOf(
	folder: Path,
	choosing: Choosing,
	disk: Disk = JvmDisk(),
	home: Path = Path(System.getProperty("user.home")),
	title: String = titleOf(choosing),
	places: Places = currentDesktop().places,
): BrowserState =
	opened(choosing, folder, home, sidebarOf(places, home, null), title).settled(disk)

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

/**
 * The sidebar: the places [pinned] (where the user has pinned or unpinned any; each named as [places] names it where it
 * is one of the desktop's, and by its own name otherwise), or the desktop's own; then the drives.
 */
fun sidebarOf(places: Places, home: Path, pinned: List<Path>?): Sidebar {
	val known = places.known(home)

	return Sidebar(
		pinned?.map { path ->
			known.firstOrNull { it.path == path }
				?: Place(path.name.ifEmpty { "/" }, Place.Kind.Folder, path)
		}
			?: known,
		places.drives(home),
	)
}
