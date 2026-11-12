package prism.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import prism.desktop.freedesktop.Xdg
import prism.portal.FileChooser
import prism.portal.FileManager

/*
 * Making prism the desktop's file chooser and file manager, for one user, and undoing it (docs/portal.md). Nothing is
 * written outside the user's own folders, and everything written says it was prism's, so that undoing it takes away
 * what prism wrote and nothing else:
 *
 * - `xdg-desktop-portal/portals/prism.portal`, in the user's data folder: what tells the portal there is a backend
 *   called prism, which file chooser it serves, and on which bus name;
 * - two D-Bus service files, in the user's data folder: what starts `prism portal serve` when the portal (or anything)
 *   calls the backend's name or `org.freedesktop.FileManager1`, and nothing owns it yet;
 * - a desktop entry that opens folders (`prism portal show`), made the default for `inode/directory`, which is what
 *   `xdg-open` on a folder, and a desktop's "open folder", runs;
 * - one line in the user's `xdg-desktop-portal/portals.conf` (or the file for their desktop, where they have one),
 *   which prefers prism for the file chooser; every other interface is left to the files that say what it is.
 *
 * Each is a plain function of what is there now to what is to be there, so that it is tested on text.
 */

/** The marker every file prism writes carries: what tells uninstalling a file of prism's from one of another's. */
internal const val WRITTEN_BY = "# Written by prism portal install; prism portal uninstall takes it away."

/**
 * The first line of a portals.conf prism made, copied from the file the portal read before it: a portal older than
 * 1.21 reads one file alone, so a file of prism's line alone would hide every other preference the desktop has.
 */
internal const val MADE_FROM = "# Made by prism portal install, from"

/** What saying that prism is the file chooser in a portals.conf looks like: the key, and prism's backend's name. */
internal const val CHOOSER_KEY = "org.freedesktop.impl.portal.FileChooser"

/** The name of prism's backend, as portals.conf names it: its `.portal` file's name. */
internal const val BACKEND_NAME = "prism"

/** The desktop entry that opens folders, by its file's name. */
internal const val FOLDER_ENTRY = "prism-folder.desktop"

/** A word of a command line, as D-Bus's service files and desktop entries take one: quoted where it must be. */
internal fun quoted(word: String): String =
	if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "/._-+=:,@%" })
		word
	else
		"\"" +
			word
				.replace(
					"\\",
					"\\\\",
				).replace("\"", "\\\"")
				.replace("$", "\\$")
				.replace("`", "\\`") +
			"\""

/** The `.portal` file of prism's backend. */
internal fun portalFile(): String =
	"""
	|$WRITTEN_BY
	|[portal]
	|DBusName=${FileChooser.BACKEND}
	|Interfaces=${FileChooser.INTERFACE};
	|
	""".trimMargin()

/** A D-Bus service file: what D-Bus starts, [command] and its words, when something calls [name] and none has it. */
internal fun serviceFile(name: String, command: List<String>): String =
	"""
	|$WRITTEN_BY
	|[D-BUS Service]
	|Name=$name
	|Exec=${command.joinToString(" ", transform = ::quoted)}
	|
	""".trimMargin()

/** The desktop entry that opens folders: `prism portal show`, given what is opened. */
internal fun folderEntry(command: List<String>): String =
	"""
	|$WRITTEN_BY
	|[Desktop Entry]
	|Type=Application
	|Name=prism
	|GenericName=File Manager
	|Comment=Browse the file system
	|Exec=${(command + listOf("portal", "show")).joinToString(" ", transform = ::quoted)} %U
	|MimeType=inode/directory;
	|Categories=System;FileTools;FileManager;
	|Terminal=false
	|
	""".trimMargin()

/** What a portals.conf says of the file chooser: prism's line, and the line it took the place of, if any. */
internal data class ChooserLine(
	val text: String,
	/** What the file preferred for the file chooser before prism did; null where it said nothing. */
	val before: String?,
)

/**
 * [text], a portals.conf (or none, for a file that is not there), preferring prism for the file chooser: the key set
 * in `[preferred]` (the group made where there is none), and what it said before kept in a comment above it, for
 * [withoutPrism] to put back. Everything else is as it was.
 */
internal fun withPrism(text: String?): ChooserLine {
	val lines = text?.lines()?.dropLastWhile { it.isEmpty() }.orEmpty()
	val group = lines.indexOfFirst { it.trim() == "[preferred]" }

	// Prism's already: as it is.
	if (group >= 0 && valueIn(lines, group) == BACKEND_NAME)
		return ChooserLine(text.orEmpty(), lines.firstNotNullOfOrNull(::beforeIn))

	val before =
		if (group >= 0)
			valueIn(lines, group)
		else
			null

	val ours =
		listOf(
			"# prism portal install: $CHOOSER_KEY was ${before ?: "(not set)"}",
			"$CHOOSER_KEY=$BACKEND_NAME",
		)

	val changed =
		if (group < 0) {
			lines + listOfNotNull("".takeIf { lines.isNotEmpty() }, "[preferred]") + ours
		} else {
			val end = endOf(lines, group)
			val inGroup = lines.subList(group + 1, end).filterNot { isChooserLine(it) }

			lines.subList(0, group + 1) + ours + inGroup + lines.subList(end, lines.size)
		}

	return ChooserLine(changed.joinToString("\n", postfix = "\n"), before)
}

