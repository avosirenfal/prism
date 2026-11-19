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
import prism.index.given
import prism.index.search

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

	/** A path a line, and no line of it: a file found, as `rg --files-with-matches` and `git ls-files` print them. */
	Paths("paths"),
}

/** Where the path ends in a line `grep -n` prints: at the colon before its number, or at a NUL (`grep --null`). */
enum class PathEnd {
	Colon,
	Nul,
}

/** What parts the paths a command prints: a line each, or a NUL character after each (`rg -0`). */
enum class PathsApart {
	Lines,
	Nul,
}

/**
 * How what one command prints is read: each kind with only what can be said of it, so that ripgrep's JSON is never
 * read as ended by NULs, nor grep's lines as JSON. Said of each way of a search, with its command ([SearchWay]).
 */
@Immutable
sealed interface Printed {
	/** As it is declared. */
	val kind: HitRead

	/** `path:position:text`, a line each, as `grep -n` prints, the path ended as [pathEnd] says. */
	data class Lines(
		val pathEnd: PathEnd,
	) : Printed {
		override val kind: HitRead get() = HitRead.Lines
	}

	/** ripgrep's own `--json`. */
	data object RipgrepJson : Printed {
		override val kind: HitRead get() = HitRead.RgJson
	}

	/** An object a line: `path`, `at`, `text`, and `marks`. */
	data object ObjectJson : Printed {
		override val kind: HitRead get() = HitRead.Json
	}

	/** Paths alone, apart as [apart] says. */
	data class Paths(
		val apart: PathsApart,
	) : Printed {
		override val kind: HitRead get() = HitRead.Paths
	}

	/** How a line is printed that is read as one found, by a search that [takes] what it does: "path:number:text". */
	fun described(takes: Takes): String =
		when (this) {
			is Lines ->
				when {
					takes == Takes.Lines -> "number:text"
					pathEnd == PathEnd.Nul -> "path, NUL, number:text"
					else -> "path:number:text"
				}
			RipgrepJson -> "ripgrep JSON"
			ObjectJson -> "JSON"
			is Paths -> "paths"
		}

	companion object {
		/** What is declared as [kind], with paths ended by NULs where [nul] says. */
		fun of(kind: HitRead, nul: Boolean): Printed =
			when (kind) {
				HitRead.Lines -> Lines(if (nul) PathEnd.Nul else PathEnd.Colon)
				HitRead.RgJson -> RipgrepJson
				HitRead.Json -> ObjectJson
				HitRead.Paths -> Paths(if (nul) PathsApart.Nul else PathsApart.Lines)
			}
	}
}

/**
 * A way of running a text search: the [programs] it needs (`a|b`, either), its command, and how what that command
 * prints is read. The two are declared together and kept together: one way's command is never read as another's.
 */
@Immutable
data class SearchWay(
	val programs: List<String>,
	val run: Command,
	val printed: Printed,
) {
	/** Whether every program it needs is [installed]: one of each `a|b`. */
	fun needsAll(installed: (String) -> Boolean): Boolean =
		programs.all { any -> any.split('|').any { installed(it.trim()) } }
}

/**
 * A text search's ways, the most preferred first, and the one it is run by ([chosen]): as it is read, the first that
 * can be run here; to try one, any of them ([trying]).
 */
@Immutable
@ConsistentCopyVisibility
data class SearchWays private constructor(
	val all: List<SearchWay>,
	/** Which of [all] is run. */
	val at: Int,
) {
	val chosen: SearchWay get() = all[at]

	/** These, run by the way at [index] among them; none where there is none there. */
	fun trying(index: Int): SearchWays? = of(all, index)

	companion object {
		/** [all], run by the way at [at]; none where there is no way there. */
		fun of(all: List<SearchWay>, at: Int): SearchWays? =
			if (at in
				all.indices
			)
				SearchWays(all, at)
			else
				null
	}
}

