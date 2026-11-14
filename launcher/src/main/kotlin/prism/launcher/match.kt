package prism.launcher

import prism.desktop.Application
import prism.desktop.ApplicationAction

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

/** Something an application says of itself, and how much being found there counts, of a hundred. */
private class Said(
	val text: String,
	val worth: Int,
)

/** Where [word] is found in [text]: the best place; null where it is not there at all. */
private fun placeOf(word: String, text: String): Place? {
	val lower = text.lowercase()
	val at = lower.indexOf(word)

	fun startsWord(index: Int) =
		index == 0 || !lower[index - 1].isLetterOrDigit() ||
			(text[index].isUpperCase() && text[index - 1].isLowerCase())

	val atWordStart =
		generateSequence(at) { lower.indexOf(word, it + 1) }
			.takeWhile { it >= 0 }
			.any(::startsWord)

	return when {
		at < 0 -> Place.Initials.takeIf { word.length > 1 && initialsOf(text).startsWith(word) }
		lower == word -> Place.Whole
		at == 0 -> Place.Start
		atWordStart -> Place.WordStart
		else -> Place.Inside
	}
}

/** The first letter of each word of [text], in lower case: `vsc`, of Visual Studio Code. */
private fun initialsOf(text: String): String =
	text
		.split(Regex("[^\\p{L}\\p{N}]+"))
		.filter { it.isNotEmpty() }
		.joinToString("") { it.take(1).lowercase() }

/** How well [words] fit what is [said]: each word's best, added; null where a word is found nowhere. */
private fun scoreOf(words: List<String>, said: List<Said>): Int? {
	if (words.isEmpty())
		return 0

	val each =
		words.map { word ->
			said
				.mapNotNull {
					placeOf(word, it.text)?.let { place ->
						place.points * it.worth / 100
					}
				}.maxOrNull()
		}

	return if (each.any { it == null })
		null
	else
		each.filterNotNull().sum()
}

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

/** How well [words] fit [application]; null where they do not. */
internal fun scoreOf(words: List<String>, application: Application): Int? =
	scoreOf(words, saidBy(application))

/**
 * How well [words] fit [action] of [application]: found by the action's own name, with the application's to tell whose
 * it is, and a little behind the application itself, so that `firefox` is Firefox before it is its private window.
 * An action is found only where a word is in its own name, or what is typed is its application's whole name: `libre`
 * finds the five programs of LibreOffice, and not, after them, everything each of them can be started to do.
 */
internal fun scoreOf(
	words: List<String>,
	application: Application,
	action: ApplicationAction,
): Int? =
	scoreOf(words, listOf(Said(action.name, 90), Said(application.name, 70)))
		?.takeIf {
			words.any { placeOf(it, action.name) != null } ||
				placeOf(words.joinToString(" "), application.name) == Place.Whole
		}?.let { it - 5 }

/** How well [words] fit [command]; null where they do not. */
internal fun scoreOf(words: List<String>, command: LauncherCommand): Int? =
	scoreOf(
		words,
		listOf(Said(command.label, 100), Said(command.detail, 25)) +
			command.keywords.map { Said(it, 50) },
	)
