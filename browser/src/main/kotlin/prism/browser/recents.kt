package prism.browser

import androidx.compose.runtime.Immutable
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import prism.preferences.RecentSection
import prism.shared.Format
import prism.shared.Guarded
import prism.shared.Stored
import prism.shared.Uses

/*
 * What is recent: the folders and the files pickers were lately answered with, for every application together and
 * for each one apart; how much each folder is gone to; and the files the index lately saw made. The first three are
 * state of prism's own, each kept in a file beside what else it keeps (`$XDG_STATE_HOME/prism/recent-folders`,
 * `recent-files`, `folder-accesses`); the new files are asked of the index each time, and kept nowhere, so a file
 * made and deleted since is simply not among them the next time they are asked for.
 *
 * The Recents page ([RecentPage]) shows them all, and a picker whose application names no folder opens where that
 * application was last answered from ([Recents.start]).
 */

/** An application that asks for pickers, by the id the desktop portal knows it by. Never blank: one unknown is none. */
@JvmInline
value class AppKey private constructor(
	val id: String,
) {
	override fun toString(): String = id

	companion object {
		/** The application [id] names; none, where it names nothing (as the portal says of one it cannot tell). */
		fun of(id: String?): AppKey? =
			id?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it }?.let(::AppKey)
	}
}

/** A file the index saw made, and when it did. */
@Immutable
data class NewFile(
	val path: Path,
	val at: Instant,
)

/** Something a picker was answered with (a file, or a folder), and when it last was. */
@Immutable
data class Recent(
	val path: Path,
	val at: Instant,
)

/**
 * The files, or the folders, last used, the latest first: each once, and no more than [MOST]. Made only by [of],
 * which keeps it so whatever it is given, so a use ([used]) lets the oldest go where there is no room, and two lists
 * read at once are one ([plus]).
 */
@Immutable
class RecentList private constructor(
	val entries: List<Recent>,
) {
	val paths: List<Path> get() = entries.map { it.path }

	/** These, with [paths] used at [at]: each first among them, in the order given. */
	fun used(paths: List<Path>, at: Instant): RecentList =
		of(paths.map { Recent(it, at) } + entries)

	/** These and [other], each as it was last used in either. */
	operator fun plus(other: RecentList): RecentList = of(entries + other.entries)

	/** These, without what [gone] says is not there any more. */
	fun without(gone: (Path) -> Boolean): RecentList =
		RecentList(entries.filterNot { gone(it.path) })

	override fun equals(other: Any?): Boolean =
		other is RecentList && other.entries == entries

	override fun hashCode(): Int = entries.hashCode()

	override fun toString(): String = "RecentList($entries)"

	companion object {
		/** How many a list keeps. */
		const val MOST = 10

		val NONE = RecentList(emptyList())

		/** [entries] as a list: the latest use of each, the latest first, the first [MOST] of them. */
		fun of(entries: List<Recent>): RecentList =
			RecentList(
				entries
					.sortedByDescending { it.at }
					.distinctBy { it.path }
					.take(MOST),
			)
	}
}

/**
 * What pickers were answered with, of one kind (the files, or the folders): by any application ([all]), and by each
 * that could be told apart ([apps]). A use by an application is a use in both; one by nobody that can be told is in
 * [all] alone.
 */
@Immutable
data class Recently(
	val all: RecentList = RecentList.NONE,
	val apps: Map<AppKey, RecentList> = emptyMap(),
) {
	/** What [app] was answered with; nothing, for nobody. */
	fun of(app: AppKey?): RecentList = app?.let(apps::get) ?: RecentList.NONE

	/** These, with [paths] used by [app] at [at]. */
	fun used(paths: List<Path>, app: AppKey?, at: Instant): Recently =
		if (paths.isEmpty())
			this
		else
			Recently(
				all.used(paths, at),
				app?.let { capped(apps + (it to of(it).used(paths, at))) } ?: apps,
			)

	/** These, without what [gone] says is not there any more. */
	fun without(gone: (Path) -> Boolean): Recently =
		Recently(all.without(gone), apps.mapValues { it.value.without(gone) })

	companion object {
		/** How many applications' lists are kept: those used latest. */
		const val MOST_APPS = 200

		private fun capped(apps: Map<AppKey, RecentList>): Map<AppKey, RecentList> =
			if (apps.size <= MOST_APPS)
				apps
			else
				apps.entries
					.sortedByDescending {
						it.value.entries
							.firstOrNull()
							?.at ?: Instant.MIN
					}.take(MOST_APPS)
					.associate { it.key to it.value }
	}
}

