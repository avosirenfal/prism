package prism.launcher

import prism.desktop.Application
import prism.desktop.ApplicationAction
import prism.index.query.Own
import prism.window.OpenWindow

/*
 * How well what is typed fits an application. What is typed is words; every word must be found in something the
 * application says of itself, and each counts for the best place it is found in: its name before what kind of program
 * it is, that before the words it asks to be found by, and those before what it says it is for. Where in the text a
 * word is found counts too: all of it, its start, the start of a word in it (or the first letters of its words: `vsc`
 * is Visual Studio Code), or only somewhere inside.
 */

/** The words of [query], in lower case. */
internal fun wordsOf(query: String): List<String> =
	query.lowercase().split(' ', '\t').filter { it.isNotEmpty() }

/** How a word is found in a text, from best to worst. */
private enum class Place(
	val points: Int,
) {
	Whole(100),
	Start(80),
	WordStart(60),
	Initials(55),
	Inside(35),
}

/**
 * Something an application says of itself, and how much being found there counts, of a hundred. What a word is
 * looked for in (the text in lower case, and its words' first letters) is worked out as this is made, once, and not
 * for every word of every key typed.
 */
internal class Said(
	val text: String,
	val worth: Int,
) {
	val lower: String = text.lowercase()

	/** The first letter of each word of the text, in lower case: `vsc`, of Visual Studio Code. */
	val initials: String =
		text
			.split(BETWEEN_WORDS)
			.filter { it.isNotEmpty() }
			.joinToString("") { it.take(1).lowercase() }

	private companion object {
		val BETWEEN_WORDS = Regex("[^\\p{L}\\p{N}]+")
	}
}

/** Where [word] is found in what is [said]: the best place; null where it is not there at all. */
private fun placeOf(word: String, said: Said): Place? {
	val text = said.text
	val lower = said.lower
	val at = lower.indexOf(word)

	fun startsWord(index: Int) =
		index == 0 || !lower[index - 1].isLetterOrDigit() ||
			(text[index].isUpperCase() && text[index - 1].isLowerCase())

	val atWordStart =
		generateSequence(at) { lower.indexOf(word, it + 1) }
			.takeWhile { it >= 0 }
			.any(::startsWord)

	return when {
		at < 0 -> Place.Initials.takeIf { word.length > 1 && said.initials.startsWith(word) }
		lower == word -> Place.Whole
		at == 0 -> Place.Start
		atWordStart -> Place.WordStart
		else -> Place.Inside
	}
}

/** How well [words] fit what is [said]: each word's best, added; null where a word is found nowhere. */
private fun scoreOf(words: List<String>, said: List<Said>): Int? {
	if (words.isEmpty())
		return 0

	val each =
		words.map { word ->
			said
				.mapNotNull {
					placeOf(word, it)?.let { place ->
						place.points * it.worth / 100
					}
				}.maxOrNull()
		}

	return if (each.any { it == null })
		null
	else
		each.filterNotNull().sum()
}

/** How well [words] fit a text that is all there is of something ([said]: a line of a dmenu); null where they do not. */
internal fun scoreOf(words: List<String>, said: Said): Int? = scoreOf(words, listOf(said))

/** What [application] says of itself. */
private fun saidBy(application: Application): List<Said> =
	listOfNotNull(
		Said(application.name, 100),
		application.generic?.let { Said(it, 60) },
		// What it is run by: `code`, of Visual Studio Code.
		application.command
			.firstOrNull()
			?.substringAfterLast('/')
			?.let { Said(it, 55) },
		application.comment?.let { Said(it, 25) },
	) + application.keywords.map { Said(it, 50) }

/**
 * An application as the launcher looks through it: what it says of itself, and each thing it can be started to do,
 * made ready to be looked through when it is read, and not again for every key typed.
 */
class Listed internal constructor(
	val application: Application,
	windows: List<OpenWindow> = emptyList(),
) {
	internal val entry = Entry.App(application, windows)
	internal val said: List<Said> = saidBy(application)

	/** Its name, as what one of its actions is told by. */
	internal val owning = Said(application.name, 70)

	/** Its name in lower case: what applications are in the order of, with nothing typed. */
	internal val sortName: String = application.name.lowercase()
	internal val actions: List<Doing> = application.actions.map { Doing(application, it) }

	/** Something else the application can be started to do, and its name as it is looked through. */
	internal class Doing(
		application: Application,
		action: ApplicationAction,
	) {
		val entry = Entry.Action(application, action)
		val name = Said(action.name, 90)
	}

	override fun equals(other: Any?): Boolean = other is Listed && entry == other.entry

	override fun hashCode(): Int = entry.hashCode()

	override fun toString(): String = application.name
}

/** How well [words] fit [listed]; null where they do not. */
internal fun scoreOf(words: List<String>, listed: Listed): Int? =
	scoreOf(words, listed.said)

/**
 * How well [words] fit [action] of [listed]: found by the action's own name, with the application's to tell whose
 * it is, and a little behind the application itself, so that `firefox` is Firefox before it is its private window.
 * An action is found only where a word is in its own name, or what is typed is its application's whole name: `libre`
 * finds the five programs of LibreOffice, and not, after them, everything each of them can be started to do.
 */
internal fun scoreOf(words: List<String>, listed: Listed, action: Listed.Doing): Int? =
	scoreOf(words, listOf(action.name, listed.owning))
		?.takeIf {
			words.any { placeOf(it, action.name) != null } ||
				placeOf(words.joinToString(" "), listed.owning) == Place.Whole
		}?.let { it - 5 }

/**
 * What prism does that is found as an application is, by what is typed fitting what it says of itself, and listed
 * after the applications with nothing typed: a window on the home folder, and the launcher's settings. The settings
 * are the same entry the word `prism` asks for ([Entry.Prism]), found here by their own words too.
 */
internal class Does private constructor(
	val entry: Entry,
	keywords: List<String>,
) {
	private val said: List<Said> =
		listOf(Said(entry.title, 100), Said(entry.detail, 25)) + keywords.map { Said(it, 50) }

	/** How well [words] fit it; null where they do not. */
	fun scoreOf(words: List<String>): Int? = scoreOf(words, said)

	companion object {
		val ALL: List<Does> =
			LauncherCommand.entries.map { Does(Entry.Command(it), it.keywords) } +
				Does(Entry.Prism(Own.Settings), Own.Settings.words + listOf("launcher", "configure"))
	}
}
