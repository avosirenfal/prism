package prism.portal

import java.nio.file.Path
import kotlinx.serialization.Serializable
import prism.dbus.Answer
import prism.dbus.Connection
import prism.dbus.Method
import prism.dbus.Reply
import prism.dbus.method

/*
 * The file manager, as freedesktop.org's applications ask one to show a file: `org.freedesktop.FileManager1`, served by
 * whichever file manager owns that name on the session bus. It is what a browser's "Show in folder" calls, and what the
 * portal's OpenURI calls when a sandboxed application asks for a folder to be opened. It is not a portal: the name is
 * the file manager's own, and the user's default file manager is whichever owns it (or is started for it, by the
 * service file D-Bus starts one by).
 */

/** What every one of its methods takes: files, as `file://` URIs, and the startup id for focusing the window. */
@Serializable
data class ShowCall(
	val uris: List<String>,
	val startupId: String,
)

/** What a file manager is asked to show. */
sealed interface Showing {
	val paths: List<Path>

	/** Folders, each in a window. */
	data class Folders(
		override val paths: List<Path>,
	) : Showing

	/** Files and folders, each shown in the folder it is in, chosen there. */
	data class Items(
		override val paths: List<Path>,
	) : Showing

	/** Files' properties. */
	data class Properties(
		override val paths: List<Path>,
	) : Showing
}

object FileManager {
	const val NAME = "org.freedesktop.FileManager1"
	const val PATH = "/org/freedesktop/FileManager1"
	const val INTERFACE = "org.freedesktop.FileManager1"

	val showFolders: Method<ShowCall, Unit> = method(null, PATH, INTERFACE, "ShowFolders")
	val showItems: Method<ShowCall, Unit> = method(null, PATH, INTERFACE, "ShowItems")
	val showItemProperties: Method<ShowCall, Unit> =
		method(null, PATH, INTERFACE, "ShowItemProperties")

	/** The same, as called on whichever file manager owns the name (starting one where D-Bus can). */
	val callShowFolders: Method<ShowCall, Unit> =
		method(NAME, PATH, INTERFACE, "ShowFolders")
	val callShowItems: Method<ShowCall, Unit> = method(NAME, PATH, INTERFACE, "ShowItems")
}

/**
 * The file manager served on [connection]: each call given to [show], which is told what to show and answers at once
 * (the caller waits for nothing but that it was asked). What is not a `file://` URI is left out.
 */
fun Connection.serveFileManager(show: (Showing) -> Unit): AutoCloseable {
	fun served(method: Method<ShowCall, Unit>, showing: (List<Path>) -> Showing) =
		serve(method) { called ->
			val paths = called.arguments.uris.mapNotNull(::pathOf)

			if (paths.isNotEmpty())
				show(showing(paths))

			Answer.Returned(Unit)
		}

	val served =
		listOf(
			served(FileManager.showFolders, Showing::Folders),
			served(FileManager.showItems, Showing::Items),
			served(FileManager.showItemProperties, Showing::Properties),
		)

	return AutoCloseable { served.forEach(AutoCloseable::close) }
}

/** [paths] shown by whichever file manager owns the name, folders as folders and files in theirs; whether it was. */
suspend fun Connection.showInFileManager(paths: List<Path>): Boolean {
	val (folders, files) =
		paths.partition {
			java.nio.file.Files
				.isDirectory(it)
		}

	suspend fun shown(method: Method<ShowCall, Unit>, these: List<Path>) =
		these.isEmpty() || call(method, ShowCall(these.map(::uriOf), "")) is Reply.Returned

	return shown(FileManager.callShowFolders, folders) &&
		shown(FileManager.callShowItems, files)
}
