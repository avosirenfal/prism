package prism.browser

import androidx.compose.runtime.Immutable
import java.nio.file.Path

/*
 * A text search as steps, one after another (docs/text.md): each is given what the one before it gave, and what the
 * last gives is what the window lists. So "the files that have A in them and B too" is two steps, the second looking
 * in the files the first found; and it is the same two steps whatever programs do the looking.
 *
 * What goes from a step to the next is a [TextFlow]: files, with the lines found in them. A step is one of three kinds, by
 * what it takes and what it gives:
 *
 * - a search that takes **files** ([Takes.Files]) finds lines in them: what it gives is those lines, under their
 *   files. One that prints files and no lines ([HitRead.Paths]) gives the files it printed, with what lines they had
 *   coming in;
 * - a search that takes **lines** ([Takes.Lines]) keeps some of the lines it is given;
 * - a query of the files' **names** ([TextStep.Names]) keeps the files it finds, with their lines. It runs no command: it
 *   is the index's own language, over the files given and no others.
 *
 * A search can be turned about ([TextStep.Search.without]): it then gives what it was given and did not find, which is
 * the files it found no line in, or the lines it did not keep. Since that is said of what it was given, the first step
 * cannot be: what it is given may be folders, and which files are in them only its command knows.
 *
 * Nothing here runs anything, or changes anything: these are the values, and what each step makes of what it found.
 * Which step runs next, and what its ending comes to, are the reducer's (texting.kt).
 *
 * For example, `strings` over two binaries, then `rg` over what it printed: the first step gives every string, under
 * the binary it is in, at the byte it starts at; the second is given those lines as text, says which it keeps by
 * their numbers, and gives those, each still under its binary and at its byte.
 */

/** A step of a text search: what it looks for, in what it is given. */
@Immutable
sealed interface TextStep {
	/** What is typed for it. */
	val typed: String

	/** This, with [text] typed for it. */
	fun typing(text: String): TextStep

	/**
	 * [search], looking for [query] with [on] of its options on. [without], it gives what it was given and found
	 * nothing in.
	 */
	data class Search(
		val search: TextSearch,
		val query: String = "",
		val on: Set<String> = emptySet(),
		val without: Boolean = false,
	) : TextStep {
		override val typed: String get() = query

		override fun typing(text: String): TextStep = copy(query = text)

		/** What its command is run for: what is typed, where it looks for that; nothing, where what is typed narrows. */
		val sought: String get() =
			if (search.query == QueryUse.Typed)
				query
			else
				""

		/** Whether it has nothing to look for, and so gives what it was given: a search for what is typed, with nothing typed. */
		val idle: Boolean get() = search.query == QueryUse.Typed && query.isBlank()

		/** Whether what is typed changes what its command is run for, and not only what is made of what it found. */
		fun reruns(other: Search): Boolean =
			copy(query = sought) != other.copy(query = other.sought)
	}

	/** The files whose names, and where they are, [text] finds: a query in the language the index is searched in. */
	data class Names(
		val text: String = "",
	) : TextStep {
		override val typed: String get() = text

		override fun typing(text: String): TextStep = copy(text = text)
	}
}

/**
 * What a step is given, and gives: files, with the lines found in them ([hits]), and the search whose lines those are
 * ([source]), which says what a line's number is and how its file is shown. None, where no lines have been found:
 * files alone.
 *
 * Equal only to itself.
 */
class TextFlow(
	val hits: Hits,
	val source: TextSearch?,
) {
	override fun toString(): String = "TextFlow($hits, ${source?.id})"

	companion object {
		val NONE = TextFlow(Hits.NONE, null)
	}
}

/** What a step that has run came to: how it ended, what it found ([raw]), and what it gives of that ([out]). */
@Immutable
data class StepDone(
	val ending: TextEnding,
	val raw: Hits,
	val out: TextFlow,
) {
	/** Why it failed, where it did. */
	val failure: String? get() = (ending as? TextEnding.Failed)?.why
}

/** A step, and what it came to, once it has run since anything before it changed. */
@Immutable
data class Stage(
	val step: TextStep,
	val done: StepDone? = null,
)

/** What a step under way waits for. */
@Immutable
sealed interface Awaited {
	/** Its command, run. */
	data class Run(
		val run: TextRun,
	) : Awaited

	/** Which files its query finds. */
	data class Names(
		val ask: FilterAsk,
	) : Awaited
}

/** The step under way: which ([index]), what it waits for, and what its command has found so far. */
@Immutable
data class Under(
	val index: Int,
	val awaited: Awaited,
	val found: Hits = Hits.NONE,
) {
	/** Its command's run, where it runs one. */
	val run: TextRun? get() = (awaited as? Awaited.Run)?.run
}

/**
 * What [step] gives, having found [raw] in what it was given ([input]; nothing, for the first step, which is given
 * what the window showed).
 */
fun given(step: TextStep.Search, input: TextFlow?, raw: Hits): TextFlow {
	val search = step.search
	// What is typed narrows what it found, where that is what typing does to it.
	val found =
		if (search.query == QueryUse.Narrows)
			raw.narrowed(Hits.wordsOf(step.query))
		else
			raw

	return when {
		// The lines it kept are lines of whatever found them first.
		search.takes == Takes.Lines -> TextFlow(found, input?.source)
		// Files and no lines of them: the files it names, with whatever lines they came with.
		search.way.printed is Printed.Paths || step.without ->
			TextFlow(input?.hits?.restricted(found.paths.toSet()) ?: found, input?.source)
		else -> TextFlow(found, search)
	}
}

/** What a step that has nothing to look for gives: what it was given, or nothing where it was given none. */
fun passed(input: TextFlow?): StepDone =
	(input ?: TextFlow.NONE).let { StepDone(TextEnding.Done(), it.hits, it) }

/** What a query of names gives of [input], having found [admitted] of its files. */
fun named(input: TextFlow, admitted: Set<Path>): TextFlow =
	TextFlow(input.hits.restricted(admitted), input.source)

/** The files a step is given to look in, where it is given what the step before it gave: those, by [said]. */
fun scopeOf(input: TextFlow, said: String, folder: Path): TextScope =
	TextScope(said, folder, input.hits.paths)
