package prism.explorer

import prism.browser.Count
import prism.browser.HitRead
import prism.browser.On
import prism.browser.Positions
import prism.browser.QueryUse
import prism.browser.Takes
import prism.browser.ThingKind
import prism.preferences.ActionSpec
import prism.preferences.AskSpec
import prism.preferences.BringSpec
import prism.preferences.Declared
import prism.preferences.HowSpec
import prism.preferences.OpenSpec
import prism.preferences.OptionSpec
import prism.preferences.ShowSpec
import prism.preferences.TextSearchSpec
import prism.preferences.ThenSpec
import prism.preferences.WaySpec
import prism.preferences.WhenSpec

/*
 * An action and a text search as their windows edit them: every part as it is typed, so that what is half typed (a
 * list with a comma at its end, a shortcut not yet a chord) stays as it was typed and is only said to be wrong; and
 * what each comes to, as the preferences write it ([ActionForm.spec], [SearchForm.spec]).
 *
 * A form made from something written, and written again unchanged, is what was written: what a form does not show
 * (the values a place must have, what a negation says beyond extensions) is kept as it was.
 *
 * A form holds what was typed for each thing an action can do, whichever it now does, as the Terminal page keeps a
 * custom command while another terminal is chosen: turning from a command to a folder and back loses nothing. Only
 * what belongs to what it now does is written.
 */

/**
 * A command as it is edited: as one line, typed as a shell would be given it (though no shell runs it); or as its
 * words, each in a field of its own, which is how a word with quotes and lines in it (a script) is read without
 * a layer of quoting over it. Either way it is the same words.
 */
sealed interface CommandForm {
	val words: List<String>

	data class Line(
		val text: String = "",
	) : CommandForm {
		override val words: List<String> get() = wordsOf(text)
	}

	data class Words(
		override val words: List<String>,
	) : CommandForm

	/** This, with [word] after the rest of it: a placeholder chosen from a list, not typed. */
	fun with(word: String): CommandForm =
		when (this) {
			is Line ->
				Line(
					listOf(text.trimEnd(), word).filter { it.isNotEmpty() }.joinToString(" "),
				)
			is Words -> Words(words.dropLastWhile { it.isEmpty() } + word)
		}

	/** This as one line. */
	fun line(): Line = this as? Line ?: Line(textOf(words))

	/** This as its words, one at least. */
	fun apart(): Words = this as? Words ?: Words(words.ifEmpty { listOf("") })

	companion object {
		/** [words] as they are best edited: apart where any is long, or has in it what a line would have to quote. */
		fun of(words: List<String>): CommandForm =
			if (words.any { word -> word.length > 60 || word.any { it in "\"'\\\n" } })
				Words(words)
			else
				Line(textOf(words))
	}
}

/**
 * One way of running something: the [programs] it needs, as typed (apart by commas; `a|b` is either), and its
 * command. For a text search's way, how what it prints is read where that is not as the search says ([read], [nul]).
 */
data class WayForm(
	val programs: String = "",
	val command: CommandForm = CommandForm.Line(),
	val read: HitRead? = null,
	val nul: Boolean? = null,
) {
	fun spec(): WaySpec =
		WaySpec(listed(programs).orEmpty(), run = command.words, read = read?.word, nul = nul)

	companion object {
		fun of(spec: WaySpec): WayForm =
			WayForm(
				typedOf(spec.programs),
				CommandForm.of(spec.run.orEmpty()),
				HitRead.entries.firstOrNull { it.word == spec.read },
				spec.nul,
			)

		/** The ways [spec] is run, one at least: its one command, or its several. */
		fun of(spec: Declared?): List<WayForm> =
			(
				spec?.run?.let { listOf(WayForm(command = CommandForm.of(it))) }
					?: spec?.ways.orEmpty().map(::of)
			).ifEmpty { listOf(WayForm()) }

		/** [ways] as one command, where they are one that needs nothing said of it; null where they are several. */
		fun single(ways: List<WayForm>): List<String>? =
			ways
				.singleOrNull()
				?.takeIf { it.programs.isBlank() && it.read == null && it.nul == null }
				?.command
				?.words
	}
}

