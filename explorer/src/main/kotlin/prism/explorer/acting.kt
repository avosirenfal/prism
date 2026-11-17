package prism.explorer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import prism.browser.Actions
import prism.browser.Conditions
import prism.browser.Reading
import prism.preferences.ActionSpec
import prism.preferences.ActionsFile
import prism.preferences.Declared
import prism.preferences.OpenSpec
import prism.preferences.PlaceSpec
import prism.preferences.Preferences
import prism.preferences.Spec
import prism.preferences.TextSearchSpec
import prism.preferences.WaySpec
import prism.preferences.declared

/*
 * The actions and the text searches as the preferences' two pages of them list and change them, as values.
 *
 * What there is to list is a catalogue ([Catalog]): every one prism comes with and every one the user wrote, each
 * with where it comes from ([Source]: prism's own, prism's own as the user changed it, or the user's), whether it is
 * turned on, and what is the matter with it where anything is ([Trouble]: a program it needs is not installed, or it
 * is written wrong). Nothing is ever taken out of the catalogue for being off, unavailable or wrong: the pages are
 * where those are seen and put right.
 *
 * What the user changes is only ever what the preferences write ([Acting]): an action of the user's is written, one
 * of prism's that they change is written over it under the same id (which is how the preferences have always
 * replaced one), and putting one of prism's back is no more than taking what was written over it away.
 */

/** The editor as it is typed: its command, as a line, and whether it runs in a terminal. None, where nothing is typed. */
data class EditorForm(
	val command: String = "",
	val terminal: Boolean = false,
) {
	fun spec(): OpenSpec? =
		wordsOf(command)
			.takeIf {
				it.isNotEmpty()
			}?.let { OpenSpec(it, terminal) }

	companion object {
		fun of(spec: OpenSpec?): EditorForm =
			spec?.let { EditorForm(textOf(it.run), it.terminal) } ?: EditorForm()
	}
}

/**
 * What of the preferences the two pages change: the user's actions and text searches as they are written, the ids
 * turned off, and the editor. The user's [places] are here to read the rest with, and are not changed.
 */
data class Acting(
	val actions: List<JsonElement> = emptyList(),
	val searches: List<JsonElement> = emptyList(),
	val disabled: Set<String> = emptySet(),
	val editor: EditorForm = EditorForm(),
	val places: List<JsonElement> = emptyList(),
) {
	/** These as a file of actions, to be read after prism's own. */
	val file: ActionsFile get() = ActionsFile(actions, places, searches, editor.spec())

	/** These, with what is called [id] turned on, or off. */
	fun enabling(id: String, on: Boolean): Acting =
		copy(
			disabled =
				if (on)
					disabled - id
				else
					disabled + id,
		)

	companion object {
		fun of(preferences: Preferences): Acting =
			Acting(
				preferences.actions,
				preferences.textSearches,
				preferences.disabled,
				EditorForm.of(preferences.editor),
				preferences.places,
			)
	}
}

/**
 * Which of the two a page lists: the actions, or the text searches. Each knows how its own are written and read, so
 * that everything else here is said once, for both.
 */
