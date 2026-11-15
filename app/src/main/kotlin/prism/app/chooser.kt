package prism.app

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import prism.browser.AppKey
import prism.browser.Choosing
import prism.browser.Recents
import prism.browser.TypeFilter
import prism.browser.titleOf
import prism.desktop.FileTypes
import prism.desktop.MimeType
import prism.explorer.Answered
import prism.explorer.Asking
import prism.portal.Asked
import prism.portal.ChooserRequest
import prism.portal.FileFilter
import prism.portal.Parent
import prism.portal.Picked
import prism.portal.initialChoices
import prism.browser.Outcome as Ended

/*
 * A request of the portal's file chooser as prism's picker answers it (docs/portal.md): what the application asks
 * ([Asked]) made into a picker ([Asking]), and what the picker ends with made into what the portal is answered
 * ([Picked]). Both are plain functions of values, tested as such; the picker's window is the explorer's.
 */

/**
 * The picker [request] asks for, its filters' types made into names by [types]: for [asker], the application that
 * asked, where the portal could tell which, and opening where [startOf] says.
 */
fun askingOf(
	request: ChooserRequest,
	types: FileTypes,
	home: Path,
	recents: Recents = Recents(),
	asker: AppKey? = null,
): Asking {
	val asked = request.asked

	val choosing =
		when (asked) {
			is Asked.Opening ->
				when {
					asked.directory -> Choosing.Folder()
					asked.multiple -> Choosing.Files()
					else -> Choosing.OneFile()
				}
			is Asked.Saving ->
				Choosing.SaveAs(
					asked.name ?: asked.file?.fileName?.toString() ?: "",
				)
			is Asked.SavingMany -> Choosing.Folder()
		}

	val folder = startOf(asked, asker, recents, home)
	val (filters, filter) = filtersOf(asked, types)

	return Asking(
		title = asked.title.ifBlank { titleOf(choosing) },
		choosing = choosing,
		folder = folder,
		filters = filters.map { it.first },
		filter = filter,
		acceptLabel = asked.acceptLabel ?: "Save here".takeIf { asked is Asked.SavingMany },
		parent = (request.parent as? Parent.X11)?.window,
		asker = asker,
	)
}

/**
 * Where the picker for [asked] opens: in the folder the application names (the one to show, or the one the file it
 * would save over is in), where that is [there]; otherwise where [app] was last answered from that is still there
 * ([Recents.start]: a picker of files, in the folder of the file it was last answered with, and failing that the
 * folder it last was; then any application's, the same way; a picker of folders, by the folders alone); and with
 * none of those, at [home].
 */
internal fun startOf(
	asked: Asked,
	app: AppKey?,
	recents: Recents,
	home: Path,
	there: (Path) -> Boolean = { Files.isDirectory(it) },
): Path =
	when (asked) {
		is Asked.Opening -> asked.folder
		is Asked.Saving -> asked.folder ?: asked.file?.parent
		is Asked.SavingMany -> asked.folder
	}?.takeIf(there)
		?: recents.start(app, picksFiles = picksFiles(asked), there)
		?: home

/** Whether what [asked] asks for is files: to open, or one to save; not a folder, to open or to save many into. */
private fun picksFiles(asked: Asked): Boolean =
	when (asked) {
		is Asked.Opening -> !asked.directory
		is Asked.Saving -> true
		is Asked.SavingMany -> false
	}

/**
 * The picker's filters for [asked], each beside the portal's filter it was made from, and the one in use at first. A
 * rule by type is the type's name patterns, and those of its kinds; a filter none of whose rules says anything prism
 * can match by is left out, since a filter of no patterns would show every file. A filter the application says is in
 * use that is not among those it offers is offered first. With no filters, there is one, of every file.
 */
internal fun filtersOf(
	asked: Asked,
	types: FileTypes,
): Pair<List<Pair<TypeFilter, FileFilter?>>, TypeFilter?> {
	val (offered, current) =
		when (asked) {
			is Asked.Opening -> asked.filters to asked.currentFilter
			is Asked.Saving -> asked.filters to asked.currentFilter
			is Asked.SavingMany -> emptyList<FileFilter>() to null
		}

	val all =
		(
			listOfNotNull(
				current?.takeIf {
					it !in offered &&
						offered.none { o -> o.name == it.name }
				},
			) +
				offered
		).mapNotNull { filter -> typeFilterOf(filter, types)?.let { it to filter } }

	val chosen =
		current?.let { wanted ->
			all.firstOrNull { it.second == wanted }?.first
				?: all.firstOrNull { it.second.name == wanted.name }?.first
		}

	return if (all.isEmpty())
		listOf(TypeFilter.ALL_FILES to null) to null
	else
		all to chosen
}

/** [filter] as the picker's, its types made into name patterns by [types]; null where nothing in it can be matched. */
internal fun typeFilterOf(filter: FileFilter, types: FileTypes): TypeFilter? {
	val patterns =
		filter.rules
			.flatMap { rule ->
				when {
					rule.isGlob -> listOf(rule.pattern)
					rule.isMimeType -> types.patternsOf(MimeType(rule.pattern))
					else -> emptyList()
				}
			}.distinct()

	return when {
		patterns.isEmpty() -> null
		// Every file: what the picker's own filter of every file is, by no patterns.
		"*" in patterns -> TypeFilter(filter.name, emptyList())
		else -> TypeFilter(filter.name, patterns)
	}
}

/**
 * What the portal is answered, from what the picker [answered] for [request]. Saving several, the folder chosen is
 * where each file goes, each under its own name where nothing has it, and under a free one ("a (copy).txt") where
 * something does, as the portal allows. The filter in use is the portal's own it was made from.
 */
fun pickedOf(
	request: ChooserRequest,
	asking: Asking,
	answered: Answered,
	types: FileTypes,
): Picked {
	val chosen =
		when (val outcome = answered.outcome) {
			Ended.Cancelled -> return Picked.Cancelled
			is Ended.Chosen -> outcome.paths
		}
	val asked = request.asked
	val paths =
		when (asked) {
			is Asked.SavingMany -> placedIn(chosen.first(), asked.names)
			else -> chosen
		}
	val (filters, _) = filtersOf(asked, types)
	val filter = filters.firstOrNull { it.first == answered.filter }?.second

	return Picked.Chose(paths, filter, initialChoices(asked.choices))
}

/** Each of [names] in [folder]: as itself where nothing there has that name, or under the first free one. */
internal fun placedIn(folder: Path, names: List<String>): List<Path> {
	val taken = HashSet<String>()

	return names.map { name ->
		val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length

		val free =
			(
				sequenceOf(name, "${name.substring(0, dot)} (copy)${name.substring(dot)}") +
					generateSequence(2) {
						it + 1
					}.map { "${name.substring(0, dot)} (copy $it)${name.substring(dot)}" }
			).first {
				it !in taken &&
					!Files.exists(
						folder.resolve(it),
						LinkOption.NOFOLLOW_LINKS,
					)
			}

		taken += free
		folder.resolve(free)
	}
}
