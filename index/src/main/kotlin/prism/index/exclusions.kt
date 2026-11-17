package prism.index

import java.nio.file.Path
import java.util.WeakHashMap
import prism.index.query.Anchors
import prism.index.query.Case
import prism.index.query.DirTarget
import prism.index.query.Flag
import prism.index.query.Glob
import prism.index.query.NameMatch
import prism.index.query.NamePart
import prism.index.query.PathBase
import prism.index.query.PathPattern
import prism.index.query.Query
import prism.index.query.QueryText
import prism.index.query.Segment
import prism.index.query.Term
import prism.index.query.Test
import prism.index.query.TextPattern
import prism.platform.Dev
import prism.platform.KernelDev
import prism.platform.Os
import prism.shared.Guarded

/*
 * What the index leaves out, as the user says it (docs/daemon.md): rules, each of which names entries and says what
 * is done with them.
 *
 * A rule names entries by a path (`/nix`, `~/Games`: the folder there) or by a query in the search language
 * (`build/`, `.o`, `is:dir ^tmp_`). What a rule does it does to every entry it names AND TO EVERYTHING UNDER ONE:
 * nothing under a folder left out is held, at any depth. A rule of folder terms alone names the folders, as the same
 * words typed as a search find them ([Query.aimed]): `build/` as a rule is every folder named build, and with it
 * all it holds. A rule tells upper case from lower, as the disk does, unless one of its terms says otherwise.
 *
 * What is done is one of two things ([Exclusion]): the entries are not indexed at all, or they are indexed and left
 * out of every search's results until a search names where they are.
 */

/** What a rule does with what it names, and all under it. */
enum class Exclusion {
	/** Not read, not held, not watched. A search that names a path into it has it read for that search alone. */
	Ignored,

	/**
	 * Held as everything else is, and left out of what a search finds, unless a path term of the search names the
	 * entry the rule named or something under it: `/games/ foo` finds what is under a hidden `/games`, and `foo`
	 * does not.
	 */
	Hidden,
}

/** What a rule names: the folder at a path, or what a query finds. */
sealed interface Naming {
	/** The query that finds the entries named themselves: all under each is the rule's too. */
	val itself: Query

	/** The folder at [path], which is absolute, normal, and not the root. */
	data class Folder(
		val path: Path,
	) : Naming {
		init {
			require(path.isAbsolute && path == path.normalize() && path.nameCount > 0) {
				"$path is not a folder's absolute path"
			}
		}

		override val itself: Query
			get() {
				val names = (0 until path.nameCount).map { path.getName(it).toString() }

				return Query(
					listOf(
						Term(
							Test.InPath(
								PathPattern(
									PathBase.Root,
									names.dropLast(1).map { Segment.Named(TextPattern.Literal(it)) },
									PathPattern.Last.Entry(
										NameMatch(
											TextPattern.Literal(names.last()),
											NamePart.Whole,
											Anchors.BOTH,
										),
									),
								),
							),
						),
					),
				)
			}
	}

	/** What [query] finds, which is what [text] reads as: equal to another of the same text. */
	class Sought internal constructor(
		val text: String,
		val query: Query,
	) : Naming {
		/**
		 * What the query finds, as a search for it does: for folder terms alone, the folders they name (`build/`:
		 * every folder named build), and for anything else the entries.
		 */
		override val itself: Query get() = query.aimed()

		override fun equals(other: Any?): Boolean = other is Sought && other.text == text

		override fun hashCode(): Int = text.hashCode()

		override fun toString(): String = "Sought($text)"
	}

	/** What reading a rule as it is written gives: what it names, or why it is no rule. */
	sealed interface Read {
		data class Fit(
			val naming: Naming,
		) : Read

		data class Unfit(
			val why: String,
		) : Read
	}