sealed class ScriptKind<S : Declared>(
	/** What one is called where it is said not to read, as the browser says it. */
	val what: String,
	val serializer: KSerializer<S>,
) {
	/** Those [file] writes, each read on its own. */
	abstract fun read(file: ActionsFile): List<Spec<S>>

	/** The user's, as [acting] has them written. */
	abstract fun written(acting: Acting): List<JsonElement>

	/** [acting], with the user's [written] so. */
	abstract fun writing(acting: Acting, written: List<JsonElement>): Acting

	/** Those prism comes with, by their ids, in the order they are written. */
	val shipped: Map<String, S> by lazy { rightly(read(Actions.SHIPPED)) }

	data object Action : ScriptKind<ActionSpec>("action", ActionSpec.serializer()) {
		override fun read(file: ActionsFile): List<Spec<ActionSpec>> = file.actionSpecs()

		override fun written(acting: Acting): List<JsonElement> = acting.actions

		override fun writing(acting: Acting, written: List<JsonElement>): Acting =
			acting.copy(actions = written)
	}

	data object Search : ScriptKind<TextSearchSpec>(
		"text search",
		TextSearchSpec.serializer(),
	) {
		override fun read(file: ActionsFile): List<Spec<TextSearchSpec>> =
			file.textSearchSpecs()

		override fun written(acting: Acting): List<JsonElement> = acting.searches

		override fun writing(acting: Acting, written: List<JsonElement>): Acting =
			acting.copy(searches = written)
	}

	/**
	 * [acting] with [spec] written: in place of the user's of the same id, or after the rest of theirs. Written just as
	 * prism's own of that id is, it is not written at all, so that one changed back is prism's own again and follows
	 * it from then on.
	 */
	fun put(acting: Acting, spec: S): Acting {
		val ids = read(acting.file).map(::idOf)
		val written = written(acting)
		val at = ids.lastIndexOf(spec.id)

		return writing(
			acting,
			when {
				shipped[spec.id] == spec ->
					written.filterIndexed {
						index,
						_,
						->
						ids[index] != spec.id
					}
				at < 0 -> written + JSON.encodeToJsonElement(serializer, spec)
				else ->
					written.mapIndexed { index, element ->
						if (index == at)
							JSON.encodeToJsonElement(serializer, spec)
						else
							element
					}
			},
		)
	}

	/**
	 * [acting] without what the user wrote as [id]: one of their own gone, with whether it was off; one of prism's as
	 * prism has it again, on or off as it was.
	 */
	fun without(acting: Acting, id: String): Acting {
		val ids = read(acting.file).map(::idOf)

		return writing(
			acting,
			written(acting).filterIndexed { index, _ -> ids[index] != id },
		).let {
			if (id in shipped)
				it
			else
				it.enabling(id, true)
		}
	}

	/** [acting] without the [at]th the user wrote: for one that did not read, which has no id to be told by. */
	fun withoutAt(acting: Acting, at: Int): Acting =
		writing(acting, written(acting).filterIndexed { index, _ -> index != at })

	/** Everything there is of this kind, as [acting] has it, on a machine where [installed] says what is. */
	fun catalog(acting: Acting, installed: (String) -> Boolean): Catalog<S> {
		val own = read(acting.file)
		val mine = rightly(own)
		val read = Actions.read(listOf(Actions.SHIPPED, acting.file), installed = installed)
		val wrong = read.broken.filter { it.what == what }.associate { it.id to it.why }
		val everything = Written.of(acting)

		return Catalog(
			this,
			(shipped.keys + mine.keys).map { id ->
				val theirs = shipped[id]
				val ours = mine[id]

				val source =
					when {
						ours == null -> Source.Shipped(theirs!!)
						theirs == null -> Source.Own(ours)
						else -> Source.Changed(theirs, ours)
					}

				Entry(
					source,
					id !in acting.disabled,
					wrong[id]?.let(Trouble::Wrong)
						?: everything.lacking(source.spec, installed)?.let(Trouble::Missing),
				)
			},
			own.mapIndexedNotNull { at, spec ->
				(spec as? Spec.Unread)?.let { Unread(at, it.id, it.why) }
			},
			Scenes(
				everything.places.keys.toList(),
				read.searches.associate { it.id to it.label },
			),
		)
	}

	/**
	 * [acting] with what is called [id] offered where, and for what, [to] says: itself, moved there; or, where it is
	 * to be a [copy], a copy of it, under an id of its own, the one copied left as it is.
	 */
	fun moved(acting: Acting, id: String, to: Retarget, copy: Boolean): Acting {
		val catalog = catalog(acting) { true }
		val spec = catalog[id]?.spec ?: return acting

		return put(
			acting,
			to.applied(
				if (copy)
					copyOf(spec, catalog.ids)
				else
					spec,
			),
		)
	}

	private fun rightly(specs: List<Spec<S>>): Map<String, S> =
		specs.filterIsInstance<Spec.Read<S>>().associate { it.value.id to it.value }

	private fun idOf(spec: Spec<S>): String? =
		when (spec) {
			is Spec.Read -> spec.value.id
			is Spec.Unread -> spec.id
		}

	companion object {
		/** As the preferences are written: what is as it would be unsaid is not said. */
		private val JSON = Json { encodeDefaults = false }
	}
}

/** Where something in the catalogue comes from; [spec] is what it now is. */
sealed interface Source<out S> {
	val spec: S

	/** Prism's own, as it comes. */
	data class Shipped<S>(
		override val spec: S,
	) : Source<S>

	/** Prism's own ([shipped]), as the user changed it. */
	data class Changed<S>(
		val shipped: S,
		override val spec: S,
	) : Source<S>

