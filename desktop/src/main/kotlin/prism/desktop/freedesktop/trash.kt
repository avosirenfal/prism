package prism.desktop.freedesktop

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import prism.desktop.Trash
import prism.desktop.TrashEntry
import prism.desktop.TrashFor
import prism.desktop.Trashed
import prism.desktop.erased
import prism.desktop.map
import prism.linux.AtFlags
import prism.linux.Fd
import prism.linux.Mount
import prism.linux.Mounts
import prism.linux.Sys
import prism.linux.statx
import prism.linux.uid
import prism.platform.XdgHomes

/*
 * The user's trashes on Linux, as the freedesktop.org trash specification lays them out, so that what prism trashes is in the
 * desktop's own trash, and any file manager can put it back. A trash is a folder holding two: `files`, what was
 * trashed, and `info`, a `.trashinfo` record for each, saying where it came from and when.
 *
 * An entry is moved into a trash, never copied: a move within one mount is a rename, which is instant and cannot leave
 * half a file behind. So each mount has a trash of its own. What is on the home trash's mount goes to the home trash
 * (`$XDG_DATA_HOME/Trash`); what is on another goes to a trash at that mount's top: `$top/.Trash/$uid`, where an
 * administrator has made a `.Trash` for every user (sticky, and not a link, or it is not used), or else the user's own
 * `$top/.Trash-$uid`, made as it is first needed. What is kept in memory (tmpfs, a ramdisk) has no trash: it is gone
 * when the machine stops anyway, and a trash there would only keep it from freeing the memory. Nor has what the kernel
 * shows of itself (/proc, /sys). Where there is no trash, nothing is deleted without the user saying so.
 */

/** A trash of freedesktop.org's: [root], holding `files` and `info`. */
sealed interface Bin {
	val root: Path

	/** The user's home trash, for what is on its own mount: its records name paths whole. */
	data class Home(
		override val root: Path,
	) : Bin

	/**
	 * A trash at the top of a mount, [top], for what is on that mount: its records name paths from [top], so that a
	 * drive mounted somewhere else next time still knows where its entries go back to.
	 */
	data class Top(
		override val root: Path,
		val top: Path,
	) : Bin
}

/** The mounts, as the trash needs to know them: which there are, and which a path is on. */
interface MountTable {
	/** What is mounted now. */
	fun all(): List<Mount>

	/** The mount [path] (which is there) is on; null where that cannot be told. */
	fun of(path: Path): Mount?

	/** The mounts of this machine, as the kernel says them now: they change as drives come and go. */
	object System : MountTable {
		override fun all(): List<Mount> = Mounts.current()

		/** The mount [path] was reached through, as statx says; or, on a kernel that does not say, the deepest it is under. */
		override fun of(path: Path): Mount? {
			val all = all()

			val id =
				try {
					(statx(Fd.CWD, path.toString(), AtFlags.NO_AUTOMOUNT) as? Sys.Ok)
						?.value
						?.mountId
						?.takeIf { it != 0L }
				} catch (notLinux: LinkageError) {
					null
				}

			return id?.let { all.lastOrNull { mount -> mount.id == it } }
				?: try {
					deepest(all, path.toRealPath())
				} catch (gone: IOException) {
					null
				}
		}
	}

	companion object {
		/** The mount of [all] that [path] is deepest under: of those mounted at one place, the last, which is the one seen. */
		fun deepest(all: List<Mount>, path: Path): Mount? =
			all
				.filter { path.startsWith(it.at) }
				.maxWithOrNull(compareBy<Mount> { it.at.nameCount }.thenBy { all.indexOf(it) })
	}
}

/**
 * The trashes of the user [uid]: [home], the home trash, and one at the top of each other mount, as [mounts] says
 * there are.
 */