/**
 * When something is offered, as its window edits it. Lists are as typed, apart by commas. [kept] is what was written,
 * for what of it the window does not show: the values a place must have, and what its negation says beyond extensions.
 */
data class WhenForm(
	/** What the menu is of; whatever it is of, where null, which only a text search can say. */
	val target: On? = On.Files,
	/** The windows it is offered in, as conditions write them; anywhere, where none. */
	val within: List<String> = emptyList(),
	val count: Count? = null,
	val kind: ThingKind? = null,
	val ext: String = "",
	val names: String = "",
	val types: String = "",
	/** The extensions it is not for, whatever else it is for: `tar.gz`, of what is for `gz`. */
	val notExt: String = "",
	val under: String = "",
	val inside: String = "",
	val programs: String = "",
	val elsewhere: Boolean? = null,
	val real: Boolean? = null,
	val kept: WhenSpec = WhenSpec(),
) {
	/** Whether anything is said that the window keeps folded away until it is. */
	val more: Boolean get() =
		under.isNotBlank() || inside.isNotBlank() || programs.isNotBlank() || elsewhere != null ||
			real != null || notExt.isNotBlank()

	/** These as they are written, where a menu is of [unsaid] unless they say. */
	fun spec(unsaid: On?): WhenSpec {
		val not =
			(kept.not ?: WhenSpec())
				.copy(ext = listed(notExt))
				.takeIf { it != WhenSpec() }

		return kept.copy(
			// Said only where it has to be, or was: `files` is what an action is for unless it says.
			target = target?.word.takeIf { target != unsaid || kept.target == target?.word },
			within = within.takeIf { it.isNotEmpty() },
			count = count?.word,
			kind = kind?.word,
			ext = listed(ext),
			names = listed(names),
			types = listed(types),
			under = listed(under),
			inside = listed(inside),
			programs = listed(programs),
			elsewhere = elsewhere,
			real = real,
			not = not,
		)
	}

	companion object {
		fun of(spec: WhenSpec, unsaid: On?): WhenForm =
			WhenForm(
				target = On.entries.firstOrNull { it.word == spec.target } ?: unsaid,
				within = spec.within.orEmpty(),
				count = Count.entries.firstOrNull { it.word == spec.count },
				kind = ThingKind.entries.firstOrNull { it.word == spec.kind },
				ext = typedOf(spec.ext),
				names = typedOf(spec.names),
				types = typedOf(spec.types),
				notExt = typedOf(spec.not?.ext),
				under = typedOf(spec.under),
				inside = typedOf(spec.inside),
				programs = typedOf(spec.programs),
				elsewhere = spec.elsewhere,
				real = spec.real,
				kept = spec,
			)
	}
}

/** What an action does. */
enum class Does(
	val label: String,
) {
	Run("Run a command"),
	Folder("Open a folder"),
	Place("Open a place"),
	Like("Like another"),
}

/** How an action's command is run. */
enum class Running(
	val label: String,
	val help: String,
) {
	Detached(
		"In the background",
		"Started and left alone. Nothing is shown, even if it fails. For opening other applications.",
	),
	Terminal(
		"In a terminal",
		"Opens your terminal and runs it there, so you can see what it prints and type into it.",
	),
	Watched(
		"Watched",
		"Prism runs it, shows that it is running, lets you stop it, and shows its error output if it fails.",
	),
}

/** Where something an action opens is opened. */
enum class Opens(
	val label: String,
	val word: String?,
) {
	Usual("Default", null),
	Here("This window", "here"),
	Window("New window", "window"),
}

