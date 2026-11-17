package prism.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import prism.index.IgnoreRules
import prism.platform.Mount

/*
 * A daemon's place in the system's own record of changes, kept beside its index, so that one started later takes up
 * where the last left off and is told what changed while none ran, where otherwise it reads every root again to find
 * out. Only a Mac keeps such a record (FSEvents'); the Linux watcher sees what happens while it watches and nothing
 * before.
 *
 * The place is good only for the index it was kept with, read as that index was: the same volumes mounted, and the
 * same rules of what is left out. So it is kept with a mark of those ([recordOf]), and a daemon whose own mark is
 * another reads its roots as if there were no place kept.
 *
 * It is always a place the index's file is known to be up to, or before: every change says what is so, so being told
 * again of one already made changes nothing, and being told of none that was missed is what must not happen.
 */

/** A place in the record of changes ([id], the last one made in the index), and [of] what that record is. */
data class Place(
	val of: String,
	val id: Long,
)

/** Where a daemon's [Place] is kept. */
class History(
	private val file: Path,
) {
	/** The place kept; null where there is none, or what is there is no place. */
	fun read(): Place? =
		try {
			Files
				.readString(file)
				.trim()
				.split(' ')
				.takeIf { it.size == 2 }
				?.let { (of, id) -> id.toLongOrNull()?.let { Place(of, it) } }
		} catch (none: IOException) {
			null
		}

	/** [place] kept, whole or not at all: written beside, and moved over what was there. */
	fun write(place: Place) {
		val beside = file.resolveSibling("${file.fileName}.new")

		Files.writeString(beside, "${place.of} ${place.id}\n")
		Files.move(
			beside,
			file,
			StandardCopyOption.REPLACE_EXISTING,
			StandardCopyOption.ATOMIC_MOVE,
		)
	}

	/** No place kept any more: what a daemon that reads its roots anew has. */
	fun forget() {
		try {
			Files.deleteIfExists(file)
		} catch (kept: IOException) {
			// Read again as one that is no good for this index, or good, and then the index is as it says.
		}
	}

	companion object {
		/** The history of the index whose file is [index]. */
		fun beside(index: Path): History =
			History(index.resolveSibling("${index.fileName}.history"))
	}
}

/**
 * A mark of what a record of changes is of: the volumes [mounts] are, and the [rules] of what is left out, in an
 * order of their own so that the same ones make the same mark.
 */
fun recordOf(rules: IgnoreRules, mounts: List<Mount>): String {
	val said =
		listOf(
			mounts.map { "${it.source} ${it.at} ${it.type}" }.sorted(),
			rules.directories.map { it.toString() }.sorted(),
			rules.fileSystems.sorted(),
			rules.sought.queries.map { it.toString() },
		).joinToString("\n") { it.joinToString("\n") }

	return MessageDigest
		.getInstance("SHA-256")
		.digest(said.encodeToByteArray())
		.joinToString("") { "%02x".format(it) }
}
