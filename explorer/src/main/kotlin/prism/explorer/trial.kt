package prism.explorer

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import prism.browser.Actions
import prism.browser.Commands
import prism.browser.Deed
import prism.browser.FileHit
import prism.browser.Filling
import prism.browser.Hit
import prism.browser.HitRead
import prism.browser.Invocation
import prism.browser.Item
import prism.browser.Need
import prism.browser.On
import prism.browser.Placed
import prism.browser.Question
import prism.browser.Showing
import prism.browser.Subject
import prism.browser.TEMP
import prism.browser.Takes
import prism.browser.TextEnding
import prism.browser.TextRun
import prism.browser.TextScope
import prism.browser.TextSearch
import prism.browser.Thing
import prism.browser.ThingKind
import prism.browser.UserAction
import prism.browser.Watch
import prism.browser.Where
import prism.browser.World
import prism.browser.found
import prism.browser.pathOf
import prism.preferences.ActionSpec
import prism.preferences.Declared
import prism.preferences.TextSearchSpec

/*
 * An action or a text search tried out from the window that edits it, and what there is to see before it is: whether
 * what is typed can be saved ([checked]), what it would run for an example ([exampleOf]), and what running it for
 * real on files the user names came to ([Tried]), with everything it printed, so that a command that does not do what
 * was meant is seen not to, and why.
 *
 * A trial runs the command as it is typed, saved or not, on this machine, with what it is given typed beside it. It is
 * run and waited for, whatever the action says of how it is run, so that what it printed can be shown; and what is to
 * follow it (the listing read again, what it made brought out of its folder) is not done.
 */

/** What is typed to try something on: the files, a line each; the answers to what it asks; and a search's query. */
data class Trial(
	/** The files to try it on, a line each; or, for a search that takes lines, the lines. */
	val given: String = "",
	/** The answers to its questions, and the values of the place it is in, by their names. */
	val answers: Map<String, String> = emptyMap(),
	val query: String = "",
	/** The options of a text search that are on. */
	val on: Set<String> = emptySet(),
	/** How the last trial went; null before any. */
	val result: Tried? = null,
) {
	val lines: List<String> get() =
		given
			.lines()
			.map {
				it.trim()
			}.filter { it.isNotEmpty() }
}

/** How a trial went. */
sealed interface Tried {
	/** It is running. */
	data object Running : Tried

	/** It could not be tried: [why]. */
	data class Refused(
		val why: String,
	) : Tried

	/** Its commands were run, each with what it printed; [note] is what is to be known of the trial itself. */
	data class Ran(
		val runs: List<RanOnce>,
		val note: String? = null,
	) : Tried

	/** It would open [what], which a trial does not. */
	data class Opens(
		val what: String,
	) : Tried

	/**
	 * A search's command ([words]) was run: how it [ended], how many [lines] it found in how many [files], the [first]
	 * of them, and the lines it printed that could not be read as it says they are to be ([unread], the first of them).
	 */
	data class Found(
		val words: List<String>,
		val ended: TextEnding,
		val lines: Int,
		val files: Int,
		val first: List<FileHit>,
		val unread: List<String>,
	) : Tried
}

/** One command run in a trial: its words, how it ended (null where it could not be started), and what it printed. */
data class RanOnce(
	val words: List<String>,
	val status: Int?,
	val output: String,
	val errors: String,
)

/**
 * What stops something typed from being saved ([wrong]), which is only that it is not finished where it is
 * [unfinished] (no name yet, no command); the programs it needs that are not here ([missing]), with which it can be
 * saved and is offered once they are; and the program its command runs, where that is not found here ([unfound]) and
 * nothing says it has to be, so that it would be offered and then fail.
 */
data class Checked(
	val wrong: List<String> = emptyList(),
	val missing: String? = null,
	val unfound: String? = null,
	val unfinished: Boolean = false,
) {
	val savable: Boolean get() = wrong.isEmpty()
}

/**
 * Whether [spec], typed in a window, can be saved among what [acting] has: it has a name, and an id nothing else has
 * (where it is [fresh]: one being changed keeps its own), and reads.
 */
