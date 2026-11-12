package prism.browser

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readLines
import prism.desktop.Desktop
import prism.desktop.FileTypes
import prism.desktop.Launched
import prism.desktop.MimeType
import prism.desktop.Opening
import prism.desktop.Place
import prism.desktop.Places
import prism.desktop.currentDesktop
import prism.index.SearchContext
import prism.index.Searched
import prism.index.search
import prism.preferences.Builtin
import prism.preferences.MemoryPreferences
import prism.preferences.PreferenceStore
import prism.preferences.Preferences
import prism.preferences.QuickAccess
import prism.preferences.QuickItem
import prism.preferences.Sort

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

	/** [erasing] deleted for good: null where all of it was, or why what came after what was could not be. */
	fun erase(erasing: Erasing): String?

	/** Every trash's entries, as rows, in no particular order. */
	fun trash(): Listing.Read

	/** What each of [paths] is on disk now: its size and date, as a row of its folder would show them; or gone. */
	fun stat(paths: List<Path>): List<Stat>

	/**
	 * Whether [a] and [b] are on the same volume, so that moving between them is a rename rather than a copy: what
	 * decides whether files dragged with no key held are moved or copied. Where it cannot be told, they are taken to be.
	 */
	fun sameVolume(a: Path, b: Path): Boolean
}

/**
 * What tells a browser that the folder it shows has changed on disk, so that it reads it again: something made in it,
 * taken from it, or written to. How a platform says so is its own (inotify on Linux, through the JVM here; FSEvents
 * and the USN journal elsewhere); a browser only asks to be told.
 */
fun interface FolderWatcher {
	/** [changed] called, on some thread, whenever what [folder] holds changes, until what this gives is closed. */
	fun watch(folder: Path, changed: () -> Unit): AutoCloseable

	companion object {
		/** Nothing watched: a browser that reads a folder when it is asked to, and not otherwise. */
		val NONE = FolderWatcher { _, _ -> AutoCloseable {} }
	}
}

/**
 * Folders watched through the JVM's watch service, which is the platform's own where the JVM has one (inotify on
 * Linux, ReadDirectoryChangesW on Windows) and polling where it has not. One service, and one thread reading it, for
 * every folder watched; a folder watched by several browsers is watched once.
 */
object JvmFolderWatcher : FolderWatcher {
	private val watched = HashMap<WatchKey, MutableList<() -> Unit>>()

	private val service: WatchService by lazy {
		FileSystems.getDefault().newWatchService().also { service ->
			Thread({ read(service) }, "prism-folders").apply {
				isDaemon = true
				start()
			}
		}
	}

	override fun watch(folder: Path, changed: () -> Unit): AutoCloseable {
		val key =
			try {
				folder.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
			} catch (failed: IOException) {
				return AutoCloseable {}
			} catch (unsupported: UnsupportedOperationException) {
				return AutoCloseable {}
			}

		synchronized(watched) { watched.getOrPut(key, ::ArrayList).add(changed) }

		return AutoCloseable {
			synchronized(watched) {
				watched[key]?.let { told ->
					told.remove(changed)
					// The last to watch it lets go of the folder itself.
					if (told.isEmpty()) {
						watched.remove(key)
						key.cancel()
					}
				}
			}
		}
	}

	private fun read(service: WatchService) {
		try {
			while (true) {
				val key = service.take()

				key.pollEvents()

				val told = synchronized(watched) { watched[key]?.toList().orEmpty() }

				told.forEach { it() }
				// A folder that is gone is watched no longer; whoever watched it was told it changed.
				if (!key.reset())
					synchronized(watched) { watched.remove(key) }
			}
		} catch (closed: ClosedWatchServiceException) {
			Unit
		} catch (stopped: InterruptedException) {
			Unit
		}
	}
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
	/** What runs the commands that are waited for: a place's listing, an action's watched command. */
	val commands: Commands = ProcessCommands,
	/** The home folder, which a command is run in where nothing says where. */
	val home: Path = Path(System.getProperty("user.home")),
	/**
	 * The entries of a place copied last, in any window these services are given to: what a paste then brings out. Let
	 * go of when files are copied instead.
	 */
	val carried: AtomicReference<Carried?> = AtomicReference(null),
	/** The folders made for a while, which are removed when they are done with. */
	val temps: Temps = SHARED_TEMPS,
	/** What says the folder shown has changed on disk; nothing does, unless whoever runs the browser gives one. */
	val watcher: FolderWatcher = FolderWatcher.NONE,
) {
	/** What conditions are decided by: the desktop's types, the `PATH`, and the disk. */
	val world: World by lazy { SystemWorld(home, desktop?.types) }
}