/**
 * [text] with prism's preference for the file chooser taken away, and what it took the place of put back. Null where
 * nothing is left in it but what prism wrote, for the file to be removed.
 */
internal fun withoutPrism(text: String): String? {
	// A file prism made, a copy of another's: gone, for the other to be read again.
	if (text.startsWith(MADE_FROM))
		return null

	val lines = text.lines().dropLastWhile { it.isEmpty() }
	val before = lines.firstNotNullOfOrNull(::beforeIn)
	val group = lines.indexOfFirst { it.trim() == "[preferred]" }

	if (group < 0 || valueIn(lines, group) != BACKEND_NAME)
		return text

	val end = endOf(lines, group)
	val inGroup =
		lines.subList(group + 1, end).filterNot {
			isChooserLine(it) || beforeIn(it) != null ||
				it.startsWith("# prism portal install:")
		}

	val restored =
		listOfNotNull(before?.takeIf { it != "(not set)" }?.let { "$CHOOSER_KEY=$it" }) +
			inGroup

	val rest =
		lines.subList(0, group) +
			(
				if (restored.any { it.isNotBlank() })
					listOf(lines[group]) + restored
				else
					emptyList()
			) +
			lines.subList(end, lines.size)

	return rest
		.joinToString("\n")
		.trim()
		.takeIf { it.isNotEmpty() }
		?.let { it + "\n" }
}

private fun isChooserLine(line: String): Boolean =
	line.substringBefore('=').trim() == CHOOSER_KEY

/** What a comment of prism's says the file chooser was before it. */
private fun beforeIn(line: String): String? =
	line
		.removePrefix("# prism portal install: $CHOOSER_KEY was ")
		.takeIf {
			it != line
		}?.trim()

/** The file chooser's value in the group whose header is at [group]. */
private fun valueIn(lines: List<String>, group: Int): String? =
	lines
		.subList(
			group + 1,
			endOf(lines, group),
		).firstOrNull(::isChooserLine)
		?.substringAfter('=')
		?.trim()

/** Where the group whose header is at [group] ends: at the next header, or the end. */
private fun endOf(lines: List<String>, group: Int): Int =
	(group + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") }
		?: lines.size

/**
 * Which portals.conf of the user's prism's line goes in: the portal reads, in each folder, the file for the first of
 * the session's desktops that has one, then `portals.conf`; so the user's own file for their desktop, where they have
 * one, or `portals.conf`.
 */
internal fun chooserConfigOf(xdg: Xdg): Path {
	val folder = xdg.configHome.resolve("xdg-desktop-portal")

	return xdg.desktops
		.map { folder.resolve("$it-portals.conf") }
		.firstOrNull { Files.exists(it) }
		?: folder.resolve("portals.conf")
}

/**
 * The portals.conf the portal reads now, for the session's desktops, where the user has none of their own: the first
 * of each folder's file for a desktop, then its `portals.conf`, in the folders in the order the portal looks in them.
 */
internal fun portalConfigNow(xdg: Xdg): Path? {
	val folders =
		(xdg.config.drop(1) + Path.of("/etc") + xdg.data + Path.of("/usr/share"))
			.map { it.resolve("xdg-desktop-portal") }
			.distinct()

	return folders.firstNotNullOfOrNull { folder ->
		(xdg.desktops.map { "$it-portals.conf" } + "portals.conf")
			.map(folder::resolve)
			.firstOrNull { Files.isRegularFile(it) }
	}
}

/** What installing remembers, to undo it: the application that opened folders before prism did. */
@Serializable
internal data class Installed(
	val folderHandler: String? = null,
)

/** Where [Installed] is kept: in the user's state folder. */
internal fun installedFile(xdg: Xdg): Path =
	(
		xdg["XDG_STATE_HOME"]?.takeIf { it.startsWith("/") }?.let { Path.of(it) }
			?: xdg.home.resolve(".local/state")
	).resolve("prism/portal-install.json")

internal fun readInstalled(file: Path): Installed =
	try {
		Json.decodeFromString(Installed.serializer(), Files.readString(file))
	} catch (missing: IOException) {
		Installed()
	} catch (unreadable: SerializationException) {
		Installed()
	} catch (unreadable: IllegalArgumentException) {
		Installed()
	}

/** The files installing writes, by where they go, for [command]: each prism's, by its marker. */
internal fun installedFiles(
	xdg: Xdg,
	command: List<String>,
	chooser: Boolean,
	manager: Boolean,
): Map<Path, String> {
	val data = xdg.dataHome
	val serve = command + listOf("portal", "serve")

	return buildMap {
		if (chooser) {
			put(data.resolve("xdg-desktop-portal/portals/$BACKEND_NAME.portal"), portalFile())
			put(
				data.resolve("dbus-1/services/${FileChooser.BACKEND}.service"),
				serviceFile(FileChooser.BACKEND, serve),
			)
		}
		if (manager) {
			put(
				data.resolve("dbus-1/services/${FileManager.NAME}.service"),
				serviceFile(FileManager.NAME, serve),
			)
			put(data.resolve("applications/$FOLDER_ENTRY"), folderEntry(command))
		}
	}
}

/** Whether [file] is one prism wrote: there, and carrying the marker. */
internal fun isPrisms(file: Path): Boolean =
	try {
		Files.readString(file).lines().firstOrNull() == WRITTEN_BY
	} catch (missing: IOException) {
		false
	}
