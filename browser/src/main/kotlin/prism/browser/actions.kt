package prism.browser

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import java.nio.file.Path
import prism.preferences.ActionSpec
import prism.preferences.ActionsFile
import prism.preferences.AskSpec
import prism.preferences.PlaceSpec
import prism.preferences.Preferences
import prism.preferences.ShowSpec
import prism.preferences.Spec
import prism.preferences.TextSearchSpec
import prism.preferences.WhenSpec

/*
 * The user's own actions and places, as what they mean (docs/menus.md). The preferences write them as JSON
 * (preferences' actions.kt); here each is read into a value that can only say what can be done:
 *
 * - A command is a list of words, never text for a shell. A word is text with holes in it, each a placeholder of a
 *   kind that is known; a placeholder that stands for several words (`{paths}`) is a word alone. Whether a command is
 *   run once or once for each file, and whether it needs files on disk or entries of a place, are read off its
 *   placeholders, so nothing beside them can disagree with them.
 * - An action runs a command or shows somewhere, never both and never neither.
 * - What follows a command ("read the folder again") can only be said of a command prism watches.
 *
 * What does not read is kept as [Broken], with why, for the user to be told; the rest are read all the same.
 *
 * For example, `["bsdtar", "-xf", "{archive}", "-C", "{dest}", "--", "{entries}"]` is seven words; the third and fifth
 * are filled by values with names (the place's `archive`, a question's `dest`), and the last becomes as many words as
 * there are entries chosen, each one whole, whatever is in its name.
 */

/** Which part of a path a placeholder takes: `{archive}` the whole of it, `{archive.name}` its last name. */
enum class Part(
	val word: String?,
) {
	Whole(null),
	Parent("parent"),
	Name("name"),
	Stem("stem"),
	Ext("ext"),
	;

	/** This part of [text], read as a path. */
	fun of(text: String): String =
		when (this) {
			Whole -> text
			Parent -> text.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
			Name -> nameOf(text)
			Stem -> stemOf(nameOf(text))
			Ext -> extOf(nameOf(text))
		}

	private fun nameOf(text: String): String = text.trimEnd('/').substringAfterLast('/')
}

/** Where [name]'s extension starts: at `.tar.` and what follows, or at its last dot; a leading dot starts none. */
private fun extensionAt(name: String): Int? =
	(
		name.lastIndexOf(".tar.").takeIf { it > 0 }
			?: name.lastIndexOf('.').takeIf { it > 0 }
	)

/** [name] without its extension: `report` of `report.pdf`, `backup` of `backup.tar.gz`. */
fun stemOf(name: String): String =
	extensionAt(name)?.let { name.substring(0, it) } ?: name

/** [name]'s extension, without its dot: `tar.gz` of `backup.tar.gz`; nothing where it has none. */
fun extOf(name: String): String =
	extensionAt(name)?.let { name.substring(it + 1) }.orEmpty()

/** What a placeholder needs the things an action is for to be. */
enum class Need {
	/** Files and folders on disk: `{path}`. */
	Real,

	/** Entries of a place, which are nowhere on disk: `{entry}`. */
	Virtual,
}

/** A placeholder filled once for each thing, the command run once for each: `{path}`, `{name}`. */
enum class Each(
	val word: String,
	val need: Need?,
) {
	Path("path", Need.Real),
	Parent("parent", Need.Real),
	Name("name", null),
	Stem("stem", null),
	Ext("ext", null),
	Entry("entry", Need.Virtual),
}

/** A placeholder that becomes a word for every thing, the command run once: `{paths}`. */
enum class Every(
	val word: String,
	val need: Need?,
) {
	Paths("paths", Need.Real),
	Names("names", null),
	Entries("entries", Need.Virtual),
}

/** What fills a hole in a word. */
@Immutable
sealed interface Hole {
	/** Something of the thing the command is run for. */
	data class Of(
		val each: Each,
	) : Hole

	/** The folder shown, or, where none is, the folder the first thing is in. */
	data object Folder : Hole

	/** A value with a name: a question's answer, or one a place was opened with. */
	data class Value(
		val name: String,
		val part: Part,
	) : Hole
}

/** A piece of a word: text as it is, or a hole. */
@Immutable
sealed interface Piece {
	data class Text(
		val text: String,
	) : Piece

	data class Filled(
		val hole: Hole,
	) : Piece
}

/** Text with holes, which fills to one string: a word of a command, a folder to go to, a place's title. */
@Immutable
data class Phrase(
	val pieces: List<Piece>,
) {
	val holes: List<Hole> get() = pieces.filterIsInstance<Piece.Filled>().map { it.hole }

	/** This filled from [filling], for [thing] where it names one; null where something it names is not there. */
	fun fill(filling: Filling, thing: Thing? = filling.things.firstOrNull()): String? {
		val parts =
			pieces.map { piece ->
				when (piece) {
					is Piece.Text -> piece.text
					is Piece.Filled ->
						when (val hole = piece.hole) {
							is Hole.Of -> thing?.let { filling.of(hole.each, it) }
							Hole.Folder -> filling.folder?.toString()
							is Hole.Value -> filling.values[hole.name]?.let(hole.part::of)
						}
				}
			}

		return if (parts.any { it == null })
			null
		else
			parts.joinToString("")
	}

	companion object {
		private val HOLE = Regex("""\\\{|(?<!\$)\{([a-z][A-Za-z0-9_]*)(?:\.([a-z]+))?\}""")

		/**
		 * [text] read: `{name}` is a hole, unless a `$` is before it (a shell's own `${name}`) or a backslash is (`\{`,
		 * a brace as itself); anything else in braces (an awk program's `{ print }`) is text. [named] are the names
		 * values have here. A name that is none of them, and none of prism's, is refused, since a placeholder written
		 * wrong would otherwise be handed to the command as it stands.
		 */
		fun read(text: String, named: Set<String>): Reading<Phrase> {
			val pieces = ArrayList<Piece>()
			var at = 0

			for (match in HOLE.findAll(text)) {
				if (match.range.first > at)
					pieces += Piece.Text(text.substring(at, match.range.first))
				at = match.range.last + 1

				if (match.value == "\\{") {
					pieces += Piece.Text("{")
					continue
				}

				val name = match.groupValues[1]
				val part = match.groupValues[2]
				val each = Each.entries.firstOrNull { it.word == name }.takeIf { part.isEmpty() }

				pieces +=
					Piece.Filled(
						when {
							each != null -> Hole.Of(each)
							name == "folder" && part.isEmpty() -> Hole.Folder
							name in named ->
								Hole.Value(
									name,
									Part.entries.firstOrNull { it.word == part.ifEmpty { null } }
										?: return Reading.Wrong(
											"“${match.value}”: unknown part “.$part”. Available: " +
												"${Part.entries.mapNotNull { it.word }.joinToString { ".$it" }}",
										),
								)
							Every.entries.any { it.word == name } ->
								return Reading.Wrong(
									"“${match.value}” expands to several arguments, so it must be an argument on its own",
								)
							else ->
								return Reading.Wrong(
									"Unknown placeholder “${match.value}”" +
										named
											.takeIf { it.isNotEmpty() }
											?.let { names ->
												". Available here: ${names.sorted().joinToString { "{$it}" }}"
											}.orEmpty(),
								)
						},
					)
			}

			if (at < text.length)
				pieces += Piece.Text(text.substring(at))

			return Reading.Right(Phrase(pieces))
		}
	}
}

