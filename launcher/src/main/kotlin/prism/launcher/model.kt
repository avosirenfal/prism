package prism.launcher

import androidx.compose.runtime.Immutable
import java.nio.file.Path
import java.time.Instant
import prism.browser.Category
import prism.desktop.Application
import prism.desktop.ApplicationAction
import prism.index.query.Alias
import prism.index.query.AliasCompletion
import prism.index.query.Aliases
import prism.index.query.Own
import prism.index.query.QueryText
import prism.index.query.ReadQuery
import prism.index.query.completing
import prism.preferences.LauncherPreferences
import prism.window.OpenWindow

/*
 * The launcher, as one immutable value: what is typed, what is installed, what was found for what is typed, and which
 * result the arrows are on. Everything it shows is worked out from that ([LauncherState.sections]), so the view only
 * draws, and the reducer (step.kt) only says what the next state is and what is to be done (start an application,
 * search the index), which a session carries out (session.kt).
 */

/** Something the launcher finds, and does something with when it is chosen. */
@Immutable
sealed interface Entry {
	/** What tells it from every other entry, and counts its uses: the same from one opening of the launcher to the next. */
	val key: String
	val title: String

	/** What is said of it beside its title: what it is for, or where it is. */
	val detail: String

	/** What kind of thing it is, said at its row's end. */
	val kind: String

	/** An application, started; with the [windows] it has open now, where the desktop tells of them and it has any. */
	data class App(
		val application: Application,
		val windows: List<OpenWindow> = emptyList(),
	) : Entry {
		override val key: String get() = "app:${application.id}"
		override val title: String get() = application.name
		override val detail: String get() = application.comment ?: application.generic.orEmpty()
		override val kind: String get() = "Application"
	}

	/** Something else an application can be started to do: a new private window. */
	data class Action(
		val application: Application,
		val action: ApplicationAction,
	) : Entry {
		override val key: String get() = "action:${application.id}:${action.id}"
		override val title: String get() = action.name
		override val detail: String get() = application.name
		override val kind: String get() = "Action"
	}

	/** A file or a folder the index holds, opened with the application that opens its kind. */
	data class File(
		val path: Path,
		val category: Category,
		/** Where it is, as it is said: its folder, from `~` where it is under the home folder. */
		override val detail: String,
		/** What kind of file it is, as a listing's type column says: "PDF document". */
		override val kind: String,
	) : Entry {
		override val key: String get() = "file:$path"
		override val title: String get() = path.fileName?.toString() ?: path.toString()
		val isFolder: Boolean get() = category == Category.Folder
	}

	/** Something prism itself does. */
	data class Command(
		val command: LauncherCommand,
	) : Entry {
		override val key: String get() = "command:${command.name}"
		override val title: String get() = command.label
		override val detail: String get() = command.detail
		override val kind: String get() = PRISM
	}

	/**
	 * Something of prism's own that what is typed asks for by the word `prism`, or by a bare `?` ([Own]): the
	 * launcher's settings and its keys, the page of the system's drives, how a query is written.
	 */
	data class Prism(
		val own: Own,
	) : Entry {
		override val key: String get() = "own:${own.title}"
		override val title: String get() = own.title

		/** What choosing it does here: the settings and the keys are the launcher's own. */
		override val detail: String
			get() =
				when (own) {
					Own.Settings -> "Launcher appearance, results and shortcuts"
					Own.Bindings -> "Change the launcher's keyboard shortcuts"
					Own.System, Own.Help -> own.does
				}
		override val kind: String get() = PRISM
	}

	/**
	 * A search [alias] the word being typed could name: chosen, its name is put where the word was, so that what
	 * is typed is [completed], and the launcher stays for the rest of the query.
	 */
	data class Named(
		val alias: Alias,
		val completed: String,
	) : Entry {
		override val key: String get() = "alias:${alias.name.key}"
		override val title: String get() = alias.name.written
		override val detail: String get() = alias.text
		override val kind: String get() = "Alias"
	}

	companion object {
		/** What is said of what is prism's own at its row's end, and over it. */
		const val PRISM = "Prism"

		/** What of prism's own the launcher finds, in the order it lists them in. */
		val OFFERED: List<Own> = listOf(Own.Settings, Own.Bindings, Own.System, Own.Help)
	}
}

/**
 * What prism itself does from the launcher that is nothing of [Own]'s: found as an application is, by what is typed
 * fitting its name and its words ([Does]).
 */