/**
 * Everything kept of what is recent: the [folders] folder pickers were answered with, the [files] file pickers were
 * (to open, or to save), and how much each folder has been gone to or picked from ([accesses]). A file picked is not
 * its folder picked: its folder is only gone to.
 */
@Immutable
data class Recents(
	val folders: Recently = Recently(),
	val files: Recently = Recently(),
	val accesses: Uses<Path> = Uses(),
) {
	/**
	 * Where a picker for [app] opens when it is not told where. One that picks files ([picksFiles]): in the folder
	 * of the file that application was last answered with, and failing that the folder it last was; then the same
	 * of any application's. A folder picker goes by the folders alone. Only a folder that is still [there]; none,
	 * where there is none.
	 */
	fun start(app: AppKey?, picksFiles: Boolean, there: (Path) -> Boolean): Path? {
		fun of(who: (Recently) -> RecentList): Path? =
			(
				who(files)
					.paths
					.takeIf { picksFiles }
					.orEmpty()
					.mapNotNull { it.parent } + who(folders).paths
			).firstOrNull(there)

		return app?.let { known -> of { it.of(known) } } ?: of { it.all }
	}
}

/**
 * Where what is recent is kept: the folders and the files each a value kept in a file ([Stored]) of its own, and the
 * folders' accesses another; or all in memory alone. Read as it is wanted and added to as a picker is answered or a
 * folder gone to. Each use is added to the file as it then is, so that every prism of the session (the daemon's
 * pickers, and a picker run on its own) keeps the others' uses.
 *
 * A folder is gone to far more often than a picker is answered, and nothing waits to know it was: accesses are held
 * as they are told ([went]) and written together, no oftener than every [WRITE_EVERY], and when asked ([flush]).
 */
class RecentStore private constructor(
	private val folders: Stored<Recently>,
	private val files: Stored<Recently>,
	private val accesses: Stored<Uses<Path>>,
	private val folderThere: (Path) -> Boolean,
	private val fileThere: (Path) -> Boolean,
) {
	/** The accesses told and not yet written, and when they were last written. */
	private class Waiting(
		var told: List<Pair<Path, Instant>> = emptyList(),
		var written: Instant = Instant.MIN,
	)

	private val waiting = Guarded(Waiting())

	/** What is recent as it is now, with the accesses told and not yet written. */
	fun read(): Recents =
		Recents(
			folders.value.value,
			files.value.value,
			waiting.guarded {
				told.fold(accesses.value.value) { uses, (folder, at) -> uses.used(folder, at) }
			},
		)

	/**
	 * A picker that was [choosing], for [app], was answered with [chosen] at [at]: a folder picker's answer is the
	 * latest of the recent folders, a file picker's the latest of the recent files; and the folder each was picked
	 * from (a folder picked, itself) was gone to. Exploring answers nobody.
	 */
	fun picked(chosen: List<Path>, choosing: Choosing, app: AppKey?, at: Instant) {
		when (choosing) {
			is Choosing.Folder -> {
				folders.update { used(chosen, app, at).without { !folderThere(it) } }
				chosen.forEach { went(it, at) }
			}
			is Choosing.OneFile, is Choosing.Files, is Choosing.SaveAs -> {
				files.update { used(chosen, app, at).without { !fileThere(it) } }
				chosen.mapNotNull { it.parent }.distinct().forEach { went(it, at) }
			}
			is Choosing.Exploring -> Unit
		}
	}

	/** [folder] was gone to, or picked from, at [at]: held, and written with the rest when it is time. */
	fun went(folder: Path, at: Instant) {
		val due =
			waiting.guarded {
				told = told + (folder to at)
				written == Instant.MIN || Duration.between(written, at) >= WRITE_EVERY
			}

		if (due)
			flush(at)
	}

	/**
	 * The accesses told so far written, as of [at]: all but [MOST_ACCESSED] are let go ([Uses.capped]). Written
	 * while what was told is still held, so that whoever reads meanwhile ([read]) finds each access once: told, or
	 * written, and never neither.
	 */
	fun flush(at: Instant) {
		waiting.guarded {
			if (told.isNotEmpty())
				accesses.update {
					told
						.fold(this) { uses, (folder, at) -> uses.used(folder, at) }
						.capped(MOST_ACCESSED, at)
				}
			told = emptyList()
			written = at
		}
	}

	companion object {
		/** How many folders' accesses are kept: far more than the page shows, so that one can come to be shown. */
		const val MOST_ACCESSED = 500

		/** How long accesses are held before they are written. */
		val WRITE_EVERY: Duration = Duration.ofSeconds(5)

		/** What is recent kept in memory alone: a browser run without files of it, and the tests. */
		fun inMemory(first: Recents = Recents()): RecentStore =
			RecentStore(
				Stored.inMemory(first.folders),
				Stored.inMemory(first.files),
				Stored.inMemory(first.accesses),
				{ true },
				{ true },
			)

		/**
		 * What is recent kept in [folder]: the folders in `recent-folders`, the files in `recent-files`
		 * ([RecentFormat]) and the accesses in `folder-accesses` ([AccessFormat]). A file that cannot be read is
		 * nothing recent; one that cannot be written is said to [report], and the use is kept for as long as this
		 * prism runs.
		 */
		fun inFolder(
			folder: Path,
			report: (String, Throwable) -> Unit = { _, _ -> },
		): RecentStore {
			fun <T> kept(name: String, format: Format<T>, what: String): Stored<T> =
				folder.resolve(name).let { file ->
					Stored.of(file, format) { report("keeping $what in $file", it) }
				}

			return RecentStore(
				kept("recent-folders", RecentFormat, "the folders lately picked"),
				kept("recent-files", RecentFormat, "the files lately picked"),
				kept("folder-accesses", AccessFormat, "how much each folder is used"),
				Files::isDirectory,
				Files::exists,
			)
		}
	}
}