/** A question an action asks, as typed: the [name] its answer is used by, whether it is for a folder, and its prompt. */
data class AskForm(
	val name: String = "",
	val folder: Boolean = false,
	val prompt: String = "",
	val start: String = "",
) {
	fun spec(): AskSpec =
		AskSpec(
			text = prompt.takeIf { !folder },
			folder = prompt.takeIf { folder },
			start = start.ifBlank { null },
		)

	companion object {
		fun of(name: String, spec: AskSpec): AskForm =
			AskForm(
				name,
				spec.folder != null,
				spec.folder ?: spec.text.orEmpty(),
				spec.start.orEmpty(),
			)
	}
}

/** An action as its window edits it. [fresh] is whether it is one being made, whose id is still its own to choose. */
data class ActionForm(
	val fresh: Boolean = true,
	val id: String = "",
	val label: String = "",
	val submenu: String = "",
	val key: String = "",
	val default: Boolean = false,
	val conditions: WhenForm = WhenForm(),
	val does: Does = Does.Run,
	// A command.
	val ways: List<WayForm> = listOf(WayForm()),
	val asks: List<AskForm> = emptyList(),
	val running: Running = Running.Detached,
	val confirm: String = "",
	val reread: Boolean = false,
	val choose: String = "",
	val bringFrom: String = "",
	val bringTo: String = "",
	// A folder.
	val folder: String = "",
	val folderChoose: String = "",
	// A place.
	val place: String = "",
	val placeWith: Map<String, String> = emptyMap(),
	val opens: Opens = Opens.Usual,
	// Another action.
	val like: String = "",
	val given: Map<String, String> = emptyMap(),
) {
	/** This, called [label]: and, while it is being made and its id is still the one its name gave it, by that id. */
	fun named(label: String, taken: Set<String>): ActionForm =
		copy(
			label = label,
			id =
				if (fresh && (id.isEmpty() || id == fresh(idFrom(this.label), taken)))
					fresh(idFrom(label), taken)
				else
					id,
		)

	/** What this comes to, as the preferences write it. */
	fun spec(): ActionSpec {
		val one = WayForm.single(ways)

		return ActionSpec(
			id = id.trim(),
			label = label.trim(),
			conditions = conditions.spec(On.Files),
			ask =
				asks
					.takeIf { does == Does.Run }
					.orEmpty()
					.associate { it.name.trim() to it.spec() },
			given = given.filterValues { it.isNotBlank() }.takeIf { does == Does.Like }.orEmpty(),
			run = one.takeIf { does == Does.Run },
			ways =
				ways
					.takeIf { does == Does.Run && one == null }
					.orEmpty()
					.map { it.spec() },
			show =
				when (does) {
					Does.Folder ->
						ShowSpec(
							folder = folder,
							choose = folderChoose.ifBlank { null },
							within = opens.word,
						)
					Does.Place ->
						ShowSpec(
							place = place,
							with = placeWith.filterValues { it.isNotBlank() },
							within = opens.word,
						)
					Does.Run, Does.Like -> null
				},
			like = like.takeIf { does == Does.Like },
			how =
				HowSpec(
					terminal = does == Does.Run && running == Running.Terminal,
					watched = does == Does.Run && running == Running.Watched,
					confirm = confirm.ifBlank { null },
				),
			then =
				when {
					does == Does.Run && running == Running.Watched ->
						ThenSpec(
							reread,
							choose.ifBlank { null },
							BringSpec(bringFrom, bringTo)
								.takeIf { bringFrom.isNotBlank() && bringTo.isNotBlank() },
						)
					does == Does.Like -> ThenSpec(choose = choose.ifBlank { null })
					else -> ThenSpec()
				},
			submenu = submenu.trim().ifEmpty { null },
			key = key.trim().ifEmpty { null },
			default = default,
		)
	}

	companion object {
		/** [spec], to be changed. */
		fun of(spec: ActionSpec, fresh: Boolean = false): ActionForm =
			ActionForm(
				fresh = fresh,
				id = spec.id,
				label = spec.label,
				submenu = spec.submenu.orEmpty(),
				key = spec.key.orEmpty(),
				default = spec.default,
				conditions = WhenForm.of(spec.conditions, On.Files),
				does =
					when {
						spec.like != null -> Does.Like
						spec.show?.place != null -> Does.Place
						spec.show != null -> Does.Folder
						else -> Does.Run
					},
				ways = WayForm.of(spec),
				asks = spec.ask.map { (name, ask) -> AskForm.of(name, ask) },
				running =
					when {
						spec.how.watched -> Running.Watched
						spec.how.terminal -> Running.Terminal
						else -> Running.Detached
					},
				confirm = spec.how.confirm.orEmpty(),
				reread = spec.then.reread,
				choose = spec.then.choose.orEmpty(),
				bringFrom =
					spec.then.bring
						?.from
						.orEmpty(),
				bringTo =
					spec.then.bring
						?.to
						.orEmpty(),
				folder = spec.show?.folder.orEmpty(),
				folderChoose = spec.show?.choose.orEmpty(),
				place = spec.show?.place.orEmpty(),
				placeWith = spec.show?.with.orEmpty(),
				opens = Opens.entries.firstOrNull { it.word == spec.show?.within } ?: Opens.Usual,
				like = spec.like.orEmpty(),
				given = spec.given,
			)
	}
}

