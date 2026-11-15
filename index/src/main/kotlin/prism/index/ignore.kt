package prism.index

import java.nio.file.Path
import kotlin.io.path.Path
import prism.linux.Mount
import prism.linux.Mounts

/**
 * A directory the index leaves out, with everything in it, written as a person writes one:
 *
 * - without a leading `/`, it applies anywhere: `.git` is every directory named `.git`, and `.local/share/containers`
 *   every directory whose path ends with those three names;
 * - with a leading `/`, it is that one directory: `/nix/store`.
 */
sealed interface Ignored {
	/** Every directory whose path ends with [ending]: one name, or several. */
	data class Anywhere(
		val ending: List<Name>,
	) : Ignored {
		init {
			require(ending.isNotEmpty()) { "a rule names at least one directory" }
		}
	}

	/** The directory at [path], which is absolute and normal (no `.`, `..` or trailing `/`). */
	data class At(
		val path: Path,
	) : Ignored {
		init {
			require(path.isAbsolute && path == path.normalize()) {
				"$path is not an absolute, normal path"
			}
		}
	}

	companion object {
		/** [rule] as written: `.git`, `.local/share/containers`, `/nix/store`. */
		fun of(rule: String): Ignored =
			if (rule.startsWith("/"))
				At(Path(rule).normalize())
			else
				Anywhere(rule.split('/').filter { it.isNotEmpty() }.map(Name::of))
	}
}

/**
 * What the index leaves out: directories, by their names or paths (each with everything in it), and whole file
 * systems, by their types, wherever they are mounted. What is left out is not read, not held and not watched; a change
 * inside it is one the host drops, since the index does not hold the directory it happened in.
 */
data class IgnoreRules(
	val directories: Set<Ignored>,
	/** File systems left out wherever they are mounted: the kernel's views of itself, and memory that is not files. */
	val fileSystems: Set<String>,
	/**
	 * What is left out that a scan cannot tell as it goes into a folder: entries a query finds, looked for in what a
	 * scan read and taken out of it, each with all under it ([Exclusions]).
	 */
	val sought: Seeking = Seeking.none(),
) {
	/** The [Ignored.At] rules, as the bytes of their paths (as [Where.key]s), to be looked up as a directory is met. */
	private val at: Set<String> by lazy {
		directories.filterIsInstance<Ignored.At>().map { Where.keyOf(it.path) }.toSet()
	}

	/**
	 * The [Ignored.Anywhere] rules, by their last name, each as the end of a path it leaves out (`/.local/share/
	 * containers`), so a directory is checked against only the rules that end in its own name.
	 */
	private val anywhere: Map<String, List<String>> by lazy {
		directories
			.filterIsInstance<Ignored.Anywhere>()
			.groupBy(
				{ keyOf(it.ending.last()) },
				{ rule -> rule.ending.joinToString("") { "/" + keyOf(it) } },
			)
	}

	/** Whether the directory at [where] is left out. */
	fun ignores(where: Where): Boolean {
		val key = where.key

		if (key in at)
			return true

		val endings = anywhere[key.substring(key.lastIndexOf('/') + 1)] ?: return false

		return endings.any(key::endsWith)
	}

	/**
	 * The rules that leave out the directory at [where] itself, or a directory above it: what would have to be set
	 * aside for it to be read.
	 */
	fun matching(where: Where): Set<Ignored> =
		generateSequence(where) { it.parent }
			.filterNot { it.isRoot }
			.flatMap { place ->
				directories.filter { rule ->
					when (rule) {
						is Ignored.At -> Where.keyOf(rule.path) == place.key
						is Ignored.Anywhere ->
							place.key.endsWith(
								rule.ending.joinToString("") {
									"/" +
										keyOf(it)
								},
							)
					}
				}
			}.toSet()

	/** These rules without those that leave out [where], or a folder above it: what a search of that place alone reads it with. */
	fun settingAside(where: Where): IgnoreRules =
		notIgnoring(matching(where)).copy(sought = sought.settingAside(where))

	/** These rules with [more] directories left out as well. */
	fun alsoIgnoring(more: Collection<Ignored>): IgnoreRules =
		copy(
			directories =
				directories + more,
		)

	/** These rules with [rules] no longer leaving anything out. */
	fun notIgnoring(rules: Collection<Ignored>): IgnoreRules =
		copy(
			directories =
				directories - rules.toSet(),
		)

	companion object {
		private fun keyOf(name: Name): String = String(name.bytes(), Charsets.ISO_8859_1)

		/** The defaults ([Exclusions.DEFAULTS]), with nothing of the user's. */
		val DEFAULT: IgnoreRules by lazy { Exclusions.DEFAULT.ignore }

		/** File systems left out wherever they are mounted: the kernel's views of itself, and memory that is not files. */
		val FILE_SYSTEMS: Set<String> =
			setOf(
				"proc",
				"sysfs",
				"devtmpfs",
				"devpts",
				"tmpfs",
				"ramfs",
				"cgroup",
				"cgroup2",
				"debugfs",
				"tracefs",
				"securityfs",
				"selinuxfs",
				"smackfs",
				"apparmorfs",
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
				"nfsd",
				"fuse.gvfsd-fuse",
				"fuse.portal",
				"fuse.lxcfs",
			)
	}
}

/** The mount points of file systems [rules] leave out: read once, and looked up as each directory is met. */
fun mountsLeftOut(rules: IgnoreRules, mounts: List<Mount> = Mounts.current()): Set<Path> =
	mounts.filter { it.type in rules.fileSystems }.map { it.at }.toSet()
