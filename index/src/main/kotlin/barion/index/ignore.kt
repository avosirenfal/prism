package barion.index

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * What the index leaves out: directories by name, wherever they are, and whole file systems by their type. What is
 * left out is not read, not held and not watched; a change inside it is one the host drops, since the index does not
 * hold the directory it happened in.
 */
data class IgnoreRules(
	/** Directories left out wherever they are: a repository's history, a package manager's tree, a cache. */
	val names: Set<Name>,
	/** File systems left out wherever they are mounted: the kernel's views of itself, which are not files. */
	val fileSystems: Set<String>,
) {
	fun ignores(name: Name): Boolean = name in names

	/** These rules with [more] names left out as well. */
	fun alsoIgnoring(more: Collection<Name>): IgnoreRules = copy(names = names + more)

	/** These rules with [names] no longer left out. */
	fun notIgnoring(names: Collection<Name>): IgnoreRules = copy(names = this.names - names.toSet())

	companion object {
		/**
		 * The defaults: version control's own directories, package managers' and build tools' trees, caches, and
		 * file system snapshots, each of which can hold more entries than everything a person made, and none of which
		 * a person picks a file from.
		 */
		val DEFAULT =
			IgnoreRules(
				names =
					listOf(
						".git",
						".hg",
						".svn",
						".jj",
						"node_modules",
						".pnpm-store",
						".zfs",
						".snapshots",
						".cache",
						"__pycache__",
						".mypy_cache",
						".pytest_cache",
						".gradle",
						".kotlin",
						".direnv",
						".venv",
						".tox",
						".cargo",
						".rustup",
						".npm",
					).map(Name::of).toSet(),
				fileSystems =
					setOf(
						"proc",
						"sysfs",
						"devtmpfs",
						"devpts",
						"cgroup",
						"cgroup2",
						"debugfs",
						"tracefs",
						"securityfs",
						"bpf",
						"pstore",
						"configfs",
						"fusectl",
						"mqueue",
						"hugetlbfs",
						"autofs",
						"binfmt_misc",
						"efivarfs",
						"nsfs",
						"rpc_pipefs",
					),
			)
	}
}

/** One mounted file system, as `/proc/self/mountinfo` describes it. */
data class Mount(
	val at: Path,
	val type: String,
	val major: Int,
	val minor: Int,
)

/** The file systems mounted where this process sees them. */
object Mounts {
	private val MOUNTINFO = Path("/proc/self/mountinfo")

	/** What is mounted now; empty where the kernel does not say (a system without /proc). */
	fun current(): List<Mount> = if (MOUNTINFO.exists()) parse(MOUNTINFO.readLines()) else emptyList()

	/**
	 * `mountinfo`'s lines: `ID PARENT MAJOR:MINOR ROOT MOUNT_POINT OPTIONS [OPTIONAL...] - TYPE SOURCE ...`. A mount
	 * point's spaces and tabs are written as octal escapes (`\040`).
	 */
	fun parse(lines: List<String>): List<Mount> =
		lines.mapNotNull { line ->
			val fields = line.split(' ')
			val separator = fields.indexOf("-")
			val device = fields.getOrNull(2)?.split(':')

			if (separator < 0 || fields.size <= separator + 1 || device?.size != 2)
				null
			else
				Mount(
					Path(unescaped(fields[4])),
					fields[separator + 1],
					device[0].toInt(),
					device[1].toInt(),
				)
		}

	/** The mount points of file systems [rules] leave out. */
	fun ignored(rules: IgnoreRules, mounts: List<Mount> = current()): Set<Path> =
		mounts.filter { it.type in rules.fileSystems }.map { it.at }.toSet()

	private val ESCAPE = Regex("\\\\([0-7]{3})")

	private fun unescaped(field: String): String = ESCAPE.replace(field) { it.groupValues[1].toInt(8).toChar().toString() }
}