/** A word of a command: one string once filled, or as many as there are things. */
@Immutable
sealed interface Word {
	data class One(
		val phrase: Phrase,
	) : Word

	data class Spread(
		val every: Every,
	) : Word
}

/** What a command is filled from: the things it is for, the folder shown, and the values with names. */
@Immutable
data class Filling(
	val things: List<Thing>,
	val folder: Path?,
	val values: Map<String, String>,
) {
	fun of(each: Each, thing: Thing): String? =
		when (each) {
			Each.Path -> thing.path?.toString()
			Each.Parent -> thing.path?.parent?.toString()
			Each.Name -> thing.name
			Each.Stem -> stemOf(thing.name)
			Each.Ext -> extOf(thing.name)
			Each.Entry -> thing.key
		}

	fun of(every: Every): List<String>? =
		things
			.map {
				when (every) {
					Every.Paths -> it.path?.toString()
					Every.Names -> it.name
					Every.Entries -> it.key
				}
			}.takeIf { all -> all.none { it == null } }
			?.filterNotNull()
}

/** A command: its words. How many times it is run, and what it needs, are what its placeholders say. */
@Immutable
data class Command(
	val words: List<Word>,
) {
	private val holes: List<Hole> get() =
		words.filterIsInstance<Word.One>().flatMap { it.phrase.holes }

	/** Whether it is run once for each thing, as a command that names one thing (`{path}`) is. */
	val forEach: Boolean get() = holes.any { it is Hole.Of }

	/** Whether it names the things at all; one that does not is run once whatever is chosen. */
	val named: Boolean get() = forEach || words.any { it is Word.Spread }

	val needs: Set<Need> get() =
		(
			holes.filterIsInstance<Hole.Of>().map { it.each.need } +
				words.filterIsInstance<Word.Spread>().map { it.every.need }
		).filterNotNullTo(LinkedHashSet())

	val folder: Boolean get() = Hole.Folder in holes

	/** Each run of this, as its words, filled from [filling]; null where something it names is not there. */
	fun fill(filling: Filling): List<List<String>>? {
		fun run(thing: Thing?): List<String>? =
			words
				.map { word ->
					when (word) {
						is Word.One -> word.phrase.fill(filling, thing)?.let(::listOf)
						is Word.Spread -> filling.of(word.every)
					}
				}.takeIf { all -> all.none { it == null } }
				?.flatMap { it.orEmpty() }

		val runs =
			if (forEach)
				filling.things.map(::run)
			else
				listOf(run(null))

		return runs.takeIf { all -> all.none { it == null } }?.filterNotNull()
	}

	companion object {
		private val WHICH = Regex("""\{which:([^{}]+)\}""")

		/**
		 * [words] read. A word that is `{which:a|b|c}` is the first of those programs that is [installed]; where none
		 * is, the command is [missing][Reading.Wrong.missing] rather than wrong: it is written rightly, for a machine
		 * that has one of them.
		 */
		fun read(
			words: List<String>,
			named: Set<String>,
			installed: (String) -> Boolean = { true },
		): Reading<Command> {
			val chosen =
				words.map { word ->
					WHICH.matchEntire(word)?.let { which ->
						val programs = which.groupValues[1].split('|').map { it.trim() }

						programs.firstOrNull(installed)
							?: return Reading.Wrong(
								"None of these programs is installed: ${programs.joinToString()}",
								missing = true,
							)
					} ?: word
				}

			return read(chosen, named)
		}

		private fun read(words: List<String>, named: Set<String>): Reading<Command> {
			if (words.isEmpty() || words.first().isBlank())
				return Reading.Wrong("The command is empty")

			val read =
				words.map { word ->
					Every.entries
						.firstOrNull {
							"{${it.word}}" == word
						}?.let { Reading.Right(Word.Spread(it)) }
						?: Phrase.read(word, named).map { Word.One(it) }
				}

			read.filterIsInstance<Reading.Wrong>().firstOrNull()?.let { return it }

			val command = Command(read.map { (it as Reading.Right).value })

			return when {
				command.forEach && command.words.any { it is Word.Spread } -> {
					val one =
						command.holes
							.filterIsInstance<Hole.Of>()
							.first()
							.each.word

					val all =
						command.words
							.filterIsInstance<Word.Spread>()
							.first()
							.every.word

					Reading.Wrong(
						"It uses both {$one} (one file at a time) and {$all} (all files at once). Use one or the other",
					)
				}
				command.needs.size > 1 ->
					Reading.Wrong(
						"It uses both files on disk and entries of a place. It can only be for one",
					)
				else -> Reading.Right(command)
			}
		}
	}
}

/** What reading something written came to. */
sealed interface Reading<out T> {
	data class Right<T>(
		val value: T,
	) : Reading<T>

	/** Not read: [why]. [missing] where it is written rightly and only needs a program this machine has not got. */
	data class Wrong(
		val why: String,
		val missing: Boolean = false,
	) : Reading<Nothing>

	fun <R> map(change: (T) -> R): Reading<R> =
		when (this) {
			is Right -> Right(change(value))
			is Wrong -> this
		}

	fun <R> then(next: (T) -> Reading<R>): Reading<R> =
		when (this) {
			is Right -> next(value)
			is Wrong -> this
		}
}

// ---- when an action is offered -------------------------------------------------------------------------------------

/** What a menu is a menu of, as an action's conditions name it. */
enum class On(
	val word: String,
) {
	/** Rows of the listing. */
	Files("files"),

	/** The folder shown itself: the listing pressed where no row is. */
	Folder("folder"),

	/** A place of the sidebar. */
	Place("place"),
}

/** How many things. */
enum class Count(
	val word: String,
	val holds: (Int) -> Boolean,
) {
	One("one", { it == 1 }),
	Some("some", { it >= 1 }),
	Many("many", { it >= 2 }),
}

/** What a thing is. */
enum class ThingKind(
	val word: String,
) {
	File("file"),
	Folder("folder"),
	Link("link"),
}

