package prism.desktop.macos

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import prism.desktop.Application
import prism.desktop.ApplicationAction
import prism.desktop.Applications
import prism.desktop.MimeType
import prism.macos.Bundle
import prism.macos.BundlePath
import prism.macos.Catalogue
import prism.macos.KeptBundles
import prism.macos.Revision
import prism.macos.UniformTypes
import prism.shared.Published
import prism.macos.Applications as LaunchServices

/*
 * The applications of a Mac: the bundles in the folders applications are installed in, each as its Info.plist says
 * it, and Launch Services for which opens a type and which opens it by default. An application is started by `open`,
 * which asks Launch Services to start it (or bring it forward, where it is running) as a process of its own.
 */

/** Where a Mac's applications are found. */
sealed interface ApplicationSource {
	/** The folders applications are installed in, read when they are asked for: what a prism with no daemon has. */
	data object Folders : ApplicationSource

	/**
	 * The bundles a daemon keeps of its index ([Catalogue]): every application on the disk, wherever it is, kept as
	 * the disk changes. The folders, until the index has first been read.
	 */
	class Kept(
		val catalogue: () -> Catalogue,
	) : ApplicationSource
}

/** A Mac's applications, found as [source] says, [types] saying what each opens as prism's types. */
class LaunchServicesApplications(
	private val types: UniformFileTypes,
	private val source: ApplicationSource = ApplicationSource.Folders,
	private val home: Path = Path.of(System.getProperty("user.home")),
) : Applications {
	/** What was read of the folders, until they are read again; none before they are first asked for. */
	private val read = Published<List<Application>?>(null)

	/** A bundle as it was last read, and the revision of it that was: read again only once it has a newer one. */
	private data class Known(
		val revision: Revision,
		val application: Application?,
	)

	/** What was read of each bundle kept: what a bundle says of itself is read again only when it changes. */
	private val known = Published<Map<BundlePath, Known>>(emptyMap())

	/**
	 * Where applications are installed: the user's own, the computer's, and the system's, each with its Utilities; and
	 * the applications the system keeps among its own services (Screen Sharing, Archive Utility).
	 */
	private val folders: List<Path>
		get() =
			listOf(
				home.resolve("Applications"),
				Path.of("/Applications"),
				Path.of("/Applications/Utilities"),
				Path.of("/System/Applications"),
				Path.of("/System/Applications/Utilities"),
				Path.of("/System/Library/CoreServices/Applications"),
			)

	/**
	 * Applications kept where nothing else in the folder is one to list: Finder, among the system's services and agents
	 * (/System/Library/CoreServices), which are listed one by one and not by their folder.
	 */
	private val bundles: List<Path>
		get() = listOf(Path.of("/System/Library/CoreServices/Finder.app"))

	/**
	 * Every application: as a daemon keeps them, where one does and has read its index; and as the folders they are
	 * installed in hold them, where not. Two threads that ask at once may each read them: the same, and one is kept.
	 */
	override fun all(): List<Application> =
		when (source) {
			ApplicationSource.Folders -> inFolders()
			is ApplicationSource.Kept ->
				when (val catalogue = source.catalogue()) {
					Catalogue.Unread -> inFolders()
					is Catalogue.Read -> kept(catalogue.kept)
				}
		}

	/** The applications in the folders they are installed in, as they were last read. */
	private fun inFolders(): List<Application> =
		read.now
			?: listed(
				(folders.flatMap(::bundlesIn) + bundles.filter { it.isDirectory() })
					.mapNotNull(BundlePath::of)
					.mapNotNull { applicationAt(it, Listing.Installed) },
			).also(read::set)

	/**
	 * The applications of the bundles [kept]: each as it was last read, and read again where it has changed since. A
	 * bundle in an applications folder is listed before another copy of the same application elsewhere.
	 */
	private fun kept(kept: KeptBundles): List<Application> {
		val before = known.now
		val now =
			kept.revisions.mapValues { (bundle, revision) ->
				before[bundle]?.takeIf { it.revision == revision }
					?: Known(revision, applicationAt(bundle, Listing.Anywhere))
			}

		known.set(now)

		return listed(
			now.entries
				.sortedBy {
					rankOf(it.key.path)
				}.mapNotNull { it.value.application },
		)
	}

	/** [applications] as they are listed: each once, by its name. */
	private fun listed(applications: List<Application>): List<Application> =
		applications.distinctBy { it.id }.sortedBy { it.name.lowercase() }

	/** Of two copies of one application, which is listed: the one in an applications folder before any other. */
	private fun rankOf(path: Path): Int =
		folders.indexOfFirst { path.startsWith(it) }.let { if (it < 0) folders.size else it }

	override fun reread() = read.set(null)

	override fun forType(type: MimeType): List<Application> {
		val identifier = types.uniformOf(type)?.identifier ?: return emptyList()
		val default = defaultFor(type)
		val others = LaunchServices.forType(identifier).mapNotNull(::installedAt)

		return (listOfNotNull(default) + others).distinctBy { it.id }
	}

	override fun defaultFor(type: MimeType): Application? =
		types
			.uniformOf(type)
			?.identifier
			?.let(LaunchServices::defaultFor)
			?.let(::installedAt)

	override fun setDefault(type: MimeType, application: Application) {
		val identifier =
			types.uniformOf(type)?.identifier
				?: throw IOException(
					"macOS has no type for $type, so no application can be its default.",
				)

		LaunchServices.setDefault(identifier, application.id)?.let { throw IOException(it) }
	}

	/** The application bundles in [folder]; none where it is not there. */
	private fun bundlesIn(folder: Path): List<Path> =
		try {
			if (folder.isDirectory())
				folder.listDirectoryEntries().filter {
					it.extension == "app" &&
						Files.isDirectory(
							it,
						)
				}
			else
				emptyList()
		} catch (unreadable: IOException) {
			emptyList()
		}

	/** Where a bundle was found, which is what says whether one that runs with no window is listed. */
	private enum class Listing {
		/** In a folder applications are installed in: listed only where it has a window (no agent of the system's). */
		Installed,

		/**
		 * Anywhere on the disk: listed but where it is inside another's bundle and has no window (a browser's
		 * helper), so that a menu bar's application, which has none either, is found.
		 */
		Anywhere,
	}

	/** The application at [path], as Launch Services names one: one that has a window, as an installed one is. */
	private fun installedAt(path: Path): Application? =
		BundlePath.of(path)?.let { applicationAt(it, Listing.Installed) }

	/** The application of [bundle], found as [listing] says; none for one that is not, or is not to be listed. */
	private fun applicationAt(bundle: BundlePath, listing: Listing): Application? =
		LaunchServices
			.bundle(bundle.path)
			?.takeUnless {
				it.background && it.identifier != PRISM &&
					when (listing) {
						Listing.Installed -> true
						Listing.Anywhere -> bundle.inAnother
					}
			}?.let(::applicationOf)

	private fun applicationOf(bundle: Bundle): Application =
		Application(
			id = bundle.identifier ?: bundle.path.toString(),
			name = bundle.name,
			types =
				(
					bundle.opens.mapNotNull {
						UniformTypes
							.ofIdentifier(
								it,
							)?.let(UniformFileTypes::mimeOf)
					} +
						bundle.opensMime.map(::MimeType)
				).toSet(),
			command = listOf(OPEN, "-a", bundle.path.toString()),
			inTerminal = false,
			icon = bundle.path.toString(),
			source = bundle.path,
			generic = bundle.category?.let(::categoryName),
			actions = actionsOf(bundle),
		)

	/**
	 * What else an application can be started to do. A Mac's bundles do not say, as a desktop entry does; prism knows
	 * its own, which has "New Window": its program run with `explore`, which asks the prism running for a window.
	 */
	private fun actionsOf(bundle: Bundle): List<ApplicationAction> =
		bundle.executable
			?.takeIf { bundle.identifier == PRISM }
			?.let {
				listOf(
					ApplicationAction("new-window", "New Window", listOf(it.toString(), "explore")),
				)
			}.orEmpty()

	companion object {
		/** What asks Launch Services to open: an application, or files with one. */
		const val OPEN = "/usr/bin/open"

		/** prism's own bundle, by its identifier: listed though it starts in no Dock, and known to have "New Window". */
		const val PRISM = "dev.prism"

		/** What a bundle's category says, as a person would: `public.app-category.developer-tools` is "Developer Tools". */
		fun categoryName(category: String): String =
			category
				.substringAfter("app-category.")
				.split('-')
				.joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
	}
}