	/** The user's. */
	data class Own<S>(
		override val spec: S,
	) : Source<S>
}

/** What is the matter with something in the catalogue, that keeps it out of the menus whether or not it is on. */
sealed interface Trouble {
	/** Written rightly, for a machine with programs this one has not: [needs] says which, as "unzip, or 7z". */
	data class Missing(
		val needs: String,
	) : Trouble

	/** Written wrong: [why]. */
	data class Wrong(
		val why: String,
	) : Trouble
}

/** What can be said of an entry that the pages show only those of: each a chip that narrows the list to it. */
enum class Mark(
	val label: String,
) {
	Own("yours"),
	Changed("changed"),
	Off("off"),
	Unavailable("unavailable"),
	Wrong("with errors"),
}

/** One action or text search in the catalogue: where it comes from, whether it is on, and what is the matter with it. */
data class Entry<out S : Declared>(
	val source: Source<S>,
	val enabled: Boolean,
	val trouble: Trouble?,
) {
	val spec: S get() = source.spec
	val id: String get() = spec.id

	/** Where it is offered: each window it names, or anywhere. */
	val scenes: List<Scene> get() =
		spec.conditions.within
			?.takeIf { it.isNotEmpty() }
			?.map(Scene::In)
			?: listOf(Scene.Anywhere)

	val marks: Set<Mark> get() =
		setOfNotNull(
			Mark.Own.takeIf { source is Source.Own },
			Mark.Changed.takeIf { source is Source.Changed },
			Mark.Off.takeIf { !enabled },
			Mark.Unavailable.takeIf { trouble is Trouble.Missing },
			Mark.Wrong.takeIf { trouble is Trouble.Wrong },
		)

	/** Whether a menu could offer it: on, and nothing the matter with it. */
	val offered: Boolean get() = enabled && trouble == null
}

/** One the user wrote that did not read at all, the [at]th of theirs: it has no more to it than why not. */
data class Unread(
	val at: Int,
	val id: String?,
	val why: String,
)

/** Where a window is, as what is offered there names it: anywhere, or one kind of window. */
sealed interface Scene {
	data object Anywhere : Scene

	/** [word] is how conditions write it: `folder`, `results`, a place's id, a text search's. */
	data class In(
		val word: String,
	) : Scene
}

/** The windows there are for something to be offered in, beside a folder and a search's results, and what each is called. */
data class Scenes(
	/** The places declared, by their ids. */
	val places: List<String> = emptyList(),
	/** The text searches, each id with its name: an action can be for the lines one found. */
	val searches: Map<String, String> = emptyMap(),
) {
	/** Every window that can be named, the two every prism has first. */
	val words: List<String> get() = listOf(FOLDER, RESULTS) + places + searches.keys

	/** What [scene] is called, after "in": "folders", "the place archive.zip". */
	fun said(scene: Scene): String =
		when (scene) {
			Scene.Anywhere -> "any window"
			is Scene.In ->
				when (val word = scene.word) {
					FOLDER -> "folders"
					RESULTS -> "search results"
					"trash" -> "the trash"
					in searches -> "lines found by “${searches[word]}”"
					else -> "the place $word"
				}
		}

	/** What [scene] is called as a heading, or a choice. */
	fun titled(scene: Scene): String =
		when (scene) {
			Scene.Anywhere -> "Anywhere"
			is Scene.In ->
				when (val word = scene.word) {
					FOLDER -> "Folders"
					RESULTS -> "Search results"
					"trash" -> "Trash"
					in searches -> "Lines found by “${searches[word]}”"
					else -> "Place: $word"
				}
		}

	companion object {
		const val FOLDER = "folder"
		const val RESULTS = "results"
	}
}

