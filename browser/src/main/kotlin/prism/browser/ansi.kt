package prism.browser

import androidx.compose.runtime.Immutable

/*
 * Text as a terminal would colour it, read into text and how each part of it is drawn. Programs that colour what
 * they print (`grep --color`, `bat`) do it with escape sequences among the text (ECMA-48's "select graphic
 * rendition": ESC `[`, numbers apart by `;`, `m`). Read here, the sequences are gone from the text and what they said
 * is kept beside it, as the runs of the text each way of drawing ([Ink]) covers.
 *
 * It is how a text search's results come to be highlighted without the program that found them knowing anything of
 * prism (docs/text.md): what a program draws differently in a line it found is what it matched there, and a file it
 * printed coloured is that file with its syntax coloured.
 *
 * For example, `grep --color=always` prints a match as `ESC[01;31mESC[K` + the match + `ESC[mESC[K`: read, that is
 * the match alone, in an ink that is bold and red.
 */

/** A colour a terminal's text is given: one of its palette's, by number, or one said outright. */
@Immutable
sealed interface AnsiColor {
	/** One of the palette's: 0 to 7 the eight colours, 8 to 15 their bright ones, and on to 255 as xterm numbers them. */
	data class Indexed(
		val index: Int,
	) : AnsiColor

	data class Rgb(
		val red: Int,
		val green: Int,
		val blue: Int,
	) : AnsiColor
}

/** How a run of text is drawn, as the escape sequences before it said. */
@Immutable
data class Ink(
	val color: AnsiColor? = null,
	val background: AnsiColor? = null,
	val bold: Boolean = false,
	val faint: Boolean = false,
	val italic: Boolean = false,
	val underlined: Boolean = false,
	/** Its colour and its background each drawn as the other. */
	val inverse: Boolean = false,
) {
	/** Whether it draws text as text is drawn anyway. */
	val plain: Boolean get() = this == PLAIN

	companion object {
		val PLAIN = Ink()
	}
}

/** A run of a line, by where its characters are in it, and how it is drawn. */
@Immutable
data class Inked(
	val range: IntRange,
	val ink: Ink,
)

/** A line of text, and the runs of it that are drawn other than plainly, in order, none over another. */
@Immutable
data class InkedLine(
	val text: String,
	val runs: List<Inked> = emptyList(),
)

private const val ESCAPE = '\u001B'

/** The escape sequences of [text] that do something other than colour it: gone as well, and saying nothing. */
private val OTHER_SEQUENCE =
	Regex(
		"""\u001B(?:\[[0-9;?]*[ -/]*[@-ln-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\)|[@-Z\\^_])""",
	)

/**
 * [line] read, starting in [ink] (what the line before left the text in): its text, the runs of it that are not
 * plain, and the ink it leaves the text in, for the line after.
 */
fun inked(line: String, ink: Ink = Ink.PLAIN): Pair<InkedLine, Ink> {
	if (ESCAPE !in line)
		return InkedLine(line, runOf(0, line.length, ink)) to ink

	val cleaned = OTHER_SEQUENCE.replace(line, "")
	val text = StringBuilder(cleaned.length)
	val runs = ArrayList<Inked>()
	var current = ink
	var since = 0
	var at = 0

	while (at < cleaned.length) {
		val sequence =
			if (cleaned[at] == ESCAPE)
				sequenceAt(cleaned, at)
			else
				null

		if (sequence == null) {
			// An escape that begins nothing known is not text either.
			if (cleaned[at] != ESCAPE)
				text.append(cleaned[at])
			at++
		} else {
			runs += runOf(since, text.length, current)
			since = text.length
			current = sequence.codes.applied(current)
			at = sequence.end
		}
	}

	return InkedLine(text.toString(), runs + runOf(since, text.length, current)) to current
}

/** Every line of [text] read, each starting in the ink the one before left. */
fun inkedLines(text: Sequence<String>): Sequence<InkedLine> =
	sequence {
		var ink = Ink.PLAIN

		for (line in text) {
			val (read, left) = inked(line, ink)

			ink = left
			yield(read)
		}
	}

private fun runOf(from: Int, to: Int, ink: Ink): List<Inked> =
	if (to > from && !ink.plain)
		listOf(Inked(from until to, ink))
	else
		emptyList()

/** A sequence that colours, read: its numbers, and where what follows it starts. */
private class Colouring(
	val codes: List<Int>,
	val end: Int,
)

/** The colouring sequence that starts at [at] in [text], where one does: ESC `[`, numbers apart by `;` or `:`, `m`. */
private fun sequenceAt(text: String, at: Int): Colouring? {
	if (text.getOrNull(at + 1) != '[')
		return null

	val end =
		(at + 2 until text.length).firstOrNull { text[it] !in "0123456789;:" } ?: return null

	if (text[end] != 'm')
		return null

	// No number at all is a zero, which puts everything back.
	return Colouring(
		text.substring(at + 2, end).split(';', ':').map { it.toIntOrNull() ?: 0 },
		end + 1,
	)
}

/** [ink] as these numbers change it, each in turn. */
private fun List<Int>.applied(ink: Ink): Ink {
	var next = ink
	var at = 0

	/** The colour the numbers from [from] say, after a 38 or a 48, and how many numbers that took. */
	fun extended(from: Int): Pair<AnsiColor?, Int> =
		when (getOrNull(from)) {
			5 -> getOrNull(from + 1)?.takeIf { it in 0..255 }?.let(AnsiColor::Indexed) to 2
			2 ->
				(
					if (size >= from + 4)
						AnsiColor.Rgb(
							this[from + 1].coerceIn(0, 255),
							this[from + 2].coerceIn(0, 255),
							this[from + 3].coerceIn(0, 255),
						)
					else
						null
				) to 4
			else -> null to 0
		}

	while (at < size) {
		val code = this[at]

		next =
			when (code) {
				0 -> Ink.PLAIN
				1 -> next.copy(bold = true)
				2 -> next.copy(faint = true)
				3 -> next.copy(italic = true)
				4 -> next.copy(underlined = true)
				7 -> next.copy(inverse = true)
				22 -> next.copy(bold = false, faint = false)
				23 -> next.copy(italic = false)
				24 -> next.copy(underlined = false)
				27 -> next.copy(inverse = false)
				in 30..37 -> next.copy(color = AnsiColor.Indexed(code - 30))
				39 -> next.copy(color = null)
				in 40..47 -> next.copy(background = AnsiColor.Indexed(code - 40))
				49 -> next.copy(background = null)
				in 90..97 -> next.copy(color = AnsiColor.Indexed(code - 90 + 8))
				in 100..107 -> next.copy(background = AnsiColor.Indexed(code - 100 + 8))
				38, 48 -> {
					val (colour, taken) = extended(at + 1)

					at += taken
					when {
						colour == null -> next
						code == 38 -> next.copy(color = colour)
						else -> next.copy(background = colour)
					}
				}
				else -> next
			}
		at++
	}

	return next
}
