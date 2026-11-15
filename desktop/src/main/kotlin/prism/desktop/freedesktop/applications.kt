package prism.desktop.freedesktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import prism.desktop.Application
import prism.desktop.Applications
import prism.desktop.FileTypes
import prism.desktop.MimeType
import prism.shared.Format
import prism.shared.OnDisk
import prism.shared.Published
import prism.shared.Writers

/*
 * Applications as freedesktop.org's specifications say them. Each data folder's `applications/` holds desktop entries,
 * an entry's id being its path there with `/` made `-`; the user's folder first, an id it has taking the place of the
 * system's wholly, so that one that says the application is deleted leaves none (entries.kt). Which application opens a type is said by the `mimeapps.list` files, read in the order the MIME
 * Applications Associations specification gives (the user's settings, the desktop's own first; then the system's;
 * then the data folders'): the first default named for the type (or, failing that, for each type it is a kind of) that
 * is installed and opens it; failing those, the first that says it opens it, in the order the lists add them, then by
 * name. Setting a default writes the user's own `mimeapps.list`, as every desktop's settings do.
 */

/** The applications in [xdg]'s data folders, typed by [types]. */
class DesktopEntries(
	private val xdg: Xdg,
	private val types: FileTypes,
) : Applications {
	/** Every application installed, by id, whether a person is shown it or not; read once, again after a change. */
	private var installed: Map<String, Installed> by Published(read())

	/**
	 * What is installed: each id's entry in the first data folder that has one, the user's first, and what that
	 * entry says. An entry there that says the application is deleted, or is not one, or cannot be read, leaves the
	 * id with nothing installed, whatever a later folder's entry says: that is how a user takes one away.
	 */
	private fun read(): Map<String, Installed> =
		xdg.data
			.map { it.resolve("applications") }
			.filter { it.isDirectory() }
			.flatMap { folder ->
				entriesIn(folder).map { it.relativeTo(folder).toString().replace('/', '-') to it }
			}.distinctBy { (id, _) -> id }
			.mapNotNull { (id, file) ->
				KeyFile.read(file)?.let { installedOf(id, file, it, xdg) }?.let { id to it }
			}.toMap()

	private fun entriesIn(folder: Path): List<Path> =
		try {
			Files.walk(folder).use { files ->
				files.filter { it.extension == "desktop" && Files.isRegularFile(it) }.toList()
			}
		} catch (failed: IOException) {
			emptyList()
		}

	override fun all(): List<Application> =
		installed.values
			.filter { it.shown }
			.map { it.application }
			.sortedBy { it.name.lowercase() }

	/** The `mimeapps.list` files, in the order they are asked. */
	private fun lists(): List<Path> {
		val named = xdg.desktops.map { "$it-mimeapps.list" } + "mimeapps.list"

		return (xdg.config + xdg.data.map { it.resolve("applications") }).flatMap { folder ->
			named.map { folder.resolve(it) }
		}
	}

	/** What each list says, read now (they change: the user, and other programs, set defaults). */
	private fun associations(): List<KeyFile> = lists().mapNotNull { KeyFile.read(it) }

	override fun defaultFor(type: MimeType): Application? {
		val read = associations()

		for (each in types.lineage(type)) {
			val named =
				read.asSequence().flatMap {
					itemsOf(
						it["Default Applications"][each.name],
					).asSequence()
				}

			named.firstNotNullOfOrNull { installed[it]?.application }?.let { return it }
		}

		return forType(type, read).firstOrNull()
	}

	override fun forType(type: MimeType): List<Application> {
		val read = associations()
		val default = defaultFor(type)
		val all = forType(type, read)

		return listOfNotNull(default) + all.filter { it != default }
	}

	/** Those added for [type] by the lists, then those that say they open it, each type of its lineage in turn. */
	private fun forType(type: MimeType, read: List<KeyFile>): List<Application> {
		val removed =
			read.flatMap { itemsOf(it["Removed Associations"][type.name]) }.toSet()

		return types
			.lineage(type)
			.filter { it != MimeType.BINARY || type == MimeType.BINARY }
			.flatMap { each ->
				val added =
					read
						.flatMap {
							itemsOf(it["Added Associations"][each.name])
						}.mapNotNull { installed[it] }

				// Those a person is shown before those they are not (a service's own entry), then by name.
				val saying =
					installed.values
						.filter { each in it.application.types }
						.sortedWith(compareBy({ !it.shown }, { it.application.name.lowercase() }))

				(added + saying).map { it.application }
			}.distinct()
			.filter { it.id !in removed }
	}

	override fun setDefault(type: MimeType, application: Application) {
		// The desktop's file, and every program's that sets a default: changed as it is at that moment, and no
		// more of it than the two lines.
		OnDisk(xdg.configHome.resolve("mimeapps.list"), Format.TEXT, Writers.Anyone).update {
			val added =
				itemsOf(KeyFile.parse(this)["Added Associations"][type.name]).filter {
					it != application.id
				}

			withKey(
				withKey(this, "Default Applications", type.name, application.id),
				"Added Associations",
				type.name,
				(listOf(application.id) + added).joinToString(";", postfix = ";"),
			)
		}
	}

	/** [text] (a key file) with [key] in [group] set to [value]: the line replaced, or added to the group, or both. */
	private fun withKey(text: String, group: String, key: String, value: String): String {
		val lines = text.lines().dropLastWhile { it.isEmpty() }.toMutableList()
		val header = lines.indexOfFirst { it.trim() == "[$group]" }

		if (header < 0)
			return (lines + listOf("", "[$group]", "$key=$value"))
				.dropWhile {
					it.isEmpty()
				}.joinToString("\n", postfix = "\n")

		val end =
			(header + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") }
				?: lines.size

		val existing =
			(header + 1 until end).firstOrNull {
				lines[it].substringBefore('=').trim() ==
					key
			}

		if (existing != null)
			lines[existing] = "$key=$value"
		else
			lines.add(end, "$key=$value")

		return lines.joinToString("\n", postfix = "\n")
	}

	/** The entries read again: an application installed or removed meanwhile is seen. */
	override fun reread() {
		installed = read()
	}
}
