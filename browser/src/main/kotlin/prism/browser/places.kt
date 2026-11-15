package prism.browser

import androidx.compose.runtime.Immutable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import prism.desktop.asChildOfPrism
import prism.index.Given
import prism.index.Index
import prism.index.given

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

	/** An entry of a trash, which is put back or deleted for good, and nothing else. */
	data class InTrash(
		val trashed: Trashed,
		/** When it was trashed, as its record says. */
		val deleted: java.time.Instant?,
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

		// Every folder something sits under, before the files: a tool that lists a folder with nothing to say it is
		// one lists what looks like a file where a folder is, which is that folder and is not shown twice.
		for (entry in entries)
			group(entry.at.dropLast(1))?.grouping = true

		for (entry in entries.filter { !it.folder && it.at.isNotEmpty() && it.at !in groups })
			under.getOrPut(entry.at.dropLast(1), ::LinkedHashMap)[entry.key] = Node(entry)

		// A folder implied by what is under it is a grouping; so is one above another.
		for ((at, node) in groups)
			if (under[at]?.isNotEmpty() == true)
				node.grouping = true

		under.mapValues { it.value.values.toList() }
	}

	/** Every entry, by where it sits. */
	private val sitting: Map<List<String>, Node> by lazy {
		children.values.flatten().associateBy { it.entry.at }
	}

	private val keyed: Map<String, Node> by lazy {
		children.values.flatten().associateBy { it.entry.key }
	}

	/**
	 * Everything the place holds as an index of its own, which is what a search in the place searches: the same
	 * search the index of the disk is given, over these entries and no others.
	 */
	val index: Index by lazy {
		Index.given(sitting.values.map { Given(it.entry.at, it.entry.folder) })
	}

	/** Where the entry its command called [key] sits. */
	fun at(key: String): List<String>? = keyed[key]?.entry?.at

	/** The entry sitting at [at], as a row; null where none does. */
	fun row(at: List<String>, zone: ZoneId = ZoneId.systemDefault()): Row? =
		sitting[at]?.let { rowOf(it, zone) }

	/** The rows of the folder at [within], in no particular order, their dates said in [zone]. */
	fun rows(within: List<String>, zone: ZoneId = ZoneId.systemDefault()): List<Row> =
		children[within].orEmpty().map { rowOf(it, zone) }

	private fun rowOf(node: Node, zone: ZoneId): Row {
		val entry = node.entry
		val name = entry.at.last()
		val category =
			if (entry.folder)
				Category.Folder
			else
				categoryOf(name)

		return Row(
			id = RowId.Entry(entry.key),
			// A grouping is the place's own, whatever path was printed for it.
			item =
				entry.path?.takeUnless { node.grouping }?.let(Item::OnDisk)
					?: Item.InPlace(entry.key),
			name = name,
			category = category,
			type = typeOf(name, category),
			size = entry.size?.takeUnless { entry.folder }?.let { Shown(it, sizeOf(it)) },
			modified = entry.modified,
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
	/** Everything it wrote, to its output and to standard error, in the order it was written. */
	val said: String = errors,
) {
	val worked: Boolean get() = status == 0

	/**
	 * Why it did not work, for a person to read: how it ended, and the last of what it wrote. What it wrote to
	 * standard error where it wrote any there; otherwise everything, since some programs say what went wrong on their
	 * output.
	 */
	val why: String get() {
		val wrote =
			errors
				.ifBlank { said }
				.trim()
				.lines()
				.takeLast(SHOWN)
				.joinToString("\n")

		val ended =
			when (status) {
				null -> null
				else -> "Exited with status $status."
			}

		return listOfNotNull(
			ended.takeIf {
				wrote.isBlank() || status != null
			},
			wrote.ifBlank { null },
		).joinToString("\n\n")
			.ifBlank { "The command couldn't be run." }
	}

	private companion object {
		/** How many of the last lines a command wrote are kept to be shown when it fails. */
		const val SHOWN = 40
	}
}

/**
 * What [delivery] says to bring, brought: each entry moved from where the command put it to the folder it is wanted
 * in, by its own name; a folder there already is added to, and a file there already is left as it is. Why not, where
 * it could not be.
 */
fun delivered(delivery: Delivery): String? =
	try {
		Files.createDirectories(delivery.into)

		val sources =
			delivery.entries?.map { (key, name) ->
				val made = delivery.from.resolve(key.trim('/')).normalize()

				// Only what is under the folder the command worked in: a name that climbs out of it is not followed.
				if (!made.startsWith(delivery.from) || !Files.exists(made, LinkOption.NOFOLLOW_LINKS))
					return "“$name” wasn't found after the command ran."

				made to name
			}
				?: Files.list(delivery.from).use { all ->
					all.map { it to it.fileName.toString() }.toList()
				}

		sources.forEach { (made, name) -> merged(made, delivery.into.resolve(name)) }
		null
	} catch (failed: IOException) {
		failed.message ?: failed.toString()
	}

/** [made] moved to [target]: a folder into the folder there, entry by entry; a file only where nothing is. */
private fun merged(made: Path, target: Path) {
	if (Files.isDirectory(made, LinkOption.NOFOLLOW_LINKS)) {
		Files.createDirectories(target)
		Files
			.list(made)
			.use { all ->
				all.toList()
			}.forEach { merged(it, target.resolve(it.fileName.toString())) }
	} else if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS))
		Files.move(made, target)
}