/** The world as this machine has it: types as the desktop names them, programs on the `PATH`, and what is on disk. */
class SystemWorld(
	override val home: Path,
	private val types: FileTypes?,
	private val path: String = System.getenv("PATH").orEmpty(),
) : World {
	private val found = ConcurrentHashMap<String, Boolean>()

	override fun lineage(thing: Thing): List<String> =
		when {
			thing.kind == ThingKind.Folder -> listOf(MimeType.FOLDER.name)
			types == null -> emptyList()
			else ->
				try {
					// An entry of a place has only its name to tell its type by.
					types.lineage(types.of(thing.path ?: Path("/nowhere").resolve(thing.name))).map {
						it.name
					}
				} catch (failed: RuntimeException) {
					emptyList()
				}
		}

	override fun installed(program: String): Boolean =
		found.getOrPut(program) {
			if ('/' in program)
				Files.isExecutable(Path(program))
			else
				path.split(':').filter { it.isNotEmpty() }.any {
					Path(it, program).let { at -> Files.isExecutable(at) && !Files.isDirectory(at) }
				}
		}

	override fun exists(path: Path): Boolean = Files.exists(path)
}

/** The temporary folders of this process, made once: every window's, and removed when prism ends. */
private val SHARED_TEMPS: Temps by lazy { Temps() }

/** What [search] finds in the place it says to search, with the search the index of the disk is given. */
private fun findingIn(tree: PlaceTree, search: Effect.Search): Finding =
	when (val found = tree.index.search(search.query, SearchContext(home = Path("/")))) {
		is Searched.Found -> Finding.Matched(search.text, Matches(found.results, tree))
		is Searched.Refused -> Finding.Refused(search.text, found.why)
	}

/**
 * The answer to [asking], from these. A command that is waited for (an action's, a place's listing) is watched by
 * what [watching] gives for its task, which is told how to stop it and what it writes.
 */
fun Services.answer(
	asking: Effect.Asking,
	watching: (Task) -> Watch = { Watch.NONE },
): Answer =
	when (asking) {
		is Effect.Read -> Answer.Arrived(asking.folder, disk.read(asking.folder))
		is Effect.Look -> Answer.Looked(asking.lookup, disk.look(asking.lookup.paths))
		is Effect.Make -> Answer.Changed(asking.change, disk.make(asking.change), asking.doing)
		is Effect.Erase -> Answer.Erased(asking.erasing, disk.erase(asking.erasing))
		Effect.ReadTrash -> Answer.TrashRead(disk.trash())
		is Effect.Search ->
			Answer.Searched(
				asking.within?.let { findingIn(it, asking) } ?: finder.findingOf(asking),
				asking.purpose,
			)
		is Effect.Born ->
			Answer.Born(
				asking.folder,
				asking.names.associateWith { name ->
					desktop?.facts?.bornAt(asking.folder.resolve(name))
				},
			)
		is Effect.Stat ->
			Answer.Stated(
				asking.matches,
				if (asking.matches.tree != null)
					asking.places.associateWith(asking.matches::listedStat)
				else
					asking.places.zip(disk.stat(asking.places.map(asking.matches::pathOf))).toMap(),
			)
		is Effect.Order ->
			Answer.Ordered(
				asking.matches,
				asking.column,
				orderOf(asking.matches, Stats.none(asking.matches.size), asking.column),
			)
		is Effect.Open ->
			Answer.Opened(
				desktop?.open(asking.paths, preferences.preferences.value.terminal)
					?: Opening(emptyList(), asking.paths),
			)
		is Effect.Terminal ->
			Answer.Started(
				desktop?.launcher?.terminal(asking.folder, preferences.preferences.value.terminal)
					?: Launched.Failed("a terminal", "there is no desktop to start one on"),
			)
		is Effect.ReadClip -> Answer.Clipped(asking.into, clipboard.read(), carried.get())
		is Effect.ListPlace ->
			Answer.Listed(
				asking.placed,
				listingOf(asking.placed, commands, home, watching(Task.Listing(asking.placed))),
			)
		// Each command waited for in turn; the first that fails ends them, and is why.
		// The folder made for them to work in goes when they end, however they do.
		is Effect.Run ->
			try {
				Answer.Ran(
					asking.job,
					asking.job.invocations.firstNotNullOfOrNull { invocation ->
						commands
							.run(invocation, watching(Task.Running(asking.job)))
							.takeUnless { it.worked }
							?.why
					}
						// What they made is brought to where it is wanted, once they have all worked.
						?: asking.job.bring?.let(::delivered),
				)
			} finally {
				asking.job.scratch?.let(temps::remove)
			}
		is Effect.Launch -> Answer.Started(launched(asking.job, asking.terminal))
		is Effect.Temp -> Answer.TempMade(temps.make(), asking.carried)
		is Effect.Scratch -> Answer.ScratchMade(temps.make(), asking.pending)
	}

