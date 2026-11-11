package prism.browser

import androidx.compose.runtime.Immutable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Places: what a window shows that is not a folder on disk (docs/menus.md). A place is listed by a command, once: it
 * prints every entry, each with where it sits in the place (slashes make folders) and, where there is one, the file on
 * disk behind it. The browser then goes about the tree it was given without asking the command again.
 *
 * What a row is decides what can be done with it. A row that is a file on disk ([Item.OnDisk]) is a file wherever it is
 * shown: opened, copied, renamed, put in the trash. A row that only its place knows ([Item.InPlace]) can be gone into,
 * if it is a folder, and otherwise only what the place's own actions do can be done to it. A folder with entries under
 * it in a place is always the place's own grouping, even where a folder on disk has its name: what would be done to it
 * must be what is shown in it, and a folder shown with two of its four hundred files is not that folder.
 *
 * For example, `git diff --name-only` in a repository prints
 *
 * ```
 * browser/step.kt
 * docs/menus.md
 * ```
 *
 * which, read as paths under the repository, are two files on disk; shown as a tree, they are under two folders,
 * `browser` and `docs`, which are groupings: gone into, and nothing else.
 */

/** What a row is: a file or a folder on disk, or an entry of the place shown, which is nowhere on disk. */
@Immutable
sealed interface Item {
	data class OnDisk(
		val path: Path,
	) : Item

	/** An entry of a place, by what the place's command called it ([key]), which is what its commands are given. */
	data class InPlace(
		val key: String,
	) : Item
}

/** One entry of a place, as its command printed it. */
@Immutable
data class PlaceEntry(
	/** What the command called it: its path, or its place in the tree, exactly as printed. */
	val key: String,
	/** Where it sits in the place: the names of the folders it is under, outermost first, then its own. */
	val at: List<String>,
	/** The file on disk behind it, where there is one. */
	val path: Path?,
	val folder: Boolean,
	val size: Long? = null,
	val modified: Instant? = null,
)

/** Everything a place holds, as one listing of it gave: gone about without listing it again. A tree is equal only to itself. */
class PlaceTree(
	val entries: List<PlaceEntry>,
) {
	private class Node(
		val entry: PlaceEntry,
		/** Whether the place has entries under it: a grouping, whatever is on disk. */
		var grouping: Boolean = false,
	)

	/** Each folder's entries, by where the folder sits: the listed, and the folders their places imply. */
	private val children: Map<List<String>, List<Node>> by lazy {
		val under = LinkedHashMap<List<String>, LinkedHashMap<String, Node>>()
		val groups = HashMap<List<String>, Node>()

		fun group(at: List<String>): Node? =
			if (at.isEmpty())
				null
			else
				groups.getOrPut(at) {
					group(at.dropLast(1))
					Node(PlaceEntry(at.joinToString("/", postfix = "/"), at, null, folder = true)).also {
						under.getOrPut(at.dropLast(1), ::LinkedHashMap)[it.entry.key] = it
					}
				}

		// Folders the command listed itself first, so that what it said of them (a date) is kept.
		for (entry in entries.filter { it.folder && it.at.isNotEmpty() })
			if (entry.at !in groups) {
				group(entry.at.dropLast(1))?.grouping = true
				Node(entry).also {
					groups[entry.at] = it
					under.getOrPut(entry.at.dropLast(1), ::LinkedHashMap)[entry.key] = it
				}
			}

		for (entry in entries.filter { !it.folder && it.at.isNotEmpty() }) {
			group(entry.at.dropLast(1))?.grouping = true
			under.getOrPut(entry.at.dropLast(1), ::LinkedHashMap)[entry.key] = Node(entry)
		}

		// A folder implied by what is under it is a grouping; so is one above another.
		for ((at, node) in groups)
			if (under[at]?.isNotEmpty() == true)
				node.grouping = true

		under.mapValues { it.value.values.toList() }
	}

	/** Whether there is a folder at [within] to be in: the place itself, or a grouping in it. */
	fun has(within: List<String>): Boolean = within.isEmpty() || within in children

	/** The rows of the folder at [within], in no particular order, their dates said in [zone]. */
	fun rows(within: List<String>, zone: ZoneId = ZoneId.systemDefault()): List<Row> =
		children[within].orEmpty().map { node ->
			val entry = node.entry
			val name = entry.at.last()
			val category =
				if (entry.folder)
					Category.Folder
				else
					categoryOf(name)

			Row(
				id = RowId.Entry(entry.key),
				// A grouping is the place's own, whatever path was printed for it.
				item = entry.path?.takeUnless { node.grouping }?.let(Item::OnDisk) ?: Item.InPlace(entry.key),
				name = name,
				category = category,
				type = typeOf(name, category),
				size = entry.size?.takeUnless { entry.folder }?.let { Shown(it, sizeOf(it)) },
				modified = entry.modified?.let { Shown(it, dateOf(it, zone)) },
				hidden = name.startsWith("."),
			)
		}

	override fun toString(): String = "PlaceTree(${entries.size} entries)"
}

