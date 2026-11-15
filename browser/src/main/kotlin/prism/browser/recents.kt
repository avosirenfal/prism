package prism.browser

import androidx.compose.runtime.Immutable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import prism.linux.WholeFile
import prism.preferences.RecentSection

/*
 * What is recent: the folders pickers were lately answered from, for every application together and for each one
 * apart, and the files the index lately saw made. The folders are state of prism's own, kept in a file beside what
 * else it keeps (`$XDG_STATE_HOME/prism/recent-folders`); the files are asked of the index each time, and kept
 * nowhere, so a file made and deleted since is simply not among them the next time they are asked for.
 *
 * The Recents page ([RecentPage]) shows both, and a picker whose application names no folder opens in the folder that
 * application was last answered from ([RecentFolders.last]).
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

/** A folder a picker was answered from, and when it last was. */
@Immutable
data class Recent(
	val folder: Path,
	val at: Instant,
)

/**
 * The folders last used, the latest first: each once, and no more than [MOST]. Made only by [of], which keeps it so
 * whatever it is given, so a use ([used]) lets the oldest go where there is no room, and two lists read at once are
 * one ([plus]).
 */
@Immutable
class RecentList private constructor(
	val entries: List<Recent>,
) {
	val folders: List<Path> get() = entries.map { it.folder }

	/** These, with [folders] used at [at]: each first among them, in the order given. */
	fun used(folders: List<Path>, at: Instant): RecentList =
		of(folders.map { Recent(it, at) } + entries)

	/** These and [other], each folder as it was last used in either. */
	operator fun plus(other: RecentList): RecentList = of(entries + other.entries)

	/** These, without the folders [gone] says are not there any more. */
	fun without(gone: (Path) -> Boolean): RecentList =
		RecentList(entries.filterNot { gone(it.folder) })

	override fun equals(other: Any?): Boolean =
		other is RecentList && other.entries == entries

	override fun hashCode(): Int = entries.hashCode()

	override fun toString(): String = "RecentList($entries)"

	companion object {
		/** How many folders a list keeps. */
		const val MOST = 10

		val NONE = RecentList(emptyList())

		/** [entries] as a list: the latest use of each folder, the latest first, the first [MOST] of them. */
		fun of(entries: List<Recent>): RecentList =
			RecentList(
				entries
					.sortedByDescending { it.at }
					.distinctBy { it.folder }
					.take(MOST),
			)
	}
}

/**
 * The folders pickers were answered from: by any application ([all]), and by each that could be told apart
 * ([apps]). A use by an application is a use in both; one by nobody that can be told is in [all] alone.
 */
