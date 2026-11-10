package prism.desktop.freedesktop

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import prism.desktop.Application
import prism.desktop.Applications
import prism.desktop.FileTypes
import prism.desktop.MimeType

/*
 * Applications as freedesktop.org's specifications say them. Each data folder's `applications/` holds desktop entries,
 * an entry's id being its path there with `/` made `-`; the user's folder first, an id it has taking the place of the
 * system's. Which application opens a type is said by the `mimeapps.list` files, read in the order the MIME
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
	@Volatile
	private var installed: Map<String, Installed> = read()

	private class Installed(
		val application: Application,
		val shown: Boolean,
	)

	private fun read(): Map<String, Installed> =
		xdg.data
			.map { it.resolve("applications") }
			.filter { it.isDirectory() }
			.reversed()
			.flatMap { folder ->
				entriesIn(folder).mapNotNull { file ->
					val id = file.relativeTo(folder).toString().replace('/', '-')
					val entry = KeyFile.read(file) ?: return@mapNotNull null

					applicationOf(id, file, entry)?.let { id to Installed(it, !hiddenFromMenus(entry)) }
				}
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
		val file = xdg.configHome.resolve("mimeapps.list")
		val text =
			try {
				Files.readString(file)
			} catch (missing: IOException) {
				""
			}

		val added =
			itemsOf(KeyFile.parse(text)["Added Associations"][type.name]).filter {
				it !=
					application.id
			}

		val updated =
			withKey(
				withKey(text, "Default Applications", type.name, application.id),
				"Added Associations",
				type.name,
				(listOf(application.id) + added).joinToString(";", postfix = ";"),
			)

		write(file, updated)
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

	private fun write(file: Path, text: String) {
		val beside = file.resolveSibling("${file.fileName}.new")

		Files.createDirectories(file.parent)
		Files.writeString(beside, text)
		try {
			Files.move(beside, file, StandardCopyOption.ATOMIC_MOVE)
		} catch (unsupported: AtomicMoveNotSupportedException) {
			Files.move(beside, file, StandardCopyOption.REPLACE_EXISTING)
		}
	}

	/** The entries read again: an application installed or removed meanwhile is seen. */
	fun reread() {
		installed = read()
	}
}
