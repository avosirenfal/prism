package barion.picker

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.io.path.Path

/**
 * The user's trash, as the freedesktop.org trash specification lays it out, so that what the picker trashes is in the
 * desktop's own trash, and its file manager can put it back. Under [root] are two folders: `files`, which holds what
 * was trashed, and `info`, which holds a `.trashinfo` file for each, saying where it came from and when.
 *
 * An entry is moved into the trash, never copied, so the trash must be on the entry's own file system. The
 * specification gives each file system a trash of its own for the rest; this is only the home trash, and an entry on
 * another drive is refused with why, to be deleted for good instead if the user wants.
 */
class Trash(
	private val root: Path,
) {
	/** [path] moved into the trash, under its own name or, where that is taken, the first of `name.2`, `name.3`, .... */
	fun put(path: Path) {
		val files = Files.createDirectories(root.resolve("files"))
		val info = Files.createDirectories(root.resolve("info"))
		val name = path.fileName.toString()

		// A name is claimed by making its info file, which fails if it is taken, so two trashings cannot take one.
		val (claimed, record) =
			(sequenceOf(name) + generateSequence(2) { it + 1 }.map { "$name.$it" })
				.firstNotNullOf { candidate ->
					claimed(info.resolve("$candidate.trashinfo"), path)?.let {
						candidate to
							it
					}
				}

		try {
			Files.move(path, files.resolve(claimed), StandardCopyOption.ATOMIC_MOVE)
		} catch (elsewhere: AtomicMoveNotSupportedException) {
			Files.deleteIfExists(record)
			throw IOException(
				"“$name” is on another drive than the trash; it can be deleted for good instead.",
			)
		} catch (failed: IOException) {
			Files.deleteIfExists(record)
			throw failed
		}
	}

	/** [record] made, saying [path] was trashed now; null if a record of that name is there already. */
	private fun claimed(record: Path, path: Path): Path? =
		try {
			Files.writeString(
				record,
				infoOf(path),
				StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE,
			)
		} catch (taken: FileAlreadyExistsException) {
			null
		}

	companion object {
		/** The trash of the user this runs as: `$XDG_DATA_HOME/Trash`, which is `~/.local/share/Trash` by default. */
		fun ofUser(environment: Map<String, String> = System.getenv()): Trash =
			Trash(
				(
					environment["XDG_DATA_HOME"]?.takeIf { it.startsWith("/") }?.let { Path(it) }
						?: Path(System.getProperty("user.home"), ".local", "share")
				).resolve("Trash"),
			)

		/** What a `.trashinfo` file says of [path], trashed now. */
		fun infoOf(path: Path, at: LocalDateTime = LocalDateTime.now()): String =
			"[Trash Info]\nPath=${escaped(path.toAbsolutePath().toString())}\n" +
				"DeletionDate=${at.truncatedTo(ChronoUnit.SECONDS)}\n"

		/** [path] as a URL's path writes it: every byte but the unreserved characters and the slash, as `%XX`. */
		fun escaped(path: String): String =
			path.toByteArray().joinToString("") { byte ->
				val char = (byte.toInt() and 0xFF).toChar()

				if ((char.isLetterOrDigit() && char.code < 0x80) || char in "-_.!~*'()/")
					char.toString()
				else
					"%%%02X".format(byte.toInt() and 0xFF)
			}
	}
}
