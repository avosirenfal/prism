package prism.browser

import androidx.compose.runtime.Immutable
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.math.pow
import prism.preferences.RecentSection
import prism.preferences.Relevance
import prism.shared.Format
import prism.shared.Guarded
import prism.shared.Stored
import prism.shared.Used
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

/** What a picker answered with a file was for: a file to open, or one to save. A folder picked is opened. */
enum class Picked(
	/** As the file of recent files writes it. */
	val word: String,
) {
	Opened("open"),
	Saved("save"),
}

/** Something a picker was answered with (a file, or a folder), when it last was, and whether to open or to save it. */
@Immutable
data class Recent(
	val path: Path,
	val at: Instant,
	val picked: Picked = Picked.Opened,
)

/**
 * The files, or the folders, last used, the latest first: each once, and no more than [MOST]. Made only by [of],
 * which keeps it so whatever it is given, so a use ([used]) lets the oldest go where there is no room, and two lists
 * read at once are one ([plus]). More are kept than the Recents page shows of a list ([RecentPage.SHOWN]): what was
 * used before those still says which folders are used, for the page's most relevant.
 */
@Immutable
class RecentList private constructor(
	val entries: List<Recent>,
) {
	val paths: List<Path> get() = entries.map { it.path }

	/** These, with [paths] used at [at] ([picked] to open, or to save): each first among them, in the order given. */
	fun used(paths: List<Path>, at: Instant, picked: Picked = Picked.Opened): RecentList =
		of(paths.map { Recent(it, at, picked) } + entries)

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
		/** How many a list keeps of each kind. */
		const val MOST = 30

		val NONE = RecentList(emptyList())

		/**
		 * [entries] as a list: the latest use of each, the latest first, the first [MOST] of each kind of them (to
		 * open, to save), so that what an application saves is not put out by what it opens, however much more of
		 * that there is.
		 */
		fun of(entries: List<Recent>): RecentList =
			RecentList(
				entries
					.sortedByDescending { it.at }
					.distinctBy { it.path }
					.groupBy { it.picked }
					.values
					.flatMap { it.take(MOST) }
					.sortedByDescending { it.at },
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

	/** These, with [paths] used by [app] at [at], [picked] to open or to save. */
	fun used(
		paths: List<Path>,
		app: AppKey?,
		at: Instant,
		picked: Picked = Picked.Opened,
	): Recently =
		if (paths.isEmpty())
			this
		else
			Recently(
				all.used(paths, at, picked),
				app?.let { capped(apps + (it to of(it).used(paths, at, picked))) } ?: apps,
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
 * A folder [app] (or, null, an application nobody could tell) was answered from: the folder of a file it was answered
 * with, to open or to save ([picked]), or a folder it was answered with, which is opened. What [Recents.answered]
 * counts the uses of.
 */
@Immutable
data class AppFolder(
	val app: AppKey?,
	val picked: Picked,
	val folder: Path,
)

/**
 * Everything kept of what is recent: the [folders] folder pickers were answered with, the [files] file pickers were
 * (to open, or to save), how much each folder has been gone to or picked from ([accesses]), and how much each
 * application was answered from each folder, to open from it or to save into it ([answered]). A file picked is not
 * its folder picked: its folder is only gone to, and answered from.
 */
@Immutable
data class Recents(
	val folders: Recently = Recently(),
	val files: Recently = Recently(),
	val accesses: Uses<Path> = Uses(),
	val answered: Uses<AppFolder> = Uses(),
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
	private val answered: Stored<Uses<AppFolder>>,
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
			answered.value.value,
		)

	/**
	 * A picker that was [choosing], for [app], was answered with [chosen] at [at]: a folder picker's answer is the
	 * latest of the recent folders, a file picker's the latest of the recent files (to open, or to save); and the
	 * folder each was picked from (a folder picked, itself) was gone to, and the application answered from it once
	 * ([Recents.answered]). Exploring answers nobody.
	 *
	 * What is not there any more is let go as each is added, but never what was just chosen: a file to save is not
	 * there until the application that asked has written it, which it does once the picker has answered.
	 */
	fun picked(chosen: List<Path>, choosing: Choosing, app: AppKey?, at: Instant) {
		fun Recently.gone(there: (Path) -> Boolean): Recently =
			without {
				it !in chosen &&
					!there(it)
			}

		fun answeredFrom(from: List<Path>, picked: Picked) =
			answered.update {
				from
					.fold(this) { uses, folder -> uses.used(AppFolder(app, picked, folder), at) }
					.capped(MOST_ANSWERED, at)
			}

		when (choosing) {
			is Choosing.Folder -> {
				folders.update { used(chosen, app, at).gone(folderThere) }
				answeredFrom(chosen.distinct(), Picked.Opened)
				chosen.forEach { went(it, at) }
			}
			is Choosing.OneFile, is Choosing.Files, is Choosing.SaveAs -> {
				val picked =
					if (choosing is Choosing.SaveAs)
						Picked.Saved
					else
						Picked.Opened
				val from = chosen.mapNotNull { it.parent }.distinct()

				files.update { used(chosen, app, at, picked).gone(fileThere) }
				answeredFrom(from, picked)
				from.forEach { went(it, at) }
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

		/** How many folders, of every application's, are kept as answered from ([Uses.capped]). */
		const val MOST_ANSWERED = 1_000

		/** How long accesses are held before they are written. */
		val WRITE_EVERY: Duration = Duration.ofSeconds(5)

		/** What is recent kept in memory alone: a browser run without files of it, and the tests. */
		fun inMemory(first: Recents = Recents()): RecentStore =
			RecentStore(
				Stored.inMemory(first.folders),
				Stored.inMemory(first.files),
				Stored.inMemory(first.accesses),
				Stored.inMemory(first.answered),
				{ true },
				{ true },
			)

		/**
		 * What is recent kept in [folder]: the folders in `recent-folders`, the files in `recent-files`
		 * ([RecentFormat]), the accesses in `folder-accesses` ([AccessFormat]), and where each application was
		 * answered from in `answered-from` ([AnsweredFormat]). A file that cannot be read is nothing recent; one that
		 * cannot be written is said to [report], and the use is kept for as long as this prism runs.
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
				kept("answered-from", AnsweredFormat, "where each application was answered from"),
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
 * for the list of every application's), whether it was to open or to save (`open`, `save`), and the path, between
 * tabs. A line an older version wrote, with no word for which, was to open. A line that does not say a use is left
 * out.
 */
val RecentFormat: Format<Recently> =
	Format.lines(
		Recently(),
		read = { lines ->
			val uses =
				lines.mapNotNull { line ->
					val parts = line.split('\t', limit = 3)
					val at = parts.getOrNull(0)?.toLongOrNull()
					val rest = parts.getOrNull(2).orEmpty()
					// A path is absolute, and no word for which begins as one does.
					val (picked, written) =
						if (rest.startsWith("/"))
							Picked.Opened to rest
						else
							rest.substringBefore('\t').let { word ->
								Picked.entries.firstOrNull { it.word == word } to rest.substringAfter('\t', "")
							}
					val path = pathRead(written)

					if (at == null || path == null || picked == null)
						null
					else
						AppKey.of(parts[1]) to Recent(path, Instant.ofEpochMilli(at), picked)
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
					"${it.at.toEpochMilli()}\t${app?.id.orEmpty()}\t${it.picked.word}\t${escaped(
						it.path.toString(),
					)}"
				}

			lines(null, kept.all) + kept.apps.flatMap { (app, list) -> lines(app, list) }
		},
	)

/** How much each folder is used, as a file ([Uses.format]), each by its path. */
val AccessFormat: Format<Uses<Path>> = Uses.format(::pathRead) { escaped(it.toString()) }

/**
 * How much each application was answered from each folder, as a file ([Uses.format]): each by the application
 * (nothing, for one nobody could tell), whether it was to open or to save (`open`, `save`), and the folder's path,
 * between tabs.
 */
val AnsweredFormat: Format<Uses<AppFolder>> =
	Uses.format(
		read = { text ->
			val (app, word, path) = (text.split('\t', limit = 3) + listOf("", "", "")).take(3)
			val picked = Picked.entries.firstOrNull { it.word == word }
			val folder = pathRead(path)

			if (picked == null || folder == null)
				null
			else
				AppFolder(AppKey.of(app), picked, folder)
		},
		written = {
			"${it.app?.id.orEmpty()}\t${it.picked.word}\t${escaped(it.folder.toString())}"
		},
	)

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
 * Which of what pickers were answered with says where this one is likely to pick from: of a picker that saves, the
 * folders files were saved into; of one that opens files, the folders they were opened from; of anything else (a
 * picker of folders, or the explorer, which is nobody's picker), both.
 */
internal fun picksOf(choosing: Choosing): Set<Picked> =
	when (choosing) {
		is Choosing.SaveAs -> setOf(Picked.Saved)
		is Choosing.OneFile, is Choosing.Files -> setOf(Picked.Opened)
		is Choosing.Folder, is Choosing.Exploring -> Picked.entries.toSet()
	}

/**
 * What of the Recents page an arrangement leaves out, and how it shows the rest: the sections [folded] to their
 * headings, and the paths the user has [hidden] from it, which are left out of every section unless they are asked
 * to be shown all the same ([showsHidden]); whether its sections are [tabbed], shown one at a time (the [tab] chosen,
 * or, where that has nothing to show or none is, the first that has something), or each under its heading in one
 * list; and how its most relevant folders are found ([relevance]). Nothing, of any other listing.
 */
@Immutable
data class RecentArranged(
	val folded: Set<RecentSection> = emptySet(),
	val hidden: Set<Path> = emptySet(),
	val showsHidden: Boolean = false,
	val tabbed: Boolean = false,
	val tab: RecentSection? = null,
	val relevance: Relevance = Relevance(),
) {
	companion object {
		val NONE = RecentArranged()
	}
}

/**
 * A folder that may be among the Recents page's most relevant, and what says how relevant it is: when it was [last]
 * used (gone to, picked, or picked from), how much it is used ([frequency]: gone to and picked from, by anyone), and
 * how much by the application asking ([application]: answered from it, as its picker picks). Each of those two is from
 * nothing to one, by how many uses it is ([saturated]): a half for a few, and nearer one the more, whatever any other
 * folder's is.
 */
@Immutable
data class Candidate(
	val row: Row,
	val last: Instant,
	val frequency: Double,
	val application: Double,
) {
	/**
	 * How relevant it is as of [now], as [relevance] weighs what says so: how lately it was used (one, used now, and
	 * half as much every [Relevance.recencyHalfLifeHours] since), how much, and how much by the application asking,
	 * each by its weight, added up.
	 */
	fun score(relevance: Relevance, now: Instant): Double {
		val hours = Duration.between(last, now).toMillis().coerceAtLeast(0) / MILLIS_AN_HOUR
		val recency =
			0.5.pow(hours / relevance.recencyHalfLifeHours.coerceAtLeast(LEAST_HALF_LIFE))

		return relevance.recency * recency +
			relevance.frequency * frequency +
			relevance.application * application
	}

	companion object {
		private const val MILLIS_AN_HOUR = 3_600_000.0

		/** The shortest half-life a recency is weighed by: what a preferences file says that is shorter, or none. */
		private const val LEAST_HALF_LIFE = 0.01

		/** How many uses, as [Used] weighs them, make a folder's frequency a half: four, lately. */
		const val FREQUENT = 4.0

		/** How many answers from a folder, weighed so, make the application's use of it a half: one, lately. */
		const val ANSWERED = 1.0

		/** [weight] as a part of one: a half at [half], three quarters at twice as much, and nearer one the more. */
		fun saturated(weight: Double, half: Double): Double =
			1 - 0.5.pow(weight.coerceAtLeast(0.0) / half)
	}
}

/**
 * The Recents page as it was [read]: its [sections], each with something in it, in the order they are shown, and the
 * folders that may be its most relevant ([candidates]), which are weighed as it is shown, as the user weighs what says
 * so. Its rows are the listing's ([Shown.rows]), so they are chosen, opened and dragged as any folder's are; what it
 * adds to each is which section it is in, when it was made or used, and whether that is long before its section's
 * newest ([Shown.marks]).
 */
@Immutable
class RecentPage(
	val sections: List<Section>,
	val read: Instant,
	val candidates: List<Candidate> = emptyList(),
) {
	/** What a section holds: which of the page's it is, whoever the page is for. */
	enum class Kind(
		val title: String,
		/** What its tab says: as little as tells it from the tabs beside it. */
		val tab: String,
		/** What the user collapses, and what is kept of that. */
		val section: RecentSection,
		/** What its entries are, as what folds it says: "Hide files". */
		val holds: String,
		/**
		 * Whether its entries are chosen by how much they are used, not by how lately: shown the newest first all the
		 * same, and none drawn quieter for being older than the rest.
		 */
		val scored: Boolean = false,
	) {
		/** The folders most relevant to whoever asks, by how lately and how much they are used ([Candidate]). */
		Relevant(
			"Most relevant folders",
			"Relevant",
			RecentSection.Relevant,
			"folders",
			scored = true,
		),

		/** The files the index saw made last. */
		NewFiles("New files", "New files", RecentSection.NewFiles, "files"),

		/** The files the application that asked was last answered with. */
		ApplicationFiles(
			"Recent files for this application",
			"This app's files",
			RecentSection.ApplicationFiles,
			"files",
		),

		/** The files any application was last answered with. */
		Files("Recent files", "Files", RecentSection.Files, "files"),

		/** The folders the application that asked was last answered with. */
		Application(
			"Recent folders for this application",
			"This app's folders",
			RecentSection.Application,
			"folders",
		),

		/** The folders any application was last answered with. */
		Folders("Recent folders", "Folders", RecentSection.Folders, "folders"),

		/** The folders gone to and picked from most. */
		Frequent(
			"Frequently accessed",
			"Frequent",
			RecentSection.Frequent,
			"folders",
			scored = true,
		),
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

	/** A section's tab, as the listing draws it: how many entries the section has to show. */
	@Immutable
	data class Tab(
		val kind: Kind,
		val count: Int,
	)

	/** How the sections shown are told apart: in one list, each under its heading; or as tabs, one at a time. */
	@Immutable
	sealed interface Grouping {
		/** Every section under its heading: the [headings], each where it is drawn among the rows. */
		data class Groups(
			val headings: List<Heading>,
		) : Grouping

		/** A tab for each section with anything to show; the [chosen] one's entries are the rows, and no other's. */
		data class Tabs(
			val tabs: List<Tab>,
			val chosen: Kind,
		) : Grouping
	}

	/**
	 * What the page shows, arranged: the [rows] of its open sections (of the tab shown, in tabs), in order, and how the
	 * sections are told apart ([grouping]). A collapsed section's entries, and another tab's, are no rows of the
	 * listing, so nothing that goes by its rows (the cursor, what is chosen, a name typed, their count) knows of them.
	 * The rows that are hidden from the page and shown all the same are [dimmed]; what the page says of each row is
	 * its mark ([marks]).
	 */
	@Immutable
	class Shown(
		val rows: Rows,
		val grouping: Grouping,
		val dimmed: Set<RowId>,
		val marks: Map<RowId, Mark>,
	) {
		/** Every section's heading, in a list; none, in tabs. */
		val headings: List<Heading> get() = (grouping as? Grouping.Groups)?.headings.orEmpty()

		/** The headings drawn over each row, by the row's place; over nothing, those after the last row. */
		val over: Map<Int, List<Heading>> = headings.groupBy { it.before }

		/** How many entries are shown: those of the open sections, or of the tab shown. */
		val listed: Int get() = rows.size
	}

	/**
	 * The page as [arrangement] shows it: the most relevant folders as it weighs them, then every other section; of
	 * each, what it shows of the section ([Section.shown]). A section with nothing to show is left out, heading, tab
	 * and all. In a list, one it folds is its heading alone; in tabs, the rows are the chosen tab's alone.
	 */
	fun shown(arrangement: Arrangement): Shown {
		val recent = arrangement.recent

		val parts =
			(listOfNotNull(relevant(recent.relevance)) + sections).mapNotNull {
				it.shown(
					arrangement,
					if (it.kind == Kind.Relevant)
						recent.relevance.shown.coerceAtLeast(1)
					else
						SHOWN,
				)
			}

		val chosen = parts.firstOrNull { it.kind.section == recent.tab } ?: parts.firstOrNull()

		val (open, grouping) =
			if (recent.tabbed && chosen != null) {
				listOf(chosen) to
					Grouping.Tabs(parts.map { Tab(it.kind, it.entries.size) }, chosen.kind)
			} else {
				val collapsed = parts.map { it.kind.section in recent.folded }
				// Each heading over the first row after the open sections before it.
				val before =
					parts.zip(collapsed).runningFold(0) { rows, (part, shut) ->
						rows +
							if (shut)
								0
							else
								part.entries.size
					}

				parts.filterIndexed { index, _ -> !collapsed[index] } to
					Grouping.Groups(
						parts.mapIndexed { index, part ->
							Heading(part.kind, part.entries.size, collapsed[index], before[index])
						},
					)
			}

		val entries = open.flatMap { it.entries }

		return Shown(
			Rows(entries.map { it.first }),
			grouping,
			entries.filter { it.first.path in recent.hidden }.mapTo(HashSet()) { it.first.id },
			entries.associate { (row, mark) -> row.id to mark },
		)
	}

	/** The section of the most relevant folders, as [relevance] weighs them: the most relevant first. */
	private fun relevant(relevance: Relevance): Section? =
		Section.of(
			Kind.Relevant,
			candidates.sortedByDescending { it.score(relevance, read) }.map { it.row to it.last },
		)

	/** Whether anything on the page is among [hidden]: whether there is anything to show that is not shown. */
	fun hides(hidden: Set<Path>): Boolean =
		hidden.isNotEmpty() && rows.any { it.path in hidden }

	/** A row of a section, and when it was made (a file) or last used (a folder). */
	@Immutable
	data class Entry(
		val row: Row,
		val at: Instant,
	)

	/**
	 * What of a section is shown: its [kind], and its [entries], each a row and what the page says of it, the
	 * newest first.
	 */
	@Immutable
	class Part(
		val kind: Kind,
		val entries: List<Pair<Row, Mark>>,
	)

	/**
	 * One section: never empty, its entries in the order it chooses among them by, the newest first (or, of one
	 * [Kind.scored], the most used first). Made by [of].
	 */
	@Immutable
	class Section private constructor(
		val kind: Kind,
		val entries: List<Entry>,
	) {
		/**
		 * What of it [arrangement] shows: the first [most] of its entries it admits, in its order, without those
		 * hidden from the page unless it shows them; the newest first, and, where its kind is not [Kind.scored], each
		 * long before the newest of them ([STALE_AFTER]) marked so. None, where it admits none.
		 */
		fun shown(arrangement: Arrangement, most: Int): Part? {
			val recent = arrangement.recent
			val admitted =
				entries
					.asSequence()
					.filter { (row) ->
						arrangement.admits(row) && (recent.showsHidden || row.path !in recent.hidden)
					}.take(most)
					.sortedByDescending { it.at }
					.toList()
			val fresh = admitted.firstOrNull()?.at?.minus(STALE_AFTER) ?: return null

			return Part(
				kind,
				admitted.map { (row, at) ->
					row to Mark(kind, at, stale = !kind.scored && at < fresh)
				},
			)
		}

		companion object {
			/**
			 * The section of [kind] holding [dated], each a row of that section's own ([RowId.Recent]): the newest
			 * first, or, of one [Kind.scored], as given. None, where there is nothing to hold.
			 */
			fun of(kind: Kind, dated: List<Pair<Row, Instant>>): Section? =
				dated.takeIf { it.isNotEmpty() }?.let { given ->
					Section(
						kind,
						(
							if (kind.scored)
								given
							else
								given.sortedByDescending { it.second }
						).map { (row, at) ->
							Entry(row.path?.let { row.copy(id = RowId.Recent(it, kind.section)) } ?: row, at)
						},
					)
				}
		}
	}

	/** What the page says of one of its rows shown: its section, when, and whether that is long before the rest. */
	@Immutable
	data class Mark(
		val section: Kind,
		val at: Instant,
		val stale: Boolean,
	)

	/** Every row there is to show, section after section, then the folders that may be the most relevant. */
	val rows: List<Row> =
		sections.flatMap { section -> section.entries.map { it.row } } +
			candidates.map { it.row }

	override fun toString(): String =
		"RecentPage(${sections.joinToString { "${it.kind.title}: ${it.entries.size}" }}, " +
			"${candidates.size} candidates)"

	companion object {
		/** How many of the newest files are asked of the index: enough that those since deleted leave ten. */
		const val NEW_KEPT = 100

		/** How many entries of each section the page shows: the first of them it admits. */
		const val SHOWN = 10

		/** How many new files the page shows: the newest that are still there. */
		const val NEW_SHOWN = SHOWN

		/** How many of the folders used most are held: so that the most used it admits are shown, whatever it admits. */
		const val FREQUENT_KEPT = 30

		/** How much older than its section's newest an entry is before it is drawn quieter. */
		val STALE_AFTER: Duration = Duration.ofDays(10)

		/**
		 * The page of the [newest] files (the newest first, as the index saw them made) and what is [recent], for
		 * [asker] where the picker is some application's, as [row] reads each on disk: what it does not find is not
		 * shown. In order: the newest files that are there; the files [asker] was answered with; the files anybody
		 * was, without those already shown as the application's; the folders, the same two ways; and the folders used
		 * most as of [now]. A page that shows no [files] (a folder picker's) has the folders' sections alone. Its
		 * candidates for the most relevant are every folder of those sections, and the folder of every file [asker]
		 * was answered with of the kinds [picks] says ([candidatesOf]).
		 */
		fun of(
			newest: List<NewFile>,
			recent: Recents,
			asker: AppKey?,
			now: Instant,
			files: Boolean = true,
			picks: Set<Picked> = Picked.entries.toSet(),
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
				newest.mapNotNull { made ->
					row(made.path)?.takeUnless { it.isFolder }?.let { it to made.at }
				}

			val (ownFiles, otherFiles) = split(recent.files)
			val (ownFolders, otherFolders) = split(recent.folders)

			val frequent =
				recent.accesses
					.most(FREQUENT_KEPT, now) { row(it)?.isFolder == true }
					.mapNotNull { (folder, used) -> row(folder)?.let { it to used.at } }

			val sections =
				listOfNotNull(
					Section.of(Kind.NewFiles, made).takeIf { files },
					Section.of(Kind.ApplicationFiles, dated(ownFiles, folders = false)).takeIf { files },
					Section.of(Kind.Files, dated(otherFiles, folders = false)).takeIf { files },
					Section.of(Kind.Application, dated(ownFolders, folders = true)),
					Section.of(Kind.Folders, dated(otherFolders, folders = true)),
					Section.of(Kind.Frequent, frequent),
				)

			return RecentPage(
				sections,
				now,
				candidatesOf(sections, recent, asker, picks, now, row),
			)
		}

		/**
		 * The folders that may be the most relevant of a page of [sections]: those its sections show (the first
		 * [SHOWN] of each), and every one [asker] was answered from as [picks] says (anyone, where nobody asks), that
		 * [row] finds there; each as lately and as much as [recent] says it is used, as of [now] ([Candidate]).
		 */
		private fun candidatesOf(
			sections: List<Section>,
			recent: Recents,
			asker: AppKey?,
			picks: Set<Picked>,
			now: Instant,
			row: (Path) -> Row?,
		): List<Candidate> {
			val asked: Map<Path, List<Used>> =
				recent.answered.uses.entries
					.filter { (from) -> (asker == null || from.app == asker) && from.picked in picks }
					.groupBy({ it.key.folder }, { it.value })

			val picked =
				recent.folders.all.entries
					.associate { it.path to it.at }

			val paths =
				(
					sections.flatMap { section ->
						section.entries
							.take(SHOWN)
							.filter { it.row.isFolder }
							.mapNotNull { it.row.path }
					} + asked.keys
				).distinct()

			return paths.mapNotNull { path ->
				val folder = row(path)?.takeIf { it.isFolder }

				val last =
					listOfNotNull(
						recent.accesses.uses[path]?.at,
						picked[path],
						asked[path]?.maxOf { it.at },
					).maxOrNull()

				if (folder == null || last == null)
					null
				else
					Candidate(
						folder.copy(id = RowId.Recent(path, RecentSection.Relevant)),
						last,
						Candidate.saturated(recent.accesses.weightOf(path, now), Candidate.FREQUENT),
						Candidate.saturated(
							asked[path].orEmpty().sumOf { it.weightAt(now) },
							Candidate.ANSWERED,
						),
					)
			}
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
		picks = asking.picks,
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