fun <S : Declared> ScriptKind<S>.checked(
	acting: Acting,
	spec: S,
	fresh: Boolean,
	installed: (String) -> Boolean,
): Checked {
	val named =
		listOfNotNull(
			when {
				spec.label.isBlank() -> "Give it a name."
				spec.id.isBlank() -> "Give it an id."
				else -> null
			},
		)

	if (named.isNotEmpty())
		return Checked(named, unfinished = true)

	val taken =
		when {
			!ID.matches(
				spec.id,
			) -> "The id can only have letters, digits, dots, hyphens and underscores."
			fresh && spec.id in catalog(acting, installed).ids ->
				"The id “${spec.id}” is already used. Choose another."
			else -> null
		}

	if (taken != null)
		return Checked(listOf(taken))

	// The program a lone command runs, where it is a name to be looked for and not a path or something filled in.
	val unfound =
		spec.run
			?.firstOrNull()
			?.takeIf { '/' !in it && '{' !in it && it.isNotBlank() && !installed(it) }

	return when (val trouble = catalog(put(acting, spec), installed)[spec.id]?.trouble) {
		is Trouble.Wrong ->
			Checked(listOf(trouble.why), unfinished = spec.run?.all { it.isBlank() } == true)
		is Trouble.Missing -> Checked(missing = trouble.needs)
		null -> Checked(unfound = unfound)
	}
}

private val ID = Regex("[A-Za-z0-9._-]+")

/** [acting]'s actions and searches read with [spec] among them, as this machine would have them. */
private fun <S : Declared> ScriptKind<S>.readWith(
	acting: Acting,
	spec: S,
	world: World,
): Actions =
	Actions.read(
		listOf(Actions.SHIPPED, put(acting, spec).file),
		installed = world::installed,
	)

// ---- an example ------------------------------------------------------------------------------------------------------

/** What something would do for an example of what it is for: the commands it would run, or what it would open. */
sealed interface Example {
	/** It cannot be shown: it does not read, or cannot be run here. */
	data object None : Example

	/** Each command it would run, as its words, for [files]; a value only known when it is done is `‹its name›`. */
	data class Runs(
		val files: List<String>,
		val runs: List<List<String>>,
	) : Example

	data class Opens(
		val what: String,
	) : Example
}

/** A name of a file [spec] would be offered for, as nearly as its conditions say one. */
private fun sampleName(spec: Declared, at: Int): String {
	val stem = listOf("example", "another").getOrElse(at) { "file$at" }
	val conditions = spec.conditions

	return when {
		conditions.ext?.isNotEmpty() == true -> "$stem.${conditions.ext!!.first()}"
		conditions.names?.isNotEmpty() == true ->
			conditions.names!!
				.first()
				.replace("*", stem)
				.replace("?", "x")
		conditions.kind == ThingKind.Folder.word -> stem.replaceFirstChar { it.uppercase() }
		else -> "$stem.txt"
	}
}

/** What stands for a value that is only known when the action is done: an answer, a folder made for it. */
private fun unknown(name: String): String = "‹$name›"

/**
 * What [spec] would do for an example of what it is for, as [acting] and this machine have it: a file or two of a name
 * its conditions say, in the home folder, or entries of the place it is for.
 */
fun exampleOf(acting: Acting, spec: ActionSpec, world: World): Example {
	val actions = ScriptKind.Action.readWith(acting, spec, world)
	val action = actions[spec.id] ?: return Example.None
	val conditions = spec.conditions
	val place = conditions.within.orEmpty().firstNotNullOfOrNull(actions.places::get)
	val names = List(if (conditions.count == "many") 2 else 1) { sampleName(spec, it) }

	val things =
		names.map { name ->
			Thing(
				if (place != null && Need.Real !in action.needs)
					Item.InPlace(name)
				else
					Item.OnDisk(world.home.resolve(name)),
				name,
				ThingKind.entries.firstOrNull { it.word == conditions.kind } ?: ThingKind.File,
			)
		}

	val where =
		place?.let { placed ->
			Where.Place(Placed(placed, placed.takes.associateWith(::unknown)))
		} ?: Where.Folder(world.home)

	val subject =
		Subject(
			action.conditions.on ?: On.Files,
			if (action.conditions.on == On.Files)
				things
			else
				listOf(Thing.folder(world.home)),
			where,
		)

	val answers =
		action.questions.associate { it.name to unknown(it.name) } + (TEMP to unknown("temp"))
	val filling = filled(action, subject, answers) ?: return Example.None

	return when (val deed = action.deed) {
		is Deed.Run ->
			deed.command.fill(filling)?.let { Example.Runs(names, it) }
				?: Example.None
		is Deed.Show -> Example.Opens(openedSaid(deed.what, filling))
	}
}

