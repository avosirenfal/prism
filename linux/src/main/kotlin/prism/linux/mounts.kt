package prism.linux

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * One mounted file system, as `/proc/self/mountinfo` describes it: [id], the mount's own number (what statx's
 * `STATX_MNT_ID` says a file was reached through); [dev], the kernel's device for it; [root], the folder of its file
 * system that is mounted; [at], where it is mounted; [type], its file system's type; [source], what was mounted
 * (`/dev/sda1`, `tmpfs`, `server:/export`); and [options], its file system's own (`subvol=/@home`, of a btrfs).
 */
data class Mount(
	val id: Long,
	val dev: Dev,
	val root: String,
	val at: Path,
	val type: String,
	val source: String,
	val options: List<String> = emptyList(),
) {
	/** The value of the file system's option [name] (`subvol`, of `subvol=/@home`); null where it has none. */
	fun option(name: String): String? =
		options.firstNotNullOfOrNull {
			it.takeIf { it.startsWith("$name=") }?.substringAfter('=')
		}
}

/** The file systems mounted where this process sees them. */
object Mounts {
	private val MOUNTINFO = Path("/proc/self/mountinfo")

	/** What is mounted now, in the order it was mounted; empty where the kernel does not say (a system without /proc). */
	fun current(): List<Mount> =
		if (MOUNTINFO.exists())
			parse(MOUNTINFO.readLines())
		else
			emptyList()

	/**
	 * `mountinfo`'s lines: `ID PARENT MAJOR:MINOR ROOT MOUNT_POINT OPTIONS OPTIONAL... - TYPE SOURCE SUPER_OPTIONS`. A
	 * path's spaces, tabs, newlines and backslashes are written as octal escapes (`\040`).
	 */
	fun parse(lines: List<String>): List<Mount> =
		lines.mapNotNull { line ->
			val fields = line.split(' ')
			val separator = fields.indexOf("-")
			val device = fields.getOrNull(2)?.split(':')
			val id = fields.getOrNull(0)?.toLongOrNull()

			if (id == null || separator < 5 || fields.size <= separator + 2 || device?.size != 2)
				null
			else
				Mount(
					id,
					Dev(device[0].toInt(), device[1].toInt()),
					unescaped(fields[3]),
					Path(unescaped(fields[4])),
					fields[separator + 1],
					unescaped(fields[separator + 2]),
					fields
						.getOrNull(separator + 3)
						?.split(',')
						?.filter { it.isNotEmpty() }
						.orEmpty(),
				)
		}

	private val ESCAPE = Regex("\\\\([0-7]{3})")

	private fun unescaped(field: String): String =
		ESCAPE.replace(field) {
			it.groupValues[1]
				.toInt(8)
				.toChar()
				.toString()
		}
}
