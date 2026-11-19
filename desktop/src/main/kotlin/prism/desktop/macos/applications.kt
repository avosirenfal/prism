package prism.desktop.macos

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import prism.desktop.Application
import prism.desktop.ApplicationAction
import prism.desktop.Applications
import prism.desktop.MimeType
import prism.macos.Bundle
import prism.macos.UniformTypes
import prism.shared.Published
import prism.macos.Applications as LaunchServices

/*
 * The applications of a Mac: the bundles in the folders applications are installed in, each as its Info.plist says
 * it, and Launch Services for which opens a type and which opens it by default. An application is started by `open`,
 * which asks Launch Services to start it (or bring it forward, where it is running) as a process of its own.
 */

/** A Mac's applications, [types] saying what each opens as prism's types. */
class LaunchServicesApplications(
	private val types: UniformFileTypes,
	private val home: Path = Path.of(System.getProperty("user.home")),
) : Applications {
	/** What was read of the installed applications, until they are read again; none before they are first asked for. */
	private val read = Published<List<Application>?>(null)

	/**
	 * Where applications are installed: the user's own, the computer's, and the system's, each with its Utilities; and
	 * the applications the system keeps among its own services (Screen Sharing, Archive Utility), which Spotlight lists.
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
	 * Every application, as Spotlight finds them: whatever its own index holds as an application, wherever it is, and
	 * every pane of System Settings it holds, so that what Spotlight finds by name prism finds too. Where Spotlight
	 * says nothing (it is turned off, or has not indexed the disk yet), the folders applications are installed in, read
	 * as they are. An application inside another's bundle that runs with no window (a browser's helper) is left out.
	 * Two threads that ask at once may each read them: the same, and one is kept.
	 */
	override fun all(): List<Application> =
		read.now ?: run {
			val spotlit = spotlit(APPLICATION).sortedBy(::rankOf)

			val applications =
				if (spotlit.isEmpty())
					(
						folders.flatMap(::bundlesIn) +
							bundles.filter {
								it.isDirectory()
							}
					).mapNotNull(::applicationAt)
				else
					spotlit.mapNotNull { path ->
						LaunchServices
							.bundle(path)
							?.takeUnless { it.background && it.identifier != PRISM && insideABundle(path) }
							?.let(::applicationOf)
					}

			val panes = spotlit(SETTINGS_PANE).mapNotNull(::paneAt)

			(applications + panes)
				.distinctBy { it.id }
				.sortedBy { it.name.lowercase() }
				.also(read::set)
		}

	/** What Spotlight's index holds of the content [type], by path; none where it says nothing. */
	private fun spotlit(type: String): List<Path> =
		try {
			val process =
				ProcessBuilder(MDFIND, "-0", "kMDItemContentType == '$type'")
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start()
			val found = process.inputStream.readAllBytes().decodeToString()

			if (process.waitFor(SPOTLIGHT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0)
				found.split('\u0000').filter { it.isNotEmpty() }.map(Path::of)
			else
				emptyList()
		} catch (failed: IOException) {
			emptyList()
		}

	/** Of two copies of one application, which is listed: the one in an applications folder before any other. */
	private fun rankOf(path: Path): Int =
		folders.indexOfFirst { path.startsWith(it) }.let {
			if (it <
				0
			) folders.size else it
		}

	/** Whether [path] is inside another bundle: an application's helper, kept in its Contents. */
	private fun insideABundle(path: Path): Boolean =
		generateSequence(path.parent) { it.parent }.any {
			it.fileName?.toString()?.endsWith(".app") ==
				true
		}

	/** The pane of System Settings at [path], as an application that opens System Settings at it. */
	private fun paneAt(path: Path): Application? =
		LaunchServices.bundle(path)?.let { bundle ->
			Application(
				id = bundle.identifier ?: path.toString(),
				name = bundle.name.removeSuffix(".prefPane"),
				types = emptySet(),
				command = listOf(OPEN, path.toString()),
				inTerminal = false,
				icon = path.toString(),
				source = path,
				generic = "System Settings",
			)
		}

	override fun reread() = read.set(null)

	override fun forType(type: MimeType): List<Application> {
		val identifier = types.uniformOf(type)?.identifier ?: return emptyList()
		val default = defaultFor(type)
		val others = LaunchServices.forType(identifier).mapNotNull(::applicationAt)

		return (listOfNotNull(default) + others).distinctBy { it.id }
	}

	override fun defaultFor(type: MimeType): Application? =
		types
			.uniformOf(type)
			?.identifier
			?.let(LaunchServices::defaultFor)
			?.let(::applicationAt)

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

	/** The application of the bundle at [path]: none for one that is not, or runs with no window anyone starts. */
	private fun applicationAt(path: Path): Application? =
		LaunchServices
			.bundle(path)
			?.takeUnless {
				it.background && it.identifier != PRISM
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

		/** Spotlight's index, asked from the command line. */
		private const val MDFIND = "/usr/bin/mdfind"

		/** What Spotlight calls an application, and a pane of System Settings. */
		private const val APPLICATION = "com.apple.application-bundle"
		private const val SETTINGS_PANE = "com.apple.systempreference.prefpane"

		/** How long Spotlight is waited for. */
		private const val SPOTLIGHT_SECONDS = 10L

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