/** What [action]'s command is filled from for [subject]: the [answers], under what it was given beforehand. */
private fun filled(
	action: UserAction,
	subject: Subject,
	answers: Map<String, String>,
): Filling? {
	val before = subject.filling(answers)
	val given = action.given.mapValues { (_, phrase) -> phrase.fill(before) ?: return null }

	return subject.filling(answers + given)
}

private fun openedSaid(what: Showing, filling: Filling): String =
	when (what) {
		is Showing.Folder ->
			"Opens the folder ${what.path.fill(filling) ?: "?"}" +
				what.choose
					?.fill(filling)
					?.let { ", with $it selected" }
					.orEmpty()
		is Showing.Place ->
			"Opens the place ${what.place}" +
				what.with.entries
					.joinToString { (name, phrase) -> "$name = ${phrase.fill(filling) ?: "?"}" }
					.takeIf { it.isNotEmpty() }
					?.let { " with $it" }
					.orEmpty()
	}

/** What [spec]'s command would be for the query “example”, with none of its options on, as this machine has it. */
fun exampleOf(acting: Acting, spec: TextSearchSpec, world: World): Example {
	val search =
		ScriptKind.Search.readWith(acting, spec, world).search(spec.id) ?: return Example.None

	return search
		.words(unknown("query"), emptySet(), world.home)
		?.let { Example.Runs(emptyList(), listOf(it)) }
		?: Example.None
}

// ---- a trial ---------------------------------------------------------------------------------------------------------

/** The values [spec]'s command is given beside the files: what it asks, and what the place it is in is opened with. */
fun valuesAsked(acting: Acting, spec: ActionSpec, world: World): List<String> {
	val actions = ScriptKind.Action.readWith(acting, spec, world)
	val action = actions[spec.id] ?: return emptyList()
	val place =
		spec.conditions.within
			.orEmpty()
			.firstNotNullOfOrNull(actions.places::get)

	return place?.takes.orEmpty() +
		action.questions.filter { it.name !in action.given }.map { it.name }
}

/**
 * [spec] done, for real, to what [trial] names, and what that came to. Its commands are run one after another and
 * waited for, stopping at the first that fails; what it would open is only said.
 */