/**
 * When an action is offered: every condition that is said (not null) must hold. None of them runs anything: each is
 * decided from what prism knows of the things already, or (for [inside]) a few looks at the disk, so a menu never
 * waits on a program to open.
 */
@Immutable
data class Conditions(
	/** What the menu must be of; whatever it is of, where null, as a text search is offered unless it says. */
	val on: On? = On.Files,
	val types: List<String>? = null,
	val names: List<Regex>? = null,
	val kind: ThingKind? = null,
	val count: Count? = null,
	val under: List<String>? = null,
	val inside: List<String>? = null,
	val programs: List<String>? = null,
	val within: List<String>? = null,
	val elsewhere: Boolean? = null,
	val real: Boolean? = null,
	val with: Map<String, List<Regex>>? = null,
	val not: Conditions? = null,
) {
	/** Whether these hold for [subject], in [world]. */
	fun hold(subject: Subject, world: World): Boolean =
		(on == null || on == subject.on) && holdBut(subject, world)

	/** As [hold], but for what the menu is of, which a negation does not say again. */
	private fun holdBut(subject: Subject, world: World): Boolean {
		val things = subject.things

		fun each(holds: (Thing) -> Boolean) = things.all(holds)

		return listOf<() -> Boolean>(
			{
				types == null ||
					each { thing -> world.lineage(thing).any { type -> types.any { typed(it, type) } } }
			},
			{ names == null || each { thing -> names.any { it.matches(thing.name) } } },
			{ kind == null || each { it.kind == kind } },
			{ count == null || count.holds(things.size) },
			{
				under == null ||
					each { thing ->
						thing.path?.let { path ->
							under.any { path.startsWith(pathOf(it, null, world.home) ?: return@any false) }
						} ?: false
					}
			},
			{
				inside == null ||
					each { thing ->
						thing.path?.let { path ->
							generateSequence(path) { it.parent }.any { folder ->
								inside.any { world.exists(folder.resolve(it)) }
							}
						} ?: false
					}
			},
			{
				programs == null ||
					programs.all { any -> any.split('|').any { world.installed(it.trim()) } }
			},
			{ within == null || subject.where.word in within },
			{
				elsewhere == null ||
					elsewhere ==
					(
						things.isNotEmpty() &&
							each { thing ->
								thing.path?.let { it.parent != (subject.where as? Where.Folder)?.path } ?: false
							}
					)
			},
			{ real == null || each { (it.path != null) == real } },
			{
				with == null ||
					(subject.where as? Where.Place)?.placed?.values?.let { values ->
						with.all { (name, globs) ->
							values[name]?.let { value -> globs.any { it.matches(value) } }
								?: false
						}
					} ?: false
			},
			{ not == null || !not.holdBut(subject, world) },
		).all { it() }
	}

	private fun typed(pattern: String, type: String): Boolean =
		if (pattern.endsWith("/*"))
			type.startsWith(pattern.dropLast(1))
		else
			pattern == type

	companion object {
		/** [spec] read: for a menu of [unsaid], where it does not say what of. */
		fun read(spec: WhenSpec, unsaid: On? = On.Files): Reading<Conditions> {
			fun <T> one(
				text: String?,
				of: List<T>,
				word: (T) -> String,
				what: String,
			): Reading<T?> =
				when (text) {
					null -> Reading.Right(null)
					else ->
						of.firstOrNull { word(it) == text }?.let { Reading.Right(it) }
							?: Reading.Wrong("“$text” is not $what. Options: ${of.joinToString { word(it) }}")
				}

			return one(spec.target, On.entries, On::word, "a valid target").then { on ->
				one(spec.kind, ThingKind.entries, ThingKind::word, "a valid kind").then { kind ->
					one(spec.count, Count.entries, Count::word, "a valid count").then { count ->
						(spec.not?.let { read(it) } ?: Reading.Right(null)).map { not ->
							Conditions(
								on = on ?: unsaid,
								types = spec.types,
								names =
									(
										spec.names.orEmpty().map(::globOf) +
											spec.ext.orEmpty().map {
												Regex(".*\\." + Regex.escape(it.removePrefix(".")), RegexOption.IGNORE_CASE)
											}
									).takeIf { spec.names != null || spec.ext != null },
								kind = kind,
								count = count,
								under = spec.under,
								inside = spec.inside,
								programs = spec.programs,
								within = spec.within,
								elsewhere = spec.elsewhere,
								real = spec.real,
								with = spec.with?.mapValues { (_, globs) -> globs.map(::globOf) },
								not = not,
							)
						}
					}
				}
			}
		}
	}
}

/** What the browser knows of where it runs, that conditions are decided by, without running anything. */
interface World {
	val home: Path

	/** [thing]'s type and each type it is a kind of, nearest first, as their names. */
	fun lineage(thing: Thing): List<String>

	/** Whether [program] is on the `PATH`. */
	fun installed(program: String): Boolean

	fun exists(path: Path): Boolean

	/** A world that knows nothing: no types, no programs, nothing on disk. */
	data class Bare(
		override val home: Path,
	) : World {
		override fun lineage(thing: Thing): List<String> = emptyList()

		override fun installed(program: String): Boolean = false

		override fun exists(path: Path): Boolean = false
	}
}

/** The world the view decides the conditions of the user's actions by: the session's, given by whoever runs one. */
val LocalWorld =
	staticCompositionLocalOf<World> {
		World.Bare(
			java.nio.file.Path
				.of("/"),
		)
	}

/** Something an action is for: a file or a folder on disk, or an entry of a place. */
@Immutable
data class Thing(
	val item: Item,
	val name: String,
	val kind: ThingKind,
) {
	val path: Path? get() = (item as? Item.OnDisk)?.path

	/** What an entry of a place is called there, as its place's commands name it. */
	val key: String? get() = (item as? Item.InPlace)?.key

	companion object {
		fun of(row: Row): Thing =
			Thing(
				row.item,
				row.name,
				when {
					row.isFolder -> ThingKind.Folder
					row.category == Category.Link -> ThingKind.Link
					else -> ThingKind.File
				},
			)

		/** The folder at [path]. */
		fun folder(path: Path): Thing =
			Thing(
				Item.OnDisk(path),
				path.fileName?.toString() ?: path.toString(),
				ThingKind.Folder,
			)

		/** [path], taken to be a file: something given to the browser, of which no more is known. */
		fun file(path: Path): Thing =
			Thing(Item.OnDisk(path), path.fileName?.toString() ?: path.toString(), ThingKind.File)
	}
}

/** Where a window is, as an action's conditions name it. */
@Immutable
sealed interface Where {
	val word: String

	/** The values with names being there adds, for a command to name. */
	val values: Map<String, String> get() = emptyMap()

