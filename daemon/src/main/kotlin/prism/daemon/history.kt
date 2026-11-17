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
 * The place is good only for the index it was kept with, read as that index was. So it is kept with a mark of the
 * volumes that were mounted ([volumesOf]) and with the rules of what was left out ([rulesOf]). A daemon started with
 * other volumes reads its roots as if there were no place kept. So does one whose rules lack any that were kept:
 * what a rule taken away left out is not in the index, and is found only by reading. One whose rules are those kept
 * and more takes up from the place, and takes out of the index what the new rules leave out, which reads nothing.
 *
 * It is always a place the index's file is known to be up to, or before: every change says what is so, so being told
 * again of one already made changes nothing, and being told of none that was missed is what must not happen.
 */

/**
 * A place in the record of changes ([id], the last one made in the index), [of] which volumes that record is, and
 * the [rules] the index was read by.
 */
data class Place(
	val of: String,
	val rules: Set<String>,
	val id: Long,
)

/** Where a daemon's [Place] is kept. */
class History(
	private val file: Path,
) {
	/** The place kept; null where there is none, or what is there is no place. */
	fun read(): Place? =
		try {
			val lines = Files.readAllLines(file)
			val first = lines.firstOrNull()?.split(' ').orEmpty()

			first.getOrNull(1)?.toLongOrNull()?.takeIf { first.size == 2 }?.let {
				Place(first[0], lines.drop(1).toSet(), it)
			}
		} catch (none: IOException) {
			null
		}

	/** [place] kept, whole or not at all: written beside, and moved over what was there. A rule is a line. */
	fun write(place: Place) {
		val beside = file.resolveSibling("${file.fileName}.new")

		Files.write(beside, listOf("${place.of} ${place.id}") + place.rules.sorted())
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

/** A mark of the volumes [mounts] are: the same ones make the same mark, in whatever order they are listed. */
fun volumesOf(mounts: List<Mount>): String =
	MessageDigest
		.getInstance("SHA-256")
		.digest(
			mounts
				.map { "${it.source} ${it.at} ${it.type}" }
				.sorted()
				.joinToString("\n")
				.encodeToByteArray(),
		).joinToString("") { "%02x".format(it) }

/** [rules] as text, a rule to a line: what is compared to tell which rules a daemon has that the last had not. */
fun rulesOf(rules: IgnoreRules): Set<String> =
	(
		rules.directories.map { "folder $it" } +
			rules.fileSystems.map { "file system $it" } +
			rules.sought.queries.map { "query $it" } +
			"home ${rules.sought.home}"
	).map { it.replace('\n', ' ') }.toSet()