fun tried(
	acting: Acting,
	spec: ActionSpec,
	trial: Trial,
	world: World,
	commands: Commands,
): Tried {
	val actions = ScriptKind.Action.readWith(acting, spec, world)

	val action =
		actions[spec.id]
			?: return Tried.Refused(
				actions.broken.firstOrNull { it.id == spec.id }?.why
					?: "It can't be run on this computer: a program it needs isn't installed.",
			)

	val place =
		spec.conditions.within
			.orEmpty()
			.firstNotNullOfOrNull(actions.places::get)
	val entries = place != null && Need.Real !in action.needs

	val things =
		trial.lines.map { line ->
			if (entries) {
				Thing(Item.InPlace(line), line.trimEnd('/').substringAfterLast('/'), ThingKind.File)
			} else {
				val path =
					pathOf(line, world.home, world.home)
						?: return Tried.Refused("“$line” isn't a path.")

				if (!Files.exists(path))
					return Tried.Refused("$path doesn't exist.")
				if (Files.isDirectory(path))
					Thing.folder(path)
				else
					Thing.file(path)
			}
		}

	val on = action.conditions.on ?: On.Files
	val folder = things.firstNotNullOfOrNull { it.path?.parent } ?: world.home

	val subject =
		Subject(
			on,
			things,
			place?.let {
				Where.Place(
					Placed(
						it,
						it.takes.associateWith { name ->
							trial.answers[name].orEmpty()
						},
					),
				)
			}
				?: Where.Folder(
					if (on == On.Files)
						folder
					else
						things.firstNotNullOfOrNull { it.path } ?: world.home,
				),
		)

	if (!action.fits(subject))
		return Tried.Refused(
			if (things.isEmpty())
				"Name at least one file to try it on."
			else
				"It can't be run for these: its command needs something they don't have.",
		)

	val unanswered =
		action.questions.filter {
			it.name !in action.given &&
				trial.answers[it.name].isNullOrEmpty()
		}

	if (unanswered.isNotEmpty())
		return Tried.Refused("Fill in ${unanswered.joinToString { "“${it.name}”" }} first.")

	val asked =
		action.questions.associate { question ->
			val answer = trial.answers[question.name].orEmpty()

			question.name to
				when (question) {
					is Question.Text -> answer
					is Question.Folder -> pathOf(answer, folder, world.home)?.toString() ?: answer
				}
		}

	val scratch =
		if (action.scratch)
			try {
				Files.createTempDirectory("prism-trial")
			} catch (failed: IOException) {
				return Tried.Refused("A temporary folder couldn't be made: ${failed.message}")
			}
		else
			null

	try {
		val filling =
			filled(action, subject, asked + listOfNotNull(scratch?.let { TEMP to it.toString() }))
				?: return Tried.Refused("Some of its values couldn't be filled in from these files.")

		val note =
			"These files don't meet its conditions, so the menu wouldn't offer it for them. It was run anyway."
				.takeIf { !action.conditions.hold(subject, world) }

		return when (val deed = action.deed) {
			is Deed.Show -> Tried.Opens(openedSaid(deed.what, filling))
			is Deed.Run -> {
				val runs =
					deed.command.fill(filling)
						?: return Tried.Refused("Its command uses a value that isn't available here.")
				val within = filling.folder ?: world.home
				val environment = filling.values + ("folder" to within.toString())

				// One after another, as the action's own are, and no further than the first that fails.
				val ran =
					runs.fold(emptyList<RanOnce>()) { done, words ->
						if (done.lastOrNull()?.let { it.status != 0 } == true)
							done
						else
							done +
								commands.run(Invocation(words, within, environment)).let {
									RanOnce(words, it.status, it.output, it.errors)
								}
					}

				Tried.Ran(ran, note)
			}
		}
	} finally {
		scratch?.toFile()?.deleteRecursively()
	}
}

/**
 * [spec]'s command run, for real, on what [trial] names with what it has typed, and what it found: with the lines it
 * printed that could not be read as [spec] says they are to be, which is what is wrong where it found nothing.
 */