/** What is recent kept in memory alone. */
@Suppress("FunctionName")
fun MemoryRecents(first: Recents = Recents()): RecentStore = RecentStore.inMemory(first)

/** What is recent kept in files of [folder]. */
@Suppress("FunctionName")
fun RecentsFiles(
	folder: Path,
	report: (String, Throwable) -> Unit = { _, _ -> },
): RecentStore = RecentStore.inFolder(folder, report)

/**
 * Recent files, or folders, as a file: a line a use, the millisecond it was at, the application it was by (nothing,
 * for the list of every application's), and the path, between tabs. A line that does not say a use is left out.
 */
val RecentFormat: Format<Recently> =
	Format.lines(
		Recently(),
		read = { lines ->
			val uses =
				lines.mapNotNull { line ->
					val parts = line.split('\t', limit = 3)
					val at = parts.getOrNull(0)?.toLongOrNull()
					val path = parts.getOrNull(2)?.let(::pathRead)

					if (at == null || path == null)
						null
					else
						AppKey.of(parts[1]) to Recent(path, Instant.ofEpochMilli(at))
				}

			Recently(
				RecentList.of(uses.filter { it.first == null }.map { it.second }),
				uses
					.mapNotNull { (app, recent) -> app?.let { it to recent } }
					.groupBy({ it.first }, { it.second })
					.mapValues { RecentList.of(it.value) },
			)
		},
		written = { kept ->
			fun lines(app: AppKey?, list: RecentList): List<String> =
				list.entries.map {
					"${it.at.toEpochMilli()}\t${app?.id.orEmpty()}\t${escaped(it.path.toString())}"
				}

			lines(null, kept.all) + kept.apps.flatMap { (app, list) -> lines(app, list) }
		},
	)

/** How much each folder is used, as a file ([Uses.format]), each by its path. */
val AccessFormat: Format<Uses<Path>> = Uses.format(::pathRead) { escaped(it.toString()) }

/** The absolute path a line's [text] says, as [escaped] wrote it; none, where it says none. */
private fun pathRead(text: String): Path? =
	text.takeIf { it.startsWith("/") }?.let {
		try {
			Path.of(unescaped(it))
		} catch (unspellable: InvalidPathException) {
			null
		}
	}

