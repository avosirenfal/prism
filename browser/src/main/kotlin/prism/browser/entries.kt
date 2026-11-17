package prism.browser

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import kotlin.io.path.name
import prism.desktop.macos.Packages
import prism.linux.AtFlags
import prism.linux.DirentBuffer
import prism.linux.Errno
import prism.linux.Fd
import prism.linux.OpenFlags
import prism.linux.Sys
import prism.linux.close
import prism.linux.openat
import prism.linux.statx
import prism.macos.FOLDER_REFUSED
import prism.macos.keptByMacOS
import prism.platform.FileKind

/*
 * A folder's entries read into rows. Everything a row shows of an entry (what it is, its size, when it was last
 * changed and when it was made) is the answer to one question asked of the disk, so a row is whole as soon as there
 * is one: nothing of it is read later, and no listing is ever shown that is waiting for what it is ordered by.
 *
 * On Linux the question is statx(2), which says when a file was made where its file system keeps that, asked of each
 * name relative to the folder, which is opened once. Elsewhere it is the JVM's attributes of the entry, which say
 * the same of it.
 *
 * A folder told that some of its entries changed has those alone read again ([entriesOf]): one question for each,
 * however many the folder holds.
 */

/** What a row shows of an entry, as one read of it said. */
private class EntryRead(
	/** Whether the entry is a link, whatever it leads to. */
	val link: Boolean,
	/** Whether it is a folder, or a link to one: what opening it opens. */
	val folder: Boolean,
	/** Whether it is a file, or a link to one. */
	val regular: Boolean,
	val size: Long,
	val modified: Instant,
	val created: Created,
) {
	/** This, as a file's is read: for a folder that is shown and opened as one thing. */
	fun asFile(): EntryRead =
		EntryRead(link, folder = false, regular = true, size, modified, created)
}

/** [name] of [folder] as a row, from what was [read] of it. */
private fun rowOf(folder: Path, name: String, entry: EntryRead): Row {
	// A Mac's packages (an application, a document kept as a folder) are folders on disk and one thing to a person:
	// shown and opened as a file is, as Finder has them, and gone into only when asked ("Open as folder").
	val read =
		if (entry.folder && Packages.holds(name))
			entry.asFile()
		else
			entry

	val category =
		when {
			read.folder -> Category.Folder
			read.link && !read.regular -> Category.Link
			else -> categoryOf(name)
		}

	return Row(
		id = RowId(name),
		item = Item.OnDisk(folder.resolve(name)),
		name = name,
		category = category,
		type =
			if (read.folder)
				"Folder"
			else
				typeOf(name, category),
		size =
			if (read.folder)
				null
			else
				Shown(read.size, sizeOf(read.size)),
		modified = read.modified,
		hidden = name.startsWith("."),
		created = read.created,
	)
}

/** Why a folder could not be read, as [failure] says, and on a Mac, what to do where macOS keeps it from prism. */
private fun unreadable(failure: Throwable): String =
	if (keptByMacOS(failure))
		FOLDER_REFUSED
	else
		failure.message ?: failure.toString()

/** What [folder] holds, as rows in no particular order, or why it cannot be read. */
fun listingOf(folder: Path): Listing.Read =
	try {
		Listing.Shown(
			if (LinuxEntries.there)
				LinuxEntries.all(folder)
			else
				JvmEntries.all(folder),
		)
	} catch (failed: IOException) {
		Listing.Unreadable(unreadable(failed))
	} catch (failed: DirectoryIteratorException) {
		Listing.Unreadable(unreadable(failed.cause ?: failed))
	} catch (denied: SecurityException) {
		Listing.Unreadable(denied.message ?: "Permission denied.")
	}

/**
 * The entries [names] of [folder], each as its row is now, or none for one that is not there: those and no others
 * are read, each once (and what a link leads to, for a link).
 */
fun entriesOf(folder: Path, names: Set<String>): Map<String, Row?> =
	if (LinuxEntries.there)
		LinuxEntries.some(folder, names)
	else
		names.associateWith { JvmEntries.one(folder.resolve(it)) }

/** One entry as a row, its link followed for what it is and left as it is otherwise; null if it is not there. */
internal fun rowOf(entry: Path): Row? =
	entry.parent?.let { entriesOf(it, setOf(entry.name))[entry.name] }

/** Entries read through the JVM: every platform's, and what is used where statx(2) is not there to ask. */
private object JvmEntries {
	fun all(folder: Path): List<Row> =
		Files.newDirectoryStream(folder).use { entries -> entries.mapNotNull(::one) }