/** Something turned on or off beside a text search's query, as typed: its words either way, each a command line. */
data class OptionForm(
	val name: String = "",
	val label: String = "",
	val on: String = "",
	val off: String = "",
) {
	fun spec(): OptionSpec = OptionSpec(label, wordsOf(on), wordsOf(off))

	companion object {
		fun of(name: String, spec: OptionSpec): OptionForm =
			OptionForm(name, spec.label, textOf(spec.on), textOf(spec.off))
	}
}

/** A text search as its window edits it. */
data class SearchForm(
	val fresh: Boolean = true,
	val id: String = "",
	val label: String = "",
	val submenu: String = "",
	val key: String = "",
	val conditions: WhenForm = WhenForm(target = null),
	val takes: Takes = Takes.Files,
	val query: QueryUse = QueryUse.Typed,
	val options: List<OptionForm> = emptyList(),
	val ways: List<WayForm> = listOf(WayForm()),
	val read: HitRead = HitRead.Lines,
	val nul: Boolean = false,
	val positions: Positions = Positions.Lines,
	/** The commands that show a file beside a line found; prism reads the file itself, where there is none. */
	val previews: List<WayForm> = emptyList(),
	/** Whether opening a line found is this search's own to say, and not the editor's. */
	val opening: Boolean = false,
	val open: CommandForm = CommandForm.Line(),
	val openInTerminal: Boolean = false,
	/** What was written, for how it wrote what can be written two ways. */
	val kept: TextSearchSpec? = null,
) {
	/** This, called [label]: and, while it is being made and its id is still the one its name gave it, by that id. */
	fun named(label: String, taken: Set<String>): SearchForm =
		copy(
			label = label,
			id =
				if (fresh && (id.isEmpty() || id == fresh(idFrom(this.label), taken)))
					fresh(idFrom(label), taken)
				else
					id,
		)

	fun spec(): TextSearchSpec {
		val one = WayForm.single(ways)
		val shown = WayForm.single(previews)

		return TextSearchSpec(
			id = id.trim(),
			label = label.trim(),
			conditions = conditions.spec(null),
			submenu = submenu.trim().ifEmpty { null },
			key = key.trim().ifEmpty { null },
			// Each said only where it is not what is meant unsaid, or was said.
			query = query.word.takeIf { query != QueryUse.Typed || kept?.query != null },
			options = options.associate { it.name.trim() to it.spec() },
			run = one,
			ways =
				ways
					.takeIf { one == null }
					.orEmpty()
					.map { it.spec() },
			takes = takes.word.takeIf { takes != Takes.Files || kept?.takes != null },
			read = read.word.takeIf { read != HitRead.Lines || kept?.read != null },
			nul = nul,
			positions =
				positions.word.takeIf {
					positions != Positions.Lines ||
						kept?.positions != null
				},
			preview = shown,
			previews =
				previews
					.takeIf { shown == null }
					.orEmpty()
					.map { it.spec() },
			open = OpenSpec(open.words, openInTerminal).takeIf { opening },
		)
	}

	companion object {
		fun of(spec: TextSearchSpec, fresh: Boolean = false): SearchForm =
			SearchForm(
				fresh = fresh,
				id = spec.id,
				label = spec.label,
				submenu = spec.submenu.orEmpty(),
				key = spec.key.orEmpty(),
				conditions = WhenForm.of(spec.conditions, null),
				takes = Takes.entries.firstOrNull { it.word == spec.takes } ?: Takes.Files,
				query = QueryUse.entries.firstOrNull { it.word == spec.query } ?: QueryUse.Typed,
				options = spec.options.map { (name, option) -> OptionForm.of(name, option) },
				ways = WayForm.of(spec),
				read = HitRead.entries.firstOrNull { it.word == spec.read } ?: HitRead.Lines,
				nul = spec.nul,
				positions =
					Positions.entries.firstOrNull { it.word == spec.positions } ?: Positions.Lines,
				previews =
					spec.preview?.let { listOf(WayForm(command = CommandForm.of(it))) }
						?: spec.previews.map(WayForm::of),
				opening = spec.open != null,
				open = CommandForm.of(spec.open?.run.orEmpty()),
				openInTerminal = spec.open?.terminal ?: false,
				kept = spec,
			)
	}
}