/** Everything of one kind there is, in the order the menus have them, and what of the user's did not read. */
data class Catalog<S : Declared>(
	val kind: ScriptKind<S>,
	val entries: List<Entry<S>>,
	val unread: List<Unread>,
	val scenes: Scenes,
) {
	operator fun get(id: String): Entry<S>? = entries.firstOrNull { it.id == id }

	val ids: Set<String> get() = entries.mapTo(LinkedHashSet()) { it.id }

	/** How many entries each mark is true of, those true of none left out. */
	val marked: Map<Mark, Int> get() =
		Mark.entries
			.associateWith { mark -> entries.count { mark in it.marks } }
			.filterValues { it > 0 }

	/** Each window something here is offered in, in the order [groups] lists them. */
	val offeredIn: List<Scene> get() = ordered(entries.flatMap { it.scenes }.distinct())

	/**
	 * The entries [narrowing] shows, under each window they are offered in: anywhere first, then folders, results, and
	 * the rest in the order they are first named. One offered in two windows is under both.
	 */
	fun groups(narrowing: Narrowing): List<Group<S>> {
		val shown = entries.filter(narrowing::shows)

		return ordered(shown.flatMap { it.scenes }.distinct())
			.filter { narrowing.scene == null || it == narrowing.scene }
			.map { scene -> Group(scene, shown.filter { scene in it.scenes }) }
	}

	private fun ordered(scenes: List<Scene>): List<Scene> {
		val first = listOf(Scene.Anywhere, Scene.In(Scenes.FOLDER), Scene.In(Scenes.RESULTS))

		return first.filter { it in scenes } + scenes.filter { it !in first }
	}
}

/** The entries shown that are offered in [scene]. */
data class Group<out S : Declared>(
	val scene: Scene,
	val entries: List<Entry<S>>,
)

/**
 * Which of a page's entries are shown: those with every word of [words] in them (in a name, a submenu, an id, an
 * extension, a program), offered in [scene] (anywhere they are, where null), for a file called [named], and with every
 * one of [marks]. [folded] are the windows whose entries are shown as their heading alone.
 */
data class Narrowing(
	val words: String = "",
	val scene: Scene? = null,
	val named: String = "",
	val marks: Set<Mark> = emptySet(),
	val folded: Set<Scene> = emptySet(),
) {
	/** Whether anything narrows what is shown. */
	val narrowed: Boolean get() =
		words.isNotBlank() || scene != null || named.isNotBlank() || marks.isNotEmpty()

	/** These, narrowed by nothing; what is folded kept. */
	fun cleared(): Narrowing = Narrowing(folded = folded)

	fun shows(entry: Entry<Declared>): Boolean =
		(scene == null || scene in entry.scenes) &&
			entry.marks.containsAll(marks) &&
			terms.all { term ->
				haystack(entry.spec).any { it.contains(term, ignoreCase = true) }
			} &&
			file.let { it == null || offeredFor(entry.spec.conditions, it) }

	private val terms: List<String> get() =
		words.trim().split(Regex("\\s+")).filter {
			it.isNotEmpty()
		}

	/** The file's name that [named] means: `zip` and `.zip` are a file of that extension, anything else a name. */
	private val file: String? get() =
		named.trim().takeIf { it.isNotEmpty() }?.let {
			if (it.startsWith(".") || '.' !in it)
				"file.${it.removePrefix(".")}"
			else
				it
		}

	private fun haystack(spec: Declared): List<String> =
		listOfNotNull(spec.label, spec.id, spec.submenu, spec.key) +
			spec.conditions.ext
				.orEmpty()
				.map { ".$it" } +
			spec.conditions.names.orEmpty() +
			spec.conditions.types.orEmpty() +
			programsOf(spec).flatten()

	companion object {
		/**
		 * Whether [conditions] would offer something for a file called [name], as far as its name decides: what is for
		 * folders only is not for a file; what names no extension and no name is for every file.
		 */
		fun offeredFor(conditions: prism.preferences.WhenSpec, name: String): Boolean =
			when (val read = Conditions.read(conditions)) {
				is Reading.Wrong -> false
				is Reading.Right ->
					read.value.kind != prism.browser.ThingKind.Folder &&
						read.value.names
							?.any { it.matches(name) } != false &&
						read.value.not
							?.names
							?.takeIf {
								// A negation that says more than names is not decided by a name alone.
								conditions.not ==
									prism.preferences.WhenSpec(
										ext = conditions.not?.ext,
										names = conditions.not?.names,
									)
							}?.any { it.matches(name) } != true
			}
	}
}

/** The programs each way of running [spec] needs, a way each: those its ways say, and those its commands choose among. */
internal fun programsOf(spec: Declared): List<List<String>> =
	spec.run?.let { listOf(chosenIn(it)) }
		?: spec.ways.map { (it.programs + chosenIn(it.run ?: it.list.orEmpty())).distinct() }

/** The programs [words] choose among, each choice as a way's programs write one: `{which:a|b}` is `a|b`. */
private fun chosenIn(words: List<String>): List<String> =
	words.mapNotNull { WHICH.matchEntire(it)?.groupValues?.get(1) }

