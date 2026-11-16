package prism.desktop

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import prism.desktop.freedesktop.FreedesktopTrash
import prism.desktop.macos.SystemTrash
import prism.platform.Os

/*
 * The user's trash, as their platform keeps it, so that what prism moves there is in the trash the desktop's own file
 * manager shows: freedesktop.org's on Linux (a trash on each mount, with a record of where each entry came from),
 * the system's on macOS. Each says where a path would go before anything is moved, so that what has no trash is never
 * deleted without the user saying so; and answers a trashing with where the entry went, for it to be put back.
 */

/** An entry of a trash: where it was, the name the trash keeps it under, and the trash ([bin], its folder). */
data class Trashed(
	val original: Path,
	val name: String,
	val bin: Path,
)

/** Where something would be trashed: [In] a trash ([bin], as the platform knows it), or [Nowhere], and why. */
sealed interface TrashFor<out B> {
	data class In<B>(
		val bin: B,
	) : TrashFor<B>

	data class Nowhere(
		val why: String,
	) : TrashFor<Nothing>
}

/** The trash [transform] makes of this one's, where there is one. */
inline fun <B, C> TrashFor<B>.map(transform: (B) -> C): TrashFor<C> =
	when (this) {
		is TrashFor.In -> TrashFor.In(transform(bin))
		is TrashFor.Nowhere -> this
	}

/** An entry of a trash, as its record says: when it was trashed ([deleted], where the record says), and what it is. */
data class TrashEntry(
	val trashed: Trashed,
	val deleted: Instant?,
	val folder: Boolean,
	val size: Long?,
	/** When it was last modified, before it was trashed. */
	val modified: Instant? = null,
)

/** The user's trash. */
interface Trash {
	/** Where [path] would be trashed: the folder of the trash it would go to; or nowhere, and why. */
	fun whereFor(path: Path): TrashFor<Path>

	/** [path] moved into its trash, and where it went; it fails, moving nothing, where [whereFor] says nowhere. */
	fun put(path: Path): Trashed

	/** [entry] put back where it was, never over something there now; answered with where it is back. */
	fun putBack(entry: Trashed): Path

	/** [entry] deleted for good. */
	fun erase(entry: Trashed)

	/** Every entry of every trash the user has. */
	fun entries(zone: ZoneId = ZoneId.systemDefault()): List<TrashEntry>

	/** No trash at all, because [why]: nothing goes anywhere, and nothing is deleted unless the user says so. */
	class None(
		private val why: String,
	) : Trash {
		override fun whereFor(path: Path): TrashFor<Path> = TrashFor.Nowhere(why)

		override fun put(path: Path): Trashed =
			throw IOException("“${path.fileName}” can't be moved to the trash: $why.")

		override fun putBack(entry: Trashed): Path =
			throw IOException("There is no trash to put “${entry.name}” back from.")

		override fun erase(entry: Trashed) =
			throw IOException("There is no trash to delete “${entry.name}” from.")

		override fun entries(zone: ZoneId): List<TrashEntry> = emptyList()
	}

	companion object {
		/** The trash of the user this process runs as, on [os]: freedesktop.org's on Linux, the system's on macOS. */
		fun ofUser(os: Os = Os.current): Trash =
			when (os) {
				Os.Linux -> FreedesktopTrash.ofUser()
				Os.MacOS -> SystemTrash()
				Os.Windows -> None("prism has no trash for Windows yet")
			}
	}
}

/**
 * [path] deleted for good, with all it holds; a link is deleted itself, never what it leads to. Throws at the first
 * entry that cannot be, having deleted what came before it.
 */
fun erased(path: Path) {
	if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
		Files.delete(path)

		return
	}

	Files.walkFileTree(
		path,
		object : SimpleFileVisitor<Path>() {
			override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
				Files.delete(file)

				return FileVisitResult.CONTINUE
			}

			override fun postVisitDirectory(folder: Path, failed: IOException?): FileVisitResult {
				failed?.let { throw it }
				Files.delete(folder)

				return FileVisitResult.CONTINUE
			}
		},
	)
}