/** What listing a place came to. */
sealed interface PlaceListing {
	data class Listed(
		val tree: PlaceTree,
	) : PlaceListing

	data class Failed(
		val why: String,
	) : PlaceListing
}

/** A command to run: its words, the folder it runs in, and the values with names, which it is given as its environment. */
@Immutable
data class Invocation(
	val words: List<String>,
	val folder: Path,
	val environment: Map<String, String> = emptyMap(),
)

/** What a command that was waited for came to. */
@Immutable
data class Ran(
	/** Its exit status; null where it could not be started at all. */
	val status: Int?,
	val output: String,
	val errors: String,
) {
	val worked: Boolean get() = status == 0

	/** Why it did not work, in a line or a few: what it wrote to standard error, or that it wrote nothing. */
	val why: String get() =
		errors
			.trim()
			.lines()
			.takeLast(6)
			.joinToString("\n")
			.ifBlank { "It ended with status $status and said nothing." }
}

/** What runs the commands prism waits for: a place's listing, and an action's watched command. */
fun interface Commands {
	fun run(invocation: Invocation): Ran
}

/** Commands run as processes, with nothing of prism's to read, and all they write kept. */
object ProcessCommands : Commands {
	override fun run(invocation: Invocation): Ran =
		try {
			val process =
				ProcessBuilder(invocation.words)
					.directory(invocation.folder.toFile())
					.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
					.apply { environment().putAll(invocation.environment) }
					.start()

			// Both read at once: a command that fills one pipe while the other is read would wait for ever.
			val errors = StringBuilder()
			val reader =
				Thread {
					try {
						errors.append(process.errorStream.bufferedReader().readText())
					} catch (closed: IOException) {
						Unit
					}
				}.apply {
					isDaemon = true
					start()
				}
			val output = process.inputStream.bufferedReader().readText()
			val status = process.waitFor()

			reader.join()
			Ran(status, output, errors.toString())
		} catch (failed: IOException) {
			Ran(null, "", failed.message ?: failed.toString())
		} catch (stopped: InterruptedException) {
			Thread.currentThread().interrupt()
			Ran(null, "", "It was stopped.")
		}
}

/** [placed] listed by [commands], from [home] where its place names no folder to run in. */
fun listingOf(placed: Placed, commands: Commands, home: Path): PlaceListing {
	val filling = Filling(emptyList(), null, placed.values)
	val folder =
		placed.type.folder?.let {
			it.fill(filling)?.let { text -> pathOf(text, home, home) }
				?: return PlaceListing.Failed("The folder to list it in could not be worked out.")
		} ?: home
	val words =
		placed.type.list.fill(filling.copy(folder = folder))?.singleOrNull()
			?: return PlaceListing.Failed("Its command names something this place was not opened with.")
	val ran = commands.run(Invocation(words, folder, placed.values))

	return if (ran.worked)
		PlaceListing.Listed(PlaceTree(entriesOf(ran.output, placed.type, folder)))
	else
		PlaceListing.Failed(ran.why)
}