	companion object {
		/**
		 * [text] read as a rule. A path that starts at the root or at `~` (the [home] folder), with no glob in it
		 * and no space, is the folder there, with or without a `/` after it; anything else is a query. A query is
		 * refused that cannot be decided of an entry by where it is, what it is called and what kind of thing it is,
		 * as an entry is met on the disk with no index to ask: what it was used for (`recent:`), when it was seen
		 * (`new:`), what it is mounted on (`mount:`); and one that uses a name that may come to mean something else
		 * (an alias, `~name`).
		 */
		fun read(text: String, home: Path): Read {
			val written = text.trim()

			if (written.isEmpty())
				return Read.Unfit("Enter a path or a search")

			folderOf(written, home)?.let { return it }

			val read = QueryText.read(written)

			read.problems.firstOrNull()?.let { return Read.Unfit(it.message) }

			val query = read.query

			if (query.terms.isEmpty())
				return Read.Unfit("Enter a path or a search")

			return unfit(query.terms)?.let { Read.Unfit(it) } ?: Read.Fit(Sought(written, query))
		}

		/** [written] as the folder at a path, where it is plainly one; why it is not a folder a rule can name, where it is `/`. */
		private fun folderOf(written: String, home: Path): Read? {
			val plain = written.none { it.isWhitespace() || it == '"' } && !Glob.isGlob(written)
			val absolute =
				when {
					written == "~" -> home.toString()
					written.startsWith("~/") -> home.toString() + written.substring(1)
					written.startsWith("/") -> written
					else -> return null
				}

			if (!plain || '\\' in written)
				return null

			val path = Path.of(absolute).normalize()

			return if (path.nameCount == 0)
				Read.Unfit("The root folder can't be left out")
			else
				Read.Fit(Folder(path))
		}

		/** Why one of [terms] cannot be in a rule; null where every one can. */
		private fun unfit(terms: List<Term>): String? =
			terms.firstNotNullOfOrNull { term ->
				when (val test = term.test) {
					is Test.Recent -> "A rule can't use recent:"
					is Test.New -> "A rule can't use new:"
					is Test.Mount -> "A rule can't use mount:"
					is Test.All -> unfit(test.terms)
					is Test.Folders -> test.places.firstNotNullOfOrNull { named(it.pattern.base) }
					is Test.InPath -> named(test.pattern.base)
					is Test.Dir -> (test.target as? DirTarget.Located)?.let { named(it.base) }
					is Test.Name, is Test.Regex, is Test.Ext, is Test.Type, is Test.Is -> null
				}
			}

		private fun named(base: PathBase): String? =
			(base as? PathBase.Alias)?.let { "A rule can't use ~${it.name}" }
	}
}

/**
 * Entries found by queries with no search asked for: what rules name ([Naming.itself]), looked for in a block as it is
 * read, or as a search is about to walk it. Case is told apart, and `~` is [home].
 */
data class Seeking(
	val queries: List<Query>,
	val home: Path,
) {
	private val context: SearchContext get() =
		SearchContext(
			caseSensitive = true,
			home = home,
		)

	val none: Boolean get() = queries.isEmpty()

	/** The entries of [block] any of the queries finds, in the block's order, each once. */
	internal fun found(block: Block): IntArray =
		when (queries.size) {
			0 -> NOTHING
			1 -> block.found(queries[0], context)
			else ->
				queries
					.flatMap { block.found(it, context).asIterable() }
					.distinct()
					.sorted()
					.toIntArray()
		}

	/** Whether any of the queries finds the folder at [where], were it there. */
	fun finds(where: Where): Boolean =
		!none && !where.isRoot && found(folderAt(where)).isNotEmpty()

	/** The places at or above [where] that the queries find, as folders: the outermost first. */
	fun above(where: Where): List<Where> =
		if (none)
			emptyList()
		else
			generateSequence(where) { it.parent }.filter(::finds).toList().asReversed()

	/** These without the queries that find [where], or a folder above it: what would have to be set aside to read it. */
	fun settingAside(where: Where): Seeking {
		val places = generateSequence(where) { it.parent }.filterNot { it.isRoot }.toList()

		return copy(
			queries =
				queries.filterNot { query ->
					val one = Seeking(listOf(query), home)

					places.any(one::finds)
				},
		)
	}

	companion object {
		private val NOTHING = IntArray(0)

		fun none(home: Path = Path.of("/")): Seeking = Seeking(emptyList(), home)

		/** A block of one entry: a folder at [where], on no device anyone knows. */
		private fun folderAt(where: Where): Block =
			BlockBuilder(where)
				.also {
					it.add(
						-1,
						where.name,
						Kind.Directory,
						Device(Dev(0, 0), KernelDev(Dev(0, 0)), null),
						0,
					)
				}.built()
	}
}

/**
 * What a search leaves out of what it finds: the entries [seeking] finds, each with all under it. Which those are in
 * a block is worked out once a block, the first time a search walks it, and kept for as long as the block is: a block
 * never changes, and the big ones stay for as long as the index does.
 */