@Immutable
data class RecentFolders(
	val all: RecentList = RecentList.NONE,
	val apps: Map<AppKey, RecentList> = emptyMap(),
) {
	/** The folders [app] was answered from; none, for nobody. */
	fun of(app: AppKey?): RecentList = app?.let(apps::get) ?: RecentList.NONE

	/** These, with [folders] used by [app] at [at]. */
	fun used(folders: List<Path>, app: AppKey?, at: Instant): RecentFolders =
		if (folders.isEmpty())
			this
		else
			RecentFolders(
				all.used(folders, at),
				app?.let { capped(apps + (it to of(it).used(folders, at))) } ?: apps,
			)

	/**
	 * Where a picker for [app] opens when it is not told where: the folder that application was last answered from
	 * that is still [there]; for nobody that can be told, the one any was. None, where there is none.
	 */
	fun last(app: AppKey?, there: (Path) -> Boolean): Path? =
		(app?.let(::of) ?: all).folders.firstOrNull(there)

	/** These, without the folders [gone] says are not there any more. */
	fun without(gone: (Path) -> Boolean): RecentFolders =
		RecentFolders(all.without(gone), apps.mapValues { it.value.without(gone) })

	companion object {
		/** How many applications' folders are kept: those used latest. */
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

/** Where the recent folders are kept: read as they are wanted, and added to as a picker is answered. */
interface RecentStore {
	/** The folders as they are now, whoever wrote them last. */
	fun read(): RecentFolders

	/** [folders] were what a picker for [app] was answered from, at [at]. */
	fun used(folders: List<Path>, app: AppKey?, at: Instant)
}

/** Recent folders kept in memory alone: a browser run without a file of them, and the tests. */
class MemoryRecents(
	first: RecentFolders = RecentFolders(),
) : RecentStore {
	private val held = AtomicReference(first)

	override fun read(): RecentFolders = held.get()

	override fun used(folders: List<Path>, app: AppKey?, at: Instant) {
		held.updateAndGet { it.used(folders, app, at) }
	}
}

/**
 * Recent folders kept in [file]: a line a use, the millisecond it was at, the application it was by (nothing, for the
 * list of every application's), and the folder, between tabs. Every prism of the session writes the one file (the
 * daemon's pickers, and a picker run on its own), so it is read each time it is asked for, and a use is added to what
 * the file holds then, under a lock on a file beside it, and written whole, beside it and renamed over: no writer
 * loses another's use, and no reader finds half a file. Folders that are gone are left out of what is written.
 *
 * A file that cannot be read is no folders; one that cannot be written loses that use, and says so to [report].
 */
class RecentsFile(
	private val file: Path,
	private val report: (String, Throwable) -> Unit = { _, _ -> },
) : RecentStore {
	override fun read(): RecentFolders = read(file)

	override fun used(folders: List<Path>, app: AppKey?, at: Instant) {
		try {
			// One writer at a time, here and among processes: what is added to is the file as it is now.
			WholeFile.locked(file) {
				write(file, read(file).used(folders, app, at).without { !Files.isDirectory(it) })
			}
		} catch (failed: IOException) {
			report("keeping the folders lately used in $file", failed)
		}
	}

	companion object {
		fun read(file: Path): RecentFolders =
			try {
				val uses =
					Files.readAllLines(file).mapNotNull { line ->
						val parts = line.split('\t', limit = 3)
						val at = parts.getOrNull(0)?.toLongOrNull()
						val folder = parts.getOrNull(2)?.takeIf { it.startsWith("/") }

						if (at == null || folder == null)
							null
						else
							AppKey.of(parts[1]) to Recent(Path.of(unescaped(folder)), Instant.ofEpochMilli(at))
					}

				RecentFolders(
					RecentList.of(uses.filter { it.first == null }.map { it.second }),
					uses
						.mapNotNull { (app, recent) -> app?.let { it to recent } }
						.groupBy({ it.first }, { it.second })
						.mapValues { RecentList.of(it.value) },
				)
			} catch (missing: IOException) {
				RecentFolders()
			} catch (unspellable: java.nio.file.InvalidPathException) {
				RecentFolders()
			}

		/** [folders] written to [file] whole ([WholeFile]). */
		fun write(file: Path, folders: RecentFolders) {
			fun lines(app: AppKey?, list: RecentList): List<String> =
				list.entries.map {
					"${it.at.toEpochMilli()}\t${app?.id.orEmpty()}\t${escaped(it.folder.toString())}"
				}

			WholeFile.replace(
				file,
				lines(null, folders.all) + folders.apps.flatMap { (app, list) -> lines(app, list) },
			)
		}

		// A folder's name can hold a new line, which would end its line here: written as two characters.
		private fun escaped(path: String): String =
			path.replace("\\", "\\\\").replace("\n", "\\n")

		private fun unescaped(text: String): String =
			Regex("""\\(.)""").replace(text) {
				if (it.groupValues[1] ==
					"n"
				)
					"\n"
				else
					it.groupValues[1]
			}
	}
}

/**
 * The folders [chosen] were picked from, as a picker that is [choosing] was answered with them: a folder picker's
 * answer is the folder itself; anything else's is each file's folder, once, in the order chosen.
 */
fun foldersUsed(chosen: List<Path>, choosing: Choosing): List<Path> =
	when (choosing) {
		is Choosing.Folder -> chosen
		is Choosing.OneFile, is Choosing.Files, is Choosing.SaveAs ->
			chosen.mapNotNull {
				it.parent
			}
		// Exploring answers nobody.
		is Choosing.Exploring -> emptyList()
	}.distinct()

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
	/** What a section holds. */
	sealed interface Kind {
		val title: String

		/** Which section it is, whoever the page is for: what the user collapses, and what is kept of that. */
		val section: RecentSection

		/** What its entries are, as what folds it says: "Hide files". */
		val holds: String

		/** The files the index saw made last. */
		data object NewFiles : Kind {
			override val title: String get() = "New files"
			override val section: RecentSection get() = RecentSection.NewFiles
			override val holds: String get() = "files"
		}

		/** The folders the application that asked was last answered from. */
		data object Application : Kind {
			override val title: String get() = "Recent folders for this application"
			override val section: RecentSection get() = RecentSection.Application
			override val holds: String get() = "folders"
		}

		/** The folders any application was last answered from. */
		data object Folders : Kind {
			override val title: String get() = "Recent folders"
			override val section: RecentSection get() = RecentSection.Folders
			override val holds: String get() = "folders"
		}
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
	 * nothing that goes by its rows (the cursor, what is chosen, a name typed, their count) knows of them.
	 */
	@Immutable
	class Shown(
		val rows: Rows,
		val headings: List<Heading>,
	) {
		/** The headings drawn over each row, by the row's place; over nothing, those after the last row. */
		val over: Map<Int, List<Heading>> = headings.groupBy { it.before }
	}

	/**
	 * The page as [arrangement] shows it: of each section, the entries it admits; a section with none is left
	 * out, heading and all, and one it folds is its heading alone.
	 */
	fun shown(arrangement: Arrangement): Shown {
		val rows = ArrayList<Row>()
		val headings = ArrayList<Heading>()

		for (section in sections) {
			val admitted = section.entries.map { it.row }.filter(arrangement::admits)
			val collapsed = section.kind.section in arrangement.folded

			if (admitted.isNotEmpty()) {
				headings += Heading(section.kind, admitted.size, collapsed, before = rows.size)
				if (!collapsed)
					rows += admitted
			}
		}

		return Shown(Rows(rows), headings)
	}

	/** A row of the page: when it was made (a file) or last used (a folder), and whether that is [stale]. */
	@Immutable
	data class Entry(
		val row: Row,
		val at: Instant,
		/** Long before the newest of its section ([STALE_AFTER]): drawn quieter. */
		val stale: Boolean,
	)

	/** One section: never empty, its entries the newest first. Made by [of]. */
	@Immutable
	class Section private constructor(
		val kind: Kind,
		val entries: List<Entry>,
	) {
		companion object {
			/** The section of [kind] holding [dated], the newest first; none, where there is nothing to hold. */
			fun of(kind: Kind, dated: List<Pair<Row, Instant>>): Section? {
				val ordered = dated.sortedByDescending { it.second }
				val newest = ordered.firstOrNull()?.second ?: return null
				val fresh = newest.minus(STALE_AFTER)

				return Section(kind, ordered.map { (row, at) -> Entry(row, at, at < fresh) })
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

		/** How much older than its section's newest an entry is before it is drawn quieter. */
		val STALE_AFTER: Duration = Duration.ofDays(10)

		/**
		 * The page of the [newest] files (the newest first, as the index saw them made) and the [folders] lately
		 * used, for [asker] where the picker is some application's, as [row] reads each on disk: what it does not
		 * find is not shown. The newest [NEW_SHOWN] files that are there; the folders [asker] was answered from; and
		 * the folders anybody was, without those already shown as the application's.
		 */
		fun of(
			newest: List<NewFile>,
			folders: RecentFolders,
			asker: AppKey?,
			now: Instant,
			row: (Path) -> Row?,
		): RecentPage {
			fun dated(recent: List<Recent>): List<Pair<Row, Instant>> =
				recent.mapNotNull { used ->
					row(used.folder)?.takeIf { it.isFolder }?.let { it to used.at }
				}

			val files =
				newest
					.asSequence()
					.mapNotNull { made ->
						row(made.path)?.takeUnless { it.isFolder }?.let { it to made.at }
					}.take(NEW_SHOWN)
					.toList()

			val own = folders.of(asker).entries
			val others =
				folders.all.entries.filterNot { used ->
					own.any { it.folder == used.folder }
				}

			return RecentPage(
				listOfNotNull(
					Section.of(Kind.NewFiles, files),
					asker?.let { Section.of(Kind.Application, dated(own)) },
					Section.of(Kind.Folders, dated(others)),
				),
				now,
			)
		}
	}
}

/** [path] as a row of the Recents page, as it is on disk now; none, where it is not there. */
internal fun recentRowOf(path: Path): Row? =
	rowOf(path)?.copy(id = RowId.Recent(path), hidden = false)

/**
 * The Recents page for [asking], read from these: the newest files the index knows that are still there, and the
 * recent folders. The index can be a moment behind the disk, and what it lists newest can since have been deleted
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