// ---- in a person's words ---------------------------------------------------------------------------------------------

/** The first few of [words], and how many more there are. */
private fun few(words: List<String>, most: Int = 4): String =
	if (words.size <= most)
		words.joinToString(", ")
	else
		words.take(most).joinToString(", ") + " and ${words.size - most} more"

/** What [spec] is for, as few words as say it: "one file", "two or more folders", "items". */
private fun thingsSaid(spec: WhenSpec): String {
	val (one, many) =
		when (spec.kind) {
			"file" -> "file" to "files"
			"folder" -> "folder" to "folders"
			"link" -> "link" to "links"
			else -> "item" to "items"
		}

	return when (spec.count) {
		"one" -> "one $one"
		"some" -> "one or more $many"
		"many" -> "two or more $many"
		else -> "any $many"
	}
}

/**
 * When [spec] says something is offered, a sentence for each thing it says: which menu and which window, what is
 * chosen, and anything more. [unsaid] is what a menu is of where it does not say; [scenes] names the windows.
 */
fun offeredSaid(spec: WhenSpec, unsaid: On?, scenes: Scenes): List<String> {
	val menu =
		when (spec.target ?: unsaid?.word) {
			On.Files.word -> "In the menu of the files selected"
			On.Folder.word -> "In the menu of the folder itself (a click on empty space)"
			On.Place.word -> "In the menu of a place in the sidebar"
			else -> "In any menu"
		}

	val where =
		spec.within
			?.takeIf { it.isNotEmpty() }
			?.joinToString(" or ") { scenes.said(Scene.In(it)) }
			?: "any window"

	fun dotted(ext: List<String>) = few(ext.map { ".$it" })

	val named =
		listOfNotNull(
			spec.ext?.takeIf { it.isNotEmpty() }?.let { "ending in ${dotted(it)}" },
			spec.names?.takeIf { it.isNotEmpty() }?.let { "named like ${few(it)}" },
			spec.types?.takeIf { it.isNotEmpty() }?.let { "of type ${few(it)}" },
		).joinToString(" or ")

	val not = spec.not

	// What is chosen is said where the conditions say anything of it, and of a menu of files always.
	val things =
		when {
			named.isNotEmpty() || spec.count != null || spec.kind != null -> "For ${thingsSaid(
				spec,
			)}"
			(spec.target ?: unsaid?.word) == On.Files.word -> "For anything selected"
			else -> null
		}

	return listOfNotNull(
		"$menu, in $where.",
		things?.let {
			it +
				named
					.takeIf { said -> said.isNotEmpty() }
					?.let { said -> " $said" }
					.orEmpty() +
				not
					?.ext
					?.takeIf { ext -> ext.isNotEmpty() }
					?.let { ext -> ", but not ${dotted(ext)}" }
					.orEmpty() + "."
		},
		not
			?.takeIf { it.copy(ext = null) != WhenSpec() }
			?.let { "With further exceptions, set in the preferences file." },
		spec.real?.let {
			if (it)
				"Only for files on disk."
			else
				"Only for entries of a place, which are not files on disk."
		},
		spec.elsewhere?.let {
			if (it)
				"Only where they are shown outside their own folder, as in search results."
			else
				"Only where they are shown in their own folder."
		},
		spec.under?.takeIf { it.isNotEmpty() }?.let { "Only under ${few(it)}." },
		spec.inside?.takeIf { it.isNotEmpty() }?.let {
			"Only inside a folder that contains ${few(it)}."
		},
		spec.programs?.takeIf { it.isNotEmpty() }?.let { programs ->
			"Only when ${few(programs.map { it.replace("|", " or ") })} is installed."
		},
		spec.with?.takeIf { it.isNotEmpty() }?.let { with ->
			"Only where the place's " +
				with.entries.joinToString(
					" and ",
				) { (name, globs) -> "$name is like ${few(globs)}" } +
				"."
		},
	)
}