class Hiding(
	private val seeking: Seeking,
) {
	/** What is hidden of one block: its entries the rules name, in order, and the folders above its root they name. */
	private class Of(
		val entries: IntArray,
		val above: List<Where>,
	)

	private val known = Guarded(WeakHashMap<Block, Of>())

	val none: Boolean get() = seeking.none

	private fun of(block: Block): Of =
		known.guarded { this[block] }
			?: Of(
				seeking.found(block),
				block.root.parent
					?.let(seeking::above)
					.orEmpty(),
			).also { known.guarded { put(block, it) } }

	/**
	 * The entries of [block] a search does not look at: each one the rules name, with all under it, but for those
	 * the search [asked] for by path (each place a path term names, [Query.named]): one asked for itself, or with
	 * something under it asked for, is looked at, and what is under it too, down to the next the rules name.
	 */
	internal fun skipped(block: Block, asked: List<Where>): Ranges {
		if (none)
			return Ranges.NONE

		val of = of(block)

		if (of.above.any { hidden -> asked.none { it.within(hidden) } })
			return Ranges.of(intArrayOf(0, block.size))

		if (of.entries.isEmpty())
			return Ranges.NONE

		val shown =
			asked
				.filter { it.within(block.root) }
				.flatMap { place ->
					generateSequence(place) { it.parent }
						.takeWhile { it.within(block.root) }
						.mapNotNull { block.locate(it, Ranges.NONE) }
						.toList()
				}.toSet()

		val bounds = IntArray(of.entries.size * 2)
		var count = 0
		var covered = 0

		for (entry in of.entries) {
			if (entry < covered || entry in shown)
				continue

			bounds[count++] = entry
			bounds[count++] = block.end[entry]
			covered = block.end[entry]
		}

		return Ranges.of(bounds.copyOf(count))
	}

	companion object {
		val NONE = Hiding(Seeking.none())
	}
}

/**
 * The rules, all of them: prism's own ([DEFAULTS]), each of which the user may switch off or make the other kind,
 * and the user's own. Made only by [of], from what the preferences keep (the user's differences from the defaults),
 * so a rule is in it once, is of one kind, and reads as a rule.
 */