/** What a text search is given to look in. */
enum class Takes(
	val word: String,
) {
	/** Files, each ended by a NUL character, on its standard input: it finds lines in them. */
	Files("files"),

	/**
	 * Lines found already, each a line of its standard input: it keeps some of them, and says which by each one's
	 * number among those it was given, as `grep -n` does. It can only follow something that found lines.
	 */
	Lines("lines"),
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
	/** The ways it can be run, and the one it is: each with how what it prints is read. */
	val ways: SearchWays,
	/** What it is given to look in: files, or lines found already. */
	val takes: Takes,
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

	/** The way it is run. */
	val way: SearchWay get() = ways.chosen

	/** This, run by its way at [index]; none where it has none there. */
	fun trying(index: Int): TextSearch? = ways.trying(index)?.let { copy(ways = it) }

	/**
	 * The words of its [way]'s command for [query], with [on] of its options on; null where it names something that is not there.
	 * `{options}`, a word alone, becomes the words of every option, each as it is on or off.
	 */
	fun words(query: String, on: Set<String>, folder: Path): List<String>? {
		val filling = Filling(emptyList(), folder, mapOf(QUERY to query))
		val chosen = options.flatMap { if (it.name in on) it.on else it.off }

		return way.run.words
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

/**
 * A line found, with the file it was found in: what reading a line of a command's output gives. A file found with no
 * line of it said ([HitRead.Paths]) has no [hit].
 */
@Immutable
data class FileHit(
	val path: Path,
	val hit: Hit?,
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

	/** The files, in order. */
	val paths: List<Path> get() = groups.map { it.path }

	/** Every line, with its file, in the order they are listed. */
	val lines: Sequence<FileHit> get() =
		groups.asSequence().flatMap { group ->
			group.hits.asSequence().map { FileHit(group.path, it) }
		}

	/**
	 * These, and [found] after them: each line under its file, a file not seen before after those that were. A file
	 * found with no line is listed with none.
	 */
	operator fun plus(found: List<FileHit>): Hits {
		if (found.isEmpty())
			return this

		val added =
			found
				.groupBy(
					{ it.path },
					{ it.hit },
				).mapValues { it.value.filterNotNull() }

		val grown =
			added.entries
				.filter { it.key in places && it.value.isNotEmpty() }
				.associate { places.getValue(it.key) to it.value }

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
			count + added.values.sumOf { it.size },
		)
	}

	/** These, of the files among [files] alone. This itself, where that is all of them. */
	fun restricted(files: Set<Path>): Hits =
		if (groups.all { it.path in files })
			this
		else
			of(groups.filter { it.path in files })

	/**
	 * These, narrowed to the lines that have every one of [words] in them, without case, and the files that have
	 * such a line: each word's first place in a line a mark of it. A file listed with no line is kept where the words
	 * are in where it is. This itself, with no words.
	 */
	fun narrowed(words: List<String>): Hits =
		if (words.isEmpty())
			this
		else
			of(
				groups.mapNotNull { group ->
					if (group.hits.isEmpty())
						group.takeIf {
							words.all { word ->
								group.path.toString().contains(word, ignoreCase = true)
							}
						}
					else
						group.hits
							.filter { it.has(words) }
							.takeIf { it.isNotEmpty() }
							?.let { kept -> HitGroup(group.path, kept.map { it.marked(words) }) }
				},
			)

	override fun toString(): String = "Hits($count in ${groups.size} files)"

	companion object {
		val NONE = Hits(emptyList(), emptyMap(), 0)

		/** [groups], each file's lines under it; a file given twice is listed once, with the lines of both. */
		fun of(groups: List<HitGroup>): Hits =
			if (groups.map { it.path }.toSet().size == groups.size)
				Hits(
					groups,
					groups.withIndex().associate { (at, group) -> group.path to at },
					groups.sumOf { it.hits.size },
				)
			else
				NONE +
					groups.flatMap { group ->
						group.hits
							.map {
								FileHit(
									group.path,
									it,
								)
							}.ifEmpty { listOf(FileHit(group.path, null)) }
					}

		/** What typed to narrow lines is, as the words each must have. */
		fun wordsOf(narrowing: String): List<String> =
			narrowing.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

		/** The most lines kept of one run of a command: one that prints more is stopped there, and said to have been. */
		const val LIMIT = 100_000
	}
}

// ---- reading what a command prints -------------------------------------------------------------------------------

/**
 * What a line a command printed comes to, read as its search says it is printed ([HitReader]). Of the two that are
 * not something found, one is as a command of that kind prints and says nothing was (ripgrep's `begin` and `end`, a
 * blank line); and the other is not as one prints at all, which says the search is read as something its command
 * does not print: macOS's grep, told `-Z`, prints no NUL after a path, and none of its lines is one found.
 */
sealed interface LineRead<out T> {
	/** What a line found, or kept, is. */
	data class Found<out T>(
		val value: T,
	) : LineRead<T>

	/** As a command of its kind prints, and saying nothing found. */
	data object Empty : LineRead<Nothing>

	/** Not as a command of its kind prints. */
	data object Unread : LineRead<Nothing>

	/** What was found; none for a line that found nothing. */
	val found: T? get() = (this as? Found)?.value

	companion object {
		/** [value] found, where there is one; and where there is none, a line that is not as its kind is printed. */
		fun <T> of(value: T?): LineRead<T> = value?.let(::Found) ?: Unread
	}
}

/**
 * What reads a command's output, a line at a time, into the lines it found, as [printed] says it is printed: that of
 * the way of a search that is run, and no other. Relative paths are in [base].
 */
class HitReader(
	private val printed: Printed,
	private val base: Path,
) {
	/** Each line in [line], as a command prints a line: paths apart by NULs come as one line of them all. */
	fun readAll(line: String): List<Pair<String, LineRead<FileHit>>> =
		when (printed) {
			Printed.Paths(PathsApart.Nul) -> line.split('\u0000')
			else -> listOf(line)
		}.map { it to read(it) }

	fun read(line: String): LineRead<FileHit> =
		when {
			line.isBlank() -> LineRead.Empty
			else ->
				when (printed) {
					is Printed.Lines -> LineRead.of(plain(line, printed.pathEnd))
					Printed.RipgrepJson -> objectOf(line)?.let(::ripgrep) ?: LineRead.Unread
					// A line found is found in a file: an object that names none says nothing found.
					Printed.ObjectJson ->
						objectOf(line)?.let {
							if (it.text("path") == null)
								LineRead.Empty
							else
								LineRead.of(written(it))
						} ?: LineRead.Unread
					is Printed.Paths -> LineRead.of(resolved(line)?.let { FileHit(it, null) })
				}
		}

	/**
	 * A line of what a command that was given lines printed, read as one it kept: which of the lines it was given
	 * (their number, from 1, as `grep -n` counts its standard input), and that line as it now is, with what matched in
	 * it. Whatever file the command says it is of is not said: it was given no file.
	 */
	fun kept(line: String): LineRead<Pair<Long, Hit>> =
		when {
			line.isBlank() -> LineRead.Empty
			else -> keptOf(line)
		}

	private fun keptOf(line: String): LineRead<Pair<Long, Hit>> =
		when (printed) {
			is Printed.Lines -> LineRead.of(lineKept(line))
			// A line kept says which it was: one that says nothing of it is not one kept.
			Printed.RipgrepJson ->
				when (val said = objectOf(line)?.let(::ripgrep) ?: LineRead.Unread) {
					is LineRead.Found ->
						LineRead.of(
							said.value.hit?.let { hit ->
								hit.position?.let {
									it to
										hit
								}
							},
						)
					LineRead.Empty -> LineRead.Empty
					LineRead.Unread -> LineRead.Unread
				}
			Printed.ObjectJson ->
				LineRead.of(
					objectOf(line)?.let(::written)?.hit?.let { hit -> hit.position?.let { it to hit } },
				)
			// Refused as it is declared: a search that takes lines is not read as paths.
			is Printed.Paths -> LineRead.Unread
		}

	/** `12:text`, as `grep -n` prints a line of its standard input: none where it is not printed so. */
	private fun lineKept(line: String): Pair<Long, Hit>? =
		inked(line).first.let { read ->
			NUMBERED.matchAt(read.text, 0)?.let { numbered ->
				val from = numbered.range.last + 1

				numbered.groupValues[1].toLongOrNull()?.let {
					it to
						Hit(
							it,
							read.text.substring(from),
							read.runs
								.filter { run -> run.range.last >= from }
								.map { run -> maxOf(run.range.first, from) - from..run.range.last - from }
								.merged(),
						)
				}
			}
		}

	/**
	 * `path:12:text`, or `path: 12 text` as `strings -f -t d` prints; and, where a NUL ends the path, the text alone
	 * after it where no number is.
	 */
	private fun plain(line: String, pathEnd: PathEnd): FileHit? {
		val (read, _) = inked(line)
		val text = read.text
		val nul = pathEnd == PathEnd.Nul
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

	/**
	 * One of ripgrep's `--json` messages: a `match` is a line found, and every other kind it prints (`begin`, `end`,
	 * `context`, `summary`) says nothing found; a message of no kind is none of ripgrep's.
	 */
	private fun ripgrep(message: JsonObject): LineRead<FileHit> =
		when (message.text("type")) {
			"match" -> LineRead.of(match(message))
			null -> LineRead.Unread
			else -> LineRead.Empty
		}

	/** A `match` of ripgrep's: none where it is not as ripgrep prints one. */
	private fun match(message: JsonObject): FileHit? {
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
		// A line kept of those it was given is of no file it knows.
		val path = said.text("path") ?: "-"
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
 * What a text search's list shows of [hits]: each file, and under it, unless it is among [folded], its lines.
 *
 * Equal only to itself.
 */
class TextRows(
	hits: Hits,
	folded: Set<Path>,
) {
	/** A file listed: its lines, and where its own row is. */
	private class Listed(
		val path: Path,
		val hits: List<Hit>,
		val folded: Boolean,
		val start: Int,
	) {
		val lines: Int get() = hits.size

		val rows: Int get() =
			if (folded)
				1
			else
				1 + lines
	}

	private val listed: List<Listed> =
		hits.groups.let { groups ->
			val starts =
				groups.runningFold(0) { at, group ->
					at + 1 +
						group.hits.size
							.takeUnless { group.path in folded }
							.let { it ?: 0 }
				}

			groups.mapIndexed { at, group ->
				Listed(group.path, group.hits, group.path in folded, starts[at])
			}
		}

	private val byPath: Map<Path, Listed> by lazy { listed.associateBy { it.path } }

	/** How many rows: files, and the lines under those not folded. */
	val size: Int = listed.lastOrNull()?.let { it.start + it.rows } ?: 0

	/** How many files are listed. */
	val files: Int get() = listed.size

	/** How many lines are listed, those under a folded file too. */
	val lines: Int = listed.sumOf { it.lines }

	/**
	 * How many characters the longest line listed is shown in ([Hit.excerpt]), those under a folded file too: what the
	 * list is as wide as, where that is wider than its room. Worked out when it is first asked for.
	 */
	val longest: Int by lazy {
		listed.maxOfOrNull { file -> file.hits.maxOfOrNull { it.shownLength } ?: 0 } ?: 0
	}

	fun isEmpty(): Boolean = size == 0

	operator fun get(index: Int): TextRow {
		val file = listed[fileAt(index)]
		val within = index - file.start - 1

		return if (within < 0)
			TextRow.File(TextRowId.File(file.path), file.lines, file.folded)
		else
			TextRow.Line(TextRowId.Line(file.path, within), file.hits[within])
	}

	/** Where [id]'s row is; nowhere, for one not listed (a line under a folded file, or of a file that is not there). */
	fun indexOf(id: TextRowId): Int? {
		val file = byPath[id.path] ?: return null

		return when (id) {
			is TextRowId.File -> file.start
			is TextRowId.Line ->
				id.index
					.takeIf { it in file.hits.indices && !file.folded }
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

/** How many characters [Hit.excerpt] shows of this line, give or take the dots where it is cut. */
private val Hit.shownLength: Int get() =
	minOf(
		text.length -
			(text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: text.length),
		EXCERPT,
	)

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
 * One run of a text search's command: what it looks in, what for, and with which options on. What it looks in is the
 * files of [scope]; or, for a search that takes lines, the [lines] it is given, of which it says which it keeps. Run
 * [without], what it gives is what it did not find of what it was given: the files it found no line in, or the lines
 * it did not keep.
 *
 * Equal only to itself, so that what a run found is never taken for what another did, however alike the two were
 * asked.
 */
class TextRun(
	val search: TextSearch,
	val scope: TextScope,
	val query: String,
	val on: Set<String>,
	val lines: List<FileHit>? = null,
	val without: Boolean = false,
) {
	/** What reads what its command prints. */
	val reader: HitReader get() = HitReader(search.way.printed, scope.folder)

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
		val rows: TextRows,
	)

	private val last = prism.shared.Published<Kept?>(null)

	internal fun of(hits: Hits, folded: Set<Path>): TextRows =
		last.now
			?.takeIf { it.hits === hits && it.folded == folded }
			?.rows
			?: TextRows(hits, folded).also { last.set(Kept(hits, folded, it)) }

	override fun equals(other: Any?): Boolean = other is TextListed

	override fun hashCode(): Int = 0

	override fun toString(): String = "TextListed"
}

// ---- narrowing the files, and finding in the file shown ------------------------------------------------------------

/**
 * A question of which of the files a search found a query of the index's own language finds ([text], as [query] reads
 * it): the files are those of [hits], and `~` in the query is [home]. Equal only to itself: the same asked of more
 * files is another question.
 */
class FilterAsk(
	val text: String,
	val query: prism.index.query.Query,
	val hits: Hits,
	val home: Path,
) {
	/** Whether this asks what [text] typed, of the files of [hits], would. */
	fun asks(text: String, hits: Hits): Boolean = this.text == text && this.hits === hits

	override fun toString(): String = "FilterAsk(“$text”, $hits)"
}

/** The files [ask]'s query finds among those it asks of; or why it could not be run. */
fun filtered(ask: FilterAsk): Reading<Set<Path>> {
	val paths = ask.hits.groups.mapTo(HashSet()) { it.path }

	// An index of these files and no others, each where it is on disk, searched as the index of the disk is.
	val index =
		prism.index.Index.given(
			paths.map { path -> prism.index.Given(path.map { it.toString() }, folder = false) },
		)

	return when (
		val found = index.search(ask.query, prism.index.SearchContext(home = ask.home))
	) {
		is prism.index.Searched.Refused -> Reading.Wrong(found.why)
		// The folders above a file are in such an index too, and are no file a line was found in.
		is prism.index.Searched.Found ->
			Reading.Right(
				found.results
					.mapNotNullTo(HashSet()) { it.where.path }
					.apply { retainAll(paths) },
			)
	}
}

/**
 * The files a text search found, asked to be shown as a listing of files, under [title]. Equal only to itself.
 */
class FoundAsk(
	val title: String,
	val paths: List<Path>,
) {
	override fun toString(): String = "FoundAsk($title, ${paths.size} files)"

	companion object {
		/** What the place of them is opened with: what it is called. */
		const val TITLE = "title"

		/** What finds every file of the place, and none of the folders they are under. */
		const val EVERY_FILE = "is:file"
	}
}

/**
 * The place the files a text search found are shown as: files on disk, each where it is, listed by nothing but what
 * found them. It is declared nowhere and runs no command; what it holds is given to it as it is made ([found]).
 */
val FOUND_FILES: PlaceType =
	PlaceType(
		id = "text.found",
		title = Phrase(listOf(Piece.Filled(Hole.Value(FoundAsk.TITLE, Part.Whole)))),
		takes = listOf(FoundAsk.TITLE),
		window = false,
		list = Command(listOf(Word.One(Phrase(listOf(Piece.Text("true")))))),
		folder = null,
		read = PlaceRead.Paths,
		tree = true,
		nul = false,
		out = null,
		into = null,
	)

/**
 * [ask]'s files as a place of them, each under the folders it is in on disk, with its size and when it was changed
 * as they are now; and every file of it, as a search of the place finds them, which is how they are shown: all
 * together, as a search's results are.
 */
fun found(ask: FoundAsk): Pair<PlaceTree, Finding> {
	val tree =
		PlaceTree(
			ask.paths.map { path ->
				val read =
					try {
						java.nio.file.Files.readAttributes(
							path,
							java.nio.file.attribute.BasicFileAttributes::class.java,
						)
					} catch (gone: java.io.IOException) {
						null
					}

				PlaceEntry(
					key = path.toString(),
					at = path.map { it.toString() },
					path = path,
					folder = false,
					size = read?.size(),
					modified = read?.lastModifiedTime()?.toInstant(),
				)
			},
		)

	val text = FoundAsk.EVERY_FILE
	val query =
		prism.index.query.QueryText
			.read(text, prism.index.query.Aliases.NONE)
			.query

	return tree to
		when (
			val searched =
				tree.index.search(
					query,
					prism.index.SearchContext(home = Path.of("/")),
				)
		) {
			is prism.index.Searched.Found -> Finding.Matched(text, Matches(searched.results, tree))
			is prism.index.Searched.Refused -> Finding.Refused(text, searched.why)
		}
}

/** One place what is looked for in the file shown is at: which of the lines shown, and where in the line. */
@Immutable
data class Occurrence(
	val line: Int,
	val range: IntRange,
)

/**
 * What is looked for in the file shown beside the list, as Ctrl+F looks in any text: [text], typed, and which of the
 * places it is at is the one gone to ([at], counted round: one past the last is the first). It is of the file shown,
 * and knows nothing of what the search looks for.
 */
@Immutable
data class PreviewFind(
	val text: String = "",
	val at: Int = 0,
) {
	/** The place gone to among [found], where there is any. */
	fun current(found: List<Occurrence>): Occurrence? = found.getOrNull(index(found))

	/** Which of [found] is the one gone to. */
	fun index(found: List<Occurrence>): Int =
		if (found.isEmpty())
			0
		else
			at.mod(found.size)
}

/** Every place [text] is at in these lines, without case, in order; no more than [OCCURRENCES] of them. */
fun Preview.Shown.occurrences(text: String): List<Occurrence> =
	if (text.isEmpty())
		emptyList()
	else
		lines
			.asSequence()
			.flatMapIndexed { at, line ->
				generateSequence(
					line.text.indexOf(text, ignoreCase = true).takeIf { it >= 0 },
				) { from ->
					line.text.indexOf(text, from + text.length, ignoreCase = true).takeIf { it >= 0 }
				}.map { Occurrence(at, it until it + text.length) }
			}.take(OCCURRENCES)
			.toList()

/** The most places counted of what is looked for in a file. */
const val OCCURRENCES = 10_000

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
 * The most characters of one line the file beside the list draws. A line longer than this (a minified file is one
 * line of hundreds of thousands) is shown as this much of it, about what was found in it: laying out all of it would
 * take longer than anyone waits, and a line that long is wider than anything drawn can be.
 */
const val LINE_SHOWN = 2000

/**
 * A line as the file beside the list shows it: [line], all of it or, of one longer than [LINE_SHOWN], the part of it
 * from [from], with an ellipsis ([lead] characters of it at its start) wherever it is cut.
 */
@Immutable
data class LineSight(
	val line: InkedLine,
	val from: Int = 0,
	val lead: Int = 0,
	private val length: Int = line.text.length,
) {
	/** Where [range] of the whole line is in what is shown; null where none of it is. */
	fun at(range: IntRange): IntRange? {
		val first = maxOf(range.first, from)
		val last = minOf(range.last, from + length - 1)

		return (first - from + lead..last - from + lead).takeIf { first <= last }
	}
}

/**
 * This line as it is shown: itself, where it is no longer than [most]; otherwise [most] characters of it, a quarter of
 * them before [about] (a place in it: where what was found is; its start, where nothing was) and the rest after.
 */
fun InkedLine.sight(about: Int?, most: Int = LINE_SHOWN): LineSight {
	if (text.length <= most)
		return LineSight(this)

	val from = ((about ?: 0) - most / 4).coerceIn(0, text.length - most)
	val to = from + most
	val before = "…".takeIf { from > 0 }.orEmpty()
	val sight = LineSight(this, from, before.length, most)

	return sight.copy(
		line =
			InkedLine(
				before + text.substring(from, to) + "…".takeIf { to < text.length }.orEmpty(),
				runs.mapNotNull { run -> sight.at(run.range)?.let { run.copy(range = it) } },
			),
	)
}

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
