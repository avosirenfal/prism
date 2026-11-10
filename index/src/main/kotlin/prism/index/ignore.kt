package prism.index

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

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
			require(path.isAbsolute && path == path.normalize()) { "$path is not an absolute, normal path" }
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
						is Ignored.Anywhere -> place.key.endsWith(rule.ending.joinToString("") { "/" + keyOf(it) })
					}
				}
			}.toSet()

	/** These rules with [more] directories left out as well. */
	fun alsoIgnoring(more: Collection<Ignored>): IgnoreRules = copy(directories = directories + more)

	/** These rules with [rules] no longer leaving anything out. */
	fun notIgnoring(rules: Collection<Ignored>): IgnoreRules = copy(directories = directories - rules.toSet())

	companion object {
		private fun keyOf(name: Name): String = String(name.bytes(), Charsets.ISO_8859_1)

		/**
		 * The defaults. Each is a tree that can hold more entries than everything a person made, and that nobody picks
		 * a file from: version control's history, package managers' downloads, build tools' output and caches,
		 * browsers' caches, file system snapshots, the system's own stores, and temporary files. Generic names that
		 * people also give their own folders (`build`, `target`, `dist`, `vendor`) are not among them.
		 */
		val DEFAULT =
			IgnoreRules(
				directories =
					listOf(
						// Version control.
						".git",
						".hg",
						".svn",
						".bzr",
						".jj",
						// Packages, as package managers download and unpack them.
						"node_modules",
						"bower_components",
						"jspm_packages",
						".pnpm-store",
						".yarn/cache",
						".yarn/unplugged",
						".npm",
						".bun/install/cache",
						".m2",
						".ivy2",
						".gradle",
						".konan",
						".cargo",
						".rustup",
						".nuget",
						".dotnet",
						"go/pkg/mod",
						".cabal/packages",
						".cabal/store",
						".stack",
						".ghcup",
						".opam",
						".pub-cache",
						".julia/packages",
						".julia/artifacts",
						".conda/pkgs",
						".vscode-server",
						".vscode/extensions",
						// Python's environments and caches.
						"__pycache__",
						"__pypackages__",
						".venv",
						".tox",
						".nox",
						".eggs",
						".mypy_cache",
						".pytest_cache",
						".ruff_cache",
						".ipynb_checkpoints",
						// Build tools' output and caches.
						".cache",
						".kotlin",
						".direnv",
						".terraform",
						".next",
						".nuxt",
						".svelte-kit",
						".angular",
						".turbo",
						".parcel-cache",
						".zig-cache",
						"zig-cache",
						".stack-work",
						"dist-newstyle",
						".bloop",
						".metals",
						".bsp",
						".cpcache",
						".shadow-cljs",
						"elm-stuff",
						".dart_tool",
						".ccache",
						".sccache",
						"CMakeFiles",
						".flatpak-builder",
						".vagrant",
						".serverless",
						".aws-sam",
						// Browsers' and Electron applications' caches.
						"Cache_Data",
						"Code Cache",
						"GPUCache",
						"ShaderCache",
						"GrShaderCache",
						"DawnCache",
						"DawnGraphiteCache",
						"DawnWebGPUCache",
						"CacheStorage",
						"cache2",
						// Containers' images and layers.
						".local/share/containers",
						".local/share/flatpak/repo",
						// A Wine prefix's copy of Windows.
						"drive_c/windows",
						// File system snapshots, and what fsck found.
						".zfs",
						".snapshots",
						"lost+found",
						// The system's own stores, temporary files, and what is not files at all.
						"/nix/store",
						"/boot",
						"/proc",
						"/sys",
						"/dev",
						"/run",
						"/tmp",
						"/var/tmp",
						"/var/cache",
						"/var/log/journal",
						"/var/lib/docker",
						"/var/lib/containers",
						"/var/lib/flatpak/repo",
						"/var/lib/snapd",
						"/var/lib/systemd/coredump",
						"/snap",
					).map(Ignored::of).toSet(),
				fileSystems =
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

	/** The mount points of file systems [rules] leave out: read once, and looked up as each directory is met. */
	fun ignored(rules: IgnoreRules, mounts: List<Mount> = current()): Set<Path> =
		mounts.filter { it.type in rules.fileSystems }.map { it.at }.toSet()

	private val ESCAPE = Regex("\\\\([0-7]{3})")

	private fun unescaped(field: String): String = ESCAPE.replace(field) { it.groupValues[1].toInt(8).toChar().toString() }
}