enum class LauncherCommand(
	val label: String,
	val detail: String,
	/** Other words it is found by. */
	val keywords: List<String>,
) {
	Files(
		"Files",
		"Open your home folder",
		listOf("explorer", "file manager", "browse", "folders", "home", "prism"),
	),
}

/** Results of one kind, under their [title]. */
@Immutable
data class Section(
	val title: String,
	val entries: List<Entry>,
)

/** What a search of the index found for the launcher. */
@Immutable
sealed interface FilesFound {
	/** What was typed that this is the answer for; none, where nothing has been answered. */
	val query: String?

	/** Nothing asked for, or nothing back yet. */
	data object None : FilesFound {
		override val query: String? get() = null
	}

	/** The first [files] of [total], found for [query]. */
	data class Found(
		override val query: String,
		val files: List<Entry.File>,
		val total: Int,
	) : FilesFound

	/** The search for [query] went wrong: a mistake of prism's, reported where it happened, and said here. */
	data class Failed(
		override val query: String,
	) : FilesFound
}

/** What the launcher says along its bottom of the last thing done: that it [Failed], or, where it stays to say so, that it was [Done]. */
@Immutable
sealed interface LauncherNotice {
	val text: String

	data class Failed(
		override val text: String,
	) : LauncherNotice

	data class Done(
		override val text: String,
	) : LauncherNotice
}

/**
 * The applications installed that a person chooses from, each made ready to be looked through ([Listed]), and with
 * the windows it has open among [windows]: those the desktop told of as the launcher was shown.
 */
data class Installed private constructor(
	internal val listed: List<Listed>,
	private val windows: List<OpenWindow>,
) {
	val applications: List<Application> get() = listed.map { it.application }

	/** What is installed read again as [applications], the windows open being those they were. */
	fun read(applications: List<Application>): Installed = of(applications, windows)

	companion object {
		val NONE = Installed(emptyList(), emptyList())

		fun of(
			applications: List<Application>,
			windows: List<OpenWindow> = emptyList(),
		): Installed =
			Installed(applications.map { Listed(it, windowsOf(it, windows)) }, windows)
	}
}