/** [job]'s commands started on the desktop, each a process of its own, or in a terminal: the first that fails, or the last. */
private fun Services.launched(job: Job, terminal: Boolean): Launched {
	val launcher =
		desktop?.launcher
			?: return Launched.Failed(job.label, "there is no desktop to start it on")

	val started =
		job.invocations.map { invocation ->
			// A process started detached is not prism's to give an environment: `env` gives it its values.
			val words =
				if (invocation.environment.isEmpty())
					invocation.words
				else
					listOf("env") + invocation.environment.map { (name, value) -> "$name=$value" } +
						invocation.words

			if (terminal)
				launcher.inTerminal(words, invocation.folder, preferences.preferences.value.terminal)
			else
				launcher.run(words, invocation.folder)
		}

	return started.firstOrNull { it is Launched.Failed } ?: started.lastOrNull()
		?: Launched.Failed(job.label, "there is nothing to run")
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

/** The file system through the JVM, with [trash], the user's trashes. */
class JvmDisk(
	private val trash: Trash = Trash.ofUser(),
) : Disk {
	override fun read(folder: Path): Listing.Read = listingOf(folder)

	override fun look(paths: List<Path>): Map<Path, Found> = paths.associateWith(::foundAt)

	/**
	 * [change] made, answered with what undoes it. An entry trashed is put back from where the trash keeps it; trashing
	 * several stops at the first that fails, and what was trashed before it is still put back by the undo.
	 */
	override fun make(change: Change): ChangeResult =
		if (change is Change.Trash)
			trashed(change.paths)
		else
			attempted {
				when (change) {
					// Never over what is there: the JVM refuses to move onto an existing entry without being told to.
					is Change.Rename -> Change.Rename(Files.move(change.from, change.to), change.from)
					is Change.MakeFolder -> Change.RemoveFolder(Files.createDirectory(change.path))
					// Only an empty folder: what is in it would be lost, and an undo should lose nothing.
					is Change.RemoveFolder -> Change.MakeFolder(change.path.also(Files::delete))
					// Answered apart, below: a trash is not every path's to go to.
					is Change.Trash -> error("trashed apart")
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

	/**
	 * [paths] moved each to its drive's trash, in turn, until one fails; those on a drive with no trash left as they
	 * are, and said so, for the user to say whether to delete them for good. What was moved before a failure is still
	 * put back by the undo.
	 */
	private fun trashed(paths: List<Path>): ChangeResult {
		data class Going(
			val moved: List<Trashed> = emptyList(),
			val failed: String? = null,
		)

		val wheres = paths.map { it to trash.binFor(it) }
		val nowhere =
			wheres.mapNotNull { (path, where) ->
				(where as? TrashFor.Nowhere)?.let { Untrashable(path, it.why) }
			}

		val gone =
			wheres.fold(Going()) { going, (path, where) ->
				if (going.failed != null || where !is TrashFor.In)
					going
				else
					when (val put = tried { trash.put(path, where.bin) }) {
						is Tried.Did -> going.copy(moved = going.moved + put.value)
						is Tried.Failed -> going.copy(failed = put.why)
					}
			}

		return ChangeResult.Trashed(Change.PutBack(gone.moved), nowhere, gone.failed)
	}

	override fun erase(erasing: Erasing): String? =
		(
			tried {
				when (erasing) {
					is Erasing.Files -> erasing.paths.forEach(::erased)
					is Erasing.FromTrash -> erasing.entries.forEach(trash::erase)
					Erasing.Everything -> trash.entries().forEach { trash.erase(it.trashed) }
				}
			} as? Tried.Failed
		)?.why

	override fun trash(): Listing.Read =
		try {
			Listing.Shown(trash.entries().map(::rowOf))
		} catch (failed: IOException) {
			Listing.Unreadable(failed.message ?: failed.toString())
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

	override fun stat(paths: List<Path>): List<Stat> = paths.map(::statOf)

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
private fun statOf(path: Path): Stat =
	try {
		val read = Files.readAttributes(path, BasicFileAttributes::class.java)

		Stat.Read(
			if (read.isDirectory)
				null
			else
				read.size().let { Shown(it, sizeOf(it)) },
			read.lastModifiedTime().toInstant(),
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

/** What came of work on the disk: what it gave, or why it was not done, in the words a person reads. */
private sealed interface Tried<out T> {
	data class Did<T>(
		val value: T,
	) : Tried<T>

	data class Failed(
		val why: String,
	) : Tried<Nothing>
}

/** [work] done, or why it was not. */
private fun <T> tried(work: () -> T): Tried<T> =
	try {
		Tried.Did(work())
	} catch (taken: FileAlreadyExistsException) {
		Tried.Failed("There is already something called “${Path(taken.file).fileName}” here.")
	} catch (gone: NoSuchFileException) {
		Tried.Failed("“${Path(gone.file).fileName}” isn't there any more.")
	} catch (denied: AccessDeniedException) {
		Tried.Failed("You aren't allowed to change “${Path(denied.file).fileName}”.")
	} catch (full: DirectoryNotEmptyException) {
		Tried.Failed("“${Path(full.file).fileName}” isn't empty any more.")
	} catch (failed: IOException) {
		Tried.Failed(failed.message ?: failed.toString())
	} catch (denied: SecurityException) {
		Tried.Failed(denied.message ?: "Not allowed.")
	}

/** [work] done, answered with what undoes it, or why it was not done. */
private fun attempted(work: () -> Change): ChangeResult =
	when (val result = tried(work)) {
		is Tried.Did -> ChangeResult.Done(result.value)
		is Tried.Failed -> ChangeResult.Failed(result.why)
	}

/**
 * The state [step] comes to once everything it asks of the disk (and of [finder]) has been answered, in turn, here:
 * the picker as it is once it has settled, for drawing it headless and for tests.
 */
fun Step.settled(disk: Disk, finder: Finder? = null): BrowserState =
	settled(Services(disk, finder))

/** As [settled], with everything asked answered by [services]: a clip and quick access arranged are made there too, at once. */
tailrec fun Step.settled(services: Services): BrowserState {
	effects.filterIsInstance<Effect.Clip>().forEach {
		services.carried.set(null)
		services.clipboard.put(it.clip)
	}
	effects.filterIsInstance<Effect.Carry>().forEach { services.carried.set(it.carried) }
	effects.filterIsInstance<Effect.Arrange>().forEach { arrange ->
		services.preferences.update { it.arranging(arrange.quick) }
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
	opened(choosing, folder, home, sidebarOf(places, home), title).settled(disk)

/** What [folder] holds, as rows in no particular order, or why it cannot be read. */
fun listingOf(folder: Path): Listing.Read =
	try {
		Listing.Shown(
			Files.newDirectoryStream(folder).use { entries ->
				entries.mapNotNull(::rowOf)
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

/**
 * An entry of a trash as a row: named as it was, with when it was trashed for its date, and where it was for its
 * folder, as a search's result says where it is.
 */
private fun rowOf(entry: TrashEntry): Row {
	val name =
		entry.trashed.original.fileName
			?.toString() ?: entry.trashed.name

	val category =
		if (entry.folder)
			Category.Folder
		else
			categoryOf(name)

	return Row(
		id = RowId.Trashed(entry.trashed.bin, entry.trashed.name),
		item = Item.InTrash(entry.trashed),
		name = name,
		category = category,
		type =
			if (entry.folder)
				"Folder"
			else
				typeOf(name, category),
		size = entry.size?.let { Shown(it, sizeOf(it)) },
		modified = entry.deleted,
		hidden = false,
	)
}

/** One entry as a row, its symlink followed for what it is and left as it is otherwise; null if it vanished. */
private fun rowOf(entry: Path): Row? {
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
		item = Item.OnDisk(entry),
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
		modified = target.lastModifiedTime().toInstant(),
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
			Sort.Column.Created -> compareBy<Row> { it.created.at }.then(byName)
		}

	val directed =
		if (sort.ascending)
			byColumn
		else
			byColumn.reversed()

	// By when they were made: those not known (not read yet, or not kept) last whichever way, and by name among
	// themselves, since there is nothing else to order them by.
	val ordered =
		if (sort.by == Sort.Column.Created)
			Comparator<Row> { a, b ->
				val aKnown = a.created.at != null
				val bKnown = b.created.at != null

				when {
					aKnown && bKnown -> directed.compare(a, b)
					aKnown -> -1
					bKnown -> 1
					else -> byName.compare(a, b)
				}
			}
		else
			directed

	return rows.sortedWith(compareBy<Row> { !it.isFolder }.then(ordered))
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

/** The path to [folder] as crumbs, from the home folder where it is under it and from the root otherwise. */
fun crumbsOf(folder: Path, home: Path): List<Crumb> {
	val underHome = folder.startsWith(home)
	val start = if (underHome) home else folder.root
	val first = Crumb(if (underHome) "Home" else start.toString(), start)
	val rest = start.relativize(folder).filter { it.toString().isNotEmpty() }

	return rest.runningFold(
		first,
	) { previous, part -> Crumb(part.toString(), previous.path.resolve(part)) }
}

/** The sidebar: quick access as [preferences] arrange it, with the folders of prism's own [places] has; then the drives. */
fun sidebarOf(
	places: Places,
	home: Path,
	preferences: Preferences = Preferences.DEFAULT,
): Sidebar {
	val known =
		places
			.known(home)
			.mapNotNull { place ->
				builtinOf(place.kind)?.let {
					it to
						place
				}
			}.toMap()

	return Sidebar(quickAccessOf(preferences, known), known, places.drives(home))
}

/**
 * Quick access as [preferences] arrange it; or, where only an older version's pins say how, as they had it: the folders
 * pinned in their order, prism's own folders among them where they are [known], and those of prism's own folders the
 * pins left out hidden, since the user had unpinned them. The trash is newer than those pins, and shown.
 */
fun quickAccessOf(preferences: Preferences, known: Map<Builtin, Place>): QuickAccess =
	preferences.quickAccess
		?: preferences.pinned?.let { pins ->
			val items =
				pins.map { path ->
					known.entries.firstOrNull { it.value.path == path }?.let { QuickItem.Own(it.key) }
						?: QuickItem.Pinned(path)
				}
			val named = items.filterIsInstance<QuickItem.Own>().map { it.place }.toSet()

			QuickAccess.of(
				items +
					known.keys
						.filterNot {
							it in named
						}.map { QuickItem.Own(it, shown = false) },
			)
		}
		?: QuickAccess.DEFAULT

/** Which of prism's own places a place of the desktop's [kind] is; none for a folder pinned, or a drive. */
private fun builtinOf(kind: Place.Kind): Builtin? =
	when (kind) {
		Place.Kind.Home -> Builtin.Home
		Place.Kind.Desktop -> Builtin.Desktop
		Place.Kind.Documents -> Builtin.Documents
		Place.Kind.Downloads -> Builtin.Downloads
		Place.Kind.Pictures -> Builtin.Pictures
		Place.Kind.Music -> Builtin.Music
		Place.Kind.Videos -> Builtin.Videos
		Place.Kind.Folder, Place.Kind.Drive -> null
	}