/**
 * Who watches a command as it runs: told how to stop it once it has started, and each line it writes as it writes
 * it, so that a person waiting on it sees that it is running, what it says, and can end it.
 */
interface Watch {
	/** The command has started; [stop] ends it, and whatever it started. */
	fun started(stop: () -> Unit) = Unit

	/** A line it wrote: to standard error where [error], and otherwise to its output. */
	fun said(line: String, error: Boolean) = Unit

	companion object {
		/** Nobody watching. */
		val NONE = object : Watch {}
	}
}

/** What runs the commands prism waits for: a place's listing, and an action's watched command. */
fun interface Commands {
	fun run(invocation: Invocation, watch: Watch): Ran

	fun run(invocation: Invocation): Ran = run(invocation, Watch.NONE)
}

/** Commands run as processes, with nothing of prism's to read, and all they write kept and told as it is written. */
object ProcessCommands : Commands {
	override fun run(invocation: Invocation, watch: Watch): Ran =
		try {
			val process =
				ProcessBuilder(invocation.words)
					.asChildOfPrism()
					.directory(invocation.folder.toFile())
					.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
					.apply { environment().putAll(invocation.environment) }
					.start()

			val stopped = AtomicBoolean(false)

			// Ended with everything it started: a shell's pipeline is several processes, and each would run on.
			watch.started {
				stopped.set(true)
				process.descendants().forEach { it.destroy() }
				process.destroy()
			}

			val said = StringBuffer()

			fun read(stream: java.io.InputStream, error: Boolean): StringBuilder {
				val kept = StringBuilder()

				try {
					stream.bufferedReader().forEachLine { line ->
						kept.append(line).append('\n')
						said.append(line).append('\n')
						watch.said(line, error)
					}
				} catch (closed: IOException) {
					// The command closed its end: what was read so far is what it said.
				}

				return kept
			}

			// Both read at once: a command that fills one pipe while the other is read would wait for ever.
			// Set by the one thread that reads it, and read once that thread is done: a place to put what it read.
			val errors = AtomicReference(StringBuilder())
			val reader =
				Thread { errors.set(read(process.errorStream, error = true)) }.apply {
					isDaemon = true
					start()
				}
			val output = read(process.inputStream, error = false)
			val status = process.waitFor()

			reader.join()

			if (stopped.get())
				Ran(null, output.toString(), STOPPED)
			else
				Ran(status, output.toString(), errors.get().toString(), said.toString())
		} catch (failed: IOException) {
			Ran(null, "", failed.message ?: failed.toString())
		} catch (stopped: InterruptedException) {
			Thread.currentThread().interrupt()
			Ran(null, "", STOPPED)
		}

	/** What a command the user stopped is said to have failed by. */
	const val STOPPED = "Stopped."
}

/**
 * Folders made for a while: what entries of a place are brought out into to be opened, and what a command that asks
 * for somewhere to work is given. Each is in `prism`, under the system's temporary folder (`/tmp/prism`), named with
 * this process's id, and gone when whoever asked for it is done with it ([remove]), or
 * when prism ends, whichever is first. Those a process that is no longer running left behind (one that was killed)
 * are removed when the next one starts.
 */