/** What [spec] is for, in a line: "One file · .zip, .jar and 13 more". Nothing, for what is for anything anywhere. */
fun offeredBrief(spec: WhenSpec, unsaid: On?): String {
	val target =
		when (spec.target ?: unsaid?.word) {
			On.Folder.word -> "Folder background"
			On.Place.word -> "Sidebar place"
			else -> null
		}

	return listOfNotNull(
		target,
		thingsSaid(spec)
			.takeIf { spec.count != null || spec.kind != null }
			?.removePrefix("any ")
			?.replaceFirstChar { it.uppercase() },
		spec.ext?.takeIf { it.isNotEmpty() }?.let { ext -> few(ext.map { ".$it" }, 3) },
		spec.names?.takeIf { it.isNotEmpty() }?.let { few(it, 3) },
		spec.types?.takeIf { it.isNotEmpty() }?.let { few(it, 3) },
		spec.not?.ext?.takeIf { it.isNotEmpty() }?.let { ext ->
			"not ${few(ext.map { ".$it" }, 2)}"
		},
		spec.inside?.takeIf { it.isNotEmpty() }?.let { "inside ${few(it, 2)}" },
		spec.under?.takeIf { it.isNotEmpty() }?.let { "under ${few(it, 2)}" },
	).joinToString(" · ")
}

/** What [spec] does, in a few words, for a list's row: the programs it runs, or what it opens. */
fun doesBrief(spec: Declared, labelOf: (String) -> String?): String? =
	when {
		spec is ActionSpec && spec.like != null -> "Same as “${labelOf(
			spec.like!!,
		) ?: spec.like}”"
		spec is ActionSpec && spec.show?.place != null -> "Opens the place ${spec.show?.place}"
		spec is ActionSpec && spec.show != null -> "Opens ${spec.show?.folder}"
		else ->
			programsOf(spec)
				// A program that goes by several names is said by its first: which it is here is not the point.
				.map { way -> way.joinToString(" and ") { it.substringBefore('|') } }
				.filter { it.isNotEmpty() }
				.distinct()
				.takeIf { it.isNotEmpty() }
				?.let { "Runs ${it.joinToString(" or ")}" }
				?: (spec.run ?: spec.ways.firstOrNull()?.run)
					?.firstOrNull()
					?.let { "Runs $it" }
	}
