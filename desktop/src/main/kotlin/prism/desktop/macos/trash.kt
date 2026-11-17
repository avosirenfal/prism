package prism.desktop.macos

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.time.ZoneId
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import prism.desktop.Trash
import prism.desktop.TrashEntry
import prism.desktop.TrashFor
import prism.desktop.Trashed
import prism.desktop.erased
import prism.macos.Binned
import prism.macos.FOLDER_REFUSED
import prism.macos.MacMounts
import prism.macos.MountFlag
import prism.macos.contains
import prism.macos.keptByMacOS
import prism.macos.trashItem
import prism.platform.Mount

/*
 * The system's trash, as Finder shows it: NSFileManager moves an item into the trash of its volume (`~/.Trash`, or the
 * volume's `.Trashes/<uid>`) under a name free there, and says where. Finder keeps where each item came from in a file
 * of its own that is not for other programs to read, so prism keeps it on the item itself, as an extended attribute
 * written before the move ([ORIGIN]), which goes with it: what prism trashed it can put back. What Finder trashed is
 * listed too, as in the trash, and is moved out by hand. A network volume has no trash, and is said to have none.
 */

/** The system's trash, of the user whose home is [home]. */
class SystemTrash(
	private val home: Path = Path.of(System.getProperty("user.home")),
	private val mounted: () -> List<Mount> = MacMounts::current,
	private val binOf: (Path) -> Binned = ::trashItem,
) : Trash {
	override fun whereFor(path: Path): TrashFor<Path> {
		val absolute = path.toAbsolutePath().normalize()
		val mount =
			mounted()
				.filter { absolute.startsWith(it.at) }
				.maxByOrNull { it.at.nameCount }

		return when {
			absolute.parent == null ->
				TrashFor.Nowhere(
					"the computer's own volume can't be moved to the trash",
				)
			mount != null && MountFlag.Local !in mount ->
				TrashFor.Nowhere(
					"${mount.at} is on another computer, with no trash",
				)
			mount == null || !mount.at.startsWith(VOLUMES) -> TrashFor.In(home.resolve(".Trash"))
			else -> TrashFor.In(mount.at.resolve(".Trashes").resolve(uid().toString()))
		}
	}

	override fun put(path: Path): Trashed {
		val original = path.toAbsolutePath().normalize()

		(whereFor(original) as? TrashFor.Nowhere)?.let {
			throw IOException("“${original.fileName}” can't be moved to the trash: ${it.why}.")
		}

		// Where it came from goes with it, where its file system keeps extended attributes; where not, it is trashed all the same.
		val recorded = record(original, original.toString())

		return when (val binned = binOf(original)) {
			is Binned.In -> Trashed(original, binned.at.fileName.toString(), binned.at.parent)
			is Binned.Refused -> {
				if (recorded)
					forget(original)
				throw IOException(
					"“${original.fileName}” can't be moved to the trash: ${binned.why}.",
				)
			}
		}
	}

	override fun putBack(entry: Trashed): Path {
		val kept = entry.bin.resolve(entry.name)

		if (!Files.exists(kept, LinkOption.NOFOLLOW_LINKS))
			throw IOException("“${entry.original.fileName}” is no longer in the trash.")
		// Its place in the trash, for what does not say where it came from.
		if (entry.original == kept)
			throw IOException(
				"“${entry.name}” was put in the trash by another program, which doesn't say where " +
					"it was. Move it out of the trash to where you want it.",
			)
		if (Files.exists(entry.original, LinkOption.NOFOLLOW_LINKS))
			throw FileAlreadyExistsException(entry.original.toString())

		Files.move(kept, entry.original, StandardCopyOption.ATOMIC_MOVE)
		forget(entry.original)

		return entry.original
	}

	override fun erase(entry: Trashed) = erased(entry.bin.resolve(entry.name))

	/**
	 * What each trash there is holds now, as Finder shows it. What prism put there says where it came from, and is put
	 * back there; what Finder or another program put there does not say so to prism (Finder keeps that to itself),
	 * and is shown as in the trash, to be moved out by hand.
	 */
	override fun entries(zone: ZoneId): List<TrashEntry> =
		bins().flatMap { bin ->
			listed(bin)
				.filterNot { it.fileName.toString() == DS_STORE }
				.mapNotNull { kept ->
					try {
						val attributes =
							Files.readAttributes(
								kept,
								"unix:ctime,size,lastModifiedTime",
								LinkOption.NOFOLLOW_LINKS,
							)

						val folder = Files.isDirectory(kept, LinkOption.NOFOLLOW_LINKS)

						TrashEntry(
							trashed =
								Trashed(
									recorded(kept)?.let(Path::of) ?: kept,
									kept.fileName.toString(),
									bin,
								),
							// Moving it into the trash changed it last: when it was trashed, near enough.
							deleted = (attributes["ctime"] as? FileTime)?.toInstant(),
							folder = folder,
							size = (attributes["size"] as Long).takeUnless { folder },
							modified = (attributes["lastModifiedTime"] as FileTime).toInstant(),
						)
					} catch (gone: IOException) {
						null
					}
				}
		}

	/**
	 * What is in the trash at [bin]: nothing, where it has not been made yet; and where macOS keeps it from prism (the
	 * trash is among what it asks about), that is said, with how to allow it.
	 */
	private fun listed(bin: Path): List<Path> =
		try {
			bin.listDirectoryEntries()
		} catch (none: java.nio.file.NoSuchFileException) {
			emptyList()
		} catch (refused: IOException) {
			throw if (keptByMacOS(refused))
				IOException(FOLDER_REFUSED)
			else
				refused
		}

	/** The user's trash, and each volume's under /Volumes that has one for them. */
	private fun bins(): List<Path> =
		listOf(home.resolve(".Trash")) +
			mounted()
				.filter { it.at.startsWith(VOLUMES) && MountFlag.Local in it }
				.map { it.at.resolve(".Trashes").resolve(uid().toString()) }
				.filter { it.isDirectory() }

	private fun uid(): Int = Files.getAttribute(home, "unix:uid") as Int

	/** [origin] written on [path] as where it came from; whether it could be. */
	private fun record(path: Path, origin: String): Boolean =
		try {
			view(path)?.write(ORIGIN, ByteBuffer.wrap(origin.toByteArray(Charsets.UTF_8)))
			true
		} catch (unsupported: IOException) {
			false
		} catch (unsupported: UnsupportedOperationException) {
			false
		}

	/** Where [path] came from, as its record says; null where it has none. */
	private fun recorded(path: Path): String? =
		try {
			view(path)?.takeIf { ORIGIN in it.list() }?.let { view ->
				ByteBuffer
					.allocate(view.size(ORIGIN))
					.also {
						view.read(ORIGIN, it)
					}.array()
					.toString(Charsets.UTF_8)
			}
		} catch (unreadable: IOException) {
			null
		}

	private fun forget(path: Path) {
		try {
			view(path)?.delete(ORIGIN)
		} catch (gone: IOException) {
			// Nothing to forget.
		}
	}

	private fun view(path: Path): UserDefinedFileAttributeView? =
		Files.getFileAttributeView(
			path,
			UserDefinedFileAttributeView::class.java,
			LinkOption.NOFOLLOW_LINKS,
		)

	companion object {
		/** The extended attribute that says where an item prism trashed came from. */
		const val ORIGIN = "dev.prism.trashed-from"

		private val VOLUMES: Path = Path.of("/Volumes")

		/** Finder's own record of how a folder is shown, which is in every folder it has shown, the trash among them. */
		private const val DS_STORE = ".DS_Store"
	}
}