	data class Folder(
		val path: Path,
	) : Where {
		override val word: String get() = "folder"
	}

	data object Results : Where {
		override val word: String get() = "results"
	}

	/** The trash, where nothing but putting back and deleting for good is done. */
	data object Trash : Where {
		override val word: String get() = "trash"
	}

	/** The System page, which lists no files for actions to be done to. */
	data object System : Where {
		override val word: String get() = "system"
	}

	/** In [placed], in the folder of it at [within]. */
	data class Place(
		val placed: Placed,
		val within: List<String> = emptyList(),
	) : Where {
		override val word: String get() = placed.type.id

		/**
		 * What the place adds to the values with names: those it was opened with, and where in it the window is:
		 * `within`, the folder shown as a path with a `/` after it (empty at the top), and `depth`, how many folders
		 * deep that is. A command that brings entries out uses them to leave the folders above behind.
		 */
		override val values: Map<String, String> get() =
			placed.values +
				mapOf(
					"within" to within.joinToString("") { "$it/" },
					"depth" to within.size.toString(),
				)
	}

	/**
	 * Among the lines [search] found, on one of them: [values] are what is said of that line (its number, its text,
	 * and what was looked for), which an action offered for it can name.
	 */
	data class Text(
		val search: TextSearch,
		override val values: Map<String, String> = emptyMap(),
	) : Where {
		override val word: String get() = search.id
	}
}

/** What an action is offered for, and done to: what the menu is of, the things, and where the window is. */
@Immutable
data class Subject(
	val on: On,
	val things: List<Thing>,
	val where: Where,
) {
	/** The folder shown, or, where the window is in none, the folder the first thing is in. */
	val folder: Path? get() =
		(where as? Where.Folder)?.path ?: things.firstNotNullOfOrNull { it.path?.parent }

	/** What commands are filled from, with [answers] to what was asked. */
	fun filling(answers: Map<String, String> = emptyMap()): Filling =
		Filling(things, folder, where.values + answers)
}

// ---- an action ---------------------------------------------------------------------------------------------------

/** Something asked for before an action is done; its answer is a value named [name]. */
@Immutable
sealed interface Question {
	val name: String
	val prompt: String

	/** A line of text, starting as [start]. */
	data class Text(
		override val name: String,
		override val prompt: String,
		val start: Phrase?,
	) : Question

	/** A folder, chosen in a picker that opens on [start]; or the folder something is dropped on, or pasted into. */
	data class Folder(
		override val name: String,
		override val prompt: String,
		val start: Phrase?,
	) : Question
}

/** What follows a watched command that worked. */
@Immutable
data class Then(
	val reread: Boolean = false,
	val choose: Phrase? = null,
	val bring: Bring? = null,
)

/** What a command put in [from] brought into [to], by name and over nothing: see [prism.preferences.BringSpec]. */
@Immutable
data class Bring(
	val from: Phrase,
	val to: Phrase,
)

/** How a command is run. */
@Immutable
sealed interface How {
	/** Started as a process of its own, and forgotten. */
	data object Detached : How

	/** Started in the user's terminal. */
	data object Terminal : How

	/** Run by prism: shown while it runs, what it wrote to standard error said if it fails, and [then] if it works. */
	data class Watched(
		val then: Then,
	) : How
}

/** Somewhere an action goes. */
@Immutable
sealed interface Showing {
	/** A folder on disk, the cursor on [choose] in it. */
	data class Folder(
		val path: Phrase,
		val choose: Phrase?,
	) : Showing

	/** The place declared as [place], opened [with] its values. */
	data class Place(
		val place: String,
		val with: Map<String, Phrase>,
	) : Showing
}

/** What an action does: it runs something, or it shows somewhere. */
@Immutable
sealed interface Deed {
	data class Run(
		val command: Command,
		val how: How,
	) : Deed

	/** [what] shown: in a window of its own where [window] says so, here where it says not, and as the place says. */
	data class Show(
		val what: Showing,
		val window: Boolean?,
	) : Deed
}

/** An action of the user's: when it is offered, what it asks, and what it does. */
@Immutable
data class UserAction(
	val id: String,
	val label: String,
	val conditions: Conditions,
	val questions: List<Question>,
	/** Answers given beforehand: questions an action made [like][ActionSpec.like] another does not ask. */
	val given: Map<String, Phrase>,
	val deed: Deed,
	/** A question to answer yes before it is done. */
	val confirm: String?,
	/** The submenus it is under, outermost first; none for the menu itself. */
	val submenu: List<String>,
	/** The chord that does it until the user binds another. */
	val key: Chord?,
	/** Whether a double click on what it is offered for does it. */
	val default: Boolean,
) {
	private val phrases: List<Phrase> get() =
		given.values +
			questions.mapNotNull {
				when (it) {
					is Question.Text -> it.start
					is Question.Folder -> it.start
				}
			} +
			when (deed) {
				is Deed.Run ->
					(deed.how as? How.Watched)?.then.let {
						listOfNotNull(it?.choose, it?.bring?.from, it?.bring?.to)
					}
				is Deed.Show ->
					when (val what = deed.what) {
						is Showing.Folder -> listOfNotNull(what.path, what.choose)
						is Showing.Place -> what.with.values.toList()
					}
			}

	/** What it needs the things it is for to be. */
	val needs: Set<Need> get() =
		(deed as? Deed.Run)?.command?.needs.orEmpty() +
			phrases.flatMap { it.holes }.filterIsInstance<Hole.Of>().mapNotNull { it.each.need }

	/** Whether it names the things it is for, and so needs there to be some. */
	private val named: Boolean get() =
		(deed as? Deed.Run)?.command?.named == true ||
			phrases.any { phrase -> phrase.holes.any { it is Hole.Of } }

	private val folder: Boolean get() =
		(deed as? Deed.Run)?.command?.folder == true || phrases.any { Hole.Folder in it.holes }

	/**
	 * Whether it is offered for [subject]: its conditions hold, and what its placeholders name is there. So a command
	 * with `{path}` in it is never offered for an entry of an archive, without a condition having to say so.
	 */
	fun offered(subject: Subject, world: World): Boolean =
		conditions.hold(subject, world) && fits(subject)

	/** Whether what its placeholders name is there in [subject], its conditions aside. */
	fun fits(subject: Subject): Boolean =
		(!named || subject.things.isNotEmpty()) &&
			(Need.Real !in needs || subject.things.all { it.path != null }) &&
			(Need.Virtual !in needs || subject.things.all { it.key != null }) &&
			(!folder || subject.folder != null)

	/** Whether its command names `{temp}`: a folder made for it to work in, and removed when it ends. */
	val scratch: Boolean get() =
		(
			(deed as? Deed.Run)?.command?.words.orEmpty().filterIsInstance<Word.One>().map {
				it.phrase
			} +
				phrases
		).any { phrase -> phrase.holes.any { it is Hole.Value && it.name == TEMP } }

	/** The one question a drop, or a paste, answers with its folder; null where there is not exactly one. */
	val destination: Question.Folder? get() =
		questions
			.filterIsInstance<Question.Folder>()
			.filter { it.name !in given }
			.singleOrNull()
}