// A path can hold a new line, which would end its line here: written as two characters.
private fun escaped(path: String): String =
	path.replace("\\", "\\\\").replace("\n", "\\n")

private fun unescaped(text: String): String =
	Regex("""\\(.)""").replace(text) {
		if (it.groupValues[1] == "n")
			"\n"
		else
			it.groupValues[1]
	}

/**
 * What of the Recents page an arrangement leaves out, and puts away: the sections [folded] to their headings, and
 * the paths the user has [hidden] from it, which are left out of every section unless they are asked to be shown
 * all the same ([showsHidden]). Nothing, of any other listing.
 */
@Immutable
data class RecentArranged(
	val folded: Set<RecentSection> = emptySet(),
	val hidden: Set<Path> = emptySet(),
	val showsHidden: Boolean = false,
) {
	companion object {
		val NONE = RecentArranged()
	}
}

/**
 * The Recents page as it was [read]: its [sections], each with something in it, in the order they are shown. Its
 * rows are the listing's ([rows]), so they are chosen, opened and dragged as any folder's are; what it adds to each
 * is which section it is in, when it was made or used, and whether it is long before its section's newest ([marks]).
 */
@Immutable
class RecentPage(
	val sections: List<Section>,
	val read: Instant,
) {
	/** What a section holds: which of the page's it is, whoever the page is for. */
	enum class Kind(
		val title: String,
		/** What the user collapses, and what is kept of that. */
		val section: RecentSection,
		/** What its entries are, as what folds it says: "Hide files". */
		val holds: String,
		/** Whether its entries are in the order they were given in, and none of them long before another. */
		val ranked: Boolean = false,
	) {
		/** The files the index saw made last. */
		NewFiles("New files", RecentSection.NewFiles, "files"),

		/** The files the application that asked was last answered with. */
		ApplicationFiles(
			"Recent files for this application",
			RecentSection.ApplicationFiles,
			"files",
		),

		/** The files any application was last answered with. */
		Files("Recent files", RecentSection.Files, "files"),

		/** The folders the application that asked was last answered with. */
		Application(
			"Recent folders for this application",
			RecentSection.Application,
			"folders",
		),

		/** The folders any application was last answered with. */
		Folders("Recent folders", RecentSection.Folders, "folders"),

		/** The folders gone to and picked from most, the most first. */
		Frequent("Frequently accessed", RecentSection.Frequent, "folders", ranked = true),
	}

	/**
	 * A section's heading, as the listing draws it: how many entries the section has to show ([count]), whether
	 * they are put away under it ([collapsed]), and the row it is drawn over ([before], counting the rows shown
	 * from the top): its own first row, open; the next section's first, collapsed; and where there is no row after
	 * it, the count of the rows, which is past the last.
	 */
	@Immutable
	data class Heading(
		val kind: Kind,
		val count: Int,
		val collapsed: Boolean,
		val before: Int,
	)

	/**
	 * What the page shows, arranged: the [rows] of its open sections, in order, and every section's heading
	 * ([headings]), each where it is drawn among them. A collapsed section's entries are no rows of the listing, so
	 * nothing that goes by its rows (the cursor, what is chosen, a name typed, their count) knows of them. The rows
	 * that are hidden from the page and shown all the same are [dimmed].
	 */
	@Immutable
	class Shown(
		val rows: Rows,
		val headings: List<Heading>,
		val dimmed: Set<RowId>,
	) {
		/** The headings drawn over each row, by the row's place; over nothing, those after the last row. */
		val over: Map<Int, List<Heading>> = headings.groupBy { it.before }

		/** How many entries the open sections show. */
		val listed: Int get() = headings.filterNot { it.collapsed }.sumOf { it.count }
	}

	/**
	 * The page as [arrangement] shows it: of each section, the entries it admits, without those hidden from the
	 * page (unless it shows them); a section with none is left out, heading and all, and one it folds is its
	 * heading alone.
	 */
	fun shown(arrangement: Arrangement): Shown {
		val recent = arrangement.recent
		val rows = ArrayList<Row>()
		val headings = ArrayList<Heading>()
		val dimmed = HashSet<RowId>()

		for (section in sections) {
			val admitted =
				section.entries
					.map { it.row }
					.filter { row ->
						arrangement.admits(row) && (recent.showsHidden || row.path !in recent.hidden)
					}
			val collapsed = section.kind.section in recent.folded

			if (admitted.isNotEmpty()) {
				headings += Heading(section.kind, admitted.size, collapsed, before = rows.size)
				if (!collapsed) {
					rows += admitted
					admitted.filter { it.path in recent.hidden }.mapTo(dimmed) { it.id }
				}
			}
		}

		return Shown(Rows(rows), headings, dimmed)
	}

	/** Whether anything on the page is among [hidden]: whether there is anything to show that is not shown. */
	fun hides(hidden: Set<Path>): Boolean =
		hidden.isNotEmpty() && rows.any { it.path in hidden }

	/** A row of the page: when it was made (a file) or last used (a folder), and whether that is [stale]. */
	@Immutable
	data class Entry(
		val row: Row,
		val at: Instant,
		/** Long before the newest of its section ([STALE_AFTER]): drawn quieter. */
		val stale: Boolean,
	)

	/** One section: never empty, its entries the newest first, or as they were given ([Kind.ranked]). Made by [of]. */
	@Immutable
	class Section private constructor(
		val kind: Kind,
		val entries: List<Entry>,
	) {
		companion object {
			/**
			 * The section of [kind] holding [dated], each a row of that section's own ([RowId.Recent]); none, where
			 * there is nothing to hold.
			 */
			fun of(kind: Kind, dated: List<Pair<Row, Instant>>): Section? {
				val ordered =
					if (kind.ranked)
						dated
					else
						dated.sortedByDescending { it.second }
				val newest = ordered.maxOfOrNull { it.second } ?: return null
				val fresh = newest.minus(STALE_AFTER)

				return Section(
					kind,
					ordered.map { (row, at) ->
						Entry(
							row.path?.let { row.copy(id = RowId.Recent(it, kind.section)) } ?: row,
							at,
							stale = !kind.ranked && at < fresh,
						)
					},
				)
			}
		}
	}

	/** What the page says of one of its rows. */
	@Immutable
	data class Mark(
		val section: Kind,
		val at: Instant,
		val stale: Boolean,
	)

	/** Every row, section after section. */
	val rows: List<Row> = sections.flatMap { section -> section.entries.map { it.row } }

	/** What is said of each row, by its identity. */
	val marks: Map<RowId, Mark> =
		sections
			.flatMap { section ->
				section.entries.map { it.row.id to Mark(section.kind, it.at, it.stale) }
			}.toMap()

	override fun toString(): String =
		"RecentPage(${sections.joinToString { "${it.kind.title}: ${it.entries.size}" }})"

	companion object {
		/** How many of the newest files are asked of the index: enough that those since deleted leave ten. */
		const val NEW_KEPT = 100

		/** How many new files the page shows: the newest that are still there. */
		const val NEW_SHOWN = 10

		/** How many of the folders used most the page shows. */
		const val FREQUENT_SHOWN = 10

		/** How much older than its section's newest an entry is before it is drawn quieter. */
		val STALE_AFTER: Duration = Duration.ofDays(10)

		/**
		 * The page of the [newest] files (the newest first, as the index saw them made) and what is [recent], for
		 * [asker] where the picker is some application's, as [row] reads each on disk: what it does not find is not
		 * shown. In order: the newest [NEW_SHOWN] files that are there; the files [asker] was answered with; the
		 * files anybody was, without those already shown as the application's; the folders, the same two ways; and
		 * the [FREQUENT_SHOWN] folders used most as of [now]. A page that shows no [files] (a folder picker's) has
		 * the folders' sections alone.
		 */
		fun of(
			newest: List<NewFile>,
			recent: Recents,
			asker: AppKey?,
			now: Instant,
			files: Boolean = true,
			row: (Path) -> Row?,
		): RecentPage {
			fun dated(used: List<Recent>, folders: Boolean): List<Pair<Row, Instant>> =
				used.mapNotNull { entry ->
					row(entry.path)?.takeIf { it.isFolder == folders }?.let { it to entry.at }
				}

			/** The application's of [kept], and everyone's without those: none of either, where there is no [asker]. */
			fun split(kept: Recently): Pair<List<Recent>, List<Recent>> {
				val own = kept.of(asker).entries

				return own to kept.all.entries.filterNot { used -> own.any { it.path == used.path } }
			}

			val made =
				newest
					.asSequence()
					.mapNotNull { made ->
						row(made.path)?.takeUnless { it.isFolder }?.let { it to made.at }
					}.take(NEW_SHOWN)
					.toList()

			val (ownFiles, otherFiles) = split(recent.files)
			val (ownFolders, otherFolders) = split(recent.folders)

			val frequent =
				recent.accesses
					.most(FREQUENT_SHOWN, now) { row(it)?.isFolder == true }
					.mapNotNull { (folder, used) -> row(folder)?.let { it to used.at } }

			return RecentPage(
				listOfNotNull(
					Section.of(Kind.NewFiles, made).takeIf { files },
					Section.of(Kind.ApplicationFiles, dated(ownFiles, folders = false)).takeIf { files },
					Section.of(Kind.Files, dated(otherFiles, folders = false)).takeIf { files },
					Section.of(Kind.Application, dated(ownFolders, folders = true)),
					Section.of(Kind.Folders, dated(otherFolders, folders = true)),
					Section.of(Kind.Frequent, frequent),
				),
				now,
			)
		}
	}
}