fun tried(
	acting: Acting,
	spec: TextSearchSpec,
	trial: Trial,
	world: World,
	commands: Commands,
): Tried {
	val actions = ScriptKind.Search.readWith(acting, spec, world)

	val search =
		actions.search(spec.id)
			?: return Tried.Refused(
				actions.broken.firstOrNull { it.id == spec.id }?.why
					?: "It can't be run on this computer: a program it needs isn't installed.",
			)

	val lines = search.takes == Takes.Lines

	val paths =
		if (lines)
			emptyList()
		else
			trial.lines.map {
				pathOf(it, world.home, world.home) ?: return Tried.Refused("“$it” isn't a path.")
			}

	paths
		.firstOrNull {
			!Files.exists(it)
		}?.let { return Tried.Refused("$it doesn't exist.") }
	if (trial.lines.isEmpty())
		return Tried.Refused(
			if (lines)
				"Type a few lines for it to look through."
			else
				"Name at least one file or folder to look in.",
		)

	val folder =
		paths.firstOrNull()?.let {
			if (Files.isDirectory(it))
				it
			else
				it.parent
		} ?: world.home

	val run =
		TextRun(
			search,
			TextScope("these", folder, paths),
			trial.query,
			trial.on,
			lines =
				trial.lines
					.takeIf { lines }
					?.mapIndexed { at, text -> FileHit(GIVEN, Hit(at + 1L, text)) },
		)

	val words =
		search.words(trial.query, trial.on, folder)
			?: return Tried.Refused("Its command uses a value that isn't available here.")

	val printed = ArrayList<String>()
	val hits = ArrayList<FileHit>()

	// What it printed is kept as it is read, a line each, to say which of them were not read as anything found.
	val listening =
		object : Commands {
			override fun run(invocation: Invocation, watch: Watch) =
				commands.run(invocation, watch)

			override fun stream(
				invocation: Invocation,
				input: ((java.io.OutputStream) -> Unit)?,
				watch: Watch,
				line: (String) -> Unit,
			) = commands.stream(invocation, input, watch) {
				if (printed.size < PRINTED)
					printed += it
				line(it)
			}
		}

	val ended = found(run, listening) { hits += it }
	val reader = run.reader

	return Tried.Found(
		words,
		ended,
		hits.count { it.hit != null }.takeIf { it > 0 } ?: hits.size,
		hits.mapTo(HashSet()) { it.path }.size,
		hits.take(SHOWN),
		printed
			.filter { line ->
				line.isNotBlank() &&
					when {
						search.read == HitRead.RgJson || search.read == HitRead.Json -> !isObject(line)
						lines -> reader.kept(line) == null
						// Paths ended by NUL characters come as one line of them all.
						search.read == HitRead.Paths && search.nul -> false
						else -> reader.read(line) == null
					}
			}.take(SHOWN),
	)
}

/** Whether [line] is a JSON object, as each line of a command that prints JSON is. */
private fun isObject(line: String): Boolean =
	try {
		Json.parseToJsonElement(line) is JsonObject
	} catch (notJson: SerializationException) {
		false
	}

/** The file the lines typed for a trial are said to be of, which are of none. */
private val GIVEN: Path = Path.of("lines given")

/** How many lines of what a trial printed are kept to look through, and how many of what it found are shown. */
private const val PRINTED = 20_000
private const val SHOWN = 50

/** The search [spec] reads as, with [acting], on this machine: for its window to show what it has to be tried with. */
fun searchRead(acting: Acting, spec: TextSearchSpec, world: World): TextSearch? =
	ScriptKind.Search.readWith(acting, spec, world).search(spec.id)

// ---- the window over the pages -----------------------------------------------------------------------------------------

/** The window open over the pages of actions and text searches. */
sealed interface ActingModal {
	/**
	 * Something being made or changed in its window: with what it is being tried on ([trial]), and whether the window
	 * that tries it is open over the one that edits it ([trying]).
	 */
	sealed interface Editing : ActingModal {
		val trial: Trial
		val trying: Boolean

		/** This, tried on [trial], the window that tries it [open] or not. */
		fun trying(trial: Trial, open: Boolean = true): Editing =
			when (this) {
				is EditingAction -> copy(trial = trial, trying = open)
				is EditingSearch -> copy(trial = trial, trying = open)
			}
	}

	/** An action being made or changed. */
	data class EditingAction(
		val form: ActionForm,
		override val trial: Trial = Trial(),
		override val trying: Boolean = false,
	) : Editing

	/** A text search being made or changed. */
	data class EditingSearch(
		val form: SearchForm,
		override val trial: Trial = Trial(),
		override val trying: Boolean = false,
	) : Editing

	/** What is called [id], of [kind], being moved or copied [to] other windows and extensions. */
	data class Moving(
		val kind: ScriptKind<*>,
		val id: String,
		val to: Retarget,
	) : ActingModal

	companion object {
		/** [spec] opened to be changed; or, where it is [fresh], to be made. */
		fun editing(spec: Declared, fresh: Boolean = false): ActingModal =
			when (spec) {
				is ActionSpec -> EditingAction(ActionForm.of(spec, fresh))
				is TextSearchSpec -> EditingSearch(SearchForm.of(spec, fresh))
			}
	}
}
