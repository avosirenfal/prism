package prism.browser

import androidx.compose.runtime.Immutable
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Text searches (docs/text.md): lines found in the files a window shows, by a command, shown in the window in the
 * files' place. The window then has three parts: what is looked for, typed; the lines found, under the file each is
 * in; and, beside them, the file the line under the cursor is in, at that line.
 *
 * Prism finds nothing itself. A text search is declared (in the preferences, as the actions are), and what it
 * declares is a command: given the files the window showed on its standard input, it prints the lines it finds in
 * them, as `grep` prints them. So what searches text is whatever program the user has for it (`rg`, `grep`,
 * `git grep`), and anything else that prints lines of files can be looked through the same way (`strings` over
 * binaries, a build's errors).
 *
 * Here are what a text search is, as it is declared ([TextSearch]); what one found ([Hit], [Hits]); how what a command
 * prints is read into that ([HitReader]); and what the window lists of it ([TextRows]). What typing, moving and
 * opening do is the reducer's (texting.kt), and running the command the session's.
 *
 * For example, `rg --json -e class` given `step.kt` prints, for each line it finds, an object that names the file,
 * the line's number and text, and where in the text the match is: read, that is a [FileHit] of `step.kt`, its [Hit]
 * at line 157 with the text `data class CopyFile(` and one mark, over `class`.
 */

/** What a text search does with what is typed into it. */
enum class QueryUse(
	val word: String,
) {
	/** Its command looks for it, and is run again as it changes. */
	Typed("typed"),

	/** Its command is run once, without it, and what it printed is narrowed to the lines that have what is typed. */
	Narrows("narrows"),
}

/** How what a text search's command prints is read. */
enum class HitRead(
	val word: String,
) {
	/** `path:position:text`, a line each, as `grep -n` prints: what a program draws differently in the text is a mark. */
	Lines("lines"),

	/** ripgrep's own `--json`. */
	RgJson("rg-json"),

	/** An object a line: `path`, `at`, `text`, and `marks`, each a pair of where a match starts and ends. */
	Json("json"),
}

/** What the number a command prints with each line is. */
enum class Positions(
	val word: String,
	/** What a command names it by: `{line}`, `{offset}`. */
	val hole: String,
	/** What a line found with none is opened at. */
	val first: Long,
) {
	/** The line's number in its file, from 1: a file shown beside it is shown at that line. */
	Lines("lines", "line", 1),

	/** A byte's place in its file, from 0, as `strings -t d` prints: a file is not shown at it by lines. */
	Offsets("offsets", "offset", 0),
}

/** Something turned on or off beside a text search's query, by [name]: the words its command is given either way. */
@Immutable
data class TextOption(
	val name: String,
	val label: String,
	val on: List<String>,
	val off: List<String>,
)

/** A command that opens a file at a line, and whether it runs in the user's terminal. */
@Immutable
data class Opener(
	val command: Command,
	val terminal: Boolean,
)

/** A text search, declared: when it is offered, the command that finds, how what it prints is read, and what is shown. */
@Immutable
data class TextSearch(
	val id: String,
	val label: String,
	val conditions: Conditions,
	/** The submenus it is under, outermost first; none for the menu itself. */
	val submenu: List<String>,
	/** The chord that opens it until the user binds another. */
	val key: Chord?,
	val query: QueryUse,
	val options: List<TextOption>,
	val run: Command,
	val read: HitRead,
	/** For [HitRead.Lines]: whether a NUL character ends each path printed, and not a colon. */
	val nul: Boolean,
	val positions: Positions,
	/** The command whose output is shown beside a line found; prism reads the file itself, where there is none. */
	val preview: Command?,
	/** What opens a line found, in place of the user's editor. */
	val open: Opener?,
) {
	/** Whether it is offered for [subject]: whatever the menu is of, unless its conditions say which. */
	fun offered(subject: Subject, world: World): Boolean = conditions.hold(subject, world)

	/** The values with names an action offered for one of its lines can name. */
	val named: Set<String> get() = setOf(positions.hole, TEXT, QUERY)

	/**
	 * The words of [run] for [query], with [on] of its options on; null where it names something that is not there.
	 * `{options}`, a word alone, becomes the words of every option, each as it is on or off.
	 */
	fun words(query: String, on: Set<String>, folder: Path): List<String>? {
		val filling = Filling(emptyList(), folder, mapOf(QUERY to query))
		val chosen = options.flatMap { if (it.name in on) it.on else it.off }

		return run.words
			.map { word ->
				when {
					word.isOptions -> chosen
					word is Word.One -> word.phrase.fill(filling)?.let(::listOf)
					else -> null
				}
			}.takeIf { all -> all.none { it == null } }
			?.flatMap { it.orEmpty() }
	}

	companion object {
		/** What is typed, as a command names it. */
		const val QUERY = "query"

		/** `{options}`: the words of the options, as each is on or off. */
		const val OPTIONS = "options"

		/** The text of a line found, as an action for it names it. */
		const val TEXT = "text"
	}
}

/** Whether this word is `{options}` alone, which becomes as many words as the options say. */
internal val Word.isOptions: Boolean get() =
	(this as? Word.One)?.phrase?.pieces?.singleOrNull() ==
		Piece.Filled(Hole.Value(TextSearch.OPTIONS, Part.Whole))

/**
 * What opens a file at a line, from a text search that does not say what opens its own: the user's editor. It is the
 * one the preferences name; or, where they name none, the one the environment does (`VISUAL`, then `EDITOR`), given
 * the line as that editor takes it; or, where it names none either, the file's own application, which is not told the
 * line.
 */
@Immutable
sealed interface Editor {
	/** The file's application, as opening it anywhere else starts it. */
	data object Application : Editor

	data class Program(
		val opener: Opener,
	) : Editor

	companion object {
		/** What is said of a line, which what opens it can name beside the file. */
		val NAMED = setOf(Positions.Lines.hole, TextSearch.TEXT, TextSearch.QUERY)

		/** How an editor is told the line, and whether it has a window of its own, for those that are not told `+12 file`. */
		private class Known(
			val words: List<String>,
			val windowed: Boolean,
		)

		private val COLON = listOf("{path}:{line}")
		private val FLAG = listOf("--line", "{line}", "{path}")
		private val PLUS = listOf("+{line}", "{path}")

		private val KNOWN: Map<String, Known> =
			listOf("code", "codium", "code-insiders", "cursor").associateWith {
				Known(listOf("--goto") + COLON, windowed = true)
			} +
				listOf("subl", "zed", "zeditor").associateWith { Known(COLON, windowed = true) } +
				listOf("hx", "helix").associateWith { Known(COLON, windowed = false) } +
				listOf("kate", "idea", "pycharm", "clion", "webstorm", "goland", "rider", "rustrover")
					.associateWith { Known(FLAG, windowed = true) } +
				listOf("gvim", "gedit", "gnome-text-editor", "mousepad", "pluma", "xed")
					.associateWith { Known(PLUS, windowed = true) }

		/**
		 * The editor [written] names; or the one [environment] does, in a terminal unless it is known to have a window
		 * of its own; or the file's application.
		 */
		fun of(
			written: prism.preferences.OpenSpec?,
			environment: (String) -> String?,
		): Reading<Editor> {
			val said =
				written ?: listOf("VISUAL", "EDITOR")
					.firstNotNullOfOrNull { environment(it)?.trim()?.takeIf(String::isNotEmpty) }
					?.split(Regex("\\s+"))
					?.let { command ->
						val known = KNOWN[command.first().substringAfterLast('/')]

						prism.preferences.OpenSpec(
							command + (known?.words ?: PLUS),
							terminal = known?.windowed != true,
						)
					}

			return Actions.openerOf(said, NAMED).map { opener ->
				opener?.let(::Program) ?: Application
			}
		}
	}
}

/** A line found in a file: where in the file ([position], where the command said), its text, and what of it matched. */
@Immutable
data class Hit(
	val position: Long?,
	val text: String,
	/** The runs of [text] that matched, in order. */
	val marks: List<IntRange> = emptyList(),
)

/** A line found, with the file it was found in: what reading a line of a command's output gives. */
@Immutable
data class FileHit(
	val path: Path,
	val hit: Hit,
)

/** A file's lines found, in the order they were. */
@Immutable
data class HitGroup(
	val path: Path,
	val hits: List<Hit>,
)

/**
 * Every line a text search has found so far, under the file each is in, the files in the order their first line
 * came. Made by adding what was found to what there was ([plus]), which leaves what there was as it is.
 *
 * Equal only to itself: there can be a hundred thousand.
 */
class Hits private constructor(
	val groups: List<HitGroup>,
	private val places: Map<Path, Int>,
	/** How many lines, in every file. */
	val count: Int,
) {
	operator fun get(path: Path): HitGroup? = places[path]?.let(groups::get)

	/** These, and [found] after them: each line under its file, a file not seen before after those that were. */
	operator fun plus(found: List<FileHit>): Hits {
		if (found.isEmpty())
			return this

		val added = found.groupBy({ it.path }, { it.hit })
		val known = added.keys.filter { it in places }.associateWith { places.getValue(it) }
		val grown = known.entries.associate { (path, at) -> at to added.getValue(path) }
		val fresh =
			added.filterKeys { it !in places }.map { (path, hits) ->
				HitGroup(path, hits)
			}

		return Hits(
			(
				if (grown.isEmpty())
					groups
				else
					groups.mapIndexed { at, group ->
						grown[at]?.let { group.copy(hits = group.hits + it) } ?: group
					}
			) + fresh,
			places + fresh.mapIndexed { at, group -> group.path to groups.size + at },
			count + found.size,
		)
	}

	override fun toString(): String = "Hits($count in ${groups.size} files)"

	companion object {
		val NONE = Hits(emptyList(), emptyMap(), 0)

		/** The most lines kept of one run of a command: one that prints more is stopped there, and said to have been. */
		const val LIMIT = 100_000
	}
}

// ---- reading what a command prints -------------------------------------------------------------------------------

/**
 * What reads a command's output, a line at a time, into the lines it found. Relative paths are in [base]. A line that
 * says nothing found (ripgrep's `begin` and `end`, a line that is not as its kind is printed) reads as none.
 */
class HitReader(
	private val read: HitRead,
	private val nul: Boolean,
	private val base: Path,
) {
	constructor(search: TextSearch, base: Path) : this(search.read, search.nul, base)

	fun read(line: String): FileHit? =
		when (read) {
			HitRead.Lines -> plain(line)
			HitRead.RgJson -> objectOf(line)?.let(::ripgrep)
			HitRead.Json -> objectOf(line)?.let(::written)
		}

	/**
	 * `path:12:text`, or `path: 12 text` as `strings -f -t d` prints; and, where a NUL ends the path, the text alone
	 * after it where no number is.
	 */
	private fun plain(line: String): FileHit? {
		val (read, _) = inked(line)
		val text = read.text
		val parted =
			if (nul)
				text.indexOf('\u0000').takeIf { it > 0 }?.let { text.substring(0, it) to it + 1 }
			else
				POSITIONED.find(text)?.let { it.groupValues[1] to it.groupValues[1].length }
		val (path, rest) = parted ?: return null
		val placed = (if (nul) NUMBERED else PLACE).matchAt(text, rest)

		if (placed == null && !nul)
			return null

		val from = placed?.let { it.range.last + 1 } ?: rest

		return resolved(path)?.let { file ->
			FileHit(
				file,
				Hit(
					placed?.groupValues?.get(1)?.toLongOrNull(),
					text.substring(from),
					// What the program drew differently in the text, which is what it matched there.
					read.runs
						.filter { it.range.last >= from }
						.map { maxOf(it.range.first, from) - from..it.range.last - from }
						.merged(),
				),
			)
		}
	}

	/** One of ripgrep's `--json` messages: a `match` is a line found, and nothing else is. */
	private fun ripgrep(message: JsonObject): FileHit? {
		if (message.text("type") != "match")
			return null

		val data = message["data"] as? JsonObject ?: return null
		val path = (data["path"] as? JsonObject)?.let(::said) ?: return null
		val bytes = (data["lines"] as? JsonObject)?.let(::bytesOf) ?: return null
		// One match can be of several lines: the first is the line its number is of, and is the one shown.
		val line = bytes.decodeToString().substringBefore('\n').removeSuffix("\r")

		val marks =
			(data["submatches"] as? JsonArray).orEmpty().mapNotNull { match ->
				val start = (match as? JsonObject)?.number("start")?.toInt() ?: return@mapNotNull null
				val end = match.number("end")?.toInt() ?: return@mapNotNull null

				// Said in bytes of the line as it is on disk; drawn by characters of it as text.
				(charsIn(bytes, start) until charsIn(bytes, end))
					.takeIf { !it.isEmpty() && it.first < line.length }
					?.let { it.first..minOf(it.last, line.length - 1) }
			}

		return resolved(path)?.let {
			FileHit(it, Hit(data.number("line_number"), line, marks.merged()))
		}
	}

	/** An object as a script writes one: `path`, and any of `at`, `text` and `marks`. */
	private fun written(said: JsonObject): FileHit? {
		val path = said.text("path") ?: return null
		val text = said.text("text").orEmpty()

		val marks =
			(said["marks"] as? JsonArray).orEmpty().mapNotNull { mark ->
				val ends = (mark as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }

				ends
					?.takeIf { it.size == 2 && it[0] >= 0 && it[1] > it[0] && it[0] < text.length }
					?.let { it[0]..minOf(it[1], text.length) - 1 }
			}

		return resolved(path)?.let { FileHit(it, Hit(said.number("at"), text, marks.merged())) }
	}

	private fun resolved(path: String): Path? =
		try {
			base.resolve(path).normalize()
		} catch (bad: InvalidPathException) {
			null
		}

	private companion object {
		/** A path, up to the first colon a position follows. */
		val POSITIONED = Regex("""^(.+?):\s*\d+(?::|\s)""")

		/** A position, after the path: a number, and the colon or the space that parts it from the text. */
		val PLACE = Regex("""\s*:?\s*(\d+)(?::|\s)""")

		/** A position after a path a NUL ended: a number and a colon, which text that starts with a number is not. */
		val NUMBERED = Regex("""(\d+):""")

		fun objectOf(line: String): JsonObject? =
			try {
				Json.parseToJsonElement(line) as? JsonObject
			} catch (notJson: SerializationException) {
				null
			}

		fun JsonObject.text(name: String): String? =
			(this[name] as? JsonPrimitive)?.contentOrNull

		fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

		/** What ripgrep says as `{"text": …}`, or, where it is not UTF-8, as `{"bytes": …}` in base 64. */
		fun bytesOf(said: JsonObject): ByteArray? =
			said.text("text")?.encodeToByteArray()
				?: said.text("bytes")?.let {
					try {
						Base64.getDecoder().decode(it)
					} catch (notBase64: IllegalArgumentException) {
						null
					}
				}

		fun said(said: JsonObject): String? = bytesOf(said)?.decodeToString()

		/** How many characters the first [count] bytes of [bytes] are, as text. */
		fun charsIn(bytes: ByteArray, count: Int): Int =
			String(bytes, 0, count.coerceIn(0, bytes.size), Charsets.UTF_8).length

		fun List<JsonElement>?.orEmpty(): List<JsonElement> = this ?: emptyList()
	}
}

/** These runs in order, those that meet or overlap made one. */
internal fun List<IntRange>.merged(): List<IntRange> =
	sortedBy { it.first }.fold(emptyList()) { kept, next ->
		val last = kept.lastOrNull()

		if (last != null && next.first <= last.last + 1)
			kept.dropLast(1).plusElement(last.first..maxOf(last.last, next.last))
		else
			kept.plusElement(next)
	}

// ---- what the window lists ---------------------------------------------------------------------------------------

/** A row of a text search's list, by an identity that outlasts more lines being found. */
@Immutable
sealed interface TextRowId {
	val path: Path

	/** A file lines were found in. */
	data class File(
		override val path: Path,
	) : TextRowId

	/** The line of [path] found at [index] among those found in it. */
	data class Line(
		override val path: Path,
		val index: Int,
	) : TextRowId
}

/** A row of a text search's list: a file, over its lines; or a line found in one. */
@Immutable
sealed interface TextRow {
	val id: TextRowId

	/** A file, with how many of its lines are listed, and whether they are put away under it ([folded]). */
	data class File(
		override val id: TextRowId.File,
		val lines: Int,
		val folded: Boolean,
	) : TextRow

	/** A line found: [hit], its marks what is drawn as matched. */
	data class Line(
		override val id: TextRowId.Line,
		val hit: Hit,
	) : TextRow
}

/**
 * What a text search's list shows of [hits]: each file, and under it, unless it is among [folded], its lines; of a
 * search whose query narrows, only the lines that have every word of [narrowing] in them, without case, and only the
 * files that have such a line, each word's first place in a line a mark of it.
 *
 * Equal only to itself.
 */
class TextRows(
	hits: Hits,
	folded: Set<Path>,
	narrowing: String = "",
) {
	/** A file listed: its lines listed, each by its place among the file's, and where its own row is. */
	private class Listed(
		val path: Path,
		val indices: IntArray?,
		val hits: List<Hit>,
		val folded: Boolean,
		val start: Int,
	) {
		val lines: Int get() = indices?.size ?: hits.size
		val rows: Int get() =
			if (folded)
				1
			else
				1 + lines
	}

	private val words = narrowing.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

	private val listed: List<Listed> =
		hits.groups
			.mapNotNull { group ->
				val kept =
					if (words.isEmpty())
						null
					else
						group.hits.indices
							.filter { group.hits[it].has(words) }
							.toIntArray()

				if (kept?.isEmpty() == true)
					null
				else
					Triple(group, kept, group.path in folded)
			}.let { kept ->
				val starts =
					kept.runningFold(0) { at, (group, indices, away) ->
						at + 1 + (indices?.size ?: group.hits.size).takeUnless { away }.let { it ?: 0 }
					}

				kept.mapIndexed { at, (group, indices, away) ->
					Listed(group.path, indices, group.hits, away, starts[at])
				}
			}

	private val byPath: Map<Path, Listed> by lazy { listed.associateBy { it.path } }

	/** How many rows: files, and the lines under those not folded. */
	val size: Int = listed.lastOrNull()?.let { it.start + it.rows } ?: 0

	/** How many files are listed. */
	val files: Int get() = listed.size

	/** How many lines are listed, those under a folded file too. */
	val lines: Int = listed.sumOf { it.lines }

	fun isEmpty(): Boolean = size == 0

	operator fun get(index: Int): TextRow {
		val file = listed[fileAt(index)]
		val within = index - file.start - 1

		return if (within < 0)
			TextRow.File(TextRowId.File(file.path), file.lines, file.folded)
		else
			(file.indices?.get(within) ?: within).let { at ->
				TextRow.Line(TextRowId.Line(file.path, at), file.hits[at].marked(words))
			}
	}

	/** Where [id]'s row is; nowhere, for one not listed (a line under a folded file, or narrowed away). */
	fun indexOf(id: TextRowId): Int? {
		val file = byPath[id.path] ?: return null

		return when (id) {
			is TextRowId.File -> file.start
			is TextRowId.Line ->
				(
					file.indices?.binarySearch(id.index)
						?: id.index.takeIf { it in file.hits.indices }
				)?.takeIf { it >= 0 && !file.folded }
					?.let { file.start + 1 + it }
		}
	}

	operator fun get(id: TextRowId): TextRow? = indexOf(id)?.let(::get)

	operator fun contains(id: TextRowId): Boolean = indexOf(id) != null

	/** The first line listed, where there is one to be seen. */
	val firstLine: TextRowId.Line? get() =
		(0 until size).firstNotNullOfOrNull { this[it].id as? TextRowId.Line }

	/** The paths of the files listed, in order. */
	val paths: List<Path> get() = listed.map { it.path }

	private fun fileAt(index: Int): Int {
		require(index in 0 until size) { "no row $index of $size" }

		// The last file that starts at or before the row.
		var low = 0
		var high = listed.size - 1

		while (low < high) {
			val middle = (low + high + 1) / 2

			if (listed[middle].start <= index)
				low = middle
			else
				high = middle - 1
		}

		return low
	}

	override fun toString(): String = "TextRows($lines lines in $files files)"

	companion object {
		val NONE = TextRows(Hits.NONE, emptySet())
	}
}

/** Whether this line's text has every one of [words] in it, without case. */
private fun Hit.has(words: List<String>): Boolean =
	words.all { text.contains(it, ignoreCase = true) }

/** This line, with where each of [words] is first in its text marked as well as what its command marked. */
private fun Hit.marked(words: List<String>): Hit =
	if (words.isEmpty())
		this
	else
		copy(
			marks =
				(
					marks +
						words.mapNotNull { word ->
							text
								.indexOf(word, ignoreCase = true)
								.takeIf { it >= 0 }
								?.let { it until it + word.length }
						}
				).merged(),
		)

// ---- a search shown ----------------------------------------------------------------------------------------------

/**
 * The files a text search looks in: what the window showed as it was opened, or what was chosen of it. Their paths
 * are made as they are asked for, since they can be every result of a search of the index.
 *
 * Equal only to itself.
 */
class TextScope(
	/** What it is called, after "in": `proj`, `results for “*.kt”`, `3 selected items`. */
	val said: String,
	/** The folder the command is run in, and the one the files' paths are shown from. */
	val folder: Path,
	private val count: Int,
	private val pathAt: (Int) -> Path?,
) {
	constructor(
		said: String,
		folder: Path,
		paths: List<Path>,
	) : this(said, folder, paths.size, paths::get)

	/** Each file or folder to look in, in the order the window showed them. */
	val paths: Sequence<Path> get() = (0 until count).asSequence().mapNotNull(pathAt)

	override fun toString(): String = "TextScope($said, $count)"
}

/**
 * One run of a text search's command: what it looks in, what for, and with which options on. Equal only to itself, so
 * that what a run found is never taken for what another did, however alike the two were asked.
 */
class TextRun(
	val search: TextSearch,
	val scope: TextScope,
	val query: String,
	val on: Set<String>,
) {
	/** What reads what its command prints. */
	val reader: HitReader get() = HitReader(search, scope.folder)

	override fun toString(): String = "TextRun(${search.id}, “$query”)"
}

/** How a run of a text search's command ended. */
@Immutable
sealed interface TextEnding {
	/** By itself; [note] is what it wrote to standard error, where it wrote anything (a file it could not read). */
	data class Done(
		val note: String? = null,
	) : TextEnding

	/** Stopped by prism, having found as many lines as are kept ([Hits.LIMIT]). */
	data object Capped : TextEnding

	/** Stopped by the user. */
	data object Stopped : TextEnding

	/** It found nothing and said why not, or could not be run. */
	data class Failed(
		val why: String,
	) : TextEnding
}

/** What a run found, so far or in all: [of] is the run, none before any has found anything; [ending] how it ended, once it has. */
@Immutable
data class TextResults(
	val of: TextRun?,
	val hits: Hits,
	val ending: TextEnding?,
) {
	companion object {
		val NONE = TextResults(null, Hits.NONE, null)
	}
}

/**
 * A file asked to be shown beside a line found in it: by [words], a command, where the search says one; read by
 * prism, where they are null. [around] is the line wanted in sight, which a file too long to be read whole is read
 * about. Two that differ only by that are of the same file shown the same way ([same]).
 */
@Immutable
data class PreviewAsk(
	val path: Path,
	val words: List<String>?,
	val folder: Path,
	val around: Long?,
) {
	fun same(other: PreviewAsk): Boolean = copy(around = null) == other.copy(around = null)
}

/** A file as it is shown beside a line found in it. */
@Immutable
sealed interface Preview {
	/**
	 * Its lines, coloured as whatever printed them coloured them, the first of them line [first] of the file; and
	 * whether these are every line the file has ([whole]), or as many as are read of one too long to read all of.
	 */
	data class Shown(
		val first: Long,
		val lines: List<InkedLine>,
		val whole: Boolean,
	) : Preview {
		/** Whether line [line] of the file is among these. */
		fun covers(line: Long): Boolean = line >= first && line < first + lines.size
	}

	/** Not shown, and why: not text, not there, or its command failed. */
	data class Unshown(
		val why: String,
	) : Preview

	companion object {
		/** The most lines shown of a file: one longer is read about the line wanted, where prism reads it. */
		const val LINES = 20_000

		/** How many lines before the one wanted a file too long to read whole is read from. */
		const val BEFORE = 5_000
	}
}

/** A file shown beside the list: what was asked, and what came of it. */
@Immutable
data class Previewed(
	val ask: PreviewAsk,
	val preview: Preview,
)

/**
 * The rows last listed of what a text search found, kept for the places copied from the one they were listed for:
 * moving the cursor changes none of what is listed, and such a copy is given the same rows without listing them
 * again. The keeping is the only thing about it that changes, and it says nothing of the place: every one is equal to
 * every other.
 */
class TextListed {
	private class Kept(
		val hits: Hits,
		val folded: Set<Path>,
		val narrowing: String,
		val rows: TextRows,
	)

	private val last = prism.shared.Published<Kept?>(null)

	internal fun of(hits: Hits, folded: Set<Path>, narrowing: String): TextRows =
		last.now
			?.takeIf { it.hits === hits && it.folded == folded && it.narrowing == narrowing }
			?.rows
			?: TextRows(
				hits,
				folded,
				narrowing,
			).also { last.set(Kept(hits, folded, narrowing, it)) }

	override fun equals(other: Any?): Boolean = other is TextListed

	override fun hashCode(): Int = 0

	override fun toString(): String = "TextListed"
}

// ---- how a line is drawn -------------------------------------------------------------------------------------------

/**
 * This line as a row of the list shows it: without the white space it starts with, a tab in it drawn as a space, and
 * no longer than [width] characters. A line longer than that is shown from a little before what first matched in it,
 * where that would otherwise be out of sight, with an ellipsis where it is cut. Its marks are where they were, in
 * what is shown.
 */
fun Hit.excerpt(width: Int = EXCERPT): Hit {
	val indent = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: text.length
	val match = marks.firstOrNull()?.first ?: indent
	// A match further along than is shown is brought into sight, a little of what leads to it before it.
	val from =
		if (match - indent < width - LEAD)
			indent
		else
			match - LEAD

	val to = minOf(text.length, from + width)
	val before = "…".takeIf { from > indent }.orEmpty()
	val after = "…".takeIf { to < text.length }.orEmpty()

	return Hit(
		position,
		before + text.substring(from, to).replace('\t', ' ') + after,
		marks
			.filter { it.last >= from && it.first < to }
			.map {
				maxOf(
					it.first,
					from,
				) - from + before.length..minOf(it.last, to - 1) - from + before.length
			},
	)
}

/** How many characters of a line found a row of the list shows. */
const val EXCERPT = 400

/** How many characters before a match far along its line are shown before it. */
private const val LEAD = 24

/**
 * [text] with each tab in it as the spaces that reach the next stop, every [stop] characters: the text, and where
 * each character of [text] is in it (and where its end is), for what is marked in the one to be marked in the other.
 * Text with no tab in it is itself, each character where it was.
 */
fun untabbed(text: String, stop: Int = 4): Pair<String, (Int) -> Int> {
	if ('\t' !in text)
		return text to { it }

	val places = IntArray(text.length + 1)
	val spaced = StringBuilder(text.length + 8)

	text.forEachIndexed { at, char ->
		places[at] = spaced.length
		if (char == '\t')
			repeat(stop - spaced.length % stop) { spaced.append(' ') }
		else
			spaced.append(char)
	}
	places[text.length] = spaced.length

	return spaced.toString() to { places[it.coerceIn(0, text.length)] }
}