/** [path] as a row of the Recents page, as it is on disk now; none, where it is not there. */
internal fun recentRowOf(path: Path): Row? = rowOf(path)?.copy(hidden = false)

/**
 * The Recents page for [asking], read from these: the newest files the index knows that are still there, and what
 * is recent. The index can be a moment behind the disk, and what it lists newest can since have been deleted
 * (programs write a file where a person would see it, and take it away again): so [RecentPage.NEW_KEPT] are asked
 * for, to show the first [RecentPage.NEW_SHOWN] of them that are there, and, where too few of them are, as many
 * again, until there are enough or the index has no more.
 */
internal fun Services.recentPage(asking: Effect.ReadRecents): RecentPage {
	val seen = HashMap<Path, Row?>()
	// Each path read once, whether it is there or not, however many times it is asked for.
	val row = { path: Path ->
		if (path in seen)
			seen[path]
		else
			recentRowOf(path).also { seen[path] = it }
	}

	tailrec fun newest(count: Int): List<NewFile> {
		val found =
			asking.files
				?.let { filter -> finder?.newest(count, filter::admits) }
				.orEmpty()
				.mapNotNull { seen ->
					seen.entry.where.path
						?.let { NewFile(it, seen.at) }
				}
		val there = found.count { row(it.path) != null }

		return if (there >= RecentPage.NEW_SHOWN || found.size < count || count >= NEW_MOST)
			found
		else
			newest(count * 2)
	}

	return RecentPage.of(
		newest(RecentPage.NEW_KEPT),
		recents.read(),
		asking.asker,
		clock(),
		files = asking.files != null,
		row,
	)
}