/** The value every action's command can name: a folder made for it, empty, and removed when the command ends. */
const val TEMP = "temp"

/** How what a place's command prints is read. */
enum class PlaceRead(
	val word: String,
) {
	/** Each a file on disk, by its path. */
	Paths("paths"),

	/** Each an entry's place in the tree; then, apart by tabs, `size=`, `time=`, `path=` and `kind=`, as many as are said. */
	Names("names"),

	/** Each a JSON object: `at`, and `path`, `size`, `time` and `kind` where they are said. */
	Entries("entries"),
}

/** A place, declared: what a window shows that is not a folder on disk, as a command lists it. */
@Immutable
data class PlaceType(
	val id: String,
	val title: Phrase?,
	/** The values it is opened with, by name. */
	val takes: List<String>,
	/** Whether an action that shows it opens a window for it, unless the action says where. */
	val window: Boolean,
	val list: Command,
	/** The folder [list] is run in. */
	val folder: Phrase?,
	val read: PlaceRead,
	val tree: Boolean,
	val nul: Boolean,
	/** Whether a window in it shows the sidebar of places on disk. */
	val sidebar: Boolean = true,
	/** The action that brings its entries out, into a folder: by id, among the actions. */
	val out: String?,
	/** The action that takes files in. */
	val into: String?,
)

/** A place open: its type, and the values it was opened with. As a path is to a folder, this is what says where. */
@Immutable
data class Placed(
	val type: PlaceType,
	val values: Map<String, String>,
) {
	val title: String get() =
		type.title?.fill(Filling(emptyList(), null, values)) ?: type.id
}

/** An action or a place that did not read, and why: said to the user, where the rest are read all the same. */
@Immutable
data class Broken(
	val what: String,
	val id: String?,
	val why: String,
) {
	override fun toString(): String = "$what ${id?.let { "“$it”" } ?: "(with no id)"}: $why"
}

/**
 * Every action, place and text search there is: prism's own (the defaults) and the user's, which replace them by id.
 */