class Temps(
	private val under: Path = Path.of(System.getProperty("java.io.tmpdir"), "prism"),
	private val process: Long = ProcessHandle.current().pid(),
) {
	private val made: MutableSet<Path> = ConcurrentHashMap.newKeySet()

	init {
		sweep()
		try {
			Runtime.getRuntime().addShutdownHook(Thread { clear() })
		} catch (ending: IllegalStateException) {
			// prism is ending already: what is made is cleared by the hook that is running.
		}
	}

	/** A folder of its own, empty. */
	fun make(): Path =
		Files.createTempDirectory(Files.createDirectories(under), "$process-").also {
			made.add(it)
		}

	/** [folder], one of these, removed with all it holds. */
	fun remove(folder: Path) {
		if (made.remove(folder))
			removed(folder)
	}

	/** Every folder made and not yet removed, removed. */
	fun clear() = made.toList().forEach(::remove)

	/** The folders left by processes that are not running any more, removed. */
	private fun sweep() {
		try {
			Files.newDirectoryStream(under).use { left ->
				for (folder in left) {
					val owner =
						folder.fileName
							.toString()
							.substringBefore('-')
							.toLongOrNull()

					if (owner != null && owner != process && ProcessHandle.of(owner).isEmpty)
						removed(folder)
				}
			}
		} catch (failed: IOException) {
			// Nothing made yet, or not to be listed: nothing is left to remove.
		}
	}

	/** [folder] and everything in it deleted, links not followed; what cannot be deleted is left. */
	private fun removed(folder: Path) {
		try {
			Files.walk(folder).use { all ->
				all.sorted(Comparator.reverseOrder()).forEach {
					try {
						Files.deleteIfExists(it)
					} catch (failed: IOException) {
						// What cannot be deleted is left.
					}
				}
			}
		} catch (failed: IOException) {
			// Gone already, or not to be walked: left.
		} catch (failed: java.io.UncheckedIOException) {
			// Gone as it was walked: left.
		}
	}
}

/** [placed] listed by [commands], from [home] where its place names no folder to run in. */
fun listingOf(
	placed: Placed,
	commands: Commands,
	home: Path,
	watch: Watch = Watch.NONE,
): PlaceListing {
	val filling = Filling(emptyList(), null, placed.values)
	val folder =
		placed.type.folder?.let {
			it.fill(filling)?.let { text -> pathOf(text, home, home) }
				?: return PlaceListing.Failed("The folder to list couldn't be determined.")
		} ?: home

	val words =
		placed.type.list
			.fill(filling.copy(folder = folder))
			?.singleOrNull()
			?: return PlaceListing.Failed(
				"Its command uses a value this place wasn't opened with.",
			)

	val ran = commands.run(Invocation(words, folder, placed.values), watch)

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
				val below =
					path
						.takeIf {
							type.tree && it.startsWith(base) && it != base
						}?.let(base::relativize)

				PlaceEntry(
					key = path.toString(),
					at =
						below?.map { it.toString() } ?: listOf(
							path.fileName?.toString() ?: path.toString(),
						),
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
					fields
						.drop(1)
						.mapNotNull { field ->
							field.indexOf('=').takeIf { it > 0 }?.let {
								field.substring(0, it) to
									field.substring(it + 1)
							}
						}.toMap()

				entryOf(
					fields.first(),
					said["path"],
					said["kind"],
					said["size"]?.toLongOrNull(),
					said["time"],
					base,
				)
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
					text("kind")
						?: "folder".takeIf { (said["folder"] as? JsonPrimitive)?.booleanOrNull == true },
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
		folder =
			at.endsWith("/") || kind == "folder" ||
				(kind == null && real?.let(Files::isDirectory) == true),
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
	listOf(
		"yyyy-MM-dd HH:mm:ss",
		"yyyy-MM-dd HH:mm",
		"yyyy-MM-dd'T'HH:mm:ss",
		"yyyyMMdd.HHmmss",
		"yyyyMMdd'T'HHmmss",
	).map(DateTimeFormatter::ofPattern)

/**
 * A time as a command prints one: seconds since 1970, an instant (`2026-01-02T03:04:05Z`), or a date and a time of
 * day in this machine's zone, as `tar` and `unzip` print them (`2026-01-02 03:04`, `20260102.030405`).
 */
fun timeOf(text: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
	text.toLongOrNull()?.let { return Instant.ofEpochSecond(it) }

	try {
		return Instant.parse(text)
	} catch (notAnInstant: DateTimeParseException) {
		// Not an instant: one of the other forms, tried next.
	}

	return TIMES.firstNotNullOfOrNull {
		try {
			LocalDateTime.parse(text, it).atZone(zone).toInstant()
		} catch (notThis: DateTimeParseException) {
			null
		}
	}
}