	fun one(entry: Path): Row? {
		val own =
			try {
				Files.readAttributes(
					entry,
					BasicFileAttributes::class.java,
					LinkOption.NOFOLLOW_LINKS,
				)
			} catch (gone: IOException) {
				return null
			}

		// A link to a folder is shown as the folder it leads to, since that is what opening it does.
		val target =
			if (own.isSymbolicLink)
				followed(entry) ?: own
			else
				own

		return rowOf(
			entry.parent,
			entry.name,
			EntryRead(
				link = own.isSymbolicLink,
				folder = target.isDirectory,
				regular = target.isRegularFile,
				size = target.size(),
				modified = target.lastModifiedTime().toInstant(),
				created = Created.At(target.creationTime().toInstant()),
			),
		)
	}

	private fun followed(link: Path): BasicFileAttributes? =
		try {
			Files.readAttributes(link, BasicFileAttributes::class.java)
		} catch (dangling: IOException) {
			null
		}
}

/**
 * Entries read by statx(2): a folder's names by getdents64(2), each asked about relative to the folder, into one
 * buffer that is written over for each. A link is asked about twice, once for itself and once for what it leads to,
 * as a row shows what opening it opens.
 */
private object LinuxEntries {
	/** Whether this is Linux, and its C library has the calls: asked once. */
	val there: Boolean by lazy {
		System.getProperty("os.name") == "Linux" &&
			try {
				statx(Fd.CWD, "/") is Sys.Ok
			} catch (missing: RuntimeException) {
				false
			} catch (missing: LinkageError) {
				false
			}
	}

	/** What `struct statx` takes. */
	private const val STATX_BYTES = 256L

	/** A folder is opened to be read, through a link where the way to it is one, and by no child process. */
	private val FOR_READING =
		OpenFlags.READ_ONLY or OpenFlags.DIRECTORY or OpenFlags.CLOSE_ON_EXEC

	/** An entry itself, not what it leads to; and no share is mounted for the asking. */
	private val ITSELF = AtFlags.SYMLINK_NO_FOLLOW or AtFlags.NO_AUTOMOUNT

	fun all(folder: Path): List<Row> =
		Arena.ofConfined().use { arena ->
			val dir =
				when (val opened = openat(Fd.CWD, folder.toString(), FOR_READING)) {
					is Sys.Ok -> opened.value
					is Sys.Failed -> throw IOException(saidOf(opened.errno))
				}

			try {
				val buffer = DirentBuffer(arena)
				val scratch = arena.allocate(STATX_BYTES, 8)
				val rows = ArrayList<Row>()

				while (true) {
					val length =
						when (val filled = buffer.fill(dir)) {
							is Sys.Ok -> filled.value
							is Sys.Failed -> throw IOException(saidOf(filled.errno))
						}

					if (length == 0)
						break
					buffer.forEach(length) { _, _, name, nameLength ->
						// One that went between being listed and being asked about is not there.
						read(dir, buffer.nameSegment(name, nameLength), scratch)?.let {
							rows += rowOf(folder, buffer.nameString(name), it)
						}
					}
				}

				rows
			} finally {
				close(dir)
			}
		}

	fun some(folder: Path, names: Set<String>): Map<String, Row?> =
		Arena.ofConfined().use { arena ->
			val scratch = arena.allocate(STATX_BYTES, 8)

			// Too few to be worth opening the folder for: each is asked about by its whole path.
			names.associateWith { name ->
				read(Fd.CWD, arena.allocateFrom(folder.resolve(name).toString()), scratch)
					?.let { rowOf(folder, name, it) }
			}
		}

	/** What a row shows of the entry at [path] (a C string) under [dir]; null where it is not there. */
	private fun read(dir: Fd, path: MemorySegment, scratch: MemorySegment): EntryRead? {
		val own = (statx(dir, path, ITSELF, scratch) as? Sys.Ok)?.value ?: return null
		val link = own.kind == FileKind.Symlink

		// A link to a folder is shown as the folder it leads to, since that is what opening it does; one that
		// leads nowhere, as itself.
		val target =
			if (link)
				(statx(dir, path, AtFlags.NO_AUTOMOUNT, scratch) as? Sys.Ok)?.value ?: own
			else
				own

		return EntryRead(
			link = link,
			folder = target.kind == FileKind.Directory,
			regular = target.kind == FileKind.Regular,
			size = target.size,
			modified = target.modified,
			created = target.born?.let(Created::At) ?: Created.Unkept,
		)
	}

	/** Why the system refused, as it says it. */
	private fun saidOf(errno: Errno): String =
		when (errno) {
			Errno.EACCES, Errno.EPERM -> "Permission denied"
			Errno.ENOENT -> "No such file or directory"
			Errno.ENOTDIR -> "Not a directory"
			Errno.ELOOP -> "Too many levels of symbolic links"
			Errno.EMFILE -> "Too many open files"
			Errno.EIO -> "Input/output error"
			Errno.ESTALE -> "Stale file handle"
			else -> errno.name
		}
}