/** The most of the newest files ever asked of the index for one page. */
private const val NEW_MOST = RecentPage.NEW_KEPT * 64

/**
 * How long ago [at] was, as of [now], in a few words: "Just now", "5 minutes ago", "Yesterday", "12 days ago", and
 * a date for anything a month old and more.
 */
fun agoOf(
	at: Instant,
	now: Instant,
	zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String {
	val since = Duration.between(at, now)
	val days =
		java.time.temporal.ChronoUnit.DAYS
			.between(at.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())

	fun counted(count: Long, unit: String): String =
		if (count == 1L)
			"1 $unit ago"
		else
			"$count ${unit}s ago"

	return when {
		since < Duration.ofMinutes(1) -> "Just now"
		since < Duration.ofHours(1) -> counted(since.toMinutes(), "minute")
		// Late last night is still hours ago, early this morning.
		days == 0L || since < Duration.ofHours(6) -> counted(since.toHours(), "hour")
		days == 1L -> "Yesterday"
		days < 30 -> "$days days ago"
		else ->
			java.time.format.DateTimeFormatter
				.ofPattern(
					if (at.atZone(zone).year == now.atZone(zone).year)
						"d MMM"
					else
						"d MMM yyyy",
					java.util.Locale.ENGLISH,
				).format(at.atZone(zone))
	}
}