/** The launcher. */
@Immutable
data class LauncherState(
	/** What is typed into its field. */
	val query: String = "",
	val installed: Installed = Installed.NONE,
	/** What has been started from here, and how lately. */
	val launches: Launches = Launches.NONE,
	val preferences: LauncherPreferences = LauncherPreferences(),
	/** What the index's search last came back with: for an older [query], until the one for this arrives. */
	val files: FilesFound = FilesFound.None,
	/** Whether there is an index to search for files. */
	val searchable: Boolean = true,
	/**
	 * The entry the arrows are on, by its key; none for the first. Kept by what it is and not by where it is, so that
	 * it stays on the same entry when what is shown changes around it (files arrive, what is installed is read
	 * again), and can never be past the end of what is shown.
	 */
	val arrowsOn: String? = null,
	/** What came of the last thing chosen, where that is said: until something else is typed or chosen. */
	val notice: LauncherNotice? = null,
	/**
	 * The menu of what can be done to a result, while it is open: the arrows and Enter are the menu's then. It holds
	 * the result it is of.
	 */
	val menu: EntryMenu? = null,
	/** Whether how a query is written is shown, in place of the field and what was found, until it is left. */
	val help: Boolean = false,
	/** The user's search aliases: what a `name@` in [query] stands for, where files are looked for. */
	val aliases: Aliases = Aliases.NONE,
	/** When this is, for how lately something was used. */
	val now: Instant = Instant.EPOCH,
) {
	/**
	 * What is shown, in groups: the applications (and their actions) that fit [query], the best first, then what
	 * prism does ([Does]), then the files the index found for it. With nothing typed, every application, those used
	 * most and most lately first, and what prism does. Where [query] asks for prism's own ([Own.found]: the word
	 * `prism`, or a bare `?`), what it asks for comes before everything, with whatever else prism does that fits;
	 * but for an application named exactly what is typed, which comes first of all, and its section with it.
	 * The aliases the word being typed could name come after what is started and before the files: they are for
	 * finding files, and Enter on the first thing shown still starts an application.
	 */
	val sections: List<Section> by lazy {
		val shown = installed.listed.filterNot { it.application.id in preferences.hidden }
		val words = wordsOf(query)

		val programs =
			if (words.isEmpty())
				shown
					.sortedWith(
						compareByDescending<Listed> { launches.weightOf(it.entry.key, now) }
							.thenBy { it.sortName },
					).map { it.entry }
			else
				ranked(
					shown.flatMap { listed ->
						listOfNotNull(scored(listed.entry, scoreOf(words, listed))) +
							listed.actions
								.takeIf { preferences.actions }
								.orEmpty()
								.mapNotNull { scored(it.entry, scoreOf(words, listed, it)) }
					},
				)

		val own = Own.found(query, Entry.OFFERED).map(Entry::Prism)

		// What prism does that fits as an application does; but for what is asked for outright, which is said once.
		val does =
			ranked(Does.ALL.mapNotNull { scored(it.entry, it.scoreOf(words)) }).filterNot {
				it in
					own
			}

		val found = (files as? FilesFound.Found)?.takeIf { words.isNotEmpty() }?.files.orEmpty()

		val applications = Section("Applications", programs)

		(
			// What is typed is an application's very name: it is that application that is asked for, whatever
			// of prism's own the same word asks for (`prism` is prism's word, and the explorer's name).
			if (own.isEmpty() || programs.firstOrNull()?.let(::named) == true)
				listOf(applications, Section(Entry.PRISM, own + does))
			else
				listOf(Section(Entry.PRISM, own + does), applications)
		).plus(
			listOf(
				Section(
					"Aliases",
					completing
						?.let {
							it.aliases.map { alias ->
								Entry.Named(alias, it.completed(alias))
							}
						}.orEmpty(),
				),
				Section("Files", found),
			),
		).filter { it.entries.isNotEmpty() }
	}

	/** [query] read in the search language, with the aliases it uses stood for. */
	val read: ReadQuery by lazy { QueryText.read(query, aliases) }

	/** The aliases the word being typed at the end of [query] could be on its way to naming, where files are found. */
	val completing: AliasCompletion? by lazy {
		aliases.completing(read).takeIf { preferences.files && searchable }
	}

	/** Everything shown, in order: what the arrows move through. */
	val entries: List<Entry> by lazy { sections.flatMap { it.entries } }

	/** Which of [entries] the arrows are on: the first, where the one they were on is no longer shown. */
	val highlighted: Int by lazy {
		entries.indexOfFirst { it.key == arrowsOn }.coerceAtLeast(0)
	}

	/** The entry the arrows are on; none where nothing is shown. */
	val current: Entry? get() = entries.getOrNull(highlighted)

	/** This, with the arrows on the entry at [at], or on the nearest to it there is. */
	fun arrowsAt(at: Int): LauncherState =
		copy(
			arrowsOn = entries.getOrNull(at.coerceIn(0, (entries.size - 1).coerceAtLeast(0)))?.key,
		)

	/** Each section's title, by where its first entry is among [entries]. */
	private val headings: Map<Int, String> by lazy {
		sections
			.runningFold(0) { before, section -> before + section.entries.size }
			.zip(sections) { start, section -> start to section.title }
			.toMap()
	}

	/** The title over the entry at [at], where it is the first of its section. */
	fun headingAt(at: Int): String? = headings[at]

	/** Whether files are being looked for: the search for [query] is not back. */
	val searching: Boolean
		get() =
			preferences.files && searchable && wordsOf(query).isNotEmpty() && files.query != query

	/** What is typed, as a name is compared with it: without the space around it, in lower case. */
	private val asked: String by lazy { query.trim().lowercase() }

	/** Whether [entry] is named exactly what is typed, whatever the case. */
	private fun named(entry: Entry): Boolean = entry.title.lowercase() == asked

	/**
	 * [scored], the best first, and by name among equals; used ones before the rest of theirs. One named exactly
	 * what is typed comes before them all, however much another has been used.
	 */
	private fun ranked(scored: List<Scored>): List<Entry> =
		scored
			.sortedWith(
				compareByDescending<Scored> { it.title == asked }
					.thenByDescending { it.score + launches.pointsOf(it.entry.key, now) }
					.thenBy { it.title },
			).map { it.entry }

	/** An entry that fits what is typed, and how well. */
	private class Scored(
		val entry: Entry,
		val score: Int,
	) {
		/** What equals are in the order of. */
		val title: String = entry.title.lowercase()
	}

	/** [entry] with its [score], where it has one: where it fits what is typed at all. */
	private fun scored(entry: Entry, score: Int?): Scored? = score?.let { Scored(entry, it) }
}