class Exclusions private constructor(
	val rules: List<Rule>,
	val home: Path,
) {
	/** One rule: as it is [written], what it [names], what is done ([exclusion]), whether it is one of prism's own, and whether it is [on]. */
	data class Rule(
		val written: String,
		val names: Naming,
		val exclusion: Exclusion,
		val builtIn: Boolean,
		val on: Boolean,
	)

	private fun namings(exclusion: Exclusion): List<Naming> =
		rules.filter { it.on && it.exclusion == exclusion }.map { it.names }

	/**
	 * What a scan leaves out. A rule that names a folder by its path, or folders by the names their paths end with
	 * (`build/`, `.yarn/cache/`), is one a scan never goes into ([Ignored]); any other is looked for in what a scan
	 * read, and taken out of it ([IgnoreRules.sought]).
	 */
	val ignore: IgnoreRules by lazy {
		val ignored = namings(Exclusion.Ignored)
		val plain =
			ignored
				.mapNotNull { naming ->
					plainOf(naming, home)?.let { naming to it }
				}.toMap()

		IgnoreRules(
			plain.values.toSet(),
			IgnoreRules.fileSystemsOn(Os.current),
			Seeking(ignored.filterNot { it in plain }.map { it.itself }, home),
		)
	}

	/** What a search leaves out of what it finds. */
	val hiding: Hiding by lazy {
		Hiding(Seeking(namings(Exclusion.Hidden).map { it.itself }, home))
	}

	/** How many rules are in effect, of each kind. */
	fun count(exclusion: Exclusion): Int = rules.count { it.on && it.exclusion == exclusion }

	companion object {
		/**
		 * The defaults every platform has, as they are written. Each is a tree that can hold more entries than
		 * everything a person made, and that nobody picks a file from: version control's history, package managers'
		 * downloads, build tools' output and caches, and browsers' caches, which are named the same wherever they are.
		 * Generic names that people also give their own folders (`build`, `target`, `dist`, `vendor`) are not among
		 * them.
		 */
		private val COMMON: List<String> =
			listOf(
				// Version control.
				".git/",
				".hg/",
				".svn/",
				".bzr/",
				".jj/",
				// Packages, as package managers download and unpack them.
				"node_modules/",
				"bower_components/",
				"jspm_packages/",
				".pnpm-store/",
				".yarn/cache/",
				".yarn/unplugged/",
				".npm/",
				".bun/install/cache/",
				".m2/",
				".ivy2/",
				".gradle/",
				".konan/",
				".cargo/",
				".rustup/",
				".nuget/",
				".dotnet/",
				"go/pkg/mod/",
				".cabal/packages/",
				".cabal/store/",
				".stack/",
				".ghcup/",
				".opam/",
				".pub-cache/",
				".julia/packages/",
				".julia/artifacts/",
				".conda/pkgs/",
				".vscode-server/",
				".vscode/extensions/",
				// Python's environments and caches.
				"__pycache__/",
				"__pypackages__/",
				".venv/",
				".tox/",
				".nox/",
				".eggs/",
				".mypy_cache/",
				".pytest_cache/",
				".ruff_cache/",
				".ipynb_checkpoints/",
				// Build tools' output and caches.
				".cache/",
				".kotlin/",
				".direnv/",
				".terraform/",
				".next/",
				".nuxt/",
				".svelte-kit/",
				".angular/",
				".turbo/",
				".parcel-cache/",
				".zig-cache/",
				"zig-cache/",
				".stack-work/",
				"dist-newstyle/",
				".bloop/",
				".metals/",
				".bsp/",
				".cpcache/",
				".shadow-cljs/",
				"elm-stuff/",
				".dart_tool/",
				".ccache/",
				".sccache/",
				"CMakeFiles/",
				".vagrant/",
				".serverless/",
				".aws-sam/",
				// Browsers' and Electron applications' caches.
				"Cache_Data/",
				"\"Code Cache/\"",
				"GPUCache/",
				"ShaderCache/",
				"GrShaderCache/",
				"DawnCache/",
				"DawnGraphiteCache/",
				"DawnWebGPUCache/",
				"CacheStorage/",
				"cache2/",
				// A Wine prefix's copy of Windows.
				"drive_c/windows/",
			)

		/**
		 * Linux's own: containers' images, file system snapshots and what fsck found, and the system's own stores,
		 * temporary files, and what is not files at all.
		 */
		private val LINUX: List<String> =
			listOf(
				".flatpak-builder/",
				".local/share/containers/",
				".local/share/flatpak/repo/",
				".zfs/",
				".snapshots/",
				"lost+found/",
				"/nix",
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
			)

		/**
		 * macOS's own. The Library's folders that are applications' own data, caches and records, in the user's
		 * Library and the computer's alike (each named by how its path ends, which is both); what is inside an
		 * application's or a framework's bundle, which is the application's and not a file anyone picks, the bundle
		 * itself still being found; what the system keeps on every volume for itself (Spotlight's index, FSEvents'
		 * record, versions, the trash); Xcode's and its package managers' output; and the system's own trees. iCloud
		 * Drive (`~/Library/Mobile Documents`) and other clouds' folders (`~/Library/CloudStorage`) are a person's
		 * files, and are not among them.
		 */
		private val MACOS: List<String> =
			listOf(
				// Applications' own data, caches and records.
				"\"Library/Application Support/\"",
				"\"Library/Application Scripts/\"",
				"\"Library/Saved Application State/\"",
				"\"Library/Group Containers/\"",
				"\"Library/Daemon Containers/\"",
				"\"Library/Autosave Information/\"",
				"Library/Containers/",
				"Library/Caches/",
				"Library/Logs/",
				"Library/Developer/",
				"Library/Metadata/",
				"Library/Preferences/",
				"Library/Mail/",
				"Library/Messages/",
				"Library/Safari/",
				"Library/Calendars/",
				"Library/Cookies/",
				"Library/HTTPStorages/",
				"Library/WebKit/",
				"Library/Keychains/",
				"Library/Biome/",
				"Library/Suggestions/",
				"Library/Assistant/",
				"Library/IdentityServices/",
				"Library/Trial/",
				"Library/DuetExpertCenter/",
				// What is inside a bundle (an application, a framework, a plug-in).
				"Contents/MacOS/",
				"Contents/Resources/",
				"Contents/Frameworks/",
				"Contents/PlugIns/",
				"Contents/Library/",
				"Contents/Helpers/",
				"Contents/XPCServices/",
				"Contents/SharedSupport/",
				"Contents/_CodeSignature/",
				"Versions/A/",
				// What the system keeps on a volume for itself.
				".Spotlight-V100/",
				".fseventsd/",
				".DocumentRevisions-V100/",
				".TemporaryItems/",
				".MobileBackups/",
				"Backups.backupdb/",
				".Trashes/",
				".Trash/",
				// Xcode's and its package managers' output.
				"DerivedData/",
				".build/",
				".swiftpm/",
				"Pods/",
				"Carthage/Build/",
				// The system's own trees, package managers' stores, and temporary files.
				"/System",
				"/private/var",
				"/private/tmp",
				"/cores",
				"/dev",
				"/nix",
				"/opt/homebrew",
				"/usr/local/Cellar",
				"/usr/local/Homebrew",
			)

		/**
		 * The defaults on [os], as they are written: those every platform has, then its own. One platform's are not
		 * another's: a Mac is not told of `/proc`, nor Linux of `Library/Caches`.
		 */
		fun defaultsOn(os: Os): List<String> =
			COMMON +
				when (os) {
					Os.Linux -> LINUX + linuxTrashes()
					Os.MacOS -> MACOS
					// Windows' own are to be written with the rest of Windows.
					Os.Windows -> emptyList()
				}

		/**
		 * The trashes of a Linux desktop, which are found by nobody looking for a file: the home trash, the one an
		 * administrator makes at a drive's top for every user, and the user's own at a drive's top, which is named
		 * with their number (`.Trash-1000`).
		 */
		private fun linuxTrashes(): List<String> =
			listOf(".local/share/Trash/", ".Trash/") +
				listOfNotNull(
					try {
						".Trash-${java.nio.file.Files.getAttribute(
							Path.of(System.getProperty("user.home")),
							"unix:uid",
						)}/"
					} catch (unknown: Exception) {
						null
					},
				)

		/** The defaults of the platform this runs on. */
		val DEFAULTS: List<String> = defaultsOn(Os.current)

		/** The rules with nothing of the user's: prism's own, every one on, and not indexed. */
		val DEFAULT: Exclusions by lazy { of(home = Path.of(System.getProperty("user.home"))) }

		/** No rule at all. */
		fun none(home: Path = Path.of("/")): Exclusions = Exclusions(emptyList(), home)

		/**
		 * The rules as the preferences keep them: the defaults but for those switched [off] and those [changed] to
		 * another kind, and the user's [own], each with its kind, in the order they were added. One of the user's
		 * that does not read as a rule, or that says what a rule before it says, is not among them ([unfit] says
		 * why, for whoever shows them).
		 */
		fun of(
			off: Set<String> = emptySet(),
			changed: Map<String, Exclusion> = emptyMap(),
			own: Map<String, Exclusion> = emptyMap(),
			home: Path,
			defaults: List<String> = DEFAULTS,
		): Exclusions {
			val builtIn =
				defaults.map { written ->
					val naming = (Naming.read(written, home) as Naming.Read.Fit).naming

					Rule(
						written,
						naming,
						changed[written] ?: Exclusion.Ignored,
						builtIn = true,
						on = written !in off,
					)
				}

			val user =
				own.entries.fold(emptyList<Rule>()) { rules, (written, exclusion) ->
					val naming = (Naming.read(written, home) as? Naming.Read.Fit)?.naming

					if (naming == null || (builtIn + rules).any { it.names == naming })
						rules
					else
						rules + Rule(written.trim(), naming, exclusion, builtIn = false, on = true)
				}

			return Exclusions(builtIn + user, home)
		}

		/**
		 * Why [written] cannot be added to [rules] as one more of the user's; null where it can: it reads as a rule,
		 * and no rule there names the same.
		 */
		fun unfit(written: String, rules: List<Naming>, home: Path): String? =
			when (val read = Naming.read(written, home)) {
				is Naming.Read.Unfit -> read.why
				is Naming.Read.Fit ->
					"There is already a rule for this".takeIf { read.naming in rules }
			}

		/**
		 * [naming] as a folder a scan does not go into, where it is one: the folder at a path, or every folder whose
		 * path ends with some names, each said whole and as it is spelled.
		 */
		private fun plainOf(naming: Naming, home: Path): Ignored? =
			when (naming) {
				is Naming.Folder -> Ignored.At(naming.path)
				is Naming.Sought -> {
					val term =
						naming.query.terms
							.singleOrNull()
							?.takeUnless { it.negated }
					val test = (term?.test as? Test.InPath)?.takeIf { it.case != Case.Insensitive }
					val pattern = test?.pattern?.takeIf { it.last == PathPattern.Last.Under }

					val names =
						pattern?.segments?.map {
							((it as? Segment.Named)?.pattern as? TextPattern.Literal)?.text
						}

					if (names == null || names.isEmpty() ||
						names.any { it == null || it == "." || it == ".." }
					)
						null
					else
						when (pattern.base) {
							PathBase.Relative -> Ignored.Anywhere(names.filterNotNull().map(Name::of))
							PathBase.Root ->
								Ignored.At(Path.of("/" + names.joinToString("/")))
							PathBase.Home ->
								Ignored.At(home.resolve(names.joinToString("/")).normalize())
							is PathBase.Alias -> null
						}
				}
			}
	}
}