class FreedesktopTrash(
	private val home: Path,
	private val uid: Int = currentUid(),
	private val mounts: MountTable = MountTable.System,
) : Trash {
	/**
	 * Where [path] is trashed: the trash of the mount it is on, made where the specification says to make it as it is
	 * needed; or nowhere, and why.
	 */
	fun binFor(path: Path): TrashFor<Bin> {
		val absolute = path.toAbsolutePath().normalize()
		// An entry is in its folder, whose mount it is moved within; a mount point itself cannot be moved at all.
		val folder =
			absolute.parent
				?: return TrashFor.Nowhere("the root folder can't be moved to the trash")
		val mount = mounts.of(folder) ?: return TrashFor.In(Bin.Home(home))
		val homeMount =
			generateSequence(home) {
				it.parent
			}.firstOrNull(Files::isDirectory)?.let(mounts::of)

		return when {
			homeMount?.id == mount.id -> TrashFor.In(Bin.Home(home))
			mount.inMemory ->
				TrashFor.Nowhere(
					"${mount.at} is an in-memory file system (${mount.type}) with no trash",
				)
			mount.type in KERNELS_OWN ->
				TrashFor.Nowhere(
					"${mount.at} is a system file system with no trash",
				)
			else -> topOf(mount.at)
		}
	}

	/** [path] moved into [bin], under its own name or, where that is taken, the first of `name.2`, `name.3`, .... */
	fun put(path: Path, bin: Bin): Trashed {
		val files = Files.createDirectories(bin.root.resolve("files"))
		val info = Files.createDirectories(bin.root.resolve("info"))
		val name = path.fileName.toString()

		// A name is claimed by making its record, which fails if it is taken, so two trashings cannot take one; a name
		// whose file is there without its record (a trash left untidy) is passed over, since moving onto it would
		// replace it.
		val (claimed, record) =
			(sequenceOf(name) + generateSequence(2) { it + 1 }.map { "$name.$it" })
				.filterNot { Files.exists(files.resolve(it), LinkOption.NOFOLLOW_LINKS) }
				.firstNotNullOf { candidate ->
					claimed(info.resolve("$candidate.trashinfo"), recordOf(path, bin))?.let {
						candidate to
							it
					}
				}

		try {
			Files.move(path, files.resolve(claimed), StandardCopyOption.ATOMIC_MOVE)
		} catch (elsewhere: AtomicMoveNotSupportedException) {
			Files.deleteIfExists(record)
			throw IOException(
				"“$name” is on a different drive from its trash.",
			)
		} catch (failed: IOException) {
			Files.deleteIfExists(record)
			throw failed
		}

		return Trashed(path.toAbsolutePath(), claimed, bin.root)
	}

	override fun whereFor(path: Path): TrashFor<Path> = binFor(path).map { it.root }

	/** [path] moved into its own trash: [binFor]'s, which fails where it is nowhere. */
	override fun put(path: Path): Trashed =
		when (val where = binFor(path)) {
			is TrashFor.In -> put(path, where.bin)
			is TrashFor.Nowhere -> throw IOException(
				"“${path.fileName}” can't be moved to the trash: ${where.why}.",
			)
		}

	/**
	 * [entry] put back where it was, never over something there now, and its record removed; answered with where it
	 * is back.
	 */
	override fun putBack(entry: Trashed): Path {
		val kept = entry.bin.resolve("files").resolve(entry.name)

		if (!Files.exists(kept, LinkOption.NOFOLLOW_LINKS))
			throw IOException("“${entry.original.fileName}” is no longer in the trash.")

		// An atomic move on Linux replaces what is there without a word, so what is there is looked for first.
		if (Files.exists(entry.original, LinkOption.NOFOLLOW_LINKS))
			throw FileAlreadyExistsException(entry.original.toString())

		Files.move(kept, entry.original, StandardCopyOption.ATOMIC_MOVE)
		Files.deleteIfExists(recordOf(entry))

		return entry.original
	}

	/** [entry] deleted for good, with its record. */
	override fun erase(entry: Trashed) {
		erased(entry.bin.resolve("files").resolve(entry.name))
		Files.deleteIfExists(recordOf(entry))
	}

	/** Every trash there is now: the home trash, and each mount's that has been made. */
	fun bins(): List<Bin> {
		val tops =
			mounts
				.all()
				.filterNot { it.inMemory || it.type in KERNELS_OWN }
				.map { it.at }
				.distinct()
				.flatMap { top ->
					listOfNotNull(
						sharedOf(top)?.takeIf(Files::isDirectory),
						top.resolve(".Trash-$uid").takeIf { owned(it) },
					).map { Bin.Top(it, top) }
				}

		return listOf(Bin.Home(home)) + tops.filterNot { it.root == home }
	}

	/** What every trash holds, as their records say: an entry with no record, or a record with no entry, is left out. */
	override fun entries(zone: ZoneId): List<TrashEntry> =
		bins().flatMap { entriesOf(it, zone) }

	private fun entriesOf(bin: Bin, zone: ZoneId): List<TrashEntry> {
		val info = bin.root.resolve("info")

		val records =
			try {
				info.takeIf { it.isDirectory() }?.listDirectoryEntries("*.trashinfo").orEmpty()
			} catch (unreadable: IOException) {
				emptyList()
			}

		return records.mapNotNull { record ->
			val name = record.name.removeSuffix(".trashinfo")
			val kept = bin.root.resolve("files").resolve(name)
			val fields = fieldsOf(record)
			val original = fields["Path"]?.let { originalOf(it, bin) }

			if (original == null || !Files.exists(kept, LinkOption.NOFOLLOW_LINKS))
				null
			else
				TrashEntry(
					Trashed(original, name, bin.root),
					fields["DeletionDate"]?.let { timeOf(it, zone) },
					folder = Files.isDirectory(kept, LinkOption.NOFOLLOW_LINKS),
					size =
						if (Files.isRegularFile(kept, LinkOption.NOFOLLOW_LINKS))
							Files.size(kept)
						else
							null,
					modified = Files.getLastModifiedTime(kept, LinkOption.NOFOLLOW_LINKS).toInstant(),
				)
		}
	}

	/** The trash at the top of the mount at [top]: the shared one where there is one fit to use, or the user's own. */
	private fun topOf(top: Path): TrashFor<Bin> {
		sharedOf(top)?.let { mine ->
			if (made(mine))
				return TrashFor.In(Bin.Top(mine, top))
		}

		val own = top.resolve(".Trash-$uid")

		return when {
			Files.isSymbolicLink(
				own,
			) -> TrashFor.Nowhere("$own is a symbolic link, which can't be used as a trash")
			Files.isDirectory(own, LinkOption.NOFOLLOW_LINKS) ->
				if (owned(own))
					TrashFor.In(Bin.Top(own, top))
				else
					TrashFor.Nowhere("$own belongs to another user")
			Files.exists(
				own,
				LinkOption.NOFOLLOW_LINKS,
			) -> TrashFor.Nowhere("$own is not a folder")
			made(own) -> TrashFor.In(Bin.Top(own, top))
			else -> TrashFor.Nowhere("$top has no trash, and one can't be created there")
		}
	}

	/**
	 * The user's trash within the shared `.Trash` at [top], where there is one fit to use: a folder, not a link, with
	 * its sticky bit set, so that no user can take another's trash away. One that is not fit is not used at all.
	 */
	private fun sharedOf(top: Path): Path? {
		val shared = top.resolve(".Trash")
		val fit =
			Files.isDirectory(shared, LinkOption.NOFOLLOW_LINKS) &&
				!Files.isSymbolicLink(shared) &&
				(modeOf(shared) ?: 0) and STICKY != 0

		return shared.resolve(uid.toString()).takeIf { fit && !Files.isSymbolicLink(it) }
	}

	/** Whether [folder] is there as a folder of this user's, or could be made one, readable by them alone. */
	private fun made(folder: Path): Boolean =
		if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
			owned(folder)
		else
			try {
				Files.createDirectory(
					folder,
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
				)
				true
			} catch (failed: IOException) {
				false
			} catch (unsupported: UnsupportedOperationException) {
				// A file system without Unix's permissions (FAT): made as it can be, which is everyone's anyway.
				try {
					Files.createDirectory(folder)
					true
				} catch (failed: IOException) {
					false
				}
			}

	/** Whether [folder] is a folder (not a link to one) of this user's, or on a file system that keeps no owners. */
	private fun owned(folder: Path): Boolean =
		Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) &&
			(ownerOf(folder)?.let { it == uid } ?: true)

	/** What a record says of [path], trashed now into [bin]. */
	private fun recordOf(path: Path, bin: Bin): String =
		infoOf(writtenPathOf(path.toAbsolutePath(), bin))

	private fun recordOf(entry: Trashed): Path =
		entry.bin.resolve("info").resolve("${entry.name}.trashinfo")

	/** [record] made, saying [text]; null if a record of that name is there already. */
	private fun claimed(record: Path, text: String): Path? =
		try {
			Files.writeString(
				record,
				text,
				StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE,
			)
		} catch (taken: FileAlreadyExistsException) {
			null
		}

	companion object {
		/** The trash of the user this runs as: `$XDG_DATA_HOME/Trash`, which is `~/.local/share/Trash` by default. */
		fun ofUser(environment: Map<String, String> = System.getenv()): FreedesktopTrash =
			FreedesktopTrash(XdgHomes(environment).data.resolve("Trash"))

		/** The user this process runs as, where the platform says (Linux); nobody's otherwise, which owns no trash. */
		private fun currentUid(): Int =
			try {
				uid()
			} catch (notLinux: LinkageError) {
				-1
			}

		/** File systems kept in memory, which go when the machine stops: no trash is kept on them. */
		private val IN_MEMORY = setOf("tmpfs", "ramfs", "devtmpfs", "hugetlbfs", "mqueue")

		/** Block devices that are memory: a compressed one (zram), or the kernel's ramdisk. */
		private val RAM_DEVICE = Regex("/dev/(zram|ram)[0-9]+")

		/** File systems that are the kernel showing itself, which hold nothing of the user's. */
		private val KERNELS_OWN =
			setOf(
				"proc",
				"sysfs",
				"devpts",
				"cgroup",
				"cgroup2",
				"debugfs",
				"tracefs",
				"securityfs",
				"pstore",
				"configfs",
				"efivarfs",
				"bpf",
				"binfmt_misc",
				"fusectl",
				"autofs",
				"nsfs",
				"rpc_pipefs",
			)

		private const val STICKY = 0x200

		private val Mount.inMemory: Boolean get() =
			type in IN_MEMORY ||
				RAM_DEVICE.matches(source)

		/** What a `.trashinfo` file says of a file at [written] (as [writtenPathOf] writes it), trashed now. */
		fun infoOf(written: String, at: LocalDateTime = LocalDateTime.now()): String =
			"[Trash Info]\nPath=$written\nDeletionDate=${at.truncatedTo(ChronoUnit.SECONDS)}\n"

		/**
		 * [path] as a record of [bin] names it, escaped as a URL's path is: whole in the home trash; from the mount's
		 * top in a mount's own, where it is under it (as it always is: that is the trash it went to).
		 */
		fun writtenPathOf(path: Path, bin: Bin): String =
			escaped(
				when (bin) {
					is Bin.Home -> path.toString()
					is Bin.Top ->
						if (path.startsWith(bin.top) && path != bin.top)
							bin.top.relativize(path).toString()
						else
							path.toString()
				},
			)

		/** Where a record of [bin] that says [written] puts its entry back: from the mount's top where it is relative. */
		fun originalOf(written: String, bin: Bin): Path? {
			val path = Path(unescaped(written) ?: return null)

			return when {
				path.isAbsolute -> path
				bin is Bin.Top && ".." !in path.map { it.toString() } -> bin.top.resolve(path)
				else -> null
			}
		}

		/** [path] as a URL's path writes it: every byte but the unreserved characters and the slash, as `%XX`. */
		fun escaped(path: String): String =
			path.toByteArray().joinToString("") { byte ->
				val char = (byte.toInt() and 0xFF).toChar()

				if ((char.isLetterOrDigit() && char.code < 0x80) || char in "-_.!~*'()/")
					char.toString()
				else
					"%%%02X".format(byte.toInt() and 0xFF)
			}

		/** What [escaped] wrote, read back as UTF-8; null where it is not escaped as a URL's path is. */
		fun unescaped(text: String): String? {
			val bytes = java.io.ByteArrayOutputStream()
			var at = 0

			while (at < text.length) {
				val char = text[at]

				if (char == '%') {
					val byte =
						text.substring(at + 1, (at + 3).coerceAtMost(text.length)).toIntOrNull(16)
							?: return null

					bytes.write(byte)
					at += 3
				} else {
					bytes.write(char.toString().toByteArray())
					at += 1
				}
			}

			return bytes.toString(Charsets.UTF_8)
		}

		/** The fields of the record at [record]'s `[Trash Info]` group: `Path` and `DeletionDate`. */
		private fun fieldsOf(record: Path): Map<String, String> =
			try {
				record
					.readLines()
					.dropWhile { it.trim() != "[Trash Info]" }
					.drop(1)
					.takeWhile { !it.startsWith("[") }
					.mapNotNull { line ->
						line.indexOf('=').takeIf { it > 0 }?.let {
							line.substring(0, it).trim() to
								line.substring(it + 1).trim()
						}
					}.toMap()
			} catch (unreadable: IOException) {
				emptyMap()
			}

		/** A record's `DeletionDate` (`2026-11-12T14:03:00`, in the local zone), as an instant. */
		private fun timeOf(text: String, zone: ZoneId): Instant? =
			try {
				LocalDateTime.parse(text).atZone(zone).toInstant()
			} catch (malformed: java.time.format.DateTimeParseException) {
				null
			}

		private fun modeOf(path: Path): Int? =
			try {
				Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int
			} catch (unsupported: UnsupportedOperationException) {
				null
			} catch (failed: IOException) {
				null
			} catch (unknown: IllegalArgumentException) {
				null
			}

		private fun ownerOf(path: Path): Int? =
			try {
				Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int
			} catch (unsupported: UnsupportedOperationException) {
				null
			} catch (failed: IOException) {
				null
			} catch (unknown: IllegalArgumentException) {
				null
			}
	}
}