@Immutable
data class Actions(
	val actions: List<UserAction>,
	val places: Map<String, PlaceType>,
	val broken: List<Broken>,
	/** The ids of menu entries the user has hidden, prism's own among them. */
	val hidden: Set<String> = emptySet(),
	/** The ids of actions and places written rightly that cannot be run here: none of the programs they need is installed. */
	val unavailable: Set<String> = emptySet(),
	/** The text searches, in the order they are written. */
	val searches: List<TextSearch> = emptyList(),
	/** What opens a file at a line: the user's editor. */
	val editor: Editor = Editor.Application,
) {
	/** The text searches offered for [subject], in the order they are written, those hidden left out. */
	fun searching(subject: Subject, world: World): List<TextSearch> =
		searches.filter { it.id !in hidden && it.offered(subject, world) }

	/** The text search called [id], where there is one. */
	fun search(id: String): TextSearch? = searches.firstOrNull { it.id == id }

	private val byId: Map<String, UserAction> by lazy { actions.associateBy { it.id } }

	operator fun get(id: String): UserAction? = byId[id]

	/** The actions offered for [subject], in the order they are written, those hidden left out. */
	fun offered(subject: Subject, world: World): List<UserAction> =
		actions.filter { it.id !in hidden && it.offered(subject, world) }

	/** What bringing entries out of [placed] does, where it says. */
	fun outOf(placed: Placed): UserAction? = placed.type.out?.let(::get)

	/** What giving files to [placed] does, where it says. */
	fun into(placed: Placed): UserAction? = placed.type.into?.let(::get)

	companion object {
		val NONE = Actions(emptyList(), emptyMap(), emptyList())

		/** What the editor is known by, where it is said not to read. */
		private const val EDITOR = "editor"

		/** The values every place has, beside those it takes: where in it the window is. */
		private val IN_PLACE = setOf("within", "depth")

		/** Prism's own actions and places, which ship with it (resources' actions.json). */
		val SHIPPED: ActionsFile by lazy {
			ActionsFile.parse(
				Actions::class.java
					.getResourceAsStream("/prism/browser/actions.json")
					?.use { it.readBytes().decodeToString() }
					?: "{}",
			)
		}

		/** Prism's own and [preferences]' together. */
		fun of(
			preferences: Preferences,
			environment: (String) -> String? = System::getenv,
			installed: (String) -> Boolean = { true },
		): Actions =
			read(
				listOf(SHIPPED, preferences.written),
				preferences.menu.hidden.toSet(),
				environment,
				installed,
			)

		/**
		 * Every action and place in [files], a later file's replacing an earlier one's of the same id. Where one has
		 * several ways, it is read as the first whose programs are [installed]; one none of whose ways can be run here
		 * is left out, without being said to be wrong ([Actions.unavailable]). Unless told, every program is taken to
		 * be installed, which reads everything, as its first way. The editor is the last file's that names one, or
		 * the one [environment] names.
		 */
		fun read(
			files: List<ActionsFile>,
			hidden: Set<String> = emptySet(),
			environment: (String) -> String? = { null },
			installed: (String) -> Boolean = { true },
		): Actions {
			val broken = ArrayList<Broken>()
			val unavailable = LinkedHashSet<String>()

			fun <T> written(
				specs: List<Spec<T>>,
				what: String,
				id: (T) -> String,
			): Map<String, T> =
				specs
					.mapNotNull {
						when (it) {
							is Spec.Read -> it.value
							is Spec.Unread -> null.also { _ -> broken += Broken(what, it.id, it.why) }
						}
					}.associateBy(id)

			val placeSpecs = written(files.flatMap { it.placeSpecs() }, "place") { it.id }
			val actionSpecs = written(files.flatMap { it.actionSpecs() }, "action") { it.id }
			val searchSpecs =
				written(files.flatMap { it.textSearchSpecs() }, "text search") { it.id }

			fun <S, T> meant(
				specs: Map<String, S>,
				what: String,
				read: (S) -> Reading<T>,
			): Map<String, T> =
				specs
					.mapNotNull { (id, spec) ->
						when (val reading = read(spec)) {
							is Reading.Right -> id to reading.value
							is Reading.Wrong ->
								null.also {
									if (reading.missing)
										unavailable += id
									else
										broken += Broken(what, id, reading.why)
								}
						}
					}.toMap()

			val places = meant(placeSpecs, "place") { placeOf(it, installed) }
			val searches = meant(searchSpecs, "text search") { searchOf(it, installed) }
			// What an action that says it is only in a place, or among a text search's lines, can name there.
			val taken =
				placeSpecs.mapValues { it.value.takes.toSet() + IN_PLACE } +
					searches.mapValues { it.value.named }

			val plain =
				meant(actionSpecs.filterValues { it.like == null }, "action") {
					// A place that cannot be listed here is not one to show, which is nothing wrong with the action.
					if (it.show?.place in unavailable)
						Reading.Wrong("The place it opens isn't available here", missing = true)
					else
						actionOf(it, places, taken, installed)
				}

			val alike =
				meant(
					actionSpecs.filterValues {
						it.like != null
					},
					"action",
				) {
					if (it.like in unavailable)
						Reading.Wrong("The action it is based on isn't available here", missing = true)
					else
						likeOf(it, plain, taken)
				}

			val all = actionSpecs.keys.mapNotNull { plain[it] ?: alike[it] }

			// A place's way out and way in are actions that are there, and that a drop can be the doing of.
			val sound =
				places
					// A way out or in that cannot be run here is no way: the place is still one to look at.
					.mapValues { (_, place) ->
						place.copy(
							out = place.out.takeUnless { it in unavailable },
							into = place.into.takeUnless { it in unavailable },
						)
					}.filterValues { place ->
						val wrong =
							listOfNotNull(
								place.out?.let { id ->
									val out = all.firstOrNull { it.id == id }

									when {
										out == null -> "Its “out” action, “$id”, doesn't exist"
										out.destination == null ->
											"Its “out” action, “$id”, must ask for exactly one folder"
										(out.deed as? Deed.Run)?.how !is How.Watched ->
											"Its “out” action, “$id”, must be a watched command"
										else -> null
									}
								},
								place.into?.let { id ->
									"Its “in” action, “$id”, doesn't exist".takeIf { all.none { it.id == id } }
								},
							).firstOrNull()

						wrong?.let { broken += Broken("place", place.id, it) }
						wrong == null
					}

			val editor =
				when (
					val read =
						Editor.of(
							files.lastOrNull { it.editor != null }?.editor,
							environment,
						)
				) {
					is Reading.Right -> read.value
					is Reading.Wrong ->
						Editor.Application.also { broken += Broken("editor", EDITOR, read.why) }
				}

			return Actions(
				all,
				sound,
				broken,
				hidden,
				unavailable,
				searchSpecs.keys.mapNotNull(searches::get),
				editor,
			)
		}

		/**
		 * The command among [ways] to run here: the first whose programs are [installed]. Every way is read, so that
		 * one written wrong is said to be on any machine; with none that can be run here, it is missing.
		 */
		private fun wayOf(
			ways: List<Pair<List<String>, List<String>>>,
			named: Set<String>,
			installed: (String) -> Boolean,
		): Reading<Command> = wayAt(ways, named, installed).map { it.value }

		/** As [wayOf], with which of [ways] it is: its place among them. */
		private fun wayAt(
			ways: List<Pair<List<String>, List<String>>>,
			named: Set<String>,
			installed: (String) -> Boolean,
		): Reading<IndexedValue<Command>> {
			ways.forEach { (_, words) ->
				(Command.read(words, named) as? Reading.Wrong)?.let { return it }
			}

			val runnable =
				ways.withIndex().firstOrNull { (_, way) ->
					way.first.all { any -> any.split('|').any { installed(it.trim()) } } &&
						Command.read(way.second, named, installed) is Reading.Right
				}

			return runnable?.let { (at, way) ->
				Command.read(way.second, named, installed).map { IndexedValue(at, it) }
			} ?: Reading.Wrong("None of the programs it can use is installed", missing = true)
		}

		private fun placeOf(
			spec: PlaceSpec,
			installed: (String) -> Boolean,
		): Reading<PlaceType> {
			val named = spec.takes.toSet()
			val list = spec.list
			// Each way with its command; none, for a way that has none.
			val ways = spec.ways.map { way -> way.list?.let { way.programs to it } }

			val listing =
				when {
					list != null && ways.isNotEmpty() ->
						Reading.Wrong("It has both “list” and “ways”. Use one or the other")
					list != null -> Command.read(list, named, installed)
					null in ways -> Reading.Wrong("Each of its ways needs a “list”")
					ways.isNotEmpty() -> wayOf(ways.filterNotNull(), named, installed)
					else -> Reading.Wrong("It needs a “list” command")
				}

			return listing.then { list ->
				if (list.named)
					Reading.Wrong("A place's command can't use the selected files")
				else
					phrase(spec.title, named).then { title ->
						phrase(spec.folder, named).then { folder ->
							word(
								spec.read ?: PlaceRead.Paths.word,
								PlaceRead.entries,
								PlaceRead::word,
								"a valid way to read",
							).then { read ->
								window(spec.opens).map { window ->
									PlaceType(
										id = spec.id,
										title = title,
										takes = spec.takes,
										window = window ?: false,
										list = list,
										folder = folder,
										read = read,
										tree = spec.tree,
										nul = spec.nul,
										sidebar = spec.sidebar,
										out = spec.out,
										into = spec.into,
									)
								}
							}
						}
					}
			}
		}

		/** The values with names in an action that says it is only [within] these places: those every one takes. */
		private fun namedIn(
			within: List<String>?,
			taken: Map<String, Set<String>>,
		): Set<String> =
			within
				?.map { taken[it].orEmpty() }
				?.reduceOrNull { all, its -> all intersect its }
				.orEmpty()

		/** A text search, as it is declared: what it says checked, and its commands read. */
		private fun searchOf(
			spec: TextSearchSpec,
			installed: (String) -> Boolean,
		): Reading<TextSearch> {
			val run = spec.run
			// Each way with its command; none, for a way that has none.
			val ways = spec.ways.map { way -> way.run?.let { way.programs to it } }
			val asked = setOf(TextSearch.QUERY, TextSearch.OPTIONS)

			// The command, and the way it is, where it is one of several: which may say how its own output is read.
			val finding: Reading<Pair<Command, prism.preferences.WaySpec?>> =
				when {
					run != null && ways.isNotEmpty() ->
						Reading.Wrong("It has both “run” and “ways”. Use one or the other")
					run != null -> Command.read(run, asked, installed).map { it to null }
					null in ways -> Reading.Wrong("Each of its ways needs a “run”")
					ways.isNotEmpty() ->
						// Every way's reading is checked, so that one written wrong is said to be on any machine.
						spec.ways
							.mapNotNull { it.read }
							.map { word(it, HitRead.entries, HitRead::word, "a valid way to read") }
							.filterIsInstance<Reading.Wrong>()
							.firstOrNull()
							?: wayAt(ways.filterNotNull(), asked, installed).map {
								it.value to spec.ways[it.index]
							}
					else -> Reading.Wrong("It needs a “run” command")
				}

			return finding.then { (command, way) ->
				val options =
					command.words
						.filterIsInstance<Word.One>()
						.count { word ->
							word.phrase.holes.any {
								it is Hole.Value &&
									it.name == TextSearch.OPTIONS
							}
						}

				when {
					command.named ->
						Reading.Wrong(
							"Its command is given the files on its standard input, and can't use the selected files",
						)
					options != command.words.count { it.isOptions } ->
						Reading.Wrong(
							"“{options}” expands to several arguments, so it must be an argument on its own",
						)
					else ->
						word(
							spec.query ?: QueryUse.Typed.word,
							QueryUse.entries,
							QueryUse::word,
							"a valid use of the query",
						).then { query ->
							word(
								way?.read ?: spec.read ?: HitRead.Lines.word,
								HitRead.entries,
								HitRead::word,
								"a valid way to read",
							).then { read ->
								word(
									spec.positions ?: Positions.Lines.word,
									Positions.entries,
									Positions::word,
									"a valid kind of position",
								).then { positions ->
									// What is said of a line found, which a command for it can name beside the file itself.
									val named = setOf(positions.hole, TextSearch.TEXT, TextSearch.QUERY)

									previewOf(spec, named, installed).then { preview ->
										openerOf(spec.open, named, installed).then { open ->
											Conditions.read(spec.conditions, unsaid = null).then { conditions ->
												keyOf(spec.key).map { key ->
													TextSearch(
														id = spec.id,
														label = spec.label,
														conditions = conditions,
														submenu = submenuOf(spec.submenu),
														key = key,
														query = query,
														options =
															spec.options.map { (name, option) ->
																TextOption(name, option.label, option.on, option.off)
															},
														run = command,
														read = read,
														nul = way?.nul ?: spec.nul,
														positions = positions,
														preview = preview,
														open = open,
													)
												}
											}
										}
									}
								}
							}
						}
				}
			}
		}

		/**
		 * The command that shows a file beside a line found in it: the one said, or the first of several whose programs
		 * are installed; none, where none is said or none of them can be run here, and prism reads the file itself.
		 */
		private fun previewOf(
			spec: TextSearchSpec,
			named: Set<String>,
			installed: (String) -> Boolean,
		): Reading<Command?> {
			val preview = spec.preview
			val ways = spec.previews.map { way -> way.run?.let { way.programs to it } }

			val read: Reading<Command?> =
				when {
					preview != null && ways.isNotEmpty() ->
						Reading.Wrong("It has both “preview” and “previews”. Use one or the other")
					preview != null -> Command.read(preview, named, installed)
					null in ways -> Reading.Wrong("Each of its previews needs a “run”")
					ways.isNotEmpty() ->
						when (val way = wayOf(ways.filterNotNull(), named, installed)) {
							is Reading.Right -> way
							is Reading.Wrong ->
								if (way.missing)
									Reading.Right(null)
								else
									way
						}
					else -> Reading.Right(null)
				}

			return read.then { command ->
				if (command != null && fileless(command))
					Reading.Wrong("Its preview must name the file to show, as {path}")
				else
					Reading.Right(command)
			}
		}

		/** Whether [command] names no one file on disk, which a command for a line found in one must. */
		private fun fileless(command: Command): Boolean =
			!command.forEach || command.needs != setOf(Need.Real)

		/** What opens a file at a line, as it is written; [named] are what is said of the line. */
		internal fun openerOf(
			spec: prism.preferences.OpenSpec?,
			named: Set<String>,
			installed: (String) -> Boolean = { true },
		): Reading<Opener?> =
			when (spec) {
				null -> Reading.Right(null)
				else ->
					Command.read(spec.run, named, installed).then { command ->
						if (fileless(command))
							Reading.Wrong("What opens a file must name it, as {path}")
						else
							Reading.Right(Opener(command, spec.terminal))
					}
			}

		private fun actionOf(
			spec: ActionSpec,
			places: Map<String, PlaceType>,
			/** What each place written takes, whether or not it can be listed here: what its actions may name. */
			taken: Map<String, Set<String>>,
			installed: (String) -> Boolean,
		): Reading<UserAction> {
			val named = namedIn(spec.conditions.within, taken) + spec.ask.keys + TEMP
			val run = spec.run
			val show = spec.show
			// Each way with its command; none, for a way that has none.
			val ways = spec.ways.map { way -> way.run?.let { way.programs to it } }
			val runs = run != null || ways.isNotEmpty()

			val deed: Reading<Deed> =
				when {
					runs && show != null ->
						Reading.Wrong(
							"It has both “run” and “show”. Use one or the other",
						)
					run != null && ways.isNotEmpty() ->
						Reading.Wrong("It has both “run” and “ways”. Use one or the other")
					null in ways -> Reading.Wrong("Each of its ways needs a “run”")
					runs ->
						(
							run?.let { Command.read(it, named, installed) }
								?: wayOf(ways.filterNotNull(), named, installed)
						).then { command ->
							howOf(spec, named).map { Deed.Run(command, it) }
						}
					show != null ->
						if (spec.how.terminal || spec.how.watched ||
							spec.then != prism.preferences.ThenSpec()
						)
							Reading.Wrong("“how” and “then” only apply to a command, not to “show”")
						else
							showingOf(show, named, places)
					else ->
						Reading.Wrong(
							"It needs a “run” or a “show”",
						)
				}

			return deed.then { did ->
				Conditions.read(spec.conditions).then { conditions ->
					questionsOf(spec.ask, named).then { questions ->
						if (spec.given.isNotEmpty())
							Reading.Wrong(
								"“given” is only allowed with “like”",
							)
						else
							keyOf(spec).map { key ->
								UserAction(
									spec.id,
									spec.label,
									conditions,
									questions,
									emptyMap(),
									did,
									spec.how.confirm,
									submenuOf(spec.submenu),
									key,
									spec.default,
								)
							}
					}
				}
			}
		}

		/** An action that does what another does, some of its questions answered beforehand. */
		private fun likeOf(
			spec: ActionSpec,
			plain: Map<String, UserAction>,
			taken: Map<String, Set<String>>,
		): Reading<UserAction> {
			val base =
				plain[spec.like]
					?: return Reading.Wrong(
						"“like” names “${spec.like}”, which doesn't exist or is itself based on another action",
					)

			if (spec.run != null || spec.ways.isNotEmpty() || spec.show != null ||
				spec.ask.isNotEmpty()
			)
				return Reading.Wrong(
					"It is based on “${spec.like}”, so it can't have its own “run”, “show” or “ask”",
				)

			val unasked = spec.given.keys - base.questions.map { it.name }.toSet()

			if (unasked.isNotEmpty())
				return Reading.Wrong(
					"“${spec.like}” doesn't ask for ${unasked.joinToString { "“$it”" }}",
				)

			val named = namedIn(spec.conditions.within, taken)
			val given = spec.given.mapValues { (_, text) -> Phrase.read(text, named) }

			given.values
				.filterIsInstance<Reading.Wrong>()
				.firstOrNull()
				?.let { return it }

			val choose =
				when (val read = phrase(spec.then.choose, named)) {
					is Reading.Right -> read.value
					is Reading.Wrong -> return read
				}

			return Conditions.read(spec.conditions).then { conditions ->
				keyOf(spec).map { key ->
					// What it is like says what follows; where the cursor lands can be this one's own to say.
					(
						choose?.let { landing ->
							val run = base.deed as? Deed.Run
							val watched = run?.how as? How.Watched

							if (run == null || watched == null)
								base
							else
								base.copy(
									deed = run.copy(how = watched.copy(then = watched.then.copy(choose = landing))),
								)
						} ?: base
					).copy(
						id = spec.id,
						label = spec.label,
						conditions = conditions,
						given = given.mapValues { (_, read) -> (read as Reading.Right).value },
						confirm = spec.how.confirm ?: base.confirm,
						submenu = submenuOf(spec.submenu),
						key = key,
						default = spec.default,
					)
				}
			}
		}

		private fun howOf(spec: ActionSpec, named: Set<String>): Reading<How> {
			val follows = spec.then.reread || spec.then.choose != null || spec.then.bring != null

			return when {
				spec.how.terminal && spec.how.watched ->
					Reading.Wrong("It is both run in a terminal and watched. Use one or the other")
				follows && !spec.how.watched ->
					Reading.Wrong("“then” needs a watched command: add “how”: {“watched”: true}")
				spec.how.watched ->
					phrase(spec.then.choose, named).then { choose ->
						phrase(spec.then.bring?.from, named).then { from ->
							phrase(spec.then.bring?.to, named).map { to ->
								How.Watched(
									Then(
										spec.then.reread,
										choose,
										if (from != null && to != null)
											Bring(from, to)
										else
											null,
									),
								)
							}
						}
					}
				spec.how.terminal -> Reading.Right(How.Terminal)
				else -> Reading.Right(How.Detached)
			}
		}

		private fun showingOf(
			show: ShowSpec,
			named: Set<String>,
			places: Map<String, PlaceType>,
		): Reading<Deed> {
			val shown = show.folder
			val placed = show.place

			return window(show.within).then { window ->
				when {
					shown != null && placed != null ->
						Reading.Wrong("It shows both a folder and a place. Use one or the other")
					shown != null ->
						Phrase.read(shown, named).then { folder ->
							phrase(show.choose, named).map { Deed.Show(Showing.Folder(folder, it), window) }
						}
					placed != null -> {
						val place =
							places[placed]
								?: return@then Reading.Wrong("It shows “$placed”, which isn't a place")
						val missing = place.takes - show.with.keys
						val extra = show.with.keys - place.takes.toSet()

						when {
							missing.isNotEmpty() ->
								Reading.Wrong(
									"“${place.id}” needs ${missing.joinToString {
										"“$it”"
									}}, which isn't given",
								)
							extra.isNotEmpty() ->
								Reading.Wrong("“${place.id}” doesn't accept ${extra.joinToString { "“$it”" }}")
							else -> {
								val with = show.with.mapValues { (_, text) -> Phrase.read(text, named) }

								with.values.filterIsInstance<Reading.Wrong>().firstOrNull()
									?: Reading.Right(
										Deed.Show(
											Showing.Place(
												place.id,
												with.mapValues { (_, read) ->
													(read as Reading.Right).value
												},
											),
											window,
										),
									)
							}
						}
					}
					else -> Reading.Wrong("“show” needs a folder or a place")
				}
			}
		}

		private fun questionsOf(
			ask: Map<String, AskSpec>,
			named: Set<String>,
		): Reading<List<Question>> {
			val read =
				ask.map { (name, spec) ->
					val text = spec.text
					val folder = spec.folder

					// A question's start cannot be an answer: what was asked is not known until it is.
					phrase(spec.start, named - ask.keys).then { start ->
						when {
							!Regex("[a-z][A-Za-z0-9_]*").matches(name) ->
								Reading.Wrong(
									"“$name” isn't a valid name: use letters and digits, starting with a lowercase letter",
								)
							Each.entries.any { it.word == name } || Every.entries.any { it.word == name } ||
								name == "folder" ->
								Reading.Wrong(
									"“$name” is reserved. Choose another name",
								)
							text != null && folder != null ->
								Reading.Wrong("“$name” asks for both text and a folder. Use one or the other")
							text != null -> Reading.Right(Question.Text(name, text, start))
							folder != null -> Reading.Right(Question.Folder(name, folder, start))
							else ->
								Reading.Wrong(
									"“$name” needs a “text” or “folder” prompt",
								)
						}
					}
				}

			return read.filterIsInstance<Reading.Wrong>().firstOrNull()
				?: Reading.Right(read.map { (it as Reading.Right).value })
		}

		private fun keyOf(spec: ActionSpec): Reading<Chord?> = keyOf(spec.key)

		private fun keyOf(key: String?): Reading<Chord?> =
			when (key) {
				null -> Reading.Right(null)
				else ->
					Chord.parse(key)?.let { Reading.Right(it) }
						?: Reading.Wrong("“$key” isn't a valid shortcut")
			}

		private fun submenuOf(text: String?): List<String> =
			text
				?.split('/')
				?.map { it.trim() }
				?.filter { it.isNotEmpty() }
				.orEmpty()

		private fun phrase(text: String?, named: Set<String>): Reading<Phrase?> =
			text?.let { Phrase.read(it, named) } ?: Reading.Right(null)

		private fun window(text: String?): Reading<Boolean?> =
			when (text) {
				null -> Reading.Right(null)
				"here" -> Reading.Right(false)
				"window" -> Reading.Right(true)
				else -> Reading.Wrong("“$text” must be “here” or “window”")
			}

		private fun <T> word(
			text: String,
			of: List<T>,
			word: (T) -> String,
			what: String,
		): Reading<T> =
			of.firstOrNull { word(it) == text }?.let { Reading.Right(it) }
				?: Reading.Wrong("“$text” is not $what. Options: ${of.joinToString { word(it) }}")
	}
}