/** The entries [output] lists, read as [type] says its command prints them; relative paths are in [base]. */
fun entriesOf(output: String, type: PlaceType, base: Path): List<PlaceEntry> {
	val printed =
		(
			if (type.nul)
				output.split('\u0000')
			else
				output.lines()
		).filter { it.isNotBlank() }

	return when (type.read) {
		PlaceRead.Paths ->
			printed.mapNotNull { line ->
				val path = resolved(line, base) ?: return@mapNotNull null
				val read = attributesOf(path)
				val below = path.takeIf { type.tree && it.startsWith(base) && it != base }?.let(base::relativize)

				PlaceEntry(
					key = path.toString(),
					at = below?.map { it.toString() } ?: listOf(path.fileName?.toString() ?: path.toString()),
					path = path,
					folder = read?.isDirectory ?: false,
					size = read?.size(),
					modified = read?.lastModifiedTime()?.toInstant(),
				)
			}
		PlaceRead.Names ->
			printed.mapNotNull { line ->
				val fields = line.split('\t')
				val said =
					fields.drop(1).mapNotNull { field ->
						field.indexOf('=').takeIf { it > 0 }?.let { field.substring(0, it) to field.substring(it + 1) }
					}.toMap()

				entryOf(fields.first(), said["path"], said["kind"], said["size"]?.toLongOrNull(), said["time"], base)
			}
		PlaceRead.Entries ->
			printed.mapNotNull { line ->
				val said =
					try {
						Json.parseToJsonElement(line) as? JsonObject
					} catch (notJson: SerializationException) {
						null
					} ?: return@mapNotNull null

				fun text(name: String) = (said[name] as? JsonPrimitive)?.contentOrNull

				val path = text("path")
				val at = text("at") ?: path ?: return@mapNotNull null

				entryOf(
					at,
					path,
					text("kind") ?: "folder".takeIf { (said["folder"] as? JsonPrimitive)?.booleanOrNull == true },
					(said["size"] as? JsonPrimitive)?.longOrNull,
					text("time"),
					base,
				)
			}
	}
}

/** An entry printed as sitting at [at] (a `/` after it says a folder), with what else was said of it. */
private fun entryOf(
	at: String,
	path: String?,
	kind: String?,
	size: Long?,
	time: String?,
	base: Path,
): PlaceEntry? {
	val names = at.split('/').filter { it.isNotEmpty() && it != "." }
	val real = path?.let { resolved(it, base) }

	if (names.isEmpty())
		return null

	return PlaceEntry(
		key = at,
		at = names,
		path = real,
		folder = at.endsWith("/") || kind == "folder" || (kind == null && real?.let(Files::isDirectory) == true),
		size = size,
		modified = time?.let(::timeOf),
	)
}

private fun resolved(text: String, base: Path): Path? =
	try {
		base.resolve(text).normalize()
	} catch (bad: InvalidPathException) {
		null
	}

private fun attributesOf(path: Path): BasicFileAttributes? =
	try {
		Files.readAttributes(path, BasicFileAttributes::class.java)
	} catch (gone: IOException) {
		null
	}

private val TIMES =
	listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyyMMdd.HHmmss", "yyyyMMdd'T'HHmmss")
		.map(DateTimeFormatter::ofPattern)

/**
 * A time as a command prints one: seconds since 1970, an instant (`2026-01-02T03:04:05Z`), or a date and a time of
 * day in this machine's zone, as `tar` and `unzip` print them (`2026-01-02 03:04`, `20260102.030405`).
 */
fun timeOf(text: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
	text.toLongOrNull()?.let { return Instant.ofEpochSecond(it) }

	try {
		return Instant.parse(text)
	} catch (notAnInstant: DateTimeParseException) {
		Unit
	}

	return TIMES.firstNotNullOfOrNull {
		try {
			LocalDateTime.parse(text, it).atZone(zone).toInstant()
		} catch (notThis: DateTimeParseException) {
			null
		}
	}
}
