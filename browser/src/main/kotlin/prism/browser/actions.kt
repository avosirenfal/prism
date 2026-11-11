package prism.browser

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import java.nio.file.Path
import prism.preferences.ActionSpec
import prism.preferences.ActionsFile
import prism.preferences.AskSpec
import prism.preferences.PlaceSpec
import prism.preferences.Preferences
import prism.preferences.Spec
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
											"“${match.value}”: there is no “.$part” of a value; there are " +
												"${Part.entries.mapNotNull { it.word }.joinToString { ".$it" }}",
										),
								)
							Every.entries.any { it.word == name } ->
								return Reading.Wrong(
									"“${match.value}” stands for several words, so it must be a word by itself",
								)
							else ->
								return Reading.Wrong(
									"nothing fills “${match.value}”" +
										named
											.takeIf { it.isNotEmpty() }
											?.let { names ->
												": the values with names here are ${names.sorted().joinToString { "{$it}" }}"
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
		fun read(words: List<String>, named: Set<String>): Reading<Command> {
			if (words.isEmpty() || words.first().isBlank())
				return Reading.Wrong("a command needs a program to run")

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
						"it names one file at a time ({$one}) and every file at once ({$all}): one or the other",
					)
				}
				command.needs.size > 1 ->
					Reading.Wrong("it names files on disk and entries of a place: it cannot be for both")
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

	data class Wrong(
		val why: String,
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
	val on: On = On.Files,
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
		on == subject.on && holdBut(subject, world)

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
			{ programs == null || programs.all(world::installed) },
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
		fun read(spec: WhenSpec): Reading<Conditions> {
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
							?: Reading.Wrong("“$text” is not $what: there are ${of.joinToString { word(it) }}")
				}

			return one(spec.target, On.entries, On::word, "something a menu is of").then { on ->
				one(spec.kind, ThingKind.entries, ThingKind::word, "a kind of file").then { kind ->
					one(spec.count, Count.entries, Count::word, "a count").then { count ->
						(spec.not?.let(::read) ?: Reading.Right(null)).map { not ->
							Conditions(
								on = on ?: On.Files,
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

	data class Folder(
		val path: Path,
	) : Where {
		override val word: String get() = "folder"
	}

	data object Results : Where {
		override val word: String get() = "results"
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
		val values: Map<String, String> get() =
			placed.values +
				mapOf(
					"within" to within.joinToString("") { "$it/" },
					"depth" to within.size.toString(),
				)
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
		Filling(things, folder, (where as? Where.Place)?.values.orEmpty() + answers)
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
				is Deed.Run -> listOfNotNull((deed.how as? How.Watched)?.then?.choose)
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
		(deed as? Deed.Run)?.command?.words.orEmpty().any { word ->
			word is Word.One && word.phrase.holes.any { it is Hole.Value && it.name == TEMP }
		}

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

/** Every action and place there is: prism's own (the defaults) and the user's, which replace them by id. */
@Immutable
data class Actions(
	val actions: List<UserAction>,
	val places: Map<String, PlaceType>,
	val broken: List<Broken>,
	/** The ids of menu entries the user has hidden, prism's own among them. */
	val hidden: Set<String> = emptySet(),
) {
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
		fun of(preferences: Preferences): Actions =
			read(listOf(SHIPPED, preferences.written), preferences.menu.hidden.toSet())

		/** Every action and place in [files], a later file's replacing an earlier one's of the same id. */
		fun read(files: List<ActionsFile>, hidden: Set<String> = emptySet()): Actions {
			val broken = ArrayList<Broken>()

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

			fun <S, T> meant(
				specs: Map<String, S>,
				what: String,
				read: (S) -> Reading<T>,
			): Map<String, T> =
				specs
					.mapNotNull { (id, spec) ->
						when (val reading = read(spec)) {
							is Reading.Right -> id to reading.value
							is Reading.Wrong -> null.also { broken += Broken(what, id, reading.why) }
						}
					}.toMap()

			val places = meant(placeSpecs, "place", ::placeOf)
			val plain =
				meant(actionSpecs.filterValues { it.like == null }, "action") { actionOf(it, places) }

			val alike =
				meant(
					actionSpecs.filterValues {
						it.like != null
					},
					"action",
				) { likeOf(it, plain, places) }

			val all = actionSpecs.keys.mapNotNull { plain[it] ?: alike[it] }

			// A place's way out and way in are actions that are there, and that a drop can be the doing of.
			val sound =
				places.filterValues { place ->
					val wrong =
						listOfNotNull(
							place.out?.let { id ->
								val out = all.firstOrNull { it.id == id }

								when {
									out == null -> "its way out, “$id”, is not an action"
									out.destination == null ->
										"its way out, “$id”, must ask for exactly one folder, which a drop gives"
									(out.deed as? Deed.Run)?.how !is How.Watched ->
										"its way out, “$id”, must be a command that is watched"
									else -> null
								}
							},
							place.into?.let { id ->
								"its way in, “$id”, is not an action".takeIf { all.none { it.id == id } }
							},
						).firstOrNull()

					wrong?.let { broken += Broken("place", place.id, it) }
					wrong == null
				}

			return Actions(all, sound, broken, hidden)
		}

		private fun placeOf(spec: PlaceSpec): Reading<PlaceType> {
			val named = spec.takes.toSet()

			return Command.read(spec.list, named).then { list ->
				if (list.named)
					Reading.Wrong("a place is listed whatever is chosen: its command cannot name files")
				else
					phrase(spec.title, named).then { title ->
						phrase(spec.folder, named).then { folder ->
							word(
								spec.read ?: PlaceRead.Paths.word,
								PlaceRead.entries,
								PlaceRead::word,
								"a way to read",
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
			places: Map<String, PlaceType>,
		): Set<String> =
			within
				?.map { places[it]?.let { place -> place.takes.toSet() + IN_PLACE }.orEmpty() }
				?.reduceOrNull { all, its -> all intersect its }
				.orEmpty()

		private fun actionOf(
			spec: ActionSpec,
			places: Map<String, PlaceType>,
		): Reading<UserAction> {
			val named = namedIn(spec.conditions.within, places) + spec.ask.keys + TEMP

			val deed: Reading<Deed> =
				when {
					spec.run != null && spec.show != null ->
						Reading.Wrong(
							"it says both “run” and “show”: one or the other",
						)
					spec.run != null ->
						Command.read(spec.run!!, named).then { command ->
							howOf(spec, named).map { Deed.Run(command, it) }
						}
					spec.show != null ->
						if (spec.how.terminal || spec.how.watched ||
							spec.then != prism.preferences.ThenSpec()
						)
							Reading.Wrong("“how” and “then” are said of a command, and this shows somewhere")
						else
							showingOf(spec, named, places)
					else ->
						Reading.Wrong(
							"it says neither “run” nor “show”: there is nothing for it to do",
						)
				}

			return deed.then { did ->
				Conditions.read(spec.conditions).then { conditions ->
					questionsOf(spec.ask, named).then { questions ->
						if (spec.given.isNotEmpty())
							Reading.Wrong(
								"“given” answers the questions of an action this is “like”, and it is like none",
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
			places: Map<String, PlaceType>,
		): Reading<UserAction> {
			val base =
				plain[spec.like]
					?: return Reading.Wrong(
						"it is like “${spec.like}”, which is not an action (or is itself like another)",
					)

			if (spec.run != null || spec.show != null || spec.ask.isNotEmpty())
				return Reading.Wrong(
					"it is like “${spec.like}”, so what it runs, shows and asks is that action's to say",
				)

			val unasked = spec.given.keys - base.questions.map { it.name }.toSet()

			if (unasked.isNotEmpty())
				return Reading.Wrong(
					"“${spec.like}” does not ask for ${unasked.joinToString { "“$it”" }}",
				)

			val named = namedIn(spec.conditions.within, places)
			val given = spec.given.mapValues { (_, text) -> Phrase.read(text, named) }

			given.values
				.filterIsInstance<Reading.Wrong>()
				.firstOrNull()
				?.let { return it }

			return Conditions.read(spec.conditions).then { conditions ->
				keyOf(spec).map { key ->
					base.copy(
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
			val follows = spec.then.reread || spec.then.choose != null

			return when {
				spec.how.terminal && spec.how.watched ->
					Reading.Wrong("it is run in a terminal and watched: one or the other")
				follows && !spec.how.watched ->
					Reading.Wrong("“then” follows a command prism watches: say “how”: {“watched”: true}")
				spec.how.watched ->
					phrase(spec.then.choose, named).map { How.Watched(Then(spec.then.reread, it)) }
				spec.how.terminal -> Reading.Right(How.Terminal)
				else -> Reading.Right(How.Detached)
			}
		}

		private fun showingOf(
			spec: ActionSpec,
			named: Set<String>,
			places: Map<String, PlaceType>,
		): Reading<Deed> {
			val show = spec.show!!

			return window(show.within).then { window ->
				when {
					show.folder != null && show.place != null ->
						Reading.Wrong("it shows a folder and a place: one or the other")
					show.folder != null ->
						Phrase.read(show.folder!!, named).then { folder ->
							phrase(show.choose, named).map { Deed.Show(Showing.Folder(folder, it), window) }
						}
					show.place != null -> {
						val place =
							places[show.place]
								?: return@then Reading.Wrong("it shows “${show.place}”, which is not a place")
						val missing = place.takes - show.with.keys
						val extra = show.with.keys - place.takes.toSet()

						when {
							missing.isNotEmpty() ->
								Reading.Wrong(
									"“${place.id}” is opened with ${missing.joinToString {
										"“$it”"
									}}, which it does not give",
								)
							extra.isNotEmpty() ->
								Reading.Wrong("“${place.id}” does not take ${extra.joinToString { "“$it”" }}")
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
					else -> Reading.Wrong("it shows neither a folder nor a place")
				}
			}
		}

		private fun questionsOf(
			ask: Map<String, AskSpec>,
			named: Set<String>,
		): Reading<List<Question>> {
			val read =
				ask.map { (name, spec) ->
					// A question's start cannot be an answer: what was asked is not known until it is.
					phrase(spec.start, named - ask.keys).then { start ->
						when {
							!Regex("[a-z][A-Za-z0-9_]*").matches(name) ->
								Reading.Wrong(
									"“$name” cannot name a value: a name is letters and digits, a small letter first",
								)
							Each.entries.any { it.word == name } || Every.entries.any { it.word == name } ||
								name == "folder" ->
								Reading.Wrong(
									"“$name” is a placeholder of prism's own: a question needs another name",
								)
							spec.text != null && spec.folder != null ->
								Reading.Wrong("“$name” asks for text and for a folder: one or the other")
							spec.text != null -> Reading.Right(Question.Text(name, spec.text!!, start))
							spec.folder != null -> Reading.Right(Question.Folder(name, spec.folder!!, start))
							else ->
								Reading.Wrong(
									"“$name” asks for nothing: say “text” or “folder”, with what to ask",
								)
						}
					}
				}

			return read.filterIsInstance<Reading.Wrong>().firstOrNull()
				?: Reading.Right(read.map { (it as Reading.Right).value })
		}

		private fun keyOf(spec: ActionSpec): Reading<Chord?> =
			when (val key = spec.key) {
				null -> Reading.Right(null)
				else ->
					Chord.parse(key)?.let { Reading.Right(it) }
						?: Reading.Wrong("“$key” is not a chord")
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
				else -> Reading.Wrong("“$text” is not where something opens: “here” or “window”")
			}

		private fun <T> word(
			text: String,
			of: List<T>,
			word: (T) -> String,
			what: String,
		): Reading<T> =
			of.firstOrNull { word(it) == text }?.let { Reading.Right(it) }
				?: Reading.Wrong("“$text” is not $what: there are ${of.joinToString { word(it) }}")
	}
}
