package prism.launcher

import androidx.compose.runtime.Immutable
import java.nio.file.Path
import java.time.Instant
import prism.browser.Category
import prism.desktop.Application
import prism.desktop.ApplicationAction
import prism.preferences.LauncherPreferences

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

	/** An application, started. */
	data class App(
		val application: Application,
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
		override val kind: String get() = "Prism"
	}
}

/** What prism itself does from the launcher. */
enum class LauncherCommand(
	val label: String,
	val detail: String,
	/** Other words it is found by. */
	val keywords: List<String>,
) {
	Settings(
		"Settings",
		"How the launcher looks, what it finds, and its keys",
		listOf("launcher", "preferences", "options", "configure", "prism"),
	),
	Files(
		"Files",
		"Open a window on your home folder",
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
	/** Nothing asked for, or nothing back yet. */
	data object None : FilesFound

	/** The first [files] of [total], found for [query]. */
	data class Found(
		val query: String,
		val files: List<Entry.File>,
		val total: Int,
	) : FilesFound
}

/** The launcher. */
@Immutable
data class LauncherState(
	/** What is typed into its field. */
	val query: String = "",
	/** The applications installed that a person chooses from. */
	val applications: List<Application> = emptyList(),
	/** What has been started from here, and how lately. */
	val launches: Launches = Launches.NONE,
	val preferences: LauncherPreferences = LauncherPreferences(),
	/** What the index's search last came back with: for an older [query], until the one for this arrives. */
	val files: FilesFound = FilesFound.None,
	/** Whether there is an index to search for files. */
	val searchable: Boolean = true,
	/** Which of [entries] the arrows are on. */
	val highlighted: Int = 0,
	/** What went wrong with the last thing chosen, said until something else is typed or chosen. */
	val notice: String? = null,
	/** When this is, for how lately something was used. */
	val now: Instant = Instant.EPOCH,
) {
	/**
	 * What is shown, in groups: the applications (and their actions) that fit [query], the best first, then what
	 * prism does, then the files the index found for it. With nothing typed, every application, those used most and
	 * most lately first, and what prism does.
	 */
	val sections: List<Section> by lazy {
		val shown = applications.filterNot { it.id in preferences.hidden }
		val words = wordsOf(query)

		val programs =
			if (words.isEmpty())
				shown
					.sortedWith(
						compareByDescending<Application> { launches.weightOf(Entry.App(it).key, now) }
							.thenBy { it.name.lowercase() },
					).map(Entry::App)
			else
				ranked(
					shown.flatMap { application ->
						listOf(Matched(Entry.App(application), scoreOf(words, application))) +
							application.actions
								.takeIf { preferences.actions }
								.orEmpty()
								.map { action ->
									Matched(
										Entry.Action(application, action),
										scoreOf(words, application, action),
									)
								}
					},
				)

		val commands =
			ranked(
				LauncherCommand.entries.map { Matched(Entry.Command(it), scoreOf(words, it)) },
			)

		val found = (files as? FilesFound.Found)?.takeIf { words.isNotEmpty() }?.files.orEmpty()

		listOf(
			Section("Applications", programs),
			Section("Prism", commands),
			Section("Files", found),
		).filter { it.entries.isNotEmpty() }
	}

	/** Everything shown, in order: what the arrows move through. */
	val entries: List<Entry> by lazy { sections.flatMap { it.entries } }

	/** The entry the arrows are on; none where nothing is shown. */
	val current: Entry? get() = entries.getOrNull(highlighted)

	/** The title over the entry at [at], where it is the first of its section. */
	fun headingAt(at: Int): String? =
		sections
			.runningFold(0) { before, section -> before + section.entries.size }
			.zip(sections)
			.firstOrNull { (start, _) -> start == at }
			?.second
			?.title

	/** Whether files are being looked for: the search for [query] is not back. */
	val searching: Boolean
		get() =
			preferences.files && searchable && wordsOf(query).isNotEmpty() &&
				(files as? FilesFound.Found)?.query != query

	/** Those of [matched] that matched, the best first, and by name among equals; used ones before the rest of theirs. */
	private fun ranked(matched: List<Matched>): List<Entry> =
		matched
			.filter { it.score != null }
			.sortedWith(
				compareByDescending<Matched> { it.score!! + launches.pointsOf(it.entry.key, now) }
					.thenBy { it.entry.title.lowercase() },
			).map { it.entry }

	private class Matched(
		val entry: Entry,
		val score: Int?,
	)
}