private val WHICH = Regex("""\{which:([^{}]+)\}""")

/** Everything written, prism's and the user's together: what one thing is read against where it names another. */
private class Written(
	val actions: Map<String, ActionSpec>,
	val places: Map<String, PlaceSpec>,
) {
	/**
	 * The programs [spec] cannot be run for want of, as words: "unzip, or 7z" where either would do; null where it can
	 * be run. An action like another wants what that one does, and one that opens a place what the place's listing
	 * does; and whatever it is, the programs its conditions say must be installed.
	 */
	fun lacking(spec: Declared, installed: (String) -> Boolean): String? {
		fun lacks(programs: List<String>): List<String> =
			programs.filter { any -> any.split('|').none { installed(it.trim()) } }

		fun lacking(ways: List<List<String>>): String? =
			ways
				.map(::lacks)
				.takeIf { each -> each.isNotEmpty() && each.none { it.isEmpty() } }
				?.distinct()
				?.joinToString(
					", or ",
				) { way -> way.joinToString(" and ") { it.replace("|", " or ") } }

		val action = spec as? ActionSpec
		val place = action?.show?.place?.let(places::get)

		return lacking(programsOf(spec))
			?: lacks(spec.conditions.programs.orEmpty())
				.takeIf { it.isNotEmpty() }
				?.joinToString(" and ") { it.replace("|", " or ") }
			?: action?.like?.let(actions::get)?.let { lacking(it, installed) }
			?: place?.let { listed ->
				lacking(
					listed.list?.let { listOf(chosenIn(it)) }
						?: listed.ways.map { (it.programs + chosenIn(it.list.orEmpty())).distinct() },
				)
			}
	}

	companion object {
		fun of(acting: Acting): Written {
			val files = listOf(Actions.SHIPPED, acting.file)

			fun <T> read(
				specs: (ActionsFile) -> List<Spec<T>>,
				id: (T) -> String,
			): Map<String, T> =
				files
					.flatMap(specs)
					.filterIsInstance<Spec.Read<T>>()
					.associate { id(it.value) to it.value }

			return Written(
				read(ActionsFile::actionSpecs) { it.id },
				read(ActionsFile::placeSpecs) { it.id },
			)
		}
	}
}

/** An id like [wanted] that none of [taken] is: itself, or with the first number after it that makes it so. */
fun fresh(wanted: String, taken: Set<String>): String =
	generateSequence(1) { it + 1 }
		.map {
			if (it == 1)
				wanted
			else
				"$wanted-$it"
		}.first { it !in taken }

/** An id made from a name: its letters and digits in lower case, a hyphen for each run of anything else. */
fun idFrom(label: String): String =
	label
		.lowercase()
		.replace(Regex("[^a-z0-9]+"), "-")
		.trim('-')

/** A copy of [spec] to be changed into something else: under an id of its own, and called a copy. */
fun <S : Declared> copyOf(spec: S, taken: Set<String>): S =
	spec.declared(id = fresh("${spec.id}-copy", taken), label = "${spec.label} (copy)")

/**
 * Where something is moved or copied to, as its window is filled in: the windows it is to be offered in ([within],
 * anywhere where none), and the extensions it is to be for, as typed ([ext], any file where blank).
 */
data class Retarget(
	val within: List<String> = emptyList(),
	val ext: String = "",
) {
	/** [spec], offered where this says and for what it says; the rest of its conditions as they were. */
	fun <S : Declared> applied(spec: S): S =
		spec.declared(
			conditions =
				spec.conditions.copy(
					within = within.takeIf { it.isNotEmpty() },
					ext = listed(ext),
				),
		)

	companion object {
		fun of(spec: Declared): Retarget =
			Retarget(spec.conditions.within.orEmpty(), typedOf(spec.conditions.ext))
	}
}

/** What is typed as a list, apart by commas or lines: its items, or null where it has none. */
fun listed(text: String): List<String>? =
	text
		.split(',', '\n')
		.map { it.trim() }
		.filter { it.isNotEmpty() }
		.takeIf { it.isNotEmpty() }

/** [items] as they are typed: apart by commas. */
fun typedOf(items: List<String>?): String = items.orEmpty().joinToString(", ")

/** A way's programs, as [WaySpec] writes them, from what is typed, and back. */
internal fun WaySpec.programsTyped(): String = typedOf(programs)
